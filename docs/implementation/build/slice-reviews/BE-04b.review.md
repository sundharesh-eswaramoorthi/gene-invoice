# BE-04b adversarial review

## Definition of done (re-run by reviewer)
- `cd backend && mvn -q -o test`: EXIT 0; surefire totals **256 tests, 0 failures, 0 errors, 0 skipped** (matches the report).
- `cd mail-service && mvn -q -o test`: EXIT 0; **24 tests, 0 failures, 0 errors**.
- No baseline test file edited (test diff touches only `region/*` and `auth/PrincipalRightsTest`, both created by BE-02..BE-04a).

## Spec coverage check (all present)
RecordAccess and the §6.5 flags on Customer/Invoice(+Summary)/Payment/Promise/Dispute; the audit factories pass null flags. Region-aware `DocumentDto` flags, and document writes under `requireIn` x2. `requireAssignable(user, type, region)` at all 8 callers plus `requireAssignableByRole` for bulk ADD_POC. `assignable(type, regions, ...)` over `findAssignableInRegions`. The `activeHoldersByType` filter. Seat `add`/`remove`/`setPrimary`, each with `requireLive` and `requireIn(POC_ASSIGN)`. `/assignable?customerId|regionId`, `/my-scope.regions[]`, `assignable-check?customerId`. `EmailTargets.regionId`, `loadForSend`, and record-level holder filter. `EmailAddressing` to/from region check with fieldErrors. `requireLive` on send/bulk/retry. `notifications.region_id` and its index. `notify(..., regionId)`, the link guard, `notifyAdmins(region)`, `notifyHolders`. List/unread/bulk honour `regionIds`. Broken-promise POC filter and fallback, and the POC-assignment notice filter. `findLatestRegionId`, `CUSTOMER_DELETED` region, and deleted-customer `readOrGone`. The R17 dead methods are deleted. `RegionParamCoverageTest` rows are added and `PENDING_ROWS` is empty. No code defect was found that leaks data or gives a wrong answer. The findings below are unguarded required behaviours (confirmed by mutation) and one known scope gap.

## Findings

### 1. medium: the record-level email role holder region filter has no test (mutation survives)
- `backend/src/main/java/com/geneinvoice/email/EmailTargets.java:330-335`: `.filter(ownReachable::contains)` implements §5.6.7 "EmailTargets.target(...) applies withRegionAccess to record-level holders too".
- Reviewer mutation: replacing line 334-335 with `ownFields.getOrDefault(...).stream().toList()`. All 161 tests in `Region*Test, Email*Test, PrincipalRightsTest, CustomerLoginRegionTest` still pass.
- `RegionBookTest.aSeatHolderWithoutTheRegionIsNeitherTheDefaultNorEmailedAsTheRole` covers only the CUSTOMER-level COLLECTION_POC seat, not an invoice/payment/promise's own POC field.
- Failure scenario: an invoice's Sales POC loses the NORTH grant (or, from BE-08, the customer moves). Email `to:[{ROLE, SALES_POC, RECORD}]` on that invoice. A regression that drops the filter would email (and make sender) someone with no access to the record, and nothing fails.
- Fix: add a RegionBookTest/RegionAccessMatrixTest case. Take an invoice whose `salesPoc` has no grant in its region. Assert that ROLE `SALES_POC` at RECORD level is unresolved ("Sales POC is not assigned") and the POC is not among the recipients. Assert the same for the From role.

### 2. medium: the `notifyAdmins` region and active filters have no test (mutation survives the full suite)
- `backend/src/main/java/com/geneinvoice/notification/NotificationService.java:54-60` filters by `User::isActive` and by `regionAccess.withAccess(ids, regionId)` (§5.6.7, BE-04b goal).
- Reviewer mutation: remove both filters (notify every ADMIN-role user). The **entire backend suite still passes, 256/0/0**. `RegionParamCoverageTest.notifications` only checks that an admin who holds every region was told.
- Failure scenario: a SOUTH customer login opens a dispute. An ADMIN-role user with no SOUTH grant, or a deactivated admin, receives "New dispute from …" with the dispute's reason and a link. A regression that drops the filter would leak SOUTH data to NORTH-only admins, and no test catches it.
- Fix: in the matrix/book test, add an ADMIN-role user with a NORTH-only grant and an inactive admin. Open a SOUTH dispute and assert only the SOUTH-granted active admins get `DISPUTE_OPENED`.

### 3. medium: the region check on `POST /api/emails/{id}/retry` has no test (mutation survives)
- `backend/src/main/java/com/geneinvoice/email/EmailService.java:312` changed `requireVisible` to `loadForSend` (EMAIL_SEND in the record's region, §5.6.6 row "send, preview, bulk (per row), retry").
- Reviewer mutation: `targets.loadForSend(...)` changed back to `targets.load(...)`. All 161 region/email tests still pass. `RegionAccessMatrixTest` covers send, preview and bulk, but not retry.
- Failure scenario: rita (NORTH READ, SOUTH MANAGE) retries a failed email about a NORTH invoice. If the check regressed, she would re-send mail about a record in a region where she may not send, and nothing fails.
- Fix: add a matrix row. Retry a FAILED email on a NORTH record as rita and expect 403 with the email row unchanged. Retry on WEST and expect 404.

### 4. low: `POST /api/notifications/mark-all-read` ignores `regionIds`, while the list and count it sits beside honour them
- `backend/src/main/java/com/geneinvoice/notification/NotificationController.java:77-81` calls `service.markAllRead(userId)` with no region predicate.
- The frontend calls it from the notifications screen (`frontend/lib/features/notifications/notifications_screen.dart:28`), and `viewScopeInterceptor` appends `regionIds`.
- Failure scenario: a user narrowed to SOUTH sees 2 unread (SOUTH plus the no-region notice) and taps "Mark all read". Their unseen NORTH notices are also marked read.
- The spec (§5.2.4) classifies it only as housekeeping and is silent on `regionIds`, and the implementer lists it as a known gap, so this is low.
- Fix: apply the same `askedRegions()` predicate, as a JPQL update with `(region_id is null or region_id in :r)`, when `regionIds` is set.

### 5. low: other small required behaviours without tests
- The link guard (`NotificationService.java:38-41`, a link longer than 300 characters is stored as null with a WARN) has no test.
- `DocumentDto` historical flags (`DocumentService.java:226`, `live = !AsOf.active()`) are not asserted. The historical-flag test covers only `RecordAccess.canManage/can`.
- Fix: a unit/integration assertion for each.

## Verified non-issues (checked, no finding)
- `RecordAccess` reads `currentUser.isCustomer()` only after an `idOrNull()` guard, and `hasIn` is false without a principal (C-8). `notifyBroken` from the sweep uses `idOrNull()`.
- Email bulk explicit ids are intersected with `bulkIds(... EMAIL_SEND)`. A row that fails the region check becomes IneligibleException and is skipped, not an aborted batch.
- The notification bulk intersects explicit ids with the region-narrowed scope. Unread count and list agree for narrowed-to-nothing (null-region only).
- All record-concerning notification producers pass a region: POC assignment, dispute opened/resolved, broken promise. Gmail reconnect passes null.
- Seat endpoints do the visibility check (`service.get(id)` in `CustomerController:93-119`) before `lockCustomer` and `requireIn(POC_ASSIGN)`, so W gives 404 and N gives 403 as specified.
- H2/PG: new JPQL is portable (`in :list` is always non-empty or guarded, and `order by` columns are in the `distinct u` select). The `notifications.region_id` column is nullable, with no FK and `@Index`, which is ddl-auto safe (C-5).
