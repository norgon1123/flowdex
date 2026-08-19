package dev.orgon.flowdex.summary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Summary(
        String addr,
        Instant from,
        Instant to,
        Instant coveredFrom,
        Instant coveredTo,
        long connections,
        long bytesOut,
        long bytesIn,
        Map<String, Long> protocols,
        List<PeerStat> topPeers,
        Instant firstSeen,
        Instant lastSeen,
        boolean truncated) {
}
