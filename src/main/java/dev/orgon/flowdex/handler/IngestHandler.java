package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import dev.orgon.flowdex.api.*;
import dev.orgon.flowdex.store.*;
import dev.orgon.flowdex.zeek.ConnLogParser;
import dev.orgon.flowdex.zeek.ConnRecord;
import dev.orgon.flowdex.zeek.ParseResult;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * POST /ingest.
 *
 * Storage first, derived data second: the raw object is written before any
 * indexing, so a partway failure leaves durable bytes and a safe retry.
 */
public class IngestHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /** API Gateway allows 10 MB and Lambda's sync payload limit is 6 MB; 5 leaves headroom. */
    public static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    /**
     * Sequential per-record transactions cannot fit API Gateway's 29-second
     * integration timeout at 5 MB, so transactions are dispatched across a
     * pool. 32 is chosen for an I/O-bound workload on a function sized at 2048
     * MB memory (roughly 1.15 vCPU; Lambda reaches 1 vCPU at 1769 MB): the
     * threads spend their time waiting on DynamoDB, not computing, so pool
     * size is not bound by core count.
     *
     * The unit of parallelism is a PARTITION, not a record — see shardByPartition.
     */
    public static final int WRITE_CONCURRENCY = 32;

    /**
     * The documented ceiling that keeps the pool inside the gateway timeout.
     *
     * 5,000 records is roughly 10,000 transactions (two endpoints each) and
     * ~40,000 WCU. Three ceilings set this number, and the smallest wins:
     *
     *  - A brand-new on-demand table serves about 4,000 WCU/s before it has
     *    doubled its way up from a cold start, so 40,000 WCU is ~10 s on the
     *    very first ingest into a fresh stack — the README's own demo path.
     *  - A single DynamoDB partition caps near 1,000 WCU/s in every billing
     *    mode, and adaptive capacity cannot split one partition key. At 4 WCU
     *    per transaction that is ~250 transactions/s for one address.
     *  - The gateway gives up at 29 s regardless.
     *
     * A mixed-traffic batch spreads across many partitions and lands in
     * seconds. A scan-shaped batch — one scanner against 5,000 targets — is
     * pinned to the scanner's single partition and can exceed 29 s. That is a
     * real 504, not a theoretical one, and it is safe: the function runs on
     * past the gateway (see the Terraform timeout) and finishes, so the
     * client's retry reports duplicates rather than redoing the work.
     */
    public static final int MAX_RECORDS = 5_000;

    private final ConnLogParser parser = new ConnLogParser();
    private final IndexStore index;
    private final RawStore raw;

    public IngestHandler() {
        this(new IndexStore(Clients.dynamo(), Clients.table()), new RawStore(Clients.s3(), Clients.bucket()));
    }

    IngestHandler(IndexStore index, RawStore raw) {
        this.index = index;
        this.raw = raw;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String requestId = RequestId.of(event, context);
        try {
            return Responses.ok(202, ingest(event, requestId, context::getRemainingTimeInMillis), requestId);
        } catch (ApiException e) {
            Log.event("ingest.rejected", Map.of("requestId", requestId, "code", e.code(), "status", e.status()));
            return Responses.error(e, requestId);
        } catch (TransactionCanceledException | ProvisionedThroughputExceededException
                 | RequestLimitExceededException | InternalServerErrorException e) {
            // DynamoDB is saturated or contended, not broken. IndexStore already
            // retried MAX_ATTEMPTS times with backoff; telling the client "our
            // bug, don't retry" (500) here would be wrong — this is "we're
            // loaded, back off" (503).
            ApiException unavailable = ApiException.serviceUnavailable("index is saturated; retry with backoff");
            Log.event("ingest.rejected", Map.of(
                    "requestId", requestId, "code", unavailable.code(), "status", unavailable.status(),
                    "exception", e.toString()));
            return Responses.error(unavailable, requestId);
        } catch (RuntimeException e) {
            Log.event("ingest.failed", Map.of("requestId", requestId, "exception", e.toString()));
            e.printStackTrace();
            return Responses.serverError(requestId);
        }
    }

    private Map<String, Object> ingest(APIGatewayProxyRequestEvent event, String requestId,
                                       IndexStore.RetryBudget budget) {
        byte[] body = Body.decode(event, MAX_BODY_BYTES);
        ParseResult parsed = parser.parse(new String(body, StandardCharsets.UTF_8));
        parser.enforceMalformedThreshold(parsed);

        if (parsed.records().size() > MAX_RECORDS) {
            throw ApiException.payloadTooLarge(
                    "batch exceeds " + MAX_RECORDS + " records; split the file");
        }

        String ingestId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String s3Key = raw.putBatch(ingestId, now, body);

        AtomicIntegerArray written = writeAll(parsed.records(), s3Key, budget);

        int indexed = 0;
        for (int i = 0; i < written.length(); i++) {
            indexed += written.get(i);
        }
        int duplicates = parsed.records().size() - indexed;

        Log.event("ingest.completed", Map.of(
                "requestId", requestId, "ingestId", ingestId,
                "received", parsed.received(), "indexed", indexed,
                "duplicates", duplicates, "malformed", parsed.malformed().size(),
                "s3Key", s3Key));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ingestId", ingestId);
        response.put("received", parsed.received());
        response.put("indexed", indexed);
        response.put("duplicates", duplicates);
        response.put("malformed", parsed.malformed().stream()
                .map(m -> Map.of("line", m.line(), "reason", m.reason())).toList());
        response.put("s3Key", s3Key);
        return response;
    }

    /** One row of the batch, kept with the index of the record it came from. */
    private record PendingRow(int record, IndexRow row) {}

    /**
     * Every row of one partition is owned by one worker.
     *
     * The obvious sharding — one task per record — is wrong here. Each record
     * bumps its endpoints' hourly rollup items, so a file about one busy
     * address has every worker contending on the same rollup item, and the
     * transactions cancel each other with TransactionConflict faster than any
     * retry ladder can absorb. Partitioning the work by partition key removes
     * the contention rather than backing off from it: two workers never touch
     * the same rollup, so the retry path returns to being the rare case it was
     * always assumed to be. Cross-address parallelism, which is where the real
     * throughput is, is untouched.
     *
     * The cost is that one address is written serially, which is the honest
     * shape of the underlying limit — a single partition caps near 1,000 WCU/s
     * no matter how many threads shout at it.
     */
    private static Map<String, List<PendingRow>> shardByPartition(List<ConnRecord> records, String s3Key) {
        Map<String, List<PendingRow>> shards = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            ConnRecord record = records.get(i);
            for (String addr : IndexRow.endpointsOf(record)) {
                IndexRow row = IndexRow.forSide(record, addr, s3Key);
                shards.computeIfAbsent(row.pk(), k -> new ArrayList<>()).add(new PendingRow(i, row));
            }
        }
        return shards;
    }

    /**
     * Returns 1 per record that was newly written, 0 per record that was
     * already present in full.
     *
     * A record is indexed when at least one of its endpoint rows was newly
     * written, and a duplicate when every one of them already existed. Its two
     * rows now live in different shards and so are written by different
     * threads, which is why the flag is an atomic set-to-one rather than a
     * plain assignment: both threads may reach the same slot, and both agree
     * on the value.
     */
    private AtomicIntegerArray writeAll(List<ConnRecord> records, String s3Key, IndexStore.RetryBudget budget) {
        AtomicIntegerArray written = new AtomicIntegerArray(records.size());
        Map<String, List<PendingRow>> shards = shardByPartition(records, s3Key);
        if (shards.isEmpty()) {
            return written;
        }

        AtomicBoolean aborted = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(WRITE_CONCURRENCY, shards.size()));
        try {
            CompletionService<Void> completions = new ExecutorCompletionService<>(pool);
            for (List<PendingRow> shard : shards.values()) {
                completions.submit(shardWriter(shard, budget, aborted, written));
            }

            for (int done = 0; done < shards.size(); done++) {
                try {
                    completions.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted during ingest", e);
                } catch (ExecutionException e) {
                    // Fail fast. Waiting for the remaining shards after a
                    // deterministic failure — a missing IAM grant, say — spends
                    // the entire gateway budget on work that cannot succeed and
                    // turns a diagnosable 5xx into a bare timeout.
                    aborted.set(true);
                    throw asRuntime(e.getCause());
                }
            }
        } finally {
            aborted.set(true);
            pool.shutdownNow();
        }
        return written;
    }

    private Callable<Void> shardWriter(List<PendingRow> shard, IndexStore.RetryBudget budget,
                                       AtomicBoolean aborted, AtomicIntegerArray written) {
        return () -> {
            for (PendingRow pending : shard) {
                if (aborted.get()) {
                    return null;
                }
                if (index.writeRow(pending.row(), budget) == TxnOutcome.WRITTEN) {
                    written.set(pending.record(), 1);
                }
            }
            return null;
        };
    }

    private static RuntimeException asRuntime(Throwable cause) {
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new IllegalStateException("index write failed", cause);
    }

}
