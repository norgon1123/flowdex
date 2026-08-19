package dev.orgon.flowdex.zeek;

import java.time.Instant;

/** One parsed Zeek conn.log record. service and connState may be null. */
public record ConnRecord(
        Instant ts,
        String uid,
        String origH,
        int origP,
        String respH,
        int respP,
        String proto,
        String service,
        double duration,
        long origBytes,
        long respBytes,
        String connState,
        int line) {
}
