# Handover — Gene Invoice, 15–16 Sep 2026

Start a new session with: *"Read `docs/regression/2026-09-15/HANDOVER.md` and continue."*

## Where things stand

- **The feature** — POC ownership, Payment Promises, and the list/detail table framework
  ([PRD](../../requirements/poc-payment-promise-and-tables.md),
  [design notes](../../implementation/poc-payment-promise-and-tables.md)) — is implemented and
  committed on `main` (`87c502c`). **Nothing is pushed**; ask the user before pushing.
- **A full regression run finished on 15 Sep 2026:** 503 cases across 10 areas (API and UI, all
  7 roles plus customer logins, desktop and phone width). 375 passed, 128 failed as reported.
  After every failure was reproduced by an independent verifier and duplicates were merged:
  **67 confirmed defects — 12 high, 27 medium, 28 low.** Verdict: **not ready for release.**
  Re-checking the medium fixes on 16 Sep found five more (D-68…D-72), so `defects.md` now lists
  **72 — 12 high, 28 medium, 32 low.**
- **71 of the 72 defects are fixed** — all 12 high, all 28 medium and 31 of the 32 low — committed
  (not pushed) with regression tests, and re-verified against the regression environment
  (8083/8084). See the three "-severity fixes" sections below and the Status column in
  `defects.md`. **Only D-72 is open** (table row checkboxes are not in the accessibility tree).
  It was confirmed with `scripts/verify-low-fixes/probe-d72.js` — the invoices table exposes 160
  cells and 8 column headers but no row checkbox — and then attempted: replacing Material's
  selection column with a labelled `Semantics` + `Checkbox` of our own left the tree with zero
  checkbox nodes just the same, so that change was reverted. Which widget draws the checkbox is
  not the cause. See its entry in `defects.md` for what to try next; it needs Flutter's semantics
  debugger and a real screen reader, not another blind swap.
- Automated suites are green: backend `mvn test` 193/193 (22 classes), `flutter analyze` clean,
  `flutter test` 45/45.
- **Waiting on the user — the user's deployment (8082/8081) does not have group 5 yet.** The user
  approved applying it ("go", 16 Sep), but restarting 8082 was refused by the session's automatic
  permission check, so the user must run the restart or allow it. Then run the promise recompute:
  preview first, and apply only if it still shows the approved changes — see "Deploying group 5 to
  the user's data" below.
- Next after that: the low defects.

## What is in this folder

| Path | What it is |
|---|---|
| `HANDOVER.md` | This file — start here |
| `report/index.html` | The full interactive report (open it in a browser; works offline). Published copy: https://claude.ai/artifact/JEa2ujkXpq8GX9xzxyvVth |
| `defects.md` | All 72 confirmed defects: severity, repro, expected/actual, root cause (file:line), suggested fix, screenshot. **Status** column: high and medium FIXED (commit; checks that passed), low OPEN |
| `test-results.md` | Pass/fail per area, every case per area, verifier verdicts, recommendations, what was not tested |
| `test-cases.csv` | All 503 cases (area, id, feature, kind, title, status, severity, steps, expected, actual, evidence, codeRef) |
| `report/shots/` | Screenshot evidence for 36 defects |
| `data/regression-results.json` | Raw results: the report plus every tester's cases and every verifier's verdicts |
| `data/codebase-map.md` | Architecture map of the feature, per-feature file index and a symptom → where-to-look table (written 12 Sep, before this session's fixes) |
| `data/suspected-issues-static-review.md` | 62 issues suspected from reading the code before testing (leads, not facts; some since fixed or confirmed) |
| `scripts/` | The testers' and verifiers' Node scripts, the shared helper `lib.js`, and `TESTER-RULES.md` (how the run was organised and how to drive this Flutter app) |

## The 12 high-severity defects (all fixed — see "High-severity fixes")

| ID | Defect |
|---|---|
| D-01 | A deactivated user's existing token keeps full API access for up to 24 h |
| D-02 | Exports check only `EXPORT_DATA`, not the entity's view privilege — CASHIER/POC roles can export all users (emails), roles, disputes, products |
| D-03 | Voiding a payment whose money already moved through customer credit leaves spendable credit, or invoices paid with no payment behind them |
| D-04 | The `FilledButton` theme (`minimumSize: Size.fromHeight(46)`) gives infinite width inside a Row: dispute Approve/Deny and Invoice/Payment "Save changes" are not painted; a click on the blank dispute row approves |
| D-05 | Creating invoices concurrently fails with 500 duplicate invoice number |
| D-06 | Invoice form and Record payment dialog offer only the first 50 customers/products |
| D-07 | Notes-only save on Invoice/Payment details fails when the POC is deactivated or the role lacks `POC_ASSIGN` |
| D-08 | Customer logins can filter/sort on POC-restricted columns and infer their reps |
| D-09 | A KEPT general promise flips to BROKEN (and notifies) when a later invoice is raised |
| D-10 | A promise can't be edited once one of its invoices is cancelled |
| D-11 | Unsaved-changes guard only covers the Back arrow; sidebar, browser Back and links drop edits silently |
| D-12 | On phones a long "username • ROLE" chip pushes the bell over the hamburger menu |

Shared causes behind many medium defects (all fixed on 16 Sep): missing 400 handlers in
`GlobalExceptionHandler` (D-13, D-27, D-29), the bulk `resolveIds` pattern that silently drops ids
(D-14 — `BulkActionTest` currently asserts the drop), table width that ignores the nav rail
(D-19, D-20), the `PocPicker` debounce (D-18).

## High-severity fixes (second 15 Sep session)

Committed on `main`, not pushed. Every commit carries its own regression tests.

| Commit | Defects | What changed |
|---|---|---|
| `51619ef` | D-01, D-02, D-08 | `JwtAuthFilter` no longer authenticates a disabled or locked account, so old tokens get 401. Every `/export` also needs the table's `*_VIEW`. `TableSchema.visibleTo(customerScoped)` drops POC columns for customer logins; list, summary, bulk, export and `/api/table-schemas` all use it. |
| `1e4d127` | D-03, D-05 | New `payment/CreditLedger`: a dispute refund moves the invoice's allocations back into the paying payments' `creditApplied` (newest first); a new invoice paid from credit gets allocations from the payments holding it (oldest first, audited as `PAYMENT_APPLIED`). Invariant: an active payment's `amount = allocations + creditApplied`. New `invoice/InvoiceNumbers` draws numbers from one locked row (`invoice_number_sequence`, created by ddl-auto, seeded at startup) — it must stay the first thing `InvoiceService.create` does, before any write, or concurrent creates can deadlock. `DataIntegrityViolationException` → 409 without SQL. |
| `691c39d` | D-07, D-09, D-10 | Invoice, payment and promise updates check the POC (and POC_ASSIGN) only when it actually changes; the detail screens send the POC only when changed. A general promise answers for invoices dated by its promised date **or already raised when it was made** (`owedUnderPromise`). An already-linked cancelled invoice stays acceptable on promise edit, and the edit dialog lists it so it can be unticked. |
| `a04c5e8`, `8ffed8f` | D-04, D-06, D-11, D-12 | Theme `FilledButton` minimum size `Size(64, 46)`. `shared/widgets/search_picker_field.dart` (server-side search; the dialog owns its search text) replaces the 50-item customer and product dropdowns. `core/unsaved_changes.dart`: detail screens register their discard prompt; `GoRoute.onExit` on `/customers/:id`, `/invoices/:id`, `/payments/:id` covers browser Back; in-app links use `goGuarded()`, which asks before `go()`. App bar shows only the account icon below 600px and caps the label at 200px above. |

Behaviour to know about:

- A payment's "Credit applied" is now the part of it still sitting in customer credit. It drops when
  a later invoice is paid from it, and that invoice then appears in the payment's invoice list and
  History. Credit from before the ledger has no source payment; a void keeps the old floor-at-zero
  behaviour for that part only.
- Invoice numbers continue from the sequence row, not from a count, so a deleted invoice's number
  is never reused.

Verification: `scripts/verify-high-fixes/api.js` (V-01…V-12, all pass against 8083 on Postgres) and
`scripts/verify-high-fixes/ui/` (U-01…U-25, a real browser against 8084, all pass; results in
`ui-results.json`, first-run evidence in `run1/`). The screenshots (`*.png`, about 33 MB) are kept
on disk but not committed.

Found while verifying the high fixes, and fixed with the medium batch (re-checked 16 Sep):

- The bell's unread badge swallowed taps on the icon — now wrapped in `IgnorePointer` (W-18 pass).
- The promise card showed "₹… left" for a cancelled invoice — now "… • cancelled" (W-09 pass).
- Only customer logins may open disputes (`DisputeService`), but admin screens offered
  "Raise dispute" — now shown only where `canRaiseDispute` holds (W-05 pass). Scripts open disputes
  with the customer login.
- D-18 and D-38 were fixed with the medium batch (W-10, W-16 pass).

## Medium-severity fixes (16 Sep)

Committed on `main`, not pushed. Every commit carries its own regression tests.

| Commit | Defects | What changed |
|---|---|---|
| `8cb2f87` | D-13, D-27, D-28, D-29, D-30, D-32 | Malformed input (bad JSON, enum, number, id, missing param) → 400 with a plain message, no class names. Emails and role names are unique ignoring case, and a duplicate is a field error, not SQL. A blank email is stored as no email. Overlong text is a 400 field error (`FieldLimits`). Amounts may have at most 2 decimals (`Money.requireCents`) — payments, promises and unit prices. Dispute changes need quantity ≥ 1; a unit price of 0 is allowed. |
| `d57a846` | D-15, D-16, D-17, D-22 | A Sales POC's locked book now also applies to reads and edits by id (404 outside the book) and to seat changes. Promise DTOs no longer show staff user ids to customers. USER audit history needs `USER_VIEW`. Table schemas are fetched afresh for each signed-in user. |
| `44fc64f` | D-14, D-31, D-33, D-36, D-37 | Bulk actions report ids they cannot reach as skipped with one neutral reason (`BulkExecutor.run`, `NOT_REACHABLE`). Record payment keeps a Collection POC the cashier already picked. A cancelled promise can't be overridden. The default Collection POC is the first *active* one (no active seat → 400). A Promises row and its tiles refresh after an override. |
| `7552dd6`, `bf1613f` | D-18…D-21, D-23…D-25, D-38, D-39 | POC picker searches the text actually typed. Desktop tables are sized to the space beside the sidebar, with an always-visible horizontal scrollbar and 24 px column gaps; long-text columns (dispute Target/Customer/Reason, notification Title/Message) are capped, end in "…" and show the full text on hover. Export-only roles can select rows. Date upper bounds no longer take in the next day's midnight rows. A page past the end says so and offers "Go to last page". A malformed or unknown record id shows "That … does not exist." The phone detail header keeps the invoice number on one line. The dispute target reads "Invoice INV-… — ₹…". |
| `d83d1d8` | D-26, D-34, D-35 | How payments count towards promises — see below. `POST /api/promises/recompute?apply=false\|true` (admin) previews or applies the rule to existing promises. The Promises tab on Payment Details lists only the promises that payment is linked to (new `paymentId` column/param) and no longer offers "Raise promise". |
| `bf1613f` | D-68 | Notification title and message are shortened to fit their columns (200/1,000, ending "…"), so a dispute reason of up to 2,000 characters saves; the dispute keeps the full text. |

How payments count towards promises now (`PaymentPromiseService.shareOut`), all of a customer's
live promises being evaluated together:

- Each active payment is shared out once, oldest payment first. Money a payment put on an invoice goes
  to the promises covering that invoice; what is left counts towards general promises.
- Promises the payment is still in time for come first, then earliest promised date, then id. Late money
  can't un-break a promise, so it is not taken from one that can still be kept.
- No promise takes more than it promised, and money paid before a promise was made doesn't count for it.
- A promise whose invoices are all settled is KEPT even if another promise on the same invoice took
  the money. If they were settled only after its date, it is still BROKEN, whoever the money counted for.
- Cancelling a promise frees its share for the others.
- Ticking a promise while recording a payment pays that promise's invoices first. A ticked promise
  that the chosen invoices can't serve is refused with 400.
- `recomputeAll(apply)` records each status change in the audit history ("Recomputed: each payment now
  counts once across promises") and sends no broken-promise notifications. With `apply=false` it
  rolls back.

Verification: `scripts/verify-medium-fixes/api-group1.js`…`api-group5.js` (M1-01…M5-07, all pass
against 8083) and `scripts/verify-medium-fixes/ui/` (W-01…W-18 in `ui-results.json`: 17 pass. W-12
failed at 1366 px, was fixed in `bf1613f`, and passes in the re-check `w12r.js`). Screenshots are on
disk, not committed. The browser run also found D-68…D-72, now logged in `defects.md`.

## Low-severity fixes (16 Sep)

Committed on `main`, not pushed.

| Commit | Defects | What changed |
|---|---|---|
| `9b6a2a2` | D-40…D-48, D-53 | API: a sort direction other than asc/desc is a 400 and a page past `Integer.MAX_VALUE` is an empty page, not a 500. The users, roles and products exports follow the requested sort. One password rule (6 characters) for user create/update, customer logins and change-password (`common/Passwords`). Deleting an unknown role is a 404 (came with D-27). The automatic primary-POC promotion and demotion are audited as `POC_PRIMARY_CHANGED`. A bulk ADD_POC that could work for no row is one 400 and missing `POC_ASSIGN` is a 403, while "already holds that seat" stays a skipped row (`PocService.AlreadyAssignedException`). A deactivated product cannot go on a new invoice line. An unknown invoiceId on a payment is a 404 before any money moves. The dispute notification links to `/disputes/{id}`. |
| `89d151b`, `358e30a` | D-49…D-52, D-54…D-67, D-69…D-71 | UI: no "Raise promise" on a cancelled invoice; reference filter chips show the name that was picked; the "POC missing" badge wraps under the name; typing clears "Name is required"; a missing dispute reads as a sentence; the bell badge refreshes after bulk Mark read (`onBulkDone`); summary tiles are keyed on the filters alone, so paging no longer refetches them; remembered page sizes are cleared on sign-out; a viewer sees plain POC chips; History links are their own accessibility nodes; the Raise promise dialog fits a phone; on a phone the summary tiles scroll with the rows and the pager keeps only the page and arrows; nothing in the sidebar is highlighted on pages that are not in it; one date-time format; the detail top pane has a visible scrollbar; the dispute dropdown no longer runs under its arrow; "Proposed change" instead of "(JSON)"; staff see "All payments"; past the end the pager says "N pages"; signing out no longer fetches a schema without a token; and "That page does not exist." renders inside the app shell (`AppShell.path`, since `GoRouterState.of` throws in the router's error builder). |

Verification: `scripts/verify-low-fixes/api-low.js` (L-01…L-09, all pass against 8083) and
`scripts/verify-low-fixes/ui-low.js` (L-UI-01…L-UI-04 pass in a real browser, plus screenshots at
1366 and 400 of the layout-only fixes, in `shots/`, not committed). The high and medium API checks
were re-run against the same build and all still pass.

## Deploying to the user's data

None of the 16 Sep work is on 8082/8081 yet — group 5 (`d83d1d8`, `bf1613f`) or the low-severity
fixes (`9b6a2a2`, `89d151b`, `358e30a`). Deploy them together; only group 5 needs the extra step
below. A dry run on a copy of the user's data
(DB `geneinvoice_preview`, made with `pg_dump | psql` inside the `gene-invoice-db` container) found
3 of 34 promises would change. The user saw these and approved on 16 Sep:

| Promise | Customer | Status | Fulfilled |
|---|---|---|---|
| #22 | zz-bulk-cust | PARTIALLY_KEPT → OPEN | 100 → 0 (payment #29 now counts only for #21) |
| #23 | zz-promise-cust | BROKEN (unchanged) | 530 → 0 (the late money counts for #5, still in time) |
| #26 | zz-promise-cust | BROKEN (unchanged) | 1,000 → 500 (capped at the promise) |

Steps: redeploy the backend and web app as under "Running environments". Then, as admin, run
`POST http://localhost:8082/api/promises/recompute` (it previews by default), check that it
still shows the rows above, and only then run it with `?apply=true`. Drop `geneinvoice_preview`
afterwards (`docker exec gene-invoice-db psql -U geneinvoice -d postgres -c "DROP DATABASE geneinvoice_preview"`).

## What the first 15 Sep session changed (now in `87c502c`)

User-reported bugs, fixed:

1. **"New …" buttons overlapped the pagination bar** — floating buttons removed from the six list
   screens; `DataTableScaffold` gained an `actions` slot at the end of the filter bar, with a
   local override of the app's full-width `FilledButton` theme.
2. **Removing a POC showed a blank page, no API call** — eight dialogs popped the ShellRoute's
   navigator through the outer widget context; all now pop through their own `dialogContext`.
3. **History at customer and invoice level** — new audit events (`INVOICE_CREATED`,
   `PAYMENT_RECORDED`, per-invoice `PAYMENT_APPLIED` / `PAYMENT_REVERSED`, `DISPUTE_OPENED`,
   `DISPUTE_DENIED`); new `AuditTimelineService`; `GET /api/audit?...&includeRelated=true` merges a
   customer's invoices/payments/promises/disputes (an invoice's payments/promises/disputes); events
   older than the audit trail are derived from the records; customer logins get no POC events,
   keys or staff names. History panel UI rewritten (type chips, record links, show-more paging).
   Follow-ups from an adversarial review: audit reason truncated to the 500-char column, derived
   events use original figures, detail routes keyed by record id (fixes stale forms saving onto
   another record), `DetailScaffold` follows `?tab=` changes, dispute dialog refreshes History.
4. **No filters by default** (user decision) — POC "my records" chips no longer pre-applied for any
   role; design notes §2, §7, §10 updated.
5. **"api 403"** — an expired session got 403 on every call and the app never signed out. Backend
   now answers missing/expired/invalid tokens with **401** (403 stays for missing privilege);
   frontend signs out on 401.
6. **Invoice Payment Promise tab 400 "Unknown column: invoiceId"** — promises table gained an
   `invoiceId` column (EXISTS over the promise–invoice link table; eq/neq/in/isEmpty/isNotEmpty).

Tests added: `AuditTimelineTest` (9), `AuthenticationStatusTest` (3), `PromiseInvoiceFilterTest` (3).

Files touched by this session:

- Backend: `audit/AuditController.java`, `audit/AuditService.java`, `audit/AuditTimelineService.java` (new),
  `audit/AuditLogRepository.java`, `auth/CurrentUser.java`, `config/SecurityConfig.java`,
  `common/query/TableSchemas.java`, `dispute/DisputeService.java`, `dispute/DisputeRepository.java`,
  `invoice/InvoiceService.java`, `payment/PaymentService.java`, `payment/PaymentAllocationRepository.java` (new);
  tests `audit/AuditTimelineTest.java`, `auth/AuthenticationStatusTest.java`, `promise/PromiseInvoiceFilterTest.java` (new).
- Frontend: `core/api/api_client.dart`, `core/router.dart`, `core/table/data_table_scaffold.dart`,
  `core/table/route_query.dart`, `features/audit/audit_history_panel.dart`,
  `features/customers/{customer_detail_screen,customers_screen}.dart`,
  `features/invoices/{invoice_detail_screen,invoices_screen}.dart`,
  `features/payments/{payment_detail_screen,payments_screen}.dart`, `features/products/products_screen.dart`,
  `features/users/{users_screen,roles_screen}.dart`, `features/poc/customer_poc_editor.dart`,
  `features/promises/{promises_tab,promises_screen}.dart`, `features/disputes/dispute_create_dialog.dart`,
  `shared/widgets/detail_scaffold.dart`.
- Docs: `docs/implementation/poc-payment-promise-and-tables.md`; this folder.

## Decisions the user made

- **Lists open with no filters for every role** (15 Sep). The server-enforced scope for a role without
  `SCOPE_OVERRIDE` (seeded `SALES_POC`) stays and shows as a locked chip.
- **A cold deep link landing on the dashboard is accepted** — leave it.
- Medium batch (16 Sep):
  - **D-15:** a record outside a Sales POC's book is a 404, and its seats can't be changed.
  - **D-14:** unreachable ids are reported as skipped.
  - **D-30:** amounts with more than 2 decimals are refused everywhere, including promise amounts
    and unit prices.
  - **D-27:** emails and role names are unique ignoring case.
  - **D-36:** a promise falls back to an active Collection POC.
  - **D-32:** a unit price of 0 is allowed; quantity must be ≥ 1.
  - **D-34:** money on an invoice is shared first with the promises covering that invoice, and
    each promise is capped at what it promised. Two promises on a settled invoice both stay KEPT.
    I (Claude) added the refinement that promises still in time come first, and the user was told.
  - **Group 5 on the user's data:** preview the status changes first, then apply without
    notifications.

## Running environments

| | User's deployment | Regression test environment |
|---|---|---|
| Backend | `:8082` — `java -jar backend/target/gene-invoice-backend-0.0.1-SNAPSHOT.jar`, profile `dev`, Postgres `geneinvoice` at `localhost:5433` (container `gene-invoice-db`) | `:8083` — a copy of the jar in the session scratchpad, Postgres DB `geneinvoice_rt` |
| Web | `:8081` — `python3 -m http.server` serving `frontend/build/web` (built with `API_BASE_URL=http://localhost:8082`) | `:8084` — a separate web build in the scratchpad pointing at 8083 |
| Logins | `admin/admin123`, `cashier/cashier123` | same, plus users created by the run (password `Passw0rd!`) |

Port 8080 is taken by another container (`dood-srv`), which is why the app runs on 8082.
There is no local `psql`; run it inside the container (`docker exec gene-invoice-db psql …`).

Redeploy the user's backend after a change:

```bash
cd backend && mvn -q -B verify                 # runs tests and rebuilds the jar
kill $(lsof -nP -iTCP:8082 -sTCP:LISTEN -t)    # rebuilding under a running JVM can crash it — restart
SPRING_PROFILES_ACTIVE=dev DB_URL=jdbc:postgresql://localhost:5433/geneinvoice DB_USER=geneinvoice \
  DB_PASSWORD=geneinvoice nohup java -jar target/gene-invoice-backend-0.0.1-SNAPSHOT.jar \
  --server.port=8082 > /tmp/gene-invoice-8082.log 2>&1 &
cd ../frontend && flutter build web --dart-define=API_BASE_URL=http://localhost:8082   # 8081 serves it from disk
```

Remove the test environment when done (the scratchpad copy disappears with the old session anyway):

```bash
kill $(lsof -nP -iTCP:8083 -sTCP:LISTEN -t) $(lsof -nP -iTCP:8084 -sTCP:LISTEN -t)
docker exec gene-invoice-db psql -U geneinvoice -d postgres -c "DROP DATABASE geneinvoice_rt"
```

Recreate it for a re-run: `CREATE DATABASE geneinvoice_rt`; copy the jar out of `target/` and run it
on 8083 with `DB_URL=jdbc:postgresql://localhost:5433/geneinvoice_rt`; build the web app with
`--dart-define=API_BASE_URL=http://localhost:8083 --output <dir>` and serve it on 8084.

## Re-running the regression scripts

```bash
cd docs/regression/2026-09-15/scripts && npm install      # playwright-core; drives the installed Google Chrome
```

- `lib.js` targets `:8083` / `:8084`; change `API` / `WEB` at its top to test elsewhere.
- The area scripts load the helper as `require('../lib.js')` (already rewritten from the old scratchpad
  path). Some scripts also write or read files (screenshots, `state.json`) relative to their own folder;
  saved login tokens were redacted, so any script that reuses `state.json` must log in again first.
- Scripts create their own data with unique prefixes; follow `scripts/TESTER-RULES.md`.
- After re-testing, update the **Status** column in `defects.md`.

## Things learned the hard way

- **Flutter web (CanvasKit) automation:** click `flt-semantics-placeholder` to switch on the
  accessibility tree, then `flt-semantics` nodes carry role/label; a list tile or history row is one
  merged node, so links inside it need a real mouse click; the login fields have no labels.
- **Dialogs under a `ShellRoute`** must pop with the dialog's own context, never the screen's.
- **The app theme's `FilledButton` is full-width** — any FilledButton placed in a `Row` needs a
  bounded width or a local theme override.
- **Filter wire format** is always `field:operator:value`; valueless operators keep the trailing
  colon (`invoiceId:isEmpty:`).
- Backend tests run on H2; the app runs on Postgres — date bounds and SQL errors differ.
- The dev JWT secret is the default in `application.yml`; tokens last 24 h.
- A navigation refused in `GoRoute.onExit` still leaves a duplicate browser-history entry
  (go_router 14.8.1), which swallows the next Back press. Ask before `go()` — use `goGuarded()`.
- `setState(() => _future = load())` returns the Future and trips a debug assertion, so the state
  never updates; use a block body.
- Parallel shell commands share one working directory: use absolute paths (or `npm --prefix`).
- 8082 runs straight from `backend/target/`: never `mvn package` there while it runs. Build in a
  copy of `backend/` and swap the jar, or stop 8082 first. Build the test web app with
  `flutter build web -o <dir>` so `frontend/build/web` (served by 8081) is left alone.
- The session's automatic permission check treats restarting 8082 as a production deploy and may
  refuse it. Hand the user the commands rather than working around it.
- List endpoints accept only `size` 10, 20 or 50; anything else is a 400.

## Suggested next steps

1. Deploy to 8082/8081 and run the promise recompute (see "Deploying to the user's data"). Nothing
   from 16 Sep is on the user's own instance yet.
2. D-72, the one defect still open, together with a screen-reader pass over the tables and the
   History panel (D-59 changed that too).
3. After backend changes, re-run `scripts/verify-high-fixes/api.js`,
   `scripts/verify-medium-fixes/api-group*.js` and `scripts/verify-low-fixes/api-low.js` (they
   create their own data on 8083); `scripts/verify-low-fixes/ui-low.js` needs 8084 rebuilt first.
4. Nothing is pushed; ask the user before pushing.
