# Test results by area — regression run 15 Sep 2026

**Verdict:** Not ready for release: 12 high-severity defects are confirmed. They include two security holes (deactivated accounts keep API access, and every export skips the view privilege check) and a money-integrity bug where voiding a payment leaves credit the customer can still spend.

The automated suites were green before the manual regression: backend 123/123, flutter analyze clean, flutter test 19/19. The manual regression then ran 503 cases in 10 areas: 375 passed, 128 failed and none were blocked. After verification and merging duplicates there are 67 confirmed defects (12 high, 27 medium, 28 low); 8 reported failures were not accepted as defects. The core flows work: login and session expiry, the seeded 7-role privilege matrix, customer isolation, invoice and payment arithmetic and allocation, the promise lifecycle, the table framework contract on all 9 lists, History, and dashboards. Every regression fix on the list still holds. The blockers fall into three groups. Security: a deactivated user's token keeps full read/write access for 24 hours. Every export checks only EXPORT_DATA, so CASHIER and the POC roles can download every user's email and all roles, disputes and products. Customer logins can filter on POC columns and work out who their reps are. A SALES_POC can edit and cancel another rep's invoices by id. Money: voiding a payment after its money passed through customer credit leaves credit the customer can spend, or invoices marked paid with no payment behind them, and creating invoices at the same time fails with 500 duplicate-number errors. UI: one theme setting hides the dispute Approve/Deny buttons (a click anywhere on that row approves) and the Save changes button on Payment and Invoice details. Notes can't be saved when the POC is deactivated or the role lacks POC_ASSIGN, and the invoice and payment forms offer only the first 50 customers and products. Kept general promises flip to broken, promises lock once one of their invoices is cancelled, unsaved edits are lost without a prompt on sidebar or browser navigation, and on phones a long account label covers the hamburger menu. Much of the medium tier comes from a few shared causes: missing 400 handlers in GlobalExceptionHandler, the bulk resolveIds pattern that silently drops ids, table width that ignores the nav rail, and the PocPicker debounce. A handful of targeted fixes will therefore clear many cases. The green automated suites didn't catch these partly because BulkActionTest asserts the silent-drop behaviour and no test covers the security paths, so add regression tests before the re-run.

## Totals

| Area | Cases | Pass | Fail | Blocked | Confirmed defects | Health |
|---|---|---|---|---|---|---|
| Authentication, session, users, roles and privileges | 57 | 45 | 12 | 0 | 10 | poor |
| Customers, customer POC seats, and products | 59 | 46 | 13 | 0 | 11 | fair |
| Invoices | 49 | 34 | 15 | 0 | 13 | poor |
| Payments, allocation and customer credit | 55 | 42 | 13 | 0 | 12 | poor |
| Disputes and notifications | 49 | 38 | 11 | 0 | 9 | poor |
| Payment promises | 57 | 47 | 10 | 0 | 9 | poor |
| List/table framework across all 9 lists | 45 | 31 | 14 | 0 | 12 | fair |
| Permissions matrix, customer isolation and POC scoping | 48 | 36 | 12 | 0 | 9 | poor |
| Detail screens, History timeline, dashboard | 46 | 36 | 10 | 0 | 6 | fair |
| Screen-by-screen UI sweep (6 roles, 1366 and 400 px) | 38 | 20 | 18 | 0 | 15 | fair |
| **All** | **503** | **375** | **128** | **0** | **67** | |

Automated suites before the manual run: backend `mvn test` 123/123, `flutter analyze` clean, `flutter test` 19/19.

## Recommendations

1. Fix the security blockers before release. In JwtAuthFilter, reject disabled users (D-01). Require the entity's *_VIEW privilege plus its scope on every export (D-02). Add a single-record scope check for by-id reads and writes and seat edits (D-15). In TableQuery, reject POC-restricted columns for customer-scoped callers (D-08). Null the staff actor ids in customer DTOs (D-16). Gate USER/PRODUCT audit on their view privileges (D-17).
2. Fix money integrity. Tie credit use to the payment the money came from so a void reverses it exactly, or refuse the void (D-03). Generate invoice numbers from a DB sequence or a locked counter (D-05). Add @Digits and @DecimalMin on amounts (D-30). Validate replace_items quantities (D-32).
3. Change the FilledButton theme to minimumSize Size(64, 46) (D-04), then re-verify Approve/Deny and Save changes on every detail screen in a headed browser.
4. Harden GlobalExceptionHandler: 400 for HttpMessageNotReadable, MethodArgumentTypeMismatch and MissingServletRequestParameter, and 409 for DataIntegrityViolation. Stop echoing ex.getMessage() from the 500 handler. Add @Size on text DTOs and @Valid on PATCH bodies. This clears D-13, D-27 and D-29 and the raw SQL shown in dialogs.
5. Change the shared bulk resolveIds pattern to report excluded ids as skipped, and update BulkActionTest, which currently asserts the silent drop (D-14).
6. Frontend: add server-backed searchable customer and product pickers (D-06); fix the PocPicker debounce (D-18); send POC ids only when they changed (D-07); add a GoRoute.onExit guard for dirty detail screens (D-11); use int.tryParse in routes (D-25); size tables with LayoutBuilder and cap long-text cells (D-19, D-20); cap the account chip width (D-12); invalidate user-scoped providers on logout (D-22, D-57); allow selection when the role can export (D-21).
7. Promises: judge general promises on the balance at the promised date (D-09); allow edits when linked invoices are cancelled (D-10); allocate a shared payment only once across promises (D-34); block override on cancelled promises (D-33); validate the default Collection POC (D-36).
8. Add automated regression tests for each high defect: deactivated token → 401, export → 403 whenever the list is 403, concurrent invoice creation, void after credit use, customer POC filter → 400, and a widget test that Approve/Deny and Save changes lay out inside a Row. The current green suites don't cover these paths.
9. Optional polish that needs a product decision: show keptAmount on the dashboard Kept tile to match the Promises page (UIS-16/DASH-006), keep one 'Raise dispute' entry point (UIS-15), and decide whether SALES_POC should see payments and promises outside its book (PS-033).
10. After the fixes, re-run the full manual regression, including a headed-browser UI pass, tablet widths, and the untested gaps above.

## Not tested

- The bulk/export 5000-row truncation path (the truncated=true flag and the first-5000 window) was not exercised in any area; it needs more than 5000 matching rows in the shared database. D-14 notes that explicit ids beyond that window are also dropped.
- Promise transitions driven by the real clock (PromiseSweepScheduler, a stale PARTIALLY_KEPT after its date, notify-once racing a concurrent void) and non-UTC date boundaries were not tested; the server clock can't be advanced.
- Real 24h token expiry (only short-lived minted tokens were used) and login brute-force or rate limiting (not in the requirements) were not tested.
- The invisible-button defects (D-04) were seen only in headless Chrome/CanvasKit. The code analysis supports the root cause, but a headed-browser check has not been done. Customer Details has the same button pattern and was not specifically checked.
- Only 1366, 1920 and 400px web widths were covered. Tablet widths (760-900px, where the rail and drawer switch), 360px phones and native iOS/Android builds were not.
- CUSTOMER_SUCCESS_POC was left out of the UI sweep, and dashboards were not checked for VIEWER, CS_POC or custom roles.
- Many dialogs were only opened and cancelled in the UI sweep: Change password, the filter editor, promise Edit/Override/Cancel, New product/user/role, bulk-action and CSV dialogs. Bulk reassign through the UI was covered only through the API.
- Legacy records with a null POC (pocMissing > 0, isEmpty filters on real data) could not be created, because the API now requires a POC.
- Dispute update_amount effects on credit, two admins resolving the same dispute at once, and a custom role with DISPUTE_MANAGE that isn't named ADMIN (notifyAdmins targets the ADMIN role) were not tested.
- The unsaved-changes guard was not tested through the narrow-width drawer or browser Forward; it is expected to fail the same way as D-11.
- The Users form role picker cap at 50 could not be reproduced with fewer than 50 roles. Deleting a role from the UI couldn't be tested because the UI has no delete control.
- Screen-reader behaviour was checked only once (D-59).

## Invoices

I ran 49 cases: 32 on the API and 17 in the UI (admin, cashier, SALES_POC, a custom role with INVOICE_MANAGE but no POC_ASSIGN, VIEWER and CUSTOMER users). Every failure was reproduced a second time. The core invoice rules work: prices and totals, INV-yyyyMMdd-NNNN numbering, status computation, automatic use of customer credit, cancel rules, paging, sort on all 10 sortable columns, every filter type including date presets, summary tiles excluding cancelled invoices, bulk CANCEL and REASSIGN with per-record results, CSV export, and role checks. All the listed UI regressions are fixed: the Payment Promise tab loads with no 400, New invoice sits in the toolbar clear of pagination, Keep it closes only the dialog, Cancel invoice cancels, lists open unfiltered for admin, detail-to-detail navigation shows no stale values, and the History tab works. The most serious failure: creating invoices at the same time gives 500 duplicate-key errors, because the next number is a count+1 read with no lock (4 of 6 and 5 of 6 parallel creates failed). Two save paths are broken: a notes-only save fails in the UI on an invoice whose Sales POC was deactivated, and for any role with INVOICE_MANAGE but not POC_ASSIGN, because the client always resends the unchanged POC and the server re-validates it. The New invoice customer dropdown shows only the first 50 customers by name (111 exist). A SALES_POC without SCOPE_OVERRIDE can GET, PATCH and cancel another rep's invoice by id. Bulk actions silently drop ids that are unknown or outside the caller's scope. CUSTOMER users can filter on Sales POC columns, and the counts reveal POC identity. Smaller defects: several bad inputs return 500 instead of 400 (non-numeric id, notes over 500 characters, a huge page number); already-paid or cancelled rows in a bulk cancel are reported as failed instead of skipped; the POC picker search lags one keystroke; cancelled rows still offer Raise promise; #/invoices/abc shows a grey screen.

_Pricing, numbering format, status, credit use, cancel rules, sort/filter/tiles and the listed UI regressions pass. Blockers: concurrent creates return 500 (D-05), notes saves are blocked (D-07), the customer dropdown is capped at 50 (D-06), and SALES_POC can edit other reps' invoices by id (D-15). INV-028 was ruled not a defect._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| INV-001 | Create invoice | API | Create with default product price, items, status, Sales POC | PASS |  |
| INV-002 | Invoice numbering | API | Number format INV-yyyyMMdd-NNNN (UTC) and sequential increment | PASS |  |
| INV-003 | Create invoice | API | unitPrice override and zero price | PASS |  |
| INV-004 | Create validation | API | Negative price, unknown product, empty/missing items, qty 0, unknown customer/POC | PASS |  |
| INV-005 | Sales POC mandatory | API | Missing salesPocUserId rejected by the backend (AC-A2) | PASS |  |
| INV-006 | Sales POC mandatory | API | Unassignable, inactive, COLLECTION_POC or customer-account POC rejected | PASS |  |
| INV-007 | Status computation | API | UNPAID -> PARTIALLY_PAID -> FULLY_PAID from payments | PASS |  |
| INV-008 | Customer credit | API | Customer credit consumed automatically on create | PASS |  |
| INV-009 | Invoice detail API | API | GET /api/invoices/{id} returns items, balance, salesPoc; unknown id 404 | PASS |  |
| INV-010 | PATCH notes | API | PATCH notes saves and writes an INVOICE_UPDATED audit row | PASS |  |
| INV-011 | PATCH Sales POC | API | Reassign Sales POC: audited, new POC notified, invalid POC rejected | PASS |  |
| INV-012 | Cancel | API | Cancel an UNPAID invoice | PASS |  |
| INV-013 | Cancel | API | Cancel rejected when already cancelled, partially paid (credit), fully paid; unknown id | PASS |  |
| INV-014 | List paging | API | Paging, page disjointness, default sort, input validation (AC-D9) | PASS |  |
| INV-015 | List sort | API | Sort asc/desc on every sortable column; non-sortable rejected | PASS |  |
| INV-016 | List filters | API | status in, total between, date presets, date between, customerId, salesPocUserId eq/isEmpty/isNotEmpty | PASS |  |
| INV-017 | Summary tiles | API | Tiles over the filter: count, totals, outstanding excluding cancelled, per-status counts, pocMissingCount, zeros when empty | PASS |  |
| INV-018 | Bulk CANCEL | API | Mixed selection gives per-record result; selectAllMatchingFilter limited to the filter | PASS |  |
| INV-019 | Bulk REASSIGN_SALES_POC | API | Bulk reassign: success, audit, per-record failure, validation | PASS |  |
| INV-020 | Export CSV | API | Export selected ids and select-all over a filter | PASS |  |
| INV-021 | Permissions / scoping | API | VIEWER read-only, CASHIER creates, CUSTOMER sees own invoices without POC identity, no token 401 | PASS |  |
| INV-022 | POC scope | API | SALES_POC list locked to own book | PASS |  |
| INV-023 | Invoice numbering | API | Concurrent invoice creation fails with 500 duplicate invoice number | FAIL | high |
| INV-024 | Invoice Details – Notes save | API+UI | Notes-only save fails on an invoice whose Sales POC was deactivated | FAIL | high |
| INV-025 | Invoice Details – Notes save | API+UI | Role with INVOICE_MANAGE but not POC_ASSIGN cannot save notes | FAIL | medium |
| INV-026 | POC scope | API | SALES_POC without SCOPE_OVERRIDE can GET, PATCH and cancel another rep's invoice by id | FAIL | medium |
| INV-027 | Bulk actions | API | Bulk silently drops unknown or out-of-scope ids | FAIL | medium |
| INV-028 | Bulk CANCEL | API | Ineligible rows (paid / already cancelled) reported as failed, not skipped | FAIL | low |
| INV-029 | List filters / AC-A8 | API | CUSTOMER can filter and sort on POC-restricted columns, and counts reveal POC identity | FAIL | medium |
| INV-030 | PATCH / create validation | API | Notes longer than 500 characters return 500 | FAIL | low |
| INV-031 | Invoice detail API | API | Non-numeric invoice id returns 500 | FAIL | low |
| INV-032 | List paging | API | Huge page number gives 500; invalid sort direction silently accepted | FAIL | low |
| INV-033 | Invoices list UI | UI | Tiles, New invoice in the toolbar, pagination not covered (regression) | PASS |  |
| INV-034 | Invoices list UI | UI | Phone width (400px) layout | PASS |  |
| INV-035 | New Invoice form | UI | Admin: Sales POC preselected, lines and totals, save | PASS |  |
| INV-036 | New Invoice form | UI | SALES_POC user sees themself preselected | PASS |  |
| INV-037 | New Invoice form | UI | Cashier (not assignable): Sales POC starts empty and blocks submission | PASS |  |
| INV-038 | Invoice Details | UI | Header figures, read-only line items, edit Notes and Sales POC, Save | PASS |  |
| INV-039 | Invoice Details – History | UI | History tab lists changes newest first | PASS |  |
| INV-040 | Invoice Details – Payment Promise tab | UI | Payment Promise tab loads without error (regression of the invoiceId fix) | PASS |  |
| INV-041 | Row Cancel invoice dialog | UI | Keep it closes only the dialog; Cancel invoice cancels (regression) | PASS |  |
| INV-042 | Bulk cancel UI | UI | Bulk cancel states the exact count and shows a per-record result | PASS |  |
| INV-043 | Export UI | UI | Export selected opens the CSV dialog | PASS |  |
| INV-044 | Invoice Details | UI | Detail-to-detail navigation shows no stale state; unknown id shows a not-found state | PASS |  |
| INV-045 | New Invoice form | UI | Customer dropdown capped at the first 50 customers by name | FAIL | high |
| INV-046 | Sales POC picker | UI | POC picker search lags one keystroke behind | FAIL | medium |
| INV-047 | Invoices list row actions | UI | Cancelled invoices still offer 'Raise promise' | FAIL | low |
| INV-048 | Invoice Details routing | UI | Malformed detail URL renders a blank grey screen | FAIL | low |
| INV-049 | Invoices list filter chips | UI | Customer filter chip shows the numeric id instead of the name | FAIL | low |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| INV-023 | yes | high | backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:146-150. nextInvoiceNumber() computes countByInvoiceNumberStartingWith(prefix)+1 with no lock, sequence or retry, so concurrent transactions read the same |
| INV-024 | yes | high | frontend/lib/features/invoices/invoice_detail_screen.dart:79 always resends the unchanged salesPocUserId. backend InvoiceService.java:106-107 calls pocService.requireAssignable() (PocService.java:61-63 rejects inactive u |
| INV-025 | yes | medium | backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:78-80 rejects any non-null salesPocUserId when the caller lacks POC_ASSIGN, even if it equals the current POC. frontend invoice_detail_screen.dart:79 a |
| INV-026 | yes | medium | backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:152-161: get() only enforces the customer restriction. update() (line 102) and cancel() (line 226) use getInternal() (163-167) with no ScopeResolver book  |
| INV-027 | yes | medium | backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:154: resolveIds() filters req.ids() with permitted::contains before BulkExecutor.run, so excluded ids never reach the result. The same pattern exists i |
| INV-028 | yes | low | backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:227-232: cancel() throws BadRequestException for the ineligible states. BulkExecutor.java:49-52 only puts BulkExecutor.IneligibleException into skipped, a |
| INV-029 | yes | medium | backend/src/main/java/com/geneinvoice/common/query/TableSchema.java:32-50: requireSortable/requireFilterable never check ColumnDef.pocRestricted. TableQuery.parse (TableQuery.java:40, 48) and TableQueryExecutor.predicate |
| INV-030 | yes | low | backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:18 and :26 have no @Size(max=500) on notes (and update is not @Valid). Invoice.java:53 is length=500, so Postgres rejects the row. GlobalExceptionHandler.jav |
| INV-031 | yes | low | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-63: there is no handler for MethodArgumentTypeMismatchException (or other framework 4xx exceptions), so the catch-all Exception handler returns  |
| INV-032 | yes | low | backend/src/main/java/com/geneinvoice/common/query/TableQueryExecutor.java:50: setFirstResult(query.page() * query.size()) overflows int. TableQuery.java:23 only checks page >= 0. TableQuery.java:39 treats any direction  |
| INV-045 | yes | high | frontend/lib/features/customers/customers_screen.dart:19-26: allCustomersProvider requests /api/customers with size 50 and sort name,asc and never pages. The backend caps size at 50 (TableQuery.MAX_SIZE/ALLOWED_SIZES). I |
| INV-046 | yes | medium | frontend/lib/features/poc/poc_picker.dart:44-48: the debounce timer updates _search via the outer State's setState, which does not rebuild the dialog route. At 120-122, onChanged calls setDialogState() immediately, befor |
| INV-047 | yes | low | frontend/lib/features/invoices/invoices_screen.dart:166: `if (canPromise && inv.balance > 0)` ignores status, and a cancelled invoice keeps balance = total − paid (Invoice.getBalance, Invoice.java:75-77). |
| INV-048 | yes | low | frontend/lib/core/router.dart:88: `id: int.parse(s.pathParameters['id']!)` throws a FormatException inside the route builder, and the release build renders Flutter's grey ErrorWidget. The same pattern is at router.dart:6 |
| INV-049 | yes | low | frontend/lib/core/table/filter_editor.dart:333-341: describeFilter() renders '$label ${operatorLabel} ${values.join(', ')}' from the raw wire values. TableFilter (core/table/table_models.dart:6-13) stores only field/oper |

Not tested here: pocMissingCount > 0 and salesPocUserId:isEmpty on real POC-missing invoices: the API makes the Sales POC mandatory and the fresh rt database has no legacy invoices, so I could only check that the filter and tile return 0 and 200 correctly.; Bulk and export truncation at 5000 rows (the truncated flag and the 'first 5000' drop): it would need more than 5000 invoices in the shared database.; Unsaved-changes guard on in-app navigation away from Invoice Details (AC-C3): not exercised.; Line-item changes and refunds through disputes (replaceItems / cancelWithRefund): left to the Disputes area.; Date presets 'yesterday' and 'thisYear': not asserted separately; the other presets were.; The Copy-to-clipboard button in the export dialog: the headless browser has no clipboard.; Native iOS/Android builds: only Flutter web at 1366, 1920 and 400 px widths.

## Customers, customer POC seats, and products

I ran 58 cases against http://localhost:8083 and :8084, using only my own customers, staff, roles and products (prefixes cp-, cprr-, cpui-, zzzcp-). Every failure was re-run once with fresh data (rerun.js) and reproduced. The core features work. Customer create, read, update, password change and audit all behave. So do paging, sort and filters (text operators and the seat filters, including isEmpty for a missing POC), the filter-aware summary tiles, bulk ADD_POC with per-record results, and CSV export. POC seats keep exactly one primary per type, removing the primary promotes the next holder, assignability follows the POC_ASSIGNABLE_* privilege, and seat changes show in History. Product create, update, validation, bulk and permissions also pass. All three regressions pass in the UI: the unsaved-changes dialog (Keep editing stays on the page, Discard leaves without saving), the POC Remove dialog (Keep closes only the dialog and sends no DELETE, Remove sends the DELETE and the chip disappears), and the New customer button sitting in the toolbar rather than over the pager. The most important failures are these. The invoice form's customer and product pickers load only the first 50 rows by name, so later customers and products can't be invoiced from the UI (high). The POC picker search doesn't filter until one more key is pressed (medium). Bulk requests silently drop ids outside the permitted set (medium). Bad enum or number input returns 500 instead of 400 on /api/pocs/assignable, bulk ADD_POC params and JSON bodies (medium). Lower-severity issues: automatic primary changes aren't audited; an invalid bulk request is reported as every row skipped; a missing POC_ASSIGN gives 400 instead of 403; product export ignores the requested sort; the backend accepts inactive products on invoices; the "Name is required" message stays after the name is retyped; and the POC-missing badge is clipped on phone-width cards.

_Customer and product CRUD, seat primary rules, seat filters, tiles, bulk ADD_POC and the three UI regressions all pass. The invoice-form pickers are capped at 50 (D-06, high). The rest are input-validation 500s, the POC picker lag, and audit and bulk reporting gaps._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| CUS-001 | Customer create | API | Create customer also creates a CUSTOMER self-service login | PASS |  |
| CUS-002 | Customer create validation | API | Blank name, missing username or missing password rejected with fieldErrors; nothing persisted | PASS |  |
| CUS-003 | Customer create validation | API | Duplicate username rejected, including the seeded 'admin' username; no orphan customer | PASS |  |
| CUS-004 | Customer GET by id | API | GET /api/customers/{id} returns the customer; unknown id returns 404 | PASS |  |
| CUS-005 | Customer scoping / AC-A8 | API | Customer login sees only its own record, without POC data | PASS |  |
| CUS-006 | Customer update | API | PUT updates contact fields, syncs the login, and changes the password only when given | PASS |  |
| CUS-007 | Customer update validation | API | PUT with blank name gives 400 and changes nothing; PUT on unknown id gives 404 | PASS |  |
| CUS-008 | Customer audit | API | Create and updates are written to the customer's audit trail with the actor | PASS |  |
| CUS-009 | Customer permissions | API | CASHIER (CUSTOMER_MANAGE) can create; VIEWER gets 403 on create and update | PASS |  |
| CUS-010 | Customer list sort | API | Sort by name, phone, email, outstanding and creditBalance (asc/desc) | PASS |  |
| CUS-011 | Customer list paging | API | Paging envelope and server-side validation (AC-D9) | PASS |  |
| CUS-012 | Customer list filters | API | Text operators contains/eq/neq/isEmpty/isNotEmpty combine with AND; invalid input gives 400 | PASS |  |
| TIL-001 | Customer summary tiles | API | GET /api/customers/summary computes tiles over the filtered set | PASS |  |
| TIL-002 | Customer summary tiles | API | Tiles follow extra filters, show zeros for an empty result, and reject bad filters | PASS |  |
| POC-001 | POC seats — add | API | Several SUCCESS POCs on one customer, with exactly one primary | PASS |  |
| POC-002 | POC seats — COLLECTION and set primary | API | COLLECTION seats are independent of SUCCESS; POST .../{pocId}/primary switches the primary | PASS |  |
| POC-003 | POC seats — remove | API | Removing a non-primary keeps the primary; removing the primary promotes another; removing the last clears it | PASS |  |
| POC-004 | POC seats — validation | API | Invalid seat adds are rejected and nothing is created | PASS |  |
| POC-005 | POC seats — ownership | API | A pocId belonging to another customer is rejected; an unknown pocId gives 404 | PASS |  |
| POC-006 | POC seats — permissions | API | Customer login gets 403 on seats and assignable; VIEWER can read seats but not change them | PASS |  |
| POC-007 | Assignable users | API | /api/pocs/assignable returns only active users whose role carries the matching privilege; search works; a custom role counts | PASS |  |
| POC-008 | Assignable users — validation | API | /api/pocs/assignable with a bad or missing type returns 500 instead of 400 | FAIL | medium |
| POC-009 | POC audit | API | Explicit seat changes are audited on the customer with before/after and actor | PASS |  |
| POC-010 | POC audit | API | Implicit primary changes (auto-promotion on remove, demotion on add-as-primary) are not audited | FAIL | low |
| POC-011 | POC assignment notification | API | A newly assigned POC receives a POC_ASSIGNED notification linking to the customer | PASS |  |
| POC-012 | Seat filters | API | successPocUserId / collectionPocUserId filters: eq, isEmpty (POC missing), isNotEmpty, neq, in | PASS |  |
| BLK-001 | Bulk ADD_POC | API | Bulk ADD_POC reports a result per record | PASS |  |
| BLK-002 | Bulk ADD_POC select-all | API | selectAllMatchingFilter applies to the whole filtered set and the tiles update | PASS |  |
| BLK-003 | Bulk ADD_POC validation | API | Bulk ADD_POC with pocType=foo or userId=abc returns 500 | FAIL | medium |
| BLK-004 | Bulk ADD_POC validation | API | An invalid bulk request (pocType SALES, inactive user) reports every row 'skipped' instead of one 400 | FAIL | low |
| BLK-005 | Bulk request validation | API | Unknown action, missing ids and missing params rejected | PASS |  |
| BLK-006 | Bulk ADD_POC permission | API | A role with CUSTOMER_MANAGE but no POC_ASSIGN gets 400, not 403, on bulk ADD_POC | FAIL | low |
| BLK-007 | Bulk — accountability | API | An id outside the permitted set is dropped silently from the bulk result | FAIL | medium |
| EXP-001 | Customer export | API | CSV export by ids and by filter+sort; permission check | PASS |  |
| VAL-001 | Input validation (customers/POCs/products) | API | Malformed JSON body values return 500 instead of 400 | FAIL | medium |
| PRD-001 | Product create | API | Create product; price >= 0 enforced; name and price required | PASS |  |
| PRD-002 | Product update | API | PUT persists name/description/price, keeps active when omitted, validates, and audits | PASS |  |
| PRD-003 | Product list | API | Sort by price, filter active and price between; invalid sort or operator gives 400 | PASS |  |
| PRD-004 | Product bulk | API | Bulk DEACTIVATE and ACTIVATE with per-record results and audit | PASS |  |
| PRD-005 | Product export | API | Product CSV export ignores the requested sort | FAIL | low |
| PRD-006 | Product permissions | API | CASHIER can list but not create or bulk-change; COLLECTION_POC has no product access | PASS |  |
| PRD-007 | Inactive products vs invoices (backend) | API | The backend accepts an INACTIVE product on a new invoice | FAIL | low |
| UI-001 | Customers list UI | UI | Customers list shows filter-aware tiles, and New customer sits in the toolbar, not over pagination | PASS |  |
| UI-002 | Customers list UI (phone width) | UI | At 400px the toolbar and pager work, but the POC-missing badge is clipped under the row icon | FAIL | low |
| UI-003 | Create customer dialog | UI | Empty submit shows Required on Name, Username and Password | PASS |  |
| UI-004 | Create customer dialog | UI | Creating a customer through the dialog persists all fields and a working login | PASS |  |
| UI-005 | Create customer dialog | UI | A duplicate username shows the server error in the dialog and keeps it open | PASS |  |
| UI-006 | Customer Details edit | UI | Editing Name/Phone/Email/Address and tapping Save changes persists them | PASS |  |
| UI-007 | Customer Details edit | UI | An empty name shows an error on the field and nothing is sent | PASS |  |
| UI-008 | Customer Details edit | UI | The 'Name is required' error stays after a valid name is typed | FAIL | low |
| UI-009 | Unsaved-changes guard (regression) | UI | Back with unsaved edits asks Discard/Keep editing, and both buttons behave | PASS |  |
| UI-010 | POC editor — add and set primary | UI | Add two Customer Success POCs through the picker and switch the primary by tapping a chip | PASS |  |
| UI-011 | POC editor — remove confirm (regression) | UI | Remove dialog: Keep closes only the dialog; Remove calls the API and the chip disappears | PASS |  |
| UI-012 | POC picker search | UI | The picker doesn't filter after typing until one more key is pressed | FAIL | medium |
| UI-013 | Customer History tab | UI | POC changes are visible in the customer's History tab | PASS |  |
| UI-014 | Products screen — create/edit | UI | Create a product with validation, then edit its price and switch Active off | PASS |  |
| UI-015 | Products screen — bulk deactivate | UI | Selecting a row shows the bulk toolbar; Deactivate works | PASS |  |
| UI-016 | Inactive products hidden on invoices | UI | The New invoice product dropdown doesn't offer inactive products | PASS |  |
| UI-017 | Customer/product pickers (invoice form) | UI | The invoice form loads only the first 50 customers and products by name; later ones can't be chosen | FAIL | high |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| POC-008 | yes | medium | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-64: the catch-all @ExceptionHandler(Exception.class) maps every exception with no specific handler to 500, and there is no handler for MethodArg |
| POC-010 | yes | low | backend/src/main/java/com/geneinvoice/poc/PocService.java:148-155: remove() promotes the oldest remaining seat and saves it without calling auditService. :187-195: clearPrimary(), called from add() at :113, demotes the p |
| BLK-003 | yes | medium | backend/src/main/java/com/geneinvoice/customer/CustomerController.java:136: PocType.valueOf(type.toUpperCase()) throws IllegalArgumentException. common/bulk/BulkDtos.java:30: Long.valueOf(v.toString()) throws NumberForma |
| BLK-004 | yes | low | backend/src/main/java/com/geneinvoice/customer/CustomerController.java:138-144: the lambda catches every BadRequestException from pocService.add() and rethrows it as BulkExecutor.IneligibleException (skipped). The commen |
| BLK-006 | yes | low | backend/src/main/java/com/geneinvoice/customer/CustomerController.java:128-130: the POC_ASSIGN check inside bulk() throws BadRequestException, not AccessDeniedException. The single-seat endpoint is guarded by @PreAuthori |
| BLK-007 | yes | medium | backend/src/main/java/com/geneinvoice/customer/CustomerController.java:184 and product/ProductController.java:163 (the same resolveIds pattern in the other controllers): req.ids().stream().filter(permitted::contains) dis |
| VAL-001 | yes | medium | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-64: HttpMessageNotReadableException has no handler, so the catch-all Exception handler returns 500 with ex.getMessage(). The same root cause as  |
| PRD-005 | yes | low | backend/src/main/java/com/geneinvoice/product/ProductController.java:144: repository.findAllById(ids) issues WHERE id IN (...) with no ORDER BY, which discards the order of the ids that resolveIds (:155-164) computed wit |
| PRD-007 | yes | low | backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java: create() (~line 69-70) and replaceItems() (~line 282-283) load the product with productRepository.findById(...).orElseThrow(NotFound) and never check p.i |
| UI-002 | yes | low | frontend/lib/features/customers/customers_screen.dart:107-117: the Name cell is Row(mainAxisSize: min, [Text(name), Padding(PocMissingBadge)]) with no Flexible/Wrap and no ellipsis. core/table/data_table_scaffold.dart:33 |
| UI-008 | yes | low | frontend/lib/features/customers/customer_detail_screen.dart:213-215: the Name TextField's onChanged only does setState(() => _dirty = true) and never removes _fieldErrors['name']. The error is cleared only at the start o |
| UI-012 | yes | medium | frontend/lib/features/poc/poc_picker.dart:44-49: the 250ms debounce calls setState on the outer _PocPickerState. That does not rebuild the dialog route created by showDialog (:104-178). The dialog reads _search only when |
| UI-017 | yes | high | frontend/lib/features/customers/customers_screen.dart:19-26: allCustomersProvider fetches size 50 sorted by name and ignores totalElements. frontend/lib/features/products/products_screen.dart:15-22: productsProvider does |

Not tested here: Collection POC editing in the UI: only the Customer Success editor was driven in the browser. Collection seats were covered through the API (POC-002/003/012).; POC-scoped roles (SALES_POC locked book, CUSTOMER_SUCCESS_POC, COLLECTION_POC) on the Customers list and tiles, and export/bulk reachability for roles without *_MANAGE (candidate 12). These belong to the scoping/permissions area.; Leaving Customer Details by browser back/forward or the sidebar with unsaved edits (candidate 15). Only the in-page Back button was tested.; The CSV export dialog in the UI (copyable dialog). Export was verified through the API only.; Bulk/export truncation at the 5000-row limit (would need 5000+ customers).; DELETE /api/customers/{id} and DELETE /api/products/{id}: not part of the listed scope and not exposed in the UI.; Non-admin customer creation from the UI (e.g. CASHIER or CUSTOMER_SUCCESS_POC). Covered through the API for CASHIER only.

## Authentication, session, users, roles and privileges

I ran 57 cases: auth API (login, /me, change-password, 401 vs 403), Users API (list, paging, sort, filter, create, update, delete-vs-deactivate, bulk, export), Roles and Privileges API (including a cell-by-cell check of the seeded 7-role x 27-privilege matrix against design notes §2, which matches exactly), and the Flutter UI (login, change-password dialog, logout, session expiry while the app is open, per-role sidebar for 7 roles, Users and Roles screens). The core flows and the listed regressions all pass: expired, garbage, unknown-user and wrong-signature tokens give 401 and 403 still means a missing privilege; an expired session returns the open app to the login screen; deleting a user named as a POC deactivates them instead; bulk ACTIVATE/DEACTIVATE reports per-record results; and every role's sidebar matches its privileges. There are 3 high-severity failures. First, a deactivated user's existing JWT keeps full read and write API access, because JwtAuthFilter never checks isEnabled. Second, the Users form sends an empty email as "", and email is unique, so a user without an email can no longer be edited (or re-activated, or given a new role) from the UI; the dialog shows a raw 500 SQL error. Third, CASHIER and SALES_POC can export the whole user table (usernames, emails, roles) and the roles table through /export, which checks only EXPORT_DATA. Medium failures: a duplicate email, a duplicate role name and deleting a role still in use all return 500 with raw SQL constraint text, which the UI shows to the admin. Low failures: a non-existent id is silently dropped from bulk results, a username over 80 characters gives 500, an admin can set a 1-character password, deleting an unknown role returns 200, the export ignores the requested sort, and the table's rightmost columns are clipped at 1366px. Every failure was re-run once and reproduced; scripts and screenshots are under /private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/auth-users-roles/.

_Login, 401/403 semantics, session expiry, the POC delete-deactivates path and the role matrix all pass. Blockers: deactivated users keep their token access (D-01), and users/roles export leaks to CASHIER and the POC roles (D-02). Admin CRUD shows raw SQL 500s and cannot edit users who have no email._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| AUR-001 | Auth / login | API | Login with valid credentials returns token and user info | PASS |  |
| AUR-002 | Auth / login | API | Wrong password, unknown user and blank fields rejected | PASS |  |
| AUR-003 | Auth / deactivated user | API | Deactivated user cannot log in; reactivated user can | PASS |  |
| AUR-004 | Auth / me | API | GET /api/auth/me returns the caller | PASS |  |
| AUR-005 | Auth / token validation (regression: expired session gave 403) | API | Missing, garbage, expired, wrong-signature, unknown-user and Basic tokens give 401 | PASS |  |
| AUR-006 | Authorization | API | Signed-in user lacking a privilege gets 403 | PASS |  |
| AUR-007 | Auth / change password | API | Change-password validation | PASS |  |
| AUR-008 | Auth / change password | API | Change-password success; old password rejected, new accepted | PASS |  |
| AUR-009 | Auth / deactivated user session | API | Deactivated user keeps full API access through an existing JWT | FAIL | high |
| AUR-010 | Auth / deleted user session | API | Hard-deleted user's existing token is rejected | PASS |  |
| AUR-011 | Users / list | API | Users list default envelope | PASS |  |
| AUR-012 | Users / paging | API | Paging over own 11 users; invalid size and page rejected | PASS |  |
| AUR-013 | Users / sort and filter | API | Sort and filter work; invalid column or operator rejected | PASS |  |
| AUR-014 | Users / create validation | API | Create-user validation | PASS |  |
| AUR-015 | Users / duplicate username | API+UI | Duplicate username rejected with a clear message | PASS |  |
| AUR-016 | Users / duplicate email | API+UI | Duplicate email on create or update gives 500 with raw SQL shown to the admin | FAIL | medium |
| AUR-017 | Users / edit form (empty email) | API+UI | User without an email cannot be edited or re-activated from the UI; blank-email create fails | FAIL | high |
| AUR-018 | Users / create validation | API | Username longer than 80 characters gives 500 | FAIL | low |
| AUR-019 | Users / create | API | Create user happy path, get by id, unknown id | PASS |  |
| AUR-020 | Users / update | API | Update email, name, password and role; audit row; blank password keeps the old one | PASS |  |
| AUR-021 | Users / update password policy | API | Admin can set a 1-character password via PUT /api/users/{id} | FAIL | low |
| AUR-022 | Users / delete | API | Delete a plain user | PASS |  |
| AUR-023 | Users / delete a POC (AC-A5) | API | Deleting a user named as an invoice Sales POC deactivates instead | PASS |  |
| AUR-024 | Users / delete a POC (AC-A5) | API | Deleting a user holding a customer POC seat deactivates them and drops them from the dropdown | PASS |  |
| AUR-025 | Users / bulk | API | Bulk DEACTIVATE gives per-record results, self-skip and dedupe | PASS |  |
| AUR-026 | Users / bulk | API | Bulk ACTIVATE; login gated on active | PASS |  |
| AUR-027 | Users / bulk validation | API | Bulk validation errors | PASS |  |
| AUR-028 | Users / bulk | API | A non-existent id is silently dropped from the bulk result | FAIL | low |
| AUR-029 | Users / bulk select-all | API | selectAllMatchingFilter acts only on filtered rows | PASS |  |
| AUR-030 | Users / export | API | Export users CSV | PASS |  |
| AUR-031 | Users / export | API | Export ignores the requested sort | FAIL | low |
| AUR-032 | Permissions / export | API | CASHIER and SALES_POC can export the whole user table and roles without USER_VIEW or ROLE_VIEW | FAIL | high |
| AUR-033 | Privileges | API | GET /api/privileges lists the 27 canonical privileges | PASS |  |
| AUR-034 | Roles / list | API | Roles list, filter and non-sortable column | PASS |  |
| AUR-035 | Roles / seeded matrix | API | Seeded role x privilege matrix equals design notes §2 exactly | PASS |  |
| AUR-036 | Roles / create | API | Create a custom role; validation | PASS |  |
| AUR-037 | Roles / update | API | Update a role; an existing session picks up the new privileges; unknown 404 | PASS |  |
| AUR-038 | Roles / duplicate name | API+UI | Duplicate role name (create or rename) gives 500 with raw SQL, shown in the UI | FAIL | medium |
| AUR-039 | Roles / delete in use | API | Deleting a role still assigned to a user gives 500 | FAIL | medium |
| AUR-040 | Roles / delete | API | Delete an unused custom role | PASS |  |
| AUR-041 | Roles / delete | API | Deleting a non-existent role returns 200 | FAIL | low |
| AUR-042 | Roles / zero privileges | API | User on a role with zero privileges | PASS |  |
| AUR-043 | Roles / export | API | Roles export CSV as admin | PASS |  |
| AUR-044 | UI / login screen | UI | Login validation, wrong password and disabled-account messages | PASS |  |
| AUR-045 | UI / login screen | UI | Successful sign-in lands on the dashboard | PASS |  |
| AUR-046 | UI / change password dialog | UI | Account menu → Change password dialog validation | PASS |  |
| AUR-047 | UI / change password dialog | UI | Change password success from the UI | PASS |  |
| AUR-048 | UI / logout | UI | Logout returns to login and clears the session | PASS |  |
| AUR-049 | UI / session expiry (regression) | UI | A session that expires while the app is open returns to the login screen | PASS |  |
| AUR-050 | UI / session bootstrap | UI | Booting with an expired or garbage stored token lands on login | PASS |  |
| AUR-051 | UI / 403 handling | UI | A 403 does not sign the user out | PASS |  |
| AUR-052 | UI / sidebar per role | UI | Sidebar shows only the screens each role may use | PASS |  |
| AUR-053 | UI / Users screen | UI | Users list, New user button in the toolbar, create form validation and create | PASS |  |
| AUR-054 | UI / Users screen | UI | Edit user via row click; Active switch; role preselected | PASS |  |
| AUR-055 | UI / Users screen bulk | UI | Deactivate via row selection and bulk action | PASS |  |
| AUR-056 | UI / Roles screen | UI | Create a role with chosen privileges and edit its privileges | PASS |  |
| AUR-057 | UI / list layout | UI | Rightmost table columns and row actions clipped at 1366px on Users and Roles | FAIL | low |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| AUR-009 | yes | high | backend/src/main/java/com/geneinvoice/auth/JwtAuthFilter.java:35-40 loads UserDetails on every request and sets the authentication without checking userDetails.isEnabled(). AppUserDetails.isEnabled() (AppUserDetails.java |
| AUR-016 | yes | medium | backend/src/main/java/com/geneinvoice/user/UserController.java:106-123: create checks existsByUsername (line 107) but not email, although UserRepository.existsByEmail exists. update at :131 sets the email with no uniquen |
| AUR-017 | yes | medium | frontend/lib/features/users/users_screen.dart:160 (create) and :168 (update) always send 'email': _email.text.trim(), which is '' when the field is empty. backend/src/main/java/com/geneinvoice/user/UserController.java:11 |
| AUR-018 | yes | low | backend/src/main/java/com/geneinvoice/user/UserController.java:67-74: CreateUserRequest.username has only @NotBlank, with no @Size(max = 80) matching User.username length = 80 (User.java:22). Email (length 120) and fullN |
| AUR-021 | yes | low | backend/src/main/java/com/geneinvoice/user/UserController.java:133 only checks non-blank before encoding. CreateUserRequest.password (:71) has only @NotBlank. AuthController.java:73 enforces length >= 6 on change-passwor |
| AUR-028 | yes | low | backend/src/main/java/com/geneinvoice/user/UserController.java:233 filters req.ids() through permitted::contains before calling BulkExecutor, so ids that are unknown (or out of scope) disappear before BulkExecutor.run ca |
| AUR-031 | yes | low | backend/src/main/java/com/geneinvoice/user/UserController.java:213: resolveIds returns ids in the requested order (TableQueryExecutor.ids applies orderBy, TableQueryExecutor.java:79), but userRepository.findAllById(...)  |
| AUR-032 | yes | high | backend/src/main/java/com/geneinvoice/user/UserController.java:210-211 (@PreAuthorize EXPORT_DATA only) and role/RoleController.java:64-65 (same). Neither export also requires the entity's view privilege, and resolveIds  |
| AUR-038 | yes | medium | backend/src/main/java/com/geneinvoice/role/RoleController.java:96-101 (create) and :106-111 (update) save without a name uniqueness check. The unique-constraint violation becomes a 500 with the raw message in common/Glob |
| AUR-039 | yes | medium | backend/src/main/java/com/geneinvoice/role/RoleController.java:116-117: delete calls roleRepository.deleteById(id) with no check for users holding the role. The FK violation becomes a raw 500 via GlobalExceptionHandler.j |
| AUR-041 | yes | low | backend/src/main/java/com/geneinvoice/role/RoleController.java:116-117: Spring Data deleteById silently ignores a missing id, and no findById check precedes it, unlike GET/PUT (:90, :107) and UserController.delete (:155) |
| AUR-057 | yes | medium | frontend/lib/core/table/data_table_scaffold.dart:240 sets BoxConstraints(minWidth: MediaQuery.sizeOf(context).width - 24), i.e. the full window width. The table lives in Expanded(child) next to the ~257px extended Naviga |

Not tested here: User-form role picker capped at 50 (candidate 13, possible ADMIN preselect for a role outside the first 50): only 39-41 roles exist and VIEWER is at index 38, so it can't be reproduced now. I didn't add 10+ roles to force it because they would crowd other testers' role pickers. The code path (users_screen.dart orElse: list.first) is unchanged.; Real 24-hour token expiry: only covered with short-lived tokens from rt.mintToken.; Narrow or mobile layout (drawer navigation, card lists) for the Users and Roles screens: all UI runs were at 1366x900.; Deleting a role from the UI: the Roles screen has no delete control, so role delete was tested through the API only.; Bulk self-skip was tested with my own user-manager account, not the seeded admin, per the rules. The seeded admin and cashier were only used to sign in and read.; Hard delete of a customer-linked login or a user referenced only by audit rows (possible FK side effects) was not tried.; Brute-force lockout and rate limiting on login: not in the requirements, not tested.

## Permissions matrix, customer isolation and POC scoping

I created my own data: VIEWER, CASHIER, SALES_POC ×2, CUSTOMER_SUCCESS_POC and COLLECTION_POC ×2 users, customers A, B, Y and X with logins, plus invoices, payments, promises, disputes and seats for each. I then ran 73 endpoint probes per role against the design notes §2 matrix, with the seeded cashier used for reads only. The live seeded roles match §2 exactly, and all non-export calls give the expected allow/deny for all 7 roles. CASHIER keeps everything it had.

Customer isolation (AC-D10) holds on every path tried. By-id reads, audit, filters, the customerId parameter, sort, summaries, notifications, cross-customer disputes and bulk/export all returned 403, 404, zero rows or totals matching A's data only.

The most important failures, each re-run once and reproduced:
- **High — deactivated users keep API access.** Login is refused, but the old JWT still reads, writes and exports.
- **High — export only checks EXPORT_DATA, not the view privilege.** CASHIER and the POC roles can dump every user (with emails), every role, and all disputes and products.
- **Medium — customers can filter and sort on POC columns.** POC identity is not in the payloads or schemas, but the match counts reveal who the Sales and Collection POCs are (AC-A8).
- **Medium — SALES_POC's locked book applies only to lists and bulk.** It can GET, PATCH and cancel another rep's invoice by id.
- **Medium — Export is unreachable in the UI for roles with EXPORT_DATA but no *_MANAGE,** because rows can't be selected.

Lower-severity issues:
- Bulk calls silently drop out-of-scope ids.
- SALES_POC is not scoped on payments and promises.
- SALES_POC can add seats on customers outside its book.
- USER audit is readable without USER_VIEW.
- Promise POC can be reassigned without POC_ASSIGN.
- Staff user ids are exposed to customers.
- POC chips look disabled for viewers.

The UI sidebars, action buttons, locked chip, read-only viewer screens and the customer's lack of any POC field or filter all behave as intended.

_The seeded roles match design §2 exactly, all non-export probes matched for all 7 roles, and customer isolation held on every path. Blockers: export ignores view privileges (D-02), deactivated tokens stay live (D-01), and the SALES_POC book can be bypassed by id (D-15). PS-033 and PS-036 match the documented design._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| PS-001 | Privilege matrix (seeding) | API | Live seeded role privileges equal design notes §2 matrix | PASS |  |
| PS-002 | Privilege matrix | API | ADMIN: 73 endpoint probes allow/deny | PASS |  |
| PS-003 | Privilege matrix — CASHIER keeps prior abilities | API | CASHIER (own CASHIER-role user): all non-export probes | PASS |  |
| PS-004 | Privilege matrix — seeded cashier | API | Seeded cashier/cashier123: all GET probes | PASS |  |
| PS-005 | Privilege matrix | API | VIEWER: 73 probes | PASS |  |
| PS-006 | Privilege matrix | API | CUSTOMER: 73 probes | PASS |  |
| PS-007 | Privilege matrix | API | SALES_POC: non-export probes | PASS |  |
| PS-008 | Privilege matrix | API | CUSTOMER_SUCCESS_POC: non-export probes | PASS |  |
| PS-009 | Privilege matrix | API | COLLECTION_POC: non-export probes | PASS |  |
| PS-010 | Privilege matrix — export | API | Export checks only EXPORT_DATA and leaks users/roles/disputes/products to roles without the view privilege | FAIL | high |
| PS-011 | Customer isolation AC-D10 | API | Customer A reading B's records by id | PASS |  |
| PS-012 | Customer isolation — audit | API | Customer A reading audit of B's records and of USER/PRODUCT | PASS |  |
| PS-013 | Customer isolation — list widening | API | Filters, customerId param and sort cannot widen a customer's lists | PASS |  |
| PS-014 | Customer isolation — tiles | API | Customer's summary tiles equal only A's data | PASS |  |
| PS-015 | Customer isolation — notifications | API | A cannot see or mark B's notification | PASS |  |
| PS-016 | Customer isolation — disputes | API | A cannot open a dispute on B's invoice or payment | PASS |  |
| PS-017 | Customer isolation — bulk/export | API | Customer cannot bulk-act or export, even with B's ids or select-all | PASS |  |
| PS-018 | Bulk scoping / reporting (AC-D5, §8) | API | Out-of-scope ids in a bulk request are dropped silently instead of reported | FAIL | low |
| PS-019 | 401/403/404 semantics for customers | API | Non-existent ids | PASS |  |
| PS-020 | AC-A8 POC identity for customers | API | Crawl of every customer-readable JSON response for POC identity and staff names | PASS |  |
| PS-021 | AC-A8 table schemas | API | /api/table-schemas hides POC columns for a customer | PASS |  |
| PS-022 | AC-A8 POC filter/sort as customer | API | Customer can filter and sort on POC columns; match counts reveal POC identity | FAIL | medium |
| PS-023 | AC-A8 staff identity in customer payloads | API | Customer DTOs expose staff user ids (createdByUserId, resolvedByUserId) | FAIL | low |
| PS-024 | AC-A8 audit/history for customers | API | Customer's audit of an invoice with POC reassignments hides POC identity | PASS |  |
| PS-025 | POC book — SALES_POC locked | API | SALES_POC list limited to own invoices with lockedFilters | PASS |  |
| PS-026 | POC book — cannot widen | API | SALES_POC cannot widen its book via filters or sort | PASS |  |
| PS-027 | POC book — tiles | API | SALES_POC summary tiles equal its book | PASS |  |
| PS-028 | POC book — bulk/export | API | SALES_POC bulk and export confined to its book | PASS |  |
| PS-029 | POC book — by-id access | API | SALES_POC can read, edit and cancel another rep's invoice by id | FAIL | medium |
| PS-030 | POC book — customers | API | SALES_POC customers list is its book (seat or owned invoice) | PASS |  |
| PS-031 | POC scope endpoint | API | /api/pocs/my-scope per POC role | PASS |  |
| PS-032 | POC book — override roles unfiltered (intended) | API | COLLECTION_POC and CS_POC lists open unfiltered | PASS |  |
| PS-033 | POC book — SALES_POC on payments/promises | API | SALES_POC (no SCOPE_OVERRIDE) sees every payment, promise and dispute | FAIL | low |
| PS-034 | POC seats — book/role check | API | Locked SALES_POC can add a POC seat on a customer outside its book | FAIL | low |
| PS-035 | Audit — USER history | API | USER audit readable by roles without USER_VIEW | FAIL | low |
| PS-036 | Promise POC reassignment permission | API | Custom role with PROMISE_MANAGE but no POC_ASSIGN can reassign a promise's Collection POC | FAIL | low |
| PS-037 | Auth — 401 semantics | API | Missing, invalid, expired or unknown-user tokens return 401 | PASS |  |
| PS-038 | Auth — 403 semantics | API | Authenticated caller without the privilege gets 403 | PASS |  |
| PS-039 | Auth — deactivated user | API | Deactivated user's still-valid JWT keeps full API access, including writes | FAIL | high |
| PS-040 | Auth — live privilege changes | API | Revoking a role privilege takes effect for an existing token | PASS |  |
| PS-041 | UI — sidebar per role | UI | Sidebar items match privileges for all 7 roles | PASS |  |
| PS-042 | UI — action buttons gated by privilege | UI | New invoice, Record payment, New customer, Raise promise, Cancel invoice and Save changes appear only with the privilege | PASS |  |
| PS-043 | UI — locked POC chip | UI | SALES_POC invoices list shows a locked 'My records only' chip | PASS |  |
| PS-044 | UI — viewer read-only | UI | VIEWER sees values read-only with no editable inputs | PASS |  |
| PS-045 | UI — viewer read-only styling (AC-C2) | UI | Viewer's customer detail renders POC seats as greyed, disabled-looking chips | FAIL | low |
| PS-046 | UI — export reachability | UI | Roles with EXPORT_DATA but no *_MANAGE cannot select rows, so 'Export selected' is unreachable | FAIL | medium |
| PS-047 | UI — AC-A8 customer screens | UI | Customer login sees no POC field, column, tile or filter | PASS |  |
| PS-048 | UI — customer typed route | UI | Customer typing #/customers (not in its sidebar) | PASS |  |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| PS-010 | yes | high | The export endpoints check only EXPORT_DATA and never the entity's VIEW privilege: UserController.java:210-211, RoleController.java:64-65, DisputeController.java:80-81, ProductController.java:140-141. Their resolveIds (e |
| PS-039 | yes | high | JwtAuthFilter.java:36-40 loads the user and sets the Authentication without checking userDetails.isEnabled(), even though AppUserDetails.isEnabled() returns user.isActive(). Tokens last 24h (application.yml:13, expiratio |
| PS-022 | yes | medium | TableQuery.java:40 (requireSortable) and :48 (requireFilterable) validate against the full schema. TableSchema.java:32-50 never looks at ColumnDef.pocRestricted, so hidden columns are only filtered from the published sch |
| PS-029 | yes | medium | InvoiceService.java:153-161 (get) only applies the customer-scoped restriction and never ScopeResolver.forInvoices(). update (:101-102) and cancel (:225-226) use getInternal(id) with no scope check at all. CustomerServic |
| PS-046 | yes | medium | The list screens set selectable to canManage only: invoices_screen.dart:56, payments_screen.dart:61, products_screen.dart:51, promises_screen.dart:40, customers_screen.dart:65. core/table/data_table_scaffold.dart:175 sho |
| PS-023 | yes | medium | PromiseDtos.java:57 (overriddenByUserId) and :64 (createdByUserId) are always filled by PaymentPromiseService.toDto (:510-529), which only nulls collectionPoc when !canSeePoc. DisputeDtos.java:27 (resolvedByUserId) is al |
| PS-035 | yes | medium | AuditController.java:34-35 lists USER (and PRODUCT) in SUPPORTED, and ensureCallerCanSee returns early for any staff caller (:97: if (callerCustomer == null) return;). So AUDIT_VIEW alone is enough to read USER_CREATED/U |
| PS-018 | yes | low | resolveIds filters the requested ids through the permitted set before the executor sees them, e.g. InvoiceController.java:154 and NotificationController.java:117 (req.ids().stream().filter(permitted::contains)). The same |
| PS-034 | yes | low | The role-level grant matches the design: §2 gives POC_ASSIGN to SALES_POC and §1 says POC_ASSIGN covers customers, so the role check alone isn't a defect even though US-A3 names admin or CS POC. The defect is the missing |
| PS-045 | yes | low | frontend/lib/features/poc/customer_poc_editor.dart:155-167 always renders an InputChip. When !widget.editable, both onPressed (:163) and onDeleted are null, so Material renders the chip in its disabled style. AC-C2 requi |
| PS-033 | no | none | Not a defect per the design. Design §7 defines 'my book' as invoices where I am the Sales POC, payments and promises where I am the Collection POC, and disputes carry no POC. ScopeResolver.java:134 (empty scope when the  |
| PS-036 | no | none | Reproduced but intended per the documentation. Design §1 defines POC_ASSIGN as 'May change POC assignments on invoices, payments and customers' (promises not listed). PROMISE_MANAGE is 'create, edit and cancel promises', |

Not tested here: Mobile-width (400px) layouts of the role-gated toolbars and detail screens — all UI checks ran at 1366×900.; Bulk actions driven through the UI (select rows, then Cancel unpaid / Reassign / Add POC). Bulk permission and scope were verified through the API only; the UI checks covered only whether checkboxes and buttons are present.; Logout/login as a different role in the same browser session (candidate 27, tableSchemaProvider cached across logins). Each role ran in a fresh browser context, so a POC filter carried over from an admin session into a customer session was not exercised.; An unprivileged custom role with INVOICE_MANAGE but without POC_VIEW (candidate 51), and the full >5000-row truncation path — outside this area's data budget.; Editing seeded roles or seeded admin/cashier (forbidden). CASHIER writes were tested with my own CASHIER-role user; the seeded cashier was used for reads and exports only.

## List/table framework across all 9 lists

I seeded my own data under the prefix tfw-mu2sowlk11ca: customer A (id 2) with 24 invoices, 6 payments, 7 promises and 3 disputes, plus 28 customers, 12 products, 14 staff, 3 roles and POC seats that generate notifications. I then ran about 1,000 API requests and 3 UI scripts. The core contract is solid on all 9 lists:
- 10/20/50 page sizes, default 20.
- Paging returns every row exactly once, with a stable id tiebreak.
- All 62 sortable columns sort correctly in both directions (124 checks); unsortable and unknown columns give 400.
- Every operator/type combination is validated (167 requests).
- Values with % _ : , quotes, backslash or SQL-injection text are treated as plain data.
- Summary tiles equal aggregates computed from the list.
- CSV export and bulk select-all/per-record results work.
- In the UI: filter chips, URL sync, page size memory, header sorting, and the empty/loading/error states and selection/export toolbar all work.

The most important failures:
1. HIGH: a customer-scoped user can filter and sort on POC columns that are hidden from its schema (salesPocName, collectionPocUserId). The match counts reveal the rep's identity (AC-A8). The UI makes this worse: after an admin logs out and the customer logs in on the same app instance, the stale cached schema offers "Sales POC" / "Sales POC name" in Add filter.
2. HIGH: the cashier can export every user's username, name and email through /api/users/export, which checks EXPORT_DATA only.
3. MEDIUM: on Postgres, date upper bounds (lte, between, yesterday, past) include rows stamped at exactly 00:00:00 of the next day, because of nanosecond rounding.
4. MEDIUM: non-numeric page/size, a huge page number, and malformed JSON bodies return 500 instead of 400.
5. MEDIUM: bulk actions silently drop unknown or out-of-filter ids (requested=0, nothing reported), against AC-D5.
6. MEDIUM: an out-of-range page shows "No invoices match this filter" with a garbled "991–990 of 24".

Lower-severity issues: the sort direction isn't validated; ineligible invoices are reported as failed rather than skipped; tiles are refetched on every page change; page size is remembered per device rather than per user; the cashier cannot reach Export on Products or Promises; reference chips show a raw id.

_The contract is solid: page sizes, complete paging, 124 sort directions, 167 operator checks, injection-safe filters, tiles equal to aggregates, and CSV escaping. The cross-cutting problems are the customer POC-column oracle (D-08), the export privilege gap (D-02), date upper bounds (D-23) and the bulk silent drop (D-14). TF-095 was not a defect._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| TF-001 | Table schemas | API | Schemas for all 9 entities match design doc §6 | PASS |  |
| TF-010 | Paging / page size | API | size must be 10/20/50 on all 9 lists; default 20 | PASS |  |
| TF-012 | Paging validation | API | Non-numeric page/size and huge page numbers return 500 instead of 400 | FAIL | medium |
| TF-020 | Paging completeness (AC-D2) | API | Paging through all pages returns every row exactly once on all 9 lists | PASS |  |
| TF-021 | Stable ordering (AC-D2) | API | Sorting on columns full of ties pages stably | PASS |  |
| TF-022 | Paging | API | Page beyond the end | PASS |  |
| TF-030 | Sorting (AC-D3) | API | Every sortable column of all 9 lists sorts asc and desc correctly | PASS |  |
| TF-031 | Sorting validation | API | Unsortable or unknown sort column -> 400 on all 9 lists | PASS |  |
| TF-032 | Sorting validation | API | Sort direction is not validated | FAIL | low |
| TF-040 | Date filters (Postgres) | API | Date upper bounds include rows stamped at 00:00:00 the next day (yesterday / past / lte / between) | FAIL | medium |
| TF-041 | Filters (number/money/enum/reference) | API | Money, number, enum and reference operators match a local computation | PASS |  |
| TF-042 | Filters (text) | API | Text contains (case-insensitive) / eq / neq / isEmpty / isNotEmpty | PASS |  |
| TF-043 | Filters (special characters / injection) | API | Colons, commas, quotes, % _ \ and SQL-injection strings are plain data | PASS |  |
| TF-044 | Filters (seats / link table / LocalDate / boolean) | API | Customer POC seat filters, promise invoiceId link filter, promisedDate presets, booleans across lists | PASS |  |
| TF-046 | Filters | API | Multiple filters AND together; appliedFilters echoes them | PASS |  |
| TF-050 | Filter validation (AC-D9) | API | Disallowed operator -> 400, allowed operator -> 200, unknown column -> 400 for every column of every list | PASS |  |
| TF-051 | Filter validation | API | Malformed filters are rejected with a message | PASS |  |
| TF-060 | Summary tiles (AC-E1/E2) | API | /summary honours the same filters; every tile equals the value computed from the full filtered list | PASS |  |
| TF-061 | Summary tiles | API | Summary endpoints validate filters; ?customerId= equals the chip | PASS |  |
| TF-070 | CSV export | API | Invoice export: ids and selectAll give exactly the selection, in sort order, correctly escaped | PASS |  |
| TF-072 | CSV export | API | Export on customers/payments/promises/products/users/roles/disputes equals the selection; validation | PASS |  |
| TF-080 | Scoping (AC-D10) | API | Customer-scoped caller cannot widen its scope via filters; summary scoped; bulk forbidden | PASS |  |
| TF-081 | Scoping / POC-restricted columns (AC-A8) | API | A customer can filter and sort on POC columns stripped from its schema, which reveals who its rep is | FAIL | high |
| TF-082 | Scoping (POC locked book) | API | SALES_POC without SCOPE_OVERRIDE gets a locked filter and cannot widen it | PASS |  |
| TF-083 | Export permissions | API | Cashier (no USER_VIEW) can export every user's username, full name and email | FAIL | high |
| TF-090 | Bulk (per-record results) | API | Products DEACTIVATE by ids; ACTIVATE via selectAllMatchingFilter; audit rows | PASS |  |
| TF-091 | Bulk (AC-D5 / design §8) | API | Bulk silently drops ids that do not exist or fall outside the filter or scope | FAIL | medium |
| TF-093 | Bulk validation | API | Missing, malformed or mistyped JSON body on bulk/export returns 500 | FAIL | low |
| TF-094 | Bulk (invoices) | API | Invoice bulk CANCEL on [unpaid, fully-paid, already-cancelled]; REASSIGN_SALES_POC via select-all | PASS |  |
| TF-095 | Bulk (AC-D6) | API | Ineligible invoices are attempted and counted as failed instead of skipped | FAIL | low |
| TF-097 | Bulk (users / notifications / promises / customers) | API | Users self-skip; notifications MARK_READ/UNREAD; promise CANCEL; customer ADD_POC select-all | PASS |  |
| UI-01 | UI Invoices — filters | UI | Add filter builds a chip from a column and operator, puts it in the URL, filters rows; a second enum chip ANDs | PASS |  |
| UI-03 | UI Invoices — filters | UI | Removing a chip and 'Clear all' | PASS |  |
| UI-05 | UI Invoices — paging | UI | Page size 20 -> 10, Next page, size remembered on return and after reload | PASS |  |
| UI-06 | UI Invoices — AC-D1 | UI | Changing page also refetches the summary tiles (2 requests, not 1) | FAIL | low |
| UI-08 | UI — sorting (AC-D3) | UI | Sortable headers sort; unsortable headers do nothing (Invoices and Customers) | PASS |  |
| UI-09 | UI — states (AC-D11) | UI | Empty vs loading vs error states are distinct | PASS |  |
| UI-12 | UI — states (AC-D11) | UI | A page past the end says 'No invoices match this filter' and shows a garbled pager | FAIL | medium |
| UI-13 | UI Invoices — selection / export | UI | Select rows -> toolbar count; Export selected shows exactly the selected rows | PASS |  |
| UI-15 | UI Invoices — select all matching (AC-D7) | UI | 'Select all 24 matching this filter', export and bulk-confirm count; header checkbox; page change clears selection | PASS |  |
| UI-17 | UI Customers — filters/paging/export | UI | Customers: Add filter chip in URL, Rows 50 remembered per table, select + Export selected | PASS |  |
| UI-22 | UI — export reachability | UI | Cashier holds EXPORT_DATA but cannot select rows, and so cannot export, on Products and Promises | FAIL | medium |
| UI-23 | UI — schema cache / AC-A8 | UI | After admin logout and customer login in the same app, Add filter still offers 'Sales POC' and 'Sales POC name' | FAIL | medium |
| UI-24 | UI — page size memory | UI | Page size is remembered per device, not per user | FAIL | low |
| UI-25 | UI — chips | UI | Reference filter chip shows the raw id ('Customer is 2') instead of the customer name | FAIL | low |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| TF-012 | yes | medium | backend/src/main/java/com/geneinvoice/common/query/TableQueryExecutor.java:50: tq.setFirstResult(query.page() * query.size()) is int multiplication and overflows to a negative number. TableQuery.java:22-23 only checks p  |
| TF-032 | yes | low | backend/src/main/java/com/geneinvoice/common/query/TableQuery.java:39: asc = bits.length < 2 // !"desc".equalsIgnoreCase(bits[1].trim()) treats any token other than desc as ascending. Design §5 defines sort as 'column,as |
| TF-040 | yes | medium | backend/src/main/java/com/geneinvoice/common/query/FilterPredicates.java:75-80 (relative upper bound = nextDay.atStartOfDay().minusNanos(1) with <=) and ValueCoercion.java:49-54 endOfDayIfDateOnly (same minusNanos(1)), u |
| TF-081 | yes | high | backend/src/main/java/com/geneinvoice/common/query/TableSchema.java:24-50: require/requireSortable/requireFilterable never check ColumnDef.pocRestricted(). TableQuery.parse (TableQuery.java:40,48) and TableQueryExecutor. |
| TF-083 | yes | high | backend/src/main/java/com/geneinvoice/user/UserController.java:210-213, role/RoleController.java:64-65 and dispute/DisputeController.java:80-82 guard export with @PreAuthorize(EXPORT_DATA) only, not with the entity's VIE |
| TF-091 | yes | medium | The resolveIds helpers filter the requested ids against the permitted set before BulkExecutor sees them, e.g. backend/src/main/java/com/geneinvoice/product/ProductController.java:163, invoice/InvoiceController.java:154,  |
| TF-093 | yes | low | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-64: the catch-all @ExceptionHandler(Exception.class) maps HttpMessageNotReadableException (and HttpMediaTypeNotSupportedException) to 500, becau |
| TF-095 | no | none | Reproduced, but it is the intended, tested behaviour. backend/src/test/java/com/geneinvoice/common/BulkActionTest.java (aPartialFailureReportsWhichRowsSucceeded... and aFailingRowDoesNotRollBackTheRowsThatAlreadySucceede |
| UI-06 | yes | low | frontend/lib/core/table/table_providers.dart:52-61: tableSummaryProvider is a FutureProvider.autoDispose.family keyed by the whole TableRequest (its == and hashCode include query.page, size and sort, lines 26-35). A page |
| UI-12 | yes | medium | frontend/lib/core/table/data_table_scaffold.dart:193-200: `if (page.isEmpty)` shows _EmptyState with the filter message whenever content is empty, without checking totalElements > 0 or page >= totalPages. frontend/lib/co |
| UI-22 | yes | medium | frontend/lib/features/products/products_screen.dart:51 and frontend/lib/features/promises/promises_screen.dart:40 pass `selectable: canManage` (PRODUCT_MANAGE / PROMISE_MANAGE). The Export action exists only inside _Sele |
| UI-23 | yes | medium | frontend/lib/core/table/table_providers.dart:38-42: tableSchemaProvider is a FutureProvider.family (not autoDispose) that watches only dioProvider. dioProvider (core/api/api_client.dart:17) watches only tokenStorageProvi |
| UI-24 | yes | low | frontend/lib/core/table/table_providers.dart:70 and 90-98: PageSizeStore persists under 'table.pageSize.<entity>' with no user id, and hydrate (lines 73-86) loads every such key. core/router.dart:30 reads it as the defau |
| UI-25 | yes | low | frontend/lib/core/table/filter_editor.dart:333-342: describeFilter returns '$label ${operatorLabel(op)} ${f.values.join(', ')}' using raw wire values. For REFERENCE columns no label lookup is done, and the picker's displ |

Not tested here: The bulk/export truncated=true path and the 5000-row limit (candidate 35 off-by-one): building more than 5000 matching rows is not feasible in the shared environment. Only truncated=false / limit=5000 was verified.; The UI list screens for Payments, Promises, Products, Users, Roles, Disputes and Notifications: the scope was UI on Invoices and Customers. Their API contract was tested on all 9 lists, and a cashier reachability check was done on Products, Promises, Payments, Invoices and Customers.; Filters built with the reference picker (Customer / POC search dialog) and the date picker in the filter dialog. The UI chips I built used text and enum editors; reference and date filters were applied through the URL and the API.; The narrow/mobile card layout below 760px (AC-D12) and its selection toolbar.; Relative date presets for non-UTC users (the IST side of candidate 46): only UTC behaviour was checked. The Postgres rounding defect was found instead (TF-040).; Cold deep links that reproduce a filtered view in a fresh session (AC-D4): landing on the dashboard is the documented, accepted behaviour. In-app navigation and reload restoration of filters and size were tested.

## Payments, allocation and customer credit

Covered on the API: POST /api/payments (validation, oldest-first allocation, explicit invoiceIds, overpayment to credit, credit consumed by the next invoices, promise linking and rollback), GET /{id}, PATCH notes and Collection POC (audit and notification), /credits, list paging/sort/filter, summary tiles including a void through the dispute flow, bulk REASSIGN_COLLECTION_POC and export, and permissions for VIEWER, SALES_POC, CUSTOMER, CASHIER and COLLECTION_POC. Covered in the UI: the Payments list (tiles, Record payment button, 400/1366/1920 widths), the Record payment dialog and Payment Details. All scripts and evidence are under /private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/payments-credit. The API ran twice (api-run1.log, api-run2.log) with identical results. Core money handling is healthy: oldest-first allocation, explicit targeting, overpayment credit, and credit consumed by the next invoice all reconcile exactly; bad POCs, other customers' invoices and bad promiseIds are rejected with a full rollback; tiles exclude voided payments. The most important failures are these. (1) Notes can't be saved on a payment whose Collection POC has been deactivated, because the UI resends the unchanged POC id and the backend re-validates it; the same happens for a role without POC_ASSIGN. (2) On Payment Details, and also Invoice Details, the 'Save changes' button and 'Unsaved changes' hint are not drawn at any tested size, although clicking the blank spot saves; this was seen in headless Chrome. (3) The Record payment customer dropdown loads only 50 customers, so a customer later in the alphabet can't be paid from the UI. (4) Choosing a customer overwrites a POC the cashier picked by hand. (5) Unsaved edits are lost without a prompt when leaving through the sidebar or an allocation row. Also confirmed: a 301-character note gives a 500; voiding a payment whose credit was already used leaves 123.00 of paid invoices with no payment behind it; bulk actions silently drop an unknown id; the Payment Promise tab lists all of the customer's promises, not just this payment's; the POC picker filters one keystroke late.

_Oldest-first allocation, explicit targeting, overpayment credit and promise linking reconcile exactly. Blockers: voiding a payment whose credit was already used leaves invoices paid with no payment behind them (D-03), Save changes is invisible (D-04), notes saves are blocked (D-07), and the customer dropdown is capped at 50 (D-06)._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| PAYCR-001 | POST /api/payments validation | API | Collection POC is mandatory on create | PASS |  |
| PAYCR-002 | POST /api/payments validation | API | A POC that can't be assigned is rejected | PASS |  |
| PAYCR-003 | POST /api/payments validation | API | amount <= 0, missing amount or customer, unknown customer | PASS |  |
| PAYCR-004 | Allocation oldest-first | API | With no invoiceIds, the payment is applied to the oldest outstanding invoice first | PASS |  |
| PAYCR-005 | GET /api/payments/{id} | API | Payment detail returns allocations with invoice numbers and creditApplied | PASS |  |
| PAYCR-006 | Explicit invoiceIds | API | Explicit invoiceIds pay only those invoices | PASS |  |
| PAYCR-007 | Explicit invoiceIds | API | An invoice from another customer is rejected with no side effects | PASS |  |
| PAYCR-008 | Overpayment to credit | API | Overpayment becomes the customer's credit balance | PASS |  |
| PAYCR-009 | Credit consumed by the next invoice | API | The next invoices use up the credit in order | PASS |  |
| PAYCR-010 | Audit of payments and credit | API | Audit rows for recording, applying and credit use | PASS |  |
| PAYCR-011 | Explicit invoiceIds and overpayment | API | Overpaying one targeted invoice sends the leftover to credit, not to other invoices | PASS |  |
| PAYCR-012 | Explicit invoiceIds | API | An unknown invoice id is silently ignored and the money becomes credit | FAIL | low |
| PAYCR-013 | Explicit invoiceIds | API | Targeting a cancelled invoice leaves it untouched and keeps the money | PASS |  |
| PAYCR-014 | Overpayment to credit | API | A customer with no invoices: the whole amount becomes credit | PASS |  |
| PAYCR-015 | POST /api/payments validation | API | A 3-decimal amount is accepted, and the response shows a value different from what is stored | FAIL | low |
| PAYCR-016 | promiseIds | API | promiseIds links the payment to the promise | PASS |  |
| PAYCR-017 | promiseIds | API | A bad promiseId rejects and rolls back the whole payment | PASS |  |
| PAYCR-018 | PATCH /api/payments/{id} | API | Editing notes is saved and audited | PASS |  |
| PAYCR-019 | PATCH /api/payments/{id} | API | Changing the Collection POC (as cashier) is audited and the new POC is notified | PASS |  |
| PAYCR-020 | PATCH /api/payments/{id} | API | PATCH to a POC that can't be assigned, and PATCH on an unknown payment | PASS |  |
| PAYCR-021 | PATCH /api/payments/{id} | API | A 301-character note gives a 500 instead of a validation error | FAIL | medium |
| PAYCR-022 | Payment Details: notes edit with an inactive POC | API+UI | Notes can't be saved once the payment's Collection POC is deactivated | FAIL | high |
| PAYCR-023 | PATCH /api/payments/{id} permissions | API | A role with PAYMENT_MANAGE but no POC_ASSIGN can't save notes the way the UI sends them | FAIL | medium |
| PAYCR-024 | Permissions | API | Who can record and edit payments | PASS |  |
| PAYCR-025 | Customer scoping | API | A customer login sees only its own payments and credit, without POC identity | PASS |  |
| PAYCR-026 | GET /api/payments/{id}, /credits | API | Unknown payment or customer returns 404 | PASS |  |
| PAYCR-027 | List: paging, sort, filter | API | Customer filter, sorting and combined filters | PASS |  |
| PAYCR-028 | List: server-side validation | API | Bad size, sort column, filter column or operator returns 400 | PASS |  |
| PAYCR-029 | GET /api/payments/summary | API | Tiles over the filtered set match the rows | PASS |  |
| PAYCR-030 | Void (dispute) and summary | API | A voided payment reverses its allocation and drops out of totalCollected | PASS |  |
| PAYCR-031 | Void (dispute) and customer credit | API | Voiding a payment whose overpayment credit was already used leaves invoices paid by money that no longer exists | FAIL | medium |
| PAYCR-032 | Bulk REASSIGN_COLLECTION_POC | API | Bulk reassign on explicit ids (cashier) | PASS |  |
| PAYCR-033 | Bulk REASSIGN_COLLECTION_POC | API | Bulk validation and per-record failures | PASS |  |
| PAYCR-034 | Bulk REASSIGN_COLLECTION_POC | API | Bulk silently drops an id it can't resolve | FAIL | medium |
| PAYCR-035 | Bulk REASSIGN_COLLECTION_POC | API | Select-all-matching acts on exactly the filtered set | PASS |  |
| PAYCR-036 | Export | API | CSV export by ids and by filter; permission checks | PASS |  |
| PAYCR-037 | Notification on create | API | The Collection POC is notified when a payment is recorded for them | PASS |  |
| PAYCR-038 | Payments list UI | UI | Tiles, Record payment button and filtered tiles | PASS |  |
| PAYCR-039 | Payments list UI layout | UI | The list at 400, 1366 and 1920 px | PASS |  |
| PAYCR-040 | Record payment dialog | UI | Validation messages | PASS |  |
| PAYCR-041 | Record payment dialog | UI | Choosing a customer pre-fills the POC and lists outstanding invoices and open promises | PASS |  |
| PAYCR-042 | Record payment dialog | UI | Record a payment linked to a promise and one invoice | PASS |  |
| PAYCR-043 | Record payment dialog | UI | The pre-filled Collection POC can be changed before recording | PASS |  |
| PAYCR-044 | Record payment dialog | UI | Choosing a customer overwrites a POC the cashier already picked (candidate 36) | FAIL | medium |
| PAYCR-045 | Record payment dialog: Collection POC picker | UI | The picker list doesn't filter after typing stops (candidate 14) | FAIL | medium |
| PAYCR-046 | Record payment dialog: customer dropdown | UI | Customers beyond the first 50 by name can't be chosen (candidate 13) | FAIL | high |
| PAYCR-047 | Payment Details top section | UI | Amount, credit applied, method, POC, notes and allocations | PASS |  |
| PAYCR-048 | Payment Details save | UI | 'Save changes' and 'Unsaved changes' are not drawn, though the button works | FAIL | high |
| PAYCR-049 | Payment Details save | API+UI | Edit notes and save | PASS |  |
| PAYCR-050 | Payment Details save | API+UI | Change the Collection POC, save, and see it in History | PASS |  |
| PAYCR-051 | Payment Details: discard-changes dialog | API+UI | Back with unsaved edits asks first; Keep editing and Discard both work | PASS |  |
| PAYCR-052 | Payment Details: discard-changes dialog | UI | Leaving by the sidebar or an allocation row loses edits without asking (candidate 15) | FAIL | medium |
| PAYCR-053 | Payment Details allocation link | UI | Clicking an allocation row opens its invoice | PASS |  |
| PAYCR-054 | Payment Details: Payment Promise tab | UI | The tab lists every promise of the customer, not the ones linked to this payment (candidate 37) | FAIL | medium |
| PAYCR-055 | Payment Details as a customer | UI | A customer sees their payment read-only with no POC field | PASS |  |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| PAYCR-012 | yes | low | backend/src/main/java/com/geneinvoice/payment/PaymentService.java:58-65. invoiceRepository.findAllById silently omits missing ids and targets.size() is never compared with req.invoiceIds().size(). An empty target list se |
| PAYCR-015 | yes | medium | backend/src/main/java/com/geneinvoice/payment/PaymentDtos.java:17 has @NotNull @Positive but no @Digits(integer=12, fraction=2), so 0.001 passes @Positive. The entity column is scale 2 (Payment.java:30-35) and Postgres r |
| PAYCR-021 | yes | medium | backend/src/main/java/com/geneinvoice/payment/PaymentDtos.java:19 (Create notes) and :29 (UpdatePaymentRequest.notes) have no @Size(max=300), and the PATCH at PaymentController.java:85-86 is not @Valid. Payment.java:40 i |
| PAYCR-022 | yes | high | backend/src/main/java/com/geneinvoice/payment/PaymentService.java:100-101 runs requireAssignable (PocService.java:61-63 rejects inactive users) before the unchanged check at lines 102-103. frontend/lib/features/payments/ |
| PAYCR-023 | yes | medium | backend/src/main/java/com/geneinvoice/payment/PaymentController.java:87-89 rejects any non-null collectionPocUserId when the caller lacks POC_ASSIGN, without comparing it with the payment's current POC. frontend/lib/feat |
| PAYCR-031 | yes | high | backend/src/main/java/com/geneinvoice/payment/PaymentService.java:212-217 (reverseAllocations). It subtracts creditApplied from the customer's credit and floors it at 0, never reversing the invoice that already consumed  |
| PAYCR-034 | yes | medium | backend/src/main/java/com/geneinvoice/payment/PaymentController.java:165 (resolveIds filters req.ids() with permitted::contains). BulkExecutor.run (common/bulk/BulkExecutor.java:40,55) only ever sees the filtered list, s |
| PAYCR-044 | yes | medium | frontend/lib/features/payments/record_payment_dialog.dart:138-143 resets _pocResolvedFor when the customer changes. _resolveDefaultPoc at lines 60-70 then sets _collectionPoc = primary in a post-frame callback without ch |
| PAYCR-045 | yes | medium | frontend/lib/features/poc/poc_picker.dart:44-49. The debounce Timer calls setState on _PocPickerState, which isn't part of the dialog route's widget tree, so the StatefulBuilder dialog doesn't rebuild. At lines 120-123 s |
| PAYCR-046 | yes | high | frontend/lib/features/customers/customers_screen.dart:19-26 (allCustomersProvider fetches size 50, sort name,asc) is used by the DropdownButtonFormField at frontend/lib/features/payments/record_payment_dialog.dart:112,12 |
| PAYCR-048 | yes | high | frontend/lib/features/payments/payment_detail_screen.dart:265-281 (the Save Row, last child of the top Column) inside frontend/lib/shared/widgets/detail_scaffold.dart:157-160 (Flexible(flex:5) + SingleChildScrollView(chi |
| PAYCR-052 | yes | medium | frontend/lib/features/payments/payment_detail_screen.dart:119-127. Only PopScope and onBack (131-133) call _confirmDiscard. The allocation ListTile onTap at line 257 calls context.go directly, and the shell NavigationRai |
| PAYCR-054 | yes | medium | frontend/lib/features/payments/payment_detail_screen.dart:169-177 builds PromisesTab(customerId, customerName) with no payment context. PromisesTab (frontend/lib/features/promises/promises_tab.dart:59-74, documented as ' |

Not tested here: Bulk REASSIGN_COLLECTION_POC and CSV export through the UI (row selection toolbar, POC dialog, copyable CSV dialog): covered through the API only.; Bulk truncation at 5000 ids: would need more than 5000 payments in the shared DB.; Legacy payments with a null Collection POC ('POC missing' badge, isEmpty filter, and their notes save): the API can't create such rows because the POC is mandatory; pocMissingCount was only seen as 0.; Dispute-driven update_amount re-allocation and its effect on credit: only void was exercised.; A Collection POC or CASHIER login driving the payments UI: UI runs used ADMIN and a CUSTOMER login; the API covered all roles.; The 'Save changes' drawing problem (PAYCR-048) was not confirmed in a headed browser; every screenshot is from headless Chrome/CanvasKit.; Behaviour at more than 50 outstanding invoices or open promises in the Record payment dialog (the list is capped at size 50), not seeded.

## Payment promises

I ran 56 consolidated cases: 41 API and 15 UI. The API cases were driven by Node scripts (api1.js, api2.js, recheck1/2.js); the UI cases were driven by Playwright against the Flutter web app, with every screenshot read. 45 PASS and 11 FAIL, all failures reproduced on a second run. The core lifecycle works end to end: create validation (AC-B1), the Collection POC defaulting to the customer's primary (AC-B8), automatic KEPT and PARTIALLY_KEPT (AC-B3/B4), general promises, BROKEN on a past date with exactly one notification that deep-links correctly (AC-B5/B10), and audit rows for every change (AC-B9). Overriding and clearing an override work. Cancel unlinks payments without touching them or their allocations (AC-B11). Re-evaluation after a voided payment, a changed payment amount, or a cancelled invoice (dispute-driven or direct) works (AC-B6). The list filters (including the new invoiceId eq/isEmpty/isNotEmpty), sorting, input validation, bulk CANCEL/REASSIGN, export and the customer's read-only view all work. Regressions confirmed fixed: the Invoice Details Payment Promise tab loads and raises pre-scoped promises, and 'Keep it' closes only the dialog. The most important failures: (1) HIGH — a general promise that was KEPT because the account owed nothing flips to BROKEN, with a notification, as soon as any later invoice is raised (AC-B7). (2) HIGH — once one of a promise's invoices is cancelled, the promise can never be edited from the UI (400, and the cancelled invoice can't be unticked). (3) MEDIUM — the summary double-counts a payment shared by overlapping promises (fulfilledAmount 200 against 100 collected; AC-B12). (4) MEDIUM — a cancelled promise can be revived by an override, and clearing that override re-links the payment. Also medium: a customer can filter and sort promises on the POC-restricted columns; the default Collection POC can be a deactivated user; a payment ticked to a promise but allocated to another invoice links while contributing 0; and overriding from a Promises list row leaves the row and tiles stale.

_Create validation, the default POC, KEPT/PARTIALLY_KEPT/BROKEN transitions, overrides, cancel, re-entrancy, filters, bulk and export all pass. Blockers: kept general promises flip to BROKEN with a false notification (D-09), and a promise can't be edited after one of its invoices is cancelled (D-10). Totals double-count shared payments (D-34)._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| PRM-V01 | Create validation (AC-B1) | API | Create rejects non-positive amount, missing promisedDate, missing/unknown customer | PASS |  |
| PRM-V02 | Create validation (AC-B1) | API | Invoices must belong to the customer, never CANCELLED, and must exist | PASS |  |
| PRM-D01 | Collection POC default (AC-B8) | API | Collection POC defaults to the customer's primary when omitted | PASS |  |
| PRM-D02 | Collection POC default (AC-B8) | API | POC required when there is no primary; a non-Collection-assignable POC is rejected | PASS |  |
| PRM-K01 | Auto-link KEPT (AC-B3) | API | A payment that fully settles the promised invoice auto-links and marks KEPT | PASS |  |
| PRM-K02 | Partial payment (AC-B4) | API | A partial payment marks PARTIALLY_KEPT and records remainingAmount | PASS |  |
| PRM-K03 | Excess / shortfall (AC-B2) | API | Promised amount may exceed or fall short of the invoice balance | PASS |  |
| PRM-G01 | General promise (AC-B7) | API | A general promise (no invoices) tracks the customer's payments | PASS |  |
| PRM-B01 | BROKEN + notification (AC-B5, AC-B10) | API | A past-dated unpaid promise is BROKEN; the Collection POC gets exactly ONE notification that deep-links | PASS |  |
| PRM-B03 | Status algorithm §4 | API | Paying after the date does not un-break the promise but is recorded | PASS |  |
| PRM-A01 | Audit (AC-B9) | API | Creation, automatic status changes and edits write audit rows | PASS |  |
| PRM-O01 | Override (US-B6, AC-B9) | API | An override requires a reason and refuses status CANCELLED | PASS |  |
| PRM-O07 | Override input validation | API | An unknown override status returns 500 instead of 400 | FAIL | low |
| PRM-O02 | Override (US-B6, AC-B9) | API | Override OPEN->KEPT records who/when/why, audits it, and pins the status | PASS |  |
| PRM-O04 | Clear override | API | DELETE /api/promises/{id}/override hands back to automatic tracking | PASS |  |
| PRM-O05 | Override to BROKEN | API | Overriding to BROKEN notifies the Collection POC once | PASS |  |
| PRM-C01 | Cancel (AC-B11) | API | Cancel unlinks payments without altering the payment or its allocations | PASS |  |
| PRM-C02 | Cancel (AC-B11) | API | A cancelled promise can't be re-cancelled or edited, evaluation never resurrects it, and the reason is optional | PASS |  |
| PRM-C03 | Cancel / Override (lead #20) | API | An override revives a CANCELLED promise; clearing it then re-links the payment | FAIL | medium |
| PRM-U01 | Edit promise | API | PUT validation, moving the date into the past re-evaluates, and reassigning the POC notifies | PASS |  |
| PRM-R01 | Re-entrancy (AC-B6) | API | Voiding a linked payment via an approved customer dispute re-evaluates KEPT -> OPEN | PASS |  |
| PRM-R02 | Re-entrancy (AC-B6) | API | Cancelling a promised invoice (dispute-driven or direct) re-evaluates the promise | PASS |  |
| PRM-R05 | Re-entrancy (AC-B6) | API | Changing a linked payment's amount via dispute re-evaluates KEPT -> PARTIALLY_KEPT | PASS |  |
| PRM-R04 | Edit promise after a re-entrant invoice cancel (lead #3) | API+UI | A promise can never be edited once one of its invoices is cancelled | FAIL | high |
| PRM-M01 | Many-to-many (AC-B12) | API | One payment settling two promises on distinct invoices: both KEPT, totals correct | PASS |  |
| PRM-M02 | Many-to-many (AC-B12) | API | One promise fulfilled by two payments doesn't double-count | PASS |  |
| PRM-M03 | Many-to-many totals (AC-B12) | API | Two promises on the SAME invoice plus one payment of 100: the summary double-counts | FAIL | medium |
| PRM-M04 | Many-to-many totals (AC-B12, lead #6) | API+UI | Two general promises plus one payment of 100: both KEPT and totals doubled | FAIL | medium |
| PRM-F01 | List filters (US-B5) | API | Filters on status (eq/in) and promisedDate (between/gte) | PASS |  |
| PRM-F03 | List filters (US-B5) | API | customerId (chip and ?customerId=) and collectionPocUserId filters | PASS |  |
| PRM-F05 | invoiceId filter (new) | API | invoiceId eq, ?invoiceId=, isEmpty (general promises) and isNotEmpty | PASS |  |
| PRM-F07 | List validation, sort, tiles | API | Bad inputs give 400, server-side sort works, and summary matches the list | PASS |  |
| PRM-BK01 | Bulk REASSIGN_COLLECTION_POC | API | Bulk reassign moves the selected promises; bad input handled per contract | PASS |  |
| PRM-BK03 | Bulk CANCEL | API | Bulk cancel by ids and by selectAllMatchingFilter; an already-cancelled id is reported | PASS |  |
| PRM-BK04 | Bulk validation / permissions | API | Unknown action or missing ids give 400; CASHIER/VIEWER get 403 | PASS |  |
| PRM-E01 | Export | API | CSV export of selected ids and of all rows matching a filter | PASS |  |
| PRM-CU01 | Customer visibility (US-B7) | API | A customer login reads only its own promises, POC stripped, and can't mutate | PASS |  |
| PRM-CU03 | Customer visibility / POC blindness (AC-A8, lead #10) | API | A customer can filter and sort promises on the POC-restricted columns | FAIL | medium |
| PRM-P01 | Permissions (AC-B8, matrix §2) | API | Only PROMISE_MANAGE/OVERRIDE holders mutate; view roles read; unknown id is 404 | PASS |  |
| PRM-L01 | General promise status (AC-B7, AC-B6, lead #5) | API | A KEPT general promise flips to BROKEN (and notifies) when a later invoice is raised | FAIL | high |
| PRM-L02 | Explicit link on record payment (US-B3, lead #30) | API | A payment ticked to a promise but allocated to another invoice shows as linked yet contributes 0 | FAIL | medium |
| PRM-L03 | Collection POC default (AC-B8, lead #23) | API | The default Collection POC can be a deactivated user | FAIL | medium |
| PRM-L04 | Explicit link guards | API | Record payment refuses promiseIds of another customer or of a cancelled promise | PASS |  |
| PRM-UI01 | Promises list tiles (US-B5, Feature E) | UI | Tiles reflect the customerId filter chip and match the API summary | PASS |  |
| PRM-UI02 | Raise from Customer Details (US-B1, US-C4) | UI | Raise promise from Customer Details > Payment Promise tab | PASS |  |
| PRM-UI03 | Shortfall display (AC-B2) | UI | Raise dialog shows the shortfall; the dialog's Cancel saves nothing and stays on the page | PASS |  |
| PRM-UI04 | Raise from Invoice Details (US-B2) — regression of the invoiceId 400 fix | UI | Invoice Details > Payment Promise tab loads and raises a promise pre-scoped to the invoice | PASS |  |
| PRM-UI05 | Edit promise | UI | Edit a promise from its card | PASS |  |
| PRM-UI06 | Override status dialog (US-B6) | UI | Override dialog requires a reason, pins the status, shows it on the card, and Clear override reverts | PASS |  |
| PRM-UI08 | Cancel dialog — regression | UI | 'Keep it' closes only the dialog | PASS |  |
| PRM-UI09 | Cancel promise (AC-B11) | UI | Cancel a promise with a reason | PASS |  |
| PRM-UI10 | Record payment links a promise (US-B3) | UI | The Record payment dialog lists the customer's open promise and links the payment to it | PASS |  |
| PRM-UI11 | Override from the Promises list (lead #16) | UI | After overriding from a list row, the row and the tiles stay stale | FAIL | medium |
| PRM-UI13 | Customer read-only view (US-B7, AC-C7) | UI | A customer sees its promises read-only with no POC identity | PASS |  |
| PRM-UI15 | Broken notification deep link (AC-B10, AC-C1) | UI | The 'Promise broken' notification link lands on the customer's Payment Promise tab | PASS |  |
| PRM-UI16 | Bulk actions and export from the list (D.3) | UI | Select all on page > Cancel promises confirms the exact count; Export selected shows the CSV | PASS |  |
| PRM-UI14 | Mobile layout (AC-C6, AC-D12) | UI | Promises list and the customer's Payment Promise tab at 400px | PASS |  |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| PRM-O07 | yes | low | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-64. There is no @ExceptionHandler for HttpMessageNotReadableException (or MethodArgumentTypeMismatchException), so Jackson deserialisation failu |
| PRM-C03 | yes | medium | backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:165-188. override() rejects a target status of CANCELLED but never checks whether the current status is CANCELLED, unlike update() at :106 and canc |
| PRM-R04 | yes | high | Backend: PaymentPromiseService.java:126-128 re-resolves every submitted invoiceId through resolveInvoices(). Its CANCELLED check (:556-559) treats invoices already linked to the promise as new ones. Frontend: promise_for |
| PRM-M03 | yes | medium | PaymentPromiseService.java:314-342 (computeFulfilment) credits each promise with the full allocation that landed on its covered invoices, regardless of other promises sharing that payment. tiles() at :497 then sums fulfi |
| PRM-M04 | yes | medium | PaymentPromiseService.java:299-311 (linkPayments): a general promise (no covered invoices) links every active payment made since its creation. computeFulfilment at :325-326 counts the payment's full amount for it, even w |
| PRM-CU03 | yes | medium | common/query/TableQuery.java:37-50 validates sort and filters through TableSchema.requireSortable/requireFilterable (TableSchema.java:24-50), which never check ColumnDef.pocRestricted(). The only POC-blindness check is i |
| PRM-L01 | yes | high | PaymentPromiseService.java:274-280 judges a past-dated general promise with customerOutstanding() (:355-363), which sums the current balances of all live invoices, including ones raised after the promised date. InvoiceSe |
| PRM-L02 | yes | medium | PaymentService.java:57-68 picks allocation targets from req.invoiceIds only, falling back to all invoices oldest-first, and ignores the invoices of the selected promiseIds. PaymentPromiseService.attachPayment (:371-387)  |
| PRM-L03 | yes | medium | PaymentPromiseService.java:534-542 (resolveCollectionPoc) returns pocService.primaryFor() (PocService.java:92-95) without the active, customer-account and assignability checks that requireAssignable applies to explicit i |
| PRM-UI11 | yes | medium | frontend/lib/features/promises/promises_screen.dart:140-147. After showOverrideDialog the row action runs only ref.invalidate(promiseDetailProvider(p.id)), which the list does not watch. The table's tablePageProvider and |

Not tested here: Sweeper-driven transitions with real time passing (PromiseSweepScheduler, AC-B5) and lead #7 (PARTIALLY_KEPT going stale after the date): the server clock can't be advanced. Past-dated promises are judged immediately on create or edit, so the sweeper path itself never ran.; Concurrency of notify-once between the sweeper and a concurrent void (lead #45), and the UTC-vs-local promised-date boundary (lead #46): these need timing control or a non-UTC clock.; Date picker interaction in the promise dialog: the UI cases used the default +7 day date, and past dates were set through the API.; Bulk REASSIGN_COLLECTION_POC through the UI POC-picker dialog: covered through the API (PRM-BK01) only.; Bulk/export truncation beyond 5000 rows and the 50-item caps in the promise tabs and dialogs (lead #57): these need large data volumes, which would disturb the other testers on the shared server.

## Disputes and notifications

I ran 49 cases in all, through both the API and the Flutter web UI. They covered who may open a dispute and on which record, validation, duplicate prevention, list and by-id scoping, approving each change type with its side effects checked on follow-up reads (cancel, replace_items, update_notes, void, update_amount, update_meta), deny, resolving twice, and bad approvals. On the notifications side they covered DISPUTE_OPENED/APPROVED/DENIED delivery, paging, unread count, single and bulk mark read/unread, mark-all-read, links, the bell and navigation. The core dispute workflow is sound: permissions, scoping, allocation and refund maths on the happy paths, rollback on failed approvals, and customer/admin notifications all behave as designed. Every failure was reproduced a second time.

The most important failures:
1. **Invisible Approve/Deny (UI, high).** The Approve and Deny buttons on the admin dispute detail are never painted. Approve's invisible hit area covers the whole row, so a click anywhere there approves, and Deny can't be reached by mouse at all.
2. **Money created by a voided payment (API, high).** If a dispute cancels or shrinks an invoice and a later dispute voids the payment that paid it, the customer keeps the refunded credit.
3. **Dispute export bypass (API, high).** CASHIER, which has no DISPUTE_VIEW, can export every customer's disputes through POST /api/disputes/export.

Smaller issues:
- replace_items accepts quantity 0, giving a ₹0 FULLY_PAID invoice.
- Malformed JSON bodies and a non-numeric amount return 500 instead of 400.
- A bulk request containing another user's notification id drops it silently.
- The DISPUTE_OPENED link /admin/disputes/{id} is not a router route (the app rewrites it).
- A customer who opens another customer's dispute sees a raw DioException 403 message.
- The bell badge stays stale after bulk Mark read until the next 30-second poll.

_Opening, scoping, each approve action's side effects, rollback and notifications all work through the API. Blockers: Approve/Deny are not painted and a click on the blank row approves (D-04), a void after a refund leaves spendable credit (D-03), and CASHIER can export every dispute (D-02)._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| DN-API-01 | Open dispute - who may open | API | Staff logins (ADMIN, CASHIER, SALES_POC) cannot open a dispute | PASS |  |
| DN-API-02 | Open dispute - ownership | API | Customer cannot dispute another customer's invoice or payment; unknown target 404 | PASS |  |
| DN-API-03 | Open dispute - validation | API | Reason required, targetId required | PASS |  |
| DN-API-04 | Open dispute - validation | API | Malformed request bodies return 500 instead of 400 | FAIL | low |
| DN-API-05 | Open dispute - happy path | API | Customer opens a dispute on its own invoice | PASS |  |
| DN-API-06 | Open dispute - duplicate | API | Second PENDING dispute on the same target rejected; allowed again after resolution | PASS |  |
| DN-API-07 | Notifications - DISPUTE_OPENED | API | Every ADMIN user gets DISPUTE_OPENED; customer and other staff do not | PASS |  |
| DN-API-08 | Dispute list scoping | API | Customer sees only its own disputes; admin sees all | PASS |  |
| DN-API-09 | Dispute GET by id / role access | API | GET by id scoping and DISPUTE_VIEW enforcement | PASS |  |
| DN-API-10 | Resolve - permission | API | Customer and SALES_POC cannot approve or deny | PASS |  |
| DN-API-11 | Approve invoice update_notes | API | update_notes approval changes notes, notifies customer, audits on invoice | PASS |  |
| DN-API-12 | Approve invoice cancel | API | Cancel refunds paid amount to credit; cancelling a cancelled invoice is rejected and rolled back | PASS |  |
| DN-API-13 | Approve invoice replace_items | API | replace_items with a smaller total refunds the excess to credit | PASS |  |
| DN-API-14 | Approve invoice replace_items | API | appliedChangeJson overrides the proposal; larger total; bad items rejected | PASS |  |
| DN-API-15 | Approve invoice replace_items | API | replace_items accepts quantity 0 and produces a ₹0 FULLY_PAID invoice | FAIL | medium |
| DN-API-16 | Approve payment void | API | Void reverses allocations, removes the credit applied, sets VOIDED; a second void is rejected | PASS |  |
| DN-API-17 | Approve payment update_amount | API | update_amount re-allocates oldest-first | PASS |  |
| DN-API-18 | Approve payment update_amount | API | Non-numeric amount returns 500 (negative and missing amounts correctly 400) | FAIL | low |
| DN-API-19 | Approve payment update_meta | API | update_meta changes only method and notes | PASS |  |
| DN-API-20 | Deny | API | Deny stores notes, leaves the target untouched, notifies the customer | PASS |  |
| DN-API-21 | Resolve twice | API | An already-resolved dispute cannot be resolved again | PASS |  |
| DN-API-22 | Approve - bad input | API | Unknown action, malformed JSON, no change, wrong action for the target, unknown id | PASS |  |
| DN-API-23 | Approve invoice cancel + payment void (combined) | API | Customer keeps credit from a payment that was later voided (cancel path) | FAIL | high |
| DN-API-24 | Approve replace_items + payment void (combined) | API | Customer keeps credit from a payment that was later voided (replace_items path) | FAIL | high |
| DN-API-25 | Dispute export - permissions | API | CASHIER (no DISPUTE_VIEW) can export every customer's disputes | FAIL | high |
| DN-API-26 | Notifications list | API | GET /api/notifications returns a paged envelope | PASS |  |
| DN-API-27 | Notifications - unread count / mark read / isolation | API | Unread count, single mark read, and per-user isolation | PASS |  |
| DN-API-28 | Notifications - bulk MARK_READ / MARK_UNREAD | API | Bulk read/unread, skipped rows, select-all with a filter, and bad requests | PASS |  |
| DN-API-29 | Notifications - bulk | API | A bulk request with another user's notification id drops it without reporting it | FAIL | low |
| DN-API-30 | Notifications - mark all / sort / auth | API | mark-all-read, sorting, and unauthenticated access | PASS |  |
| DN-API-31 | Notification links vs frontend router | API | The DISPUTE_OPENED link /admin/disputes/{id} is not a route in router.dart | FAIL | low |
| DN-API-32 | Dispute audit trail | API | Dispute events appear in the target's History | PASS |  |
| DN-UI-01 | Customer invoice detail | UI | Customer sees Raise dispute on its invoice detail | PASS |  |
| DN-UI-02 | Raise dispute dialog | UI | Dialog opens and requires a reason | PASS |  |
| DN-UI-03 | Raise dispute dialog | UI | 'Correct the notes only' requires notes; submit creates the dispute and shows it in the tab | PASS |  |
| DN-UI-04 | Raise dispute dialog | UI | Duplicate dispute error is shown in the dialog | PASS |  |
| DN-UI-05 | Customer Disputes list and detail | UI | Customer's Disputes list is scoped; detail has no resolve controls | PASS |  |
| DN-UI-06 | Dispute detail - access denied | UI | Opening another customer's dispute by URL shows a raw DioException | FAIL | low |
| DN-UI-07 | Raise dispute on payment | UI | Correct-the-amount: validation, then a valid amount submits | PASS |  |
| DN-UI-08 | Bell badge | UI | Admin bell badge equals the API unread count | PASS |  |
| DN-UI-09 | Notifications screen | UI | Clicking a DISPUTE_OPENED notification opens the dispute and marks it read | PASS |  |
| DN-UI-10 | Admin Disputes list | UI | Admin Disputes list shows all customers; row click opens detail | PASS |  |
| DN-UI-11 | Admin dispute detail - Approve/Deny | UI | Approve and Deny buttons are not painted; Deny cannot be clicked; any click on the blank row approves | FAIL | high |
| DN-UI-12 | Admin approve flow | UI | Approve (via the accessibility action) applies the change and shows the resolved state | PASS |  |
| DN-UI-13 | Admin deny flow | UI | Deny with admin notes | PASS |  |
| DN-UI-14 | Admin approve - invalid JSON | UI | Invalid applied JSON is caught before submit | PASS |  |
| DN-UI-15 | Customer notifications | UI | Customer sees DISPUTE_APPROVED/DENIED and clicking them opens the dispute | PASS |  |
| DN-UI-16 | Notifications bulk Mark read / bell | UI | Bell badge not refreshed after bulk Mark read (stale until the 30 s poll) | FAIL | low |
| DN-UI-17 | Notifications - Mark all read | UI | Mark all read clears the badge | PASS |  |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| DN-API-04 | yes | low | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-64. There is no @ExceptionHandler for HttpMessageNotReadableException (or MethodArgumentTypeMismatchException), so Jackson and Spring binding er |
| DN-API-15 | yes | medium | backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:243-248 builds InvoiceDtos.LineInput from JSON with asInt() and no bean validation. The @Positive on LineInput.quantity (InvoiceDtos.java:32) only runs un |
| DN-API-18 | yes | low | backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:269: new BigDecimal(node.get("amount").asText()) throws NumberFormatException, which is not caught and falls to the GlobalExceptionHandler catch-all (500) |
| DN-API-23 | yes | high | InvoiceService.cancelWithRefund (InvoiceService.java ~247-262) moves inv.paidAmount into customer credit and zeroes paidAmount, but leaves P's PaymentAllocation(amount 100) and P.creditApplied=0 untouched. PaymentService |
| DN-API-24 | yes | high | InvoiceService.replaceItems (InvoiceService.java ~296-302) refunds paid minus the new total to customer credit and sets paidAmount = total, but P's PaymentAllocation stays at 300 and creditApplied at 0. PaymentService.re |
| DN-API-25 | yes | high | backend/src/main/java/com/geneinvoice/dispute/DisputeController.java:80-81: export is guarded only by hasAuthority(EXPORT_DATA), not DISPUTE_VIEW. ScopeResolver.forDisputes() (ScopeResolver.java:104-112) returns Scope.em |
| DN-API-29 | yes | low | backend/src/main/java/com/geneinvoice/notification/NotificationController.java:117 filters req.ids() through the caller's permitted set before BulkExecutor.run, so foreign and unknown ids never reach the executor. Its 'N |
| DN-API-31 | yes | low | backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:84 builds the notification link as "/admin/disputes/" + id, a path that does not exist in the frontend router. It is patched on the client by a special ca |
| DN-UI-06 | yes | low | frontend/lib/features/disputes/dispute_detail_screen.dart:26: error: (e, _) => Center(child: Text('Failed: $e')) prints the raw exception instead of the shared RecordUnavailable + notFoundMessage (shared/widgets/detail_s |
| DN-UI-11 | yes | high | frontend/lib/core/theme.dart:15-19 sets the FilledButton theme minimumSize to Size.fromHeight(46), an infinite minimum width. frontend/lib/features/disputes/dispute_detail_screen.dart:185-198 places FilledButton.icon (Ap |
| DN-UI-16 | yes | low | frontend/lib/core/table/data_table_scaffold.dart:128-133 (_refresh, called after _runBulkAction around line 390) invalidates only tablePageProvider and tableSummaryProvider. NotificationsScreen (features/notifications/no |

Not tested here: Phone-width (~400px) layout of the dispute dialog, disputes list and dispute detail: all UI runs were at 1366x900 (plus one 1920x1080 screenshot).; Entering a negative corrected amount (-5) in the payment dispute dialog through the UI: my text went into the notes field because of a harness coordinate issue. The empty-amount validation and a valid amount (120) were verified.; Submitting the 'Cancel this invoice', 'Void / never happened' and 'Correct method / notes' dispute options through the dialog: they were covered only through the API (proposedChangeJson).; replace_items with a negative quantity or a negative unit price: only quantity 0 was tried. Negative unitPrice is rejected in code (InvoiceService.java:285), but negative quantity is not validated.; Two admins approving or denying the same dispute at the same time (race on mustBePending).; A custom role that holds DISPUTE_MANAGE but isn't named ADMIN: notifyAdmins only targets role 'ADMIN' and the frontend uses isAdmin to show resolve controls. Not exercised, to avoid creating extra roles.; Error handling when a notification mark-read call fails (candidate #56): I could not force the server to fail.; Disputes-tab truncation at 50 rows (candidate #57): it would need more than 50 disputes on one record.

## Screen-by-screen UI sweep (6 roles, 1366 and 400 px)

Six roles plus the login screen were swept at 1366x900 and 400x820, 397 screenshots in total. Each run covered every sidebar screen and Notifications, the Customer/Invoice/Payment detail with each tab, the dispute detail, the New invoice form, and the New customer / Record payment / Raise dispute / Raise promise dialogs wherever the role may open them. Fresh API data was created for this (users uisv/uiss/uisc/uiscust, invoices INV-20260915-0236/0237, payment #108, promise #103, dispute #81). No API response of 400 or above and no page error appeared on any screen, and the fixed regressions held: the invoice Payment Promise tab no longer returns 400, the History timeline works, and no FAB overlaps the pager. The worst problem is on phones. When the 'username • ROLE' account chip is long (COLLECTION_POC), it pushes the Notifications bell on top of the hamburger, and a real tap on the hamburger opens Notifications instead of the drawer. The app-bar title is cut or hidden for every role on phones. On desktop, every table is at least as wide as the whole window, because minWidth is the screen width minus 24 and ignores the 256px navigation rail. The last column and the row actions (Open, Raise promise, Cancel invoice, Edit) sit off the right edge even at 1920px. Long unbroken text in dispute reasons or notification messages makes those tables thousands of pixels wide. The dispute target text shows money unformatted with a raw enum ('INVOICE INV-… — 451234.50'). On phones, detail headers break the invoice number mid-token and the Raise promise dialog wraps the date one character onto a second line. Lower-severity inconsistencies: full-width FilledButtons caused by the theme, date formats that differ between screens, the Dashboard nav item highlighted while on Notifications, and a cramped list viewport on phones. Screens that are clean: the login screen, dashboards (apart from the stretched button), the New invoice form, New customer and Record payment dialogs, payment detail on desktop, phone card lists apart from the badge overlap, and the empty states.

_Across 397 screens there were no API errors and no page errors, and the regressions (pager overlap, Payment Promise tab, History) held. High: on phones a long account chip covers the hamburger (D-12). Medium: desktop tables push their last columns and row actions off-screen, and the dispute money text is unformatted. The rest is layout polish._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| UIS-01 | App shell / phone navigation | UI | Phone app bar: long account chip pushes the Notifications bell onto the hamburger; a tap on the hamburger opens Notifications, not the drawer | FAIL | high |
| UIS-02 | App shell / phone | UI | App-bar title truncated or hidden on phone for every role | FAIL | low |
| UIS-03 | Table framework / desktop lists | UI | Desktop tables are at least as wide as the whole window, so the last columns and row actions are always off-screen right | FAIL | medium |
| UIS-04 | Table framework / Disputes & Notifications lists | UI | Long unbroken reason or message text makes the table thousands of px wide; the ellipsis never triggers | FAIL | medium |
| UIS-05 | Disputes / money formatting | UI | Dispute target shows money unformatted with a raw enum: 'INVOICE INV-20260915-0237 — 451234.50' | FAIL | medium |
| UIS-06 | Detail screens / phone header | UI | Phone detail header breaks the invoice number mid-token and wraps the subtitle over 3-4 lines | FAIL | medium |
| UIS-07 | Promise dialog / phone | UI | Raise promise dialog on phone wraps the date one character onto a second line and truncates the amount label | FAIL | low |
| UIS-08 | Customers list / phone cards | UI | 'POC missing' badge on customer cards runs under the Open icon | FAIL | low |
| UIS-09 | Table framework / phone lists | UI | Phone list pages keep tiles, filter bar and a two-row pager fixed, leaving ~340px (about 1.3 cards) for the list | FAIL | low |
| UIS-10 | Theme / buttons | UI | The FilledButton theme forces full width, so 'New invoice', 'Raise promise' and 'Raise dispute' stretch across the page and dialog Save sits under a lone Cancel | FAIL | low |
| UIS-11 | App shell / navigation highlight | UI | The sidebar highlights 'Dashboard' on the Notifications screen (and on the customer's own detail page) | FAIL | low |
| UIS-12 | Formatting / dates | UI | Date/time formats are inconsistent between screens | FAIL | low |
| UIS-13 | Customer detail / desktop | UI | The customer detail top pane is fixed at half height and clips the POC section mid-label | FAIL | low |
| UIS-14 | Raise dispute dialog / phone | UI | The dispute 'What should change?' dropdown text runs under the arrow on phone | FAIL | low |
| UIS-15 | Invoice/Payment detail | UI | Two 'Raise dispute' actions on the same screen | FAIL | low |
| UIS-16 | Dashboard tiles | UI | The dashboard 'Kept' promise tile shows only a count; Open and Broken show count and amount | FAIL | low |
| UIS-17 | Dispute detail labels | UI | Developer wording shown to users: 'INVOICE history', 'Proposed change (JSON)' | FAIL | low |
| UIS-18 | Dashboard labels | UI | Staff roles without PAYMENT_MANAGE see a 'My payments' button that opens all payments | FAIL | low |
| UIS-19 | All screens / errors | API+UI | No API errors (>=400) and no page errors on any screen, role or width | PASS |  |
| UIS-20 | Login | UI | Login screen at desktop and phone | PASS |  |
| UIS-21 | Phone drawer / sidebar per role | UI | Hamburger drawer (semantics tap) opens, lists the right entries per role and navigates | PASS |  |
| UIS-22 | Dashboard per role | UI | Dashboard tiles and actions follow privileges | PASS |  |
| UIS-23 | List screens / pager regression | UI | List screens load with tiles, filter bar and pager; no button overlaps the pagination (fixed FAB/pager overlap) | PASS |  |
| UIS-24 | Phone card lists | UI | Lists switch to readable label/value cards below 760px | PASS |  |
| UIS-25 | Customer detail tabs per role | UI | Customer detail shows tabs and editability by privilege | PASS |  |
| UIS-26 | Invoice detail Payment Promise tab (regression) | API+UI | The invoice Payment Promise tab loads without 400 for every role | PASS |  |
| UIS-27 | Payment detail | UI | Payment detail: tiles, allocations and POC editability by role | PASS |  |
| UIS-28 | History timeline (regression) | UI | History tab shows a typed timeline with filter chips and record links | PASS |  |
| UIS-29 | Dispute detail by role | UI | Resolve section only for admin; the customer sees a read-only dispute | PASS |  |
| UIS-30 | New invoice form | UI | The New invoice form fits at desktop and phone for admin, cashier and SALES_POC | PASS |  |
| UIS-31 | New customer dialog | UI | The New customer dialog fits on desktop and phone (admin, cashier) | PASS |  |
| UIS-32 | Record payment dialog | UI | The Record payment dialog fits on desktop and phone (admin, cashier, COLLECTION_POC) | PASS |  |
| UIS-33 | Raise dispute / Raise promise dialogs (desktop) | UI | Raise dispute (admin, customer) and Raise promise (admin, COLLECTION_POC) dialogs on desktop | PASS |  |
| UIS-34 | Money formatting | UI | Money in lists, details, tiles and dialogs uses ₹ with en-IN grouping and 2 decimals | PASS |  |
| UIS-35 | POC scope chips | UI | SALES_POC sees locked 'My records only' / 'My book only' chips; the other roles open unfiltered | PASS |  |
| UIS-36 | Horizontal scroll on wide tables | UI | Clipped columns can be reached by horizontal wheel or trackpad, and by shift+wheel | PASS |  |
| UIS-37 | Empty states | UI | Empty states read as empty, not as errors | PASS |  |
| UIS-38 | POC identity hidden from the customer (AC-A8) | UI | The customer login sees no Sales/Collection POC anywhere | PASS |  |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| UIS-01 | yes | high | frontend/lib/shared/widgets/app_shell.dart:98-108. The account PopupMenuButton child is an unconstrained Row that shows Text('${user.username} • ${user.role}') with no maxWidth or ellipsis. AppBar's NavigationToolbar giv |
| UIS-02 | yes | low | frontend/lib/shared/widgets/app_shell.dart:63 and 98-108. AppBar gives the title only the width left after the unconstrained account chip in actions, so the chip takes it all. |
| UIS-03 | yes | medium | frontend/lib/core/table/data_table_scaffold.dart:239-240: ConstrainedBox(minWidth: MediaQuery.sizeOf(context).width - 24). The table sits in Expanded next to the 256px NavigationRail plus a 1px divider, so its visible wi |
| UIS-04 | yes | medium | frontend/lib/features/disputes/disputes_screen.dart:55-58 and frontend/lib/features/notifications/notifications_screen.dart:58-61. The Text has maxLines: 2 and ellipsis but sits in a DataCell with no width constraint. Da |
| UIS-05 | yes | medium | backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:207 and 210 build the summary from BigDecimal.toString() (getInvoiceNumber() + " — " + getTotal(); "Payment #" + id + " — " + getAmount()). The frontend a |
| UIS-06 | yes | medium | frontend/lib/shared/widgets/detail_scaffold.dart:186-209. _Header is a Row of Expanded(title column) plus a non-flex Wrap(trailing). The Wrap is laid out first at its intrinsic width, leaving the title about 75px. There  |
| UIS-07 | yes | low | frontend/lib/features/promises/promise_form_dialog.dart:156-188. A Row of two Expanded fields (amount TextField and date InputDecorator with a suffix icon) inside a dialog about 272px wide on a phone, with no narrow brea |
| UIS-08 | yes | low | frontend/lib/features/customers/customers_screen.dart:107-116. The Name cell is Row(mainAxisSize: min, [Text(name), PocMissingBadge]) with no Flexible or Wrap. In the card layout (data_table_scaffold.dart:333-341) the ce |
| UIS-09 | yes | low | frontend/lib/core/table/data_table_scaffold.dart:141-222. The tiles, filter bar and _PaginationBar are fixed children of the outer Column, and only the Expanded body scrolls, on narrow screens too. |
| UIS-10 | yes | low | frontend/lib/core/theme.dart:15-19. filledButtonTheme minimumSize: Size.fromHeight(46) means minimum width = infinity. Each FilledButton expands to its parent's max width, and in AlertDialog actions the OverflowBar canno |
| UIS-11 | yes | low | frontend/lib/shared/widgets/app_shell.dart:135-148. _selectedIndex starts at best = 0 and returns 0 when no visible entry matches the path. '/notifications' has no entry, and '/customers' is hidden for customers (hideFor |
| UIS-12 | yes | low | frontend/lib/features/audit/audit_history_panel.dart:331 (static final _when = DateFormat.yMMMd().add_jm()) and frontend/lib/features/disputes/dispute_detail_screen.dart:106 (DateFormat.yMMMd().add_jm()) bypass the share |
| UIS-13 | yes | low | frontend/lib/shared/widgets/detail_scaffold.dart:154-172. Two Flexible(flex: 5) halves; the top half is a SingleChildScrollView with no visible Scrollbar, so on the customer screen, which has the most content, the POC ed |
| UIS-14 | yes | low | frontend/lib/features/disputes/dispute_create_dialog.dart:155-160. DropdownButtonFormField is used without isExpanded: true, and the item Text widgets (lines 57 and 63) have no overflow handling, so the selected item kee |
| UIS-15 | no | none | Not a defect. Requirements C.3 says the Disputes tab carries 'the existing create/open actions', so the tab button (frontend/lib/features/disputes/disputes_tab.dart:41-58) is required. The header button (invoice_detail_s |
| UIS-16 | yes | low | frontend/lib/features/dashboard/dashboard_screen.dart:94-98. The Kept SummaryTile value is '${s['keptCount'] ?? 0}' and omits keptAmount, unlike the Open and Broken tiles and the Promises page. |
| UIS-17 | yes | low | frontend/lib/features/disputes/dispute_detail_screen.dart:203 ('${d.targetType.name} history') and :134 (label 'Proposed change (JSON)', shown to every role, customers included). |
| UIS-18 | yes | low | frontend/lib/features/dashboard/dashboard_screen.dart:125-129. The label depends only on paymentManage ('Record payment' : 'My payments') and ignores isCustomer, unlike the invoices button at line 122. |

Not tested here: The CUSTOMER_SUCCESS_POC role was not swept (not in the requested role list).; Only 1366x900 and 400x820 were swept fully; 1920x900 was spot-checked for table width only. Other phone sizes (360px) and tablet widths (760-900px, where the rail and drawer switch) were not covered.; Dialogs were opened and cancelled only. No create/save went through the UI (Save, Record, Submit and Raise promise were not pressed), so field validation messages inside dialogs were not screenshotted.; These dialogs were not opened: Change password, Add filter / filter editor, POC pickers, promise Edit / Override status / Cancel, New product / New user / New role, and the bulk-action and CSV export dialogs.; The unsaved-changes guard, browser back/forward and deep-link behaviour were not exercised (other areas).; RenderFlex overflow is not reported in a release web build (no overflow stripes and no page error), so overflow was judged only visually from screenshots.

## Detail screens, History timeline, dashboard

I ran 44 cases, API and UI, against my own data: customers A, B and C (ids 164-166), invoices 248-251, payments 109-112, promises 104-106, and disputes 82-85, two of them with reasons of 616 and 703 characters. I also covered roles admin, cashier, SALES_POC, COLLECTION_POC and a customer login. The listed fixes hold. The stale-form fix works for customer, invoice and payment. The invoice Payment Promise tab loads (no 400). History works end to end: complete, newest first, correctly scoped, chips and links work, 'Show more' appears past 100 rows, and a customer login sees no POC events or staff names. Dispute reasons over 500 characters open and expand. Dashboard tiles match the list-page summaries for every role tested. The most important failure: the unsaved-changes guard only covers the Back arrow and system pop. Sidebar navigation, browser Back, the notifications bell and the payment allocation row all leave a dirty detail screen with no prompt, and the edits are dropped (AC-C3). Also: malformed ids (#/invoices/abc, #/customers/abc, #/payments/abc) show a grey error box instead of a not-found state (AC-C8), and the backend returns 500 instead of 400 for those ids. A payment's Payment Promise tab lists every promise of the customer, not the ones the payment counts towards (C.3). Smaller issues: a staff user id (resolvedByUserId) leaks into the customer-visible dispute history snapshot; the dashboard Kept tile has no amount; single-link History rows can't be expanded via accessibility activation; and the invoice header wraps badly at phone width.

_History is complete, ordered, scoped and hides POC identity from customers. The stale-form and Payment Promise tab fixes hold, and dashboard tiles match the summaries for every role tested. Main issue: the unsaved-changes guard only covers the Back arrow (D-11, high). Malformed URLs show a grey error box. HIST-006 and DASH-006 were not defects._

| ID | Feature | Kind | Title | Result | Severity |
|---|---|---|---|---|---|
| HIST-001 | History timeline API | API | Customer History (includeRelated) lists every event, newest first, only this customer's | PASS |  |
| HIST-002 | History timeline API | API | Rows carry readable record labels and amounts | PASS |  |
| HIST-003 | History timeline API | API | Invoice History: own events, payments applied/reversed, covering promises and its disputes, nothing else | PASS |  |
| HIST-004 | History timeline API | API | Payment History: recorded, disputes on it, approval | PASS |  |
| HIST-005 | History timeline API - customer login | API | Customer login's History has no POC events/keys and no staff usernames | PASS |  |
| HIST-006 | History timeline API - customer login | API | Staff user id still visible inside a customer-visible dispute snapshot | FAIL | low |
| HIST-007 | History timeline API - permissions | API | A customer login cannot read another customer's history | PASS |  |
| HIST-008 | History timeline API - validation | API | Audit parameter validation | PASS |  |
| API-500-001 | Detail/History API - malformed id | API | Non-numeric id returns 500 instead of 400 | FAIL | low |
| HIST-009 | History timeline API - long dispute reason (regression) | API | Dispute reason over 500 characters opens and is audited | PASS |  |
| HIST-010 | History timeline API | API | includeRelated=false returns only the anchored record's rows | PASS |  |
| DET-API-001 | Detail screens API | API | Detail endpoints: 404 unknown, 403 other customer, 200 own | PASS |  |
| DET-API-002 | Detail tabs API | API | Tab queries: invoice promises (invoiceId filter) and disputes | PASS |  |
| DET-001 | Detail screens - lazy tabs | UI | Customer detail opens on Disputes and loads only that tab's data | PASS |  |
| DET-002 | Detail screens - tab URL | UI | Selecting a tab writes ?tab= and does not reload the top section | PASS |  |
| DET-003 | Detail screens - tab URL | UI | In-app navigation to ?tab= opens that tab, including on the same record | PASS |  |
| DET-004 | Detail screens - invoice | UI | Invoice top section and lazy tabs | PASS |  |
| DET-005 | Detail screens - invoice Payment Promise tab (regression) | UI | Invoice Payment Promise tab loads without 400 and lists only promises covering the invoice | PASS |  |
| DET-006 | Detail screens - payment Payment Promise tab | UI | Payment's Payment Promise tab lists every promise of the customer | FAIL | medium |
| DET-007 | Detail screens - stale form (regression) | UI | Customer A to customer B shows B's own values | PASS |  |
| DET-008 | Detail screens - stale form (regression) | UI | Invoice A1 to invoice B1 shows B1's notes and POC | PASS |  |
| DET-009 | Detail screens - stale form (regression) | UI | Payment PA1 to PB1 shows PB1's values | PASS |  |
| DET-010 | Detail screens - unsaved guard | UI | The Back arrow prompts when there are unsaved edits | PASS |  |
| DET-011 | Detail screens - unsaved guard | UI | Switching tabs keeps unsaved edits | PASS |  |
| DET-012 | Detail screens - unsaved guard | UI | Sidebar navigation silently drops unsaved edits | FAIL | high |
| DET-013 | Detail screens - unsaved guard | UI | Browser Back drops unsaved edits without a prompt | FAIL | medium |
| DET-014 | Detail screens - unsaved guard | UI | In-page links (payment allocation row, notifications bell) drop unsaved edits | FAIL | medium |
| DET-015 | Detail screens - not found | UI | Non-existent ids show a clean not-found state | PASS |  |
| DET-016 | Detail screens - malformed id | UI | Malformed ids render a blank grey error box instead of not-found | FAIL | medium |
| DET-017 | Detail screens - customer login | UI | Customer opening another customer's records sees a forbidden state | PASS |  |
| DET-018 | Detail screens - customer login | UI | Customer's own invoice: no POC field, read-only, no Save | PASS |  |
| DET-019 | Detail screens - tab failure | UI | A failing tab shows its error inside the tab | PASS |  |
| DET-020 | Detail screens - phone width | UI | Invoice header at 400px squeezes the invoice number into 3 lines | FAIL | low |
| HUI-001 | History tab UI - filter chips | UI | Chips All/Customer/Invoices/Payments/Promises/Disputes narrow correctly | PASS |  |
| HUI-002 | History tab UI - titles | UI | Readable titles with amounts, newest first, POC events included (admin) | PASS |  |
| HUI-003 | History tab UI - links | UI | Links in History rows open the right record | PASS |  |
| HUI-004 | History tab UI - Show more | UI | 'Show more' appears past 100 rows | PASS |  |
| HUI-005 | History tab UI - customer login | UI | Customer's History shows no POC events and no staff names | PASS |  |
| HUI-006 | History tab UI - long dispute reason (regression) | UI | Dispute with a 616-character reason opens and its History row expands | PASS |  |
| HUI-007 | History tab UI - accessibility | UI | Activating a single-link History row via the accessibility tree follows the link instead of expanding | FAIL | low |
| DASH-001 | Dashboard - admin | API+UI | Admin dashboard tiles match /summary and the list totals; quick actions navigate | PASS |  |
| DASH-002 | Dashboard - cashier | API+UI | Cashier dashboard loads, tiles match, quick actions work | PASS |  |
| DASH-003 | Dashboard - Sales POC | API+UI | SALES_POC dashboard shows its locked book and matches its lists | PASS |  |
| DASH-004 | Dashboard - Collection POC | API+UI | COLLECTION_POC dashboard loads and matches | PASS |  |
| DASH-005 | Dashboard - customer | API+UI | Customer dashboard shows exactly its own figures | PASS |  |
| DASH-006 | Dashboard - promise tiles | UI | The Kept tile shows a count but no amount | FAIL | low |

Verifier verdicts on this area's failures:

| Case | Confirmed | Severity | Root cause |
|---|---|---|---|
| HIST-006 | no | none | Not a defect under the requirements. AC-A8 withholds POC identity. DISPUTE_MANAGE is ADMIN-only (docs/implementation/poc-payment-promise-and-tables.md:46), so resolvedByUserId is never a POC. AuditTimelineService.without |
| API-500-001 | yes | low | backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:60-64. There is no handler for MethodArgumentTypeMismatchException, so the catch-all @ExceptionHandler(Exception.class) maps a client input error t |
| DET-006 | yes | medium | frontend/lib/features/payments/payment_detail_screen.dart:169-178 builds PromisesTab(customerId: payment.customerId) with no payment scope. PromiseScope (features/promises/promise_providers.dart:9-33) only knows customer |
| DET-012 | yes | high | frontend/lib/shared/widgets/app_shell.dart:120 (NavigationRail onDestinationSelected -> context.go) and :208 (drawer). context.go replaces the route without a pop, so the only guard never runs: a PopScope in customer_det |
| DET-013 | yes | medium | customer_detail_screen.dart:133-141 (same pattern in invoice and payment detail) relies on PopScope. A browser-history change is handled by go_router as a location change, not a Navigator pop, so the canPop:false guard i |
| DET-014 | yes | medium | frontend/lib/features/payments/payment_detail_screen.dart:257 (allocation ListTile onTap: context.go('/invoices/${inv.id}')) and shared/widgets/app_shell.dart:165 (bell onPressed: context.go('/notifications')). Neither c |
| DET-016 | yes | medium | frontend/lib/core/router.dart:65, 88, 103 (and 119, 133 for /promises/:id and /disputes/:id) call int.parse(s.pathParameters['id']!) inside the GoRoute builder. A FormatException there gives Flutter's release-mode ErrorW |
| DET-020 | yes | low | frontend/lib/shared/widgets/detail_scaffold.dart:177-209 (_Header): a Row with the title in Expanded (197) and an unconstrained trailing Wrap (207). The Wrap takes its intrinsic width first, leaving the title ~75px on na |
| HUI-007 | yes | low | frontend/lib/features/audit/audit_history_panel.dart:353-376: the link is an InkWell (355) inside the ExpansionTile (359) subtitle Column (362) with no Semantics boundary. The tile header merges into one node whose tap a |
| DASH-006 | no | none | Matches the requirement. Feature E lists the promise tiles as 'open count and amount, kept, broken, broken amount' (docs/requirements/poc-payment-promise-and-tables.md:230), so kept is a count only. dashboard_screen.dart |

Not tested here: Saving edits from the detail screens (AC-C5 refresh after save). Saves belong to the customers/invoices/payments areas; here I only checked that dropped edits were not saved.; Browser Back/Forward across ?tab changes on the same record. Tab-following was tested with in-app hash changes only.; Unsaved-changes guard via the narrow-width drawer menu. Only the wide NavigationRail sidebar was tested; the drawer uses the same unguarded context.go path.; Dashboards for VIEWER, CUSTOMER_SUCCESS_POC and custom roles.; Mobile/iOS/Android builds. Phone width was checked only in the web build at 400px.; The PROMISE-anchored audit timeline (API supports it; no detail screen uses it) and the PRODUCT/USER audit types.; Screen-reader behaviour beyond the one History-row activation check (HUI-007).

