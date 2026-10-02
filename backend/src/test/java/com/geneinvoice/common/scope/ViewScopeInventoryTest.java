package com.geneinvoice.common.scope;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.scope.ViewScopeInterceptor.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint under /api is deliberately classified for "as of a date", so a new one cannot ship
 * answering live data to a historical request (or changing data in one) by accident; and for "which
 * regions am I asking about", so a new set cannot ship answering every region whatever was asked (B1).
 */
public class ViewScopeInventoryTest extends IntegrationTestBase {

    /** Read-only POSTs that answer as of a date. Some exist only once their slice lands. */
    static final Set<String> READ_POSTS = Set.of(
            "POST /api/customers/export",
            "POST /api/invoices/export",
            "POST /api/payments/export",
            "POST /api/promises/export",
            "POST /api/disputes/export",
            "POST /api/products/export",
            "POST /api/tasks/export",
            "POST /api/approvals/export",
            "POST /api/automation/rules/preview");

    /** Writes that are allowed, live, while a past date is being viewed. */
    static final Set<String> HOUSEKEEPING = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/change-password",
            "POST /api/notifications/{id}/read",
            "POST /api/notifications/mark-all-read",
            "POST /api/notifications/bulk",
            "POST /api/inbox/{id}/read",
            "POST /api/inbox/{id}/unread",
            "POST /api/inbox/mark-all-read",
            "POST /api/inbox/bulk",
            "PUT /api/me/gmail",
            "DELETE /api/me/gmail",
            "POST /api/mail-service/events",
            "POST /api/emails/preview",
            "POST /api/emails/sync",
            "POST /api/admin/history/reconcile",
            "POST /api/users/export",
            "POST /api/roles/export");

    /**
     * Sets: lists, summaries, exports, bulk, the dashboard, pickers. Each is narrowed by {@code regionIds}, and
     * {@code region/RegionParamCoverageTest} proves it endpoint by endpoint.
     */
    public static final Set<String> SETS = Set.of(
            "GET /api/customers",
            "GET /api/customers/summary",
            "POST /api/customers/export",
            "POST /api/customers/bulk",
            "GET /api/invoices",
            "GET /api/invoices/summary",
            "POST /api/invoices/export",
            "POST /api/invoices/bulk",
            "GET /api/payments",
            "GET /api/payments/summary",
            "POST /api/payments/export",
            "POST /api/payments/bulk",
            "GET /api/promises",
            "GET /api/promises/summary",
            "POST /api/promises/export",
            "POST /api/promises/bulk",
            "GET /api/disputes",
            "POST /api/disputes/export",
            "GET /api/dashboard/billed-by-month",
            "GET /api/dashboard/outstanding-by-age",
            "GET /api/dashboard/top-outstanding-customers",
            "GET /api/dashboard/collected-by-month",
            "GET /api/dashboard/top-paying-customers",
            "POST /api/emails/bulk",
            "GET /api/inbox",
            "GET /api/inbox/unread-count",
            "POST /api/inbox/bulk",
            "GET /api/notifications",
            "GET /api/notifications/unread-count",
            "POST /api/notifications/bulk",
            "GET /api/pocs/assignable",
            "GET /api/pocs/my-scope",
            "GET /api/tasks",
            "GET /api/tasks/summary",
            "GET /api/tasks/mine",
            "GET /api/tasks/mine/summary",
            "GET /api/tasks/mine/count",
            "POST /api/tasks/bulk",
            "POST /api/tasks/export",
            "GET /api/assignees/people",
            "GET /api/approvals",
            "GET /api/approvals/summary",
            "GET /api/approvals/awaiting-me/count",
            "POST /api/approvals/export",
            "GET /api/automation/rules",
            "GET /api/automation/rules/summary",
            "POST /api/automation/rules/preview",
            "GET /api/automation/rules/{id}/preview",
            "GET /api/automation/rules/{id}/matches");

    /** One record, or a record's tab: authorised against that record's own region, whatever is asked (I3). */
    static final Set<String> BY_ID = Set.of(
            "GET /api/customers/{id}",
            "GET /api/customers/{id}/pocs",
            "GET /api/invoices/{id}",
            "GET /api/invoices/due-date-preview",
            "GET /api/invoices/assignable-check",
            "GET /api/payments/{id}",
            "GET /api/payments/credits/{customerId}",
            "GET /api/promises/{id}",
            "GET /api/disputes/{id}",
            "GET /api/audit",
            "GET /api/documents",
            "GET /api/documents/count",
            "GET /api/documents/{id}/download",
            "GET /api/emails",
            "GET /api/emails/{id}",
            "GET /api/emails/context",
            "GET /api/emails/delivery",
            "POST /api/emails/preview",
            "GET /api/tasks/{id}",
            "GET /api/tasks/count",
            "GET /api/assignees/context",
            "GET /api/approvals/{id}",
            "GET /api/approvals/for-record",
            "GET /api/automation/rules/{id}",
            "GET /api/automation/rules/{id}/runs",
            "GET /api/automation/runs/{id}",
            "GET /api/automation/runs/{id}/effects");

    /** Writes to one record (checked in its region), or to the caller's own notifications and inbox. */
    static final Set<String> WRITES = Set.of(
            "POST /api/customers",
            "PUT /api/customers/{id}",
            "DELETE /api/customers/{id}",
            "POST /api/customers/{id}/move-region",
            "POST /api/customers/{id}/pocs",
            "DELETE /api/customers/{id}/pocs/{pocId}",
            "POST /api/customers/{id}/pocs/{pocId}/primary",
            "POST /api/invoices",
            "PATCH /api/invoices/{id}",
            "POST /api/invoices/{id}/cancel",
            "POST /api/payments",
            "PATCH /api/payments/{id}",
            "POST /api/promises",
            "PUT /api/promises/{id}",
            "POST /api/promises/{id}/cancel",
            "POST /api/promises/{id}/override",
            "DELETE /api/promises/{id}/override",
            "POST /api/promises/recompute",
            "PUT /api/promises/{id}/assignees",
            "POST /api/disputes",
            "POST /api/disputes/{id}/approve",
            "POST /api/disputes/{id}/deny",
            "PUT /api/disputes/{id}/assignees",
            "POST /api/documents",
            "PATCH /api/documents/{id}",
            "DELETE /api/documents/{id}",
            "POST /api/emails",
            "POST /api/emails/{id}/retry",
            "POST /api/notifications/{id}/read",
            "POST /api/notifications/mark-all-read",
            "POST /api/inbox/{id}/read",
            "POST /api/inbox/{id}/unread",
            "POST /api/inbox/mark-all-read",
            "POST /api/tasks",
            "PATCH /api/tasks/{id}",
            "POST /api/tasks/{id}/status",
            "POST /api/approvals/{id}/approve",
            "POST /api/approvals/{id}/reject",
            "POST /api/approvals/{id}/withdraw",
            "POST /api/automation/rules",
            "PUT /api/automation/rules/{id}",
            "POST /api/automation/rules/{id}/enable",
            "POST /api/automation/rules/{id}/disable",
            "DELETE /api/automation/rules/{id}",
            "POST /api/automation/rules/{id}/resume",
            "POST /api/automation/rules/{id}/run",
            "POST /api/automation/runs/{id}/retry",
            "POST /api/automation/events/{id}/requeue");

    /** Configuration and the caller's own session: nothing here belongs to a region. */
    static final Set<String> GLOBAL_EXEMPT = Set.of(
            "POST /api/auth/login",
            "GET /api/auth/me",
            "POST /api/auth/change-password",
            "GET /api/users",
            "GET /api/users/{id}",
            "POST /api/users",
            "PUT /api/users/{id}",
            "DELETE /api/users/{id}",
            "POST /api/users/bulk",
            "POST /api/users/export",
            "GET /api/roles",
            "GET /api/roles/{id}",
            "POST /api/roles",
            "PUT /api/roles/{id}",
            "DELETE /api/roles/{id}",
            "POST /api/roles/export",
            "GET /api/privileges",
            "GET /api/products",
            "GET /api/products/{id}",
            "POST /api/products",
            "PUT /api/products/{id}",
            "DELETE /api/products/{id}",
            "POST /api/products/bulk",
            "POST /api/products/export",
            "GET /api/table-schemas",
            "GET /api/table-schemas/{entity}",
            "GET /api/table-schemas/all",
            "GET /api/me/gmail",
            "PUT /api/me/gmail",
            "DELETE /api/me/gmail",
            "GET /api/users/{id}/gmail",
            "DELETE /api/users/{id}/gmail",
            "POST /api/mail-service/events",
            "GET /api/pocs/types",
            "GET /api/emails/people",
            "POST /api/emails/sync",
            "GET /api/regions",
            "GET /api/regions/{id}",
            "POST /api/regions",
            "PUT /api/regions/{id}",
            "POST /api/regions/{id}/make-default",
            "DELETE /api/regions/{id}",
            "GET /api/regions/{id}/grants",
            "GET /api/users/{id}/region-grants",
            "PUT /api/users/{id}/region-grants",
            "GET /api/history/range",
            "POST /api/admin/history/reconcile",
            "GET /api/approvals/operations",
            "GET /api/regions/{regionId}/approval-thresholds",
            "PUT /api/regions/{regionId}/approval-thresholds",
            "GET /api/automation/placeholders",
            "GET /api/automation/health");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyReadAnswersEitherAsOfTheDateOrLive() {
        List<String> unclassified = new ArrayList<>();
        endpoints().forEach((endpoint, hm) -> {
            if (!isRead(endpoint)) return;
            Mode mode = ViewScopeInterceptor.classify(hm, method(endpoint));
            if (mode != Mode.AWARE && mode != Mode.IGNORED) unclassified.add(endpoint + " -> " + mode);
        });
        assertThat(unclassified)
                .as("annotate each GET (or its controller) with @AsOfAware or @AsOfIgnored")
                .isEmpty();
    }

    @Test
    void onlyTheListedPostsReadAsOfADateAndOnlyTheListedWritesRunLive() {
        List<String> wrong = new ArrayList<>();
        endpoints().forEach((endpoint, hm) -> {
            if (isRead(endpoint)) return;
            boolean aware = AnnotatedElementUtils.hasAnnotation(hm.getMethod(), AsOfAware.class);
            boolean ignored = AnnotatedElementUtils.hasAnnotation(hm.getMethod(), AsOfIgnored.class);
            if (aware && ignored) wrong.add(endpoint + " is marked both ways");
            if (aware && !READ_POSTS.contains(endpoint)) wrong.add(endpoint + " is not a read-only POST");
            if (ignored && !HOUSEKEEPING.contains(endpoint)) wrong.add(endpoint + " is not housekeeping");
            Mode expected = READ_POSTS.contains(endpoint) ? Mode.AWARE
                    : HOUSEKEEPING.contains(endpoint) ? Mode.IGNORED : Mode.WRITE;
            Mode actual = ViewScopeInterceptor.classify(hm, method(endpoint));
            if (actual != expected) wrong.add(endpoint + " is " + actual + ", expected " + expected);
        });
        assertThat(wrong).isEmpty();
    }

    @Test
    void theRecordReadsAnswerAsOfADateAndConfigurationAnswersLive() {
        Map<String, HandlerMethod> all = endpoints();
        Map<String, Mode> expected = new LinkedHashMap<>();
        expected.put("GET /api/customers", Mode.AWARE);
        expected.put("GET /api/customers/{id}/pocs", Mode.AWARE);
        expected.put("GET /api/invoices/summary", Mode.AWARE);
        expected.put("GET /api/invoices/due-date-preview", Mode.IGNORED);
        expected.put("GET /api/invoices/assignable-check", Mode.IGNORED);
        expected.put("GET /api/payments/credits/{customerId}", Mode.AWARE);
        expected.put("GET /api/promises/{id}", Mode.AWARE);
        expected.put("GET /api/disputes", Mode.AWARE);
        expected.put("GET /api/products", Mode.AWARE);
        expected.put("GET /api/dashboard/billed-by-month", Mode.AWARE);
        expected.put("GET /api/audit", Mode.AWARE);
        expected.put("GET /api/documents", Mode.AWARE);
        expected.put("GET /api/documents/{id}/download", Mode.IGNORED);
        expected.put("GET /api/emails", Mode.AWARE);
        expected.put("GET /api/emails/{id}", Mode.AWARE);
        expected.put("GET /api/emails/context", Mode.IGNORED);
        expected.put("GET /api/emails/delivery", Mode.IGNORED);
        expected.put("GET /api/users", Mode.IGNORED);
        expected.put("GET /api/roles", Mode.IGNORED);
        expected.put("GET /api/privileges", Mode.IGNORED);
        expected.put("GET /api/table-schemas/{entity}", Mode.IGNORED);
        expected.put("GET /api/pocs/my-scope", Mode.IGNORED);
        expected.put("GET /api/notifications", Mode.IGNORED);
        expected.put("GET /api/inbox", Mode.IGNORED);
        expected.put("GET /api/me/gmail", Mode.IGNORED);
        expected.put("GET /api/auth/me", Mode.IGNORED);
        expected.put("GET /api/regions", Mode.IGNORED);
        expected.put("GET /api/regions/{id}/grants", Mode.IGNORED);
        expected.put("GET /api/users/{id}/region-grants", Mode.IGNORED);
        expected.put("POST /api/regions/{id}/make-default", Mode.WRITE);
        expected.put("PUT /api/users/{id}/region-grants", Mode.WRITE);
        expected.put("POST /api/promises/recompute", Mode.WRITE);
        expected.put("PUT /api/promises/{id}/assignees", Mode.WRITE);
        expected.put("PUT /api/disputes/{id}/assignees", Mode.WRITE);
        expected.put("POST /api/emails", Mode.WRITE);
        expected.put("POST /api/customers/bulk", Mode.WRITE);
        expected.put("POST /api/customers/{id}/move-region", Mode.WRITE);
        expected.put("GET /api/history/range", Mode.IGNORED);
        expected.put("POST /api/admin/history/reconcile", Mode.IGNORED);
        expected.put("GET /api/tasks", Mode.AWARE);
        expected.put("GET /api/tasks/mine/count", Mode.AWARE);
        expected.put("GET /api/tasks/{id}", Mode.AWARE);
        expected.put("POST /api/tasks/export", Mode.AWARE);
        expected.put("POST /api/tasks/{id}/status", Mode.WRITE);
        expected.put("POST /api/tasks/bulk", Mode.WRITE);
        expected.put("GET /api/assignees/context", Mode.IGNORED);
        expected.put("GET /api/assignees/people", Mode.IGNORED);
        expected.put("GET /api/approvals/{id}", Mode.AWARE);
        expected.put("POST /api/approvals/{id}/approve", Mode.WRITE);
        expected.put("POST /api/approvals/{id}/reject", Mode.WRITE);
        expected.put("POST /api/approvals/{id}/withdraw", Mode.WRITE);
        expected.put("GET /api/approvals", Mode.AWARE);
        expected.put("GET /api/approvals/summary", Mode.AWARE);
        expected.put("GET /api/approvals/awaiting-me/count", Mode.AWARE);
        expected.put("GET /api/approvals/for-record", Mode.AWARE);
        expected.put("POST /api/approvals/export", Mode.AWARE);
        expected.put("GET /api/approvals/operations", Mode.IGNORED);
        expected.put("GET /api/regions/{regionId}/approval-thresholds", Mode.IGNORED);
        expected.put("PUT /api/regions/{regionId}/approval-thresholds", Mode.WRITE);
        expected.put("GET /api/automation/rules", Mode.AWARE);
        expected.put("GET /api/automation/rules/summary", Mode.AWARE);
        expected.put("GET /api/automation/rules/{id}", Mode.AWARE);
        expected.put("POST /api/automation/rules/preview", Mode.AWARE);
        expected.put("GET /api/automation/rules/{id}/preview", Mode.AWARE);
        expected.put("GET /api/automation/rules/{id}/matches", Mode.AWARE);
        expected.put("POST /api/automation/rules", Mode.WRITE);
        expected.put("PUT /api/automation/rules/{id}", Mode.WRITE);
        expected.put("POST /api/automation/rules/{id}/enable", Mode.WRITE);
        expected.put("DELETE /api/automation/rules/{id}", Mode.WRITE);
        expected.put("GET /api/automation/placeholders", Mode.IGNORED);
        expected.put("GET /api/automation/health", Mode.IGNORED);
        expected.put("GET /api/automation/rules/{id}/runs", Mode.AWARE);
        expected.put("GET /api/automation/runs/{id}", Mode.AWARE);
        expected.put("GET /api/automation/runs/{id}/effects", Mode.AWARE);
        expected.put("POST /api/automation/rules/{id}/resume", Mode.WRITE);
        expected.put("POST /api/automation/rules/{id}/run", Mode.WRITE);
        expected.put("POST /api/automation/runs/{id}/retry", Mode.WRITE);
        expected.put("POST /api/automation/events/{id}/requeue", Mode.WRITE);
        expected.forEach((endpoint, mode) -> {
            assertThat(all).as(endpoint).containsKey(endpoint);
            assertThat(ViewScopeInterceptor.classify(all.get(endpoint), method(endpoint))).as(endpoint).isEqualTo(mode);
        });
    }

    @Test
    void everyEndpointSaysWhetherItAnswersWhichRegions() {
        List<String> wrong = new ArrayList<>();
        endpoints().keySet().forEach(endpoint -> {
            long lists = Stream.of(SETS, BY_ID, WRITES, GLOBAL_EXEMPT).filter(l -> l.contains(endpoint)).count();
            if (lists != 1) wrong.add(endpoint + " is in " + lists + " region lists");
        });
        assertThat(wrong)
                .as("put each endpoint in exactly one of SETS, BY_ID, WRITES and GLOBAL_EXEMPT")
                .isEmpty();
    }

    @Test
    void theInboxAndNotificationsAnswerWhichRegionsThoughTheyAnswerLive() {
        Map<String, HandlerMethod> all = endpoints();
        for (String endpoint : List.of("GET /api/inbox", "GET /api/inbox/unread-count",
                "GET /api/notifications", "GET /api/notifications/unread-count")) {
            assertThat(SETS).as(endpoint).contains(endpoint);
            assertThat(ViewScopeInterceptor.classify(all.get(endpoint), method(endpoint))).as(endpoint)
                    .isEqualTo(Mode.IGNORED);
        }
    }

    private Map<String, HandlerMethod> endpoints() {
        Map<String, HandlerMethod> out = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            Set<String> verbs = new TreeSet<>();
            if (methods.isEmpty()) {
                verbs.addAll(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            } else {
                methods.forEach(m -> verbs.add(m.name()));
            }
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                for (String verb : verbs) out.put(verb + " " + path, e.getValue());
            }
        }
        assertThat(out).as("the handler mappings were found").isNotEmpty();
        return out;
    }

    private static String method(String endpoint) {
        return endpoint.substring(0, endpoint.indexOf(' '));
    }

    private static boolean isRead(String endpoint) {
        String m = method(endpoint);
        return m.equals("GET") || m.equals("HEAD");
    }
}
