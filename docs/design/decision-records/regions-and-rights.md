# B1 decision record: regions, per-region rights, the POC book per region, and "which regions am I asking about"

**Status:** decided. It is written to be implemented without the two proposals.

**Winner:** the *access-levels* proposal. Each user keeps one `Role`, which says **what** they may do. Each user also gets per-region grants of `(level READ | MANAGE | APPROVE, scope ALL | BOOK)`, which say **where** and **how far**. A right is effective only when both agree.

Nine ideas are grafted from the *role-per-region* proposal, and eight flaws found in both proposals are fixed (§1.3).

**Paths used below:**
- `B/` = `backend/src/main/java/com/geneinvoice/`
- `BT/` = `backend/src/test/java/com/geneinvoice/`
- `F/` = `frontend/lib/`

---

## 1. Evaluation

### 1.1 Scores

| Criterion | role-per-region `(user, region, role)` | access-levels `(user, region, level, scope)` |
|---|---|---|
| **PRD fit** | Meets B1. Most expressive: it allows a different *function* per region, for example Sales POC in North and Collection POC in South. | Meets B1 literally: "read in one, manage in another, nothing in a third" is exactly the level axis. It cannot give a different function per region. That path stays open with an additive nullable `role_id` column on the grant (§9). |
| **Concurrency and failure** | Good: `updatable=false` on `region_id` protects rows that have no `@Version`. Weak: a "grants table empty" one-shot marker re-grants everyone after a deliberate total revocation plus a restart. | Weak: the move uses `customer.setRegion` + save, and stale saves of Payment/Promise/Dispute (no `@Version`) can write the old region back. Good: a per-user backfill flag, and the grant replace runs under a user row lock. |
| **Fit with this codebase** | Changes the meaning of `User.role` (global only). This needs a compatibility hack in `PUT /api/users`, a region-aware rewrite of `GmailDisconnects.mayConnect`, `RoleController.delete` and `UserController.update`, and every READ-level check (`canSeePoc` at 14 sites, audit, document, email view) done per region. | Every role-keyed seam keeps its meaning. **Key insight (§4.5):** for READ-level privileges, "effective in the record's region" equals "role holds it" whenever the record is visible, so those checks stay unchanged. |
| **Implementable by AI agents in slices** | Larger surface, and a 15-field `RecordAccess` record. | Smaller surface. The slices each leave existing tests green. |
| **Volume and regression risk** | Higher. A missed region check falls back to the union of all regional roles, which can exceed what any one region intended. | Lower. A missed region check falls back to "role ∧ held somewhere", which never exceeds the user's own role. |

### 1.2 Spot checks against source

| # | Claim | Result |
|---|---|---|
| 1 | `ScopeResolver` returns `Scope.empty()` for override users and for staff disputes; `hasPrivilege` is queried per call (`B/poc/ScopeResolver.java:39-43,89-97,111-129`) | Held |
| 2 | The principal is built in a servlet filter; `loadUserByUsername` is not transactional; the only `new AppUserDetails` sites are `AppUserDetailsService:29` and `IntegrationTestBase:134,141` | Held (grep) |
| 3 | Only `Invoice` and `Customer` have `@Version`; `Payment` and `Dispute` have none (`Dispute` has only `Long customerId`, lines 25-26) | Held (grep for `@Version`) |
| 4 | `InvoiceService.create` loads the customer with plain `findById` (61); `PaymentPromiseService.create` does the same (73); `PaymentService.record` locks it (60); `CreditLedger` locks the customer after the invoice-number sequence (`payment/CreditLedger.java:33`) | Held. This exposes a race **neither proposal handled** (§1.3 F1) |
| 5 | `DashboardService.collected` switches formula on `forInvoices().lockedFilters()`, and today `forInvoices()` never checks `INVOICE_VIEW` (`:181-205`) | Held. This exposes a regression in **both** proposals' dashboard split (§1.3 F2) |
| 6 | H2 2.2.224: `alter table t alter column region_id set not null` keeps the FK and enforces NOT NULL; the correlated backfill works; `insert … select` with parameters works; `add column … boolean default … not null` fills existing rows | Held (my own probe in the scratchpad `judge-regions/Probe.java`) |
| 7 | No test constructs `CustomerCreateRequest`, `PageResponse` or `Invoice`/`Payment`/`Dispute` entities directly; custom roles are always attached through `user(name, role)` | Held (grep) |
| 8 | `PageResponse` is built only in `of` and `map`; `TableSchemas.buildIndex` and `TableSchemaController.VIEW_PRIVILEGE` both need a new schema registered | Held |
| 9 | `UserController.create` and `update` are not transactional; `GmailDisconnects.mayConnect` reads `user.getRole()`; `notifyAdmins` has one caller (`DisputeService:82`) | Held |
| 10 | `AuditController.readOrGone` lets staff read a deleted record's history; only customers are hard-deleted | Held |

### 1.3 Flaws fixed in the synthesis

- **F1. Child-create race with a region move (both proposals).** Invoice and promise creation read the customer's region without a lock. A create that runs during a move commits a child with the old region. **Fix:** every path that inserts a region-bearing child reads the customer with `findByIdForUpdate` (§4.9).
- **F2. Dashboard split regression (both proposals).** Both partitioned "collected" by `regionsWith(INVOICE_VIEW)`, so a role with PAYMENT_VIEW and override but no INVOICE_VIEW would change from "sum of payments" to "zero" or "allocations". **Fix:** the VIEW gate in `ScopeResolver` is "holds any grant in the region", which preserves today's semantics exactly (§4.7). The split is by override per region (§4.8.4).
- **F3. Stale save overwrites a moved region (access-levels).** **Fix:** `region_id` is mapped `updatable = false`, and the move uses native SQL (§4.3, §4.12).
- **F4. `TableQueryExecutor.inScope` would add a count query to every by-id read (access-levels).** **Fix:** an in-memory tier check. The count query is kept only for the BOOK tier (§4.7).
- **F5. Backfill-flag pitfall (access-levels).** `@Builder.Default boolean … = true` is left `false` by Lombok's no-args constructor. **Fix:** invert the flag to `region_backfill_pending`, which has a DB default of `true` and a Java default of `false` (§4.3).
- **F6. "Grants table empty" one-shot marker (role-per-region).** It re-grants everyone after a deliberate total revocation plus a restart. **Fix:** the per-user flag (§4.4).
- **F7. Grant replace inside one flush (both proposals).** Hibernate runs inserts before deletes, which violates `uk_user_region`. **Fix:** a bulk JPQL delete, then the inserts (§4.11).
- **F8. `POST /api/users` without grants (access-levels).** Today's form would create users with no data access. **Fix:** when the grants are absent, the user gets DEFAULT/APPROVE/ALL (§4.11, P7).

### 1.4 Grafted from role-per-region

1. `updatable=false` on `region_id`.
2. The in-memory tier check for by-id reads.
3. The `RegionMoveParticipant` SPI, with veto, for A6, B2, B3 and A5.
4. `audit_logs.region_id` for deleted-customer history.
5. A default grant when `POST /api/users` omits grants.
6. The endpoint-enumeration safeguard test.
7. `ViewScopeHolder.with(...)` for the consumer.
8. The `@PrePersist` safety net on child entities.
9. The per-region `/api/pocs/my-scope` array.

---

## 2. PRD clause coverage

**B1: "Regions."**
- New `regions` table, admin API and admin screen (§4.3, §4.11, §5).

**B1: "Every record belongs to exactly one."**
- `region_id` NOT NULL, with an FK to `regions`, on `customers`, `invoices`, `payments`, `payment_promises` and `disputes`.
- It is copied from the customer on create and changed only by the explicit move, which cascades (§4.12).
- Documents, emails, invoice items, allocations and POC seats derive their region from their parent.
- Task and pending-change tables (A6, B2) must also carry `region_id NOT NULL` (§4.14).
- **Deliberate interpretation:** products, users, roles, regions and notifications are configuration or per-person rows, not business records, so they have no region. Products stay a global catalogue.

**B1: "A user holds different rights in different regions — read in one, manage in another, nothing in a third."**
- Grants `(region, level, scope)`. READ@North, MANAGE@South and no row for West give 403 on North writes, 200 on South writes, and 404 on anything in West (§4.5, §4.9, the Rita test).

**B1: "The POC book is per region."**
- `ScopeResolver` decides a tier **per region**:
  - FULL: override held there.
  - BOOK: assignable as the entity's POC type there.
  - NONE: otherwise.
- POC assignability is checked per region. Seat holders without access in a region are skipped as holders.
- `/api/pocs/my-scope` reports each region (§4.7, §4.10).

**B1: "Every list, filter, count and dashboard figure becomes a question about which regions am I asking about."**
- The `regionIds` query parameter is read in one place (`ViewScope`) and intersected with the caller's grants.
- It is applied by `ScopeResolver` to every list, count, tile, export, bulk "select all", dashboard figure, POC picker and email bulk send.
- The regions actually answered are echoed in `PageResponse.regionIds` and `DashboardDtos.scope`.
- A `regionId` filter column exists on the five core schemas (§4.6-§4.8).

**B2 (hooks only):**
- "…approval from someone else holding the approval right in that record's region": CHANGE_APPROVE is classified at APPROVE level; approver queries are provided (§4.14).
- "Thresholds are per region": keyed on `regions.id` (B2's table).

**B3 (hooks only):**
- "…including which region a record belonged to then": the move publishes to history through a participant; `Scope.coverage()` exposes region sets for history roots (§4.14).

**Predecessor PRD:** "Nothing an automation sends or assigns escapes existing permissions."
- Rules run as their owner through `principalFor(owner)` (P3).
- Holders and assignees are filtered to people with access in the record's region (§4.10).

### Pinned decisions

| Pin | How it is met |
|---|---|
| P1 | Everything runs on H2. Only the partial unique index is gated to Postgres. `region_id` is added nullable, backfilled, then enforced. `@ColumnDefault` is used only for a scalar flag. `widen` is applied to the new enum columns. |
| P2 | The move emits events only through a participant that A5 implements, using its MANDATORY publisher. |
| P3 | `AppUserDetailsService.principalFor(owner)` loads the grants eagerly. `ViewScopeHolder.with(...)` sets the scope on consumer threads. |
| P4 | `region_id` is denormalised on the five tables. The move is an explicit, cascading operation. |
| P5 | One `HandlerInterceptor` fills a thread-bound `ViewScope`. Region and as-of never enter `lockedFilters`. Domain writes reject `asOf`. |
| P6 | `Clock` is used for `created_at` and `granted_at`. |
| P7 | With one DEFAULT region and APPROVE/ALL grants, every tier reduces to today's. Fixtures grant DEFAULT. |

---

## 3. Vocabulary and invariants

- **Grant:** `(user, region, access_level, record_scope)`, unique on `(user, region)`.
  - `AccessLevel` is ordered: `READ < MANAGE < APPROVE`.
  - `RecordScope` is `ALL` or `BOOK`.
  - "No grant row" means nothing in that region.
- **Effective right:**
  ```
  effective(p, R) =
      role ∋ p
    ∧ ( global(p)
        ∨ ( grant(R) exists
            ∧ grant(R).level ≥ requiredLevel(p)
            ∧ (p ≠ SCOPE_OVERRIDE ∨ grant(R).scope = ALL) ) )
  ```
  - A customer login is region-bound implicitly: `effective(p, R) = role ∋ p ∧ R = customer.region`.
  - Customer logins have no grant rows.
- **Invariant I1:** `child.region_id = customer.region_id` for invoices, payments, promises and disputes. Tasks and pending changes follow the same rule.
- **Invariant I2:** grants only ever *narrow* the role. A missing region check can never exceed `role ∧ held somewhere`.
- **Invariant I3:** the region selector (`regionIds`) narrows **sets** only. By-id reads and single-record writes are authorised against the record's own region, so notification, task and audit links keep working.

---

## 4. Backend design

### 4.1 New package `com.geneinvoice.region`

It contains:
- `Region`, `RegionRepository`, `RegionService`, `RegionController`, `RegionDtos`
- `UserRegionGrant`, `UserRegionGrantRepository`, `RegionGrantService`, `RegionGrantController`
- `AccessLevel`, `RecordScope`, `GrantRow`, `RegionRights`, `RegionAccessQueries`
- `RegionSchemaUpgrade`
- `CustomerRegionMoveService`, `RegionMoveParticipant`, `RegionMoveDtos`

The shared scope infrastructure lives in `com.geneinvoice.common.scope`: `ViewScope`, `ViewScopeHolder`, `ViewScopeInterceptor`, `ViewScopeConfig`.

`B/common/SchemaSupport.java` becomes public and holds `product(Connection)`, `isNullable(...)` and `widen(...)`, moved from `InvoiceSchemaUpgrade` and `EmailSchemaUpgrade`. Those two classes delegate to it.

If no `Clock` bean exists yet, slice R1 adds `B/common/ClockConfig`:
```java
@Bean @ConditionalOnMissingBean Clock clock() { return Clock.systemUTC(); }
```

### 4.2 Privilege catalogue (`B/privilege/Privileges.java`)

```java
public static final String REGION_VIEW = "REGION_VIEW", REGION_MANAGE = "REGION_MANAGE";
// ALL += REGION_VIEW, REGION_MANAGE   (append at end; pickByNames silently drops unknown names)
public static final Set<String> GLOBAL = Set.of(USER_VIEW, USER_MANAGE, ROLE_VIEW, ROLE_MANAGE,
        PRODUCT_VIEW, PRODUCT_MANAGE, NOTIFICATION_VIEW, EXPORT_DATA, REGION_VIEW, REGION_MANAGE);
public static final Map<String, AccessLevel> REGION_LEVEL = Map.ofEntries(
    // READ
    entry(CUSTOMER_VIEW, READ), entry(INVOICE_VIEW, READ), entry(PAYMENT_VIEW, READ), entry(PROMISE_VIEW, READ),
    entry(DISPUTE_VIEW, READ), entry(AUDIT_VIEW, READ), entry(POC_VIEW, READ), entry(EMAIL_VIEW, READ),
    entry(DOCUMENT_VIEW, READ), entry(SCOPE_OVERRIDE, READ),
    entry(POC_ASSIGNABLE_SALES, READ), entry(POC_ASSIGNABLE_SUCCESS, READ), entry(POC_ASSIGNABLE_COLLECTION, READ),
    // MANAGE
    entry(CUSTOMER_MANAGE, MANAGE), entry(INVOICE_MANAGE, MANAGE), entry(PAYMENT_MANAGE, MANAGE),
    entry(PROMISE_MANAGE, MANAGE), entry(PROMISE_OVERRIDE, MANAGE), entry(DISPUTE_MANAGE, MANAGE),
    entry(DISPUTE_CREATE, MANAGE), entry(POC_ASSIGN, MANAGE), entry(EMAIL_SEND, MANAGE), entry(DOCUMENT_MANAGE, MANAGE));
/** null ⇒ global. A name in neither map (legacy DB row, forgotten classification) ⇒ MANAGE (fail-safe, needs a grant). */
public static AccessLevel requiredLevel(String p) { return GLOBAL.contains(p) ? null : REGION_LEVEL.getOrDefault(p, AccessLevel.MANAGE); }
public static boolean isRegional(String p) { return !GLOBAL.contains(p); }
```

- There are 33 names in total: 10 global, 13 READ, 10 MANAGE.
- Other designs classify their privileges here:
  - `TASK_VIEW`: READ
  - `TASK_MANAGE`: MANAGE
  - `CHANGE_APPROVE`: APPROVE
  - `RULE_*`: GLOBAL
- `ADMIN` receives REGION_VIEW and REGION_MANAGE through `upsertRole`. No other seeded role receives them.
- Frontend: add `regionView` and `regionManage` to `F/shared/models/privileges.dart`.

### 4.3 Data model

**`regions`** (new table, so NOT NULL is safe)

```java
@Entity @Table(name = "regions", uniqueConstraints = @UniqueConstraint(name = "uk_region_code", columnNames = "code"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Region {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, length = 20)  private String code;          // ^[A-Z0-9_-]{2,20}$, immutable after create
  @Column(nullable = false, length = 120) private String name;          // unique ignoring case (service check)
  @Column(nullable = false) private boolean active;                     // services set explicitly (no @Builder.Default)
  @Column(name = "is_default", nullable = false) private boolean defaultRegion;
  @Version @Column(name = "version") private Long version;
  @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;  // from Clock
  @Column(name = "updated_at") private Instant updatedAt;                                       // from Clock
}
```

- **Postgres only:** `create unique index if not exists uk_regions_default on regions (is_default) where is_default`.
- **H2:** the invariant is kept by `RegionService` bulk updates.

**`user_region_grants`**

```java
@Entity @Table(name = "user_region_grants",
  uniqueConstraints = @UniqueConstraint(name = "uk_user_region", columnNames = {"user_id", "region_id"}),
  indexes = @Index(name = "idx_urg_region", columnList = "region_id,access_level"))
public class UserRegionGrant {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
  @OnDelete(action = OnDeleteAction.CASCADE) private User user;          // hard user delete takes its grants
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "region_id") private Region region;
  @Enumerated(EnumType.STRING) @Column(name = "access_level", nullable = false, length = 10) private AccessLevel accessLevel;
  @Enumerated(EnumType.STRING) @Column(name = "record_scope", nullable = false, length = 10) private RecordScope recordScope;
  @Column(name = "granted_by_user_id") private Long grantedByUserId;
  @Column(name = "granted_at", nullable = false) private Instant grantedAt;
}
public enum AccessLevel { READ, MANAGE, APPROVE;
  public boolean permits(AccessLevel required) { return compareTo(required) >= 0; }
  public static List<AccessLevel> atLeast(AccessLevel l) { return Arrays.stream(values()).filter(v -> v.permits(l)).toList(); } }
public enum RecordScope { ALL, BOOK }
public record GrantRow(Long regionId, AccessLevel level, RecordScope scope) {}   // top-level: JPQL constructor target
```

The grant is **not** mapped on `User`. There is no collection, which avoids detached-principal and N+1 problems (C-4).

**`region_id` on the five core tables**

`Customer`, `Invoice`, `Payment`, `PaymentPromise` and `Dispute` each gain:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "region_id", updatable = false)   // insert-only; only CustomerRegionMoveService's native UPDATEs change it
private Region region;
```

- Add `@Index(name = "idx_<table>_region", columnList = "region_id")` to each `@Table`.
- The column is nullable in the mapping (R10). `RegionSchemaUpgrade` enforces NOT NULL.
- There is no `@ColumnDefault` on it (C-5).
- Safety net on `Invoice`, `Payment` and `PaymentPromise`, merged into the existing `@PrePersist` method where there is one:
  ```java
  if (region == null && customer != null) region = customer.getRegion();
  ```
  `Dispute` has no association, so its service always sets the region.

**`users.region_backfill_pending`** (scalar flag, C-5)

```java
@ColumnDefault("true") @Column(name = "region_backfill_pending", nullable = false)
private boolean regionBackfillPending;   // false for every row Hibernate writes (builder and no-args); true only for rows that predate B1
```

**`audit_logs.region_id`**

- `@Column(name = "region_id") private Long regionId;` is nullable with no FK, so the audit insert can never fail.
- Add an overload: `AuditService.record(entityType, entityId, action, before, after, userId, disputeId, reason, Long regionId)`. The existing 8-argument form delegates with `null`.
- In B1, only `CUSTOMER_DELETED` and `CUSTOMER_REGION_CHANGED` pass a region.

**Not added:** region columns on documents, emails, invoice_items, payment_allocations, customer_pocs, products, users or notifications.

### 4.4 Migration and seeding

`RegionSchemaUpgrade implements InitializingBean`, package `region`.

- **Constructor:** `(DataSource, AuditService, Clock, EntityManagerFactory schemaUpToDate)`. It runs after `ddl-auto` and before `DataSeeder`.
- **Step handling:** each step runs in its own `try`, calls `commit()` if autocommit is off, and logs a WARN and continues on `SQLException`. A failed step is retried on the next boot.

**Steps:**

1. **Default region.**
   - `select id from regions where is_default = true order by id`.
   - If there is none, run:
     ```sql
     insert into regions (code, name, active, is_default, version, created_at)
       values ('DEFAULT', 'Default region', true, true, 0, ?)
     ```
     using `Timestamp.from(clock.instant())`.
   - On `SQLException` (a concurrent instance won `uk_region_code`), re-select `where code = 'DEFAULT'`, then `update regions set is_default = true where code = 'DEFAULT' and not exists (select 1 from regions where is_default = true)`.
   - On Postgres, create `uk_regions_default`.
2. **Customers.** Find `min(id)` and `max(id)` where `region_id is null`. In windows of 1000:
   ```sql
   update customers set region_id = ? where region_id is null and id between ? and ?
   ```
3. **Children** (`invoices`, `payments`, `payment_promises`, `disputes`), in the same windows:
   ```sql
   update <t> set region_id = (select c.region_id from customers c where c.id = <t>.customer_id)
    where region_id is null and id between ? and ?
   ```
   Then deal with orphaned disputes (no FK to customers):
   ```sql
   update disputes set region_id = ? where region_id is null
   ```
4. **Enforce NOT NULL.** For each of the five tables, if `SchemaSupport.isNullable(conn, t, "region_id")`:
   - If `select count(*) from <t> where region_id is null` is greater than 0, log WARN and skip.
   - Otherwise run `alter table <t> alter column region_id set not null`. This SQL is identical on H2 and Postgres (verified on H2).
5. **Legacy grants, once per user:**
   ```sql
   insert into user_region_grants (user_id, region_id, access_level, record_scope, granted_at)
     select u.id, ?, 'APPROVE', 'ALL', ? from users u
      where u.region_backfill_pending = true and u.customer_id is null
        and not exists (select 1 from user_region_grants g where g.user_id = u.id and g.region_id = ?);
   update users set region_backfill_pending = false where region_backfill_pending = true;
   ```
   Commit after both statements. APPROVE/ALL means "the role alone decides", which is exactly today's behaviour.
6. `SchemaSupport.widen(conn, "user_region_grants", "access_level", AccessLevel.class)`, and the same for `record_scope` with `RecordScope.class` (R9).
7. If steps 2 to 5 changed any rows, write an audit row:
   ```java
   auditService.record("REGION", 0L, "REGIONS_BACKFILLED", null,
       Map.of("customers", n1, "children", n2, "grants", n3), null, null,
       "Assigned existing records and users to the default region")
   ```

**`DataSeeder`:**
- Inject `RegionService`.
- When it creates `admin` or `cashier`, also call `regionGrantService.ensure(user, regionService.defaultRegion(), APPROVE, ALL)`.
- `regionService.defaultRegion()` finds the default region, or creates DEFAULT if the upgrade failed.
- No sample regions are seeded.

### 4.5 Principal, `RegionRights` and `CurrentUser`

```java
public final class RegionRights {                         // immutable, plain data (safe on detached principal)
  public static RegionRights none();
  public static RegionRights forStaff(Set<String> rolePrivileges, Collection<GrantRow> grants);
  public static RegionRights forCustomer(Set<String> rolePrivileges, Long customerRegionId);
  public boolean has(String p, Long regionId);     // formula in §3; regionId == null ⇒ false for regional p
  public boolean anywhere(String p);               // global ⇒ role ∋ p; else ∃ granted r: has(p, r)
  public Set<Long> regionsWith(String p);          // regional p only; IllegalArgumentException for GLOBAL
  public Set<Long> grantedRegions();               // staff: grant keys; customer: {own}
  public boolean holdsAnyIn(Long regionId);
  public Optional<GrantRow> grantIn(Long regionId);
  public Set<String> effectiveAnywhere();          // flat authorities / /me.privileges
  public Set<String> effectiveIn(Long regionId);   // /me.regions[].privileges
  public boolean isCustomer();
}
```

**`AppUserDetails`:**
- The constructor becomes `(User, Collection<? extends GrantedAuthority>, RegionRights)`, with a getter `getRights()`.
- **The 2-argument constructor is deleted**, so the compiler lists every construction site.

**`AppUserDetailsService`** gains the dependencies `UserRegionGrantRepository` and `CustomerRepository`:

```java
@Override @Transactional(readOnly = true)
public UserDetails loadUserByUsername(String username) { return principalFor(userRepository.findByUsernameIgnoreCase(username).orElseThrow(...)); }

@Transactional(readOnly = true)
public AppUserDetails principalFor(User user) {      // also: IntegrationTestBase.as/actAs, A5 consumer (P3)
  Set<String> rolePrivs = user.getRole() == null ? Set.of() : user.getRole().getPrivileges().stream().map(Privilege::getName).collect(toSet());
  RegionRights rights = user.getCustomerId() != null
      ? RegionRights.forCustomer(rolePrivs, customerRepository.regionIdOf(user.getCustomerId()).orElse(null))
      : RegionRights.forStaff(rolePrivs, grantRepository.rowsFor(user.getId()));   // exactly one query
  return new AppUserDetails(user, buildAuthorities(user, rights), rights);
}
public static List<GrantedAuthority> buildAuthorities(User user, RegionRights rights);
// "ROLE_" + role name + rights.effectiveAnywhere(); List.of() when role is null. The 1-arg overload is deleted.
```

`CustomerRepository` adds:
```java
@Query("select c.region.id from Customer c where c.id = :id") Optional<Long> regionIdOf(Long id);
```

`JwtAuthFilter` is unchanged. Grants are read once per request, so a grant change applies on the user's next request.

**`CurrentUser`** keeps `has()` unchanged; it now reads the effective union. Additions:

```java
public RegionRights rights();                                 // principal's, or RegionRights.none() (fails closed, C-8)
public boolean hasIn(String privilege, Long regionId);
public void requireIn(String privilege, Long regionId);       // AccessDeniedException("Not allowed") → 403
public void requireReachable(Long regionId, String notFound); // !rights().holdsAnyIn(r) → NotFoundException → 404
public boolean canAssignPocIn(Long regionId);                 // !isCustomer() && hasIn(POC_ASSIGN, r)
public boolean canAssignPocAnywhere();                        // replaces canAssignPoc(UserRepository); delete the old method
```

**Why READ-level checks stay unchanged (the key simplification).** Take a record in region R that the caller can see. Visibility requires a grant of at least READ in R, so for any READ-level privilege `p` other than SCOPE_OVERRIDE, `effective(p, R) = role ∋ p`. It follows that these checks are already correct and are left untouched:
- `@PreAuthorize(X_VIEW)`
- `ScopeResolver.canSeePoc()` and its 14 callers
- `AuditController.ensureCallerCanSee`'s privilege check
- `DocumentTargets.requireVisible`
- `EmailTargets.requireTypeAccess`
- `TableSchemaController.mayRead`

Only MANAGE-level and APPROVE-level checks need the record's region.

**`RegionAccessQueries`** (`@Component`) answers questions about *other* users, from the database:

```java
public boolean holds(Long userId, String privilege, Long regionId);   // GLOBAL → userRepository.hasPrivilege; SCOPE_OVERRIDE → IllegalArgumentException
public Set<Long> withAccess(Collection<Long> userIds, Long regionId); // grant ≥ READ in region; empty input → empty
public List<User> holdersIn(String privilege, Long regionId);         // active internal users; B2 approvers, notifications
```

These are backed by `UserRegionGrantRepository` (§4.11).

### 4.6 ViewScope, the single reader of `regionIds` and `asOf` (P5)

```java
package com.geneinvoice.common.scope;
public record ViewScope(Set<Long> regionIds /* null = all mine */, LocalDate asOf /* null = live */) {
  public static final ViewScope LIVE_ALL = new ViewScope(null, null);
  public Set<Long> narrow(Set<Long> mine) { /* regionIds == null ? mine : unmodifiable intersection */ }
  public boolean historical() { return asOf != null; }
}
public final class ViewScopeHolder {                 // ThreadLocal so the A5 consumer can set it (a @RequestScope bean would throw there)
  public static ViewScope current();                 // LIVE_ALL when unset
  public static void set(ViewScope s); public static void clear();
  public static <T> T with(ViewScope s, Supplier<T> body);   // saves previous, restores in finally
  public static void requireLive();                  // historical() → BadRequestException("Viewing as of " + asOf + " is read-only")
}
@Component public class ViewScopeInterceptor implements HandlerInterceptor {
  // preHandle: clear(); parse; set(); return true.   afterCompletion: clear().
}
@Configuration public class ViewScopeConfig implements WebMvcConfigurer {
  // registry.addInterceptor(viewScopeInterceptor).addPathPatterns("/api/**")
}
```

**Parsing:**
- `regionIds` is taken from `getParameterValues("regionIds")`. It may be repeated, comma-separated, or both.
  - Blank tokens are skipped. No tokens means `null`.
  - A token that is not a positive long throws `BadRequestException("regionIds must be a comma-separated list of region ids")`.
- `asOf` is parsed as ISO `yyyy-MM-dd`. A malformed value throws `BadRequestException("asOf must be a date in the form yyyy-MM-dd")`. The as-of semantics belong to B3.
- The exception comes from an interceptor, not a filter, so `GlobalExceptionHandler` renders it as 400.

**Semantics:**
1. **Asked ∩ granted.** Asking for a region you hold nothing in contributes nothing. The result is an empty 200, never an error, and the echo shows `regionIds: []`.
2. The scope **narrows sets only** (Invariant I3).
3. Customer logins ignore it.
4. Domain writes call `ViewScopeHolder.requireLive()` (§4.9). Other writes (mark-read, Gmail, login) ignore `asOf`.
5. Nothing is ever put into `lockedFilters` (R14).

### 4.7 `ScopeResolver` rewrite (`B/poc/ScopeResolver.java`)

Dependencies: `CurrentUser` and `TableQueryExecutor`. `UserRepository` is no longer needed.

```java
public enum Access { VIEW, MANAGE }
public enum Reach { SELECTED /* sets: narrowed by ViewScope */, GRANTED /* by-id: all granted regions */ }
public enum Tier { FULL, BOOK, NONE }
public enum Kind {   // entity, schema, manage privilege, book, locked column
  CUSTOMER(Customer.class,       TableSchemas.CUSTOMERS, CUSTOMER_MANAGE, Book.ANY_POC,    "myBook"),
  INVOICE (Invoice.class,        TableSchemas.INVOICES,  INVOICE_MANAGE,  Book.SALES,      "salesPocUserId"),
  PAYMENT (Payment.class,        TableSchemas.PAYMENTS,  PAYMENT_MANAGE,  Book.COLLECTION, "collectionPocUserId"),
  PROMISE (PaymentPromise.class, TableSchemas.PROMISES,  PROMISE_MANAGE,  Book.COLLECTION, "collectionPocUserId"),
  DISPUTE (Dispute.class,        TableSchemas.DISPUTES,  DISPUTE_MANAGE,  Book.NONE,       null);
}   // A6 adds TASK.
public record RegionCoverage(Set<Long> full, Set<Long> book, boolean own) {
  public static final RegionCoverage OWN = new RegionCoverage(Set.of(), Set.of(), true);
  public Set<Long> answered(); public boolean mixed() { return !full.isEmpty() && !book.isEmpty(); }
}
public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters, RegionCoverage coverage) {
  public List<Long> regionIds();       // sorted answered()
  public List<Long> bookRegionIds();   // sorted book()
}

public Scope forCustomers() { return scope(Kind.CUSTOMER, Access.VIEW, Reach.SELECTED); }  // likewise forInvoices,
public Scope forInvoices()  ...  forPayments() ... forPromises() ... forDisputes()          // signatures unchanged
public Scope scope(Kind kind, Access access, Reach reach, String... alsoRequired);
public Tier tier(Kind kind, Access access, Long regionId, String... alsoRequired);          // in memory, no SQL
public void requireVisible(Kind kind, Long id, Long regionId, String notFoundMessage);
public boolean canSeePoc();                    // !customer && rights.anywhere(POC_VIEW)       (in memory)
public boolean isAssignableAs(PocType t);      // !customer && rights.anywhere(t.assignabilityPrivilege())
public boolean isAssignableIn(PocType t, Long regionId);
public boolean canSeeEverything();             // for /my-scope.clearable: !customer && full≠∅ && book=∅ on forInvoices()
```

**Tier for one region `r` (staff):**

```
gate   = access == VIEW ? rights.holdsAnyIn(r)            // VIEW gate = "any grant": X_VIEW itself is enforced by @PreAuthorize (F2 fix)
                        : rights.has(kind.manage, r)
if !gate or any x in alsoRequired with !rights.has(x, r)  → NONE
if kind.book == NONE                                      → FULL      (disputes: no book, as today)
if rights.has(SCOPE_OVERRIDE, r)                          → FULL      (needs grant.scope == ALL)
if kind.book == ANY_POC ? any POC_ASSIGNABLE_* effective in r
                        : rights.has(kind.book.type.assignabilityPrivilege(), r) → BOOK
else                                                      → NONE
```

For customer logins, `tier` returns FULL when `regionId` is the customer's own region and NONE otherwise.

**`scope(kind, access, reach, extras)`:**

1. **Customer login:** keep today's predicates unchanged (`customer.id = own`, `id = own`, `customerId = own`), `lockedFilters = []`, `coverage = OWN`. Ignore `ViewScope`.
2. **Staff:**
   - `granted` = the regions where the gate and extras hold.
   - `asked` = `reach == SELECTED ? ViewScopeHolder.current().narrow(granted) : granted`.
   - Split `asked` by tier into `full` and `book`.
3. **If `full ∪ book = ∅`**, return the predicate `cb.disjunction()` and `coverage (∅, ∅)`. `lockedFilters` is:
   - `[lockedColumn + ":eq:" + me]` when *no region in `granted`* has a non-NONE tier. This is today's `nothing()` with its chip.
   - `[]` when the set is empty only because of narrowing.
4. **Otherwise**, the predicate is:
   ```java
   (root, q, cb) -> {
       Path<Long> r = root.get("region").get("id");
       Predicate f = full.isEmpty() ? null : r.in(full);
       Predicate b = book.isEmpty() ? null : cb.and(r.in(book), bookPredicate(kind, root, q, cb, me));
       return f == null ? b : b == null ? f : cb.or(f, b);   // never "in ()"
   }
   ```
   The book predicates are:
   - invoices: `salesPoc.id = me`
   - payments and promises: `collectionPoc.id = me`
   - customers: the existing `customerHasPocSeat OR customerHasInvoiceOwnedBy`
5. `lockedFilters = full.isEmpty() && kind.lockedColumn != null ? [lockedColumn:eq:me] : []`. The chip therefore appears only for a **pure** book view. A mixed view shows no chip; the UI reads `bookRegionIds` instead.

**`requireVisible(kind, id, regionId, nf)`**, the by-id check (F4):
- Customer login: return. Callers already enforce the own-customer check.
- Otherwise switch on `tier(kind, VIEW, regionId)`:
  - `FULL`: return. No SQL.
  - `BOOK`: `if (!queryExecutor.inScope(kind.type, kind.schema, id, scope(kind, VIEW, GRANTED).predicates())) throw new NotFoundException(nf)`.
  - `NONE`: throw `NotFoundException(nf)` (404, AUTH-08).
- Reading `entity.getRegion().getId()` on a LAZY proxy does not initialise it.

Single-region check: DEFAULT with APPROVE/ALL gives:
- `granted = {D}`
- FULL when the role has SCOPE_OVERRIDE, BOOK when it has only the POC privilege, NONE otherwise
- lockedFilters as today
- `EmailContextTest`: admin is FULL, so there is no `customer_pocs` subquery and the count stays 1

### 4.8 Reads

#### 4.8.1 By-id reads

Replace each `requireInBook(id)` with `scopeResolver.requireVisible(Kind.X, id, entity.getRegion().getId(), "<X> not found")`. The existing customer-login checks stay as they are.

| Site | Change |
|---|---|
| `CustomerService.get` (129-145) | As above. Keep the existing 403 for another customer's id (X1). |
| `InvoiceService.get` (260-286) | As above. |
| `PaymentService.get` (272-291) | As above. |
| `PaymentPromiseService.get` (505-520) | As above. |
| `DisputeService.get` (143-152) | Staff now get `requireVisible(DISPUTE, …)`. The customer-login 403 is kept (X1). |
| `PaymentController.credit` (93-108) | Replace the inline `inScope(forCustomers())` with `customerService.get(customerId)`. |
| `InvoiceService.requireCustomerInBook` (288-293, preview) | Becomes `requireVisible(CUSTOMER, id, c.region, "Customer not found")`, then `currentUser.requireIn(INVOICE_MANAGE, c.region)`. |

`AuditController.readOrGone` (118-124): when the scoped read throws NotFound and the record no longer exists, and the entity type is `CUSTOMER`:
- Look up `auditLogRepository.findLatestRegionId("CUSTOMER", id)`:
  ```java
  @Query("select a.regionId from AuditLog a where a.entityType = :t and a.entityId = :id and a.regionId is not null order by a.id desc")
  ```
  with `setMaxResults(1)`. If there is none, use the default region.
- If `!currentUser.rights().holdsAnyIn(region)`, rethrow NotFound.

#### 4.8.2 Sets (lists, tiles, export, email bulk)

All existing `forX()` callers pick up `SELECTED` automatically:
- `page`, `idsMatching` (export), `allMatching`, `tiles`
- `DisputeController` list and export
- `EmailTargets.bulkIds`

Changes:
- `EmailTargets.bulkIds` uses `scope(kind, VIEW, SELECTED, EMAIL_SEND)` for the five core kinds.
- **`PageResponse`** gains `List<Long> regionIds, List<Long> bookRegionIds`:
  - Add `of(content, query, total, ScopeResolver.Scope scope)`, which fills `lockedFilters`, `regionIds` and `bookRegionIds`.
  - Keep `of(content, query, total, lockedFilters)` for unscoped lists (users, roles, products, notifications, inbox, regions), with both lists `[]`.
  - `map()` copies both.
  - Switch the five `page()` methods and `DisputeController.list` to the Scope overload.
- **Fetch `region`:** add `"region"` to the `fetchAssociations` list in the five `page()`/`allMatching()` calls, so DTO mapping does no per-row region loads.

#### 4.8.3 Bulk writes

Each `*Controller.resolveIds` gains an `Access` argument. The services get `idsMatching(TableQuery, int limit, Access access, String... extras)`, which uses `scope(kind, access, SELECTED, extras)`.

| Action | Scope used for id resolution |
|---|---|
| Invoice CANCEL | `MANAGE` |
| REASSIGN_SALES_POC / REASSIGN_COLLECTION_POC (invoice, payment, promise) | `MANAGE`, extra `POC_ASSIGN` |
| Promise CANCEL | `MANAGE` |
| Customer ADD_POC | `MANAGE`, extra `POC_ASSIGN` |
| Every `/export` | `VIEW` |

- A selected row that is visible but not writable drops out of the permitted set and is reported as skipped with `BulkExecutor.NOT_REACHABLE`.
- The per-row service call re-checks.
- The coarse controller checks become `currentUser.canAssignPocAnywhere()`:
  - `InvoiceController:117`, `PaymentController:123`, `PaymentPromiseController:134`: keep today's 400.
  - `CustomerController:133`: keep today's 403.

#### 4.8.4 Dashboard (`B/dashboard/DashboardService.java`)

**D1-D3** (`billedByMonth`, `outstandingByAge`, `topOutstanding`) are unchanged apart from coverage. They already apply `forInvoices()` predicates.

**Collected** (`collectedByMonth`, `topPaying`) replaces `paidAgainstBookOnly()`:

```
if customer login → today's payment path (unchanged)
inv = forInvoices(); pay = forPayments();
partA (payment sum)  = pay.coverage().full()                                   // override regions
partB (allocations)  = pay.coverage().book() ∪ inv.coverage().book()           // no-override regions
A: Root<Payment>  where pay.predicates ∧ payment.region.id ∈ partA ∧ status = ACTIVE            (skip if partA = ∅)
B: Root<PaymentAllocation> ⋈ payment ⋈ invoice
      where payment.status = ACTIVE ∧ invoice.region.id ∈ partB
        ∧ (invoice.id ∈ idsInScope(Invoice, inv) ∨ payment.id ∈ idsInScope(Payment, pay))       (skip if partB = ∅)
result = A + B
```

- Month points are added month by month.
- For `topPaying`, merge per customer. A customer is in exactly one region, so the two parts are disjoint. Then sort descending and apply the limit.
- Invariant I1 guarantees there is no double counting.
- Single-region results are identical to today, for every role shape, including roles with PAYMENT_VIEW but no INVOICE_VIEW.

**Coverage:**
- `Coverage` becomes `{ ALL, BOOK, OWN, MIXED }`.
- `coverage(scope)`:
  - OWN for a customer login
  - BOOK if `lockedFilters` is non-empty
  - MIXED if `coverage.mixed()`
  - ALL otherwise
- Collected coverage:
  - OWN for a customer login
  - A only → ALL
  - B only → BOOK
  - both → MIXED
  - neither → BOOK if either scope has lockedFilters, else ALL

**Response records:** every response record gains a trailing component `DashboardDtos.ScopeInfo scope(List<Long> regionIds, List<Long> bookRegionIds)`. For collected, that is `(A ∪ B, B)`. B3 adds `asOf` to `ScopeInfo`.

#### 4.8.5 Other reads

- **Unscoped customer figures** (`CustomerService.outstandingSubquery`, `InvoiceRepository.sumOutstanding/OverdueByCustomer`, `TableSchemas.customerOutstanding`): unchanged. By invariant I1 they never cross regions.
- **Unchanged:**
  - `AuditTimelineService includeRelated`: children share the parent's region.
  - `EmailService.get` party access (by design).
  - Inbox and notifications (per person).
- **Delete these dead unscoped methods** (R17):
  - `PaymentService.list`
  - `PaymentPromiseService.listForCustomer` and `listForInvoice`
  - `DisputeService.list`
  - `PocService.callerIfAssignable` and `primaryFor`

### 4.9 Writes: the complete inventory (R4)

Every write follows the same pattern:
1. Load the row, with its existing lock.
2. Check visibility → 404.
3. `ViewScopeHolder.requireLive()` → 400.
4. `currentUser.requireIn(<privilege>, region)` → 403.

Creates use `currentUser.requireReachable(customerRegion, "Customer not found")`, then `requireIn`. Status codes are never 401 (G26).

| Site | Today | B1 |
|---|---|---|
| `CustomerService.create` (204-248) | none | Region: `req.regionId()`. If null: the single active region where CUSTOMER_MANAGE is effective; else, if narrowing the candidates by `ViewScope` leaves one, that one; else 400 `fieldErrors.regionId = "Choose the region this customer belongs to"`. Then `requireReachable`, `requireIn(CUSTOMER_MANAGE)`, and `regionService.requireActive(r)` (400 "Region X is inactive"). Builder sets `.region(regionRef)`. |
| `CustomerService.update` / `delete` | `requireInBook` | `requireVisible` + `requireLive` + `requireIn(CUSTOMER_MANAGE)`. `delete` passes `c.getRegion().getId()` to the `CUSTOMER_DELETED` audit overload. |
| `InvoiceService.create` (59-99) | none | `customerRepository.findByIdForUpdate` (F1; lock order customer → sequence → invoice), then `requireLive`, `requireReachable`, `requireIn(INVOICE_MANAGE, c.region)`. The book is deliberately not checked, as today. `requireAssignable(salesPoc, SALES, region)`. Builder sets `.region(customer.getRegion())`. |
| `InvoiceService.update` / `cancel` | `requireInBook` | `requireVisible` + `requireLive` + `requireIn(INVOICE_MANAGE)`. Line 134 becomes `canAssignPocIn(region)` (keep the 400 message); line 137 becomes `requireAssignable(…, region)`. |
| `PaymentService.record` (54-109) | none | After `lockCustomer` (60): `requireLive`, `requireReachable`, `requireIn(PAYMENT_MANAGE)`. `requireAssignable(…, COLLECTION, region)`. Builder sets `.region(...)`. |
| `PaymentService.update` | `requireInBook` | `requireVisible` + `requireLive` + `requireIn(PAYMENT_MANAGE)`. Line 121 becomes `canAssignPocIn`; line 124 becomes `requireAssignable(…, region)`. |
| `PaymentPromiseService.create` (71-103) | none | `customerRepository.findByIdForUpdate` (F1), `requireLive`, `requireReachable`, `requireIn(PROMISE_MANAGE)`. `resolveCollectionPoc(requested, customerId, regionId)`. Builder sets `.region(...)`. |
| Promise `update`, `cancel`, `reassignCollectionPoc` | scoped `get` | New `getForWrite(id, privilege)` = `get(id)` + `requireLive` + `requireIn(PROMISE_MANAGE, p.region)`. Line 124 becomes `canAssignPocIn`; line 127 becomes `requireAssignable(…, region)`. |
| Promise `override` / `clearOverride` | global | `getForWrite(id, PROMISE_OVERRIDE)` |
| `PaymentPromiseService.recomputeAll` (458-483) | global | Regions = `ViewScope.narrow(rights.regionsWith(PROMISE_OVERRIDE))`. New repository query `findCustomerIdsWithLivePromisesInRegions(Collection<Long>)`. An empty set is a no-op. |
| `DisputeService.open` (52-89) | customer login only | `customerRepository.findByIdForUpdate(callerCustomer)` (F1). Builder sets `.region(customer.getRegion())`. `notifyAdmins(..., regionId)` (§4.10). |
| `DisputeService.approve` / `deny` (91-133) | global | Order changed so existence does not leak: `findById` (404) → `requireVisible(DISPUTE)` → `requireLive` → `requireIn(DISPUTE_MANAGE, d.region)` → `mustBePending` (lock + 400 "already resolved"). The internal apply methods (`cancelWithRefund`, `replaceItems`, `voidPayment`, `updateAmount`) stay unscoped behind this gate. |
| `DocumentTargets.requireManageable` (54-60), `canManage` (62-66) | global | `Target` gains `Long regionId`, filled in `describe` from the loaded entity. Staff: after `describe`, `requireLive`, `requireIn(DOCUMENT_MANAGE, t.regionId)` and `requireIn(MANAGE_PRIVILEGE(type), t.regionId)`. Customers: unchanged. `canManage(type)` becomes `canManage(type, regionId)`. |
| `DocumentService.toDto` (216-227) | global flags | Becomes `toDto(Document d, Target t)`: `manage = staff ∧ hasIn(DOCUMENT_MANAGE, t.regionId) ∧ hasIn(MANAGE(type), t.regionId) ∧ !historical`; `canDelete = manage ∨ (uploader ∧ hasIn(DOCUMENT_MANAGE, t.regionId) ∧ !historical)`. A list is computed once, because all its documents share one target. |
| `EmailService.send` / `preview` / `bulk` (per row) / `retry` | global EMAIL_SEND | `EmailTargets.Target` gains `Long regionId` (null for PRODUCT, USER and ROLE). New `targets.loadForSend(type, id)` = `load` + (if `regionId != null`) `requireIn(EMAIL_SEND, regionId)`. `send`, `bulk` and `retry` call `requireLive`. |
| `PocService.add` / `remove` / `setPrimary` | controller POC_ASSIGN | After `lockCustomer`: `requireLive` + `requireIn(POC_ASSIGN, customer.region)`. `add` calls `requireAssignable(userId, type, customer.region)`. |
| Region move | new | §4.12 |
| Users, roles, products, notifications, inbox, Gmail | global | unchanged |

**Contract for new writers (A6, B2):** read a region-bearing row's region from a customer row locked `FOR UPDATE` in the same transaction.

### 4.10 The POC book per region, and holders

**`PocService`:**

```java
public User requireAssignable(Long userId, PocType type, Long regionId);
// existing checks and messages first (null → 400, missing → 404, inactive, customer login, role lacks privilege → 400 as today),
// then !regionAccess.holds(userId, type.assignabilityPrivilege(), regionId) →
//   BadRequestException("User " + username + " is not assignable as " + type.label() + " in region " + code)
public List<User> assignable(PocType type, Collection<Long> regionIds, String query, int limit);   // empty regions → []
public Map<PocType, List<User>> activeHoldersByType(Long customerId);
// existing single customer_pocs read, then keep only users in regionAccess.withAccess(ids, regionOf(customerId))
public List<User> withRegionAccess(List<User> users, Long regionId);                                // one grants query
```

- The two-argument `requireAssignable` is deleted, so the compiler lists all eight callers (§4.9 and `CustomerController:149`).
- `defaultAssignee` and `activeHolders` inherit the filter.
- `EmailTargets.target(...)` applies `withRegionAccess` to record-level holders as well, using the target's `regionId`.
- Rule: a person with no access to the record's region is not a holder of any role on it. This keeps A3 and A4 assignees and recipients inside permissions.

**`PocController`:**
- `GET /api/pocs/assignable?type=SALES&q=&limit=25[&customerId=12 | &regionId=2]`:
  - `customerId`: the region comes from `customerService.get(id)` (404 if not visible).
  - `regionId`: must be in `rights.grantedRegions()`; otherwise return `[]`.
  - Neither: `ViewScope.narrow(rights.grantedRegions())`.
- `GET /api/pocs/types`: `callerIsAssignable = isAssignableAs(t)` (anywhere).
- `GET /api/pocs/my-scope`:
  ```json
  {"userId":7,"clearable":false,"sales":true,"success":false,"collection":true,
   "regions":[{"regionId":1,"code":"NORTH","clearable":true,"sales":true,"success":false,"collection":true},
              {"regionId":2,"code":"SOUTH","clearable":false,"sales":true,"success":false,"collection":true}]}
  ```
  - `regions` = narrowed granted regions.
  - Per-region `clearable` = override there; per-region type flags = assignable there.
  - Top-level `clearable = canSeeEverything()`; top-level type flags = assignable in any selected region.
- `InvoiceController.assignableCheck` (176-180) gains an optional `customerId`. With it, the answer is `isAssignableIn(SALES, customer.region)`.

**`NotificationService.notifyAdmins(type, title, message, link, Long regionId)`**, replacing the 4-argument form (one caller):
- Recipients are `findByRoleName("ADMIN")` filtered by `regionAccess.withAccess(ids, regionId)`.
- In a single region this is identical to today.

`GmailDisconnects.mayConnect` is **unchanged** and stays role-based. Consequence: a user whose grants no longer allow EMAIL_SEND anywhere keeps their Gmail connection, but every send is refused per region. This is documented; it is not a leak.

### 4.11 Region and grant administration

**`RegionService`** (`@Transactional` writes; audits under entity `"REGION"`):

```java
public Region defaultRegion();                     // query every call; never cache (R10)
public Region requireActive(Long id);              // 404 unknown, 400 inactive
public RegionDto create(RegionInput in);           // validates code/name; Clock timestamps; grants the creator APPROVE/ALL
public RegionDto update(Long id, RegionUpdate in); // version mismatch → OptimisticLockingFailureException (409)
public RegionDto makeDefault(Long id);             // must be active; bulk: clearDefaultExcept(id) then markDefault(id)
public void delete(Long id);                       // 400 if default or referenced by customers/disputes/grants; other FKs → 409
```

- Audit actions: `REGION_CREATED`, `REGION_UPDATED`, `REGION_DEFAULT_CHANGED`, `REGION_DELETED`.
- `AuditController.SUPPORTED` and `VIEW_PRIVILEGE` gain `REGION → REGION_VIEW`.

**`RegionGrantService`:**

```java
@Transactional public List<GrantDto> replace(Long userId, List<GrantInput> grants);
// userRepository.findByIdForUpdate (new @Lock PESSIMISTIC_WRITE) → refuse self (400 "You cannot change your own region access"),
// customer login (400 "Customer logins take their region from their customer") → validate (unknown or duplicate region → 400 fieldErrors.grants)
// → before = list → grantRepository.deleteAllOfUser(userId)   [@Modifying bulk JPQL, executes immediately: F7]
// → saveAll(new rows, grantedBy = me, grantedAt = clock) → audit "USER", userId, "USER_REGION_GRANTS_CHANGED", before, after
@Transactional public void ensure(User u, Region r, AccessLevel l, RecordScope s);   // seeder, region-create, fixtures
```

**`UserRegionGrantRepository`:**

```java
@Query("select new com.geneinvoice.region.GrantRow(g.region.id, g.accessLevel, g.recordScope) from UserRegionGrant g where g.user.id = :userId")
List<GrantRow> rowsFor(@Param("userId") Long userId);
@Query("select g from UserRegionGrant g join fetch g.region where g.user.id = :userId order by g.region.code")
List<UserRegionGrant> findWithRegionByUserId(Long userId);
@Query("select g from UserRegionGrant g join fetch g.user u where g.region.id = :regionId order by u.username")
List<UserRegionGrant> findWithUserByRegionId(Long regionId);
@Query("select distinct g.user.id from UserRegionGrant g where g.user.id in :userIds and g.region.id = :regionId and g.accessLevel in :levels")
List<Long> usersWithAccess(Collection<Long> userIds, Long regionId, Collection<AccessLevel> levels);
@Query("""select count(g) > 0 from UserRegionGrant g join g.user u join u.role r join r.privileges p
          where u.id = :userId and g.region.id = :regionId and p.name = :privilege and g.accessLevel in :levels""")
boolean hasPrivilegeInRegion(Long userId, String privilege, Long regionId, Collection<AccessLevel> levels);
@Query("""select distinct u from UserRegionGrant g join g.user u join u.role r join r.privileges p
          where g.region.id in :regionIds and g.accessLevel in :levels and u.active = true and u.customerId is null
            and p.name = :privilege and (:q is null or lower(u.username) like :q escape '\\'
                 or lower(coalesce(u.fullName, '')) like :q escape '\\' or lower(coalesce(u.email, '')) like :q escape '\\')
          order by u.fullName asc, u.username asc""")
List<User> findAssignableInRegions(String privilege, Collection<Long> regionIds, Collection<AccessLevel> levels, String q, Pageable page);
@Query("""select distinct u from UserRegionGrant g join g.user u join u.role r join r.privileges p
          where g.region.id = :regionId and g.accessLevel in :levels and p.name = :privilege and u.active = true and u.customerId is null""")
List<User> findHoldersInRegion(String privilege, Long regionId, Collection<AccessLevel> levels);
@Modifying(flushAutomatically = true) @Query("delete from UserRegionGrant g where g.user.id = :userId") int deleteAllOfUser(Long userId);
long countByRegionId(Long regionId);
```

Callers short-circuit empty collections, so no query ever has `in ()`.

**Endpoints:**

| Method, path | Gate | Request → response |
|---|---|---|
| `GET /api/regions` | `REGION_VIEW or USER_MANAGE` | `PageResponse<RegionDto>` via `TableSchemas.REGIONS` |
| `GET /api/regions/{id}` | `REGION_VIEW or USER_MANAGE` | `RegionDto` |
| `POST /api/regions` | REGION_MANAGE | `{"code":"SOUTH","name":"South","active":true}` → 200 `{"id":2,"code":"SOUTH","name":"South","active":true,"defaultRegion":false,"version":0,"createdAt":"2026-09-26T10:00:00Z"}`. 400 on a bad or duplicate code or a duplicate name. |
| `PUT /api/regions/{id}` | REGION_MANAGE | `{"name":"South & East","active":false,"version":0}` → `RegionDto`. 409 stale; 400 when deactivating the default; the code is immutable. |
| `POST /api/regions/{id}/make-default` | REGION_MANAGE | → `RegionDto`. 400 if inactive. |
| `DELETE /api/regions/{id}` | REGION_MANAGE | 204. 400 "Region is the default" or "Region still has customers, disputes or grants; deactivate it instead". |
| `GET /api/regions/{id}/grants` | `REGION_VIEW and USER_VIEW` | `[{"userId":7,"username":"rita","fullName":"Rita","roleName":"CASHIER","level":"MANAGE","scope":"ALL"}]` |
| `GET /api/users/{id}/region-grants` | USER_VIEW | `[{"regionId":2,"regionCode":"SOUTH","regionName":"South","level":"MANAGE","scope":"ALL","grantedAt":"…","grantedByUserId":1}]` |
| `PUT /api/users/{id}/region-grants` | USER_MANAGE | `{"grants":[{"regionId":1,"level":"READ","scope":"ALL"},{"regionId":2,"level":"MANAGE","scope":"BOOK"}]}` → the list above |
| `POST /api/users` | USER_MANAGE | Optional `"regionGrants":[{"regionId":…,"level":…,"scope":…}]`. **Absent** means DEFAULT/APPROVE/ALL (F8, P7); present means exactly that list (`[]` is allowed). |
| `GET /api/privileges` | ROLE_VIEW | `PrivilegeDto` gains `"regionLevel":"READ"\|"MANAGE"\|"APPROVE"\|null` (null = global) |

**User persistence:**
- New `B/user/UserAccountService.create(User unsaved, List<GrantInput> grantsOrNull)` is `@Transactional`. It saves the user and inserts the grants atomically, which fixes V6 for this path.
- `UserController.create` keeps its validation and delegates to it.
- `UserDto` gains a nullable `List<GrantDto> regionGrants`, filled only by `GET /api/users/{id}`.
- User delete: grants go by the FK cascade. They are not added to `pocReferenceCount`, because grants never block a delete.

**`TableSchemas`:**
- New `REGIONS` schema with columns `id`, `code`, `name`, `active`, `defaultRegion` (BOOLEAN), `createdAt`.
- Register it in `buildIndex` **and** in `TableSchemaController.VIEW_PRIVILEGE` as `"regions" → REGION_VIEW` (V9).
- CUSTOMERS, INVOICES, PAYMENTS, PROMISES and DISPUTES gain:
  ```java
  ColumnDef.of("regionId", "Region", ColumnType.REFERENCE).reference("region").notSortable().path(ColumnDef.referenceId("region")).build(),
  ColumnDef.of("regionName", "Region name", ColumnType.TEXT).path(ColumnDef.nested("region", "name")).build(),
  ```
  These are for display, the user's own filtering and A2 conditions. Authorisation stays in `ScopeResolver`.

### 4.12 Moving a customer to another region

Endpoint: `POST /api/customers/{id}/move-region`, `@PreAuthorize(CUSTOMER_MANAGE)`.

```json
// request
{"regionId":2,"version":5,"reason":"Account moved to the South team"}
// 200
{"customer":{"...CustomerDto, regionId:2, regionCode:\"SOUTH\", canManage:true":""},
 "moved":{"invoices":12,"payments":7,"promises":2,"disputes":1,"other":{"tasks":3}},
 "warnings":["sam.sales is Sales POC on 4 invoices but has no access to South",
             "cara.collections holds a Collection POC seat but has no access to South"]}
```

**The participant SPI:**

```java
public interface RegionMoveParticipant {
  default void beforeCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) {}   // throw BadRequestException to veto
  default int afterCustomerMoved(Long customerId, Long fromRegionId, Long toRegionId) { return 0; } // own rows moved
  default String name() { return getClass().getSimpleName(); }  // key in moved.other
}
```

**`CustomerRegionMoveService.move(Long id, MoveRequest req)`** runs in one `@Transactional`:

1. `ViewScopeHolder.requireLive()`.
2. `customerRepository.findByIdForUpdate(id)`. The customer is locked first (PPD-01, the same order as `DocumentParentLock`).
3. `scopeResolver.requireVisible(CUSTOMER, …)` → 404.
4. `requireIn(CUSTOMER_MANAGE, from)` → 403.
5. Target validation: unknown → 404; `requireReachable(to, "Region not found")`; `requireIn(CUSTOMER_MANAGE, to)` → 403; inactive → 400; same as source → 400.
6. `req.version != null && != customer.version` → `OptimisticLockingFailureException` (409).
7. `invoiceRepository.findByCustomerIdForUpdate(id)`, which locks invoices in ascending id order.
8. Call `beforeCustomerMoved` on every participant. A veto rolls everything back.
9. Compute warnings (read only):
   - Seat holders (`customerPocRepository.findBy…`) and distinct `salesPoc`/`collectionPoc` holders on children who have no grant ≥ READ in the target region.
   - **Nothing is removed.** Seats and record POCs are kept and are filtered out at use (§4.10).
10. Native updates (`@Modifying(flushAutomatically = true, clearAutomatically = true)`, `nativeQuery = true`; standard SQL on both dialects):
    ```sql
    update invoices         set region_id = :to, version = version + 1 where customer_id = :cid
    update payments         set region_id = :to where customer_id = :cid
    update payment_promises set region_id = :to where customer_id = :cid
    update disputes         set region_id = :to where customer_id = :cid
    update customers        set region_id = :to, version = version + 1 where id = :cid
    ```
    The version bumps make a concurrent stale `Customer` or `Invoice` save fail with 409. `updatable = false` protects rows without a version.
11. Call `afterCustomerMoved` on every participant, and collect their counts into `moved.other`.
12. Re-read the customer with `findById`, because the persistence context was cleared.
13. Audit `CUSTOMER_REGION_CHANGED` under `"CUSTOMER"`:
    - before `{regionId, regionCode}`, after `{regionId, regionCode, moved}`
    - reason = `req.reason` (≤ 500)
    - `regionId = to`
14. Return the response.

**Participants that other designs register:**
- **A6:** moves tasks.
- **B2:** re-homes or refuses pending changes. The recommendation is to refuse with 400 "Customer has pending changes awaiting approval".
- **B3:** writes region-history rows. Bulk updates bypass entity listeners, so B3 must hook here.
- **A5:** publishes UPDATED for the customer and every moved child id through the MANDATORY publisher.

Customer logins follow automatically: their rights read the customer's region on every request.

### 4.13 DTO changes and `/me`

**Record DTOs** gain trailing nullable components `Long regionId, String regionCode, String regionName` plus flags, all annotated `@JsonInclude(NON_NULL)`:

| DTO | Flags | Construction |
|---|---|---|
| `CustomerDto` | `canManage`, `canMoveRegion` | single constructor call, `CustomerService.toDtos` |
| `InvoiceDto`, `InvoiceSummary` | `canManage` | |
| `PaymentDto` | `canManage` | |
| `PromiseDto` | `canManage`, `canOverride` | |
| `DisputeDto` | `canResolve` | |

**`RecordAccess` component** (`B/region/RecordAccess.java`):

```java
public Boolean canManage(ScopeResolver.Kind kind, Long regionId);  // !historical && currentUser.hasIn(kind.manage(), regionId)
public Boolean can(String privilege, Long regionId);               // !historical && hasIn(privilege, regionId)
public Boolean canMoveRegion(Long regionId);                        // staff && can(CUSTOMER_MANAGE, r) && ∃ other active region with CUSTOMER_MANAGE
public Boolean canResolve(Dispute d);                               // staff && status PENDING && can(DISPUTE_MANAGE, d.region)
```

- A visible row already satisfies the book tier, so these flags need no SQL.
- **Audit snapshots** (`InvoiceDto.from(inv)` and similar) carry the region fields with **null** flags, so the caller's rights never land in audit JSON. `CustomerAuditSnapshot` gains `regionId`.

**`LoginResponse.UserInfo`** (`/login` and `/me`):
- `privileges` becomes `rights.effectiveAnywhere()`. For backfilled single-region users this is identical to today's list.
- It gains `regions`:

```json
{"id":7,"username":"rita","fullName":"Rita","role":"CASHIER","customerId":null,
 "privileges":["CUSTOMER_VIEW","CUSTOMER_MANAGE","INVOICE_VIEW","INVOICE_MANAGE","..."],
 "regions":[{"id":1,"code":"NORTH","name":"North","active":true,"defaultRegion":true,"level":"READ","scope":"ALL",
             "privileges":["CUSTOMER_VIEW","INVOICE_VIEW","SCOPE_OVERRIDE","..."]},
            {"id":2,"code":"SOUTH","name":"South","active":true,"defaultRegion":false,"level":"MANAGE","scope":"ALL",
             "privileges":["CUSTOMER_MANAGE","INVOICE_MANAGE","..."]}]}
```

- Customer logins get `"regions":[]`.
- The region names come from one `regionRepository.findAllById(rights.grantedRegions())` on `/me` and `/login`.
- The JWT is unchanged.

### 4.14 Contracts offered to the other designs

- **A5 (P3):**
  - `appUserDetailsService.principalFor(owner)` is the only way to build a principal.
  - Wrap evaluation in `ViewScopeHolder.with(new ViewScope(ruleRegionIdsOrNull, null), …)`.
  - An owner with no grants gets empty scopes: the rule does nothing, and the run records "owner has no region access".
  - Implement a `RegionMoveParticipant` that publishes events.
- **A3/A6:**
  - Tasks carry `region_id NOT NULL` with an FK to regions, set from a locked customer.
  - Add `Kind.TASK` and classify TASK_* (§4.2).
  - Assignee and recipient validation uses `regionAccess.holds(userId, TASK_VIEW, region)` or `withAccess`.
  - Add a move participant.
  - Add the table to `resetTransactionalData` before customers.
- **B2:**
  - `CHANGE_APPROVE → APPROVE`. "Can approve" = `currentUser.hasIn(CHANGE_APPROVE, record.region) && me != maker`.
  - Approvers come from `regionAccess.holdersIn(CHANGE_APPROVE, region)`. `findByRoleName` is never used for this.
  - Pending rows carry `region_id NOT NULL` from a locked customer.
  - Pending-change visibility uses `scopeResolver.requireVisible(kind, id, region, …)`.
  - The threshold table is keyed on `regions.id`.
  - Add a move participant.
- **B3:**
  - Rights are always **current** grants, applied to the record's region **as of** the date. Grants have no history; changes are audited as `USER_REGION_GRANTS_CHANGED`.
  - History roots must expose an attribute named `region`, so the `ScopeResolver` predicate applies unchanged.
  - `Scope.coverage()` exposes the region sets.
  - Add a move participant. `ViewScope.asOf` is already parsed; all record flags are false while `historical()`.

---

## 5. Frontend (`F/`)

- **`shared/models/auth_models.dart`:**
  - `CurrentUser` gains `final List<RegionAccess> regions;` with the default `const []`, so the all-required const constructor still compiles (G9).
  - `RegionAccess {int id; String code; String name; bool active; bool defaultRegion; String? level; String? scope; Set<String> privileges}`, parsed tolerantly (G10).
  - `bool hasIn(String p, int? regionId)`: if `regionId == null` or `regions.isEmpty`, return `has(p)`; otherwise that region's `privileges.contains(p)`, or false when the region is absent.
  - `Set<int> regionsWith(String p)`.
  - `bool get multiRegion => regions.length > 1`.
- **`shared/models/privileges.dart`:** add `regionView` and `regionManage`.
- **`core/scope/view_scope.dart`:**
  - `@immutable ViewScope {Set<int>? regionIds; DateTime? asOf}`, with `==` and `hashCode` over a canonical string.
  - `Map<String,String> get apiParams` returns `{'regionIds': sorted.join(',')}` only when set, and `asOf` (`yyyy-MM-dd`) only when set. It is an empty map when unset (G11).
  - `viewScopeProvider` is a StateNotifier holding session memory. It is not persisted, and it is cleared in `AuthController.logout`.
  - `InterceptorsWrapper viewScopeInterceptor(Ref ref)` adds `apiParams` to `options.queryParameters` with `putIfAbsent` on **every** request, including bulk and export POSTs (P5, G2). It is exported so a test can attach it to a bare Dio (G23).
- **`core/api/api_client.dart`:** `dio.interceptors.add(viewScopeInterceptor(ref))`.
- **Refetch on change:** `ref.watch(viewScopeProvider)` in:
  - `tablePageProvider` and `tableSummaryProvider` (`table_providers.dart:56-72`)
  - every dashboard provider
  - `myPocScopeProvider`, `assignablePocsProvider` and `referenceOptionsProvider` (G22)

  No collections go into `TableRequest.extra` (G1).
- **Region selector** in the `AppShell` actions (`app_shell.dart:88-91`):
  - A popup of checkboxes over `user.regions`, with "All my regions". The label reads "All regions" or the chosen names.
  - It is hidden for customer logins and when `regions.length <= 1`.
  - Its options come from `/me`; it fetches nothing (G13).
- **`PagedResult`** gains `regionIds` and `bookRegionIds` (default `const []`). The scaffold shows "Your book only in: SOUTH" when `bookRegionIds` is non-empty and `lockedFilters` is empty. `_lockedLabel` and its chip are unchanged.
- **Dashboard:**
  - `Coverage` gains `mixed`; `_coverage` maps `'MIXED'`.
  - `BookBadge` copy for mixed: "Your book in some regions".
  - Parse `scope.regionIds`.
- **Lists:**
  - A Region column `TableColumnSpec(label: 'Region', sortKey: 'regionName')` on Customers, Invoices, Payments, Promises and Disputes, shown only when `user.multiRegion`.
  - `reference_picker.dart` gets a `'region'` case whose options come from `currentUser.regions` (G6).
- **Models:** `Customer`, `InvoiceSummary`/`Invoice`, `PaymentRecord`, `PaymentPromise` and `Dispute` gain nullable `regionId`, `regionCode`, `regionName`, `canManage`, and the entity flags (`canMoveRegion`, `canOverride`, `canResolve`).
- **Gating** (sites in auth-privileges §7.5):
  - The entity's own edit, cancel and delete become `x.canManage ?? user.has(<xManage>)`.
  - Other MANAGE-level actions on a record (email send, document manage, add promise, POC assign, promise override) become `user.hasIn(<p>, x.regionId)`.
  - `dispute_detail_screen.dart:123` becomes `d.canResolve ?? user.has(Privileges.disputeManage)`, replacing the role-name gate (G20).
- **Create flows:**
  - The customer dialog shows a required Region dropdown from active `regionsWith(customerManage)` when there is more than one choice, and sends `regionId`.
  - The invoice, payment and promise customer pickers add `filter=regionId:in:<regionsWith(xManage)>`.
  - POC pickers pass `customerId` to `/api/pocs/assignable`.
- **Customer detail:** a "Move to region…" action when `customer.canMoveRegion == true`.
  - The dialog offers the target region (active `regionsWith(customerManage)` minus the current region) and a reason.
  - It POSTs `/move-region` with `version`, then shows the counts and warnings.
  - It invalidates the detail and list providers.
- **Users:**
  - `region_grants_editor.dart`, embedded in `UserFormDialog` and the user detail screen.
  - Each row has a region (from `GET /api/regions?size=50`), a level (READ/MANAGE/APPROVE segmented control) and a scope (ALL/BOOK).
  - The scope toggle is disabled with "Book (set by role)" when the role lacks `SCOPE_OVERRIDE`.
  - A warning appears when BOOK is chosen for a role with no `POC_ASSIGNABLE_*`.
  - On create it is pre-filled with DEFAULT at the highest level the role needs, and `regionGrants` is always sent. Edits go through `PUT …/region-grants`.
  - When editing yourself, the editor is read-only.
- **Roles:** `roles_screen.dart:207-227` shows a "Global", "Read", "Manage" or "Approve" badge from `regionLevel`, with the note "applies only where the user's region level allows".
- **Regions admin:**
  - `features/regions/regions_screen.dart` (a `DataTableScaffold` on `/api/regions`), `region_form_dialog.dart`, and `region_detail_screen.dart` (the grants list; B2 adds a thresholds tab).
  - `NavEntry('Regions', …, [Privileges.regionView], hideForCustomer: true)`, with the route guarded by `needs(const [Privileges.regionView])` (G16).
- **Constraints:** `flutter analyze` stays at zero; all new fields are optional; parameters are added only when set.

---

## 6. Edge cases and failure modes

| Case | Behaviour |
|---|---|
| `regionIds` names a region the caller holds nothing in, or an unknown id | Dropped by the intersection. 200 with an empty result, `regionIds: []`, and no lock chip. |
| `regionIds=abc` or `asOf=x` | 400 from the interceptor |
| Staff user with zero grants | Authorities are the global privileges only. Region-bound endpoints give 403 at `@PreAuthorize`. `/me.regions = []`. Nav hides them. |
| Customer login sends `regionIds` | Ignored; it sees its own customer. |
| Grants edited while the user is signed in | The server applies them on the next request. The UI uses per-record flags; `/me` refreshes on reload. |
| Role privileges edited | Applies in every granted region, subject to level. The role editor states this. |
| BOOK scope on a role without override | Behaves as the role already does (book-limited everywhere). The editor shows "set by role". |
| BOOK scope on a role without `POC_ASSIGNABLE_*` | Nothing is visible in that region. The editor warns. |
| APPROVE vs MANAGE before B2 lands | Identical |
| Unclassified privilege at runtime | Treated as regional MANAGE (fail-safe). `PrivilegeClassificationTest` catches it in CI. |
| Inactive region | Existing rows remain readable under grants. No creates or moves into it; not offered in create pickers. It can still be viewed in the selector. The default region cannot be deactivated or deleted. |
| Deleting a region that is referenced | 400 with guidance, or 409 from an FK. |
| Two instances boot together | The DEFAULT insert races on `uk_region_code`. The loser catches, re-selects and repairs the default flag. |
| NOT NULL enforcement cannot run (null rows) | WARN with a count; retried next boot. Null-region rows are invisible to staff (`region.id in (…)` excludes null), so the failure is closed. |
| A user deliberately left with no grants | Never re-granted: `region_backfill_pending` is false. |
| Child create runs concurrently with a move | Serialised by the customer row lock (F1). |
| Stale Payment/Promise/Dispute save after a move | `region_id` is not in the UPDATE (`updatable=false`). |
| Stale Customer/Invoice save after a move | 409 from the version bump. |
| Move leaves POCs without access in the target | Seats and record POCs are kept and listed as warnings. They are skipped as holders, assignees and senders, and they lose visibility. |
| Move with pending B2 changes | Decided by B2's participant (veto or re-home). |
| History of a deleted customer | Region-checked through `audit_logs.region_id`; legacy rows count as the default region. |
| A region nobody can reach | Its creator is auto-granted APPROVE/ALL. Any USER_MANAGE holder can grant others; self-edit is refused (AUTH-03 parity). |
| Background thread with no principal | `rights()` is `none()`, so `hasIn` is false; `require()` still throws BadCredentials. Both are permanent failures (C-8). |
| `asOf` on a domain write | 400 "Viewing as of … is read-only". Other writes ignore it. |
| Stale id cache after the second test context recreates H2 | No region id is cached anywhere. |
| Invoice numbers, customer email uniqueness, products | Remain global (explicit). |
| Notification links into a region the user lost | 404, rendered as `RecordUnavailable`. |
| Lock-timeout risk | The move holds customer → invoices; other writers already use this order. H2's 2 s lock timeout applies only to contrived race tests. |

---

## 7. Tests (H2)

### 7.1 Backend

Test classes live under `BT/` and extend `IntegrationTestBase` unless marked plain.

**`IntegrationTestBase` changes** (every existing test must stay green unedited):
- Autowire `RegionRepository`, `UserRegionGrantRepository`, `RegionGrantService`, `AppUserDetailsService principals`, `DisputeRepository` and `AuditLogRepository`.
- `defaultRegion()` looks the region up on every call.
- `region(String code)` finds or creates an active region.
- `grant(User, Region, AccessLevel, RecordScope)`.
- `asking(Long... regionIds)` sets `ViewScopeHolder` for direct service calls.
- `user(name, role)` saves the user, then grants DEFAULT/APPROVE/ALL. `customerUser` is unchanged.
- `customer(name[, email])` sets `.region(defaultRegion())`; new overload `customer(name, Region)`.
- `actAs` and `as` use `principals.principalFor(fresh)`.
- **Reset**, in FK order:
  1. `ViewScopeHolder.clear()`
  2. (tasks and pending tables from other designs)
  3. The existing chain, with `disputeRepository.deleteAll()` added before `customerRepository.deleteAll()`
  4. The user deletes (grants cascade)
  5. Delete grants in regions whose `code <> 'DEFAULT'`
  6. Delete regions whose `code <> 'DEFAULT'`
  7. Set DEFAULT `active = true`, `is_default = true`
  8. Replace the grants of `admin` and `cashier` with DEFAULT/APPROVE/ALL

**New test classes:**

| Class | Scenarios |
|---|---|
| `privilege/PrivilegeClassificationTest` (plain) | Every name in `Privileges.ALL` is in exactly one of `GLOBAL` and `REGION_LEVEL`; `requiredLevel` of an unknown name is MANAGE. |
| `region/RegionRightsTest` (plain) | The formula in §3 for each level and scope; SCOPE_OVERRIDE needs ALL; `regionsWith(GLOBAL)` throws; `forCustomer` binds to its own region; `none()`; `effectiveAnywhere` / `effectiveIn`; `null` region → false. |
| `auth/PrincipalRightsTest` | `CountingStatements.reads("user_region_grants", GET /api/invoices with a real Bearer header) == 1`; `role_privileges` is not read by `ScopeResolver`; `GET /api/emails` and `/api/inbox` (outside OEMIV) give 200 for a multi-region user; `SecurityContextHolder.clearContext()` then `scopeResolver.forInvoices()` throws BadCredentials while `currentUser.hasIn` is false; `/me` shape including `regions` and a customer's `[]`. |
| `common/scope/ViewScopeTest` | Repeated and comma forms; blanks; 400 on garbage `regionIds` and `asOf`; the ThreadLocal is cleared after the request; `with()` restores the previous scope. |
| `region/RegionAccessMatrixTest` | **Rita** (CASHIER role; NORTH READ/ALL, SOUTH MANAGE/ALL, no WEST row); customers and one invoice, payment, promise and dispute in each region. Lists: all → N+S; `regionIds=N` → N only; `regionIds=W` → empty 200 with `regionIds: []`. Detail: N 200 with `canManage=false`; S `canManage=true`; W 404. PATCH/cancel: 403, 200, 404, **and the DB is unchanged after each refusal**. POST invoice, payment and promise per customer region: 403, 200, 404; new records carry `region = S`. Customer create: `regionId=N` 403; missing `regionId` with two managed regions → 400 `fieldErrors.regionId`; inactive region → 400. Document upload on N → 403; `DocumentDto` flags per region. Email send on N → 403. POC seat add on N → 403. Dispute approve on N → 403 (on W → 404). Promise override on N → 403. `asOf=2026-01-01` on PATCH → 400. `/recompute` limited to S. |
| `region/RegionParamCoverageTest` | Table-driven with N and S fixtures and `regionIds=S`: lists and `/summary` for the 5 entities; `/export` ×5; invoice bulk CANCEL with `selectAllMatchingFilter` (only S cancelled); dashboard ×5; `/api/pocs/my-scope` and `/assignable`; email bulk ids. Each asserts that only S appears and that `PageResponse.regionIds == [S]` or `scope.regionIds == [S]`. Also enumerates `RequestMappingHandlerMapping.getHandlerMethods()` and **fails on any `/api/**` handler** that is not in exactly one of the lists `SETS`, `BY_ID`, `WRITES`, `GLOBAL_EXEMPT` (users, roles, products, regions, privileges, notifications, inbox, auth, table-schemas, gmail, mail-service). |
| `region/RegionBookTest` | Role SALES_POC plus SCOPE_OVERRIDE; NORTH MANAGE/ALL, SOUTH MANAGE/BOOK. Sees every N invoice and only own S invoices; `lockedFilters == []`, `bookRegionIds == [S]`. Pure-book user: chip present, as today. Narrowed to nothing → no chip. `requireAssignable` fails in a region without a grant (400 with the region message). `/assignable?customerId` lists only region-accessible users. Email To and `defaultAssignee` skip seat holders without access. Dashboard: `coverage = MIXED`; collected = S allocations + N payment sums (including unallocated credit in N), checked by hand. Single-region figures for the admin, SALES_POC, collection-only custom and PAYMENT_VIEW-only override roles equal the pre-change formula (F2). |
| `region/CustomerLoginRegionTest` | `regionIds` pointing elsewhere changes nothing; another customer's invoice is 404; another customer's dispute is still 403 (X1); a login's rights follow a move. |
| `region/RegionAdminTest` | CRUD with validation; 409 on a stale version; make-default flips exactly one; the default cannot be deactivated or deleted; deleting a referenced region → 400; the creator is auto-granted; `PUT` grants writes an audit row; self → 400; customer login → 400; duplicate region → 400; deleting a user removes its grants (verifies `@OnDelete` DDL); `POST /api/users` without `regionGrants` → DEFAULT/APPROVE/ALL; with `[]` → none; `GET /api/privileges` `regionLevel`; `/api/table-schemas/regions` needs REGION_VIEW; deleted-customer history is 404 for a user without access in its region. |
| `region/CustomerRegionMoveTest` | Children regions change and Invoice/Customer versions bump; the audit row `CUSTOMER_REGION_CHANGED` carries `region_id`; warnings list POCs without access and seats are kept; 403 without CUSTOMER_MANAGE in source or target; 400 for an inactive or identical target; 409 on a stale `version`; `asOf` → 400; a NORTH-only user gets 404 afterwards; a test participant's veto rolls back everything; a stale `PaymentPromise` entity saved after the move keeps the new region; `moved.other` includes participant counts. |
| `region/RegionConsistencyTest` | After create, pay, promise, dispute and move scenarios, a JPQL join finds no child whose region differs from its customer's. |
| `region/RegionSchemaUpgradeTest` | (a) Shared DB, following the deleted `InvoiceSchemaUpgradeTest` pattern: `alter … region_id set null` on the five tables, null out seeded rows' `region_id`, set `region_backfill_pending = true` on a staff user and delete their grants, call `afterPropertiesSet()` in try/finally. Assert the backfill, `isNullable == false`, one APPROVE/ALL grant, the flag false and the audit row; run again → no duplicates; a user with the flag false and no grants stays without grants. (b) `SchemaDriftTest` pattern on `jdbc:h2:mem:drift-<uuid>` with `ddl-auto=update`: boots and seeds a DEFAULT grant for admin. |

### 7.2 Existing suites

All 119 backend, 24 mail-service and 61 frontend tests must pass with **no edits to test classes** other than `IntegrationTestBase`.

Under one region, behaviour intentionally shifts in two places, neither of which fixtures observe:
- **Dispute-opened notifications** go to ADMIN-role users with access to the region. Admin has DEFAULT, so it still receives them.
- **Staff dispute visibility** requires a grant in the region. All fixtures have DEFAULT.

### 7.3 Frontend (new tests)

- **`current_user_regions_test.dart`:** parse with and without `regions`; `hasIn` fallback; `regionsWith`.
- **`view_scope_test.dart`:**
  - `apiParams` is empty when unset.
  - The canonical string is used.
  - The exported interceptor on a bare Dio adds `regionIds` to GET and POST only when set.
  - It never overrides an explicit parameter.
- **`region_selector_test.dart`:** hidden for 0 or 1 regions and for customers; selecting a region refetches the list with `regionIds`.
- **`region_grants_editor_test.dart`:** the exact PUT body; the BOOK toggle is disabled when the role lacks override; read-only for self.
- **`detail_gating_test.dart`:** `canManage: false` hides Save on an invoice even when the user has `invoiceManage`; `canResolve` controls the dispute buttons.
- **`dashboard_coverage_test.dart`:** `'MIXED'` parses to `Coverage.mixed`.

---

## 8. Implementation slices

Each slice must leave backend, mail-service and frontend tests green and `flutter analyze` at zero.

1. **R1: Foundations, no visible behaviour change.**
   - `ClockConfig` (if missing) and `SchemaSupport`.
   - The entities, enums and repositories in §4.3 and §4.11.
   - `region_id` columns with `updatable=false` and `@PrePersist`; `users.region_backfill_pending`; `audit_logs.region_id` and its overload.
   - `RegionSchemaUpgrade`; the `DataSeeder` grants.
   - `RegionRights`, `principalFor`, the 3-argument `AppUserDetails`, the `CurrentUser` additions, `RegionAccessQueries`.
   - `ViewScope` classes, with the holder only; no interceptor yet.
   - The `ScopeResolver` rewrite (§4.7) with `requireVisible` at the by-id sites (§4.8.1).
   - Create paths set the region and lock the customer (F1).
   - `IntegrationTestBase` fixtures.
   - Tests: `PrivilegeClassificationTest`, `RegionRightsTest`, `PrincipalRightsTest`, `RegionSchemaUpgradeTest`.
2. **R2: "Which regions am I asking about".**
   - The interceptor and config.
   - `PageResponse` echo and `region` fetch; region fields on record DTOs and `TableSchemas` region columns.
   - The dashboard split, coverage and `ScopeInfo`; email bulk ids.
   - Tests: `ViewScopeTest`, `RegionParamCoverageTest` (sets part), `RegionBookTest`.
3. **R3: Writes and POC per region.**
   - The §4.9 inventory: `requireLive`, `requireIn`, `requireReachable`, bulk `Access`.
   - `requireAssignable(…, region)`; holder filtering; `notifyAdmins(…, regionId)`; dispute ordering.
   - `DocumentDto` flags and `RecordAccess` flags; the deleted-customer audit check; `/my-scope` and assignable parameters; dead-method removal.
   - Tests: `RegionAccessMatrixTest`, `CustomerLoginRegionTest`.
4. **R4: Administration.**
   - `RegionService`/`RegionController`, `RegionGrantService`/`RegionGrantController`, `UserAccountService`, `POST /api/users` grants, `/me` and `/login` `regions`, `/api/privileges` `regionLevel`, `TableSchemas.REGIONS`, the audit `REGION` type.
   - Tests: `RegionAdminTest`; complete the endpoint classification in `RegionParamCoverageTest`.
5. **R5: Move.**
   - The `RegionMoveParticipant` SPI, `CustomerRegionMoveService` and the endpoint.
   - Tests: `CustomerRegionMoveTest`, `RegionConsistencyTest`.
6. **R6: Frontend (§5)** and the frontend tests.

---

## 9. Rejected alternatives

- **Grants as `(user, region, role)` (the role-per-region proposal).**
  - It turns `User.role` into a "global-only" role alongside regional roles: two role concepts on one user.
  - It needs a compatibility rule in `PUT /api/users`.
  - It needs a region-aware rewrite of `GmailDisconnects`, `RoleController.delete` and the user editor, and it makes every READ-level check (`canSeePoc` and audit, document and email view) per region.
  - A missed region check falls back to the union of all regional roles.
  - Its one advantage, a different function per region, stays reachable later by adding a nullable `role_id` to `user_region_grants` with `effective = (grant.role ?? user.role) ∋ p ∧ level`. That is additive and needs no data migration.
- **Grants as privilege lists per region.** It duplicates roles and the editor becomes unusable.
- **Region as an ordinary filter (`regionId:in:`) or in `lockedFilters`.** Filters are removable chips, many endpoints take no filters, and `lockedFilters` means "book" to the dashboard and the UI (X4, R14).
- **Region derived through the customer.** Ruled out by P4; disputes have no association, and B3 needs a per-record region.
- **`@RequestScope` ViewScope bean.** It throws on consumer threads.
- **Region in the JWT.** A revocation would wait up to 24 hours.
- **The selector narrowing by-id reads.** Deep links from notifications, tasks and audit would 404, and it adds no security because grants are the boundary.
- **A "table empty" one-shot backfill marker.** It re-grants everyone after a deliberate total revocation plus a restart.
- **A derived ceiling level at backfill** (READ for VIEWER, and so on). A later role edit would then silently do nothing for existing users. APPROVE/ALL means "the role decides", as today.
- **Auto-pruning POC seats on a move.** It is destructive and irreversible. Seats are kept and filtered at use.
- **`EXPORT_DATA` as a regional privilege.** Export content is already region-scoped by the view scope, so a regional export right adds nothing.
- **A 15-field per-record `RecordAccess` DTO.** Because of the READ-level equivalence, the entity `canManage` flag plus `/me.regions[].privileges` cover every gate with far less server code.
- **Falling back to the default region on customer create when the choice is ambiguous.** That would silently file customers in the wrong region; the API returns 400 instead.
- **Allowing self-edit of grants.** It breaks parity with AUTH-03. Auto-granting the creator on region create removes the "orphaned new region" case that motivated self-edit.