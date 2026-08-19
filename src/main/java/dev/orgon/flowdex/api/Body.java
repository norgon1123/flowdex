package dev.orgon.flowdex.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        return looksGzipped(wire) ? gunzip(wire, maxBytes) : wire;
    }

    /**
     * The body's own first two bytes decide whether it is gzip — not a header.
     *
     * Headers are unreliable here in both directions. A client that pairs
     * "Content-Type: application/x-ndjson" with "Content-Encoding: gzip" — the
     * natural pairing — and a client that sends "Content-Type: application/gzip"
     * with no Content-Encoding at all are both sending gzip, and only one of
     * them says so in the header this used to read. curl's --data-binary with a
     * .gz file says neither. The magic number 1f 8b is in the bytes regardless,
     * and NDJSON cannot begin with it, so sniffing is both stricter and more
     * permissive than any header rule.
     */
    private static boolean looksGzipped(byte[] wire) {
        return wire.length >= 2 && (wire[0] & 0xff) == 0x1f && (wire[1] & 0xff) == 0x8b;
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
            throw ApiException.badRequest("INVALID_GZIP",
                    "body starts with the gzip magic number but is not valid gzip");
        }
        return out.toByteArray();
    }
}
