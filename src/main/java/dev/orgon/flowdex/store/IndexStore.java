package dev.orgon.flowdex.store;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Map;

/**
 * All DynamoDB access. One TransactWriteItems per row pairs a conditional Put
 * with the hourly rollup Update, so idempotency and aggregation share one
 * mechanism: a counter can only advance when the row it describes is new.
 */
public class IndexStore {

    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_MILLIS = 20;

    private final DynamoDbClient ddb;
    private final String table;

    public IndexStore(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    public TxnOutcome writeRow(IndexRow row) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(putIndexRow(row), bumpRollup(row))
                .build();

        for (int attempt = 1; ; attempt++) {
            try {
                ddb.transactWriteItems(request);
                return TxnOutcome.WRITTEN;
            } catch (TransactionCanceledException e) {
                if (hasReason(e, "ConditionalCheckFailed")) {
                    return TxnOutcome.DUPLICATE;
                }
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    throw e;
                }
                backoff(attempt);
            } catch (ProvisionedThroughputExceededException | RequestLimitExceededException
                     | InternalServerErrorException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    private TransactWriteItem putIndexRow(IndexRow row) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(table)
                        .item(row.item())
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build();
    }

    /**
     * Every counter uses ADD, which is atomic and needs no read.
     *
     * Protocol counts are stored as TOP-LEVEL attributes named "proto#tcp",
     * "proto#udp", reached through an expression-name alias, rather than as a
     * nested map. A nested map cannot be maintained in one update: ADD does not
     * work on nested paths, and the SET form needs the parent map to exist —
     * while creating the parent and incrementing a child in the same expression
     * is rejected outright as overlapping document paths. Aliased top-level
     * attributes make create-or-increment a single atomic ADD.
     */
    private TransactWriteItem bumpRollup(IndexRow row) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(table)
                        .key(Map.of(
                                "PK", av(row.pk()),
                                "SK", av(Keys.rollupSk(row.ts()))))
                        .updateExpression("ADD conns :one, bytesOut :out, bytesIn :in, #p :one")
                        .expressionAttributeNames(Map.of("#p", Keys.protoAttr(row.proto())))
                        .expressionAttributeValues(Map.of(
                                ":one", num(1),
                                ":out", num(row.bytesOut()),
                                ":in", num(row.bytesIn())))
                        .build())
                .build();
    }

    private static boolean hasReason(TransactionCanceledException e, String code) {
        List<CancellationReason> reasons = e.cancellationReasons();
        return reasons != null && reasons.stream().anyMatch(r -> code.equals(r.code()));
    }

    /**
     * TransactionConflict and throughput errors are transient and the SDK's
     * default policy does not retry them, because they arrive wrapped in a
     * TransactionCanceledException rather than as a retryable error of their
     * own. Concurrent ingest contends on shared rollup items constantly, so
     * this retry is load-bearing, not defensive.
     */
    private static boolean isRetryable(TransactionCanceledException e) {
        return hasReason(e, "TransactionConflict")
            || hasReason(e, "ThrottlingError")
            || hasReason(e, "ProvisionedThroughputExceeded");
    }

    private static void backoff(int attempt) {
        long millis = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        try {
            Thread.sleep(Math.min(millis, 800));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying", ie);
        }
    }

    static AttributeValue av(String v) { return AttributeValue.builder().s(v).build(); }
    static AttributeValue num(Number v) { return AttributeValue.builder().n(v.toString()).build(); }
}
