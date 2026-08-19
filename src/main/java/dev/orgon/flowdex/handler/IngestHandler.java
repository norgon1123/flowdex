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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
     * pool. 32 is chosen for an I/O-bound workload on a function sized at 1024
     * MB memory (roughly 0.58 vCPU; Lambda reaches 1 vCPU at 1769 MB): the
     * threads spend their time waiting on DynamoDB, not computing, so pool
     * size is not bound by core count.
     */
    public static final int WRITE_CONCURRENCY = 32;

    /** The documented ceiling that keeps the pool inside the gateway timeout. */
    public static final int MAX_RECORDS = 20_000;

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
        String requestId = context.getAwsRequestId() == null ? "" : context.getAwsRequestId();
        try {
            return Responses.ok(202, ingest(event, requestId), requestId);
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

    private Map<String, Object> ingest(APIGatewayProxyRequestEvent event, String requestId) {
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

        int indexed = 0;
        int duplicates = 0;
        ExecutorService pool = Executors.newFixedThreadPool(WRITE_CONCURRENCY);
        try {
            List<Callable<TxnOutcome>> tasks = new ArrayList<>(parsed.records().size());
            for (ConnRecord record : parsed.records()) {
                tasks.add(() -> writeRecord(record, s3Key));
            }
            for (Future<TxnOutcome> future : pool.invokeAll(tasks)) {
                if (get(future) == TxnOutcome.WRITTEN) {
                    indexed++;
                } else {
                    duplicates++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during ingest", e);
        } finally {
            pool.shutdownNow();
        }

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

    /**
     * A record is indexed when at least one of its endpoint rows was newly
     * written, and a duplicate when every one of them already existed. Both
     * rows of a record are written on the same thread so the two outcomes
     * cannot interleave.
     */
    private TxnOutcome writeRecord(ConnRecord record, String s3Key) {
        TxnOutcome outcome = TxnOutcome.DUPLICATE;
        for (String addr : IndexRow.endpointsOf(record)) {
            if (index.writeRow(IndexRow.forSide(record, addr, s3Key)) == TxnOutcome.WRITTEN) {
                outcome = TxnOutcome.WRITTEN;
            }
        }
        return outcome;
    }

    private static TxnOutcome get(Future<TxnOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during ingest", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("index write failed", cause);
        }
    }
}
