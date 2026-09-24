package com.geneinvoice.automation;

import com.geneinvoice.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tripwire on the trigger's coverage (A1).
 *
 * <p>Everything else in this feature is a test of what the engine does with the writes it knows
 * about. This one is about the writes it does NOT know about yet: it walks the endpoints the
 * running application actually publishes for the three subjects a rule can be written about, and
 * compares them against a list somebody had to type. The day a colleague adds
 * {@code POST /api/invoices/{id}/reissue}, this fails and its author has to answer one question in
 * writing — does this change a record a rule can be about? — rather than discovering in six months
 * that half the dunning rules silently never fired for reissued invoices.
 *
 * <p>It is deliberately NOT derived from the axis table, the audit call sites or anything else the
 * engine reads, because a list generated from the thing it is checking confirms whatever that
 * thing says. The sentences are the review.
 */
class AutomationTriggerCoverageTest extends IntegrationTestBase {

    @Autowired RequestMappingHandlerMapping handlerMapping;

    /** The three subjects a rule can name; nothing else is in scope for this check (A1). */
    private static final List<String> SUBJECT_ROOTS =
            List.of("/api/customers", "/api/invoices", "/api/payments");

    private static final Set<RequestMethod> MUTATIONS = Set.of(
            RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /**
     * Every mutating endpoint on the three subjects, and what it publishes. "Audit hook" means the
     * write goes through {@code AuditService.record} against its own subject and the feed
     * classifies it; a named exception says why it publishes nothing or publishes something else.
     *
     * <p>DEVIATION, small and deliberate: the settled text says to DROP the /export mappings
     * before comparing. They are listed instead, each saying in words that it is a POST only
     * because a filter set does not fit in a query string and that it writes nothing. A dropped
     * line is a line nobody reads; the sentences ARE the review this test exists to force (A1).
     */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            entry("POST /api/customers",
                    "TRIGGER, audit hook: CUSTOMER_CREATED is on the CREATES allow-list"),
            entry("PUT /api/customers/{id}",
                    "TRIGGER, audit hook: CUSTOMER_UPDATED"),
            entry("DELETE /api/customers/{id}",
                    "DELIBERATELY NOT A TRIGGER: CUSTOMER_DELETED is a hard delete and there is"
                            + " no record left for a rule to condition on or act against"),
            entry("POST /api/customers/{id}/region",
                    "TRIGGER, audit hook: CUSTOMER_REGION_CHANGED — and it matters, because the"
                            + " branch a rule is bounded by is the one it just moved to"),
            entry("POST /api/customers/{id}/pocs",
                    "TRIGGER, audit hook: POC_ASSIGNED is audited against CUSTOMER with a null"
                            + " before, which is why the feed classifies by action and not by"
                            + " nullness"),
            entry("DELETE /api/customers/{id}/pocs/{pocId}",
                    "TRIGGER, audit hook: POC_REMOVED is audited against CUSTOMER with a null"
                            + " after, and a seat removal is a customer update, not a deletion"),
            entry("POST /api/customers/{id}/pocs/{pocId}/primary",
                    "TRIGGER, audit hook: POC_PRIMARY_CHANGED against CUSTOMER"),
            entry("POST /api/customers/bulk",
                    "TRIGGER, audit hook, one event per row: each row is its own"
                            + " REQUIRES_NEW transaction and publishes with its own commit"),
            entry("POST /api/customers/export",
                    "NOT A MUTATION: a POST because a filter set does not fit in a query string"),

            entry("POST /api/invoices",
                    "TRIGGER, audit hook: INVOICE_CREATED is on the CREATES allow-list, and the"
                            + " PAYMENT_APPLIED rows the same transaction writes coalesce into it"),
            entry("PATCH /api/invoices/{id}",
                    "TRIGGER, audit hook: INVOICE_UPDATED / INVOICE_DUE_DATE_CHANGED"),
            entry("POST /api/invoices/{id}/cancel",
                    "TRIGGER, audit hook: INVOICE_CANCELLED"),
            entry("POST /api/invoices/bulk",
                    "TRIGGER, audit hook, one event per row"),
            entry("POST /api/invoices/export",
                    "NOT A MUTATION"),

            entry("POST /api/payments",
                    "TRIGGER, audit hook: PAYMENT_RECORDED is on the CREATES allow-list; the"
                            + " PAYMENT_APPLIED rows publish the invoices it paid, and the"
                            + " explicit call in PaymentService.applyTo publishes the customer"
                            + " whose credit balance an overpayment moved"),
            entry("PATCH /api/payments/{id}",
                    "TRIGGER, audit hook: PAYMENT_UPDATED"),
            entry("POST /api/payments/bulk",
                    "TRIGGER, audit hook, one event per row: REASSIGN_COLLECTION_POC goes through"
                            + " PaymentService.update and audits PAYMENT_UPDATED"),
            entry("POST /api/payments/export",
                    "NOT A MUTATION")));

    /**
     * The eight hand-placed calls, counted per file, because four of them are the ONLY source for
     * the change they describe and deleting one is silent: no test of behaviour anywhere else in
     * the suite notices a rule that simply stops being offered a fact (A1).
     *
     * <p>CreditLedger.applyTo and .refund, PaymentService.applyTo and .reverseAllocations are the
     * load-bearing four — every one of them moves {@code customer.credit_balance} and audits
     * nothing against CUSTOMER. PaymentService.voidPayment and .updateAmount and InvoiceService's
     * two dispute hatches are the defensive four: audited today only through
     * DisputeService.approve, which anchors its row on the target record, so coalescing collapses
     * them. A NINTH call site is not forbidden — it is a review, which is what this map forces.
     */
    private static final Map<String, Integer> EXPLICIT_CALLS = new TreeMap<>(Map.of(
            "payment/CreditLedger.java", 2,
            "payment/PaymentService.java", 4,
            "invoice/InvoiceService.java", 2));

    @Test
    void everyMoneyMutationEndpointIsEitherATriggerOrNamedAsDeliberatelyNotOne() {
        Set<String> found = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (SUBJECT_ROOTS.stream().noneMatch(pattern::startsWith)) continue;
                for (RequestMethod method : methods) {
                    if (MUTATIONS.contains(method)) found.add(method + " " + pattern);
                }
            }
        }

        // Not vacuous: a mapping walk that silently found nothing would agree with an empty list.
        assertThat(found).hasSizeGreaterThanOrEqualTo(15);
        assertThat(found).isEqualTo(new TreeSet<>(EXPECTED.keySet()));
    }

    @Test
    void theEightHandPlacedCallsAreStillWhereTheyWerePutAndNowhereElse() throws IOException {
        Path main = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(main))
                .describedAs("run from the backend module directory; src/main/java is not here")
                .isTrue();
        Path root = main.resolve("com").resolve("geneinvoice");

        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int calls = occurrences(Files.readString(file), "changeFeed.changed(");
                if (calls > 0) found.put(root.relativize(file).toString().replace('\\', '/'), calls);
            }
        }

        assertThat(found).isEqualTo(EXPLICIT_CALLS);
        assertThat(found.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(8);
    }

    private static int occurrences(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
