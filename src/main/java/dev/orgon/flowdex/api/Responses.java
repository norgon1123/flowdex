package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Responses {

    /** Instants are written by the callers as pre-formatted strings, so no time module surprises. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private Responses() {}

    public static APIGatewayProxyResponseEvent ok(int status, Object payload, String requestId) {
        return response(status, payload, requestId);
    }

    public static APIGatewayProxyResponseEvent error(ApiException e, String requestId) {
        Map<String, Object> details = new LinkedHashMap<>(e.details());
        details.put("requestId", requestId);
        return response(e.status(), Map.of("error", Map.of(
                "code", e.code(),
                "message", e.getMessage() == null ? "" : e.getMessage(),
                "details", details)), requestId);
    }

    public static APIGatewayProxyResponseEvent serverError(String requestId) {
        return response(500, Map.of("error", Map.of(
                "code", "INTERNAL",
                "message", "unexpected error",
                "details", Map.of("requestId", safeId(requestId)))), requestId);
    }

    /** Map.of rejects null values, and this is the one path that must never throw. */
    private static String safeId(String requestId) {
        return requestId == null ? "" : requestId;
    }

    private static APIGatewayProxyResponseEvent response(int status, Object payload, String requestId) {
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            status = 500;
            body = "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"could not serialise response\"}}";
        }
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "content-type", "application/json",
                        "x-flowdex-request-id", requestId == null ? "" : requestId))
                .withBody(body);
    }
}
