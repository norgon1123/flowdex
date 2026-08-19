package dev.orgon.flowdex.summary;

import dev.orgon.flowdex.api.Params;
import dev.orgon.flowdex.store.IndexStore;
import dev.orgon.flowdex.store.Keys;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Counters come from hour-granular rollups; firstSeen, lastSeen and topPeers
 * come from index rows and are exact within the requested window. The two sets
 * of numbers therefore need not reconcile, which is why the response reports
 * both the requested window and the window the counters actually cover.
 */
public final class SummaryBuilder {

    public static final int TOP_PEERS = 10;

    private SummaryBuilder() {}

    public static Summary build(String addr,
                                Params.Range range,
                                List<Map<String, AttributeValue>> rollups,
                                IndexStore.PeerScan peers,
                                Optional<Instant> firstSeen,
                                Optional<Instant> lastSeen) {
        HourWindow covered = HourWindow.of(range.from(), range.to());

        long conns = 0;
        long bytesOut = 0;
        long bytesIn = 0;
        Map<String, Long> protocols = new LinkedHashMap<>();

        for (Map<String, AttributeValue> rollup : rollups) {
            conns += number(rollup, "conns");
            bytesOut += number(rollup, "bytesOut");
            bytesIn += number(rollup, "bytesIn");
            rollup.forEach((attribute, value) -> {
                if (Keys.isProtoAttr(attribute) && value.n() != null) {
                    protocols.merge(Keys.protoNameOf(attribute), Long.parseLong(value.n()), Long::sum);
                }
            });
        }

        return new Summary(
                addr,
                range.from(),
                range.to(),
                covered.fromHour(),
                covered.coveredTo(),
                conns,
                bytesOut,
                bytesIn,
                Map.copyOf(protocols),
                PeerTally.top(peers.rows(), TOP_PEERS),
                firstSeen.orElse(null),
                lastSeen.orElse(null),
                peers.truncated());
    }

    private static long number(Map<String, AttributeValue> item, String field) {
        AttributeValue v = item.get(field);
        return v == null || v.n() == null ? 0L : Long.parseLong(v.n());
    }
}
