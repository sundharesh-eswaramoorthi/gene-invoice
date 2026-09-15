# Handover — Gene Invoice, 15 Sep 2026

Start a new session with: *"Read `docs/regression/2026-09-15/HANDOVER.md` and continue."*

## Where things stand

- **The feature** — POC ownership, Payment Promises, and the list/detail table framework
  ([PRD](../../requirements/poc-payment-promise-and-tables.md),
  [design notes](../../implementation/poc-payment-promise-and-tables.md)) — is implemented in the
  working tree. **Nothing is committed**; everything sits uncommitted on `main` on top of
  `Initial commit`. Ask the user before committing.
- **A full regression run finished on 15 Sep 2026:** 503 cases across 10 areas (API and UI, all
  7 roles plus customer logins, desktop and phone width). 375 passed, 128 failed as reported.
  After every failure was reproduced by an independent verifier and duplicates were merged:
  **67 confirmed defects — 12 high, 27 medium, 28 low.** Verdict: **not ready for release.**
- Automated suites are green: backend `mvn test` 123/123, `flutter analyze` clean, `flutter test` 19/19.
- **Waiting on the user:** whether to start fixing the 12 high-severity defects (listed below).

## What is in this folder

| Path | What it is |
|---|---|
| `HANDOVER.md` | This file — start here |
| `report/index.html` | The full interactive report (open it in a browser; works offline). Published copy: https://claude.ai/artifact/JEa2ujkXpq8GX9xzxyvVth |
| `defects.md` | All 67 confirmed defects: severity, repro, expected/actual, root cause (file:line), suggested fix, screenshot. Has a **Status** column to track fixes (all OPEN) |
| `test-results.md` | Pass/fail per area, every case per area, verifier verdicts, recommendations, what was not tested |
| `test-cases.csv` | All 503 cases (area, id, feature, kind, title, status, severity, steps, expected, actual, evidence, codeRef) |
| `report/shots/` | Screenshot evidence for 36 defects |
| `data/regression-results.json` | Raw results: the report plus every tester's cases and every verifier's verdicts |
| `data/codebase-map.md` | Architecture map of the feature, per-feature file index and a symptom → where-to-look table (written 12 Sep, before this session's fixes) |
| `data/suspected-issues-static-review.md` | 62 issues suspected from reading the code before testing (leads, not facts; some since fixed or confirmed) |
| `scripts/` | The testers' and verifiers' Node scripts, the shared helper `lib.js`, and `TESTER-RULES.md` (how the run was organised and how to drive this Flutter app) |

## The 12 high-severity defects (fix these first)

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

Shared causes behind many medium defects (cheap, broad wins): missing 400 handlers in
`GlobalExceptionHandler` (D-13, D-27, D-29), the bulk `resolveIds` pattern that silently drops ids
(D-14 — `BulkActionTest` currently asserts the drop), table width that ignores the nav rail
(D-19, D-20), the `PocPicker` debounce (D-18).

## What this session changed (all uncommitted)

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

## Running environments

| | User's deployment | Regression test environment |
|---|---|---|
| Backend | `:8082` — `java -jar backend/target/gene-invoice-backend-0.0.1-SNAPSHOT.jar`, profile `dev`, Postgres `geneinvoice` at `localhost:5433` (container `gene-invoice-db`) | `:8083` — a copy of the jar in the session scratchpad, Postgres DB `geneinvoice_rt` |
| Web | `:8081` — `python3 -m http.server` serving `frontend/build/web` (built with `API_BASE_URL=http://localhost:8082`) | `:8084` — a separate web build in the scratchpad pointing at 8083 |
| Logins | `admin/admin123`, `cashier/cashier123` | same, plus users created by the run (password `Passw0rd!`) |

Port 8080 is taken by another container (`dood-srv`), which is why the app runs on 8082.

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

## Suggested next steps

1. Fix the 12 high-severity defects, each with a regression test (the security ones have none today).
2. Take the shared-cause medium fixes listed above.
3. Re-run the affected regression areas and update `defects.md`.
4. Ask the user whether to commit, and how to split the commits.
