# Engineering map: write paths for Customer, Invoice, Payment, PaymentPromise and Dispute

All paths are relative to `backend/src/main/java/com/geneinvoice/` unless they start with `backend/`. Line numbers are for the current `main` (HEAD `321150e`). I read every file in `customer/`, `invoice/`, `payment/`, `promise/` and `dispute/` in full, plus `common/{RowVersionUpgrade,Money,FieldLimits,GlobalExceptionHandler,BadRequestException,NotFoundException,Strings}.java`. To follow the calls out of this area I also read `audit/AuditService.java`, `audit/AuditLog.java`, `auth/CurrentUser.java`, `poc/{PocService,ScopeResolver,CustomerPoc,PocType,PocSchemaUpgrade}.java`, `notification/NotificationService.java`, `common/bulk/BulkExecutor.java`, `common/query/{TableQueryExecutor,TableSchemas}.java`, `email/{RoleRef,EmailRole,RoleLevel,EmailAddressing,EmailTargets,RecipientSet,EmailText,EmailDispatcher,EmailSweepScheduler}.java`, `config/OpenEntityManagerInViewConfig.java`, `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml` and `backend/src/test/java/com/geneinvoice/IntegrationTestBase.java`.

---

## 1. Conventions a new feature must copy

### 1.1 Entity shape
Every core entity uses the same Lombok and JPA preamble. From `invoice/Invoice.java:15-88` (annotations shortened here, content unchanged):
```java
@Entity
@Table(name = "invoices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id") private Customer customer;
    @Column(nullable = false, precision = 14, scale = 2) @Builder.Default private BigDecimal total = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) @Builder.Default private InvoiceStatus status = InvoiceStatus.UNPAID;
    @Version @Column(name = "version") private Long version;
    @Column(updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { this.createdAt = Instant.now(); ... }
```
- Table names are plural snake case. Indexes are declared in `@Table(indexes = …)` with an `idx_` prefix (`promise/PaymentPromise.java:17-22`, `dispute/Dispute.java:9-13`).
- Money is always `BigDecimal` with `precision = 14, scale = 2`.
- Enums are stored with `EnumType.STRING` and an explicit length.
- Timestamps are `Instant` and filled in `@PrePersist`. Only Promise and Dispute have `updatedAt` via `@PreUpdate` (`PaymentPromise.java:96-108`, `Dispute.java:62-74`). **Customer, Invoice and Payment have no `updatedAt`.**
- Polymorphic references without an FK follow the Dispute pattern: `targetType` enum plus `targetId` Long, with a composite index (`Dispute.java:11,31-36`).

### 1.2 DTO shape
- There is one holder class per aggregate (`XDtos`) containing nested `public record`s: `CustomerDtos.java:14-54`, `InvoiceDtos.java:19-152`, `PaymentDtos.java:17-84`, `PromiseDtos.java:16-81`, `DisputeDtos.java:11-35`.
- Request records carry Jakarta validation. Example from `PaymentDtos.java:19-28`:
  ```java
  @NotNull @Positive @Digits(integer = 12, fraction = 2, message = Money.CENTS_MESSAGE) BigDecimal amount,
  @Size(max = FieldLimits.PAYMENT_METHOD) String method,
  ```
- Response records have static factories `from(entity)` and `from(entity, boolean includePoc)`. When POC fields are hidden they are set to `null`, and so is `pocMissing` (`InvoiceDtos.java:81-99`, `PaymentDtos.java:53-73`).
- Staff-only fields are nulled for customer logins, e.g. `showStaff ? p.getCreatedByUserId() : null` (`PaymentPromiseService.java:591,596,609`) and `currentUser.isCustomer() ? null : d.getResolvedByUserId()` (`DisputeService.java:163`).
- **Convenience constructors exist on request records and are called from services.** Adding a field to one of these records breaks the calls below or silently defaults the new field:
  - `InvoiceDtos.CreateInvoiceRequest(…5 args)` at `InvoiceDtos.java:30-33`
  - `UpdateInvoiceRequest(notes, salesPocUserId)` at `:43-45`, used by `InvoiceService.reassignSalesPoc` at `InvoiceService.java:222`
  - `new PaymentDtos.UpdatePaymentRequest(null, userId)` at `PaymentService.java:137`
  - The **canonical** 5-arg `new PromiseDtos.UpdatePromiseRequest(...)` at `PaymentPromiseService.java:149-150`. This one breaks at compile time if the record changes.

### 1.3 Controller shape
The same pattern appears in `invoice/InvoiceController.java:31-98`:
```java
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {
    private TableSchema schema() { return TableSchemas.INVOICES.visibleTo(currentUser.isCustomer()); }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.INVOICE_MANAGE + "')")
    public InvoiceDtos.InvoiceDto create(@Valid @RequestBody InvoiceDtos.CreateInvoiceRequest req) {
        return InvoiceDtos.InvoiceDto.from(service.create(req), scopeResolver.canSeePoc());
    }
```
- Every aggregate has the same standard endpoints: list (`TableQuery.parse(schema(), page, size, sort, FilterParams.from(request))`), `/summary` (tiles), `/{id}`, create, update, `/bulk`, `/export`. Context params are merged into filter chips as `"customerId:eq:" + id` (`InvoiceController.java:169-174`).
- Creates return 200 with the DTO, not 201. Deletes return `void`.
- The HTTP verbs are inconsistent: Customer uses `PUT` (full replace), Invoice and Payment use `PATCH` (a null field means "leave it"), Promise uses `PUT` (full replace, and `notes` is overwritten with null when omitted: `PaymentPromiseService.java:118`).
- Bulk controllers follow this pattern (`InvoiceController.java:100-126`). `resolveIds` intersects the requested ids with scope-permitted ids (`:159-167`). Actions are validated before anything runs.
  ```java
  case "CANCEL" -> bulkExecutor.run(req, ids, truncated, BulkExecutor.eligibility(service::cancel));
  ```
- `BulkExecutor` runs **each id in its own `REQUIRES_NEW` transaction** (`common/bulk/BulkExecutor.java:20-25,68`). A `BadRequestException` becomes a *skipped* row through `eligibility` (`:38-46`). A `ConcurrencyFailureException` at commit also becomes skipped (`:72-78`). The result buckets are only succeeded, failed and skipped (`:83-84`).
- `DisputeController` breaks the pattern. It runs `queryExecutor` itself in `list` and `export` (`DisputeController.java:45-51,83-97`), and it uses `TableSchemas.DISPUTES` directly without `visibleTo`.

### 1.4 Service shape, scoping and transactions
- Services are annotated `@Service @RequiredArgsConstructor` with a `public static final String ENTITY` (`CUSTOMER`/`INVOICE`/`PAYMENT`/`PROMISE`/`DISPUTE`). These strings are the audit `entity_type` values (`CustomerService.java:50`, `InvoiceService.java:43`, `PaymentService.java:41`, `PaymentPromiseService.java:56`, `DisputeService.java:35`).
- Writes are `@Transactional` (REQUIRED) and reads are `@Transactional(readOnly = true)`. Nested calls join the outer transaction. Self-invocation (for example `reassignSalesPoc → update` at `InvoiceService.java:220-223`, `PaymentPromiseService.update → get` at `:107`) does not go through the proxy, so the `readOnly` flag on `get` has no effect inside writes.
- The read and "in book" check (the same for read and write today) looks like this in `InvoiceService.java:281-286`:
  ```java
  private void requireInBook(Long id) {
      if (!queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES, id,
              scopeResolver.forInvoices().predicates())) {
          throw new NotFoundException("Invoice not found");
      }
  }
  ```
  Out-of-scope and other-customer lookups deliberately answer **404, not 403** (AUTH-08: `InvoiceService.java:264-270`, `PaymentService.java:276-281`, `PaymentPromiseService.java:509-518`). Exceptions: `CustomerService.get` throws `AccessDeniedException` for another customer's id (`CustomerService.java:131-134`), and `DisputeService.get` does the same (`DisputeService.java:147-150`).
- Scope predicates come from `ScopeResolver` (`poc/ScopeResolver.java`):
  - `forInvoices`, `forPayments` and `forPromises` all call `build("customer", PocType, lockedColumn, bookPredicate)` (`:51-64,111-129`).
  - A customer login is restricted to its own customer. `SCOPE_OVERRIDE` sees everything. Otherwise the caller sees only rows where they are the record's POC. A user who is not assignable as that POC type sees nothing (`nothing()` at `:131-133`).
  - `forCustomers` means "has a POC seat OR owns an invoice as Sales POC" (`:66-87`).
  - `forDisputes` restricts only customer logins; **staff see every dispute** (`:89-97`).
- `TableQueryExecutor.inScope` returns `true` immediately when the scope list is empty (`common/query/TableQueryExecutor.java:72-75`).

### 1.5 Exceptions and HTTP status
From `common/GlobalExceptionHandler.java`:

| Throw | HTTP | Where it is handled |
|---|---|---|
| `NotFoundException(msg)` | 404 | `:34-38` |
| `BadRequestException(msg)` | 400 | `:40-43` |
| `GlobalExceptionHandler.InvalidFieldsException(Map.of(field,msg))` | 400 with a field map | `:73-90` (used by `CustomerService.settableTerm` `:322-328` and `InvoiceService.fieldError` `:202-204`) |
| Bean validation failure | 400 with a field map | `:63-71` |
| `AccessDeniedException` | 403 with a fixed message | `:57-61` |
| `OptimisticLockingFailureException` or any `ConcurrencyFailureException` | 409 "This record changed while you were working on it; reload and try again" | `:139-145` |
| `DataIntegrityViolationException` | 409 "This change conflicts with existing data" | `:133-137` |
| anything else | 500 | `:147-152` |

- "Not allowed to change the POC" is reported as **400** `BadRequestException("You may not change the Sales POC")` (`InvoiceService.java:134-136`, `PaymentService.java:121-123`, `PaymentPromiseService.java:124-126`). The customers bulk endpoint is the one exception: it deliberately throws 403 (`CustomerController.java:133-137`, D-46).
- `BadRequestException` is a plain `RuntimeException`. `PocService.AlreadyAssignedException extends BadRequestException` (`PocService.java:165-169`).

### 1.6 Privileges
- `@PreAuthorize("hasAuthority('" + Privileges.X + "')")` is used on every endpoint. The constants live in `privilege/Privileges.java:8-52`: CUSTOMER_VIEW/MANAGE, INVOICE_VIEW/MANAGE, PAYMENT_VIEW/MANAGE, PROMISE_VIEW/MANAGE/OVERRIDE, DISPUTE_CREATE/VIEW/MANAGE, POC_VIEW, POC_ASSIGN, POC_ASSIGNABLE_{SALES,SUCCESS,COLLECTION}, SCOPE_OVERRIDE, EXPORT_DATA. `Privileges.ALL` is at `:54-71`.
- In-service privilege checks:
  - `currentUser.canAssignPoc(userRepository)` (`auth/CurrentUser.java:41-45`)
  - `userRepository.hasPrivilege(userId, priv)`, called from `PocService.requireAssignable` `:67` and from ScopeResolver
  - `currentUser.has(priv)` (`CurrentUser.java:35-39`)
- **All authorities are global.** Nothing is keyed by region or record.

### 1.7 Audit
- Signature (`audit/AuditService.java:18-32`, `@Transactional` REQUIRED, joins the caller):
  ```java
  public AuditLog record(String entityType, Long entityId, String action,
                         Object before, Object after, Long userId, Long disputeId, String reason)
  ```
- `before` and `after` are Jackson-serialized to TEXT (`:47-54`). Column limits in `audit/AuditLog.java:24-48`: `entity_type` 30 chars, `action` 40 chars, `reason` 500 chars (longer reasons are truncated by `fit`, `:34-40`).
- The usual call is shown below. The actor is `currentUser.require().getId()` almost everywhere. `currentUser.idOrNull()` is used in `InvoiceService.create` (`:88,92`), `PaymentService.record` (`:102`) and `auditMovements` (`:147`). `null` is used for system changes: `PROMISE_STATUS_CHANGED` (`PaymentPromiseService.java:265-266`) and the backfill (`InvoiceSchemaUpgrade.java:52-54`).
  ```java
  auditService.record(ENTITY, id, "INVOICE_UPDATED", before,
          InvoiceDtos.InvoiceDto.from(saved), currentUser.require().getId(), null, null);
  ```
- Snapshot types differ from action to action (see §2), which matters for B3.

### 1.8 Notifications
- `NotificationService.notify(userId, type, title, message, link)` and `notifyAdmins(type, title, message, link)`. `notifyAdmins` selects users by **role name "ADMIN"**, not by privilege (`notification/NotificationService.java:18-38`). Both are in-transaction inserts.
- `PocService.notifyAssignee(User assignee, PocType type, String what, String link)` does nothing for `null` or self-assignment. **It calls `currentUser.require()`**, so it throws when there is no authentication (`poc/PocService.java:267-275`).
- Types used in this area: `POC_ASSIGNED`, `PROMISE_BROKEN` (`PaymentPromiseService.java:57,643-657`), `DISPUTE_OPENED/APPROVED/DENIED` (`DisputeService.java:37-39`).

### 1.9 Money, strings and limits
- `Money.requireCents(amount, field)` throws `BadRequestException(field + " must have at most 2 decimal places")` (`common/Money.java:12-16`).
- `Money.scale` gives 2dp HALF_UP (`:18-20`).
- `Money.format` gives ₹ with Indian digit grouping, e.g. `₹1,23,456.00` (`:22-40`). Human-readable money in notifications and email suggestions goes through `Money.format` (`PaymentPromiseService.java:653`, `email/EmailText.java:48-50`). **A4 placeholders should use it.**
- `Strings.trim`, `Strings.blankToNull` (CP-12, CP-15) and `Strings.escapeLike` (`common/Strings.java`).
- `FieldLimits` constants (`common/FieldLimits.java:7-28`) are used in `@Size(max=…)` and in `DisputeService.text()`. Note that `FieldLimits.FULL_NAME = 120` while `customers.name` has length 150 (`Customer.java:23`).

### 1.10 Schema upgrades and row versions
- `ddl-auto: update` is used for dev, prod and azurefree, and **`hibernate.hbm2ddl.halt_on_error: ${SCHEMA_HALT_ON_ERROR:true}`** (`backend/src/main/resources/application.yml:10-24`). The comment there explains that `update` cannot add a NOT NULL column to a populated table, and that startup now **fails** when it tries.
- Tests use H2 `create-drop` (`backend/src/test/resources/application-test.yml:1-16`), so this failure **never shows up in tests**.
- Upgrade class skeleton (`customer/CustomerSchemaUpgrade.java:17-42`, `poc/PocSchemaUpgrade.java:14-37`, `common/RowVersionUpgrade.java:14-36`):
  ```java
  @Component @Slf4j
  class XSchemaUpgrade implements InitializingBean {
      private final DataSource dataSource;
      XSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) { this.dataSource = dataSource; } // EMF param forces Hibernate DDL first
      @Override public void afterPropertiesSet() {
          try (Connection connection = dataSource.getConnection()) { ...; if (!connection.getAutoCommit()) connection.commit(); }
          catch (SQLException e) { log.warn("Could not finish the ... upgrade: {}", e.getMessage()); }
      }
  }
  ```
- Patterns to reuse:
  - **Chunked backfill by id** with `CHUNK = 1000` (`invoice/InvoiceSchemaUpgrade.java:60-88,96-128`).
  - **Enforce NOT NULL after the backfill**, with separate H2 and PG syntax (`:146-164`).
  - `isNullable` via `information_schema`, which needs upper-case identifiers and `schema()` on H2 and `current_schema()` on PG (`:166-178`).
  - Partial unique indexes on Postgres only (`CustomerSchemaUpgrade.java:58-70`, `PocSchemaUpgrade.java:57-69`).
  - The upgrade writes its own audit row (`InvoiceSchemaUpgrade.java:51-55`, entity id `0`).
- `RowVersionUpgrade.TABLES = List.of("customers", "invoices")` sets `version = 0 where version is null` (`common/RowVersionUpgrade.java:18,38-48`). If you add `@Version` to payments, promises, disputes or a new table that already has rows, **add that table here**. Otherwise existing rows keep a null version. Spring Data's `isNew()` treats a null wrapper version as new, so `save()` then calls `persist` on a detached entity and fails.

### 1.11 Pessimistic lock order (the "money path")
- The rule is: lock the **customer, then its invoices by ascending id** (PPD-01). See `PaymentService.java:56-60,171-177,221-224,255-257` and `CreditLedger.java:30-34,63-64`.
- Lock methods: `CustomerRepository.findByIdForUpdate` (`customer/CustomerRepository.java:13-15`), `InvoiceRepository.findByIdForUpdate` / `findAllByIdForUpdate` (ordered by id) / `findByCustomerIdForUpdate` (ordered by id) (`invoice/InvoiceRepository.java:17-27`), `DisputeRepository.findByIdForUpdate` (`dispute/DisputeRepository.java:14-16`).
- The invoice number sequence row is locked by `InvoiceNumbers.next()` (`invoice/InvoiceNumbers.java:32-44`, `Propagation.MANDATORY`). **Every invoice create serializes on this one row.**
- POC seat changes lock the customer row first (`PocService.java:125,173,229`).

### 1.12 Tests
- `IntegrationTestBase` uses `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")` (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java:41-45`).
- `resetTransactionalData` deletes tables **in a fixed FK order** (`:65-89`) and does not touch disputes or audit logs. Any new table with an FK to customers, invoices or payments (tasks, outbox with FKs, pending changes, region history) **must be added to this list**. Otherwise `customerRepository.deleteAll()` fails on every test.
- Test helpers build `Customer.builder().name(name).build()` with nothing else (`:119-125`). A required region column breaks every test unless it has a default.
- `actAs(user)` and `as(user)` set up a principal (`:132-144`).
- Existing tests in this area: `customer/CustomerFieldHygieneTest`, `invoice/DueDateTest`, `promise/PromiseFiguresAndPocTest`.

---

## 2. Per-aggregate maps

### 2.1 Customer

**Fields** (`customer/Customer.java`):

| Field | Line | Type and constraints |
|---|---|---|
| id | 19-21 | Long IDENTITY |
| name | 23-24 | not null, 150 |
| phone | 26-27 | 30 |
| email | 29-30 | 120; unique `lower(email)` partial index on PG only (`CustomerSchemaUpgrade.java:58-70`); duplicates block the index (`:32-37`) |
| creditBalance | 35-37 | not null 14,2, default ZERO. **Derived-by-code**: kept equal to the sum of ACTIVE payments' `creditApplied`. **No API edits it directly.** |
| paymentTerm | 39-41 | enum `PaymentTerm`, nullable; `CUSTOM` is refused (`CustomerService.java:322-328`) |
| version | 43-45 | `@Version` |
| createdAt | 47-48, 50-53 | |

There is no `updatedAt` and no region.

**Relationships:**
- Referenced by `Invoice.customer`, `Payment.customer` and `PaymentPromise.customer` (JPA FKs).
- `Dispute.customerId` is a plain column with **no FK**.
- `CustomerPoc.customer` (`poc/CustomerPoc.java:29-31`).
- `User.customerId` for the login (`user/User.java:42-43`, a plain column).

**Endpoints** (`CustomerController.java`):

| Endpoint | Privilege | Lines |
|---|---|---|
| GET list | CUSTOMER_VIEW | 46-54 |
| `/summary` | CUSTOMER_VIEW | 56-61 |
| `/{id}` | CUSTOMER_VIEW | 63-67 |
| POST | CUSTOMER_MANAGE | 69-73 |
| PUT `/{id}` | CUSTOMER_MANAGE | 75-80 |
| DELETE `/{id}` | CUSTOMER_MANAGE | 82-86 |
| POC seat endpoints | POC_VIEW / POC_ASSIGN; customer logins refused | 88-120 |
| bulk `ADD_POC` | CUSTOMER_MANAGE | 122-157 |
| export | EXPORT_DATA and CUSTOMER_VIEW | 159-187 |

**Write methods** (`customer/CustomerService.java`):
- `create` at `:204-248`, one transaction:
  1. Trims the username and rejects a duplicate case-insensitively (`:208-213`).
  2. Normalizes the email and checks it is unique against users and customers (`:217-222`).
  3. `repository.save(Customer…)` at `:226-232`.
  4. `Passwords.require` runs **after** the insert (`:234`); a failure rolls the insert back.
  5. Saves the linked `User` with role CUSTOMER (`:235-243`).
  6. Audits `CUSTOMER_CREATED` (before `null`, after `CustomerAuditSnapshot`, `:245-246`).
  - No notification. **No scope check at all.**
- `update` at `:250-297`:
  1. `findById`, **not locked** (`:252`).
  2. `requireInBook` (`:254`).
  3. Takes the `before` snapshot (`:255`) and checks the email is unique (`:260-267`).
  4. Full replace of name, phone, email, address and paymentTerm. **A null paymentTerm clears it** (`:269-273`).
  5. `save` (`:274`).
  6. Syncs the linked user's fullName and email, and optionally the password plus `credentialsChangedAt` (`:276-287`).
  7. Audits `CUSTOMER_UPDATED` (`:288-289`), plus `CUSTOMER_PAYMENT_TERM_CHANGED` with plain string before and after values if the term changed (`:290-295`).
  - The request has no version field (`CustomerDtos.java:38-45`).
- `delete` at `:299-320`:
  1. `requireInBook`, then takes a snapshot.
  2. `documentCascade.onCustomerDeleted` (soft delete; locks the customer) and `emailCascade.onCustomerDeleted` (marks emails).
  3. Deletes the POC seats and the login, then `deleteById`.
  4. Audits `CUSTOMER_DELETED`.
  - With invoices, payments or promises present, the FK fails at flush and the request returns a **409 through `DataIntegrityViolationException`**. That FK failure is the only thing stopping the delete.
- **Indirect writes to Customer that write no CUSTOMER audit row:**
  - `creditBalance`: `PaymentService.applyTo` `:197-201`, `PaymentService.reverseAllocations` `:235-240` (clamped at zero), `CreditLedger.applyTo` `:55-57`, `CreditLedger.refund` `:81-82`.
  - POC seats: `PocService.add/remove/setPrimary` (`poc/PocService.java:117-247`), which audit `POC_ASSIGNED`, `POC_REMOVED` and `POC_PRIMARY_CHANGED` under `"CUSTOMER"` and notify the assignee.

**Derived fields:**
- `outstanding` and `overdueAmount` are computed per page by `invoiceRepository.sumOutstandingByCustomer` and `sumOverdueByCustomer(ids, InvoiceDates.today())` (`CustomerService.java:158-166`, `InvoiceRepository.java:34-53`).
- `paymentTermLabel` falls back to `invoiceProperties.defaultTerm()` (`:188-190`).
- Tiles use subqueries (`:86-127`).

### 2.2 Invoice (with InvoiceItem, PaymentTerm and InvoiceNumbers)

**Fields** (`invoice/Invoice.java`):

| Field | Line | Type and constraints |
|---|---|---|
| invoiceNumber | 28-29 | unique, not null, 40 |
| customer | 31-33 | LAZY, optional=false |
| invoiceDate | 35-36 | Instant, not null |
| dueDate | 38-39 | LocalDate; NOT NULL is enforced by `InvoiceSchemaUpgrade.enforceDueDateNotNull`, not by the mapping |
| paymentTerm | 41-43 | |
| items | 45-47 | OneToMany, cascade ALL, orphanRemoval |
| total | 49-51 | 14,2 |
| paidAmount | 53-55 | 14,2 |
| status | 57-60 | `InvoiceStatus` UNPAID / PARTIALLY_PAID / FULLY_PAID / CANCELLED (`InvoiceStatus.java`) |
| notes | 62-63 | 500 |
| salesPoc | 65-67 | LAZY, nullable column, but **required on create** |
| version | 69-71 | |
| createdAt | 73-74 | |

- `@PrePersist` (`:76-88`) fills in `invoiceDate`, and `dueDate`/`paymentTerm` when absent.
- `InvoiceItem` (`InvoiceItem.java:16-38`) has invoice, product, `int quantity`, `unitPrice`, `lineTotal`. It has no version and no timestamps.
- `PaymentTerm` (`PaymentTerm.java:9-53`) defines days, label and `due(LocalDate)`. `SETTABLE` excludes `CUSTOM`; `SYSTEM_DEFAULT = NET_30`.
- `InvoiceProperties` (`app.invoice.*`, `InvoiceProperties.java:11-45`) holds `defaultTerm()` and `dueDateHorizonDays`.

**Derived fields:**
- `getBalance() = total - paidAmount` (`:90-92`). Also the `balance` column in `TableSchemas.java:52-55`.
- `isOverdue(today)` and `daysOverdue(today)` are **computed from the clock and never stored**, D3 (`:94-101`). They are used by `InvoiceDtos.from` through `InvoiceDates.today()` (`InvoiceDtos.java:86,117`), by `TableSchemas.overduePredicate` (`TableSchemas.java:80-85`) and by tiles (`InvoiceService.java:321,340-342`).
- `status` is stored and recomputed by the public static `InvoiceService.recomputeStatus(inv)` (`:252-258`). **Any code that changes total or paidAmount must call it.** CANCELLED is sticky, and an invoice with total 0 comes out FULLY_PAID (DASH-05, `:362-364`).
- There are no ageing buckets in this area.

**Endpoints** (`InvoiceController.java`):

| Endpoint | Privilege | Lines |
|---|---|---|
| list | INVOICE_VIEW | 46-56 |
| summary | INVOICE_VIEW | 58-65 |
| get | INVOICE_VIEW | 67-71 |
| `/due-date-preview` | INVOICE_MANAGE | 73-79 |
| POST | INVOICE_MANAGE | 81-85 |
| PATCH | INVOICE_MANAGE | 87-92 |
| `/{id}/cancel` | INVOICE_MANAGE | 94-98 |
| bulk `CANCEL`, `REASSIGN_SALES_POC` | INVOICE_MANAGE | 100-126 |
| export | EXPORT_DATA and INVOICE_VIEW | 128-157 |

**Write methods** (`invoice/InvoiceService.java`):
- `create` at `:59-99`, one transaction. It has **no scope or region check on the customer**.
  1. `customerRepository.findById` (`:61`).
  2. `pocService.requireAssignable(req.salesPocUserId(), SALES)`. A null id gives 400 "Sales POC is required" (`:64`).
  3. Resolves the due date (`:67`, rules at `:159-196`).
  4. Builds the invoice with `invoiceNumbers.next()` (`:69-77`).
  5. `buildLines` validates the lines: active product, non-negative, cents (`:225-246`).
  6. `setTotal` (`:81`).
  7. **INSERT** `save` (`:82`).
  8. `creditLedger.applyTo(saved)` locks the customer, then the invoice, and consumes credit (`:83`).
  9. `recomputeStatus` and a second `save` (`:84-85`).
  10. Audits `INVOICE_CREATED` (before `null`, after `InvoiceDto`, reason "Customer credit applied: x") at `:87-89`, then one `PAYMENT_APPLIED` per credit move (`:90-93`).
  11. `notifyAssignee` (`:95`) and `promiseService.reevaluateForCustomer` (`:97`).
- `update` at `:101-153`:
  1. `findByIdForUpdate` (`:106`), then `requireInBook` (`:108`).
  2. Refuses CANCELLED (`:109-111`).
  3. **Explicit version check**: `req.version() != null && !equals` throws `OptimisticLockingFailureException`, which returns 409 (UI-09, `:115-118`).
  4. Takes `before` (`:119`).
  5. Applies notes (`:121`), dueDate/paymentTerm (`:122-131`), and salesPoc, which needs `canAssignPoc` and notifies (`:132-141`).
  6. `save` (`:142`).
  7. Audits `INVOICE_UPDATED` (`:143-145`), plus `INVOICE_DUE_DATE_CHANGED` with `DueDateSnapshot` (`:146-151`).
  - Items, total, customer and invoiceDate **cannot** be changed here.
- `reassignSalesPoc` at `:220-223` delegates to `update`. It is used by bulk.
- `cancel` at `:352-375`: `findByIdForUpdate`, `requireInBook`, refuses when already cancelled or when `paidAmount > 0` (`:359-367`), sets CANCELLED, `save`, audits `INVOICE_CANCELLED`, then `reevaluateForCustomer`.
- `cancelWithRefund` at `:377-391` is **only called from `DisputeService.applyInvoiceChange("cancel")`** (`DisputeService.java:242`). It uses `getInternal` with **no lock and no scope check**. `creditLedger.refund` moves `paidAmount` to customer credit, then the method zeroes `paidAmount`, sets CANCELLED and calls `save` and `reevaluate`. **It writes no audit row of its own.**
- `replaceItems` at `:393-418` is **only called from dispute `replace_items`** (`DisputeService.java:243-255`). It rebuilds the lines, sets a new total, refunds any overpayment to credit, recomputes status, saves and reevaluates. **No lock, no scope check, no audit of its own.**
- `DisputeService.applyInvoiceChange("update_notes")` writes `invoiceRepository.save` directly (`DisputeService.java:256-260`).
- Indirect writes to Invoice:
  - `PaymentService.applyTo` changes `paidAmount` and status with a `save` per invoice (`:184-196`).
  - `PaymentService.reverseAllocations` (`:225-233`).
  - `CreditLedger.applyTo` (`:50-51,55`). **`:55` adds any credit "remaining" to `paidAmount` with no allocation row**, which covers drift when `creditBalance` is greater than the sum of payment credit.
  - `InvoiceSchemaUpgrade.backfill` writes raw JDBC (`:111-128`).

### 2.3 Payment (with PaymentAllocation and CreditLedger)

**Fields** (`payment/Payment.java`):

| Field | Line | Type and constraints |
|---|---|---|
| customer | 26-28 | LAZY, optional=false |
| amount | 30-31 | not null 14,2 |
| creditApplied | 33-35 | **the name is misleading**: it is the part of this payment *not* allocated, i.e. what currently sits as customer credit. `CreditLedger.applyTo` consumes it (`CreditLedger.java:43-54`). |
| method | 37-38 | 40 |
| notes | 40-41 | 300 |
| paidAt | 43-44 | not null; `@PrePersist` sets it to now (`:59-64`); **not settable via the API** |
| collectionPoc | 46-48 | nullable column, **required on record** |
| status | 50-53 | ACTIVE / VOIDED (`PaymentStatus.java`) |
| allocations | 55-57 | cascade ALL, orphanRemoval |

- **There is no `@Version`, no `createdAt` and no `updatedAt`.**
- `PaymentAllocation` (`PaymentAllocation.java:16-32`) has payment, invoice and amount.

**Endpoints** (`PaymentController.java`):

| Endpoint | Privilege | Lines |
|---|---|---|
| list | PAYMENT_VIEW | 51-61 |
| summary | PAYMENT_VIEW | 63-70 |
| get | PAYMENT_VIEW | 72-76 |
| POST | PAYMENT_MANAGE | 78-82 |
| PATCH | PAYMENT_MANAGE | 84-89 |
| `/credits/{customerId}` | PAYMENT_VIEW, scoped to forCustomers | 93-108 |
| bulk `REASSIGN_COLLECTION_POC` | PAYMENT_MANAGE | 110-128 |
| export | EXPORT_DATA and PAYMENT_VIEW | 130-155 |

The bulk endpoint here does **not** wrap the operation in `eligibility`, so a BadRequest counts as *failed*.

**Write methods** (`payment/PaymentService.java`):
- `record` at `:54-109`, one transaction, **with no scope check on the customer**:
  1. `lockCustomer` (`:60`).
  2. `requireAssignable(collectionPocUserId, COLLECTION)` (`:62`).
  3. `promiseService.invoicesToPayFirst` (`:65`).
  4. Target invoices are either the chosen ones, locked, checked to exist and to belong to this customer (`:68-84`), or all of the customer's invoices, locked (`:86`). They are sorted promise-first, then oldest (`:88`).
  5. Builds the payment (`:90-97`).
  6. `applyTo` (`:99`, body at `:171-203`) allocates oldest first, saves each invoice, puts the rest into `creditApplied` and the customer's `creditBalance`.
  7. **INSERT** `save` (`:100`).
  8. Audits `PAYMENT_RECORDED` (after `PaymentDto`, reason = notes, `:101-102`), then `auditMovements(..., "PAYMENT_APPLIED")`, which writes one INVOICE-entity row per allocation (`:103,146-157`).
  9. `notifyAssignee` (`:105`) and `reevaluateForCustomer` (`:107`).
- `update` at `:111-133`: `findById` (unlocked), `requireInBook`, notes, collectionPoc (needs `canAssignPoc` and notifies), `save`, audits `PAYMENT_UPDATED`. Amount **cannot** be changed here.
- `reassignCollectionPoc` at `:135-138` delegates to `update`.
- `voidPayment` at `:205-218` is **only called from dispute `void`** (`DisputeService.java:267`). It reads with `findById` (**no row lock on the payment and no version**), `lockCustomer`, `reverseAllocations` (audits `PAYMENT_REVERSED` per invoice, `:220-242`), sets VOIDED, saves and reevaluates. **There is no PAYMENT-entity audit row.**
- `updateAmount` at `:244-270` is **only called from dispute `update_amount`** (`DisputeService.java:268-276`). It validates positive and cents, locks the customer, reverses, sets amount, method and notes, re-applies to **all** of the customer's invoices oldest first (it ignores the original invoice choice), saves and audits `PAYMENT_APPLIED` per invoice. **There is no PAYMENT-entity audit row.**
- `DisputeService.applyPaymentChange("update_meta")` writes `paymentRepository.save` directly (`DisputeService.java:277-285`).
- `CreditLedger` (`payment/CreditLedger.java`) is a `@Component` with no `@Transactional`; it joins the caller's transaction.
  - `applyTo(invoice)` at `:29-59` locks the customer, then the invoice. It takes from payments holding credit, oldest `paidAt` first (`:94-99`), adds allocations, lowers `creditBalance`, and returns `CreditMove`s for audit.
  - `refund(invoice, amount)` at `:61-83` reverses allocations newest first, returns the amount to payments' `creditApplied`, and raises `creditBalance`.
- Unused in main: `PaymentService.list(customerId)` at `:293-298`.

### 2.4 PaymentPromise (with PromiseSweepScheduler)

**Fields** (`promise/PaymentPromise.java`):

| Field | Line | Type and constraints |
|---|---|---|
| customer | 34-36 | |
| amount | 38-39 | |
| promisedDate | 41-42 | LocalDate, not null |
| **collectionPoc** | 44-46 | **EAGER, optional=false, so NOT NULL** |
| notes | 48-49 | 1000 |
| status | 51-54 | OPEN / KEPT / PARTIALLY_KEPT / BROKEN / CANCELLED (`PromiseStatus.java`) |
| fulfilledAmount | 56-58 | |
| statusOverridden, overrideReason, overriddenByUserId, overriddenAt | 60-71 | |
| brokenNotifiedAt | 73-74 | |
| invoices | 76-81 | M2M through `payment_promise_invoices` |
| payments | 83-88 | M2M through `payment_promise_payments` |
| createdByUserId | 90-91 | nullable |
| createdAt, updatedAt | 93-108 | |

- `getRemainingAmount()` is derived (`:110-114`, SQL version at `TableSchemas.java:223-231`).
- **There is no `@Version` and no row lock anywhere, so concurrent edits are last-writer-wins.**

**Endpoints** (`PaymentPromiseController.java`):

| Endpoint | Privilege | Lines |
|---|---|---|
| list | PROMISE_VIEW | 42-54 |
| `POST /recompute?apply=` | PROMISE_OVERRIDE | 56-64 |
| summary | PROMISE_VIEW | 66-73 |
| get | PROMISE_VIEW | 75-79 |
| POST | PROMISE_MANAGE | 81-85 |
| PUT | PROMISE_MANAGE | 87-92 |
| `/{id}/cancel` | PROMISE_MANAGE | 94-99 |
| POST and DELETE `/{id}/override` | PROMISE_OVERRIDE | 101-112 |
| bulk `CANCEL`, `REASSIGN_COLLECTION_POC` | PROMISE_MANAGE | 114-143 |
| export | EXPORT_DATA and PROMISE_VIEW | 145-170 |

**Write methods** (`promise/PaymentPromiseService.java`):
- `create` at `:71-103`, **with no scope check on the customer**:
  1. `customerRepository.findById`, then amount > 0 and date checks (`:73-80`).
  2. `resolveCollectionPoc` (`:82`, body at `:612-620`) uses the explicit id through `requireAssignable`, or the customer's `defaultAssignee`. With neither, it returns 400 "A Collection POC is required…".
  3. `resolveInvoices` (`:83`, body at `:622-641`): the invoices must belong to the same customer and must not be cancelled.
  4. **INSERT** `save` (`:96`).
  5. Audits `PROMISE_CREATED` (`PromiseAuditSnapshot`, `:98-99`).
  6. `evaluate` (`:101`), then a second `save` (`:102`).
  - **It does not notify the POC on create.**
- `update` at `:105-144`: full replace. A cancelled promise is refused. Changing collectionPoc needs `canAssignPoc`, `requireAssignable` and notifies. `invoiceIds == null` keeps the links. Then `evaluate`, `save`, and audits `PROMISE_UPDATED`.
- `reassignCollectionPoc` at `:146-151` delegates to `update` with the canonical constructor.
- `cancel` at `:153-174` clears payments, fulfilment and override fields, sets CANCELLED, saves, audits `PROMISE_CANCELLED` (reason) and calls `reevaluateForCustomer`.
- `override` at `:176-202` and `clearOverride` at `:204-221` audit `PROMISE_STATUS_OVERRIDDEN` and `PROMISE_OVERRIDE_CLEARED`. `override` with BROKEN calls `notifyBroken`.
- The evaluation engine (`:225-420`):
  - `evaluate` → `evaluateCustomer` (`:231-244`) → `shareOut` gives each payment's money to promises once, in date order (`:313-353`).
  - `applyShares` (`:246-269`) changes `fulfilledAmount`, the payment links and **status**. It audits **only status changes**, as `PROMISE_STATUS_CHANGED` with before and after as **plain status-name strings** and userId `null` (`:265-266`). A fulfilment-only change is **not audited**.
  - A BROKEN status calls `notifyBroken`, which notifies the single `collectionPoc` once, deduplicated through `brokenNotifiedAt` (`:643-657`).
  - Uses `InvoiceDates.today()` at `:272`.
- `reevaluateForCustomer` at `:447-451` saves the changed promises. It is called from `InvoiceService.create/cancel/cancelWithRefund/replaceItems` (`InvoiceService.java:97,373,389,416`), `PaymentService.record/voidPayment/updateAmount` (`PaymentService.java:107,216,268`) and `PaymentPromiseService.cancel` (`:172`).
- `recomputeAll(apply)` at `:458-483` is **a dry run done by `setRollbackOnly()`** (`:481`).
- `sweepOverdue` at `:491-503` is **one transaction** covering every overdue OPEN promise that is not overridden (`PaymentPromiseRepository.findOverdueOpen`, `:28-30`).
- `PromiseSweepScheduler` (`promise/PromiseSweepScheduler.java:17-30`) runs with `fixedDelay` and `initialDelay` both `${app.promises.sweep-interval-ms:900000}`, plus **synchronously on `ApplicationReadyEvent`**. It catches and logs `RuntimeException`. It runs with **no SecurityContext**; that works because the sweep path never calls `currentUser`.
- Scheduler pool: `spring.task.scheduling.pool.size: 3`, currently one thread for the email sweeper and one for the promise sweeper (`application.yml:25-30`).
- Unused in main: `listForCustomer`, `listForInvoice`, `reevaluateForInvoice` (`:527-535,485-489`).

### 2.5 Dispute

**Fields** (`dispute/Dispute.java`):

| Field | Line | Type and constraints |
|---|---|---|
| customerId | 25-26 | **plain Long, no FK or association** |
| openedByUserId | 28-29 | not null |
| targetType | 31-33 | INVOICE / PAYMENT only (`DisputeTargetType.java`) |
| targetId | 35-36 | no FK |
| reason | 38-39 | not null, 2000 |
| proposedChangeJson | 41-43 | `@Lob` TEXT |
| status | 45-48 | PENDING / APPROVED / DENIED |
| adminNotes | 50-51 | |
| resolvedByUserId | 53-54 | |
| resolvedAt | 56-57 | |
| createdAt, updatedAt | 59-74 | |

There is no version. `mustBePending` takes a pessimistic lock (`DisputeService.java:167-174`).

**Endpoints** (`DisputeController.java`):

| Endpoint | Privilege | Lines |
|---|---|---|
| list | DISPUTE_VIEW | 35-52 |
| get | DISPUTE_VIEW | 54-58 |
| POST | DISPUTE_CREATE | 60-64 |
| `/{id}/approve` | DISPUTE_MANAGE | 66-71 |
| `/{id}/deny` | DISPUTE_MANAGE | 73-78 |
| export | EXPORT_DATA and DISPUTE_VIEW | 80-102 |

There is no update endpoint and no bulk endpoint.

**Write methods** (`dispute/DisputeService.java`):
- `open` at `:52-89`:
  1. **Only a customer login may open**: otherwise `AccessDeniedException("Only customers can open disputes")` (`:54-58`).
  2. The target must belong to that customer (`:176-193`).
  3. **Only one PENDING dispute per (customer, target)**; a second gives 400 (`:61-65`).
  4. `save` (`:76`).
  5. Audits `DISPUTE_OPENED` with disputeId set to its own id (`:77-78`).
  6. `notifyAdmins` (role name ADMIN) (`:82-86`).
- `approve` at `:91-117` is **an existing "held change, applied by a second person" flow**:
  1. The change is `appliedChangeJson` if given, otherwise `proposedChangeJson` (`:94-96`).
  2. Takes a before snapshot of the target (`:98`).
  3. `applyChange`, which is a JSON `action` switch (`:219-288`):
     - Invoice: `cancel` → `cancelWithRefund`, `replace_items` → `replaceItems`, `update_notes` → direct save.
     - Payment: `void` → `voidPayment`, `update_amount` → `updateAmount`, `update_meta` → direct save.
  4. Takes an after snapshot, sets APPROVED, `resolvedAt` and `resolvedBy` (`:100-106`).
  5. **Audits under the target's entity type**, e.g. `"INVOICE"`/`"PAYMENT"` with action `DISPUTE_APPROVED` and `disputeId` (`:108-113`). **No DISPUTE-entity row is written.**
  6. Notifies the opener (`:115`, body at `:333-340`).
- `deny` at `:119-133` audits `DISPUTE_DENIED` under DISPUTE with an after snapshot only.
- `toDto` at `:154-165` runs 2 to 3 repository lookups per row (N+1).
- Unused in main: `list()` at `:135-141`.

---

## 3. Write-site inventory for A1 and A5 (where to put the outbox write)

Rule for all rows: write the outbox or event row **inside the same transaction, after the method's last `auditService.record`**, using the saved id. Each method already has a single happy-path exit where audits are written. None of these methods has a `try/catch` that would swallow a rollback.

| # | Entity and event | Method | Last mutation or audit | Suggested insert point |
|---|---|---|---|---|
| C1 | Customer CREATED | `CustomerService.create` | audit `:245-246` | before `return c` `:247` |
| C2 | Customer UPDATED | `CustomerService.update` | audits `:288-295` | before `return saved` `:296` |
| C3 | Customer deleted (not an A1 event) | `CustomerService.delete` | audit `:318-319` | end `:320` |
| C4 | Customer UPDATED (credit) | `PaymentService.applyTo` `:198-201`, `reverseAllocations` `:235-240`, `CreditLedger.applyTo` `:56-57`, `refund` `:81-82` | no audit | emit from the callers (P1, P3, P4, I1, I4, I5) when credit moved |
| C5 | Customer UPDATED (POC book)? | `PocService.add/remove/setPrimary` | audits `PocService.java:145-159,207-221,239-245` | after those audits (decide whether seats count as "customer updated") |
| I1 | Invoice CREATED (+ Payment UPDATED for credit moves, + Customer credit) | `InvoiceService.create` | audits `:87-93`, then `:95-97` | after `:97`, before `return saved` `:98`. Emit **one** CREATED even though the method saves twice (`:82,85`). |
| I2 | Invoice UPDATED | `InvoiceService.update` (also `reassignSalesPoc`, bulk) | audits `:143-151` | before `return saved` `:152` |
| I3 | Invoice UPDATED (status CANCELLED) | `InvoiceService.cancel` (also bulk) | audit `:371-372` | after `:373` |
| I4 | Invoice UPDATED | `InvoiceService.cancelWithRefund` | save `:388` | after `:389` |
| I5 | Invoice UPDATED | `InvoiceService.replaceItems` | save `:415` | after `:416` |
| I6 | Invoice UPDATED | `DisputeService.applyInvoiceChange` "update_notes" | save `:259` | after `:259` |
| I7 | Invoice UPDATED (paid, status) | `PaymentService.applyTo` `:184-196` | audited via `auditMovements` | emit from P1 and P4 using the `applied` Movement list (`:99,265`) |
| I8 | Invoice UPDATED (reversed) | `PaymentService.reverseAllocations` `:225-233` | `PAYMENT_REVERSED` `:232` | emit from P3 and P4 |
| P1 | Payment CREATED (+ Invoice UPDATED per movement, + Customer credit) | `PaymentService.record` | audits `:101-103` | after `:107`, before `return` `:108` |
| P2 | Payment UPDATED | `PaymentService.update` (also bulk) | audit `:130-131` | before `return` `:132` |
| P3 | Payment UPDATED (VOIDED) | `PaymentService.voidPayment` | save `:215` | after `:216` |
| P4 | Payment UPDATED (amount) | `PaymentService.updateAmount` | audits `:267` | after `:268` |
| P5 | Payment UPDATED | `DisputeService.applyPaymentChange` "update_meta" | save `:284` | after `:284` |
| P6 | Payment UPDATED (creditApplied, allocations) | `CreditLedger.applyTo` `:45-49`, `refund` `:74-79` | — | from I1, I4, I5 |
| — | Promise (not an A1 trigger) | create `:96-102`, update `:140-142`, cancel `:168-172`, override `:196-200`, clearOverride `:217-219`, `applyShares` `:263-267`, `sweepOverdue` `:497` | | only if promise triggers are added later |
| — | Dispute (not an A1 trigger) | open `:76-86`, approve `:106-115`, deny `:126-131` | | |

Recommendations and constraints for A5:
- **Do not publish to RabbitMQ, or run rule evaluation, inside these transactions.**
  - Transactions roll back after work has been done in several places: `recomputeAll(apply=false)` uses `setRollbackOnly` (`PaymentPromiseService.java:481`), `BulkExecutor` treats a commit-time `ConcurrencyFailureException` as skipped (`BulkExecutor.java:72-78`), and FK violations surface at flush during delete. A broker message sent before commit leaks in all of these cases.
  - A broker call in the transaction also slows every save, including the invoice create that already serializes on the sequence row (`InvoiceNumbers.java:36`).
  - The codebase already follows "DB row now, deliver outside the transaction": `EmailDispatcher.dispatch` refuses to run inside a transaction (`email/EmailDispatcher.java:75-78`), and the sweeper recovers stragglers (`EmailSweepScheduler.java:17-30`).
- An outbox insert that fails inside the transaction marks it rollback-only, and **you cannot catch your way out of that**. To make sure a rule "can never break the save", keep the outbox table free of FKs and unique constraints, use no `nullable=false` columns you might not have a value for, and do not read the rules table inside the transaction.
- Use `Propagation.MANDATORY` for the outbox writer, in the same style as `InvoiceNumbers.next()` (`:32`).
- Deduplicate per transaction. One transaction can touch the same invoice several times (I1 saves at `:82` and `:85`; P1 plus I7 touch several invoices). A transaction-bound set via `TransactionSynchronizationManager` keyed by (type, id, event) avoids duplicate rows.
- An alternative is a Hibernate or JPA `PostInsert`/`PostUpdate` listener that catches every path above, including dispute approvals and direct saves. The catch is that updates are flushed at commit, which is *after* Spring's `beforeCommit` synchronizations. A listener-based collector would therefore have to flush explicitly or hook Hibernate's action queue. The explicit-call approach matches the codebase's style: audit is written explicitly everywhere.
- The consumer runs with **no SecurityContext and no OEMIV**. OEMIV is registered for web requests only (`config/OpenEntityManagerInViewConfig.java:12-24`).
  - Lazy associations used in DTO and placeholder building (`Invoice.customer`, `items→product`, `salesPoc`; `Payment.customer`, `allocations`) must be read inside a `@Transactional(readOnly=true)` block.
  - Every service method that calls `currentUser.require()` throws `BadCredentialsException("Not authenticated")` when called from the consumer (`auth/CurrentUser.java:12-18`). The affected calls:
    - `CustomerService.java:246,289,294,319`
    - `InvoiceService.java:134,145,150,372`
    - `PaymentService.java:121,131`
    - `PaymentPromiseService.java:94,99,124,142,171,193,199,219`
    - `DisputeService.java:54,104,124`
    - `PocService.notifyAssignee` `:269`, reached from invoice/payment create
    - all of `ScopeResolver` (`:34,40,46,69,90,112`), reached through `requireInBook`, `get` and `toDto`
  - Automation actions therefore need either a system principal (a seeded user holding `SCOPE_OVERRIDE`) or dedicated "internal" service entry points that take an actor id.
- The scheduling pool is 3 (`application.yml:25-30`). A daily/weekly rule scheduler plus an outbox poller need that raised, or they will contend with the two sweepers. Copy the `PromiseSweepScheduler` pattern: `@Scheduled(fixedDelayString="${…}")`, try/catch-log, and in `application-test.yml` a long interval so tests drive it directly (`application-test.yml:22-24`).
- "Invoice became overdue" is **not a write event**. Overdue is derived from the clock (D3, `Invoice.java:94-101`), so it can only be caught by the A1 daily/weekly schedule (a query over `TableSchemas.invoiceOverdue`, `TableSchemas.java:72-78`).

---

## 4. PRD extension points in this area

### 4.1 A3 actions: constraints on creating a Promise or Dispute from automation
- **Promise** via `PaymentPromiseService.create` (`:71-103`) requires:
  - `amount > 0` with cents
  - a `promisedDate`
  - a Collection POC, either explicit (`requireAssignable`: active, internal, holds `POC_ASSIGNABLE_COLLECTION`, `PocService.java:55-72`) or the customer default (`defaultAssignee` = first active seat holder, `PocService.java:96-104`). Without one the call returns 400.
  - `invoiceIds` of the same customer that are not cancelled
  - `createdByUserId` can be null (column is nullable, `PaymentPromise.java:90-91`), but `create` calls `currentUser.require()` at `:94`.
  - **There is no idempotency guard.** A rule that fires repeatedly creates duplicate promises; the outbox event id should be carried as a dedupe key.
- **Dispute** via `DisputeService.open` **cannot be reused**:
  - It refuses non-customer callers (`:56-58`).
  - The opener must be the customer login (`openedByUserId` is NOT NULL).
  - The target may only be INVOICE or PAYMENT, not CUSTOMER.
  - The one-PENDING-per-target guard returns 400 (`:61-65`); the automation should record that as "skipped (already open)" rather than as a failure.
  - A new internal entry point that sets `customerId` from the target is needed. `notifyAdmins` (`:82-86`) still fires.
- **Invoice and Payment creation are not A3 actions.** They would need `salesPoc`/`collectionPoc` (`InvoiceService.java:64`, `PaymentService.java:62`).

### 4.2 A4 placeholders: the data available at each level
- `Customer`: `Name`, `Email`, `Phone`, `Address`, `CreditBalance`, `PaymentTerm` (with label), plus the derived `Outstanding` and `Overdue`. The derived values need the repository sums (`CustomerService.java:158-166`).
- `Invoice`: `InvoiceNumber`, `InvoiceDate` (Instant, UTC), `DueDate`, `PaymentTerm` label, `Total`, `PaidAmount`, `Balance` (`getBalance`), `Status`, `DaysOverdue`/`Overdue` (these need "today", or as-of for B3), `Notes`, `SalesPoc`.
- `Payment`: `Amount`, `PaidAt`, `Method`, `Status`, `CreditApplied`, `CollectionPoc`.
- Formatting to reuse: `Money.format` for money. `EmailText.date(...)` and `EmailText.humanize(enum)` are package-private in `email/` (`EmailText.java:52-63`).
- "A role held by several people resolves to the primary":
  - At customer level, `PocService.activeHoldersByType` returns seats ordered **primary first, then by id**, filtered to *active* users (`PocService.java:106-115`, repository method `findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc`).
  - `EmailTargets.Target.sender(role)` takes the first holder (`email/EmailTargets.java:157-160`). If the primary is **inactive**, this gives the next active seat, not the primary.
  - `PocService.primaryFor` returns the primary regardless of whether it is active (`:88-94`).
  - Primary uniqueness is enforced **only on Postgres** by a partial unique index (`PocSchemaUpgrade.java:57-69`). H2 relies on code (CP-02).

### 4.3 How Promise and Dispute assignees work today (context for A3 and A6)
- **Promise: exactly one person.**
  - `collectionPoc` is `@ManyToOne(EAGER, optional=false)` (`PaymentPromise.java:44-46`).
  - It defaults on create to the customer's first active Collection POC seat (`PaymentPromiseService.java:612-620`).
  - Changing it needs `POC_ASSIGN` (`:120-132`); it is also changed through bulk `REASSIGN_COLLECTION_POC`.
  - It drives list scope: the book is `collectionPoc.id = me` (`ScopeResolver.java:61-64`).
  - The broken-promise notification goes to that one user (`:643-657`).
  - In the email model, `PROMISE` offers CUSTOMER-level SUCCESS and COLLECTION POC and RECORD-level `COLLECTION_POC` = that single field (`email/EmailTargets.java:89-111,272-276`).
- **Dispute: nobody.**
  - There is no assignee field.
  - Staff visibility is not scoped at all (`ScopeResolver.java:89-97`).
  - Opening notifies every ADMIN-role user; resolution notifies the opener (`DisputeService.java:82-86,333-340`).
  - Email RECORD roles for a dispute are borrowed from its target: the invoice's `salesPoc` or the payment's `collectionPoc` (`EmailTargets.java:296-300`).
- **Invoice** `salesPoc` and **Payment** `collectionPoc` are single nullable FKs (`Invoice.java:65-67`, `Payment.java:46-48`). They are required at create.
- The role-at-a-level model to reuse for multi-assignee Tasks is `RoleRef(EmailRole role, RoleLevel level)` with the token `ROLE:<LEVEL>:<ROLE>` (`email/RoleRef.java:5-42`). `EmailRole` maps onto `PocType` (`EmailRole.java:8-21`). Levels are `CUSTOMER` and `RECORD` (`RoleLevel.java`). Validation and default level are in `EmailAddressing.offeredRole` (`:122-132`) and `EmailTargets.defaultLevel` (`:198-200`). Holder resolution is in `EmailTargets.holders` (`:308-326`).
- Open design decision for Tasks: store the resolved user ids at creation time (a join table), or keep the role tokens and resolve them when read. Existing records store the resolved person.

### 4.4 A6 Tasks: what to copy
- Put the target in `targetType` plus `targetId` with a composite index, as Dispute does (`Dispute.java:11,31-36`), with `customerId` denormalized so scope and region can be applied (`ScopeResolver.forDisputes` uses `root.get("customerId")`).
- Assignees go in a join table, patterned on `payment_promise_invoices` (`PaymentPromise.java:76-81`).
- Include `createdAt`/`updatedAt` via `@PrePersist`/`@PreUpdate`, a `ENTITY = "TASK"` audit constant, a `TableSchemas.TASKS` entry (and register it in `buildIndex`, `TableSchemas.java:333-342`), a `Privileges` pair added to `ALL`, and a line in `IntegrationTestBase.resetTransactionalData`.

### 4.5 B1 Regions: columns, inheritance and scope hooks
- **Where to add the column:**
  - `Customer` is the root (`Customer.java`).
  - The children that carry `customer` associations are `Invoice.customer` (`:31-33`), `Payment.customer` (`:26-28`) and `PaymentPromise.customer` (`:34-36`). `Dispute.customerId` is a plain id (`:25-26`).
  - Grandchildren: `InvoiceItem` (via invoice), `PaymentAllocation` (via payment and invoice), `CustomerPoc` (via customer).
  - Inheritance points at create: `InvoiceService.create` builder `:69-77` (customer at `:61`), `PaymentService.record` builder `:90-97`, `PaymentPromiseService.create` builder `:85-95`, `DisputeService.open` builder `:67-75`, `CustomerService.create` builder `:226-232` (the region must come from `CustomerCreateRequest`, `CustomerDtos.java:28-36`).
  - Cross-customer links are already refused, so a single region per customer stays consistent across children:
    - payment invoices must belong to the payment's customer (`PaymentService.java:79-84`)
    - promise invoices must belong to the promise's customer (`PaymentPromiseService.java:630-633`)
    - a dispute target must belong to the dispute's customer (`DisputeService.java:176-193`)
    - credit is per customer (`CreditLedger`)
- **Decision needed:** can a customer's region change? If yes, either the children follow (derive the region through `customer`; as-of reads then need a customer region history) or they are pinned (a denormalized `region_id` on each child, which means lists can disagree with the customer). B3's "which region a record belonged to then" requires region history either way.
- **Migration gotcha:** with `halt_on_error: true` (`application.yml:24`), a `@ManyToOne(optional=false)` or `@Column(nullable=false)` region on the existing populated tables **stops startup on Postgres**. Add the column as nullable, backfill it in a `RegionSchemaUpgrade` (copy `InvoiceSchemaUpgrade.backfill` and `enforceDueDateNotNull`, `:62-88,146-164`), then enforce NOT NULL. H2 tests (`create-drop`) will not catch this.
- **Scope hooks.** Adding region predicates in `ScopeResolver.build` (`:111-129`), `forCustomers` (`:66-87`) and `forDisputes` (`:89-97`) automatically reaches:
  - `page`, `idsMatching`, `allMatching`, `tiles`, `get`, `requireInBook` in all five services (e.g. `InvoiceService.java:281-350`)
  - bulk id resolution (`*Controller.resolveIds`)
  - export
  - `PaymentController.credit` (`:103-106`)
  - `EmailTargets.bulkIds` and `loadScoped` (`email/EmailTargets.java:240-253,342-371`)
- **Region checks must be added separately** in these places:
  1. **Create paths have no scope check today**: `InvoiceService.create` `:61`, `PaymentService.record` `:60`, `PaymentPromiseService.create` `:73`, `InvoiceService.previewDueDate` (which does check, `:210`).
  2. Read and manage are the same check today. `requireInBook` guards writes (`CustomerService.java:254,301`; `InvoiceService.java:108,358`; `PaymentService.java:115`; `PaymentPromiseService.get` used by update/cancel/override). "Read in region A, manage in region B" needs a separate manage-scope check. The `@PreAuthorize` authorities are global (`CurrentUser.has`, `:35-39`).
  3. Dispute-approval internals skip scope entirely: `cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount`, direct saves.
  4. `DisputeService.get` has no staff scope check (`:144-152`).
  5. Repository aggregates keyed by customer ids (`InvoiceRepository.java:34-53`) are safe only because their input ids are already scoped.
  6. `TableSchemas` needs a `regionId` filter column on CUSTOMERS, INVOICES, PAYMENTS, PROMISES and DISPUTES (`TableSchemas.java:40-302`) so the UI can ask "which regions".
- "POC book is per region": the book is defined in `ScopeResolver.forCustomers` (seat OR invoice Sales POC, `:83-86`) and `build` (record POC = me). `CustomerPoc` has no region (`poc/CustomerPoc.java`). Because a customer is in exactly one region, a seat is implicitly per region, but assignability (`POC_ASSIGNABLE_*`) is global.

### 4.6 B2 maker-checker: what to measure and where to intercept
**Monetary and material changes that exist today:**

| Change | Method | Magnitude available at | Intercept after |
|---|---|---|---|
| Invoice created (total; credit auto-applied) | `InvoiceService.create` | total at `:81` (after `buildLines`), credit at `:83-86` | `:81`, before INSERT `:82`. Note `invoiceNumbers.next()` at `:75` is consumed (a sequence gap) unless you move it. |
| Invoice due date moved or term changed (non-monetary; days extended × balance?) | `InvoiceService.update` | `due` vs `inv.getDueDate()` `:123-131` | the version check `:118`, before `before` `:119` |
| Invoice Sales POC changed | `InvoiceService.update` `:132-141` | — | same |
| Invoice cancelled (writes off `total`, since `paidAmount` must be 0) | `InvoiceService.cancel` | `inv.getTotal()` | the checks `:367` |
| Invoice cancelled with refund (total plus refund to credit) | `cancelWithRefund` via dispute | `inv.getPaidAmount()` `:383` | `:382` |
| Invoice lines replaced (Δtotal; refund to credit) | `replaceItems` via dispute | `totalOf(lines)` `:406` vs old total | `:403` |
| Payment recorded (amount; the credit part) | `PaymentService.record` | `req.amount()` | the targets are resolved `:88`, before `:90` |
| Payment amount changed (Δamount) | `updateAmount` via dispute | `newAmount - p.getAmount()` | `:254` |
| Payment voided (amount) | `voidPayment` via dispute | `p.getAmount()` | `:211` |
| Payment Collection POC changed | `PaymentService.update` `:119-128` | — | `:116` |
| Promise amount or date changed | `PaymentPromiseService.create`/`update` | `req.amount()` | `update` `:113`, `create` `:80` |
| Promise status overridden by hand (e.g. marked KEPT) | `override` | `promise.getAmount()` | `:187` |
| Customer payment term changed | `CustomerService.update` | `previousTerm` vs `in.paymentTerm()` `:268-273` | `:267` |

- **There is no "write-off" feature and no direct credit edit.** `creditBalance` only moves as a side effect of payments, cancels and replaces (`CreditLedger`, `PaymentService.applyTo`/`reverseAllocations`). Ask the PRD owner what "credit" and "write-off" mean.
- **A precedent already exists:** the dispute flow stores a proposed change as JSON (`Dispute.proposedChangeJson`, `:41-43`), shows it before it takes effect (`DisputeDtos.DisputeDto.proposedChangeJson`, `:30`), and applies it on approval by a different user holding DISPUTE_MANAGE through a switch into the same service methods (`DisputeService.java:219-288`). It also audits under the target with a `disputeId` link (`:108-113`). The `audit_logs.dispute_id` column (`AuditLog.java:44-45`) is the model for a `pending_change_id` link.
- **Interception gotchas:**
  1. **Do not signal "pending" by throwing.** A `RuntimeException` rolls back the `@Transactional` method, including the pending-change row you just wrote. Return normally, or write the pending row in `REQUIRES_NEW`. Controllers currently return the saved entity's DTO (`InvoiceController.java:81-92`, etc.), so a new response shape or pending marker is needed.
  2. **Bulk**: `BulkResult` only has succeeded, failed and skipped (`BulkExecutor.java:83-84`). A pending row needs a bucket, or a skipped reason.
  3. **Re-entry**: dispute approval calls `cancelWithRefund`, `replaceItems`, `voidPayment` and `updateAmount`. If B2 gating lives inside those methods, an approved dispute becomes pending again. The approval context needs a bypass flag, and so does applying an approved pending change through the same method.
  4. **Base version**: Invoice's `@Version` goes up on every payment allocation and every credit application (`PaymentService.applyTo` `:192`, `CreditLedger`). A pending invoice change pinned to a base version will conflict after any payment. Payment, Promise and Dispute have **no version at all**, so conflict detection on approval has to be designed.
  5. **"Someone else"**: nothing today checks that the approver differs from the maker. Disputes get this only structurally (customer versus staff).
  6. **System changes must not be gated**: promise status changes from `reevaluateForCustomer`/`sweepOverdue`, credit application at invoice create (`CreditLedger.applyTo`), and schema backfills.
  7. **Creates above threshold have no record to hang the pending change on.** Invoice numbering happens in `create`, as does payment allocation (whose result depends on the invoices at the time of approval). Visibility "to everyone who can see the record" then has to be through the customer (and region).
  8. Per-region thresholds need the record's region at the intercept point. That is the customer's region for all four children; Dispute needs a lookup via `customerId`.

### 4.7 B3 as-of reads: history captured today, and the gaps
- Current tables hold **only current state**. `audit_logs` is the only history (`AuditLog.java`), indexed by `(entity_type, entity_id)` (`:10`). `AuditTimelineService` reads it (`audit/AuditTimelineService.java:63-120`).
- Snapshot coverage by entity:
  - **Customer**: `CustomerAuditSnapshot(id,name,phone,email,address,creditBalance,paymentTerm)` on create, update and delete (`CustomerService.java:330-337`). **Credit balance changes from payments, credit and refunds are not audited on CUSTOMER.** POC seat history is under CUSTOMER as `PocAuditSnapshot` (`PocService.java:277`).
  - **Invoice**: the full `InvoiceDto` on create, update and cancel. `InvoicePaymentAudit(paymentId, amount, paidAmount, balance, status)` on PAYMENT_APPLIED/REVERSED (`PaymentService.java:140-141`). `DueDateSnapshot` (`InvoiceService.java:155`). **`cancelWithRefund`, `replaceItems` and `update_notes` leave only the DISPUTE_APPROVED before/after `InvoiceDto` pair.** Note that `InvoiceDto` embeds `overdue`/`daysOverdue` as computed *at audit time*.
  - **Payment**: `PaymentDto` on record and update. **Void and amount change have no PAYMENT-entity row except DISPUTE_APPROVED.**
  - **Promise**: `PromiseAuditSnapshot` (`PaymentPromiseService.java:668-672`) on user actions. System status changes are stored as **bare strings** (`:265-266`). **Fulfilment-only changes are not audited.**
  - **Dispute**: OPENED and DENIED under DISPUTE; APPROVED under the target type.
  - JSON shapes differ by action, so reconstructing a record as of a date from audit rows is fragile. A dedicated versioned history table, written at the same points as the §3 outbox, is the dependable approach. It should include `region_id` and `valid_from`/`valid_to`. The pending-change table needs `created_at` plus `decided_at` so "outstanding as of T" is `created_at ≤ T AND (decided_at IS NULL OR decided_at > T)`.
- **Clock reads that must take an as-of date:**
  - `InvoiceDates.today()` in `InvoiceDtos.java:86,117` (static factories with no date parameter)
  - `CustomerService.java:164`, `InvoiceService.java:321`, `InvoiceController.java:139` (export)
  - `TableSchemas.java:81` (overdue filter)
  - `PaymentPromiseService.java:272` (target status) and `:493` (sweep)
  - `dashboard/DashboardController.java:53`
  - All dates are UTC (`InvoiceDates.java:14-20`).
- Available creation times: `createdAt` on Customer, Invoice, Promise and Dispute; `paidAt` on Payment (no `createdAt`); `invoiceDate` on Invoice, which is back-datable through the API (`InvoiceService.java:66`).

---

## 5. Risks and gotchas, collected

1. `halt_on_error: true` combined with `ddl-auto: update`: a new NOT NULL column or `optional=false` FK on customers, invoices, payments, payment_promises or disputes breaks startup on a populated Postgres database. Tests will not show it (`application.yml:13-24`, `application-test.yml:9`).
2. New `@Version` columns on existing tables must be added to `RowVersionUpgrade.TABLES` (`common/RowVersionUpgrade.java:18`). A null version makes Spring Data treat the entity as new.
3. New tables with FKs to core rows must be added to `IntegrationTestBase.resetTransactionalData`, in FK order (`:65-89`). A required region breaks the `customer(name)` helpers (`:119-125`).
4. `currentUser.require()` is called throughout the write paths (§3). Background consumers and schedulers need a system principal or internal overloads. `PromiseSweepScheduler` works only because the sweep path avoids it.
5. OEMIV covers web requests only (`OpenEntityManagerInViewConfig.java:12-24`). Consumers must build DTOs and placeholders inside a transaction because of lazy `customer`, `items`, `salesPoc`, `collectionPoc` and `allocations`.
6. Keep the lock order customer → invoices by ascending id (PPD-01). A new writer that touches both, such as applying a pending change or moving a customer's region and cascading it, must follow that order or risk deadlocks. Invoice create also holds the global sequence row lock (`InvoiceNumbers.java:36`); keep that transaction short.
7. Payment and Promise have no optimistic version and no row lock on themselves. Customer has `@Version` but `update` takes no client version, so the only protection is the transaction window. Invoice's version goes up on every allocation.
8. Several money-moving methods write **no audit on their own entity** and **skip scope**: `cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount`, the dispute direct saves, and credit balance moves. A1, B2 and B3 hooks placed only next to existing audit calls will miss them.
9. Create paths (invoice, payment, promise) have no customer scope check today. B1 must add a manage-in-region check there.
10. Staff see all disputes (`ScopeResolver.forDisputes`, `:89-97`), and `Dispute.customerId` has no association. Region scoping for disputes needs a subquery or a denormalized column.
11. `DisputeService.open` is customer-only and allows one pending dispute per target. Automation needs a different entry point and must handle the duplicate case idempotently.
12. `notifyAdmins` selects by role **name** "ADMIN" (`NotificationService.java:33-38`). A tailored admin role with a different name receives nothing. Watch this if approval notifications are built the same way.
13. `recomputeAll(apply=false)` is a dry run done by rollback (`PaymentPromiseService.java:481`). Anything outside the database (broker publish, HTTP) done inside it will leak.
14. `sweepOverdue` updates every overdue promise in one transaction (`:491-503`). A failure rolls back the whole sweep, and outbox rows written in it would be rolled back too, which is correct.
15. The scheduler pool is 3 (`application.yml:25-30`). Raise it for the automation schedulers and poller.
16. Changing request records breaks or silently defaults the internal constructor calls (`InvoiceService.java:222`, `PaymentService.java:137`, `PaymentPromiseService.java:149-150`).
17. Customer `PUT` is a full replace: a null `paymentTerm` clears the term (`CustomerService.java:273`). Promise `PUT` overwrites `notes` with null (`PaymentPromiseService.java:118`). Pending changes stored as request payloads must be complete payloads.
18. Primary-POC uniqueness is enforced by a database index only on Postgres (`PocSchemaUpgrade.java:57-69`). The resolution code already tolerates duplicates by taking the oldest (`PocService.java:88-94`).
19. HTTP status conventions to keep: out-of-scope reads return 404, not 403 (AUTH-08); version conflicts return 409 through `OptimisticLockingFailureException`; field problems use `InvalidFieldsException` (400 with a field map); "may not reassign the POC" returns 400 (except the customers bulk endpoint, which returns 403).