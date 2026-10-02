# Engineering map: dashboard, notifications, schedulers and background processing (for PRD A5, A6, B1, B2, B3)

All paths are relative to the repository root. Abbreviations: `BE` = `backend/src/main/java/com/geneinvoice`, `MS` = `mail-service/src/main/java/com/geneinvoice/mail`, `FE` = `frontend/lib`.

---

## 0. Summary

- **Scheduling**
  - Backend has `@EnableScheduling` but no `@EnableAsync` (`BE/GeneInvoiceApplication.java:7-8`).
  - There are exactly two `@Scheduled` jobs: `PromiseSweepScheduler` (15 min) and `EmailSweepScheduler` (60 s). `InvoiceNumbers` is a startup hook only.
  - The scheduler pool size is 3 (`backend/src/main/resources/application.yml:25-30`).
  - "Async" work runs on hand-made single-thread executors, controlled by an `app.mail.dispatch.async` flag. Tests set that flag to `false`.
- **No Clock bean in the backend.** Nothing in `backend/src/main/java` uses `java.time.Clock`. Time comes from `Instant.now()`, `@PrePersist` hooks and the static `InvoiceDates.today()`. mail-service has the pattern to copy: `MS/config/ClockConfig.java` plus `MovableClock` in tests.
- **No AMQP in the backend.**
  - `backend/pom.xml:24-87` has web, jpa, security, validation, postgresql, h2, jjwt, lombok and test dependencies. There is no `spring-boot-starter-amqp`.
  - The root `docker-compose.yml` runs only Postgres. RabbitMQ is only in `mail-service/docker-compose.yml`.
  - Backend tests are H2-only and have no Testcontainers.
- **Durable pattern already in use (both services):** the database row is the queue. A worker claims a row with a conditional UPDATE, a sweeper retries and recovers stale rows, and a fast path "nudges" the worker after commit.
  - Backend: `EmailDispatcher`, with an in-process executor as the nudge.
  - mail-service: `SendWorker`/`SendSweeper`. RabbitMQ is only the doorbell; the sweeper re-publishes rows that were never enqueued.
  - mail-service also has a real transactional outbox: `EventRecorder` (Propagation.MANDATORY) plus the `WebhookDispatcher` poller.
- **Recommendation for A5:** a transactional outbox table in the backend's own database, filled with one INSERT inside the save transaction, and drained by a `@Scheduled` polling consumer. Claims use conditional UPDATEs. Retries, backoff and stale-claim recovery follow the `EmailDispatcher` pattern. Do not add RabbitMQ to the backend (reasons in §4.6).
- **Dashboard figures**
  - Five backend endpoints, plus three other endpoints the Flutter dashboard combines (invoice tiles, promise tiles, upcoming promises).
  - Every one reads live, overwritten-in-place columns: `invoice.paidAmount`, `invoice.status`, `payment.status`, and `payment_allocations`, which are deleted when a payment is voided.
  - So as-of (B3) cannot come from current tables. History or effective-dated storage is needed first.
  - Region scoping (B1) can go into `ScopeResolver.Scope`: every dashboard query already applies `scope.predicates()`.
- **Notifications** are per user, have no entity or region columns, and are always created inside the caller's transaction. The Flutter bell polls every 30 s.

---

## 1. Background processing infrastructure (backend)

### 1.1 Application bootstrap
`BE/GeneInvoiceApplication.java:7-13`:
```java
@SpringBootApplication
@EnableScheduling
public class GeneInvoiceApplication {
```
- There is no `@EnableAsync`, and no `@Async` anywhere (grep of `BE` found none).
- There is no `ApplicationEventPublisher`, `@TransactionalEventListener`, or `TransactionSynchronization.registerSynchronization` / `afterCommit` anywhere in the backend.
- There is no ShedLock or any other cross-instance scheduler lock.

### 1.2 Configuration (`backend/src/main/resources/application.yml`)

| Key | Line | Value | Note |
|---|---|---|---|
| `spring.jpa.open-in-view` | 9 | `false` | OEMIV is re-registered by `OpenEntityManagerInViewConfig` (see 1.6). |
| `spring.jpa.properties.hibernate.hbm2ddl.halt_on_error` | 24 | `${SCHEMA_HALT_ON_ERROR:true}` | A `ddl-auto: update` failure stops startup. A new NOT NULL column on a table that has rows will stop the app. |
| `spring.task.scheduling.pool.size` | 25-30 | `3` | Comment: "The email sweeper and the promise sweeper each get a thread". |
| `app.mail.dispatch.async` | 58-59 | `true` | |
| `app.mail.dispatch.sweep-interval-ms` | 60 | `60000` | |
| `app.promises.sweep-interval-ms` | — | **not in application.yml** | Defaults to 900000 through the `@Scheduled` placeholder. |
| prod `hikari.maximum-pool-size` | 98-100 | `5` | A background consumer competes with requests for 5 connections. |
| `azurefree` profile | 129-144 | H2 **file** DB, `ddl-auto: update` | A deployment with no broker. |

`backend/src/test/resources/application-test.yml`:
- Line 16: `statement_inspector: com.geneinvoice.CountingStatements`.
- Lines 22-24: `app.promises.sweep-interval-ms: 3600000`. Comment: "Long enough that the background sweeper never races a test; tests call sweepOverdue directly."
- Lines 29-37: `app.mail.transport: none`, `dispatch.async: false` ("Bulk sends are handed over inline, so a test sees the outcome as soon as the request returns"), `dispatch.sweep-interval-ms: 3600000` ("Tests sweep by calling the dispatcher directly").

**Test convention for any new scheduled job:**
- `@Scheduled` jobs are not disabled in tests.
- Each job reads its interval from a property with a default in the placeholder. Tests override it to `3600000`, and `initialDelayString` equals the interval.
- Tests call the job's service method directly.

### 1.3 Scheduled jobs

**`BE/promise/PromiseSweepScheduler.java`**
```java
@Scheduled(fixedDelayString = "${app.promises.sweep-interval-ms:900000}",
        initialDelayString = "${app.promises.sweep-interval-ms:900000}")
public void sweep() {
    try { promiseService.sweepOverdue(); }
    catch (RuntimeException e) { log.warn("Promise sweep failed: {}", e.getMessage()); }
}
@EventListener(ApplicationReadyEvent.class)
public void sweepOnStartup() { sweep(); }
```
- Lines 17-25 and 27-30.
- It also runs at startup, including when the test context starts.
- `PaymentPromiseService.sweepOverdue()` (`BE/promise/PaymentPromiseService.java:491-503`) is one `@Transactional` over all overdue promises. It uses `findOverdueOpen(InvoiceDates.today())`.
- A single failure rolls back the whole sweep, including notifications created inside it.
- Audit rows written from the sweep have `userId=null`: `auditService.record(ENTITY, promise.getId(), "PROMISE_STATUS_CHANGED", previous.name(), target.name(), null, null, reason)` at `PaymentPromiseService.java:265-266`. This is the existing "system actor" convention.
- `notifyBroken` is at `PaymentPromiseService.java:643-657`. It is idempotent through `brokenNotifiedAt` (checked at 644, set at 656 with `Instant.now()`).

**`BE/email/EmailSweepScheduler.java`**
- `@Scheduled(fixedDelayString = "${app.mail.dispatch.sweep-interval-ms:60000}", initialDelayString = same)` at lines 17-18.
- It runs `dispatcher.sweep()` and then `gmailDisconnects.sweep()` in separate try/catch blocks, so one failure does not stop the other (lines 19-30).

**`BE/invoice/InvoiceNumbers.java`**
- `@EventListener(ApplicationReadyEvent.class) @Transactional ensureSequenceRow()` at lines 24-30.
- `next()` is `Propagation.MANDATORY` (32-44). It is the precedent for "must be called inside the caller's transaction".

### 1.4 Hand-made async executors
- `EmailDispatcher` (`BE/email/EmailDispatcher.java:48-54`): `Executors.newSingleThreadExecutor` with a daemon thread named `"email-dispatch"`, a `backgroundOwned` set, `@PreDestroy shutdownNow()` (66-69), and handling of `RejectedExecutionException` ("stay queued for the sweeper", 113-116). The constructor injects `@Value("${app.mail.dispatch.async:true}") boolean async` (56-64).
- `GmailDisconnects` (`BE/email/connection/GmailDisconnects.java:38-42, 47, 87-102`) follows the same shape. It throws `IllegalStateException("Gmail connections must be removed outside a transaction")` at 90-92.

### 1.5 Clock and time
- There is no `Clock` bean in the backend.
- Current-time sources:
  - `InvoiceDates.today()` returns `LocalDate.now(ZoneOffset.UTC)` (`BE/invoice/InvoiceDates.java:14-16`). It is static.
  - `Instant.now()` in `EmailDispatcher` (72, 120, 157, 228, 262), `Notification.onCreate` (`BE/notification/Notification.java:49-52`), `AuditLog.onCreate` (`BE/audit/AuditLog.java:53-56`), `Invoice.onCreate` and `Payment.onCreate`, and `PaymentPromiseService` (194, 656).
  - `TableSchemas.overduePredicate` calls `InvoiceDates.today()` directly (`BE/common/query/TableSchemas.java:80-85`).
- Template to copy for a backend Clock:
  - `MS/config/ClockConfig.java:9-15`: `@Bean public Clock clock() { return Clock.systemUTC(); }`.
  - `mail-service/src/test/java/com/geneinvoice/mail/MovableClock.java`: `set`, `advance`, `reset`, fixed UTC zone.
  - It is registered as `@Bean @Primary MovableClock` in a `@TestConfiguration` imported by the test base (`mail-service/src/test/java/com/geneinvoice/mail/IntegrationTestBase.java:63-76`) and reset in `@BeforeEach` (`clock.set(T0)`, line 100).
- Backend tests use the real time (`PromiseFiguresAndPocTest.java:50`: `static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);`).
- **Recommendation:** new automation and scheduling code should take an injected `Clock` from the start. Retrofitting `InvoiceDates.today()` is a wider change.

### 1.6 Open-EntityManager-in-view and background threads
`BE/config/OpenEntityManagerInViewConfig.java:12-13`:
```java
static final String[] WITHOUT_OPEN_ENTITY_MANAGER = {"/api/emails", "/api/emails/**", "/api/inbox", "/api/inbox/**",
        "/api/me/gmail", "/api/users/*/gmail", "/api/mail-service/**"};
```
- OEMIV is on for every other endpoint (20-24).
- Scheduled jobs and executor threads never pass through this interceptor, so there is no EntityManager outside an explicit transaction.
- Any lazy association (`Invoice.customer`, `Payment.allocations`, `User.role`) touched outside a `TransactionTemplate` or `@Transactional` block in a consumer throws `LazyInitializationException`.
- Any new endpoint that makes slow remote calls synchronously must be added to this exclusion list.

### 1.7 Security context in background threads
`BE/auth/CurrentUser.java:12-18`:
```java
public User require() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails p)) {
        throw new BadCredentialsException("Not authenticated");
    }
```
- On a scheduler or consumer thread, `require()`, `isCustomer()` and `customerIdOrNull()` throw. The null-safe variant is `idOrNull()` (24-29), used for example by `PaymentService.record` audit at `BE/payment/PaymentService.java:102`.
- Services that call `require()` and would therefore fail from an automation consumer:
  - `ScopeResolver` (every method: `BE/poc/ScopeResolver.java:34, 40, 46, 69, 90, 112`).
  - `PaymentPromiseService.create` (94, 99).
  - `PocService.notifyAssignee` (`BE/poc/PocService.java:269`).
  - `EmailService.save` (`sentByUserId(currentUser.require().getId())`, `BE/email/EmailService.java:277`).
  - `DashboardService` (`coverage()`, `requireStaff()`).
- Tests set the principal with `actAs(User)` or `as(User)` (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java:132-144`). That builds an `AppUserDetails` with `AppUserDetailsService.buildAuthorities(user)`, i.e. `ROLE_<name>` plus the privilege names (`BE/auth/AppUserDetailsService.java:32-38`).
- An automation consumer must either run the action with an explicit actor (new internal service methods taking `Long actorUserId`), or set a system or rule-owner `AppUserDetails` in `SecurityContextHolder` for the duration of the action and clear it in `finally`.

---

## 2. Existing durable patterns to copy

### 2.1 Backend: `EmailDispatcher`, a row-as-queue with claim, in-process nudge and sweeper
- **Row state.** `Email.status` moves QUEUED to SENDING, and `nextAttemptAt`, `handedOffAt`, `attempts`, `updatedAt` are tracked. The index is `@Index(name = "idx_email_dispatch", columnList = "status,next_attempt_at")` (`BE/email/Email.java:17`). `updatedAt` is set by `@PrePersist`/`@PreUpdate` (146-158).
- **Claim.** A conditional UPDATE, portable across H2 and Postgres (`BE/email/EmailRepository.java:32-42`):
```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update Email e
           set e.status = com.geneinvoice.email.EmailStatus.SENDING,
               e.attempts = e.attempts + 1,
               e.updatedAt = :now
         where e.id = :id and e.status = com.geneinvoice.email.EmailStatus.QUEUED
           and e.handedOffAt is null
           and (e.nextAttemptAt is null or e.nextAttemptAt <= :due)
        """)
int claim(@Param("id") Long id, @Param("due") Instant due, @Param("now") Instant now);
```
  - The due query `findDue(now, createdBefore, Pageable)` is at 44-52.
  - The stale-work query `findStaleSending(touchedBefore, Pageable)` is at 54-61.
  - The pessimistic lock is `@Lock(LockModeType.PESSIMISTIC_WRITE) findByIdForUpdate` at 28-30.
- **`dispatch(emailId, now)`** (`EmailDispatcher.java:75-95`) runs in three phases:
  1. It refuses to run inside a transaction: `if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Email must be dispatched outside a transaction")` (76-78).
  2. It claims in its own transaction through `transactions.execute(status -> claim(emailId, now))` (79).
  3. It makes the remote call with no transaction open (84), then records the outcome in a separate transaction (86-94).
- **Nudge.** `dispatchAll(ids)` (97-117) runs the ids inline when `async=false`. Otherwise it adds them to `backgroundOwned` and submits them to the executor.
- **Sweeper.** `sweep(now)` (123-142):
  - Rows stuck in SENDING for more than `STALE_SENDING = 10 min` are marked FAILED/interrupted (124-133).
  - Due rows are picked only if created more than `SETTLE = 30 s` ago, so the nudge is not raced, and rows in `backgroundOwned` are skipped (134-141).
  - The batch size is `SWEEP_BATCH = 200` (42).
- **Retry and backoff.** `failed(...)` (255-279): a transient failure with `attempts < MAX_ATTEMPTS(3)` goes back to QUEUED with `nextAttemptAt = now + (attempts<=1 ? 1m : 5m)` (259-265); otherwise the row becomes FAILED.
- **Call sites.**
  - `EmailService.send` saves in a `TransactionTemplate` and then calls `dispatcher.dispatch(id)` synchronously on the request thread (`BE/email/EmailService.java:226-227`). The remote read timeout is 40 s (`application.yml:66`), so this is not "never slow" behaviour and must not be reused as-is for automation.
  - `bulk` calls `dispatchAll` at 255.
  - `retry` calls `dispatch` at 318.

### 2.2 mail-service: RabbitMQ as the doorbell, the database as the truth
- **Topology** (`MS/config/RabbitTopology.java`):
  - `@ConditionalOnProperty(prefix="mail", name="queue", havingValue="rabbit", matchIfMissing=true)` (22-24).
  - A durable direct exchange and queue `mail.send`, with dead-lettering to `mail.dead` and the `mail.send.dead` queue (44, 47).
  - Retry queues `mail.send.retry.1m` and `.5m` use a TTL and dead-letter back to `mail.send` (58-64).
  - The listener factory uses prefetch 2, `AcknowledgeMode.AUTO`, `defaultRequeueRejected(false)`, and a Jackson converter (71-84).
- **`SendQueue` port** (`MS/message/SendQueue.java:5-12`): `record Item(long id)`, `enqueue(long id)`, `enqueueRetry(long id, Duration delay)`. Only the id is published, never a payload.
  - `RabbitSendQueue` implements it (23-33).
  - Tests swap in `RecordingSendQueue`, which has a `failing` switch simulating a broker outage (`mail-service/src/test/java/com/geneinvoice/mail/RecordingSendQueue.java:16-28`), with `mail.queue: direct` set in `mail-service/src/test/resources/application-test.yml:14-15`.
  - `MailProperties.Queue { RABBIT, DIRECT }` is declared at `MS/config/MailProperties.java:18`, but there is **no** DIRECT implementation in main code; only the test bean exists.
- **Publish after commit, tolerant of failure** (`MS/message/MessageService.java`):
  - `submit` saves in `transactions.execute(...)` and then calls `publish(saved.queued())` (65-76).
  - `publish` (102-116) enqueues each id. A failure is logged with "the sweeper will", and ids that were published get `markEnqueued(ids, now)`.
- **Sweeper** (`MS/message/SendSweeper.java:36-62`):
  - Stale SENDING rows (more than 10 min) become FAILED with `deliveryUncertain=true`.
  - `findUnpublished(now, now - REPUBLISH_AFTER(2m))` (`MS/message/MailMessageRepository.java:45-53`) re-publishes QUEUED rows whose `enqueuedAt` is null or old.
  - This is why "nothing is lost if the broker or consumer is down": the database row is the queue.
- **Worker** (`MS/message/SendWorker.java`):
  - `process(id)` refuses to run inside a transaction (69-71). The claim is a conditional UPDATE (`MailMessageRepository.java:22-32`), which makes a duplicate delivery a no-op.
  - Test: `aQueueMessageDeliveredTwiceSendsOnce` (`mail-service/src/test/java/com/geneinvoice/mail/message/SendWorkerTest.java:135-145`).
  - The database state is written first, then `queue.enqueueRetry(...)`, with the sweeper as fallback (218-246). An interrupted worker is un-claimed (248-255).
  - Poison messages: `SendListener` throws `AmqpRejectAndDontRequeueException`, which sends the message to the dead queue (`MS/message/SendListener.java:21-29`).
- **Transactional outbox (the exact A5 shape)**:
  - `MS/events/EventRecorder.java:28-43`:
```java
@Transactional(propagation = Propagation.MANDATORY)
public void record(String type, String ownerRef, String externalId, Object data) {
    ... events.save(MailEvent.builder().type(type).ownerRef(ownerRef).externalId(externalId)
            .payload(payload).createdAt(clock.instant()).build());
}
```
  - `MailEvent` (`MS/events/MailEvent.java`): `type` is a String(40) and not an enum, `payload` is `text`, and it has `deliveredAt`, `attempts` and `lastError`. The index is `(delivered_at,id)` (line 9).
  - Poller `WebhookDispatcher` (`MS/events/WebhookDispatcher.java`):
    - It runs as `@Scheduled(fixedDelayString="${mail.webhook.interval-ms:1000}")` (60-67) and `synchronized dispatch()` (69-94).
    - It loads `findUndelivered(PageRequest)` in its own transaction.
    - On failure it calls `markFailed` and backs off exponentially from 1 s to 5 min (81-82). On success it calls `markDelivered`.
    - It deletes delivered rows older than 7 days daily (100-105).
- **Tests** call jobs directly and set intervals to 3600000 (`mail-service/src/test/resources/application-test.yml:16-28`). The `@BeforeEach` resets queue, clock and dispatcher backoff fields (`IntegrationTestBase.java:92-105`).

### 2.3 Backend inbound consumer (webhook from mail-service)
- `BE/email/mailservice/MailServiceEventsController.java:85-100` processes events one by one.
- If `MailServiceEventHandler.databaseUnavailable(e)` (`BE/email/mailservice/MailServiceEventHandler.java:74-84`: checks for `TransientDataAccessException`, `DataAccessResourceFailureException`, `TransactionException`, and SQL transient, recoverable and non-transient-connection exceptions), it stops and returns 503 so the batch is re-sent. Any other exception is a poison event: it is logged and skipped.
- Reuse `databaseUnavailable` in the automation consumer to tell "stop and retry the batch" apart from "mark this event failed".

---

## 3. Dashboard: every figure and its query

### 3.1 Backend endpoints (`BE/dashboard/DashboardController.java`)
- `@RestController @RequestMapping("/api/dashboard") @RequiredArgsConstructor` (14-17).
- The date comes from `private static LocalDate today() { return InvoiceDates.today(); }` (52-54). This is the single place to swap in `asOf`.

| # | Endpoint | Guard | Service |
|---|---|---|---|
| D1 | `GET /billed-by-month?months=12` (21-25) | `hasAuthority(INVOICE_VIEW)` | `billedByMonth(months, today)` |
| D2 | `GET /outstanding-by-age` (27-31) | `INVOICE_VIEW` | `outstandingByAge(today)` |
| D3 | `GET /top-outstanding-customers?limit=5` (33-37) | `INVOICE_VIEW`, plus `requireStaff()` | `topOutstanding(limit)`, which takes **no date** |
| D4 | `GET /collected-by-month?months=12` (39-43) | `PAYMENT_VIEW` | `collectedByMonth(months, today)` |
| D5 | `GET /top-paying-customers?months=12&limit=5` (45-50) | `PAYMENT_VIEW`, plus `requireStaff()` | `topPaying(months, limit, today)` |

- The guard convention to copy is `@PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")`.
- There is no dashboard-specific privilege.

**DTOs** (`BE/dashboard/DashboardDtos.java`) are nested records in a holder class:
```java
public enum Coverage { ALL, BOOK, OWN }
public record MonthPoint(String month, BigDecimal amount, long count) {}
public record MonthlySeries(Coverage coverage, List<MonthPoint> months) {}
public record AgeBucket(String label, Integer fromDays, Integer toDays, BigDecimal amount, long count,
                        LocalDate dueDateFrom, LocalDate dueDateTo) {}
public record OutstandingByAge(Coverage coverage, List<AgeBucket> buckets) {}
public record OutstandingCustomer(Long customerId, String customerName, BigDecimal outstanding,
                                  long openInvoices, Instant oldestInvoiceDate) {}
public record TopOutstanding(Coverage coverage, List<OutstandingCustomer> customers) {}
public record PayingCustomer(Long customerId, String customerName, BigDecimal collected,
                             long payments, Instant lastPaidAt) {}
public record TopPaying(Coverage coverage, List<PayingCustomer> customers) {}
```

**Service** (`BE/dashboard/DashboardService.java`):
- `@Service @RequiredArgsConstructor`, with `@PersistenceContext private EntityManager em;` and the collaborators `ScopeResolver` and `CurrentUser` (39-63).
- Every public method is `@Transactional(readOnly = true)` and builds a raw JPA Criteria query. It does not use `TableQueryExecutor`, so dashboard endpoints accept no `f=` filters.
- Limits: `MAX_MONTHS = 24` and `MAX_LIMIT = 20` (43-44). A value out of range throws `BadRequestException` (224-240), which maps to 400.
- Ageing buckets `AGES` (50-57): "Not yet due" (null to 0), "1–30 days", "31–60 days", "61–90 days", "Over 90 days" (91 to null).

**D1 billedByMonth (66-81)**
- Root `Invoice`, scope `scopeResolver.forInvoices()`.
- WHERE: scope predicates, `status <> CANCELLED`, and `invoiceDate` within [start of first month UTC, start of month after last) (`within`, 246-249; `startOf` 242-244 uses `ZoneOffset.UTC`).
- SELECT: for each month, `Aggregates.sumWhen(inMonth, total)` and `countDistinct(case when inMonth then id)` (256-267). That is 2×months columns in one row, so up to 48 CASE expressions.
- Result: `points(window, row)` (269-276). `coverage(scope)`.

**D2 outstandingByAge (84-109)**
- Root `Invoice`. `balance = total - paidAmount` (291-293).
- `open()` adds `status <> CANCELLED` and `balance > 0` (295-299).
- For each bucket: `sumWhen(overdueBy(dueDate, age, today), balance)` and `countWhen(...)`.
- `overdueBy` (278-289): `dueDate <= today - fromDays` and `dueDate >= today - toDays`. The "Not yet due" bucket also includes `dueDate IS NULL`.
- It returns `dueDateFrom = today - toDays` and `dueDateTo = today - fromDays` for drill-down.

**D3 topOutstanding (112-134)**
- Staff only (`requireStaff`, 218-222, throws `AccessDeniedException("Not allowed")`, which maps to 403).
- Root `Invoice` joined to `customer`. `open()` plus scope.
- SELECT customer id and name, `sum(balance)`, `count(id)`, `least(invoiceDate)`. GROUP BY customer, ORDER BY sum desc then name asc, `setMaxResults(limit)`.
- `asInstant` (319-325) normalises Instant, Timestamp and OffsetDateTime results (H2 and Postgres differ).

**D4 collectedByMonth (137-149)** and **D5 topPaying (152-175)** share `collected(cq, cb)` (177-201):
- **Book mode** (`paidAgainstBookOnly()` is true when `forInvoices().lockedFilters()` is non-empty, 203-205):
  - Root `PaymentAllocation` joined to payment and invoice. `payment.status = ACTIVE`.
  - `invoice.id IN (subquery of invoices in forInvoices scope)`, OR `payment.id IN (subquery of payments in forPayments scope)` when the payment scope has predicates (`idsInScope`, 311-317).
  - Amount is `alloc.amount`, date is `payment.paidAt`, customer is `invoice.customer`.
- **Otherwise:** root `Payment` with the `forPayments` scope, `status = ACTIVE`, amount `payment.amount`, and `payment.customer`.
- D4 selects per-month sums and distinct payment counts.
- D5 groups by customer: `sum(amount)`, `countDistinct(paymentId)`, `greatest(paidAt)`, ordered desc, then `setMaxResults(limit)`.
- `paymentCoverage()` (207-211) returns BOOK in book mode, otherwise `coverage(forPayments)`.

**Coverage** (213-216): OWN for customer users. Otherwise ALL if `lockedFilters` is empty, else BOOK.

**Scope helper** (301-309) applies each `PredicateFactory` in the scope to the root:
```java
private static List<Predicate> scoped(Root<?> root, CriteriaQuery<?> cq, CriteriaBuilder cb, ScopeResolver.Scope scope) {
    List<Predicate> out = new ArrayList<>();
    scope.predicates().forEach(f -> { Predicate p = f.build(root, cq, cb); if (p != null) out.add(p); });
    return out;
}
```

**Helpers** (`BE/common/query/Aggregates.java:13-31`): `countWhen`, `sumWhen` (both coalesce to 0), `asLong`, and `asMoney` (scale 2, HALF_UP).

### 3.2 Other endpoints the Flutter dashboard combines

| # | Figure(s) | Endpoint | Code |
|---|---|---|---|
| D6 | "Outstanding" tile; "Invoices by status" donut (paid / unpaid / partly paid / cancelled counts) | `GET /api/invoices/summary` (INVOICE_VIEW) — `BE/invoice/InvoiceController.java:58-64` | `InvoiceService.tiles(TableQuery)` (`BE/invoice/InvoiceService.java:319-350`) |
| D7 | "Open promises" and "Broken promises" tiles; "Promises by status" donut | `GET /api/promises/summary` (PROMISE_VIEW) — `BE/promise/PaymentPromiseController.java:66-72` | `PaymentPromiseService.tiles` (`PaymentPromiseService.java:558-586`) |
| D8 | "Upcoming promises" card (next 10, plus the `book` flag from `lockedFilters`) | `GET /api/promises?size=10&sort=promisedDate,asc&filter=status:in:OPEN,PARTIALLY_KEPT&filter=promisedDate:gte:<today>` | `PaymentPromiseService.page` (537-544) |

Details for D6 and D7:
- **D6** uses `queryExecutor.aggregate(Invoice.class, TableSchemas.INVOICES, query, scope.predicates(), selections)`. It returns count, sum(total), sum(paid), live balance (cancelled counts as 0), counts per status, unassigned salesPoc count, and overdue amount/count through `TableSchemas.invoiceOverdue(root, cb, today)` (`TableSchemas.java:72-78`). The DTO is `InvoiceSummaryTiles(count, totalBilled, totalPaid, outstanding, unpaidCount, ...)` (`BE/invoice/InvoiceDtos.java:130-...`).
- **D7** returns counts and amounts per status plus promised and fulfilled amounts. The DTO is `PromiseSummaryDto` (`BE/promise/PromiseDtos.java:72-80`).

`TableQueryExecutor.aggregate` (`BE/common/query/TableQueryExecutor.java:89-99`) is `@Transactional(readOnly=true)` and runs the scope predicates plus the URL filters through `predicates(...)`. `run`, `count`, `inScope` and `ids` (30-86) do the same. Region scoping injected into `Scope.predicates()` therefore reaches lists, counts, tiles and bulk ids through this one path.

Frontend-derived figures (`FE/features/dashboard/dashboard_screen.dart:242-291`):
- "Billed" is the sum of the D1 series (`MonthlySeries.total`, `dashboard_providers.dart:41`).
- "Collected"/"Paid" is the sum of D4.
- "Outstanding" is `invoiceSummary['outstanding']`.
- "Open promises" is `openCount + partiallyKeptCount` and the matching amounts.
- "Broken promises" is `brokenCount` and `brokenAmount`.

### 3.3 Scope resolution (feeds B1)
`BE/poc/ScopeResolver.java`:
- `public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters)` (27-31). `PredicateFactory` is `Predicate build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb)` (`BE/common/query/PredicateFactory.java:8-11`).
- `forInvoices()` (51-54) uses the `salesPoc` book, and `forPayments()`/`forPromises()` (56-64) use the `collectionPoc` book, all through `build(...)` (111-129):
  - A customer user is limited to their own customer.
  - `canSeeEverything()` (the global `SCOPE_OVERRIDE` privilege, 39-43) gives an empty scope.
  - A user not assignable as that POC type gets `nothing(...)`, i.e. `cb.disjunction()` (131-133).
  - Otherwise the scope is the user's own book, and `lockedFilters = ["salesPocUserId:eq:<me>"]` or the collection equivalent.
- Every privilege check is a database query: `userRepository.hasPrivilege(userId, privilege)` (`BE/user/UserRepository.java:60`), made through `canSeeEverything` and `isAssignableAs`.
- `DashboardService` calls `scopeResolver.forInvoices()` several times per request (`paidAgainstBookOnly()` for example), so each call repeats those queries.

### 3.4 B1 (regions) extension points for the dashboard
1. **Put the region predicate inside `ScopeResolver.Scope`.** For a regions-in-scope set R (the regions requested, intersected with the regions where the user holds the view right): `(root,q,cb) -> root.get("region").get("id").in(R)`.
   - D1, D2, D3, D6, D7 and D8 then pick it up with no code change.
   - D4 and D5 pick it up in both branches: the book branch applies scope inside `idsInScope` subqueries over `Invoice` and `Payment`, the other branch applies it to the `Payment` root.
   - Precondition: `Invoice`, `Payment`, `Customer` and `PaymentPromise` must all resolve the same attribute path. The existing factories already assume `root.get("customer")` exists.
2. **Per-region rights replace global ones.**
   - `@PreAuthorize("hasAuthority(INVOICE_VIEW)")` stays as a coarse gate, meaning "holds it in at least one region".
   - The fine check is the region set in the scope. `canSeeEverything()` (SCOPE_OVERRIDE) and `isAssignableAs()` (the POC book) must become per region: "the POC book is per region".
3. **Coverage must change.**
   - `Coverage.ALL` currently means "whole organisation". Echo the regions actually covered: add `List<Long> regionIds` (and `LocalDate asOf`) to each response record, or a common `DashboardScope` record.
   - Mixed coverage is possible: BOOK in region A and ALL in region B.
   - Frontend risk: `_coverage()` maps any unknown wire value to `Coverage.all` (`FE/features/dashboard/dashboard_providers.dart:10-14`). Adding a new enum value such as `REGIONS` would silently show as "whole organisation" with no badge.
4. **Request parameters.** Add `@RequestParam(required=false) List<Long> region` and `@RequestParam(required=false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf` to D1-D5, and pass them through the summary endpoints and `FilterParams`. A malformed value becomes 400 through `GlobalExceptionHandler` `MethodArgumentTypeMismatchException` (`BE/common/GlobalExceptionHandler.java:104-107`).

### 3.5 B3 (as-of) obstacles in the current data model
The figures cannot be recomputed for a past date from today's tables:

| Needed as of D | Why current data is insufficient | Evidence |
|---|---|---|
| Invoice balance (D2, D3, D6 "outstanding") | `paidAmount` is overwritten in place. A payment void removes the allocations and subtracts. | `PaymentService.reverseAllocations` `p.getAllocations().clear()` (`BE/payment/PaymentService.java:220-242`, clear at 234). `Payment.allocations` is `orphanRemoval = true` (`BE/payment/Payment.java:55-57`). |
| Payment active at D (D4, D5) | Status flips to VOIDED with no `voidedAt`. | `voidPayment` at `PaymentService.java:205-218` (`setStatus(VOIDED)` at 214). `PaymentStatus {ACTIVE, VOIDED}`. |
| Payment amount at D | `updateAmount` rewrites the amount. | `PaymentService.java:244-...` |
| Invoice total at D | `replaceItems` recomputes the total. | `InvoiceService.java:393-...` |
| Invoice cancelled at D | Status flips to CANCELLED with no timestamp. | `InvoiceService.cancel` (352-...) |
| Allocation timing | `PaymentAllocation` has no timestamp column. | `BE/payment/PaymentAllocation.java:10-31` |
| Invoice existed at D | `invoiceDate` is user-set and can be backdated, while `createdAt` is `updatable=false`. "Billed as of D" must also filter `createdAt <= D`, or a backdated invoice created after D appears in a past figure. | `Invoice.java:36, 74-78` |
| Region of the record at D | The region column does not exist yet. | — |
| Approvals outstanding at D | No approval model exists. | — |
| Audit JSON | `audit_logs.before_json`/`after_json` are `@Lob TEXT`. They cannot be aggregated in SQL, so they are not viable as a history source for dashboards. | `BE/audit/AuditLog.java:33-39` |

- **Recommendation for dashboard as-of:** introduce effective-dated storage. Either SCD2 `*_history` tables (`valid_from`, `valid_to`, `region_id`, `total`, `paid_amount`, `status`), or an append-only money ledger (`invoice_ledger`: `invoice_id`, `region_id`, `at`, `delta_total`, `delta_paid`, `status_after`) written in the same transaction as each money movement.
- Each D-query then gets a second code path: when `asOf != null`, query the history view with `valid_from <= asOf < valid_to`.
- Because `DashboardService` builds Criteria against entity classes, map the history tables as entities (for example `InvoiceVersion`) so the same `scoped(...)` helper works.
- The region predicate must use the history row's `region_id` at D, not the current one ("which region a record belonged to then").
- **B2 (pending changes).** Keep pending changes out of the entity tables, in a separate pending-change table. Every D-query then stays correct without change ("visible ... without having taken effect"). If pending values were written onto the entity with a flag, every query listed here would need an extra filter.

### 3.6 Dashboard risks
- `TableSchemas.overduePredicate` (80-85) and the frontend drill-down links use today, so a drill-down from an as-of dashboard would show today's list. The `asOf` and `region` parameters must travel with the links (see 5.1).
- The prod connection pool is 5. Adding as-of history joins to D1 (48 CASE columns) will be heavy. Consider daily snapshot tables if the history queries are slow.
- `topOutstanding` has no `today`/`asOf` parameter today. Add one rather than reading live balances.

---

## 4. Recommendation for PRD A5: transactional outbox plus polling consumer (backend)

### 4.1 Requirements this must meet
- A rule never slows or breaks the user's save.
- Nothing is lost if the consumer is down.
- A rule can be scheduled or run from the UI on demand.
- It must be testable on H2.

### 4.2 Publisher (inside the save transaction, one INSERT)
Copy the shape of mail-service `EventRecorder` (`MS/events/EventRecorder.java:28-43`):
```java
@Component @RequiredArgsConstructor
public class AutomationEvents {
    private final AutomationEventRepository events; private final Clock clock;
    @Transactional(propagation = Propagation.MANDATORY)
    public void published(String entityType, Long entityId, String kind, Long regionId, Long actorUserId, String changedFields) {
        events.save(AutomationEvent.builder()...occurredAt(clock.instant()).status("PENDING").attempts(0).build());
    }
}
```
- **Why MANDATORY.** The event commits atomically with the save, so no event is lost if the JVM dies after commit. It also fails fast if someone calls it outside a transaction. The precedent in the backend is `InvoiceNumbers.next()` at `BE/invoice/InvoiceNumbers.java:32`.
- **Keep the insert trivially safe so it cannot break the save:**
  - No FK to customers, invoices, payments or users. `CustomerService.delete` exists (`BE/customer/CustomerService.java:299-300`), and `IntegrationTestBase` deletes users and customers (see §6).
  - No unique constraint.
  - Store `kind` and `status` as `String`, like `Notification.type` (length 40) and `MailEvent.type`. A `@Enumerated(STRING)` column gets a CHECK constraint that `ddl-auto: update` never widens; the workaround is `EmailSchemaUpgrade.widen(connection, table, column, EnumClass)` at `BE/email/EmailSchemaUpgrade.java:40-80`.
  - Any text written must be truncated first (the `fit(...)` idiom: `NotificationService.java:27-30`, `AuditService.java:34-40`).
  - Do not evaluate rules or load related data in the publisher.
- **Where to call it (write paths in the PRD's trigger scope):**
  - `InvoiceService`: `create` (59-60), `update` (101-102), `reassignSalesPoc` (220-221), `cancel` (352-353), `cancelWithRefund` (377-378), `replaceItems` (393-394).
  - `PaymentService`: `record` (54-55), `update` (111-112), `reassignCollectionPoc` (135-136), `voidPayment` (205-206), `updateAmount` (244-245).
  - `PaymentService.record`, `voidPayment` and `updateAmount` also change invoices (`paidAmount` and status), so "Invoice updated" must be published for each touched invoice.
  - `CustomerService`: `create` (204-205), `update` (250-251), and credit-balance changes made inside payment flows.
  - Also the POC-seat changes in `PocService` and dispute approvals that apply changes.
- **Coalescing.** Consider de-duplicating `(entityType, id, kind)` per transaction with a transaction-bound set flushed in `TransactionSynchronization.beforeCommit`. Note there is no existing use of transaction synchronizations in the backend.
- **Contrast with today's practice.** `promiseService.reevaluateForCustomer` runs synchronously inside the payment save (`PaymentService.java:107, 216`) and can fail it. Automation must not follow that precedent.

### 4.3 Suggested table (created by `ddl-auto: update`; no SchemaUpgrade needed for a new table)
```java
@Entity
@Table(name = "automation_events", indexes = {
  @Index(name = "idx_automation_event_due", columnList = "status,next_attempt_at"),  // mirrors idx_email_dispatch
  @Index(name = "idx_automation_event_entity", columnList = "entity_type,entity_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AutomationEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable=false, length=20) private String kind;          // CREATED / UPDATED / SCHEDULED / MANUAL
  @Column(name="entity_type", length=20) private String entityType; // CUSTOMER / INVOICE / PAYMENT
  @Column(name="entity_id") private Long entityId;
  @Column(name="region_id") private Long regionId;                 // region at publish time (B1/B3)
  @Column(name="rule_id") private Long ruleId;                     // for SCHEDULED/MANUAL
  @Column(name="actor_user_id") private Long actorUserId;
  @Column(name="changed_fields", length=500) private String changedFields;
  @Column(name="occurred_at", nullable=false) private Instant occurredAt;
  @Column(nullable=false, length=12) private String status;        // PENDING/PROCESSING/DONE/FAILED
  @Column(nullable=false) private int attempts;
  @Column(name="next_attempt_at") private Instant nextAttemptAt;
  @Column(name="updated_at") private Instant updatedAt;
  @Column(name="last_error", length=1000) private String lastError;
}
```
- Add an idempotency table, for example `automation_runs(rule_id, event_id, action_index)` with a **unique** constraint, written in the same transaction as the action's effect (task, promise, dispute or email rows).
- A re-delivered event (after stale-claim recovery or on multiple instances) then cannot create duplicate tasks or emails. This follows the claim-then-effect idea of `aQueueMessageDeliveredTwiceSendsOnce`.

### 4.4 Consumer (poller)
- **Scheduler**, following the `EmailSweepScheduler` shape:
  - `@Scheduled(fixedDelayString = "${app.automation.poll-interval-ms:5000}", initialDelayString = "${app.automation.poll-interval-ms:5000}")`, wrapped in try/catch with `log.warn`.
- **`AutomationConsumer.drain(Instant now)`**, using `TransactionTemplate` like `EmailDispatcher`:
  1. In a short transaction, load due ids: `status='PENDING' and (nextAttemptAt is null or <= now)`, ordered by id, paged (batch of about 200, like `SWEEP_BATCH`).
  2. For each id, claim with a conditional UPDATE copied from `EmailRepository.claim`: `set status='PROCESSING', attempts=attempts+1, updatedAt=:now where id=:id and status='PENDING'`. A result of 0 means another instance or thread has it, so skip.
     - Do not use `SKIP LOCKED`. The codebase never uses it, and H2 support is not verified.
  3. Process the event in its own transaction: load the entity (tolerate a missing one and mark it DONE or SKIPPED), evaluate matching rules, perform actions, insert `automation_runs`, and mark the event DONE.
  4. If the action sends email, commit the Email rows first, then call `emailDispatcher.dispatchAll(ids)` **outside** the transaction (`EmailDispatcher.java:76-78` throws if called inside one).
  5. On exception:
     - If `MailServiceEventHandler.databaseUnavailable(e)`, stop the batch and leave the row for recovery.
     - Else if `attempts < max`, set it back to PENDING with a backoff (1 min then 5 min, like `EmailDispatcher.failed` 259-265).
     - Else set FAILED with `lastError` (fit to 1000). It is shown in the UI with a "retry" action that re-queues it.
  6. **Stale recovery:** rows in PROCESSING with `updatedAt < now - 10 min` go back to PENDING (the `findStaleSending` pattern). This is safe because of the `automation_runs` unique key.
- **Optional low-latency nudge:**
  - After the save commits, register an `afterCommit` synchronization that submits `drain` to a single-thread executor. Gate it with `app.automation.async`, the same as `app.mail.dispatch.async`.
  - Keep the `SETTLE` idea (the sweeper skips rows newer than 30 s) and the `backgroundOwned` set so the poller does not race the nudge.
- Run each action as an explicit actor. `CurrentUser.require()` throws on this thread (§1.7). Use `null` or the rule owner as `userId` in `AuditService.record(...)`, which is the existing system-actor convention.
- **Thread pool:** raise `spring.task.scheduling.pool.size` from 3. With promise, email, automation poller and schedule ticker there are 4 or more fixed-delay jobs, and the yml comment assumes one thread per job.

### 4.5 Scheduled and on-demand rules
- **Ticker:** `@Scheduled(fixedDelayString = "${app.automation.tick-interval-ms:60000}")`.
  - Select rules with `nextRunAt <= now` and claim each with a conditional UPDATE `set nextRunAt = :next where id = :id and nextRunAt = :expected`. This is safe with multiple instances, which matters because there is no ShedLock.
  - Insert one `SCHEDULED` outbox event in the same transaction.
  - Missed runs while the app was down produce a single catch-up run, not N runs.
  - Compute `next` from a daily or weekly spec with an injected `Clock`.
- **Run from UI:** `POST /api/automation/rules/{id}/run`, `@Transactional`, inserts a `MANUAL` event and returns 202 with the event id. The UI polls the event status.
  - This path must not execute rules synchronously, and then it needs no OEMIV exclusion.
  - Precedent for an admin-triggered batch endpoint: `POST /api/promises/recompute`, guarded by the PROMISE_OVERRIDE privilege (`BE/promise/PaymentPromiseController.java:56-64`).
- **B3:** "rule evaluation as of a date" is a dry-run endpoint that evaluates conditions against the history tables from §3.5 and writes nothing.

### 4.6 Why not RabbitMQ in the backend
1. **No dependency or infrastructure.** `backend/pom.xml` has no AMQP. The root `docker-compose.yml` has no broker. The `azurefree` profile (`application.yml:129-144`) runs on an H2 file DB, and a broker would be new mandatory infrastructure for every deployment.
2. **Dual write.** Publishing inside the save transaction either loses events (publish after commit fails, nothing records it) or creates phantom events (publish before commit, then rollback).
   - mail-service shows the only safe answer: the database row is the truth and the sweeper re-publishes (`SendSweeper.java:56-61`, `findUnpublished`). An outbox table is needed anyway, and RabbitMQ would only add latency reduction.
3. **"Never slow the save".** A synchronous `RabbitTemplate.convertAndSend` on the request thread blocks on connect or timeout when the broker is down. Even mail-service only publishes after commit and swallows failures (`MessageService.java:102-116`).
4. **Tests.** Backend tests are H2-only with no Testcontainers. mail-service tests themselves bypass RabbitMQ (`queue: direct` plus `RecordingSendQueue`), and its DIRECT mode has no main-code implementation.
5. **Future-proofing.** Put a port such as `AutomationQueue { void nudge(long eventId); }` in front of the poller, like `SendQueue`. A RabbitMQ doorbell can be added later without changing the durability model.

### 4.7 Tests for A5
- `application-test.yml`: set `app.automation.poll-interval-ms: 3600000`, `app.automation.tick-interval-ms: 3600000` and `app.automation.async: false`. Tests call `consumer.drain(now)` and `ticker.tick(now)` directly.
- Add a `Clock` bean (§1.5) and a `MovableClock` `@Primary` test bean, imported into `IntegrationTestBase`, which already does `@Import(RecordingMailTransport.Config.class)` (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java:41-45`).
- **"Consumer down" test:** save an entity, assert an `automation_events` row is PENDING and no effect exists, then `drain()` and assert the effect.
- **"Never breaks the save" test:** make the action throw, assert the HTTP save returned 2xx and the event is FAILED with `lastError`.
- **Duplicate delivery test:** reset the row to PENDING and call `drain()` twice; assert one task.
- **"Refuses to run inside a transaction" test**, like `theWorkerRefusesToRunInsideATransaction` (`SendWorkerTest.java:442-450`).
- `CountingStatements.reads(table, body)` (`backend/src/test/java/com/geneinvoice/CountingStatements.java:14-27`) can assert that the save path issues no rule-table SELECTs.

---

## 5. Notifications

### 5.1 Backend model and API
**Entity** (`BE/notification/Notification.java`):
- Table `notifications`, indexes `idx_notif_user (user_id)` and `idx_notif_user_read (user_id,is_read)` (8-12).
- Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.
- Fields:
  - `userId` (Long, not null, **no FK**).
  - `type` (String 40, not null).
  - `title` (`TITLE_MAX = 200`).
  - `message` (`MESSAGE_MAX = 1000`).
  - `link` (length 300, **not truncated anywhere**).
  - `read` (column `is_read`, `@Builder.Default false`).
  - `createdAt`, set to `Instant.now()` in `@PrePersist` (46-52).
- There is **no entity type, entity id, or region column**.

**Service** (`BE/notification/NotificationService.java`):
```java
@Transactional
public Notification notify(Long userId, String type, String title, String message, String link) {
    return repository.save(Notification.builder().userId(userId).type(type)
            .title(fit(title, Notification.TITLE_MAX)).message(fit(message, Notification.MESSAGE_MAX))
            .link(link).build());
}
```
- `notify` is at 18-25. `fit` (27-30) truncates with "…".
- `notifyAdmins(type, title, message, link)` (32-38) uses `userRepository.findByRoleName("ADMIN")`. It is keyed on a **role name**, not a privilege, and is not region-aware.
- Also: `listFor` (40-43, unused by the controller), `unreadCount` (45-48), `markRead` (50-58, ignores other users' ids), and `markAllRead` (60-63, a `@Modifying` JPQL update in `NotificationRepository.java:14-16`).

**Controller** (`BE/notification/NotificationController.java`), all methods guarded by `hasAuthority(NOTIFICATION_VIEW)`:
- `NotificationDto(id, type, title, message, link, read, createdAt)`, a nested record with a static `from` (35-41).
- `GET /api/notifications` (43-55): a `TableQuery` over `TableSchemas.NOTIFICATIONS` (`TableSchemas.java:304-310`, default sort `createdAt,desc`, columns id, type, title, message, read, createdAt). The scope is `ownedByCaller()` = `cb.equal(root.get("userId"), me)` (103-106). It returns `PageResponse`.
- `GET /unread-count` returns `{"count": n}` (57-61).
- `POST /{id}/read` (63-67).
- `POST /mark-all-read` returns `{"updated": n}` (69-73).
- `POST /bulk` with actions `MARK_READ` and `MARK_UNREAD` through `BulkExecutor`, using `IneligibleException` for rows that are not the caller's or already in that state (75-101). `resolveIds` (108-117).

**Current producers:**

| Type | Where | Recipient | Link |
|---|---|---|---|
| `POC_ASSIGNED` | `PocService.notifyAssignee` (`BE/poc/PocService.java:33, 267-275`); skips if assignee == actor (`currentUser.require()`, 269) | assignee | caller-provided (for example `/payments/{id}`, `PaymentService.java:105-106`) |
| `DISPUTE_OPENED` | `DisputeService` (`BE/dispute/DisputeService.java:37, 82-86`) via `notifyAdmins` | all ADMIN-role users | `/disputes/{id}` |
| `DISPUTE_APPROVED` / `DISPUTE_DENIED` | `DisputeService.notifyCustomerOfResolution` (38-39, 115, 131, 333-339) | the opener | `/disputes/{id}` |
| `PROMISE_BROKEN` | `PaymentPromiseService.notifyBroken` (57, 643-657) | collection POC | `/promises/{id}` |
| `GMAIL_RECONNECT` | `MailServiceEventHandler.connectionChanged` (`BE/email/mailservice/MailServiceEventHandler.java:51-54, 149-167`) | the mailbox owner | `/me/gmail` |

### 5.2 Frontend
- `FE/features/notifications/notifications_providers.dart`:
  - `unreadCountProvider` is a `StreamProvider.autoDispose` over `pollUnreadCount(ref, dio, '/api/notifications/unread-count')` (15-16).
  - `pollUnreadCount` (18-35) fetches immediately and then every **30 s** with `Timer.periodic`, swallowing errors. It is reused by the inbox badge (`FE/features/email/email_providers.dart:8, 54`).
  - `notificationsProvider` (9-13) casts `res.data as List`, but the backend returns a `PageResponse` map. It is **unused**; grep shows no reader. It would throw if anyone used it.
- `FE/features/notifications/notifications_screen.dart`:
  - `DataTableScaffold<AppNotification>` with `entity: 'notifications'` and `path: '/api/notifications'` (35-50).
  - Bulk actions MARK_READ and MARK_UNREAD. `onBulkDone` invalidates `unreadCountProvider` (45).
  - Columns (51-84): Title (bold when unread), Message, **Type as a raw string** (71), Received, Read.
  - "Mark all read" posts and invalidates `tablePageProvider` and `unreadCountProvider` (24-31).
  - Tapping a row (`_open`, 89-100) marks it read, rewrites legacy `/admin/disputes/...` links (96-97), and calls `context.go(link)`.
- Model: `FE/shared/models/notification.dart:1-29` (`AppNotification.fromJson`).
- Bell: `_NotificationsBell` in `FE/shared/widgets/app_shell.dart:203-225`, with a Badge ("99+" cap) that navigates to `/notifications`.
- Route: `FE/core/router.dart:207-213`, `redirect: needs(const [Privileges.notificationView])`.

### 5.3 Extension points
- **A6 Tasks:** notify each assignee with type `TASK_ASSIGNED` and link `/tasks/{id}`, using `NotificationService.notify`. For automation-created tasks, call it inside the consumer's transaction, never in the user's save. The Flutter "Type" column shows raw type strings, so add a label map if needed.
- **B2 approvals:** a pending change should notify approvers, meaning users holding the approval right in the record's region, excluding the maker. Add a privilege- and region-based helper, for example `notifyHolders(String privilege, Long regionId, Long exceptUserId, ...)` backed by a new repository query. Do not copy `notifyAdmins`' role-name lookup. Also notify the maker on approve or reject (the `notifyCustomerOfResolution` shape).
- **B1:**
  - Notifications are not scoped by region. A user who loses rights in a region keeps notifications whose links now 404.
  - Optionally add nullable `entity_type`, `entity_id` and `region_id` columns. `ddl-auto` can add nullable columns; do **not** add NOT NULL, because of `halt_on_error`. The list can then filter by current rights.
  - Recipient choice for region-bound events (dispute opened, promise broken) must become region-aware.
- **Automation "send notification"** is not in the PRD's action list. If it is added, it goes through the same `notify`.

### 5.4 Notification risks
- `notify` is `@Transactional` (REQUIRED) and runs **inside the caller's transaction**. A failure rolls back the business save. For example, a `link` longer than 300 characters is not fit and raises `DataIntegrityViolationException`.
- Inside `sweepOverdue`'s single transaction, one bad notification rolls back the whole sweep.
- `type` has a maximum length of 40. Keep new type names short.

---

## 6. Test-harness gotchas (backend)
- `IntegrationTestBase.resetTransactionalData()` (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java:65-89`) deletes tables in a fixed FK order: gmail, documents, recipients, emails, promises, payments, invoices, POCs, notifications, customers, products, then all users except `admin` and `cashier`.
  - Every new table with an FK to customers, invoices, payments or users (tasks, task assignees, rules, approvals, region history) **must be added before its parents**, or every test's `@BeforeEach` fails.
  - Rules and tasks referencing users would block user deletion.
  - Outbox rows are safe if they have no FKs, but still clear them to isolate tests.
  - `audit_logs` is never cleared.
- `@Scheduled` jobs are live in tests, so a new job without a `3600000` override in `application-test.yml` will race tests.
  - `PromiseSweepScheduler.sweepOnStartup` runs when every test context starts.
- The mail transport in tests is `RecordingMailTransport` (`Mode.NOT_CONFIGURED` by default, `@Primary` through `RecordingMailTransport.Config`, `backend/src/test/java/com/geneinvoice/email/RecordingMailTransport.java:228-234`). Email actions in automation tests go through it.

---

## 7. Frontend dashboard: extension map for B1 and B3
**`FE/features/dashboard/dashboard_providers.dart`**
- State: `dashboardMonthsProvider = StateProvider<int>((ref) => 12)` (152). It is global and not autoDispose, so the selection persists. Add sibling `dashboardRegionsProvider` (a set of ids) and `dashboardAsOfProvider` (a nullable date) the same way.
- Every provider is a `FutureProvider.autoDispose` using `ref.watch(dioProvider).get(path, queryParameters: {...})`:
  - `billedByMonthProvider` (159-165) and `collectedByMonthProvider` (167-173) send `months`.
  - `outstandingByAgeProvider` (175-178) and `topOutstandingProvider` (180-183) send **no parameters**.
  - `topPayingProvider` (185-191) sends `months`.
  - `invoiceSummaryProvider` (193-196) calls `/api/invoices/summary` and `promiseSummaryProvider` (198-201) calls `/api/promises/summary`.
  - `upcomingPromisesProvider` (220-234) sends `filter: status:in:OPEN,PARTIALLY_KEPT` and `promisedDate:gte:<todayUtc()>`, and derives `book` from `lockedFilters`.
  - Every one must `ref.watch` the new region and as-of providers and send them.
- `refreshDashboard(ref)` (236-245) invalidates an explicit list. New providers must be added to it.
- `todayUtc()` (154-157) is used as "today" in several places, and each must use the as-of date instead:
  - `_Header` range and note (`dashboard_screen.dart:141-157`; the text reads "balances and promises are as of today (UTC)").
  - `_AgingCard` (389).
  - `_UpcomingPromisesCard` link (480) and `upcomingPromisesProvider` (224).
  - `TopOutstandingTable` "days" (`dashboard_tables.dart:116`) and `UpcomingPromisesTable` (203).
- Models: `Coverage` enum and `_coverage` (8-14, unknown values fall back to `all`); `MonthPoint`, `MonthlySeries`, `AgeBucket` (with `dueDateFrom`/`dueDateTo`), `OutstandingByAge`, `RankedCustomer`, `CustomerRanking` (20-150). Add `regionIds` and `asOf` parsing.

**`FE/features/dashboard/dashboard_screen.dart`**
- `DashboardAccess.of(user)` (38-48) uses the global `user.has(Privileges.x)`. Per-region rights need a region-aware query on the `CurrentUser` model.
- The layout composes `_KeyFigures`, `_TrendCard`, `_InvoiceStatusCard`, `_AgingCard`, `_PromiseStatusCard`, `_TopOutstandingCard`, `_TopPayingCard` and `_UpcomingPromisesCard` (58-92).
- The `_Header` `SegmentedButton` for months (174-184) is where region and as-of selectors would go.

**`FE/features/dashboard/dashboard_charts.dart`**
- `DashboardCard(title, subtitle, book, action, child)` (25-77).
- `BookBadge` has the tooltip "Only your own book, not the whole organisation" (79-109). This copy becomes wrong once ALL means "all of the requested regions".
- `asyncCard` (150-155).
- Drill-down links do not carry region or as-of today and must add them:
  - `AgingBars.linkFor` (562-570) builds `/invoices?f=status:in:UNPAID,PARTIALLY_PAID&f=dueDate...`.
  - Donut links `/invoices?f=status:eq:X` and `/promises?f=status:eq:X` (`dashboard_screen.dart:352-353, 397-398`).
  - "All customers" (437).

**`FE/features/dashboard/dashboard_tables.dart`**: `DashTable` and `DashColumn`/`DashRow`, and the three tables (108-236). These are purely presentational.

---

## 8. Checklist for implementers
1. Publish outbox events with one MANDATORY-propagation INSERT. No rule logic, no FKs, no enum CHECKs, and all text fit to column lengths.
2. The consumer, ticker and dispatch run outside request transactions, claim rows with conditional UPDATEs, write effects plus an idempotency row in one transaction, and call `EmailDispatcher` only after commit.
3. Never call `currentUser.require()`, `ScopeResolver.*` or `PocService.notifyAssignee` from background threads without an explicit actor or security context.
4. Load everything the consumer needs inside `TransactionTemplate` blocks. Background threads have no OEMIV.
5. Every new `@Scheduled` job gets an `app.*.…-interval-ms` property, `initialDelayString` equal to the interval, a `3600000` test override, and a direct-callable method. Raise `spring.task.scheduling.pool.size`.
6. Inject a `Clock` into new time-dependent code. `InvoiceDates.today()` is static and cannot be moved in tests.
7. Region predicates go into `ScopeResolver.Scope.predicates()`. Response DTOs echo `regionIds` and `asOf`. Update the frontend `_coverage` mapping and `BookBadge` copy.
8. As-of dashboards need effective-dated history first. Allocations are deleted on void, and void, cancel, updateAmount and replaceItems leave no timestamps. The "billed as of D" figure must also filter on `createdAt`.
9. Keep B2 pending changes out of the entity tables so all eight dashboard figures stay correct unchanged.
10. Add every new FK-bearing table to `IntegrationTestBase.resetTransactionalData()` in dependency order.