package dev.orgon.flowdex.summary;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PeerTallyTest {

    @Test
    void tallyGroupsByPeerAndSumsBytes() {
        List<PeerStat> top = PeerTally.top(List.of(
                row("10.0.0.9", 100, 200),
                row("10.0.0.9", 50, 25),
                row("8.8.8.8", 1, 2)), 10);

        assertThat(top).containsExactly(
                new PeerStat("10.0.0.9", 2, 150, 225),
                new PeerStat("8.8.8.8", 1, 1, 2));
    }

    @Test
    void resultsAreSortedByConnectionsDescending() {
        List<PeerStat> top = PeerTally.top(List.of(
                row("a", 1, 1), row("b", 1, 1), row("b", 1, 1), row("c", 1, 1), row("c", 1, 1), row("c", 1, 1)), 10);
        assertThat(top).extracting(PeerStat::addr).containsExactly("c", "b", "a");
    }

    @Test
    void tiesBreakOnAddressSoOutputIsDeterministic() {
        List<PeerStat> top = PeerTally.top(List.of(row("10.0.0.9", 1, 1), row("10.0.0.1", 1, 1)), 10);
        assertThat(top).extracting(PeerStat::addr).containsExactly("10.0.0.1", "10.0.0.9");
    }

    @Test
    void onlyTheTopNAreReturned() {
        List<Map<String, AttributeValue>> rows = IntStream.range(0, 50)
                .boxed()
                .flatMap(i -> IntStream.rangeClosed(0, i).mapToObj(j -> row("peer-" + i, 1, 1)))
                .toList();
        assertThat(PeerTally.top(rows, 10)).hasSize(10);
        assertThat(PeerTally.top(rows, 10).getFirst().addr()).isEqualTo("peer-49");
    }

    @Test
    void noRowsYieldsAnEmptyList() {
        assertThat(PeerTally.top(List.of(), 10)).isEmpty();
    }

    @Test
    void rowsMissingByteAttributesCountAsZeroNotAsAFailure() {
        List<PeerStat> top = PeerTally.top(List.of(Map.of("peer", AttributeValue.builder().s("x").build())), 10);
        assertThat(top).containsExactly(new PeerStat("x", 1, 0, 0));
    }

    private static Map<String, AttributeValue> row(String peer, long out, long in) {
        return Map.of(
                "peer", AttributeValue.builder().s(peer).build(),
                "bytesOut", AttributeValue.builder().n(Long.toString(out)).build(),
                "bytesIn", AttributeValue.builder().n(Long.toString(in)).build());
    }
}
