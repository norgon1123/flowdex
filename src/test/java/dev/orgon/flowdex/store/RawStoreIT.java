package dev.orgon.flowdex.store;

import dev.orgon.flowdex.LocalStackBase;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RawStoreIT extends LocalStackBase {

    @Test
    void keyLayoutIsIngestWallClockUtc() {
        assertThat(RawStore.keyFor("b6f1", Instant.parse("2026-08-18T14:59:59.999Z")))
                .isEqualTo("raw/dt=2026-08-18/hour=14/b6f1.ndjson.gz");
    }

    @Test
    void putBatchStoresGzippedBytesRetrievableAndIdentical() throws Exception {
        RawStore store = new RawStore(s3(), bucket());
        String body = "{\"a\":1}\n{\"a\":2}\n";

        String key = store.putBatch("ingest-1", Instant.parse("2026-08-18T14:00:00Z"),
                body.getBytes(StandardCharsets.UTF_8));

        ResponseBytes<?> object = s3().getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket()).key(key).build());
        String roundTripped = new String(
                new GZIPInputStream(new ByteArrayInputStream(object.asByteArray())).readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(roundTripped).isEqualTo(body);
    }
}
