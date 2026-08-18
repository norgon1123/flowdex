package dev.orgon.flowdex.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opaque pagination cursor: base64url of the LastEvaluatedKey as JSON.
 *
 * The key carries its own PK, so decode() checks it against the partition the
 * caller asked for. Without that check a cursor minted for one IP would page
 * through another IP's rows.
 */
public final class CursorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public static Map<String, AttributeValue> decode(String cursor, String expectedPk) {
        Map<String, String> plain;
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            plain = MAPPER.readValue(new String(json, StandardCharsets.UTF_8),
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_CURSOR", "cursor is not a valid pagination token");
        }
        if (!plain.containsKey("PK") || !plain.containsKey("SK")) {
            throw ApiException.badRequest("INVALID_CURSOR", "cursor is not a valid pagination token");
        }
        if (!expectedPk.equals(plain.get("PK"))) {
            throw ApiException.badRequest("INVALID_CURSOR", "cursor belongs to a different address");
        }
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        plain.forEach((k, v) -> key.put(k, AttributeValue.builder().s(v).build()));
        return key;
    }
}
