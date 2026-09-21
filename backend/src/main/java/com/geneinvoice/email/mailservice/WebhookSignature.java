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
        return MessageDigest.isEqual(expected, given);
    }

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
