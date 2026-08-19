package dev.orgon.flowdex.api;

import java.util.Map;

/** An error with an HTTP status, thrown from anywhere and rendered by Responses. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message, Map.of());
    }

    public static ApiException badRequest(String code, String message, Map<String, Object> details) {
        return new ApiException(400, code, message, details);
    }

    public static ApiException payloadTooLarge(String message) {
        return new ApiException(413, "PAYLOAD_TOO_LARGE", message, Map.of());
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(503, "SERVICE_UNAVAILABLE", message, Map.of());
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
