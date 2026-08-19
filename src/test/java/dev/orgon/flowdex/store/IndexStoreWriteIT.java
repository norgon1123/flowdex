package dev.orgon.flowdex.store;

import dev.orgon.flowdex.LocalStackBase;
import dev.orgon.flowdex.zeek.ConnRecord;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IndexStoreWriteIT extends LocalStackBase {

    private static final ConnRecord REC = new ConnRecord(
            Instant.parse("2026-08-18T14:03:22.451Z"), "CHhAvV",
            "10.0.0.5", 54321, "10.0.0.9", 443,
            "tcp", "ssl", 1.25, 512L, 8192L, "SF", 1);

    @Test
    void firstWriteStoresTheRowAndAdvancesTheRollup() {
        IndexStore store = new IndexStore(ddb(), table());

        assertThat(store.writeRow(IndexRow.forSide(REC, "10.0.0.5", "raw/k.gz")))
                .isEqualTo(TxnOutcome.WRITTEN);

        Map<String, AttributeValue> row = get("IP#10.0.0.5", "C#2026-08-18T14:03:22.451Z#CHhAvV");
        assertThat(row.get("role").s()).isEqualTo("orig");
        assertThat(row.get("bytesOut").n()).isEqualTo("512");

        Map<String, AttributeValue> rollup = get("IP#10.0.0.5", "H#2026-08-18T14");
        assertThat(rollup.get("conns").n()).isEqualTo("1");
        assertThat(rollup.get("bytesOut").n()).isEqualTo("512");
        assertThat(rollup.get("bytesIn").n()).isEqualTo("8192");
        assertThat(rollup.get("proto#tcp").n()).isEqualTo("1");
    }

    @Test
    void rewritingTheSameRowIsADuplicateAndMovesNoCounter() {
        IndexStore store = new IndexStore(ddb(), table());
        IndexRow row = IndexRow.forSide(REC, "10.0.0.5", "raw/k.gz");

        assertThat(store.writeRow(row)).isEqualTo(TxnOutcome.WRITTEN);
        assertThat(store.writeRow(row)).isEqualTo(TxnOutcome.DUPLICATE);
        assertThat(store.writeRow(row)).isEqualTo(TxnOutcome.DUPLICATE);

        assertThat(get("IP#10.0.0.5", "H#2026-08-18T14").get("conns").n()).isEqualTo("1");
    }

    @Test
    void protocolCountersAccumulatePerProtocolWithoutClobbering() {
        IndexStore store = new IndexStore(ddb(), table());
        store.writeRow(IndexRow.forSide(REC, "10.0.0.5", "k"));
        store.writeRow(IndexRow.forSide(udpAt("U1", "2026-08-18T14:10:00Z"), "10.0.0.5", "k"));
        store.writeRow(IndexRow.forSide(udpAt("U2", "2026-08-18T14:20:00Z"), "10.0.0.5", "k"));

        Map<String, AttributeValue> rollup = get("IP#10.0.0.5", "H#2026-08-18T14");
        assertThat(rollup.get("proto#tcp").n()).isEqualTo("1");
        assertThat(rollup.get("proto#udp").n()).isEqualTo("2");
        assertThat(rollup.get("conns").n()).isEqualTo("3");
    }

    @Test
    void recordsInDifferentHoursLandInDifferentRollups() {
        IndexStore store = new IndexStore(ddb(), table());
        store.writeRow(IndexRow.forSide(REC, "10.0.0.5", "k"));
        store.writeRow(IndexRow.forSide(udpAt("U1", "2026-08-18T15:00:00Z"), "10.0.0.5", "k"));

        assertThat(get("IP#10.0.0.5", "H#2026-08-18T14").get("conns").n()).isEqualTo("1");
        assertThat(get("IP#10.0.0.5", "H#2026-08-18T15").get("conns").n()).isEqualTo("1");
    }

    /**
     * The regression test for TransactionConflictException: 40 concurrent
     * transactions all contend on one rollup item. Without reason-inspecting
     * retry, this test loses rows.
     */
    @Test
    void concurrentWritesToOneRollupAllLand() throws Exception {
        IndexStore store = new IndexStore(ddb(), table());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<TxnOutcome>> tasks = IntStream.range(0, 40)
                    .mapToObj(i -> (Callable<TxnOutcome>) () ->
                            store.writeRow(IndexRow.forSide(
                                    udpAt("U" + i, "2026-08-18T14:00:00Z"), "10.0.0.5", "k")))
                    .toList();
            for (var future : pool.invokeAll(tasks)) {
                assertThat(future.get()).isEqualTo(TxnOutcome.WRITTEN);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(get("IP#10.0.0.5", "H#2026-08-18T14").get("conns").n()).isEqualTo("40");
    }

    private static ConnRecord udpAt(String uid, String ts) {
        return new ConnRecord(Instant.parse(ts), uid, "10.0.0.5", 33445, "8.8.8.8", 53,
                "udp", null, 0.01, 64L, 128L, null, 1);
    }

    private Map<String, AttributeValue> get(String pk, String sk) {
        return ddb().getItem(GetItemRequest.builder()
                .tableName(table())
                .key(Map.of("PK", AttributeValue.builder().s(pk).build(),
                            "SK", AttributeValue.builder().s(sk).build()))
                .consistentRead(true)
                .build()).item();
    }
}
