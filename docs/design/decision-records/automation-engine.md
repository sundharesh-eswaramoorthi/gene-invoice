# Decision record: automation engine (Part A: A1–A6) and how it fits Regions, Maker-checker and As-of

Status: decided. This record is for the implementation agents, who have not seen the proposals, and it is the complete specification. Backend paths are relative to `backend/src/main/java/com/geneinvoice/`. Flutter paths are relative to `frontend/lib/`.

---

## 0. Verdict

**Winner: the "reuse-first" proposal**, taken as the base, with nine grafts from the "robustness-first" proposal and eleven fixes to flaws found in one or both proposals (§2).

| Criterion | Reuse-first | Robustness-first | Notes |
|---|---|---|---|
| (1) PRD coverage | 4/5 | 4/5 | Both cover A1–A6. Both drop the predecessor PRD's "several assignees on Promise". Robustness adds much that the PRD does not ask for. |
| (2) Concurrency and failure | 3.5/5 | 4.5/5 | Robustness has fencing tokens, a claims cap, a customer-first lock and internal disputes. Both would wrongly stop the drain on a lock timeout (spot check S8). |
| (3) Fit with the codebase and pinned decisions | 4.5/5 | 3.5/5 | Robustness adds a fire-gate state table, a nightly revalidation job, and worst-case template rejection that turns away ordinary templates. |
| (4) Implementable by AI agents in H2-tested slices | 4.5/5 | 3/5 | The ON_ENTER fire gate (mark-seen, sweep-unseen, a write per non-match) is where agents are most likely to go wrong. |
| (5) Volume of change and regression risk | 4/5 | 3/5 | Reuse-first refactors `send_email_dialog.dart` (32 pinned tests). This record avoids that. |

### Spot checks against the source (14 done; all bear on this design)

| # | Claim checked | File | Result |
|---|---|---|---|
| S1 | The CHECK widen finds the stale constraint through the enum's **first** constant | `email/EmailSchemaUpgrade.java:40-88` | Confirmed. It filters on `contains("'" + values.get(0) + "'")`, so `CUSTOMER` must be **appended last** to `DisputeTargetType`. The method is package-private static on a package-private class, so it has to move. |
| S2 | `disputeOwnFields` sends every non-INVOICE value into the PAYMENT branch | `email/EmailTargets.java:296-300` | Confirmed. It is a ternary, so CUSTOMER would query `paymentRepository` with a customer id. |
| S3 | CUSTOMER dispute edits are "compile-forced" (reuse-first) | `dispute/DisputeService.java` | **Only partly true.** `snapshotTarget` and `describeTarget` are switch *expressions* (compile error). `ensureTargetBelongsToCustomer` and `applyChange` are switch *statements*, so a CUSTOMER value silently does nothing. The customer-facing `open` would then accept a CUSTOMER target with any id. **This needs an explicit guard.** |
| S4 | `TableSchemaController.VIEW_PRIVILEGE` has exactly 10 `Map.of` pairs | `common/query/TableSchemaController.java:21-31` | Confirmed. It must become `Map.ofEntries`. |
| S5 | Keyset paging on `id` is well defined | `common/query/TableQueryExecutor.java:77-87, 135-144` | Confirmed. `orderBy` adds the `id desc` tiebreaker only when the sort field is not `id`. `ids()` honours `limit`. `run` with `parseUnpaged` caps at 5000. |
| S6 | `EmailService.save` is private and stamps `sentByUserId` from `currentUser.require()` | `email/EmailService.java:259-295` | Confirmed. `Content` is also private. The email adapter must be a method of `EmailService`. |
| S7 | `PaymentService.applyTo` runs before the payment is saved | `payment/PaymentService.java:99-100` | Confirmed (new finding). Inside `applyTo`, `payment.getId()` is **null** during `record`. The publisher must ignore a null id, and the payment must be published by the callers. |
| S8 | `databaseUnavailable` can tell "database down" apart from "lock conflict" | `email/mailservice/MailServiceEventHandler.java:74-84` | **It cannot** (new finding). It returns true for any `TransientDataAccessException`, which includes `ConcurrencyFailureException`, `PessimisticLockingFailureException` and `CannotAcquireLockException`. The automation classifier must test for concurrency **first**. The bean is `@ConditionalOnProperty`, so the static method has to be moved. |
| S9 | A fixed movable clock is safe for existing tests | mail-service `MovableClock` against backend tests | **It is not** (new finding). Backend tests take today from the real clock (`PromiseFiguresAndPocTest`). The backend `MovableClock` must follow system time until a test sets it. |
| S10 | The test harness has a single `@Import`, does not reset disputes, and resets in a fixed order | `backend/src/test/.../IntegrationTestBase.java:41-89` | Confirmed. |
| S11 | `pollUnreadCount` swallows errors but starts a `Timer.periodic`, and `list_columns_test` overrides the badge providers | `features/notifications/notifications_providers.dart:18-35`, `test/list_columns_test.dart:153-158` | Confirmed. A new badge provider must be overridden in that test too. |
| S12 | `PaymentPromiseService.create` needs a principal and a Collection POC | `promise/PaymentPromiseService.java:71-103, 612-620` | Confirmed. It works under run-as. With no POC it returns 400. It does not check PROMISE_MANAGE itself. |
| S13 | `CurrentUser.has()` returns false with no principal, and `require()` throws | `auth/CurrentUser.java` | Confirmed (C-8). |
| S14 | `EmailTargets.load` goes through the scoped `get` of each service, and `Target.sender` is the first active holder | `email/EmailTargets.java:146-161, 221-254` | Confirmed. This is the primary rule from X7. |

---

## 1. The decision in one paragraph

**Publishing.** Every service write method explicitly publishes `(entity, id, CREATED|UPDATED)` to a `Propagation.MANDATORY` publisher. The publisher buffers the calls per transaction and writes FK-free, unique-free, string-only rows to `automation_events` in `beforeCommit` (the outbox).

**Consuming.** An in-process poller claims events with a conditional UPDATE, a lease and a fencing counter. For each rule, it installs the **rule owner's** principal (P3) and evaluates the rule's AND/OR condition **in the database** through the existing list engine, ANDed with the owner's own scope. Each action runs in its own transaction, which also inserts a uniquely-keyed effect row. That makes every action idempotent. Emails are dispatched after commit.

**Schedules and on-demand runs.** Daily and weekly schedules, and "Run now", insert events into the same outbox.

**Tasks.** Tasks are a new entity. Tasks, disputes and promises share one live "role at a level" assignee table. "My tasks" follows POC hand-overs automatically.

**Nothing escapes permissions.**
- Every read and write happens as the owner.
- Assignees who cannot see the record are dropped.
- Automation disputes are internal, so customer logins never see them.
- A change an owner's rule proposes cannot be approved by that same owner.

---

## 2. What changed from the proposals

### Grafts from robustness-first

1. **Fencing counter `claims`** on each event, with a dead-letter at `claims ≥ 20`. This catches crash loops that never record a failure. Lease expiry is recovered without counting an attempt.
2. **Customer-first row lock** (PPD-01) inside the transactions of the actions that create a Task, Promise or Dispute. It serialises "only if none is open" guards, region copy and a region move.
3. **Suspension as a distinct state** (`suspended_reason`, separate from `enabled`), plus an owner guard. It suspends a rule when the owner is inactive, not staff, missing a required privilege, or when the definition is invalid or the rate limit is hit.
4. **Internal disputes** (`disputes.internal`), hidden from customer logins. Approving a *change* proposed by one's own rule is refused.
5. **Placeholder fallbacks** `{{Customer.Phone|n/a}}`.
6. **Neutral `WriteOrigin` thread-local.** `AuditService` and the publisher stamp it (X6), without `audit` depending on `automation`.
7. **Run now**, extended:
   - It is idempotent while one is pending.
   - It accepts an optional `recordIds` (up to 50).
   - Scheduled and manual runs are **sliced**: a cursor checkpoint and a 10 s yield, so long runs do not starve entity events.
   - A run is **superseded** if the rule is edited mid-run.
8. **Stable action keys (UUID)** in the dedupe key, a per-rule hourly **circuit breaker**, and **coalescing** of pending UPDATED events for one record.
9. **Test coverage oracle.** A Hibernate `Integrator`, loaded only through `application-test.yml`, records every Customer, Invoice and Payment insert or update. The test asserts that each one has an outbox row.

### Fixes to flaws

1. **Promise with several assignees** (predecessor PRD intent, which both proposals dropped). A promise keeps its single accountable `collectionPoc`, which is the book key and the target of the broken-promise notice. It also gains live co-assignees in the shared assignee table.
2. **Explicit CUSTOMER handling** in the switch *statements* of `DisputeService` (S3). The customer-login `open` path refuses a CUSTOMER target.
3. **Concurrency before infrastructure** in failure classification (S8).
4. **Null-id guard** in the publisher. The payment is published by `record`, not by `applyTo` (S7).
5. **`MovableClock` follows real time until set** (S9).
6. **"My tasks" is not a `lockedFilters` entry.** That would give the wrong "your role limits this list" chip (R14, G8).
7. **The frontend does not refactor `send_email_dialog.dart`.** The picker widgets are new files, so the 32 compose tests are untouched.
8. **Rules can be written by staff roles**, not only ADMIN (predecessor: "a rule anyone can write in the UI"). Editing someone else's rule needs AUTOMATION_ADMIN and an explicit confirmation that ownership moves.
9. **Rejected:**
   - robustness's save-time "worst case length" rejection (it refuses plausible templates);
   - skipping an action on a missing value;
   - the ON_ENTER fire-gate table (too complex; see §3).
10. **Assignee visibility is checked by probing as that user** (`VisibilityProbe`), not by re-deriving book rules. It follows B1 automatically.
11. **`ViewScopeHolder` defaults to live, all-permitted-regions** on non-request threads instead of throwing. After the P6 clock retrofit, the promise and email sweepers may read "today" through it.

---

## 3. Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Hibernate or JPA listeners, or `AuditService.record`, as the trigger source | X2. They miss unaudited money paths and fire for fixtures and backfills (id 0). They also have the flush-after-`beforeCommit` problem. Kept only as the test oracle. |
| RabbitMQ, or `@TransactionalEventListener(AFTER_COMMIT)` | No broker (P1/P2). Dual-write loss. `EmailDispatcher` refuses to run inside `afterCommit`. |
| `SKIP LOCKED` claims | `H2Dialect` silently degrades to `FOR UPDATE`. The conditional UPDATE behaves the same on H2 and PG. |
| Evaluating conditions in memory against an event-time snapshot | It would duplicate `FilterPredicates` semantics (null-inclusive `neq`, trimmed `isEmpty`, date bounds, `relative`) and derived columns. Accepted trade-off: the condition sees committed state at consumption time, usually within seconds. |
| Robustness's ON_ENTER / ONCE / EVERY_TIME fire-gate table | It needs a write per non-matching evaluation, plus mark-seen and sweep-unseen bookkeeping for schedules. `repeat` (ONCE_PER_RECORD / EVERY_TIME) together with the "only if none open" guards covers the real cases (§12.1) with one unique key. |
| A seeded system user with SCOPE_OVERRIDE | Bypasses B1 and breaks "nothing escapes permissions" (X6). |
| Run-now or preview running as the owner | Preview would show the caller records they cannot see. Run-now runs **as the owner** (the rule's identity), but it is requested by an owner or AUTOMATION_ADMIN only. |
| Promise with several *accountable* POCs | It would redefine the promise book (`ScopeResolver.forPromises`) and the broken-promise recipient. Co-assignees give "several assignees" without changing scope. |
| Snapshotting role assignees to user ids at creation | It would break "via a role I hold" and POC hand-overs. The roles are resolved live, and removed rows are soft-deleted so history survives for as-of. |
| Relaxing `/api/emails/context` from EMAIL_SEND | It would change pinned tests (`EmailContextTest`). `/api/assignees/*` delegates with its own gate instead. |
| Rejecting templates at save by worst-case length | Refuses normal templates. For example, a 200-character task title with `Customer.Name` (150) plus `Invoice.Number` (40) and literals. Text is fitted with "…" at run time and a warning is recorded. |
| Skipping an action when a placeholder is empty | Automation would be lost silently. An empty value renders the fallback or "", and a warning is recorded. |
| Purging runs and effects | Effects hold the ONCE_PER_RECORD keys. Only processed events are purged. |
| Adding `origin` and `rule_id` columns to `emails` | Not needed. `batch_id = "rule-{ruleId}-run-{runId}"` (fits 40), and the effect row links to the email id. |
| A nightly rule-revalidation job | Invalid definitions are caught at evaluation and suspend the rule (INVALID_DEFINITION). |

---

## 4. PRD traceability

| Clause (quoted) | How it is met |
|---|---|
| A1 "A rule fires when a Customer, Invoice or Payment is created or updated, or on a daily/weekly schedule." | `TriggerKind {ENTITY_CREATED, ENTITY_UPDATED, ENTITY_CREATED_OR_UPDATED, SCHEDULE_DAILY, SCHEDULE_WEEKLY}` × `TriggerEntity {CUSTOMER, INVOICE, PAYMENT}`. Events are published from every write site, **including side effects** such as allocation, credit, seats and dispute application (§8.3). The `ScheduleTicker` inserts SCHEDULED events (§10.8). |
| A2 "Filters over that entity's fields — with AND and OR operations." | `Condition` is a tree of AND/OR groups whose leaves are the list page's `field:op:values` strings, validated against `TableSchemas.{CUSTOMERS,INVOICES,PAYMENTS}`. It is compiled to one Criteria predicate and evaluated in the database (§9). |
| A3 "Create a Task, Promise or Dispute, or send an Email. Assignees and recipients are picked with the same role-at-a-level model the email To field already uses." | There are four handlers (§12). The wire format is the existing `EmailDtos.EmailToken` `{USER \| ROLE+level \| CUSTOMER}`. Roles offered and their levels come from `EmailTargets.rolesOffered` and `defaultLevel`. Resolution uses `Target.holders` and `Target.sender`. The picker context comes from `/api/assignees/context`, which returns the same `ContextDto` as the email picker. |
| A4 "{{Customer.Name}}, {{Invoice.Balance}} and so on, in subject and body, offered for both levels. A role held by several people resolves to the primary." | The catalogue is per level: `Customer.*` everywhere, plus `Invoice.*` or `Payment.*` (§13). Role placeholders use `Target.sender` (primary if active, otherwise the oldest active seat, X7). Placeholders are allowed in email subject and body, task title and description, promise notes and dispute reason. |
| A5 "The trigger publishes an event; a consumer performs the action." | The save publishes to the outbox, and `AutomationWorker` performs the action (§10). |
| A5 "A rule can never slow down or break the user's save" | The save path only buffers in memory, then inserts k rows that cannot fail: no FK, no unique key, all strings fitted. It never reads rules. A test asserts zero reads of `automation_rules` (§8, T-O1). |
| A5 "nothing is lost if the consumer is down" | Rows stay PENDING. Leases, retries, backoff and dead-letters handle the rest. Schedules missed during downtime produce one catch-up run (§10). |
| A5 "A Rule can be scheduled or ran from UI whenever wanted." | Daily and weekly triggers. `POST /api/automation/rules/{id}/run` runs any enabled rule over its matching set, or over up to 50 chosen records (§10.9). |
| A6 "A task on a customer, invoice or payment — title, due date, status, assignees — since automation needs something to create and people need somewhere to see their work." | The `tasks` entity, several live assignees, and statuses OPEN, IN_PROGRESS, DONE and CANCELLED. There is a record tab, a `/tasks` list, **`/my-tasks`** with a nav badge, and a detail page (§14). |
| B1 "Every record belongs to exactly one [region]" | `tasks.region_id` is copied from the customer under the customer lock (P4). Automation disputes and promises inherit whatever B1 does for their tables. |
| B1 "A user holds different rights in different regions" | Rules run as the owner. Evaluation is ANDed with the owner's `ScopeResolver` scope, which carries B1's regions. Handlers check `hasIn(MANAGE, record.region)` when B1 provides it (§11). |
| B1 "The POC book is per region" | Book scope reaches evaluation, visibility probes and "My tasks" through `ScopeResolver`. |
| B1 "Every list, filter, count and dashboard figure becomes a question about which regions am I asking about." | The tasks list, summary, count and export read `ViewScope` through `forTasks()`. Rules are global, and their run logs are masked by the viewer's scope. Conditions may use B1's `regionId` column. |
| B2 "Any change above a configurable threshold ... becomes pending and needs approval from someone else ..." | An automation-created promise may come back as pending, with the owner as maker. It is recorded as effect `PENDING_CHANGE`. An internal dispute's proposed change needs a resolver other than its opener (the owner) (§12.4, §15). |
| B3 "Every list, report and rule evaluation can be asked as of a date" | Preview evaluates as of a date through `RuleEvaluationSource` (§9.4). The tasks list reads `task_history` as of a date. The rules list reads `automation_rule_versions`. The runs list filters on `created_at`. Writes reject `asOf` (P5). |
| Predecessor: several assignees on Task, Dispute and Promise | Task and Dispute: several live tokens. Promise: one accountable `collectionPoc` plus several live co-assignees (§12.3). |
| Predecessor: a "my tasks" list | `/my-tasks` and `/api/tasks/mine` (§14.5). |
| Predecessor: placeholders by level | §13. |
| Predecessor: nothing escapes existing permissions or the internal/shared rule on documents | Rules run as the owner. Assignees are visibility-probed. Disputes are internal. No attachments exist and no placeholder reads a document, so the internal/shared rule cannot be bypassed. |

---

## 5. Contracts with other decisions (and what Part A does if they have not landed)

| Contract | Owner | If absent when a Part A slice starts |
|---|---|---|
| `Clock` bean (P6) | foundation / Part B | Create `config/ClockConfig`: `@Bean Clock clock() { return Clock.systemUTC(); }`. |
| `common/scope/ViewScope(Set<Long> regionIds, LocalDate asOf)` with `LIVE = new ViewScope(null, null)`, `isHistorical()`, `requireLive()` (throws `BadRequestException("As-of views are read-only")`) and `asOfInstantExclusive()` = start of `asOf + 1` day UTC | Part B (P5) | Create exactly these members. |
| `ViewScopeHolder.current()`: the thread-local override if set, else the request's scope (filled by the filter from `regionIds` and `asOf`), else `ViewScope.LIVE`. `callWith(ViewScope, Supplier<T>)` sets the override and restores the previous one in `finally`. | Part B | Create it, including a `ViewScopeFilter` (`OncePerRequestFilter`) that parses `regionIds=1,2` and `asOf=yyyy-MM-dd` (400 on bad input). |
| `@AsOfReadable`, a method annotation. Any central guard that rejects `asOf` on non-GET requests must let annotated methods through. | Part B | Create the annotation in `common/scope`. Part A puts it on `RuleController.preview`. |
| `AppUserDetailsService.principalFor(Long userId): Optional<AppUserDetails>`, `@Transactional(readOnly = true)`. It loads the user and builds authorities exactly as `loadUserByUsername` does. B1 extends it to fetch region grants eagerly (C-4/R3). | Part A adds it, Part B extends it | Add it. |
| `CurrentUser.hasIn(String privilege, Long regionId)` | Part B (B1) | Handlers call `RegionRights.has(priv, regionId)`, which is `currentUser.has(priv)` until B1 replaces its body with `hasIn`. |
| `ScopeResolver.forX()` includes the region predicate (regions permitted ∩ `ViewScope.regionIds`). | B1 | Nothing to do; it flows in. |
| Region move locks the customer first. It cascades `region_id` to `tasks` and to `disputes`/promises if B1 stores it there. It publishes `updated(CUSTOMER, id, "REGION")` and `updated(INVOICE\|PAYMENT, id, "REGION")` for each moved child. | B1 | Not applicable. |
| `tasks.region_id` is created **nullable**. B1's region upgrade adds `tasks` to its backfill-and-enforce list (R10). If B1 has already landed, `TaskService` copies `customer.regionId` from the start. | B1 | The column is null until B1. |
| A promise create above the threshold returns normally with a pending marker, never by throwing. The automation handler reads `PaymentPromiseService.CreateOutcome{PromiseDto promise; Long pendingChangeId}`. Dispute approval is itself the checker step and is never gated (R7). Applying an approved change goes through the normal service methods and so publishes. | B2 | Until B2, `create` returns `PromiseDto`, and the handler records `result_type=PROMISE`. |
| `RuleEvaluationSource` for as-of (§9.4): mirror entity classes with the same attribute names, plus as-of variants of the subquery columns and scope. | B3 | Preview with `asOf` returns 400 "As-of preview is not available yet". |
| `InvoiceDates.today()`, `FilterPredicates.relative` and the `overdue` filters read `ViewScopeHolder.current().asOf()` or else `LocalDate.now(clock)` (P6/C-11). | foundation / Part B | Automation-only "today" values (due offsets, date placeholders, `Today`) use `LocalDate.now(clock.withZone(rule zone))` from the start. |

---

## 6. Data model

**General rules for this section:**
- New tables are created by `ddl-auto: update`.
- Every timestamp is set by services from the injected `Clock`, never by `@PrePersist`.
- Column names avoid H2 and PG reserved words: no `key`, `value`, `day`, `user`, `year`, `month`, `trigger` or `position`.
- JSON is stored as `@Lob @Column(columnDefinition = "TEXT")`, the `AuditLog` pattern.
- New enum columns (`@Enumerated(STRING)`) get their widen call registered in the table's schema-upgrade bean (§6.10), so later values widen automatically (R9).

### 6.1 `automation_rules` (`automation/rule/AutomationRule`)

| column | type | null | notes |
|---|---|---|---|
| id | BIGINT identity | no | |
| name | VARCHAR(120) | no | Unique among non-deleted rules, case-insensitive, checked in the service. |
| description | VARCHAR(500) | yes | |
| owner_user_id | BIGINT | no | No FK. Always the last saver (§11.3). |
| enabled | BOOLEAN | no | The user's intent. |
| active_since | TIMESTAMP | yes | Set to now whenever the rule becomes active (created enabled, enabled, resumed). Entity events with `occurred_at < active_since` are ignored, so there is no backlog firing. |
| suspended_reason | VARCHAR(30) enum `SuspendReason` | yes | `OWNER_INACTIVE`, `OWNER_NOT_STAFF`, `OWNER_LACKS_RIGHTS`, `INVALID_DEFINITION`, `RATE_LIMITED`. Non-null means suspended. |
| suspended_detail | VARCHAR(500) | yes | |
| suspended_at | TIMESTAMP | yes | |
| trigger_kind | VARCHAR(30) enum `TriggerKind` | no | |
| entity_type | VARCHAR(20) enum `TriggerEntity` | no | Scheduled rules also have one: the set they evaluate. |
| schedule_time | VARCHAR(5) | yes | `HH:mm`. Required for SCHEDULE_*. |
| schedule_day | VARCHAR(9) | yes | `DayOfWeek.name()`. Required for WEEKLY. |
| schedule_zone | VARCHAR(40) | no | Any `ZoneId.of`-parsable value (`+05:30`, `Asia/Kolkata`). Defaults to `app.automation.default-zone`. |
| next_run_at | TIMESTAMP | yes | Null unless the rule is an active schedule. |
| last_scheduled_for | TIMESTAMP | yes | |
| condition_json | TEXT | yes | Null means every record. |
| actions_json | TEXT | no | 1 to 5 actions. |
| max_records_per_run | INT | no | Default `app.automation.default-max-records-per-run` (1000), range 1..5000. |
| last_run_id, last_run_at, last_run_status | BIGINT, TIMESTAMP, VARCHAR(20) enum `RunStatus` | yes | Denormalised for the list. Written by JPQL, which does **not** bump the version. |
| created_by_user_id, updated_by_user_id | BIGINT | yes | |
| created_at, updated_at | TIMESTAMP | no | |
| deleted_at | TIMESTAMP | yes | Soft delete. |
| version | BIGINT `@Version` | yes | New table, so no `RowVersionUpgrade` entry. Stale PUT gives 409. **Only** user edits go through the entity. Ticker, worker and suspension updates are JPQL and do not bump it. |

Indexes: `idx_automation_rule_trigger (entity_type, trigger_kind, enabled)` and `idx_automation_rule_next_run (next_run_at)`.

### 6.2 `automation_rule_versions` (`AutomationRuleVersion`), for B3 and forensics

| column | type | notes |
|---|---|---|
| id | BIGINT identity | |
| rule_id | BIGINT not null | No FK. |
| rule_version | BIGINT | The rule's `@Version` after the change. |
| snapshot_json | TEXT not null | `RuleDto` without runtime fields (§17.3). |
| owner_user_id | BIGINT | |
| enabled | BOOLEAN not null | |
| suspended_reason | VARCHAR(30) | Stored as a string. |
| deleted | BOOLEAN not null | |
| saved_by_user_id | BIGINT | Null for a system suspension. |
| saved_at | TIMESTAMP not null | |

- Index `(rule_id, saved_at)`. There is no unique key.
- A row is written in the same transaction as every create, update, enable, disable, suspend, resume, delete and ownership change.
- The rule as of T is the row with the greatest `(saved_at, id)` where `saved_at < T`.

### 6.3 `automation_events`: the outbox (`automation/event/AutomationEvent`)

**It has no FK, no unique constraint, and no enum types.** Every text value is fitted before insert (P2/R5).

| column | type | null | notes |
|---|---|---|---|
| id | BIGINT identity | no | |
| kind | VARCHAR(20) | no | `CREATED`, `UPDATED`, `SCHEDULED`, `MANUAL` |
| entity_type | VARCHAR(20) | yes | `CUSTOMER`, `INVOICE`, `PAYMENT` (entity events) |
| entity_id | BIGINT | yes | |
| rule_id | BIGINT | yes | SCHEDULED and MANUAL |
| rule_version | BIGINT | yes | SCHEDULED and MANUAL |
| record_ids | VARCHAR(600) | yes | MANUAL "run on these records". Comma-joined, at most 50 ids. |
| changes | VARCHAR(300) | yes | Comma-joined labels (§8.4), fitted. |
| origin | VARCHAR(12) | yes | `USER`, `SYSTEM`, `AUTOMATION`, `APPROVAL` |
| caused_by_rule_id | BIGINT | yes | |
| depth | INT | no | 0 for human writes; origin depth + 1 inside automation. |
| actor_user_id | BIGINT | yes | `currentUser.idOrNull()` |
| scheduled_for | TIMESTAMP | yes | |
| occurred_at | TIMESTAMP | no | `clock.instant()` |
| status | VARCHAR(12) | no | `PENDING`, `PROCESSING`, `DONE`, `COALESCED`, `DEAD` |
| attempts | INT | no | Counted failures. |
| claims | INT | no | Fencing token, +1 on every claim. |
| claimed_by | VARCHAR(80) | yes | Node id. |
| lease_until | TIMESTAMP | yes | |
| next_attempt_at | TIMESTAMP | yes | |
| cursor_id | BIGINT | yes | Checkpoint for a sliced SCHEDULED or MANUAL run. |
| coalesced_into | BIGINT | yes | |
| last_error | VARCHAR(1000) | yes | Fitted. |
| processed_at, updated_at | TIMESTAMP | yes | |

Indexes: `idx_automation_event_due (status, next_attempt_at)`, `idx_automation_event_entity (entity_type, entity_id, status)` and `idx_automation_event_rule (rule_id, status)`.

### 6.4 `automation_runs` (`automation/run/AutomationRun`)

| column | type | null | notes |
|---|---|---|---|
| id | BIGINT identity | no | |
| rule_id | BIGINT | no | No FK. |
| event_id | BIGINT | no | No FK; events are purged. |
| rule_version | BIGINT | yes | |
| trigger_type | VARCHAR(20) | no | `CREATED`, `UPDATED`, `SCHEDULED`, `MANUAL` |
| entity_type, entity_id | VARCHAR(20), BIGINT | yes | Entity triggers only. |
| requested_by_user_id | BIGINT | yes | MANUAL |
| scheduled_for | TIMESTAMP | yes | |
| status | VARCHAR(20) enum `RunStatus` | no | `QUEUED`, `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `SKIPPED`, `SUPERSEDED` |
| matched, done, skipped, failed | INT | no | |
| truncated | BOOLEAN | no | |
| message | VARCHAR(1000) | yes | |
| created_at | TIMESTAMP | no | |
| started_at, finished_at | TIMESTAMP | yes | |

- Constraint **`uk_automation_run (rule_id, event_id)`**. Index `idx_automation_run_rule (rule_id, created_at)`.
- Entity events write a run **only when the record matches** (or on a rule-level error).
- SCHEDULED and MANUAL runs are pre-created as `QUEUED` in the same transaction as their event.

### 6.5 `automation_effects` (`AutomationEffect`): idempotency and the per-record log

| column | type | null | notes |
|---|---|---|---|
| id | BIGINT identity | no | |
| run_id, rule_id, event_id | BIGINT | no | No FKs. |
| action_key | VARCHAR(36) | no | Stable UUID of the action (§12.1). |
| action_type | VARCHAR(20) | no | |
| target_type, target_id | VARCHAR(20), BIGINT | no | |
| target_label | VARCHAR(200) | yes | Snapshot. |
| **dedupe_key** | VARCHAR(160) | **yes** | **Unique `uk_automation_effect_dedupe`.** Set only on a DONE row (§10.5). H2 and PG both allow many NULLs. |
| status | VARCHAR(12) enum `EffectStatus` | no | `DONE`, `SKIPPED`, `FAILED` |
| result_type | VARCHAR(20) | yes | `TASK`, `PROMISE`, `DISPUTE`, `EMAIL`, `PENDING_CHANGE` |
| result_id | BIGINT | yes | |
| message | VARCHAR(1000) | yes | |
| warnings | VARCHAR(1000) | yes | Joined with " \| ". |
| created_at | TIMESTAMP | no | |

- Indexes: `idx_automation_effect_run (run_id)`, `idx_automation_effect_rule (rule_id, created_at)`, `idx_automation_effect_target (target_type, target_id)`.
- Dedupe key: `r{ruleId}:{actionKey}:{TARGET_TYPE}:{targetId}` for ONCE_PER_RECORD, and the same plus `:e{eventId}` for EVERY_TIME. The maximum is about 120 characters.

### 6.6 `tasks` (`task/Task`)

| column | type | null | notes |
|---|---|---|---|
| id | BIGINT identity | no | |
| title | VARCHAR(200) | no | `FieldLimits.TASK_TITLE = 200` |
| description | VARCHAR(2000) | yes | `TASK_DESCRIPTION = 2000` |
| status | VARCHAR(20) enum `TaskStatus` | no | `OPEN`, `IN_PROGRESS`, `DONE`, `CANCELLED` |
| due_date | DATE | yes | |
| entity_type | VARCHAR(20) enum `TaskEntityType` | no | `CUSTOMER`, `INVOICE`, `PAYMENT` |
| entity_id | BIGINT | no | |
| entity_label | VARCHAR(200) | no | Snapshot ("Invoice INV-…", "Customer Acme", "Payment #12"). |
| entity_deleted | BOOLEAN | no | `@Builder.Default false`. |
| customer_id | BIGINT | no | Plain Long, as on Dispute. No FK. |
| region_id | BIGINT | yes | See §5. |
| origin | VARCHAR(12) | no | `USER` or `AUTOMATION` |
| rule_id, run_id | BIGINT | yes | |
| created_by_user_id | BIGINT | yes | The owner when origin is AUTOMATION. |
| completed_at, completed_by_user_id, cancelled_at | | yes | |
| created_at, updated_at | TIMESTAMP | no | |
| version | BIGINT `@Version` | yes | |

Indexes: `idx_task_entity (entity_type, entity_id)`, `idx_task_customer (customer_id)`, `idx_task_status_due (status, due_date)` and `idx_task_rule (rule_id, entity_type, entity_id, status)`.

### 6.7 `task_history` (`task/TaskHistory`, SCD2, written only by `TaskService`)

- `@IdClass(TaskHistory.Key.class)` with `@Id Long id` (the task id) and `@Id Instant validFrom`. This lets `TableSchemas.TASKS` run unchanged on it.
- Columns mirror `tasks`: title, description, status (enum, widened), due_date, entity_type (enum, widened), entity_id, entity_label, entity_deleted, customer_id, region_id, origin, rule_id, created_by_user_id, completed_at, cancelled_at, created_at.
- Plus `valid_from` (not null), `valid_to` (null for the current row) and `changed_by_user_id`.
- Index `(id, valid_to)`.
- Every task write closes the current row (`valid_to = now`) and inserts a new one in the same transaction.

### 6.8 `record_assignees` (`assignee/RecordAssignee`), shared by tasks, disputes and promises

| column | type | null | notes |
|---|---|---|---|
| id | BIGINT identity | no | |
| owner_type | VARCHAR(10) | no | `TASK`, `DISPUTE`, `PROMISE` (strings, no CHECK) |
| owner_id | BIGINT | no | No FK. |
| kind | VARCHAR(4) | no | `USER` or `ROLE` |
| user_id | BIGINT | yes | USER |
| role | VARCHAR(30) | yes | `EmailRole` name |
| role_level | VARCHAR(10) | yes | `CUSTOMER` or `RECORD` |
| sort_order | INT | no | |
| added_at | TIMESTAMP | no | |
| added_by_user_id | BIGINT | yes | |
| removed_at | TIMESTAMP | yes | Null while active. Rows are never deleted. |
| removed_by_user_id | BIGINT | yes | |

- Indexes: `idx_record_assignee_owner (owner_type, owner_id)` and `idx_record_assignee_user (user_id)`.
- Uniqueness among active rows is enforced in `Assignees.replace`, under the owner row's lock.

### 6.9 Changes to existing tables

| table | change | how |
|---|---|---|
| `disputes` | `internal BOOLEAN NOT NULL DEFAULT false` | `@Column(nullable = false) @ColumnDefault("false") @Builder.Default private boolean internal = false;` (C-5 scalar flag) |
| `disputes` | `origin VARCHAR(12) NULL`, `rule_id BIGINT NULL` | nullable columns |
| `disputes.target_type` | add `CUSTOMER` **as the last constant** | `DisputeSchemaUpgrade` calls `SchemaWiden.widen(connection, "disputes", "target_type", DisputeTargetType.class)` |
| `audit_logs` | `origin VARCHAR(12) NULL`, `rule_id BIGINT NULL` | nullable columns |

No changes are made to `emails`, `payment_promises`, `customers`, `invoices` or `payments` for Part A.

### 6.10 Schema-upgrade beans

All three follow the `(DataSource, EntityManagerFactory)` `InitializingBean` pattern, log a warning on `SQLException`, and are idempotent.
- `common/schema/SchemaWiden`: `public static void widen(Connection, String table, String column, Class<? extends Enum<?>> type)`, moved **verbatim** from `EmailSchemaUpgrade`. `EmailSchemaUpgrade` now calls it.
- `dispute/DisputeSchemaUpgrade`: `disputes.target_type`.
- `task/TaskSchemaUpgrade`: `tasks.status`, `tasks.entity_type`, `task_history.status`, `task_history.entity_type`.
- `automation/AutomationSchemaUpgrade`: `automation_rules.trigger_kind`, `automation_rules.entity_type`, `automation_rules.suspended_reason`, `automation_rules.last_run_status`, `automation_runs.status`, `automation_effects.status`.

---

## 7. Packages and classes

```
common/DatabaseErrors                  isUnavailable(Throwable), moved verbatim from MailServiceEventHandler.databaseUnavailable (which now delegates)
common/schema/SchemaWiden              (above)
common/origin/WriteOrigin              record(Kind kind, Long ruleId, Long runId, Long eventId, int depth); enum Kind {USER, SYSTEM, AUTOMATION, APPROVAL}
common/origin/WriteOrigins             static WriteOrigin current(); static <T> T callWith(WriteOrigin, Supplier<T>)   (ThreadLocal, restores previous)
common/security/RunAs                  @Component; <T> T call(AppUserDetails p, ViewScope s, WriteOrigin o, Supplier<T> work)
common/query/Condition, ConditionJson  (§9)
assignee/RecordAssignee, RecordAssigneeRepository, Assignees, AssignedTo, VisibilityProbe, AssigneeDtos, AssigneeController
task/Task, TaskStatus, TaskEntityType, TaskHistory, TaskRepository, TaskHistoryRepository, TaskTargets, TaskService,
     TaskController, TaskDtos, TaskCascade, TaskSchemaUpgrade
automation/AutomationSchemaUpgrade, AutomationProperties (@ConfigurationProperties("app.automation"), mutable setters for tests)
automation/rule/AutomationRule, AutomationRuleRepository, AutomationRuleVersion(+Repository), TriggerKind, TriggerEntity,
     SuspendReason, RuleDefinition, RuleValidator, RuleService, RuleController, RuleDtos, RequiredPrivileges
automation/action/RuleAction (sealed), CreateTaskAction, CreatePromiseAction, CreateDisputeAction, SendEmailAction, Repeat,
     DueSpec, AmountSpec, ActionHandler, ActionHandlers, CreateTaskHandler, CreatePromiseHandler, CreateDisputeHandler,
     SendEmailHandler, RecordContext, RecordContexts, ActionResult, ActionPreview, ActionSkippedException
automation/placeholder/PlaceholderCatalogue, PlaceholderValues, PlaceholderRenderer, Rendered
automation/eval/RuleEvaluator, RuleEvaluationSource, LiveRuleEvaluationSource, RuleEvaluationSources, RuleDefinitionException
automation/event/AutomationEvent, AutomationEventRepository, AutomationEvents, PendingEvents
automation/run/AutomationRun(+Repository), RunStatus, AutomationEffect(+Repository), EffectStatus, AutomationWorker,
     EventProcessor, RuleRunner, ActionExecutor, FailureClassifier, OwnerSession, OwnerUnavailableException,
     AutomationFaults, NoAutomationFaults, AutomationScheduler, RunController
automation/schedule/ScheduleCalculator, ScheduleTicker
```

### 7.1 Key signatures

```java
// common/security/RunAs
public <T> T call(AppUserDetails principal, ViewScope scope, WriteOrigin origin, Supplier<T> work) {
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext mine = SecurityContextHolder.createEmptyContext();
    mine.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    SecurityContextHolder.setContext(mine);
    try { return ViewScopeHolder.callWith(scope, () -> WriteOrigins.callWith(origin, work)); }
    finally { SecurityContextHolder.setContext(previous); }
}

// assignee/VisibilityProbe: "could user U see this record?", asked by running the normal scoped read as U
public boolean canSee(Long userId, EmailEntityType type, Long id);   // principalFor(userId) → RunAs(LIVE, SYSTEM) → targets.requireVisible(type, id); NotFound/AccessDenied → false; inactive or customer login → false

// automation/event/AutomationEvents
@Transactional(propagation = Propagation.MANDATORY) public void created(TriggerEntity type, Long id);
@Transactional(propagation = Propagation.MANDATORY) public void updated(TriggerEntity type, Long id, String change);

// automation/run/AutomationWorker (not @Transactional; throws IllegalStateException if a transaction is active)
public int drain(Instant now);
// automation/schedule/ScheduleTicker
public int tick(Instant now);

// automation/action/ActionHandler
public interface ActionHandler<A extends RuleAction> {
    Class<A> type();
    boolean locksCustomer();                                            // true for task/promise/dispute, false for email
    void validate(A action, TriggerEntity entity, FieldErrors errors);  // at save; paths like actions[2].title
    ActionPreview preview(A action, RecordContext rc);                  // no writes
    ActionResult perform(A action, RecordContext rc);                   // Propagation.MANDATORY transaction, as owner
}
public sealed interface ActionResult { record Done(String resultType, Long resultId, Long emailId, List<String> warnings) implements ActionResult {}
                                       record Skipped(String reason) implements ActionResult {} }
public record RecordContext(TriggerEntity type, Long id, Long customerId, Long regionId, Object entity,
                            EmailTargets.Target target, PlaceholderValues values, ZoneId zone, LocalDate today,
                            AutomationRule rule, Long runId, Long eventId) {}

// email/EmailService (new method; existing ones are unchanged)
@Transactional(propagation = Propagation.MANDATORY)
public Queued queue(EmailEntityType type, Long entityId, EmailToken from, boolean fallbackToOwner,
                    List<EmailToken> to, String subject, String body, String batchId, int maxRecipients);
public record Queued(Long emailId, List<String> warnings) {}
public static class NotQueued extends BadRequestException { public final boolean noRecipients; ... }
public PreviewDto previewRendered(EmailEntityType type, Long entityId, EmailToken from, boolean fallbackToOwner,
                                  List<EmailToken> to);   // read-only; used by preview

// dispute/DisputeService (new)
@Transactional(propagation = Propagation.MANDATORY)
public Dispute openInternal(InternalDisputeRequest req);   // (targetType, targetId, reason, proposedChangeJson, ruleId, List<EmailToken> assignees)
public static class AlreadyOpenException extends BadRequestException {}
@Transactional public DisputeDtos.DisputeDto setAssignees(Long disputeId, List<EmailToken> tokens);

// promise/PaymentPromiseService (new)
@Transactional public PromiseDtos.PromiseDto setAssignees(Long promiseId, List<EmailToken> tokens);

// task/TaskService
@Transactional public Task create(TaskDtos.CreateTaskRequest req);                        // UI
@Transactional(propagation = Propagation.MANDATORY) public Created createForAutomation(TaskDraft d);   // returns (task, warnings)
@Transactional public Task patch(Long id, TaskDtos.PatchTaskRequest req);
@Transactional public Task changeStatus(Long id, TaskStatus to, Long version);
@Transactional(readOnly = true) public Task get(Long id);                                  // forTasks scope, otherwise 404
```

---

## 8. A1: publishing (the outbox writer)

### 8.1 Buffer

- `AutomationEvents.created` and `updated` look through `TransactionSynchronizationManager.getSynchronizations()` for a `PendingEvents` instance and register one if none is found.
- Synchronizations are suspended with their transaction under `REQUIRES_NEW`. So each `BulkExecutor` per-record transaction gets its own buffer (`common/bulk/BulkExecutor.java:22-25`).
- `PendingEvents` holds a `LinkedHashMap<Key(TriggerEntity type, Long id), Draft(String kind, LinkedHashSet<String> changes)>`:
  - `id == null || id <= 0` is ignored (S7).
  - CREATED absorbs UPDATED in both orders, and change labels are unioned.
- `beforeCommit(boolean readOnly)`:
  - If `readOnly`, it logs WARN "event published in a read-only transaction; dropped" and returns.
  - If the buffer is empty, it returns.
  - Otherwise it calls `repository.saveAll(...)`. Each row has `occurred_at = clock.instant()`, `actor_user_id = currentUser.idOrNull()`, `origin`, `caused_by_rule_id` and `depth` from `WriteOrigins.current()` (default: USER when a principal exists, else SYSTEM), `status=PENDING`, `attempts=0`, `claims=0`, and `changes` fitted to 300.
- `afterCompletion`: clears the map.
- A rolled-back or rollback-only transaction never reaches `beforeCommit`, so it writes no events. Examples: `recomputeAll(apply=false)` and a bulk row that loses at commit.

### 8.2 The save path reads no automation table

`AutomationEvents` injects only the repository (insert), `Clock` and `CurrentUser`.

### 8.3 Call-site inventory (each call goes after the method's last save or audit)

| # | Method | Call |
|---|---|---|
| C1 | `CustomerService.create` | `created(CUSTOMER, c.getId())` before `return` (~:247) |
| C2 | `CustomerService.update` | `updated(CUSTOMER, id, "DETAILS")` before `return` (~:296) |
| C3 | `PocService.add`, `remove`, `setPrimary` | `updated(CUSTOMER, customerId, "POC_SEATS")` after their audits |
| C4 | `PaymentService.applyTo` (private helper) | For each invoice saved: `updated(INVOICE, inv.getId(), "PAYMENT")`. If `remaining > 0`: `updated(CUSTOMER, customer.getId(), "CREDIT")`. **Never the payment** (its id may be null). |
| C5 | `PaymentService.reverseAllocations` | For each invoice: `updated(INVOICE, …, "PAYMENT")`. If credit was reversed: `updated(CUSTOMER, …, "CREDIT")`. |
| C6 | `CreditLedger.applyTo` (after the early return) | `updated(CUSTOMER, …, "CREDIT")`, `updated(INVOICE, invoice.getId(), "PAYMENT")`, and for each payment drawn `updated(PAYMENT, p.getId(), "ALLOCATION")` |
| C7 | `CreditLedger.refund` (when `amount > 0`) | `updated(CUSTOMER, "CREDIT")`, `updated(INVOICE, "PAYMENT")`, and for each payment whose `creditApplied` changed `updated(PAYMENT, "ALLOCATION")` |
| I1 | `InvoiceService.create` | `created(INVOICE, saved.getId())` before `return` (~:98). This absorbs C6's UPDATED for the same invoice. |
| I2 | `InvoiceService.update` (also reached from `reassignSalesPoc` and bulk) | `updated(INVOICE, id, "DETAILS")` |
| I3 | `InvoiceService.cancel` | `updated(INVOICE, id, "STATUS")` |
| I4 | `InvoiceService.cancelWithRefund` | `updated(INVOICE, id, "STATUS")` |
| I5 | `InvoiceService.replaceItems` | `updated(INVOICE, id, "ITEMS")` |
| I6 | `DisputeService.applyInvoiceChange`, `update_notes` branch | `updated(INVOICE, invoiceId, "DETAILS")` |
| P1 | `PaymentService.record` | `created(PAYMENT, saved.getId())` before `return` |
| P2 | `PaymentService.update` (also reached from `reassignCollectionPoc` and bulk) | `updated(PAYMENT, id, "DETAILS")` |
| P3 | `PaymentService.voidPayment` | `updated(PAYMENT, paymentId, "STATUS")` |
| P4 | `PaymentService.updateAmount` | `updated(PAYMENT, paymentId, "AMOUNT")` |
| P5 | `DisputeService.applyPaymentChange`, `update_meta` branch | `updated(PAYMENT, paymentId, "DETAILS")` |

Not published, by design:
- deletes;
- promise, dispute, task, document and email writes (these are not A1 trigger entities);
- `*SchemaUpgrade` backfills;
- test fixtures that save through repositories;
- **B2 pending changes** (R8). Applying an approved change goes through these same methods, so it does publish.

### 8.4 Semantics

- "Updated" means the record's own row, or any column in its `TableSchemas` entry, changed. That includes side effects: allocation, credit, dispute application and seat changes.
- A change in a *derived cross-record* figure (a customer's `outstanding` when one of its invoices changes) does **not** make the customer UPDATED. Neither does "became overdue", which is a clock change. Rules that need either use a daily or weekly schedule; the editor explains this in its help text.
- Change labels are for diagnostics only; rules do not filter on them.
  - CUSTOMER: DETAILS, POC_SEATS, CREDIT, REGION
  - INVOICE: DETAILS, STATUS, ITEMS, PAYMENT, REGION
  - PAYMENT: DETAILS, STATUS, AMOUNT, ALLOCATION, REGION
- An invoice that is created and then modified in the same transaction produces one CREATED event. ENTITY_UPDATED rules do not fire for that edit; ENTITY_CREATED_OR_UPDATED rules do fire (once).

---

## 9. A2: conditions and evaluation

### 9.1 Wire format (stored verbatim in `condition_json`)

```json
{"op":"AND","children":[
  {"filter":"status:in:UNPAID,PARTIALLY_PAID"},
  {"op":"OR","children":[{"filter":"overdue:eq:true"},{"filter":"balance:gt:100000"}]}]}
```

- Null or absent means every record.
- Limits: depth ≤ 4 (the root group is depth 1), total leaves ≤ 30, children per group between 1 and 20. An empty group is rejected.
- Leaves use the list-page grammar, `FilterSpec.parse`.

### 9.2 `common/query/Condition`

```java
public sealed interface Condition permits Condition.Leaf, Condition.Group {
    enum Junction { AND, OR }
    record Leaf(FilterSpec spec) implements Condition {}
    record Group(Junction op, List<Condition> children) implements Condition {}
    void validate(TableSchema schema, String path, Map<String,String> errors);   // requireFilterable per leaf, limits, errors under path
    PredicateFactory toPredicate(TableSchema schema);                            // leaves → FilterPredicates.build(spec, def, root, q, cb); groups → cb.and / cb.or
}
```

- `ConditionJson.read(String)` and `write(Condition)` use Jackson. A parse error gives field error `condition`.
- `Condition` lives in `common.query` because `FilterPredicates` is package-private.

### 9.3 Validation at save

1. Parse, then check the limits.
2. Run `requireFilterable` against the **full staff** schema `TableSchemas.X`, not `visibleTo`.
3. Force coercion with a dry run:
   ```java
   queryExecutor.count(entityClass, schema, new TableQuery(0, 1, "id", true, List.of()),
                       List.of(cond.toPredicate(schema), (r, q, cb) -> cb.disjunction()))
   ```
   A `BadRequestException` is mapped to the failing leaf path.
4. Errors are `InvalidFieldsException` with paths such as `condition.children[1].children[0].filter`.

### 9.4 `RuleEvaluationSource` (the as-of seam)

```java
public interface RuleEvaluationSource {
    Class<?> entityClass(TriggerEntity t);          // Customer.class / Invoice.class / Payment.class (live)
    TableSchema schema(TriggerEntity t);            // TableSchemas.CUSTOMERS / INVOICES / PAYMENTS
    List<PredicateFactory> scope(TriggerEntity t);  // live: scopeResolver.forCustomers()/forInvoices()/forPayments().predicates() for the CURRENT principal
    boolean historical();
}
```

`RuleEvaluationSources.current()` returns the live source when `ViewScopeHolder.current().asOf()` is null. Otherwise it returns B3's `AsOfRuleEvaluationSource`, or throws `BadRequestException("As-of preview is not available yet")` until B3 lands.

### 9.5 `RuleEvaluator` (every call runs under the current principal: the owner in the consumer, the caller in preview)

```java
@Transactional(readOnly = true) boolean matches(RuleDefinition r, Long id);
  // count(entityClass, schema, new TableQuery(0,1,"id",true,List.of(FilterSpec.parse("id:eq:"+id))), scope + [cond]) > 0
@Transactional(readOnly = true) List<Long> matchingPage(RuleDefinition r, long afterId, int pageSize, List<Long> restrictTo);
  // ids(entityClass, schema, new TableQuery(0,pageSize,"id",true,List.of(FilterSpec.parse("id:gt:"+afterId))),
  //     scope + [cond] + (restrictTo==null ? [] : [(root,q,cb)->root.get("id").in(restrictTo)]), pageSize)
@Transactional(readOnly = true) long count(RuleDefinition r);
@Transactional(readOnly = true) List<Long> sample(RuleDefinition r, int n);   // sort id desc, limit n
```

- Any `BadRequestException` thrown while *building* predicates is rethrown as `RuleDefinitionException` (rule-level; §10.6). Examples: "Unknown column", coercion failures after a schema change.
- The unpaged `run` is never used (R18).
- A deleted or out-of-scope record never matches.

---

## 10. A5: durable execution

### 10.1 Threads and configuration

- `AutomationScheduler` holds two jobs, each wrapped in `try { … } catch (RuntimeException e) { log.warn }` (the `PromiseSweepScheduler` shape):
  - `@Scheduled(fixedDelayString="${app.automation.poll-interval-ms:2000}", initialDelayString=same) poll()` calls `worker.drain(clock.instant())` when `consumerEnabled`.
  - `@Scheduled(fixedDelayString="${app.automation.tick-interval-ms:30000}", initialDelayString=same) tick()` calls `ticker.tick(clock.instant())`.
- A `@Scheduled` fixed-delay method never overlaps itself, so each node runs one drain at a time.
- There is no after-commit nudge.
- `@PreDestroy` sets a `stopping` flag, which is checked between events and between pages.
- The node id is `app.automation.node-id`, or `hostname + "-" + 8 random hex`, fitted to 80.

### 10.2 `AutomationWorker.drain(now)`

Each numbered step that touches the database is its own short `TransactionTemplate`. The repository methods are `@Modifying(clearAutomatically = true, flushAutomatically = true)` JPQL.

1. **Guard.** If `TransactionSynchronizationManager.isActualTransactionActive()`, throw `IllegalStateException("Automation must be drained outside a transaction")`.
2. **Recover expired leases.** For each id in `findExpiredLeaseIds(now, page 50)`:
   `update … set status='PENDING', claimedBy=null, leaseUntil=null, updatedAt=:now where id=:id and status='PROCESSING' and leaseUntil < :now`
   This counts no attempt.
3. **Find due work.** `findDueIds(now, page batchSize)`:
   `status='PENDING' and (nextAttemptAt is null or nextAttemptAt <= :now) order by id`
4. **Claim**, for each id:
   `update … set status='PROCESSING', claims=claims+1, claimedBy=:node, leaseUntil=:now+lease, updatedAt=:now where id=:id and status='PENDING' and (nextAttemptAt is null or nextAttemptAt <= :now)`
   - 0 rows: skip.
   - Otherwise read `claims` in the same transaction; that is the **token**.
   - If `token > maxClaims (20)`, finish DEAD "Processing never finished after 20 claims".
   - **Coalesce** in the same transaction, only when the claimed event is `UPDATED`. Select pending rows with the same `entity_type`, `entity_id`, `kind='UPDATED'`, `claims=0`, `attempts=0` and `id <> :id`. Union their `changes` into this row (fitted), then set `status='COALESCED', coalescedInto=:id, processedAt=:now`.
5. **Process**: `outcome = processor.process(id, token, now)` returns `DONE | RETRY(error) | YIELD(cursorId) | LOST`. On an exception:
   - If `FailureClassifier.isInfrastructure(e)` (§10.6): make a best-effort fenced release (`status=PENDING, claimedBy=null, leaseUntil=null, nextAttemptAt=now+30s where id and claims=:token and status='PROCESSING'`) and **stop the drain**.
   - Otherwise the outcome is `RETRY(e)`.
   - An `Error` propagates; lease expiry recovers the event.
6. **Finish**, fenced with `where id=:id and status='PROCESSING' and claims=:token`:
   - `DONE`: `status=DONE, processedAt=now, leaseUntil=null`.
   - `RETRY`: `attempts=attempts+1`.
     - If `attempts+1 ≥ maxAttempts (5)`: `status=DEAD`. Then, in the same transaction, set this event's non-terminal runs to `FAILED` with "Gave up after 5 attempts: <error>", update the rule's `last_run_*`, and send one `AUTOMATION_RUN_FAILED` notification to the owner (link `/automation/{ruleId}?tab=runs`, fitted to 300).
     - Otherwise `status=PENDING, nextAttemptAt = now + [30s, 2m, 10m, 1h][attempts]`, `lastError` fitted.
   - `YIELD(c)`: `status=PENDING, cursorId=c, nextAttemptAt=now, leaseUntil=null`. Attempts are unchanged.
   - `LOST`: nothing.
   - A fenced finish that updates 0 rows is logged as WARN "lost the lease" and nothing more is written.

### 10.3 `EventProcessor.process(eventId, token, now)`

- **Entity events (CREATED or UPDATED).**
  - Load candidates with `ruleRepository.findEntityCandidates(entityType, kinds, occurredAt)`:
    `enabled = true and suspendedReason is null and deletedAt is null and entityType=:t and triggerKind in :kinds and activeSince <= :occurredAt`.
    - CREATED uses `[ENTITY_CREATED, ENTITY_CREATED_OR_UPDATED]`.
    - UPDATED uses `[ENTITY_UPDATED, ENTITY_CREATED_OR_UPDATED]`.
  - Drop the rule whose id equals `caused_by_rule_id`. If `depth > 3`, skip every rule and log.
  - Call `ruleRunner.runForRecord` for each rule, isolating failures per rule. The event outcome is the worst of the rule outcomes, in the order `DONE < RETRY`.
- **SCHEDULED and MANUAL events.** `ruleRunner.runForSet(rule, ev, token, now)` can return YIELD or LOST.

### 10.4 `RuleRunner`

**`runForRecord(rule, ev)`:**
1. If a run for `(rule, ev)` exists and is terminal, return DONE.
2. Run the circuit breaker (§10.7).
3. Run `ownerSession.call(rule, ctx, …)`:
   - `matched = evaluator.matches(rule, ev.entityId)`.
   - If there is no match: if a non-terminal run exists (a retry), set it to `SKIPPED` "No longer matches". Return DONE.
   - Otherwise `run = runs.ensure(rule, ev)`: insert with status RUNNING; a unique-key violation means re-read.
   - `result = actionExecutor.applyAll(rule, run, ev, type, id)`.
   - **After** `applyAll` returns, and outside any transaction, call `emailDispatcher.dispatchAll(result.emailIds())`.
   - In a transaction, add the run's counters and set its status to SUCCEEDED, PARTIAL or FAILED (FAILED when every action failed permanently), or keep RUNNING if a transient failure remains. Update the rule's `last_run_*` with JPQL.
   - Return RETRY if there is a transient failure, otherwise DONE.
4. `OwnerUnavailableException(reason)`: suspend the rule (§11.2), set the run to SKIPPED (created if needed) with the reason, return DONE.
5. `RuleDefinitionException`: suspend with `INVALID_DEFINITION`, set the run to FAILED, return DONE.

**`runForSet(rule, ev, token, now)`:**
1. If the run (pre-created QUEUED) is terminal, return DONE.
2. If the rule is deleted, disabled or suspended, set the run to `SKIPPED` "Rule is not active" and return DONE.
3. If `ev.cursorId != null && rule.version != run.ruleVersion`, set the run to `SUPERSEDED` and return DONE.
   - For a run that has not started, just record the current version on it.
4. Set the run to RUNNING. `after = ev.cursorId ?? 0`, `sliceStart = clock.instant()`.
5. Loop over pages. **Each page is its own `ownerSession.call`**, so the owner is re-checked per page:
   - `page = evaluator.matchingPage(rule, after, props.pageSize, ev.recordIds)`. An empty page means the run is complete.
   - For each id: if `run.matched ≥ rule.maxRecordsPerRun`, set `truncated=true` and stop. Otherwise `run.matched++` and `actionExecutor.applyAll(...)`.
   - Dispatch this page's emails outside any transaction.
   - Persist the counters. Heartbeat: `update … set leaseUntil=:now+lease where id and claims=:token and status='PROCESSING'`. 0 rows means return LOST.
   - Re-read the rule. If it is deleted, disabled or suspended, set the run to `PARTIAL`/`SKIPPED` "Stopped: rule is not active" and return DONE. If its version changed, set `SUPERSEDED` and return DONE.
   - `after` = the last id of the page. If `clock.instant() - sliceStart > sliceBudget`, save `cursor_id = after` and return `YIELD(after)`.
6. Finalise the run as in `runForRecord`.

### 10.5 `ActionExecutor.applyAll(rule, run, ev, type, id)`: one transaction per (target, action)

For each action, in order:
```
if effects.existsByRunIdAndActionKeyAndTargetTypeAndTargetId(run.id, a.key, type, id): continue   // already attempted in this run
key = dedupeKey(rule, a, type, id, ev)
try {
  ActionResult r = actionTx.execute(s -> {                         // TransactionTemplate, PROPAGATION_REQUIRES_NEW
      Long customerId = recordContexts.customerIdOf(type, id);     // NotFound → ActionSkippedException("The record no longer exists")
      if (handler.locksCustomer()) customerRepository.findByIdForUpdate(customerId).orElseThrow(...);   // PPD-01
      if (effects.existsByDedupeKey(key)) return AlreadyDone;      // committed twin, or an earlier ONCE_PER_RECORD
      faults.beforeAction(rule.id, a.key, type, id);               // test hook, no-op in production
      RecordContext rc = recordContexts.load(type, id, rule, run, ev);   // EmailTargets.load(scoped, as owner) + entity + placeholders
      ActionResult res = handler.perform(a, rc);
      effects.save(effect(run, ev, a, type, id, rc.target().label(), res,
                          res instanceof Done ? key : null));      // unique key is the backstop
      return res;
  });
  faults.afterActionCommitted(rule.id, a.key, type, id);
  tally(r)                                                          // DONE → done++ (+emailId); Skipped → skipped++; AlreadyDone → skipped++ (no row)
} catch (Exception e) {
  switch (classifier.classify(e)) {
    DUPLICATE  -> skipped++                                         // a twin committed the same key; this transaction rolled back
    SKIP       -> newTx: effects.save(SKIPPED, key=null, message); skipped++
    PERMANENT  -> newTx: effects.save(FAILED, key=null, message); failed++
    DEFINITION -> throw new RuleDefinitionException(e)
    TRANSIENT  -> transient++; run.message = fit(e)                 // no row; the event retries this action later
    INFRA      -> throw e                                           // the worker stops the drain
  }
}
```

**The effect row is written in the same transaction as the Task, Promise, Dispute or Email it records**, so an action cannot happen twice for the same key (P2).

### 10.6 `FailureClassifier` (applied in this order)

| class | condition |
|---|---|
| DUPLICATE | A `DataIntegrityViolationException` whose cause-chain messages contain `UK_AUTOMATION_EFFECT_DEDUPE` (case-insensitive) |
| SKIP | `ActionSkippedException`, `DisputeService.AlreadyOpenException`, `NotFoundException` |
| TRANSIENT | Any `ConcurrencyFailureException` in the chain (optimistic, pessimistic, `CannotAcquireLock`, H2 lock timeout), `QueryTimeoutException` |
| INFRA | `DatabaseErrors.isUnavailable(e)`, only once the concurrency case has been ruled out (S8) |
| DEFINITION | `RuleDefinitionException`, `PlaceholderRenderer.UnknownPlaceholderException` |
| PERMANENT | `BadRequestException` (including `InvalidFieldsException`, `EmailService.NotQueued` without `noRecipients`, `BulkExecutor.IneligibleException`), `AccessDeniedException`, `AuthenticationException` (logged at ERROR: a missing principal is a bug, C-8), any other `DataIntegrityViolationException`, `IllegalArgumentException` |
| TRANSIENT (default) | Any other `RuntimeException` |

`EmailService.NotQueued` with `noRecipients = true` is SKIP.

### 10.7 Circuit breaker

- Before `runForRecord`, and before each page of `runForSet`, compute `effects.countByRuleIdAndStatusAndCreatedAtAfter(rule.id, DONE, now - 1h)` plus the DONE effects done in this drain.
- If that is `≥ app.automation.max-actions-per-rule-per-hour` (1000), suspend the rule with `RATE_LIMITED` and set the run to `PARTIAL`/`SKIPPED` "Hourly action limit reached".

### 10.8 Schedules

**`ScheduleCalculator`** is pure (no Spring):
```java
static Instant nextAfter(TriggerKind k, LocalTime time, DayOfWeek day, ZoneId zone, Instant after);        // first slot strictly after
static Instant latestAtOrBefore(TriggerKind k, LocalTime time, DayOfWeek day, ZoneId zone, Instant t);
```
It iterates by date in the zone. `ZonedDateTime.of(date, time, zone)` moves a DST gap forward, and an overlap takes the earlier offset.

**`ScheduleTicker.tick(now)`:**
```
due = ruleRepository.findDueSchedules(now, page 100)   // enabled, suspendedReason null, deletedAt null, triggerKind in SCHEDULE_*, nextRunAt <= now; returns (id, nextRunAt, version)
for each: tx {
   slot = latestAtOrBefore(spec, now); next = nextAfter(spec, now)
   n = update AutomationRule r set r.nextRunAt=:next, r.lastScheduledFor=:slot where r.id=:id and r.nextRunAt=:expected   // JPQL, no version bump
   if n == 1: insert SCHEDULED event(rule_id, rule_version, scheduled_for=slot, occurred_at=now) + run(QUEUED, trigger SCHEDULED, scheduled_for=slot)
}
purge: delete events where status in ('DONE','COALESCED') and processedAt < now - eventRetentionDays, in chunks of 1000 ids (DEAD rows are kept)
```

- Missed slots collapse into **one** catch-up run, because `next` is computed from `now`. The run records `scheduled_for`; the UI shows "late by …".
- `next_run_at` is recomputed on create, update, enable and resume (`nextAfter(now)`), and cleared on disable, suspend and delete.
- There is no `ApplicationReadyEvent` hook.

### 10.9 Run now

`POST /api/automation/rules/{id}/run`, body `{"recordIds":[77,78]}` (optional, at most 50, all positive):
- Gate: AUTOMATION_MANAGE, and the caller is the owner or holds AUTOMATION_ADMIN.
- The rule must be enabled, not suspended and not deleted; otherwise 400.
- `ViewScope.requireLive()`; `asOf` gives 400 "As-of views are read-only".
- **Idempotent:** if a MANUAL event for the rule is `PENDING` or `PROCESSING`, return 200 with its run.
- Otherwise, in one transaction, insert `MANUAL` event (`rule_id`, `rule_version`, `actor_user_id = caller`, `record_ids`) and run (`QUEUED`, `requested_by_user_id = caller`), audit `RULE_RUN_REQUESTED`, and return **202** `{"runId":88,"eventId":9031,"status":"QUEUED"}`.
- It executes **as the owner** and evaluates the rule's condition over the whole set, or over `recordIds`, for every trigger kind. ONCE_PER_RECORD keeps a backfill safe.

### 10.10 Preview (read-only)

`POST /api/automation/rules/preview` (`@AsOfReadable`), and `GET /api/automation/rules/{id}/preview?recordId=`:
- Gate: AUTOMATION_VIEW.
- It runs as the **caller**, under the request's `ViewScope`, inside `@Transactional(readOnly = true)` with `setRollbackOnly()`. It never writes.
- Body: `{"rule": RuleRequest, "ruleId": 12?, "recordId": 77?, "sampleSize": 10}` (sample size ≤ 20).
- It validates exactly as a save does (400 `fieldErrors`).
- It computes `matchCount = evaluator.count` and `sample = evaluator.sample(n)`.
- For one record (`recordId`, or else the first sample) it reports `matches`, and for each action `handler.preview`.
- When `ruleId` is given, `alreadyDone` per sample means a DONE effect exists with that action's ONCE_PER_RECORD key.

### 10.11 Test knobs

- `application-test.yml`: `poll-interval-ms` and `tick-interval-ms` are 3600000. Tests call `drain` and `tick` directly and **call `SecurityContextHolder.clearContext()` first** (R2).
- `AutomationProperties` setters (page size, slice budget, max attempts and so on) are changed in tests and restored in `finally`.

---

## 11. Run as the owner (P3), ownership and suspension

### 11.1 `OwnerSession.call(rule, ctx, work)`

1. `principal = appUserDetailsService.principalFor(rule.ownerUserId)`, read-only; with B1 it fetches region grants eagerly.
2. Missing or `!active` throws `OwnerUnavailableException(OWNER_INACTIVE)`. `customerId != null` throws `OWNER_NOT_STAFF`.
3. `RequiredPrivileges.of(rule)`: the entity's VIEW privilege (CUSTOMER_VIEW, INVOICE_VIEW or PAYMENT_VIEW) plus, per action type:

   | action | privilege |
   |---|---|
   | CREATE_TASK | TASK_MANAGE |
   | CREATE_PROMISE | PROMISE_MANAGE |
   | CREATE_DISPUTE | DISPUTE_MANAGE |
   | SEND_EMAIL | EMAIL_SEND |

   These are checked on `principal.getAuthorities()`, the union across regions under B1. A missing one throws `OWNER_LACKS_RIGHTS`, and the detail names the privilege.
4. `runAs.call(principal, ViewScope.LIVE, WriteOrigin(AUTOMATION, ruleId, runId, eventId, ev.depth + 1), work)`. The previous context is restored in `finally`.

**Per record, as defence in depth,** each handler checks `RegionRights.has(<action privilege>, rc.regionId())`. A failure is a **permanent effect failure for that record only** and does not suspend the rule. With B1, an owner may be able to view a region but not manage it.

**`AuditService.record`** stamps `rule_id` and `origin` from `WriteOrigins.current()` when the kind is not USER. None of the ~30 existing callers change. The actor stays the owner (X6).

### 11.2 Suspension

- `ruleRepository.suspend(id, reason, detail, now)` is a JPQL update `where id=:id and suspendedReason is null`, so it applies once.
- On 1 row, in the same transaction:
  - clear `nextRunAt`;
  - write a rule version row;
  - audit `RULE_SUSPENDED` (entity `RULE`, userId null);
  - notify `AUTOMATION_RULE_SUSPENDED` to the owner if active, and to every active staff user holding AUTOMATION_ADMIN. Add `UserRepository.findActiveStaffHolding(String privilege)`; do not use `findByRoleName`.
- Events arriving while a rule is suspended are skipped, because candidates exclude it.
- `POST /rules/{id}/resume`: re-run `RuleValidator` and the owner guard, clear the suspension, set `active_since = now`, recompute `next_run_at`, and audit `RULE_RESUMED`.
- **Proactive hook.** When a user is deactivated, call `ruleService.suspendOwnedBy(userId, OWNER_INACTIVE)` (`@Transactional`). The three places are: `UserController.update` with `active=false`, bulk `DEACTIVATE`, and delete-falls-back-to-deactivate. The owner guard remains the real check.

### 11.3 Ownership

- Creating a rule makes the caller its owner.
- `PUT` by the owner (holding AUTOMATION_MANAGE) keeps the owner.
- `PUT` by anyone else requires AUTOMATION_ADMIN **and** `"confirmOwnershipChange": true`. Otherwise it is 400 with `fieldErrors.confirmOwnershipChange = "Saving makes you the owner; this rule will then run with your access"`. On save, `owner_user_id = caller`, with audit `RULE_OWNERSHIP_TAKEN` and `RULE_UPDATED`.
- There is no "assign to another user" endpoint.
- Enable, disable, delete, run and resume are allowed to the owner (with AUTOMATION_MANAGE) or to AUTOMATION_ADMIN.

---

## 12. A3: actions

### 12.1 Action JSON (inside `actions_json`)

- Every action has `key` (a UUID, client-generated; the server assigns one if missing), `type` and `repeat`.
- `repeat` is `ONCE_PER_RECORD` (the default, for every type) or `EVERY_TIME`:
  - ONCE_PER_RECORD: done at most once per record for that rule and action. A failure or skip is retried on later events.
  - EVERY_TIME: done for every event, where a coalesced event counts as one.

```json
[
 {"key":"5b0c7f0e-…","type":"CREATE_TASK","repeat":"EVERY_TIME",
  "title":"Chase {{Invoice.Number}} ({{Invoice.Balance}})",
  "description":"{{Customer.Name}} is {{Invoice.DaysOverdue}} days late",
  "due":{"base":"TODAY","offsetDays":2},
  "assignees":[{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},{"type":"USER","userId":7}],
  "onlyIfNoOpenTask":true},
 {"key":"c3d1…","type":"CREATE_PROMISE","repeat":"ONCE_PER_RECORD",
  "amount":{"source":"FIELD","field":"Invoice.Balance"},
  "promisedDate":{"base":"TODAY","offsetDays":7},
  "collectionPoc":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},
  "assignees":[{"type":"ROLE","role":"CUSTOMER_SUCCESS_POC","level":"CUSTOMER"}],
  "notes":"Auto-created for {{Invoice.Number}}","linkRecord":true,"onlyIfNoOpenPromise":true},
 {"key":"e7f2…","type":"CREATE_DISPUTE","repeat":"ONCE_PER_RECORD",
  "reason":"Review {{Invoice.Number}}: overdue with balance {{Invoice.Balance}}",
  "proposedChange":"none",
  "assignees":[{"type":"ROLE","role":"SALES_POC","level":"RECORD"}]},
 {"key":"91aa…","type":"SEND_EMAIL","repeat":"EVERY_TIME",
  "from":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},"fallbackToOwner":true,
  "to":[{"type":"CUSTOMER"}],
  "subject":"Invoice {{Invoice.Number}} is overdue",
  "body":"Dear {{Customer.Name}},\n{{Invoice.Balance}} has been due since {{Invoice.DueDate}}.\n{{Customer.CollectionPoc|Our collections team}}"}
]
```

Typical recipes that the editor's help text shows:
- "Invoice becomes fully paid → thank-you email": ENTITY_UPDATED, condition `status:eq:FULLY_PAID`, ONCE_PER_RECORD.
- "Every day, overdue → chase task until done": SCHEDULE_DAILY, `overdue:eq:true`, CREATE_TASK with EVERY_TIME and `onlyIfNoOpenTask`.
- "Weekly reminder while overdue": SCHEDULE_WEEKLY, SEND_EMAIL, EVERY_TIME.

### 12.2 Assignee model (one model for tasks, disputes and promises)

**Wire format:** the existing `EmailDtos.EmailToken`. Stored as `record_assignees` rows.

**Validation, `Assignees.validate(EmailEntityType context, List<EmailToken> tokens, Set<RoleRef> allowedRoles)`:**
- Token type is USER or ROLE. CUSTOMER gives "Assignees are people or roles".
- USER must pass `EmailAddressing.activeInternalUser` (make it `public static requireActiveInternalUser(UserRepository, Long)`).
- ROLE must pass `offeredRole` against `allowedRoles` (make it `public static requireOfferedRole(...)`, including the `defaultLevel` fallback).
- At most 10 tokens, deduplicated by `RoleRef.token()` or `USER:id`.

**Roles allowed per owner:**

| owner | allowed roles |
|---|---|
| Task | `EmailTargets.rolesOffered(task entity type)` |
| Dispute | CUSTOMER rules: customer-level roles only. INVOICE rules: customer-level + `SALES_POC@RECORD`. PAYMENT rules: customer-level + `COLLECTION_POC@RECORD`. For a manual `PUT` on an existing dispute, derive the same list from the dispute's target type. |
| Promise co-assignees | `rolesOffered(PROMISE)`: the customer-level roles, plus `COLLECTION_POC@RECORD`, which is the promise's own POC |

**Live resolution, `Assignees.resolve(ownerType, ownerId, Target target)`:**
- USER is that user, if active.
- ROLE is **all** active holders, `target.holders(ref)`.
- The target comes from `EmailTargets.load` (for a task, its entity; for a dispute, DISPUTE; for a promise, PROMISE).

**Sender semantics** (only for the Promise `collectionPoc` field, the email From and placeholders): `target.sender(ref)`, the primary if active, otherwise the oldest active seat (X7).

**Visibility ("nothing escapes permissions").**
- When a USER token is added: `VisibilityProbe.canSee(userId, entityType, entityId)`. Automation drops a failing token with warning "X cannot see this record, so was not assigned". The UI returns 400 `fieldErrors.assignees`.
- Notifications go only to resolved people who pass the probe, at most 20 per change.
- "Assigned to me" lists are ANDed with the viewer's own scope, so invisible records never show.

**`AssignedTo.predicate(String ownerType, Root root, CriteriaQuery q, CriteriaBuilder cb, Long userId, Instant asOf)`:**
```
EXISTS (select 1 from RecordAssignee a where a.ownerType=:ownerType and a.ownerId = root.id
        and (asOf == null ? a.removedAt is null : a.addedAt < :asOf and (a.removedAt is null or a.removedAt >= :asOf))
        and ( (a.kind='USER' and a.userId=:u)
           or (a.kind='ROLE' and a.roleLevel='CUSTOMER' and exists (select 1 from CustomerPoc s
                 where s.customer.id = <root customer id> and s.user.id=:u
                   and s.pocType = <COLLECTION if a.role='COLLECTION_POC', SUCCESS if a.role='CUSTOMER_SUCCESS_POC'>))
           or <RECORD branches> ))
```

RECORD branches by owner type:
- **TASK:** `a.role='SALES_POC'` and `root.entityType='INVOICE'` and exists an Invoice with `id=root.entityId` and `salesPoc.id=:u`; or `a.role='COLLECTION_POC'` and `root.entityType='PAYMENT'` and exists a Payment with `collectionPoc.id=:u`.
- **DISPUTE:** the same, using `root.targetType` and `root.targetId`.
- **PROMISE:** `a.role='COLLECTION_POC'` and `root.collectionPoc.id=:u`.

The root's customer id is `root.customerId` for Task and Dispute, and `root.customer.id` for Promise. The role-to-`PocType` mapping is written as `cb.or` of two conjunctions, not a Java ternary.

### 12.3 Handlers

Each handler first checks `RegionRights.has(privilege, rc.regionId())`.

| Handler | Privilege | Behaviour |
|---|---|---|
| **CreateTask** | TASK_MANAGE | If `onlyIfNoOpenTask`: when `taskRepository.existsByRuleIdAndEntityTypeAndEntityIdAndStatusIn(rule.id, T, id, [OPEN, IN_PROGRESS])`, return `Skipped("An open task from this rule already exists")`. Otherwise call `taskService.createForAutomation(TaskDraft(T, id, render(title, 200), render(description, 2000), due, assignees, ruleId, runId))`. `due` = base (TODAY in the rule zone, or INVOICE_DUE_DATE on INVOICE rules) + offset; `null` means no due date; a result before today becomes today. Returns `Done(TASK, taskId, warnings)`. |
| **CreatePromise** | PROMISE_MANAGE | **Amount:** `FIXED` (a positive value with cents) or `FIELD`, one of `Customer.Outstanding`, `Customer.OverdueAmount`, `Invoice.Balance`, `Invoice.Total`, `Payment.Amount`, according to entity. `Money.scale`; `≤ 0` returns `Skipped("Nothing owed")`. **Promised date:** base (TODAY or INVOICE_DUE_DATE) + 0..365 days, at least today. **Collection POC:** null means the customer default (`resolveCollectionPoc(null, …)`, primary first). A USER token goes to `pocService.requireAssignable(u, COLLECTION)`. `COLLECTION_POC@CUSTOMER` is `target.sender(...)` then `requireAssignable`. `COLLECTION_POC@RECORD` is only allowed on PAYMENT rules (the payment's POC). **Guard `onlyIfNoOpenPromise`:** on INVOICE rules, skip if an OPEN or PARTIALLY_KEPT promise is linked to the invoice; on CUSTOMER and PAYMENT rules, skip if such a promise exists on the customer. **Call:** `promiseService.create(new CreatePromiseRequest(customerId, amount, date, pocId, render(notes, 1000), linkRecord && T==INVOICE ? [id] : []))`, then `promiseService.setAssignees(promiseId, assignees)` in the same transaction. B2 pending gives `Done(PENDING_CHANGE, pendingId)`. "No Collection POC" gives PERMANENT. |
| **CreateDispute** | DISPUTE_MANAGE | `disputeService.openInternal(new InternalDisputeRequest(targetType = T, id, render(reason, 2000), proposal, ruleId, assignees))`. **Proposals:** `"none"` (any target) is stored as `{"action":"none"}`; `"cancel"` (INVOICE) as `{"action":"cancel"}`; `"void"` (PAYMENT) as `{"action":"void"}`. `AlreadyOpenException` gives SKIP. |
| **SendEmail** | EMAIL_SEND | Render the subject (`EmailText.oneLine`, then fit to 500) and the body (fit to 20000), recording a warning when anything is shortened. Then `emailService.queue(T.emailType, id, from, fallbackToOwner, to, subject, body, "rule-" + ruleId + "-run-" + runId, props.maxEmailRecipients)`. Returns `Done(EMAIL, emailId, emailId, warnings)`. |

**`EmailService.queue` steps:**
1. `target = targets.load(type, id)`, which is scoped and runs as the owner.
2. `Content.check(subject, body, to)`.
3. If `from` is a ROLE and `target.sender(role)` is empty: when `fallbackToOwner`, set `from = null` (the owner); otherwise throw `NotQueued("Nobody holds <label> on <record>, so it cannot be the sender", false)`.
4. `resolution = addressing.resolve(addressing.plan(target, from, to), target)`. A problem throws `NotQueued(problem, resolution.recipients().isEmpty())`.
5. More than `maxRecipients` TO entries throws `NotQueued`.
6. `id = save(target, content, resolution, batchId)`. `sentByUserId` is the owner; `fromUserId` is always an internal user (V5).
7. Return `Queued(id, warnings(resolution.from()))`.

`queue` **never dispatches**. The worker calls `dispatchAll` after commit.

**Delivery outcomes and threading:**
- Gmail not connected, or transport `none`: the effect is DONE with the warning. The email row ends NOT_SENT and can be retried from the email UI.
- Replies thread back through the ordinary OUTBOUND row and its `CopyRef` (R15).
- No attachments exist, so documents are never reachable.

### 12.4 Disputes: CUSTOMER target, internal, assignees (edits to `dispute/*`)

**Enum and customer path:**
- `DisputeTargetType { INVOICE, PAYMENT, CUSTOMER }`, with CUSTOMER last (S1).
- `open` (the customer-login path): after the caller check, `if (req.targetType() == CUSTOMER) throw new BadRequestException("A dispute is raised on an invoice or a payment")`. Existing behaviour is otherwise unchanged.

**Switch edits:**
- `ensureTargetBelongsToCustomer`: add `case CUSTOMER -> { if (!id.equals(customerId)) throw new AccessDeniedException("Not allowed"); }`.
- `snapshotTarget`: `case CUSTOMER -> customerRepository.findById(id).map(CustomerService::snapshotOf).orElse(null)`. If the existing snapshot factory is not public static, add `public static CustomerAuditSnapshot snapshotOf(Customer c)` next to the record and use it in both places.
- `describeTarget`: `case CUSTOMER -> customerRepository.findById(id).map(c -> new Target(null, null, "Customer " + c.getName())).orElse(new Target(null, null, "Customer #" + id))`.
- `applyChange`:
  - a blank change on a CUSTOMER target means `none`;
  - otherwise read `action`;
  - if `"none"`, return (acknowledge; valid for every target);
  - add `case CUSTOMER -> throw new BadRequestException("Only 'none' is allowed on a customer dispute")` for any other action.
  - INVOICE and PAYMENT behaviour is unchanged: a blank change is still 400 "No change specified for approval".

**Approval and assignment:**
- `approve`: if `d.isInternal()`, the effective action is not `none`, and `resolver.id == d.getOpenedByUserId()`, throw 400 "A change your own rule proposed must be approved by someone else". Deny has no such rule. The audit of a CUSTOMER target is recorded under entity `CUSTOMER`.
- `openInternal`:
  1. Require a staff caller and `RegionRights.has(DISPUTE_MANAGE, region)`.
  2. Load the target through the scoped `customerService`/`invoiceService`/`paymentService.get` to get `customerId`.
  3. Guard one PENDING dispute per (customer, target type, target id) with `AlreadyOpenException`.
  4. Save with `internal=true`, `origin="AUTOMATION"`, `ruleId`, `openedByUserId = caller`.
  5. `Assignees.replace(DISPUTE, id, tokens)`.
  6. Audit `DISPUTE_OPENED`.
  7. Notify `DISPUTE_ASSIGNED` to the resolved, visible assignees. If nobody resolves, fall back to the existing `notifyAdmins`.
- `setAssignees` (UI): DISPUTE_MANAGE, staff. Audit `DISPUTE_ASSIGNEES_CHANGED`. Notify new assignees.

**Hiding internal disputes from customer logins (the fix for a permission escape):**
- `ScopeResolver.forDisputes` customer branch adds `cb.isFalse(root.get("internal"))`.
- `DisputeService.get` throws `NotFoundException("Dispute not found")` for an internal dispute when the caller is a customer.
- `DisputeService.list()` customer branch filters it out.
- `AuditTimelineService`: for customer viewers, rows whose `disputeId` refers to an internal dispute keep the change but have `reason` and `disputeId` set to null.

**Other dispute edits:**
- `EmailTargets.disputeOwnFields` becomes a `switch` expression: INVOICE maps to `SALES_POC`, PAYMENT to `COLLECTION_POC`, **CUSTOMER to `Map.of()`** (S2).
- `EmailTargets.suggestion` and the `describe` label handle CUSTOMER.
- `DisputeDtos.DisputeDto` gains `boolean internal, String origin, Long ruleId, List<AssigneeDto> assignees, boolean canResolve`:
  - `assignees` is filled only by `GET /api/disputes/{id}` and is null in lists.
  - `canResolve = !isCustomer && has(DISPUTE_MANAGE) && status==PENDING`.
- `TableSchemas.DISPUTES` adds `internal` (BOOLEAN, `.pocRestricted()`) and `assigneeUserId` (REFERENCE `staffUser`, custom `AssignedTo` filter over EQ, IN, IS_EMPTY, IS_NOT_EMPTY; `.pocRestricted().notSortable()`).

### 12.5 Promises: co-assignees

- `PaymentPromiseService.setAssignees` (PROMISE_MANAGE via the controller, scoped `get`). It audits `PROMISE_ASSIGNEES_CHANGED` and notifies new assignees with `PROMISE_ASSIGNED`.
- `PromiseDto` gains `List<AssigneeDto> assignees`, filled only by `get`/detail and null in lists.
- `collectionPoc`, book scope and the broken-promise notice are unchanged.
- `TableSchemas.PROMISES` adds `assigneeUserId`, the same custom column.

---

## 13. A4: placeholders

### 13.1 Grammar

- Regex: `\{\{\s*([A-Za-z]+(?:\.[A-Za-z]+)?)\s*(?:\|([^{}|]{0,100}))?\s*\}\}`.
- Rendering is a single pass (`Matcher.appendReplacement` with `quoteReplacement`), so values are never re-scanned.
- Everything is plain text.
- Placeholders are allowed in: email subject and body, task title and description, promise notes, dispute reason.
- Unknown keys, or keys not offered for the entity level, are rejected at save: 400 on `actions[i].<field>` listing the keys.

### 13.2 Catalogue (`GET /api/automation/placeholders?entityType=`)

| Level (where offered) | Keys → source |
|---|---|
| `Customer.*` (all three entity types) | `Name`, `Email`, `Phone`, `Address`, `PaymentTerm` (label, falling back to the default term as `CustomerService` does), `CreditBalance` (money), `Outstanding` and `OverdueAmount` (`invoiceRepository.sumOutstandingByCustomer` / `sumOverdueByCustomer([id], today)`, computed only when used; region-blind until B1 fixes R13), `CollectionPoc`, `CollectionPocEmail`, `SuccessPoc`, `SuccessPocEmail` (`target.sender(RoleRef.customer(...))`), `Region` (B1; empty until then) |
| `Invoice.*` (INVOICE) | `Number`, `Date` (`invoiceDate` in the rule zone), `DueDate`, `PaymentTerm`, `Total`, `Paid`, `Balance`, `Status` (`EmailText.humanize`), `Overdue` (Yes/No), `DaysOverdue`, `Notes`, `SalesPoc`, `SalesPocEmail` (`target.sender(RoleRef.record(SALES_POC))`) |
| `Payment.*` (PAYMENT) | `Number` ("#id"), `Date` (`paidAt`), `Amount`, `Unallocated` (`creditApplied`), `Method`, `Notes`, `Status`, `Invoices` (allocated invoice numbers, fitted to 500), `CollectionPoc`, `CollectionPocEmail` |
| Common | `Today` (rule zone), `Rule.Name` |

Response:
```json
{"entityType":"INVOICE","groups":[
 {"level":"CUSTOMER","label":"Customer","items":[{"token":"{{Customer.Name}}","label":"Name","kind":"TEXT"}]},
 {"level":"RECORD","label":"Invoice","items":[{"token":"{{Invoice.Balance}}","label":"Balance","kind":"MONEY"}]},
 {"level":"COMMON","label":"General","items":[{"token":"{{Today}}","label":"Today","kind":"DATE"}]}]}
```

### 13.3 Values and formatting

- Formatting uses `Money.format`, ISO dates, `EmailText.humanize`, `nameOf`, `fit` and `oneLine`. `EmailText` becomes a `public` class with public methods.
- POC keys render only when the owner (or the preview caller) passes `scopeResolver.canSeePoc()`. Otherwise they render "" and add a warning, matching DTO masking.
- Null or blank values render the fallback, or "" with warning "`<key>` is empty".
- Over-length results are fitted with "…", and a warning is recorded (limits in §12.3).

---

## 14. A6: tasks

### 14.1 Rules

- **Transitions:**
  - OPEN → IN_PROGRESS, DONE or CANCELLED
  - IN_PROGRESS → OPEN, DONE or CANCELLED
  - DONE → OPEN
  - CANCELLED → OPEN (TASK_MANAGE only)
  - The same status gives 400.
- DONE sets `completed_at` and `completed_by`. Reopen clears them. CANCELLED sets `cancelled_at`.
- Every write carries `version`; a mismatch is 409. Every write adds a `task_history` row and audits under `TASK`: `TASK_CREATED`, `TASK_UPDATED`, `TASK_STATUS_CHANGED`, `TASK_ASSIGNEES_CHANGED`, `TASK_CANCELLED_WITH_RECORD`.
- **Create (UI):**
  1. TASK_MANAGE.
  2. `TaskTargets.load(type, id)` through the scoped `get`, giving label and `customerId`. Out of scope is 404.
  3. `customerRepository.findByIdForUpdate(customerId)`.
  4. Copy `regionId`, with a B1 `RegionRights.has(TASK_MANAGE, region)` check.
  5. Validate the assignees (USER tokens are probed, 400 on failure).
  6. Save the task, assignees and history row, then audit and notify.
- **Rights:**
  - TASK_MANAGE: all edits and all transitions.
  - An **effective assignee** (live, §12.2) holding TASK_VIEW: OPEN ↔ IN_PROGRESS, → DONE, and DONE → OPEN. Otherwise 403.
- **Notifications:**
  - `TASK_ASSIGNED` ("Task assigned: <title>", link `/tasks/{id}`) goes to people newly resolved, visible and active. The actor is excluded when origin is USER; for AUTOMATION the owner is included when resolved.
  - `TASK_DONE` goes to the creator when origin is USER and the completer is someone else.

### 14.2 Scope (`ScopeResolver.forTasks()`)

- A customer login sees nothing (`cb.disjunction()`).
- Staff see an OR over the three types. The branch for type X is included only when `currentUser.has(X_VIEW)`, and is:
  `root.entityType = X AND root.entityId IN (subquery of X ids under forX().predicates())`
  This is the `DashboardService.idsInScope` pattern. If `forX()` is empty, the branch is just `root.entityType = X`.
- B1's region predicate flows in through `forX()`, and B1 also adds `root.regionId ∈ allowed(TASK_VIEW) ∩ requested`.
- An out-of-scope task is 404.

### 14.3 `TableSchemas.TASKS` (entity `tasks`, default sort `dueDate,asc`)

| column | type | notes |
|---|---|---|
| id | NUMBER | |
| title | TEXT | |
| status | ENUM (`TaskStatus`) | |
| dueDate | DATE (`LocalDate`) | |
| overdue | BOOLEAN | Custom filter: status in (OPEN, IN_PROGRESS) and `dueDate < today`, where today = `ViewScope.asOf` or `LocalDate.now(clock)` UTC. |
| entityType | ENUM (`TaskEntityType`) | |
| entityId | NUMBER | |
| entityLabel | TEXT | |
| customerId | REFERENCE `customer` | Path `root.get("customerId")`. |
| assigneeUserId | REFERENCE `staffUser` | Custom `AssignedTo`, not sortable, path `root.get("id")`. |
| origin | ENUM | Values `USER`, `AUTOMATION`. |
| ruleId | NUMBER | |
| regionId | REFERENCE `region` | Present once B1 adds `region`. |
| createdAt, completedAt, updatedAt | DATE | |

Registration:
- `buildIndex` gets `TASKS`, `AUTOMATION_RULES` and `AUTOMATION_RUNS`.
- `TableSchemaController.VIEW_PRIVILEGE` becomes `Map.ofEntries(...)` with `tasks → TASK_VIEW`, `automation-rules → AUTOMATION_VIEW`, `automation-runs → AUTOMATION_VIEW` (S4).

### 14.4 As-of

- When `ViewScope.asOf` is set, the list, summary, count and export run the same schema over `TaskHistory.class`, adding `validFrom < T AND (validTo IS NULL OR validTo >= T)`.
- `forTasks` works on history roots (same attribute names). `AssignedTo` filters rows by `added_at` and `removed_at` for T.
- ROLE tokens as of T resolve against **current** seats until B3 provides seat history. The UI states this under the list.
- Detail as of T (`GET /api/tasks/{id}?asOf=`) returns the history row, with `canEdit=false`.

### 14.5 Cascade and references

- `TaskCascade.onCustomerDeleted(customerId)` is called in `CustomerService.delete` next to `emailCascade.onCustomerDeleted`. It sets `entityDeleted=true`, sets OPEN and IN_PROGRESS tasks to CANCELLED, and writes history and audit.
- Invoices and payments are never deleted.
- `UserController.pocReferenceCount` gains:
  - `recordAssigneeRepository.countByKindAndUserIdAndRemovedAtIsNull("USER", id)`
  - `ruleRepository.countByOwnerUserIdAndDeletedAtIsNull(id)`
  A referenced user is therefore deactivated, not deleted.

---

## 15. Privileges and seeding

| Constant | Scope under B1 | Seeded to |
|---|---|---|
| `TASK_VIEW` | regional | ADMIN (through ALL), CASHIER, VIEWER, SALES_POC, CUSTOMER_SUCCESS_POC, COLLECTION_POC |
| `TASK_MANAGE` | regional | ADMIN, CASHIER, the three POC roles |
| `AUTOMATION_VIEW` | global | ADMIN, CASHIER, the three POC roles |
| `AUTOMATION_MANAGE` | global | ADMIN, CASHIER, the three POC roles |
| `AUTOMATION_ADMIN` | global | ADMIN only |

- Add each constant to `Privileges.java` **and to `Privileges.ALL`** (R11).
- Add them to the `DataSeeder` lists: CASHIER and VIEWER through `upsertRole`; the POC roles through `createRoleIfAbsent`, which adds privileges created on that boot.
- Mirror them in `shared/models/privileges.dart`.
- Every task, automation and assignee controller refuses customer logins with `AccessDeniedException`.
- Automation-created disputes need DISPUTE_MANAGE, which only ADMIN holds by default. This conservative mapping is deliberate.

---

## 16. API (JSON conventions unchanged)

- Creates return 200, except run-now (202).
- Out of scope is 404, a missing privilege is 403, field errors are 400 with `fieldErrors`, and a stale version is 409.
- Every write endpoint below calls `ViewScopeHolder.current().requireLive()`.

### 16.1 Rules (`RuleController`, `/api/automation`)

| Method and path | Gate | Body → response |
|---|---|---|
| GET `/rules` | AUTOMATION_VIEW | `PageResponse<RuleSummary>`, schema `automation-rules`: id, name, enabled, suspendedReason (ENUM, supports `isEmpty`), triggerKind, entityType, ownerUserId (REFERENCE `staffUser`), lastRunStatus, lastRunAt, nextRunAt, createdAt, updatedAt. Scope `deletedAt is null`. With `asOf`: built from versions in memory; only `name:contains`, `enabled:eq`, `entityType:in`, `triggerKind:in` and sort by `name` are supported, anything else is 400. |
| GET `/rules/summary` | AUTOMATION_VIEW | `{"active":4,"disabled":1,"suspended":1,"failedRuns24h":2}` |
| GET `/rules/{id}` | AUTOMATION_VIEW | `RuleDto` (as of a date: from versions) |
| POST `/rules` | AUTOMATION_MANAGE | `RuleRequest` → `RuleDto` |
| PUT `/rules/{id}` | AUTOMATION_MANAGE (and owner, or AUTOMATION_ADMIN with confirmation) | `RuleRequest` → `RuleDto` |
| POST `/rules/{id}/enable`, `/disable`, `/resume` | owner or AUTOMATION_ADMIN | → `RuleDto` |
| DELETE `/rules/{id}` | owner or AUTOMATION_ADMIN | 204 (soft delete) |
| POST `/rules/{id}/run` | owner or AUTOMATION_ADMIN | `{"recordIds"?}` → 202 or 200 `{"runId","eventId","status"}` |
| POST `/rules/preview` (`@AsOfReadable`), GET `/rules/{id}/preview` | AUTOMATION_VIEW | → `PreviewDto` |
| GET `/placeholders?entityType=` | AUTOMATION_VIEW or TASK_MANAGE | catalogue (§13.2) |
| GET `/rules/{id}/runs` | AUTOMATION_VIEW | `PageResponse<RunDto>`, schema `automation-runs`: id, status, triggerType, entityType, entityId, matched, done, skipped, failed, createdAt, finishedAt. With `asOf`: `createdAt < T`. |
| GET `/runs/{id}` | AUTOMATION_VIEW | `RunDto` |
| GET `/runs/{id}/effects?page&size` | AUTOMATION_VIEW | `PageResponse<EffectDto>`. Targets outside the **viewer's** scope come back `{"hidden":true}` with no label or link; checked with one `ids(...)` per type using an `id IN (...)` predicate plus `forX()`. |
| POST `/runs/{id}/retry` | owner or AUTOMATION_ADMIN | Only for FAILED runs whose event is DEAD: the event becomes `PENDING, attempts=0, claims=0, nextAttemptAt=now`, the run becomes QUEUED; 202. Otherwise 400. |
| GET `/health` | AUTOMATION_ADMIN | `{"consumerEnabled":true,"pending":17,"processing":1,"retrying":2,"dead":0,"oldestPendingSeconds":3,"deadEvents":[{"id":9,"kind":"UPDATED","entityType":"INVOICE","entityId":7,"ruleId":null,"attempts":5,"lastError":"…","updatedAt":"…"}]}` (at most 50 dead events) |
| POST `/events/{id}/requeue` | AUTOMATION_ADMIN | DEAD → PENDING (attempts and claims reset); 202 |

`RuleRequest`:
```json
{"name":"Chase overdue invoices","description":null,"enabled":true,
 "trigger":{"kind":"SCHEDULE_DAILY","entityType":"INVOICE","time":"09:00","day":null,"zone":"+05:30"},
 "condition":{"op":"AND","children":[{"filter":"overdue:eq:true"}]},
 "actions":[ … §12.1 … ],
 "maxRecordsPerRun":1000,"version":3,"confirmOwnershipChange":false}
```

`RuleDto`:
```json
{"id":12,"name":"Chase overdue invoices","description":null,"enabled":true,"version":3,
 "status":"ACTIVE","suspendedReason":null,"suspendedDetail":null,
 "owner":{"id":5,"name":"Cara Collections","active":true},
 "trigger":{"kind":"SCHEDULE_DAILY","entityType":"INVOICE","time":"09:00","day":null,"zone":"+05:30"},
 "condition":{…},"actions":[…],"maxRecordsPerRun":1000,
 "nextRunAt":"2026-09-27T03:30:00Z",
 "lastRun":{"id":88,"status":"SUCCEEDED","finishedAt":"…","matched":14,"done":14,"skipped":0,"failed":0},
 "warnings":["Cara Collections has not connected Gmail, so emails sent from them will be saved in the app but not sent."],
 "listLink":"/invoices?f=overdue:eq:true",
 "canEdit":true,"editTakesOwnership":false,"canRun":true,
 "createdAt":"…","updatedAt":"…"}
```

- `status` is ACTIVE, DISABLED or SUSPENDED.
- `listLink` is present only for AND-only trees.
- `warnings` cover the Gmail status of the owner and of USER senders, and "owner inactive".

`PreviewDto`:
```json
{"entityType":"INVOICE","asOf":null,"evaluatedAs":"CALLER","matchCount":132,
 "sample":[{"id":77,"label":"Invoice INV-20260901-0007","link":"/invoices/77","alreadyDone":["5b0c…"]}],
 "record":{"id":77,"label":"Invoice INV-20260901-0007","matches":true,"actions":[
   {"key":"5b0c…","type":"CREATE_TASK","rendered":{"title":"Chase INV-20260901-0007 (₹12,000.00)","dueDate":"2026-09-28"},
    "assignees":[{"token":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},"label":"Collection POC (customer)",
                  "people":[{"userId":5,"name":"Cara Collections"}]}],"problems":[],"warnings":[]},
   {"key":"91aa…","type":"SEND_EMAIL","email":{…existing EmailDtos.PreviewDto…},
    "rendered":{"subject":"Invoice INV-… is overdue","body":"…"},"problems":[],
    "warnings":["Email delivery is not configured, so this email will be saved in the app but not sent."]}]}}
```

`RunDto`:
```json
{"id":88,"ruleId":12,"ruleVersion":3,"eventId":9031,"triggerType":"SCHEDULED","status":"PARTIAL",
 "scheduledFor":"…","lateBySeconds":0,"entity":null,"requestedBy":null,
 "matched":14,"done":12,"skipped":1,"failed":1,"truncated":false,"message":null,
 "createdAt":"…","startedAt":"…","finishedAt":"…"}
```

`EffectDto`:
```json
{"id":901,"actionKey":"5b0c…","actionType":"CREATE_TASK","target":{"type":"INVOICE","id":77,"label":"Invoice INV-…","link":"/invoices/77","hidden":false},
 "status":"DONE","result":{"type":"TASK","id":41,"link":"/tasks/41"},"message":null,"warnings":[],"createdAt":"…"}
```

### 16.2 Tasks (`TaskController`, `/api/tasks`)

| Method and path | Gate | Notes |
|---|---|---|
| GET `/api/tasks` | TASK_VIEW | Schema `tasks`. Context params `entityType`, `entityId` and `customerId` are merged into chips. `customerId` also matches tasks on that customer's invoices and payments, because it is a column. |
| GET `/api/tasks/summary` | TASK_VIEW | `{"count","open","inProgress","overdue","dueToday"}` |
| GET `/api/tasks/mine`, `/mine/summary` | TASK_VIEW | Scope = `forTasks` ∧ `AssignedTo(me)`. **`lockedFilters: []`.** |
| GET `/api/tasks/mine/count` | TASK_VIEW | `{"count": n}`: OPEN and IN_PROGRESS tasks assigned to me (the nav badge) |
| GET `/api/tasks/count?entityType&entityId` | TASK_VIEW | `{"count","open"}` (the tab badge) |
| GET `/api/tasks/{id}` | TASK_VIEW | `TaskDto` |
| POST `/api/tasks` | TASK_MANAGE | `{"entityType":"INVOICE","entityId":77,"title":"…","description":null,"dueDate":"2026-09-28","assignees":[EmailToken]}` |
| PATCH `/api/tasks/{id}` | TASK_MANAGE | `{"title"?,"description"?,"dueDate"?,"clearDueDate"?,"assignees"?,"version":3}` |
| POST `/api/tasks/{id}/status` | TASK_MANAGE or effective assignee | `{"status":"DONE","version":3}` |
| POST `/api/tasks/bulk` | TASK_MANAGE | Actions `START`, `MARK_DONE`, `REOPEN`, `CANCEL` through `BulkExecutor.eligibility` |
| POST `/api/tasks/export` | EXPORT_DATA and TASK_VIEW | `Csv.of` |

`TaskDto`:
```json
{"id":41,"title":"Chase INV-…","description":null,"status":"OPEN","dueDate":"2026-09-28","overdue":false,
 "entityType":"INVOICE","entityId":77,"entityLabel":"Invoice INV-…","entityDeleted":false,"customerId":5,"customerName":"Acme Ltd",
 "regionId":null,
 "assignees":[{"token":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},"label":"Collection POC (customer)",
               "people":[{"userId":5,"name":"Cara Collections","primary":true}],"unresolved":false}],
 "assignedToMe":true,"origin":"AUTOMATION","rule":{"id":12,"name":"Chase overdue invoices"},
 "createdBy":{"id":5,"name":"Cara Collections"},"createdAt":"…","updatedAt":"…","completedAt":null,"version":0,
 "canEdit":true,"canChangeStatus":true}
```

`canEdit` and `canChangeStatus` are false whenever `asOf` is set.

### 16.3 Assignees, disputes and promises

| Method and path | Gate |
|---|---|
| GET `/api/assignees/context?entityType=&entityId=` | any of TASK_MANAGE, AUTOMATION_MANAGE, DISPUTE_MANAGE, PROMISE_MANAGE; staff only. Returns `emailService.context(entityType, entityId, null, null)` unchanged (the same `ContextDto`). Without `entityId` it returns the type-level roles. |
| GET `/api/assignees/people?q=` | any of TASK_VIEW, AUTOMATION_VIEW, DISPUTE_MANAGE, PROMISE_MANAGE; staff only. Delegates to `emailService.people(q)`. |
| PUT `/api/disputes/{id}/assignees` | DISPUTE_MANAGE: `{"assignees":[…]}` → `DisputeDto` |
| PUT `/api/promises/{id}/assignees` | PROMISE_MANAGE: `{"assignees":[…]}` → `PromiseDto` |

`AssigneeDto` = `{token, label, people:[{userId, name, primary}], unresolved}`.

### 16.4 Audit

- `AuditController.SUPPORTED` gains `TASK` (read through `taskService.get`) and `RULE` (AUTOMATION_VIEW, read through `ruleService.get`).
- `VIEW_PRIVILEGE` gains `TASK → TASK_VIEW` and `RULE → AUTOMATION_VIEW`, converted to `Map.ofEntries` if it would exceed 10 entries.
- The audit entry DTO gains a nullable `ruleId`.

---

## 17. Validation details (`RuleValidator`: create, update, resume, preview)

1. `name`: 1..120 characters, trimmed, unique among non-deleted rules (case-insensitive).
2. `trigger`:
   - kind and entityType are required;
   - SCHEDULE_* needs `time` matching `^([01]\d|2[0-3]):[0-5]\d$`;
   - WEEKLY needs `day` (a `DayOfWeek`);
   - `zone` must parse with `ZoneId.of`;
   - ENTITY_* ignores time, day and zone except as the display zone.
3. `condition`: §9.3.
4. `actions`:
   - 1..5, unique keys (keys ≤ 36 characters);
   - `repeat` is required, default ONCE_PER_RECORD;
   - each handler's `validate`: templates parse, keys are in the catalogue for the entity, fallback ≤ 100 characters with no braces;
   - token lists pass §12.2 with the allowed roles;
   - task due offset −30..365;
   - promise amount source and field allowed for the entity, FIXED value positive with cents, date base allowed for the entity, offset 0..365;
   - dispute proposal allowed for the entity (`cancel` only on INVOICE, `void` only on PAYMENT, `none` everywhere);
   - email: `to` has 1..10 tokens, `CUSTOMER` allowed; subject template non-blank; `from` is USER, ROLE or null.
5. `maxRecordsPerRun`: 1..5000.
6. Owner capability, at save and resume: the **saver** must hold `RequiredPrivileges.of(rule)`. Otherwise 400 `fieldErrors.actions` "You need TASK_MANAGE to add a Create task action". This means saving never produces a rule that immediately suspends.

---

## 18. Flutter (keep `flutter analyze` at zero issues)

### 18.1 New files

**`features/assignees/`**
- `assignee_providers.dart`:
  - `assigneeContextProvider = FutureProvider.autoDispose.family<EmailContext, ({EmailEntityType type, int? entityId})>`, which calls `/api/assignees/context` and parses with `EmailContext.fromJson` from `email_models.dart`;
  - `searchAssignablePeople(Dio, String q)`, which calls `/api/assignees/people` and returns `List<EmailPerson>`.
- `recipient_chips_field.dart`: `RecipientChipsField({required EmailContext ctx, required List<EmailToken> value, required ValueChanged<List<EmailToken>> onChanged, bool allowCustomer = false, bool single = false, Set<String>? roleFilter, bool perRecordLabels = false, String label = 'Assignees', required Future<List<EmailPerson>> Function(String) searchPeople})`. Chip icons: person, badge or business. Tooltips list holders. Rows are "Person…" plus one row per `ctx.roleGroups`. It is modelled on `_toField` but written fresh, and **`send_email_dialog.dart` is not modified**.
- `people_search_dialog.dart`: a generic debounced (250 ms) search dialog, adapted from `_SearchDialog`.

**`features/tasks/`**
- `task_models.dart`: `enum TaskStatus { OPEN, IN_PROGRESS, DONE, CANCELLED }` with a parse fallback to OPEN; `TaskItem.fromJson` parses everything nullable except `id`, `title` and `status`.
- `task_providers.dart`: `taskDetailProvider`, `taskCountProvider(({String entityType, int entityId}))`, and `myTasksCountProvider = StreamProvider.autoDispose<int>` using `pollUnreadCount(ref, dio, '/api/tasks/mine/count')` (returns `Stream.empty()` when signed out).
- `task_actions.dart`:
  - `canViewTasksProvider` and `canManageTasksProvider`;
  - `tasksDetailTab(WidgetRef ref, {required String entityType, required int entityId, bool customerScope = false})`. Its presence depends **only** on `canViewTasksProvider` (G5); the count is the badge.
- `tasks_tab.dart`: a `DataTableScaffold` on `/api/tasks` with `extraParams` `entityType`/`entityId`, or `customerId` when `customerScope`. It has a "New task" action.
- `tasks_screen.dart`: `TasksScreen({required TableQuery query, bool mine = false})`.
  - `/tasks` uses `/api/tasks`, with quick filters "Assigned to me" (`assigneeUserId:eq:<me>`), "Overdue" and "Open".
  - `/my-tasks` uses `/api/tasks/mine`.
  - Row actions: Start, Done, Reopen, Cancel. Bulk actions are the same. There is a mobile card.
  - `onBulkDone` invalidates `myTasksCountProvider`.
- `task_form_dialog.dart`: `showTaskDialog(context, {required String entityType, required int entityId, TaskItem? existing})`, with fields title, description, due date and assignees (`RecipientChipsField` fed by `assigneeContextProvider`).
- `task_detail_screen.dart`: `/tasks/:id`, a `DetailScaffold` with tabs Details and History (`AuditHistoryPanel(entityType: 'TASK')`). Edits are disabled when as-of is set.

**`features/automation/`**
- `automation_models.dart`: `Rule`, `RuleTrigger`, `ConditionNode` (`ConditionGroup{op, children}` / `ConditionLeaf{TableFilter filter}`) with `toJson`/`fromJson`, `RuleAction` variants, `RunSummary`, `Effect`, `PlaceholderCatalogue`. Enums use UPPER wire names.
- `automation_providers.dart`: record-typedef keys; `ruleDetailProvider`, `placeholdersProvider(String entityType)`, `runDetailProvider`, `runEffectsProvider`, `automationHealthProvider`. Each adds the Part B view-scope params explicitly if the provider exists (G23).
- `rules_screen.dart`: `DataTableScaffold<RuleSummary>(entity: 'automation-rules', path: '/api/automation/rules')`.
  - Status chip (Active, Disabled, Suspended with the reason).
  - Row actions: Run now, Enable/Disable, Delete.
  - Tiles from `/rules/summary`.
  - A health card (AUTOMATION_ADMIN) with a Requeue button on dead events.
- `rule_editor_screen.dart`: `/automation/new` and `/automation/:id`, a full page in the `InvoiceFormScreen` shape plus an `UnsavedChanges` guard. Sections:
  - Basics;
  - Trigger (entity `SegmentedButton`; kind; time picker; weekday dropdown; zone dropdown preset to the device offset from `DateTime.now().timeZoneOffset`, formatted `+HH:mm`, plus "UTC");
  - Conditions;
  - Actions (cards and an "Add action" menu);
  - Preview (count, sample, record picker via `searchEmailRecords`, rendered actions, problems and warnings).
  - Existing rules also get tabs Runs and History (`AuditHistoryPanel(entityType: 'RULE')`), and show "Runs as <owner>".
  - A 400 on `confirmOwnershipChange` opens a confirm dialog, then resends with `true`.
  - Save, Run now, Enable and Delete are disabled when as-of is set; Preview stays enabled.
  - Changing the trigger entity asks before dropping placeholders and tokens that no longer apply.
- `condition_builder.dart`: a recursive `ConditionGroupEditor` with an "All of these / Any of these" `SegmentedButton`.
  - Leaves are `InputChip`s labelled by `describeFilter`; tapping edits through `showFilterEditor(context:, schema:, existing:)`.
  - "Add condition" and "Add group" (depth ≤ 4).
  - The schema comes from `tableSchemaProvider('customers'|'invoices'|'payments')`.
  - It **never** uses `TableQuery.addFilter`.
- `action_cards.dart`: one card per type, each with a `repeat` segmented control and the guard checkbox.
  - Task: title and description with `PlaceholderButton`, due base + offset, assignees (`RecipientChipsField`, context = the trigger entity type, no id).
  - Promise: amount source, date, Collection POC (`single: true`, `roleFilter: {COLLECTION_POC}`), also assign (context PROMISE), notes, link.
  - Dispute: reason, proposal dropdown, assignees (context DISPUTE, `roleFilter` by entity).
  - Email: summary plus "Edit email…".
- `email_action_dialog.dart`: From dropdown (Me/owner, roles, a person), "Fall back to the owner" switch, To (`RecipientChipsField(allowCustomer: true, perRecordLabels: true)`), subject and body with `PlaceholderButton`. It returns a `Map`.
- `placeholder_button.dart`: a menu grouped by level from `placeholdersProvider`. It inserts at `controller.selection`. On the subject it strips newlines.
- `rule_runs_tab.dart`: `DataTableScaffold(entity: 'automation-runs', path: '/api/automation/rules/$id/runs')`.
- `run_detail_dialog.dart`: the effects list (links; hidden targets greyed) and a Retry button. While QUEUED or RUNNING it polls every 2 s, for at most 2 minutes, using the `EmailRefresher` adaptive-timer pattern.

### 18.2 Changed files

- **`shared/widgets/app_shell.dart`**:
  - `NavEntry.unreadBadge: bool` becomes `badge: NavBadge` with `enum NavBadge { none, inbox, myTasks }`; Inbox uses `NavBadge.inbox`.
  - `_NavIcon` switches on it.
  - New entries, in order after Disputes: `My tasks` (`/my-tasks`, `[taskView, taskManage]`, hideForCustomer, `NavBadge.myTasks`), `Tasks` (`/tasks`, same privileges, hideForCustomer), `Automation` (`/automation`, `[automationView, automationManage]`, hideForCustomer). No two paths share a prefix (G15).
- **`core/router.dart`**:
  - `/my-tasks` and `/tasks` use `needs(navPrivilegesFor(...))` and `RouteQuery.read(...)`.
  - `/tasks/:id` uses `onExit: mayLeave` and `pageForId`.
  - `/automation` is a list route.
  - `/automation/new` uses `needs(const [Privileges.automationManage])` and `/automation/:id` uses `needs(const [Privileges.automationView])` (G16).
- **`core/table/reference_picker.dart`**: a `staffUser` kind backed by `/api/assignees/people` (G6).
- **`features/{customers,invoices,payments}/*_detail_screen.dart`**: add `tasksDetailTab(...)` beside `documentsDetailTab` (customer passes `customerScope: true`).
- **`features/audit/audit_history_panel.dart`**: TASK and RULE in `_typeOrder`, `_typeLabels`, `_routeFor` and `_iconFor`; labels for the new actions; a "via rule #N" meta line when `ruleId` is present.
- **`shared/models/dispute.dart`**: `enum DisputeTargetType { INVOICE, PAYMENT, CUSTOMER }` plus its label. The customer-login `dispute_create_dialog.dart` must offer **only** INVOICE and PAYMENT; replace any `DisputeTargetType.values` iteration with an explicit list.
- **`features/disputes/dispute_detail_screen.dart`**:
  - `canResolve` comes from the DTO, falling back to `isAdmin` when absent (G20).
  - Assignee chips with Edit (DISPUTE_MANAGE), a "Created by rule" chip and an "Internal" badge.
  - "Close without change" and "Acknowledge" wording when the proposal is `none` or blank on a CUSTOMER target.
- **Promise detail view**: co-assignee chips with Edit (PROMISE_MANAGE).
- **`shared/models/privileges.dart`**: the five constants.
- **`core/field_limits.dart`**: `taskTitle = 200`, `taskDescription = 2000`, `ruleName = 120`.
- **`shared/widgets/status_chip.dart`**: `TaskStatusChip`, `RuleStatusChip`, `RunStatusChip`.
- **`test/list_columns_test.dart`**: add `myTasksCountProvider.overrideWith((ref) => Stream.value(0))` to its `ProviderScope` overrides (S11/G13). No assertion changes.

---

## 19. Configuration

```yaml
# application.yml
spring.task.scheduling.pool.size: 6        # email sweeper, promise sweeper, automation poll, automation tick, 2 spare
app:
  automation:
    consumer-enabled: true
    poll-interval-ms: 2000
    tick-interval-ms: 30000
    batch-size: 50
    lease-seconds: 300
    max-attempts: 5
    max-claims: 20
    page-size: 500
    slice-budget-ms: 10000
    default-max-records-per-run: 1000
    max-actions-per-rule-per-hour: 1000
    max-email-recipients: 50
    event-retention-days: 14
    default-zone: ${AUTOMATION_ZONE:UTC}
    node-id: ${AUTOMATION_NODE_ID:}
# prod profile: hikari maximum-pool-size 5 → 8

# application-test.yml additions
app:
  automation:
    poll-interval-ms: 3600000
    tick-interval-ms: 3600000
spring.jpa.properties.hibernate.integrator_provider: com.geneinvoice.automation.event.TouchRecorderIntegratorProvider   # test-only class; records nothing until a test enables it
```

---

## 20. Edge cases and their defined behaviour

| Case | Behaviour |
|---|---|
| Record deleted, or moved out of the owner's scope or region, before consumption | No match, no run (entity event). Not in the page (set run). If it happens mid-action: SKIP "The record no longer exists". |
| Owner deactivated or deleted, or turned into a customer login | Suspended (`OWNER_INACTIVE`/`OWNER_NOT_STAFF`), run SKIPPED, notification sent once. A user with rules is deactivated rather than deleted. |
| Owner loses a privilege | Suspended `OWNER_LACKS_RIGHTS`. |
| Owner can view a region but not manage it (B1) | That record's effect FAILED (permanent). The rule keeps running. |
| Rule disabled, deleted or suspended while events are queued | Entity events: not a candidate. SCHEDULED/MANUAL: run SKIPPED. Mid-run: PARTIAL "Stopped". |
| Rule edited mid-run | SUPERSEDED. A queued, unstarted run uses the new definition. |
| Actions reordered or removed between a failure and its retry | Keys are stable, so nothing is done twice. A removed key is simply not run. |
| Newly enabled rule and an old backlog | Events older than `active_since` are ignored. |
| Consumer disabled or down | Rows stay PENDING. Health shows `oldestPendingSeconds`. Missed schedules produce one catch-up. |
| JVM crash mid-event | The lease expires and is recovered. Effects are deduplicated. More than 20 claims gives DEAD. |
| Database down | The drain stops. The event is released with a 30 s delay, or recovered by the lease. No attempt is counted. |
| Lock timeout or optimistic conflict | TRANSIENT: event backoff. It is never mistaken for "database down" (S8). |
| Two nodes | Conditional claim with a fencing token; the ticker uses a conditional `next_run_at` update. |
| DST gap or overlap | The gap moves forward; the overlap fires once, at the earlier offset. |
| Sender role unheld | Falls back to the owner, or FAILED if `fallbackToOwner=false`. |
| No recipients resolve | SKIP. |
| Gmail not connected, or transport `none` | DONE with a warning; the email row is NOT_SENT and can be retried in the UI. |
| Placeholder empty | Fallback, or "" with a warning. |
| Value contains `{{` | Not re-expanded. |
| Subject contains a newline | Collapsed with `oneLine`. |
| Text too long | Fitted with "…" and a warning. |
| Promise amount ≤ 0 | SKIP "Nothing owed". |
| No Collection POC | PERMANENT, with the service's 400 message. |
| Dispute already pending | SKIP. |
| Owner tries to approve the change their own rule proposed | 400. Deny is allowed. |
| Customer login and an internal dispute | 404 on get; absent from lists and email context; audit reasons redacted. |
| Customer login opens a CUSTOMER dispute | 400. |
| Task USER assignee cannot see the record | Automation drops it with a warning; the UI returns 400. |
| ROLE assignee unheld | Kept, with "Nobody holds X" in the DTO (`unresolved:true`). It appears in "My tasks" when someone takes the seat. |
| Customer deleted | Its tasks get `entityDeleted=true` and open ones become CANCELLED. |
| Loops | The rule that caused an event skips it. `depth > 3` skips everything. No current action writes a trigger entity. |
| Burst of updates to one record while the consumer lags | Coalesced into one event (an EVERY_TIME action fires once). |
| Runaway rule | Suspended `RATE_LIMITED` after 1000 DONE actions in an hour. Per-run cap gives `truncated`. |
| `asOf` on any write endpoint | 400 "As-of views are read-only". |
| Preview with `asOf` before B3 | 400 "As-of preview is not available yet". |
| CREATED followed by an in-transaction update | One CREATED. Only CREATED and CREATED_OR_UPDATED rules fire. |
| Invoice created with credit applied | CREATED(invoice), UPDATED(customer, CREDIT), UPDATED(each payment drawn, ALLOCATION). |
| Bulk cancel | One event per committed record. Skipped or rolled-back rows produce none. |
| `recomputeAll(apply=false)` | No events. |
| Notification link or type length | Types ≤ 40 characters; links fitted to ≤ 300 (§3.6 of the critic). |

---

## 21. Tests

### 21.1 Harness changes (one place only, R12)

- **`IntegrationTestBase`** keeps a **single** `@Import(IntegrationTestBase.TestBeans.class)`. `TestBeans` is a static nested `@TestConfiguration` that does `@Import(RecordingMailTransport.Config.class)` and declares:
  - `@Bean @Primary MovableClock clock()`;
  - `@Bean @Primary RecordingAutomationFaults faults()`.
- **`MovableClock`** (`src/test/java/com/geneinvoice/MovableClock.java`) extends `Clock`:
  - `set(Instant)`, `advance(Duration)`, `reset()`;
  - it follows `Clock.systemUTC()` until `set` is called (S9);
  - `reset()` returns it to following mode and is called in `@BeforeEach`.
- **`RecordingAutomationFaults`**: modes `NONE`, `TRANSIENT`, `PERMANENT`, `DB_DOWN` (throws `CannotCreateTransactionException`) and `CRASH_AFTER_COMMIT` (throws `Error` from `afterActionCommitted`), optionally scoped to one action key. Reset in `@BeforeEach`.
- **`resetTransactionalData`** adds, before `customerRepository.deleteAll()`: `recordAssigneeRepository`, `taskHistoryRepository`, `taskRepository`, `automationEffectRepository`, `automationRunRepository`, `automationEventRepository`, `automationRuleVersionRepository`, `automationRuleRepository`, `disputeRepository` (each `deleteAll()`). It also resets `faults`.
- **`TouchRecorder`** (test): a static recorder with `start()` and `stop()` returning `Set<(String type, Long id)>`, fed by `PostInsertEventListener` and `PostUpdateEventListener` for Customer, Invoice and Payment. It is registered by `TouchRecorderIntegratorProvider`.

### 21.2 Backend tests (H2; every automation test clears the SecurityContext before `drain` or `tick`)

**`automation/event/OutboxPublisherTest`**
- `theSaveReadsNoRules`: `CountingStatements.reads("automation_rules", POST /api/invoices) == 0`.
- `aRolledBackSaveLeavesNoEvent`: invoice with an unassignable POC → 400 → no rows.
- `recomputeDryRunLeavesNoEvent`.
- `invoiceCreateWritesOneCreatedAndCustomerCreditUpdated`.
- `paymentRecordPublishesEachAllocatedInvoiceOnceAndThePaymentCreated`.
- `bulkCancelPublishesPerCommittedRecordOnly`.
- `publishingOutsideATransactionFails` (`IllegalTransactionStateException`).
- `oversizedChangesAreFitted`.
- `automationOriginIsStampedWithCausedByRule`.

**`automation/event/TriggerCoverageTest`**: `everyTouchedRecordHasAnEvent`, run over each of these operations:
- customer create and update;
- POC seat add, remove and set-primary;
- invoice create (with credit), update, cancel, `cancelWithRefund`, `replaceItems`;
- payment record (with an excess), update, void, `updateAmount`;
- dispute `update_notes` and `update_meta`.

**`common/query/ConditionTest`**
- `andOrNesting`;
- `andOnlyParityWithListEndpoint`;
- `neqIncludesNulls`;
- `derivedOverdueAndSeatColumns`;
- `unknownColumnBadOperatorBadEnumRejectedAtSaveWithPaths`;
- `emptyGroupRejected`;
- `depthAndLeafLimits`;
- `keysetVisitsEveryRecordOnce` (page size 3, 10 records).

**`automation/placeholder/PlaceholderTest`**
- `levelsOfferedPerEntity`;
- `primaryFollowsSenderRuleWhenPrimaryInactive` (`threeCollectionSeats`);
- `fallbackUsedAndEmptyWarns`;
- `braceValueNotReexpanded`;
- `subjectOneLineAndFittedWithWarning`;
- `pocPlaceholdersBlankWithoutPocView`;
- `unknownKeyRejectedAtSave`.

**`automation/rule/RuleApiTest`**
- `createMakesCallerOwner`;
- `editingOthersRuleNeedsAdminAndConfirmation`;
- `staleVersion409`;
- `saverLackingActionPrivilegeGets400`;
- `softDeleteKeepsHistory`;
- `tableSchemasReadableForHoldersAnd403Otherwise` (`tasks`, `automation-rules`, `automation-runs`);
- `writesRejectAsOf`;
- `versionRowsWrittenOnEveryChange`.

**`automation/run/WorkerDurabilityTest`**
- `consumerDownKeepsEventsPending` (consumer disabled, then enabled and drained);
- `claimTwiceOnlyOneWins`;
- `redeliveredEventCreatesOneTask` (reset to PENDING, drain twice);
- `crashAfterEffectCommitCreatesOneTask` (`CRASH_AFTER_COMMIT`, advance 6 minutes, drain);
- `staleWorkerCannotOverwriteOutcome` (finish with an old token updates 0 rows);
- `transientBacksOffThenSucceedsWithoutDuplicate`;
- `deadAfterFiveAttemptsAndRunFailedAndOwnerNotified`;
- `deadAfterTwentyClaims`;
- `dbDownStopsDrainWithoutCountingAnAttempt`;
- `lockTimeoutIsTransientNotInfrastructure`;
- `coalescesPendingUpdatesOfOneRecord`;
- `scheduledRunYieldsAndResumesFromCursor` (page 2, budget 0);
- `midRunEditSupersedes`;
- `rateLimitSuspends` (limit 3);
- `truncatedAtMaxRecordsPerRun`;
- `drainRefusesInsideATransaction`;
- `requeueAndRetryEndpoints`.

**`automation/run/RunAsOwnerTest`**
- `consumerRunsWithoutAmbientPrincipalAndClearsIt` (after success and after an exception);
- `auditActorIsOwnerWithRuleId`;
- `bookLimitedSalesPocOwnerTouchesOnlyOwnInvoices`;
- `inactiveOwnerSuspendsOnceAndNotifies`;
- `ownerLosingTaskManageSuspends`;
- `deactivatingUserSuspendsTheirRules`;
- `resumeRefusedWhileOwnerInactive`;
- `selfCausedEventSkipped`;
- `activeSinceIgnoresBacklog`.

**`automation/schedule/ScheduleCalculatorTest`** (unit)
- daily and weekly across `+05:30`;
- Europe/London spring-forward and fall-back;
- `latestAtOrBefore` on a slot boundary.

**`automation/schedule/ScheduleTickerTest`**
- `threeMissedDaysGiveOneCatchUp`;
- `concurrentTickOneEvent`;
- `disabledNotTicked`;
- `tickDoesNotBumpRuleVersion`;
- `purgesOldDoneEventsKeepsDead`.

**`automation/action/TaskActionTest`**
- `onceVsEveryTime`;
- `onlyIfNoOpenTaskGuard`;
- `invisibleUserAssigneeDroppedWithWarning`;
- `notificationsToVisibleHolders`;
- `regionCopiedUnderCustomerLock` (B1 variant when available).

**`automation/action/EmailActionTest`**
- `emailRowIsOutboundOnRecordSentByOwner`;
- `fromRoleResolvesPrimaryThenNextActive`;
- `unheldSenderFallsBackOrFails`;
- `noRecipientsSkipped`;
- `successModeDispatchesOnceAfterCommit` (`mailTransport.mode(SUCCESS)`);
- `notConfiguredIsDoneWithWarning`;
- `neverLeftQueued`;
- `recipientCapFails`.

**`automation/action/PromiseActionTest`**
- `primaryBecomesCollectionPoc`;
- `coAssigneesStoredLive`;
- `noPocFailsPermanently`;
- `amountZeroSkipped`;
- `onlyIfNoOpenPromiseGuard`;
- `oncePerRecordAcrossEvents`.

**`automation/action/DisputeActionTest`**
- `internalDisputeCreatedWithAssignees`;
- `duplicatePendingSkipped`;
- `proposedCancelAppliedOnApprovalByAnotherAdmin`;
- `ownerCannotApproveOwnProposedChangeButCanDeny`;
- `customerDisputeAcknowledgedWithoutChange`;
- `internalDisputeHiddenFromCustomerLoginListGetAndEmailContext`;
- `customerLoginCannotOpenCustomerTargetDispute`.

**`automation/PreviewTest`**
- `previewWritesNothing` (row counts of every automation table and of tasks, emails, promises and disputes unchanged);
- `previewScopedToCaller`;
- `previewReportsAlreadyDone`;
- `asOfPreviewReturns400UntilB3`.

**`task/TaskApiTest`**
- CRUD and 404 out of scope;
- `tokenValidation`;
- `assigneeMayCompleteButNotRetitle`;
- `cancelNeedsManage`;
- `versionConflict409`;
- `historyRowPerChange`;
- `customerDeleteCancelsAndMarks`;
- `userWithAssignmentsDeactivatedNotDeleted`;
- `bulkMarkDone`;
- `export`.

**`task/MyTasksTest`**
- `seatAddedAfterCreationAppears`;
- `removedSeatDisappears`;
- `recordLevelSalesPocSeesInvoiceTask`;
- `invisibleParentNeverListed`;
- `mineCountBadge`;
- `mineHasNoLockedFilters`.

**`task/TaskAsOfTest`** (`MovableClock`)
- `statusAsOfDateFromHistory`;
- `assigneesAsOfDate`.

**`dispute/DisputeTargetWidenTest`**: SchemaDrift style. A throwaway `jdbc:h2:mem:auto-drift-<uuid>` URL, booted with `SpringApplicationBuilder` and `--spring.jpa.hibernate.ddl-auto=update`, after creating the old CHECK constraint; then insert a CUSTOMER dispute.

**`automation/AutomationSchemaUpgradeTest`**: `widenIsIdempotentOnFreshSchema`.

### 21.3 Frontend tests (fake backend `test/support/fake_backend.dart`)

**`test/automation_condition_builder_test.dart`**
- builds `AND[leaf, OR[leaf, leaf]]` and asserts the exact JSON;
- edits a leaf through the filter editor;
- removes a group;
- never collapses two leaves with the same field and operator.

**`test/automation_rule_editor_test.dart`**
- POST body shape, including stable action keys;
- trigger kind shows time, day and zone;
- placeholder inserted at the cursor;
- the ownership 400 opens a confirm dialog, then resends;
- preview request carries `regionIds`/`asOf` only when set (G11);
- Save disabled when as-of is set.

**`test/tasks_test.dart`**
- tab list query params (`entityType`, `entityId`, `page`, `size`);
- the create dialog uses `/api/assignees/context`;
- Mark done posts `{status:'DONE', version}`;
- `/my-tasks` hits `/api/tasks/mine`;
- tab hidden without TASK_VIEW and the tab controller is not reset;
- nav badge shows the count.

**Existing 61 tests.** They stay green. The only harness change is the override in `list_columns_test`. The `CurrentUser` constructor is unchanged (G9).

---

## 22. Implementation slices (sequential; every slice leaves backend 119+, mail-service 24 and frontend 61 green, plus its own tests)

| Slice | Content | Depends on |
|---|---|---|
| **A0 Foundations** | Anything from §5 not yet landed: Clock, ViewScope minimal, `WriteOrigin`, `RunAs`, `principalFor`, `AsOfReadable`. Move `SchemaWiden` and `DatabaseErrors` (callers delegate). `EmailText` public; the two `EmailAddressing` helpers public static. Harness `TestBeans`, `MovableClock`, faults. Five privileges, seeding, Dart mirror. | none |
| **A1 Outbox** | Event entity and repository, `AutomationEvents`, `PendingEvents`, every call site in §8.3, `OutboxPublisherTest`, `TriggerCoverageTest` with `TouchRecorder`. | A0 |
| **A2 Assignees and Tasks backend** | `record_assignees`, `Assignees`, `AssignedTo`, `VisibilityProbe`, `AssigneeController`. Tasks entity, history, service, controller and schema. `forTasks`, notifications, audit (TASK), cascade, `pocReferenceCount`, `TaskSchemaUpgrade`. `TaskApiTest`, `MyTasksTest`. | A0 |
| **A3 Rules and preview** | `Condition`, `ConditionJson`, placeholders, rule entity and versions, validator, CRUD, enable/disable/delete, preview (live), placeholders endpoint, schemas, audit (RULE), `AutomationSchemaUpgrade`. `ConditionTest`, `PlaceholderTest`, `RuleApiTest`, `PreviewTest` (live part). | A2 |
| **A4 Consumer core** | Worker, processor, runner, executor, classifier, `OwnerSession`, suspension, runs and effects, CREATE_TASK and SEND_EMAIL handlers (`EmailService.queue`), ticker and calculator, run-now, runs/effects/health/requeue endpoints, circuit breaker, coalescing, slicing, retention, deactivation hook. `WorkerDurabilityTest`, `RunAsOwnerTest`, schedule tests, `TaskActionTest`, `EmailActionTest`. | A1, A3 |
| **A5 Disputes and promises** | CUSTOMER target (widen and all edits in §12.4), internal flag and scoping, `openInternal`, approval rule, dispute and promise co-assignees plus endpoints, CREATE_DISPUTE and CREATE_PROMISE handlers. `DisputeActionTest`, `PromiseActionTest`, `DisputeTargetWidenTest`. | A4 |
| **A6 Frontend tasks** | Assignee widgets, nav badge enum, routes, tasks feature and record tabs, `reference_picker` `staffUser`, audit panel TASK, `list_columns_test` override. `tasks_test`. | A2 |
| **A7 Frontend automation** | Rules list, editor, condition builder, action cards, email action dialog, placeholders, preview, runs, run detail, health. Dispute and promise UI changes. Audit panel RULE. Editor and condition tests. | A5, A6 |
| **A8 As-of and regions integration** | Once B1 and B3 foundations exist: `tasks.region_id` copy and `hasIn`, `forTasks` region, tasks as-of via history, rules as-of via versions, runs as-of, `AsOfRuleEvaluationSource` wiring for preview. `TaskAsOfTest` and the B1 variants. If B1 or B3 landed before A2 or A4, fold these into those slices. | B1, B3 |

---

## 23. Existing files touched

| File | Change |
|---|---|
| `customer/CustomerService.java` | Publish C1 and C2; `taskCascade.onCustomerDeleted` in `delete`; public static `snapshotOf` if needed. |
| `invoice/InvoiceService.java` | I1–I5 |
| `payment/PaymentService.java` | P1–P4; C4 and C5 inside `applyTo` and `reverseAllocations` |
| `payment/CreditLedger.java` | C6 and C7 |
| `poc/PocService.java` | C3 |
| `poc/ScopeResolver.java` | `forTasks()`; `forDisputes` customer branch `internal=false` |
| `dispute/*` | CUSTOMER target; `internal`/`origin`/`ruleId`; `openInternal`, `setAssignees`, approval rule; I6 and P5; DTO fields; controller endpoint; `DisputeSchemaUpgrade` |
| `promise/PaymentPromiseService.java`, `PaymentPromiseController.java`, `PromiseDtos.java` | `setAssignees`, endpoint, DTO `assignees` |
| `email/EmailService.java` | `queue`, `previewRendered` |
| `email/EmailAddressing.java` | Two helpers public static |
| `email/EmailText.java` | Public |
| `email/EmailTargets.java` | `disputeOwnFields` switch; CUSTOMER label and suggestion |
| `email/EmailSchemaUpgrade.java` | Delegates to `SchemaWiden` |
| `email/mailservice/MailServiceEventHandler.java` | Delegates to `DatabaseErrors` |
| `audit/AuditLog.java`, `AuditService.java`, `AuditController.java`, `AuditTimelineService.java` | `origin`, `ruleId`; TASK and RULE; internal-dispute redaction |
| `auth/AppUserDetailsService.java` | `principalFor` |
| `common/query/TableSchemas.java`, `TableSchemaController.java` | TASKS, AUTOMATION_RULES, AUTOMATION_RUNS; DISPUTES and PROMISES `assigneeUserId`; DISPUTES `internal`; `Map.ofEntries` |
| `user/UserController.java`, `UserRepository.java` | `pocReferenceCount`; suspend-on-deactivate hooks; `findActiveStaffHolding` |
| `privilege/Privileges.java`, `config/DataSeeder.java` | Five privileges |
| `common/FieldLimits.java` | `TASK_TITLE`, `TASK_DESCRIPTION`, `RULE_NAME` |
| `application.yml`, `application-test.yml` | §19 |
| `IntegrationTestBase.java` | §21.1 |
| Frontend | §18.2 |