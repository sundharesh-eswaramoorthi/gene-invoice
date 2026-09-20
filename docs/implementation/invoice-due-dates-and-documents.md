# Invoice due dates, true ageing and document attachments — design and contracts

**Date:** 2026-09-20
**Status:** Implementation contract for the PRD `docs/requirements/invoice-due-dates-and-documents.md`. The backend
and frontend are built against this document, so schema, API shapes, class names, file paths and messages below are
binding. Change them only by changing this file. The PRD's locked decisions (D1–D8) and acceptance criteria (AC-A*,
AC-B*, AC-C*) are the source of truth for behaviour; this document says how they are met.

## 1. Answers to the PRD's open questions (from the user, 2026-09-20)

| # | Question | Answer |
|---|---|---|
| 1 | System default term | **Net 30**, for new customers and the backfill (`app.invoice.default-payment-term`) |
| 2 | Who may override a due date | Anyone with `INVOICE_MANAGE`; every override is audited |
| 3 | Overdue notifications | **Out of scope** this round |
| 4 | Customer uploads | **Yes, on their own records** (their customer record, invoices, payments). Their uploads are `SHARED` automatically (an `INTERNAL` upload would be invisible to its own uploader); they may not change visibility or delete |
| 5 | Other document types | Deferred; the enum stays open (D5) |
| 6 | Email ↔ documents | Out of scope, not designed out: `Document` keeps storage keys and a stream API an email attachment could reuse |
| 7 | Limits | **10 MB**; PDF, PNG, JPEG, DOCX, XLSX, checked from content |
| 8 | Purging bytes | Retain after a soft delete; the file stops being downloadable |
| 9 | Business days | Calendar days, no weekend or holiday rolling |

## 2. Feature A — payment terms and due dates

### 2.1 Model

`PaymentTerm` (new, `com.geneinvoice.invoice.PaymentTerm`): `DUE_ON_RECEIPT(0, "Due on receipt")`, `NET_15(15,
"Net 15")`, `NET_30(30, "Net 30")`, `NET_45(45, "Net 45")`, `NET_60(60, "Net 60")`, `NET_90(90, "Net 90")`,
`CUSTOM(null, "Custom")`. `days()` is null only for `CUSTOM`; `due(LocalDate invoiceDate)` = `invoiceDate.plusDays(days)`;
`parse(String)` → 400 listing the values, as `PocType.parse` does.

| Table | Column | Notes |
|---|---|---|
| `customers` | `payment_term` varchar(20) null | null = "use the system default"; `CUSTOM` is not allowed here (400) |
| `invoices` | `due_date` date | **not null after the upgrade** (§2.5); always set by code |
| `invoices` | `payment_term` varchar(20) | the term the date came from; `CUSTOM` when it was overridden. The **column is nullable**: §2.5's migration enforces not-null on `due_date` only, and `ddl-auto: update` cannot add a NOT NULL column to a populated table. Non-nullness is held by `InvoiceService` and `Invoice.@PrePersist` instead |

`Invoice.isOverdue(LocalDate today)` (D3, derived, never stored): `dueDate.isBefore(today)` **and**
`total - paidAmount > 0` **and** `status != CANCELLED`. `daysOverdue(today)` = days between `dueDate` and `today`,
0 when not overdue. "Today" is `InvoiceDates.today()` — `LocalDate.now(ZoneOffset.UTC)`, the zone the promise
sweeper already judged a promised date by. The sweeper had no helper to reuse (it inlined the call twice), so
`InvoiceDates` **is** that one helper now and `PaymentPromiseService` and `DashboardController` both call it, which
is what keeps the ageing chart and the invoice list's overdue filter from disagreeing. An invoice due today is not
overdue and becomes overdue at the start of the next day (AC-A9).

### 2.2 Where the due date comes from

At **create**: the request's `dueDate` when given (term `CUSTOM`), else `customer.paymentTerm` (else the system
default) applied to `invoiceDate`. At **update**: an explicit `dueDate` sets term `CUSTOM`; an explicit
`paymentTerm` recomputes from `invoiceDate`; neither → unchanged. A named term and a `dueDate` in the **same**
request are refused rather than one of them being dropped: the term is the rule the date is worked out from, so a
request carrying both has two minds about the date (400 `fieldErrors.dueDate`
`"Pick Custom terms to set the due date yourself"`). Changing a customer's terms never touches an
existing invoice (D1, AC-A2). `GET /api/invoices/due-date-preview?customerId&invoiceDate` →
`{"dueDate","paymentTerm","paymentTermLabel","source":"CUSTOMER"|"DEFAULT"}` so the form can show
"Net 30 — due 20 Oct 2026" the moment a customer is picked (US-A2). The preview hands over a customer's
commercial terms, so it is scoped like the customer itself: an id outside the caller's book answers 404, exactly as
`GET /api/customers/{id}` does.

Validation (`ApiError.validation`): `dueDate` before `invoiceDate` → 400 `fieldErrors.dueDate`
`"The due date cannot be before the invoice date"`. Beyond `app.invoice.due-date-horizon-days` (365) the API
accepts it; the **form** warns ("That is more than a year away — is it right?"), counting from the invoice date the
preview gave, or from today when that call did not come back. `paymentTerm: CUSTOM` without a
`dueDate` → 400 `"Pick a due date for custom terms"`. A date outside 1900-01-01 … 9999-12-31 is refused the same
way rather than being left to the database, which answered a conflict naming neither the field nor the rule
(INV-4). A **cancelled** invoice refuses the whole edit — `400 "Cannot edit a cancelled invoice"`, the same answer
replacing its lines already gave — so a dead record's terms and deadline cannot be rewritten (INV-3).

### 2.3 API changes

- `InvoiceDtos` (detail and list rows) gain `dueDate` (`yyyy-MM-dd`), `paymentTerm`, `paymentTermLabel`,
  `overdue` (bool), `daysOverdue` (int). Existing fields are unchanged (§8 backward compatibility).
- Create/update requests gain optional `dueDate` and `paymentTerm`.
- The detail DTO also carries `version`, and the update request takes an optional `version`. An editor sends back
  the version it was shown; if the invoice has moved on since, the save is refused with `409 "This record changed
  while you were working on it; reload and try again"` instead of landing on top of whatever was saved in between
  (UI-09). A request that sends no `version` carries no precondition, so existing callers are unaffected.
- Cancelling refuses on money actually taken (`paidAmount > 0`), not on `FULLY_PAID`: a zero-total invoice is born
  `FULLY_PAID` and could otherwise never be cancelled (DASH-05).
- `CustomerDtos` gain `paymentTerm` (nullable) and `paymentTermLabel`; the customer form and detail show it
  (US-A1), `CUSTOMER_MANAGE` to change.
- Customer summary (the detail screen's totals) gains `overdueAmount` (AC-A6).
- Invoice **table schema** (`TableSchemas.INVOICE`) gains `dueDate` DATE (sortable, filterable, range) and
  `overdue` BOOLEAN — a **computed** filter, not a column: `overdue=true` means
  `due_date < :today and (total - paid_amount) > 0 and status <> 'CANCELLED'`, applied server-side inside the
  existing predicate building so scope rules still apply (AC-A6). It is filterable and not sortable; sorting is by
  `dueDate`. `overdue=false` is `due_date is null or not(...)`, so the two halves of the filter are the whole
  list even where a row has no due date at all (§2.5) — `Invoice.isOverdue` guards the same null in Java.
- The invoice list's summary tiles gain `overdueAmount` and `overdueCount`, computed in the existing `Aggregates`
  query over the full filtered set (AC-A7).
- CSV export gains `Due date` and `Overdue` columns (`EXPORT_DATA`).
- Audit (`AuditService`, existing style): a customer's `paymentTerm` change, and an invoice's due-date/term
  override (old → new), and one entry for the backfill (§2.5).

### 2.4 Frontend

- **Invoice form**: a "Payment terms" dropdown and a due-date field. Picking a customer fills both from the
  preview endpoint; changing the term recomputes the date; editing the date switches the term to Custom. The terms
  line reads "Net 30 — due 20 Oct 2026". The horizon warning is inline, not blocking.
- **Invoice detail**: due date beside the invoice date, with an **Overdue** badge ("Overdue by 12 days") in the
  error colour when overdue. With `INVOICE_MANAGE`, the terms dropdown and the due-date field are editable in the
  top section and saved by the existing PATCH, so a renegotiated deadline can be moved after the invoice was
  raised (US-A3) and shows in the History tab as `INVOICE_DUE_DATE_CHANGED` (AC-A8). The date the terms are
  recomputed from is worked back from the stored due date, not from the invoice timestamp, which the browser reads
  in its own zone and the server counts in UTC.
- **The invoice day is a UTC calendar day everywhere it is shown.** The server derives the due date from the UTC
  day of the invoice instant (`InvoiceDates.dayOf`, `ZoneOffset.UTC`), so every place that shows an invoice date
  beside a due date or a payment term renders it in that same zone — `formatUtcDate` in `frontend/lib/core/format.dart`,
  used by the detail header, the terms basis and the list's Date column. Rendered in the browser's zone instead,
  an invoice raised at 23:30 UTC reads a day later than the day its Net 30 was counted from, so the pair on screen
  is 29 days apart (INV-2). `formatDate`/`formatDateTime` keep local rendering for true instants such as audit
  timestamps.
- **Invoice list**: a Due date column (sortable) beside the Date column — both UTC calendar days, as above — the
  Overdue badge on the row, an "Overdue only" filter chip, and the two new tiles. The date and its badge sit in a
  `Wrap`, so on a phone, where a card's cell is narrower than the two together, the badge falls onto the next line
  instead of being clipped.
- **Customer detail**: payment terms in the header/fields, and "of which overdue" beside the outstanding total.
- `frontend/lib/core/field_limits.dart` mirrors any new limit.

### 2.5 Migration (D2, §9 of the PRD)

`InvoiceSchemaUpgrade` (`com.geneinvoice.invoice`, the shape of `EmailSchemaUpgrade`), at startup, in order,
idempotent, logged, and safe to re-run on a populated database:

1. The entity maps `due_date` **nullable** so `ddl-auto: update` can add the column to a table with rows.
2. Every invoice with no due date gets `invoice_date + <default term> days` and `payment_term = '<default>'`,
   guarded by `where due_date is null` so a second run is a no-op. It logs the row count, writes one audit entry
   ("Backfilled N invoice due dates using Net 30", `INVOICE_DUE_DATES_BACKFILLED`, against `entity_id` 0 because
   the act is on the whole table), and never touches `status` or `paid_amount`.
   **Not** the single literal `update … set due_date = invoice_date + N days`: the calendar day an instant falls
   on is UTC everywhere in this app, but H2 casts a stored instant to DATE in the session's own zone, which dates
   a 23:30Z invoice a day late. The date is worked out in Java through `InvoiceDates.dayOf` and written by one
   prepared statement in batches of 1000, paged by `where id > ? and due_date is null order by id limit ?` with
   the last id of each chunk carried into the next, so a million-row table is one pass of the primary key rather
   than a fresh scan per chunk; progress is logged per chunk. The raised timestamp is read as an instant
   (`getObject(…, OffsetDateTime.class)`, or `Timestamp.toInstant()` from a driver that gives only that — the
   shape PostgreSQL's returns for a `timestamptz`) and **never** as a wall time re-read as UTC, which would move
   it by the machine's offset and cause the very day-shift this Java path exists to avoid. Same semantics,
   same row count, same one audit entry, and no dialect branch (the literal form needed one).
3. `alter table invoices alter column due_date set not null` (Postgres) / `alter table invoices alter column due_date date not null` (H2),
   skipped when already not null; failure is logged as a WARN, not fatal, and the service-level invariant still
   holds (AC-A1).

## 3. Feature B — ageing by days past due

`DashboardService.outstandingByAge` keeps its endpoint, scope and `Coverage`, and changes its measure:

| Bucket | Meaning |
|---|---|
| `Not yet due` | `due_date >= today`, and a row with no due date at all (§2.5) |
| `1–30 days` | `today - due_date` between 1 and 30 |
| `31–60 days` | 31–60 |
| `61–90 days` | 61–90 |
| `Over 90 days` | 91 and more |

Every bucket's amount is `sum(total - paid_amount)` over invoices with a balance, excluding `CANCELLED` and
fully-paid (AC-B2), in **one query** with the existing `Aggregates` approach (AC-B7). An invoice with no due date
at all — only reachable where step 3 of §2.5 could not run — counts as `Not yet due`, because it is not late
(`Invoice.isOverdue`) and because a row in no bucket would take its balance off a chart that has to add up to the
outstanding total (AC-B3). Labels come from the backend.
The chart's title becomes "Outstanding by days overdue" and the axis "Days overdue" (AC-B5).

`DashboardDtos.AgeBucket` is `{label, fromDays, toDays, amount, count, dueDateFrom, dueDateTo}`.
`fromDays`/`toDays` are how late the band is,
and **both are nullable**: `fromDays` is null on `Not yet due` and `toDays` on `Over 90 days`, because each of those
bands is open at one end. `dueDateFrom`/`dueDateTo` are the same bounds turned back into calendar days —
`today - toDays` and `today - fromDays`, null where the band is open — and they are what a bucket deep-links by:
`status in (UNPAID, PARTIALLY_PAID)` plus `dueDate:gte:<from>` / `dueDate:lte:<to>` / `dueDate:between:<from>,<to>`
on the invoice list. So `Not yet due` → `dueDate:gte:today`, `1–30` → `dueDate:between:today-30,today-1`,
`Over 90` → `dueDate:lte:today-91`. The frontend reads those two dates off the response rather than working them
out from its own clock, so the bar and the rows it opens cannot disagree about which day it is (AC-B6). A bucket
whose window cannot be written as a range is rendered but not clickable. The filter grammar has no "is null", so
an invoice with no due date is counted in `Not yet due` but is not among the rows that bucket's link opens — a
difference only a database where step 3 of §2.5 did not run can show.

### 3.1 Privileges and scope for Features A and B

PRD §8 asks for a role × capability matrix covering both features; it was missing until the 2026-09-21
regression built one empirically (D-04). Measured against the running app, all seven seeded roles:

| Role | Set a customer's terms | Create an invoice | Override a due date | Read ageing | Ageing coverage | Filter by overdue | Export invoices CSV |
|---|---|---|---|---|---|---|---|
| ADMIN | ✓ | ✓ | ✓ | ✓ | `ALL` | ✓ unrestricted | ✓ |
| CASHIER | ✓ | ✓ | ✓ | ✓ | `ALL` | ✓ unrestricted | ✓ |
| VIEWER | ✗ 403 | ✗ 403 | ✗ 403 | ✓ | `ALL` | ✓ unrestricted | ✗ 403 (no `EXPORT_DATA`) |
| CUSTOMER | ✗ 403 | ✗ 403 | ✗ 403 | ✓ | `OWN` | ✓ own rows only | ✗ 403 |
| SALES_POC | ✗ 403 | ✓ | ✓ | ✓ | `BOOK` | ✓ own book only | ✓ |
| CUSTOMER_SUCCESS_POC | ✓ | ✗ 403 | ✗ 403 | ✓ | `ALL` | ✓ unrestricted | ✓ |
| COLLECTION_POC | ✗ 403 | ✗ 403 | ✗ 403 | ✓ | `ALL` | ✓ unrestricted | ✓ |

Reading it:

- **Setting payment terms is `CUSTOMER_MANAGE`**, which is why a Customer Success POC can and a Collection
  POC cannot. **Overriding a due date is `INVOICE_MANAGE`**, so it follows invoice creation.
- **Ageing is readable by everyone**, including VIEWER and a customer login; what differs is coverage, and
  coverage always matches what that caller can list for themselves (AC-B4). `ALL` for the two POC roles
  carrying `SCOPE_OVERRIDE` is the same grant discussed in §4.5.
- **Export needs `EXPORT_DATA` *and* the entity's view privilege**, both. A role holding only `EXPORT_DATA`
  gets 403 everywhere — the fix for an earlier defect, re-verified here.

## 4. Feature C — documents

### 4.1 Model (`com.geneinvoice.document`)

`documents` — `Document`

| Column | Type | Notes |
|---|---|---|
| id | bigint identity | |
| entity_type | varchar(20) | `DocumentEntityType`: `CUSTOMER`, `INVOICE`, `PAYMENT` (open for more, D5) |
| entity_id | bigint | |
| entity_label | varchar(200) | snapshot, e.g. `Invoice INV-0042` |
| customer_id | bigint null | the record's customer, for scope checks without a join |
| filename | varchar(260) | the original name, for display only (AC-C8) |
| content_type | varchar(120) | what was detected, not what the client claimed |
| size_bytes | bigint | |
| checksum | varchar(64) | SHA-256 hex, computed while streaming |
| storage_key | varchar(300) | server-generated (§4.4) |
| visibility | varchar(10) | `INTERNAL` (default) / `SHARED` (D7) |
| description | varchar(500) null | |
| uploaded_by_user_id | bigint null, uploaded_by_name varchar(200) | |
| uploaded_at | timestamp | |
| deleted | boolean not null default false | soft delete (AC-C3) |
| deleted_by_user_id | bigint null, deleted_at timestamp null | |
| created_at, updated_at | timestamp | |

Indexes: `(entity_type, entity_id, deleted, uploaded_at)`, `customer_id`, `storage_key`.

### 4.2 API

`DocumentDto`: `{id, entityType, entityId, entityLabel, entityLink, filename, contentType, sizeBytes,
sizeLabel, visibility, description, uploadedBy: {userId, name}, uploadedAt, canDownload, canEdit, canDelete}`.

| Method | Path | Rules |
|---|---|---|
| POST | `/api/documents` | multipart: `file`, `entityType`, `entityId`, `description?`, `visibility?`. `DOCUMENT_MANAGE` + the record's **manage** privilege (customer logins: their own record + `DOCUMENT_MANAGE`, see §4.5). 201 `DocumentDto`. |
| GET | `/api/documents?entityType=&entityId=&page=&size=` | `DOCUMENT_VIEW` + the record's view privilege + scope. `PageResponse<DocumentDto>`, newest first (AC-C2). Customer logins see only `SHARED`. |
| GET | `/api/documents/count?entityType=&entityId=` | `{"count": n}` for the tab badge (AC-C1). |
| GET | `/api/documents/{id}/download` | Same rules as the list. Streams the bytes (§4.4). |
| PATCH | `/api/documents/{id}` | `{description?, visibility?}`. `DOCUMENT_MANAGE` + record manage; never a customer login (403). |
| DELETE | `/api/documents/{id}` | Soft delete; `DOCUMENT_MANAGE` on the endpoint either way, then the **record's** manage privilege or being the uploader — the uploader stands in for the record's privilege, not for the document one, and still has to be able to see the record. Never a customer login (403). |

Errors use the existing `ApiError` shapes: too large → 400 `fieldErrors.file`
`"The file is larger than 10 MB"`; disallowed → 400 `"Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)"`;
missing file → 400 `"Choose a file"`. A deleted document is 404 on download. Every upload, edit and delete writes
an audit entry and appears in the record's History tab (AC-C4).

### 4.3 Validation (AC-C6–C9)

`DocumentRules`: size against `app.documents.max-size-bytes` (10,485,760), enforced while streaming, before
storage; the content type **detected from the first bytes** (`ContentSniffer`: `%PDF-`, PNG signature, JPEG SOI,
and ZIP + `[Content_Types].xml` with `word/` → DOCX or `xl/` → XLSX) and required to be in
`app.documents.allowed-types`; a client-supplied type that disagrees is ignored, and the detected one is stored.
Filenames are cleaned for display — path separators dropped, then every character that is not there to be read
(control characters, the bidi controls, the zero-width characters, the byte order mark, line and paragraph
separators), then cut to 260 at a code point — and **never** used as a path: a name that reorders or hides what it
says spoofs the documents tab and the `Content-Disposition` header alike. A description is cleaned the same way
but keeps the line breaks and tabs a note is written with, so a null byte in one is dropped rather than reaching a
text column, which answers a database conflict for what is a validation matter. `DocumentScanner` (interface) is called with the stored temp file before the row is
written; `NoopDocumentScanner` is the shipped implementation (AC-C14).
`spring.servlet.multipart.max-file-size` reads `${DOCUMENT_MAX_BYTES}` itself, rather than repeating today's value
of it, so a deployment that raises the app's limit raises the container's with it and the two always fire on the
same upload; `max-request-size` is `${DOCUMENT_MAX_REQUEST_BYTES}` (the file plus room for the rest of the form)
and `file-size-threshold` is 1MB. `DocumentUploadAdvice`
(`@RestControllerAdvice`, `@Order(HIGHEST_PRECEDENCE)`) turns the container's `MaxUploadSizeExceededException` into
the same `fieldErrors.file` message the app's own check gives, so an over-large upload reads identically whichever
one caught it (AC-C9). It is an advice and not a `@ExceptionHandler` on the controller because the exception is
raised before a handler is chosen, and not in `GlobalExceptionHandler` because that would make `common` depend on
the document package — its `Exception` catch-all would otherwise report a 500.

Both messages reach the user verbatim. `apiErrorMessage` (`lib/core/api/api_client.dart`) names the field only
for a message that starts lower-case — a Bean Validation fragment such as "must not be blank" — and shows anything
that already reads as a sentence untouched. Naming the field regardless produced "File Files of this kind cannot
be attached (…)" from the server while the client's own check on the same file produced the sentence itself, which
is the very difference AC-C9 rules out (UI-03).

### 4.4 Storage (D6, AC-C15–C18)

`DocumentStorage` (interface): `boolean isConfigured()`, `StoredFile put(InputStream in, String key, String contentType, long maxBytes)`
(returns size and checksum, throws `DocumentStorageException`), `InputStream open(String key)`, `void delete(String key)`.
`LocalDocumentStorage` (`app.documents.storage: local`, the default) writes under `app.documents.local.root`
(`${DOCUMENT_ROOT:./data/documents}`): the root is **created when it is not there** — the default path exists in
no fresh checkout, and AC-C17 promises the H2 demo works with no extra setup — and startup fails naming
`DOCUMENT_ROOT` when it cannot be created, is not a directory, or is not writable. Keys are `{entityType lower}/{entityId}/{uuid}.{safe extension}`, generated server-side;
the key is resolved against the root and the result **must** stay inside it (a check, not a hope). Bytes are written
to a temp file, checked, then moved into place; the row is written after, and a row that could not be
written deletes the bytes, so there is never a half-made document (AC-C18). Only the transaction is guarded that
way: once the row has been written the document is real, and a failure after it is a 500 over a whole document
rather than a listed row whose file has been taken away from it. `app.documents.storage: none` makes uploads 503
`"Document storage is not configured"` while everything else keeps working.

Download responses carry `Content-Disposition: attachment; filename="…"; filename*=UTF-8''…`,
`Content-Type: application/octet-stream`, `X-Content-Type-Options: nosniff`,
`Content-Security-Policy: default-src 'none'`, `Cache-Control: private, no-store` (AC-C13), and stream from
storage (no full read into memory).

### 4.5 Privileges and scope

New: `DOCUMENT_VIEW`, `DOCUMENT_MANAGE` (`Privileges`, seeded idempotently in `DataSeeder`'s grant-once style).
`DOCUMENT_MANAGE` on the **CUSTOMER** role is granted only in the boot that creates the privilege and is never
forced back on: letting an outside account attach files is the one capability here an operator may want to
withdraw, and a revocation on the roles screen has to survive a restart. Every other privilege of a built-in role
is still kept in step with the code on every boot.

| Role | View | Manage | Notes |
|---|---|---|---|
| ADMIN | ✓ | ✓ | everything, including visibility |
| CASHIER | ✓ | ✓ | keeps every existing capability |
| VIEWER | ✓ | — | |
| SALES_POC | ✓ | ✓ | manages **invoice** documents only; book-limited (`ScopeResolver`) |
| CUSTOMER_SUCCESS_POC | ✓ | ✓ | manages **customer** documents only; holds `SCOPE_OVERRIDE`, so **not** book-limited |
| COLLECTION_POC | ✓ | ✓ | manages **payment** documents only; holds `SCOPE_OVERRIDE`, so **not** book-limited |
| CUSTOMER (self-service) | ✓ | ✓ | **only their own records**; sees only `SHARED`; uploads land `SHARED` and are attributed to them; `PATCH` and `DELETE` are 403 |

Two things about the POC rows are easy to get wrong, and both were stated wrongly here until the 2026-09-21
regression measured them (D-03):

- **Manage is per record kind, not blanket.** A POC role can manage documents only on the record kind whose
  manage privilege it holds, because every endpoint requires `DOCUMENT_MANAGE` *and* the parent record's manage
  privilege (AC-C10). A Sales POC gets 403 on a customer's documents and 404 on a payment's.
- **Only SALES_POC is book-limited.** `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC` are seeded with
  `SCOPE_OVERRIDE` (`DataSeeder`), so they reach records they hold no seat on — including downloading an
  `INTERNAL` document on another POC's customer, invoice or payment. That is the same grant that makes their
  ageing coverage `ALL` rather than `BOOK`, so it is consistent rather than accidental; it is recorded here
  because the security posture is the opposite of what "inside their POC book" implied. Whether those two roles
  *should* reach outside their book is an open product question, not a defect.

The measured matrix these rows come from, for all seven roles across all three features, is
`docs/regression/2026-09-21/data/role-matrix-observed.md`.

Every document endpoint checks, in this order: the document privilege, then the **parent record's** privilege
(view for reads, manage for writes — for a customer login, the record's *view* privilege plus `DOCUMENT_MANAGE`,
which is the one deliberate exception to AC-C10, made because the user asked for customer uploads), then
`ScopeResolver` on the parent record, then `visibility` for customer logins. A request naming a document on a
record outside the caller's scope answers exactly as the record itself would (404/403), and is tested directly by
id, not through the UI (AC-C11).

Deleting a parent record soft-deletes its documents in the same transaction (AC-C5). Both sides take the
customer's row lock first (`DocumentParentLock`): the cascade before it reads what to delete, and an upload before
it writes its row. So one upload and one delete have only the two orders they should have — the upload finds its
record gone, fails and takes its bytes back out, or the delete waits for the row and takes it with the rest —
and neither leaves a live document on a customer that no longer exists. A document's own delete reads its row the
same way, so deleting one document twice at once is one deletion and one 404, exactly as deleting it twice in a
row is.

### 4.6 Frontend (`lib/features/documents/`)

| File | Contents |
|---|---|
| `document_models.dart` | `DocumentItem`, `DocumentVisibility`, `DocumentEntityType` (wire/noun/apiPath) |
| `document_providers.dart` | list (paged), count, upload/edit/delete actions, invalidation |
| `documents_tab.dart` | the tab body: list (name, type icon, size, who, when, visibility chip), row actions (download, edit, delete), empty/loading/error states (AC-C21) |
| `upload_document_dialog.dart` | file picker **and** drag-and-drop, progress bar (dio `onSendProgress`), description, visibility (hidden for customer logins), client-side size/type check before sending (AC-C8, C20) |
| `document_actions.dart` | the public API — screens import only this: `DetailTab? documentsDetailTab(WidgetRef ref, {required DocumentEntityType type, required int entityId, String? entityLabel})` (null without `DOCUMENT_VIEW`), `canManageDocumentsProvider` |

The tab is added to the **invoice**, **customer** and **payment** detail screens with the existing `DetailTab` +
URL slug pattern (slug `documents`, label "Documents", `Icons.folder_outlined`), placed before the Email tab, with
a count badge. Buttons that would 403 are not shown (AC-C22). Drag-and-drop uses a web drop target behind a
conditional import so tests and any non-web build still compile.

**A user without `DOCUMENT_MANAGE` sees no Upload control and no explanation of its absence.** AC-C20 as first
written asked for the control to be disabled with an explanation, which cannot be satisfied at the same time as
AC-C22's "no visible button that will predictably 403". AC-C22 governs, and the PRD's AC-C20 was amended on
2026-09-21 to say so rather than leave the next implementer with two criteria that contradict each other
(regression defect D-05). If the explanation is ever wanted, it belongs as a line of text where the button would
be, not as a disabled button — a line of text is not something that can be pressed and 403.

## 5. Configuration

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${DOCUMENT_MAX_BYTES:10485760}            # the same setting as app.documents.max-size-bytes
      max-request-size: ${DOCUMENT_MAX_REQUEST_BYTES:12582912}
      file-size-threshold: 1MB
app:
  invoice:
    default-payment-term: ${INVOICE_DEFAULT_TERM:NET_30}
    due-date-horizon-days: ${INVOICE_DUE_DATE_HORIZON_DAYS:365}
  documents:
    storage: ${DOCUMENT_STORAGE:local}        # local | none
    max-size-bytes: ${DOCUMENT_MAX_BYTES:10485760}
    allowed-types: application/pdf,image/png,image/jpeg,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
    local:
      root: ${DOCUMENT_ROOT:./data/documents}
```

## 6. Tests

Backend: `PaymentTerm` and due-date derivation; D1 (changing a customer's terms leaves an existing invoice's date
alone); AC-A4 (overdue by the clock alone, with no write; `CANCELLED` and fully-paid never overdue); AC-A5
validation, including a term and a date in one request; AC-A6 list filter and sort under each scope (POC book,
customer login), and the preview refusing a customer outside the book; AC-A7 tiles; AC-A9 boundaries;
the backfill (idempotent, re-run, row count, nothing else touched, each page starting after the last id, and a
driver's `Timestamp` read as the instant it stands for); every ageing bucket edge (due today, 1, 30/31,
60/61, 90/91) and the AC-B3 reconciliation, including an invoice with no due date at all; the deep-link filters
returning the bucket's rows. Documents: upload
(happy path, too large, disallowed type, lying content type, traversal and null-byte filenames, a name that
reorders or hides itself and the header it reaches, a description with a null byte, over-long name),
sizes at the kilobyte-to-megabyte rollover, two deletes of one document at once and an upload racing its
customer's deletion both ways round,
listing and paging, download headers and streaming, soft delete then 404, parent delete, edit, the full privilege
and scope matrix including a direct request for a foreign document id, customer visibility and customer upload
(lands `SHARED`, `PATCH`/`DELETE` 403), storage failure leaving no row, a failure *after* the row is written
leaving the document whole, the container's limit being the configured one, a revoked customer upload privilege
surviving a restart, `storage: none`. Frontend: form terms and
due-date wiring, the horizon warning with and without the preview, the detail page's override, the overdue badge
at phone width, list filter chip and tiles, the ageing chart's labels and deep link, and the
documents tab (empty/loading/error, upload progress, size/type refusal, a failed download saying what the server
said, privilege-hidden actions, count badge).
`mvn verify` and `flutter analyze` must pass, and the existing suites stay green.
