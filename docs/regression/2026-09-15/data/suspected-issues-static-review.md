# Suspected issues from static code reading (UNVERIFIED)

Produced before several fixes landed. Already fixed since: the invoice Promises tab 400 (invoiceId column),
ADMIN opening lists pre-filtered to its own book (lists now open unfiltered), stale detail-screen state when
moving between records, the POC default chip on deep link, the expired-session 403, FAB/pagination overlap,
dialogs popping the page, the History timeline. Treat every item as a lead to test, not as a fact.

1. [high] (Promise / Table framework) Invoice detail 'Payment Promise' tab always fails with 400: PROMISES schema has no invoiceId column
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseController.java:157-162 (adds 'invoiceId:eq:N'); backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:171-201 (no invoiceId); TableSchema.java:24-28; frontend/lib/features/promises/promise_providers.dart:21-33; frontend/lib/features/invoices/invoice_detail_screen.dart:170-174
   - scenario: Opening /invoices/5?tab=promises sends GET /api/promises?customerId=1&invoiceId=5, which fails with 400 'Unknown column: invoiceId'. The tab shows an error, so you can't view or raise a promise from an invoice (US-B2, US-C4). The design doc §6 lists invoiceId as a link-table filter. No test covers it.

2. [high] (Detail screens / POC) Notes-only save on invoice or payment fails when its POC is inactive or the editor lacks POC_ASSIGN
   - where: frontend/lib/features/invoices/invoice_detail_screen.dart:48,79; frontend/lib/features/payments/payment_detail_screen.dart:49,80; backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:78-80; InvoiceService.java:~106 (requireAssignable before comparing with the previous POC); PaymentController.java:87-89; PaymentService.java:101
   - scenario: The client always resends the unchanged POC id, and the backend re-validates it before comparing. After the invoice's Sales POC is deactivated (the AC-A5 delete path), editing only the notes returns 400 'User x is inactive and cannot be assigned'. A role with INVOICE_MANAGE but not POC_ASSIGN gets 400 'You may not change the Sales POC'. Each save also writes a no-op audit row.

3. [high] (Promise) Promise edit permanently blocked once one of its invoices is cancelled or its Collection POC is deactivated
   - where: frontend/lib/features/promises/promise_form_dialog.dart:64-71 (seeds invoiceIds and POC), 117-126 (PUT always sends invoiceIds and POC), 292-304 (list shows only UNPAID/PARTIALLY_PAID); backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:117-128, 544-563
   - scenario: A dispute cancels INV-5, which a promise covers. Every later PUT, even one that only changes the date, returns 400 'Invoice INV-5 is cancelled and cannot be promised against', and INV-5 isn't in the checklist, so it can't be unticked. The same happens with an inactive POC. 'Raise promise' from a cancelled invoice's page fails the same way.

4. [high] (Table framework / bulk) Bulk and export silently drop explicit ids that are out of scope or beyond the first 5000 filtered rows
   - where: backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:147-155 (same resolveIds in Payment/Customer/PaymentPromise/Product/User/Notification controllers and Role/Dispute export); common/bulk/BulkExecutor.java:39-57; test BulkActionTest.java:155 asserts the drop
   - scenario: An admin with 8000 invoices selects 3 rows on page 350 and runs CANCEL. The permitted list holds only the first 5000 ids in sort order, so the result is requested=0 with nothing done and nothing reported. A locked Sales POC sending [mine, theirs] gets requested=1 with no skipped entry. This violates AC-D5, AC-D6 and design doc §8.

5. [high] (Promise) General promise re-judged against today's outstanding balance, so a kept promise flips to BROKEN when a later invoice is created
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:268,278,355-363 (customerOutstanding uses current balances); InvoiceService.create calls reevaluateForCustomer
   - scenario: A debt-free customer promises 500 by the 10th, and on the 10th the promise is KEPT. On the 20th a new invoice is created, re-evaluation sees the date has passed with onTime=0 and outstanding>0, and the promise becomes BROKEN. That writes PROMISE_STATUS_CHANGED and sends a 'Promise broken' notification (AC-B6, AC-B7).

6. [high] (Promise / Tiles) fulfilledAmount and keptAmount tiles double-count shared payments
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:299-311 (a general promise links every payment since createdAt), 314-342, 497 (sum(fulfilledAmount))
   - scenario: Two general promises of 100 each and one payment of 100: both promises link the payment, both are KEPT with fulfilled=100, and the summary shows fulfilledAmount=200 and keptAmount=200 against 100 collected. Overlapping invoice-scoped promises behave the same way. This violates AC-B12.

7. [high] (Promise) Sweeper selects only OPEN promises, so PARTIALLY_KEPT status goes stale after the promised date
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseRepository.java:25-27; PaymentPromiseService.java:264-265,275,410-421
   - scenario: A promise of 100 against a 500 invoice is paid 100 early, so it is PARTIALLY_KEPT. Once the date passes, targetStatus gives KEPT, but findOverdueOpen never selects it. The list, filters and tiles keep showing PARTIALLY_KEPT until some unrelated payment or invoice write for that customer (AC-B5).

8. [high] (Permissions) Export endpoints check only EXPORT_DATA, not the entity's VIEW privilege
   - where: backend/src/main/java/com/geneinvoice/user/UserController.java:210-222; role/RoleController.java:64-85; dispute/DisputeController.java:80-102
   - scenario: CASHIER and the POC roles hold EXPORT_DATA without USER_VIEW or ROLE_VIEW (and CASHIER lacks DISPUTE_VIEW). POST /api/users/export {selectAllMatchingFilter:true} returns every username, full name and email; /api/disputes/export returns all disputes.

9. [high] (POC / Auth) Deactivated ('deleted') users keep API access through existing JWTs
   - where: backend/src/main/java/com/geneinvoice/auth/JwtAuthFilter.java:36-40; user/UserController.java:154-167
   - scenario: Deleting a user who is named as a POC now only deactivates them. JwtAuthFilter loads the user and authenticates without checking isEnabled(), so the user keeps full access for the token's lifetime (24h). Before this change, the delete made the lookup fail.

10. [high] (POC / Table framework) Customer-scoped callers can filter and sort on POC-restricted columns
   - where: backend/src/main/java/com/geneinvoice/common/query/TableQuery.java:37-50; TableSchema.java:24-50 (never checks pocRestricted); the only check is TableSchemaController.java:41
   - scenario: A CUSTOMER user calls GET /api/invoices?filter=salesPocName:contains:ra, or sort=salesPocName,asc, or filter=collectionPocUserId:eq:12 on promises. The request is accepted, and match counts reveal POC identity (AC-A8). The promise DTO also returns overriddenByUserId and createdByUserId.

11. [high] (POC / Frontend table) ADMIN lists open pre-filtered to the admin's own POC book
   - where: backend/src/main/java/com/geneinvoice/poc/PocController.java:60-65 (sales/success/collection = isAssignableAs); frontend/lib/features/promises/promises_screen.dart:21-24; invoices_screen.dart:27-28; payments_screen.dart:26-27; router.dart:163-164
   - scenario: ADMIN holds every POC_ASSIGNABLE_* privilege plus SCOPE_OVERRIDE, so opening /invoices pre-applies salesPocUserId:eq:<admin>, and payments and promises pre-apply collectionPocUserId:eq:<admin>. The admin sees only their own records, often an empty list, while the dashboard shows everything.

12. [high] (Frontend table) Export and bulk actions unreachable for roles that have EXPORT_DATA or POC_ASSIGN but not *_MANAGE
   - where: frontend/lib/features/customers/customers_screen.dart:68-69; invoices_screen.dart:59-60; payments_screen.dart:63-64; promises_screen.dart:46-47; products_screen.dart:50-51; core/table/data_table_scaffold.dart (the toolbar needs a selection)
   - scenario: selectable is set to canManage. CASHIER can't export Products or Promises. COLLECTION_POC can't export Invoices or Customers, or run 'Add POC'. SALES and CS POCs can't export Payments. The buttons are declared but no row can be selected to reveal them.

13. [high] (Frontend forms) Customer, product, user and role pickers silently capped at 50 rows (regression from unbounded lists)
   - where: frontend/lib/features/customers/customers_screen.dart:20-27; products/products_screen.dart:15-22; users/users_screen.dart:15,22-29; used at invoice_form_screen.dart:112 and record_payment_dialog.dart:112
   - scenario: With 51 or more customers, the 51st by name can't be invoiced or paid from the UI. Inactive products take up slots because they are filtered client-side. In the user edit form, a role outside the first 50 falls back to orElse: list.first, which could preselect ADMIN; saving would then escalate the user.

14. [high] (POC frontend) POC picker search lags one keystroke behind (the dialog doesn't rebuild after the debounce)
   - where: frontend/lib/features/poc/poc_picker.dart:44-49 (the debounce updates the outer State), 120-131 (onChanged calls setDialogState immediately while _search is still stale)
   - scenario: Type 'ali' and stop: the list stays unfiltered or shows the previous query until another key is pressed, because the outer setState doesn't rebuild the dialog route. This affects every POC dropdown (AC-A3).

15. [high] (Detail screens) Unsaved-changes guard bypassed by in-app navigation
   - where: frontend/lib/features/customers/customer_detail_screen.dart:132-140 (PopScope only; same pattern in invoice and payment detail); shared/widgets/app_shell.dart:120,165,208; features/disputes/disputes_tab.dart:97; features/payments/payment_detail_screen.dart:255
   - scenario: Edit the payment notes, then click the 'Invoices' nav item, a dispute row or an allocation row. context.go replaces the page with no prompt and the edits are lost. Browser back/forward does the same (AC-C3).

16. [high] (Promise frontend) Promise override from the Promises list leaves the row and tiles stale
   - where: frontend/lib/features/promises/promises_screen.dart:151-154; promise_form_dialog.dart:307+ (showOverrideDialog invalidates nothing)
   - scenario: Overriding OPEN to KEPT from a list row invalidates only promiseDetailProvider(p.id), so the row still reads Open and the Open and Kept tiles are unchanged until the user navigates.

17. [high] (Frontend table / Tiles) Summary tiles refetched on every page, size or sort change
   - where: frontend/lib/core/table/table_providers.dart:52-61 (family keyed by the full TableRequest; page/size/sort are removed only from the params)
   - scenario: Clicking Next page fires both GET /api/invoices and GET /api/invoices/summary, and the tiles flash a spinner each time (AC-D1).

18. [high] (Backend performance) Customer list DTO mapping makes one user query per row (N+1)
   - where: backend/src/main/java/com/geneinvoice/customer/CustomerService.java:145-148
   - scenario: toDtos calls userRepository.findByCustomerId once per customer just to fill in username. That is 50 extra selects per page and up to 5000 on export, despite the comment saying a fixed number of queries. Related per-row N+1 patterns: the payments list lazy-loads allocations and invoices (PaymentService.java:227-231); promise toDto runs a canSeePoc COUNT and lazy-loads invoices and payments (PaymentPromiseService.java:510-524); dispute toDto does a per-row customer lookup (DisputeService.toDto:147,197-200).

19. [high] (Frontend table) Bulk mark read/unread doesn't refresh the unread badge
   - where: frontend/lib/core/table/data_table_scaffold.dart:123-127 (_refresh); features/notifications/notifications_screen.dart:44-48
   - scenario: After bulk 'Mark read' on 10 notifications, the bell count stays unchanged until the next 30-second poll of unreadCountProvider.

20. [medium] (Promise) Override can revive a CANCELLED promise
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:166-188
   - scenario: POST /api/promises/{id}/override {status:OPEN, reason:'x'} on a cancelled promise succeeds, because override() never checks the current status. Once the status is no longer CANCELLED, a later clearOverride or evaluate relinks payments, silently undoing the cancel.

21. [medium] (POC scope) Locked 'my book' not enforced on GET, PATCH or cancel by id
   - where: backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:146-161 (get; update and cancel use getInternal); payment/PaymentController.java:66-75; PaymentService.java:91-93; customer/CustomerService.java:118-126
   - scenario: sam.sales (no SCOPE_OVERRIDE) can GET, PATCH or POST /cancel another rep's invoice by id, and read /api/customers/{id}, its seats, and /api/audit, even though lists and bulk exclude those records. A Collection POC can PATCH any payment.

22. [medium] (POC) POC seat endpoints have no book or role check: any POC_ASSIGN holder can edit any customer's seats
   - where: backend/src/main/java/com/geneinvoice/poc/PocService.java:97-185; customer/CustomerController.java:92-113
   - scenario: A SALES_POC locked to their own book, or a COLLECTION_POC or CASHIER, can POST or DELETE /api/customers/{anyId}/pocs and change the primary. US-A3 limits this to an admin or CS POC.

23. [medium] (Promise / POC) Default promise Collection POC can be an inactive or unassignable primary
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:534-542; poc/PocService.java:92-95 (primaryFor)
   - scenario: The customer's primary Collection POC is deactivated, and the seat keeps its primary flag. A promise created without collectionPocUserId is assigned that inactive user, and 'promise broken' notifications go to a dead account.

24. [medium] (POC / Frontend table) Inactive POCs can't be selected in the POC filter, so their records can't be found
   - where: frontend/lib/core/table/reference_picker.dart:35-56; backend/src/main/java/com/geneinvoice/user/UserRepository.java:23-38
   - scenario: The 'pocUser' options come from /api/pocs/assignable, which returns active users only, 25 per type. After a Sales POC is deactivated, no one can build salesPocUserId:eq:<them> except by editing the URL (AC-A5).

25. [medium] (POC / Invoice form) Sales POC can't raise the first invoice for a customer outside their book
   - where: frontend/lib/features/invoices/invoice_form_screen.dart:112 (allCustomersProvider); backend/src/main/java/com/geneinvoice/poc/ScopeResolver.java:83-101 (forCustomers = a seat OR an owned invoice)
   - scenario: A SALES_POC without SCOPE_OVERRIDE only sees customers they already hold a seat on or have invoiced. Sales isn't a customer seat type, so a brand-new customer never appears in their New invoice dropdown.

26. [medium] (Frontend routing) POC default chip not applied on deep link or refresh; double fetch and a flash of unfiltered rows
   - where: frontend/lib/core/router.dart:32,166-173 (_RouterRefresh triggers GoRouter.refresh, which does nothing for an identical URL); core/table/route_query.dart:24,32
   - scenario: A Collection POC reloads /payments before my-scope resolves: the builder sees a null scope, fetches all payments and shows no chip, and never re-seeds. On a first navigation, if the router does rebuild, the list is fetched twice. If the user sorts before the scope loads, push() writes cleared=1 and the default is lost permanently.

27. [medium] (Frontend table / POC) tableSchemaProvider cached per entity across logout/login
   - where: frontend/lib/core/table/table_providers.dart:38-42 (not autoDispose); features/auth/auth_controller.dart:62-65 (logout invalidates nothing)
   - scenario: An admin logs out and a customer logs in on the same app instance. The customer's 'Add filter' still offers Sales POC and Collection POC, and the backend executes those filters (AC-A8). The reverse order hides POC filters from the admin.

28. [medium] (POC / Frontend table) Customer Success POC gets no default book; the client chip's definition differs from the server's myBook
   - where: frontend/lib/features/customers/customers_screen.dart:36-37 (collection && !success); backend ScopeResolver.java:97-99
   - scenario: A CUSTOMER_SUCCESS_POC (clearable) opens Customers, Invoices and Promises unfiltered, contrary to D1. When the customers chip does apply, it filters on collectionPocUserId, whereas the server's book is 'any seat or any owned invoice'.

29. [medium] (Detail screens) Same-route navigation reuses stale seeded detail state, so one invoice's edits can be saved onto another
   - where: frontend/lib/features/invoices/invoice_detail_screen.dart:44-49 (_loadedInto seed-once; customer and payment detail _seed use the same pattern); go_router pageKey is the route pattern
   - scenario: On web, going from #/invoices/1 to #/invoices/2 reuses the State. The notes field and Sales POC still hold invoice 1's values, and Save PATCHes them onto invoice 2. Browser back/forward over ?tab also leaves the tab unchanged (detail_scaffold.dart:79-89 ignores initialTabSlug updates).

30. [medium] (Promise) Explicitly linked payment can contribute 0 to an invoice-scoped promise; stale links survive re-allocation
   - where: backend/src/main/java/com/geneinvoice/payment/PaymentService.java:57-68,84; promise/PaymentPromiseService.java:295,328-332
   - scenario: The cashier ticks promise P (on INV-2) but no invoices. applyTo allocates oldest-first to INV-1, so P shows the payment as linked yet fulfilled stays 0 and P can go BROKEN. After updateAmount re-allocates away from the promised invoice, the link also stays, contributing 0.

31. [medium] (Promise performance) Unbounded O(promises × payments) re-evaluation inside every payment or invoice write
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:391-396, 299-311, 355-363
   - scenario: findLiveByCustomer returns every non-cancelled promise ever made, including historical KEPT and BROKEN ones. Each iterates all of the customer's payments and lazily loads their allocations. A customer with 200 promises and 2000 payments makes every payment record or invoice create very slow, all inside the caller's transaction.

32. [medium] (Detail screens / validation) Server field validation errors never shown against fields; over-long notes give 500
   - where: frontend/lib/core/api/api_client.dart:44-52; features/customers/customer_detail_screen.dart:104-105; backend InvoiceDtos.java:25-28 and PaymentDtos.java:27-31 (no @Size; Invoice.notes length 500, Payment.notes 300)
   - scenario: A 400 carrying fieldErrors {name:'must not be blank'} shows only the generic 'One or more fields are invalid' under the form. PATCH with a 301-character payment note fails at flush with DataIntegrityViolation, which comes back as a 500 (US-C2).

33. [medium] (Frontend table) Out-of-range page renders as 'no rows match this filter'
   - where: frontend/lib/core/table/data_table_scaffold.dart:165-172 (empty state), pager ~782; table_models.dart:178-179
   - scenario: Bulk-cancelling every row on the last page, or opening a URL with page=99, shows 'No invoices match this filter' and 'Page 3 of 2' even though totalElements > 0 (AC-D11).

34. [medium] (Frontend routing) Malformed detail id crashes the route builder
   - where: frontend/lib/core/router.dart:66,91,108,125,136 (int.parse, no errorBuilder)
   - scenario: /invoices/abc throws a FormatException in the GoRoute builder, showing a framework error screen instead of RecordUnavailable (AC-C8).

35. [medium] (Table framework / bulk) Export silently capped at 5000; truncated flag off by one
   - where: backend/src/main/java/com/geneinvoice/common/query/TableQueryExecutor.java:49-57,71-81; InvoiceController.java:98 (truncated = size >= 5000) and the same line in the other bulk controllers
   - scenario: Exporting 12,000 matching payments returns 5000 rows with no signal (plus a wasted COUNT). A bulk select-all matching exactly 5000 rows reports truncated=true. Fetching limit+1 would fix both.

36. [medium] (Payment frontend) Record-payment default POC overwrites the user's manual pick
   - where: frontend/lib/features/payments/record_payment_dialog.dart:60-71,138-143
   - scenario: The cashier picks POC 'amy' and then selects a customer. After the frame, customerPocsProvider replaces the pick with the customer's primary, or with null if there is none.

37. [medium] (Detail screens) Payment detail Promises tab lists every promise for the customer, not this payment's
   - where: frontend/lib/features/payments/payment_detail_screen.dart:170-177
   - scenario: A payment linked to P1 shows P1 to P9 and offers 'Raise promise' (C.3).

38. [medium] (Backend validation) Customer bulk ADD_POC and /pocs/assignable return 500 on bad input; validation errors misfiled as skipped
   - where: backend/src/main/java/com/geneinvoice/customer/CustomerController.java:128-145; common/bulk/BulkDtos.java:30; poc/PocController.java:26-34
   - scenario: pocType='foo' makes PocType.valueOf throw, giving a 500. userId='abc' gives a NumberFormatException, also 500. pocType=SALES, or an inactive user, marks every row 'skipped' instead of returning one 400. A caller without POC_ASSIGN gets 400 rather than 403. GET /api/pocs/assignable?type=bogus returns 500.

39. [medium] (Tiles) Dashboard tiles ignore the POC default book, so they disagree with the list pages
   - where: frontend/lib/features/dashboard/dashboard_screen.dart:11-23
   - scenario: A clearable Collection POC, or ADMIN, sees organisation-wide promise and invoice tiles on the dashboard, while /promises opens with their own-book chip and smaller totals (AC-E4, AC-E6).

40. [medium] (Frontend table) Page size remembered per device, not per user
   - where: frontend/lib/core/table/table_providers.dart:70,88-98
   - scenario: The key 'table.pageSize.<entity>' has no user id and is not cleared on logout, so user B inherits user A's 50-row setting (D.1).

41. [medium] (Detail screens) Header trailing Wrap overflows on phones
   - where: frontend/lib/shared/widgets/detail_scaffold.dart:177-197
   - scenario: At 360px, an invoice header with the back button, POC-missing badge, status chip and 'Raise dispute' overflows the RenderFlex (AC-C6).

42. [low] (Table framework / bulk) Ineligible invoices attempted and counted as failed rather than skipped
   - where: backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:101; InvoiceService.cancel ~219-236; BulkExecutor.java:49-52
   - scenario: A bulk CANCEL that includes an already-cancelled or paid invoice throws BadRequestException, which is reported under failed. AC-D6 wants ineligible rows excluded and reported (products and users use IneligibleException, which reports them as skipped).

43. [low] (POC audit) Primary auto-promotion on remove and demotion on add(primary) not audited
   - where: backend/src/main/java/com/geneinvoice/poc/PocService.java:113,148-161
   - scenario: Removing the primary CS POC silently promotes the next seat, and the only audit row is POC_REMOVED. add(makePrimary=true) demotes the old primary but records before=null (AC-A7).

44. [low] (Permissions) Promise POC reassignment lacks the POC_ASSIGN check that invoices and payments enforce
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseController.java:98-117; PaymentPromiseService.java:117-124; frontend promises_screen.dart ~93-101 (gated on canManage && canSeePoc)
   - scenario: A custom role with PROMISE_MANAGE but not POC_ASSIGN can reassign Collection POCs on promises (bulk or PUT), but not on payments.

45. [low] (Promise) Sweep runs as one all-or-nothing transaction; notify-once is not concurrency-safe
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:409-421,565-577; PromiseSweepScheduler.java:25-29
   - scenario: One promise that throws rolls back the entire sweep every 15 minutes, logging only a warning. If the sweeper and a concurrent void both see brokenNotifiedAt=null, they send two notifications (no @Version or lock).

46. [low] (Table framework / Promise) Relative date presets, date-only bounds and the promise deadline use UTC
   - where: backend/src/main/java/com/geneinvoice/common/query/FilterPredicates.java:66-79; ValueCoercion.java:43-54; PaymentPromiseService.java:248,346
   - scenario: For an IST user at 02:00 IST, 'today' is still the previous UTC date. A promise due on the 20th breaks only at 05:30 IST on the 21st. On Postgres, the 23:59:59.999999999 upper bound may round to the next midnight (tests run only on H2).

47. [low] (Table framework) Int overflow on a huge page number gives 500; sort direction not validated
   - where: backend/src/main/java/com/geneinvoice/common/query/TableQueryExecutor.java:50; TableQuery.java:22-23,37-40
   - scenario: page=50000000&size=50 overflows setFirstResult and returns 500. sort=total,sideways is silently treated as asc (AC-D9).

48. [low] (Permissions / Audit) USER audit history readable by POC roles without USER_VIEW
   - where: backend/src/main/java/com/geneinvoice/audit/AuditController.java (SUPPORTED includes USER; staff callers get no type-level check)
   - scenario: A SALES_POC holding AUDIT_VIEW calls GET /api/audit?entityType=USER&entityId=1 and receives USER_UPDATED snapshots containing the admin's email and privileges.

49. [low] (POC scope) Sales POC sees every promise despite lacking SCOPE_OVERRIDE
   - where: backend/src/main/java/com/geneinvoice/poc/ScopeResolver.java:74-77,134
   - scenario: forPromises checks only whether the caller is COLLECTION-assignable. A SALES_POC isn't, so it gets an empty scope and sees all promises and tiles, which contradicts the confidential per-rep rationale.

50. [low] (POC) Customer pocMissing flag disagrees with its doc and with the per-kind tiles
   - where: backend/src/main/java/com/geneinvoice/customer/CustomerService.java:171 vs CustomerDtos.java:19
   - scenario: The flag is computed as success.isEmpty() || collection.isEmpty(), but the doc says it means no seat of either kind. The badge count matches neither missingSuccessPocCount nor missingCollectionPocCount.

51. [low] (Frontend forms) POC field mandatory on submit but hidden without POC_VIEW
   - where: frontend/lib/features/invoices/invoice_form_screen.dart:77,150; payments/record_payment_dialog.dart:87,147
   - scenario: A custom role with INVOICE_MANAGE but not POC_VIEW never sees the picker, yet submit requires _salesPoc, so it can never create an invoice. The same applies to payments.

52. [low] (Frontend forms) Invoice form self-preselect scans only the first 25 assignable Sales users
   - where: frontend/lib/features/invoices/invoice_form_screen.dart:54-67,114-120 (the backend's /api/invoices/assignable-check is unused)
   - scenario: With more than 25 assignable users sorted ahead of her by name, a salesperson isn't preselected (US-A2).

53. [low] (Frontend formatting) Money formatting goes through a double
   - where: frontend/lib/core/format.dart:7-19
   - scenario: formatMoney uses num.tryParse, which yields a double, contrary to the 'exact decimal' comment. Large values would display rounded (AC-E3); this is cosmetic at 2dp.

54. [low] (Promise frontend) Promise deep link lands on the customer's Promises tab without identifying the promise
   - where: frontend/lib/features/promises/promises_screen.dart:225-231
   - scenario: A 'promise broken' notification for #9 opens /customers/{cid}?tab=promises with nothing highlighting #9 (AC-B10). The post-frame go() is also rescheduled on every rebuild.

55. [low] (Frontend table) Mobile card layout has no page select-all and no sort control
   - where: frontend/lib/core/table/data_table_scaffold.dart:267-329
   - scenario: Below 760px there is only a checkbox per card, with no header checkbox and no sort UI (D.3, AC-D12).

56. [low] (Frontend) Notification actions lack error handling
   - where: frontend/lib/features/notifications/notifications_screen.dart:27-31,82-86
   - scenario: If mark-read fails, a DioException surfaces uncaught and _open also aborts navigation to the link.

57. [low] (Detail screens) Detail-tab dispute and promise lists truncated at 50 with no paging
   - where: frontend/lib/features/disputes/disputes_providers.dart:27-40; promises/promise_providers.dart:21-55; promise_form_dialog.dart:292-304; record_payment_dialog.dart:283-295
   - scenario: A customer with 60 promises or disputes shows 50 in the tab with no 'more' indicator. The record-payment and promise dialogs also cap outstanding invoices and open promises at 50.

58. [low] (Promise frontend) Promise date picker assertion for old promises
   - where: frontend/lib/features/promises/promise_form_dialog.dart:171-176
   - scenario: Editing a promise whose date is more than 365 days in the past passes an initialDate earlier than firstDate to showDatePicker, which asserts in debug builds.

59. [low] (Backend) Export maps entities to DTOs outside the service transaction (depends on open-in-view)
   - where: backend/src/main/java/com/geneinvoice/promise/PaymentPromiseController.java:123-127; invoice/InvoiceController.java:64-87; payment/PaymentController.java:68-90
   - scenario: Lazy invoices, payments, items and allocations are touched in the controller. Setting spring.jpa.open-in-view=false would throw LazyInitializationException. The code also has O(n*m) ids.contains filtering.

60. [low] (Tests / Seeding) DataSeederTest mutates the shared SALES_POC role and never reverts it
   - where: backend/src/test/java/com/geneinvoice/config/DataSeederTest.java:73-83
   - scenario: PROMISE_MANAGE added to SALES_POC stays in the shared H2 context, so later permission tests depend on test order. Separately, POC roles are frozen after first creation (DataSeeder createRoleIfAbsent), so future privileges never reach them.

61. [low] (Frontend routing) GoRouter recreated on every auth change and never disposed
   - where: frontend/lib/core/router.dart:28-39
   - scenario: routerProvider ref.watch-es auth, so each login or logout builds a new GoRouter and _RouterRefresh without disposing the old ones. On web, logging back in can land on the page-load URL instead of '/'.

62. [low] (Frontend) Dead notificationsProvider still expects a bare list
   - where: frontend/lib/features/notifications/notifications_providers.dart:8-12
   - scenario: Unused today, but wiring it up would throw a cast error because /api/notifications now returns a PageResponse.
