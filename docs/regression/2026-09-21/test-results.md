# Test results — regression run 2026-09-21

The three features ship-ready: 343 cases, 337 passed, and not one high-severity defect in due dates, ageing or documents. The single high-severity defect found is a migration-path gap in the email feature that predates this work and breaks any upgrade of an existing database.

## Totals

| Area | Cases | Pass | Fail | Blocked | Not tested | Health |
|---|---:|---:|---:|---:|---:|---|
| A — Payment terms and invoice due dates (API) | 57 | 57 | 0 | 0 | 0 | good |
| B — Overdue status and ageing by days past due | 46 | 46 | 0 | 0 | 0 | good |
| C — Documents: API, security and limits | 69 | 67 | 2 | 0 | 0 | fair |
| D — Documents: the tab in the UI | 39 | 38 | 1 | 0 | 0 | fair |
| E — Permissions, privileges and scoping | 35 | 32 | 3 | 0 | 0 | fair |
| F — Blast radius: did anything that worked stop working | 97 | 97 | 0 | 0 | 0 | good |
| **Total** | **343** | **337** | **6** | **0** | **0** | |

## Summary

A targeted regression on invoice due dates, ageing by days past due, and document attachments (docs/requirements/invoice-due-dates-and-documents.md), plus the blast radius around them. 343 cases across six areas on a fresh, isolated environment (backend 8083, web 8084, empty Postgres database geneinvoice_rt). 337 passed, 6 failed: 4 medium, 2 low, 0 high.

The features themselves are in good shape. Every boundary in the new ageing calculation is exact, the money arithmetic carries no floating-point drift, and the security surface of document upload — the largest new attack surface in the app — held under deliberate attack: content-type sniffing rejects a script payload disguised as a PDF, path traversal is fully contained, storage keys are server-generated, and downloads carry headers that neutralise anything that does get stored. Authorization was probed by direct request against document ids rather than through the UI, and no bypass was found in 35 permission cases.

The one high-severity defect was found before testing began, while deploying current code onto the user's own database. The emails table there is still on the pre-refactor schema, ddl-auto: update cannot migrate it, and every email endpoint returns 500. A fresh database is unaffected — which is exactly why 679 green backend tests never caught it, since they all run against create-drop schemas. It is not caused by the due-date or document work.

Of the five remaining defects, two are a wrong status code on one hostile input, two are errors in the implementation doc's role matrix where the code is right and the document is wrong, and one is an acceptance criterion the implementation chose not to honour. None blocks release of the three features.

## Failures

| Case | Severity | AC | Title | Observed |
|---|---|---|---|---|
| C-38 | medium | AC-C8 | Filename attack — null byte: "shell\u0000.pdf" | 500 "Unexpected error" stored filename=undefined |
| C-43 | medium | AC-C8 | A null byte anywhere in the filename is answered consistently, never with a 5xx | leading: 201 filename="boot.pdf"; middle: 500 "Unexpected error"; before extension: 500 "Unexpected error"; on its own: 201 filename="file" |
| D-18 | low | AC-C20 | A user without DOCUMENT_MANAGE is told why they cannot upload | Unchanged by the rebuild: the Upload button is simply absent and nothing on the tab explains why. Buttons on the tab: Notifications, Account d-viewonly-mua75g39 |
| E-33 | medium | AC-C10 | The implementation doc's matrix claims POC roles have document Manage, but each POC can only manage the one record kind its role can manage | Manage is only ever available on the record kind whose manage privilege the role holds. SALES_POC: upload/PATCH/DELETE 201/200/204 on an INVOICE, 403/403/403 on |
| E-34 | medium | AC-C11 | The implementation doc says POC document access is limited to 'their POC book'; two of the three POC roles are not book-limited at all | Only SALES_POC is book-limited: 404 on GET /api/customers/{custB}, /api/invoices/{invB} and /api/payments/{payB}, 404 on the document list and download for all  |
| E-35 | low |  | The implementation doc carries no role × capability matrix for Feature A and Feature B, which PRD §8 requires 'for both features' | The only such table is §4.5, lines 261-267, and its columns are 'View' and 'Manage' — documents only. Nothing states, per role, who may set a customer's payment |

## Acceptance-criterion coverage

| AC | Cases | Pass | Fail |
|---|---:|---:|---:|
| AC-A1 | 6 | 6 | 0 |
| AC-A2 | 16 | 16 | 0 |
| AC-A3 | 12 | 12 | 0 |
| AC-A4 | 2 | 2 | 0 |
| AC-A5 | 20 | 20 | 0 |
| AC-A6 | 4 | 4 | 0 |
| AC-A7 | 1 | 1 | 0 |
| AC-A8 | 4 | 4 | 0 |
| AC-A9 | 1 | 1 | 0 |
| AC-A10 | 5 | 5 | 0 |
| AC-B1 | 4 | 4 | 0 |
| AC-B2 | 8 | 8 | 0 |
| AC-B3 | 6 | 6 | 0 |
| AC-B4 | 8 | 8 | 0 |
| AC-B5 | 3 | 3 | 0 |
| AC-B6 | 7 | 7 | 0 |
| AC-B7 | 1 | 1 | 0 |
| AC-B8 | 8 | 8 | 0 |
| AC-C1 | 13 | 13 | 0 |
| AC-C2 | 3 | 3 | 0 |
| AC-C3 | 6 | 6 | 0 |
| AC-C4 | 5 | 5 | 0 |
| AC-C6 | 9 | 9 | 0 |
| AC-C7 | 8 | 8 | 0 |
| AC-C8 | 13 | 11 | 2 |
| AC-C9 | 1 | 1 | 0 |
| AC-C10 | 20 | 19 | 1 |
| AC-C11 | 8 | 7 | 1 |
| AC-C12 | 13 | 13 | 0 |
| AC-C13 | 3 | 3 | 0 |
| AC-C15 | 1 | 1 | 0 |
| AC-C16 | 1 | 1 | 0 |
| AC-C18 | 2 | 2 | 0 |
| AC-C19 | 9 | 9 | 0 |
| AC-C20 | 8 | 7 | 1 |
| AC-C21 | 10 | 10 | 0 |
| AC-C22 | 9 | 9 | 0 |
| D3 | 4 | 4 | 0 |
| D5 | 1 | 1 | 0 |
| D7 | 5 | 5 | 0 |
| US-C5 | 2 | 2 | 0 |
| US-C6 | 1 | 1 | 0 |

## A — Payment terms and invoice due dates (API)

| Case | Kind | AC | Title | Status |
|---|---|---|---|---|
| A-01 | API | AC-A1 | POST /api/invoices with dueDate and paymentTerm omitted entirely still stores a due date | PASS |
| A-02 | API | AC-A1 | Explicit JSON nulls for dueDate and paymentTerm still yield a due date | PASS |
| A-03 | API | AC-A1 | No other invoice-creating route exists (bulk/import/upload probed) | PASS |
| A-04 | API | AC-A1 | Every invoice visible in the list carries a non-null due date | PASS |
| A-05 | API | AC-A2 | Customer on DUE_ON_RECEIPT gives dueDate = invoiceDate + 0 days | PASS |
| A-06 | API | AC-A2 | Customer on NET_15 gives dueDate = invoiceDate + 15 days | PASS |
| A-07 | API | AC-A2 | Customer on NET_30 gives dueDate = invoiceDate + 30 days | PASS |
| A-08 | API | AC-A2 | Customer on NET_45 gives dueDate = invoiceDate + 45 days | PASS |
| A-09 | API | AC-A2 | Customer on NET_60 gives dueDate = invoiceDate + 60 days | PASS |
| A-10 | API | AC-A2 | Customer on NET_90 gives dueDate = invoiceDate + 90 days | PASS |
| A-11 | API | AC-A2 | A customer with no terms gets the system default (Net 30) | PASS |
| A-12 | API | AC-A2 | D1: changing a customer's terms afterwards never moves an issued invoice's due date | PASS |
| A-13 | API | AC-A2 | GET /api/invoices/due-date-preview reports the terms and their source | PASS |
| A-14 | API | AC-A2 | CUSTOM is refused as a customer-level payment term (D1) | PASS |
| A-15 | API | AC-A3 | Invoice detail exposes dueDate, paymentTerm and paymentTermLabel | PASS |
| A-16 | API | AC-A3 | Invoice list rows expose dueDate and paymentTerm | PASS |
| A-17 | API | AC-A3 | CSV export carries a Due date column, with the right value on the row | PASS |
| A-18 | API | AC-A3 | Invoice summary tiles expose overdueAmount and overdueCount | PASS |
| A-19 | API | AC-A5 | A due date earlier than the invoice date is rejected with the standard validation shape | PASS |
| A-20 | API | AC-A5 | A due date equal to the invoice date is accepted | PASS |
| A-21 | API | AC-A5 | A far-future due date past the 365-day horizon is accepted by the API | PASS |
| A-22 | API | AC-A5 | A custom due date is recorded as paymentTerm CUSTOM | PASS |
| A-23 | API | AC-A5 | paymentTerm CUSTOM without a dueDate is rejected | PASS |
| A-24 | API | AC-A5 | A named term sent together with a dueDate is refused rather than silently ignored | PASS |
| A-25 | API | AC-A5 | An explicit DUE_ON_RECEIPT term on the invoice overrides the customer's NET_45 | PASS |
| A-26 | API | AC-A8 | A customer payment-terms change is written to the audit log | PASS |
| A-27 | API | AC-A8 | An invoice due-date override is written to the audit log | PASS |
| A-28 | API | AC-A8 | Re-sending the same due date writes no spurious due-date audit entry | PASS |
| A-29 | API | AC-A10 | A promise dated after the invoice due date is accepted and links to the invoice | PASS |
| A-30 | API | AC-A10 | A promise past the due date still works after the invoice due date is overridden | PASS |
| A-31 | API | AC-A5 | A malformed due date ("2026-13-45") is a 400, not a 500 | PASS |
| A-32 | API | AC-A5 | A non-date due date ("tomorrow") is a 400, not a 500 | PASS |
| A-33 | API | AC-A5 | A due date in year 9999 is handled deterministically | PASS |
| A-34 | API | AC-A5 | A leap-day due date (2028-02-29) is accepted verbatim | PASS |
| A-35 | API | AC-A5 | A non-existent leap day (2027-02-29) is a 400, not a rolled-forward 1 March | PASS |
| A-36 | API | AC-A2 | Term arithmetic across a leap February: 2028-01-31 + NET_30 = 2028-03-01 | PASS |
| A-37 | API | AC-A2 | Term arithmetic across a year end: 2026-12-31 + NET_90 = 2027-03-31 | PASS |
| A-38 | API | AC-A5 | An unknown payment term is a 400 that names the valid values | PASS |
| A-39 | API | AC-A5 | A due date sent as an instant is either rejected or read as its UTC calendar day | PASS |
| A-40 | API | AC-A5 | An empty-string due date is a 400, not a 500 and not an invoice with no due date | PASS |
| A-41 | API | AC-A5 | PATCH cannot move a due date before the invoice date either | PASS |
| A-42 | API | AC-A2 | PATCH with a named term recomputes the due date from the invoice date, not from today | PASS |
| A-43 | API | AC-A9 | An invoice due today is not overdue; one due yesterday is, with daysOverdue 1 | PASS |
| A-44 | API | AC-A3 | A self-service customer reads the due date on their own invoice | PASS |
| A-45 | API | AC-A3 | The CSV Overdue cell reads true for a late invoice and false for one not yet due | PASS |
| A-46 | API | AC-A6 | The invoice list sorts by due date server-side, both directions | PASS |
| A-47 | API | AC-A5 | A cashier (INVOICE_MANAGE, not admin) may set and later override a due date | PASS |
| A-48 | API | AC-A5 | A self-service customer cannot create an invoice or set its own due date | PASS |
| A-49 | CODE REVIEW | AC-A1 | InvoiceSchemaUpgrade backfill is idempotent, chunked, and touches nothing but due_date | PASS |
| A-50 | CODE REVIEW | AC-A1 | Backfill risks found by reading: audit actor is null and the backfill row is entity id 0 | PASS |
| A-51 | API | AC-A6 | The overdue-only filter is server-side and its complement is exactly the rest | PASS |
| A-52 | API | AC-A6 | The list can be filtered by a due-date range server-side | PASS |
| A-53 | API | AC-A5 | PATCH with paymentTerm CUSTOM and no due date is rejected, leaving the invoice unmoved | PASS |
| A-54 | API | AC-A8 | A customer PUT that omits paymentTerm clears the terms — audited, and issued invoices unmoved | PASS |
| A-55 | API | AC-A4 | A cancelled invoice and a fully-paid one never read as overdue, whatever the due date says | PASS |
| A-56 | API | AC-A4 | daysOverdue is exact at each ageing boundary (1, 30, 31, 60, 61, 90, 91, 400 days late) | PASS |
| A-57 | API | AC-A5 | Out-of-range and negative years are rejected, not clamped | PASS |

## B — Overdue status and ageing by days past due

| Case | Kind | AC | Title | Status |
|---|---|---|---|---|
| B-01 | API | AC-B1 | Invoice due today+1 (due tomorrow) lands in "Not yet due" | PASS |
| B-02 | API | AC-B1 | Invoice due today (due today) lands in "Not yet due" | PASS |
| B-03 | API | AC-B8 | Invoice due today-1 (1 day past due) lands in "1–30 days" | PASS |
| B-04 | API | AC-B8 | Invoice due today-30 (30 days past due) lands in "1–30 days" | PASS |
| B-05 | API | AC-B8 | Invoice due today-31 (31 days past due) lands in "31–60 days" | PASS |
| B-06 | API | AC-B8 | Invoice due today-60 (60 days past due) lands in "31–60 days" | PASS |
| B-07 | API | AC-B8 | Invoice due today-61 (61 days past due) lands in "61–90 days" | PASS |
| B-08 | API | AC-B8 | Invoice due today-90 (90 days past due) lands in "61–90 days" | PASS |
| B-09 | API | AC-B8 | Invoice due today-91 (91 days past due) lands in "Over 90 days" | PASS |
| B-10 | API | AC-B1 | Five buckets with the D4 labels and contiguous, non-overlapping day bounds | PASS |
| B-11 | API | AC-B1 | Each invoice falls in exactly one bucket — amounts and counts add up per bucket | PASS |
| B-12 | API | AC-B6 | "Not yet due" window (2026-09-20 .. -) fetches exactly the invoices behind the bucket | PASS |
| B-13 | API | AC-B6 | "1–30 days" window (2026-08-21 .. 2026-09-19) fetches exactly the invoices behind the bucket | PASS |
| B-14 | API | AC-B6 | "31–60 days" window (2026-07-22 .. 2026-08-20) fetches exactly the invoices behind the bucket | PASS |
| B-15 | API | AC-B6 | "61–90 days" window (2026-06-22 .. 2026-07-21) fetches exactly the invoices behind the bucket | PASS |
| B-16 | API | AC-B6 | "Over 90 days" window (- .. 2026-06-21) fetches exactly the invoices behind the bucket | PASS |
| B-17 | API | AC-B3 | Buckets sum to the outstanding total reported by the invoice list tiles for the same scope | PASS |
| B-18 | API | AC-B3 | Buckets sum to the outstanding the dashboard reports per customer (top-outstanding-customers) | PASS |
| B-19 | API | AC-B3 | Book where every invoice is not yet due: all money in "Not yet due", nothing overdue, and it still reconciles | PASS |
| B-20 | API | AC-B2 | A bucket carries the outstanding balance of its invoices | PASS |
| B-21 | API | AC-B2 | A part payment drops the bucket by exactly the payment, never by the invoice total | PASS |
| B-22 | API | AC-B2 | The bucket holds total - paidAmount for the part-paid invoice, not its total | PASS |
| B-23 | API | AC-B2 | A fully-paid invoice leaves the buckets entirely | PASS |
| B-24 | API | AC-B2 | A cancelled invoice leaves the buckets entirely | PASS |
| B-25 | API | AC-B3 | After a part payment, a full payment and a cancellation the buckets still sum to outstanding | PASS |
| B-26 | API | AC-B2 | Awkward amounts (0.01, 12345.67, 99999999.99) add up exactly in a bucket, with no floating-point drift | PASS |
| B-27 | API | AC-B2 | A sub-rupee part payment moves the bucket by exactly one cent-accurate step | PASS |
| B-28 | API | D3 | An invoice whose due date has passed reads as overdue with no write to the row | PASS |
| B-29 | API | D3 | An invoice due today is not overdue; it is the day rolling over, not a write, that makes it late | PASS |
| B-30 | API | D3 | A CANCELLED invoice 200 days past its due date never reads as overdue | PASS |
| B-31 | API | D3 | A fully-paid invoice 200 days past its due date never reads as overdue | PASS |
| B-32 | API | AC-B2 | Neither the cancelled nor the fully-paid 200-day-old invoice reaches the "Over 90 days" bucket | PASS |
| B-33 | API | AC-B4 | A Sales POC sees only their own book, reported as Coverage BOOK | PASS |
| B-34 | API | AC-B4 | A self-service customer login sees only their own invoices, reported as Coverage OWN | PASS |
| B-35 | API | AC-B4 | A customer's buckets reconcile with the outstanding on their own invoice list | PASS |
| B-36 | API | AC-B4 | A full-scope staff login reports Coverage ALL and covers at least every book this run created | PASS |
| B-37 | API | AC-B4 | A COLLECTION_POC sees exactly what their invoice list shows, with the same coverage the list scope gives | PASS |
| B-38 | CODE | AC-B7 | The ageing figures come from a single aggregate query — no per-invoice work in Java, no N+1 | PASS |
| B-39 | UI | AC-B5 | The ageing card is titled by days OVERDUE, and its axis says so too | PASS |
| B-40 | UI | AC-B5 | Bucket labels are the five days-past-due bands, with no "days since invoice" wording left on the dashboard | PASS |
| B-41 | UI | AC-B4 | Coverage BOOK is shown on the card, so a POC knows the chart is their book and not the company | PASS |
| B-42 | UI | AC-B6 | Clicking the "31–60 days" bar opens the invoice list filtered to that bucket | PASS |
| B-43 | UI | AC-B5 | Loading the dashboard and following a bucket link raises no API error and no page exception | PASS |
| B-44 | API | AC-B3 | The four overdue buckets name exactly the invoices the list's own overdue filter names | PASS |
| B-45 | API | AC-B6 | A bucket window fetches the part-paid invoice behind it, and not the fully-paid or cancelled ones it excluded | PASS |
| B-46 | API | AC-B8 | daysOverdue is exact at every boundary: 0 / 0 / 1 / 30 / 31 / 60 / 61 / 90 / 91 | PASS |

## C — Documents: API, security and limits

| Case | Kind | AC | Title | Status |
|---|---|---|---|---|
| C-01 | API | AC-C1 | Upload a document to a CUSTOMER | PASS |
| C-02 | API | AC-C1 | Upload a document to a INVOICE | PASS |
| C-03 | API | AC-C1 | Upload a document to a PAYMENT | PASS |
| C-04 | API | AC-C1 | Downloaded bytes are identical to the bytes uploaded (all three entity types) | PASS |
| C-05 | API | AC-C2 | Listing a record's documents is newest-first | PASS |
| C-06 | API | AC-C2 | Listing is paged the way the app's other lists are (page/size/totalElements/totalPages) | PASS |
| C-07 | API | AC-C1 | The tab badge count matches the list total | PASS |
| C-08 | API | D7 | Visibility defaults to INTERNAL when the upload does not set it | PASS |
| C-09 | API | D7 | An explicit visibility=SHARED and a description are stored | PASS |
| C-10 | API | AC-C1 | Description and visibility can be edited after upload | PASS |
| C-11 | API | AC-C3 | Delete returns 204 and the document drops out of the list | PASS |
| C-12 | API | AC-C3 | A deleted document 404s on download (not 500, not the bytes) | PASS |
| C-13 | API | AC-C3 | Delete is soft — the row survives with who deleted it and when | PASS |
| C-14 | API | AC-C4 | document uploaded is written to the audit log | PASS |
| C-15 | API | AC-C4 | document updated is written to the audit log | PASS |
| C-16 | API | AC-C4 | document deleted is written to the audit log | PASS |
| C-17 | API | AC-C6 | A file just under the limit (10484736 B) is accepted | PASS |
| C-18 | API | AC-C6 | A file exactly at the limit (10485760 B) is accepted | PASS |
| C-19 | API | AC-C6 | One byte over the limit is refused with the app's validation error | PASS |
| C-20 | API | AC-C6 | Well over the limit (12 MB) is still the app's validation error, not a container 500 | PASS |
| C-21 | API | AC-C9 | Past the multipart max-request-size (20 MB) the client still gets the app's error, not a reset | PASS |
| C-22 | API | AC-C6 | An empty file is refused with a specific message | PASS |
| C-23 | API | AC-C6 | A request with no file part is a validation error, not a 500 | PASS |
| C-24 | API | AC-C6 | A description past the 500-character field limit is refused | PASS |
| C-25 | API | D7 | An unknown visibility value is refused (no silent fallback to SHARED) | PASS |
| C-26 | API | D5 | An entityType outside CUSTOMER/INVOICE/PAYMENT is refused | PASS |
| C-27 | API | AC-C10 | Uploading to a record that does not exist is a 404 | PASS |
| C-28 | API | AC-C7 | HTML/script bytes named innocent.pdf and declared application/pdf are REJECTED | PASS |
| C-29 | API | AC-C7 | A real PNG named payload.exe is accepted and recorded as image/png (extension ignored) | PASS |
| C-30 | API | AC-C7 | A plain ZIP named report.pdf and declared application/pdf is REJECTED | PASS |
| C-31 | API | AC-C7 | A script-bearing SVG is REJECTED (image/svg+xml is not on the allow-list) | PASS |
| C-32 | API | AC-C7 | Office files are recognised from the ZIP parts even when declared application/octet-stream | PASS |
| C-33 | API | AC-C7 | Plain text named notes.pdf is REJECTED | PASS |
| C-34 | API | AC-C7 | A %PDF- polyglot with an HTML/script body is accepted by the sniffer, and served inert | PASS |
| C-35 | API | AC-C8 | Filename attack — POSIX traversal: "../../../../etc/passwd" | PASS |
| C-36 | API | AC-C8 | Filename attack — Windows traversal: "..\\..\\windows\\system32\\cmd.png" | PASS |
| C-37 | API | AC-C8 | Filename attack — absolute path: "/etc/passwd" | PASS |
| C-38 | API | AC-C8 | Filename attack — null byte: "shell\u0000.pdf" | FAIL |
| C-39 | API | AC-C8 | Filename attack — CRLF header injection: "a\r\nX-Injected: yes\r\n.pdf" | PASS |
| C-40 | API | AC-C8 | Filename attack — doubled-up traversal: "....//....//secret.pdf" | PASS |
| C-41 | API | AC-C8 | Filename attack — the parent directory itself: ".." | PASS |
| C-42 | API | AC-C8 | The server survives every filename attack and keeps serving | PASS |
| C-43 | API | AC-C8 | A null byte anywhere in the filename is answered consistently, never with a 5xx | FAIL |
| C-44 | API | AC-C8 | A CRLF filename cannot inject a response header on download | PASS |
| C-45 | API | AC-C8 | A 604-character filename is cut to the column limit rather than failing | PASS |
| C-46 | API | AC-C8 | Every storage key is server-generated — <noun>/<id>/<uuid>.<ext>, nothing from the uploader | PASS |
| C-47 | API | AC-C8 | After every traversal attempt, nothing was written outside the storage root | PASS |
| C-48 | API | AC-C10 | Download, list and upload all require authentication | PASS |
| C-49 | API | AC-C10 | A role with every record privilege but no DOCUMENT_VIEW/MANAGE is refused | PASS |
| C-50 | API | AC-C10 | DOCUMENT_VIEW/MANAGE alone is not enough — the parent record's privilege is also required | PASS |
| C-51 | API | AC-C10 | The view privilege is checked per record type: no INVOICE_VIEW blocks an invoice document only | PASS |
| C-52 | API | AC-C10 | INVOICE_VIEW without INVOICE_MANAGE can download but not upload | PASS |
| C-53 | API | AC-C10 | The seeded VIEWER role can download but cannot upload or delete | PASS |
| C-54 | API | AC-C11 | A SALES_POC cannot reach a document on an invoice outside their book (direct id request) | PASS |
| C-55 | API | AC-C11 | The book also narrows a POC's reach to customer and payment documents | PASS |
| C-56 | API | AC-C11 | A self-service customer cannot reach another customer's document by id | PASS |
| C-57 | API | AC-C12 | A self-service customer downloads a SHARED document on their own record and 404s on an INTERNAL one | PASS |
| C-58 | API | AC-C12 | A customer's document list on their own record holds only SHARED rows | PASS |
| C-59 | API | AC-C12 | The badge count a customer sees excludes INTERNAL documents | PASS |
| C-60 | API | AC-C12 | A self-service customer cannot edit visibility or delete a document | PASS |
| C-61 | API | AC-C12 | Customer self-service upload — documented deviation from AC-C12; the upload is forced SHARED | PASS |
| C-62 | API | AC-C11 | A customer login cannot upload onto another customer's record | PASS |
| C-63 | API | AC-C10 | Deleting someone else's document needs the record's manage privilege | PASS |
| C-64 | API | AC-C13 | Download headers make the file undisplayable in the app's origin | PASS |
| C-65 | API | AC-C13 | The stored content type is never echoed back as the response Content-Type | PASS |
| C-66 | API | AC-C16 | No file bytes are stored in the database | PASS |
| C-67 | API | AC-C18 | Every document row points at bytes that are really there, at the recorded size and checksum | PASS |
| C-68 | API | AC-C18 | A refused upload leaves no half-created document row | PASS |
| C-69 | API | AC-C15 | Bytes land under the configured DOCUMENT_ROOT, and no .part temp file is left behind | PASS |

## D — Documents: the tab in the UI

| Case | Kind | AC | Title | Status |
|---|---|---|---|---|
| D-01 | UI | AC-C19, AC-C1 | Documents tab, with its count, is on the invoice detail screen | PASS |
| D-02 | UI | AC-C19, AC-C1 | Documents tab, with its count, is on the customer detail screen | PASS |
| D-03 | UI | AC-C19, AC-C1 | Documents tab, with its count, is on the payment detail screen | PASS |
| D-04 | UI | AC-C19 | Selecting the Documents tab syncs the URL slug | PASS |
| D-05 | UI | AC-C19 | The documents slug is linkable — navigating to it opens the tab | PASS |
| D-06 | UI | AC-C19 | Browser back steps off the Documents tab like any other tab | PASS |
| D-07 | UI | AC-C1 | The tab count tracks the record — a third document makes it 3, without opening the tab | PASS |
| D-08 | UI | AC-C2, AC-C1 | Rows list newest-first with name, kind, size, uploader, time and visibility | PASS |
| D-09 | UI | AC-C19, AC-C22 | Browsing the Documents tab on all three record types raises no console or API error | PASS |
| D-10 | UI | AC-C20, AC-C6 | The upload form states the allowed kinds and the size limit before a file is chosen | PASS |
| D-11 | UI | AC-C20, AC-C1 | Uploading through the file picker attaches the file and it appears in the list | PASS |
| D-12 | UI | AC-C20 | A slow upload shows progress while the bytes are going out | PASS |
| D-13 | UI | AC-C20 | Dropping a file on the Documents tab opens the form with that file ready to send | PASS |
| D-14 | UI | AC-C21, AC-C7 | An upload the server refuses leaves a specific message and the form open to retry | PASS |
| D-15 | UI | AC-C20, AC-C6 | A file over the 10 MB limit is refused in the form, before the bytes are sent | PASS |
| D-16 | UI | AC-C21 | A record with no documents shows an empty state, not a blank panel | PASS |
| D-17 | UI | AC-C22 | Without DOCUMENT_MANAGE the tab still lists documents and offers no Upload button | PASS |
| D-18 | UI | AC-C20 | A user without DOCUMENT_MANAGE is told why they cannot upload | FAIL |
| D-19 | UI | AC-C22, AC-C10 | Row actions without DOCUMENT_MANAGE are download-only — no Edit, no Remove | PASS |
| D-20 | UI | AC-C20, AC-C22 | Dropping a file does nothing for a user who may not upload | PASS |
| D-21 | UI | AC-C22 | The no-DOCUMENT_MANAGE session makes no request the server refuses | PASS |
| D-22 | UI | AC-C12, D7 | A self-service customer sees only SHARED documents on their own invoice | PASS |
| D-23 | UI | AC-C12, AC-C22 | A customer login gets no edit or delete control on a shared document | PASS |
| D-24 | UI | AC-C12 | The customer upload control (a documented deviation from AC-C12) works and lands SHARED | PASS |
| D-25 | UI | AC-C22, AC-C12 | The customer session makes no request the server refuses | PASS |
| D-26 | UI | AC-C1, AC-C13 | Download from the tab hands the browser the file under its original name | PASS |
| D-27 | UI | AC-C3, US-C5 | Remove asks for confirmation first, and backing out keeps the document | PASS |
| D-28 | UI | AC-C3, US-C5 | Confirming the removal says so, drops the row and stops the download | PASS |
| D-29 | UI | AC-C4, US-C6, D7 | Editing a document's description and visibility saves and shows on the row | PASS |
| D-30 | UI | AC-C21 | While the list is loading the tab shows a loading state, not an empty list | PASS |
| D-31 | UI | AC-C21 | A failed list shows the server's own message with a Try again, not a blank list | PASS |
| D-32 | UI | AC-C21 | Try again after a failed list reloads the documents | PASS |
| D-33 | UI | AC-C19, AC-C21 | At 400px the Documents tab and its rows fit without sideways overflow | PASS |
| D-34 | UI | AC-C20, AC-C21 | At 400px the upload and edit forms fit, with every control reachable | PASS |
| D-35 | UI | AC-C21 | The refused-upload message reads as a sentence, not with the field name glued in front | PASS |
| D-36 | UI | AC-C21 | Downloading a row that has since been removed reports the server's own reason | PASS |
| D-37 | UI | AC-C10, AC-C22 | Without DOCUMENT_VIEW the Documents tab is not offered, and its link shows nothing | PASS |
| D-38 | UI | AC-C4 | A document uploaded from the tab appears in the record's History tab | PASS |
| D-39 | UI | AC-C19, AC-C21 | After the rebuild, invoice detail and the Documents tab still render cleanly | PASS |

## E — Permissions, privileges and scoping

| Case | Kind | AC | Title | Status |
|---|---|---|---|---|
| E-01 | API | AC-A2 | Setting a customer's payment terms follows CUSTOMER_MANAGE for every seeded role | PASS |
| E-02 | API | AC-A2 | Invoice creation follows INVOICE_MANAGE, and the due date defaults from the customer's terms for every role that can create one | PASS |
| E-03 | API | AC-A3 | Overriding a due date needs INVOICE_MANAGE and nothing narrower (impl doc §1 answer 2) | PASS |
| E-04 | API | AC-B4 | GET /api/dashboard/outstanding-by-age needs INVOICE_VIEW; every role that holds it gets the new bucket labels | PASS |
| E-05 | API | AC-B4 | Ageing Coverage is unchanged in meaning per role: ALL / BOOK / OWN | PASS |
| E-06 | API | AC-B3 | The buckets a scoped caller gets add up to exactly the open balance that caller can list | PASS |
| E-07 | API | AC-A6 | filter=overdue:eq:true honours the existing scope rules for every role | PASS |
| E-08 | API | AC-C10 | Upload needs DOCUMENT_MANAGE plus the parent record's manage privilege, per role and per record kind | PASS |
| E-09 | API | AC-C10 | List and count need DOCUMENT_VIEW plus the parent record's view privilege | PASS |
| E-10 | API | AC-C10 | Download is authorized on every request and carries the AC-C13 headers | PASS |
| E-11 | API | AC-C10 | PATCH (description / visibility) needs DOCUMENT_MANAGE plus the record's manage privilege, and is never open to a customer login | PASS |
| E-12 | API | AC-C10 | DELETE needs DOCUMENT_MANAGE, then the record's manage privilege or being the uploader, and is never open to a customer login | PASS |
| E-13 | API | AC-C10 | The uploader stands in for the record's manage privilege on DELETE only — not on PATCH, and not for the document privilege | PASS |
| E-14 | API | AC-C22 | canEdit / canDelete on DocumentDto agree with what the API then allows | PASS |
| E-15 | API | AC-C12 | A self-service customer sees only SHARED documents on its own records, in list and in count | PASS |
| E-16 | API | AC-C12 | A self-service customer cannot download an INTERNAL document on its own record | PASS |
| E-17 | API | AC-C12 | A customer login may upload on its own records and the upload is forced SHARED — PRD deviation recorded in the implementation doc | PASS |
| E-18 | API | AC-A3 | The invoice CSV export requires EXPORT_DATA and INVOICE_VIEW together | PASS |
| E-19 | API | AC-A3 | The exported CSV carries the due date and the overdue flag for every role that may export | PASS |
| E-20 | API | AC-C10 | Escalation: document privileges alone reach nothing without the parent record's privilege | PASS |
| E-21 | API | AC-C10 | Escalation: the record's privilege alone reaches no document without DOCUMENT_VIEW | PASS |
| E-22 | API | AC-C10 | Escalation: a role without DOCUMENT_MANAGE calling upload and delete directly is refused | PASS |
| E-23 | API | AC-C12 | Escalation: a customer login is refused on every staff-only document endpoint | PASS |
| E-24 | API | AC-C11 | A customer login cannot reach another customer's documents by direct id | PASS |
| E-25 | API | AC-C11 | A POC cannot reach a record outside their book by direct document id | PASS |
| E-26 | API | AC-C11 | SALES_POC's empty payments book closes payment documents entirely | PASS |
| E-27 | API | AC-C3 | An unknown or soft-deleted document id answers 404, never 500 | PASS |
| E-28 | API |  | An expired token gets 401, not 500, on reads and on writes | PASS |
| E-29 | API |  | A malformed, wrongly-signed, unknown-user or absent token gets 401, not 500 | PASS |
| E-30 | CODE |  | DOCUMENT_VIEW and DOCUMENT_MANAGE are seeded idempotently — re-running duplicates nothing and drops nothing | PASS |
| E-31 | API |  | The privileges the seven seeded roles actually hold match DataSeeder exactly | PASS |
| E-32 | API |  | CASHIER retains everything it could do before this feature (PRD §8) | PASS |
| E-33 | DOC | AC-C10 | The implementation doc's matrix claims POC roles have document Manage, but each POC can only manage the one record kind its role can manage | FAIL |
| E-34 | DOC | AC-C11 | The implementation doc says POC document access is limited to 'their POC book'; two of the three POC roles are not book-limited at all | FAIL |
| E-35 | DOC |  | The implementation doc carries no role × capability matrix for Feature A and Feature B, which PRD §8 requires 'for both features' | FAIL |

## F — Blast radius: did anything that worked stop working

| Case | Kind | AC | Title | Status |
|---|---|---|---|---|
| F-01 | API |  | Invoice with several line items still saves every line | PASS |
| F-02 | API |  | Invoice total is the sum of its line totals, with paid 0 and balance = total | PASS |
| F-03 | API | AC-A3 | GET /api/invoices/{id} returns the same invoice, with the pre-existing fields intact | PASS |
| F-04 | API |  | Twelve invoices created concurrently get twelve distinct numbers (regression of D-05) | PASS |
| F-05 | API |  | Invoice numbers keep the INV-yyyyMMdd-NNNN shape and increase | PASS |
| F-06 | API |  | Cancelling an unpaid invoice sets CANCELLED and drops it out of the customer outstanding | PASS |
| F-07 | API |  | Cancelling an already-cancelled invoice is refused with a 400 | PASS |
| F-08 | API |  | Server-side pagination on the invoice list: page 0 and page 1 are disjoint and add up | PASS |
| F-09 | API |  | Server-side sorting on pre-existing columns (total asc/desc, invoiceDate) | PASS |
| F-10 | API |  | Server-side filtering on pre-existing columns (status, customerId, total gte, invoiceNumber contains) | PASS |
| F-11 | API | AC-A7 | Invoice summary tiles are computed server-side over the whole filtered set, not the page | PASS |
| F-12 | API |  | Tiles follow the filter chips, not just the customer | PASS |
| F-13 | API |  | Invoice create still rejects an empty item list and an unknown product | PASS |
| F-14 | API | AC-A3 | The pre-existing inline edit (notes, Sales POC) still works on an invoice | PASS |
| F-15 | API |  | A payment with no chosen invoices is allocated oldest invoice first | PASS |
| F-16 | API |  | The payment records its allocations and they add up to the amount | PASS |
| F-17 | API |  | An overpayment settles the rest and the excess becomes customer credit | PASS |
| F-18 | API |  | The next invoice raised for the customer consumes the credit balance | PASS |
| F-19 | API |  | Voiding a payment takes back exactly the money it put in | PASS |
| F-20 | API |  | A voided payment keeps amount = sum(allocations) + creditApplied at zero allocations | PASS |
| F-21 | API |  | A payment aimed at chosen invoiceIds goes to those invoices only | PASS |
| F-22 | API |  | A payment cannot be aimed at another customer's invoice | PASS |
| F-23 | API |  | Payments list still paginates, sorts and filters server-side | PASS |
| F-24 | API |  | Payment tiles are server-side over the filtered set and exclude voided money from "collected" | PASS |
| F-25 | API |  | Creating a customer still stores every pre-existing field and its self-service login | PASS |
| F-26 | API |  | Editing a customer saves the changed fields and leaves credit and outstanding alone | PASS |
| F-27 | API |  | Customer create still rejects a blank name and a malformed email | PASS |
| F-28 | API |  | The customer detail reports credit balance and outstanding from live invoice data | PASS |
| F-29 | API |  | Success and Collection POCs can still be assigned to a customer | PASS |
| F-30 | API |  | A second Collection POC can be added and made primary; the previous one stops being primary | PASS |
| F-31 | API |  | A POC seat can still be removed, and a user with no assignability marker is refused | PASS |
| F-32 | API |  | Customers list still paginates, sorts and filters server-side on the pre-existing columns | PASS |
| F-33 | API |  | Customer tiles are computed server-side over the filtered set | PASS |
| F-34 | API |  | Deleting a customer removes it and its self-service login | PASS |
| F-35 | API | AC-A10 | A promise against an unpaid invoice is still created OPEN | PASS |
| F-36 | API | AC-A10 | Paying the promised money flips the promise OPEN -> KEPT | PASS |
| F-37 | API | AC-A10 | A promise whose date has gone with nothing paid reads BROKEN | PASS |
| F-38 | API |  | Promises list, filter and summary tiles still work | PASS |
| F-39 | API |  | A customer can still raise a dispute on their own invoice | PASS |
| F-40 | API |  | A second open dispute on the same record is still refused | PASS |
| F-41 | API |  | Approving a dispute applies the proposed change and resolves it | PASS |
| F-42 | API |  | Denying a dispute resolves it and leaves the target untouched | PASS |
| F-43 | API |  | Disputes list still filters and paginates server-side, and a resolved dispute cannot be re-resolved | PASS |
| F-44 | API |  | billed-by-month returns the book total for the current month, cancelled invoices excluded | PASS |
| F-45 | API |  | Empty months are present as zero and the window length follows ?months | PASS |
| F-46 | API |  | collected-by-month reports the money that landed on the book this month | PASS |
| F-47 | API |  | top-outstanding-customers ranks by outstanding balance and excludes cancelled invoices | PASS |
| F-48 | API |  | top-paying-customers reports what each customer paid into the book | PASS |
| F-49 | API |  | The dashboard cards agree with the invoice and payment lists for the same scope | PASS |
| F-50 | API |  | The dashboard still rejects out-of-range months and limits | PASS |
| F-51 | API |  | A self-service customer sees only their own figures and no customer rankings | PASS |
| F-52 | API |  | The query framework still rejects a bad page size, an unknown sort column and a malformed filter | PASS |
| F-53 | API |  | A column that is declared not-sortable is still refused as a sort key | PASS |
| F-54 | API |  | The table-schema endpoint still describes every table the frontend builds filters from | PASS |
| F-55 | API |  | The customers CSV export still downloads the filtered rows | PASS |
| F-56 | API |  | The payments CSV export still downloads the filtered rows | PASS |
| F-57 | API |  | The products CSV export still downloads the filtered rows | PASS |
| F-58 | API |  | The users CSV export still downloads the filtered rows | PASS |
| F-59 | API |  | The roles CSV export still downloads the filtered rows | PASS |
| F-60 | API |  | The disputes CSV export still downloads the filtered rows | PASS |
| F-61 | API |  | The promises CSV export still downloads the filtered rows | PASS |
| F-62 | API |  | EXPORT_DATA alone does not open any CSV export (regression of D-02) | PASS |
| F-63 | API |  | The entity view privilege alone does not open a CSV export either | PASS |
| F-64 | API |  | A self-service customer exporting invoices gets only their own rows | PASS |
| F-65 | API |  | GET /api/emails for a record answers on the fresh 8083 database | PASS |
| F-66 | API |  | GET /api/inbox and /api/inbox/unread-count answer on 8083 | PASS |
| F-67 | API |  | The compose context and preview still answer for an invoice | PASS |
| F-68 | API |  | GET /api/emails/delivery reports the transport state instead of failing | PASS |
| F-69 | UI |  | Dashboard screen loads with no console error and no failed API call | PASS |
| F-70 | UI |  | Customers screen loads with no console error and no failed API call | PASS |
| F-71 | UI |  | Invoices screen loads with no console error and no failed API call | PASS |
| F-72 | UI |  | Invoice form screen loads with no console error and no failed API call | PASS |
| F-73 | UI |  | Payments screen loads with no console error and no failed API call | PASS |
| F-74 | UI |  | Promises screen loads with no console error and no failed API call | PASS |
| F-75 | UI |  | Disputes screen loads with no console error and no failed API call | PASS |
| F-76 | UI |  | Products screen loads with no console error and no failed API call | PASS |
| F-77 | UI |  | Users screen loads with no console error and no failed API call | PASS |
| F-78 | UI |  | Roles screen loads with no console error and no failed API call | PASS |
| F-79 | UI |  | Notifications screen loads with no console error and no failed API call | PASS |
| F-80 | UI |  | Customer detail loads with no console error and no failed API call | PASS |
| F-81 | UI |  | Invoice detail loads with no console error and no failed API call | PASS |
| F-82 | UI |  | No uncaught page error and no failed API call across the whole smoke run | PASS |
| F-83 | API |  | CASHIER can still create a customer, raise an invoice, record a payment and export | PASS |
| F-84 | API |  | Bulk CANCEL on invoices still cancels each named invoice | PASS |
| F-85 | API |  | Bulk REASSIGN_SALES_POC on invoices and ADD_POC on customers still work | PASS |
| F-86 | API |  | An invoice's History still records creation and the payments that land on it | PASS |
| F-87 | API |  | Notifications still list, count unread and mark read | PASS |
| F-88 | API |  | Products still create, edit, deactivate in bulk and delete | PASS |
| F-89 | API |  | Two payments recorded at the same instant on one customer never lose money | PASS |
| F-90 | API | AC-A3 | The invoice CSV keeps every pre-existing column and gains due date and overdue | PASS |
| F-91 | API |  | Sending an email about an invoice writes to the emails table on 8083 and reads back | PASS |
| F-92 | API |  | A self-service customer still sees only their own invoices and payments | PASS |
| F-93 | API |  | A self-service customer never sees POC identity on their own records | PASS |
| F-94 | API | AC-A2 | Patching only the Sales POC leaves the due date, terms, total and notes alone | PASS |
| F-95 | API |  | Approving a replace_items dispute recomputes the total and leaves the due date where it was | PASS |
| F-96 | API |  | Cancelling a part-paid invoice through a dispute refunds the money to customer credit | PASS |
| F-97 | API |  | The payment-scope change on collected-by-month shows a POC only money they can already reach | PASS |

## What was not tested

- No independent verifier pass. The 15-16 Sep run had every failure reproduced by a second agent before it was counted; this targeted run did not, though each tester reproduced its own failures (area C three times) before reporting.
- The InvoiceSchemaUpgrade backfill was reviewed by reading the source, not exercised against a populated database — the test database was empty, so it filled 0 rows. Its real behaviour was observed once, incidentally, on the user's own deployment during redeployment: 1,015 invoices backfilled to Net 30, then due_date set NOT NULL, with no change to status or paid_amount.
- UI coverage was rebuilt mid-run. Thirteen frontend files changed while testing was in progress, so areas B, D and F tested a bundle built at 00:10. After the rebuild only the invoice detail and Documents surfaces were re-exercised; the dashboard, customer, payment and promise screens were not re-tested against the newer bundle.
- Overdue reminder notifications (OverdueReminderSweepTest exists in the backend suite) were not exercised end to end. They were outside this run's areas, and PRD open question 3 had deferred them.
- AC-C14 (the scanner hook), AC-C15 and AC-C17 (H2 demo with no setup) were not covered by any area's cases.
- Content sniffing is magic-bytes only, so a %PDF- polyglot whose body is HTML and script is accepted. AC-C13's download headers neutralise it and those verified clean, but the residual risk is undocumented.

## Recommendations

- Fix D-01 before any deployment that upgrades an existing database. Everything else here can ship. Write the email backfill the way InvoiceSchemaUpgrade is written, and make failed DDL loud rather than a WARN nobody reads.
- Adopt Flyway. Four hand-written *SchemaUpgrade classes now exist (customer, email, invoice, poc), one of them incomplete, and nothing verifies that a given database has had them all applied. D-01 is what that costs.
- Add CI. 679 backend tests, 254 Flutter tests, flutter analyze and this suite all pass, and nothing enforces any of it. The repository still has no .github/workflows.
- Correct the implementation doc's role matrix (D-03, D-04) from data/role-matrix-observed.md, then decide separately whether CUSTOMER_SUCCESS_POC and COLLECTION_POC should reach INTERNAL documents outside their book.
- Resolve the AC-C20 / AC-C22 contradiction in the PRD itself (D-05), so the next implementer is not asked to satisfy both.
- Two product decisions surfaced by testing rather than by defect: a customer login can upload documents (the implementation doc records this as a deliberate departure from AC-C12), and PUT /api/customers/{id} silently clears a customer's payment terms when the field is omitted. Both behave correctly as built; both are worth a conscious yes.
