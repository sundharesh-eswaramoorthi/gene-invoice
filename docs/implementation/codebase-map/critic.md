# Completeness review of the nine area maps

Scope: this was a read-only review. I edited no files; the only extra commands were `git show` on old commits and a `curl` reachability check against Maven Central. Paths are relative to the repo root. `B/` means `backend/src/main/java/com/geneinvoice/` and `F/` means `frontend/lib/`.

---

## 0. Headline

The maps are mostly accurate. Of the claims I spot-checked, 16 held and 9 were wrong or needed a caveat (§2). Four gaps matter most:

1. **A predecessor PRD for Part A exists in git history.** No map mentions it. It adds intent the pasted PRD leaves unstated (§3.1).
2. **The maps give conflicting advice** on four design decisions: where trigger events are captured, how history is stored, how "which regions" reaches the server, and whose identity a rule runs as. §1 resolves each.
3. **Several subsystems had no map**, and each one breaks silently under the PRD: `document/**`, `product/**`, invoice numbering, inbound email, user deletion, and the full set of frontend save paths (§3).
4. **Two traps in shared infrastructure** were missed or stated too narrowly:
   - Unknown query parameters are ignored silently. One existing test proves it passes without its filter being applied (§2, C-1).
   - The principal `User` is detached on every request path, not only on the paths without an open entity manager (§2, C-4).

---

## 1. Contradictions between maps, resolved

### X1. Status code for a record the caller may not see (auth-privileges vs domain-writes)

- **auth-privileges** (§5.3): "Per AUTH-08, a record outside scope or belonging to another customer returns 404."
- **domain-writes** (§1.4): `CustomerService.get` and `DisputeService.get` return 403.
- **Answer: domain-writes is right.**
  - `B/customer/CustomerService.java:130-133` throws `AccessDeniedException("Not allowed")` when a customer login asks for another customer's id.
  - Only a staff caller outside their book gets 404 (`requireInBook`, lines 140-145).
  - Invoice, payment and promise are consistently 404 (`B/invoice/InvoiceService.java:264-271`).
  - Disputes return 403 to a customer asking for another customer's dispute, and apply no scope at all to staff.
- **For B1:** keep "invisible means 404" for new region checks. Decide separately whether to fix the two 403 cases.

### X2. Where A1 trigger events are written

The maps give four different answers:

| Map | Proposal |
|---|---|
| audit-history §12 | Write the outbox row inside `AuditService.record`, or use an Envers `EntityTrackingRevisionListener` |
| domain-writes §3 | Call a publisher explicitly after each write method's last audit call, deduplicated per transaction |
| dashboard-sched §4.2 | A `Propagation.MANDATORY` publisher called at each write path |
| baseline §8.2 | Mentions JPA entity listeners |

**Answer: use an explicit `Propagation.MANDATORY` publisher, called from the service write methods.** Deduplicate by `(type, id, kind)` in a `TransactionSynchronization` that inserts the rows in `beforeCommit`.

`AuditService.record` is the wrong choke point:
- It records payment allocations as `PAYMENT_APPLIED`/`PAYMENT_REVERSED` against the **INVOICE** (`B/payment/PaymentService.java:146-157`).
- `voidPayment`, `updateAmount`, `cancelWithRefund` and `replaceItems` write no audit row on their own entity.
- Customer credit changes are never audited.
- It fires at startup with entity id 0 (`B/invoice/InvoiceSchemaUpgrade.java:51-55`).
- It is non-atomic in `ProductController` and `UserController`, which have no `@Transactional` (checked by grep).
- It records `DISPUTE_APPROVED` against the target entity.

`beforeCommit` works here because the rows are known before the flush. The flush-ordering problem domain-writes describes applies only to a listener that collects entity changes.

Use a Hibernate listener only as a coverage check in tests, not as the trigger source. There is no event infrastructure today: `ApplicationEventPublisher`, `@TransactionalEventListener`, `registerSynchronization`, `@Async` and `@EntityListeners` return no grep hits.

### X3. Storage for B3 history

The maps propose different mechanisms:
- **audit-history:** Envers with the validity strategy, plus `@Subselect` read-only mirror entities.
- **dashboard-sched:** hand-written SCD2 `*_history` tables or a money ledger.
- **query-engine:** history entities with the same attribute names as the live ones.
- **poc-scope:** a seat table with effective dates.
- **baseline** (§5) says effective-dated columns "should be set by services from the injected clock, not by entity callbacks". That conflicts with Envers, which timestamps revisions itself.

**Answer:**
- The **shape** is agreed: rows with `valid_from`/`valid_to`, including `region_id`, read through the existing Criteria engine with one extra `PredicateFactory`.
- The **capture mechanism** is still open. Each option has conditions:
  - **Envers.** Take the revision timestamp from the injected `Clock` inside a custom `RevisionListener`; a Spring bean is needed so tests can move time. Run the baseline backfill first. Add `org.hibernate.orm:hibernate-envers`, which Maven Central serves (checked with `curl`: HTTP 200). It is **not** in `~/.m2`, so the offline `mvn -o test` baseline fails until one online build has run.
  - **Hand-rolled.** Write the history row in the same explicit service hooks as X2. That shares X2's coverage list, and the known gap is the same: several money paths are unaudited today.
- Either way, the "pending changes outstanding at T" query uses its own interval table (all maps agree). Tasks, which are new, can carry their own `valid_*` columns.

### X4. How "which regions am I asking about" reaches the server

The maps disagree:
- **query-engine §12:** send it as an ordinary filter, `regionId:in:1,2`.
- **poc-scope §8.1 and dashboard §3.4:** a separate request parameter plus a new `PageResponse` field.
- **frontend-arch §15:** a global `ViewScope` merged into `apiParams` as `regionIds`. frontend-arch also notes that bulk and export bodies drop `extraParams` (`F/core/table/data_table_scaffold.dart:473-480, 499-505`).

**Answer:**
- Send one pair of query parameters, `regionIds` and `asOf`, on **every** request, including POSTs.
- Read them in exactly one place: a request-scoped `ViewScope` filled by a `HandlerInterceptor` or `OncePerRequestFilter`.
- `ScopeResolver` and `DashboardService` consume it.

Why not a filter string:
- The dashboard, the record tabs (documents, emails, audit, promises by customer), `/api/pocs/*` and detail reads accept no filters.
- A filter shows as a chip the user can remove.
- Authorisation has to be the intersection computed in `ScopeResolver` anyway.

Also keep a `regionId` column in each `TableSchemas` entry, for display, user filtering and A2 rule conditions.

Because the parameter is read from the request rather than from `BulkRequest`, bulk and export pick it up once the frontend adds it to the POST URL.

**Required safeguard:** add an integration test that calls every list, summary, dashboard and export endpoint with `regionIds` and asserts the effect. Unknown parameters are ignored silently (C-1).

### X5. Where the region is stored

- poc-scope and domain-writes leave open "denormalise onto children" versus "derive through customer".
- dashboard-sched §3.4 assumes `root.get("region")` resolves on the Invoice, Payment, Customer and Promise roots.

**Answer: store a denormalised `region` on Customer, Invoice, Payment, PaymentPromise, Dispute, Task and any pending-change row**, copied from the customer when the record is created. Reasons:
- `Dispute` has only `customerId` as a plain `Long` (`B/dispute/Dispute.java:25-26`).
- A uniform attribute makes every `forX()` predicate identical.
- B3 asks for "the region a record belonged to then", which is per record.

Moving a region is then an explicit operation. It locks the customer, then its invoices in ascending id order (PPD-01), cascades to the children, and writes history.

Documents and emails keep deriving through their parent: `documents.customer_id` is already denormalised (`B/document/Document.java:43-44`). Invoice items, allocations and POC seats also derive through their parent.

### X6. Whose identity a rule runs under

The maps propose three identities:
- **domain-writes §3:** a seeded system user holding `SCOPE_OVERRIDE`.
- **auth-privileges §9.4 option (b) and email-roles §9.1 option (b):** run as the rule owner.
- **auth-privileges (a) and dashboard-sched §4.4:** internal service methods that take an explicit actor id.

**Answer: run as the rule owner**, installing a fresh `AppUserDetails` built with `AppUserDetailsService.buildAuthorities`, the way `IntegrationTestBase.actAs` does, and clear it in `finally`. Add principal-free internal entry points only where the existing path cannot be reused: dispute creation, which is customer-only, and task creation, which is new.

Why:
- A system `SCOPE_OVERRIDE` user bypasses B1 regions entirely.
- The predecessor PRD states "Nothing an automation sends or assigns escapes existing permissions" (§3.1).

Consequences to design for:
- An inactive owner suspends the rule.
- Audit rows then show the owner as actor. Add `origin` and `rule_id` columns to `audit_logs` (nullable, which is safe under `ddl-auto`).
- `PocService.notifyAssignee` skips when the assignee is the owner (`B/poc/PocService.java:267-275`).

### X7. What "the primary" means (A4)

- The backend maps agree: `Target.sender` / `PocService.defaultAssignee` is the primary if active, otherwise the oldest active seat.
- `primaryFor` (unused) returns the flagged primary even when inactive.
- The frontend's `Customer._primary` (`F/shared/models/customer.dart:59-65`) returns the flagged primary **regardless of whether it is active**, falling back to the first seat.
- **Answer:** use the `sender` semantics. Any UI preview of a placeholder or assignee must come from the server (`EmailRoleOption.sender`), never from `Customer.primaryCollectionPoc`.

---

## 2. Corrections: claims verified against the code

### Held

| # | Claim (map) | Evidence |
|---|---|---|
| V1 | `halt_on_error` plus `ddl-auto: update` stops startup when a NOT NULL column is added to a populated table (several maps) | `backend/src/main/resources/application.yml:24`. **Caveat the maps missed:** see C-5 on `@ColumnDefault` |
| V2 | `ScopeResolver` semantics, including disputes having no scope for staff | `B/poc/ScopeResolver.java:51-133` |
| V3 | `InvoiceService.create` does no scope check on the customer | `B/invoice/InvoiceService.java:59-64` |
| V4 | Payment bulk is not wrapped in `eligibility`, so a `BadRequestException` counts as **failed** (domain-writes) | `B/payment/PaymentController.java:112-127` |
| V5 | `Submission.senderUserId` is a primitive `long`, so a null `fromUserId` throws NPE inside the claim transaction (email-roles) | `B/email/transport/Submission.java:5`, `B/email/EmailDispatcher.java:185` |
| V6 | `UserController`, `ProductController` and `RoleController` have no `@Transactional`, so their audit rows are not atomic with the change (audit-history) | grep finds no annotation |
| V7 | `notificationsProvider` casts a `PageResponse` to `List` and has no readers (dashboard-sched) | `F/features/notifications/notifications_providers.dart:9-13` |
| V8 | No `Clock` bean and no event or async infrastructure | grep |
| V9 | Page sizes `[10,20,50]`; DATE columns have no `eq`; every new schema must be registered in both `buildIndex` and `VIEW_PRIVILEGE` | `TableQuery.java:11-23`, `ColumnType.java:14`, `TableSchemas.java:335-341`, `TableSchemaController.java:21-31` |
| V10 | Envers absent from `~/.m2`; AMQP present | cache listing |
| V11 | The 403 message is always replaced by a generic one | `B/common/GlobalExceptionHandler.java:57-61` |
| V12 | The exception-to-status mapping (`NotFoundException` → 404, `BadRequestException` → 400, integrity and concurrency failures → 409) | `B/common/GlobalExceptionHandler.java` |
| V13 | `DataSeeder` line numbers in auth-privileges (`upsertRole` 167-173, `createRoleIfAbsent` 175-192, `pickByNames` 205-211) | read in full |
| V14 | `TableRequest ==` compares with `mapEquals` (frontend-arch G1), and `RouteQuery.push` drops unknown parameters (G3) | `F/core/table/table_providers.dart:35-44`, `F/core/table/route_query.dart:29-32` |
| V15 | `AuditController.SUPPORTED` excludes DISPUTE, and `readOrGone` lets staff read the history of a deleted record | `B/audit/AuditController.java:36-47, 118-124` |
| V16 | `IntegrationTestBase` reset order, and `RowVersionUpgrade.TABLES = [customers, invoices]` | `IntegrationTestBase.java:65-89`, `RowVersionUpgrade.java:18` |

### Wrong or needs a caveat

- **C-1 (baseline §3.6, wrong): filter syntax.** Baseline says the list filter syntax is `?remainingAmount=eq:0`. The real syntax is repeated `?filter=remainingAmount:eq:0` (`B/common/query/FilterParams.java:10-18`).
  - `PromiseFiguresAndPocTest.java:128-131` sends `.param("remainingAmount","eq:0")`. The controller ignores that parameter (`PaymentPromiseController.java:44-53`), and the test passes only because a single promise exists.
  - **So the engine ignores unknown parameters silently.** That is the X4 risk: a missing `regionIds` reader returns unscoped data with no error.
- **C-2 (baseline §3.3, §8.3, wrong): DataSeeder line numbers.** The class spans `DataSeeder.java:24-212`, and `createRoleIfAbsent` is at lines 175-192, not 147-164.
- **C-3 (auth-privileges §5.3, wrong):** the AUTH-08 "404 everywhere" claim. See X1.
- **C-4 (auth-privileges §9.1 item 3 and §10.11, too narrow): the principal is detached on every path.** The map says a lazy association on the principal `User` fails only on the paths excluded from open-entity-manager-in-view.
  - `JwtAuthFilter` loads the user in a **servlet filter** (`B/auth/JwtAuthFilter.java:36`), outside any transaction: `AppUserDetailsService.loadUserByUsername` is not `@Transactional` (`AppUserDetailsService.java:24-30`).
  - `OpenEntityManagerInViewInterceptor` is a handler interceptor that runs later, with a different EntityManager.
  - So the principal `User` is detached on **every** path, and any LAZY association reached through `currentUser.require()` throws. Region grants on the principal must be EAGER or loaded with a JOIN FETCH in the filter.
- **C-5 (all maps, incomplete): `@ColumnDefault` is a second migration route.** The maps say every new NOT NULL column needs a nullable column, a backfill, then enforcement.
  - The code also uses `@ColumnDefault` so that `ddl-auto` adds a NOT NULL column **with a default** to populated tables: `Document.deleted` (`Document.java:78-80`), `Email.deliveryUncertain` (`Email.java:115-117`), `EmailRecipient.deliverySeq/deliveredConfirmed` (`EmailRecipient.java:73-75, 89-91`).
  - Use it for scalar flags, such as a pending marker or `origin`.
  - **Do not** use it for `region_id`. The foreign key needs the region row to exist before `update` adds the constraint, and `*SchemaUpgrade` beans run *after* the DDL. For `region_id` the backfill pattern stands.
- **C-6 (poc-scope §2.4, overstated):** "`activeHolders` has no production callers" is wrong. `defaultAssignee` calls it (`PocService.java:98`), and `PaymentPromiseService.resolveCollectionPoc` uses `defaultAssignee` (line 616). Only `primaryFor` and `callerIfAssignable` are unused.
- **C-7 (audit-history §1, wrong count):** `AuditService` has **10** production callers, not 11: CustomerService, DisputeService, DocumentService, InvoiceSchemaUpgrade, InvoiceService, PaymentService, PocService, ProductController, PaymentPromiseService, UserController.
- **C-8 (email-roles §9.1, imprecise): the first error on a background thread is 403, not 401.** On a thread with no principal, `EmailTargets.load` → `requireTypeAccess` calls `currentUser.has()`, which **returns false rather than throwing** (`CurrentUser.java:35-39`). The first failure is therefore `AccessDeniedException("Not allowed")`, not `BadCredentialsException` (`EmailTargets.java:206-213`). Background error classification must treat both as permanent, never as transient.
- **C-9 (dashboard-sched §2.3, caveat): reusing `databaseUnavailable`.** The map says to reuse `MailServiceEventHandler.databaseUnavailable`. The class only exists as a bean when `app.mail.transport` is `mail-service` (`@ConditionalOnProperty`, `B/email/mailservice/MailServiceEventHandler.java:45-46`), which is not the default or the test value. Call the static method, or better, move it to `common/`. Never inject the bean.
- **C-10 (domain-writes §1.3, caveat):** "Creates return 200 with the DTO, not 201" holds for the five core aggregates only. `DocumentController.upload` returns **201** and `delete` returns **204** (`B/document/DocumentController.java:29, 78`).
- **C-11 (query-engine §13, dashboard §1.5, audit §11: clock-read lists incomplete).** Add:
  - `B/invoice/InvoiceNumbers.java:34`: the invoice number embeds `LocalDate.now(UTC)`, read independently of `InvoiceDates.today()`.
  - `InvoiceService.java:66` (default `invoiceDate`) and `:215` (due-date preview).
  - `Payment.onCreate` (`paidAt`), which cannot be set through the API.

---

## 3. Missing coverage (full write-ups)

### 3.1 The predecessor PRD and requirement docs in git history

Read `git show ae7e378:docs/requirements/tasks-placeholders-and-automation.md` (32 lines; removed at 9ff6712). It is the earlier version of Part A. The pasted PRD is authoritative where the two differ. Where it is silent, the old one records intent:

- **Assignees.** "Task, Dispute and Promise all take **several** assignees, each either a person or a role at a level." Today `PaymentPromise.collectionPoc` is a single `@ManyToOne(optional=false)` (`PaymentPromise.java:44-46`) and Dispute has no assignee at all. The pasted A3 says only that assignees are "picked with the same role-at-a-level model". **This is an open decision:** multi-assignee join tables on Promise and Dispute, or a single primary.
- **"My tasks" list.** Named explicitly (the pasted A6 says "somewhere to see their work").
- **Placeholders by level.** On an invoice, offer `Customer.*` and `Invoice.*`; on a payment, `Customer.*` and `Payment.*`.
- **Document attachments on email.** Not in the pasted PRD; email-roles §5 shows no attachment path exists, so treat it as out of scope.
- **Rule conditions** are "the same [filters] the list page filters by". That confirms the query-engine map's `TableSchemas` reuse.
- **Success criterion:** "Nothing an automation sends or assigns escapes existing permissions **or the internal/shared rule on documents**". This supports X6 (run as owner).
- **Out of scope then:** approvals (now in scope as B2), delays or branching, triggers on Task, Dispute, Promise or Document, saved templates.

The other two requirement docs define the IDs the code comments cite (D7, E13, L5, AUTH-08, PPD-01 and so on):
- `git show ae7e378:docs/requirements/{invoice-due-dates-and-documents,poc-payment-promise-and-tables}.md`
- The regression defect list is at `git show ae7e378^:docs/regression/2026-09-15/defects.md`.

### 3.2 `document/**`

Only partly covered (auth and poc-scope cite `DocumentTargets`).

**Model** (`B/document/Document.java:10-108`):
- Polymorphic `entityType` (`DocumentEntityType` = CUSTOMER, INVOICE, PAYMENT; `DocumentEntityType.java:8-11`, an enum column with a CHECK constraint) plus `entityId`.
- Denormalised `customerId` (43-44), `entityLabel` (a snapshot), `visibility` INTERNAL or SHARED (default INTERNAL, 61-64).
- Soft delete: `deleted`, `deletedByUserId`, `deletedAt` (78-86).
- `uploadedAt`, `createdAt`, `updatedAt`.

**Access** (`B/document/DocumentTargets.java`):
- `requireVisible` needs the entity's VIEW privilege (48-52).
- `requireManageable` needs the entity's MANAGE privilege, or VIEW for a customer login (54-60).
- Then `describe` (74-91) calls the **scoped** `customerService/invoiceService/paymentService.get`, so book scope and future region scope flow in automatically.
- `canManage(type)` (62-66) is **global**: `DOCUMENT_MANAGE` plus the entity's MANAGE privilege.

**Per-record capability flags** (`B/document/DocumentService.java:216-227`):
- `DocumentDto` carries `canDownload=true`, `canEdit=manage` and `canDelete=manage||uploader`, all computed from global privileges.
- This is the precedent frontend-arch §4 recommends for B1, and **under B1 these flags become wrong** unless they are region-aware. A user with DOCUMENT_MANAGE and INVOICE_MANAGE in region R1 would see "Edit" on region R2's documents. The server would still refuse at `requireManageable` once writes are checked per region.

**Transactions and locks:**
- `upload` (64-116) is **not** `@Transactional`. It stages the bytes, stores them outside any transaction, then in a `TransactionTemplate` locks the customer row through `DocumentParentLock.lockCustomer` (`Propagation.MANDATORY`, `DocumentParentLock.java:15-18`), inserts, and audits. On failure it deletes the stored bytes.
- **Lock order is customer first**, the same as the money path. A region-move operation must also lock the customer first.

**Audit:** document events are recorded under the **parent's** entity type and id (106-107, 157-158, 178-179). The timeline's customer redaction drops `DOCUMENT_*` unless both sides are SHARED (audit-history §7.2).

**Cascade:** `DocumentCascade.onCustomerDeleted` bulk-soft-deletes by `customer_id` with a `@Modifying` JPQL update (`DocumentRepository.java:28-39`). That bypasses Envers and any ORM listener; see X3.

**PRD impact:**
- **B1:** scope through the parent. No `region_id` is needed. Make `DocumentDto` flags region-aware, or accept that they are advisory.
- **B3 (useful precedent):** documents already support as-of. A document existed at T when `uploadedAt <= T` and (`deletedAt` is null or `> T`), so the record-tab list can answer as-of with one extra predicate in `onRecord` (205-214).
- **A3/A6:** documents are not trigger entities. If tasks are ever document parents, `DocumentEntityType` and the frontend exhaustive switch (frontend-arch G24) both change, and the `documents.entity_type` CHECK constraint must be widened (§4, R9).
- **Storage:** the local root is `./data/documents` (`application.yml:55`). An empty `data/documents/` exists in the working tree, and tests use `${java.io.tmpdir}`.

### 3.3 `product/**`

- **Model** (`B/product/Product.java:9-42`): `name`, `description`, `price` (14,2), `active`, `createdAt`. No version, no `updatedAt`, **no region**.
- **Controller-only feature** (`B/product/ProductController.java`) with no service. The list uses an empty scope (`List.of()`, line 74), so it is unscoped.
  - Create, update and delete call `repository.save`/`deleteById` and then `auditService.record` in a **separate** transaction (96-98, 111-113, 124-126). The actor comes from `currentUser.require()`.
  - Deleting a product used on an invoice fails at commit with `DataIntegrityViolationException`, so the response is 409 and no audit row is written.
- **Invoice lines are already point-in-time.** `InvoiceItem.unitPrice` and `lineTotal` are copied at creation, so as-of invoice figures never need product price history.
- **PRD decisions:**
  - (a) Products are a global catalogue outside B1 ("every record belongs to exactly one [region]" should say explicitly that products are excluded), or they get a region.
  - (b) B2: a product price change is monetary, but with no region it has no per-region threshold. Exclude it, or define a global threshold.
  - (c) Any future product trigger or maker-checker hook needs a service layer first, because of V6.

### 3.4 Invoice numbering (`B/invoice/InvoiceNumbers.java`, `InvoiceNumberSequence.java`, `InvoiceNumberSequenceRepository.java`)

- **Format:** `INV-yyyyMMdd-NNNN`. The day is `LocalDate.now(UTC)` (line 34) and the counter is zero-padded to 4 digits (43); it grows past 9999 without wrapping.
- **One global row, id 1, taken with `PESSIMISTIC_WRITE`** (`lockById`, `InvoiceNumberSequenceRepository.java:13-15`). `next()` is `Propagation.MANDATORY` (32).
  - On the first issue of a day it re-derives `lastNumber` with `countByInvoiceNumberStartingWith(prefix)` (38-41).
  - If the row is missing, `ensureSequenceRow` on `ApplicationReadyEvent` (24-30) and the `orElseGet` fallback (37) recreate it. This matters because the test contexts wipe each other's H2 database (baseline §4).
- **Every invoice create serialises on this row** for the whole create transaction, which includes credit application and promise re-evaluation.
- **PRD impact:**
  - **B1:** numbering stays global unless the business wants a region prefix, which would need a sequence row per region keyed by `(region, day)`. Either way, the uniqueness constraint on `invoice_number` is global.
  - **B2:** if an invoice create above the threshold becomes pending, assign the number **at approval**, inside the transaction that applies it. The number then reflects the approval day, not the request day. Reserving a number at request time leaves gaps when a request is rejected.
  - **B3:** the number's date prefix is not a creation timestamp. Use `createdAt`.

### 3.5 Inbound email, the mail-service webhook, and Gmail connections

**Inbound mail** (`B/email/EmailInboundService.java`) runs from the mail-service webhook with **no principal**, inside a `TransactionTemplate` (51).
- It is idempotent: `providerMessageId` is unique, and on `DataIntegrityViolationException` it re-reads (50-59). This insert-then-catch pattern is a precedent for the A5 idempotency table.
- A reply is attached **only** to the record of the email it answers. The anchor is found through `CopyRef` from the hint, then the provider thread, then `In-Reply-To`/`References` (138-176). With no anchor, the mail is dropped (95).
- **Consequence for A3:** an automation email must be persisted as an ordinary outbound `Email` row on the triggering record (`entity_type`/`entity_id`), or customer replies will not thread back to that record.
- Senders and recipients are resolved against users by address, then by connected Gmail, then customers **globally** (`EmailDirectory.customerByAddress`; lines 185-206, 229-234). This is region-blind by design; customer email uniqueness is also global (`CustomerSchemaUpgrade`, Postgres-only unique index on `lower(email)`, lines 58-70).
- Inbound mail is not an A1 trigger.

**Webhook handler** (`B/email/mailservice/MailServiceEventHandler.java`):
- The bean is conditional (C-9).
- `connectionChanged` calls `notificationService.notify(..., "GMAIL_RECONNECT", ...)` inside its own transaction (149-167). It is another principal-free notification producer.

**Gmail as the sender of automation mail:**
- Mail is sent from the **sender's connected Gmail**. With `app.mail.transport=none` (the default and the test setting), every automation email ends `NOT_SENT` / `NOT_CONFIGURED` (`EmailDispatcher.claim`, 166-169).
- The rule editor should warn when the resolved sender has not connected Gmail. `GmailConnectionService.statuses(Collection<Long>)` returns `Map<Long, GmailStatus>`.
- `GmailDisconnects.mayConnect` reads only `user.getRole().getPrivileges()` (`B/email/connection/GmailDisconnects.java:62-65`). Under B1 it must take the union of region grants, or the sweeper disconnects users whose EMAIL_SEND comes only from a regional grant. The auth map noted this too.
- The mail service throttles each mailbox to one send per 500 ms (`mail-service/src/main/resources/application.yml:31`, `MailboxThrottle.acquire`), so a rule that emails many records from one role holder's mailbox queues behind itself.

### 3.6 `notification/**` (additions to dashboard-sched §5)

- `notifyAdmins` uses `findByRoleName("ADMIN")` and **does not filter inactive users** (`B/notification/NotificationService.java:32-38`). Deactivated administrators still receive notifications.
- `notify` is REQUIRED-propagation `@Transactional` (18-25). `title` and `message` are truncated; **`link` (length 300) and `type` (length 40) are not** (`Notification.java:27, 39`). A long link throws inside the caller's transaction and rolls back the caller's save. Truncate or validate in any new producer (tasks, approvals).
- There is **no entity, customer or region column**, so B1 cannot filter notifications. Links to records the user can no longer see answer 404, which the frontend renders as `RecordUnavailable`.
- For B2 approver notifications, write a new privilege-and-region-based recipient query. Do not copy `findByRoleName`.

### 3.7 `TableSchemaController` (additions to query-engine §3.6)

- It has no `@PreAuthorize`. `mayRead` uses `currentUser.has` over a static map (`B/common/query/TableSchemaController.java:21-31, 68-71`), and the schema is `visibleTo(isCustomer())` (49, 61-62).
- New list entities (tasks, rules, approvals, regions) must go in **both** `TableSchemas.buildIndex` (`TableSchemas.java:335-341`) and `VIEW_PRIVILEGE`. Otherwise `/api/table-schemas/tasks` returns 403, and `DataTableScaffold` shows an error rather than an empty list.
- The frontend `tableSchemaProvider` is not `autoDispose` and is keyed only by user (`F/core/table/table_providers.dart:46-54`). If a schema ever becomes region-dependent, for example with region-specific enum values, it will not refetch.

### 3.8 User deletion and reference counting (`B/user/UserController.java:194-241`)

- Delete falls back to deactivate when `pocReferenceCount(id) > 0`. That count covers `invoices.sales_poc`, `payments.collection_poc`, `payment_promises.collection_poc` and `customer_pocs` (236-241).
- **Every new user reference must be added there**: task assignee rows, rule owner, pending-change maker and checker, region grants (which should cascade).
  - With foreign keys, the delete fails at commit with 409 and the user sees a generic conflict.
  - Without foreign keys, rows are left pointing at a deleted user.
- The controller is not transactional (V6). The hard delete and its audit row are separate transactions.

### 3.9 Customer request records: B2 payload hazards

- `CustomerUpdateRequest` carries **`password`** (`B/customer/CustomerDtos.java:38-45`), and `CustomerService.update` resets the linked login's password and `credentialsChangedAt` (`CustomerService.java:276-287`).
  - **A pending change stored as the request JSON would keep a plaintext password.** Strip password fields before storing a pending payload, and never gate password changes.
  - The existing `CustomerAuditSnapshot` already leaves the password out.
- The customer `PUT` and promise `PUT` are full replaces: a null `paymentTerm` clears it (domain-writes §5.17). Store complete post-change payloads, not diffs, and re-validate them at approval.

### 3.10 Disputes as an A3 action (additions to domain-writes §4.1)

- `DisputeTargetType` has only `INVOICE` and `PAYMENT` (`B/dispute/DisputeTargetType.java:3-6`). **A Customer-triggered rule cannot create a dispute** until CUSTOMER is added, which also needs a CHECK-constraint widen on `disputes.target_type`.
- `openedByUserId` is NOT NULL (`Dispute.java:28-29`).
- A dispute created without `proposedChangeJson` **cannot be approved** unless the approver types `appliedChangeJson`: `applyChange` throws "No change specified for approval" (`DisputeService.java:219-222`). The UI does allow typing it (`F/features/disputes/dispute_detail_screen.dart:87-99`).
- Approval in the UI is gated on `isAdmin` (line 123).
- **Decision needed:** automation-created disputes need a defined proposed change, or an "acknowledge/close" outcome that does not apply a change.

### 3.11 Frontend mutating calls: complete inventory for B2 (pending replies) and B1 (region checks)

| File:line | Call |
|---|---|
| `customers/customers_screen.dart:333` | POST /api/customers |
| `customers/customers_screen.dart:347` | PUT /api/customers/{id} (edit dialog on a row) |
| `customers/customer_detail_screen.dart:109` | PUT /api/customers/{id} |
| `customers/customer_detail_screen.dart:172` | DELETE /api/customers/{id} |
| `invoices/invoice_form_screen.dart:167` | POST /api/invoices |
| `invoices/invoice_detail_screen.dart:135` | PATCH /api/invoices/{id} |
| `invoices/invoices_screen.dart:254` | POST /api/invoices/{id}/cancel |
| `payments/record_payment_dialog.dart:115` | POST /api/payments |
| `payments/payment_detail_screen.dart:95` | PATCH /api/payments/{id} |
| `promises/promise_form_dialog.dart:167-168` | PUT and POST /api/promises |
| `promises/promise_form_dialog.dart:442-444` | override add and remove |
| `promises/promises_tab.dart:284` | POST /api/promises/{id}/cancel |
| `poc/customer_poc_editor.dart:87, 118, 123` | add, remove and set-primary POC seats |
| `disputes/dispute_create_dialog.dart:130` | POST /api/disputes |
| `disputes/dispute_detail_screen.dart:96` | approve / deny |
| `core/table/data_table_scaffold.dart:473-480` | every bulk action |
| `core/table/data_table_scaffold.dart:499` | export |

Paths are under `F/features/` unless they start with `core/`.

- **Create call sites parse `(res.data as Map)['id'] as int`**, for example `record_payment_dialog.dart:125-126`.
  - If a pending create returns a body with no record id (such as `{pendingChangeId, status}`), the client throws after the server has **accepted** the request. The user sees an error and may submit again.
  - Choose the B2 response shape (for example 202 with a `pending` object) and update every create site in the same change.

### 3.12 Frontend screens no map covered

- **`F/features/email/inbox_screen.dart:19-80`** is the closest existing model for "My tasks". It is a `DataTableScaffold` scoped to the owner on the server (`B/email/InboxController.java:103-108`), with a local AppBar action ("Mark all as read"), bulk mark actions, `onBulkDone` invalidating a badge provider, and a mobile card.
- **`gmail_connection_screen.dart`** (PUT and DELETE `/api/me/gmail`, lines 340 and 410) is where a user fixes the "sender has not connected Gmail" state that automation emails can hit.
- **`products_screen.dart` and `product_detail_screen.dart`** have global gating only. Nothing is region-relevant unless products get a region.
- **`upload_document_dialog.dart`** chooses INTERNAL or SHARED. That is the "internal/shared rule on documents" the predecessor PRD says automation must not bypass.

---

## 4. Cross-cutting risks for the PRD, most severe first

- **R1. Region and as-of parameters are dropped silently.** Unknown request parameters are ignored (C-1), `RouteQuery.push` drops extra URL parameters (frontend-arch G3), and bulk and export bodies drop `extraParams` (G2). A single missing reader shows other regions' or today's data with no error.
  - **Mitigation:** one server-side `ViewScope` resolver (X4), one frontend provider feeding `tablePageProvider`, `tableSummaryProvider`, bulk, export and the dashboard, and an endpoint-by-endpoint integration test.

- **R2. Background identity.**
  - `require()`, `isCustomer()` and `customerIdOrNull()` throw on a background thread, while `has()` returns false silently (C-8).
  - Tests hide this: a test that calls the consumer directly runs under the test thread's `actAs` (baseline §8.4).
  - **Mitigation:** run as owner (X6), and have the consumer test call `SecurityContextHolder.clearContext()` before invoking it.

- **R3. The principal is always detached (C-4).** Region grants on `User` must load eagerly in `JwtAuthFilter`. Otherwise every `currentUser.require().getRegionGrants()` throws `LazyInitializationException`. The consumer's run-as principal must also be loaded with its grants fetched.

- **R4. Union authorities widen writes.** Once `@PreAuthorize` means "holds the privilege in some region", every write needs a per-region MANAGE check, including create paths that check nothing today:
  - `InvoiceService.create` (line 61)
  - `PaymentService.record`
  - `PaymentPromiseService.create`
  - `CustomerService.create`
  - document upload (`requireManageable`)
  - POC seat changes
  - dispute approve and deny
  - and the flags on `DocumentDto` (§3.2).

- **R5. The event source must not break the save.** An outbox insert inside the user's transaction that fails marks it rollback-only.
  - Keep the insert trivial: no foreign keys, no unique constraints, `String` rather than enum columns, all text fitted to column length.
  - Never publish to a broker inside the transaction. `recomputeAll(apply=false)` is a dry run done by rollback (`PaymentPromiseService.java:481`).
  - Put idempotency on the **effect** (a unique `(rule_id, event_id, action_index)`), not on the event row.

- **R6. Trigger coverage.** Invoice `paidAmount` and `status` and customer `creditBalance` change mostly as side effects (payment allocation, credit ledger, dispute approval). Publishing only from `update()` methods misses them (domain-writes §3). "Overdue" is not a write, so it needs the daily schedule. Decide whether a system side effect counts as "updated".

- **R7. Maker-checker re-entry and exemptions.**
  - Dispute approval calls `cancelWithRefund`, `replaceItems`, `voidPayment` and `updateAmount`. If B2 gating sits inside those methods, an approved dispute becomes pending again. Decide whether dispute approval is itself the checker step.
  - System changes must never be gated: promise sweeps, credit application at invoice create, schema backfills.
  - Automation-created promises above the threshold need a defined maker (the rule owner) and checker.
  - Pending must be signalled by returning normally. A thrown exception rolls back the pending row.

- **R8. Pending changes and triggers.** A pending change has not taken effect, so it must not publish UPDATED. Publish when the approved change is applied, with the maker as origin. The "which approvals were outstanding" as-of query reads the pending table's interval columns.

- **R9. Enum CHECK constraints are never widened by `ddl-auto`.** Every new value on an existing enum column needs a widen call:
  - `documents.entity_type` (TASK?)
  - `emails.entity_type` (TASK?)
  - `disputes.target_type` (CUSTOMER for A3)
  - `emails.from_role`
  - `email_recipients.delivery_status`

  `EmailSchemaUpgrade.widen` is package-private on a package-private class (`B/email/EmailSchemaUpgrade.java:21-40`). Move it to `common/`. Tests run on create-drop H2 and will not catch this; the `azurefree` profile (H2 file with `update`) will fail at runtime.

- **R10. NOT NULL `region_id` migration.** Nullable column, chunked backfill in a `*SchemaUpgrade` that inserts its own default region, then dialect-specific enforcement (the `InvoiceSchemaUpgrade.enforceDueDateNotNull` pattern). `@ColumnDefault` does **not** work here (C-5).
  - Test fixtures `customer(name)` (`IntegrationTestBase.java:119-125`) and `DataSeeder` users need a default region.
  - Any cached "default region id" goes stale when the second test context wipes the shared H2 database (baseline §4). Look it up lazily.

- **R11. Tests and seeding.**
  - Add every new foreign-key table to `resetTransactionalData` in dependency order (`IntegrationTestBase.java:65-89`).
  - Add every new user reference to `pocReferenceCount` (§3.8).
  - Add new privileges to `Privileges.ALL`; `pickByNames` drops unknown names silently (`DataSeeder.java:205-211`).
  - Existing POC roles only receive privileges created on the current boot (`createRoleIfAbsent`, 175-192), and ADMIN, CASHIER, VIEWER and CUSTOMER are overwritten on every boot (167-173). Region grants therefore need their own seeding path.
  - Mirror each new privilege by hand in `F/shared/models/privileges.dart`.

- **R12. Clock and as-of.** No `Clock` bean exists. Add one to the single `@Import` in `IntegrationTestBase`; any other `@Import` or `@MockBean` starts a third context and wipes the database. Every clock read in C-11 and in the maps' lists must take the as-of date, including `InvoiceNumbers.next()` and the `overdue`, `relative`, tile and dashboard "today" values. As-of mode must be **read-only**: writes ignore `asOf`, and the frontend disables edit flags when as-of is set.

- **R13. Live subqueries in the list columns.** `customerOutstanding`, `pocSeatPredicate`, `promiseLinkPredicate`, `overdue` and the `ScopeResolver` book subqueries all target live tables. The Customer DTO's outstanding and overdue sums ignore scope and region. Each needs a region-aware and an as-of variant before A2 conditions or B3 lists are correct.

- **R14. `lockedFilters` means "book".** Region and as-of state must not go into it (every map agrees). Otherwise the dashboard's "collected" figure switches to the allocation-based formula (`DashboardService.java:203-205`), and the UI labels every list "My records only".

- **R15. Automation email specifics.**
  - Persist a normal outbound `Email` row on the record, so replies thread back (§3.5).
  - Dispatch only outside a transaction; `EmailDispatcher.dispatch` throws inside one, including during `afterCommit`.
  - The sender must be an active internal user with Gmail connected, and with a non-null `fromUserId` (V5).
  - Placeholder expansion must re-apply the subject and body limits and keep the subject to one line.
  - Primary resolution follows `Target.sender` (X7).

- **R16. Stale frontend rights.** Privileges and region grants are fetched only at login and bootstrap (`F/features/auth/auth_controller.dart:29-61`). Prefer per-record server flags (`canEdit`, `canApprove`, `pending`), following the `DocumentDto` pattern. Never gate on the role name `ADMIN`, as `dispute_detail_screen.dart:123` does.

- **R17. Unscoped and dead paths that bypass region scope:**
  - `PaymentService.list`, `PaymentPromiseService.listForCustomer/listForInvoice`, `DisputeService.list`, `InvoiceService.getInternal` and `PocService.primaryFor` (dead or unscoped)
  - `AuditTimelineService` with `includeRelated`
  - `EmailDirectory.searchPeople`, `PocService.assignable`, and `/api/pocs/my-scope`, which are global

  New features must not call these.

- **R18. Capacity.**
  - The scheduler pool has 3 threads (`application.yml:25-30`); raise it for the automation poller and schedule ticker.
  - The production Hikari pool has 5 connections (98-100).
  - Rule evaluation over large sets must page by keyset on `id`, because unpaged queries are silently capped at 5000 rows (`TableQueryExecutor.java:23, 53-55`).

---

*Note: the claude.ai Google Drive connector needs authorization in claude.ai connector settings before it can be used. It was not needed for this review.*