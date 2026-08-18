package dev.orgon.flowdex.api;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private static final Map<String, AttributeValue> KEY = Map.of(
            "PK", AttributeValue.builder().s("IP#10.0.0.5").build(),
            "SK", AttributeValue.builder().s("C#2026-08-18T14:03:22.451Z#CHhAvV").build());

    @Test
    void roundTripsAKey() {
        String cursor = CursorCodec.encode(KEY);
        assertThat(CursorCodec.decode(cursor, "IP#10.0.0.5")).isEqualTo(KEY);
    }

    @Test
    void encodesUrlSafelyWithNoPadding() {
        String cursor = CursorCodec.encode(KEY);
        assertThat(cursor).doesNotContain("+", "/", "=");
    }

    @Test
    void rejectsACursorMintedForADifferentPartition() {
        String cursor = CursorCodec.encode(KEY);
        assertThatThrownBy(() -> CursorCodec.decode(cursor, "IP#10.0.0.9"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!", "IP#10.0.0.5"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsWellFormedBase64ThatIsNotAKey() {
        String notAKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"hello\":\"world\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorCodec.decode(notAKey, "IP#10.0.0.5"))
                .isInstanceOf(ApiException.class);
    }
}
