package dev.orgon.flowdex.summary;

import dev.orgon.flowdex.api.Params;
import dev.orgon.flowdex.store.IndexStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryBuilderTest {

    private static final Params.Range RANGE = new Params.Range(
            Instant.parse("2026-08-18T14:30:00Z"), Instant.parse("2026-08-18T15:30:00Z"));

    @Test
    void countersAreSummedAcrossRollupsAndProtocolMapsMerged() {
        Summary s = SummaryBuilder.build("10.0.0.5", RANGE,
                List.of(rollup(10, 1000, 2000, Map.of("tcp", 8L, "udp", 2L)),
                        rollup(5, 500, 100, Map.of("tcp", 5L))),
                new IndexStore.PeerScan(List.of(), false),
                Optional.of(Instant.parse("2026-08-18T14:31:00Z")),
                Optional.of(Instant.parse("2026-08-18T15:29:00Z")));

        assertThat(s.connections()).isEqualTo(15);
        assertThat(s.bytesOut()).isEqualTo(1500);
        assertThat(s.bytesIn()).isEqualTo(2100);
        assertThat(s.protocols()).containsExactlyInAnyOrderEntriesOf(Map.of("tcp", 13L, "udp", 2L));
    }

    @Test
    void windowIsEchoedAndCoveredWindowIsHourAligned() {
        Summary s = SummaryBuilder.build("10.0.0.5", RANGE, List.of(),
                new IndexStore.PeerScan(List.of(), false), Optional.empty(), Optional.empty());

        assertThat(s.from()).isEqualTo(RANGE.from());
        assertThat(s.to()).isEqualTo(RANGE.to());
        assertThat(s.coveredFrom()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(s.coveredTo()).isEqualTo(Instant.parse("2026-08-18T16:00:00Z"));
    }

    @Test
    void anAddressWithNoDataYieldsAZeroedSummaryNotAnError() {
        Summary s = SummaryBuilder.build("203.0.113.1", RANGE, List.of(),
                new IndexStore.PeerScan(List.of(), false), Optional.empty(), Optional.empty());

        assertThat(s.addr()).isEqualTo("203.0.113.1");
        assertThat(s.connections()).isZero();
        assertThat(s.bytesOut()).isZero();
        assertThat(s.protocols()).isEmpty();
        assertThat(s.topPeers()).isEmpty();
        assertThat(s.firstSeen()).isNull();
        assertThat(s.lastSeen()).isNull();
        assertThat(s.truncated()).isFalse();
    }

    /**
     * SummaryBuilder's half of truncation only: the flag is decided by
     * IndexStore.scanPeers and carried through here untouched, so this asserts
     * the carry-through and the top-10 cap, not the budget logic. Whether the
     * flag is set correctly in the first place — including the case where the
     * page budget runs out exactly at the true end of the data — is
     * IndexStorePeerScanTest's job, against a fake that can control page counts
     * and LastEvaluatedKey presence directly.
     */
    @Test
    void topPeersAreCappedAtTenAndTheTruncationFlagIsCarriedThrough() {
        List<Map<String, AttributeValue>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            for (int j = 0; j <= i; j++) {
                rows.add(Map.of("peer", AttributeValue.builder().s("p" + i).build()));
            }
        }
        Summary s = SummaryBuilder.build("10.0.0.5", RANGE, List.of(),
                new IndexStore.PeerScan(rows, true), Optional.empty(), Optional.empty());

        assertThat(s.topPeers()).hasSize(10);
        assertThat(s.truncated()).isTrue();
    }

    private static Map<String, AttributeValue> rollup(long conns, long out, long in, Map<String, Long> proto) {
        Map<String, AttributeValue> item = new java.util.LinkedHashMap<>();
        item.put("conns", AttributeValue.builder().n(Long.toString(conns)).build());
        item.put("bytesOut", AttributeValue.builder().n(Long.toString(out)).build());
        item.put("bytesIn", AttributeValue.builder().n(Long.toString(in)).build());
        proto.forEach((k, v) -> item.put(
                dev.orgon.flowdex.store.Keys.protoAttr(k),
                AttributeValue.builder().n(Long.toString(v)).build()));
        return item;
    }
}
