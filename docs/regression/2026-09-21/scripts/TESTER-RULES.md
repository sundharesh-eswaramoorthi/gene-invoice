# Regression run 2026-09-21 — rules for testers

Targeted regression on the work delivered by
[`docs/requirements/invoice-due-dates-and-documents.md`](../../../requirements/invoice-due-dates-and-documents.md):
**invoice due dates, ageing by days past due, and document attachments** — plus the blast radius
around them (invoices, customers, payments, dashboard).

## Environment (already running — do NOT restart, rebuild or redeploy anything)

| | |
|---|---|
| Backend API | `http://localhost:8083` — Spring Boot, Postgres DB `geneinvoice_rt` (fresh, empty at run start) |
| Web app | `http://localhost:8084` — Flutter web build pointing at 8083 |
| Document storage | Local filesystem under `/Users/srinivasans/gene-invoice-documents-rt` |
| Seeded logins | `admin`/`admin123` (ADMIN), `cashier`/`cashier123` (CASHIER) |
| Seeded roles | ADMIN, CASHIER, VIEWER, CUSTOMER, SALES_POC, CUSTOMER_SUCCESS_POC, COLLECTION_POC |

**The user's own deployment is on 8081/8082 — never touch it, never send it a request.**
Never run `mvn`, `flutter build`, or kill any process. Never edit repository files outside your own
folder under `docs/regression/2026-09-21/`.

## What to test against

Expected behaviour comes from the code and the two docs, not from your assumptions:

- Requirements + acceptance criteria: `docs/requirements/invoice-due-dates-and-documents.md`
- Design notes: `docs/implementation/invoice-due-dates-and-documents.md`
- Source: `backend/src/main/java/com/geneinvoice/**`, `frontend/lib/**`

Every acceptance criterion in the PRD carries an ID (`AC-A1`, `AC-B8`, `AC-C13`, …). **Cite the AC ID
in every case you write that maps to one.** Where behaviour contradicts the PRD, the PRD wins unless
the implementation doc explicitly records a decision to differ.

## Helper library

`const rt = require('/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1/docs/regression/2026-09-21/scripts/lib.js')`

- API: `rt.api(method, path, {token, body})` → `{status, json, text, headers}`; `rt.login`,
  `rt.adminToken()`, `rt.cashierToken()`, `rt.createStaff(token, role, prefix)`,
  `rt.createCustomer(token, prefix)`, `rt.mintToken(username, ttlSeconds)`, `rt.uniq(prefix)`.
- UI: `const app = await rt.openApp({token, width, height})` → `{page, apiErrors, pageErrors, close}`;
  `rt.go(page, '#/invoices')`, `rt.semantics(page)`, `rt.tap(page, 'Save changes')`,
  `rt.clickAt(page, x, y)`, `rt.typeText(page, 'text', {clear:true})`, `rt.shot(page, dir, name)`.

Driving this Flutter app (CanvasKit): the page is a canvas, so work through the accessibility tree.
Buttons, tabs and dialog buttons respond to `rt.tap`. A whole list row is one merged node — to hit a
link inside it, screenshot, read the pixel position, then `rt.clickAt`. Text fields need a click, then
`rt.typeText`.

## Isolation between testers

Several testers run at once against one database.

- Create your own data with unique names: `rt.uniq('<area>')`, `rt.createCustomer(token, '<area>')`.
- Never modify the seeded `admin`/`cashier` users or the seeded roles — create your own.
- Dashboard totals, ageing buckets and list tiles are **shared**. When asserting on them, either
  filter to your own customer, or measure a before/after delta. Re-check once before calling a
  mismatch a failure — another tester may have written between your two reads.
- Uploaded documents accumulate in shared storage; scope assertions to your own records.

## Reporting

Write **one JSON file** to `docs/regression/2026-09-21/data/area-<your-area>.json`:

```json
{
  "area": "A — due dates",
  "cases": [
    {
      "id": "A-01",
      "feature": "Invoice due date",
      "kind": "API",
      "ac": "AC-A2",
      "title": "Due date defaults from the customer's payment terms",
      "status": "PASS",
      "severity": "",
      "steps": "POST /api/customers {paymentTerm:NET_45}; POST /api/invoices for that customer",
      "expected": "dueDate = invoiceDate + 45 days, paymentTerm NET_45",
      "actual": "dueDate = invoiceDate + 45 days",
      "evidence": "",
      "codeRef": "backend/.../InvoiceService.java:120"
    }
  ]
}
```

- `status`: `PASS` | `FAIL` | `BLOCKED` | `NOT_TESTED`
- `severity` on a FAIL: `high` (data loss, wrong money, auth bypass, feature unusable) |
  `medium` (wrong behaviour with a workaround) | `low` (cosmetic, wording, minor UX)
- `actual` on a FAIL must be **what you observed**, precisely enough for someone else to reproduce.
- `codeRef` — `file:line` of the code you believe is responsible, when you can find it.
- Screenshots for UI failures: `rt.shot(page, '<run>/report/shots', '<id>-1')`.

**Report only — do not fix anything.** If you find a defect, record it and move on. Do not edit
`backend/` or `frontend/` source. A defect you cannot reproduce on a second attempt is `NOT_TESTED`
with a note, not a `FAIL`.

## Known environment conditions (not defects — do not re-report)

- The user's 8081/8082 deployment has a stale `emails` table that makes `/api/emails` return 500.
  That is a **migration-path defect already logged as D-01** for this run. The fresh `geneinvoice_rt`
  database on 8083 does not have it; if you see email 500s on **8083**, that IS a new defect.
- The database starts empty. There is no demo data — create whatever you need.
