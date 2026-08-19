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

    /**
     * Gzip is identified by its magic number, so every spelling of the request
     * works — including the ones a header rule gets wrong. API Gateway REST
     * selects binary handling by Content-Type while gzip is announced with
     * Content-Encoding, so no header rule can cover the natural pairing, the
     * application/gzip-with-no-encoding pairing, and curl's silence about a
     * .gz file all at once.
     */
    @Test
    void gzipIsDetectedByMagicNumberWhateverTheHeadersSay() throws Exception {
        byte[] gz = gzip("hello");
        byte[] expected = "hello".getBytes(StandardCharsets.UTF_8);

        assertThat(Body.decode(gzipEvent(gz, Map.of("Content-Encoding", "gzip")), 1024)).isEqualTo(expected);
        assertThat(Body.decode(gzipEvent(gz, Map.of("content-encoding", "GZIP")), 1024)).isEqualTo(expected);
        assertThat(Body.decode(gzipEvent(gz, Map.of("content-type", "application/gzip")), 1024)).isEqualTo(expected);
        assertThat(Body.decode(gzipEvent(gz, Map.of("content-type", "application/x-ndjson")), 1024)).isEqualTo(expected);
        assertThat(Body.decode(gzipEvent(gz, null), 1024)).isEqualTo(expected);
    }

    /** The inverse: a header claiming gzip must not make plain NDJSON unreadable. */
    @Test
    void aPlainBodyIsLeftAloneEvenWhenAHeaderClaimsGzip() {
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent()
                .withBody("{\"uid\":\"CHhAvV\"}")
                .withHeaders(Map.of("content-encoding", "gzip"));

        assertThat(Body.decode(e, 1024)).isEqualTo("{\"uid\":\"CHhAvV\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aBodyThatStartsWithTheGzipMagicNumberButIsCorruptIs400() {
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent()
                .withBody(Base64.getEncoder().encodeToString(new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x11, 0x22}))
                .withIsBase64Encoded(true);

        assertThatThrownBy(() -> Body.decode(e, 1024))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_GZIP"));
    }

    private static APIGatewayProxyRequestEvent gzipEvent(byte[] gz, Map<String, String> headers) {
        APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent()
                .withBody(Base64.getEncoder().encodeToString(gz))
                .withIsBase64Encoded(true);
        return headers == null ? e : e.withHeaders(headers);
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
                .withIsBase64Encoded(true);
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
