package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.role.Role;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Placeholders a sender writes into a subject or a body, what they are offered on each kind of
 * record, and what they come to when the email is written (M1 to M6).
 *
 * <p>The offer and the send are meant to be one code path, so these tests read the sample the
 * compose form would show and then check that exactly that string is what ends up stored on the
 * email — a placeholder that previewed one way and sent another would be worse than none.
 */
class EmailPlaceholderTest extends EmailTestBase {

    @Autowired PaymentService paymentService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired PrivilegeRepository privilegeRepository;

    // ---- what is offered (M1) --------------------------------------------------------

    @Test
    void bothLevelsAreOfferedOnARecordThatHasOneAndTheCustomersAloneOnOneThatDoesNot() throws Exception {
        Invoice inv = invoice(acme, sales);
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Bank transfer", null, List.of(), collections.getId(), null));
        PromiseDtos.PromiseDto promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(
                acme.getId(), new BigDecimal("500.00"), LocalDate.now().plusDays(7),
                collections.getId(), null, null, null));

        // An invoice belongs to a customer and has figures of its own, so both levels are offered:
        // the customer's fields first, because that is the level every record with a customer has.
        assertThat(groups(admin, "INVOICE", inv.getId())).containsExactly("Customer", "Invoice");
        assertThat(keys(admin, "INVOICE", inv.getId())).containsExactly(
                "{{Customer.Name}}", "{{Customer.Email}}", "{{Customer.Phone}}", "{{Customer.Outstanding}}",
                "{{Customer.PaymentTerm}}", "{{Customer.SalesPoc.Name}}", "{{Customer.CollectionPoc.Name}}",
                "{{Customer.CustomerSuccessPoc.Name}}",
                "{{Invoice.Number}}", "{{Invoice.Total}}", "{{Invoice.Balance}}", "{{Invoice.DueDate}}",
                "{{Invoice.IssueDate}}", "{{Invoice.Status}}", "{{Invoice.DaysPastDue}}",
                "{{Invoice.SalesPoc.Name}}");

        assertThat(groups(admin, "PAYMENT", payment.getId())).containsExactly("Customer", "Payment");
        assertThat(keys(admin, "PAYMENT", payment.getId())).contains("{{Customer.Name}}",
                "{{Payment.Reference}}", "{{Payment.Amount}}", "{{Payment.CollectionPoc.Name}}");

        // A customer is its own level and has no second one; a promise has a customer but no
        // figures the app offers, so both are offered the customer's fields and nothing else.
        assertThat(groups(admin, "CUSTOMER", acme.getId())).containsExactly("Customer");
        assertThat(groups(admin, "PROMISE", promise.id())).containsExactly("Customer");
        // A product belongs to nobody, so there is no level to offer at all.
        assertThat(groups(admin, "PRODUCT", widget.getId())).isEmpty();
    }

    @Test
    void eachSampleIsWhatThatPlaceholderSaysOnThisVeryRecord() throws Exception {
        Customer initech = customerRepository.save(Customer.builder()
                .name("Initech Pvt Ltd").email("ap@initech.test").phone("+91 80 4000 1234")
                .paymentTerm(PaymentTerm.NET_45).build());
        // A lakh and a fifth, so the grouping is the app's own rupee grouping and not a locale's.
        Invoice inv = bigInvoice(initech, sales, 100);
        seat(initech, PocType.SUCCESS, success);

        Map<String, String> samples = samples(admin, "INVOICE", inv.getId());

        assertThat(samples).containsEntry("{{Customer.Name}}", "Initech Pvt Ltd")
                .containsEntry("{{Customer.Email}}", "ap@initech.test")
                .containsEntry("{{Customer.Phone}}", "+91 80 4000 1234")
                // Money reads exactly as every screen and notification writes it (PPD-06).
                .containsEntry("{{Customer.Outstanding}}", "₹1,20,000.00")
                .containsEntry("{{Customer.PaymentTerm}}", "Net 45")
                .containsEntry("{{Customer.CustomerSuccessPoc.Name}}", "SUE.SUCCESS")
                // Nobody holds the Collection seat here, and an empty sample is the warning that
                // the placeholder has nobody to name — never an error and never the key left behind.
                .containsEntry("{{Customer.CollectionPoc.Name}}", "")
                // The Sales POC is assigned per invoice and the app seats none on a customer
                // (CP-01), so this one is offered and always empty; the invoice's names a person.
                .containsEntry("{{Customer.SalesPoc.Name}}", "")
                .containsEntry("{{Invoice.SalesPoc.Name}}", "SAM.SALES");

        assertThat(samples).containsEntry("{{Invoice.Number}}", inv.getInvoiceNumber())
                .containsEntry("{{Invoice.Total}}", "₹1,20,000.00")
                .containsEntry("{{Invoice.Balance}}", "₹1,20,000.00")
                .containsEntry("{{Invoice.Status}}", "Unpaid")
                // Not overdue, so nothing is past due — the figure the invoice list shows (D3).
                .containsEntry("{{Invoice.DaysPastDue}}", "0")
                // Dates are yyyy-MM-dd, and the due date is the one the customer's terms produced.
                .containsEntry("{{Invoice.DueDate}}", InvoiceDates.today().plusDays(45).toString())
                .containsEntry("{{Invoice.IssueDate}}", LocalDate.now(ZoneId.systemDefault()).toString());

        // Null terms mean "whatever the deployment's default is", which is not a fact about this
        // customer, so nothing is put in its place (D1).
        assertThat(samples(admin, "CUSTOMER", acme.getId())).containsEntry("{{Customer.PaymentTerm}}", "");
    }

    @Test
    void anOverdueInvoiceCountsItsDaysPastDueAgainstTheDayTheAppItselfDecidesOverdueBy() throws Exception {
        LocalDate today = InvoiceDates.today();
        actAs(admin);
        Invoice inv = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(),
                // Noon UTC, so the instant falls on the same calendar day wherever the app runs.
                today.minusDays(40).atTime(12, 0).toInstant(ZoneOffset.UTC),
                today.minusDays(9), PaymentTerm.CUSTOM, null, sales.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));

        Map<String, String> samples = samples(admin, "INVOICE", inv.getId());

        assertThat(samples).containsEntry("{{Invoice.DueDate}}", today.minusDays(9).toString())
                .containsEntry("{{Invoice.IssueDate}}", today.minusDays(40).toString())
                // Counted against the app's own today (AC-A9), so an email never calls an invoice
                // late that the invoice list still calls current.
                .containsEntry("{{Invoice.DaysPastDue}}", "9");
    }

    @Test
    void aPaymentsPlaceholdersAreItsOwnFiguresAndTheOneCollectionPocTheRecordStores() throws Exception {
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("150000.50"), "Bank transfer", null, List.of(), collections.getId(), null));
        // The customer's seat is somebody else's, so a record-level placeholder naming the payment's
        // own POC proves it read the record and not the book (L3).
        seat(acme, PocType.COLLECTION, user("cora.collections", DataSeeder.ROLE_COLLECTION_POC));

        Map<String, String> samples = samples(admin, "PAYMENT", payment.getId());

        assertThat(samples)
                // A payment carries no reference of its own, so it reads as the app labels it.
                .containsEntry("{{Payment.Reference}}", "Payment #" + payment.getId())
                .containsEntry("{{Payment.Id}}", String.valueOf(payment.getId()))
                .containsEntry("{{Payment.Amount}}", "₹1,50,000.50")
                .containsEntry("{{Payment.Method}}", "Bank transfer")
                .containsEntry("{{Payment.Status}}", "Active")
                .containsEntry("{{Payment.PaidAt}}", LocalDate.now(ZoneId.systemDefault()).toString())
                .containsEntry("{{Payment.CollectionPoc.Name}}", "CARA.COLLECTIONS")
                .containsEntry("{{Customer.CollectionPoc.Name}}", "CORA.COLLECTIONS");
    }

    // ---- filling in (M3) -------------------------------------------------------------

    @Test
    void aPreviewAndASendFillInTheSameTextAndWhatIsStoredIsTheFilledText() throws Exception {
        Invoice inv = bigInvoice(acme, sales, 100);
        String subject = "{{Invoice.Number}} for {{Customer.Name}}";
        String body = "Balance {{Invoice.Balance}} is due on {{Invoice.DueDate}}.\nYour rep is {{Invoice.SalesPoc.Name}}.";
        String filledSubject = inv.getInvoiceNumber() + " for Acme Ltd";
        String filledBody = "Balance ₹1,20,000.00 is due on " + InvoiceDates.today().plusDays(30)
                + ".\nYour rep is SAM.SALES.";

        JsonNode preview = read(postJson("/api/emails/preview", admin,
                email("INVOICE", inv.getId(), List.of(toCustomer()), "subject", subject, "body", body))
                .andExpect(status().isOk()));
        assertThat(preview.get("subject").asText()).isEqualTo(filledSubject);
        assertThat(preview.get("body").asText()).isEqualTo(filledBody);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", subject, "body", body));

        assertThat(sent.get("subject").asText()).isEqualTo(filledSubject);
        assertThat(sent.get("body").asText()).isEqualTo(filledBody);
        // The row itself, not just the answer: the Email tab and the Inbox must show what the
        // customer actually read, so nothing re-renders the original text later.
        Email stored = emailRepository.findById(sent.get("id").asLong()).orElseThrow();
        assertThat(stored.getSubject()).isEqualTo(filledSubject);
        assertThat(stored.getBody()).isEqualTo(filledBody);
        assertThat(stored.getSubject()).doesNotContain("{{");
        assertThat(getOk("/api/emails", admin, "entityType", "INVOICE", "entityId", inv.getId().toString())
                .at("/content/0/subject").asText()).isEqualTo(filledSubject);
    }

    @Test
    void aPlaceholderNobodyRecognisesIsLeftExactlyAsItWasTyped() throws Exception {
        Invoice inv = invoice(acme, sales);

        JsonNode preview = read(postJson("/api/emails/preview", admin,
                email("INVOICE", inv.getId(), List.of(toCustomer()),
                        // A misspelling, a placeholder from another level, and one that is nothing
                        // at all — beside a good one, which still fills in.
                        "subject", "{{Invoice.Numbr}} for {{Customer.Name}}",
                        "body", "{{Payment.Amount}} {{ }} {{Customer.Name}"))
                .andExpect(status().isOk()));

        assertThat(preview.get("subject").asText()).isEqualTo("{{Invoice.Numbr}} for Acme Ltd");
        // Never blanked and never an error: the writer sees their own typing in the preview, which
        // is where they can still fix it (M5).
        assertThat(preview.get("body").asText()).isEqualTo("{{Payment.Amount}} {{ }} {{Customer.Name}");

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "{{Invoice.Numbr}} for {{Customer.Name}}", "body", "{{Payment.Amount}}"));
        assertThat(sent.get("subject").asText()).isEqualTo("{{Invoice.Numbr}} for Acme Ltd");
        assertThat(sent.get("body").asText()).isEqualTo("{{Payment.Amount}}");
    }

    @Test
    void aKeyIsReadWithoutRegardToCaseOrSpacingAndAFilledInValueIsNeverFilledInAgain() throws Exception {
        // A customer who named themselves after a placeholder: the pass runs once over what was
        // typed, so their name is put in and stays put in (M5).
        Customer awkward = customerRepository.save(Customer.builder()
                .name("{{Invoice.Total}}").email("ap@awkward.test").build());
        Invoice inv = bigInvoice(awkward, sales, 100);

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "subject", "{{ customer.NAME }} owes us", "body", "{{CUSTOMER.OUTSTANDING}}"));

        assertThat(sent.get("subject").asText()).isEqualTo("{{Invoice.Total}} owes us");
        assertThat(sent.get("subject").asText()).doesNotContain("₹");
        assertThat(sent.get("body").asText()).isEqualTo("₹1,20,000.00");
    }

    // ---- a role several people hold (L5) ---------------------------------------------

    @Test
    void aRoleHeldBySeveralPeopleFillsInThePrimary() throws Exception {
        Invoice inv = invoice(acme, sales);
        User cora = threeCollectionSeats(acme).get(0);

        // Three people sit in the Collection seat — cara is the primary although she was seated
        // last, cora is active beside her, cody is inactive — and an email is written to one of
        // them, so the placeholder names the primary.
        assertThat(samples(admin, "INVOICE", inv.getId()))
                .containsEntry("{{Customer.CollectionPoc.Name}}", "CARA.COLLECTIONS");

        JsonNode sent = send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "body", "Please reply to {{Customer.CollectionPoc.Name}}."));
        assertThat(sent.get("body").asText()).isEqualTo("Please reply to CARA.COLLECTIONS.");
        assertThat(sent.get("body").asText()).doesNotContain("CORA");

        // With the primary gone, the seat's next active holder is who the email names — the same
        // person a new record would be assigned to, so the text and the app never disagree.
        deactivate(collections);
        assertThat(samples(admin, "INVOICE", inv.getId()))
                .containsEntry("{{Customer.CollectionPoc.Name}}", "CORA.COLLECTIONS");
        assertThat(send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "body", "Please reply to {{Customer.CollectionPoc.Name}}."))
                .get("body").asText()).isEqualTo("Please reply to " + cora.getFullName() + ".");
    }

    // ---- who may be named (AC-A8, AC-A6) ---------------------------------------------

    /**
     * A POC placeholder names a member of staff, and a customer login never learns staff identity
     * (AC-A8) — which is the one thing the compose form, the recipient list and a promise's
     * assignees all already take care over. The placeholder list used to hand the same names
     * straight back to any login that could open it, and {@code EMAIL_SEND} is a privilege the
     * seeded CUSTOMER role holds.
     */
    @Test
    void aCustomerLoginIsOfferedNoPlaceholderThatWouldNameStaff() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);
        seat(acme, PocType.SUCCESS, success);

        // Staff are offered them, and every one of them names somebody — so what follows is the
        // masking and not an account with nobody on it.
        assertThat(samples(admin, "INVOICE", inv.getId()))
                .containsEntry("{{Customer.CollectionPoc.Name}}", "CARA.COLLECTIONS")
                .containsEntry("{{Customer.CustomerSuccessPoc.Name}}", "SUE.SUCCESS")
                .containsEntry("{{Invoice.SalesPoc.Name}}", "SAM.SALES");

        Map<String, String> theirs = samples(acmeLogin, "INVOICE", inv.getId());

        // Their own record's figures are theirs to write with; the seats are not offered at all.
        assertThat(theirs).containsEntry("{{Customer.Name}}", "Acme Ltd")
                .containsKey("{{Invoice.Balance}}").containsKey("{{Invoice.DueDate}}");
        assertThat(theirs.keySet()).doesNotContain(
                "{{Customer.SalesPoc.Name}}", "{{Customer.CollectionPoc.Name}}",
                "{{Customer.CustomerSuccessPoc.Name}}", "{{Invoice.SalesPoc.Name}}");
        assertThat(theirs.toString()).doesNotContain("CARA", "SUE", "SAM");
    }

    /** The same on a payment, whose record-level seat is its own Collection POC (L3). */
    @Test
    void aCustomerLoginIsOfferedNoPlaceholderThatWouldNameThePaymentsOwnPoc() throws Exception {
        actAs(admin);
        Payment payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("500.00"), "Bank transfer", null, List.of(), collections.getId(), null));

        Map<String, String> theirs = samples(acmeLogin, "PAYMENT", payment.getId());

        assertThat(theirs).containsEntry("{{Payment.Amount}}", "₹500.00");
        assertThat(theirs.keySet()).doesNotContain("{{Payment.CollectionPoc.Name}}");
        assertThat(theirs.toString()).doesNotContain("CARA");
    }

    /**
     * The offer and the send are one code path (M4), so a key a writer is not offered is one their
     * send does not fill in either: it comes back exactly as they typed it, as any other key the
     * record does not know does (M5). Blanking it instead would have deleted a line of their email
     * and, worse, made an empty sample ambiguous — an empty sample means an empty seat (L5).
     */
    @Test
    void aCustomerLoginSendsThePlaceholderItselfRatherThanTheStaffNameBehindIt() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);
        String body = "Ask {{Customer.CollectionPoc.Name}} or {{Invoice.SalesPoc.Name}} about {{Invoice.Number}}.";
        String asTyped = "Ask {{Customer.CollectionPoc.Name}} or {{Invoice.SalesPoc.Name}} about "
                + inv.getInvoiceNumber() + ".";

        JsonNode preview = read(postJson("/api/emails/preview", acmeLogin,
                email("INVOICE", inv.getId(), List.of(toCustomer()), "body", body))
                .andExpect(status().isOk()));
        assertThat(preview.get("body").asText()).isEqualTo(asTyped);

        JsonNode sent = send(acmeLogin, email("INVOICE", inv.getId(), List.of(toCustomer()), "body", body));

        // The stored row, not just the answer: this is the text the mail service carries out.
        Email stored = emailRepository.findById(sent.get("id").asLong()).orElseThrow();
        assertThat(stored.getBody()).isEqualTo(asTyped);
        assertThat(stored.getBody()).doesNotContain("CARA", "SAM");

        // Staff writing the very same text on the very same record do get the names, so the email
        // above is short of them because of who wrote it and not because the seats are empty.
        assertThat(send(admin, email("INVOICE", inv.getId(), List.of(toCustomer()), "body", body))
                .get("body").asText()).isEqualTo("Ask CARA.COLLECTIONS or SAM.SALES about "
                + inv.getInvoiceNumber() + ".");
    }

    /**
     * POC identity is {@code POC_VIEW}'s to give — the invoice list, the payment list and a
     * promise all ask that and no other question (AC-A6) — so an internal writer without it is
     * offered the seats no more than a customer login is.
     */
    @Test
    void aStaffWriterWithoutPocViewIsOfferedNoPlaceholderThatWouldNameAPoc() throws Exception {
        Invoice inv = invoice(acme, sales);
        seat(acme, PocType.COLLECTION, collections);
        User writer = user("wanda.writer", pocBlindRole().getName());

        Map<String, String> theirs = samples(writer, "INVOICE", inv.getId());

        assertThat(theirs).containsEntry("{{Customer.Name}}", "Acme Ltd");
        assertThat(theirs.keySet()).doesNotContain(
                "{{Customer.CollectionPoc.Name}}", "{{Invoice.SalesPoc.Name}}");
        assertThat(send(writer, email("INVOICE", inv.getId(), List.of(toCustomer()),
                "body", "Ask {{Customer.CollectionPoc.Name}}."))
                .get("body").asText()).isEqualTo("Ask {{Customer.CollectionPoc.Name}}.");
    }

    // ---- one text, many records (M2) -------------------------------------------------

    @Test
    void aBulkSendFillsInEachRecordsOwnPlaceholdersAgainstItsOwnRecord() throws Exception {
        Customer globex = customer("Globex Corp", "ap@globex.test");
        Invoice acmes = bigInvoice(acme, sales, 100);
        Invoice globexs = bigInvoice(globex, sales, 2);

        JsonNode result = read(postJson("/api/emails/bulk", admin, Map.of(
                "action", "SEND_EMAIL",
                "ids", List.of(acmes.getId(), globexs.getId()),
                "params", Map.of(
                        "entityType", "INVOICE",
                        "to", List.of(toCustomer()),
                        "subject", "{{Customer.Name}}: invoice {{Invoice.Number}}",
                        "body", "You owe {{Invoice.Balance}}.")))
                .andExpect(status().isOk()));

        assertThat(result.get("succeeded")).hasSize(2);
        // Writing one text for many is only worth doing if every customer is greeted by their own
        // name and told their own figures, so each row is checked against its own record.
        List<Email> emails = emailRepository.findAll().stream()
                .sorted(Comparator.comparing(Email::getId)).toList();
        assertThat(emails).extracting(Email::getSubject).containsExactly(
                "Acme Ltd: invoice " + acmes.getInvoiceNumber(),
                "Globex Corp: invoice " + globexs.getInvoiceNumber());
        assertThat(emails).extracting(Email::getBody).containsExactly(
                "You owe ₹1,20,000.00.", "You owe ₹2,400.00.");
        assertThat(emails).extracting(Email::getBatchId).doesNotContainNull();
    }

    // ---- helpers ---------------------------------------------------------------------

    /** Writes email about an invoice, and may not be told who any POC is. */
    private Role pocBlindRole() {
        return roleRepository.findByName("EMAIL_NO_POC").orElseGet(() ->
                roleRepository.save(Role.builder().name("EMAIL_NO_POC")
                        .description("Writes email, sees no POC identity")
                        .privileges(new HashSet<>(Set.of(
                                privilegeRepository.findByName(Privileges.CUSTOMER_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.INVOICE_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.PAYMENT_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.SCOPE_OVERRIDE).orElseThrow(),
                                privilegeRepository.findByName(Privileges.EMAIL_VIEW).orElseThrow(),
                                privilegeRepository.findByName(Privileges.EMAIL_SEND).orElseThrow())))
                        .build()));
    }

    /** An invoice for a round number of widgets, so its total is worth grouping. */
    private Invoice bigInvoice(Customer customer, User salesPoc, int quantity) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), quantity, null))));
    }

    /** Every offered placeholder and what it says on this record, keyed as the form shows it. */
    private Map<String, String> samples(User caller, String entityType, Long entityId) throws Exception {
        Map<String, String> samples = new LinkedHashMap<>();
        for (JsonNode group : offered(caller, entityType, entityId)) {
            for (JsonNode placeholder : group.get("placeholders")) {
                samples.put(placeholder.get("key").asText(), placeholder.get("sample").asText());
            }
        }
        return samples;
    }

    private List<String> groups(User caller, String entityType, Long entityId) throws Exception {
        return StreamSupport.stream(offered(caller, entityType, entityId).spliterator(), false)
                .map(g -> g.get("label").asText()).toList();
    }

    private List<String> keys(User caller, String entityType, Long entityId) throws Exception {
        return List.copyOf(samples(caller, entityType, entityId).keySet());
    }

    private JsonNode offered(User caller, String entityType, Long entityId) throws Exception {
        return getOk("/api/emails/placeholders", caller,
                "entityType", entityType, "entityId", String.valueOf(entityId));
    }
}
