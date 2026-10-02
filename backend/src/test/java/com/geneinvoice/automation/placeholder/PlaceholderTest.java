package com.geneinvoice.automation.placeholder;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.action.RecordContext;
import com.geneinvoice.automation.action.RecordContexts;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Placeholders (A4, §5.10.4): the levels offered on each kind of record, values read live for the record, the
 * person a role's work falls to named by the sender rule, blanks and fallbacks, one pass only, text fitted to its
 * field, POC details only for those who may see them, and an unknown key refused when the rule is saved.
 */
class PlaceholderTest extends AutomationTestBase {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired RecordContexts recordContexts;
    @Autowired PocService pocService;
    @Autowired TransactionTemplate tx;

    User admin;
    Customer acme;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        acme = customer("Acme Ltd", "billing@acme.test");
        widget = product("Widget", "1250.00");
        actAs(admin);
    }

    private String render(TriggerEntity type, Long id, String text) {
        return rendered(type, id, text).text();
    }

    /** As an action renders it: the record read and its values filled in within one transaction. */
    private Rendered rendered(TriggerEntity type, Long id, String text) {
        return rendered(type, id, text, 2000, false, "Title");
    }

    private Rendered rendered(TriggerEntity type, Long id, String text, int max, boolean oneLine, String field) {
        return tx.execute(status -> {
            RecordContext rc = recordContexts.load(type, id, null, "Chase overdue", UTC);
            return PlaceholderRenderer.render(text, rc.values(), max, oneLine, field);
        });
    }

    @Test
    void eachKindOfRecordOffersItsOwnLevels() throws Exception {
        assertThat(levels("CUSTOMER")).containsExactly("CUSTOMER", "COMMON");
        assertThat(levels("INVOICE")).containsExactly("CUSTOMER", "RECORD", "COMMON");
        assertThat(levels("PAYMENT")).containsExactly("CUSTOMER", "RECORD", "COMMON");
        assertThat(levels("DISPUTE")).as("an email about a dispute offers its customer's fields")
                .containsExactly("CUSTOMER", "COMMON");
        JsonNode invoice = body(mockMvc.perform(get("/api/automation/placeholders").with(as(admin))
                .param("entityType", "INVOICE")).andExpect(status().isOk()));
        JsonNode record = invoice.get("groups").get(1);
        assertThat(record.get("label").asText()).isEqualTo("Invoice");
        assertThat(record.get("items")).extracting(i -> i.get("token").asText())
                .contains("{{Invoice.Number}}", "{{Invoice.Balance}}", "{{Invoice.DaysOverdue}}")
                .noneMatch(t -> t.startsWith("{{Payment."));
        assertThat(invoice.get("groups").get(2).get("items")).extracting(i -> i.get("token").asText())
                .containsExactly("{{Today}}", "{{Rule.Name}}");

        mockMvc.perform(get("/api/automation/placeholders").with(as(admin)).param("entityType", "PRODUCT"))
                .andExpect(status().isBadRequest());
        User reader = user("rhea.reader", "VIEWER");
        mockMvc.perform(get("/api/automation/placeholders").with(as(reader)).param("entityType", "INVOICE"))
                .andExpect(status().isForbidden());
    }

    private List<String> levels(String entityType) throws Exception {
        JsonNode catalogue = body(mockMvc.perform(get("/api/automation/placeholders").with(as(admin))
                .param("entityType", entityType)).andExpect(status().isOk()));
        assertThat(catalogue.get("entityType").asText()).isEqualTo(entityType);
        return StreamSupport.stream(catalogue.get("groups").spliterator(), false)
                .map(g -> g.get("level").asText()).toList();
    }

    /**
     * Three Collection seats: the primary is no longer active, so the work falls to the longest-seated active one,
     * which is neither the first person made nor the last seat taken (the sender rule, X7).
     */
    @Test
    void threeCollectionSeatsNameThePersonTheSenderRuleNames() {
        User first = user("cole.first", DataSeeder.ROLE_COLLECTION_POC);
        User second = user("cole.second", DataSeeder.ROLE_COLLECTION_POC);
        User primary = user("cole.primary", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, second.getId(), false);
        pocService.add(acme.getId(), PocType.COLLECTION, first.getId(), false);
        pocService.add(acme.getId(), PocType.COLLECTION, primary.getId(), true);

        assertThat(render(TriggerEntity.CUSTOMER, acme.getId(), "{{Customer.CollectionPoc}}"))
                .isEqualTo("COLE.PRIMARY");
        primary.setActive(false);
        userRepository.save(primary);
        assertThat(render(TriggerEntity.CUSTOMER, acme.getId(),
                "{{Customer.CollectionPoc}} <{{Customer.CollectionPocEmail}}>"))
                .isEqualTo("COLE.SECOND <cole.second@test.local>");
    }

    @Test
    void aFallbackStandsInForABlankValueAndABlankWithoutOneIsWarnedOf() {
        Rendered withFallback = rendered(TriggerEntity.CUSTOMER, acme.getId(),
                "Call {{ Customer.Phone | the office }} about {{Customer.Name}}");
        assertThat(withFallback.text()).isEqualTo("Call the office about Acme Ltd");
        assertThat(withFallback.warnings()).isEmpty();

        Rendered blank = rendered(TriggerEntity.CUSTOMER, acme.getId(), "Call {{Customer.Phone}} ({{Customer.SuccessPoc}})");
        assertThat(blank.text()).isEqualTo("Call  ()");
        assertThat(blank.warnings()).containsExactly("Customer.Phone is empty", "Customer.SuccessPoc is empty");
    }

    @Test
    void aValueHoldingBracesIsPrintedAsItIs() {
        Customer odd = customer("{{Rule.Name}} and {{Customer.Email}}", "odd@acme.test");
        actAs(admin);
        assertThat(render(TriggerEntity.CUSTOMER, odd.getId(), "Dear {{Customer.Name}}, from {{Rule.Name}}"))
                .isEqualTo("Dear {{Rule.Name}} and {{Customer.Email}}, from Chase overdue");
    }

    @Test
    void aSubjectIsOneLineAndCutToItsFieldWithAWarning() {
        Customer multi = customerRepository.save(Customer.builder().name("Multi Co").region(defaultRegion())
                .address("1 High Street\nLondon\r\nUK").build());
        actAs(admin);
        Rendered subject = rendered(TriggerEntity.CUSTOMER, multi.getId(), "Visit {{Customer.Address}}", 500, true,
                "Subject");
        assertThat(subject.text()).isEqualTo("Visit 1 High Street London UK");
        assertThat(subject.warnings()).isEmpty();

        Customer longAddress = customerRepository.save(Customer.builder().name("Long Co").region(defaultRegion())
                .address("x".repeat(500)).build());
        actAs(admin);
        Rendered cut = rendered(TriggerEntity.CUSTOMER, longAddress.getId(), "Visit {{Customer.Address}}", 500, true,
                "Subject");
        assertThat(cut.text()).hasSize(500).endsWith("x…");
        assertThat(cut.warnings()).containsExactly("Subject was cut to 500 characters");
    }

    @Test
    void pocPlaceholdersAreLeftBlankForSomeoneWhoMayNotSeePocs() {
        User cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, cara.getId(), true);
        assertThat(render(TriggerEntity.CUSTOMER, acme.getId(), "{{Customer.CollectionPoc}}"))
                .isEqualTo("CARA.COLLECTIONS");

        User reader = user("nell.no.pocs", roleWith("CUSTOMERS_NO_POCS", Privileges.CUSTOMER_VIEW, Privileges.SCOPE_OVERRIDE).getName());
        actAs(reader);
        Rendered hidden = rendered(TriggerEntity.CUSTOMER, acme.getId(),
                "{{Customer.Name}}: {{Customer.CollectionPoc}}{{Customer.SuccessPoc|someone}}");
        assertThat(hidden.text()).isEqualTo("Acme Ltd: someone");
        assertThat(hidden.warnings()).containsExactly(
                "{{Customer.CollectionPoc}} is left out: POC details are not visible here",
                "{{Customer.SuccessPoc}} is left out: POC details are not visible here");
    }

    @Test
    void anInvoiceAndAPaymentOfferTheirOwnFiguresFormatted() {
        Long invoiceId = invoice(acme, widget, 2, null);
        Long paymentId = pay(acme, "500.00", invoiceId);
        String number = invoiceRepository.findById(invoiceId).orElseThrow().getInvoiceNumber();

        assertThat(render(TriggerEntity.INVOICE, invoiceId,
                "{{Invoice.Number}}: {{Invoice.Balance}} of {{Invoice.Total}}, {{Invoice.Status}}, overdue "
                        + "{{Invoice.Overdue}} ({{Invoice.DaysOverdue}} days), for {{Customer.Name}}"))
                .isEqualTo(number + ": ₹2,000.00 of ₹2,500.00, Partially paid, overdue No (0 days), for Acme Ltd");
        assertThat(render(TriggerEntity.CUSTOMER, acme.getId(),
                "{{Customer.Outstanding}} outstanding, {{Customer.OverdueAmount}} overdue, terms {{Customer.PaymentTerm}}"))
                .isEqualTo("₹2,000.00 outstanding, ₹0.00 overdue, terms Net 30");
        assertThat(render(TriggerEntity.PAYMENT, paymentId,
                "Payment {{Payment.Number}} of {{Payment.Amount}} by {{Payment.Method}} paid {{Payment.Invoices}}"))
                .isEqualTo("Payment #" + paymentId + " of ₹500.00 by Cash paid " + number);
    }

    @Test
    void anUnknownPlaceholderOrOneFromAnotherKindOfRecordIsRefusedAtSave() throws Exception {
        JsonNode unknown = body(send(post(RULES), admin, rule("Welcome", "CUSTOMER", "ENTITY_CREATED", null,
                task("Welcome {{Customer.Nmae}}"))).andExpect(status().isBadRequest()));
        assertThat(unknown.get("fieldErrors").get("actions[0].title").asText())
                .isEqualTo("Unknown placeholder {{Customer.Nmae}}");

        JsonNode foreign = body(send(post(RULES), admin, rule("Welcome", "CUSTOMER", "ENTITY_CREATED", null,
                task("Welcome {{Customer.Name}}"),
                email(null, List.of(Map.of("type", "CUSTOMER")), "Your invoice {{Invoice.Number}}", "Hi")))
                .andExpect(status().isBadRequest()));
        assertThat(foreign.get("fieldErrors").get("actions[1].subject").asText())
                .isEqualTo("{{Invoice.Number}} is not available on customer rules");
        assertThat(foreign.get("fieldErrors").has("actions[0].title")).isFalse();
        assertThat(automationRuleRepository.count()).isZero();
    }
}
