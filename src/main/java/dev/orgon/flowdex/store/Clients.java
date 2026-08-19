package dev.orgon.flowdex.store;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Clients are built once at class initialisation so SnapStart captures them in
 * the snapshot. Credentials are deliberately NOT resolved here: the default
 * provider chain reads the container credential endpoint per call, so a
 * restored snapshot picks up rotated credentials rather than replaying stale
 * ones.
 */
public final class Clients {

    private static final Region REGION = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));

    private static final DynamoDbClient DDB = DynamoDbClient.builder()
            .region(REGION)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();

    private static final S3Client S3 = S3Client.builder()
            .region(REGION)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();

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
}
