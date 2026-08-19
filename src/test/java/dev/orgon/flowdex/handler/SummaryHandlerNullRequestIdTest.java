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
 * FIX 4: same null-requestId gap as ConnectionsHandler, in SummaryHandler.
 * Both handlers built Map.of("requestId", requestId, ...) with no guard, so a
 * null id threw inside the catch block itself and no response was produced.
 */
class SummaryHandlerNullRequestIdTest {

    @Test
    void nullRequestIdStillYieldsAWellFormedResponse() {
        IndexStore index = new IndexStore(new EmptyResultsDdb(), "table");
        SummaryHandler handler = new SummaryHandler(index);

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withPathParameters(Map.of("addr", "10.0.0.5"))
                .withQueryStringParameters(Map.of(
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
