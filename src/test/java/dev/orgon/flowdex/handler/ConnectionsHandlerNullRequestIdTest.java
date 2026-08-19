package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import dev.orgon.flowdex.store.IndexStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FIX 4: context.getAwsRequestId() can return null (Lambda's own test console
 * does this). ConnectionsHandler used to build Map.of("requestId", requestId, ...)
 * both inside the try and inside the catch with no null guard — Map.of throws
 * on a null value, and when that NPE originates inside the catch block itself
 * it escapes handleRequest entirely, producing NO response at all.
 */
class ConnectionsHandlerNullRequestIdTest {

    @Test
    void nullRequestIdStillYieldsAWellFormedResponse() {
        IndexStore index = new IndexStore(new EmptyResultsDdb(), "table");
        ConnectionsHandler handler = new ConnectionsHandler(index);

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withQueryStringParameters(Map.of(
                        "ip", "10.0.0.5",
                        "from", "2026-08-18T00:00:00Z",
                        "to", "2026-08-19T00:00:00Z"));

        assertThatCode(() -> {
            APIGatewayProxyResponseEvent response = handler.handleRequest(event, new TestContext(null));
            assertThat(response.getStatusCode()).isEqualTo(200);
        }).doesNotThrowAnyException();
    }

    private static class EmptyResultsDdb implements DynamoDbClient {
        @Override
        public QueryResponse query(QueryRequest request) {
            return QueryResponse.builder().items(List.of()).build();
        }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }
}
