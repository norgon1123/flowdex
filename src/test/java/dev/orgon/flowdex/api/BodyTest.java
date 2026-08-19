package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyTest {

    @Test
    void plainBodyIsReturnedAsUtf8Bytes() {
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent().withBody("hello");
        assertThat(Body.decode(e, 1024)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void base64BodyIsDecoded() {
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent()
                .withBody(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)))
                .withIsBase64Encoded(true);
        assertThat(Body.decode(e, 1024)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void gzipBodyIsDecompressedRegardlessOfHeaderCase() throws Exception {
        byte[] gz = gzip("hello");
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent()
                .withBody(Base64.getEncoder().encodeToString(gz))
                .withIsBase64Encoded(true)
                .withHeaders(Map.of("Content-Encoding", "gzip"));
        assertThat(Body.decode(e, 1024)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));

        APIGatewayProxyRequestEvent lower = new APIGatewayProxyRequestEvent()
                .withBody(Base64.getEncoder().encodeToString(gz))
                .withIsBase64Encoded(true)
                .withHeaders(Map.of("content-encoding", "GZIP"));
        assertThat(Body.decode(lower, 1024)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void oversizedWireBytesAre413() {
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent().withBody("x".repeat(2000));
        assertThatThrownBy(() -> Body.decode(e, 1024))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(413));
    }

    /** A small gzip payload that expands past the cap must not be buffered whole. */
    @Test
    void aZipBombIsRejectedAtTheCapNotAfterFullExpansion() throws Exception {
        byte[] gz = gzip("A".repeat(1_000_000));
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent()
                .withBody(Base64.getEncoder().encodeToString(gz))
                .withIsBase64Encoded(true)
                .withHeaders(Map.of("Content-Encoding", "gzip"));
        assertThatThrownBy(() -> Body.decode(e, 1024))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(413));
    }

    @Test
    void anAbsentBodyIsRejectedAsAnEmptyBatch() {
        assertThatThrownBy(() -> Body.decode(new APIGatewayProxyRequestEvent(), 1024))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("EMPTY_BATCH"));
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
