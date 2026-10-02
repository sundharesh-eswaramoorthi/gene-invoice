# Audit and history: engineering map (for the B3 as-of reads work, plus audit touchpoints of A1–A6 and B1–B2)

All backend paths below are relative to `backend/src/main/java/com/geneinvoice/` unless a full path is given. Nothing was edited.

---

## 0. Summary

- **What is recorded.** Each row is one event in `audit_logs`. It holds whole-object **before/after JSON snapshots** (Jackson serialisations of DTO records or small `*AuditSnapshot` records), stored as TEXT. These are **not field-level diffs**. The JSON shape depends on the action. Some actions store whole objects, some store part of an object, and some store a bare enum string.
- **When and where.** `AuditService.record` is `@Transactional` with REQUIRED propagation, so it joins the caller's transaction. In the `@Transactional` services the audit row commits or rolls back together with the change. In `ProductController` and `UserController` it does not: those controllers have no transaction, so the entity save and the audit insert are two separate transactions.
- **Actor.** `changed_by_user_id` is a bare Long with no foreign key. The username is looked up when the history is read. System jobs record `null`, which the UI shows as "automatic".
- **Time.** `created_at` is set from `Instant.now()` on the JVM in `@PrePersist`, i.e. when the row is persisted, not when the transaction commits. With Hibernate 6 defaults this is microsecond precision.
- **Can it reconstruct past state? No.** Reasons:
  - At least 12 state-changing write paths are never audited (§10).
  - Snapshot shapes vary, some are partial, and some contain derived values computed "as of the write".
  - History predates the log: the timeline already has to *invent* missing CREATED entries (`derived`).
  - The JSON is TEXT, so it cannot be filtered in SQL.
  - Region and approvals don't exist yet.
- **Recommendation for B3.** Use SCD2 (valid-from/valid-to) version rows captured at the Hibernate flush layer, not at call sites. The cheapest way to get them on this stack is Hibernate Envers with the `ValidityAuditStrategy`. Envers is managed by the Boot BOM at 6.5.3.Final but is not in the pom today.
  - Read the version tables through read-only `@Subselect` "as-of" entities, so the existing `TableQueryExecutor`, `TableSchemas`, `ScopeResolver` and `DashboardService` Criteria code runs unchanged with one extra `PredicateFactory`.
  - Keep `audit_logs` as the "who / why / via what" narrative behind the History tab.
  - Put pending maker-checker changes in their own interval table.
- **Two conditions are mandatory:**
  - Backfill a baseline revision for all existing rows *before* Envers goes live. Without it, validity-strategy updates to legacy rows throw and break user saves.
  - Keep to the DB-agnostic path. H2 runs the tests and also a real deployment profile (`azurefree`).

---

## 1. Files in scope

| File | Lines | Role |
|---|---|---|
| `audit/AuditLog.java` | 57 | Entity `audit_logs` |
| `audit/AuditLogRepository.java` | 12 | Spring Data repo |
| `audit/AuditService.java` | 55 | Write API `record(...)` + `historyFor` |
| `audit/AuditTimelineService.java` | 354 | Read model: related-record timeline, derived entries, customer redaction |
| `audit/AuditController.java` | 125 | `GET /api/audit` |
| `frontend/lib/features/audit/audit_history_panel.dart` | 478 | History tab widget + provider |

`AuditService` is called from 11 production files: CustomerService, InvoiceService, InvoiceSchemaUpgrade, PaymentService, PaymentPromiseService, DisputeService, PocService, DocumentService, UserController and ProductController. The only test that touches audit is `backend/src/test/java/com/geneinvoice/invoice/DueDateTest.java` (lines 5–6, 40, 178–213).

---

## 2. Data model: `AuditLog`

`audit/AuditLog.java:8-57`:

```java
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_audit_dispute", columnList = "dispute_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "entity_type", nullable = false, length = 30) private String entityType;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(nullable = false, length = 40) private String action;
    @Lob @Column(name = "before_json", columnDefinition = "TEXT") private String beforeJson;
    @Lob @Column(name = "after_json", columnDefinition = "TEXT") private String afterJson;
    @Column(name = "changed_by_user_id") private Long changedByUserId;
    @Column(name = "dispute_id") private Long disputeId;
    @Column(length = 500) private String reason;
    @Column(updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { this.createdAt = Instant.now(); }
}
```

Facts that matter:

- **Entity type and action are free strings**, not enums. `entity_type` is limited to 30 characters and `action` to 40. A longer action name makes the INSERT fail, and because the audit call runs inside the save's transaction, the user's save fails too.
- **Snapshots are TEXT, not jsonb.** No SQL query can read inside them in a way that works on both Postgres and H2.
- **Indexes:** there is `(entity_type, entity_id)` and `(dispute_id)`. There is **no index on `created_at`**, so time-range queries over audit scan the table.
- **No foreign keys** to users or entities. This is deliberate: history survives deletes (`CUSTOMER_DELETED`, `USER_DELETED`, `PRODUCT_DELETED`).
- **Only one link column, `dispute_id`.** It is a *cross-link* ("this change happened via dispute #N"). It is the precedent for an `approval_id` column in B2 and a `rule_id` or `origin` column in A5.
- **No region, origin, request or correlation id, and no approver** column.
- Repository (`audit/AuditLogRepository.java:8-12`):
  ```java
  List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId); // used by AuditService.historyFor (tests only)
  List<AuditLog> findByEntityTypeAndEntityIdIn(String entityType, Collection<Long> entityIds);      // used by timeline, unordered
  List<AuditLog> findByDisputeIdOrderByCreatedAtDesc(Long disputeId);                                // UNUSED today
  ```

---

## 3. Write API: `AuditService`

`audit/AuditService.java:18-54`:

```java
@Transactional
public AuditLog record(String entityType, Long entityId, String action,
                       Object before, Object after,
                       Long userId, Long disputeId, String reason) {
    return repository.save(AuditLog.builder()
            .entityType(entityType).entityId(entityId).action(action)
            .beforeJson(toJson(before)).afterJson(toJson(after))
            .changedByUserId(userId).disputeId(disputeId).reason(fit(reason))
            .build());
}
static final int REASON_MAX = 500;
private static String fit(String reason) { ... reason.substring(0, REASON_MAX - 1) + "…"; }   // :36-40
private String toJson(Object o) {                                                               // :47-54
    if (o == null) return null;
    try { return objectMapper.writeValueAsString(o); }
    catch (JsonProcessingException e) { return "{\"_serialization_error\":\"" + ... + "\"}"; }
}
```

- **Propagation is REQUIRED.** The call joins whatever transaction is open. If none is open it starts its own.
- **IDENTITY id.** The INSERT runs immediately at `save`, not at flush. Row ids therefore follow insertion order within the transaction, not commit order across transactions.
- **Serialisation errors are swallowed** into a marker JSON so they never fail a save. **INSERT errors are not swallowed.** One would mark the outer transaction rollback-only and break the user's save.
- **The ObjectMapper is Spring Boot's auto-configured one.** No Jackson config exists in `application.yml`, so Instants and LocalDates serialise as ISO strings and BigDecimal as a JSON number.
- **`reason` is truncated to 500 characters with "…".** The same rule is enforced at the column.

---

## 4. Complete call-site catalogue: what gets recorded

"Tx" means the transaction the audit row joins. "require()" means `currentUser.require().getId()`, which throws `BadCredentialsException` when there is no security context. "idOrNull()" returns null in system context.

### Customer (`customer/CustomerService.java`, `ENTITY="CUSTOMER"`, line 50)

| Line | Action | before | after | Actor | Reason | Tx |
|---|---|---|---|---|---|---|
| 245 | `CUSTOMER_CREATED` | null | `CustomerAuditSnapshot` | require() | null | `create` @Transactional (204) |
| 288 | `CUSTOMER_UPDATED` | `CustomerAuditSnapshot` (255) | `CustomerAuditSnapshot` | require() | null | `update` @Transactional (250) |
| 291 | `CUSTOMER_PAYMENT_TERM_CHANGED` | bare enum name string or null | bare enum name string or null | require() | null | same; written only if the term changed (290) |
| 318 | `CUSTOMER_DELETED` | snapshot read before the cascade (305) | null | require() | `"Customer deleted"` | `delete` @Transactional (299) |

`CustomerAuditSnapshot(id, name, phone, email, address, creditBalance, paymentTerm)` at lines 330–337. It has no `createdAt`, `version` or POC seats.

### POC seats, recorded against CUSTOMER (`poc/PocService.java`)

| Line | Action | before | after | Actor | Reason |
|---|---|---|---|---|---|
| 145 | `POC_ASSIGNED` | null | `PocAuditSnapshot` | require() | `"Added <type> <username>"` |
| 153 | `POC_PRIMARY_CHANGED` (a seat was demoted) | old primary snapshot | new primary snapshot | require() | text |
| 207 | `POC_REMOVED` | snapshot | null | require() | text |
| 215 | `POC_PRIMARY_CHANGED` (automatic promotion) | removed | promoted | require() | text |
| 239 | `POC_PRIMARY_CHANGED` (`setPrimary`) | previous or null | new | require() | text |

`PocAuditSnapshot(String pocType, Long userId, String username, boolean primary)` at line 277. These rows are **events, not state**. To get the seat set at a past time you replay them from the customer's creation. All callers are @Transactional (117, 171, 224).

### Invoice (`invoice/InvoiceService.java`, `ENTITY="INVOICE"`, line 43)

| Line | Action | before | after | Actor | Reason |
|---|---|---|---|---|---|
| 87 | `INVOICE_CREATED` | null | `InvoiceDtos.InvoiceDto` | **idOrNull()** | `"Customer credit applied: X"` or null |
| 91 | `PAYMENT_APPLIED` (credit spent at creation) | `InvoicePaymentAudit` | `InvoicePaymentAudit` | idOrNull() | `"Paid from customer credit"` |
| 143 | `INVOICE_UPDATED` | `InvoiceDto` (119, read under `findByIdForUpdate`, 106) | `InvoiceDto` | require() | null |
| 147 | `INVOICE_DUE_DATE_CHANGED` | `DueDateSnapshot(dueDate, paymentTerm)` | `DueDateSnapshot` | require() | null |
| 371 | `INVOICE_CANCELLED` | `InvoiceDto` | `InvoiceDto` | require() | null |

`InvoiceDto` (`invoice/InvoiceDtos.java:68-99`) includes derived values that were true only at write time:

- `overdue` and `daysOverdue`, computed with `InvoiceDates.today()` (line 86)
- `balance`
- `customerName`, copied from the customer
- `salesPoc`, a `PocUserDto`
- `items`, the line items
- `version` and `createdAt`

### Invoice due-date backfill (`invoice/InvoiceSchemaUpgrade.java:51-55`)

This writes one synthetic row: `record("INVOICE", BACKFILL_ENTITY_ID /*0L*/, "INVOICE_DUE_DATES_BACKFILLED", null, new Backfill(filled, term), null, null, "Backfilled N ...")`.

- It uses **entity id 0**, which is not a real invoice. The per-invoice changes were made with raw JDBC (lines 111–128) and are **not** audited per row.
- It runs at startup outside any request, with actor null.

### Payment (`payment/PaymentService.java`, `ENTITY="PAYMENT"`, line 41)

| Line | Action | Entity | before | after | Actor | Reason |
|---|---|---|---|---|---|---|
| 101 | `PAYMENT_RECORDED` | PAYMENT | null | `PaymentDtos.PaymentDto` | idOrNull() | `req.notes()` |
| 103→150 | `PAYMENT_APPLIED` (one per invoice touched) | **INVOICE** | `InvoicePaymentAudit` (paid/balance/status before) | `InvoicePaymentAudit` (after) | idOrNull() | null |
| 130 | `PAYMENT_UPDATED` (notes / collection POC only) | PAYMENT | `PaymentDto` | `PaymentDto` | require() | null |
| 232→150 | `PAYMENT_REVERSED` (per allocation, during void/updateAmount) | INVOICE | `InvoicePaymentAudit` | `InvoicePaymentAudit` | idOrNull() | null |
| 267→150 | `PAYMENT_APPLIED` (re-application in `updateAmount`) | INVOICE | same | same | idOrNull() | null |

- `InvoicePaymentAudit(Long paymentId, BigDecimal amount, BigDecimal paidAmount, BigDecimal balance, InvoiceStatus status)` at lines 140–141. It is a **partial invoice state**.
- `PaymentDto` (`payment/PaymentDtos.java:45-73`) contains `invoices`, which holds live invoice balances *at write time*, plus `customerCreditBalance` and `collectionPoc`.

### Promise (`promise/PaymentPromiseService.java`, `ENTITY="PROMISE"`, line 56)

| Line | Action | before | after | Actor | Reason |
|---|---|---|---|---|---|
| 98 | `PROMISE_CREATED` | null | `PromiseAuditSnapshot` | require() | notes |
| 141 | `PROMISE_UPDATED` | snapshot | snapshot | require() | null |
| 170 | `PROMISE_CANCELLED` | snapshot | snapshot | require() | reason |
| 198 | `PROMISE_STATUS_OVERRIDDEN` | snapshot | snapshot | require() | reason |
| 218 | `PROMISE_OVERRIDE_CLEARED` | snapshot | snapshot | require() | null |
| 265 | `PROMISE_STATUS_CHANGED` | **bare status string** | **bare status string** | **null, always** (even when a user's payment triggered it) | `"Recomputed from payments"` or the recompute text |

`PromiseAuditSnapshot(id, customerId, amount, promisedDate, status, fulfilledAmount, collectionPocUserId, notes, invoiceIds, statusOverridden, overrideReason)` at lines 668–672.

### Dispute (`dispute/DisputeService.java`, `ENTITY="DISPUTE"`, line 35)

| Line | Action | Entity | before | after | Actor | disputeId | Reason |
|---|---|---|---|---|---|---|---|
| 77 | `DISPUTE_OPENED` | DISPUTE | null | `DisputeDto` | caller | d.id | req.reason |
| 108 | `DISPUTE_APPROVED` | **target type (INVOICE or PAYMENT), target id** | full target DTO before `applyChange` (98) | full target DTO after (100) | resolver | d.id | adminNotes, or the reason |
| 127 | `DISPUTE_DENIED` | DISPUTE | null | `DisputeDto` | resolver | d.id | adminNotes, or the reason |

The dispute's *own* entity history therefore has no `DISPUTE_APPROVED` row. That row lives on the target and is linked back only through `dispute_id`.

### Documents, recorded against the parent entity type (`document/DocumentService.java`)

| Line | Action | Entity | before | after | Tx |
|---|---|---|---|---|---|
| 106 | `DOCUMENT_UPLOADED` | `type.name()` (CUSTOMER, INVOICE or PAYMENT), target id | null | `DocumentSnapshot` | `TransactionTemplate.execute` (84) together with the insert |
| 157 | `DOCUMENT_UPDATED` | parent | snapshot | snapshot | @Transactional (146) |
| 178 | `DOCUMENT_DELETED` (soft delete) | parent | snapshot | null | @Transactional (162) |

`DocumentSnapshot(id, filename, contentType, sizeBytes, visibility, description, uploadedBy)` at lines 60–62.

### Users (`user/UserController.java`) and Products (`product/ProductController.java`), with no service transaction

| File:Line | Action | before | after | Reason |
|---|---|---|---|---|
| UserController:188 | `USER_UPDATED` | `UserDto` | `UserDto` | null |
| UserController:213 | `USER_DEACTIVATED` (delete fell back to deactivate) | null | `UserDto` | "Deactivated instead of deleted: …" |
| UserController:223 | `USER_DELETED` | `UserDto` | null | "User deleted" |
| UserController:272 | `USER_ACTIVATED` or `USER_DEACTIVATED` (bulk) | `UserDto` | `UserDto` | "Bulk action" |
| ProductController:97 | `PRODUCT_CREATED` | null | `ProductDto` | null |
| ProductController:112 | `PRODUCT_UPDATED` | `ProductDto` | `ProductDto` | null |
| ProductController:125 | `PRODUCT_DELETED` | `ProductDto` | null | "Product deleted" |
| ProductController:153 | `PRODUCT_ACTIVATED` or `PRODUCT_DEACTIVATED` (bulk) | `ProductDto` | `ProductDto` | "Bulk action" |

`UserDto` (UserController:65-77) includes the role name and the full sorted **privilege list**, so a user's rights at write time are captured, but only when the *user* row is edited.

---

## 5. Transaction and atomicity per call site

- **Atomic with the change** (the audit row is in the same transaction): CustomerService, InvoiceService, PaymentService, PaymentPromiseService, DisputeService, PocService and DocumentService, all of which are @Transactional or use a TransactionTemplate. Also bulk operations: `BulkExecutor` wraps each record in `PROPAGATION_REQUIRES_NEW` (`common/bulk/BulkExecutor.java:22-25, 68`), so a bulk record and its audit row commit together.
- **Not atomic** — the entity save and the audit are two separate transactions:
  - `UserController.update/delete` (lines 187–189, 211–215, 221–224)
  - `ProductController.create/update/delete` (lines 96–98, 111–113, 124–126)

  These controllers have no `@Transactional`. Open-EntityManager-in-View only keeps the EntityManager open (`config/OpenEntityManagerInViewConfig.java:15-24`); it does not open a transaction. `SimpleJpaRepository.save` commits, then `record` commits in a new transaction. If the audit insert fails, the change stays and there is no trail.
- **Nested services.** `DisputeService.approve` calls `invoiceService.cancelWithRefund`, `replaceItems`, `paymentService.voidPayment` and `updateAmount` (lines 242, 253, 267, 273). Those all join the approve transaction. Any `PAYMENT_REVERSED` or `PAYMENT_APPLIED` rows they write are in the same transaction as `DISPUTE_APPROVED`.
- **Startup.** `InvoiceSchemaUpgrade.afterPropertiesSet` (line 42) runs raw JDBC with its own commits (line 73), then `record` in a fresh transaction.

## 6. Actor and timestamp semantics

- **Actor** is a user id or null. It comes from two helpers in `auth/CurrentUser.java`:
  - `require()` (lines 12–18) throws `BadCredentialsException("Not authenticated")` when no `AppUserDetails` principal is present.
  - `idOrNull()` (lines 24–29) returns null in that case.

  Creates in Invoice and Payment use `idOrNull()` (InvoiceService 88, 92; PaymentService 102, 147). Almost everything else uses `require()`.
- **Gotcha for A5.** A consumer thread with no security context will throw inside any service path that calls `require()`. This covers promise create (PaymentPromiseService 94, 99), all updates, POC ops, and `pocService.notifyAssignee` (PocService 269). A system actor or a run-as context is needed, and the audit should then record *which rule* acted.
- **Null actor** is rendered as "automatic" in the UI (`audit_history_panel.dart:380`). It is used by the promise sweeper (`promise/PromiseSweepScheduler.java:19-30`, `@Scheduled`, with no auth) and by `PROMISE_STATUS_CHANGED` always.
- **Username** is resolved when history is read (`AuditController.java:88-91`). A deleted user gives a null username and a non-null id, so the UI shows no "by …" at all.
- **Time:**
  - `created_at` is set in `@PrePersist` with `Instant.now()`. It uses the application clock and reflects persist time, not commit time.
  - Hibernate 6 maps `Instant` to `timestamp(6) with time zone` on both Postgres and H2, which is microsecond precision.
  - All rows written in one transaction carry distinct but near-identical times. The read side breaks ties with `id DESC` (`AuditTimelineService.java:45-47`).
  - The project's notion of "day" is UTC: `invoice/InvoiceDates.java:14-20` (`today()` and `dayOf()` use `ZoneOffset.UTC`). An as-of *date* parameter should use the same convention: the end of that UTC day.

---

## 7. Read side

### 7.1 `GET /api/audit` (`audit/AuditController.java`)

```java
@GetMapping
@PreAuthorize("hasAuthority('" + Privileges.AUDIT_VIEW + "')")
public List<AuditEntryDto> history(@RequestParam String entityType, @RequestParam Long entityId,
                                   @RequestParam(defaultValue = "false") boolean includeRelated)
```

- `SUPPORTED = {INVOICE, PAYMENT, CUSTOMER, PROMISE, USER, PRODUCT}` (lines 36–37). **DISPUTE is not supported directly**; the dispute screen shows its *target's* history (`dispute_detail_screen.dart:163`). The same set is used for `CUSTOMER_READABLE` (line 39) and the `VIEW_PRIVILEGE` map (lines 41–47).
- **Access** is checked in `ensureCallerCanSee` (lines 98–116):
  1. The caller must hold the view privilege for the entity type.
  2. Customers may only read `CUSTOMER_READABLE` types.
  3. The record is then read through the domain service's `get()`, which applies the POC-book scope via `requireInBook`, e.g. `InvoiceService.get` at 260–273 and `requireInBook` at 281–286.
  4. `readOrGone` (lines 118–124): if the record is **deleted** and the caller is staff, history is still allowed, because the trail is all that remains.

  **For B1** this is the place where region scoping must plug in. For deleted records the region can only come from the audit snapshot, and no snapshot contains a region today.
- DTO (lines 61–65):
  ```java
  public record AuditEntryDto(Long id, String entityType, Long entityId, String entityLabel,
                              String action, String beforeJson, String afterJson,
                              Long changedByUserId, String changedByUsername,
                              Long disputeId, String reason,
                              Instant createdAt, boolean derived, boolean actorHidden) {}
  ```
- **Customer callers** get `timelineService.withoutPocIdentity(entries, ownLogins)` (lines 80–86).

### 7.2 `AuditTimelineService.timeline(type, id, includeRelated)` (lines 63–130), `@Transactional(readOnly = true)`

- It gathers `Related` records from **current** relationships:
  - CUSTOMER: its invoices, payments, promises, disputes and allocations (lines 76–82).
  - INVOICE: promises, disputes and allocations (lines 91–96).
  - PAYMENT: promises and disputes only (lines 105–109). **`PAYMENT_APPLIED` and `PAYMENT_REVERSED` rows sit on the INVOICE, so a payment's own history does not show its allocations.**
- It loads audit rows in chunks of 1000 ids per type (lines 115–126).
- `derived(...)` (lines 148–223) *synthesises* entries for facts older than the log: `CUSTOMER_CREATED`, `INVOICE_CREATED`, `PAYMENT_RECORDED`, `PROMISE_CREATED`, `DISPUTE_OPENED`, `DISPUTE_DENIED`, and `PAYMENT_APPLIED` from allocations with no matching audit row. These entries have `id=null` and `derived=true`. `original()` (lines 225–236) recovers the *original* invoice total or payment amount by scanning the **oldest** `beforeJson` that contains that field name.
- Everything is sorted newest first in memory. **There is no server-side pagination**; the UI pages at 100 on the client.
- `withoutPocIdentity` (lines 132–146) does five things:
  1. Drops every action whose name **contains "POC"**.
  2. Drops `DOCUMENT_*` entries unless both sides are `visibility == SHARED`.
  3. Strips any JSON field whose lowercase name contains `"poc"` or is one of `uploadedby`, `changedby`, `createdby`, `resolvedby`, `assignedby` or `recordedby` (lines 278–279, 311–314).
  4. Drops entries whose before equals after once stripped.
  5. Hides the actor unless it is one of the customer's own logins.

### 7.3 Frontend: `frontend/lib/features/audit/audit_history_panel.dart`

- `AuditEntry.fromJson` (lines 58–82) mirrors the DTO and computes `headline` (lines 103–167, a per-action switch) and `paymentId` (lines 169–173).
- `stableKey` (lines 55–56) is `'$entityType:$entityId:$action:${id ?? 'derived:${createdAt µs}'}'`. It assumes an entry with no id is a derived entry.
- The provider is `auditHistoryProvider`, a `FutureProvider.autoDispose.family` keyed by `(entityType, entityId, includeRelated)` (lines 175–186). Screens call `ref.invalidate(auditHistoryProvider)` after writes:
  - customers `customer_detail_screen.dart:123,291`
  - products `products_screen.dart:45`
  - payments `payment_detail_screen.dart:103`
  - disputes `dispute_create_dialog.dart:139`
  - promises `promise_detail_screen.dart:114`
  - invoices `invoice_detail_screen.dart:149`
  - users `users_screen.dart:51`
  - documents `document_providers.dart:51`
- Labels live in the `_actionLabels` map (lines 188–228); unknown actions fall back to `humanizeEnum`. Type chips are driven by `_typeOrder`, `_typeLabels`, `_routeFor` and `_iconFor` (lines 232–257). **These cover CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE only.** TASK or RULE would need entries.
- The meta line (lines 382–387) shows date, "by X" or "automatic", and `via dispute #N`. This is the exact pattern to copy for "via approval #N" and "by rule X".
- Details (lines 433–477) show pretty-printed Before/After JSON. Derived entries carry the note "Reconstructed from the record itself — this happened before the history log covered it." (lines 453–461).
- The tab is gated by `user.has(Privileges.auditView)` (for example `invoice_detail_screen.dart:169,246`). `includeRelated: true` is used on the customer, invoice and payment screens.
- `AUDIT_VIEW` is seeded for ADMIN (all privileges), CUSTOMER, and the SALES, SUCCESS and COLLECTION POC roles. It is **not** seeded for CASHIER or VIEWER (`config/DataSeeder.java:54-138`). It is one global privilege with no region dimension.

---

## 8. Other conventions an implementer must copy

- **Snapshot style.** Use a nested `public record XxxAuditSnapshot(...)` next to the service, built by a `private Object snapshot(Entity)` helper (`CustomerService.java:330-337`, `PaymentPromiseService.java:659-672`). Or reuse the response DTO (`InvoiceDtos.InvoiceDto.from`, `PaymentDtos.PaymentDto.from`). Take the before snapshot *before* mutating, and under the row lock when there is one (`InvoiceService.java:103-119`).
- **Entity constant.** Use `public static final String ENTITY = "INVOICE";` on the service. `PocService` and the controllers use string literals.
- **Where to call record.** Call it after `repository.save(...)` and inside the same @Transactional method. Pass `currentUser.require().getId()` for user actions and `currentUser.idOrNull()` for paths that system code may reach.
- **Exceptions:** `NotFoundException` gives 404, `BadRequestException` 400, `AccessDeniedException` 403, `GlobalExceptionHandler.InvalidFieldsException(Map)` 400 with per-field messages, and `ConcurrencyFailureException` or `OptimisticLockingFailureException` 409 (`common/GlobalExceptionHandler.java:34-155`).
- **Schema upgrade pattern.** Examples: `invoice/InvoiceSchemaUpgrade.java:24-56,146-182`, `poc/PocSchemaUpgrade.java:14-37`, `common/RowVersionUpgrade.java:14-48`, `email/EmailSchemaUpgrade.java:21-60`.
  ```java
  @Component @Slf4j
  class XSchemaUpgrade implements InitializingBean {
      XSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) { ... } // EMF param = run after ddl-auto
      @Override public void afterPropertiesSet() {
          try (Connection connection = dataSource.getConnection()) { ... if (!connection.getAutoCommit()) connection.commit(); }
          catch (SQLException e) { log.warn("Could not ...: {}", e.getMessage()); }
      }
  }
  ```
  - Dialect branching uses `connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql" | "h2")`.
  - H2 `information_schema` queries use upper-case names and `schema()`; Postgres uses `current_schema()` (InvoiceSchemaUpgrade 166–178).
  - Partial unique indexes are created **only on Postgres** (PocSchemaUpgrade 57–69, CustomerSchemaUpgrade 58–70).
  - These beans run during context refresh: after ddl-auto, before the `CommandLineRunner` `DataSeeder`, and before `ApplicationReadyEvent` (when the promise sweeper first runs, `PromiseSweepScheduler.java:27-30`). This ordering is what makes a pre-Envers baseline backfill safe.
- **Schema config** (`backend/src/main/resources/application.yml`):
  - `ddl-auto: update` for dev, prod and azurefree (lines 81, 103, 141); `create-drop` for h2 and test.
  - `hibernate.hbm2ddl.halt_on_error: ${SCHEMA_HALT_ON_ERROR:true}` (line 24). **Any DDL that `update` cannot apply stops startup.** The classic case is a NOT NULL column added to a table that already has rows.
  - **The `azurefree` profile runs a file-based H2 as a real deployment** (lines 129–144).
  - Postgres in docker is `postgres:16` (`docker-compose.yml`).
  - Tests use H2 in memory with `create-drop` and a statement inspector `com.geneinvoice.CountingStatements` (`backend/src/test/resources/application-test.yml:1-16`).
- **Query engine** (it matters for B3):
  - `TableQueryExecutor.run/count/ids/aggregate(Class<T> type, TableSchema, TableQuery, List<PredicateFactory> scope, ...)` (`common/query/TableQueryExecutor.java:30-100`) is JPA Criteria over an entity class.
  - Columns resolve by **attribute name**: `ColumnDef.attr(name)`, `nested(assoc, attr)` (a LEFT join) and `referenceId(assoc)` (`common/query/ColumnDef.java:26-36`).
  - Custom filters and derived columns use Criteria subqueries against live `Invoice` and `CustomerPoc`. See `TableSchemas.java:135-180, 223-266` and the book scope in `poc/ScopeResolver.java:135-151`.
  - Dashboard aggregations use raw Criteria on `Invoice.class` joined to `Customer` (`dashboard/DashboardService.java:111-135`). The methods already take `LocalDate today` as a parameter (`DashboardController.java:23-53`), which is a ready-made as-of seam.

---

## 9. Completeness: writes that change state and are not audited

| # | Write | Where | What is lost |
|---|---|---|---|
| 1 | Customer `creditBalance` changes | `PaymentService.applyTo` 197–201; `reverseAllocations` 235–240; `CreditLedger.applyTo` 56; `CreditLedger.refund` 81 | The credit balance can never be reconstructed. |
| 2 | Payment `creditApplied`, and allocations moved by the credit ledger | `CreditLedger.java:47-49, 76-78` | Allocation and credit state of payments |
| 3 | Payment VOIDED status | `PaymentService.voidPayment` 205–218 | No PAYMENT-level row. Only per-invoice `PAYMENT_REVERSED`, plus `DISPUTE_APPROVED` because it is only reachable through a dispute (`DisputeService:267`). |
| 4 | Payment amount, method or notes via `updateAmount` | `PaymentService` 244–270 | No `PAYMENT_UPDATED`. The same caveat as #3 applies (dispute only, `DisputeService:273`). |
| 5 | Invoice cancel with refund, and `replaceItems` | `InvoiceService` 377–391, 393–418 | Covered only by `DISPUTE_APPROVED` on the target (the only callers are `DisputeService:242,253`) |
| 6 | Dispute `update_notes` and `update_meta` direct saves | `DisputeService` 256–260, 277–285 | Same as #5 |
| 7 | Promise `fulfilledAmount` and payment links changing while the status stays the same; `brokenNotifiedAt` | `PaymentPromiseService.applyShares` 246–262 (returns `moved` with no audit), 656 | Fulfilment history |
| 8 | User creation | `UserController.create` 116–146; customer login created in `CustomerService.create` 235–243 | No `USER_CREATED` |
| 9 | Role privilege changes, and role create or delete | `role/RoleController.java` 107–178 | **Who had which rights, and when**. This matters for B1 rights and B2 "approval right". |
| 10 | Password change | `auth/AuthController.java:85-90` | Security trail |
| 11 | Seats and login deleted by a customer delete | `CustomerService.delete` 310–313 | No `POC_REMOVED` or `USER_DELETED`; only `CUSTOMER_DELETED` |
| 12 | Raw-SQL upgrades | `InvoiceSchemaUpgrade` 111–128 (per invoice); `PocSchemaUpgrade` 39–55 (demoted primaries) | Per-row changes |
| 13 | Bulk `@Modifying` JPQL | `NotificationRepository:14`, `UserRepository:31` (blank emails to null), `DocumentRepository:28`, `EmailRepository:32,63`, `EmailRecipientRepository:20,29,38`, `GmailConnectionRepository:21` | These bypass *every* ORM hook as well, including Envers and entity listeners. |
| 14 | Emails and notifications | email and notification packages | By design; these tables are their own record. |

The `derived()` machinery in `AuditTimelineService` (lines 148–223) exists *because* the log began after data existed. Records that predate the log have no CREATED row at all.

---

## 10. Can `audit_logs` reconstruct a record's state as of a past date?

| Entity | Verdict | Why |
|---|---|---|
| Customer | **Partial** | `CUSTOMER_UPDATED` holds a full 7-field snapshot, but `creditBalance` changes elsewhere with no audit (§9 #1). The POC seat set can only be replayed from events, and seats deleted with the customer leave no removal events. |
| Invoice | **Mostly, by merging** | Full `InvoiceDto` on create, update and cancel. `paidAmount` and `status` arrive as partial `InvoicePaymentAudit` events. Refund and replace are visible only through `DISPUTE_APPROVED`. The snapshot's `overdue`, `daysOverdue` and `customerName` are values from write time and must be recomputed. |
| Payment | **Partial** | Void and amount change appear only through `DISPUTE_APPROVED`. `creditApplied` changes from `CreditLedger` are missing. The embedded `invoices` balances are values from write time. |
| Promise | **Partial** | `PROMISE_STATUS_CHANGED` carries only the status, and fulfilment changes are unaudited. |
| Dispute | **Partial** | OPENED and DENIED are on DISPUTE; APPROVED is on the target, reachable through `dispute_id`. |
| Region at time T | **Impossible today** | No region exists, and no snapshot contains a region. |
| Outstanding approvals at T | **Impossible today** | No approval model exists. |
| Anything before audit coverage | **Unknowable** | CREATED rows are missing for legacy data. |

It also cannot answer list, filter, count or aggregate questions efficiently. The JSON is TEXT; H2 has no JSON operators that match Postgres `->>`; and every predicate in `TableSchemas`, `ScopeResolver` and `DashboardService` is SQL Criteria. Reconstructing means loading every row and replaying it in Java before filtering, sorting, paging or summing. That breaks `PageResponse` totals, tiles and dashboards.

---

## 11. B3 design options, evaluated against this codebase

### (1) Reconstruct from the audit log — rejected as the mechanism

- **Incomplete:** see §9 and §10. Closing the gaps would need 12+ new call sites, and any future write path can forget one again. The gap list proves call-site capture drifts.
- **Hard to replay:** snapshot shapes differ per action and change as DTOs evolve. The record types gained `dueDate`, `paymentTerm` and `version` over time.
- **Wrong kind of query:** filtering, sorting and aggregation would run in memory, which is incompatible with `TableQueryExecutor.run/count/aggregate` and with `DashboardService`.
- **Keep it for** the narrative History tab (the who/why/via-dispute/via-approval/via-rule context), and optionally to seed pre-baseline versions from full `*_UPDATED` snapshots.

### (2) SCD2 history tables (`valid_from`, `valid_to`) written on every save — right *shape*, wrong if hand-wired at call sites

- **As-of predicates are plain SQL.** `valid_from <= :t and (valid_to is null or valid_to > :t)` works on H2 and Postgres, so the Criteria engine can be reused.
- **Region and approval questions fit.** Region membership at T is just `region_id` on the version row. Outstanding approvals at T is an interval predicate on the pending-change table.
- **The hard part is capture.** Writing history from services repeats the gap problem. JPA `@PostUpdate` callbacks may not use the EntityManager, and they fire during the commit-time flush, *after* Spring's `beforeCommit` synchronisations. The correct hook is Hibernate's `PostInsert`, `PostUpdate`, `PostDelete` and collection event listeners plus an action-queue `BeforeTransactionCompletionProcess`. That is a reimplementation of Envers, including collections: `Invoice.items` (`Invoice.java:45-47`), `Payment.allocations` (`Payment.java:55-57`), and the promise `@ManyToMany` links `payment_promise_invoices` and `payment_promise_payments` (`PaymentPromise.java:76-88`).

### (3) Hibernate Envers — recommended as the capture engine for (2)

**Status.** Envers is not in `backend/pom.xml`. `spring-boot-dependencies:3.3.5` manages `org.hibernate.orm:hibernate-envers` at `${hibernate.version}` = `6.5.3.Final` (BOM lines 68, 700–702), so it can be added without a version. The jar is **not** in the local `~/.m2` (only `hibernate-core` and `hibernate-platform` are cached), so the first build needs network access.

**Why it fits:**
- It hooks Hibernate events, so it captures every ORM write, including items 1–7 of §9, without touching call sites.
- It writes in the same transaction as the change.
- `ddl-auto update` creates the `_aud` and `revinfo` tables on Postgres, H2 in memory (tests) and H2 file (azurefree).
- `_aud` tables have no foreign keys to live tables. They survive deletes, and they don't break `IntegrationTestBase.resetTransactionalData`'s `deleteAll()` ordering (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java:65-89`).
- A custom `@RevisionEntity` can carry `userId` (via `CurrentUser.idOrNull()`), `origin` (USER, RULE, SYSTEM, APPROVAL), `ruleId`, `approvalId` and a request id.

**Suggested configuration** (verify the property and class names against 6.5.3):
- `org.hibernate.envers.audit_strategy` = ValidityAuditStrategy, which gives SCD2 `REVEND`.
- `org.hibernate.envers.audit_strategy_validity_store_revend_timestamp=true`
- `org.hibernate.envers.store_data_at_delete=true`
- Audit Customer, Invoice, InvoiceItem, Payment, PaymentAllocation, PaymentPromise, Dispute and CustomerPoc, plus the new Region-bearing columns, Task, and the pending-change entity if it is ever mutated in place.
- Mark relations to User and Product with the not-audited target mode, or audit them too. Auditing User drags Role and the Privilege many-to-many along.

**Reading as-of.**
- **Single record** (detail screens, rule evaluation, placeholder resolution): `AuditReader.find(Invoice.class, id, date)`.
- **Lists, tiles and dashboards:** do not use Envers' own query API, which is not Criteria and has no subqueries or SUM/CASE. Instead:
  1. Resolve `R = auditReader.getRevisionNumberForDate(t)`.
  2. Query read-only mirror entities, e.g. `@Entity @Immutable @Subselect("select * from invoices_aud") @Synchronize("invoices_aud") class InvoiceAsOf`. Give them the same attribute names as the live entity (`id`, `invoiceNumber`, `total`, `paidAmount`, `status`, `dueDate`, `invoiceDate`, `createdAt`, `regionId`, plus `@ManyToOne(insertable=false, updatable=false) Customer customer` and `User salesPoc`), with `rev`, `revend` and `revtype` as extra attributes. A `@Subselect` entity produces no DDL, so it does not collide with the Envers table under `ddl-auto`.
  3. Run the existing `TableQueryExecutor.run(InvoiceAsOf.class, TableSchemas.INVOICES, query, scope + asOf(R), fetches)` with one extra `PredicateFactory`: `rev <= R and (revend is null or revend > R) and revtype <> 2`.

  `TableSchemas` paths such as `root.get("total")` and `nested("customer","name")` then work unchanged. Note that `customerName` then reads the *live* name unless you join `CustomerAsOf`. In Hibernate 6 Criteria that is an entity join with the same R predicate.

**Where it does not fit:**
- Revision timestamps are milliseconds, taken when the revision is created, not at commit.
- Bulk `@Modifying` and raw JDBC bypass it (§9 #12–13; none of them touch the PRD entities today, except the startup upgrades).
- Every audited save gets extra INSERTs and an UPDATE of the previous row's `REVEND` inside the user's transaction.

### (4) Postgres temporal tables — rejected

- Postgres 16 (`docker-compose.yml`) has no SQL:2011 system versioning. You would need the `temporal_tables` C extension, which is not available on every managed Postgres, or hand-written plpgsql triggers.
- H2 is used by all tests and **by the `azurefree` production profile** (`application.yml:129-144`). That means either two trigger implementations (H2 triggers are Java `org.h2.api.Trigger` classes) or tests that never exercise as-of.
- `ddl-auto update` does not manage triggers or history columns. A column added to a live table by `update` silently goes missing from the history table and trigger unless a SchemaUpgrade keeps them in step.

### Recommendation

Adopt **(2) implemented with (3)**: Envers with the validity strategy, a custom revision entity, and `@Subselect` as-of mirror entities feeding the existing Criteria query engine. Around it:

- **Pending changes (B2)** go in their own table, which is naturally an interval table: `requested_at`, `decided_at`, `status`, `region_id` copied at request time, `entity_type`, `entity_id`, `proposed_json`, `maker_id`, `checker_id`. The query "outstanding at T" is `requested_at <= T and (decided_at is null or decided_at > T)`, with no history machinery needed. If you also want it covered by Envers, add `@Audited`.
- **Baseline backfill is mandatory before going live.** Add an `EnversBaselineUpgrade` SchemaUpgrade using the §8 pattern that inserts one `revinfo` row and copies `x` into `x_aud` with that revision and `revtype 0` for every audited table. Without it, the first update of a pre-existing row fails with "Cannot update previous revision for entity …" under the validity strategy. **That would break user saves.** Also expose `historyStartsAt` and refuse, or flag, as-of dates before it.
- **Authorisation semantics** (decide and document): check the caller's *current* rights against the record's *as-of* region. Rights history is not stored today; role changes aren't even audited (§9 #9).
- **Thread "today" through derived values:** `TableSchemas.overduePredicate` hard-codes `InvoiceDates.today()` (line 81), `InvoiceDtos.from` does too (lines 86, 117), `CustomerService.toDtos` (line 164), `InvoiceService.tiles` (line 321) and `PaymentPromiseService.targetStatus` (line 272). As-of reads must pass the as-of date as "today", and `DashboardService` methods already take it as a parameter.
- **Fallback if a new dependency is vetoed:** hand-rolled SCD2 via a Hibernate `Integrator` that registers post-insert, post-update and post-delete listeners writing `*_versions` rows in a before-completion process. Keep the same `@Subselect`-style read path. Never capture at call sites.

---

## 12. Extension points by PRD item (audit and history perspective)

- **A1/A5 triggers and outbox.** `AuditService.record` is already the single choke point that sees `(entityType, entityId, action, after)` inside the save's transaction for CUSTOMER, INVOICE and PAYMENT creates and updates. Writing a transactional-outbox row there, filtered to trigger-relevant actions, gives "publish with the commit; nothing lost". Caveats:
  - Coverage gaps: payment void and amount changes are not recorded on PAYMENT, and customer credit changes are not recorded at all.
  - The PAYMENT_APPLIED and PAYMENT_REVERSED rows are the invoice-updated signal for `paidAmount` and `status`.
  - Product and User writes are not atomic with their audit.
  - The outbox insert must be trivial and must never throw into the save, because `record`'s REQUIRED propagation would roll the save back.

  If Envers is adopted, an `EntityTrackingRevisionListener` sees every audited entity change per revision. That is a more complete trigger source than the audit rows.
- **A3/A5 rule-executed actions.** Add nullable `rule_id` and `origin` columns to `AuditLog`. Nullable columns are safe under `ddl-auto update` + `halt_on_error`. Surface them in `AuditEntryDto` and in the UI meta line, like `via dispute #N` (`audit_history_panel.dart:386`), and do not rely on a null actor meaning "automatic". Keep action names at 40 characters or fewer.
- **A6 Tasks.** For task history on the record's timeline, add `TASK` to:
  - `AuditController.SUPPORTED`, `CUSTOMER_READABLE` if applicable, and `VIEW_PRIVILEGE` (lines 36–47)
  - a `Related.task` plus a relation loader in `AuditTimelineService.timeline` (lines 66–112, 316–353)
  - `_typeOrder`, `_typeLabels`, `_routeFor`, `_iconFor` and `_actionLabels` in the panel (lines 188–257)

  The field names `assignees`, `assignedBy` or `createdBy` in task snapshots matter; see gotcha 4 in §14.
- **B1 regions.**
  - Add `regionId` to **every** snapshot record: `CustomerAuditSnapshot`, `PromiseAuditSnapshot`, `InvoiceDto`, `PaymentDto` and so on. Otherwise deleted records' history cannot be region-scoped, because `readOrGone` (lines 118–124) has only the trail.
  - Make `ensureCallerCanSee` region-aware.
  - Region changes of a record should be a distinct audit action such as `REGION_CHANGED`, with before/after region ids.
  - Consider indexing `(entity_type, entity_id, created_at)`.
- **B2 maker-checker.** The closest existing template is disputes:
  - A proposal is stored as JSON: `Dispute.proposedChangeJson` (`dispute/Dispute.java:41-43`).
  - The approver applies it through the normal service methods: `DisputeService.approve`, `applyChange` (lines 92–117, 219–288).
  - The applied change is audited on the **target** with before/after snapshots and a link id: `record(targetType, targetId, "DISPUTE_APPROVED", before, after, resolverId, disputeId, notes)` (lines 108–113).

  Copy this pattern with an `approval_id` column. Record the maker as the actor, or add an `approved_by_user_id`.

  **While a change is pending, do not write `XXX_UPDATED`.** Only record request, approve and reject events under distinct action names. `AuditTimelineService.original()` (lines 225–236) and `derived()` treat recorded rows as facts.
- **B3.** Covered in §11: Envers (validity strategy) plus `@Subselect` as-of entities plus an `asOf` `PredicateFactory` in `TableQueryExecutor` calls. Also:
  - `AuditReader.find` for single-record and rule evaluation, so the rule engine's condition evaluator and placeholders work on either a live or an as-of entity instance.
  - An optional `asOf` parameter on `GET /api/audit` that filters `created_at <= t`. Add a `created_at` index.

---

## 13. What the History tab needs in order to show pending (maker-checker) changes

**Backend: `AuditController.AuditEntryDto`** (`AuditController.java:61-65`) and `AuditTimelineService.Entry` (lines 49–52):
- New fields: `boolean pending`, `approvalId`, `approvalStatus` (PENDING, APPROVED, REJECTED, WITHDRAWN), `requestedByUserId` / username, `decidedByUserId` / username, `decidedAt`, `regionId`, and optionally `threshold` and the value that triggered it.
- Pending entries should come from the **pending-change table**, not `audit_logs`, because they have not taken effect. Merge them in `timeline()` the way `derived()` entries are merged (line 127), and sort them into the same newest-first order.
- Show `beforeJson` = current state and `afterJson` = proposed state.
- `withoutPocIdentity` must process pending entries as well: strip staff identities and the new field names, and hide the actor.
- For as-of: include requests where `requested_at <= t < decided_at` and render them as "outstanding at t".
- Access: only callers who can see the record; approve and reject are shown only to holders of the approval right in the record's region who are **not** the maker.

**Frontend: `audit_history_panel.dart`:**
- `AuditEntry.fromJson` (lines 58–82) should parse the new fields.
- **`stableKey` (lines 55–56) must add a `pending:<approvalId>` branch.** Today an entry with no id is assumed to be derived, and two pending entries could collide.
- `_actionLabels` needs `CHANGE_REQUESTED`, `CHANGE_APPROVED`, `CHANGE_REJECTED` and `CHANGE_WITHDRAWN`.
- `_headline` needs the same per-action money and status arrows (lines 103–167).
- A "Pending approval" chip or colour on `_AuditTile` (lines 361–431), and a meta line "requested by X · awaiting approval in <region>" alongside the existing "via dispute #N" pattern.
- Approve and Reject actions, gated client-side and enforced on the server.
- `_AuditDetails` (lines 433–477): label the panes "Current" and "Proposed" instead of Before/After when pending.
- A filter chip for pending items only.
- Screens that save (the `ref.invalidate(auditHistoryProvider)` sites in §7.3) must also handle a save that returns "pending" rather than applied, re-fetch history, and show a banner on the record.

---

## 14. Risks and gotchas checklist

1. **The audit insert can break the save.** Because `record` is REQUIRED and inside the save's transaction, an action name over 40 characters, an entity type over 30, or any constraint failure rolls back the user's change. Keep automation and outbox work out of this path, or make it trivially safe.
2. **`ProductController` and `UserController` are not transactional.** Their audit rows are not atomic with the change. Do not use their audit rows as a reliable trigger or outbox source.
3. **`currentUser.require()` in services** throws in scheduler or consumer threads (§6). The promise sweeper works only because its path uses a null actor.
4. **Customer redaction by substring:**
   - Any new action whose name *contains* `"POC"` is hidden from customers (`AuditTimelineService.java:135`).
   - Any JSON field containing `"poc"` is stripped.
   - The staff-name fields are a fixed list (lines 278–279), so new fields such as `approvedBy`, `requestedBy`, `assignees` or `decidedBy` **will leak staff identity to customers** unless they are added to that list.
5. **Derived values in snapshots** (`overdue`, `daysOverdue`, `balance`, embedded invoice balances in `PaymentDto`, `customerName`) are true only for their write time. Never treat them as as-of truth.
6. **`PAYMENT_APPLIED` and `PAYMENT_REVERSED` are recorded on INVOICE**, with `paymentId` inside the JSON. A payment's own timeline does not include them. Dispute approvals are recorded on the target, not on DISPUTE.
7. **`INVOICE_DUE_DATES_BACKFILLED` uses entity id 0.** Skip it in any reconstruction or trigger logic.
8. **Envers validity strategy plus legacy rows means failed saves** unless the baseline backfill runs first. That backfill belongs in a SchemaUpgrade, which runs before `DataSeeder` and before the sweeper.
9. **`halt_on_error: true` plus `ddl-auto update`.** New NOT NULL columns on populated tables (for example `region_id NOT NULL`) stop startup. Add them nullable, backfill in a SchemaUpgrade, then tighten, using the `InvoiceSchemaUpgrade.enforceDueDateNotNull` pattern (lines 146–164, which is dialect-specific). New `@Enumerated(STRING)` values need check-constraint widening like `EmailSchemaUpgrade.widen`, and `_aud` copies of enum columns need the same treatment.
10. **H2 is used in production (`azurefree`).** Postgres-only features must degrade gracefully, the way partial unique indexes are created only on Postgres.
11. **`CountingStatements` (tests) matches on table-name substrings.** `reads("customer_pocs", …)` in `EmailContextTest.java:130` would also count selects on `customer_pocs_aud` or `customer_pocs_versions`. Name history tables with that in mind, or confirm no history selects happen in measured blocks.
12. **`IntegrationTestBase.resetTransactionalData`** deletes live tables with `deleteAll()` and does not clear `audit_logs` or disputes. History tables must not have foreign keys to live tables.
13. **Timeline cost.** `timeline()` loads every related record and all of their audit rows with no server paging. Adding pending and as-of data makes this heavier; consider server-side paging along with the new `created_at` index.
14. **Rights history does not exist.** Role and privilege edits are not audited (§9 #9). "Someone else holding the approval right *at the time*" can only be proven if the approval event records the approver's right or region at decision time.
15. **Time semantics.** All options timestamp at persist or revision creation, not at commit. Near-simultaneous transactions can appear slightly out of commit order in as-of answers. Also be explicit that the "as of a date" boundary is the end of that day in UTC.