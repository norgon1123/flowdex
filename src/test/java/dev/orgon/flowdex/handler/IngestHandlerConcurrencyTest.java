package dev.orgon.flowdex.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orgon.flowdex.store.IndexStore;
import dev.orgon.flowdex.store.RawStore;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write path under real parallelism: how work is sharded, how outcomes are
 * aggregated across threads, what the record cap does, and how a deterministic
 * failure ends. Hand-written fakes over DynamoDbClient and S3Client — the
 * planned LocalStack tests only ever push five records through, which is fewer
 * records than there are threads.
 */
class IngestHandlerConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyRowOfOnePartitionIsWrittenByExactlyOneThread() throws Exception {
        // 300 connections from one busy address to 300 distinct peers: the
        // shape that used to put every thread on the same rollup item.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            body.append(line("uid" + i, "10.0.0.5", "203.0.113." + (i % 254 + 1), i)).append('\n');
        }

        RecordingDdb ddb = new RecordingDdb();
        response(ddb, body.toString());

        assertThat(ddb.threadsPerPartition.get("IP#10.0.0.5"))
                .as("the hot partition must be owned by a single writer")
                .hasSize(1);
        assertThat(ddb.threadsPerPartition.values().stream().flatMap(Set::stream).distinct().count())
                .as("cross-partition parallelism must survive the sharding")
                .isGreaterThan(1);
    }

    /**
     * A record's two endpoint rows now live in different shards and so are
     * written by different threads. "Indexed" still has to mean "at least one
     * row was new", counted once per record.
     */
    @Test
    void outcomesAggregateCorrectlyAcrossThreads() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append(line("uid" + i, "10.0.0." + (i % 50 + 1), "203.0.113." + (i % 40 + 1), i)).append('\n');
        }

        JsonNode ok = response(new RecordingDdb(), body.toString());

        assertThat(ok.get("received").asInt()).isEqualTo(200);
        assertThat(ok.get("indexed").asInt()).isEqualTo(200);
        assertThat(ok.get("duplicates").asInt()).isZero();
    }

    /** A re-POST of a batch already fully indexed is all duplicates and no writes. */
    @Test
    void aFullyIndexedBatchRePostsAsAllDuplicates() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            body.append(line("uid" + i, "10.0.0." + (i % 20 + 1), "203.0.113.9", i)).append('\n');
        }

        JsonNode ok = response(new AlwaysDuplicateDdb(), body.toString());

        assertThat(ok.get("indexed").asInt()).isZero();
        assertThat(ok.get("duplicates").asInt()).isEqualTo(100);
    }

    /**
     * Partial-write recovery. One endpoint's row exists from an earlier,
     * interrupted run; the other does not. The record is INDEXED, not a
     * duplicate, because work genuinely happened — and the missing row is now
     * present, which is the whole point of re-POSTing.
     */
    @Test
    void aRecordWithOneSideAlreadyWrittenCountsAsIndexed() throws Exception {
        JsonNode ok = response(
                new DuplicateForPartition("IP#10.0.0.5"),
                line("uid0", "10.0.0.5", "203.0.113.9", 0));

        assertThat(ok.get("indexed").asInt()).isEqualTo(1);
        assertThat(ok.get("duplicates").asInt()).isZero();
    }

    /** The documented 413, and it must fire before anything is put in S3. */
    @Test
    void aBatchOverTheRecordCapIsRejectedBeforeTheRawObjectIsWritten() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= IngestHandler.MAX_RECORDS; i++) {
            body.append(line("uid" + i, "10.0.0.5", "203.0.113.9", i)).append('\n');
        }

        RecordingDdb ddb = new RecordingDdb();
        CountingS3 s3 = new CountingS3();
        APIGatewayProxyResponseEvent response = new IngestHandler(new IndexStore(ddb, "table"), new RawStore(s3, "bucket"))
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(body.toString()), new TestContext("req"));

        assertThat(response.getStatusCode()).isEqualTo(413);
        assertThat(MAPPER.readTree(response.getBody()).get("error").get("code").asText())
                .isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(s3.puts.get()).as("a rejected batch must leave no orphan object").isZero();
        assertThat(ddb.calls.get()).isZero();
    }

    /**
     * A deterministic failure — a missing table, a missing IAM grant — must end
     * the batch, not be waited out. Blocking on every remaining shard would
     * spend the whole gateway budget on work that cannot succeed and turn a
     * diagnosable 500 into a bare timeout.
     */
    @Test
    void aDeterministicFailureStopsTheBatchInsteadOfBurningTheBudget() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            body.append(line("uid" + i, "10.0." + (i / 254) + "." + (i % 254 + 1),
                    "203.0." + (i / 254) + "." + (i % 254 + 1), i)).append('\n');
        }

        FailingDdb ddb = new FailingDdb();
        APIGatewayProxyResponseEvent response = new IngestHandler(
                new IndexStore(ddb, "table"), new RawStore(new CountingS3(), "bucket"))
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(body.toString()), new TestContext("req"));

        assertThat(response.getStatusCode()).isEqualTo(500);
        assertThat(ddb.calls.get())
                .as("must abandon the remaining shards rather than attempt all 2000 rows")
                .isLessThan(500);
    }

    private static JsonNode response(DynamoDbClient ddb, String body) throws Exception {
        APIGatewayProxyResponseEvent response = new IngestHandler(
                new IndexStore(ddb, "table"), new RawStore(new CountingS3(), "bucket"))
                .handleRequest(new APIGatewayProxyRequestEvent().withBody(body), new TestContext("req"));
        assertThat(response.getStatusCode()).isEqualTo(202);
        return MAPPER.readTree(response.getBody());
    }

    private static String line(String uid, String orig, String resp, int offsetSeconds) {
        return "{\"ts\":" + (1787061802 + offsetSeconds) + ".451,\"uid\":\"" + uid + "\","
                + "\"id.orig_h\":\"" + orig + "\",\"id.orig_p\":54321,"
                + "\"id.resp_h\":\"" + resp + "\",\"id.resp_p\":443,\"proto\":\"tcp\"}";
    }

    private static String partitionOf(TransactWriteItemsRequest request) {
        return request.transactItems().get(0).put().item().get("PK").s();
    }

    /** Succeeds, recording which threads touched which partition. */
    private static class RecordingDdb implements DynamoDbClient {
        final Map<String, Set<String>> threadsPerPartition = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            calls.incrementAndGet();
            threadsPerPartition
                    .computeIfAbsent(partitionOf(request), k -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().getName());
            return TransactWriteItemsResponse.builder().build();
        }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }

    private static class AlwaysDuplicateDdb implements DynamoDbClient {
        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            throw conditionalCheckFailed();
        }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }

    /** Only one endpoint's row already exists — the interrupted-run shape. */
    private record DuplicateForPartition(String pk) implements DynamoDbClient {
        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            if (pk.equals(partitionOf(request))) {
                throw conditionalCheckFailed();
            }
            return TransactWriteItemsResponse.builder().build();
        }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }

    /**
     * Non-retryable and permanent, like a missing table or a missing grant.
     *
     * The few milliseconds of latency are load-bearing for the test rather than
     * decoration: a fake that fails instantly lets the pool tear through
     * hundreds of one-row shards before the main thread is even scheduled to
     * observe the first failure, which measures thread scheduling instead of
     * the abort. A real DynamoDB round trip is milliseconds, so this is also
     * the more honest fake.
     */
    private static class FailingDdb implements DynamoDbClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            calls.incrementAndGet();
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw ResourceNotFoundException.builder().message("Requested resource not found").build();
        }

        @Override public String serviceName() { return "fake-dynamodb"; }
        @Override public void close() { }
    }

    private static TransactionCanceledException conditionalCheckFailed() {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled, please refer cancellation reasons for specific reasons")
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build())
                .build();
    }

    private static class CountingS3 implements S3Client {
        final AtomicInteger puts = new AtomicInteger();

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            puts.incrementAndGet();
            return PutObjectResponse.builder().build();
        }

        @Override public String serviceName() { return "fake-s3"; }
        @Override public void close() { }
    }
}
