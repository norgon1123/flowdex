package dev.orgon.flowdex;

import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

/**
 * One LocalStack container for the whole run, started lazily and torn down by
 * the JVM shutdown hook Testcontainers installs. Each test class gets a fresh
 * table and bucket so tests cannot leak state into one another.
 */
public abstract class LocalStackBase {

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.DYNAMODB);

    private static S3Client s3;
    private static DynamoDbClient ddb;

    private String bucket;
    private String table;

    static {
        LOCALSTACK.start();
        s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
        ddb = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @org.junit.jupiter.api.BeforeEach
    void provisionResources(org.junit.jupiter.api.TestInfo info) {
        String suffix = Integer.toHexString(System.identityHashCode(this)) + "-" + Math.abs(info.getDisplayName().hashCode());
        bucket = "flowdex-test-" + suffix;
        table = "flowdex-test-" + suffix;

        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        ddb.createTable(CreateTableRequest.builder()
                .tableName(table)
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        ddb.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(table).build());
    }

    protected S3Client s3() { return s3; }
    protected DynamoDbClient ddb() { return ddb; }
    protected String bucket() { return bucket; }
    protected String table() { return table; }
}
