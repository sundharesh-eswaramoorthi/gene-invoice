# Regression test environment — rules for testers

## Environment (already running — do not restart, rebuild or redeploy anything)

- Backend API: http://localhost:8083 — Spring Boot, Postgres database `geneinvoice_rt` (fresh; isolated
  from the user's own deployment on 8081/8082, which you must not touch).
- Web app: http://localhost:8084 — Flutter web build pointing at 8083.
- Seeded logins: `admin` / `admin123` (ADMIN), `cashier` / `cashier123` (CASHIER).
  Seeded roles: ADMIN, CASHIER, VIEWER, CUSTOMER, SALES_POC, CUSTOMER_SUCCESS_POC, COLLECTION_POC.
- Source code (read it to know expected behaviour): /Users/srinivasans/Git/DooD_Test/gene-invoice-main
  (backend/src/main/java/com/geneinvoice/**, frontend/lib/**). Requirements:
  docs/requirements/poc-payment-promise-and-tables.md; design notes (privilege matrix, promise status
  algorithm, list API contract, columns, scoping, bulk actions): docs/implementation/poc-payment-promise-and-tables.md;
  original API overview: README.md.
- Never run `mvn package`/`mvn verify`/`flutter build`, never kill java or python processes, never
  edit files in the repository. Only write files under your own folder in this directory.

## Other testers run at the same time

- Create your own data with unique names: `rt.uniq('<area>')`, `rt.createStaff(token, 'SALES_POC', '<area>')`,
  `rt.createCustomer(token, '<area>')`. Products, invoices, payments, promises and disputes likewise.
- Never modify the seeded `admin`/`cashier` users or the seeded roles; create your own users and roles.
- Totals, tiles and counts are shared across testers. When you assert on them, filter to your own
  customers (e.g. `filter=customerId:eq:<id>`), or compare before/after deltas, and re-check once
  before calling a mismatch a failure.

## Helper library: rt/lib.js

`const rt = require('<this dir>/lib.js')` — see the file for details.
- API: `rt.api(method, path, {token, body})` → `{status, json, text}`; `rt.login`, `rt.adminToken()`,
  `rt.cashierToken()`, `rt.createStaff`, `rt.createCustomer`, `rt.mintToken(username, ttlSeconds)`.
- UI: `const app = await rt.openApp({token, width, height})` → `{page, apiErrors, pageErrors, close}`.
  `rt.go(page, '#/invoices')`, `rt.semantics(page)` (labelled nodes with centre x/y),
  `rt.tap(page, 'Save changes')` / `rt.tap(page, /Remove/)`, `rt.clickAt(page, x, y)`,
  `rt.typeText(page, 'text', {clear:true})`, `rt.shot(page, dir, name)`.

## Driving this Flutter app (CanvasKit) — what works

- The page is a canvas; `openApp`/`go` switch on the accessibility tree, which exposes
  `flt-semantics` nodes with `role` (button, tab, checkbox, …) and a label or text. Use `rt.semantics`
  to see what is on screen and where.
- Buttons, tabs, chips, dialog buttons: `rt.tap(page, <label>)`.
- A whole list card or history row is ONE merged node; links inside it (e.g. "Payment #32") need a
  real mouse click at their pixel position — take a screenshot, read coordinates, `rt.clickAt`.
- Text fields: click them (`rt.clickAt` on the field, found from a screenshot or from the semantics
  node), then `rt.typeText`. Dropdowns and pickers open dialogs whose options are tappable.
  Unlabelled fields (the login screen's Username/Password) are not in the semantics list — at
  1366×900 the login fields sit at about (683,440) and (683,492); confirm from a screenshot.
- Leads: `candidates.md` in this directory lists suspected issues from an earlier static reading
  (unverified; some fixed since). Test the ones in your area and report what you actually observe.
- Always take screenshots and READ them (the Read tool shows images) to confirm what the user would
  see — layout overlap, wrong values, blank screens, error text.
- Navigate inside the loaded app with `rt.go(page, '#/…')`. Tokens live in localStorage
  `flutter.gene_invoice_token`.

## Behaviour that is intended (do not report as bugs)

- Lists open with no filters for every role (product decision); a POC role without SCOPE_OVERRIDE
  (seeded SALES_POC) is limited server-side and sees a locked chip.
- A cold deep link (fresh browser session opening `/#/customers`) lands on the dashboard — accepted.
- CSV export opens a copyable dialog instead of downloading a file (documented departure).
- 403 when typing the URL of a screen the role's sidebar does not offer.

## Reporting

Return structured results: one entry per test case with an id, the feature, what you did
(steps), what should happen (expected, citing the requirement/AC or code when relevant), what
happened (actual), status PASS / FAIL / BLOCKED, severity for failures (critical / high / medium /
low), the evidence (HTTP status + body snippet, screenshot path) and, for failures, the likely
cause in code (file:line) when you can find it.
