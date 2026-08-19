package dev.orgon.flowdex.store;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level regression coverage for {@link IndexStore#scanPeers}'s truncation flag.
 * No Docker, no LocalStack — a hand-written fake {@link DynamoDbClient} scripts the
 * exact sequence of Query responses and records every request it receives.
 *
 * This exists because DynamoDB sets LastEvaluatedKey whenever a query stops on
 * Limit, whether or not anything is actually left — so exhausting the page budget
 * is not by itself evidence that rows remain. Seeding 5000+ real rows through
 * LocalStack to exercise that boundary would make the integration suite
 * unacceptably slow; a fake client lets these tests control page counts and
 * LastEvaluatedKey presence directly.
 */
class IndexStorePeerScanTest {

    private static final String ADDR = "10.0.0.5";
    private static final Instant FROM = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void scanCompletesInsideTheBudgetAndIssuesNoProbe() {
        Map<String, AttributeValue> pageOneKey = key("page1");
        FakeDdb fake = new FakeDdb(List.of(
                () -> pageOf(2, pageOneKey),
                () -> pageOf(2, null)));
        IndexStore store = new IndexStore(fake, "table");

        IndexStore.PeerScan scan = store.scanPeers(ADDR, FROM, TO);

        assertThat(scan.truncated()).isFalse();
        assertThat(scan.rows()).hasSize(4);
        assertThat(fake.invocations()).isEqualTo(2);
    }

    @Test
    void budgetExhaustedWithRowsGenuinelyRemainingIsTruncated() {
        FakeDdb fake = new FakeDdb(fullBudgetThenProbe(true));
        IndexStore store = new IndexStore(fake, "table");

        IndexStore.PeerScan scan = store.scanPeers(ADDR, FROM, TO);

        assertThat(scan.truncated()).isTrue();
        assertThat(fake.invocations()).isEqualTo(IndexStore.PEER_MAX_PAGES + 1);
    }

    /** The regression case for Finding 1: exhausting the budget exactly at the true end. */
    @Test
    void budgetExhaustedExactlyAtTheTrueEndIsNotTruncated() {
        FakeDdb fake = new FakeDdb(fullBudgetThenProbe(false));
        IndexStore store = new IndexStore(fake, "table");

        IndexStore.PeerScan scan = store.scanPeers(ADDR, FROM, TO);

        assertThat(scan.truncated()).isFalse();
        assertThat(fake.invocations()).isEqualTo(IndexStore.PEER_MAX_PAGES + 1);
    }

    @Test
    void theProbeIsTargetedAtWhereTheScanStopped() {
        Map<String, AttributeValue> lastKey = key("page" + IndexStore.PEER_MAX_PAGES);
        List<Supplier<QueryResponse>> script = new ArrayList<>();
        for (int i = 1; i < IndexStore.PEER_MAX_PAGES; i++) {
            Map<String, AttributeValue> pageKey = key("page" + i);
            script.add(() -> pageOf(2, pageKey));
        }
        script.add(() -> pageOf(2, lastKey));
        script.add(() -> pageOf(0, null));
        FakeDdb fake = new FakeDdb(script);
        IndexStore store = new IndexStore(fake, "table");

        store.scanPeers(ADDR, FROM, TO);

        QueryRequest probeRequest = fake.requests().get(IndexStore.PEER_MAX_PAGES);
        assertThat(probeRequest.exclusiveStartKey()).isEqualTo(lastKey);
        assertThat(probeRequest.limit()).isEqualTo(1);
    }

    /** Builds a script of PEER_MAX_PAGES full pages (each with a non-empty LastEvaluatedKey), followed by a probe. */
    private static List<Supplier<QueryResponse>> fullBudgetThenProbe(boolean probeFindsRow) {
        List<Supplier<QueryResponse>> script = new ArrayList<>();
        for (int i = 1; i <= IndexStore.PEER_MAX_PAGES; i++) {
            Map<String, AttributeValue> pageKey = key("page" + i);
            script.add(() -> pageOf(2, pageKey));
        }
        script.add(() -> pageOf(probeFindsRow ? 1 : 0, null));
        return script;
    }

    private static Map<String, AttributeValue> key(String uid) {
        return Map.of(
                "PK", AttributeValue.builder().s("IP#" + ADDR).build(),
                "SK", AttributeValue.builder().s("C#2026-08-18T00:00:00.000Z#" + uid).build());
    }

    private static QueryResponse pageOf(int itemCount, Map<String, AttributeValue> lastEvaluatedKey) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(Map.of(
                    "peer", AttributeValue.builder().s("8.8.8.8").build(),
                    "bytesOut", AttributeValue.builder().n("100").build(),
                    "bytesIn", AttributeValue.builder().n("200").build()));
        }
        QueryResponse.Builder builder = QueryResponse.builder().items(items);
        if (lastEvaluatedKey != null) {
            builder.lastEvaluatedKey(lastEvaluatedKey);
        }
        return builder.build();
    }

    /**
     * Fake DynamoDbClient. DynamoDbClient is an interface whose operation methods all
     * carry default implementations, so only serviceName(), close(), and the one
     * operation under test need overriding. Replays a scripted sequence of Query
     * responses, records every request it receives, and counts invocations.
     */
    private static class FakeDdb implements DynamoDbClient {
        private final List<Supplier<QueryResponse>> script;
        private final List<QueryRequest> requests = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        FakeDdb(List<Supplier<QueryResponse>> script) {
            this.script = script;
        }

        @Override
        public QueryResponse query(QueryRequest request) {
            int n = calls.incrementAndGet();
            requests.add(request);
            Supplier<QueryResponse> step = script.get(Math.min(n, script.size()) - 1);
            return step.get();
        }

        int invocations() { return calls.get(); }

        List<QueryRequest> requests() { return requests; }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }
}
