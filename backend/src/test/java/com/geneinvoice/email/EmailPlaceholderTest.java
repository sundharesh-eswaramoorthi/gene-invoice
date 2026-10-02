package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.Money;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.DisputeDtos;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Placeholders in emails written by hand (A4, PC-10): filled in with the record's values as the sender sees it, in
 * the subject and the body; each row of a bulk send gets its own record's; one that is none is a 400 on its field
 * and nothing is saved; text without a placeholder is saved exactly as written; and a retry sends what was saved,
 * not the record's values now.
 */
class EmailPlaceholderTest extends EmailTestBase {

    @Autowired DisputeService disputeService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PaymentService paymentService;

    private Email stored(JsonNode sent) {
        return emailRepository.findById(sent.get("id").asLong()).orElseThrow();
    }

    @Test
    void aManualEmailHasItsPlaceholdersFilledInInTheSubjectAndTheBody() throws Exception {
        Email aboutAcme = stored(send(admin, email("CUSTOMER", acme.getId(), List.of(toCustomer()),
                "subject", "Hello {{Customer.Name}}",
                "body", "Dear {{Customer.Name}},\nwe write from {{ Customer.Region }}. {{Customer.Phone|No phone}}")));
        assertThat(aboutAcme.getSubject()).isEqualTo("Hello Acme Ltd");
        assertThat(aboutAcme.getBody()).isEqualTo("Dear Acme Ltd,\nwe write from "
                + defaultRegion().getName() + ". No phone");

        Invoice inv = invoice(acme, sales);
        Email aboutInvoice = stored(send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "Invoice {{Invoice.Number}} for {{Customer.Name}}", "body", "Total {{Invoice.Total}}")));
        assertThat(aboutInvoice.getSubject()).isEqualTo("Invoice " + inv.getInvoiceNumber() + " for Acme Ltd");
        assertThat(aboutInvoice.getBody()).startsWith("Total ").doesNotContain("{{");
    }

    @Test
    void eachRowOfABulkSendGetsItsOwnRecordsValues() throws Exception {
        List<Invoice> invoices = List.of(invoice(acme, sales), invoice(acme, sales), invoice(acme, sales));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("entityType", "INVOICE");
        params.put("to", List.of(toUser(admin)));
        params.put("subject", "Invoice {{Invoice.Number}}");
        params.put("body", "{{Customer.Name}} owes {{Invoice.Balance}}");
        Map<String, Object> bulk = new LinkedHashMap<>();
        bulk.put("action", "SEND_EMAIL");
        bulk.put("ids", invoices.stream().map(Invoice::getId).toList());
        bulk.put("params", params);

        JsonNode result = read(postJson("/api/emails/bulk", admin, bulk).andExpect(status().isOk()));

        assertThat(result.get("succeeded")).hasSize(3);
        List<Email> sent = emailRepository.findAll().stream().sorted(Comparator.comparing(Email::getEntityId))
                .toList();
        assertThat(sent).extracting(Email::getSubject).containsExactlyElementsOf(invoices.stream()
                .sorted(Comparator.comparing(Invoice::getId)).map(i -> "Invoice " + i.getInvoiceNumber()).toList());
        assertThat(sent).allSatisfy(e -> assertThat(e.getBody()).startsWith("Acme Ltd owes "));
    }

    @Test
    void aPlaceholderThatIsNoneOrNotOfferedOnTheRecordIsRefusedOnItsField() throws Exception {
        Invoice inv = invoice(acme, sales);
        JsonNode unknown = read(postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "About {{Invoice.Nmber}}")).andExpect(status().isBadRequest()));
        assertThat(unknown.get("fieldErrors").get("subject").asText())
                .isEqualTo("Unknown placeholder {{Invoice.Nmber}}");

        JsonNode notOffered = read(postJson("/api/emails", admin, email("CUSTOMER", acme.getId(),
                List.of(toCustomer()), "body", "Your invoice {{Invoice.Number}}")).andExpect(status().isBadRequest()));
        assertThat(notOffered.get("fieldErrors").get("body").asText())
                .isEqualTo("{{Invoice.Number}} is not available on customer emails");

        // No rule sends an email written by hand, so it has no rule name to give.
        JsonNode noRule = read(postJson("/api/emails", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "body", "Sent by {{Rule.Name}}")).andExpect(status().isBadRequest()));
        assertThat(noRule.get("fieldErrors").get("body").asText())
                .isEqualTo("{{Rule.Name}} is not available on invoice emails");

        read(postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "About {{Nothing}}")).andExpect(status().isBadRequest()));
        assertThat(emailRepository.findAll()).as("nothing saved").isEmpty();

        // The preview tells what would come out empty.
        JsonNode preview = read(postJson("/api/emails/preview", admin, email("INVOICE", inv.getId(),
                List.of(toCustomer()), "subject", "About {{Invoice.Number}}", "body", "Call {{Customer.Phone}}"))
                .andExpect(status().isOk()));
        assertThat(preview.get("warnings").toString()).contains("Customer.Phone is empty");
    }

    @Test
    void textWithoutAPlaceholderIsSavedExactlyAsWritten() throws Exception {
        String body = "Line one\n  { not a placeholder } and {single} braces }{\n\tLine three  ";
        Email plain = stored(send(admin, email("CUSTOMER", acme.getId(), List.of(toCustomer()),
                "subject", "Your {account} summary", "body", body)));
        assertThat(plain.getSubject()).isEqualTo("Your {account} summary");
        assertThat(plain.getBody()).isEqualTo(body);

        // A record that offers no placeholders keeps them as written.
        Email aboutProduct = stored(send(admin, email("PRODUCT", widget.getId(), List.of(toUser(sales)),
                "subject", "Price of {{Customer.Name}}", "body", "Hello")));
        assertThat(aboutProduct.getSubject()).isEqualTo("Price of {{Customer.Name}}");
    }

    /**
     * A promise or a dispute is seen outside its customer's book (a dispute has none, a promise is the Collection
     * POC's), so its email fills in the customer's fields only for a caller who could open the customer (SC-1):
     * anyone else gets the name the record already shows, the rest empty, and a warning.
     */
    @Test
    void aCustomerTheCallerCannotOpenLendsAPromiseOrADisputesEmailOnlyItsName() throws Exception {
        Customer globex = customer("Globex", "ap@globex.test");
        globex.setPhone("+1-555-0100");
        globex.setAddress("1 Secret Street");
        globex = customerRepository.save(globex);
        Invoice othersInvoice = invoice(globex, otherSales);
        User globexLogin = customerUser("globex.login", globex.getId());
        actAs(globexLogin);
        Long hiddenDispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                othersInvoice.getId(), "Wrong quantity", null)).getId();
        User cody = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        grantRepository.deleteAllOfUser(cody.getId());
        grant(cody, defaultRegion(), AccessLevel.MANAGE, RecordScope.BOOK);
        actAs(admin);
        Long hiddenPromise = promiseService.create(new PromiseDtos.CreatePromiseRequest(globex.getId(),
                new BigDecimal("100.00"), Today.date().plusDays(5), cody.getId(), null, null)).id();
        // A payment is the Collection POC's too, and reaches its customer through the record, not its book.
        Long hiddenPayment = paymentService.record(new PaymentDtos.CreatePaymentRequest(globex.getId(),
                new BigDecimal("50.00"), "Cash", null, null, cody.getId(), null)).getId();

        String body = "name={{Customer.Name}} phone={{Customer.Phone}} addr={{Customer.Address}} "
                + "out={{Customer.Outstanding}}";
        String outstanding = Money.format(new BigDecimal("1200.00"));
        getOk("/api/disputes/" + hiddenDispute, sales);
        mockMvc.perform(get("/api/customers/" + globex.getId()).with(as(sales))).andExpect(status().isNotFound());
        getOk("/api/promises/" + hiddenPromise, cody);
        mockMvc.perform(get("/api/customers/" + globex.getId()).with(as(cody))).andExpect(status().isNotFound());

        Email onDispute = stored(send(sales, email("DISPUTE", hiddenDispute, List.of(toUser(sales)),
                "subject", "About {{Customer.Phone}}", "body", body)));
        assertThat(onDispute.getSubject()).isEqualTo("About");
        assertThat(onDispute.getBody()).isEqualTo("name=Globex phone= addr= out=");
        Email onPromise = stored(send(cody, email("PROMISE", hiddenPromise, List.of(toUser(cody)),
                "subject", "Promise", "body", body)));
        assertThat(onPromise.getBody()).isEqualTo("name=Globex phone= addr= out=");
        Email onPayment = stored(send(cody, email("PAYMENT", hiddenPayment, List.of(toUser(cody)),
                "subject", "Payment", "body", body)));
        assertThat(onPayment.getBody()).isEqualTo("name=Globex phone= addr= out=");
        for (Email e : List.of(onDispute, onPromise, onPayment)) {
            assertThat(e.getSubject() + e.getBody()).doesNotContain("+1-555-0100").doesNotContain("1 Secret Street")
                    .doesNotContain(outstanding);
        }
        JsonNode preview = read(postJson("/api/emails/preview", sales, email("DISPUTE", hiddenDispute,
                List.of(toUser(sales)), "subject", "Hello", "body", body)).andExpect(status().isOk()));
        assertThat(preview.get("warnings").toString())
                .contains("Customer details are left out: you cannot see this record's customer")
                .contains("Customer.Phone is empty");

        // On a customer of their own, the same callers get its fields.
        acme.setPhone("+1-555-0199");
        acme.setAddress("2 Open Road");
        acme = customerRepository.save(acme);
        Invoice ownInvoice = invoice(acme, sales);
        actAs(acmeLogin);
        Long ownDispute = disputeService.open(new DisputeDtos.CreateDisputeRequest(DisputeTargetType.INVOICE,
                ownInvoice.getId(), "Wrong quantity", null)).getId();
        seat(acme, PocType.COLLECTION, cody);
        actAs(admin);
        Long ownPromise = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(),
                new BigDecimal("100.00"), Today.date().plusDays(5), cody.getId(), null, null)).id();
        String rendered = "name=Acme Ltd phone=+1-555-0199 addr=2 Open Road out=" + outstanding;
        assertThat(stored(send(sales, email("DISPUTE", ownDispute, List.of(toUser(sales)),
                "subject", "Dispute", "body", body))).getBody()).isEqualTo(rendered);
        assertThat(stored(send(cody, email("PROMISE", ownPromise, List.of(toUser(cody)),
                "subject", "Promise", "body", body))).getBody()).isEqualTo(rendered);
    }

    @Test
    void aRetrySendsWhatWasSavedNotTheRecordsValuesNow() throws Exception {
        mailTransport.mode(RecordingMailTransport.Mode.PERMANENT_FAILURE);
        Email first = stored(send(admin, email("CUSTOMER", acme.getId(), List.of(toCustomer()),
                "subject", "Hello {{Customer.Name}}", "body", "For {{Customer.Name}}")));
        assertThat(emailRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(EmailStatus.FAILED);

        Customer renamed = customerRepository.findById(acme.getId()).orElseThrow();
        renamed.setName("Acme Holdings");
        customerRepository.save(renamed);
        mailTransport.mode(RecordingMailTransport.Mode.SUCCESS);
        read(postJson("/api/emails/" + first.getId() + "/retry", admin, Map.of()).andExpect(status().isOk()));

        assertThat(mailTransport.submissions()).last().satisfies(s -> {
            assertThat(s.subject()).isEqualTo("Hello Acme Ltd");
            assertThat(s.body()).isEqualTo("For Acme Ltd");
        });
        assertThat(emailRepository.findById(first.getId()).orElseThrow().getSubject()).isEqualTo("Hello Acme Ltd");
    }
}
