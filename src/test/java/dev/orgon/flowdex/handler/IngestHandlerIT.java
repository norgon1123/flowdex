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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestHandlerIT extends LocalStackBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IngestHandler handler;
    private String sample;

    @BeforeEach
    void setUp() throws Exception {
        handler = new IngestHandler(new IndexStore(ddb(), table()), new RawStore(s3(), bucket()));
        sample = Files.readString(Path.of("samples/conn-sample.ndjson"), StandardCharsets.UTF_8);
    }

    @Test
    void ingestWritesRowsRollupsAndTheRawObject() throws Exception {
        JsonNode body = invoke(sample, 202);

        assertThat(body.get("received").asInt()).isEqualTo(5);
        assertThat(body.get("indexed").asInt()).isEqualTo(5);
        assertThat(body.get("duplicates").asInt()).isZero();
        assertThat(body.get("malformed")).isEmpty();
        assertThat(body.get("s3Key").asText()).startsWith("raw/dt=");

        assertThat(s3().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket()).build()).contents())
                .hasSize(1);

        assertThat(get("IP#10.0.0.5", "C#2026-08-18T14:03:22.451Z#CHhAvV").get("role").s()).isEqualTo("orig");
        assertThat(get("IP#10.0.0.9", "C#2026-08-18T14:03:22.451Z#CHhAvV").get("role").s()).isEqualTo("resp");
        assertThat(get("IP#10.0.0.5", "H#2026-08-18T14").get("conns").n()).isEqualTo("2");
    }

    /** The idempotency argument, executable. */
    @Test
    void reIngestingTheSameFileChangesNoCounter() throws Exception {
        invoke(sample, 202);
        String before = get("IP#10.0.0.5", "H#2026-08-18T14").toString();

        JsonNode second = invoke(sample, 202);

        assertThat(second.get("indexed").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(5);
        assertThat(get("IP#10.0.0.5", "H#2026-08-18T14").toString()).isEqualTo(before);
        assertThat(s3().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket()).build()).contents())
                .hasSize(2);
    }

    @Test
    void aSelfConnectionProducesOneRowNotTwo() throws Exception {
        invoke(sample, 202);
        assertThat(get("IP#127.0.0.1", "H#2026-08-18T15").get("conns").n()).isEqualTo("1");
    }

    /**
     * One bad line in six is 16.7%, over the batch threshold, so the whole
     * file is refused. Half-ingesting a wrong file is worse than refusing it.
     */
    @Test
    void aFixtureWithOneBadLineInSixIsRejectedAndReportsThatLine() throws Exception {
        String withBad = Files.readString(Path.of("samples/conn-malformed.ndjson"), StandardCharsets.UTF_8);
        JsonNode body = invoke(withBad, 400);

        assertThat(body.get("error").get("code").asText()).isEqualTo("MALFORMED_BATCH");
        assertThat(body.get("error").get("details").get("received").asInt()).isEqualTo(6);
        assertThat(body.get("error").get("details").get("malformed").asInt()).isEqualTo(1);
    }

    /**
     * The per-line reason survives to the caller when the batch is accepted:
     * nineteen good lines carry one bad one, which is under the 10% threshold.
     */
    @Test
    void anAcceptedBatchReportsItsMalformedLinesIndividually() throws Exception {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 19; i++) {
            batch.append("{\"ts\":1787061802.451,\"uid\":\"Cgood").append(i)
                 .append("\",\"id.orig_h\":\"10.0.0.5\",\"id.orig_p\":54321,")
                 .append("\"id.resp_h\":\"10.0.0.9\",\"id.resp_p\":443,\"proto\":\"tcp\"}\n");
        }
        batch.append("{\"ts\":1787066800.000,\"uid\":\"CbadOne\",\"id.orig_h\":\"10.0.0.5\",")
             .append("\"id.orig_p\":9999,\"id.resp_p\":80,\"proto\":\"tcp\"}\n");

        JsonNode body = invoke(batch.toString(), 202);

        assertThat(body.get("received").asInt()).isEqualTo(20);
        assertThat(body.get("indexed").asInt()).isEqualTo(19);
        assertThat(body.get("malformed")).hasSize(1);
        assertThat(body.get("malformed").get(0).get("line").asInt()).isEqualTo(20);
        assertThat(body.get("malformed").get(0).get("reason").asText()).isEqualTo("missing id.resp_h");
    }

    @Test
    void aBatchThatIsMostlyGarbageIsRejectedWholesale() throws Exception {
        String garbage = ("this is not a zeek log\n").repeat(20);
        JsonNode body = invoke(garbage, 400);
        assertThat(body.get("error").get("code").asText()).isEqualTo("MALFORMED_BATCH");

        assertThat(s3().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket()).build()).contents())
                .isEmpty();
        assertThat(tableIsEmpty()).isTrue();
    }

    /** The 5 MB body cap is enforced before anything is persisted. */
    @Test
    void anOversizeBodyIs413AndPersistsNothing() throws Exception {
        String oversized = "x".repeat(IngestHandler.MAX_BODY_BYTES + 1);
        JsonNode body = invoke(oversized, 413);

        assertThat(body.get("error").get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(s3().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket()).build()).contents())
                .isEmpty();
        assertThat(tableIsEmpty()).isTrue();
    }

    /**
     * The record-count cap is only reachable with compact records: MAX_RECORDS
     * + 1 records at a realistic ~300 bytes each would trip the 5 MB body cap
     * first. These lines run about 105 bytes each, so the batch stays well
     * under MAX_BODY_BYTES and it is genuinely the record cap being tested.
     */
    @Test
    void tooManyRecordsIs413AndPersistsNothing() throws Exception {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i <= IngestHandler.MAX_RECORDS; i++) {
            batch.append("{\"ts\":1787061802.451,\"uid\":\"U").append(i)
                 .append("\",\"id.orig_h\":\"10.0.0.5\",\"id.orig_p\":1,")
                 .append("\"id.resp_h\":\"10.0.0.9\",\"id.resp_p\":2,\"proto\":\"udp\"}\n");
        }
        assertThat(batch.length()).isLessThan(IngestHandler.MAX_BODY_BYTES);

        JsonNode body = invoke(batch.toString(), 413);

        assertThat(body.get("error").get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(s3().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket()).build()).contents())
                .isEmpty();
        assertThat(tableIsEmpty()).isTrue();
    }

    @Test
    void everyResponseCarriesTheRequestId() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(event(sample), new TestContext("req-42"));
        assertThat(response.getHeaders()).containsEntry("x-flowdex-request-id", "req-42");
    }

    private JsonNode invoke(String body, int expectedStatus) throws Exception {
        APIGatewayProxyResponseEvent response = handler.handleRequest(event(body), new TestContext("req-1"));
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        return MAPPER.readTree(response.getBody());
    }

    private static APIGatewayProxyRequestEvent event(String body) {
        return new APIGatewayProxyRequestEvent().withBody(body).withHttpMethod("POST").withPath("/ingest");
    }

    private Map<String, AttributeValue> get(String pk, String sk) {
        return ddb().getItem(GetItemRequest.builder()
                .tableName(table())
                .key(Map.of("PK", AttributeValue.builder().s(pk).build(),
                            "SK", AttributeValue.builder().s(sk).build()))
                .consistentRead(true)
                .build()).item();
    }

    private boolean tableIsEmpty() {
        return ddb().scan(ScanRequest.builder()
                .tableName(table())
                .limit(1)
                .build()).items().isEmpty();
    }
}
