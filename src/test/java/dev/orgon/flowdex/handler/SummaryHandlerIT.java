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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryHandlerIT extends LocalStackBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SummaryHandler handler;

    @BeforeEach
    void seed() throws Exception {
        new IngestHandler(new IndexStore(ddb(), table()), new RawStore(s3(), bucket()))
                .handleRequest(new APIGatewayProxyRequestEvent()
                        .withBody(Files.readString(Path.of("samples/conn-sample.ndjson"), StandardCharsets.UTF_8)),
                        new TestContext("seed"));
        handler = new SummaryHandler(new IndexStore(ddb(), table()));
    }

    @Test
    void summaryMathMatchesTheHandComputedFixture() throws Exception {
        JsonNode body = invoke("10.0.0.5", "2026-08-18T14:00:00Z", "2026-08-18T16:00:00Z", 200);

        assertThat(body.get("addr").asText()).isEqualTo("10.0.0.5");
        assertThat(body.get("connections").asLong()).isEqualTo(3);
        assertThat(body.get("bytesOut").asLong()).isEqualTo(832);
        assertThat(body.get("bytesIn").asLong()).isEqualTo(9344);
        assertThat(body.get("protocols").get("tcp").asLong()).isEqualTo(2);
        assertThat(body.get("protocols").get("udp").asLong()).isEqualTo(1);
        assertThat(body.get("firstSeen").asText()).isEqualTo("2026-08-18T14:03:22.451Z");
        assertThat(body.get("lastSeen").asText()).isEqualTo("2026-08-18T15:22:40.318Z");
        assertThat(body.get("truncated").asBoolean()).isFalse();

        JsonNode topPeer = body.get("topPeers").get(0);
        assertThat(topPeer.get("addr").asText()).isEqualTo("10.0.0.9");
        assertThat(topPeer.get("connections").asLong()).isEqualTo(2);
        assertThat(topPeer.get("bytesOut").asLong()).isEqualTo(768);
        assertThat(topPeer.get("bytesIn").asLong()).isEqualTo(9216);
    }

    @Test
    void windowIsEchoedAndCoveredWindowStatesWhatTheCountersDescribe() throws Exception {
        JsonNode body = invoke("10.0.0.5", "2026-08-18T14:30:00Z", "2026-08-18T15:30:00Z", 200);

        assertThat(body.get("window").get("from").asText()).isEqualTo("2026-08-18T14:30:00.000Z");
        assertThat(body.get("window").get("to").asText()).isEqualTo("2026-08-18T15:30:00.000Z");
        assertThat(body.get("windowCovered").get("from").asText()).isEqualTo("2026-08-18T14:00:00.000Z");
        assertThat(body.get("windowCovered").get("to").asText()).isEqualTo("2026-08-18T16:00:00.000Z");
    }

    /**
     * Counters are hour-granular and edges are exact, so they need not
     * reconcile: this window's counters include the whole 14:00 hour while
     * firstSeen respects the requested 14:10 start.
     */
    @Test
    void countersAreHourGranularWhileEdgesAreExact() throws Exception {
        JsonNode body = invoke("10.0.0.5", "2026-08-18T14:05:00Z", "2026-08-18T15:00:00Z", 200);

        // Counters come from the whole 14:00 rollup, so both of that hour's
        // connections are counted even though one of them predates 14:05.
        assertThat(body.get("connections").asLong()).isEqualTo(2);
        // firstSeen comes from an index row and respects the window exactly.
        assertThat(body.get("firstSeen").asText()).isEqualTo("2026-08-18T14:07:01.002Z");
        assertThat(body.get("lastSeen").asText()).isEqualTo("2026-08-18T14:07:01.002Z");
        // to is exclusive and hour-aligned, so the 15:00 rollup is not pulled in.
        assertThat(body.get("windowCovered").get("to").asText()).isEqualTo("2026-08-18T15:00:00.000Z");
    }

    @Test
    void anUnknownAddressReturnsAZeroedTwoHundred() throws Exception {
        JsonNode body = invoke("203.0.113.7", "2026-08-18T14:00:00Z", "2026-08-18T16:00:00Z", 200);

        assertThat(body.get("connections").asLong()).isZero();
        assertThat(body.get("bytesOut").asLong()).isZero();
        assertThat(body.get("protocols")).isEmpty();
        assertThat(body.get("topPeers")).isEmpty();
        assertThat(body.get("firstSeen").isNull()).isTrue();
        assertThat(body.get("lastSeen").isNull()).isTrue();
    }

    @Test
    void aMissingOrInvalidAddressIs400() throws Exception {
        APIGatewayProxyResponseEvent noAddr = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withQueryStringParameters(
                        new HashMap<>(Map.of("from", "2026-08-18T14:00:00Z", "to", "2026-08-18T16:00:00Z"))),
                new TestContext("r"));
        assertThat(noAddr.getStatusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(noAddr.getBody()).get("error").get("code").asText()).isEqualTo("MISSING_PARAM");

        assertThat(invoke("not-an-ip", "2026-08-18T14:00:00Z", "2026-08-18T16:00:00Z", 400)
                .get("error").get("code").asText()).isEqualTo("INVALID_IP");
    }

    private JsonNode invoke(String addr, String from, String to, int expectedStatus) throws Exception {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent()
                        .withPathParameters(new HashMap<>(Map.of("addr", addr)))
                        .withQueryStringParameters(new HashMap<>(Map.of("from", from, "to", to))),
                new TestContext("req-1"));
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        return MAPPER.readTree(response.getBody());
    }
}
