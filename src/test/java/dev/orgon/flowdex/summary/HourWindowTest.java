package dev.orgon.flowdex.summary;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HourWindowTest {

    @Test
    void aMidHourWindowRoundsOutwardInBothDirections() {
        HourWindow w = HourWindow.of(Instant.parse("2026-08-18T14:30:00Z"), Instant.parse("2026-08-18T15:30:00Z"));
        assertThat(w.fromHour()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(w.lastHour()).isEqualTo(Instant.parse("2026-08-18T15:00:00Z"));
        assertThat(w.coveredTo()).isEqualTo(Instant.parse("2026-08-18T16:00:00Z"));
    }

    /**
     * `to` is exclusive. An hour-aligned `to` of 15:00 must NOT pull in the
     * 15:00 rollup — every event it counts falls outside the window.
     */
    @Test
    void anHourAlignedExclusiveEndDoesNotPullInTheNextHour() {
        HourWindow w = HourWindow.of(Instant.parse("2026-08-18T14:00:00Z"), Instant.parse("2026-08-18T15:00:00Z"));
        assertThat(w.lastHour()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(w.coveredTo()).isEqualTo(Instant.parse("2026-08-18T15:00:00Z"));
    }

    @Test
    void aSubMillisecondWindowStillCoversItsOwnHour() {
        HourWindow w = HourWindow.of(Instant.parse("2026-08-18T14:00:00Z"), Instant.parse("2026-08-18T14:00:00.001Z"));
        assertThat(w.fromHour()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(w.lastHour()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
    }

    @Test
    void aMultiDayWindowSpansFromItsFirstHourToItsLast() {
        HourWindow w = HourWindow.of(Instant.parse("2026-08-18T23:10:00Z"), Instant.parse("2026-08-20T01:05:00Z"));
        assertThat(w.fromHour()).isEqualTo(Instant.parse("2026-08-18T23:00:00Z"));
        assertThat(w.lastHour()).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
    }
}
