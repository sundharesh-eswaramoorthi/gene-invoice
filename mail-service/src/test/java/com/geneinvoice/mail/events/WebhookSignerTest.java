package com.geneinvoice.mail.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code sha256=hex(HMAC-SHA256(secret, timestamp + "." + body))}, checked against openssl. */
class WebhookSignerTest {

    private static final String SECRET = "test-webhook-secret-0123456789";
    private static final String TIMESTAMP = "1790000000";
    private static final String BODY = "{\"events\":[{\"id\":1,\"type\":\"message.status\"}]}";
    /** {@code printf '%s' '1790000000.{…}' | openssl dgst -sha256 -hmac 'test-webhook-secret-0123456789'} */
    private static final String EXPECTED = "sha256=6cb87ae9fbfe7bdf86f794bd337d12ff6f3aded0cf5328806c2a7d96c8e52053";

    @Test
    void theSignatureIsTheHmacOfTheTimestampADotAndTheBody() {
        assertThat(WebhookSigner.sign(SECRET, TIMESTAMP, BODY)).isEqualTo(EXPECTED);
    }

    @Test
    void theBodyIsSignedAsUtf8() {
        // printf '%s' '1790000000.{"subject":"Zoë ₹"}' | openssl dgst -sha256 -hmac 'test-webhook-secret-0123456789'
        assertThat(WebhookSigner.sign(SECRET, TIMESTAMP, "{\"subject\":\"Zoë ₹\"}"))
                .isEqualTo("sha256=b188740eac6aab5eed33e2481bb89c885984785dda23547360a5e673031b0017");
    }

    @Test
    void verifyAcceptsOnlyTheSameSecretTimestampAndBody() {
        assertThat(WebhookSigner.verify(SECRET, TIMESTAMP, EXPECTED, BODY)).isTrue();
        assertThat(WebhookSigner.verify(SECRET, TIMESTAMP, " " + EXPECTED + " ", BODY)).isTrue();
        assertThat(WebhookSigner.verify(SECRET, "1790000001", EXPECTED, BODY)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, TIMESTAMP, EXPECTED, BODY + " ")).isFalse();
        assertThat(WebhookSigner.verify("another-secret-0123456789", TIMESTAMP, EXPECTED, BODY)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, TIMESTAMP, EXPECTED.toUpperCase(), BODY)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, null, EXPECTED, BODY)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, TIMESTAMP, null, BODY)).isFalse();
    }
}
