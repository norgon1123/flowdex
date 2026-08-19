package dev.orgon.flowdex.api;

import dev.orgon.flowdex.store.Keys;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private static final String PK = "IP#10.0.0.5";
    private static final Instant FROM = Instant.parse("2026-08-18T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-18T15:00:00Z");
    private static final String FROM_BOUND = Keys.connBound(FROM);
    private static final String TO_BOUND = Keys.connBound(TO);

    private static final Map<String, AttributeValue> KEY = Map.of(
            "PK", AttributeValue.builder().s(PK).build(),
            "SK", AttributeValue.builder().s("C#2026-08-18T14:03:22.451Z#CHhAvV").build());

    @Test
    void roundTripsAKey() {
        String cursor = CursorCodec.encode(KEY);
        assertThat(CursorCodec.decode(cursor, PK, FROM_BOUND, TO_BOUND)).isEqualTo(KEY);
    }

    @Test
    void encodesUrlSafelyWithNoPadding() {
        assertThat(CursorCodec.encode(KEY)).doesNotContain("+", "/", "=");
    }

    @Test
    void rejectsACursorMintedForADifferentPartition() {
        assertThatThrownBy(() -> CursorCodec.decode(CursorCodec.encode(KEY), "IP#10.0.0.9", FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!", PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsWellFormedBase64ThatIsNotAKey() {
        assertThatThrownBy(() -> CursorCodec.decode(cursorOf("{\"hello\":\"world\"}"), PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class);
    }

    /**
     * The common misuse, and the one that used to reach DynamoDB and come back
     * as a 500: page once over a wide window, then narrow it while still
     * holding the cursor. DynamoDB rejects an ExclusiveStartKey outside the key
     * condition, and that is a client mistake.
     */
    @Test
    void rejectsACursorReplayedAgainstANarrowedRange() {
        String cursor = CursorCodec.encode(KEY);
        String narrowedFrom = Keys.connBound(Instant.parse("2026-08-18T14:30:00Z"));

        assertThatThrownBy(() -> CursorCodec.decode(cursor, PK, narrowedFrom, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"))
                .hasMessageContaining("time range");
    }

    @Test
    void rejectsACursorReplayedAgainstAnEarlierRange() {
        String cursor = CursorCodec.encode(KEY);
        String earlierTo = Keys.connBound(Instant.parse("2026-08-18T14:01:00Z"));

        assertThatThrownBy(() -> CursorCodec.decode(cursor, PK, FROM_BOUND, earlierTo))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("time range");
    }

    /** Stored sort keys always carry a #uid suffix, so none can equal a bare bound. */
    @Test
    void rejectsASortKeyEqualToTheBareLowerBound() {
        assertThatThrownBy(() -> CursorCodec.decode(cursorOf(json(PK, FROM_BOUND)), PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("time range");
    }

    /**
     * The upper bound is compared inclusively, but no real cursor can ever sit
     * exactly on it: every stored sort key carries a #uid suffix, so a row at
     * precisely `to` sorts ABOVE the bare bound and is excluded from the query
     * that would have minted the cursor. The last key a page can return is the
     * one immediately below.
     */
    @Test
    void acceptsTheLargestSortKeyAQueryCanActuallyReturn() {
        String justInside = Keys.connSk(TO.minusMillis(1), "CHhAvV");
        assertThat(CursorCodec.decode(cursorOf(json(PK, justInside)), PK, FROM_BOUND, TO_BOUND))
                .containsEntry("SK", AttributeValue.builder().s(justInside).build());
    }

    @Test
    void rejectsASortKeyAtTheExclusiveUpperBoundItself() {
        String atTheBound = Keys.connSk(TO, "CHhAvV");
        assertThatThrownBy(() -> CursorCodec.decode(cursorOf(json(PK, atTheBound)), PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("time range");
    }

    /** A hand-crafted key could otherwise start the scan inside the H# rollup rows. */
    @Test
    void rejectsASortKeyPointingAtARollupRow() {
        assertThatThrownBy(() -> CursorCodec.decode(
                cursorOf(json(PK, Keys.rollupSk(FROM))), PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("connection row");
    }

    /** A three-attribute start key against a two-attribute schema is a ValidationException. */
    @Test
    void rejectsExtraAttributes() {
        String extra = "{\"PK\":\"" + PK + "\",\"SK\":\"C#2026-08-18T14:03:22.451Z#CHhAvV\",\"peer\":\"8.8.8.8\"}";
        assertThatThrownBy(() -> CursorCodec.decode(cursorOf(extra), PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsAnOversizedCursorWithoutDecodingIt() {
        String huge = "A".repeat(CursorCodec.MAX_CURSOR_CHARS + 1);
        assertThatThrownBy(() -> CursorCodec.decode(huge, PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsASortKeyOverDynamoDbsSortKeyLimit() {
        String longSk = "C#2026-08-18T14:03:22.451Z#" + "u".repeat(CursorCodec.MAX_SK_BYTES);
        assertThatThrownBy(() -> CursorCodec.decode(cursorOf(json(PK, longSk)), PK, FROM_BOUND, TO_BOUND))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }

    private static String json(String pk, String sk) {
        return "{\"PK\":\"" + pk + "\",\"SK\":\"" + sk + "\"}";
    }

    private static String cursorOf(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
