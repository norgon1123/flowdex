package dev.orgon.flowdex.store;

import dev.orgon.flowdex.zeek.ConnRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IndexRowTest {

    private static final ConnRecord REC = new ConnRecord(
            Instant.parse("2026-08-18T14:03:22.451Z"), "CHhAvV",
            "10.0.0.5", 54321, "10.0.0.9", 443,
            "tcp", "ssl", 1.25, 512L, 8192L, "SF", 7);

    @Test
    void originatorSideSeesItsOwnBytesAsOut() {
        IndexRow row = IndexRow.forSide(REC, "10.0.0.5", "raw/k.gz");
        assertThat(row.role()).isEqualTo("orig");
        assertThat(row.peer()).isEqualTo("10.0.0.9");
        assertThat(row.localPort()).isEqualTo(54321);
        assertThat(row.peerPort()).isEqualTo(443);
        assertThat(row.bytesOut()).isEqualTo(512L);
        assertThat(row.bytesIn()).isEqualTo(8192L);
    }

    @Test
    void responderSideSeesTheSameBytesSwapped() {
        IndexRow row = IndexRow.forSide(REC, "10.0.0.9", "raw/k.gz");
        assertThat(row.role()).isEqualTo("resp");
        assertThat(row.peer()).isEqualTo("10.0.0.5");
        assertThat(row.localPort()).isEqualTo(443);
        assertThat(row.peerPort()).isEqualTo(54321);
        assertThat(row.bytesOut()).isEqualTo(8192L);
        assertThat(row.bytesIn()).isEqualTo(512L);
    }

    @Test
    void bothSidesShareTheSameProvenance() {
        assertThat(IndexRow.forSide(REC, "10.0.0.5", "raw/k.gz").s3Line()).isEqualTo(7);
        assertThat(IndexRow.forSide(REC, "10.0.0.9", "raw/k.gz").s3Key()).isEqualTo("raw/k.gz");
    }

    @Test
    void aSelfConnectionYieldsOneEndpointNotTwo() {
        ConnRecord self = new ConnRecord(Instant.parse("2026-08-18T14:00:00Z"), "Cloop",
                "127.0.0.1", 40000, "127.0.0.1", 8080, "tcp", "http", 0.0, 10L, 20L, "SF", 1);
        assertThat(IndexRow.endpointsOf(self)).containsExactly("127.0.0.1");
    }

    @Test
    void anOrdinaryConnectionYieldsBothEndpointsOriginatorFirst() {
        assertThat(IndexRow.endpointsOf(REC)).containsExactly("10.0.0.5", "10.0.0.9");
    }

    @Test
    void absentOptionalStringsAreOmittedFromTheItemRatherThanStoredEmpty() {
        ConnRecord noService = new ConnRecord(REC.ts(), REC.uid(), REC.origH(), REC.origP(),
                REC.respH(), REC.respP(), "udp", null, 0.0, 0L, 0L, null, 1);
        var item = IndexRow.forSide(noService, "10.0.0.5", "raw/k.gz").item();
        assertThat(item).doesNotContainKeys("service", "connState");
        assertThat(item.get("PK").s()).isEqualTo("IP#10.0.0.5");
        assertThat(item.get("SK").s()).isEqualTo("C#2026-08-18T14:03:22.451Z#CHhAvV");
    }
}
