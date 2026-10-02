
## E2E r1 B1 — Automation reads large objects inside a transaction on PostgreSQL (entity triggers and schedules)

Root cause (confirmed in code): Hibernate 6.5 on PostgreSQL stores each `@Lob String` as a large object (the TEXT column holds an OID), and reading it needs a transaction. Inherited CRUD methods run in SimpleJpaRepository's read-only transaction, but query methods declared on the interface (custom `@Query` and derived) do not. `EventProcessor.candidates` calls `AutomationRuleRepository.findEntityCandidates`, and `ScheduleTicker.tick` calls `findDueSchedules`. Both run with no transaction on the worker and scheduler threads, so they failed with "Unable to access lob stream". H2 hides this.

- AUT-R1-1 / AUT-R1-2 fixed: added `@Transactional(readOnly = true)` to every non-`@Modifying` entity-returning query method of each repository whose entity has a `@Lob`. The `@Lock` methods get plain `@Transactional`. Nothing is annotated at the interface level. `@Lob` and the column types are unchanged.
  - AutomationRuleRepository: findEntityCandidates, findDueSchedules (readOnly); findByIdForUpdate (plain).
  - AutomationEventRepository: findByHistoryTxIdOrderByIdAsc, findQueuedManual, findReplayable, findByStatusOrderByUpdatedAtDescIdDesc.
  - AutomationRuleRevisionRepository: latestAt, findFirstByRuleIdAndSavedAtLessThanEqualOrderByIdDesc, findByRuleIdOrderByIdAsc.
  - PendingChangeRepository: findByOutstandingKey, findFirstBySupersededById (readOnly); findByIdForUpdate, findOnRecordForUpdate, findForCustomerForUpdate (plain).
  - AuditLogRepository: the three derived finders. DisputeRepository: the three derived finders (readOnly); findByIdForUpdate (plain).
- Tests:
  - New `common/LobReadsAreTransactionalTest`, a static guard. It scans src/main/java for `interface X extends JpaRepository<E, ?>`, then Class.forName. If E or a superclass has a `@Lob`, every abstract non-`@Modifying` method whose generic return type mentions E must be `@Transactional`. A self-test of the guard runs on a sample interface. Before the fix it failed on 22 methods, including findEntityCandidates and findDueSchedules.
  - New `common/LobLoadProbe` (test helper, no bean, so the context stays shared). It appends a Hibernate POST_LOAD listener that records every entity with a `@Lob` loaded while no Spring transaction is active. This gives an H2 regression check at runtime.
  - `ScheduleTickerTest.aTickWithNoTransactionOpenReadsTheDueRulesInOneAndQueuesTheSlot`: a non-transactional tick of a due SCHEDULE_DAILY rule. It checks for one SCHEDULED event and run, that nextRunAt moves to the next day, and that the drain gives one SUCCEEDED run and one task. The probe finds nothing loaded outside a transaction.
  - `TriggerMatrixTest.aCustomerPutIsActedOnByTheWorkerWhichReadsTheRulesInATransaction`: an ENTITY_UPDATED CUSTOMER rule, then PUT /api/customers/{id}, then a worker drain. It checks that the event is DONE and that there is one task "Check Acme Holdings" and one UPDATED run SUCCEEDED, with an empty probe.
  - Verified: with the annotations stripped from AutomationRule/AutomationEventRepository, all three new tests fail (probe: `AutomationRule#4`, `AutomationRule#11`). They pass once the annotations are back.
- The optional Postgres check (c) was not run: e2e/build-dist.sh and e2e/env are not in the repo, and they build under scratchpad/e2e, which this package may not touch.
- `cd backend && mvn -q -o test`: 821 tests, 0 failures, 0 errors. `cd mail-service && mvn -q -o test`: green.

## E2E r1 B2 — Upgrade a DB that already has legacy automation_rules / automation_events tables

Root cause (confirmed in code): `AutomationSchemaUpgrade` only called `SchemaSupport.widen()`. `ddl-auto: update` only adds columns, so the dev DB's earlier-build tables kept their constraints. automation_events kept idempotency_key, entity_id and trigger_kind NOT NULL, plus CHECKs on status, trigger_kind and entity_type. automation_rules kept action and run_count NOT NULL, plus CHECKs on action, entity_type and trigger_kind. As a result `AutomationEvents.write` (idempotency_key null, status PENDING) was refused, which rolled back every customer, invoice and payment save with a 409 (MIG-R1-1), and rule creation was refused with a 409 (MIG-R1-2). `widen()` only treated a CHECK as stale when it contained the enum's first constant (`ENTITY_CREATED`), so it never recognised the legacy trigger_kind CHECK (CREATED/UPDATED/DAILY/WEEKLY). A further latent problem on the same path: the legacy trigger_kind is varchar(20), which is too short for `ENTITY_CREATED_OR_UPDATED` (25 characters).

- common/schema/SchemaSupport:
  - `relaxColumns(conn, table, mapped, keepNotNull, dropAllChecks)`, with a 4-argument overload `(conn, table, keepNotNull, dropAllChecks)`. It drops NOT NULL on every column outside keepNotNull. It drops each CHECK that names a column outside `mapped`; the identifiers come from pg_get_constraintdef on Postgres and from information_schema.check_constraints on H2, with literals stripped first. With dropAllChecks it drops every CHECK. The work runs in one transaction, each change is logged at INFO, and it returns the number of changes. A missing table is a no-op.
  - `lengthen(conn, table, lengths)`: widens only varchar columns that are shorter than the mapping (`set data type varchar(n)`, valid on both pg and H2), and never shortens one.
  - `columns(conn, table)`: returns name to nullable.
  - `widen()`: stale detection no longer depends on the first constant. A CHECK is the column's enum check when its only identifiers are the column plus IN, or ANY/ARRAY plus the pg cast words, over string literals. It is stale when any current constant is missing. The native H2 ENUM path is kept and the Javadoc is updated.
- automation/AutomationSchemaUpgrade keeps the EntityManagerFactory. `mappedColumns()` reads the columns and their nullability via `SessionFactoryImplementor.getMappingMetamodel().getEntityDescriptor(X).forEachSelectable`. The identifier is forced to non-null, because the metamodel reports a generated id as nullable; without that the PK would have been relaxed and the transaction would have failed. `mappedLengths()` reads the declared lengths the same way. Steps, in order:
  1. automation_events: keepNotNull is the mapped non-null columns, which are exactly attempts, claims, depth, id, kind, occurred_at and status, the same list as OutboxSchemaTest. dropAllChecks is true.
  2. automation_rules, automation_runs and automation_effects: keepNotNull is the mapped non-null columns, and CHECKs on unmapped columns are dropped. Short varchar columns are lengthened.
  3. Legacy rows, only when a legacy column is present (automation_rules.action or filters_json; automation_events.idempotency_key or trigger_kind). Unreadable rows are moved into `<t>_legacy` (`create table if not exists ... as select * ... where 1 = 0`, then an insert-select by column name, then a delete, in one transaction) with a WARN giving the count. Unreadable means, for rules: actions_json null, or trigger_kind or entity_type not in the enum. For events: status not in PENDING/PROCESSING/DONE/DEAD, or kind null. No table is ever dropped.
  4. The existing widen() calls.
- Tests:
  - SchemaSupportTest, new cases:
    - The legacy outbox DDL plus relaxColumns(keep 7, true): the PENDING / null key / null entity_id insert succeeds, no CHECK remains, only the 7 columns stay NOT NULL, and a second run or a missing table gives 0.
    - A legacy rules table: only the CHECK on the unmapped `action` column is dropped.
    - widen(TriggerKind) on CHECK (CREATED, UPDATED, DAILY, WEEKLY): ENTITY_CREATED and MANUAL are accepted, BOGUS is refused. This fails on the old first-constant logic.
    - widen leaves OR checks and checks on other columns alone.
    - pg-printed definitions are recognised.
    - lengthen widens only the short varchar column and keeps NOT NULL.
    - widenReplacesAStaleCheckConstraint is still green.
  - New automation/AutomationLegacySchemaTest (AutomationTestBase, shared context). It puts the legacy shape on the live H2 schema: automation_events.idempotency_key NOT NULL (default dropped), `automation_events_status_check (QUEUED, DONE) NOCHECK`, automation_rules.action NOT NULL (default dropped), trigger_kind as varchar(20) (H2's native ENUM would refuse the old names), and the legacy trigger_kind CHECK NOCHECK. It then calls `afterPropertiesSet()` and checks:
    - PUT /api/customers/{id} returns 200 and writes a PENDING UPDATED event.
    - POST /api/automation/rules returns 200 for MANUAL CUSTOMER CREATE_TASK and for ENTITY_CREATED_OR_UPDATED.
    - The legacy QUEUED event goes to automation_events_legacy while the DONE row stays, and a second run moves nothing.
    - On a fresh schema, two runs leave information_schema unchanged and create no _legacy table.
    - The metamodel's required outbox columns equal the OutboxSchemaTest list.
    - `@AfterEach` restores everything (enum type included) and asserts the automation schema's shape equals what it was before the test.
  - Verified: with the relax and archive calls removed from afterPropertiesSet, the save/rule and legacy-row ITs fail. OutboxSchemaTest is green.
- `cd backend && mvn -q -o test`: 831 tests, 0 failures, 0 errors. `cd mail-service && mvn -q -o test`: green.
- To re-run: MIG-A-40/41 on the dev dump (tester, next round).

## E2E r1 B3 — Approvals: terms approval applies only its own fields; blank note = no note; as-of detail hides later decisions; readable change rows

- **APP-R1-1** (lost update on terms approval). Root cause: `InvoiceChangeApplier.applyTerms` ran the three-way checks only for fields the change actually moved, but then re-ran `invoiceService.updateOrHold(id, req)` with the whole stored request, so notes/Sales POC carried unchanged overwrote later edits. Fix: after the checks, the request is rebased. `notes` is sent only when `norm(base.notes) != norm(proposed.notes)`, and `salesPocUserId` only when base != proposed. dueDate/paymentTerm are kept, and version is null. The Javadoc is updated.
- **APP-R1-2** (blank notes). Added `InvoiceChangeApplier.norm(String)`: null or blank gives null. It is used in the notes three-way check (base/proposed/now) and in the rebase. `InvoiceService.termsGate` keeps `proposed.notes = base.notes()` when `norm(req.notes) == norm(base.notes)`, so there is no spurious Notes row and no conflict.
- **ASO-R1-2** (as-of detail showed the later decision). `PendingChangeViews.hiddenDecision(pc)` = `AsOf.active() && statusAt == PENDING && status != PENDING`. When it holds, Detail nulls decidedBy, decidedAt, decisionNote, failureReason, supersededById and result, and Brief nulls decidedAt. Live status and statusAt are unchanged.
- **APP-R1-4** (readable rows). `label()` now uses an explicit map (Sales POC, Collection POC, Customer Success POC, Customer, Payment term, Invoice date, Due date, Items, Paid on, Method). Otherwise it strips a trailing UserId/Id, splits camelCase and writes poc as POC. `Change` gains `currentText` and `proposedText`, filled as follows. `*UserId` gives the display name, from one batched `userRepository.findAllById`; an unknown id gives `user #<id>`. `paymentTerm` gives `PaymentTerm.label()`, or null when the value is not a constant. `customerId` gives the customer name, from one batched `customerRepository.findAllById`. A non-free-text UPPER_SNAKE value is humanised (BANK_TRANSFER gives "Bank transfer"); notes, overrideReason, reason, description and name are never humanised. Everything else is null. POC fields are still stripped without POC_VIEW before rows are built.
- Tests: `ApproveFlowTest` gains anApprovedChangeOfTermsKeepsNotesSomeoneElseWroteMeanwhile, anApprovedChangeOfTermsKeepsASalesPocReassignedMeanwhile, blankNotesOnAHeldChangeOfTermsAreNoNotes (the detail right after the hold has no notes row) and heldChangesReadWithNamesAndPlainLabels. `ApprovalAsOfTest` gains aChangeReadAsOfADayBeforeItsDecisionShowsNoDecision. I checked that each fails with its fix reverted: 3 fail on the invoice revert, and 2 fail when hiddenDecision/LABELS are disabled.
- Note (not changed): once a terms field the change carries unchanged is edited by someone else, the detail still shows it as a drifted row (CF-8 generic display). After the APP-R1-1 fix, approval keeps the other user's value.
- Result: backend `mvn -q -o test` gives 836 tests, 0 failures and 0 errors. mail-service passes.

## E2E r1 B4 — Automation Customer.* placeholders respect customer visibility; requeue message; dashboard cross-part ties

- **RR-1** (rule texts filled in Customer.* of a customer that cannot be opened). Root cause: `RecordContexts.load` called `customerValues` for every record. Only `renderForEmail` checked `canSeeCustomer`.
  - Fix: `PlaceholderValues` gains `customerHidden`, as a new 3-arg constructor. The 2-arg constructor keeps false.
  - `PlaceholderRenderer.CUSTOMER_HIDDEN` is now the single source of the text "Customer details are left out: you cannot see this record's customer". `render` adds it once per text when a Customer.* key other than Name is referenced and `customerHidden()` holds. The "X is empty" warnings are unchanged.
  - `ActionSupport.collect` keeps that warning once across an action's texts, for example subject and body.
  - `renderForEmail` now uses the same mechanism through `collect`, so the old `CUSTOMER_TOKEN` regex and constant are gone.
  - New overload `load(..., pocVisible, customerVisible)`. For INVOICE and PAYMENT, it puts only Customer.Name when `!(customerVisible && canSeeCustomer(customer))`, asked as whoever is signed in. The 5- and 6-arg overloads pass true, so the run path (ActionExecutor) keeps the owner's own visibility.
  - New `RecordContexts.callerSeesCustomerOf(type, id)`: a repository lookup (customer, invoice or payment) and then `canSeeCustomer`. It gives false when the record is not found.
  - `RulePreviewService.evaluate` computes it for `chosen` as the caller, outside `as(asOwner, …)` and only when actions are shown (no as-of point). It passes it through `actions()`, so the owner's view needs both owner and caller to be able to open the customer, mirroring PC-7.
- **RR-2** (requeue message). `RuleService.requeue` for a MANUAL event of someone else's rule now says "A run by hand is tried again from its run: open the rule's Runs tab and use Retry, which takes the rule over", plus " (run #<id>)" only when a run exists. It names no API path and never says "null".
- **RR-3** (topPaying ties across parts). With several parts, rows are sorted by collected descending, then customerName (CASE_INSENSITIVE), then customerName, then customerId. A single part keeps the SQL order. The comment is updated.
- Tests:
  - `PreviewTest.aCustomerThatCannotBeOpenedLendsTheActionsOnlyItsName` uses the EmailPlaceholderTest setup: Globex with a phone and address, and cody (COLLECTION_POC, default region MANAGE/BOOK). Cody sees his Globex payment and gets 404 on the customer.
    - The PAYMENT MANUAL rule has CREATE_TASK '{{Customer.Name}} {{Customer.Phone}}' and SEND_EMAIL 'addr={{Customer.Address}}'. In cody's POST preview the title is 'Globex', the body is 'addr=' and the warning appears once per action. Nothing is written.
    - Admin's evaluateAs=OWNER view of cody's saved rule hides the same fields.
    - An admin-owned copy renders '+1-555-0100' and '1 Secret Street' with no warning.
    - Ada is an AUTOMATION_ADMIN who works her own Globex payment but cannot open Globex. The OWNER view of the admin's rule hides the fields for her (the caller-side path).
  - `EmailActionTest.aCustomerTheOwnerCannotOpenLendsTheRulesEmailOnlyItsName`: run now as cody, then drain. The stored email subject is 'Globex' and the body is 'addr='. The DONE effect's warnings contain the customer-hidden text exactly once. The admin copy's email has subject 'Globex +1-555-0100' and body 'addr=1 Secret Street'.
  - Deviation: the run path asserts the one-line subject rather than a task title. `TaskService.insert` (like promise and dispute) requires the owner to see the customer, so cody's CREATE_TASK run is SKIPPED "Customer not found", before and after this fix. That is existing product behaviour and was not changed. The task title is asserted in the preview.
  - `WorkerDurabilityTest.aRunByHandGivenUpOnIsNotRequeuedByAnAdministratorWhoDoesNotOwnTheRule`: the exact-message assertion is updated to the new text with "(run #id)". It also checks that the message contains 'Runs tab' and contains neither '/api/' nor 'null'.
  - `RegionBookTest.aTieInTopPayingAcrossAFullAndABookRegionIsOrderedByName`: lena has NORTH at ALL and SOUTH at BOOK. Zeta (NORTH) and alpha (SOUTH, on lena's own invoice) collect 100 each. Coverage is MIXED and the order is alpha, then Zeta. The CF-4 single-part test (withOneRegion…) stays green.
  - Checked that the tests fail with the fixes disabled: without load's hiding, PreviewTest and EmailActionTest fail. Passing `true` for callerSeesCustomer fails PreviewTest at the ada step. Without the tie-break comparators, the RegionBookTest case fails. EmailPlaceholderTest stays green.
- Result: backend `mvn -q -o test` ran 839 tests with 0 failures and 0 errors. mail-service `mvn -q -o test` ran 24 tests with 0 failures.

## E2E r2 B1 — Upgrade a database whose legacy automation tables have rows (archive before Hibernate DDL)

- **MIG-R2-1** (legacy automation rows make `ddl-auto` fail or leave the tables without columns). Root cause confirmed: `AutomationSchemaUpgrade` takes the `EntityManagerFactory`, so its archive ran only after Hibernate's `update`. On Postgres, the NOT NULL columns with no default (events: kind, depth, occurred_at, claims; rules: owner_user_id, schedule_zone, catch_up, actions_json, max_records_per_run) could not be added to a table with rows. The archive also skipped the `kind is null` predicate when `kind` was missing, so the legacy DONE row stayed in the live table.
  - New `automation/LegacyAutomationRows` (DataSource only). It looks only at a table that has a LEGACY_COLUMNS column (events: idempotency_key or trigger_kind; rules: action or filters_json). If the table lacks any column in `REQUIRED`, every row is unreadable (`1 = 1`). `REQUIRED` is events {kind, occurred_at, depth, claims, status, attempts} and rules {actions_json, owner_user_id, schedule_zone, catch_up, max_records_per_run, trigger_kind, entity_type}. Otherwise the earlier predicates apply. The class does nothing when the table is missing, has no legacy column, or no rows match.
  - The archive copies rows by shared column names into `<table>_legacy`, which is created with CTAS if missing and reused if it exists. It first drops every NOT NULL in the archive table, so an archive made when the live table had more columns still accepts rows. Then insert and delete run in one transaction and log a WARN. On SQLException it rolls back and logs a WARN. It never throws.
  - New `automation/AutomationPreSchemaConfig` has `@Bean(name="automationLegacyRows", initMethod="archiveAll")` and a `static EntityManagerFactoryDependsOnPostProcessor("automationLegacyRows")`. The rows move before Hibernate's DDL, and the bean depends on nothing but the DataSource.
  - `AutomationSchemaUpgrade.afterPropertiesSet` keeps relax and widen. Its archive step now delegates to `LegacyAutomationRows.archive(EVENTS/RULES)`, which applies the same rule. The private archive, eventsUnreadable, rulesUnreadable, LEGACY_COLUMNS, names and listed were removed.
  - `AutomationEvent.depth` and `claims` gain `@ColumnDefault("0")` (DESIGN §3.1, defence in depth). `OutboxSchemaTest` and `AutomationLegacySchemaTest.shape()` do not compare defaults, so no assertion changed.
- Tests:
  - New `LegacyAutomationRowsTest` (plain JUnit, H2 `jdbc:h2:mem:legacyrows;MODE=PostgreSQL`) uses the tables from legacy-automation.sql with its CHECKs, 2 rules and 3 events.
    - After `archiveAll()`: 0 live events and 3 legacy events (legacy-k1/k2/k3, k2 DONE); 0 live rules and 2 legacy rules. `alter table automation_events add column claims integer not null` and `alter table automation_rules add column actions_json text not null` both succeed. A second run changes nothing.
    - Half-migrated case: `_legacy` already holds 2 rows and the live table holds the DONE row without kind. After the run, the live table has 0 rows and `_legacy` has 3.
    - An archive table whose NOT NULL column is no longer in the live table still takes the rows.
    - With every required column present, only QUEUED, FAILED and null-kind rows move.
    - A table with no legacy column, or a missing table, is a no-op with no exception.
  - `AutomationLegacySchemaTest`:
    - theLegacyRowsAreMovedAsideBeforeHibernatesSchemaUpdate: `entityManagerFactory` dependsOn contains `automationLegacyRows`.
    - everyRequiredColumnTheEarlierTablesLackedIsOneTheArchiveChecksFor: every NOT NULL mapped column is in REQUIRED or in {id, enabled, name, created_at, updated_at}, and every REQUIRED column is mapped.
    - rowsTheMappingCannotReadAreMovedAsideNotLost is extended: after its existing assertions, drop `kind` and insert a DONE row (legacy-k2). After the upgrade the live table is empty and `_legacy` holds legacy-1:QUEUED, current-1:DONE and legacy-k2:DONE. `@AfterEach` re-adds `kind varchar(20) not null`, and the shape check still passes.
  - `ModifyingQueryGuardTest.ALLOWED` gains `automation/LegacyAutomationRows.java`, a startup upgrader like the `*Upgrade.java` files. It is not named *Upgrade.
  - Mutation check: with the `1 = 1` rule removed and the depends-on pointed elsewhere, 3 LegacyAutomationRowsTest tests and 2 AutomationLegacySchemaTest tests fail.
- Not run here: E2E `suites/migration/run.sh 2` (MIG-C-00..05), per the package rules. Expected: default boot on the legacy fixture starts, PUT customer/POST invoice give 200/201, the 4 rule creates give 201, the restart boots, and MIG-A-40/41 still pass. There, the tables have both column sets and are empty, so the archive has nothing to move.
- Result: backend `mvn -q -o test` ran 847 tests with 0 failures and 0 errors. mail-service `mvn -q -o test` passes.

## E2E r2 B2 — Approval changes table: readable invoices, promises and invoice line products

**APP-R2-1 (fixed).** Root cause: `PendingChangeViews.changes()` resolved names only for `*UserId` and `customerId`, `text()` returned null for any non-textual value (so arrays stayed raw), and `LABELS` had no entry for `invoiceIds`/`promiseIds`.

Fix (`backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java`):
- LABELS: `invoiceIds` → "Invoices", `promiseIds` → "Promises" ("Items" unchanged).
- `changes()` collects invoice ids (`invoiceIds`), promise ids (`promiseIds`) and product ids (`items[*].productId`) from both now and becomes, and loads each kind once with `findAllById` (InvoiceRepository, PaymentPromiseRepository, ProductRepository injected). The maps travel in a new `record Names(users, customers, invoices, promises, products)` (with `Names.NONE`).
- `text(field, v, Names)` handles arrays before the textual check: `invoiceIds` → invoice numbers in list order, joined by ", ", fallback "invoice #<id>"; `promiseIds` → "Promise of <Money.format(amount)> by <yyyy-MM-dd>", fallback "promise #<id>"; empty array → "None"; null → null. `items` → one line per item joined by "\n": "<qty> × <product> at <Money.format(unitPrice)>" or "at list price" when unitPrice is absent/null, fallback "product #<id>". The 4-arg `text(field, v, users, customers)` overload is kept and delegates. Raw `current`/`proposed` are unchanged.
- On a create (base == null), rows whose field ends with "Ids" and whose proposed value is null or an empty array are dropped (the payment's "Promise ids — —" row is gone).
- Masking (`shown()`) and as-of (current is null) are untouched.

Note: a held invoice create resolves the catalogue price into the payload at hold time, so a line POSTed without unitPrice reads "at ₹250.00" (the price that will be applied), not "at list price". The API integration test asserts that; "at list price" is covered by unit assertions on `text()` for an absent and a null unitPrice.

Tests:
- `ApproveFlowTest.heldPaymentAndInvoiceNameTheirInvoicesAndProducts`: a held payment with invoiceIds [id] shows label "Invoices", proposedText = invoice number, no `promiseIds` row. A held invoice create with items [{gadget,150,100},{gadget,2}] reads "150 × Gadget at ₹100.00\n2 × Gadget at ₹250.00". Unit fallbacks: "invoice #987654", "promise #987654", "None", null → null, "1 × product #987654 at list price" (absent and null unitPrice). Labels asserted too.
- `GatePaymentPromiseTest.heldChangesNameInvoicesAndPromisesNotIds`: a held promise update that sets invoiceIds shows "Invoices", currentText "None", proposedText = the invoice number. A held payment with promiseIds reads "Promise of ₹1,000.00 by <date>" and has no `invoiceIds` row.

Result: `cd backend && mvn -q -o test` 849 tests, 0 failures, 0 errors. `cd mail-service && mvn -q -o test` passes.

## E2E r3 B1 — Approval detail as of a date resolves names from history (regression of r2 B2), and blockedReason for a record the caller cannot see

### ASO-R3-1 (high, regression of r2 B2 / APP-R2-1)
- Root cause confirmed: `PendingChangeViews.changes()` read the names for the ids its rows carry with `customerRepository`/`invoiceRepository`/`promiseRepository`/`productRepository.findAllById`. All four are versioned, so with an as-of point bound `LiveReadGuard.onPostLoad` threw `AS_OF_UNSUPPORTED`, and `GET /api/approvals/{id}?asOf=` returned 400 for any change naming invoices, promises or items.
- Fix (`backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java`): injected `VersionStore`. The four look-ups now go through one helper, `names(ids, VersionClass, versionName, repository, idOf, liveName)`:
  - Live: unchanged, read from the repository.
  - As of a date: `versionStore.validAt(CustomerVersion|InvoiceVersion|PromiseVersion|ProductVersion, ids, AsOf.current().instant())`. The name comes from the version row (`name`, `invoiceNumber`, "Promise of <amount> by <date>", `name`), with no rehydration.
  - Ids with no version at the point (gaps) fall back to the live name via `LiveReadGuard.allow(() -> repo.findAllById(missing))`. Ids still missing keep the "invoice #id"-style fallbacks.
  - Nothing is caught, so the read-only transaction of `ApprovalService.get` is never marked rollback-only.
  - Users are unchanged (not versioned).
- Every APP-R2-1 behaviour is kept: labels, list order, promise text, item lines and "at list price", "None", empty `*Ids` rows dropped on a create, masking, and `current == null` as of a date.
- Rest of the as-of path checked:
  - `requireVisible` and `canSeeRecord` go through the as-of-aware `ScopeResolver`, and `canSeeRecord` is not even evaluated when historical.
  - `current` is skipped when historical.
  - `result()` reads ids only.
  - `regionName`, `person` and `approverCount` read regions and users only, which are not versioned.
- Note: no current operation's proposed payload carries a `customerId` field (`CreateView`, `RecordView`, `PromiseView` and the rest do not), so the customer branch is latent. It now shares the same helper.

### RGN-R3-1 (low)
- `detail()` computes `visible = !historical && scope.canSeeRecord(pc)` once. `current` uses it, and it is passed to `blockedReason`.
- New precedence:
  1. historical -> "Read-only in the as-of view"
  2. `!visible` -> "You no longer have access to this record"
  3. the existing rules, unchanged
- `canApprove`, `canReject` and `canWithdraw` are unchanged (SC-15). A held create is judged by customer visibility through `canSeeRecord`.
- Javadoc on `blockedReason` lists the possible strings.

### Tests
- New `ApprovalAsOfTest.aHeldChangeNamingInvoicesPromisesAndProductsReadsAsOfADay`, using the AsOfScenario clock.
  - Setup on D5, by SOUTH maker Mia:
    - a held invoice create with 2 × Gadget at 300;
    - a held payment with invoiceIds [X] and promiseIds [the promise];
    - a held promise update setting invoiceIds [X, Z];
    - an invoice create that admin approves on D5.
  - D7: Gadget is renamed "Gadget Max" and Acme is renamed "Omega Corp".
  - As of D6, all four return 200, with:
    - statusAt PENDING, or APPROVED for the one decided on D5;
    - canApprove, canReject and canWithdraw false;
    - "Read-only in the as-of view";
    - current null, and customerName "Zeta Holdings";
    - Items "2 × Gadget at ₹300.00" (the OLD name) and "1 × Gadget at ₹150.00";
    - Invoices = X's number;
    - Promises = "Promise of ₹600.00 by …";
    - the promise's Invoices "X#, Z#", with currentText null.
  - Live: the same numbers, and "Gadget Max".
  - Without the fix it fails with 400. The naive `LiveReadGuard.allow` fix would fail on the old product name.
- `ApprovalVisibilityTest.anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout` extended:
  - the book-less approver gets "You no longer have access to this record", with canApprove and canReject still true;
  - the approver who sees the record gets null;
  - the maker gets "You requested this change";
  - with the clock moved +1 day, the book-less approver as of yesterday gets "Read-only in the as-of view" (canApprove false).
  - Without the fix it fails.
- `ApproveFlowTest.heldPaymentAndInvoiceNameTheirInvoicesAndProducts` and `GatePaymentPromiseTest.heldChangesNameInvoicesAndPromisesNotIds` pass, unchanged.
- `cd backend && mvn -q -o test`: 850 tests, 0 failures, 0 errors. `cd mail-service && mvn -q -o test`: green.

## E2E r4 B1 — Approval record visibility follows the record's own region, live and as of a date

**RGN-R4-1 (medium, B1)** — not a regression; RGN-R3-1 was applied incompletely.
- Root cause confirmed: `PendingChangeScope.canSeeRecord` (and the create branch at the end of `isVisible`) passed
  `pc.getRegionId()` — the region the change was asked in, which never changes — to `ScopeResolver.requireVisible`.
  For a FULL tier that runs no SQL, so a South-only approver was judged able to see an invoice that had moved to
  North, and `PendingChangeViews.detail` filled `current` / `changes[].current` and left `blockedReason` null.
- Fix (`backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java`): new private
  `recordRegion(kind, id, fallback)` — one Criteria query over `Temporal.fromValid(q, kind.type(), cb, where)`
  selecting `region.id` where `id = :id`, `setMaxResults(1).getResultList()` (never `getSingleResult`, no
  rollback-only marking); scalar select, so `LiveReadGuard` does not fire; works live and as of a date (version rows
  carry the record id and region). No row (deleted / not yet made at the point) falls back to `pc.getRegionId()`.
  `canSeeRecord` now uses the record's (or, for creates, the customer's) own region; the last line of `isVisible`
  (create judged by customer visibility) likewise. THRESHOLD_CHANGE, maker (SC-15), approver and
  `viewPrivilege(region)` gates unchanged; `PendingChangeViews.detail` / `blockedReason` precedence (historical, then
  !visible, then the rest) and canApprove/canReject/canWithdraw unchanged.
- `forList` checked: its record subqueries (`ids(type, scope)` via `ScopeResolver.scope(..., SELECTED)` and
  `forCustomers()`) already filter on each record's own `region.id`. The only `pc.region` comparisons there are the
  maker / approver / threshold / `viewPrivilege(region)` gates, which mirror the unchanged `isVisible` gates. No change.
- Test: `ApprovalVisibilityTest.aChangeLeftBehindByARegionMoveShowsNoLiveValuesToTheOldRegion` — South INVOICE_TERMS
  1000; sally APPROVE/ALL South only; vic VIEWER READ South only; South invoice 2500; South maker PATCHes dueDate+60 /
  CUSTOM (202). Before the move sally: current + every changes[].current filled, blockedReason null, canApprove; vic
  200 with current. Next day admin moves the customer to North (200), pc CLOSED. sally: invoice 404; approval 200
  CLOSED, current null, every changes[].current and currentText null, blockedReason 'You no longer have access to this
  record'; vic 404; admin 200 with current. Clock advanced; asOf the day before the move: sally 200 'Read-only in the
  as-of view', current null; vic 200 (record was in South then). Verified failing without the fix ("a North record's
  values, to a South-only approver"). RGN-R3-1 pin `anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout`
  passes unchanged.
- Result: `cd backend && mvn -q -o test` 851 run, 0 failures, 0 errors; `cd mail-service && mvn -q -o test` green.

## E2E r5 B1 — A change on a customer's record is visible to whoever can see that record now, judged in the record's own region (detail and list)

**RGN-R5-1 (low, B1)**: not a regression. This is the gap r4 B1 left open.
- Root cause confirmed. r4 moved `canSeeRecord` onto the record's own region, but `PendingChangeScope.isVisible` still
  returned false at `!rights.has(op.viewPrivilege(), pc.getRegionId())` before it reached the record check. `forList`
  also gated on the change's region in two places: the record branches (`region.in(kindRegions(i))`, and
  `region.in(createRegions(i))` for creates) and the standalone `region.in(askedIds)` predicate. So nico, who reads
  North only, could open the moved invoice but got 404 on the closed change its History links to, and the change was
  missing from her list.
- The rule now: `pc.region` (the region the change was asked in) governs only four paths: the maker (SC-15), the
  approver, THRESHOLD_CHANGE, and the POC named by a create. The "can see the record" path (for a create, the
  customer) is judged in the region the record is in now, or as of a date the region it was in then, through the
  existing `recordRegion()` (one scalar query per call).
- Fix in `backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java`.
  - `isVisible`: the as-of `requestedAt` check, the customer-login branch, the maker line and the CHANGE_APPROVE line
    are unchanged. Then:
    - THRESHOLD_CHANGE returns `has(APPROVAL_THRESHOLD_MANAGE, region)`.
    - An existing record returns `has(p, at) && visible(kind, id, at)`, with `at = recordRegion(kind, id, region)`.
      There is no gate on `has(p, region)`.
    - A create returns true on `has(p, region) && recordPocUserId == me`. Otherwise it returns
      `has(p, at) && visible(CUSTOMER, customerId, at)`.
  - `forList`: the standalone `region.in(askedIds)` is removed, and the `asked.isEmpty()` early return is kept.
    `visible` is now an OR of two groups:
    - `pc.region IN askedIds AND (maker | approver | threshold | named-POC create)`. The named-POC create branch is
      `entityId IS NULL AND recordPocUserId = me AND OR_i(operation = op_i AND pc.region IN createRegions(i))`.
    - The record branches, with no `pc.region` condition:
      - `entityType = k AND entityId NOT NULL AND entityId IN ids(k.type, kindScope_i, record.region IN kindRegions(i))`.
      - `entityId IS NULL AND operation = op_i AND customerId IN ids(Customer, customers, record.region IN createRegions(i))`.
    - The extra predicate goes through a new `ids(type, scope, extra, q, cb)`, so the scope's list is never mutated.
      The SELECTED scopes already narrow to the regions asked about, and `Temporal.subquery` makes them as of the date,
      because version rows carry `region`.
  - AWAITING_ME, MINE and the as-of `outstanding` predicates are unchanged. The `regionId` column and
    `Brief.regionId`/`regionName` still mean the change's own region; the class and `forList` javadocs now say so and
    state the rule.
  - Unchanged: `canSeeRecord`, `PendingChangeViews.detail`/`blockedReason` strings and precedence,
    `canApprove`/`canReject`/`canWithdraw`. No response-shape change.
- `summary()` and `awaitingMeCount()` are unaffected live: a move closes every pending change on the customer, so
  the record's region and the change's region can differ only on decided changes. As of a date, a change was
  outstanding only while its record was still in the change's region.
- Tests (`ApprovalVisibilityTest`). Both fail at the nico assertions without the fix (404 where 200 was expected).
  That was checked by running them against the pre-fix file from `refs/snapshots/E2E-r4-F1`.
  - `aChangeLeftBehindByARegionMoveShowsNoLiveValuesToTheOldRegion` (the r4 pin) keeps every existing assertion and
    adds nico (VIEWER, READ/ALL North only).
    - Before the move: nico gets 404, and the change is not in nico's list.
    - After the move:
      - nico gets 200 on the invoice.
      - nico gets 200 on the change: CLOSED, `current` and every `changes[].current` filled, `blockedReason` null,
        `canApprove` false.
      - nico's list has the change with no regionIds, with `filter=id:eq:{pc}` (exactly `[pc]`), and with
        `regionIds=north`.
      - sally's list still has it, and vic's list does not; vic's detail is still 404 (the r4 pins).
      - The admin's list has it with `regionIds=north` (record branch) and with `regionIds=south` (approver branch).
    - As of the day before the move: nico gets 404 and the change is not in nico's as-of list. The sally and vic
      as-of assertions are unchanged.
  - New test `aHeldCreateLeftBehindByARegionMoveFollowsTheCustomer`. Setup: South INVOICE_CREATE is 1000; sam (South
    maker) POSTs 2500 and gets 202 (the pending change has entityId null); the next day the admin moves the customer
    to North and the change is CLOSED.
    - nico gets 404 before the move. After it, nico gets 200 and the change is in nico's list.
    - vic gets 200 before the move. After it, vic gets 404 and the change is not in vic's list.
    - sally gets 200 with 'You no longer have access to this record'.
    - sam gets 200.
  - These pins pass unchanged: `anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout`,
    `theRegionsAskedAboutNarrowTheList`, `theViewSaysWhoseChangesTheListIsAbout`,
    `aChangeOfLimitsIsSeenOnlyByTheRegionsApproversAndLimitSetters`, `aMakerWhoLostTheRegionNoLongerSeesTheirChange`,
    `aBookColleagueSeesItAndSomeoneOutsideTheBookDoesNot`.
- REG-155 replay was done on a private env, not the harness and not `e2e/dist`. The env scripts were copied to
  `scratchpad/r5b1/e2ehome` with a jar built from this tree; area b1 ran on ports 18137/18237 with DB e2e_b1 and was
  torn down afterwards. Steps: seedWorld, South INVOICE_TERMS 1000, nico READ/ALL North, a new South customer with a
  2500 invoice (POC sam), sam PATCHes the due date (202, pc 1), and the admin moves the customer to North (200).
  - nico gets 200 on the invoice, and the audit rows CHANGE_REQUESTED and CHANGE_CLOSED carry the pc.
  - nico gets 200 on `/api/approvals/1`: CLOSED, current filled, `changes[].current` filled, `blockedReason` null,
    `canApprove` false.
  - `?filter=id:eq:1` returns exactly 1 row.
  - UI: /invoices/15, History tab, click 'Approval #1': it opens `#/approvals/1` with the change and its Current and
    Proposed values, and not "That change does not exist".
  - Script: `scratchpad/r5b1/replay/`.
- Result: `cd backend && mvn -q -o test` ran 852 tests with 0 failures and 0 errors. `cd mail-service && mvn -q -o
  test` is green (exit 0).
