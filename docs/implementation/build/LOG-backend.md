## BE-01 — Foundations: clock, view scope, provenance, schema support

**Built**
- `B/common/time`: `ClockConfig` (the only main `Clock` bean `appClock`, not `@Primary`), `WallClock.UTC`, `AppClock` (static bridge, starts on `Clock.systemUTC()`, `set` rejects null), `AppClockBridge` (package-private `@Component`), `Today.date()` / `Today.liveDate()`.
- `B/common/scope`: `AsOfPoint` (`endOfDay`, `at`), `ViewScope` (`LIVE_ALL`, `narrow`, `historical`, `withAsOf`; regionIds kept as an unmodifiable sorted set), `ViewScopeHolder` (ThreadLocal; `current/with/set/clear/requireLive`), `AsOf` facade, `@AsOfAware`, `@AsOfIgnored`, `CodedBadRequestException` (`public final String code`; constant + factory `regionIdsInvalid()` for `REGION_IDS_INVALID`), `AsOfException` (5 coded factories, messages exactly as §6.17, dates as `d MMM yyyy`), `AsOfSupport` interface, `NoHistoryYet` (the only `AsOfSupport` bean; always `AS_OF_UNSUPPORTED`), `ViewScopeInterceptor` (the only reader of `regionIds`/`asOf`; `HEADER = "X-View-As-Of"`, public `enum Mode` and `public static classify(HandlerMethod, httpMethod)` so the inventory test uses the exact runtime rule), `ViewScopeConfig` (registers it on `/api/**`).
- `B/common/origin`: `WriteOrigin` (record with `actorUserId`; factories `user/automation/approval/schema/reconcile/system`, `withActor`), `WriteOrigins` (ThreadLocal; `current(cu)`, `actor(cu)`, `callWith`, `run`, `clear`).
- `B/common/schema/SchemaSupport` (`product`, `isNullable`, `enforceNotNull`, `widen` with the new H2 native-ENUM branch via `information_schema.columns.dtd_identifier` ⋈ `information_schema.enum_values`), `B/common/DatabaseErrors` (`isUnavailable` moved verbatim, `isConcurrency`), `B/common/Fit.to`.
- Clock retrofit: every file in the §5.1 table. Beans take an injected `Clock`; entity callbacks use `AppClock.now()`; `InvoiceDates.today()` and `FilterPredicates.relative` use `Today.date()`; `InvoiceNumbers` uses the live clock; JWT (`JwtService`), `WebhookSignature`, `MailServiceEventsController` and `ApiError` use `WallClock.UTC`.
- The 20 controllers annotated exactly per §5.2.3 / §5.2.4.
- `GlobalExceptionHandler` handles `CodedBadRequestException` (error = code); `SecurityConfig` CORS exposes `X-View-As-Of`; `PageResponse` gains `regionIds`, `bookRegionIds` (both `[]` for now) and `asOf` (date string when a point is bound, else null).
- `audit_logs` gains nullable `region_id`, `origin` (varchar 12), `rule_id`, `pending_change_id` (index `idx_audit_pending`), `approved_by_user_id`. `AuditService` stamps origin (kind name, null for USER), `rule_id`, `pending_change_id`, `approved_by_user_id` (checker) from `WriteOrigins.current`; new overloads `record(..., reason, regionId)` and `recordLinked(..., pendingChangeId, reason)`; the reason is fitted with `Fit.to`. `AuditTimelineService.Entry` carries the four provenance fields; `AuditEntryDto` gains `origin`, `ruleId`, `pendingChangeId`, `approvedByUserId`, `approvedByUsername`.
- `EmailSchemaUpgrade` and `InvoiceSchemaUpgrade` delegate to `SchemaSupport`; `MailServiceEventHandler.databaseUnavailable` delegates to `DatabaseErrors.isUnavailable` (the bean is still never injected).
- Tests: `T/MovableClock` (`SHARED`, `set/advance/reset`, follows system time until set); `IntegrationTestBase` has exactly one `@Import(IntegrationTestBase.TestBeans.class)`, the nested `@TestConfiguration` imports `RecordingMailTransport.Config` and declares `@Bean @Primary Clock testClock()`; reset now clears the SecurityContext, `ViewScopeHolder`, `WriteOrigins`, resets the clock and calls `AppClock.set(MovableClock.SHARED)`.
- New tests: `common/time/ClockTest` (7), `common/time/ClockReadGuardTest` (2, plain), `common/scope/ViewScopeTest` (13), `common/scope/ViewScopeInventoryTest` (3, as-of half), `common/schema/SchemaSupportTest` (5, plain, private in-memory H2).

**Deviations from DESIGN.md (with reasons)**
- `LocalDate.now(clock, UTC)` (§5.1/§5.2.2 pseudo-code) does not exist in the JDK; used `LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)` (same meaning).
- `InvoiceSchemaUpgrade` binds `WriteOrigin.schema()` around its backfill audit row, so that row's `origin` is `SCHEMA` rather than `SYSTEM` (what the kind exists for). `enforceDueDateNotNull`/`isNullable` there are deleted in favour of `SchemaSupport.enforceNotNull(conn, "invoices", "due_date")`; on H2 the DDL is now `alter column … set not null` (per §3.10) and a NULL count is checked first.
- `SchemaSupport` methods declare `throws SQLException` (as the code they replace did); `enforceNotNull` returns true when the column is already NOT NULL; the H2 ENUM branch never drops values the enum no longer has (appends missing ones after the existing ones, preserving ordinals).
- `WriteOrigin.user()` factory added (what `WriteOrigins.current` returns for a signed-in caller with nothing bound).
- `ClockReadGuardTest` additionally forbids `Clock.systemUTC()/systemDefaultZone()/system()` outside `common/time`, and self-tests its patterns.
- `IntegrationTestBase.resetTransactionalData` is now `protected` (was package-private) so `ClockTest` can prove the next reset re-points `AppClock` after a simulated drift boot.
- Customer redaction: `withoutPocIdentity` nulls `approvedByUserId` (so `approvedByUsername` is null) for customer logins (D17: customers never see approval identities). The rest of §5.13's customer redaction belongs to BE-15/BE-19.
- Audit-provenance assertions (origin/rule/pending change/checker stamping, `recordLinked`, the region overload, 500-char fitting, DTO exposure) live in `ClockTest.auditRowsSayWhoAndWhatWasBehindAChange`, because BE-01's file list names no audit test.
- The interceptor/classification tests use a standalone MockMvc with `@Controller` probe classes nested in `ViewScopeTest` (excluded from component scanning by Boot's `TestTypeExcludeFilter`), so the app context gains no test endpoints.

**API-contract changes versus §6:** none. Delivered as specified: `X-View-As-Of` header (exposed via CORS), coded `ApiError.error` for `AS_OF_*` and `REGION_IDS_INVALID`, `PageResponse.regionIds/bookRegionIds/asOf`, `AuditEntryDto.origin/ruleId/pendingChangeId/approvedByUserId/approvedByUsername`. Until BE-09a every AWARE endpoint answers a past `asOf` with 400 `AS_OF_UNSUPPORTED` (fail-closed, by design); `PageResponse.regionIds` is `[]` until BE-02.

**Known gaps / deferred**
- `NotificationService.fit` and `GmailConnectionService.fit` still have their own copies; not switched to `Fit.to` because neither file is in BE-01's list (harmless; can be folded in by a later slice that touches them).
- `RunAs` and principal changes are BE-02 (D6); the region half of `ViewScopeInventoryTest` is BE-02/BE-03.
- `/api/admin/history/reconcile` and the later read-POSTs are already in the inventory test's literal allow-lists; they do not exist yet.

**Tests:** backend 149 run, 0 failures, 0 errors, 0 skipped (119 baseline + 30 new); mail-service 24, 0 failures.

## BE-02 — Regions core: data, rights, principal, scope

**Built**
- `B/region`:
  - `Region` (`regions`: `code` v20 unique `uk_region_code`, immutable; `name` v120; `active`; `is_default` → attribute `defaultRegion`; `@Version`; `created_at`/`updated_at` from `AppClock`; `DEFAULT_CODE`; a static `idOf(Region)` that reads a LAZY proxy's id without loading it).
  - `UserRegionGrant` (`user_region_grants`: `user_id` with `@OnDelete(CASCADE)`, `region_id`, `access_level`/`record_scope` `@Enumerated`, `granted_by_user_id`, `granted_at`; `uk_user_region`, `idx_urg_region`). `User` has no mapping to its grants.
  - `AccessLevel` (`permits`, static `atLeast` → EnumSet), `RecordScope`, top-level `GrantRow`.
  - `RegionRights`: `none/forStaff/forCustomer/has/anywhere/regionsWith/grantedRegions/holdsAnyIn/grantIn/effectiveAnywhere/effectiveIn/isCustomer`. It is immutable plain data.
  - `RegionAccessQueries`: `holds`, `withAccess`, `holdersIn`.
  - `RegionRepository`: `findByIsDefaultTrue`, `findByCode`, `existsByNameIgnoreCaseAndIdNot`, `clearDefaultExcept`, `markDefault`, `deleteByCodeNot`.
  - `UserRegionGrantRepository`: every §5.6.3 query, plus `findByUserIdAndRegionId` and `deleteByRegionCodeNot`.
  - `RegionService`: `defaultRegion()` (never cached), `requireActive(id)` (404 unknown; 400 "Region X is inactive").
  - `RegionGrantService.ensure`: an upsert to exactly that level and scope; not bounded by the granter.
  - `RegionSchemaUpgrade`: the §3.11 steps 1–7.
- Schema changes:
  - Nullable `region_id` (LAZY, `updatable = false`) with an `idx_<table>_region` index on `customers`, `invoices`, `payments`, `payment_promises` and `disputes`.
  - A `@PrePersist` safety net on `Invoice`, `Payment` and `PaymentPromise`. `Dispute`'s region is set by its service.
  - `users.region_backfill_pending` (`@ColumnDefault("true")`, NOT NULL, Java default false).
- `Privileges`: `REGION_VIEW` and `REGION_MANAGE` (appended to `ALL`), plus `GLOBAL`, `REGION_LEVEL`, `requiredLevel` and `isRegional`.
- Principal:
  - `AppUserDetails` has a 3-argument constructor and `getRights()`. The 2-argument constructor is deleted.
  - `AppUserDetailsService`:
    - `loadUserByUsername` is `@Transactional(readOnly = true)`.
    - `principalFor(User)` costs one grants query for staff and one `CustomerRepository.regionIdOf` for a customer login.
    - `principalFor(Long)`.
    - `buildAuthorities(User, RegionRights)` returns `ROLE_x` plus `effectiveAnywhere()`. The 1-argument form is deleted.
  - `CurrentUser` gains `rights()` (returns `none()` without a principal), `hasIn`, `regionsWith`, `requireIn` (403), `requireReachable` (404), `canAssignPocIn` and `canAssignPocAnywhere`. `canAssignPoc(UserRepository)` now delegates to `canAssignPocAnywhere()` until BE-04a deletes it.
- `ScopeResolver` is rewritten to §5.6.4, without TASK:
  - Enums `Access`, `Reach`, `Tier`, `Book` and `Kind` (CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE).
  - `RegionCoverage` and `Scope(predicates, lockedFilters, coverage)` with `regionIds()` and `bookRegionIds()`.
  - `scope(kind, access, reach, extras…)`, `tier(...)`, `requireVisible(...)`, `canSeePoc`, `isAssignableAs`, `isAssignableIn` and `canSeeEverything`.
  - It works in memory: no `UserRepository`, and no SQL (tested).
  - Staff now see disputes only in regions where they hold a grant.
- `PageResponse.of(…, Scope)` fills `lockedFilters`, `regionIds` and `bookRegionIds`. It is used by the customer, invoice, payment and promise `page()` methods and by `DisputeController.list`.
- By-id reads use `scopeResolver.requireVisible(Kind, id, region, msg)`:
  - `CustomerService.get`, `update` and `delete` (the customer-login 403 is kept).
  - `InvoiceService.get`, `update` and `cancel`.
  - `PaymentService.get` and `update`.
  - `PaymentPromiseService.get`.
  - `DisputeService.get`: staff now go through `requireVisible`, and the customer 403 is kept.
  - `InvoiceService.previewDueDate`: `requireVisible(CUSTOMER)` plus `requireIn(INVOICE_MANAGE, region)`.
  - `PaymentController.credit` now goes through `customerService.get`.
- Creates:
  - `CustomerService.create` implements the full §5.6.6 region rule:
    - use `req.regionId`, else the only active region with CUSTOMER_MANAGE, else the only one left after `regionIds` narrowing;
    - otherwise 400 `fieldErrors.regionId`;
    - then `requireReachable` (404), `requireIn` (403) and `requireActive` (400).
  - `CustomerDtos.CustomerCreateRequest` gains a trailing `regionId`. It keeps a 7-argument constructor and a `@JsonCreator` canonical constructor.
  - `InvoiceService.create` and `PaymentPromiseService.create` now lock the customer (`findByIdForUpdate`, F1). `PaymentService.record` already did.
  - `DisputeService.open` locks the caller's customer.
  - All four copy `customer.getRegion()` onto the new record.
- `CustomerRepository.regionIdOf`, `UserRepository.findByIdForUpdate`.
- `UserController.create` is the BE-02 bridge: it calls `ensure(saved, DEFAULT, APPROVE, ALL)`. `DataSeeder` does the same when it creates `admin` or `cashier`.
- `T/IntegrationTestBase`:
  - It autowires `disputeRepository`, `regionRepository`, `grantRepository`, `regionGrantService` and `principals`.
  - `actAs` and `as` use `principals.principalFor`.
  - Helpers `defaultRegion()`, `region(code)`, `grant(...)` and `asking(...)`; `user()` grants DEFAULT/APPROVE/ALL; `customer(name[, email])` sets the default region; new `customer(name, Region)`.
  - The reset deletes disputes before customers. Its tail deletes grants and then regions other than DEFAULT, re-activates DEFAULT and makes it the default, and sets `admin`/`cashier` grants to exactly DEFAULT/APPROVE/ALL.
- New tests:
  - `T/privilege/PrivilegeClassificationTest`: 5 tests, plain.
  - `T/region/RegionRightsTest`: 13 tests, plain.
  - `T/auth/PrincipalRightsTest`: 17 tests. They cover:
    - one grants read per Bearer request;
    - no SQL at all spent deciding scope;
    - `/api/emails` and `/api/inbox` return 200 for a multi-region user with a real token;
    - fail-closed behaviour without a principal;
    - the `/me` and `/login` shape, and `regions: []` for a customer login;
    - a READ-only grant refused by `@PreAuthorize`;
    - the bridge grant on `POST /api/users`;
    - list narrowing and `regionIds`;
    - by-id 404 outside granted regions, with `regionIds` ignored by by-id reads;
    - a mixed FULL/BOOK view (no chip, `bookRegionIds`) and a pure book view (today's chip);
    - creates copying the customer's region;
    - customer-create region selection, 403/404/400 cases, and nothing written on a refusal.
  - `T/region/RegionSchemaUpgradeTest`: 4 tests, all passing.
    - Backfill (children take their customer's region, not the default; an orphan dispute goes to DEFAULT), NOT NULL enforced, audit row with origin SCHEMA.
    - Legacy grants: flagged staff get exactly one grant, idempotent across repeated runs; customer logins and unflagged users get none.
    - A second run changes nothing.
    - A real `ddl-auto=update` boot of a pre-regions H2 database (columns, tables and the flag dropped after a first boot). Admin gets a DEFAULT/APPROVE/ALL grant and `AppClock` is restored in `finally`.

**Deviations from DESIGN.md (with reasons)**
- **`/api/auth/login` and `/me` now return `regions`, and `privileges = rights.effectiveAnywhere()`.** The design lists `AuthController`/`LoginResponse` under BE-05, but BE-02's acceptance test `PrincipalRightsTest` asserts "the `/me` shape, and `[]` for a customer". Leaving `privileges` role-only would also make `/me` promise privileges the server now refuses (authorities are the union). The shape is exactly §6.2: `RegionAccessDto{id, code, name, active, defaultRegion, level, scope, privileges}`, regions sorted by code. BE-05 has nothing left to do there.
- **`PrivilegeClassificationTest` does not yet assert `REGION_LEVEL.get(CHANGE_APPROVE) == APPROVE`.** The privilege is added in BE-13, which must add that line (§4.9: each slice that adds a privilege extends the test). The test pins the full current catalogue instead: 10 global, 13 READ, 10 MANAGE.
- `GLOBAL` holds the 10 global privileges that exist now. The three AUTOMATION_* privileges join it in BE-16.
- `RegionSchemaUpgrade`:
  - It counts a child only when it actually received a region, via an extra `exists (customer has a region)` clause. Without it, an orphan dispute was counted twice.
  - The audit row is written only when rows were backfilled or grants added. Creating the default region on a fresh database files nothing.
  - It is package-private, like the other upgrade beans, and takes the `Clock` bean for `created_at`/`granted_at`.
- **`@Modifying` repository methods on the region repositories carry `@Transactional`.** The test reset calls them outside a transaction.
- **Disputes have no `@PrePersist` region safety net.** A dispute has only a plain `customerId`, so there is nothing to copy the region from; `DisputeService.open` sets it, as §3.4 says.
- **`ViewScopeTest.anAsOfOfTodayIsLive` (a BE-01 file) now expects `regionIds == [DEFAULT]`.** BE-01 asserted `[]`, which was the documented placeholder until BE-02.
- **`CustomerService.update` and `delete` also moved from `requireInBook` to `requireVisible`.** This is the same read-side visibility; the region MANAGE checks stay in BE-04a.

**API-contract changes versus §6:** none.
- `/login` and `/me` `regions` and union `privileges` land early, exactly as §6.2 specifies.
- `PageResponse.regionIds` and `bookRegionIds` are now populated on the five scoped lists.
- `POST /api/customers` accepts `regionId` (§6.4).

**Known gaps / deferred (by design to later slices)**
- Region/DTO fields and `"region"` in fetch lists, the dashboard split and `ScopeInfo`, email bulk and inbox narrowing, and the region half of `ViewScopeInventoryTest` are BE-03.
- MANAGE/APPROVE `requireIn` on update, delete, cancel and record paths, book-aware create checks, customer-first lock order on update paths, `resolveIds(access)`, and deleting `CurrentUser.canAssignPoc` are BE-04a. Until then a create needs only role privileges plus the endpoint gate, as before.
- Per-region assignability (`requireAssignable(…, region)`), `RecordAccess` flags, the notification region, `AuditController.readOrGone` by region, and deleting the dead unscoped methods are BE-04b.
- Region CRUD, `RegionGrantService.replace` and `UserAccountService` (which replaces the `UserController` bridge), `regionLevel` and the REGIONS schema are BE-05.
- `RunAs` is BE-12.

**Tests:** backend 188 run, 0 failures, 0 errors, 0 skipped (149 before + 39 new: 5 + 13 + 17 + 4). mail-service 24, 0 failures.

### BE-02 review fixes

**Fixed**
- **(high) A customer login could write to other customers.** `ScopeResolver.requireVisible` returned at once for a customer login, as §5.6.4 said ("callers keep today's own-customer checks"). But `CustomerService.update`/`delete`, `InvoiceService.update`/`cancel`/`previewDueDate` and `PaymentService.update` had no own-customer check of their own. Before regions, `requireInBook`'s own-customer predicate was their only guard.
  - Fix (a small deviation from §5.6.4): for a customer login, `requireVisible` now requires ownership and answers 404 otherwise, as `requireInBook` did.
  - A CUSTOMER record is compared by id, with no SQL. Any other kind runs one `inScope` count with the own-customer predicate.
  - The `get()` paths keep their explicit own-customer checks, which run first, so `CustomerService.get` still answers 403 (X1).
  - Test: `PrincipalRightsTest.aCustomerLoginThatMayManageStillReachesOnlyItsOwnCustomer`. A customer login whose role also holds CUSTOMER/INVOICE/PAYMENT_MANAGE gets 404 on each of the six calls against another customer: PUT and DELETE customer, PATCH invoice, cancel invoice, due-date preview, and PATCH payment. Nothing changes, and the same calls still work on its own customer. With the ownership throw removed, the test fails (200 instead of 404).
- **(low) No test covered region scoping of payments, promises and disputes.** Added `PrincipalRightsTest.paymentsPromisesAndDisputesAreScopedByRegionAsListsAndById`: a READ/ALL user in NORTH and SOUTH, with one record of each kind in DEFAULT, NORTH and SOUTH.
  - Each list shows only the 2 granted regions, and `regionIds` narrows it to 1.
  - A by-id read in DEFAULT gives 404 for each kind.
  - A by-id read in a granted region gives 200, even when narrowed to another region (I3).
- **(low) The bridge grant was not atomic with the user save.** `UserController.create` is now `@Transactional`, so the save and `RegionGrantService.ensure` commit or roll back together. BE-05's `UserAccountService` replaces this bridge anyway.
  - Test: `PrincipalRightsTest.aUserWhoseGrantCannotBeGivenIsNotCreatedEither`. With no default region, the call answers 5xx and no account is left behind. Without `@Transactional`, the test fails.
- **(low) A failed Postgres `uk_regions_default` index skipped the whole backfill.** The index DDL in `RegionSchemaUpgrade.ensureDefaultRegion` now has its own try/catch: it rolls back, logs a WARN and returns the region id, so steps 2–7 still run. The index is retried on the next start. No test was added, because the suite runs on H2, where this branch does not run.

**Rejected:** none.

**Tests:** backend 191 run, 0 failures, 0 errors, 0 skipped (188 + 3 new in `PrincipalRightsTest`, now 20). mail-service 24 run, 0 failures, 0 errors.

## BE-03 — Regions: "which regions am I asking about" in figures and DTOs

**Built**
- **Record DTO region fields (§6.5, without flags).** `regionId`, `regionCode` and `regionName` are appended as trailing components, each `@JsonInclude(NON_NULL)` at component level. The rest of each record's serialisation is unchanged. They are on `CustomerDto`, `InvoiceDto`, `InvoiceSummary`, `PaymentDto`, `PromiseDto` and `DisputeDto`.
  - They are filled from `Region.idOf/codeOf/nameOf`. `codeOf` and `nameOf` are new null-safe static helpers on `Region`.
  - The factories are `CustomerService.toDtos`, `InvoiceDtos.*.from`, `PaymentDtos.PaymentDto.from`, `PaymentPromiseService.toDto` and `DisputeService.toDto`.
  - Audit JSON from `InvoiceDto`/`PaymentDto` now carries the region too. That is harmless, since before and after hold the same values.
- **`"region"` added to the fetch lists** of every `page()`/`allMatching()` for customers, invoices, payments and promises, and of `DisputeController.list`. List DTO mapping therefore does no per-row region loads.
- **`TableSchemas`.** Two private helpers, `regionId()` and `regionName()`:
  - `regionId`: REFERENCE, `referenceKind "region"`, not sortable, path `region.id`.
  - `regionName`: TEXT, sortable, path `nested(region, name)`.
  - Both are on CUSTOMERS, INVOICES, PAYMENTS, PROMISES and DISPUTES, placed before `createdAt` (or at the end for payments).
- **`TableSchemaController`.** `VIEW_PRIVILEGE` is now `Map.ofEntries` of `List<String>`, and `mayRead` is any-of. The behaviour is unchanged; BE-05 adds `regions → [REGION_VIEW, USER_MANAGE]`.
- **Dashboard (§5.12).**
  - `DashboardDtos`:
    - `Coverage` gains `MIXED`.
    - New `ScopeInfo(regionIds, bookRegionIds, asOf)` is a trailing `scope` on `MonthlySeries`, `OutstandingByAge`, `TopOutstanding` and `TopPaying`.
  - `DashboardService`:
    - The invoice figures use `coverage(scope)`: OWN, BOOK if there is a chip, MIXED if `coverage.mixed()`, otherwise ALL. They also use `scopeInfo(scope)`.
    - "Collected" (`collectedByMonth`, `topPaying`) replaces `paidAgainstBookOnly()` with a `Split`:
      - `sums` is `forPayments().coverage().full()`. Over those regions it sums whole payments.
      - `allocations` is `pay.book ∪ inv.book`. Over those regions it sums allocations with `invoice.region ∈ allocations ∧ (invoice ∈ idsInScope(inv) ∨ payment ∈ idsInScope(pay))`, passing the outer `cq`.
      - A customer login keeps today's payment path.
      - Months are added part by part. `topPaying` runs each part with `limit`, merges per customer, re-sorts (desc amount, then name) and limits. By I1 the parts are disjoint in payments and customers.
      - Coverage: A only → ALL, B only → BOOK, both → MIXED, neither → BOOK if either scope has a chip, else ALL. `scope` is `(A ∪ B, B, asOf)`.
- **Email bulk.** `EmailTargets.bulkIds` uses a new private `sendable(kind)` = `scope(kind, VIEW, SELECTED, EMAIL_SEND)` for the five regional kinds. A region where EMAIL_SEND is not effective (for example a READ grant) is not offered.
- **Inbox (§5.6.7).**
  - New `B/email/EmailRegionPredicate`:
    - `onRecipient(regions)` returns a `PredicateFactory` on an `EmailRecipient` root.
    - `of(emailPath, q, cb, regions)` is an OR of "`entityType` not regional" (USER, ROLE and PRODUCT always kept) and, per regional kind, `entityType = X ∧ entityId IN (select id from X where region.id in :regions)`.
    - It iterates an `EnumMap`, so the SQL is deterministic. It never generates `in ()`.
  - `InboxController`:
    - A private `scope()` builds a `ScopeResolver.Scope` from the own-TO-rows predicate, plus `EmailRegionPredicate` only when `regionIds` is set. The regions used are `ViewScope.narrow(grantedRegions)`.
    - The list returns `PageResponse.of(…, scope)`: `regionIds` is the answered set, and `[]` for a customer login, which ignores `regionIds`.
    - Inbox **bulk** `resolveIds` uses the same scope (§6.7: all bulk POSTs honour `regionIds`).
  - `InboxService.unreadCount(userId, regionsOrNull)`: `null` → the existing derived count; otherwise a Criteria count with the same predicate.
- **`ViewScopeInventoryTest` (region half).**
  - The class is now `public`, and `SETS` is `public static final`, so `RegionParamCoverageTest` can check itself against it.
  - It has literal `SETS`, `BY_ID`, `WRITES` and `GLOBAL_EXEMPT` lists covering all 127 current endpoints.
  - `everyEndpointSaysWhetherItAnswersWhichRegions` requires each endpoint to be in exactly one list.
  - `theInboxAndNotificationsAnswerWhichRegionsThoughTheyAnswerLive` checks that those four endpoints are in SETS and classified IGNORED.
- **New tests**
  - `T/region/RegionParamCoverageTest` (30 tests):
    - 27 parameterised rows, one per SETS endpoint narrowed so far: the 5 record lists, 4 summaries, 5 exports, 4 record bulks, 5 dashboard figures, email bulk, and the inbox list, unread count and bulk.
    - `theRowsCoverEverySetEndpoint`: rows ∪ `PENDING_ROWS` = SETS, and they are disjoint. `PENDING_ROWS` holds `/api/pocs/assignable`, `/my-scope`, `GET /api/notifications`, `/unread-count` and `POST /api/notifications/bulk`, all for BE-04b.
    - `emailBulkSelectsOnlyTheRegionsWhereTheCallerMaySend`.
    - `everyRegionalListHasTheRegionAsAColumn`: schema kind, filter `regionId:eq`, sort `regionName`.
    - Fixtures are NORTH and SOUTH customers with different amounts, each with a paid and an unpaid invoice, a payment, a promise and a dispute.
  - `T/region/RegionBookTest` (4 tests, read side):
    - A mixed FULL/BOOK sales lead: rows, no chip, `bookRegionIds`, region codes, tiles, and 404 on another person's SOUTH invoice.
    - A pure book user keeps the chip. Narrowing to an ungranted region, or to a region where the user holds nothing, gives no chip.
    - The dashboard as MIXED: collected = NORTH payment sums + SOUTH own allocations, checked by hand as 100 + 120. Also `topPaying` merged, billed MIXED, and the single-region answers BOOK/ALL.
    - F2 on one region: admin, a PAYMENT_VIEW+SCOPE_OVERRIDE-only role, SALES_POC, and two collection-only custom-role users. Collected, `topPaying` totals and payment counts, coverage and `bookRegionIds` all equal the pre-change formula.
  - Mutation-checked:
    - Dropping the inbox predicate fails the inbox list and bulk rows.
    - Dropping the allocation part fails both dashboard book tests.
    - Dropping EMAIL_SEND from `sendable` fails the email bulk test.

**Deviations from DESIGN.md (with reasons)**
- **Region DTO fields use component-level `@JsonInclude(NON_NULL)`, not class-level.** A class-level annotation would also drop today's null fields (`salesPoc`, `pocMissing`…) from existing responses (P7).
- **The inbox bulk POST is narrowed as well, and classified in SETS.** §5.6.7 names only the list and unread count, but §6.7 says every bulk POST honours `regionIds`. Otherwise "select all" on a narrowed inbox would mark rows the user cannot see.
  - For the same reason, `POST /api/notifications/bulk` is classified in SETS and left in `PENDING_ROWS` for BE-04b.
  - `POST /api/inbox/mark-all-read` and `/api/notifications/mark-all-read` are classified WRITES and are not narrowed. They take no filter; see Known gaps.
- **Classification choices the design left implicit:**
  - `/api/emails/people` (staff directory picker) and `/api/emails/sync` are in GLOBAL_EXEMPT.
  - `/api/emails/context`, `/delivery`, `POST /api/emails/preview`, `/api/invoices/due-date-preview` and `/assignable-check` are in BY_ID.
  - `POST /api/promises/recompute` is in WRITES. Its region narrowing is BE-04a.
- **The inbox `PageResponse.regionIds` is `narrow(grantedRegions)` also when `regionIds` is absent.** Nothing is filtered in that case, but it is the set of regions "answered", consistent with the record lists.
- **Record exports gain no region CSV column.** Not specified; the exports are narrowed only.

**API-contract changes versus §6:** none. The additions match §6.5, §6.9 and §6.0:
- the region fields;
- the `regionId`/`regionName` columns with reference kind `region`;
- the dashboard `scope` and `MIXED`;
- inbox `regionIds` narrowing, with `PageResponse.regionIds`.

**Known gaps / deferred**
- `CustomerAuditSnapshot.regionId` and all capability flags are BE-04b (§5.6.9).
- The inbox region predicate drops emails about a record that has since been deleted (its id is in no region's table) whenever `regionIds` is set. Without `regionIds` they are still listed.
- `mark-all-read` on the inbox and notifications ignores `regionIds`, as above.
- Notifications `regionIds` filtering, the POC endpoints, and their coverage rows are BE-04b (`PENDING_ROWS`).

**Tests:** backend 227 run, 0 failures, 0 errors, 0 skipped. That is 191 before plus 36 new: `RegionParamCoverageTest` 30, `RegionBookTest` 4, `ViewScopeInventoryTest` +2 (now 5). mail-service 24 run, 0 failures, 0 errors.

## BE-04a — Regions: writes and bulk

**Built**
- **Write checks in the record's region (§5.6.6).** Every write now follows the same order: load (and lock), then visibility (404), then `ViewScopeHolder.requireLive()` (400), then `currentUser.requireIn(privilege, region)` (403).
  - `CustomerService.update` and `delete` go through a new private `requireManageable(id)`, which does `requireVisible`, `requireLive` and `requireIn(CUSTOMER_MANAGE)`. `create` also calls `requireLive`.
  - `InvoiceService.create`, `PaymentService.record` and `PaymentPromiseService.create`: after the F1 customer lock, they call `requireLive`, then the **book-aware** `scopeResolver.requireVisible(CUSTOMER, …)` (404, SC-12), then `requireIn(INVOICE_MANAGE / PAYMENT_MANAGE / PROMISE_MANAGE, customer.region)`.
  - `InvoiceService.update` and `cancel` use a new private `lockForWrite(id)`. The customer comes first (§5.11.5): an unlocked `invoiceRepository.customerIdOf(id)`, then `customerRepository.findByIdForUpdate`, then `invoiceRepository.findByIdForUpdate`. Then `requireVisible`, `requireLive` and `requireIn(INVOICE_MANAGE)`. The Sales POC change uses `canAssignPocIn(invoice.region)` and keeps its 400 message.
  - `PaymentService.update` calls `requireVisible`, `requireLive` and `requireIn(PAYMENT_MANAGE)`. The Collection POC change uses `canAssignPocIn(payment.region)` (400).
  - `PaymentPromiseService` has a new private `getForWrite(id, privilege)`: `promiseRepository.customerIdOf`, then lock the customer, then `promiseRepository.findByIdForUpdate` (new, `@Lock(PESSIMISTIC_WRITE)`). Then `requireVisible(PROMISE)`, `requireLive` and `requireIn(privilege, promise.region)`. The region is read after the customer lock (SC-7).
    - `update`, `cancel` and `reassignCollectionPoc` use PROMISE_MANAGE.
    - `override` and `clearOverride` use PROMISE_OVERRIDE.
    - The Collection POC change uses `canAssignPocIn(promise.region)`.
  - Promise re-evaluation takes the customer lock (SC-7):
    - `reevaluateForCustomer` locks the customer, re-entrantly, before it re-reads and saves the promises.
    - `sweepOverdue` iterates the new `findCustomerIdsWithOverdueOpen(today)` (distinct, ascending) and locks each customer before evaluating.
    - `recomputeAll` covers only `ViewScope.narrow(regionsWith(PROMISE_OVERRIDE))`, through the new `findCustomerIdsWithLivePromisesInRegions(regions)` (ascending). An empty set is a no-op. Each customer is locked before its promises are read.
  - `DisputeService.approve` and `deny` take the customer first (CF-8, SC-19) in `mustBePending`: `disputeRepository.customerIdOf`, then lock the customer, then `findByIdForUpdate` the dispute, then `requireVisible(DISPUTE)` (404), then "already resolved" (400), then `requireLive`, then `requireIn(DISPUTE_MANAGE, dispute.region)` (403). Before this, staff could decide a dispute in any region, even one they could not see.
- **Bulk `resolveIds(access)` (§5.6.5).**
  - Each `idsMatching(query, limit)` became `idsMatching(query, limit, Access, String... alsoRequired)`, which uses `scope(kind, access, SELECTED, extras)`. This covers the customer, invoice, payment and promise services.
  - Each controller's `resolveIds(req, access, extras…)` is called as follows:
    - invoice CANCEL: MANAGE
    - REASSIGN_SALES_POC and REASSIGN_COLLECTION_POC: MANAGE with POC_ASSIGN
    - promise CANCEL: MANAGE
    - customer ADD_POC: MANAGE with POC_ASSIGN
    - every `/export`: VIEW
  - Rows that the caller can see but may not change are skipped with `BulkExecutor.NOT_REACHABLE`.
  - The coarse checks now call `currentUser.canAssignPocAnywhere()`. They keep 400 on invoices, payments and promises, and 403 on customers (D-46).
- `CurrentUser.canAssignPoc(UserRepository)` is **deleted**. The now-unused `UserRepository` fields were removed from `InvoiceController`, `PaymentController`, `PaymentPromiseController`, `CustomerController`, `InvoiceService`, `PaymentService` and `PaymentPromiseService`.
- Repositories:
  - `InvoiceRepository.customerIdOf`
  - `DisputeRepository.customerIdOf`
  - `PaymentPromiseRepository.findByIdForUpdate`, `customerIdOf`, `findCustomerIdsWithLivePromisesInRegions` and `findCustomerIdsWithOverdueOpen`. The now-dead `findCustomerIdsWithLivePromises` and `findOverdueOpen` were removed.
- Tests:
  - `T/region/RegionAccessMatrixTest` (9 tests). **Rita** has role `RITA_CASHIER`, which is CASHIER plus PROMISE_MANAGE, PROMISE_OVERRIDE, DISPUTE_VIEW and DISPUTE_MANAGE. She holds NORTH READ/ALL and SOUTH MANAGE/ALL, and nothing in WEST. Each region has a customer, two invoices, a payment, a promise and a dispute. The tests cover:
    - Lists: all regions give N+S; `regionIds=N` gives N; `regionIds=W` gives an empty 200 with `regionIds: []`. By-id: N and S give 200, W gives 404.
    - Invoice PATCH and cancel: N 403, W 404, S 200.
    - Payment PATCH, promise PUT, override (POST and DELETE), and cancel: N 403, W 404, S 200.
    - Customer PUT and DELETE: N 403, W 404, and PUT on S gives 200.
    - POST invoice, payment and promise: N 403, W 404, S 200, and each new record carries `regionId = S`.
    - Customer create:
      - `regionId=N` gives 403.
      - A missing region, with two managed regions, gives 400 `fieldErrors.regionId`.
      - An inactive region gives 400.
      - S gives 200 with region S.
    - Dispute approve and deny: N 403, W 404. Approve on S gives 200, and the change is applied.
    - `/recompute` reports only SOUTH, `regionIds=N` reports `[]`, and admin gets all three.
    - Bulk CANCEL over N, S and W: only S succeeds, and N and W are `NOT_REACHABLE`. Promise bulk reassign with select-all takes only S.
    - `asOf` on a PATCH gives 400 `AS_OF_READ_ONLY` and changes nothing.
    - **After every refusal, the rows of customers, users, invoices, invoice_items, payments, allocations, promises, disputes and seats are byte-identical, and the audit and notification counts are unchanged.**
  - `T/region/CustomerLoginRegionTest` (3 tests):
    - `regionIds` for another region, or its own, changes nothing in the lists, summary or by-id reads, and `regionIds` is `[]`.
    - Another customer's invoice gives 404, and their dispute gives 403 (X1), in the same region and in another.
    - A login follows a region move. With a real Bearer token issued before the move, a CUSTOMER_MANAGE customer login can still edit its own customer after it moves N to S, because the rights are rebuilt per request, and `principalFor(...).rights.grantedRegions()` is `{S}`. A principal built before the move is refused with 403, which proves the check reads the region.
  - `T/region/RegionBookTest`: new test `aPureBookPocCannotCreateAnythingForACustomerOutsideTheirBook`.
    - A BOOK-only sales POC whose role may write invoices, payments and promises POSTs each one for a customer outside their book. Each call gives 404, and the invoices, items, payments, allocations, promises and audit counts are unchanged (SC-12).
    - The same three POSTs for a customer in their book give 200.
- Mutation-checked. Each of these makes the new tests fail:
  - removing `requireIn` from `lockForWrite`, from the dispute decision or from `requireManageable`;
  - removing the book-aware `requireVisible` from invoice create;
  - widening `recomputeAll` to all granted regions;
  - bulk CANCEL with `Access.VIEW`.

**Deviations from DESIGN.md (with reasons)**
- **The design's unlocked first read is a scalar `customerIdOf` query, not an entity load.** §5.6.6 says "unlocked read" for invoices, "unlocked `get`" for promises and "unlocked `findById`" for disputes.
  - Loading the entity unlocked puts it in the persistence context. The later `findByIdForUpdate` then returns that managed instance without refreshing it, so the before-snapshot and the status checks (INV-1, TBL-07, "already resolved") would read pre-lock state.
  - To avoid that, `customerIdOf` was added to `InvoiceRepository` and `DisputeRepository` (not in the slice's file list; unavoidable) and to `PaymentPromiseRepository`.
  - Visibility (404) is checked right after the row lock, still before any 400 or 403. The answers are the same as the design's order, and the region is always read under the customer lock.
- **`sweepOverdue` now counts every promise its evaluation changed.** Before, a second overdue promise of the same customer was evaluated a second time and not counted. Only the log line reads the count. It now evaluates once per customer, under that customer's lock.
- `reevaluateForCustomer`, `recomputeAll` and `sweepOverdue` skip a customer that no longer exists, instead of evaluating an orphan.
- `CustomerService.create` also calls `requireLive` (D2 defence in depth; the §5.6.6 row does not list it).
- **Rita's role is `RITA_CASHIER`, not the seeded CASHIER.** CASHIER holds none of PROMISE_MANAGE, PROMISE_OVERRIDE or DISPUTE_MANAGE, which the matrix's promise, override, recompute and dispute rows need. Roles outlive a test, so the seeded role must not be modified. Every other privilege is exactly CASHIER's.
- `CustomerLoginRegionTest` simulates the region move with JDBC `UPDATE`s of `region_id` on the customer and its rows, because the endpoint arrives in BE-08. `CustomerRegionMoveTest` (BE-08) covers the real move.

**API-contract changes versus §6:** none.
- Writes now answer 403 or 404 per region, as specified in §6.4 and D15.
- Bulk results skip rows that the caller can read but not write, as `NOT_REACHABLE`.
- `/api/promises/recompute` honours `regionIds` and the caller's PROMISE_OVERRIDE regions.

**Known gaps / deferred (by design to BE-04b)**
- `requireAssignable(userId, type, region)` on create and update paths, and in the `CustomerController` bulk ADD_POC pre-check. These still use the two-argument, role-only form.
- `PocService.add`, `remove` and `setPrimary` have no `requireIn(POC_ASSIGN, region)` yet. That is the matrix's "Seat add on N → 403" row.
- `InvoiceController.assignableCheck(customerId)`.
- `CUSTOMER_DELETED` does not carry the region yet.
- Document and email region checks, `RecordAccess` flags (`canManage` etc.), and the dead unscoped methods (`PaymentService.list`, `PaymentPromiseService.listForCustomer`/`listForInvoice`, `DisputeService.list`).
- The matrix rows for flags, documents, email and seats.

**Tests:** backend 240 run, 0 failures, 0 errors, 0 skipped. That is 227 before plus 13 new: `RegionAccessMatrixTest` 9, `CustomerLoginRegionTest` 3, `RegionBookTest` +1 (now 5). mail-service 24 run, 0 failures, 0 errors.

### BE-04a review fixes

**Fixed**
- **(medium) `/api/promises/recompute` ignored the POC book and customer-login confinement.** Confirmed: `recomputeAll` walked every customer with live promises in `narrow(regionsWith(PROMISE_OVERRIDE))`.
  - It now reaches exactly what an override by hand (`getForWrite(id, PROMISE_OVERRIDE)`) would reach:
    - In each region, the tier is `scopeResolver.tier(PROMISE, VIEW, r, PROMISE_OVERRIDE)`.
      - FULL regions: every customer with live promises, as before.
      - BOOK regions: only customers with a live promise whose collection POC is the caller, via the new `PaymentPromiseRepository.findCustomerIdsWithLivePromisesOf(regions, pocId)`.
    - A customer login gets only its own customer, in `regionsWith(PROMISE_OVERRIDE)`, and `regionIds` is ignored (D17, the same as its lists).
  - A customer's live promises are evaluated together (payments are shared out across them). So if any of a customer's live promises is out of the caller's reach, the customer is left alone entirely. The check (`mayRecompute`) runs under the customer lock, after the promises are re-read. This is the conservative reading. The alternative was to save a promise the caller cannot see, or to save only some of a joint evaluation.
  - This deviates from the §5.6.6 row, which says only "Regions = narrow(regionsWith(PROMISE_OVERRIDE))". That row conflicted with B1 (book per region) and SC-12.
  - Test: `RegionAccessMatrixTest.recomputeReachesOnlyThePromisesTheCallerCouldOverrideByHand`.
    - A book-only COLLECTION_POC (SOUTH MANAGE/BOOK) previews and applies. Only the customer whose promises are all theirs is shown and rewritten.
    - A customer shared with another collector, other people's customers and NORTH are left at `fulfilledAmount` 1.00.
    - A customer login whose role holds PROMISE_OVERRIDE sees only its own two promises, even when it asks about `regionIds=NORTH`.
    - Mutation-checked: removing the `mayRecompute` filter fails the test.
- **(medium) No test pinned the customer-first lock order.** Confirmed. New `RegionAccessMatrixTest.everyWriteLocksTheCustomerBeforeItsOwnRow`:
  - A raw H2 session holds `select … from customers where id = ? for update`, and the write runs on another thread.
  - The test waits until `information_schema.sessions.blocker_id` shows the write waiting. If the write finishes first, it fails with "finished without waiting".
  - A third session with `lock_timeout 200` must then lock the write's own row at once. If it times out, the test fails with "locked its row before its customer".
  - Then the holder rolls back, and the write must complete with 200.
  - Covered writes:
    - invoice PATCH and cancel
    - promise PUT, override and clearing an override
    - dispute approve (SOUTH, as rita) and deny (NORTH, as admin)
    - `recompute?apply=true`
    - `sweepOverdue` (on a new overdue promise)
  - Mutation-checked. Each of these fails the test:
    - deleting the customer lock from `InvoiceService.lockForWrite`, `PaymentPromiseService.getForWrite`, `DisputeService.mustBePending`, `recomputeAll` or `sweepOverdue`;
    - swapping `lockForWrite` to take the invoice row before the customer ("Timeout trying to lock table INVOICES").
  - It passed three repeated runs.
- **(low) Bulk assertions were weak.** Confirmed for the POC half.
  - The promise bulk now reassigns to a *different* collector (`colin`). It asserts that the SOUTH promise's POC changed and the NORTH one did not.
  - New: payment bulk `REASSIGN_COLLECTION_POC` with select-all. Only the SOUTH payment succeeds and is changed; NORTH keeps its POC.
  - New: customer bulk `ADD_POC` over N, S and W ids. Only S succeeds, N and W are `NOT_REACHABLE`, and colin's only seat is on SOUTH.
- **(low) Customer update/delete authorised against a region read before the lock.** Confirmed. `CustomerService.requireManageable` now loads with `repository.findByIdForUpdate(id)`, so the region is read under the lock a move takes. The lock is re-entrant for the later cascade lock in `delete`. Both callers are `@Transactional`.
- **(low) The sweep held every affected customer lock until one transaction ended.** Confirmed for `sweepOverdue`. It is no longer `@Transactional`. It reads the customer ids, then runs each customer in its own short transaction through the injected `TransactionTemplate`: lock, evaluate, save. So it holds one customer's lock at a time. A failure now leaves the customers already swept committed. The scheduler already catches and logs the failure.

**Rejected / kept (with reasons)**
- **Recompute stays one transaction.** §3 and `HistoryRollbackTest` (BE-07) require `recomputeAll(false)` to be a single rolled-back transaction that leaves no versions. `apply=true` is an explicit, rare administrator action whose returned list must describe one consistent pass. Taking no locks in the dry run would still issue promise UPDATEs on auto-flush, which means row locks without the customer lock (SC-7). The locks are taken in ascending customer id, so there is no deadlock.
- **The "role with X_MANAGE but no POC_ASSIGN → NOT_REACHABLE" case cannot be written today.** POC_ASSIGN and every X_MANAGE both need a MANAGE grant (`Privileges.REGION_LEVEL`) and come from the same role. So in any region where the caller has X_MANAGE, they have POC_ASSIGN exactly when their role has it. A role without it is refused by the coarse `canAssignPocAnywhere()` check before ids are resolved (400, or 403 for customers). The `POC_ASSIGN` extra in `resolveIds` is defence in depth for when levels diverge (for example, custom privileges in BE-05). No behaviour distinguishes it now.
- **`CustomerLoginRegionTest.moveAcmeTo` still uses SQL.** Moving it through the ORM is impossible: `Customer.region` (and the children's) is `updatable = false` (F3), so an entity save does not move anything. This was tried, and the test failed. The helper's comment now says so. **For BE-07:** this test writes `region_id` with raw JDBC on customers, invoices, payments, payment_promises and disputes. BE-07 must either call `historyTracker.touch(...)` for those ids in the helper or replace it with BE-08's move endpoint, or `historyIsInStep` will fail after `aCustomerLoginFollowsItsCustomerToAnotherRegion`.

**Tests:** backend 242 run, 0 failures, 0 errors, 0 skipped. That is 240 plus 2 new in `RegionAccessMatrixTest`, which now has 11. mail-service 24 run, 0 failures, 0 errors.

## BE-04b — Regions: the POC book per region, holders, flags, documents, email, notifications

**Built**
- **`B/region/RecordAccess`** (new, §5.6.9): `canManage(Kind, regionId)`, `can(privilege, regionId)`, `canMoveRegion(regionId)` (+ `canMoveRegion(regionId, moveTargets)` and `moveTargets()` so a page of customers costs one `regions` read, and only for a caller managing customers in ≥ 2 regions), `canResolve(Dispute)`. Every flag is false when `AsOf.active()`; staff-only flags are false for customer logins and without a principal.
- **DTO flags (§6.5, all `Boolean`, component-level `@JsonInclude(NON_NULL)`):** `CustomerDto.canManage/canMoveRegion`, `InvoiceDto`/`InvoiceSummary.canManage`, `PaymentDto.canManage`, `PromiseDto.canManage/canOverride`, `DisputeDto.canResolve`. API factories take the access: `InvoiceDto.from(inv, poc, RecordAccess)`, `InvoiceSummary.from(inv, poc, RecordAccess)`, `PaymentDto.from(p, poc, RecordAccess)`, `DisputeService.toDto(d, RecordAccess)`; `CustomerService.toDtos` and `PaymentPromiseService.toDto` (API-only) compute them. The one-argument audit factories (`InvoiceDto.from(inv)`, `PaymentDto.from(p)`, `DisputeService.toDto(d)`) pass null, so audit JSON carries no flags (tested).
- **POC book per region (`poc/PocService`, §5.6.7):**
  - `requireAssignable(userId, type, regionId)`: today's checks, then `regionAccess.holds(user, type.assignabilityPrivilege(), region)` → 400 "User X is not assignable as Sales POC in region CODE". The two-argument form is deleted; all 8 callers pass the record's region (invoice create/update, payment record/update, promise create (`resolveCollectionPoc(requested, customerId, regionId)`)/update, seat add).
  - `requireAssignableByRole(userId, type)`: today's checks only, used by the bulk ADD_POC pre-check (`CustomerController`), which spans regions; each row is then held to its own region by `add`.
  - `assignable(type, regionIds, q, limit)` over `UserRegionGrantRepository.findAssignableInRegions` (empty regions → `[]`).
  - `activeHoldersByType` keeps its single `customer_pocs` read, then filters by one `withAccess(ids, customer.region)` (+ one `regionIdOf`); `defaultAssignee`/`activeHolders` inherit it. New `withRegionAccess(users, regionId)`.
  - `add`/`remove`/`setPrimary`: after `lockCustomer`, `requireLive` + `requireIn(POC_ASSIGN, customer.region)` (403); `add` then `requireAssignable(…, region)`.
  - `notifyAssignee(assignee, type, what, link, regionId)`: skipped for a POC with no grant in the region; the notice carries the region.
  - Deleted: `callerIfAssignable`, `primaryFor`, and the unscoped `UserRepository.findAssignable`.
- **`PocController`:** `/assignable?type&q&limit[&customerId|&regionId]` (customer → its region via the scoped `customerService.get`, 404 if not visible; `regionId` → only if held, else `[]`; neither → `ViewScope.narrow(grantedRegions)`). `/my-scope` gains `regions[]` = `{regionId, code, clearable (INVOICE tier FULL), sales, success, collection}` for the asked-and-granted regions, sorted by code.
- **`InvoiceController.assignableCheck(customerId?)`:** with a customer, `isAssignableIn(SALES, customer.region)` after the scoped `customerService.get` (404).
- **Documents:** `DocumentTargets.Target` gains `regionId`; `requireManageable` = today's privilege gate → visibility → `requireLive` → for staff `requireIn(DOCUMENT_MANAGE, r)` + `requireIn(<record>_MANAGE, r)`; `canManage(type, regionId)`. `DocumentService.toDto(Document, Target)`: `canEdit = live ∧ canManage(type, r)`, `canDelete = canEdit ∨ (live ∧ uploader ∧ hasIn(DOCUMENT_MANAGE, r))`; `delete` enforces the same rule (plus `requireLive`).
- **Email:** `EmailTargets.Target` gains `regionId` (null for PRODUCT/USER/ROLE); `loadForSend(type, id)` = `load` + `requireIn(EMAIL_SEND, regionId)`; record-level holders (the record's own POC fields) are filtered with `withRegionAccess`, customer-level ones through `activeHoldersByType`. `EmailAddressing.resolve` first checks every internal person named directly (USER `to[i]`, USER `from`) with one `withAccess(ids, regionId)` → 400 `fieldErrors["to[i]"]`/`fieldErrors.from` = "NAME has no access to South". `EmailService`: `send`/`preview`/`bulk` rows/`retry` use `loadForSend`; `send`/`bulk`/`retry` call `requireLive`; in bulk a region miss on a row is a skipped row with that message.
- **Notifications:** `notifications.region_id` (nullable, no FK) + index `idx_notification_user_region (user_id, region_id)`; `Notification.LINK_MAX = 300`. `NotificationService`: `notify(…, regionId)` primary overload (old 5-arg passes null; link > 300 → stored null with a WARN), `notifyAdmins(…, regionId)` (active admins with a grant in the region; replaces the 4-arg form), `notifyHolders(privilege, regionId, exceptUserId, type, title, message, link)` (active holders, ≤ 100, returns the count), `unreadCount(userId, regionsOrNull)`; the private `fit` copy is replaced by `Fit.to`. `NotificationController`: list, unread-count and bulk `resolveIds` share a `scope()` like the inbox's — own rows, plus `region_id IS NULL OR region_id IN narrow(granted)` when `regionIds` is sent; `PageResponse.regionIds` = the answered set (`[]` for a customer login, which ignores `regionIds`). Producers pass regions: `DisputeService.open` (`notifyAdmins`), dispute resolution notices, POC assignment notices, broken promises.
- **Broken promises (SC-17):** `PaymentPromiseService.notifyBroken` notifies the Collection POC only if they hold a grant in the promise's region; otherwise `notifyHolders(PROMISE_MANAGE, region, actor, …)`.
- **Audit:** `AuditLogRepository.findLatestRegionId(type, id)` (newest non-null `region_id`); `CustomerService.delete` files `CUSTOMER_DELETED` with the customer's region; `AuditController.readOrGone` for a deleted CUSTOMER rethrows 404 unless the caller holds any grant in that region (default region when the history names none). `CustomerAuditSnapshot` gains `regionId`.
- **Dead unscoped methods deleted (R17):** `PaymentService.list`, `PaymentPromiseService.listForCustomer/listForInvoice`, `DisputeService.list` (+ `DisputeRepository.findAllByOrderByCreatedAtDesc`), `PocService.callerIfAssignable/primaryFor`, `UserRepository.findAssignable`.
- **Tests**
  - `RegionAccessMatrixTest` (+5, now 16): flags per region on details and lists for all five kinds (N false, S true), `canMoveRegion` (false with one managed region, true once EAST is managed, false again when EAST is inactive), `canResolve` false after approval, no flag in any audit JSON, `RecordAccess` false under a historical scope; documents (upload N → 403, W → 404, list flags N false / S true, rita's own N upload not deletable, PATCH/DELETE on N → 403 with the DB unchanged); email (send N → 403, W → 404, `to[1]` and `from` naming a NORTH-only user on SOUTH → 400 fieldErrors "NORA.NORTH has no access to South", preview 400, bulk row skipped with that reason, nothing saved); seats (add N → 403, W → 404, remove/primary N → 403, a NORTH-only collector on SOUTH → 400 with the region message, DB unchanged); a deleted NORTH customer's history → 200 for a NORTH reader, 404 for a SOUTH-only reader, and its `CUSTOMER_DELETED` row carries NORTH.
  - `RegionBookTest` (+4, now 9): `requireAssignable` without a grant → 400 "User sam.sales is not assignable as Sales POC in region NORTH", nothing written, PATCH refused, then allowed once granted (the notice carries NORTH); the picker (`customerId`, `regionId`, none, `regionIds`) lists only people with the region, an unheld `regionId` → `[]`, an invisible customer → 404; `assignable-check?customerId`; a seated collector without the region is neither `defaultAssignee`, nor the promise default, nor emailed as COLLECTION_POC (seat kept), until granted; a broken promise goes to its POC (tagged with the region) and, once the POC loses the region, to the region's PROMISE_MANAGE holders except the actor.
  - `RegionParamCoverageTest` (+5 rows, now 35): `/api/pocs/assignable`, `/my-scope`, notifications list, unread-count and bulk; `PENDING_ROWS` is now empty (the self-check was rewritten as `noneMatch` because AssertJ refuses an empty "does not contain any of").
  - Mutation-checked (each makes a new test fail): dropping the holders filter, the named-recipient check, the seat `requireIn`, the assignability region check, the notification region predicate, the document region checks, the deleted-customer region check, the broken-promise fallback, `loadForSend`'s EMAIL_SEND check, the historical flag rule, and `/my-scope` narrowing.

**Deviations from DESIGN.md (with reasons)**
- **Test fixtures seat their POCs in the regions they work in.** With assignability per region, every fixture that makes a DEFAULT-only POC the Sales/Collection POC of a NORTH/SOUTH record now gets 400 (by design). Grants (READ/BOOK) were added in `PrincipalRightsTest` (a `pocsAlsoIn(...)` helper called by the four tests that need it, and `sid` in the book test), `CustomerLoginRegionTest`, `RegionSchemaUpgradeTest`, `RegionBookTest`, `RegionParamCoverageTest` and `RegionAccessMatrixTest` setups (all created by BE-02..BE-04a). No assertion was changed; no baseline file was touched.
- **`requireAssignableByRole`** is a public addition: the bulk ADD_POC pre-check (§5.6.7 "CustomerController:149") spans regions, so it keeps today's role-level 400 and each row is checked in its own region by `add`. A row whose person lacks the region fails with the region message.
- **Email region check on staff only, and only on directly named people.** Customer-login senders (`internal = false`) have no grants and are not checked; the implicit sender (the caller) already passed `requireIn(EMAIL_SEND, region)`. The check runs in `resolve` (where the target is known), so it also covers bulk rows; a bulk row that fails it is skipped (IneligibleException) with the field message rather than failing the batch.
- **Document delete** now requires the uploader to hold DOCUMENT_MANAGE *in the record's region* (the same rule as the `canDelete` flag); before it needed it anywhere (controller gate). `requireManageable` keeps today's 403-before-404 order for the role gate, then 404 visibility, then the region 403s.
- **`CustomerDto` has no `version` yet** (the frontend asked for it for move-region): §6.5 does not list it, so it is left to BE-08 with the move endpoint.
- **Notification bulk honours `regionIds`** exactly as the list does (it was already classified SETS by BE-03).

**API-contract changes versus §6:** none. Added exactly: the §6.5 flags, `/api/pocs/assignable?customerId|regionId`, `/my-scope.regions[]`, `/api/invoices/assignable-check?customerId`, notifications answering `regionIds` with `PageResponse.regionIds`, 400 `fieldErrors["to[i]"]`/`from` for named recipients without access, 403 on seat/document/email writes outside a managed region.

**Known gaps / deferred**
- `POST /api/notifications/mark-all-read` (like the inbox's, BE-03 review item 1) still marks every notification regardless of `regionIds`; it is a housekeeping write in §5.2.4. The UI can use `/bulk` with `selectAllMatchingFilter`, which is narrowed.
- The inbox region predicate still drops emails about deleted records when narrowed (BE-03 review item 2); `findLatestRegionId` now exists for whoever resolves it.
- Deleted invoices/payments/promises keep today's audit behaviour (they cannot be deleted on their own; only CUSTOMER history can outlive its record).
- `pendingApproval` (BE-15), `assignees`/`internal`/`origin` (BE-19) DTO fields, `RegionAdminTest`'s deleted-customer case (BE-05; covered here already in the matrix test).

**Tests:** backend 256 run, 0 failures, 0 errors, 0 skipped (242 before + 14 new: `RegionAccessMatrixTest` +5, `RegionBookTest` +4, `RegionParamCoverageTest` +5). mail-service 24 run, 0 failures, 0 errors.

### BE-04b review fixes

All five review findings were valid; all fixed. Each new test was checked by re-applying the regression it guards (mutation) and confirming it fails, then restoring the code.

- **(medium) Record-level email role holders not region-tested** — new `RegionBookTest.anInvoicesOwnPocWithoutItsRegionIsNeitherEmailedNorTheSenderAsTheRole`: a SOUTH invoice whose Sales POC (sid) resolves as `ROLE SALES_POC RECORD` to/from while he holds SOUTH; after his grants are cut to NORTH only, `/api/emails/preview` shows no recipient, a null sender and `ROLE:RECORD:SALES_POC` unresolved, and a real send writes to the other recipient only (no `email_recipients` row for sid). Removing `.filter(ownReachable::contains)` in `EmailTargets.holders` now fails it.
- **(medium) `notifyAdmins` region/active filters untested** — new `RegionBookTest.aDisputeIsToldOnlyToActiveAdministratorsWhoHoldItsRegion`: ADMIN-role ada (SOUTH), nick (NORTH only) and ivy (SOUTH, deactivated); a SOUTH customer login opens a dispute; DISPUTE_OPENED reaches admin and ada, never nick or ivy, all tagged SOUTH with the dispute link. Removing either the region filter or the `isActive` filter fails it.
- **(medium) Retry's per-region EMAIL_SEND untested** — new `RegionAccessMatrixTest.aFailedEmailIsRetriedOnlyWhereTheCallerMaySend`: admin sends one unsent (retryable) email per region; rita's retry is 403 in NORTH and 404 in WEST with `emails`/`email_recipients` byte-for-byte unchanged and nothing submitted, and 200 (requeued) in SOUTH. Reverting `retry` to `targets.load` fails it.
- **(low) `POST /api/notifications/mark-all-read` ignored `regionIds`** — fixed: `NotificationController.markAll` passes `askedRegions()`; new `NotificationService.markAllRead(userId, regionsOrNull)` with `NotificationRepository.markAllReadInRegions` / `markAllReadAboutNoRegion` (JPQL updates on `regionId is null or regionId in :regions`, same rule as the list/count). Null (nothing asked) keeps the old unnarrowed update. The endpoint stays a WRITE in `ViewScopeInventoryTest` (not a SET), so it gets its own test rather than a coverage row: `RegionParamCoverageTest.markingAllReadTakesInOnlyTheRegionsAskedAbout` (SOUTH asked → SOUTH + no-region marked, NORTH untouched; a region the caller does not hold → only no-region; unnarrowed → all). The inbox's `/api/inbox/mark-all-read` is unchanged (BE-03 carry-over, not part of this finding) and remains a known gap.
- **(low) Link guard and historical DocumentDto flags untested** — new `RegionBookTest.aNoticeWhoseLinkIsTooLongIsKeptWithoutIt` (310-char link → notice saved with null link, title and region kept; a 300-char link is stored as is; removing the guard fails with the column-length violation). `RegionAccessMatrixTest.documentsAreChangedOnlyWhereTheRecordsRegionIsManaged` now also advances the clock a day and lists SOUTH's documents as rita as of the upload day: `canDownload` true, `canEdit`/`canDelete` false. Deviation: `GET /api/documents?asOf=` still answers 400 `AS_OF_UNSUPPORTED` (its as-of slice is later), so the check calls `DocumentService.list` directly under a historical `ViewScope`; forcing `live = true` in `toDto` fails it.

Rejected: none.

Final counts: backend 261 run, 0 failures, 0 errors, 0 skipped (256 + 5 new: RegionBookTest 12, RegionAccessMatrixTest 17, RegionParamCoverageTest 36); mail-service 24 run, 0 failures, 0 errors.

## BE-05 — Regions: administration, grants, `/me`

**Built**
- **`B/region/RegionDtos`**: `RegionDto{id, code, name, active, defaultRegion, version, createdAt}`, `CreateRegionRequest{code, name, active}`, `UpdateRegionRequest{name, active, version}`, `GrantInput{regionId, level, scope}` (all `@NotNull`, enums), `ReplaceGrantsRequest{grants}` (`@NotNull`, elements `@Valid @NotNull`), `UserGrantDto{regionId, regionCode, regionName, level, scope, grantedAt, grantedByUserId}`, `RegionGrantHolderDto{userId, username, fullName, roleName, level, scope}`.
- **`RegionService` CRUD** (audit entity `REGION`; every write calls `ViewScopeHolder.requireLive()`):
  - `get(id)` (404), `requireActive` now reuses it.
  - `create`: code trimmed and upper-cased, must match `^[A-Z0-9_-]{2,20}$` → else 400 `fieldErrors.code`; taken code → `fieldErrors.code`; name taken ignoring case → `fieldErrors.name`. Saves `active` (default true), `defaultRegion = false`; the creator (staff) is `ensure`d APPROVE/ALL; then every `RegionCreatedListener` bean is told (via `ObjectProvider`, none exist until BE-15); audit `REGION_CREATED`.
  - `update`: stale `version` → `OptimisticLockingFailureException` (409); name taken by another region → `fieldErrors.name`; deactivating the default → 400 "The default region cannot be deactivated"; `saveAndFlush`; audit `REGION_UPDATED` (before/after).
  - `makeDefault`: 400 if inactive; no-op if already default; `clearDefaultExcept` then `markDefault` (clear first so Postgres's partial unique index holds statement by statement); audit `REGION_DEFAULT_CHANGED` with reason "Previously the default: CODE".
  - `delete`: 400 "Region is the default"; 400 "Region still has customers, disputes or grants; deactivate it instead"; otherwise deletes the region's remaining grants (only the caller's can be left), deletes and flushes (a stray FK → 409), audit `REGION_DELETED`.
- **`RegionCreatedListener`** SPI (`regionCreated(Region)`), called inside the create transaction.
- **`RegionGrantService`**:
  - `replace(userId, grants)`: `requireLive`; lock the user (`findByIdForUpdate`, 404); 400 self ("You cannot change your own region access"); 400 customer login ("Customer logins take their region from their customer"); validate (unknown region or a region listed twice → 400 `fieldErrors.grants`); **granter bound (SC-5)**: for every row added, changed or removed, the caller's own grant in that region must exist, be ≥ both the old and the new level, and have scope ALL when either row has ALL, else 403; rows that come back identical are untouched. A PUT that changes nothing writes nothing (no audit). Otherwise `deleteAllOfUser` (bulk, executed at once: F7) then `saveAll`; an unchanged row keeps its `grantedAt`/`grantedByUserId`, a new/changed row records the granter and now. Audit `USER_REGION_GRANTS_CHANGED` on entity `USER` (before/after lists, `changedBy` = granter).
  - `grantNewUser(saved, grants)`: the same validation and bound (field `regionGrants`), absent/`[]` → nothing; audits `USER_REGION_GRANTS_CHANGED` when grants are given.
  - `grantsOf(userId)` (404 unknown user), `holdersOf(regionId)` (404 unknown region). `ensure` unchanged (unbounded; seeder, fixtures, region creator).
- **`B/user/UserAccountService`**: `@Transactional create(User unsaved, List<GrantInput>)` → `Created(user, regionGrants)`; saves the account and its grants together (a refused grant rolls the account back).
- **`UserController`**: `create` delegates to `UserAccountService` (the BE-02 bridge grant and the controller's `@Transactional` are gone); `CreateUserRequest` gains trailing `List<@Valid @NotNull GrantInput> regionGrants`; `UserDto` gains trailing `regionGrants` (component-level `@JsonInclude(NON_NULL)`): filled by `GET /api/users/{id}` and `POST /api/users`, omitted in lists, updates and audit snapshots.
- **Controllers** (`@AsOfIgnored`; every non-GET is WRITE):
  - `RegionController` `/api/regions`: `GET` (list through `TableQueryExecutor` on `TableSchemas.REGIONS`, `PageResponse` with `regionIds: []`) and `GET /{id}` gated `REGION_VIEW or USER_MANAGE`; `POST`, `PUT /{id}`, `POST /{id}/make-default`, `DELETE /{id}` (204) gated `REGION_MANAGE`.
  - `RegionGrantController`: `GET /api/regions/{id}/grants` (`REGION_VIEW and USER_VIEW`), `GET /api/users/{id}/region-grants` (`USER_VIEW`), `PUT /api/users/{id}/region-grants` (`USER_MANAGE`).
- **Schemas**: `TableSchemas.REGIONS` (`regions`, default sort `code,asc`; columns `id`, `code`, `name`, `active`, `defaultRegion`, `createdAt`), registered in `buildIndex`; `TableSchemaController` `regions → [REGION_VIEW, USER_MANAGE]`.
- **`PrivilegeController`**: `PrivilegeDto` gains `regionLevel` (`READ`/`MANAGE`/`APPROVE`, `null` for a global privilege, serialised as null) from `Privileges.requiredLevel`.
- **`AuditController`**: `REGION` supported, `VIEW_PRIVILEGE` is now `Map.ofEntries` with `REGION → REGION_VIEW`; read through `regionService.get`, and a deleted region's history stays readable (`readOrGone` with `regionRepository::existsById`). Customer logins are refused as before (not customer-readable).
- **Repositories**: `RegionRepository.existsByNameIgnoreCase`, `hasCustomers(id)`, `hasDisputes(id)`; `UserRegionGrantRepository.countByRegionIdAndUserIdNot`, `deleteAllInRegion(regionId)`.
- **`ViewScopeInventoryTest`**: the nine new endpoints are in `GLOBAL_EXEMPT`; the classification test also pins `GET /api/regions`, `/regions/{id}/grants`, `/users/{id}/region-grants` as IGNORED and `make-default` and the grants PUT as WRITE.
- **`/login` and `/me` `regions`**: nothing to do; BE-02 already delivered them exactly as §6.2 (see BE-02 deviations). `AuthController`/`LoginResponse` are unchanged by this slice.
- **Tests**: new `T/region/RegionAdminTest` (15): create/read/list/filter/update with history; 409 stale version (DB unchanged); bad/taken code and name → field errors (nothing created); make-default leaves exactly one default, inactive → 400, repeat is a no-op; the default cannot be deactivated or deleted; only an empty region is deleted (creator's grant goes with it, `REGION_DELETED` history readable after deletion), a region with a customer or another user's grant → 400 and nothing changes; who may read and change regions (USER_MANAGE-only role reads, cashier 403, table schema `regions` gated any-of, grants listing needs REGION_VIEW and USER_VIEW, `REGION` audit needs REGION_VIEW); historical PUT → `AS_OF_READ_ONLY`, historical GET answers live; grants PUT replaces whole, sorted, audited with the granter, reflected in `GET /users/{id}`, `/regions/{id}/grants` and `/me`, identical PUT writes nothing and keeps `grantedAt`, `[]` removes all; self / customer login / duplicate / unknown region / missing level / bad enum / missing body / unknown user or region refused with the DB and audit unchanged; **SC-5** (a NORTH-only MANAGE user admin: APPROVE → 403, a SOUTH row raised/narrowed/dropped → 403, creating an account in SOUTH → 403 and no account, READ in SOUTH does not reach a MANAGE row, the SOUTH row sent back unchanged is kept with its original granter; a BOOK-scoped granter may give BOOK rows but not ALL rows, nor remove an ALL row); `POST /api/users` absent → none, `[]` → none, given → exactly those (audited), duplicate → `fieldErrors.regionGrants` and no account; deleting a user removes its grants (`@OnDelete`); `regionLevel` for every privilege (null for all of `GLOBAL`); deleted-customer history → 404 without its region.
- Mutation-checked: dropping the ALL-scope rule of the bound fails `aGranterGives…`; treating a region the granter holds nothing in as reachable fails `aGranterGives…` and `PrincipalRightsTest.aUserWhoseGrantCannotBeGivenIsNotCreatedEither`.

**Deviations from DESIGN.md (with reasons)**
- **`PrincipalRightsTest` (a BE-02 file) had two tests pinning the BE-02 bridge**, which this slice removes by design (§4.6, CF-19). They were rewritten, not deleted: `aUserCreatedWithoutGrantsGetsTheDefaultRegionUntilGrantsCanBeChosen` → `aUserCreatedWithoutGrantsHoldsNoRegionUntilOneIsGiven` (no grants, flag false, `/me` has `regions: []` and only the role's global privileges); `aUserWhoseGrantCannotBeGivenIsNotCreatedEither` keeps its name and intent (account and grants are atomic) but now provokes the failure with a grant beyond the creator's reach (403) instead of a missing default region (which no longer matters to user create).
- **Region delete ignores the caller's own grant.** §6.3 blocks deletion while any grant exists, but the creator is always auto-granted APPROVE/ALL and cannot edit their own access, so a region could never be deleted by the person who created it (a typo'd region would need a second administrator to strip the creator first). Other users' grants still block; the caller's own grant is deleted with the region. Customers and disputes block as specified (their children follow them by I1).
- **Region `code` is trimmed and upper-cased before the pattern check** (`" east "` → `EAST`); a code that still does not match is a `fieldErrors.code`. The design only states the pattern.
- **Grant rows that come back unchanged keep their original `grantedAt`/`grantedByUserId`** even though the rows are physically re-inserted (bulk delete, then insert, per F7); an identical PUT is a no-op (no rewrite, no audit row).
- **`POST /api/users` with grants also files `USER_REGION_GRANTS_CHANGED`** (before `[]`), so the account's history names who gave its first access; there is still no `USER_CREATED` row (unchanged behaviour).
- **`make-default` does not bump `regions.version`** (bulk JPQL updates, as `RegionRepository` already had them). `PUT /api/regions/{id}` does not touch `defaultRegion`, so a client's version stays valid across a default change.
- **Concurrent make-default on H2** relies on clear-then-mark ordering only; Postgres additionally has `uk_regions_default` (created by `RegionSchemaUpgrade`). No extra locking was added.

**API-contract changes versus §6:** none. Delivered as §6.2/§6.3/§6.15/§6.16: region CRUD and make-default, grants endpoints, `regionGrants` on user create and `GET /api/users/{id}`, `regionLevel` on `/api/privileges`, `REGION` audit, `/api/table-schemas/regions`. Error shapes: `fieldErrors.code`/`.name` on regions, `fieldErrors.grants` (PUT) / `fieldErrors.regionGrants` (user create) for unknown or duplicate regions, bean-validation paths such as `fieldErrors["grants[0].level"]` for a missing field.

**Known gaps / deferred**
- Granting APPROVE needs `CHANGE_APPROVE` and gaining APPROVE notifies the region's other approvers (`APPROVER_ADDED`): BE-13 (the privilege does not exist yet), with its two `RegionAdminTest` cases.
- `RegionCreatedListener` has no implementation until BE-15 (`ApprovalThresholdService` copies the default region's thresholds).
- `recheckOwner` on grant replace: BE-17.

**Tests:** backend 276 run, 0 failures, 0 errors, 0 skipped (261 before + 15 new `RegionAdminTest`; `PrincipalRightsTest` still 20, `ViewScopeInventoryTest` still 5). mail-service 24 run, 0 failures, 0 errors.

### BE-05 review fixes

- **M1 (fixed): region writes could undo a concurrent make-default.** `Region.defaultRegion` is now mapped `@Column(updatable = false)`, so the only thing that changes `is_default` is `makeDefault`'s bulk updates. A save made from an entity loaded before a make-default can no longer write the old flag back. `RegionRepository` gains `findByIdForUpdate` (PESSIMISTIC_WRITE), which `RegionService.update` and `delete` now use for their first read, so the "is the default" and "cannot deactivate the default" checks run against the committed state. It also gains `lockWithDefault(id)` (the region plus the current default, locked in id order), which `makeDefault` takes first. As a result, make-default, edit and delete on the same rows run one after another, and the H2 double-make-default gap listed under BE-05 is closed. The earlier deviation note "the region PUT doesn't touch the default flag" is now true at the SQL level. New test `RegionAdminTest.aRegionChangeWaitsForAMakeDefaultAndNeverUndoesIt`: another JDBC session holds the region rows and commits a default switch mid-request. It checks that an edit of the new default leaves it the default, an edit of the old default doesn't restore it, a delete of the region just made the default is refused with 400, and a make-default waits for the old default. With the fix reverted the test fails (zero defaults; delete returns 204). Reverting only the delete lock also fails it.
- **L1 (fixed): the PUT version is now required.** `UpdateRegionRequest.version` is `@NotNull`, and the service always compares it, as §6.3 specifies. A PUT without a version gets 400 with `fieldErrors.version`, asserted in `aBadOrTakenCodeOrNameIsReportedAgainstItsField`. The slice's PUTs now send a version.
- **L2 (fixed): the history of a customer deleted from a since-deleted region is readable again.** `AuditController.deletedCustomerReachable` ignores a recorded region that no longer exists and falls back to the default region, as it already did for a customer deleted before regions. New test `aDeletedCustomersHistoryOutlivesItsRegion`: admin and a default-region reader get 200, and a NORTH-only reader gets 404. It fails without the fix. Refusing the region delete was rejected, since it would block deleting any region that had ever held a customer.
- **L3 (fixed): the REGION_VIEW side of the any-of gates is now tested.** A REGION_READER role holding only REGION_VIEW gets 200 on `/api/regions`, `/api/regions/{id}` and `/api/table-schemas/regions` (and sees `regions` in `/api/table-schemas`). It gets 403 on `/api/regions/{id}/grants` and on a PUT.
- Nothing rejected.
- Final counts: backend 278 run, 0 failures, 0 errors, 0 skipped (RegionAdminTest now has 17 tests); mail-service 24 run, 0 failures, 0 errors.

## BE-06 — History storage model

**Built** (new files only; no existing file modified)
- `B/history/VersionRow` (`@MappedSuperclass`): `version_id` identity `@Id` (attribute `versionId`), `entity_id` NOT NULL mapped to attribute **`id`**, `valid_from`/`valid_to` NOT NULL, `tx_id` v36 NOT NULL, `origin` v12 NOT NULL (a `WriteOrigin.Kind` name), `origin_ref_id`, `actor_user_id`; `OPEN = 9999-12-31T00:00:00Z`; `isOpen()`.
- `B/history/versions` — nine version entities, column names/lengths/precision equal to the live table's, every business column a nullable wrapper, every association a writable `<assoc>Id` Long **plus** a read-only (`insertable/updatable = false`, `ConstraintMode.NO_CONSTRAINT`, no setter) association to the live class under the live attribute name, indexes `idx_<table>_entity (entity_id, valid_to)`, `idx_<table>_window (valid_to, valid_from)` plus the §3.5 per-table ones:
  - `CustomerVersion` (`customer_versions`), `ProductVersion` (`product_versions`), `InvoiceVersion` (`invoice_versions`, + `items_json` TEXT), `PaymentVersion` (`payment_versions`), `PaymentAllocationVersion` (`payment_allocation_versions`), `PromiseVersion` (`promise_versions`), `DisputeVersion` (`dispute_versions`, without `internal`/`origin`/`rule_id` — BE-19), `CustomerPocVersion` (`customer_poc_versions`), `EntityLinkVersion` (`entity_link_versions`: `kind` v30, `owner_id` (attribute `ownerId`), `target_id` via `@AttributeOverride` of `id`; all NOT NULL; indexes `(kind, owner_id, valid_to)`, `(kind, target_id, valid_to)`, window).
  - Attribute names for the ids: `customerId`, `salesPocId` (`sales_poc_user_id`), `collectionPocId` (`collection_poc_user_id`), `regionId`, `paymentId`, `invoiceId`, `userId`; `DisputeVersion.customerId` is plain, as live.
  - `EnumNames` (abstract `EnumName<E extends Enum<E>> implements AttributeConverter<E,String>`, unknown names → null + WARN `history.enum.unknown`) with `InvoiceStatusName`, `PaymentTermName`, `PaymentStatusName`, `PromiseStatusName`, `PocTypeName`, `DisputeTargetTypeName`, `DisputeStatusName`. Not auto-applied.
  - `ItemSnapshot(id, productId, quantity, unitPrice, lineTotal)` — no product name (CF-4); amounts scaled to 2 (HALF_UP) in the compact constructor and serialised as strings (`@JsonFormat(STRING)`), fixed property order.
- `B/history/HistoryMeta` (`history_meta`: `id` smallint PK = `ID` 1, `started_at`, `baseline_completed_at`).
- `B/history/TypeHistory<L, V>`: `code/liveType/versionType/rank/snapshotAll/sameState/rehydrate` + static `sameAmount` (compareTo), `sameInstant`/`micros` (compare at the microsecond the column keeps, rounded half up like H2 and Postgres, so an in-memory nanosecond instant never looks like drift).
- `B/history/types/{Customer,Product,Invoice,Payment,Allocation,Promise,Dispute,Seat}History`: codes/ranks CUSTOMER 10, PRODUCT 15, INVOICE 20, PAYMENT 30, ALLOCATION 35, PROMISE 40, DISPUTE 50, SEAT 60. `snapshotAll` fills business columns only (ids of associations read from proxies without initialising them); `InvoiceHistory` runs one scalar HQL per 500 invoices for all their items and builds `items_json` sorted by item id (static `itemsJson(List)`/`items(String)` helpers). `sameState` excludes `updatedAt` (promise, dispute) and `brokenNotifiedAt` (promise). `rehydrate` throws `UnsupportedOperationException`.
- `B/history/LinkHistory` (record `(kind, owner, collectionAttribute)`): `PROMISE_INVOICES`/`PROMISE_PAYMENTS`, `code()` = `LINK:<kind>`, rank 45, `currentTargets(session, ownerIds)` = `select o.id, t.id from <Owner> o join o.<collection> t where o.id in :ids` (chunked; every owner present, empty set when none).
- `B/history/HistoryModel` (static, built once): `types()` in rank order, `links()`, `linksOf(owner)`, `isVersioned`, `isVersionClass` (incl. `EntityLinkVersion`), `of(live)`, `byCode`, `linkByCode`, `rootFor(live)` (version class only while `AsOf.active()`), and the §5.7.12 navigations as data (`Navigation(from, attribute, target, On)`: `customer` from Invoice/Payment/Promise/Seat versions, `payment`/`invoice` from allocation versions, `invoices`/`payments` from promise versions to `EntityLinkVersion` with `kind` bound as a parameter) for `Temporal` (BE-09a).
- `B/history/VersionStore` (`@Component`, Criteria, every value a parameter, `in` chunked at 500, null/duplicate ids dropped, results ordered by record id then `validFrom`): `open`, `validAt` (`validFrom <= p < validTo`), `validAtBy` (rejects an association/collection attribute), `openLinks`, `linksAt`. Not `@Transactional`: callers bring the transaction (the BE-07 writer runs inside before-completion).
- Tests (all extend `IntegrationTestBase`):
  - `T/history/VersionShapeTest` (4): per live/version pair via the JPA metamodel and Hibernate persister: every live attribute exists on the version with the boxed Java type and the same physical column name and length/precision/scale, or as `<assoc>Id` Long on the association's column plus a read-only, FK-less association of the same target type, or is `version` (not stored) / a collection kept elsewhere (items → `itemsJson`, allocations → own versions, promise invoices/payments → links); no version attribute unaccounted for; `versionId` is the key on `version_id`, `id` is on `entity_id` (`target_id` for links); no primitive, NOT NULL, `@Enumerated` business field, every enum field on an `EnumNames` converter; the model's codes, rank order, lookups, links, navigations and `rootFor` live vs under `AsOf.with`.
  - `T/history/VersionTablesHaveNoConstraintsTest` (7): via `information_schema`: exactly the nine `*_VERSIONS` tables, none containing a live table's name; each has only its `PRIMARY KEY` on `VERSION_ID`; no FK into them; no unique index; no ENUM column (enum columns are `CHARACTER VARYING`); only the window/provenance (and link) columns are NOT NULL; the entity/window/owner/target indexes and invoice per-table indexes exist; `history_meta` is `smallint` PK plus two nullable timestamps.
  - `T/history/SnapshotTest` (5), on a real family (customer, two products, an invoice with two lines, a promise linked to it, a payment linked to both, a seat, a dispute): every live column is copied (nulls filled in memory first, transaction rolled back), keys are the record ids and the window/provenance stay empty; exact `items_json` (id order, strings at scale 2, no `productName`/names; order and scale of the input never change the text); a product rename changes no invoice snapshot; `sameState` is false for a change to **every** business column of every type and true for a change to any window/provenance column or excluded column, for amounts at another scale and for instants within the same microsecond (and false past it); versions persisted and read back through `VersionStore` (`open`, `validAt` half-open window incl. an older closed customer version, `validAtBy`, `openLinks`, `linksAt`, `LinkHistory.currentTargets`) are `sameState` with a fresh snapshot; an unknown stored enum name reads back as null. It deletes the version rows it wrote in `@AfterEach` (the reset gains `clearVersions` in BE-07).
  - Mutation-checked: a forgotten snapshot column, a dropped `sameState` column, comparing an excluded column, exact-instant comparison, a changed column length, and an FK on a read-only association each fail a test.

**Deviations from DESIGN.md (with reasons)**
- **`B/history/Rehydration` created now** (a plain carrier of `AsOfPoint`, `VersionStore`, `EntityManager`, `UserRepository`, the exact constructor §5.7.13 uses). §9.1 lists it under BE-09a, but BE-06's `TypeHistory.rehydrate(List<V>, Rehydration)` signature needs the type to compile. BE-09a adds its memoised batch loaders.
- **Eight `TypeHistory` implementations, not nine.** `TaskHistory`/`TaskVersion` and the `TaskStatusName`/`TaskEntityTypeName` converters need the `Task` entity and its enums, which arrive in BE-12. BE-12's list has `TaskVersion`/`TaskHistory` and "`HistoryModel` (TASK)", but **not** `EnumNames` (see the review fixes below).
- **`items_json` is written with a private plain `ObjectMapper`** in `InvoiceHistory`, not Spring's bean: `HistoryModel` is a static registry built without Spring. The text is fixed by `ItemSnapshot`'s annotations (property order, string amounts), so it is byte-identical to what Spring's mapper would write.
- `sameState` compares instants rounded half up to the microsecond (the column's precision) rather than with `equals`: an entity whose instant still carries nanoseconds in memory would otherwise differ from its stored version and write a spurious version. Stored values are never truncated by the snapshot, so the database rounds live and version values identically.
- Small additions the design implies: `VersionRow.isOpen()`, `LinkHistory` constants/`currentTargets`, `HistoryModel.links/linksOf/linkByCode/navigation`, `InvoiceHistory.itemsJson/items` static helpers.

**API-contract changes versus §6:** none (no endpoint touched).

**Known gaps / deferred (by design)**
- No capture yet: nothing writes versions at runtime (BE-07: listener, writer, tracker, reconciler `check`, `HistoryTestSupport.clearVersions` in the reset). Per-key catching of snapshot mapping errors (§5.7.6) is the writer's job.
- `rehydrate` throws until BE-09a/09b; `Temporal`/`Records`/`LiveReadGuard` are BE-09a.
- `history_transactions`/`HistoryTransactionRow` is BE-11.

**Tests:** backend 294 run, 0 failures, 0 errors, 0 skipped (278 before + 16 new: `VersionShapeTest` 4, `VersionTablesHaveNoConstraintsTest` 7, `SnapshotTest` 5). mail-service 24 run, 0 failures, 0 errors.

### BE-06 review fixes

- **M1 (fixed): the shape tests now compare physical columns, not only annotations.** New `VersionShapeTest.everyLiveColumnHasTheSamePhysicalTypeInItsVersionTable` reads `information_schema.columns` for each live/version table pair. Every live column except `ID` and `VERSION` must exist in the version table with the same `DATA_TYPE`, `CHARACTER_MAXIMUM_LENGTH`, `NUMERIC_PRECISION` and `NUMERIC_SCALE`. A live ENUM column must be `CHARACTER VARYING` in the version table. `INVOICE_VERSIONS.ITEMS_JSON` has no live counterpart, so it must have the same physical type as the live TEXT column `DISPUTES.PROPOSED_CHANGE_JSON`, and that type must not be a 255 varchar. In H2 that type is `CHARACTER VARYING 1000000000`, not the `CHARACTER LARGE OBJECT` the reviewer suggested, so the test compares against the live TEXT column rather than a hard-coded name. Probes: dropping `@Lob @Column(columnDefinition = "TEXT")` from `DisputeVersion.proposedChangeJson` fails the test, and so does dropping it from `InvoiceVersion.itemsJson`. The main code was already correct and is unchanged.
- **L1 (fixed): `SnapshotTest.fillNulls` now gives each field its own value.** Strings are `"s-" + fieldName`. Longs, amounts, instants, dates, integers and enum picks are offset by a hash of the field name. The only exception is booleans, which have just two values. As a result, a snapshot that copies the wrong column of the same type now fails. Probes: `v.setOverriddenAt(p.getBrokenNotifiedAt())` in `PromiseHistory` and `v.setAdminNotes(d.getProposedChangeJson())` in `DisputeHistory` each fail `aSnapshotCopiesEveryColumnOfItsRecordAndNothingOfTheWindow`. Both mappings were restored.
- **L2 (fixed as a hand-off record): BE-12's missing pieces are now recorded.** I corrected the earlier deviation note, which wrongly said BE-12's file list already covers the Task converters. Two items were added to `CARRY-FORWARD.md` and are repeated here:
  - (a) BE-12 must add `TaskStatusName` and `TaskEntityTypeName` to `history/versions/EnumNames`. It is not in BE-12's Modify list.
  - (b) BE-12 must extend `VersionTablesHaveNoConstraintsTest`. Both its nine-table `VERSION_TABLES` list and its exact enum-column list (`containsExactly`) will fail once `TASK_VERSIONS` (`STATUS`, `ENTITY_TYPE`) exists. It must also extend `VersionShapeTest`'s code list, which is also `containsExactly`.
- Nothing rejected.
- Final counts: backend 295 run, 0 failures, 0 errors, 0 skipped (`VersionShapeTest` now has 5 tests). mail-service 24 run, 0 failures, 0 errors.

## BE-07 — History capture

**Built**
- `B/history/HistoryListener` (Post insert/update/delete + collection recreate/update/remove; `requiresPostCommitHandling = false`; no DB work). Versioned entity → `(CODE, id)` with INSERTED/UPDATED/DELETED; `InvoiceItem` → `(INVOICE, invoiceId)` CHILD (id read from the proxy without loading it); a collection change on an `Invoice` owner → `(INVOICE, owner)` CHILD, on a `PaymentPromise` owner → both `LINK:PROMISE_INVOICE`/`LINK:PROMISE_PAYMENT` keys; version rows, `Payment.allocations` and untracked types ignored. Throws `AsOfException.readOnly` when a point is bound.
- `HistoryListenerRegistrar` (`@Component`, `@PostConstruct` appends the listener for the six event types).
- `HistoryWork` (Before- and AfterTransactionCompletionProcess): `txId` UUID, `Change {INSERTED, UPDATED, DELETED, CHILD, TOUCHED}` per key (code → sorted ids), instant bound on first `instant(clock)` call (inside the writer), `pin(Instant)` for BE-08's baseline, `writing` (late marks log ERROR and are ignored), `done`. Stale-process guard (CF-22): after completion (commit or rollback) it is `done` and removed; before completion runs only if `!done` and it is the work `HistoryTransactions` has registered for the committing session's `Transaction`.
- `HistoryTransactions` (`@Component`): `ConcurrentHashMap<org.hibernate.Transaction, HistoryWork>`; `current(SharedSessionContractImplementor)` creates and registers the work as both processes on the session's `ActionQueue`; `current(EntityManager)`.
- `HistoryWriter` (`@Component`; `Clock`, `CurrentUser`, `VersionStore`, `HistoryFaults`, `app.history.strict`): §5.7.4 exactly — instant bound once, after the flush; origin/actor via `WriteOrigins.current/actor`, `originRefId` = rule id (AUTOMATION) / pending change id (APPROVAL); types in rank order, ids ascending, chunks of 500; `e(x) = max(t, x.validFrom + 1µs)`; CHILD-without-UPDATED → `PESSIMISTIC_WRITE` on the root, re-read open, re-compare; gap WARN `history.gap` (not for a pinned work); links row by row, no owner lock; final `s.flush()`. Failure policy: DB errors (`PersistenceException`/`DataAccessException`) rethrown; mapping errors rethrown in strict mode, otherwise retried per key and the failing key skipped with ERROR `history.capture.failed` (open version left stale).
- `HistoryTracker.touch(Class, ids, lock)` (`Propagation.MANDATORY`; refuses under a point): flushes, re-reads a copy the session already holds (`refresh`, `PESSIMISTIC_WRITE` when `lock`; a vanished row is detached), locks an unheld row when `lock`, marks the keys TOUCHED plus the owner's LINK keys.
- `HistoryReconciler.check()` (read-only `TransactionTemplate`): per type keyset chunks of 500 → `missing`, `drifted`; `orphaned` and `duplicates` by query; `linkDrift` per owner (live targets vs open rows, duplicate rows, open rows of a vanished owner). `ReconcileReport(checked, missing, drifted, orphaned, duplicates, linkDrift, repaired)` with `problems()`.
- `HistoryFaults` (interface, `beforeSnapshot(code, id)`) and `NoHistoryFaults` (`@Component`, no-op).
- `VersionStore`: `open(EntityManager, …)` and `openLinks(EntityManager, …)` overloads so the writer and reconciler read on their own session (the old signatures delegate).
- Config: `app.history.strict: false` in `application.yml`, `true` in `application-test.yml`.
- Tests:
  - `T/history/HistoryTestHooks` (`@Primary HistoryFaults` bean: `failSnapshotOf(code, id)`, `reset()`), `T/history/HistoryTestSupport` (bean: `clearVersions()` over every version table, links and `history_meta`; `versionsOf`, `allVersions`, `linksOf`; `touch(Class, ids)` = `HistoryTracker.touch` in its own transaction).
  - `IntegrationTestBase`: `TestBeans` declares both beans; the reset calls `historyFaults.reset()` and `historyTestSupport.clearVersions()` right after `mailTransport.reset()` (dispute delete before customers was already there); `@AfterEach historyIsInStep()` asserts `historyReconciler.check().problems()` is empty after **every** test.
  - `HistoryCaptureTest` (12): fixed clock moved a minute per write so the instant is known exactly; each case asserts one txId, every new version from `t`, origin USER and the actor, exactly one open version, the previous one closed at `t`, contiguous intervals, and the expected columns — customer create/update/delete (seats close with it); seat add/add/set-primary (both seats in one tx)/remove; invoice create/update/cancel; invoice create spending credit (invoice, allocation, credit payment, customer in one tx); `replaceItems` and `cancelWithRefund` (allocation shrunk then closed, payment credit and customer credit); payment record (2 allocations), update, `updateAmount` (old allocations closed, new one), void; promise create (link), update (link X closed / Y opened at `t`), override, clear, cancel (payment link closed); a payment that only joins a kept promise → one link row and no promise version (`applyShares` link-only); sweep (own tx, origin USER); dispute open (customer login as actor), approve with an `update_notes` change (dispute + invoice, one tx, admin notes), deny with notes; product create/rename/delete over HTTP, the rename writes no invoice version and `check()` is clean; every bulk action (invoice reassign/cancel, payment reassign, promise reassign/cancel, customer ADD_POC, product deactivate/activate) → one transaction per row.
  - `HistoryRollbackTest` (5): exception after a flush, `setRollbackOnly`, `recomputeAll(false)` over a stale promise (change reported, no version), a bulk with an ineligible row (rows' txIds distinct, the refused row untouched), and the OEMIV shape (one bound `EntityManager`: a rolled-back then a committed transaction → only the second's versions, all with its txId; the rolled-back txId appears nowhere).
  - `HistoryTimeTest` (4): one instant per transaction (5 versions); same-µs second transaction → `+1µs` (customer and invoice); clock moved back 3 h → `valid_to > valid_from` everywhere incl. links; no-op saves (identical update, unchanged save, promise `brokenNotifiedAt` only) write nothing.
  - `HistoryConcurrencyTest` (2): two transactions add different invoices to one promise, both flushed before either commits (barrier; no owner lock) → one open row per target, each its own txId, no promise version; two concurrent payments on one invoice → 3 contiguous versions in 3 transactions, paid 0 → 30|45 → 75.
  - `HistoryGuardTest` (3): insert, update and `touch` under `AsOf.with` refused (`AS_OF_READ_ONLY` in the cause chain), nothing changed; strict mode rethrows the injected mapping failure and both renames roll back; a lenient writer (strict=false) skips the failing record and versions the other.
  - `ModifyingQueryGuardTest` (4, plain scan): no `@Modifying` in a repository of a versioned entity (incl. `InvoiceItem`); no `createNativeQuery`/`executeUpdate`/`JdbcTemplate`/`nativeQuery = true` outside `**/*Upgrade.java`, `common/schema/**`, `history/**`, `GmailConnectionRepository`; allow-list entries exist; pattern self-test. Passes on the unchanged code incl. `RowVersionUpgrade` (CF-10).
- Mutation-checked: dropping the stale-process guard fails the OEMIV case; dropping the `+1µs` rule fails two `HistoryTimeTest` cases; skipping link writing fails 4 capture/concurrency tests (and `historyIsInStep`); ignoring `strict` fails the strict guard case.

**Deviations from DESIGN.md (with reasons)**
- **Collection events are mapped by owner entity, not by collection role.** In Hibernate 6.5.3 the event's `PersistentCollection.getRole()` is already null when the post-recreate event fires (verified). So any collection change on a `PaymentPromise` marks both of its link kinds (one extra link query), and one on an `Invoice` marks the invoice CHILD (its only collection is `items`); `Payment.allocations` is still ignored.
- **Earlier-slice test files changed (allowed: not baseline):** `SnapshotTest` (BE-06) — its `@AfterEach` used to delete every version, which would now leave live rows without history; it rebuilds history from the live rows instead (`clearVersions` + `touch`), and `aStoredVersionReadsBack…` clears the captured versions before storing its own. `CustomerLoginRegionTest.moveAcmeTo` (BE-04a) now calls `historyTestSupport.touch` for every row its SQL moved, as the BE-04a hand-off asked. `RegionSchemaUpgradeTest` (BE-02) touches the orphan dispute the upgrade really moves (a startup JDBC path; on a real pre-history database there are no versions yet).
- `HistoryTestSupport` is declared as a bean in `TestBeans` (not a scanned `@Component`, which would be picked up from test classes too).
- `VersionStore` gained session-taking overloads (see above): the design's writer reads open versions on its own session.
- `HistoryTracker.touch` re-reads a held copy of each row before marking it: after native SQL the session's copy is stale (region move: `updatable = false`), and the writer would otherwise version the old state.
- `HistoryWork` has no `needsInstant` and `HistoryTransactions` no `txId(em)`, and `history_transactions` is not written: all BE-11. The registrar does not register `POST_LOAD → LiveReadGuard` (BE-09a). `HistoryWork.pin` exists already for BE-08.
- Extra test beyond the list: the lenient-mode case in `HistoryGuardTest` (the production failure policy had no test).

**API-contract changes versus §6:** none (no endpoint touched).

**Known gaps / deferred**
- `reconcile()`, the reconciler schedule and `app.history.reconcile-interval-ms` are BE-08, as are the baseline and `/api/history/range`. Until then drift in production (strict=false skips, JDBC upgrades) is only detectable by `check()`.
- Startup JDBC upgraders that change versioned rows (`RegionSchemaUpgrade`, `PocSchemaUpgrade`) write no versions; per §3.11 they run before `HistoryBaseline` (BE-08), which catches missing rows (drift needs `reconcile`).
- Slice size ≈ 2,570 changed lines (about 1,650 of them tests), slightly over the 2,500 guide.

**Tests:** backend 325 run, 0 failures, 0 errors, 0 skipped (295 before + 30 new: `HistoryCaptureTest` 12, `HistoryRollbackTest` 5, `HistoryTimeTest` 4, `HistoryConcurrencyTest` 2, `HistoryGuardTest` 3, `ModifyingQueryGuardTest` 4), every one of them now followed by `historyIsInStep`. mail-service 24 run, 0 failures, 0 errors.

### BE-07 review fixes

All six findings were checked against the code and confirmed. Every one is fixed; none was rejected.

1. **MEDIUM: a stale copy versioned the old region.** Fixed in `HistoryWriter.rereadMoved`. For every type with a `region` attribute (Customer, Invoice, Payment, PaymentPromise, Dispute), the writer runs one query per chunk, `select e.id, r.id from <L> e left join e.region r where e.id in :ids`, and `refresh`es each held copy whose region differs from its row. This is safe because the commit flush has already run. It re-reads only moved rows, so a normal save costs one extra small query per region-bearing type. New test `HistoryCaptureTest.aPromiseSavedFromACopyOlderThanARegionMoveIsVersionedInItsNewRegion`, the reviewer's probe p1: live and open version are both SOUTH, and the version regions are default, SOUTH, SOUTH. This is also the §8.3 case BE-08 needs.
2. **MEDIUM: provenance other than USER was untested.** New `HistoryCaptureTest.aBoundOriginIsStampedWithItsReferenceAndTheActorItNames`:
   - `approval(77, admin).withActor(sam)` around the transaction gives APPROVAL, ref 77, actor sam (not the signed-in admin);
   - `automation(55, …)` gives AUTOMATION, ref 55, actor admin.
   The sweep case now runs with the SecurityContext cleared, as the scheduler does, and asserts SYSTEM, a null ref and a null actor on every row.
3. **LOW: `touch` on a held row that SQL deleted rolled the transaction back.** Fixed. `touch` first queries which ids still exist (chunked). It detaches held copies of missing ids without calling `refresh`, and refreshes only rows that exist. The `EntityNotFoundException` catch is gone. Test: `HistoryTrackerTest.aCopyWhoseRowTheSqlDeletedIsLetGoAndItsHistoryClosed`.
4. **LOW: `check()` joined the caller's transaction and cleared its session.** Fixed: the reconciler's template is `PROPAGATION_REQUIRES_NEW` (and read-only). OEMIV is off (`spring.jpa.open-in-view: false`), so REQUIRES_NEW always gets its own EntityManager. Test: `HistoryTrackerTest.checkInsideATransactionLeavesTheCallersSessionAlone`. Under the old code the auto-flush made `check()` report drift and the caller's entity was detached.
5. **LOW: untested branches.** Covering them exposed a real bug in the lock-and-recheck branch, so that branch is now fixed as well as tested.
   - **The bug:** the branch locked the root and re-read the open version, but compared it with the snapshot taken before the lock. Take two transactions that change different lines of one invoice, where the second snapshots while the first still holds the invoice. The second then wrote a version that put the first transaction's line back as it was.
   - **The fix:** the branch now does `s.refresh(live, PESSIMISTIC_WRITE)` (lock and re-read the root, which also avoids the version-checked `select … for update` of `lock()` on a `@Version` entity). It then snapshots again, through the same per-record failure policy, before re-reading and comparing the open version.
   - **The design's wording** (§5.7.4 "re-read open; re-compare") is kept. "Re-compare" now means against a fresh snapshot.
   - **New tests:**
     - `HistoryConcurrencyTest.twoTransactionsChangingDifferentLinesOfOneInvoiceLeaveAVersionWithBoth`. It uses a new one-shot hook, `HistoryTestHooks.onNextSnapshotOf`, and holds the lock for about 300 ms, well inside H2's lock timeout. The versions are [1,2], then [5,2], then [5,7].
     - `HistoryCaptureTest.aLineChangedOnItsOwnVersionsItsInvoice`, the CHILD-only path without contention.
     - `HistoryTrackerTest.aCopyTheSessionHoldsIsReReadSoTheVersionHasWhatTheSqlWrote`.
     - `HistoryTrackerTest.touchWithLockHolds{ARowTheSessionHolds,ARowTheSessionDoesNotHold}UntilCommit`: another transaction's update waits at least 300 ms, then goes through once the holder commits.
6. **LOW: guard hardening.** `ModifyingQueryGuardTest` now:
   - matches `JdbcTemplate` as a substring, which includes `NamedParameterJdbcTemplate`;
   - matches `JdbcClient` and `createStatement(`, `prepareStatement(` and `prepareCall(`;
   - has a new scan, `noQueryAnywhereBulkUpdatesOrDeletesAVersionedEntity`. It checks every `@Query` (string or text block, `update`/`delete from`/`delete`, any case) for a versioned entity target, whichever repository declares it, outside the allow-list.
   The self-test covers each new pattern. The main code still passes unchanged.

**Mutation-checked** on a scratch copy (build/mut07). Each of these fails at least one new test:
- dropping `rereadMoved`;
- dropping the re-snapshot after the lock;
- REQUIRED propagation in the reconciler;
- refreshing held copies without the existence check;
- not refreshing held copies;
- never locking in `touch`.

**Files changed:** `HistoryWriter`, `HistoryTracker`, `HistoryReconciler` (main); `HistoryTestHooks`, `HistoryCaptureTest`, `HistoryConcurrencyTest`, `ModifyingQueryGuardTest`, and new `HistoryTrackerTest` (test). Main code outside `history/` was not touched.

**Tests:**
- backend: 335 run, 0 failures, 0 errors, 0 skipped. That is 325 plus 10 new: HistoryCaptureTest +3 (now 15), HistoryTrackerTest 5, HistoryConcurrencyTest +1 (now 3), ModifyingQueryGuardTest +1 (now 5).
- The concurrency and tracker classes were rerun 3 more times, all green.
- mail-service: 24 run, 0 failures, 0 errors.

## BE-08 — History baseline, reconcile, range; the customer region move

**Built**
- **`B/history/HistoryBaseline`** (`SmartInitializingSingleton`, §5.7.9): `afterSingletonsInstantiated()` calls `run()` and logs ERROR `history.baseline.failed` and carries on if it throws. `run()` (public, returns the number of records versioned) holds `history_meta` row 1 with `PESSIMISTIC_WRITE` for the whole run (it creates the row in a `REQUIRES_NEW` transaction first when it is missing, then re-finds it with the lock; a duplicate-key race is caught). For each type it keysets over `select e.id from <Live> e where e.id > :last and not exists (open version) order by e.id` (500 per chunk). Each chunk runs in its own `REQUIRES_NEW` transaction, bound in `WriteOrigins.callWith(WriteOrigin.schema(), …)` around the transaction: `historyTransactions.current(em).pin(first ? startedAt : null)` then `historyTracker.touch(type, ids, false)`. `startedAt` = clock instant truncated to µs, taken once, on the first non-empty chunk of a first run. First run: sets `started_at` (only if something was copied) and `baseline_completed_at`. Later runs: gaps from now, with WARN `history.baseline.gaps` (plus the writer's per-record `history.gap`).
- **`HistoryRange`** (plain `@Component`, not an `AsOfSupport`, CF-17): `historyStartsOn()` reads `history_meta.started_at` on every call (the UTC date); `latestAsOf()` = today (UTC, from the clock bean) − 1; `requireAvailable(d)` throws `AsOfException.beforeHistory(start)` (400 `AS_OF_BEFORE_HISTORY`) for a day before the start.
- **`HistoryController`** (class `@AsOfIgnored`): `GET /api/history/range` (`isAuthenticated()`) → `{historyStartsOn, latestAsOf, timezone: "UTC"}`; `POST /api/admin/history/reconcile` (USER_MANAGE, method-level `@AsOfIgnored`) → `{checked, missing, drifted, orphaned, duplicates, linkDrift, repaired}` (counts).
- **`HistoryReconciler.reconcile()`** (§5.7.8): runs the check, which now also keeps the ids behind each finding. It then repairs per type in 500-id chunks, each in a `REQUIRES_NEW` transaction under `WriteOrigin.reconcile()` bound around it:
  - Records that are missing, drifted or orphaned: `historyTracker.touch(type, ids, true)`. This locks each live row before the writer re-reads it, so a record a concurrent save put back in step is skipped by the writer's `sameState`.
  - Duplicates: every open version but the newest is closed at the newest's `valid_from` (never before its own `valid_from + 1µs`), then touched.
  - Link drift: open link rows doubled per (owner, target) are closed down to the newest, then the owners are touched with a lock, which re-inserts missing rows and closes rows of owners that are gone.
  - A failing chunk is logged (`history.reconcile.failed`) and rolled back; the other chunks continue.
  - The run logs WARN with the counts, or INFO when history is in step. `repaired` is the number of distinct records (and link owners) whose versions the repair actually opened or closed.
- **`HistoryScheduler`**: `@Scheduled(fixedDelayString = initialDelayString = "${app.history.reconcile-interval-ms:86400000}")` → `reconcile()`, catching and logging errors.
- **`HistoryWork.written()` / `HistoryWriter`**: the writer records `"CODE id"` for every record (and every link owner) whose versions it opened or closed. This is how `reconcile` counts `repaired` exactly (see deviations).
- **Region move (`B/region`, §5.6.8)**:
  - `RegionMoveParticipant` is an SPI with default `beforeCustomerMoved` (veto by throwing), `afterCustomerMoved` (returns a count) and `name()`.
  - `CustomerRegionMove` is the `customer_region_moves` table: `id`, `customer_id` NOT NULL, `from_region_id`, `to_region_id` NOT NULL, `moved_at` NOT NULL, `moved_by_user_id`, index `idx_crm_customer_moved (customer_id, moved_at)`, no FK. It is append-only.
  - `CustomerRegionMoveRepository` extends `JpaRepository<CustomerRegionMove, Long>` and adds `findByCustomerIdOrderByMovedAtAscIdAsc`, plus the five native `@Modifying(flushAutomatically, clearAutomatically)` updates of §5.6.8 step 11. Invoices and customers get `version = version + 1`.
  - `RegionMoveDtos` has `MoveRequest{regionId @NotNull, version, reason ≤ 500}`, `Moved{invoices, payments, promises, disputes, other}` and `MoveResult{customer, moved, warnings}`.
  - `CustomerRegionMoveService.move` implements steps 1–17 exactly:
    - `requireLive`, then customer `findByIdForUpdate`, then `requireVisible(CUSTOMER)` (404), then `requireIn(CUSTOMER_MANAGE, from)` (403).
    - Target: `requireReachable` (404, which also covers an unknown id), then `regionService.get`, then `requireIn(CUSTOMER_MANAGE, to)` (403), then inactive → 400 "Region X is inactive", then same region → 400 "The customer is already in X".
    - A stale `version` → `OptimisticLockingFailureException` (409).
    - Invoices `findByCustomerIdForUpdate` (ascending ids), then the payment, promise and dispute ids.
    - `participants.beforeCustomerMoved`.
    - Warnings (read only), then the native updates, then `historyTracker.touch` for the Customer, Invoice, Payment, PaymentPromise and Dispute ids.
    - Insert the move row (clock instant, mover), then `afterCustomerMoved` counts go into `moved.other` (a TreeMap keyed by name).
    - Re-read the customer, write audit `CUSTOMER_REGION_CHANGED` (before `{regionId, regionCode}`, after `{regionId, regionCode, moved}`, reason, `region_id = to`), and return `MoveResult` with `customerService.toDto`.
  - Warnings name:
    - seat holders: "colin.collections is Collection POC of Acme Ltd but has no access to South";
    - distinct invoice Sales POCs and payment and promise Collection POCs: "sam.sales is Sales POC on 2 invoices but has no access to South".
    - One `withAccess` query decides who is named. Nothing is removed.
- **`CustomerController`**: `POST /api/customers/{id}/move-region` (CUSTOMER_MANAGE, WRITE, i.e. 400 `AS_OF_READ_ONLY` when historical).
- **Config**: `spring.task.scheduling.pool.size` 3 → 4 (email sweeper, promise sweeper, history reconcile, one spare); `app.history.reconcile-interval-ms: 86400000` (test: 3600000).
- **Test harness**: the `IntegrationTestBase` reset calls `moveParticipant.reset()` and `customerRegionMoveRepository.deleteAll()` (after `clearVersions`, before gmail). `TestBeans` declares `TestMoveParticipant` (in `T/region`), a steerable `RegionMoveParticipant` (veto, reported rows, recorded calls). `ModifyingQueryGuardTest` allow-lists `region/CustomerRegionMoveRepository.java` (and asserts it exists). `ViewScopeInventoryTest`:
  - `POST /api/customers/{id}/move-region` is in WRITES;
  - `GET /api/history/range` and `POST /api/admin/history/reconcile` are in GLOBAL_EXEMPT;
  - all three are pinned in the classification test (WRITE, IGNORED, IGNORED).
- **Tests (23 new):**
  - `T/history/HistoryBaselineTest` (6):
    - A first run over a seeded family of every type (history cleared) versions every row from `started_at` with origin SCHEMA, including the promise link, and `check()` is clean afterwards.
    - A second run changes nothing (the same version ids and meta).
    - A later gap gets a version from now, while others and `started_at` stay put.
    - An empty DB leaves `started_at` null, and `/api/history/range` answers `null`, `latestAsOf` and UTC.
    - A day before the start → `AS_OF_BEFORE_HISTORY` "History is available from 4 May 2026", while the start day is allowed, and a customer login can read the range.
    - An injected mapping failure: startup carries on, nothing is half-written, the run is still "first", and the next run completes.
  - `T/history/HistoryReconcilerTest` (5):
    - A bulk JPQL `update Invoice set notes` is reported drifted, then `reconcile()` writes exactly one RECONCILE version at the repair instant and closes the old one there (`repaired` 1). A second reconcile repairs 0.
    - An orphan is closed and a missing record is inserted (RECONCILE).
    - A doubled open version is closed at the newest's start, and a deleted link row is put back (RECONCILE).
    - Lock and recheck: a save holding the invoice row lock (seen waiting via `information_schema.sessions.blocker_id`) fixes it by hand while the repair waits. The repair then writes nothing (`repaired` 0; the versions are the USER one and the save's SYSTEM one only).
    - `POST /api/admin/history/reconcile` as admin gives the counts and a RECONCILE version with admin as the actor. A CASHIER gets 403. A past `asOf` still runs (housekeeping).
  - `T/region/CustomerRegionMoveTest` (10):
    - Everything moves: DTO region and `version + 1`, `moved` counts, every row in S, customer and invoice row versions bumped, the move row (from, to, instant, mover), the audit row (region S, reason, before and after JSON), and participants told before and after.
    - History: every moved row's previous version (N) ends at the move instant and a new open one (S) starts there, all in one `txId`, with the actor set. `VersionStore.validAt` 1µs before the move gives N and at the move gives S.
    - Warnings as above; seats and POCs are kept; moving back once everyone is granted gives no warnings.
    - Refusals, each leaving rows, versions, row versions, move rows, audit, seats and participant calls unchanged:
      - READ in the source → 403; READ in the target → 403; invisible customer → 404;
      - ungranted target → 404; unknown target → 404; inactive → 400; same region → 400; stale version → 409;
      - missing `regionId` → 400 fieldErrors; reason over 500 characters → 400;
      - historical `asOf` → 400 `AS_OF_READ_ONLY`; a role without CUSTOMER_MANAGE → 403.
    - A customer login whose role may manage customers → 404.
    - A NORTH-only reader gets 200 before and 404 after (customer, invoice, payment, promise, dispute), and an empty list.
    - A participant's veto → 400 with its message, and everything is rolled back.
    - `moved.other.TestMoveParticipant` = 3.
    - A stale `PaymentPromise` copy saved after the move keeps S, live and in its version.
    - A broken promise after the move is not sent to its NORTH-only Collection POC but to SOUTH's PROMISE_MANAGE holder (SC-17).
  - `T/region/RegionConsistencyTest` (2): two families created over HTTP (customer with `regionId`, invoice, payment, promise, dispute by the customer login) in N and S, moved across both ways, then a new invoice after the move. JPQL joins find no invoice, payment, promise or dispute whose region differs from its customer's. A control proves the check finds a payment planted in the wrong region with SQL (then put back).

**Deviations from DESIGN.md (with reasons)**
- **`CustomerDto` gains a trailing `version` (`@JsonInclude(NON_NULL)`, the customer's `@Version`).** §6.5 does not list it, but §6.4's move request carries `version` and §5.6.8 step 6 checks it. Without the field no client could ever send it. FE-03 already parses and sends it, and `CARRY-FORWARD.md` ("[FE-03 rejected, cross-stream] Move-region stale check can never run") asked for exactly this. That carry-forward item is now **resolved**. `CustomerDtos`/`CustomerService` are outside the slice's file list; the change is one component plus its argument.
- **`HistoryWork`/`HistoryWriter` (BE-07 files) record which records the writer changed (`HistoryWork.written()`).** The design gives `repaired` in the report but not how to count it. Counting the records submitted would over-count rows that lock-and-recheck skips. The writer is the only place that knows what it really wrote, so it now records a `"CODE id"` string for each record it opens or closes a version of. The cost is one set entry per changed record per transaction.
- **"`asOf` before the start → 400" and "`asOf` before the move shows the old region" are asserted without an HTTP as-of read.** Until BE-09a, `NoHistoryYet` is the only `AsOfSupport`, so every past `asOf` on an AWARE endpoint answers 400 `AS_OF_UNSUPPORTED` (CF-17 keeps `HistoryRange` out of the interceptor on purpose). The baseline test calls `HistoryRange.requireAvailable` directly and checks the code and message. The move test reads what an as-of read would answer from, `VersionStore.validAt` at the instant just before the move and at the move. BE-09a's `AsOfListTest` (D3 moves A to S) covers the HTTP side.
- **A test participant is registered in `IntegrationTestBase.TestBeans`** (`TestMoveParticipant`, reset before every test), following the `HistoryTestHooks` pattern. The design requires "a test participant's veto" but allows no `@MockBean` or per-class `@Import`. As a result, every move in the test suite reports `other: {"TestMoveParticipant": 0}` by default. In production `other` is `{}` until BE-11, BE-12 and BE-15 add their participants.
- **The reconciler catches a failing repair chunk** (it logs ERROR and continues with the other chunks) rather than aborting the whole run. The design only says the schedule is wrapped in try/catch. The rolled-back chunk is found again by the next run.
- **`RegionMoveParticipant.name()` uses `ClassUtils.getUserClass(getClass()).getSimpleName()`**, so a proxied participant (for example a `@Transactional` one in a later slice) still reports under its own class name.
- **Warnings list the people named once per role and record kind.** The wording follows §6.4's example ("X is Sales POC on 4 invoices but has no access to South"; seats: "X is Collection POC of Acme Ltd but has no access to South"). Seats come first, then invoices, payments and promises, each ordered by username.
- The move's target checks run in the order `requireReachable` (404; this also covers an unknown id, and a customer login, which holds no other region) → `get` → `requireIn(to)` (403) → inactive (400) → same (400). This is §5.6.8 step 5's order with the reachability check first, so an ungranted region is never told apart from a missing one.

**API-contract changes versus §6**
- Additive only: `CustomerDto.version` (see above; the frontend already expects it).
- Delivered as specified otherwise:
  - `POST /api/customers/{id}/move-region` (§6.4 request, response and errors);
  - `GET /api/history/range` and `POST /api/admin/history/reconcile` (§6.10).

**Known gaps / deferred**
- `HistoryRange` becomes the `AsOfSupport` (and `NoHistoryYet` is deleted) in BE-09a. Until then historical reads still answer `AS_OF_UNSUPPORTED`, as designed.
- Participants arrive in later slices: `AutomationMoveParticipant` (BE-11), `TaskMoveParticipant` (BE-12, with its own allow-listed native update and touch) and `PendingChangeMoveParticipant` (BE-15, veto or close). `RegionMoveCooldown` reads `customer_region_moves` in BE-15.
- `CustomerLoginRegionTest.moveAcmeTo` (BE-04a) still moves with SQL plus `touch`. It could now call the endpoint, but it tests login rights, not the move, so it was left alone.
- The baseline's "instances that boot together serialise" rests on the `history_meta` row lock. No test boots two contexts concurrently, because a second context would wipe the shared H2 database.

**Tests:** backend 358 run, 0 failures, 0 errors, 0 skipped (335 before + 23 new: HistoryBaselineTest 6, HistoryReconcilerTest 5, CustomerRegionMoveTest 10, RegionConsistencyTest 2). mail-service 24 run, 0 failures, 0 errors.

## BE-09a — As-of reads I: context, executor, customers, invoices, products

**Built**
- **Binding a past date.** `HistoryRange implements AsOfSupport`, and `common/scope/NoHistoryYet` is deleted in the same change. Exactly one `AsOfSupport` bean exists (CF-17). A historical `asOf` on an AWARE handler is now bound. The interceptor checks it with `requireAvailable` (`AS_OF_BEFORE_HISTORY` before `history_meta.started_at`), sets header `X-View-As-Of: <date>`, and echoes `PageResponse.asOf`.
- **`B/history/Temporal`** (static, §5.7.12):
  - `from`, `validOrNull`, `fromValid`, `join`, `subquery` and `SubBody`.
  - `join` from a live from is the old `ColumnDef.leftJoin` for LEFT, and `from.join(attr, type)` otherwise.
  - `join` from a version with a registered `HistoryModel` navigation is an entity join to the target version class. Its alias is `t_<attr>_<type>`, and an existing join is found again through `getSqmJoins()`. The ON clause is the navigation plus `validOrNull`.
  - `join` from a version with any other attribute uses the read-only live association (`salesPoc`, `collectionPoc`, `user`, `region`).
- **`B/history/Records`** (`find`, `findAll`).
  - Live: `em.find`, or one chunked criteria `in` query, with results in the order asked.
  - As of a date: `VersionStore.validAt` then `rehydrate`. A record with no version at the point is not found, so the existing not-found messages are unchanged.
- **`B/history/LiveReadGuard`** (`PostLoadEventListener`, registered in `HistoryListenerRegistrar` for `POST_LOAD`).
  - While a point is bound, loading a live versioned entity throws `AS_OF_UNSUPPORTED`.
  - `allow(Supplier)` switches the check off. It is re-entrant through a depth counter and is cleared when the outermost call returns.
- **`B/history/Historical`** (marker interface). Five unmapped subclasses in `B/history/types`: `HistoricalCustomer`, `HistoricalInvoice`, `HistoricalInvoiceItem`, `HistoricalProduct` and `HistoricalCustomerPoc`.
- **`rehydrate` implementations:**
  - `CustomerHistory`: scalars and the region.
  - `ProductHistory`: scalars.
  - `InvoiceHistory`:
    - scalars;
    - a shallow `HistoricalCustomer` at T;
    - `salesPoc` from `usersOrStub`;
    - the region;
    - `items` from `items_json`, each with `invoice = this` and a `HistoricalProduct` at T.
  - `SeatHistory`: a shallow customer at T, `user` from `usersOrStub`, and scalars.
  - Rehydrated records have `version = null`, so the DTOs omit it.
- **`Rehydration`**: memoised batch loaders.
  - `at(Class, ids)` / `at(Class, id)` load any versioned type at the point with one `validAt` query per kind, for ids it has not read before.
  - `usersOrStub` uses `findAllById`. A deleted user becomes a transient "Deleted user" who is inactive.
  - `regions(ids)` / `region(id)` run one query and are live. A deleted region becomes an id-only stub.
- **`B/history/HistoryQueries`**:
  - `sumOutstandingByCustomerAt` and `sumOverdueByCustomerAt` are the `InvoiceRepository` JPQL rewritten over `InvoiceVersion`, with the enum passed as a parameter and chunks of 500.
  - `seatsAt(ids, p)` is ordered `pocType asc, primary desc, id asc`.
- **`TableQueryExecutor`** (§5.7.13):
  - `run` switches to `runHistorical` when `AsOf.active()` and the type is versioned. It roots on the version class with no fetch joins, keeps the same where, order and paging (including the 5000 cap), then calls `rehydrate` with a fresh `Rehydration`.
  - `count`, `ids` and `aggregate` use `Temporal.from`.
  - `predicates` adds `Temporal.validOrNull` first.
  - Live SQL is unchanged.
  - The paging code moved into a shared `page(...)` helper. `VersionStore` and `UserRepository` are now constructor-injected.
- **`ColumnDef.nested`** uses `Temporal.join(root, assoc, LEFT, cb)`. As a result, `customerName` at T is the name at T, and POC and region names stay live.
- **Sites switched to `Temporal` (§5.7.14):**
  - `TableSchemas.customerOutstanding`, `pocSeatPredicate` and `promiseLinkPredicate` (the last goes through `Temporal.join(promise, association, INNER)`);
  - `ScopeResolver.customerHasPocSeat` and `customerHasInvoiceOwnedBy`, so the book at T is the book then;
  - `CustomerService.outstandingSubquery` and `seatSubquery`.
- **By-id reads (§5.7.17):**
  - `CustomerService.get`, `InvoiceService.get` and `ProductController GET /{id}` go through `records.find`.
  - `CustomerService.toDtos` in as-of mode takes outstanding, overdue (against `InvoiceDates.today()`, which is `Today.date()` = D) and seats from `HistoryQueries`.
  - `ProductController POST /export` uses `records.findAll`, so it keeps the filter order and answers at T.
- **Capability flags** are already false in historical mode, through `RecordAccess` (from BE-04b). `canManage` and `canMoveRegion` are asserted false at T.
- **Tests (34 new; `ViewScopeTest` changed):**
  - `T/history/AsOfScenario` (abstract, D0–D4 plus TODAY):
    - D1: Acme (NORTH) with X 1000 and Y 500, Beta with B 100, Sam as seller and Success seat.
    - D2: payment 400 on X, promise 600 linked to X, Y's lines replaced.
    - D3: rename to Zeta Holdings, move to SOUTH, seat removed, Widget renamed Widget Pro, Gamma and Gadget created.
    - D4: a dispute voids the payment, and Y is cancelled.
  - `AsOfContextTest` (6):
    - invalid and future dates are refused;
    - today is live (header `live`, no `asOf`, flags true);
    - a past date is bound (header, `asOf`, the list at T, flags false, product list, customer detail);
    - `AS_OF_BEFORE_HISTORY` with its message, and the start day is answered;
    - ThreadLocal hygiene;
    - endpoints whose as-of answer comes in a later slice refuse with `AS_OF_UNSUPPORTED` and work live.
  - `AsOfListTest` (9): customers, invoices and products at D0–D4.
    - Values, `totalElements`, and `overdue`/`daysOverdue` with today = D.
    - `relative` presets measured from D.
    - Sort and filter by `customerName` at T, `outstanding` at T, `successPocUserId` at T and `isEmpty`, `regionId` at T, status and `paidAmount`.
    - Customer and invoice tiles and `/summary`, including `customerId` and region filters.
    - `POST /export` CSV at T for customers (including the seat column), invoices and products.
    - The live list is untouched.
  - `AsOfDetailTest` (7):
    - customer, invoice and product `GET /{id}` at T, with `version` absent or null and flags false;
    - 404 before creation, with the same message;
    - invoice lines after `replaceItems`, and the product name on a line at T;
    - today's grants applied to the region at T (a NORTH-only admin sees Acme at D2, but not at D3 or live);
    - the book at T (a seat taken away on D3).
  - `HistoricalObjectsAreWriteProofTest` (3): `save`, `saveAndFlush`, `merge` and `persist` of rebuilt invoices, customers and products throw, and so does a new seat that points at a rebuilt customer. Live rows and counts are unchanged.
  - `LiveReadGuardTest` (3):
    - `findById`/`findAll` of versioned types inside `AsOf.with` throws `AS_OF_UNSUPPORTED`;
    - nested `allow` permits the read, and the check returns once `allow` ends;
    - unversioned types and live reads are never refused.
  - `AsOfQueryCountTest` (2):
    - An admin page of 20 invoices at T reads `invoice_versions` 2 times, `customer_versions` once and `product_versions` once.
    - A live page, and `asOf` = today, read `_versions` 0 times.
  - `TemporalConventionTest` (2, plain scan):
    - no `.from(X.class)` for a versioned X, and no `.join("<association to a versioned type>")`, in `common/query`, `poc`, `dashboard`, `task`, `assignee` or `*Service.java` outside `history/`;
    - a literal `PENDING` map (`dashboard/DashboardService.java` → BE-10) must still be needed;
    - a self-test of the scan itself.
  - `T/common/scope/AsOfReadOnlyTest` (2):
    - Every handler that `ViewScopeInterceptor.classify` calls WRITE (discovered from the handler mapping; more than 40 of them) returns 400 `AS_OF_READ_ONLY` with a past `asOf`. Row counts of 31 tables, including every version table and its open-version count, are unchanged.
    - The housekeeping endpoints are not refused, and five of them answer 200 with header `live`.
  - `ViewScopeTest` (BE-01 file, not baseline): its historical case now expects the date to be bound and answered (§8.1, "before BE-09a").
- Mutation-checked on the working tree (scratch `build/mut09a`, every file restored and verified byte-identical afterwards). Each of these is caught by at least one test:
  - `ColumnDef.nested` back to `leftJoin`;
  - `CustomerService.toDtos` using the live sums;
  - dropping the `POST_LOAD` registration;
  - `TableSchemas` subqueries back to `sq.from(...)`;
  - `Temporal.join` without `validOrNull`;
  - `ScopeResolver` live subqueries;
  - the executor without `validOrNull`.

**Deviations from DESIGN.md (with reasons)**
- **Endpoints answered as of a date in a later slice refuse a past date (400 `AS_OF_UNSUPPORTED`) instead of answering live.** Once `HistoryRange` binds dates, these AWARE handlers would otherwise return today's data under a historical `X-View-As-Of` header, which is the C-1 failure. The guards are:
  - an `if (AsOf.active()) throw AsOfException.unsupported()` line in `DashboardController` (all five figures, via `refuseAPastDate()`), `AuditController.history`, `DocumentController` list and count, `EmailController` `GET ""` and `GET /{id}`, and `CustomerController GET /{id}/pocs`;
  - in `Payment`, `Allocation`, `Promise` and `DisputeHistory.rehydrate`, which throw `AS_OF_UNSUPPORTED` instead of `UnsupportedOperationException` (a 500). The payment, promise and dispute lists and by-id reads therefore refuse. By-id reads are also stopped by `LiveReadGuard`.
  - These files are outside the slice's Modify list. Each guard is one line. **BE-09b must remove the rehydrate throws, and BE-10 must remove the controller guards** as it implements each site. `AsOfContextTest.aReadNotYetAnsweredFromHistoryRefusesAPastDate` lists them, and those slices should shrink that list.
- `GET /api/payments/summary` and `/api/promises/summary` are not refused. Their aggregates already root on the version classes through `Temporal.from`, so they answer from versions. BE-09b's tests should cover them.
- `Rehydration` loads regions with one batch query (`regions(ids)`) instead of `em.getReference`. This keeps the load batched and non-lazy, and a region deleted since T becomes an id-only stub rather than a proxy that fails on access.
- `Rehydration.at` handles a record missing from history (a baseline gap). It reads the live row inside `LiveReadGuard.allow`, snapshots it and rehydrates it, with WARN `history.gap.read`, rather than dropping a parent that a version points at. §5.7.15 describes this fallback only for products.
- `ProductController POST /export` is switched to `records.findAll` (§5.7.17 names only `GET /{id}`). Without it, the product export at T, which `AsOfListTest` requires, would fail on `LiveReadGuard`. The customer and invoice exports already go through the executor.
- `HistoryQueries.seatsAt` sorts in Java, after `VersionStore.validAtBy`, which orders by record id.
- `TemporalConventionTest` keeps a literal `PENDING` map (`DashboardService` → BE-10), in the style of `PENDING_ROWS` in `RegionParamCoverageTest`. BE-10 must empty it.

**API-contract changes versus §6:** none. Historical reads now answer on customers, invoices and products, as §6.0 specifies. The interim `AS_OF_UNSUPPORTED` answers above use an error code already in the catalogue.

**Known gaps / deferred**
- BE-09b: payments, allocations, promises and disputes (`rehydrate`, `Records` sites), `PaymentController.credit` (it already answers at T, because it goes through `CustomerService.get`), and `EmailTargets`.
- BE-10: dashboard, POC tab, documents, emails and audit at T; removing the controller guards; `AsOfEndpointCoverageTest`/`AsOfParityTest`; and the literal "every DTO flag ANDs `!historical`" sweep. `RecordAccess` already returns false, which covers the flags on customers and invoices.
- Slice size: about 2,190 changed lines of main and test code, within the bound.

**Tests:** backend 392 run, 0 failures, 0 errors, 0 skipped. That is 358 before plus 34 new: AsOfContextTest 6, AsOfListTest 9, AsOfDetailTest 7, HistoricalObjectsAreWriteProofTest 3, LiveReadGuardTest 3, AsOfQueryCountTest 2, TemporalConventionTest 2, AsOfReadOnlyTest 2; ViewScopeTest is still 13. mail-service 24 run, 0 failures, 0 errors.

### BE-09a review fixes

- **Fixed (medium): the live fallback for a gap in history now applies only to products.** Before, `Rehydration.at` filled a missing version of *any* parent with today's live row, logging only a WARN. An invoice or seat at T whose customer had a history gap therefore showed the customer's current name, region and credit as history. It still logs `WARN history.gap.read`, but now only `Product` (a product on an invoice line, §5.7.15) is read live through `LiveReadGuard.allow`. Every other type throws `AsOfException.unsupported()` (400 `AS_OF_UNSUPPORTED`), because §5.7.16 says an unanswerable read must fail rather than pass today's data off as history. This supersedes the implementer's earlier deviation "Rehydration.at falls back to a snapshot of the live row ... for a record that has no version at T". Test: `AsOfDetailTest.aGapInHistoryIsRefusedRatherThanFilledWithToday`. It deletes Acme's `customer_versions` and expects invoice X at D2 to give 400 `AS_OF_UNSUPPORTED`, customer Acme at D2 to give 404, and invoice B (Beta) to still give 200. It then deletes Widget's `product_versions` and expects B at D2 to read the live product name. At the end it repairs both with `historyTestSupport.touch`, as the reconciler would, so the after-each in-step check passes.
- **Fixed (low): seats are in the same order live and as-of.** `CustomerService.toDtos` now sorts `successPocs`/`collectionPocs` in Java with `SEAT_ORDER` (primary first, then id), the same order `PocService.listFor` and `HistoryQueries.seatsAt` use. This applies in both modes. The live SQL (`findByCustomerIdIn`) is unchanged. Test: `AsOfDetailTest.seatsAreListedInOneOrderLiveAndAsOf`. Beta gets a non-primary seat and then a primary one, and the test expects the primary first both live and at D4.
- Rejected: none.
- Final counts: backend 394 run, 0 failures, 0 errors, 0 skipped (392 + 2 new in AsOfDetailTest, now 9). mail-service 24 run, 0 failures, 0 errors.

## BE-09b — As-of reads II: payments, allocations, promises, disputes, remaining by-id sites

**Built**
- **`B/history/types/Historical{Payment,PaymentAllocation,PaymentPromise,Dispute}`**: unmapped marker subclasses, like BE-09a's.
- **`rehydrate` implementations** (the `AS_OF_UNSUPPORTED` throws BE-09a left in them are gone):
  - `PaymentHistory`: scalars; a shallow `HistoricalCustomer` at T; `collectionPoc` from `usersOrStub`; the region; `allocations` at T, each a `HistoricalPaymentAllocation` with `payment = this` and its invoice at T (ordered by allocation id, as live).
  - `AllocationHistory`: rebuilds each allocation's payment at the same point (`ctx.at(Payment)`) and returns the matching element of that payment's `allocations`. So an allocation, its payment and the payment's list are one object graph, and payment and allocation never rebuild each other recursively.
  - `PromiseHistory`: scalars (including `updatedAt` and `brokenNotifiedAt`); customer at T; `collectionPoc`; region; `invoices`/`payments` from `entity_link_versions` at T, each rebuilt at T (a payment brings its allocations at T).
  - `DisputeHistory`: scalars and the region. (`internal` → `false` for old versions is BE-19's, as §5.7.15 says.)
- **`Rehydration`** gained two memoised batch loaders:
  - `allocationsAt(paymentIds)` uses `VersionStore.validAtBy(PaymentAllocationVersion, "paymentId", …)`. A payment with none maps to an empty list.
  - `linksAt(kind, ownerIds)` uses `VersionStore.linksAt`. It returns targets in ascending id, one query per kind for the owners not read before.
- **By-id reads through `Records` (§5.7.17):**
  - `PaymentService.get`, `PaymentPromiseService.get` and `DisputeService.get`.
  - `DisputeService.toDto`: the customer-name lookup and `describeTarget` (invoice or payment) go through `records.find`. A dispute at T therefore names its customer and its target (number, amount) as they were at T. Live reads are `em.find`, as before.
  - `EmailTargets.loadUnscoped`: CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE and PRODUCT go through `records.find`. **The PRODUCT branch of `loadScoped`** does too (CF-23). The now-unused promise, dispute and product repositories were dropped from `EmailTargets`.
  - `PaymentController.credit` needed no change. It already answers at T through `CustomerService.get` (BE-09a), and the test now asserts that.
- **Payment, promise and dispute lists** now answer as of a date through the executor's root swap. The rehydrate throws are gone, so lists, `/summary`, filters (including the promise `invoiceId`/`paymentId` link filters at T), sorts and exports all answer at T.
- **Tests (12 new; 4 earlier-slice test files extended):**
  - `T/history/RoundTripTest` (2):
    - Every record of every versioned type, with every column filled (`SnapshotTest.fillNulls`, in a rolled-back session with flush mode COMMIT), goes through `snapshotAll` then `rehydrate`. It comes back equal attribute by attribute:
      - values by value, and the row version null;
      - associations by id, and two levels down by the target's own attributes (for example, a line's product name comes from `product_versions`);
      - collections element by element (lines, allocations, promise links);
      - a child's back-pointer is the rebuilt parent itself.
    - A line names its product as it was at the point: "Widget" before a rename, "Widget Pro" after.
    - Mutation-checked: dropping `setNotes` in `PaymentHistory`, `setPayments` in `PromiseHistory`, or `setResolvedAt` in `DisputeHistory` each fails it.
  - `AsOfListTest` (+4):
    - Payments at D1–D4: allocations and invoice figures that day; customer name and region at T; VOIDED with no allocations on D4; `status`, `regionId` and `customerName` filters; `/summary`; live flags.
    - Promises at D1–D4: PARTIALLY_KEPT with its payment link on D2, OPEN with no payment on D4. Link filters `invoiceId:eq`, `paymentId:eq` and `paymentId:isEmpty` at T; status and region filters; `/summary`.
    - Disputes at D1–D4, plus a second dispute (below): status, customer name and target amount as they were; filters; `customerId`.
    - Payment, promise and dispute exports at T.
  - `AsOfDetailTest` (+6):
    - Payment, promise and dispute `GET /{id}` at T, with 404 and the unchanged message before creation, and flags false.
    - `/api/payments/credits/{id}` names the customer as it was.
    - Today's grants applied to the region at T for payments, promises and disputes (NORTH-only admin), plus the customer login's own records and another customer's (404).
    - `theRecordBehindAnEmailTabIsFoundAsItWas` (CF-23): under `AsOf.with`, `EmailTargets.load/requireVisible` answers PRODUCT at T ("Product Widget" on D2, "Product Widget Pro" on D3, a product created later is "Product not found"). It does the same for CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE without tripping `LiveReadGuard`.
  - `AsOfScenario.betaDisputesB()` is an extra story used by the dispute tests. On D2 Beta's login disputes invoice B (100). On D3 Beta is renamed Beta Group, B becomes 150 and the dispute is denied.
  - `AsOfContextTest`: payments, promises and disputes left the "not yet answered" list.
  - `SnapshotTest`: `fillNulls` and `read` are now package-visible so `RoundTripTest` can reuse them.

**Deviations from DESIGN.md (with reasons)**
- **The HTTP product email tab (`GET /api/emails?entityType=PRODUCT&entityId=…&asOf=…`) still answers 400 `AS_OF_UNSUPPORTED`.** §8.4 lists "the product email tab answers at T" under BE-09b. But BE-09a put an `AS_OF_UNSUPPORTED` guard on `EmailController GET ""`, and the list's own as-of rule (`occurredAt <= p`) is BE-10's `EmailService.list` item. Answering the tab now would mean doing BE-10's work, and dropping the guard without it would return today's emails under a historical header (C-1). BE-09b makes the part CF-23 is about work: the target lookup (`loadScoped` PRODUCT) no longer hits `LiveReadGuard`. That is tested at the service level. BE-10 removes the controller guard and adds the `occurredAt` filter, which makes the HTTP tab answer.
- **`DisputeController POST /export` reads through `records.findAll`** instead of `repository.findAllById`. It is not in the Modify list, but without the change the dispute export at T (part of "disputes at D1–D4") trips `LiveReadGuard`. This is the same fix BE-09a made in `ProductController`. As a side effect, the live CSV rows now follow the requested sort order, where before they came in database order.
- **An allocation's invoice is the full memoised `HistoricalInvoice` at T** (lines included, one `product_versions` query per page), not a "shallow" one. It is the same object the rest of the page uses, and `PaidInvoiceDto` needs only its figures. Building a second, reduced invoice type would add code for no gain.
- `AllocationHistory.rehydrate` reaches allocations through their payment, as described under Built. This is how the circular payment ↔ allocation rebuild is resolved.

**API-contract changes versus §6:** none. Payments, promises and disputes (list, `/summary`, `/{id}`, `/export`) now answer historical requests, as §6.0 specifies.

**Known gaps / deferred**
- BE-10:
  - dashboard, POC tab, documents, the email tab list/get (`occurredAt <= p`) and audit at T;
  - removing those controller guards;
  - `AsOfEndpointCoverageTest` and `AsOfParityTest`.
- **Flaky test found (pre-existing, BE-07 code, not changed here).** `HistoryConcurrencyTest.twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink` failed once in a full run, through `historyIsInStep` ("link LINK:PROMISE_INVOICE …: [a, b, c] live, [a, c] open"). It then passed 4/4 in isolation and in the next full run.
  - Likely cause: a race in `HistoryWriter`'s link step. Transaction A reads the owner's current targets before B commits, then reads the open link rows after B has committed. It therefore sees B's new open row as a target that is no longer linked, and closes it.
  - Consequence: under two concurrent relinks of one promise, a link's history can be closed wrongly. The reconciler repairs it later.
  - This should go to the final wave or a BE-07 follow-up. A possible fix is to lock the owner (or its link rows) during the link step.

**Tests:** backend 406 run, 0 failures, 0 errors, 0 skipped. That is 394 before, plus 12 new: RoundTripTest 2, AsOfListTest +4 (now 13), AsOfDetailTest +6 (now 15); AsOfContextTest is still 6. mail-service 24 run, 0 failures, 0 errors.

### BE-09b review fixes
- **Fixed (medium): the dispute list and export at T looked up each row's customer and target one by one (N+1).** `DisputeService.toDtos(List<Dispute>, RecordAccess)` now reads the page's customers, invoice targets and payment targets with one `records.findAll` per kind; `toDto(d, access)` delegates to it, and `describeTarget` is gone. `DisputeController` `list` and `POST /export` use `toDtos`. New `AsOfQueryCountTest.anAsOfDisputePageReadsWhatItsRowsPointAtInBatches`: 15 disputes (INVOICE and PAYMENT targets over 5 customers) at T read `dispute_versions` twice (page and count), and `customer_versions`, `invoice_versions`, `payment_versions`, `payment_allocation_versions` and `product_versions` as often for 15 rows as for 3, at most 3 each. Each `findAll` builds its own `Rehydration`, so a kind can be read once per lookup kind (customers directly, and again under invoices and payments). That is a fixed count per page, not one per row. Mutation-checked: putting back the per-row mapping in `list` fails it (customer_versions 6 against 2).
- **Recorded, not fixed here (medium): the product email tab over HTTP still refuses a past date.** This is the deviation already logged above. The guard comes off with BE-10's `occurredAt <= p` filter. It is added to CARRY-FORWARD.md as a BE-10 hand-off: `AsOfTabsTest` must assert that `GET /api/emails?entityType=PRODUCT&entityId=…&asOf=D` returns 200 with only emails up to D.
- **Fixed in part (low): `EmailTargets.disputeOwnFields` read live invoices and payments.** It now uses `records.find(Invoice/Payment)`. **Correction to the BE-09b section above:** the CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE cases of that test are checked through `requireVisible` only. `load` is not answered at T for them, and that part of the finding is rejected. `load` also builds the recipients: the live POC book (`PocService.activeHoldersByType` over `CustomerPoc`, a versioned type) and the customer's address. Its only callers are `/api/emails/context`, which §5.7.17 classes IGNORED (live), and the send paths, which are live-only. The as-of email tab (list/get) goes through `requireVisible`, which answers at T for every type. Rebuilding recipients at T would serve no endpoint.
- **Fixed (low): the credit test could not tell the balance at T from today's.** `AsOfDetailTest.aCustomersCreditIsAnsweredAsItWas` now has Beta overpay invoice B by 200 on D2, and that payment is voided on D3. It asserts `creditBalance` 0 on D1, 200 on D2, 0 on D3, and 0 live.
- **Fixed (low): `AllocationHistory.rehydrate` ignored the rows it was given.** The precondition is documented and enforced. Each row given must match (via `sameState`) the allocation rebuilt with its payment at the point; otherwise it throws `IllegalStateException`. New `RoundTripTest.anAllocationRowThatIsNotTheOneAtThePointIsRefused` checks that the snapshot rows rebuild and that a row with a changed amount is refused.

**Tests:** backend 408 run, 0 failures, 0 errors, 0 skipped (406 + `AsOfQueryCountTest` +1, now 3; `RoundTripTest` +1, now 3). mail-service 24 run, 0 failures, 0 errors.

## BE-10 — As-of reads III: remaining paths, dashboard, coverage

**Built**
- **Dashboard (§5.12 as-of).** `DashboardService`: every `Invoice`, `Payment` and `PaymentAllocation` root is `Temporal.fromValid`. The `customer`, `payment` and `invoice` joins are `Temporal.join(…, INNER)`, and the generics are widened to `Root<?>`/`From<?, ?>`. `idsInScope` is `Temporal.subquery`, and it still passes the outer `cq` to the scope predicates. As a result:
  - D1 leaves out invoices created after T, even backdated ones;
  - a payment voided after T is ACTIVE at T with its allocations, in both the payment-sum branch and the allocation branch;
  - D3 and D5 name customers as they were at T;
  - the book and region at T decide what counts.
  
  `DashboardController.today()` is now `Today.date()`, and the BE-09a `refuseAPastDate()` guard is gone. `TemporalConventionTest.PENDING` is now empty.
- **POC tab.** `PocService.listFor`, when a date is set, returns `historyQueries.seatsAt([customerId], p)`. The `AS_OF_UNSUPPORTED` guard in `CustomerController GET /{id}/pocs` is removed.
- **Documents tab.** `DocumentService.onRecord`, when a date is set, uses `uploadedAt <= p AND (deleted = false OR deletedAt > p)` instead of `deleted = false`. `count` at T goes through `queryExecutor.count` with the same predicates. Live count still uses the repository methods, so live SQL is unchanged. `toDto` flags `canEdit`/`canDelete` were already false at T. `canDownload` is now `!d.isDeleted()`. Both `DocumentController` guards are removed.
- **Emails tab.** `EmailService.list` adds `occurredAt <= p`. `EmailService.get` returns 404 ("Email not found") for an email with `occurredAt > p`. Delivery status still shows the current state. `EmailViews.canRetry` now also requires `!AsOf.active()`. Both `EmailController` guards are removed. This closes the BE-09b carry-forward: the product email tab over HTTP now answers at T.
- **Audit tab.** `AuditTimelineService.timeline` works like this when a date is set:
  - it builds the trail inside `LiveReadGuard.allow`, using today's relationships;
  - it keeps only entries, recorded or derived, with `createdAt <= p`.
  
  `AuditController.readOrGone` never falls back when a date is set. A record with no version at T gives 404, which covers both "not yet created" and "already deleted". The `AuditController` guard is removed.
- **"Every DTO flag ANDs `!historical`" sweep.** Customer, invoice, payment, promise and dispute flags already go through `RecordAccess`, which ANDs `!AsOf.active()`. Document flags were already false. The remaining flag was `EmailDto.canRetry`, and it is now fixed as described above. `AsOfParityTest` checks every `can*` flag in every historical answer.
- **Tests (17 new; `AsOfContextTest` and `TemporalConventionTest` changed; both are earlier-slice files):**
  - `AsOfDashboardTest` (7). Uses `AsOfScenario`, plus invoice Z (70) created on D3 and backdated to D1.
    - Billed at D1–D4 and live: Z is absent at D2 and present at D3, and Y is gone after it is cancelled.
    - Ageing against D, including `dueDateFrom`/`To` at D4 versus live, and a customer login's OWN figure.
    - Top outstanding and top paying use names at T (Acme Ltd versus Zeta Holdings).
    - Collected at D1–D4 and live, for the admin (payment sums) and for Colin (book, allocations).
    - The book at T: the payment is reassigned from Colin to Cody today, and Colin still counts it at D3.
    - `regionIds=N` at D2 versus D4 (the region at T), and SOUTH collected at D2 versus D3.
    - D6–D8: the invoice and promise summaries, and the upcoming-promises query.
  - `AsOfScopeTest` (3).
    - A SALES_POC whose invoice B moved to Sid on D3 still sees B at D2 in the list, the detail, the summary, the customer book (Beta) and billed, but not live. Sid sees the reverse.
    - Customer logins see only their own records at T in lists, details, payments and the OWN dashboard, and ignore `regionIds`. Another customer's customer still gives 403 (X1).
    - A NORTH READ/ALL user sees Acme at D2 but not at D4 or live, across the customers and invoices lists, the POC tab, the audit tab and top outstanding. The same holds for admin with `regionIds=N`.
  - `AsOfTabsTest` (4).
    - POC tab: a seat removed on D3 is listed at D2 but not at D3 or live. A seat added on D3 is absent at D2. A customer created later gives 404.
    - Documents: uploaded D1, uploaded D3, first one deleted D4. Checks the list and count at D0 (404), D2, D3 and D4, with flags off and `canDownload` false for the file deleted since. The customer login sees shared files only. A customer created later gives 404.
    - Emails: the customer tab and the product tab (CF-23) at D2 and D3. `GET /{id}` of a later email gives 404 at T. `canRetry` is false at T and true live.
    - Audit:
      - D2 has no D3 or D4 actions (plain and with `includeRelated`), and every `createdAt` is at or before the end of D2.
      - Invoice Y at D3 shows no cancellation.
      - A customer created later gives 404 at T.
      - A customer deleted on D3 has its trail at D2, gives 404 at D4, and is still readable live through the deleted-record fallback.
  - `AsOfEndpointCoverageTest` (2).
    - The AWARE handlers found from the handler mapping (26 GET + 6 export POST) must exactly match the probe table, so an unclassified new AWARE endpoint fails.
    - For each endpoint at D2 as admin:
      - it returns 200 with the header set to the date;
      - the answer differs from live once `asOf`, `version` and flags are stripped;
      - VERSIONED endpoints must read `_versions`;
      - INTERVAL endpoints (audit, documents list and count, emails list and get) must leave out a row that exists today.
    - Extra check: every live answer reads `_versions` 0 times.
  - `AsOfParityTest` (1).
    - Live answers are captured at TODAY. The clock then moves to TODAY+1 10:00 and every answer is requested with `asOf=TODAY`.
    - Answers must be identical apart from `asOf`, `version` and flags, and every `can*`/`editTakesOwnership`/`blockedReason` flag in the historical answer must be false or null.
    - The request matrix is generated from the six `TableSchema`s: every filterable column with one valid operator for its type, every sortable column, all four `/summary` endpoints, D1–D5, the D8 query, every record's `/{id}`, and each customer's `/credits` and `/pocs`. That is well over 100 requests for admin. Sam (a book user) and acme.login also run the base lists, summaries and non-"top" dashboard figures.
    - Checked for sensitivity: if `version` is not stripped, it fails on 22 answers.
  - `AsOfContextTest`: the interim "refuses a past date" test becomes `theDashboardAndTheRecordTabsAnswerAPastDate`, which expects 200 and the header.
- **Mutation-checked** on the working tree, using `build/mut10/mut.py`, which restores each file byte for byte. Each of these 12 mutations is caught:
  - dashboard: `idsInScope` live, billed live root, top-outstanding live join, allocation live root;
  - documents: live predicates, `canDownload` true;
  - emails: list unfiltered, get unfiltered, `canRetry` without the guard;
  - audit: unfiltered, deleted-record fallback kept at T;
  - POC tab: live.

**Deviations from DESIGN.md (with reasons)**
- **Documents at T use `deleted = false OR deletedAt > p`, not `deletedAt IS NULL OR deletedAt > p`.** The two are the same for every row the application writes, because `delete` sets both fields. A deleted row with no `deletedAt` (for example, one written before that column existed) now stays hidden instead of reappearing at every past date.
- **`DocumentDto.canDownload` is now `!deleted`, where it was always `true`.** Downloading is live (`@AsOfIgnored`). A file listed at T but deleted since would give 404 if downloaded, so the flag says so. Live lists never contain deleted files, so live answers are unchanged. The DTO shape is unchanged.
- **The document count at T goes through the executor.** It uses the same predicates as the list, because the repository's derived counts cannot take a point. The live count is unchanged.
- **The audit timeline runs all of its assembly inside `LiveReadGuard.allow` when a date is set.** That covers related-record discovery and the derived entries, both of which read today's relationships, not only the three lookups §5.7.17 lists. When a date is set, an entry with no `createdAt` is dropped, because it cannot be shown to be at or before the point.
- **`EmailService.get` at T checks the `occurredAt` cut before the party check.** Even a participant cannot open, as of a date, an email written after that date.
- **`AsOfEndpointCoverageTest` checks `GET /api/emails/{id}` with a witness.** An email's DTO carries nothing historical (delivery shows today's state), so "differs from live" is proven by a second request: an email written after the day is 404 then and 200 now. All other endpoints compare bodies directly.
- **`AsOfParityTest` excludes the "top-" dashboard figures and products for the sam and acme.login runs.** They are staff-only, or products are not a customer list. Filters use fixed values per type (for example `contains:a`, `neq:<first enum>`, `relative:last30Days`, `isNotEmpty:`), not values taken from the data.

**API-contract changes versus §6:** none. The dashboard, POC tab, documents, emails and audit now answer historical requests, as §6.0, §6.9 and §6.15 specify. `canDownload` becoming false for a file deleted since the viewed day is a value change only.

**Known gaps / deferred**
- Tasks, approvals, rules, runs and effects rows in `AsOfEndpointCoverageTest` belong to BE-12, BE-15, BE-16 and BE-17. The mapping check forces each of them to add its row.
- The audit `pendingStatus` decoration at T (BE-15) and `customerView` redaction (BE-15/BE-19) are unchanged here.
- By design (D8), these show their current state even as of a date: document description and visibility, email delivery status, and derived audit labels.
- The flaky `HistoryConcurrencyTest` link race (BE-09b note) was not touched. It did not reproduce in this slice's full runs.
- Slice size is about 1,200 changed lines (main 111+/68−, tests about 1,080).

**Tests:** backend 425 run, 0 failures, 0 errors, 0 skipped. That is 408 before plus 17 new: AsOfDashboardTest 7, AsOfScopeTest 3, AsOfTabsTest 4, AsOfEndpointCoverageTest 2, AsOfParityTest 1. AsOfContextTest is still 6 and TemporalConventionTest still 2. mail-service 24 run, 0 failures, 0 errors.

### BE-10 review fixes

**Fixed**
- **(medium) AsOfParityTest compared empty pages with empty pages.** Filter values now come from the live data. For each filterable column, the value is taken from the first live base-list row that carries a value for it: TEXT becomes `contains:<value>`, ENUM/BOOLEAN/NUMBER/MONEY/REFERENCE become `eq:<value>`, and DATE becomes `between:<day>,<day>`. Only a column the DTO does not carry falls back to a type filter: `isNotEmpty:` for TEXT and REFERENCE (was `contains:a`), with the others unchanged.
  - Added `aRowForEveryColumn()`. On TODAY it gives Gamma a phone, email, address and a Collection seat, gives Beta a Success seat, gives Gadget a description, and records for Gamma an invoice, a promise and a payment that keeps the promise, each with notes.
  - New assertion: every generated column filter keeps at least one row in the live admin answer. Before the fix, the probe found 25 of 135 empty; now 0.
  - Mutation-checked. Dropping rows at T in `pocSeatPredicate` (the seat subquery) and in `promiseLinkPredicate` (the promise→payment link subquery) now fails the test; both passed before. Logs: build/be10-rfmut1.log, be10-rfmut2.log.
- **(low) `EmailDto.canOpenRecord` was true as of a date.** It is now `!AsOf.active() && canSeeRecord`, per D14 and the slice rule that every DTO flag ANDs `!historical`. Its only frontend consumer is the live inbox, so no screen changes. `AsOfTabsTest` asserts it is false at D2 on the list and on `GET /{id}`, and true live.
- **(low) Audit of a PRODUCT as of a date before it existed returned 200 [].** `AuditController.ensureCallerCanSee` has a PRODUCT case: `readOrGone(id, records.find(Product.class, id) or 404, productRepository::existsById)`. As of a date it gives 404 when no version exists at T. Live behaviour is unchanged: an unknown or deleted product still falls back to 200 through `readOrGone`, as before. `AsOfTabsTest` checks Gadget at D2 (404), Gadget at D3 (200) and Widget at D2 (200). Mutation-checked: dropping the case fails the test.
- **(low) Derived audit entries at T carried today's state.** `AuditTimelineService.timeline` is split into `related(...)` (discovery through today's relationships) and `entries(Related)`. At T, discovery alone runs inside `LiveReadGuard.allow`. `asThen` then re-reads each record found (the customer, invoices, payments, promises, disputes and allocations) through `Records.findAll` at T.
  - Derived entries and labels are therefore built from the record as it was then. A record, or an allocation, with no version at T derives nothing. A dispute denied after T is OPEN then, so no DISPUTE_DENIED. Labels of records with no version at T stay as found. Only their recorded rows, cut by `createdAt`, remain.
  - New test `AsOfTabsTest.anEntryDerivedFromTheRecordShowsItAsItWasThen`. It deletes Acme's CUSTOMER_CREATED row to simulate pre-audit data. At D2 the derived entry is labelled "Acme Ltd" and its afterJson has "Acme Ltd", not "Zeta", including with `includeRelated`. Live, both are "Zeta Holdings". Mutation-checked: building from the live records fails it.
  - This replaces the BE-10 deviation "the audit timeline runs all of its assembly inside `LiveReadGuard.allow`". The "derived audit labels are current" gap is closed for records that have a version at T.

**Rejected (with reason)**
- **`DocumentDto.canDownload` stays `!deleted` as of a date.** It is not ANDed with `!historical`. D14 lists the Document flags explicitly as `canEdit` and `canDelete`. §5 `DocumentService.toDto` (DESIGN line ~1448) derives only `manage`/`canDelete` with `!historical`. §5.2.3 marks `GET /{id}/download` as `@AsOfIgnored` (served live, a read). The frontend rule (§7) gates only *mutating* controls on `!historical`.
  - The BE-06 review fix made `RegionAccessMatrixTest.documentsAreChangedOnlyWhereTheRecordsRegionIsManaged` assert `canDownload` true as of a date ("the file may be opened, never changed (D14)"). I tried ANDing it, and that test failed.
  - Recorded as an explicit exception to the §8.4 "every can* flag off" rule: a read affordance for a file still stored. `AsOfTabsTest` keeps asserting true at D3 and false at D2 for the file deleted since.

**Tests:** backend 426 run, 0 failures, 0 errors, 0 skipped. That is 425 plus `AsOfTabsTest.anEntryDerivedFromTheRecordShowsItAsItWasThen`; AsOfTabsTest is now 5. mail-service 24 run, 0 failures, 0 errors. Logs: build/be10-rf-full2.log, build/be10-rf-mail.log.

## BE-11 — Outbox publishing (A1)

**Built**
- **`B/automation/rule/TriggerEntity`**: `{CUSTOMER, INVOICE, PAYMENT}`, stored by name.
- **`B/automation/event/AutomationEvent`**: the `automation_events` table, every column in §3.6. All of them are plain strings or numbers: no FK, no unique constraint, no `@Enumerated` (so no H2 ENUM type). `record_ids` and `region_ids` are `@Lob TEXT` and are never fitted. `changes` is v300, `claimed_by` v80 and `last_error` v1000. Each of those three lengths is a public constant (`CHANGES_LENGTH`, `CLAIMED_BY_LENGTH`, `LAST_ERROR_LENGTH`), and the entity and `Fit.to` both use it. Kind and status vocabularies are string constants (`CREATED … REPLAY`, `PENDING/PROCESSING/DONE/DEAD`). There are four indexes: `idx_automation_event_due/entity/rule/occurred`.
- **`AutomationEventRepository`**: `JpaRepository` plus `findByHistoryTxIdOrderByIdAsc`.
- **`AutomationEvents`** (`@Component`). It injects only the repository, `Clock`, `CurrentUser`, `HistoryTransactions` and the shared `EntityManager`.
  - Methods: `created`, `updated`, `invoiceMoney` (INVOICE + CUSTOMER `OUTSTANDING`) and `children`. Each is `@Transactional(MANDATORY)`, so a call outside a transaction throws `IllegalTransactionStateException`.
  - A null or ≤ 0 id is ignored (S7).
  - It finds or registers the transaction's `PendingEvents` among `TransactionSynchronizationManager.getSynchronizations()`.
  - `write(pending)` runs in `beforeCommit`. It calls `historyTransactions.txId(em)` and then `saveAll` of the rows. Each row gets `occurred_at` = clock instant, `history_tx_id`, `status PENDING` and `attempts/claims 0`. Its `origin`, `caused_by_rule_id`, `depth` and `actor_user_id` come from the draft's snapshot, and `changes` is comma-joined and fitted to 300.
- **`PendingEvents`** (package-private `TransactionSynchronization`).
  - The buffer is a `LinkedHashMap<Key(children, type, id), Draft(kind, LinkedHashSet changes, Provenance)>`.
  - CREATED absorbs UPDATED in either order, and the labels are unioned. CHILDREN events are keyed apart, one per customer.
  - `Provenance(origin, ruleId, depth, actorUserId)` is taken from `WriteOrigins.current/actor` at the first call for the key. `ruleId` is set only for AUTOMATION.
  - `beforeCommit(readOnly)`: WARN `automation.events.dropped` and drop the buffer when read-only, otherwise write.
  - `afterCompletion` clears the buffer.
- **`B/automation/run/AutomationMoveParticipant`** (`RegionMoveParticipant`, M1): `updated(CUSTOMER, cid, "REGION")` plus one `children(cid, "REGION")`. It returns 0 because it moves no rows of its own.
- **History.**
  - `HistoryTransactions.txId(em)` creates and registers the transaction's work if needed, sets `needsInstant` and returns the `txId`. It never binds the instant.
  - `HistoryWork.needsInstant` flag.
  - `HistoryWriter.write` now returns early only when nothing was touched **and** no instant is needed. When `needsInstant` is set, it persists `HistoryTransactionRow(txId, t)` right after binding `t`.
  - New `B/history/versions/HistoryTransactionRow` (`history_transactions`: `id` identity, `tx_id` v36 NOT NULL, `instant` NOT NULL, `idx_history_tx (tx_id)`, no FK and no unique constraint) and `HistoryTransactionRowRepository` (`findFirstByTxId`).
  - The listener already ignores the row, because it is not a versioned type.
- **Call sites (§5.8.2):**
  - C1/C2 `CustomerService.create/update`. The update also publishes `children(id, "CUSTOMER_NAME")` when the name changed (PC-5).
  - C3 `PocService.add/remove/setPrimary` (`POC_SEATS`).
  - C4/C5 `PaymentService.applyTo/reverseAllocations`: `invoiceMoney(…, "PAYMENT")` per invoice saved, and `CREDIT` when the customer's credit moved. The payment itself is never published there.
  - C6/C7 `CreditLedger.applyTo` (after the early return) and `refund` (after the `amount <= 0` return): customer `CREDIT`, `invoiceMoney(…, "PAYMENT")`, and payment `ALLOCATION` for each payment drawn or credited.
  - I1 `InvoiceService.create`: INVOICE CREATED plus customer `OUTSTANDING`.
  - I2 `update` (also reached from reassign and bulk): `DETAILS`.
  - I3/I4 `cancel` and `cancelWithRefund` (`STATUS`), and I5 `replaceItems` (`ITEMS`), all via `invoiceMoney`.
  - I6/P5 `DisputeService` `update_notes` and `update_meta` (`DETAILS`).
  - P1 `PaymentService.record` (CREATED, after the save), P2 `update` (`DETAILS`), P3 `voidPayment` (`STATUS`) and P4 `updateAmount` (`AMOUNT`).
- **Test harness:**
  - The `IntegrationTestBase` reset calls `automationEventRepository.deleteAll()` right after `clearVersions` (§3.12 order).
  - `HistoryTestSupport.clearVersions` also empties `HistoryTransactionRow`.
- **Tests (32 new):**
  - `T/automation/event/OutboxPublisherTest` (15):
    - `theSaveReadsNoRules`: `CountingStatements.reads("automation_", POST /api/invoices) == 0`, and INVOICE CREATED + CUSTOMER UPDATED are written.
    - A rollback by exception or by `setRollbackOnly` leaves no event and no `history_transactions` row.
    - A `recomputeAll(false)` dry run leaves no event.
    - A read-only transaction drops its events.
    - Publishing outside a transaction throws `IllegalTransactionStateException`.
    - An invoice paid from credit gives exactly INVOICE CREATED (`PAYMENT`), CUSTOMER UPDATED `CREDIT,OUTSTANDING` and PAYMENT UPDATED `ALLOCATION`, all in one tx with an instant. Every row has PENDING, USER, actor, depth 0 and attempts/claims 0.
    - A payment over two invoices publishes each invoice once, the customer `OUTSTANDING,CREDIT` and the payment CREATED.
    - Bulk cancel (a, paid, failing, b), where `failing` publishes and then fails at commit through an injected strict-history fault: only a and b publish, each in its own tx, and the failing row's events roll back with it.
    - Per-transaction dedupe: CREATED absorbs UPDATED in either order, labels are unioned, CHILDREN is kept apart, and null/0 ids are ignored.
    - `changes` is fitted to 300 with an ellipsis.
    - A seats-only save points at the instant of its seat version. A save that changed nothing versioned still gets a `history_transactions` row.
    - AUTOMATION origin with `caused_by_rule_id` and depth, and the version's origin and txId match.
    - Provenance is snapshotted per record at first publish: a principal/origin swap that ended before commit still stamps AUTOMATION/rule/sam, while a record published after it gets USER/admin.
    - APPROVAL origin with `actor_user_id` = maker.
    - SYSTEM with no one signed in.
  - `T/automation/event/OutboxSchemaTest` (6). Checked through `information_schema` for `automation_events` and `history_transactions`:
    - only the `ID` primary key; no unique index; no FK into them;
    - no ENUM columns;
    - every fitted column is at least its fit constant, and every unfitted vocabulary (kinds, statuses, `TriggerEntity`, `WriteOrigin.Kind`) fits its column;
    - UUID columns are exactly 36;
    - `record_ids` and `region_ids` have the same physical type as `audit_logs.after_json` (TEXT) and are longer than 1,000,000;
    - the NOT NULL column set is exact;
    - the consumer indexes exist.
  - `T/automation/event/TriggerCoverageTest` (11). The §5.8.4 version oracle runs after each operation. Every version opened must have its event in the same tx:
    - customer/seat versions → the customer;
    - invoice/payment versions → the record or a CHILDREN event for its customer;
    - allocation versions → both the invoice and the payment;
    - an invoice version whose money or status changed → customer `OUTSTANDING`;
    - a rename → CHILDREN;
    - and every event has a recorded instant.

    Operations covered:
    - customer create, update and rename (exactly UPDATED + one CHILDREN);
    - seat add ×2, set-primary and remove-with-promotion;
    - invoice create with credit, update and cancel;
    - `cancelWithRefund`, `replaceItems` and `update_notes` through dispute approval;
    - payment record with excess, update and void;
    - `updateAmount` and `update_meta` through dispute approval;
    - a region move of a customer with 20 invoices and 5 payments: 20 + 5 versions, and **exactly two** `automation_events` rows (PC-4).
  - `CustomerRegionMoveTest.whatEachParticipantMovedIsCountedUnderItsName` (a BE-08 file): `moved.other` now has two entries, with `AutomationMoveParticipant` = 0.
- **Mutation-checked:**
  - Dropping I6 (`update_notes`) and the `ALLOCATION` publishes in `CreditLedger` fails 4 `TriggerCoverageTest` cases.
  - Making the writer ignore `needsInstant` fails 2 `OutboxPublisherTest` cases.

**Deviations from DESIGN.md (with reasons)**
- **`PendingEvents.Provenance` has no `pendingChangeId`.** §2 D4 lists it in the snapshot, but `automation_events` (§3.6) has no column to write it to. The APPROVAL origin and the maker (`actor_user_id`) are snapshotted and stored as §5.8.1 requires. BE-13 can add the column and the field together if the consumer ever needs the id.
- **`AutomationEventRepository` has no claim, finish, due, expired-lease or purge queries yet.** §5.8.1 lists them on the class, but they belong to the consumer (§5.10.6) and are exercised only by BE-17/BE-18. They are left to those slices, which will add them to this repository, rather than shipped untested here.
- **The "RunAs that ended before commit" and "approved change" provenance cases are simulated.** `RunAs` arrives in BE-12 and approvals in BE-13. The tests swap the SecurityContext and bind a `WriteOrigin` inside the transaction (RunAs's exact effect), and bind `WriteOrigin.approval(pc, checker).withActor(maker)` around it (what §5.11.6 does). BE-13 should add the end-to-end APPROVAL case to `OutboxPublisherTest` (§8.5 marks it BE-13+).
- **`CreditLedger` publishes the drawn or credited payments inside its loops.** The customer `CREDIT` is published before `invoiceMoney`, so a credit-paid invoice's customer event reads `CREDIT,OUTSTANDING` as §8.5 states.
- **`AutomationMoveParticipant.afterCustomerMoved` returns 0**, so `moved.other` gains `"AutomationMoveParticipant": 0`. The frontend move dialog hides zero counts. The §6.4 example simply omits it.

**API-contract changes versus §6:** none. The only visible change is the extra `moved.other.AutomationMoveParticipant: 0` entry, which is additive and hidden by the UI.

**Known gaps / deferred**
- Nothing consumes the outbox yet (BE-17). `history_transactions` and `automation_events` are never purged until BE-18 adds retention (`event-retention-days`).
- Held pending changes (R8) and the APPROVAL end-to-end publish are BE-13+.

**Tests:** backend 458 run, 0 failures, 0 errors, 0 skipped (426 before + 32 new: OutboxPublisherTest 15, OutboxSchemaTest 6, TriggerCoverageTest 11). mail-service 24 run, 0 failures, 0 errors. Log: build/be11-full.log.

### BE-11 review fixes
- **Fixed [medium] event point could precede the versions its own transaction wrote.** `HistoryWriter.write` persisted `HistoryTransactionRow(txId, t)` with the raw bound instant, while the versions it then opened start at `after(t, open) = max(t, open.validFrom + 1µs)`. With a clock behind (skew, clock going back, equal microseconds, §5.7.5) the point was before the transaction's own version, so D10 evaluation at it would see an older state. `writeType`/`writeLinks` now return the latest instant they assigned (every `validFrom` opened, every `validTo` closed, every link `at`), and the row is persisted after steps 3–4 with `max(t, those)`. Deviation from §5.7.4's pseudo-code (step 1 persisted it with `t`): the row is written at the end of the writer, before `s.flush()`, which is what D10 and §5.7.5 require. New test `OutboxPublisherTest.theEventPointSeesTheVersionsItsTransactionWroteWhenTheClockIsBehind` (clock set 5 ms back for the second save: the point equals the new version's `validFrom` and the one version valid there is "Acme B"). Mutation check: recording `t` again fails exactly that test (build/be11-rfmut.log); code restored.
- **Fixed [low] consumer queries missing from `AutomationEventRepository` with no later slice naming the file.** Not added here (no user before BE-17; adding unused queries would be later-slice work). Recorded as an explicit hand-off in CARRY-FORWARD.md ("From BE-11 review"): BE-17 adds claim/finish/findDueIds/findExpiredLeaseIds, BE-18 adds purge of `automation_events` with `history_transactions`.
- Rejected: none.
- Final: backend `mvn -q -o test` 459 run, 0 failures, 0 errors, 0 skipped (458 + 1 new); mail-service 24 run, 0 failures, 0 errors. Logs: build/be11-rf-full.log, build/be11-rf-mail.log.

## BE-12 — Assignees and tasks (A6)

**Built**
- **`B/auth/RunAs`** (D6, §5.5): `call(principal, ViewScope, WriteOrigin, work)` installs a fresh SecurityContext, the scope and the origin (actor = the principal unless the origin names one), and restores all three in `finally`; `asUser(userId, …)` throws `RunAs.InactiveUserException` for a missing, inactive or customer-login user.
- **`B/assignee`**:
  - `RecordAssignee` (`record_assignees`, every §3.7 column, indexes `idx_record_assignee_owner`/`_user`, no FK; rows never deleted) and `RecordAssigneeRepository` (current rows, a page's current rows, rows current at an instant — `addedAt <= p and (removedAt is null or removedAt > p)` — and `countByKindAndUserIdAndRemovedAtIsNull`).
  - `AssigneeStore` (repositories, `Clock`, `CurrentUser` only; CF-3): `validate(context, tokens, offeredRoles)` (USER → `EmailAddressing.requireActiveInternalUser`, ROLE → `EmailAddressing.requireOfferedRole` with the default level, anything else "Assignees are people or roles", duplicates collapsed, ≤ 10; every failure is 400 `fieldErrors.assignees`), `replace(ownerType, ownerId, tokens)` (closes rows no longer wanted, adds new ones, returns the added tokens), `list`/`listAll` (current, or at the bound point), static `key`/`token`/`roleOf`.
  - `AssigneeService`: `probe` (drops USER tokens that fail the probe, with "X cannot see this record, so was not assigned"), `requireVisible` (the UI's 400 "X cannot see this record"), `resolve` (USER → active user; ROLE → `EmailTargets.Target.holders(ref)` kept only when active and holding the owner's VIEW privilege in the region, PC-2), `describe` (detail), `describeBriefly` (list rows), `describeAt` (as of a date: seats at T for customer-level roles, the record's POC at T for record-level ones), `notifyAssigned` (≤ 20 people, each once, VIEW privilege in the region, an `exceptUserId`), `people(q, entityType, entityId)` (§6.13/SC-18: `findStaffInRegions` over the record's region — scoped load, 404 — or `ViewScope.narrow(grantedRegions)`; ≤ 20; Gmail status).
  - `AssignedTo` (§5.9.3): `predicate(ownerType, root, q, cb, userIds)` — an EXISTS over current (or, as of a date, then-current) assignee rows: USER named, customer-level role via a `Temporal.subquery` over seats, record-level role via `Temporal.subquery` over invoices/payments (TASK, DISPUTE) or the promise's Collection POC; `filter(...)` for the list column (EQ, IN, IS_EMPTY, IS_NOT_EMPTY).
  - `VisibilityProbe` (§5.9.1, CF-2): `canSee(userId, type, id, regionId)` — `principalFor(userId)` (inactive / customer login / missing → false), then under `RunAs.call(p, LIVE_ALL, system)`: the type's VIEW privilege, then the tier (FULL true, NONE false, BOOK one `inScope` count). Never throws, never calls a throwing `@Transactional` method.
  - `AssigneeDtos` (`AssigneeDto{token, label, people[{userId, name, primary}], unresolved}`), `AssigneeController` (`@AsOfIgnored`): `GET /api/assignees/context` (the same `ContextDto` as the email context, via `EmailService.context`; staff only), `GET /api/assignees/people`.
- **`B/task`**:
  - `Task` (`tasks`, every §3.7 column and index; `region` `@ManyToOne(LAZY, optional = false) @JoinColumn(updatable = false)`; `@Version`; `created_at`/`updated_at` from `AppClock`), `TaskStatus` (`isOpenWork`), `TaskEntityType` (email type, VIEW privilege, `kind()` as a switch — a field would read `ScopeResolver.Kind` while it is being built), `TaskRepository` (`findByIdForUpdate`, `customerIdOf`, ids/rows by customer, rows by record), `TaskSchemaUpgrade` (widens `status` and `entity_type`).
  - `TaskTargets.load(type, id)`: the record through the scoped `get` (404), label ("Invoice INV-…", fitted to 200), customer, region.
  - `TaskService`: `create` (read-only work first: TASK_MANAGE somewhere, the record's VIEW privilege (403), the record (404), assignees validated and probed (400); then the customer `findByIdForUpdate`, the region re-read under the lock, book-aware `requireVisible(CUSTOMER)`, `requireIn(TASK_MANAGE, region)`, save, `AssigneeStore.replace`, audit `TASK_CREATED`, notify), `createForAutomation(TaskDraft)` (`MANDATORY`; origin AUTOMATION with rule and run; unprobed assignees dropped with warnings), `patch` (title/description/due/`clearDueDate`/assignees; TASK_MANAGE in the task's region; new USER assignees probed; `TASK_UPDATED`, `TASK_ASSIGNEES_CHANGED`), `changeStatus` (the §5.9.2 transitions; TASK_MANAGE in the region, or an effective assignee — TASK_VIEW in the region and `AssignedTo(me)` — for moves that neither cancel nor reopen a cancelled task; `TASK_STATUS_CHANGED`; `TASK_DONE` to a USER-origin task's creator when someone else finishes it), `get` (`Records.find` + visibility as a query), `page`/`summary`/`mineCount`/`count`/`idsMatching`, `toDto` (detail: assignees with the people each means; as of a date from the interval) and `toDtos` (list: one query per kind of thing). Every write: customer lock first (unlocked `customerIdOf`, customer `FOR UPDATE`, task `FOR UPDATE`), 404 visibility, `requireLive`, `version` → 409.
  - `TaskController` (`@AsOfAware`; §6.12): `GET /api/tasks[?entityType&entityId|customerId]`, `/summary` (same context params), `/mine`, `/mine/summary`, `/mine/count`, `/count?entityType&entityId`, `/{id}`, `POST /api/tasks`, `PATCH /{id}`, `POST /{id}/status` (TASK_VIEW gate; the service decides), `POST /bulk` (START, MARK_DONE, REOPEN, CANCEL; MANAGE scope, others NOT_REACHABLE), `POST /export` (method `@AsOfAware`, EXPORT_DATA and TASK_VIEW).
  - `TaskCascade.onCustomerDeleted` (repositories, audit, clock only): through the ORM, every task of the customer (its invoices' and payments' too) gets `entityDeleted`, open ones are CANCELLED, each audited `TASK_CANCELLED_WITH_RECORD`. Called from `CustomerService.delete` next to the email cascade.
  - `TaskMoveParticipant`: native `update tasks set region_id = :to where customer_id = :cid`, then `HistoryTracker.touch(Task.class, ids, false)`; returns the rows moved.
- **History**: `TaskVersion` (`task_versions`, indexes entity/window/customer/region/record), `TaskHistory` (code TASK, rank 70; `updatedAt` stored, excluded from `sameState`), `HistoricalTask`, `EnumNames.TaskStatusName`/`TaskEntityTypeName`, registered in `HistoryModel`.
- **Rights and scope**: `Privileges.TASK_VIEW` (READ) and `TASK_MANAGE` (MANAGE), appended to `ALL`; seeded to CASHIER (both), VIEWER (VIEW), the three POC roles (both, via `createRoleIfAbsent`), ADMIN (all). `ScopeResolver`: `Book.PARENT`, `Kind.TASK`, `forTasks()`; TASK tiers are FULL wherever the gate holds, and the scope ANDs the parent predicate (§5.6.4 step 5): an OR over the kinds the caller may view of `entityType = X ∧ entityId IN (X's own scope, as a subquery of the outer query)`; customer logins get `disjunction()`.
- **Schema**: `TableSchemas.TASKS` (§5.9.4: `id`, `title`, `status`, `dueDate`, `overdue` (custom), `entityType`, `entityId`, `entityLabel`, `customerId`, `assigneeUserId` (REFERENCE `staffUser`, custom `AssignedTo` filter, not sortable), `origin`, `ruleId`, `regionId`, `regionName`, `createdAt`, `completedAt`, `updatedAt`; default sort `dueDate,asc`), registered; `TableSchemaController` `tasks → [TASK_VIEW]`.
- **Audit**: `AuditController` supports `TASK` (TASK_VIEW; read through `taskService.get`, a deleted task's history stays readable); `AuditTimelineService.timeline(type, id, includeRelated, customerView)` — a task's own trail is labelled with its title; with `includeRelated`, staff get the customer's tasks (by `customerId`) and an invoice's or payment's tasks; `customerView` (customer logins) never takes tasks in and drops every TASK row (SC-1).
- **Elsewhere**: `UserController.pocReferenceCount` counts current USER assignee rows (someone still assigned is deactivated, not deleted); `EmailAddressing.requireActiveInternalUser`/`requireOfferedRole` public static; `EmailTargets.offeredRoles(type)` static; `FieldLimits.TASK_TITLE` 200 / `TASK_DESCRIPTION` 2000; `IntegrationTestBase` reset deletes assignees then tasks right after `clearVersions` (§3.12).
- **Tests (new)**: `task/TaskApiTest` (16): make/read/change/count/tab/audit/related trail and notices; visibility (other region, out of book → 404 and nothing changed); TASK_MANAGE per region (READ region → 403, MANAGE region → 200); an assignee starts, finishes and reopens but may neither retitle nor cancel (403, DB unchanged), creator told on DONE; impossible/same moves → 400; 409 stale with nothing changed; token validation (each case → `fieldErrors.assignees`, 11 → "At most 10", duplicates collapse, default level); **CF-2** a person who cannot see the record → 400 and every table unchanged; **PC-2** a role holder without TASK_VIEW is not told, not among the people, and refused (HTTP and service); `customerDeleteCancelsAndMarks`; an assignee is deactivated, not deleted; bulk MARK_DONE (skipped "already done", other region NOT_REACHABLE, unknown action 400); CSV export; **region copied from the locked customer** (a JDBC session moves the customer and holds its row; the create waits, then takes the new region); the automation create as its owner through `RunAs` (AUTOMATION/rule/run, warning for the unprobed assignee, owner notified, audit origin, contexts restored, MANDATORY, `InactiveUserException`); **context starts (CF-3)** with `AssigneeStore`/`TaskCascade` injecting no service and no `EmailTargets`, plus the seeded privileges. `task/MyTasksTest` (4): a seat taken after creation brings the task, giving it up takes it away; a record-level Sales POC; an invisible parent is never mine (list, count, summary, 404); count/summary shape, `lockedFilters == []`. `task/TaskAsOfTest` (3): status from `task_versions` (reads them; 404 before creation; list/summary/count at T), assignees from their interval (`/mine`, `assignedToMe`, the `assigneeUserId` filter), a role assignee resolved against the seats of that day. `assignee/VisibilityProbeTest` (4): FULL, BOOK in/out, NONE, no VIEW privilege, inactive/customer login/missing/no staff scope → false; a "no" inside a write transaction that then commits, with the caller's principal, scope and origin intact.
- **Tests (extended)**: `PrivilegeClassificationTest` (TASK_VIEW READ, TASK_MANAGE MANAGE); `VersionShapeTest` (TASK in the code list, `RENAMED`/`RENAMED_COLUMNS`); `VersionTablesHaveNoConstraintsTest` (`TASK_VERSIONS`, its `STATUS`/`ENTITY_TYPE` as varchar, its indexes); `SnapshotTest`, `RoundTripTest`, `HistoryBaselineTest` (a task in each family); `ModifyingQueryGuardTest` (allow-lists `task/TaskMoveParticipant.java`; Task is versioned); `ViewScopeInventoryTest` (the 13 new endpoints classified; classification pins); `RegionParamCoverageTest` (8 rows: tasks list/summary/mine/mine summary/mine count/bulk/export, `/api/assignees/people`; each fixture has a task assigned to admin); `AsOfEndpointCoverageTest` (8 task probes, VERSIONED, on a Beta task retitled on D3 plus a later task); `AsOfReadOnlyTest` (`tasks`, `record_assignees`, `task_versions` in the unchanged-rows check); `CustomerRegionMoveTest` (participants now 3; **PC-16**: after N → S, `GET /api/tasks?regionIds=N&asOf=<before>` lists the task and `regionIds=S` does not, the reverse live, and the task's version changes region at the move instant); `RegionConsistencyTest` (tasks in both families and after the moves; the stray check finds a planted task); `RegionSchemaUpgradeTest` (the pre-regions database drops `tasks`, which references `regions`).

**Deviations from DESIGN.md (with reasons)**
- **Two task columns are stored under another name in `task_versions`.** A version row already has its own `origin` (provenance) and `entity_id` (the record id). So the task's `origin` is `TaskVersion.taskOrigin` / `task_origin`, and its `entity_id` is `TaskVersion.entityId` on column `record_id` (index `idx_task_versions_record (entity_type, record_id, valid_to)` instead of `(entity_type, entity_id, valid_to)`). The `origin` list column resolves to `taskOrigin` on a version root. `VersionShapeTest`/`SnapshotTest` compare those two columns through an explicit `RENAMED` map (without it, `origin` would silently pass against the provenance column).
- **`EnumNames` gained `TaskStatusName`/`TaskEntityTypeName`** (not in BE-12's Modify list; the BE-06 carry-forward asked for it). Both BE-06 carry-forward items (converters; `VersionTablesHaveNoConstraintsTest`/`VersionShapeTest` lists) are done.
- **The assignee gates omit AUTOMATION_MANAGE / AUTOMATION_VIEW**, which do not exist until BE-16: `/context` is TASK_MANAGE, DISPUTE_MANAGE or PROMISE_MANAGE; `/people` is TASK_VIEW, DISPUTE_MANAGE or PROMISE_MANAGE. **BE-16 must add AUTOMATION_MANAGE to `/context` and AUTOMATION_VIEW to `/people`** (`AssigneeController`).
- **`TaskMoveParticipant` runs its native update itself** (allow-listed file) rather than through a repository method: `ModifyingQueryGuardTest` forbids `@Modifying` in the repository of a versioned entity, and Task is versioned.
- **The task's region is re-read under the customer lock** (`regionIdOf` after `findByIdForUpdate`, then `getReferenceById`). The PC-4 order reads the record (and its customer) before the lock, and `findByIdForUpdate` returns that managed, possibly pre-move copy unrefreshed; reading the committed column after the lock keeps I1 under a concurrent move (tested).
- **A task whose record was deleted stays visible where the caller sees that kind of record in full.** The parent predicate adds `entityDeleted = true ∧ region ∈ full(X)`; without it, tasks cancelled with their customer would be invisible to everyone, including their TASK_MANAGE holders and the task page's "(deleted)" state.
- **The effective-assignee rule is evaluated for the caller as `TASK_VIEW in the task's region ∧ AssignedTo(me)`** (one query) rather than by resolving every holder through `EmailTargets`: for a caller who can already see the task this is the same set (active, region access, TASK_VIEW, named or holding the role on the record). `assignedToMe`/`canChangeStatus` use the same predicate.
- **List rows resolve people for named assignees only**; a role on a list row carries its label with `people: []` (and `unresolved: false`). Resolving holders per row would cost several queries per row; the detail (`GET /api/tasks/{id}` and every write's response) resolves them fully.
- **As of a date, a role assignee's `people` are the seats of that day (customer level) or the record's POC that day (record level), active today;** today's grants are not re-applied to them (the task itself is already filtered by the caller's rights at T).
- **`TaskDto.rule.name` is null** until the rules table exists (BE-16); `rule.id` is set for AUTOMATION tasks. `/api/tasks/count` answers `{count, open}` and `/mine/count` `{count}`, as §6.12.
- **A transition that the status cannot make is a 400 before rights are checked** (an assignee asking to cancel a DONE task gets "A done task cannot become cancelled"); a valid move they may not make is 403.
- `createForAutomation` falls back to the record's label for a blank title; `TASK_ASSIGNEES_CHANGED` stores `{"assignees":[keys]}` before/after; the customer-delete cascade audits every task it marks (a closed one with "…the task was already closed").

**API-contract changes versus §6:** none. Delivered as §6.12 (tasks), §6.13 (`/api/assignees/context`, `/api/assignees/people`), §6.15 (TASK audit, `TASK_*` actions), §6.16 (`tasks` schema, `staffUser` reference kind). `/summary` also accepts `entityType`/`entityId`/`customerId`, as the list does (the FE-06 customer badge uses `?customerId`). Bulk REOPEN / `status OPEN` accept IN_PROGRESS → OPEN (FE-06 review note).

**Known gaps / deferred**
- Dispute and promise assignees (`PUT …/assignees`, their `assigneeUserId` columns, `AssigneeStore` in those services) are BE-19; `AssignedTo` already has their branches.
- Notifications about a task go to people with TASK_VIEW in its region; a USER assignee without TASK_VIEW can be assigned (the probe checks the record's VIEW privilege, per §5.9.1) but is not told and cannot move it.
- Slice size ≈ 3,960 changed lines (≈ 2,050 main, ≈ 1,900 tests), above the 2,500 guide; everything is in BE-12's own list.

**Tests:** backend 495 run, 0 failures, 0 errors, 0 skipped (459 before + 36 new: TaskApiTest 16, MyTasksTest 4, TaskAsOfTest 3, VisibilityProbeTest 4, RegionParamCoverageTest +8 (now 44), CustomerRegionMoveTest +1 (now 11)). mail-service 24 run, 0 failures, 0 errors. Logs: build/be12-full.log, build/be12-mail.log.

### BE-12 review fixes

All six findings in `build/BE-12.review.md` were checked against the code, found valid and fixed. None was rejected.

- **1 [medium] Role holders who cannot open the record were notified and listed (PC-2).** Fixed in `AssigneeService.resolve`: a role holder is kept only when they are active, hold the owner's VIEW privilege in the region (`regionAccess.holds`) **and** `visibilityProbe.canSee(holder, target.type(), target.id(), target.regionId())` (the non-throwing book check). The detail's `people`, the notification and the "unresolved" flag all go through `resolve`, so all three follow it. The notification path was split into two steps to keep the §5.9.1 "Order of work":
  - the new read-only `AssigneeService.recipients(ownerType, tokens, target)` runs before the lock. It is `resolve` plus, for people named directly, the owner's VIEW privilege in the region; that check used to run per person inside the lock.
  - `notifyAssigned(ownerType, added, target, recipients, except, …)` only sends. A token added under the lock that was not worked out beforehand (only possible if someone else changed the assignees in between) is worked out there.
  - `TaskService` keeps a private `Notice(target, recipients)`, built in `create`, `createForAutomation` and `patch` before the lock. If the task's region changed between that read and the lock (a move committed in between), the notice is worked out again under the lock.
  - Test: `MyTasksTest.aTaskOnARecordICannotSeeIsNeverListedAsMine` now also asserts that colin gets exactly one TASK_ASSIGNED, the one for the customer task. The invoice task's detail shows him in no `people`, with `unresolved: true`, while the customer task lists him. His customer trail with `includeRelated` has only the customer task.
- **2 [medium] The audit trail's `includeRelated` exposed tasks to staff without TASK_VIEW.** Fixed:
  - `AuditTimelineService` now takes in related tasks through the new `TaskService.visibleAmong(ids)`. That returns nothing for a caller without TASK_VIEW (or a customer login), and otherwise keeps the ids that pass `ScopeResolver.scope(TASK, VIEW, GRANTED)`, the same predicate as `TaskService.requireVisible`, one query per 1,000 ids.
  - So besides the privilege, a customer seen through a seat no longer brings in the tasks on its invoices outside the caller's book.
  - As of a date, the query runs as of that point (Temporal), so it shows the tasks as they could be seen then.
  - Tests: in `TaskApiTest.aRoleHolderWithoutTaskViewIsNeitherToldNorAnAssignee`, nick (AUDIT_VIEW, no TASK_*) reads the invoice trail and gets no TASK rows, while cara gets them. The book case is covered in MyTasksTest above.
- **3 [low] `canChangeStatus` was true for an assignee on a CANCELLED task.** Fixed: the assignee branch is now `assignedToMe && !entityDeleted && status != CANCELLED`. `TaskApiTest.anAssigneeMayMoveATaskAlongButNeitherChangeNorCancelIt` asserts the flag is false after the admin cancels, before the 403.
- **4 [low] PATCH probed new USER assignees while holding the customer and task locks.** Fixed: `patch` now does its read-only work first.
  - It reads a `TaskRepository.Head` projection (`entityType`, `entityId`, `regionId`, `entityDeleted`) instead of loading the entity. A managed copy would come back unrefreshed from `findByIdForUpdate`, the same trap as the customer in BE-12.
  - Then, still before the lock: `requireVisible`, `requireLive`, TASK_MANAGE in the region, not deleted, validate the tokens, probe the ones that are new, and work out whom to tell.
  - Then `lockForWrite`, the version check, and the rights and deleted checks again.
  - Under the lock, only tokens that became new there are probed. That happens when the assignees changed in between, or when the region changed, in which case every new token is probed again.
  - This is covered by the existing PATCH tests (the 400 against `fieldErrors.assignees` with nothing changed, notifications, 409). There is no separate test of the lock window; the BE-17 statement-count test will pin that for automation.
- **5 [low] Who is left out of TASK_ASSIGNED depended on the task's origin.** Fixed: `except` is null only when `WriteOrigins.current(currentUser).kind() == AUTOMATION`; otherwise it is the actor. `TaskApiTest.aRulesTaskIsMadeAsItsOwnerAndLeavesOutWhoeverCannotSeeTheRecord` now has admin, by hand, remove and then re-add themselves (plus cara) on the rule's task. Admin is still told only once (by the rule), and cara is told.
- **6 [low] A task cancelled with its customer could be reopened and edited.** Fixed:
  - `changeStatus` and `patch` refuse an `entityDeleted` task with 400 "The record this task was on has been deleted". The check runs after the version check and, in `patch`, after the rights check, both before and under the lock.
  - `canEdit` and `canChangeStatus` are false for such a task.
  - `TaskApiTest.deletingTheCustomerCancelsItsOpenTasksAndMarksThemAll` asserts both flags, the 400 on reopen (with the message), the 400 on a PATCH of the cancelled task and of the DONE one (assignees), and that nothing changed.

**Deviations added:** `TaskRepository.Head` (an interface projection) and `TaskService.visibleAmong` are new public members. `AuditTimelineService` now injects `TaskService`; the context still starts, since nothing `TaskService` reaches injects the timeline. The one remaining `AssigneeService.notifyAssigned` takes the pre-computed `recipients` map; BE-17 and BE-19 callers should build it with `recipients(...)` before their lock, or pass `Map.of()` to have it worked out under the lock.

**Tests:** backend 495 run, 0 failures, 0 errors, 0 skipped. The count is unchanged because the review assertions were added to existing tests: TaskApiTest ×4, MyTasksTest ×1. mail-service 24 run, 0 failures, 0 errors. Logs: build/be12-rf-full.log, build/be12-rf-mail.log.

## BE-13 — Maker-checker: foundation, gate, decisions (INVOICE_CANCEL end to end)

**Built**
- **`B/approval`** (new package):
  - `ApprovalOperation` (the 8 amount-gated operations; `entityType`, `viewPrivilege`, `label`, `measureLabel`, `isCreate`, `amountGated`; THRESHOLD_CHANGE is BE-15), `PendingStatus` (`isFinal`), `ChangeOrigin {USER, AUTOMATION}`, `ApprovalConverters` (`OperationName`/`StatusName`/`OriginName`, plain varchar, D12).
  - `PendingChange` (`pending_changes`, every §3.8 column and index, `uk_pending_outstanding`, `region` `@ManyToOne(LAZY, optional=false) @JoinColumn(updatable=false)`, `@Version`; `pending(...)` factory fits every string (SC-20); `approve/reject/withdraw/fail/supersede` set `decided_*` and free the outstanding key; `outstandingKeyAtRequest()`, `getRegionId()`), `PendingChangeRepository` (`findByOutstandingKey`, `findByIdForUpdate`, `findMakerId`, `findFirstBySupersededById`, `countByMakerUserIdOrDecidedByUserId`).
  - `ApprovalThreshold` (`approval_thresholds`, `uk_threshold_region_op`, `window_hours` default 24) and `ApprovalThresholdRepository.findByRegionIdAndOperation`.
  - `Outcome` (sealed; `Applied`/`Held`, `appliedOrThrow`, `map`, `heldChange`), `Gated` (`outstandingKey`, `auditEntityType/Id`), `ApprovalContext` (ThreadLocal `Applying`), `PayloadCodec` (strips every `*password*` key recursively, ignores unknown properties, `validate` → `PayloadException`), `ChangeApplier` SPI, `ApprovalConflictException` (extends `BadRequestException`), `ChangeConflictException`, `PayloadException`.
  - `MakerChecker.gate` (MANDATORY; §5.11.3 minus BE-15's cool-down and cumulative window): apply mode only verifies the amount did not grow; ≤ 0 or no threshold or within it → apply; else supersede the same maker's same-operation change (flushed to free the key; `CHANGE_SUPERSEDED`), 409 for anyone else's, save the pending row (origin AUTOMATION ⇒ `rule_id`/`automation_event_id`), audit `CHANGE_REQUESTED` (actor maker, `pending_change_id`), notify.
  - `ApprovalNotifier`: `requested` → `notifyHolders(CHANGE_APPROVE, region, maker, APPROVAL_REQUESTED, "Approval needed: <label>", summary, /approvals/{id})`; `decided` → maker gets APPROVED (link to the record) / REJECTED / FAILED / CLOSED (link `/approvals/{id}`); nothing for SUPERSEDED/WITHDRAWN.
  - `PendingChangeScope` (`requireVisible` only, evaluated in memory for one row): maker while still holding a grant in the region; `CHANGE_APPROVE` in the region; the op's VIEW privilege in the region plus record visibility through the non-transactional `ScopeResolver.requireVisible` (creates: named POC or the customer); a customer login only for its own customer's existing records and never `internal_source`; as of a date, a change requested after the point is missing.
  - `PendingChangeViews`: `Brief`, `Detail` (§6.11 shape incl. `changes[]`, `current`/`proposed`, `statusAt`, flags `canApprove/canReject/canWithdraw/blockedReason`, `approverCount`, `result`), `heldReply` (§6.6 202 body: `status: PENDING_APPROVAL`, message "Above North's approval limit of ₹100.00 for Cancel invoice. It takes effect once someone else approves it.", `pendingChange` with `supersededId`, `canWithdraw`); customer logins get `requestedBy/decidedBy/decisionNote/origin/ruleId` null; POC keys stripped without POC_VIEW.
  - `ApprovalService`: `get`; `approve` (not transactional; origin `approval(pcId, checker).withActor(maker)` bound **around** TX-A; TX-A: unlocked peek → `applier.lockDomainRows` → peek detached → `findByIdForUpdate` → checks 1–6 → region-moved check → `RunAs.call(principalFor(maker) active, LIVE_ALL, approval origin)` inside `ApprovalContext.applying` → `approve`, `CHANGE_APPROVED` (actor checker), notify; refusals of the re-run → `ApplyFailed` → TX-B re-reads under lock, FAILED + `CHANGE_APPLY_FAILED` + notify; concurrency/integrity propagate as 409); `reject` (note 1..500 else `fieldErrors.note`; own → 400 "Use Withdraw for your own request"); `withdraw` (maker only, 403 otherwise; no notice).
  - `ApprovalController` (`@AsOfAware`, `/api/approvals`): `GET /{id}` (authenticated; visibility 404), `POST /{id}/approve` and `/reject` (`@PreAuthorize(CHANGE_APPROVE)`, body `{"note"}` optional), `POST /{id}/withdraw` (authenticated).
- **`B/common/Outcomes.respond`** (200 + unchanged DTO, or 202 + `Location: /api/approvals/{id}` + held reply; no top-level `id`).
- **`B/invoice/InvoiceChangeApplier`** (INVOICE_CANCEL): `CancelPayload{invoiceId}`, `CancelView{total, paidAmount, status}`; locks customer → invoice; strict check of total/paid/status (only when the cancel's own rules still pass, so a paid or already-cancelled invoice fails with the rule's own message); re-runs `cancelOrHold`.
- **`InvoiceService.cancelOrHold`** (gate after the checks, before `setStatus`; held → nothing else touched: no invoice save, audit, outbox event or version); `cancel` is the legacy wrapper `cancelOrHold(id).appliedOrThrow()`. `InvoiceController.cancel` returns `ResponseEntity<Object>` via `Outcomes.respond`; bulk CANCEL uses `runOutcomes(…, eligibilityOutcome(service::cancelOrHold))`.
- **Bulk**: `BulkDtos.BulkPending(id, pendingChangeId, summary)`, `BulkResult` trailing `pending` (always present, `[]` except held cancels); `BulkExecutor.OutcomeOperation`, `eligibilityOutcome`, `runOutcomes` (`run` delegates).
- **Rights**: `Privileges.CHANGE_APPROVE`, `APPROVAL_THRESHOLD_MANAGE` (APPROVE level, appended to `ALL`; seeded to ADMIN through `upsertRole(ALL)`). `RegionGrantService`: granting a row at APPROVE needs the granter to hold `CHANGE_APPROVE` in that region (403; `replace` and `grantNewUser`); a user who gains APPROVE and thereby holds `CHANGE_APPROVE` there → `notifyHolders(CHANGE_APPROVE, region, newApprover, "APPROVER_ADDED", "New approver in North", "<name> can now approve changes in North", null)`.
- `GlobalExceptionHandler`: `ApprovalConflictException` → 409 with its message. `UserController.pocReferenceCount` adds `countByMakerUserIdOrDecidedByUserId`.
- **Tests**: new `T/approval/ApprovalTestBase` (helpers: `threshold`, `approverIn`, `makerIn`, `staffIn`, `workRegion`, `invoice`, `cancel`, `approve`, `reject`, `withdraw`, `pendingOf`, `audit`, `noticesTo`), `GateInvoiceTest` (9: no limit/at the limit/other op → 200 unchanged paths; held cancel → 202 body/`Location`, invoice status+version+paid unchanged, no `INVOICE_CANCELLED`, no outbox event, no new version, stored payload/base/proposed, `CHANGE_REQUESTED`, approvers told and the maker not; the Detail for approver/maker/reader/outsider (404); PC-13 NORTH 100 vs SOUTH 10000 → 202/200; bulk 3 with 2 above → 1 succeeded, 2 pending, then another maker → 409 message and bulk skipped; same maker again → SUPERSEDED with `supersededId`; SC-20 150-char name kept and fitting of over-long strings; legacy wrapper on a hold → `IllegalStateException`, no row), `ApproveFlowTest` (7: approve → APPROVED, `INVOICE_CANCELLED` actor maker/approved_by checker/`pending_change_id`/origin APPROVAL, `CHANGE_APPROVED` actor checker, outbox events origin APPROVAL actor maker, the invoice version origin APPROVAL/`origin_ref_id` pcId/actor maker (CF-1, SC-6), maker notified with the invoice link; self → 400, right only in another region → 403, not visible → 404, customer login → 403, `asOf` → 400 `AS_OF_READ_ONLY`, nothing changed; twice → 409 "Already approved by …"; reject note rules and REJECTED + notice; withdraw non-maker 403 / maker WITHDRAWN, no notice; inactive maker → FAILED "The requester's account is no longer active" (never 401); maker lost MANAGE → FAILED "no longer has the right"), `PayloadCodecTest` (3, plain), `ApprovalSchemaTest` (3: varchar enum columns with no CHECK; `outstanding_key` many NULLs, duplicate → integrity violation; drift boot without the two tables and with audit rows starts, `AppClock` restored).
- **Extended**: `IntegrationTestBase` (reset deletes pending changes then thresholds first, right after `clearVersions`), `ViewScopeInventoryTest` (`GET /api/approvals/{id}` BY_ID/AWARE; approve/reject/withdraw WRITES/WRITE), `PrivilegeClassificationTest` (APPROVE level = the two new privileges; `CHANGE_APPROVE → APPROVE`), `RegionAdminTest` (+2: APPROVE needs `CHANGE_APPROVE` in the region, incl. user create; `APPROVER_ADDED` to the region's other approvers only, not on a kept APPROVE nor a role that cannot approve), `AsOfEndpointCoverageTest` (INTERVAL probe for `GET /api/approvals/{id}`: a change rejected after the day reads `statusAt PENDING`, one asked after it is 404), `AsOfReadOnlyTest` (`pending_changes`, `approval_thresholds` in the unchanged-tables check), `RegionSchemaUpgradeTest` (the pre-regions drift DB also drops `pending_changes`/`approval_thresholds`, which reference `regions`).
- Mutation-checked: binding the approval origin inside TX-A instead of around it fails `ApproveFlowTest` (the version row says USER).

**Deviations from DESIGN.md (with reasons)**
- **`ChangeApplier.currentView(pc)`** added to the SPI, plus a small `ChangeAppliers` registry component (operation → applier). The Detail's `current`/`changes[].current` (§6.11) need the record in the change's own view shape, which only the domain applier can build; both `ApprovalService` and `PendingChangeViews` need the lookup.
- **Detail `current` is null in historical mode** (and when the caller cannot see the record, SC-15); `statusAt` is filled from `decided_at` vs the point, flags are false with `blockedReason` "Read-only in the as-of view". The fuller as-of treatment is BE-15's; `{id}` is AWARE already because §6.1 classifies it so and the client checks the header.
- **`PendingChangeScope.requireVisible` returns the row and has a `(PendingChange)` overload**, and `isVisible`/`canSeeRecord` are public (the views use them). The customer-login rule is applied to `{id}` as §5.11.9 gives it for `for-record` (own customer's existing records, never `internal_source`), so a customer login reaches check 4 (403) on approve rather than 404.
- **The unlocked peek in `approve` is detached before `findByIdForUpdate`**: otherwise the locked query returns the already-managed copy unrefreshed and a concurrent decision could be missed (the BE-04a trap).
- **Summary text** for a cancel is "Cancel invoice INV-… for Acme Ltd (₹500.00)" (the customer's name added to §6.6's example) so the approver sees whose invoice it is; it is display text only.
- **Maker principal**: `principalFor(maker).filter(active)` rather than `RunAs.asUser`, so a customer-login maker (BE-14 dispute proposals) is not refused; an inactive or missing maker → FAILED "The requester's account is no longer active".
- **`APPROVER_ADDED` goes out only when the user really becomes an approver** (the grant is APPROVE *and* their role holds `CHANGE_APPROVE` there); an APPROVE grant to a role that cannot approve announces nobody. The notice has no link. The check "granting APPROVE needs `CHANGE_APPROVE`" applies to added or changed rows whose new level is APPROVE (not to taking APPROVE away, which the existing level bound already covers).
- **`DataSeeder` unchanged**: ADMIN already receives every privilege in `ALL` through `upsertRole`, and no other role is seeded the new ones (§4.1).
- Check 5a (internal-dispute opener) is not implemented: internal disputes arrive in BE-19. `PendingChangeCloser` (closing on the applied cancel path) is BE-14 as listed.

**API-contract changes versus §6:** none. Delivered: §6.6 held 202 on `POST /api/invoices/{id}/cancel`, §6.7 `BulkResult.pending`, §6.11 `GET /api/approvals/{id}`, approve/reject/withdraw, 409 `ApprovalConflictException` messages, D14 flags on the Detail.

**Known gaps / deferred**
- Remaining gated operations, the closer, `for-record`/list/summary/count/operations/export, thresholds API, cool-down, cumulative window (`approval_usage`) and full as-of: BE-14/BE-15 as planned. `effectiveThreshold` is the plain row lookup.
- `MakerChecker` records `rule_id`/`automation_event_id` from an AUTOMATION origin already; nothing runs rules until BE-17.

**Tests:** backend 519 run, 0 failures, 0 errors, 0 skipped (495 before + 24 new: GateInvoiceTest 9, ApproveFlowTest 7, PayloadCodecTest 3, ApprovalSchemaTest 3, RegionAdminTest +2 (now 19)). mail-service 24 run, 0 failures, 0 errors. Logs: build/be13-full.log, build/be13-mail.log.

## BE-14 — Maker-checker: every remaining operation

**Built**
- **Gated entry methods** (§5.11.1), each `xOrHold(...)` returning `Outcome<T>`; the old signatures are wrappers `xOrHold(...).appliedOrThrow()` (same annotations), so every existing caller and test compiles unchanged:
  - `InvoiceService.createOrHold` (customer lock → visibility → `requireIn` → `requireAssignable` → `due` → `buildLines`/total → **gate** → `invoiceNumbers.next()` → save; a held create draws no number and publishes nothing; payload = the request with every line's `unitPrice` pinned, `invoiceDate` as sent), `updateOrHold` (the resolved `Due` and the Sales POC checks are worked out **before any setter**; the gate runs only when the due date or term really changes; measure = the balance when the new date is later than the current one (or it is null), else 0; payload = the request with `version = null`), `public BigDecimal totalFor(List<LineInput>)` (pure: prices and checks lines as a create does, saves nothing).
  - `PaymentService.recordOrHold` (gate after the targets are sorted, before the builder; `paidAt` = `ApprovalContext.current().requestedAt` or the clock, so an approved payment keeps the request time).
  - `PaymentPromiseService.createOrHold` (gate after `resolveInvoices`; payload keeps the resolved `collectionPocUserId` and `assignees`), `updateOrHold` (POC and invoice checks before the gate; measure `max(|new−old|, newDate > oldDate ? newAmount : 0)`; payload = the full PUT), `overrideOrHold` (measure `status == BROKEN ? 0 : max(0, amount − fulfilled)`; payload `OverrideStatusRequest`).
  - `DisputeService.approveOrHold`: `applyChange` split into `parse(Dispute, json) → ParsedChange` (every existing validation and message), `measure` (cancel: total; replace_items: |Δtotal| via `totalFor`; void: amount; update_amount: |Δ|; notes/meta: 0) and `apply`; the checker step of §5.11.8 (as proposed — JSON trees compared, so reformatting is still "as proposed" — ∧ caller ≠ opener ∧ caller ≠ `credentials_set_by_user_id` ∧ `CHANGE_APPROVE` in the dispute's region); held with maker = the staff approver, dispute stays PENDING; `CHANGE_*` rows go on the target with `dispute_id`. `deny` closes what waits on the dispute.
- **Appliers** (`ChangeApplier`, re-run as the maker): `InvoiceChangeApplier` now covers INVOICE_CREATE (customer lock; rules re-run), INVOICE_CANCEL, INVOICE_TERMS (customer → invoice; three-way on due date and term, and on notes / Sales POC when the request sets them; `TermsView`, `CreateView`); new `PaymentChangeApplier` (PAYMENT_RECORD; customer lock), `PromiseChangeApplier` (PROMISE_CREATE; PROMISE_UPDATE three-way on amount/date/notes, POC and invoices when set, then **re-based**: a term the maker left alone takes today's value, so an approval never undoes someone else's edit; PROMISE_OVERRIDE strict on `statusOverridden`/`overrideReason`; `PromiseView`, `OverrideView`), `DisputeChangeApplier` (customer → dispute; strict on the target's total/paid/status or amount/status via `DisputeTargetView`). A record whose own rules now refuse the change fails with that rule's message; the amount-not-grown check is the gate's apply mode.
- **`B/approval/PendingChangeCloser`** (`closeForCustomer`, `closeForRecord`, both `MANDATORY`): locks the PENDING rows (after the writer's own locks), sets CLOSED (`decided_by` null, note = reason), audits `CHANGE_CLOSED` (actor null, `pending_change_id`), notifies the maker `APPROVAL_CLOSED`; skips the row an approval is applying. Called from `cancelOrHold` (applied: "Invoice cancelled"), `approveOrHold` (applied: "Dispute resolved") and `deny` ("Dispute denied"). `closeForCustomer` is ready for BE-15's `CustomerService.delete`. `PendingChange.close`, `PendingChangeRepository.findOnRecordForUpdate` / `findForCustomerForUpdate`.
- **SC-10**: `users.credentials_set_by_user_id` (`User.credentialsSetByUserId`, nullable, no FK), `UserRepository.findCredentialsSetBy`; set by `CustomerService.create` and by `update` when staff set the login's password; cleared by `AuthController.change-password`.
- **Controllers** return `ResponseEntity<Object>` via `Outcomes.respond` (200 + the unchanged DTO, or 202 + `Location` + held reply): `POST /api/invoices`, `PATCH /api/invoices/{id}`, `POST /api/payments`, `POST /api/promises`, `PUT /api/promises/{id}`, `POST /api/promises/{id}/override`, `POST /api/disputes/{id}/approve` (with BE-13's cancel, all eight).
- `PromiseDtos.CreatePromiseRequest` gains a trailing `List<EmailDtos.EmailToken> assignees` (`@JsonCreator` compact canonical constructor, 6-argument convenience constructor); accepted, kept in a held payload, otherwise ignored until BE-19.
- **Tests (new)**: `GatePaymentPromiseTest` (4), `ApproveFailureTest` (7), `ApprovalConcurrencyTest` (3), `OutstandingChangeTest` (5), `DisputeApprovalGateTest` (5), `ExemptPathsTest` (3). **Completed**: `GateInvoiceTest` (+3: no-limit create/PATCH unchanged; held create takes no number or sequence step, `CHANGE_REQUESTED` on CUSTOMER, prices pinned, approver told and maker not; terms extension held with the whole edit, shortening applied, notes-only PATCH reads no `approval_thresholds`), `ApproveFlowTest` (+2: create approved a day later → approval-day prefix, `invoiceDate` = approval instant, no number gap after a rejection; payment approved later keeps `paidAt` = request). `ApprovalTestBase` gained helpers only (`collectorIn`, `promise`, `createInvoice`, `pay`, `putPromise`, `override`, `everyLimitAtZero`, `pendingCount`). Mutation-checked: applying the stored PUT without the re-base fails `aPocReassignedWhileTheChangeWaitedIsKept`. The concurrency and lock tests were run 3× more, green each time.

**Deviations from DESIGN.md (with reasons)**
- **`ChangeConflictException` gained static helpers** `requireUnchanged` (strict), `requireNotChangedElsewhere` (three-way) and `same` (money by value), shared by the four appliers; `InvoiceChangeApplier`'s private helper now uses them (same messages).
- **`ApprovalService.approve` keeps TX-A's own failure when the rollback itself fails.** On H2 a lock wait that times out is a `SQLTimeoutException`, which Hikari treats as fatal and evicts the connection; the rollback then fails and `TransactionTemplate` throws the rollback's `JpaSystemException` in place of the `PessimisticLockingFailureException` → 500. §5.11.6 needs 409 with the row still PENDING (the §8.6 "customer lock held 3 s" case), so TX-A's body failure is rethrown when the rollback error replaced it (nothing was committed either way).
- **`UserController.update`** (not in the list) also sets `credentials_set_by_user_id` when an administrator resets a **customer login's** password through `PUT /api/users/{id}`: it is the same SC-10 hole as the customer PUT.
- **Validation moved before the gate** in `InvoiceService.updateOrHold` (Sales POC `canAssignPocIn`/`requireAssignable`) and `PaymentPromiseService.updateOrHold` (Collection POC checks, `resolveInvoices`), per the "authorise → validate → gate → mutate" order, so a held change is one that could be made as it stands. The same messages; the applied path is otherwise unchanged.
- **`closeForRecord` matches `entity_*` or `affected_*`**, so cancelling an invoice also closes a waiting dispute approval whose target is that invoice (it could only fail now).
- **CUSTOMER target / `none` action rules** named in §5.11.1 refactor 3 are left to BE-19, which adds `DisputeTargetType.CUSTOMER`; `parse` keeps today's INVOICE/PAYMENT actions and messages. Check 5a (internal-dispute opener) and `internal_source` on holds are BE-19 as planned.
- **`DisputeTargetView.status`** is the status name (an invoice's or a payment's).
- **Display text** (fitted as before): entity labels "New invoice for Acme Ltd", "New payment from Acme Ltd", "New promise from Acme Ltd", "Promise #12 from Acme Ltd", "Dispute #7"; summaries "Create invoice for Acme Ltd (₹500.00)", "Extend due date of INV-… from 2026-10-26 to 2026-11-25 on ₹500.00 outstanding", "Record payment of ₹600.00 from Acme Ltd", "Promise of ₹500.00 by 2026-10-02 from Acme Ltd", "Change Promise #12 from Acme Ltd: ₹1,000.00 by D to ₹700.00 by D", "Mark Promise #12 from Acme Ltd KEPT with ₹800.00 unpaid", "Approve dispute #7 on payment #3: void (₹500.00)".
- **Test file edit (BE-13's, not baseline)**: `GateInvoiceTest.aCancelWithNoLimitOrWithinItIsMadeAsBefore` made its "another operation's limit" invoice before setting the `INVOICE_CREATE` limit (the legacy create is now correctly held there). The assertion is unchanged.

**API-contract changes versus §6:** none. §6.6's eight endpoints answer 202 when held; `CreatePromiseRequest.assignees` is accepted (ignored until BE-19).

**Known gaps / deferred**
- BE-15: list/summary/count/for-record/export, `pendingApproval` flags, cool-down, cumulative window, thresholds API, `closeForCustomer` from `CustomerService.delete`, the move participant. BE-19: promise co-assignees applied on create/approval, internal disputes (5a, `internal_source`), CUSTOMER targets.
- The BE-13 review findings were not in this slice and are still open: money in the Detail's `current`/`proposed`/`changes` serialises as doubles (`PayloadCodec.tree`); `Location` not in the CORS exposed headers; no service-level test of check 4; strict-check fields that changed do not appear as `changes[]` rows. Findings 1 and 4 now also apply to the new views (`TermsView`, `PromiseView`, `OverrideView`, `DisputeTargetView`).
- `resultJson` of an approved promise is the `PromiseDto` built in TX-A (its `canManage`/`canOverride` are the checker's view). Nothing reads `result_json` on the wire yet.

**Tests:** backend 551 run, 0 failures, 0 errors, 0 skipped (519 before + 32 new: GateInvoiceTest +3 (now 12), ApproveFlowTest +2 (now 9), GatePaymentPromiseTest 4, ApproveFailureTest 7, ApprovalConcurrencyTest 3, OutstandingChangeTest 5, DisputeApprovalGateTest 5, ExemptPathsTest 3). mail-service 24 run, 0 failures, 0 errors. Logs: build/be14-full.log, build/be14-mail.log.

### BE-14 review fixes
All five findings in `build/BE-14.review.md` were checked against the code and found valid. All five are fixed.
- **1 (medium) SC-10 bypass through change-password. Fixed, with a deviation from §3.4 and §5.11.8.** `AuthController.change-password` no longer clears `users.credentials_set_by_user_id`. Whoever set the old password knows it and may be the one changing it, so a change made while signed in as the customer proves nothing. The column now keeps the last staff member who set the password; the next staff reset replaces it. The cost is small: that one person's as-proposed approval is held for another approver, which is narrower than the sub-point SC-10 declined (disabling the step whenever the column is set). `DisputeApprovalGateTest.whoeverSetTheCustomersPasswordIsNotTheSecondPairOfEyes` now asserts that after the customer changes the password, the column still names the admin, the admin's second approval is still held (202; the earlier row is SUPERSEDED), and a different CHANGE_APPROVE holder's as-proposed approval applies and closes the held row. That still meets the §8.6 line "a CHANGE_APPROVE holder's as-proposed approval applies". The Javadoc on `User.credentialsSetByUserId` is updated.
- **2 (medium) Unpinned `replace_items` prices on a held dispute resolution. Fixed.** `DisputeService.approveGate` now stores `pinned(change)`: the change JSON with each `replace_items` line's `unitPrice` set to the price `measure` used, via the new `InvoiceService.priced(lines)`, which uses the same `buildLines` path as `totalFor`. Other actions are stored as sent. The new test `DisputeApprovalGateTest.aHeldReplacementOfLinesKeepsThePricesTheApproverSaw` holds a 3 × 100.00 replacement on a 500.00 invoice, raises the catalogue price to 150.00 (the measure becomes 50, so "amount grew" does not fire), approves, and checks that the invoice is re-issued at 300.00 and the stored unitPrice is 100.00. With pinning disabled, the test fails.
- **3 (low) Customer logins from before BE-14 have no setter. Fixed without a backfill.** `DisputeService.credentialsSetBy(d)` reads the opener's column. When the opener is a customer login and the column is null, it falls back to the actor of the customer's first `CUSTOMER_CREATED` audit row (new `AuditLogRepository.findFirstActor`), since that person chose the starting password. For a non-customer opener it returns null, which leaves the BE-19 internal disputes to check 5a. I chose a read-time fallback over a startup backfill: it needs no schema step, and it is exact for new rows, since CustomerService.create sets the column. The new test `DisputeApprovalGateTest.onALoginWithNoSetterRecordedWhoeverCreatedTheCustomerIsNotTheSecondPairOfEyes` fails if the fallback is removed.
- **4 (low) Missing success paths. Fixed with three new `ApproveFlowTest` cases:**
  - `aHeldPromiseIsMadeWhenApproved`: the CreatePromiseRequest payload round trip, POC, notes, and an audit row naming the maker and the approver.
  - `aHeldOverrideIsMadeWhenApprovedAndFailsIfSomeoneElseSetTheStatusFirst`: an approved KEPT override. Another held override FAILS with "Status override was changed by someone else" after an admin BROKEN override (measure 0, so applied at once).
  - `aHeldChangeOfTermsIsMadeWhenApprovedAndFailsIfTheDueDateMovedMeanwhile`: an approved extension keeps an unrelated sales-POC reassignment. Another FAILS with "Due date was changed by someone else" after the due date is brought forward.
- **5 (low) `everyLimitAtZero` and THRESHOLD_CHANGE. Fixed.** The loop now filters on `op.amountGated()`.

The final definition of done: backend `mvn -q -o test` gives 556 tests, 0 failures, 0 errors, 0 skipped (551 + 5 new). mail-service gives 24 tests, 0 failures, 0 errors.

## BE-15 — Maker-checker: visibility, lists, as-of, lifecycle, thresholds, the escape hatches

**Built**
- **`ApprovalOperation.THRESHOLD_CHANGE`** (appended last; entity type `REGION`, view privilege `APPROVAL_THRESHOLD_MANAGE`, label "Loosen approval limits", `amountGated() == false`). `Gated.outstandingKey` gives `REGION_THRESHOLDS:<regionId>` for it; `Gated.auditEntityType/Id` (and `ApprovalService.auditType/auditId`) fall back to the change's own record when it has no affected record, so its `CHANGE_*` rows go on `("REGION", regionId)`.
- **`MakerChecker`**:
  - `effectiveThreshold(g, now)`: the row for the record's region, or the stricter (lower amount, then longer window) row of any region the customer left within the cool-down (`RegionMoveCooldown`). A region without a row does not gate, so moving into an ungated region still gates at the old limit.
  - Cumulative window (SC-9): only when a threshold exists, the change alone is within it, the row has `window_hours > 0`, and there is a maker and a customer: measure = amount + `ApprovalUsageLedger.recent(...)` (applied, unapproved, in the window) + `PendingChangeRepository.sumPendingBy(...)` (still PENDING, excluding the row this one would supersede). Applied within the limit → `approval_usage` row. Held because of the window → the summary gets "…; with your other changes to Acme Ltd in the last 24 hours this comes to ₹1,10,000.00" (`amount` stays the change's own measure).
  - The hold itself is now the public `hold(Gated, threshold, makerId, summary, now)` (supersede own / 409 anyone else's / save / `CHANGE_SUPERSEDED` + `CHANGE_REQUESTED` / notify), used by `gate` and by the held loosening of limits.
- **New `B/approval` classes**: `ApprovalUsage` (`approval_usage`, index `idx_approval_usage`) + `ApprovalUsageRepository` (`sumSince`, `deleteUsedBefore`), `ApprovalUsageLedger` (`recent`, `record`), `ApprovalHousekeeping` (daily `@Scheduled(cron = ${app.approval.housekeeping-cron:0 15 3 * * *}, UTC)`, `purge()` deletes rows older than 720 h), `RegionMoveCooldown` (`previousRegions(customerId, now)` from `customer_region_moves`, `app.approval.move-cooldown-days`, default 30), `ApprovalThresholdService` (implements `RegionCreatedListener`), `ApprovalThresholdController`, `ThresholdChangeApplier`, `PendingChangeMoveParticipant`.
- **Thresholds API** (`/api/regions/{regionId}/approval-thresholds`, class `@AsOfIgnored`): `GET` (authenticated; 404 unknown region; 403 without `APPROVAL_THRESHOLD_MANAGE` or `CHANGE_APPROVE` in the region) → `{regionId, regionName, canEdit, activeApprovers, pendingChange (Brief|null), thresholds[8 × {operation, label, measure, amount, windowHours, updatedAt, updatedBy{id,name}}]}`. `PUT` (`@PreAuthorize(APPROVAL_THRESHOLD_MANAGE)` + `requireIn` in the region; locks the region row, then its threshold rows): validation → `fieldErrors["thresholds[i].operation|amount|windowHours"]` (unknown/THRESHOLD_CHANGE/duplicate operation; amount < 0, more than 2 decimals, > 12 integer digits; window outside 0..720; missing body → `fieldErrors.thresholds`). Per operation and per part: a new row, a lower amount or a longer window applies at once; a higher amount, a shorter window or a deletion is collected into **one** THRESHOLD_CHANGE (payload `Loosening{thresholds}`, base/proposed = limits keyed `invoiceCancel` etc., amount 0, threshold 0, entityLabel "Approval limits in North", summary "Loosen approval limits in North: Cancel invoice ₹100.00 → ₹1,000.00; Record payment ₹100.00 → no limit"). 200 with the GET shape, or 202 + `Location: /api/approvals/{id}` + the GET shape with `pendingChange`. Every change (applied or held) → audit `("REGION", id, "APPROVAL_THRESHOLDS_CHANGED", before, after)` (reason "Loosening held for approval as #N" when held) and `notifyHolders(CHANGE_APPROVE, region, caller, "APPROVAL_THRESHOLDS_CHANGED", …, "/approval-thresholds?regionId=N")`; `warnings` when fewer than 2 active approvers ("Only 1 person can approve changes in East, and nobody can approve their own changes"). Same maker's second loosening supersedes; anyone else's → 409.
- **`ThresholdChangeApplier`**: locks region → threshold rows; `currentRegionId` = the region; `apply` (as the maker, who must still hold `APPROVAL_THRESHOLD_MANAGE` there, else FAILED) runs a three-way check per loosened limit (current ≠ base ∧ current ≠ proposed → FAILED "The invoice cancel limit was changed by someone else") and then `ApprovalThresholdService.applyLoosening` (sets each row exactly; audited with the maker as actor, `approved_by` = checker via the approval origin; approvers notified).
- **New regions** start with a copy of the default region's rows (`regionCreated`, in the create transaction; audit "Copied from the default region, …").
- **`PendingChangeScope`** (full): `forList(View)` → a `ScopeResolver.Scope` (§5.11.9 VISIBLE predicate: maker ∧ granted; `CHANGE_APPROVE` regions; THRESHOLD_CHANGE ∧ `APPROVAL_THRESHOLD_MANAGE` regions; per record kind, `entity_type = X ∧ region ∈ regionsWith(X_VIEW) ∧ entity_id ∈ ids(X, scope(X))` as subqueries on the outer query through `Temporal.subquery`; creates by operation ∧ region ∈ regionsWith(view) ∧ (record POC = me ∨ customer ∈ ids(Customer, forCustomers()))), `region ∈ narrow(granted)`, the view (AWAITING_ME: outstanding ∧ maker ≠ me ∧ region ∈ CHANGE_APPROVE regions; MINE: maker = me), and the interval when a date is bound. Coverage = the answered regions (so `PageResponse.regionIds`), no `lockedFilters`. Customer logins → 403. `forRecord(type, id)` (outstanding; INVOICE/PAYMENT/PROMISE on `affected_*`, DISPUTE on `entity_*`, CUSTOMER = its creates; each row through `isVisible`). `pendingOn(type, ids)` (one query per page; customer logins never count `internal_source`). Static `pendingApprovalFilter` (EXISTS; `eq` only; `internal_source = false` for a customer login or nobody) and `outstanding` (live `status = PENDING`, as of a date `requested_at <= p ∧ (decided_at null ∨ > p)`). `isVisible`/`canSeeRecord` handle THRESHOLD_CHANGE (approvers and limit setters of the region only; never customers).
- **`ApprovalController`**: `GET /api/approvals?page&size&sort&filter&view` (`PageResponse<Brief>`), `GET /summary` (`{pendingCount, pendingAmount, awaitingMeCount, mineCount}` over the list's filters and the outstanding rows), `GET /awaiting-me/count` (`{count}`), `GET /for-record?entityType&entityId` (the record's own scoped `get` first: 404 / the existing customer 403s; CUSTOMER staff-only → 403 for a customer login), `GET /operations` (method `@AsOfIgnored`; all nine operations with label, entity type and measure), `POST /export` (method `@AsOfAware`, EXPORT_DATA; `view` from the query or `params.view`; CSV with `statusAt` as the status when historical). Read methods live in `ApprovalService` (`page`, `summary`, `awaitingMeCount`, `briefs`, `forRecord`).
- **`TableSchemas.APPROVALS`** (entity `approvals`, default sort `requestedAt,desc`; keys = Brief keys: `id`, `status`, `operation`, `entityType`, `entityId`, `entityLabel`, `customerId` REF customer, `customerName`, `regionId` REF region, `amount`, `threshold` (→ `thresholdAmount`), `summary` (not sortable), `requestedByUserId` REF staffUser (→ `makerUserId`), `origin`, `requestedAt`, `decidedAt`); registered; `TableSchemaController` `approvals → any of CHANGE_APPROVE, INVOICE_VIEW, PAYMENT_VIEW, PROMISE_VIEW, DISPUTE_VIEW`. A `pendingApproval` BOOLEAN column (`eq` only, not sortable) on INVOICES, PAYMENTS, PROMISES (affected) and DISPUTES (entity).
- **DTOs**: `InvoiceSummary`, `PaymentDto`, `PromiseDto`, `DisputeDto` gain a trailing `@JsonInclude(NON_NULL) Boolean pendingApproval` + `withPendingApproval(...)`; filled only by `InvoiceService.page`, `PaymentService.page`, `PaymentPromiseService.page` and `DisputeController.list` (one `pendingOn` query per page). Details and audit snapshots carry no flag.
- **Audit**: `AuditTimelineService.Entry` gains `pendingStatus` (one `findAllById` of the named changes; status now, or at the point: PENDING until decided). For `customerView`, rows of `internal_source` changes are dropped. `withoutPocIdentity` drops CUSTOMER-level `CHANGE_*` rows and strips `makeruserid`, `requestedby`, `decidedby`, `decidedbyuserid`, `approvedby`, `approvedbyuserid` (approvedBy was already nulled since BE-01). `AuditEntryDto` gains trailing `pendingStatus`.
- **Lifecycle**: `CustomerService.delete` → `closeForCustomer(id, "Customer deleted")` (after the customer lock). `PendingChangeMoveParticipant`: `beforeCustomerMoved` locks the customer's PENDING rows and vetoes with 400 "Acme Ltd has N change(s) awaiting approval; they must be decided first" unless the mover holds `CHANGE_APPROVE` in the source region; `afterCustomerMoved` → `closeForCustomer(cid, "Customer moved from North to South")`, counted in `moved.other.PendingChangeMoveParticipant`.
- **Config**: `app.approval.move-cooldown-days: 30`; scheduling pool 4 → 5 (approval housekeeping); test profile `app.approval.housekeeping-cron: "-"` (never on its own schedule in tests).
- **Tests (new)**: `ApprovalVisibilityTest` (9), `ApprovalAsOfTest` (6), `ApprovalAuditTimelineTest` (4), `ApprovalLifecycleTest` (6), `ApprovalThresholdApiTest` (10), `RegionMoveCooldownTest` (5), `CumulativeGateTest` (6). **Extended**: `IntegrationTestBase` (reset deletes `approval_usage` after thresholds), `ViewScopeInventoryTest` (8 endpoints classified + pinned), `RegionParamCoverageTest` (+4 rows: list, summary, awaiting-me count, export), `AsOfEndpointCoverageTest` (+5 INTERVAL probes; a second later change on B; `leavesOutALiveRow` understands CSV and `pendingCount`), `AsOfReadOnlyTest` (`approval_usage` in the unchanged tables), `AsOfScenario` (D5–D7 approval story), `CustomerRegionMoveTest` (+1: SC-7 promise race; `moved.other` now has 4 participants).
- Mutation-checked (build/mut15; files restored and diffed): dropping the cool-down fails 3 `RegionMoveCooldownTest` cases; dropping the cumulative window fails 4 `CumulativeGateTest` cases; dropping the move veto fails `ApprovalLifecycleTest`; letting customers count internal changes fails `ApprovalVisibilityTest`; ignoring the as-of interval fails 4 `ApprovalAsOfTest` cases.

**Deviations from DESIGN.md (with reasons)**
- **`RegionMoveCooldown.previousRegions(customerId, now)` returns every region left within the cool-down**, not `Optional<Long> previousRegion`. With two quick moves (N → S → E) a single "previous region" would shed N's limit after the second move; taking the strictest of all keeps SC-4's intent (tested: `twoQuickMovesKeepTheFirstRegionsLimitToo`).
- **A loosening row that also tightens another part is split per part**: e.g. a lower amount with a shorter window applies the lower amount now and holds the row at (new amount, new window). §5.11.11 lists the parts; it does not say what happens to a row with both.
- **Every held loosening also files `APPROVAL_THRESHOLDS_CHANGED` and notifies `APPROVAL_THRESHOLDS_CHANGED`** (besides the hold's own `CHANGE_REQUESTED`/`APPROVAL_REQUESTED`), literally "every change, applied or held, is audited … and notified". The audit row's after-JSON is the limits as applied now; its reason names the held change.
- **`ApprovalThresholdService.put` returns `Saved(view, held)`**, not `Outcome<ThresholdsView>`: a held PUT still answers with the view (§6.11: "202 with the GET shape … plus pendingChange"), which a `Held` outcome cannot carry.
- **`ThresholdChangeApplier` looks `ApprovalThresholdService` up through an `ObjectProvider`**: the service shows held changes (`PendingChangeViews`), whose `ChangeAppliers` include this applier — a constructor cycle otherwise.
- **`THRESHOLD_CHANGE.viewPrivilege()` is `APPROVAL_THRESHOLD_MANAGE`** and its label "Loosen approval limits" (the design gave no label).
- **The approvals schema's `regionId` is sortable** (the records' is not): FE-04's approvals screen uses `regionId` as the Region column's sort key; an unsortable column would answer 400.
- **`/api/approvals/operations` lists all nine operations** (THRESHOLD_CHANGE included, entity type REGION), so the list can label every row; the thresholds GET lists the eight amount-gated ones as specified.
- **`AsOfScenario` tells D5–D7, not D5–D6**, as an opt-in helper (`xExtendedWithApproval()`, like `betaDisputesB()`): D5 held, D6 superseded by the same maker, D7 approved; "today" moves to `AFTER_D7`. Putting it in the shared `@BeforeEach` would have moved `TODAY` under every earlier as-of test. The supersede day is `ApprovalAsOfTest`'s "T2".
- **Test profile disables the housekeeping cron** (`app.approval.housekeeping-cron: "-"`), in the style of the other background intervals, so a purge can never race a test's moved clock.
- **The held reply's message names the record's region** ("Above South's approval limit of ₹100.00") even when the cool-down applied the region left behind's limit; the threshold amount itself is the one that applied. `PendingChangeViews` is BE-13's and the wording is display text.

**API-contract changes versus §6:** none. Delivered: §6.11 list (`view`), summary, awaiting-me count, for-record, operations, export; thresholds GET/PUT incl. 202 + `pendingChange` + `warnings` + `Location`; §6.4 move 400 veto and `moved.other.PendingChangeMoveParticipant`; §6.3 region create copying the default region's limits; §6.5 `pendingApproval` (list only) and the `pendingApproval` columns; §6.15 `pendingStatus`; §6.16 `approvals` schema.

**Known gaps / deferred**
- BE-19: `internal_source` is only ever set by internal disputes, which BE-19 adds; BE-15's customer-login exclusions are tested by setting the flag directly. Check 5a and nulling `reason`/`disputeId` of internal-dispute rows for customers are BE-19's.
- A `status` filter on the approvals list compares the change's status now, also as of a date (the rows are already limited to those outstanding then, and `statusAt` says PENDING).
- BE-13/14 review leftovers not in this slice: money in `Detail.current/proposed/changes` serialises as doubles; `Location` is not a CORS-exposed header.
- Slice size ≈ 3,400 changed lines (≈ 1,350 main, ≈ 2,050 tests), above the 2,500 guide; all within BE-15's own list.

**Tests:** backend 607 run, 0 failures, 0 errors, 0 skipped (556 before + 51 new: ApprovalVisibilityTest 9, ApprovalAsOfTest 6, ApprovalAuditTimelineTest 4, ApprovalLifecycleTest 6, ApprovalThresholdApiTest 10, RegionMoveCooldownTest 5, CumulativeGateTest 6, RegionParamCoverageTest +4 (now 48), CustomerRegionMoveTest +1 (now 12)). mail-service 24 run, 0 failures, 0 errors. Logs: build/be15-full.log, build/be15-mail.log.

### BE-15 review fixes

All four findings were valid, and all four are fixed.

1. **[medium] A region with limits could not be deleted.** Added a `region/RegionDeletingListener` SPI, the counterpart of `RegionCreatedListener`. `RegionService.delete` calls it after its own checks, before grants are deleted. `ApprovalThresholdService` implements it:
   - a THRESHOLD_CHANGE still PENDING in the region → 400 "Region has a change of its approval limits waiting; decide or withdraw it first";
   - any other pending change ever asked in the region → 400 "Region has changes that were held for approval; deactivate it instead". Before, that was a 409 from the FK at the flush; §6 says a referenced region gets 400;
   - otherwise the region's decided THRESHOLD_CHANGE rows are deleted (they concern only the region, and its REGION audit trail keeps the history), and so are its `approval_thresholds` rows.
   New repository methods: `ApprovalThresholdRepository.deleteByRegionId`, and on `PendingChangeRepository` `existsInRegion`, `existsInRegionOtherThan` and `deleteInRegion`.
   Deviation: decided THRESHOLD_CHANGE rows are deleted with the region rather than kept. Keeping them would make the region impossible to delete (`pending_changes.region_id` is a real FK).
   Tests, in ApprovalThresholdApiTest: `anEmptyRegionIsDeletedWithItsLimits` (DEFAULT has a limit, a new region is deleted → 204; a pending loosening → 400; withdrawn → 204, row gone, audit kept) and `aRegionWithHeldChangesOfRecordsIsDeactivatedInstead`.
2. **[low] A customer's trail hid the applied row of an internal change.** `AuditTimelineService.timeline` now drops only `CHANGE_*` rows of an `internal_source` change for a customer login. The applied row stays, with `pendingChangeId`, `approvedByUserId` and `pendingStatus` nulled (`Entry.withoutPendingChange`). Test: `ApprovalVisibilityTest.aCustomerLoginSeesWhatAnInternalChangeDidOnceApproved`.
3. **[low] A customer could read the checker's note in the trail.** `withoutPocIdentity` nulls `reason` on CHANGE_APPROVED, CHANGE_REJECTED and CHANGE_WITHDRAWN rows. CHANGE_CLOSED keeps its reason, because it is system text such as "Customer moved from North to South", not a staff note. Test: `ApprovalAuditTimelineTest.aCustomerLoginDoesNotReadTheCheckersNote`.
4. **[low] As of a date, the approvals status filter used today's status.** The APPROVALS `status` column now has a custom filter (`TableSchemas.approvalStatusPredicate`). Live, it delegates to the generic `FilterPredicates.build`. As of a date, only changes outstanding then are listed, so each one's status then is PENDING. EQ/IN therefore match all rows iff PENDING is among the values, and NEQ/NOT_IN match all rows iff it is not; values are still validated (400 on an unknown status). Sorting by status as of a date still sorts by today's status, which is harmless. This closes the known gap logged for BE-15. Test: extra assertions in `ApprovalAsOfTest.aChangeIsOutstandingFromItsRequestUntilItIsDecided`.

Final counts: backend 611 tests, 0 failures, 0 errors, 0 skipped (607 + 4 new tests); mail-service 24, 0 failures, 0 errors.

## BE-16 — Automation: rules, regions, conditions, placeholders, preview (A2, A4)

**Built**
- **Rights and seeding.** `Privileges.AUTOMATION_VIEW`, `AUTOMATION_MANAGE`, `AUTOMATION_ADMIN` (global, appended to `ALL` and `GLOBAL`). `DataSeeder`: CASHIER (upsert) and the three POC roles (`createRoleIfAbsent`, so existing databases get them once) gain VIEW and MANAGE; ADMIN has all three through `ALL`.
- **`B/common/query/Condition`** (sealed `Leaf(FilterSpec)` / `Group(Junction, children)`): `validate(schema, path, errors)` (columns and operators against the full staff schema, depth ≤ 4 groups, ≤ 30 leaves, 1..20 children, empty group refused), `toPredicate(schema)` (leaves through the package-private `FilterPredicates.build`, groups `and`/`or`; a leaf whose value fails coercion throws `Condition.InvalidLeafException` carrying its path), `leaves()`, `andOnly()`. **`ConditionJson`**: `parse(JsonNode, path, errors)` with paths (`condition.children[1].children[0].filter`, `condition.op`), `write`, `read(String)`, `filters` (for `listLink`).
- **`TableSchemas`**: `paymentTerm` (ENUM of `PaymentTerm`) on INVOICES and CUSTOMERS; `overdueAmount` (MONEY) on CUSTOMERS, a `Temporal.subquery` over overdue invoices against `InvoiceDates.today()` (as-of correct); `AUTOMATION_RULES` (`automation-rules`, default `name,asc`: `id`, `name`, `enabled`, `status` (custom filter ACTIVE/DISABLED/SUSPENDED, not sortable), `suspendedReason`, `triggerKind`, `entityType`, `ownerUserId` REF staffUser, `regionIds` REF region (custom EXISTS over the element collection; EQ/IN/NEQ/isEmpty/isNotEmpty; not sortable), `lastRunStatus`, `lastRunAt`, `nextRunAt`, `createdAt`, `updatedAt`); `AUTOMATION_RUNS` (`automation-runs`, default `createdAt,desc`, the §6.14 columns). Both registered; `TableSchemaController` maps both to AUTOMATION_VIEW.
- **`B/automation`**: `AutomationProperties` (`app.automation.*`, every §5.15 key with its default, public setters, plus `maxPreviewSample` 20); `AutomationSchemaUpgrade` (widens `automation_rules.trigger_kind/entity_type/suspended_reason/last_run_status`, `automation_runs.status`, `automation_effects.status`).
- **`B/automation/rule`**: `AutomationRule` (every §3.6 column and index; `@ElementCollection regionIds` in `automation_rule_regions` with `idx_rule_region`, `@BatchSize(50)`; `@Version`; timestamps from `AppClock`) + repository (`findByIdForUpdate`, case-insensitive `nameTaken` among rules not deleted, `countByOwnerUserIdAndDeletedAtIsNull`); `AutomationRuleRevision` (every §3.6 column, index `(rule_id, saved_at)`) + repository (`latestAt(instant)`, `findFirstByRuleIdAndSavedAtLessThanEqualOrderByIdDesc`); `TriggerKind` (MANUAL last; `isSchedule`, `firesOnUpdate`), `SuspendReason`; `TriggerEntity` gained `kind()/type()/schema()/emailType()/viewPrivilege()/noun()/listPath()` (switches, no init-order risk); `RuleDefinition`; `RuleDtos` (§6.14 `RuleRequest`, `TriggerDto`, `RuleDto`, `RuleSummary`, `SummaryDto`, `ActRequest`, `PreviewRequest`, `PreviewDto`, `SampleDto`, `RecordPreviewDto`, `MatchDto`, and `RuleSnapshot` = the revision's `snapshot_json`); `RequiredPrivileges` (AUTOMATION_MANAGE + entity VIEW + each action's privilege; `regional(...)`); `RuleValidator` (every §6.14 field rule, condition parse + limits + a dry-run count that coerces every value at save, actions through their handlers, "not an update" warnings for UPDATED rules on `overdue`/`overdueAmount`/relative dates/POC names, saver must hold `RequiredPrivileges` (400 `actions`) and every listed region must be one where they hold its regional part (400 `regionIds`); `requireRights(definition)` for enable/take-over; `validateForPreview`); `RuleVisibility` (owner ∨ AUTOMATION_ADMIN ∨ effective regions meet the caller's grants — restricted rule: its regions; unrestricted: any grant of its owner; `regionIds` narrows sets for everyone, an administrator's as asked; a Criteria predicate for the live list and an in-memory form for revisions and by-id reads); `RuleAsOfLists` (latest non-deleted revision per rule at the point, visibility on the snapshot's regions with today's grants, filters `name:contains`, `enabled:eq`, `entityType:eq/in`, `triggerKind:eq/in`, sorts `name`/`id`, anything else 400 "This filter isn't available for past dates"; `revisionAt(id, p)`); `RuleService` (page, summary with `failedRuns24h` from `automation_runs` over the visible rules (at T: runs failed in (T−24h, T]), `get` (visible, deleted too — for the audit trail), `detail` (live, or the revision at T with flags false), `definition` (current, or the revision at T), create/update/enable/disable/delete, each with a revision row and audit `RULE_CREATED/UPDATED/ENABLED/DISABLED/DELETED/OWNERSHIP_TAKEN`; ownership per §5.10.7: the owner needs AUTOMATION_MANAGE, anyone else AUTOMATION_ADMIN (403) plus `confirmOwnershipChange` (400) and then owns it; disable/delete by the owner or an administrator without taking over; `version` → 409; soft delete; `RuleDto` flags `canEdit`/`editTakesOwnership`/`canRun` (false as of a date), `listLink` for AND-only trees, Gmail/delivery warnings for emails from the owner); `RulePreviewService` (POST body, GET saved rule or revision at T, GET matches; read-only transaction always rolled back; `evaluateAs=OWNER` for the owner or an administrator (403 otherwise) through `RunAs.call(principalFor(owner), ViewScope(rule regions ∩ asked, point), system)`, samples the caller cannot see come back `{"hidden":true}` and `record` null; `alreadyDone` from DONE effects' ONCE_PER_RECORD keys; historical: `asOf`, `asOfNote`, `record.actions = null`); `RuleController` (`/api/automation/rules`: GET list/summary/{id}, POST, PUT, POST enable/disable, DELETE 204, POST preview (method `@AsOfAware`), GET {id}/preview, GET {id}/matches).
- **`B/automation/action`**: models `RuleAction` (sealed) with `CreateTaskAction`, `CreatePromiseAction`, `CreateDisputeAction`, `SendEmailAction`, `Repeat`, `DueSpec`, `AmountSpec`, `ActionType` (privilege per type); `RuleActions` (codec: parse with per-action paths, key assigned when missing / refused when bad or duplicate, `tree`/`write`/`read`); `ActionHandler` (`normalize`, `prepare`, `preview`) + `ActionHandlers`; `ActionSupport`; the four handlers' validate/prepare/preview halves (task: rendered title/description, due date clamped ≥ today, assignees probed, open-task guard; promise: FIXED/FIELD amount (≤ 0 → "Nothing owed"), date, Collection POC (customer default, person, role; none → "No Collection POC"), notes, link, co-assignees probed, open-promise guard; dispute: reason, proposal (none/cancel on INVOICE/void on PAYMENT), allowed roles per §5.9.1, assignees probed; email: from/to validated as the email code does, subject one line fitted to 500, body fitted to 20000, preview through `EmailService.previewRendered`); each `prepare` reports lacking the action privilege in the record's region as a problem; `RecordContext` + `RecordContexts.load` (scoped get as whoever is signed in, `EmailTargets.load`, placeholder values; `label`/`idOf`); `Prepared`, `ActionPreview`.
- **`B/automation/placeholder`**: `PlaceholderCatalogue` (the §5.10.4 keys, kinds and levels; `offered(entity)`, `find`, `catalogue(entityType)` for CUSTOMER/INVOICE/PAYMENT and — Customer.* and Today — PROMISE/DISPUTE), `PlaceholderValues` (lazy, memoised, live), `PlaceholderRenderer` (the grammar, one pass with `quoteReplacement`, fallbacks, "X is empty" warnings, POC keys blank with a warning without POC_VIEW, one-line option, fit with "…" and a warning; `validate` at save: unknown key or a key not offered on the entity; `UnknownPlaceholderException`), `Rendered`, `PlaceholderController` (`GET /api/automation/placeholders`, `@AsOfIgnored`, AUTOMATION_VIEW, TASK_MANAGE or EMAIL_SEND).
- **`B/automation/eval`**: `RuleEvaluator` (`matches`, `matchingPage(afterId, pageSize, Restriction NONE|ids|customer)`, `count`, `sample`, `page`; scope = `ScopeResolver.scope(kind, VIEW, SELECTED)` for the signed-in principal and bound ViewScope, plus the condition; a `BadRequestException` from building becomes `RuleDefinitionException`), `RuleDefinitionException` (a `BadRequestException`).
- **`B/automation/run` (data model only)**: `AutomationRun`, `AutomationEffect` (every §3.6 column, `uk_automation_run`, `uk_automation_effect_dedupe`, indexes; `AutomationEffect.oncePerRecordKey`), `RunStatus`, `EffectStatus`, repositories (`countFailed`, `findDoneKeys`).
- **Elsewhere**: `EmailText` public; `EmailService.previewRendered(...)` + private `resolveForAutomation(...)` (§5.10.5 steps 1–6 as the signed-in person, never throwing for a not-queued condition: EMAIL_SEND in the region, content, USER recipients and sender through `VisibilityProbe` (dropped with "X cannot see this record, so was not emailed"; sender → owner fallback or "…so cannot be the sender"), a role sender nobody holds (fallback or problem), resolved role senders and internal recipients probed, TO count limit; plus the Gmail/delivery warnings); `AuditController` supports `RULE` (AUTOMATION_VIEW, read through `ruleService.get`, a deleted rule's trail stays readable); `UserController.pocReferenceCount` counts rules owned; `UserRepository.findActiveStaffHolding(privilege)`; `AssigneeController` gates gain AUTOMATION_MANAGE (`/context`) and AUTOMATION_VIEW (`/people`) (BE-12 hand-off).
- **Tests**: new `T/automation/AutomationTestBase` (roles, grants, rule bodies as Maps, a seller and a collector); `T/common/query/ConditionTest` (9), `T/automation/placeholder/PlaceholderTest` (8), `T/automation/rule/RuleApiTest` (12), `T/automation/PreviewTest` (7) — every §8.5 case for the four. Extended: `IntegrationTestBase` (reset: effects, runs, events, revisions, rules), `ViewScopeInventoryTest` (12 endpoints classified; pins), `RegionParamCoverageTest` (+5 rows: rules list, summary, POST preview, GET preview, matches; `PENDING_ROWS` empty), `AsOfEndpointCoverageTest` (+6 probes: list/summary/detail INTERVAL from revisions, the three previews VERSIONED), `AsOfReadOnlyTest` (automation tables in the unchanged set), `PrivilegeClassificationTest` (13 global), `PrincipalRightsTest` (see deviations).

**Deviations from DESIGN.md (with reasons)**
- **`AutomationRun`, `AutomationEffect`, `RunStatus`, `EffectStatus` and their repositories are created here, as data model only** (BE-17 lists them). `automation_rules.last_run_status` is a `RunStatus` column of this slice's entity, `/rules/summary.failedRuns24h` reads `automation_runs` and preview's `alreadyDone` reads `automation_effects`; both are in BE-16's acceptance. Processing, `AutomationRun.finish`/`AutomationEffect.of` factories and the worker stay BE-17's.
- **Enable, disable and delete are implemented here, enable already taking ownership** (BE-17's list says "ownership-taking enable/resume/run"). Enabling makes a rule act, so shipping it without the SC-2 rule would be a hole; it reuses the PUT's take-over. Resume and run now remain BE-17's.
- **`EmailService.previewRendered` takes a trailing `int maxRecipients`** (step 6 needs the limit; EmailService does not depend on the automation config). The handler passes `props.maxEmailRecipients`.
- **Preview validates what the rule says exactly as a save does, but not the name's uniqueness nor whether the previewer could save it** (`RuleValidator.validateForPreview`): a viewer holding only AUTOMATION_VIEW may preview, and a draft's name may still clash.
- **`evaluatedAs` is `OWNER` when the owner asks for their own view** (it is evaluated as them, i.e. as the caller); only someone else's OWNER preview runs through `RunAs`.
- **The three preview endpoints are in `SETS`** (§8.2 "rule preview"), with RegionParamCoverage rows; the rule detail is `BY_ID`, placeholders `GLOBAL_EXEMPT`.
- **`/rules/summary` accepts the list's filters** (like every other summary) and, as of a date, only those `RuleAsOfLists` supports.
- **`PrincipalRightsTest.aUserCreatedWithoutGrantsHoldsNoRegionUntilOneIsGiven` (BE-02/05 file) now expects `AUTOMATION_MANAGE, AUTOMATION_VIEW` too**: §4.1 seeds them, global, to SALES_POC, so a no-grant SALES_POC holds them. The assertion stays exact.
- `RuleDto.lastRun` is read from `automation_runs` when `last_run_id` is set (null until BE-17 writes runs); `nextRunAt` stays null until BE-18's `ScheduleCalculator`.
- `listLink` for a rule with no condition is the plain list path.
- The placeholder endpoint is its own `PlaceholderController` (`@AsOfIgnored`) rather than a method of `RuleController` (which is `@AsOfAware`).
- `AssigneeController` (not in the list) gains the automation privileges, as the BE-12 carry-forward asked.

**API-contract changes versus §6:** none. Delivered per §6.14: `GET /rules` (+`status`/`regionIds` filters, historical from revisions), `/rules/summary`, `/rules/{id}`, `POST/PUT /rules`, `/enable`, `/disable`, `DELETE`, `POST /rules/preview`, `GET /rules/{id}/preview`, `GET /rules/{id}/matches`, `GET /placeholders`; §6.15 `RULE` audit and `RULE_*` actions; §6.16 `automation-rules`, `automation-runs`; §6.5/§5.10.2 `paymentTerm` and `overdueAmount` columns.

**Known gaps / deferred**
- BE-17: resume, run now, runs/effects endpoints and `RunViews`, the consumer, `CreateTaskHandler.perform`, suspension (`suspendOwnedBy`, `recheckOwner*` — `UserRepository.findActiveStaffHolding` is ready), and **`TaskDto.rule.name`** (still null: `TaskService` is not in this slice's list).
- BE-18: `next_run_at`, schedules, `EmailService.queue` (sharing `resolveForAutomation`), placeholders in manual emails.
- BE-19: promise/dispute `perform`, internal disputes.
- Slice size ≈ 5,400 changed lines (≈ 3,400 main, ≈ 2,000 tests), above the 2,500 guide; everything is within BE-16's list plus the data-model deviation above.

**Tests:** backend 652 run, 0 failures, 0 errors, 0 skipped (611 before + 41 new: ConditionTest 9, PlaceholderTest 8, RuleApiTest 12, PreviewTest 7, RegionParamCoverageTest +5 (now 53)). mail-service 24 run, 0 failures, 0 errors. Log: build/be16-full.log.

### BE-16 review fixes

All five findings were checked against the code and found valid. All are fixed, except one part of finding 2, which is rejected.

1. **[medium] The owner's-view preview showed POC names using the owner's POC_VIEW.** `RecordContexts.load` gained an overload taking `pocVisible`. `PlaceholderValues.canSeePoc` is now `pocVisible && scopeResolver.canSeePoc()`, and the old signature passes `true`. `RulePreviewService.evaluate` reads the caller's `canSeePoc()` before any `RunAs` and passes it into `actions(...)`. So in OWNER mode, POC keys render only when both the owner and the caller may see POCs (PC-7, and §5.10.4 "for the owner or preview caller").
   `CreatePromiseHandler.preview` now also drops `collectionPoc` and `collectionPocUserId` from `rendered` when POC placeholders are hidden, with the warning "The Collection POC is left out: POC details are not visible here". `prepare`, which BE-19's perform will use, still resolves the POC.
   Test: `PreviewTest.theOwnersViewLeavesOutPocDetailsTheCallerMayNotSee`. Olga (POC_VIEW) sees "Chase COLM.COLLECTOR" and the promise's POC. Adam (AUTOMATION_ADMIN, no POC_VIEW), previewing with evaluateAs=OWNER, gets "Chase", the "left out" warning, and no POC keys. Mutation-checked: removing `pocVisible &&` makes the test fail.
2. **[low] A PUT that left out `enabled` or `maxRecordsPerRun`.**
   - **Fixed:** `RuleService.update` validates `keepingUnsent(req, rule)`. A null `enabled` keeps `rule.isEnabled()`, and a null `maxRecordsPerRun` keeps the stored limit. Create keeps its defaults (true and 1000). Test: `RuleApiTest.aChangeThatLeavesOutEnabledOrTheLimitKeepsThem`.
   - **Rejected: making `version` required.** An optional `version` is the house convention. §5.6.8 step 6 says "`req.version != null && != customer.version` → 409", and `InvoiceService.update`, `TaskService` and `CustomerRegionMoveService` check it the same way. §6.14 does not say the field is required. The existing `aStaleVersionIsAConflict` still pins the 409 when a version is sent.
3. **[low] CREATE_PROMISE skipped the assignability check on a named Collection POC.** For a USER token, `prepare` now adds a problem when the person is not internal or lacks `POC_ASSIGNABLE_COLLECTION` ("X is not assignable as Collection POC"). It also adds one when `RegionAccessQueries.holds(user, POC_ASSIGNABLE_COLLECTION, record region)` fails ("… in the record's region"). These are `PocService.requireAssignable`'s checks.
   Deviation: the checks are asked without throwing, not by calling `pocService.requireAssignable` and catching. That method is a proxied `@Transactional(readOnly = true)`, so an exception thrown out of it would mark the surrounding transaction rollback-only. That would poison BE-19's perform transaction even if caught.
   Test: `PreviewTest.aNamedCollectionPocMustBeAssignableWhereTheRecordIs`. Wes, SOUTH only, gets a problem on a NORTH invoice and none on a SOUTH one.
4. **[low] The owner's-view matches page returned the owner's scope.** `matches` now builds the page's `ScopeResolver.Scope` as the caller, under the same ViewScope narrowed to the rule's regions (`as(false, …)`), outside the RunAs. `Evaluated` lost its `scope` component. Test: an extra assertion in `theOwnersViewCountsAsTheOwnerButShowsOnlyWhatTheCallerSees` (`regionIds == [NORTH]` for Adam, not Olga's NORTH+SOUTH).
5. **[low] Matches pages stopped at 5,000 records.** Added `TableQueryExecutor.idsPage(type, schema, query, scope)`. It is the id query read at the query's page offset through the existing `page(tq, query)` helper (`setFirstResult` plus `setMaxResults`, with its int-overflow guard). `RuleEvaluator.page` uses it, so any page of any size of set is read directly, and `totalElements` stays the true count. `sample` still reads page 0. Test: `PreviewTest.aLaterPageOfMatchesIsReadAtItsOffset` (11 matches: page 0 has 10, page 1 has exactly the oldest).

Final counts: backend 656 tests, 0 failures, 0 errors, 0 skipped (652 + 4 new: PreviewTest +3 = 10, RuleApiTest +1 = 13). mail-service 24, 0 failures, 0 errors. Logs: build/be16r-full.log, build/be16r-mail.log.

## BE-17 — Automation: consumer core, event points, CREATE_TASK, run now

**Built**
- **`B/automation/run`** (the consumer):
  - `AutomationWorker.drain(now)`: refuses to run inside a transaction; recovers expired leases (no attempt counted); claims due events one at a time with `claims + 1` as the fencing token and a lease of `lease-seconds`; `token > max-claims` → DEAD "Processing never finished after 20 claims"; processes; finishes fenced by token (DONE / RETRY with backoff 30 s, 2 m, 10 m, 1 h / DEAD after `max-attempts` with the event's unfinished runs FAILED "Gave up after N attempts: …", the rule's `last_run_*` and one `AUTOMATION_RUN_FAILED` notice to each owner, in the same transaction). An exception is classified concurrency first → RETRY, then database unavailable → a best-effort fenced release (PENDING, `next_attempt_at = now + 30 s`, no attempt) and the drain stops; an `Error` propagates and lease expiry recovers the event. A fenced update of 0 rows logs "lost the lease". It loops until nothing more is due, so a customer's fan-out is processed in the same drain. `@PreDestroy` stops it between events. Node id: `app.automation.node-id`, else `$HOSTNAME`/`$COMPUTERNAME` + 8 hex (no resolver call at startup), fitted to 80.
  - `EventProcessor.process(id, token)` → `Outcome {DONE, RETRY, YIELD, LOST, FINISHED}`. CREATED/UPDATED: candidate rules (`findEntityCandidates`: on, standing, not suspended, trigger kinds, `active_since <= occurred_at`), minus the rule that caused the event, none past depth 3; each rule run isolated (a non-infrastructure exception makes the event RETRY; infrastructure is rethrown); the worst outcome wins. CHILDREN without a rule: one per-rule CHILDREN event per INVOICE/PAYMENT UPDATED rule, inserted in the **same transaction as the fenced DONE of the parent** (redelivery can never fan out twice). Events with a rule (per-rule CHILDREN, MANUAL, and SCHEDULED once BE-18 writes them) → `runForSet`.
  - `RuleRunner`: `runForRecord(rule, ev, point)` (terminal run → DONE; the owner session; the condition under `AsOf.with(point)`; no match → a pre-existing unfinished run SKIPPED "Did not match"; `ensure` in its own REQUIRES_NEW with the unique-key violation caught outside and re-read; actions; settle), `runForSet(rule, ev, token)` (pre-created QUEUED run or one ensured for CHILDREN; deleted/suspended, or disabled except MANUAL → SKIPPED "Rule is not active"; RUNNING with `rule_version` recorded; keyset pages of `page-size`, **each page in its own owner session**, at the event's point, restricted to MANUAL `record_ids` or the CHILDREN customer; `max_records_per_run` → `truncated` + "Stopped after N records, the rule's limit"; progress and a fenced lease heartbeat per page (0 rows → LOST); the rule re-read between pages), `gaveUp`. Counters are always recounted from the run's effects, so a redelivery never double-counts. Settle: FAILED when every effect failed, PARTIAL when some did, SUCCEEDED otherwise; RUNNING + RETRY while a transient failure is left; `last_run_*` by JPQL (no version bump). `OwnerUnavailableException` → the rule suspended with its reason, the run SKIPPED; `RuleDefinitionException` (or unreadable stored JSON) → suspended INVALID_DEFINITION, run FAILED.
  - `ActionExecutor.applyAll`: one REQUIRES_NEW transaction per (action, record): skip what this run already settled; record context read as the owner (NotFound → `ActionSkippedException` "The record no longer exists", which rolls the transaction back); dedupe check; `prepare`; problems (e.g. no TASK_MANAGE in the record's region) → PERMANENT before any lock; customer `findByIdForUpdate` + `faults.afterLock`; dedupe re-checked; `faults.beforeAction`; `perform`; the effect row (DONE with the unique `dedupe_key`, or SKIPPED) in the same transaction; `faults.afterActionCommitted`. Failures by `FailureClassifier`: DUPLICATE and SKIP → a SKIPPED effect in a new transaction, PERMANENT → FAILED effect, DEFINITION → `RuleDefinitionException`, TRANSIENT → left for the event's retry (no row), INFRA → rethrown. A once-per-record action already done by another run is recorded as SKIPPED "Already done for this record".
  - `FailureClassifier` (§5.10.6 table in order), `OwnerSession` (`call(rule, ev, runId, work)`: `principalFor(owner)`; missing/inactive → OWNER_INACTIVE, customer login → OWNER_NOT_STAFF, missing `RequiredPrivileges` (on the union authorities) → OWNER_LACKS_RIGHTS naming them; `ViewScope(rule regions ∩ MANUAL region_ids, live)`; `RunAs.call(..., WriteOrigin.automation(rule, run, event, depth + 1))`; `forRun(runId, work)` re-binds the origin with the run once it exists; `require(definition)` shared with `RuleService`), `OwnerUnavailableException`, `AutomationFaults` + `NoAutomationFaults`, `AutomationScheduler.poll` (fixed delay `poll-interval-ms`, only when `consumer-enabled`, failures logged), `RunController`, `RunViews`.
  - Data model (from BE-16) extended: `RunStatus.isTerminal`, `AutomationRun.of(...)`/`finish(...)` (fitted message), `AutomationEffect.of(...)` (every text fitted, warnings one per line, key only on DONE) / `warningList()`; repositories: runs `findByRuleIdAndEventId`, `findByEventIdOrderByIdAsc`, `findUnfinishedByEventId`, `setProgress`; effects `existsByRunIdAndActionKeyAndTargetTypeAndTargetId`, `existsByDedupeKey`, `countByStatus`, `findByRunId(run, until, page)`, `targetsOf`.
- **`B/automation/event/EventPoints.pointOf(ev)`**: CREATED/UPDATED/CHILDREN → the `history_transactions` instant of the event's `history_tx_id` (none → null, evaluated live, WARN, run message "Evaluated on current data: the moment of the change was not recorded"); SCHEDULED/MANUAL → `scheduled_for`.
- **`AutomationEventRepository`** (the BE-11 hand-off): `findDueIds`, `findExpiredLeaseIds`, `recover`, `claim`, `claimsOf`, `finishDone`, `finishRetry`, `finishDead`, `release`, `heartbeat` (all conditional JPQL updates fenced by `claims`), `requeue`, `findQueuedManual`, `countEntityEvents`. Purge stays BE-18's.
- **`AutomationRuleRepository`**: `findEntityCandidates`, `suspend` (JPQL, `where suspendedReason is null`, clears `next_run_at`, no version bump), `recordLastRun`, `findActiveIdsOwnedBy`, `findOwnersWithRole`.
- **Actions**: `ActionHandler.perform(a, rc, prepared)` (default: `UnsupportedOperationException` "… cannot be carried out yet" → PERMANENT, until BE-18/19 override it for email, promise and dispute) and `locksCustomer()` (default true); `ActionHandlers.perform/locksCustomer`; `ActionResult` (sealed `Done(resultType, resultId, warnings)` / `Skipped(message)`); `ActionSkippedException`; `Prepared` gains a handler-private `plan` (old 4-argument constructor kept). **`CreateTaskHandler`**: `prepare` renders, dates, probes USER assignees (dropped with "X cannot see this record, so was not assigned"), validates the kept tokens without a transactional call, and asks `TaskService.planForAutomation` whom they would tell — all before the lock; `perform` re-checks the `onlyIfNoOpenTask` guard under the lock (→ `Skipped("An open task from this rule is already on this record")`) and calls `createForAutomation(draft, plan)` with the run id from the bound origin → `Done(TASK, id, warnings)`. The guard warning of the preview moved from `prepare` to `preview` (the preview's output is unchanged).
- **`TaskService`**: `AutomationPlan`, `planForAutomation(target, record, tokens)` (read-only: recipients), `createForAutomation(TaskDraft, AutomationPlan)` (no record load, validation or recipient work under the lock); the old `createForAutomation(TaskDraft)` is kept. `TaskDto.rule.name` is now filled (the BE-16 gap). `TaskTargets.of(...)` builds a target from an already-read record.
- **`RuleService`**: `suspend(id, reason, detail)` (JPQL; revision; audit `RULE_SUSPENDED` with user null; `AUTOMATION_RULE_SUSPENDED` to the owner if active and every active AUTOMATION_ADMIN holder, link `/automation/{id}`), `suspendOwnedBy`, `recheckOwner` (the consumer's owner guard, suspending with its reason), `recheckOwnersOfRole`, `resume(id, replay, confirm)` (owner or a confirming AUTOMATION_ADMIN who takes it over; re-validated exactly as a save, as the caller; the owner guard → 400 with its message; suspension cleared; `active_since = now`; audit `RULE_RESUMED` {replay: false, skippedWhileSuspended}; returns `ResumedDto` = `RuleDto` + `skippedWhileSuspended`, the count of the rule's kinds of entity events (plus parent CHILDREN for invoice/payment UPDATED rules) in `[suspended_at, now)`), `runNow(id, RunRequest)` (rule locked first; owner, or AUTOMATION_ADMIN taking over (400 without confirmation, 403 for others); suspended → 400 "The rule is suspended; resume it first"; may be disabled; `recordIds` ≤ 50 positive, kept exactly; `runAt` later than now and ≤ 90 days; the request's `regionIds` stored; identical PENDING/PROCESSING MANUAL request → 200 with its run; otherwise MANUAL event (`next_attempt_at = runAt`) + QUEUED run + audit `RULE_RUN_REQUESTED` → 202), `retry(runId, confirm)` (FAILED run whose event is DEAD; MANUAL takes ownership, others owner or admin; event requeued as new and its FAILED runs QUEUED again), `requeue(eventId)`.
- **Endpoints** (§6.14): `POST /api/automation/rules/{id}/resume` (RuleController), and in `RunController` (`/api/automation`, class `@AsOfAware`): `POST /rules/{id}/run` (202/200 `{runId, eventId, status, scheduledFor}`), `GET /rules/{id}/runs` (schema `automation-runs`), `GET /runs/{id}`, `GET /runs/{id}/effects?page&size`, `POST /runs/{id}/retry` (202), `POST /events/{id}/requeue` (AUTOMATION_ADMIN, 202 `{eventId, status}`).
- **`RunViews`** (§5.10.10): owner and AUTOMATION_ADMIN see runs and effects in full; any other viewer of a visible rule gets `partial: true` counters counted over the effects whose target is in their `scope(kind, VIEW, GRANTED)` (at the point, through the executor), a hidden run `entity` `{type, hidden: true}` with `message` null, and hidden effects `{id, actionKey, actionType, target: {type, hidden: true}, status, createdAt}` with `result`, `message`, `warnings` null. As of a date: runs and effects with `created_at <= p` (a later run is 404), `statusAt` from `started_at`/`finished_at`. `lateBySeconds` = start − `scheduled_for`. Result links `/tasks|/promises|/disputes|/approvals|/emails/{id}`.
- **Hooks**: `UserController.update` (deactivation → `suspendOwnedBy(OWNER_INACTIVE)`; role change → `recheckOwner`), bulk DEACTIVATE and delete-falls-back-to-deactivate → `suspendOwnedBy`; `RoleController.update` → `recheckOwnersOfRole`; `RegionGrantService.replace` → `recheckOwner` (through an `ObjectProvider<RuleService>`: the rules reach the domain services that region code serves).
- **Config**: `application.yml` §5.15 automation block (every key), scheduling pool 5 → 6, prod Hikari 5 → 8; `application-test.yml` `poll-interval-ms`/`tick-interval-ms` 3600000.
- **Tests (new)**: `T/automation/RecordingAutomationFaults` (fail before the action or after the lock, crash after commit (an `Error`), run code inside the lock window, count the statements of each lock window through Hibernate statistics), `T/automation/run/WorkerDurabilityTest` (15), `TriggerMatrixTest` (13: the 9-case matrix + credit-applied create, MANUAL never fires, region-move CHILDREN once per matching invoice, customer OUTSTANDING crossing), `FailureClassifierTest` (7, plain), `RunAsOwnerTest` (9), `RunViewsTest` (3), `RunNowTest` (10), `T/automation/action/TaskActionTest` (6; the lock window of a CREATE_TASK with two assignees is asserted ≤ 30 statements).
- **Tests (extended)**: `IntegrationTestBase` (the `@Primary RecordingAutomationFaults` bean, reset with the other faults), `AutomationTestBase` (`drain()` after `clearContext()`, `runsOf`, `tasksOf`, `events`, `event`, `everyTime`), `ViewScopeInventoryTest` (7 endpoints classified: runs/run/effects BY_ID AWARE, resume/run/retry/requeue WRITES; pins), `AsOfEndpointCoverageTest` (3 INTERVAL probes: runs list, run with a later-run witness, effects), `AsOfReadOnlyTest` (`automation_events` in the unchanged tables).

**Deviations from DESIGN.md (with reasons)**
- **Requeue and retry are built here, not in BE-18.** §8.5 puts "requeue and retry endpoints" in BE-17's `WorkerDurabilityTest`, while §9.1 lists them under BE-18's `RunController` change; the acceptance test decides. `/health` stays BE-18's. Requeue is classified WRITES (not GLOBAL_EXEMPT).
- **`resume` with `replay: true` is a 400 `fieldErrors.replay`** ("… not available yet") until BE-18 adds REPLAY, rather than silently resuming without replay.
- **The CHILDREN fan-out is written in the transaction of the parent's fenced DONE** (outcome `FINISHED`), not returned as DONE for the worker to finish: otherwise a lost finish would fan out twice and fire twice.
- **Set runs restart from their first record on a retry** (no cursor until BE-18) and recount `matched`; all run counters are recounted from effects at every finish.
- **SKIPPED effects are written for DUPLICATE and for "already done by another run"** so the counters, which come from effects, say what happened (§5.10.6 only says `skipped++`).
- **`ActionHandler.perform` defaults to a PERMANENT refusal** for promise, dispute and email actions until BE-18/19; `UnsupportedOperationException` is listed as PERMANENT in the classifier. `EmailService.NotQueued` and `DisputeService.AlreadyOpenException` do not exist yet; BE-18/19 add them to the classifier's SKIP/PERMANENT rows with their classes.
- **Problems from `prepare` are a PERMANENT failure taken before the lock**, in the executor, so a record in a region the owner only reads never locks its customer.
- **`TaskService` gained a plan overload** rather than changing `createForAutomation(TaskDraft)`: the record, the assignee check and the recipients move before the lock (PC-4, the ≤ 30-statement window); the old method is kept for its callers.
- **Run DTOs are nested in `RunViews`**, resume/run request DTOs in `RuleDtos`; `ResumedDto` unwraps the `RuleDto`, as §6.14's "RuleDto plus skippedWhileSuspended".
- **Rule definitions are read inside a transaction by the runner** (the rules the candidate query returns are detached and their `regionIds` are lazy).
- **`RunNowTest`'s "after running the rule's CREATE_DISPUTE they cannot approve the resulting change"** is left for BE-19, which makes CREATE_DISPUTE act; here the test proves the take-over (403 / 400 / 202, `RULE_OWNERSHIP_TAKEN`) and that the run then acts as the new owner.
- **Redaction counters are counted in memory** from the run's effect targets and one visibility query per 1,000 targets, not one grouped SQL query.

**API-contract changes versus §6:** none. Additive: `QueuedRunDto` also carries `scheduledFor` on retry; requeue answers `{eventId, status: "PENDING"}`.

**Known gaps / deferred**
- BE-18: schedules and the ticker, SEND_EMAIL and dispatch after commit, slicing/YIELD/cursor, supersede, rate-limit breaker, REPLAY and `resume(replay)`, `/health`, purge of events and `history_transactions`, `next_run_at`.
- BE-19: CREATE_PROMISE/CREATE_DISPUTE `perform`, the own-rule approval check (and that part of `RunNowTest`).

**Tests:** backend 719 run, 0 failures, 0 errors, 0 skipped (656 before + 63 new: WorkerDurabilityTest 15, TriggerMatrixTest 13, FailureClassifierTest 7, RunAsOwnerTest 9, RunViewsTest 3, RunNowTest 10, TaskActionTest 6). mail-service 24 run, 0 failures, 0 errors. Log: build/be17-full.log.

### BE-17 review fixes

All seven findings were checked against the code. Six were valid and are fixed. The seventh is a deferral that was already logged, so it stays open for BE-19.

- **RunViews `matched` leaked to partial viewers (medium, fixed).** `viewersCounters` now also returns `matched`, counted as the distinct visible targets among the run's effects. `dto()` uses that value for non-full viewers. RunViewsTest now expects 2 for the SOUTH-only viewer and 3 for the owner.
- **Drain lease and loop (medium, fixed).** Each claim takes its `now` and lease from `clock.instant()` at the moment of the claim, never earlier than the drain's `now`. Every batch recovers expired leases first. The loop stops after half a lease of wall time (`System.nanoTime`) and leaves the rest to the next poll. Fan-out still drains within the same call. New test WorkerDurabilityTest.everyClaimTakesItsLeaseFromTheClockAtThatMomentNotFromTheStartOfTheDrain: the first event's action moves the clock on 10 minutes, and the second event's lease must be the new clock plus 300 s.
- **Requeue reset the fencing token (medium, fixed).** New nullable column `automation_events.claims_base`. It is nullable so that OutboxSchemaTest's list of required columns and the "nothing can refuse a row" rule still hold. `requeue` keeps `claims` and sets `claimsBase = claims`. The claim limit now uses `claimsSinceRequeue` (`claims - coalesce(claims_base, 0)`). New test WorkerDurabilityTest.aRequeuedEventGetsATokenNoEarlierWorkerHoldsAndCountsItsClaimsAfresh: after DEAD by claims and a requeue, tokens 1 and 21 are fenced out of finishDone, heartbeat and finishRetry, and the event then processes to DONE.
- **Per-rule CHILDREN ignored active_since (low, fixed).** `RuleRunner.active(rule, ev, manual)` refuses a CHILDREN event whose `occurredAt` is before `rule.activeSince`. It is checked when the run starts and each time the rule is re-read between pages. New test TriggerMatrixTest.aCustomersChangeStillWaitingWhenTheRuleWasSwitchedOffAndOnIsNotActedOn: the per-rule event is in backoff, the rule is disabled and enabled again, and the next drain marks the run SKIPPED "Rule is not active" with no task.
- **Run progress and finish not fenced (low, fixed).** A new `RuleRunner.hold(status, ev, token)` heartbeats the event first in each transaction that writes the run. That locks the event row until commit. If the heartbeat changes 0 rows, it calls setRollbackOnly and the method returns LOST. It is used for the pre-loop reset, per-page `setProgress`, `settle`, `finish` and `definitionFailed`. `runForRecord` now takes the token: `EventProcessor.forRecord` passes it through.
- **Runs list filtered on unredacted columns (low, fixed).** For non-full viewers, `RunViews.runs` returns 400 for a filter or sort on `entityId`, `matched`, `done`, `skipped` or `failed` (`REDACTED_COLUMNS`). RunViewsTest asserts the 400s for the partial viewer, that `status` still filters, and that the owner can still filter on `matched`.
- **RunNowTest CREATE_DISPUTE / SC-2 assertion (low, not changed).** This is still deferred. CREATE_DISPUTE perform arrives in BE-19, which must add the assertion to RunNowTest or DisputeActionTest.

Final counts: backend 722 run, 0 failures, 0 errors, 0 skipped (719 + 3 new tests). mail-service 24 run, 0 failures, 0 errors.

## BE-18 — Automation: schedules, SEND_EMAIL, manual placeholders, slicing, replay, breaker, health

**Built**
- **Schedules (§5.10.8).**
  - New `B/automation/schedule/ScheduleCalculator`. It is pure and has a `Spec(kind, time, day, zone)` built from a rule.
    - `nextAfter` and `latestAtOrBefore` work day by day in the rule's zone through `ZonedDateTime.of`, so a time in a DST gap moves forward by the gap and a time that happens twice takes the earlier offset.
    - `missedSlots(spec, fromExclusive, toInclusive, max)` returns `Missed(slots, dropped, firstDropped, lastDropped)`: the latest `max` slots, oldest first, plus what was left out.
  - New `ScheduleTicker.tick(now)`. It pages through `findDueSchedules` 100 rules at a time. Each rule is handled in its own transaction:
    - The slots are worked out: `ONE` gives the latest slot, `ALL` gives the missed slots from `max(lastScheduledFor, nextRunAt − 1ns)`, capped at `max-catch-up-slots`.
    - A JPQL `advanceSchedule(id, expected, next, lastSlot)` moves the rule on, conditional on `nextRunAt = expected`, with no version bump. Only the tick that moves it writes the SCHEDULED events (`scheduled_for` = slot, `occurred_at` = now, origin SYSTEM) and QUEUED runs.
    - The first run's message says what the catch-up did: "Caught up once for N missed slots", or "K earlier slots were not run (d1 to d2): at most M missed slots are caught up".
    - Then it purges, in chunks of 1000 ids (at most 100 chunks per tick): DONE events with `processed_at` older than the retention, and `history_transactions` rows with `instant` older than it. DEAD events are kept.
  - `AutomationScheduler.tick()` is a fixed-delay job on `tick-interval-ms`. It runs even when the consumer is off, so no slot is lost; its events wait.
  - `RuleService` keeps `next_run_at`:
    - set on create, and on enable and resume;
    - on update it is kept when the schedule is unchanged and the rule was already on, so an edit does not skip a slot that is due; otherwise it is recomputed;
    - cleared on disable, delete and suspend (already done), and for non-schedule triggers.
  - `RuleDto.nextRunAt` and `RuleSummary.nextRunAt` are now filled.
  - `AutomationRun.finish` keeps a message the run was queued with when it finishes with none.
- **SEND_EMAIL (§5.10.5, R15, V5, PC-2, SC-3).**
  - `EmailService.queue(type, id, from, fallbackToOwner, to, subject, body, batchId, maxRecipients)` is `@Transactional(MANDATORY)` and never dispatches. Its steps:
    - `loadForSend`, then `Content.check`, then the shared `resolveForAutomation`. That method gained an overload that takes the already-loaded `Target`, and a `noRecipients` flag meaning "only nobody to write to".
    - It saves an ordinary OUTBOUND email with `sentBy` = owner, `from` internal, and exactly the recipients left after the visibility drops. `batch_id` is `rule-{id}-run-{runId}`, fitted to the new `Email.BATCH_MAX` (40).
    - It returns `Queued(emailId, warnings)`. The warnings are the visibility and fallback ones plus delivery-not-configured / Gmail-not-connected.
    - `NotQueued(message, noRecipients)` extends `RuntimeException`.
  - `FailureClassifier` maps `NotQueued` with `noRecipients` to SKIP and without it to PERMANENT.
  - `SendEmailHandler.perform` queues the texts that `prepare` rendered and returns `Done(EMAIL, emailId, warnings)`. `locksCustomer()` is false.
  - `ActionExecutor.Result.emailIds` is now filled.
  - `RuleRunner` calls `EmailDispatcher.dispatchAll` after the action transactions commit, outside any transaction: per record run, and per page of a set run.
- **Placeholders in emails written by hand (§5.10.4, PC-10).**
  - `EmailService.send`, `preview` and `bulk` render placeholders for CUSTOMER, INVOICE and PAYMENT (their own fields plus `Customer.*`, `Today` and `Rule.Name`) and for PROMISE and DISPUTE (`Customer.*` and `Today`), as the caller. `bulk` renders per row, so each invoice gets its own `{{Invoice.Number}}`.
  - An unknown key, or one not offered for that kind of record, is a 400 on `fieldErrors.subject` / `fieldErrors.body`: "Unknown placeholder {{X}}" or "{{Invoice.Number}} is not available on customer emails".
  - The rendered text then goes through `Content.check`. In bulk, a row whose rendered text cannot be saved is ineligible.
  - `preview` adds the render warnings (for example "Customer.Phone is empty").
  - Text containing no `{{`, and emails about a product, user or role, are untouched. `retry` sends the stored text.
  - New helpers: `PlaceholderCatalogue.offered(EmailEntityType)`, `PlaceholderRenderer.mayHavePlaceholders` and `validate(text, offered, where, path, errors)`.
- **Slicing, cursor and supersede (§5.10.6).** `RuleRunner.runForSet` was reworked.
  - Every page is checkpointed. The run's `matched`/`truncated` and the event's `cursor_id` are written in one transaction fenced by the lease token (`AutomationEventRepository.checkpoint`).
  - An event with a cursor resumes after it and keeps `matched`. It is SUPERSEDED at once if the rule's version moved since the run started.
  - Between pages the rule is re-read:
    - inactive → PARTIAL/SKIPPED "Stopped: rule is not active";
    - version changed → SUPERSEDED "Stopped: the rule was changed while it ran";
    - `slice-budget-ms` used up (elapsed ≥ budget, measured with `System.nanoTime`) → `Outcome.yielded(cursor)`. The worker then runs `yieldAt(id, token, cursor, now)`: PENDING, `next_attempt_at = now`, and `claims_base = claims`, so slices do not count toward `max-claims` while the token keeps growing.
  - A page shorter than `page-size` is the last page, which saves an empty query.
  - A page with a transient failure is not checkpointed and the event returns RETRY straight away. The retry redoes that page, and only the unsettled actions run.
- **Hourly limit (breaker).**
  - `AutomationEffectRepository.countByRuleIdAndStatusAndCreatedAtAfter` counts DONE effects in the last hour. It is checked before each record in a set page (in-memory tally from one query per page) and before a record run.
  - Reaching `max-actions-per-rule-per-hour` suspends the rule RATE_LIMITED, with a revision, audit and notices through `RuleService.suspend`. The run ends PARTIAL/SKIPPED "Stopped: the rule reached its limit of actions an hour and was suspended".
- **Resume with replay (§5.10.7, PC-18).**
  - `RuleService.resume(replay=true)` works for entity-trigger rules. `active_since` stays at or before `suspended_at`. It inserts one REPLAY event (`scheduled_for` = `suspended_at`, `occurred_at` = now, actor = resumer). The audit row carries `replay`.
  - The runner's REPLAY path:
    - pages through `findReplayable`: DONE entity events of the rule's kinds, plus parent CHILDREN events for invoice and payment UPDATED rules, in `[scheduled_for, occurred_at)`, keyset by id with the cursor as the last replayed event;
    - applies the live candidate filters: depth ≤ 3, not caused by this rule, and a CHILDREN event only if it was not already fanned out to the rule (`fannedOutTo`);
    - runs `runForRecord(rule, past, lease-of-the-REPLAY-event, pointOf(past))`, or a per-customer set for CHILDREN, each at its own point, each run keyed by the replayed event with `trigger_type` REPLAY and `requested_by` the resumer. A second replay therefore repeats nothing.
  - The REPLAY run's message is "Replayed N changes made while the rule was suspended", plus a note when the window reaches past the retention.
  - Internally, `RuleRunner` separates the event whose lease is held (`Lease`) from the event a run is for.
- **Health (§6.14).**
  - `GET /api/automation/health` is on `RunController`, method-level `@AsOfIgnored`, AUTOMATION_ADMIN only. It is served by `RunViews.health()`: `{consumerEnabled, pending (first tries), processing, retrying (PENDING with attempts > 0), dead, oldestPendingSeconds (since the earliest due PENDING event became due), deadEvents[≤ 50, newest first]{id, kind, entityType, entityId, ruleId, attempts, lastError, updatedAt}}`.
  - It is classified GLOBAL_EXEMPT / IGNORED in `ViewScopeInventoryTest`.
- **Repositories.**
  - `AutomationRuleRepository`: `findDueSchedules`, `advanceSchedule`.
  - `AutomationEventRepository`: `checkpoint`, `yieldAt`, `findReplayable`, `fannedOutTo`, `findPurgeableIds`/`deleteByIds`, and health counts.
  - `HistoryTransactionRowRepository`: `findIdsBefore`/`deleteByIds`. This is the BE-11 carry-forward hand-off, now done.
- **Config.** `application.yml` scheduling pool 6 → 7, with a comment on `max-catch-up-slots`.
- **Tests (new, 37).**
  - `T/automation/schedule/ScheduleCalculatorTest` (6, plain): daily and weekly at +05:30; London spring-forward and fall-back; `latestAtOrBefore` on a boundary; `missedSlots` including the cap.
  - `ScheduleTickerTest` (9):
    - next slot on save, and a tick before it does nothing;
    - three missed days → one catch-up (ONE, with message), or three runs (ALL), capped with the older slots named;
    - two concurrent ticks → one event;
    - disabled and switched-off rules are not ticked, and re-enabled ones are due from then on;
    - no version bump;
    - weekly MONDAY 09:00 +05:30: Sunday → none, Monday before 09:00 IST → none, after → one event → drain creates the task;
    - the slot is evaluated as of `scheduled_for` (an invoice paid after the slot is still acted on, and the next slot sees it paid);
    - the purge removes old DONE events and old `history_transactions`, and keeps DEAD events and recent rows.
  - `T/automation/run/SetRunTest` (4):
    - a scheduled run with page 2 and budget 0 yields twice and resumes from the cursor (claims 3, matched 5, one run), with every page at the slot (a paid invoice is still acted on, one made later is not);
    - resume from a checkpoint;
    - a mid-run edit supersedes the run;
    - limit 3 → 3 tasks, run PARTIAL, rule RATE_LIMITED, owner notified, run-now refused.
  - `ResumeReplayTest` (2): `skippedWhileSuspended` = 2; `replay=false` acts on nothing; `replay=true` fires once, for the change that matched at its own point (the paid one does not), with REPLAY runs and the resumer recorded; a second replay of the same window repeats nothing; live changes act again.
  - `T/automation/action/EmailActionTest` (10): every §8.5 case, including a SUCCESS-mode hand-over that sees the effect committed before submit and happens once.
  - `T/email/EmailPlaceholderTest` (5): manual send, bulk per row, unknown or not-offered → 400 on the field (preview too) with nothing saved, byte-identical plain text and product emails, retry sends the stored text.
  - Extended BE-17 files: `WorkerDurabilityTest` (+1: the health view and its 403) and `FailureClassifierTest` (NotQueued SKIP and PERMANENT assertions).
- **Mutation-checked.** Evaluating SCHEDULED events live fails `ScheduleTickerTest` (2 cases) and `SetRunTest` (1). Evaluating replayed changes live fails `ResumeReplayTest`. Code restored.

**Deviations from DESIGN.md (with reasons)**
- **Placeholder rendering for manual emails lives in `RecordContexts.renderForEmail`**, a file not in BE-18's list. It already builds every placeholder value, and the rendering must happen inside the read-only transaction the record is read in, because email endpoints have no open-in-view and `Customer.Region` / `invoice.getCustomer()` are lazy. `EmailService` depends on it; there is no bean cycle. PROMISE and DISPUTE read their customer through the repository, since the record was already found as the caller, so a holder of PROMISE_VIEW without CUSTOMER_VIEW can still send. `{{Today}}` uses `app.automation.default-zone`.
- **Emails about a product, a user or a role keep `{{…}}` literally** rather than refusing it. §5.10.4 renders only record-bound types, and refusing would change what such emails accept today.
- **Checkpointing on every page, not only on YIELD**, so a crash or a lost lease resumes from the last settled page with a consistent `matched`. **A page with a transient failure is not checkpointed and the event retries at once**, instead of continuing through later pages as BE-17 did. Otherwise a slice boundary would lose track of transient failures in earlier pages.
- **A yield resets the claim-limit base (`claims_base = claims`)**, so a long run spread over many slices is never taken for a claim that never finished. The token still grows (fencing unaffected).
- **SUPERSEDED on resume also applies to a requeued DEAD set event with a cursor** whose rule changed. A cursor-less restart records the current rule version (§5.10.6 step 2 only mentions "a run that has not started"; a restart from the first record is equivalent).
- **Replay only for entity-trigger rules**: resuming a schedule or MANUAL rule with `replay: true` resumes without replay (`replay: false` in the audit). There are no entity events to replay; missed slots are the catch-up's business.
- **The REPLAY run's `matched` counts the changes gone through**, whether or not they matched. What they did is on their own runs.
- **"AutomationWorker (heartbeat)"**: the lease heartbeat for long runs has lived in `RuleRunner.hold` since BE-17 (every page and finish is fenced by it). The worker change here is finishing a YIELD (`yieldAt`).
- **"Gmail not connected" yields DONE with the warning**. Delivery itself is unchanged: the dispatcher hands the email to the mail service, and with transport `none` the row ends NOT_SENT (asserted). `EmailActionTest`'s SUCCESS case asserts `handedOffAt` rather than a non-QUEUED status, because copies stay QUEUED until the mail service reports.
- **A role holder without access to the record's region is already absent from `Target.holders`** (BE-04b's per-region POC book), so no warning names them. The test asserts they are not a recipient. USER tokens get the "cannot see this record" warning as specified.
- **Requeue and retry** were built in BE-17 (logged there). Here, `requeue` keeps the cursor, so a requeued set run resumes from its checkpoint.

**API-contract changes versus §6:** none. `GET /api/automation/health` is as in §6.14. `nextRunAt` is now populated. The manual email endpoints gain the §5.10.4 400s (`fieldErrors.subject` / `.body`) and preview warnings.

**Known gaps / deferred**
- BE-19: CREATE_PROMISE and CREATE_DISPUTE `perform`, internal disputes, holds, the own-rule approval check, and the `RunNowTest` SC-2 assertion.
- `runForRecord` asks the hourly-limit count once per (event, candidate rule), even when the condition does not match: one indexed count query.
- `oldestPendingSeconds` measures from when the event became due (`coalesce(next_attempt_at, occurred_at)`), so a future-dated run-now is not counted as lag.
- Slice size ≈ 2,440 changed lines (≈ 1,200 main, ≈ 1,240 tests), within the bound.

**Tests:** backend 759 run, 0 failures, 0 errors, 0 skipped (722 before + 37 new: ScheduleCalculatorTest 6, ScheduleTickerTest 9, SetRunTest 4, ResumeReplayTest 2, EmailActionTest 10, EmailPlaceholderTest 5, WorkerDurabilityTest +1). mail-service 24 run, 0 failures, 0 errors. Logs: build/be18-full.log, build/be18-mail.log, build/be18-mut.log.

### BE-18 review fixes

All five findings were checked against the code and found valid. All five are fixed.

- **Fixed [medium] A replay that suspends the rule part way reported success and dropped the rest of the window.**
  - `RuleRunner.replay` now re-reads the rule after every replayed change and between pages. If the rule is no longer active, the replay stops before that change and does not count it. The change that suspended the rule, by the hourly limit, the owner or the definition, may never have been evaluated.
  - The new `replayStopped` handles the stop. The same path also covers a REPLAY event that finds its rule already suspended when it is claimed.
    - A suspended rule gets its `suspended_at` moved back to the `occurred_at` of the first change not reached. This is the new JPQL `AutomationRuleRepository.suspendedSince`: conditional, never moves later, no version bump. The next `resume(replay=true)` window therefore starts there, and runs keyed per change mean nothing already done repeats.
    - The run ends PARTIAL, or SKIPPED if nothing was replayed, with `matched` as reached. The message is "Stopped: the rule reached its limit of actions an hour and was suspended; resuming it with replay goes through the changes not reached", or the same with "Stopped: the rule was suspended".
    - A rule switched off or deleted just stops, as before, with "Stopped: rule is not active".
  - New test `ResumeReplayTest.aReplayTheHourlyLimitStopsLeavesTheChangesNotReachedToTheNextResume`, with a limit of 1 and two changes in the window. The first REPLAY is PARTIAL with `matched` 1 and the message; the rule is RATE_LIMITED with `suspended_at` = the payment's moment. The second resume reports 1 skipped, its window starts at 07:20, it ends SUCCEEDED "Replayed 1 change…", and there is still one task.
- **Fixed [low] Changes still waiting for the consumer at suspension were neither replayed nor counted.**
  - `RuleService.suspend` now sets `suspended_at` to the earlier of now and the `occurred_at` of the earliest change the rule would be asked about that is still PENDING or PROCESSING (the new `AutomationEventRepository.earliestUnprocessed`, entity events only). Entity-trigger rules only.
  - So `skippedWhileSuspended` counts those changes, and the replay goes through them. This also covers the change being processed when the consumer suspends a rule over its limit or owner: that change was never evaluated, and the next replay now includes it.
  - The kinds and CHILDREN logic that `RuleRunner` and `RuleService` had each duplicated is now shared as `RuleService.changeKinds` and `asksAboutChildren`.
  - New test `ResumeReplayTest.aChangeStillWaitingWhenTheRuleWasSuspendedIsReplayedToo`: an update at 08:10, left undrained, then a suspend at 08:20, then a drain (no task). Resume with replay reports 1 skipped, the window starts at 08:10, and the task is created.
  - **Remaining race, documented not fixed:** an event whose candidate selection ran before the resume committed, and which goes DONE only after the REPLAY cursor has passed it, is still missed. Closing this would need a lock between resume and candidate selection.
- **Fixed [low] Long set runs spent an attempt on every page with a transient failure.** `AutomationEventRepository.checkpoint` now also sets `attempts = 0`: a settled page is progress. A page that keeps failing is never checkpointed, so it still goes DEAD after `max-attempts`. The worker reads `attempts` fresh on RETRY, so the reset is safe mid-claim. New test `SetRunTest.aPassingFailureOnEveryPageDoesNotGiveUpOnARunThatMakesProgress`: page size 1, 5 invoices, a lock timeout on the first try of every page (5 failures in all), minute steps. The event ends DONE, with 5 tasks and SUCCEEDED 5/5. Before the fix it stalls on backoff and heads for DEAD.
- **Fixed [low] `{{Rule.Name}}` was accepted in hand-written emails and rendered blank.**
  - `PlaceholderCatalogue.offered(EmailEntityType)` now gives, for CUSTOMER, INVOICE and PAYMENT, the customer's fields, the record's fields and `Today` only. `TODAY` is now a constant, also used for PROMISE and DISPUTE.
  - `EmailPlaceholderTest` asserts `{{Rule.Name}}` on an invoice email is a 400 `fieldErrors.body` "{{Rule.Name}} is not available on invoice emails".
  - The picker's `catalogue(entityType)` is unchanged. It is shared with the rule editor and takes only an entity type, so it still lists "Rule name" under General for CUSTOMER, INVOICE and PAYMENT. That is an API-shape question for the frontend's email composer, which can drop the group.
- **Fixed [low] The purge scanned two growing tables every tick.**
  - `ScheduleTicker.tick` now purges at most once per `PURGE_EVERY` (1 hour). A clock set back, as tests do, purges again at once.
  - No indexes were added, to keep the outbox's write path unchanged.
  - `ScheduleTickerTest`'s purge test also asserts that an old event added after a purge survives a tick 30 minutes later and is gone at the tick an hour on.

Mutation-checked each fix: undoing it makes its test fail (build/be18-rfmut.log: 5 failures across ResumeReplayTest ×2, SetRunTest, ScheduleTickerTest and EmailPlaceholderTest). The code was then restored.

**Tests:** backend 762 run, 0 failures, 0 errors, 0 skipped (759 + 3 new: ResumeReplayTest +2, SetRunTest +1; two existing tests strengthened). mail-service 24 run, 0 failures, 0 errors. Logs: build/be18-rf-full.log, build/be18-rf-mail.log.

## BE-19 — Automation: dispute and promise actions, holds

**Built**
- **Disputes (§5.10.11)**:
  - `DisputeTargetType.CUSTOMER` (appended last). `Dispute` gains `internal` (`@ColumnDefault("false")`, NOT NULL, `@Builder.Default false`), `origin` (v12, `Dispute.ORIGIN_AUTOMATION`) and `rule_id`.
  - New `DisputeSchemaUpgrade`: `SchemaSupport.widen(disputes.target_type)` (the H2 ENUM / PG CHECK), then `update dispute_versions set internal = false where internal is null`. Each step is its own try.
  - New `InternalDisputeRequest(targetType, targetId, reason, proposal, ruleId, assignees)`.
  - `DisputeRepository`: `openerOf`, `findInternalAmong`, `existsBy…StatusAndInternalFalse` (the customer's open guard) and `…AndInternalTrue` (one internal dispute per record).
  - `DisputeService`:
    - `open`: a CUSTOMER target → 400 "A dispute is raised on an invoice or a payment"; the "already open" guard counts only non-internal disputes (SC-1).
    - `openInternal` (`MANDATORY`): staff only; the record read through its scoped `get`; the customer locked and its region re-read under the lock; book-aware `requireVisible(CUSTOMER)`; `requireIn(DISPUTE_MANAGE)`; checks that the proposal suits the target; `AlreadyOpenException` (extends `BadRequestException`) when an internal one is pending; saved `internal`, `origin` AUTOMATION, `ruleId`, opener = the owner, `proposed_change_json` `{"action":"none|cancel|void"}`; `AssigneeStore.replace`; audit `DISPUTE_OPENED`.
    - `setAssignees` (customer then dispute locked, 404/`requireLive`/DISPUTE_MANAGE in the region, validated against `assigneeRoles(targetType)`, audit `DISPUTE_ASSIGNEES_CHANGED` {assignees keys}, returns the added tokens).
    - Static `assigneeRoles(type)`: customer-level roles, plus SALES_POC@RECORD on an invoice or COLLECTION_POC@RECORD on a payment. `CreateDisputeHandler.allowedRoles` now delegates to it.
    - The own-rule rule in `approveOrHold`: an internal dispute, an action other than `none`, and caller = opener → 400 "A change your own rule proposed must be approved by someone else". A hold from an internal dispute sets `internal_source = true` on the pending row.
    - Every target switch now handles CUSTOMER by hand: `ensureTargetBelongsToCustomer`, `snapshotTarget` (`CustomerService.snapshotOf`, now public static), `describeTarget` ("Customer Acme Ltd"), `targetView`/`targetPocId` (null), and `toDtos` ("Customer <name>").
    - `parse`: a blank change on a CUSTOMER dispute means `none`; action `none` is valid on any target and `apply` does nothing for it; any other action on CUSTOMER → 400 "Only 'none' is allowed on a customer dispute".
  - `DisputeDtos.DisputeDto` gains trailing `internal`, `origin`, `ruleId`, `assignees` (NON_NULL; the first three for staff only), plus `withAssignees`. New `AssigneesRequest`.
  - `DisputeController`: `PUT /api/disputes/{id}/assignees`. It reads the dispute (404), checks DISPUTE_MANAGE in its region (403), validates (400), runs `AssigneeService.requireVisible` (400 `fieldErrors.assignees`), then `setAssignees`, notifies `DISPUTE_ASSIGNED`, and returns the detail. `GET /{id}` now returns assignees for staff (`describe` live, `describeAt` as of a date).
- **History (CF-11)**: `DisputeVersion` gains `internal` (Boolean), `disputeOrigin` (column `dispute_origin`) and `ruleId`. `DisputeHistory` snapshots, compares and rehydrates them. `HistoricalDispute.internal(Boolean)` maps null to false.
- **Customer logins never see internal work (D17, SC-1)**:
  - `ScopeResolver.ownPredicate(DISPUTE)` adds `internal is null or internal = false`. This drives the list, `get`/by-id (404), the email context through `EmailTargets.load` (404) and `pendingOn`.
  - `AuditTimelineService`, when `customerView`: related disputes skip internal ones; rows about an internal dispute are dropped (one `findInternalAmong` query); rows on the target that name an internal `disputeId` keep the change, with `disputeId` and `reason` nulled (`Entry.withoutDispute`).
- **Promises (§5.10.12)**:
  - `PaymentPromiseService.createOrHold` validates `req.assignees()` (400 `fieldErrors.assignees`) before the gate, keeps the validated tokens in a held payload, and stores them through `AssigneeStore` after the save. The approval's re-run therefore stores them too.
  - `setAssignees` (`getForWrite` PROMISE_MANAGE, audit `PROMISE_ASSIGNEES_CHANGED`, returns the added tokens).
  - `PromiseDto` gains trailing `assignees` (detail only), plus `withAssignees`. New `PromiseDtos.AssigneesRequest`.
  - `PaymentPromiseController`: `PUT /api/promises/{id}/assignees`, built like the dispute one. `GET /{id}` returns co-assignees for staff. `POST /api/promises`: USER co-assignees must be able to see the customer (400), and they are told `PROMISE_ASSIGNED` once the promise is made.
- **Actions**:
  - `CreateDisputeHandler`:
    - `prepare`: renders the reason (a blank result falls back to the record's label), probes and validates assignees.
    - `preview`: warns when an internal dispute is already open.
    - `perform`: re-checks the one-internal-dispute guard under the lock → `Skipped("An internal dispute is already open on this record")`. Otherwise it calls `openInternal`, then `AssigneeService.notifyAssignedTo(DISPUTE…)`, falling back to `notifyAdmins(DISPUTE_OPENED, …, region)` when nobody was told. Result: `Done(DISPUTE, id)`.
  - `CreatePromiseHandler.perform`:
    - problems (e.g. "No Collection POC") → PERMANENT, as before;
    - amount ≤ 0 → `Skipped("Nothing owed")`;
    - the `onlyIfNoOpenPromise` guard is asked again under the lock → `Skipped`;
    - otherwise `promiseService.createOrHold(CreatePromiseRequest(customer, amount, date, POC, notes, [invoice] when linked, probed co-assignees))`. Applied → `Done(PROMISE)` and the co-assignees are told. Held → `Done(PENDING_CHANGE, pc.id)` (D11), whose dedupe key stops a redelivery or a later event from asking twice.
  - `FailureClassifier`: `DisputeService.AlreadyOpenException` → SKIP.
- **Approvals**: `ApprovalService` gains check 5a (`requireNotTheRulesOwner`): an `internal_source` change's dispute opener may not approve it (400, before check 6). After a held PROMISE_CREATE is applied, its stored co-assignees are told `PROMISE_ASSIGNED` (`except` null for an AUTOMATION change, else the maker).
- **Elsewhere**:
  - `EmailTargets`: `disputeOwnFields` is a switch (CUSTOMER → `Map.of()`); the dispute suggestion handles CUSTOMER ("Customer <name>", no record-level role); an internal dispute's suggestions speak for staff, and their outcome goes to the customer-level roles, never the customer. New `findUnscoped(type, id)` (non-throwing).
  - `AssigneeService.notifyAssignedTo(ownerType, ownerId, added, except)`: the record is read unscoped, so a rule's owner or an approver who cannot open it can still trigger the notice; each person is checked as before.
  - `TableSchemas`: DISPUTES gains `internal` (BOOLEAN, pocRestricted) and `assigneeUserId`; PROMISES gains `assigneeUserId`. Both `assigneeUserId` columns are REFERENCE `staffUser`, use the `AssignedTo` filter, are not sortable, and are pocRestricted.
- **Tests (new, 20)**:
  - `automation/action/DisputeActionTest` (8):
    - an internal dispute is opened as the owner with its assignees and region; the invisible person is dropped with a warning; the detail and the notices are checked;
    - a second one is skipped while the first waits, and allowed after it is denied;
    - another approver's approval applies the proposed cancel;
    - the owner's approve → 400 with nothing changed; a clerk's approval above the limit is held with `internal_source`; the owner, even as an approver, cannot approve the held change (check 5a); the owner may deny, which closes the held change;
    - a held change from an internal dispute is applied by another approver;
    - a MANUAL CUSTOMER-target dispute: a `cancel` is refused, approving acknowledges it, and the customer is unchanged;
    - for the customer login: list, get, email context and invoice audit trail (`includeRelated`) show no internal dispute, task or `CHANGE_*` row, even after the held change is applied (the `DISPUTE_APPROVED` row keeps the change but loses `disputeId` and `reason`);
    - the customer opens their own dispute despite a pending internal one; a CUSTOMER target → 400.
  - `automation/action/PromiseActionTest` (5): the primary (not the older seat) is the POC and the co-assignees are stored and resolved live (a seat taken later shows up); no POC → FAILED, run FAILED, event DONE; nothing owed → SKIPPED; `onlyIfNoOpenPromise`; once per record across events.
  - `assignee/AssigneeApiTest` (5): dispute and promise PUT (200 in a MANAGE region, 403 in a READ one, 404 in an ungranted one, invisible USER → 400 with nothing changed, audit row, notices, the caller left out); picker gating (customer login and a VIEWER → 403) and region scoping (record region, own regions, `regionIds`, invisible record → 404); `pocReferences` = 2 after assignments → deactivated; CF-3 context start with the domain services injecting `AssigneeStore` but never `AssigneeService`/`EmailTargets`/`VisibilityProbe`.
  - `dispute/DisputeTargetWidenTest` (1): drift boot on `jdbc:h2:mem:auto-drift-<uuid>` with the columns dropped and an old `enum('INVOICE','PAYMENT')` column. A CUSTOMER insert is refused before the upgrade and accepted after. The old version's `internal` becomes false and the live rows are false. `AppClock` is restored in `finally`.
  - `automation/AutomationHoldTest` (1): a held automation promise has origin AUTOMATION, maker = owner, rule and event. After a crash after commit, the redelivery (claims 2) and a later event leave one pending row (effects DONE `PENDING_CHANGE` + SKIPPED). The owner (an approver) approving → 400 "You cannot approve a change you requested". After the admin approves, there is exactly one promise, its co-assignees are stored and told, and its `PROMISE_CREATED` row has actor owner, `approved_by` admin, `pending_change_id` and origin APPROVAL.
- **Tests (extended)**:
  - `RunNowTest` +1: the BE-17 SC-2 deferral. After an administrator takes over and runs a CREATE_DISPUTE rule, they cannot approve the resulting change (400).
  - `FailureClassifierTest`: AlreadyOpen → SKIP.
  - `ViewScopeInventoryTest`: both PUT assignee endpoints are WRITES/WRITE.
  - `VersionShapeTest`: `RENAMED`/`RENAMED_COLUMNS` map dispute `origin` → `disputeOrigin`/`dispute_origin`.
  - `AsOfParityTest`: the scenario assigns admin to Gamma's promise and Beta's dispute, so the new `assigneeUserId` filters have a live row; they answer the same as of the day.
- Mutation-checked (build/mut19, code restored each time), each on the new tests:
  - a customer filter without `internal` fails DisputeActionTest ×2;
  - no check 5a fails ×1;
  - no `internal_source` fails ×2;
  - no open-guard `internal = false` fails ×1;
  - dropping both audit filters fails ×1; either filter alone is covered by the other, by design;
  - no approval co-assignee notice fails AutomationHoldTest;
  - not storing promise assignees fails PromiseActionTest.

**Deviations from DESIGN.md (with reasons)**
- **A dispute's own `origin` is `dispute_versions.dispute_origin` (attribute `DisputeVersion.disputeOrigin`)**, not `origin`. A version row's `origin` is already the provenance column (NOT NULL). This follows the BE-12 `task_origin` precedent; `VersionShapeTest` maps it explicitly.
- **`AssigneeService.notifyAssignedTo` and `EmailTargets.findUnscoped` are new.** Neither file is in BE-19's list. The rule's owner (in the handler) and the approver (in `ApprovalService`) may not be able to open the new dispute or promise, and the scoped `EmailTargets.load` would throw inside the write transaction and mark it rollback-only (the §5.9.1 rule). The notice therefore reads the record unscoped and checks each person told, as before.
- **`FailureClassifier`** (not listed) maps `AlreadyOpenException` to SKIP, as the §5.10.5 table says. The handler also asks the guard itself under the lock and returns `Skipped`, so the exception is only a backstop.
- **"Nothing owed" is a SKIPPED effect, not a PERMANENT problem**, as §5.10.5 says (`Skipped("Nothing owed")`). BE-16 put it in `prepare`'s problems; `preview` still lists it as a problem.
- **`DisputeService.setAssignees` works on a dispute of any status**, and the as-of-parity scenario assigns to a denied one. Assigning people to review a decided dispute is harmless, and §5.10.11 does not restrict it.
- **Co-assignees on a person's `POST /api/promises`** must be able to see the customer (400 otherwise), because the promise does not exist yet to probe. They are told once it is made. The UI does not offer them on create (FE-07b).
- **`internal`, `origin` and `ruleId` on `DisputeDto` are null for a customer login**, whose disputes are never internal. `assigneeUserId` on DISPUTES/PROMISES is `pocRestricted`, so customers cannot filter on staff identity.
- **The `DisputeTargetWidenTest` drift boot drops the new columns, as well as re-creating the old ENUM**, so the second boot adds them the way a real pre-BE-19 database would get them.

**API-contract changes versus §6:** none. Delivered:
- §6.13 `PUT /api/disputes/{id}/assignees` and `/api/promises/{id}/assignees` (→ `DisputeDto`/`PromiseDto` with `assignees`);
- §6.5 `DisputeDto.internal/origin/ruleId/assignees`, `PromiseDto.assignees` (detail only) and `targetType` CUSTOMER;
- §6.6 `CreatePromiseRequest.assignees`, now applied on create and on approval;
- the §6.16 `assigneeUserId` and `internal` columns.

**Known gaps / deferred**
- As of a date, a record-level role assignee on a dispute or promise resolves to nobody. `AssigneeService.describeAt` only knows invoice and payment POCs. Customer-level roles and people are exact.
- Notices for a rule's dispute or promise are resolved inside the action's lock window, unlike CREATE_TASK's pre-computed plan. The new record does not exist before the lock. There is no statement-count bound on these actions.
- `DisputeController.list` still parses filters against the full DISPUTES schema for customer logins, as before BE-19. A customer filtering on `internal`/`assigneeUserId` gets only their own non-internal rows, but the column names are accepted.
- Slice size ≈ 2,100 changed lines (≈ 900 main, ≈ 1,200 tests), within the bound.

**Tests:** backend 783 run, 0 failures, 0 errors, 0 skipped (762 before + 21: DisputeActionTest 8, PromiseActionTest 5, AssigneeApiTest 5, DisputeTargetWidenTest 1, AutomationHoldTest 1, RunNowTest +1). mail-service 24 run, 0 failures, 0 errors. Logs: build/be19-full.log, build/be19-mail.log; mutations in build/mut19.

### BE-19 review fixes

All five findings were checked against the code and found valid. All five are fixed. The three "Known gaps" logged above for BE-19 (past-date record roles, notices under the lock, customer filters on the dispute list) are now closed.

- **Fixed [medium] Customer logins saw who staff assigned, through the audit trail.** `AuditTimelineService.withoutPocIdentity` now drops every `*_ASSIGNEES_CHANGED` row (the new `ASSIGNEES_ACTION` constant) for customer logins. New test `AssigneeApiTest.aCustomerLoginsTrailDoesNotSayWhoStaffAssigned`:
  - staff see `PROMISE_ASSIGNEES_CHANGED` on the promise trail, and `DISPUTE_ASSIGNEES_CHANGED` on the invoice trail with `includeRelated`;
  - the customer login sees neither, but still sees `PROMISE_CREATED` and its own `DISPUTE_OPENED`;
  - the raw customer response contains no `USER:<id>` and no `COLLECTION_POC`.
- **Fixed [medium] On a past date, a record-level role assignee on a dispute or promise showed nobody (B3).** `AssigneeService.recordPoc` gains two cases:
  - PROMISE is the promise's `collectionPoc` at T;
  - DISPUTE is read at T, then its target: an INVOICE target gives the invoice's `salesPoc` (for SALES_POC only), a PAYMENT target the payment's `collectionPoc` (for COLLECTION_POC only), a CUSTOMER target nobody. This mirrors `EmailTargets.disputeOwnFields`.

  New test `AssigneeApiTest.onAPastDateARecordLevelRoleMeansTheRecordsOwnPocThen`: `SALES_POC@RECORD` on an invoice dispute and `COLLECTION_POC@RECORD` on a promise, with the clock moved 2 days and `?asOf=` the day they were made, resolve to the seller and the collector, with `unresolved` false.
- **Fixed [low] Customer logins could filter the dispute list and export on `assigneeUserId` and `internal`.** `DisputeController` now has a `schema()` helper, `TableSchemas.DISPUTES.visibleTo(currentUser.isCustomer())`, as `PaymentPromiseController` does. Both `list` and `export` use it. New test `AssigneeApiTest.onlyStaffFilterDisputesByWhoIsAssignedOrWhetherTheyAreInternal`: staff filtering `assigneeUserId:eq` gets 1 row; a customer login gets 400 "Unknown column" for `assigneeUserId:eq`, `assigneeUserId:isNotEmpty:` and `internal:eq:false`, and 200 without a filter.
- **Fixed [low] The dispute and promise actions worked out whom to notify inside the customer lock (§5.10.6).**
  - **New `AssigneeService.recipientsForNew`, run in `prepare`.** It works out, before the lock, whom each token will mean on a record that does not exist yet. The result is stored as `Prepared.plan`, as `NewRecordNotice(regionId, recipients)`.
    - A role's holders come from the trigger record's `rc.target()`: a dispute's own POC is its target's, and customer-level seats are the customer's.
    - A promise's `COLLECTION_POC@RECORD` is the Collection POC that `prepare` chose.
    - Each person is checked as `resolve` + `recipients` would check them: active, the owner type's VIEW privilege in the region, and, for a role holder, able to see the record.
  - **New `VisibilityProbe.wouldSee` answers "able to see the record" for a record not yet made.** It shares `canSee`'s principal, privilege and tier logic through the new private `asThem`. A dispute has no book (`Book.NONE`), so FULL means yes and NONE means no. A book-scoped promise is seen only by its Collection POC, which is exactly `bookPredicate(PROMISE)`.
  - **New `AssigneeService.notifyNew`, used by `perform`.** It tells those people and reads nothing: the notice target is built in memory as "Dispute #id" / "Promise #id". If the new record's region differs from the one the notice was worked out for (a customer move committed between `prepare` and the lock), it falls back to `notifyAssignedTo` under the lock, which is exact.
  - `notifyAssignedTo` and `notifyNew` now share a private `tellAssigned`. `ApprovalService`'s approval path still uses `notifyAssignedTo`, since it runs outside the automation lock.
  - **Lock window measured on the test's scenario:** dispute 30 → 21 statements, promise 47 → 31. New tests `DisputeActionTest.insideTheLockWindowThereIsOnlyTheWriteAndItsNotices` (`LOCK_MAX` 25) and `PromiseActionTest.insideTheLockWindowThereIsOnlyTheWriteAndItsNotices` (`LOCK_MAX` 38). Each also asserts that the assigned people, and the role holders, are each told once, with the right link. Both fail on the old path: `notifyNew` forced to fall back gives 30 and 47.
- **Fixed [low] With nothing owed and no Collection POC, the promise action failed instead of skipping, and preview disagreed with execution.**
  - `CreatePromiseHandler.prepare` works out the amount first. When nothing is owed it does not look up a Collection POC, so there is no "No Collection POC" problem, and `perform` returns `Skipped("Nothing owed")`.
  - Preview now shows "Nothing owed, so none would be made" as a warning, not a problem, so preview and execution agree.
  - New test `PromiseActionTest.nothingOwedIsSkippedEvenWhereNobodyCouldWorkAPromise`: a paid invoice on a customer with no Collection POC gives a SKIPPED effect "Nothing owed" and a SUCCEEDED run. Its preview has no problems and has the warning.
  - `FailureClassifierTest`'s generic `BadRequestException("Nothing owed")` → PERMANENT assertion is unchanged; that exception is simply no longer thrown for this case.

Mutation-checked: undoing fixes 1–3 fails the three new `AssigneeApiTest` tests (build/mut19/rf-mut.log). Forcing `notifyNew`'s fallback raises the lock windows to 30 and 47, above both bounds (build/be19-rf-measure2.log). The old `prepare` adds "No Collection POC" before the amount check, so the nothing-owed test's FAILED/SKIPPED assertion fails. The code was restored after each check.

**Deviation:** none from DESIGN.md. `VisibilityProbe` gains `wouldSee`, and `AssigneeService` gains `recipientsForNew` / `notifyNew` / `NewRecordNotice`. They were needed because the record does not exist before the lock, and they reproduce exactly what the post-lock resolution would decide.

**Tests:** backend 789 run, 0 failures, 0 errors, 0 skipped (783 + 6 new: AssigneeApiTest +3, DisputeActionTest +1, PromiseActionTest +2). mail-service 24 run, 0 failures, 0 errors. Logs: build/be19-rf-full.log, build/be19-rf-mail.log.
