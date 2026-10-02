# Triage plan (hardening round 1)

All findings verified against the working tree (read-only). Duplicates merged:
- CR-1 = CF-1 (link versioning race)            - PC-4 = CF-2 (inbox mark-all-read regions)
- PC-5 = CF-3 (deleted-record emails vanish)    - CT-6 = PC-6 = CF-10 (rule people 'User #id')
- CT-2 = PC-3 (approvals 'Requested by' sort)   - CT-1 ⊂ PC-2 (rules Status sort; PC-2 adds past-date sorts/filters)
- CT-4 = CF-6 (approval money as doubles)       - PC-1 = CF-11 (pendingApproval never shown on lists)
- PC-7 = CR-2 = CF-12 (flaky region_selector_test)

Streams: backend B1→B5 sequential; frontend F1→F4 sequential; streams parallel.
Frontend packages are written against the contract backend packages WILL produce (stated in each).

## Backend
B1 Contract: RuleDto.people, past-date rule list sorts/filters, CORS Location   [CT-6, PC-6, CF-10 (be), PC-2 (be), CF-7]
B2 Security: book/region leaks and requeue ownership                              [SC-1, SC-2, SC-3, SC-4, CF-9]
B3 History: link-writer owner lock, baseline started_at, moved_at, clock guard   [CR-1, CF-1, CF-13, CF-14, CF-15]
B4 Inbox regions + dashboard order                                                [PC-4, CF-2, PC-5, CF-3, CF-4]
B5 Approvals detail/links + missing tests                                         [CT-5, CF-8, CF-16, CF-17, CF-18]

## Frontend
F1 Table scaffold honours schema sortable; export/bulk carry extraParams; rules list past-date limits  [CT-1, CT-2, PC-3, PC-2 (fe), CT-3]
F2 'Awaiting approval' chip on list rows                                           [PC-1, CF-11]
F3 Approval money formatting, composer drops Rule.*, rule people names seeded      [CT-4, CF-6 (fe), CF-5, CT-6/PC-6/CF-10 (fe)]
F4 Deterministic region_selector_test                                              [PC-7, CR-2, CF-12]

## Rejected
- CF-6 backend part (USE_BIG_DECIMAL_FOR_FLOATS in PayloadCodec.tree): Jackson 2.17's default JsonNodeFactory strips trailing
  zeros (500.00 -> 5E+2), DecimalNode.equals is scale-sensitive so changedSinceRequest comparisons would flip, and Dart's
  jsonDecode turns 500.00 into 500.0 anyway. Formatting belongs on the client (F3).
- PC-8 not built: user decision, default = out of scope (DESIGN §10.1 defers it; mail-service must stay untouched).

## User decisions (defaults applied)
- PC-8: email attachments from the record/customer? Default: deferred (DESIGN §10.1), nothing built.
- CF-11/PC-1: show pendingApproval on list rows? Default: yes, 'Awaiting approval' chip (B2 "visible to everyone who can see the record").
- SC-1: Customer.* placeholders when the caller cannot see the customer: default render empty with a warning (not 400).
- SC-3: requeue of a MANUAL event: default refuse 400 pointing to /runs/{id}/retry (unless caller owns the rule).

Full per-package instructions are in the StructuredOutput of the triage agent (same text as below).

---------------------------------------------------------------------------------------------------------
## B1 — Contract fixes the frontend builds against
1. RuleDto.people (CT-6/PC-6/CF-10 backend). backend/.../automation/rule/RuleDtos.java: add a last component
   `Map<Long,String> people` to RuleDto (serialises as {"12":"Priya Rao"}). Fill it in BOTH RuleService.toDto(AutomationRule,
   List<String>) (~l.789) and toDto(RuleSnapshot) (~l.808): walk the actions JsonNode recursively, collect every object node with
   "type":"USER" and a numeric "userId" (assignees, collectionPoc, from, to, cc…), load them with one userRepository.findAllById,
   name each with the same formatter the rest of the rules API uses (EmailText.nameOf); ids of users that no longer exist are
   omitted. Do NOT add it to RuleSnapshot (persisted as revision JSON and read back by RuleAsOfLists.snapshot). Empty map when
   no USER tokens. Test (existing rule API test class, e.g. RuleCrudTest/RuleApiTest): rule with CREATE_TASK assignee USER a
   and SEND_EMAIL to USER b → GET /api/automation/rules/{id} has people == {a: name, b: name}; same GET with asOf after save.
2. Past-date rules list (PC-2 backend). automation/rule/RuleAsOfLists.java page()/filter(): additionally support
   sorts triggerKind (trigger().kind()), entityType (trigger().entityType()), suspendedReason, createdAt, updatedAt (ties by id;
   nulls last). Keep refusing lastRunStatus/lastRunAt/nextRunAt ("This sort isn't available for past dates"). ('status' is
   notSortable in the schema so TableQuery.parse already 400s it live and past — leave it.) Filters: add status (EQ/IN,
   same meaning as TableSchemas.ruleStatusPredicate: ACTIVE = enabled && suspendedReason==null, DISABLED = !enabled &&
   suspendedReason==null, SUSPENDED = suspendedReason!=null; invalid value → same 400 text), suspendedReason (EQ/IN),
   ownerUserId (EQ/IN), regionIds (mirror TableSchemas.ruleRegionPredicate exactly, incl. IS_EMPTY = unrestricted rule and
   how IN treats unrestricted rules), and for name every operator the schema offers for TEXT (at least EQ, CONTAINS,
   STARTS_WITH if FilterOperator has them; case-insensitive like live). Keep refusing lastRunStatus/lastRunAt/nextRunAt.
   Test (the existing rule as-of test class): asOf a past date with sort=triggerKind,asc / entityType,desc →200 ordered;
   filter status:eq:ACTIVE, ownerUserId:eq:X, regionIds:in:N →200 with the right rows; sort=lastRunAt,desc →400.
3. CORS (CF-7). config/SecurityConfig.java:49 add HttpHeaders.LOCATION to setExposedHeaders. Test: unit test on
   corsConfigurationSource().getCorsConfiguration(request) exposed headers contain "Location", or MockMvc with Origin on a
   202 held change asserting Access-Control-Expose-Headers contains Location.

## B2 — Security
1. SC-1 automation/action/RecordContexts.renderForEmail (~l.125): PROMISE/DISPUTE must not read the customer unscoped.
   Read it through customerService.get(target.customerId()) (scoped); on NotFoundException put only Customer.Name (already
   on the record) — or nothing — so other Customer.* render empty (PlaceholderRenderer already warns "X is empty"), and add
   one warning "Customer details are left out: you cannot see this record's customer". Apply the same visibility check in
   the INVOICE/PAYMENT branch (customerOf(entity) is read through the record, not the customer's scope). Test in
   EmailPlaceholderTest: SALES_POC who cannot GET /api/customers/{c} sends POST /api/emails on a DISPUTE and a PROMISE of c
   with {{Customer.Phone}}{{Customer.Address}}{{Customer.Outstanding}} → body has none of the values; same caller on own
   customer still renders them.
2. SC-2 audit/AuditTimelineService.related (~l.174-240): for staff callers filter related invoices, payments, promises,
   disputes by what the caller can open: add a helper (like TaskService.visibleAmong, l.423) that for a Kind/entity/schema
   requires the kind's VIEW privilege (INVOICE_VIEW, PAYMENT_VIEW, PROMISE_VIEW, DISPUTE_VIEW via currentUser.has) and
   returns ids within scopeResolver.scope(kind, VIEW, GRANTED).predicates(), chunked by VISIBLE_CHUNK, using
   queryExecutor.ids with TableSchemas.INVOICES/PAYMENTS/PROMISES/DISPUTES. Keep allocations only when both payment and
   invoice ids are visible. Customer logins: keep today's behaviour (root check already limits them to their customer; the
   internal-dispute exclusion stays) unless ScopeResolver scopes customer logins identically — then use it for them too.
   Tests (RegionBookTest or ApprovalAuditTimelineTest): sam (SALES_POC, owns one Acme invoice) GET /api/audit?entityType=
   CUSTOMER&entityId=acme&includeRelated=true does not contain tom's INVOICE_CREATED row nor its CHANGE_* rows; sam's own
   invoice rows present; a customer login's includeRelated still shows its own invoices.
3. SC-3 automation/rule/RuleService.requeue (~l.599): load the event; if AutomationEvent.MANUAL.equals(ev.getKind()) and the
   caller is not the rule's owner (ev.getRuleId() → rule.getOwnerUserId()), throw BadRequestException("A run by hand is
   tried again from its run, which takes the rule over: POST /api/automation/runs/{runId}/retry") (find the run by eventId
   for the message; plain text if none). Test (WorkerDurabilityTest or RunNowTest): owner's disabled MANUAL rule run goes
   DEAD; admin POST /events/{id}/requeue → 400, event still DEAD, no task made after drain; non-MANUAL DEAD event still 202.
4. SC-4 automation/run/ActionExecutor.act (~l.131): after loading rc, if the session's ViewScope regions
   (ViewScopeHolder current scope, set by OwnerSession.call from OwnerSession.regions(rule, ev)) is non-null and does not
   contain rc.regionId(), record the effect SKIPPED with "The record is no longer in the rule's regions" before
   prepare/perform (use the existing record(...) SKIPPED path, Outcome.SKIPPED). Apply the same rule in the preview's action
   rendering (RulePreview / preview path that renders actions) as a warning/skipped sample. Test (new case next to the
   restricted-rule tests): NORTH-only rule, invoice UPDATED in NORTH, customer moved to SOUTH before drain → no task,
   effect SKIPPED with that message; unrestricted rule still acts.
5. CF-9 assignee/AssigneeService.probe/requireVisible (l.89-109): add a `String ownerType` parameter (AssignedTo.TASK/
   DISPUTE/PROMISE) and also require regionAccess.holds(userId, viewPrivilege(ownerType), regionId). requireVisible → 400
   fieldErrors.assignees "<name> cannot see tasks here" (disputes/promises likewise); probe → dropped with warning
   "<name> cannot see tasks here, so was not assigned". Update all 7 callers: DisputeController:108, TaskService:133,151,
   PaymentPromiseController:111,133, CreateTaskHandler:89, CreateDisputeHandler:111, CreatePromiseHandler:159. Optionally
   filter /api/assignees/people when an ownerType param is given (skip unless trivial). Tests: TaskService create with a
   USER assignee holding INVOICE_VIEW but not TASK_VIEW in the region → 400 fieldErrors.assignees; automation
   CreateTaskHandler drops him with a warning.

## B3 — History
1. CR-1/CF-1 history/HistoryWriter.writeLinks (~l.221): before link.currentTargets, lock the owners per VersionStore.CHUNK in
   ascending id: `s.createQuery("select o.id from " + ownerEntity + " o where o.id in :ids order by o.id", Long.class)
   .setParameter("ids", chunk).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList()` with ownerEntity =
   s.getFactory().getJpaMetamodel().entity(link.owner()).getName(). Owners deleted in this txn simply return no row. Update
   LinkHistory Javadoc ("relinking one owner never needs the owner's own row") and DESIGN note is out of repo — only code
   comments. Test: HistoryConcurrencyTest — make the relink case @RepeatedTest(10) (or add a variant) and keep passing; if a
   test hook exists, add a deterministic interleave; at minimum run the class 20x in the private copy under load.
2. CF-13 history/HistoryBaseline.run: on a first run where startedAt[0]==null and meta.getStartedAt()==null, set started_at
   from min(validFrom) over the version tables of HistoryModel.types() (one "select min(v.validFrom) from <Version> v"
   each), when any exist. Test: HistoryBaselineTest — delete history_meta row's started_at/baseline_completed_at after a
   baseline with versions present, run() again → started_at equals the earliest version's valid_from.
3. CF-14 region/CustomerRegionMoveService:135 movedAt(clock.instant().truncatedTo(ChronoUnit.MICROS)); prefer the
   transaction's history instant if HistoryTransactions exposes it cheaply. Test: move → moved_at has zero sub-micro nanos.
4. CF-15 test/.../common/time/ClockReadGuardTest: replace the zone pattern with one that flags any `<Type>.now(<arg>)` whose
   arg does not start with `clock`/`this.clock`/`AppClock.get()`/`WallClock.UTC` (Type list as in pattern 1); add
   `Calendar\s*\.\s*getInstance\s*\(` and `new\s+(java\.util\.)?GregorianCalendar\s*\(`; extend
   theGuardRecognisesEveryWayOfReadingTheSystemClock with LocalDate.now(UTC), ZonedDateTime.now(zone), LocalDate.now(ZONE),
   Calendar.getInstance(), new GregorianCalendar() — and assert LocalDate.now(clock) still passes. Must stay green on main.

## B4 — Inbox regions + dashboard order
1. PC-4/CF-2 email/InboxService.markAllRead(Long userId, Collection<Long> regionsOrNull); null → current query; non-null →
   select the unread TO recipient ids of userId with EmailRegionPredicate.onRecipient(regions) then update them in chunks
   (mark read at clock.instant()). InboxController.markAll passes askedRegions() (as NotificationController:81 does).
   Test in RegionParamCoverageTest mirroring markingAllReadTakesInOnlyTheRegionsAskedAbout for /api/inbox/mark-all-read.
2. PC-5/CF-3 email/EmailRegionPredicate.of: per REGIONAL kind add a disjunct for a record that no longer exists:
   type==kind AND entityId NOT IN (select id from <Entity>) AND its last-known region is in :regions, where last-known =
   AuditLog.regionId of the newest AuditLog row (max id) for (entityType==<audit entity type name for that kind — check the
   ENTITY constants: CUSTOMER/INVOICE/PAYMENT/PROMISE/DISPUTE>, entityId) with regionId not null; when there is no such row,
   treat it as the default region (RegionService.defaultRegion(): pass its id into of(...) or resolve once per request).
   Keep the predicate a pure criteria subquery (no per-row Java). Also set nothing on Email.entityDeleted (unused). Test in
   RegionParamCoverageTest: NORTH customer with an inbox email, delete the customer → listed with regionIds=NORTH, not with
   SOUTH; unread-count agrees.
3. CF-4 dashboard/DashboardService.topPaying (~l.211): when split.parts().size()==1 keep SQL order (no re-sort); when
   merging, sort only by collected desc (List.sort/stream sorted are stable, so each part's SQL tie order holds), then limit.
   Test: DashboardService test with two customers of equal collected whose names differ by case — single-region order equals
   the SQL ORDER BY order.

## B5 — Approvals detail/links + missing tests
1. CT-5 approval/ApprovalNotifier.recordLink: add `case "REGION" -> "/approval-thresholds?regionId=" + id;` (and
   `case "CUSTOMER" -> "/customers/" + id;` for completeness). Test: approve a THRESHOLD_CHANGE → GET /api/approvals/{id}
   result.link == "/approval-thresholds?regionId=<r>"; the APPROVED notification link equals it.
2. CF-8 approval/PendingChangeViews.changes (~l.161): when proposed == base for a field but current != null and current
   differs from base, still emit the row with changedSinceRequest=true. Compare numbers numerically (both isNumber →
   decimalValue().compareTo == 0) so 500 vs 500.0 is not drift. Test (ApproveFlowTest / PendingChangeViewsTest): hold an
   INVOICE_CANCEL, record a payment on the invoice so paidAmount moves, GET detail → a paidAmount row with
   changedSinceRequest=true.
3. CF-16 test/.../region/TestMoveParticipant: add a configurable `failAfter` message thrown from afterCustomerMoved;
   CustomerRegionMoveTest new case: the move 4xx/5xx and customer, children regions, version rows,
   customer_region_moves count and participant side effects are unchanged.
4. CF-17 ApproveFlowTest: grant the CUSTOMER role CHANGE_APPROVE in the region; customer login POST approve and reject on
   another maker's change on its own customer → 403, change still PENDING.
5. CF-18 RegionBookTest: give sam a NORTH grant whose tier for invoices is NONE (a role lacking INVOICE_VIEW there, or the
   tier rule that yields NONE) while another granted region is visible; GET /api/invoices?regionIds=NORTH → totalElements 0,
   lockedFilters [], regionIds [].

## F1 — Table scaffold sort/export contract; rules & approvals lists
Contract: live schema `sortable` flags (GET /api/table-schemas/...) are authoritative; approvals export reads
`?view=`; after B1, past-date rules list accepts sorts name,id,triggerKind,entityType,suspendedReason,createdAt,updatedAt and
filters name,enabled,status,suspendedReason,triggerKind,entityType,ownerUserId,regionIds (refuses lastRunStatus/lastRunAt/
nextRunAt).
1. core/table/data_table_scaffold.dart ~l.367: onSort only when c.sortKey != null && (schema == null ? false :
   schema.column(c.sortKey!)?.sortable == true) — while the schema loads, headers are not clickable. Add optional
   `Set<String>? sortableOverride`/`bool Function(String key)? canSort` and `Set<String>? filterFields` params: canSort, when
   given, further restricts sorting; filterFields restricts showFilterEditor's column list (add an `allowedFields`
   parameter to core/table/filter_editor.dart showFilterEditor/_FilterEditorDialog) and hides quick filters/chips outside it.
   Fixes CT-1, CT-2/PC-3 generically (also keep: remove sortKey 'status' from rules_screen.dart:81 and
   'requestedByUserId' from approvals_screen.dart:186 for clarity).
2. CT-3 _runExport (~l.557) and _runBulkAction (~l.530): pass `queryParameters: widget.extraParams` (when non-empty) to the
   dio.post so the approvals export gets ?view=AWAITING_ME|MINE|ALL.
3. PC-2 rules_screen.dart: when isHistoricalProvider is true, Last run / Next run columns get sortKey null (or canSort
   excludes lastRunAt,nextRunAt,lastRunStatus) and filterFields = {name, enabled, status, suspendedReason, triggerKind,
   entityType, ownerUserId, regionIds}; on entering a past date with a current sort on lastRunAt/nextRunAt/lastRunStatus,
   push `name,asc` through the route query, and drop filters on those three fields.
Tests: rules_screen_test — Status header not tappable (no request with sort=status); with asOf set and route sort=lastRunAt
the request goes out as sort=name,asc; filter editor on a past date lists no Last run/Next run. approvals_test — every
column header tappable only when the fake schema says sortable (fake schema must mirror backend flags: requestedByUserId
notSortable); 'Awaiting my approval' + select all matching + Export → request has queryParameters view=AWAITING_ME.
Also update test/support/fake_backend.dart schemas for automation-rules/approvals so status/requestedByUserId are sortable:false.

## F2 — 'Awaiting approval' on list rows (PC-1/CF-11)
Contract (existing): list rows of /api/invoices, /api/payments, /api/promises, /api/disputes carry `pendingApproval: true`
when a change is held. Add a small shared widget (shared/widgets/status_chip.dart: `PendingApprovalChip`, label 'Awaiting
approval', tooltip 'A change is waiting for approval') and show it next to the status chip (Wrap/Row, spacing 4) when
row.pendingApproval == true in: invoices_screen.dart:197, payments_screen.dart:154, promises_screen.dart:126,
disputes_screen.dart:71, their mobileCard builders, promises_tab.dart PromiseCard (~l.156) and disputes_tab.dart:97.
Tests (list_columns_test.dart or new pending_chip_test.dart): per list, a fake row with pendingApproval:true shows the chip,
false/absent does not; PromiseCard and disputes tab likewise.

## F3 — Approval money formatting, composer placeholders, rule people names
1. CT-4/CF-6 approvals/approval_detail_screen.dart: make _value field-aware: `_value(String field, Object? v)`; a num
   whose field name matches RegExp(r'(amount|total|balance|price|credit|outstanding|threshold|limit)s?$',
   caseSensitive:false) → formatMoney(v); other nums unchanged. For the Current/Proposed JsonDiffView panes (~l.106) and
   nested values, pre-map the JSON with the same key rule (recursive map copy replacing money nums with formatMoney strings)
   before pretty-printing. Test (approvals_test): detail with changes [{field:'total', current:1234.5, proposed:500.0}] shows
   formatMoney(1234.5) and formatMoney(500) text, not '1234.5'.
2. CF-5 automation/placeholder_button.dart: add `bool Function(String token)? exclude` (or `bool forManualEmail`) and drop
   items whose key starts with 'Rule.' (and empty groups) in send_email_dialog.dart:378. Test in
   send_email_placeholders_test.dart with the real catalogue shape (General group containing Today and Rule.Name): the
   compose picker lists Today but not Rule.Name.
3. CT-6/PC-6/CF-10 (frontend) contract after B1: GET /api/automation/rules/{id} (live and asOf) returns
   `people: {"<userId>": "<name>"}` (may be absent on old servers → treat as {}). automation_models.dart Rule: add
   `final Map<int,String> people` parsed from json['people'] (int.parse keys). rule_editor_screen.dart _seed(rule): `_people
   ..clear()..addAll(rule.people)` (keep names picked this session if the id is not in rule.people). Update
   test/support/fake_backend.dart rule fixtures to include people. Test (automation_rule_editor_test): load a rule whose
   actions have {type:USER,userId:12} and people {"12":"Priya Rao"} → chips/summary show 'Priya Rao', never 'User #12'.

## F4 — Deterministic region_selector_test (PC-7/CR-2/CF-12)
test/region_selector_test.dart: replace settled()'s 20 ms real-time wait. In 'every dashboard figure' keep the providers
typed so `.future` is readable (list of ProviderListenable<Future<Object?>> built from each FutureProvider's .future, or
`(p as dynamic).future`), and `await Future.wait(...)` after listen and again after setRegions({2}) (read .future again —
autoDispose providers rebuild). For 'the bell and the inbox badge' await unreadCountProvider.future/inboxUnreadCountProvider
.future likewise. If any provider cannot expose a future, use a bounded poll helper: loop `await Future<void>.delayed(
Duration.zero)` up to 200 times until backend.requests.length reaches the expected count. Apply the same to tasks_test.dart
~799/803 and approvals_test.dart ~844/848 settled() uses. Proof: temporarily set any residual delay to 0/1 ms and run the
file 20x — all pass (do not commit the probe).
