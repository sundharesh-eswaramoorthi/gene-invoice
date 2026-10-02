package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.common.time.AppClock;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Previewing a rule (§5.10.9, D10, PC-7): what it would match and do, for whom, as of when, without writing
 * anything. Ten days ago ({@link #d1}) Acme (NORTH) was billed n1 (100, sold by Sally) and n2 (300), and South Co
 * (SOUTH) s1 (200); all three are unpaid.
 */
class PreviewTest extends AutomationTestBase {

    static final List<String> WRITTEN = List.of("automation_rules", "automation_rule_revisions", "automation_runs",
            "automation_effects", "automation_events", "tasks", "record_assignees", "emails", "email_recipients",
            "payment_promises", "disputes", "pending_changes", "audit_logs", "notifications");

    @Autowired PocService pocService;
    @Autowired JdbcTemplate jdbc;

    User admin;
    /** Sales, NORTH MANAGE/BOOK: her book is n1. */
    User sally;
    Region north;
    Region south;
    Product widget;
    Customer acme;
    Customer southCo;
    Long n1;
    Long n2;
    Long s1;
    LocalDate d1;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        for (Region r : List.of(north, south)) {
            grant(seller, r, AccessLevel.READ, RecordScope.BOOK);
            grant(collector, r, AccessLevel.READ, RecordScope.BOOK);
        }
        sally = staff("sally.sales", DataSeeder.ROLE_SALES_POC, north, AccessLevel.MANAGE, RecordScope.BOOK);
        d1 = LocalDate.ofInstant(MovableClock.SHARED.instant(), ZoneOffset.UTC).minusDays(10);
        MovableClock.SHARED.set(d1.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        widget = product("Widget", "100.00");
        acme = customerRepository.save(Customer.builder().name("Acme Ltd").email("ap@acme.test").region(north)
                .build());
        southCo = customer("South Co", south);
        actAs(admin);
        n1 = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, null, null,
                sally.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null)))).getId();
        n2 = invoice(acme, widget, 3, null);
        s1 = invoice(southCo, widget, 2, null);
        pocService.add(acme.getId(), PocType.COLLECTION, collector.getId(), true);
        MovableClock.SHARED.reset();
    }

    private Map<String, Object> unpaid(String name, Object... actions) {
        return rule(name, "INVOICE", "ENTITY_UPDATED", and(leaf("status:in:UNPAID,PARTIALLY_PAID")), actions);
    }

    private Map<String, Object> previewOf(Map<String, Object> rule, Long recordId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rule", rule);
        body.put("recordId", recordId);
        body.put("sampleSize", 10);
        return body;
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, User caller, Object body) throws Exception {
        return body(send(request, caller, body).andExpect(status().isOk()));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, User caller) throws Exception {
        return body(mockMvc.perform(request.with(as(caller))).andExpect(status().isOk()));
    }

    private Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : WRITTEN) out.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
        return out;
    }

    private static List<Long> sampleIds(JsonNode preview) {
        return StreamSupport.stream(preview.get("sample").spliterator(), false)
                .filter(s -> s.has("id")).map(s -> s.get("id").asLong()).sorted().toList();
    }

    @Test
    void aPreviewShowsWhatEachActionWouldDoAndWritesNothing() throws Exception {
        Map<String, Object> promise = new LinkedHashMap<>();
        promise.put("type", "CREATE_PROMISE");
        promise.put("amount", Map.of("source", "FIELD", "field", "Invoice.Balance"));
        promise.put("promisedDate", Map.of("base", "TODAY", "offsetDays", 7));
        promise.put("notes", "For {{Invoice.Number}}");
        promise.put("linkRecord", true);
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("type", "CREATE_DISPUTE");
        dispute.put("reason", "Review {{Invoice.Number}} for {{Customer.Name}}");
        dispute.put("proposedChange", "cancel");
        dispute.put("assignees", List.of(roleToken("SALES_POC", "RECORD")));
        Map<String, Object> rule = unpaid("Everything",
                task("Chase {{Invoice.Number}} ({{Invoice.Balance}})", userToken(admin), userToken(collector)), promise, dispute,
                email(null, List.of(Map.of("type", "CUSTOMER")), "Invoice {{Invoice.Number}}",
                        "Dear {{Customer.Name}}, {{Invoice.Balance}} is due {{Invoice.DueDate}}."));
        String number = invoiceRepository.findById(n2).orElseThrow().getInvoiceNumber();
        Map<String, Long> before = counts();

        JsonNode preview = ok(post(RULES + "/preview"), admin, previewOf(rule, n2));

        assertThat(counts()).isEqualTo(before);
        assertThat(preview.get("entityType").asText()).isEqualTo("INVOICE");
        assertThat(preview.get("evaluatedAs").asText()).isEqualTo("CALLER");
        assertThat(preview.get("matchCount").asLong()).isEqualTo(3);
        assertThat(sampleIds(preview)).containsExactly(n1, n2, s1);
        assertThat(preview.get("asOf").isNull()).isTrue();
        JsonNode record = preview.get("record");
        assertThat(record.get("id").asLong()).isEqualTo(n2);
        assertThat(record.get("label").asText()).isEqualTo("Invoice " + number);
        assertThat(record.get("matches").asBoolean()).isTrue();
        JsonNode actions = record.get("actions");
        assertThat(actions).extracting(a -> a.get("type").asText())
                .containsExactly("CREATE_TASK", "CREATE_PROMISE", "CREATE_DISPUTE", "SEND_EMAIL");
        assertThat(actions.get(0).get("rendered").get("title").asText()).isEqualTo("Chase " + number + " (₹300.00)");
        assertThat(actions.get(0).get("rendered").get("dueDate").asText())
                .isEqualTo(LocalDate.ofInstant(AppClock.now(), ZoneOffset.UTC).plusDays(2).toString());
        assertThat(actions.get(0).get("assignees")).as("the collector sees no NORTH invoice outside the book")
                .extracting(a -> a.get("people").get(0).get("userId").asLong()).containsExactly(admin.getId());
        assertThat(actions.get(0).get("warnings")).extracting(JsonNode::asText)
                .containsExactly("COLM.COLLECTOR cannot see this record, so was not assigned");
        assertThat(actions.get(1).get("rendered").get("amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(actions.get(1).get("rendered").get("collectionPoc").asText()).isEqualTo("COLM.COLLECTOR");
        assertThat(actions.get(1).get("problems")).isEmpty();
        assertThat(actions.get(2).get("rendered").get("reason").asText())
                .isEqualTo("Review " + number + " for Acme Ltd");
        assertThat(actions.get(2).get("rendered").get("proposedChange").asText()).isEqualTo("cancel");
        assertThat(actions.get(3).get("rendered").get("subject").asText()).isEqualTo("Invoice " + number);
        assertThat(actions.get(3).get("email").get("to").get(0).get("address").asText()).isEqualTo("ap@acme.test");

        Long saved = saveRule(admin, rule);
        Map<String, Long> afterSave = counts();
        JsonNode again = ok(get(RULES + "/" + saved + "/preview").param("recordId", n2.toString()), admin);
        assertThat(again.get("record").get("actions")).hasSize(4);
        ok(get(RULES + "/" + saved + "/matches"), admin);
        assertThat(counts()).isEqualTo(afterSave);
    }

    /** Whose view: the caller's regions and book, and the regions asked about. */
    @Test
    void aPreviewCountsOnlyWhatTheCallerMaySee() throws Exception {
        User nora = staff("nora.north", roleWith("AUTOMATION_READER", Privileges.AUTOMATION_VIEW,
                Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE).getName(), north, AccessLevel.READ, RecordScope.ALL);
        Map<String, Object> rule = unpaid("Unpaid", task("Chase"));

        JsonNode northOnly = ok(post(RULES + "/preview"), nora, previewOf(rule, null));
        assertThat(northOnly.get("matchCount").asLong()).isEqualTo(2);
        assertThat(sampleIds(northOnly)).containsExactly(n1, n2);
        JsonNode booked = ok(post(RULES + "/preview"), sally, previewOf(rule, null));
        assertThat(booked.get("matchCount").asLong()).as("Sally's book is n1").isEqualTo(1);
        assertThat(sampleIds(booked)).containsExactly(n1);
        JsonNode southAsked = ok(post(RULES + "/preview").param("regionIds", south.getId().toString()), admin,
                previewOf(rule, null));
        assertThat(southAsked.get("matchCount").asLong()).isEqualTo(1);
        assertThat(sampleIds(southAsked)).containsExactly(s1);
        JsonNode restricted = ok(post(RULES + "/preview"), admin,
                previewOf(with(rule, "regionIds", List.of(north.getId())), null));
        assertThat(sampleIds(restricted)).as("a rule restricted to NORTH matches there only").containsExactly(n1, n2);

        send(post(RULES + "/preview"), nora, previewOf(rule, s1)).andExpect(status().isNotFound());
    }

    @Test
    void aRecordAlreadyActedOnSaysWhichActions() throws Exception {
        Map<String, Object> once = task("Chase {{Invoice.Number}}");
        Map<String, Object> every = task("Chase again");
        every.put("repeat", "EVERY_TIME");
        Long ruleId = saveRule(admin, unpaid("Chase", once, every));
        String onceKey = (String) once.get("key");
        automationEffectRepository.save(AutomationEffect.builder().runId(1L).ruleId(ruleId).eventId(1L)
                .actionKey(onceKey).actionType("CREATE_TASK").targetType("INVOICE").targetId(n2)
                .dedupeKey(AutomationEffect.oncePerRecordKey(ruleId, onceKey, "INVOICE", n2))
                .status(EffectStatus.DONE).createdAt(AppClock.now()).build());

        JsonNode preview = ok(get(RULES + "/" + ruleId + "/preview"), admin);
        Map<Long, List<String>> done = new LinkedHashMap<>();
        preview.get("sample").forEach(s -> done.put(s.get("id").asLong(),
                StreamSupport.stream(s.get("alreadyDone").spliterator(), false).map(JsonNode::asText).toList()));
        assertThat(done).containsEntry(n2, List.of(onceKey)).containsEntry(n1, List.of()).containsEntry(s1, List.of());

        JsonNode unsaved = ok(post(RULES + "/preview"), admin, previewOf(unpaid("Draft", once), null));
        assertThat(unsaved.get("sample")).allMatch(s -> s.get("alreadyDone").isEmpty());
    }

    /**
     * As of a date: the condition on the records as they were, a saved rule's revision in force then, and no actions
     * (they would be done now, D10). n2 was paid in full two days after the bills.
     */
    @Test
    void aPreviewAsOfADateEvaluatesTheRecordsAndTheRuleAsTheyWere() throws Exception {
        LocalDate d2 = d1.plusDays(1);
        LocalDate d3 = d1.plusDays(2);
        MovableClock.SHARED.set(d1.atTime(LocalTime.of(12, 0)).toInstant(ZoneOffset.UTC));
        Long ruleId = saveRule(admin, unpaid("Unpaid", task("Chase")));
        MovableClock.SHARED.set(d3.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        actAs(admin);
        pay(acme, "300.00", n2);
        send(put(RULES + "/" + ruleId), admin,
                with(rule("Paid", "INVOICE", "ENTITY_UPDATED", and(leaf("status:eq:FULLY_PAID")), task("Thank")),
                        "version", 0)).andExpect(status().isOk());
        Long later = saveRule(admin, unpaid("Later", task("Chase")));
        MovableClock.SHARED.reset();

        Map<String, Object> draft = unpaid("Unpaid", task("Chase {{Invoice.Number}}"));
        JsonNode then = ok(post(RULES + "/preview").param("asOf", d2.toString()), admin, previewOf(draft, n2));
        assertThat(then.get("matchCount").asLong()).as("n2 was unpaid then").isEqualTo(3);
        assertThat(then.get("asOf").asText()).isEqualTo(d2.toString());
        assertThat(then.get("asOfNote").asText()).isEqualTo("Actions are shown only for today's view");
        assertThat(then.get("record").get("matches").asBoolean()).isTrue();
        assertThat(then.get("record").get("actions").isNull()).isTrue();
        JsonNode now = ok(post(RULES + "/preview"), admin, previewOf(draft, n2));
        assertThat(now.get("matchCount").asLong()).isEqualTo(2);
        assertThat(now.get("record").get("matches").asBoolean()).isFalse();

        JsonNode savedThen = ok(get(RULES + "/" + ruleId + "/preview").param("asOf", d2.toString()), admin);
        assertThat(savedThen.get("matchCount").asLong()).as("the revision in force then: unpaid").isEqualTo(3);
        JsonNode savedNow = ok(get(RULES + "/" + ruleId + "/preview"), admin);
        assertThat(savedNow.get("matchCount").asLong()).as("today's rule: paid").isEqualTo(1);
        assertThat(sampleIds(savedNow)).containsExactly(n2);
        assertThat(ok(get(RULES + "/" + ruleId + "/matches").param("asOf", d2.toString()), admin)
                .get("totalElements").asLong()).isEqualTo(3);
        mockMvc.perform(get(RULES + "/" + later + "/preview").param("asOf", d2.toString()).with(as(admin)))
                .andExpect(status().isNotFound());
    }

    /**
     * {@code evaluateAs=OWNER} counts as the owner would, and lists only what the caller can also see (PC-7): Adam,
     * an administrator working NORTH, previews Olga's rule; Olga works NORTH and SOUTH. Sally's rule counts her
     * book only.
     */
    @Test
    void theOwnersViewCountsAsTheOwnerButShowsOnlyWhatTheCallerSees() throws Exception {
        User olga = staff("olga.owner", DataSeeder.ROLE_COLLECTION_POC,
                north, AccessLevel.MANAGE, RecordScope.ALL, south, AccessLevel.MANAGE, RecordScope.ALL);
        User adam = staff("adam.admin", roleWith("AUTOMATION_ADMIN_NORTH", Privileges.AUTOMATION_VIEW,
                        Privileges.AUTOMATION_ADMIN, Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE).getName(),
                north, AccessLevel.READ, RecordScope.ALL);
        Long olgas = saveRule(olga, unpaid("Olga's", task("Chase")));

        JsonNode mine = ok(get(RULES + "/" + olgas + "/preview"), adam);
        assertThat(mine.get("evaluatedAs").asText()).isEqualTo("CALLER");
        assertThat(mine.get("matchCount").asLong()).isEqualTo(2);
        JsonNode hers = ok(get(RULES + "/" + olgas + "/preview").param("evaluateAs", "OWNER"), adam);
        assertThat(hers.get("evaluatedAs").asText()).isEqualTo("OWNER");
        assertThat(hers.get("matchCount").asLong()).isEqualTo(3);
        assertThat(sampleIds(hers)).containsExactly(n1, n2);
        assertThat(hers.get("sample")).filteredOn(s -> s.has("hidden"))
                .singleElement().satisfies(s -> {
                    assertThat(s.get("hidden").asBoolean()).isTrue();
                    assertThat(s.has("label")).isFalse();
                    assertThat(s.has("link")).isFalse();
                });
        JsonNode hidden = ok(get(RULES + "/" + olgas + "/preview").param("evaluateAs", "OWNER")
                .param("recordId", s1.toString()), adam);
        assertThat(hidden.get("record").isNull()).isTrue();
        mockMvc.perform(get(RULES + "/" + olgas + "/preview").param("recordId", s1.toString()).with(as(adam)))
                .andExpect(status().isNotFound());
        JsonNode matches = ok(get(RULES + "/" + olgas + "/matches").param("evaluateAs", "OWNER"), adam);
        assertThat(matches.get("totalElements").asLong()).isEqualTo(3);
        assertThat(matches.get("content")).filteredOn(m -> m.has("hidden")).hasSize(1);
        assertThat(matches.get("regionIds")).as("the page's scope is the caller's, not the owner's")
                .extracting(JsonNode::asLong).containsExactly(north.getId());
        assertThat(ok(get(RULES + "/" + olgas + "/preview").param("evaluateAs", "OWNER"), olga)
                .get("matchCount").asLong()).isEqualTo(3);

        Long sallys = saveRule(sally, unpaid("Sally's", task("Chase")));
        JsonNode book = ok(get(RULES + "/" + sallys + "/preview").param("evaluateAs", "OWNER"), admin);
        assertThat(book.get("matchCount").asLong()).as("her book only").isEqualTo(1);
        assertThat(sampleIds(book)).containsExactly(n1);
    }

    /**
     * In the owner's view POC details still follow the caller's rights (PC-7): Adam holds no POC_VIEW, so Olga's
     * {@code {{Customer.CollectionPoc}}} and her promise's Collection POC are left out for him, not for her.
     */
    @Test
    void theOwnersViewLeavesOutPocDetailsTheCallerMayNotSee() throws Exception {
        User olga = staff("olga.owner", DataSeeder.ROLE_COLLECTION_POC,
                north, AccessLevel.MANAGE, RecordScope.ALL, south, AccessLevel.MANAGE, RecordScope.ALL);
        User adam = staff("adam.admin", roleWith("AUTOMATION_ADMIN_NORTH", Privileges.AUTOMATION_VIEW,
                        Privileges.AUTOMATION_ADMIN, Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE).getName(),
                north, AccessLevel.READ, RecordScope.ALL);
        Map<String, Object> promise = new LinkedHashMap<>();
        promise.put("type", "CREATE_PROMISE");
        promise.put("amount", Map.of("source", "FIELD", "field", "Invoice.Balance"));
        promise.put("promisedDate", Map.of("base", "TODAY", "offsetDays", 7));
        Long olgas = saveRule(olga, unpaid("Olga's", task("Chase {{Customer.CollectionPoc}}"), promise));

        JsonNode hers = ok(get(RULES + "/" + olgas + "/preview").param("evaluateAs", "OWNER")
                .param("recordId", n2.toString()), olga);
        JsonNode herActions = hers.get("record").get("actions");
        assertThat(herActions.get(0).get("rendered").get("title").asText()).isEqualTo("Chase COLM.COLLECTOR");
        assertThat(herActions.get(1).get("rendered").get("collectionPoc").asText()).isEqualTo("COLM.COLLECTOR");

        JsonNode forAdam = ok(get(RULES + "/" + olgas + "/preview").param("evaluateAs", "OWNER")
                .param("recordId", n2.toString()), adam);
        assertThat(forAdam.get("evaluatedAs").asText()).isEqualTo("OWNER");
        JsonNode actions = forAdam.get("record").get("actions");
        assertThat(actions.get(0).get("rendered").get("title").asText()).isEqualTo("Chase");
        assertThat(actions.get(0).get("warnings")).extracting(JsonNode::asText)
                .contains("{{Customer.CollectionPoc}} is left out: POC details are not visible here");
        assertThat(actions.get(1).get("rendered").has("collectionPoc")).isFalse();
        assertThat(actions.get(1).get("rendered").has("collectionPocUserId")).isFalse();
        assertThat(actions.get(1).get("warnings")).extracting(JsonNode::asText)
                .contains("The Collection POC is left out: POC details are not visible here");
        assertThat(actions.get(1).get("problems")).as("the promise itself would still be made").isEmpty();
    }

    /**
     * A customer that whoever the actions are shown as cannot open lends the rule's texts only its name (RR-1): Cody
     * works Globex's payment but not Globex, in his own preview and in his rule's owner's view alike. In the owner's
     * view the caller must be able to open the customer too, as with POC details (PC-7): Ada, an automation
     * administrator who works her own Globex payment, sees the administrator's rule leave Globex's details out.
     */
    @Test
    void aCustomerThatCannotBeOpenedLendsTheActionsOnlyItsName() throws Exception {
        Customer globex = customer("Globex", "ap@globex.test");
        globex.setPhone("+1-555-0100");
        globex.setAddress("1 Secret Street");
        globex = customerRepository.save(globex);
        User cody = user("cody.collections", DataSeeder.ROLE_COLLECTION_POC);
        grantRepository.deleteAllOfUser(cody.getId());
        grant(cody, defaultRegion(), AccessLevel.MANAGE, RecordScope.BOOK);
        // A Collection POC's rights, and the automation administrator's.
        User ada = staff("ada.admin", roleWith("COLLECTION_AUTOMATION_ADMIN", Privileges.CUSTOMER_VIEW,
                        Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE, Privileges.POC_VIEW,
                        Privileges.POC_ASSIGNABLE_COLLECTION, Privileges.SCOPE_OVERRIDE, Privileges.EMAIL_VIEW,
                        Privileges.EMAIL_SEND, Privileges.TASK_VIEW, Privileges.TASK_MANAGE, Privileges.AUTOMATION_VIEW,
                        Privileges.AUTOMATION_MANAGE, Privileges.AUTOMATION_ADMIN).getName(),
                defaultRegion(), AccessLevel.MANAGE, RecordScope.BOOK);
        actAs(admin);
        Long codysPayment = paymentService.record(new PaymentDtos.CreatePaymentRequest(globex.getId(),
                new BigDecimal("50.00"), "Cash", null, null, cody.getId(), null)).getId();
        Long adasPayment = paymentService.record(new PaymentDtos.CreatePaymentRequest(globex.getId(),
                new BigDecimal("60.00"), "Cash", null, null, ada.getId(), null)).getId();
        for (User u : List.of(cody, ada)) {
            mockMvc.perform(get("/api/customers/" + globex.getId()).with(as(u))).andExpect(status().isNotFound());
        }
        ok(get("/api/payments/" + codysPayment), cody);
        ok(get("/api/payments/" + adasPayment), ada);

        Map<String, Object> rule = rule("Globex", "PAYMENT", "MANUAL", null,
                everyTime(task("{{Customer.Name}} {{Customer.Phone}}")),
                email(null, List.of(userToken(cody)), "Payment", "addr={{Customer.Address}}"));
        Map<String, Long> before = counts();

        JsonNode his = ok(post(RULES + "/preview"), cody, previewOf(rule, codysPayment));
        assertThat(counts()).isEqualTo(before);
        assertCustomerHidden(his);

        Long codys = saveRule(cody, rule);
        JsonNode ownersView = ok(get(RULES + "/" + codys + "/preview").param("evaluateAs", "OWNER")
                .param("recordId", codysPayment.toString()), admin);
        assertThat(ownersView.get("evaluatedAs").asText()).isEqualTo("OWNER");
        assertCustomerHidden(ownersView);

        Long admins = saveRule(admin, with(rule, "name", "Globex (admin's)"));
        JsonNode adminsOwn = ok(get(RULES + "/" + admins + "/preview").param("recordId", codysPayment.toString()),
                admin);
        JsonNode actions = adminsOwn.get("record").get("actions");
        assertThat(actions.get(0).get("rendered").get("title").asText()).isEqualTo("Globex +1-555-0100");
        assertThat(actions.get(1).get("rendered").get("body").asText()).isEqualTo("addr=1 Secret Street");
        for (JsonNode a : actions) {
            assertThat(a.get("warnings")).extracting(JsonNode::asText)
                    .doesNotContain(PlaceholderRenderer.CUSTOMER_HIDDEN);
        }

        JsonNode forAda = ok(get(RULES + "/" + admins + "/preview").param("evaluateAs", "OWNER")
                .param("recordId", adasPayment.toString()), ada);
        assertThat(forAda.get("evaluatedAs").asText()).isEqualTo("OWNER");
        assertCustomerHidden(forAda);
    }

    private static void assertCustomerHidden(JsonNode preview) {
        JsonNode actions = preview.get("record").get("actions");
        assertThat(actions).extracting(a -> a.get("type").asText()).containsExactly("CREATE_TASK", "SEND_EMAIL");
        assertThat(actions.get(0).get("rendered").get("title").asText()).isEqualTo("Globex");
        assertThat(actions.get(1).get("rendered").get("body").asText()).isEqualTo("addr=");
        for (JsonNode a : actions) {
            assertThat(a.get("warnings")).extracting(JsonNode::asText)
                    .containsOnlyOnce(PlaceholderRenderer.CUSTOMER_HIDDEN);
            assertThat(a.toString()).doesNotContain("+1-555-0100").doesNotContain("1 Secret Street");
        }
    }

    /** Matches are paged newest first, each page read at its own offset. */
    @Test
    void aLaterPageOfMatchesIsReadAtItsOffset() throws Exception {
        actAs(admin);
        for (int i = 0; i < 8; i++) invoice(acme, widget, 1, null);
        Long ruleId = saveRule(admin, unpaid("Unpaid", task("Chase")));

        JsonNode first = ok(get(RULES + "/" + ruleId + "/matches").param("size", "10"), admin);
        JsonNode second = ok(get(RULES + "/" + ruleId + "/matches").param("page", "1").param("size", "10"), admin);

        assertThat(first.get("totalElements").asLong()).isEqualTo(11);
        assertThat(first.get("content")).hasSize(10);
        assertThat(second.get("totalElements").asLong()).isEqualTo(11);
        assertThat(second.get("content")).as("the oldest is last").extracting(m -> m.get("id").asLong())
                .containsExactly(n1);
    }

    /** A Collection POC named on a promise must be assignable in the record's region (§5.10.5). */
    @Test
    void aNamedCollectionPocMustBeAssignableWhereTheRecordIs() throws Exception {
        User wes = staff("wes.south", DataSeeder.ROLE_COLLECTION_POC, south, AccessLevel.MANAGE, RecordScope.ALL);
        Map<String, Object> promise = new LinkedHashMap<>();
        promise.put("type", "CREATE_PROMISE");
        promise.put("amount", Map.of("source", "FIXED", "value", 50));
        promise.put("promisedDate", Map.of("base", "TODAY", "offsetDays", 7));
        promise.put("collectionPoc", userToken(wes));
        Map<String, Object> rule = unpaid("Promise by Wes", promise);

        JsonNode inNorth = ok(post(RULES + "/preview"), admin, previewOf(rule, n2));
        assertThat(inNorth.get("record").get("actions").get(0).get("problems")).extracting(JsonNode::asText)
                .containsExactly("WES.SOUTH is not assignable as Collection POC in the record's region");
        JsonNode inSouth = ok(post(RULES + "/preview"), admin, previewOf(rule, s1));
        assertThat(inSouth.get("record").get("actions").get(0).get("problems")).isEmpty();
        assertThat(inSouth.get("record").get("actions").get(0).get("rendered").get("collectionPoc").asText())
                .isEqualTo("WES.SOUTH");
    }

    @Test
    void theOwnersViewIsRefusedToSomeoneWhoIsNeitherOwnerNorAdministrator() throws Exception {
        User nora = staff("nora.north", roleWith("AUTOMATION_READER", Privileges.AUTOMATION_VIEW,
                Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE).getName(), north, AccessLevel.READ, RecordScope.ALL);
        Long ruleId = saveRule(admin, unpaid("Admin's", task("Chase")));
        mockMvc.perform(get(RULES + "/" + ruleId + "/preview").param("evaluateAs", "OWNER").with(as(nora)))
                .andExpect(status().isForbidden());
        Map<String, Object> body = previewOf(unpaid("Admin's", task("Chase")), null);
        body.put("ruleId", ruleId);
        body.put("evaluateAs", "OWNER");
        send(post(RULES + "/preview"), nora, body).andExpect(status().isForbidden());
        mockMvc.perform(get(RULES + "/" + ruleId + "/matches").param("evaluateAs", "OWNER").with(as(nora)))
                .andExpect(status().isForbidden());
        ok(get(RULES + "/" + ruleId + "/preview"), nora);
    }

    /** An email a rule would send goes to nobody who cannot see the record (PC-2, SC-3), and is not sent. */
    @Test
    void anEmailPreviewLeavesOutAPersonWhoCannotSeeTheRecord() throws Exception {
        User wes = staff("wes.south", DataSeeder.ROLE_COLLECTION_POC, south, AccessLevel.MANAGE, RecordScope.ALL);
        Map<String, Object> rule = unpaid("Tell them", email(null, List.of(userToken(wes), Map.of("type", "CUSTOMER")),
                "About {{Invoice.Number}}", "Hello"));
        Map<String, Long> before = counts();

        JsonNode preview = ok(post(RULES + "/preview"), admin, previewOf(rule, n2));

        JsonNode email = preview.get("record").get("actions").get(0).get("email");
        assertThat(email.get("warnings")).extracting(JsonNode::asText)
                .contains("WES.SOUTH cannot see this record, so was not emailed");
        assertThat(email.get("to")).extracting(t -> t.get("address").asText()).containsExactly("ap@acme.test");
        assertThat(email.get("problems")).isEmpty();
        assertThat(counts()).isEqualTo(before);
        assertThat(emailRepository.count()).isZero();

        Map<String, Object> fromWes = email(userToken(wes), List.of(userToken(wes)), "Hi", "Hi");
        fromWes.put("fallbackToOwner", false);
        Map<String, Object> onlyWes = unpaid("Only Wes", fromWes);
        JsonNode nobody = ok(post(RULES + "/preview"), admin, previewOf(onlyWes, n2));
        assertThat(nobody.get("record").get("actions").get(0).get("email").get("problems"))
                .extracting(JsonNode::asText)
                .contains("WES.SOUTH cannot see this record, so cannot be the sender", "No recipients left");
        assertThat(emailRepository.count()).isZero();
    }
}
