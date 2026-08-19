package dev.orgon.flowdex.summary;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The hour-aligned range that rollup counters actually describe.
 *
 * Counters are hour-granular, so a 14:30-15:30 request is served by the 14:00
 * and 15:00 buckets in full. Rounding outward and saying so beats silently
 * returning numbers for a window nobody requested.
 *
 * `to` is exclusive, so the last covered hour is the hour containing the last
 * instant inside the window — one millisecond before `to`.
 */
public record HourWindow(Instant fromHour, Instant lastHour, Instant coveredTo) {

    public static HourWindow of(Instant from, Instant to) {
        Instant fromHour = from.truncatedTo(ChronoUnit.HOURS);
        Instant lastHour = to.minusMillis(1).truncatedTo(ChronoUnit.HOURS);
        if (lastHour.isBefore(fromHour)) {
            lastHour = fromHour;
        }
        return new HourWindow(fromHour, lastHour, lastHour.plus(1, ChronoUnit.HOURS));
    }
}
