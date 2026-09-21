package com.geneinvoice.invoice;

import com.geneinvoice.common.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTermTest {

    @Test
    void everyTermButCustomTurnsAnInvoiceDateIntoADueDate() {
        LocalDate raised = LocalDate.of(2026, 9, 20);

        assertThat(PaymentTerm.DUE_ON_RECEIPT.due(raised)).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(PaymentTerm.NET_15.due(raised)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(PaymentTerm.NET_30.due(raised)).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(PaymentTerm.NET_45.due(raised)).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(PaymentTerm.NET_60.due(raised)).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(PaymentTerm.NET_90.due(raised)).isEqualTo(LocalDate.of(2026, 12, 19));
    }

    @Test
    void aDueDateLandingOnAWeekendIsLeftWhereItFalls() {
        assertThat(PaymentTerm.NET_30.due(LocalDate.of(2026, 8, 20)))
                .isEqualTo(LocalDate.of(2026, 9, 19));
    }

    @Test
    void customHasNoDaysAndCannotWorkOutADateOfItsOwn() {
        assertThat(PaymentTerm.CUSTOM.days()).isNull();
        assertThatThrownBy(() -> PaymentTerm.CUSTOM.due(LocalDate.of(2026, 9, 20)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(PaymentTerm.SETTABLE).doesNotContain(PaymentTerm.CUSTOM)
                .containsExactly(PaymentTerm.DUE_ON_RECEIPT, PaymentTerm.NET_15, PaymentTerm.NET_30,
                        PaymentTerm.NET_45, PaymentTerm.NET_60, PaymentTerm.NET_90);
    }

    @Test
    void labelsAreWhatTheScreensShow() {
        assertThat(PaymentTerm.DUE_ON_RECEIPT.label()).isEqualTo("Due on receipt");
        assertThat(PaymentTerm.NET_30.label()).isEqualTo("Net 30");
        assertThat(PaymentTerm.CUSTOM.label()).isEqualTo("Custom");
    }

    @Test
    void aTermIsReadFromTextWhateverItsCaseAndAnUnknownOneNamesTheChoices() {
        assertThat(PaymentTerm.parse("net_60")).isEqualTo(PaymentTerm.NET_60);
        assertThat(PaymentTerm.parse("  NET_30 ")).isEqualTo(PaymentTerm.NET_30);

        assertThatThrownBy(() -> PaymentTerm.parse("NET_7"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("NET_30")
                .hasMessageContaining("CUSTOM");
    }

    @Test
    void theSystemDefaultIsNetThirty() {
        assertThat(PaymentTerm.SYSTEM_DEFAULT).isEqualTo(PaymentTerm.NET_30);
    }
}
