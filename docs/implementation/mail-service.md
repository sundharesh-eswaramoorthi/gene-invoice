# Mail service: per-user Gmail, a send queue and per-recipient status — design and contracts

**Date:** 2026-09-20
**Status:** Implementation contract. The mail service (`mail-service/`), the backend and the frontend are built against
this document, so the API shapes, class names, file paths and messages below are binding. Change them only by
changing this file. Where it differs from `email.md`, this document wins; `email.md` is updated to point here.

## 1. Requirements (as given, 2026-09-20)

1. Gmail moves out of the backend into a **separate service**, kept in this repo as a third top-level folder:
   `frontend/`, `backend/`, `mail-service/`.
2. **Every internal user sets up their own Gmail connection.** They enter three things: **client ID, client secret
   and refresh token** (made with their own Google Cloud OAuth client, e.g. in Google's OAuth Playground), and get
   connected. Accounts are personal `@gmail.com`.
3. Sends go through a **queue**, because many emails go out at the same time.
4. The app shows, **for every To recipient**, whether the mail was sent, delivered and read, kept up to date by a
   webhook (or similar) from the service.

Answers the user gave to the design questions:

| Question | Answer |
|---|---|
| Who creates the Google app | Each user: client ID, client secret, refresh token (pasted; no redirect flow) |
| Account type | Personal `@gmail.com` (so a token from a Google app in "Testing" stops working after 7 days) |
| Several people in To | **One copy per person**, so each person is tracked on their own |
| Public URL | **Local only for now**: poll Gmail, no open-tracking pixel |
| Sender without a working connection | **Mark Not sent**; retry after connecting |
| Stack / queue | **Spring Boot + RabbitMQ** |
| Received mail | **Replies to app emails only** |
| Repo | A folder `mail-service/` in this repo |

## 2. Decisions

| # | Decision |
|---|---|
| M1 | **Split.** `mail-service` owns everything Gmail: each user's connection (secrets encrypted), the send queue and workers, reading mailboxes, bounce detection and delivery/read tracking. The backend keeps the email form, roles, recipients, records, the Email tab and the Inbox. The backend calls the service over REST with an API key; the service reports back with **signed webhooks**. The browser never talks to the service: the Gmail connection form goes through the backend. |
| M2 | **Connection = one Gmail account per internal user**, keyed by the backend's user id (`ownerRef`, a string). The user pastes client ID, client secret and refresh token. The service checks them at once — refresh an access token, check the granted scopes, read the Gmail profile — and stores the Gmail address it finds. Client secret and refresh token are encrypted at rest (AES-256-GCM) and never returned or logged. Customer logins do not connect Gmail. |
| M3 | **Scopes.** A connection needs to send **and** read: send = any of `gmail.send`, `gmail.compose`, `gmail.modify`, `https://mail.google.com/`; read = any of `gmail.readonly`, `gmail.modify`, `https://mail.google.com/` (all `https://www.googleapis.com/auth/…` except the last). The instructions ask for `https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly`. |
| M4 | **The sender's own Gmail sends.** An email's From person (a user, or the person a From role resolves to, E3) sends it from **their** connected Gmail, and the From header is that Gmail address with the person's name. There is no shared mailbox any more (E6 is replaced). A consequence: a customer sees the staff sender's Gmail address. |
| M5 | **No working connection → Not sent.** When the sender never connected, disconnected, or the connection needs renewing, the copy is saved as `NOT_SENT` with the reason (`"Samuel L. Jackson has not connected Gmail"`, `"Samuel L. Jackson's Gmail connection needs to be renewed"`). It can be retried after they connect. Records (invoices, disputes…) are never blocked by email. When Google stops accepting a refresh token while sending or reading, the connection becomes `NEEDS_RECONNECT` and the user gets an in-app notification. |
| M6 | **One copy per recipient.** Every TO recipient with an address gets their own message, whose To header is only them, with its own status. The email in the app stays one email with all its recipients. Staff are therefore never shown to customers in headers (the old E13 Bcc rule is no longer needed). Recipients without an address still get the in-app copy only. A person is still reached once (E5). |
| M7 | **Queue.** RabbitMQ, durable queue `mail.send`, persistent messages carrying only the copy's id; the row in the service's database is the truth. Workers (4–8 consumers) claim a copy atomically before sending, so a message delivered twice is sent once. Retries go through delay queues (1 min, then 5 min); a message the worker cannot handle goes to a dead-letter queue. Each Gmail mailbox sends at most one message per 500 ms (Gmail's per-user rate limit). A sweeper re-publishes queued copies whose queue message was lost. |
| M8 | **Per-recipient status**, per copy: `QUEUED` → `SENDING` → `SENT` → `DELIVERED` → `READ`, or `BOUNCED`, `FAILED`, `NOT_SENT`. Gmail gives no delivery or read receipts, so: **Sent** = Gmail accepted it (exact). **Bounced** = a delivery-failure notice arrived in the sender's mailbox (read from the thread; exact). **Delivered** = found in the recipient's own connected Gmail (**confirmed**), else no bounce within 15 minutes (**estimated**, flag `deliveredConfirmed: false`; a later bounce still turns it `BOUNCED`). **Read** = the recipient's connected Gmail shows it read (UNREAD label removed). For recipients without a connected Gmail (customers, outsiders) there is no Read: no open-tracking pixel (local only, no public URL). Internal recipients' **in-app** read (Inbox) is shown alongside ("read in app"). |
| M9 | **Webhook.** Every change to a copy, every received reply and every connection status change is an event, written in the same transaction as the change (outbox table) and POSTed in order, in batches, to the backend, signed with HMAC-SHA256. Delivery retries forever with back-off (max 5 min); the backend applies events idempotently (each copy's `seq` only moves forward). |
| M10 | **Received mail: replies to app emails only.** The service reads each connected mailbox's history every minute and looks only at messages in threads the app started from that mailbox. It never fetches anything else. A thread message is either a bounce (updates the copy) or a reply (sent to the backend, which saves it on the record, addressed to the mailbox owner — the person who sent the original, E8). The old rule "any mail from a customer address" (E7) is dropped. |
| M11 | **Backend hand-off.** The backend still saves every email first (`QUEUED`) and hands it to the service (`POST /api/v1/messages`) from the request (single send) or a background thread (bulk), with the existing sweeper retrying a hand-off that failed. Hand-off is idempotent: each copy carries `externalId = "gi-{emailId}-{recipientId}"`, so a repeat never sends twice (the old "delivery uncertain" recheck moves into the service). |
| M12 | **Email-level status** in the backend is the roll-up of its copies: any `QUEUED`/`SENDING` → that (`SENDING` wins); all sent/delivered/read → `SENT`; all `NOT_SENT` → `NOT_SENT`; all failed/bounced/not-sent → `FAILED`; a mix of good and bad → **`PARTIAL`** (new, "Partly sent"). |
| M13 | **Transport `none`** (default, and tests) still saves emails, which appear in the Email tab and Inboxes, with every copy `NOT_SENT` "Email delivery is not configured (mail service)". |

## 3. Architecture and ports

```
Flutter web (8087) ──JWT──► backend (8086) ──REST + X-Api-Key──► mail-service (8091) ──► Gmail API (each user's token)
                               ▲                                     │    ▲
                               └──── POST /api/mail-service/events ──┘    │ RabbitMQ (5672, UI 15672): mail.send
                                     (HMAC-signed webhook)                 └ Postgres DB `genemail`
```

Local deployment: RabbitMQ container `geneinvt1-rabbitmq` (`rabbitmq:3.13-management`, ports 5672/15672), database
`genemail` in the existing `geneinvt1-main-db` Postgres (localhost:5435), mail service jar on **8091** (8090 is
taken by another container).

## 4. mail-service (`mail-service/`)

Maven project `com.geneinvoice:gene-mail-service`, Spring Boot 3.3.5, Java 17, base package `com.geneinvoice.mail`.
Dependencies: web, data-jpa, validation, amqp, actuator, mail (Jakarta Mail for MIME), postgresql, lombok; tests:
spring-boot-starter-test, spring-rabbit-test, h2, testcontainers (`rabbitmq`, `junit-jupiter`). Files:
`pom.xml`, `README.md` (run, env, Google setup of §8), `docker-compose.yml` (RabbitMQ only), `.gitignore`
(`target/`), `src/main/resources/application.yml`, `src/test/resources/application-test.yml`.

### 4.1 Packages and classes

| Package | Classes |
|---|---|
| `mail` | `MailServiceApplication` (`@EnableScheduling`) |
| `mail.config` | `MailProperties` (`@ConfigurationProperties("mail")`, validated at startup, §4.9), `RabbitTopology` (declarables, listener factory), `ApiKeyFilter` (`/api/v1/**`), `ApiErrors` (`@RestControllerAdvice`), `Clock` bean (`Clock.systemUTC()`, injected everywhere a time is taken so tests can move it) |
| `mail.connection` | `MailConnection`, `ConnectionStatus` (`CONNECTED`, `NEEDS_RECONNECT`, `DISCONNECTED`), `MailConnectionRepository`, `ConnectionService`, `ConnectionController`, `SecretBox` |
| `mail.message` | `MailMessage`, `MessageStatus`, `MailMessageRepository`, `MessageService` (submit, retry), `MessageController`, `SendQueue` (interface), `RabbitSendQueue`, `SendListener` (`@RabbitListener`), `SendWorker`, `MailboxThrottle`, `SendSweeper` |
| `mail.tracking` | `DeliveryTracker`, `MailboxSync`, `SyncScheduler`, `DsnParser`, `MailInbound`, `MailInboundRepository` |
| `mail.events` | `MailEvent`, `MailEventRepository`, `EventRecorder`, `WebhookDispatcher`, `WebhookSigner` |
| `mail.gmail` | `GmailHttp`, `GmailClient`, `GmailApiException`, `GmailMime`, `GoogleTokens` (refresh + per-connection access-token cache), `GoogleAuthException` (Google refused the credentials: `invalid_grant`, `invalid_client`, `unauthorized_client`, `deleted_client`, `disabled_client`) — ported from the backend's `email.gmail` package, which is deleted there |

### 4.2 Data model (Postgres `genemail`, `ddl-auto: update`; H2 in tests)

`mail_connections` — `MailConnection`

| Column | Type | Notes |
|---|---|---|
| id | bigint identity | |
| owner_ref | varchar(64) unique | the backend's user id |
| owner_name | varchar(200) | display name at connect time |
| gmail_address | varchar(320) null | lower case, from the Gmail profile |
| client_id | varchar(300) | not secret; returned to the owner |
| client_secret_enc | varchar(1000) null | `SecretBox`; null once disconnected |
| refresh_token_enc | varchar(4000) null | `SecretBox`; null once disconnected |
| scopes | varchar(1000) | space-separated, as Google granted them |
| status | varchar(20) | `ConnectionStatus` |
| status_reason | varchar(1000) null | why it needs renewing |
| history_id | varchar(40) null | Gmail history cursor |
| last_synced_at | timestamp null | last sync that finished |
| last_sync_error | varchar(1000) null | |
| connected_at, created_at, updated_at | timestamp | |
| version | bigint | `@Version` |

`mail_messages` — `MailMessage` (one row per copy)

| Column | Type | Notes |
|---|---|---|
| id | bigint identity | the queue carries this |
| external_id | varchar(100) **unique** | the client's key, e.g. `gi-91-501` |
| group_ref | varchar(100) null | the client's email id |
| connection_id | bigint null | the sender's connection when submitted (null when there was none) |
| sender_ref | varchar(64) | the sender's `ownerRef` |
| from_name | varchar(200) | |
| from_address | varchar(320) null | the Gmail address it was sent from |
| to_name | varchar(200) | |
| to_address | varchar(320) | |
| to_address_key | varchar(320) | lower case, indexed |
| subject | varchar(500) | |
| body | varchar(20000) | |
| rfc_message_id | varchar(300) | `<gm-{uuid}@{sender gmail domain, else geneinvoice.local}>`, fixed at submit; indexed |
| status | varchar(12) | `MessageStatus`: `QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `READ`, `BOUNCED`, `FAILED`, `NOT_SENT` |
| seq | bigint | +1 on **every** change the client can see; carried by every event |
| error | varchar(1000) null | failure, not-sent or bounce reason |
| attempts | int | |
| next_attempt_at | timestamp null | |
| enqueued_at | timestamp null | when last put on the queue |
| delivery_uncertain | boolean | an attempt may have delivered it (§4.5) |
| provider_message_id | varchar(100) null unique | sender-side Gmail id |
| provider_thread_id | varchar(100) null | sender-side thread; indexed with connection_id |
| recipient_connection_id | bigint null | the recipient's own connection, once found (§4.6) |
| recipient_message_id | varchar(100) null | the recipient-side Gmail id; indexed |
| sent_at, delivered_at, read_at, bounced_at | timestamp null | |
| delivered_confirmed | boolean | true when seen in the recipient's mailbox |
| created_at, updated_at | timestamp | |

Indexes: `(status, next_attempt_at)`, `(connection_id, provider_thread_id)`, `recipient_message_id`, `to_address_key`,
`rfc_message_id`, `group_ref`.

`mail_inbound` — `MailInbound`: `id`, `connection_id`, `provider_message_id` varchar(100), `provider_thread_id`,
`kind` varchar(10) (`REPLY`, `BOUNCE`, `DELAY`, `SKIPPED`), `message_id` bigint null (the copy it concerned),
`created_at`; unique `(connection_id, provider_message_id)`. It stops a message being handled twice.

`mail_events` — `MailEvent`: `id` bigint identity (delivery order), `type` varchar(40), `owner_ref` varchar(64)
null, `external_id` varchar(100) null, `payload` text (JSON of `data`, §4.8), `created_at`, `delivered_at` null,
`attempts` int, `last_error` varchar(1000) null. Index `(delivered_at, id)`.

### 4.3 API (`/api/v1/**`; every call needs header `X-Api-Key`)

`ApiKeyFilter`: missing or wrong key → 401 `{"status":401,"error":"Unauthorized","message":"Missing or invalid API key"}`,
compared in constant time. `/actuator/health` is open. Errors everywhere:
`{"status":400,"error":"Bad Request","message":"…","fieldErrors":{"field":"…"}}` (`fieldErrors` only for validation;
then `message` is the distinct field messages joined with `"; "`, so a caller that shows only `message` still says what
to fix — the backend passes `message` on and never appends `fieldErrors` to it). Two requests changing the same rows
at once → 409 `"Another request changed the same data at the same time; try again"`.

`ConnectionDto`:

```jsonc
{"ownerRef": "7", "ownerName": "Jane Doe", "status": "CONNECTED",   // CONNECTED | NEEDS_RECONNECT | DISCONNECTED
 "gmailAddress": "jane@gmail.com", "clientId": "123-abc.apps.googleusercontent.com",
 "scopes": ["https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/gmail.readonly"],
 "statusReason": null, "connectedAt": "2026-09-20T10:00:00Z", "lastSyncedAt": null, "lastSyncError": null}
```

`CopyState` (response of submit, and the `data` of a `message.status` event):

```jsonc
{"externalId": "gi-91-501", "groupRef": "91", "seq": 3, "status": "SENT",
 "error": null, "attempts": 1, "fromAddress": "jane@gmail.com",
 "sentAt": "…", "deliveredAt": null, "deliveredConfirmed": false, "readAt": null, "bouncedAt": null,
 "providerMessageId": "18c2…", "providerThreadId": "18c2…", "rfcMessageId": "<gm-…@gmail.com>"}
```

| Method | Path | Purpose |
|---|---|---|
| PUT | `/api/v1/connections/{ownerRef}` | Connect or replace. Body `{"ownerName","clientId","clientSecret","refreshToken"}`. Trimmed; blank → 400 field errors `"Enter the client ID"`, `"Enter the client secret"`, `"Enter the refresh token"`; longer than 300 / 300 / 2000 (`ownerName` 200, cut) → 400 `"… is too long"`. Then (§4.4): refresh an access token, check scopes, read the profile. Success → 200 `ConnectionDto` (status `CONNECTED`, `history_id` = the profile's for a new connection, one without a `history_id`, or a different Gmail address; reconnecting the same address keeps its `history_id`, so what came while it could not be read is still read — a cursor Gmail no longer has falls back as in §4.7 step 2; sync errors cleared), the access-token cache for this owner dropped, event `connection.status`. Google refused → 400 with the message of §4.4; Google unreachable or 5xx → 502 `"Could not reach Google: …"` / `"Google is unavailable (503): …"`. Nothing is stored on failure (an existing connection is left as it was). |
| GET | `/api/v1/connections/{ownerRef}` | `ConnectionDto`, or 404 `"No Gmail connection for 7"`. |
| GET | `/api/v1/connections` | All, ordered by owner name: `[ConnectionDto]`. |
| DELETE | `/api/v1/connections/{ownerRef}` | Revoke the refresh token at Google (best effort, errors only logged), wipe both secrets, status `DISCONNECTED`, reason null; event `connection.status` (only when the status changed). 204, also when there was no connection. |
| POST | `/api/v1/connections/{ownerRef}/sync` | Read that mailbox now (§4.7). `{"enabled": bool, "fetched": n, "imported": n, "error": str\|null}`; no connection or not `CONNECTED` → `enabled: false`, error `"Gmail is not connected"`; a run already under way → `enabled: true`, 0, 0, `"A sync is already running"`; `mail.sync.enabled` false → `enabled: false`, `"Receiving email is turned off (MAIL_SYNC_ENABLED)"`. |
| POST | `/api/v1/messages` | Submit copies (below). 202 `{"copies": [CopyState]}` in request order. |
| GET | `/api/v1/messages/{externalId}` | `CopyState`, or 404. |

Submit body:

```jsonc
{"sender": {"ownerRef": "7", "name": "Jane Doe"},
 "subject": "Invoice INV-0042", "body": "…", "groupRef": "91",
 "retry": false,
 "copies": [{"externalId": "gi-91-501", "to": {"name": "Bob Smith", "address": "bob@acme.com"}}]}
```

- Validation (400): `sender.ownerRef` 1–64; `sender.name` ≤ 200 (cut); `subject` required, line breaks → spaces,
  trimmed, 1–500; `body` null → `""`, ≤ 20,000; `copies` 1–500; each `externalId` 1–100 and unique in the request;
  `to.address` 1–320; `to.name` ≤ 200 (cut; blank → the address). U+0000 removed and lone surrogates → U+FFFD first.
- One transaction. Per copy:
  - **Existing `externalId`:** unchanged and returned as it is — unless `retry` is true and it is `FAILED` or
    `NOT_SENT`: then it is requeued as a new copy would be (sender connection looked up again, `attempts` 0, `error`,
    `next_attempt_at` and the timestamps cleared, `seq` +1, `delivery_uncertain` kept), with the request's subject and
    body. A copy in any other state is never sent again.
  - **New:** status by the sender's connection now: none or `DISCONNECTED` → `NOT_SENT`
    `"{sender.name} has not connected Gmail"`; `NEEDS_RECONNECT` → `NOT_SENT`
    `"{sender.name}'s Gmail connection needs to be renewed"`; an address Jakarta Mail rejects
    (`InternetAddress(address, true)`) → `NOT_SENT` `"Invalid email address: {address}"`; else `QUEUED`. `seq` 1.
  - Every created or changed copy records a `message.status` event.
- After commit, every `QUEUED` copy's id is published (`SendQueue.enqueue`) and `enqueued_at` set. A publish that
  fails is logged; the sweeper publishes it later.

### 4.4 Connecting (`ConnectionService`, `GoogleTokens`)

1. `POST {token-url}` form `client_id, client_secret, refresh_token, grant_type=refresh_token`.
   - `{"error":"invalid_grant"}` → 400 `"Google did not accept the refresh token (invalid_grant: {error_description}). Make sure it was made with this client ID and secret, and has not expired or been revoked."`
   - `invalid_client` / `unauthorized_client` / `deleted_client` / `disabled_client` → 400 `"Google did not accept the client ID and secret ({error}: {error_description})."`
   - Other 4xx → 400 `"Google sign-in refused the request ({code}): {explanation}"`; 5xx → 502 `"Google is unavailable ({code}): {explanation}"`; I/O → 502 `"Could not reach Google: {detail}"`.
2. Scopes: the token response's `scope`; if absent, `GET {tokeninfo-url}?access_token=…` and its `scope`. Missing
   send or read (M3) → 400 `"This refresh token cannot {send mail|read mail|send or read mail}. Make a new one with the scopes https://www.googleapis.com/auth/gmail.send and https://www.googleapis.com/auth/gmail.readonly."`
3. `GET {api-base-url}/gmail/v1/users/me/profile` → `emailAddress`, `historyId`. Errors per `GmailApiException`
   (4xx → 400 with its message; 5xx/I/O → 502).
4. Save (encrypt with `SecretBox`), cache the access token from step 1.

`GoogleTokens.accessToken(connection)`: cached per connection id until 60 s before expiry; the cache key includes the
connection's `version`, so reconnecting never uses an old token. A `GoogleAuthException` while sending or syncing marks
the connection `NEEDS_RECONNECT` with reason `"Google no longer accepts this Gmail connection ({error}: {description}). Reconnect Gmail."`
and records `connection.status` (only on the change).

`SecretBox`: AES/GCM/NoPadding, 256-bit key from `mail.secrets-key` (base64), 12-byte random IV, stored as
`base64(iv ‖ ciphertext+tag)`. A value that fails to decrypt (key changed) makes the connection `NEEDS_RECONNECT`
`"The stored Gmail secrets cannot be read. Reconnect Gmail."`.

### 4.5 Sending (`RabbitTopology`, `SendListener`, `SendWorker`)

Topology (declared by the app):

| Name | Kind | Settings |
|---|---|---|
| `mail.send` | direct exchange, durable | |
| `mail.send` | queue, durable | bound to exchange `mail.send` with key `send`; `x-dead-letter-exchange: mail.dead` |
| `mail.retry` | direct exchange, durable | |
| `mail.send.retry.1m` / `mail.send.retry.5m` | queues, durable | bound to `mail.retry` with keys `1m` / `5m`; `x-message-ttl` 60000 / 300000; `x-dead-letter-exchange: mail.send`, `x-dead-letter-routing-key: send` |
| `mail.dead` | fanout exchange, durable | |
| `mail.send.dead` | queue, durable | bound to `mail.dead` |

Messages: JSON `{"id": 123}`, persistent. `SendQueue`: `enqueue(long id)`, `enqueueRetry(long id, Duration delay)`
(the first of `mail.send.retry-delays` → `1m`, otherwise `5m`). `RabbitSendQueue` implements it (active unless `mail.queue: direct`); tests use a
direct implementation that records ids and lets the test run the worker.

`SendListener`: `@RabbitListener(queues = "mail.send")`, container concurrency `mail.send.concurrency` (4) to
`mail.send.max-concurrency` (8), prefetch 2, acknowledge after the listener returns, `defaultRequeueRejected=false`
(an exception → `mail.send.dead`, logged). It calls `SendWorker.process(id)`.

`SendWorker.process(id)` — every DB step its own short transaction, none open during a Google call:

1. **Claim**: `update … set status='SENDING', attempts=attempts+1, seq=seq+1, updated_at=now where id=? and status='QUEUED' and (next_attempt_at is null or next_attempt_at <= now)`. 0 rows → return. Record the event.
2. Connection by `sender_ref` (the current one). Missing/`DISCONNECTED`/`NEEDS_RECONNECT` → `NOT_SENT` with the
   reason of §4.3 (owner name from the copy's `from_name`). Record `connection_id`.
3. `MailboxThrottle.acquire(connectionId)`: waits until `mail.send.per-mailbox-interval-ms` (500) after that
   mailbox's previous send started.
4. Access token (`GoogleTokens`); `GoogleAuthException` → connection `NEEDS_RECONNECT` (§4.4) and the copy `NOT_SENT`
   `"{from_name}'s Gmail connection needs to be renewed"`.
5. `delivery_uncertain` → search the sender's mailbox `q=rfc822msgid:{id without brackets}` (`includeSpamTrash=true`);
   found → treat as sent (step 7) without sending; search failed → the failure of step 8 with message
   `"Could not check whether the email was already sent: {error}"`.
6. MIME (`GmailMime.build`): `From: "{from_name}" <{gmail address}>`, `To: "{to_name}" <{to_address}>`, `Subject`,
   `Date`, `Message-ID: {rfc_message_id}`, `MIME-Version`, `Content-Type: text/plain; charset=UTF-8`, CRLF, encoded
   words for non-ASCII. `POST /gmail/v1/users/me/messages/send {"raw": base64url}`; 2xx without an id → permanent and
   uncertain `"Gmail accepted the message but did not return its id"`. Then read back `Message-ID` and `From`
   (`format=metadata`); a value found replaces ours.
7. **Sent**: `SENT`, `sent_at`, `from_address`, provider ids, `error` null, `delivery_uncertain` false, `seq`+1, event.
   Then, when a run of the sender's mailbox (§4.7) was under way or ran since just before `messages.send` (always for
   a copy found by the step-5 recheck) — such a run passed over the thread, whose id was not recorded yet —
   `GET /gmail/v1/users/me/threads/{providerThreadId}?format=minimal` and pass the messages after the copy through
   §4.7 steps 4–5 (`mail_inbound` stops double handling). Best effort: a 404 is ignored, a `GoogleAuthException`
   marks the connection (§4.4), other errors are logged; the send stands.
8. **Failed** (`GmailApiException`, classification as in `email.md` §7, including uncertain): `error` = its message;
   `delivery_uncertain` set when uncertain (never cleared by a failure). Transient and `attempts < mail.send.max-attempts`
   (3) → `QUEUED`, `next_attempt_at` = now + 1 min (after attempt 1) / 5 min (`mail.send.retry-delays`), `seq`+1,
   event, then `enqueueRetry(id, delay)` after commit (`enqueued_at` = when it is due, so the sweeper leaves a copy
   waiting in a delay queue alone). Otherwise `FAILED`. A failure reported after the copy left `SENDING`
   changes nothing. Any other exception → `FAILED` `"Delivery failed: {message}"`, logged.

`SendSweeper` (`@Scheduled` every `mail.send.sweep-interval-ms`, 30000):
- `SENDING` untouched 10 min → `FAILED` `"Sending was interrupted; retry to send again"`, `delivery_uncertain` true, event.
- `QUEUED`, due (`next_attempt_at` null or ≤ now), `enqueued_at` null or older than 2 min → published again (max 500 per run).

### 4.6 Delivery and read tracking (`DeliveryTracker`, `@Scheduled` every `mail.tracking.interval-ms`, 60000)

1. **Confirm in the recipient's mailbox.** Copies `SENT` or `DELIVERED` with `recipient_message_id` null, sent
   within `mail.tracking.confirm-window` (24 h), whose `to_address_key` equals a `CONNECTED` connection's
   `gmail_address` (max 200 per run, oldest first): search that mailbox `q=rfc822msgid:{id}` with
   `includeSpamTrash=true`. Found → `recipient_connection_id`, `recipient_message_id`, `delivered_confirmed` true,
   `DELIVERED` (`delivered_at` = now unless already set), and if its labels have no `UNREAD` → `READ`, `read_at` now.
   One event for the change. A search error skips that copy this run (logged at debug); a `GoogleAuthException`
   marks that connection as §4.4.
2. **Estimate delivery.** Copies still `SENT` with `sent_at` older than `mail.tracking.delivered-after` (15 min) →
   `DELIVERED`, `delivered_confirmed` false, `delivered_at` now, event.
3. **Read** comes from the recipient mailbox's sync (§4.7).

A copy never goes back: `READ` stays `READ`; `DELIVERED` becomes `BOUNCED` only when not confirmed.

### 4.7 Reading mailboxes (`MailboxSync`, `SyncScheduler`)

`SyncScheduler` runs every `mail.sync.interval-ms` (60000) while `mail.sync.enabled`, one connection after another,
for every `CONNECTED` connection. A run per connection holds that connection's lock (`tryLock`; busy → skipped, or
`"A sync is already running"` for the API).

1. No `history_id` → store the profile's and stop.
2. `GET /gmail/v1/users/me/history?startHistoryId=…&historyTypes=messageAdded&historyTypes=labelRemoved` (all pages).
   A 404 (cursor too old) → read the profile's `historyId`, then `messages.list?q=newer_than:7d` (all pages; ids and
   thread ids only) as the added messages, and continue from the profile's id. Also, for copies sent in the last
   7 days found in this mailbox (`recipient_connection_id` this connection, `recipient_message_id` set, status
   `SENT`/`DELIVERED`), `format=metadata` of each: no `UNREAD` label (or no labels) → `READ` as in step 3; a 404
   skips that copy.
3. **Label removals.** `UNREAD` removed from a message id that is a copy's `recipient_message_id` with this
   connection as `recipient_connection_id` and status `SENT`/`DELIVERED` → `READ`, `read_at` now, event. `SPAM` or
   `TRASH` removed → the message is treated as added.
4. **Added messages.** Keep only those whose `threadId` is the `provider_thread_id` of a copy sent from this
   connection (one query per page). Skip our own sent copies (`provider_message_id`), ids already in `mail_inbound`
   for this connection, and messages whose labels (listing, then `format=metadata`) include `SENT`, `DRAFT`, `SPAM`
   or `TRASH`. Then `format=raw` and parse (`GmailMime.parse`, as in `email.md` §7).
5. **Bounce or reply** (`DsnParser`): a bounce is `multipart/report; report-type=delivery-status`, or a message from
   `mailer-daemon@…`/`postmaster@…` with an `X-Failed-Recipients` header. From the `message/delivery-status` part:
   per recipient `Final-Recipient`/`Original-Recipient` (`rfc822;addr`), `Action`, `Status`, `Diagnostic-Code`;
   from the `message/rfc822` or `text/rfc822-headers` part the original `Message-ID`. The copy: the one whose
   `rfc_message_id` is that Message-ID, else the thread's copy whose address is among the failed recipients, else
   the thread's only copy. `Action: failed` (or `X-Failed-Recipients` alone) → `BOUNCED` (from `SENT`, or
   `DELIVERED` not confirmed), `bounced_at` = the notice's time, `error` = `"{Status} {Diagnostic-Code without the smtp; prefix}"`
   cut to 1000 (else `"The recipient's mail server rejected the message"`), event; `mail_inbound` kind `BOUNCE`.
   `Action: delayed` only → nothing changes (kind `DELAY`). A bounce naming no copy → kind `SKIPPED`, logged.
   Anything else is a **reply**: event `message.received` (§4.8) naming the newest copy (by `sent_at`) in that
   thread from this connection; kind `REPLY`.
6. Per-message and per-run error handling as `email.md` §7 "Receive" (skip with WARN, or stop the run). A run that
   finishes stores the history id, `last_synced_at` and `last_sync_error` null; one that stops keeps the history id
   and stores the error (cut to 1000). `GoogleAuthException` → the connection as §4.4.

### 4.8 Events and the webhook (`EventRecorder`, `WebhookDispatcher`, `WebhookSigner`)

Every event is written by `EventRecorder.record(type, ownerRef, externalId, data)` in the transaction of the change.

| Type | `data` |
|---|---|
| `message.status` | `CopyState` (§4.3) |
| `message.received` | `{"ownerRef", "mailboxAddress", "repliedToExternalId", "providerMessageId", "providerThreadId", "rfcMessageId", "inReplyTo", "references": [], "from": {"name","address"}, "to": [{"name","address"}], "cc": [...], "subject", "body", "receivedAt"}` |
| `connection.status` | `ConnectionDto` — on connect, on a status change, and when a sync's `lastSyncError` appears, changes or clears (so the backend's copy, which feeds the Inbox banner, stays current; a run that fails the same way again records nothing) |

`WebhookDispatcher` (`@Scheduled(fixedDelay = mail.webhook.interval-ms, 1000)`): with `mail.webhook.url` blank it does
nothing (events wait). Otherwise it takes the oldest undelivered events (max `mail.webhook.batch-size`, 100), and
POSTs

```jsonc
{"events": [{"id": 812, "type": "message.status", "occurredAt": "…", "data": {…}}]}
```

with headers `Content-Type: application/json`, `X-Mail-Timestamp: {unix seconds}`,
`X-Mail-Signature: sha256={hex HMAC-SHA256(webhook secret, timestamp + "." + body)}`. Connect timeout 5 s, read
timeout 30 s. 2xx → `delivered_at` now for the batch. Anything else → `attempts`+1 and `last_error` on those events,
and the dispatcher waits 1 s, 2 s, 4 s … up to 300 s before the next try (in memory; reset by a success).
Delivered events older than 7 days are deleted once a day.

### 4.9 Configuration (`application.yml`)

```yaml
server:
  port: ${PORT:8091}
spring:
  datasource:
    url: ${MAIL_DB_URL:jdbc:postgresql://localhost:5435/genemail}
    username: ${MAIL_DB_USER:geneinvoice}
    password: ${MAIL_DB_PASSWORD:geneinvoice}
  jpa:
    hibernate.ddl-auto: update
    open-in-view: false
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  task.scheduling.pool.size: 4          # webhook, sweeper, tracker, sync
mail:
  api-key: ${MAIL_API_KEY:}             # required, at least 16 characters
  secrets-key: ${MAIL_SECRETS_KEY:}     # required, base64 of 32 bytes
  queue: rabbit                         # rabbit | direct (tests)
  webhook:
    url: ${MAIL_WEBHOOK_URL:}           # e.g. http://localhost:8086/api/mail-service/events
    secret: ${MAIL_WEBHOOK_SECRET:}     # required when url is set, at least 16 characters
    interval-ms: 1000
    batch-size: 100
  send:
    concurrency: 4
    max-concurrency: 8
    per-mailbox-interval-ms: 500
    max-attempts: 3
    sweep-interval-ms: 30000
    retry-delays: PT1M, PT5M            # also the TTLs of mail.send.retry.1m / .5m (delete both queues after a change)
  tracking:
    interval-ms: 60000
    delivered-after: PT15M
    confirm-window: PT24H
  sync:
    enabled: ${MAIL_SYNC_ENABLED:true}
    interval-ms: ${MAIL_SYNC_INTERVAL_MS:60000}
  google:
    api-base-url: ${GMAIL_API_BASE_URL:https://gmail.googleapis.com}
    token-url: ${GOOGLE_TOKEN_URL:https://oauth2.googleapis.com/token}
    tokeninfo-url: ${GOOGLE_TOKENINFO_URL:https://oauth2.googleapis.com/tokeninfo}
    revoke-url: ${GOOGLE_REVOKE_URL:https://oauth2.googleapis.com/revoke}
```

A missing or invalid required setting stops startup with a message naming the variable (`MAIL_API_KEY`,
`MAIL_SECRETS_KEY`, `MAIL_WEBHOOK_SECRET`). The Google URLs are settable so tests and the E2E run use a fake Gmail.

### 4.10 Tests (mail-service)

Unit: `SecretBox`, `WebhookSigner`, `DsnParser` (Gmail's real bounce layout, `X-Failed-Recipients` only, delayed),
`GmailMime` (ported tests), `GmailApiException` (ported), `MailboxThrottle`. Integration (H2, `mail.queue: direct`,
a JDK `HttpServer` fake for Google token/tokeninfo/revoke/Gmail, and a fake webhook receiver): connect success, each
connect error, scope check; submit validation, idempotency, retry; worker paths (sent, transient → retry, permanent,
uncertain → recheck, `invalid_grant` → `NEEDS_RECONNECT` + `NOT_SENT`, disconnected sender); sweeper; tracker
confirm/estimate/read; sync bounce/reply/delay/history 404 (and copies read meanwhile); a reconnect of the same
mailbox keeping its history cursor; a bounce a run passed over before the copy's thread was recorded;
`deleted_client`/`disabled_client` → `NEEDS_RECONNECT`; webhook signing, batching, back-off; API key filter.
One Testcontainers test (`@Testcontainers(disabledWithoutDocker = true)`, `rabbitmq:3.13-management`) sends through
the real queue, including a retry through the delay queue with the delays shortened. `pom.xml` sets
`testcontainers.version` 1.21.4: the 1.19.8 Boot 3.3.5 manages speaks Docker API 1.32, which current Docker refuses.

## 5. Backend changes (`backend/`)

### 5.1 Configuration

```yaml
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
      webhook-max-bytes: 33554432                     # largest webhook body read (§5.5); default 32 MiB
```

`app.mail.gmail.*` is removed, with the `email.gmail` package, its tests and `MailSyncState` (the old table is left
in the database, unused). `spring.task.scheduling.pool.size` stays 3.

### 5.2 Transport contract (`com.geneinvoice.email.transport`, replaces the old one)

| Type | Shape |
|---|---|
| `MailTransport` | `boolean isConfigured()`; `List<CopyState> submit(Submission s)` throws `MailSendException` |
| `Submission` | `(long senderUserId, String senderName, String subject, String body, String groupRef, boolean retry, List<CopyRequest> copies)` |
| `CopyRequest` | `(String externalId, String name, String address)` |
| `CopyState` | record of §4.3's `CopyState` (`status` as `RecipientDeliveryStatus`) |
| `MailSendException` | `(String message, boolean transientFailure[, Throwable cause])` |
| `MailConnections` | `ConnectionState connect(long userId, String name, String clientId, String clientSecret, String refreshToken)` throws `MailConnectException`; `Optional<ConnectionState> connection(long userId)` throws `MailConnectException`; `void disconnect(long userId)` throws `MailConnectException`; `SyncResult syncNow(long userId)` |
| `ConnectionState` | record of `ConnectionDto` |
| `MailConnectException` | `(int httpStatus, String message)` — 400 (Google refused / invalid), 502 (Google unreachable), 503 (service unreachable or not configured) |
| `IncomingMail` | unchanged |
| `InboundHint` | `(long mailboxOwnerUserId, String mailboxAddress, String repliedToExternalId)` |
| `NoopMailTransport` | active when `app.mail.transport` is `none`/unset: `isConfigured` false; implements `MailConnections` with 503 `"Email delivery is not configured (mail service)"` (and `connection` → empty, `syncNow` → `enabled: false`, that error) |

Removed: `OutgoingMail`, `SentMail`, `MailAddress` (if unused), `InboundMailSync`, `IncomingMailHandler.isKnown`.

`com.geneinvoice.email.mailservice` (active with `app.mail.transport=mail-service`): `MailServiceProperties`
(startup fails naming `MAIL_SERVICE_API_KEY` / `MAIL_SERVICE_WEBHOOK_SECRET` when blank), `MailServiceClient`
(implements `MailTransport` and `MailConnections`; JDK `HttpClient`, never retries a POST itself), `MailServiceDtos`,
`MailServiceEventsController`, `MailServiceEventHandler`, `WebhookSignature`.

`MailServiceClient` errors: I/O → `MailSendException("Could not reach the mail service: {detail}", true)`;
5xx → `("The mail service is unavailable ({code})", true)`; 4xx → `("The mail service refused the email: {message}", false)`.
For `MailConnections`: the service's 400/502 pass through with its message; its 404 on GET → empty; I/O, 5xx or any
other status (e.g. 401 for a wrong key) → 503 `"Could not reach the mail service: …"`. A 2xx submit answer that cannot
be read is transient (`"Unexpected answer from the mail service: …"`; handing the copies over again is harmless).

### 5.3 Data model

`emails`: `status` gains **`PARTIAL`**; new column `handed_off_at` timestamp null (when the service accepted the
copies). `delivery_uncertain`, `provider_message_id`, `provider_thread_id` and `rfc_message_id` stay for received mail
and old rows; new outbound emails leave them null (the copies carry them). `delivered_from` = the copies' `fromAddress`.

`email_recipients` — new columns (all null/false/0 for received mail and for emails sent before this change):

| Column | Type | Notes |
|---|---|---|
| delivery_status | varchar(12) null | `RecipientDeliveryStatus`: `QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `READ`, `BOUNCED`, `FAILED`, `NOT_SENT`. Set to `QUEUED` when an outbound email is saved, for each `TO` recipient with an address; null otherwise. |
| delivery_error | varchar(1000) null | |
| delivery_seq | bigint not null default 0 | last applied `seq` |
| sent_at, delivered_at, mail_read_at, bounced_at | timestamp null | `mail_read_at` = read in Gmail (not the in-app `read_at`) |
| delivered_confirmed | boolean not null default false | |
| provider_message_id | varchar(100) null | the copy's sender-side Gmail id |
| provider_thread_id | varchar(100) null | indexed |
| rfc_message_id | varchar(300) null | indexed |

Column defaults are declared so `ddl-auto: update` can add them to tables that have rows. `Email` and
`EmailRecipient` are `@DynamicUpdate` (an update writes only the columns it changed): a recipient marking their Inbox
row read and a report on that row's copy (under the email's lock, which the Inbox does not take) would otherwise
put back each other's columns. Marking read or unread (`POST /api/inbox/{id}/read|unread`, bulk `MARK_READ` /
`MARK_UNREAD`) is a targeted `update … set is_read, read_at where id = ? and user_id = ? and field = 'TO'`, like
mark-all-read.

`gmail_connections` — `GmailConnection` (package `com.geneinvoice.email.connection`): the backend's copy of each
user's connection status, for the compose form and the user page without calling the service: `user_id` bigint PK,
`status` varchar(20) (`CONNECTED`, `NEEDS_RECONNECT`, `DISCONNECTED`), `gmail_address` varchar(320) null, `reason`
varchar(1000) null, `connected_at` null, `last_synced_at` null, `last_sync_error` varchar(1000) null (for
`GET /api/emails/delivery`), `disconnect_requested_at` timestamp null (a removal still to be confirmed by the
service, §5.6), `updated_at`. Written from connect/disconnect/refresh responses (also after `POST /api/emails/sync`)
and `connection.status` events. A first copy is never made by reading and then inserting it — the answer to a first
connect and the service's `connection.status` report of it can arrive together, and the second insert would fail
(409 on the connect) —: a blank `DISCONNECTED` row is inserted in a transaction of its own (a duplicate key is
ignored), then the row is updated under `PESSIMISTIC_WRITE`.

`EmailSchemaUpgrade` (backend, at startup): an older database's check constraint on `emails.status` (PostgreSQL and
H2) is replaced by one listing every `EmailStatus`, since `ddl-auto: update` never alters a constraint and `PARTIAL`
would otherwise be refused. `GmailStatus` (API values) = `CONNECTED`, `NEEDS_RECONNECT`, `NOT_CONNECTED` (no row or
`DISCONNECTED`).

### 5.4 Dispatch (`EmailDispatcher`, rewritten; §7 of `email.md` is replaced)

1. Claim as today (`QUEUED` → `SENDING`, `attempts`+1) but only rows with `handed_off_at` null; the sweeper's
   `findDue` and `findStaleSending` also only look at `handed_off_at` null.
2. Not configured → every copy (`delivery_status` `QUEUED`) `NOT_SENT` `"Email delivery is not configured (mail service)"`, roll-up.
3. No `TO` recipient with an address → email `NOT_SENT` `"No recipient has an email address"`. The sender is a
   customer login → every copy `NOT_SENT` `"Email from a customer login is not sent through Gmail"` (they do not
   connect Gmail; handing them over would only come back "… has not connected Gmail").
4. `Submission`: sender = `from_user_id` and `from_name`; `groupRef` = email id; `retry` = true when any copy was
   handed off before (the email was retried) — always true is also safe; copies = `TO` recipients with an address
   whose `delivery_status` is `QUEUED`, `externalId` `gi-{emailId}-{recipientId}`.
5. Success → in one transaction with the email row locked (`findByIdForUpdate`, `PESSIMISTIC_WRITE`): apply each
   returned `CopyState` (§5.5), `handed_off_at` = now, roll-up (M12).
6. `MailSendException` transient and `attempts < 3` → email `QUEUED` (still not handed off), `next_attempt_at` +1 min /
   +5 min, `error` = message; else its `QUEUED` copies `FAILED` with the message and the email rolled up (`FAILED`
   when it has no other copies; a retried `PARTIAL` email stays `PARTIAL`). Other exceptions → the same with
   `"Delivery failed: {message}"`. A copy the service's answer leaves out → `FAILED`
   `"The mail service did not accept this copy"`. The stale-`SENDING` sweep (`SENDING`, `handed_off_at` null,
   untouched 10 min) settles each such email on its own with the row locked (and checked again): its `QUEUED` copies
   `FAILED` `"Sending was interrupted; retry to send again"`, then the roll-up — `FAILED` with that message only
   when it has no copies, so a retried `PARTIAL` email whose hand-off died stays `PARTIAL` — and `next_attempt_at` null.

**Roll-up** (`EmailDeliveryRollup.apply(Email, List<EmailRecipient>)`), over recipients with a `delivery_status`:
any `SENDING` → `SENDING`; else any `QUEUED` → `QUEUED`; else all in {`SENT`,`DELIVERED`,`READ`} → `SENT`; else all
`NOT_SENT` → `NOT_SENT`; else all in {`FAILED`,`BOUNCED`,`NOT_SENT`} → `FAILED`; else `PARTIAL`. `error`: null when
none failed; the one message when every failed copy has the same one; else `"{n} of {m} not delivered: {first message}"`
(cut to 1000). `sent_at` = the earliest copy `sent_at`. `delivered_from` = a copy's `fromAddress` when null. No copies
→ unchanged.

**Retry** (`POST /api/emails/{id}/retry`): allowed when the email is `OUTBOUND`, status `FAILED`, `NOT_SENT` or
`PARTIAL`, and it has a copy `FAILED` or `NOT_SENT` — or it is an older email without copies (status `FAILED`/`NOT_SENT`),
whose `TO` recipients with an address then get `delivery_status` `QUEUED`. Those copies → `QUEUED`, error cleared;
email → `QUEUED`, `handed_off_at` null, `attempts` 0, `error` and `next_attempt_at` null; dispatched at once
(`retry: true`). `BOUNCED` copies are not retried. Otherwise 400 `"Only failed or unsent email can be retried"`.
`canRetry` in `EmailDto` follows the same rule plus today's permission rules.

### 5.5 Webhook receiver (`MailServiceEventsController`, `MailServiceEventHandler`)

`POST /api/mail-service/events` — `permitAll` in `SecurityConfig` (no JWT), exists only with transport
`mail-service` (404 otherwise). Missing headers or a timestamp more than 300 s away from now → 401
`{"message":"Invalid signature"}` **before the body is read**; a `Content-Length` above
`app.mail.service.webhook-max-bytes` (32 MiB, far above the service's largest batch) → 413
`{"message":"The events are too large"}`, also unread. The body is read by at most 2 calls at once (another waits up
to 2 s, then 503 `{"message":"Busy; send the events again"}`) and never past the limit (longer → 413), so a caller
without the secret cannot fill the heap. (A real batch refused as too large would be sent again for ever: lower the
service's `mail.webhook.batch-size` or raise the limit.) Then
`WebhookSignature.verify(secret, timestampHeader, signatureHeader, body)` (the secret trimmed, as the service trims
it before signing): a wrong signature (constant-time compare) → 401 `{"message":"Invalid signature"}`. Then each
event in order, **each in its own transaction**:

- `message.status`: parse `gi-{emailId}-{recipientId}` (anything else, or a recipient not on that email → ignored,
  debug log). Lock the email row; if `seq > delivery_seq` → set `delivery_status`, `delivery_error` (= `error`),
  `delivery_seq`, `sent_at`, `delivered_at`, `delivered_confirmed`, `mail_read_at` (= `readAt`), `bounced_at`, the
  copy's provider ids and `rfc_message_id`; roll-up. `handed_off_at` = now (and `next_attempt_at` null) when null
  **and the copy was `QUEUED` here**: a report on a copy the app still has queued proves the service has the hand-off
  it waited for (its answer was lost). A report on a copy handed over before — the one that went out, while a retry
  of another waits — says nothing about that hand-off, which the dispatcher keeps; and an email whose hand-off is
  under way (`SENDING`, `handed_off_at` null) stays `SENDING` where the roll-up would say `QUEUED`, so the
  dispatcher's answer or failure still settles it.
- `message.received`: `EmailInboundService.handle(IncomingMail, InboundHint)`. Linking: the email of
  `repliedToExternalId` → its record (else today's thread / In-Reply-To / References lookups, now also over the
  copies' ids; else ignored — no customer-address rule). Recipients: To/Cc entries equal to `mailboxAddress`
  (also `local+tag@`) → the mailbox owner user (source `MAILBOX`); the owner is added as a `TO` recipient
  (`MAILBOX`) even when only in Bcc, so it reaches their Inbox (E8). Other addresses, and the sender: a user by
  their email in Users, else by the Gmail address they connected (`gmail_connections.gmail_address`, case and a
  `+tag` ignored; a `CONNECTED` one first, then the latest) — staff replying from their own Gmail (M4 lets it
  differ from Users) are staff, internal and masked to customers —, else a customer, else a stranger. Idempotent on
  `providerMessageId` as today.
- `connection.status`: upsert `gmail_connections` for `ownerRef` (numeric; else ignored). When the status becomes
  `NEEDS_RECONNECT` (from anything else) → `NotificationService.notify(userId, "GMAIL_RECONNECT", "Reconnect your Gmail", "{statusReason}", "/me/gmail")`.
- Unknown types → ignored.

A `TransientDataAccessException` — or any sign the database cannot be used: `DataAccessResourceFailureException`, a
transaction exception, `SQLTransientException`, `SQLRecoverableException`, `SQLNonTransientConnectionException`
anywhere in the cause chain (a failed rollback on a broken connection hides the original error) — stops the batch
with 503 (the service sends it again; applying is idempotent). Any other exception on one event is logged (WARN with
stack) and that event is skipped. A signed body that is not JSON → 400 `{"message":"The events cannot be read"}`.
Response 200 `{"processed": n}`.

### 5.6 Gmail connection endpoints (`GmailConnectionController`, `GmailConnectionService`, `GmailDisconnects`)

`GET`/`PUT /api/me/gmail`: internal users with `EMAIL_SEND` only (customer logins and others → 403).

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/me/gmail` | `GmailConnectionDto` (below). Asks the service (`MailConnections.connection`) and refreshes `gmail_connections`; when the service cannot be reached, answers from `gmail_connections` with `serviceError` set. |
| PUT | `/api/me/gmail` | Body `{"clientId","clientSecret","refreshToken"}`, trimmed; blank → 400 field errors (`"Enter the client ID"`, `"Enter the client secret"`, `"Enter the refresh token"`); longer than 300 / 300 / 2000 → `"The client ID is too long"`, `"The client secret is too long"`, `"The refresh token is too long"` (the service's own limits and words, checked here so they land under the field). `MailConnections.connect(userId, fullName or username, …)`; `MailConnectException` → that status with its message (`ApiError`). Success → mirror updated, 200 `GmailConnectionDto`. |
| DELETE | `/api/me/gmail` | Any internal user, also one without `EMAIL_SEND` (so someone who lost it can take their mailbox back); customer logins 403. `disconnect`; mirror → `DISCONNECTED`; 204. |
| GET | `/api/users/{id}/gmail` | `USER_VIEW`; the mirror only: `{"status","gmailAddress","reason","updatedAt"}` (404 when the user does not exist). |
| DELETE | `/api/users/{id}/gmail` | `USER_MANAGE`; someone else's connection: `MailConnections.disconnect(id)` (the service revokes it at Google and wipes the secrets), mirror → `DISCONNECTED`; 204. 404 when neither the user nor a mirror row exists; `MailConnectException` → its status and message. |

**Someone who may no longer send loses their connection** (`GmailDisconnects`). The service would otherwise keep
reading their mailbox, and a retry of one of their emails would still go out from their Gmail. "May connect" =
active, internal, and the role has `EMAIL_SEND`. The connection is removed when a user is hard-deleted (internal
users), deactivated (`PUT /api/users/{id}` with `active: false`, a delete that deactivates instead, bulk
`DEACTIVATE`), moved to a role without `EMAIL_SEND` (`PUT /api/users/{id}`), or when a role loses `EMAIL_SEND`
(`PUT /api/roles/{id}`: every internal holder). The removal is first marked (`disconnect_requested_at`; a mirror row
is created when missing, except with transport `none` for a user the app has no row for; in bulk, in each user's
own transaction), then `MailConnections.disconnect` is called after the change, on a background thread (inline when
`app.mail.dispatch.async` is false). Success → mirror `DISCONNECTED`, mark cleared (the row deleted when the user no
longer exists). `MailConnectException` → logged; the mark stays and the email sweeper (`EmailSweepScheduler`, every
minute, 50 per run, oldest first, stopping at the first failure) asks again. Before each call the user is checked
again: one who may connect by then (reactivated, given `EMAIL_SEND` back) keeps their connection and the mark is
cleared. `forget` (the service has no connection) also clears it.

```jsonc
// GmailConnectionDto
{"configured": true,                      // false with transport none
 "status": "CONNECTED",                   // CONNECTED | NEEDS_RECONNECT | NOT_CONNECTED
 "gmailAddress": "jane@gmail.com", "clientId": "…apps.googleusercontent.com",
 "reason": null, "connectedAt": "…", "lastSyncedAt": "…", "lastSyncError": null,
 "serviceError": null}                    // "Could not reach the mail service: …" when answered from the copy
```

### 5.7 Changes to the email API (`email.md` §6)

- `Participant` gains `"delivery": RecipientDelivery | null` — null for masked people (E13), received email, and
  recipients of emails sent before this change that have no in-app read either:

  ```jsonc
  {"status": "DELIVERED",                 // RecipientDeliveryStatus, or null (no copy: no address / older email)
   "error": null,                         // customer viewers: "Could not be delivered" in place of the provider's text,
                                          // except the app's own not-configured / not-connected messages
   "sentAt": "…", "deliveredAt": "…", "deliveredConfirmed": false,
   "readAt": null,                        // read in Gmail
   "bouncedAt": null,
   "readInAppAt": "…"}                    // the recipient read it in the app's Inbox (TO recipients with a user id)
  ```
- `EmailDto.status` and `InboxItemDto.status` may be `PARTIAL`; `TableSchemas.INBOX` `status` enum gains it.
- For a **customer viewer**, `EmailDto.status`, `error` and `sentAt`, and `InboxItemDto.status`, are the roll-up
  (§5.4) over the copies they may see — those of recipients who are not staff — with `error` passed through the
  customer masking above; with no such copy (a customer login's email to a role, or nobody to send to), the email's
  own. The roll-up over every copy would say "Partly sent" / "Could not be delivered" when only a staff member's
  copy failed, which is how staff's copies fared (E13). Sorting and filtering the Inbox by `status` still use the
  email's own status.
- `GET /api/emails/context`: `delivery` becomes `{"configured": bool}`; every person object (`sender.self`, each
  role's `people` and `sender`) and each `/api/emails/people` result gains `"gmail": "CONNECTED" | "NEEDS_RECONNECT" | "NOT_CONNECTED"`
  from `gmail_connections` (null when masked; `NOT_CONNECTED` for customer logins).
- `POST /api/emails/preview` gains `"warnings": [str]` (they do not block Send):
  - not configured → `"Email delivery is not configured, so this email will be saved in the app but not sent."`
  - the resolved sender is a customer login → `"Email from a customer login is saved in the app and is not sent through Gmail."`
  - sender `NOT_CONNECTED` → `"{name} has not connected Gmail, so this email will be saved in the app but not sent."`
  - sender `NEEDS_RECONNECT` → `"{name}'s Gmail connection needs to be renewed, so this email will be saved in the app but not sent."`
- `POST /api/emails` returns right after the hand-off: status is usually `QUEUED` (or `NOT_SENT`).
- `GET /api/emails/delivery` → `{"configured": bool, "gmail": {"status","gmailAddress","reason","lastSyncedAt","lastSyncError"} | null}`
  — the caller's own connection from the mirror (null for customer logins).
- `POST /api/emails/sync` → the caller's own mailbox (`MailConnections.syncNow`), same response shape as today;
  not configured → `enabled: false`, `"Email delivery is not configured (mail service)"`; not connected →
  `enabled: false`, `"Gmail is not connected"`.

### 5.8 Backend tests

A test `MailTransport`/`MailConnections` bean (`@Primary`, in test sources) records submissions and returns scripted
states. Tests: roll-up table; dispatch (not configured, no address, success, transient/permanent hand-off failure,
bulk); retry rules (copies, older emails, `BOUNCED` not retried, `PARTIAL`); webhook (signature, stale timestamp,
seq ordering, unknown ids, `message.received` linking and Inbox, `connection.status` + notification, 503 on a
transient DB error, a report on another copy while a retry waits or is being handed over, a body over the limit);
`/api/me/gmail` (validation, pass-through errors, 403 for customer logins, mirror fallback, a first connect racing
its `connection.status` report); `/api/users/{id}/gmail` (read and admin disconnect); removal of the connections of
users deleted, deactivated or left without `EMAIL_SEND`, with the sweeper's retry; the stale-`SENDING` sweep of a
retried `PARTIAL` email; a reply from a staff member's connected Gmail; Inbox read against a concurrent copy report;
context `gmail` fields and preview warnings; Participant `delivery` and masking, and the customer's own roll-up. Remove the
Gmail module tests (they move to the mail service). Keep the rest of the suite green.

## 6. Frontend changes (`frontend/`)

| File | Change |
|---|---|
| `lib/features/email/gmail_connection_screen.dart` (new) | `GmailConnectionScreen` at `/me/gmail`, title "Gmail connection". A status card: Connected ("Connected as jane@gmail.com since {date}", last check / last error), Needs renewing (the reason, in the error colour), Not connected. A form with **Client ID**, **Client secret** and **Refresh token** (the last two obscured with a show/hide toggle; all required, "Enter the …"), a **Connect** button ("Reconnect" when a connection exists; the client ID is pre-filled), and **Disconnect** (with a confirm dialog) when a connection exists (`CONNECTED` or `NEEDS_RECONNECT`). Field errors from the server show under their field. Server errors show under the form. An expandable "How to get these" with the steps of §8 and the note that tokens from a Google app in Testing stop working after 7 days. When `configured` is false: a notice and the form disabled. |
| `lib/core/router.dart` | route `/me/gmail`. |
| `lib/shared/widgets/app_shell.dart` | account menu item "Gmail connection" (Icons.mail_lock_outlined) for internal users with EMAIL_SEND, above "Change password". |
| `email_models.dart` | `RecipientDelivery` (fields of §5.7, `fromJson` tolerant of missing keys), `EmailParticipant.delivery`, `EmailStatus`/labels gain `PARTIAL` ("Partly sent"), `EmailPreview.warnings`, `EmailDelivery` (`configured`, `gmail`), `GmailConnection` model, `gmail` on person options; `emailOutcomeMessage`: `QUEUED` → "Email queued for sending", `NOT_SENT` → "Email saved — not sent: {error}", `PARTIAL` → "Email partly sent: {error}"; `emailProgress`: `PARTIAL` → "written {occurredAt}, partly delivered". |
| `email_providers.dart` | `myGmailProvider` (`GET /api/me/gmail`), `userGmailProvider(id)`; invalidated after connect/disconnect. `canConnectGmailProvider`: signed in, not a customer login, EMAIL_SEND — used by the account menu item, `GmailConnectionScreen` and the Inbox banner alike. |
| `send_email_dialog.dart` | Shows the preview's `warnings` (amber, above Send; they do not disable it). The old "Gmail is not connected…" notice becomes "Email delivery is not configured. The email will be saved in the app but not sent." when `delivery.configured` is false. When From is "Me" and my `gmail` is not `CONNECTED`, a line "Connect your Gmail to send email" with a link to `/me/gmail` (also in bulk and picker modes). A From role or person whose `gmail` is not `CONNECTED` shows " · Gmail not connected" after the name. Neither the line nor the label shows when `delivery.configured` is false (the notice covers it), and the line never shows for customer logins. The single and picker forms return an `EmailComposeOutcome` (`sent`, `closed`, `leftForGmail`); Connect closes the form with `leftForGmail`, so a screen that would go back to its list after a save (new invoice, dispute decision) stays on the Gmail page. |
| `email_tab.dart` (`EmailCard`) | Each To recipient shows its delivery after the name: "Queued", "Sending", "Sent {time}", "Delivered {time}" / "Delivered {time} (estimated)" with tooltip "No bounce came back; Gmail does not confirm delivery", "Read {time}", "Bounced: {error}", "Failed: {error}", "Not sent: {error}", then " · read in app {time}" when `readInAppAt`; colours: success for Delivered/Read, error for Bounced/Failed/Not sent. Status chip for `PARTIAL` ("Partly sent", warning colour). The Email tab refreshes itself every 5 s while an email on the page is `QUEUED`/`SENDING` or a copy is `QUEUED`/`SENDING`, and every 30 s while a copy is `SENT`/`DELIVERED` and was sent in the last 24 h; it stops otherwise and on dispose. A refresh icon button in the tab header. The Inbox reader refreshes the same way. |
| `inbox_screen.dart` | The banner, from `/api/emails/delivery`: not configured → "Email delivery is not configured"; my Gmail `NOT_CONNECTED` → "Connect your Gmail to send email and receive replies" + "Connect" (to `/me/gmail`); `NEEDS_RECONNECT` → "Your Gmail connection needs to be renewed" + "Reconnect"; `lastSyncError` → "The last Gmail check failed: {error}". The Gmail messages show only to internal users with EMAIL_SEND (`canConnectGmailProvider`, the rule `/api/me/gmail` applies); everyone else sees only the not-configured notice. |
| `lib/features/users/user_detail_screen.dart` | For an internal user, a "Gmail" line: "Connected as …" / "Needs renewing" / "Not connected" (from `/api/users/{id}/gmail`). |
| tests | widget tests for the new screen (states, validation, error, disconnect confirm), recipient delivery lines, `PARTIAL`, preview warnings, the connect link, the banner, polling start/stop. The existing suite stays green; `flutter analyze` clean. |

## 7. Webhook and queue guarantees (summary)

- A copy is sent at most once per attempt (atomic claim), and an attempt that may have gone out is checked in the
  sender's Sent mail before another (`delivery_uncertain`).
- The backend may hand the same copies over any number of times; only new ones or retried failures are sent.
- Events are stored with the change and delivered in order until the backend says 2xx; the backend ignores any
  `seq` it has already passed.
- Restarts: RabbitMQ keeps queued messages (durable, persistent); the service's sweeper publishes anything whose
  message was lost; the backend's sweeper hands over anything not yet accepted.

## 8. Getting the three values (shown in the app and the README)

1. In Google Cloud Console, create a project, then **APIs & Services → Library → Gmail API → Enable**.
2. **Google Auth Platform / OAuth consent screen**: user type **External**, app name and your email; under
   **Audience → Test users** add your Gmail address.
3. **Credentials → Create credentials → OAuth client ID**, type **Web application**, authorized redirect URI
   `https://developers.google.com/oauthplayground`. Copy the **Client ID** and **Client secret**.
4. Open `https://developers.google.com/oauthplayground`, click the gear icon, tick **Use your own OAuth
   credentials** and paste the client ID and secret.
5. In Step 1 enter `https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly`,
   click **Authorize APIs**, sign in with your Gmail and allow access (on "Google hasn't verified this app" choose
   **Continue**).
6. In Step 2 click **Exchange authorization code for tokens** and copy the **Refresh token**.
7. Paste the three into **Gmail connection** in the app and click **Connect**.

While the Google app is in **Testing**, Google expires the refresh token after **7 days**; repeat steps 4–7 and click
**Reconnect**. Personal Gmail sends at most about 500 messages a day, and each copy counts.

## 9. Known limits

- Customer "read" is not tracked (no public address for an open-tracking pixel). Delivery to customers is an estimate
  (no bounce within 15 minutes); a bounce that comes later still turns it Bounced.
- Internal recipients' Gmail delivery and read are exact only when the address they were sent to is the Gmail address
  they connected (the app sends to the user's email in Users).
- Replies are found by Gmail thread only; a reply Gmail does not thread with the original is not received.
- A customer login's email is saved in the app but not sent (customer logins do not connect Gmail).
- Personal Gmail tokens from a Google app in Testing last 7 days (§8).
- `MailboxThrottle` and the access-token cache live in memory: with more than one mail-service instance the 500 ms
  per-mailbox limit is not shared (Gmail's 429s are still retried).
- The backend's copy of a connection (`gmail_connections`) refreshes on connect, on `GET /api/me/gmail`, on "sync now"
  and on `connection.status` events; `lastSyncedAt` shown from it can be up to the last of those behind.
