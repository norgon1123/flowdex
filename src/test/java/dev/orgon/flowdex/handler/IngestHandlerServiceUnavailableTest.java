package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orgon.flowdex.store.IndexStore;
import dev.orgon.flowdex.store.RawStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX 2: exhausted DynamoDB retries must surface as 503 SERVICE_UNAVAILABLE,
 * not 500 INTERNAL. No Docker, no LocalStack — hand-written fakes over
 * DynamoDbClient and S3Client, both of which are interfaces whose operation
 * methods carry default implementations (the same pattern IndexStoreRetryTest
 * uses for DynamoDbClient).
 */
class IngestHandlerServiceUnavailableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LINE =
            "{\"ts\":1787061802.451,\"uid\":\"CHhAvV\",\"id.orig_h\":\"10.0.0.5\",\"id.orig_p\":54321,"
          + "\"id.resp_h\":\"10.0.0.9\",\"id.resp_p\":443,\"proto\":\"tcp\"}";

    @Test
    void exhaustedTransactionConflictRetriesReturn503NotInternal() throws Exception {
        IndexStore index = new IndexStore(new AlwaysConflictingDdb(), "table");
        RawStore raw = new RawStore(new AlwaysSucceedingS3(), "bucket");
        IngestHandler handler = new IngestHandler(index, raw);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                new APIGatewayProxyRequestEvent().withBody(LINE), new TestContext("req-1"));

        assertThat(response.getStatusCode()).isEqualTo(503);
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("error").get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    /** Every TransactWriteItems call reports TransactionConflict, forever, so IndexStore exhausts MAX_ATTEMPTS. */
    private static class AlwaysConflictingDdb implements DynamoDbClient {
        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            throw TransactionCanceledException.builder()
                    .message("Transaction cancelled, please refer cancellation reasons for specific reasons")
                    .cancellationReasons(CancellationReason.builder().code("TransactionConflict").build())
                    .build();
        }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }

    private static class AlwaysSucceedingS3 implements S3Client {
        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            return PutObjectResponse.builder().build();
        }

        @Override public String serviceName() { return "fake-s3"; }
        @Override public void close() { }
    }
}
