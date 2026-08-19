package dev.orgon.flowdex.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The single source of key-format truth.
 *
 * Fixed-width timestamps are load-bearing: they make lexicographic sort order
 * equal chronological order, which is what lets a time range be a native
 * sort-key BETWEEN with no secondary index.
 */
public final class Keys {

    public static final String CONN_PREFIX = "C#";
    public static final String ROLLUP_PREFIX = "H#";
    public static final String PROTO_PREFIX = "proto#";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private Keys() {}

    /**
     * Addresses are normalised to a single spelling before they become keys.
     *
     * IPv6 has many spellings of one address — 2001:db8::1, 2001:DB8::1, and the
     * fully expanded form are the same host — so without this, a lookup spelled
     * differently from the ingested form returns an empty page and reads as "no
     * traffic". Both the write and the read path call pk(), so normalising here is
     * what keeps them from drifting apart.
     *
     * A value that is not an IP literal is returned unchanged rather than rejected;
     * key construction is not the place to validate input.
     */
    public static String canonicalAddr(String addr) {
        if (addr == null || addr.isEmpty() || addr.indexOf(':') < 0) {
            return addr;
        }
        try {
            return java.net.InetAddress.getByName(addr).getHostAddress();
        } catch (Exception e) {
            return addr;
        }
    }

    public static String pk(String addr) { return "IP#" + canonicalAddr(addr); }

    public static String formatTs(Instant ts) { return TS.format(ts); }

    public static Instant parseTs(String ts) { return Instant.parse(ts); }

    public static String formatHour(Instant ts) { return HOUR.format(ts.truncatedTo(ChronoUnit.HOURS)); }

    /** Sort key of a stored index row. The uid suffix is what makes bounds exclusive. */
    public static String connSk(Instant ts, String uid) { return CONN_PREFIX + formatTs(ts) + "#" + uid; }

    /** Bare bound for a query: no uid suffix, so it sorts below every row at that instant. */
    public static String connBound(Instant ts) { return CONN_PREFIX + formatTs(ts); }

    public static String rollupSk(Instant ts) { return ROLLUP_PREFIX + formatHour(ts); }

    public static String rollupBound(Instant ts) { return rollupSk(ts); }

    /**
     * Rollup protocol counters are top-level attributes, not a nested map,
     * because only a top-level attribute can be incremented with ADD. The "#"
     * is legal in an attribute name as long as the name reaches DynamoDB
     * through an expression-name alias.
     */
    public static String protoAttr(String proto) { return PROTO_PREFIX + proto; }

    public static String protoNameOf(String attribute) { return attribute.substring(PROTO_PREFIX.length()); }

    public static boolean isProtoAttr(String attribute) { return attribute.startsWith(PROTO_PREFIX); }
}
