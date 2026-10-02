# BE-09b review (adversarial, read-only)

## Definition of done (re-run by reviewer)
- backend `mvn -q -o test`: exit 0. Surefire reports: 406 run, 0 failures, 0 errors, 0 skipped (log: build/be09b-review-test.log).
- mail-service `mvn -q -o test`: exit 0. 24 run (18 + 6), 0 failures, 0 errors (log: build/be09b-review-mail.log).
- Probe run in a scratch copy (build/be09brv, test `ZProbe9bTest`, log build/be09brv/probe.log). The repository was not touched.

## Scope checked
- The full diff BE-09a..working tree for backend/.
- DESIGN §2 D8–D10, §5.7.12–5.7.17, §8.4 (AsOfListTest, AsOfDetailTest, RoundTripTest) and §9.0/§9.1 BE-09a–BE-10.
- LOG-backend BE-09a and BE-09b.

Verified correct:
- The rehydrate code for Payment, Promise and Dispute sets every attribute. RoundTripTest checks this through the metamodel.
- Allocations come from `validAtBy(paymentId)` at the same point, and links come from `linksAt`. No recursive rebuild happens.
- By-id reads for payment, promise and dispute go through `Records`, then `requireVisible` with the region at T. `inScope` goes through `Temporal.from`, so book checks use the book at T.
- Customer-login checks are unchanged.
- The credit endpoint answers at T through `CustomerService.get`.
- `EmailTargets.loadUnscoped` and the PRODUCT branch of `loadScoped` use `Records`.
- The live dispute export is still correct. The OSIV config keeps lazy regions loadable.
- No new clock reads, no new SQL, and no write paths are affected.

## Findings

### 1. MEDIUM: The dispute list and export at T run version queries for every row, not once per page (N+1)
- **Where:**
  - `DisputeController.java:55` (list) and `:100` (export) map every row through `DisputeService.toDto`.
  - `toDto` calls `records.find` for the customer (`DisputeService.java:170`) and again for the target (`:237` invoice, `:241` payment).
  - Each `Records.find` builds a fresh `Rehydration` (`Records.java:68`), so nothing is shared between rows or kinds. An INVOICE target also rebuilds that invoice's customer, sales POC, region and product names. A PAYMENT target rebuilds its allocations, their invoices and their products.
- **Probe:** `GET /api/disputes?asOf=D4` with 2 rows read `customer_versions` 4 times (2 per row) and `_versions` 10 times in all. The payment page at D2 read `customer_versions` once, because it is batched by the executor.
- **Scale:** a 20-row page runs about 100 version queries. The export (up to `BULK_ID_LIMIT` = 5000 ids) runs tens of thousands.
- **Why it matters:** this breaks the §5.7.15 rule ("one query per kind for a whole page") and the BE-09a gotcha ("Rehydration uses batch loaders only"). `AsOfQueryCountTest` guards this rule for invoices only.
- **Fix:** add a `toDtos(List<Dispute>, access)` that:
  - prefetches customers with `records.findAll(Customer.class, ids)`;
  - prefetches INVOICE targets with `records.findAll(Invoice.class, …)` and PAYMENT targets with `records.findAll(Payment.class, …)`, one batch per kind;
  - is used by the list and the export.
- **Test:** add a dispute case to `AsOfQueryCountTest`.

### 2. MEDIUM: An acceptance item of §8.4 is not met: the product email tab over HTTP still refuses a past date
- **Spec:** §8.4 AsOfDetailTest (BE-09b) says "the product email tab (`GET /api/emails?entityType=PRODUCT&entityId=`) answers at T (BE-09b; CF-23)".
- **Actual:** `EmailController.java:67` and `:76` still throw `AsOfException.unsupported()`, so the tab answers 400 `AS_OF_UNSUPPORTED`. The new test `AsOfDetailTest.theRecordBehindAnEmailTabIsFoundAsItWas` only exercises `EmailTargets` at the service level.
- **Assessment:** the reason given is sound. The `occurredAt <= p` filter is BE-10's `EmailService.list` item, and removing the guard now would serve today's emails under a historical header (C-1). But the acceptance test the design names does not exist.
- **Fix:** record this in CARRY-FORWARD.md or the BE-10 hand-off. BE-10's `AsOfTabsTest` must assert `GET /api/emails?entityType=PRODUCT&entityId=…&asOf=D` returns 200 with only the emails up to D, and that the controller guards are removed.

### 3. LOW: `EmailTargets.load(DISPUTE, id)` still reads live invoices and payments, so it fails at T. The LOG says otherwise.
- **Where:** `EmailTargets.java:304-307` (`disputeOwnFields`, called from `describe` at `:287`) uses `invoiceRepository.findById` and `paymentRepository.findById`.
- **Probe:** `AsOf.with(endOfDay(D4), () -> emailTargets.load(DISPUTE, disputeId))` throws `AsOfException: This page can't be shown as of a date` (LiveReadGuard).
- **Test gap:** the new test calls only `requireVisible` for DISPUTE, and `requireVisible` never reaches `describe`. LOG-backend still says "It does the same for CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE without tripping `LiveReadGuard`."
- **Current impact:** none over HTTP. `/api/emails/context` is `@AsOfIgnored`, and the list and get are guarded.
- **Why fix it:** §5.7.17 intends every EmailTargets record lookup to answer at T, and BE-10's email tab work would trip this path.
- **Fix:** use `records.find(Invoice.class/Payment.class, …)` in `disputeOwnFields`, and have the test call `load` for every type.

### 4. LOW: The credit test cannot tell the balance at T from today's balance
- **Where:** `AsOfDetailTest.aCustomersCreditIsAnsweredAsItWas` asserts only `customerName`.
- **Problem:** in `AsOfScenario`, Acme's `creditBalance` is 0 on every day, live included. The 400 payment is fully allocated and the void creates no credit. A credit endpoint that took the balance from the live row would still pass. The balance is this endpoint's only figure.
- **Fix:** give Acme a credit at D2 (for example an overpayment) that changes later, then assert `creditBalance` at D2 against live.

### 5. LOW: `AllocationHistory.rehydrate` ignores the rows it is given, so RoundTripTest's allocation case does not test a round trip
- **Where:** `AllocationHistory.java:55-67`. It looks up each allocation by id among the allocations of `ctx.at(Payment)` at `ctx.point()`, and uses nothing from the `PaymentAllocationVersion` rows passed in except their ids.
- **Effect on RoundTripTest:** the ALLOCATION round trip compares live rows against the rows in the database at "now", not against the snapshot the test just built. A snapshot or rehydrate mismatch for allocations would not be caught. It is caught indirectly only through `PaymentHistory`, which builds the allocations.
- **Contract:** the `TypeHistory` contract ("rehydrate these versions") holds only when the rows are the ones valid at `ctx.point()`. That is true for every current caller.
- **Fix:** document the precondition on `AllocationHistory.rehydrate`, or assert it (throw if a row passed in differs from the one on the rebuilt payment).

## Not raised (checked, acceptable or documented)
- **`DisputeController POST /export` switched to `records.findAll`.** It is outside the file list but needed, and mirrors `ProductController`. Live rows now follow the sort order, which is harmless.
- **An allocation hangs off the full memoised `HistoricalInvoice`, not a shallow one.** It is documented. It costs one extra `product_versions` batch per page, which is bounded.
- **The flaky `HistoryConcurrencyTest.twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink`.** It is pre-existing BE-07 code and was not reproduced in this run. It is recorded in LOG-backend but not in CARRY-FORWARD.md; the orchestrator should add it there.
- **`SnapshotTest` helpers made package-visible.** It is an earlier-slice test file, not baseline, so this is allowed.
