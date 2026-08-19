package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serverErrorWithNullRequestIdDoesNotThrowAndReturnsAWellFormed500() {
        assertThatCode(() -> {
            APIGatewayProxyResponseEvent r = Responses.serverError(null);
            assertThat(r.getStatusCode()).isEqualTo(500);
            JsonNode body = MAPPER.readTree(r.getBody());
            assertThat(body.get("error").get("code").asText()).isEqualTo("INTERNAL");
        }).doesNotThrowAnyException();
    }

    @Test
    void errorWithNullRequestIdDoesNotThrow() {
        assertThatCode(() -> {
            APIGatewayProxyResponseEvent r = Responses.error(ApiException.badRequest("X", "boom"), null);
            assertThat(r.getStatusCode()).isEqualTo(400);
        }).doesNotThrowAnyException();
    }

    @Test
    void apiExceptionWithNullMessageStillProducesAWellFormedBody() throws Exception {
        ApiException e = new ApiException(400, "NO_MSG", null, Map.of());
        APIGatewayProxyResponseEvent r = Responses.error(e, "req-1");
        assertThat(r.getStatusCode()).isEqualTo(400);
        JsonNode body = MAPPER.readTree(r.getBody());
        assertThat(body.get("error").get("message").asText()).isEqualTo("");
    }

    @Test
    void okCarriesTheRequestIdHeader() {
        APIGatewayProxyResponseEvent r = Responses.ok(202, Map.of("a", 1), "req-1");
        assertThat(r.getHeaders()).containsEntry("x-flowdex-request-id", "req-1");
    }
}
