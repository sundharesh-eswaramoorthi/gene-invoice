package com.geneinvoice.email.mailservice;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final String SECRET = "a-webhook-secret-of-some-length";
    private static final byte[] BODY = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.ofEpochSecond(1_790_000_000L);
    private static final String TIMESTAMP = String.valueOf(NOW.getEpochSecond());

    @Test
    void theSignatureIsHmacSha256OfTheTimestampAndTheBody() {
        assertThat(WebhookSignature.sign(SECRET, TIMESTAMP, BODY))
                .isEqualTo("sha256=f7fe42f24fb33241a26acaae1747f6af96a391bcfd8908d0ef1411915e4b8394");
    }

    @Test
    void aSignedRecentCallIsAccepted() {
        String signature = WebhookSignature.sign(SECRET, TIMESTAMP, BODY);

        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature, BODY, NOW)).isTrue();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature.toUpperCase().replace("SHA256=", "sha256="), BODY, NOW))
                .isTrue();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature, BODY, NOW.plusSeconds(300))).isTrue();
    }

    @Test
    void anythingElseIsRefused() {
        String signature = WebhookSignature.sign(SECRET, TIMESTAMP, BODY);

        assertThat(WebhookSignature.verify("another-secret-altogether", TIMESTAMP, signature, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature,
                "{\"events\":[{}]}".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, String.valueOf(NOW.getEpochSecond() + 1), signature, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, null, signature, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, null, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature, null, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "yesterday", signature, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature, BODY, NOW.plusSeconds(301))).isFalse();
        assertThat(WebhookSignature.verify(SECRET, TIMESTAMP, signature, BODY, NOW.minusSeconds(301))).isFalse();
        assertThat(WebhookSignature.verify(SECRET, String.valueOf(Long.MIN_VALUE), signature, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify("", TIMESTAMP, signature, BODY, NOW)).isFalse();
    }
}
