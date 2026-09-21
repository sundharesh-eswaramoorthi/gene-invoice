package com.geneinvoice.mail.connection;

import com.geneinvoice.mail.config.MailProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SecretBox {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretBox(MailProperties properties) {
        this(properties.secretsKeyBytes());
    }

    SecretBox(byte[] key) {
        if (key.length != 32) throw new IllegalArgumentException("The secrets key must be 32 bytes");
        this.key = new SecretKeySpec(key, "AES");
    }

    public static class UnreadableSecretException extends RuntimeException {
        UnreadableSecretException(Throwable cause) {
            super("The stored secret cannot be read with this key", cause);
        }
    }

    public String seal(String plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    public String open(String stored) {
        try {
            byte[] bytes = Base64.getDecoder().decode(stored);
            if (bytes.length < IV_BYTES + TAG_BITS / 8) throw new IllegalArgumentException("Too short");
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new UnreadableSecretException(e);
        }
    }
}
