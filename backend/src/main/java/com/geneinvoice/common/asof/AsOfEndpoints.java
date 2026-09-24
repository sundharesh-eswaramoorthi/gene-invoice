package com.geneinvoice.common.asof;

import java.util.Map;
import java.util.Set;

/**
 * THE ALLOWLIST, and the reason an endpoint that silently ignores ?asOf is impossible.
 *
 * <p>Refusal is the default. AsOfInterceptor lets a request carrying ?asOf through only when its
 * "METHOD pattern" pair is named here; everything else is 400 before the handler runs. A read
 * endpoint written next year is refused until somebody decides which side it belongs on, and a
 * renamed path drops out of the allowlist loudly rather than quietly (B3).
 *
 * <p>THIS IS A DECLARED SERIALIZATION POINT, AND IT IS NOW CLOSED. Both collections were appended
 * to one slice unit at a time — B3-SLICE-INVOICE, B3-SLICE-REST, B3-DASHBOARD — and B3-CLOSE filled
 * NOT_AS_OF in full and added the architecture test that reflects over RequestMappingHandlerMapping
 * and asserts that AS_OF_CAPABLE ∪ NOT_AS_OF is EXACTLY the set of GET mappings this application
 * declares, with nothing in both. They were a promise until that test landed; they are enforced
 * now. A GET mapping added after this point fails the build until its author picks a side, which
 * is the whole reason B3-CLOSE is the last backend unit in the programme (B3).
 *
 * <p>Both are keyed "METHOD pattern", not pattern alone, so the union B3-CLOSE asserts is taken in
 * one key space and a POST /export that is as-of capable cannot be confused with the GET on the
 * same path (B3).
 */
public final class AsOfEndpoints {

    /** Contract clause a.2: outside the temporal boundary, so there is no past to read (B3). */
    public static final String NOT_MIRRORED = "not mirrored (contract a.2)";

    /** The reads that describe the person asking rather than anything in the ledger (B3). */
    public static final String CALLER = "about the caller, not a record; there is no third state";

    /**
     * The endpoints that can actually answer as of a date, one slice unit at a time.
     *
     * <p>THE NON-GET PAIRS ARE THE SIX CSV EXPORT POSTS AND ONE MORE. This repo's exports are
     * POST — verified at InvoiceController, PaymentController, CustomerController,
     * PaymentPromiseController and DisputeController — and an export is a READ that happens to
     * carry its query in a body. B3-RULES added the seventh on the same argument and no other:
     * {@code POST /api/automation/rules/{id}/simulate} is a backtest that writes nothing. Every
     * other mutating mapping stays 400 by construction because nothing names it here, and a
     * non-GET that WRITES may never be named here at all — the replay beside the simulate is the
     * worked example, and it is not in this set (B3).
     *
     * <p>B3-SLICE-INVOICE, the first four. All four read from the one source switch
     * {@code InvoiceService.invoiceSource()}, so there is no per-endpoint as-of code behind any of
     * them: the list and the tiles are the executor's {@code run} and {@code aggregate}, the export
     * is {@code run} plus {@code ids}, and the single record is {@code run} with an {@code id:eq:}
     * filter — which is also what makes its 404 for an invoice that did not exist then the same
     * 404 the book already gives for one outside it (AUTH-08, B3).
     */
    public static final Set<String> AS_OF_CAPABLE = Set.of(
            "GET /api/invoices",
            "GET /api/invoices/summary",
            "GET /api/invoices/{id}",
            "POST /api/invoices/export",

            // B3-SLICE-REST, the other five mirrored families. Each one is the SAME four patterns
            // behind the SAME source switch — list, tiles, single record and export — except that
            // disputes have no /summary endpoint to register (B3).
            "GET /api/customers",
            "GET /api/customers/summary",
            "GET /api/customers/{id}",
            "POST /api/customers/export",

            "GET /api/payments",
            "GET /api/payments/summary",
            "GET /api/payments/{id}",
            "POST /api/payments/export",

            "GET /api/promises",
            "GET /api/promises/summary",
            "GET /api/promises/{id}",
            "POST /api/promises/export",

            "GET /api/disputes",
            "GET /api/disputes/{id}",
            "POST /api/disputes/export",

            "GET /api/tasks",
            "GET /api/tasks/summary",
            "GET /api/tasks/{id}",
            "POST /api/tasks/export",

            // THE TWO PLUMBED READS THAT ARE NOT LISTS. Both answer a question about an ACCOUNT
            // rather than about a row of a table, and both would otherwise serve today's answer
            // under a banner that said January: who sat on the account then, and what credit it
            // held then. The third plumbed read the unit names — GET /api/disputes/{id} — is a
            // single record and is registered with its own family above (B3).
            "GET /api/customers/{id}/pocs",
            "GET /api/payments/credits/{customerId}",

            // THE APPROVAL QUEUE, WITH NO MIRROR TABLE BEHIND IT AT ALL. pending_changes is
            // already interval-shaped — requestedAt opens it and decidedAt closes it — so these
            // three read the decision log as the history it already is, through
            // AsOf.outstandingAt. That is the second half of the PRD clause: which approvals were
            // outstanding then, and in which branch (B2, B3).
            //
            // POST /api/approvals/export is deliberately NOT here. The unit registers the three
            // approval GETs and no fourth pattern, so a download of the January queue is a 400
            // until somebody decides it should not be (B3).
            "GET /api/approvals",
            "GET /api/approvals/summary",
            "GET /api/approvals/{id}",

            // B3-DASHBOARD, the five figures. They are the one reader in the application that does
            // not go through TableQueryExecutor — DashboardService builds Criteria by hand and
            // applies the book through a scoped() of its own — so as-of is plumbed there by hand
            // too: three invoice roots swapped for invoice_history, a second body for the two
            // money figures, and AsOfContext.instant() ANDed onto every mirror root so a figure
            // counts RECORDS and not versions. None of the five takes a new parameter; the
            // interceptor opens the context and DashboardController.today() already followed the
            // reader into the past (B3).
            "GET /api/dashboard/billed-by-month",
            "GET /api/dashboard/outstanding-by-age",
            "GET /api/dashboard/top-outstanding-customers",
            "GET /api/dashboard/collected-by-month",
            "GET /api/dashboard/top-paying-customers",

            // B3-CLOSE, THE LAST ONE IN, AND THE ONLY AS-OF READ WITH NO MIRROR AND NO INTERVAL
            // TABLE BEHIND IT. audit_logs is append-only, so "the trail as it stood on 31 January"
            // is the trail with everything recorded after that instant removed — a truncation, not
            // a reconstruction. AuditTimelineService does exactly that, and SUPPRESSES its derived
            // entries entirely while a date is set, because those are fabricated from the LIVE
            // record and must never be presented as historical evidence (B3).
            "GET /api/audit",

            // B3-RULES, THE LAST ENTRY IN THE PROGRAMME AND THE ONLY NON-GET HERE THAT IS NOT A
            // CSV EXPORT. The javadoc above says the only non-GET pairs that may ever be added are
            // the export posts; this is the argument for the one exception, made out loud because
            // AsOfContractTest refuses to let it be made quietly.
            //
            // A simulate is a READ that happens to be spelled POST — the same sentence the exports
            // make — and it writes nothing at all: there is no write in the method, and one would
            // in any case be refused by HistoryWriter.drain, which throws for any non-read-only
            // transaction that commits while a context is open. It is a POST because it sits
            // beside POST .../run whose shape it copies, and because what it returns is a
            // computation over the whole population rather than a resource.
            //
            // THE REPLAY IS NOT HERE AND MUST NOT BE. POST /api/automation/rules/{id}/replay
            // opens a real run and plans real steps, so it is 400 "The past is read only" for
            // ?asOf like every other mutating mapping; the day it replays travels in its body,
            // where it is a property of the run rather than a request to read the past (A5, B3).
            "POST /api/automation/rules/{id}/simulate");

    /**
     * Pattern -> the one line that says why this read is always answered as of now (B3).
     *
     * <p>FILLED IN FULL BY B3-CLOSE, AND THAT IS WHAT TURNS THE ALLOWLIST FROM A PROMISE INTO A
     * PROOF. AsOfContractTest asserts that AS_OF_CAPABLE ∪ NOT_AS_OF is EXACTLY the set of GET
     * mappings this application declares, and that the two do not overlap. A read endpoint written
     * next year therefore fails the build until its author decides which side it is on, and a
     * renamed path fails on both sides at once rather than dropping quietly out of the allowlist.
     *
     * <p>THREE REASONS COVER ALL BUT A HANDFUL. "not mirrored (contract a.2)" is the temporal
     * boundary itself: products, people, roles, mail, documents and the schema catalogue keep no
     * interval history, so there is nothing to read as of a date and answering today's values under
     * a January banner is the one thing this feature exists to prevent. "about the caller, not a
     * record; there is no third state" covers the three reads that describe the person asking
     * rather than anything in the ledger — they would be unaffected by a date, and 400 is chosen
     * over silently ignoring it because a parameter that is sometimes honoured and sometimes
     * dropped is worse than one that is always refused. The rest say their own piece.
     */
    public static final Map<String, String> NOT_AS_OF = Map.ofEntries(
            // The feature describing itself.
            Map.entry("GET /api/as-of", "describes the feature, not a record"),

            // ABOUT THE CALLER, NOT A RECORD. Deliberately 400 rather than "ignored": a third
            // state — accepted and silently disregarded — is the behaviour the allowlist exists to
            // make unreachable (B3).
            Map.entry("GET /api/auth/me", CALLER),
            Map.entry("GET /api/invoices/assignable-check", CALLER),
            Map.entry("GET /api/pocs/my-scope", CALLER),
            Map.entry("GET /api/regions/my", CALLER),

            // A FORECAST, NOT HISTORY. due-date-preview computes a date that has not happened yet
            // from terms that apply now; asking what it would have said in January is a question
            // about a calculation, not about a record (B3).
            Map.entry("GET /api/invoices/due-date-preview", "a forecast, not history"),

            // COUNTS OF WHAT IS WAITING NOW. Both are badges: a number a person acts on today. The
            // LISTS behind them are as-of capable, which is where a historical question belongs
            // (A6, A5, B3).
            Map.entry("GET /api/tasks/count",
                    "a count of what is waiting for me now, not a record"),
            Map.entry("GET /api/automation/steps/poisoned-count",
                    "an operational count of what is stuck now, not a record"),

            // CONFIGURATION IN FORCE NOW. A threshold is not a record with a history; it is the
            // rule the queue is being judged by today, and B2 keeps no interval table for it (B2,
            // B3).
            Map.entry("GET /api/approvals/thresholds/{regionId}",
                    "the threshold in force now; " + NOT_MIRRORED),

            // THE BRANCH MAP ITSELF. Region is not a mirrored entity, which is contract clause a.3
            // and is why every as-of answer in the application renders a branch under TODAY'S name
            // while resolving WHICH branch from the placement ledger (B1, B3).
            Map.entry("GET /api/regions", NOT_MIRRORED),

            // THE AUTOMATION AUTHORING SURFACE. Rules, steps and the placeholder catalogue are the
            // machinery, not the ledger (A1, A5, B3).
            Map.entry("GET /api/automation/rules", NOT_MIRRORED),
            Map.entry("GET /api/automation/rules/{id}", NOT_MIRRORED),
            Map.entry("GET /api/automation/steps", NOT_MIRRORED),
            Map.entry("GET /api/automation/placeholders", NOT_MIRRORED),

            // EVERYTHING ELSE OUTSIDE THE TEMPORAL BOUNDARY, contract clause a.2, table by table.
            Map.entry("GET /api/products", NOT_MIRRORED),
            Map.entry("GET /api/products/{id}", NOT_MIRRORED),
            Map.entry("GET /api/users", NOT_MIRRORED),
            Map.entry("GET /api/users/{id}", NOT_MIRRORED),
            // Who may work WHERE today. user_region_grants keeps no interval history: the
            // placement ledger records where a CUSTOMER was, never how far a person's reach
            // reached, so there is no past of this to read (B1, B3, GRANTS-READBACK).
            Map.entry("GET /api/users/{id}/regions", NOT_MIRRORED),
            Map.entry("GET /api/users/{id}/gmail", NOT_MIRRORED),
            Map.entry("GET /api/me/gmail", NOT_MIRRORED),
            Map.entry("GET /api/roles", NOT_MIRRORED),
            Map.entry("GET /api/roles/{id}", NOT_MIRRORED),
            Map.entry("GET /api/privileges", NOT_MIRRORED),
            Map.entry("GET /api/notifications", NOT_MIRRORED),
            Map.entry("GET /api/notifications/unread-count", NOT_MIRRORED),
            Map.entry("GET /api/inbox", NOT_MIRRORED),
            Map.entry("GET /api/inbox/unread-count", NOT_MIRRORED),
            Map.entry("GET /api/emails", NOT_MIRRORED),
            Map.entry("GET /api/emails/{id}", NOT_MIRRORED),
            Map.entry("GET /api/emails/context", NOT_MIRRORED),
            Map.entry("GET /api/emails/delivery", NOT_MIRRORED),
            Map.entry("GET /api/emails/people", NOT_MIRRORED),
            Map.entry("GET /api/documents", NOT_MIRRORED),
            Map.entry("GET /api/documents/count", NOT_MIRRORED),
            Map.entry("GET /api/documents/{id}/download", NOT_MIRRORED),
            Map.entry("GET /api/pocs/assignable", NOT_MIRRORED),
            Map.entry("GET /api/pocs/types", NOT_MIRRORED),
            Map.entry("GET /api/table-schemas", NOT_MIRRORED),
            Map.entry("GET /api/table-schemas/all", NOT_MIRRORED),
            Map.entry("GET /api/table-schemas/{entity}", NOT_MIRRORED));

    /**
     * THE ONE EXEMPTION, AND IT IS NOT AN ALLOWLIST ENTRY. Part A shipped ?asOf on the manual rule
     * run BEFORE this feature existed, and there it means something different: the date the RULE
     * is evaluated at, parsed by the handler, allowed for a dry run and refused with its own
     * message ("A rule can only be run as of today") for a run that would actually act. The
     * interceptor therefore neither refuses it nor opens a context for it — it steps aside and
     * lets the handler own its own parameter (A5, B3 INTEGRATION).
     *
     * <p>IT MUST NOT BE MOVED INTO AS_OF_CAPABLE. Opening a context here would make
     * AutomationRuleService.runNow's guard — {@code !asOf.equals(today())} — compare the date
     * against ITSELF, because today() follows the reader into the past, and a back-dated apply
     * would start creating real Tasks dated from a replayed past.
     *
     * <p>B3-RULES DECIDED: IT STAYS EXACTLY AS PART A SHIPPED IT. {@code ?asOf} on this mapping
     * still means "render the preview's dates as of", it is still matched LIVE, and an apply at a
     * past date is still refused with "A rule can only be run as of today". The two questions Part
     * A could not answer got their own doors instead —
     * {@code POST /api/automation/rules/{id}/simulate} matches as of a date and writes nothing,
     * and {@code POST /api/automation/rules/{id}/replay} opens a run whose recorded as_of IS the
     * replayed day. Nothing about this entry changed, which is the point: a shipped meaning of a
     * shipped parameter was not quietly redefined underneath the clients that send it (A5, B3).
     */
    public static final Map<String, String> HANDLER_OWNED = Map.of(
            "POST /api/automation/rules/{id}/run",
            "the rule engine parses asOf itself and refuses a back-dated apply on its own terms");

    private AsOfEndpoints() {
    }

    public static String key(String method, String pattern) {
        return method + " " + pattern;
    }

    /** False for a null pattern too: a request Spring could not match cannot be allowlisted. */
    public static boolean capable(String method, String pattern) {
        return pattern != null && AS_OF_CAPABLE.contains(key(method, pattern));
    }

    /** True when the handler reads asOf itself and the interceptor must not touch it (B3). */
    public static boolean handlerOwned(String method, String pattern) {
        return pattern != null && HANDLER_OWNED.containsKey(key(method, pattern));
    }
}
