package com.geneinvoice.mail.connection;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    private static byte[] key(int first) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (first + i);
        return key;
    }

    private final SecretBox box = new SecretBox(key(0));

    @Test
    void whatIsSealedOpensToTheSameText() {
        String secret = "1//0gLx-refresh-token_with.all/kinds+of=chars ₹ Zoë";

        String sealed = box.seal(secret);

        assertThat(sealed).doesNotContain("refresh-token");
        assertThat(box.open(sealed)).isEqualTo(secret);
        byte[] bytes = Base64.getDecoder().decode(sealed);
        assertThat(bytes).hasSize(12 + secret.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void everySealUsesAFreshIv() {
        String first = box.seal("secret-7");
        String second = box.seal("secret-7");

        assertThat(first).isNotEqualTo(second);
        assertThat(Arrays.copyOf(Base64.getDecoder().decode(first), 12))
                .isNotEqualTo(Arrays.copyOf(Base64.getDecoder().decode(second), 12));
        assertThat(box.open(first)).isEqualTo(box.open(second));
    }

    @Test
    void anotherKeyOrAChangedValueCannotBeRead() {
        String sealed = box.seal("secret-7");

        assertThatThrownBy(() -> new SecretBox(key(1)).open(sealed))
                .isInstanceOf(SecretBox.UnreadableSecretException.class);

        byte[] bytes = Base64.getDecoder().decode(sealed);
        bytes[bytes.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> box.open(tampered)).isInstanceOf(SecretBox.UnreadableSecretException.class);

        assertThatThrownBy(() -> box.open("not base64 at all")).isInstanceOf(SecretBox.UnreadableSecretException.class);
        assertThatThrownBy(() -> box.open("c2hvcnQ=")).isInstanceOf(SecretBox.UnreadableSecretException.class);
        assertThatThrownBy(() -> box.open(null)).isInstanceOf(SecretBox.UnreadableSecretException.class);
    }

    @Test
    void theKeyMustBe256Bits() {
        assertThatThrownBy(() -> new SecretBox(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
