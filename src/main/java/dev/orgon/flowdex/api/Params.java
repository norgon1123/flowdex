package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;

public final class Params {

    public record Range(Instant from, Instant to) {}

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private Params() {}

    public static String requireIp(Map<String, String> qs) {
        return validateAddr(get(qs, "ip"));
    }

    public static String pathAddr(APIGatewayProxyRequestEvent event) {
        Map<String, String> path = event.getPathParameters();
        String addr = path == null ? null : path.get("addr");
        if (addr == null || addr.isBlank()) {
            throw ApiException.badRequest("MISSING_PARAM", "addr is required");
        }
        return validateAddr(addr);
    }

    public static Range requireRange(Map<String, String> qs) {
        Instant from = parseInstant(get(qs, "from"), "from");
        Instant to = parseInstant(get(qs, "to"), "to");
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "from must be strictly before to");
        }
        return new Range(from, to);
    }

    public static int limit(Map<String, String> qs) {
        String raw = qs == null ? null : qs.get("limit");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be an integer");
        }
        if (value < 1) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be at least 1");
        }
        return Math.min(value, MAX_LIMIT);
    }

    private static String get(Map<String, String> qs, String name) {
        String v = qs == null ? null : qs.get(name);
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("MISSING_PARAM", name + " is required");
        }
        return v.strip();
    }

    private static String validateAddr(String addr) {
        if (!isLiteralAddress(addr)) {
            throw ApiException.badRequest("INVALID_IP", "not a literal IP address: " + addr);
        }
        return addr;
    }

    /**
     * Literal addresses only. InetAddress.getByName would resolve a hostname,
     * turning a query parameter into a DNS lookup from inside the function --
     * so screen the shape first, and only then let the parser confirm it.
     *
     * InetAddress.ofLiteral would be the direct expression of this, but it
     * arrived in Java 22 and this project targets 21.
     */
    private static boolean isLiteralAddress(String addr) {
        boolean looksV4 = addr.matches("[0-9.]+");
        boolean looksV6 = addr.matches("[0-9a-fA-F:.]+") && addr.contains(":");
        if (!looksV4 && !looksV6) {
            return false;
        }
        try {
            InetAddress.getByName(addr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Instant parseInstant(String raw, String name) {
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_TIMESTAMP", name + " must be an ISO-8601 instant, e.g. 2026-08-18T14:00:00Z");
        }
    }
}
