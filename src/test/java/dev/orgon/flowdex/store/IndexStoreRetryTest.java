package dev.orgon.flowdex.store;

import dev.orgon.flowdex.zeek.ConnRecord;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level regression coverage for {@link IndexStore#writeRow}'s reason-inspecting
 * retry. No Docker, no LocalStack — a hand-written fake {@link DynamoDbClient} scripts
 * the exact sequence of TransactWriteItems outcomes and counts invocations.
 *
 * This exists because LocalStack never surfaces a real TransactionConflict, so
 * IndexStoreWriteIT's concurrentWritesToOneRollupAllLand cannot prove the retry
 * branch actually fires. These tests can, because they control the exception
 * directly rather than hoping for real contention.
 */
class IndexStoreRetryTest {

    private static final ConnRecord REC = new ConnRecord(
            Instant.parse("2026-08-18T14:03:22.451Z"), "CHhAvV",
            "10.0.0.5", 54321, "10.0.0.9", 443,
            "tcp", "ssl", 1.25, 512L, 8192L, "SF", 1);

    private static final IndexRow ROW = IndexRow.forSide(REC, "10.0.0.5", "raw/k.gz");

    /** Mirrors IndexStore.MAX_ATTEMPTS, read via reflection so it cannot drift silently. */
    private static final int MAX_ATTEMPTS = readMaxAttempts();

    @Test
    void trueDuplicateDoesNotRetry() {
        FakeDdb fake = new FakeDdb(List.of(
                () -> { throw cancelledWith("ConditionalCheckFailed", "None"); }));
        IndexStore store = new IndexStore(fake, "table");

        assertThat(store.writeRow(ROW)).isEqualTo(TxnOutcome.DUPLICATE);
        assertThat(fake.invocations()).isEqualTo(1);
    }

    @Test
    void transactionConflictRetriesThenSucceeds() {
        FakeDdb fake = new FakeDdb(List.of(
                () -> { throw cancelledWith("None", "TransactionConflict"); },
                () -> { throw cancelledWith("None", "TransactionConflict"); },
                () -> TransactWriteItemsResponse.builder().build()));
        IndexStore store = new IndexStore(fake, "table");

        assertThat(store.writeRow(ROW)).isEqualTo(TxnOutcome.WRITTEN);
        assertThat(fake.invocations()).isEqualTo(3);
    }

    @Test
    void exhaustingRetriesRethrowsTheOriginalCancellation() {
        TransactionCanceledException alwaysConflicts = cancelledWith("None", "TransactionConflict");
        FakeDdb fake = new FakeDdb(List.of(() -> { throw alwaysConflicts; }));
        IndexStore store = new IndexStore(fake, "table");

        assertThatThrownBy(() -> store.writeRow(ROW)).isSameAs(alwaysConflicts);
        assertThat(fake.invocations()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void nullCancellationReasonsPropagatesWithoutNpe() {
        TransactionCanceledException noReasons = TransactionCanceledException.builder()
                .message("Transaction cancelled, please refer cancellation reasons for specific reasons")
                .build();
        FakeDdb fake = new FakeDdb(List.of(() -> { throw noReasons; }));
        IndexStore store = new IndexStore(fake, "table");

        assertThatThrownBy(() -> store.writeRow(ROW)).isSameAs(noReasons);
        assertThat(fake.invocations()).isEqualTo(1);
    }

    /**
     * ConditionalCheckFailed must dominate TransactionConflict when both appear in the
     * same cancellation. A ConditionalCheckFailed on the Put means that exact row was
     * already committed by an earlier, successful transaction — which already bumped
     * the rollup counters. Retrying here would double-count them. This ordering looks
     * arbitrary in isolation; it is not — do not "simplify" it into a retry.
     */
    @Test
    void conditionalCheckFailedDominatesOverTransactionConflict() {
        FakeDdb fake = new FakeDdb(List.of(
                () -> { throw cancelledWith("ConditionalCheckFailed", "TransactionConflict"); }));
        IndexStore store = new IndexStore(fake, "table");

        assertThat(store.writeRow(ROW)).isEqualTo(TxnOutcome.DUPLICATE);
        assertThat(fake.invocations()).isEqualTo(1);
    }

    @Test
    void unknownReasonPropagatesImmediately() {
        TransactionCanceledException validationError = cancelledWith("ValidationError");
        FakeDdb fake = new FakeDdb(List.of(() -> { throw validationError; }));
        IndexStore store = new IndexStore(fake, "table");

        assertThatThrownBy(() -> store.writeRow(ROW)).isSameAs(validationError);
        assertThat(fake.invocations()).isEqualTo(1);
    }

    private static TransactionCanceledException cancelledWith(String... reasonCodes) {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled, please refer cancellation reasons for specific reasons")
                .cancellationReasons(Arrays.stream(reasonCodes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    private static int readMaxAttempts() {
        try {
            var field = IndexStore.class.getDeclaredField("MAX_ATTEMPTS");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Fake DynamoDbClient. DynamoDbClient is an interface whose operation methods all
     * carry default implementations, so only serviceName(), close(), and the one
     * operation under test need overriding. Replays a scripted sequence of outcomes,
     * repeating the last entry for any call past the end of the script, and counts
     * invocations — the invocation count is what proves whether a retry happened.
     */
    private static class FakeDdb implements DynamoDbClient {
        private final List<Supplier<TransactWriteItemsResponse>> script;
        private final AtomicInteger calls = new AtomicInteger();

        FakeDdb(List<Supplier<TransactWriteItemsResponse>> script) {
            this.script = script;
        }

        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            int n = calls.incrementAndGet();
            Supplier<TransactWriteItemsResponse> step = script.get(Math.min(n, script.size()) - 1);
            return step.get();
        }

        int invocations() { return calls.get(); }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }
}
