# POC book and record visibility scoping: engineering map

All paths are relative to the repository root. `B/` means `backend/src/main/java/com/geneinvoice/`, `BT/` means `backend/src/test/java/com/geneinvoice/`, and `F/` means `frontend/lib/`.

---

## 0. Summary

- **Visibility has one source: `ScopeResolver`** (`B/poc/ScopeResolver.java`). It returns a `Scope(List<PredicateFactory> predicates, List<String> lockedFilters)` for each entity. Every list, count, id-set, aggregate and detail check passes `scope.predicates()` into `TableQueryExecutor`. The dashboard applies the same predicates by hand in its criteria queries.
- **The book has two levels:**
  - Customer level: `CustomerPoc` seats, SUCCESS and COLLECTION only, many per type, one primary.
  - Record level: a single user field on each record. `Invoice.salesPoc`, `Payment.collectionPoc`, `PaymentPromise.collectionPoc`.
- **There are three visibility tiers per caller:**
  - Customer login: own customer only.
  - `SCOPE_OVERRIDE`: everything, with an empty scope.
  - A POC without override: the book only.
  - Anyone else: nothing.

  Every tier is decided from **global** privileges on the user's **single** `Role`. Nothing is per region today.
- **What "book" means depends on the entity.** Invoices are scoped by `salesPoc == me`. Payments and promises are scoped by `collectionPoc == me`. Customers are scoped by (any seat of mine OR any invoice whose salesPoc is me). Disputes are **not book-scoped for staff at all**.
- **`lockedFilters` is overloaded.** It is shown as a lock chip in the UI. The dashboard also uses "non-empty" to mean book coverage and to switch how "collected" is calculated. Putting region information into `lockedFilters` would silently break the dashboard maths and the UI labels (§10, R1).
- **Background work has no `CurrentUser`.** `ScopeResolver` and most service write paths call `currentUser.require()`, which throws `BadCredentialsException` with no principal. This matters for the A5 consumer and for scheduled rules.

---

## 1. Files read in full

| File | Lines |
|---|---|
| `B/poc/PocType.java` | 36 |
| `B/poc/CustomerPoc.java` | 55 |
| `B/poc/CustomerPocRepository.java` | 36 |
| `B/poc/PocService.java` | 278 |
| `B/poc/ScopeResolver.java` | 152 |
| `B/poc/PocController.java` | 67 |
| `B/poc/PocDtos.java` | 32 |
| `B/poc/PocSchemaUpgrade.java` | 70 |
| `B/common/query/*` (TableQueryExecutor, TableSchemas, TableSchema, ColumnDef, ColumnType, FilterPredicates, FilterSpec, FilterOperator, TableQuery, PageResponse, PredicateFactory, PredicateResolver, PathResolver, ValueCoercion, Aggregates, DateRange, FilterParams, TableSchemaController) | all |
| `B/customer/*`, `B/invoice/{InvoiceService,InvoiceController,InvoiceRepository,Invoice}`, `B/payment/{PaymentService,PaymentController,PaymentRepository,Payment,PaymentAllocationRepository}`, `B/promise/{PaymentPromiseService,PaymentPromiseController,PaymentPromiseRepository,PaymentPromise,PromiseSweepScheduler}`, `B/dispute/*`, `B/dashboard/*` | all |
| `B/email/{EmailTargets,EmailAddressing,EmailRole,RoleLevel,RoleRef,RecipientSet,EmailEntityType,EmailService,EmailController,InboxController,InboxService,EmailDirectory,EmailCascade}`, `B/email/EmailViews.java` (first 140 lines) | as noted |
| `B/document/DocumentTargets.java` (all), `B/document/DocumentService.java` (access calls) | as noted |
| `B/audit/{AuditService,AuditController}`, `B/audit/AuditTimelineService.java` (lines 60–125) | as noted |
| `B/auth/{CurrentUser,AppUserDetails,AppUserDetailsService,JwtAuthFilter}`, `B/auth/dto/LoginResponse.java` | all |
| `B/user/{User,UserRepository}`, `B/privilege/Privileges.java`, `B/config/DataSeeder.java`, `B/common/{RowVersionUpgrade,BadRequestException,NotFoundException}.java`, `B/common/bulk/BulkExecutor.java`, `B/invoice/InvoiceSchemaUpgrade.java` | all |
| `F/features/poc/poc_providers.dart` | 152 |
| `F/features/poc/poc_name_cell.dart` | 30 |
| `F/features/poc/customer_poc_editor.dart` | 190 |
| `F/features/poc/poc_picker.dart` | 210 |

---

## 2. The POC book model

### 2.1 Customer-level seats: `CustomerPoc` (`B/poc/CustomerPoc.java`)

```java
@Entity
@Table(name = "customer_pocs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_poc", columnNames = {"customer_id", "user_id", "poc_type"}),
        indexes = {
                @Index(name = "idx_customer_poc_customer", columnList = "customer_id,poc_type"),
                @Index(name = "idx_customer_poc_user", columnList = "user_id")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CustomerPoc {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id") private Customer customer;
    @ManyToOne(fetch = FetchType.EAGER, optional = false) @JoinColumn(name = "user_id") private User user;
    @Enumerated(EnumType.STRING) @Column(name = "poc_type", nullable = false, length = 20) private PocType pocType;
    @Column(name = "is_primary", nullable = false) @Builder.Default private boolean primary = false;
    @Column(name = "created_by_user_id") private Long createdByUserId;
    @Column(updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { this.createdAt = Instant.now(); }
}
```

- The class declaration is at L10–23 and the fields at L25–54.
- There is no `updatedAt`, no validity dates and no soft delete. Removing a seat is a **hard delete** (`PocService.remove` L183; `CustomerService.delete` L310–312).
- Seat history exists only in audit rows (§2.5). This matters for B3.
- Only SUCCESS and COLLECTION can be seated. SALES is refused in two places:
  - `PocService.add` L119–121: `"Sales POC is assigned per invoice, not per customer"`.
  - `CustomerController.bulk` L146–148.

### 2.2 Record-level POC fields (one user per record)

| Entity | Field | Mapping | Required? |
|---|---|---|---|
| `Invoice` | `salesPoc` | `B/invoice/Invoice.java` L65–67: `@ManyToOne(fetch=LAZY) @JoinColumn(name="sales_poc_user_id")`, nullable | Required on create: `InvoiceService.create` L64 calls `pocService.requireAssignable(req.salesPocUserId(), SALES)`, which throws on null (`PocService` L56–58) |
| `Payment` | `collectionPoc` | `B/payment/Payment.java` L46–48: LAZY, nullable | Required on record: `PaymentService.record` L62 |
| `PaymentPromise` | `collectionPoc` | `B/promise/PaymentPromise.java` L44–46: `@ManyToOne(fetch=EAGER, optional=false)` | If omitted, defaults to the customer's first active COLLECTION seat holder (`PaymentPromiseService.resolveCollectionPoc` L612–620 → `pocService.defaultAssignee`) |
| `Dispute` | none | `B/dispute/Dispute.java` stores `customerId` as a plain `Long` (L25–26), with no association | Its "record roles" are borrowed from its target (`EmailTargets.disputeOwnFields` L296–300) |

The customer-level vs record-level split is written down in `B/email/EmailTargets.java`:
- `CUSTOMER_ROLES`, L89–97: SUCCESS and COLLECTION for every customer-bearing type.
- `RECORD_ROLES`, L103–111:
  - INVOICE → SALES_POC.
  - PAYMENT and PROMISE → COLLECTION_POC.
  - DISPUTE → both.

### 2.3 `PocType` (`B/poc/PocType.java`)

```java
SALES(Privileges.POC_ASSIGNABLE_SALES, "Sales POC"),
SUCCESS(Privileges.POC_ASSIGNABLE_SUCCESS, "Customer Success POC"),
COLLECTION(Privileges.POC_ASSIGNABLE_COLLECTION, "Collection POC");
public String assignabilityPrivilege(); public String label();
public static PocType parse(String raw)   // throws BadRequestException("pocType must be one of [...]")
```

- A type is bound to exactly one global privilege, the "assignability" privilege.
- The same privilege also decides whether a caller's view is **book-limited** for that entity (`ScopeResolver.build` L123, `isAnyPoc` L99–104).
- `EmailRole` maps 1:1 onto `PocType` (`B/email/EmailRole.java` L9–11).

### 2.4 Primary semantics and invariants

**Invariant:** at most one `primary` seat per (customer, pocType).

**Writers serialise on the customer row:**
- `lockCustomer` (`PocService` L262–265) calls `customerRepository.findByIdForUpdate` (`B/customer/CustomerRepository.java` L13–15, `PESSIMISTIC_WRITE`).
- They then take `findByCustomerIdAndPocTypeForUpdate` (`CustomerPocRepository` L21–25, `PESSIMISTIC_WRITE`).
- The customer lock comes first in `add` L125, `remove` L173 and `setPrimary` L229.

**Rules:**
- **The first seat becomes primary automatically.** `add` L133–134: `boolean first = ...ForUpdate(...).isEmpty(); boolean primary = makePrimary || first;`
- **Making a seat primary demotes the previous one** via `clearPrimary` (L249–260), which demotes *all* current primaries and returns the first.
- **Removing the primary promotes the oldest remaining seat** (lowest id). All stray primaries are cleared first (L186–205).

**Database enforcement is Postgres-only.** A partial unique index `uk_customer_poc_primary on customer_pocs (customer_id, poc_type) where is_primary` is created only on Postgres (`PocSchemaUpgrade` L57–69). **H2 tests have no database guard.**
- The same upgrade first demotes duplicate primaries, keeping the oldest (L39–55).

**Readers tolerate duplicates.** `primaryFor` (L88–94) returns the oldest primary and "a reader answers, it does not fail (CP-02)".

**Ordering used by every "who holds it" read:** `findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc` (`CustomerPocRepository` L15). The primary comes first, then the others oldest first.

**Inactive users:**
- They keep their seats.
- `activeHoldersByType` (L106–115) filters `seat.getUser().isActive()`.
- `defaultAssignee` (L96–99) returns the **first active holder**. That is the primary if active, otherwise the oldest active non-primary.
- `primaryFor` does **not** filter inactive users.
- **`primaryFor`, `activeHolders` and `callerIfAssignable` have no production callers.** The only reader used in production is `activeHoldersByType` (via `EmailTargets.holders` L319, `defaultAssignee`).

### 2.5 `PocService` public API (`B/poc/PocService.java`)

| Method | Lines | Notes |
|---|---|---|
| `List<User> assignable(PocType, String query, int limit)` | 42–52 | `UserRepository.findAssignable(priv, likePattern, PageRequest)` (`B/user/UserRepository.java` L37–52): active, internal (`customerId is null`), role holds the privilege. The limit is clamped to 1..100. **Global, with no region.** |
| `User requireAssignable(Long userId, PocType)` | 54–72 | `BadRequestException` if null, inactive, a customer login, or lacking the privilege (`userRepository.hasPrivilege`). `NotFoundException` if the user is missing. |
| `Optional<User> callerIfAssignable(PocType)` | 74–81 | Unused. |
| `List<CustomerPoc> listFor(Long customerId)` | 83–86 | No scope check inside. Callers must check first. |
| `Optional<User> primaryFor(Long, PocType)` | 88–94 | Unused. Includes inactive users. |
| `Optional<User> defaultAssignee(Long, PocType)` | 96–99 | First active holder. |
| `List<User> activeHolders(Long, PocType)` | 101–104 | Unused. |
| `Map<PocType, List<User>> activeHoldersByType(Long)` | 106–115 | **One read of the book** (a test counts this, §10 R9). |
| `CustomerPoc add(Long customerId, PocType, Long userId, boolean makePrimary)` | 117–163 | Locks, validates, saves, audits, notifies. Throws `AlreadyAssignedException extends BadRequestException` (L165–169). |
| `void remove(Long customerId, Long pocId)` | 171–222 | Hard delete, then promote. Audits `POC_REMOVED` and possibly `POC_PRIMARY_CHANGED`. |
| `CustomerPoc setPrimary(Long customerId, Long pocId)` | 224–247 | Audits `POC_PRIMARY_CHANGED`. |
| `void notifyAssignee(User, PocType, String what, String link)` | 267–275 | Skips when the assignee is the caller. **Calls `currentUser.require()` (L269).** |
| `record PocAuditSnapshot(String pocType, Long userId, String username, boolean primary)` | 277 | Audit payload. |

**Audit convention.** Seat changes are audited under entity `"CUSTOMER"`, with constants at L30–32:

```java
auditService.record("CUSTOMER", customerId, AUDIT_POC_ASSIGNED,
        null,
        new PocAuditSnapshot(type.name(), user.getId(), user.getUsername(), primary),
        currentUser.require().getId(), null,
        "Added " + type.label() + " " + user.getUsername());
```

- This is L145–149.
- The signature is `AuditService.record(String entityType, Long entityId, String action, Object before, Object after, Long userId, Long disputeId, String reason)` (`B/audit/AuditService.java` L19–32). Before and after are serialised to JSON, and the reason is truncated to 500 characters.

**Notification convention.** `notificationService.notify(userId, "POC_ASSIGNED", title, message, link)` (L271–274).

### 2.6 Endpoints and DTOs

**`B/poc/PocController.java`** (`/api/pocs`). Every endpoint needs `POC_VIEW` and is refused for customer logins (`requireNonCustomerCaller` L62–66 → `AccessDeniedException`):
- `GET /assignable?type&q&limit` (L25–33) → `List<PocUserDto>`.
- `GET /types` (L35–48) → `{type, label, assignabilityPrivilege, callerIsAssignable}`.
- `GET /my-scope` (L50–60) → `{userId, clearable: canSeeEverything, sales, success, collection}`. **Everything here is global.**

**`B/customer/CustomerController.java`** POC endpoints. Each calls `service.get(id)` first, which is the scoped detail read that 404s outside the book:
- `GET /api/customers/{id}/pocs`: `POC_VIEW`, L88–94.
- `POST /{id}/pocs`: `POC_ASSIGN`, L96–104.
- `DELETE /{id}/pocs/{pocId}`: L106–112.
- `POST /{id}/pocs/{pocId}/primary`: L114–120.
- Bulk `ADD_POC` (L122–157):
  - Needs `CUSTOMER_MANAGE` plus a live `currentUser.canAssignPoc(userRepository)` check (L133–137), which returns 403.
  - Ids come from the scoped `resolveIds` (L201–209).
  - `AlreadyAssignedException` is turned into `BulkExecutor.IneligibleException` (L150–156).

**`B/poc/PocDtos.java`:**
- `PocUserDto(id, username, fullName, email, role, active)`, with `from(User)` null-safe (L10–17).
- `CustomerPocDto(id, pocType, primary, user, createdAt)` (L19–25).
- `AddCustomerPocRequest(@NotNull PocType pocType, @NotNull Long userId, Boolean primary)` (L27–31).
- DTO style throughout: **nested `record`s inside a `XxxDtos` holder class, with a static `from(Entity)` factory**.

### 2.7 Schema-upgrade convention (copy this)

Source: `B/poc/PocSchemaUpgrade.java` L14–37, `B/common/RowVersionUpgrade.java`, and `B/invoice/InvoiceSchemaUpgrade.java` L146–178.

```java
@Component
@Slf4j
class PocSchemaUpgrade implements InitializingBean {
    private final DataSource dataSource;
    PocSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) { // EMF param forces "after ddl-auto update"
        this.dataSource = dataSource;
    }
    @Override public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) { ... }
        catch (SQLException e) { log.warn("Could not finish ...: {}", e.getMessage()); }
    }
    // each step: try (Statement s = connection.createStatement()) {...; if (!connection.getAutoCommit()) connection.commit(); }
    //            catch (SQLException e) { log.warn(...); return 0; }
    // Postgres-only DDL gated on connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql")
}
```

Rules for adding a NOT NULL column:
- **NOT NULL on an existing table must be enforced by an upgrade class, not by the entity.** `backend/src/main/resources/application.yml` sets `hibernate.hbm2ddl.halt_on_error: ${SCHEMA_HALT_ON_ERROR:true}`, and `ddl-auto update` cannot add a NOT NULL column to a table that has rows. That combination **halts startup**.
- Follow `InvoiceSchemaUpgrade`:
  1. Keep the entity column nullable.
  2. Backfill in chunks (L62–88).
  3. Then run `enforceDueDateNotNull` (L146–164), which issues H2 and Postgres variants guarded by `isNullable` (L166–178). That check reads `information_schema.columns` with `schema()` on H2 and `current_schema()` on Postgres.

---

## 3. `ScopeResolver`: exact semantics (`B/poc/ScopeResolver.java`)

```java
@Component @RequiredArgsConstructor
public class ScopeResolver {
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters) {
        static Scope empty() { return new Scope(List.of(), List.of()); }   // package-private!
    }
```

(L20–31)

### 3.1 Capability checks

Each check does a live database lookup through `userRepository.hasPrivilege` (`UserRepository` L54–60). Every call is one query, and none are cached.

- `canSeePoc()` (L33–37): not a customer login AND `POC_VIEW`.
- `canSeeEverything()` (L39–43): not a customer login AND `SCOPE_OVERRIDE`.
- `isAssignableAs(PocType)` (L45–49): not a customer login AND the type's `POC_ASSIGNABLE_*` privilege.
- `isAnyPoc()` (L99–104, private): any of the three.

### 3.2 Per-entity scope

| Method | Customer login | `SCOPE_OVERRIDE` | Book-limited (holds the type's assignability) | Otherwise | `lockedFilters` |
|---|---|---|---|---|---|
| `forInvoices()` L51–54 | `root.customer.id = own` | empty | `root.salesPoc.id = me` (needs `POC_ASSIGNABLE_SALES`) | `cb.disjunction()` (nothing) | `["salesPocUserId:eq:<me>"]`, or `[]` for customer login and override |
| `forPayments()` L56–59 | `root.customer.id = own` | empty | `root.collectionPoc.id = me` (needs `POC_ASSIGNABLE_COLLECTION`) | nothing | `["collectionPocUserId:eq:<me>"]` |
| `forPromises()` L61–64 | `root.customer.id = own` | empty | `root.collectionPoc.id = me` (needs COLLECTION) | nothing | `["collectionPocUserId:eq:<me>"]` |
| `forCustomers()` L66–87 | `root.id = own` | empty | `EXISTS seat(customer=root, user=me, any type) OR EXISTS invoice(customer=root, salesPoc=me)` (needs *any* assignability) | nothing | `["myBook:eq:<me>"]` |
| `forDisputes()` L89–97 | `root.customerId = own` | **empty** | **empty (staff see all disputes)** | **empty** | `[]` |

Shared builder (L111–129):

```java
private Scope build(String customerAssociation, PocType type, String lockedColumn, BookPredicate book) {
    User me = currentUser.require();
    if (me.getCustomerId() != null) { ... cb.equal(root.get(customerAssociation).get("id"), own) ...; return new Scope(..., List.of()); }
    if (canSeeEverything()) return Scope.empty();
    Long meId = me.getId();
    if (!isAssignableAs(type)) return nothing(lockedColumn + ":eq:" + meId);
    return new Scope(List.of((root, q, cb) -> book.build(root, q, cb, meId)), List.of(lockedColumn + ":eq:" + meId));
}
private static Scope nothing(String lockedFilter) { return new Scope(List.of((root, q, cb) -> cb.disjunction()), List.of(lockedFilter)); }
```

The subqueries are `customerHasPocSeat` (L135–142) and `customerHasInvoiceOwnedBy` (L144–151).

**Asymmetries to keep in mind:**
- **The customer book ignores payment and promise ownership.** A collection POC named on a payment, with no seat on that customer, sees the payment but gets 404 on the customer (`GET /api/customers/{id}`, `/api/payments/credits/{customerId}`).
- **The invoice book ignores customer seats.** A SUCCESS or COLLECTION seat holder without `SCOPE_OVERRIDE` and without `POC_ASSIGNABLE_SALES` sees **no invoices**.
- **The customer-book seat check ignores `pocType`.** Any seat grants visibility.

### 3.3 Who is actually book-limited with the seeded roles (`B/config/DataSeeder.java`)

- ADMIN: all privileges (L54).
- CASHIER and VIEWER: include `SCOPE_OVERRIDE` (L64, L74).
- `CUSTOMER_SUCCESS_POC` (L108–123) and `COLLECTION_POC` (L124–138): **include `SCOPE_OVERRIDE`**.
- **Only `SALES_POC` (L93–107) is book-limited by default.**
- POC roles are created with `createRoleIfAbsent`. That adds only *newly created* privileges to existing roles (L175–192), so a region privilege added later would **not** be pushed onto existing roles unless it is new in `Privileges.ALL`.

### 3.4 Two privilege mechanisms (both global)

1. **`@PreAuthorize("hasAuthority('" + Privileges.X + "')")` and `CurrentUser.has(p)`** (`B/auth/CurrentUser.java` L35–39).
   - These read `Authentication.getAuthorities()`.
   - The authorities are built from `user.getRole().getPrivileges()` (`AppUserDetailsService.buildAuthorities` L32–38).
   - They are rebuilt from the database on every request (`JwtAuthFilter` L36).
2. **Live `userRepository.hasPrivilege(userId, p)`**, used by:
   - `ScopeResolver`.
   - `PocService.requireAssignable` L67 and `callerIfAssignable` L78.
   - `CurrentUser.canAssignPoc` L41–45.

`User` has exactly one `Role` (`B/user/User.java` L38–40: `@ManyToOne(fetch=EAGER, optional=false) role`). **There is no per-region grant structure anywhere.** The frontend receives a flat `privileges` list (`B/auth/dto/LoginResponse.java` L10–17) and checks it with `user.has(...)` (`F/shared/models/auth_models.dart` L34).

---

## 4. How scope is applied: query-engine mechanics (`B/common/query/`)

`PredicateFactory` (`PredicateFactory.java` L8–11):

```java
@FunctionalInterface
public interface PredicateFactory { Predicate build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb); }
```

`TableQueryExecutor` (L20–145), all `@Transactional(readOnly = true)`:
- `run(Class<T>, TableSchema, TableQuery, List<PredicateFactory> scope, List<String> fetchAssociations)` → `Page<T>(content, total)` (L30–59).
  - Unpaged (`size == Integer.MAX_VALUE`) is **silently capped** at `BULK_ID_LIMIT = 5000` (L23, L53–55).
- `count(...)` (L61–69).
- `inScope(type, schema, id, scope)` (L71–75): `if (scope.isEmpty()) return true;` otherwise it counts with an `id:eq:<id>` filter. **It never checks that the record exists when scope is empty**; callers do `findById` first.
- `ids(...)` (L77–87) and `aggregate(..., AggregateSelections)` (L89–100).
- The private `predicates(...)` (L107–120) builds each scope factory (skipping nulls), then each filter via `FilterPredicates.build`. **Everything is ANDed** (`all()` L130–132).
- There is no OR grouping anywhere in `FilterSpec` or `TableQuery`. `FilterPredicates` (L18) and `ValueCoercion` (L11) are **package-private**.

Other pieces:
- `PageResponse<T>(content, page, size, totalElements, totalPages, sort, appliedFilters, lockedFilters)` with `of(content, query, total, lockedFilters)` (`PageResponse.java` L6–23).
- `TableSchema.visibleTo(boolean customerScoped)` (`TableSchema.java` L46–50) removes `pocRestricted` columns **only for customer logins**. Staff without `POC_VIEW` can still filter by `salesPocUserId` and similar columns.
- POC columns in `TableSchemas`:
  - `INVOICES.salesPocUserId` and `salesPocName`: L65–69.
  - `PAYMENTS.collectionPocUserId` and name: L108–112.
  - `CUSTOMERS.successPocUserId` and `collectionPocUserId`: L123–132, with custom filter `pocSeatPredicate` L146–180 (EQ, NEQ, IN, IS_EMPTY, IS_NOT_EMPTY via EXISTS on `CustomerPoc`).
  - `PROMISES.collectionPocUserId`: L205–209.
- **Unscoped aggregate subqueries inside scoped queries.** These sum *all* invoices of the customer regardless of the caller's invoice scope or region:
  - `TableSchemas.customerOutstanding` (L135–144).
  - `CustomerService.outstandingSubquery` (L103–115).
  - `InvoiceRepository.sumOutstandingByCustomer` and `sumOverdueByCustomer` (L34–53, used by `CustomerService.toDtos` L159–166).
- Time anchors:
  - `TableSchemas.overduePredicate` uses `InvoiceDates.today()` (L81).
  - `FilterPredicates.relative` uses `LocalDate.now(ZoneOffset.UTC)` (L68).
  - DTOs use `InvoiceDates.today()` (`InvoiceDtos` L86, L117).
  - `InvoiceDates.today()` is `LocalDate.now(UTC)` (`B/invoice/InvoiceDates.java` L14–16).

**The standard service shape to copy for any new scoped entity** (from `CustomerService` L66–84, L140–145):

```java
@Transactional(readOnly = true)
public PageResponse<XDto> page(TableQuery query) {
    ScopeResolver.Scope scope = scopeResolver.forX();
    var page = queryExecutor.run(X.class, TableSchemas.XS, query, scope.predicates(), List.of("customer", ...));
    return PageResponse.of(toDtos(page.content()), query, page.total(), scope.lockedFilters());
}
public List<Long> idsMatching(TableQuery q, int limit) { return queryExecutor.ids(X.class, TableSchemas.XS, q, scopeResolver.forX().predicates(), limit); }
public List<X> allMatching(TableQuery q) { return queryExecutor.run(X.class, TableSchemas.XS, q, scopeResolver.forX().predicates(), List.of(...)).content(); }
private void requireInBook(Long id) {
    if (!queryExecutor.inScope(X.class, TableSchemas.XS, id, scopeResolver.forX().predicates()))
        throw new NotFoundException("X not found");   // outside scope == missing (AUTH-08), never 403
}
```

**Controller shape.** From `InvoiceController` L42–65 and L128–167:
- `schema()` returns `TableSchemas.X.visibleTo(currentUser.isCustomer())`.
- `list` calls `TableQuery.parse(schema(), page, size, sort, FilterParams.from(request))`.
- `summary` calls `TableQuery.parseUnpaged(schema(), null, filters)`.
- `export` and `bulk` go through `resolveIds`: scoped `idsMatching`, intersected with the requested ids, then `BulkExecutor.run(req, ids, truncated, op)`.
  - Rows outside scope are reported as skipped with `BulkExecutor.NOT_REACHABLE = "Not found, or outside your scope or the current filter"` (`BulkExecutor` L48, L62–66).
  - Each row runs in a `REQUIRES_NEW` transaction (L20–25).

**Exception conventions** (`B/common/GlobalExceptionHandler.java`):
- `NotFoundException` → 404.
- `BadRequestException` → 400.
- `AccessDeniedException` → 403.
- `InvalidFieldsException(Map)` → 400 with field errors.
- `ConcurrencyFailureException` → 409.
- `BadCredentialsException` → 401.

---

## 5. Every enforcement site

Legend: ✅ scoped by `ScopeResolver`; ⚠️ partially scoped; ❌ not scoped.

### 5.1 Customers
- ✅ List `CustomerService.page` L66–72 (`forCustomers`).
- ✅ Ids `idsMatching` L74–78, all `allMatching` L80–84, tiles `tiles` L86–101 (`GET /api/customers/summary`, `CustomerController` L56–61).
- ✅ Detail `get` L129–138: customer-login check, then `requireInBook` L140–145.
- ✅ `update` L254 and `delete` L301 via `requireInBook`.
- ✅ Export `CustomerController.export` L159–187 and bulk L124–157, via `resolveIds` L201–209.
- ✅ `InvoiceService.previewDueDate` L207–218 → `requireCustomerInBook` L288–293.
- ✅ `PaymentController.credit` L93–108: `forCustomers` `inScope`, 404 outside.
- ✅ The Customers POC endpoints (§2.6).
- ❌ `create` L204–248 (a new record, no scope).
- ⚠️ `toDtos` L152–202:
  - POC seats are shown only if `canSeePoc()` (L155, L174–178).
  - Outstanding and overdue sums cover all of the customer's invoices (unscoped).
  - It calls `userRepository.findByCustomerId` once per row (L168–171).

### 5.2 Invoices
- ✅ `page` L295–304, `idsMatching` L306–310, `allMatching` L312–316, `tiles` L318–350.
- ✅ `get` L260–273: customer-login mismatch → 404, then `requireInBook` L281–286.
- ✅ `update` L108 and `cancel` L358 via `requireInBook`.
- ✅ Controller export (L128–157) and bulk (L102–126) via `resolveIds`.
- `InvoiceController.list` and `summary` add `customerId:eq:` through `withCustomer` (L169–174). This is a filter, not an access check.
- ❌ `create` L59–99: **does not check that the customer is in the caller's book or scope.** The customer is loaded with plain `findById`. This is how a sales POC brings a customer into their book (via `customerHasInvoiceOwnedBy`).
- ❌ `getInternal` L275–279, `cancelWithRefund` L377–391 and `replaceItems` L393–418 are unscoped. They are called only from `DisputeService.applyInvoiceChange` L240–263.
- ✅ `InvoiceController.get`, `create`, `update` and `cancel` mask POC fields with `InvoiceDto.from(x, scopeResolver.canSeePoc())` (L70, 84, 91, 97). `InvoiceDto.from(inv)` defaults to `includePoc = true` (`InvoiceDtos` L81–83) and is used in audit snapshots.

### 5.3 Payments
- ✅ `page` L300–309, `idsMatching` L311–315, `allMatching` L317–321, `tiles` L323–348.
- ✅ `get` L272–284 and `update` L115 via `requireInBook` L286–291.
- ✅ Controller export (L130–155) and bulk (L112–128).
- ❌ `record` L54–109: customer not checked against scope. Invoice ids are only checked to belong to the customer (L79–84).
- ❌ `voidPayment` L205–218 and `updateAmount` L244–270 are unscoped (dispute-internal).
- ❌ `list(Long customerId)` L293–298 is **dead code, completely unscoped** (`paymentRepository.findAll()`).

### 5.4 Promises
- ✅ `get` L505–520, which inlines `inScope` with `forPromises` at L515–518.
- ✅ `update`, `cancel`, `override`, `clearOverride` and `reassignCollectionPoc` all go through `get`.
- ✅ `page` L537–544, `idsMatching` L546–550, `allMatching` L552–556, `tiles` L558–586.
- ✅ Controller export (L145–170) and bulk (L116–143).
- ❌ `create` L71–103: customer and invoices not checked against scope.
- ❌ `recomputeAll` L458–483 is global across all customers (`PROMISE_OVERRIDE`).
- ❌ `sweepOverdue` L491–503 is global and runs from the scheduler.
- ❌ `listForCustomer` L527–530 and `listForInvoice` L532–535 are **dead and unscoped**.

### 5.5 Disputes
- ⚠️ `DisputeController.list` L35–52 and `export` L80–102 use `forDisputes`. That scope is **empty for all staff**.
- ❌ `DisputeService.get` L143–152 only checks customer logins, and returns `AccessDeniedException` (403) rather than 404.
- ❌ `approve` and `deny` (L91–133) have no scope check, only global `DISPUTE_MANAGE`.
- ❌ `DisputeService.list` L135–141 is dead.
- ❌ `toDto` L154–165 reads the customer name and the target summary unscoped.
- ❌ `open` L52–89 allows **customer logins only** (L56–58). This matters for A3's "create Dispute" action.

### 5.6 Dashboard (`B/dashboard/DashboardService.java`)

All five endpoints use `ScopeResolver` and apply `scoped(root, cq, cb, scope)` (L301–309):

| Endpoint | Lines | Scope |
|---|---|---|
| `billedByMonth` | L65–81 | `forInvoices` |
| `outstandingByAge` | L83–109 | `forInvoices` |
| `topOutstanding` | L111–134 | `forInvoices`; staff only |
| `collectedByMonth` | L136–149 | `collected()` |
| `topPaying` | L151–175 | `collected()`; staff only |

`collected()` (L181–201):
- **If `paidAgainstBookOnly()`, which is `!forInvoices().lockedFilters().isEmpty()` (L203–205)**, it sums `PaymentAllocation`s on invoices in scope OR payments in scope, using `idsInScope` subqueries (L311–317).
- Otherwise it sums `Payment` under `forPayments`.

Coverage:
- `coverage(scope)` (L213–216) returns `OWN` for customer logins, `ALL` if `lockedFilters` is empty, and `BOOK` otherwise.
- `paymentCoverage()` is at L207–211.
- The DTO is `DashboardDtos.Coverage { ALL, BOOK, OWN }` (`DashboardDtos.java` L10).

Detail: `idsInScope` passes the **outer** `cq` into the `PredicateFactory` while `root` belongs to the subquery (L313–315). Factories must not call `q.from(...)` or rely on `q` being the root's own query.

### 5.7 Email (`B/email/`)
- ✅ `EmailTargets.load` and `requireVisible` (L215–225) → `loadScoped` (L240–254), which delegates to `customerService.get`, `invoiceService.get`, `paymentService.get`, `promiseService.get` and `disputeService.get`. Disputes are unscoped for staff (§5.5).
- ✅ `EmailTargets.bulkIds` L342–371 uses `forCustomers`, `forInvoices`, `forPayments`, `forPromises` and `forDisputes`. Products, users and roles are unscoped.
- ✅ Every `EmailService` entry point (`context` L106–151, `preview` L187, `send` L218, `bulk` L231–257, `retry` L297–320, `list` L322–334) calls `targets.load` or `requireVisible`.
- ⚠️ `EmailService.get` L336–355: a *party* to the email (sender or recipient) still sees it when the record is outside scope, with `canSeeRecord = false`.
- ⚠️ `InboxController` (L41–53, L103–108) is scoped by recipient `userId` only. There is no record scope.
- `EmailTargets.suggest` L373–386 uses `loadUnscoped` (L227–238), but only for a `Target` that was already loaded with scope.

### 5.8 Documents (`B/document/DocumentTargets.java`)
- ✅ `requireVisible` and `requireManageable` (L48–60) → `describe` (L74–91), which calls `customerService.get`, `invoiceService.get` or `paymentService.get` (all scoped).
- Used by `DocumentService`: upload L67, list L122, count L133, update L150, delete L166, download via `visible` L184.
- Test: `BT/document/DocumentAccessTest.java` L76–108 (`aPocCannotReachADocumentOnARecordOutsideTheirBook`) expects **404** everywhere.

### 5.9 Audit (`B/audit/AuditController.java`)
- ✅ `ensureCallerCanSee` L98–116 → scoped `get`.
- `readOrGone` L118–124 lets staff read the history of a *deleted* record.
- ⚠️ With `includeRelated`, `AuditTimelineService.timeline` (L63–112) loads all child invoices, payments, promises, disputes and allocations **without scope**, underneath a visible parent.

### 5.10 Other
- `UserController.pocReferenceCount` L236–241 counts across everything (used on delete and deactivate).
- `InvoiceNumbers` L40 uses global numbering.
- Email uniqueness checks are global (`CustomerRepository` L17–19, `CustomerService` L218–221, L260–263).

---

## 6. Every repository or query method that returns lists or counts of the core entities

Each entry gives the caller, its location, and whether it is scoped.

### `CustomerRepository` (`B/customer/CustomerRepository.java`)
- Inherited `findAll`, `findAllById`, `count`: no production list callers.
- `findByIdForUpdate` L13–15: a lock, not a list.
- `existsByEmailIgnoreCase` L17 and `existsByEmailIgnoreCaseAndIdNot` L19: global uniqueness.
- `EmailDirectory.customerByAddress` (`B/email/EmailDirectory.java` L79–91): JPQL `select c.id from Customer c where lower(c.email)=...`. Global; used for inbound mail matching.

### `InvoiceRepository` (`B/invoice/InvoiceRepository.java`)
- `findByCustomerIdOrderByInvoiceDateDesc` L15:
  - `PaymentPromiseService.owedUnderPromise` L410 (internal).
  - `AuditTimelineService` L77 (under a visible parent).
- `findAllByIdForUpdate` L21–23: `PaymentService.record` L69, `lockInvoices` L168.
- `findByCustomerIdForUpdate` L25–27: `PaymentService` L86, L263.
- `findByStatusIn` L28: **unused**.
- `countByInvoiceNumberStartingWith` L29: `InvoiceNumbers` L40.
- `existsBySalesPocId` L31: unused.
- `countBySalesPocId` L32: `UserController` L237.
- `sumOutstandingByCustomer` L34–41 and `sumOverdueByCustomer` L43–53: `CustomerService.toDtos` L159, L164. Unscoped figures.

### `PaymentRepository` (`B/payment/PaymentRepository.java`)
- `findByCustomerIdOrderByPaidAtDesc` L8:
  - `PaymentService.list` L297 (dead).
  - `CreditLedger` L95.
  - `PaymentPromiseService.evaluateCustomer` L233.
  - `AuditTimelineService` L78.
- Inherited `findAll`: `PaymentService.list` L296 (dead).
- `existsByCollectionPocId` L10: unused.
- `countByCollectionPocId` L11: `UserController` L238.

### `PaymentAllocationRepository` (`B/payment/PaymentAllocationRepository.java`)
- `findByInvoiceIdWithPayment` L11–12: `CreditLedger` L66, `AuditTimelineService` L95.
- `findByCustomerIdWithPayment` L14–16: `AuditTimelineService` L81.

### `PaymentPromiseRepository` (`B/promise/PaymentPromiseRepository.java`)
- `findByCustomerIdOrderByPromisedDateDesc` L12: `listForCustomer` L529 (dead), `AuditTimelineService` L79.
- `findLiveByCustomer` L14–16: `evaluateCustomer` L232, `recomputeAll` L462.
- `findCustomerIdsWithLivePromises` L18–20: `recomputeAll` L461. **Global.**
- `findByInvoiceId` L22–23: `listForInvoice` L534 (dead), `AuditTimelineService` L92.
- `findByPaymentId` L25–26: `AuditTimelineService` L106.
- `findOverdueOpen` L28–30: `sweepOverdue` L493. **Global, scheduler.**
- `countLinkedPayments` L32–33: unused.
- `countByCollectionPocId` L35: `UserController` L239.
- `existsByCollectionPocId` L37: unused.

### `DisputeRepository` (`B/dispute/DisputeRepository.java`)
- `findAllByOrderByCreatedAtDesc` L18: `DisputeService.list` L140 (dead).
- `findByCustomerIdOrderByCreatedAtDesc` L19: `DisputeService.list` L139 (dead), `AuditTimelineService` L80.
- `findByStatusOrderByCreatedAtDesc` L20: unused.
- `findByTargetTypeAndTargetId` L21: `AuditTimelineService` L93, L107.
- `existsByCustomerIdAndTargetTypeAndTargetIdAndStatus` L22–25: `DisputeService.open` L61.
- Inherited `findAllById`: `DisputeController.export` L94, after scoped ids.

### `CustomerPocRepository` (`B/poc/CustomerPocRepository.java`): the book itself
- `findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc` L15: `PocService.listFor` L85, `activeHoldersByType` L109, `CustomerService.delete` L310.
- `findByCustomerIdAndPocType` L17: unused.
- `findByCustomerIdAndPocTypeAndPrimaryTrueOrderByIdAsc` L19: `primaryFor` L92 (unused).
- `findByCustomerIdAndPocTypeForUpdate` L21–25: `add` L133, `remove` L189, `clearPrimary` L251.
- `findByCustomerIdAndUserIdAndPocType` L27: `add` L128.
- `findByCustomerIdIn` L29: `CustomerService.toDtos` L175.
- `existsByUserId` L31: unused.
- `countByUserId` L33: `UserController` L240.
- `deleteByCustomerId` L35: unused.

### `UserRepository` book-related methods
- `findAssignable` L37–52: `PocService.assignable`.
- `hasPrivilege` L54–60: `ScopeResolver` ×3, `PocService` L67 and L78, `CurrentUser.canAssignPoc`.

### Criteria entry points (all scope-aware through `TableQueryExecutor`)

| Entity | Call sites |
|---|---|
| Customer | `CustomerService` L69, 76, 82, 89, 141; `InvoiceService` L289; `PaymentController` L104; `EmailTargets` L344 |
| Invoice | `InvoiceService` L282, 299, 308, 314, 322; `EmailTargets` L346 |
| Payment | `PaymentService` L287, 304, 313, 319, 326; `EmailTargets` L348 |
| Promise | `PaymentPromiseService` L515, 540, 548, 554, 561; `EmailTargets` L350 |
| Dispute | `DisputeController` L48, 84; `EmailTargets` L352 |
| Dashboard (direct criteria) | `DashboardService` L66–201 |
| Unscoped subqueries | `TableSchemas` L135–180, L233–266; `CustomerService` L103–127 |

---

## 7. Frontend POC feature (`F/features/poc/`)

### `poc_providers.dart`
- `enum PocType { SALES, SUCCESS, COLLECTION }` (L8) mirrors the backend. `pocTypeLabel` L10–14 duplicates the backend labels. `parsePocType` defaults to SALES (L16–17).
- `PocUser` (L19–52): `display` is fullName or username; equality is by id.
- `CustomerPoc` (L54–74).
- `MyPocScope(userId, clearable, sales, success, collection)` (L76–101):
  - `clearable` defaults to **true** when missing (L96).
  - `isAnyPoc` (L92).
- `canSeePocProvider` (L103–107) and `canAssignPocProvider` (L109–113) use **global** `user.has(Privileges.pocView / pocAssign)`.
- `myPocScopeProvider` → `GET /api/pocs/my-scope` (L115–121). Used by `F/features/invoices/invoice_form_screen.dart` L205–211 to preselect the caller as Sales POC.
- `assignablePocsProvider` → `/api/pocs/assignable?type&q&limit=25` (L136–145). No region or customer parameter.
- `customerPocsProvider` → `/api/customers/{id}/pocs` (L147–152).

### Other files
- `customer_poc_editor.dart`:
  - add → `POST /api/customers/{id}/pocs {pocType,userId,primary}` (L87–90). "Make primary" is pre-ticked when there are no seats (L48).
  - remove → `DELETE` (L116–118).
  - set primary → `POST .../primary` (L121–123).
  - `editable` comes from `canAssignPoc` (`F/features/customers/customer_detail_screen.dart` L377–396).
- `poc_picker.dart`: `PocPicker` and `_PocPickerDialog` use `assignablePocsProvider` (L122). `PocMissingBadge` L186–210.
- `poc_name_cell.dart`: marks inactive holders with " (inactive)" (L19–20).

### Scope signals consumed elsewhere
- `F/core/table/table_models.dart` L132–156: `PagedResult.lockedFilters` is parsed with `TableFilter.parse`. Unparseable entries are dropped (`whereType`).
- `F/core/table/data_table_scaffold.dart` L774–781: a lock chip with tooltip "Your role limits this list to your own records". `_lockedLabel` (L806–809): `myBook` → "My book only", anything else → "My records only".
- `F/features/dashboard/dashboard_providers.dart`:
  - `Coverage { all, book, own }` L8–14.
  - `UpcomingPromises.book = lockedFilters.isNotEmpty` (L232).
  - Used in `dashboard_screen.dart` L308, 357, 388, 434, 455.
- `F/core/table/reference_picker.dart`:
  - `pocUser` → `/api/pocs/assignable` for all three types (L32–53), global.
  - `customer`, `invoice` and `payment` options come from the scoped list endpoints (L54–80).

---

## 8. How the PRD threads through this area

### 8.1 B1 Regions: "every record belongs to exactly one region"

**Data.** Put a region on `Customer`, and either:
- (a) copy `region_id` onto `Invoice`, `Payment`, `PaymentPromise`, `Dispute` and the new `Task`, or
- (b) derive it through `customer`.

Considerations:
- `Dispute` has no `customer` association, only `customerId Long` (`Dispute` L25–26). Option (b) needs a correlated subquery on `Customer` for disputes.
- B3 needs "which region a record belonged to **then**". That calls for a history table (for example `(entity_type, entity_id, region_id, valid_from, valid_to)`) whichever option is chosen.
- New columns must follow the nullable, backfill, then enforce-NOT-NULL pattern (§2.7).

**Rights per region.** Today there is one global `User.role`. A grant structure such as `user_region_roles(user_id, region_id, role_id)` is needed. Every path that decides visibility must become region-aware:
- `ScopeResolver.canSeeEverything`, `isAssignableAs` and `canSeePoc`, which call `hasPrivilege(userId, p)` (L33–49). This becomes `hasPrivilegeInRegion(userId, p, regionId)` or "the set of regions where p is held".
- The `@PreAuthorize(... X_VIEW)` annotations on every list and detail endpoint. With region rights, "can see the list at all" becomes "has X_VIEW in at least one region", and the per-row check moves into scope.
- `CurrentUser.canAssignPoc` (L41–45).
- `PocService.requireAssignable` (L54–72) and `UserRepository.findAssignable` (L37–52).

**The POC book per region.** The natural generalisation of `ScopeResolver.build` (L111–129) and `forCustomers` (L66–87) is an OR over the regions being asked about:

```
predicate = OR over r in (askedRegions ∩ regionsWhere(X_VIEW)) of
              ( record.region = r AND tier(r) )
tier(r) = SCOPE_OVERRIDE in r ? TRUE
        : POC_ASSIGNABLE_<type> in r ? bookPredicate(me)
        : FALSE
```

- If no region qualifies, return `cb.disjunction()`, as `nothing()` does at L131–133.
- Keep the customer-login branch unchanged. A customer login sees its own customer, whose region is implicit.
- Because every call site listed in §5 and §6 passes `scopeResolver.forX().predicates()`, **changing the five `forX()` methods covers every list, count, ids, aggregate, `inScope`, export, bulk, email bulk, dashboard and document or email detail check automatically**.
- The **gaps** are the ❌ items in §5, which must be handled explicitly:
  - The create paths (`InvoiceService.create` L59, `PaymentService.record` L54, `PaymentPromiseService.create` L71, `CustomerService.create` L204). These need a "manage in the target region" check.
  - The dispute paths.
  - The unscoped subqueries (`customerOutstanding` and related).
  - The global schedulers (`recomputeAll`, `sweepOverdue`).
  - `AuditTimelineService` with `includeRelated`, if children can live in a different region from their parent.

**Assignability is per region.**
- `PocController.assignable` and `/types` (L25–48), `PocService.assignable` and `requireAssignable`: add a `regionId` (or `customerId`, and derive the region) and restrict to users holding `POC_ASSIGNABLE_*` **in that region**.
- The frontend `assignablePocsProvider` (L136–145) and `reference_picker.dart` L32–53 must pass it.
- `add` (L117), `InvoiceService.create/update` (L64, L137), `PaymentService.record/update` (L62, L124) and `PaymentPromiseService.update/resolveCollectionPoc` (L127, L614) all call `requireAssignable(userId, type)`. That signature needs the region.
- Decide what happens to seats and record POCs when a customer or record **moves region**: the holder may not be assignable in the new region.

**`/api/pocs/my-scope`** (L50–60) and `MyPocScope` (Dart L76–101) must become per region, for example `{userId, regions: [{regionId, clearable, sales, success, collection}]}`.

**"Which regions am I asking about".** This is a request-level parameter.
- Existing precedent for reading multi-valued request parameters: `FilterParams.from(HttpServletRequest)` (`FilterParams.java` L14–18).
- `ScopeResolver` is a singleton that reads `CurrentUser` from the `SecurityContext`. Either pass regions explicitly (`forInvoices(ScopeRequest)`) or add a request-scoped holder filled by a filter or argument resolver.
- The frontend has a single place where every list and summary request is built (`DataTableScaffold` with `tablePageProvider` and `tableSummaryProvider`), plus the dashboard providers (`dashboard_providers.dart`).

**Response metadata.** Do **not** put region scope into `lockedFilters` (§10 R1). Add a separate field to `PageResponse` (for example `regions`), and extend `DashboardDtos.Coverage` or add `regions` to each dashboard DTO.

**Filtering by region.** Add a `region` REFERENCE column to `TableSchemas.{CUSTOMERS, INVOICES, PAYMENTS, PROMISES, DISPUTES}` for the user's own filtering. Authorisation must still live in `ScopeResolver`, because a user filter is only ANDed on top.

**Figures.** "Every count or dashboard figure" includes:
- Summary tiles (the four `tiles()` methods).
- The five dashboard endpoints.
- `CustomerDto.outstanding/overdueAmount` (`CustomerService.toDtos` L158–166).
- The `outstanding` sort and filter column (`TableSchemas` L121–122).

The last two are unscoped sums over the customer's invoices. They are correct only if an invoice's region always equals its customer's.

### 8.2 B2 Maker-checker (visibility intersection)

- "A pending change is visible to everyone who can see the record." Scope the pending-change list and detail with `EXISTS (target in forX() scope)`. Build it from the target entity's `forX().predicates()` inside a subquery, as `DashboardService.idsInScope` does (L311–317).
- **Do not copy `forDisputes()`**, whose scope is empty for all staff.
- The approver must hold the approval right in the **record's** region, not globally. Neither `@PreAuthorize` nor `CurrentUser.has` can express that.
- The closest existing analogue of propose → approve → apply is `Dispute`:
  - `proposedChangeJson` (`Dispute` L41–43).
  - `approve` and `deny` with `mustBePending` under `PESSIMISTIC_WRITE` (`DisputeService` L91–133, L167–174).
  - The audit `disputeId` link (L108–113).

  Its apply paths call **unscoped** internals (`cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount`).

### 8.3 B3 As-of reads (scope implications)

- Seats are hard-deleted with no validity dates (§2.1). As-of books need an effective-dated seat table, or reconstruction from audit rows (`POC_ASSIGNED`, `POC_REMOVED`, `POC_PRIMARY_CHANGED` with `PocAuditSnapshot`).
- Record POC changes (`salesPoc`, `collectionPoc`) are only captured inside `INVOICE_UPDATED`, `PAYMENT_UPDATED` and `PROMISE_UPDATED` DTO JSON (`InvoiceService` L143–145, `PaymentService` L130–131, `PaymentPromiseService` L141–142).
- "Now" is hard-coded in:
  - `InvoiceDates.today()`, used by the `overdue` filter (`TableSchemas` L81), the DTOs, the dashboard (`DashboardController.today()` L52–54) and `CustomerService.toDtos` L164.
  - `FilterPredicates.relative` (`LocalDate.now(UTC)` L68).

  All of these must accept an as-of date.
- The query engine reads live columns only (`paidAmount`, `status`).

### 8.4 A3/A4/A5/A6 touch-points in this area

**The role-at-a-level model to reuse (A3):**
- `RoleRef(EmailRole role, RoleLevel level)` with token `ROLE:<LEVEL>:<ROLE>` (`RoleRef` L5–42).
- Offered roles per type: `EmailTargets.ROLES_OFFERED` L114–118.
- Default level: `EmailTargets.defaultLevel` L198–200.
- Validation: `EmailAddressing.offeredRole` L122–132.
- Resolution:
  - `EmailTargets.holders` L308–326. Customer level uses `pocService.activeHoldersByType` (one book read). Record level uses the record's own field.
  - **To** goes to **all** active holders of a role (`EmailAddressing.resolve` L152–163).
  - **From** goes to `Target.sender(role)`, the first holder (L157–160).

**A4 "a role held by several people resolves to the primary".** The code has two candidate meanings:
- `Target.sender` or `PocService.defaultAssignee`: primary if active, otherwise the oldest active holder.
- `PocService.primaryFor` (L88–94, currently unused): the primary even if inactive.

Pick one and document it. `sender` is the consistent choice.

**Tasks (A6).** Tasks on customers, invoices and payments need a `ScopeResolver.forTasks()` that means "parent is in scope", built as an OR of EXISTS over the parent types.
- "People need somewhere to see their work" is like the Inbox: assignee-scoped (`InboxController.ownedByCaller` L103–108) plus a `canSeeRecord` flag, as `EmailService.get` does at L336–355.
- Decide whether an assignee who has lost visibility of the parent still sees the task.
- Tasks must also carry a region and appear in region-scoped counts.

**A5 consumer or scheduler: no principal.**
- `CurrentUser.require()` throws `BadCredentialsException` (`CurrentUser` L12–18). Only `idOrNull()` (L24–29) is safe.
- It is called by every `ScopeResolver.forX()` and capability check.
- It is also called by write paths an action would reuse:
  - `PocService.notifyAssignee` L269, which runs even inside `InvoiceService.create` L95, `PaymentService.record` L105 and `PaymentPromiseService.update` L129.
  - `PocService.add/remove/setPrimary` audit (L142, 148, 157, 210, 219, 244).
  - `PaymentPromiseService.create` L94 and L99.
  - `EmailAddressing.plan` L60.
  - `EmailService.save` L277.
  - `EmailTargets.load` → scoped `get`.
  - `DisputeService.open` L54, which also requires a customer caller at L56–58.
- Existing background jobs avoid this entirely: `PromiseSweepScheduler` (L17–30) and `PaymentPromiseService.applyShares` audit with `null` actor (L265–266).
- The consumer therefore needs either a system principal or principal-free service variants, plus an explicit decision on whose scope and regions a rule acts within.
- Scheduling is already enabled (`@EnableScheduling` in `GeneInvoiceApplication` L8; scheduler pool size 3 in `application.yml`).

**A2 conditions.** `TableSchemas` is a ready catalogue of fields, types and operators per entity, and gives parity with list filters. It is **AND-only** (`TableQueryExecutor.all` L130–132). `FilterPredicates` and `ValueCoercion` are package-private, so an OR-group evaluator must live in `com.geneinvoice.common.query` or those must be opened up. Scheduled evaluation with `TableQueryExecutor.ids(...)` is capped at 5000 rows (L23, L86).

---

## 9. Conventions to copy

**Detail read that hides out-of-scope records as 404** (`InvoiceService` L260–273, AUTH-08):

```java
Invoice inv = invoiceRepository.findById(id).orElseThrow(() -> new NotFoundException("Invoice not found"));
Long callerCustomer = currentUser.customerIdOrNull();
if (callerCustomer != null && !callerCustomer.equals(inv.getCustomer().getId())) throw new NotFoundException("Invoice not found");
requireInBook(id);
```

**Write under a row lock, then check scope, then check version** (`InvoiceService.update` L106–118): `findByIdForUpdate` → `requireInBook` → `OptimisticLockingFailureException` on a version mismatch.

**Masking POC fields in DTOs:**
- `XDto.from(entity, scopeResolver.canSeePoc())`.
- `CustomerService.toDtos` returns null lists when `!showPoc` (L197–199).
- `PaymentPromiseService.toDto` L590 and L597.

**Summary tiles** use `queryExecutor.aggregate(..., scope.predicates(), (root,q,cb) -> List.of(...))` with `Aggregates.countWhen` and `sumWhen` (`Aggregates.java` L13–21).

**Controllers:**
- `@PreAuthorize("hasAuthority('" + Privileges.X + "')")` on every endpoint.
- Export additionally requires `EXPORT_DATA` (for example `InvoiceController` L129).
- Customer logins are refused with `AccessDeniedException("Not allowed")`.

---

## 10. Risks and gotchas

1. **R1: `lockedFilters` is used for three things.**
   - `DashboardService.paidAgainstBookOnly()` (L203–205) switches the whole "collected" computation to allocation-based as soon as `forInvoices().lockedFilters()` is non-empty.
   - `coverage()` (L213–216) reports `BOOK`.
   - The frontend `UpcomingPromises.book` (`dashboard_providers.dart` L232) and the lock chips (`data_table_scaffold.dart` L774–809) label any non-`myBook` entry "My records only".

   Putting region entries into `lockedFilters` would make admins' "collected" figures and coverage wrong. Carry regions in a new field.
2. **R2: `TableQueryExecutor.inScope` returns `true` for an empty scope** (L73). Adding a region predicate for everyone changes both performance (a count query on every detail read) and behaviour: it no longer short-circuits. `Scope.empty()` is package-private (`ScopeResolver` L28).
3. **R3: No principal in background code** (§8.4). `ScopeResolver`, `PocService.notifyAssignee` and the audit calls with `currentUser.require()` throw `BadCredentialsException`.
4. **R4: Disputes are unscoped for staff** (list, detail, approve, deny, export and email target). Region scoping must add a real staff predicate. `Dispute` has only `customerId` as a Long, so paths like `root.get("customer")` do not exist and need a subquery.
5. **R5: Create paths do not check scope** (`InvoiceService.create`, `PaymentService.record`, `PaymentPromiseService.create`, `CustomerService.create`). Region "manage" rights must be checked there explicitly, because `ScopeResolver` is never consulted.
6. **R6: Unscoped figure subqueries.** Customer outstanding and overdue (DTO, sortable column, tiles) sum all invoices of the customer. If invoice region can differ from customer region, these leak across regions.
7. **R7: Dead unscoped methods are traps.**
   - `PaymentService.list` (L293–298).
   - `PaymentPromiseService.listForCustomer` and `listForInvoice` (L527–535).
   - `DisputeService.list` (L135–141).
   - `InvoiceService.getInternal` (L275–279).
   - `PocService.primaryFor` and `activeHolders` (L88–104).

   Any new feature that calls them bypasses scope.
8. **R8: The primary invariant is enforced by locks, plus an index on Postgres only.** H2 tests will not catch a new write path that forgets `lockCustomer` → `...ForUpdate` → `clearPrimary`.
9. **R9: A SQL-count test.** `BT/email/EmailContextTest.java` L124–136 asserts exactly **one** read of `customer_pocs` per email-context request. Region or book predicates that touch `customer_pocs` (for example "book per region" done through a seat subquery) will break it.
10. **R10: Test fixtures.**
    - `IntegrationTestBase.customer(name)` (`BT/IntegrationTestBase.java` L119–125) builds customers with a name only. A required region breaks every test unless a default region is applied.
    - `resetTransactionalData` (L65–89) deletes tables in dependency order. New tables that reference customers or users (tasks, rules, pending changes, region grants, seat history) must be added there, or `customerRepository.deleteAll()` fails on foreign keys.
11. **R11: `halt_on_error: true`.** An entity column declared `nullable=false` on an existing table stops startup (§2.7).
12. **R12: Staff without `POC_VIEW` can filter and sort by POC columns.** `visibleTo()` strips them only for customer logins (`TableSchema` L46–50). New region columns have the same issue if they should be hidden.
13. **R13: The book is asymmetric** (§3.2). Customer visibility for collection POCs does not include customers they only hold payments or promises on. Invoice visibility ignores customer seats. Preserve or deliberately change this when adding regions; do not change it by accident.
14. **R14: Unpaged queries are silently capped at 5000** (`TableQueryExecutor` L53–55). Scheduled rule evaluation and exports that use `run(..., parseUnpaged)` truncate without an error. Only bulk requests report `truncated`.
15. **R15: `createRoleIfAbsent` only grants newly created privileges to existing POC roles** (`DataSeeder` L175–192). `upsertRole` overwrites the privileges of ADMIN, CASHIER, VIEWER and CUSTOMER on every start (L167–173). Per-region grant seeding needs its own path.
16. **R16: Frontend privilege checks are global** (`user.has(...)`, `canSeePocProvider`, `canAssignPocProvider`). With per-region rights, the UI must ask per record or per region, for example with flags on each DTO (`canEdit`, as documents already expose at `DocumentAccessTest` L210–213), instead of relying on a global privilege.