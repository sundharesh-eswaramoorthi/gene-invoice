# Engineering map: email addressing, the role-at-a-level model, and dispatch (for PRD A3, A4, A5, A6, with notes for B1 and B3)

All paths are relative to ``. Backend package root: `backend/src/main/java/com/geneinvoice/`, shortened to `…/` below. Nothing was edited.

---

## 0. Summary for implementers

- **Tokens.** An addressee is an `EmailToken{type, userId, role, level}` (`…/email/EmailDtos.java:12-25`). `type` is one of:
  - `USER`: a named internal person.
  - `ROLE`: a POC role, read at `CUSTOMER` level (the customer's POC book) or `RECORD` level (the POC field stored on the record).
  - `CUSTOMER`: the customer's own address plus its active customer logins.
- **Roles.** `EmailRole` has 3 values and maps 1:1 onto `PocType` (`…/email/EmailRole.java:8-11`). `RoleRef(role, level)` is the resolved pair (`…/email/RoleRef.java:5`). Its stored string form is `ROLE:<LEVEL>:<ROLE>`, or `ROLE:<ROLE>` for rows written before levels existed.
- **How a role resolves.** `EmailTargets.load(type,id)` builds a `Target` (`…/email/EmailTargets.java:146-161`). The Target holds `Map<RoleRef, List<Person>> holders`: every *active* holder, primary first. It also holds `customerEmails`. `Target.sender(role)` is the first holder, which is the primary; if the primary is inactive it is the oldest active seat (`:157-160`). This is the existing rule the PRD means by "a role held by several people resolves to the primary". `PocService.defaultAssignee` applies the same rule at customer level (`…/poc/PocService.java:96-99`).
- **Recipients.** In `EmailAddressing.resolve(Plan, Target)` (`…/email/EmailAddressing.java:138-183`) a `ROLE` in To reaches **all** active holders. A role as sender uses only the **first** holder.
- **Durable execution.** Today this is "DB row as outbox":
  1. A request thread saves an `Email` with status `QUEUED` in its own transaction.
  2. `EmailDispatcher` claims the row with a conditional UPDATE, calls the mail service outside any transaction, and records the outcome in a new transaction.
  3. A sweeper runs every 60 s. It picks up QUEUED rows older than 30 s and marks SENDING rows older than 10 min as interrupted.
  4. Idempotency toward the mail service uses the `gi-{emailId}-{recipientId}` key (`…/email/CopyRef.java`).
- **Placeholders.** None exist anywhere. There are no `{{` in backend or frontend. The closest thing is hard-coded suggestion text in `EmailTargets.suggestion` (`:388-502`).
- **Attachments.** Outbound email has none. `Submission` has no attachment field, and the mail service drops attachments on inbound mail.
- **Blocker for automation.** Most of the pipeline requires an HTTP caller. `EmailService.*`, `EmailTargets.load/requireVisible/suggest/bulkIds` and `EmailAddressing.plan` all call `CurrentUser.require()`. That throws `BadCredentialsException` on a background thread. The pieces that work without a user are:
  - `EmailAddressing.resolve`
  - `EmailDispatcher.*`
  - `EmailDeliveryRollup`
  - `PocService.activeHoldersByType`
  - `EmailDirectory`

  Several key types are package-private (see §9), so a new `automation` package cannot build a `Plan` or read `RecipientSet.Entry`.

---

## 1. Files read (in full)

Backend `…/email/`:
- `RoleLevel` (16 lines), `RoleRef` (60), `RecipientSet` (79), `RecipientField` (6), `CopyRef` (29)
- `EmailAddressing` (201), `EmailTargets` (503), `EmailDirectory` (92), `EmailRole` (34)
- `EmailService` (406), `EmailDispatcher` (280), `EmailSweepScheduler` (31), `EmailDeliveryRollup` (110)
- `EmailText` (64), `EmailViews` (285), `EmailDtos` (137), `Email` (164), `EmailRecipient` (101)
- `EmailEntityType` (37), `EmailSchemaUpgrade` (100), `EmailRepository` (72), `EmailRecipientRepository` (56)
- `EmailController` (85), `EmailCascade` (21), `EmailStatus`, `EmailDirection`, `RecipientDeliveryStatus`, `InboxService`, `InboxController`
- `transport/*` (all 15 files), `mailservice/MailServiceClient`, `MailServiceDtos`, `MailServiceEventHandler`, `MailServiceEventsController`

Supporting files:
- `…/poc/PocService.java`, `CustomerPoc.java`, `CustomerPocRepository.java`, `PocType.java`, `ScopeResolver.java`
- `…/auth/CurrentUser.java`, `…/config/OpenEntityManagerInViewConfig.java`
- `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml`

Frontend `frontend/lib/features/email/`:
- `send_email_dialog.dart` (1050 lines), `email_models.dart` (739), `email_providers.dart` (108), `email_entity.dart` (97), `email_actions.dart` (121)

---

## 2. How a recipient is expressed

### 2.1 Wire token: backend and frontend

`…/email/EmailDtos.java:12-25`:
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmailToken(String type, Long userId, String role, String level) {
    public static EmailToken user(Long userId) { return new EmailToken("USER", userId, null, null); }
    public static EmailToken role(RoleRef role) { return new EmailToken("ROLE", null, role.role().name(), role.level().name()); }
    public static EmailToken customer() { return new EmailToken("CUSTOMER", null, null, null); }
}
```
- JSON examples: `{"type":"USER","userId":7}`, `{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"}`, `{"type":"CUSTOMER"}`.
- **Gotcha:** `EmailToken.role(RoleRef)` throws NPE on an unlevelled `RoleRef` because it calls `role.level().name()` (`:19`).

The request DTO is `SendEmailRequest(String entityType, Long entityId, EmailToken from, List<EmailToken> to, String subject, String body)` (`EmailDtos.java:27-34`). There is **no `cc` field**.

The Dart mirror is `EmailToken` in `frontend/lib/features/email/email_models.dart:28-90`:
- Constructors: `.user(int)`, `.role(String key, {level})`, `.customer()`.
- `toJson` omits null fields (`:69-74`).
- Value equality covers all 4 fields (`:77-85`).
- Level constants are `EmailRoleLevel.customer/record` (`:23-26`).

### 2.2 Kinds and validation: `EmailAddressing.plan`

`…/email/EmailAddressing.java`:
- `private enum Kind { USER, ROLE, CUSTOMER }` (`:30`). It is parsed case-insensitively; anything else gives `BadRequestException("to type must be one of …")` (`:104-110`).
- The plan types are:
  ```java
  record From(Person person, RoleRef role) {}
  record To(Person person, RoleRef role, boolean customer) {}
  public record Plan(From from, List<To> to) {}
  ```
  `From` and `To` are **package-private** (`:32-36`).
- `plan(EmailEntityType, from, to)` works per type, e.g. for bulk (`:51-53`). `plan(Target, from, to)` works per record (`:55-57`), and there the offered roles depend on the record (a USER target without a customer offers none).
- The private `plan` (`:59-67`) calls `currentUser.require()`. `restricted = caller.getCustomerId() != null`, i.e. the caller is a customer login.

`checkFrom` (`:69-84`):
- A null token means the sender is the caller (`Person.of(caller)`).
- A restricted caller may only name themselves as a `USER`. Otherwise it gets `AccessDeniedException("Not allowed")`.
- `USER` must satisfy `activeInternalUser` (`:112-120`): the user exists, is active, and has `customerId == null`. Otherwise `BadRequestException("<username> is not an active internal user")`.
- `ROLE` must satisfy `offeredRole`.
- `CUSTOMER` is rejected: `BadRequestException("The sender must be a person or a role")`.

`checkTo` (`:86-102`):
- A null token gives `BadRequestException("A recipient is empty")`.
- `USER` is forbidden to restricted callers ("Customer logins write to roles and their own customer, never to staff by name (E13)"). Otherwise the user must pass `activeInternalUser`.
- `ROLE` must pass `offeredRole`.
- `CUSTOMER` requires `targets.mayHaveCustomer(type)`, i.e. the type is not PRODUCT or ROLE.

`offeredRole` (`:122-132`):
```java
EmailRole role = EmailRole.parse(token.role());
RoleLevel level = token.level() == null || token.level().isBlank()
        ? EmailTargets.defaultLevel(type, role)
        : RoleLevel.parse(token.level());
RoleRef ref = new RoleRef(role, level);
if (!offered.contains(ref)) throw new BadRequestException(ref.label(type) + " is not a role on " + plural(type));
```

### 2.3 Roles and levels

- `EmailRole { SALES_POC(PocType.SALES), CUSTOMER_SUCCESS_POC(PocType.SUCCESS), COLLECTION_POC(PocType.COLLECTION) }` (`…/email/EmailRole.java:8-11`).
  - `label()` is `pocType.label()`: "Sales POC", "Customer Success POC" or "Collection POC" (`…/poc/PocType.java:8-11`).
  - `parse` throws `BadRequestException("role must be one of …")` (`:27-33`).
- `RoleLevel { CUSTOMER, RECORD }` with `parse` throwing `"level must be CUSTOMER or RECORD"` (`…/email/RoleLevel.java:5-16`).
- `RoleRef` (`…/email/RoleRef.java`):
  - Factories `customer(role)`, `record(role)`, and `unlevelled(role)` (level null, for legacy rows) (`:9-20`).
  - `token()` returns `ROLE:CUSTOMER:COLLECTION_POC`, or `ROLE:COLLECTION_POC` when unlevelled (`:22-24`).
  - `parseToken` returns `Optional.empty()` on garbage (`:30-42`).
  - `label(type)` returns "Collection POC (customer)" or "Sales POC (this invoice)" (`:48-51`).
  - `levelLabel(type)` returns "Customer" or the type title, e.g. "Invoice". `groupLabel` appends " level" (`:53-59`).

### 2.4 Roles offered per entity type (`…/email/EmailTargets.java:83-118`)

| Entity type | CUSTOMER level (from the customer's POC book) | RECORD level (field stored on the record) |
|---|---|---|
| CUSTOMER | CUSTOMER_SUCCESS_POC, COLLECTION_POC | – |
| INVOICE | same two | SALES_POC (`Invoice.salesPoc`, `…/invoice/Invoice.java:65-67`, LAZY) |
| PAYMENT | same two | COLLECTION_POC (`Payment.collectionPoc`, `…/payment/Payment.java:46-48`, LAZY) |
| PROMISE | same two | COLLECTION_POC (`PaymentPromise.collectionPoc`, `…/promise/PaymentPromise.java:44-46`, EAGER) |
| DISPUTE | same two | SALES_POC **and** COLLECTION_POC, taken from the disputed invoice or payment. One is always unheld (`:296-300`). |
| USER | same two, but only if the user is a customer login (`rolesOffered(Target)` `:193-196`) | – |
| PRODUCT, ROLE | – | – |

- `CUSTOMER_BOOK = List.of(CUSTOMER_SUCCESS_POC, COLLECTION_POC)` (`:83`). Sales POC is never offered at customer level, because `PocService.add` rejects SALES: "Sales POC is assigned per invoice, not per customer" (`…/poc/PocService.java:119-121`).
- Order is customer level first, then record level (`ROLES_OFFERED` `:114-118`). Tests index roles by position, e.g. `/roles/1` is customer COLLECTION on an invoice.
- `defaultLevel(type, role)`: CUSTOMER if the role is in that type's CUSTOMER_ROLES, otherwise RECORD (`:198-200`). So an unlevelled `SALES_POC` on an invoice resolves to RECORD, and an unlevelled `COLLECTION_POC` on an invoice resolves to CUSTOMER.

### 2.5 CC

`RecipientField { TO, CC }` (`…/email/RecipientField.java`). Compose never produces CC: `resolve` always adds `RecipientField.TO` (`EmailAddressing.java:154,162,170`). CC rows come only from inbound mail (`EmailInboundService.java:212-246`). Only TO rows get a delivery copy and appear in the Inbox (`EmailService.java:291-292`, `InboxController.ownedByCaller`).

---

## 3. From role to people to addresses

### 3.1 `EmailTargets.load` and `describe`

```java
@Transactional(readOnly = true)
public Target load(EmailEntityType type, Long id) {           // :221-225
    requireTypeAccess(type);
    return describe(type, loadScoped(type, id));
}
```
- `loadScoped` (`:240-254`) goes through the domain services' scoped `get`: `customerService.get` (book check, `…/customer/CustomerService.java:130-138`), `invoiceService.get`, `paymentService.get`, `promiseService.get` (inScope with `ScopeResolver`, `…/promise/PaymentPromiseService.java:505-520`), `disputeService.get`. PRODUCT, USER and ROLE go straight to the repository.
- `loadUnscoped` (`:227-238`) is **private**. It is used only by `suggest`.
- `describe` (`:256-294`) is **private**. It builds the label ("Invoice INV-001", "Payment #12" and so on), finds the customerId, and collects the record-level POC fields as `Map<EmailRole, Optional<User>>`.
- `target(...)` (`:302-306`) calls `holders(type, customerId, ownFields)` and `customerEmails(customerId)`.

`holders` (`:308-326`):
```java
for (RoleRef ref : ROLES_OFFERED.get(type)) {
    if (ref.level() == RoleLevel.RECORD) people = ownFields.getOrDefault(ref.role(), Optional.empty()).stream().toList();
    else if (customerId == null)          people = List.of();
    else { if (book == null) book = pocService.activeHoldersByType(customerId);   // read once per target
           people = book.getOrDefault(ref.role().pocType(), List.of()); }
    List<Person> active = people.stream().filter(User::isActive).map(Person::of).toList();
    if (!active.isEmpty()) holders.put(ref, active);          // unheld roles are ABSENT from the map
}
```

`customerEmails` (`:328-340`) returns:
- the Customer's own `email`, as a Person with `userId` null, `internal=false` and `customerId` set;
- every active customer login that has an email, via `EmailDirectory.activeLoginsOf` (`…/email/EmailDirectory.java:40-46`).

`Person` (`:139-144`):
```java
public record Person(Long userId, String name, String address, Long customerId, boolean internal) {
    static Person of(User u) { return new Person(u.getId(), EmailText.nameOf(u), Emails.normalize(u.getEmail()),
                                                 u.getCustomerId(), u.getCustomerId() == null); }
}
```
`Person.of` is **package-private**. `nameOf` returns fullName, or username if fullName is blank (`EmailText.java:44-46`). `Emails.normalize` is only `blankToNull` (`…/common/Emails.java:7-9`); it does not lowercase.

### 3.2 How "primary" is determined

- The storage is `customer_pocs(customer_id, user_id, poc_type, is_primary, created_by_user_id, created_at)`, with a unique constraint on (customer, user, type) (`…/poc/CustomerPoc.java:10-43`).
- `PocService.activeHoldersByType` (`…/poc/PocService.java:106-115`) iterates `findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc` (`CustomerPocRepository.java:15`) and keeps active users. Each list is therefore **primary first, then by seat id (oldest first)**, with inactive users removed.
- `Target.sender(role)` is `holders(role).stream().findFirst()` (`EmailTargets.java:157-160`), documented as "an email has one sender, so the first holder (L5)". `PocService.defaultAssignee(customerId, type)` does the same (`PocService.java:96-99`).
- `PocService.primaryFor` (`:88-94`) differs: it returns the flagged primary even if that user is inactive. **Use `defaultAssignee` / `Target.sender` semantics for A4 and assignees, not `primaryFor`.**
- The invariant is at most one primary per (customer, type), kept under a pessimistic lock on the customer row (`add` `:117-163`, `remove` `:171-222`, `setPrimary` `:224-247`). When the primary is removed, the oldest remaining seat is promoted (`:199-203`).
- Tests pin this behaviour:
  - `backend/src/test/java/com/geneinvoice/email/EmailSendTest.java:160-189`: a To role reaches every active holder, primary first.
  - `EmailSendTest.java:207-227`: a From role sends as the first holder; after the primary is deactivated, the next one sends.
  - `EmailContextTest.java:100-121`.
- Record-level roles hold exactly one person (the FK), or none.

### 3.3 `EmailAddressing.resolve(Plan, Target)`: no CurrentUser (`:138-183`)

**From:**
- If `plan.from().role() != null`, then `from = target.sender(fromRole)`.
- If nobody holds the role, `problems` gets "Nobody holds X on Y, so it cannot be the sender".

**To.** Each entry goes into a `RecipientSet`:
- A person gives `add(TO, person, "USER")`.
- A role gives `add(TO, holder, role.token())` for **every** holder. With no holders, `missing[token] = "<label> is not assigned"`.
- A customer gives `add(TO, address, "CUSTOMER")` for each entry in `customerEmails`. With none, `missing["CUSTOMER"]` is set.

**Problems:**
- An empty plan gives "Add at least one recipient".
- A plan that resolves to nobody gives "No recipients: A, B and C".
- Unresolved tokens become `EmailDtos.Unresolved(token, label, reason)` through `describeUnresolved` (`:190-200`, which is package-static and also used by `EmailViews`).

The result is `Resolution(Person from, RoleRef fromRole, List<RecipientSet.Entry> recipients, List<Unresolved> unresolved, List<String> problems)`. `problem()` returns the first problem (`:38-49`).

### 3.4 `RecipientSet`: dedupe and merge (`…/email/RecipientSet.java`, package-private)

- Two drafts are the same person if they share a `userId`, or their addresses match case-insensitively (`:22-25`).
- On merge (`absorb` `:27-38`): user identity wins over address-only, TO beats CC, and `sources` are unioned in insertion order.
- `Entry(field, userId, customerId, name, address, internal, List<String> sources)` (`:10-11`).
- Test example: `sources = "USER,ROLE:CUSTOMER:COLLECTION_POC"` (`EmailSendTest.java:192-205`).

### 3.5 Persisted shape

`Email` (`…/email/Email.java`):
- Annotations: `@Entity @DynamicUpdate @Table(name="emails", indexes=…) @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.
- Limits: `LABEL_MAX=200`, `NAME_MAX=200`, `ADDRESS_MAX=320`, `UNRESOLVED_MAX=1000`, `ERROR_MAX=1000` (`:26-31`).
- Columns:
  - `entity_type` (enum STRING, length 20) and `entity_id`
  - `entity_label` (snapshot) and `entity_deleted`
  - `direction`, `status`
  - `subject` (length 500 = `FieldLimits.EMAIL_SUBJECT`) and `body` (length 20000)
  - `from_user_id`, `from_role`, `from_role_level` (null on legacy rows), `from_name` (NOT NULL), `from_address`, `from_customer_id`, `from_internal`
  - `delivered_from`
  - `sent_by_user_id` (nullable)
  - `unresolved` (comma-joined tokens), `error`
  - `attempts`, `next_attempt_at`, `handed_off_at`
  - `batch_id`
  - `occurred_at`, `sent_at`, `created_at`, `updated_at`
- `@PrePersist` sets createdAt, updatedAt and occurredAt. `@PreUpdate` sets updatedAt (`:146-159`).

`EmailRecipient` (`…/email/EmailRecipient.java`):
- `@ManyToOne(fetch=LAZY) email`
- `field` (length 4), `user_id`, `customer_id`, `name`, `address`, `internal`
- `sources` (NOT NULL, `SOURCES_MAX=300`, comma-joined)
- `is_read` and `read_at` (the in-app Inbox)
- `delivery_status` (`RecipientDeliveryStatus`, null means no copy), `delivery_error`, `delivery_seq` (monotonic)
- sent, delivered, read and bounced timestamps, and provider ids
- Index `idx_email_recipient_inbox(user_id, field, is_read)`.

`EmailService.save` (`…/email/EmailService.java:259-295`) is the only writer of outbound rows. It is **private**:
```java
.fromUserId(from.userId()).fromRole(...role()).fromRoleLevel(...level())
.fromName(EmailText.fit(from.name(), Email.NAME_MAX)).fromAddress(from.address())
.fromCustomerId(from.customerId()).fromInternal(from.internal())
.sentByUserId(currentUser.require().getId())            // needs a caller
.unresolved(EmailText.fit(resolution.unresolvedTokens(), Email.UNRESOLVED_MAX)).batchId(batchId)
...
.deliveryStatus(r.field() == RecipientField.TO && r.address() != null ? RecipientDeliveryStatus.QUEUED : null)
```

**In-app delivery is independent of Gmail.** Every TO row with a `userId` shows in that user's Inbox:
- `InboxController.ownedByCaller` filters on `userId = me AND field = TO` (`…/email/InboxController.java`, near the end).
- The unread count is `countByUserIdAndFieldAndReadFalse` (`InboxService.java:17-20`).

This is a ready model for "people need somewhere to see their work" (A6).

---

## 4. Send pipeline and durable execution

### 4.1 `EmailService.send`: transaction boundaries (`…/email/EmailService.java:218-229`)

```java
public EmailDtos.EmailDto send(EmailDtos.SendEmailRequest req) {       // NOT @Transactional
    EmailTargets.Target target = targets.load(...);                    // tx A (readOnly, own)
    Content content = Content.check(req.subject(), req.body(), req.to());
    EmailAddressing.Resolution resolution = addressing.resolve(addressing.plan(target, req.from(), req.to()), target);
    resolution.problem().ifPresent(problem -> { throw new BadRequestException(problem); });
    Long id = transactions.execute(status -> save(target, content, resolution, null));  // tx B commits QUEUED
    dispatcher.dispatch(id);                                           // synchronous, outside any tx
    return get(id);
}
```

`Content.check` (`:84-104`) is a private record. It applies:
- `EmailText.storable`, which strips NUL characters and replaces lone surrogates.
- `EmailText.oneLine` on the subject.
- A blank subject, subject over 500, body over 20000, or empty `to` throws `GlobalExceptionHandler.InvalidFieldsException(Map<field,msg>)` (400 with field errors).

`/api/emails/**` is **excluded from Open-EntityManager-in-View**, so a mail-service call never holds a pooled connection (`…/config/OpenEntityManagerInViewConfig.java:12-13`; comment at `application.yml:7-8`). Everything that touches lazy associations is done inside `@Transactional` methods for this reason, e.g. `load` reads `i.getSalesPoc()` inside its readOnly transaction.

### 4.2 Bulk (`:231-257`)

1. Check the type once with `requireTypeAccess`.
2. Build the `Plan` once per type with `addressing.plan(type, from, to)`.
3. Get the ids from `targets.bulkIds` (scoped by `ScopeResolver` predicates, capped at `BULK_ID_LIMIT`).
4. Run `bulkExecutor.run`. Each record gets its own `REQUIRES_NEW` transaction (`…/common/bulk/BulkExecutor.java:22-25`) and does load, resolve and save. A per-record problem throws `BulkExecutor.IneligibleException(problem)`, which skips that record.
5. After all records, call `dispatcher.dispatchAll(ids)`. This is async on a single background thread unless `app.mail.dispatch.async=false`.

All emails in one bulk run share a `batchId` (UUID).

### 4.3 `EmailDispatcher.dispatch` (`…/email/EmailDispatcher.java:71-95`)

```java
if (TransactionSynchronizationManager.isActualTransactionActive())
    throw new IllegalStateException("Email must be dispatched outside a transaction");
Submission submission = transactions.execute(status -> claim(emailId, now));       // tx 1
if (submission == null) return;
try { states = transport.submit(submission); }                                      // HTTP, no tx
catch (MailSendException e) { transactions.executeWithoutResult(s -> failed(emailId, e.getMessage(), e.isTransientFailure())); return; }  // tx 2a
catch (RuntimeException e)  { ... failed(emailId, "Delivery failed: …", false) ...; return; }                                               // tx 2b (permanent)
transactions.executeWithoutResult(status -> handedOff(emailId, submission, states));                                                         // tx 2c
```

`claim` (`:156-189`) is a compare-and-set. `EmailRepository.claim` (`…/email/EmailRepository.java:32-42`):
```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""update Email e set e.status = SENDING, e.attempts = e.attempts + 1, e.updatedAt = :now
          where e.id = :id and e.status = QUEUED and e.handedOffAt is null
            and (e.nextAttemptAt is null or e.nextAttemptAt <= :due)""")
```
The UPDATE sets `updatedAt` by hand because a JPQL bulk update skips `@PreUpdate`. If zero rows change, another worker owns the row and the call returns null. Otherwise `claim`:
- backfills QUEUED on TO copies of legacy rows;
- short-circuits to `notSent` (§4.4);
- builds `Submission(email.getFromUserId(), fromName, subject, body, groupRef=String.valueOf(emailId), retry=true, copies=[CopyRequest("gi-{emailId}-{recipientId}", name, address)])`.

`handedOff` (`:205-232`) takes a `PESSIMISTIC_WRITE` lock with `findByIdForUpdate` (`EmailRepository.java:28-30`). It then:
- applies each returned `CopyState`;
- marks any requested copy the service did not report as FAILED ("The mail service did not accept this copy");
- sets `handedOffAt`;
- rolls up the email status.

### 4.4 Terminal "not sent" cases (in `claim`, no retry)

Each of these marks the queued copies `NOT_SENT` and sets `nextAttemptAt = null` (`notSent` `:191-203`):

| Constant | Condition |
|---|---|
| `NOT_CONFIGURED` = "Email delivery is not configured (mail service)" | `transport.isConfigured()` is false |
| `NO_ADDRESS` = "No recipient has an email address" | no copies at all |
| `CUSTOMER_SENDER` = "Email from a customer login is not sent through Gmail" | `!email.isFromInternal()` |

A missing Gmail connection for the sender is reported per copy by the mail service. `EmailViews` recognises the suffixes " has not connected Gmail" and "'s Gmail connection needs to be renewed" (`…/email/EmailViews.java:144-145`).

### 4.5 Retry policy

`failed` (`:255-279`), locked:
- It only acts if the row is still `SENDING` and not handed off.
- If `transientFailure && attempts < MAX_ATTEMPTS (3)`: status goes back to `QUEUED`, `error` is set, and `nextAttemptAt` is now + 1 min when `attempts <= 1`, otherwise now + 5 min.
- Otherwise the queued copies become FAILED and the email status is rolled up (or set to FAILED).

Which failures are transient is decided in `MailServiceClient.submit` (`…/email/mailservice/MailServiceClient.java:72-97`):

| Response | Transient? |
|---|---|
| IOException or interrupt | yes |
| 5xx | yes |
| 4xx | no |
| other non-2xx | no |
| unparsable body | yes |

Any non-`MailSendException` RuntimeException is permanent (`EmailDispatcher.java:88-92`).

Manual retry is `EmailService.retry` (`:297-320`):
1. Check visibility.
2. If the viewer is a customer, check they took part.
3. Check `mayRetry`.
4. In a locked transaction, `EmailDeliveryRollup.canRetry` must pass (status FAILED, NOT_SENT or PARTIAL, with some copy FAILED or NOT_SENT). Then `requeue` resets attempts to 0, clears handedOffAt, error and nextAttemptAt, and sets retried copies back to QUEUED (`EmailDeliveryRollup.java:78-104`).
5. Call `dispatch(id)`.

### 4.6 The sweeper (the recovery path)

`EmailSweepScheduler` (`…/email/EmailSweepScheduler.java:17-30`):
```java
@Scheduled(fixedDelayString = "${app.mail.dispatch.sweep-interval-ms:60000}",
        initialDelayString = "${app.mail.dispatch.sweep-interval-ms:60000}")
public void sweep() { try { dispatcher.sweep(); } catch (RuntimeException e) { log.warn(...); }
                      try { gmailDisconnects.sweep(); } catch (RuntimeException e) { log.warn(...); } }
```

`EmailDispatcher.sweep(now)` (`:123-142`):
1. **Stale claims.** `findStaleSending(touchedBefore = now - 10 min)` (`EmailRepository.java:54-61`). For each id, `interrupted` (`:234-253`) re-checks under lock and marks the queued copies FAILED with "Sending was interrupted; retry to send again". **These are not auto-requeued**: the mail service may already have the copies, so a human must retry. The mail service dedups on the external id.
2. **Due work.** `findDue(now, createdBefore = now - SETTLE(30 s), page = 200 + held)` (`EmailRepository.java:44-52`). It skips ids in `backgroundOwned`, which the async executor is holding, then calls `dispatchQuietly` for each. The 30 s settle window stops the sweeper racing the request thread's own `dispatch`.

- `dispatchQuietly` (`:148-154`) swallows exceptions and logs "the sweeper will pick it up".
- The executor is `Executors.newSingleThreadExecutor` with a daemon thread named "email-dispatch" (`:49-53`) and `@PreDestroy shutdownNow` (`:66-69`).
- `RejectedExecutionException` leaves rows QUEUED for the sweeper (`:113-116`).

### 4.7 Mail service contract and delivery events

- `MailTransport { boolean isConfigured(); List<CopyState> submit(Submission); }` (`…/email/transport/MailTransport.java`).
- It is chosen by `app.mail.transport`:
  - `none` (the default) gives `NoopMailTransport`, whose `submit` throws a non-transient `MailSendException(NOT_CONFIGURED)` (`…/email/transport/NoopMailTransport.java:13-27`).
  - `mail-service` gives `MailServiceClient`, which POSTs to `/api/v1/messages` with the `X-Api-Key` header.
- `Submission(long senderUserId, String senderName, String subject, String body, String groupRef, boolean retry, List<CopyRequest> copies)` (`…/email/transport/Submission.java:5-6`). **Plain text body only, no attachments, no CC.**
- Delivery updates arrive by webhook at `POST /api/mail-service/events`:
  - The controller verifies the HMAC headers `X-Mail-Timestamp` and `X-Mail-Signature`, limits body size, and allows only 2 concurrent readers (`…/email/mailservice/MailServiceEventsController.java:45-101`).
  - Each event is applied on its own. If `databaseUnavailable(e)` is true, the controller returns 503 so the mail service resends. Any other failure is logged and skipped.
  - `copyChanged` (`MailServiceEventHandler.java:96-125`) locks the email, applies the copy only if `state.seq > copy.deliverySeq` (monotonic, `EmailDeliveryRollup.applyCopy` `:28-45`), and rolls up.
- Rollup (`EmailDeliveryRollup.status` `:57-66`), in order:
  1. Any copy SENDING gives SENDING.
  2. Otherwise any QUEUED gives QUEUED.
  3. All copies in {SENT, DELIVERED, READ} give SENT.
  4. All NOT_SENT gives NOT_SENT.
  5. All in {FAILED, BOUNCED, NOT_SENT} gives FAILED.
  6. Anything else gives PARTIAL.
- The mail-service itself (separate app, reference only) uses RabbitMQ:
  - durable `mail.send` queue;
  - TTL retry queues `mail.send.retry.1m` and `mail.send.retry.5m`, which dead-letter back to send;
  - dead-letter queue `mail.send.dead`;
  - `AcknowledgeMode.AUTO` with `defaultRequeueRejected=false`.

  See `mail-service/src/main/java/com/geneinvoice/mail/config/RabbitTopology.java:38-84`, `message/RabbitSendQueue.java`, `message/SendListener.java`. **The backend has no AMQP dependency** (`backend/pom.xml`), and `docker-compose.yml` runs only postgres.

### 4.8 Configuration and test knobs

- `application.yml:56-60`: `app.mail.transport: ${MAIL_TRANSPORT:none}`, `dispatch.async: true`, `dispatch.sweep-interval-ms: 60000`, `service.read-timeout-ms: 40000`.
- `application.yml:25-30`: `spring.task.scheduling.pool.size: 3`, with the comment "The email sweeper and the promise sweeper each get a thread". **A new automation scheduler competes for this pool**, so raise it.
- `application-test.yml:29-37`: `transport: none`, `dispatch.async: false` (bulk dispatch runs inline), `sweep-interval-ms: 3600000` (tests call `dispatcher.sweep()` directly).
- Tests swap in `RecordingMailTransport` (`backend/src/test/java/com/geneinvoice/email/RecordingMailTransport.java`), with modes `NOT_CONFIGURED`, `SUCCESS`, `TRANSIENT_FAILURE` and `PERMANENT_FAILURE`, plus `submissions()` and `copiesHandedOver()`.
- Test fixtures (`EmailTestBase.java`) provide `seat`/`extraSeat`/`threeCollectionSeats`, `toUser`/`toRole(role[,level])`/`toCustomer`, and `send(caller, email(...))`.
- The `PromiseSweepScheduler` pattern is `@Scheduled` plus `@EventListener(ApplicationReadyEvent)` to sweep on startup (`…/promise/PromiseSweepScheduler.java:17-30`). It runs with no user, and `sweepOverdue` never touches `CurrentUser`.

### 4.9 Pattern to copy for A5 (rule trigger → event → consumer)

1. Persist the event as a row: an outbox table with status, attempts, `next_attempt_at`, `handed_off_at` or `processed_at`, and created/updated timestamps, plus a `@DynamicUpdate` entity and a status/next-attempt index like `idx_email_dispatch` (`Email.java:17`).
2. Claim it with a conditional `@Modifying` UPDATE that also sets `updatedAt` by hand.
3. Perform the side effect outside the transaction.
4. Record the outcome under `findByIdForUpdate` in a new transaction.
5. Sweep for due rows (with a settle window) and for stale claims.
6. Give each downstream effect an idempotency key, as `CopyRef` does, so a re-run cannot double-act. An automation "create Task" should store e.g. `(rule_id, event_id)` as a unique key.

Rule evaluation must not run in the user's save path. The save should at most insert a tiny event row (or register an afterCommit hook, with the caveat in §9 item 3).

---

## 5. Document attachments

- **Not supported on email.** The chain `SendEmailRequest` → `Email` → `Submission` → `MailServiceDtos.SubmitRequest(Sender, subject, body, groupRef, retry, copies)` (`…/email/mailservice/MailServiceDtos.java:19-27`) has no attachment field anywhere.
- On inbound mail, attachment parts are skipped: `isAttachment` checks disposition or filename (`mail-service/src/main/java/com/geneinvoice/mail/gmail/GmailMime.java:214,226-228`).
- Documents are a separate feature (`…/document/*`, with `DocumentEntityType` starting CUSTOMER, INVOICE, …). `DocumentService.download(id)` returns a `Download(filename, sizeBytes, InputStream)` (`…/document/DocumentService.java:58,141`).
- Attaching documents would need a backend↔mail-service contract change: `Submission`, `SubmitRequest`, and MIME building in the mail-service.

---

## 6. Permissions when sending (all depend on `CurrentUser`)

- **Endpoint level** (`…/email/EmailController.java`):
  - `@PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")` on `/context`, `/people`, `/preview`, `POST /`, `/bulk` and `/{id}/retry`.
  - `EMAIL_VIEW` on list, get, `/delivery` and `/sync`.
  - The constants are at `…/privilege/Privileges.java:46-47`.
- **Record level**, `EmailTargets.requireTypeAccess` (`:206-213`):
  - `currentUser.has(VIEW_PRIVILEGE.get(type))` (map at `:69-77`: CUSTOMER_VIEW, INVOICE_VIEW, …).
  - A customer login is further limited to `CUSTOMER_READABLE = {CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE}`.
  - Then `loadScoped` applies the domain service's book or ownership scope. A record outside scope answers NotFound or AccessDenied (AUTH-08 comments in `InvoiceService.get`, `PaymentPromiseService.get`).
- **Addressee level** (§2.2):
  - A customer login cannot name staff or pick a sender.
  - A `USER` must be an active internal user.
  - A role must be offered for the type.
- **People search**: `EmailService.people` rejects customers (`:177-185`). `EmailDirectory.searchPeople` returns all active internal users, not scoped, limit 20 (`EmailDirectory.java:23-34`).
- **Reading an email** (`EmailService.get` `:336-355`): a party to the email (sender, sentBy or recipient) can read it even without record visibility, in which case `canOpenRecord=false`. A customer viewer must have taken part (`EmailViews.customerTookPart` `:51-65`).
- **Masking** (`EmailViews.shown` `:239-247`): a customer viewer sees internal people as their role label, or "Gene Invoice team" (`EmailText.TEAM`), with no address. Errors are masked as "Could not be delivered" (`:133-142`). The context endpoint hides holders' names from restricted callers (`EmailService.context` `:129-133`).
- **Audit**: the email path writes **no** `AuditService` rows. The `Email` row is its own record. The convention elsewhere is `auditService.record(entityType, entityId, action, before, after, userId, disputeId, reason)` (`…/audit/AuditService.java:18-32`; example `PocService.java:145-149`).

---

## 7. Placeholders (A4)

- **None exist.** No `{{`, "template" or "placeholder" machinery appears in `backend/src/main/java` or `frontend/lib`.
- The closest thing is the hard-coded per-type, per-event suggestion text in `EmailTargets.suggestion(target, entity, event, zone)` (`:388-502`), exposed through `suggest` (`:373-386`, which truncates to the field limits). Examples:
  - "Invoice " + number + " for " + `EmailText.money(i.getTotal())`
  - "Balance due: " + `EmailText.money(i.getBalance())`
  - customer name through `i.getCustomer().getName()`
- Formatting helpers already exist in `EmailText` (package-private, `…/email/EmailText.java`):
  - `money(BigDecimal)` → `Money.format`
  - `date(Instant, ZoneId)` and `date(LocalDate)` (ISO)
  - `humanize(Enum)`
  - `nameOf(User)`
  - `fit(text, max)` (adds "…", surrogate-safe)
  - `oneLine`
  - `storable`
- **Time zone**: suggestions use the reader's zone from `utcOffsetMinutes` (`EmailService.readerZone` `:153-160`). An automation has no reader, so it needs a configured zone or `ZoneId.systemDefault()`.
- Type differences: `Invoice.invoiceDate` is an `Instant`, while `PaymentPromise.promisedDate` is a `LocalDate`.
- Suggested design: a `PlaceholderResolver(Target, entity, zone)` that maps
  - `{{Customer.*}}` to fields of the target's customer;
  - `{{Invoice.*}}`, `{{Payment.*}}` etc. to record fields;
  - role placeholders such as `{{Customer.CollectionPoc}}` or `{{Invoice.SalesPoc}}` to `RoleRef.customer(...)` / `RoleRef.record(...)`, then `target.sender(ref)`, which is the primary.

  After expansion, re-run the `Content.check` rules. Expansion can push the subject over 500 or the body over 20000, and a value containing a newline breaks the one-line subject rule.
- The existing trigger vocabulary is `EmailTargets.Event { CREATED, UPDATED }` with `parse` (`:163-173`); Dart has `EmailEvent` (`email_models.dart:7-12`). The frontend already offers compose after a save via `NotifyByEmailCheckbox` / `notifyByEmailAfterSave` (`frontend/lib/features/email/email_actions.dart:88-121`). Callers:
  - `customers_screen.dart:208`
  - `record_payment_dialog.dart:29`
  - `invoice_form_screen.dart:189`
  - `dispute_create_dialog.dart:35`
  - `dispute_detail_screen.dart:105` (UPDATED)
  - `products_screen.dart:47`

  This is a manual, UI-driven precursor to A1 triggers.

---

## 8. Frontend recipient picker (for reuse in a Task assignee picker or rule editor)

### 8.1 Data sources

`frontend/lib/features/email/email_providers.dart`:
- `emailContextProvider` is a `FutureProvider.autoDispose.family<EmailContext, ({EmailEntityType type, int? entityId, EmailEvent? event})>` (`:12-24`). It calls `GET /api/emails/context` with `entityType`, `entityId?`, `event?` and `utcOffsetMinutes`.
  - With **no `entityId`** it returns the type-level role options (`resolved=null`, `people=[]`) (`EmailService.context` `:125-128`). This is exactly what a rule editor needs.
- `searchEmailPeople(dio, q)` calls `GET /api/emails/people` (`:88-94`).
- `searchEmailRecords(dio, type, q)` calls the type's list API with `EmailEntityType.searchFilters` and `pickerSort` (`:96-108`, `email_entity.dart:34-66`).
- **Gotcha:** `/context` and `/people` both require `EMAIL_SEND`. A Task assignee picker built on them would be hidden from users without the email privilege. It needs its own endpoint, or the email one must be relaxed.

### 8.2 Models (`email_models.dart`)

- `EmailRoleOption(role, label, level, levelLabel, groupLabel, resolved, people, sender)` (`:236-287`), with:
  - `token` → `EmailToken.role(role, level: level)` (`:282`);
  - `labelWithLevel` → "X (customer)" or "X (this invoice)" (`:284-286`);
  - `fromJson`, which defaults a missing level to CUSTOMER (`:265-280`).
- `EmailContext` (`:421-492`):
  - `roles`, `restricted`, `self`, `customerEmailsAvailable`, `customerAddresses`, `suggestion`;
  - `role(key, {level})` and `roleOf(token)` (`:472-480`), which resolve unlevelled tokens to the first offered;
  - `roleGroups`, which groups roles by level in server order with `groupLabel` (`:482-491`).
- `EmailPerson(userId, name, username, email, gmail)` (`:299-321`).
- `EmailPreview(from, to, unresolved, problems, warnings)` (`:509-534`).
- `EmailParticipant` with `sources` and `howAdded` (`:188-234`).
- `EmailSource.description` (`:112-120`).

### 8.3 Widget: `SendEmailDialog` (`send_email_dialog.dart`)

It is a single `ConsumerStatefulWidget` with 3 modes (`SendEmailMode { single, picker, bulk }`, `:15-21`):
- `openSendEmailDialog(context, type, entityId, entityLabel?, event?)` (`:31-50`) returns an `EmailComposeOutcome`.
- `openSendEmailForPickedRecord` (`:52-60`).
- **`openBulkEmailParams(context, type)` (`:62-69`)** returns a `Map`, `{entityType, from?, to:[token json], subject, body?}`, **without sending**. On submit, bulk mode does `Navigator.pop(_fields())` (`:298-301`), and role chips read "Collection POC (each record's)" (`_roleText` `:325-329`). This is the closest existing thing to a rule's "send email" action editor. It needs a new mode for rule-specific wording and placeholder insertion; today's title reads "Selected invoices — a separate email for each" (`:497-498`).

State: `_from` (`EmailToken?`, where null means "Me"), `_to` (`List<EmailToken>`), and `_people` (a cache of userId to `EmailPerson` for chip labels) (`:99-102`).

**To field** (`_toField`, `:694-782`):
1. An `InputDecorator` labelled "To *" wraps a `Wrap` of `InputChip`s, one per token.
   - Icons: person for USER, badge for ROLE, business for CUSTOMER.
   - Labels come from `_tokenText` (`:405-412`), which shows the level only when the same role is chosen at both levels.
   - Tooltips list holders when there are 2 or more (`_roleTooltip` `:346-349`).
2. An "Add" row. The "Person…" `ActionChip` appears only when `!ctx.restricted` and opens `_pickPerson` (`:237-260`).
3. One row per `ctx.roleGroups`, holding `ActionChip`s for roles not yet chosen. Their labels (`_roleText` `:323-341`) show holders: "· nobody assigned", "· Name <addr>", "· A, B" or "· A + n more".
4. A "Customer emails" chip, disabled when `!customerEmailsAvailable`.

**From field** (`_fromField`, `:592-662`): a `DropdownButton<String>` with these values:
- `'self'`
- disabled `'group:<LEVEL>'` headers
- `'role:<ROLE>:<LEVEL>'` (encoded by `_fromRoleValue`, decoded by `_fromRoleToken` `:666-672`), each showing the resolved sender
- `'user:<id>'`
- `'other'`, which opens the person search

`FromPickerItem` (`:1014-1050`) is **public**.

**Search dialog**: `_SearchDialog<T>` (`:914-1012`) is generic over `search`, `labelOf` and `subtitleOf`, with a 250 ms debounce. It is **private (underscore)**, so it must be extracted to reuse it.

**Live preview**: a 400 ms debounce, then `POST /api/emails/preview` with a sequence guard `_previewSeq` (`:156-192`). `_previewPanel` (`:784-855`) shows resolved recipients, "Not assigned" entries and problems. Send is disabled while `problems` is non-empty (`:438`).

The suggestion seeds the form once and never overwrites typed text (`_seed` `:196-216`).

Existing entry points and privilege providers are in `email_actions.dart`:
- `canSendEmailProvider`, `canViewEmailProvider` (`:18-21`)
- `sendEmailRowAction`, `sendEmailPageAction`, `sendEmailHeaderButton`, `emailDetailTab`
- `sendEmailBulkAction` (endpoint `/api/emails/bulk`, `:56-64`)

**Reuse plan for a Task assignee picker.** Extract into a shared widget:
- the chips row and "Add" rows (`_toField`),
- `_SearchDialog`,
- the `EmailToken`, `EmailRoleOption` and `EmailContext` models.

Parameterise it by a context provider, which could be a new neutral `/api/.../assignable-context` returning the same `RoleOption` shape, and by a people-search function. Drop the Customer-emails row, since assignees must be internal users. A role's people list already carries the primary first, so `people.first` is the primary assignee.

---

## 9. Risks and gotchas

1. **No caller on background threads.** These all call `CurrentUser.require()` and throw `BadCredentialsException` without an authenticated request:
   - `EmailAddressing.plan` (`:60`)
   - `EmailTargets.load` / `requireVisible` / `requireTypeAccess` (`currentUser.has`, `isCustomer`)
   - `EmailTargets.suggest` (`:379`)
   - `EmailTargets.bulkIds` (via `ScopeResolver`)
   - `EmailService.send`, `bulk`, `preview` and `context`
   - `EmailService.save` (`:277` `sentByUserId`)

   The domain `get()` methods are scoped to the caller too. An automation consumer therefore needs one of two things:
   - (a) a system path: an unscoped `describe` plus a plan builder that does not consult the caller; or
   - (b) running "as" the rule owner by installing a `SecurityContext`. There is no helper for this; only `JwtAuthFilter` sets it.

   With (b), rules silently skip records outside the owner's book, and under B1 outside the owner's regions.
2. **Package-private types block reuse from other packages:**
   - `EmailAddressing.From` and `To` (`:32,34`), so `Plan` cannot be built outside `com.geneinvoice.email`
   - `RecipientSet` and its `Entry` (`RecipientSet.java:8,10`), even though `Resolution.recipients()` is public
   - `Person.of` (`EmailTargets.java:140`)
   - `EmailText` (whole class)
   - `EmailService.Content` and `save` (private)
   - `EmailTargets.describe` and `loadUnscoped` (private)
   - `EmailSchemaUpgrade.widen` (package-private static on a package-private class)

   Either put the automation "email action adapter" inside the `email` package or widen visibility.
3. **`dispatch()` refuses to run inside a transaction** (`EmailDispatcher.java:76-78`). Calling `EmailService.send` from a `@Transactional` consumer makes `transactions.execute(save)` join the outer transaction, and then `dispatch` throws `IllegalStateException`. Spring still reports a transaction as active during `afterCommit` callbacks, so `@TransactionalEventListener(AFTER_COMMIT)` hits the same guard. `dispatchAll` with `async=false` (tests) runs inline, and `dispatchQuietly` would swallow that exception.

   Safe options:
   - save the rows in the consumer's transaction and let the sweeper deliver them (within 30–90 s), or
   - call `dispatcher.dispatchAll(ids)` after the transactional method has returned.
4. **The sender must be a real internal user.** `Submission.senderUserId` is a primitive `long` (`Submission.java:5`) and `claim` passes `email.getFromUserId()` (`EmailDispatcher.java:185`). A `fromInternal=true` email with a null `fromUserId` throws NPE inside the claim transaction, which rolls the claim back. The row stays QUEUED and the sweeper retries it every minute forever, with no FAILED status and no attempt count. Mail goes out from the sender's own connected Gmail (M4); with no connected Gmail the copies come back NOT_SENT. An automation email must pick one of:
   - a USER sender;
   - a ROLE sender, whose first holder must exist (otherwise the resolve problem "Nobody holds …, so it cannot be the sender");
   - a designated rule owner.
5. **`from_name` is NOT NULL** (`Email.java:80-81`). `sent_by_user_id` is nullable, and `EmailViews.sentBy` handles null (`:153-161`). For automation, consider null or the rule owner, and possibly a new "sent by rule" marker column.
6. **Enum check constraints.** Hibernate generates `check (col in (...))` for `@Enumerated(STRING)` columns, and `ddl-auto: update` never alters them. `EmailSchemaUpgrade.widen` exists for exactly this reason, but it is called **only for `emails.status`** (`EmailSchemaUpgrade.java:32-38`). Each of these needs a new `widen(...)` call, or existing Postgres databases reject the new value at runtime:
   - a new `EmailEntityType` (e.g. TASK) → `emails.entity_type`
   - a new `EmailRole` → `emails.from_role`
   - a new `RecipientDeliveryStatus` → `email_recipients.delivery_status`
   - new values in any new enum columns

   H2 tests use `create-drop`, so they will not catch this. The schema-upgrade convention (`EmailSchemaUpgrade.java:21-38`, `…/poc/PocSchemaUpgrade.java:16-27`) is: a package-private `@Component` class implementing `InitializingBean`, whose constructor takes `(DataSource, EntityManagerFactory schemaUpToDate)` so it runs after Hibernate DDL, and which switches on `getDatabaseProductName()` for postgres and H2. Also note `hibernate.hbm2ddl.halt_on_error: ${SCHEMA_HALT_ON_ERROR:true}` (`application.yml:24`): a NOT NULL column added to a populated table **stops startup**.
7. **Unheld roles are absent from `Target.holders`** (`EmailTargets.java:323`). Use `target.holders(ref)` (returns an empty list) rather than `holders().get(ref)`. A DISPUTE always has one unheld record-level role.
8. **Inactive holders are filtered out at resolve time.** An email resolves holders when it is sent and stores userIds as a snapshot. A Task that stores a ROLE token without snapshotting ids will change assignee whenever a POC changes. Decide deliberately and mirror `email_recipients.sources` if you want both: resolved ids plus the token that produced them.
9. **Record-level POC associations are LAZY** (`Invoice.salesPoc`, `Payment.collectionPoc`). Resolve inside a transaction. `/api/emails/**` has no OEMIV, and background threads have no session at all.
10. **Scheduler pool** size is 3 and shared (`application.yml:25-30`). An additional automation scheduler, or a daily/weekly rule runner, needs a bigger pool. Otherwise a 40 s mail-service call in the email sweeper can delay it.
11. **B1 Regions.** Four places assume one global scope today:
    - `EmailDirectory.searchPeople` lists all active internal users (`:23-34`).
    - `PocService.activeHoldersByType(customerId)` has no region dimension, and `customer_pocs` has no region column.
    - `EmailTargets.bulkIds` relies on `ScopeResolver` predicates, which have no region concept (`…/poc/ScopeResolver.java:51-97`).
    - `EmailTargets.VIEW_PRIVILEGE` uses global authorities through `currentUser.has`.

    All four are where "rights per region" and a "POC book per region" must be threaded in.
12. **B3 As-of.** Emails already snapshot `entity_label`, sender and resolved recipients, so they are point-in-time by construction. **POC seats have no history**: `PocService.remove` hard-deletes the row (`:183`), and `customer_pocs` has only `created_at`. Evaluating "who held Collection POC on date D" for an as-of rule must reconstruct seats from the audit rows `POC_ASSIGNED`, `POC_REMOVED` and `POC_PRIMARY_CHANGED` (each with `PocAuditSnapshot(pocType, userId, username, primary)`, `PocService.java:30-32, 277`), or from a new effective-dated seat table.
13. **Customer-deleted cascade.** `EmailCascade.onCustomerDeleted` marks emails `entity_deleted=true` (`…/email/EmailCascade.java:14-20`, called from `CustomerService`). A Task (A6) on a customer, invoice or payment needs an equivalent cascade hook.

---

## 10. Concrete extension points (ranked)

1. **Add an `EmailAutomation` (or `EmailActionService`) in `com.geneinvoice.email` that does not need a caller:**
   - load a Target without the caller's scope (a new public method reusing private `loadUnscoped` and `describe`);
   - build a `Plan` from stored `EmailToken`s by extracting the type and offered-role checks from `checkFrom`/`checkTo`, minus the restricted-caller branch;
   - call `addressing.resolve(plan, target)` as is;
   - validate content with the `Content.check` rules after placeholder expansion;
   - persist through a shared `save(...)` with `sentByUserId` set to the rule owner or null;
   - return the email id. The caller then does `dispatcher.dispatchAll(List.of(id))` outside any transaction, or leaves the row to the sweeper.
2. **Add an assignee resolver for Task/Promise/Dispute (A3/A6).** Input: `EmailToken`-shaped `{USER | ROLE+level}` plus a Target. Output: all holders (`target.holders(ref)`) or the primary only (`target.sender(ref)`). Reject `CUSTOMER` and non-internal users as `activeInternalUser` does. Reuse `EmailTargets.rolesOffered(type)` and `defaultLevel` for validation, and `RoleRef.token()` for the stored "source".
3. **Add a placeholder resolver (A4).** New code; see §7. Resolve role placeholders with `Target.sender(RoleRef)` and format with the `EmailText.money/date/humanize/nameOf` helpers (move them to `common` or make them public).
4. **Build the outbox and consumer for A5** by copying the `EmailRepository.claim/findDue/findStaleSending` and `EmailDispatcher.sweep` shapes (§4.9). Include an idempotency key per action, like `CopyRef`.
5. **Frontend.** Add a new `SendEmailMode.rule` (or a separate editor) built on the `openBulkEmailParams` output shape. Extract `_SearchDialog` and the To-chips row into a shared `RecipientPicker` for Task assignees. Add a non-`EMAIL_SEND` context and people endpoint for task assignment.