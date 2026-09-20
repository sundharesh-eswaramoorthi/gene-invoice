package com.geneinvoice.email.mailservice;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The mail service signs each webhook call: {@code X-Mail-Signature: sha256={hex HMAC-SHA256(secret,
 * timestamp + "." + body)}}, with the timestamp in {@code X-Mail-Timestamp} (unix seconds). A call
 * whose timestamp is more than five minutes off is refused, so a recorded call cannot be replayed later.
 */
public final class WebhookSignature {

    private WebhookSignature() {}

    static final Duration TOLERANCE = Duration.ofSeconds(300);
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    public static boolean verify(String secret, String timestampHeader, String signatureHeader, byte[] body) {
        return verify(secret, timestampHeader, signatureHeader, body, Instant.now());
    }

    static boolean verify(String secret, String timestampHeader, String signatureHeader, byte[] body, Instant now) {
        if (secret == null || secret.isEmpty() || signatureHeader == null || body == null
                || !timely(timestampHeader, now)) {
            return false;
        }
        byte[] expected = sign(secret, timestampHeader, body).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        // In constant time, so the answer's timing does not give the signature away byte by byte.
        return MessageDigest.isEqual(expected, given);
    }

    /**
     * Whether the {@code X-Mail-Timestamp} header is a time within {@link #TOLERANCE} of now. Checked
     * before the body is read, as well as with the signature: a call that fails it is refused unread.
     */
    public static boolean timely(String timestampHeader, Instant now) {
        if (timestampHeader == null) return false;
        long seconds;
        try {
            seconds = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long nowSeconds = now.getEpochSecond();
        return seconds >= nowSeconds - TOLERANCE.getSeconds() && seconds <= nowSeconds + TOLERANCE.getSeconds();
    }

    /** The {@code X-Mail-Signature} value for this timestamp and body. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return PREFIX + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
