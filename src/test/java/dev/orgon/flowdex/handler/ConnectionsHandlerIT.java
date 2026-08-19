package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orgon.flowdex.LocalStackBase;
import dev.orgon.flowdex.store.IndexStore;
import dev.orgon.flowdex.store.RawStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionsHandlerIT extends LocalStackBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectionsHandler handler;

    @BeforeEach
    void seed() throws Exception {
        new IngestHandler(new IndexStore(ddb(), table()), new RawStore(s3(), bucket()))
                .handleRequest(new APIGatewayProxyRequestEvent()
                        .withBody(Files.readString(Path.of("samples/conn-sample.ndjson"), StandardCharsets.UTF_8)),
                        new TestContext("seed"));
        handler = new ConnectionsHandler(new IndexStore(ddb(), table()));
    }

    @Test
    void returnsOrientedRowsForTheRequestedAddress() throws Exception {
        JsonNode body = invoke(Map.of("ip", "10.0.0.9",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 200);

        JsonNode first = body.get("items").get(0);
        assertThat(first.get("uid").asText()).isEqualTo("CHhAvV");
        assertThat(first.get("role").asText()).isEqualTo("resp");
        assertThat(first.get("peer").asText()).isEqualTo("10.0.0.5");
        assertThat(first.get("bytesOut").asLong()).isEqualTo(8192L);
        assertThat(first.get("bytesIn").asLong()).isEqualTo(512L);
        assertThat(first.get("ts").asText()).isEqualTo("2026-08-18T14:03:22.451Z");
        assertThat(first.get("s3Key").asText()).startsWith("raw/dt=");
    }

    @Test
    void theUpperBoundIsExclusiveAndTheLowerBoundInclusive() throws Exception {
        JsonNode body = invoke(Map.of("ip", "10.0.0.5",
                "from", "2026-08-18T14:03:22.451Z", "to", "2026-08-18T15:22:40.318Z"), 200);

        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("uid").asText()).isEqualTo("CHhAvV");
    }

    @Test
    void paginationVisitsEveryRowExactlyOnce() throws Exception {
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            Map<String, String> qs = new HashMap<>(Map.of("ip", "10.0.0.5",
                    "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z", "limit", "1"));
            if (cursor != null) qs.put("cursor", cursor);
            JsonNode body = invoke(qs, 200);
            body.get("items").forEach(i -> assertThat(seen.add(i.get("uid").asText())).isTrue());
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
            if (cursor == null) break;
        }
        assertThat(seen).containsExactlyInAnyOrder("CHhAvV", "CmES5u", "C4J4Th");
    }

    @Test
    void anUnknownAddressReturnsAnEmptyPageNotAnError() throws Exception {
        JsonNode body = invoke(Map.of("ip", "203.0.113.7",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 200);
        assertThat(body.get("items")).isEmpty();
        assertThat(body.hasNonNull("nextCursor")).isFalse();
    }

    @Test
    void invalidParametersAre400WithACode() throws Exception {
        assertThat(invoke(Map.of("from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 400)
                .get("error").get("code").asText()).isEqualTo("MISSING_PARAM");
        assertThat(invoke(Map.of("ip", "not-an-ip",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 400)
                .get("error").get("code").asText()).isEqualTo("INVALID_IP");
        assertThat(invoke(Map.of("ip", "10.0.0.5",
                "from", "2026-08-19T00:00:00Z", "to", "2026-08-18T00:00:00Z"), 400)
                .get("error").get("code").asText()).isEqualTo("INVALID_RANGE");
    }

    /**
     * FIX 1: a row ingested under one spelling of an IPv6 address must be
     * findable under a different, equally-valid spelling of the same host.
     * Built inline rather than added to samples/conn-sample.ndjson, which
     * other tests assert exact counts and totals against.
     */
    @Test
    void ipv6EndpointIngestedOneSpellingIsFoundQueriedWithAnotherSpelling() throws Exception {
        String line = "{\"ts\":1787061900.000,\"uid\":\"Cv6Rec1\","
                + "\"id.orig_h\":\"10.0.0.5\",\"id.orig_p\":54321,"
                + "\"id.resp_h\":\"2001:db8::1\",\"id.resp_p\":443,"
                + "\"proto\":\"tcp\",\"service\":\"ssl\",\"duration\":1.0,"
                + "\"orig_bytes\":10,\"resp_bytes\":20,\"conn_state\":\"SF\"}";
        new IngestHandler(new IndexStore(ddb(), table()), new RawStore(s3(), bucket()))
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(line), new TestContext("seed-v6"));

        JsonNode body = invoke(Map.of("ip", "2001:DB8::1",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 200);

        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("uid").asText()).isEqualTo("Cv6Rec1");
    }

    @Test
    void aCursorFromAnotherAddressIsRejected() throws Exception {
        JsonNode firstPage = invoke(Map.of("ip", "10.0.0.5",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z", "limit", "1"), 200);
        String cursor = firstPage.get("nextCursor").asText();

        Map<String, String> qs = new HashMap<>(Map.of("ip", "10.0.0.9",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z", "cursor", cursor));
        assertThat(invoke(qs, 400).get("error").get("code").asText()).isEqualTo("INVALID_CURSOR");
    }

    /**
     * FIX 3: CursorCodec.decode only checks the cursor's PK against the
     * address, not whether its SK falls inside a narrower range the caller
     * supplies on the next page. DynamoDB rejects that ExclusiveStartKey
     * server-side; that must become 400 INVALID_CURSOR, not 500.
     */
    @Test
    void aCursorReusedAgainstANarrowerRangeIsRejectedNotA500() throws Exception {
        JsonNode firstPage = invoke(Map.of("ip", "10.0.0.5",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z", "limit", "1"), 200);
        String cursor = firstPage.get("nextCursor").asText();

        Map<String, String> qs = new HashMap<>(Map.of("ip", "10.0.0.5",
                "from", "2026-08-18T14:00:00Z", "to", "2026-08-18T14:01:00Z", "cursor", cursor));
        JsonNode body = invoke(qs, 400);
        assertThat(body.get("error").get("code").asText()).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void integerFieldsAreIntegers() throws Exception {
        JsonNode body = invoke(Map.of("ip", "10.0.0.9",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 200);

        JsonNode item = body.get("items").get(0);
        assertThat(item.get("localPort").isIntegralNumber()).isTrue();
        assertThat(item.get("peerPort").isIntegralNumber()).isTrue();
        assertThat(item.get("bytesOut").isIntegralNumber()).isTrue();
        assertThat(item.get("bytesIn").isIntegralNumber()).isTrue();
        assertThat(item.get("s3Line").isIntegralNumber()).isTrue();
        assertThat(item.get("localPort").isFloatingPointNumber()).isFalse();
    }

    @Test
    void durationStaysFloatingPointEvenWhenWhole() throws Exception {
        JsonNode body = invoke(Map.of("ip", "192.168.1.2",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 200);

        JsonNode item = body.get("items").get(0);
        assertThat(item.get("duration").isFloatingPointNumber()).isTrue();
        assertThat(item.get("duration").asDouble()).isEqualTo(30.0);
    }

    @Test
    void nextCursorIsAbsentNotNullOnFinalPage() throws Exception {
        JsonNode body = invoke(Map.of("ip", "203.0.113.7",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z"), 200);

        assertThat(body.has("nextCursor")).isFalse();
    }

    @Test
    void nextCursorIsPresentWhenMorePagesRemain() throws Exception {
        JsonNode body = invoke(Map.of("ip", "10.0.0.5",
                "from", "2026-08-18T00:00:00Z", "to", "2026-08-19T00:00:00Z", "limit", "1"), 200);

        assertThat(body.has("nextCursor")).isTrue();
        assertThat(body.get("nextCursor").asText()).isNotBlank();
    }

    private JsonNode invoke(Map<String, String> qs, int expectedStatus) throws Exception {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withQueryStringParameters(new HashMap<>(qs)),
                new TestContext("req-1"));
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        return MAPPER.readTree(response.getBody());
    }
}
