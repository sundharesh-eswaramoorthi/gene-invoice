package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.task.Task;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WHERE a rule may reach, and the two ways it is not the same question as "what may its author
 * see" (A1, B1).
 *
 * <p>The engine has no principal at all, so a read on its thread has no grants to consult. The
 * only bound is {@code RegionScope.asRegions} with the rule's own regions — the NARROWING hatch,
 * never {@code asSystem} — and an EMPTY set denies rather than widens. That asymmetry is the whole
 * point: the failure mode of a region model has to be "too little", never "too much".
 */
class AutomationRegionTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired InvoiceService invoiceService;
    @Autowired CustomerService customerService;
    @Autowired AutomationRuleService ruleService;
    @Autowired AutomationDispatcher dispatcher;

    User admin;
    User ana;      // manages HQ and nothing else, and can see the whole POC book
    User bea;      // manages HQ and nothing else, and is in nobody's book
    Region hq;
    Region north;
    Customer home;
    Customer away;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        hq = defaultRegion();
        north = region("NORTH");
        ana = user("ana.author", authorRole().getName());
        bea = user("bea.bookless", booklessRole().getName());
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
        away = customerRepository.save(Customer.builder().name("Away Ltd").region(north).build());
    }

    @Test
    void aRuleCannotReachARecordOutsideItsOwnRegions() {
        AutomationRule rule = rule(ana, List.of(hq.getId()));

        Invoice northern = invoiceFor(away);
        Invoice southern = invoiceFor(home);

        dispatcher.sweep(Instant.now().plusSeconds(60));

        // One step and one task, and both about the HQ invoice. The northern one was not refused
        // with an error — it simply was not there to be found, which is AUTH-08 applied to a
        // caller that is not a person (A5, B1).
        assertThat(stepsOf(rule)).singleElement()
                .satisfies(s -> assertThat(s.getSubjectId()).isEqualTo(southern.getId()));
        assertThat(taskRepository.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getEntityId()).isEqualTo(southern.getId()));
        assertThat(automationStepRepository.findAll().stream()
                .anyMatch(s -> s.getSubjectId().equals(northern.getId()))).isFalse();
    }

    @Test
    void aRuleFiresOnRecordsOutsideItsAuthorsBookButInsideItsRegions() {
        AutomationRule rule = rule(bea, List.of());

        // Bea cannot see this customer AT ALL on her own screens: she is assignable as no POC
        // type, so her book is empty and her own read answers 404 (AUTH-08).
        actAs(bea);
        assertThatThrownBy(() -> customerService.get(home.getId()))
                .isInstanceOf(NotFoundException.class);
        SecurityContextHolder.clearContext();

        Invoice invoice = invoiceFor(home);
        dispatcher.sweep(Instant.now().plusSeconds(60));

        // And her rule fires on it anyway, because a rule is bounded by its BRANCHES and not by
        // the POC book. The book is who is expected to handle a record; the branch is who is
        // allowed to (A1, B1).
        assertThat(stepsOf(rule)).singleElement()
                .satisfies(s -> assertThat(s.getStatus()).isEqualTo(StepStatus.DONE));
        assertThat(taskRepository.findAll()).singleElement()
                .satisfies((Task t) -> {
                    assertThat(t.getEntityId()).isEqualTo(invoice.getId());
                    assertThat(t.getCreatedByUserId()).isEqualTo(bea.getId());
                });
    }

    @Test
    void aRuleWhoseAuthorHasLostEveryGrantReachesNothingRatherThanEverything() {
        // NO named branches: this rule means "every branch my author may manage, as of now".
        AutomationRule rule = rule(ana, List.of());

        // And then she is unstaffed — moved, suspended, or simply never re-granted after a
        // reorganisation. Her rule is still enabled and still matches records (B1).
        revokeRegionGrants(ana);

        invoiceFor(home);
        invoiceFor(away);
        dispatcher.sweep(Instant.now().plusSeconds(60));

        assertThat(stepsOf(rule)).isEmpty();
        assertThat(taskRepository.count()).isZero();

        // Not "it is broken": give the branch back and the very same rule reaches it again, which
        // is what makes the emptiness above a DENIAL and not a failure (B1).
        userRegionGrantRepository.save(com.geneinvoice.region.UserRegionGrant.builder()
                .userId(ana.getId()).regionId(hq.getId())
                .right(com.geneinvoice.region.RegionRight.MANAGE).build());
        automationEventRepository.findAll().forEach(e -> {
            e.setStatus(EventStatus.NEW);
            automationEventRepository.save(e);
        });
        dispatcher.sweep(Instant.now().plusSeconds(120));

        assertThat(stepsOf(rule)).singleElement()
                .satisfies(s -> assertThat(s.getCustomerId()).isEqualTo(home.getId()));
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private List<AutomationStep> stepsOf(AutomationRule rule) {
        return automationStepRepository.findAll().stream()
                .filter(s -> s.getRuleId().equals(rule.getId())).toList();
    }

    private AutomationRule rule(User author, List<Long> regionIds) {
        actAs(author);
        Long id = ruleService.create(new AutomationDtos.SaveRuleRequest("Chase", null,
                SubjectType.INVOICE, TriggerKind.ON_CREATED_OR_UPDATED, null, null, condition(),
                List.of(new ActionSpec.CreateTask("Chase {{Invoice.Number}}", null, List.of(), 3)),
                null, true, regionIds)).id();
        SecurityContextHolder.clearContext();
        return automationRuleRepository.findById(id).orElseThrow();
    }

    private JsonNode condition() {
        try {
            return objectMapper.readTree("{\"op\":\"AND\",\"of\":[{\"filter\":\"balance:gt:0\"}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Invoice invoiceFor(Customer c) {
        actAs(admin);
        Invoice invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(),
                null, null, admin.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, new BigDecimal("1000.00")))));
        SecurityContextHolder.clearContext();
        return invoice;
    }

    private Role authorRole() {
        return roleWith("RULE_AUTHOR_REGIONS", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.TASK_VIEW, Privileges.TASK_MANAGE,
                Privileges.SCOPE_OVERRIDE);
    }

    /** Everything an author needs EXCEPT a book: assignable as no POC type, so she sees nothing. */
    private Role booklessRole() {
        return roleWith("RULE_AUTHOR_BOOKLESS", Privileges.AUTOMATION_VIEW,
                Privileges.AUTOMATION_MANAGE, Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE,
                Privileges.INVOICE_VIEW, Privileges.TASK_VIEW, Privileges.TASK_MANAGE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationRegionTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }
}
