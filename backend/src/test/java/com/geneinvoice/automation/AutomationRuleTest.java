package com.geneinvoice.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrant;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saving a rule, and the five things about it that are properties rather than plumbing (A1..A4).
 *
 * <p>THE HEADLINE: every refusal here is an EXISTING sentence. A condition naming a column the
 * subject does not have is refused in the FILTER BAR's own words, because it is the filter bar's
 * own method that refuses it; a role the subject does not offer is refused in the TO FIELD's own
 * words, by the method that owns that text. Two of the tests below prove that by driving the
 * other surface and comparing the strings, which is the only way to show it is one vocabulary and
 * not two that happen to agree today.
 *
 * <p>And a rule cannot reach further than its author can: a branch it NAMES is checked against
 * the author's own MANAGE grants (403, because the branch was named), and a rule that names none
 * resolves to exactly the branches its author manages — never to "everywhere" (A1, B1, D-46).
 */
class AutomationRuleTest extends IntegrationTestBase {

    @Autowired PrivilegeRepository privilegeRepository;
    @Autowired CustomerRepository customers;
    @Autowired InvoiceService invoiceService;
    @Autowired AutomationRuleService ruleService;
    @Autowired RuleRegions ruleRegions;

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    User admin;
    User ana;          // authors rules, manages HQ and nothing else
    User nate;         // authors rules, manages NORTH and nothing else
    User vic;          // may look at automation and nothing more
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
        vic = user("vic.viewer", viewerRole().getName());
        nate = user("nate.north", authorRole().getName());
        revokeRegionGrants(nate);
        grant(nate, north, RegionRight.VIEW);
        grant(nate, north, RegionRight.MANAGE);
        widget = product("Widget", "1000.00");
        home = customer("Home Ltd", "ap@home.test");
        away = customers.save(Customer.builder().name("Away Ltd").region(north).build());
    }

    // ---- the words ------------------------------------------------------------------------

    @Test
    void aRuleNamingAColumnTheSubjectDoesNotHaveIsRefusedInTheFilterBarsOwnWords() throws Exception {
        // The filter bar's answer first, so the comparison below is against a real response and
        // not against a string somebody typed into this test (A2).
        String fromTheFilterBar = message(mockMvc.perform(get("/api/invoices")
                        .param("filter", "ballance:gt:1000").with(as(admin)))
                .andExpect(status().isBadRequest()));
        assertThat(fromTheFilterBar).isEqualTo("Unknown column: ballance");

        String fromTheRule = message(save(rule("Chase", "INVOICE",
                condition("ballance:gt:1000"), emailAction()), ana)
                .andExpect(status().isBadRequest()));
        assertThat(fromTheRule).isEqualTo(fromTheFilterBar);

        // And the operator half, which is the other thing requireFilterable owns.
        String badOperator = message(save(rule("Chase", "INVOICE",
                condition("balance:contains:1000"), emailAction()), ana)
                .andExpect(status().isBadRequest()));
        assertThat(badOperator).isEqualTo(message(mockMvc.perform(get("/api/invoices")
                        .param("filter", "balance:contains:1000").with(as(admin)))
                .andExpect(status().isBadRequest())));
        assertThat(badOperator).contains("Operator contains is not valid for column balance");

        // Nothing was written by either refusal: validation happens before the row (A1).
        assertThat(automationRuleRepository.count()).isZero();

        // A column the subject DOES have goes through, which is what stops this being vacuous.
        save(rule("Chase", "INVOICE", condition("balance:gt:1000"), emailAction()), ana)
                .andExpect(status().isOk());
    }

    @Test
    void aRuleNamingARoleTheSubjectDoesNotOfferIsRefusedInTheToFieldsOwnWords() throws Exception {
        // An invoice offers the Sales POC at RECORD level and the customer book at CUSTOMER
        // level. "Sales POC (customer)" is neither, and it is EmailAddressing.offeredRole — the
        // To field's own method — that says so (A3).
        EmailDtos.EmailToken unoffered = EmailDtos.EmailToken.role(RoleRef.customer(EmailRole.SALES_POC));

        String refused = message(save(rule("Nudge", "INVOICE", null,
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(unoffered), "Hello", "Body")), ana)
                .andExpect(status().isBadRequest()));
        assertThat(refused).isEqualTo("Sales POC (customer) is not a role on invoices");

        // The same token as an ASSIGNEE is refused by the same method, so a rule author cannot
        // reach a seat through the task form that the To field would have refused (A3, A6).
        assertThat(message(save(rule("Nudge", "INVOICE", null,
                new ActionSpec.CreateTask("Call them", null, List.of(unoffered), 3)), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("Sales POC (customer) is not a role on invoices");

        // A CUSTOMER token may receive an email and may never own a task: the one place the two
        // token vocabularies deliberately differ (A3, A6).
        assertThat(message(save(rule("Nudge", "INVOICE", null,
                new ActionSpec.CreateTask("Call them", null,
                        List.of(EmailDtos.EmailToken.customer()), 3)), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("A customer cannot be given a task; pick a person or a role");
        save(rule("Nudge", "INVOICE", null,
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(EmailDtos.EmailToken.customer()), "Hello", "Body")), ana)
                .andExpect(status().isOk());

        // And the role the subject DOES offer saves, so the refusal is about the level and not
        // about roles in general.
        save(rule("Nudge again", "INVOICE", null,
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(EmailDtos.EmailToken.role(RoleRef.record(EmailRole.SALES_POC))),
                        "Hello", "Body")), ana)
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownPlaceholderIsRefusedWhenTheRuleIsSavedNotWhenItRuns() throws Exception {
        assertThat(message(save(rule("Dunning", "INVOICE", null,
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(EmailDtos.EmailToken.customer()),
                        "You owe {{Invoice.Ballance}}", "Body")), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("Unknown placeholder {{Invoice.Ballance}} for invoices");
        assertThat(automationRuleRepository.count()).isZero();

        // A task title is a template too, and is checked against the same catalogue (A4, A6).
        assertThat(message(save(rule("Dunning", "INVOICE", null,
                new ActionSpec.CreateTask("Chase {{Invoice.Nmber}}", null, List.of(), 1)), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("Unknown placeholder {{Invoice.Nmber}} for invoices");

        // The correctly spelled one saves AND renders, which is the "not when it runs" half: by
        // the time anything is rendered the author has already been told (A4).
        save(rule("Dunning", "INVOICE", null,
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(EmailDtos.EmailToken.customer()),
                        "You owe {{Invoice.Balance}}", "Body")), ana)
                .andExpect(status().isOk());
        assertThat(automationRuleRepository.count()).isEqualTo(1);
    }

    /**
     * VALIDATION HAPPENS AT SAVE, NOT AT RUN — for the subject too (A2, A3).
     *
     * <p>A task title and a dispute reason are both refused at save AND skipped at run, because
     * they are two different checks: a literal blank is a mistake the author can be told about
     * now, and a non-blank template that RENDERS empty is one nobody can see until it runs. The
     * email subject only ever had the second, so a rule saved with an empty Subject box appeared
     * armed on the list and then settled every step it ever planned SKIPPED "The subject rendered
     * empty" — nothing sent, nothing POISONED, no badge, for every record, for ever.
     */
    @Test
    void anEmailActionWithNoSubjectIsRefusedAtSaveLikeATaskWithNoTitle() throws Exception {
        for (String blank : new String[]{null, "", "   "}) {
            assertThat(message(save(rule("Dunning", "INVOICE", null,
                    new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                            List.of(EmailDtos.EmailToken.customer()), blank, "Please pay")), ana)
                    .andExpect(status().isBadRequest())))
                    .isEqualTo("An email action needs a subject");
        }
        assertThat(automationRuleRepository.count()).isZero();

        // The two the file already refused, quoted here because the point is the inconsistency:
        // the subject was the one of the three that was missed.
        assertThat(message(save(rule("Chase", "INVOICE", null,
                new ActionSpec.CreateTask("  ", null, List.of(), 1)), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("A task action needs a title");
        assertThat(message(save(rule("Argue", "INVOICE", null,
                new ActionSpec.CreateDispute("  ")), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("A dispute action needs a reason");

        // A subject that is a template, not a literal blank, still saves — the run-time skip is
        // the check for what it renders to, and it is untouched.
        save(rule("Dunning", "INVOICE", null,
                new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                        List.of(EmailDtos.EmailToken.customer()),
                        "{{Invoice.Number}}", "Please pay")), ana)
                .andExpect(status().isOk());
        assertThat(automationRuleRepository.count()).isEqualTo(1);
    }

    @Test
    void aRuleThatWouldCreateADisputeOnACustomerIsRefusedBecauseThereIsNothingToDispute()
            throws Exception {
        assertThat(message(save(rule("Argue", "CUSTOMER", null,
                new ActionSpec.CreateDispute("This is wrong")), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("There is nothing to dispute on a customer;"
                        + " a dispute is raised against an invoice or a payment");

        // The same action on the two subjects a DisputeTargetType actually has goes through.
        save(rule("Argue about an invoice", "INVOICE", null,
                new ActionSpec.CreateDispute("This is wrong")), ana).andExpect(status().isOk());
        save(rule("Argue about a payment", "PAYMENT", null,
                new ActionSpec.CreateDispute("This is wrong")), ana).andExpect(status().isOk());

        // The sibling rule from the same family: an invoice balance is not a thing a customer or
        // a payment rule can promise against (A3).
        assertThat(message(save(rule("Promise", "CUSTOMER", null,
                new ActionSpec.CreatePromise(ActionSpec.AmountSource.INVOICE_BALANCE, null, 7,
                        null, null)), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("An invoice balance is only available on a rule about invoices");
        save(rule("Promise", "INVOICE", null,
                new ActionSpec.CreatePromise(ActionSpec.AmountSource.INVOICE_BALANCE, null, 7,
                        null, null)), ana).andExpect(status().isOk());

        // FIXED without a figure is the other half of the same check.
        assertThat(message(save(rule("Promise a figure", "CUSTOMER", null,
                new ActionSpec.CreatePromise(ActionSpec.AmountSource.FIXED, null, 7, null, null)), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("A fixed amount needs a figure");
        assertThat(message(save(rule("Promise a figure", "CUSTOMER", null,
                new ActionSpec.CreatePromise(ActionSpec.AmountSource.FIXED,
                        new BigDecimal("10.005"), 7, null, null)), ana)
                .andExpect(status().isBadRequest())))
                .contains("must have at most 2 decimal places");
    }

    // ---- the reach ------------------------------------------------------------------------

    @Test
    void aRuleCannotReachIntoABranchItsAuthorDoesNotManage() throws Exception {
        // Ana manages HQ. Naming NORTH is 403 and NOT 404: she typed the branch in, so no id
        // space is being probed and pretending the branch does not exist would be a lie (D-46).
        save(rule("Northern chase", "INVOICE", null, emailAction(), north.getId()), ana)
                .andExpect(status().isForbidden());
        assertThat(automationRuleRepository.count()).isZero();

        // Her own branch goes through, and comes back naming it.
        read(save(rule("Home chase", "INVOICE", null, emailAction(), hq.getId()), ana)
                .andExpect(status().isOk()));
        AutomationRule saved = automationRuleRepository.findAll().get(0);
        assertThat(saved.getRegionIds()).containsExactly(hq.getId());

        // Reading it back says which branches it names and that it is not the unbranched kind.
        mockMvc.perform(get("/api/automation/rules/" + saved.getId()).with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].code").value("HQ"))
                .andExpect(jsonPath("$.allAuthorRegions").value(false));

        // VIEW in a branch is not MANAGE in it: looking at NORTH does not let a rule act there.
        grant(ana, north, RegionRight.VIEW);
        save(rule("Northern chase", "INVOICE", null, emailAction(), north.getId()), ana)
                .andExpect(status().isForbidden());

        // And a branch that has been retired cannot be pointed at even by somebody who manages it.
        Region closed = region("CLOSED");
        closed.setActive(false);
        regionRepository.save(closed);
        grant(ana, closed, RegionRight.MANAGE);
        assertThat(message(save(rule("Closed chase", "INVOICE", null, emailAction(), closed.getId()), ana)
                .andExpect(status().isBadRequest())))
                .isEqualTo("CLOSED is retired, so a rule cannot be pointed at it");
    }

    @Test
    void aRuleWithNoBranchesMeansEveryBranchItsAuthorManagesAndNeverMoreThanThat() throws Exception {
        // Ana names no branch, so her rule reaches exactly what she manages — HQ — and not NORTH,
        // which is the whole difference between "no branches" and "every branch" (A1, B1).
        read(save(rule("Everywhere I work", "INVOICE", null, emailAction()), ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allAuthorRegions").value(true))
                .andExpect(jsonPath("$.regions").isEmpty()));
        AutomationRule anas = automationRuleRepository.findAll().get(0);
        assertThat(ruleRegions.reachOf(anas)).containsExactly(hq.getId());

        // The same rule written by a wildcard holder reaches every ACTIVE branch, and a branch
        // opened after the rule was saved is in that set without anybody re-saving anything.
        read(save(rule("Company wide", "INVOICE", null, emailAction()), admin)
                .andExpect(status().isOk()));
        AutomationRule admins = automationRuleRepository.findAll().stream()
                .filter(r -> r.getName().equals("Company wide")).findFirst().orElseThrow();
        assertThat(ruleRegions.reachOf(admins))
                .containsExactlyInAnyOrder(hq.getId(), north.getId());
        Region opened = region("EAST");
        assertThat(ruleRegions.reachOf(admins))
                .containsExactlyInAnyOrder(hq.getId(), north.getId(), opened.getId());
        // A retired branch drops out: a rule reaching into a branch nobody works in any more
        // would act on records nobody is watching (B1).
        opened.setActive(false);
        regionRepository.save(opened);
        assertThat(ruleRegions.reachOf(admins))
                .containsExactlyInAnyOrder(hq.getId(), north.getId());

        // A NAMED branch is still exactly the named one, whoever wrote it.
        read(save(rule("Just the north", "INVOICE", null, emailAction(), north.getId()), admin)
                .andExpect(status().isOk()));
        AutomationRule named = automationRuleRepository.findAll().stream()
                .filter(r -> r.getName().equals("Just the north")).findFirst().orElseThrow();
        assertThat(ruleRegions.reachOf(named)).containsExactly(north.getId());

        // And an author who manages nothing reaches NOTHING, which is refused at save time rather
        // than left in the list looking armed and doing nothing for ever (A1, B1).
        User una = user("una.unstaffed", authorRole().getName());
        revokeRegionGrants(una);
        assertThat(message(save(rule("Hopeful", "INVOICE", null, emailAction()), una)
                .andExpect(status().isBadRequest())))
                .isEqualTo("A rule with no branches runs in every branch its author may manage,"
                        + " and this rule's author may manage none");
        assertThat(ruleRegions.reachOf(AutomationRule.builder()
                .createdByUserId(una.getId()).build())).isEmpty();
    }

    // ---- the lifecycle ----------------------------------------------------------------------

    @Test
    void editingWhatARuleDoesBumpsItsVersionAndRenamingItDoesNot() throws Exception {
        JsonNode made = read(save(rule("Chase", "INVOICE", condition("balance:gt:1000"),
                emailAction()), ana).andExpect(status().isOk()));
        long id = made.get("id").asLong();
        assertThat(made.get("definitionVersion").asInt()).isEqualTo(1);

        // A rename is not a change to what the rule DOES, so a step planned against version 1 is
        // still good: bumping here would cancel work nobody asked to cancel (A1, A5).
        assertThat(edit("Chase harder", "INVOICE", condition("balance:gt:1000"),
                emailAction(), id, null)).isEqualTo(1);

        // Turning a rule OFF is not a definition change either — enabled is read live at act time
        // precisely so that "stop doing this" means now rather than "after the planned steps".
        assertThat(edit("Chase harder", "INVOICE", condition("balance:gt:1000"),
                emailAction(), id, false)).isEqualTo(1);
        assertThat(automationRuleRepository.findById(id).orElseThrow().isEnabled()).isFalse();

        // The conditions are.
        assertThat(edit("Chase harder", "INVOICE", condition("balance:gt:2000"),
                emailAction(), id, false)).isEqualTo(2);
        // So are the actions.
        assertThat(edit("Chase harder", "INVOICE", condition("balance:gt:2000"),
                new ActionSpec.CreateTask("Ring them", null, List.of(), 2), id, false))
                .isEqualTo(3);
        // So are the branches it may reach.
        assertThat(edit("Chase harder", "INVOICE", condition("balance:gt:2000"),
                new ActionSpec.CreateTask("Ring them", null, List.of(), 2), id, false, hq.getId()))
                .isEqualTo(4);
        // And saving the identical thing again changes nothing, so a form that round-trips does
        // not invalidate every step in flight.
        assertThat(edit("Chase harder", "INVOICE", condition("balance:gt:2000"),
                new ActionSpec.CreateTask("Ring them", null, List.of(), 2), id, false, hq.getId()))
                .isEqualTo(4);
    }

    @Test
    void aDeletedRuleLeavesTheListButItsHistoryStillNamesIt() throws Exception {
        long id = read(save(rule("Chase", "INVOICE", null, emailAction()), ana)
                .andExpect(status().isOk())).get("id").asLong();
        automationStepRepository.save(step(id, "Chase", home.getId()));

        mockMvc.perform(delete("/api/automation/rules/" + id).with(as(ana)))
                .andExpect(status().isOk());

        // Gone from every list, including the one a bulk action resolves ids through, and 404 by
        // id — a soft delete that still answered would be no delete at all.
        mockMvc.perform(get("/api/automation/rules").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/automation/rules/" + id).with(as(ana)))
                .andExpect(status().isNotFound());
        actAs(ana);
        assertThat(ruleService.idsMatching(
                com.geneinvoice.common.query.TableQuery.parseUnpaged(
                        AutomationSchemas.RULES, null, List.of()), 5000)).isEmpty();

        // The row is still there, which is what makes the history readable at all.
        assertThat(automationRuleRepository.findById(id)).isPresent();

        // And the history still names it, from the snapshot on the step rather than from a join
        // that would now find nothing (A5).
        mockMvc.perform(get("/api/automation/steps").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ruleName").value("Chase"))
                .andExpect(jsonPath("$.content[0].ruleId").value(id));
    }

    @Test
    void somebodyWhoMayOnlyViewAutomationCannotSaveARule() throws Exception {
        save(rule("Chase", "INVOICE", null, emailAction()), vic).andExpect(status().isForbidden());
        assertThat(automationRuleRepository.count()).isZero();

        long id = read(save(rule("Chase", "INVOICE", null, emailAction()), ana)
                .andExpect(status().isOk())).get("id").asLong();

        // Looking is exactly what AUTOMATION_VIEW is for, so reading must still work.
        mockMvc.perform(get("/api/automation/rules").with(as(vic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/automation/steps/poisoned-count").with(as(vic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(get("/api/automation/placeholders").param("subjectType", "INVOICE")
                        .with(as(vic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == '{{Invoice.Balance}}')]").exists());

        // Editing, deleting and turning off are all MANAGE.
        mockMvc.perform(put("/api/automation/rules/" + id).with(as(vic))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(rule("Chase", "INVOICE", null, emailAction()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/automation/rules/" + id).with(as(vic)))
                .andExpect(status().isForbidden());
    }

    // ---- the reads --------------------------------------------------------------------------

    @Test
    void theRunHistoryOnlyShowsStepsAboutRecordsTheReaderMaySee() throws Exception {
        long ruleId = read(save(rule("Chase", "INVOICE", null, emailAction()), admin)
                .andExpect(status().isOk())).get("id").asLong();
        automationStepRepository.save(step(ruleId, "Chase", home.getId()));
        automationStepRepository.save(step(ruleId, "Chase", away.getId()));

        // The axis does this, not the service: AutomationStep is VIA_CUSTOMER_ID, so the caller's
        // own branches are ANDed in by TableQueryExecutor before the list has said anything (B1).
        mockMvc.perform(get("/api/automation/steps").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].customerId").value(home.getId()))
                .andExpect(jsonPath("$.content[0].regionName").value(hq.getName()));
        mockMvc.perform(get("/api/automation/steps").with(as(nate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].customerId").value(away.getId()));
        mockMvc.perform(get("/api/automation/steps").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // The page SAYS which branches it is narrowed to, the lockedFilters contract (B1).
        mockMvc.perform(get("/api/automation/steps").with(as(nate)))
                .andExpect(jsonPath("$.lockedFilters[0]").value("regionId:in:" + north.getId()));

        // And a rule that names a branch I cannot read is not in MY list, although the rule table
        // has no region axis of its own: that clause is a predicate, not an axis (A1, B1).
        read(save(rule("Northern chase", "INVOICE", null, emailAction(), north.getId()), admin)
                .andExpect(status().isOk()));
        mockMvc.perform(get("/api/automation/rules").with(as(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Chase"));
        mockMvc.perform(get("/api/automation/rules").with(as(nate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Ana can always see a rule SHE wrote, wherever it reaches, because she is the person who
        // has to be able to turn it off (A1).
        read(save(rule("Anas own", "INVOICE", null, emailAction(), hq.getId()), ana)
                .andExpect(status().isOk()));
        mockMvc.perform(get("/api/automation/rules").with(as(ana)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void thePreviewRendersAgainstOneRecordAndRefusesARecordTheCallerCannotSee() throws Exception {
        actAs(admin);
        Invoice mine = invoiceFor(home);
        Invoice theirs = invoiceFor(away);

        mockMvc.perform(post("/api/automation/preview").with(as(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.PreviewRequest(SubjectType.INVOICE,
                                mine.getId(), null,
                                "{{Customer.Name}} owes {{Invoice.Balance}}",
                                "Invoice {{Invoice.Number}} is due {{Invoice.DueDate}}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.text")
                        .value("Home Ltd owes " + com.geneinvoice.common.Money.format(mine.getTotal())))
                .andExpect(jsonPath("$.body.text")
                        .value("Invoice " + mine.getInvoiceNumber() + " is due " + mine.getDueDate()))
                .andExpect(jsonPath("$.asOf").value(TODAY.toString()));

        // The northern invoice is 404 and never 403, and it is refused BEFORE a character is
        // rendered — otherwise a preview would be a way to read a customer's name across a
        // branch (A4, AUTH-08).
        mockMvc.perform(post("/api/automation/preview").with(as(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.PreviewRequest(SubjectType.INVOICE,
                                theirs.getId(), null, "{{Customer.Name}}", null))))
                .andExpect(status().isNotFound());

        // Nate, who works in NORTH, gets the answer Ana could not have.
        mockMvc.perform(post("/api/automation/preview").with(as(nate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.PreviewRequest(SubjectType.INVOICE,
                                theirs.getId(), null, "{{Customer.Name}}", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.text").value("Away Ltd"));

        // A slot with nothing behind it renders empty and SAYS so, rather than leaving a literal
        // {{...}} in a customer-facing subject (A4).
        mockMvc.perform(post("/api/automation/preview").with(as(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.PreviewRequest(SubjectType.INVOICE,
                                mine.getId(), null,
                                "Call {{Role.CUSTOMER.COLLECTION_POC.Name}}", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.text").value("Call "))
                .andExpect(jsonPath("$.subject.unresolved[0]")
                        .value("{{Role.CUSTOMER.COLLECTION_POC.Name}}"));

        // The seat that IS held resolves, so the empty one above is about this record and not
        // about role slots in general (A4).
        mockMvc.perform(post("/api/automation/preview").with(as(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AutomationDtos.PreviewRequest(SubjectType.INVOICE,
                                mine.getId(), null, "Ask {{Role.RECORD.SALES_POC.Name}}", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.text").value("Ask " + admin.getFullName()))
                .andExpect(jsonPath("$.subject.unresolved").isEmpty());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private AutomationDtos.SaveRuleRequest rule(String name, String subject, JsonNode conditions,
                                                ActionSpec action, Long... regionIds) {
        return new AutomationDtos.SaveRuleRequest(name, null, SubjectType.valueOf(subject),
                TriggerKind.ON_CREATED_OR_UPDATED, null, null, conditions, List.of(action), null,
                true, List.of(regionIds));
    }

    private ActionSpec emailAction() {
        return new ActionSpec.SendEmail(EmailDtos.EmailToken.user(ana.getId()),
                List.of(EmailDtos.EmailToken.customer()), "Hello", "Body");
    }

    private JsonNode condition(String wire) {
        try {
            return objectMapper.readTree("{\"op\":\"AND\",\"of\":[{\"filter\":\"" + wire + "\"}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions save(AutomationDtos.SaveRuleRequest req, User who) throws Exception {
        return mockMvc.perform(post("/api/automation/rules").with(as(who))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)));
    }

    /** @return the definition version the edit left behind, which is what these tests read. */
    private int edit(String name, String subject, JsonNode conditions, ActionSpec action,
                     long id, Boolean enabled, Long... regionIds) throws Exception {
        AutomationDtos.SaveRuleRequest req = new AutomationDtos.SaveRuleRequest(name, null,
                SubjectType.valueOf(subject), TriggerKind.ON_CREATED_OR_UPDATED, null, null,
                conditions, List.of(action), null, enabled, List.of(regionIds));
        return read(mockMvc.perform(put("/api/automation/rules/" + id).with(as(ana))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isOk())).get("definitionVersion").asInt();
    }

    private AutomationStep step(long ruleId, String ruleName, Long customerId) {
        return AutomationStep.builder()
                .occasion("E" + (ruleId * 1000 + customerId))
                .ruleId(ruleId).ruleName(ruleName).ruleVersion(1)
                .actionIndex(0).actionKind(ActionKind.SEND_EMAIL)
                .subjectType(SubjectType.CUSTOMER).subjectId(customerId)
                .customerId(customerId)
                .source(StepSource.EVENT).status(StepStatus.DONE)
                .build();
    }

    private Invoice invoiceFor(Customer c) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(c.getId(), null, null,
                admin.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1,
                        new BigDecimal("1000.00")))));
    }

    private void grant(User u, Region region, RegionRight right) {
        userRegionGrantRepository.save(UserRegionGrant.builder()
                .userId(u.getId()).regionId(region.getId()).right(right).build());
    }

    /**
     * SCOPE_OVERRIDE is deliberate and is what makes these tests about REGIONS. The POC book is a
     * second, older narrowing that would otherwise refuse ana the invoice before the branch was
     * ever consulted — it has its own tests and it is not what is under examination here (A1, B1).
     */
    private Role authorRole() {
        return roleWith("RULE_AUTHOR", Privileges.AUTOMATION_VIEW, Privileges.AUTOMATION_MANAGE,
                Privileges.CUSTOMER_VIEW, Privileges.CUSTOMER_MANAGE, Privileges.INVOICE_VIEW,
                Privileges.PAYMENT_VIEW, Privileges.EMAIL_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    private Role viewerRole() {
        return roleWith("RULE_WATCHER", Privileges.AUTOMATION_VIEW, Privileges.CUSTOMER_VIEW,
                Privileges.INVOICE_VIEW, Privileges.SCOPE_OVERRIDE);
    }

    private Role roleWith(String name, String... privileges) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .description("Built by AutomationRuleTest")
                .privileges(Arrays.stream(privileges)
                        .map(p -> privilegeRepository.findByName(p).orElseThrow())
                        .collect(Collectors.toCollection(HashSet<Privilege>::new)))
                .build()));
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String message(ResultActions actions) throws Exception {
        return read(actions).get("message").asText();
    }
}
