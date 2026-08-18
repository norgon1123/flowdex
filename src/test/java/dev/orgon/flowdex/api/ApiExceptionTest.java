package dev.orgon.flowdex.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    void badRequestCarriesStatusCodeAndMessage() {
        ApiException e = ApiException.badRequest("INVALID_RANGE", "from must be before to");
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("INVALID_RANGE");
        assertThat(e.getMessage()).isEqualTo("from must be before to");
        assertThat(e.details()).isEmpty();
    }

    @Test
    void detailsAreCarriedThrough() {
        ApiException e = ApiException.badRequest("MALFORMED_BATCH", "too many bad lines",
                Map.of("malformedCount", 11, "received", 100));
        assertThat(e.details()).containsEntry("malformedCount", 11);
    }

    @Test
    void payloadTooLargeIs413() {
        assertThat(ApiException.payloadTooLarge("body exceeds 5 MB").status()).isEqualTo(413);
    }
}
