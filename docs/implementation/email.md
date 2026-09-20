# Email for every record — design and contracts

**Date:** 2026-09-17
**Status:** Implementation contract, updated to the code as built and as changed by the review fixes
(R1–R17), the end-to-end test fixes (A1–A3, U3–U9) and the change of E3 to every role holder (2026-09-19). Backend and frontend are built against this document, so the API shapes, class names and
file paths below are binding. Change them only by changing this file.

**2026-09-20: roles carry a level** ([email-role-levels.md](email-role-levels.md)): the To and From
lists offer two groups — the customer's POC book and the record's own POC fields — and a role token
names which of them it means. That document replaces **E3** and the role column of §4, and changes
the tokens of §5, the sources and the compose context of §6. Where the two differ, it wins.

**2026-09-20: Gmail moved to a separate mail service** ([mail-service.md](mail-service.md)): every internal
user connects their own Gmail, sends go through a queue, one copy per To recipient, and each copy's
sent / delivered / read status comes back by a signed webhook. Where the two documents differ,
mail-service.md wins. The parts it replaces are marked **Replaced** below and point there: E6, E7, E9, E10,
the E13 Bcc rule, the §6 changes, §7 Delivery with the Gmail module, and §9 configuration.

## 1. Requirements (as given)

- Users can send and receive emails for any entity: Customer, Invoice, Product, Payment, Promise,
  Dispute and all other entities (this app: also **User** and **Role**).
- Send email from: the **details page**, the **list page**, a **table quick action** (single row) and a
  **table bulk action**. A bulk action sends **a separate email for each row**.
- The create page of every entity has a **"Notify through email"** checkbox. Promise and Dispute also
  have it on their **edit** page.
- Email form: **From** = an internal user (by name) or a role. **To** = internal users, roles and
  customer emails (all emails of the customer). **Subject** required. **Body** optional.
- A role means the person who has that role on that entity. It is looked up **when the email is sent**,
  and that person's email address is shown. People who get the role later do not get old emails.
  A person added more than once gets the email once.
  Changed by the user on 2026-09-19: a role in To goes to **everyone** who holds it, not just the primary (E3).
  Changed by the user on 2026-09-20: every role is offered at **both levels** — the customer's POC book and
  the record's own POC field — and either or both can be picked (email-role-levels.md).
- Emails are sent and received with the **Gmail API**. Every email is also saved in the system.
  Changed on 2026-09-20: through a separate mail service, from each sender's own Gmail (mail-service.md §1).
- Every entity's details page has an **Email tab**: all emails for that entity, newest first, full details.
- **Inbox** in the left menu, below Dashboard. Users see the emails where they are in To. Options: read,
  mark as read, mark all as read, pagination.

## 2. Decisions

| # | Decision |
|---|---|
| E1 | Entity types: `CUSTOMER`, `INVOICE`, `PRODUCT`, `PAYMENT`, `PROMISE`, `DISPUTE`, `USER`, `ROLE`. Notifications, audit rows and emails themselves are not email targets. |
| E2 | "Role" = the point-of-contact roles, relative to the record (§4). ADMIN/CASHIER/VIEWER are not "on" a record and are not offered. |
| E3 | **Replaced** by [email-role-levels.md](email-role-levels.md) L1–L7 (2026-09-20): a role token carries a **level**, and the pair (role, level) is what is picked, checked and stored. **Customer level** reaches **every active holder** of that seat on the record's customer (`customer_pocs`, `PocService.activeHolders`: the primary first, then in the order they were seated), for the two roles the customer's POC book holds — `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC` — on every kind that has a customer. The Sales POC is seated per invoice, not per customer, so it is offered at record level only (L2, CP-01). **Record level** is the one person the record's own POC field names: an invoice's `salesPoc`, a payment's or promise's `collectionPoc`, a dispute's target's (§4). Both groups are offered wherever they exist, whether or not anyone holds them. In **To** a role reaches everyone at its level, each a recipient with source `ROLE:CUSTOMER:<role>` or `ROLE:RECORD:<role>`, merged with anyone added another way, keeping every way they were added (E5, L6). In **From** it resolves to **one** person, because an email has one sender: the record's own, or the first customer-level holder — whom new records also default to (`PocService.defaultAssignee`). Inactive people never count. With nobody active the (role, level) is unresolved. (Until 2026-09-19 a role in To reached only that first person; until 2026-09-20 a role meant the record's field where it had one and the customer's seats otherwise. A request token without a level now takes the kind's default level: `CUSTOMER` where the kind offers that role there, else `RECORD`. So `{"role":"COLLECTION_POC"}` on a payment reaches the customer's Collection POC seats, and a caller that means the payment's own must send `"level":"RECORD"`; `{"role":"SALES_POC"}` on an invoice means the invoice's own Sales POC, the only level that role is offered at (L2). A **stored** source written without a level is left alone and shown without one, "Sales POC", as it read then, so no row is told it meant a level it never carried, L7.) |
| E4 | Recipients are resolved and **stored** at send time (user id, name, address, and how they were added). The inbox reads the stored rows, so a later role holder never sees an old email. |
| E5 | De-duplication: two candidates are the same person when they share a user id **or** an email address (case-insensitive). The merged recipient keeps every way it was added (e.g. "added directly" and "Collection POC"). |
| E6 | **Replaced** by mail-service.md M2, M4: there is no shared mailbox. Each internal user connects their own Gmail (client ID, client secret, refresh token) and the sender's Gmail sends; `delivered_from` is that Gmail address. |
| E7 | **Replaced** by mail-service.md M10: only **replies to app emails** are received — messages in a Gmail thread the app started from the sender's mailbox, linked through the copy they answer, the thread, or `In-Reply-To`/`References`. Mail from a customer's address that answers no app email is no longer saved. |
| E8 | A received reply counts as addressed **to the owner of the mailbox it arrived in** — the person who sent the email it replies to — so it lands in their Inbox, however it was addressed (mail-service.md §5.5). |
| E9 | **Replaced** by mail-service.md M7, M11, §5.4: every outbound email is saved first (`QUEUED`), then handed to the mail service, which queues one copy per To recipient and sends it; the email's status is the roll-up of its copies (M12). A single send hands off inside the request; a bulk send hands off in the background. The backend's sweeper repeats a hand-off that failed (max 3 attempts); the service never sends a copy twice. |
| E10 | **Replaced** by mail-service.md M13: without the mail service (`app.mail.transport: none`, the default) emails are still saved, appear in the Email tab and in recipients' Inboxes, and every copy is `NOT_SENT` "Email delivery is not configured (mail service)". The compose preview warns before sending. |
| E11 | The in-app Inbox is the system copy: it shows every email where the user is a To recipient, whatever its delivery status (the status is shown). |
| E12 | "Notify through email": after a successful save the compose dialog opens for the new/edited record, pre-filled with a suggested subject, body and recipients. Nothing is sent unless the user presses Send. |
| E13 | Customer logins (a `User` with `customerId`) take part, because only they can open disputes: they may send **From themselves only**, To **roles and their own customer's emails only** (never internal users by name), and may **retry only emails they sent**. They see only emails their customer took part in (sent by one of their logins, or addressed to their customer's emails/logins), and staff appear as the role label (or "Gene Invoice team") with no name or address (keeps AC-A8). Staff shown alike are **one entry** per list (To, Cc), with every way any of them was added, so a role that reaches several holders (E3) shows once and a customer cannot count the staff behind it (the compose context tells them only that a role is filled, §6). **Replaced:** the Bcc rule — each To recipient now gets a copy of their own whose To header is only them (mail-service.md M6), so no header shows staff to a customer. The From display name stays the sender's name, so a role holder's name is in From when From is a role; the From address is the sender's own Gmail. A customer login's email is saved but not sent (customer logins do not connect Gmail). A customer viewer is not shown `deliveredFrom` of staff email, a provider's failure text, a count of copies, how staff's copies fared, or staff's Gmail connections (§6, mail-service.md §5.7). |
| E14 | Privileges: `EMAIL_VIEW` (Email tab, Inbox, read) and `EMAIL_SEND` (compose, notify, bulk). Seeded: ADMIN all; CASHIER, CUSTOMER and the three POC roles both; VIEWER `EMAIL_VIEW`. The POC roles are created once and then left alone, so on an existing database the seeder grants a POC role a privilege from its default set **only in the boot that first creates that privilege row** — later admin edits survive. |
| E15 | Records without a details page get one: Product, Promise (replaces the redirect to the customer's Promises tab), User, Role. List rows open them. |
| E16 | Subject: required, trimmed, 1–500 characters, line breaks replaced by spaces. Body: optional, plain text, up to 20,000 characters (received bodies are cut to fit with "…"). No attachments, no CC in the form (received CC is stored and shown). Text Postgres cannot store is cleaned before any check or save, sent or received: U+0000 is removed and a lone surrogate becomes U+FFFD. Every cut of email text to its limit (subject 500, body 20,000, names and entity label 200, addresses 320, header ids 300, `error` and `unresolved` 1000, `sources` 300, the Inbox `snippet` 160) is made at a code-point boundary (`EmailText.start`): a character outside the BMP (an emoji) that would straddle the cut is dropped whole, so the text before "…" stays valid UTF-8 and can be one unit shorter than the limit. |

## 3. Data model (backend, package `com.geneinvoice.email`)

Polymorphic `entityType` + `entityId`, no foreign keys to users (like `Dispute.openedByUserId`), so deleting
or deactivating people or records never breaks the email history. `entityLabel` is a snapshot.

`emails` — `Email`

| Column | Type | Notes |
|---|---|---|
| id | bigint identity | |
| entity_type | varchar(20) | `EmailEntityType` |
| entity_id | bigint | |
| entity_label | varchar(200) | e.g. `Invoice INV-0042` (snapshot) |
| direction | varchar(10) | `OUTBOUND` / `INBOUND` |
| status | varchar(12) | `QUEUED`, `SENDING`, `SENT`, `FAILED`, `NOT_SENT`, `RECEIVED`, `PARTIAL` (mail-service.md M12). `EmailSchemaUpgrade` widens the check constraint an older database has on it, which `ddl-auto: update` leaves alone. |
| subject | varchar(500) | |
| body | varchar(20000) | plain text, never null (empty string) |
| from_user_id | bigint null | the sender person (internal or customer login) |
| from_role | varchar(30) null | `EmailRole` when From was a role |
| from_role_level | varchar(10) null | `RoleLevel` of that role; null on rows written before levels, which keep no level and are named without one (L7) |
| from_name | varchar(200) | |
| from_address | varchar(320) null | the person's own address (outbound) / header address (inbound) |
| from_customer_id | bigint null | set when the sender belongs to a customer |
| from_internal | boolean | |
| delivered_from | varchar(320) null | the sender's Gmail address the copies went out from (a copy's `fromAddress`) |
| sent_by_user_id | bigint null | who pressed Send (null for inbound) |
| unresolved | varchar(1000) null | comma-separated tokens that resolved to nobody, e.g. `ROLE:CUSTOMER:COLLECTION_POC,CUSTOMER` |
| error | varchar(1000) null | delivery failure / not-sent reason |
| attempts | int | hand-offs to the mail service |
| next_attempt_at | timestamp null | |
| handed_off_at | timestamp null | when the mail service accepted the copies; the dispatcher and its sweeper only touch rows where it is null (mail-service.md §5.3) |
| delivery_uncertain | boolean not null default false | older rows only (before the mail service, which now tracks this per copy); new email leaves it false |
| provider_message_id | varchar(100) null, **unique** | Gmail message id: received mail, and email sent before the mail service (copies carry their own) |
| provider_thread_id | varchar(100) null | Gmail thread id, indexed; as above |
| rfc_message_id | varchar(300) null | `Message-ID` header, indexed; as above |
| in_reply_to | varchar(300) null | |
| batch_id | varchar(40) null | one id per bulk request |
| occurred_at | timestamp | outbound: when saved; inbound: when received. **Sort key: newest first = `occurred_at desc, id desc`.** |
| sent_at | timestamp null | |
| created_at, updated_at | timestamp | the dispatcher's bulk updates (claim, requeue, stale marking) stamp `updated_at` themselves |

Indexes: `(entity_type, entity_id, occurred_at)`, `provider_thread_id`, `rfc_message_id`, `(status, next_attempt_at)`.

`email_recipients` — `EmailRecipient`

| Column | Type | Notes |
|---|---|---|
| id | bigint identity | the Inbox row id |
| email_id | FK → emails | `@ManyToOne(fetch = LAZY, optional = false)` |
| field | varchar(4) | `TO` / `CC` (one row per unique person per email; TO wins) |
| user_id | bigint null | set for internal users and customer logins → Inbox |
| customer_id | bigint null | set when the recipient belongs to a customer |
| name | varchar(200) | |
| address | varchar(320) null | null for a person without an email address (in-app copy only) |
| internal | boolean | |
| sources | varchar(300) | comma-separated, in order added: `USER`, `ROLE:<RoleLevel>:<EmailRole>`, `CUSTOMER`, `MAILBOX`, `HEADER`. A role written before levels is `ROLE:<EmailRole>`, kept as it is and read back with no level (L7); someone reached at both levels keeps both (L6) |
| is_read | boolean default false | read in the app's Inbox |
| read_at | timestamp null | |
| delivery_status, delivery_error, delivery_seq, sent_at, delivered_at, mail_read_at, bounced_at, delivered_confirmed, provider_message_id, provider_thread_id, rfc_message_id | | the recipient's own copy at the mail service: mail-service.md §5.3 |

Indexes: `(user_id, field, is_read)`, `email_id`, `customer_id`, `provider_thread_id`, `rfc_message_id`.

`mail_sync_state` is no longer used (the mail service reads each mailbox); the table is left in the
database. `gmail_connections` (package `email.connection`) is the app's copy of each user's Gmail
connection: mail-service.md §5.3.

Enums: `EmailEntityType` (with `parse(String)` → 400 listing the values, and `noun()`/`title()` — "invoice",
"Invoice"), `EmailRole` (`SALES_POC` "Sales POC", `CUSTOMER_SUCCESS_POC` "Customer Success POC",
`COLLECTION_POC` "Collection POC"; labels from `PocType`; `parse` → 400), `RoleLevel` (`CUSTOMER`, `RECORD`;
`parse` → 400 `"level must be CUSTOMER or RECORD"`), `EmailDirection`, `EmailStatus`, `RecipientField`,
`RecipientDeliveryStatus`.

`RoleRef(EmailRole role, RoleLevel level)` is the pair itself: `token()` (`ROLE:CUSTOMER:SALES_POC`),
`parseToken(String)` (a level-less token keeps a null level, L7), and, given the kind of record,
`label(type)` ("Sales POC (customer)", "Sales POC (this invoice)"), `levelLabel(type)` ("Customer",
"Invoice") and `groupLabel(type)` ("Customer level", "Invoice level").

### Backend classes

| Class | Role |
|---|---|
| `EmailController`, `InboxController` | REST (§6). |
| `EmailService` | Context (`context(entityType, entityId, event, utcOffsetMinutes)`), people search, preview, send, bulk, retry, list, get, delivery status, manual sync. Subject/body checks (`Content.check`, errors via `GlobalExceptionHandler.InvalidFieldsException` → `ApiError.validation`). |
| `EmailTargets` | §4 per type: view privilege, scoped/unscoped load (`@Transactional(readOnly)`, fully materialised `Target` with every holder of every (role, level) — `holders(RoleRef)` for To, `sender(RoleRef)` for From, E3 — and customer emails), labels, links, bulk id resolution, suggestions (`suggest(Target, Event, ZoneId)`). Pairs offered: `rolesOffered(EmailEntityType)` for a kind, `rolesOffered(Target)` for one record (none on a USER that is not a customer login), customer level first; `defaultLevel(type, role)` is the level a token without one means (L7). |
| `EmailAddressing` | §5: `plan` checks tokens once (400/403) — `plan(Target, from, to)` checks roles against the record (single send, preview), `plan(EmailEntityType, from, to)` against the kind (bulk); `resolve` applies a plan to one record. `RecipientSet` merges people (E5). |
| `EmailViews` | `EmailDto`/`InboxItemDto` and the preview's recipients for the caller: masking (E13), with masked people shown alike merged into one entry (`merged`); each To recipient's `delivery`; `canRetry`, `canOpenRecord`, `deliveredFrom`/`error` for customer viewers. |
| `EmailDispatcher`, `EmailSweepScheduler` | The hand-off to the mail service (mail-service.md §5.4). |
| `EmailDeliveryRollup`, `CopyRef` | An email's status from its copies (M12), a copy's report applied by `seq`, the retry rule; the copy key `gi-{emailId}-{recipientId}`. |
| `EmailSchemaUpgrade` | At startup, widens an older database's check constraint on `emails.status` to take `PARTIAL`. |
| `EmailInboundService` | `IncomingMailHandler`: saves received replies (§7). `EmailDirectory` finds users and customers by search text or address. |
| `InboxService` | Unread count, read/unread, mark all read. |
| `EmailText` | `fit` and `start(text, length)` (code-point-safe cuts, E16), `storable` (E16), `oneLine`, names, money, dates (`date(Instant, ZoneId)`, `date(LocalDate)`). |
| `email.transport.*` | The contract of mail-service.md §5.2: `MailTransport`, `Submission`, `CopyRequest`, `CopyState`, `MailSendException`, `MailConnections`, `ConnectionState`, `ConnectionStatus`, `MailConnectException`, `SyncResult`, `MailAddress`, `IncomingMail`, `IncomingMailHandler`, `InboundHint`, `NoopMailTransport`. |
| `email.mailservice.*` | `MailServiceProperties`, `MailServiceClient`, `MailServiceDtos`, `MailServiceEventsController`, `MailServiceEventHandler`, `WebhookSignature` (mail-service.md §5.2, §5.5). |
| `email.connection.*` | `GmailConnection`, `GmailConnectionRepository`, `GmailStatus`, `GmailConnectionService`, `GmailConnectionController`, `GmailConnectionDtos` (mail-service.md §5.3, §5.6). |
| `email.gmail.*` | **Removed**: moved to the mail service (`mail-service/`, package `com.geneinvoice.mail.gmail`). |
| `RoleLevel`, `RoleRef` | The level on a role and the pair itself (email-role-levels.md L1; above). |
| Elsewhere | `config/OpenEntityManagerInViewConfig` (§9), `PocService.activeHolders` (E3), `TableSchemas.INBOX`, `Privileges.EMAIL_VIEW/EMAIL_SEND`, `FieldLimits.EMAIL_SUBJECT/EMAIL_BODY`, `DataSeeder` (E14). `ProductController.ProductDto` and `UserController.UserDto` gained `createdAt` for the new details pages. |

## 4. Records, roles and customer emails

| Type | View privilege | Load (applies customer restriction and POC book) | Customer login may use | Label | Link | Customer | Roles offered → resolution (email-role-levels.md L2–L4) |
|---|---|---|---|---|---|---|---|
| CUSTOMER | CUSTOMER_VIEW | `CustomerService.get` | yes | `Customer {name}` | `/customers/{id}` | itself | Customer level: both customer-book roles → the customer's seat holders. Record level: none |
| INVOICE | INVOICE_VIEW | `InvoiceService.get` | yes | `Invoice {invoiceNumber}` | `/invoices/{id}` | invoice.customer | Customer level: both customer-book roles. Record level: SALES_POC → `invoice.salesPoc` |
| PAYMENT | PAYMENT_VIEW | `PaymentService.get` | yes | `Payment #{id}` | `/payments/{id}` | payment.customer | Customer level: both customer-book roles. Record level: COLLECTION_POC → `payment.collectionPoc` |
| PROMISE | PROMISE_VIEW | `PaymentPromiseService.get` | yes | `Promise #{id}` | `/promises/{id}` | promise.customer | Customer level: both customer-book roles. Record level: COLLECTION_POC → `promise.collectionPoc` |
| DISPUTE | DISPUTE_VIEW | `DisputeService.get` | yes | `Dispute #{id}` | `/disputes/{id}` | dispute.customerId | Customer level: both customer-book roles. Record level: SALES_POC → an invoice target's `salesPoc`, COLLECTION_POC → a payment target's `collectionPoc`; both are offered on every dispute, so the one its target cannot have is unresolved (L4) |
| PRODUCT | PRODUCT_VIEW | repository, 404 | no | `Product {name}` | `/products/{id}` | — | none at either level |
| USER | USER_VIEW | repository, 404 | no | `User {username}` | `/users/{id}` | user.customerId (customer logins only) | Customer level: both customer-book roles → that customer's seat holders. Record level: none. Offered on the kind (list, picker, bulk compose); on one record only when it is a customer login — an internal user's record offers none |
| ROLE | ROLE_VIEW | repository, 404 | no | `Role {name}` | `/roles/{id}` | — | none at either level |

"Both customer-book roles" is CUSTOMER_SUCCESS_POC then COLLECTION_POC (L2: a customer holds no Sales POC seat); the compose form lists the
customer group first, then the record's (L4). "Seat holders" are everyone active in that seat type on the
customer, the primary first, then in the order they were seated (`PocService.activeHolders(customerId, type)`,
whose first entry is `PocService.defaultAssignee`). A record's own POC field names one person. In To a role
reaches every holder at its level; as From only the first (E3, L5). What a **kind** offers at record level is
the union of what its records can store, so a bulk or picker compose offers a dispute both. Only active people
count: a (role, level) with no active holder is **unresolved** (recorded in `unresolved`, shown as "Collection
POC (customer) — nobody assigned").

**Customer emails** (token `CUSTOMER`) = the customer's own `email` (name = customer name) plus the email of
each **active** customer login of that customer (name = full name or username, `userId` set). Logins without
an email address are not included. A record without a customer offers no customer emails.

Bulk id resolution mirrors each list controller's `resolveIds`: `TableSchemas.<X>.visibleTo(isCustomer)` +
`TableQuery.parseUnpaged(schema, sort, filters)` + the entity's `ScopeResolver.for…()` predicates
(customers, invoices, payments, promises, disputes; none for products, users, roles), capped at
`TableQueryExecutor.BULK_ID_LIMIT`.

## 5. Addressing rules (server-enforced)

Request tokens:

```json
{"type": "USER", "userId": 7}
{"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER"}
{"type": "ROLE", "role": "COLLECTION_POC"}
{"type": "CUSTOMER"}
```

- A `ROLE` token's `level` is `CUSTOMER` or `RECORD` (400 `"level must be CUSTOMER or RECORD"`). Without one it
  means `CUSTOMER` where the **kind** offers that role at customer level, else `RECORD` (L7) — which for an
  invoice's `SALES_POC` and a payment's, promise's or dispute's `COLLECTION_POC` is not what the same token meant
  before levels existed, so a caller that wants the record's own POC sends `"level": "RECORD"`.
- **From** is one token, `USER` or `ROLE`; omitted = the caller. `USER` must be an active internal user
  (400 otherwise). `ROLE` must be offered, as for To, and resolves per record to one person: the record's own at
  record level, the first holder at customer level (L5); nobody holding it → single send 400
  `"Nobody holds Collection POC (customer) on Invoice INV-0042, so it cannot be the sender"`, bulk → that row skipped with that reason.
- **To** is a non-empty list (400 `"Add at least one recipient"`, field error `to`). `USER` must be an active internal user
  (400 naming them). `ROLE` must be offered as that pair (400 `"Collection POC (customer) is not a role on users"`,
  `"Sales POC (this payment) is not a role on payments"`) and reaches every active holder at its level (L2, L3), each a
  recipient with source `ROLE:CUSTOMER:<role>` or `ROLE:RECORD:<role>`; a bulk send resolves them per row. `CUSTOMER`
  needs a type that can have a customer (400).
- Which pairs are offered (§4): `POST /api/emails` and `POST /api/emails/preview` check `ROLE` tokens (From and To)
  against the record, so a role on a USER record that is not a customer login is that 400. `POST /api/emails/bulk`
  checks them against the kind, once up front; an internal user's row then resolves the role to nobody and is skipped
  with the usual reason (`"No recipients: Collection POC (customer) is not assigned"`, or
  `"Nobody holds Customer Success POC (customer) on User sam, so it cannot be the sender"`).
- After resolution there must be at least one recipient (single send 400 `"No recipients: Collection POC (customer) is not assigned and the customer has no email address"`; bulk → row skipped with that reason).
- Customer login callers: From must be omitted or themselves (403 otherwise); To may contain only `ROLE` and `CUSTOMER` (403 for `USER`).
- De-duplicate as E5. Recipients without an address are kept (in-app copy). If no recipient has an address, the email is saved with status `NOT_SENT`, error `"No recipient has an email address"`.
- Subject and body: U+0000 removed and lone surrogates replaced with U+FFFD first (E16), then the one-line conversion, trimming and length checks; the same for single and bulk sends.
- Validation errors on the body fields use the existing `ApiError.validation` shape (`fieldErrors.subject`, `fieldErrors.to`, `fieldErrors.body`).

## 6. REST API

All under `/api`, JSON, errors in the existing `ApiError` shape. "Can see the record" = view privilege +
the loader in §4 (404 / 403 as that loader throws); customer logins additionally only customer-readable types.

### Shared shapes

```jsonc
// Participant — one sender or recipient as the caller may see it. Masked people with the same name
// are one Participant in a list (To, Cc, the preview's to), in the place of the first, with the
// sources of all of them (E13). It also has "delivery": each To recipient's copy — mail-service.md §5.7.
{
  "name": "Bob Smith",            // masked: role label or "Gene Invoice team"
  "address": "bob@company.com",   // null when none or masked
  "userId": 12,                   // null when none or masked
  "internal": true,
  "masked": false,
  "sources": [                     // how this person was added, in order; both levels when both reached them (L6)
    {"type": "USER"},
    {"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER", "label": "Collection POC (customer)"},
    {"type": "ROLE", "role": "SALES_POC", "level": "RECORD", "label": "Sales POC (this invoice)"},
    {"type": "CUSTOMER"},          // one of the customer's emails
    {"type": "MAILBOX"},           // inbound: the mailbox the reply arrived in, mapped to its owner
    {"type": "HEADER"}             // inbound: an address as it appeared in the header
  ]
}

// EmailDto
{
  "id": 91, "entityType": "INVOICE", "entityId": 42, "entityLabel": "Invoice INV-0042", "entityLink": "/invoices/42",
  "direction": "OUTBOUND", "status": "SENT",       // or PARTIAL (mail-service.md M12)
  "subject": "…", "body": "…",
  "from": Participant, "fromRole": "COLLECTION_POC" /* or null */,
  "fromRoleLevel": "CUSTOMER" /* or null */, "fromRoleLabel": "Collection POC (customer)" /* or null */,
  "to": [Participant], "cc": [Participant],
  "unresolved": [{"token": "ROLE:RECORD:SALES_POC", "label": "Sales POC (this invoice)",
                  "reason": "Nobody holds Sales POC (this invoice) on Invoice INV-0042"}],
  "sentBy": {"userId": 3, "name": "Jane Doe"},   // null for inbound; {"userId": null, "name": "Gene Invoice team"} for staff, to a customer viewer
  "deliveredFrom": "jane@gmail.com",             // customer viewer: null unless their own customer sent it
  "error": null,                                  // customer viewer: "Could not be delivered", except the app's own reasons
                                                  // ("Email delivery is not configured (mail service)", "No recipient has an
                                                  // email address", "Email from a customer login is not sent through Gmail",
                                                  // and a not-connected reason when the sender is their own)
  "attempts": 1,                                  // hand-offs to the mail service
  "occurredAt": "2026-09-17T10:15:00Z", "sentAt": "2026-09-17T10:15:01Z",   // sentAt: the earliest copy's
  "canRetry": false,               // mail-service.md §5.4 Retry (a failed or unsent copy), caller has EMAIL_SEND and
                                   // can see the record, and for a customer login also sentByUserId == caller
  "canOpenRecord": true,           // the caller can see the record (a sender or recipient may read an email whose record they cannot)
  "readByMe": true                 // null when the caller is not a To recipient
}

// SendEmailRequest
{
  "entityType": "INVOICE", "entityId": 42,
  "from": {"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER"},   // optional
  "to": [{"type": "USER", "userId": 7}, {"type": "ROLE", "role": "SALES_POC", "level": "RECORD"},
         {"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER"}, {"type": "CUSTOMER"}],
  "subject": "Invoice INV-0042", "body": "…"            // body optional
}
```

### Endpoints

| Method | Path | Privilege | Purpose |
|---|---|---|---|
| GET | `/api/emails/context?entityType=&entityId=&event=&utcOffsetMinutes=` | EMAIL_SEND | Everything the compose form needs (below). `entityId` optional (list/bulk compose); `event` optional `CREATED`/`UPDATED`; `utcOffsetMinutes` optional integer, the reader's offset in minutes east of UTC, -840..840 (outside: 400 `"utcOffsetMinutes must be between -840 and 840"`; not an integer: 400 `"Invalid value for 'utcOffsetMinutes': …"`). The client sends it on every request (`DateTime.now().timeZoneOffset.inMinutes`). |
| GET | `/api/emails/people?q=` | EMAIL_SEND, internal caller (403 for customers) | Active internal users whose username, full name or email contains `q` (case-insensitive, and literally: `%`, `_` and `\` are escaped, `LIKE … ESCAPE '\'`, as the table framework's `contains` does), ordered by name, max 20: `[{"userId","name","username","email"}]` (email may be null). |
| POST | `/api/emails/preview` | EMAIL_SEND | Body `SendEmailRequest`. No side effects. `{"from": Participant\|null, "to": [Participant], "unresolved": [...], "problems": ["…"], "warnings": ["…"]}` — `problems` lists what would make Send fail (no recipients, sender role unresolved); `warnings` why it would be saved but not sent (mail-service.md §5.7). Malformed tokens are still 400. |
| POST | `/api/emails` | EMAIL_SEND | Send one email about one record. Returns `EmailDto` right after the hand-off: usually `QUEUED` (at the service), or `NOT_SENT`, `FAILED`, or `QUEUED` here when a transient failure scheduled another hand-off. |
| POST | `/api/emails/bulk` | EMAIL_SEND | `BulkDtos.BulkRequest` with `action: "SEND_EMAIL"`, `ids` or `selectAllMatchingFilter` + `sort` + `filters` (the list's), and `params: {"entityType", "from", "to", "subject", "body"}`. Params are validated once up front (400). One email per reachable row, each with its own role and customer-email resolution. Returns `BulkDtos.BulkResult` (`succeeded` = record ids that got an email saved; unresolvable rows `skipped` with the reason; unreachable ids `skipped` with `NOT_REACHABLE`). Dispatch continues in the background. |
| GET | `/api/emails?entityType=&entityId=&page=0&size=20` | EMAIL_VIEW + can see the record | The record's emails, newest first, as the existing `PageResponse` envelope of `EmailDto` (`size` 10/20/50, `sort` `"occurredAt,desc"`; `canOpenRecord` always true). Customer viewers: only their customer's emails (E13). |
| GET | `/api/emails/{id}` | EMAIL_VIEW | One email. Allowed when the caller is its sender, who pressed Send, or a recipient, or can see its record; otherwise the record's own 403/404. A customer whose customer took no part: 404. `canOpenRecord` says whether the record could be seen. |
| POST | `/api/emails/{id}/retry` | EMAIL_SEND + can see the record | Sends the failed and unsent copies again (mail-service.md §5.4 Retry); otherwise 400 `"Only failed or unsent email can be retried"`. Returns `EmailDto`. Customer login: 404 when their customer took no part; 403 when it did but they did not send it (the body carries the app's usual "You do not have permission for this action": `GlobalExceptionHandler` words every 403 the same way). |
| GET | `/api/emails/delivery` | EMAIL_VIEW | `{"configured": bool, "gmail": {"status","gmailAddress","reason","lastSyncedAt","lastSyncError"}\|null}` — the caller's own Gmail connection from the app's copy; `gmail` is null for customer logins (mail-service.md §5.7). |
| POST | `/api/emails/sync` | EMAIL_VIEW, internal caller (403 for customers) | Reads the caller's own Gmail for replies now, through the mail service. `{"enabled", "fetched", "imported", "error"}`; not configured → `enabled: false`, `"Email delivery is not configured (mail service)"`; not connected → `enabled: false`, `"Gmail is not connected"`; a run under way → `"A sync is already running"`. |
| GET, PUT | `/api/me/gmail` | EMAIL_SEND, internal caller | The caller's own Gmail connection (mail-service.md §5.6). |
| DELETE | `/api/me/gmail` | internal caller | Disconnects the caller's own Gmail, also for one who lost EMAIL_SEND (mail-service.md §5.6). |
| GET | `/api/users/{id}/gmail` | USER_VIEW | Someone's connection, from the app's copy (mail-service.md §5.6). |
| DELETE | `/api/users/{id}/gmail` | USER_MANAGE | Disconnects someone else's Gmail at the mail service (mail-service.md §5.6). |
| POST | `/api/mail-service/events` | none (HMAC signature) | The mail service's webhook (mail-service.md §5.5); only with transport `mail-service`. |
| GET | `/api/inbox?page&size&sort&filter` | EMAIL_VIEW | The caller's inbox (table framework, schema `inbox`), rows = the caller's `TO` recipient rows. `PageResponse<InboxItemDto>`. |
| GET | `/api/inbox/unread-count` | EMAIL_VIEW | `{"count": n}` |
| POST | `/api/inbox/{id}/read` | EMAIL_VIEW | Mark one of the caller's inbox rows read (404 if not theirs). |
| POST | `/api/inbox/{id}/unread` | EMAIL_VIEW | Mark unread. |
| POST | `/api/inbox/mark-all-read` | EMAIL_VIEW | `{"updated": n}` |
| POST | `/api/inbox/bulk` | EMAIL_VIEW | `MARK_READ` / `MARK_UNREAD` over inbox row ids (same shape and rules as notifications bulk). |

`GET /api/emails/context` response:

```jsonc
{
  "entityType": "INVOICE", "entityId": 42,          // entityId null without a record
  "entityLabel": "Invoice INV-0042", "entityLink": "/invoices/42",
  "delivery": {"configured": false},
  "sender": {
    "restricted": false,                              // true for customer logins: From is always "self", To offers roles + customer emails only
    "self": {"userId": 3, "name": "Jane Doe", "email": "jane@company.com", "gmail": "CONNECTED"}
    // every person object here, and each /api/emails/people result, has "gmail" (mail-service.md §5.7)
  },
  "roles": [                                          // one entry per (role, level) offered (§4): for the type without
                                                      // entityId, for the record with one ([] for a USER that is not a
                                                      // customer login). Customer level first, then record level, each
                                                      // customer level CUSTOMER_SUCCESS_POC then COLLECTION_POC (L2),
                                                      // record level SALES_POC then COLLECTION_POC.
    {"role": "COLLECTION_POC", "label": "Collection POC",
     "level": "CUSTOMER", "levelLabel": "Customer", "groupLabel": "Customer level",
     "resolved": true,                                // null when no entityId; true when at least one active holder
     "people": [                                      // everyone the role reaches in To at that level, primary first;
       {"userId": 12, "name": "Bob Smith", "email": "bob@company.com"},  // [] when unresolved, no entityId, or masked
       {"userId": 15, "name": "Ann Lee", "email": "ann@company.com"}
     ],
     "sender": {"userId": 12, "name": "Bob Smith", "email": "bob@company.com"}},  // who sends when it is the From;
                                                      // null when unresolved, no entityId, or masked
    {"role": "SALES_POC", "label": "Sales POC",
     "level": "RECORD", "levelLabel": "Invoice", "groupLabel": "Invoice level",
     "resolved": true, "people": [{"userId": 15, "name": "Ann Lee", "email": "ann@company.com"}],
     "sender": {"userId": 15, "name": "Ann Lee", "email": "ann@company.com"}}
  ],
  "customerEmails": {
    "available": true,                                // the type (and, with a record, the record) has a customer
    "addresses": [{"name": "Acme Ltd", "address": "ap@acme.com"}]   // empty without a record
  },
  "suggestion": {"subject": "…", "body": "…", "to": [ /* tokens */ ]}   // only when event is given, else null
}
```

Suggestions (`event`, server-built; money like the app, e.g. `₹1,200.00`; a timestamp's date, `yyyy-MM-dd`, is the
day at `utcOffsetMinutes`, or in the server's default time zone without it — the INVOICE body's date (`invoiceDate`)
and the PAYMENT body's date (`paidAt`); the promise's `{date}` is a calendar date and does not move):

| Type | CREATED | UPDATED | Suggested To |
|---|---|---|---|
| CUSTOMER | "Welcome, {name}" | — | `CUSTOMER` |
| INVOICE | "Invoice {number} for {total}" (date, total, balance in body) | — | `CUSTOMER` |
| PAYMENT | "Payment of {amount} received" (date, method) | — | `CUSTOMER` |
| PROMISE | "Payment promise: {amount} by {date}" | "Payment promise updated: {amount} by {date}" (status) | `CUSTOMER`, then `COLLECTION_POC` at **both** levels |
| DISPUTE | "Dispute #{id} raised on {target}" (reason) | "Dispute #{id} {approved/denied}" (admin notes) | CREATED: customer-level `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC`, then the record-level role its target has (`SALES_POC` for an invoice, `COLLECTION_POC` for a payment); UPDATED: `CUSTOMER` |
| PRODUCT | "New product: {name}" | — | none |
| USER | "Your Gene Invoice account" (username) | — | `USER:{id}` for an internal user, `CUSTOMER` for a customer login |
| ROLE | "New role: {name}" | — | none |

Only offer suggested tokens the caller may use (a customer login never gets a `USER` token).

### Inbox

`InboxItemDto`:

```jsonc
{"id": 501, "emailId": 91, "entityType": "INVOICE", "entityId": 42, "entityLabel": "Invoice INV-0042",
 "entityLink": "/invoices/42", "subject": "…", "snippet": "first 160 characters of the body (cut as E16)",
 "from": Participant, "direction": "OUTBOUND", "status": "SENT", "occurredAt": "…", "read": false}
```

Table schema `inbox` (add to `TableSchemas` and its index) over `EmailRecipient`, always constrained to
`userId = caller AND field = TO`: `id` NUMBER; `subject` TEXT (email.subject); `fromName` TEXT (email.fromName,
**pocRestricted** so customer logins can't filter on staff names); `entityType` ENUM (email.entityType);
`entityLabel` TEXT (email.entityLabel); `direction` ENUM; `status` ENUM; `read` BOOLEAN; `occurredAt` DATE
(email.occurredAt). Default sort `occurredAt,desc`.

## 7. Delivery

**Replaced** by mail-service.md (2026-09-20): the transport contract is §5.2, the hand-off, roll-up and
retry are §5.4, the webhook is §5.5, and sending, tracking and reading mailboxes happen in the mail service
(§4). The Gmail module that was here (`email.gmail`: the shared mailbox, OAuth or service-account sign-in,
send with the mailbox recheck, the history poller, MIME parsing, Gmail error classification) moved there.
What stays true in the backend:

- Every outbound email is saved first (`QUEUED`), each To recipient with an address getting a copy
  (`delivery_status` `QUEUED`), and is then handed over: `EmailDispatcher` claims it atomically (only rows not
  yet handed off), settles what needs no service (not configured, nobody with an address, a customer-login
  sender), and submits the copies with `externalId` `gi-{emailId}-{recipientId}`. A transient hand-off failure
  is handed over again after 1 minute, then 5; the third, or a permanent one, fails the waiting copies.
- Calling `dispatch` inside a transaction is an `IllegalStateException`. Every DB write happens in its own short
  transaction; no transaction and no pooled connection is held across a call to the mail service (the email,
  inbox and Gmail-connection endpoints run without open-in-view, §9).
- Bulk hand-off uses a single-thread executor (daemon thread `email-dispatch`; `app.mail.dispatch.async: true`;
  tests set `false`). The ids handed to it are held in memory until each has been handed over, so the sweeper
  leaves them alone; a rejected task (shutting down) releases its ids at once, and the sweeper hands over what a
  restart left queued.
- `EmailSweepScheduler` (`@Scheduled(fixedDelayString = "${app.mail.dispatch.sweep-interval-ms:60000}")`, same
  initial delay) settles hand-offs untouched for 10 min, one email at a time with its row locked: their queued
  copies `FAILED` ("Sending was interrupted; retry to send again") and the email rolled up (`FAILED` only without
  copies; a retried `PARTIAL` email stays `PARTIAL`). It then hands over up to 200 due emails created more than
  30 s ago, and asks the mail service again to remove Gmail connections still marked for removal
  (mail-service.md §5.6).
- After the hand-off, each copy's progress arrives by webhook and the email is rolled up from its copies.

### Inbound handling (core, `EmailInboundService`)

Called by the webhook's `message.received` with an `InboundHint` (the mailbox owner, the mailbox address,
the copy answered); mail-service.md §5.5.

- A message without a provider id is ignored (WARN). Before any lookup or save, every text is made storable (E16):
  subject, body, from/to/cc names and addresses, Message-ID, In-Reply-To, References; provider ids are left as they are.
- Idempotent on `providerMessageId` (a stored `INBOUND` row returns its id; our own sent message — an email's or a
  copy's — returns empty); ignores a message whose Message-ID is one the app sent.
- Link (M10): the email of `repliedToExternalId` → its record; else thread id → newest email in that thread (by the
  emails' and their copies' thread ids); else In-Reply-To, then References newest first → the first email whose
  own or copy's Message-ID matches; else ignored.
- Sender: a user by address (active first) → that user (internal unless a customer login); else the header name, or
  the matching customer's name, or the address, or "Unknown sender" (external, with the customer's id when it matched).
- Recipients (To and Cc): the mailbox address (also `local+tag@domain` variants) → the mailbox owner (E8; source
  `MAILBOX`; the answered email's stored sender fields when that user is gone); a user's address → that user
  (`HEADER`); a customer's address → that customer (`CUSTOMER`); others external as `HEADER`. The owner is also a
  `TO` recipient when the mailbox was not in To (Cc or Bcc), so the reply reaches their Inbox. De-duplicate (E5).
- Save with status `RECEIVED`, subject as one line cut to 500, body cut to 20,000, names to 200, addresses to 320,
  header ids to 300, each with "…" and at a code point (E16).
- A `DataIntegrityViolationException` on save counts as a concurrent import only when a row with that provider id now
  exists (its id is returned if `INBOUND`). Otherwise WARN `"Received mail <providerMessageId> could not be saved:
  <cause>"` and return empty.

### Known limits

- Those of mail-service.md §9: customer "read" is not tracked, customer delivery is an estimate, and replies are found
  by Gmail thread only.
- The app's copy of a connection (`gmail_connections`) learns the last mailbox read and its error when the user's
  connection is looked up (`GET /api/me/gmail`, "sync now") or its status changes; in between it can be behind.

## 8. Frontend (Flutter)

The mail service's changes to the frontend — the Gmail connection screen, per-recipient delivery lines, `PARTIAL`,
preview warnings and the Inbox banner — are in mail-service.md §6; where they differ from this section, §6 wins.
How the compose dialog groups the role chips by level, in To and in From, is
[email-role-levels.md](email-role-levels.md) §4; where it differs from this section, it wins.

### Core files (feature `lib/features/email/`)

| File | Contents |
|---|---|
| `email_entity.dart` | `enum EmailEntityType { customer, invoice, product, payment, promise, dispute, user, role }` with `wire` (`'INVOICE'`…), `noun` (`'invoice'`), `routeBase` (`'/invoices'`), `apiPath` (`'/api/invoices'`), and what the record picker searches. |
| `email_models.dart` | `EmailParticipant`, `EmailMessage` (incl. `canRetry`, `canOpenRecord` — false when the key is missing), `EmailContext` (+ role option with its level, `roleGroups`, `role(key, {level})`, customer emails, delivery, suggestion), `EmailDelivery`, `InboxItem`, `EmailToken` (`user(id)`, `role(key, {level})`, `customer()`, `toJson`), `enum EmailEvent { created, updated }` (`wire` `'CREATED'`/`'UPDATED'`), `emailOutcomeMessage`, `emailProgress` (the Email tab's time line, below). |
| `email_providers.dart` | `emailContextProvider` (sends `utcOffsetMinutes` = `DateTime.now().timeZoneOffset.inMinutes` on every request, §6), `entityEmailsProvider`, `emailDetailProvider`, `inboxUnreadCountProvider`, `emailDeliveryProvider`, `myGmailProvider`, `userGmailProvider(id)` (mail-service.md §6). `inboxUnreadCountProvider` returns an empty stream while nobody is signed in (no request without a token, D-70) and otherwise polls through `pollUnreadCount`. |
| `send_email_dialog.dart` | The compose dialog (single record, record picker, bulk-params modes). |
| `email_tab.dart` | `EmailTab` (paged list, newest first, full details per email, Send and Retry buttons) and `EmailCard({required email, showRecord = false, inbox = false})`, reused by the inbox reader (below). |
| `inbox_screen.dart` | `InboxScreen({required TableQuery query})` on `DataTableScaffold` (entity `inbox`, path `/api/inbox`). |
| `gmail_connection_screen.dart` | `GmailConnectionScreen` at `/me/gmail` (mail-service.md §6). |
| `email_actions.dart` | The public API below — **screens import only this file**. |

`lib/features/notifications/notifications_providers.dart` has `Stream<int> pollUnreadCount(Ref ref, Dio dio, String path)`,
used by the bell (`unreadCountProvider`) and the Inbox badge: fetches `{"count": n}` now and every 30 s
(`Timer.periodic`), skips a failed fetch (the first included; the badge stays loading, shown as 0, until a tick
succeeds), emits only on success, and cancels the timer and closes the stream in `ref.onDispose`, so no request
outlives the provider.

### Public API (`lib/features/email/email_actions.dart`) — binding

```dart
export 'email_entity.dart' show EmailEntityType;
export 'email_models.dart' show EmailEvent;

/// True when the signed-in user holds EMAIL_SEND / EMAIL_VIEW.
final canSendEmailProvider = Provider<bool>(...);
final canViewEmailProvider = Provider<bool>(...);

/// Compose an email about one record. Resolves true when an email was saved (sent or not).
Future<bool> showSendEmailDialog(BuildContext context,
    {required EmailEntityType type, required int entityId, String? entityLabel, EmailEvent? event});

/// Compose from a list page: the dialog first asks which record the email is about.
Future<bool> showSendEmailForPickedRecord(BuildContext context, {required EmailEntityType type});

/// Row quick action: an IconButton (Icons.mail_outline, size 18, tooltip 'Send email'), or an empty
/// SizedBox when the user cannot send.
Widget sendEmailRowAction(BuildContext context, {required EmailEntityType type, required int entityId, String? entityLabel});

/// List page action for DataTableScaffold.actions: OutlinedButton.icon 'Send email'. Returns null when
/// the user cannot send, so callers write `if (a != null) a`.
Widget? sendEmailPageAction(BuildContext context, WidgetRef ref, {required EmailEntityType type});

/// Bulk action for DataTableScaffold.bulkActions ('Send email', posts to /api/emails/bulk with
/// params from the compose dialog in bulk mode; success snackbar "Email queued for N records").
/// Callers add it only when canSendEmailProvider is true.
BulkActionSpec sendEmailBulkAction(EmailEntityType type);

/// Detail page header button (for DetailScaffold.titleTrailing): OutlinedButton.icon 'Send email', or null.
Widget? sendEmailHeaderButton(BuildContext context, WidgetRef ref,
    {required EmailEntityType type, required int entityId, String? entityLabel});

/// The Email tab for DetailScaffold.tabs (slug 'email', label 'Email', Icons.mail_outline). Null when
/// the user lacks EMAIL_VIEW, so callers write `if (tab != null) tab`.
DetailTab? emailDetailTab(WidgetRef ref, {required EmailEntityType type, required int entityId, String? entityLabel});

/// The "Notify through email" checkbox (CheckboxListTile). Renders SizedBox.shrink() without EMAIL_SEND.
class NotifyByEmailCheckbox extends ConsumerWidget {
  const NotifyByEmailCheckbox({super.key, required this.value, required this.onChanged});
  final bool value;
  final ValueChanged<bool> onChanged;
}

/// Call after a successful save, with a context that is still mounted (the screen's, never a closed
/// dialog's). When [notify] is true and the user may send, opens the compose dialog pre-filled for the
/// record and event. Never throws.
Future<void> notifyByEmailAfterSave(BuildContext context,
    {required bool notify, required EmailEntityType type, required int entityId, required EmailEvent event});
```

`DataTableScaffold` gains `BulkActionSpec.endpoint` (`String?`, default `'$path/bulk'`) and
`BulkActionSpec.successMessage` (`String Function(int succeeded)?`, default "N records updated"). Its page
`actions` sit at the end of the filter bar's row at 760 px and wider; below 760 px they get their own line under
the filter bar (padding `12, 0, 12, 8`) and wrap, so two buttons never squeeze the filter chips. The bulk result
dialog is titled `'{BulkActionSpec.label} result'` (e.g. "Send email result"), not the action code.

Row actions are not the table's last column: at 1366 px with wide rows (long names, crore amounts) they were
off-screen until the table was scrolled sideways. They sit in a one-column `DataTable` of their own
(`horizontalMargin` 12, empty heading), pinned to the right of an `Expanded` horizontal scroll view holding the
columns (sized by `LayoutBuilder` to the space beside the rail and the actions), both inside one vertical scroll
view. The rows line up because `DataTable` gives every heading row 56 px and every data row 48 px; a future
`dataRowMinHeight`/`dataRowMaxHeight` in the scaffold must be set on both tables. A selected row tints its actions
row too (hover does not reach it). The action `IconButton`s get an `IconButtonTheme` with `VisualDensity.compact`
(32×32 on desktop; touch platforms still pad the tap target to 48), tooltips unchanged. The always-visible horizontal
scrollbar (depth-1 predicate) and `columnSpacing` 24 (D-19, D-20) are kept; its track also runs under the actions
column. The phone card list is unchanged. On a list with row actions `find.byType(DataTable)` finds two tables, the
main one first; screen readers and keyboard focus meet every cell of the main table first, then the actions row by row.

### Compose dialog behaviour

- Header "Send email" and "About: Invoice INV-0042"; in picker mode a search field for the record first; in bulk
  mode "Selected invoices — a separate email for each" (the table's confirmation states the count).
- A notice when `delivery.configured` is false: "Email delivery is not configured. The email will be saved in the app but not sent."
  The preview's `warnings` (sender not connected, customer login…) show in amber above Send and do not disable it; when
  From is "Me" and my `gmail` is not `CONNECTED`, a line "Connect your Gmail to send email" links to `/me/gmail`
  (mail-service.md §6).
- **From**: "Me (name)" by default; the offered roles, grouped by level (email-role-levels.md §4), each naming
  the one person who would send (the role's `sender`, L5: "Collection POC · Bob Smith <bob@…>" or "… · nobody
  assigned"; in bulk "Collection POC (each record's)"); "Someone else…" searches people. Customer logins: fixed "You".
  The **closed** field stands away from those headings, so a role offered at both levels names its own level there —
  "Collection POC (customer)" / "Collection POC (this payment)", in bulk "Collection POC (each customer's)" /
  "Collection POC (each payment's)" (email-role-levels.md §4); bulk has no preview, so that is the only place the chosen sender is stated.
- **To**: people search (internal callers only) adding chips; a chip per offered (role, level), under the group
  headings "Customer level" and "<Record> level" (email-role-levels.md §4); a "Customer emails" chip
  (with the addresses listed in single mode; disabled when not available). Remove by chip delete. A role chip
  (to add, or added) names everyone the role reaches (the role's `people`, E3): one in full ("Collection POC ·
  Anil Kumar <anil@…>"), two by name ("Collection POC · Anil Kumar, Bala Raman"), three or more as "Collection
  POC · Anil Kumar + 2 more"; from two on, its tooltip lists each name and address, one per line. Nobody:
  "… · nobody assigned". A customer login sees the role label alone (`people` is empty for them), with no tooltip.
  One role added at **both** levels has each of its two chips in the box name its own level
  (email-role-levels.md §4), the group headings not being there to say it.
- A From role chosen before the record (picker mode), which the record then does not offer (an internal user, §4),
  stays selected under its role label, as a To chip for such a role stays; the preview then reports the server's
  400 ("Preview unavailable: Collection POC is not a role on users") and Send gets the same error.
- **Subject** (required, max 500) and **Body** (optional, multi-line, max 20,000). Subject is not checked while typing
  until a Send finds it empty; from then on it checks itself on every change (`AutovalidateMode.onUserInteraction`),
  so "Enter a subject" goes as soon as a subject is typed, as the To error does when a recipient is added, and comes
  back if the field is emptied.
- Single mode shows a live preview from `/api/emails/preview` (debounced 400 ms): "Will be sent to …" with how each person was
  added, unresolved roles, and problems. Send is disabled while a problem is listed.
- Send → `POST /api/emails`; snackbar by status (`emailOutcomeMessage`: "Email sent" / "Email queued for sending" (`QUEUED`,
  `SENDING`) / "Email saved — not sent: {error}" / "Email partly sent: {error}" / "Email failed: {error}"); invalidates the record's email list, the inbox badge and table pages. Bulk mode returns params for the scaffold.
- Requests that can outlast their widget — Send, Retry on an `EmailCard`, Inbox mark read/unread and "Mark all as
  read" — take the `ProviderContainer` and `ScaffoldMessenger` before the request, so the outcome or error snackbar
  and the invalidations happen even when the dialog, card or Inbox is gone by the time the server answers. After a
  send the dialog pops itself only while it is still the current route.

### Email card (`EmailCard`)

- Header: a direction icon with its tooltip, the subject and the status chip, then a time line. On the Email tab it
  speaks for the system: `'Outgoing · {progress}'` (Icons.call_made) or `'Incoming · {progress}'`
  (Icons.call_received), where `emailProgress(email)` is by status: `SENT` "sent {sentAt, else occurredAt}",
  `RECEIVED` "received {occurredAt}", `QUEUED` "written {occurredAt}, waiting to send", `SENDING` "written …, sending",
  `PARTIAL` "written …, partly delivered", `FAILED` "written …, failed", `NOT_SENT` "written …, not delivered". With `inbox: true` (the Inbox reader) it
  speaks for the reader, who received it whichever way it went: "Received {occurredAt}", Icons.call_received,
  tooltip "Received".
- From, To and Cc: each person is separate text runs (name, address, notes) in a `Wrap` (spacing 4) under
  `MergeSemantics`, so a line breaks between a name and its address, not inside either. The address is split into two
  runs after its last `@`, in angle brackets only when a name is shown ("—" when there is neither); a part longer than
  a line still breaks by character. Notes, in `bodySmall`: `· as {role}` for From (unless the masked name already is
  the role), `— {howAdded}` or `— no email address` for a recipient.
- About, From, To, Cc, Not assigned and Sent by sit inside one `SelectionArea`, so all of them can be copied; Delivered
  from, Error, the subject and the body stay `SelectableText`.

### Placement

| Where | What |
|---|---|
| App shell | "Inbox" (Icons.inbox_outlined) directly below Dashboard, needs EMAIL_VIEW, unread badge. Route `/inbox`. |
| Every list page (customers, invoices, products, payments, promises, disputes, users, roles) | `sendEmailPageAction` in `actions`; `sendEmailRowAction` in `rowActions`; `sendEmailBulkAction` in `bulkActions` (when the user can send). Row tap opens the details page. |
| Every details page | `sendEmailHeaderButton` in the header; `emailDetailTab` as the last tab. New pages: `ProductDetailScreen` (`lib/features/products/product_detail_screen.dart`), `PromiseDetailScreen` (`lib/features/promises/promise_detail_screen.dart`), `UserDetailScreen` (`lib/features/users/user_detail_screen.dart`), `RoleDetailScreen` (`lib/features/users/role_detail_screen.dart`), each `({Key? key, required int id, String? initialTab})`, routes `/products/:id`, `/promises/:id`, `/users/:id`, `/roles/:id` via `pageForId`; `?tab=` selects a tab, an unknown slug the first. |
| Dispute details page | `DetailScaffold`. Top pane: the dispute's facts only (Opened/resolved line, Reason, Admin notes when present, Proposed change when there is no Resolve tab). Tabs: **Resolve** (slug `resolve`, Icons.gavel_outlined; admin and `PENDING` only, first so it opens by default) holding the resolve panel — hint, Proposed change (read-only) beside Applied change (JSON) and Admin notes (shown to customer), `NotifyByEmailCheckbox`, error text, Approve/Deny; then `<Target> history` (AUDIT_VIEW); then Email. |
| Create forms | `NotifyByEmailCheckbox` + `notifyByEmailAfterSave(event: created)`: customer form, invoice form, product form, record payment dialog, promise form (create), dispute create dialog, user form, role form. |
| Edit forms | Promise form (edit mode) and the dispute's Resolve tab (approve/deny): checkbox + `notifyByEmailAfterSave(event: updated)`, before navigating to `/disputes`. |
| Inbox | Columns From (not sortable for customer logins), Subject (bold when unread), About (entity label), Received, Status; row tap opens a reader dialog (`EmailCard(inbox: true)` + "Open {entity}", shown only once the email has loaded with `canOpenRecord` true and a link) and marks it read; row action mark read/unread; bulk Mark read / Mark unread; AppBar "Mark all as read"; server pagination. A banner, from `/api/emails/delivery`, says when delivery is not configured, my Gmail is not connected or needs renewing (with Connect / Reconnect to `/me/gmail`), or the last Gmail check failed (mail-service.md §6). |

## 9. Configuration

**Replaced** by mail-service.md §5.1:

```yaml
spring:
  jpa:
    open-in-view: false                        # see OpenEntityManagerInViewConfig below
  task:
    scheduling:
      pool:
        size: 3
app:
  mail:
    transport: ${MAIL_TRANSPORT:none}                 # none | mail-service
    dispatch:
      async: true
      sweep-interval-ms: 60000
    service:
      url: ${MAIL_SERVICE_URL:http://localhost:8091}
      api-key: ${MAIL_SERVICE_API_KEY:}               # required with transport mail-service
      webhook-secret: ${MAIL_SERVICE_WEBHOOK_SECRET:} # required with transport mail-service
      connect-timeout-ms: 5000
      read-timeout-ms: 40000                          # connecting calls Google
```

`app.mail.gmail.*` is gone. With `transport: mail-service`, startup fails naming `MAIL_SERVICE_API_KEY` or
`MAIL_SERVICE_WEBHOOK_SECRET` when blank.

`config/OpenEntityManagerInViewConfig` registers `OpenEntityManagerInViewInterceptor` for every path except
`/api/emails`, `/api/emails/**`, `/api/inbox`, `/api/inbox/**`, `/api/me/gmail`, `/api/users/*/gmail` and
`/api/mail-service/**`, so the other controllers, which map lazy associations after their services return, keep
open-in-view. The email paths are left out because an open EntityManager holds the connection of its first query
until the response is written, and single send, retry, "sync now", connecting Gmail and disconnecting someone's
Gmail call the mail service within the request (connecting waits on Google): with the prod pool of 5, a slow service would starve the whole app. The webhook is left out because it
applies each event in a transaction of its own, which must not share one persistence context.

Test profile (`application-test.yml`): `transport: none`, `dispatch.async: false` (bulk sends hand over inline),
`dispatch.sweep-interval-ms: 3600000` (tests call the sweeper directly), and `service.*` values for the tests that
switch the transport to `mail-service` (the webhook tests, in a context of their own). Mail goes to
`RecordingMailTransport`, a `@Primary` test bean that stands in for the mail service.
