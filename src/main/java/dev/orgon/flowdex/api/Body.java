package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class Body {

    private Body() {}

    public static byte[] decode(APIGatewayProxyRequestEvent event, int maxBytes) {
        String raw = event.getBody();
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("EMPTY_BATCH", "request body is empty");
        }

        byte[] wire = Boolean.TRUE.equals(event.getIsBase64Encoded())
                ? Base64.getDecoder().decode(raw)
                : raw.getBytes(StandardCharsets.UTF_8);

        if (wire.length > maxBytes) {
            throw ApiException.payloadTooLarge("body exceeds " + maxBytes + " bytes");
        }
        return isGzip(event) ? gunzip(wire, maxBytes) : wire;
    }

    private static boolean isGzip(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = event.getHeaders();
        if (headers == null) {
            return false;
        }
        return headers.entrySet().stream()
                .filter(h -> h.getKey().equalsIgnoreCase("content-encoding"))
                .anyMatch(h -> h.getValue() != null && h.getValue().toLowerCase().contains("gzip"));
    }

    /**
     * Stops at the cap rather than expanding first and measuring after: a few
     * kilobytes of gzip can expand to gigabytes, and a function that buffers
     * that has already lost.
     */
    private static byte[] gunzip(byte[] compressed, int maxBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 1 << 16));
        byte[] chunk = new byte[8192];
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (out.size() + read > maxBytes) {
                    throw ApiException.payloadTooLarge("decompressed body exceeds " + maxBytes + " bytes");
                }
                out.write(chunk, 0, read);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_GZIP", "Content-Encoding was gzip but the body is not");
        }
        return out.toByteArray();
    }
}
