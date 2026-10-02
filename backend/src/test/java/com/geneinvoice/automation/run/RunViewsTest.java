package com.geneinvoice.automation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.automation.AutomationTestBase;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a viewer is shown of a rule's runs (§5.10.10; PC-8, SC-13). An unrestricted rule of an owner who works in
 * NORTH and SOUTH is seen by someone who works only in SOUTH: its runs count only the SOUTH records for them
 * ({@code partial}), NORTH effects and a run on a NORTH invoice are shown only by kind, with nothing that names a
 * record or a person; its owner and administrators see everything. As of a date, runs made by then are listed with
 * where each stood.
 */
class RunViewsTest extends AutomationTestBase {

    User admin;
    User owen;
    /** Works in SOUTH only, and may view rules. */
    User vic;
    Region north;
    Region south;
    Product widget;
    Long inNorth;
    Long inSouth;
    Long otherInSouth;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        owen = staff("owen.owner", "CASHIER", north, AccessLevel.MANAGE, RecordScope.ALL, south, AccessLevel.MANAGE,
                RecordScope.ALL);
        vic = staff("vic.viewer", "CASHIER", south, AccessLevel.READ, RecordScope.ALL);
        for (User u : List.of(admin, seller)) {
            grant(u, north, AccessLevel.APPROVE, RecordScope.ALL);
            grant(u, south, AccessLevel.APPROVE, RecordScope.ALL);
        }
        widget = product("Widget", "100.00");
        actAs(admin);
        inNorth = invoice(customer("Nord", north), widget, 1, null);
        inSouth = invoice(customer("Sud", south), widget, 1, null);
        otherInSouth = invoice(customer("Sud Two", south), widget, 2, null);
        drain();
    }

    private JsonNode read(MockHttpServletRequestBuilder request, User caller) throws Exception {
        return body(mockMvc.perform(request.with(as(caller))).andExpect(status().isOk()));
    }

    private Long runByHand(Long ruleId) throws Exception {
        JsonNode queued = body(send(post(RULES + "/" + ruleId + "/run"), owen, Map.of())
                .andExpect(status().isAccepted()));
        return queued.get("runId").asLong();
    }

    @Test
    void someoneWhoWorksOnlyInSouthSeesTheRunsCountedOverSouthAndNorthOnlyByKind() throws Exception {
        Long ruleId = saveRule(owen, rule("Chase all", "INVOICE", "MANUAL", null,
                everyTime(task("Chase {{Invoice.Number}}"))));
        Long runId = runByHand(ruleId);
        drain();

        JsonNode asOwner = read(get("/api/automation/runs/" + runId), owen);
        assertThat(asOwner.get("done").asInt()).isEqualTo(3);
        assertThat(asOwner.get("partial").asBoolean()).isFalse();
        assertThat(asOwner.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(asOwner.get("requestedBy").get("id").asLong()).isEqualTo(owen.getId());
        assertThat(asOwner.get("entity").isNull()).isTrue();

        JsonNode page = read(get(RULES + "/" + ruleId + "/runs"), vic);
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        JsonNode seen = page.get("content").get(0);
        assertThat(seen.get("id").asLong()).isEqualTo(runId);
        assertThat(seen.get("partial").asBoolean()).isTrue();
        assertThat(seen.get("done").asInt()).as("the two SOUTH invoices").isEqualTo(2);
        assertThat(seen.get("matched").asInt()).as("how many matched in NORTH is not told").isEqualTo(2);
        assertThat(asOwner.get("matched").asInt()).isEqualTo(3);

        // Filtering or sorting on what is stored unredacted would tell through the total what the rows hide.
        mockMvc.perform(get(RULES + "/" + ruleId + "/runs").param("filter", "entityId:eq:" + inNorth).with(as(vic)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RULES + "/" + ruleId + "/runs").param("filter", "matched:gt:2").with(as(vic)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(RULES + "/" + ruleId + "/runs").param("sort", "failed,desc").with(as(vic)))
                .andExpect(status().isBadRequest());
        assertThat(read(get(RULES + "/" + ruleId + "/runs").param("filter", "status:eq:SUCCEEDED"), vic)
                .get("totalElements").asInt()).isEqualTo(1);
        assertThat(read(get(RULES + "/" + ruleId + "/runs").param("filter", "matched:gt:2"), owen)
                .get("totalElements").asInt()).isEqualTo(1);

        JsonNode effects = read(get("/api/automation/runs/" + runId + "/effects"), vic);
        assertThat(effects.get("totalElements").asInt()).isEqualTo(3);
        List<JsonNode> hidden = new ArrayList<>();
        List<Long> shown = new ArrayList<>();
        effects.get("content").forEach(e -> {
            if (e.get("target").get("hidden").asBoolean()) {
                hidden.add(e);
            } else {
                shown.add(e.get("target").get("id").asLong());
            }
        });
        assertThat(shown).containsExactlyInAnyOrder(inSouth, otherInSouth);
        assertThat(hidden).singleElement().satisfies(e -> {
            assertThat(e.get("target").get("type").asText()).isEqualTo("INVOICE");
            assertThat(e.get("target").has("id")).isFalse();
            assertThat(e.get("target").has("label")).isFalse();
            assertThat(e.get("target").has("link")).isFalse();
            assertThat(e.get("result").isNull()).isTrue();
            assertThat(e.get("message").isNull()).isTrue();
            assertThat(e.get("warnings").isNull()).isTrue();
            assertThat(e.get("status").asText()).isEqualTo("DONE");
            assertThat(e.get("actionType").asText()).isEqualTo("CREATE_TASK");
        });
        effects.get("content").forEach(e -> {
            if (e.get("target").get("hidden").asBoolean()) return;
            assertThat(e.get("result").get("type").asText()).isEqualTo("TASK");
            assertThat(e.get("result").get("link").asText()).startsWith("/tasks/");
            assertThat(e.get("target").get("link").asText()).startsWith("/invoices/");
            assertThat(e.get("target").get("label").asText()).startsWith("Invoice ");
        });

        for (User full : List.of(owen, admin)) {
            JsonNode all = read(get("/api/automation/runs/" + runId + "/effects"), full);
            all.get("content").forEach(e -> assertThat(e.get("target").get("hidden").asBoolean()).isFalse());
            assertThat(read(get(RULES + "/" + ruleId + "/runs"), full).get("content").get(0).get("partial").asBoolean())
                    .isFalse();
        }
    }

    @Test
    void aRunOnANorthInvoiceShowsItsRecordOnlyByKindToSomeoneWhoCannotSeeIt() throws Exception {
        Long ruleId = saveRule(owen, rule("New invoices", "INVOICE", "ENTITY_CREATED", null, task("Look at it")));
        actAs(admin);
        Long fresh = invoice(customer("Nord Two", north), widget, 1, null);
        drain();
        Long runId = runsOf(ruleId).get(0).getId();
        AutomationRun stored = automationRunRepository.findById(runId).orElseThrow();
        stored.setMessage("Made a task for Nord Two");
        automationRunRepository.save(stored);

        JsonNode asViewer = read(get("/api/automation/runs/" + runId), vic);
        assertThat(asViewer.get("entity").get("type").asText()).isEqualTo("INVOICE");
        assertThat(asViewer.get("entity").get("hidden").asBoolean()).isTrue();
        assertThat(asViewer.get("entity").has("id")).isFalse();
        assertThat(asViewer.get("message").isNull()).isTrue();
        assertThat(asViewer.get("done").asInt()).isZero();

        JsonNode asOwner = read(get("/api/automation/runs/" + runId), owen);
        assertThat(asOwner.get("entity").get("id").asLong()).isEqualTo(fresh);
        assertThat(asOwner.get("entity").get("link").asText()).isEqualTo("/invoices/" + fresh);
        assertThat(asOwner.get("entity").get("label").asText()).startsWith("Invoice ");
        assertThat(asOwner.get("message").asText()).isEqualTo("Made a task for Nord Two");
        assertThat(asOwner.get("done").asInt()).isEqualTo(1);

        User stranger = staff("stan.stranger", "CASHIER", region("WEST"), AccessLevel.MANAGE, RecordScope.ALL);
        mockMvc.perform(get("/api/automation/runs/" + runId).with(as(stranger))).andExpect(status().isNotFound());
        mockMvc.perform(get(RULES + "/" + ruleId + "/runs").with(as(stranger))).andExpect(status().isNotFound());
    }

    @Test
    void asOfADateRunsMadeByThenAreListedWithWhereEachStoodThen() throws Exception {
        LocalDate day = LocalDate.now(MovableClock.SHARED).minusDays(3);
        MovableClock.SHARED.set(day.atTime(9, 0).toInstant(ZoneOffset.UTC));
        actAs(admin);
        Long first = invoice(customer("Early", south), widget, 1, null);
        Long second = invoice(customer("Early Two", south), widget, 1, null);
        MovableClock.SHARED.set(day.atTime(10, 0).toInstant(ZoneOffset.UTC));
        Long ruleId = saveRule(owen, rule("Chase all", "INVOICE", "MANUAL", null, everyTime(task("Chase"))));
        Long queuedThen = runByHand(ruleId);

        MovableClock.SHARED.set(day.plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC));
        drain();
        Long madeLater = runByHand(ruleId);
        MovableClock.SHARED.set(day.plusDays(3).atTime(9, 0).toInstant(ZoneOffset.UTC));

        JsonNode then = read(get(RULES + "/" + ruleId + "/runs").param("asOf", day.toString()), owen);
        assertThat(then.get("asOf").asText()).isEqualTo(day.toString());
        assertThat(then.get("content")).hasSize(1);
        assertThat(then.get("content").get(0).get("id").asLong()).isEqualTo(queuedThen);
        assertThat(then.get("content").get(0).get("statusAt").asText()).isEqualTo("QUEUED");
        assertThat(then.get("content").get(0).get("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode nextDay = read(get("/api/automation/runs/" + queuedThen).param("asOf", day.plusDays(1).toString()),
                owen);
        assertThat(nextDay.get("statusAt").asText()).isEqualTo("SUCCEEDED");
        mockMvc.perform(get("/api/automation/runs/" + madeLater).param("asOf", day.toString()).with(as(owen)))
                .andExpect(status().isNotFound());
        JsonNode live = read(get(RULES + "/" + ruleId + "/runs"), owen);
        assertThat(live.get("content")).hasSize(2);
        assertThat(live.get("content").get(0).get("statusAt").isNull()).isTrue();

        JsonNode effectsThen = read(get("/api/automation/runs/" + queuedThen + "/effects")
                .param("asOf", day.toString()), owen);
        assertThat(effectsThen.get("totalElements").asInt()).as("made the next day").isZero();
        List<Long> actedOn = new ArrayList<>();
        read(get("/api/automation/runs/" + queuedThen + "/effects"), owen).get("content")
                .forEach(e -> actedOn.add(e.get("target").get("id").asLong()));
        assertThat(actedOn).as("the invoices as they stood at the time asked for").containsExactlyInAnyOrder(first,
                second);
        assertThat(Duration.between(Instant.parse(nextDay.get("scheduledFor").asText()),
                Instant.parse(nextDay.get("startedAt").asText())).getSeconds())
                .isEqualTo(nextDay.get("lateBySeconds").asLong());
    }
}
