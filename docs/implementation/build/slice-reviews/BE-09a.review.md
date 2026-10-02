# BE-09a review (adversarial, read-only)

## Definition of done (re-run by reviewer)
- backend `mvn -q -o test`: exit 0; surefire reports 392 run, 0 failures, 0 errors, 0 skipped (log: build/be09a-review-test.log).
- mail-service `mvn -q -o test`: exit 0; 24 run, 0 failures, 0 errors (log: build/be09a-review-mail.log).

## Scope checked
Full diff BE-08-impl..working tree (backend/), DESIGN §1-2, D2, D8-D9, D14-D17, §5.2, §5.7.11-5.7.17, §6.0, §8.1-8.4, §9.0 and BE-09a..BE-10 entries, LOG-backend (BE-06..BE-09a).
Verified correct: HistoryRange is the single AsOfSupport and NoHistoryYet is deleted. Live SQL is unchanged: Temporal.from/validOrNull/join/subquery collapse to the old code when no point is bound. The executor swaps roots with no fetch joins, and count/ids/aggregate/inScope all go through versions, so the book check in requireVisible is the book at T. The ScopeResolver and TableSchemas subqueries go through Temporal. Records and LiveReadGuard behave as specified. Version associations are LAZY, so loading a version never trips the guard. Customer-login rights use a scalar regionIdOf, so no PostLoad fires. Every WRITE is refused (AsOfReadOnlyTest). The interim AS_OF_UNSUPPORTED guards stop the C-1 failure on the dashboard, docs, emails, audit, POC tab, and the payment/promise/dispute list and by-id endpoints.
Extra probes run in a scratch copy (build/be09arv, probe.log / probe2.log): two filters on one promise link column at T (entity-join aliases in sibling subqueries), payments/promises summary with customerName at T, customers collectionPocUserId:neq at T, invoices customerName filter+sort at T, sort by salesPocName/regionName at T, and credits at T. All returned correct 200 answers.

## Findings

### 1. MEDIUM: A gap in a parent record's history is filled with today's live row, so an as-of answer shows today's data as history without saying so
- Where: `backend/src/main/java/com/geneinvoice/history/Rehydration.java:68-86` (`at`: when there is no version at the point it logs `history.gap.read`, then runs `LiveReadGuard.allow(() -> em.find(live, id))`, then `snapshotAll` and `rehydrate`). It applies to every versioned type. The callers are `InvoiceHistory.rehydrate` (customer, product) and `SeatHistory.rehydrate` (customer).
- Spec: §5.7.15 allows the live fallback only for a product on an invoice line. D9 says a record exists at T only if it has a version valid at p. §5.7.16 says a path must fail rather than pass today's data off as history.
- Reproduced with probe `ZProbe2Test.gapShowsToday` on AsOfScenario. It deletes Acme's `customer_versions` rows, which stands in for a missing version.
  - `GET /api/invoices/{X}?asOf=D2` returns 200 with `"customerName":"Zeta Holdings"`. That is today's name; on D2 the customer was "Acme Ltd".
  - `GET /api/customers/{acme}?asOf=D2` returns 404 on the same request pair. The two answers contradict each other, and the only signal is a WARN in the log.
- Why it is realistic: with `app.history.strict=false` (production), a capture failure on a customer insert is skipped (`history.capture.failed`). The reconciler then inserts the "missing" version valid from now (§5.7.8). So for every T before the repair, invoices and seats at T show the customer's current name, region object and credit balance.
- Fix: keep the live fallback for `Product` only, as the design says. For any other type, throw `AsOfException.unsupported()` (or 404) instead of substituting live state.

### 2. LOW: Seat order in CustomerDto differs between live and as-of on the same data, which breaks the parity BE-10 requires
- Where: `backend/src/main/java/com/geneinvoice/customer/CustomerService.java:218-219`. The live branch uses `customerPocRepository.findByCustomerIdIn(ids)` (`poc/CustomerPocRepository.java:29`, no ORDER BY, so the DB decides the order). The as-of branch uses `HistoryQueries.seatsAt`, which sorts pocType, then primary first, then id (`history/HistoryQueries.java:101-104`).
- Reproduced with probe `ZProbe2Test.seatOrder`: Beta gets a non-primary SUCCESS seat (id 3), then a primary one (id 4).
  - Live `GET /api/customers/{beta}` returns `successPocs` [3, 4].
  - `?asOf=D4` returns [4, 3], with nothing changed in between.
- Impact: the list, detail and export CSV list POCs in a different order at T than on screen that day. BE-10's `AsOfParityTest` (§8.4: every detail must be identical apart from `version` and flags) will fail for any customer whose primary seat is not its lowest id. On Postgres the live order is not even stable.
- Fix: sort the seats with the same comparator in both modes. The simplest place is Java in `toDtos`, which leaves the live SQL unchanged.

## Not raised (checked, acceptable or documented)
- Guards outside the listed files (dashboard, audit, documents, emails, POC tab, and the Payment/Allocation/Promise/Dispute `rehydrate` throws) are documented deviations. They are strictly safer than binding and answering live. BE-09b and BE-10 must remove them.
- `/api/payments/summary` and `/api/promises/summary` already answer from versions, and the probe showed correct values at T. The lists still refuse. This is documented for BE-09b.
- Regions are loaded by a batch query instead of `em.getReference`, and seats are sorted in Java. Both are documented and harmless.
