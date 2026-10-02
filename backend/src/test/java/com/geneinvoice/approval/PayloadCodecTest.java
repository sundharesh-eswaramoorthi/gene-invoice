package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.invoice.InvoiceChangeApplier.CancelPayload;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a pending change keeps of a request (§5.11.2): never a password, however deep; read back by a newer build
 * that dropped or added a field; re-validated before it is applied. Plain: no Spring context.
 */
class PayloadCodecTest {

    record Login(String username, String password) {}

    record Request(String name, String newPassword, Login login, List<Map<String, Object>> lines,
                   BigDecimal amount, Instant at) {}

    record Checked(@NotNull Long id, @Positive BigDecimal amount) {}

    final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final PayloadCodec codec = new PayloadCodec(mapper, validation.getValidator());

    @AfterEach
    void close() {
        validation.close();
    }

    @Test
    void noPasswordIsKeptHoweverDeepItIs() throws Exception {
        String json = codec.toJson(new Request("Acme", "s3cret", new Login("acme", "hunter2"),
                List.of(Map.of("sku", "W-1", "PASSWORD", "x", "customerPassword", "y")),
                new BigDecimal("10.50"), Instant.parse("2026-09-26T09:14:03Z")));

        assertThat(json).doesNotContainIgnoringCase("password").doesNotContain("s3cret", "hunter2");
        var tree = mapper.readTree(json);
        assertThat(tree.get("name").asText()).isEqualTo("Acme");
        assertThat(tree.get("login").get("username").asText()).isEqualTo("acme");
        assertThat(tree.get("lines").get(0).get("sku").asText()).isEqualTo("W-1");
        assertThat(tree.get("amount").decimalValue()).isEqualByComparingTo("10.50");
        assertThat(codec.toJson(null)).isNull();
    }

    @Test
    void aFieldTheRequestNoLongerHasIsIgnoredAndOneItGainedIsNull() {
        assertThat(codec.read("{\"invoiceId\":5,\"reason\":\"gone since\"}", CancelPayload.class))
                .isEqualTo(new CancelPayload(5L));
        assertThat(codec.read("{}", CancelPayload.class).invoiceId()).isNull();
        assertThat(codec.read(null, CancelPayload.class)).isNull();
    }

    @Test
    void aStoredRequestThatNoLongerPassesOrCannotBeReadIsAPayloadError() {
        assertThatThrownBy(() -> codec.validate(new Checked(null, new BigDecimal("-1"))))
                .isInstanceOf(PayloadException.class)
                .hasMessageContaining("amount").hasMessageContaining("id");
        assertThatThrownBy(() -> codec.validate(codec.read("{}", CancelPayload.class)))
                .isInstanceOf(PayloadException.class).hasMessageContaining("invoiceId");
        assertThatThrownBy(() -> codec.read("{not json", CancelPayload.class)).isInstanceOf(PayloadException.class);
        assertThatThrownBy(() -> codec.read("{\"invoiceId\":\"five\"}", CancelPayload.class))
                .isInstanceOf(PayloadException.class);

        codec.validate(new Checked(1L, BigDecimal.ONE));
    }
}
