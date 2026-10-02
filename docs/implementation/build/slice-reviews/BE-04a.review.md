# BE-04a review: Regions, writes and bulk

## Definition of done (re-run by the reviewer)
- `cd backend && mvn -q -o test`: exit 0. `backend/target/surefire-reports` (fresh, 19:47) shows 240 run, 0 failures, 0 errors, 0 skipped. Log: `build/be04a-review-test.log`.
- `cd mail-service && mvn -q -o test`: exit 0. 24 run, 0 failures, 0 errors. Log: `build/be04a-review-mail.log`.
- Slice diff: `build/be04a-review.diff` (`sdiff.sh BE-03-impl backend/src/main`). No baseline test file was edited.
- Probe: a copy of the backend in `build/be04a-probe`; the repo was not touched.
  - `ProbeBe04aTest` (log `build/be04a-probe.log`) is the recompute probe for finding 1.
  - A lock-removal mutant (log `build/be04a-probe-mut.log`) is the mutation check for finding 2.

## Findings (most severe first)

### 1. MEDIUM: `/api/promises/recompute` ignores the POC book and customer-login confinement; it reads and rewrites promises the caller cannot see
- **Location:** `B/promise/PaymentPromiseService.java:478-505`.
  - `:480` takes the regions from `regionsWith(PROMISE_OVERRIDE)`, narrowed by `ViewScope`.
  - `:482` then walks **every** customer with live promises in those regions.
  - The result is `RecomputeChange(promiseId, customerId, customerName, amount, promisedDate, status…)`, and with `apply=true` the promises are saved.
- **Why it is wrong:**
  - Every by-id path on the same promises is book-aware. `getForWrite` → `requireVisible(PROMISE)` answers 404 outside the book.
  - The bulk and list paths use the book predicate.
  - Recompute is the one set path that does not.
  - It also ignores D17 (a customer login sees only its own customer). `RegionRights.forCustomer` makes `regionsWith` return the login's own region, so the login is shown every customer in that region.
- **Proof** (`build/be04a-probe.log`):
  - Setup: a seeded COLLECTION_POC role (it holds PROMISE_OVERRIDE) with a DEFAULT **MANAGE/BOOK** grant. "Other Ltd" has a promise owned by another collector.
  - `GET /api/promises/{other}` gives 404, and `POST …/{other}/override` gives 404.
  - `POST /api/promises/recompute` returns `{"customerName":"Other Ltd","amount":40.00,…}`.
  - `?apply=true` rewrites Other Ltd's promise (`fulfilledAmount` 1.00 → 0.00).
  - A customer login whose role also holds PROMISE_OVERRIDE gets both "Mine Ltd" and "Other Ltd" in the dry run.
- **Spec status:** the §5.6.6 row literally says only "Regions = ViewScope.narrow(regionsWith(PROMISE_OVERRIDE))". It conflicts with B1 (book per region, every figure scoped) and with SC-12's rule that a BOOK-tier POC may neither write to nor read back records outside its book.
- **Fix:**
  - Staff: only recompute customers in regions where `tier(PROMISE, VIEW, r, PROMISE_OVERRIDE) == FULL`. For BOOK regions, restrict to customers whose promises match `scope(PROMISE, MANAGE, SELECTED, PROMISE_OVERRIDE).predicates()`, or leave BOOK regions out.
  - Customer login: restrict to its own customer.
  - Add a matrix row with a BOOK user.

### 2. MEDIUM (test): nothing pins the customer-first lock order, which is half of this slice's goal
- **Location:** the new locks at:
  - `InvoiceService.lockForWrite` (`:305-316`)
  - `PaymentPromiseService.getForWrite` (`:552-563`)
  - `DisputeService.mustBePending` (`:185-198`)
  - `reevaluateForCustomer` (`:464`), `recomputeAll` (`:483`) and `sweepOverdue` (`:521`)
- **Mutation:** the probe copy deletes every `customerRepository.findByIdForUpdate(customerId)` line added in those six places. The full suite still passes: 240 run, 0 failures, 0 errors (`build/be04a-probe-mut.log`).
- **Impact:**
  - CF-8, SC-7 and SC-19 (the slice goal "the customer-first lock order on every multi-row write path") are not protected by any test.
  - The implementer's list of mutations does not include this.
  - The first race tests are planned for BE-14/BE-15 (`ApprovalConcurrencyTest`, `ApprovalLifecycleTest`), so a regression before then goes unnoticed.
- **Fix:** Add a cheap test. Either:
  - use `CountingStatements` / a statement inspector to assert that the first `for update` in invoice update/cancel, promise update/cancel/override and dispute approve/deny is on `customers`; or
  - write a two-thread test: hold the customer lock in one transaction and assert that the invoice/promise/dispute write blocks.

### 3. LOW (test): the bulk and POC-change assertions in `RegionAccessMatrixTest` are weaker than they look
- **Location:** `T/region/RegionAccessMatrixTest.java:357-361`.
- **Detail:**
  - Promise bulk `REASSIGN_COLLECTION_POC` reassigns to `collections`, who is already every promise's collection POC. The per-row `update` therefore never enters the POC-change branch (`PaymentPromiseService.java:130-142`), and "succeeded" proves only which ids were selected.
  - Nothing exercises bulk resolution for customers (`ADD_POC`, MANAGE + POC_ASSIGN) or payments.
  - Dropping the `POC_ASSIGN` extra from any `resolveIds(…, MANAGE, POC_ASSIGN)` call would not fail a test, because Rita holds POC_ASSIGN wherever she holds MANAGE.
- **Fix:**
  - Reassign to a different collector and assert that the S promise changed and the N one did not.
  - Add one case with a role that has `X_MANAGE` but not `POC_ASSIGN`, so REASSIGN rows come back NOT_REACHABLE.

### 4. LOW: customer update and delete read the region they authorise against before taking the customer lock (SC-7 analogue)
- **Location:** `B/customer/CustomerService.java:159-166` (`requireManageable`: an unlocked `findById`, then `requireIn(CUSTOMER_MANAGE, c.region)`).
  - `delete` takes the customer lock only later, inside `documentCascade.onCustomerDeleted` (`DocumentParentLock.lockCustomer`).
- **Scenario (once BE-08's move exists):**
  1. A user with CUSTOMER_MANAGE only in NORTH starts a delete of a NORTH customer.
  2. A concurrent move to SOUTH commits between the region read and the lock.
  3. The delete passes `requireIn(…, NORTH)`, waits for the move, then deletes a SOUTH customer with all its invoices, payments and promises.
- **Why it matters:** the invoice, promise and dispute paths in this slice deliberately read the region under the customer lock for this reason. The customer's own update and delete do not.
- **Fix:** Use `repository.findByIdForUpdate(id)` in `requireManageable`, so the region is read under the lock; it is re-entrant for the later cascade lock.

### 5. LOW: `CustomerLoginRegionTest` moves rows with raw JDBC, which will trip BE-07's `@AfterEach historyIsInStep`
- **Location:** `T/region/CustomerLoginRegionTest.java:105-110` (`moveAcmeTo`: `jdbc.update("update … set region_id …")` on five tables).
- **Detail:**
  - §9.0 says the BE-07 check applies to every later test.
  - D16 and §5.7 say that any write must be ORM-based or call `HistoryTracker.touch`.
  - After BE-07, the reconciler will see live `region_id` values with no matching open version and fail this test.
- **Fix:** For now, note it in the log so BE-07 knows. In BE-07, add `historyTracker.touch(...)` for the moved ids, or in BE-08 replace the helper with `POST /api/customers/{id}/move-region`.

### 6. LOW: the promise sweep and the recompute dry run hold every customer lock until the end of one transaction
- **Location:** `PaymentPromiseService.sweepOverdue` (`:518-529`) and `recomputeAll` (`:478-505`).
- **Detail:**
  - Both are a single `@Transactional` that now takes `FOR UPDATE` on every affected customer in turn, and releases none until commit or rollback.
  - The sweep runs at `ApplicationReadyEvent` and every 15 minutes. `recompute?apply=false` is a preview that rolls back.
  - Any invoice, payment, promise or POC write on those customers blocks for the whole run. Before this slice, neither path took a lock.
  - There is no deadlock (both lock in ascending id), but it is a latency and 409 risk on a large book.
- **Fix:** Evaluate each customer in its own short transaction (`TransactionTemplate` with `REQUIRES_NEW` per customer; the dry run can stay one transaction but lock nothing, or use `FOR SHARE`).

## Checked and found correct
- **§5.6.6 write inventory for this slice:**
  - customer update/delete (`requireVisible` → `requireLive` → `requireIn(CUSTOMER_MANAGE)`);
  - invoice/payment/promise creates (F1 lock → `requireLive` → book-aware `requireVisible(CUSTOMER)` → `requireIn`, with region copied from the locked row);
  - invoice update/cancel and promise update/cancel/reassign/override/clearOverride (customer → row lock → visibility → live → `requireIn`, region read under the locks);
  - payment update (`requireIn(PAYMENT_MANAGE)`);
  - dispute approve/deny (customer → dispute → visibility → "already resolved" → live → `requireIn(DISPUTE_MANAGE)`).
- **Customer logins:** they stay confined on every new write path, because `ScopeResolver.requireVisible` checks ownership (the BE-02 fix). `getForWrite` did not lose the old `get()` own-customer 404.
- **POC checks:** `canAssignPocIn(region)` on invoice, payment and promise POC changes keeps its 400 (V11). The coarse bulk checks use `canAssignPocAnywhere()`, and the customer bulk still answers 403 (D-46). `CurrentUser.canAssignPoc` is deleted.
- **Bulk access levels:** `idsMatching(query, limit, access, extras…)` matches the §5.6.5 table (MANAGE, MANAGE + POC_ASSIGN for reassign and ADD_POC, VIEW for exports). Rows that are visible but not writable come back as `NOT_REACHABLE`. The per-row service checks agree with the MANAGE tier.
- **New JPQL** (`customerIdOf`, `findCustomerIdsWithLivePromisesInRegions`, `findCustomerIdsWithOverdueOpen`): `distinct` plus `order by` on the same column is valid on Postgres, `in :regionIds` is never empty (short-circuit), and there is no DDL change.
- **Background path:** the sweep calls nothing that needs a principal.
- **Acceptance tests:** `RegionAccessMatrixTest` write rows with byte-identical DB state after each refusal; `CustomerLoginRegionTest` (`regionIds` ignored, invoice 404, dispute 403, following a move); `RegionBookTest` SC-12 create case.
- **Disclosed deferrals:** the flags, documents, email, seats, region-aware `requireAssignable` and POC seat `requireIn` are deferred to BE-04b, matching the BE-04b slice entry.
