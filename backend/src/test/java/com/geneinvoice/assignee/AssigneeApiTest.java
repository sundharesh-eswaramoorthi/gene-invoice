package com.geneinvoice.assignee;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.ResultActions;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Assigning people and roles to disputes and promises by hand (§6.13; PC-14): DISPUTE_MANAGE or PROMISE_MANAGE in the
 * record's region (a region only read is a 403, one not held a 404); someone named who could not open the record is a
 * 400 against {@code assignees} and nothing changes; the change is audited and those newly assigned are told. The
 * pickers are for staff only and offer only people who work in the region (SC-18). Being assigned keeps a person from
 * being deleted, and the assignee code stays apart from the services that keep assignees (CF-3).
 */
class AssigneeApiTest extends AutomationTestBase {

    @Autowired PocService pocService;
    @Autowired PaymentPromiseService promiseService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ApplicationContext context;

    User admin;
    Region north;
    /** Manages disputes and promises in NORTH. */
    User mia;
    /** The same role, but only reads NORTH. */
    User rex;
    /** The same role, in SOUTH only. */
    User oscar;
    /** Someone to assign who works in NORTH, and one who works only in SOUTH. */
    User amy;
    User sam;
    User acmeLogin;
    Customer acme;
    Long invoiceId;
    Long disputeId;
    Long promiseId;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        Region south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(seller, north, AccessLevel.READ, RecordScope.ALL);
        grant(collector, north, AccessLevel.MANAGE, RecordScope.ALL);
        roleWith("DESK", Privileges.CUSTOMER_VIEW, Privileges.INVOICE_VIEW, Privileges.PAYMENT_VIEW,
                Privileges.DISPUTE_VIEW, Privileges.DISPUTE_MANAGE, Privileges.PROMISE_VIEW, Privileges.PROMISE_MANAGE,
                Privileges.SCOPE_OVERRIDE, Privileges.POC_VIEW, Privileges.NOTIFICATION_VIEW);
        mia = staff("mia.manager", "DESK", north, AccessLevel.MANAGE, RecordScope.ALL);
        rex = staff("rex.reader", "DESK", north, AccessLevel.READ, RecordScope.ALL);
        oscar = staff("oscar.south", "DESK", south, AccessLevel.MANAGE, RecordScope.ALL);
        amy = staff("amy.north", "DESK", north, AccessLevel.READ, RecordScope.ALL);
        sam = staff("sam.south", "DESK", south, AccessLevel.READ, RecordScope.ALL);

        acme = customer("Acme Ltd", north);
        acmeLogin = customerUser("acme.login", acme.getId());
        Product widget = product("Widget", "100.00");
        actAs(admin);
        pocService.add(acme.getId(), PocType.COLLECTION, collector.getId(), true);
        invoiceId = invoice(acme, widget, 1, null);
        disputeId = disputeRepository.save(Dispute.builder().customerId(acme.getId())
                .openedByUserId(acmeLogin.getId()).targetType(DisputeTargetType.INVOICE).targetId(invoiceId)
                .reason("The price is wrong").status(DisputeStatus.PENDING).region(north).build()).getId();
        promiseId = promiseService.create(new PromiseDtos.CreatePromiseRequest(acme.getId(), new BigDecimal("100.00"),
                Today.date().plusDays(10), collector.getId(), null, null)).id();
    }

    private ResultActions assign(String type, Long id, User as, Object... tokens) throws Exception {
        return send(put("/api/" + type + "/" + id + "/assignees"), as, Map.of("assignees", List.of(tokens)));
    }

    private int status(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private List<String> current(String ownerType, Long ownerId) {
        return recordAssigneeRepository.findAll().stream()
                .filter(r -> r.getOwnerType().equals(ownerType) && r.getOwnerId().equals(ownerId)
                        && r.getRemovedAt() == null)
                .map(r -> RecordAssignee.USER.equals(r.getKind()) ? "USER:" + r.getUserId()
                        : r.getRole() + "@" + r.getRoleLevel())
                .sorted().toList();
    }

    private List<Notification> noticesTo(User u, String type) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(u.getId()) && n.getType().equals(type)).toList();
    }

    private List<Long> people(String query, User as) throws Exception {
        ResultActions result = mockMvc.perform(get("/api/assignees/people" + query).with(as(as)));
        assertThat(status(result)).isEqualTo(200);
        return StreamSupport.stream(body(result).spliterator(), false).map(p -> p.get("userId").asLong()).toList();
    }

    @Test
    void aDisputesAssigneesAreSetWhereTheCallerManagesDisputes() throws Exception {
        ResultActions done = assign("disputes", disputeId, mia, userToken(amy), roleToken("COLLECTION_POC", "CUSTOMER"));
        assertThat(status(done)).isEqualTo(200);
        JsonNode dto = body(done);
        assertThat(dto.get("assignees")).extracting(a -> a.get("token").get("type").asText())
                .containsExactly("USER", "ROLE");
        assertThat(dto.get("assignees").get(1).get("people").get(0).get("userId").asLong())
                .isEqualTo(collector.getId());
        List<String> expected = List.of("COLLECTION_POC@CUSTOMER", "USER:" + amy.getId());
        assertThat(current(AssignedTo.DISPUTE, disputeId)).isEqualTo(expected);
        AuditLog changed = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("DISPUTE", disputeId)
                .stream().filter(a -> a.getAction().equals("DISPUTE_ASSIGNEES_CHANGED")).findFirst().orElseThrow();
        assertThat(changed.getChangedByUserId()).isEqualTo(mia.getId());
        assertThat(changed.getAfterJson()).contains("USER:" + amy.getId(), "ROLE:CUSTOMER:COLLECTION_POC");
        assertThat(noticesTo(amy, "DISPUTE_ASSIGNED")).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/disputes/" + disputeId));
        assertThat(noticesTo(collector, "DISPUTE_ASSIGNED")).hasSize(1);
        assertThat(noticesTo(mia, "DISPUTE_ASSIGNED")).as("not told of their own change").isEmpty();

        assertThat(status(assign("disputes", disputeId, rex, userToken(amy)))).as("reads NORTH only").isEqualTo(403);
        assertThat(status(assign("disputes", disputeId, oscar, userToken(amy)))).as("no NORTH at all").isEqualTo(404);
        ResultActions unseen = assign("disputes", disputeId, mia, userToken(amy), userToken(sam));
        assertThat(status(unseen)).isEqualTo(400);
        assertThat(body(unseen).get("fieldErrors").get("assignees").asText())
                .isEqualTo("SAM.SOUTH cannot see this record");
        assertThat(current(AssignedTo.DISPUTE, disputeId)).as("nothing changed").isEqualTo(expected);
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("DISPUTE", disputeId))
                .filteredOn(a -> a.getAction().equals("DISPUTE_ASSIGNEES_CHANGED")).hasSize(1);

        // The detail shows them to staff; the customer's own dispute page does not.
        JsonNode staffView = body(mockMvc.perform(get("/api/disputes/" + disputeId).with(as(rex))));
        assertThat(staffView.get("assignees")).hasSize(2);
        JsonNode ownView = body(mockMvc.perform(get("/api/disputes/" + disputeId).with(as(acmeLogin))));
        assertThat(ownView.has("assignees")).isFalse();
    }

    @Test
    void aPromisesCoAssigneesAreSetWhereTheCallerManagesPromises() throws Exception {
        ResultActions done = assign("promises", promiseId, mia, userToken(amy),
                roleToken("COLLECTION_POC", "RECORD"));
        assertThat(status(done)).isEqualTo(200);
        assertThat(body(done).get("assignees").get(1).get("people").get(0).get("userId").asLong())
                .as("the promise's own Collection POC").isEqualTo(collector.getId());
        List<String> expected = List.of("COLLECTION_POC@RECORD", "USER:" + amy.getId());
        assertThat(current(AssignedTo.PROMISE, promiseId)).isEqualTo(expected);
        AuditLog changed = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("PROMISE", promiseId)
                .stream().filter(a -> a.getAction().equals("PROMISE_ASSIGNEES_CHANGED")).findFirst().orElseThrow();
        assertThat(changed.getChangedByUserId()).isEqualTo(mia.getId());
        assertThat(noticesTo(amy, "PROMISE_ASSIGNED")).singleElement()
                .satisfies(n -> assertThat(n.getLink()).isEqualTo("/promises/" + promiseId));

        assertThat(status(assign("promises", promiseId, rex, userToken(amy)))).isEqualTo(403);
        assertThat(status(assign("promises", promiseId, oscar, userToken(amy)))).isEqualTo(404);
        ResultActions unseen = assign("promises", promiseId, mia, userToken(sam));
        assertThat(status(unseen)).isEqualTo(400);
        assertThat(body(unseen).get("fieldErrors").get("assignees").asText())
                .isEqualTo("SAM.SOUTH cannot see this record");
        assertThat(current(AssignedTo.PROMISE, promiseId)).isEqualTo(expected);

        // Taking one away is a change too; the one kept is not told again.
        assertThat(status(assign("promises", promiseId, mia, userToken(amy)))).isEqualTo(200);
        assertThat(current(AssignedTo.PROMISE, promiseId)).containsExactly("USER:" + amy.getId());
        assertThat(noticesTo(amy, "PROMISE_ASSIGNED")).hasSize(1);
    }

    @Test
    void onAPastDateARecordLevelRoleMeansTheRecordsOwnPocThen() throws Exception {
        assertThat(status(assign("disputes", disputeId, mia, roleToken("SALES_POC", "RECORD")))).isEqualTo(200);
        assertThat(status(assign("promises", promiseId, mia, roleToken("COLLECTION_POC", "RECORD")))).isEqualTo(200);
        LocalDate then = Today.date();
        MovableClock.SHARED.advance(Duration.ofDays(2));

        JsonNode dispute = body(mockMvc.perform(get("/api/disputes/" + disputeId)
                .param("asOf", then.toString()).with(as(admin))));
        assertThat(dispute.at("/assignees/0/people/0/userId").asLong()).as("the disputed invoice's Sales POC")
                .isEqualTo(seller.getId());
        assertThat(dispute.at("/assignees/0/unresolved").asBoolean()).isFalse();

        JsonNode promise = body(mockMvc.perform(get("/api/promises/" + promiseId)
                .param("asOf", then.toString()).with(as(admin))));
        assertThat(promise.at("/assignees/0/people/0/userId").asLong()).as("the promise's Collection POC")
                .isEqualTo(collector.getId());
        assertThat(promise.at("/assignees/0/unresolved").asBoolean()).isFalse();
    }

    private List<String> trailActions(String query, User as) throws Exception {
        ResultActions result = mockMvc.perform(get("/api/audit?" + query).with(as(as)));
        assertThat(status(result)).isEqualTo(200);
        return StreamSupport.stream(body(result).spliterator(), false).map(e -> e.get("action").asText()).toList();
    }

    @Test
    void aCustomerLoginsTrailDoesNotSayWhoStaffAssigned() throws Exception {
        assertThat(status(assign("disputes", disputeId, mia, userToken(amy)))).isEqualTo(200);
        assertThat(status(assign("promises", promiseId, mia, userToken(amy),
                roleToken("COLLECTION_POC", "RECORD")))).isEqualTo(200);

        String promiseTrail = "entityType=PROMISE&entityId=" + promiseId;
        String invoiceTrail = "entityType=INVOICE&entityId=" + invoiceId + "&includeRelated=true";
        assertThat(trailActions(promiseTrail, admin)).contains("PROMISE_ASSIGNEES_CHANGED");
        assertThat(trailActions(invoiceTrail, admin)).contains("DISPUTE_OPENED", "DISPUTE_ASSIGNEES_CHANGED");

        assertThat(trailActions(promiseTrail, acmeLogin)).contains("PROMISE_CREATED")
                .doesNotContain("PROMISE_ASSIGNEES_CHANGED");
        assertThat(trailActions(invoiceTrail, acmeLogin)).as("their own dispute, but not who works it")
                .contains("DISPUTE_OPENED").doesNotContain("DISPUTE_ASSIGNEES_CHANGED");
        String raw = mockMvc.perform(get("/api/audit?" + promiseTrail).with(as(acmeLogin)))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("USER:" + amy.getId(), "COLLECTION_POC");
    }

    @Test
    void onlyStaffFilterDisputesByWhoIsAssignedOrWhetherTheyAreInternal() throws Exception {
        assertThat(status(assign("disputes", disputeId, mia, userToken(amy)))).isEqualTo(200);
        String byAmy = "assigneeUserId:eq:" + amy.getId();
        ResultActions staff = mockMvc.perform(get("/api/disputes").param("filter", byAmy).with(as(mia)));
        assertThat(status(staff)).isEqualTo(200);
        assertThat(body(staff).get("totalElements").asLong()).isEqualTo(1);

        for (String filter : List.of(byAmy, "assigneeUserId:isNotEmpty:", "internal:eq:false")) {
            ResultActions own = mockMvc.perform(get("/api/disputes").param("filter", filter).with(as(acmeLogin)));
            assertThat(status(own)).as(filter).isEqualTo(400);
            assertThat(body(own).toString()).as(filter).contains("Unknown column");
        }
        assertThat(status(mockMvc.perform(get("/api/disputes").with(as(acmeLogin))))).isEqualTo(200);
    }

    @Test
    void thePickersAreForStaffWhoAssignAndOfferOnlyPeopleInTheRegion() throws Exception {
        assertThat(status(mockMvc.perform(get("/api/assignees/context?entityType=DISPUTE&entityId=" + disputeId)
                .with(as(mia))))).isEqualTo(200);
        assertThat(status(mockMvc.perform(get("/api/assignees/context?entityType=DISPUTE&entityId=" + disputeId)
                .with(as(acmeLogin))))).isEqualTo(403);
        User viewer = user("vera.viewer", "VIEWER");
        assertThat(status(mockMvc.perform(get("/api/assignees/context?entityType=PROMISE").with(as(viewer)))))
                .isEqualTo(403);
        assertThat(status(mockMvc.perform(get("/api/assignees/people").with(as(acmeLogin))))).isEqualTo(403);

        // Around a NORTH record: those who work in NORTH, whoever asks.
        User cashier = userRepository.findByUsername("cashier").orElseThrow();
        List<Long> aroundPromise = people("?entityType=PROMISE&entityId=" + promiseId, admin);
        assertThat(aroundPromise).contains(amy.getId(), mia.getId(), collector.getId())
                .doesNotContain(sam.getId(), oscar.getId(), cashier.getId());
        // Without a record: the regions the caller holds and asks about.
        assertThat(people("", mia)).contains(amy.getId()).doesNotContain(sam.getId(), cashier.getId());
        assertThat(people("", oscar)).contains(sam.getId()).doesNotContain(amy.getId());
        assertThat(people("?regionIds=" + north.getId(), admin)).contains(amy.getId()).doesNotContain(sam.getId());
        // A record the caller cannot see is missing.
        assertThat(status(mockMvc.perform(get("/api/assignees/people?entityType=DISPUTE&entityId=" + disputeId)
                .with(as(oscar))))).isEqualTo(404);
    }

    @Test
    void someoneAssignedIsDeactivatedRatherThanDeleted() throws Exception {
        assertThat(status(assign("disputes", disputeId, mia, userToken(amy)))).isEqualTo(200);
        assertThat(status(assign("promises", promiseId, mia, userToken(amy)))).isEqualTo(200);

        JsonNode kept = body(mockMvc.perform(delete("/api/users/" + amy.getId()).with(as(admin))));
        assertThat(kept.get("deleted").asBoolean()).isFalse();
        assertThat(kept.get("deactivated").asBoolean()).isTrue();
        assertThat(kept.get("pocReferences").asLong()).as("one dispute, one promise").isEqualTo(2);
        assertThat(userRepository.findById(amy.getId()).orElseThrow().isActive()).isFalse();
    }

    /**
     * The dispute and promise services keep assignees through the store alone; the service that resolves and tells
     * people, and the email targets it reads, depend on those services, never the other way (CF-3): the context
     * starts.
     */
    @Test
    void theContextStartsWithTheDomainServicesKeepingAssigneesThroughTheStoreAlone() {
        assertThat(context.getBean(DisputeService.class)).isNotNull();
        assertThat(context.getBean(PaymentPromiseService.class)).isNotNull();
        assertThat(context.getBean(AssigneeService.class)).isNotNull();
        assertThat(context.getBean(EmailTargets.class)).isNotNull();
        for (Class<?> domain : List.of(DisputeService.class, PaymentPromiseService.class)) {
            Set<String> injected = new HashSet<>();
            for (Constructor<?> c : domain.getDeclaredConstructors()) {
                Arrays.stream(c.getParameterTypes()).map(Class::getSimpleName).forEach(injected::add);
            }
            assertThat(injected).as(domain.getSimpleName()).contains(AssigneeStore.class.getSimpleName())
                    .doesNotContain(AssigneeService.class.getSimpleName(), EmailTargets.class.getSimpleName(),
                            VisibilityProbe.class.getSimpleName());
        }
    }
}
