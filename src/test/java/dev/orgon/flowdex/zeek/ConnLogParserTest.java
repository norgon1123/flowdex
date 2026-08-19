package dev.orgon.flowdex.zeek;

import dev.orgon.flowdex.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnLogParserTest {

    private static final String GOOD =
        "{\"ts\":1787061802.451,\"uid\":\"CHhAvV\",\"id.orig_h\":\"10.0.0.5\",\"id.orig_p\":54321,"
      + "\"id.resp_h\":\"10.0.0.9\",\"id.resp_p\":443,\"proto\":\"tcp\",\"service\":\"ssl\","
      + "\"duration\":1.25,\"orig_bytes\":512,\"resp_bytes\":8192,\"conn_state\":\"SF\"}";

    private final ConnLogParser parser = new ConnLogParser();

    @Test
    void parsesAllFieldsOfAGoodLine() {
        ConnRecord r = parser.parse(GOOD).records().getFirst();
        assertThat(r.uid()).isEqualTo("CHhAvV");
        assertThat(r.origH()).isEqualTo("10.0.0.5");
        assertThat(r.origP()).isEqualTo(54321);
        assertThat(r.respH()).isEqualTo("10.0.0.9");
        assertThat(r.respP()).isEqualTo(443);
        assertThat(r.proto()).isEqualTo("tcp");
        assertThat(r.service()).isEqualTo("ssl");
        assertThat(r.duration()).isEqualTo(1.25);
        assertThat(r.origBytes()).isEqualTo(512L);
        assertThat(r.respBytes()).isEqualTo(8192L);
        assertThat(r.connState()).isEqualTo("SF");
        assertThat(r.line()).isEqualTo(1);
    }

    @Test
    void convertsEpochSecondsToMillisecondPreciseInstant() {
        ConnRecord r = parser.parse(GOOD).records().getFirst();
        assertThat(r.ts()).isEqualTo(Instant.ofEpochMilli(1787061802451L));
    }

    @Test
    void absentOptionalNumericsDefaultToZeroAndStringsToNull() {
        String line = "{\"ts\":1.0,\"uid\":\"U\",\"id.orig_h\":\"1.1.1.1\",\"id.orig_p\":1,"
                    + "\"id.resp_h\":\"2.2.2.2\",\"id.resp_p\":2,\"proto\":\"udp\"}";
        ConnRecord r = parser.parse(line).records().getFirst();
        assertThat(r.duration()).isZero();
        assertThat(r.origBytes()).isZero();
        assertThat(r.respBytes()).isZero();
        assertThat(r.service()).isNull();
        assertThat(r.connState()).isNull();
    }

    @Test
    void zeekDashPlaceholdersAreTreatedAsAbsent() {
        String line = "{\"ts\":1.0,\"uid\":\"U\",\"id.orig_h\":\"1.1.1.1\",\"id.orig_p\":1,"
                    + "\"id.resp_h\":\"2.2.2.2\",\"id.resp_p\":2,\"proto\":\"udp\","
                    + "\"service\":\"-\",\"duration\":\"-\",\"orig_bytes\":\"-\"}";
        ConnRecord r = parser.parse(line).records().getFirst();
        assertThat(r.service()).isNull();
        assertThat(r.duration()).isZero();
        assertThat(r.origBytes()).isZero();
    }

    @Test
    void missingRequiredFieldIsMalformedWithLineNumberAndReason() {
        String line = "{\"ts\":1.0,\"uid\":\"U\",\"id.orig_h\":\"1.1.1.1\",\"id.orig_p\":1,"
                    + "\"id.resp_p\":2,\"proto\":\"tcp\"}";
        ParseResult result = parser.parse(GOOD + "\n" + line);
        assertThat(result.records()).hasSize(1);
        assertThat(result.malformed()).singleElement()
            .satisfies(m -> {
                assertThat(m.line()).isEqualTo(2);
                assertThat(m.reason()).isEqualTo("missing id.resp_h");
            });
    }

    /**
     * FIX 9: a non-literal id.orig_h (a hostname, say) would be indexed under
     * a PK no read path can ever address, because Params rejects hostnames.
     * Zeek always writes literal addresses, so rejecting this at parse time
     * costs nothing legitimate.
     */
    @Test
    void nonLiteralOrigHIsMalformedWithFieldSpecificReason() {
        String line = "{\"ts\":1.0,\"uid\":\"U\",\"id.orig_h\":\"gateway\",\"id.orig_p\":1,"
                    + "\"id.resp_h\":\"2.2.2.2\",\"id.resp_p\":2,\"proto\":\"udp\"}";
        ParseResult result = parser.parse(line);
        assertThat(result.records()).isEmpty();
        assertThat(result.malformed()).singleElement()
            .satisfies(m -> assertThat(m.reason()).isEqualTo("id.orig_h is not an IP address"));
    }

    @Test
    void nonLiteralRespHIsMalformedWithFieldSpecificReason() {
        String line = "{\"ts\":1.0,\"uid\":\"U\",\"id.orig_h\":\"1.1.1.1\",\"id.orig_p\":1,"
                    + "\"id.resp_h\":\"gateway.local\",\"id.resp_p\":2,\"proto\":\"udp\"}";
        ParseResult result = parser.parse(line);
        assertThat(result.records()).isEmpty();
        assertThat(result.malformed()).singleElement()
            .satisfies(m -> assertThat(m.reason()).isEqualTo("id.resp_h is not an IP address"));
    }

    @Test
    void literalIpv6EndpointsAreAccepted() {
        String line = "{\"ts\":1.0,\"uid\":\"U\",\"id.orig_h\":\"2001:db8::1\",\"id.orig_p\":1,"
                    + "\"id.resp_h\":\"2.2.2.2\",\"id.resp_p\":2,\"proto\":\"udp\"}";
        ParseResult result = parser.parse(line);
        assertThat(result.records()).hasSize(1);
        assertThat(result.malformed()).isEmpty();
    }

    @Test
    void unparseableJsonIsMalformedNotFatal() {
        ParseResult result = parser.parse("not json at all\n" + GOOD);
        assertThat(result.records()).hasSize(1);
        assertThat(result.malformed().getFirst().line()).isEqualTo(1);
        assertThat(result.malformed().getFirst().reason()).isEqualTo("not valid JSON");
    }

    @Test
    void blankLinesAreSkippedEntirelyAndDoNotCountAsReceived() {
        ParseResult result = parser.parse(GOOD + "\n\n   \n");
        assertThat(result.received()).isEqualTo(1);
        assertThat(result.malformed()).isEmpty();
    }

    @Test
    void thresholdAcceptsNinePercent() {
        ParseResult r = new ParseResult(java.util.List.of(), nMalformed(9), 100);
        parser.enforceMalformedThreshold(r); // must not throw
    }

    @Test
    void thresholdAcceptsExactlyTenPercent() {
        ParseResult r = new ParseResult(java.util.List.of(), nMalformed(10), 100);
        parser.enforceMalformedThreshold(r); // "more than 10%" — 10% passes
    }

    @Test
    void thresholdRejectsElevenPercent() {
        ParseResult r = new ParseResult(java.util.List.of(), nMalformed(11), 100);
        assertThatThrownBy(() -> parser.enforceMalformedThreshold(r))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("MALFORMED_BATCH"));
    }

    @Test
    void emptyBatchIsRejected() {
        assertThatThrownBy(() -> parser.enforceMalformedThreshold(parser.parse("")))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("EMPTY_BATCH"));
    }

    /**
     * Byte counts feed an ADD on the shared hourly rollup, and ADD with a
     * negative value decrements. One crafted line would otherwise reach in and
     * corrupt an aggregate summarising every other record in that hour.
     */
    @Test
    void negativeByteCountsAndDurationsAreClampedRatherThanDecrementingCounters() {
        ConnRecord r = parser.parse(withFields(
                "\"orig_bytes\":-1000000,\"resp_bytes\":-5,\"duration\":-2.5")).records().getFirst();

        assertThat(r.origBytes()).isZero();
        assertThat(r.respBytes()).isZero();
        assertThat(r.duration()).isZero();
    }

    @Test
    void aZeroDurationIsLeftAlone() {
        assertThat(parser.parse(withFields("\"duration\":0")).records().getFirst().duration()).isZero();
    }

    @Test
    void portsOutsideTheValidRangeAreMalformed() {
        assertThat(malformedReason(replace(GOOD, "\"id.orig_p\":54321", "\"id.orig_p\":-1")))
                .contains("id.orig_p", "0-65535");
        assertThat(malformedReason(replace(GOOD, "\"id.resp_p\":443", "\"id.resp_p\":70000")))
                .contains("id.resp_p", "0-65535");
    }

    /** ICMP reuses the port fields for message type and code; both are single bytes. */
    @Test
    void icmpTypeAndCodeInThePortFieldsAreAccepted() {
        ConnRecord r = parser.parse(replace(
                replace(GOOD, "\"id.orig_p\":54321", "\"id.orig_p\":8"),
                "\"id.resp_p\":443", "\"id.resp_p\":0")).records().getFirst();

        assertThat(r.origP()).isEqualTo(8);
        assertThat(r.respP()).isZero();
    }

    /**
     * A year outside four digits cannot be written as a fixed-width key, and the
     * sign character it carries sorts below every digit — one such record would
     * sort ahead of the entire index and break every range query.
     */
    @Test
    void timestampsOutsideTheRepresentableRangeAreMalformed() {
        assertThat(malformedReason(replace(GOOD, "\"ts\":1787061802.451", "\"ts\":-6857222400")))
                .contains("out of range");
        assertThat(malformedReason(replace(GOOD, "\"ts\":1787061802.451", "\"ts\":99999999999")))
                .contains("out of range");
    }

    @Test
    void anAbsurdlyLargeTimestampIsMalformedRatherThanAnOverflow() {
        assertThat(malformedReason(replace(GOOD, "\"ts\":1787061802.451", "\"ts\":1e30")))
                .contains("out of range");
    }

    private String malformedReason(String line) {
        ParseResult result = parser.parse(line);
        assertThat(result.records()).isEmpty();
        return result.malformed().getFirst().reason();
    }

    private static String replace(String line, String from, String to) {
        assertThat(line).contains(from);
        return line.replace(from, to);
    }

    /** GOOD with the listed fields overridden. */
    private static String withFields(String fields) {
        return "{\"ts\":1787061802.451,\"uid\":\"CHhAvV\",\"id.orig_h\":\"10.0.0.5\",\"id.orig_p\":54321,"
             + "\"id.resp_h\":\"10.0.0.9\",\"id.resp_p\":443,\"proto\":\"tcp\"," + fields + "}";
    }

    private static java.util.List<MalformedLine> nMalformed(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n)
            .mapToObj(i -> new MalformedLine(i, "r")).toList();
    }
}
