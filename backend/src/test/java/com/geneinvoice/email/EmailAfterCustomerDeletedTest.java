package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailAfterCustomerDeletedTest extends EmailTestBase {

    @Autowired EmailCascade emailCascade;
    @Autowired PlatformTransactionManager transactionManager;

    private int cascade(Long customerId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> emailCascade.onCustomerDeleted(customerId));
    }

    @Test
    void anEmailOnADeletedCustomerKeepsItsContentButLosesItsLink() throws Exception {
        Customer doomed = customer("Mail Orphan", "orphan@t.example");
        JsonNode sent = send(admin, email("CUSTOMER", doomed.getId(), List.of(toCustomer())));
        long emailId = sent.get("id").asLong();
        assertThat(sent.get("entityLink").asText()).isEqualTo("/customers/" + doomed.getId());

        userRepository.findByCustomerId(doomed.getId()).ifPresent(userRepository::delete);
        mockMvc.perform(delete("/api/customers/" + doomed.getId()).with(as(admin)))
                .andExpect(status().isOk());

        JsonNode after = getOk("/api/emails/" + emailId, admin);
        assertThat(after.get("subject").asText())
                .as("the email itself is kept: it is a record of something that was said")
                .isEqualTo("About your account");
        assertThat(after.get("entityLink").isNull())
                .as("no link, because there is nothing at the other end of it")
                .isTrue();
        assertThat(after.get("entityLabel").asText())
                .as("and the reader is told why")
                .endsWith(" (deleted)");
    }

    @Test
    void anotherCustomersEmailsAreLeftAlone() throws Exception {
        Customer doomed = customer("Mail Orphan", "orphan@t.example");
        JsonNode keptEmail = send(admin, email("CUSTOMER", acme.getId(), List.of(toCustomer())));

        cascade(doomed.getId());

        JsonNode after = getOk("/api/emails/" + keptEmail.get("id").asLong(), admin);
        assertThat(after.get("entityLink").asText()).isEqualTo("/customers/" + acme.getId());
        assertThat(after.get("entityLabel").asText()).doesNotContain("(deleted)");
    }

    @Test
    void markingTheSameCustomerTwiceChangesNothingTheSecondTime() throws Exception {
        Customer doomed = customer("Mail Orphan", "orphan@t.example");
        send(admin, email("CUSTOMER", doomed.getId(), List.of(toCustomer())));

        assertThat(cascade(doomed.getId())).isEqualTo(1);
        assertThat(cascade(doomed.getId())).isZero();
    }
}
