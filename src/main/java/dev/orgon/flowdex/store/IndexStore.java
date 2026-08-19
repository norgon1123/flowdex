package dev.orgon.flowdex.store;

import dev.orgon.flowdex.api.ApiException;
import dev.orgon.flowdex.api.CursorCodec;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * All DynamoDB access. One TransactWriteItems per row pairs a conditional Put
 * with the hourly rollup Update, so idempotency and aggregation share one
 * mechanism: a counter can only advance when the row it describes is new.
 */
public class IndexStore {

    private static final int MAX_ATTEMPTS = 8;
    private static final long BASE_BACKOFF_MILLIS = 20;
    private static final long MAX_BACKOFF_MILLIS = 800;

    /**
     * Retrying is only worth doing while there is still time to answer. Below
     * this much remaining budget the retry stops and the failure surfaces, so
     * the caller gets a 503 it can act on instead of a gateway timeout.
     */
    static final long RETRY_RESERVE_MILLIS = 1_500;

    /**
     * How much time the caller has left. The ingest handler supplies Lambda's
     * own countdown; everything else retries on the attempt ladder alone.
     *
     * A fixed ladder is the wrong shape for a retry that runs inside a request
     * with a deadline: it either gives up while there was time left, or sleeps
     * past the moment an answer was still useful.
     */
    @FunctionalInterface
    public interface RetryBudget {
        long remainingMillis();

        RetryBudget UNLIMITED = () -> Long.MAX_VALUE;
    }

    private final DynamoDbClient ddb;
    private final String table;

    public IndexStore(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    public TxnOutcome writeRow(IndexRow row) {
        return writeRow(row, RetryBudget.UNLIMITED);
    }

    public TxnOutcome writeRow(IndexRow row, RetryBudget budget) {
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
                backoff(attempt, budget, e);
            } catch (ProvisionedThroughputExceededException | RequestLimitExceededException
                     | InternalServerErrorException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt, budget, e);
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

    /**
     * Full jitter: sleep a uniform random draw from [0, ceiling), not the
     * ceiling itself.
     *
     * A deterministic ladder is actively harmful here. Every writer that
     * collides on one rollup item collides at the same instant, so a shared
     * ladder makes them all wake at the same instant and collide again — the
     * contention is re-synchronised by the very mechanism meant to spread it.
     * Randomising the whole interval is what actually spreads them out.
     *
     * Giving up early when the budget is nearly spent is the other half: a
     * sleep that outlasts the request converts a reportable 503 into a
     * timeout, which tells the caller strictly less.
     */
    private static void backoff(int attempt, RetryBudget budget, RuntimeException cause) {
        long ceiling = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L << (attempt - 1)));
        long millis = ThreadLocalRandom.current().nextLong(ceiling + 1);
        if (budget.remainingMillis() - millis < RETRY_RESERVE_MILLIS) {
            throw cause;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying", ie);
        }
    }

    static AttributeValue av(String v) { return AttributeValue.builder().s(v).build(); }
    static AttributeValue num(Number v) { return AttributeValue.builder().n(v.toString()).build(); }

    public static final int PEER_PAGE_SIZE = 1000;
    public static final int PEER_MAX_PAGES = 5;

    public record Page(List<Map<String, AttributeValue>> items, String nextCursor) {}

    public record PeerScan(List<Map<String, AttributeValue>> rows, boolean truncated) {}

    /**
     * PK = IP#addr AND SK BETWEEN C#from AND C#to.
     *
     * Stored sort keys always carry a #uid suffix, so a row at exactly `to`
     * sorts above the bare C#to bound and is excluded — which is what makes
     * the upper bound exclusive with no sentinel character.
     */
    public Page queryConnections(String addr, Instant from, Instant to, int limit, String cursor) {
        String pk = Keys.pk(addr);
        String fromBound = Keys.connBound(from);
        String toBound = Keys.connBound(to);
        boolean cursorSupplied = cursor != null && !cursor.isBlank();
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":pk", av(pk),
                        ":from", av(fromBound),
                        ":to", av(toBound)))
                .limit(limit);
        if (cursorSupplied) {
            request.exclusiveStartKey(CursorCodec.decode(cursor, pk, fromBound, toBound));
        }

        QueryResponse response;
        try {
            response = ddb.query(request.build());
        } catch (DynamoDbException e) {
            // Backstop. CursorCodec.decode already rejects every start-key shape
            // this codebase knows how to produce badly, but it is validating a
            // caller-supplied token against rules DynamoDB owns, so anything it
            // has not anticipated should still read as a client mistake rather
            // than a server failure. Only translated when a cursor was actually
            // supplied, so a genuine service error on an un-cursored query still
            // surfaces as 500.
            if (cursorSupplied && startKeyMismatch(e)) {
                throw ApiException.badRequest("INVALID_CURSOR", "cursor does not belong to this time range");
            }
            throw e;
        }
        Map<String, AttributeValue> last = response.lastEvaluatedKey();
        String next = (last == null || last.isEmpty()) ? null : CursorCodec.encode(last);
        return new Page(response.items(), next);
    }

    private static boolean startKeyMismatch(DynamoDbException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("starting key");
    }

    /**
     * First or last seen, exact. DynamoDB cannot express min or max in an
     * update, so rather than emulating it on the write path with conditional
     * writes that abort on ordinary data, read the edge of the index range:
     * one item, forward or reverse.
     */
    public Optional<Instant> edge(String addr, Instant from, Instant to, boolean forward) {
        QueryResponse response = ddb.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":pk", av(Keys.pk(addr)),
                        ":from", av(Keys.connBound(from)),
                        ":to", av(Keys.connBound(to))))
                .scanIndexForward(forward)
                .limit(1)
                .build());
        return response.items().isEmpty()
                ? Optional.empty()
                : Optional.of(Keys.parseTs(response.items().getFirst().get("ts").s()));
    }

    /** Both bounds are hour-truncated instants, inclusive. */
    public List<Map<String, AttributeValue>> queryRollups(String addr, Instant fromHour, Instant lastHour) {
        List<Map<String, AttributeValue>> all = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                    .expressionAttributeValues(Map.of(
                            ":pk", av(Keys.pk(addr)),
                            ":from", av(Keys.rollupBound(fromHour)),
                            ":to", av(Keys.rollupBound(lastHour))));
            if (start != null) {
                request.exclusiveStartKey(start);
            }
            QueryResponse response = ddb.query(request.build());
            all.addAll(response.items());
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return all;
    }

    /**
     * Rows for the peer tally, projected down to what the tally needs.
     * Bounded at PEER_MAX_PAGES x PEER_PAGE_SIZE rows; exhausting the budget
     * sets truncated, which the response surfaces. Silent truncation in a
     * security tool is how analysts reach confident wrong conclusions.
     */
    public PeerScan scanPeers(String addr, Instant from, Instant to) {
        List<Map<String, AttributeValue>> rows = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        for (int page = 0; page < PEER_MAX_PAGES; page++) {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                    .expressionAttributeValues(Map.of(
                            ":pk", av(Keys.pk(addr)),
                            ":from", av(Keys.connBound(from)),
                            ":to", av(Keys.connBound(to))))
                    .projectionExpression("#peer, #bo, #bi")
                    .expressionAttributeNames(Map.of("#peer", "peer", "#bo", "bytesOut", "#bi", "bytesIn"))
                    .limit(PEER_PAGE_SIZE);
            if (start != null) {
                request.exclusiveStartKey(start);
            }
            QueryResponse response = ddb.query(request.build());
            rows.addAll(response.items());
            start = response.lastEvaluatedKey();
            if (start == null || start.isEmpty()) {
                return new PeerScan(rows, false);
            }
        }
        return new PeerScan(rows, moreRowsRemain(addr, from, to, start));
    }

    /**
     * DynamoDB sets LastEvaluatedKey whenever a query stops on Limit, whether or
     * not anything is actually left, so the page budget running out is not by
     * itself evidence of truncation. One Limit-1 probe from where the scan stopped
     * settles it. A flag that over-reports partial results teaches analysts to
     * ignore it, which costs exactly what silent truncation costs.
     */
    private boolean moreRowsRemain(String addr, Instant from, Instant to,
                                   Map<String, AttributeValue> start) {
        QueryResponse probe = ddb.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":pk", av(Keys.pk(addr)),
                        ":from", av(Keys.connBound(from)),
                        ":to", av(Keys.connBound(to))))
                .exclusiveStartKey(start)
                .limit(1)
                .build());
        return !probe.items().isEmpty();
    }
}
