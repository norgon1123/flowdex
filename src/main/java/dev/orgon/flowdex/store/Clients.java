package dev.orgon.flowdex.store;

import org.crac.Core;
import org.crac.Resource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Map;

/**
 * Clients are built once at class initialisation so SnapStart captures them in
 * the snapshot. Credentials are deliberately NOT resolved here: the default
 * provider chain reads the container credential endpoint per call, so a
 * restored snapshot picks up rotated credentials rather than replaying stale
 * ones.
 */
public final class Clients {

    /**
     * UrlConnectionHttpClient wraps HttpURLConnection, whose keep-alive cache
     * holds http.maxConnections idle connections PER HOST — and the JDK default
     * is 5. The ingest function runs 32 writers against one DynamoDB endpoint,
     * so at the default all but five completed connections are discarded and
     * most transactions pay a fresh TLS handshake: tens of milliseconds of
     * latency and, worse, handshake crypto competing for a fractional vCPU.
     *
     * There is no builder setting for this — the cache is JDK-global and reads
     * the system property once — so it has to be a property, and it has to be
     * set before the first connection is opened. Class initialisation is that
     * moment, and it is also inside the SnapStart snapshot.
     *
     * Sized above the writer pool so a pool-sized burst never evicts its own
     * connections.
     */
    private static final int MAX_IDLE_CONNECTIONS_PER_HOST = 64;

    static {
        System.setProperty("http.maxConnections", Integer.toString(MAX_IDLE_CONNECTIONS_PER_HOST));
    }

    private static final Region REGION = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));

    private static final DynamoDbClient DDB = DynamoDbClient.builder()
            .region(REGION)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();

    private static final S3Client S3 = S3Client.builder()
            .region(REGION)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();

    static {
        Core.getGlobalContext().register(new Priming());
    }

    private Clients() {}

    public static DynamoDbClient dynamo() { return DDB; }
    public static S3Client s3() { return S3; }
    public static String table() { return required("TABLE_NAME"); }
    public static String bucket() { return required("BUCKET_NAME"); }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing required environment variable " + name);
        }
        return v;
    }

    /**
     * Building a client is cheap; USING one for the first time is not.
     *
     * A restored snapshot still pays, on its first real invocation, for the
     * operation marshallers, the signer, the JSON and XML protocol machinery,
     * the TLS stack and the JIT passes over all of it — one to two seconds,
     * which is most of what SnapStart was supposed to buy. None of that work is
     * captured by merely constructing the client at class-init, because none of
     * it happens until a request is actually marshalled.
     *
     * So the checkpoint hook issues one of each operation the handlers use and
     * throws the answers away. The point is the code paths, not the results.
     *
     * The requests are signed with deliberately fake static credentials, which
     * is the load-bearing detail rather than a shortcut. Priming with the real
     * clients would drive the default provider chain during INIT, and its
     * resolved credentials would then be baked into the snapshot and replayed —
     * expired — by every restore, which is precisely what building the clients
     * without credentials was avoiding. Fake credentials exercise marshalling,
     * signing and transport in full and are rejected at authentication, so
     * nothing is read, nothing is written, and no credential is captured.
     */
    private static final class Priming implements Resource {

        private static final String NOWHERE = "flowdex-priming-does-not-exist";

        @Override
        public void beforeCheckpoint(org.crac.Context<? extends Resource> context) {
            AwsCredentialsProvider fake = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("priming", "priming"));

            try (DynamoDbClient ddb = DynamoDbClient.builder()
                    .region(REGION)
                    .credentialsProvider(fake)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build()) {
                Map<String, AttributeValue> key = Map.of(
                        "PK", AttributeValue.builder().s("IP#192.0.2.1").build(),
                        "SK", AttributeValue.builder().s(Keys.connBound(java.time.Instant.EPOCH)).build());
                ignoringFailure(() -> ddb.query(QueryRequest.builder()
                        .tableName(NOWHERE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(":pk", key.get("PK")))
                        .build()));
                ignoringFailure(() -> ddb.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(TransactWriteItem.builder()
                                .put(Put.builder().tableName(NOWHERE).item(key).build())
                                .build())
                        .build()));
            }

            try (S3Client s3 = S3Client.builder()
                    .region(REGION)
                    .credentialsProvider(fake)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build()) {
                ignoringFailure(() -> s3.putObject(
                        PutObjectRequest.builder().bucket(NOWHERE).key("priming").build(),
                        RequestBody.fromBytes(new byte[] {0})));
            }
        }

        /** Nothing is restored: the snapshot deliberately carries no live connection. */
        @Override
        public void afterRestore(org.crac.Context<? extends Resource> context) {}

        private static void ignoringFailure(Runnable call) {
            try {
                call.run();
            } catch (RuntimeException expected) {
                // Rejection is the expected outcome. The warm code path is the point.
            }
        }
    }
}
