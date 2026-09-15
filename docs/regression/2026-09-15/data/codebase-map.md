# Codebase map — POC / Payment Promise / table framework

Produced by a read-through of the feature on 12 Sep 2026, before this session's fixes. File:line references may have shifted.

## End-to-end architecture (verified against the working tree around 19:35; several files were still being edited during the review)

**1. Privileges and seeding.** `Privileges.java` adds 10 privileges: `POC_VIEW`, `POC_ASSIGN`, `POC_ASSIGNABLE_{SALES,SUCCESS,COLLECTION}`, `SCOPE_OVERRIDE`, `PROMISE_{VIEW,MANAGE,OVERRIDE}` and `EXPORT_DATA`. `DataSeeder` keeps overwriting the four built-in roles on every boot. It creates `SALES_POC`, `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC` once (createRoleIfAbsent) and never touches them again. The Dart `privileges.dart` has the same 27 constants.

**2. POC model.** `Invoice.salesPoc` and `Payment.collectionPoc` are nullable ManyToOne links to users. Customer Success and Collection POCs sit in `customer_pocs` (`CustomerPoc`), where each type has one primary holder.
- `PocService.requireAssignable` checks that the user is active, not a customer account, and holds the marker. Invoice/payment create requires the POC; PATCH re-checks it.
- `UserRepository.findAssignable` feeds the dropdowns: active users holding the marker, up to 100 per page.
- Deleting a user who is named as a POC deactivates them instead (`UserController.delete`).

**3. Scope.** `ScopeResolver` returns predicates plus lockedFilters. A customer account is restricted to its own customer id. A POC without `SCOPE_OVERRIDE` is locked to their own book:
- invoices: salesPoc = me
- payments and promises: collectionPoc = me
- customers: a seat OR an owned invoice (shown as the chip `myBook`)

The frontend's pre-applied chips for clearable users come from `/api/pocs/my-scope`.

**4. Table framework (backend).** Every list is `GET /api/{entity}?page&size(10|20|50)&sort&filter=field:op:value` (repeated).
- `TableQuery.parse` validates the request against the static `TableSchemas`.
- `TableQueryExecutor` builds JPA Criteria: scope predicates AND filter predicates, a `desc(id)` tiebreak, and a separate COUNT. `run`, `count`, `ids` (bulk/export, capped at 5000) and `aggregate` (tiles) all share the same predicates.
- The response is a `PageResponse` envelope.
- `/summary` returns tiles, `/bulk` goes through `BulkExecutor` (per-record `REQUIRES_NEW`; results are succeeded / failed / skipped), and `/export` returns a CSV string.

**5. Promise domain.** `PaymentPromise` belongs to a customer, links M:N to invoices (an empty list means a general promise) and M:N to payments, and has a required Collection POC plus override fields and `brokenNotifiedAt`.
- `PaymentPromiseService.evaluate()` recomputes links, fulfilment and status as a pure recompute.
- It runs on promise create/update/clearOverride, on every payment or invoice mutation (`reevaluateForCustomer`), and from `PromiseSweepScheduler`, every 15 minutes and at startup, which only picks up OPEN promises.
- A BROKEN result notifies the Collection POC once.

**6. Audit.** `AuditController` delegates to the new `AuditTimelineService`, with `includeRelated` (related records) and derived rows (`id=null`) for legacy records. For customer callers, `withoutPocIdentity` strips POC events and every `*poc*` key. Invoice and payment create now write `*_CREATED`/`PAYMENT_RECORDED`.

**7. Frontend table framework.**
- The URL is the source of truth: `RouteQuery.read` turns page/size/sort/`f`/`cleared` into a `TableQuery`.
- `DataTableScaffold<T>` builds a value-equal `TableRequest` that keys `tablePageProvider` and `tableSummaryProvider` (both autoDispose). `tableSchemaProvider` is keyed by entity only and is never disposed.
- Every change goes through `context.go`.
- Page size is saved per device in SharedPreferences.
- Locked filters render as lock chips; the clearable default comes from `pocDefaultScope`.
- The global customer scope bar was deleted and replaced by a `customerId` filter chip.

**8. Frontend detail screens.** `/customers|invoices|payments/:id?tab=` use `DetailScaffold`: lazy tabs, the tab written back to `?tab=`, a pinned tab bar below 760px, and `RecordUnavailable` on 403/404.
- Inline edits are a PUT for customers and a PATCH `{notes, poc}` for invoices and payments.
- `CustomerPocEditor` manages the seats. `PromisesTab`, `PromiseFormDialog` and `OverrideDialog` handle promises.
- `RecordPaymentDialog` pre-fills the primary Collection POC and can link promises.
- The invoice form requires a Sales POC.
- The dashboard now reads `/api/invoices/summary` and `/api/promises/summary`.

**Fixed during the review, so not listed as bugs:**
- Scaffold and inline-cancel dialogs now pop `dialogContext`.
- `AuditHistoryPanel` accepts a null id and sends `includeRelated`.
- Customer audit JSON is POC-stripped.
- Invoice and payment create are now audited.

## Features

### A. POC roles, assignment, scoping

**Backend:** privilege/Privileges.java; config/DataSeeder.java (upsertRole 157-163, createRoleIfAbsent 166-172); poc/PocType, CustomerPoc, CustomerPocRepository, PocService (requireAssignable 54-72, add/remove/setPrimary 97-185, notifyAssignee 198-206), PocController (/api/pocs/assignable|types|my-scope), ScopeResolver (build 126-141, forCustomers 83-101, forPromises 74-77, forDisputes 104-112); UserRepository.findAssignable 23-38 / hasPrivilege 40-46; UserController.delete 154-174 (deactivate when referenced); CurrentUser.canAssignPoc; InvoiceService.create/update + PaymentService.record/update (requireAssignable); CustomerController /{id}/pocs + bulk ADD_POC; TableSchemaController hides pocRestricted columns for customers; DTOs null POC fields when !canSeePoc; AuditTimelineService.withoutPocIdentity strips POC data for customer callers.

**Frontend:** features/poc/poc_providers.dart (canSeePocProvider, canAssignPocProvider, myPocScopeProvider, assignablePocsProvider limit 25, customerPocsProvider); poc_picker.dart (PocPicker search dialog, PocMissingBadge); customer_poc_editor.dart; the invoice form Sales POC picker with self-preselect; POC pickers on the invoice/payment detail screens; record_payment_dialog.dart pre-fills the primary Collection POC; pocDefaultScope in promises_screen.dart:21-24 plus per-entity *DefaultScope; lock chips from lockedFilters in data_table_scaffold.dart; reference_picker.dart 'pocUser' merges three /assignable calls.

**Status then:** Mostly implemented. Gaps:
- POC-restricted filter and sort columns are still accepted from customer callers.
- The locked book applies only to lists, tiles and bulk, not to GET/PATCH by id or to the POC seat endpoints.
- ADMIN gets its own-book chip pre-applied; a CS POC gets none.
- Inactive POCs can't be picked in filters.
- Deactivated users keep JWT access.
- PATCH re-validates an unchanged POC.

### B. Payment Promise

**Backend:** promise/PaymentPromise.java (entity; getRemainingAmount floors at 0); PaymentPromiseService (create/update/reassign/cancel/override/clearOverride 69-205, evaluate/targetStatus/linkPayments/computeFulfilment 213-363, attachPayment/reevaluateForCustomer/sweepOverdue 371-421, page/tiles/toDto 425-530, resolveCollectionPoc/resolveInvoices/notifyBroken 534-577); PaymentPromiseController (/api/promises list/summary/get/create/update/cancel/override/bulk/export; withContext 157-163); PaymentPromiseRepository (findOverdueOpen: OPEN only); PromiseSweepScheduler (15 min plus ApplicationReadyEvent); hooks in PaymentService.record/voidPayment/updateAmount and InvoiceService.create/cancel/cancelWithRefund/replaceItems; TableSchemas.PROMISES 171-201 (no invoiceId column).

**Frontend:** features/promises/promises_screen.dart (list, pocDefaultScope, pickPocParams, PromiseRedirectScreen for /promises/:id which goes to /customers/{cid}?tab=promises); promises_tab.dart (PromiseCard, raise/edit/cancel/override); promise_form_dialog.dart (create/edit showing shortfall/excess, OverrideDialog); promise_providers.dart (scopedPromisesProvider, promiseDetailProvider, openPromisesForCustomerProvider); shared/models/promise.dart; the record-payment dialog links promiseIds; dashboard promise tiles.

**Status then:** Implemented. Broken or wrong:
- The Promises tab on invoice detail returns 400 (invoiceId filter).
- Tiles double-count fulfilment.
- A general promise is judged against today's outstanding balance, so it can re-break later.
- The sweeper skips PARTIALLY_KEPT.
- Editing a promise fails after one of its invoices is cancelled.
- Override can revive a cancelled promise.

### C. Detail screens (customer / invoice / payment)

**Backend:** GET /api/{customers|invoices|payments}/{id} (restricted to the caller's customer only; 403 vs 404); PUT /api/customers/{id}; PATCH /api/invoices/{id} {notes, salesPocUserId} and PATCH /api/payments/{id} {notes, collectionPocUserId} (the controller requires POC_ASSIGN for any non-null POC id, and the service calls requireAssignable before comparing); /api/customers/{id}/pocs*; GET /api/audit?entityType&entityId&includeRelated (AuditTimelineService, derived rows); scoped /api/disputes and /api/promises for the tabs.

**Frontend:** shared/widgets/detail_scaffold.dart (DetailTab, lazy _visited, ?tab slug, NestedScrollView below 760px, RecordUnavailable); features/customers/customer_detail_screen.dart; invoices/invoice_detail_screen.dart; payments/payment_detail_screen.dart (allocation rows link to invoices); disputes/disputes_tab.dart; promises/promises_tab.dart; audit/audit_history_panel.dart (includeRelated, derived rows); router.dart detail routes (int.parse of the id). Saving invalidates the detail provider, tablePageProvider, tableSummaryProvider and auditHistoryProvider.

**Status then:** Implemented. Gaps:
- A notes-only save fails when the POC is inactive.
- The unsaved-changes guard only catches pops.
- Server field errors are not attached to fields.
- The tab doesn't follow browser back/forward, and the same route with a different id reuses stale seeded state.
- A malformed id crashes the router.
- The payment Promises tab shows all of the customer's promises.

### D. Table framework (paging / sort / filter / bulk / export)

**Backend:** common/query/*: TableQuery.parse/parseUnpaged, TableSchema(s), ColumnDef, ColumnType, FilterSpec, FilterOperator, FilterParams (getParameterValues), FilterPredicates, ValueCoercion, DateRange, TableQueryExecutor (run/count/ids/aggregate, BULK_ID_LIMIT 5000), PageResponse, TableSchemaController (/api/table-schemas); common/bulk/*: BulkExecutor (REQUIRES_NEW per id), BulkDtos, Csv. Every list controller uses resolveIds (intersects explicit ids with the first 5000 scoped and filtered ids).

**Frontend:** core/table/table_models.dart (TableFilter wire format, TableQuery, PagedResult); table_providers.dart (tableSchemaProvider not autoDispose; tablePageProvider and tableSummaryProvider autoDispose, keyed by the full TableRequest; PageSizeStore); route_query.dart (read/push, cleared=1); data_table_scaffold.dart (tiles, filter bar, lock chips, selection toolbar, bulk confirm and result dialogs, CSV dialog, DataTable at 760px and wider, cards below that, pager); filter_editor.dart; reference_picker.dart; router.dart (_RouterRefresh).

**Status then:** Works end to end. Problems:
- Explicit bulk/export ids that are out of scope or past the first 5000 are silently dropped.
- Export is capped at 5000 with no signal.
- Tiles refetch on every page flip.
- The schema cache survives logout.
- Checkbox selection, and therefore export, requires *_MANAGE.
- The default chip is missing after a refresh or deep link.
- Dropdowns built on these lists are capped at 50.

### E. Summary tiles and dashboard

**Backend:** GET /api/{invoices|payments|customers|promises}/summary: TableQuery.parseUnpaged, then service.tiles, then TableQueryExecutor.aggregate (same scope and filter predicates; COALESCE; Aggregates.countWhen/sumWhen/asLong/asMoney). InvoiceService.tiles 190-216, PaymentService.tiles 247-271, CustomerService.tiles 77-91 (correlated outstanding subquery), PaymentPromiseService.tiles 480-507.

**Frontend:** _SummaryTiles in data_table_scaffold.dart (its own loading and error states); tableSummaryProvider (drops page/size/sort from the params, but the provider key still includes them); per-screen tile builders; dashboard_screen.dart:13-23 reads /api/invoices/summary and /api/promises/summary without filters.

**Status then:** Met for server-side aggregation, empty sets returning zeros, and scope parity with the list. Problems:
- The promise fulfilledAmount/keptAmount tiles double-count.
- The dashboard ignores the POC default book, so its numbers differ from the lists.
- Tiles reload on every page or sort change.
- Money is formatted through a double.

## Requirement gaps noted then

- **AC-A5 inactive POC keeps records, drops out of dropdowns, and records naming them stay findable** (partial): Deactivation works, and so does exclusion from the dropdown. Gaps:
- The filter picker only offers active assignable users (reference_picker.dart:35-56), so records naming an inactive POC can't be found from the UI.
- A notes-only save on a record with an inactive POC returns 400.
- An inactive primary seat is still used as the default promise Collection POC.
- The user's existing JWTs stay valid.
- **AC-A6 default scope is a visible chip, locked when not clearable** (partial): Gaps:
- ADMIN (assignable as every POC kind and clearable) gets its own-book chip pre-applied on invoices, payments and promises.
- A CS POC gets no default chip anywhere.
- The customers chip (collection seat) differs from the server's myBook definition.
- The chip is missing after a refresh or deep link if my-scope hasn't loaded.
- Chips show raw ids.
- The locked book is enforced only on list, tile and bulk endpoints, not on GET, PATCH or cancel by id.
- **AC-A7 every POC assignment change audited** (partial): Seat add/remove/primary changes, invoice and payment updates, and invoice and payment create are audited. Not audited:
- auto-promotion of the next primary after the primary is removed
- demotion of the previous primary by add(primary=true), which records before=null
- **AC-A8 no POC identity for customer-scoped users** (partial): Covered: DTOs, schema columns and audit JSON (withoutPocIdentity) are stripped. Gaps:
- TableQuery.parse still accepts filter and sort on salesPocName, salesPocUserId and collectionPoc* from customer callers.
- The promise DTO exposes overriddenByUserId and createdByUserId.
- The frontend schema cache can show POC filter columns to a customer after an admin session in the same app.
- **AC-B5 status correct in lists, filters and totals without opening the record** (partial): The sweeper only re-examines OPEN promises. A PARTIALLY_KEPT promise that should become KEPT once its date has passed stays stale.
- **AC-B6 idempotent and re-entrant evaluation** (partial): All the hooks are wired. However, re-evaluating historical promises against current outstanding balances can flip a kept general promise to BROKEN after a later invoice is created, sending a new notification.
- **AC-B7 general promise judged on outstanding balance at the promised date** (partial): customerOutstanding() (PaymentPromiseService.java:355-363) uses current balances.
- **AC-B8 collections privilege, Collection POC defaults to primary** (partial): The default primary is not checked for being active or assignable. Promise POC reassignment needs only PROMISE_MANAGE, whereas invoices and payments also require POC_ASSIGN.
- **AC-B10 broken promise notifies once, deep-linking to the promise** (partial): Notify-once works but is not concurrency-safe. The deep link lands on the customer's Promises tab without identifying the promise.
- **AC-B12 many-to-many without double counting** (missing): The fulfilledAmount and keptAmount tiles sum per-promise fulfilment. General promises link every payment, and overlapping invoice-scoped promises share allocations, so one payment is counted more than once.
- **AC-C1 / AC-C4 invoice Promises tab and deep links** (missing): GET /api/promises?invoiceId=N always returns 400 because the PROMISES schema has no invoiceId column, so the invoice detail Promises tab always shows an error. A non-numeric id in the route crashes int.parse.
- **AC-C3 unsaved edits prompt before navigating away** (partial): Only PopScope and the header back button prompt. Shell nav, dispute and allocation row taps, and browser URL or history changes all use context.go and discard edits without asking.
- **US-C2 validation errors shown against the field** (partial): apiErrorMessage ignores ApiError.fieldErrors. Over-long notes on PATCH give a 500 (no @Size).
- **US-C3 selected tab survives refresh and history** (partial): Refresh works. Browser back/forward changes ?tab but not the visible tab. Going from /invoices/1 to /invoices/2 in place reuses the State with invoice 1's seeded notes and POC.
- **C.3 Promises tab shows the promises attached to the record** (partial): Payment detail shows every promise for the customer, not the ones linked to this payment.
- **AC-C6 mobile detail layout** (partial): The header's trailing Wrap sits unconstrained inside a Row and can overflow at about 360px.
- **AC-C8 clean not-found/forbidden** (partial): RecordUnavailable handles 403 and 404. A malformed id crashes the route builder. A locked-book POC can open any record by id.
- **AC-D1 one request per change** (partial): The page fetch is single. However, /summary is refetched on every page, size or sort change, and on first load the list is fetched unfiltered and then again once my-scope resolves.
- **AC-D4 view restored from URL** (partial): page, size, sort and filters round-trip. The POC default chip is not re-applied on a deep link or refresh: _RouterRefresh calls GoRouter.refresh, which does nothing for an unchanged URL.
- **AC-D5 / AC-D6 per-record bulk results, ineligible rows reported** (partial): REQUIRES_NEW per record works. But resolveIds silently drops explicit ids that are out of scope or beyond the first 5000 filtered ids, so they are never reported (BulkActionTest:155 asserts this). Already-cancelled or paid invoices are attempted and reported as failed rather than skipped.
- **AC-D7 select-all over the filtered set** (partial): Works up to 5000. truncated is computed as >= 5000, so exactly 5000 rows is flagged as truncated. Export gives no truncation signal.
- **AC-D8 every mutation audited** (partial): Not audited: notification bulk MARK_READ/UNREAD, product delete, customer delete. Every invoice or payment save writes a no-op INVOICE_UPDATED or PAYMENT_UPDATED even when nothing changed.
- **AC-D9 server-side validation** (partial): Gaps:
- An invalid sort direction is silently treated as asc.
- A large page*size overflows int and returns 500.
- A bad bulk pocType or userId, or a bad /pocs/assignable type, returns 500.
- POC-restricted columns are accepted from customers.
- **D.1 page size persisted per user** (partial): The SharedPreferences key table.pageSize.<entity> is per device and is not cleared on logout.
- **D.3 bulk toolbar incl. export selected** (partial): Rows are selectable only with *_MANAGE, so roles that hold EXPORT_DATA or POC_ASSIGN without MANAGE never reach Export or Add POC. Mobile cards have no page select-all and no sort control.
- **D.4 filters, chips, clear all** (partial): Reference chips show raw ids (e.g. 'Sales POC is 12'). Clear all removes the role's default instead of resetting to it (a deliberate choice).
- **AC-D11 distinct empty/loading/error** (partial): A page past the end (for example after a bulk cancel empties the last page) shows 'No X match this filter' and 'Page 3 of 2'.
- **AC-E3 exact decimal** (partial): The backend sums BigDecimal, but formatMoney parses the value into a double before display.
- **AC-E4 / AC-E6 dashboard and tiles match the caller's default book** (partial): The dashboard sends no default-book chip, so for clearable POCs and ADMIN its numbers differ from the list pages.
- **AC-A1 POC roles seeded idempotently** (unclear): Met for first boot and re-runs. The POC roles are frozen after creation, so privileges added in future code will never reach them. DataSeederTest mutates the shared SALES_POC role and never reverts it.
- **§10 permissions: export only for what you can view** (unclear): /api/users/export, /api/roles/export and /api/disputes/export require only EXPORT_DATA, so a CASHIER or POC can export every user's email, all roles and all disputes. The matrix implies view plus export.

## Where to look for a symptom

| Symptom | Look at |
|---|---|
| Invoice 'Payment Promise' tab shows error / 400 Unknown column: invoiceId | backend/.../common/query/TableSchemas.java:171-201 (PROMISES columns); promise/PaymentPromiseController.java:157-162 withContext; frontend/lib/features/promises/promise_providers.dart:21-33 |
| Any list returns 400 (bad size/sort/filter) | common/query/TableQuery.java:20-53 (size must be 10/20/50, sortable check); TableSchema.java:24-50 (require/requireFilterable); FilterSpec.java:15-48 (chip parsing, comma split only for multi-valued operators); ColumnType.java (allowed operators); frontend core/table/table_models.dart (wire format) |
| Filter returns wrong rows / dates off by a day | common/query/FilterPredicates.java:22-104 (neq and notIn also match NULL; lte and between use end of day); ValueCoercion.java:41-54 (UTC start and end of day); DateRange.java presets; TableSchemas computed columns (balance 50-53, outstanding 110-119, remaining 195-201, POC seat EXISTS 125-159) |
| Rows repeat or are skipped across pages / wrong total | common/query/TableQueryExecutor.java:35-68 (paging, count), 134-144 (id tiebreak); PageResponse.java |
| Table not refreshing after save / bulk / override | frontend core/table/data_table_scaffold.dart:123-127 (_refresh invalidates only this request); detail screens' save handlers (invalidate tablePageProvider/tableSummaryProvider/auditHistoryProvider); promises_screen.dart:151-154 (override invalidates only promiseDetailProvider); promises_tab.dart (invalidates only scopedPromisesProvider) |
| Tiles reload or flash on page change / tiles disagree with the dashboard | frontend core/table/table_providers.dart:52-61 (summary keyed by the full TableRequest); dashboard_screen.dart:11-23 (no default chip); backend *Service.tiles + TableQueryExecutor.aggregate 87-98 |
| Promise tile totals too high (fulfilled/kept > collected) | promise/PaymentPromiseService.java:299-342 (linkPayments/computeFulfilment), 480-507 (tiles sum fulfilledAmount) |
| Promise status wrong / stale / flipped to BROKEN unexpectedly | PaymentPromiseService.evaluate 213-240, targetStatus 247-281, customerOutstanding 355-363 (uses current balances); PaymentPromiseRepository.findOverdueOpen 25-27 (OPEN only); PromiseSweepScheduler; reevaluateForCustomer callers in PaymentService (record 84-85, void 160, updateAmount 204) and InvoiceService (create, cancel, cancelWithRefund, replaceItems) |
| Duplicate or missing 'Promise broken' notification | PaymentPromiseService.notifyBroken 565-577 (brokenNotifiedAt), resets at 156/181/235; sweepOverdue 410-421 single transaction; PromiseSweepScheduler.java:22-36 |
| Cannot edit a promise (400 invoice cancelled / POC inactive) | frontend promise_form_dialog.dart:64-71,117-126,292-304; backend PaymentPromiseService.update 103-135, resolveInvoices 544-563, requireAssignable |
| Saving notes on an invoice or payment fails with a POC error | frontend invoice_detail_screen.dart:79, payment_detail_screen.dart:80 (always resend the POC); InvoiceController.java:78-80 / PaymentController.java:87-89 (POC_ASSIGN check); InvoiceService.update ~101-118 / PaymentService.update ~95-112 (requireAssignable before comparing); PocService.requireAssignable 54-72 |
| User sees only 'my' records or an unexpected chip / missing chip on list open | backend poc/PocController.java:56-66 (my-scope: sales/success/collection = isAssignableAs, clearable = SCOPE_OVERRIDE); ScopeResolver.build 126-141 (lockedFilters); frontend promises_screen.dart:21-24 pocDefaultScope, invoices_screen.dart:27-28, payments_screen.dart:26-27, customers_screen.dart:36-37; core/table/route_query.dart:24,32 (f/cleared); router.dart:32,166-173 (_RouterRefresh no-op) |
| POC user can see or edit records outside their book | ScopeResolver (lists only); InvoiceService.get/getInternal 146-161; PaymentController.get 66-75; CustomerService.get 118-126; PocService add/remove/setPrimary 97-185 (no book check); forPromises 74-77 (Sales POC unscoped) |
| Customer user can see or infer POC names | TableQuery.parse / TableSchema.requireFilterable (no pocRestricted check); TableSchemaController.java:41; AuditController + AuditTimelineService.withoutPocIdentity (current mitigation); PromiseDtos overriddenByUserId/createdByUserId; frontend table_providers.dart:38-42 (schema cache across logout) |
| Bulk action result missing ids / requested count lower than selected / truncated flag wrong | <Entity>Controller.resolveIds (e.g. InvoiceController.java:147-155, filtered by permitted::contains, 5000 cap); truncated computed at line ~98; common/bulk/BulkExecutor.java:39-63 (skipped vs failed) |
| Bulk or export returns 500 | CustomerController.java:128-145 (PocType.valueOf, Long parse); BulkDtos.longParam line 30; GlobalExceptionHandler (IllegalArgumentException mapped to 500) |
| Checkboxes / Export button not visible for a role | frontend list screens: selectable: canManage (customers_screen.dart:69, invoices_screen.dart:60, payments_screen.dart:64, promises_screen.dart:47, products_screen.dart:51); data_table_scaffold.dart _SelectionToolbar (Export only appears with a selection) |
| CSV export truncated / exports data the user can't view | TableQueryExecutor.run (MAX_VALUE becomes maxResults 5000); *Controller.export; UserController.java:210-222, RoleController.java:64-85, DisputeController.java:80-102 (EXPORT_DATA only); common/bulk/Csv.java |
| Customer / product / role missing from a dropdown | frontend customers_screen.dart:20-27 allCustomersProvider, products_screen.dart:15-22, users_screen.dart:15-29 (size=50); ScopeResolver.forCustomers (a Sales POC only sees its book); users_screen.dart:219-223 (role orElse: list.first) |
| POC dropdown search doesn't filter / inactive POC can't be picked | frontend features/poc/poc_picker.dart:44-49,103-131 (the dialog doesn't rebuild after the debounce); poc_providers.dart:139-148 (limit 25); core/table/reference_picker.dart:35-56; UserRepository.findAssignable 23-38 (active only) |
| Wrong default Collection POC on a new promise or payment | PaymentPromiseService.resolveCollectionPoc 534-542 / PocService.primaryFor 92-95 (no active check); frontend record_payment_dialog.dart:60-71 (overwrites a manual pick), promise_form_dialog.dart:86-99 |
| History tab empty / wrong / missing POC or promise events / crashes | backend audit/AuditController.java (history, ensureCallerCanSee, SUPPORTED), audit/AuditTimelineService.java (includeRelated, derived rows with null id, withoutPocIdentity/stripPoc 125-145, 258-280); frontend features/audit/audit_history_panel.dart (AuditEntry.fromJson ~48, includeRelated key 65-73); PocService audit calls 123/157/177 |
| Unsaved edits lost without prompt / wrong record's values on a detail screen / tab doesn't match URL | frontend customer_detail_screen.dart:132-140 (PopScope only), invoice_detail_screen.dart:44-49 (_loadedInto seed-once), shared/widgets/detail_scaffold.dart:60-89 (initialTabSlug read once); go_router pageKey is the route pattern |
| Detail page crashes / error screen on a bad URL | frontend core/router.dart:66,91,108,125,136 (int.parse, no errorBuilder); detail_scaffold.dart:270-277 notFoundMessage/RecordUnavailable |
| Field validation error shown only generically / 500 on long notes | frontend core/api/api_client.dart:44-52 apiErrorMessage (ignores fieldErrors); backend InvoiceDtos.UpdateInvoiceRequest / PaymentDtos.UpdatePaymentRequest (no @Size), Invoice.java:53, Payment.java:40 |
| Deleted user still able to call the API | backend auth/JwtAuthFilter.java:36-40 (no isEnabled check); user/UserController.java:154-174 (delete becomes deactivate) |
| Page shows 'No X match this filter' though a count exists / pager says 'Page 3 of 2' | frontend core/table/data_table_scaffold.dart:165-193 (empty state and pager); table_models.dart:178-179 |
| Bell count stale after bulk mark read | frontend data_table_scaffold.dart:123-127; notifications_screen.dart:44-48; notifications_providers.dart unreadCountProvider (30s poll) |
| Slow list / export (customers, payments, promises, disputes) | CustomerService.java:145-148 (findByCustomerId per row); PaymentService.page 227-231 plus PaymentDto.from (lazy allocations); PaymentPromiseService.toDto 510-530 (canSeePoc per row); DisputeService.toDto 147,197-200; ScopeResolver repeated hasPrivilege COUNTs 45-62; PaymentPromiseService.reevaluateForCustomer 391-396 (every promise × every payment) |
| Page size not remembered / different user gets my page size | frontend core/table/table_providers.dart:66-98 PageSizeStore (key table.pageSize.<entity>); app.dart:19-22 hydrate; router.dart:35 sizeFor |
| Seeded role missing a new privilege after upgrade | config/DataSeeder.java:157-172 (upsertRole overwrites built-in roles; createRoleIfAbsent never updates POC roles); privilege/Privileges.java ALL |
