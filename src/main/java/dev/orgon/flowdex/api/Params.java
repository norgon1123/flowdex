package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

public final class Params {

    public record Range(Instant from, Instant to) {}

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    /**
     * Canonical dotted-quad only: each octet 0-255, no leading zeros.
     *
     * The value bound matters as much as the character set. Java parses an
     * all-digit string below 2^32 as an unsigned-decimal IPv4 literal and sends
     * anything larger to the resolver, so a character-class screen alone still
     * lets a query parameter trigger a DNS lookup from inside the function.
     * Matching the canonical form instead means getByName is never reached with
     * an all-digit string.
     *
     * Requiring canonical spelling is also the correct read: Zeek writes
     * canonical addresses, so "2130706433" could only ever build a partition key
     * that matches nothing. A 400 beats a silent empty result.
     */
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

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
        // Canonicalise so the value the handler logs and echoes back matches
        // what Keys.pk() derived and what was actually stored under it.
        return dev.orgon.flowdex.store.Keys.canonicalAddr(addr);
    }

    /**
     * True when addr is a literal IPv4 or IPv6 address, false for anything else
     * (hostnames included). Package-visible reuse: ConnLogParser calls this to
     * reject non-literal endpoint addresses at ingest time, so a record never
     * gets indexed under a key no read path can address.
     */
    public static boolean isLiteralAddress(String addr) {
        if (IPV4.matcher(addr).matches()) {
            return true;
        }
        // Colon-bearing strings are parsed as IPv6 literals or fail fast; they
        // never reach the resolver, so the parser can confirm this branch.
        if (!addr.contains(":") || !addr.matches("[0-9a-fA-F:.]+")) {
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
