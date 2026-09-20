# PRD — Invoice Due Dates, True Ageing, and Document Attachments

**Product:** Gene Invoice (Spring Boot 3.3 backend + Flutter frontend)
**Date:** 2026-09-20
**Status:** Ready for implementation
**Doc type:** Lean PRD — goals, user stories, acceptance criteria. **API contracts, entity/DDL design, storage wiring and Flutter widget structure are deliberately left to the implementing session.**

> **How to use this doc:** hand this file to a fresh session with "implement this". The session should first read the current code (`backend/src/main/java/com/geneinvoice/**`, `frontend/lib/**`), then propose its own schema, endpoint and storage design that satisfies the acceptance criteria below.

---

## 1. Context — where the app is today

- **Invoices have no due date.** `Invoice` carries `invoiceNumber`, `customer`, `invoiceDate`, `items`, `total`, `paidAmount`, `status` (`UNPAID` / `PARTIALLY_PAID` / `FULLY_PAID` / `CANCELLED`), `notes`, `salesPoc`, `createdAt`. There is no due date, no payment terms, and no notion of "overdue" anywhere in the domain.
- **The dashboard's ageing chart therefore measures the wrong thing.** `GET /api/dashboard/outstanding-by-age` buckets outstanding balance into `0–30 / 31–60 / 61–90 / Over 90 days` by **days since the invoice date** (`DashboardService.AGES`, and the `aged()` predicate). An invoice issued 45 days ago on 60-day terms is not late, but today it sits in the "31–60 days" bucket exactly like one that is 15 days past due. Collections cannot act on the chart as it stands.
- **`Customer` has no payment terms** — only `name`, `phone`, `email`, `address`, `creditBalance`, `createdAt`.
- **Payment Promises already exist** (`OPEN → KEPT / PARTIALLY_KEPT / BROKEN`, auto-flipping when a promised date passes). Promises are the app's only existing date-based collections signal; due dates must line up with them, not compete.
- **There is no file upload anywhere in the system.** No `MultipartFile`, no multipart configuration in `application.yml`, no object-store or filesystem storage client, no `file_picker` in the Flutter app. Emails have no attachments. Documents starts from zero.
- **There is a strong precedent for per-record tabs.** Detail screens compose `DetailTab`s with URL-synced slugs (`invoice_detail_screen.dart`), and `emailDetailTab(...)` is an existing tab contributed by another feature — the exact shape a Documents tab should take.
- **There is a strong precedent for polymorphic per-record data.** `Email` hangs off any record via `entityType` (`EmailEntityType`: CUSTOMER, INVOICE, PRODUCT, PAYMENT, PROMISE, DISPUTE, USER, ROLE) + `entityId` + `entityLabel`. Documents should follow this shape rather than invent a new one.
- **There is a strong precedent for pluggable infrastructure.** Mail delivery ships behind `MAIL_TRANSPORT`, defaulting to `none`, with the real implementation in `email/transport` and `email/mailservice`. Document storage should be pluggable the same way.
- **Access control:** one `Role` per `User`, privilege-guarded endpoints via `@PreAuthorize`, and a `ScopeResolver` that narrows every list to a POC's book or a self-service customer's own records.
- **Schema management:** all profiles run `ddl-auto: update`. There is no Flyway or Liquibase.

---

## 2. Goals

1. Make **"when is this money actually due"** a first-class fact on every invoice, derived from customer payment terms so nobody types a date by hand in the common case.
2. Re-point the dashboard's ageing chart at **days past due**, so every rupee in a bucket is genuinely late.
3. Make **overdue** visible and filterable everywhere an invoice appears — list, detail, dashboard.
4. Let users **attach documents** (PO, signed delivery note, cheque image, bank advice, dispute evidence) to a customer, invoice or payment, and find them again from that record.
5. Ship document storage in a form that works on a laptop with no cloud account, and swaps to an object store without touching feature code.

## 3. Non-goals (explicitly out of scope for this round)

- Tax, discounts, multi-currency, PDF invoice generation, recurring invoices.
- Dunning automation — reminder ladders, escalation, auto-emailed statements. Due dates are the prerequisite for that work; this round stops at making them exist and be visible.
- Attaching documents to emails as outbound attachments, or saving inbound email attachments as documents. (See Open Questions — this is the obvious next step and the design should not preclude it.)
- Document versioning, check-in/check-out, e-signature, OCR, full-text search inside files.
- Virus scanning integration (but see AC-C14 — the hook for it is in scope).
- Multi-tenancy, company branding, any change to the JWT/auth mechanism.
- Changing the credit/allocation algorithm or the dispute workflow.

## 4. Locked decisions

These were decided up front; do not re-litigate them during implementation.

| # | Decision |
|---|---|
| D1 | **Payment terms live on the customer; the due date lives on the invoice.** Terms are a small fixed set (Due on receipt, Net 15, Net 30, Net 45, Net 60, Net 90, plus Custom). An invoice takes its customer's terms at creation time and stores the **resulting date**, not a pointer to the terms. Changing a customer's terms later must never silently move the due date of an invoice already issued. |
| D2 | **Every invoice has a non-null due date, including existing rows.** Existing invoices are backfilled once to `invoiceDate + default terms`. Ageing is a money figure on a chart; a nullable due date with a read-time fallback would quietly mix two definitions of "late" in one bucket. The backfill's default term is configurable and recorded in the audit log. |
| D3 | **Overdue is derived, never stored.** No new `InvoiceStatus` value. An invoice is overdue when `dueDate < today` **and** `total - paidAmount > 0` **and** status is not `CANCELLED`. A persisted status would be stale the moment the clock rolls past midnight without a write. |
| D4 | **The ageing chart moves to days past due and gains a "Not yet due" bucket.** Buckets become `Not yet due`, `1–30 days`, `31–60 days`, `61–90 days`, `Over 90 days`. The chart's title and axis must say "overdue", so nobody reads the new numbers with the old meaning. |
| D5 | **Documents attach polymorphically**, following the `Email` pattern — `entityType` + `entityId` + `entityLabel` — with this round enabling **CUSTOMER, INVOICE and PAYMENT** only. Adding the remaining entity types later must be a one-line enum change, not a redesign. |
| D6 | **Storage is behind an interface with a local-filesystem default**, mirroring `MAIL_TRANSPORT`. Bytes never live in the database. The default implementation writes under a configured root directory; an object-store implementation must be addable without touching controllers, services or the Flutter app. |
| D7 | **Every document has a visibility: `INTERNAL` or `SHARED`.** Self-service customers see only `SHARED` documents on their own records. `INTERNAL` is the default on upload — a document becomes customer-visible only by an explicit act. |
| D8 | **Downloads are authorized on every request.** No public, unguessable or pre-signed URL that outlives the permission check. A download is an authenticated, privilege-checked, scope-checked endpoint that streams the file. |

---

## 5. Feature A — Payment terms and invoice due dates

### A.1 Shape

- **Customer** gains payment terms, used as the default for that customer's new invoices.
- **Invoice** gains `dueDate` (a date, not an instant — "due on the 30th" is a calendar fact, not a moment) and a record of the terms it was created under, for display and for explaining the date to a user.
- A **system-wide default term** applies to customers that have none, and is what the D2 backfill uses.

### A.2 Where the due date comes from

| Situation | Due date |
|---|---|
| New invoice, customer has terms | `invoiceDate + customer terms`, shown in the form and editable before save |
| New invoice, customer has no terms | `invoiceDate + system default term` |
| User picks Custom on the invoice form | Whatever date they choose, subject to AC-A5 |
| Existing invoice at migration time | `invoiceDate + system default term` (D2) |
| Customer's terms are edited afterwards | Unchanged — existing invoices keep their date (D1) |

### A.3 User stories

- **US-A1** As an **admin**, I can set a customer's payment terms on the customer detail screen, so their invoices default to the right date without anyone remembering the arrangement.
- **US-A2** As a **cashier creating an invoice**, I see the due date computed the moment I pick the customer, with the terms named beside it ("Net 30 — due 20 Oct 2026"), so I can trust it without doing the arithmetic.
- **US-A3** As a **cashier**, I can override the due date on an individual invoice when the customer negotiated something special, and the invoice records that it was a custom date.
- **US-A4** As **anyone viewing an invoice**, I see the due date on the detail screen and in the invoice list, with an unmistakable **Overdue** badge and the number of days late when it is past due and unpaid.
- **US-A5** As a **collections POC**, I can filter and sort the invoice list by due date and by overdue-only, so my day starts with the invoices that are actually late.
- **US-A6** As a **collections POC**, I can see on a customer's detail screen how much of their outstanding balance is overdue, not just what it totals.

### A.4 Acceptance criteria

- **AC-A1** Every invoice, new or existing, has a non-null due date after this change ships. No code path can create an invoice without one.
- **AC-A2** The due date defaults from the customer's terms at creation time and is stored as a date. Editing the customer's terms afterwards leaves every existing invoice's due date untouched (D1) — covered by a test that changes terms and asserts the old invoice is unmoved.
- **AC-A3** Due date is exposed on every invoice read path that already exposes `invoiceDate`: detail, list, CSV export, and any DTO the Flutter app binds to.
- **AC-A4** Overdue is computed per D3. A test must prove an invoice flips to overdue purely by the date advancing, with no write to the row, and that a `CANCELLED` or fully-paid invoice never reads as overdue.
- **AC-A5** A due date **earlier than the invoice date** is rejected with the same validation-error shape the app already returns. A far-future date is accepted (some contracts really are Net 365) but the form warns beyond a configurable horizon.
- **AC-A6** The invoice list supports sorting by due date and a filter for "overdue only", both **server-side**, honouring the existing scope rules — a self-service customer filtering by overdue sees only their own overdue invoices.
- **AC-A7** The summary tiles on the invoice list gain an **overdue amount** and **overdue count**, computed server-side over the full filtered set, consistent with the existing tile behaviour.
- **AC-A8** Payment terms changes and due-date overrides are written to the audit log through the existing audit service, and show in the History tab.
- **AC-A9** Timezone and boundary behaviour is defined once and tested: an invoice due today is **not** overdue; it becomes overdue at the start of the next day, in the same zone the rest of the app already uses to decide "today".
- **AC-A10** The Payment Promise feature continues to work unchanged, and a promise whose promised date is later than the invoice due date is still valid — promises are a negotiated exception to the due date, not a contradiction of it.

---

## 6. Feature B — Ageing by days past due

### B.1 What changes

`GET /api/dashboard/outstanding-by-age` keeps its place on the dashboard and its scope/coverage behaviour, but changes its measure from *days since invoice date* to *days past due date*, and gains the `Not yet due` bucket from D4.

### B.2 User stories

- **US-B1** As a **collections POC**, the ageing chart tells me how much money is genuinely late and by how long, so I can work the worst bucket first.
- **US-B2** As a **manager**, I can see at a glance how much of the receivable is not yet due versus overdue, because those two numbers mean completely different things.
- **US-B3** As **anyone on the dashboard**, clicking a bucket takes me to the invoice list filtered to exactly the invoices behind that number.

### B.3 Acceptance criteria

- **AC-B1** Buckets are `Not yet due`, `1–30`, `31–60`, `61–90`, `Over 90` days past due. Bucket boundaries are contiguous and non-overlapping, and an invoice due exactly today lands in `Not yet due` (consistent with AC-A9).
- **AC-B2** Every bucket's amount is the **outstanding balance** (`total - paidAmount`), never the invoice total, and cancelled and fully-paid invoices are excluded — matching what the endpoint does today.
- **AC-B3** The sum of all buckets equals total outstanding as reported elsewhere on the dashboard. A test asserts this reconciliation, including the case where every invoice is not yet due.
- **AC-B4** Existing scope and coverage behaviour is preserved exactly: a POC sees their book, a self-service customer sees only their own, and the `Coverage` value returned is unchanged in meaning.
- **AC-B5** Bucket labels and the chart's title come from the backend or are changed in the frontend in the same commit — the UI must never label days-past-due data with the old "days since invoice" wording.
- **AC-B6** Clicking a bucket deep-links to the invoice list with an equivalent server-side filter, and the row count there matches the bucket's count (**US-B3**). If the filter cannot express `Not yet due`, that bucket is not clickable rather than wrong.
- **AC-B7** The aggregate stays a single database query per the existing `Aggregates` approach — no per-invoice computation in Java, no N+1.
- **AC-B8** Tests cover each bucket boundary at its exact edge (due today, 1 day late, 30/31, 60/61, 90/91).

---

## 7. Feature C — Documents

### C.1 Shape

A **Document** is an uploaded file attached to one record: CUSTOMER, INVOICE or PAYMENT (D5). It carries at minimum the original filename, content type, size, checksum, visibility (D7), who uploaded it and when, an optional short description, and wherever the bytes ended up in storage.

### C.2 The tab

A **Documents** tab appears on the customer, invoice and payment detail screens, built with the existing `DetailTab` + URL-slug pattern and contributed the way `emailDetailTab(...)` is. It lists the record's documents with name, type, size, who uploaded it, when, and visibility; it supports upload, download, edit description/visibility, and delete.

### C.3 User stories

- **US-C1** As a **cashier**, I can attach the customer's PO to an invoice by dragging it onto the Documents tab, so the paperwork lives with the invoice instead of in my mailbox.
- **US-C2** As a **cashier recording a payment**, I can attach the cheque image or bank advice as proof of what was collected.
- **US-C3** As an **admin**, I can attach a signed contract or trade licence to a customer.
- **US-C4** As **anyone with access to a record**, I can download any document on it that I am allowed to see, and see who uploaded it and when.
- **US-C5** As an **uploader or admin**, I can delete a document I attached by mistake, and the deletion is audited.
- **US-C6** As an **admin**, I can mark a document **Shared** so the customer can see it in their self-service view, and everything else stays internal by default.
- **US-C7** As a **self-service customer**, I see only the documents explicitly shared with me on my own records, and never anything internal.
- **US-C8** As a **user on a slow connection**, I see upload progress and a clear, specific error when a file is too large or of a disallowed type — before the bytes are wasted where possible.

### C.4 Acceptance criteria

**Behaviour**

- **AC-C1** A document can be uploaded to, listed on, downloaded from and deleted from a customer, an invoice and a payment. The record's Documents tab shows a count so users know there is something there without opening it.
- **AC-C2** Listing is paged and sorted newest-first, consistent with the app's other list endpoints.
- **AC-C3** Deleting is a **soft delete** — the row is retained for audit with who deleted it and when, and the file is no longer downloadable. Whether stored bytes are purged, and when, is an implementation decision that must be stated in the implementation doc.
- **AC-C4** Uploading, editing and deleting a document each write to the audit log via the existing audit service and appear in the record's History tab.
- **AC-C5** Deleting the parent record (where that is possible today) leaves no orphaned, still-downloadable documents.

**Limits and validation**

- **AC-C6** Maximum file size is configurable with a sane default, enforced **server-side**, and surfaced in the UI before upload. Exceeding it returns the app's standard validation error, not a container-level 500 or a dropped connection.
- **AC-C7** Allowed content types are an explicit allow-list (at minimum PDF, PNG, JPEG, and common office documents), configurable, and validated **from the file's content, not only its extension or the client-supplied type**.
- **AC-C8** The original filename is stored for display but **never used as a filesystem path**. Storage keys are generated server-side. Path traversal (`../`), absolute paths, null bytes and over-long names are tested.
- **AC-C9** Multipart handling is configured explicitly in `application.yml` (max file size, max request size, and the resolver's own limits) and agrees with AC-C6 rather than contradicting it.

**Security**

- **AC-C10** Download requires authentication, the `DOCUMENT_VIEW` privilege **and** the parent record's own view privilege, mirroring how email enforces `EMAIL_VIEW` plus the record's view privilege. Upload and delete require `DOCUMENT_MANAGE` plus the record's manage privilege.
- **AC-C11** Scope rules apply: a POC cannot reach documents on a record outside their book, and a self-service customer cannot reach another customer's documents. Tested with a direct request for a known-foreign document id, not just through the UI.
- **AC-C12** A self-service customer sees only `SHARED` documents (D7), can download those, and cannot upload, edit visibility or delete unless the Open Questions resolve otherwise.
- **AC-C13** Downloads are served with `Content-Disposition: attachment`, a safe content type, and headers that prevent the browser from rendering the file inline in the app's origin — an uploaded SVG or HTML file must not be able to execute script against the app.
- **AC-C14** The upload path has a single, documented place where a scanner would be called, even though no scanner ships in this round, so adding one later is a drop-in.

**Storage**

- **AC-C15** Storage is behind an interface with a local-filesystem implementation as the default (D6). The storage root is configurable; startup fails with a clear message naming the setting when it is missing or unwritable, the way missing mail settings already fail.
- **AC-C16** No file bytes are stored in the database.
- **AC-C17** The H2 demo profile works end-to-end with local storage and no extra setup, consistent with the project's zero-setup demo promise.
- **AC-C18** Storage failure mid-upload leaves no half-created document row, and a document row never points at bytes that are not there; the failure returns a real error to the user.

**Frontend**

- **AC-C19** The Documents tab uses the existing `DetailTab` pattern with a URL-synced slug, so a document view is linkable and browser-back behaves like every other tab.
- **AC-C20** Upload supports both a file picker and drag-and-drop on web, and shows progress. For a user without `DOCUMENT_MANAGE` the control is **absent**, not disabled — see AC-C22, which governs. *(Amended 2026-09-21: this criterion originally asked for the control to "disable itself with an explanation", which contradicted AC-C22's rule that no button appears that would predictably 403. AC-C22 won in the implementation; the contradiction is resolved here rather than left for the next reader. Regression defect D-05.)*
- **AC-C21** The tab renders correct empty, loading and error states, and a failed upload leaves a retryable message rather than a silently empty list.
- **AC-C22** Document actions respect privileges in the UI as well as the API — no visible button that will predictably 403.

---

## 8. Cross-cutting requirements

- **Permissions.** New privileges `DOCUMENT_VIEW` and `DOCUMENT_MANAGE` are added to `Privileges` and seeded idempotently, in the existing style. The implementing session must produce a role × capability matrix covering `ADMIN`, `CASHIER`, `VIEWER`, `CUSTOMER` and the three POC roles for both features. `CASHIER` must retain everything it can do today.
- **Auditing.** Payment-terms changes, due-date overrides, the D2 backfill, and every document upload/edit/delete flow through the existing audit service.
- **Notifications.** No new notifications are required in this round. If the session adds an "invoice overdue" notification, it must be scheduler-driven in the style of `PromiseSweepScheduler`, idempotent, and must not fire repeatedly for the same invoice.
- **Exports.** The existing CSV exports for invoices gain the due date and an overdue flag, guarded by `EXPORT_DATA` as they are today.
- **Field limits.** Any new string column's length is declared in `FieldLimits` and mirrored in `frontend/lib/core/field_limits.dart`, as existing fields are.
- **Backward compatibility.** Existing clients and the dispute, promise and email flows keep working. The invoice DTO gains fields rather than changing the shape of existing ones.
- **Testing.** Ships with tests. At minimum: the D1 terms-do-not-retroactively-move case, AC-A4's derived overdue including the date-advance case, every bucket boundary in AC-B8, the AC-B3 reconciliation, and for documents the AC-C8 traversal cases, AC-C11 cross-scope access and AC-C12 customer visibility. `mvn verify` and `flutter analyze` must pass.

## 9. Migration and existing data

- **Due dates (D2).** A one-time, idempotent backfill sets `dueDate = invoiceDate + default term` for every existing invoice. It must be safe to run against a populated production database, safe to re-run, and must log how many rows it touched. Running it must not change any invoice's `status` or `paidAmount`.
- **The chart changes meaning on deploy.** After the backfill, invoices previously in "0–30 days" may land in "Not yet due". This is correct, and is the reason AC-B5 requires the labels to change in the same release. Anyone tracking the old numbers should be told the definition changed.
- **Customer terms** start empty and fall back to the system default; there is no backfill of per-customer terms, and an empty value is a legitimate state meaning "use the default".
- **Schema.** Both features add columns and one table under `ddl-auto: update`. The implementing session should confirm whether `update` will apply the new non-null due-date column safely against existing rows, and stage it (add nullable → backfill → enforce) if not.

## 10. Open questions — resolve before or during implementation

1. **System default payment term.** Assumed **Net 30** for both new customers and the D2 backfill. Confirm.
2. **Who may override a due date?** Assumed anyone with `INVOICE_MANAGE`. Should it need its own privilege, given it directly moves a collections deadline?
3. **Overdue notifications.** Deferred above. Confirm they are genuinely out of scope for this round rather than a small addition worth folding in.
4. **Can a self-service customer upload documents?** Assumed **no** for this round (AC-C12) — but dispute evidence from the customer is an obvious use, and the dispute flow already lets them act. Confirm.
5. **Which document types beyond customer/invoice/payment?** D5 keeps the enum open. Disputes and promises are the likely next two; confirm they are deferred.
6. **Email ↔ documents.** Attaching an existing document to an outbound email, and saving an inbound attachment as a document, are both out of scope but should not be designed out. Confirm the direction.
7. **Maximum file size and allow-list.** Assumed **10 MB** and PDF/PNG/JPEG/DOCX/XLSX. Confirm both.
8. **Purging bytes on delete (AC-C3).** Retain indefinitely for audit, or purge after a retention window? Assumed retain.
9. **Business-day terms.** Do "Net 30" terms mean calendar days or business days, and should a due date landing on a weekend or holiday roll forward? Assumed **calendar days, no rolling**.

## 11. Suggested build order

1. **Payment terms + `dueDate` on the invoice** (Feature A, backend) with the D2 backfill — everything else depends on it.
2. **Derived overdue, list filter/sort and tiles** (AC-A4, A6, A7), then the frontend badge and form wiring.
3. **Re-point the ageing aggregate** to days past due with the new bucket (Feature B), including the label change and the deep link.
4. **Document storage interface + local filesystem implementation** (D6, AC-C15–C18) behind a feature-flagged, empty-UI backend — provable by tests before any UI exists.
5. **Document API** — upload, list, download, delete, with the full privilege and scope matrix (AC-C10–C12).
6. **Documents tab** on invoice first (the richest use case), then customer and payment once the pattern is settled.
