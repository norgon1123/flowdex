package dev.orgon.flowdex.summary;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact within the scanned rows; the caller reports whether the scan was complete. */
public final class PeerTally {

    private PeerTally() {}

    public static List<PeerStat> top(List<Map<String, AttributeValue>> rows, int n) {
        Map<String, long[]> byPeer = new LinkedHashMap<>();
        for (Map<String, AttributeValue> row : rows) {
            AttributeValue peer = row.get("peer");
            if (peer == null || peer.s() == null) {
                continue;
            }
            long[] acc = byPeer.computeIfAbsent(peer.s(), k -> new long[3]);
            acc[0] += 1;
            acc[1] += number(row, "bytesOut");
            acc[2] += number(row, "bytesIn");
        }

        List<PeerStat> stats = new ArrayList<>(byPeer.size());
        byPeer.forEach((addr, acc) -> stats.add(new PeerStat(addr, acc[0], acc[1], acc[2])));
        stats.sort(Comparator.comparingLong(PeerStat::connections).reversed()
                .thenComparing(PeerStat::addr));
        return stats.size() <= n ? List.copyOf(stats) : List.copyOf(stats.subList(0, n));
    }

    private static long number(Map<String, AttributeValue> row, String field) {
        AttributeValue v = row.get(field);
        return v == null || v.n() == null ? 0L : Long.parseLong(v.n());
    }
}
