package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import dev.orgon.flowdex.store.Keys;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

public final class Params {

    public record Range(Instant from, Instant to) {}

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    /**
     * The summary reads one rollup row per hour in the window and pages until
     * they are all in memory, so an unbounded window is bounded only by the
     * function timeout — a 50-year request is a slow 504 rather than an answer.
     * A year is far past any plausible investigation and cheap to state.
     */
    public static final Duration MAX_SUMMARY_WINDOW = Duration.ofDays(366);

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

    /**
     * Bounds are truncated to milliseconds HERE rather than left to be truncated
     * implicitly by key formatting later.
     *
     * Sort keys carry three fractional digits, so any finer precision a caller
     * sends is lost the moment the bound becomes a key — silently, and only on
     * one of the two paths, since the summary echoes its window back and
     * /connections does not. Truncating up front means the value that is
     * validated, the value that is echoed, and the value that is queried with
     * are the same value. The API's granularity is milliseconds; sub-millisecond
     * input is rounded down to it, not honoured.
     */
    public static Range requireRange(Map<String, String> qs) {
        Instant from = parseInstant(get(qs, "from"), "from").truncatedTo(ChronoUnit.MILLIS);
        Instant to = parseInstant(get(qs, "to"), "to").truncatedTo(ChronoUnit.MILLIS);
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_RANGE",
                    "from must be strictly before to, at millisecond granularity");
        }
        return new Range(from, to);
    }

    /** The summary's window, additionally capped at {@link #MAX_SUMMARY_WINDOW}. */
    public static Range requireSummaryRange(Map<String, String> qs) {
        Range range = requireRange(qs);
        if (Duration.between(range.from(), range.to()).compareTo(MAX_SUMMARY_WINDOW) > 0) {
            throw ApiException.badRequest("WINDOW_TOO_LARGE",
                    "summary window may not exceed " + MAX_SUMMARY_WINDOW.toDays() + " days");
        }
        return range;
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
        return Keys.canonicalAddr(addr);
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
        Instant parsed;
        try {
            parsed = Instant.parse(raw);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_TIMESTAMP", name + " must be an ISO-8601 instant, e.g. 2026-08-18T14:00:00Z");
        }
        // Instant.parse happily accepts year 10000 and negative years, which no
        // fixed-width key can express. See Keys.isRepresentable.
        if (!Keys.isRepresentable(parsed)) {
            throw ApiException.badRequest("INVALID_TIMESTAMP",
                    name + " must fall between " + Keys.MIN_TS + " and " + Keys.MAX_TS);
        }
        return parsed;
    }
}
