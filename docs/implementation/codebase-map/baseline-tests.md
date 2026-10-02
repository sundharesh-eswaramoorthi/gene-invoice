# Build and test baseline map (backend, mail-service, frontend)

Research only. No files were edited and `git status` is clean. Every path below is relative to the repository root unless it says otherwise.

## 1. Baseline results

| # | Command (from repo root) | Result | Counts | Wall time |
|---|---|---|---|---|
| 1 | `cd backend && mvn -q -o test` | exit 0, offline worked, no retry needed | **119 run, 0 failures, 0 errors, 0 skipped** | **34 s** |
| 2 | `cd mail-service && mvn -q -o test` | exit 0 | **24 run, 0 failures, 0 errors, 0 skipped** | **5 s** |
| 3 | `cd frontend && flutter test` | exit 0, "All tests passed!" | **61 passed**: documents_tab_test 25, email_compose_test 32, list_columns_test 4 | **9 s** |
| 4 | `cd frontend && flutter analyze` | exit 0, "No issues found! (ran in 1.6s)" | **0 errors, 0 warnings, 0 infos** | 3 s |

**Backend, per class** (from `backend/target/surefire-reports/*.txt`):
- CustomerFieldHygieneTest: 9 tests, 0.36 s
- DocumentAccessTest: 10 tests, 4.5 s
- DocumentUploadFilenameTest: 2 tests, 4.1 s
- EmailAccessTest: 15 tests, 4.9 s
- EmailContextTest: 17 tests, 5.8 s
- EmailSendTest: 29 tests, 9.8 s
- DueDateTest: 26 tests, 1.8 s
- PromiseFiguresAndPocTest: 11 tests, 1.7 s

**Mail-service, per class:**
- SendWorkerTest: 18 tests, 3.3 s
- DeliveryTrackerTest: 6 tests, 0.23 s

**Pre-existing failures: none** in any of the four commands.

Log noise (all harmless):
- **Backend:** the only WARN lines are 2× `HHH90000025: H2Dialect does not need to be specified explicitly using 'hibernate.dialect'`, one per Spring context. It comes from `backend/src/test/resources/application-test.yml:12`.
- **Mail-service:** expected WARNs from the scenarios under test, e.g. `SendSweeper : Marked 1 interrupted send(s) as failed` and `ConnectionService : The Gmail connection of 8 needs renewing: ... invalid_grant ...`.
- **Flutter:** no warnings or overflow errors.

**Toolchain:** Java 17.0.19 (Zulu), Maven 3.9.15, Flutter 3.44.8 (stable), Spring Boot parent 3.3.5, Hibernate 6.5.3.Final, H2 2.2.224.

## 2. Is Postgres (or RabbitMQ) reachable?

**No. Tests must stay H2-compatible.**
- `docker info` fails with "Cannot connect to the Docker daemon at unix://~/.docker/run/docker.sock". The Docker CLI is installed, but the daemon is not running.
- Ports 5432, 5433 (root `docker-compose.yml` maps Postgres to 5433), 5435 (mail-service's Postgres per `mail-service/docker-compose.yml:1-2`), 5672 (RabbitMQ) and 8091 (mail-service) are all closed. There is no `psql` or `pg_isready`.
- The backend default profile is `dev`, which points at `jdbc:postgresql://localhost:5432/geneinvoice` (`backend/src/main/resources/application.yml:72-86`), so the app cannot boot locally without `SPRING_PROFILES_ACTIVE=h2`.

Offline Maven cache (`~/.m2`), relevant to A5:
- **Present:** `spring-boot-starter-amqp` 3.3.5, `spring-rabbit` 3.1.7/3.1.8, `spring-rabbit-test`, awaitility 4.2.2 (Boot 3.3.5's managed version), testcontainers `rabbitmq` / `junit-jupiter` / `jdbc` modules.
- **Absent:** `org/testcontainers/postgresql`.
- So adding AMQP to the backend would still resolve with `-o`. A Testcontainers test would need Docker, which is not running. The mail-service RabbitMQ Testcontainers test (`RabbitQueueTest`) was deleted in commit 2c3d69a.
- **The backend has no AMQP dependency today** (`backend/pom.xml:24-87`).

## 3. Backend test infrastructure

### 3.1 `IntegrationTestBase` (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java`)

Annotations (lines 41-45):
```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RecordingMailTransport.Config.class)
public abstract class IntegrationTestBase {
```

Injected fields (lines 47-63): `MockMvc`, `ObjectMapper`, and the repositories for User, Role, Customer, Product, Invoice, Payment, PaymentPromise (`promiseRepository`), CustomerPoc, Notification, Document, Email, EmailRecipient, GmailConnection, plus `RecordingMailTransport mailTransport` and `PasswordEncoder`.

**Per-test reset** (`@BeforeEach resetTransactionalData`, lines 65-89). There is no `@Transactional` rollback: every service call and request commits, and the data is deleted before the next test. The order is FK-safe:
```
SecurityContextHolder.clearContext(); mailTransport.reset();
gmailConnection → document → emailRecipient → email → promise → payment → invoice
→ customerPoc → notification → customer → product →
users except "admin" and "cashier" deleted; any inactive survivor re-activated
```

Not reset between tests:
- `audit_logs`, `disputes`, `roles`, `privileges`, `invoice_number_sequence`.
- `invoice_items` and `payment_allocations` go away only through cascade (`Invoice.java:45`, `Payment.java:55` use `cascade = ALL, orphanRemoval = true`).
- Identity sequences are never reset, so ids keep growing. Tests use values like `"404404"` as a missing id (`DueDateTest.java:338`).

**Signing in as a user.** Tests never log in over HTTP. There are two helpers, and both re-read the user so they pick up role changes:
```java
protected void actAs(User u) {                         // lines 132-137: for direct service calls on this thread
    User fresh = userRepository.findById(u.getId()).orElseThrow();
    AppUserDetails principal = new AppUserDetails(fresh, AppUserDetailsService.buildAuthorities(fresh));
    SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
}
protected RequestPostProcessor as(User u) { ... return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())); } // lines 139-144: for MockMvc
```
- Authorities are `ROLE_<roleName>` plus every privilege name of the user's single role (`backend/src/main/java/com/geneinvoice/auth/AppUserDetailsService.java:32-38`).
- `User.role` is EAGER (`user/User.java:38`) and `Role.privileges` is EAGER (`role/Role.java:29`), which is why `buildAuthorities` works outside a transaction.
- **Services read the caller** through `CurrentUser` (`auth/CurrentUser.java`):
  - `require()` (lines 12-18) throws `BadCredentialsException("Not authenticated")` when there is no `AppUserDetails` principal.
  - `idOrNull()` (lines 24-29) returns null instead.
  - `has(privilege)` (lines 35-39) checks the authorities.
  - `canAssignPoc(...)` (lines 41-45) queries the DB.

**Seeding helpers** (lines 91-130). They all write through repositories, so they bypass services, service-level audit and any trigger in a service:
- `role(name)`
- `user(username, roleName)`: email `<username>@test.local`, password "password", active
- `customerUser(username, customerId)`: role CUSTOMER
- `customer(name)` and `customer(name, email)`: `customerRepository.save(Customer.builder().name(name).build())`
- `product(name, price)`
- `json(Object)`: serialises with the app's `ObjectMapper`

**Seeded fixed users.** `DataSeeder` (`backend/src/main/java/com/geneinvoice/config/DataSeeder.java:27-133`) is a `CommandLineRunner`, so it runs once per context start:
- It creates every privilege in `Privileges.ALL`.
- It upserts roles ADMIN (all privileges), CASHIER, VIEWER and CUSTOMER, and creates POC roles `SALES_POC`, `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC` if absent. Constants: `DataSeeder.ROLE_SALES_POC`, `ROLE_SUCCESS_POC`, `ROLE_COLLECTION_POC` (lines 29-31).
- It seeds users `admin/admin123` and `cashier/cashier123`. Tests fetch them with `userRepository.findByUsername("admin").orElseThrow()`.

### 3.2 `CountingStatements` (`backend/src/test/java/com/geneinvoice/CountingStatements.java`)

- A Hibernate `StatementInspector`, wired in `application-test.yml:13-16` as `hibernate.session_factory.statement_inspector`.
- `CountingStatements.reads(String table, ThrowingRunnable body)` (lines 14-27) records all SQL while `body` runs. It returns how many statements start with `select` and contain `table` as a **substring**.
- The buffer is a **global static** (lines 11-12), not tied to a thread. Background threads (schedulers, a future automation consumer) that run SQL during the window are counted too.
- Usage example, the N+1 guard (`email/EmailContextTest.java:130-135`):
  `long reads = CountingStatements.reads("customer_pocs", () -> context(admin, ...)); assertThat(reads).isEqualTo(1);`
- This is the tool for B1: assert that a list or count request reads the user's region grants once, not once per row.

### 3.3 `EmailTestBase` (`backend/src/test/java/com/geneinvoice/email/EmailTestBase.java`)

Fixtures (`@BeforeEach emailFixtures`, lines 43-54):
- Users: `admin`, `sales` (sam.sales, SALES_POC), `otherSales`, `collections` (cara.collections), `success` (sue.success)
- Customer `acme` ("Acme Ltd", ap@acme.test), its login `acmeLogin`, product `widget` (1200.00)
- Then `actAs(admin)`

Helpers:
- `invoice(customer, salesPoc)` (lines 56-60) goes through `invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null, salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))))`.
- `seat(customer, PocType, holder)` gives a primary seat and `extraSeat` a non-primary one. Both call `pocService.add(customerId, type, userId, primary)` (lines 62-70).
- `threeCollectionSeats` (lines 72-80) sets up a role held by several people with one of them inactive. It is the direct precedent for A4 ("a role held by several people resolves to the primary").
- `deactivate` / `reactivate` (lines 82-92).
- Recipient token builders, the same wire shape A3 must reuse (lines 94-109):
  - `toUser(u)` → `{"type":"USER","userId":..}`
  - `toRole(role)` → `{"type":"ROLE","role":..}`, the legacy form with no level
  - `toRole(role, level)` → `{"type":"ROLE","role":..,"level":..}`
  - `toCustomer()` → `{"type":"CUSTOMER"}`
- `email(entityType, entityId, to, extra...)` builds the send body `{entityType, entityId, to, subject:"About your account", body:"Hello", ...}` (lines 111-120).
- HTTP helpers: `postJson(path, caller, body)` (122-125), `send(caller, body)` (POST `/api/emails`, expects 200, lines 127-129), `getOk(path, caller, params...)` (131-135), `read(ResultActions)` (UTF-8 → JsonNode, 137-139), `recipientsOf(emailId)` (141-143).

### 3.4 `RecordingMailTransport` (`backend/src/test/java/com/geneinvoice/email/RecordingMailTransport.java`)

- It implements both `MailTransport` and `MailConnections`.
- It is registered as a `@Primary` bean through the nested `@TestConfiguration Config` (lines 227-234), which `IntegrationTestBase` imports.
- **Modes** (line 31): `NOT_CONFIGURED` (the default after `reset()`), `SUCCESS`, `TRANSIENT_FAILURE`, `PERMANENT_FAILURE`.
  - In the default mode, `isConfigured()` is false. The dispatcher then marks copies NOT_SENT and never calls `submit()`.
  - `submit()` in NOT_CONFIGURED mode throws `IllegalStateException` (line 119).
- **Controls:** `mode(..)`, `beforeSubmit(Consumer<Submission>)`, `outcome(Function<CopyRequest,Outcome>)`, `reported(Predicate)`, `failConnections(..)`, `beforeConnect(Runnable)`, `syncResult(..)`, `hold(ConnectionState)` (lines 79-155).
- **Inspection:** `submissions()`, `copiesHandedOver()`, `copy(externalId)`, `connectCalls()`, `syncCalls()`, `disconnectCalls()`, `held(userId)`.
- An A3 "send Email" action test should call `mailTransport.mode(Mode.SUCCESS)` (as in `EmailAccessTest.java:66`) and assert on `mailTransport.submissions()` or the recipients.

### 3.5 `DocumentTestBase` (`backend/src/test/java/com/geneinvoice/document/DocumentTestBase.java`)

- Fixtures (lines 56-72):
  - Users: admin, cashier, `viewer` (VIEWER), sales, otherSales, `collector`
  - Two customers, `acme` and `globex`, each with its own login
  - Product `widget`
  - One invoice per customer, with **different sales POCs** so that "outside my book" can be tested
- `payment(customer, amount)` (lines 80-84): `paymentService.record(new PaymentDtos.CreatePaymentRequest(customer.getId(), new BigDecimal(amount), "Cash", null, List.of(), collector.getId(), null))`.
- File byte builders `pdf()`, `png()`, `jpeg()`, `docx()`, `xlsx()`, `plainZip()`, `plainText()` (lines 86-145), and multipart `uploadRequest` / `upload` (151-164).
- `DocumentUploadFilenameTest` (`document/DocumentUploadFilenameTest.java:22-81`) is the one real-server test:
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@LocalServerPort`, `java.net.http.HttpClient`
  - Bearer token from `jwtService.generateToken(admin.getUsername(), jwtService.claimsFor(admin))` (line 80)
  - **It forces a second Spring context** (see §4).

### 3.6 `PromiseFiguresAndPocTest` (`backend/src/test/java/com/geneinvoice/promise/PromiseFiguresAndPocTest.java`)

- Services are called directly after `actAs(admin)` in `@BeforeEach` (lines 60-69). The seat is created with `pocService.add(acme.getId(), PocType.COLLECTION, collections.getId(), true)`.
- Direct DTO calls:
  - `promiseService.create(new PromiseDtos.CreatePromiseRequest(customerId, amount, date, null, "note", invoiceIds))` (77-80)
  - `promiseService.cancel(id, reason)`, `promiseService.dto(id)`, `promiseService.reassignCollectionPoc(id, userId)`
- **Filter query syntax** on list endpoints is `?remainingAmount=eq:0`. The paged response is `{"content":[...]}` (lines 128-137).
- **Custom roles** are built from privileges (lines 161-173, 240-248):
  ```java
  roleRepository.findByName("PROMISE_CLERK").orElseGet(() -> roleRepository.save(Role.builder().name(..).description(..)
      .privileges(Stream.of(Privileges.PROMISE_VIEW, ..., Privileges.SCOPE_OVERRIDE)
          .map(n -> privilegeRepository.findByName(n).orElseThrow()).collect(Collectors.toCollection(HashSet::new))).build()));
  ```
  - Roles persist across tests, hence `findByName(..).orElseGet(..)`. The test at line 240 saves `PROMISE_OVERSEER` without that guard and relies on running only once.
  - `SCOPE_OVERRIDE` is what lets a custom role see beyond its own POC book. B1 must decide how it interacts with region rights.
- **Bulk endpoint shape:**
  - Request: POST `/api/promises/bulk` with `{"action":"CANCEL"|"REASSIGN_COLLECTION_POC", "ids":[..], "params":{..}}`.
  - Response: `{"failed":[..], "skipped":[{"reason":".."}], ...}` (lines 195-231).
- **Privilege refusal** returns 403 (`recompute`, line 259). A business-rule refusal returns 400 (lines 180-184, 195-200). After a refusal the test re-reads the repository to prove nothing changed.

### 3.7 `DueDateTest` (`backend/src/test/java/com/geneinvoice/invoice/DueDateTest.java`)

- **Time is controlled only through data.** Fixed instants such as `RAISED = Instant.parse("2026-03-01T09:00:00Z")` (line 35) are passed as `invoiceDate` in the request, or dates are computed relative to `LocalDate.now(ZoneOffset.UTC)` / `InvoiceDates.today()` (lines 327, 371).
- **Audit assertions** (lines 185-196, 203-212):
  ```java
  auditService.historyFor(InvoiceService.ENTITY, inv.getId()).stream().filter(e -> e.getAction().equals("INVOICE_DUE_DATE_CHANGED"))
  ... entry.getBeforeJson() / getAfterJson() / getChangedByUserId()
  ```
  They also assert idempotence: a second save that changes nothing adds no second entry.
- **Validation error shape:** 400 with `$.fieldErrors.<field>` holding the exact message, e.g. `jsonPath("$.fieldErrors.dueDate").value("The due date cannot be before the invoice date")` (lines 220-225). The test then asserts the DB is untouched: `assertThat(invoiceRepository.findAll()).isEmpty()`.
- **Status conventions:**
  - Missing privilege → **403** (line 336).
  - Unknown id → **404** (339).
  - Garbage parameter → **400** (343).
  - **A record outside the caller's book → 404, not 403**: "cannot tell a foreign record from a missing one" (lines 347-366, `thePreviewDoesNotReachOutsideTheCallersBook`).
  - B1 region scoping should keep the same 404-for-invisible and 403-for-no-privilege split.
- Direct service DTOs:
  - `new InvoiceDtos.CreateInvoiceRequest(customerId, Instant date, LocalDate dueDate, PaymentTerm term, <notes>, salesPocUserId, List<LineInput>)` (lines 57-59), plus the shorter 5-argument overload (line 122).
  - `new InvoiceDtos.UpdateInvoiceRequest(notes, ?, dueDate, term)` and its 2-argument form.
  - `customerService.update(id, new CustomerDtos.CustomerUpdateRequest(name, phone, email, address, term, null))`.
- HTTP verbs in use: `post("/api/invoices")`, `patch("/api/invoices/{id}")`, `put("/api/customers/{id}")`, `get("/api/invoices/due-date-preview")`, `get("/api/invoices")` → `$.content[0]...`.

### 3.8 Assertion and style conventions

- AssertJ (`assertThat`, `satisfies`, `singleElement`, `filteredOn`, `.as("why")`) plus MockMvc `status()` / `jsonPath()`.
- JSON is parsed with `objectMapper.readTree(result.getResponse().getContentAsString())`.
- Request bodies are `Map.of(...)` or `HashMap` passed through `json(..)`, not DTO instances.
- Test names are sentence-style camelCase. Javadoc cites defect or requirement ids (PPD-05, AUTH-05, L7, D-02, AC-C8).
- No `@Nested`. `@DisplayName` appears only in `DocumentUploadFilenameTest`.
- Test classes are package-private and live in the same package as the feature, so package-private services are reachable.

## 4. Spring contexts and the shared H2 database

- **Two contexts start in one surefire JVM.** The log shows `HikariPool-1` for DocumentUploadFilenameTest (RANDOM_PORT) and `HikariPool-2` for everything else.
- **Both use the same URL** `jdbc:h2:mem:geneinvoice-test;DB_CLOSE_DELAY=-1` with `ddl-auto: create-drop` (`application-test.yml:3,9`).
- When the second context started, its schema export **dropped and recreated every table** in the shared in-memory DB. `DataSeeder` then logged "Seeded admin user" a second time, which proves the DB was wiped.
- This is harmless today only because test classes run one after another and every test resets its data.

Gotchas:
1. **Anything that changes the context cache key starts a third context.** That includes `@MockBean`, `@SpyBean`, `@TestPropertySource`, `@DynamicPropertySource`, `@Import` or `properties=` on a subclass. The new context costs about 0.5–2.5 s and wipes and reseeds the shared DB.
2. **`@DirtiesContext`, or closing a context, would run create-drop's DROP** against the DB the other cached context is still using. Its later tests would then fail with missing tables. Avoid it. If an alternative configuration is really needed (as the old webhook tests had, per the `application-test.yml:30-31` comment), give that context its own H2 URL through a property.
3. Any bean that caches DB ids at startup can go stale after the other context recreates the schema. Examples are `InvoiceNumbers` on `ApplicationReadyEvent` (`invoice/InvoiceNumbers.java:24`) and a future "default region id" cache. Look ids up lazily.
4. `PromiseSweepScheduler.sweepOnStartup` runs on `ApplicationReadyEvent` in every context (`promise/PromiseSweepScheduler.java:27-30`).
5. Scheduled jobs are neutralised by 1-hour intervals, not disabled:
   - `app.promises.sweep-interval-ms: 3600000` and `app.mail.dispatch.sweep-interval-ms: 3600000` (`application-test.yml:24,37`)
   - Mail dispatch is inline: `app.mail.dispatch.async: false` (35)
   - Transport is `none` and the mail service URL is `http://127.0.0.1:9` (32, 40)
   - **New schedulers (A1 daily/weekly, the A5 consumer or sweeper) must follow this:** an interval property, set to 3600000 in `application-test.yml`, with tests calling the job method directly.
6. `spring.task.scheduling.pool.size: 3` (`application.yml:26-30`, commented as one thread per sweeper). Adding automation schedulers without raising it lets a slow job (for example, a mail call of up to 40 s) delay the others.

## 5. Controlling time

- **The backend has no `Clock` bean.**
  - "Today" is `InvoiceDates.today()`, which is `LocalDate.now(ZoneOffset.UTC)` (`invoice/InvoiceDates.java:14-16`).
  - The date-filter presets use `LocalDate.now(ZoneOffset.UTC)` (`common/query/FilterPredicates.java:68`).
  - Timestamps are set in `@PrePersist` / `@PreUpdate` with `Instant.now()` (`Customer.java:50-53`, `Invoice.java:78`, `Payment.java:62`, `Document.java:96,106`, `Dispute.java:66,73`, `PaymentPromise.java:100,107`, `Email.java:148,158`, `AuditLog.java:55`, `Notification.java:51`, `User.java:53`, `CustomerPoc.java:53`, `Product.java:40`).
  - Services also use `Instant.now()` directly, e.g. `InvoiceService.java:66,215`, `EmailDispatcher.java:72,120,157`, `DisputeService.java:103,123`.
- **Tests control time only by passing dates in** (see §3.7). `PromiseFiguresAndPocTest` freezes `TODAY` in a `static final` field (line 50). Because it uses UTC dates on an IST machine, that can flake if the run crosses 00:00 UTC (05:30 IST).
- **The in-repo precedent for an injectable clock is the mail service:**
  - `mail-service/src/main/java/com/geneinvoice/mail/config/ClockConfig.java:8-15` declares `@Bean Clock clock() { return Clock.systemUTC(); }`.
  - `mail-service/src/test/java/com/geneinvoice/mail/MovableClock.java` is a `final class MovableClock extends Clock` with `set(Instant)`, `advance(Duration)` and `reset()`, fixed at UTC.
  - Its `IntegrationTestBase` registers it as `@Bean @Primary MovableClock` in a nested `@TestConfiguration TestBeans` (lines 63-76) and calls `clock.set(T0)` in `@BeforeEach` (line 100), where `T0 = Instant.parse("2026-09-20T10:00:00Z")`.
- For B3 (as-of) and the A1 schedules, the backend needs the same pattern.
  - A backend `MovableClock` has to be in `IntegrationTestBase`'s **single** `@Import`, or it creates a new context (§4).
  - Entity `@PrePersist` `Instant.now()` cannot see a Spring `Clock`. Effective-dated columns that as-of reads depend on (`valid_from`, `region_since`, `decided_at`) should be set by services from the injected clock, not by entity callbacks.

## 6. H2 vs Postgres differences the code already works around

No literal `SKIP LOCKED` or `FOR UPDATE` string appears anywhere in `backend/src/main` or `mail-service/src/main`. The existing workarounds:

| Concern | Postgres path | H2 path / workaround | Where |
|---|---|---|---|
| Detecting the DB | `connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql")` | `.contains("h2")`; anything else returns without action | `poc/PocSchemaUpgrade.java:58-60`, `customer/CustomerSchemaUpgrade.java:59-61`, `email/EmailSchemaUpgrade.java:42-64`, `invoice/InvoiceSchemaUpgrade.java:147-155,180-182` |
| Partial / functional unique indexes | `create unique index if not exists uk_customer_poc_primary on customer_pocs (customer_id, poc_type) where is_primary`; `... uk_customer_email on customers (lower(email)) where email is not null` | **Skipped on H2.** H2 2.2.224 rejects `CREATE INDEX ... WHERE` (probed). Tests rely on service-level locking and checks instead (`CustomerPocRepository.findByCustomerIdAndPocTypeForUpdate` lines 21-25; `CustomerRepository.existsByEmailIgnoreCase`) | `PocSchemaUpgrade.java:57-69`, `CustomerSchemaUpgrade.java:58-70` |
| Enum CHECK constraints (Hibernate 6 generates them for `@Enumerated(STRING)`; `ddl-auto: update` never widens them) | `pg_constraint` / `pg_get_constraintdef`, column written `(status)` | `information_schema.table_constraints` joined to `check_constraints`, `table_name = upper(?)`, `schema()`, column written `"STATUS"` | `EmailSchemaUpgrade.widen(connection, table, column, EnumClass)` lines 40-88; used only for `emails.status` (line 34) |
| Adding NOT NULL after a backfill | `alter table invoices alter column due_date set not null` | `alter table invoices alter column due_date date not null` | `InvoiceSchemaUpgrade.enforceDueDateNotNull` lines 146-164 |
| Column metadata | `information_schema.columns`, lower-case names, `current_schema()` | upper-case names, `schema()` | `InvoiceSchemaUpgrade.isNullable` lines 166-178 |
| Reading timestamps over JDBC | `getObject(col, OffsetDateTime.class)` | falls back to `Timestamp` or `LocalDateTime` (read as UTC) | `InvoiceSchemaUpgrade.raisedAt` / `instantOf` lines 130-144 |
| Insert-if-absent (no `ON CONFLICT`; H2 rejects it, probed) | native `insert into gmail_connections (user_id, status, updated_at) values (:userId, 'DISCONNECTED', :now)` | same SQL, run in its own transaction; `DataIntegrityViolationException` is swallowed; then `@Lock(PESSIMISTIC_WRITE)` select | `email/connection/GmailConnectionRepository.java:17-24`, `GmailConnectionService.java:177-188` |
| Work-queue claim (no SKIP LOCKED) | conditional JPQL `update Email e set e.status = SENDING, e.attempts = e.attempts + 1 ... where e.id = :id and e.status = QUEUED and e.handedOffAt is null and (e.nextAttemptAt is null or e.nextAttemptAt <= :due)`; 0 rows means someone else claimed it | identical | `email/EmailRepository.java:32-42`, used by `EmailDispatcher.claim` line 157 |
| Row locks | `@Lock(LockModeType.PESSIMISTIC_WRITE)` JPQL `findByIdForUpdate` | identical, rendered as `for update` | Customer, Invoice (single, list, by customer), InvoiceNumberSequence, CustomerPoc, Document, Dispute, Email, GmailConnection repositories |
| LIKE escaping | `lower(..) like :q escape '\\'` plus `Strings.escapeLike` | identical | `user/UserRepository.java:45-47`, `email/EmailDirectory.java:28-36`, `common/query/FilterPredicates.java:38-40` |
| Long text / JSON | `@Lob @Column(columnDefinition = "TEXT")` | identical; no `jsonb` (H2: "Unknown data type: JSONB", probed) | `audit/AuditLog.java:33-38`, `dispute/Dispute.java:41-42` |
| Optimistic locking | `@Version Long version` with backfill `update <t> set version = 0 where version is null` | identical | `Customer.java:43-45`, `Invoice.java:69`, `common/RowVersionUpgrade.java:18-48` |

I also ran probes against the cached H2 2.2.224 jar (throwaway scripts in the scratchpad, not in the repo):
- **Compatibility mode is `MODE=REGULAR`.** The test URL does not use `MODE=PostgreSQL`.
- **Raw `FOR UPDATE SKIP LOCKED` works in H2 2.2.224.** With two connections, the first locked row 1 and the second received row 2. `FETCH FIRST n ROWS ONLY FOR UPDATE` works, as do `ILIKE` and `date - int`.
- **Hibernate 6.5.3's `H2Dialect` does not support skip-locked.** It does not override `supportsSkipLocked()`, which returns false in the base `Dialect`, and `getForUpdateSkipLockedString()` falls back to `getForUpdateString()` (checked with `javap -c`).
  - So a JPA `lock.timeout = -2` (SKIP_LOCKED) hint **silently becomes a plain `FOR UPDATE` on H2**, while it really skips on Postgres.
  - A queue claim built on the JPA hint would behave differently in tests than in production. Copy the conditional-UPDATE claim pattern instead, or use a native query that has been verified on both databases.
- **H2 lock timeout is about 2 s** (a blocked `FOR UPDATE` failed after 2014 ms with "Timeout trying to lock table"). Postgres waits indefinitely by default. A race test that holds a lock for more than about 2 s fails on H2 with a lock timeout, not a wait.
- Not available in H2: `jsonb`, range types (`tstzrange`), partial indexes, `ON CONFLICT`. Keep B3 as-of storage to plain columns: `valid_from` / `valid_to` timestamps, history rows, TEXT JSON snapshots.

## 7. Schema-upgrade convention

Copy this for new region, approval and as-of columns:
```java
@Component
@Slf4j
class XxxSchemaUpgrade implements InitializingBean {
    private final DataSource dataSource;
    XxxSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) { this.dataSource = dataSource; } // EMF param forces ordering after ddl-auto
    @Override public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) { ... }
        catch (SQLException e) { log.warn("Could not finish the ... upgrade: {}", e.getMessage()); }
    }
    // static, package-private steps taking a Connection so a test can call them directly;
    // `if (!connection.getAutoCommit()) connection.commit();` after each write
}
```
(From `PocSchemaUpgrade.java:14-37`, `InvoiceSchemaUpgrade.java:24-56`, `RowVersionUpgrade.java:14-36`.)

- **The `ddl-auto: update` + `halt_on_error` trap.** `application.yml:10-24` sets `hibernate.hbm2ddl.halt_on_error: ${SCHEMA_HALT_ON_ERROR:true}`. `update` cannot add a NOT NULL column to a table that already has rows, and with the halt flag, **startup fails**.
  - B1's "every record belongs to exactly one region" must therefore be done in three steps: map `region_id` as nullable, backfill in chunks in a `*SchemaUpgrade`, then enforce NOT NULL with the dialect-branched ALTER.
  - `InvoiceSchemaUpgrade` does exactly this for `due_date`: keyset chunks of 1000 by id (lines 60-128), then `enforceDueDateNotNull`, then one audit row `auditService.record(ENTITY, 0L, "INVOICE_DUE_DATES_BACKFILLED", null, record, null, null, reason)` (lines 51-55).
- **Tests on create-drop H2 start from an empty schema, so they never exercise this path.**
  - The deleted `InvoiceSchemaUpgradeTest` (`git show 2c3d69a^:backend/src/test/java/com/geneinvoice/invoice/InvoiceSchemaUpgradeTest.java`) shows the pattern: autowire the upgrade and `DataSource`, put the table back into its pre-upgrade state with raw SQL (`alter table invoices alter column due_date set null`), call `upgrade.afterPropertiesSet()` in `try/finally`, then assert `isNullable(..)` and the backfill audit entry.
  - The deleted `SchemaDriftTest` (`git show 2c3d69a^:backend/src/test/java/com/geneinvoice/config/SchemaDriftTest.java`) boots the app with `SpringApplicationBuilder` against a throwaway `jdbc:h2:mem:geneinvoice-drift-<uuid>` URL, using `--spring.jpa.hibernate.ddl-auto=update`. This is the safe way to test "an existing DB upgrades" without touching the shared test DB.
- **New enum values on existing columns** (for example a new `EmailEntityType`, `DocumentEntityType` or `DisputeTargetType` for TASK, or new statuses) need `EmailSchemaUpgrade.widen(...)` called for that table and column. Otherwise inserts fail the stale CHECK on existing Postgres or H2-file databases, and tests will not notice.
- **The `azurefree` profile** (`application.yml:131-144`) runs **H2 file + `ddl-auto: update`** in production-like use. Upgrades must work on H2 with existing data too, not only on Postgres. This is why every upgrade branches on "h2".

## 8. Extension points and risks for the PRD, from a test and build angle

1. **The reset list must grow.**
   - New tables with FKs to customers, invoices, payments or users go before those deletes: tasks, task assignees, rules, rule runs, outbox or events, regions, user region grants, region POC book, pending changes / approvals, history tables.
   - Otherwise `customerRepository.deleteAll()` or the user delete fails with an FK violation in the next test's `@BeforeEach` (`IntegrationTestBase.java:65-89`).
   - Tables holding plain `Long` ids (the `Dispute` style, `dispute/Dispute.java:25-36`) do not break the reset, but they leak rows into later tests' lists and counts. Disputes already leak today, which matters for A3 "create Dispute" tests that count disputes.
   - Regions and thresholds that are seeded like roles would persist, as roles do.
2. **Fixture helpers bypass services.**
   - `customer(..)`, `product(..)` and `user(..)` use `repository.save(builder...)`. Once `region_id` is NOT NULL on customers, invoices and payments, these helpers and the seeded admin need a default region, either from an entity `@PrePersist` or by updating the helpers.
   - The same applies to A1: triggers wired only in `CustomerService` will not fire for repository-created fixtures. Triggers wired as JPA entity listeners would fire for fixtures and for `*SchemaUpgrade` backfills.
3. **Authority model.**
   - `buildAuthorities` gives flat privilege names from one role (`AppUserDetailsService.java:32-38`), and `as(u)` / `actAs(u)` rebuild them from the DB on each call. Per-region rights (B1) will need a helper along the lines of `grant(user, region, level)`, plus `as(u)` still re-reading so that grants made mid-test apply.
   - New privileges (for example an approval right or rule management) must be added to `Privileges.ALL` and to role defaults in `DataSeeder`. `createRoleIfAbsent` only grants a POC role privileges that were **created in this run** (`DataSeeder.java:147-164`).
4. **Consumers run with no authenticated user.**
   - An A5 consumer or scheduled rule has an empty `SecurityContext`, so `CurrentUser.require()` throws `BadCredentialsException` (`auth/CurrentUser.java:12-18`).
   - Tests that call a consumer synchronously **inherit the test thread's `actAs(admin)` context and hide this bug**. Call `SecurityContextHolder.clearContext()` before invoking the consumer in tests, or run it on another thread.
   - `EmailService.send` also checks the caller's access through `targets.load(...)`.
5. **Email from a consumer.** `EmailDispatcher.dispatch` throws `IllegalStateException("Email must be dispatched outside a transaction")` if a transaction is active (`email/EmailDispatcher.java:75-78`). `EmailService.send` is deliberately not `@Transactional`; it saves through `TransactionTemplate` and then dispatches (`EmailService.java:218-228`). An automation action that calls it from inside `@Transactional` will fail.
6. **Durable-execution templates already in the repo:**
   - **Backend DB outbox:** a status row, the conditional-UPDATE `claim`, a `findDue` / `findStaleSending` sweep in batches of 200, and a sweeper under `@Scheduled(fixedDelayString = "${...sweep-interval-ms}")` wrapped in try/catch-log (`email/EmailRepository.java:32-61`, `EmailDispatcher.java:119-154`, `EmailSweepScheduler.java:17-30`). In tests, `async=false` runs dispatch inline and the test calls `dispatcher.sweep()` directly.
   - **Mail-service queue abstraction:** a `SendQueue` interface with `RabbitSendQueue` for production and `mail.queue: direct` in tests. The test double `RecordingSendQueue` (`mail-service/src/test/java/com/geneinvoice/mail/RecordingSendQueue.java`) provides `enqueue`, `take()` and `failing(true)`, which throws "RabbitMQ is not reachable". It is a ready pattern for testing "nothing lost if the consumer or broker is down" without Docker.
7. **Don't use `@DirtiesContext`,** and minimise per-class `@MockBean` / `@TestPropertySource` (§4). Put new test doubles (a movable clock, a recording event bus) in the single `@Import` of `IntegrationTestBase`, following `RecordingMailTransport.Config`.
8. **N+1 guard for B1.** Use `CountingStatements.reads("<grant table>", () -> mockMvc.perform(...))` to pin region-grant lookups to one per request. It counts all threads (§3.2), so keep schedulers idle during the window.
9. **Git history reference.** Commit 2c3d69a deleted 105 test files. Useful ones to read with `git show 2c3d69a^:<path>`:
   - `backend/src/test/java/com/geneinvoice/auth/RecordVisibilityTest.java`: 404 for foreign records, 403 for schema access
   - `.../dashboard/DashboardTest.java`: dashboard figures, which B1 has to scope by region
   - `.../payment/ConcurrentMoneyTest.java`: two threads, each calling `actAs(admin)` itself, with a `CountDownLatch`
   - `.../dispute/DisputeApprovalRaceTest.java`: an existing approval race, relevant to B2
   - `.../common/.../TableQueryTest.java`, `BulkActionTest.java`, `DateFilterBoundsTest.java`: list, filter and bulk behaviour
   - `mail-service/.../RabbitQueueTest.java`: Testcontainers RabbitMQ, which needs Docker

## 9. Mail-service and frontend test conventions

**Mail-service** (`mail-service/src/test/java/com/geneinvoice/mail/IntegrationTestBase.java`):
- `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestBeans.class)`
- A static `FakeGoogle` HTTP server, wired in through `@DynamicPropertySource` (lines 53-61)
- `@BeforeEach` deletes all four repositories, resets the fakes, sets `clock.set(T0)`, and resets webhook state through `ReflectionTestUtils` (92-105)
- Calls carry an `X-Api-Key` header (107-114)
- `work()` drains the recording queue into `SendWorker.process` (149-151)
- H2 is test-scope only. `application-test.yml` sets `mail.queue: direct`, 1-hour intervals and `management.health.rabbit.enabled: false`.

**Frontend:**
- `frontend/test/support/fake_backend.dart:9-38`: `FakeBackend(Map<'METHOD /path', handler>)` puts an interceptor on a `Dio`.
  - Unknown routes are rejected as 404 `{'message':'no fake route'}`.
  - A handler can return `FakeFailure(status, data)`.
  - `requests` / `sent(key)` show what was sent, and `held[key]` gates simulate a request that is still in flight.
- Widgets are pumped inside `ProviderScope(overrides: [dioProvider.overrideWithValue(backend.dio), currentUserProvider.overrideWithValue(user), ...])` with `MaterialApp(theme: AppTheme.light())`. The test also sets `tester.view.physicalSize` and `devicePixelRatio = 1`, with `addTearDown(tester.view.reset)` (`documents_tab_test.dart:97-106`).
- The current user is a `const CurrentUser(id, username, fullName, role, privileges: {Privileges.customerView, ...})` (`list_columns_test.dart:22-30`). Region rights will need a counterpart there.
- `support/roboto.dart` `loadRoboto()` loads the real fonts from `$FLUTTER_ROOT/bin/cache/artifacts/material_fonts` for width and overflow tests, and fails if they are missing.
- `analysis_options.yaml` includes `flutter_lints` with `prefer_const_constructors: true` and `constant_identifier_names: false`, because enum constants mirror the backend wire names (UNPAID, PARTIALLY_KEPT).
- The frontend depends on `dio ^5.7.0`, `flutter_riverpod` and `go_router`. The Dart SDK constraint is `>=3.4.0 <4.0.0`.

**Key paths:**
- `backend/src/test/java/com/geneinvoice/IntegrationTestBase.java`
- `backend/src/test/resources/application-test.yml`
- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/geneinvoice/invoice/InvoiceSchemaUpgrade.java`
- `backend/src/main/java/com/geneinvoice/email/EmailSchemaUpgrade.java`
- `backend/src/main/java/com/geneinvoice/email/EmailDispatcher.java`
- `backend/src/main/java/com/geneinvoice/email/EmailRepository.java`
- `mail-service/src/test/java/com/geneinvoice/mail/MovableClock.java`
- `mail-service/src/test/java/com/geneinvoice/mail/RecordingSendQueue.java`