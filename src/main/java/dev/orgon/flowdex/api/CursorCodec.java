package dev.orgon.flowdex.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orgon.flowdex.store.Keys;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opaque pagination cursor: base64url of the LastEvaluatedKey as JSON.
 *
 * A cursor is caller-supplied input that is handed straight to DynamoDB as an
 * ExclusiveStartKey, so decode() checks every property the query depends on
 * rather than only the ones a well-behaved client would get right. DynamoDB
 * enforces most of them too, but it enforces them as a ValidationException,
 * which surfaces as a 500 — the wrong answer for a client that sent a bad
 * token. Checking here turns each of those into a 400 that names the problem.
 */
public final class CursorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A real cursor is about 90 characters. The cap exists so a megabyte of
     * base64 is rejected before it is decoded, and so an oversized sort key
     * cannot reach DynamoDB's own 1,024-byte limit and come back as a 500.
     */
    static final int MAX_CURSOR_CHARS = 1024;
    static final int MAX_SK_BYTES = 1024;

    private CursorCodec() {}

    public static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        Map<String, String> plain = new LinkedHashMap<>();
        lastEvaluatedKey.forEach((k, v) -> plain.put(k, v.s()));
        try {
            byte[] json = MAPPER.writeValueAsBytes(plain);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("could not encode cursor", e);
        }
    }

    /**
     * @param expectedPk the partition the caller asked for
     * @param fromBound  exclusive lower bound, {@code Keys.connBound(from)}
     * @param toBound    inclusive upper bound, {@code Keys.connBound(to)}
     */
    public static Map<String, AttributeValue> decode(String cursor, String expectedPk,
                                                     String fromBound, String toBound) {
        if (cursor.length() > MAX_CURSOR_CHARS) {
            throw invalid("cursor is not a valid pagination token");
        }
        Map<String, String> plain;
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            plain = MAPPER.readValue(new String(json, StandardCharsets.UTF_8),
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw invalid("cursor is not a valid pagination token");
        }

        String pk = plain.get("PK");
        String sk = plain.get("SK");
        if (pk == null || sk == null) {
            throw invalid("cursor is not a valid pagination token");
        }
        // Exactly the two key attributes are carried forward. A start key with a
        // third attribute is a ValidationException against a two-attribute
        // schema, and the previous code copied every JSON entry it found.
        if (plain.size() != 2) {
            throw invalid("cursor is not a valid pagination token");
        }
        if (!expectedPk.equals(pk)) {
            throw invalid("cursor belongs to a different address");
        }
        // A hand-crafted sort key could otherwise start a scan inside the H#
        // rollup rows, which are the same partition but a different row shape.
        if (!sk.startsWith(Keys.CONN_PREFIX)) {
            throw invalid("cursor does not point at a connection row");
        }
        if (sk.getBytes(StandardCharsets.UTF_8).length > MAX_SK_BYTES) {
            throw invalid("cursor is not a valid pagination token");
        }
        // DynamoDB rejects an ExclusiveStartKey outside the key condition, and
        // the common way to land there is innocent: page once, then narrow or
        // shift from/to while still holding the cursor. That is a 400.
        //
        // The lower bound is strict because a stored sort key always carries a
        // #uid suffix and so sorts strictly above the bare bound; the upper
        // bound is inclusive because BETWEEN is.
        if (sk.compareTo(fromBound) <= 0 || sk.compareTo(toBound) > 0) {
            throw invalid("cursor does not belong to this time range");
        }

        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("PK", AttributeValue.builder().s(pk).build());
        key.put("SK", AttributeValue.builder().s(sk).build());
        return key;
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_CURSOR", message);
    }
}
