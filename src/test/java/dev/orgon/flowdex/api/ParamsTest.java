package dev.orgon.flowdex.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamsTest {

    private static Map<String, String> qs(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void acceptsValidIpv4AndIpv6() {
        assertThat(Params.requireIp(qs("ip", "10.0.0.5"))).isEqualTo("10.0.0.5");
        // IPv6 is returned in its canonical (Keys.canonicalAddr) spelling, not
        // echoed verbatim — see canonicalisesIpv6ToASingleSpelling below.
        assertThat(Params.requireIp(qs("ip", "2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void rejectsAHostnameOrGarbageAsAnIp() {
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "evil.example.com")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_IP"));
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "10.0.0.999")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsAMissingIp() {
        assertThatThrownBy(() -> Params.requireIp(qs()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("MISSING_PARAM"));
    }

    @Test
    void parsesAnIsoRange() {
        Params.Range r = Params.requireRange(qs("from", "2026-08-18T14:00:00Z", "to", "2026-08-18T16:00:00Z"));
        assertThat(r.from()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(r.to()).isEqualTo(Instant.parse("2026-08-18T16:00:00Z"));
    }

    @Test
    void rejectsAnUnparseableInstant() {
        assertThatThrownBy(() -> Params.requireRange(qs("from", "yesterday", "to", "2026-08-18T16:00:00Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_TIMESTAMP"));
    }

    @Test
    void rejectsAnEmptyOrInvertedRange() {
        assertThatThrownBy(() -> Params.requireRange(qs("from", "2026-08-18T16:00:00Z", "to", "2026-08-18T16:00:00Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_RANGE"));
        assertThatThrownBy(() -> Params.requireRange(qs("from", "2026-08-18T17:00:00Z", "to", "2026-08-18T16:00:00Z")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void limitDefaultsTo100AndCapsAt1000() {
        assertThat(Params.limit(qs())).isEqualTo(100);
        assertThat(Params.limit(qs("limit", "250"))).isEqualTo(250);
        assertThat(Params.limit(qs("limit", "5000"))).isEqualTo(1000);
    }

    @Test
    void limitRejectsZeroNegativeAndNonNumeric() {
        assertThatThrownBy(() -> Params.limit(qs("limit", "0"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Params.limit(qs("limit", "-1"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Params.limit(qs("limit", "lots"))).isInstanceOf(ApiException.class);
    }

    @Test
    void nullQueryStringMapIsTreatedAsEmpty() {
        assertThat(Params.limit(null)).isEqualTo(100);
        assertThatThrownBy(() -> Params.requireIp(null)).isInstanceOf(ApiException.class);
    }

    /**
     * 11+ digits exceeds 2^32-1, so this is not an IPv4 literal. Before the fix
     * it fell through to a real ~4s DNS lookup.
     */
    @Test
    void rejectsAnAllDigitStringLargeEnoughToReachTheResolver() {
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "11111111111")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_IP"));
    }

    @Test
    void screeningAnAllDigitStringDoesNotHitTheNetwork() {
        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "999999999999999999")))
                .isInstanceOf(ApiException.class);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(250);
    }

    @Test
    void rejectsNonCanonicalIpv4Spellings() {
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "2130706433"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "010.0.0.5"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "10.0.0.5."))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Params.requireIp(qs("ip", "10.0.0"))).isInstanceOf(ApiException.class);
    }

    @Test
    void stillAcceptsOrdinaryAddresses() {
        assertThat(Params.requireIp(qs("ip", "10.0.0.5"))).isEqualTo("10.0.0.5");
        assertThat(Params.requireIp(qs("ip", "0.0.0.0"))).isEqualTo("0.0.0.0");
        assertThat(Params.requireIp(qs("ip", "255.255.255.255"))).isEqualTo("255.255.255.255");
        assertThat(Params.requireIp(qs("ip", "2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(Params.requireIp(qs("ip", "::1"))).isEqualTo("0:0:0:0:0:0:0:1");
    }

    /**
     * FIX 1: three spellings of the same host must not just be individually
     * valid — they must return the identical canonical form, which is what
     * keeps a lookup spelled differently from ingestion from silently missing.
     */
    @Test
    void canonicalisesIpv6ToASingleSpelling() {
        String canonical = Params.requireIp(qs("ip", "2001:db8::1"));
        assertThat(Params.requireIp(qs("ip", "2001:DB8::1"))).isEqualTo(canonical);
        assertThat(Params.requireIp(qs("ip", "2001:0db8:0000:0000:0000:0000:0000:0001")))
                .isEqualTo(canonical);
    }

    /**
     * Sort keys carry three fractional digits, so finer precision is lost the
     * moment a bound becomes a key. Truncating up front means the value that is
     * validated, echoed and queried with is one value, not three.
     */
    @Test
    void rangeBoundsAreTruncatedToMillisecondGranularity() {
        Params.Range range = Params.requireRange(
                qs("from", "2026-08-18T14:00:00.0009Z", "to", "2026-08-18T15:00:00.999999Z"));

        assertThat(range.from()).isEqualTo(Instant.parse("2026-08-18T14:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-08-18T15:00:00.999Z"));
    }

    @Test
    void aRangeThatCollapsesToNothingOnceTruncatedIsRejected() {
        assertThatThrownBy(() -> Params.requireRange(
                qs("from", "2026-08-18T14:00:00.0001Z", "to", "2026-08-18T14:00:00.0009Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_RANGE"));
    }

    /**
     * Instant.parse accepts year 10000 and negative years; "uuuu" then emits a
     * leading "+" or "-", which sorts below every digit and breaks the
     * lexicographic-equals-chronological property the whole index rests on.
     */
    @Test
    void timestampsOutsideTheRepresentableYearRangeAreRejected() {
        assertThatThrownBy(() -> Params.requireRange(
                qs("from", "+10000-01-01T00:00:00Z", "to", "+10000-01-02T00:00:00Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_TIMESTAMP"));

        assertThatThrownBy(() -> Params.requireRange(
                qs("from", "1969-12-31T23:59:59Z", "to", "2026-08-18T15:00:00Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_TIMESTAMP"));
    }

    /**
     * The summary pages one rollup row per hour in the window, so an unbounded
     * window is bounded only by the function timeout — a slow 504 rather than
     * an answer.
     */
    @Test
    void anAbsurdlyWideSummaryWindowIsRejected() {
        assertThatThrownBy(() -> Params.requireSummaryRange(
                qs("from", "1970-01-01T00:00:00Z", "to", "2099-01-01T00:00:00Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("WINDOW_TOO_LARGE"));
    }

    @Test
    void aWindowInsideTheCapIsAccepted() {
        assertThat(Params.requireSummaryRange(qs("from", "2026-01-01T00:00:00Z", "to", "2026-08-18T00:00:00Z")).to())
                .isEqualTo(Instant.parse("2026-08-18T00:00:00Z"));
    }
}
