# Decision record: B2 maker-checker

**Decision:** the **service-gate** proposal wins. It is grafted with the strongest ideas from **command-registry** and with fixes for flaws found during verification. Everything an implementation agent needs is below; the proposals themselves are not needed.

Paths: `B/` = `backend/src/main/java/com/geneinvoice/`, `F/` = `frontend/lib/`, `T/` = `backend/src/test/java/com/geneinvoice/`. Line numbers are at HEAD `321150e`.

---

## 0. Verdict in one paragraph

The gate sits **inside the eight money-moving service methods**. It comes after authorisation and validation and before the first change to any managed entity.
- If the measured amount is strictly above the region's threshold for that operation, the method writes one `pending_changes` row and returns `Outcome.held(...)`. It changes nothing else, and the controller answers **202**.
- To approve, the same method is **called again, as the maker**, inside an `ApprovalContext`. The gate then only verifies. Every validation, lock, audit row, notification and outbox event runs exactly as on an ordinary save.

**Grafted from command-registry:**
1. A dispute approval can itself be the checker step.
2. Measures are direction-aware: shortening a due date is not gated.
3. A region move *closes* pending changes, so `region_id` on a pending row never changes.
4. `record_poc_user_id` controls who sees a pending create.
5. Pending-change makers and checkers are added to `pocReferenceCount`.
6. Warnings when a region has too few approvers.
7. The `blockedReason` field.
8. The `for-record` endpoint.

**Fixed after verification:**
1. Approver visibility: a notified approver could otherwise get 404.
2. Supersede applies only to the same operation.
3. A canonical lock order. Invoice create now locks the customer before the sequence row.
4. The `awaitingMe` filter becomes a `view` parameter, because `PredicateResolver` has no context.
5. `Map.of` has a 10-entry limit.
6. `NavEntry` is `const`, so its badge becomes an enum.
7. A `BadCredentialsException` must never escape an approve call: a 401 logs the checker out.
8. `CHANGE_*` audit rows for a dispute go on the dispute's target, not on DISPUTE.
9. `isDirty()` is dropped as a runtime guard.
10. A `PendingChangeCloser` is added to avoid bean cycles.

---

## 1. Evaluation

| Criterion | service-gate | command-registry |
|---|---|---|
| (1) PRD coverage | Full | Full |
| (2) Concurrency and failure | Sound. Pending-row lock, `@Version`, portable unique `outstanding_key`, clean FAILED vs 409 split. Two gaps: the lock-order cycle with a region move, and approvers who cannot see the record. | Sound but heavier. It runs every gated write **twice** and holds the sequence and customer locks inside a transaction that is certain to roll back. A two-transaction submit (txA roll back, txB insert) needs a re-lock and fingerprint recheck. Failed applies stay PENDING indefinitely. |
| (3) Fit with the codebase and pinned decisions | Good. Service-layer explicit calls match the audit and outbox style (critic X2). Every caller, including bulk and the P3 automation consumer, reaches the gate. | Weaker. Every routed write, including ungated ones such as the payment PATCH, now takes a customer lock, which changes existing concurrency. Customer PUT is gated, which needs a special password error. A new AOP aspect is strict in production and lenient in tests, so the guard is untested where it matters. ArchUnit is a new dependency not in `~/.m2`. |
| (4) Implementable in H2 slices | Yes. One operation at a time; the legacy wrapper keeps everything compiling. | Framework-first; almost nothing works until the gateway, registry, aspect and codec all exist. |
| (5) Volume and regression risk | About 8 methods refactored plus new classes. Controllers change only on 8 endpoints. | Every write controller changes, plus bulk handlers and customer PUT; the speculative-apply machinery; more existing behaviour changes. |

### Spot checks (source opened at HEAD `321150e`)

| # | Claim | Result |
|---|---|---|
| 1 | Invoice create takes the number before building lines | ✔ `InvoiceService.java:75` `.invoiceNumber(invoiceNumbers.next())`, before `buildLines` `:79`, `setTotal` `:81`, `save` `:82`, `creditLedger.applyTo` `:83`. The number must move after the gate. |
| 2 | Invoice update: version check, then setters | ✔ Version check `:115-118`; `before` `:119`; setters from `:121`; `due(...)` is computed inside the setter block `:123-131`, so it must be computed before the gate. |
| 3 | Cancel locks, checks, then mutates | ✔ `findByIdForUpdate` `:356`; checks `:359-367`; `setStatus` `:369`. |
| 4 | Payment record is mutation-free until `applyTo` | ✔ `lockCustomer` `:60`, targets sorted `:88`, builder `:90-97`, `applyTo` `:99`. `invoicesToPayFirst` (`PaymentPromiseService:423-445`) only reads. `Payment.onCreate` sets `paidAt` **only if null** (`Payment.java:59-64`), so pinning the request time is feasible. |
| 5 | Promise update is a full replace; reassign uses the canonical constructor | ✔ `:105-144`, first setter `:116`, notes overwritten `:118`, `reassignCollectionPoc` `:146-151` passes the current amount and date, so it measures 0. |
| 6 | Dispute approve: parse, then switch; row locked | ✔ `approve` `:91-117`, change JSON chosen `:94-96`, `snapshotTarget` `:98`, `mustBePending` uses `findByIdForUpdate` `:167-174`, `applyChange` parse and switch `:219-288`. |
| 7 | Bulk: `REQUIRES_NEW` per id; `BulkResult` built once | ✔ `BulkExecutor.java:20-25, 53-85`; the only constructor call is `:83`; `BulkDtos.BulkResult` `:47-55`. |
| 8 | 403 message replaced; 401 on bad credentials | ✔ `GlobalExceptionHandler` `denied` (403, generic text) and `badCreds` (401). A 401 logs the Flutter user out (`api_client.dart:48-52`, G26). **New finding:** the approve path must never let `BadCredentialsException` or `AuthenticationException` reach the handler. |
| 9 | Audit has only a `dispute_id` link | ✔ `AuditLog.java` columns; `AuditService.record` `:18-32`. |
| 10 | `TableSchemaController.VIEW_PRIVILEGE` | ✔ It is `Map.of(...)` with **exactly 10 entries**, the `Map.of` maximum (`:21-31`). Adding `approvals` needs `Map.ofEntries`, and "any of" needs a list value. **New finding.** |
| 11 | H2 unique constraint with NULLs | ✔ Probed H2 2.2.224: many NULLs are allowed; a duplicate non-null key is rejected (23505); nulling the key frees it. Postgres behaves the same by default. |
| 12 | `FakeBackend` always answers 200; Dio accepts 2xx | ✔ `fake_backend.dart:36`; no custom `validateStatus` in `api_client.dart`, so 202 resolves as success. Create sites parse `(res.data as Map)['id'] as int` (`record_payment_dialog.dart:125-126`, `invoice_form_screen.dart:192`). |
| 13 | Quick filters need a filterable schema column; custom predicates get no context | ✔ `data_table_scaffold.dart:747`. `PredicateResolver(spec, root, q, cb)` has no user or as-of context, so "awaiting me" cannot be a custom filter column. **New finding** (fixed by the `view` parameter). |
| 14 | `NavEntry` is a `const` list | ✔ `app_shell.dart:12-45`, and the badge is a hard-wired `unreadBadge` bool (`:235`). A provider cannot sit in a const list, so the badge becomes an enum. **New finding.** |
| 15 | `pocReferenceCount` | ✔ `UserController.java:235-240`. |
| 16 | `IntegrationTestBase` single `@Import` and reset order | ✔ `:43` and `:65-89`. |

---

## 2. PRD clauses, and how each is met

| Clause (quoted) | How it is met |
|---|---|
| B2: "Any change above a configurable threshold doesn't take effect on save." | The gate runs before the first mutation in 8 money-moving operations (§5.1). When held, the entity tables are untouched: no invoice number, no allocation, no credit move, no outbox event (§5.7), and the response is 202 with no record id. "Above" means `amount > threshold`; equal is applied. |
| B2: "It becomes pending and needs approval from someone else holding the approval right in that record's region." | A `pending_changes` row is written. Approval requires: the checker is not the maker (400); `CHANGE_APPROVE` is held **in the row's region** (403); the row's region equals the record's current region (moves close pending rows, §5.10). For automation the maker is the rule owner. For a customer-proposed dispute the customer is the maker (§5.11). |
| B2: "Thresholds are per region." | `approval_thresholds(region_id, operation, amount)`. No row means not gated. Admin API in §5.17. |
| B2: "A pending change is visible to everyone who can see the record, without having taken effect." | Visibility = anyone whose scoped read reaches the target record, plus the maker, plus approvers in the region (§5.13). Shown in the record banner, the list flag and filter, the History tab and the approvals inbox. Nothing is written to entity tables. |
| B1: "A user holds different rights in different regions." | `CHANGE_APPROVE` and `APPROVAL_THRESHOLD_MANAGE` are **regional** privileges, checked with `hasIn(p, regionId)`. |
| B1: "Every list, filter, count and dashboard figure becomes a question about which regions am I asking about." | The approvals list, summary, count and for-record calls are narrowed by `ViewScope.regionIds` (P5). Region is never put in `lockedFilters`. |
| B3: "Every list, report and rule evaluation can be asked as of a date — including which region a record belonged to then, and which approvals were outstanding." | Outstanding at T is `requested_at < T AND (decided_at IS NULL OR decided_at >= T)`. `region_id` on a pending row never changes, so it is also the region at T. The `pendingApproval` filter column is usable in A2 rule conditions and honours as-of (§5.14). |
| A5: "A rule can never slow down or break the user's save." | The gate adds one indexed select only when the amount is greater than 0. Held saves publish no events (§5.7). |
| A3 plus predecessor PRD: "Nothing an automation sends or assigns escapes existing permissions." | The consumer runs as the owner (P3), so an automation-created promise above the threshold is held with `maker = owner` and `origin = RULE`. The owner can never approve it. Approval re-runs every check as the owner (§5.12). |
| P5: as-of mode is read-only | Approve, reject and withdraw return 400 when `asOf` is set. The server returns `canApprove/canReject/canWithdraw = false`. |

---

## 3. Rejected alternatives

| Alternative | Why rejected |
|---|---|
| **Command-registry gateway with speculative apply** | Every gated write runs twice. The sequence and customer locks are held in a transaction that is certain to roll back. It needs a re-lock and fingerprint recheck across two transactions. Every routed write, including ungated ones, gains a customer lock (a behaviour change, P7 risk). Correctness depends on "not routed means exempt", guarded by an AOP aspect that is lenient in tests plus ArchUnit (a new dependency, not cached). |
| AOP or controller-filter gate | Cannot compute the measure (prices, balances, dispute parsing) or place the gate after validation and before mutation. Bulk and automation bypass controllers. |
| Pending status or shadow columns on the entity | Every list, count, dashboard, credit and allocation query would have to exclude it (dashboard map §8.9). |
| Applying by replaying a stored diff through repositories | Bypasses validation, PPD-01 locks, credit, promise re-evaluation, audit, the outbox and the maker's rights. |
| Signalling a hold with an exception and writing the pending row in `REQUIRES_NEW` | A second connection while row locks are held (Hikari pool is 5), control flow by exception, and it breaks bulk (R7). |
| Changing existing method signatures to return `Outcome` | Breaks `EmailTestBase`, `DocumentTestBase`, `DueDateTest` and internal callers (P7). |
| Applying as the checker | Wrong audit actor, wrong `createdByUserId`, wrong notification self-skip, and the maker's lost rights would go unchecked. |
| `@Version`-based conflict detection | Invoice versions move on every allocation; Payment and Promise have none. |
| A region-wide DEFAULT threshold row, or an OFF mode | A default must never silently start gating due-date edits. Per-operation rows, where absence means not gated, are explicit; the UI offers "same amount for all". |
| Keeping a change PENDING when its apply fails | A permanently inapplicable change would block the record's outstanding key and the inbox. Business failures go to FAILED; locks and concurrency give 409 and the row stays PENDING. |
| Payment `paidAt` set at approval time | Promise fulfilment and statements need the receipt date. Kept as an open question (§10). |
| De-duplicating identical pending creates | Two genuine identical cheques would silently become one: a lost payment. |
| Gating customer payment-term changes and promise cancel | No money moves, and the customer PUT carries a plaintext `password` (critic §3.9). Open question (§10). |
| A `Session.isDirty()` runtime assertion | It can trigger flush-time cascades. It is replaced by one "held leaves the record untouched" test per operation. |
| `GET /api/approvals/check` pre-submit preview | Not required by the PRD. Deferred. |
| `awaitingMe` and `mine` as custom filter columns | `PredicateResolver` gets no user context. They become a `view` request parameter. |

---

## 4. Contracts assumed from sibling decisions

Implement B2 **after** B1, P5 and P6 have landed. Where a sibling record uses different names, use its names.

| From | Needed by B2 |
|---|---|
| **B1** | `Region` entity with `id` and `name`. `region` on Customer, Invoice, Payment, PaymentPromise and Dispute (P4). `CurrentUser.hasIn(String privilege, Long regionId)`, `Set<Long> regionsWith(String privilege)` and `requireIn(String privilege, Long regionId)`, with grants eagerly loaded on the principal (C-4). `UserRepository.findActiveHoldersInRegion(String privilege, Long regionId)`. Region-aware `ScopeResolver.forX()`. The manage-in-region check (R4) at the top of each write method. The B1 region-move operation (it calls `PendingChangeCloser.closeForCustomer`, §5.10). Test helpers to create regions and grant roles or privileges per region. B1 must list `CHANGE_APPROVE` and `APPROVAL_THRESHOLD_MANAGE` as **regional** privileges. |
| **P5** | `ViewScope` with `regionIds()` and `asOf()`. A way to reject writes in as-of mode (called `viewScope.requireLive()` here; it throws `BadRequestException("Viewing as of … is read-only")`). A **thread-local override** that `ScopeResolver` and `DashboardService` consult before the request values, used by `RunAs`. The static as-of accessor that B3 gives the `overdue` custom filter, reused by the `pendingApproval` filter. The helper that turns an as-of date into the instant `T = start of (asOf + 1 day) UTC`. |
| **P6** | A `Clock` bean. `MovableClock` in `IntegrationTestBase`'s single `@Import`. |
| **P2** | The `Propagation.MANDATORY` event publisher called at the end of service write methods. The effect table with a unique key and an `outcome` column that can hold `HELD` plus `pending_change_id`. |
| **P3** | `RunAs` (defined in §5.4 if not already present) and the consumer's thread-bound run context (called `AutomationRunContext.current()` here, returning `Optional<Run(ruleId, eventId)>`). |

---

## 5. Design

### 5.1 Gated operations

"Above" means `amount.compareTo(threshold) > 0`. Amounts are `numeric(14,2)`. If `amount.signum() <= 0` the gate returns without querying.

| `ApprovalOperation` | `entityType` | New entry (old method kept as a wrapper) | Amount measured | Stored payload | Conflict check at apply (§5.9) | Gate placement |
|---|---|---|---|---|---|---|
| `INVOICE_CREATE` | INVOICE | `InvoiceService.createOrHold(CreateInvoiceRequest)` → `Outcome<Invoice>` | Σ `lineTotal` after `buildLines` | The request, with each line's `unitPrice` **pinned** to the resolved price. `invoiceDate` stays as sent (null stays null). | none (all rules re-run; amount must not grow) | after `invoice.setTotal(...)` (`:81`); before the customer lock, `invoiceNumbers.next()` and `save` |
| `INVOICE_CANCEL` | INVOICE | `cancelOrHold(Long id)` | `inv.getTotal()` (`paidAmount` must be 0) | `{"invoiceId": id}` | strict: `total`, `paidAmount`, `status` | after the checks `:359-367`; before `before` and `setStatus` `:368-369` |
| `INVOICE_TERMS` | INVOICE | `updateOrHold(Long id, UpdateInvoiceRequest)`. The gate is called **only when** the resolved due date or term differs from the current one. | `inv.getBalance()` if the proposed due date is after the current one (or the current is null); otherwise 0 | the request with `version = null` | three-way: `dueDate` and `paymentTerm` as resolved, plus `notes` and `salesPocUserId` when the request sets them | after the version check `:115-118`; before `:119` |
| `PAYMENT_RECORD` | PAYMENT | `PaymentService.recordOrHold(CreatePaymentRequest)` → `Outcome<Payment>` | `req.amount()` | the request as submitted | none | after the targets are sorted `:88`; before the builder `:90` |
| `PROMISE_CREATE` | PROMISE | `PaymentPromiseService.createOrHold(CreatePromiseRequest)` → `Outcome<PromiseDto>` | `req.amount()` | the request, with `collectionPocUserId` set to the resolved POC's id | none | after `resolveInvoices` `:83`; before the builder `:85` |
| `PROMISE_UPDATE` | PROMISE | `updateOrHold(Long id, UpdatePromiseRequest)` | `max(\|new − old\|, newDate after oldDate ? newAmount : 0)` | the full PUT request | three-way on `amount`, `promisedDate`, `notes`; on `collectionPocUserId` and `invoiceIds` when non-null; fields the request does not change are re-based on current values | after `before` `:114`; before `setAmount` `:116` |
| `PROMISE_OVERRIDE` | PROMISE | `overrideOrHold(Long id, PromiseStatus status, String reason)` | `status == BROKEN ? 0 : max(0, amount − fulfilledAmount)` | `{"status":…, "reason":…}` | strict: `statusOverridden`, `overrideReason` | after `before` `:187`; before `setStatus` `:189` |
| `DISPUTE_APPROVE` | DISPUTE | `DisputeService.approveOrHold(Long id, ResolveDisputeRequest)` → `Outcome<Dispute>` | invoice `cancel`: total. `replace_items`: \|newTotal − oldTotal\|. `update_notes`: 0. Payment `void`: amount. `update_amount`: \|new − old\|. `update_meta`: 0. | The request, with `appliedChangeJson` set to the effective change JSON (the proposed JSON when the typed one is blank) | strict: invoice target `total`, `paidAmount`, `status`, or payment target `amount`, `status` | after the change JSON is chosen, parsed and measured; before `snapshotTarget` `:98` |

**Legacy wrappers.** `create`, `update`, `cancel`, `record`, promise `create`, `update`, `override` and dispute `approve` keep their exact signatures and annotations. Each body becomes one line:

```java
return xOrHold(...).appliedOrThrow();
```

`appliedOrThrow()` on `Held` throws `IllegalStateException`. The wrapper is in the same transaction (self-invocation), so the pending row rolls back too: fail closed. Existing tests, test bases and internal callers keep compiling. `reassignSalesPoc` and `reassignCollectionPoc` keep calling the wrappers; they always measure 0.

**Refactors inside those methods:**

1. **`InvoiceService.createOrHold`:**
   - Remove `.invoiceNumber(...)` from the builder.
   - After the gate, run `customerRepository.findByIdForUpdate(customer.getId())`, then `invoice.setInvoiceNumber(invoiceNumbers.next())`, then `save`.
   - This makes invoice create take **customer before sequence**, the canonical order (§5.8). The later customer lock in `CreditLedger.applyTo` is re-entrant.
   - Use `clock.instant()` for the default `invoiceDate` (P6).
2. **`InvoiceService.updateOrHold`:**
   - Compute `Due proposed` (only when `req.dueDate() != null || req.paymentTerm() != null`) and `boolean dueChanges` **before any setter**.
   - Then gate if `dueChanges`.
   - The setter block uses `proposed`. Error precedence does not change: `due()` still throws `InvalidFieldsException` before anything is written.
3. **`DisputeService`:**
   - Split `applyChange` (`:219-288`) into:
     - `ParsedChange parse(String json)`: all existing validation and messages, including the `wholeNumber`/`decimal`/`text` extraction.
     - `BigDecimal measure(Dispute d, ParsedChange c)`.
     - `void apply(Dispute d, ParsedChange c)`: the existing switch.
   - Add `public BigDecimal InvoiceService.totalFor(List<LineInput> lines)`, which returns `totalOf(buildLines(new Invoice(), lines))`. It is pure: the invoice is transient and nothing is persisted.
4. **`PaymentService.recordOrHold`:**
   - Set `paidAt` explicitly: `ApprovalContext.current().map(Applying::requestedAt).orElse(clock.instant())`.
   - That is, the receipt time is the moment the cashier recorded it (open question §10.1).

### 5.2 Not gated, and exempt paths

**Not gated** (there is no monetary measure):
- Customer create, update and delete. Passwords are never gated or stored.
- POC seats, and single or bulk POC reassignment.
- Invoice PATCH that changes only notes or the Sales POC.
- Payment PATCH.
- Promise `cancel` and `clearOverride`.
- Dispute `open` and `deny`.
- Tasks, rules, emails and documents.
- Products, which are global and have no region.
- Region moves and threshold administration: they are audited, not gated, to avoid a bootstrap deadlock.

**Exempt system paths.** These never reach a gate, because `gate` is called only from the 8 entry methods:
- `sweepOverdue`, `recomputeAll`, `reevaluateForCustomer`, `evaluate`, `applyShares`
- `CreditLedger.applyTo` and `refund`
- `PaymentService.applyTo` and `reverseAllocations`
- The dispute internals `cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount` and the direct saves
- `*SchemaUpgrade` and `DataSeeder`
- Test fixtures

A defensive second layer: while `ApprovalContext` is active, **every** gate call on that thread only verifies (§5.5).

The **automation consumer is not exempt** (§5.12).

### 5.3 Data model (P1: new tables and nullable columns only; no dialect SQL; no enum CHECKs)

#### `pending_changes`

Entity `B/approval/PendingChange.java`, created by `ddl-auto`. The table has **no foreign keys to users, customers or entities**, so it never blocks a delete.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `operation` | varchar(40) | no | `ApprovalOperation` via an `AttributeConverter<ApprovalOperation,String>`. With a converter there is no CHECK constraint (R9); a schema test asserts this. |
| `status` | varchar(20) | no | `PendingStatus` via a converter: `PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, FAILED, CLOSED` |
| `entity_type` | varchar(30) | no | `INVOICE` / `PAYMENT` / `PROMISE` / `DISPUTE` (the operation's type) |
| `entity_id` | bigint | yes | null for creates |
| `entity_label` | varchar(80) | yes | snapshot, e.g. `INV-20260926-0003`, `Promise #12`, `Dispute #4` |
| `affected_type` / `affected_id` | varchar(30) / bigint | yes | The record whose money state changes, and where `CHANGE_*` audit rows go. It is the entity itself for invoice and promise operations, and the **dispute's target** for `DISPUTE_APPROVE`. It is null for creates. |
| `customer_id` | bigint | no | plain Long, no FK |
| `customer_name` | varchar(150) | no | snapshot |
| `region` | FK `regions(id)`, column `region_id` | no | Mapped **exactly as B1 maps `Invoice.region`** (expected `@ManyToOne(fetch = LAZY, optional = false)`). Copied from the customer at request time. **Never updated**: a region move closes the row. |
| `record_poc_user_id` | bigint | yes | The POC named in a create payload (Sales for invoices, Collection for payments and promises); used for visibility |
| `amount` | numeric(14,2) | no | measured |
| `threshold_amount` | numeric(14,2) | no | threshold in force at request time |
| `summary` | varchar(300) | no | fitted; `Money.format` used |
| `payload_json` | TEXT (`@Lob @Column(columnDefinition="TEXT")`) | no | sanitised request |
| `payload_version` | int | no | 1 |
| `base_json` | TEXT | yes | the view record of current values (null for creates) |
| `proposed_json` | TEXT | yes | the view record of proposed values |
| `outstanding_key` | varchar(60), **unique** `uk_pending_outstanding` | yes | `"<entityType>:<entityId>"` while PENDING on an existing record; null otherwise and for creates |
| `origin` | varchar(12) | no | `USER` / `RULE` (converter) |
| `rule_id`, `automation_event_id` | bigint | yes | |
| `maker_user_id` | bigint | no | |
| `requested_at` | Instant | no | from `Clock`, **never** `@PrePersist` |
| `decided_by_user_id` | bigint | yes | checker for APPROVED, REJECTED and FAILED; maker for WITHDRAWN and SUPERSEDED; null for CLOSED |
| `decided_at` | Instant | yes | set on **every** move to a final status, from `Clock` |
| `decision_note` | varchar(500) | yes | required on reject; the system reason for CLOSED |
| `failure_reason` | varchar(500) | yes | fitted |
| `superseded_by_id` | bigint | yes | |
| `result_entity_id` | bigint | yes | on APPROVED |
| `result_json` | TEXT | yes | the applied DTO |
| `version` | bigint `@Version` | | |

Indexes (in `@Table(indexes=…)`):
- `idx_pending_status_region (status, region_id)`
- `idx_pending_entity (entity_type, entity_id)`
- `idx_pending_affected (affected_type, affected_id)`
- `idx_pending_customer (customer_id, status)`
- `idx_pending_maker (maker_user_id, status)`
- `idx_pending_requested (requested_at)`
- `idx_pending_decided (decided_at)`

Entity methods (all set `outstandingKey = null`, `decidedAt`, `status`):
- `static PendingChange pending(Gated g, BigDecimal threshold, long makerId, ChangeOrigin origin, Long ruleId, Long eventId, String payloadJson, String baseJson, String proposedJson, Instant now)`
- `supersede(long makerId, Instant now)`
- `approve(long checkerId, String note, Instant now, Long resultId, String resultJson)`
- `reject(long checkerId, String note, Instant now)`
- `withdraw(long makerId, Instant now)`
- `fail(long checkerId, String reason, Instant now)`
- `close(String reason, Instant now)`

`PendingChangeRepository`:
- `findByOutstandingKey(String)`
- `@Lock(PESSIMISTIC_WRITE) @Query findByIdForUpdate(Long)`
- `@Lock(PESSIMISTIC_WRITE) findByCustomerIdAndStatus(Long, PendingStatus)`
- `@Lock(PESSIMISTIC_WRITE) findByOutstandingKeyForUpdate(String)`
- `countByMakerUserIdOrDecidedByUserId(Long, Long)`
- `List<Object[]> findOutstandingAffected(String affectedType, Collection<Long> ids)` returning `(affectedId, pendingId)` where `status = PENDING`

#### `approval_thresholds`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | bigint identity | no | |
| `region` | FK `regions(id)` | no | mapped as in B1 |
| `operation` | varchar(40) | no | converter, no CHECK |
| `amount` | numeric(14,2) | no | ≥ 0, whole cents |
| `updated_by_user_id` | bigint | yes | |
| `updated_at` | Instant | no | from `Clock` |

Unique constraint `uk_threshold_region_op (region_id, operation)`. No row means not gated. Nothing is seeded.

#### `audit_logs` additions (nullable)

- `pending_change_id bigint`, with index `idx_audit_pending`.
- `approved_by_user_id bigint`.

`AuditService.record(...)` (`:18-32`) stamps both from `ApprovalContext.current()` (`pendingChangeId`, `checkerUserId`). The 10 existing callers do not change.

New method:

```java
@Transactional
public AuditLog recordLinked(String entityType, Long entityId, String action,
                             Object before, Object after, Long userId, Long disputeId,
                             Long pendingChangeId, String reason)
```

#### Privileges

- Add `CHANGE_APPROVE` and `APPROVAL_THRESHOLD_MANAGE` to `B/privilege/Privileges.java`, both as constants **and** in `ALL` (`:54-71`).
- Mirror them in `F/shared/models/privileges.dart` as `changeApprove` and `approvalThresholdManage`.
- ADMIN receives them through `upsertRole(all)`. Do not add them to CASHIER, VIEWER or the POC roles. Regional grants come through B1's seeding path.

#### Test reset

`T/IntegrationTestBase.resetTransactionalData` (`:65-89`) adds `pendingChangeRepository.deleteAll()` and `approvalThresholdRepository.deleteAll()` as the **first** deletes. Add autowired fields for both repositories. **This is critical for P7:** a threshold left behind would gate other tests.

#### User deletion

`UserController.pocReferenceCount` (`:235-240`) adds `+ pendingChangeRepository.countByMakerUserIdOrDecidedByUserId(userId, userId)`, so such users are deactivated rather than deleted (critic §3.8).

### 5.4 Classes and signatures

Package `com.geneinvoice.approval` unless stated otherwise.

**`ApprovalOperation` (enum).**
- Fields: `String entityType`, `String viewPrivilege`, `String label`, `String measureLabel`.
- Values:
  - `INVOICE_CREATE("INVOICE", INVOICE_VIEW, "Create invoice", "Invoice total")`
  - `INVOICE_CANCEL(…, "Cancel invoice", "Invoice total")`
  - `INVOICE_TERMS(…, "Extend invoice due date", "Balance whose due date moves later")`
  - `PAYMENT_RECORD("PAYMENT", PAYMENT_VIEW, "Record payment", "Payment amount")`
  - `PROMISE_CREATE("PROMISE", PROMISE_VIEW, "Create promise", "Promised amount")`
  - `PROMISE_UPDATE(…, "Change promise", "Change in amount, or amount deferred")`
  - `PROMISE_OVERRIDE(…, "Override promise status", "Unfulfilled amount")`
  - `DISPUTE_APPROVE("DISPUTE", DISPUTE_VIEW, "Approve dispute", "Money the dispute moves")`
- `boolean isCreate()` is true for INVOICE_CREATE, PAYMENT_RECORD and PROMISE_CREATE.

**`PendingStatus` (enum).** Values as in §5.3, plus `boolean isFinal()`.

**`ChangeOrigin` (enum).** `USER`, `RULE`. There is one converter per enum.

**`Outcome<T>`:**

```java
public sealed interface Outcome<T> permits Outcome.Applied, Outcome.Held {
  record Applied<T>(T value) implements Outcome<T> {}
  record Held<T>(PendingChange change) implements Outcome<T> {}
  static <T> Outcome<T> applied(T v) { return new Applied<>(v); }
  static <T> Outcome<T> held(PendingChange c) { return new Held<>(c); }
  default T appliedOrThrow() {
    if (this instanceof Applied<T> a) return a.value();
    throw new IllegalStateException("Change held for approval (#" + ((Held<T>) this).change().getId()
        + "); call the ...OrHold variant");
  }
  default <R> Outcome<R> map(Function<? super T, ? extends R> f) { ... }
}
```

**`Gated`.** A record plus builder, built by the service:

```java
record Gated(ApprovalOperation op, Long entityId, String entityLabel, String affectedType, Long affectedId,
             Long disputeId, Long customerId, String customerName, Long regionId, Long recordPocUserId,
             BigDecimal amount, Object payload, Object base, Object proposed, String summary,
             boolean checkerStepSatisfied) {
  String outstandingKey() { return entityId == null ? null : op.entityType() + ":" + entityId; }
  String auditEntityType() { return op.isCreate() ? "CUSTOMER" : affectedType; }
  Long auditEntityId()     { return op.isCreate() ? customerId : affectedId; }
  static Builder create(ApprovalOperation op) { … }
  static Builder onRecord(ApprovalOperation op, Long id, String label) { … }
}
```

**`MakerChecker` (`@Component`).** `@Transactional(propagation = MANDATORY) Optional<PendingChange> gate(Gated g)`; algorithm in §5.5.

**`ApprovalContext` (final class, `ThreadLocal`):**
- `record Applying(long pendingChangeId, long checkerUserId, ApprovalOperation op, String outstandingKey, Long customerId, BigDecimal approvedAmount, Instant requestedAt)`
- `public static Optional<Applying> current()`
- `static <T> T applying(Applying a, Supplier<T> body)` is **package-private**. It saves the previous value and restores it in `finally`.

**`PayloadCodec` (`@Component`):**
- `String toJson(Object)`: uses the app `ObjectMapper`; removes, recursively, every property whose lower-case name contains `password`.
- `<T> T read(String json, Class<T> type)`: throws `PayloadException` on any Jackson error.
- `void validate(Object request)`: `jakarta.validation.Validator`; throws `PayloadException` listing the violations.

**`ChangeApplier` (interface; one implementation per domain):**

```java
public interface ChangeApplier {
  Set<ApprovalOperation> operations();
  void lockDomainRows(PendingChange pc);   // canonical order §5.8
  Long currentRegionId(PendingChange pc);  // the record's (or, for creates, the customer's) region now
  Outcome<?> apply(PendingChange pc);      // read payload, validate, conflict-check (§5.9), call xOrHold
  Long resultEntityId(Object applied);
  Object resultView(Object applied);       // DTO stored in result_json
}
```

Implementations:
- `B/invoice/InvoiceChangeApplier`
- `B/payment/PaymentChangeApplier`
- `B/promise/PromiseChangeApplier`
- `B/dispute/DisputeChangeApplier`

**`ApprovalService`:**

```java
PendingChangeDtos.Detail approve(long id, String note)
PendingChangeDtos.Detail reject(long id, String note)
PendingChangeDtos.Detail withdraw(long id)
```

`approve` is **not** `@Transactional`; it uses a `TransactionTemplate`. `reject` and `withdraw` are `@Transactional`.

**`PendingChangeCloser` (`@Component`).** Depends only on the repository, `AuditService`, `ApprovalNotifier` and `Clock`, which avoids bean cycles.

```java
@Transactional(propagation = MANDATORY) int closeForCustomer(Long customerId, String reason)
@Transactional(propagation = MANDATORY) int closeForRecord(String entityType, Long entityId, String reason)
```

`closeForRecord` skips the row being applied (`ApprovalContext` id).

**`ApprovalNotifier`.** `requested(PendingChange)`, `decided(PendingChange)` (§5.16).

**`PendingChangeScope` (`@Component`).** Visibility and view predicates (§5.13):
- `ScopeResolver.Scope forList(View view)`
- `void requireVisible(long id)` throws 404.

**`PendingChangeViews`.** `brief(pc)`, `detail(pc, caller)`, `heldReply(pc)`, `rows(page)`. User names are looked up in one batch; region names too; redaction is applied here.

**`ApprovalController`** serves `/api/approvals/**`. **`ApprovalThresholdController`** serves `/api/regions/{regionId}/approval-thresholds`.

**`B/auth/RunAs`.** Shared with A5; reuse it if A5 already created it.
- Signature: `<T> T as(long userId, Supplier<T> body)`.
- Loads the user with grants fetched (B1 repository method).
- If the user is missing, inactive or a customer login, throws `RunAs.InactiveUserException extends RuntimeException`.
- Saves `SecurityContextHolder.getContext()`, installs a **new** `SecurityContextImpl` holding `AppUserDetails` (built with `AppUserDetailsService.buildAuthorities`), and installs a ViewScope override: all regions granted, live, `asOf = null`.
- Restores both in `finally`.

**`B/common/Outcomes`:**

```java
static <T> ResponseEntity<Object> respond(Outcome<T> o, Function<T, ?> body, PendingChangeViews v)
```

- `Applied` → `ResponseEntity.ok(body.apply(value))`.
- `Held` → `ResponseEntity.accepted().location(URI.create("/api/approvals/" + id)).body(v.heldReply(pc))`.

**Exceptions:**
- `ApprovalConflictException extends BadRequestException`. A new `@ExceptionHandler` in `GlobalExceptionHandler` returns **409 with its message**. As a `BadRequestException` subclass it is *skipped* in bulk `eligibility`.
- `ChangeConflictException extends RuntimeException`.
- `PayloadException extends RuntimeException`.

### 5.5 The gate

```java
@Transactional(propagation = Propagation.MANDATORY)
public Optional<PendingChange> gate(Gated g) {
  Optional<ApprovalContext.Applying> applying = ApprovalContext.current();
  if (applying.isPresent()) {                                   // APPLY MODE: never holds
    ApprovalContext.Applying a = applying.get();
    boolean same = a.op() == g.op() && (g.outstandingKey() != null
        ? g.outstandingKey().equals(a.outstandingKey()) : Objects.equals(a.customerId(), g.customerId()));
    if (same && g.amount().compareTo(a.approvedAmount()) > 0)
      throw new ChangeConflictException("The amount grew from " + Money.format(a.approvedAmount())
          + " to " + Money.format(g.amount()) + " since the request");
    return Optional.empty();
  }
  if (g.amount().signum() <= 0) return Optional.empty();
  ApprovalThreshold t = thresholds.findByRegionIdAndOperation(g.regionId(), g.op()).orElse(null);
  if (t == null || g.amount().compareTo(t.getAmount()) <= 0) return Optional.empty();
  if (g.checkerStepSatisfied()) return Optional.empty();       // §5.11
  Long makerId = currentUser.idOrNull();
  if (makerId == null) throw new IllegalStateException(g.op() + " above threshold with no principal"); // fail closed; NOT BadCredentials
  Instant now = clock.instant();
  PendingChange superseded = null;
  if (g.outstandingKey() != null) {
    PendingChange open = pending.findByOutstandingKey(g.outstandingKey()).orElse(null);
    if (open != null) {
      if (!open.getMakerUserId().equals(makerId) || open.getOperation() != g.op())
        throw new ApprovalConflictException(g.entityLabel() + " already has a change awaiting approval (#"
            + open.getId() + ", " + open.getOperation().label() + " by " + nameOf(open.getMakerUserId())
            + "). It must be approved, rejected or withdrawn first.");
      open.supersede(makerId, now); pending.saveAndFlush(open); superseded = open;   // frees the unique key
    }
  }
  var run = automationRunContext.current();                    // P3; empty for users
  PendingChange pc = pending.save(PendingChange.pending(g, t.getAmount(), makerId,
      run.isPresent() ? ChangeOrigin.RULE : ChangeOrigin.USER, run.map(Run::ruleId).orElse(null),
      run.map(Run::eventId).orElse(null), codec.toJson(g.payload()), codec.toJson(g.base()),
      codec.toJson(g.proposed()), now));
  if (superseded != null) {
    superseded.setSupersededById(pc.getId());
    auditService.recordLinked(g.auditEntityType(), g.auditEntityId(), "CHANGE_SUPERSEDED", null, null,
        makerId, g.disputeId(), superseded.getId(), "Replaced by #" + pc.getId());
  }
  auditService.recordLinked(g.auditEntityType(), g.auditEntityId(), "CHANGE_REQUESTED",
      g.base(), g.proposed(), makerId, g.disputeId(), pc.getId(), g.summary());
  notifier.requested(pc);
  return Optional.of(pc);
}
```

**Rule for every gated method.** The order is always: authorise (B1 R4) → validate and compute → **gate** → mutate → side effects (audit, notify, reevaluate, **outbox publish**).
- On `Held` the method returns `Outcome.held(pc)` immediately and touches nothing else.
- The held path commits the pending row, its audit rows and the notifications in the maker's own transaction.

**Base and proposed view records** (small records in each domain; stored as JSON and compared field by field):
- `CancelView(BigDecimal total, BigDecimal paidAmount, InvoiceStatus status)`. Proposed has status `CANCELLED`.
- `TermsView(LocalDate dueDate, PaymentTerm paymentTerm, String notes, Long salesPocUserId)`.
- `PromiseView(BigDecimal amount, LocalDate promisedDate, Long collectionPocUserId, String notes, Set<Long> invoiceIds)`.
- `OverrideView(PromiseStatus status, boolean statusOverridden, String overrideReason)`.
- `DisputeTargetView(String targetType, BigDecimal total, BigDecimal paidAmount, String status, BigDecimal amount)`.
- Creates have a null base. Proposed is the pinned request, plus `total` and `lineCount` for invoices.

### 5.6 Response shapes and controllers

**Applied:** the existing **200** and the existing DTO, byte for byte (P7).

**Held: 202 Accepted**, `Location: /api/approvals/{id}`, and this body. It deliberately has **no top-level `id`**:

```json
{"status":"PENDING_APPROVAL",
 "message":"Above South's approval limit of ₹1,00,000.00 for Cancel invoice. It takes effect once someone else approves it.",
 "pendingChange":{"id":41,"status":"PENDING","operation":"INVOICE_CANCEL","operationLabel":"Cancel invoice",
   "entityType":"INVOICE","entityId":1203,"entityLabel":"INV-20260926-0003","customerId":77,"customerName":"Acme Ltd",
   "regionId":2,"regionName":"South","amount":120000.00,"threshold":100000.00,
   "summary":"Cancel invoice INV-20260926-0003 (₹1,20,000.00)","requestedAt":"2026-09-26T09:14:03.120Z",
   "requestedBy":{"id":9,"name":"Sam Sales"},"origin":"USER","approverCount":3,"supersededId":null,"canWithdraw":true}}
```

**Controllers** change their return type to `ResponseEntity<Object>` and call `Outcomes.respond(service.xOrHold(...), mapper, views)`:
- `InvoiceController.create`, `update` and `cancel` (`:81-98`)
- `PaymentController.create` (`:78-82`)
- `PaymentPromiseController.create`, `update` and `override` (`:81-106`)
- `DisputeController.approve` (`:66-71`)

Mappers are the existing DTO factories, e.g. `inv -> InvoiceDtos.InvoiceDto.from(inv, scopeResolver.canSeePoc())`.

**Bulk:**
- Add `record BulkPending(Long id, Long pendingChangeId, String summary)` to `BulkDtos`.
- Append `List<BulkPending> pending` as the **last** component of `BulkResult`. Update the only constructor call (`BulkExecutor:83`).
- Add to `BulkExecutor`:

  ```java
  @FunctionalInterface public interface OutcomeOperation { Outcome<?> apply(Long id); }
  public static OutcomeOperation eligibilityOutcome(OutcomeOperation op)   // BadRequestException → IneligibleException
  public BulkResult runOutcomes(BulkRequest req, List<Long> permitted, boolean truncated, OutcomeOperation op)
  ```

  `runOutcomes` runs each id in the same `REQUIRES_NEW` template: `Held` goes to `pending`, `Applied` to `succeeded`. The existing catch branches are unchanged.
- The existing `run(..., RecordOperation)` delegates to `runOutcomes(..., id -> { op.apply(id); return Outcome.applied(null); })`.
- `InvoiceController` bulk `CANCEL` becomes `bulkExecutor.runOutcomes(req, ids, truncated, BulkExecutor.eligibilityOutcome(service::cancelOrHold))`. `ApprovalConflictException` is therefore reported as **skipped** with its message.

### 5.7 Triggers and the outbox (R8)

- The P2 publisher call sits at the end of each method, after the gate. **A held request publishes no entity event.** The pending row is not an A1 trigger entity.
- On approval, the re-invoked method publishes inside the approval transaction with `actor = maker`.
- If the P2 table has `origin` and `pending_change_id` columns, the publisher stamps `origin = "APPROVAL"` and `pending_change_id` from `ApprovalContext`.
- A rollback discards the `beforeCommit` rows.

### 5.8 Canonical lock order

Every path takes locks in this order:

**dispute row → customer row → invoice rows (ascending id) → invoice-number sequence row → pending_changes rows**

- Maker paths already follow it:
  - cancel and terms: invoice → pending;
  - dispute approve: dispute → customer → invoices, or dispute → pending when held;
  - payment record: customer → invoices;
  - invoice create, after the §5.1 refactor: customer → sequence → customer (re-entrant).
- **Approve** follows it through `lockDomainRows`, then takes the pending row with `findByIdForUpdate`, then re-invokes the method; its locks are re-entrant or later in the order. The per-operation `lockDomainRows`:

  | Operation | Locks, in order |
  |---|---|
  | `DISPUTE_APPROVE` | dispute, then customer |
  | `INVOICE_CANCEL`, `INVOICE_TERMS` | customer, then invoice |
  | Creates and promise operations | customer |

- **Close paths** (region move and customer delete: customer → pending; dispute deny: dispute → pending) follow it too.
- Residual cross-path cycles, for example a B1 move updating disputes after the customer, are resolved by Postgres deadlock detection or H2's roughly 2 s lock timeout. Both surface as `ConcurrencyFailureException` → **409**, with nothing written. The approval transaction is fully retry-safe.

### 5.9 Approve, reject and withdraw

**Checker rules**, checked in this order inside the transaction after the locks:

| # | Check | Failure |
|---|---|---|
| 1 | `pendingChangeScope.requireVisible(id)` | 404 |
| 2 | `viewScope.asOf() == null` | 400 "Leave the as-of view to make changes" |
| 3 | status is `PENDING` | 409 `ApprovalConflictException("Already {status} by {name} at {time}")` |
| 4 | caller is not a customer login | 403 |
| 5 | `caller.id != pc.makerUserId` | **400** "You cannot approve a change you requested" (a 403's message would be replaced, V11) |
| 6 | `currentUser.hasIn(CHANGE_APPROVE, pc.regionId)` | 403 |

**`approve(id, note)`:**

```
assert !TransactionSynchronizationManager.isActualTransactionActive();  // like EmailDispatcher.dispatch
User checker = currentUser.require();
try {
  return tx.execute(s -> {                                   // TX-A, REQUIRED (the request has no transaction)
    PendingChange peek = pending.findById(id).orElseThrow(404);
    ChangeApplier applier = appliers.of(peek.getOperation());
    applier.lockDomainRows(peek);                            // §5.8
    PendingChange pc = pending.findByIdForUpdate(id).orElseThrow(404);
    checks(pc, checker);                                     // rules 1–6; these THROW STRAIGHT OUT (no FAILED marking)
    Outcome<?> out;
    try {
      if (!pc.getRegionId().equals(applier.currentRegionId(pc)))
        throw new ChangeConflictException("The record has moved to another region");
      out = runAs.as(pc.getMakerUserId(), () -> ApprovalContext.applying(
              new Applying(pc.getId(), checker.getId(), pc.getOperation(), pc.getOutstandingKeyAtRequest(),
                           pc.getCustomerId(), pc.getAmount(), pc.getRequestedAt()),
              () -> applier.apply(pc)));
    } catch (RuntimeException e) {
      if (isApplyFailure(e)) throw new ApplyFailed(reasonOf(e));   // leave the callback → TX-A rolls back
      throw e;                                                     // concurrency → 409; anything else → 500; both PENDING
    }
    Object value = out.appliedOrThrow();                     // Held is impossible in apply mode → programming error (500)
    pc.approve(checker.getId(), fit(note), clock.instant(), applier.resultEntityId(value),
               codec.toJson(applier.resultView(value)));
    auditService.recordLinked(auditType(pc), auditId(pc), "CHANGE_APPROVED", null, null,
                              checker.getId(), disputeId(pc), pc.getId(), note);
    notifier.decided(pc);
    return views.detail(pc, checker);
  });                                                        // commit; P2 beforeCommit inserts the events
} catch (ApplyFailed f) {
  return tx.execute(s -> {                                   // TX-B: re-read; NEVER reuse entities from TX-A
    PendingChange pc = pending.findByIdForUpdate(id).orElseThrow(404);
    if (pc.getStatus() == PENDING) {
      pc.fail(checker.getId(), fit(f.reason()), clock.instant());
      auditService.recordLinked(..., "CHANGE_APPLY_FAILED", ..., checker.getId(), ..., pc.getId(), f.reason());
      notifier.decided(pc);
    }
    return views.detail(pc, checker);                        // 200 with status FAILED (or whatever it now is)
  });
}
```

`pc.getOutstandingKeyAtRequest()` is the key recomputed from `entity_type` and `entity_id`, because the stored key column is nulled on decision.

**Implementation notes:**
- **Never catch and continue inside TX-A.** An exception that crossed a joined `@Transactional` proxy has already marked the transaction rollback-only.
- After a TX-A rollback, `JpaTransactionManager` clears the OEMIV-bound `EntityManager`, which is why TX-B re-reads.

**`isApplyFailure(e)`** is true for these, each with the reason text shown:

| Exception | FAILED reason |
|---|---|
| `BadRequestException` (and subclasses) | the exception message |
| `GlobalExceptionHandler.InvalidFieldsException` | field messages joined with "; " |
| `NotFoundException` | "The requester can no longer reach this record (…)" |
| `AccessDeniedException` | "The requester no longer has the right to make this change" |
| `AuthenticationException` (includes `BadCredentialsException`) | same as `AccessDeniedException` |
| `RunAs.InactiveUserException` | "The requester's account is no longer active" |
| `ChangeConflictException` | message |
| `PayloadException` | message |

`ConcurrencyFailureException` (optimistic, pessimistic, cannot-acquire-lock, deadlock loser) and `DataIntegrityViolationException` propagate as **409**, and the row stays PENDING. This mapping also guarantees that no 401 ever reaches the checker's browser (G26).

**Outcomes of `POST /api/approvals/{id}/approve`:**

| Situation | HTTP | Row |
|---|---|---|
| Applied | 200 detail, `status: "APPROVED"`, `result: {"entityType":"INVOICE","entityId":913,"link":"/invoices/913"}` | APPROVED |
| Business or conflict failure | 200 detail, `status: "FAILED"`, `failureReason` | FAILED (final; maker notified) |
| Lock, optimistic or integrity failure | 409 | PENDING |
| Not visible / as-of / already decided / customer / self / no right in region | 404 / 400 / 409 / 403 / 400 / 403 | unchanged |

**`reject(id, note)`** (`@Transactional`):
- Lock the pending row.
- Rules 1–4 and 6.
- `caller == maker` gives 400 "Use Withdraw for your own request".
- `note` is required, 1 to 500 characters; otherwise `InvalidFieldsException(Map.of("note", …))`.
- Result: REJECTED, audit `CHANGE_REJECTED`, maker notified.

**`withdraw(id)`** (`@Transactional`):
- Lock the pending row.
- Rules 1–3; the caller must be the maker (403 otherwise).
- Result: WITHDRAWN, `decided_by = maker`, audit `CHANGE_WITHDRAWN`.

**Conflict detection** (inside `ChangeApplier.apply`, after the locks, before calling the method). There are three layers:

1. **Rules run again because the method runs again.** This covers book and region scope, manage-in-region, `canAssignPoc`, active products, due-date bounds, "already cancelled", "has payments", `mustBePending`, and so on.
2. **Field checks:**
   - **Three-way** for `INVOICE_TERMS` and `PROMISE_UPDATE`. For each field f that the request changes (`proposed[f] ≠ base[f]`): if `current[f] ≠ base[f]` and `current[f] ≠ proposed[f]`, throw `ChangeConflictException("<Label> was changed by someone else")`.
   - For `PROMISE_UPDATE` the applied request is **re-based**. Each unchanged field takes its current value; `collectionPocUserId` and `invoiceIds` are sent as null (keep) unless the request changes them.
   - **Strict** for `INVOICE_CANCEL`, `PROMISE_OVERRIDE` and `DISPUTE_APPROVE`: the listed base fields must be equal now, otherwise `ChangeConflictException` naming the field and both values.
3. **The amount must not have grown** (the gate's apply mode).

**Payload evolution.** `ObjectMapper` ignores unknown properties, and missing components arrive as null. `codec.validate(request)` enforces constraints added since the request was made. A violation or deserialisation error is a `PayloadException`, which makes the change FAILED.

**Dates at approval:**
- `invoiceNumbers.next()` runs in TX-A, so the prefix is the **approval** day (P6, `InvoiceNumbers.java:34` reads the `Clock`). Rejects leave no gaps.
- A null `invoiceDate` becomes the approval instant.
- `Payment.paidAt` is the **request** instant (§5.1, point 4).

### 5.10 One outstanding change per record; closing

- **At most one PENDING change per existing record**, enforced by the unique `outstanding_key`.
- Same maker and **same operation** → the old change is SUPERSEDED (`superseded_by_id` set; its `decided_at` equals the new `requested_at`). The 202 body carries `supersededId`.
- Same maker with a different operation, or a different maker → 409 `ApprovalConflictException`, which bulk reports as *skipped*.
- A true race between two makers → a unique violation → 409 "This change conflicts with existing data".
- Ungated writes are never blocked; interactions are resolved at apply time.
- Creates have no key; several pending creates per customer are allowed.

**Closing (status CLOSED, `decided_by = null`, note = reason, maker notified with `APPROVAL_CLOSED`):**

| Trigger | Call |
|---|---|
| `CustomerService.delete` (`:299-320`) | `closer.closeForCustomer(id, "Customer deleted")` (after `requireInBook`) |
| B1 region move | `closer.closeForCustomer(id, "Customer moved from <A> to <B>")` inside its transaction, after it locks the customer |
| `DisputeService.deny` | `closer.closeForRecord("DISPUTE", id, "Dispute denied")` after `mustBePending` |
| `DisputeService.approveOrHold`, applied path | `closer.closeForRecord("DISPUTE", id, "Dispute resolved")` |
| `InvoiceService.cancelOrHold`, applied path | `closer.closeForRecord("INVOICE", id, "Invoice cancelled")` |

The "Customer deleted" message follows the audit convention. The two applied-path closes skip the row currently being applied.

A threshold change never touches existing rows.

### 5.11 Dispute approval as the checker step (R7)

In `approveOrHold`, the service computes the following and passes `checkerStepSatisfied(checkerStep)` into `Gated`:

```java
boolean asProposed = req == null || isBlank(req.appliedChangeJson())
    || objectMapper.readTree(req.appliedChangeJson()).equals(objectMapper.readTree(d.getProposedChangeJson()));
boolean checkerStep = asProposed
    && !caller.getId().equals(d.getOpenedByUserId())
    && currentUser.hasIn(Privileges.CHANGE_APPROVE, d.getRegionId());
```

A JSON parse error here is a `BadRequestException("The change is not valid JSON")`, the same as today.

- A customer-proposed change, approved as proposed by a `CHANGE_APPROVE` holder in the region, **applies at once**: maker = opener, checker = approver.
- Otherwise, if the amount is above the threshold, it is held with maker = the staff member who clicked Approve. The dispute stays `PENDING` meanwhile.
- On apply, `approveOrHold` runs as that maker. `mustBePending` runs again. The internals (`cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount`, direct saves) are never gated, and `ApprovalContext` passes any nested gate, so an approved dispute can never become pending twice.
- `CHANGE_*` audit rows go on the dispute's **target**, with `dispute_id` set (`AuditController` does not support DISPUTE, V15).

### 5.12 Automation (A3 / A5)

- The consumer calls `promiseService.createOrHold(...)` inside `RunAs.as(owner)` and its run context. The gate records `origin = RULE`, `rule_id` and `automation_event_id`.
- On `Held`, the consumer writes its effect row with `outcome = HELD` and `pending_change_id` **in the same transaction**. A redelivery hits the effect's unique key, so there is never a second pending row.
- The owner cannot approve it (maker = owner). Approval runs as the owner; if the owner is inactive the change is FAILED, consistent with P3.
- The consumer must call the action before changing any of its own managed entities in that transaction.

### 5.13 Visibility and the approvals API

`PendingChangeScope.forList(View view)` returns a `Scope` whose single `PredicateFactory` is the conjunction of the following. It follows the `DashboardService.idsInScope` subquery pattern (`:310-316`), passing the **outer** `cq`.

```
VISIBLE :=
     maker_user_id = me
  OR region_id IN regionsWith(CHANGE_APPROVE)                       -- approvers always see what they may decide
  OR (entity_id IS NOT NULL AND (
        (entity_type='INVOICE' AND entity_id IN ids(Invoice,        forInvoices()))
     OR (entity_type='PAYMENT' AND entity_id IN ids(Payment,        forPayments()))
     OR (entity_type='PROMISE' AND entity_id IN ids(PaymentPromise, forPromises()))
     OR (entity_type='DISPUTE' AND entity_id IN ids(Dispute,        forDisputes()))))
  OR (entity_id IS NULL AND (
        (operation='INVOICE_CREATE' AND region_id IN regionsWith(INVOICE_VIEW))
     OR (operation='PAYMENT_RECORD' AND region_id IN regionsWith(PAYMENT_VIEW))
     OR (operation='PROMISE_CREATE' AND region_id IN regionsWith(PROMISE_VIEW)))
      AND (record_poc_user_id = me OR customer_id IN ids(Customer, forCustomers())))
AND (ViewScope.regionIds() empty OR region_id IN ViewScope.regionIds())       -- never lockedFilters (R14)
AND view predicate:  AWAITING_ME → status='PENDING' AND maker_user_id <> me AND region_id IN regionsWith(CHANGE_APPROVE)
                     MINE        → maker_user_id = me
                     ALL         → true
AND as-of (ViewScope.asOf() set): requested_at < T AND (decided_at IS NULL OR decided_at >= T)
```

**Customer logins:**
- `GET /api/approvals` returns 403 for them.
- Through `for-record` and the History tab they see pending changes on **existing** records they can see, never pending creates.
- Their DTOs have `requestedBy`, `decidedBy`, `decisionNote` and `origin` set to null, and POC keys are stripped from `current` and `proposed`: any key whose lower-case name contains `poc`.
- Staff callers without `POC_VIEW` get the same POC-key stripping.

**`TableSchemas.APPROVALS`** (entity `"approvals"`, default sort `requestedAt,desc`):

| Column | Type | Notes |
|---|---|---|
| `id` | NUMBER | |
| `operation` | ENUM | `ApprovalOperation` names |
| `status` | ENUM | `PendingStatus` |
| `entityType` | ENUM | |
| `entityId` | NUMBER | |
| `entityLabel` | TEXT | |
| `customerId` | REFERENCE `customer` | not sortable |
| `customerName` | TEXT | |
| `regionId` | REFERENCE `region` | B1's kind |
| `amount` | MONEY | |
| `thresholdAmount` | MONEY | |
| `summary` | TEXT | not sortable |
| `makerUserId` | REFERENCE `pocUser` | not sortable |
| `origin` | ENUM | |
| `requestedAt` | DATE | |
| `decidedAt` | DATE | |

- Register it in `TableSchemas.buildIndex` (`:335-341`).
- In `TableSchemaController`, change `VIEW_PRIVILEGE` to `Map<String, List<String>>` built with **`Map.ofEntries`** (the current `Map.of` already holds its maximum of 10 entries). `mayRead` becomes an any-of check.
- Add `"approvals" → [CHANGE_APPROVE, INVOICE_VIEW, PAYMENT_VIEW, PROMISE_VIEW, DISPUTE_VIEW]`. Wrap every existing entry in `List.of(x)`.

**Endpoints:**

| Endpoint | Guard | Response |
|---|---|---|
| `GET /api/approvals?page&size&sort&filter=…&view=ALL\|AWAITING_ME\|MINE` (default ALL) | authenticated staff (customers 403); rows limited by `forList(view)`; list via `queryExecutor` as in `DisputeController.list` | `PageResponse<PendingChangeDtos.Brief>` |
| `GET /api/approvals/summary` | same | `{"pendingCount":7,"pendingAmount":950000.00,"awaitingMeCount":3,"mineCount":2}` (in as-of mode, counts at T) |
| `GET /api/approvals/awaiting-me/count` | same | `{"count":3}` |
| `GET /api/approvals/for-record?entityType=INVOICE&entityId=42` | The record's scoped `get` (`invoiceService.get`, `paymentService.get`, `promiseService.get`, `disputeService.get`, or `customerService.get` for staff only); 404 otherwise | `List<Brief>`: PENDING, or outstanding at T in as-of mode. INVOICE, PAYMENT and PROMISE match `affected_type`/`affected_id`, so dispute approvals on an invoice appear too. DISPUTE matches `entity_*`. CUSTOMER returns the pending **creates** for that customer (staff only). |
| `GET /api/approvals/operations` | authenticated | `[{"operation":"INVOICE_CREATE","label":"Create invoice","entityType":"INVOICE","measure":"Invoice total"}, …]` |
| `GET /api/approvals/{id}` | `requireVisible` (404) | `Detail` |
| `POST /api/approvals/{id}/approve` `{"note":"optional"}` | `@PreAuthorize(hasAuthority CHANGE_APPROVE)` plus §5.9 | §5.9 |
| `POST /api/approvals/{id}/reject` `{"note":"required"}` | same | 200 `Detail`, REJECTED |
| `POST /api/approvals/{id}/withdraw` | authenticated; maker only | 200 `Detail`, WITHDRAWN |
| `POST /api/approvals/export` (`BulkRequest` body) | `EXPORT_DATA` plus visibility | CSV via `Csv.of`, like the other exports |

**`Detail` JSON:**

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

- `changedSinceRequest` compares the current value with `base_json`, read live.
- `canApprove` and `canReject` are true when all hold: PENDING, the caller is not the maker, `hasIn(CHANGE_APPROVE, region)`, no `asOf`, and the caller is not a customer. `canWithdraw` = PENDING, the caller is the maker, no `asOf`.
- `blockedReason` is one of: "You requested this change", "You don't hold the approval right in South", "Read-only in the as-of view", or null. These are server flags (R16).

**List flag and filter:**
- Add a BOOLEAN custom-filter column `pendingApproval` ("Awaiting approval", `eq` only, `.notSortable()`, dummy path `root.get("id")`) to INVOICES, PAYMENTS and PROMISES, matching on `affected_type`/`affected_id`, and to DISPUTES, matching on `entity_type`/`entity_id`.
- Its resolver is `EXISTS(select 1 from PendingChange pc where … and <live: status='PENDING' | as-of: the interval predicate>)`, with as-of read through the B3 static accessor. Copy `pocSeatPredicate` (`TableSchemas.java:146-180`) for the operator switch.
- Add a nullable `Boolean pendingApproval` component with `@JsonInclude(NON_NULL)` to `InvoiceSummary`, `PaymentDto`, `PromiseDto` and `DisputeDto`.
  - Only each service's `page()` fills it, with **one** batch query (`findOutstandingAffected`).
  - The `from(...)` factories pass null, so audit snapshot JSON does not change.

### 5.14 As-of (B3)

- `T` is computed from `ViewScope.asOf()` with B3's helper: the start of the next UTC day, exclusive.
- **Outstanding at T:** `requested_at < T AND (decided_at IS NULL OR decided_at >= T)`.
  - Every final status sets `decided_at`, and a superseded row ends exactly where its successor starts, so each record has at most one outstanding row at any T.
  - `region_id` never changes, so it is also the region at T.
- Applies to: the list (rows outstanding at T, each with `statusAt: "PENDING"`), summary, count, for-record, the `pendingApproval` column and the History decoration.
- Visibility in as-of mode uses the caller's **current** rights against `region_id` (audit-history §11).
- Approve, reject and withdraw return 400 in as-of mode.

### 5.15 Audit and the History tab

**Lifecycle actions** (all ≤ 40 characters, none containing "POC"):

| Action | Actor |
|---|---|
| `CHANGE_REQUESTED` | maker |
| `CHANGE_SUPERSEDED` | maker |
| `CHANGE_APPROVED` | checker |
| `CHANGE_REJECTED` | checker |
| `CHANGE_WITHDRAWN` | maker |
| `CHANGE_APPLY_FAILED` | checker |
| `CHANGE_CLOSED` | null |

- They are recorded on the audit entity (`CUSTOMER` for creates; `affected_*` otherwise) with `pending_change_id`, and `dispute_id` for disputes.
- The applied rows (`INVOICE_CANCELLED`, `PAYMENT_RECORDED`, …) have actor = maker and carry `pending_change_id` and `approved_by_user_id`.

**`AuditEntryDto`** gains `Long pendingChangeId`, `String pendingStatus`, `Long approvedByUserId` and `String approvedByUsername`.
- For rows with a `pending_change_id`, the timeline fills `pendingStatus` from `pending_changes` in one batch lookup. It is the live status, or `PENDING` or the final status at T in as-of mode.
- There are **no synthetic merged entries**: the `CHANGE_REQUESTED` row *is* the pending entry.

**`withoutPocIdentity` (customer logins):**
- Drop `CHANGE_*` rows whose `entity_type = CUSTOMER` (pending creates).
- Null `approvedBy*`.
- Add `makeruserid`, `requestedby`, `decidedby`, `decidedbyuserid`, `approvedby` and `approvedbyuserid` to the staff-field set (`AuditTimelineService.java:278-279`).

### 5.16 Notifications

- **On request** (`ApprovalNotifier.requested`, in the maker's transaction):
  - Recipients are `userRepository.findActiveHoldersInRegion(CHANGE_APPROVE, regionId)` minus the maker, capped at 100.
  - Sent through a new `NotificationService.notifyHolders(String privilege, Long regionId, Long exceptUserId, String type, String title, String message, String link)`. Do **not** copy `notifyAdmins`.
  - Type `APPROVAL_REQUESTED`; title "Approval needed: " + operation label; message = summary; link `/approvals/{id}`.
- **On decision:** the maker receives `APPROVAL_APPROVED` (link to the record, e.g. `/invoices/913`), `APPROVAL_REJECTED`, `APPROVAL_FAILED` or `APPROVAL_CLOSED` (link `/approvals/{id}`). Nothing is sent for SUPERSEDED or WITHDRAWN.
- `link` and `type` are not fitted by `notify` (critic §3.6). All links here are constant-shape and well under 300 characters, and all types are under 40.
- `approverCount` (active holders in the region, excluding the maker) is returned in the 202 body and in `Detail`.

### 5.17 Threshold administration

**`GET /api/regions/{regionId}/approval-thresholds`**
- Guard: `hasIn(APPROVAL_THRESHOLD_MANAGE, regionId) || hasIn(CHANGE_APPROVE, regionId)`, else 403. An unknown region gives 404.

```json
{"regionId":2,"regionName":"South","canEdit":true,"activeApprovers":3,
 "thresholds":[{"operation":"INVOICE_CREATE","label":"Create invoice","measure":"Invoice total","amount":100000.00,
                "updatedAt":"2026-09-20T08:00:00Z","updatedBy":{"id":1,"name":"System Administrator"}},
               {"operation":"INVOICE_CANCEL","label":"Cancel invoice","measure":"Invoice total","amount":null,
                "updatedAt":null,"updatedBy":null}]}
```

All 8 operations are always listed; `amount: null` means not gated.

**`PUT /api/regions/{regionId}/approval-thresholds`**
- Guard: `@PreAuthorize(hasAuthority APPROVAL_THRESHOLD_MANAGE)` plus `requireIn(APPROVAL_THRESHOLD_MANAGE, regionId)`. Runs in one transaction.
- Body: `{"thresholds":[{"operation":"INVOICE_CREATE","amount":100000.00},{"operation":"PAYMENT_RECORD","amount":null}]}`.
- Listed operations are upserted, or deleted when `amount` is null. Unlisted operations are unchanged.
- Validation errors are returned as `InvalidFieldsException`:
  - `thresholds[i].operation`: unknown or duplicated;
  - `thresholds[i].amount`: < 0, more than 2 decimals (`Money.requireCents`), or more than 12 integer digits.
- Audited once: `auditService.record("REGION", regionId, "APPROVAL_THRESHOLDS_CHANGED", beforeMap, afterMap, actorId, null, null)`.
- Response: the GET shape plus `"warnings":[...]`, e.g. "Only 1 person can approve changes in South, and nobody can approve their own changes" when `activeApprovers < 2` and at least one threshold is set.

### 5.18 Frontend

**Shared (`F/core/api/pending_reply.dart`):**
- `class PendingChangeRef { int id; String? operation; String? operationLabel; String summary; String? regionName; int? approverCount; int? supersededId; }`, with a `fromJson` that treats every field except `id` and `summary` as nullable (G10).
- `PendingChangeRef? pendingFrom(Response res)`: returns non-null only when `res.statusCode == 202 && res.data is Map && (res.data as Map)['pendingChange'] is Map`.
- `void showSentForApproval(BuildContext context, PendingChangeRef p, {String? message})`: a SnackBar "Sent for approval: {summary}" with a **View** action to `/approvals/{id}`. When `approverCount == 0` it appends "No one else can approve changes in {regionName} yet. Ask an administrator."
- `void invalidateApprovals(WidgetRef ref)`: invalidates `approvalsAwaitingMeCountProvider`, `pendingForRecordProvider`, `tablePageProvider` and `tableSummaryProvider`.

**`F/features/approvals/`:**

| File | Contents |
|---|---|
| `approval_models.dart` | `enum PendingStatus { PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, FAILED, CLOSED }` with a `parse` that falls back to `PENDING` (G25); `PendingChangeBrief`; `PendingChangeDetail`; `FieldChange` |
| `approval_providers.dart` | `approvalDetailProvider` (`FutureProvider.autoDispose.family<PendingChangeDetail, int>`); `pendingForRecordProvider` (`FutureProvider.autoDispose.family<List<PendingChangeBrief>, ({String entityType, int entityId})>`, a record key, G1); `approvalsAwaitingMeCountProvider` (`StreamProvider.autoDispose<int>` via `pollUnreadCount(ref, dio, '/api/approvals/awaiting-me/count')`). All requests add `regionIds`/`asOf` the way P5's providers do. |
| `approvals_screen.dart` | A `DataTableScaffold` with entity `approvals`. A `SegmentedButton` (Awaiting my approval / My requests / All I can see) sets `extraParams: {'view': 'AWAITING_ME' \| 'MINE' \| 'ALL'}`, a scalar string (G1). The default is `AWAITING_ME` when the user has `changeApprove`, else `MINE`. Row tap goes to `/approvals/{id}`. |
| `approval_detail_screen.dart` | Header (summary, `ApprovalStatusChip`, amount vs threshold, region, requested by and at, origin with rule link); a `changes` table; Current/Proposed panes from `_AuditDetails` extracted from `audit_history_panel.dart` as a public `JsonDiffView`. Buttons are shown **only by the server flags**: Approve (optional note dialog), Reject (note required, 1–500 characters), Withdraw (confirm). After an action, invalidate the detail, `invalidateApprovals`, and `auditHistoryProvider`. APPROVED shows an "Open {entityLabel}" button to `result.link`; FAILED shows `failureReason`; `blockedReason` is shown as a hint. |
| `pending_banner.dart` | `PendingChangeBanner(entityType, entityId)`. Placed in each detail screen's `top` (never as a tab, G5) on the invoice, payment, promise, dispute and customer screens. It shows "Awaiting approval: {summary} · requested by {name} on {date}" with a **View** button, or "A change to this record is awaiting approval" when the name is null (customer logins). Hidden when the list is empty. |
| `approval_thresholds_screen.dart` | Route `/approval-thresholds`. A region dropdown lists regions where the user holds `approvalThresholdManage` or `changeApprove`. Eight rows, each with an amount field (`parseMoneyInput`, `formatMoney`, empty = not gated). "Apply to all" fills every field. Save issues the PUT and shows `warnings`. Read-only when `canEdit` is false. |

**Routes and nav:**
- Add `/approvals`, `/approvals/:id` (using `pageForId`) and `/approval-thresholds` with `needs(const [Privileges.approvalThresholdManage, Privileges.changeApprove])` (G16).
- `NavEntry`:
  - Replace `final bool unreadBadge` with `final NavBadge badge`, where `enum NavBadge { none, inbox, approvals }` (the list is `const`). The Inbox entry uses `badge: NavBadge.inbox`.
  - Add `NavEntry('Approvals', Icons.fact_check_outlined, '/approvals', [Privileges.changeApprove, Privileges.invoiceView, Privileges.paymentView, Privileges.promiseView, Privileges.disputeView], hideForCustomer: true, badge: NavBadge.approvals)`.
  - `_NavIcon` switches on `badge`, watching `inboxUnreadCountProvider` or `approvalsAwaitingMeCountProvider`.
- `status_chip.dart` gains `ApprovalStatusChip`.
- `audit_history_panel.dart`:
  - add the `CHANGE_*` labels to `_actionLabels`;
  - add `pendingChangeId` to `stableKey`, e.g. `…:${id ?? (pendingChangeId != null ? 'pending:$pendingChangeId' : 'derived:…')}`;
  - add the meta line "via approval #N · approved by X" plus a pending chip from `pendingStatus`.
- In as-of mode every edit flag is ANDed with `!isHistorical` (P5).

**Every write site (critic §3.11):**

| Site | Gated? | Change |
|---|---|---|
| `customers/customers_screen.dart:333, 347`; `customer_detail_screen.dart:109, 172` | no | none (the G19 409 text stays correct) |
| `invoices/invoice_form_screen.dart:167` POST | **yes** | `final p = pendingFrom(res);` **before** the `['id']` parse at `:192`. If `p != null`: `showSentForApproval`, invalidate the tables, **skip** `notifyByEmailAfterSave`, and `context.go('/approvals/${p.id}')`. |
| `invoices/invoice_detail_screen.dart:135` PATCH | **yes** | On pending: notice; `_loadedInto = false`; `_dirty = false`; invalidate `invoiceDetailProvider(id)` so fields re-seed with **live** values (G18); invalidate the banner and history. |
| `invoices/invoices_screen.dart:254` cancel | **yes** | On pending: "Cancellation sent for approval" snack; invalidate the list. |
| `payments/record_payment_dialog.dart:115` POST | **yes** | `_RecordedPayment` becomes `({int? id, PendingChangeRef? pending, bool notify})`. Parse pending before `:126`. `showRecordPaymentDialog` (`:19-35`) shows the notice and skips the email step when pending. |
| `payments/payment_detail_screen.dart:95` PATCH | no | none |
| `promises/promise_form_dialog.dart:167-169` PUT and POST | **yes** | `_SavedPromise` gains `pending`. Parse before `:169`. On pending, `showPromiseDialog` shows the notice and skips the email step. |
| `promises/promise_form_dialog.dart:444` POST override | **yes** | On 202: pop `true` and show the notice. DELETE override `:442` unchanged. |
| `promises/promises_tab.dart:284` cancel | no | none |
| `poc/customer_poc_editor.dart:87, 118, 123`; `disputes/dispute_create_dialog.dart:130` | no | none |
| `disputes/dispute_detail_screen.dart:96` approve | **yes** | On 202: snack "This approval needs a second approver"; skip the email step; invalidate the dispute and banner; stay on the page. Deny unchanged. |
| `core/table/data_table_scaffold.dart:473-480` bulk | **yes** (invoice CANCEL) | `_showBulkResult` (`:552-607`) reads `(data['pending'] as List?) ?? const []`. The quick-snack condition also needs `pending.isEmpty`; otherwise the snack reads "N updated, M sent for approval". The dialog gains an "Awaiting approval" section with rows `#id → approval #pid`. An all-pending result counts as success. |
| `core/table/data_table_scaffold.dart:499` export | n/a | none |

---

## 6. Edge cases

1. **Amount equal to the threshold:** applied. Threshold 0: any positive amount is held; amount 0 (notes, POC, shortening, BROKEN override, `update_meta`) is never held and costs no query.
2. **No approver in the region:** the change is still stored; `approverCount: 0`; the UI warns; the threshold PUT warns.
3. **Maker deactivated, lost MANAGE in the region, or record left their book before approval:** FAILED with a specific reason; maker notified (if active).
4. **Checker's right revoked between listing and clicking:** 403, row unchanged.
5. **Invoice paid, cancelled or voided meanwhile:** the rules re-run or the strict check fails, giving FAILED with the message. A direct invoice cancel closes a pending `INVOICE_TERMS` immediately (§5.10).
6. **Customer deleted:** its PENDING rows become CLOSED. Region move: CLOSED; the maker may re-request, and the new region's thresholds and approvers apply.
7. **Dispute denied, or approved directly, while an approval is pending:** CLOSED.
8. **Threshold changed or removed while pending:** no effect on existing rows.
9. **Two checkers at once:** serialised on the customer and pending-row locks; one APPROVED, the other 409 "Already approved by …".
10. **Maker re-submits the same operation:** SUPERSEDED plus a new row. A different operation, or a different maker: 409; in bulk, *skipped*.
11. **Held invoice create:** no invoice number, no sequence increment, no invoice row. On approval the number uses the approval-day prefix. A null `invoiceDate` becomes the approval instant, and the due date is recomputed from it. A CUSTOM due date before the approval day fails re-validation, giving FAILED.
12. **Held payment:** no allocation and no credit. On approval the allocation is recomputed against the invoices as they stand then; `paidAt` = request time.
13. **Legacy wrapper reaching a held change:** `IllegalStateException`, rollback, no pending row (fail closed). A principal-less thread above the threshold: `IllegalStateException`, **never** `BadCredentialsException`.
14. **Automation redelivery:** one pending row. The owner cannot approve their own rule's change.
15. **Approver who cannot see the record:** sees the pending row and its snapshots through the approver branch (§5.13). The record link may 404, which is the expected `RecordUnavailable` behaviour.
16. **Customer login:** sees pending markers on its own existing records, redacted; never pending creates; no approvals inbox.
17. **Password:** never gated, never stored (`PayloadCodec` strips `*password*` recursively, defence in depth).
18. **Payload from an older build:** tolerant Jackson plus re-validation. A violation or unreadable payload gives FAILED. Reject and withdraw never deserialise, so they always work.
19. **As-of mode:** decisions return 400; UI read-only; lists show what was outstanding at T.
20. **User with pending references is deleted:** deactivated instead (`pocReferenceCount`).
21. **Invoice PATCH carrying `version`:** it is checked at request time (409 if stale) and stored as null. Approval relies on the three-way check, not versions.
22. **H2 lock timeout (about 2 s) during approval:** 409, still PENDING. Postgres waits instead.

---

## 7. Implementation slices

Run each slice in order. Each must leave `mvn -q -o test` green (existing 119 plus new tests) and, from slice 11 onwards, `flutter analyze` at zero issues and `flutter test` green.

| # | Slice | Contents |
|---|---|---|
| 1 | Foundation, no behaviour change | Enums and converters, `PendingChange`, `ApprovalThreshold`, repositories, the `audit_logs` columns and `AuditService` stamping plus `recordLinked`, privileges (Java and Dart constants), the `resetTransactionalData` additions, `pocReferenceCount`, `Outcome`, `Gated`, `ApprovalContext`, `PayloadCodec`, `ApprovalConflictException` and its 409 handler, `ChangeConflictException`, `PayloadException`, `RunAs` (if A5 has not added it). |
| 2 | Gate and first operation | `MakerChecker` (hold mode and apply mode), `ApprovalNotifier` plus `NotificationService.notifyHolders`, `Outcomes`, `PendingChangeViews.heldReply`; `INVOICE_CANCEL` in the service, the controller (202) and bulk (`runOutcomes`, the `BulkResult.pending` field). |
| 3 | Decisions | `ChangeApplier`, `InvoiceChangeApplier` (cancel), `ApprovalService` approve/reject/withdraw with TX-A/TX-B and the exception mapping, `PendingChangeScope.requireVisible` (basic), `ApprovalController` (`{id}`, approve, reject, withdraw). |
| 4 | Invoice create and terms | `INVOICE_CREATE` (number moved after the gate, customer lock before the sequence, prices pinned), `INVOICE_TERMS` (`due` precomputed, extension measure, three-way check). |
| 5 | Payments and promises | `PAYMENT_RECORD` (`paidAt` rule), `PROMISE_CREATE`, `PROMISE_UPDATE` (re-base), `PROMISE_OVERRIDE`, and their appliers. |
| 6 | Disputes | `DISPUTE_APPROVE`: the parse/measure/apply split, `totalFor`, the checker step, `DisputeChangeApplier`; `PendingChangeCloser` with dispute deny and approve plus the invoice-cancel close. |
| 7 | Visibility and API | The full `PendingChangeScope` (views, creates, approver branch, `ViewScope` regions), `TableSchemas.APPROVALS`, the `VIEW_PRIVILEGE` refactor, list, summary, awaiting-me count, for-record, operations and export. |
| 8 | As-of and list flags | The as-of predicates, `statusAt`, the `pendingApproval` filter column, the DTO `pendingApproval` flags filled in `page()`. |
| 9 | History and lifecycle hooks | `AuditEntryDto` fields, `pendingStatus` decoration, customer redaction; `closeForCustomer` from `CustomerService.delete` and B1's region move; the threshold admin API. |
| 10 | Automation | The A5 consumer calls `promiseService.createOrHold` and writes a HELD effect. **Requires A5.** |
| 11 | Frontend foundation | `pending_reply.dart`; `FakeReply` in `test/support/fake_backend.dart`; privileges; handling for all the gated write sites; the bulk dialog. |
| 12 | Frontend approvals UI | Approvals list and detail, the banner in the five detail screens, the nav badge enum, the audit panel updates. |
| 13 | Frontend thresholds and as-of | The thresholds screen; as-of read-only flags. |

---

## 8. Tests

### Backend

New files live in `T/approval/` unless noted. Every class extends `ApprovalTestBase extends IntegrationTestBase`, which adds **helpers only**: no `@Import`, `@MockBean` or `@TestPropertySource`, because any of those would start a third context. The helpers are:
- `threshold(Region, ApprovalOperation, String amount)` (repository);
- `approverIn(Region)` and `makerIn(Region)`, built with B1's grant helpers;
- `approve(id, User)`, `reject(id, User, note)`, `withdraw(id, User)` through MockMvc;
- `pendingOf(entityType, entityId)`.

Time is moved with `MovableClock` (P6).

**`ApprovalSchemaTest`**
1. `pending_changes` and `approval_thresholds` have no CHECK constraint on `status`, `operation` or `origin` (`information_schema`, on H2).
2. `outstanding_key` accepts many NULLs and rejects a duplicate key.
3. A `SchemaDriftTest`-style boot (`SpringApplicationBuilder`, `jdbc:h2:mem:geneinvoice-drift-<uuid>`, `ddl-auto=update`) against a schema pre-populated with `audit_logs` rows starts cleanly.

**`PayloadCodecTest`** (unit)
4. Nested `password` and `newPassword` keys are removed.
5. An unknown field is ignored.
6. A missing new field reads as null.
7. A constraint violation raises `PayloadException`.

**`GateInvoiceTest`**
8. No thresholds: POST, PATCH and cancel return 200 with unchanged JSON paths.
9. `heldCancelLeavesInvoiceUntouched`: 202; the body has no top-level `id`; `pendingChange.id` present; `Location` header set. A new transaction re-reads `status`, `version` and `paidAmount` unchanged, and there is no `INVOICE_CANCELLED` audit row.
10. An amount equal to the threshold returns 200.
11. `heldCreateConsumesNoNumber`: 202; `invoiceRepository.count() == 0`; `invoice_number_sequence.last_number` unchanged; `CHANGE_REQUESTED` recorded on CUSTOMER; the approver has an `APPROVAL_REQUESTED` notification; the maker has none.
12. Terms: an extension above the threshold is held; shortening the due date is applied; a PATCH with only notes or Sales POC is applied with no threshold query (`CountingStatements.reads("approval_thresholds", …) == 0`).
13. Bulk CANCEL of three invoices, two above the threshold: `succeeded` 1, `pending` 2, `failed` and `skipped` empty.
14. No automation event row for a held entity (once A5 exists).

**`GatePaymentPromiseTest`**
15. A held payment leaves the customer's `creditBalance`, the invoices' `paidAmount` and `paymentRepository.count()` unchanged.
16. Promise create, update and override held.
17. `reassignCollectionPoc` and invoice `reassignSalesPoc` are never held, even with the threshold at 0.

**`ApproveFlowTest`**
18. Approving a held cancel as the checker returns 200 APPROVED. The invoice is CANCELLED. `INVOICE_CANCELLED` has actor = maker, `approved_by_user_id` = checker and `pending_change_id` set. `CHANGE_APPROVED` has actor = checker. The maker has an `APPROVAL_APPROVED` notification linking `/invoices/{id}`.
19. Self-approval returns 400 with the message.
20. An approver holding the right only in R2 gets 403. A user without visibility gets 404. A customer login gets 403.
21. Approve with `asOf` returns 400. Approving twice returns 409 "Already approved".
22. Reject without a note returns 400 `fieldErrors.note`. Reject with a note gives REJECTED and a notification. Withdraw by a non-maker returns 403; withdraw by the maker gives WITHDRAWN.
23. Invoice create requested on day 1, `clock.advance(1 day)`, approved: the number has the day-2 prefix, `invoiceDate` is the approval instant, and the sequence has no gap after a rejected request.
24. A payment approved a day later keeps `paidAt` = the request instant.

**`ApproveFailureTest`**
25. Pending cancel, then a below-threshold payment lands on the invoice, then approve: 200 FAILED "Cannot cancel an invoice with payments"; the invoice is not cancelled; the maker has `APPROVAL_FAILED`.
26. Pending promise amount change; someone reassigns the POC; approve succeeds and **the reassigned POC is kept**.
27. Pending promise change; someone else edits the amount: FAILED naming "Amount".
28. Pending `INVOICE_TERMS` extension; the balance grows (a payment is voided through a dispute): FAILED "The amount grew…".
29. Maker deactivated: FAILED "The requester's account is no longer active", and the response is **not** 401.
30. The maker's MANAGE grant in the region is removed: FAILED.
31. The product is deactivated before a pending create is approved: FAILED.
32. A thread holds the customer lock for 3 s while `PAYMENT_RECORD` is approved: 409, row still PENDING; a later approve succeeds.

**`ApprovalConcurrencyTest`**
33. Two checkers on two threads (the `ConcurrentMoneyTest` pattern, a `CountDownLatch`, each thread calling `actAs` itself): exactly one invoice or payment, one APPROVED, the other 409.
34. A maker superseding while a checker approves the same row: no H2 lock timeout; exactly one final outcome; the rows stay consistent (at most one PENDING).

**`OutstandingChangeTest`**
35. Same maker, same operation: SUPERSEDED plus a new row; the 202 carries `supersededId`.
36. Same maker, different operation: 409.
37. A different maker: 409 with the message naming the request.
38. Bulk: the row is *skipped* with that message.
39. Creates are not limited.

**`DisputeApprovalGateTest`**
40. A customer-proposed ₹5 L void, approved as proposed by a `CHANGE_APPROVE` holder: applied at once (200).
41. An approver without `CHANGE_APPROVE`: 202, and the dispute stays PENDING.
42. An approver who rewrote `appliedChangeJson`: 202.
43. A second approver approves the pending change: payment VOIDED, dispute APPROVED with `resolvedBy` = maker, **`pendingChangeRepository.count() == 1`**.
44. Deny while pending: the pending row becomes CLOSED.

**`ExemptPathsTest`**
45. With every threshold at 0: `sweepOverdue`, `recomputeAll(true)`, `reevaluateForCustomer` and the credit application during an **approved** invoice create produce no new pending rows.
46. A legacy wrapper that reaches a held change throws `IllegalStateException` and leaves no pending row.
47. `SecurityContextHolder.clearContext()`, then `createOrHold` above the threshold: `IllegalStateException`.

**`ApprovalVisibilityTest`**
48. A second staff member in the book sees the row in the list and in for-record. An out-of-book user without the approver right: 404 on detail, empty list. An approver in the region without book access sees it.
49. A customer login: for-record on its own invoice shows the row with `requestedBy == null`; creates are not shown; `GET /api/approvals` returns 403.
50. `regionIds=R2` narrows R1 rows away.
51. `view=AWAITING_ME` excludes the caller's own requests; `view=MINE` shows only them.
52. The `pendingApproval` filter column and the DTO flag on invoice and payment lists.

**`ApprovalAsOfTest`**
53. Request at T1, supersede at T2, approve at T3. As-of between T1 and T2 shows #1 outstanding; between T2 and T3 shows #2; after T3 shows none. The `statusAt` values match.
54. The `pendingApproval` filter honours as-of.
55. Summary counts at T.

**`ApprovalAuditTimelineTest`**
56. The `CHANGE_*` rows appear on the target (and on CUSTOMER for creates) with `pendingStatus` decoration.
57. A dispute approval's rows are on the target, with `dispute_id` set.
58. The customer-login timeline drops create requests and nulls `approvedBy`.

**`ApprovalLifecycleTest`**
59. Customer delete with a pending create: the row becomes CLOSED.
60. The B1 region move closes pending rows, and approve then returns 409.
61. A user with pending references is deactivated rather than deleted.
62. A threshold change leaves existing rows PENDING.

**`ApprovalThresholdApiTest`**
63. GET lists 8 operations.
64. PUT validation: negative amount, three decimals and unknown operation each give a field error.
65. No right in the region gives 403.
66. An audit row `APPROVAL_THRESHOLDS_CHANGED` is written.
67. `warnings` appear with one approver.
68. A null amount deletes the row.

**`T/automation/AutomationHoldTest`** (slice 10)
69. The rule owner's promise is held with `origin=RULE` and maker = owner.
70. Redelivery through `drain()` twice leaves one pending row, with `clearContext()` called first (R2).
71. The owner's approve returns 400.
72. After another user approves, exactly one promise-created event exists, with actor = owner.

### Frontend

`test/approvals_test.dart`, using `test/support/fake_backend.dart` extended with `class FakeReply { final int status; final Object? data; }`, which is resolved with that status code:

1. Invoice form on 202: the "Sent for approval" snack shows; no email-compose request or dialog; navigation goes to `/approvals/41`.
2. Record-payment dialog on 202: the pending result is returned; no email prompt; the snack shows.
3. Invoice detail PATCH on 202: a second `GET /api/invoices/{id}` is made, and the fields show the server's values (re-seeded).
4. Promise form and override on 202: the notice shows and the dialog closes.
5. Dispute approve on 202: the "needs a second approver" snack shows.
6. A bulk result with `pending` shows the "Awaiting approval" section and "N updated, M sent for approval".
7. Approval detail: the buttons follow `canApprove`, `canReject` and `canWithdraw`; Approve posts to `/api/approvals/41/approve`; Reject refuses an empty note; `blockedReason` shows.
8. The pending banner is visible when for-record returns rows, hidden when it returns none, and shows the anonymous text when `requestedBy` is null.
9. `pendingFrom`: null on 200; parses a 202 that is missing optional fields (G10).
10. The approvals list sends `view` as a scalar; the exact-parameter asserts elsewhere are unchanged (G11).
11. The nav shows the approvals badge count (with the provider overridden).

`list_columns_test.dart` must override `approvalsAwaitingMeCountProvider` in its `ProviderScope` (G13). No invoice tile is added (G12).

`flutter analyze` stays at 0 issues. All 61 existing Flutter tests stay green.

---

## 9. Why existing behaviour and tests are unchanged (P7)

- No thresholds are seeded, so every gate returns after at most one indexed select. Zero-amount operations skip even that.
- The legacy signatures are kept. Controllers still return 200 with byte-identical DTOs on the applied path.
- `BulkResult` gains a trailing list, which `jsonPath` tests and Dart both ignore.
- The new audit, DTO and table columns are nullable or new.
- The `CountingStatements` assertion targets the `customer_pocs` substring, which no new table name contains.
- Moving `invoiceNumbers.next()` and adding the customer lock before it change only lock timing, not numbering.
- The reset order change is additive.

---

## 10. Open questions for the PRD owner (defaults as built)

1. **Payment `paidAt` after approval.** Default: the request time (the receipt date). The alternative is the approval time.
2. **Whether customer payment-term changes and promise cancel should be gateable.** Default: no. There is no money measure, and the customer PUT carries a password.
3. **Region move.** Default: pending changes are closed, and the maker re-requests under the new region's thresholds.
4. **Dispute approval.** Default: a `CHANGE_APPROVE` holder approving a customer's proposal as proposed is the checker step and applies at once.
5. **Expiry of stale requests.** Default: none in v1.
6. **Threshold changes.** Default: audited, not maker-checked.
7. **Customer logins.** Default: they see pending markers on existing records and never see pending creates.