# GeneInvoice: automation, regions, maker-checker and as-of reads — authoritative design

**Status:** authoritative. It supersedes the four decision records (as-of, regions, maker-checker, automation) wherever they differ; §2 lists every such override. Implementation agents build from this document alone.

**Branch:** `feature/automation-regions-approvals-asof`. Baseline at HEAD `321150e`: backend 119 tests, mail-service 24, frontend 61, `flutter analyze` 0 issues.

**Path prefixes.**
- `B/` = `backend/src/main/java/com/geneinvoice/`
- `T/` = `backend/src/test/java/com/geneinvoice/`
- `R/` = `backend/src/main/resources/`
- `F/` = `frontend/lib/`
- `FT/` = `frontend/test/`

Line numbers are at HEAD `321150e` and are for orientation only. Agents re-open the file before editing.

**How to read this document.**
- §2 holds every decision that spans features. When a later section seems to disagree with §2, §2 wins.
- §3 (data), §4 (rights), §5 (backend) and §6 (API) are the specification.
- §9 is the order of work. Each slice names the sections it implements.
- §11 logs how each finding of the three design critics was resolved; the text above already reflects every fix.

---

## 1. Overview and glossary

### 1.1 What is being built

**Part A: the automation engine**
- **Triggers (A1).** A rule fires when a Customer, Invoice or Payment is created or updated, or on a daily or weekly schedule.
- **Conditions (A2).** An AND/OR tree of the list-page filters.
- **Actions (A3).** Create a Task, Promise or Dispute, or send an Email. Assignees and recipients use the email "role at a level" token model.
- **Placeholders (A4).** `{{Customer.Name}}` and so on, offered per level.
- **Durable execution (A5).** A transactional outbox with an in-process leased poller. Each event's condition is evaluated **as of the moment the event happened** (its transaction's history instant, or the schedule slot), so consumer lag or an outage never changes what fires. Rules run as their owner. Schedules and "Run now" (now or at a chosen time) use the same outbox.
- **Tasks (A6).** Tasks with several live assignees, a "My tasks" list, and a tab on each record.

**Part B: the dimensions everything now has**
- **Regions (B1).** Each record belongs to exactly one region. Each user holds a per-region grant, meaning an access level plus a record scope. Every set-returning read answers "which regions am I asking about".
- **Maker-checker (B2).** Eight money-moving operations are gated inside the services. A change above the region's threshold (counting the same maker's recent unapproved changes to the same customer) becomes a pending change that someone else holding `CHANGE_APPROVE` in that region must approve. Approval re-runs the original service method as the maker. Loosening a region's thresholds is itself held for a second approver, and a customer moved between regions stays under the stricter of the two regions' thresholds for a cool-down period.
- **As-of reads (B3).** Typed SCD2 version tables are captured by a Hibernate listener and a before-completion writer. They are read through the existing Criteria engine by swapping the root entity class. Every list, report, dashboard figure and rule preview can be asked as of the end of a UTC day.

### 1.2 How one request flows

```
HTTP ─► JwtAuthFilter ─► AppUserDetailsService.principalFor(user)      (role + region grants, 1 query; §5.5)
     ─► ViewScopeInterceptor.preHandle                                 (the ONLY reader of regionIds / asOf; §5.2)
            ViewScopeHolder := ViewScope(regionIds, asOfPoint|null)
            historical + write handler ⇒ 400 AS_OF_READ_ONLY
     ─► controller ─► service (@Transactional)
            1. load and lock (PPD-01 order)       2. visibility → 404       3. requireLive (defence in depth)
            4. requireIn(privilege, region) → 403  5. validate / compute
            6. MakerChecker.gate(...)  → Held? write pending_changes row, return 202 (steps 7–8 skipped)
            7. mutate entities          8. audit rows; AutomationEvents.created/updated(...) (buffered)
     ─► commit sequence (one DB transaction, one connection):
            a. Spring TransactionSynchronization.beforeCommit  → outbox rows INSERTed, each carrying the
                                                                   transaction's history tx_id (§5.8)
            b. Hibernate flush (all entity DML)
            c. Hibernate BeforeTransactionCompletionProcess     → history versions + history_transactions(tx_id, instant) (§5.7)
            d. COMMIT
            e. afterCompletion: clear per-transaction buffers (outbox buffer, HistoryWork marked done)
     ─► ViewScopeInterceptor.afterCompletion: ViewScopeHolder.clear()

Asynchronously:
  AutomationScheduler.poll ─► AutomationWorker.drain: claim with lease + fencing token (no coalescing)
        ─► RunAs(owner principal, ViewScope(rule regions), WriteOrigin.AUTOMATION(actor = owner))
        ─► RuleEvaluator under AsOf.with(event point)   (DB, through TableQueryExecutor on version roots)
        ─► read-only preparation (render, resolve, probe), then per action one REQUIRES_NEW tx:
           customer lock → re-check dedupe and guards → write + uniquely keyed effect row
        ─► email dispatch after commit
  AutomationScheduler.tick ─► ScheduleTicker inserts SCHEDULED outbox rows (evaluated as of scheduled_for)
```

### 1.3 Glossary

| Term | Meaning |
|---|---|
| **Region** | Row in `regions`. Exactly one is the default. Customers and their region-bearing children point at one region. |
| **Grant** | `(user, region, accessLevel, recordScope)`, unique per (user, region). "No row" means no access in that region. |
| **AccessLevel** | `READ < MANAGE < APPROVE`. Each regional privilege needs a minimum level (§4.1). |
| **RecordScope** | `ALL` or `BOOK`. `SCOPE_OVERRIDE` is effective only with `ALL`. |
| **Effective right** | `role ∋ p ∧ (global(p) ∨ (grant(R) ∧ grant.level ≥ level(p) ∧ (p ≠ SCOPE_OVERRIDE ∨ grant.scope = ALL)))`. |
| **RegionRights** | Immutable per-principal object that answers `has(p, region)`, `regionsWith(p)`, and so on. It is built once per request. |
| **Tier** | Per region and per entity kind, one of `FULL` (all records), `BOOK` (only records whose POC is me) or `NONE`. |
| **ViewScope** | `(Set<Long> regionIds /*null = all mine*/, AsOfPoint asOf /*null = live*/)`, bound to the thread for one request or one background unit of work. |
| **AsOfPoint** | `(LocalDate date, Instant instant)`. For wire date D the instant is `(D+1)T00:00Z − 1µs`. |
| **Historical / live** | A request is historical when a non-null AsOfPoint is bound. |
| **Version row** | A row in `<entity>_versions` with the entity's attribute names plus `valid_from`, `valid_to`, `tx_id`, `origin`, `origin_ref_id`, `actor_user_id`. |
| **OPEN** | The sentinel `valid_to = 9999-12-31T00:00:00Z` marking the current version. |
| **HistoryWork** | Per Hibernate transaction: the set of touched aggregate keys. The writer turns it into versions just before commit. |
| **Historical object** | An unmapped subclass such as `HistoricalInvoice extends Invoice`. It is rebuilt from versions and cannot be persisted. |
| **Records** | The by-id loader that returns a live entity, or a historical object when a point is bound. |
| **Temporal** | Static helper that routes `from`, `join` and `subquery` to version classes when a point is bound. |
| **Outbox event** | A row in `automation_events` (CREATED, UPDATED, CHILDREN, SCHEDULED, MANUAL or REPLAY). It is inserted in `beforeCommit`. |
| **Event point** | The `AsOfPoint` a rule condition is evaluated at: the history instant of the publishing transaction (entity events), `scheduled_for` (SCHEDULED, MANUAL). Actions, placeholders and assignees are always live. |
| **Rule** | A row in `automation_rules`. It has a trigger, a condition tree, 1–5 actions, and an owner. |
| **Run** | A row in `automation_runs`, one per (rule, event). |
| **Effect** | A row in `automation_effects`, one per (run, action, target). When DONE it carries the unique `dedupe_key`. |
| **WriteOrigin** | Thread-local provenance of a write: kind (USER, SYSTEM, AUTOMATION, APPROVAL, SCHEMA or RECONCILE), rule, run, event, pending change, checker, **actor**, depth. |
| **RunAs** | Installs a fresh principal, a ViewScope and a WriteOrigin for a unit of work, then restores the previous ones. |
| **Pending change** | A row in `pending_changes`: a held, not-yet-applied money change. |
| **Maker / checker** | The requester, and the approver. Approval re-invokes the service method as the maker. |
| **Threshold** | `approval_thresholds(region, operation, amount, window_hours)`. No row means the operation is not gated. "Above" means `amount > threshold`, where the amount includes the same maker's recent unapproved changes to the same customer (§5.11.3). |
| **Outstanding key** | `"<ENTITY>:<id>"`. It is unique while a change on that record is PENDING. |
| **Assignee token** | The email `EmailToken` shape: `{USER,userId}` or `{ROLE,role,level}`. It is stored in `record_assignees` and resolved live. |
| **Sender rule** | The primary holder if active, otherwise the oldest active seat (`EmailTargets.Target.sender`, critic X7). |

---

## 2. Resolved cross-cutting decisions

Each decision gives the choice, the rationale, and what it overrides. "DR-asof", "DR-regions", "DR-mc" and "DR-auto" are the four input records.

### D1. Package map

| Package | Contents |
|---|---|
| `B/common/time` | `ClockConfig`, `AppClock`, `AppClockBridge`, `Today`, `WallClock` |
| `B/common/scope` | `AsOfPoint`, `ViewScope`, `ViewScopeHolder`, `AsOf`, `ViewScopeInterceptor`, `ViewScopeConfig`, `AsOfAware`, `AsOfIgnored`, `AsOfException`, `CodedBadRequestException` |
| `B/common/origin` | `WriteOrigin`, `WriteOrigins` |
| `B/common/schema` | `SchemaSupport`: `product`, `isNullable`, `enforceNotNull`, `widen`, the latter now handling H2 native ENUM columns |
| `B/common` | `DatabaseErrors`, `Fit` (the one string-fitting helper) |
| `B/auth` | `RunAs`, plus changes to `AppUserDetails`, `AppUserDetailsService` and `CurrentUser` |
| `B/region` | regions, grants, rights, `RegionSchemaUpgrade`, move, `RecordAccess` |
| `B/history`, `B/history/versions`, `B/history/types` | as-of capture and reads |
| `B/automation/**`, `B/task`, `B/assignee` | Part A |
| `B/approval` | maker-checker |

### D2. One request context, read in exactly one place (P5)

**Choice**
- `ViewScopeInterceptor` (a `HandlerInterceptor` on `/api/**`) is the **only** code that reads the `regionIds` and `asOf` request parameters.
- It fills `ViewScopeHolder`, a ThreadLocal holding `ViewScope(regionIds, AsOfPoint)`.
- `AsOf` is a static facade over the same holder (`AsOf.current()`, `AsOf.active()`, `AsOf.with(point, body)`), so DR-asof's API and DR-regions' API are one mechanism.
- Consumers:
  - `ScopeResolver` (region narrowing)
  - `TableQueryExecutor` / `Temporal` / `Records` (as-of)
  - `DashboardService`
  - `Today.date()`
  - `PendingChangeScope`
  - task and rule lists

**Why a ThreadLocal and an interceptor**
- A ThreadLocal, not a `@RequestScope` bean, because `RunAs`, the consumer and the approval path must set it off-request.
- An interceptor, not a filter, because it needs the `HandlerMethod` to classify the endpoint, and because exceptions thrown there reach `GlobalExceptionHandler`.

**Overrides**
- DR-auto's `ViewScopeFilter` and `@AsOfReadable`, replaced by method-level `@AsOfAware`.
- DR-asof's separate `AsOf` ThreadLocal, which is now a facade.
- DR-regions' `BadRequestException("Viewing as of … is read-only")`, now coded `AS_OF_READ_ONLY`.

**Classification rule (fail-closed; this extends DR-asof)**

| Handler | In a historical request (asOf < today) |
|---|---|
| GET/HEAD on a class or method annotated `@AsOfAware` | AWARE: bound, header `X-View-As-Of: <date>` |
| GET/HEAD on a class or method annotated `@AsOfIgnored` | IGNORED: live, header `X-View-As-Of: live`, `PageResponse.asOf = null` |
| Any method with a **method-level** `@AsOfAware` | AWARE. Allowed only for read-only POSTs: `/export` endpoints and the rule preview. |
| Any method with a **method-level** `@AsOfIgnored` | IGNORED. Allowed only for housekeeping writes (§5.2.4). |
| Non-GET with no method-level annotation | 400 `AS_OF_READ_ONLY`, **including on `@AsOfIgnored` classes** |
| GET with no annotation at all | 400 `AS_OF_UNSUPPORTED`. `ViewScopeInventoryTest` fails the build on any such handler. |

- **Consequence:** in historical mode every domain and configuration write is refused by the server, and the UI disables it too.
- `asOf == today (UTC, appClock)` is treated as live.
- `ViewScopeHolder.requireLive()` stays in service write methods as defence in depth, for example for code reached from `RunAs`.

### D3. How the three transaction hooks share one transaction

| Hook | Where it runs | What it writes | Depends on |
|---|---|---|---|
| Maker-checker gate (§5.11) | Inline in the service method, after authorisation and validation, before the first mutation | `pending_changes`, audit rows, notifications (held path only) | nothing |
| Outbox publisher (§5.8) | `AutomationEvents.created/updated` buffer in a `TransactionSynchronization`; rows are INSERTed in `beforeCommit` | `automation_events` | nothing |
| History writer (§5.7) | Hibernate `BeforeTransactionCompletionProcess`, after Hibernate's final flush | `*_versions`, `entity_link_versions`, and `history_transactions` for a transaction that has outbox rows | nothing (the outbox only asks for the `tx_id`) |

Commit order, verified in Hibernate 6.5.3 `SessionImpl`: Spring `beforeCommit` → `flushBeforeTransactionCompletion()` → `actionQueue.beforeTransactionCompletion()` → COMMIT.

**Rules**
1. **None of the three reads another's output during the transaction.** The outbox stores only the transaction's history `tx_id` (a UUID that exists before the instant is bound, from `HistoryTransactions.txId(em)`, which never binds the instant). The history writer records `history_transactions(tx_id, instant)`; the **consumer** later joins the two to find the event point (D10). The history writer ignores untracked entities: outbox rows, pending changes, audit rows, notifications, emails, assignees, and rule revisions.
2. **A held change** mutates no tracked entity and publishes nothing, so it produces no outbox row and no entity version (R8). The `pending_changes` row is not versioned (D8).
3. **On rollback** none of them leaves anything. This covers an exception, `setRollbackOnly`, `recomputeAll(false)`, and a `REQUIRES_NEW` bulk row that loses at commit. Spring skips `beforeCommit` and Hibernate skips before-completion.
4. **A read-only transaction** (`beforeCommit(readOnly=true)`) drops buffered events with a WARN. No write path is read-only.
5. **All three are trivially safe:**
   - The outbox and version tables have no FK, no unique constraint and no CHECK; every bounded string is fitted to its column, and identifier lists are unbounded TEXT that is never fitted.
   - The history writer catches Java-side mapping errors (strict in tests).
   - The gate's only failure modes are business answers.

### D4. One provenance carrier: `WriteOrigin`

**Choice.** `record WriteOrigin(Kind kind, Long ruleId, Long runId, Long eventId, Long pendingChangeId, Long checkerUserId, Long actorUserId, int depth)`.
- `enum Kind { USER, SYSTEM, AUTOMATION, APPROVAL, SCHEMA, RECONCILE }`.
- `WriteOrigins.current()` returns the bound origin. When none is bound it resolves to USER if a principal exists, otherwise SYSTEM.
- `WriteOrigins.actor(currentUser)` returns the bound origin's `actorUserId` when set, otherwise `currentUser.idOrNull()`. **Commit-time consumers read the actor through it, never from the SecurityContext directly.**
- `RunAs.call` binds the origin with `actorUserId` = the installed principal's id when the passed origin has none.

**Provenance must still be bound when the transaction commits** (critic finding CF-1). The outbox (`beforeCommit`) and the history writer (before-completion) run at commit, after any `RunAs` inside the transaction body has already restored the caller. Two rules follow:
1. Code that runs a transaction body under another identity binds the origin **around** the transaction, not inside it. `ApprovalService.approve` is the one such site: `WriteOrigins.callWith(WriteOrigin.approval(pcId, checkerId).withActor(makerId), () -> tx.execute(...))` (§5.11.6). The automation consumer already commits each action transaction inside `OwnerSession.call`.
2. `PendingEvents` snapshots `(kind, ruleId, depth, actorUserId, pendingChangeId)` on each draft at the first `created()`/`updated()` call for its key, and `beforeCommit` writes that snapshot (defence in depth).

**Consumers**
- `AuditService`, which stamps `origin`, `rule_id`, `pending_change_id` and `approved_by_user_id`.
- `AutomationEvents`, which stamps `origin`, `caused_by_rule_id`, `depth` and `actor_user_id` from the draft snapshot.
- `HistoryWriter`, which stamps `origin`, `origin_ref_id` and `actor_user_id` (via `WriteOrigins.actor`).
- `MakerChecker`, which reads `AUTOMATION` to set `origin`, `rule_id` and `automation_event_id` on a pending change.

**Replaces** DR-asof's `HistoryOrigin`, DR-auto's `WriteOrigin`, and DR-mc's `AutomationRunContext` plus the provenance part of `ApprovalContext`. `ApprovalContext` still exists, but only for the gate's apply-mode data (§5.11).

**Spelling.** The automation origin is **`AUTOMATION`** everywhere: audit, outbox, versions, `tasks.origin`, `disputes.origin` and `pending_changes.origin`. DR-asof's `RULE` and DR-mc's `ChangeOrigin.RULE` are renamed.

### D5. Clock (P6)

- `ClockConfig` declares **exactly one** `Clock` bean: `@Bean Clock appClock()` = `Clock.systemUTC()`.
  - It is deliberately **not** `@Primary`. The test `@Primary` clock then wins without bean-definition overriding, which Boot disables by default.
- `AppClock` is a static bridge set by `AppClockBridge` (a `@Component` that takes the primary `Clock`). JPA callbacks and static helpers read it.
  - It starts as `Clock.systemUTC()`, so an entity callback that fires before the bridge bean exists (for example an audit row written by a schema-upgrade bean) never sees null.
  - It is JVM-wide. Any extra context a test boots (the drift boots) would overwrite it with that context's clock, so `IntegrationTestBase.resetTransactionalData` calls `AppClock.set(MovableClock.SHARED)` before every test, and the drift-boot tests restore it in `finally` (critic finding CF-7).
- `Today.date()` returns `AsOf.current().date()` when a point is bound, otherwise `LocalDate.now(AppClock.get(), UTC)`.
- **Every business "now" or "today" read goes through `appClock`, `AppClock` or `Today`.** That covers every file in the §5.1 table: every `@PrePersist`/`@PreUpdate`, `InvoiceNumbers`, `InvoiceService` defaults and `FilterPredicates.relative`; `ClockReadGuardTest` keeps it so.
- **Protocol time** uses `WallClock.UTC`, a `public static final Clock UTC = Clock.systemUTC()` in `B/common/time/WallClock`. It is not the bean, so tests never move it. This covers JWT `iat`/`exp` (`JwtService:43`), webhook signature skew (`WebhookSignature`, `MailServiceEventsController:50`) and `ApiError.timestamp`. This is the one documented carve-out from P6.
  - *Rationale:* a test that moves `appClock` into the past must not mint expired tokens or reject webhook signatures.
- **Tests:** `MovableClock.SHARED` is one JVM-wide instance registered as `@Bean @Primary Clock testClock()` in both test contexts.
  - It follows `Clock.systemUTC()` until `set(...)` is called.
  - `reset()` returns it to following mode.
  - `IntegrationTestBase` resets it in `@BeforeEach`.
  - Existing tests therefore see real time, which preserves P7.

### D6. Principal, rights and RunAs (P3, C-4/R3)

- `AppUserDetailsService.principalFor(User)` builds `AppUserDetails(user, authorities, RegionRights)` with exactly one grants query.
  - `principalFor(Long userId)` returns `Optional<AppUserDetails>`.
  - `loadUserByUsername` delegates to `principalFor` and is `@Transactional(readOnly = true)`.
  - The two-argument `AppUserDetails` constructor and the one-argument `buildAuthorities` are **deleted**, so the compiler lists every construction site. The only ones are `AppUserDetailsService` and `IntegrationTestBase:134,141`.
- Flat authorities are `ROLE_<name>` plus `RegionRights.effectiveAnywhere()`, the union across regions. `@PreAuthorize` therefore means "holds it somewhere". Per-record MANAGE and APPROVE checks are explicit (§5.6.6).
- **`B/auth/RunAs`** is the single run-as component:
  - `<T> T call(AppUserDetails p, ViewScope s, WriteOrigin o, Supplier<T> work)` saves the SecurityContext, ViewScope and WriteOrigin, installs fresh ones (the origin with `actorUserId` = the principal's id when unset, D4), and restores all three in `finally`.
  - `asUser(long userId, ViewScope, WriteOrigin, Supplier)` throws `RunAs.InactiveUserException` for a missing, inactive or customer-login user.
  - Used by the automation `OwnerSession`, the maker-checker approval (running as the maker), and `VisibilityProbe`.
- **Overrides** DR-mc's `RunAs.as(userId, body)` and DR-auto's `principalFor(Long)`-only signature. Both are subsumed.

### D7. Region storage, and moving a customer (P4, X5)

- `region_id` is denormalised on `customers`, `invoices`, `payments`, `payment_promises`, `disputes`, `tasks`, `pending_changes` and `approval_thresholds`.
  - It is copied from a customer row locked `FOR UPDATE` in the same transaction (F1).
  - It is mapped `@ManyToOne(LAZY) @JoinColumn(name = "region_id", updatable = false)`, so a stale ORM save can never write an old region back (F3).
- Documents, emails, invoice items, allocations and seats derive their region through their parent.
- **The move** (`POST /api/customers/{id}/move-region`) proceeds as follows:
  1. Lock the customer, then its invoices in ascending id (PPD-01).
  2. Collect the child ids.
  3. Run native `UPDATE … SET region_id` statements, bumping `version` on customers and invoices.
  4. **Call `HistoryTracker.touch(type, ids, false)` for every moved type in the same transaction.** This resolves DR-regions (native SQL) against DR-asof ("ORM cascade"): the native SQL is kept for F3, and history is kept exact by the sanctioned touch path.
  5. Insert a `customer_region_moves` row (from, to, when, who), which the maker-checker cool-down reads (§5.11.3).
  6. Call the `RegionMoveParticipant` SPI.
- Participants:
  - **Tasks** move their own rows (native plus touch).
  - **Automation** publishes UPDATED(REGION) for the customer and **one** `CHILDREN` event for its invoices and payments, which the consumer expands (§5.8.2 M1). The move therefore writes O(1) outbox rows, not one per child (critic finding PC-4).
  - **Maker-checker** **vetoes** the move (400 "Acme Ltd has 2 changes awaiting approval; they must be decided first") while the customer has PENDING changes, **unless** the mover holds `CHANGE_APPROVE` in the source region. In that case it **closes** them (status CLOSED, maker notified), as before.
    - A move must never let someone without the approval right silently discard other makers' requests, and a move must never be a way round a threshold (critic finding SC-4). `pending_changes.region_id` still never changes.
- **Threshold cool-down.** For `app.approval.move-cooldown-days` (default 30) after a move, the gate uses the stricter of the current and the previous region's threshold for each operation (§5.11.3). Moving a customer to a laxer region, changing it, and moving it back is therefore still held.

### D8. Which tables are versioned for as-of, and which answer from their own timestamps

**Versioned by the generic capture (§5.7)**, because rows are mutated in place:
- Customer, Product, Invoice (lines embedded as JSON), Payment, PaymentAllocation
- PaymentPromise, and its invoice and payment links
- Dispute, CustomerPoc (seats)
- **Task** (A6)

**Answer as-of from their own interval or timestamp columns**, with no version table:

| Table | As-of predicate at point p |
|---|---|
| `pending_changes` | `requested_at <= p AND (decided_at IS NULL OR decided_at > p)`; `region_id` never changes |
| `record_assignees` | `added_at <= p AND (removed_at IS NULL OR removed_at > p)` |
| `automation_rule_revisions` | latest `saved_at <= p` per rule |
| `automation_runs`, `automation_effects` | `created_at <= p`; a run's `statusAt` is derived from `started_at`/`finished_at` (§5.10.10) |
| `documents` | `uploaded_at <= p AND (deleted_at IS NULL OR deleted_at > p)` |
| `emails` | `occurred_at <= p` |
| `audit_logs` | `created_at <= p` |
| `customer_region_moves` | `moved_at <= p` (append-only) |

**Bookkeeping tables with no as-of role:** `history_transactions` (the instant of each history transaction; the consumer's event point), `approval_usage` (the cumulative-threshold window, §5.11.3).

**Overrides**
- DR-asof §9.4, which versioned PendingChange. B2's rows are append-and-decide-once, and interval columns are exact and cheaper.
- DR-auto §6.7 `task_history`, which was written by the service. Tasks use the generic mechanism instead, so there is one capture path and lists, counts, exports and "my tasks" swap roots with no task-specific as-of code.
- DR-auto's `automation_rule_versions` is renamed **`automation_rule_revisions`**, so that `*_versions` names only generic version tables. The `CountingStatements.reads("_versions")` assertions depend on this.

### D9. As-of semantics (B3)

- Wire `asOf=yyyy-MM-dd`, strict. Point `p` = end of that UTC day.
- `asOf > today` → 400 `AS_OF_FUTURE`. `asOf == today` → live. `asOf < historyStartsOn` → 400 `AS_OF_BEFORE_HISTORY`.
- A record exists at T iff it has a version valid at p. Backdated `invoiceDate` values never decide existence.
- "Today" in as-of mode is `Today.date()` = D. This drives overdue, `relative` presets, tiles, ageing and export columns.
- **Rights:** the caller's **current** grants are applied to the record's **region and book at T**. Grants have no history; grant changes are audited as `USER_REGION_GRANTS_CHANGED`.
- **Configuration lists answer live** under a historical request, with header `live` and `asOf: null`: users, roles, privileges, regions, grants, thresholds, table schemas, POC pickers, notifications, inbox, Gmail, assignee pickers. Notifications and the inbox still honour `regionIds` (B1; §5.6.7).
- **Rules list and rule detail are AWARE.** They are served from `automation_rule_revisions` (§5.10.10).

### D10. Rule evaluation and as-of

- **The consumer evaluates each event at its event point** (critic finding PC-1; this reverses the earlier deferral of DR-asof §9.2):
  - **Entity events (CREATED, UPDATED, CHILDREN):** the instant of the transaction that published them. The outbox row stores the transaction's history `tx_id`; the history writer records `history_transactions(tx_id, instant)` for every transaction that has buffered events (even one that changed only seats, or nothing versioned). The consumer looks the instant up and wraps the condition check in `AsOf.with(AsOfPoint.at(instant))`.
  - **SCHEDULED and MANUAL:** `AsOfPoint.at(scheduled_for)` (the slot, or the requested run time).
  - If no `history_transactions` row exists (history capture was skipped by a non-strict mapping error), the event is evaluated live, with a WARN and the run message "Evaluated on current data".
- **Consequence:** consumer lag or an outage never changes what fires. An invoice created UNPAID and paid while the consumer was down still fires a "created, status UNPAID" rule. Two quick updates are two evaluations, each at its own point, so **events are never coalesced**.
- **Only the condition is evaluated at the point.** Guards (`onlyIfNoOpenTask`, `onlyIfNoOpenPromise`), placeholders, assignees, senders and the actions themselves are resolved **live, at action time**, because they describe what is done now. A record deleted since the event is skipped ("The record no longer exists").
- **Rights** at the point are the owner's **current** rights applied to the record's region and book at the point (as Q2).
- **"Rule evaluation can be asked as of a date"** is met twice: by the engine itself (above), and by the preview endpoints (`POST /api/automation/rules/preview`, `GET /api/automation/rules/{id}/preview`, `GET /api/automation/rules/{id}/matches`), which are `@AsOfAware`:
  - `matchCount` and `sample` come from the executor's root swap. That covers versions, region and book at T, and today = D.
  - For a saved rule under a bound point, the **revision in force at T** is evaluated (404 if the rule did not exist then).
  - In historical mode the per-record **action rendering is omitted** (`record.actions = null`, plus `asOfNote`). Rendering needs live seats, senders and Gmail status, and it describes what would happen **now**.

### D11. Maker-checker interactions

- **Placement.** Inside the eight service methods, in the order: authorise (region) → validate → gate → mutate → side effects (audit, notify, reevaluate, **publish**).
- **Legacy signatures stay.** `create`, `update`, `cancel`, `record`, promise `create`/`update`/`override` and dispute `approve` keep their signatures as `xOrHold(...).appliedOrThrow()` wrappers. Existing tests and callers compile unchanged.
- **Automation.** The consumer calls `createOrHold` under `RunAs(owner)` with `WriteOrigin.AUTOMATION`.
  - A `Held` outcome is recorded as effect `DONE`, `result_type = PENDING_CHANGE`, with its dedupe key. A redelivery can therefore never create a second pending row.
  - The owner can never approve it, because maker = owner.
- **Approval.**
  - `ApprovalService.approve` runs outside any transaction. It binds `WriteOrigin.approval(pcId, checkerId).withActor(makerId)` **around** TX-A (D4), and inside TX-A runs the apply under `RunAs.call(principalFor(maker), ViewScope.LIVE_ALL, the same origin, …)` inside `ApprovalContext.applying(...)`.
  - The re-invoked method re-checks every rule as the maker, including region MANAGE, book and assignability.
  - Its audit rows carry `pending_change_id` and `approved_by_user_id`. Its outbox rows carry `origin = APPROVAL` and `actor_user_id = maker`. Its versions carry `origin = APPROVAL`, `origin_ref_id = pcId` and `actor_user_id = maker`, because the origin is still bound when TX-A commits.
- **Rights.** Approving needs `CHANGE_APPROVE` **in the pending row's region**, which is APPROVE level, and `checker ≠ maker`. When the pending change came from an internal dispute, the checker must also differ from the dispute's opener (the rule owner) (§5.11.6). Approvers come from `RegionAccessQueries.holdersIn`, never from `findByRoleName`.
- **Promise co-assignees survive a hold.** `PromiseDtos.CreatePromiseRequest` gains a trailing nullable `List<EmailDtos.EmailToken> assignees` (BE-14 adds the component; BE-19 applies it). It keeps a 6-argument convenience constructor (tests use it) and gets a `@JsonCreator` compact canonical constructor. From BE-19, `createOrHold` stores the assignees through `AssigneeStore` after the save, so a held automation promise gets its co-assignees on approval.
- **Invoice create.** It locks the customer at the start (F1). The invoice number is taken **after** the gate, so a held create consumes no number. The lock order is customer → sequence → invoice.

### D12. Enum columns and widening (P1, R9); new finding

- On **H2**, Hibernate 6.5's `H2Dialect` renders `@Enumerated(STRING)` as a **native `ENUM(...)` column type**, not a CHECK constraint. This was verified in the scratchpad (`exp`, `enumprobe`).
  - `EmailSchemaUpgrade.widen` only looks for CHECK constraints on H2, so today it silently cannot widen `azurefree`'s file database.
  - `SchemaSupport.widen` therefore gains an H2 branch: if `information_schema.columns.data_type = 'ENUM'` and the declared values lack a constant, run `alter table T alter column C set data type enum(<all values>)`. NOT NULL is preserved (verified).
  - The Postgres branch (CHECK replace) is unchanged.
- **Where each enum style is used**
  - New enum columns on **live** tables keep `@Enumerated(STRING)` and register a `widen` call in their schema-upgrade bean.
  - Enum columns on **version tables**, **`pending_changes`** and **`approval_thresholds`** use `AttributeConverter<E,String>` (plain `varchar`, no CHECK and no ENUM type).
- **Appending constants.** The `widen` Postgres filter identifies the stale CHECK through the enum's **first** constant. New constants are therefore always **appended last**, for example `DisputeTargetType.CUSTOMER`.

### D13. Frontend view scope (P5, G1/G2/G3/G11/G23)

- `viewScopeProvider` holds `ViewScope{regionIds, asOf}`. It lives in session memory only; it is not persisted and not in the URL, and it is cleared on logout.
- `viewScopeInterceptor(() => ref.read(viewScopeProvider))` is added to `dioProvider` and adds `regionIds` and `asOf` to **every** request, including bulk and export POSTs, with `putIfAbsent`, and **only when set**.
  - It is exported so tests can put it in front of the fake backend with `FakeBackend.dioWith([...])`, because tests bypass `dioProvider`'s interceptors (G23; §7.8).
- Providers that must refetch when the scope changes `ref.watch(viewScopeProvider)`:
  - `tablePageProvider`, `tableSummaryProvider`
  - the dashboard providers, `myPocScopeProvider`, `assignablePocsProvider`, `referenceOptionsProvider`
  - the detail and record-tab providers
- `checkAsOf(response, asOf)` verifies the `X-View-As-Of` header in those providers. A missing or wrong header throws `AsOfMismatch`, so a screen can never silently show live data as history.
- **No collection is ever put into `TableRequest.extra`** (G1).
- The region selector and the as-of chip live in `AppShell` actions, and a read-only banner sits in the body.
- **AppShell never fetches at build time** (G13). Region options come from `/me`; the history range is fetched when the picker opens.

### D14. Server-provided capability flags (R16)

- Rights that depend on a record's region come from the server, so the UI never re-derives region logic. The flags are:
  - `canManage` on Customer, Invoice, Payment and Promise
  - `canMoveRegion` on Customer, `canOverride` on Promise, `canResolve` on Dispute
  - the existing `canEdit` and `canDelete` on Document
  - `canEdit` and `canChangeStatus` on Task
  - `canApprove`, `canReject`, `canWithdraw` and `blockedReason` on PendingChange
  - `canEdit`, `canRun` and `editTakesOwnership` on Rule
- Every flag is `false` in historical mode.
- Audit snapshots (`XDto.from(entity)`) carry `null` flags, so a caller's rights never land in audit JSON.
- `CurrentUser.privileges` on the client stays the union, for nav and route guards. `CurrentUser.regions[].privileges` exists for create-flow pickers.

### D15. Status codes

| Status | Meaning |
|---|---|
| 404 | Invisible: out of region or book (AUTH-08), or no version at T in historical mode |
| 403 | No right, e.g. `requireIn` fails in a visible record's region. The message is replaced by the generic one (V11), so custom denials that need text are 400. |
| 400 | Business rule, validation (`fieldErrors`), as-of codes, `REGION_IDS_INVALID`, self-approval |
| 409 | Optimistic or pessimistic concurrency, integrity, `ApprovalConflictException` (another change is outstanding, already decided) |
| 202 | Held for approval |

**Never 401** except for authentication (G26). The approval path maps `AuthenticationException` from the maker's re-run to a FAILED outcome (§5.11.6).

### D16. Test harness (R11, R12)

- `IntegrationTestBase` keeps **one** `@Import(IntegrationTestBase.TestBeans.class)`. The nested static `@TestConfiguration` imports `RecordingMailTransport.Config` and declares:
  - `@Bean @Primary Clock testClock()` returning `MovableClock.SHARED`
  - `@Bean @Primary RecordingAutomationFaults automationFaults()` (from BE-17)
  - `@Bean @Primary HistoryTestHooks historyFaults()` (from BE-07). `HistoryTestHooks` (in `T/history`) implements the main-code interface `B/history/HistoryFaults`, whose production bean is the no-op `@Component NoHistoryFaults`. Main code never references a test class (critic finding CF-6); this is the same pattern as `AutomationFaults`.
- **No `@MockBean`, `@TestPropertySource`, per-class `@Import` or `@DirtiesContext` anywhere.** Each of those would start a third context and wipe the shared H2 database.
- `@BeforeEach resetTransactionalData` gets the order in §3.12.
- `@AfterEach historyIsInStep()` (from BE-07) asserts `historyReconciler.check().problems()` is empty after every test in the suite.
- `application-test.yml` gains:
  - `app.history.strict: true`
  - `app.history.reconcile-interval-ms: 3600000`
  - `app.automation.poll-interval-ms: 3600000`
  - `app.automation.tick-interval-ms: 3600000`
- Background jobs are driven directly by tests. Any test calling a consumer or ticker first calls `SecurityContextHolder.clearContext()` (R2).

### D17. Customer logins

- They have no grant rows. Their rights are `role ∩ {own customer's region}`, which is read on every request, so a region move is followed automatically.
- They ignore `regionIds`.
- In as-of mode they see their own records at T.
- They never see internal disputes, pending creates, approval identities or task data. That holds on **every** path, including the audit timeline with `includeRelated`, `for-record`, the `pendingApproval` flag, the History-tab `pendingStatus`, and the "an open dispute already exists" guard (critic finding SC-1; §5.13, §5.11.9, §5.10.11). A pending change that came from an internal dispute is marked `internal_source` and is treated as internal work.
- The as-of chip is shown to staff only (Q22); the API honours `asOf` for customer logins.
- `CustomerService.get` keeps its 403 for another customer's id, and `DisputeService.get` keeps its 403 for another customer's dispute (critic X1: existing behaviour, P7). The new checks use 404.

### D18. Products stay global

Products are a catalogue with no region. They are versioned for as-of, so a line's product name at T is correct, but they are not gated by B2. This narrows B1's "every record belongs to exactly one region" and B2's "any change"; both are put to the PRD owner as Q19 and Q20.

### D19. Pinned decisions: where each is met

| Pin | Met by |
|---|---|
| **P1** H2 tests, Postgres-valid SQL, gated dialect SQL, C-5/R9/R10, deps from Central | All new tables are plain JPA DDL. Dialect-gated SQL: `uk_regions_default` partial index (PG only); `SchemaSupport.enforceNotNull` (H2 `alter column … set not null`, verified on H2 2.2.224, same on PG); `SchemaSupport.widen` (PG CHECK / H2 ENUM). `@ColumnDefault` only for scalar flags (`users.region_backfill_pending`, `disputes.internal`). `region_id`: nullable, backfill, enforce. **No new Maven dependency is needed.** Envers and ArchUnit were rejected. |
| **P2** Transactional outbox | §5.8 publisher (MANDATORY, dedupe, `beforeCommit`, trivially safe rows guarded by `OutboxSchemaTest`) and §5.10 consumer (lease, fencing, retry, backoff, DEAD, effect-level idempotency, **evaluation at the event point** so an outage changes nothing, no coalescing); schedules, run-now and replay insert outbox rows. The consumer takes the customer lock only for the write itself (§5.10.6). |
| **P3** Rules run as owner | `OwnerSession` → `RunAs.call(principalFor(owner))` with grants loaded eagerly; suspension on an inactive owner or a lost right (including AUTOMATION_MANAGE); cleared in `finally`. Anything that makes a rule act (enable, resume, run) is done by its owner; a non-owner AUTOMATION_ADMIN must take ownership first (§5.10.7). |
| **P4** Denormalised region | D7 |
| **P5** `regionIds`/`asOf` on every request, one reader, never in `lockedFilters`, as-of read-only | D2, D13; `PageResponse.regionIds/bookRegionIds/asOf` and `DashboardDtos.ScopeInfo` are separate from `lockedFilters` |
| **P6** Clock | D5: one `appClock` bean, the `AppClock` bridge for entities and static code, `Today` for dates. The single documented carve-out is protocol time (JWT, webhook skew, `ApiError`) on `WallClock.UTC`. |
| **P7** Existing tests green | Legacy wrappers, additive DTO fields (`@JsonInclude(NON_NULL)`), request records keep old constructors, single-region grants APPROVE/ALL reduce every tier to today's behaviour, MovableClock follows real time, reset additions only |

---

## 3. Data model

### 3.1 Migration routes (P1)

| Change | Route | Why |
|---|---|---|
| New table | `ddl-auto: update` creates it on Postgres and H2-file; `create-drop` creates it in tests. NOT NULL is fine because the table is empty. | — |
| New nullable column on an existing table | `ddl-auto` adds it | Safe under `halt_on_error` |
| New NOT NULL **scalar flag** on an existing table | `@ColumnDefault("…") @Column(nullable = false)`. `ddl-auto` adds it with a default, which fills existing rows. | C-5 |
| New NOT NULL **FK** (`region_id`) on an existing table | Mapped nullable → `RegionSchemaUpgrade` backfills in 1000-id windows → `SchemaSupport.enforceNotNull` | R10, C-5: the FK needs the region row first |
| New constant on an existing `@Enumerated` column | Append last, and call `SchemaSupport.widen(conn, table, column, Enum.class)` in that module's schema-upgrade bean | R9, D12 |
| Rows that need JDBC | Only in `*SchemaUpgrade` beans, which run before `HistoryBaseline` | §3.11 |

Every timestamp written by application code comes from `appClock` or `AppClock` (D5). `Instant` maps to `timestamp(6) with time zone` on both dialects. Money is `numeric(14,2)`. JSON is `@Lob @Column(columnDefinition = "TEXT")`, the `AuditLog` pattern; H2 has no `jsonb`. Identifiers avoid reserved words: no `key`, `value`, `day`, `user`, `year`, `month`, `trigger` or `position`.

### 3.2 `regions` (new): entity `B/region/Region`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `code` | varchar(20) | no | Unique constraint `uk_region_code`. Matches `^[A-Z0-9_-]{2,20}$`. Immutable after create. |
| `name` | varchar(120) | no | Unique ignoring case, checked in the service |
| `active` | boolean | no | Set explicitly by services; no `@Builder.Default` |
| `is_default` | boolean | no | Attribute `defaultRegion`. **Postgres only:** `create unique index if not exists uk_regions_default on regions (is_default) where is_default`. On H2 the invariant is kept by `RegionService`'s bulk updates. |
| `version` | bigint `@Version` | yes | New table, so no `RowVersionUpgrade` entry is needed |
| `created_at` | timestamptz | no | `updatable = false`, from the clock |
| `updated_at` | timestamptz | yes | |

### 3.3 `user_region_grants` (new): entity `B/region/UserRegionGrant`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `user_id` | bigint FK → `users` | no | `@OnDelete(action = CASCADE)`: a hard user delete takes its grants with it |
| `region_id` | bigint FK → `regions` | no | |
| `access_level` | varchar(10), `@Enumerated` `AccessLevel` {READ, MANAGE, APPROVE} | no | widened |
| `record_scope` | varchar(10), `@Enumerated` `RecordScope` {ALL, BOOK} | no | widened |
| `granted_by_user_id` | bigint | yes | no FK |
| `granted_at` | timestamptz | no | |

- Unique constraint `uk_user_region (user_id, region_id)`. Index `idx_urg_region (region_id, access_level)`.
- There is **no** mapping from `User` to its grants: no collection on the principal (C-4).

### 3.4 Changes to existing tables

| Table | Column | Mapping | Migration |
|---|---|---|---|
| `customers`, `invoices`, `payments`, `payment_promises`, `disputes` | `region_id` bigint FK → `regions` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "region_id", updatable = false) Region region` plus `@Index idx_<table>_region (region_id)`. Nullable in the mapping. | `RegionSchemaUpgrade` §3.11 steps 2–4. On `Invoice`, `Payment` and `PaymentPromise`, `@PrePersist` also sets `region = customer.getRegion()` when null, as a safety net. `Dispute` sets it in its service. |
| `users` | `region_backfill_pending` boolean NOT NULL | `@ColumnDefault("true") @Column(nullable = false) boolean regionBackfillPending` (Java default false) | `true` only for rows that predate B1 (F5) |
| `users` | `credentials_set_by_user_id` bigint | nullable, no FK | Set by `CustomerService.create`/`update` when staff set a customer login's password; cleared by `AuthController.change-password` when a user changes their own (BE-14; §5.11.8) |
| `notifications` | `region_id` bigint | nullable, no FK, index `idx_notification_user_region (user_id, region_id)` | Written by every producer that concerns a record; null for the rest (Gmail reconnect, legacy rows). BE-04b. |
| `audit_logs` | `region_id` bigint | nullable, no FK | Written by the region-aware `record` overload (`CUSTOMER_DELETED`, `CUSTOMER_REGION_CHANGED`) |
| `audit_logs` | `origin` varchar(12), `rule_id` bigint, `pending_change_id` bigint (index `idx_audit_pending`), `approved_by_user_id` bigint | nullable, no FK | Stamped from `WriteOrigins.current()` (D4). All four are added in BE-01. |
| `disputes` | `internal` boolean NOT NULL | `@ColumnDefault("false") @Column(nullable = false) @Builder.Default boolean internal = false` | C-5 scalar flag |
| `disputes` | `origin` varchar(12), `rule_id` bigint | nullable | |
| `disputes` | `target_type` gains `CUSTOMER`, appended **last** | `@Enumerated` | `DisputeSchemaUpgrade` → `SchemaSupport.widen(conn, "disputes", "target_type", DisputeTargetType.class)` |

Not changed:
- No region column on `documents`, `emails`, `invoice_items`, `payment_allocations`, `customer_pocs`, `products` or `users`.
- No `@Version` added to payments, promises or disputes, so `RowVersionUpgrade.TABLES` is unchanged. Lost updates on promises and disputes are prevented by pessimistic locks in the canonical order instead (§5.6.6, §5.11.5).

**New small tables** (plain JPA, no FK except where stated, not versioned):

| Table | Columns | Written by |
|---|---|---|
| `customer_region_moves` | `id`, `customer_id` bigint NOT NULL, `from_region_id`, `to_region_id` bigint NOT NULL, `moved_at` timestamptz NOT NULL, `moved_by_user_id`; index `(customer_id, moved_at)` | `CustomerRegionMoveService` (BE-08); read by the gate's cool-down (BE-15) |

### 3.5 History tables (new): package `B/history/versions`

**Common columns** (`@MappedSuperclass VersionRow`):

| Column | Type | Null | Notes |
|---|---|---|---|
| `version_id` | bigint identity | no | The `@Id`. **Never** use `em.find(XVersion.class, …)`. |
| `entity_id` | bigint | no | Mapped to the Java attribute **`id`** (not the `@Id`), so `root.get("id")` and the executor's `id desc` tiebreak are the business id. Verified in the probe. |
| `valid_from` | timestamptz | no | |
| `valid_to` | timestamptz | no | `OPEN = 9999-12-31T00:00:00Z` while current |
| `tx_id` | varchar(36) | no | One UUID per Hibernate transaction |
| `origin` | varchar(12) | no | USER, SYSTEM, AUTOMATION, APPROVAL, SCHEMA or RECONCILE |
| `origin_ref_id` | bigint | yes | Rule id for AUTOMATION; pending-change id for APPROVAL |
| `actor_user_id` | bigint | yes | |

**Rules for every version entity**
1. **No FK, no unique constraint, no CHECK.**
   - Every association is a writable `Long` column attribute (e.g. `customerId`) **plus** a read-only association with the live attribute name, pointing at the live class: `@ManyToOne(fetch = LAZY) @JoinColumn(name = "customer_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)) Customer customer`.
   - Enums use `@Convert(converter = EnumNames.XName.class)`. Unknown strings read back as `null` with a WARN.
   - All business columns are nullable wrapper types.
2. **Column names and lengths equal the live table's.** Attribute names equal the live entity's.
3. **Indexes:** `idx_<t>_entity (entity_id, valid_to)`, `idx_<t>_window (valid_to, valid_from)`, plus the per-table indexes below.
4. **No table name contains a live table name that tests count.** `customer_poc_versions` does not contain `customer_pocs`.

| Table / entity | Business columns (live names; "→ro X" means a read-only association to live X) | Extra indexes |
|---|---|---|
| `customer_versions` / `CustomerVersion` | `name` v150, `phone` v30, `email` v120, `address` v500, `credit_balance` n(14,2), `payment_term` v20, `created_at`, `region_id` →ro `region` | `(region_id, valid_to)` |
| `invoice_versions` / `InvoiceVersion` | `invoice_number` v40, `customer_id` →ro `customer`, `invoice_date`, `due_date` date, `payment_term` v20, `total`, `paid_amount` n(14,2), `status` v30, `notes` v500, `sales_poc_user_id` →ro `salesPoc` (User), `created_at`, `region_id` →ro `region`, `items_json` TEXT | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(sales_poc_user_id, valid_to)` |
| `payment_versions` / `PaymentVersion` | `customer_id` →ro `customer`, `amount`, `credit_applied` n(14,2), `method` v40, `notes` v300, `paid_at`, `collection_poc_user_id` →ro `collectionPoc`, `status` v20, `region_id` →ro `region` | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(collection_poc_user_id, valid_to)` |
| `payment_allocation_versions` / `PaymentAllocationVersion` | `payment_id` →ro `payment`, `invoice_id` →ro `invoice`, `amount` n(14,2) | `(payment_id, valid_to)`, `(invoice_id, valid_to)` |
| `promise_versions` / `PromiseVersion` | `customer_id` →ro `customer`, `amount`, `fulfilled_amount`, `promised_date`, `collection_poc_user_id` →ro `collectionPoc`, `notes` v1000, `status` v20, `status_overridden`, `override_reason` v500, `overridden_by_user_id`, `overridden_at`, `broken_notified_at`, `created_by_user_id`, `created_at`, `updated_at`, `region_id` →ro `region` | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(collection_poc_user_id, valid_to)` |
| `dispute_versions` / `DisputeVersion` | `customer_id` (plain `Long customerId`, as live), `opened_by_user_id`, `target_type` v20, `target_id`, `reason` v2000, `proposed_change_json` TEXT, `status` v20, `admin_notes` v2000, `resolved_by_user_id`, `resolved_at`, `created_at`, `updated_at`, `region_id` →ro `region`. **BE-19 adds** `internal`, `origin` v12 and `rule_id`, in the same slice as the live attributes (critic finding CF-11). | `(customer_id, valid_to)`, `(region_id, valid_to)` |
| `customer_poc_versions` / `CustomerPocVersion` | `customer_id` →ro `customer`, `user_id` →ro `user` (LAZY), `poc_type` v20, `is_primary` (attribute `primary`), `created_by_user_id`, `created_at`. `entity_id` is the seat id. | `(customer_id, valid_to)`, `(user_id, valid_to)` |
| `product_versions` / `ProductVersion` | `name` v150, `description` v500, `price` n(14,2), `active`, `created_at` | — |
| `task_versions` / `TaskVersion` (added in BE-12) | every `tasks` column in §3.7 except `id` and `version`; `region_id` →ro `region`; `customer_id` plain `Long customerId` (as live) | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(entity_type, entity_id, valid_to)` |
| `entity_link_versions` / `EntityLinkVersion` | `kind` v30 NOT NULL (`PROMISE_INVOICE`, `PROMISE_PAYMENT`), `owner_id` bigint NOT NULL (attribute `ownerId`), `target_id` bigint NOT NULL (attribute **`id`**) | `(kind, owner_id, valid_to)`, `(kind, target_id, valid_to)`, window |
| `history_meta` / `HistoryMeta` | `id` smallint PK (always 1), `started_at` timestamptz null, `baseline_completed_at` timestamptz null | — |
| `history_transactions` / `HistoryTransactionRow` (BE-11) | `id` bigint identity PK, `tx_id` v36 NOT NULL, `instant` timestamptz NOT NULL. **No unique constraint** (the UUID is unique by construction) and no FK. | `idx_history_tx (tx_id)` |

**Stored but excluded from comparison:** `updated_at` (promise, dispute, task) and `broken_notified_at`. The `@Version` column is **not stored**.

**`items_json` format.** A JSON array sorted by item id, written with Spring's `ObjectMapper`:
- `[{"id":90,"productId":3,"quantity":1,"unitPrice":"1200.00","lineTotal":"1200.00"}]`
- It holds only the item's **own** columns. `InvoiceItem` has no product-name column, so no name is stored: a product rename must not make every invoice using it look drifted to the reconciler (critic finding CF-4). The name at T comes from `product_versions` (§5.7.15).
- Money values are strings scaled with `setScale(2, HALF_UP)`, so string equality equals state equality.
- Java type: `record ItemSnapshot(Long id, Long productId, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal)`.

### 3.6 Automation tables (new): packages `B/automation/**`

**`automation_events`**: the outbox. **No FK, no unique constraint, no enum or CHECK types**, which `OutboxSchemaTest` asserts. Every **fitted** string is cut to its column before insert (P2, R5). **Identifier lists are never fitted**: they are TEXT, and the API bounds them (critic findings PC-12, CF-20, SC-20).

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `kind` | v20 | no | CREATED, UPDATED, CHILDREN, SCHEDULED, MANUAL, REPLAY |
| `entity_type` | v20 | yes | CUSTOMER, INVOICE, PAYMENT (entity events; CUSTOMER for CHILDREN) |
| `entity_id` | bigint | yes | |
| `history_tx_id` | v36 | yes | The publishing transaction's history `tx_id` (entity events; D10). Never fitted: a UUID is 36 characters. |
| `rule_id`, `rule_version` | bigint | yes | SCHEDULED, MANUAL and REPLAY |
| `record_ids` | TEXT | yes | MANUAL: comma-joined, at most 50, validated as positive longs at the API; **never fitted** |
| `region_ids` | TEXT | yes | MANUAL: the requester's `ViewScope.regionIds`, comma-joined (null = all) |
| `changes` | v300 | yes | Comma-joined labels (§5.8.3), fitted |
| `origin` | v12 | yes | USER, SYSTEM, AUTOMATION, APPROVAL |
| `caused_by_rule_id` | bigint | yes | |
| `depth` | int | no | |
| `actor_user_id` | bigint | yes | |
| `scheduled_for`, `occurred_at` | timestamptz | `occurred_at` no | `scheduled_for`: the slot (SCHEDULED), the requested run time (MANUAL); REPLAY: the replay window start |
| `status` | v12 | no | PENDING, PROCESSING, DONE, DEAD |
| `attempts`, `claims` | int | no | `claims` is the fencing token |
| `claimed_by` | v80 | yes | |
| `lease_until`, `next_attempt_at` | timestamptz | yes | A MANUAL event with `runAt` is inserted with `next_attempt_at = runAt` |
| `cursor_id` | bigint | yes | Checkpoint for sliced set runs (and REPLAY: the last replayed event id) |
| `last_error` | v1000 | yes | fitted |
| `processed_at`, `updated_at` | timestamptz | yes | |

There is **no coalescing** (D10), so there is no COALESCED status and no `coalesced_into`.

Indexes: `idx_automation_event_due (status, next_attempt_at)`, `idx_automation_event_entity (entity_type, entity_id, status)`, `idx_automation_event_rule (rule_id, status)`, `idx_automation_event_occurred (entity_type, occurred_at)` (resume counts and REPLAY).

**`automation_rules`**

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `name` | v120 | no | Unique among non-deleted rules, case-insensitive, checked in the service |
| `description` | v500 | yes | |
| `owner_user_id` | bigint | no | No FK |
| `enabled` | boolean | no | |
| `active_since` | timestamptz | yes | Entity events with `occurred_at < active_since` are ignored |
| `suspended_reason` | v30, `@Enumerated` `SuspendReason` {OWNER_INACTIVE, OWNER_NOT_STAFF, OWNER_LACKS_RIGHTS, INVALID_DEFINITION, RATE_LIMITED} | yes | widened |
| `suspended_detail` | v500 | yes | |
| `suspended_at` | timestamptz | yes | |
| `trigger_kind` | v30, `@Enumerated` `TriggerKind` {ENTITY_CREATED, ENTITY_UPDATED, ENTITY_CREATED_OR_UPDATED, SCHEDULE_DAILY, SCHEDULE_WEEKLY, MANUAL} | no | widened. `MANUAL` never fires automatically; the rule runs only from "Run now" (critic finding PC-6). |
| `entity_type` | v20, `@Enumerated` `TriggerEntity` {CUSTOMER, INVOICE, PAYMENT} | no | widened |
| `schedule_time` | v5 `HH:mm` | yes | |
| `schedule_day` | v9 `DayOfWeek` | yes | |
| `schedule_zone` | v40 | no | Default `app.automation.default-zone` |
| `catch_up` | v4 | no | `ONE` (default) or `ALL`: after missed slots, one catch-up run or one run per missed slot (at most `app.automation.max-catch-up-slots`, 31). Plain string, set by the service. |
| `next_run_at`, `last_scheduled_for` | timestamptz | yes | |
| `condition_json` | TEXT | yes | Null means every record |
| `actions_json` | TEXT | no | |
| `max_records_per_run` | int | no | 1..5000 |
| `last_run_id`, `last_run_at`, `last_run_status` | bigint, timestamptz, v20 `@Enumerated RunStatus` | yes | Written by JPQL; no version bump |
| `created_by_user_id`, `updated_by_user_id` | bigint | yes | |
| `created_at`, `updated_at` | timestamptz | no | |
| `deleted_at` | timestamptz | yes | Soft delete |
| `version` | bigint `@Version` | yes | Only user edits go through the entity |

Indexes: `idx_automation_rule_trigger (entity_type, trigger_kind, enabled)`, `idx_automation_rule_next_run (next_run_at)`.

**`automation_rule_regions`** (`@ElementCollection Set<Long> regionIds` on `AutomationRule`, `@CollectionTable(name = "automation_rule_regions", joinColumns = @JoinColumn(name = "rule_id"))`, column `region_id` bigint). An optional region restriction (critic finding PC-8). **Empty means "every region where the owner can act"**. Index `idx_rule_region (region_id)`.

**`automation_rule_revisions`** (renamed, D8)

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `rule_id` | bigint | no | No FK |
| `rule_version` | bigint | yes | |
| `snapshot_json` | TEXT | no | `RuleDto` without runtime fields (it includes `regionIds`) |
| `name` | v120 | no | Denormalised for as-of filtering |
| `entity_type` | v20 | no | Denormalised |
| `trigger_kind` | v30 | no | Denormalised |
| `owner_user_id` | bigint | yes | |
| `enabled` | boolean | no | |
| `suspended_reason` | v30 string | yes | |
| `deleted` | boolean | no | |
| `saved_by_user_id` | bigint | yes | |
| `saved_at` | timestamptz | no | |

Index `(rule_id, saved_at)`. A row is written in the same transaction as every create, update, enable, disable, suspend, resume, delete and ownership change.

**`automation_runs`**

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `rule_id`, `event_id` | bigint | no | No FK |
| `rule_version` | bigint | yes | |
| `trigger_type` | v20 | no | CREATED, UPDATED, CHILDREN, SCHEDULED, MANUAL, REPLAY |
| `entity_type`, `entity_id` | v20, bigint | yes | |
| `requested_by_user_id` | bigint | yes | |
| `scheduled_for` | timestamptz | yes | |
| `status` | v20 `@Enumerated RunStatus` {QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, SKIPPED, SUPERSEDED} | no | widened |
| `matched`, `done`, `skipped`, `failed` | int | no | |
| `truncated` | boolean | no | |
| `message` | v1000 | yes | |
| `created_at` | timestamptz | no | |
| `started_at`, `finished_at` | timestamptz | yes | |

Unique constraint **`uk_automation_run (rule_id, event_id)`**. Index `idx_automation_run_rule (rule_id, created_at)`.

**`automation_effects`**

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `run_id`, `rule_id`, `event_id` | bigint | no | No FK |
| `action_key` | v36 | no | Stable UUID of the action |
| `action_type` | v20 | no | |
| `target_type`, `target_id` | v20, bigint | no | |
| `target_label` | v200 | yes | |
| **`dedupe_key`** | v160 | yes | **Unique `uk_automation_effect_dedupe`.** Set only on DONE rows. Many NULLs are allowed on H2 and PG (probed). |
| `status` | v12 `@Enumerated EffectStatus` {DONE, SKIPPED, FAILED} | no | widened |
| `result_type` | v20 | yes | TASK, PROMISE, DISPUTE, EMAIL, PENDING_CHANGE |
| `result_id` | bigint | yes | |
| `message`, `warnings` | v1000 each | yes | |
| `created_at` | timestamptz | no | |

Every string written to `automation_runs` and `automation_effects` (`message`, `warnings`, `target_label`) goes through the one helper `B/common/Fit.to(String, int)` in the entity factories (`AutomationEffect.of(...)`, `AutomationRun.finish(...)`), so a long customer name can never fail an action transaction (critic finding SC-20).

- Indexes: `idx_automation_effect_run (run_id)`, `idx_automation_effect_rule (rule_id, created_at)`, `idx_automation_effect_target (target_type, target_id)`.
- Dedupe key: `r{ruleId}:{actionKey}:{TARGET_TYPE}:{targetId}` for ONCE_PER_RECORD; the same with `:e{eventId}` appended for EVERY_TIME.

### 3.7 Tasks and assignees (new)

**`tasks`**: entity `B/task/Task`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `title` | v200 | no | `FieldLimits.TASK_TITLE` |
| `description` | v2000 | yes | `FieldLimits.TASK_DESCRIPTION` |
| `status` | v20 `@Enumerated TaskStatus` {OPEN, IN_PROGRESS, DONE, CANCELLED} | no | widened |
| `due_date` | date | yes | |
| `entity_type` | v20 `@Enumerated TaskEntityType` {CUSTOMER, INVOICE, PAYMENT} | no | widened |
| `entity_id` | bigint | no | |
| `entity_label` | v200 | no | snapshot |
| `entity_deleted` | boolean | no | `@Builder.Default false` |
| `customer_id` | bigint | no | Plain `Long`, no FK (Dispute style) |
| `region_id` | bigint FK → `regions` | **no** | New table. `@ManyToOne(LAZY, optional = false) @JoinColumn(updatable = false) Region region` |
| `origin` | v12 | no | USER or AUTOMATION |
| `rule_id`, `run_id` | bigint | yes | |
| `created_by_user_id` | bigint | yes | |
| `completed_at`, `completed_by_user_id`, `cancelled_at` | | yes | |
| `created_at`, `updated_at` | timestamptz | no | from the clock |
| `version` | bigint `@Version` | yes | |

Indexes: `idx_task_entity (entity_type, entity_id)`, `idx_task_customer (customer_id)`, `idx_task_status_due (status, due_date)`, `idx_task_rule (rule_id, entity_type, entity_id, status)`, `idx_task_region (region_id)`.

**`record_assignees`**: shared by TASK, DISPUTE and PROMISE; **not versioned** (D8)

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `owner_type` | v10 | no | TASK, DISPUTE, PROMISE (strings, no CHECK) |
| `owner_id` | bigint | no | No FK |
| `kind` | v4 | no | USER or ROLE |
| `user_id` | bigint | yes | |
| `role` | v30 | yes | `EmailRole` name |
| `role_level` | v10 | yes | CUSTOMER or RECORD |
| `sort_order` | int | no | |
| `added_at` | timestamptz | no | |
| `added_by_user_id` | bigint | yes | |
| `removed_at` | timestamptz | yes | Null while active. Rows are never deleted. |
| `removed_by_user_id` | bigint | yes | |

- Indexes: `idx_record_assignee_owner (owner_type, owner_id)`, `idx_record_assignee_user (user_id)`.
- Uniqueness among active rows is enforced in `AssigneeStore.replace` under the owner row's lock (§5.9.1).

### 3.8 Maker-checker tables (new): package `B/approval`

**`pending_changes`**: **no FK** except `region_id`. **Not versioned** (D8).

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `operation` | v40, converter `ApprovalOperation` | no | No CHECK |
| `status` | v20, converter `PendingStatus` {PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, FAILED, CLOSED} | no | |
| `entity_type` | v30 | no | INVOICE, PAYMENT, PROMISE, DISPUTE, or REGION (THRESHOLD_CHANGE) |
| `entity_id` | bigint | yes | Null for creates; the region id for THRESHOLD_CHANGE |
| `entity_label` | v80 | yes | fitted |
| `affected_type`, `affected_id` | v30, bigint | yes | The record whose money state changes: the entity itself, or the dispute's **target** for DISPUTE_APPROVE. Null for creates and THRESHOLD_CHANGE. |
| `customer_id` | bigint | yes | Null only for THRESHOLD_CHANGE |
| `customer_name` | v150 | yes | snapshot, fitted; null only for THRESHOLD_CHANGE |
| `internal_source` | boolean | no | `@Builder.Default false`. True when the change came from an **internal** (automation) dispute. Customer logins never see such rows (D17, critic finding SC-1). New table, so no `@ColumnDefault` is needed. |
| `region_id` | bigint FK → `regions` | no | `@ManyToOne(LAZY, optional = false) @JoinColumn(updatable = false)`. Never changes: a region move either is refused while the row is PENDING or closes it (D7). |
| `record_poc_user_id` | bigint | yes | |
| `amount`, `threshold_amount` | n(14,2) | no | `amount` is the change's own measure; the summary says when the hold came from the cumulative window. THRESHOLD_CHANGE stores 0 and 0. |
| `summary` | v300 | no | fitted |
| `payload_json` | TEXT | no | Sanitised: every `*password*` key is removed recursively |
| `payload_version` | int | no | 1 |
| `base_json`, `proposed_json` | TEXT | yes | |
| `outstanding_key` | v60 | yes | **Unique `uk_pending_outstanding`.** `"<ENTITY>:<id>"` while PENDING on an existing record (`"REGION_THRESHOLDS:<regionId>"` for THRESHOLD_CHANGE), otherwise null. Many NULLs are allowed (probed). |
| `origin` | v12 (converter) | no | USER, AUTOMATION |
| `rule_id`, `automation_event_id` | bigint | yes | |
| `maker_user_id` | bigint | no | |
| `requested_at` | timestamptz | no | From the clock, never `@PrePersist` |
| `decided_by_user_id` | bigint | yes | |
| `decided_at` | timestamptz | yes | Set on **every** move to a final status |
| `decision_note`, `failure_reason` | v500 | yes | fitted |
| `superseded_by_id`, `result_entity_id` | bigint | yes | |
| `result_json` | TEXT | yes | |
| `version` | bigint `@Version` | yes | |

Indexes: `idx_pending_status_region (status, region_id)`, `idx_pending_entity (entity_type, entity_id)`, `idx_pending_affected (affected_type, affected_id)`, `idx_pending_customer (customer_id, status)`, `idx_pending_maker (maker_user_id, status)`, `idx_pending_requested (requested_at)`, `idx_pending_decided (decided_at)`.

Every string is set through `Fit.to` in `PendingChange.pending(...)` and the decision methods, so a 150-character customer name can never fail the maker's held save (critic finding SC-20).

**`approval_thresholds`**

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `region_id` | bigint FK → `regions` | no | |
| `operation` | v40 (converter) | no | One of the eight amount-gated operations (never THRESHOLD_CHANGE) |
| `amount` | n(14,2) | no | ≥ 0, whole cents |
| `window_hours` | int | no | 0..720, default 24. The cumulative window (§5.11.3); 0 measures each change alone. New table, so plain NOT NULL. |
| `updated_by_user_id` | bigint | yes | |
| `updated_at` | timestamptz | no | |

Unique constraint `uk_threshold_region_op (region_id, operation)`. No row means not gated. Nothing is seeded, but **a new region starts with a copy of the default region's rows** (critic finding SC-4; §5.11.11).

**`approval_usage`** (append-only, no FK, not versioned; critic finding SC-9)

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `maker_user_id`, `region_id`, `customer_id` | bigint | no | |
| `operation` | v40 (converter) | no | |
| `amount` | n(14,2) | no | The applied change's measure |
| `used_at` | timestamptz | no | from the clock |

Index `idx_approval_usage (maker_user_id, region_id, operation, customer_id, used_at)`. Written only on the **applied, unapproved** path of a gated method when a threshold row with `window_hours > 0` exists. Rows older than 30 days (the largest window) are deleted by `ApprovalHousekeeping`, a daily `@Scheduled` job in `B/approval` (one of the spare scheduler threads, §5.15).

### 3.9 Enum columns and widening (R9, D12)

| Table.column | Enum | New constant(s) | Widen call lives in |
|---|---|---|---|
| `emails.status` (existing) | `EmailStatus` | — | `EmailSchemaUpgrade` (now delegates to `SchemaSupport.widen`; the H2 ENUM branch fixes a latent `azurefree` bug) |
| `disputes.target_type` | `DisputeTargetType` | `CUSTOMER` (last) | `DisputeSchemaUpgrade` (new, BE-19) |
| `user_region_grants.access_level` / `.record_scope` | `AccessLevel` / `RecordScope` | — (new table) | `RegionSchemaUpgrade` (registered for future constants) |
| `tasks.status` / `.entity_type` | `TaskStatus` / `TaskEntityType` | — | `TaskSchemaUpgrade` (new, BE-12) |
| `automation_rules.trigger_kind` / `.entity_type` / `.suspended_reason` / `.last_run_status`, `automation_runs.status`, `automation_effects.status` | as named | `TriggerKind.MANUAL` is appended last from the start (new table) | `AutomationSchemaUpgrade` (new, BE-16/BE-17) |
| `pending_changes.*`, `approval_thresholds.operation`, all `*_versions` enums | converters | n/a | none needed |

Not widened, because no value is added: `emails.entity_type`, `documents.entity_type`, `emails.from_role`, `email_recipients.delivery_status`. Tasks are not email or document parents in this release (§10).

### 3.10 `SchemaSupport` (`B/common/schema/SchemaSupport.java`, public)

```java
public static String product(Connection c)                                   // "postgresql" | "h2" | other
public static boolean isNullable(Connection c, String table, String column)  // information_schema; upper-case + schema() on H2, current_schema() on PG
public static boolean enforceNotNull(Connection c, String table, String column) // "alter table T alter column C set not null" (same SQL on both, verified on H2);
                                                                             //  skipped + WARN when any NULL remains; returns true if enforced
public static void widen(Connection c, String table, String column, Class<? extends Enum<?>> type)
        // PG: replace the stale CHECK (existing logic).
        // H2: (a) existing CHECK logic; (b) NEW: if data_type = 'ENUM' and the enum_values of the column lack a constant
        //     → "alter table T alter column C set data type enum('A','B',...)"   (NOT NULL is preserved; probe verified)
```

`InvoiceSchemaUpgrade.isNullable` and `enforceDueDateNotNull`, `EmailSchemaUpgrade.widen`, and every new upgrade bean delegate here.

### 3.11 Schema-upgrade beans and startup order

Startup order:
1. Hibernate DDL (`ddl-auto`).
2. `InitializingBean` upgrade beans, in any order. Each takes `(DataSource, …, EntityManagerFactory schemaUpToDate)`, runs each step in its own `try`, commits when autocommit is off, and logs WARN and continues on `SQLException`.
3. `HistoryBaseline` (a `SmartInitializingSingleton`, after all `InitializingBean`s).
4. Web server.
5. `DataSeeder` (`CommandLineRunner`).
6. `ApplicationReadyEvent`: `InvoiceNumbers.ensureSequenceRow`, `PromiseSweepScheduler.sweepOnStartup`.

**`RegionSchemaUpgrade`** (BE-02), steps in order:
1. **Default region.**
   - If `select id from regions where is_default = true` returns nothing, insert `('DEFAULT','Default region',true,true,0,now)`.
   - On `SQLException` (a race on `uk_region_code`), re-select by code, then run `update regions set is_default = true where code='DEFAULT' and not exists (select 1 from regions where is_default)`.
   - On PG, create `uk_regions_default`.
2. **Customers:** `update customers set region_id = ? where region_id is null and id between ? and ?`, in windows of 1000.
3. **Children:** `update <t> set region_id = (select c.region_id from customers c where c.id = <t>.customer_id) where region_id is null and id between ? and ?` for `invoices`, `payments`, `payment_promises` and `disputes`. Then `update disputes set region_id = ? where region_id is null` for orphans.
4. **Enforce NOT NULL:** `SchemaSupport.enforceNotNull` on the five tables. With any NULL left it logs WARN and retries next boot.
5. **Legacy grants:**
   ```sql
   insert into user_region_grants (user_id, region_id, access_level, record_scope, granted_at)
     select u.id, ?, 'APPROVE', 'ALL', ? from users u
      where u.region_backfill_pending = true and u.customer_id is null
        and not exists (select 1 from user_region_grants g where g.user_id = u.id and g.region_id = ?)
   ```
   then `update users set region_backfill_pending = false where region_backfill_pending = true`, and commit.
6. `widen` on `access_level` and `record_scope`.
7. If any rows changed, write the audit row `("REGION", 0, "REGIONS_BACKFILLED", counts, reason)`.

Other beans:
- `TaskSchemaUpgrade` (BE-12), `AutomationSchemaUpgrade` (BE-16): widen calls only.
- `DisputeSchemaUpgrade` (BE-19): the widen call, then `update dispute_versions set internal = false where internal is null` (the versioned-column backfill contract below; BE-19 adds `internal` to both tables).
- `HistoryBaseline` (BE-08): §5.7.9.
- **Contract for any future JDBC upgrade that backfills a versioned column:** apply the same backfill to `<x>_versions`. Because B1 lands before history in the slice order (§9), `region_id` is on every version table from the start and needs no backfill. The one versioned column added after history is `disputes.internal` (BE-19), whose `@ColumnDefault("false")` fills only the live table; `DisputeSchemaUpgrade` therefore sets `dispute_versions.internal = false where null`, and `HistoricalDispute` also maps a null to false.

### 3.12 Test reset order (`T/IntegrationTestBase.resetTransactionalData`)

```
SecurityContextHolder.clearContext(); ViewScopeHolder.clear(); WriteOrigins.clear(); MovableClock.SHARED.reset();
AppClock.set(MovableClock.SHARED);                                                   // BE-01: a drift boot may have replaced it (D5)
mailTransport.reset(); automationFaults.reset(); historyFaults.reset();              // faults from BE-17 / BE-07
historyTestSupport.clearVersions();                                                  // BE-07: bulk delete of every *_versions, entity_link_versions, history_meta;
                                                                                     //        BE-11 adds history_transactions
pendingChangeRepository.deleteAll(); approvalThresholdRepository.deleteAll();        // BE-13 (first: a stray threshold would gate other tests)
approvalUsageRepository.deleteAll();                                                 // BE-15
recordAssigneeRepository.deleteAll(); taskRepository.deleteAll();                    // BE-12
automationEffectRepository…, automationRunRepository…, automationEventRepository…,
automationRuleRevisionRepository…, automationRuleRepository.deleteAll();             // BE-11/16/17 (rule regions cascade with the rule)
customerRegionMoveRepository.deleteAll();                                            // BE-08
gmailConnection → document → emailRecipient → email → promise → payment → invoice → customerPoc → notification   (existing)
disputeRepository.deleteAll();                                                       // NEW, before customers (disputes leak today)
customerRepository.deleteAll(); productRepository.deleteAll();
users except admin/cashier deleted (grants cascade); inactive survivors re-activated  (existing)
grantRepository.deleteByRegionCodeNot("DEFAULT"); regionRepository.deleteByCodeNot("DEFAULT");   // BE-02
DEFAULT region: active = true, is_default = true;
admin, cashier grants := exactly DEFAULT/APPROVE/ALL                                              // BE-02
```

- `audit_logs`, `roles`, `privileges` and `invoice_number_sequence` are still not reset.
- Each repository delete is added by the slice that introduces its table.
- Grants are deleted before their regions, and regions after everything that references them (tasks, pending changes, thresholds, customers).
- **The dispute delete is new** and must precede customers.

---

## 4. Privileges and rights

### 4.1 Full catalogue (40 privileges)

`B/privilege/Privileges.java` gains these constants and appends them **to `ALL`** (R11: `pickByNames` silently drops unknown names):
- `REGION_VIEW`, `REGION_MANAGE`
- `TASK_VIEW`, `TASK_MANAGE`
- `AUTOMATION_VIEW`, `AUTOMATION_MANAGE`, `AUTOMATION_ADMIN`
- `CHANGE_APPROVE`, `APPROVAL_THRESHOLD_MANAGE`

Each is added by the slice that first needs it (§9).

| Privilege | Class | Minimum grant level | Seeded to |
|---|---|---|---|
| USER_VIEW, USER_MANAGE | global | — | ADMIN |
| ROLE_VIEW, ROLE_MANAGE | global | — | ADMIN |
| PRODUCT_VIEW | global | — | ADMIN, CASHIER, VIEWER, SALES_POC, SUCCESS_POC |
| PRODUCT_MANAGE | global | — | ADMIN |
| NOTIFICATION_VIEW | global | — | all seeded roles |
| EXPORT_DATA | global | — | ADMIN, CASHIER, POC roles |
| **REGION_VIEW, REGION_MANAGE** | global | — | ADMIN |
| **AUTOMATION_VIEW, AUTOMATION_MANAGE** | global | — | ADMIN, CASHIER, SALES_POC, CUSTOMER_SUCCESS_POC, COLLECTION_POC |
| **AUTOMATION_ADMIN** | global | — | ADMIN |
| CUSTOMER_VIEW, INVOICE_VIEW, PAYMENT_VIEW, PROMISE_VIEW, DISPUTE_VIEW, AUDIT_VIEW, POC_VIEW, EMAIL_VIEW, DOCUMENT_VIEW | regional | READ | as today |
| SCOPE_OVERRIDE | regional | READ, **and** grant scope ALL | as today |
| POC_ASSIGNABLE_SALES / _SUCCESS / _COLLECTION | regional | READ | as today |
| **TASK_VIEW** | regional | READ | ADMIN, CASHIER, VIEWER, the three POC roles |
| CUSTOMER_MANAGE, INVOICE_MANAGE, PAYMENT_MANAGE, PROMISE_MANAGE, PROMISE_OVERRIDE, DISPUTE_MANAGE, DISPUTE_CREATE, POC_ASSIGN, EMAIL_SEND, DOCUMENT_MANAGE | regional | MANAGE | as today |
| **TASK_MANAGE** | regional | MANAGE | ADMIN, CASHIER, the three POC roles |
| **CHANGE_APPROVE** | regional | APPROVE | ADMIN |
| **APPROVAL_THRESHOLD_MANAGE** | regional | APPROVE | ADMIN |

Totals: 13 global, 14 READ, 11 MANAGE, 2 APPROVE.

In code:
```java
public static final Set<String> GLOBAL = Set.of(USER_VIEW, USER_MANAGE, ROLE_VIEW, ROLE_MANAGE, PRODUCT_VIEW, PRODUCT_MANAGE,
        NOTIFICATION_VIEW, EXPORT_DATA, REGION_VIEW, REGION_MANAGE, AUTOMATION_VIEW, AUTOMATION_MANAGE, AUTOMATION_ADMIN);
public static final Map<String, AccessLevel> REGION_LEVEL = Map.ofEntries(/* the READ / MANAGE / APPROVE rows above */);
public static AccessLevel requiredLevel(String p)   // null ⇒ global; a name in neither map ⇒ MANAGE (fail-safe)
public static boolean isRegional(String p)          // !GLOBAL.contains(p)
```

`AccessLevel` lives in `B/region`. `Privileges` imports it, and nothing in `region` imports `privilege` except `Privileges` constants. There is no cycle.

**Seeding** (`B/config/DataSeeder.java`):
- ADMIN gets everything through `upsertRole(ALL)`.
- The CASHIER and VIEWER lists change in their `upsertRole` calls (lines 56–77).
- The three POC roles get their new privileges through `createRoleIfAbsent` lists. These are added on the boot where the privilege is created, so existing databases pick them up once.
- CUSTOMER gets nothing new.
- `DataSeeder` also runs `regionGrantService.ensure(user, regionService.defaultRegion(), APPROVE, ALL)` when it creates `admin` or `cashier`.

### 4.2 Effective rights

```
effective(p, R) (staff)    = role ∋ p ∧ ( global(p)
                                          ∨ ( grant(R) exists ∧ grant(R).level ≥ requiredLevel(p)
                                              ∧ (p ≠ SCOPE_OVERRIDE ∨ grant(R).scope = ALL) ) )
effective(p, R) (customer) = role ∋ p ∧ R = ownCustomer.region
```

- **Invariant I1:** `child.region_id = customer.region_id` for invoices, payments, promises, disputes, tasks and pending changes.
- **Invariant I2:** grants only narrow the role. A forgotten region check can never exceed "role ∧ held somewhere".
- **Invariant I3:** `regionIds` narrows **sets** only: lists, counts, tiles, exports, bulk "select all", dashboards, pickers and email bulk. By-id reads and single-record writes are authorised against the record's own region, so deep links from notifications, tasks and audit keep working.
- **Why READ-level checks stay unchanged.** For a record in region R that the caller can see, visibility already needs a grant ≥ READ in R, so `effective(p, R) = role ∋ p` for every READ-level `p` except SCOPE_OVERRIDE. The existing `@PreAuthorize(X_VIEW)`, `canSeePoc()` and its 14 callers, `AuditController.ensureCallerCanSee`, `DocumentTargets.requireVisible`, `EmailTargets.requireTypeAccess` and `TableSchemaController.mayRead` all stay as they are. **Only MANAGE and APPROVE checks need the record's region** (§5.6.6).

### 4.3 `RegionRights` (`B/region/RegionRights.java`): immutable plain data, safe on a detached principal

```java
public static RegionRights none();
public static RegionRights forStaff(Set<String> rolePrivileges, Collection<GrantRow> grants);
public static RegionRights forCustomer(Set<String> rolePrivileges, Long customerRegionId);
public boolean has(String p, Long regionId);      // formula §4.2; regionId == null ⇒ false for regional p
public boolean anywhere(String p);                // global ⇒ role ∋ p; else ∃ granted r: has(p, r)
public Set<Long> regionsWith(String p);           // regional p only; IllegalArgumentException for a GLOBAL p
public Set<Long> grantedRegions();                // staff: grant keys; customer: {own}
public boolean holdsAnyIn(Long regionId);
public Optional<GrantRow> grantIn(Long regionId);
public Set<String> effectiveAnywhere();           // flat authorities and /me.privileges
public Set<String> effectiveIn(Long regionId);    // /me.regions[].privileges
public boolean isCustomer();
```

`record GrantRow(Long regionId, AccessLevel level, RecordScope scope)` is top-level, because it is a JPQL constructor target.

### 4.4 `CurrentUser` additions (`B/auth/CurrentUser.java`)

- Existing methods are unchanged. `has(p)` now reads the union authorities.
- `canAssignPoc(UserRepository)` is **deleted**, and the compiler lists its callers.

```java
public RegionRights rights();                                   // principal's, or RegionRights.none() (fails closed, C-8)
public boolean hasIn(String privilege, Long regionId);
public Set<Long> regionsWith(String privilege);
public void requireIn(String privilege, Long regionId);         // AccessDeniedException("Not allowed") → 403
public void requireReachable(Long regionId, String notFound);   // !rights().holdsAnyIn(r) → NotFoundException → 404
public boolean canAssignPocIn(Long regionId);                   // !isCustomer() && hasIn(POC_ASSIGN, r)
public boolean canAssignPocAnywhere();                          // replaces canAssignPoc(UserRepository)
```

### 4.5 `RegionAccessQueries` (`B/region`): questions about *other* users, answered from the database

```java
public boolean holds(Long userId, String privilege, Long regionId);   // GLOBAL → userRepository.hasPrivilege; SCOPE_OVERRIDE → IllegalArgumentException
public Set<Long> withAccess(Collection<Long> userIds, Long regionId); // grant ≥ READ in region; empty in ⇒ empty out
public List<User> holdersIn(String privilege, Long regionId);         // active internal users; approvers and notifications
```

These are backed by `UserRegionGrantRepository` (§5.6.3). Every caller short-circuits empty collections, so no query ever has `in ()`.

### 4.6 Backfill of existing users

- `RegionSchemaUpgrade` step 5 gives every pre-B1 staff user (`region_backfill_pending = true`) a DEFAULT/**APPROVE/ALL** grant, which means "the role alone decides", i.e. today's behaviour.
- The per-user flag is then cleared. A user later left deliberately with no grants is never re-granted (F6).
- **`POST /api/users` without `regionGrants` gives no grants** (from BE-05; this reverses F8, critic finding SC-5). The frontend always sends the field. The only baseline test that posts a user (`CustomerFieldHygieneTest`) asserts only the username trim, so P7 holds.
  - *Bridge, BE-02 to BE-04 only:* until `UserAccountService` lands in BE-05, `UserController.create` calls `regionGrantService.ensure(saved, regionService.defaultRegion(), APPROVE, ALL)` for non-customer roles, which is today's behaviour, so users created in between are not left blind (critic finding CF-19).
- **Nobody grants more than they hold** (SC-5). In `RegionGrantService.replace` and `UserAccountService.create`, the granter may add, change or remove a row in region R only when the granter's own grant in R is at least both the old and the new level, and has scope ALL when either row has scope ALL. From BE-13 (when the privilege exists), granting APPROVE also needs `CHANGE_APPROVE` in R. Rows the granter cannot touch must come back unchanged; otherwise 403. The seeder, fixtures and region-create auto-grant use `ensure`, which is not bounded.
- From BE-13, when a user **gains APPROVE** in a region, the region's other `CHANGE_APPROVE` holders are notified (`notifyHolders`, type `APPROVER_ADDED`). The audit row names the granter from BE-05.
- Customer logins get no grants.
- With one DEFAULT region and APPROVE/ALL grants, every tier reduces to today's. That is P7 for all existing tests.

### 4.7 Login and `/me`

- `LoginResponse.UserInfo` gains `List<RegionAccessDto> regions`. `privileges` becomes `rights.effectiveAnywhere()`, identical to today for backfilled users.
- Region names come from one `regionRepository.findAllById(rights.grantedRegions())`.
- The JWT is unchanged.
- Shape in §6.2.

### 4.8 Frontend mirror

- `F/shared/models/privileges.dart` gains the nine constants, spelled exactly as the backend: `regionView`, `regionManage`, `taskView`, `taskManage`, `automationView`, `automationManage`, `automationAdmin`, `changeApprove`, `approvalThresholdManage`.
- `CurrentUser` gains `regions` (default `const []`, G9) with `hasIn`, `regionsWith` and `multiRegion` (§7.2).
- The client never re-derives region rights for a record. It uses the server flags (D14). `hasIn` is used only for create-flow pickers and to offer actions whose server check is authoritative.
- `GET /api/privileges` returns `regionLevel` per privilege for the roles screen badge.

### 4.9 Classification guard

`T/privilege/PrivilegeClassificationTest` is a plain JUnit test. It asserts:
- every name in `Privileges.ALL` is in exactly one of `GLOBAL` and `REGION_LEVEL`;
- `requiredLevel("UNKNOWN") == MANAGE`;
- `REGION_LEVEL.get(CHANGE_APPROVE) == APPROVE`.

Each slice that adds a privilege extends it.

---

## 5. Backend design per module

### 5.1 Time: `B/common/time`

```java
@Configuration public class ClockConfig {
    @Bean public Clock appClock() { return Clock.systemUTC(); }            // the ONLY Clock bean; tests add a @Primary MovableClock
}
public final class WallClock { public static final Clock UTC = Clock.systemUTC(); }   // JWT, webhook skew, ApiError.timestamp only
public final class AppClock {                                             // static bridge for JPA callbacks and static helpers (set to the injected — in tests the @Primary — Clock)
    private static volatile Clock clock = Clock.systemUTC();             // never null, even before the bridge bean exists (CF-7)
    public static Clock get(); public static Instant now();
    public static void set(Clock c);                                      // AppClockBridge, IntegrationTestBase reset and drift-boot tests only
}
@Component class AppClockBridge { AppClockBridge(Clock clock) { AppClock.set(clock); } }   // takes the @Primary Clock
public final class Today {
    public static LocalDate date() { AsOfPoint p = AsOf.current(); return p != null ? p.date() : LocalDate.now(AppClock.get(), ZoneOffset.UTC); }
    public static LocalDate liveDate() { return LocalDate.now(AppClock.get(), ZoneOffset.UTC); }   // never as-of (writes, schedulers)
}
```

**Retrofit (BE-01).** Each clock read is replaced with an injected `Clock` (Spring beans) or `AppClock.now()` (entities and static code):

| File | Lines | Change |
|---|---|---|
| `invoice/InvoiceDates.java` | 15 | `today()` delegates to `Today.date()` |
| `common/query/FilterPredicates.java` | 68 | `Today.date()` |
| `invoice/InvoiceNumbers.java` | 34 | `LocalDate.now(clock, UTC)`, deliberately **live** |
| `invoice/InvoiceService.java` | 66, 215 | clock |
| `customer/CustomerService.java` | 284 | clock |
| `dispute/DisputeService.java` | 103, 123 | clock |
| `document/DocumentService.java` | 175 | clock |
| `document/DocumentCascade.java` | 21 | clock |
| `promise/PaymentPromiseService.java` | 194, 656 | clock |
| `user/UserController.java` | 179 | clock |
| `auth/AuthController.java` | 89 | clock |
| `email/EmailDispatcher.java` | 72, 75, 120, 123, 145, 148, 157, 228, 262 | clock |
| `email/EmailInboundService.java` | 112 | clock |
| `email/InboxService.java` | 30, 36 | clock |
| `email/connection/GmailConnectionService.java` | 185 | clock |
| `email/connection/GmailDisconnects.java` | 75 | clock |
| `email/mailservice/MailServiceEventHandler.java` | 117 | clock |
| **Entity callbacks:** `audit/AuditLog:55`, `customer/Customer:52`, `dispute/Dispute:66,73`, `document/Document:96,106`, `email/Email:148,158`, `email/connection/GmailConnection:53`, `invoice/Invoice:78`, `notification/Notification:51`, `payment/Payment:62`, `poc/CustomerPoc:53`, `product/Product:40`, `promise/PaymentPromise:100,107`, `user/User:53` | | `AppClock.now()` |
| **Protocol time (unchanged semantics):** `auth/JwtService:43` (`new Date()`), `email/mailservice/WebhookSignature:22,25,35`, `email/mailservice/MailServiceEventsController:50`, `common/ApiError:16,20` | | `WallClock.UTC` |

**Inventory (critic finding CF-18).** `grep -rln 'Instant\.now()\|LocalDate\.now(\|LocalDateTime\.now(\|OffsetDateTime\.now(' backend/src/main` finds **33** files, all in the table above; `JwtService` reads the clock through `new Date()`. The repository methods that take `@Param("now")` (`DocumentRepository`, `EmailRecipientRepository`, `EmailRepository`, `GmailConnectionRepository`) and `EmailDeliveryRollup.handedOff(email, now)` receive the instant from their callers and need no change. **`common/time/ClockReadGuardTest`** (plain scan, BE-01) keeps the inventory enforced: it fails on any `X.now()` with no argument, any `.now(ZoneOffset…)`/`.now(ZoneId…)`, `new Date()` or `System.currentTimeMillis()` in `src/main/java` outside `common/time/**`.

- `B/common/query/TableSchemas.overduePredicate` (line 81), `InvoiceDtos.from` (86, 117), `InvoiceService.tiles` (321), `CustomerService.toDtos` (164), `InvoiceController.export` (139), `DashboardController.today()` (52–54) and `PaymentPromiseService.targetStatus` (272) all reach `Today.date()` through `InvoiceDates.today()`. That makes them as-of-aware with no further edit.
- **Write paths use `Today.liveDate()`**, and they never run under a point (§5.7.6). `sweepOverdue` (493) is a scheduler, so it is live.

### 5.2 View scope: `B/common/scope`

#### 5.2.1 Types

```java
public record AsOfPoint(LocalDate date, Instant instant) {
    public static AsOfPoint endOfDay(LocalDate d) { return new AsOfPoint(d, d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minus(1, ChronoUnit.MICROS)); }
    public static AsOfPoint at(Instant i) { Instant t = i.truncatedTo(ChronoUnit.MICROS); return new AsOfPoint(LocalDate.ofInstant(t, ZoneOffset.UTC), t); }
}
public record ViewScope(Set<Long> regionIds /* null = all mine */, AsOfPoint asOf /* null = live */) {
    public static final ViewScope LIVE_ALL = new ViewScope(null, null);
    public Set<Long> narrow(Set<Long> mine) { return regionIds == null ? mine : unmodifiable(intersection(mine, regionIds)); }
    public boolean historical() { return asOf != null; }
    public ViewScope withAsOf(AsOfPoint p) { return new ViewScope(regionIds, p); }
}
public final class ViewScopeHolder {                                 // ThreadLocal<ViewScope>
    public static ViewScope current();                               // LIVE_ALL when unset
    public static <T> T with(ViewScope s, Supplier<T> body);         // saves previous, restores in finally
    public static void with(ViewScope s, Runnable body);
    public static void set(ViewScope s); public static void clear(); // interceptor, RunAs and tests only
    public static void requireLive();                                // historical() → throw AsOfException.readOnly(asOf.date())
}
public final class AsOf {                                            // facade over ViewScopeHolder (D2)
    public static AsOfPoint current();  public static boolean active();
    public static <T> T with(AsOfPoint p, Supplier<T> body);        // ViewScopeHolder.with(current().withAsOf(p), body)
}
@Target({TYPE, METHOD}) @Retention(RUNTIME) public @interface AsOfAware {}
@Target({TYPE, METHOD}) @Retention(RUNTIME) public @interface AsOfIgnored {}
public class CodedBadRequestException extends BadRequestException { public final String code; }  // ApiError.error = code
public class AsOfException extends CodedBadRequestException {
    public static AsOfException invalid();                          // AS_OF_INVALID  "asOf must be a date (yyyy-MM-dd)"
    public static AsOfException future();                           // AS_OF_FUTURE   "asOf cannot be after today"
    public static AsOfException beforeHistory(LocalDate start);     // AS_OF_BEFORE_HISTORY "History is available from 1 Oct 2026"
    public static AsOfException readOnly(LocalDate d);              // AS_OF_READ_ONLY "You are viewing 30 Jun 2026. Changes can only be made in the current view."
    public static AsOfException unsupported();                      // AS_OF_UNSUPPORTED "This page can't be shown as of a date"
}
```

`GlobalExceptionHandler` gains `@ExceptionHandler(CodedBadRequestException.class)`, which returns `ApiError.of(400, ex.code, ex.getMessage(), path)`. It is declared before the generic `BadRequestException` handler; Spring picks the most specific type. `REGION_IDS_INVALID` ("regionIds must be a comma-separated list of region ids") is also coded.

#### 5.2.2 `ViewScopeInterceptor` (registered for `/api/**` by `ViewScopeConfig implements WebMvcConfigurer`)

```
preHandle(req, res, handler):
  ViewScopeHolder.clear()
  if !(handler instanceof HandlerMethod hm) return true
  regionIds = parseRegionIds(req.getParameterValues("regionIds"))   // repeated and/or comma-separated; blanks skipped;
                                                                   // no tokens ⇒ null; non-positive-long ⇒ 400 REGION_IDS_INVALID
  raw = req.getParameter("asOf"); AsOfPoint point = null
  if raw not blank:
     d = LocalDate.parse strict (ISO_LOCAL_DATE, ResolverStyle.STRICT) else throw AsOfException.invalid()
     today = LocalDate.now(appClock, UTC)
     if d.isAfter(today) throw AsOfException.future()
     mode = classify(hm, req.getMethod())                          // AWARE | IGNORED | WRITE | UNCLASSIFIED (D2 table)
     if d.equals(today) || mode == IGNORED: res.setHeader("X-View-As-Of", "live")
     else if mode == WRITE:        throw AsOfException.readOnly(d)
     else if mode == UNCLASSIFIED: throw AsOfException.unsupported()
     else: asOfSupport.requireAvailable(d); point = AsOfPoint.endOfDay(d); res.setHeader("X-View-As-Of", d.toString())
  ViewScopeHolder.set(new ViewScope(regionIds, point)); return true
afterCompletion(...): ViewScopeHolder.clear()
```

- `asOfSupport` is an interface bean `AsOfSupport { void requireAvailable(LocalDate d); }`.
  - BE-01 ships `NoHistoryYet`, which throws `unsupported()`, so any historical request fails closed until the read side exists.
  - BE-08 creates `HistoryRange` **without** `implements AsOfSupport`, so exactly one `AsOfSupport` bean exists. BE-09a adds `implements AsOfSupport` to `HistoryRange` and **deletes** `NoHistoryYet` in the same change (critic finding CF-17).
- `SecurityConfig.corsConfigurationSource` (line 48) exposes `Authorization` **and `X-View-As-Of`**.
- Customer logins: `regionIds` is ignored by consumers (§5.6.4). `asOf` is honoured.

#### 5.2.3 Classification of existing controllers

| Controller | Classification |
|---|---|
| `CustomerController` | class `@AsOfAware`; `POST /export` method `@AsOfAware`; all other non-GET are WRITE |
| `InvoiceController` | class `@AsOfAware`; `GET /due-date-preview` and `GET /assignable-check` method `@AsOfIgnored`; `POST /export` `@AsOfAware` |
| `PaymentController` | class `@AsOfAware` (list, summary, `/{id}`, `/credits/{customerId}`); `POST /export` `@AsOfAware` |
| `PaymentPromiseController` | class `@AsOfAware`; `POST /export` `@AsOfAware`; `POST /recompute` is WRITE |
| `DisputeController` | class `@AsOfAware`; `POST /export` `@AsOfAware` |
| `ProductController` | class `@AsOfAware`; `POST /export` `@AsOfAware` |
| `DashboardController`, `AuditController` | class `@AsOfAware` |
| `DocumentController` | class `@AsOfAware` (list, count); `GET /{id}/download` `@AsOfIgnored` |
| `EmailController` | class `@AsOfIgnored` (`/context`, `/people`, `/delivery` answer live); `GET ""` and `GET /{id}` method `@AsOfAware`; `POST /preview` and `POST /sync` method `@AsOfIgnored`; `POST ""`, `/bulk`, `/{id}/retry` are WRITE |
| `AuthController`, `TableSchemaController`, `InboxController`, `GmailConnectionController`, `MailServiceEventsController`, `NotificationController`, `PocController`, `PrivilegeController`, `RoleController`, `UserController` | class `@AsOfIgnored`; housekeeping non-GET methods carry method-level `@AsOfIgnored` (§5.2.4); every other non-GET is WRITE |

New controllers are classified in §6. `ViewScopeInventoryTest` enforces this (§8.2).

#### 5.2.4 Housekeeping writes (method-level `@AsOfIgnored`, allowed live in historical mode)

- `POST /api/auth/login`, `POST /api/auth/change-password`
- `POST /api/notifications/{id}/read`, `POST /api/notifications/mark-all-read`, `POST /api/notifications/bulk`
- `POST /api/inbox/{id}/read`, `POST /api/inbox/{id}/unread`, `POST /api/inbox/mark-all-read`, `POST /api/inbox/bulk`
- `PUT` and `DELETE /api/me/gmail`
- `POST /api/mail-service/events`
- `POST /api/emails/preview`, `POST /api/emails/sync`
- `POST /api/admin/history/reconcile`
- `POST /api/users/export`, `POST /api/roles/export` (configuration exports answer live)

This allow-list is literal in `ViewScopeInventoryTest`.

#### 5.2.5 `PageResponse` (`B/common/query/PageResponse.java`)

```java
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, String sort,
        List<String> appliedFilters, List<String> lockedFilters,
        List<Long> regionIds, List<Long> bookRegionIds, String asOf) {
    public static <T> PageResponse<T> of(List<T> content, TableQuery q, long total, List<String> lockedFilters); // unscoped lists: regionIds=[], bookRegionIds=[]
    public static <T> PageResponse<T> of(List<T> content, TableQuery q, long total, ScopeResolver.Scope scope); // BE-02: lockedFilters/regionIds/bookRegionIds from scope
    public <R> PageResponse<R> map(Function<T, R> f);                                                           // copies all fields
}
```

- `asOf` is `AsOf.current() == null ? null : date.toString()`.
- `of` and `map` are the only construction sites (`PageResponse.java:16-28`). No test constructs it.

### 5.3 Origin: `B/common/origin`

```java
public record WriteOrigin(Kind kind, Long ruleId, Long runId, Long eventId, Long pendingChangeId, Long checkerUserId,
                          Long actorUserId, int depth) {
    public enum Kind { USER, SYSTEM, AUTOMATION, APPROVAL, SCHEMA, RECONCILE }
    public static WriteOrigin automation(long ruleId, Long runId, Long eventId, int depth);
    public static WriteOrigin approval(long pendingChangeId, long checkerUserId);
    public static WriteOrigin schema(); public static WriteOrigin reconcile(); public static WriteOrigin system();
    public WriteOrigin withActor(Long userId);            // copy with actorUserId set
}
public final class WriteOrigins {                         // ThreadLocal
    public static WriteOrigin current(CurrentUser cu);    // bound, else USER if principal, else SYSTEM
    public static Long actor(CurrentUser cu);             // bound origin's actorUserId, else cu.idOrNull()  (D4)
    public static <T> T callWith(WriteOrigin o, Supplier<T> body); public static void run(WriteOrigin o, Runnable body);
    public static void clear();
}
```

**`AuditService`** (`B/audit/AuditService.java:18-32`):
- Every `record(...)` stamps `origin` (the kind name, unless USER), `rule_id`, `pending_change_id` and `approved_by_user_id` (checker) from the current origin.
- The existing 8-argument signature is unchanged, so none of its ~10 callers change.
- It gains:
```java
public AuditLog record(String type, Long id, String action, Object before, Object after, Long userId, Long disputeId, String reason, Long regionId);
@Transactional public AuditLog recordLinked(String type, Long id, String action, Object before, Object after,
                                            Long userId, Long disputeId, Long pendingChangeId, String reason);
```

`AuditController.AuditEntryDto` gains nullable `origin`, `ruleId`, `pendingChangeId`, `approvedByUserId` and `approvedByUsername` (BE-01). BE-15 adds `pendingStatus`.

### 5.4 `DatabaseErrors` (`B/common/DatabaseErrors.java`)

- `public static boolean isUnavailable(Throwable e)` is moved verbatim from `MailServiceEventHandler.databaseUnavailable` (lines 74–84), which now delegates. That bean is `@ConditionalOnProperty`, so it must never be injected (C-9).
- `public static boolean isConcurrency(Throwable e)` walks the cause chain for `ConcurrencyFailureException`, `PessimisticLockingFailureException` and `CannotAcquireLockException`.
- **Callers test concurrency first** (DR-auto S8): `isUnavailable` is also true for every `TransientDataAccessException`.

### 5.5 Auth: principal and `RunAs`

**`AppUserDetails`**: `(User user, Collection<? extends GrantedAuthority> authorities, RegionRights rights)`, with a `getRights()` getter.

**`AppUserDetailsService`**:
```java
@Override @Transactional(readOnly = true)
public UserDetails loadUserByUsername(String u) { return principalFor(userRepository.findByUsernameIgnoreCase(u).orElseThrow(...)); }
@Transactional(readOnly = true) public AppUserDetails principalFor(User user);             // exactly one grants query (staff) or one region lookup (customer)
@Transactional(readOnly = true) public Optional<AppUserDetails> principalFor(Long userId);
public static List<GrantedAuthority> buildAuthorities(User user, RegionRights rights);   // "ROLE_"+role + rights.effectiveAnywhere(); List.of() if role null
```

- `CustomerRepository` adds `@Query("select c.region.id from Customer c where c.id = :id") Optional<Long> regionIdOf(Long id)`.
- `JwtAuthFilter` is unchanged. Grants are therefore re-read on every request, and a grant change applies to the user's next request.

**`B/auth/RunAs`** (`@Component`):
```java
public <T> T call(AppUserDetails principal, ViewScope scope, WriteOrigin origin, Supplier<T> work) {
    SecurityContext prevCtx = SecurityContextHolder.getContext(); ViewScope prevScope = ViewScopeHolder.current(); /* and prev origin */
    SecurityContext mine = SecurityContextHolder.createEmptyContext();
    mine.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    SecurityContextHolder.setContext(mine);
    WriteOrigin bound = origin.actorUserId() != null ? origin : origin.withActor(principal.getUser().getId());   // D4
    try { return ViewScopeHolder.with(scope, () -> WriteOrigins.callWith(bound, work)); }
    finally { SecurityContextHolder.setContext(prevCtx); }
}
public <T> T asUser(long userId, ViewScope scope, WriteOrigin origin, Supplier<T> work);   // InactiveUserException if missing/inactive/customer
public static class InactiveUserException extends RuntimeException {}
```

### 5.6 Regions: `B/region` (B1)

#### 5.6.1 Classes

| Class | Responsibility |
|---|---|
| `Region`, `RegionRepository` | Entity §3.2. Repository methods: `findByIsDefaultTrue`, `findByCode`, `existsByNameIgnoreCaseAndIdNot`, and `clearDefaultExcept(id)` / `markDefault(id)` (`@Modifying` bulk JPQL; `regions` is not versioned). |
| `UserRegionGrant`, `UserRegionGrantRepository` | Entity §3.3. Queries in §5.6.3. |
| `AccessLevel` (with `permits`, `atLeast`), `RecordScope`, `GrantRow`, `RegionRights`, `RegionAccessQueries` | §4 |
| `RegionService` | `defaultRegion()` (queried on every call, never cached: R10, and the second test context wipes the DB), `requireActive(id)` (404 if unknown, 400 if inactive), `create`, `update`, `makeDefault`, `delete`. Audit entity `"REGION"`; actions `REGION_CREATED`, `REGION_UPDATED`, `REGION_DEFAULT_CHANGED`, `REGION_DELETED`. From BE-15, `create` calls the `RegionCreatedListener` SPI; `ApprovalThresholdService` implements it and copies the default region's thresholds into the new region in the same transaction (SC-4). |
| `RegionGrantService` | `replace(userId, grants)`: lock the user (`UserRepository.findByIdForUpdate`, new `@Lock(PESSIMISTIC_WRITE)`), refuse self and customer logins, validate, **enforce the granter bound (§4.6; 403)**, `deleteAllOfUser` (bulk, executed immediately: F7), `saveAll`, audit `USER_REGION_GRANTS_CHANGED` (actor = granter), and (from BE-13) notify `APPROVER_ADDED` for each region where the user gains APPROVE. `ensure(user, region, level, scope)` for the seeder, region create and fixtures (not bounded). |
| `UserAccountService` (`B/user`) | `@Transactional create(User unsaved, List<GrantInput> grantsOrNull)`. **Absent or `[]` means no grants** (SC-5); the granter bound applies to each row. `UserController.create` delegates to it from BE-05. |
| `RegionController`, `RegionGrantController` | §6.3 |
| `RegionSchemaUpgrade` | §3.11 |
| `RecordAccess` | Per-record flags (§5.6.9) |
| `RegionMoveParticipant`, `CustomerRegionMoveService`, `CustomerRegionMoveRepository`, `RegionMoveDtos` | §5.6.8 (BE-08) |

#### 5.6.2 Fixture and helper contract (`T/IntegrationTestBase`)

- `defaultRegion()` looks the region up on every call.
- `region(code)` finds or creates an active region.
- `grant(User, Region, AccessLevel, RecordScope)`.
- `asking(Long... regionIds)` sets the `ViewScopeHolder` for direct service calls.
- `user(name, role)` saves the user, then grants DEFAULT/APPROVE/ALL. `customerUser` is unchanged.
- `customer(name[, email])` sets `.region(defaultRegion())`. New overload `customer(name, Region)`.
- `actAs(u)` and `as(u)` use `principals.principalFor(fresh)`.

#### 5.6.3 `UserRegionGrantRepository`

```java
@Query("select new com.geneinvoice.region.GrantRow(g.region.id, g.accessLevel, g.recordScope) from UserRegionGrant g where g.user.id = :userId")
List<GrantRow> rowsFor(Long userId);
@Query("select g from UserRegionGrant g join fetch g.region where g.user.id = :userId order by g.region.code") List<UserRegionGrant> findWithRegionByUserId(Long userId);
@Query("select g from UserRegionGrant g join fetch g.user u where g.region.id = :regionId order by u.username") List<UserRegionGrant> findWithUserByRegionId(Long regionId);
@Query("select distinct g.user.id from UserRegionGrant g where g.user.id in :userIds and g.region.id = :regionId and g.accessLevel in :levels")
List<Long> usersWithAccess(Collection<Long> userIds, Long regionId, Collection<AccessLevel> levels);
@Query("""select count(g) > 0 from UserRegionGrant g join g.user u join u.role r join r.privileges p
          where u.id = :userId and g.region.id = :regionId and p.name = :privilege and g.accessLevel in :levels""")
boolean hasPrivilegeInRegion(Long userId, String privilege, Long regionId, Collection<AccessLevel> levels);
@Query("""select distinct u from UserRegionGrant g join g.user u join u.role r join r.privileges p
          where g.region.id in :regionIds and g.accessLevel in :levels and u.active = true and u.customerId is null and p.name = :privilege
            and (:q is null or lower(u.username) like :q escape '\\' or lower(coalesce(u.fullName,'')) like :q escape '\\'
                 or lower(coalesce(u.email,'')) like :q escape '\\')
          order by u.fullName asc, u.username asc""")
List<User> findAssignableInRegions(String privilege, Collection<Long> regionIds, Collection<AccessLevel> levels, String q, Pageable page);
@Query("""select distinct u from UserRegionGrant g join g.user u join u.role r join r.privileges p
          where g.region.id = :regionId and g.accessLevel in :levels and p.name = :privilege and u.active = true and u.customerId is null""")
List<User> findHoldersInRegion(String privilege, Long regionId, Collection<AccessLevel> levels);
@Query("""select distinct u from UserRegionGrant g join g.user u
          where g.region.id in :regionIds and u.active = true and u.customerId is null
            and (:q is null or lower(u.username) like :q escape '\\' or lower(coalesce(u.fullName,'')) like :q escape '\\'
                 or lower(coalesce(u.email,'')) like :q escape '\\')
          order by u.fullName asc, u.username asc""")
List<User> findStaffInRegions(Collection<Long> regionIds, String q, Pageable page);   // /api/assignees/people (SC-18)
@Modifying(flushAutomatically = true) @Query("delete from UserRegionGrant g where g.user.id = :userId") int deleteAllOfUser(Long userId);
long countByRegionId(Long regionId);
```

#### 5.6.4 `ScopeResolver` rewrite (`B/poc/ScopeResolver.java`)

It depends on `CurrentUser` and `TableQueryExecutor`, and no longer on `UserRepository`.

```java
public enum Access { VIEW, MANAGE }
public enum Reach  { SELECTED /* sets: narrowed by ViewScope */, GRANTED /* by-id: every granted region */ }
public enum Tier   { FULL, BOOK, NONE }
public enum Kind {   // entity, schema, manage privilege, book, locked column
  CUSTOMER(Customer.class,       TableSchemas.CUSTOMERS, CUSTOMER_MANAGE, Book.ANY_POC,    "myBook"),
  INVOICE (Invoice.class,        TableSchemas.INVOICES,  INVOICE_MANAGE,  Book.SALES,      "salesPocUserId"),
  PAYMENT (Payment.class,        TableSchemas.PAYMENTS,  PAYMENT_MANAGE,  Book.COLLECTION, "collectionPocUserId"),
  PROMISE (PaymentPromise.class, TableSchemas.PROMISES,  PROMISE_MANAGE,  Book.COLLECTION, "collectionPocUserId"),
  DISPUTE (Dispute.class,        TableSchemas.DISPUTES,  DISPUTE_MANAGE,  Book.NONE,       null),
  TASK    (Task.class,           TableSchemas.TASKS,     TASK_MANAGE,     Book.PARENT,     null);   // BE-12
}
public record RegionCoverage(Set<Long> full, Set<Long> book, boolean own) {
  public static final RegionCoverage OWN = new RegionCoverage(Set.of(), Set.of(), true);
  public Set<Long> answered(); public boolean mixed() { return !full.isEmpty() && !book.isEmpty(); }
}
public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters, RegionCoverage coverage) {
  public List<Long> regionIds();      // sorted answered()
  public List<Long> bookRegionIds();  // sorted book()
}
public Scope forCustomers(); forInvoices(); forPayments(); forPromises(); forDisputes(); forTasks();   // = scope(kind, VIEW, SELECTED)
public Scope scope(Kind kind, Access access, Reach reach, String... alsoRequired);
public Tier tier(Kind kind, Access access, Long regionId, String... alsoRequired);                  // in memory, no SQL
public void requireVisible(Kind kind, Long id, Long regionId, String notFoundMessage);              // by-id (F4)
public boolean canSeePoc();                   // !customer && rights.anywhere(POC_VIEW)
public boolean isAssignableAs(PocType t);     // !customer && rights.anywhere(t.assignabilityPrivilege())
public boolean isAssignableIn(PocType t, Long regionId);
public boolean canSeeEverything();            // /my-scope clearable: !customer && full≠∅ && book=∅ on forInvoices()
```

**Tier for a region `r` (staff):**
```
gate = access == VIEW ? rights.holdsAnyIn(r)            // X_VIEW itself is enforced by @PreAuthorize (F2)
                      : rights.has(kind.manage, r)
if !gate or any x in alsoRequired with !rights.has(x, r)                   → NONE
if kind.book == NONE or kind.book == PARENT                                → FULL
if rights.has(SCOPE_OVERRIDE, r)                                           → FULL
if (kind.book == ANY_POC ? any POC_ASSIGNABLE_* effective in r : rights.has(kind.book.type.assignabilityPrivilege(), r)) → BOOK
else                                                                       → NONE
```
For a customer login, the tier is FULL for its own region and NONE elsewhere.

**`scope(kind, access, reach, extras)`:**
1. **Customer login.** Today's predicates, unchanged: `customer.id = own` / `id = own` / `customerId = own`.
   - Disputes also get `internal = false` (BE-19).
   - Tasks get `cb.disjunction()`.
   - `lockedFilters = []`, `coverage = OWN`. `ViewScope.regionIds` is ignored.
2. **Staff.**
   - `granted` = the regions where the gate and the extras hold.
   - `asked` = `reach == SELECTED ? ViewScopeHolder.current().narrow(granted) : granted`.
   - Split `asked` by tier into `full` and `book`.
3. **If `full ∪ book = ∅`:** the predicate is `cb.disjunction()`.
   - `lockedFilters = [lockedColumn + ":eq:" + me]` only when no granted region has a non-NONE tier. That is today's `nothing()` chip.
   - Otherwise `lockedFilters = []` (the set is empty only because of narrowing).
4. **Otherwise** the predicate is:
   ```java
   (root, q, cb) -> { Path<Long> r = root.get("region").get("id");
     Predicate f = full.isEmpty() ? null : r.in(full);
     Predicate b = book.isEmpty() ? null : cb.and(r.in(book), bookPredicate(kind, root, q, cb, me));
     return f == null ? b : b == null ? f : cb.or(f, b); }   // never "in ()"
   ```
   The book predicates:
   - invoices: `salesPoc.id = me`
   - payments and promises: `collectionPoc.id = me`
   - customers: the existing `customerHasPocSeat OR customerHasInvoiceOwnedBy`
5. **TASK (`Book.PARENT`).** Add an AND with the parent-visibility predicate: an OR over entity types X where `rights.anywhere(X_VIEW)` of
   `root.entityType = X AND root.entityId IN (subquery of X ids under scope(X, VIEW, reach).predicates())`.
   This follows the `DashboardService.idsInScope` pattern: pass the **outer** `cq`.
6. `lockedFilters = full.isEmpty() && kind.lockedColumn != null ? [lockedColumn:eq:me] : []`. A mixed view shows no chip; the UI reads `bookRegionIds`.

**`requireVisible(kind, id, regionId, nf)`:**
- Customer login: return (callers keep today's own-customer checks).
- Otherwise switch on `tier(kind, VIEW, regionId)`:
  - `FULL`: return, with no SQL.
  - `BOOK`: `if (!queryExecutor.inScope(kind.type, kind.schema, id, scope(kind, VIEW, GRANTED).predicates())) throw new NotFoundException(nf)`.
  - `NONE`: throw `NotFoundException(nf)`.
- Reading `entity.getRegion().getId()` on a LAZY proxy does not initialise it.

**Single region, backfilled user:** `granted = {D}`. The tier is FULL when the role has SCOPE_OVERRIDE, BOOK when it has only the POC privilege, and NONE otherwise, so `lockedFilters` are exactly today's.

**`EmailContextTest`'s count of 1 `customer_pocs` read still holds:** admin is FULL, so no seat subquery runs.

#### 5.6.5 Reads

**By-id.** Each `requireInBook(id)` is replaced by `scopeResolver.requireVisible(Kind.X, id, entity.getRegion().getId(), "<X> not found")`:

| Site | Change |
|---|---|
| `CustomerService.get` (129–145) | Keep the customer-login 403 (X1) |
| `InvoiceService.get` (260–286) | as above |
| `PaymentService.get` (272–291) | as above |
| `PaymentPromiseService.get` (505–520) | as above |
| `DisputeService.get` (143–152) | Staff now get `requireVisible(DISPUTE)`; the customer 403 is kept |
| `PaymentController.credit` (93–108) | Use `customerService.get(customerId)` |
| `InvoiceService.requireCustomerInBook` (288–293, preview) | `requireVisible(CUSTOMER, …)` + `requireIn(INVOICE_MANAGE, c.region)` |
| `AuditController.readOrGone` (118–124) | For a deleted CUSTOMER, look up the region with `auditLogRepository.findLatestRegionId("CUSTOMER", id)` (default region if none). If `!rights().holdsAnyIn(region)`, rethrow NotFound. |

**Sets.** Every `forX()` caller picks up `SELECTED` narrowing automatically: `page`, `idsMatching` (export and bulk), `allMatching`, `tiles`, `DisputeController.list` and `export`, and `EmailTargets.bulkIds`.
- `EmailTargets.bulkIds` uses `scope(kind, VIEW, SELECTED, EMAIL_SEND)` for the five core kinds.
- The five `page()` methods and `DisputeController.list` switch to `PageResponse.of(…, scope)`.
- They add `"region"` to their `fetchAssociations`, so DTO mapping does no per-row region loads.

**Bulk writes.** Each `*Controller.resolveIds` gains an `Access` argument. `idsMatching(query, limit, access, extras...)` uses `scope(kind, access, SELECTED, extras)`:

| Bulk action | Access, extras |
|---|---|
| Invoice CANCEL | MANAGE |
| REASSIGN_SALES_POC / REASSIGN_COLLECTION_POC | MANAGE, POC_ASSIGN |
| Promise CANCEL | MANAGE |
| Customer ADD_POC | MANAGE, POC_ASSIGN |
| Every `/export` | VIEW |

- Rows that are visible but not writable drop out as skipped with `BulkExecutor.NOT_REACHABLE`.
- The coarse controller checks become `currentUser.canAssignPocAnywhere()`. They keep 400 at `InvoiceController:117`, `PaymentController:123` and `PaymentPromiseController:134`, and 403 at `CustomerController:133`.

**Unchanged unscoped figures.** `CustomerService.outstandingSubquery`, `InvoiceRepository.sumOutstandingByCustomer`/`sumOverdueByCustomer` and `TableSchemas.customerOutstanding` stay as they are: by invariant I1 they never cross regions.

**Dead unscoped methods deleted (R17):** `PaymentService.list`, `PaymentPromiseService.listForCustomer`/`listForInvoice`, `DisputeService.list`, `PocService.callerIfAssignable`/`primaryFor`.

#### 5.6.6 Writes: the complete inventory (R4)

The pattern is:
1. Load the record, with its lock.
2. Visibility → 404.
3. `ViewScopeHolder.requireLive()` → 400.
4. `currentUser.requireIn(privilege, region)` → 403.

Creates on a customer (invoice, payment, promise, internal dispute, task) use the **book-aware** `scopeResolver.requireVisible(Kind.CUSTOMER, customerId, customer.getRegion().getId(), "Customer not found")` after the F1 lock, followed by `requireIn`. A BOOK-tier POC can therefore neither write to nor read back (through the response DTO) a customer outside their book (critic finding SC-12). Every create test actor in the baseline suite is admin (FULL tier), and the UI pickers already list only visible customers, so P7 holds. `CustomerService.create` has no parent and uses `requireReachable` on the chosen region. The "gate" column refers to maker-checker (§5.11); "publish" refers to the outbox (§5.8).

*Not added:* a visibility check on explicitly listed `invoiceIds` of `POST /api/payments`. A COLLECTION-only POC is in the INVOICE tier NONE (the invoice book is the sales book), yet today legitimately allocates to the customer's invoices; the customer check above already bounds the call.

| Site | Region rule (BE-02/BE-04a/BE-04b) | Other slices |
|---|---|---|
| `CustomerService.create` (204–248) | Region = `req.regionId`, or the single active region where CUSTOMER_MANAGE is effective, or the single one left after ViewScope narrowing; otherwise 400 `fieldErrors.regionId = "Choose the region this customer belongs to"`. Then `requireReachable`, `requireIn(CUSTOMER_MANAGE)`, `regionService.requireActive`. Builder `.region(ref)`. | publish CREATED |
| `CustomerService.update` / `delete` | `requireVisible` + `requireLive` + `requireIn(CUSTOMER_MANAGE)`. `delete` passes the region to the `CUSTOMER_DELETED` audit. | `update` publishes UPDATED (deletes are never published); `delete` also calls `taskCascade.onCustomerDeleted` and `closer.closeForCustomer` |
| `InvoiceService.create` (59–99) | `customerRepository.findByIdForUpdate` (F1), `requireLive`, `requireVisible(CUSTOMER)` (book-aware, 404), `requireIn(INVOICE_MANAGE, region)`, `requireAssignable(salesPoc, SALES, region)`. Builder `.region(customer.getRegion())`. | gate INVOICE_CREATE; number after the gate; publish CREATED |
| `InvoiceService.update` / `cancel` | Customer first (§5.11.5): unlocked read of the invoice's customer id → `customerRepository.findByIdForUpdate` → `invoiceRepository.findByIdForUpdate(id)` (today's row lock, now second) → `requireVisible` + `requireLive` + `requireIn(INVOICE_MANAGE)`. Line 134 → `canAssignPocIn(region)` (keeps its 400 message); line 137 → `requireAssignable(…, region)`. | gates INVOICE_TERMS / INVOICE_CANCEL; publish |
| `PaymentService.record` (54–109) | After `lockCustomer`: `requireLive`, `requireVisible(CUSTOMER)` (book-aware), `requireIn(PAYMENT_MANAGE)`, `requireAssignable(…, COLLECTION, region)`. Builder `.region`. | gate PAYMENT_RECORD; publish |
| `PaymentService.update` | `requireVisible` + `requireLive` + `requireIn(PAYMENT_MANAGE)`; lines 121/124 region-aware | publish |
| `PaymentPromiseService.create` (71–103) | `findByIdForUpdate` (F1), `requireLive`, `requireVisible(CUSTOMER)` (book-aware), `requireIn(PROMISE_MANAGE)`, `resolveCollectionPoc(requested, customerId, regionId)`. Builder `.region`. | gate PROMISE_CREATE; assignees |
| Promise `update`, `cancel`, `reassignCollectionPoc` | New `getForWrite(id, privilege)` = unlocked `get` (visibility, 404) → `customerRepository.findByIdForUpdate(p.customerId)` → `promiseRepository.findByIdForUpdate(id)` (new `@Lock(PESSIMISTIC_WRITE)`) → `requireLive` → `requireIn(privilege, p.region)`. The region is read **after** the customer lock, so a concurrent move either commits first or waits (critic finding SC-7). Lines 124/127 region-aware. | gate PROMISE_UPDATE |
| Promise `override` / `clearOverride` | `getForWrite(id, PROMISE_OVERRIDE)` | gate PROMISE_OVERRIDE |
| `sweepOverdue`, `reevaluateForCustomer`, `recomputeAll` | Lock each customer (`findByIdForUpdate`, ascending id; re-entrant when the caller already holds it) before re-reading and saving its promises, so no path saves a promise without the customer lock (SC-7) | — |
| `PaymentPromiseService.recomputeAll` (458–483) | Regions = `ViewScope.narrow(rights.regionsWith(PROMISE_OVERRIDE))`. New `findCustomerIdsWithLivePromisesInRegions(Collection<Long>)`. Empty ⇒ no-op. | — |
| `DisputeService.open` (52–89) | `findByIdForUpdate(callerCustomer)`, builder `.region`, `notifyAdmins(…, regionId)`. The "an open dispute already exists" guard counts only `internal = false` disputes (BE-19; SC-1). | — |
| `DisputeService.approve` / `deny` (91–133) | Customer first (critic findings CF-8, SC-19): unlocked `findById` (404) → `requireVisible(DISPUTE)` → `customerRepository.findByIdForUpdate(d.customerId)` → `mustBePending` (dispute `FOR UPDATE`, 400) → `requireLive` → `requireIn(DISPUTE_MANAGE, d.region)` | gate DISPUTE_APPROVE; closer |
| `DocumentTargets.requireManageable` (54–60), `canManage` (62–66) | `Target` gains `Long regionId`. Staff: `requireLive`, `requireIn(DOCUMENT_MANAGE, r)`, `requireIn(MANAGE(type), r)`. `canManage(type, regionId)`. | — |
| `DocumentService.toDto` (216–227) | `toDto(Document, Target)`: `manage = staff ∧ hasIn(DOCUMENT_MANAGE, r) ∧ hasIn(MANAGE(type), r) ∧ !historical`; `canDelete = manage ∨ (uploader ∧ hasIn(DOCUMENT_MANAGE, r) ∧ !historical)` | — |
| `EmailService.send`, `preview`, `bulk` (per row), `retry` | `EmailTargets.Target` gains `regionId` (null for PRODUCT, USER, ROLE). New `targets.loadForSend(type, id)` = `load` + `requireIn(EMAIL_SEND, regionId)` when non-null. `send`, `bulk` and `retry` call `requireLive`. **USER recipients and a USER sender** of a record-bound target must hold a grant in `regionId` (`EmailAddressing.resolve` runs one `regionAccess.withAccess(userIds, regionId)`); otherwise 400 `fieldErrors["to[i]"]` / `fieldErrors.from` "X has no access to South" (critic findings PC-2, SC-3). | — |
| `PocService.add` / `remove` / `setPrimary` | After `lockCustomer`: `requireLive` + `requireIn(POC_ASSIGN, customer.region)`; `add` → `requireAssignable(userId, type, region)` | publish CUSTOMER UPDATED(POC_SEATS) |
| Region move | §5.6.8 | |
| Users, roles, products, notification and inbox housekeeping, Gmail | Global, unchanged (the notification and inbox **lists** answer `regionIds`, §5.6.7) | |

**Contract for new writers** (tasks, pending changes, internal disputes): read the region from a customer row locked `FOR UPDATE` in the same transaction.

#### 5.6.7 The POC book per region

**`PocService`**:
```java
public User requireAssignable(Long userId, PocType type, Long regionId);   // today's checks first, then
    // !regionAccess.holds(userId, type.assignabilityPrivilege(), regionId) → 400 "User X is not assignable as Sales POC in region CODE"
public List<User> assignable(PocType type, Collection<Long> regionIds, String q, int limit);   // empty regions → []
public Map<PocType, List<User>> activeHoldersByType(Long customerId);   // existing single customer_pocs read, then withAccess(region) filter
public List<User> withRegionAccess(List<User> users, Long regionId);    // one grants query
```

- The two-argument `requireAssignable` is deleted. The compiler lists its 8 callers plus `CustomerController:149`.
- `defaultAssignee` and `activeHolders` inherit the filter.
- `EmailTargets.target(...)` applies `withRegionAccess` to record-level holders too.
- **A person with no access to the record's region is not a holder of any role on it.** That covers people reached through a **role**. People named directly are checked separately (critic findings PC-2, SC-3):
  - **Manual email** (send, preview, bulk, retry): USER recipients and a USER sender need a grant in the record's region (§5.6.6). Book-level visibility is deliberately not required for manual email: today staff may email a colleague about any record they can see, and baseline tests do exactly that (`EmailAccessTest` emails a sales POC about an invoice outside their book).
  - **Automation email** (`EmailService.queue`) and every **assignee** (task, dispute, promise): the full `VisibilityProbe` (region, the entity's VIEW privilege, and book) for each USER token (§5.9.1, §5.10.5).
  - Role-resolved **assignees** additionally need the owner type's VIEW privilege (`TASK_VIEW`, `DISPUTE_VIEW`, `PROMISE_VIEW`) in the region before they are notified or treated as effective assignees, so nobody is sent a link they would get 403 on.

**`PocController`:**
- `/assignable?type&q&limit[&customerId | &regionId]`:
  - with `customerId`, the region comes from the scoped `customerService.get` (404 if not visible);
  - with `regionId`, it must be granted, otherwise the result is `[]`;
  - with neither, it uses `ViewScope.narrow(grantedRegions)`.
- `/my-scope` gains `regions[]` (§6.8).
- `InvoiceController.assignableCheck` gains an optional `customerId`.

**`NotificationService`** (BE-04b):
- `notifyAdmins(type, title, message, link, Long regionId)` replaces the 4-argument form (one caller, `DisputeService:82`). It filters `findByRoleName("ADMIN")` by `regionAccess.withAccess(ids, regionId)` **and by `active`**.
- `notifyHolders(String privilege, Long regionId, Long exceptUserId, String type, String title, String message, String link)` (active holders of the privilege in the region, at most 100). It is added here, because BE-05 (grants), BE-13 (approvals) and the POC fallback below use it.
- `notify(userId, type, title, message, link, Long regionId)` is the new primary overload and stores `notifications.region_id`; the old overload passes null. Every producer that concerns a record passes the record's region (disputes, broken promises, POC assignment, and every new producer: tasks, approvals, automation).
- `notify(...)` now stores `link = null` with a WARN when `link.length() > 300` rather than failing the caller's save (critic §3.6). Every new producer uses constant-shape links well under 300.
- **Direct POC notices respect regions** (critic finding SC-17). `PaymentPromiseService.notifyBroken` and `PocService.notifyAssignee` first filter the POC with `regionAccess.withAccess(List.of(pocId), record.region)`. A broken-promise notice for a POC without access goes to `notifyHolders(PROMISE_MANAGE, region, …)` instead; a POC-assignment notice is skipped.

**Notifications and the inbox answer "which regions"** (critic finding PC-9). Both stay live under as-of (D9), but:
- `GET /api/notifications` and `/unread-count` filter `region_id IN ViewScope.regionIds OR region_id IS NULL` when `regionIds` is set.
- `GET /api/inbox` and `/unread-count` add `EmailRegionPredicate`: an OR over the email's `entityType` of `entityId IN (select id from <X> where region.id in :asked)` for CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE; emails on USER, ROLE and PRODUCT are always kept. It applies only when `regionIds` is set (BE-03).
- Their `PageResponse.regionIds` is the answered set. Both move from `GLOBAL_EXEMPT` to `SETS` (§8.2).

**`GmailDisconnects.mayConnect`** is unchanged: it is role-based, and sends are refused per region.

#### 5.6.8 Moving a customer to another region (BE-08)

**SPI:**
```java
public interface RegionMoveParticipant {
  default void beforeCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {}  // throw BadRequestException to veto
  default int  afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) { return 0; }  // rows it moved or closed
  default String name() { return getClass().getSimpleName(); }                              // key in moved.other
}
```

**`CustomerRegionMoveService.move(Long id, MoveRequest req)`**, in one `@Transactional`:
1. `ViewScopeHolder.requireLive()`.
2. `customerRepository.findByIdForUpdate(id)` (PPD-01: customer first).
3. `scopeResolver.requireVisible(CUSTOMER, …)` → 404.
4. `requireIn(CUSTOMER_MANAGE, from)` → 403.
5. Target: unknown → 404; `requireReachable(to)`; `requireIn(CUSTOMER_MANAGE, to)` → 403; inactive → 400 "Region X is inactive"; same as `from` → 400 "The customer is already in X".
6. `req.version != null && != customer.version` → `OptimisticLockingFailureException` (409).
7. `invoiceRepository.findByCustomerIdForUpdate(id)` (ascending id).
8. Collect child ids: invoices, payments, promises, disputes (a plain select by `customer_id`).
9. `participants.beforeCustomerMoved(...)`. A veto rolls everything back. `PendingChangeMoveParticipant` (BE-15) vetoes with 400 "Acme Ltd has N changes awaiting approval; they must be decided first" when PENDING changes exist and the mover lacks `CHANGE_APPROVE` in the source region (D7, SC-4).
10. Compute warnings (read only): seat holders and distinct `salesPoc`/`collectionPoc` holders on children who have no grant ≥ READ in `to`. **Nothing is removed.**
11. Native updates through `CustomerRegionMoveRepository` (`@Modifying(flushAutomatically = true, clearAutomatically = true)`, `nativeQuery = true`, standard SQL):
    ```sql
    update invoices         set region_id = :to, version = version + 1 where customer_id = :cid
    update payments         set region_id = :to where customer_id = :cid
    update payment_promises set region_id = :to where customer_id = :cid
    update disputes         set region_id = :to where customer_id = :cid
    update customers        set region_id = :to, version = version + 1 where id = :cid
    ```
12. **`historyTracker.touch(Customer.class, [cid], false)`** and the same for the invoice, payment, promise and dispute ids from step 8. The rows are already locked by the UPDATEs. The writer re-reads them after the persistence-context clear, so every child gets a new version at the same instant with the new region (D7).
13. Insert `customer_region_moves(customer_id, from_region_id, to_region_id, moved_at, moved_by_user_id)`.
14. `participants.afterCustomerMoved(...)`, collecting counts into `moved.other`:
    - `TaskMoveParticipant` (BE-12): native `update tasks set region_id = :to where customer_id = :cid`, then touch.
    - `AutomationMoveParticipant` (BE-11): `updated(CUSTOMER, cid, "REGION")` and **one** `children(cid, "REGION")` event (§5.8.2 M1). Two outbox rows, whatever the number of children.
    - `PendingChangeMoveParticipant` (BE-15): reached only when step 9 did not veto; `closer.closeForCustomer(cid, "Customer moved from A to B")`.
15. Re-read the customer with `findById`, because the context was cleared.
16. Audit `CUSTOMER_REGION_CHANGED` under CUSTOMER, with before `{regionId, regionCode}`, after `{regionId, regionCode, moved}`, reason `req.reason` (≤ 500), and `region_id = to`.
17. Return `MoveResult` (§6.4).

`ModifyingQueryGuardTest` (BE-07) allow-lists `CustomerRegionMoveRepository`, because it is sanctioned by step 12.

**Locks.** The move takes the customer first, then its invoices (ascending), then the dispute, payment and promise rows through the UPDATEs. Every other path that locks more than one of these rows also takes the customer first (§5.11.5), so the paths serialise on the customer row and cannot deadlock with the move (critic findings CF-8, SC-19). `ApprovalLifecycleTest` races a move against a dispute approval: one succeeds, the other waits and then succeeds or fails cleanly, never 40P01.

#### 5.6.9 Per-record flags (`B/region/RecordAccess`)

```java
public Boolean canManage(ScopeResolver.Kind kind, Long regionId);  // !historical && currentUser.hasIn(kind.manage(), regionId)
public Boolean can(String privilege, Long regionId);               // !historical && hasIn(privilege, regionId)
public Boolean canMoveRegion(Long regionId);                        // staff && can(CUSTOMER_MANAGE, r) && ∃ other active region with CUSTOMER_MANAGE
public Boolean canResolve(Dispute d);                               // staff && d.status == PENDING && can(DISPUTE_MANAGE, d.region)
```

- These are used by the API factories (`XDto.from(entity, poc, access)`).
- Audit snapshots use the old factories, which give **null** flags.
- `CustomerAuditSnapshot` gains `regionId`.
- DTO field additions are listed in §6.5.

### 5.7 History and as-of reads: `B/history` (B3)

#### 5.7.1 Capture classes

| Class | Responsibility |
|---|---|
| `HistoryModel` | Static registry built once. Per type it holds: `code`, `rank`, live class ↔ version class, the `TypeHistory` implementation, and navigations (§5.7.12). <br>Codes and ranks: CUSTOMER 10, PRODUCT 15, INVOICE 20, PAYMENT 30, ALLOCATION 35, PROMISE 40, `LINK:PROMISE_INVOICE`/`LINK:PROMISE_PAYMENT` 45, DISPUTE 50, SEAT 60, TASK 70 (BE-12). <br>Methods: `isVersioned(Class)`, `isVersionClass(Class)`, `of(Class live)`, `byCode(String)`, `rootFor(Class live)` (the version class when `AsOf.active()` and versioned, otherwise live). |
| `TypeHistory<L, V extends VersionRow>` | §5.7.2. Implementations live in `history/types`: `CustomerHistory`, `ProductHistory`, `InvoiceHistory`, `PaymentHistory`, `AllocationHistory`, `PromiseHistory`, `DisputeHistory`, `SeatHistory`, `TaskHistory`. |
| `LinkHistory(String kind, Class<?> owner, String collectionAttribute)` | `PROMISE_INVOICE` = (`PaymentPromise`, `"invoices"`), `PROMISE_PAYMENT` = (`PaymentPromise`, `"payments"`). Current targets come from `select o.id, t.id from <Owner> o join o.<collection> t where o.id in :ids`. |
| `HistoryListener` | Implements PostInsert, PostUpdate, PostDelete, PostCollectionRecreate, PostCollectionUpdate and PostCollectionRemove. `requiresPostCommitHandling = false`. **It does no DB work**: it maps each event to keys (§5.7.3) and calls `HistoryTransactions.current(session).mark(...)`. If `AsOf.active()` it throws `AsOfException.readOnly`. Events on version entities and untracked entities are ignored. |
| `HistoryListenerRegistrar` | `@Component(EntityManagerFactory)`. In `@PostConstruct`: `emf.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class).appendListeners(...)` for the six event types, plus `POST_LOAD` → `LiveReadGuard`. |
| `HistoryWork` | One per Hibernate transaction. Implements `BeforeTransactionCompletionProcess` and `AfterTransactionCompletionProcess`. Fields: `txId = UUID`, `Instant instant` (null until the writer binds it), `Instant pinned` (baseline only), `LinkedHashMap<Key(code,id), Flags{inserted, updated, deleted, lockOnChange}> touched`, `boolean writing`, `boolean needsInstant` (set when the outbox asked for the `txId`), `boolean done`. <br>**Stale-process guard** (critic finding CF-22): Hibernate keeps an un-run before-completion process in the session's `ActionQueue` after a rollback, and an OEMIV session reuses the queue for its next transaction. So `doAfterTransactionCompletion` sets `done = true` on commit **and** rollback, and `doBeforeTransactionCompletion` returns at once unless `!done` and this work is the one `HistoryTransactions` has registered for `session.getTransaction()`. |
| `HistoryTransactions` (`@Component`) | `ConcurrentHashMap<org.hibernate.Transaction, HistoryWork>`. `current(SharedSessionContractImplementor s)` creates the work on first use and registers it as both a before- and after-completion process through `((SessionImplementor) s).getActionQueue().registerProcess(...)`. After completion removes it on commit **and** on rollback. `current(EntityManager)` unwraps. `String txId(EntityManager em)` (BE-11) returns the current work's `txId`, creating and registering the work if needed and setting `needsInstant`; it **never binds the instant**. |
| `HistoryWriter` (`@Component`) | §5.7.4. Dependencies: `Clock appClock`, `CurrentUser`, `VersionStore`, `@Value("${app.history.strict:false}") boolean strict`, `HistoryFaults` (the main-code fault-injection interface; `NoHistoryFaults` in production, `T/history/HistoryTestHooks` as the `@Primary` test bean, D16). |
| `HistoryTracker` (`@Component`) | `touch(Class<?> live, Collection<Long> ids, boolean lock)`, `Propagation.MANDATORY`. Marks keys, and the registered LINK keys for owner types. This is **the only sanctioned way** to version rows changed by bulk JPQL or native DML in a runtime transaction (the region move, the baseline and the reconciler). |
| `EnumNames` (`history/versions`) | `abstract class EnumName<E> implements AttributeConverter<E,String>` with nested subclasses `InvoiceStatusName`, `PaymentTermName`, `PaymentStatusName`, `PromiseStatusName`, `PocTypeName`, `DisputeTargetTypeName`, `DisputeStatusName`, `TaskStatusName`, `TaskEntityTypeName`. Unknown strings read as `null` with a WARN. |
| `VersionStore` | §5.7.12 |

#### 5.7.2 `TypeHistory` contract

```java
public interface TypeHistory<L, V extends VersionRow> {
    String code(); Class<L> liveType(); Class<V> versionType(); int rank();
    /** Batch snapshot of managed live entities (after flush). May run batched child queries on s
        (e.g. one query for all invoice items). Pure mapping otherwise. */
    Map<Long, V> snapshotAll(Collection<L> live, SharedSessionContractImplementor s);
    /** Business equality over every stored column except id/versionId/validFrom/validTo/txId/origin/originRefId/actorUserId
        and the per-type exclusions (updatedAt, brokenNotifiedAt). BigDecimal via compareTo; items_json by string
        (it holds only the items' own columns, never another entity's state, §3.5). */
    boolean sameState(V open, V next);
    /** Batch rehydration, in input order, into Historical subclasses (§5.7.15). */
    List<L> rehydrate(List<V> versions, Rehydration ctx);
}
```

#### 5.7.3 Listener: event → keys

| Event source | Keys marked |
|---|---|
| Customer, Product, Invoice, Payment, PaymentPromise, Dispute, Task | `(CODE, id)`. PostInsert sets `inserted`, PostUpdate sets `updated`, PostDelete sets `deleted`. |
| PaymentAllocation | `(ALLOCATION, id)` |
| CustomerPoc | `(SEAT, id)` |
| InvoiceItem insert, update or delete | `(INVOICE, item.getInvoice().getId())` with `lockOnChange`. The id is read from the proxy without initialising it. |
| Collection role `…Invoice.items` | `(INVOICE, ownerId)` with `lockOnChange` |
| Collection roles `…PaymentPromise.invoices` / `.payments` | `(LINK:PROMISE_INVOICE / PROMISE_PAYMENT, ownerId)` via `event.getAffectedOwnerIdOrNull()` |
| `…Payment.allocations` | nothing; the allocation entity events cover it |
| Any event after `HistoryWork.writing = true` | Ignored. Tracked types log ERROR; version entities and `HistoryTransactionRow` are silent. |

#### 5.7.4 `HistoryWriter.write(SessionImplementor s, HistoryWork w)`

This runs from `doBeforeTransactionCompletion`: after the commit-time flush and before COMMIT, on the same connection.

```
0. if AsOf.active(): throw AsOfException.readOnly(...)            // defence in depth
   if w.touched is empty && !w.needsInstant: return
   w.writing = true
1. t = w.pinned != null ? w.pinned : w.instant(appClock)           // clock.instant().truncatedTo(MICROS), bound ONCE, HERE — after all row locks are held
   origin = WriteOrigins.current(currentUser); actor = WriteOrigins.actor(currentUser)   // D4: still bound at commit
   if w.needsInstant: s.persist(new HistoryTransactionRow(w.txId, t))                     // the event point of this tx's outbox rows (D10)
2. group keys by code; process codes in rank order; ids ascending within a code (PPD-01)
3. per code, in chunks of 500 ids:
     opens = versionStore.open(V, ids)                            // valid_to = OPEN
     lives = ids not flagged deleted → s.get(L, id)                // null if deleted in this tx
     nexts = history.snapshotAll(non-null lives, s)                // Java mapping in try (§5.7.6)
     for each id:
       open = opens[id]; next = nexts[id]
       if next == null: if open != null: open.validTo = e(open); continue     // e(x) = max(t, x.validFrom + 1µs)
       if open != null && history.sameState(open, next): continue
       if open != null && flags.lockOnChange && !flags.updated:
          s.lock(live, PESSIMISTIC_WRITE); re-read open (versionStore.open); re-compare
       from = open == null ? t : e(open)
       if open != null: open.validTo = from
       if open == null && !flags.inserted: log WARN "history.gap {code} {id}"
       next.validFrom = from; next.validTo = OPEN; stamp(next, w.txId, origin, actor); s.persist(next)
4. per LINK kind (owner ids ascending):
     current = ownerId → set(targetId) from the link query (owners deleted in this tx → empty set)
     openRows = versionStore.openLinks(kind, ownerIds)
     for each owner: e = max(t, max(openRows.validFrom) + 1µs)
       close rows whose target ∉ current (validTo = e); insert rows for targets ∉ openRows (validFrom = e)
5. s.flush()
```

`stamp` sets `txId`, `origin` (the kind name), `originRefId` (the rule id for AUTOMATION, the pending-change id for APPROVAL) and `actorUserId`.

#### 5.7.5 Concurrency

1. The writer runs after the flush, so every row the transaction wrote is already row-locked. A concurrent writer of the same aggregate blocks until commit, then reads the committed open version. At most one open version exists per entity, and intervals are contiguous.
2. The instant is bound **inside the writer**, after those locks are held. A later lock holder of the same entity therefore gets a later-or-equal `t`. `e(x) = max(t, x.validFrom + 1µs)` absorbs equal instants, cross-instance clock skew and a clock that moves backwards.
3. Links are versioned per `(kind, owner, target)` row. Link-only changes therefore need **no owner lock**; `PaymentPromise` has no `@Version`.
4. A lock is taken only when a root snapshot changed without the root row being written (`lockOnChange && !updated`). The money paths lock the customer first, so lock order is not inverted.
5. On rollback, before-completion never runs and nothing is written. After-completion still removes the work and marks it `done`, so a stale queued process can never run later (§5.7.1, CF-22).
6. `BulkExecutor`'s `REQUIRES_NEW` gives each row its own `HistoryWork` and `txId`. OEMIV sessions spanning several transactions get one `HistoryWork` per transaction.

#### 5.7.6 Failure policy

- Database errors are not caught. They fail the transaction, as the database failure would anyway.
- Java-side mapping errors inside `snapshotAll` are caught **per key**:
  - With `app.history.strict=false` (production): log ERROR `history.capture.failed {code} {id}`, skip the key, and leave the open version stale for the reconciler.
  - With `strict=true` (tests): rethrow.
- The listener and writer throw when `AsOf.active()`. **Writes never run under a point.**
- **Runtime bulk JPQL or native DML on versioned tables is forbidden** unless the same transaction calls `HistoryTracker.touch`. `ModifyingQueryGuardTest` enforces this for runtime code paths. Its allow-list, kept next to the scan in the test (critic finding CF-10):
  - startup upgraders: `**/*Upgrade.java` (every `*SchemaUpgrade` and `common/RowVersionUpgrade`, which calls `statement.executeUpdate` at :40) and `common/schema/**` (`SchemaSupport`);
  - `history/**`;
  - `GmailConnectionRepository` (native insert; not versioned);
  - `CustomerRegionMoveRepository` and the `TaskMoveParticipant` repository method (both sanctioned by `HistoryTracker.touch`).
- **Cost per save:** about 1 select of open versions per touched type, plus 1 update and 1 insert per **changed** aggregate, plus 1 batched child query per touched type that has children. `PaymentService.record` over 5 invoices with credit comes to about 25–35 extra statements. Unchanged aggregates write nothing.

#### 5.7.7 Test support (BE-07)

- `HistoryTestSupport.clearVersions()` runs a bulk `delete from <V>` for every version entity, `EntityLinkVersion`, `HistoryMeta` and (from BE-11) `HistoryTransactionRow`.
- `IntegrationTestBase.@AfterEach historyIsInStep()` asserts `historyReconciler.check().problems()` is empty. This proves capture coverage for every write path the whole suite exercises (§8).
- `HistoryTestHooks implements HistoryFaults` (the `@Primary` bean in `TestBeans`) lets a test inject a mapping failure for a strict-mode test, without `@MockBean`. `B/history/HistoryFaults` (interface) and `B/history/NoHistoryFaults` (no-op `@Component`) are main code (CF-6).

#### 5.7.8 `HistoryReconciler` (BE-07 `check`, BE-08 `reconcile`)

- `ReconcileReport check()` is read-only. Per type, in chunks of 500, it snapshots the live rows and compares them with the open versions. It reports:
  - `missing`: live with no open version
  - `drifted`: `!sameState`
  - `orphaned`: open with no live row
  - `duplicates`: more than one open version per id
  - `linkDrift`
- `record ReconcileReport(int checked, List<String> missing, List<String> drifted, List<String> orphaned, List<String> duplicates, List<String> linkDrift, int repaired) { List<String> problems(); }`
- `ReconcileReport reconcile()`:
  - Run `check()`.
  - For each candidate chunk, open a `REQUIRES_NEW` transaction under `WriteOrigin.reconcile()` and call `historyTracker.touch(type, ids, true)`, which **locks each live row** before the writer re-reads it (lock and recheck).
  - Orphans close through the same path. Duplicates close all but the most recent `valid_from`.
  - Log WARN with the counts.
- Schedule: `@Scheduled(fixedDelayString = "${app.history.reconcile-interval-ms:86400000}", initialDelayString = same)`, wrapped in try/catch-log.

#### 5.7.9 `HistoryBaseline implements SmartInitializingSingleton` (BE-08)

`afterSingletonsInstantiated()` runs after every `InitializingBean`, including `RegionSchemaUpgrade`, and before the web server and `DataSeeder`.

1. In a `TransactionTemplate`, take `em.find(HistoryMeta.class, 1, PESSIMISTIC_WRITE)`.
   - If it is absent, persist `{id:1}` in its own transaction. On `DataIntegrityViolationException`, retry the locked find.
   - The lock is held for the whole run, so instances that boot together serialise.
2. `first = meta.baselineCompletedAt == null`. For each `TypeHistory`, keyset over:
   `select e.id from <Live> e where e.id > :last and not exists (select 1 from <V> v where v.id = e.id and v.validTo = :open) order by e.id` (500 per chunk).
   Each chunk runs in `REQUIRES_NEW` under `WriteOrigin.schema()`: `historyTransactions.current(em).pin(first ? startedAt : null)`, then `historyTracker.touch(type, ids, false)`.
   `startedAt = appClock.instant().truncatedTo(MICROS)` is computed once, when `first` is true and a chunk is non-empty.
3. If `first`: set `started_at` (only if any row was copied) and `baseline_completed_at`. Otherwise any rows found were gaps, inserted "from now" with a WARN.
4. On an exception, log ERROR and continue startup. A missing baseline never breaks saves (the gap rule); it only narrows history.

#### 5.7.10 `HistoryRange` (BE-08) and `HistoryController`

- `Optional<LocalDate> historyStartsOn()` reads `history_meta.started_at` on every call (never cached).
- `requireAvailable(LocalDate d)` throws `AS_OF_BEFORE_HISTORY` when `d` is before the start.
- BE-08 creates `HistoryRange` as a plain `@Component`. BE-09a adds `implements AsOfSupport` and deletes `NoHistoryYet`, so exactly one `AsOfSupport` bean exists in every slice (§5.2.2).
- `HistoryController`:
  - `GET /api/history/range`: any authenticated user, `@AsOfIgnored`.
  - `POST /api/admin/history/reconcile`: USER_MANAGE, method `@AsOfIgnored`.

#### 5.7.11 Semantics of a historical read

- A point is bound only by `ViewScopeInterceptor` on AWARE handlers, or by `AsOf.with` in tests.
- Everything below is written **once** so that it returns the live behaviour when no point is bound. **Live SQL is byte-for-byte unchanged.**

#### 5.7.12 `VersionStore` and `Temporal`

```java
// VersionStore (@Component; JPQL; every IN chunked at 500; enum comparisons always by parameter)
<V extends VersionRow> List<V> open(Class<V> v, Collection<Long> ids);
<V extends VersionRow> List<V> validAt(Class<V> v, Collection<Long> ids, Instant p);          // v.validFrom <= :p and v.validTo > :p
<V extends VersionRow> List<V> validAtBy(Class<V> v, String attr, Collection<Long> values, Instant p);
List<EntityLinkVersion> openLinks(String kind, Collection<Long> owners);
List<EntityLinkVersion> linksAt(String kind, Collection<Long> owners, Instant p);

// Temporal (static)
public static Root<?> from(AbstractQuery<?> q, Class<?> live);                       // q.from(HistoryModel.rootFor(live))
public static Predicate validOrNull(From<?, ?> f, CriteriaBuilder cb);                // null for live froms
public static Root<?> fromValid(AbstractQuery<?> q, Class<?> live, CriteriaBuilder cb, List<Predicate> where);
public static From<?, ?> join(From<?, ?> from, String attribute, JoinType type, CriteriaBuilder cb);
public static <R> Subquery<R> subquery(CommonAbstractCriteria parent, Class<R> result, Class<?> live, CriteriaBuilder cb, SubBody<R> body);
@FunctionalInterface public interface SubBody<R> { Expression<R> build(Root<?> root, Subquery<R> sq, List<Predicate> where); }
```

**`join` rules:**
- **Live from:** `LEFT` → `ColumnDef.leftJoin`; other join types → `from.join(attribute, type)`.
- **Version from, non-temporal attribute** (`salesPoc`, `collectionPoc`, `user`, `region`, `product`): join the read-only association to the live target.
- **Version from, registered navigation:**
  - alias = `"t_" + attribute + "_" + type`;
  - reuse any `((SqmFrom<?, ?>) from).getSqmJoins()` entry whose explicit alias matches (`getJoins()` does not see entity joins; probe-verified);
  - otherwise `JpaEntityJoin j = ((JpaFrom) from).join(target, SqmJoinType.from(type)); j.alias(alias); j.on(cb.and(nav.on(j, from, cb), validOrNull(j, cb)))`.

**Navigations:**

| From (version) | Attribute | Target | ON |
|---|---|---|---|
| Invoice, Payment, Promise, CustomerPoc, Task | `customer` | `CustomerVersion` | `j.id = from.customerId` |
| PaymentAllocation | `payment` / `invoice` | `PaymentVersion` / `InvoiceVersion` | `j.id = from.paymentId` / `from.invoiceId` |
| Promise | `invoices` / `payments` | `EntityLinkVersion` | `j.kind = 'PROMISE_INVOICE' / 'PROMISE_PAYMENT' and j.ownerId = from.id` |

#### 5.7.13 `TableQueryExecutor` changes (`B/common/query/TableQueryExecutor.java`)

- **`run`:** if `AsOf.active() && HistoryModel.isVersioned(type)`, call `runHistorical`:
  1. `cq.from(versionClass)`, with **no fetch joins**;
  2. the same where, order, paging and 5000 cap;
  3. `HistoryModel.of(type).rehydrate(results, new Rehydration(AsOf.current(), versionStore, em, userRepository))`;
  4. count as below.
- **`count`, `ids`, `aggregate`:** `Root<?> root = Temporal.from(cq, type)`.
- **`predicates(...)`:** add `Temporal.validOrNull(root, cb)` first when it is non-null.
- `inScope` and `orderBy` are unchanged. The `id` tiebreak is the business id.
- **`ColumnDef.nested(assoc, attr)`** becomes `(root, q, cb) -> Temporal.join(root, assoc, JoinType.LEFT, cb).get(attr)`. As a result, `customerName` is the name at T, and POC names stay live.

#### 5.7.14 Sites switched to `Temporal` (written once, correct in both modes; BE-09a)

| File | Change |
|---|---|
| `TableSchemas.customerOutstanding` (135–144) | `Temporal.subquery(q, BigDecimal.class, Invoice.class, …)` |
| `TableSchemas.pocSeatPredicate` (146–180) | `Temporal.subquery(…, CustomerPoc.class, …)` |
| `TableSchemas.promiseLinkPredicate` (233–266) | `Temporal.subquery(…, PaymentPromise.class, …)`, then `Temporal.join(promise, association, INNER, cb)` |
| `ScopeResolver.customerHasPocSeat` / `customerHasInvoiceOwnedBy` (135–151) and the TASK parent subqueries | `Temporal.subquery` |
| `CustomerService.outstandingSubquery` / `seatSubquery` (103–127) | `Temporal.subquery` |
| `DashboardService` | §5.12 |
| New columns: `regionId` (`referenceId("region")`), `pendingApproval` (EXISTS on `pending_changes` with the interval predicate), `assigneeUserId` (§5.9.3) | written with `Temporal` from the start |

`TemporalConventionTest` fails on any `.from(X.class)` or `.join("…")` where X is a versioned live class, in `common/query`, `poc`, `dashboard`, `task`, `assignee` or `*Service.java`, outside `history/`.

#### 5.7.15 Rehydration (`history/types/Historical*`, marker interface `Historical`)

- The classes are `HistoricalCustomer extends Customer implements Historical`, and likewise for Invoice, InvoiceItem, Payment, PaymentAllocation, PaymentPromise, Dispute, CustomerPoc, Product and Task. They are plain unmapped subclasses: `persist`/`merge` throw "Unable to locate persister" (probe-verified), and `save` surfaces as `InvalidDataAccessApiUsageException`.
- `Rehydration` has memoised batch loaders:
  - `customersAt`, `invoicesAt`, `paymentsAt`, `productsAt` (via `validAt`);
  - `usersOrStub(ids)` (`findAllById`; a missing id becomes a transient `User(id, fullName = "Deleted user", active = false)`);
  - `linksAt`;
  - `allocationsAt(paymentIds)`.

| Object | Populated with |
|---|---|
| `HistoricalInvoice` | Scalars. `customer` = a shallow `HistoricalCustomer` at T. `salesPoc` from `usersOrStub`. `items` from `items_json`, each with `invoice = this` and a `HistoricalProduct` from `productsAt` holding the name at T. A product with no version at T (impossible after the baseline; products used on invoices cannot be deleted) falls back to the live product through `LiveReadGuard.allow`. |
| `HistoricalPayment` | Scalars, `customer`, `collectionPoc`. `allocations` at T, each with `payment = this` and a shallow `HistoricalInvoice`. |
| `HistoricalPaymentPromise` | Scalars (including `updatedAt`), `customer`, `collectionPoc`. `invoices`/`payments` from the links at T. |
| `HistoricalDispute` | Scalars; from BE-19 a null `internal` in an old version reads as `false` (§3.11) |
| `HistoricalProduct`, `HistoricalTask` | Scalars |
| `HistoricalCustomerPoc` | Scalars, `customer` (shallow), `user` from `usersOrStub` |
| `region` (all) | `em.getReference(Region.class, regionId)`, a live proxy; region names are live |

Rehydrated DTOs carry `version = null`.

#### 5.7.16 `Records` and `LiveReadGuard`

```java
@Component public class Records {
  @Transactional(readOnly = true) public <T> Optional<T> find(Class<T> live, Long id);        // live: em.find; as-of: validAt → rehydrate
  @Transactional(readOnly = true) public <T> List<T> findAll(Class<T> live, Collection<Long> ids);
}
public class LiveReadGuard implements PostLoadEventListener {   // registered by HistoryListenerRegistrar
  onPostLoad: if (AsOf.active() && !ALLOW.get() && HistoryModel.isVersioned(Hibernate.getClass(entity))) throw AsOfException.unsupported();
  public static <T> T allow(Supplier<T> body);
}
```

On any read path reachable from an AWARE endpoint, `xRepository.findById(id)` for a versioned X becomes `records.find(X.class, id)`.

#### 5.7.17 Read paths outside the executor (BE-09a/09b by-id and the customers list; BE-10 the rest)

| Site | Change |
|---|---|
| `CustomerService.get` (:135), `InvoiceService.get` (:262), `ProductController GET /{id}` (:82) (BE-09a); `PaymentService.get` (:274), `PaymentPromiseService.get` (~:507), `DisputeService.get` (:145) plus its customer-name lookup (:156) and `describeTarget` (:197–212), `PaymentController.credit` (:103) (BE-09b); `TaskService.get` (BE-12) | `records.find(...)`. Not-found messages are unchanged (AUTH-08). |
| `EmailTargets.loadUnscoped` (~:228–237) **and the PRODUCT branch of `loadScoped`** (:247–248) (BE-09b) | CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE and PRODUCT go through `records.find`. Without the `loadScoped` change the product email tab (`GET /api/emails?entityType=PRODUCT`) would hit `LiveReadGuard` in as-of mode (critic finding CF-23). |
| `CustomerService.toDtos` (:152–202) (**BE-09a**, because the customers list and detail at T depend on it; critic finding CF-5) | When as-of: `historyQueries.sumOutstandingByCustomerAt(ids, p)`, `sumOverdueByCustomerAt(ids, Today.date(), p)` (the `InvoiceRepository:34-53` JPQL on `InvoiceVersion`, enum parameter), and `historyQueries.seatsAt(ids, p)` ordered `pocType asc, primary desc, id asc` |
| `PocService.listFor` | As-of: `historyQueries.seatsAt([customerId], p)` |
| `DocumentService.onRecord` (:205–214) | As-of: `uploadedAt <= p AND (deletedAt IS NULL OR deletedAt > p)` replaces `isFalse(deleted)`. `toDto` flags false. |
| `EmailService.list` (:322–334) / `get` | Add `occurredAt <= p` / 404 when `occurredAt > p`. Delivery status stays current. |
| `AuditController.history` / `AuditTimelineService.timeline` | Keep entries (recorded and derived) with `createdAt <= p`. Related-record discovery (:68, :85, :99) runs inside `LiveReadGuard.allow` using current relationships. `readOrGone` does **not** fall back in as-of mode: no version at T gives 404. The `pendingStatus` decoration comes from the interval (BE-15). |
| `DashboardService` | §5.12 |
| `/api/pocs/*`, `/api/emails/context`, pickers | IGNORED (live) |

### 5.8 Outbox publishing: `B/automation/event` (A1, A5, P2)

#### 5.8.1 Classes

```java
@Entity AutomationEvent            // §3.6; plain strings; no FK; no unique
AutomationEventRepository          // conditional-UPDATE claim/finish queries (§5.10.6), findDueIds, findExpiredLeaseIds, purge
@Component public class AutomationEvents {       // injects ONLY the repository (insert), appClock, CurrentUser, HistoryTransactions, EntityManager
  @Transactional(propagation = Propagation.MANDATORY) public void created(TriggerEntity type, Long id);
  @Transactional(propagation = Propagation.MANDATORY) public void updated(TriggerEntity type, Long id, String change);
  @Transactional(propagation = Propagation.MANDATORY) public void invoiceMoney(Long invoiceId, Long customerId, String change);
      // = updated(INVOICE, invoiceId, change) + updated(CUSTOMER, customerId, "OUTSTANDING")   (§5.8.3)
  @Transactional(propagation = Propagation.MANDATORY) public void children(Long customerId, String change);
      // one CHILDREN event standing for every invoice and payment of the customer (M1, customer rename)
}
final class PendingEvents implements TransactionSynchronization { ... }
```

**Buffering**
- Every method looks for a `PendingEvents` among `TransactionSynchronizationManager.getSynchronizations()` and registers one if none is found. `REQUIRES_NEW` suspends synchronizations, so each `BulkExecutor` row gets its own buffer.
- The buffer is a `LinkedHashMap<Key(kind-group, TriggerEntity, Long id), Draft(kind, LinkedHashSet<String> changes, Provenance p)>`. `Provenance(originKind, ruleId, depth, actorUserId, pendingChangeId)` is taken from `WriteOrigins` **at the first call for the key**, while any `RunAs` is still in force (D4).
- `id == null || id <= 0` is **ignored**. `PaymentService.applyTo` runs before the payment is saved (DR-auto S7).
- CREATED absorbs UPDATED in either order, and the change labels are unioned. This is the per-transaction dedupe of P2 by `(entityType, entityId, kind)`, where CREATED and UPDATED merge into CREATED. CHILDREN events are deduplicated per customer on their own key.

**`beforeCommit(boolean readOnly)`**
- If `readOnly`, log WARN "event published in a read-only transaction; dropped" and return. If the buffer is empty, return.
- `txId = historyTransactions.txId(em)`. This registers the transaction's `HistoryWork` if none exists and asks the writer to record the instant (§5.7.4). It binds nothing and reads no table.
- Then `repository.saveAll(rows)`. Each row has:
  - `occurred_at = appClock.instant()`, `history_tx_id = txId`
  - `actor_user_id`, `origin`, `caused_by_rule_id` and `depth` from the draft's provenance snapshot
  - `status = PENDING`, `attempts = 0`, `claims = 0`
  - `changes` fitted to 300 with `Fit.to`

**`afterCompletion`** clears the buffer.

**Guarantees**
- The save path **reads no automation table**, which T-O1 asserts.
- A rolled-back transaction writes nothing.
- The rows cannot fail an insert: no FK, no unique constraint, fitted strings, no enum or CHECK types. `OutboxSchemaTest` (BE-11) asserts the schema side through `information_schema`: only the primary key; no FK, unique or CHECK constraint; no H2 `ENUM` column type; every fitted column at least as long as its fit length (critic finding PC-12).
- The `history_transactions` insert happens inside the history writer, whose failure policy is §5.7.6 (database errors fail the transaction as any flush would; the row itself has no constraint beyond its identity key).

#### 5.8.2 Call sites

Each call goes after the method's last save or audit, on the **applied** path. After B2, it goes in the `xOrHold` body after the gate, so a held request publishes nothing.

| # | Method | Call |
|---|---|---|
| C1 | `CustomerService.create` | `created(CUSTOMER, c.getId())` before return (~:247) |
| C2 | `CustomerService.update` | `updated(CUSTOMER, id, "DETAILS")` (~:296); when the **name** changed, also `children(id, "CUSTOMER_NAME")`, because `customerName` is a column of the invoice and payment schemas |
| C3 | `PocService.add`, `remove`, `setPrimary` | `updated(CUSTOMER, customerId, "POC_SEATS")` after their audits |
| C4 | `PaymentService.applyTo` (private) | Each invoice saved: `invoiceMoney(inv.getId(), cid, "PAYMENT")`. If `remaining > 0`: `updated(CUSTOMER, …, "CREDIT")`. **Never the payment** (its id may be null). |
| C5 | `PaymentService.reverseAllocations` | Each invoice: `invoiceMoney(…, "PAYMENT")`; credit reversed: `updated(CUSTOMER, …, "CREDIT")` |
| C6 | `CreditLedger.applyTo` (after the early return) | `updated(CUSTOMER, …, "CREDIT")`, `invoiceMoney(invoice.getId(), cid, "PAYMENT")`, and for each payment drawn `updated(PAYMENT, p.getId(), "ALLOCATION")` |
| C7 | `CreditLedger.refund` (amount > 0) | `updated(CUSTOMER,"CREDIT")`, `invoiceMoney(…, "PAYMENT")`, and each payment whose `creditApplied` changed `updated(PAYMENT,"ALLOCATION")` |
| I1 | `InvoiceService.create` | `created(INVOICE, saved.getId())` and `updated(CUSTOMER, cid, "OUTSTANDING")` before return (~:98); the INVOICE CREATED absorbs C6's UPDATED |
| I2 | `InvoiceService.update` (also reached from `reassignSalesPoc` and bulk) | `updated(INVOICE, id, "DETAILS")` |
| I3 | `InvoiceService.cancel` | `invoiceMoney(id, cid, "STATUS")` |
| I4 | `InvoiceService.cancelWithRefund` | `invoiceMoney(id, cid, "STATUS")` |
| I5 | `InvoiceService.replaceItems` | `invoiceMoney(id, cid, "ITEMS")` |
| I6 | `DisputeService.applyInvoiceChange`, `update_notes` | `updated(INVOICE, invoiceId, "DETAILS")` |
| P1 | `PaymentService.record` | `created(PAYMENT, saved.getId())` before return |
| P2 | `PaymentService.update` (also reached from reassign and bulk) | `updated(PAYMENT, id, "DETAILS")` |
| P3 | `PaymentService.voidPayment` | `updated(PAYMENT, paymentId, "STATUS")` (the invoices come from C5) |
| P4 | `PaymentService.updateAmount` | `updated(PAYMENT, paymentId, "AMOUNT")` (the invoices come from C4/C5) |
| P5 | `DisputeService.applyPaymentChange`, `update_meta` | `updated(PAYMENT, paymentId, "DETAILS")` |
| M1 | `AutomationMoveParticipant.afterCustomerMoved` | `updated(CUSTOMER, cid, "REGION")` and **one** `children(cid, "REGION")`. The consumer expands the CHILDREN event (§5.10.6), so the user's move writes two outbox rows however many children move (critic finding PC-4). |

**Not published, by design:**
- deletes;
- promise, dispute, task, document and email writes (not trigger entities);
- `*SchemaUpgrade` backfills;
- test fixtures that save through repositories;
- held pending changes (R8).

Applying an approved change goes through these same methods, so it **does** publish, with `origin = APPROVAL`.

#### 5.8.3 Semantics

One definition, enforced by `TriggerCoverageTest` and `RuleValidator` (critic finding PC-5):

- **"Updated" means that the value of some column in the record's `TableSchemas` entry changed because of a write.** That covers:
  - the record's own row and its embedded children (items, allocations, links, seats), including side effects: allocation, credit, dispute application and region;
  - **cross-record columns whose source is a trigger entity**, published at the source's write:
    - `CUSTOMERS.outstanding` (and `overdueAmount`, §5.10.2): every write that changes an invoice's `total`, `paidAmount` or `status` publishes `updated(CUSTOMER, cid, "OUTSTANDING")` through `invoiceMoney` (I1, I3–I5, C4–C7);
    - `INVOICES.customerName` and `PAYMENTS.customerName`: a customer rename publishes `children(cid, "CUSTOMER_NAME")`.
- **Not updates:**
  - clock-driven columns (`overdue`, `DaysOverdue`, relative date presets), because "becoming overdue" is not a write;
  - names of people (`salesPocName`, `collectionPocName`), because users are not trigger entities.
  - `RuleValidator` returns a **warning** (not an error) when an `ENTITY_UPDATED` or `ENTITY_CREATED_OR_UPDATED` condition uses one of these columns: "Becoming overdue is not an update; use a daily schedule to catch it". The rule editor shows it.
- Change labels are diagnostic only:
  - CUSTOMER: DETAILS, POC_SEATS, CREDIT, OUTSTANDING, REGION
  - INVOICE: DETAILS, STATUS, ITEMS, PAYMENT, REGION (via CHILDREN), CUSTOMER_NAME (via CHILDREN)
  - PAYMENT: DETAILS, STATUS, AMOUNT, ALLOCATION, REGION (via CHILDREN), CUSTOMER_NAME (via CHILDREN)
- A record created and then modified in the same transaction produces one CREATED event.
- A **CHILDREN** event stands for an UPDATED event on each of the customer's invoices and payments that existed at the event point. It is evaluated only against `ENTITY_UPDATED` and `ENTITY_CREATED_OR_UPDATED` rules on INVOICE and PAYMENT.

#### 5.8.4 Coverage oracle (replaces DR-auto's `TouchRecorder` integrator)

`TriggerCoverageTest` uses the history capture as the oracle, so no test-only Hibernate integrator is needed. For each operation it asserts that every version row opened during the scenario has an outbox row for the corresponding trigger entity:
- `CUSTOMER` and `SEAT` versions → CUSTOMER
- `INVOICE` → INVOICE, **or** a CHILDREN event for the invoice's customer in the same transaction
- `PAYMENT` → PAYMENT, or a CHILDREN event for its customer
- `ALLOCATION` → INVOICE and PAYMENT
- an `INVOICE` version whose `total`, `paid_amount` or `status` differs from the previous version → also CUSTOMER (`OUTSTANDING`)
- a `CUSTOMER` version whose `name` differs from the previous version → also a CHILDREN event

Every outbox row written by a save (CREATED, UPDATED, CHILDREN) carries a non-null `history_tx_id` that has a `history_transactions` row after commit; SCHEDULED, MANUAL and REPLAY rows have none.

The operations covered:
- customer create, update and rename;
- POC seat add, remove and set-primary;
- invoice create (with credit), update, cancel, `cancelWithRefund`, `replaceItems`;
- payment record (with excess), update, void, `updateAmount`;
- dispute `update_notes` and `update_meta`;
- region move, which must write **exactly two** `automation_events` rows (CUSTOMER UPDATED and CHILDREN) for a customer with 20 invoices and 5 payments.

### 5.9 Assignees and tasks: `B/assignee`, `B/task` (A3, A6)

#### 5.9.1 Assignee model

- **Wire format.** The existing `EmailDtos.EmailToken`: `{"type":"USER","userId":7}` or `{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"}`. It is stored as `record_assignees` rows.
- **Two components, so that no constructor-injection cycle can form** (critic finding CF-3). `EmailTargets` injects `CustomerService`, `InvoiceService`, `PaymentService`, `PaymentPromiseService` and `DisputeService` (`EmailTargets.java:120-124`); anything those services inject must therefore never reach `EmailTargets`.

| Component | Dependencies | Used by |
|---|---|---|
| **`AssigneeStore`** (`B/assignee`) | `RecordAssigneeRepository`, `UserRepository`, `Clock`, `CurrentUser` only | The domain services: `TaskService`, `DisputeService`, `PaymentPromiseService` (and `TaskCascade`, which stays repository-only) |
| **`AssigneeService`** (`B/assignee`) | `AssigneeStore`, `EmailTargets`, `VisibilityProbe`, `RegionAccessQueries`, `NotificationService` | Controllers (`TaskController`, `DisputeController`, `PaymentPromiseController`, `AssigneeController`), the action handlers, `ApprovalService`, and `TaskService` (no service that `EmailTargets` injects depends on `TaskService`) |

  Any IntegrationTestBase test starting the context proves the absence of a cycle; BE-12 and BE-19 (the slices that add assignee dependencies to domain services) list "context starts" in their acceptance.
- **`AssigneeStore.validate(EmailEntityType context, List<EmailToken> tokens, Set<RoleRef> allowedRoles)`**, static helpers only:
  - The type must be USER or ROLE. CUSTOMER gives "Assignees are people or roles".
  - USER goes through `EmailAddressing.requireActiveInternalUser(UserRepository, Long)`, made public static.
  - ROLE goes through `EmailAddressing.requireOfferedRole(...)`, made public static and including the `defaultLevel` fallback. The allowed-role sets come from the new static `EmailTargets.offeredRoles(type)` (today's `ROLES_OFFERED.get(type)`), so no `EmailTargets` instance is needed.
  - At most 10 tokens. Duplicates (by `RoleRef.token()` or `USER:id`) are removed.
- **Roles allowed per owner:**

| Owner | Allowed roles |
|---|---|
| Task | `EmailTargets.offeredRoles(task entity type)` (static) |
| Dispute | Customer-level roles. INVOICE target adds `SALES_POC@RECORD`; PAYMENT target adds `COLLECTION_POC@RECORD`; CUSTOMER target adds nothing. |
| Promise co-assignees | `offeredRoles(PROMISE)` |

- **`AssigneeStore.replace(ownerType, ownerId, tokens)`**, under the owner row's lock, soft-removes rows no longer wanted (`removed_at`, `removed_by`), inserts new ones with `added_at` from the clock, and returns the **added** tokens. `AssigneeStore.list(ownerType, ownerId)` returns the active tokens.
- **`AssigneeService.resolve(ownerType, ownerId, Target target)`**, live:
  - USER → that user, if active.
  - ROLE → **all** active holders, `target.holders(ref)` (holders already region-filtered, §5.6.7), **kept only when they hold the owner type's VIEW privilege** (`TASK_VIEW`, `DISPUTE_VIEW`, `PROMISE_VIEW`) in the record's region (`regionAccess.holds`), so nobody is assigned a link they would get 403 on (critic finding PC-2).
  - `Target` comes from `EmailTargets.load`: the task's entity, DISPUTE, or PROMISE.
- **`AssigneeService.probe(tokens, type, id, regionId)`** returns `(kept, warnings)`, dropping USER tokens that fail `VisibilityProbe`. **`AssigneeService.notifyAssigned(ownerType, ownerId, target, addedTokens, actorOrNull)`** notifies resolved, visible, active people, at most 20 per change.
- **Order of work.** Resolution and probing are **read-only and happen before** the domain call takes the customer lock (§5.10.6). The domain call stores already-validated tokens through `AssigneeStore`. Notification follows the domain call in the same transaction.
- **Visibility ("nothing escapes permissions").**
  - `VisibilityProbe.canSee(Long userId, EmailEntityType type, Long id, Long regionId)` is **non-throwing and transaction-neutral** (critic finding CF-2). It loads `principalFor(userId)` (inactive or customer login → false) and, under `RunAs.call(p, ViewScope.LIVE_ALL, WriteOrigin.system(), …)`, answers:
    - `currentUser.has(VIEW_PRIVILEGE(type))`, which returns false rather than throwing;
    - then `scopeResolver.tier(kind, VIEW, regionId)`: FULL → true; NONE → false; BOOK → `queryExecutor.inScope(kind.type, kind.schema, id, scopeResolver.scope(kind, VIEW, GRANTED).predicates())`.
    - It never calls `EmailTargets.requireVisible` or any exception-throwing `@Transactional` service method. Such a call inside the caller's write transaction would mark it rollback-only when it failed, so "drop the token and carry on" would end in `UnexpectedRollbackException` at commit.
  - **General rule for §5.9–§5.11:** no code may catch an exception that crossed a joined `@Transactional` boundary and then continue in the same transaction. Where a failure must be tolerated, either use a non-throwing check (as here), or run the risky part in its own `REQUIRES_NEW` transaction and catch outside it (as `runs.ensure` in `RuleRunner` and `ActionExecutor` do).
  - When automation adds a USER token that fails the probe, it drops the token with warning "X cannot see this record, so was not assigned". The UI gets 400 `fieldErrors.assignees`.
- **Documents.** Nothing automation creates carries an attachment (tasks, promises, disputes and emails are text only; §10.1). That is how the predecessor PRD's "internal/shared rule on documents" is met: no automation path can expose an INTERNAL document.

#### 5.9.2 `Task` and `TaskService`

**Status transitions:**
- OPEN → IN_PROGRESS, DONE or CANCELLED
- IN_PROGRESS → OPEN, DONE or CANCELLED
- DONE → OPEN
- CANCELLED → OPEN (TASK_MANAGE only)
- Same status → 400.

DONE sets `completed_at` and `completed_by`; reopening clears them. CANCELLED sets `cancelled_at`.

```java
@Transactional public Task create(TaskDtos.CreateTaskRequest req);                                    // UI
@Transactional(propagation = MANDATORY) public Created createForAutomation(TaskDraft d);              // (task, warnings)
@Transactional public Task patch(Long id, TaskDtos.PatchTaskRequest req);
@Transactional public Task changeStatus(Long id, TaskStatus to, Long version);
@Transactional(readOnly = true) public Task get(Long id);                                              // records.find + forTasks visibility → 404
```

**Create (UI and automation).** Read-only work first, the lock right before the write (critic finding PC-4):
1. Require TASK_MANAGE; `requireLive`.
2. `TaskTargets.load(type, id)` through the scoped `get` (label, `customerId`, region); out of scope → 404.
3. Validate assignees with `AssigneeStore.validate`, then probe USER tokens with `AssigneeService.probe`: the UI gets 400 `fieldErrors.assignees`; automation arrives with tokens its handler already probed (`TaskDraft.probed = true`) and the step is skipped.
4. `customerRepository.findByIdForUpdate(customerId)` (re-entrant in the automation action transaction).
5. `region = customer.region`, then `requireVisible(CUSTOMER)` and `requireIn(TASK_MANAGE, region)`.
6. Save the task and its assignees (`AssigneeStore.replace`). **History is captured by the listener**, so there is no hand-written history.
7. Audit `TASK_CREATED`, then `AssigneeService.notifyAssigned`.

**General rules:**
- Every write carries `version`; a mismatch is 409.
- Every write audits under `"TASK"`: `TASK_CREATED`, `TASK_UPDATED`, `TASK_STATUS_CHANGED`, `TASK_ASSIGNEES_CHANGED`, `TASK_CANCELLED_WITH_RECORD`.
- **Rights:**
  - TASK_MANAGE **in the task's region** may do everything.
  - An **effective assignee** (live, §5.9.1: USER tokens, plus role holders who hold TASK_VIEW in the region) may move OPEN ↔ IN_PROGRESS, set DONE, and reopen DONE → OPEN.
  - Anyone else gets 403.
- **Notifications:**
  - `TASK_ASSIGNED` ("Task assigned: <title>", link `/tasks/{id}`) goes to people newly resolved, visible and active. The actor is excluded for USER origin; the owner is included for AUTOMATION.
  - `TASK_DONE` goes to the creator, when the origin is USER and the completer is someone else.
- **`TaskCascade.onCustomerDeleted(customerId)`** is called from `CustomerService.delete` next to `emailCascade`. **Through the ORM, not bulk JPQL** (tasks are versioned): it loads the tasks, sets `entityDeleted = true`, cancels OPEN and IN_PROGRESS ones, and audits.
- **`TaskMoveParticipant`** runs a native `update tasks set region_id = :to where customer_id = :cid`, then `historyTracker.touch(Task.class, ids, false)`.
- **`UserController.pocReferenceCount`** (236–241) gains `recordAssigneeRepository.countByKindAndUserIdAndRemovedAtIsNull("USER", id)`.

#### 5.9.3 `AssignedTo` predicate (live and as-of)

`AssignedTo.predicate(String ownerType, Root<?> root, CriteriaQuery<?> q, CriteriaBuilder cb, Long userId)` produces:

```
EXISTS (select 1 from RecordAssignee a where a.ownerType = :ownerType and a.ownerId = root.id
        and <live: a.removedAt is null | as-of p: a.addedAt <= p and (a.removedAt is null or a.removedAt > p)>
        and ( (a.kind='USER' and a.userId=:u)
           or (a.kind='ROLE' and a.roleLevel='CUSTOMER' and EXISTS Temporal.subquery(CustomerPoc: s.customer.id = <root customer id>
                 and s.user.id=:u and s.pocType = <COLLECTION if a.role='COLLECTION_POC' | SUCCESS if 'CUSTOMER_SUCCESS_POC'>))
           or <RECORD branches> ))
```

RECORD branches, all through `Temporal.subquery`:
- **TASK:** `a.role = 'SALES_POC' ∧ root.entityType = 'INVOICE' ∧ ∃ Invoice(id = root.entityId, salesPoc.id = :u)`; or `a.role = 'COLLECTION_POC' ∧ root.entityType = 'PAYMENT' ∧ ∃ Payment(id = root.entityId, collectionPoc.id = :u)`.
- **DISPUTE:** the same, using `root.targetType` and `root.targetId`.
- **PROMISE:** `a.role = 'COLLECTION_POC' ∧ root.collectionPoc.id = :u`.

Notes:
- The root's customer id is `root.customerId` for Task and Dispute, and `root.customer.id` for Promise.
- The role → `PocType` mapping is written as `cb.or` of two conjunctions, not as a Java ternary.
- In as-of mode seats are taken **at T**. This improves on DR-auto, which used current seats, because seats are versioned here.

#### 5.9.4 `TableSchemas.TASKS` (entity `tasks`, default sort `dueDate,asc`)

| Column | Type | Notes |
|---|---|---|
| `id` | NUMBER | |
| `title` | TEXT | |
| `status` | ENUM | |
| `dueDate` | DATE | |
| `overdue` | BOOLEAN | custom filter: `status in (OPEN, IN_PROGRESS) and dueDate < Today.date()` |
| `entityType` | ENUM | |
| `entityId` | NUMBER | |
| `entityLabel` | TEXT | |
| `customerId` | REFERENCE `customer` | path `root.get("customerId")` |
| `assigneeUserId` | REFERENCE `staffUser` | custom `AssignedTo` over EQ, IN, IS_EMPTY, IS_NOT_EMPTY; not sortable; path `root.get("id")` |
| `origin` | ENUM | USER, AUTOMATION |
| `ruleId` | NUMBER | |
| `regionId` | REFERENCE `region` | `referenceId("region")` |
| `regionName` | TEXT | `nested("region","name")` |
| `createdAt`, `completedAt`, `updatedAt` | DATE | |

- Register the schema in `buildIndex`.
- `TableSchemaController.VIEW_PRIVILEGE` becomes `Map<String, List<String>>` built with **`Map.ofEntries`** (it is at the 10-pair `Map.of` limit today). `mayRead` becomes any-of.
- Entries added:

| Key | Privileges (any of) | Slice |
|---|---|---|
| `tasks` | TASK_VIEW | BE-12 |
| `regions` | REGION_VIEW, USER_MANAGE | BE-05 |
| `automation-rules`, `automation-runs` | AUTOMATION_VIEW | BE-16 |
| `approvals` | CHANGE_APPROVE, INVOICE_VIEW, PAYMENT_VIEW, PROMISE_VIEW, DISPUTE_VIEW | BE-15 |

- `TableSchemas.DISPUTES` and `PROMISES` gain `assigneeUserId`, the same custom column (BE-19).
- `DISPUTES` gains `internal` (BOOLEAN, `.pocRestricted()`).

#### 5.9.5 "My tasks"

- `GET /api/tasks/mine` uses scope = `forTasks` ∧ `AssignedTo(me)`, with **`lockedFilters: []`**. It is not a locked filter (R14, G8).
- `/mine/count` counts OPEN and IN_PROGRESS tasks for the nav badge.
- The task tab on a record uses `GET /api/tasks?entityType&entityId`. The customer tab uses `customerId`, which also matches tasks on the customer's invoices and payments, because it is a column.

### 5.10 Automation engine: `B/automation/**` (A1–A5)

#### 5.10.1 Package layout

```
automation/AutomationSchemaUpgrade, AutomationProperties (@ConfigurationProperties("app.automation"), mutable setters for tests)
automation/rule/     AutomationRule(+Repository; @ElementCollection regionIds), AutomationRuleRevision(+Repository), TriggerKind, TriggerEntity,
                     SuspendReason, RuleDefinition, RuleValidator, RuleService, RuleController, RuleDtos, RequiredPrivileges, RuleAsOfLists,
                     RuleVisibility (who may see a rule; §5.10.10)
automation/action/   RuleAction (sealed) {CreateTaskAction, CreatePromiseAction, CreateDisputeAction, SendEmailAction}, Repeat, DueSpec, AmountSpec,
                     ActionHandler (prepare / perform / preview), ActionHandlers, CreateTaskHandler, CreatePromiseHandler,
                     CreateDisputeHandler, SendEmailHandler, RecordContext, RecordContexts, Prepared, ActionResult, ActionPreview,
                     ActionSkippedException
automation/placeholder/ PlaceholderCatalogue, PlaceholderValues, PlaceholderRenderer (UnknownPlaceholderException), Rendered
automation/eval/     RuleEvaluator, RuleDefinitionException
automation/event/    (§5.8), EventPoints (history_tx_id → AsOfPoint)
automation/run/      AutomationRun(+Repository), RunStatus, AutomationEffect(+Repository), EffectStatus, AutomationWorker, EventProcessor,
                     RuleRunner, ActionExecutor, FailureClassifier, OwnerSession, OwnerUnavailableException, AutomationFaults,
                     NoAutomationFaults, AutomationScheduler, RunController, AutomationMoveParticipant, RunViews (redaction; §5.10.10)
automation/schedule/ ScheduleCalculator, ScheduleTicker
common/query/        Condition, ConditionJson     (FilterPredicates is package-private)
```

#### 5.10.2 Conditions (A2): `B/common/query/Condition`

```java
public sealed interface Condition permits Condition.Leaf, Condition.Group {
    enum Junction { AND, OR }
    record Leaf(FilterSpec spec) implements Condition {}
    record Group(Junction op, List<Condition> children) implements Condition {}
    void validate(TableSchema schema, String path, Map<String,String> errors);   // requireFilterable per leaf; limits
    PredicateFactory toPredicate(TableSchema schema);                            // leaves → FilterPredicates.build; groups → cb.and / cb.or
}
```

- **Wire format:** `{"op":"AND","children":[{"filter":"status:in:UNPAID,PARTIALLY_PAID"},{"op":"OR","children":[{"filter":"overdue:eq:true"},{"filter":"balance:gt:100000"}]}]}`. Null means every record.
- **Limits:** depth ≤ 4, leaves ≤ 30, children per group 1..20. An empty group is rejected.
- **Validation at save:**
  1. Parse.
  2. Check limits.
  3. `requireFilterable` against the **full staff** schema `TableSchemas.X`.
  4. Force value coercion with a dry run: `queryExecutor.count(entityClass, schema, new TableQuery(0, 1, "id", true, List.of()), List.of(cond.toPredicate(schema), (r, q, cb) -> cb.disjunction()))`.
  5. Map errors to `InvalidFieldsException` with paths such as `condition.children[1].children[0].filter`.
  6. For `ENTITY_UPDATED` and `ENTITY_CREATED_OR_UPDATED` rules, add a **warning** (not an error) for each leaf on a column that is not an update source (§5.8.3: `overdue`, relative date presets, `salesPocName`, `collectionPocName`).
- **Filterable fields** (A2, "filters over that entity's fields"; critic finding PC-11). BE-16 adds the entity fields that placeholders already expose but the list schemas lacked, which extends the lists too:
  - `paymentTerm` (ENUM of `PaymentTerm`) on INVOICES and CUSTOMERS, path `root.get("paymentTerm")`;
  - `overdueAmount` (MONEY) on CUSTOMERS, a `Temporal.subquery` summing `total − paidAmount` over the customer's overdue invoices (the `customerOutstanding` pattern with `invoiceOverdue(…, Today.date())`), so it is as-of correct. It is an OUTSTANDING-published column (§5.8.3) but also clock-driven, so the validator warns on it in UPDATED rules.
  - `ConditionTest` and the `AsOfParityTest` matrix (generated from the schemas) cover them.

#### 5.10.3 Evaluator: `RuleEvaluator`

It always runs under the current principal: the **owner** in the consumer, the **caller** in preview (or the owner with `evaluateAs=OWNER`, §5.10.9).

```java
@Transactional(readOnly = true) boolean matches(RuleDefinition r, Long id);
    // count(entityClass, schema, new TableQuery(0,1,"id",true,[FilterSpec.parse("id:eq:"+id)]), scope + [cond]) > 0
@Transactional(readOnly = true) List<Long> matchingPage(RuleDefinition r, long afterId, int pageSize, Restriction restriction);
    // ids(..., new TableQuery(0,pageSize,"id",true,[FilterSpec.parse("id:gt:"+afterId)]), scope + [cond] + restriction.predicates(), pageSize)
    // Restriction: NONE | ids(List<Long>) (MANUAL recordIds) | customer(Long) (CHILDREN: customer.id / customerId = X)
@Transactional(readOnly = true) long count(RuleDefinition r);
@Transactional(readOnly = true) List<Long> sample(RuleDefinition r, int n);    // id desc, limit n
```

- `scope` is `scopeResolver.forCustomers() / forInvoices() / forPayments().predicates()` for the current principal and ViewScope (regions and book). In the consumer the ViewScope's `regionIds` is the rule's region restriction (intersected with a MANUAL event's `region_ids`), so a restricted rule never matches a record outside its regions (§5.10.7).
- Under a bound point the executor swaps to version roots (§5.7.13). That is how both the consumer (at the event point) and preview (at the requested date) evaluate as of a moment (D10).
- A `BadRequestException` while building predicates is rethrown as `RuleDefinitionException`.
- **The unpaged `run` is never used** (R18). Sets are paged by keyset on `id`.

#### 5.10.4 Placeholders (A4)

- **Grammar:** `\{\{\s*([A-Za-z]+(?:\.[A-Za-z]+)?)\s*(?:\|([^{}|]{0,100}))?\s*\}\}`. Rendering is a single pass, so values are never re-scanned. Everything is plain text.
- **Allowed in:** email subject and body (automation **and manual** emails), task title and description, promise notes, dispute reason.
- Unknown keys, or keys not offered at the entity's level, are rejected at save with 400 on `actions[i].<field>`.
- **Manual emails** (critic finding PC-10; the predecessor PRD's "emails that write themselves", and A4 "in subject and body" without limiting it to automation). From BE-18, `EmailService.send`, `preview`, `bulk` (per row, so each invoice gets its own `{{Invoice.Number}}`) and `retry` (no re-render: the stored text is sent) render placeholders for record-bound types (CUSTOMER, INVOICE, PAYMENT; PROMISE and DISPUTE offer `Customer.*` only) as the **caller**, then re-check the subject and body limits (`Content.check`). Text with no `{{` is untouched, so existing emails are byte-identical. An unknown key gives 400 `fieldErrors.subject` / `fieldErrors.body` "Unknown placeholder {{X}}". `GET /api/automation/placeholders` is also readable with `EMAIL_SEND`. The compose dialog gains a `PlaceholderButton` (FE-07b).

**Catalogue** (`GET /api/automation/placeholders?entityType=`):

| Level (where offered) | Keys → source |
|---|---|
| `Customer.*` (all three entity types; also the only record level offered for manual emails on a PROMISE or DISPUTE) | `Name`, `Email`, `Phone`, `Address`; `PaymentTerm` (label, default-term fallback); `CreditBalance`; `Outstanding` and `OverdueAmount` (repository sums, only computed when used); `CollectionPoc`, `CollectionPocEmail`, `SuccessPoc`, `SuccessPocEmail` (`target.sender(RoleRef.customer(...))`); `Region` (region name) |
| `Invoice.*` (INVOICE) | `Number`, `Date`, `DueDate`, `PaymentTerm`, `Total`, `Paid`, `Balance`, `Status`, `Overdue` (Yes/No), `DaysOverdue`, `Notes`, `SalesPoc`, `SalesPocEmail` |
| `Payment.*` (PAYMENT) | `Number` ("#id"), `Date` (`paidAt`), `Amount`, `Unallocated` (`creditApplied`), `Method`, `Notes`, `Status`, `Invoices` (fitted to 500), `CollectionPoc`, `CollectionPocEmail` |
| Common | `Today` (rule zone), `Rule.Name` |

**Formatting:**
- `Money.format`, ISO dates, `EmailText.humanize`/`nameOf`/`fit`/`oneLine`. `EmailText` becomes public.
- POC keys render only when `scopeResolver.canSeePoc()` holds for the owner or preview caller; otherwise they render "" with a warning.
- Null or blank values render the fallback, or "" with warning "`<key>` is empty".
- Over-length output is fitted with "…" and a warning.
- **Values are always taken live at action time** (D10).

#### 5.10.5 Actions (A3)

Every action has `key` (a UUID, client-generated; the server assigns one if missing), `type`, and `repeat`: `ONCE_PER_RECORD` (the default) or `EVERY_TIME` (once per event: every CREATED, UPDATED or CHILDREN event, every slot and every manual run; events are never coalesced, D10). The JSON shapes are in §6.14.

Each handler has three methods: `preview(a, rc)` (read-only; preview), `prepare(a, rc)` (read-only; renders text, resolves people and senders, and runs `VisibilityProbe` on USER tokens, returning `Prepared` with warnings), and `perform(a, rc, prepared)` (the write, run under the customer lock; it re-checks its guard first).

| Handler | Privilege checked in `rc.regionId` | Behaviour |
|---|---|---|
| **CreateTask** | TASK_MANAGE | If `onlyIfNoOpenTask` and an open task from this rule exists for the record → `Skipped`. Otherwise `taskService.createForAutomation(TaskDraft(T, id, render(title,200), render(description,2000), due, probedAssignees, ruleId, runId, probed = true))`; rendering and probing happen in `prepare`, before the lock. `due` = base (TODAY in the rule zone, or INVOICE_DUE_DATE on INVOICE rules) + offset (−30..365), clamped to ≥ today; null means no due date. Returns `Done(TASK, taskId, warnings)`. |
| **CreatePromise** | PROMISE_MANAGE | **Amount:** FIXED, or FIELD (`Customer.Outstanding`, `Customer.OverdueAmount`, `Invoice.Balance`, `Invoice.Total`, `Payment.Amount`), `Money.scale`; ≤ 0 → `Skipped("Nothing owed")`. **Date:** base + 0..365 days, ≥ today. **Collection POC:** null → customer default; USER → `requireAssignable(u, COLLECTION, region)`; `COLLECTION_POC@CUSTOMER` → `target.sender(...)`; `COLLECTION_POC@RECORD` only on PAYMENT rules. **Guard** `onlyIfNoOpenPromise` (linked to the invoice on INVOICE rules, otherwise on the customer). **Call:** `promiseService.createOrHold(new CreatePromiseRequest(customerId, amount, date, pocId, render(notes,1000), linkRecord && T==INVOICE ? [id] : [], probedAssignees))` → `Applied(dto)` gives `Done(PROMISE, id)` and `assigneeService.notifyAssigned(...)`; `Held(pc)` gives `Done(PENDING_CHANGE, pc.id)` (D11). "No Collection POC" → PERMANENT. |
| **CreateDispute** | DISPUTE_MANAGE | `disputeService.openInternal(new InternalDisputeRequest(T, id, render(reason,2000), proposal, ruleId, probedAssignees))`, then `assigneeService.notifyAssigned(...)`. Proposals: `"none"` (any target) → `{"action":"none"}`; `"cancel"` (INVOICE) → `{"action":"cancel"}`; `"void"` (PAYMENT) → `{"action":"void"}`. `AlreadyOpenException` → SKIP. |
| **SendEmail** | EMAIL_SEND | Render the subject (`oneLine`, fitted to 500) and the body (fitted to 20000), with warnings. Then `emailService.queue(T.emailType, id, from, fallbackToOwner, to, subject, body, "rule-"+ruleId+"-run-"+runId, props.maxEmailRecipients)` → `Done(EMAIL, emailId, emailId, warnings)`. |

**`EmailService.queue`** is new (BE-18), `@Transactional(MANDATORY)`, and **never dispatches**. It lives in `EmailService` because `save` and `Content` are private.
1. `target = targets.loadForSend(type, id)`, scoped, as the owner.
2. `Content.check(subject, body, to)`.
3. If `from` is a ROLE with no sender: when `fallbackToOwner`, use `from = null` (the owner); otherwise throw `NotQueued("Nobody holds <label> on <record>, so it cannot be the sender", false)`.
4. `resolution = addressing.resolve(addressing.plan(target, from, to), target)`. A problem throws `NotQueued(problem, recipients.isEmpty())`.
5. **Automation visibility** (critic findings PC-2, SC-3): each USER recipient must pass `visibilityProbe.canSee(userId, type, id, target.regionId())`; a failing one is dropped with the warning "X cannot see this record, so was not emailed" (and, if none are left, `NotQueued(…, true)`). A USER sender (including a person who resolved a ROLE sender) that fails the probe gives `NotQueued("X cannot see this record, so cannot be the sender", false)`, or falls back to the owner when `fallbackToOwner`.
6. More than `maxRecipients` TO entries throws `NotQueued`.
7. `save(...)`: `sentByUserId` = owner; `fromUserId` is always an internal user (V5). The stored recipients are exactly those left after step 5.
8. Return `Queued(id, warnings)`.

**`EmailService.previewRendered`** (BE-16; used only by `SendEmailHandler.preview`; critic finding PC-10):
```java
@Transactional(readOnly = true)
public RenderedPreview previewRendered(EmailEntityType type, Long id, EmailToken from, boolean fallbackToOwner,
                                       List<EmailToken> to, String renderedSubject, String renderedBody);
record RenderedPreview(EmailDtos.PreviewDto email, List<String> problems, List<String> warnings) {}
```
- Input text is **already rendered** by the handler; it never renders placeholders itself.
- It runs steps 1–6 of `queue` as the current principal (the preview caller, or the owner with `evaluateAs=OWNER`) and **never throws** for a `NotQueued` condition: the message goes into `problems`, dropped recipients into `warnings`.
- It resolves `from` (including the owner fallback) and `to`, and adds the Gmail-not-connected and delivery-not-configured warnings. It writes nothing.
- `queue` and `previewRendered` share one private `resolveForAutomation(...)` so preview and send cannot disagree.

The worker calls `emailDispatcher.dispatchAll(ids)` **after commit, outside any transaction** (R15). Gmail not connected, or transport `none`, gives DONE with a warning; the email row ends NOT_SENT and can be retried from the email UI.

#### 5.10.6 Durable execution (A5, P2)

**`AutomationScheduler`**: two jobs, each wrapped in `try { … } catch (RuntimeException e) { log.warn }`:
- `@Scheduled(fixedDelayString="${app.automation.poll-interval-ms:2000}", initialDelayString=same) poll()` calls `worker.drain(appClock.instant())` when `consumer-enabled`.
- `@Scheduled(fixedDelayString="${app.automation.tick-interval-ms:30000}", initialDelayString=same) tick()` calls `ticker.tick(now)`.

A fixed-delay method never overlaps itself. `@PreDestroy` sets a `stopping` flag, which is checked between events and pages. The node id is `app.automation.node-id`, or `hostname-<8 hex>`, fitted to 80.

**`AutomationWorker.drain(now)`**: not `@Transactional`. Each step is its own short `TransactionTemplate`. The repository methods are `@Modifying(clearAutomatically = true, flushAutomatically = true)` JPQL.
1. If `TransactionSynchronizationManager.isActualTransactionActive()`, throw `IllegalStateException`.
2. **Recover expired leases:** `update … set status='PENDING', claimedBy=null, leaseUntil=null, updatedAt=:now where id=:id and status='PROCESSING' and leaseUntil < :now`. This counts no attempt.
3. **Due ids:** `status='PENDING' and (nextAttemptAt is null or nextAttemptAt <= :now) order by id`, page `batch-size`.
4. **Claim:** `update … set status='PROCESSING', claims=claims+1, claimedBy=:node, leaseUntil=:now+lease, updatedAt=:now where id=:id and status='PENDING' and (nextAttemptAt is null or nextAttemptAt <= :now)`.
   - 0 rows means another node has it: skip.
   - Otherwise read `claims`; that is the **token**. `token > max-claims (20)` → DEAD "Processing never finished after 20 claims".
   - **There is no coalescing** (critic findings PC-3, CF-21, SC-8). Every event keeps its own provenance (`origin`, `caused_by_rule_id`, `depth`, `occurred_at`) and is evaluated at its own point (D10), so the self-trigger guard, the depth cut-off and `active_since` are exact per event, and an `EVERY_TIME` action fires once per event.
5. **Process:** `processor.process(id, token, now)` returns `DONE | RETRY(error) | YIELD(cursorId) | LOST`. On an exception:
   - `DatabaseErrors.isConcurrency(e)` → RETRY.
   - Else `DatabaseErrors.isUnavailable(e)` → a best-effort fenced release (`status=PENDING, nextAttemptAt=now+30s where id and claims=:token and status='PROCESSING'`) and **stop the drain**.
   - Else RETRY.
   - An `Error` propagates; lease expiry recovers the event.
6. **Finish**, fenced `where id=:id and status='PROCESSING' and claims=:token`:
   - `DONE`: `status=DONE, processedAt=now, leaseUntil=null`.
   - `RETRY`:
     - With `attempts+1 ≥ max-attempts (5)`: DEAD. In the same transaction, set the event's non-terminal runs to FAILED "Gave up after 5 attempts: …", update the rule's `last_run_*`, and send one `AUTOMATION_RUN_FAILED` notification to the owner.
     - Otherwise `status=PENDING`, `attempts+1`, `nextAttemptAt = now + [30s, 2m, 10m, 1h][attempts]`, `lastError` fitted.
   - `YIELD(c)`: `status=PENDING, cursorId=c, nextAttemptAt=now`.
   - `LOST`: nothing.
   - A fenced update that changes 0 rows logs WARN "lost the lease".

**`EventPoints.pointOf(ev)`** (D10; critic finding PC-1):
- CREATED, UPDATED, CHILDREN: `select h.instant from HistoryTransactionRow h where h.txId = :historyTxId` → `AsOfPoint.at(instant)`. No row → `null` (evaluate live), a WARN, and the run message "Evaluated on current data: the moment of the change was not recorded".
- SCHEDULED, MANUAL: `AsOfPoint.at(ev.scheduledFor)`.
- The point is always at or before processing time. A transaction whose history instant precedes a SCHEDULED point but which commits after the consumer has read is not seen; the window is the time between the writer binding its instant and COMMIT (milliseconds) and is accepted.

**`EventProcessor.process`:**
- **Entity events (CREATED, UPDATED).** `point = eventPoints.pointOf(ev)`. Candidates come from `findEntityCandidates(entityType, kinds, occurredAt)`: enabled, not suspended, not deleted, matching trigger kinds, `activeSince <= occurredAt`. `MANUAL` rules never match.
  - CREATED uses `[ENTITY_CREATED, ENTITY_CREATED_OR_UPDATED]`; UPDATED uses `[ENTITY_UPDATED, ENTITY_CREATED_OR_UPDATED]`. Because CREATED absorbs UPDATED within a transaction (§5.8.1), an `ENTITY_UPDATED` rule does not fire on a record created with credit applied in the same transaction.
  - Drop the rule whose id equals the event's `caused_by_rule_id`. If `depth > 3`, skip every rule.
  - Run `ruleRunner.runForRecord(rule, ev, point)` per rule, isolated. The event's outcome is the worst of the rule outcomes.
- **CHILDREN without `rule_id`** (the event the user's save wrote): candidates are the `ENTITY_UPDATED` and `ENTITY_CREATED_OR_UPDATED` rules on INVOICE and PAYMENT, with the same filters. In one transaction the processor inserts one **per-rule CHILDREN event** for each candidate (same `entity_id`, `history_tx_id`, `origin`, `caused_by_rule_id`, `depth` and `occurred_at`, plus `rule_id`) and returns DONE. The fan-out costs the consumer K rows (K = candidate rules), never the user's save.
- **Per-rule CHILDREN, SCHEDULED, MANUAL and REPLAY events:** `ruleRunner.runForSet(rule, ev, token, now)`.

**`RuleRunner.runForRecord(rule, ev, point)`:**
1. If the run for `(rule, ev)` exists and is terminal → DONE.
2. Circuit breaker: `effects.countByRuleIdAndStatusAndCreatedAtAfter(rule, DONE, now-1h)` plus this drain's count ≥ `max-actions-per-rule-per-hour` (1000) → suspend `RATE_LIMITED`.
3. `ownerSession.call(rule, ev, …)`:
   - `matched = AsOf.with(point, () -> evaluator.matches(rule, ev.entityId))`: the condition **at the event point** (live when `point` is null).
   - No match → mark a pre-existing non-terminal run SKIPPED "Did not match" → DONE.
   - Otherwise `run = runs.ensure(rule, ev)`. `ensure` inserts the RUNNING run in its **own `REQUIRES_NEW` transaction**; a unique-key violation is caught **outside** that transaction and answered by re-reading (§5.9.1 general rule).
   - `result = actionExecutor.applyAll(rule, run, ev, type, id)`. Actions are **live** (D10).
   - **After it returns, outside any transaction:** `emailDispatcher.dispatchAll(result.emailIds())`.
   - In a transaction, finalise the run's counters and status (SUCCEEDED, PARTIAL, or FAILED when every action failed permanently; RUNNING if a transient failure remains) and the rule's `last_run_*` via JPQL.
   - Return RETRY if anything transient remains, otherwise DONE.
4. `OwnerUnavailableException(reason)` → suspend the rule (§5.10.7), mark the run SKIPPED → DONE. `RuleDefinitionException` → suspend `INVALID_DEFINITION`, run FAILED → DONE.

**`RuleRunner.runForSet(rule, ev, token, now)`** (SCHEDULED and MANUAL runs were pre-created QUEUED; per-rule CHILDREN and REPLAY runs are created here):
1. Terminal → DONE. Rule deleted or suspended → run SKIPPED "Rule is not active" → DONE. A **disabled** rule is skipped the same way for SCHEDULED, CHILDREN and REPLAY events, but **not for MANUAL**: a disabled rule never fires by itself yet can be run by hand (critic finding PC-6).
2. `ev.cursorId != null && rule.version != run.ruleVersion` → SUPERSEDED. A run that has not started records the current version.
3. RUNNING. `after = ev.cursorId ?? 0`. `point = eventPoints.pointOf(ev)` (CHILDREN: the parent change's instant; SCHEDULED and MANUAL: `scheduled_for`).
4. **REPLAY** (§5.10.7 resume): instead of pages of records, iterate by keyset the DONE entity events (CREATED, UPDATED, and parent CHILDREN events) with `occurred_at` in `[ev.scheduledFor, ev.occurredAt)` whose entity type matches the rule (CHILDREN for INVOICE and PAYMENT rules), `cursor_id` = the last replayed event id; for each event that passes the same candidate filters as live processing (trigger kind, not caused by this rule, depth ≤ 3), `runForRecord(rule, replayed, pointOf(replayed))` (a CHILDREN one runs the per-customer set below). Runs are keyed `(rule, replayed event)`, so a replay never repeats a firing that already happened.
5. Otherwise loop over pages, **each page in its own `ownerSession.call`**, so the owner is re-checked per page:
   - `page = AsOf.with(point, () -> evaluator.matchingPage(rule, after, page-size, restriction))`, where `restriction` is `id IN ev.recordIds` (MANUAL) or `customerId = ev.entityId` (CHILDREN); empty means complete.
   - Per id: once `run.matched ≥ maxRecordsPerRun`, set `truncated` and stop; otherwise increment `matched` and `applyAll`.
   - Dispatch the page's emails outside any transaction.
   - Persist the counters and heartbeat `leaseUntil` fenced by token (0 rows → LOST).
   - Re-read the rule: deleted or suspended (or disabled, except MANUAL) → PARTIAL/SKIPPED "Stopped: rule is not active"; version changed → SUPERSEDED.
   - `after` = the last id. `clock − sliceStart > slice-budget` → save `cursor_id` and YIELD.
6. Finalise as in `runForRecord`.

**`ActionExecutor.applyAll(rule, run, ev, type, id)`**: one `REQUIRES_NEW` transaction per (target, action). **Read-only work happens before the customer lock; the lock is taken immediately before the write** (critic finding PC-4), so a user saving an invoice, payment, promise, document or seat on the same customer waits only for the write itself:
```
for each action a:
  if effects.existsByRunIdAndActionKeyAndTargetTypeAndTargetId(run.id, a.key, type, id): continue
  key = dedupeKey(rule, a, type, id, ev)
  try {
    r = actionTx.execute(s -> {
        rc = recordContexts.load(type, id, rule, run, ev);   // read-only: scoped target (as owner), entity, customerId, regionId, placeholder values
                                                              //   NotFound → ActionSkippedException("The record no longer exists")
        if (effects.existsByDedupeKey(key)) return AlreadyDone;           // cheap early exit
        prep = handler.prepare(a, rc);                                    // read-only: render, resolve people and sender, VisibilityProbe
        if (handler.locksCustomer()) {                                    // task, promise, dispute (PPD-01)
            customerRepository.findByIdForUpdate(rc.customerId());
            faults.afterLock(rule.id, a.key, type, id);                   // test hook: marks the start of the lock window
        }
        if (effects.existsByDedupeKey(key)) return AlreadyDone;           // re-checked under the lock
        faults.beforeAction(rule.id, a.key, type, id);                    // test hook, no-op in production
        res = handler.perform(a, rc, prep);                               // re-checks its onlyIf* guard, then the write + audit + notifications
        effects.save(AutomationEffect.of(run, ev, a, type, id, rc.target().label(), res, res instanceof Done ? key : null));
        return res; });
    faults.afterActionCommitted(rule.id, a.key, type, id);
    tally(r)
  } catch (Exception e) {
    switch (classifier.classify(e)) {
      DUPLICATE  → skipped++ ; SKIP → newTx save SKIPPED(key=null) ; PERMANENT → newTx save FAILED(key=null)
      DEFINITION → throw RuleDefinitionException ; TRANSIENT → transient++ (no row; retried with the event) ; INFRA → rethrow (stop drain)
    }
  }
```

- **Inside the lock window** there are only: the dedupe re-check, the guard query, the domain write (with its own audit rows and at most 20 notifications) and the effect row. Rendering, holder resolution, `principalFor` for probes and the `Customer.Outstanding` sums all happen before. `TaskActionTest` asserts at most 30 statements between `faults.afterLock` and commit for a CREATE_TASK with two assignees (`CountingStatements`).
- **The effect row commits in the same transaction as the Task, Promise, Dispute, Email or pending change it records**, so one dedupe key can never act twice (P2).

**`FailureClassifier`** (checked in this order):

| Class | Condition |
|---|---|
| DUPLICATE | `DataIntegrityViolationException` whose cause chain mentions `UK_AUTOMATION_EFFECT_DEDUPE` (case-insensitive) |
| SKIP | `ActionSkippedException`, `DisputeService.AlreadyOpenException`, `NotFoundException`, `EmailService.NotQueued` with `noRecipients` |
| TRANSIENT | `DatabaseErrors.isConcurrency(e)` (optimistic, pessimistic, `CannotAcquireLock`, H2 lock timeout), `QueryTimeoutException` |
| INFRA | `DatabaseErrors.isUnavailable(e)`, only after concurrency has been ruled out |
| DEFINITION | `RuleDefinitionException`, `PlaceholderRenderer.UnknownPlaceholderException` |
| PERMANENT | `BadRequestException` and its subclasses (`CodedBadRequestException`, `ApprovalConflictException`, `AlreadyOpenException` is caught earlier as SKIP); **listed explicitly because they do not extend `BadRequestException`** (critic finding CF-9): `GlobalExceptionHandler.InvalidFieldsException` (`GlobalExceptionHandler.java:79`), `BulkExecutor.IneligibleException` (`BulkExecutor.java:27`), `EmailService.NotQueued` without `noRecipients`, `ChangeConflictException`, `PayloadException`; also `AccessDeniedException`, `AuthenticationException` (logged at ERROR: a missing principal is a bug, C-8), any other `DataIntegrityViolationException`, `IllegalArgumentException`, `RunAs.InactiveUserException` |
| TRANSIENT (default) | any other `RuntimeException` |

`FailureClassifierTest` (plain, BE-17) asserts one case per row, including `InvalidFieldsException` → PERMANENT.

#### 5.10.7 Owner session, suspension and ownership (P3)

**`OwnerSession.call(rule, ev, work)`:**
1. `principal = appUserDetailsService.principalFor(rule.ownerUserId)`, read-only, with grants loaded eagerly.
2. Missing or inactive → `OwnerUnavailableException(OWNER_INACTIVE)`. A customer login → `OWNER_NOT_STAFF`.
3. `RequiredPrivileges.of(rule)`: **`AUTOMATION_MANAGE`** (critic finding SC-14), the entity's VIEW privilege, plus TASK_MANAGE, PROMISE_MANAGE, DISPUTE_MANAGE or EMAIL_SEND per action. These are checked on `principal.getAuthorities()` (the union). Any missing → `OWNER_LACKS_RIGHTS`, naming it.
4. `scope = new ViewScope(regions, null)` where `regions` is the rule's region restriction (null when unrestricted), intersected with a MANUAL event's `region_ids`. Then `runAs.call(principal, scope, WriteOrigin.automation(ruleId, runId, eventId, ev.depth + 1), work)`; the actor is the owner (D4). Previous contexts are restored in `finally`.

**Per-record defence in depth.** Each handler calls `currentUser.hasIn(<action privilege>, rc.regionId())`. A failure is a PERMANENT effect failure **for that record only**; the rule is not suspended. An owner may view a region without managing it.

**Rule regions (B1; critic finding PC-8).**
- `regionIds` on the rule is optional. **Empty means "every region where the owner holds `RequiredPrivileges`"**; this is the rule's *effective regions* E.
- At save each listed region must be one where the owner holds every `RequiredPrivileges` privilege (400 `fieldErrors.regionIds`).
- Evaluation is narrowed through the ViewScope (step 4), so a restricted rule never matches outside its regions, and an unrestricted one never outside the owner's grants.
- Who may **see** a rule is `RuleVisibility` (§5.10.10).

**Suspension.** `ruleRepository.suspend(id, reason, detail, now)` is a JPQL update `where id=:id and suspendedReason is null`. On 1 row, in the same transaction:
- clear `nextRunAt`;
- write a revision row;
- audit `RULE_SUSPENDED` (entity `RULE`, user null);
- notify `AUTOMATION_RULE_SUSPENDED` to the owner if active and to every active staff user holding AUTOMATION_ADMIN (new `UserRepository.findActiveStaffHolding(privilege)`).

**Resume** (`POST /rules/{id}/resume`, body `{"replay": false, "confirmOwnershipChange": false}`; critic finding PC-18):
- It re-validates, runs the owner guard, clears the suspension, recomputes `next_run_at`, and audits `RULE_RESUMED` (with `replay`).
- The response carries `skippedWhileSuspended`: the number of entity events of the rule's entity type (and CHILDREN for INVOICE and PAYMENT rules) with `occurred_at` in `[suspended_at, now)`.
- `replay = false` (default): `active_since = now`; the backlog is not acted on.
- `replay = true`: `active_since` stays at `suspended_at`, and one REPLAY event (`rule_id`, `scheduled_for = suspended_at`, `occurred_at = now`) is inserted; the consumer replays the window at each event's own point (§5.10.6). Events already purged (older than `event-retention-days`) cannot be replayed; the run message says from which date.

**Proactive hooks.**
- `ruleService.suspendOwnedBy(userId, OWNER_INACTIVE)` is called from `UserController.update` when `active=false`, from bulk DEACTIVATE, and from delete-falls-back-to-deactivate.
- `ruleService.recheckOwner(userId)` (runs the owner guard and suspends with `OWNER_LACKS_RIGHTS` when a required privilege is gone) is called from `UserController.update` when the role changes and from `RegionGrantService.replace`; `ruleService.recheckOwnersOfRole(roleId)` from `RoleController` privilege edits. The lazy guard in `OwnerSession` catches anything else on the next event.

`UserController.pocReferenceCount` also gains `ruleRepository.countByOwnerUserIdAndDeletedAtIsNull(id)`.

**Ownership** (critic finding SC-2: a non-owner must not make someone else's rule act and then approve what it produced).
- Creating a rule makes the caller its owner.
- A `PUT` by the owner (holding AUTOMATION_MANAGE) keeps the owner.
- A `PUT` by anyone else needs AUTOMATION_ADMIN **and** `"confirmOwnershipChange": true`. Otherwise it returns 400 `fieldErrors.confirmOwnershipChange`. On save the owner becomes the caller, audited as `RULE_OWNERSHIP_TAKEN`.
- **Anything that makes a rule act** (enable, resume, run now, and retry of a MANUAL run) is done by the owner. A non-owner holding AUTOMATION_ADMIN must send `"confirmOwnershipChange": true`, becomes the owner first (same audit; `RequiredPrivileges` and the rule's regions are checked against the caller), and only then acts; otherwise 400 `fieldErrors.confirmOwnershipChange`. A non-owner without AUTOMATION_ADMIN gets 403.
  - Consequence: whoever makes a rule act is its owner, and so the actor, maker and dispute opener of what it does. Check 5 of §5.11.6 and the own-rule dispute rule (§5.10.11) then stop that person approving it, and the run uses that person's regions and book.
- **Disable and delete** (they only stop a rule) are allowed to the owner or AUTOMATION_ADMIN without taking ownership. So are **requeue** of a DEAD event and **retry** of a failed entity or scheduled run: they redeliver an event that genuinely happened, and the owner's own condition chooses the records.
- **The saver must hold `RequiredPrivileges.of(rule)`** (400 `fieldErrors.actions`), so saving never produces a rule that immediately suspends.

#### 5.10.8 Schedules

**`ScheduleCalculator`** (pure):
- `nextAfter(kind, time, day, zone, after)` and `latestAtOrBefore(kind, time, day, zone, t)` iterate by date in the zone.
- `missedSlots(kind, time, day, zone, fromExclusive, toInclusive, max)` lists slots oldest first.
- A DST gap moves forward. A DST overlap takes the earlier offset.

**`ScheduleTicker.tick(now)`:**
```
due = ruleRepository.findDueSchedules(now, page 100)   // enabled, not suspended, not deleted, SCHEDULE_*, nextRunAt <= now → (id, nextRunAt, version, catchUp)
for each: tx {
   slots = catchUp == ALL ? missedSlots(spec, lastScheduledFor ?? nextRunAt − ε, now, max-catch-up-slots) : [latestAtOrBefore(spec, now)]
   next = nextAfter(spec, now)
   n = update AutomationRule r set r.nextRunAt=:next, r.lastScheduledFor=:lastSlot where r.id=:id and r.nextRunAt=:expected  // no version bump
   if n == 1: for each slot: insert SCHEDULED event (rule_id, rule_version, scheduled_for=slot, occurred_at=now) + run (QUEUED, SCHEDULED, scheduled_for=slot)
}
purge: delete events with status DONE and processedAt < now - event-retention-days, and history_transactions with instant < now - event-retention-days,
       in chunks of 1000 ids (DEAD rows are kept; a requeued DEAD event whose instant was purged is evaluated live, §5.10.6)
```

- Each SCHEDULED event is evaluated **as of its `scheduled_for`** (D10), not at drain time, so an outage does not shift what a slot matched.
- `catch_up = ONE` (default): missed slots collapse into **one** catch-up run at the latest slot. `ALL`: one run per missed slot, at most 31; older slots are named in the first run's message. Q23 puts the default to the PRD owner.
- A weekly rule ticks only on its configured weekday.
- `next_run_at` is recomputed on create, update, enable and resume, and cleared on disable, suspend and delete.

#### 5.10.9 Run now and preview

**`POST /api/automation/rules/{id}/run`** (optional body `{"recordIds":[…≤50], "runAt":"2026-09-27T03:30:00Z", "confirmOwnershipChange":false}`; critic findings PC-6, SC-2, SC-16):
- The caller must be the owner (holding AUTOMATION_MANAGE). A non-owner AUTOMATION_ADMIN must confirm taking ownership (§5.10.7); anyone else gets 403.
- The rule must not be deleted or suspended (400 "The rule is suspended; resume it first"). It **may be disabled**, and a rule with trigger `MANUAL` exists only to be run this way.
- `requireLive`.
- `recordIds`: at most 50 positive longs (400 `fieldErrors.recordIds`), stored exactly in the TEXT column, never fitted.
- `runAt`: optional; after now and at most 90 days ahead (400 `fieldErrors.runAt`). The event is inserted with `next_attempt_at = runAt`, which the claim query already honours.
- `regionIds`: the request's `ViewScope.regionIds` is stored in `region_ids` and ANDed into the run's evaluation, so the run acts on exactly the regions the preview showed.
- In one transaction, **first `ruleRepository.findByIdForUpdate(id)`**, which serialises run-now per rule without any constraint on the outbox. Then:
  - **Idempotent:** if a MANUAL event for the rule with the **same** `record_ids`, `region_ids` and `scheduled_for` (for a `runAt` request) is PENDING or PROCESSING, return 200 with its run.
  - Otherwise insert the MANUAL event (actor = caller, `record_ids`, `region_ids`, `scheduled_for = runAt ?? now`, `next_attempt_at = runAt`) and a QUEUED run (`requested_by = caller`, `scheduled_for`), audit `RULE_RUN_REQUESTED`, and return **202**. A request with different `recordIds` or regions queues a second run.
- The run executes **as the owner** (who is the caller), evaluated at `scheduled_for`, over the condition's set or over `recordIds`.

**Preview** (`POST /rules/preview` and `GET /rules/{id}/preview`, both `@AsOfAware`; `GET /rules/{id}/matches`):
- Gate: AUTOMATION_VIEW. For a saved rule, the rule must be visible to the caller (`RuleVisibility`), otherwise 404.
- **Whose view** (`evaluateAs`, default `CALLER`; critic finding PC-7):
  - `CALLER`: as the caller under the request's ViewScope.
  - `OWNER`: allowed to the rule's owner and to AUTOMATION_ADMIN (403 otherwise). It runs `RunAs.call(principalFor(owner), new ViewScope(ruleRegions ∩ request regionIds, request point), WriteOrigin.system(), …)`. `matchCount` counts as the owner would; `sample` and `record` contain only records the **caller** can also see (others appear as `{"hidden":true}` with no id, label or link), so an administrator cannot read records through the owner's rights.
  - `PreviewDto.evaluatedAs` reports which was used.
- **Which definition:** `POST /rules/preview` evaluates the body. `GET /rules/{id}/preview` and `/matches` evaluate the current rule when live, and **the revision in force at T** (`RuleAsOfLists.revisionAt(id, p)`) under a bound point; no revision at T → 404.
- It runs inside a read-only transaction with `setRollbackOnly()`, and validates exactly as a save does.
- It returns `matchCount` and `sample` (≤ 20).
- For one record (`recordId`, or else the first sample) it reports `matches`, and per action `handler.preview(a, rc)`: rendered text, resolved people, problems, warnings. SEND_EMAIL uses `EmailService.previewRendered` (§5.10.5).
- `alreadyDone` per sample is set when a DONE effect exists with that action's ONCE_PER_RECORD key.
- **In historical mode:** evaluation happens at T; `record.actions = null`; `asOfNote = "Actions are shown only for today's view"` (D10).

#### 5.10.10 Rules and runs: who sees them, and as of a date

**`RuleVisibility`** (B1 "every list, count"; "nothing in a third"; critic finding PC-8). A caller holding AUTOMATION_VIEW sees a rule when any of these holds:
- the caller is the owner;
- the caller holds AUTOMATION_ADMIN (a global privilege by design);
- the rule's effective regions E meet the caller's granted regions: for a restricted rule, `exists (select 1 from automation_rule_regions x where x.rule_id = r.id and x.region_id in :mine)`; for an unrestricted one, `exists (select 1 from user_region_grants g where g.user_id = r.owner_user_id and g.region_id in :mine)` (approximated by any grant of the owner).

`regionIds` narrows further, for everyone including administrators: the same two EXISTS clauses with `:mine` replaced by `ViewScope.narrow(granted)` (or the asked set for an administrator). `GET /rules`, `/rules/summary` (including `failedRuns24h`), `/rules/{id}`, the runs, the effects and preview all apply `RuleVisibility`; an invisible rule is 404. The rules list and summary are therefore **SETS** in §8.2, and `RegionParamCoverageTest` covers them.

**Run and effect redaction (`RunViews`; critic findings PC-8, SC-13).** The owner and AUTOMATION_ADMIN see runs and effects in full. For any other viewer of a visible rule:
- `RunDto` counters (`matched`, `done`, `skipped`, `failed`) are recomputed with one grouped query over the run's effects whose target is in the viewer's `scope(kind, VIEW, GRANTED)`, and `partial: true` is set.
- `RunDto.entity` of an entity-triggered run is `{"type","id","label","link"}` when the viewer can see it (`scopeResolver.requireVisible` as the viewer), otherwise `{"type":"INVOICE","hidden":true}`; `message` is then null.
- `EffectDto` for a target the viewer cannot see carries only `actionKey`, `actionType`, `status`, `createdAt` and `target: {"type", "hidden": true}`. `target.id`, `label`, `link`, `result`, `message` and `warnings` are null, because messages and warnings name records and people.
- `GET /health` stays AUTOMATION_ADMIN only; its `lastError` text is shown there.

**As of a date (D9):**
- **`GET /api/automation/rules?asOf=`** is served by `RuleAsOfLists` from `automation_rule_revisions`: the latest revision per rule with `saved_at <= p`, excluding deleted ones, built in memory, with `RuleVisibility` evaluated on the snapshot's `regionIds` and the owner's **current** grants (Q2).
  - Supported: `name:contains`, `enabled:eq`, `entityType:in`, `triggerKind:in`, sort by `name`. Anything else gives 400 "This filter isn't available for past dates".
  - `GET /rules/{id}?asOf=` returns the revision snapshot.
- **`GET /rules/{id}/runs?asOf=`** filters `createdAt <= p` and adds **`statusAt`**: `QUEUED` when `started_at` is null or after p; `RUNNING` when `started_at <= p` and `finished_at` is null or after p; otherwise the stored status. The counters are the run's **current** totals (final once it has finished); this is stated in the UI tooltip. **`/runs/{id}/effects?asOf=`** filters `createdAt <= p`.

#### 5.10.11 Disputes as an action target (`B/dispute/*`; BE-19)

**Enum and customer path**
- `DisputeTargetType { INVOICE, PAYMENT, CUSTOMER }`, with CUSTOMER **last**. `DisputeSchemaUpgrade` widens it.
- `open` (customer login): after the caller check, `if (req.targetType() == CUSTOMER) throw new BadRequestException("A dispute is raised on an invoice or a payment")`.
- `open`'s "An open dispute already exists" guard becomes `existsByCustomerIdAndTargetTypeAndTargetIdAndStatusAndInternalFalse(...)`. A pending internal dispute therefore neither blocks the customer nor reveals itself (critic finding SC-1).

**Switch statements** (only `snapshotTarget` and `describeTarget` fail compilation; the others must be edited by hand, DR-auto S3):
- `ensureTargetBelongsToCustomer`: `case CUSTOMER -> require id == customerId`.
- `snapshotTarget`: `case CUSTOMER -> CustomerService.snapshotOf(c)`. Add `public static` if needed.
- `describeTarget`: `case CUSTOMER -> "Customer " + name`.
- `applyChange`: a blank change on a CUSTOMER target means `none`; action `"none"` → return (acknowledge, valid for any target); any other action on CUSTOMER → 400 "Only 'none' is allowed on a customer dispute". INVOICE and PAYMENT behaviour is unchanged.

**`openInternal(InternalDisputeRequest)`** (`@Transactional(MANDATORY)`):
1. Staff caller.
2. Load the target through the scoped `get` to find `customerId`.
3. `customerRepository.findByIdForUpdate(customerId)`; `requireVisible(CUSTOMER)` (book-aware).
4. `requireIn(DISPUTE_MANAGE, region)`.
5. Guard: one PENDING **internal** dispute per (customer, targetType, targetId), else `AlreadyOpenException extends BadRequestException`.
6. Save with `internal=true`, `origin="AUTOMATION"`, `ruleId`, `openedByUserId = caller`, `region`.
7. `assigneeStore.replace(DISPUTE, id, tokens)` (tokens already probed by the handler).
8. Audit `DISPUTE_OPENED`.
9. Return; the handler calls `assigneeService.notifyAssigned` (`DISPUTE_ASSIGNED` to resolved, visible assignees), falling back to `notifyAdmins(..., region)` when nobody resolves.

**Other dispute changes**
- **Approve:** customer first (§5.6.6). If `d.internal` and the effective action is not `none` and `caller == openedBy` → 400 "A change your own rule proposed must be approved by someone else". The maker-checker checker-step rule (§5.11.8) is evaluated after this. A change held from an internal dispute is stored with `internal_source = true`.
- **Internal disputes are hidden from customer logins** (D17):
  - the `forDisputes` customer branch adds `internal = false`;
  - `get` → 404;
  - the open guard ignores them (above);
  - the audit timeline for a customer never includes them, nor `CHANGE_*` rows from them (§5.13);
  - rows on the target that carry an internal `disputeId` keep the change but null `reason` and `disputeId`.
- `EmailTargets.disputeOwnFields` becomes a `switch` expression: INVOICE → SALES_POC, PAYMENT → COLLECTION_POC, **CUSTOMER → `Map.of()`** (S2).
- `DisputeDto` gains `internal`, `origin`, `ruleId`, `assignees` (detail only), `canResolve`, `regionId`, `regionCode`, `regionName` and `pendingApproval`.
- `setAssignees(disputeId, tokens)` needs DISPUTE_MANAGE in the region; it locks the customer, then the dispute, stores through `AssigneeStore`, audits `DISPUTE_ASSIGNEES_CHANGED` and returns the added tokens. `DisputeController` probes first (`AssigneeService.probe`, 400 `fieldErrors.assignees`) and notifies after (`DISPUTE_ASSIGNED`).

#### 5.10.12 Promise co-assignees (BE-19)

- `PaymentPromiseService.setAssignees(promiseId, tokens)` needs PROMISE_MANAGE in the region (`getForWrite`, so customer then promise). It stores through `AssigneeStore`, audits `PROMISE_ASSIGNEES_CHANGED` and returns the added tokens; `PaymentPromiseController` probes first (400) and notifies `PROMISE_ASSIGNED` after.
- `createOrHold` stores `req.assignees()` through `AssigneeStore` after the save (BE-14 adds the request component and ignores it; BE-19 applies it). When an approval applies a held automation promise, `ApprovalService` notifies its co-assignees through `AssigneeService.notifyAssigned` after the apply.
- `PromiseDto.assignees` is filled on detail only.
- `collectionPoc` and the book scope are unchanged; the broken-promise notice gains the region filter of §5.6.7.
- `record_assignees` owner type PROMISE counts towards `pocReferenceCount`.
- The UI assigns co-assignees on the promise detail after creation; the create form does not offer them (FE-07b). The API accepts them on create for automation and holds.

### 5.11 Maker-checker: `B/approval` (B2)

#### 5.11.1 Gated operations

- "Above" means `amount.compareTo(threshold) > 0`.
- If `amount.signum() <= 0`, the gate returns without a query.
- The old method stays as a wrapper around the new entry method.

| `ApprovalOperation` | New entry method (returns `Outcome<T>`) | Amount measured | Stored payload | Conflict check at apply | Gate placement |
|---|---|---|---|---|---|
| `INVOICE_CREATE` | `InvoiceService.createOrHold(CreateInvoiceRequest)` | Σ `lineTotal` after `buildLines` | The request, with each line's `unitPrice` **pinned**; `invoiceDate` as sent | none (all rules re-run; the amount must not grow) | After the customer lock (F1), `requireIn`, `requireAssignable`, `due`, `buildLines`, `setTotal`. **Before** `invoiceNumbers.next()` and `save`. |
| `INVOICE_CANCEL` | `cancelOrHold(Long id)` | `total` (`paidAmount` must be 0) | `{"invoiceId": id}` | strict: `total`, `paidAmount`, `status` | after the checks (:359–367), before `setStatus` |
| `INVOICE_TERMS` | `updateOrHold(Long id, UpdateInvoiceRequest)`; gate only when the resolved due date or term differs | `balance` if the new due date is later than the current one (or the current is null); otherwise 0 | the request with `version = null` | three-way: `dueDate`, `paymentTerm`; also `notes` and `salesPocUserId` when set | after the version check (:115–118); `Due proposed` is computed **before** any setter |
| `PAYMENT_RECORD` | `PaymentService.recordOrHold(CreatePaymentRequest)` | `req.amount()` | the request | none | after the targets are sorted (:88), before the builder |
| `PROMISE_CREATE` | `PaymentPromiseService.createOrHold(CreatePromiseRequest)` | `req.amount()` | the request, with `collectionPocUserId` resolved and `assignees` included | none | after `resolveInvoices` (:83), before the builder |
| `PROMISE_UPDATE` | `updateOrHold(Long id, UpdatePromiseRequest)` | `max(\|new−old\|, newDate > oldDate ? newAmount : 0)` | the full PUT | three-way on `amount`, `promisedDate`, `notes`; on `collectionPocUserId` and `invoiceIds` when non-null; **re-based** on current values | after `before` (:114), before `setAmount` |
| `PROMISE_OVERRIDE` | `overrideOrHold(Long id, PromiseStatus, String reason)` | `status == BROKEN ? 0 : max(0, amount − fulfilled)` | `{"status","reason"}` | strict: `statusOverridden`, `overrideReason` | after `before` (:187) |
| `DISPUTE_APPROVE` | `DisputeService.approveOrHold(Long id, ResolveDisputeRequest)` | invoice `cancel`: total; `replace_items`: \|Δtotal\|; `void`: amount; `update_amount`: \|Δ\|; `update_notes`, `update_meta`, `none`: 0 | the request, with `appliedChangeJson` = the effective change JSON | strict: invoice `total`/`paidAmount`/`status`, or payment `amount`/`status` | after the change JSON is chosen, parsed and measured; before `snapshotTarget` |

**Legacy wrappers** keep their signatures and annotations: `create`, `update`, `cancel`, `record`, promise `create`/`update`/`override`, dispute `approve`. Each body is `return xOrHold(...).appliedOrThrow();`.
- `appliedOrThrow()` on `Held` throws `IllegalStateException`. The wrapper runs in the same transaction, so the pending row rolls back too, which fails closed.
- `reassignSalesPoc` and `reassignCollectionPoc` keep calling the wrappers; they always measure 0.

**Refactors:**
1. **`InvoiceService.createOrHold`:**
   - Remove `.invoiceNumber(...)` from the builder.
   - After the gate: `invoice.setInvoiceNumber(invoiceNumbers.next())`, then `save`.
   - The customer is already locked at the start (F1), so the lock order is customer → sequence → customer (re-entrant in `CreditLedger`).
   - The default `invoiceDate` is `appClock.instant()`.
2. **`updateOrHold`:** compute `Due proposed` and `dueChanges` before any setter. `due()` still throws `InvalidFieldsException` first.
3. **`DisputeService`:** split `applyChange` (:219–288) into:
   - `ParsedChange parse(String json)` (all existing validation and messages, plus the CUSTOMER/`none` rules of §5.10.11);
   - `BigDecimal measure(Dispute, ParsedChange)`;
   - `void apply(Dispute, ParsedChange)`.
   - Add `public BigDecimal InvoiceService.totalFor(List<LineInput>)`, which is pure.
4. **`PaymentService.recordOrHold`:** set `paidAt` explicitly to `ApprovalContext.current().map(Applying::requestedAt).orElse(appClock.instant())`, i.e. the receipt time is the request time (§10 Q4).

**Not gated** (there is no monetary measure):
- customer create, update and delete; passwords are never gated or stored;
- POC seats and reassignments; invoice PATCH of notes or Sales POC only; payment PATCH;
- promise `cancel` and `clearOverride`;
- dispute `open` and `deny`;
- tasks, rules, emails, documents, products (product price changes: Q20);
- region moves themselves (audited). A move cannot be used to escape the gate: it is refused while changes are pending unless the mover holds `CHANGE_APPROVE` in the source region, and the threshold cool-down keeps the stricter region's limits (D7, §5.11.3; critic finding SC-4).

**Gated differently:** **loosening a region's thresholds** (raising an amount, shortening its cumulative window, or deleting a row) is held as a `THRESHOLD_CHANGE` pending change for another `CHANGE_APPROVE` holder in that region; tightening applies at once (§5.11.11; critic finding SC-11).

**Exempt system paths.** They never reach a gate:
- `sweepOverdue`, `recomputeAll`, `reevaluateForCustomer`, `evaluate`, `applyShares`
- `CreditLedger.*`, `PaymentService.applyTo`/`reverseAllocations`
- the dispute internals (`cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount`, direct saves)
- upgrades, the seeder, fixtures

In apply mode **every** gate on the thread only verifies. **The automation consumer is not exempt** (D11).

#### 5.11.2 Classes

```java
public enum ApprovalOperation { INVOICE_CREATE, INVOICE_CANCEL, INVOICE_TERMS, PAYMENT_RECORD, PROMISE_CREATE, PROMISE_UPDATE, PROMISE_OVERRIDE, DISPUTE_APPROVE,
    THRESHOLD_CHANGE;   // appended last; entityType REGION; never in approval_thresholds
    String entityType(); String viewPrivilege(); String label(); String measureLabel(); boolean isCreate(); boolean amountGated(); }
public enum PendingStatus { PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, FAILED, CLOSED; boolean isFinal(); }
public enum ChangeOrigin { USER, AUTOMATION }                         // one AttributeConverter per enum
public sealed interface Outcome<T> permits Outcome.Applied, Outcome.Held {
    record Applied<T>(T value) implements Outcome<T> {}  record Held<T>(PendingChange change) implements Outcome<T> {}
    static <T> Outcome<T> applied(T v); static <T> Outcome<T> held(PendingChange c);
    default T appliedOrThrow(); default <R> Outcome<R> map(Function<? super T, ? extends R> f); }
record Gated(ApprovalOperation op, Long entityId, String entityLabel, String affectedType, Long affectedId, Long disputeId,
             Long customerId, String customerName, Long regionId, Long recordPocUserId, BigDecimal amount,
             Object payload, Object base, Object proposed, String summary, boolean checkerStepSatisfied) {
    String outstandingKey(); String auditEntityType() /* CUSTOMER for creates, else affectedType */; Long auditEntityId(); }
@Component MakerChecker { @Transactional(propagation = MANDATORY) Optional<PendingChange> gate(Gated g); }
final class ApprovalContext {   // ThreadLocal apply-mode data (provenance lives in WriteOrigin, D4)
    record Applying(long pendingChangeId, long checkerUserId, ApprovalOperation op, String outstandingKey, Long customerId,
                    BigDecimal approvedAmount, Instant requestedAt) {}
    public static Optional<Applying> current(); static <T> T applying(Applying a, Supplier<T> body); }
@Component PayloadCodec { String toJson(Object) /* strips *password* keys recursively */; <T> T read(String, Class<T>); void validate(Object); }
public interface ChangeApplier { Set<ApprovalOperation> operations(); void lockDomainRows(PendingChange pc); Long currentRegionId(PendingChange pc);
    Outcome<?> apply(PendingChange pc); Long resultEntityId(Object applied); Object resultView(Object applied); }
// InvoiceChangeApplier (B/invoice), PaymentChangeApplier (B/payment), PromiseChangeApplier (B/promise), DisputeChangeApplier (B/dispute),
// ThresholdChangeApplier (B/approval; BE-15; lockDomainRows = the region's threshold rows FOR UPDATE)
@Component ApprovalUsageLedger { BigDecimal recent(maker, region, op, customerId, Instant since); void record(Gated g, Long makerId, Instant at); }  // BE-15
@Component ApprovalThresholdService implements RegionCreatedListener { ThresholdsView get(regionId); Outcome<ThresholdsView> put(regionId, req);
                                                                        void onRegionCreated(Region r); }   // BE-15
@Component RegionMoveCooldown { Optional<Long> previousRegion(Long customerId, Instant now); }   // customer_region_moves within move-cooldown-days; BE-15
@Service ApprovalService { Detail approve(long id, String note) /* not @Transactional */; @Transactional Detail reject(long id, String note); @Transactional Detail withdraw(long id); }
@Component PendingChangeCloser { @Transactional(MANDATORY) int closeForCustomer(Long customerId, String reason);
                                 @Transactional(MANDATORY) int closeForRecord(String entityType, Long entityId, String reason); }  // skips the row being applied
@Component ApprovalNotifier { requested(pc); decided(pc); }
@Component PendingChangeScope { Scope forList(View view); void requireVisible(long id); }
@Component PendingChangeViews { Brief brief(pc); Detail detail(pc, caller); Object heldReply(pc); }
public final class Outcomes { static <T> ResponseEntity<Object> respond(Outcome<T> o, Function<T, ?> body, PendingChangeViews v); }
public class ApprovalConflictException extends BadRequestException {}   // GlobalExceptionHandler: 409 with its message; bulk "skipped"
public class ChangeConflictException extends RuntimeException {}  public class PayloadException extends RuntimeException {}
```

#### 5.11.3 The gate

```java
@Transactional(propagation = MANDATORY)
public Optional<PendingChange> gate(Gated g) {
  var applying = ApprovalContext.current();
  if (applying.isPresent()) {                                          // APPLY MODE: never holds
    var a = applying.get();
    boolean same = a.op() == g.op() && (g.outstandingKey() != null ? g.outstandingKey().equals(a.outstandingKey())
                                                                   : Objects.equals(a.customerId(), g.customerId()));
    if (same && g.amount().compareTo(a.approvedAmount()) > 0)
      throw new ChangeConflictException("The amount grew from " + Money.format(a.approvedAmount()) + " to " + Money.format(g.amount()) + " since the request");
    return Optional.empty();
  }
  if (g.amount().signum() <= 0) return Optional.empty();
  Instant now = appClock.instant();
  var t = effectiveThreshold(g, now);                                  // BE-15: stricter of current and previous region during the cool-down
  if (t == null) return Optional.empty();
  Long makerId = currentUser.idOrNull();
  BigDecimal measured = g.amount();
  if (measured.compareTo(t.getAmount()) <= 0 && t.getWindowHours() > 0 && makerId != null && g.customerId() != null)
    measured = measured.add(usage.recent(makerId, g.regionId(), g.op(), g.customerId(), now.minus(t.getWindowHours(), HOURS)))
                       .add(pending.sumPendingBy(makerId, g.regionId(), g.op(), g.customerId(), g.outstandingKey()));  // BE-15 (SC-9); excludes the row this one supersedes
  if (measured.compareTo(t.getAmount()) <= 0) {                        // applies: remember it for the window
    if (t.getWindowHours() > 0 && makerId != null && g.customerId() != null) usage.record(g, makerId, now);
    return Optional.empty();
  }
  if (g.checkerStepSatisfied()) return Optional.empty();              // §5.11.8
  if (makerId == null) throw new IllegalStateException(g.op() + " above threshold with no principal");   // never BadCredentials
  PendingChange superseded = null;
  if (g.outstandingKey() != null) {
    var open = pending.findByOutstandingKey(g.outstandingKey()).orElse(null);
    if (open != null) {
      if (!open.getMakerUserId().equals(makerId) || open.getOperation() != g.op())
        throw new ApprovalConflictException(g.entityLabel() + " already has a change awaiting approval (#" + open.getId() + ", "
            + open.getOperation().label() + " by " + nameOf(open.getMakerUserId()) + "). It must be approved, rejected or withdrawn first.");
      open.supersede(makerId, now); pending.saveAndFlush(open); superseded = open;          // frees the unique key
    }
  }
  var origin = WriteOrigins.current(currentUser);                     // AUTOMATION ⇒ origin, ruleId, eventId
  var pc = pending.save(PendingChange.pending(g, t.getAmount(), makerId, origin.kind() == AUTOMATION ? ChangeOrigin.AUTOMATION : ChangeOrigin.USER,
          origin.ruleId(), origin.eventId(), codec.toJson(g.payload()), codec.toJson(g.base()), codec.toJson(g.proposed()), now));
  if (superseded != null) { superseded.setSupersededById(pc.getId());
      auditService.recordLinked(g.auditEntityType(), g.auditEntityId(), "CHANGE_SUPERSEDED", null, null, makerId, g.disputeId(), superseded.getId(), "Replaced by #" + pc.getId()); }
  auditService.recordLinked(g.auditEntityType(), g.auditEntityId(), "CHANGE_REQUESTED", g.base(), g.proposed(), makerId, g.disputeId(), pc.getId(), g.summary());
  notifier.requested(pc);
  return Optional.of(pc);
}
```

**Order in every gated method:** authorise (region) → validate and compute → **gate** → mutate → side effects (audit, notify, reevaluate, **publish**). A `Held` result returns immediately and touches nothing else.

**`effectiveThreshold(g, now)`** (BE-13 returns `thresholds.findByRegionIdAndOperation(g.regionId(), g.op())`; BE-15 adds the cool-down; critic finding SC-4):
- `cur` = the row for (record's region, op). If `regionMoveCooldown.previousRegion(g.customerId(), now)` returns a region P (a `customer_region_moves` row within `app.approval.move-cooldown-days`, default 30), `prev` = the row for (P, op).
- The result is the **stricter** row: whichever of `cur` and `prev` exists with the lower `amount` (and, on a tie, the longer window). A region with no row does not gate, so a move into an ungated region still gates at the previous region's amount for the cool-down.
- The approver still needs `CHANGE_APPROVE` in the record's **current** region, which is the `pending_changes.region_id`.

**Cumulative window** (BE-15; critic finding SC-9). Splitting a change must not defeat the threshold. When the change alone is within the threshold and the row's `window_hours > 0`, the measure becomes the change's amount **plus** the same maker's recent unapproved applied changes for the same (region, operation, customer) in the window (`approval_usage`) **plus** their PENDING ones (not counting a pending change that this one would supersede). If that exceeds the threshold, the change is held; its summary reads "…; with your other changes to Acme Ltd in the last 24 hours this comes to ₹1,98,000.00". An applied change within the window records its amount in `approval_usage`. Approved changes are not counted (a checker has seen them). A walk-down of a promise in small `PROMISE_UPDATE` steps sums the steps' measures, so it is caught too. The usage query runs only when a threshold row exists and the change alone is within it, so ungated saves read nothing extra.

**Base and proposed views** (small records per domain, compared field by field):
- `CancelView(total, paidAmount, status)`
- `TermsView(dueDate, paymentTerm, notes, salesPocUserId)`
- `PromiseView(amount, promisedDate, collectionPocUserId, notes, invoiceIds)`
- `OverrideView(status, statusOverridden, overrideReason)`
- `DisputeTargetView(targetType, total, paidAmount, status, amount)`
- Creates have a null base.

#### 5.11.4 Responses and controllers

- **Applied:** the existing **200** and DTO, byte-identical (P7).
- **Held:** **202**, `Location: /api/approvals/{id}`, body in §6.6. The body **has no top-level `id`**.
- Controllers return `ResponseEntity<Object>` via `Outcomes.respond(service.xOrHold(...), mapper, views)`:
  - `InvoiceController.create`, `update`, `cancel` (:81–98)
  - `PaymentController.create` (:78–82)
  - `PaymentPromiseController.create`, `update`, `override` (:81–106)
  - `DisputeController.approve` (:66–71)
- **Bulk:**
  - `BulkDtos` gains `record BulkPending(Long id, Long pendingChangeId, String summary)`, and `BulkResult` gets a trailing `List<BulkPending> pending`. The only constructor call is `BulkExecutor:83`.
  - `BulkExecutor` gains `OutcomeOperation`, `eligibilityOutcome(op)` and `runOutcomes(req, permitted, truncated, op)`. `Held` goes to `pending`. `run(...)` delegates to it.
  - Invoice bulk CANCEL uses `runOutcomes(..., eligibilityOutcome(service::cancelOrHold))`, so an `ApprovalConflictException` is reported as skipped.

#### 5.11.5 Canonical lock order

**customer → dispute → invoices (ascending id) → promise → pending_changes → invoice-number sequence**

The rule behind it (critic findings CF-8, SC-19): **every path that locks more than one of these rows takes the customer row first.** All such paths then serialise on the customer, so the order of the later locks cannot produce a deadlock between them. The region move (customer → invoices → the payment, promise and dispute UPDATEs) is covered by the same rule.

- Makers follow it: invoice update and cancel, promise `getForWrite`, dispute approve and deny, `setAssignees`, and every create lock the customer before their own row (§5.6.6). The promise sweep, `reevaluateForCustomer` and `recomputeAll` lock the customer per customer.
- Approval follows it through `ChangeApplier.lockDomainRows`, then `pending.findByIdForUpdate`:

| Operation | `lockDomainRows` takes |
|---|---|
| `DISPUTE_APPROVE` | customer, then dispute |
| `INVOICE_CANCEL`, `INVOICE_TERMS` | customer, then invoice |
| creates and promise operations | customer (and the promise for promise operations) |
| `THRESHOLD_CHANGE` | the region's threshold rows |

- **`pending_changes` before the sequence.** Approving an `INVOICE_CREATE` locks the pending row and then, inside the apply, takes the invoice-number sequence. No path takes the sequence and then a `pending_changes` row (a maker's create either holds, taking no number, or applies without locking any pending row), so the order is consistent.
- Close paths also follow it: move and delete take customer → pending; deny takes customer → dispute → pending.
- Residual deadlocks surface as `ConcurrencyFailureException` → 409 with nothing written. Approval is retry-safe.

#### 5.11.6 Approve, reject, withdraw

**Checks**, in order, after the locks:

| # | Check | Failure |
|---|---|---|
| 1 | `pendingChangeScope.requireVisible(id)` | 404 |
| 2 | live (the interceptor already refuses asOf; defence in depth) | 400 `AS_OF_READ_ONLY` |
| 3 | status is PENDING | 409 `ApprovalConflictException("Already {status} by {name} at {time}")` |
| 4 | caller is not a customer login | 403 |
| 5 | caller ≠ maker | **400** "You cannot approve a change you requested" |
| 5a | when the change came from an **internal** dispute: caller ≠ the dispute's opener (the rule owner) | **400** "A change your own rule proposed must be approved by someone else" (critic finding SC-10) |
| 6 | `currentUser.hasIn(CHANGE_APPROVE, pc.regionId)` | 403 |

**`approve(id, note)`.** The approval origin is bound **around** TX-A, so it is still in force when TX-A commits and the outbox and the history writer stamp `origin = APPROVAL`, `origin_ref_id = pcId` and `actor = maker` (D4; critic findings CF-1, SC-6). The checker's SecurityContext stays in place for checks 1–6, the `CHANGE_APPROVED` audit row and the views; only the apply runs as the maker.
```
assert !TransactionSynchronizationManager.isActualTransactionActive();
checker = currentUser.require();
makerId = pending.findMakerId(id).orElseThrow(404);                          // unlocked read, only to bind the origin
try {
  return WriteOrigins.callWith(WriteOrigin.approval(id, checker.getId()).withActor(makerId), () ->
   tx.execute(s -> {                                                         // TX-A
    peek = pending.findById(id).orElseThrow(404); applier = appliers.of(peek.getOperation());
    applier.lockDomainRows(peek); pc = pending.findByIdForUpdate(id).orElseThrow(404);
    checks(pc, checker);                                                    // rules 1–6 throw straight out
    try {
      if (!pc.getRegionId().equals(applier.currentRegionId(pc))) throw new ChangeConflictException("The record has moved to another region");
      out = runAs.call(principals.principalFor(pc.getMakerUserId()).orElseThrow(InactiveUserException::new),
                       ViewScope.LIVE_ALL, WriteOrigin.approval(pc.getId(), checker.getId()).withActor(pc.getMakerUserId()),
                       () -> ApprovalContext.applying(new Applying(pc.getId(), checker.getId(), pc.getOperation(),
                                 pc.outstandingKeyAtRequest(), pc.getCustomerId(), pc.getAmount(), pc.getRequestedAt()),
                             () -> applier.apply(pc)));
    } catch (RuntimeException e) { if (isApplyFailure(e)) throw new ApplyFailed(reasonOf(e)); throw e; }
    value = out.appliedOrThrow();                                          // Held impossible in apply mode → 500
    pc.approve(checker.getId(), fit(note), appClock.instant(), applier.resultEntityId(value), codec.toJson(applier.resultView(value)));
    auditService.recordLinked(auditType(pc), auditId(pc), "CHANGE_APPROVED", null, null, checker.getId(), disputeId(pc), pc.getId(), note);
    notifier.decided(pc); assigneeNotifications(pc, value);                 // co-assignees of an applied held promise (§5.10.12)
    return views.detail(pc, checker);
   }));                                                                     // TX-A commits here, origin still bound
} catch (ApplyFailed f) {                                                   // the origin binding has been restored by now
  return tx.execute(s -> {                                                  // TX-B: re-read; never reuse TX-A entities
    pc = pending.findByIdForUpdate(id).orElseThrow(404);
    if (pc.getStatus() == PENDING) { pc.fail(checker.getId(), fit(f.reason()), appClock.instant());
        auditService.recordLinked(..., "CHANGE_APPLY_FAILED", ..., pc.getId(), f.reason()); notifier.decided(pc); }
    return views.detail(pc, checker);
  });
}
```

**`isApplyFailure`**: these become FAILED with the reason shown:

| Exception | Reason |
|---|---|
| `BadRequestException` | its message |
| `InvalidFieldsException` | field messages joined with "; " |
| `NotFoundException` | "The requester can no longer reach this record" |
| `AccessDeniedException` or any `AuthenticationException` | "The requester no longer has the right to make this change" |
| `RunAs.InactiveUserException` | "The requester's account is no longer active" |
| `ChangeConflictException`, `PayloadException` | message |

`ConcurrencyFailureException` and `DataIntegrityViolationException` propagate as **409**, and the row stays PENDING. **No 401 ever reaches the checker** (G26).

**`reject(id, note)`:** lock; rules 1–4 and 6; caller == maker → 400 "Use Withdraw for your own request"; `note` must be 1..500 characters, otherwise `InvalidFieldsException(note)`. Result: REJECTED, audit `CHANGE_REJECTED`, maker notified.

**`withdraw(id)`:** lock; rules 1–3; the caller must be the maker (403 otherwise). Result: WITHDRAWN, `decided_by = maker`, audit `CHANGE_WITHDRAWN`.

**Conflict detection** inside `ChangeApplier.apply` (after the locks, before calling the method):
1. **Rules re-run** because the method runs again, as the maker.
2. **Field checks:**
   - Three-way for INVOICE_TERMS and PROMISE_UPDATE: for each field the request changes, if `current ≠ base ∧ current ≠ proposed` → `ChangeConflictException("<Label> was changed by someone else")`. PROMISE_UPDATE is re-based.
   - Strict for INVOICE_CANCEL, PROMISE_OVERRIDE and DISPUTE_APPROVE.
3. **Amount not grown** (the gate's apply mode).

**Payload evolution:** `ObjectMapper` ignores unknown properties, and `codec.validate` re-checks constraints. Either error gives `PayloadException`, which makes the change FAILED.

**Dates at approval:**
- `invoiceNumbers.next()` runs in TX-A, so the number carries the **approval**-day prefix. Rejected requests leave no gaps.
- A null `invoiceDate` becomes the approval instant.
- `paidAt` is the **request** instant.

#### 5.11.7 One outstanding change per record; closing

- **At most one PENDING change per existing record**, enforced by the unique `outstanding_key`.
- Same maker, **same operation** → the old change becomes SUPERSEDED (`decided_at` equals the new `requested_at`), and the 202 carries `supersededId`.
- A different maker, or a different operation → 409. Bulk reports it as skipped. A true race → unique violation → 409.
- Creates have no key, so several pending creates per customer are allowed.

**Closing** (status CLOSED, `decided_by` null, note = reason, maker notified `APPROVAL_CLOSED`):

| Trigger | Call |
|---|---|
| `CustomerService.delete` | `closer.closeForCustomer(id, "Customer deleted")` |
| Region move by someone holding `CHANGE_APPROVE` in the source region | `PendingChangeMoveParticipant` → `closeForCustomer(cid, "Customer moved from A to B")` (D7). Without that right the move is **refused** while changes are pending (SC-4). |
| `DisputeService.deny` | `closeForRecord("DISPUTE", id, "Dispute denied")` after `mustBePending` |
| `approveOrHold` applied path | `closeForRecord("DISPUTE", id, "Dispute resolved")` |
| `cancelOrHold` applied path | `closeForRecord("INVOICE", id, "Invoice cancelled")` |

A threshold change (applied at once or approved as a THRESHOLD_CHANGE) never touches other pending rows.

#### 5.11.8 Dispute approval as the checker step (R7)

In `approveOrHold`:
```java
boolean asProposed = req == null || isBlank(req.appliedChangeJson())
    || objectMapper.readTree(req.appliedChangeJson()).equals(objectMapper.readTree(d.getProposedChangeJson()));
Long credentialsSetBy = userRepository.findCredentialsSetBy(d.getOpenedByUserId()).orElse(null);    // BE-14
boolean checkerStep = asProposed && !caller.getId().equals(d.getOpenedByUserId())
    && !caller.getId().equals(credentialsSetBy)                                                     // SC-10
    && currentUser.hasIn(CHANGE_APPROVE, d.getRegion().getId());
```

- A JSON parse error is 400 "The change is not valid JSON".
- A customer's proposal approved as proposed by a `CHANGE_APPROVE` holder in the region **applies at once**, unless the approver is the staff member who last set that customer login's password (`users.credentials_set_by_user_id`, §3.4). Staff set customer passwords (`CustomerCreateRequest.password`, `CustomerUpdateRequest.password`), so otherwise one person could sign in as the customer, propose a cancellation and approve it alone (critic finding SC-10). In that case the approval is held with maker = the approver, as for any other staff approval. The field is cleared when the customer changes their own password.
- Otherwise, above the threshold it is held with maker = the staff member who clicked Approve, and the dispute stays PENDING.
- The internals are never gated, so an approved dispute is never held twice.
- `CHANGE_*` audit rows go on the dispute's **target**, with `dispute_id`.
- For internal (automation) disputes, the own-rule rule in §5.10.11 applies first, a held change is stored with `internal_source = true`, and check 5a of §5.11.6 stops the rule owner approving it later.

#### 5.11.9 Visibility (`PendingChangeScope.forList(view)`)

```
VISIBLE :=  (maker_user_id = me AND region_id IN rights.grantedRegions())      -- SC-15: a maker who lost the region loses the row
         OR region_id IN regionsWith(CHANGE_APPROVE)
         OR (entity_type = 'REGION' AND region_id IN regionsWith(APPROVAL_THRESHOLD_MANAGE))   -- THRESHOLD_CHANGE
         OR (entity_id IS NOT NULL AND ( (entity_type='INVOICE' AND entity_id IN ids(Invoice, forInvoices()))
                                      OR (entity_type='PAYMENT' AND entity_id IN ids(Payment, forPayments()))
                                      OR (entity_type='PROMISE' AND entity_id IN ids(PaymentPromise, forPromises()))
                                      OR (entity_type='DISPUTE' AND entity_id IN ids(Dispute, forDisputes())) ))
         OR (entity_id IS NULL AND ((operation='INVOICE_CREATE' AND region_id IN regionsWith(INVOICE_VIEW))
                                 OR (operation='PAYMENT_RECORD' AND region_id IN regionsWith(PAYMENT_VIEW))
                                 OR (operation='PROMISE_CREATE' AND region_id IN regionsWith(PROMISE_VIEW)))
                               AND (record_poc_user_id = me OR customer_id IN ids(Customer, forCustomers())))
AND (ViewScope.regionIds == null OR region_id IN ViewScope.regionIds)          -- never lockedFilters (R14)
AND view: AWAITING_ME → status='PENDING' AND maker_user_id <> me AND region_id IN regionsWith(CHANGE_APPROVE)
          MINE → maker_user_id = me ; ALL → true
AND as-of p (when bound): requested_at <= p AND (decided_at IS NULL OR decided_at > p)
```

- `ids(...)` subqueries follow `DashboardService.idsInScope`: pass the outer `cq`, use `Temporal.from`.
- **`Detail.current` and `changes[].current`** are filled only when the caller can still see the record (`scopeResolver.requireVisible` as the caller); otherwise they are null and `blockedReason` reads "You no longer have access to this record" (SC-15).
- **Customer logins:** `GET /api/approvals` returns 403. `for-record` works on their own existing records only, with `requestedBy`, `decidedBy`, `decisionNote` and `origin` nulled and every `*poc*` key stripped. **Rows with `internal_source = true` are never returned to a customer login**: not by `for-record`, not in the `pendingApproval` EXISTS predicate or DTO flag, and not in the History-tab `pendingStatus` (critic finding SC-1). Staff without POC_VIEW also get POC keys stripped.
- **`TableSchemas.APPROVALS`** (entity `approvals`, default sort `requestedAt,desc`). Column keys equal the `Brief` keys, so sort keys and cells agree (critic finding CF-16): `id`, `status` ENUM, `operation` ENUM, `entityType` ENUM, `entityId`, `entityLabel`, `customerId` REF `customer`, `customerName`, `regionId` REF `region`, `amount` MONEY, `threshold` MONEY, `summary` (not sortable), `requestedByUserId` REF `staffUser` (the cell reads `requestedBy.name`), `origin` ENUM, `requestedAt`, `decidedAt`.
- **List flag and filter.**
  - A BOOLEAN custom-filter column `pendingApproval` (`eq` only, not sortable, dummy path `root.get("id")`) on INVOICES, PAYMENTS and PROMISES matches `affected_type`/`affected_id`; on DISPUTES it matches `entity_*`.
  - The predicate is an EXISTS over `pending_changes` with `status='PENDING'` live, or the interval at p, plus `internal_source = false` for customer logins. It copies `pocSeatPredicate`'s operator switch.
  - `InvoiceSummary`, `PaymentDto`, `PromiseDto` and `DisputeDto` gain a nullable `Boolean pendingApproval` (`@JsonInclude(NON_NULL)`), filled only by `page()` with one batch query `findOutstandingAffected(type, ids, includeInternal)`.

#### 5.11.10 As-of (B3)

- "Outstanding at T" uses the interval predicate (D8).
- In historical mode the list rows show `statusAt: "PENDING"`, and the summary, count, `for-record`, `pendingApproval` and History decoration all use the interval.
- Visibility applies the caller's **current** rights to `region_id`.
- Approve, reject and withdraw are refused (400).

#### 5.11.11 Audit, History tab, notifications, thresholds

**Lifecycle actions** (each ≤ 40 characters, none containing "POC"):
- `CHANGE_REQUESTED`, `CHANGE_SUPERSEDED`: maker
- `CHANGE_APPROVED`, `CHANGE_REJECTED`, `CHANGE_APPLY_FAILED`: checker
- `CHANGE_WITHDRAWN`: maker
- `CHANGE_CLOSED`: null

They are recorded on CUSTOMER for creates, otherwise on `affected_*`, with `pending_change_id`. The applied rows have actor = maker and carry `pending_change_id` and `approved_by_user_id`, stamped from `WriteOrigin.approval`.

**`AuditEntryDto`** gains `pendingStatus`, filled from `pending_changes` in one batch lookup (the status at T in as-of mode). `withoutPocIdentity`, for customer logins:
- drops CUSTOMER-level `CHANGE_*` rows;
- nulls `approvedBy*`;
- extends the staff-field set with `makeruserid`, `requestedby`, `decidedby`, `decidedbyuserid`, `approvedby` and `approvedbyuserid` (`AuditTimelineService:278-279`).

**Notifications**
- `ApprovalNotifier.requested` (in the maker's transaction) → `notificationService.notifyHolders(CHANGE_APPROVE, regionId, makerId, "APPROVAL_REQUESTED", "Approval needed: " + label, summary, "/approvals/" + id)`. At most 100 recipients.
- `decided`: the maker gets `APPROVAL_APPROVED` (link to the record), `APPROVAL_REJECTED`, `APPROVAL_FAILED` or `APPROVAL_CLOSED` (link `/approvals/{id}`). Nothing is sent for SUPERSEDED or WITHDRAWN.
- `approverCount` = active holders in the region, excluding the maker.

**User deletion.** `pocReferenceCount` adds `pendingChangeRepository.countByMakerUserIdOrDecidedByUserId(id, id)`.

**Thresholds** (`ApprovalThresholdController`, `/api/regions/{regionId}/approval-thresholds`):
- `GET` needs `hasIn(APPROVAL_THRESHOLD_MANAGE, r) || hasIn(CHANGE_APPROVE, r)`.
- `PUT` needs `@PreAuthorize(APPROVAL_THRESHOLD_MANAGE)` and `requireIn(APPROVAL_THRESHOLD_MANAGE, r)`. It runs in one transaction, validates, and splits the request per operation (critic finding SC-11):
  - **Tightening** (a new row, a lower amount, or a longer window) is applied at once.
  - **Loosening** (a higher amount, a shorter window, or a deleted row) is collected into **one** `THRESHOLD_CHANGE` pending change for the region (`entity_type = REGION`, `entity_id = regionId`, `outstanding_key = "REGION_THRESHOLDS:<regionId>"`, payload = the loosening rows, base = the current rows). It needs another `CHANGE_APPROVE` holder in the region (checks 1–6 of §5.11.6), and `ThresholdChangeApplier` applies it as the maker, re-checking that the maker still holds `APPROVAL_THRESHOLD_MANAGE` there. The same maker's second loosening supersedes the first; anyone else's is 409.
  - A mixed request applies its tightening part and returns **202** with the pending change for the rest; a request that only tightens returns 200.
  - Every change, applied or held, is audited `("REGION", regionId, "APPROVAL_THRESHOLDS_CHANGED")` and notified to the region's other `CHANGE_APPROVE` holders (`notifyHolders`, type `APPROVAL_THRESHOLDS_CHANGED`).
  - It warns when `activeApprovers < 2`.
- **New regions** start with a copy of the default region's rows (`ApprovalThresholdService.onRegionCreated`, in the region-create transaction; SC-4). A copy is a tightening, so it applies at once.
- JSON in §6.11.

### 5.12 Dashboard (`B/dashboard`)

**Region split for "collected"** (`collectedByMonth`, `topPaying`), replacing `paidAgainstBookOnly()` (:203–205) (BE-03):
```
if customer login → today's payment path
inv = forInvoices(); pay = forPayments();
partA (payment sums)  = pay.coverage().full()                          // override regions
partB (allocations)   = pay.coverage().book() ∪ inv.coverage().book()  // no-override regions
A: Root<Payment> where pay.predicates ∧ region ∈ partA ∧ status = ACTIVE                         (skip if partA = ∅)
B: Root<PaymentAllocation> ⋈ payment ⋈ invoice where payment.status = ACTIVE ∧ invoice.region ∈ partB
       ∧ (invoice.id ∈ idsInScope(Invoice, inv) ∨ payment.id ∈ idsInScope(Payment, pay))          (skip if partB = ∅)
result = A + B   (month by month; topPaying merged per customer, sorted desc, then limited; I1 ⇒ disjoint)
```

- The result is identical to today for single-region users of every role shape, including PAYMENT_VIEW-only override roles (F2).
- **Coverage** becomes `{ ALL, BOOK, OWN, MIXED }`:
  - `coverage(scope)`: OWN for a customer login; BOOK if `lockedFilters` is non-empty; MIXED if `coverage.mixed()`; otherwise ALL.
  - Collected: A only → ALL; B only → BOOK; both → MIXED; neither → BOOK if either scope has `lockedFilters`, otherwise ALL.
- Every response record gains a trailing `ScopeInfo scope`:
  - `record ScopeInfo(List<Long> regionIds, List<Long> bookRegionIds, String asOf)`.
  - For collected it is `(A ∪ B, B, asOf)`.

**As-of** (BE-10):
- The `Invoice`, `Payment` and `PaymentAllocation` roots become `Root<?>` via `Temporal.fromValid(cq, X.class, cb, where)`.
- The joins at :119, :184–185, :194 and :200 become `Temporal.join(x, "…", INNER, cb)`, and the generics widen to `From<?, ?>`.
- `idsInScope` (:311–317) becomes `Temporal.subquery`.
- `DashboardController.today()` returns `Today.date()`.
- Consequences:
  - D1 excludes invoices created after T, even when backdated.
  - A payment voided after T is ACTIVE at T, with its allocations.
  - D3 shows customer names at T.

### 5.13 Audit and timeline (`B/audit`)

- `AuditLog` gains `region_id`, `origin`, `rule_id`, `pending_change_id` and `approved_by_user_id` (BE-01).
- `AuditLogRepository.findLatestRegionId("CUSTOMER", id)` (BE-04b).
- `AuditController.SUPPORTED` and `VIEW_PRIVILEGE` become `Map.ofEntries`, with:
  - `REGION → REGION_VIEW` (BE-05; read through `regionService.get`);
  - `TASK → TASK_VIEW` (BE-12; `taskService.get`);
  - `RULE → AUTOMATION_VIEW` (BE-16; `ruleService.get`).
- `AuditEntryDto` gains `origin`, `ruleId`, `pendingChangeId`, `approvedByUserId`, `approvedByUsername` (BE-01) and `pendingStatus` (BE-15).
- **Customer redaction** (`withoutPocIdentity`):
  - drop CUSTOMER-level `CHANGE_*` rows;
  - null `approvedBy*`;
  - add the staff-field names of §5.11.11;
  - for rows whose `disputeId` refers to an internal dispute, null `reason` and `disputeId`.
- **`AuditTimelineService.timeline(entityType, entityId, includeRelated, boolean customerView)`** (critic finding SC-1). `AuditController` passes `customerView = currentUser.isCustomer()`. Today the timeline loads every dispute of the customer, invoice or payment (`AuditTimelineService.java:73-80`, 86-87, 98-99) and redaction strips only POC keys, so a customer would read internal disputes' reasons and proposals. With `customerView`:
  - related disputes are filtered with `!d.isInternal()` (BE-19);
  - `Related.task` is never added;
  - recorded and derived entries with entity type `DISPUTE` or `TASK` whose record is internal (every task; internal disputes) are dropped;
  - `CHANGE_*` rows whose pending change has `internal_source = true` are dropped (BE-15).
- Task history on a record: for staff callers, `AuditTimelineService.timeline(includeRelated)` adds `Related.task` for CUSTOMER, INVOICE and PAYMENT (BE-12).

### 5.14 Changes to existing services, by file (consolidated)

This is the checklist agents use to avoid overwriting each other's work. "BE-nn" is the slice.

| File | BE-01 | Regions (02–05, 08) | History (06–10) | Outbox (11) | Tasks (12) | Maker-checker (13–15) | Automation (16–19) |
|---|---|---|---|---|---|---|---|
| `customer/CustomerService` | clock | create region + lock, update/delete `requireIn`, `requireVisible`, DTO region/flags, delete audit region | `get`→Records, `toDtos` as-of (09a), subqueries Temporal | C1, C2 (+ `children` on rename) | `taskCascade` on delete | `closeForCustomer` on delete; `credentials_set_by_user_id` on password set (14) | `snapshotOf` public static (19) |
| `invoice/InvoiceService` | clock (66, 215) | lock customer (F1), book-aware `requireVisible(CUSTOMER)` on create, customer-first lock on update/cancel (04a), `requireIn`, assignable per region, `requireVisible` | `get`→Records | I1–I5 (`invoiceMoney`) | — | `createOrHold`, `updateOrHold`, `cancelOrHold`, `totalFor`, number after the gate, closer | — |
| `payment/PaymentService` | — | lock/book-aware `requireVisible(CUSTOMER)`/`requireIn`/assignable, `requireVisible`, delete `list` | `get`→Records (09b) | P1–P4, C4, C5 | — | `recordOrHold` (`paidAt` rule) | — |
| `payment/CreditLedger` | — | — | — | C6, C7 | — | — | — |
| `promise/PaymentPromiseService` | clock | F1, book-aware create, `getForWrite` (customer then promise lock), sweep/reevaluate/recompute lock per customer, `recomputeAll` regions, `notifyBroken` region filter, delete dead methods | `get`→Records (09b) | — | — | `createOrHold`, `updateOrHold`, `overrideOrHold` | `setAssignees` (via `AssigneeStore`); `assignees` stored in `createOrHold` (19) |
| `dispute/DisputeService` | clock | F1 on open, approve/deny customer-first, `notifyAdmins(region)`, staff `requireVisible`, delete `list` | `get`/describe→Records (09b) | I6, P5 | — | parse/measure/apply split, `approveOrHold`, checker step (with `credentials_set_by`), closer | CUSTOMER target, `internal`, `openInternal`, open guard `internal = false`, `internal_source` on holds, assignees (via `AssigneeStore`), own-rule rule |
| `poc/PocService` | — | `requireAssignable(region)`, holders filter, `notifyAssignee` region filter, delete dead methods | `listFor` as-of | C3 | — | — | — |
| `poc/ScopeResolver` | — | full rewrite (§5.6.4) | Temporal subqueries | — | `Kind.TASK`/`forTasks` | — | `forDisputes` `internal = false` (19) |
| `email/EmailTargets` | — | `regionId` on Target, `loadForSend`, bulk scope, holders filter | `loadUnscoped` and `loadScoped` PRODUCT→Records (09b) | — | static `offeredRoles(type)` | — | `disputeOwnFields` switch, CUSTOMER label (19) |
| `email/EmailService` | — | `requireLive` on send/bulk/retry | list/get as-of | — | — | — | `previewRendered` + `resolveForAutomation` (16); `queue`, placeholders in manual send/preview/bulk (18) |
| `email/EmailAddressing`, `email/EmailText` | — | region check on USER to/from (04b) | — | — | helpers public static | — | `EmailText` public (16) |
| `email/InboxController`, `InboxService` | — | `EmailRegionPredicate` on list and unread count (03) | — | — | — | — | — |
| `document/DocumentTargets`, `DocumentService` | clock | `regionId`, `requireIn`, flags | `onRecord` as-of, flags | — | — | — | — |
| `dashboard/DashboardService`, `DashboardController`, `DashboardDtos` | — | split, coverage, `ScopeInfo` | Temporal roots | — | — | — | — |
| `audit/*` | columns, stamping, DTO fields | region overload, `readOrGone` | timeline as-of | — | TASK; `customerView` (no `Related.task`) | `pendingStatus`, redaction, `internal_source` rows dropped for customers | RULE, internal-dispute rows dropped for customers |
| `notification/NotificationService`, `NotificationController` | — | `notifyAdmins(region)` + active filter, link guard, `region_id`, `notifyHolders`, list/unread `regionIds` filter (04b) | — | — | — | — | — |
| `user/UserController`, `UserRepository` | clock | bridge grant (02), `UserAccountService` (05), `findByIdForUpdate`, grants in DTO | — | — | `pocReferenceCount` (assignees) | `pocReferenceCount` (pending); `findCredentialsSetBy` (14) | `pocReferenceCount` (rules), suspend on deactivate, `recheckOwner` on role change, `findActiveStaffHolding` |
| `role/RoleController` | — | — | — | — | — | — | `recheckOwnersOfRole` on privilege edits (17) |
| `auth/AuthController` | clock | `/me` regions (05) | — | — | — | clear `credentials_set_by_user_id` on own password change (14) | — |
| `region/*` | — | entities, rights, grants (granter bound, 05), move (+ `customer_region_moves`, 08) | — | `AutomationMoveParticipant` | `TaskMoveParticipant` | `PendingChangeMoveParticipant` veto/close, `RegionCreatedListener` (15) | `recheckOwner` from grant replace (17) |
| `history/*` | — | — | capture, `HistoryFaults`, `done` guard (07) | `txId(em)`, `history_transactions` (11) | TASK | — | — |
| `common/query/TableSchemas`, `TableSchemaController` | — | region columns, REGIONS, `Map.ofEntries` | Temporal subqueries | — | TASKS | APPROVALS, `pendingApproval` columns | AUTOMATION_RULES/RUNS, `paymentTerm`, `overdueAmount` (16), `assigneeUserId`, `internal` (19) |
| `common/query/TableQueryExecutor`, `ColumnDef` | — | — | root swap, `nested` via Temporal | — | — | — | — |
| `common/bulk/BulkExecutor`, `BulkDtos` | — | — | — | — | — | `pending`, `runOutcomes` | — |
| `auth/*`, `config/SecurityConfig`, `config/DataSeeder` | CORS header, clock | principal, rights, seeding grants | — | — | TASK_* seeding | CHANGE_* seeding | AUTOMATION_* seeding |
| `privilege/Privileges` | — | REGION_*, classification | — | — | TASK_* | CHANGE_APPROVE, APPROVAL_THRESHOLD_MANAGE | AUTOMATION_* |
| every controller | `@AsOfAware`/`@AsOfIgnored` | `resolveIds(access)`, region DTO fields | — | — | — | 202 on the 8 endpoints and the threshold PUT | — |

### 5.15 Configuration

```yaml
# R/application.yml
spring.task.scheduling.pool.size: 7   # email sweeper, promise sweeper, automation poll, automation tick, history reconcile,
                                      # approval housekeeping (BE-15), 1 spare
app:
  history: { strict: false, reconcile-interval-ms: 86400000 }
  approval: { move-cooldown-days: 30 }  # BE-15
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
    max-catch-up-slots: 31
    event-retention-days: 14   # also bounds history_transactions and REPLAY
    default-zone: ${AUTOMATION_ZONE:UTC}
    node-id: ${AUTOMATION_NODE_ID:}
# prod profile: spring.datasource.hikari.maximum-pool-size 5 → 8

# backend/src/test/resources/application-test.yml additions
app:
  history: { strict: true, reconcile-interval-ms: 3600000 }
  automation: { poll-interval-ms: 3600000, tick-interval-ms: 3600000 }
```

`OpenEntityManagerInViewConfig` is unchanged. None of the new endpoints make remote calls.

---

## 6. REST API contract

This section is complete enough for the Flutter app to be built without reading backend code. Anything not listed here is unchanged.

### 6.0 Conventions

**Query parameters on every request.** Both are optional, and the frontend sends each **only when set**.

| Parameter | Format | Effect |
|---|---|---|
| `regionIds` | `regionIds=1,2` (repeated `regionIds=1&regionIds=2` also works) | Narrows every **set** answer: lists, summaries, exports, bulk "select all", dashboards, pickers, approvals lists, task lists. By-id reads and single-record writes ignore it (I3). Customer logins ignore it. Unknown or ungranted ids contribute nothing (200, empty). Non-numeric → 400 `REGION_IDS_INVALID`. |
| `asOf` | `asOf=2026-06-30` (strict ISO date) | See D2 and D9. |

**Response header.** `X-View-As-Of` is present whenever `asOf` was sent. Its value is either the date (the answer is historical) or `live` (the endpoint answers live, or `asOf` equalled today). It is exposed through CORS.

**List envelope (`PageResponse`).**
```json
{"content":[...],"page":0,"size":20,"totalElements":123,"totalPages":7,"sort":"invoiceDate,desc",
 "appliedFilters":["status:in:UNPAID"],"lockedFilters":["salesPocUserId:eq:5"],
 "regionIds":[1,2],"bookRegionIds":[2],"asOf":"2026-06-30"}
```

| Field | Meaning |
|---|---|
| `regionIds` | Regions actually answered: the intersection of those asked for and those granted. `[]` for unscoped lists such as users, roles, products and regions. Notifications, the inbox and the rules list answer "which regions" too (§5.6.7, §5.10.10). |
| `bookRegionIds` | Answered regions where the caller sees only their own book |
| `asOf` | null when live |
| `lockedFilters` | Still means "your book only" (R14). It is present only for a **pure** book view. |

**Error body (`ApiError`).** `{"timestamp","status","error","message","path","fieldErrors"}`.
- For coded errors, `error` is the machine code (for example `AS_OF_READ_ONLY`, `REGION_IDS_INVALID`) and `message` is user-facing.
- Otherwise `error` is the reason phrase, as today.
- `fieldErrors` is present on validation failures.

**Formats.**
- Instants are ISO-8601 UTC (`2026-09-26T09:14:03.120Z`). Dates are `yyyy-MM-dd`.
- Money is a JSON number with 2 decimals.
- Enums are UPPER_SNAKE strings. Clients must tolerate unknown values (G25).

**Status codes.** 200 OK. **202 held for approval** or queued (only the eight gated endpoints, the threshold PUT when it loosens, run-now, retry and requeue). 204 on region delete and rule delete. 400 business or validation. 403 no right. 404 invisible or missing. 409 concurrency or approval conflict. 401 only for authentication.

**Capability flags** on record DTOs are server-computed and `false` in historical mode (D14). They are omitted (null) in audit snapshots.

### 6.1 Classification of new endpoints

| Endpoint group | As-of | Honours `regionIds` |
|---|---|---|
| `/api/regions/**` (incl. grants, thresholds), `/api/users/{id}/region-grants`, `/api/history/range`, `/api/assignees/**`, `/api/automation/placeholders`, `/api/automation/health`, `/api/approvals/operations` | IGNORED (live) | no, except `/api/assignees/people` (yes, §6.13) |
| `/api/approvals` (list, summary, count, for-record, `{id}`), `/api/approvals/export` (POST, AWARE) | AWARE (interval) | yes |
| `/api/tasks/**` GETs, `/api/tasks/export` (POST, AWARE) | AWARE (versions + assignee interval) | yes |
| `/api/automation/rules`, `/rules/summary` and `/rules/{id}` GET | AWARE (revisions) | yes: rules whose effective regions meet the asked regions (`RuleVisibility`, §5.10.10) |
| `/api/automation/rules/preview` (POST, AWARE), `/rules/{id}/preview`, `/rules/{id}/matches` | AWARE (versions; a saved rule's revision at T) | yes (evaluation scope) |
| `/api/automation/rules/{id}/runs`, `/runs/{id}`, `/runs/{id}/effects` | AWARE (`created_at`; runs add `statusAt`) | by-id on a visible rule; counters and targets redacted per viewer |
| `/api/notifications`, `/api/notifications/unread-count`, `/api/inbox`, `/api/inbox/unread-count` | IGNORED (live) | yes (§5.6.7) |
| `POST /api/customers/{id}/move-region` and every other new non-GET | WRITE (400 when historical) | — |

### 6.2 Auth

**`POST /api/auth/login`, `GET /api/auth/me`**: `UserInfo` gains `regions` (always present; `[]` for customer logins).

```json
{"id":7,"username":"rita","fullName":"Rita","role":"CASHIER","customerId":null,
 "privileges":["CUSTOMER_VIEW","CUSTOMER_MANAGE","INVOICE_VIEW","..."],
 "regions":[{"id":1,"code":"NORTH","name":"North","active":true,"defaultRegion":true,"level":"READ","scope":"ALL",
             "privileges":["CUSTOMER_VIEW","INVOICE_VIEW","SCOPE_OVERRIDE","..."]},
            {"id":2,"code":"SOUTH","name":"South","active":true,"defaultRegion":false,"level":"MANAGE","scope":"ALL",
             "privileges":["CUSTOMER_MANAGE","INVOICE_MANAGE","..."]}]}
```

- `privileges` is the union, including global privileges.
- `regions[].privileges` lists the regional privileges effective in that region.

**`GET /api/privileges`** (ROLE_VIEW): each item gains `"regionLevel": "READ" | "MANAGE" | "APPROVE" | null` (null = global).

### 6.3 Regions, grants, users

| Method, path | Gate | Request → response |
|---|---|---|
| `GET /api/regions?page&size&sort&filter` | REGION_VIEW **or** USER_MANAGE | `PageResponse<RegionDto>`; schema `regions` (columns `id`, `code`, `name`, `active`, `defaultRegion`, `createdAt`) |
| `GET /api/regions/{id}` | same | `RegionDto` |
| `POST /api/regions` | REGION_MANAGE | `{"code":"SOUTH","name":"South","active":true}` → 200 `RegionDto`. 400 `fieldErrors.code` or `.name` for a bad or duplicate value. The creator is auto-granted APPROVE/ALL. From BE-15 the new region starts with a copy of the default region's approval thresholds. |
| `PUT /api/regions/{id}` | REGION_MANAGE | `{"name":"South & East","active":false,"version":0}` → `RegionDto`. 409 stale version. 400 "The default region cannot be deactivated". `code` is immutable. |
| `POST /api/regions/{id}/make-default` | REGION_MANAGE | → `RegionDto`. 400 if inactive. |
| `DELETE /api/regions/{id}` | REGION_MANAGE | 204. 400 "Region is the default", or "Region still has customers, disputes or grants; deactivate it instead". 409 on another FK. |
| `GET /api/regions/{id}/grants` | REGION_VIEW **and** USER_VIEW | `[{"userId":7,"username":"rita","fullName":"Rita","roleName":"CASHIER","level":"MANAGE","scope":"ALL"}]` |
| `GET /api/users/{id}/region-grants` | USER_VIEW | `[{"regionId":2,"regionCode":"SOUTH","regionName":"South","level":"MANAGE","scope":"ALL","grantedAt":"…","grantedByUserId":1}]` |
| `PUT /api/users/{id}/region-grants` | USER_MANAGE | `{"grants":[{"regionId":1,"level":"READ","scope":"ALL"},{"regionId":2,"level":"MANAGE","scope":"BOOK"}]}` → the list above. 400 "You cannot change your own region access", 400 "Customer logins take their region from their customer", 400 `fieldErrors.grants` for an unknown or duplicate region. **403** when a changed row is beyond the caller's own grant in that region (level, ALL scope, or APPROVE without `CHANGE_APPROVE`; §4.6). Rows the caller cannot touch must be sent back unchanged. |
| `POST /api/users` | USER_MANAGE | Existing body plus optional `"regionGrants":[{"regionId","level","scope"}]`. **Absent or `[]` → no grants** (from BE-05; SC-5). Each row is bounded by the caller's own grant (403). |
| `GET /api/users/{id}` | USER_VIEW | `UserDto` gains `regionGrants` (the list above; null in lists) |

`RegionDto` is `{"id":2,"code":"SOUTH","name":"South","active":true,"defaultRegion":false,"version":0,"createdAt":"2026-09-26T10:00:00Z"}`.

### 6.4 Customers

**`POST /api/customers`**
- `CustomerCreateRequest` gains optional `regionId`.
- If it is null and the caller can manage exactly one active region (after `regionIds` narrowing), that region is used.
- Otherwise the response is 400 `fieldErrors.regionId = "Choose the region this customer belongs to"`.
- Other errors: 403 without CUSTOMER_MANAGE in that region; 404 for an ungranted region; 400 "Region X is inactive".

**`POST /api/customers/{id}/move-region`** (CUSTOMER_MANAGE; WRITE)

Request:
```json
{"regionId":2,"version":5,"reason":"Account moved to the South team"}
```
Response 200:
```json
{"customer":{ /* CustomerDto with regionId 2 */ },
 "moved":{"invoices":12,"payments":7,"promises":2,"disputes":1,"other":{"TaskMoveParticipant":3,"PendingChangeMoveParticipant":1}},
 "warnings":["sam.sales is Sales POC on 4 invoices but has no access to South"]}
```
Errors:
- 404: the customer or the target region is not visible.
- 403: no CUSTOMER_MANAGE in the source or the target.
- 400: the target is inactive or the same as the source.
- 400: "Acme Ltd has 2 changes awaiting approval; they must be decided first", when changes are pending and the caller lacks `CHANGE_APPROVE` in the source region (BE-15). With that right they are closed and counted in `moved.other.PendingChangeMoveParticipant`.
- 409: stale `version`.
- 400 `AS_OF_READ_ONLY`.

### 6.5 Record DTO additions

All additions are nullable, with `@JsonInclude(NON_NULL)`.

| DTO (endpoints) | Added fields |
|---|---|
| `CustomerDto` (customers list and detail) | `regionId`, `regionCode`, `regionName`, `canManage`, `canMoveRegion` |
| `InvoiceSummary` (list), `InvoiceDto` (detail and writes) | `regionId`, `regionCode`, `regionName`, `canManage`, `pendingApproval` (list only) |
| `PaymentDto` | same, plus `pendingApproval` (list only) |
| `PromiseDto` | same, plus `canOverride`, `pendingApproval` (list only), `assignees` (detail only: `AssigneeDto[]`) |
| `DisputeDto` | `regionId`, `regionCode`, `regionName`, `canResolve`, `internal`, `origin`, `ruleId`, `pendingApproval` (list only), `assignees` (detail only). `targetType` may be `CUSTOMER`. |
| `DocumentDto` | unchanged shape; `canEdit`/`canDelete` become region-aware and false when historical |

- `AssigneeDto` = `{"token":EmailToken,"label":"Collection POC (customer)","people":[{"userId":5,"name":"Cara","primary":true}],"unresolved":false}`.
- New list columns:
  - `regionId` (REFERENCE kind `region`) and `regionName` (TEXT, sortable) on the customers, invoices, payments, promises and disputes schemas;
  - `pendingApproval` (BOOLEAN, `eq` only) on invoices, payments, promises and disputes;
  - `assigneeUserId` (REFERENCE kind `staffUser`) on promises and disputes;
  - `internal` on disputes;
  - `paymentTerm` (ENUM) on invoices and customers, and `overdueAmount` (MONEY) on customers (BE-16; §5.10.2).

### 6.6 Writes that may be held (B2)

| Endpoint | Held when |
|---|---|
| `POST /api/invoices` | Σ lines > threshold `INVOICE_CREATE` |
| `PATCH /api/invoices/{id}` | Due date moved later on a balance > `INVOICE_TERMS` |
| `POST /api/invoices/{id}/cancel` | total > `INVOICE_CANCEL` |
| `POST /api/payments` | amount > `PAYMENT_RECORD` |
| `POST /api/promises` | amount > `PROMISE_CREATE` |
| `PUT /api/promises/{id}` | measure > `PROMISE_UPDATE` |
| `POST /api/promises/{id}/override` | unfulfilled > `PROMISE_OVERRIDE` |
| `POST /api/disputes/{id}/approve` | measure > `DISPUTE_APPROVE`, and the approver is not the checker step |

- "Held when" also covers the cumulative window (the same maker's recent unapproved changes to the same customer push the total over the threshold) and, during a move cool-down, the stricter of the two regions' thresholds (§5.11.3).
- Applied: 200 with today's DTO, unchanged.
- **Held: 202**, `Location: /api/approvals/41`. **There is no top-level `id`.**
```json
{"status":"PENDING_APPROVAL",
 "message":"Above South's approval limit of ₹1,00,000.00 for Cancel invoice. It takes effect once someone else approves it.",
 "pendingChange":{"id":41,"status":"PENDING","operation":"INVOICE_CANCEL","operationLabel":"Cancel invoice",
   "entityType":"INVOICE","entityId":1203,"entityLabel":"INV-20260926-0003","customerId":77,"customerName":"Acme Ltd",
   "regionId":2,"regionName":"South","amount":120000.00,"threshold":100000.00,
   "summary":"Cancel invoice INV-20260926-0003 (₹1,20,000.00)","requestedAt":"2026-09-26T09:14:03.120Z",
   "requestedBy":{"id":9,"name":"Sam Sales"},"origin":"USER","approverCount":3,"supersededId":null,"canWithdraw":true}}
```
- Creates held this way have `entityId: null`.
- 409 `ApprovalConflictException` "INV-… already has a change awaiting approval (#40, Extend invoice due date by Sam). It must be approved, rejected or withdrawn first."
- `CreatePromiseRequest` gains optional `"assignees":[EmailToken]` in BE-14; **from BE-19** it is applied on create, and on approval when held. Before BE-19 the field is accepted and ignored.
- A held change's summary says when the hold came from the cumulative window: "Record payment of ₹60,000.00 for Acme Ltd; with your other changes to Acme Ltd in the last 24 hours this comes to ₹1,10,000.00".

### 6.7 Bulk

`BulkResult` gains a trailing `"pending":[{"id":1203,"pendingChangeId":41,"summary":"Cancel invoice INV-…"}]`. It is always present, and is `[]` except for invoice bulk CANCEL. All bulk and export POSTs honour `regionIds` from the query string.

### 6.8 POC endpoints

**`GET /api/pocs/assignable?type=SALES&q=&limit=25[&customerId=12 | &regionId=2]`**
- With `customerId`, the region comes from the customer (404 if not visible).
- With `regionId`, it must be granted; otherwise the response is `[]`.
- With neither, the selected regions are used.
- The response is `List<PocUserDto>`, unchanged.

**`GET /api/pocs/my-scope`**
```json
{"userId":7,"clearable":false,"sales":true,"success":false,"collection":true,
 "regions":[{"regionId":1,"code":"NORTH","clearable":true,"sales":true,"success":false,"collection":true}]}
```

**`GET /api/invoices/assignable-check[?customerId=12]`** → `{"sales":true}`. With `customerId`, it answers for that customer's region.

### 6.9 Dashboard (AWARE; honours `regionIds`)

- Every response gains `"scope":{"regionIds":[1,2],"bookRegionIds":[2],"asOf":null}`.
- `coverage` may be `"MIXED"`.
- In historical mode, "today" for the ageing buckets, `dueDateFrom`/`dueDateTo` and D8 is the as-of date.

### 6.10 History

- **`GET /api/history/range`** (any authenticated user; IGNORED) → `{"historyStartsOn":"2026-10-01","latestAsOf":"2026-09-25","timezone":"UTC"}`.
  - `historyStartsOn: null` means there is no lower bound.
  - `latestAsOf` = today (UTC) − 1.
- **`POST /api/admin/history/reconcile`** (USER_MANAGE) → `{"checked":1840,"missing":0,"drifted":0,"orphaned":0,"duplicates":0,"linkDrift":0,"repaired":0}`.

### 6.11 Approvals and thresholds

| Endpoint | Guard | Response |
|---|---|---|
| `GET /api/approvals?page&size&sort&filter=…&view=ALL\|AWAITING_ME\|MINE` (default ALL) | authenticated staff (customers 403); rows limited by visibility | `PageResponse<Brief>` |
| `GET /api/approvals/summary` | same | `{"pendingCount":7,"pendingAmount":950000.00,"awaitingMeCount":3,"mineCount":2}` |
| `GET /api/approvals/awaiting-me/count` | same | `{"count":3}` |
| `GET /api/approvals/for-record?entityType=INVOICE\|PAYMENT\|PROMISE\|DISPUTE\|CUSTOMER&entityId=42` | the record's scoped `get` (404 otherwise); CUSTOMER is staff-only | `Brief[]`: PENDING rows (outstanding at T when historical). INVOICE, PAYMENT and PROMISE match `affected_*`; DISPUTE matches `entity_*`; CUSTOMER returns that customer's pending creates. |
| `GET /api/approvals/operations` | authenticated | `[{"operation":"INVOICE_CREATE","label":"Create invoice","entityType":"INVOICE","measure":"Invoice total"}, …]` |
| `GET /api/approvals/{id}` | visibility (404) | `Detail` |
| `POST /api/approvals/{id}/approve` `{"note":"optional"}` | `CHANGE_APPROVE` somewhere, plus §5.11.6 | 200 `Detail` with `status: APPROVED` (`result:{"entityType":"INVOICE","entityId":913,"link":"/invoices/913"}`) **or** `status: FAILED` (`failureReason`). 409 when locked or already decided. 400 self-approval. 403 no right in the region. 404 not visible. |
| `POST /api/approvals/{id}/reject` `{"note":"required"}` | same | 200 `Detail` REJECTED. 400 `fieldErrors.note`. 400 "Use Withdraw for your own request". |
| `POST /api/approvals/{id}/withdraw` | the maker | 200 `Detail` WITHDRAWN. 403 otherwise. |
| `POST /api/approvals/export` (BulkRequest body) | EXPORT_DATA + visibility | CSV |

`Brief` = `{"id","status","statusAt","operation","operationLabel","entityType","entityId","entityLabel","customerId","customerName","regionId","regionName","amount","threshold","summary","requestedAt","requestedBy":{"id","name"},"origin","decidedAt"}`. `statusAt` is non-null only when historical. The `approvals` schema's column keys are these keys, with `requestedByUserId` filtering on `requestedBy.id` (§5.11.9). `entityType` may be `REGION` (THRESHOLD_CHANGE; `customerId` and `customerName` null).

`Detail`:
```json
{"id":41,"status":"PENDING","statusAt":null,"operation":"INVOICE_TERMS","operationLabel":"Extend invoice due date",
 "entityType":"INVOICE","entityId":1203,"entityLabel":"INV-20260926-0003","affectedType":"INVOICE","affectedId":1203,
 "customerId":77,"customerName":"Acme Ltd","regionId":2,"regionName":"South",
 "amount":120000.00,"threshold":100000.00,"measureLabel":"Balance whose due date moves later",
 "summary":"Extend due date of INV-20260926-0003 from 2026-10-26 to 2026-12-31 on ₹1,20,000.00 outstanding",
 "changes":[{"field":"dueDate","label":"Due date","current":"2026-10-26","proposed":"2026-12-31","changedSinceRequest":false}],
 "current":{"dueDate":"2026-10-26","paymentTerm":"NET_30"},"proposed":{"dueDate":"2026-12-31","paymentTerm":"CUSTOM"},
 "requestedBy":{"id":9,"name":"Sam Sales"},"requestedAt":"…","origin":"USER","ruleId":null,
 "decidedBy":null,"decidedAt":null,"decisionNote":null,"failureReason":null,"supersededById":null,"result":null,
 "approverCount":3,"canApprove":true,"canReject":true,"canWithdraw":false,"blockedReason":null}
```

- `blockedReason` is one of: "You requested this change", "You don't hold the approval right in South", "Read-only in the as-of view", or null.
- For customer logins, `requestedBy`, `decidedBy`, `decisionNote` and `origin` are null, and `*poc*` keys are stripped.

**Thresholds.** `GET /api/regions/{regionId}/approval-thresholds` requires `APPROVAL_THRESHOLD_MANAGE` or `CHANGE_APPROVE` in the region (403 otherwise, 404 for an unknown region):
```json
{"regionId":2,"regionName":"South","canEdit":true,"activeApprovers":3,"pendingChange":null,
 "thresholds":[{"operation":"INVOICE_CREATE","label":"Create invoice","measure":"Invoice total","amount":100000.00,"windowHours":24,
                "updatedAt":"2026-09-20T08:00:00Z","updatedBy":{"id":1,"name":"System Administrator"}},
               {"operation":"INVOICE_CANCEL","label":"Cancel invoice","measure":"Invoice total","amount":null,"windowHours":null,"updatedAt":null,"updatedBy":null}]}
```
- All 8 amount-gated operations are always listed; `amount: null` means not gated. `pendingChange` is the region's outstanding THRESHOLD_CHANGE `Brief`, if any.

`PUT` with the same guard, plus `APPROVAL_THRESHOLD_MANAGE` in the region:
- Body `{"thresholds":[{"operation":"INVOICE_CREATE","amount":100000.00,"windowHours":24},{"operation":"PAYMENT_RECORD","amount":null}]}`. `windowHours` is optional (default 24 on a new row; unchanged otherwise), 0..720.
- Listed operations are upserted; a null amount deletes; unlisted operations are unchanged.
- **Tightening** rows apply at once (200 with the GET shape). **Loosening** rows (higher amount, shorter window, deletion) become one THRESHOLD_CHANGE pending change: **202** with the GET shape (showing the applied rows) plus `"pendingChange": Brief` and `Location: /api/approvals/{id}` (§5.11.11).
- 400 `fieldErrors["thresholds[i].operation" | "thresholds[i].amount" | "thresholds[i].windowHours"]`. 409 when another person's THRESHOLD_CHANGE is outstanding for the region.
- The response adds `"warnings":["Only 1 person can approve changes in South, and nobody can approve their own changes"]` when relevant.

### 6.12 Tasks (`/api/tasks`)

| Method, path | Gate | Notes |
|---|---|---|
| `GET /api/tasks?page&size&sort&filter[&entityType&entityId][&customerId]` | TASK_VIEW | Schema `tasks`. The context parameters become filter chips. |
| `GET /api/tasks/summary` | TASK_VIEW | `{"count":12,"open":5,"inProgress":2,"overdue":3,"dueToday":1}` |
| `GET /api/tasks/mine`, `/mine/summary` | TASK_VIEW | Scope `forTasks` ∧ assigned to me. **`lockedFilters: []`.** `/mine/summary` has the `/summary` shape: `{"count","open","inProgress","overdue","dueToday"}`. |
| `GET /api/tasks/mine/count` | TASK_VIEW | `{"count":4}` (OPEN and IN_PROGRESS) |
| `GET /api/tasks/count?entityType&entityId` | TASK_VIEW | `{"count":3,"open":2}` |
| `GET /api/tasks/{id}` | TASK_VIEW | `TaskDto` |
| `POST /api/tasks` | TASK_MANAGE in the record's region | `{"entityType":"INVOICE","entityId":77,"title":"…","description":null,"dueDate":"2026-09-28","assignees":[EmailToken]}` → `TaskDto`. 400 `fieldErrors.assignees` when a user cannot see the record. |
| `PATCH /api/tasks/{id}` | TASK_MANAGE | `{"title"?,"description"?,"dueDate"?,"clearDueDate"?,"assignees"?,"version":3}` → `TaskDto`; 409 stale |
| `POST /api/tasks/{id}/status` | TASK_MANAGE, or an effective assignee | `{"status":"DONE","version":3}` → `TaskDto`; 400 same status; 403 |
| `POST /api/tasks/bulk` | TASK_MANAGE | actions `START`, `MARK_DONE`, `REOPEN`, `CANCEL` → `BulkResult` |
| `POST /api/tasks/export` (AWARE) | EXPORT_DATA and TASK_VIEW | CSV |

`TaskDto`:
```json
{"id":41,"title":"Chase INV-…","description":null,"status":"OPEN","dueDate":"2026-09-28","overdue":false,
 "entityType":"INVOICE","entityId":77,"entityLabel":"Invoice INV-…","entityDeleted":false,"customerId":5,"customerName":"Acme Ltd",
 "regionId":1,"regionCode":"NORTH","regionName":"North",
 "assignees":[{"token":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},"label":"Collection POC (customer)",
               "people":[{"userId":5,"name":"Cara Collections","primary":true}],"unresolved":false}],
 "assignedToMe":true,"origin":"AUTOMATION","rule":{"id":12,"name":"Chase overdue invoices"},
 "createdBy":{"id":5,"name":"Cara Collections"},"createdAt":"…","updatedAt":"…","completedAt":null,"version":0,
 "canEdit":true,"canChangeStatus":true}
```
In historical mode `version` is null and both flags are false.

### 6.13 Assignees and assignment on disputes and promises

| Method, path | Gate |
|---|---|
| `GET /api/assignees/context?entityType=&entityId=` | Any of TASK_MANAGE, AUTOMATION_MANAGE, DISPUTE_MANAGE, PROMISE_MANAGE; staff only. Returns the **same `ContextDto`** as `/api/emails/context` (role groups by level, holders, `sender`). Without `entityId` it returns the type-level roles. |
| `GET /api/assignees/people?q=[&entityType=&entityId=]` | Any of TASK_VIEW, AUTOMATION_VIEW, DISPUTE_MANAGE, PROMISE_MANAGE; staff only → `EmailDtos.PeopleDto[]` (`{"userId","name","username","email","gmail"}`), at most 20. **Region-scoped** (critic finding SC-18): active staff holding any grant in `ViewScope.narrow(rights.grantedRegions())`, or in the record's region when `entityType`/`entityId` are given (region from the scoped `get`, 404 if not visible), via `UserRegionGrantRepository.findStaffInRegions`. It never calls the global `EmailDirectory.searchPeople` (R17). |
| `PUT /api/disputes/{id}/assignees` | DISPUTE_MANAGE (region) — `{"assignees":[EmailToken]}` → `DisputeDto`; 400 `fieldErrors.assignees` for a person who cannot see the dispute; audit `DISPUTE_ASSIGNEES_CHANGED`; `DISPUTE_ASSIGNED` to newly resolved people |
| `PUT /api/promises/{id}/assignees` | PROMISE_MANAGE (region) — same → `PromiseDto`; audit `PROMISE_ASSIGNEES_CHANGED`; `PROMISE_ASSIGNED` |

### 6.14 Automation (`/api/automation`)

| Method, path | Gate | Body → response |
|---|---|---|
| `GET /rules` | AUTOMATION_VIEW; rows limited by `RuleVisibility` and narrowed by `regionIds` (§5.10.10) | `PageResponse<RuleSummary>`; schema `automation-rules`: `id`, `name`, `enabled`, `status` (ACTIVE, DISABLED, SUSPENDED), `suspendedReason`, `triggerKind`, `entityType`, `ownerUserId` (REF `staffUser`), `regionIds` (REF `region`, filter `in`, not sortable), `lastRunStatus`, `lastRunAt`, `nextRunAt`, `createdAt`, `updatedAt`. Historical: §5.10.10. |
| `GET /rules/summary` | same visibility and narrowing | `{"active":4,"disabled":1,"suspended":1,"failedRuns24h":2}` |
| `GET /rules/{id}` | AUTOMATION_VIEW, rule visible (404 otherwise) | `RuleDto` |
| `POST /rules` | AUTOMATION_MANAGE | `RuleRequest` → `RuleDto` |
| `PUT /rules/{id}` | AUTOMATION_MANAGE (owner), or AUTOMATION_ADMIN with confirmation | `RuleRequest` → `RuleDto`; 409 stale; 400 `fieldErrors.confirmOwnershipChange` |
| `POST /rules/{id}/disable` | owner or AUTOMATION_ADMIN | → `RuleDto` |
| `POST /rules/{id}/enable` `{"confirmOwnershipChange"?:bool}` | owner; a non-owner AUTOMATION_ADMIN takes ownership (§5.10.7) | → `RuleDto`; 400 `fieldErrors.confirmOwnershipChange` |
| `POST /rules/{id}/resume` `{"replay"?:bool,"confirmOwnershipChange"?:bool}` | as enable | → `RuleDto` plus `"skippedWhileSuspended":12` |
| `DELETE /rules/{id}` | owner or AUTOMATION_ADMIN | 204 (soft delete) |
| `POST /rules/{id}/run` `{"recordIds"?:[…≤50],"runAt"?:instant,"confirmOwnershipChange"?:bool}` | as enable; the rule may be disabled but not suspended | 202 `{"runId":88,"eventId":9031,"status":"QUEUED","scheduledFor":"…"}`, or 200 with the same shape when an identical request is already queued (§5.10.9). 400 `fieldErrors.recordIds` / `.runAt`. |
| `POST /rules/preview` (AWARE) | AUTOMATION_VIEW | `{"rule":RuleRequest,"ruleId":12?,"recordId":77?,"sampleSize":10,"evaluateAs":"CALLER"}` → `PreviewDto` |
| `GET /rules/{id}/preview?recordId=&sampleSize=&evaluateAs=CALLER\|OWNER` | AUTOMATION_VIEW, rule visible; `OWNER` needs owner or AUTOMATION_ADMIN (403) | → `PreviewDto` (under a bound point: the revision at T, 404 if none) |
| `GET /rules/{id}/matches?page&size&evaluateAs=` | as preview | `PageResponse<{"id","label","link"}>`, or `{"hidden":true}` items for records only the owner can see |
| `GET /placeholders?entityType=` | AUTOMATION_VIEW, TASK_MANAGE or EMAIL_SEND | catalogue |
| `GET /rules/{id}/runs` | AUTOMATION_VIEW, rule visible | `PageResponse<RunDto>`; schema `automation-runs`: `id`, `status` ENUM, `triggerType` ENUM, `entityType`, `entityId`, `scheduledFor` DATE, `matched`, `done`, `skipped`, `failed` (NUMBER), `truncated` BOOLEAN, `createdAt`, `startedAt`, `finishedAt` DATE; default sort `createdAt,desc` |
| `GET /runs/{id}` | AUTOMATION_VIEW, rule visible | `RunDto` (redacted per viewer, §5.10.10) |
| `GET /runs/{id}/effects?page&size` | AUTOMATION_VIEW, rule visible | `PageResponse<EffectDto>`; targets outside the **viewer's** scope come back redacted (§5.10.10) |
| `POST /runs/{id}/retry` | owner or AUTOMATION_ADMIN (a MANUAL run by a non-owner needs `confirmOwnershipChange`) | FAILED run with a DEAD event → 202 `{"runId","eventId","status":"QUEUED"}`; otherwise 400 |
| `GET /health` | AUTOMATION_ADMIN | `{"consumerEnabled":true,"pending":17,"processing":1,"retrying":2,"dead":0,"oldestPendingSeconds":3,"deadEvents":[{"id":9,"kind":"UPDATED","entityType":"INVOICE","entityId":7,"ruleId":null,"attempts":5,"lastError":"…","updatedAt":"…"}]}` |
| `POST /events/{id}/requeue` | AUTOMATION_ADMIN | 202 |

`RuleSummary` = `{"id","name","enabled","status","suspendedReason","triggerKind","entityType","owner":{"id","name","active"},"regionIds":[1],"lastRunStatus","lastRunAt","nextRunAt","createdAt","updatedAt"}`.

`RuleRequest`:
```json
{"name":"Chase overdue invoices","description":null,"enabled":true,
 "trigger":{"kind":"SCHEDULE_DAILY","entityType":"INVOICE","time":"09:00","day":null,"zone":"+05:30","catchUp":"ONE"},
 "regionIds":[2],
 "condition":{"op":"AND","children":[{"filter":"overdue:eq:true"}]},
 "actions":[
  {"key":"5b0c7f0e-…","type":"CREATE_TASK","repeat":"EVERY_TIME","title":"Chase {{Invoice.Number}} ({{Invoice.Balance}})",
   "description":"{{Customer.Name}} is {{Invoice.DaysOverdue}} days late","due":{"base":"TODAY","offsetDays":2},
   "assignees":[{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},{"type":"USER","userId":7}],"onlyIfNoOpenTask":true},
  {"key":"c3d1…","type":"CREATE_PROMISE","repeat":"ONCE_PER_RECORD","amount":{"source":"FIELD","field":"Invoice.Balance"},
   "promisedDate":{"base":"TODAY","offsetDays":7},"collectionPoc":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},
   "assignees":[{"type":"ROLE","role":"CUSTOMER_SUCCESS_POC","level":"CUSTOMER"}],"notes":"Auto-created for {{Invoice.Number}}",
   "linkRecord":true,"onlyIfNoOpenPromise":true},
  {"key":"e7f2…","type":"CREATE_DISPUTE","repeat":"ONCE_PER_RECORD","reason":"Review {{Invoice.Number}}","proposedChange":"none",
   "assignees":[{"type":"ROLE","role":"SALES_POC","level":"RECORD"}]},
  {"key":"91aa…","type":"SEND_EMAIL","repeat":"EVERY_TIME","from":{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"},
   "fallbackToOwner":true,"to":[{"type":"CUSTOMER"}],"subject":"Invoice {{Invoice.Number}} is overdue",
   "body":"Dear {{Customer.Name}},\n{{Invoice.Balance}} has been due since {{Invoice.DueDate}}.\n{{Customer.CollectionPoc|Our collections team}}"}],
 "maxRecordsPerRun":1000,"version":3,"confirmOwnershipChange":false}
```

**Field rules**
- `trigger.kind` is `ENTITY_CREATED`, `ENTITY_UPDATED`, `ENTITY_CREATED_OR_UPDATED`, `SCHEDULE_DAILY`, `SCHEDULE_WEEKLY` or `MANUAL` (runs only from "Run now"); `trigger.entityType` is `CUSTOMER`, `INVOICE` or `PAYMENT`.
- `time` matches `^([01]\d|2[0-3]):[0-5]\d$` (schedules). `day` is a `DayOfWeek` (weekly). `zone` must parse with `ZoneId.of`. `catchUp` is `ONE` (default) or `ALL` (schedules only).
- `regionIds` is optional (`[]` or absent = every region where the owner can act); each must be a region where the owner holds every required privilege (400 `fieldErrors.regionIds`).
- Actions: 1..5, with unique keys.
- `due.base` is `TODAY` or `INVOICE_DUE_DATE` (INVOICE rules), with offset −30..365.
- `promisedDate.base` is `TODAY` or `INVOICE_DUE_DATE` (INVOICE rules), with offset 0..365; the result is clamped to ≥ today.
- `amount.source` is `FIXED` (with `value`) or `FIELD` (with `field` one of `Customer.Outstanding`, `Customer.OverdueAmount`, `Invoice.Balance`, `Invoice.Total`, `Payment.Amount`).
- `proposedChange` is `none` (any), `cancel` (INVOICE) or `void` (PAYMENT).
- Email `to` has 1..10 tokens, and `CUSTOMER` is allowed.
- `maxRecordsPerRun` is 1..5000.
- `fieldErrors` paths look like `actions[2].title` or `condition.children[1].filter`.
- The response's `warnings` include the §5.8.3 "not an update" warnings for UPDATED rules.

`RuleDto`:
```json
{"id":12,"name":"Chase overdue invoices","description":null,"enabled":true,"version":3,"status":"ACTIVE",
 "suspendedReason":null,"suspendedDetail":null,"owner":{"id":5,"name":"Cara Collections","active":true},
 "trigger":{…},"regionIds":[2],"condition":{…},"actions":[…],"maxRecordsPerRun":1000,"nextRunAt":"2026-09-27T03:30:00Z",
 "lastRun":{"id":88,"status":"SUCCEEDED","finishedAt":"…","matched":14,"done":14,"skipped":0,"failed":0},
 "warnings":["Cara Collections has not connected Gmail, so emails sent from them will be saved in the app but not sent."],
 "listLink":"/invoices?f=overdue:eq:true","canEdit":true,"editTakesOwnership":false,"canRun":true,"createdAt":"…","updatedAt":"…"}
```
- `status` is ACTIVE, DISABLED or SUSPENDED.
- `canRun` is true for the owner; `editTakesOwnership` is true for a non-owner AUTOMATION_ADMIN, which also means Enable, Resume and Run now take ownership (§5.10.7).
- `listLink` is present only for AND-only trees.
- In historical mode it comes from a revision, and the flags are false.

`PreviewDto`:
```json
{"entityType":"INVOICE","asOf":null,"asOfNote":null,"evaluatedAs":"CALLER","matchCount":132,
 "sample":[{"id":77,"label":"Invoice INV-20260901-0007","link":"/invoices/77","alreadyDone":["5b0c…"]}],
 "record":{"id":77,"label":"Invoice INV-20260901-0007","matches":true,"actions":[
   {"key":"5b0c…","type":"CREATE_TASK","rendered":{"title":"Chase INV-20260901-0007 (₹12,000.00)","dueDate":"2026-09-28"},
    "assignees":[AssigneeDto],"problems":[],"warnings":[]},
   {"key":"91aa…","type":"SEND_EMAIL","email":{ /* existing EmailDtos.PreviewDto */ },"rendered":{"subject":"…","body":"…"},
    "problems":[],"warnings":["Email delivery is not configured, so this email will be saved in the app but not sent."]}]}}
```
In historical mode `asOf` is `"2026-06-30"`, `record.actions` is null, and `asOfNote` is `"Actions are shown only for today's view"`. `evaluatedAs` is `CALLER` or `OWNER`; with `OWNER`, sample items the caller cannot see are `{"hidden":true}` and `record` is null for such a record.

`RunDto`:
```json
{"id":88,"ruleId":12,"ruleVersion":3,"eventId":9031,"triggerType":"SCHEDULED","status":"PARTIAL","statusAt":null,"scheduledFor":"…","lateBySeconds":0,
 "entity":null,"requestedBy":null,"matched":14,"done":12,"skipped":1,"failed":1,"partial":false,"truncated":false,"message":null,
 "createdAt":"…","startedAt":"…","finishedAt":"…"}
```
- `entity` (CREATED, UPDATED and per-record REPLAY runs; CHILDREN runs carry the customer): `{"type":"INVOICE","id":77,"label":"Invoice INV-…","link":"/invoices/77","hidden":false}`, or `{"type":"INVOICE","hidden":true}` for a viewer who cannot see it (then `message` is null). Null for SCHEDULED and MANUAL runs.
- `requestedBy` (MANUAL and REPLAY runs): `{"id":5,"name":"Cara Collections"}`; otherwise null.
- `partial: true` means the counters count only effects on records this viewer can see (§5.10.10). `statusAt` is set only in historical mode.

`EffectDto`:
```json
{"id":901,"actionKey":"5b0c…","actionType":"CREATE_TASK",
 "target":{"type":"INVOICE","id":77,"label":"Invoice INV-…","link":"/invoices/77","hidden":false},
 "status":"DONE","result":{"type":"TASK","id":41,"link":"/tasks/41"},"message":null,"warnings":[],"createdAt":"…"}
```
`result.type` may be `PENDING_CHANGE`, with link `/approvals/{id}`. For a target the viewer cannot see: `{"id":902,"actionKey":"5b0c…","actionType":"CREATE_TASK","target":{"type":"INVOICE","hidden":true},"status":"DONE","result":null,"message":null,"warnings":null,"createdAt":"…"}`.

Placeholder catalogue:
```json
{"entityType":"INVOICE","groups":[
 {"level":"CUSTOMER","label":"Customer","items":[{"token":"{{Customer.Name}}","label":"Name","kind":"TEXT"}]},
 {"level":"RECORD","label":"Invoice","items":[{"token":"{{Invoice.Balance}}","label":"Balance","kind":"MONEY"}]},
 {"level":"COMMON","label":"General","items":[{"token":"{{Today}}","label":"Today","kind":"DATE"}]}]}
```

### 6.15 Audit (`GET /api/audit?entityType&entityId&includeRelated`)

- `entityType` also accepts `TASK` (TASK_VIEW), `RULE` (AUTOMATION_VIEW) and `REGION` (REGION_VIEW).
- `AuditEntryDto` gains `origin`, `ruleId`, `pendingChangeId`, `pendingStatus`, `approvedByUserId` and `approvedByUsername`.
- New actions:
  - `CHANGE_REQUESTED`, `CHANGE_SUPERSEDED`, `CHANGE_APPROVED`, `CHANGE_REJECTED`, `CHANGE_WITHDRAWN`, `CHANGE_APPLY_FAILED`, `CHANGE_CLOSED`
  - `CUSTOMER_REGION_CHANGED`, `USER_REGION_GRANTS_CHANGED`
  - `REGION_CREATED`, `REGION_UPDATED`, `REGION_DEFAULT_CHANGED`, `REGION_DELETED`, `APPROVAL_THRESHOLDS_CHANGED`
  - `TASK_CREATED`, `TASK_UPDATED`, `TASK_STATUS_CHANGED`, `TASK_ASSIGNEES_CHANGED`, `TASK_CANCELLED_WITH_RECORD`
  - `RULE_CREATED`, `RULE_UPDATED`, `RULE_ENABLED`, `RULE_DISABLED`, `RULE_DELETED`, `RULE_SUSPENDED`, `RULE_RESUMED`, `RULE_OWNERSHIP_TAKEN`, `RULE_RUN_REQUESTED`
  - `DISPUTE_ASSIGNEES_CHANGED`, `PROMISE_ASSIGNEES_CHANGED`
- Historical: entries with `createdAt <= p`.

### 6.16 Table schemas (`GET /api/table-schemas/{entity}`)

- New entities: `regions`, `tasks`, `automation-rules`, `automation-runs`, `approvals`.
- New reference kinds for `ColumnDto.referenceKind`:
  - `region` (options: the caller's `/me` regions);
  - `staffUser` (search `/api/assignees/people`).

### 6.17 Error code catalogue (`ApiError.error` when coded)

| Code | Status | Message |
|---|---|---|
| `AS_OF_INVALID` | 400 | "asOf must be a date (yyyy-MM-dd)" |
| `AS_OF_FUTURE` | 400 | "asOf cannot be after today" |
| `AS_OF_BEFORE_HISTORY` | 400 | "History is available from 1 Oct 2026" |
| `AS_OF_READ_ONLY` | 400 | "You are viewing 30 Jun 2026. Changes can only be made in the current view." |
| `AS_OF_UNSUPPORTED` | 400 | "This page can't be shown as of a date" |
| `REGION_IDS_INVALID` | 400 | "regionIds must be a comma-separated list of region ids" |

---

## 7. Frontend design (`F/`)

**Constraints**
- No new packages.
- `flutter analyze` stays at 0.
- The 61 existing tests stay green. The only edits allowed to existing test files are additive helper changes in `FT/support/fake_backend.dart`, and at most a `ProviderScope` override in `list_columns_test.dart` if one proves necessary, with no assertion changes (§7.3.4).
- All new JSON fields are parsed as nullable (G10).
- New enums use UPPER wire names with a fallback parser (G25).
- New shared models must not import feature providers in a cycle (G28).

### 7.1 View scope core (`F/core/scope/`)

**`view_scope.dart`**
- The model:
  ```dart
  @immutable class ViewScope {
    final Set<int>? regionIds;   // null = all my regions
    final DateTime? asOf;        // a local calendar date; null = live
    Map<String, String> get apiParams => {
      if (regionIds != null) 'regionIds': (regionIds!.toList()..sort()).join(','),
      if (asOf != null) 'asOf': DateFormat('yyyy-MM-dd').format(asOf!),   // G21: never toUtc()
    };
    // == and hashCode over the canonical string
  }
  ```
- `viewScopeProvider = StateNotifierProvider<ViewScopeController, ViewScope>`, with `setRegions(Set<int>?)`, `setAsOf(DateTime?)` and `clear()`. It lives in session memory only.
- `AuthController.logout` (`auth_controller.dart:63-68`) calls `clear()`. The controller also clears itself when the signed-in user id changes.
- `isHistoricalProvider = Provider<bool>((ref) => ref.watch(viewScopeProvider).asOf != null)`.
- `InterceptorsWrapper viewScopeInterceptor(ViewScope Function() current)` runs `options.queryParameters.putIfAbsent(k, () => v)` for each `current().apiParams` entry on **every** request, including bulk and export POSTs (G2). `dioProvider` passes `() => ref.read(viewScopeProvider)`. It is exported so tests can place it ahead of the fake routing with `FakeBackend.dioWith([viewScopeInterceptor(() => scope)])` (§7.8).

**`F/core/api/api_client.dart`**: `dio.interceptors.add(viewScopeInterceptor(() => ref.read(viewScopeProvider)))`, placed before the auth interceptor.

**`as_of.dart`**
- `historyRangeProvider` is a `FutureProvider.autoDispose` over `GET /api/history/range`. It is only read when the picker opens (G13).
- `enum AsOfApplied { live, historical }`.
- `AsOfApplied checkAsOf(Response r, DateTime? asOf)`: when `asOf != null`, the `x-view-as-of` header must equal the formatted date (historical) or `live`. Anything else throws `AsOfMismatch("This screen can't show history")`.

**Refetch and verification wiring.** These providers call `ref.watch(viewScopeProvider)` so they refetch on change, and call `checkAsOf` on the response:
- `tablePageProvider`, `tableSummaryProvider` (`F/core/table/table_providers.dart:56-72`)
- the 8 dashboard providers (`dashboard_providers.dart:159-234`)
- `customerDetailProvider`, `invoiceDetailProvider`, `paymentDetailProvider`, `promiseDetailProvider`, `disputeDetailProvider`
- `scopedPromisesProvider`, `scopedDisputesProvider`, `entityDocumentsProvider`, `documentCountProvider`, `entityEmailsProvider`, `auditHistoryProvider`
- the new task, approval and rule providers

These watch the scope for regions only, with no as-of check, because they answer live:
- `myPocScopeProvider` (it becomes autoDispose, G22), `assignablePocsProvider`, `referenceOptionsProvider`
- the notifications list and bell count, and the inbox list and `inboxUnreadCountProvider` (they honour `regionIds`, §5.6.7). `pollUnreadCount` restarts its timer when the scope changes.

Other rules:
- **No collection goes into `TableRequest.extra`** (G1).
- `tableSchemaProvider` is unchanged; schemas do not depend on the scope.

**`F/core/table/table_models.dart`**: `PagedResult` gains `regionIds`, `bookRegionIds` (default `const []`) and `asOf` (`String?`).

### 7.2 Models

**`F/shared/models/auth_models.dart`**
- `CurrentUser` gains `final List<RegionAccess> regions` (default `const []`, so the all-required const constructor still compiles, G9).
- `RegionAccess {int id; String code; String name; bool active; bool defaultRegion; String? level; String? scope; Set<String> privileges}`.
- `bool hasIn(String p, int? regionId)`: when `regionId == null` or `regions.isEmpty`, it returns `has(p)`; otherwise that region's privileges contain `p`, or false if the region is absent.
- `Set<int> regionsWith(String p)`, `bool get multiRegion => regions.length > 1`.
- `LoginResult` and `/me` parsing read `regions`.

**`F/shared/models/privileges.dart`**: the nine constants (§4.8).

**Entity models** (`customer.dart`, `invoice.dart`, `payment.dart`, `promise.dart`, `dispute.dart`): nullable `regionId`, `regionCode`, `regionName`, `canManage`, `canMoveRegion`, `canOverride`, `canResolve`, `pendingApproval`, `internal`, `origin`, `ruleId` and `assignees`.
- `DisputeTargetType` gains `CUSTOMER` with its label.
- **`dispute_create_dialog.dart` must offer only INVOICE and PAYMENT.** Replace any `DisputeTargetType.values` iteration with an explicit list.

**`F/features/dashboard/dashboard_providers.dart`**: `Coverage` gains `mixed` (the `'MIXED'` wire value); unknown values stay `all`. Parse `scope`.

### 7.3 App shell, navigation and routes

#### 7.3.1 AppShell actions (`F/shared/widgets/app_shell.dart:88-91`, before the bell)

**Region selector (FE-02)**
- A `PopupMenuButton` of checkboxes over `user.regions`, plus "All my regions". The label is "All regions" or the chosen codes.
- It is hidden for customer logins and when `regions.length <= 1`.
- It fetches nothing.
- Below 600 px it collapses to an icon button.

**As-of chip (FE-02)**
- Staff only. It reads "Today" or "As of 30 Jun 2026", with a clear ×.
- Tapping it reads `historyRangeProvider`, then opens `showDatePicker(firstDate: historyStartsOn ?? DateTime(2000), lastDate: latestAsOf)`. **Today is never sent.**
- Below 600 px it collapses to an icon.

**Body banner**, when historical: wrap `child` in `Column([banner, Expanded(child)])`. The text is "Viewing 30 Jun 2026 (end of day, UTC) — read only", with the action "Back to today".

#### 7.3.2 Nav entries and badges

- `NavEntry.unreadBadge: bool` is replaced by `final NavBadge badge` with `enum NavBadge { none, inbox, myTasks, approvals }` (the list is `const`). Inbox uses `NavBadge.inbox`.
- `_NavIcon` switches on the badge:
  - `inbox` → `inboxUnreadCountProvider`
  - `myTasks` → `myTasksCountProvider`
  - `approvals` → `approvalsAwaitingMeCountProvider`
- New entries, after Disputes, in this order. No two paths share a prefix (G15).

| Label | Path | requiredAnyOf | Flags |
|---|---|---|---|
| My tasks | `/my-tasks` | taskView, taskManage | `hideForCustomer`, badge `myTasks` (FE-06) |
| Tasks | `/tasks` | taskView, taskManage | `hideForCustomer` (FE-06) |
| Approvals | `/approvals` | changeApprove, invoiceView, paymentView, promiseView, disputeView | `hideForCustomer`, badge `approvals` (FE-04) |
| Automation | `/automation` | automationView, automationManage | `hideForCustomer` (FE-07a) |
| Regions | `/regions` | regionView | `hideForCustomer` (FE-03) |

#### 7.3.3 Routes (`F/core/router.dart`)

- Lists use `needs(navPrivilegesFor(path))` + `RouteQuery.read`.
- Non-nav routes use an explicit `needs(const [...])` (G16).

| Route | Guard |
|---|---|
| `/my-tasks`, `/tasks` | `needs(navPrivilegesFor(...))`; `RouteQuery.read(defaultSort: 'dueDate,asc')` |
| `/tasks/:id` | `onExit: mayLeave`, `pageForId` |
| `/approvals` | `needs(navPrivilegesFor('/approvals'))` |
| `/approvals/:id` | `pageForId` |
| `/approval-thresholds` | `needs(const [Privileges.approvalThresholdManage, Privileges.changeApprove])` |
| `/automation` | nav guard |
| `/automation/new` | `needs(const [Privileges.automationManage])` |
| `/automation/:id` | `needs(const [Privileges.automationView])`, `onExit: mayLeave` |
| `/regions` | nav guard |
| `/regions/:id` | `needs(const [Privileges.regionView])` |

**The router does not watch `viewScopeProvider`** (G4).

#### 7.3.4 Keeping `list_columns_test` green without edits

- Its user has invoice and dispute privileges, so the Approvals entry is visible.
- `approvalsAwaitingMeCountProvider` therefore returns `Stream.value(0)` **without any request** unless `user.has(changeApprove)`. The test user lacks it.
- `myTasksCountProvider` returns `Stream.empty()` unless `user.has(taskView)`.
- AppShell adds no provider that fetches at build time.
- If a layout assertion still changes, the permitted fix is a `ProviderScope` override in that test, with no assertion change. Record it in the slice report.

### 7.4 Read-only (historical) mode

**Every mutating control ANDs `!ref.watch(isHistoricalProvider)`.** The folded providers:
- `canSendEmailProvider`, `canAssignPocProvider`, `canManageDocumentsProvider`, `canUploadDocumentsProvider`
- `canManageTasksProvider`, and the rules and approvals editors

Per-screen sites:
- `customer_detail_screen.dart:195`, `invoice_detail_screen.dart:166`, `payment_detail_screen.dart:120`
- the list `canManage` values in `customers_screen.dart:49`, `invoices_screen.dart:36`, `payments_screen.dart:36`, `promises_screen.dart:28`
- `promises_tab.dart:58-59`, `disputes_tab.dart:30-31`, `dispute_detail_screen.dart:123`
- the users, roles, regions, threshold and rule editors

Other behaviour:
- `DataTableScaffold` hides bulk actions while historical; export stays available.
- When historical and `result.asOf == null`, it shows the strip "Showing current data — history isn't kept for this list".
- **Errors.** `ApiError.error` values starting `AS_OF_` render `apiErrorMessage`. A detail 404 while historical renders `RecordUnavailable("This record did not exist on 30 Jun 2026")`.
- **Dashboard.** The header copy reads "as of 30 Jun 2026". `asOf ?? todayUtc()` replaces `todayUtc()` at `dashboard_providers.dart:215-224`, `dashboard_charts.dart:562-588`, `dashboard_tables.dart:116,203` and `dashboard_screen.dart:141-157`.

### 7.5 Region-aware gating (FE-03)

- A record's own edit, cancel and delete controls become `x.canManage ?? user.has(<xManage>)`.
- Other MANAGE-level actions on a record (email send, document manage, add promise, POC assign) become `user.hasIn(<p>, x.regionId)`. The server remains authoritative.
- Promise override uses `p.canOverride ?? user.has(promiseOverride)`.
- `dispute_detail_screen.dart:123` becomes `d.canResolve ?? user.has(Privileges.disputeManage)`, replacing the role-name gate (G20).
- **Region column** `TableColumnSpec(label: 'Region', sortKey: 'regionName')` on the customers, invoices, payments, promises, disputes and tasks lists. It is shown only when `user.multiRegion`.
- **Locked-filter chips** are unchanged. The scaffold also shows the info strip "Your book only in: SOUTH" when `bookRegionIds` is non-empty and `lockedFilters` is empty.
- **Create flows:**
  - The customer dialog shows a required Region dropdown (active `regionsWith(customerManage)`) when there is more than one choice, and sends `regionId`.
  - The invoice, payment and promise customer pickers add `filter=regionId:in:<regionsWith(xManage)>`.
  - POC pickers pass `customerId` to `/api/pocs/assignable`.
- **`reference_picker.dart`** gains:
  - a `region` kind, with options from `currentUser.regions`;
  - a `staffUser` kind, searching `/api/assignees/people` (G6).
- **Move customer** (`customer_detail_screen.dart` top actions), when `customer.canMoveRegion == true`:
  - The dialog offers active `regionsWith(customerManage)` minus the current region, and a reason.
  - It POSTs `/move-region` with `version`, then shows the counts and warnings.
  - It invalidates the detail, `tablePageProvider` and `auditHistoryProvider`.
- **Users:** `F/features/users/region_grants_editor.dart`, embedded in `UserFormDialog` and the user detail screen.
  - Each row has a region (from `GET /api/regions?size=50`), a level (READ/MANAGE/APPROVE segmented control) and a scope (ALL/BOOK).
  - BOOK is disabled with the note "Book (set by role)" when the role lacks SCOPE_OVERRIDE. A warning shows when BOOK is chosen for a role with no `POC_ASSIGNABLE_*`.
  - On create it is pre-filled with DEFAULT at the highest level the role needs, and `regionGrants` is always sent (the server gives no grants when it is absent). Edits `PUT …/region-grants`.
  - It is read-only when editing yourself.
  - It offers only regions and levels within the editor's own grants (`currentUser.regions[].level`, and ALL scope only where the editor holds ALL); rows beyond them are shown read-only with "Only someone with this access can change it". The server's 403 remains authoritative (§4.6).
- **Roles:** `roles_screen.dart:207-227` shows a Global, Read, Manage or Approve badge from `regionLevel`.
- **Regions admin:** `F/features/regions/regions_screen.dart` (a `DataTableScaffold` on `/api/regions`), `region_form_dialog.dart`, and `region_detail_screen.dart` (Grants tab; Approval thresholds link).

### 7.6 Pending (202) handling at every mutating call site (FE-05; critic §3.11)

**Shared helper, `F/core/api/pending_reply.dart`:**
- `class PendingChangeRef { int id; String summary; String? operation; String? operationLabel; String? regionName; int? approverCount; int? supersededId; }`, parsed tolerantly.
- `PendingChangeRef? pendingFrom(Response res)` returns non-null only when `res.statusCode == 202 && res.data is Map && data['pendingChange'] is Map`.
- `void showSentForApproval(BuildContext, PendingChangeRef p, {String? message})` shows a SnackBar "Sent for approval: {summary}" with a **View** action to `/approvals/{id}`. When `approverCount == 0` it appends "No one else can approve changes in {regionName} yet. Ask an administrator."
- `void invalidateApprovals(WidgetRef)` invalidates `approvalsAwaitingMeCountProvider`, `pendingForRecordProvider`, `tablePageProvider` and `tableSummaryProvider`.

**Call sites:**

| Site | Gated? | Change |
|---|---|---|
| `customers/customers_screen.dart:333, 347`; `customer_detail_screen.dart:109, 172` | no | none (the G19 409 text stays correct) |
| `invoices/invoice_form_screen.dart:167` POST | **yes** | Call `pendingFrom(res)` **before** the `['id']` parse at :192. On pending: notice, invalidate tables, **skip** `notifyByEmailAfterSave`, `context.go('/approvals/${p.id}')`. |
| `invoices/invoice_detail_screen.dart:135` PATCH | **yes** | On pending: notice; `_loadedInto = false`, `_dirty = false`; invalidate `invoiceDetailProvider(id)` so the fields re-seed with **live** values (G18); invalidate the banner and history. |
| `invoices/invoices_screen.dart:254` cancel | **yes** | On pending: "Cancellation sent for approval"; invalidate the list |
| `payments/record_payment_dialog.dart:115` POST | **yes** | `_RecordedPayment` becomes `({int? id, PendingChangeRef? pending, bool notify})`. Parse pending before :126. `showRecordPaymentDialog` (:19–35) shows the notice and skips the email step. |
| `payments/payment_detail_screen.dart:95` PATCH | no | none |
| `promises/promise_form_dialog.dart:167-169` PUT/POST | **yes** | `_SavedPromise` gains `pending`. Parse before :169. The notice shows and the email step is skipped. |
| `promises/promise_form_dialog.dart:444` POST override | **yes** | On 202: pop `true` and show the notice. DELETE override (:442) is unchanged. |
| `promises/promises_tab.dart:284` cancel | no | none |
| `poc/customer_poc_editor.dart:87, 118, 123`; `disputes/dispute_create_dialog.dart:130` | no | none |
| `disputes/dispute_detail_screen.dart:96` approve | **yes** | On 202: snack "This approval needs a second approver"; skip the email; invalidate the dispute and banner; stay on the page. Deny is unchanged. |
| `core/table/data_table_scaffold.dart:473-480` bulk | **yes** (invoice CANCEL) | `_showBulkResult` (:552–607) reads `(data['pending'] as List?) ?? const []`. The quick snack also requires `pending.isEmpty`; otherwise it reads "N updated, M sent for approval". The dialog gains an "Awaiting approval" section (`#id → approval #pid`). An all-pending result counts as success. |
| `core/table/data_table_scaffold.dart:499` export | n/a | none (it gets `regionIds`/`asOf` through the interceptor) |
| `approvals/approval_thresholds_screen.dart` Save (PUT) | **yes** (loosening) | On 202: "Raising or removing limits needs a second approver" with a **View** action to the pending change; the tightening part shows as saved |
| `customers/move_region_dialog.dart` | no (400 when changes are pending) | Show the server message; the dialog stays open |
| New writes (tasks, rules, regions, grants, move) | no | ordinary 200 handling; run-now, retry and requeue show "Queued" on 202 |

### 7.7 New feature folders

#### 7.7.1 `F/features/approvals/` (FE-04)

| File | Contents |
|---|---|
| `approval_models.dart` | `enum PendingStatus {...}` with a parse fallback to PENDING; `PendingChangeBrief`, `PendingChangeDetail`, `FieldChange` |
| `approval_providers.dart` | `approvalDetailProvider` (family int); `pendingForRecordProvider` (family `({String entityType, int entityId})`); `approvalsAwaitingMeCountProvider` (`StreamProvider.autoDispose<int>` via `pollUnreadCount`, gated per §7.3.4) |
| `approvals_screen.dart` | `DataTableScaffold` (entity `approvals`). A `SegmentedButton` (Awaiting my approval / My requests / All I can see) sets `extraParams: {'view': 'AWAITING_ME' \| 'MINE' \| 'ALL'}`, a scalar (G1). The default is AWAITING_ME with `changeApprove`, else MINE. |
| `approval_detail_screen.dart` | Header (summary, `ApprovalStatusChip`, amount vs threshold, region, requested by and at, origin with a rule link); the `changes` table; Current/Proposed panes via a public `JsonDiffView` extracted from `audit_history_panel.dart`'s `_AuditDetails`. Buttons appear **only per the server flags**: Approve (optional note), Reject (note 1–500 required), Withdraw (confirm). After an action: invalidate the detail, `invalidateApprovals`, `auditHistoryProvider`. APPROVED shows "Open {entityLabel}" (`result.link`); FAILED shows `failureReason`; `blockedReason` is a hint. |
| `pending_banner.dart` | `PendingChangeBanner(entityType, entityId)` in the **`top`** of the invoice, payment, promise, dispute and customer detail screens (never as a tab, G5): "Awaiting approval: {summary} · requested by {name} on {date}" with a **View** button. It shows the anonymous text when `requestedBy` is null. Hidden when empty. |
| `approval_thresholds_screen.dart` | Region dropdown (regions where the user holds `approvalThresholdManage` or `changeApprove`), eight rows with money fields (`parseMoneyInput`/`formatMoney`; empty = not gated) and a "Counts changes over" hours field (`windowHours`, 0 = each change alone), "Apply to all", Save (PUT, then show `warnings`; on 202 the pending notice of §7.6). A banner shows an outstanding `pendingChange` with a View link. Read-only when `!canEdit` or historical. |

- `status_chip.dart` gains `ApprovalStatusChip`.
- `audit_history_panel.dart` gains:
  - `CHANGE_*` labels in `_actionLabels`;
  - a `stableKey` branch for `pending:$pendingChangeId`;
  - the meta line "via approval #N · approved by X" and a pending chip from `pendingStatus`.

#### 7.7.2 `F/features/tasks/`, `F/features/assignees/` (FE-06)

| File | Contents |
|---|---|
| `assignees/assignee_providers.dart` | `assigneeContextProvider` (family `({EmailEntityType type, int? entityId})` → `/api/assignees/context`, parsed with `EmailContext.fromJson`); `searchAssignablePeople(Dio, q)` → `/api/assignees/people` |
| `assignees/recipient_chips_field.dart` | `RecipientChipsField({ctx, value, onChanged, allowCustomer=false, single=false, roleFilter, perRecordLabels=false, label='Assignees', searchPeople})`. Chip icons: person, badge, business. Tooltips list holders. Rows: "Person…" plus one per `ctx.roleGroups`. Written fresh; **`send_email_dialog.dart` is not modified by FE-06** (its 32 tests stay pinned); FE-07b later adds only a `PlaceholderButton` to it, additively (§7.7.3). |
| `assignees/people_search_dialog.dart` | A generic 250 ms debounced search dialog |
| `tasks/task_models.dart` | `enum TaskStatus { OPEN, IN_PROGRESS, DONE, CANCELLED }` (fallback OPEN); `TaskItem.fromJson` (all nullable except id, title, status) |
| `tasks/task_providers.dart` | `taskDetailProvider`, `taskCountProvider(({String entityType, int entityId}))`, `myTasksCountProvider` (gated per §7.3.4) |
| `tasks/task_actions.dart` | `canViewTasksProvider`, `canManageTasksProvider` (both AND `!isHistorical`); `tasksDetailTab(ref, {entityType, entityId, customerScope=false})`. Its presence depends **only** on `canViewTasksProvider` (G5); the count is the badge. |
| `tasks/tasks_tab.dart` | `DataTableScaffold` on `/api/tasks` with `extraParams` `entityType`/`entityId`, or `customerId`; "New task" |
| `tasks/tasks_screen.dart` | `TasksScreen({query, mine=false})`. `/tasks` has quick filters "Assigned to me" (`assigneeUserId:eq:<me>`), "Overdue", "Open". `/my-tasks` uses `/api/tasks/mine`. Row and bulk actions: Start, Done, Reopen, Cancel. Mobile card. `onBulkDone` invalidates `myTasksCountProvider`. |
| `tasks/task_form_dialog.dart` | `showTaskDialog(context, {entityType, entityId, TaskItem? existing})`: title, description, due date, assignees |
| `tasks/task_detail_screen.dart` | `DetailScaffold` with tabs Details and History (`AuditHistoryPanel(entityType: 'TASK')`) |

- The customer, invoice and payment detail screens add `tasksDetailTab(...)` next to `documentsDetailTab`. The customer screen passes `customerScope: true`.
- `status_chip.dart` gains `TaskStatusChip`. `field_limits.dart` gains `taskTitle = 200` and `taskDescription = 2000`.
- `audit_history_panel.dart` adds TASK to `_typeOrder`, `_typeLabels`, `_routeFor` and `_iconFor`, plus the TASK action labels.

#### 7.7.3 `F/features/automation/` (FE-07a and FE-07b)

| File | Slice | Contents |
|---|---|---|
| `automation_models.dart` | 07a | `Rule`, `RuleTrigger` (kinds including `MANUAL`, `catchUp`), `ConditionNode` (`ConditionGroup{op, children}` / `ConditionLeaf{TableFilter filter}`) with `toJson`/`fromJson`, `RuleAction` variants, `RunSummary` (with `statusAt`, `partial`, redacted `entity`), `Effect` (with `hidden`), `PlaceholderCatalogue` |
| `automation_providers.dart` | 07a | `ruleDetailProvider`, `placeholdersProvider(String entityType)`, `runDetailProvider`, `runEffectsProvider`, `automationHealthProvider` |
| `rules_screen.dart` | 07a | `DataTableScaffold(entity: 'automation-rules', path: '/api/automation/rules')`; status chip (Active, Disabled, Suspended with reason); row actions Run now, Enable/Disable, Resume, Delete; tiles from `/rules/summary`; a health card (AUTOMATION_ADMIN) with Requeue. Run now opens `run_now_dialog.dart`. A non-owner administrator's Enable, Resume and Run now first confirm "This makes you the rule's owner; it will act with your access" and send `confirmOwnershipChange: true`. Resume offers "Also act on the N changes made while it was suspended" (`replay`). |
| `run_now_dialog.dart` | 07a | "Run on everything that matches" or "Run on chosen records" (≤ 50, record picker via `searchEmailRecords`), "Now" or "At…" (`runAt`, date and time pickers), the current region selection shown as "In: SOUTH". POSTs `/run`; shows "Queued" and opens the run. |
| `rule_editor_screen.dart` | 07a (basics, trigger, regions, conditions, preview count and sample); 07b (actions, rendered preview, Runs tab) | `/automation/new` and `/automation/:id`: a full page in the `InvoiceFormScreen` shape, **plus an `UnsavedChanges` guard**. Sections: Basics; Trigger (entity `SegmentedButton`, kind including "Only when run by hand", time picker, weekday, zone preset to the device offset `+HH:mm` or UTC, catch-up ONE/ALL); **Regions** (multi-select over the owner's `regionsWith(automationManage)`, empty = all); Conditions (server `warnings` such as "Becoming overdue is not an update" shown inline); Actions; Preview (count, sample, record picker via `searchEmailRecords`, rendered actions, problems, warnings; an "As the owner" toggle for the owner or an administrator, showing `evaluatedAs` and greyed hidden samples). Existing rules add tabs Runs and History (`AuditHistoryPanel(entityType: 'RULE')`) and the line "Runs as <owner>". A 400 on `confirmOwnershipChange` opens a confirm dialog, then resends. Save, Run now, Enable and Delete are disabled while historical; Preview stays enabled. Changing the trigger entity asks before dropping placeholders and tokens that no longer apply. |
| `condition_builder.dart` | 07a | Recursive `ConditionGroupEditor` with an "All of these / Any of these" `SegmentedButton`. Leaves are `InputChip`s labelled by `describeFilter` and edited with `showFilterEditor`. "Add condition" and "Add group" (depth ≤ 4). The schema comes from `tableSchemaProvider('customers' \| 'invoices' \| 'payments')`. **It never uses `TableQuery.addFilter`.** |
| `placeholder_button.dart` | 07a | A menu grouped by level; inserts at `controller.selection`; strips newlines in the subject |
| `action_cards.dart` | 07b | One card per type, each with a `repeat` control and its guard checkbox. **Task:** title/description with `PlaceholderButton`, due base + offset, assignees. **Promise:** amount source, date, Collection POC (`single`, `roleFilter: {COLLECTION_POC}`), co-assignees (PROMISE context), notes, link. **Dispute:** reason, proposal dropdown, assignees (DISPUTE context). **Email:** a summary plus "Edit email…". |
| `email_action_dialog.dart` | 07b | From dropdown (Me/owner, roles, a person), "Fall back to the owner", To (`RecipientChipsField(allowCustomer: true, perRecordLabels: true)`), subject and body with `PlaceholderButton`. Returns a `Map`. |
| `rule_runs_tab.dart`, `run_detail_dialog.dart` | 07b | The runs list (with `statusAt` in historical mode and a "Counts only records you can see" note when `partial`); effects with links (hidden targets greyed, no label) and Retry. Polls every 2 s while QUEUED or RUNNING, for at most 2 minutes (`EmailRefresher` pattern). |

- **Dispute detail (07b):** assignee chips with Edit (DISPUTE_MANAGE), a "Created by rule" chip, an "Internal" badge, and "Acknowledge / Close without change" wording for a `none` proposal on a CUSTOMER target.
- **Promise detail (07b):** co-assignee chips with Edit (`canManage`). Co-assignees are added after creation; `promise_form_dialog.dart` does not offer them (critic finding PC-14).
- **Manual email placeholders (07b):** `F/features/email/send_email_dialog.dart` gains a `PlaceholderButton` beside the subject and body fields, **additively**: no existing widget key, label or field order changes, so its 32 tests stay green. It loads `placeholdersProvider(entityType)` only for CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE, and shows the server's 400 `fieldErrors.subject`/`.body` for an unknown placeholder (§5.10.4).
- `status_chip.dart` gains `RuleStatusChip` and `RunStatusChip`. `field_limits.dart` gains `ruleName = 120`. The audit panel gains RULE.

### 7.8 Frontend test harness

`FT/support/fake_backend.dart` gains, additively:
- `FakeReply(int status, Object? data, {Map<String, List<String>> headers = const {}})`. It resolves with that status and headers. Dio treats 2xx as success, so a 202 flows through the success path. Existing routes returning plain data are unchanged (status 200, no headers).
- **`Dio dioWith(List<Interceptor> before)`** (critic finding CF-15). `FakeBackend.dio` is a getter that builds a **new** `Dio` on every call, and its single interceptor resolves every request in `onRequest`, so an interceptor added afterwards to `backend.dio` either lands on a throwaway instance or never runs. `dioWith` builds one instance with `before` inserted **ahead of** the fake-routing interceptor, so `sent(...)` sees the parameters they add.
- The idiom for scope tests is therefore:
  ```dart
  final dio = backend.dioWith([viewScopeInterceptor(() => scope)]);
  ProviderScope(overrides: [dioProvider.overrideWithValue(dio), ...], child: ...)
  ```
  Tests capture the `Dio` once and never call `backend.dio` twice.

---

## 8. Test plan

**Rules for every test**
- Every backend test extends `IntegrationTestBase` (H2) unless it is marked *plain*.
- No per-class `@Import`, `@MockBean`, `@TestPropertySource` or `@DirtiesContext`.
- Time is moved only with `MovableClock.SHARED`.
- Background work is driven by direct calls, after `SecurityContextHolder.clearContext()`.
- Style follows the house conventions:
  - AssertJ plus MockMvc `jsonPath`;
  - request bodies built as `Map` values;
  - sentence-style test names;
  - after a refusal, re-read the database and assert that nothing changed.

### 8.1 Harness and cross-cutting guards

| Test | Asserts |
|---|---|
| `IntegrationTestBase` itself | The reset order in §3.12 (including `AppClock.set(MovableClock.SHARED)`). `@AfterEach historyIsInStep` (from BE-07): the reconciler reports no problems after **every** test in the suite. |
| `common/time/ClockTest` | `MovableClock` follows system time until set; `set`/`advance`/`reset` behave; `InvoiceNumbers.next()` uses the clock's day; the default `invoiceDate` and entity `createdAt` come from the clock; JWT minting is unaffected by a clock set in the past; `AppClock.get()` is never null before the bridge runs; after `AppClock.set(Clock.systemUTC())` (as a drift boot would do) the next test's reset makes entity callbacks follow `MovableClock` again |
| `common/time/ClockReadGuardTest` (plain scan, BE-01) | No `X.now()` without a clock argument, `.now(ZoneOffset…)`/`.now(ZoneId…)`, `new Date()` or `System.currentTimeMillis()` in `src/main/java` outside `common/time/**` (§5.1) |
| `common/schema/SchemaSupportTest` | On a scratch H2 table: `widen` alters a native ENUM column and keeps NOT NULL; the CHECK branch still works; `enforceNotNull` enforces, and skips while NULLs remain |
| `privilege/PrivilegeClassificationTest` (plain) | §4.9 |
| `common/scope/ViewScopeTest` | `regionIds` repeated and comma forms; blanks; garbage → 400 `REGION_IDS_INVALID`; `asOf` invalid, future and today → live header; before BE-09a a historical date → 400 `AS_OF_UNSUPPORTED`; the ThreadLocal is cleared (a request with a scope followed by one without is live and all-regions); `with()` restores the previous scope |

Every test that boots an extra context (`RegionSchemaUpgradeTest` (b), `DisputeTargetWidenTest`, `ApprovalSchemaTest`'s drift boot) restores `AppClock` in `finally` (D5).

### 8.2 The endpoint-by-endpoint coverage guard (critic R1)

**`common/scope/ViewScopeInventoryTest`** enumerates `RequestMappingHandlerMapping.getHandlerMethods()` under `/api/**` and fails the build unless **both** of these hold:
1. **As-of classification:** every GET/HEAD handler resolves to AWARE or IGNORED. Every non-GET handler with a method-level `@AsOfAware` is in the literal read-POST allow-list: the six record `/export` endpoints (customers, invoices, payments, promises, disputes, products), `/api/tasks/export`, `/api/approvals/export` and `/api/automation/rules/preview`. Every non-GET with a method-level `@AsOfIgnored` is in the housekeeping allow-list (§5.2.4).
2. **Region classification:** every handler is in exactly one of the literal lists:

| List | Contents |
|---|---|
| `SETS` | Lists, summaries, exports, bulk, dashboard, approvals and tasks lists, rule preview, **the rules list and summary**, **notifications list and unread count**, **inbox list and unread count**, `/api/pocs/assignable` and `/my-scope`, `/api/assignees/people`, email bulk |
| `BY_ID` | Details and record tabs, rule detail, runs and effects |
| `WRITES` | |
| `GLOBAL_EXEMPT` | Users, roles, products, regions, grants, thresholds, privileges, auth, table-schemas, Gmail, mail-service, history, automation placeholders and health |

A new endpoint therefore cannot ship without a deliberate classification.

**Behavioural coverage**, each table-driven over its list:

| Test | Asserts |
|---|---|
| `region/RegionParamCoverageTest` (BE-03, extended by later slices) | With region N and S fixtures, for **every SETS endpoint** called with `regionIds=S`: only S rows appear, and `PageResponse.regionIds == [S]`, `scope.regionIds == [S]` or the export CSV is S-only. For bulk CANCEL with `selectAllMatchingFilter`, only S rows are cancelled. **Rows are added by the slice that narrows the endpoint** (critic finding CF-12): BE-03 the record lists, dashboard, email bulk and the inbox; BE-04b `/api/pocs/assignable`, `/my-scope` and notifications; BE-12 tasks and `/api/assignees/people`; BE-15 approvals; BE-16 the rules list and summary. The test's row table is checked against `SETS`, and an endpoint in `SETS` with no row yet must be listed in a literal `PENDING_ROWS` set naming the slice that adds it; the set is empty after BE-16. |
| `history/AsOfEndpointCoverageTest` (BE-10, extended by later slices) | For **every AWARE GET** and AWARE POST, using sample URLs from `AsOfScenario`: 200; `X-View-As-Of` equals the date; **the response differs from live** for the scenario (the C-1 safeguard); and the endpoint's declared mechanism is hit. VERSIONED endpoints need `CountingStatements.reads("_versions") > 0`. INTERVAL endpoints (approvals, runs, effects, rule revisions, record documents, emails, audit) need a scenario whose interval excludes a live row. |
| `common/scope/AsOfReadOnlyTest` (BE-09a) | Every WRITE handler, sent with a historical `asOf`, returns 400 `AS_OF_READ_ONLY`, and row counts and versions are unchanged. Housekeeping POSTs succeed. |

### 8.3 Regions (B1)

| Test | Asserts |
|---|---|
| `region/RegionRightsTest` (plain) | The formula in §4.2 for each level and scope; SCOPE_OVERRIDE needs ALL; `regionsWith(GLOBAL)` throws; `forCustomer`; `none()`; `effectiveAnywhere`/`effectiveIn`; a null region gives false |
| `auth/PrincipalRightsTest` | `CountingStatements.reads("user_region_grants", GET /api/invoices with a real Bearer) == 1`; `ScopeResolver` issues no `role_privileges` reads; `/api/emails` and `/api/inbox` (non-OEMIV paths) give 200 for a multi-region user; with `clearContext()`, `forInvoices()` throws BadCredentials while `hasIn` is false; the `/me` shape, and `[]` for a customer; `POST /api/users` without `regionGrants` (BE-02 bridge) gives the new user a DEFAULT/APPROVE/ALL grant |
| `region/RegionSchemaUpgradeTest` | (a) On the shared DB, following the deleted `InvoiceSchemaUpgradeTest` pattern: null the `region_id` columns, set the backfill flag, delete grants, `afterPropertiesSet()` in try/finally. Assert the backfill, NOT NULL, one APPROVE/ALL grant, the flag cleared, and the audit row. A second run creates no duplicates. A user whose flag is false keeps no grants. (b) A `SchemaDriftTest`-style boot on `jdbc:h2:mem:drift-<uuid>` with `ddl-auto=update` seeds a DEFAULT grant for admin; `AppClock` restored in `finally`. |
| `region/RegionAccessMatrixTest` (BE-04a writes; BE-04b flags, documents, email, seats) | **Rita** (CASHIER; NORTH READ/ALL, SOUTH MANAGE/ALL, no WEST). Lists: all → N+S; `regionIds=N` → N; `regionIds=W` → an empty 200 with `regionIds: []`. Detail: N 200 with `canManage=false`; S `canManage=true`; W 404. PATCH/cancel: N 403, S 200, W 404, and **the DB is unchanged after each refusal**. POST invoice, payment and promise per customer region: 403 / 200 / 404, and new records carry `region = S`. Customer create: `regionId=N` → 403; missing with two managed regions → 400 `fieldErrors.regionId`; inactive → 400. Document upload on N → 403, with `DocumentDto` flags per region. Email send on N → 403; **manual send on S `to` or `from` a user who holds nothing in S → 400 `fieldErrors`** (PC-2). Seat add on N → 403. Dispute approve on N → 403, on W → 404. Promise override on N → 403. `/recompute` limited to S. `asOf` on a PATCH → 400. |
| `region/RegionBookTest` (BE-03 reads; BE-04a creates; BE-04b POC and holders) | SALES_POC plus SCOPE_OVERRIDE with NORTH MANAGE/ALL and SOUTH MANAGE/BOOK: sees every N invoice and only their own S invoices; `lockedFilters == []`, `bookRegionIds == [S]`. A pure-book user sees the chip, as today. Narrowed to nothing → no chip. Dashboard: MIXED coverage, and collected = S allocations + N payment sums checked by hand. Single-region figures for admin, SALES_POC, collection-only custom and PAYMENT_VIEW-only override roles equal the pre-change formula (F2). **BE-04a:** a pure-book SALES_POC POSTing an invoice, payment or promise for a customer outside their book → 404 and nothing written (SC-12). **BE-04b:** `requireAssignable` without a grant → 400 with the region message; `/assignable?customerId` lists only users with region access; Email To and `defaultAssignee` skip holders without access. |
| `region/CustomerLoginRegionTest` | `regionIds` elsewhere changes nothing; another customer's invoice → 404; another customer's dispute → 403 (X1); a login follows a region move |
| `region/RegionAdminTest` (BE-05; BE-13 adds the APPROVE cases) | CRUD and validation; 409 stale; make-default flips exactly one; the default cannot be deactivated or deleted; deleting a referenced region → 400; creator auto-granted; grants `PUT` audited with the granter; self → 400; customer login → 400; duplicate → 400; user delete removes grants (`@OnDelete`); **`POST /api/users` without grants → none, with `[]` → none**; **a NORTH-only USER_MANAGE admin granting SOUTH → 403; granting APPROVE while holding MANAGE → 403; a row outside the caller's reach sent back changed → 403, unchanged → kept** (SC-5); **BE-13:** granting APPROVE without `CHANGE_APPROVE` in the region → 403, and a user gaining APPROVE notifies the region's other approvers; `regionLevel`; `/api/table-schemas/regions` needs REGION_VIEW or USER_MANAGE; deleted-customer history → 404 without access to its region |
| `region/CustomerRegionMoveTest` (BE-08; extended by BE-12 and BE-15) | Children regions change and Invoice/Customer versions bump; `CUSTOMER_REGION_CHANGED` carries `region_id`; a `customer_region_moves` row is written; warnings list POCs without access and seats are kept; 403 source or target; 400 inactive or same; 409 stale; `asOf` → 400; a NORTH-only user gets 404 afterwards; a test participant's veto rolls everything back; a stale `PaymentPromise` saved after the move keeps the new region; `moved.other` counts; **history:** every moved row has a new version at the same instant with the new region, and `asOf` before the move shows the old region; after the move a broken promise does not notify a POC without access to the new region, and goes to its PROMISE_MANAGE holders instead (SC-17). **BE-12 adds** (PC-16): after a move N → S, `GET /api/tasks?regionIds=N&asOf=<before>` lists the customer's task and `regionIds=S` does not, and the reverse live; `task_versions.region_id` changes at the move instant. **BE-15 adds:** a move racing a held `PROMISE_UPDATE` leaves no PENDING row whose region differs from the customer's (SC-7). |
| `region/RegionConsistencyTest` | After create, pay, promise, dispute, task and move scenarios, a JPQL join finds no child whose region differs from its customer's (I1) |

### 8.4 History and as-of (B3)

| Test | Asserts |
|---|---|
| `history/VersionShapeTest` (BE-06) | For each live/version pair, every persistent attribute of the live entity (JPA metamodel) exists on the version with the same Java type, or as an `<assoc>Id` Long plus a read-only `<assoc>`, or is in the exclusion list (`version`, JSON- or link-handled collections) |
| `history/VersionTablesHaveNoConstraintsTest` (BE-06) | Via `information_schema`: `*_VERSIONS` tables have only the PK; no FK, unique or CHECK; no H2 ENUM column types |
| `history/SnapshotTest` (BE-06) | `snapshotAll` and `sameState` per type: BigDecimal scale-insensitive; `items_json` stable ordering and **no `productName` key**; excluded columns ignored |
| `history/HistoryCaptureTest` (BE-07) | One case per write path: customer create/update/delete; seat add/remove/set-primary; invoice create (with credit), update, cancel, `replaceItems`, `cancelWithRefund`; payment record, update, void, `updateAmount`; `CreditLedger` apply and refund; promise create, update, cancel, override add and clear, `applyShares` link-only change, sweep; dispute open, approve, deny, notes; product CRUD, **including a rename of a product used on an invoice, after which `historyIsInStep` holds and no invoice version is written** (CF-4); each bulk action. Each asserts that the previous version closed at the tx instant, exactly one new open version with the expected attributes, and origin, actor and `txId` (shared within the transaction). |
| `history/HistoryRollbackTest` | An exception, `setRollbackOnly` and `recomputeAll(false)` write no versions; bulk `REQUIRES_NEW` rows get distinct `txId`s; **one `EntityManager` running a rolled-back transaction and then a committed one (the OEMIV shape) writes only the second transaction's versions, all with its `txId`** (CF-22) |
| `history/HistoryTimeTest` | All versions of one transaction share `t`; a same-µs second transaction gives `validFrom = prev + 1µs`; a clock moved back never gives `valid_to <= valid_from`; a no-op save writes nothing |
| `history/HistoryConcurrencyTest` | Two threads relink the same promise to different invoices behind a latch (each calls `actAs`; hold time < 2 s) → one open link row per target and no duplicates; two payments on one customer → contiguous intervals |
| `history/HistoryGuardTest` | A save inside `AsOf.with` throws and changes nothing; strict mode rethrows an injected mapping error (via `HistoryTestHooks`, the `@Primary` `HistoryFaults`) |
| `history/ModifyingQueryGuardTest` (plain + scan) | No `@Modifying` in the repository of a versioned entity; no `createNativeQuery`, `executeUpdate`, `JdbcTemplate` or `nativeQuery = true` in `src/main/java` outside the allow-list kept in the test (§5.7.6: `**/*Upgrade.java` including `RowVersionUpgrade`, `common/schema/**`, `history/**`, `GmailConnectionRepository`, the move repository, the task-move method). It passes on the baseline code. |
| `history/HistoryBaselineTest` (BE-08) | Seed live rows, clear versions and meta, `run()` twice: one open version per row with `valid_from = started_at` and `origin = SCHEMA`; idempotent; a later gap gets a version from now; an empty DB leaves `started_at` null; `asOf` before the start → 400 |
| `history/HistoryReconcilerTest` (BE-08) | A bulk JPQL `update Invoice set notes` in a test transaction → `check()` reports drift → `reconcile()` writes one RECONCILE version; orphan closed; missing inserted; lock-and-recheck skips a concurrently changed row |
| `history/AsOfContextTest` (BE-09a) | Invalid, future, today→live, before-history and valid; header values; `PageResponse.asOf` echoed; ThreadLocal hygiene |
| `history/AsOfListTest` (BE-09a; extended by BE-09b, BE-12 and BE-15) | Using `AsOfScenario` (D1: customer A (region N), invoices X 1000 and Y 500, Sam as sales POC and seat. D2: payment 400 on X, promise 600 linked to X. D3: rename A; move A to S; remove Sam's seat; rename the product. D4: dispute voids the payment; cancel Y. D5: pending change on X. D6: approve it). **BE-09a:** lists for customers, invoices and products at D1–D4: values; `totalElements`; `overdue` with today = D; `relative` versus D; sort and filter by `customerName` at T; `outstanding` at T; `successPocUserId` at T; tiles and `/summary`; `POST /export` CSV at T. **BE-09b:** payments, promises (`invoiceId` link filter at T) and disputes at D1–D4. **BE-12:** tasks (`TaskAsOfTest`). **BE-15:** D5–D6 (`ApprovalAsOfTest`). (Critic finding CF-5.) |
| `history/AsOfDetailTest` (BE-09a customer, invoice, product; BE-09b payment, promise, dispute) | Each `GET /{id}` at T (items after `replaceItems`; allocations after void; links; `updatedAt`); 404 before creation; flags false; the product email tab (`GET /api/emails?entityType=PRODUCT&entityId=`) answers at T (BE-09b; CF-23) |
| `history/AsOfParityTest` (BE-10) | Capture live responses at day D (after the writes), set the clock to D+1 10:00, request `asOf=D`, and compare JSON for **every schema × every filterable column × one valid operator per type** (generated from `TableSchema`, so later columns such as `paymentTerm` and `overdueAmount` join automatically), every sortable column, every `/summary`, D1–D8 and every detail. Everything must be identical except `asOf`, `version`, `X-View-As-Of` and **every server capability flag** (`can*`, `editTakesOwnership`, `blockedReason`), which must instead be `false`/null in the historical response (D14; critic finding CF-13). |
| `history/AsOfDashboardTest` (BE-10) | D1–D8, including a backdated invoice created after T, a void after T in both branches, ageing versus D, and names at T |
| `history/AsOfScopeTest` (BE-10) | A SALES_POC reassigned after T sees the old invoices at T but not live; a customer login sees only its own; read in N only: sees A at D2 but not at D4 or live |
| `history/AsOfTabsTest` (BE-10) | POC tab, documents tab (uploaded or deleted after T), emails tab (sent after T hidden) and audit tab at T |
| `history/HistoricalObjectsAreWriteProofTest` (BE-09a) | `invoiceRepository.save(historical)` throws; the live row is unchanged |
| `history/LiveReadGuardTest` (BE-09a) | `invoiceRepository.findById` inside `AsOf.with` → `AS_OF_UNSUPPORTED`; `allow` permits it |
| `history/AsOfQueryCountTest` (BE-09a) | Admin invoice page of 20: `reads("invoice_versions") == 2` (page plus count), `reads("customer_versions") == 1`; live `GET /api/invoices` has `reads("_versions") == 0` |
| `history/TemporalConventionTest` (plain scan, BE-09a) | §5.7.14 |
| `history/RoundTripTest` (BE-09b) | A fully populated live entity → `snapshotAll` → `rehydrate`: every metamodel attribute is equal (the product name on items comes from `product_versions`) |

### 8.5 Outbox, tasks and automation (Part A)

| Test | Asserts |
|---|---|
| `automation/event/OutboxPublisherTest` (BE-11) | `theSaveReadsNoRules` (`CountingStatements.reads("automation_rules", POST /api/invoices) == 0`); a rolled-back save leaves no event; `recompute` dry run leaves none; invoice create → one INVOICE CREATED plus one CUSTOMER UPDATED (`CREDIT,OUTSTANDING`); payment record publishes each allocated invoice once plus the payment CREATED; bulk cancel publishes only committed rows; publishing outside a transaction → `IllegalTransactionStateException`; oversized `changes` fitted; every row has a `history_tx_id` with a `history_transactions` row after commit, including a seats-only save; AUTOMATION origin stamped with `caused_by_rule_id`; provenance is the snapshot at publish time (a `RunAs` that ended before commit still stamps its origin); APPROVAL origin and **`actor_user_id` = maker** on an approved change (BE-13+) |
| `automation/event/OutboxSchemaTest` (BE-11; critic finding PC-12) | Via `information_schema`: `automation_events` has only its PK; no FK, unique or CHECK constraint; no H2 `ENUM` column type; each fitted column is at least its fit length; `record_ids` and `region_ids` are unbounded text |
| `automation/event/TriggerCoverageTest` (BE-11) | §5.8.4 version oracle for every listed operation, including the customer rename (one CHILDREN event) and the region move (**exactly two** `automation_events` rows for a customer with 20 invoices and 5 payments; critic finding PC-4) |
| `task/TaskApiTest` (BE-12) | CRUD; 404 out of scope; 403 on a READ-only region; token validation; an assignee may complete but not retitle; cancel needs MANAGE; 409 stale; `customerDeleteCancelsAndMarks`; a user with assignments is deactivated, not deleted; bulk mark done; export; region copied from a locked customer; **a USER assignee who cannot see the record → 400 `fieldErrors.assignees` and nothing written (no `UnexpectedRollbackException`)** (CF-2); a role holder without TASK_VIEW is neither notified nor an effective assignee (PC-2); the context starts (CF-3) |
| `task/MyTasksTest` (BE-12) | A seat added after creation makes the task appear; a removed seat makes it disappear; a record-level Sales POC sees the invoice task; an invisible parent is never listed; mine count; `/mine/summary` shape; `lockedFilters == []` |
| `task/TaskAsOfTest` (BE-12) | Status as of a date from `task_versions`; assignees as of a date from the `record_assignees` interval; ROLE assignee resolved against **seats at T** |
| `assignee/VisibilityProbeTest` (BE-12) | `canSee` for FULL, BOOK (in and out of book), NONE and no VIEW privilege; an inactive user and a customer login → false; called inside an open write transaction that then commits normally after a false answer (CF-2) |
| `common/query/ConditionTest` (BE-16) | AND/OR nesting; AND-only parity with the list endpoint (the same ids as `GET /api/invoices?filter=…`); `neq` includes nulls; derived `overdue` and seat columns; `paymentTerm` on invoices and customers and `overdueAmount` on customers (live and as of T) (PC-11); unknown column, bad operator or bad enum rejected at save with paths; empty group rejected; depth and leaf limits; keyset visits every record once (page 3, 10 records) |
| `automation/placeholder/PlaceholderTest` (BE-16) | Levels offered per entity; the primary follows the sender rule when the primary is inactive (`threeCollectionSeats`); fallback used and empty warns; a value containing braces is not re-expanded; subject one line and fitted with a warning; POC placeholders blank without POC_VIEW; unknown key rejected at save |
| `automation/rule/RuleApiTest` (BE-16) | Create makes the caller owner; editing another's rule needs AUTOMATION_ADMIN plus confirmation; 409 stale; a saver lacking an action privilege → 400; `regionIds` outside the owner's regions → 400; an UPDATED rule on `overdue` saves with the "not an update" warning (PC-5); soft delete keeps revisions; table schemas readable by holders, 403 otherwise; writes refuse `asOf`; a revision on every change; rules list as of a date from revisions; **visibility** (PC-8): a SOUTH-only viewer does not see a NORTH-only rule (list, summary, detail 404), sees an unrestricted rule whose owner holds SOUTH, and `regionIds=N` narrows the list and `failedRuns24h` |
| `automation/PreviewTest` (BE-16) | Preview writes nothing (row counts of every automation table and of tasks, emails, promises, disputes and pending changes are unchanged); scoped to the caller (regions and book); `alreadyDone`; **as of a date:** `matchCount` evaluated on versions (a record that matched at T but not now is counted), `record.actions == null`, and **the revision in force at T is evaluated** (a condition edited after T does not change the answer; a rule created after T → 404) (PC-7); `evaluateAs=OWNER` uses the owner's regions and book and hides samples the caller cannot see; a non-owner without AUTOMATION_ADMIN → 403 for OWNER; SEND_EMAIL preview through `previewRendered` reports a USER recipient without access as a warning and writes nothing |
| `automation/run/WorkerDurabilityTest` (BE-17) | Consumer down keeps events PENDING; claim twice, one wins; redelivery creates one task; `CRASH_AFTER_COMMIT` + advance 6 min + drain → one task; a stale worker cannot overwrite the outcome (old token updates 0 rows); transient backs off then succeeds without a duplicate; DEAD after 5 attempts, run FAILED, owner notified; DEAD after 20 claims; DB down stops the drain without counting an attempt; a lock timeout is TRANSIENT, not infrastructure; drain refuses inside a transaction; requeue and retry endpoints. **Event point** (PC-1): consumer down; an invoice created UNPAID and then fully paid; drain → a "CREATED where status UNPAID" rule still fires (and its task shows the live balance 0). **No coalescing** (PC-3, CF-21, SC-8): two updates before a drain → two evaluations, each at its own point, and an EVERY_TIME action fires twice; a rule enabled between two updates fires on the second only; an UPDATED caused by rule R followed by a user UPDATED on the same record → R runs for the user event and not for its own |
| `automation/run/TriggerMatrixTest` (BE-17; critic finding PC-15) | 3 entity types × 3 entity trigger kinds × {create, update}: fire or no-fire through `drain()` after `clearContext()`; a record created with credit applied in the same transaction fires `ENTITY_CREATED` and `ENTITY_CREATED_OR_UPDATED` rules but not `ENTITY_UPDATED`; a `MANUAL` rule never fires on an event; a CHILDREN event from a region move runs each UPDATED invoice rule once per matching invoice; a customer UPDATED(OUTSTANDING) fires "Customer updated where outstanding > 1000" when an invoice crosses it |
| `automation/run/FailureClassifierTest` (plain, BE-17) | One case per §5.10.6 row, including `InvalidFieldsException` and `IneligibleException` → PERMANENT (CF-9) |
| `automation/run/RunAsOwnerTest` (BE-17) | The consumer runs with no ambient principal and clears it after success and after an exception; audit actor = owner with `rule_id`/`origin`; a book-limited SALES_POC owner touches only their own invoices; a region-limited owner touches only their regions; a rule restricted to S never acts in N although the owner holds N; an inactive owner suspends once and notifies; losing TASK_MANAGE suspends; **losing AUTOMATION_MANAGE (role edit) suspends on the next drain, and a role change suspends proactively** (SC-14); deactivating a user suspends their rules; resume is refused while the owner is inactive; a self-caused event is skipped; `active_since` ignores backlog; READ-only region → permanent effect failure without suspension |
| `automation/run/RunViewsTest` (BE-17; critic findings PC-8, SC-13) | An unrestricted rule owned by a NORTH+SOUTH user: a SOUTH-only AUTOMATION_VIEW viewer sees the rule, its runs with `partial: true` counters counting only SOUTH effects, NORTH effects with `hidden: true` and null `message`, `warnings`, `result` and `target.id`, and an entity run on a NORTH invoice with a hidden `entity` and null `message`; the owner and an AUTOMATION_ADMIN see everything; runs as of T report `statusAt` |
| `automation/action/TaskActionTest` (BE-17) | Once vs every time; the `onlyIfNoOpenTask` guard (re-checked under the lock); an invisible USER assignee is dropped with a warning and the task is still created; notifications to visible holders; region copied under the customer lock; the dedupe unique key is the backstop (two workers race; one effect); **lock window:** at most 30 statements between `faults.afterLock` and commit for a CREATE_TASK with two assignees (PC-4) |
| `automation/schedule/ScheduleCalculatorTest` (plain, BE-18) | Daily and weekly across `+05:30`; Europe/London spring-forward and fall-back; `latestAtOrBefore` on a boundary; `missedSlots` |
| `automation/schedule/ScheduleTickerTest` (BE-18) | Three missed days → one catch-up (`ONE`) or three runs (`ALL`, capped at 31); concurrent tick → one event; disabled rules not ticked; no rule version bump; purge keeps DEAD rows and removes old `history_transactions`; **weekly MONDAY 09:00 +05:30: a tick on Sunday → no event; on Monday after 09:00 → one event → drain creates tasks** (PC-15); **the slot is evaluated as of `scheduled_for`**: a record that matched at the slot and was changed before the drain is still acted on (PC-1) |
| `automation/run/RunNowTest` (BE-17) | Run-now → 202 with a QUEUED run; run-now with `recordIds` touches only those records; 50 large ids round-trip exactly (PC-12); 51 ids → 400; idempotent while queued (200, same run) for an identical request; **different `recordIds` queue a second run** (PC-6); two concurrent identical requests behind a latch → exactly one MANUAL event (SC-16); `runAt` defers processing until then; `regionIds=S` narrows the run to S records; **a disabled rule can be run**; a suspended rule → 400; a `MANUAL`-trigger rule runs only this way; a non-owner without AUTOMATION_ADMIN → 403; **a non-owner AUTOMATION_ADMIN → 400 without `confirmOwnershipChange`, and with it becomes the owner; after running the rule's CREATE_DISPUTE they cannot approve the resulting change (400)** (SC-2); historical `asOf` → 400; truncated at `maxRecordsPerRun` |
| `automation/run/SetRunTest` (BE-18) | Scheduled run yields and resumes from the cursor (page 2, budget 0), all pages evaluated at the same `scheduled_for`; a mid-run edit supersedes; rate limit suspends (limit 3) |
| `automation/run/ResumeReplayTest` (BE-18; critic finding PC-18) | Resume reports `skippedWhileSuspended`; `replay=false` acts on nothing from the window; `replay=true` fires once per event in the window, each at its own point, and a second replay repeats nothing |
| `automation/action/EmailActionTest` (BE-18) | The email row is OUTBOUND on the record with `sentBy` = owner; a From role resolves primary, then next active; an unheld sender falls back or fails; no recipients → skipped; SUCCESS mode dispatches once after commit; not configured → DONE with a warning; never left QUEUED; recipient cap fails; a role holder without access to the record's region is not included; **a USER recipient with no grant in the record's region is dropped with a warning and is not among the email's recipients; a USER `from` without access → not queued (or the owner with `fallbackToOwner`)** (PC-2, SC-3); **a subject that renders blank → FAILED effect (PERMANENT), event DONE, no retry** (CF-9) |
| `email/EmailPlaceholderTest` (BE-18; critic finding PC-10) | Manual send renders `{{Customer.Name}}` in subject and body; bulk email over three invoices renders each row's `{{Invoice.Number}}`; an unknown placeholder → 400 `fieldErrors.subject`; text without `{{` is stored byte-identical; retry sends the stored text |
| `automation/action/DisputeActionTest` (BE-19) | Internal dispute created with assignees and region; a duplicate pending internal dispute is skipped; a proposed cancel is applied on approval by another admin; the owner cannot approve their own proposed change but can deny; a customer-target dispute is acknowledged without a change; internal disputes are hidden from a customer login's list, get, email context and audit; **a customer login's `GET /api/audit?entityType=INVOICE&includeRelated=true` shows no DISPUTE, TASK or CHANGE rows from internal sources** (SC-1); **the customer can open their own dispute on an invoice that has a pending internal dispute** (SC-1); a customer login cannot open a CUSTOMER-target dispute |
| `automation/action/PromiseActionTest` (BE-19) | The primary becomes the Collection POC; co-assignees stored live; no POC fails permanently; amount zero skipped; `onlyIfNoOpenPromise`; once per record across events |
| `assignee/AssigneeApiTest` (BE-19; critic finding PC-14) | `PUT /api/disputes/{id}/assignees` and `/api/promises/{id}/assignees`: 200 in a MANAGE region, 403 in a READ region, 404 in an ungranted region, an invisible USER → 400 `fieldErrors.assignees`, the audit row and the notification; `/api/assignees/context` and `/people` gating, including customer login → 403, and `/people` listing only users with a grant in the asked or record region (SC-18); `pocReferenceCount` grows with assignments; the context starts (CF-3) |
| `dispute/DisputeTargetWidenTest` (BE-19) | A `SchemaDrift`-style boot on a throwaway `jdbc:h2:mem:auto-drift-<uuid>` URL with `ddl-auto=update` after creating the old **ENUM** column; then insert a CUSTOMER dispute; `dispute_versions.internal` NULLs become false; `AppClock` restored in `finally` |
| `automation/AutomationHoldTest` (BE-19) | The owner's promise is held with `origin=AUTOMATION` and maker = owner; redelivery through `drain()` twice leaves one pending row (after `clearContext()`); the owner's approve → 400; after another user approves, exactly one promise exists with its co-assignees (notified), and its audit actor = owner with `approved_by` = checker |

### 8.6 Maker-checker (B2)

`ApprovalTestBase extends IntegrationTestBase` adds **helpers only**:
- `threshold(Region, op, "amount")`, `threshold(Region, op, "amount", windowHours)`
- `approverIn(Region)`, `makerIn(Region)`
- `approve`, `reject`, `withdraw` (through MockMvc)
- `pendingOf(type, id)`

Thresholds made by the helpers use `windowHours = 0` unless a test passes one, so the cumulative window is tested only where intended.

| Test | Asserts |
|---|---|
| `approval/ApprovalSchemaTest` (BE-13) | No CHECK or ENUM on `pending_changes`/`approval_thresholds` enum-ish columns; `outstanding_key` accepts many NULLs and rejects a duplicate; a drift boot with existing `audit_logs` rows starts (`AppClock` restored) |
| `approval/PayloadCodecTest` (plain) | Nested `password`/`newPassword` stripped; unknown field ignored; missing field null; violation → `PayloadException` |
| `approval/GateInvoiceTest` (BE-13/14) | No thresholds: POST, PATCH and cancel return 200 with unchanged JSON paths; **held cancel leaves the invoice untouched** (202, no top-level `id`, `Location`; status, version and `paidAmount` unchanged; no `INVOICE_CANCELLED` row; **no outbox event; no new invoice version**); equal to the threshold → 200; held create consumes no number and no sequence increment, `CHANGE_REQUESTED` on CUSTOMER, approver notified, maker not; terms extension held, shortening applied, notes-only PATCH does no threshold query; bulk CANCEL of 3 with 2 above → 1 succeeded, 2 pending; **per region** (PC-13): NORTH `INVOICE_CANCEL` 100 and SOUTH 10000, cancelling a 500 invoice → 202 in NORTH and 200 in SOUTH; a 150-character customer name in the summary is fitted (SC-20) |
| `approval/GatePaymentPromiseTest` (BE-14) | A held payment changes no credit, allocations or count; promise create, update and override held; reassigns never held even at threshold 0; **per region**: NORTH `PAYMENT_RECORD` 100, SOUTH 10000, a 500 payment → 202 in NORTH, 200 in SOUTH |
| `approval/ApproveFlowTest` (BE-13/14) | Approve → 200 APPROVED; `INVOICE_CANCELLED` actor = maker, `approved_by` = checker, `pending_change_id` set; `CHANGE_APPROVED` actor = checker; maker notified with a link; **the outbox event has `origin = APPROVAL` and `actor_user_id = maker`; the version row has `origin = APPROVAL`, `origin_ref_id = pcId` and `actor_user_id = maker`** (CF-1, SC-6); self → 400; right only in another region → 403; not visible → 404; customer login → 403; `asOf` → 400; twice → 409; reject without a note → 400, with a note → REJECTED; withdraw by a non-maker → 403, by the maker → WITHDRAWN; create approved a day later → next-day number prefix, `invoiceDate` = approval instant, no sequence gap after a rejection; a payment approved later keeps `paidAt` = request |
| `approval/ApproveFailureTest` (BE-14) | Pending cancel then a small payment → FAILED "Cannot cancel an invoice with payments"; a POC reassigned meanwhile is kept; another edit of the amount → FAILED naming "Amount"; balance grows → FAILED "The amount grew…"; maker deactivated → FAILED (never 401); maker's MANAGE grant removed → FAILED; product deactivated → FAILED; a thread holding the customer lock for 3 s → 409 and still PENDING, a later approve succeeds |
| `approval/ApprovalConcurrencyTest` (BE-14) | Two checkers on two threads → exactly one effect, one APPROVED, one 409; supersede racing approve → consistent, at most one PENDING; **an approval of `PROMISE_UPDATE` racing a direct promise cancel ends CANCELLED or FAILED, never with the cancel reverted** (SC-7) |
| `approval/OutstandingChangeTest` (BE-14) | Same maker and operation → SUPERSEDED with `supersededId`; different operation or maker → 409 with the message; bulk → skipped; creates not limited |
| `approval/DisputeApprovalGateTest` (BE-14) | A customer proposal approved as proposed by a `CHANGE_APPROVE` holder → applied; an approver without it → 202 and the dispute stays PENDING; a rewritten change → 202; the second approver applies it (payment VOIDED, `resolvedBy` = maker, one pending row); deny while pending → CLOSED; **an admin who reset the customer login's password, then opens a dispute as the customer and approves it as proposed → 202, not applied** (SC-10); after the customer changes their own password, a CHANGE_APPROVE holder's as-proposed approval applies |
| `approval/ExemptPathsTest` (BE-14) | Every threshold at 0: sweeps, `recomputeAll`, reevaluate and credit application during an approved create produce no pending rows; the legacy wrapper on a held change → `IllegalStateException` with no row; no principal above the threshold → `IllegalStateException` |
| `approval/ApprovalVisibilityTest` (BE-15) | A book colleague sees it in the list and `for-record`; out-of-book without the right → 404 and an empty list; an approver in the region without book access sees it; a customer login sees `for-record` redacted, no creates, and the list → 403; **a customer login never sees an `internal_source` change (for-record, `pendingApproval` flag and filter, `pendingStatus`)** (SC-1); **a maker whose grant in the region was removed no longer sees their change, and `Detail.current` is null for a caller who cannot see the record** (SC-15); `regionIds` narrows; `view` AWAITING_ME/MINE; the `pendingApproval` filter and DTO flag; a THRESHOLD_CHANGE is visible to the region's approvers and threshold managers only |
| `approval/ApprovalAsOfTest` (BE-15) | T1 request, T2 supersede, T3 approve: outstanding row per interval, and `statusAt`; the `pendingApproval` filter honours as-of; summary counts at T; **a pending change closed by a move (the mover holding `CHANGE_APPROVE`) is outstanding at a T before the move, in the old region** (PC-16) |
| `approval/ApprovalAuditTimelineTest` (BE-15) | `CHANGE_*` rows on the target (CUSTOMER for creates) with `pendingStatus`; a dispute approval's rows on the target with `dispute_id`; customer timeline drops create requests and nulls `approvedBy` |
| `approval/ApprovalLifecycleTest` (BE-15) | Customer delete → CLOSED; **region move with pending changes by a mover without `CHANGE_APPROVE` in the source → 400 and nothing moved; with it → CLOSED** and approve → 409 (SC-4); **a move racing a dispute approval on the same customer: one waits for the other, neither deadlocks** (CF-8, SC-19); a user with pending references is deactivated, not deleted; a threshold change leaves rows PENDING |
| `approval/RegionMoveCooldownTest` (BE-15; SC-4) | NORTH `INVOICE_CANCEL` 100, SOUTH none; a cashier with CUSTOMER_MANAGE in both moves a customer N → S and cancels a 500 invoice → **202** (NORTH's limit still applies); after the cool-down (clock + 31 days) → 200; a move into a region with a stricter limit applies it at once |
| `approval/CumulativeGateTest` (BE-15; SC-9) | `PAYMENT_RECORD` 100000 with a 24-hour window: 60000 applies; a second 50000 for the same customer by the same maker → 202 with the cumulative summary; another maker's 50000 applies; 25 hours later a 50000 applies; `windowHours = 0` measures each change alone; `PROMISE_UPDATE` walked down in 30000 steps is held on the fourth step; an ungated save reads no `approval_usage` row |
| `approval/ApprovalThresholdApiTest` (BE-15) | GET lists 8 with `windowHours`; PUT validation per field; 403 without the right in the region; audit row; warnings with one approver; **lowering an amount or adding a row → 200 at once; raising or deleting → 202 with a THRESHOLD_CHANGE, and the old limit still gates until another approver approves it; the maker cannot approve it; a mixed PUT applies the tightening and holds the rest** (SC-11); other approvers are notified of every change; **a new region starts with the default region's thresholds** (SC-4) |

### 8.7 Parity tests

- `AsOfParityTest` compares the version-root path with the live path for the same state: every filter operator, sort, summary, dashboard figure and detail (§8.4); capability flags are compared against "false when historical".
- `ConditionTest.andOnlyParityWithListEndpoint` compares the rule evaluator with the list endpoint for AND-only conditions.
- `RegionBookTest`'s single-region dashboard equality compares the region-split "collected" formula with today's formula.

There is no in-memory evaluator, so no evaluator-versus-database matrix is needed; that design was rejected.

### 8.8 Frontend tests (`FT/`, using `support/fake_backend.dart`)

Scope-carrying tests build their `Dio` with `backend.dioWith([viewScopeInterceptor(() => scope)])` (§7.8).

| File | Covers |
|---|---|
| `view_scope_test.dart` (FE-01) | `apiParams` empty when unset; canonical ordering; the exported interceptor on a bare Dio adds params to GET and POST only when set and never overrides an explicit param; `checkAsOf` cases (header equal, `live`, missing → `AsOfMismatch`); `FakeBackend.dioWith` puts the interceptor ahead of routing |
| `current_user_regions_test.dart` (FE-01) | Parse with and without `regions`; the `hasIn` fallback; `regionsWith`; `multiRegion` |
| `region_selector_test.dart` (FE-02) | Hidden for 0 or 1 regions and for customers; selecting refetches the list with `regionIds`; **the dashboard providers refetch with `regionIds`**; **the bell and inbox counts refetch with `regionIds`**; the as-of chip opens the picker bounded by `/api/history/range`; the banner appears; bulk actions hidden while historical; **the "Your book only in: SOUTH" strip shows when `bookRegionIds` is non-empty and `lockedFilters` is empty, and not otherwise** (B1 "POC book per region") |
| `as_of_screens_test.dart` (FE-02) | **Table-driven over the §7.4 sites** (customer, invoice, payment and promise detail; the promises and disputes tabs; dispute detail; the task and rule editors once they exist): no edit actions while historical; the banner; a 404 → "did not exist on …"; dashboard aging link uses the as-of date; the live strip when `asOf` is null in a historical view |
| `region_grants_editor_test.dart` (FE-03) | The exact PUT body; BOOK disabled without override; read-only for self; rows beyond the editor's own grants are read-only |
| `regions_screen_test.dart` (FE-03) | The regions list, the create form's POST body and field errors, make-default, and the Grants tab |
| `detail_gating_test.dart` (FE-03) | `canManage: false` hides Save even with `invoiceManage`; `canResolve` drives the dispute buttons; the move-region dialog body and its 400 "changes awaiting approval" message |
| `dashboard_coverage_test.dart` (FE-02) | `'MIXED'` → `Coverage.mixed`; `scope` parsed |
| `approvals_test.dart` (FE-04/05) | Invoice form on 202 → the "Sent for approval" snack, no email compose, navigation to `/approvals/41`; record-payment dialog 202 → pending result, no email prompt; invoice detail PATCH 202 → re-fetch and fields re-seeded; promise form and override 202; dispute approve 202 snack; bulk result with `pending`; approval detail buttons follow the flags (Approve posts; Reject refuses an empty note; `blockedReason` shows); the banner shown/hidden/anonymous; `pendingFrom` null on 200 and tolerant of missing fields; the list sends `view` as a scalar; **the list shows `statusAt` in historical mode**; the nav badge count (overridden provider); **the thresholds screen's exact PUT body (amounts and `windowHours`) and its 202 pending notice** |
| `tasks_test.dart` (FE-06) | Tab list query params (`entityType`, `entityId`, `page`, `size`); the create dialog uses `/api/assignees/context`; Mark done posts `{status:'DONE', version}`; `/my-tasks` hits `/api/tasks/mine`; the tab is hidden without TASK_VIEW and the tab controller is not reset; nav badge |
| `automation_condition_builder_test.dart` (FE-07a) | Builds `AND[leaf, OR[leaf, leaf]]` with exact JSON; edits a leaf; removes a group; never collapses two leaves with the same field and operator |
| `rules_screen_test.dart` (FE-07a) | Run now posts `/run` (with `recordIds` or `runAt` when chosen) and shows the QUEUED run; a non-owner administrator's Run now, Enable and Resume confirm and send `confirmOwnershipChange: true`; enable/disable; resume with `replay`; the health card and Requeue for AUTOMATION_ADMIN only |
| `automation_rule_editor_test.dart` (FE-07a/07b) | POST body shape with stable keys, `regionIds`, `catchUp` and the `MANUAL` trigger; the trigger kind shows time, day and zone; placeholder inserted at the cursor; the ownership 400 → confirm → resend; preview carries `regionIds`/`asOf` only when set (G11) and `evaluateAs` when toggled; Save disabled while historical; **07b: one case per action card's exact JSON (task, promise, dispute, email) and the email dialog's `from`/`to` tokens** |
| `run_detail_test.dart` (FE-07b) | Effects with links; hidden targets greyed with no label; `partial` note; Retry posts `/retry` |
| `send_email_placeholders_test.dart` (FE-07b) | The compose dialog's `PlaceholderButton` inserts `{{Invoice.Number}}` at the cursor; the 32 existing `send_email_dialog` tests are untouched and green |
| Existing 61 | Unchanged and green |

---

## 9. Implementation slice plan

### 9.0 Rules for every slice

**Backend slices are strictly sequential.** Frontend slices are sequential among themselves and depend only on §6, so they run in parallel with the backend stream.

**Before handing back, every backend slice must pass** (and report the counts):
- `cd backend && mvn -q test` — all tests, including the 119 existing ones and the slice's own.
- `cd mail-service && mvn -q -o test` — 24 tests (it should be untouched).
- `cd frontend && flutter analyze && flutter test` — only if the slice touched the frontend (backend slices must not).

**Every frontend slice must pass** `flutter analyze` (0 issues) and `flutter test` (61 existing tests plus its own).

**Size:** each slice is roughly 800–2,500 changed lines, including tests. The review found BE-04, BE-09 and FE-07 too large for one agent, so each is split in two (BE-04a/04b, BE-09a/09b, FE-07a/07b; critic finding CF-14). An agent whose slice still exceeds the bound reports it rather than cutting scope or handing back a red build.

**Scope discipline:**
- A slice may create or modify only the files it lists, plus the obvious compile fallout (for example, callers of a deleted overload).
- Each test named in a slice's acceptance list must exist and pass.
- No **baseline** test file (present at HEAD `321150e`) is edited, except `IntegrationTestBase`, `FT/support/fake_backend.dart` and the permitted `list_columns_test` override (§7.3.4). Test files created by earlier slices may be extended.

**The `@AfterEach historyIsInStep` check (from BE-07) applies to every later slice.** Any new write path must be ORM-based or call `HistoryTracker.touch`.

### 9.1 Backend stream

#### BE-01 — Foundations: clock, view scope, provenance, schema support
- **Goal.** P6 and P5 infrastructure, with no behaviour change except that a historical `asOf` fails closed (`AS_OF_UNSUPPORTED`).
- **Create:**
  - `B/common/time/{ClockConfig, AppClock, AppClockBridge, Today, WallClock}.java`
  - `B/common/scope/{AsOfPoint, ViewScope, ViewScopeHolder, AsOf, ViewScopeInterceptor, ViewScopeConfig, AsOfAware, AsOfIgnored, AsOfException, CodedBadRequestException, AsOfSupport, NoHistoryYet}.java`
  - `B/common/origin/{WriteOrigin (with actorUserId), WriteOrigins (with actor(...))}.java`
  - `B/common/schema/SchemaSupport.java`, `B/common/DatabaseErrors.java`, `B/common/Fit.java`
  - `T/MovableClock.java`
  - `T/common/time/{ClockTest, ClockReadGuardTest}.java`, `T/common/scope/ViewScopeTest.java`, `T/common/scope/ViewScopeInventoryTest.java` (as-of half), `T/common/schema/SchemaSupportTest.java`
- **Modify:**
  - Every file in the §5.1 table (clock); nothing else reads the clock (`ClockReadGuardTest`).
  - The 20 controllers (§5.2.3 annotations; the literal housekeeping list of §5.2.4).
  - `common/GlobalExceptionHandler`, `config/SecurityConfig` (:48), `common/query/PageResponse` (new fields, null or empty), `common/ApiError` (`WallClock.UTC`).
  - `audit/AuditLog` (5 nullable columns), `audit/AuditService` (stamping, `record(… regionId)`, `recordLinked`), `audit/AuditController` (DTO fields).
  - `email/EmailSchemaUpgrade`, `invoice/InvoiceSchemaUpgrade`, `email/mailservice/MailServiceEventHandler` (delegate).
  - `T/IntegrationTestBase` (`@Import(TestBeans.class)` with `RecordingMailTransport.Config` + `@Bean @Primary Clock testClock()` = `MovableClock.SHARED`; reset clears the holders, resets the clock and calls `AppClock.set(MovableClock.SHARED)`).
- **Depends on:** nothing.
- **Acceptance:** the 119 existing tests; `ClockTest`; `ClockReadGuardTest`; `ViewScopeTest`; `ViewScopeInventoryTest` (as-of classification); `SchemaSupportTest`.
- **Gotchas:**
  - R12 and D16: exactly one `@Import`.
  - JWT, webhook and `ApiError` code must use `WallClock.UTC`, never the bean.
  - Exactly one `Clock` bean in main code, not `@Primary`. `AppClock` starts as `Clock.systemUTC()` (CF-7).
  - `MovableClock` must follow real time until `set` (P7).
  - H2 ENUM widening (D12).
  - C-9: never inject `MailServiceEventHandler`.

#### BE-02 — Regions core: data, rights, principal, scope
- **Goal.** Every record has a region and every principal carries `RegionRights`. `ScopeResolver` is region-tiered and narrows sets by `ViewScope`. By-id reads use `requireVisible`. Creates lock the customer and copy its region.
- **Create:**
  - `B/region/{Region, RegionRepository, UserRegionGrant, UserRegionGrantRepository, AccessLevel, RecordScope, GrantRow, RegionRights, RegionAccessQueries, RegionSchemaUpgrade, RegionService (defaultRegion, requireActive), RegionGrantService (ensure)}.java`
  - Tests: `T/privilege/PrivilegeClassificationTest`, `T/region/RegionRightsTest`, `T/auth/PrincipalRightsTest`, `T/region/RegionSchemaUpgradeTest`.
- **Modify:**
  - Entities: `customer/Customer`, `invoice/Invoice`, `payment/Payment`, `promise/PaymentPromise`, `dispute/Dispute` (region, `@PrePersist` safety net), `user/User` (flag).
  - Rights: `privilege/Privileges` (REGION_*, `GLOBAL`, `REGION_LEVEL`, `requiredLevel`), `auth/AppUserDetails`, `auth/AppUserDetailsService`, `auth/CurrentUser` (additions; `canAssignPoc` reimplemented via rights, deleted in BE-04a), `customer/CustomerRepository` (`regionIdOf`), `user/UserRepository` (`findByIdForUpdate`).
  - Scope: `poc/ScopeResolver` (§5.6.4 without TASK), `common/query/PageResponse` (`of(…, Scope)`), the five `page()` methods and `DisputeController.list`.
  - By-id sites (§5.6.5 table).
  - Creates: `CustomerService.create` (with `CustomerDtos.CustomerCreateRequest.regionId`), `InvoiceService.create`, `PaymentService.record`, `PaymentPromiseService.create`, `DisputeService.open`: lock the customer and set the region.
  - `user/UserController.create`: the **bridge** grant DEFAULT/APPROVE/ALL for non-customer roles (§4.6; removed by BE-05; CF-19).
  - `config/DataSeeder` (grants for admin and cashier).
  - `T/IntegrationTestBase` (§5.6.2 helpers; reset tail from §3.12).
- **Depends on:** BE-01.
- **Acceptance:** all existing tests unedited; the four new tests.
- **Gotchas:**
  - C-4/R3: grants are loaded in `principalFor` inside `@Transactional(readOnly = true)`, with no LAZY collection on `User`.
  - F5: `@ColumnDefault("true")` plus the Java default false.
  - R10: `defaultRegion()` is never cached.
  - F1: lock order customer → sequence → invoice.
  - `EmailContextTest`'s count of 1 `customer_pocs` read must still hold.
  - R14: nothing goes into `lockedFilters` except the book chip.

#### BE-03 — Regions: "which regions am I asking about" in figures and DTOs
- **Goal.** Region fields and columns everywhere, the dashboard split and coverage, `ScopeInfo`, email bulk scope, the inbox's region narrowing, and the region half of the inventory test.
- **Create:** `T/region/RegionParamCoverageTest`, `T/region/RegionBookTest` (read-side cases), `B/email/EmailRegionPredicate.java`.
- **Modify:**
  - DTO region fields (§6.5, without flags): `customer/CustomerDtos` and `CustomerService.toDtos`, `invoice/InvoiceDtos`, `payment/PaymentDtos`, `promise/PromiseDtos`, `dispute/DisputeDtos`.
  - `common/query/TableSchemas` (`regionId`, `regionName` columns on five schemas); `TableSchemaController` (`Map.ofEntries`, `List` values, any-of `mayRead`).
  - Add `"region"` to the fetch lists.
  - `dashboard/{DashboardService, DashboardController, DashboardDtos}` (§5.12 split, coverage, `ScopeInfo`).
  - `email/EmailTargets.bulkIds`; `email/InboxController`, `InboxService` (list and unread count narrowed by `regionIds`, §5.6.7).
  - `ViewScopeInventoryTest` (region lists, with the inbox in `SETS`).
- **Depends on:** BE-02.
- **Acceptance:** `RegionParamCoverageTest` (every SETS endpoint narrowed so far, i.e. the record lists, dashboard, email bulk and inbox; the POC endpoints and notifications are in `PENDING_ROWS` for BE-04b, tasks for BE-12, approvals for BE-15, rules for BE-16; CF-12), `RegionBookTest` (read side, including single-region F2 equality), `ViewScopeInventoryTest`, all existing tests.
- **Gotchas:** R14; F2 (the VIEW gate is "any grant"); the dashboard's `idsInScope` passes the outer `cq`; `topPaying` merges per customer; emails on USER, ROLE and PRODUCT stay in every inbox answer.

#### BE-04a — Regions: writes and bulk
- **Goal.** The write half of the §5.6.6 inventory: MANAGE/APPROVE checks in the record's region, book-aware create checks (SC-12), the customer-first lock order on every multi-row write path (§5.11.5; CF-8, SC-7, SC-19), and bulk `resolveIds(access)`.
- **Create:** `T/region/RegionAccessMatrixTest` (write rows), `T/region/CustomerLoginRegionTest`.
- **Modify:**
  - Services: `CustomerService` (update/delete `requireIn`), `InvoiceService` (book-aware create; customer-first update and cancel), `PaymentService` (book-aware record), `PaymentPromiseService` (book-aware create, `getForWrite` with customer then promise lock, sweep/reevaluate/recompute lock per customer, `recomputeAll` regions), `PaymentPromiseRepository.findByIdForUpdate`, `DisputeService` (approve/deny customer-first).
  - Controllers: `InvoiceController`, `PaymentController`, `PaymentPromiseController`, `CustomerController` (`resolveIds(access)`, `canAssignPocAnywhere`).
  - `auth/CurrentUser` (delete `canAssignPoc`).
  - `T/region/RegionBookTest` (create cases).
- **Depends on:** BE-03.
- **Acceptance:** `RegionAccessMatrixTest` (write rows, with "DB unchanged after each refusal"), `CustomerLoginRegionTest`, `RegionBookTest` (create cases), all existing tests.
- **Gotchas:**
  - R4: the create paths.
  - 403 vs 404 (D15).
  - V11: a 403 message is replaced, so the "you may not change the POC" messages stay 400.
  - C-8: `hasIn` is false without a principal.
  - Keep the `CustomerController` bulk 403 (D-46).
  - The lock order: read the customer id unlocked, then lock the customer, then the row.

#### BE-04b — Regions: the POC book per region, holders, flags, documents, email, notifications
- **Goal.** Assignability per region, holders filtered by access, `RecordAccess` flags, region-aware document flags, the region check on named email recipients and senders (PC-2), region-tagged notifications that answer `regionIds` (PC-9), region-aware POC notices (SC-17), and deleted-customer history by region.
- **Create:** `B/region/RecordAccess.java`.
- **Modify:**
  - `poc/PocService` (`requireAssignable(region)`, `assignable(regions)`, holders filter, `notifyAssignee` filter, delete dead methods), `poc/PocController` (params, `my-scope` regions), `InvoiceController.assignableCheck`.
  - `document/DocumentTargets`, `DocumentService`; `email/EmailTargets` (`regionId`, `loadForSend`, holders filter), `email/EmailAddressing` (USER to/from region check), `EmailService` (`requireLive`).
  - `notification/{Notification (region_id), NotificationService (notifyAdmins(region), notifyHolders, notify(…, regionId), link guard), NotificationController (regionIds filter)}`; `promise/PaymentPromiseService.notifyBroken` (region filter and fallback).
  - `audit/AuditController` (`readOrGone`), `AuditLogRepository.findLatestRegionId`, `CustomerService.delete` region audit.
  - DTO flags (§6.5). Delete dead methods (R17).
  - `ViewScopeInventoryTest` (notifications to `SETS`); `RegionParamCoverageTest` (POC endpoints and notifications rows, removed from `PENDING_ROWS`); `RegionAccessMatrixTest` (flags, documents, email, seats rows); `RegionBookTest` (POC and holders cases).
- **Depends on:** BE-04a.
- **Acceptance:** the extended `RegionAccessMatrixTest`, `RegionBookTest` and `RegionParamCoverageTest`, all existing tests (the email tests pass because every fixture user holds a DEFAULT grant and manual email checks only the region, §5.6.7).
- **Gotchas:** R16: flags from the server; C-8; notification links ≤ 300; `notifyHolders` is used by BE-05, BE-13 and BE-15.

#### BE-05 — Regions: administration, grants, `/me`
- **Goal.** Region CRUD, grant management bounded by the granter (SC-5), user create with grants (absent → none), `/me` and `/login` `regions`, privilege `regionLevel`, and the REGIONS schema.
- **Create:**
  - `B/region/{RegionController, RegionGrantController, RegionDtos, RegionCreatedListener}.java` (+ `RegionService` CRUD, `RegionGrantService.replace` with the level and scope bound of §4.6)
  - `B/user/UserAccountService.java`
  - `T/region/RegionAdminTest.java`
- **Modify:** `user/UserController` (create delegates, replacing the BE-02 bridge; `UserDto.regionGrants`), `auth/AuthController` and `auth/dto/LoginResponse` (`regions`), `privilege/PrivilegeController` (`regionLevel`), `TableSchemas` (REGIONS) and `TableSchemaController` (`regions`), `audit/AuditController` (REGION), `ViewScopeInventoryTest` lists.
- **Depends on:** BE-04b.
- **Acceptance:** `RegionAdminTest`, all existing tests (`CustomerFieldHygieneTest` posts a user without grants and asserts only the trim).
- **Gotchas:** F7 (bulk delete before insert); self-edit refused (AUTH-03 parity); the Postgres-only partial index; V6 (`UserController` has no transaction, so `UserAccountService` is `@Transactional`); rows beyond the granter's reach must round-trip unchanged.

#### BE-06 — History storage model
- **Goal.** Version entities, converters, `HistoryModel`, `TypeHistory.snapshotAll`/`sameState` for all nine types (not `rehydrate` yet), `VersionStore`, `HistoryMeta`. No capture yet.
- **Create:**
  - `B/history/{VersionRow, HistoryModel, TypeHistory, LinkHistory, VersionStore, HistoryMeta}.java`
  - `B/history/versions/{CustomerVersion, ProductVersion, InvoiceVersion, PaymentVersion, PaymentAllocationVersion, PromiseVersion, EntityLinkVersion, DisputeVersion (without internal, origin, rule_id), CustomerPocVersion, EnumNames, ItemSnapshot (no productName)}.java`
  - `B/history/types/{Customer,Product,Invoice,Payment,Allocation,Promise,Dispute,Seat}History.java` (`rehydrate` throws `UnsupportedOperationException` until BE-09a/09b)
  - Tests: `T/history/{VersionShapeTest, VersionTablesHaveNoConstraintsTest, SnapshotTest}.java`
- **Modify:** none (new tables only).
- **Depends on:** BE-05, so `region` exists on every version table from the start.
- **Acceptance:** the three tests and all existing tests.
- **Gotchas:** `entity_id` mapped to attribute `id` (never `em.find` on a version class); converters, not `@Enumerated`; no FK (`ConstraintMode.NO_CONSTRAINT`); no table name containing `customer_pocs`; `items_json` holds only the items' own columns (CF-4).

#### BE-07 — History capture
- **Goal.** Every ORM write produces versions in the same transaction.
- **Create:**
  - `B/history/{HistoryListener, HistoryListenerRegistrar, HistoryWork (with the done guard), HistoryTransactions, HistoryWriter, HistoryTracker, HistoryReconciler (check only), ReconcileReport, HistoryFaults, NoHistoryFaults}.java`
  - `T/history/{HistoryTestSupport, HistoryTestHooks (implements HistoryFaults), HistoryCaptureTest, HistoryRollbackTest, HistoryTimeTest, HistoryConcurrencyTest, HistoryGuardTest, ModifyingQueryGuardTest}.java`
- **Modify:**
  - `T/IntegrationTestBase` (`clearVersions` at the start of the reset, `disputeRepository.deleteAll()` before customers, `@AfterEach historyIsInStep`, `@Bean @Primary HistoryTestHooks historyFaults()` in `TestBeans`).
  - `R/application.yml` (`app.history.strict: false`) and test yml (`strict: true`).
- **Depends on:** BE-06.
- **Acceptance:** all 119 existing tests pass **with** the `@AfterEach` consistency check, plus the six new tests. `ModifyingQueryGuardTest` passes on the unchanged `RowVersionUpgrade` (CF-10).
- **Gotchas:**
  - Bind the instant inside the writer, not at `beforeCommit`; read the actor through `WriteOrigins.actor`.
  - No owner lock for link-only changes.
  - Rollback leaves nothing, and a stale process from a rolled-back transaction does nothing (CF-22).
  - `REQUIRES_NEW` gives a distinct `txId`.
  - H2 lock timeout of about 2 s in the concurrency test.
  - The listener must ignore version entities and untracked types.
  - `CountingStatements` counts all threads.
  - Main code never references `T/` (CF-6).

#### BE-08 — History baseline, reconcile, range; the customer region move
- **Goal.** Existing databases get a baseline. Drift is repaired. `/api/history/range` works. The region move exists, with history touch, the move log and participants.
- **Create:**
  - `B/history/{HistoryBaseline, HistoryRange (not yet an AsOfSupport, CF-17), HistoryController, HistoryScheduler}.java` (+ `HistoryReconciler.reconcile`)
  - `B/region/{RegionMoveParticipant, CustomerRegionMoveService, CustomerRegionMoveRepository, CustomerRegionMove (+Repository; `customer_region_moves`), RegionMoveDtos}.java`
  - Tests: `T/history/{HistoryBaselineTest, HistoryReconcilerTest}.java`, `T/region/{CustomerRegionMoveTest, RegionConsistencyTest}.java`
- **Modify:** `customer/CustomerController` (`POST /{id}/move-region`, WRITE), `R/application.yml` (reconcile interval, scheduler pool 4 for now), test yml, `ModifyingQueryGuardTest` (allow the move repository), `ViewScopeInventoryTest`, `IntegrationTestBase` reset (`customerRegionMoveRepository`).
- **Depends on:** BE-07.
- **Acceptance:** the four tests and all prior tests.
- **Gotchas:**
  - PPD-01 lock order in the move.
  - `clearAutomatically` means re-reading the customer.
  - F3: `updatable=false`, so stale saves keep the new region.
  - The baseline runs before `DataSeeder`, holds the `history_meta` lock, and logs and continues on failure.
  - R10: nothing cached.

#### BE-09a — As-of reads I: context, executor, customers, invoices, products
- **Goal.** A historical `asOf` binds on AWARE endpoints; the executor swaps roots; customers (list, DTO sums and seats), invoices and products answer from versions (critic findings CF-5, CF-14).
- **Create:**
  - `B/history/{Temporal, Records, LiveReadGuard, Rehydration, Historical, HistoryQueries}.java`
  - `B/history/types/Historical{Customer,Invoice,InvoiceItem,Product,CustomerPoc}.java` (+ their `rehydrate` implementations)
  - `T/history/AsOfScenario.java` (D1–D4)
  - Tests: `T/history/{AsOfContextTest, AsOfListTest (customers, invoices, products), AsOfDetailTest (customer, invoice, product), HistoricalObjectsAreWriteProofTest, LiveReadGuardTest, AsOfQueryCountTest, TemporalConventionTest}.java`, `T/common/scope/AsOfReadOnlyTest.java`
- **Modify:**
  - `HistoryRange implements AsOfSupport`; delete `NoHistoryYet`. `ViewScopeTest`'s historical case now expects a binding.
  - `common/query/{TableQueryExecutor, ColumnDef, TableSchemas}` (§5.7.13–14).
  - `poc/ScopeResolver` (Temporal subqueries).
  - `customer/CustomerService` (subqueries; `get`; **`toDtos` as-of** with `HistoryQueries.sumOutstandingByCustomerAt`, `sumOverdueByCustomerAt`, `seatsAt`), `InvoiceService.get`, `ProductController GET /{id}`.
- **Depends on:** BE-08.
- **Acceptance:** the tests above and all prior tests.
- **Gotchas:**
  - Live SQL must be byte-identical: `AsOfQueryCountTest`'s live count is 0.
  - No fetch joins on version roots.
  - `getSqmJoins`, not `getJoins`.
  - Rehydration uses batch loaders only (no lazy loads).
  - `Today.date()` in as-of mode.
  - Unclassified GETs must not exist.
  - Exactly one `AsOfSupport` bean at every commit.

#### BE-09b — As-of reads II: payments, allocations, promises, disputes, remaining by-id sites
- **Goal.** The rest of the by-id and list reads answer from versions.
- **Create:**
  - `B/history/types/Historical{Payment,PaymentAllocation,PaymentPromise,Dispute}.java` (+ `rehydrate`)
  - Tests: `T/history/RoundTripTest.java`; `AsOfListTest` and `AsOfDetailTest` extended to payments, promises and disputes.
- **Modify:** `PaymentService.get`, `PaymentPromiseService.get`, `DisputeService.get`/describe, `PaymentController.credit`, `EmailTargets.loadUnscoped` and the PRODUCT branch of `loadScoped` (CF-23).
- **Depends on:** BE-09a.
- **Acceptance:** the extended tests, `RoundTripTest` and all prior tests.
- **Gotchas:** as BE-09a; allocations at T hang off a shallow `HistoricalInvoice`.

#### BE-10 — As-of reads III: remaining paths, dashboard, coverage
- **Goal.** Complete B3 for the record tabs and the dashboard, and add the parity and coverage guards.
- **Create:** `T/history/{AsOfDashboardTest, AsOfScopeTest, AsOfTabsTest, AsOfParityTest, AsOfEndpointCoverageTest}.java`.
- **Modify:**
  - `PocService.listFor`, `DocumentService.onRecord`/`toDto`, `EmailService.list`/`get`, `AuditController`/`AuditTimelineService`, `DashboardService`/`DashboardController` (§5.12 as-of).
  - Every DTO flag ANDs `!historical`.
- **Depends on:** BE-09b.
- **Acceptance:** the five tests (the parity matrix is generated from `TableSchema`, with capability flags compared against "false when historical", CF-13) and all prior tests.
- **Gotchas:** D1 backdated invoices; a payment voided after T is ACTIVE; `LiveReadGuard.allow` only for audit related-record discovery; C-1: every AWARE endpoint must differ from live in the coverage test.

#### BE-11 — Outbox publishing (A1)
- **Goal.** Every trigger-relevant write enqueues deduplicated events in `beforeCommit`, each tied to its transaction's history instant. No consumer yet.
- **Create:**
  - `B/automation/event/{AutomationEvent, AutomationEventRepository, AutomationEvents, PendingEvents}.java`
  - `B/automation/rule/TriggerEntity.java`
  - `B/automation/run/AutomationMoveParticipant.java` (M1: two events)
  - `B/history/versions/HistoryTransactionRow.java` (+ repository)
  - Tests: `T/automation/event/{OutboxPublisherTest, OutboxSchemaTest, TriggerCoverageTest}.java`
- **Modify:**
  - Call sites C1–C7, I1–I6, P1–P5 (§5.8.2) in `CustomerService`, `PocService`, `PaymentService`, `CreditLedger`, `InvoiceService`, `DisputeService`, including `invoiceMoney` (OUTSTANDING) and `children` on a rename (PC-5).
  - `history/HistoryTransactions` (`txId(em)`), `history/HistoryWork` (`needsInstant`), `history/HistoryWriter` (records `history_transactions`), `T/history/HistoryTestSupport` (clears it).
  - `T/IntegrationTestBase` (reset `automationEventRepository`).
- **Depends on:** BE-10. It needs history for the coverage oracle and the event point, and the region move for M1.
- **Acceptance:** the three tests and all prior tests.
- **Gotchas:**
  - R5: trivially safe rows; identifier lists are never fitted.
  - S7: ignore null ids; publish the payment from `record`.
  - Read-only transactions drop the buffer.
  - `REQUIRES_NEW` gives per-row buffers.
  - The save path must not read `automation_rules`.
  - Provenance is snapshotted per draft at publish time (D4).

#### BE-12 — Assignees and tasks (A6)
- **Goal.** A task entity with several live assignees, "my tasks", a record tab API, `forTasks`, versioning through `TaskHistory`, and a move participant, with assignee handling split so no injection cycle can form.
- **Create:**
  - `B/assignee/{RecordAssignee, RecordAssigneeRepository, AssigneeStore, AssigneeService, AssignedTo, VisibilityProbe (non-throwing), AssigneeDtos, AssigneeController}.java`
  - `B/task/{Task, TaskStatus, TaskEntityType, TaskRepository, TaskTargets, TaskService, TaskController, TaskDtos, TaskCascade, TaskSchemaUpgrade, TaskMoveParticipant}.java`
  - `B/auth/RunAs.java`
  - `B/history/versions/TaskVersion.java`, `B/history/types/{TaskHistory, HistoricalTask}.java`
  - Tests: `T/task/{TaskApiTest, MyTasksTest, TaskAsOfTest}.java`, `T/assignee/VisibilityProbeTest.java`
- **Modify:**
  - `Privileges` (TASK_*) and `DataSeeder`; `PrivilegeClassificationTest` lists.
  - `ScopeResolver` (`Kind.TASK`, `forTasks`); `TableSchemas` (TASKS) and `TableSchemaController`.
  - `AuditController` and `AuditTimelineService` (TASK; `customerView`, `Related.task` for staff only; SC-1).
  - `CustomerService.delete` (`taskCascade`); `UserController.pocReferenceCount`.
  - `email/EmailAddressing` (two helpers public static), `email/EmailTargets` (static `offeredRoles`).
  - `UserRegionGrantRepository.findStaffInRegions` (`/api/assignees/people`, SC-18).
  - `HistoryModel` (TASK); `IntegrationTestBase` reset; `ViewScopeInventoryTest`; `RegionParamCoverageTest` (tasks, people); `CustomerRegionMoveTest` (tasks as of a date; PC-16); `common/FieldLimits`.
- **Depends on:** BE-11.
- **Acceptance:** the four tests, `VersionShapeTest`/`RoundTripTest` extended to Task, `RegionConsistencyTest` extended to tasks, the extended `CustomerRegionMoveTest`, the context starts (CF-3), and all prior tests.
- **Gotchas:**
  - CF-3: domain services inject only `AssigneeStore`; `TaskCascade` stays repository-only.
  - CF-2: never catch an exception that crossed a joined `@Transactional` boundary and continue.
  - G7: `assigneeUserId` filterable.
  - R14: "mine" is not a locked filter.
  - The cascade is ORM, not bulk JPQL.
  - Notification links ≤ 300 characters.
  - `RunAs` restores all three contexts in `finally` and binds the actor.

#### BE-13 — Maker-checker: foundation, gate, decisions (INVOICE_CANCEL end to end)
- **Goal.** Pending changes and thresholds, the gate, 202 responses, approve/reject/withdraw with the approval origin bound across commit, and bulk `pending`, proven on invoice cancel.
- **Create:**
  - `B/approval/{ApprovalOperation, PendingStatus, ChangeOrigin, converters, PendingChange, PendingChangeRepository, ApprovalThreshold, ApprovalThresholdRepository, Outcome, Gated, MakerChecker, ApprovalContext, PayloadCodec, ChangeApplier, ApprovalService, ApprovalNotifier, PendingChangeViews, PendingChangeScope (requireVisible only), ApprovalController ({id}, approve, reject, withdraw), ApprovalConflictException, ChangeConflictException, PayloadException}.java`
  - `B/common/Outcomes.java`, `B/invoice/InvoiceChangeApplier.java`
  - Tests: `T/approval/{ApprovalTestBase, ApprovalSchemaTest, PayloadCodecTest, GateInvoiceTest (cancel part), ApproveFlowTest (cancel part)}.java`
- **Modify:**
  - `Privileges` (CHANGE_APPROVE, APPROVAL_THRESHOLD_MANAGE) and `DataSeeder`.
  - `region/RegionGrantService` (granting APPROVE needs `CHANGE_APPROVE`; `APPROVER_ADDED` notices; §4.6) and `RegionAdminTest` (those two cases).
  - `GlobalExceptionHandler` (409 for `ApprovalConflictException`).
  - `InvoiceService.cancelOrHold` (wrapper `cancel`); `InvoiceController.cancel` and bulk; `common/bulk/{BulkExecutor, BulkDtos}`.
  - `UserController.pocReferenceCount`; `IntegrationTestBase` (reset first); `ViewScopeInventoryTest`.
- **Depends on:** BE-12.
- **Acceptance:** the listed tests, plus "held publishes nothing; approved publishes `origin = APPROVAL` with `actor_user_id` = maker, and its versions carry the maker" (CF-1), plus the two-region cancel case (PC-13), plus all prior tests.
- **Gotchas:**
  - R7: return, never throw, to signal a hold.
  - TX-A/TX-B: never catch and continue inside TX-A; re-read in TX-B; bind the approval origin around TX-A.
  - Never let a 401 escape.
  - V11: self-approval is 400, not 403.
  - A stray threshold gates other tests, hence the reset order.

#### BE-14 — Maker-checker: every remaining operation
- **Goal.** INVOICE_CREATE, INVOICE_TERMS, PAYMENT_RECORD, PROMISE_CREATE/UPDATE/OVERRIDE, DISPUTE_APPROVE (with the credentials rule), and the closer.
- **Create:**
  - `B/payment/PaymentChangeApplier.java`, `B/promise/PromiseChangeApplier.java`, `B/dispute/DisputeChangeApplier.java`, `B/approval/PendingChangeCloser.java`
  - Tests: `T/approval/{GatePaymentPromiseTest, ApproveFailureTest, ApprovalConcurrencyTest, OutstandingChangeTest, DisputeApprovalGateTest, ExemptPathsTest}.java`; `GateInvoiceTest` and `ApproveFlowTest` completed.
- **Modify:**
  - `InvoiceService` (`createOrHold`: number after the gate; `updateOrHold`; `totalFor`), `PaymentService.recordOrHold` (`paidAt`), `PaymentPromiseService` (`createOrHold`, `updateOrHold`, `overrideOrHold`), `DisputeService` (parse/measure/apply split, `approveOrHold`, checker step with `credentials_set_by_user_id`, deny closes).
  - `user/User` (`credentialsSetByUserId`), `UserRepository.findCredentialsSetBy`, `CustomerService` (set it with a staff-set password), `AuthController.change-password` (clear it) (SC-10).
  - Controllers (202 on the eight endpoints).
  - `promise/PromiseDtos.CreatePromiseRequest` (`assignees` component, accepted and ignored until BE-19; 6-argument constructor; `@JsonCreator`).
- **Depends on:** BE-13.
- **Acceptance:** the tests above and all prior tests. `PromiseFiguresAndPocTest`, `DueDateTest`, `EmailTestBase` and `DocumentTestBase` compile unedited against the wrappers.
- **Gotchas:**
  - The canonical lock order (§5.11.5).
  - `due()` is computed before the setters.
  - `paidAt` is pinned.
  - Promise PUT is a full replace, so re-base.
  - `CreatePromiseRequest` Jackson: canonical constructor via `@JsonCreator`.
  - An approved dispute is never held twice.

#### BE-15 — Maker-checker: visibility, lists, as-of, lifecycle, thresholds, the escape hatches
- **Goal.** The approvals API, list flags, as-of, the History-tab decoration, customer redaction (including internal sources), closing on delete and move (with the move veto), threshold administration with held loosening, the region-move cool-down and the cumulative window.
- **Create:**
  - `B/approval/{ApprovalThresholdController, ApprovalThresholdService, ThresholdChangeApplier, PendingChangeMoveParticipant, RegionMoveCooldown, ApprovalUsage (+Repository), ApprovalUsageLedger, ApprovalHousekeeping}.java` (+ full `PendingChangeScope`, `ApprovalController` list/summary/count/for-record/operations/export)
  - Tests: `T/approval/{ApprovalVisibilityTest, ApprovalAsOfTest, ApprovalAuditTimelineTest, ApprovalLifecycleTest, ApprovalThresholdApiTest, RegionMoveCooldownTest, CumulativeGateTest}.java`
- **Modify:**
  - `ApprovalOperation` (`THRESHOLD_CHANGE`); `MakerChecker` (`effectiveThreshold`, cumulative window); `region/RegionService.create` (calls `RegionCreatedListener`).
  - `TableSchemas` (APPROVALS with the `Brief` keys, `pendingApproval` columns) and `TableSchemaController` (`approvals`).
  - DTOs (`pendingApproval`, filled in the four `page()` methods).
  - `AuditController`/`AuditTimelineService` (`pendingStatus`, redaction, `internal_source` rows dropped for customers).
  - `CustomerService.delete` (`closeForCustomer`); `ViewScopeInventoryTest`.
  - `AsOfScenario` (D5–D6); `AsOfEndpointCoverageTest` and `RegionParamCoverageTest` rows for approvals; `CustomerRegionMoveTest` (promise race).
  - `R/application.yml` (`app.approval.move-cooldown-days`).
- **Depends on:** BE-14.
- **Acceptance:** the seven tests, the extended `CustomerRegionMoveTest`, and all prior tests.
- **Gotchas:**
  - `PredicateResolver` has no context, so the `view` parameter is used instead.
  - Visibility subqueries pass the outer `cq`.
  - `withoutPocIdentity` must get the new staff-field names.
  - G12: add no invoice tile.
  - The usage query must not run on ungated saves.
  - A THRESHOLD_CHANGE has no customer.

#### BE-16 — Automation: rules, regions, conditions, placeholders, preview (A2, A4)
- **Goal.** Rule CRUD with revisions, ownership and optional regions; rule visibility; validation (including "not an update" warnings); conditions compiled to Criteria with the new filterable fields; placeholders; live and as-of preview (revision at T, `evaluateAs`); `EmailService.previewRendered`.
- **Create:**
  - `B/common/query/{Condition, ConditionJson}.java`
  - `B/automation/{AutomationSchemaUpgrade, AutomationProperties}.java`
  - `B/automation/rule/*` (§5.10.1, including `RuleVisibility`; not the run classes)
  - `B/automation/action/*` (models, the `validate`/`prepare`/`preview` halves of the handlers)
  - `B/automation/placeholder/*`, `B/automation/eval/*`
  - Tests: `T/common/query/ConditionTest`, `T/automation/placeholder/PlaceholderTest`, `T/automation/rule/RuleApiTest`, `T/automation/PreviewTest`
- **Modify:**
  - `Privileges` (AUTOMATION_*) and `DataSeeder`.
  - `TableSchemas` (AUTOMATION_RULES, AUTOMATION_RUNS; `paymentTerm` on INVOICES and CUSTOMERS; `overdueAmount` on CUSTOMERS) and `TableSchemaController`.
  - `AuditController` (RULE); `email/EmailText` (public); `email/EmailService` (`previewRendered`, `resolveForAutomation`).
  - `UserController` (`pocReferenceCount` rules); `UserRepository.findActiveStaffHolding`.
  - `IntegrationTestBase` reset; `ViewScopeInventoryTest` (rules list and summary in `SETS`); `RegionParamCoverageTest` (rules rows; `PENDING_ROWS` now empty); `AsOfEndpointCoverageTest` rows (rules list, preview).
- **Depends on:** BE-15.
- **Acceptance:** the four tests and all prior tests.
- **Gotchas:**
  - `FilterPredicates` is package-private, so `Condition` lives in `common.query`.
  - Value coercion is lazy, so run the dry-run count at save.
  - Never use `TableQuery.addFilter` semantics.
  - D10: the historical preview omits actions and evaluates the revision at T.
  - The saver must hold the action privileges in the listed regions.
  - `evaluateAs=OWNER` must never reveal a record the caller cannot see.

#### BE-17 — Automation: consumer core, event points, CREATE_TASK, run now
- **Goal.** Durable execution end to end for entity, CHILDREN and manual events, evaluated at their event points, with the owner rules of §5.10.7 and run/effect redaction.
- **Create:**
  - `B/automation/run/{AutomationRun, AutomationRunRepository, RunStatus, AutomationEffect, AutomationEffectRepository, EffectStatus, AutomationWorker, EventProcessor, RuleRunner (runForRecord, runForSet without slicing or replay), ActionExecutor, FailureClassifier, OwnerSession, OwnerUnavailableException, AutomationFaults, NoAutomationFaults, AutomationScheduler (poll), RunController, RunViews}.java`
  - `B/automation/event/EventPoints.java`
  - `B/automation/action/CreateTaskHandler.perform`
  - `T/automation/RecordingAutomationFaults.java`
  - Tests: `T/automation/run/{WorkerDurabilityTest, TriggerMatrixTest, FailureClassifierTest, RunAsOwnerTest, RunViewsTest, RunNowTest}.java`, `T/automation/action/TaskActionTest.java`
- **Modify:**
  - `RuleService` (suspension, resume without replay plus `skippedWhileSuspended`, `suspendOwnedBy`, `recheckOwner`, `recheckOwnersOfRole`, ownership-taking enable/resume/run); `UserController` (deactivation and role-change hooks); `RegionGrantService.replace` and `role/RoleController` (recheck hooks).
  - `TaskService.createForAutomation`.
  - `IntegrationTestBase` (`TestBeans` faults; reset runs and effects).
  - `R/application.yml` (§5.15 automation block; pool 6) and test yml (intervals).
- **Depends on:** BE-16.
- **Acceptance:** the seven tests and all prior tests. `RunNowTest` covers run-now with `recordIds`, `runAt`, `regionIds`, disabled rules, idempotency and serialisation, 202, and the ownership rule.
- **Gotchas:**
  - R2: `clearContext()` before `drain`.
  - S8: classify concurrency before infrastructure; list the non-`BadRequestException` permanent exceptions explicitly (CF-9).
  - Fencing on every finish.
  - The effect row commits with its effect; the customer lock is taken just before the write.
  - `runs.ensure` in its own `REQUIRES_NEW`.
  - Email dispatch happens only after commit (not in this slice).
  - The unpaged `run` is never used.
  - No coalescing.

#### BE-18 — Automation: schedules, SEND_EMAIL, manual placeholders, slicing, replay, breaker, health
- **Goal.** Daily and weekly triggers evaluated at their slots (with catch-up), email actions with the automation visibility rule, placeholders in manual emails, long-run slicing, supersede, resume with replay, rate limit, retention, and the admin health view.
- **Create:**
  - `B/automation/schedule/{ScheduleCalculator, ScheduleTicker}.java`
  - `B/automation/action/SendEmailHandler.perform`
  - Tests: `T/automation/schedule/{ScheduleCalculatorTest, ScheduleTickerTest}.java`, `T/automation/run/{SetRunTest, ResumeReplayTest}.java`, `T/automation/action/EmailActionTest.java`, `T/email/EmailPlaceholderTest.java`
- **Modify:**
  - `email/EmailService` (`queue` + `NotQueued`, `Queued`; placeholders in `send`, `preview` and `bulk`).
  - `RuleRunner` (YIELD, cursor, supersede, breaker, REPLAY); `RuleService` (resume `replay`).
  - `AutomationWorker` (heartbeat); `AutomationScheduler.tick`; `RunController` (health, requeue, retry).
  - `R/application.yml` (pool 7; `max-catch-up-slots`).
- **Depends on:** BE-17.
- **Acceptance:** the six tests and all prior tests (the baseline email tests are unaffected: their text contains no `{{`).
- **Gotchas:**
  - R15: an ordinary outbound row on the record, dispatch outside transactions, and a non-null `fromUserId`.
  - V5.
  - DST.
  - `catch_up` ONE gives one catch-up; ALL is capped.
  - A JPQL update on a rule must not bump its version.
  - R18: the pool.
  - Purge `history_transactions` with the events.

#### BE-19 — Automation: dispute and promise actions, holds
- **Goal.** CUSTOMER-target and internal disputes (hidden from customers on every path), dispute and promise co-assignees, CREATE_DISPUTE and CREATE_PROMISE (through `createOrHold`), and automation holds.
- **Create:**
  - `B/dispute/{DisputeSchemaUpgrade, InternalDisputeRequest}.java`
  - `B/automation/action/{CreateDisputeHandler, CreatePromiseHandler}.perform`
  - Tests: `T/automation/action/{DisputeActionTest, PromiseActionTest}.java`, `T/assignee/AssigneeApiTest.java`, `T/dispute/DisputeTargetWidenTest.java`, `T/automation/AutomationHoldTest.java`
- **Modify:**
  - `dispute/{DisputeTargetType (CUSTOMER last), Dispute (internal, origin, ruleId), DisputeService (§5.10.11: open guard `internal = false`, `internal_source` on holds), DisputeController (assignees), DisputeDtos}`.
  - `history/versions/DisputeVersion` (`internal`, `origin`, `rule_id`), `history/types/{DisputeHistory, HistoricalDispute}` (null `internal` → false) (CF-11).
  - `approval/ApprovalService` (check 5a; co-assignee notification after applying a held promise).
  - `promise/{PaymentPromiseService.setAssignees and createOrHold assignees, PaymentPromiseController, PromiseDtos}`.
  - `email/EmailTargets` (`disputeOwnFields` switch, CUSTOMER label and suggestion).
  - `ScopeResolver` (`internal = false` for customers).
  - `AuditTimelineService` (internal disputes filtered for customers).
  - `TableSchemas` (`assigneeUserId` on DISPUTES and PROMISES, `internal`).
  - `CustomerService.snapshotOf`.
- **Depends on:** BE-18.
- **Acceptance:** the five tests, the context starts (CF-3), and all prior tests.
- **Gotchas:**
  - S1: append CUSTOMER last.
  - S2/S3: switch statements silently no-op; edit them by hand.
  - D12: the H2 ENUM widen; `dispute_versions.internal` backfill.
  - Customer logins must never see internal disputes, nor changes held from them.
  - The owner cannot approve the change their own rule proposed, directly or as a pending change.

### 9.2 Frontend stream (depends only on §6; sequential among themselves)

#### FE-01 — Scope core and models
- **Goal.** `ViewScope` plumbing, `CurrentUser.regions`, the new privilege constants, `PagedResult` fields, the `NavBadge` enum, and the fake-backend extension. No visible UI.
- **Create:** `F/core/scope/{view_scope.dart, as_of.dart}`; `FT/view_scope_test.dart`, `FT/current_user_regions_test.dart`.
- **Modify:**
  - `F/core/api/api_client.dart` (interceptor).
  - `F/shared/models/{auth_models.dart, privileges.dart}`, `F/features/auth/auth_controller.dart` (parse `regions`; clear the scope on logout).
  - `F/core/table/{table_models.dart, table_providers.dart}` (watch the scope, `checkAsOf`).
  - `F/shared/widgets/app_shell.dart` (`NavBadge` enum; Inbox uses `inbox`).
  - `FT/support/fake_backend.dart` (`FakeReply` with status and headers; `dioWith(before)`; CF-15).
- **Acceptance:** `flutter analyze` 0; the 61 existing tests plus the two new ones.
- **Gotchas:**
  - G1: canonical strings, not collections.
  - G9: optional fields with const defaults.
  - G11: params only when set.
  - G23: export the interceptor for tests; tests use `dioWith`, never `backend.dio` twice.
  - G21: `DateFormat('yyyy-MM-dd')` on the local date.
  - Signature `viewScopeInterceptor(ViewScope Function() current)`.

#### FE-02 — Region selector, as-of picker, banner, read-only mode, dashboard
- **Goal.** The global controls, and historical read-only behaviour app-wide.
- **Create:** `F/shared/widgets/{region_selector.dart, as_of_chip.dart, as_of_banner.dart}`; `FT/{region_selector_test.dart, as_of_screens_test.dart, dashboard_coverage_test.dart}`.
- **Modify:**
  - `app_shell.dart` (actions and banner).
  - Every gating site in §7.4, and the folded permission providers.
  - `data_table_scaffold.dart` (hide bulk while historical; live strip; the "Your book only in" strip).
  - `dashboard_{providers,screen,charts,tables}.dart` (scope watch, `Coverage.mixed`, `asOf ?? todayUtc()`).
  - `detail_scaffold.dart` (`notFoundMessage` historical text).
  - The detail and record-tab providers (watch plus `checkAsOf`); the notifications and inbox providers and counts (regions only).
- **Depends on:** FE-01.
- **Acceptance:** analyze 0; all tests.
- **Gotchas:** G4: the router must not watch the scope; G13: no fetch at build; G22: `myPocScopeProvider` autoDispose; G5.

#### FE-03 — Regions and rights UI
- **Goal.** Regions admin, the grants editor, the roles badge, region columns, per-record flags, create-flow region choice, and the move-customer dialog.
- **Create:** `F/features/regions/{regions_screen.dart, region_form_dialog.dart, region_detail_screen.dart}`, `F/features/users/region_grants_editor.dart`, `F/features/customers/move_region_dialog.dart`; `FT/{region_grants_editor_test.dart, regions_screen_test.dart, detail_gating_test.dart}`.
- **Modify:**
  - `router.dart`, `app_shell.dart` (Regions entry).
  - `users_screen.dart`, `user_detail_screen.dart`, `roles_screen.dart`.
  - Entity models (§7.2).
  - The customers, invoices, payments, promises and disputes list and detail screens (region column, flags).
  - `customer_detail_screen.dart` (move).
  - `customers_screen.dart` (region in create); `invoice_form_screen.dart`, `record_payment_dialog.dart`, `promise_form_dialog.dart` (customer picker filters, POC `customerId`).
  - `reference_picker.dart` (`region`, `staffUser`); `poc_providers.dart` (`my-scope` regions).
- **Depends on:** FE-02.
- **Acceptance:** analyze 0; all tests.
- **Gotchas:** G20: never `isAdmin`; G6; G16; R16: server flags first; the grants editor never offers more than the editor holds.

#### FE-04 — Approvals UI
- **Goal.** The approvals list and detail, the banner, the nav entry and badge, audit-panel support, and the thresholds screen (with `windowHours`).
- **Create:** `F/features/approvals/*` (§7.7.1); `FT/approvals_test.dart` (UI part, including `statusAt` and the thresholds PUT body).
- **Modify:** `router.dart`, `app_shell.dart` (Approvals entry, `NavBadge.approvals`), `status_chip.dart`, `audit_history_panel.dart` (`JsonDiffView` extraction, `CHANGE_*`, `stableKey`), and the five detail screens (the banner in `top`).
- **Depends on:** FE-03.
- **Acceptance:** analyze 0; all tests. `list_columns_test` is untouched (§7.3.4).
- **Gotchas:** G5; G13; G25; G1 (`view` as a scalar); REGION-type rows (THRESHOLD_CHANGE) have no customer.

#### FE-05 — 202 pending handling at every write site
- **Goal.** No save ever announces "saved" for a held change.
- **Create:** `F/core/api/pending_reply.dart`.
- **Modify:** the §7.6 sites (including the thresholds screen's 202); `FT/approvals_test.dart` (write-site cases).
- **Depends on:** FE-04.
- **Acceptance:** analyze 0; all tests.
- **Gotchas:** G14; G18 (re-seed); critic §3.11 (`['id']` parsing); G19.

#### FE-06 — Tasks UI
- **Goal.** Tasks list, "My tasks" with badge, the record tabs, the task dialog and detail, and the assignee widgets.
- **Create:** `F/features/assignees/*`, `F/features/tasks/*` (§7.7.2); `FT/tasks_test.dart`.
- **Modify:** `router.dart`, `app_shell.dart` (entries and badge), the customer, invoice and payment detail screens (tab), `status_chip.dart`, `field_limits.dart`, `audit_history_panel.dart` (TASK).
- **Depends on:** FE-05.
- **Acceptance:** analyze 0; all tests.
- **Gotchas:** G5; G7; G15; the 32 `send_email_dialog` tests stay untouched.

#### FE-07a — Automation UI I: rules list, editor, conditions, run now
- **Goal.** The rules list (Run now, Enable/Disable, Resume with replay, health), the rule editor's basics, trigger (including MANUAL and catch-up), regions, conditions, placeholder button and preview count and sample (with `evaluateAs`).
- **Create:** `F/features/automation/{automation_models.dart, automation_providers.dart, rules_screen.dart, run_now_dialog.dart, rule_editor_screen.dart, condition_builder.dart, placeholder_button.dart}`; `FT/{automation_condition_builder_test.dart, rules_screen_test.dart, automation_rule_editor_test.dart}`.
- **Modify:** `router.dart`, `app_shell.dart` (Automation entry), `status_chip.dart` (`RuleStatusChip`), `field_limits.dart`, `audit_history_panel.dart` (RULE).
- **Depends on:** FE-06.
- **Acceptance:** analyze 0; all tests.
- **Gotchas:** G17: a single-slot `UnsavedChanges`; never `addFilter` for group children; G11; the ownership confirmation on Enable, Resume and Run now for a non-owner administrator.

#### FE-07b — Automation UI II: actions, rendered preview, runs; dispute and promise assignment; email placeholders
- **Goal.** The action cards and email action dialog, rendered previews, the runs tab and run detail (with redaction), dispute and promise assignee UI, the CUSTOMER dispute target, and placeholders in the manual email compose.
- **Create:** `F/features/automation/{action_cards.dart, email_action_dialog.dart, rule_runs_tab.dart, run_detail_dialog.dart}`; `FT/{run_detail_test.dart, send_email_placeholders_test.dart}`; `automation_rule_editor_test.dart` extended (action JSON).
- **Modify:**
  - `rule_editor_screen.dart` (Actions section, rendered preview, Runs tab).
  - `dispute_detail_screen.dart`, `promise_detail_screen.dart` (assignees, badges).
  - `shared/models/dispute.dart` (CUSTOMER), `dispute_create_dialog.dart` (explicit target list).
  - `F/features/email/send_email_dialog.dart` (additive `PlaceholderButton`).
  - `status_chip.dart` (`RunStatusChip`).
- **Depends on:** FE-07a.
- **Acceptance:** analyze 0; all tests, including the 32 unchanged `send_email_dialog` tests.
- **Gotchas:** no existing widget key in `send_email_dialog.dart` changes; hidden effects show no label.

### 9.3 Critic risk → slice map

| Risk | Handled in |
|---|---|
| R1 (dropped params) | BE-01, BE-03, BE-10, FE-01 |
| R2 (background identity) | BE-12, BE-17 |
| R3 (detached principal) | BE-02 |
| R4 (union authorities widen writes) | BE-04a, BE-04b |
| R5 (outbox must not break the save) | BE-11 |
| R6 (trigger coverage) | BE-11 |
| R7 (maker-checker re-entry and exemptions) | BE-13, BE-14 |
| R8 (pending changes and triggers) | BE-11, BE-13 |
| R9 (enum CHECK widening) | BE-01, BE-19 |
| R10 (NOT NULL `region_id` migration) | BE-02 |
| R11 (tests and seeding) | every slice's reset and seeding |
| R12 (clock and as-of) | BE-01, BE-09a |
| R13 (live subqueries) | BE-09a |
| R14 (`lockedFilters` means book) | BE-02, BE-03, BE-12 |
| R15 (automation email) | BE-18 |
| R16 (stale frontend rights) | BE-04b, FE-03 |
| R17 (unscoped and dead paths) | BE-04b, BE-12 (`/api/assignees/people`) |
| R18 (capacity) | BE-17, BE-18 |

---

## 10. Out of scope, deferred items, open questions, top risks

### 10.1 Out of scope or deferred (with the path to add each later)

| Item | Why deferred | How it would be added |
|---|---|---|
| Rights as of a date (who could see what then) | Grants and roles have no history | Version `user_region_grants` and `role_privileges` through `HistoryModel` |
| History for configuration lists (users, roles, regions, grants, thresholds, notifications) | PRD ambiguity (Q1) | Register them as `TypeHistory`; they are AWARE lists afterwards |
| Region and as-of in shareable URLs | `RouteQuery.push` drops extra parameters (G3). Every deep link would need to carry them. | Add fields to `TableQuery` (route parameters, `==`) and initialise `viewScopeProvider` from the URL on entry |
| Per-region functions (e.g. Sales POC in North, Collection POC in South; poc-scope map's `user_region_roles`) | The design reads "the POC book is per region" as a per-region level plus an ALL/BOOK scope (Q21) | An additive nullable `role_id` on `user_region_grants`; `effective = (grant.role ?? user.role) ∋ p ∧ level` |
| Guards, placeholders and actions evaluated at the event point | They describe what is done now (D10); only the condition is evaluated at the point | Pass the event point into `RecordContexts.load` and the guard queries |
| Products per region; maker-checker on product prices | Products are a global catalogue (D18) | — |
| Region-prefixed invoice numbers | Numbering stays global | A sequence row per (region, day) |
| Tasks as document or email parents | Would widen `documents.entity_type`/`emails.entity_type` and the exhaustive Dart switches (G24) | `widen` calls plus the Dart switches |
| Email attachments in automation | No attachment path exists (§3.1 of the critic) | A contract change across backend and mail-service |
| Triggers on Task, Dispute, Promise or Document; delays; branching; templates | Out of scope in both PRDs | New `TriggerEntity` values plus publisher call sites |
| Expiry of stale pending changes; a pre-submit "will this be held?" check | Not required | A sweeper that marks CLOSED; `GET /api/approvals/check` |
| Purging old version rows | History is the product | A retention policy per type |
| RabbitMQ, Envers, `SKIP LOCKED`, ArchUnit | Rejected (P2; DR-asof §16; H2Dialect degrades `SKIP LOCKED`; ArchUnit is not cached) | — |

### 10.2 Open questions for the PRD owner (the design proceeds with the default shown)

| # | Question | Default taken |
|---|---|---|
| Q1 | Does "every list" include configuration lists (users, roles, regions, notifications, inbox)? | They answer **live** under as-of, labelled "Showing current data — history isn't kept for this list". Notifications and the inbox still answer `regionIds` (§5.6.7). |
| Q2 | Rights at T | **Current** rights, applied to the record's region and book at T |
| Q3 | Dates before history began | 400 `AS_OF_BEFORE_HISTORY`, not approximated |
| Q4 | Payment `paidAt` when approved later | The **request** time (the receipt date) |
| Q5 | Gate customer payment-term changes and promise cancels? | No: no money moves, and the customer PUT carries a password |
| Q6 | A region move while changes are pending | **Refused** unless the mover holds `CHANGE_APPROVE` in the source region; then the changes are closed and the makers notified. For 30 days after a move the stricter of the two regions' thresholds applies. |
| Q7 | Is a `CHANGE_APPROVE` holder approving a customer's dispute as proposed the checker step? | Yes. It applies at once, unless the approver last set that customer login's password (§5.11.8). |
| Q8 | Expiry of stale requests | None in v1 |
| Q9 | Are threshold changes themselves maker-checked? | **Loosening** (raise, shorten the window, delete) is held for another approver in the region; tightening applies at once. Every change is audited and notified to the region's approvers, with a warning when fewer than 2 approvers exist. New regions start with the default region's thresholds. |
| Q10 | Customer logins and pending changes | They see a pending marker on their own existing records, and never see pending creates or identities |
| Q11 | Rule evaluation timing | The condition is evaluated **as of the event**: the publishing transaction's instant, or the schedule slot / requested run time. Actions, guards and placeholders use current data. Preview can be asked as of a date. |
| Q12 | Promise with several assignees | One accountable Collection POC (book and broken-promise notice) plus live co-assignees |
| Q13 | Who may create disputes by automation | Owners holding DISPUTE_MANAGE in the region (only ADMIN by default) |
| Q14 | Seats and record POCs of people without access to a customer's new region | Kept, reported as warnings, and filtered out at use |
| Q15 | Does the region selector apply to deep links (by-id reads)? | No (I3). Grants are the boundary. |
| Q16 | Region and as-of selection persistence | Session-only; not persisted and not in the URL |
| Q17 | The "above threshold" boundary | Strictly greater than; equal amounts apply |
| Q18 | Which rule list filters work as of a date | `name`, `enabled`, `entityType`, `triggerKind` and sort by `name`. Others give 400. |
| Q19 | B1 says every record belongs to exactly one region. Do products? | No: products stay a global catalogue (D18). |
| Q20 | B2 says "any change above a threshold". Are product price changes and region moves gated? | No. Product prices have no region; moves are audited, refused while changes are pending (unless the mover can approve them), and followed by the threshold cool-down. |
| Q21 | What does "the POC book is per region" mean? | A per-region access level plus an ALL/BOOK record scope on each grant. Different POC **functions** per region (Sales POC in North, Collection POC in South) are deferred (§10.1). |
| Q22 | B3 "every list as of a date" for customer logins | The API honours `asOf` for them, but the as-of chip is shown to staff only. |
| Q23 | A5 "nothing is lost": missed schedule slots and suspended rules | Missed slots: one catch-up run by default; a rule can choose `catchUp: ALL` (one run per slot, up to 31). Suspension: resume acts on nothing from the suspended window by default and reports how many events were skipped; `replay: true` acts on them (within the 14-day retention). |
| Q24 | Splitting a change to stay under a threshold | The same maker's unapproved changes to the same customer within 24 hours (configurable per threshold; 0 disables) count together. |
| Q25 | Placeholders in hand-written emails | Yes: the compose dialog offers them and send, preview and bulk render them. |
| Q26 | May a manual email be addressed to a colleague who cannot see the record? | Only if they hold some access to the record's region; book-level visibility is not required (today's behaviour). Automation emails and all assignments require full visibility. |

### 10.3 Top risks and their mitigations

1. **Silently dropped `regionIds`/`asOf` (R1, C-1).**
   - Unknown parameters are ignored everywhere today, so one missing reader shows other regions' or today's data.
   - *Mitigation:* a single reader (D2); the fail-closed classification (unclassified GET → 400; unannotated write → 400 in historical mode); `ViewScopeInventoryTest` fails the build on any unclassified handler; the behavioural `RegionParamCoverageTest` and `AsOfEndpointCoverageTest`, where every AWARE endpoint must **differ** from live in the scenario; the frontend `checkAsOf` header verification.
2. **History capture correctness and write amplification.**
   - A missed write path gives wrong as-of answers. A capture bug must never fail a save.
   - *Mitigation:* capture at the Hibernate layer; `@AfterEach historyIsInStep` over the whole suite; strict in tests, tolerant plus the reconciler in production; `ModifyingQueryGuardTest`; a bounded 25–35 extra statements for the heaviest save; `AsOfParityTest`.
3. **Background identity and the detached principal (R2, R3, C-8).**
   - Consumers have no principal. The principal is detached on every request path.
   - *Mitigation:* `RunAs` + `principalFor` with grants loaded eagerly (one query); `clearContext()` in every consumer test; the failure classifier treats AccessDenied and BadCredentials as permanent; the gate throws `IllegalStateException` (never a 401) without a principal.
4. **Union authorities widening writes (R4).**
   - `@PreAuthorize` now means "somewhere".
   - *Mitigation:* the complete §5.6.6 write inventory, including the create paths that check nothing today; `RegionAccessMatrixTest` asserts 403/404 **and an unchanged database** after every refusal; server capability flags for the UI.
5. **Maker-checker re-entry, lock order and error mapping (R7).**
   - An approved dispute becoming pending again; deadlocks; a 401 logging the checker out; a held create consuming an invoice number.
   - *Mitigation:* `ApprovalContext` apply mode (every nested gate only verifies); the canonical lock order and `lockDomainRows`; TX-A/TX-B with an explicit exception map; the number is taken after the gate; `ExemptPathsTest`, `ApprovalConcurrencyTest` and `ApproveFailureTest`.
6. **Enum columns on existing databases (R9, D12).**
   - Hibernate on H2 emits native ENUM types, so widening must handle both dialects. Tests on create-drop never see it.
   - *Mitigation:* `SchemaSupport.widen` (H2 ENUM + PG CHECK); `DisputeTargetWidenTest` boots an `update`-mode database with the old ENUM; converters on every new append-heavy table.
7. **The shared test database and contexts (R11, R12).**
   - A third Spring context wipes the database; stray thresholds gate other tests; leaked disputes; the clock.
   - *Mitigation:* one `@Import(TestBeans)`; the §3.12 reset order; `MovableClock.SHARED` following real time until set, and `AppClock` re-installed before every test.
8. **Event-point evaluation depends on history capture.**
   - A rule's condition is evaluated on version tables at the event's instant; a capture gap would evaluate the wrong state.
   - *Mitigation:* `history_transactions` is written for every publishing transaction; a missing instant falls back to live evaluation with a WARN and a run message; `@AfterEach historyIsInStep`; `WorkerDurabilityTest` and `ScheduleTickerTest` cover outage cases.
9. **Capacity (R18).**
   - Scheduler pool of 3, Hikari pool of 5, the 5000-row cap.
   - *Mitigation:* pool 7 and Hikari 8; keyset paging for sets; the per-run cap and hourly breaker; sliced runs with a 10 s budget.

---

## Appendix A. Claims re-verified against the code for this integration

| # | Claim relied on | Evidence (HEAD `321150e` or scratchpad probe) |
|---|---|---|
| A1 | `PageResponse` is built only by `of` and `map`; no test constructs it | `B/common/query/PageResponse.java`; grep of `T/` |
| A2 | `ApiError(timestamp, status, error, message, path, fieldErrors)`; `error` can carry a code; the 403 message is replaced | `B/common/ApiError.java`, `GlobalExceptionHandler.java:57-61` |
| A3 | `Privileges.ALL` has 31 names; `TableSchemaController.VIEW_PRIVILEGE` is `Map.of` with exactly 10 pairs | `B/privilege/Privileges.java`, `TableSchemaController.java:21-31` |
| A4 | `AppUserDetails(User, authorities)` and static `buildAuthorities(User)` are constructed only in `AppUserDetailsService` and `IntegrationTestBase:134,141`; `CurrentUser.has` reads authorities, and `require` throws BadCredentials | grep; `B/auth/*` |
| A5 | Tests construct `CreatePromiseRequest` (6 args), `CreateInvoiceRequest` and `CreatePaymentRequest` directly, so request records need their old constructors | `T/promise/PromiseFiguresAndPocTest:78`, `T/email/EmailContextTest:297`, `EmailSendTest:262,289`, `DueDateTest:57,122` |
| A6 | Only `IntegrationTestBase` builds entities through builders (customers); `IntegrationTestBase` has a single `@Import(RecordingMailTransport.Config.class)` and does not reset disputes | `T/IntegrationTestBase.java:44,65-89,119-125` |
| A7 | `InvoiceService.create` takes the invoice number inside the builder before `buildLines`, and loads the customer unlocked | `B/invoice/InvoiceService.java:59-99` |
| A8 | `InvoiceNumbers.next()` reads `LocalDate.now(UTC)` and is MANDATORY | `B/invoice/InvoiceNumbers.java:32-44` |
| A9 | 33 main files read the clock through `Instant.now()`/`LocalDate.now(`/`LocalDateTime.now(`, plus `JwtService` through `new Date()`; the repository `@Param("now")` methods and `EmailDeliveryRollup.handedOff` take the instant as a parameter | `grep -rln` (critic finding CF-18), listed in §5.1 |
| A10 | CORS exposes only `Authorization` | `B/config/SecurityConfig.java:48` |
| A11 | Scheduler pool 3; `halt_on_error`; the test yml intervals | `R/application.yml:10-30`; `application-test.yml` |
| A12 | `EmailSchemaUpgrade.widen` looks only for CHECK constraints (PG and H2) | `B/email/EmailSchemaUpgrade.java:40-88` |
| A13 | **Hibernate 6.5 on H2 renders `@Enumerated(STRING)` as a native `enum(...)` column; `alter table … alter column … set data type enum(…)` widens it and keeps NOT NULL** | scratchpad `exp/main.out` (DDL shows `status enum (...)`), `enumprobe/EnumAlter` re-run: "before alter: Value not permitted…", "after alter rows=2", "nullable after alter=NO" |
| A14 | H2 2.2.224: `alter table t alter column region_id set not null` keeps the FK and enforces NOT NULL; the correlated backfill, `insert … select` with parameters, and `add column … boolean default false not null` all work | scratchpad `judge-regions/Probe` re-run: "FK kept 23506", "NOT NULL enforced 23502", "flag default rows=2" |
| A15 | A version entity's `id` attribute (not the `@Id`) resolves to `entity_id` in Criteria and HQL; entity joins are invisible to `getJoins()` but visible in `getSqmJoins()`; an unmapped subclass cannot be persisted; `NO_CONSTRAINT` gives 0 FKs | scratchpad `exp/main.out` items 1, 1b, 1c, 2b, 4, 4b, 5 |
| A16 | `FakeBackend` resolves every route with status 200; `list_columns_test`'s user holds invoice, dispute and POC privileges but not `changeApprove` or `taskView`; `pollUnreadCount` swallows errors and cancels its timer on dispose | `FT/support/fake_backend.dart`, `FT/list_columns_test.dart:22-38,108-159`, `F/features/notifications/notifications_providers.dart:18-35` |
| A17 | `dioProvider` interceptors: auth only; a 401 outside `/api/auth/*` logs out | `F/core/api/api_client.dart:17-52` |
| A18 | `BulkResult(action, requested, succeeded, failed, skipped, truncated, limit)` has no pending bucket | `B/common/bulk/BulkDtos.java:47-55` |
| A19 | `DashboardDtos` records carry only `coverage` plus data; `Coverage {ALL, BOOK, OWN}` | `B/dashboard/DashboardDtos.java` |
| A20 | `DisputeTargetType {INVOICE, PAYMENT}`; `EmailController` has GET `/delivery` and POST `/sync`, which the classification must cover | `B/dispute/DisputeTargetType.java`, `B/email/EmailController.java:74-80` |
| A21 | `EmailTargets` constructor-injects `CustomerService`, `InvoiceService`, `PaymentService`, `PaymentPromiseService` and `DisputeService`; `InvoiceService` and `PaymentService` inject `PaymentPromiseService`; no `@Lazy` or `allow-circular-references` exists | `B/email/EmailTargets.java:120-137`, `InvoiceService.java:53`, `PaymentService.java:51` (critic CF-3) |
| A22 | `EmailTargets.requireVisible` and `load` are `@Transactional(readOnly = true)` and call the scoped, exception-throwing `xService.get` | `B/email/EmailTargets.java:215-253` (critic CF-2) |
| A23 | `GlobalExceptionHandler.InvalidFieldsException` and `BulkExecutor.IneligibleException` extend `RuntimeException`, not `BadRequestException` | `GlobalExceptionHandler.java:79`, `BulkExecutor.java:27` (critic CF-9) |
| A24 | `RowVersionUpgrade` (not a `*SchemaUpgrade`) calls `statement.executeUpdate`; `PocSchemaUpgrade` and `GmailConnectionRepository` are the other native-SQL files | `B/common/RowVersionUpgrade.java:16,40`; grep (critic CF-10) |
| A25 | `EmailAddressing` checks a USER recipient or sender only for "active internal user" | `B/email/EmailAddressing.java:80, 89-92, 112-120` (critics PC-2, SC-3) |
| A26 | `EmailAccessTest` emails a sales POC about an invoice outside their book, so manual email must not require book visibility | `T/email/EmailAccessTest.java:341` |
| A27 | `AuditTimelineService.timeline` loads every dispute of the customer, invoice or payment with no internal filter; customer logins hold `AUDIT_VIEW` | `B/audit/AuditTimelineService.java:73-99`; `DataSeeder.java:78-91` (critic SC-1) |
| A28 | The dispute open guard counts any PENDING dispute on the target | `B/dispute/DisputeService.java:59-63` |
| A29 | Promise `update`, `cancel`, `override` and `clearOverride` read the promise with a plain `findById`; `PaymentPromise` has no `@Version`; `sweepOverdue` saves without a lock | `B/promise/PaymentPromiseService.java:106-206, 492-520` (critic SC-7) |
| A30 | `InvoiceService.update` and `cancel` lock the invoice row first and then call `reevaluateForCustomer`; dispute approve locks the dispute before `CreditLedger` locks the customer | `InvoiceService.java:104-106, 354-373`; `DisputeService.java:167-173`; `CreditLedger.java:61-88` (critic CF-8) |
| A31 | `FakeBackend.dio` is a getter that builds a new `Dio` whose only interceptor resolves every request | `FT/support/fake_backend.dart:18-37` (critic CF-15) |
| A32 | The only baseline test that posts a user asserts only the username trim | `T/customer/CustomerFieldHygieneTest.java:56-67` (critic SC-5) |
| A33 | `InboxController` has `POST /{id}/read`, `/{id}/unread`, `/mark-all-read` and `/bulk`; `EmailDtos.PeopleDto(userId, name, username, email, gmail)` exists | `B/email/InboxController.java:61-83`, `EmailDtos.java:90` (critic CF-16) |
| A34 | `TableSchemas.INVOICES` and `CUSTOMERS` have no `paymentTerm` or `overdueAmount` column; `outstanding` and `customerName` are derived cross-record columns | `B/common/query/TableSchemas.java:40-133` (critics PC-5, PC-11) |

---

## 11. Critique log

Three independent critics reviewed the previous revision. Each finding was checked against the code (Appendix A21–A34) and this document. **61 findings: 61 fixed, 0 rejected.** Five were fixed with one sub-point declined (PC-2, SC-7, SC-10, SC-12, SC-16); the reason is given on the line. IDs: PC = PRD coverage, CF = codebase fit, SC = security and consistency.

| ID | Lens | Severity | Verdict |
|---|---|---|---|
| PC-1 | prd-coverage | high | **Fixed.** Conditions are evaluated at the event point: `history_transactions(tx_id, instant)` written for every publishing transaction, `automation_events.history_tx_id`, `EventPoints`, SCHEDULED/MANUAL at `scheduled_for` (D10, §5.8.1, §5.10.6; BE-11 capture, BE-17 use). The deferral is removed from §10.1; Q11 updated. Tests: `WorkerDurabilityTest` outage case, `ScheduleTickerTest` slot case. |
| PC-2 | prd-coverage | high | **Fixed**, one sub-point declined. Manual email: USER to/from need a grant in the record's region (400). Automation email and every assignee: the full `VisibilityProbe`. Role-resolved assignees need the owner type's VIEW privilege. "No attachments" states how the documents rule is met (§5.6.7, §5.9.1, §5.10.5). *Declined:* book-level visibility for manual email, because `EmailAccessTest:341` emails a POC about an invoice outside their book (A26); raised as Q26. |
| PC-3 | prd-coverage | medium | **Fixed.** Coalescing is removed, since event-point evaluation needs each event's own point; provenance, `active_since` and EVERY_TIME are exact per event (§5.10.6). |
| PC-4 | prd-coverage | medium | **Fixed.** `ActionExecutor` does the read-only work (`prepare`: render, resolve, probe) before `findByIdForUpdate`, and re-checks the dedupe key and guards under the lock; lock-window test. The move publishes one CUSTOMER UPDATED plus one CHILDREN event, which the consumer fans out (§5.8.2 M1, §5.10.6). |
| PC-5 | prd-coverage | medium | **Fixed.** One definition in §5.8.3: `invoiceMoney` publishes CUSTOMER UPDATED(OUTSTANDING); a rename publishes CHILDREN(CUSTOMER_NAME); clock- and people-derived columns produce validator warnings. `TriggerCoverageTest` and `TriggerMatrixTest` cover it. |
| PC-6 | prd-coverage | medium | **Fixed.** `TriggerKind.MANUAL`; disabled (not suspended) rules can be run; `runAt`; idempotency only for an identical request; the request's `regionIds` stored and applied; `rules_screen_test` (§5.10.9). |
| PC-7 | prd-coverage | medium | **Fixed.** Saved-rule preview and matches evaluate the revision at T (404 before creation); `evaluateAs=OWNER` for the owner or an administrator, with records the caller cannot see hidden; runs report `statusAt` and the counters are documented as current (§5.10.9, §5.10.10). |
| PC-8 | prd-coverage | medium | **Fixed.** Optional rule regions (`automation_rule_regions`), `RuleVisibility`, `regionIds` narrowing of the rules list and summary (now SETS), and per-viewer redaction of run counters, run entities and effects (§5.10.7, §5.10.10). |
| PC-9 | prd-coverage | medium | **Fixed.** `notifications.region_id` and a `regionIds` filter on the list and bell count; `EmailRegionPredicate` on the inbox and its count; both moved to SETS (§5.6.7). |
| PC-10 | prd-coverage | medium | **Fixed.** Placeholders render in manual send, preview and bulk (BE-18) and the compose dialog gets a `PlaceholderButton` (FE-07b); `EmailService.previewRendered` is specified and owned by BE-16 only (§5.10.4, §5.10.5). |
| PC-11 | prd-coverage | low | **Fixed.** `paymentTerm` on INVOICES and CUSTOMERS and an as-of-correct `overdueAmount` on CUSTOMERS (§5.10.2, BE-16); `ConditionTest`, parity matrix. |
| PC-12 | prd-coverage | low | **Fixed.** `OutboxSchemaTest` (BE-11); `record_ids` and `region_ids` are TEXT and never fitted; 50 ids validated at the API and round-trip tested (§3.6). |
| PC-13 | prd-coverage | low | **Fixed.** Two-region cases in `GateInvoiceTest` and `GatePaymentPromiseTest` (§8.6). |
| PC-14 | prd-coverage | low | **Fixed.** `AssigneeApiTest` (BE-19); the UI assigns promise co-assignees after creation, stated in §5.10.12 and §7.7.3. |
| PC-15 | prd-coverage | low | **Fixed.** `TriggerMatrixTest` (BE-17) and the weekly tick case in `ScheduleTickerTest` (BE-18). |
| PC-16 | prd-coverage | low | **Fixed.** `CustomerRegionMoveTest` extended in BE-12 (tasks as of a date across a move) and `ApprovalAsOfTest` move case in BE-15. |
| PC-17 | prd-coverage | low | **Fixed.** §8.8 extended: rules screen, run detail, action-card JSON, thresholds PUT body, regions screen, book strip, dashboard and count refetch, `statusAt`, table-driven read-only sites, compose placeholders. |
| PC-18 | prd-coverage | low | **Fixed.** Q19–Q23 added to §10.2; `skippedWhileSuspended`, `resume` with `replay`, and `catchUp: ONE \| ALL` added (§5.10.7, §5.10.8), tested in `ResumeReplayTest` and `ScheduleTickerTest`. |
| CF-1 | codebase-fit | high | **Fixed.** `WriteOrigin` carries the actor; `ApprovalService` binds the approval origin around TX-A so it is in force at commit; `PendingEvents` snapshots provenance per draft (D4, §5.11.6); `ApproveFlowTest` asserts the maker as actor in versions and outbox. |
| CF-2 | codebase-fit | high | **Fixed.** `VisibilityProbe` is non-throwing and never calls `@Transactional` services; the "no catch across a joined transaction" rule is stated; `runs.ensure` uses its own transaction (§5.9.1, §5.10.6); `VisibilityProbeTest`, `TaskApiTest`. |
| CF-3 | codebase-fit | high | **Fixed.** Assignee handling is split into a repository-only `AssigneeStore` (domain services) and `AssigneeService` (controllers and handlers); `EmailTargets.offeredRoles` is static; "context starts" is in BE-12 and BE-19 acceptance (§5.9.1). |
| CF-4 | codebase-fit | high | **Fixed.** `items_json` and `ItemSnapshot` drop `productName`; the name at T comes from `product_versions` (§3.5, §5.7.15); product-rename capture case added. |
| CF-5 | codebase-fit | medium | **Fixed.** The as-of `CustomerService.toDtos` and its `HistoryQueries` move to BE-09a; `AsOfListTest` coverage is stated per slice (§5.7.17, §8.4). |
| CF-6 | codebase-fit | medium | **Fixed.** Main-code `HistoryFaults` with `NoHistoryFaults`; `T/history/HistoryTestHooks implements HistoryFaults` as a `@Primary` test bean (D16, §5.7.1, §5.7.7). |
| CF-7 | codebase-fit | medium | **Fixed.** `AppClock` defaults to `Clock.systemUTC()`, `set` is public, the reset re-installs `MovableClock.SHARED`, and drift-boot tests restore it (D5, §3.12, §8.1). |
| CF-8 | codebase-fit | medium | **Fixed.** Customer first on every multi-row path, including dispute approve/deny and invoice update/cancel (which the promise-lock fix of SC-7 would otherwise have inverted); §5.11.5 rewritten, with pending rows locked before the sequence documented. |
| CF-9 | codebase-fit | medium | **Fixed.** `FailureClassifier` lists `InvalidFieldsException`, `IneligibleException`, `NotQueued`, `ChangeConflictException` and `PayloadException` explicitly as PERMANENT; `FailureClassifierTest`; blank-subject case in `EmailActionTest`. |
| CF-10 | codebase-fit | medium | **Fixed.** The allow-list covers `**/*Upgrade.java` (including `RowVersionUpgrade`) and `common/schema/**`, and is kept next to the scan (§5.7.6). |
| CF-11 | codebase-fit | medium | **Fixed.** `internal`, `origin` and `rule_id` join `DisputeVersion` in BE-19 with the live columns; `DisputeSchemaUpgrade` backfills `dispute_versions.internal`; `HistoricalDispute` maps null to false (§3.5, §3.11). |
| CF-12 | codebase-fit | medium | **Fixed.** `RegionParamCoverageTest` rows are added by the slice that narrows each endpoint, with a literal `PENDING_ROWS` set; the POC rows land in BE-04b; `RegionBookTest` cases are split by slice (§8.2, §8.3, §9.1). |
| CF-13 | codebase-fit | medium | **Fixed.** `AsOfParityTest` excludes the capability flags and asserts them false when historical (§8.4). |
| CF-14 | codebase-fit | medium | **Fixed.** BE-04 → BE-04a/04b, BE-09 → BE-09a/09b, FE-07 → FE-07a/07b (§9). |
| CF-15 | codebase-fit | low | **Fixed.** `FakeBackend.dioWith(before)` and the test idiom (§7.8). |
| CF-16 | codebase-fit | low | **Fixed.** `automation-runs` columns, `RuleSummary`, `RunDto.entity`/`requestedBy`, the APPROVALS schema aligned to `Brief` (plus `decidedAt`), `promisedDate.base`, `/mine/summary`, `EmailDtos.PeopleDto`, promise assignees from BE-19, and the literal inbox housekeeping paths (§5.2.4, §6). |
| CF-17 | codebase-fit | low | **Fixed.** BE-08's `HistoryRange` does not implement `AsOfSupport`; BE-09a adds it and deletes `NoHistoryYet` (§5.2.2, §5.7.10). |
| CF-18 | codebase-fit | low | **Fixed.** The non-reading rows are removed, the count corrected to 33 files plus `JwtService`, and `ClockReadGuardTest` enforces the inventory (§5.1, A9). |
| CF-19 | codebase-fit | low | **Fixed.** A BE-02 bridge in `UserController.create` keeps today's DEFAULT grant until BE-05, where SC-5's rule (absent → no grants) takes over (§4.6). |
| CF-20 | codebase-fit | low | **Fixed** with PC-12 (TEXT, never fitted, 400 above 50). |
| CF-21 | codebase-fit | low | **Fixed** with PC-3 (no coalescing). |
| CF-22 | codebase-fit | low | **Fixed.** `HistoryWork` gets a `done` flag and runs only when it is the registered work of the current transaction (§5.7.1); `HistoryRollbackTest` OEMIV case. |
| CF-23 | codebase-fit | low | **Fixed.** The PRODUCT branch of `EmailTargets.loadScoped` goes through `Records` (BE-09b; §5.7.17). |
| SC-1 | security-consistency | high | **Fixed.** `AuditTimelineService` gets `customerView` (no internal disputes, no tasks, no internal-source `CHANGE_*`); `pending_changes.internal_source` excluded from `for-record`, `pendingApproval` and `pendingStatus` for customers; the customer open guard ignores internal disputes (D17, §5.11.9, §5.13, §5.10.11). |
| SC-2 | security-consistency | high | **Fixed** by the ownership option: enable, resume and run by a non-owner administrator take ownership first, so whoever makes a rule act is its owner, maker and opener and cannot approve the result; disable, delete, requeue and entity-run retry stay available (§5.10.7). |
| SC-3 | security-consistency | high | **Fixed** with PC-2. |
| SC-4 | security-consistency | high | **Fixed.** New regions copy the default thresholds; a move with pending changes is refused unless the mover holds `CHANGE_APPROVE` in the source; a 30-day cool-down applies the stricter threshold (D7, §5.11.3); `RegionMoveCooldownTest`, `ApprovalLifecycleTest`. |
| SC-5 | security-consistency | high | **Fixed.** Absent `regionGrants` gives no grants; granting is bounded by the granter's own grant (403); APPROVE needs `CHANGE_APPROVE`; other approvers are notified (§4.6, §6.3); `RegionAdminTest`. |
| SC-6 | security-consistency | medium | **Fixed** with CF-1. |
| SC-7 | security-consistency | medium | **Fixed**, one sub-point declined. Every promise write takes the customer, then the promise, `FOR UPDATE`, and the gate reads the region after the customer lock (§5.6.6). *Declined:* `@Version` on promises and disputes; with the pessimistic order no unlocked save remains, and a version column would add a `RowVersionUpgrade` backfill on two populated tables for no further guarantee. |
| SC-8 | security-consistency | medium | **Fixed** with PC-3. |
| SC-9 | security-consistency | medium | **Fixed.** The cumulative window (`approval_thresholds.window_hours`, `approval_usage`) counts the same maker's unapproved changes to the same customer (§5.11.3); `CumulativeGateTest`; Q24. |
| SC-10 | security-consistency | medium | **Fixed**, one sub-point declined. The checker step fails when the approver last set the customer login's password (`users.credentials_set_by_user_id`), and check 5a stops a rule owner approving a change held from their internal dispute (§5.11.6, §5.11.8). *Declined:* disabling the step whenever the field is non-null, which would disable it for every staff-created customer until they change their own password. |
| SC-11 | security-consistency | medium | **Fixed.** Loosening thresholds becomes a `THRESHOLD_CHANGE` pending change; tightening applies at once; every change notifies the region's approvers (§5.11.11); Q9 updated. |
| SC-12 | security-consistency | medium | **Fixed**, one sub-point declined. Invoice, payment, promise, internal-dispute and task creates use the book-aware `requireVisible(CUSTOMER)` (§5.6.6). *Declined:* a visibility check on listed `invoiceIds` of a payment, because a collection-only POC has no invoice tier yet legitimately allocates to the customer's invoices. |
| SC-13 | security-consistency | medium | **Fixed.** `RunViews` redacts effects, run entities and messages for viewers who cannot see the target; health stays administrator-only (§5.10.10); `RunViewsTest`. |
| SC-14 | security-consistency | medium | **Fixed.** `AUTOMATION_MANAGE` is in `RequiredPrivileges`; role changes, grant replacement and role privilege edits re-check owners (§5.10.7). |
| SC-15 | security-consistency | low | **Fixed.** A maker sees their change only while holding a grant in its region, and `Detail.current` is null for a caller who cannot see the record (§5.11.9). |
| SC-16 | security-consistency | low | **Fixed**, one sub-point declined. Run-now locks the rule row before its check. *Declined:* 409 for a request with different `recordIds`; PC-6 asked for a second run instead, which is what the design does. |
| SC-17 | security-consistency | low | **Fixed.** Broken-promise and POC-assignment notices are filtered by region access, with a PROMISE_MANAGE fallback (§5.6.7). |
| SC-18 | security-consistency | low | **Fixed.** `/api/assignees/people` is region-scoped through `findStaffInRegions` (§6.13). |
| SC-19 | security-consistency | low | **Fixed** with CF-8. |
| SC-20 | security-consistency | low | **Fixed.** `Fit.to` in the effect, run and pending-change factories; identifier lists are TEXT; a 150-character customer name is tested (§3.6, §3.8). |
