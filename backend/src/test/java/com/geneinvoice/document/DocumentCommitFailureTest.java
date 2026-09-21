package com.geneinvoice.document;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:geneinvoice-documents-commit-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000"
})
class DocumentCommitFailureTest extends DocumentTestBase {

    @Test
    void aFailureAfterTheRowIsWrittenLeavesTheDocumentWhole() throws Exception {
        FailsBuildingTheAnswer.failing = true;
        try {
            mockMvc.perform(uploadRequest(admin, "INVOICE", acmeInvoice.getId(),
                            part("po.pdf", pdf(), "application/pdf")))
                    .andExpect(status().isInternalServerError());
        } finally {
            FailsBuildingTheAnswer.failing = false;
        }

        assertThat(documentRepository.count()).isEqualTo(1);
        Document saved = documentRepository.findAll().get(0);
        Path stored = Path.of(documentProperties.getLocal().getRoot()).toAbsolutePath()
                .normalize().resolve(saved.getStorageKey());
        assertThat(Files.exists(stored)).isTrue();
        mockMvc.perform(get("/api/documents/" + saved.getId() + "/download").with(as(admin)))
                .andExpect(status().isOk());
    }

    static class FailsBuildingTheAnswer extends DocumentTargets {

        static volatile boolean failing;

        FailsBuildingTheAnswer(CustomerService customerService, InvoiceService invoiceService,
                               PaymentService paymentService, CurrentUser currentUser) {
            super(customerService, invoiceService, paymentService, currentUser);
        }

        @Override
        public boolean canManage(DocumentEntityType type) {
            if (failing) throw new IllegalStateException("nothing left to answer with");
            return super.canManage(type);
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        FailsBuildingTheAnswer failingTargets(CustomerService customerService,
                                              InvoiceService invoiceService,
                                              PaymentService paymentService,
                                              CurrentUser currentUser) {
            return new FailsBuildingTheAnswer(customerService, invoiceService, paymentService,
                    currentUser);
        }
    }
}
