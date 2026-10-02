package com.geneinvoice.automation.action;

import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailDirection;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRecipient;
import com.geneinvoice.email.EmailStatus;
import com.geneinvoice.email.RecordingMailTransport;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEND_EMAIL as the consumer carries it out (§5.10.5; R15): an ordinary outbound email on the record, sent by the
 * rule's owner and from an internal person; a role sender is the role's primary, or its next active holder; nobody to
 * send it from falls back to the owner or fails it; nobody to send it to skips it; it is handed to the mail service
 * once, after its action committed, and never left waiting when delivery is off. Nobody who cannot see the record is
 * written to or made to write (PC-2, SC-3), and an email that cannot be saved fails its action once, for good (CF-9).
 */
class EmailActionTest extends AutomationTestBase {

    @Autowired PocService pocService;
    @Autowired AutomationProperties props;

    User admin;
    /** Owns the rules: a cashier, who may send email about every invoice in the default region. */
    User owen;
    Customer acme;
    Product widget;
    Long invoiceId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        owen = user("owen.owner", "CASHIER");
        acme = customer("Acme Ltd", "ap@acme.test");
        widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collector.getId(), true);
        invoiceId = invoice(acme, widget, 2, null);
        drain();
    }

    private Long emailRule(Map<String, Object> action) throws Exception {
        return saveRule(owen, rule("Remind", "INVOICE", "ENTITY_UPDATED", null, action));
    }

    private static Map<String, Object> noFallback(Map<String, Object> action) {
        return with(action, "fallbackToOwner", false);
    }

    private void touch(String notes) {
        actAs(admin);
        invoiceService.update(invoiceId, new InvoiceDtos.UpdateInvoiceRequest(notes, null, null, null, null));
    }

    private List<Email> emailsOnTheInvoice() {
        return emailRepository.findAll().stream()
                .filter(e -> e.getEntityType() == EmailEntityType.INVOICE && invoiceId.equals(e.getEntityId()))
                .sorted(Comparator.comparing(Email::getId)).toList();
    }

    private Email onlyEmail() {
        List<Email> emails = emailsOnTheInvoice();
        assertThat(emails).hasSize(1);
        return emails.get(0);
    }

    private List<String> addresses(Email email) {
        return emailRecipientRepository.findByEmailIdOrderByIdAsc(email.getId()).stream()
                .map(EmailRecipient::getAddress).toList();
    }

    private AutomationEffect onlyEffect(Long ruleId) {
        List<AutomationEffect> effects = automationEffectRepository.findAll().stream()
                .filter(e -> e.getRuleId().equals(ruleId)).toList();
        assertThat(effects).hasSize(1);
        return effects.get(0);
    }

    @Test
    void theEmailIsAnOrdinaryOutboundEmailOnTheRecordSentByTheOwner() throws Exception {
        Long ruleId = emailRule(email(null, List.of(Map.of("type", "CUSTOMER")), "Invoice {{Invoice.Number}} is due",
                "Dear {{Customer.Name}},\n{{Invoice.Balance}} is still open."));
        touch("chased");
        drain();

        String number = invoiceRepository.findById(invoiceId).orElseThrow().getInvoiceNumber();
        Email email = onlyEmail();
        assertThat(email.getDirection()).isEqualTo(EmailDirection.OUTBOUND);
        assertThat(email.getSubject()).isEqualTo("Invoice " + number + " is due");
        assertThat(email.getBody()).startsWith("Dear Acme Ltd,\n").contains("is still open.");
        assertThat(email.getSentByUserId()).isEqualTo(owen.getId());
        assertThat(email.getFromUserId()).as("from the owner, an internal person (V5)").isEqualTo(owen.getId());
        assertThat(email.isFromInternal()).isTrue();
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(email.getBatchId()).isEqualTo("rule-" + ruleId + "-run-" + run.getId());
        assertThat(addresses(email)).containsExactly("ap@acme.test");

        AutomationEffect effect = onlyEffect(ruleId);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.getResultType()).isEqualTo("EMAIL");
        assertThat(effect.getResultId()).isEqualTo(email.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void aRoleSenderIsItsPrimaryAndThenItsNextActiveHolder() throws Exception {
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, cora.getId(), false);
        Long ruleId = emailRule(noFallback(email(roleToken("COLLECTION_POC", "CUSTOMER"), List.of(userToken(admin)),
                "About {{Invoice.Number}}", "Hello")));

        touch("first");
        drain();
        assertThat(onlyEmail().getFromUserId()).as("the primary").isEqualTo(collector.getId());
        assertThat(onlyEmail().getFromRole()).isNotNull();

        User primary = userRepository.findById(collector.getId()).orElseThrow();
        primary.setActive(false);
        userRepository.save(primary);
        touch("second");
        drain();

        List<Email> emails = emailsOnTheInvoice();
        assertThat(emails).hasSize(2);
        assertThat(emails.get(1).getFromUserId()).as("the primary is inactive: the next active seat")
                .isEqualTo(cora.getId());
        assertThat(runsOf(ruleId)).extracting(AutomationRun::getStatus).containsOnly(RunStatus.SUCCEEDED);
    }

    @Test
    void aSenderRoleNobodyHoldsFallsBackToTheOwnerOrFailsTheAction() throws Exception {
        Map<String, Object> fromSuccess = email(roleToken("CUSTOMER_SUCCESS_POC", "CUSTOMER"),
                List.of(userToken(admin)), "About {{Invoice.Number}}", "Hello");
        Long fallsBack = emailRule(fromSuccess);
        Long fails = saveRule(owen, rule("Remind strictly", "INVOICE", "ENTITY_UPDATED", null,
                noFallback(fromSuccess)));
        touch("chased");
        drain();

        Email email = onlyEmail();
        assertThat(email.getFromUserId()).isEqualTo(owen.getId());
        assertThat(onlyEffect(fallsBack).getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(onlyEffect(fallsBack).warningList()).anySatisfy(w -> assertThat(w)
                .startsWith("Nobody holds Customer Success POC").contains("on Invoice " + invoiceNumber())
                .endsWith(", so it would come from the rule's owner"));

        AutomationEffect failed = onlyEffect(fails);
        assertThat(failed.getStatus()).isEqualTo(EffectStatus.FAILED);
        assertThat(failed.getMessage()).contains("so it cannot be the sender");
        assertThat(failed.getResultId()).isNull();
        assertThat(runsOf(fails)).singleElement().extracting(AutomationRun::getStatus).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void anEmailWithNobodyToGoToIsSkipped() throws Exception {
        Long ruleId = emailRule(email(null, List.of(roleToken("CUSTOMER_SUCCESS_POC", "CUSTOMER")),
                "About {{Invoice.Number}}", "Hello"));
        touch("chased");
        drain();

        assertThat(emailsOnTheInvoice()).isEmpty();
        AutomationEffect effect = onlyEffect(ruleId);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.SKIPPED);
        assertThat(effect.getMessage()).startsWith("No recipients");
        assertThat(runsOf(ruleId)).singleElement().extracting(AutomationRun::getStatus)
                .isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void withDeliveryOnTheEmailIsHandedOverOnceAfterItsActionCommitted() throws Exception {
        mailTransport.mode(RecordingMailTransport.Mode.SUCCESS);
        Long ruleId = emailRule(email(null, List.of(Map.of("type", "CUSTOMER")), "About {{Invoice.Number}}",
                "Hello"));
        AtomicReference<List<AutomationEffect>> committedBeforeHandOver = new AtomicReference<>();
        mailTransport.beforeSubmit(submission -> committedBeforeHandOver.set(automationEffectRepository.findAll()));
        touch("chased");
        drain();

        assertThat(mailTransport.submissions()).hasSize(1);
        Email email = onlyEmail();
        assertThat(mailTransport.submissions().get(0).groupRef()).isEqualTo(String.valueOf(email.getId()));
        assertThat(committedBeforeHandOver.get()).as("the effect had committed with the email when it was sent")
                .extracting(AutomationEffect::getResultId).containsExactly(email.getId());
        assertThat(email.getHandedOffAt()).as("handed to the mail service").isNotNull();

        drain();
        assertThat(mailTransport.submissions()).as("sent once").hasSize(1);
        assertThat(onlyEffect(ruleId).getStatus()).isEqualTo(EffectStatus.DONE);
    }

    @Test
    void withDeliveryOffTheActionIsDoneWithAWarningAndTheEmailIsNotLeftWaiting() throws Exception {
        Long ruleId = emailRule(email(null, List.of(Map.of("type", "CUSTOMER")), "About {{Invoice.Number}}",
                "Hello"));
        touch("chased");
        drain();

        AutomationEffect effect = onlyEffect(ruleId);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.warningList())
                .contains("Email delivery is not configured, so this email will be saved in the app but not sent.");
        assertThat(onlyEmail().getStatus()).isEqualTo(EmailStatus.NOT_SENT);
        assertThat(mailTransport.submissions()).isEmpty();
    }

    @Test
    void anEmailToMorePeopleThanARuleMayWriteToFails() throws Exception {
        User second = user("sam.second", "CASHIER");
        Long ruleId = emailRule(email(null, List.of(userToken(admin), userToken(second)), "About {{Invoice.Number}}",
                "Hello"));
        int limit = props.getMaxEmailRecipients();
        props.setMaxEmailRecipients(1);
        try {
            touch("chased");
            drain();
        } finally {
            props.setMaxEmailRecipients(limit);
        }

        assertThat(emailsOnTheInvoice()).isEmpty();
        AutomationEffect effect = onlyEffect(ruleId);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.FAILED);
        assertThat(effect.getMessage()).contains("goes to at most 1 people; this one would go to 2");
    }

    @Test
    void aRoleHolderWhoCannotReachTheRecordsRegionIsNotWrittenTo() throws Exception {
        User cora = user("cora.collections", DataSeeder.ROLE_COLLECTION_POC);
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, cora.getId(), false);
        // Cora keeps her seat but loses the region the customer is in.
        grantRepository.deleteAllOfUser(cora.getId());
        grant(cora, region("NORTH"), AccessLevel.MANAGE, RecordScope.ALL);
        Long ruleId = emailRule(email(null, List.of(roleToken("COLLECTION_POC", "CUSTOMER")),
                "About {{Invoice.Number}}", "Hello"));
        touch("chased");
        drain();

        Email email = onlyEmail();
        assertThat(emailRecipientRepository.findByEmailIdOrderByIdAsc(email.getId()))
                .extracting(EmailRecipient::getUserId).containsExactly(collector.getId());
        assertThat(onlyEffect(ruleId).getStatus()).isEqualTo(EffectStatus.DONE);
    }

    @Test
    void aPersonNamedWithoutAccessToTheRecordsRegionIsDroppedAndNotMadeTheSender() throws Exception {
        User nora = staff("nora.north", "CASHIER", region("NORTH"), AccessLevel.MANAGE, RecordScope.ALL);
        Long dropped = emailRule(email(null, List.of(userToken(admin), userToken(nora)), "To {{Invoice.Number}}",
                "Hello"));
        Long strictSender = saveRule(owen, rule("From Nora", "INVOICE", "ENTITY_UPDATED", null,
                noFallback(email(userToken(nora), List.of(userToken(admin)), "From {{Invoice.Number}}", "Hello"))));
        Long lenientSender = saveRule(owen, rule("From Nora or me", "INVOICE", "ENTITY_UPDATED", null,
                email(userToken(nora), List.of(userToken(admin)), "Or {{Invoice.Number}}", "Hello")));
        touch("chased");
        drain();

        List<Email> emails = emailsOnTheInvoice();
        Email toAdmin = emails.stream().filter(e -> e.getSubject().startsWith("To ")).findFirst().orElseThrow();
        assertThat(emailRecipientRepository.findByEmailIdOrderByIdAsc(toAdmin.getId()))
                .extracting(EmailRecipient::getUserId).containsExactly(admin.getId());
        assertThat(onlyEffect(dropped).warningList()).contains("NORA.NORTH cannot see this record, so was not emailed");

        AutomationEffect notQueued = onlyEffect(strictSender);
        assertThat(notQueued.getStatus()).isEqualTo(EffectStatus.FAILED);
        assertThat(notQueued.getMessage()).contains("NORA.NORTH cannot see this record, so cannot be the sender");
        assertThat(emails).noneMatch(e -> e.getSubject().startsWith("From "));

        Email fromOwner = emails.stream().filter(e -> e.getSubject().startsWith("Or ")).findFirst().orElseThrow();
        assertThat(fromOwner.getFromUserId()).isEqualTo(owen.getId());
        assertThat(onlyEffect(lenientSender).warningList())
                .contains("NORA.NORTH cannot see this record, so it would come from the rule's owner");
        assertThat(emails).extracting(Email::getFromUserId).doesNotContain(nora.getId());
    }

    @Test
    void aSubjectThatRendersBlankFailsTheActionOnceForGood() throws Exception {
        // The customer has no phone: the subject comes out empty, and an email without a subject cannot be saved.
        Long ruleId = emailRule(email(null, List.of(userToken(admin)), "{{Customer.Phone}}", "Hello"));
        touch("chased");
        drain();
        drain();

        assertThat(emailsOnTheInvoice()).isEmpty();
        AutomationEffect effect = onlyEffect(ruleId);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.FAILED);
        AutomationRun run = runsOf(ruleId).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        AutomationEvent event = event(run.getEventId());
        assertThat(event.getStatus()).as("a business answer, not a failure to retry").isEqualTo(AutomationEvent.DONE);
        assertThat(event.getAttempts()).isZero();
    }

    /**
     * A rule acts as its owner, so a customer its owner cannot open lends the rule's email only its name (RR-1, as
     * SC-1 for an email written by hand): Cody works Globex's payment but not Globex. The rest is left out with a
     * warning; the same rule owned by the administrator fills it in. (A task, promise or dispute needs the customer
     * itself, so for Cody only the email is made; PreviewTest shows the task's title.)
     */
    @Test
    void aCustomerTheOwnerCannotOpenLendsTheRulesEmailOnlyItsName() throws Exception {
        Customer globex = customer("Globex", "ap@globex.test");
        globex.setPhone("+1-555-0100");
        globex.setAddress("1 Secret Street");
        globex = customerRepository.save(globex);
        User cody = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        grantRepository.deleteAllOfUser(cody.getId());
        grant(cody, defaultRegion(), AccessLevel.MANAGE, RecordScope.BOOK);
        actAs(admin);
        Long paymentId = paymentService.record(new PaymentDtos.CreatePaymentRequest(globex.getId(),
                new BigDecimal("50.00"), "Cash", null, null, cody.getId(), null)).getId();
        drain();
        mockMvc.perform(get("/api/payments/" + paymentId).with(as(cody))).andExpect(status().isOk());
        mockMvc.perform(get("/api/customers/" + globex.getId()).with(as(cody))).andExpect(status().isNotFound());

        Map<String, Object> body = rule("Globex", "PAYMENT", "MANUAL", null,
                email(null, List.of(userToken(cody)), "{{Customer.Name}} {{Customer.Phone}}",
                        "addr={{Customer.Address}}"));
        Long codys = saveRule(cody, body);
        send(post(RULES + "/" + codys + "/run"), cody, Map.of("recordIds", List.of(paymentId)))
                .andExpect(status().isAccepted());
        drain();

        Email codysEmail = onlyEmailOn(paymentId);
        assertThat(codysEmail.getSubject()).isEqualTo("Globex");
        assertThat(codysEmail.getBody()).isEqualTo("addr=");
        AutomationEffect effect = onlyEffect(codys);
        assertThat(effect.getStatus()).isEqualTo(EffectStatus.DONE);
        assertThat(effect.getResultId()).isEqualTo(codysEmail.getId());
        assertThat(effect.warningList()).as("said once for the subject and the body")
                .containsOnlyOnce(PlaceholderRenderer.CUSTOMER_HIDDEN);

        Long admins = saveRule(admin, with(body, "name", "Globex (admin's)"));
        send(post(RULES + "/" + admins + "/run"), admin, Map.of("recordIds", List.of(paymentId)))
                .andExpect(status().isAccepted());
        drain();
        List<Email> adminsEmails = emailRepository.findAll().stream()
                .filter(e -> e.getId() > codysEmail.getId()).toList();
        assertThat(adminsEmails).extracting(Email::getSubject).containsExactly("Globex +1-555-0100");
        assertThat(adminsEmails).extracting(Email::getBody).containsExactly("addr=1 Secret Street");
        assertThat(onlyEffect(admins).warningList()).doesNotContain(PlaceholderRenderer.CUSTOMER_HIDDEN);
    }

    private Email onlyEmailOn(Long paymentId) {
        List<Email> emails = emailRepository.findAll().stream()
                .filter(e -> e.getEntityType() == EmailEntityType.PAYMENT && paymentId.equals(e.getEntityId()))
                .toList();
        assertThat(emails).hasSize(1);
        return emails.get(0);
    }

    private String invoiceNumber() {
        return invoiceRepository.findById(invoiceId).orElseThrow().getInvoiceNumber();
    }
}
