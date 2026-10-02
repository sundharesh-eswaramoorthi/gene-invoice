# Decision record: B3 as-of reads ("asof")

**Status:** decided. **Winner:** *typed-scd2*, meaning typed per-entity SCD2 version tables. They are captured by a Hibernate event listener plus a before-completion writer, and read through the existing Criteria engine by swapping the root class and rehydrating results.

**Synthesis:** the winner is kept, with grafts from *envers* and *snapshot* and with fixes for flaws found during review (§1.4). Implementation agents should build from this document alone.

Path prefixes used below:
- `B/` = `backend/src/main/java/com/geneinvoice/`
- `T/` = `backend/src/test/java/com/geneinvoice/`
- `F/` = `frontend/lib/`

---

## 0. Verdict in one paragraph

Store history as **typed SCD2 version tables** that mirror the live tables' attribute names. They carry `valid_from`/`valid_to`, a `9999-12-31` "open" sentinel, and no FK, unique or CHECK constraints.

**Capture.** A Hibernate `Post*` listener only marks which aggregates a transaction touched. A `BeforeTransactionCompletionProcess` then writes the versions in the same transaction, after Hibernate's final flush. It is state-based: it snapshots the current row and compares it with the open version. This covers every ORM write path by construction, including `CreditLedger`, `voidPayment`, `replaceItems`, `applyShares` and seat deletes.

**Reads.**
- An `AsOf` point is bound per request, only on endpoints classified `@AsOfAware`.
- `TableQueryExecutor` then queries the version class instead of the live class and adds one validity predicate. It rehydrates rows into write-proof `HistoricalX extends X` objects, so every service, controller and DTO factory keeps working.
- Associations and subqueries go through one helper, `Temporal`.
- Unclassified endpoints and live loads of versioned entities fail closed with a 400.

Region at T and approvals outstanding at T fall out of this for free: `region_id` is a versioned column, and pending changes are a versioned type.

---

## 1. Evaluation

### 1.1 PRD clauses and how the synthesized design meets them

| Clause (quoted) | How it is met |
|---|---|
| B3: **"Every list, report and rule evaluation can be asked as of a date"** | **Lists, counts, tiles and exports:** every one goes through `TableQueryExecutor`, which swaps to version roots when `AsOf` is bound (§7.4). **Reports:** dashboard D1–D8 via `Temporal` (§8.7); D6–D8 are executor-based. **Record tabs:** documents and emails are filtered on their own timestamps; the audit tab on `createdAt` (§8). **Rule evaluation:** the preview endpoint runs through the executor under `AsOf` (§9.1), and A5 can evaluate at the event point (§9.2). **Configuration lists** (users, roles, privileges, regions, rules, notifications, inbox) are not versioned. They answer live, with `X-View-As-Of: live` and `asOf: null`, and the UI shows "history isn't kept for this list" (§2.4 and §16 Q1). |
| B3: **"…including which region a record belonged to then"** | `region_id` is a column of every region-bearing version table, so B1's scope predicate `root.get("region").get("id").in(allowed ∩ requested)` evaluates the region at T with no change. The B1 region move is an ORM cascade, so all children get new versions at the same instant (§9.5). |
| B3: **"…and which approvals were outstanding"** | B2's `PendingChange` is a versioned type (§9.6). The approvals list and the `pendingApproval` column/flag are evaluated at T, and the pending row's region is also taken at T. |
| B1: **"Every list, filter, count and dashboard figure becomes a question about which regions am I asking about."** | This composes with as-of: the region predicate stays in `ScopeResolver` and runs unchanged on version roots. `regionIds` and `asOf` are separate parameters, and neither ever enters `lockedFilters` (R14). |
| B1: **"A user holds different rights in different regions"** | As-of reads apply the caller's **current** rights to the record's **region at T** (§2.3). Rights history does not exist (audit map §9 #9). This is flagged to the PRD owner (§16 Q2). |
| B1: **"The POC book is per region."** | The book is evaluated at T: `salesPoc`/`collectionPoc` columns at T, plus seat and invoice EXISTS subqueries over version tables through `Temporal.subquery`. Assignability per region is current. |
| B2: **"A pending change is visible to everyone who can see the record, without having taken effect."** | Pending rows are separate, versioned rows. The live entity tables and their versions never hold unapplied values, so as-of figures stay correct. Visibility at T means "visible on the record at T". |
| A5: **"A rule can never slow down or break the user's save"** | History is not a rule, but the same discipline applies. Version tables are trivially safe: no FK, unique or CHECK constraints, and every attribute column nullable. A Java-side capture bug is logged and skipped in production (strict mode fails it in tests). The only remaining way to fail a save is a database failure, which would fail the save anyway. Cost is bounded at about 1 select per touched type plus 1 update and 1 insert per changed aggregate (§4.7). |
| P5 pinned: **"As-of mode is read-only: writes reject/ignore asOf and the UI disables edits."** | The interceptor rejects every non-read handler with 400 `AS_OF_READ_ONLY`, unless the handler is explicitly a read (`@AsOfAware` export or preview POSTs) or housekeeping (`@AsOfIgnored`). The writer and listener throw if `AsOf` is bound. Rehydrated objects cannot be persisted (unmapped subclass). DTO capability flags are false. The UI ANDs `!isHistorical` into every edit gate (§12). |
| P5 pinned: **"read them in exactly one place… consumed by ScopeResolver, DashboardService and every list/count/export/detail path"** | `asOf` is parsed only in `AsOfInterceptor` (the P5 ViewScope interceptor), which fills `ViewScope.asOf` and the `AsOf` thread carrier. Consumers read `AsOf.current()`; nothing else reads the request parameter. |
| P6 pinned: **Clock** | Version stamps come from the injected `Clock`. "Today" goes through `Today.date()`, which returns the as-of date when a point is bound (§6.6). |
| Predecessor PRD: "Nothing an automation sends or assigns escapes existing permissions" | The rule preview uses the caller's scope. Event-point evaluation in the consumer runs under the owner's principal (P3) and only reads. |

### 1.2 Spot checks against the source (all opened or re-run in this review)

| # | Claim relied on | Evidence | Result |
|---|---|---|---|
| 1 | Every list, count, ids and aggregate path builds `Root<T> root = cq.from(type)` in one class, and `inScope` returns true on an empty scope | `B/common/query/TableQueryExecutor.java:30-100, 72-75, 107-120, 134-144` | Holds. Root swap and validity injection belong here. The `id desc` tiebreak uses `root.get("id")`. |
| 2 | `nested()` joins through `from.getJoins()`, which cannot see entity joins | `B/common/query/ColumnDef.java:30-46`. Probe `exp.Main` output: "getJoins() sees entity join? 0; sqmJoins=1" | Holds. `Temporal.join` must reuse joins by alias via `SqmFrom.getSqmJoins()`. |
| 3 | Subquery columns and scopes hard-code live classes; overdue reads the static clock | `TableSchemas.java` (`customerOutstanding` `sq.from(Invoice.class)`, `pocSeatPredicate` `sq.from(CustomerPoc.class)`, `promiseLinkPredicate` `promise.join(association)`, `overduePredicate` → `InvoiceDates.today()`); `ScopeResolver.java:135-151`; `CustomerService.java:103-127`; `DashboardService.java:71,88,118-119,183-200,313-314` | Holds. These are exactly the sites `Temporal` must cover (§7.5), and `TemporalConventionTest` guards them. |
| 4 | DTO factories read today, product, allocations, links and `updatedAt` | `InvoiceDtos.java` (`InvoiceLineDto.from` reads `it.getProduct().getName()`; `from(...)` reads `InvoiceDates.today()`); `PaymentDtos.java:57-73` (allocations, `customer.getCreditBalance()`); `PaymentPromiseService.toDto` (links, `getUpdatedAt()`) | Holds, with two corrections to typed-scd2: `updated_at` must be **stored** (not compared), and invoice items need a product stub carrying the name at T. |
| 5 | `PaymentPromise` has no `@Version`; links are an owning `@ManyToMany`; link-only changes happen | `promise/PaymentPromise.java`; `PaymentPromiseService.applyShares` (`relink`, "moved" is true when the links changed) | Holds. This is a concurrency hazard for Envers (owner `_aud` revend update without a row lock). The chosen design versions **each link row separately**, which is race-safe without owner locks (§4.5). |
| 6 | The test reset has one `@Import`, no raw SQL in tests, and disputes leak | `T/IntegrationTestBase.java:41-89`; grep of `T/` for JDBC or native SQL finds nothing | Holds. The reset **must add `disputeRepository.deleteAll()`**, or leaked disputes would have no versions and fail the new `@AfterEach` consistency check (a flaw in typed-scd2). |
| 7 | CORS exposes only `Authorization` | `B/config/SecurityConfig.java:48` | Holds. Add `X-View-As-Of`. |
| 8 | Commit order: Spring `beforeCommit` → Hibernate flush → action-queue before-completion processes | Scratch Hibernate 6.5.3 source `SessionImpl.java:1979-1990` (`flushBeforeTransactionCompletion(); actionQueue.beforeTransactionCompletion();`); `ActionQueue.java:471-476, 542-549` | Holds. The writer sees the final flushed state, and P2's outbox insert runs earlier. |
| 9 | No `@Modifying` on versioned tables; no runtime JDBC | grep: `@Modifying` only in Document, EmailRecipient, Email, GmailConnection, Notification and User repositories; native only in GmailConnection; JDBC only in `*SchemaUpgrade` | Holds. |
| 10 | Read paths that bypass the executor | `CustomerService.java:129-138, 152-202`; `PaymentController.credit` (`customerRepository.findById`); `EmailTargets.loadUnscoped` (repositories, lines ~228-237); `DocumentService.onRecord` (`isFalse(deleted)`); `PocService.listFor`; `AuditTimelineService` findById at 68, 85, 99; `DisputeService` 145, 156, 197-212 | Holds. This is the full list in §8. |
| 11 | Typed-scd2 probe: `id` attribute ≠ `@Id` resolves to `entity_id`; FK access without a join; entity join with validity; correlated subquery; unmapped subclass cannot be persisted; `NO_CONSTRAINT` gives 0 FKs; `@Enumerated` gives an H2 `enum(...)` type; listener plus before-completion writer | Re-ran `scratchpad/exp` (`exp.Main`): outputs 1, 1b, 1c, 2, 2b, 3, 4, 4b, 4c, 5 and 5b as claimed, the writer closed one version and inserted the next, and the DDL shows `status enum(...)` for `@Enumerated` | Holds. |
| 12 | Envers probe: legacy row update fails and rolls back the save (E4); association join to a filtered mirror duplicates rows (E6); the auto-enabled filter resolver works (E7) | Re-ran `scratchpad/envers-probe` `probe.Main` and `Main2`: "update FAILED: AuditException … Cannot update previous revision … live name after failure=Legacy"; the invoice rows print twice through the association join; the resolver switches R | Holds, and it confirms Envers' hard-failure mode. |
| 13 | Export endpoints are synchronous (a ThreadLocal is safe) | `InvoiceController.export` returns `ResponseEntity<String>` built with `Csv.of` | Holds. |

### 1.3 Scorecard

| Criterion | envers | snapshot | typed-scd2 (winner) |
|---|---|---|---|
| (1) PRD coverage | Full | Partial: the 50,000-row scan cap turns legitimate lists into 400s; text sort order differs on Postgres | Full |
| (2) Concurrency and failure | **Saves can fail**: legacy or unbaselined rows (E4, verified); `*SchemaUpgrade` logs and continues, so a failed baseline breaks saves; owner `_aud` revend update for link-only changes has no lock; the proposed early `getCurrentRevision` in `beforeCommit` can invert per-entity revision order and produce **two visible versions** | Safe (tolerates capture failures) | Safe (tolerates gaps). Needed fixes: bind the instant at the writer, not `beforeCommit`; no owner lock for links (§4.5) |
| (3) Codebase fit | `@Audited`/`NOT_AUDITED` on 9+ live entities; R9 widening doubled on `_aud`; `hibernate.cdi.extensions`; auto-enabled `@Filter` with a `ParamDef` resolver; two read mechanisms (mirrors and `AuditReader`) | A second query engine next to Criteria; in-memory dashboard twin | Reuses Criteria, `TableSchemas`, `ScopeResolver` and DTO factories unchanged; converters mean no R9; no live entity annotations |
| (4) Agent implementability (H2) | Fewer lines, but subtle framework behaviour that only probes reveal | Hardest: an operator × type parity matrix | Mechanical per-type code plus strong oracles (`@AfterEach` consistency, shape test, round-trip test, endpoint coverage) |
| (5) Volume and regression risk | Least capture code, highest risk of breaking saves | Large; live path untouched but as-of correctness risk high | ≈4k main LOC; live SQL byte-identical; write path gains bounded extra statements that cannot fail a save |

### 1.4 What was taken from each proposal, and what was fixed

- **From typed-scd2 (base):**
  - version tables with live attribute names and the attribute `id` = business id;
  - `OPEN` sentinel;
  - no constraints, enum `@Convert`;
  - listener plus before-completion writer;
  - executor root swap;
  - `Temporal.join`/`subquery`;
  - rehydration into unmapped subclasses;
  - `LiveReadGuard`;
  - `VersionShapeTest`, `TemporalConventionTest`, the no-constraints test.
- **Grafted from envers:**
  - handler classification (`@AsOfAware`/`@AsOfIgnored`) with fail-closed 400s;
  - the `X-View-As-Os` header plus frontend verification (header name is `X-View-As-Of`);
  - revision metadata (origin, actor, rule or approval id), carried here on each version row;
  - `HistoryOrigin.run(...)`;
  - the `@AfterEach` history-consistency check across **all** existing tests;
  - dispute cleanup in the reset;
  - **exact** semantics before history starts (400), instead of typed-scd2's "approximate";
  - the endpoint coverage test that asserts as-of results **differ** from live;
  - the listener guard under `AsOf`.
- **Grafted from snapshot:**
  - one per-transaction work object exposing `txId` to the P2 outbox;
  - production tolerance of capture bugs (strict in tests);
  - the reconciler reusing the **same writer path**, with lock-and-recheck;
  - baseline through the same writer with a pinned instant;
  - the `/api/history/range` endpoint for date-picker bounds.
- **Fixed relative to typed-scd2:**
  1. The transaction instant is bound **lazily inside the writer**, after all row locks are held, not at the first `beforeCommit` caller. This prevents ordering inversions. The outbox gets `txId` only (§9.2).
  2. There is **no owner lock** for link-only changes, because per-link rows are race-safe. A lock is taken only when a root version would change but the root row was not written in the transaction.
  3. There is no in-place overwrite path: `from = max(t, open.validFrom + 1µs)`.
  4. `updated_at` and `broken_notified_at` are stored but not compared.
  5. The product name at T comes from `product_versions`, not only from the invoice snapshot.
  6. Users are rehydrated by batch `findAllById`, with a "Deleted user" stub for ids that no longer exist, so there is no lazy proxy to a deleted user and no change to user-delete rules.
  7. One generic `VersionStore` replaces per-type Spring Data repositories, avoiding the `findById`-means-`versionId` trap.
  8. `MovableClock` is a JVM-wide singleton shared by both test contexts.
  9. The `asOfApproximate` idea is dropped.

---

## 2. Semantics

### 2.1 The instant

| Term | Definition |
|---|---|
| Wire value | `asOf=yyyy-MM-dd` (strict; anything else is 400 `AS_OF_INVALID`). A calendar date in UTC, the project's day convention (`B/invoice/InvoiceDates.java:14-20`). |
| Point | `p = (D+1)T00:00:00Z − 1µs` (end of day D). A version is valid at p iff `valid_from <= p AND valid_to > p`. |
| `asOf > clock today (UTC)` | 400 `AS_OF_FUTURE` "asOf cannot be after today". |
| `asOf == clock today` | Treated as **live**: nothing is bound, header `X-View-As-Of: live`, writes allowed. The frontend never sends today (picker `lastDate` = yesterday). |
| `asOf < historyStartsOn` | 400 `AS_OF_BEFORE_HISTORY` "History is available from 1 Oct 2026". `historyStartsOn = UTC date of history_meta.started_at`, which is set only when the first baseline copied existing rows. On a fresh or test database there is no lower bound. |
| "Today" in as-of mode | `Today.date()` returns D. This drives `overdue` (filter and DTO), `daysOverdue`, `relative` presets, tiles, ageing buckets, billed/collected month windows and export overdue columns. |
| Record existence | A record exists at T iff it has a version valid at p. Backdated `invoiceDate` values, `createdAt` and `paidAt` never decide existence. |
| History semantics | Transaction time: what the system had committed by the end of D. |

### 2.2 What is versioned

Customer, Invoice (lines embedded as JSON), Payment, PaymentAllocation, PaymentPromise and its invoice/payment links, Dispute, CustomerPoc seats, Product; later Task with its assignees (A6) and PendingChange (B2).

Every attribute of these is taken at T, including `region`, `salesPoc`, `collectionPoc` and `status`.

### 2.3 What is current (documented, not versioned)

- The caller's privileges and region grants: current rights are applied to the region and book at T.
- User names and active flags, region names, role definitions, rule definitions (the current condition is evaluated against state at T).
- Document visibility (INTERNAL/SHARED) and email delivery status.
- Notifications and inbox (live, `@AsOfIgnored`).

### 2.4 Configuration lists

Users, roles, privileges, table schemas, POC pickers, notifications, inbox, Gmail and rules lists are `@AsOfIgnored`. They answer live with header `X-View-As-Of: live` and `PageResponse.asOf = null`. The UI shows "Showing current data — history isn't kept for this list." See §16 Q1.

---

## 3. Storage

All tables are new, so `ddl-auto` creates them and C-5, R9 and R10 do not apply to them. Everything is plain JPA/DDL, valid on H2 and Postgres, with no dialect gate.

### 3.1 Common shape: `@MappedSuperclass com.geneinvoice.history.VersionRow`

```java
public static final Instant OPEN = Instant.parse("9999-12-31T00:00:00Z");
@Id @GeneratedValue(strategy = IDENTITY) @Column(name = "version_id") Long versionId;
@Column(name = "valid_from", nullable = false) Instant validFrom;   // timestamp(6) with time zone
@Column(name = "valid_to",   nullable = false) Instant validTo;     // OPEN while current
@Column(name = "tx_id", nullable = false, length = 36) String txId;
@Column(name = "origin", nullable = false, length = 12) String origin;  // USER|RULE|APPROVAL|SYSTEM|SCHEMA|RECONCILE
@Column(name = "origin_ref_id") Long originRefId;                        // rule id (RULE) or pending-change id (APPROVAL)
@Column(name = "actor_user_id") Long actorUserId;
```

Rules for every version entity (package `com.geneinvoice.history.versions`):

1. **Business id.** Each concrete class declares `@Column(name = "entity_id", nullable = false) Long id;`, which is **not** the `@Id`. Hibernate resolves `root.get("id")` and HQL `v.id` to it (verified). The exception is the link table (§3.3).
   - **Never** call `em.find(XVersion.class, …)`; it looks up `versionId`.
   - There are no Spring Data repositories for version entities. Use `VersionStore` (§7.2).
2. **No constraints.**
   - Every association uses `@JoinColumn(..., insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))`, `fetch = LAZY`.
   - No unique constraints.
   - Enums use `@Convert(converter = EnumNames.XName.class)`, an `AttributeConverter<E,String>`, so they are plain `varchar` with no CHECK. Never use `@Enumerated` here.
   - All attribute columns are nullable and use wrapper types (`Boolean`, not `boolean`).
3. **Same names as live.** Column names and lengths are the live ones. Java attribute names are the live ones.
4. **Associations are two mappings on one column.** Each association is a writable `Long` column attribute (`customerId`) plus a read-only association with the **live attribute name** (`customer`) pointing at the **live** entity class. This keeps `root.get("customer").get("id")` a join-free FK read, and makes `referenceId`, `ScopeResolver` and `cb.isNull(root.get("collectionPoc"))` work unchanged.
5. **Standard indexes.** Every table gets:
   - `(entity_id, valid_to)` named `idx_<t>_entity`;
   - `(valid_to, valid_from)` named `idx_<t>_window`;
   - plus the per-table indexes listed in §3.2.
6. **`CountingStatements` trap.** No table name contains an existing live table name that tests count (`customer_poc_versions` does not contain `customer_pocs`).

### 3.2 Tables

| Table / entity | Business columns (live names; `→ro X` = read-only association to live X) | Extra indexes |
|---|---|---|
| `customer_versions` / `CustomerVersion` | `name` v150, `phone` v30, `email` v120, `address` v500, `credit_balance` n(14,2), `payment_term` v20, `created_at`, `region_id` →ro `region` (added with B1) | `(region_id, valid_to)` |
| `invoice_versions` / `InvoiceVersion` | `invoice_number` v40; `customer_id` →ro `customer`; `invoice_date`; `due_date` date; `payment_term` v20; `total`, `paid_amount` n(14,2); `status` v30; `notes` v500; `sales_poc_user_id` →ro `salesPoc` (User); `created_at`; `region_id` →ro `region`; `items_json` TEXT (`@Lob @Column(columnDefinition="TEXT")`) | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(sales_poc_user_id, valid_to)` |
| `payment_versions` / `PaymentVersion` | `customer_id` →ro `customer`; `amount`, `credit_applied` n(14,2); `method` v40; `notes` v300; `paid_at`; `collection_poc_user_id` →ro `collectionPoc`; `status` v20; `region_id` →ro `region` | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(collection_poc_user_id, valid_to)` |
| `payment_allocation_versions` / `PaymentAllocationVersion` | `payment_id` →ro `payment` (Payment); `invoice_id` →ro `invoice` (Invoice); `amount` n(14,2). `entity_id` is the allocation id. | `(payment_id, valid_to)`, `(invoice_id, valid_to)` |
| `promise_versions` / `PromiseVersion` | `customer_id` →ro `customer`; `amount`, `fulfilled_amount` n(14,2); `promised_date` date; `collection_poc_user_id` →ro `collectionPoc`; `notes` v1000; `status` v20; `status_overridden` bool; `override_reason` v500; `overridden_by_user_id`; `overridden_at`; `broken_notified_at`; `created_by_user_id`; `created_at`; `updated_at`; `region_id` →ro `region` | `(customer_id, valid_to)`, `(region_id, valid_to)`, `(collection_poc_user_id, valid_to)` |
| `dispute_versions` / `DisputeVersion` | `customer_id` (plain `Long customerId`, as in `Dispute`); `opened_by_user_id`; `target_type` v20; `target_id`; `reason` v2000; `proposed_change_json` TEXT; `status` v20; `admin_notes` v2000; `resolved_by_user_id`; `resolved_at`; `created_at`; `updated_at`; `region_id` →ro `region` | `(customer_id, valid_to)`, `(region_id, valid_to)` |
| `customer_poc_versions` / `CustomerPocVersion` | `customer_id` →ro `customer`; `user_id` →ro `user` (**LAZY**; live is EAGER); `poc_type` v20; `is_primary` bool (attribute `primary`); `created_by_user_id`; `created_at`. `entity_id` is the seat id. | `(customer_id, valid_to)`, `(user_id, valid_to)` |
| `product_versions` / `ProductVersion` | `name` v150; `description` v500; `price` n(14,2); `active` bool; `created_at` | — |
| `history_meta` / `HistoryMeta` | `id` smallint PK (always 1), `started_at` tz null, `baseline_completed_at` tz null | — |

**Not stored:** the `version` column (`@Version`).

**Stored but excluded from comparison:** `updated_at`, `broken_notified_at`.

**`items_json` format.** A JSON array sorted by item `id`, written with the Spring `ObjectMapper`:

```json
[{"id":90,"productId":3,"productName":"Widget","quantity":1,"unitPrice":"1200.00","lineTotal":"1200.00"}]
```

Money values are strings with `setScale(2, HALF_UP)`, so string equality is state equality. The Java type is `record ItemSnapshot(Long id, Long productId, String productName, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal)`.

### 3.3 `entity_link_versions` / `EntityLinkVersion` (many-to-many membership)

- `kind` v30 NOT NULL: `PROMISE_INVOICE` or `PROMISE_PAYMENT`. A6 may add `TASK_ASSIGNEE`, and others as registered.
- `owner_id` bigint NOT NULL (attribute `ownerId`).
- `target_id` bigint NOT NULL, mapped to the attribute **`id`**. This lets `promiseLinkPredicate`'s `invoice.get("id")` translate one for one.
- Plus the `VersionRow` columns.
- Indexes: `(kind, owner_id, valid_to)`, `(kind, target_id, valid_to)`, window.

---

## 4. Capture (write path)

### 4.1 Classes (package `com.geneinvoice.history`)

| Class | Responsibility |
|---|---|
| `HistoryModel` | Static registry built once. It holds, per type:<br>- `code` (`CUSTOMER`, `PRODUCT`, `INVOICE`, `PAYMENT`, `ALLOCATION`, `PROMISE`, `DISPUTE`, `SEAT`, later `TASK`, `PENDING_CHANGE`) and the `LINK:<kind>` keys;<br>- `rank`: CUSTOMER 10, PRODUCT 15, INVOICE 20, PAYMENT 30, ALLOCATION 35, PROMISE 40, LINK 45, DISPUTE 50, SEAT 60, TASK 70, PENDING_CHANGE 80;<br>- live class ↔ version class;<br>- the `TypeHistory` implementation;<br>- navigations (§7.3).<br>Methods: `isVersioned(Class<?> live)`, `isVersionClass(Class<?>)`, `of(Class<?> live)`, `byCode(String)`, `rootFor(Class<?> live)` (the version class when `AsOf.active()` and versioned, else live). |
| `TypeHistory<L, V extends VersionRow>` | Interface, one implementation per type in `history.types` (`CustomerHistory`, `InvoiceHistory`, …). See §4.2. |
| `HistoryListener` | Implements the `PostInsert`, `PostUpdate`, `PostDelete`, `PostCollectionRecreate`, `PostCollectionUpdate` and `PostCollectionRemove` listener interfaces. `requiresPostCommitHandling` returns false. It does **no database work**; it maps each event to keys (§4.3) and calls `HistoryTransactions.current(session).mark(...)`. If `AsOf.active()`, it throws `AsOfException.readOnly(...)`. Events on version entities and untracked entities are ignored. |
| `HistoryListenerRegistrar` | A `@Component` constructed with an `EntityManagerFactory` parameter, following the `*SchemaUpgrade` ordering convention. In `@PostConstruct` it calls `emf.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class).appendListeners(EventType.POST_INSERT, listener)`, and the same for the other five types, plus `POST_LOAD` for `LiveReadGuard`. |
| `HistoryWork` | One per Hibernate transaction. Implements `BeforeTransactionCompletionProcess` and `AfterTransactionCompletionProcess`. Fields:<br>- `String txId = UUID.randomUUID().toString()`;<br>- `Instant instant` (null until the writer first asks);<br>- `Instant pinned` (baseline only);<br>- `LinkedHashMap<Key, Flags> touched` (`record Key(String code, Long id)`, `Flags{inserted, updated, deleted, lockOnChange}`);<br>- `boolean writing`. |
| `HistoryTransactions` (`@Component`) | Holds a `ConcurrentHashMap<org.hibernate.Transaction, HistoryWork>`.<br>- `HistoryWork current(SharedSessionContractImplementor s)`: key `s.accessTransaction()`; on first use it creates the work and registers it as both a before- and an after-completion process through `((SessionImplementor) s).getActionQueue().registerProcess(...)`. After completion removes the map entry on commit **and** on rollback, exactly as Envers' `AuditProcessManager` does.<br>- `current(EntityManager em)` unwraps `SessionImplementor`.<br>- `String txId(EntityManager em)` is for the P2 publisher and **does not** bind the instant. |
| `HistoryWriter` (`@Component`) | The algorithm in §4.4. Dependencies: `Clock`, `CurrentUser`, `VersionStore`, `@Value("${app.history.strict:false}") boolean strict`. |
| `HistoryTracker` (`@Component`) | `touch(Class<?> live, Collection<Long> ids, boolean lock)`, `Propagation.MANDATORY`. Marks keys on the current `HistoryWork`. This is the only sanctioned way to version rows changed by bulk JPQL or JDBC in a runtime transaction. The reconciler and baseline also use it. |
| `HistoryOrigin` | ThreadLocal. `enum Kind { USER, RULE, APPROVAL, SYSTEM, SCHEMA, RECONCILE }`. Methods: `static <T> T run(Kind k, Long refId, Supplier<T> body)` and a `Runnable` overload, both restoring the previous value in `finally`; `static void clear()`. Without an explicit origin: USER if `currentUser.idOrNull() != null`, else SYSTEM. |
| `EnumNames` (in `versions`) | `abstract class EnumName<E extends Enum<E>> implements AttributeConverter<E,String>` plus one nested subclass per enum: `InvoiceStatusName`, `PaymentTermName`, `PaymentStatusName`, `PromiseStatusName`, `PocTypeName`, `DisputeTargetTypeName`, `DisputeStatusName`; later Task and PendingChange enums. Unknown strings read back as `null` plus a WARN log; they never throw. |

### 4.2 `TypeHistory` contract

```java
public interface TypeHistory<L, V extends VersionRow> {
    String code();  Class<L> liveType();  Class<V> versionType();  int rank();
    /** Batch snapshot of managed live entities (after flush). May run batched child queries on s
        (e.g. one query for all invoice items with product names). Pure mapping otherwise. */
    Map<Long, V> snapshotAll(Collection<L> live, SharedSessionContractImplementor s);
    /** Business equality: every stored column except id/versionId/validFrom/validTo/txId/origin/
        originRefId/actorUserId and the per-type exclusions (updatedAt, brokenNotifiedAt).
        BigDecimal via compareTo; items_json by string. */
    boolean sameState(V open, V next);
    /** Batch rehydration in input order into Historical subclasses (§7.6). */
    List<L> rehydrate(List<V> versions, Rehydration ctx);
}
```

Links are handled by `LinkHistory(String kind, Class<?> owner, String collectionAttribute)` entries in `HistoryModel`:
- `PROMISE_INVOICE`: `PaymentPromise`, `"invoices"`
- `PROMISE_PAYMENT`: `PaymentPromise`, `"payments"`

Current targets come from `select o.id, t.id from <Owner> o join o.<collection> t where o.id in :ids`.

### 4.3 Event → key mapping (the listener)

| Event source | Key(s) marked |
|---|---|
| `Customer`, `Product`, `Invoice`, `Payment`, `PaymentPromise`, `Dispute` | `(CODE, id)`. PostInsert sets `inserted`, PostUpdate sets `updated`, PostDelete sets `deleted`. |
| `PaymentAllocation` | `(ALLOCATION, id)`, flags as above. |
| `CustomerPoc` | `(SEAT, id)`, flags as above. |
| `InvoiceItem` insert, update or delete | `(INVOICE, item.getInvoice().getId())` with `lockOnChange` (the id is read from the proxy without initialising it). |
| Collection role `…Invoice.items` | `(INVOICE, ownerId)` with `lockOnChange`. |
| Collection role `…PaymentPromise.invoices` / `.payments` | `(LINK:PROMISE_INVOICE / PROMISE_PAYMENT, ownerId)`. `event.getAffectedOwnerIdOrNull()` gives the owner id. |
| Collection role `…Payment.allocations` | Nothing; the allocation entity events cover it. |
| After `HistoryWork.writing` is true | Ignored. Tracked types log ERROR; version entities are silent. |

### 4.4 `HistoryWriter.write(SessionImplementor s, HistoryWork w)`

This runs from `doBeforeTransactionCompletion`, i.e. after Hibernate's commit-time flush and before COMMIT, on the same connection and in the same transaction.

```
0. if AsOf.active(): throw AsOfException.readOnly(...)           // defence in depth
   if w.touched is empty: return
   w.writing = true
1. t = w.pinned != null ? w.pinned : w.instant(clock)  // clock.instant().truncatedTo(MICROS), bound once, here
   origin = HistoryOrigin.current(currentUser.idOrNull()); actor = currentUser.idOrNull()
2. group keys by code; process codes in rank order; ids ascending within a code (PPD-01: customer → invoices → …)
3. per code (chunks of 500 ids):
   opens   = versionStore.open(V, ids)                     // valid_to = OPEN
   lives   = ids not flagged deleted → s.get(L, id) (null if deleted in this tx)
   nexts   = history.snapshotAll(non-null lives, s)        // Java mapping inside try (see 4.6)
   for each id:
     open = opens[id]; next = nexts[id]
     if next == null:                                      // entity gone
        if open != null: open.validTo = e(open)            // e(x) = max(t, x.validFrom + 1µs)
        continue
     if open != null && history.sameState(open, next): continue
     if open != null && flags.lockOnChange && !flags.updated:
        s.lock(live, LockMode.PESSIMISTIC_WRITE); re-read open (versionStore.open) and re-compare
     from = open == null ? t : e(open)
     if open != null: open.validTo = from
     if open == null && !flags.inserted: log WARN "history.gap {code} {id}"
     next.validFrom = from; next.validTo = OPEN; stamp(next, w.txId, origin, actor); s.persist(next)
4. per LINK kind (owner ids ascending):
   current = map ownerId → set(targetId) from the link query (owners deleted in this tx → empty set)
   openRows = versionStore.openLinks(kind, ownerIds)
   for each owner: e = max(t, max(openRows.validFrom)+1µs)
     close rows whose target ∉ current (validTo = e); insert rows for targets ∉ openRows (validFrom = e)
5. s.flush()
```

`stamp` sets `txId`, `origin`, `originRefId` and `actorUserId`.

### 4.5 Concurrency argument

1. **Directly written rows.** The writer runs after the flush, so every row this transaction updated, inserted or deleted is already row-locked. A concurrent writer of the same aggregate blocks on that lock until commit, then (READ COMMITTED) reads the committed open version. At most one open version exists per entity, and intervals are contiguous.
2. **The instant is bound inside the writer**, after those locks are held. For the same entity, a later lock holder therefore gets a later-or-equal `t`, and the `e(x) = max(t, x.validFrom + 1µs)` rule absorbs equal instants and cross-instance clock skew. A zero or negative interval can never be produced.
3. **Links are versioned per `(kind, owner, target)` row.** Two transactions changing different targets touch different rows. Two transactions closing the same row both write the same `valid_to`, and the second write after the lock is released is harmless. So link-only changes need **no owner lock**, which also avoids the deadlock that late owner locks could cause.
4. **Rare case with a lock.** An aggregate whose root row was not written in this transaction but whose snapshot changed (for example, invoice items changed without an invoice UPDATE) gets `PESSIMISTIC_WRITE` on the root and a re-read. Every money path already locks the customer first (PPD-01), so this lock cannot invert lock order in practice.
5. **Rollback** (exception, `setRollbackOnly`, `recomputeAll(apply=false)` at `PaymentPromiseService.java:481`, a commit-time `ConcurrencyFailureException`): before-completion never runs, so no version is written. After-completion still removes the work.
6. **`BulkExecutor` `REQUIRES_NEW`** gives each row its own transaction, so each gets its own `HistoryWork` and `txId`.
7. **OEMIV sessions** spanning several transactions get a new `HistoryWork` per transaction, because the work is keyed by transaction and removed after completion.

### 4.6 Failure policy

- **Database errors** (lazy loads, queries, inserts) are not caught. They fail the transaction, which the database failure would have done anyway.
- **Java-side mapping errors** inside `snapshotAll`'s mapping are caught per key:
  - With `app.history.strict=false` (production default): log ERROR `history.capture.failed {code} {id}`, skip the key, and leave the open version stale. The reconciler repairs it.
  - With `app.history.strict=true` (set in `application-test.yml`): rethrow.
- **Under `AsOf`**, the listener and writer always throw `AsOfException.readOnly`.
- **Runtime bulk JPQL or native DML on versioned tables is forbidden.**
  - `ModifyingQueryGuardTest` enforces it.
  - A sanctioned exception must call `HistoryTracker.touch(type, ids, true)` in the same transaction.
  - Raw JDBC is allowed only in `*SchemaUpgrade` beans, which run before the baseline (§5.1).

### 4.7 Cost per save

About one `select` of open versions per touched type, plus one `update` (close) and one `insert` per **changed** aggregate, plus one batched child query per touched type that has children (items with products, link ids). Unchanged aggregates, such as a promise re-evaluated with no change, write nothing.

Example: `PaymentService.record` over 5 invoices with credit is about 25–35 extra statements.

---

## 5. Baseline, range and reconciliation

### 5.1 `HistoryBaseline implements SmartInitializingSingleton`

`afterSingletonsInstantiated()` runs after every `InitializingBean`, including all `*SchemaUpgrade` beans and B1's `region_id` backfill. It runs before the web server starts, before `DataSeeder`, and before `PromiseSweepScheduler.sweepOnStartup`.

1. `TransactionTemplate`: `em.find(HistoryMeta.class, (short)1, PESSIMISTIC_WRITE)`.
   - If absent, persist `{id:1}` in its own transaction. On `DataIntegrityViolationException` (another instance won the race), retry the locked find.
   - Hold this lock across step 2 by doing step 2's chunks under a first "claim" transaction that sets a `baseline_running` marker? **No:** keep it simple. Steps 2–3 run inside the locking transaction's callback, and each chunk commits through a nested `REQUIRES_NEW` `TransactionTemplate`. The meta lock is held for the whole run, so instances booting together serialise.
2. `first = meta.baselineCompletedAt == null`. For each `TypeHistory`, keyset over `select e.id from <Live> e where e.id > :last and not exists (select 1 from <Version> v where v.id = e.id and v.validTo = :open) order by e.id` (500 per chunk). Each chunk runs in a `REQUIRES_NEW` transaction:
   - `HistoryOrigin.run(SCHEMA, null, …)`;
   - `historyTransactions.current(em).pin(first ? startedAt : null)` (a pinned instant is used as `t`);
   - `historyTracker.touch(type, ids, false)`;
   - commit (the writer inserts open versions).

   `startedAt` is `clock.instant().truncatedTo(MICROS)`, computed once when `first` is true **and** the first chunk of any type is non-empty. Links are covered because touching a PROMISE also marks its `LINK:*` keys (`HistoryTracker.touch` marks the registered link keys for owner types).
3. If `first`: set `started_at` (only if any row was copied) and `baseline_completed_at = now`. Otherwise, any rows found were gaps (inserted as "from now") and are logged at WARN.
4. On an exception, log ERROR and continue startup, following the `*SchemaUpgrade` convention. This is safe here because a missing baseline **does not break saves** (the gap rule); it only narrows history.

**Contract for B1 and any future JDBC upgrade.** A `*SchemaUpgrade` that adds and backfills a column that is also a version column must run the same backfill on the version table. For B1: `update <x>_versions set region_id = :defaultRegion where region_id is null`, for every version, because the region of pre-region history is the default region. Otherwise the daily reconciler repairs open-version drift only.

### 5.2 `HistoryRange` (`@Component`)

- `Optional<LocalDate> historyStartsOn()` reads `history_meta.started_at` on each call (only in historical requests). It is not cached, because test contexts wipe the database.
- `void requireAvailable(LocalDate d)` throws `AS_OF_BEFORE_HISTORY` when `d` is before the start.

### 5.3 `HistoryReconciler` (`@Component`)

- **Schedule:** `@Scheduled(fixedDelayString = "${app.history.reconcile-interval-ms:86400000}", initialDelayString = "${app.history.reconcile-interval-ms:86400000}")`, calling `run()` wrapped in try/catch-log (the `PromiseSweepScheduler` pattern). The test value is `3600000`, and tests call `reconcile()` or `check()` directly.
- **`ReconcileReport check()`** is a dry run, read-only. Per type, in id chunks of 500:
  - load live entities and snapshot them through `snapshotAll` inside a read-only transaction;
  - load the open versions;
  - report `missing` (live without open), `drifted` (`!sameState`), `orphaned` (open without live), `duplicates` (ids with more than one open version: `select v.id from V v where v.validTo = :open group by v.id having count(*) > 1`), and `linkDrift` per link kind.
  - `ReconcileReport(int checked, List<String> missing, List<String> drifted, List<String> orphaned, List<String> duplicates, List<String> linkDrift, int repaired)`.
- **`ReconcileReport reconcile()`** runs `check()`. For each candidate chunk it runs a `REQUIRES_NEW` transaction with `HistoryOrigin.run(RECONCILE, …)` and `historyTracker.touch(type, ids, true)`, which **locks each live row** (`em.find(L, id, PESSIMISTIC_WRITE)`, or skips it when absent) before the writer re-reads and re-compares. This lock-and-recheck stops a race with a concurrent user write from reverting history. Orphans are closed through the same path. Duplicates are closed so that the most recent `valid_from` survives. It logs WARN with the counts when any is non-zero.
- **`POST /api/admin/history/reconcile`** (`@PreAuthorize(USER_MANAGE)`, `@AsOfIgnored`) returns the report JSON (§11).
- Add one thread to `spring.task.scheduling.pool.size` (R18, coordinated with A5).

---

## 6. Read context

### 6.1 `com.geneinvoice.common.view.AsOf`

```java
public final class AsOf {
    public record Point(LocalDate date, Instant instant) {
        public static Point endOfDay(LocalDate d) { return new Point(d, d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minus(1, ChronoUnit.MICROS)); }
        public static Point at(Instant i)         { Instant t = i.truncatedTo(ChronoUnit.MICROS); return new Point(LocalDate.ofInstant(t, ZoneOffset.UTC), t); }
    }
    public static Point current();                 // null when live
    public static boolean active();
    public static <T> T with(Point p, Supplier<T> body);   // restores previous in finally
    public static void with(Point p, Runnable body);
    public static void bind(Point p);              // ONLY AsOfInterceptor
    public static void clear();                    // AsOfInterceptor + tests
}
```

### 6.2 Annotations (`common.view`)

- `@AsOfAware` and `@AsOfIgnored` are both `@Target({TYPE, METHOD}) @Retention(RUNTIME)`.
- A method-level annotation overrides the class-level one.
- Class-level `@AsOfAware` covers only the GET/HEAD methods of that class.
- A non-GET method is a read only if it is itself annotated `@AsOfAware` (exports, rule preview).

### 6.3 `AsOfInterceptor implements HandlerInterceptor`

This is part of P5's ViewScope interceptor, which also fills `ViewScope.asOf`. It is registered for `/api/**` in a `WebMvcConfigurer`.

```
preHandle(req, res, handler):
  AsOf.clear()
  raw = req.getParameter("asOf"); if blank → return true
  if !(handler instanceof HandlerMethod hm) → return true
  d = strict parse yyyy-MM-dd else throw AsOfException.invalid()
  today = LocalDate.now(clock, UTC)            // the Clock bean, NOT Today.date()
  if d.isAfter(today) → throw AsOfException.future()
  mode = classify(hm, req.getMethod())         // AWARE | IGNORED | UNCLASSIFIED (rules in 6.2)
  if d.equals(today) or mode == IGNORED → res.setHeader("X-View-As-Of","live"); return true
  if mode == UNCLASSIFIED → throw isRead(req) ? AsOfException.unsupported() : AsOfException.readOnly(d)
  historyRange.requireAvailable(d)
  AsOf.bind(Point.endOfDay(d)); viewScope.setAsOf(d)
  res.setHeader("X-View-As-Of", d.toString()); return true
afterCompletion(...): AsOf.clear()
```

- Exceptions thrown from `preHandle` reach `GlobalExceptionHandler`, because `DispatcherServlet` routes them through the exception resolvers.
- `SecurityConfig.corsConfigurationSource` must add `X-View-As-Of` to `setExposedHeaders` (line 48).

### 6.4 Errors

```java
public class AsOfException extends BadRequestException {
    private final String code;
    // static invalid(), future(), beforeHistory(LocalDate start), readOnly(LocalDate d), unsupported()
}
```

`GlobalExceptionHandler` gets `@ExceptionHandler(AsOfException.class)`, returning `ApiError.of(400, ex.code(), ex.getMessage(), path)`.

| Code | Message |
|---|---|
| `AS_OF_INVALID` | "asOf must be a date (yyyy-MM-dd)" |
| `AS_OF_FUTURE` | "asOf cannot be after today" |
| `AS_OF_BEFORE_HISTORY` | "History is available from 1 Oct 2026" |
| `AS_OF_READ_ONLY` | "You are viewing 30 Jun 2026. Changes can only be made in the current view." |
| `AS_OF_UNSUPPORTED` | "This page can't be shown as of a date" |

All are 400 errors, never 401 (G26) or 409 (G19).

### 6.5 Classification of existing endpoints

| Controller | Classification |
|---|---|
| `CustomerController` | class `@AsOfAware` (list, summary, `/{id}`, `/{id}/pocs`); `POST /export` method `@AsOfAware` |
| `InvoiceController` | class `@AsOfAware`; `GET /due-date-preview` and `GET /assignable-check` method `@AsOfIgnored`; `POST /export` method `@AsOfAware` |
| `PaymentController` | class `@AsOfAware` (list, summary, `/{id}`, `/credits/{customerId}`); `POST /export` `@AsOfAware` |
| `PaymentPromiseController` | class `@AsOfAware`; `POST /export` `@AsOfAware` (`POST /recompute` is rejected in as-of mode) |
| `DisputeController` | class `@AsOfAware`; `POST /export` `@AsOfAware` |
| `ProductController` | class `@AsOfAware`; `POST /export` `@AsOfAware` |
| `DashboardController`, `AuditController` | class `@AsOfAware` |
| `DocumentController` | class `@AsOfAware` (list, count); `GET /{id}/download` `@AsOfIgnored` |
| `EmailController` | class `@AsOfIgnored`; `GET ""` (record emails) and `GET /{id}` method `@AsOfAware` |
| `AuthController`, `TableSchemaController`, `InboxController`, `GmailConnectionController`, `MailServiceEventsController`, `NotificationController`, `PocController`, `PrivilegeController`, `RoleController`, `UserController`, `HistoryController` | class `@AsOfIgnored` |

Every new controller from A1–A6, B1 and B2 must be classified. `AsOfClassificationTest` enforces this (§14).

### 6.6 "Today" seam (with P6)

```java
// B/common/time/Today.java (P6 owns the Clock bridge; B3 adds the AsOf override)
public static LocalDate date() { AsOf.Point p = AsOf.current(); return p != null ? p.date() : LocalDate.now(AppClock.get(), ZoneOffset.UTC); }
```

- `InvoiceDates.today()` returns `Today.date()`.
- `FilterPredicates.java:68` `LocalDate.now(ZoneOffset.UTC)` becomes `Today.date()`.

That makes these sites as-of-aware without further edits: `TableSchemas.overduePredicate`, `InvoiceDtos.from` (both), `InvoiceService.tiles`, `CustomerService.toDtos`, `InvoiceController.export` and `DashboardController.today()`.

Write paths never run under `AsOf` (the writer and listener guard enforce this).

**Test requirement for P6:** `MovableClock` must be a **JVM-wide singleton** (`MovableClock.SHARED`) returned by the `@Bean @Primary` in both test contexts. It follows system time until `set(...)` is called, and `reset()` returns it to system-follow. It is reset in `IntegrationTestBase.@BeforeEach`.

### 6.7 `PageResponse`

Add a trailing field `String asOf`, filled in `of(...)` from `AsOf.current()` (`null` when live) and preserved in `map`. These are the only two constructor sites (`PageResponse.java:16-28`).

---

## 7. Query layer

### 7.1 Principle

Live SQL stays byte-for-byte identical. Every change is "same call, but route through `Temporal`/`HistoryModel`", which returns the live behaviour when `AsOf` is not bound.

### 7.2 `VersionStore` (`@Component`, EntityManager JPQL; every `in` is chunked at 500)

```java
<V extends VersionRow> List<V> open(Class<V> v, Collection<Long> ids);                        // v.id in :ids and v.validTo = :open
<V extends VersionRow> List<V> validAt(Class<V> v, Collection<Long> ids, Instant p);          // v.id in :ids and v.validFrom <= :p and v.validTo > :p
<V extends VersionRow> List<V> validAtBy(Class<V> v, String attr, Collection<Long> values, Instant p);
List<EntityLinkVersion> openLinks(String kind, Collection<Long> owners);
List<EntityLinkVersion> linksAt(String kind, Collection<Long> owners, Instant p);
```

- Enum comparisons in version JPQL always use **parameters**, never enum literals.
- JPQL uses the entity name of the class.

### 7.3 `Temporal` (package `history`; static)

```java
public static Root<?> from(AbstractQuery<?> q, Class<?> live);                // q.from(HistoryModel.rootFor(live))
public static Predicate validOrNull(From<?, ?> f, CriteriaBuilder cb);         // null for live froms; else validFrom <= p AND validTo > p (cb.literal(p) → bound param)
public static Root<?> fromValid(AbstractQuery<?> q, Class<?> live, CriteriaBuilder cb, List<Predicate> where); // from + adds validity to where
public static From<?, ?> join(From<?, ?> from, String attribute, JoinType type, CriteriaBuilder cb);
public static <R> Subquery<R> subquery(CommonAbstractCriteria parent, Class<R> result, Class<?> live,
                                       CriteriaBuilder cb, SubBody<R> body);
@FunctionalInterface public interface SubBody<R> { Expression<R> build(Root<?> root, Subquery<R> sq, List<Predicate> where); }
```

`join` rules:
- **Live from** (`!isVersionClass(from.getJavaType())`):
  - `LEFT` delegates to `ColumnDef.leftJoin(from, attribute)` (existing reuse);
  - other join types use `from.join(attribute, type)` (today's behaviour).
- **Version from, non-temporal attribute** (`salesPoc`, `collectionPoc`, `user`, `region`, `product`): `ColumnDef.leftJoin`, or `from.join(attribute, type)` on the read-only association to the live target.
- **Version from, registered navigation:**
  - alias = `"t_" + attribute + "_" + type`;
  - reuse any `((SqmFrom<?, ?>) from).getSqmJoins()` entry whose `getExplicitAlias()` equals the alias;
  - otherwise `JpaEntityJoin<?> j = ((JpaFrom<?, ?>) from).join(nav.target(), SqmJoinType.from(type)); j.alias(alias); j.on(cb.and(nav.on(j, from, cb), validOrNull(j, cb)))`.

Navigations registered in `HistoryModel`:

| From version | Attribute | Target | ON |
|---|---|---|---|
| Invoice, Payment, Promise, CustomerPoc (Task and PendingChange later) | `customer` | `CustomerVersion` | `j.id = from.customerId` |
| PaymentAllocation | `payment` / `invoice` | `PaymentVersion` / `InvoiceVersion` | `j.id = from.paymentId` / `from.invoiceId` |
| Promise | `invoices` / `payments` | `EntityLinkVersion` | `j.kind = 'PROMISE_INVOICE'`/`'PROMISE_PAYMENT'` and `j.ownerId = from.id` |

`subquery`:
- `Subquery<R> sq = parent.subquery(result)`
- `Root<?> r = from(sq, live)`
- `where = new ArrayList<>(); add validOrNull(r)` if non-null
- `sq.select(body.build(r, sq, where)).where(where…)`

### 7.4 `TableQueryExecutor` changes (`B/common/query/TableQueryExecutor.java`)

- **`run`:** if `AsOf.active() && HistoryModel.isVersioned(type)`, call `runHistorical(...)`:
  1. `CriteriaQuery cq = cb.createQuery(versionClass); Root root = cq.from(versionClass)`;
  2. **no fetch joins**;
  3. the same where, order, paging and 5000 cap;
  4. `List<T> content = HistoryModel.of(type).rehydrate(results, new Rehydration(AsOf.current(), versionStore, em))`;
  5. `count` as below.

  The live body is otherwise unchanged.
- **`count`, `ids`, `aggregate`:** `Root<?> root = Temporal.from(cq, type)`. For live roots this is `cq.from(type)`.
- **`predicates(...)`:** add `Temporal.validOrNull(root, cb)` first when non-null.
- **`inScope`:** unchanged. Existence at T is guaranteed by `Records.find` before any `inScope` call.
- **`orderBy`:** unchanged. The `root.get("id")` tiebreak is the business id.

`ColumnDef.nested(assoc, attr)` becomes `(root, q, cb) -> Temporal.join(root, assoc, JoinType.LEFT, cb).get(attr)`. As a result, `customerName` is the name at T and POC names stay live.

### 7.5 Sites switched to `Temporal` (written once, correct in both modes)

| File | Change |
|---|---|
| `TableSchemas.customerOutstanding` | `Temporal.subquery(q, BigDecimal.class, Invoice.class, cb, (inv, sq, where) -> { where.add(customer.id = root.id); where.add(status <> CANCELLED); return coalesce(sum(total − paidAmount), 0); })` |
| `TableSchemas.pocSeatPredicate` | `Temporal.subquery(..., CustomerPoc.class, ...)`; the operator switch is unchanged |
| `TableSchemas.promiseLinkPredicate` | `Temporal.subquery(..., PaymentPromise.class, ...)`, then `From<?,?> target = Temporal.join(promise, association, INNER, cb)`; `target.get("id")` is unchanged |
| `ScopeResolver.customerHasPocSeat` / `customerHasInvoiceOwnedBy` | `Temporal.subquery(CustomerPoc / Invoice)` |
| `CustomerService.outstandingSubquery` / `seatSubquery` | `Temporal.subquery` |
| `DashboardService` | see §8.7 |
| B1 `regionId` column; B2 `pendingApproval`; A6 `assigneeUserId` | must use `referenceId("region")` / `Temporal.subquery` / `Temporal.join` |

`TemporalConventionTest` fails on any `.from(X.class)` or `.join("…")` where X is a versioned live class, in `common/query`, `poc`, `dashboard` or `*Service.java` outside `history/`.

### 7.6 Rehydration (`history.types.Historical*`, plus marker interface `Historical`)

`HistoricalCustomer extends Customer implements Historical`, and likewise for Invoice, InvoiceItem, Payment, PaymentAllocation, PaymentPromise, Dispute, CustomerPoc and Product. These are plain unmapped subclasses: `persist`/`merge` throw "Unable to locate persister" (verified), and Spring Data `save` surfaces that as `InvalidDataAccessApiUsageException`.

`Rehydration` holds per-call memoised batch loaders:
- `customersAt(ids)`, `invoicesAt(ids)`, `paymentsAt(ids)`, `productsAt(ids)` via `VersionStore.validAt`;
- `usersOrStub(ids)`: `userRepository.findAllById`; a missing id becomes a transient `User` with that id, `fullName="Deleted user"`, `active=false`;
- `linksAt(kind, owners)`;
- `allocationsAt(paymentIds)` via `validAtBy("paymentId")`.

Depth rules:

| Object | Populated with |
|---|---|
| `HistoricalInvoice` | All scalars; `customer` is a shallow `HistoricalCustomer` at T; `salesPoc` from `usersOrStub`; `items` from `items_json`, each item with `invoice = this` and `product` = a `HistoricalProduct` (id, name at T from `product_versions`, falling back to the snapshot name) |
| `HistoricalPayment` | Scalars; `customer` at T; `collectionPoc`; `allocations` at T, each with `payment = this` and `invoice` = a shallow `HistoricalInvoice` (scalars plus customer, no items) |
| `HistoricalPaymentPromise` | Scalars (including `updatedAt`); `customer`; `collectionPoc`; `invoices`/`payments` from the links at T, as shallow `HistoricalInvoice`/`HistoricalPayment` in a `LinkedHashSet` by id |
| `HistoricalDispute`, `HistoricalProduct` | Scalars |
| `HistoricalCustomerPoc` | Scalars; `customer` shallow; `user` from `usersOrStub` |
| B1 `region` | `em.getReference(Region.class, regionId)`, a live proxy (region names are live) |

Rehydrated DTOs carry `version = null`.

### 7.7 `Records` (`@Component`)

```java
@Transactional(readOnly = true) public <T> Optional<T> find(Class<T> live, Long id);   // live: Optional.ofNullable(em.find(live, id)); as-of: validAt → rehydrate
@Transactional(readOnly = true) public <T> List<T> findAll(Class<T> live, Collection<Long> ids);
```

**Rule:** on any read path reachable from an `@AsOfAware` endpoint, `xRepository.findById(id)` for a versioned X becomes `records.find(X.class, id)` (§8.1).

### 7.8 `LiveReadGuard implements PostLoadEventListener`

- `onPostLoad`: if `AsOf.active() && !ALLOW.get() && HistoryModel.isVersioned(Hibernate.getClass(e.getEntity()))`, throw `AsOfException.unsupported()`.
- `public static <T> T allow(Supplier<T> body)` sets a ThreadLocal flag.

This fails closed when an as-of path forgets to switch a live entity load. It does not see scalar or aggregate queries against live tables; §14's endpoint coverage test covers those.

---

## 8. Read paths outside the executor (exhaustive list for the current code)

### 8.1 `findById` → `records.find`

| Site | Change |
|---|---|
| `CustomerService.get` (:135) | `records.find(Customer.class, id)` |
| `InvoiceService.get` (:262) | `records.find(Invoice.class, id)` |
| `PaymentService.get` (:274) | `records.find(Payment.class, id)` |
| `PaymentPromiseService.get` (~:507) | `records.find(PaymentPromise.class, id)` |
| `DisputeService.get` (:145) | `records.find(Dispute.class, id)` |
| `DisputeService` customer-name lookup (:156) and `describeTarget` (:197-212) | `records.find(...)` |
| `ProductController` `GET /{id}` (:82) | `records.find(Product.class, id)` |
| `PaymentController.credit` (:103) | `records.find(Customer.class, customerId)` |
| `EmailTargets.loadUnscoped` (~:228-237) | CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE and PRODUCT go through `records.find`; USER and ROLE stay on repositories |

Rules:
- Not-found messages are unchanged ("Invoice not found"), so AUTH-08 is preserved.
- In as-of mode, `AuditController.readOrGone` does **not** fall back to "deleted records are readable": a record with no version at T is 404.

### 8.2 `CustomerService.toDtos` (:152-202)

When `AsOf.active()`:
- `historyQueries.sumOutstandingByCustomerAt(ids, p)` and `sumOverdueByCustomerAt(ids, Today.date(), p)` use the same JPQL as `InvoiceRepository.java:34-53`, on `InvoiceVersion` with the validity clause and an enum parameter.
- Seats come from `historyQueries.seatsAt(ids, p)`, rehydrated and ordered by `pocType asc, primary desc, id asc`.
- Usernames stay live.

### 8.3 `PocService.listFor` (the customer POC tab)

When `AsOf.active()`, returns `historyQueries.seatsAt(List.of(customerId), p)`.

### 8.4 `DocumentService`

- `onRecord` (:205-214): when `AsOf.active()`, replace `isFalse(deleted)` with `uploadedAt <= p AND (deletedAt IS NULL OR deletedAt > p)`.
- `toDto` (:216-227): `canEdit` and `canDelete` AND `!AsOf.active()`.

### 8.5 `EmailService`

- `list` (:322-334): add `occurredAt <= p`.
- `get`: 404 when `occurredAt > p`.
- Delivery status is current.

### 8.6 `AuditController.history` / `AuditTimelineService.timeline`

- Keep entries, recorded and derived, whose `createdAt <= p`.
- Related-record discovery (findById at :68, :85, :99 and the relation loaders) runs inside `LiveReadGuard.allow(...)`, using current relationships; labels are immutable.
- Merge B2 pending entries from `PendingChange` versions valid at p with status PENDING.

### 8.7 `DashboardService`

- Roots: `Root<Invoice>`/`Root<Payment>`/`Root<PaymentAllocation>` become `Root<?>` via `Temporal.fromValid(cq, X.class, cb, where)`.
- Joins at `:119, :184-185, :194, :200` become `Temporal.join(x, "…", JoinType.INNER, cb)`, with generics widened to `From<?, ?>` (the `Collected` record and the `balance`/`open` helpers).
- `idsInScope` (`:311-317`) becomes `Temporal.subquery`.
- The results:
  - D1: invoices created after T are absent even when backdated.
  - D4/D5: a payment voided after T is ACTIVE at T, and its allocations are present at T.
  - D3: customer names are taken at T.
- `coverage` and `paidAgainstBookOnly` are unchanged (R14).
- `DashboardController.today()` comes via `Today.date()`.

---

## 9. Contracts with the other decisions

### 9.1 A2/A5: rule preview (B3's "rule evaluation … as of a date")

`GET /api/automation/rules/{id}/matches?asOf=&page=&size=` is `@AsOfAware` and guarded by the rules view privilege. It runs `queryExecutor.run(triggerLiveClass, schema, query, callerScope + condition.toPredicate(schema), List.of())`. Under `AsOf` the result follows automatically: versions, today = D, region and book at T. It writes nothing.

Response: a `PageResponse<{id, label}>` with `asOf`.

### 9.2 P2/A5: event-point evaluation (recommended, not required by B3)

- The outbox row gets `history_tx_id varchar(36)` (nullable) from `historyTransactions.txId(em)`, called in the publisher's `beforeCommit`. This **must not** call anything that binds the instant.
- The consumer computes `point = historyQueries.transactionPoint(liveClass, entityId, txId)`, i.e. `select max(v.validFrom) from <V> v where v.id = :id and v.txId = :tx`, falling back to the event's `occurred_at` when the transaction changed no snapshot.
- It evaluates conditions and resolves A4 placeholders inside `AsOf.with(AsOf.Point.at(point), …)` in a read-only `TransactionTemplate`, under the owner principal (P3).
- It performs actions **after** leaving `AsOf.with`. The writer and listener guard enforce the order.
- Actions run inside `HistoryOrigin.run(RULE, ruleId, …)`.
- Scheduled and "Run now" events evaluate live.

### 9.3 A6: tasks

- `Task` is registered as a `TypeHistory` (`task_versions`, with the task's columns plus `customer_id` and `region_id`).
- Assignees are either user ids (link kind `TASK_ASSIGNEE`) or a child entity with its own id (its own version table, like seats).
- "My tasks" at T uses `Temporal.subquery`/`join`.
- Tasks must be written through the ORM.

### 9.4 B2: pending changes

- `PendingChange` is registered as a `TypeHistory` (`pending_change_versions`). B2's own target-id column is stored as `target_id` if B2 named it `entity_id`.
- Payload JSON is stored with passwords already stripped (critic §3.9).
- The approve/apply path runs inside `HistoryOrigin.run(APPROVAL, pendingChangeId, …)`.
- `pendingApproval`/`hasPendingChange` is an EXISTS through `Temporal.subquery(PendingChange.class, …)`.

### 9.5 B1: regions

- The `region` association and `regionId` column are added to the listed version tables.
- The region move is an ORM cascade in PPD-01 lock order: all versions share one transaction and instant.
- The backfill contract is in §5.1.
- Region-scoped predicates use `root.get("region").get("id")`.

### 9.6 P5 and P6

- P5's interceptor contains the §6.3 logic.
- P6 provides `Today`/`AppClock` with the §6.6 override and the shared `MovableClock`.

---

## 10. Transaction boundaries (summary)

| Operation | Transaction |
|---|---|
| Capture | Inside the saving transaction (REQUIRED); Hibernate before-completion after the final flush, same connection. Rollback leaves nothing. |
| Outbox `txId` | P2's `beforeCommit` in the same transaction; creates `HistoryWork` if absent; no instant bound. |
| Baseline | Startup (`SmartInitializingSingleton`). The `history_meta` row lock is held for the run; `REQUIRES_NEW` per 500-id chunk. |
| Reconcile | `check()` read-only per chunk. Repair is `REQUIRES_NEW` per chunk, with `PESSIMISTIC_WRITE` on each live row and a re-check. |
| As-of read | The existing `@Transactional(readOnly = true)` service and executor methods. Rehydration needs no lazy loading (all batch). OEMIV is not required. |
| Interceptor | One short read of `history_meta` only for historical requests. |
| Event-point evaluation | A read-only `TransactionTemplate` inside `AsOf.with`; actions in their own transaction afterwards. |

---

## 11. Endpoints and JSON

```
GET /api/invoices?asOf=2026-06-30&regionIds=1,2&filter=overdue:eq:true&sort=customerName,asc&page=0&size=20
200  X-View-As-Of: 2026-06-30
{"content":[{"id":901,"invoiceNumber":"INV-20260612-0007","customerId":5,"customerName":"Acme (name on 30 Jun)",
  "overdue":true,"daysOverdue":18,"total":1200.00,"paidAmount":0.00,"balance":1200.00,"status":"UNPAID", …}],
 "page":0,"size":20,"totalElements":1,"totalPages":1,"sort":"customerName,asc",
 "appliedFilters":["overdue:eq:true"],"lockedFilters":[],"asOf":"2026-06-30"}
```

`/summary`, `/export`, `/{id}`, the tabs and the dashboard all honour the same `asOf`, with the header and no body change except `PageResponse.asOf`.

```
GET /api/users?asOf=2026-06-30  → 200  X-View-As-Of: live   {... "asOf": null}

GET /api/invoices/901?asOf=2026-01-01  → 404 {"status":404,"error":"Not Found","message":"Invoice not found","path":"/api/invoices/901", …}

PATCH /api/invoices/901?asOf=2026-06-30
→ 400 {"status":400,"error":"AS_OF_READ_ONLY","message":"You are viewing 30 Jun 2026. Changes can only be made in the current view.","path":"/api/invoices/901"}

GET /api/<unclassified>?asOf=2026-06-30 → 400 {"error":"AS_OF_UNSUPPORTED","message":"This page can't be shown as of a date"}
GET /api/invoices?asOf=2026-9-1          → 400 {"error":"AS_OF_INVALID", …}
GET /api/invoices?asOf=2099-01-01        → 400 {"error":"AS_OF_FUTURE", …}
GET /api/invoices?asOf=2025-01-01        → 400 {"error":"AS_OF_BEFORE_HISTORY","message":"History is available from 1 Oct 2026"}

GET /api/history/range   (@AsOfIgnored, any authenticated user; HistoryController)
→ 200 {"historyStartsOn":"2026-10-01","latestAsOf":"2026-09-25","timezone":"UTC"}   // historyStartsOn null = no lower bound; latestAsOf = clock today − 1

POST /api/admin/history/reconcile   (USER_MANAGE, @AsOfIgnored)
→ 200 {"checked":1840,"missing":0,"drifted":0,"orphaned":0,"duplicates":0,"linkDrift":0,"repaired":0}
```

---

## 12. Frontend

P5 owns sending `asOf` and `regionIds` (only when set, G11; formatted with `DateFormat('yyyy-MM-dd')` on the picked day, G21). B3 adds:

1. **`F/core/scope/as_of.dart`:**
   - `isHistoricalProvider` (`viewScope.asOf != null`);
   - `historyRangeProvider` (`FutureProvider` on `GET /api/history/range`), which bounds the picker: `firstDate = historyStartsOn ?? DateTime(2000)`, `lastDate = latestAsOf`;
   - `enum AsOfApplied { live, historical }`;
   - `AsOfApplied checkAsOf(Response r, DateTime? asOf)`: when `asOf` is set, header `x-view-as-of` must equal the formatted date (historical) or `'live'` (live). A missing or different header throws `AsOfMismatch('This screen can\'t show history')`.
   - It is called explicitly in providers, not in a Dio interceptor (G23): `tablePageProvider`, `tableSummaryProvider`, the 8 dashboard providers, and the detail and tab providers.
2. **`PagedResult.fromJson`** parses nullable `asOf`. `DataTableScaffold` shows an info strip "Showing current data — history isn't kept for this list" when historical and `result.asOf == null`, and hides bulk actions while historical (export stays).
3. **Read-only gating:** AND `!isHistorical` into `customer_detail_screen.dart:195`, `invoice_detail_screen.dart:166`, `payment_detail_screen.dart:120`, the `canManage` values of the four list screens, `PromisesTab`, `DisputesTab`, `canUploadDocumentsProvider` and `canSendEmailProvider`. Also fold it into the permission `Provider<bool>`s.
4. **`AppShell` body banner:** "Viewing 30 Jun 2026 (end of day, UTC) — read only · Back to today". The last action clears `asOf`.
5. **Dashboard:** header copy "as of 30 Jun 2026"; `asOf ?? todayUtc()` at `dashboard_providers.dart:215-224`, `dashboard_charts.dart:562-588` and `dashboard_tables.dart:116,203`.
6. **Errors:** codes starting `AS_OF_` render `apiErrorMessage`. A detail 404 while historical renders `RecordUnavailable` with "This record did not exist on <date>".
7. **`test/support/fake_backend.dart`:** routes may return `FakeResponse(data, headers: {...})`. Existing routes are unchanged (no headers). New tests:
   - `as_of_test.dart` (`checkAsOf` cases);
   - a table provider test (param sent only when set; throws when the header is missing; the live strip);
   - a widget test showing invoice detail with no edit actions plus the banner while historical;
   - a dashboard aging link test using the as-of date.

   `flutter analyze` must stay at 0.

---

## 13. Edge cases (the expected behaviour is binding)

| Case | Behaviour |
|---|---|
| Created and deleted on the same day | Absent at the end of that day. |
| Backdated invoice created after T | Absent at T. |
| Payment voided after T | ACTIVE at T, with its allocations; collected in D4/D5 (both branches). |
| `replaceItems` or `cancelWithRefund` after T | Items, total and status at T. |
| Customer renamed after T | Name at T in lists, sorts, `customerName` filters, D3 and DTOs. |
| Region moved after T | Region at T; current grants decide visibility (a user with read only in the old region sees it at T, not live). |
| POC reassigned or seat removed after T | The book at T includes the record; the seat is shown in the POC tab at T. |
| User deleted after T | Their name shows "Deleted user" in as-of DTOs; the `salesPocName` join yields null. |
| Product renamed after T | Line product name at T (from `product_versions`). |
| Pending change requested before T and approved after | Listed as outstanding at T; the record's values at T are the pre-approval values. |
| Document uploaded after T / deleted after T | Hidden / shown (visibility is current). |
| Email sent after T | Hidden; delivery status is current. |
| Two transactions in the same µs, or clock skew or a backwards clock | `from = max(t, open.validFrom + 1µs)`; no zero or negative intervals. Tests only move the clock forward. |
| A save that changes nothing snapshotted (`@Version` bump, `updatedAt` only) | No version is written. |
| Row with no open version (raw insert, legacy row) at write time | A version is inserted from now; WARN `history.gap`; the save succeeds. |
| Capture mapping bug in production | The save succeeds; ERROR log; the reconciler repairs. In tests (strict) the test fails. |
| As-of plus bulk, email send, create, update or recompute | 400 `AS_OF_READ_ONLY`; nothing changes. |
| As-of on a configuration list | Live, with header `live` and body `asOf:null`. |
| `asOf` on a request to a non-`/api` path | Ignored. |
| ThreadLocal hygiene | Cleared in `preHandle` and `afterCompletion`; `AsOf.with` restores the previous value. New endpoints must not stream or run async under `AsOf` (none do today). |
| Two Spring test contexts | `AsOf` is static and `MovableClock` is a shared singleton, so both contexts see the same state. |
| More than 5000 rows unpaged | Same cap and total semantics as live (R18). |
| Null ordering and collation | Same database and same `ORDER BY`, so as-of order equals live order. |

---

## 14. Tests (all H2; the existing 119 + 24 + 61 stay green)

### 14.1 Infrastructure (`T/IntegrationTestBase.java`)

- `@BeforeEach resetTransactionalData`:
  - start with `AsOf.clear(); HistoryOrigin.clear(); MovableClock.SHARED.reset();` then `historyTestSupport.clearVersions()` (bulk `delete from <V>` for every version entity plus `EntityLinkVersion` and `HistoryMeta`; bulk JPQL, so no listener events);
  - then the existing deletes, plus **`disputeRepository.deleteAll()`** before `customerRepository.deleteAll()` (inject `DisputeRepository`).
- `@AfterEach historyIsInStep()`: `assertThat(historyReconciler.check().problems()).as("history drift").isEmpty()`. This proves capture coverage for every write path the whole suite exercises.
- `application-test.yml`: `app.history.strict: true`, `app.history.reconcile-interval-ms: 3600000`.
- Helper `AsOfScenario` (package `history`): builds a timeline **through services**, with `MovableClock` at 10:00Z on D1–D6:
  - D1: customer A (region R1 when B1 lands); invoices X (1000) and Y (500), X's sales POC is Sam; a SUCCESS seat for Sam.
  - D2: payment 400 on X; a promise of 600 linked to X.
  - D3: rename A; move A to R2 (B1); remove Sam's seat; rename a product.
  - D4: approve a dispute that voids the payment; cancel Y.
  - D5: B2 pending change on X (when B2 lands).
  - D6: approve it.

### 14.2 Capture slice

- **`HistoryCaptureTest`**, one case per write path: customer create, update and delete; seat add, remove and set-primary; invoice create (with credit), update, cancel, `replaceItems`, `cancelWithRefund`; payment record, update, void, `updateAmount`; `CreditLedger` apply and refund; promise create, update, cancel, override add and clear, `applyShares` link-only change, sweep; dispute open, approve, deny and notes; product CRUD; each bulk action. It asserts that the previous version closed at the transaction instant, exactly one new open version with the expected attributes, and `origin`, `actor` and `txId` (shared within the transaction).
- **`HistoryRollbackTest`:** an exception, `setRollbackOnly`, and `recomputeAll(false)` write no versions; a bulk `REQUIRES_NEW` gives distinct `txId`s.
- **`HistoryTimeTest`:** all versions of one transaction share `t`; a same-µs second transaction gives `validFrom = prev + 1µs`; the clock moved back never gives `valid_to <= valid_from`; a no-op save writes nothing.
- **`HistoryConcurrencyTest`:** two threads relink the same promise to different invoices behind a latch (each thread calls `actAs`; hold time under H2's ~2 s lock timeout), giving one open link row per target and no duplicates. Two payments on one customer give contiguous intervals.
- **`HistoryGuardTest`:** a save inside `AsOf.with(...)` throws and changes nothing; strict mode rethrows a mapping error (inject through a test-only `TypeHistory` hook in the single `@Import`, no `@MockBean`).
- **`VersionShapeTest`:** for each live and version pair, every persistent attribute of the live entity (JPA metamodel) exists on the version with the same Java type, or as an `<assoc>Id` Long plus a read-only `<assoc>`, or is in the explicit exclusion list (`version`, collections handled by JSON or links).
- **`RoundTripTest`:** a fully populated live entity → `snapshotAll` → `rehydrate` → reflective comparison of every metamodel attribute (BigDecimal via `compareTo`).
- **`VersionTablesHaveNoConstraintsTest`:** `information_schema` shows only the PK on `*_VERSIONS`, no FK, unique or CHECK, and no H2 `ENUM` column types.
- **`ModifyingQueryGuardTest`:** reflect over Spring Data repositories and fail on `@Modifying` in the repository of a versioned entity. Scan `src/main/java` for `createNativeQuery`, `executeUpdate`, `JdbcTemplate` and `nativeQuery = true` outside `*SchemaUpgrade`, `history/`, and the existing `GmailConnectionRepository`.

### 14.3 Baseline and reconciliation slice

- **`HistoryBaselineTest`:** seed live rows through repositories, clear versions and meta, call `historyBaseline.run()` twice. Expect one open version per row with `valid_from = started_at`, `origin=SCHEMA`, and an idempotent second run. A later gap gives a version from now. An empty database leaves `started_at` null. `asOf` before the start gives 400.
- **`HistoryReconcilerTest`:** a bulk JPQL `update Invoice set notes = …` via `em.createQuery` in a test transaction, then `check()` reports drift and `reconcile()` writes one RECONCILE version. An orphan version is closed; a missing one is inserted; lock-and-recheck skips a row changed concurrently.

### 14.4 Read-context slice

- **`AsOfContextTest`:** invalid, future, today→live, before-history and valid dates; the header values; the ThreadLocal is cleared (a request with `asOf` followed by one without is live); `PageResponse.asOf` echo.
- **`AsOfClassificationTest`:** enumerate `RequestMappingHandlerMapping.getHandlerMethods()` under `/api`. Every GET/HEAD is AWARE or IGNORED. Every non-GET AWARE method is in the allow-list {six exports, rule preview}.
- **`AsOfReadOnlyTest`:** every non-GET mapping not allow-listed or IGNORED, sent with a historical `asOf`, returns 400 `AS_OF_READ_ONLY`, with no row counts or versions changed. Housekeeping POSTs (mark notification read) succeed.

### 14.5 Query slice

- **`AsOfListTest`** (scenario): the invoices, customers, payments, promises, disputes and products lists at D1–D6: values; `totalElements`; `overdue` with today = D; a `relative` preset versus D; sort and filter by `customerName` at T; `outstanding` at T; `successPocUserId` at T; `invoiceId` link filter at T; tiles and `/summary`; `POST /export` CSV at T.
- **`AsOfDetailTest`:** each `GET /{id}` at T (items after `replaceItems`, allocations after void, links, `updatedAt`); 404 before creation; flags `canEdit=false`; POC tab, documents tab, emails tab and audit tab at T.
- **`AsOfDashboardTest`:** D1–D8, including the backdated invoice created after T, void after T in both branches, ageing versus D and names at T.
- **`AsOfScopeTest`:**
  - a SALES_POC reassigned after T sees the old invoices at T and not live;
  - a customer login sees its own records only;
  - (with B1) read-in-R1-only sees A at D2 but not at D4 or live.
- **`AsOfParityTest`:** capture live responses at clock day D (after the writes), set the clock to D+1 10:00, request `asOf=D` and compare JSON. It covers every schema × every filterable column × one valid operator per type (generated from `TableSchema`), every sortable column, every `/summary`, D1–D8, and every detail. Everything must be identical except `asOf` and `version`.
- **`AsOfEndpointCoverageTest`:** for every AWARE GET, using a table of sample URLs built from the scenario: 200 with header equal to the date, a `CountingStatements.reads("_versions", …) > 0` check, and a response that **differs** from live for the scenario (C-1 safeguard).
- **`HistoricalObjectsAreWriteProofTest`:** `invoiceRepository.save(historical)` throws and the live row is unchanged.
- **`LiveReadGuardTest`:** `invoiceRepository.findById` inside `AsOf.with` throws `AS_OF_UNSUPPORTED`; `allow` permits it.
- **`AsOfQueryCountTest`** (admin invoice page of 20, default sort): `reads("invoice_versions") == 2` (page plus count); `reads("customer_versions") == 1`; live `GET /api/invoices` gives `reads("_versions") == 0`.

### 14.6 Cross-decision tests

Added by each owner's slice: `AsOfApprovalsTest` (B2), `AsOfTasksTest` (A6), `AsOfRulePreviewTest` (A2/A5), and `EventPointEvaluationTest` (A5: pay the invoice after the event, drain with `SecurityContextHolder.clearContext()`, and the rule still sees the triggering state).

---

## 15. Implementation slices (sequential; each ends green: `mvn -q test`, `flutter analyze` 0, `flutter test`)

| # | Slice | Contents | Done when |
|---|---|---|---|
| S0 | Prerequisites (with P6) | `Clock` bean, `Today`/`AppClock`, `InvoiceDates.today()` delegating, `MovableClock.SHARED` in the single `@Import`, `AsOf` class (bind is a no-op until S3 wires the interceptor), `Today` override | Existing tests green |
| S1 | Capture core | Version entities (§3), converters, `HistoryModel`, `TypeHistory` for Customer, Product, Invoice, Payment, Allocation, Promise plus links, Dispute and Seat; `VersionStore`; listener, registrar, `HistoryWork`, `HistoryTransactions`, `HistoryWriter`, `HistoryTracker`, `HistoryOrigin`; reconciler `check()`; test infrastructure (§14.1) and §14.2 tests | The 119 tests plus `@AfterEach` consistency pass |
| S2 | Baseline, reconcile, range | `HistoryMeta`, `HistoryBaseline`, `reconcile()`, scheduler, `HistoryRange`, `HistoryController` (`/api/history/range`, `/api/admin/history/reconcile`); §14.3 tests | Green |
| S3 | Read context | Annotations on all controllers (§6.5), `AsOfInterceptor`, `AsOfException` plus handler, CORS header, `PageResponse.asOf`; §14.4 tests | Green |
| S4 | Query layer | `Temporal`, executor swap, `ColumnDef.nested`, §7.5 sites, `Historical*` plus rehydration, `Records`, `LiveReadGuard`, the §8.1 swaps; `AsOfListTest`, `AsOfDetailTest` (lists and details), `AsOfParityTest`, write-proof, guard and count tests | Green |
| S5 | Remaining read paths | §8.2–8.7 (`toDtos`, seats, documents, emails, audit, dashboard, capability flags); `AsOfDashboardTest`, `AsOfScopeTest`, `AsOfEndpointCoverageTest` | Green |
| S6 | Frontend | §12 | analyze 0, tests green |
| S7 | Contracts | Applied inside B1, B2, A6 and A5 as those land: §9, plus the §14.6 tests | Green |

---

## 16. Volume, risk and open questions

**Volume, approximately:**
- Backend main: about 3,300 new LOC (history core 900, capture 500, 9 version entities plus converters 700, per-type histories plus `Historical*` 900, baseline and reconcile 300) plus about 450 changed LOC.
- Backend tests: about 1,900 LOC.
- Frontend: about 350 LOC plus 250 LOC of tests.

**Regression risk:**
- Live SQL is unchanged; `Temporal` returns the live paths when nothing is bound.
- The write path gains bounded statements that cannot fail a save except through a database failure.
- The `@AfterEach` consistency check turns any capture gap into a failing test immediately.

**Rejected alternatives:**
- **Envers plus `@Subselect` mirrors** (viable and probe-verified, but rejected):
  - Validity-strategy saves fail on any row without an open revision (E4). That makes the startup baseline and every future JDBC upgrade a save-breaking dependency, while the codebase convention is to log upgrade failures and continue.
  - The owner `_aud` revend update runs without a row lock on link-only changes (`PaymentPromise` has no `@Version`).
  - The proposed early `getCurrentRevision` in `beforeCommit` can invert per-entity revision order and produce two visible versions.
  - `_aud` enum CHECKs double R9.
  - It needs annotations on every live entity, `hibernate.cdi.extensions`, and an auto-enabled filter with a resolver; the read path has two mechanisms (mirrors plus `AuditReader`); and `AuditReader` returns live-class instances that are not write-proof.
  - Its read-side ideas survive here.
- **Generic JSON snapshots plus an in-memory evaluator:**
  - a second query engine that must match `FilterPredicates` operator by operator;
  - a 50,000-row scan cap that turns valid lists into 400s;
  - text collation differs from Postgres;
  - a hand-written dashboard twin;
  - O(N_T) cost per request.
  - Its transaction-id and reconcile ideas survive here.
- **Replaying `audit_logs`:** incomplete (12+ unaudited paths) and non-queryable TEXT.
- **Database triggers or temporal tables:** H2 runs tests and `azurefree`, and `ddl-auto` does not manage triggers.
- **Explicit service-hook capture:** it drifts as soon as a write path is added.

**Open questions for the PRD owner (the design already takes the default shown):**
1. **"Every list":** users, roles, regions, rules and notifications answer live under as-of, labelled as current. Versioning them later uses the same mechanism.
2. **Rights at T:** current rights are applied to the record's region and book at T. The strict alternative (rights then and now) is impossible without rights history.
3. **Pre-go-live dates** are refused with 400, not approximated.