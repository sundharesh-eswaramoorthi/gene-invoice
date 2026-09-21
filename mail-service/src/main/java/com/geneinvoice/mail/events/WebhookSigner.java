package com.geneinvoice.mail.events;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class WebhookSigner {

    public static final String TIMESTAMP_HEADER = "X-Mail-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Mail-Signature";
    private static final String PREFIX = "sha256=";

    private WebhookSigner() {}

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    public static boolean verify(String secret, String timestamp, String signature, String body) {
        if (timestamp == null || signature == null) return false;
        return MessageDigest.isEqual(sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }
}
