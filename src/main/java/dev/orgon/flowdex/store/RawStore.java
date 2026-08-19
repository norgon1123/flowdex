package dev.orgon.flowdex.store;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Raw batches, gzipped, never mutated. Written before indexing begins: if
 * indexing dies partway the bytes are durable and re-POSTing is safe.
 * Storage first, derived data second.
 */
public class RawStore {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

    private final S3Client s3;
    private final String bucket;

    public RawStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** dt and hour are ingest wall-clock UTC, not record time. */
    public static String keyFor(String ingestId, Instant now) {
        return "raw/dt=" + DAY.format(now) + "/hour=" + HOUR.format(now) + "/" + ingestId + ".ndjson.gz";
    }

    public String putBatch(String ingestId, Instant now, byte[] ndjson) {
        String key = keyFor(ingestId, now);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/gzip")
                        .build(),
                RequestBody.fromBytes(gzip(ndjson)));
        return key;
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not gzip batch", e);
        }
        return out.toByteArray();
    }
}
