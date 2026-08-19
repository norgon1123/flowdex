package dev.orgon.flowdex.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeysTest {

    @Test
    void timestampsAlwaysCarryExactlyThreeFractionalDigits() {
        assertThat(Keys.formatTs(Instant.parse("2026-08-18T14:03:22.451Z"))).isEqualTo("2026-08-18T14:03:22.451Z");
        assertThat(Keys.formatTs(Instant.parse("2026-08-18T14:03:22Z"))).isEqualTo("2026-08-18T14:03:22.000Z");
        assertThat(Keys.formatTs(Instant.parse("2026-08-18T14:03:22.4Z"))).isEqualTo("2026-08-18T14:03:22.400Z");
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrder() {
        List<Instant> chronological = List.of(
                Instant.parse("2026-08-18T09:00:00.000Z"),
                Instant.parse("2026-08-18T09:00:00.001Z"),
                Instant.parse("2026-08-18T10:00:00.000Z"),
                Instant.parse("2026-12-31T23:59:59.999Z"),
                Instant.parse("2027-01-01T00:00:00.000Z"));
        List<String> keys = chronological.stream().map(i -> Keys.connSk(i, "uid")).toList();
        List<String> sorted = new ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        assertThat(sorted).isEqualTo(keys);
    }

    @Test
    void connRowsSortBeforeRollupRowsInTheSamePartition() {
        assertThat(Keys.connSk(Instant.parse("2099-12-31T23:59:59.999Z"), "zzz"))
                .isLessThan(Keys.rollupSk(Instant.parse("1970-01-01T00:00:00.000Z")));
    }

    @Test
    void aRecordAtTheUpperBoundSortsAboveTheBareBoundAndIsExcluded() {
        Instant to = Instant.parse("2026-08-18T15:00:00.000Z");
        assertThat(Keys.connSk(to, "anyUid")).isGreaterThan(Keys.connBound(to));
    }

    @Test
    void aRecordAtTheLowerBoundSortsAtOrAboveTheBareBoundAndIsIncluded() {
        Instant from = Instant.parse("2026-08-18T14:00:00.000Z");
        assertThat(Keys.connSk(from, "anyUid")).isGreaterThan(Keys.connBound(from));
    }

    @Test
    void rollupKeysAreHourTruncated() {
        assertThat(Keys.rollupSk(Instant.parse("2026-08-18T14:59:59.999Z"))).isEqualTo("H#2026-08-18T14");
        assertThat(Keys.rollupSk(Instant.parse("2026-08-18T14:00:00.000Z"))).isEqualTo("H#2026-08-18T14");
    }

    @Test
    void partitionKeyIsPrefixed() {
        assertThat(Keys.pk("10.0.0.5")).isEqualTo("IP#10.0.0.5");
    }

    @Test
    void protocolAttributeNamesRoundTrip() {
        assertThat(Keys.protoAttr("tcp")).isEqualTo("proto#tcp");
        assertThat(Keys.isProtoAttr("proto#tcp")).isTrue();
        assertThat(Keys.isProtoAttr("conns")).isFalse();
        assertThat(Keys.protoNameOf(Keys.protoAttr("udp"))).isEqualTo("udp");
    }

    @Test
    void parseTsRoundTripsFormatTs() {
        Instant i = Instant.parse("2026-08-18T14:03:22.451Z");
        assertThat(Keys.parseTs(Keys.formatTs(i))).isEqualTo(i);
    }

    /**
     * FIX 1: three spellings of the same IPv6 address must produce one
     * identical partition key. Before canonicalAddr existed, pk() used the
     * address verbatim, so a row stored under "2001:db8::1" was unreachable
     * under "2001:DB8::1" or the fully expanded form — a silent empty page.
     */
    @Test
    void threeSpellingsOfOneIpv6AddressProduceOneIdenticalPk() {
        String canonical = Keys.pk("2001:db8::1");
        assertThat(Keys.pk("2001:DB8::1")).isEqualTo(canonical);
        assertThat(Keys.pk("2001:0db8:0000:0000:0000:0000:0000:0001")).isEqualTo(canonical);
    }

    @Test
    void canonicalAddrLeavesIpv4AndNonIpValuesUntouched() {
        assertThat(Keys.canonicalAddr("10.0.0.5")).isEqualTo("10.0.0.5");
        assertThat(Keys.canonicalAddr("not-an-ip")).isEqualTo("not-an-ip");
        assertThat(Keys.canonicalAddr(null)).isNull();
        assertThat(Keys.canonicalAddr("")).isEqualTo("");
    }
}
