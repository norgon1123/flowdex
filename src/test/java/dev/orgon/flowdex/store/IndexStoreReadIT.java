package dev.orgon.flowdex.store;

import dev.orgon.flowdex.LocalStackBase;
import dev.orgon.flowdex.zeek.ConnRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexStoreReadIT extends LocalStackBase {

    private IndexStore store;

    @BeforeEach
    void seed() {
        store = new IndexStore(ddb(), table());
        write("A1", "2026-08-18T14:00:00.000Z", "10.0.0.9");
        write("A2", "2026-08-18T14:30:00.000Z", "10.0.0.9");
        write("A3", "2026-08-18T15:00:00.000Z", "8.8.8.8");
        write("A4", "2026-08-18T15:59:59.999Z", "8.8.8.8");
    }

    @Test
    void rangeIsLowerInclusiveAndUpperExclusive() {
        List<String> uids = uidsOf(store.queryConnections("10.0.0.5",
                Instant.parse("2026-08-18T14:00:00.000Z"),
                Instant.parse("2026-08-18T15:00:00.000Z"), 100, null));
        assertThat(uids).containsExactly("A1", "A2");
    }

    @Test
    void rollupRowsAreNeverReturnedByAConnectionQuery() {
        var page = store.queryConnections("10.0.0.5",
                Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z"), 1000, null);
        assertThat(page.items()).allSatisfy(i -> assertThat(i.get("SK").s()).startsWith("C#"));
    }

    @Test
    void paginationReturnsEveryRowExactlyOnce() {
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = store.queryConnections("10.0.0.5",
                    Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-19T00:00:00Z"), 1, cursor);
            page.items().forEach(i -> assertThat(seen.add(i.get("uid").s())).isTrue());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 20);

        assertThat(seen).containsExactlyInAnyOrder("A1", "A2", "A3", "A4");
    }

    @Test
    void theFinalPageHasNoCursor() {
        var page = store.queryConnections("10.0.0.5",
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-19T00:00:00Z"), 1000, null);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void edgesAreExactAndRespectTheWindow() {
        Instant from = Instant.parse("2026-08-18T14:15:00Z");
        Instant to = Instant.parse("2026-08-18T16:00:00Z");
        assertThat(store.edge("10.0.0.5", from, to, true)).contains(Instant.parse("2026-08-18T14:30:00.000Z"));
        assertThat(store.edge("10.0.0.5", from, to, false)).contains(Instant.parse("2026-08-18T15:59:59.999Z"));
    }

    @Test
    void edgesAreEmptyForAnAddressWithNoRowsInWindow() {
        assertThat(store.edge("10.0.0.5",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), true)).isEmpty();
        assertThat(store.edge("203.0.113.1",
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-19T00:00:00Z"), true)).isEmpty();
    }

    @Test
    void rollupQueryReturnsOnlyRollupRowsInTheHourRange() {
        var rollups = store.queryRollups("10.0.0.5",
                Instant.parse("2026-08-18T14:00:00Z"), Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(rollups).singleElement()
                .satisfies(r -> assertThat(r.get("SK").s()).isEqualTo("H#2026-08-18T14"));
    }

    @Test
    void peerScanReturnsPeerAndByteAttributesAndIsNotTruncatedForSmallData() {
        var scan = store.scanPeers("10.0.0.5",
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-19T00:00:00Z"));
        assertThat(scan.truncated()).isFalse();
        assertThat(scan.rows()).hasSize(4);
        assertThat(scan.rows().getFirst()).containsKeys("peer", "bytesOut", "bytesIn");
    }

    private void write(String uid, String ts, String peer) {
        ConnRecord r = new ConnRecord(Instant.parse(ts), uid, "10.0.0.5", 1234, peer, 443,
                "tcp", "ssl", 1.0, 100L, 200L, "SF", 1);
        store.writeRow(IndexRow.forSide(r, "10.0.0.5", "raw/k.gz"));
    }

    private static List<String> uidsOf(IndexStore.Page page) {
        List<String> uids = new ArrayList<>();
        page.items().forEach(i -> uids.add(i.get("uid").s()));
        return uids;
    }
}
