## B1 — Contract fixes the frontend builds against: RuleDto.people, past-date rules list sorts and filters, CORS Location

### Fixed
- **CT-6 / PC-6 / CF-10 (backend part)** — verified: `RuleDto` carried raw `actions` only, no names. Added a last component `Map<Long,String> people` to `RuleDtos.RuleDto` (serialises as `{"12":"Priya Rao"}`). `RuleService.people(JsonNode)` walks the actions tree recursively, collects every object with `"type":"USER"` and an integral `userId` (assignees, collectionPoc, from, to, cc), reads them with one `userRepository.findAllById`, names each with `EmailText.nameOf`; users that no longer exist are left out; `Map.of()` when there are no USER tokens (no query). Filled in both `toDto(AutomationRule, List<String>)` and `toDto(RuleSnapshot)`. `RuleSnapshot` untouched (revision JSON unchanged).
- **PC-2 (backend part)** — verified: past-date list accepted only `name`/`id` sorts and name:contains/enabled/entityType/triggerKind filters. `RuleAsOfLists`:
  - Sorts added: `triggerKind`, `entityType`, `suspendedReason`, `createdAt`, `updatedAt` (and `enabled`, sortable in the schema). Nulls last in both directions; ties broken by id newest first, as `TableQueryExecutor.orderBy` does live. `lastRunStatus`, `lastRunAt`, `nextRunAt` still 400 "This sort isn't available for past dates"; `status` is still refused by `TableQuery.parse` (notSortable).
  - Filters added, each meaning what it means live: `status` (eq/in/neq/notIn; ACTIVE/DISABLED/SUSPENDED as `ruleStatusPredicate`; invalid value gives the identical 400 text), `suspendedReason`/`entityType`/`triggerKind` (eq/in/neq/notIn, enum values checked with the live "Invalid value for column …" text; neq/notIn keep a null reason as live SQL does), `ownerUserId` (eq/in/neq/isEmpty/isNotEmpty), `regionIds` mirroring `ruleRegionPredicate` exactly (isEmpty = unrestricted, isNotEmpty, eq/in = restricted to one of those listed so an unrestricted rule is NOT included, neq = not restricted to any of them so unrestricted IS included, "Expected an id but got" on a bad id), `name` for every TEXT operator the schema offers (contains case-insensitive; eq/neq exact, as the live `cb.equal` is; isEmpty/isNotEmpty = null-or-blank), and `id` (NUMBER operators). There is no STARTS_WITH in `FilterOperator`, so none was added. Filters on `lastRunStatus`, `lastRunAt`, `nextRunAt` still 400 "This filter isn't available for past dates"; `createdAt`/`updatedAt` DATE filters also still refused (not in the triage list; `relative` would need the live date machinery).
- **CF-7** — verified: exposed headers were `Authorization`, `X-View-As-Of` only. Added `HttpHeaders.LOCATION` in `SecurityConfig.corsConfigurationSource`.

### Rejected
- None.

### Tests (each fails with its fix reverted; checked by temporarily reverting all three)
- `RuleApiTest.aRuleNamesThePeopleItsActionsName` — CREATE_TASK assignee USER cara + SEND_EMAIL to USER sam; `GET /api/automation/rules/{id}` people == {cara: name, sam: name}; same with `asOf` the day after the save; a rule with no USER tokens gives `{}`. Without fix: `expected {"25"="CARA.COLLECTIONS","26"="SAM.SALES"} but was {}`.
- `RuleApiTest.theRulesAsOfADateSortAndFilterAsTheyDoLive` — asOf a past date: `sort=triggerKind,asc` and `sort=entityType,desc` are 200 and ordered; `status:eq:ACTIVE`, `ownerUserId:eq:cara`, `regionIds:in:NORTH` (unrestricted excluded), `regionIds:isEmpty:` give the right rows; 9 sorts and 17 filters answer exactly as the live list (nothing changed since); `lastRunAt|lastRunStatus|nextRunAt` sorts and `lastRunAt|nextRunAt|lastRunStatus` filters are 400 with the past-date texts; `status:eq:PAUSED` gives the live 400 text. Without fix: 400 on the first sort.
- `GateInvoiceTest.aCrossOriginCallerMayReadTheLocationOfAHeldChange` — 202 held cancel with an `Origin` header: `Location` present and `Access-Control-Expose-Headers` contains `Location`. Without fix: `["Authorization","X-View-As-Of"]` lacks Location.

### Final counts
- `cd backend && mvn -o test`: 792 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS.
- `cd mail-service && mvn -o test`: 24 tests, 0 failures, 0 errors — BUILD SUCCESS.

## B2 — Security: customer placeholders, related audit rows, requeue of a run by hand, rule regions at action time, assignee VIEW privilege

### Fixed
- **SC-1** (verified): `RecordContexts.renderForEmail` filled in `Customer.*` for PROMISE/DISPUTE from an unscoped `customerRepository.findById`. The INVOICE/PAYMENT branch leaked the same way through `customerOf(entity)`: a book-limited Collection POC emailing on his own payment got the customer's phone, address and outstanding.
  - Both branches now fill in the customer's fields only when `canSeeCustomer(c)` holds. Otherwise they supply `Customer.Name` alone (every record's DTO already shows it), and the other Customer placeholders come out empty with the renderer's usual "X is empty" warnings.
  - One extra warning, `Customer details are left out: you cannot see this record's customer`, is added when the text asks for a Customer field other than the name.
  - Deviation from the triage: the check is `scopeResolver.requireVisible(Kind.CUSTOMER, id, region)` wrapped in try/catch, not `customerService.get`. It is the same rule `customerService.get` applies (a customer login sees its own; staff see through book and regions). But `customerService.get` is `@Transactional`, and a NotFoundException thrown out of it would mark `renderForEmail`'s transaction rollback-only. `PendingChangeScope.visible` uses the same pattern for the same reason. The CUSTOMER target itself is still read through `customerService.get`.
- **SC-2** (verified): `AuditTimelineService.related` took in every related invoice, payment, promise, dispute and allocation. For staff callers it now keeps only the ones the caller can open.
  - New helper `visibleIds(kind, viewPrivilege, ids)`, modelled on `TaskService.visibleAmong`. It requires the kind's VIEW privilege and returns the ids inside `scopeResolver.scope(kind, VIEW, GRANTED)`, chunked, using `queryExecutor.ids` with `kind.schema()` (TableSchemas.INVOICES/PAYMENTS/PROMISES/DISPUTES).
  - An allocation is kept only when both its payment and its invoice are visible.
  - Customer logins (`customerView`) keep today's behaviour, including the internal-dispute exclusion. ScopeResolver scopes them with `ownPredicate`, which passes every record of their own customer, so the filter would do nothing for them.
  - The audit rows of a filtered record, including its CHANGE_* and PAYMENT_APPLIED rows, go with it, because rows are fetched per kept label.
- **SC-3** (verified): `RuleService.requeue` now looks up the event's kind. For a MANUAL event whose rule the caller does not own (or whose rule is gone), it throws 400 `A run by hand is tried again from its run, which takes the rule over: POST /api/automation/runs/{runId}/retry`, with runId from `runRepository.findByEventIdOrderByIdAsc`. This check runs before the DEAD check, so the event is left untouched. Non-MANUAL events are unchanged.
- **SC-4** (verified): `ActionExecutor.act` reads `ViewScopeHolder.current().regionIds()` right after `rc` is loaded. That is the rule's regions narrowed to the Run-now asked regions, as set by `OwnerSession.call`. If they are non-null and do not contain `rc.regionId()`, the action saves a SKIPPED effect `The record is no longer in the rule's regions` (label kept) and returns `Outcome.SKIPPED` before prepare/perform.
  - The save goes through a new `skipped(...)` helper; `skippedAsDone` now delegates to it.
  - The preview (`RulePreviewService.actions`) adds the warning `The record is not in the rule's regions, so the rule would not act on it` to each action when the rule is restricted and the record's region is not one of its regions. It uses the rule's own regions only, not the page's asked regions, because the preview's record is one the caller chose explicitly.
- **CF-9** (verified): `AssigneeService.probe` and `requireVisible` now take `String ownerType` (AssignedTo.TASK/DISPUTE/PROMISE). After the existing record-visibility check, they also require `regionAccess.holds(userId, viewPrivilege(ownerType), regionId)`.
  - `requireVisible` throws a 400 on `fieldErrors.assignees`: "<name> cannot see tasks|disputes|promises here".
  - `probe` drops the person with the warning "<name> cannot see tasks here, so was not assigned".
  - All callers updated: the 7 in the triage, plus the two task-assignee-change calls in `TaskService.update` (:243 and :257), which also go through `requireVisible`, 9 in all.
  - The optional filtering of `/api/assignees/people` by owner type was not done (optional, and it needs a contract change the frontend would have to follow).

### Rejected
- None. One deviation, SC-1's non-transactional visibility check, is explained under SC-1 above. Another in SC-1's test: a SALES_POC cannot open any promise (the promise book is the Collection POC's and the role lacks POC_ASSIGNABLE_COLLECTION), so the PROMISE case uses a book-limited Collection POC. That is the realistic leak path.

### Tests (each fails with its fix temporarily reverted, checked one fix at a time)
- `EmailPlaceholderTest.aCustomerTheCallerCannotOpenLendsAPromiseOrADisputesEmailOnlyItsName`:
  - Setup: sam (SALES_POC) gets 404 on GET /api/customers/{globex} and 200 on the dispute. cody (COLLECTION_POC, MANAGE/BOOK) gets 404 on the customer and 200 on his promise.
  - Emails on the DISPUTE, the PROMISE and cody's PAYMENT with `{{Customer.Name}} {{Customer.Phone}} {{Customer.Address}} {{Customer.Outstanding}}` store exactly `name=Globex phone= addr= out=`. None contains the phone, the address or the outstanding amount, and a `{{Customer.Phone}}` subject renders empty.
  - The preview warns `Customer details are left out: …` and `Customer.Phone is empty`.
  - The same callers on a customer of their own (sam sells an Acme invoice; cody holds Acme's Collection seat) get every value rendered.
  - Without the fix: `expected "About" but was "About +1-555-0100"`. With only the INVOICE/PAYMENT branch reverted: the payment body leaks `+1-555-0100 … 1 Secret Street … ₹1,150.00`.
- `ApprovalAuditTimelineTest.relatedRowsAreOnlyThoseOfRecordsTheCallerCouldOpen`:
  - Setup: sam and tom are SALES_POC with READ/BOOK in NORTH, and a held INVOICE_CANCEL sits on tom's invoice (CHANGE_REQUESTED).
  - sam's `GET /api/audit?entityType=CUSTOMER&entityId=acme&includeRelated=true` has no row of tom's invoice (neither INVOICE_CREATED nor CHANGE_REQUESTED), and has sam's own INVOICE_CREATED.
  - The Acme customer login's includeRelated trail still has both invoices' INVOICE_CREATED.
  - Without the fix, tom's CHANGE_REQUESTED and INVOICE_CREATED rows were returned.
- `WorkerDurabilityTest.aRunByHandGivenUpOnIsNotRequeuedByAnAdministratorWhoDoesNotOwnTheRule`:
  - Setup: owen's disabled MANUAL rule has a run that goes DEAD.
  - admin's POST /events/{id}/requeue returns 400 with the retry-path message. The event stays DEAD, the run stays FAILED, a drain makes no task, and the owner is unchanged.
  - The owner, made an administrator, requeues it: 202, DONE, one task.
  - The existing `aRunGivenUpOnIsTriedAgainByItsOwnerAndAnEventRequeuedByAnAdministrator` still shows a non-MANUAL DEAD event requeued by admin with 202.
  - Without the fix: `Status expected:<400> but was:<202>`.
- `RunAsOwnerTest.aRecordThatMovedOutOfTheRulesRegionsBeforeTheActionIsSkipped`:
  - Setup: a NORTH-only rule and an unrestricted rule on the same owner (MANAGE in both regions). The invoice is updated in NORTH, then its customer moves to SOUTH before the drain.
  - The NORTH rule makes no task, and its effect is SKIPPED with `The record is no longer in the rule's regions`. The unrestricted rule makes its task in SOUTH.
  - The preview of the NORTH rule warns on its action; the unrestricted rule's preview does not.
  - Fails with either half reverted (executor: `Expecting empty but was: [Task]`; preview: warning missing).
- `TaskApiTest.aPersonWhoCannotSeeTasksInTheRegionIsRefusedAsAnAssignee`:
  - Setup: nina holds a collections role without TASK_*, READ/ALL in NORTH, and gets 200 on the invoice.
  - POST /api/tasks naming her returns 400 `fieldErrors.assignees = "NINA.NOTASKS cannot see tasks here"`, and nothing is written. PATCH on the assignees behaves the same.
  - `createForAutomation` (unprobed) drops her with `NINA.NOTASKS cannot see tasks here, so was not assigned`.
- `TaskActionTest.aPersonWhoCannotSeeTasksInTheRegionIsLeftOutWithAWarning`: a rule's CreateTaskHandler drops nina and keeps the cashier. The effect is DONE with the warning.
  - Both CF-9 tests fail with the privilege check reverted (nina stored as an assignee).

### Final counts
- `cd backend && mvn -q -o test`: 798 tests, 0 failures, 0 errors, 0 skipped (792 before plus 6 new).
- `cd mail-service && mvn -q -o test`: 24 tests, 0 failures, 0 errors.
- No flaky failures seen.

## B3 — History: owner lock in writeLinks, baseline started_at recovery, moved_at precision, stricter clock guard

### Fixed
- **CR-1 / CF-1** (verified: with the lock removed, the repeated relink test failed 1 of its 10 repetitions on the first try with `[11L, 12L]` instead of `[11L, 12L, 13L]`): `HistoryWriter.writeLinks` now calls a new `lockOwners(s, link, owners)` before `link.currentTargets`.
  - Per `VersionStore.CHUNK`, in ascending id, it runs `select o.id from <owner entity> o where o.id in :ids order by o.id` with `PESSIMISTIC_WRITE`. The owner entity comes from `s.getFactory().getJpaMetamodel().entity(link.owner()).getName()`.
  - An owner deleted in the transaction returns no row. Callers already hold customer → invoices, so the promise lock comes last and the order is unchanged.
  - The `LinkHistory` Javadoc ("relinking one owner never needs the owner's own row") and the `writeLinks` Javadoc were corrected.
  - New test seam: `HistoryFaults.betweenLinkReads(code, ownerId)`, a default no-op. The writer calls it for each owner after reading the targets and before reading the open rows. `HistoryTestHooks.onNextLinkReadsOf(code, owner, action)` implements it for tests.
- **CF-13** (verified: `started_at` stayed null): `HistoryBaseline.run`. On a first run where `startedAt[0] == null` and `meta.getStartedAt() == null`, the new `earliestVersion()` sets `started_at` to the minimum `validFrom` across the version tables of `HistoryModel.types()`, one `select min(v.validFrom) from <Version> v` per type, when any versions exist.
  - The query runs in the outer `held` transaction, which holds the meta lock. There is no REQUIRES_NEW, so no deadlock.
- **CF-14** (verified): `CustomerRegionMoveService.move` now stamps `movedAt(clock.instant().truncatedTo(ChronoUnit.MICROS))`.
  - The history instant is not used on purpose. `HistoryWork.instant(clock)` is package-private and is bound only in the writer at commit, once every row lock is held. Binding it early from the move would break that rule for the whole transaction.
  - With a fixed clock, the truncated value equals the versions' `valid_from`.
- **CF-15** (verified: `LocalDate.now(UTC)`, `ZonedDateTime.now(zone)`, `LocalDate.now(ZONE)`, `Calendar.getInstance()` and `new GregorianCalendar()` were not flagged): `ClockReadGuardTest`.
  - The zone pattern is replaced by `\b<TIME_TYPES>\s*\.\s*now\s*\(\s*+(?!\)|(this\s*\.\s*)?clock|AppClock\s*\.\s*get\s*\(\s*\)|WallClock\s*\.\s*UTC\b)`. The type list is shared with pattern 1 through `TIME_TYPES`. The `\s*+` is possessive, so `now( clock )` cannot backtrack into a false match.
  - Added `\bCalendar\s*\.\s*getInstance\s*\(` and `\bnew\s+(java\.util\.)?GregorianCalendar\s*\(`.
  - src/main stays green. Its only `now(arg)` calls are `Instant.now(WallClock.UTC)` in ApiError.

### Rejected
- None. The only partial item: CF-14's "prefer the transaction's history instant" was not done, because that instant is not cheaply or safely available before commit (see above).

### Tests (each fails with its fix temporarily reverted)
- `HistoryConcurrencyTest.twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink` is now `@RepeatedTest(10)`, with its Javadoc corrected. Without the lock, 1 of the 10 failed on the first try.
- `HistoryConcurrencyTest.aRelinkCommittingWhileAnotherIsVersioningThePromiseWaitsForIt` (new, deterministic):
  - Setup: the first writer is held in `betweenLinkReads` for up to 500 ms, waiting for the second relink to commit. That is well inside H2's measured 2 s lock timeout.
  - Asserts three open link rows, the reconciler reports no problems, and the second did not commit meanwhile.
  - Without the lock: `[1L, 2L]` instead of `[1L, 2L, 3L]` (the second's open row is closed).
- Private copy (`harden/b3/ws`), `HistoryConcurrencyTest` run 20 times under 10 busy-loop CPU burners (hw.ncpu=10): 20 of 20 passed, 13 tests each.
- `HistoryBaselineTest.aFirstRunThatFindsVersionsAlreadyThereStartsHistoryAtTheEarliest`:
  - Setup: a baseline runs at STARTED, a later customer is added an hour later, then `started_at` and `baseline_completed_at` are nulled and run() is called two hours later.
  - Asserts it copies 0, `started_at` equals the earliest version's valid_from (STARTED), `baseline_completed_at` is the new clock, and `historyStartsOn` is 2026-05-04.
  - Without the fix: `expected 2026-05-04T08:15:30.123456Z but was null`.
- `CustomerRegionMoveTest.theMoveIsLoggedAtTheInstantItsVersionsStart`:
  - Setup: the clock is set to a micro-aligned instant plus 999 ns, then the customer is moved.
  - Asserts the customer's open version starts at the truncated instant, and `moved_at` has no sub-microsecond nanos and equals that version start.
  - Without the fix, H2 rounds up: `expected …761231Z but was …761232Z`.
- `ClockReadGuardTest.theGuardRecognisesEveryWayOfReadingTheSystemClock`:
  - Now also flags `LocalDate.now(UTC)`, `ZonedDateTime.now(zone)`, `LocalDate.now(ZONE)`, `Instant.now(Clock.systemUTC())`, `Calendar.getInstance()`, `java.util.Calendar.getInstance(TimeZone…)`, `new GregorianCalendar()` and `new java.util.GregorianCalendar(zone)`.
  - Still allows `LocalDate.now(clock)`, `LocalDate.now(this.clock)`, `LocalDate.now( clock )`, `ZonedDateTime.now(clock.withZone(zone))` and `LocalDate.now(AppClock.get())`.
  - Fails with the old zone pattern.

### Final counts
- `cd backend && mvn -q -o test`: 810 tests, 0 failures, 0 errors, 0 skipped (798 before, plus 9 extra repetitions and 3 new tests).
- `cd mail-service && mvn -q -o test`: 24 tests, 0 failures, 0 errors.
- No flaky failures seen.

## B4 — Inbox region narrowing (mark-all-read, emails about deleted records) and dashboard top-paying order

### Fixed
- **PC-4 / CF-2 (inbox mark-all-read ignored regionIds)**: confirmed. `InboxService.markAllRead(Long userId, Collection<Long> regionsOrNull)`: with null it runs the old single update; with a set it selects the ids of the user's unread TO rows through `EmailRegionPredicate.onRecipient(regions, defaultRegionId)` and marks them read at `clock.instant()` in chunks of 1000 (new `EmailRecipientRepository.markReadAmong(ids, userId, now)`, still guarded on user/TO/unread). `InboxController.markAll` passes `askedRegions()`, as NotificationController:81 does.
- **PC-5 / CF-3 (emails about a deleted record vanished once regions were chosen)**: confirmed. `EmailRegionPredicate.of(email, q, cb, regions, defaultRegionId)` adds, for each REGIONAL kind, a disjunct when the record no longer exists (`not exists (select id from <Entity> where id = email.entityId)`): (a) the record's newest region-naming audit row (`max(id)` for that entityType/entityId with `regionId is not null`) has `regionId in :regions`; (b) when the default region is among those asked, no audit row of the record names a region. The subqueries are correlated criteria subqueries (they use `idx_audit_entity` and the PK), with no per-row Java. The entityType strings are the services' audit `ENTITY` constants (verified: CustomerService/InvoiceService/PaymentService/PaymentPromiseService/DisputeService.ENTITY = CUSTOMER/INVOICE/PAYMENT/PROMISE/DISPUTE), held in a new `AUDIT_ENTITY` map. The default region id is resolved once per call (`InboxService.defaultRegionId()`) and passed to the list scope, unread count and mark-all-read. CUSTOMER_DELETED rows carry the customer's region (CustomerService:394), so a deleted customer's emails stay in its region. Records whose audit rows carry no region (for example invoices, whose audit rows are unstamped) fall to the default region, as the triage specifies and as `AuditController.deletedCustomerReachable` does.
- **CF-4 (topPaying re-sorted ties in Java String order)**: confirmed. `DashboardService.topPaying` keeps the SQL order when `split.parts()` has one part. With several parts it sorts by `collected` desc only (List.sort is stable, so each part's SQL tie order is kept), then applies the limit.

### Rejected
- None.

### Tests (all in `backend/src/test/java/com/geneinvoice/region/RegionParamCoverageTest.java`; each fails with its fix reverted, then passes)
- `markingAllTheInboxReadTakesInOnlyTheRegionsAskedAbout`: mirrors the notifications case. With SOUTH asked, 2 are marked (SOUTH and no-region) and NORTH stays unread. With WEST asked (not held), only the no-region email is marked. Unnarrowed, the rest are marked. Without the fix: `expected: 2 but was: 3`.
- `anEmailAboutADeletedRecordStaysInTheRegionItWasLastIn`: a NORTH customer with an inbox email is deleted via DELETE /api/customers/{id}. The email is listed and counted for regionIds=NORTH, and not for SOUTH. An INVOICE email about an id with no history is listed only for the default region. Region-narrowed mark-all-read agrees (SOUTH marks 0; NORTH marks the deleted customer's email and leaves the orphan unread). Fails without the lastRegion disjunct, and separately without the default-region disjunct.
- `topPayingKeepsTheDatabaseOrderAmongEqualAmounts`: two SOUTH customers, "alpha Co" and "Bravo Co", each pay 50. H2 compares binary, the same as Java, so the test switches `customers.name` to `VARCHAR_IGNORECASE(150)` for its duration (restored in `finally`) to stand in for a linguistic collation. The single-region answer must be the SQL ORDER BY order: South Co, alpha Co, Bravo Co. Without the fix, Java reorders the tie to Bravo, alpha. Deviation from the triage wording: the names differ by more than case, because under a case-insensitive collation names that differ only by case compare equal, which would leave the SQL order undefined and untestable.

### Final counts
- `cd backend && mvn -q -o test`: 813 tests, 0 failures, 0 errors, 0 skipped (810 before, plus 3 new).
- `cd mail-service && mvn -q -o test`: 24 tests, 0 failures, 0 errors.
- No flaky failures seen.

## B5 — Approvals: threshold-change result link, drift in unchanged fields, and missing tests (move rollback, customer approve, NONE tier)

### Fixed
- **CT-5 (an approved THRESHOLD_CHANGE linked back to itself)**: confirmed. `ApprovalNotifier.recordLink` had no REGION case, so it fell back to `/approvals/{id}`. It now has `case "REGION" -> "/approval-thresholds?regionId=" + id` (THRESHOLD_CHANGE's entityId is the region id) and `case "CUSTOMER" -> "/customers/" + id`. The CUSTOMER case is defensive: no ApprovalOperation has entityType CUSTOMER today (creates audit on the customer, but `pc.entityType` is the op's own), so nothing can test it yet. The link is shared by `PendingChangeViews.result` and the APPROVED notification.
- **CF-8 (drift in a strict field hidden from the Detail)**: confirmed. `PendingChangeViews.changes` skipped every field whose proposal equals its base. Now a field is skipped only when proposed equals base AND current has not moved from base. A moved field is emitted with `changedSinceRequest=true`. Values are compared with a comparator (`JsonNode.equals(Comparator, …)`, recursive, so nested threshold limits are covered too) that compares two numbers with `decimalValue().compareTo(...) == 0`, so 500 vs 500.00 is neither a change nor drift.

### Tests added (each fails with its fix or check reverted, then passes)
- CT-5, `ApprovalThresholdApiTest.looseningIsHeldForAnotherApproverAndTheOldLimitHoldsMeanwhile` (assertions only): after the approval, GET /api/approvals/{id} has result entityType REGION, entityId = north, `result.link == /approval-thresholds?regionId=<north>`, and the maker's APPROVED notification has the same link. Without the fix it fails at `result.link`.
- CF-8, `ApproveFlowTest.aStrictFieldThatMovedSinceTheRequestIsShownAsChanged`: the setUp holds INVOICE_CANCEL on a 500.00 invoice. A 100.00 payment is recorded (admin, not gated), then GET detail as the approver.
  - `changes` has exactly `status` and `paidAmount`: total is unchanged and stays out.
  - The paidAmount row has changedSinceRequest=true, current 100, proposed 0.
  - The status row is also drifted (part paid): changedSinceRequest=true, current is not UNPAID, proposed CANCELLED.
  - Without the fix, paidAmount is missing.
- CF-17, `ApproveFlowTest.aCustomerLoginHoldingTheApprovalRightStillCannotDecide`:
  - The acme login gets a role `CUSTOMER_APPROVER` (CUSTOMER's privileges plus CHANGE_APPROVE). It is a separate role because roles outlive a test; this is the same pattern as `aCustomerLoginCannotMoveItsCustomer`.
  - With that role, the login holds CHANGE_APPROVE in its own customer's region (`RegionRights.forCustomer`), so it passes @PreAuthorize and check 6.
  - Approve and reject (with a note) each return 403. The change stays PENDING with no decider, the invoice stays UNPAID, and there is no CHANGE_APPROVED or CHANGE_REJECTED audit row.
  - With check 4 deleted, approve returns 200.
- CF-16:
  - `TestMoveParticipant.failAfter(message, whileMoving)` makes afterCustomerMoved record the call, run the optional probe, then throw BadRequestException. `reset()` clears it.
  - New test `CustomerRegionMoveTest.aParticipantFailingAfterEverythingIsWrittenRollsTheWholeMoveBack`. Setup: acme gets a task and a held INVOICE_CANCEL on a fresh unpaid invoice (the NORTH threshold is 1), which admin's move would close.
  - The probe runs inside the move's transaction. It records: the customer is already in SOUTH, the task is in SOUTH, the change is CLOSED, and moves = 1. So the failure really comes after the native updates, the touches, the move row and the other participants' work.
  - The move returns 400 with the message.
  - Everything matches the before snapshot: `state()` (every row's region, the customer and invoice versions, the version rows of customer, invoice, payment, promise and dispute, the move count, the audit count, the seats), plus the task's region and TaskVersion rows, the pending change's status/outstandingKey/decidedAt, the notification count and the automation event count.
  - All rows are still NORTH, customer_region_moves is 0, and there is no CUSTOMER_REGION_CHANGED row. After reset, the move succeeds.
  - Proven by swallowing afterCustomerMoved's exceptions in the service: the move then returns 200.
- CF-18, `RegionBookTest.narrowingToAGrantedRegionWhoseTierIsNoneShowsNoneAndNoChip`: the triage's "sam" is a new user, `sam.watcher`.
  - The fixture's sam cannot be used: SALES_POC holds POC_ASSIGNABLE_SALES, so any grant of it is at least BOOK.
  - sam.watcher has role INVOICE_WATCHER (CUSTOMER_VIEW, INVOICE_VIEW, SCOPE_OVERRIDE, no POC assignability), NORTH READ/BOOK (tier NONE: no override at BOOK, not a sales POC) and SOUTH READ/ALL (FULL).
  - Unnarrowed: only the SOUTH invoice, lockedFilters [], regionIds [SOUTH].
  - `regionIds=NORTH`: totalElements 0, lockedFilters [], regionIds [], and the NORTH invoice by id is 404.
  - Always emitting the chip (dropping `!anyVisible`) fails it: "another granted region is visible: no chip".

### Rejected
- None.

### Final counts
- `cd backend && mvn -o test`: 817 tests, 0 failures, 0 errors, 0 skipped (813 before, plus 4 new; CT-5 added assertions to an existing test).
- `cd mail-service && mvn -o test`: 24 tests, 0 failures, 0 errors.
- No flaky failures seen.
