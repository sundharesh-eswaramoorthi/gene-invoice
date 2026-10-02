# E2E triage — round 1

Every defect was checked against the working tree. AUT-R1-1 was also reproduced on a private environment
(`up.sh triage 9`, since torn down): a rule with ENTITY_UPDATED on CUSTOMER, then PUT /api/customers/1. The result was
automation_events row 2 UPDATED/PENDING, attempts 1, last_error "Unable to access lob stream". The root cause in
backend.log is `PSQLException: Large Objects may not be used in auto-commit mode`. The same environment showed that
`automation_rules.actions_json` holds a large-object OID ('74995'), not JSON: on PostgreSQL, Hibernate 6.5 stores
every `@Lob String` as a pg large object, and pg_largeobject_metadata had 3 rows.

Streams: backend B1→B4 run in sequence, frontend F1→F3 run in sequence, and the two streams run in parallel. Each
frontend package states the contract it builds against. It must work with the current server and with the fixed one.

Isolation for every package: never touch the user's services (see the harness rules). Any Postgres check uses only
gi-e2e-db (:18432), a database named e2e_*, and ports 18100-18299.

## Backend
- B1: automation reads large objects inside a transaction on PostgreSQL. [AUT-R1-1, AUT-R1-2]
- B2: upgrading a DB that already has legacy automation_rules / automation_events tables. [MIG-R1-1, MIG-R1-2]
- B3: approvals. Invoice-terms approval applies only what it asked for; a blank note counts as no note; the as-of
  detail hides later decisions; change rows are readable. [APP-R1-1, APP-R1-2 (be), ASO-R1-2 (be), APP-R1-4 (be)]
- B4: automation placeholders hide Customer.* the runner cannot see; the requeue message names no API path;
  dashboard ties across parts sort by name. [RR-1, RR-2 (be), RR-3]

## Frontend
- F1: approval detail. As-of masking, multi-line cells, readable labels and values. [ASO-R1-2 (fe), APP-R1-3, APP-R1-4 (fe)]
- F2: small UI fixes. Assignee tooltip, the Run now wording, MANUAL dead events, the invoice page sends only what
  changed. [TAS-R1-1, AUT-R1-4, RR-2 (fe), APP-R1-2 (fe)]
- F3: the as-of banner in the accessibility tree. [ASO-R1-1]

## Rejected
- AUT-R1-3: working as designed. PRD A3 says assignees and recipients use "the same role-at-a-level model the email To
  field uses". The existing email To field (EmailAddressing, unchanged since db4b4e6) sends to every holder of the
  role, and DESIGN §5.9.1 resolves ROLE assignees to all active holders on purpose. The PRD's "a role held by several
  people resolves to the primary" is in A4, which is about placeholders. Placeholders, the From role and the promise's
  Collection POC already resolve to the primary (AUT-API-52 passes). This is recorded as a user decision. The test
  expectation is fixed in harnessFixes.

## User decisions (defaults applied)
- AUT-R1-3: a role that is a task assignee or an email To/Cc goes to every active holder who can see the record. This
  matches the existing email To field and DESIGN §5.9.1. "Resolves to the primary" applies to placeholders, the From
  role and the promise Collection POC.
- ASO-R1-2: as of T, a change decided after T shows no decider, decision date, note, failure reason, replacement or
  result. `status` stays the live status (the §6.11 contract), and `statusAt` is the status at T.
- RR-2: on the health card, a dead MANUAL event gets "Open runs" (the rule's Runs tab, where Retry takes the rule
  over) instead of Requeue. The owner can still requeue it through the API.
- RR-3: when parts are merged, tied amounts sort by name (case-insensitive, then exact, then id). A single part keeps
  the database's ORDER BY.
- APP-R1-2: a blank note and no note are the same value for approvals and conflicts.
- MIG-R1-1/2: when a legacy automation table has rows that the new code cannot read (for example trigger_kind CREATED,
  or actions_json null), the upgrade copies them to <table>_legacy and deletes them from the live table, with a WARN
  log. It does not drop them.

## Harness fixes (tester applies next round)
- AUT-API-53 / AUT-API-54 (suites/automation): expect every active holder who can see the record. For the task,
  assignees[0].people = [Sam South (primary), Nora North], and it appears in both sam's and nora's My tasks. For the
  email, recipients = [Sam South, Nora North]. Keep AUT-API-52's primary checks for placeholders, From and the promise
  Collection POC.

The per-package instructions are in the triage agent's StructuredOutput. The text is the same.

---------------------------------------------------------------------------------------------------------
## B1: automation reads on PostgreSQL [AUT-R1-1, AUT-R1-2]
Root cause (reproduced). Hibernate 6.5 PostgreSQLDialect stores each `@Lob String` as a pg large object: the TEXT
column holds its OID. Reading one needs a transaction ("Large Objects may not be used in auto-commit mode").
Inherited CRUD methods such as findById run inside SimpleJpaRepository's readOnly transaction. Custom `@Query`
methods declared on the interface do not. So EventProcessor.candidates (EventProcessor.java:180, which calls
AutomationRuleRepository.findEntityCandidates at l.44) and ScheduleTicker.tick (ScheduleTicker.java:69, which calls
findDueSchedules at l.94-102) fail when they run with no transaction on the worker and scheduler threads. H2 tests
cannot see this.
1. Put `@org.springframework.transaction.annotation.Transactional(readOnly = true)` on every query method that is not
   `@Modifying` and returns the entity (E, Optional<E>, List<E>, Page/Slice/Stream<E>), in each repository whose
   entity has an @Lob field. That means AutomationRuleRepository (findEntityCandidates, findDueSchedules, and any
   others), AutomationEventRepository (findByHistoryTxIdOrderByIdAsc, findQueuedManual, findReplayable, and any
   other entity-returning @Query), AutomationRuleRevisionRepository, PendingChangeRepository, AuditLogRepository and
   DisputeRepository. The `@Lock` method findByIdForUpdate gets plain `@Transactional` (not readOnly). Do NOT put
   readOnly at the interface level, because @Modifying methods would inherit it.
2. Do NOT remove @Lob or change a column type. Existing Postgres rows hold OIDs, so a plain text mapping would read
   '74995' as the JSON. The growth of large objects is out of scope.
3. Tests.
   (a) New common/LobReadsAreTransactionalTest, a static guard in the style of history/ModifyingQueryGuardTest. Find
       every interface under src/main/java that extends JpaRepository<E, ?> (regex on the source, then Class.forName).
       If E or a superclass has a field annotated jakarta.persistence.Lob, every declared abstract method that is not
       @Modifying and whose generic return type mentions E must carry @Transactional (on the method or the
       interface). Confirm it fails before the fix, naming findEntityCandidates and findDueSchedules.
   (b) ScheduleTickerTest: call scheduleTicker.tick(now) from a test method that is not @Transactional, with a due
       SCHEDULE_DAILY rule. Assert one SCHEDULED event and run, and that nextRunAt moved to the next slot.
       WorkerDurabilityTest or TriggerMatrixTest: an ENTITY_UPDATED rule plus a customer PUT, drained by the worker,
       gives one task. H2 cannot reproduce the LOB error, so (a) is the regression guard.
   (c) Optional check on Postgres (gi-e2e-db only). Run build-dist.sh, then `up.sh b1 <idx>`. Create a rule with
       trigger ENTITY_UPDATED on CUSTOMER and a CREATE_TASK action, then PUT the customer. Expect the
       automation_events row DONE within 5 s and one task. Add a SCHEDULE_DAILY rule 1 minute ahead and expect one
       SCHEDULED run. Run down.sh afterwards.

## B2: upgrading a DB with legacy automation tables [MIG-R1-1, MIG-R1-2]
The user's dev DB already has automation_events (idempotency_key/entity_id/entity_type/trigger_kind/status NOT NULL;
CHECKs on status QUEUED..FAILED, trigger_kind CREATED/UPDATED/DAILY/WEEKLY, entity_type) and automation_rules
(action/run_count NOT NULL; CHECKs automation_rules_action_check, _entity_type_check, _trigger_kind_check listing
CREATED..WEEKLY). Both tables are empty; see e2e/suites/migration/data/dev.sql l.112-190. ddl-auto:update only adds
columns.
1. common/schema/SchemaSupport:
   - `relaxColumns(Connection, String table, Set<String> keepNotNull, boolean dropAllChecks)`. For each column of the
     table (information_schema, current_schema() or schema()) that is NOT NULL and not in keepNotNull, run
     `alter table T alter column C drop not null`. Drop each CHECK constraint that mentions a column outside
     keepNotNull and outside the mapped set (for pg use pg_get_constraintdef; for H2 use
     information_schema.check_constraints). When dropAllChecks is set, drop every CHECK on the table. Log each change
     at INFO and do the work in one transaction.
   - `widen(...)`: stale-CHECK detection must not key on the enum's first constant. A CHECK is this column's enum
     check when its definition references the column (columnInCheck) and is an IN list or `= ANY (ARRAY[...])` of
     string literals over that column only. It is stale when any current constant is missing from it. Keep the
     native H2 ENUM path. Update the Javadoc ("New constants must be appended last" is no longer the detection rule).
2. automation/AutomationSchemaUpgrade.afterPropertiesSet, before the widen calls:
   - Read the mapped columns and their nullability from the Hibernate metamodel:
     `emf.unwrap(SessionFactoryImplementor.class).getMappingMetamodel().getEntityDescriptor(X.class).forEachSelectable((i, s) -> ...)`
     with s.getSelectionExpression() and s.isNullable(). Keep the EntityManagerFactory the constructor already
     receives.
   - automation_events (outbox, DESIGN §3.6): keepNotNull = the mapped non-nullable columns. That should be exactly
     attempts, claims, depth, id, kind, occurred_at, status; assert this against OutboxSchemaTest. Set
     dropAllChecks = true.
   - automation_rules, and automation_runs and automation_effects if they exist: keepNotNull = the mapped
     non-nullable columns, dropAllChecks = false. CHECKs on unmapped columns are dropped. Enum columns then go
     through the fixed widen().
   - Legacy rows. When the table has an unmapped legacy column (automation_rules.action or filters_json;
     automation_events.idempotency_key or trigger_kind), copy rows the new mapping cannot read (automation_rules:
     actions_json is null, or trigger_kind/entity_type not in the enum; automation_events: status not in
     PENDING/PROCESSING/DONE/DEAD or the entity's status constants, or kind null) into <table>_legacy with
     `create table if not exists ... as select * ... where false` followed by insert-select. Delete them from the live
     table and WARN with the count.
   - The upgrade must be idempotent and must be a no-op on a fresh schema.
3. Tests.
   - SchemaSupportTest (plain H2 in-memory connection, as the existing cases do):
     (a) Create the legacy automation_events DDL (H2 IN-list CHECKs, NOT NULL idempotency_key/entity_id/trigger_kind)
         plus the new nullable columns. Call relaxColumns(keep={id,attempts,status,kind,...}, true). Then insert
         (status 'PENDING', idempotency_key NULL, entity_id NULL): it succeeds, and no CHECK remains.
     (b) Put a legacy CHECK trigger_kind IN ('CREATED','UPDATED','DAILY','WEEKLY') on automation_rules. Call
         widen(TriggerKind). Inserting 'ENTITY_CREATED' now succeeds and 'BOGUS' is still refused.
     (c) Keep widenReplacesAStaleCheckConstraint green.
   - New automation/AutomationLegacySchemaTest (IntegrationTestBase, shared context; restore everything in
     @AfterEach). On the live H2 schema:
     `alter table automation_events add column idempotency_key varchar(200) default 'x' not null`, then drop the
     default. Add constraint automation_events_status_check check (status in ('QUEUED','DONE')) NOCHECK. Add
     automation_rules.action varchar(20) default 'CREATE_TASK' not null, then drop its default. Add check
     trigger_kind in ('CREATED','UPDATED','DAILY','WEEKLY') NOCHECK. Call upgrade.afterPropertiesSet() again. Then
     PUT /api/customers/{id} returns 200 and writes a PENDING event, and POST /api/automation/rules (MANUAL CUSTOMER,
     CREATE_TASK) returns 200. OutboxSchemaTest stays green.
   - The tester re-runs MIG-A-40/41 on the dev dump next round.

## B3: approvals [APP-R1-1, APP-R1-2 (be), ASO-R1-2 (be), APP-R1-4 (be)]
1. APP-R1-1, invoice/InvoiceChangeApplier.applyTerms (l.119-137). After the three-way checks, rebase the stored
   request so only fields the change actually sets are applied:
   ```
   notes  = same(norm(base.notes), norm(proposed.notes)) ? null : req.notes()
   salesPocUserId = same(base.salesPocUserId, proposed.salesPocUserId) ? null : req.salesPocUserId()
   ```
   Keep dueDate and paymentTerm as in req, and pass version null. Call
   invoiceService.updateOrHold(id, new UpdateInvoiceRequest(notes, poc, req.dueDate(), req.paymentTerm(), null)).
   Update the Javadoc.
2. APP-R1-2 (backend): add `static String norm(String s)` (null or blank gives null, otherwise s) and use it in
   applyTerms for the notes three-way comparison (base, proposed, now). Also use it in InvoiceService.termsGate
   (~l.250): when norm(req.notes()) equals norm(base.notes()), proposed.notes = base.notes(), so no spurious Notes row
   is shown and no conflict is raised.
3. ASO-R1-2, approval/PendingChangeViews.detail and brief: let `hiddenDecision = AsOf.active() && statusAt(pc) == PENDING && pc.getStatus() != PENDING`.
   When it holds, set decidedBy, decidedAt, decisionNote, failureReason, supersededById and result to null in
   Detail, and decidedAt to null in Brief. Keep `status` live and `statusAt` as it is.
4. APP-R1-4, readable change rows:
   (a) label(field): add an explicit map before the camelCase fallback: salesPocUserId→"Sales POC",
       collectionPocUserId→"Collection POC", customerSuccessPocUserId→"Customer Success POC", customerId→"Customer",
       paymentTerm→"Payment term", invoiceDate→"Invoice date", dueDate→"Due date", items→"Items", paidAt→"Paid on",
       method→"Method". Generic rule: strip a trailing "UserId" or "Id", and upper-case "poc" to "POC".
   (b) Add two nullable String components to `Change`: `currentText` and `proposedText`, the server's display text.
       Fill them for fields ending in UserId (the user's display name via MakerChecker.displayName, one batched
       userRepository.findAllById over the change's ids; unknown ids give "user #<id>"). Fill them for paymentTerm
       (PaymentTerm.valueOf(x).label(); leave null if the value is not a constant), for customerId (customer name),
       and for any other textual UPPER_SNAKE enum value (EmailText.humanize-style: "BANK_TRANSFER" gives "Bank
       transfer"). Leave them null otherwise; the client formats dates and money. POC fields are removed without
       POC_VIEW, as before.
5. Tests.
   - ApproveFlowTest:
     (i) Hold an INVOICE_TERMS change {notes:'first', paymentTerm:NET_90} as mia. Admin then PATCHes notes 'second'.
         Sam approves: APPROVED, the term is NET_90, notes stay 'second'.
     (ii) Same, but the request carries salesPocUserId=sam unchanged and admin then reassigns to mia: after approval
          the Sales POC is mia.
     (iii) APP-R1-2: the invoice has null notes, the held request is {notes:'', paymentTerm:NET_90}, admin sets
           'call Friday', sam approves: APPROVED, NET_90, notes 'call Friday'; the detail had no 'notes' row.
   - ApprovalAsOfTest: a change held at T-10d and approved today, read with GET ?asOf=T-2d, has statusAt PENDING and
     decidedBy, decidedAt, decisionNote and result all null. The list row's decidedAt is null. Live, they are
     present.
   - PendingChangeViews: a held invoice create with a Sales POC has a row with label "Sales POC" and
     proposedText = the user's name. A held payment's collectionPocUserId gives "Collection POC". paymentTerm CUSTOM
     gives proposedText "Custom" (PaymentTerm.label()).

## B4: automation placeholders, requeue message, dashboard ties [RR-1, RR-2 (be), RR-3]
1. RR-1, automation/action/RecordContexts.load(type, id, ruleId, ruleName, zone, pocVisible) (l.86-106). For INVOICE
   and PAYMENT, when !canSeeCustomer(customer) (asked as whoever is signed in: the owner in a run or the owner's
   view, the caller in the caller's view), put only Customer.Name, as renderForEmail does. Record the fact in the
   context by adding `boolean customerHidden` to PlaceholderValues (a new constructor argument, default false).
   PlaceholderRenderer.render adds CUSTOMER_HIDDEN ("Customer details are left out: you cannot see this record's
   customer") once per render when a Customer.* key other than Name is referenced and values.customerHidden(). Move
   the constant to PlaceholderRenderer or keep a single shared constant, and make renderForEmail use the same
   mechanism so there is one source. Add an overload
   load(..., boolean pocVisible, boolean customerVisible). RulePreviewService.evaluate computes customerVisible for
   `chosen` as the caller, outside `as(asOwner, …)`, with a new RecordContexts method
   `boolean callerSeesCustomerOf(TriggerEntity type, Long id)` (repository lookup of the record's customer, then
   canSeeCustomer; false when the record is not found). It passes both flags to actions(). The run path
   (ActionExecutor:136) keeps the 5-arg load, meaning the owner's visibility.
2. RR-2 (backend), automation/rule/RuleService.requeue (l.608-615): the message must name no API path and never say
   "null". Use "A run by hand is tried again from its run: open the rule's Runs tab and use Retry, which takes the
   rule over". When a run exists, append " (run #<id>)".
3. RR-3, dashboard/DashboardService.topPaying (l.212-217): for several parts, sort with
   `Comparator.comparing(PayingCustomer::collected).reversed().thenComparing(PayingCustomer::customerName, String.CASE_INSENSITIVE_ORDER).thenComparing(PayingCustomer::customerName).thenComparing(PayingCustomer::customerId)`.
   A single part keeps the SQL order. Update the comment.
4. Tests.
   - PreviewTest or TaskActionTest/EmailActionTest, reusing EmailPlaceholderTest's setup (cody: COLLECTION_POC, MANAGE
     with BOOK scope, sees a payment of Globex but gets 404 on /api/customers/{globex}; grant the privileges the rule
     needs). A PAYMENT rule owned by cody with CREATE_TASK title "{{Customer.Name}} {{Customer.Phone}}" and SEND_EMAIL
     body "{{Customer.Address}}". Run now, then drain. The task title is "Globex" (the phone is absent). The stored
     email body lacks the address. The effect or preview warnings contain the CUSTOMER_HIDDEN text. POST preview as
     cody shows the same. The owner's view as admin of cody's rule also hides them (caller admin sees; owner cody
     does not). An admin-owned copy of the rule renders the phone.
   - WorkerDurabilityTest or RunNowTest (the existing SC-3 case): the 400 message contains "Runs tab" and no "/api/"
     and no "null".
   - A dashboard test (RegionBookTest or the dashboard test class): a user with FULL in NORTH and BOOK in SOUTH;
     "Zeta" (NORTH) and "alpha" (SOUTH, in their book) each collect 100. topPaying lists alpha before Zeta. The
     single-part test from CF-4 stays green.

## F1: approval detail [ASO-R1-2 (fe), APP-R1-3, APP-R1-4 (fe)]
Contract. After B3, GET /api/approvals/{id} on a past date nulls decidedBy, decidedAt, decisionNote, failureReason,
supersededById and result when statusAt == PENDING. Each changes[] item may carry `currentText`/`proposedText`
(String, nullable), and the label is readable ("Sales POC"). The screen must also behave correctly against the current
server, which does not mask or send text fields.
1. ASO-R1-2, features/approvals/approval_detail_screen.dart. Let `pendingThen = historical && (c.statusAt ?? c.status) == PendingStatus.PENDING`.
   When pendingThen, do not render the Decided or Note rows (l.232-241), Replaced by (l.243), a failure notice, or
   the 'Open <record>' link (l.73-78). In the historical view, show that link only when statusAt == APPROVED.
2. APP-R1-3, _ChangesTab (l.378): set `dataRowMinHeight: 48, dataRowMaxHeight: double.infinity` on the DataTable and
   give each value cell vertical padding (EdgeInsets.symmetric(vertical: 8)), so multi-line JSON (threshold windows,
   invoice items) is fully visible. Keep maxWidth 280 with wrapping. Use SelectableText if convenient.
3. APP-R1-4, approval_models.dart FieldChange: add `currentText`/`proposedText` parsed from json. In _ChangesTab, show
   text ?? a formatted value. The formatted value covers: an ISO instant string (DateTime.tryParse with a 'T') as
   formatDateTime; a yyyy-MM-dd string as formatDate(DateTime.parse(...)); an UPPER_SNAKE string
   (RegExp(r'^[A-Z][A-Z0-9_]+$')) as humanizeEnum; the existing money and number rules otherwise. Use the same
   formatting for the label fallback: if the label ends in " user id", show the field name mapped as the backend
   does (salesPocUserId → "Sales POC" and so on) so an older server still reads well.
4. Tests (test/approvals_test.dart).
   (a) A historical detail (asOf set, status APPROVED, statusAt PENDING, decidedBy/decidedAt/decisionNote/result
       present in the fake JSON) shows 'Awaiting approval' and no 'Decided', 'Note' or 'Open ' text. Live, the same
       JSON shows them.
   (b) A change whose proposed value is a 4-line map ({amount, windowHours}) renders the 'windowHours' text fully:
       tester.getSize(find.textContaining('windowHours')) is not clipped, meaning the row height is at least the
       text height. Assert that the DataTable's dataRowMaxHeight is double.infinity.
   (c) A row {field:'salesPocUserId', label:'Sales POC', proposed:4, proposedText:'Sam South'} shows 'Sales POC' and
       'Sam South', not '4'. A row with no text fields: {field:'invoiceDate', proposed:'2026-09-27T14:50:16Z'}
       shows the formatDateTime text, and paymentTerm 'CUSTOM' shows 'Custom'.

## F2: small UI fixes [TAS-R1-1, AUT-R1-4, RR-2 (fe), APP-R1-2 (fe)]
No backend dependency. The existing contract applies: list rows carry assignees with people [] and unresolved false for
roles (brief). DeadEvent has kind and ruleId. PATCH /api/invoices/{id} treats an absent field as unchanged.
1. TAS-R1-1, features/tasks/tasks_screen.dart TaskAssigneesText (l.208-224). For each role assignee:
   - a.unresolved gives '${a.label}: nobody holds this now';
   - a non-empty people list gives '${a.label}: names';
   - an empty people list with unresolved false (brief list rows) is left out of the tooltip.
   When no lines remain, return the Text without a Tooltip. Fix the doc comment to match.
2. AUT-R1-4, features/automation/run_now_dialog.dart:219/233: the label is
   'Every ${widget.entity.label.toLowerCase()} that matches'. Keep the plural where the text is really plural.
3. RR-2, features/automation/rules_screen.dart AutomationHealthCard (l.560-566). When e.kind == 'MANUAL' and
   e.ruleId != null, the trailing widget is TextButton(key: ValueKey('open-runs-${e.id}'), child: Text('Open runs'))
   that calls goGuarded(context, '/automation/${e.ruleId}?tab=runs'), instead of Requeue. When it is MANUAL without a
   ruleId, show no button. Other kinds keep Requeue.
4. APP-R1-2 (frontend), features/invoices/invoice_detail_screen.dart. Keep `_savedNotes` (set at l.73 from
   inv.notes ?? '' and after a successful save next to _savedTerm). In _save (l.142), send 'notes' only when
   _notes.text.trim() != _savedNotes.trim(). If nothing at all changed, do not PATCH; say 'Nothing to save' or keep
   the button disabled, whichever the screen already does for dirty state.
5. Tests.
   - tasks_test.dart: a list row with a ROLE assignee {people:[], unresolved:false} has a tooltip or semantics
     without 'nobody'. With unresolved:true it says 'nobody holds this now'. With people [Sam] it names Sam.
   - rules_screen_test.dart: the run now dialog for an INVOICE rule shows 'Every invoice that matches'. The health
     card with a dead MANUAL event (ruleId 7) shows 'Open runs' and no Requeue, and tapping it routes to
     /automation/7?tab=runs. A dead CREATED event still shows Requeue.
   - An invoice detail test (new or detail_gating_test.dart): change only the payment term and Save. The PATCH body
     has paymentTerm and no 'notes' key. Change the notes and the body has notes.

## F3: the as-of banner in the accessibility tree [ASO-R1-1]
shared/widgets/as_of_banner.dart is placed before the page `child` (a nested go_router ShellRoute Navigator) in a
Column in app_shell.dart:216-222. The likely cause is the ModalRoute's ModalBarrier, which wraps BlockSemantics
around the route's page. BlockSemantics drops the semantics of everything painted before it in the same semantics
container, including the banner (the app bar sits in a different container). Confirm this with the test first.
1. Write the failing widget test first, in test/view_scope_test.dart or a new as_of_banner_test.dart. Pump the real
   app shell under a GoRouter with a ShellRoute and one GoRoute page (a nested Navigator, as in the app), with
   viewAsOfProvider set to a date. Call `final handle = tester.ensureSemantics();`, then expect
   `find.bySemanticsLabel(RegExp(r'Viewing .* read only'))` findsOneWidget and a 'Back to today' button node in the
   banner (find.descendant(of: find.byType(AsOfBanner), matching: find.text('Back to today')) plus
   tester.getSemantics(...) having a tap action). Dispose the handle.
2. Fix: wrap the page in its own semantics container so the route's BlockSemantics cannot reach its siblings:
   `Expanded(child: Semantics(container: true, explicitChildNodes: true, child: child))` in app_shell.dart. Give the
   banner `Semantics(container: true, liveRegion: true, child: …)` so it is announced when it appears. If the test
   shows another cause, fix that instead and note it in the FIXLOG. Check that the rail and the drawer still appear
   in the semantics tree, and add an assertion for one rail item's label.
