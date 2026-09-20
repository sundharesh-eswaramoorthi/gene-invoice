# Gene Invoice mail service

Sends Gene Invoice email through **each internal user's own Gmail** and tells the backend what became
of it. The backend saves every email and hands its copies over; this service owns everything Gmail:

- **Connections** — each user connects their own Gmail with three values (client ID, client secret,
  refresh token). They are checked with Google at once; the secret and the token are stored encrypted
  (AES-256-GCM) and never returned or logged.
- **The send queue** — one copy per To recipient, queued on RabbitMQ and sent by 4–8 workers, at most
  one send per mailbox every 500 ms. Passing failures are retried after 1 minute, then 5.
- **Tracking** — per copy: `QUEUED` → `SENDING` → `SENT` → `DELIVERED` → `READ`, or `BOUNCED`, `FAILED`,
  `NOT_SENT`. Bounces are read from the sender's mailbox; delivery and read are confirmed when the
  recipient's own Gmail is connected, otherwise delivery is estimated (no bounce within 15 minutes).
- **Replies** — only in threads the app started; everything else in a mailbox is left alone.
- **The webhook** — every change is an event, saved with the change and POSTed to the backend in order,
  signed with HMAC-SHA256, until the backend accepts it. A mailbox read that starts failing, fails
  differently or recovers is reported too (`connection.status`), so the app can show the last error.

The design and the API are in [docs/implementation/mail-service.md](../docs/implementation/mail-service.md).

```
backend (8086) ──REST + X-Api-Key──► mail-service (8091) ──► Gmail API (each user's token)
   ▲                                   │    ▲
   └── POST /api/mail-service/events ──┘    │ RabbitMQ (5672, UI 15672): mail.send
       (HMAC-signed webhook)                └ Postgres database genemail
```

## Run it

Java 17 and Maven. RabbitMQ from this folder's compose file, the database in the existing Postgres:

```bash
cd mail-service
docker compose up -d                       # RabbitMQ: container geneinvt1-rabbitmq, 5672 and 15672

# Once: the database, in the Postgres the backend uses (localhost:5435)
docker exec -it geneinvt1-main-db psql -U geneinvoice -d postgres -c 'CREATE DATABASE genemail'

export MAIL_API_KEY=$(openssl rand -hex 24)          # the backend's MAIL_SERVICE_API_KEY
export MAIL_SECRETS_KEY=$(openssl rand -base64 32)   # keep it: without it the stored secrets cannot be read
export MAIL_WEBHOOK_URL=http://localhost:8086/api/mail-service/events
export MAIL_WEBHOOK_SECRET=$(openssl rand -hex 24)   # the backend's MAIL_SERVICE_WEBHOOK_SECRET

mvn -B package -DskipTests
java -jar target/gene-mail-service-0.0.1-SNAPSHOT.jar
```

It listens on **8091** (`PORT`). `GET /actuator/health` needs no key; everything under `/api/v1/**`
needs the header `X-Api-Key`. The tables are created on first start (`ddl-auto: update`).

Then start the backend with `MAIL_TRANSPORT=mail-service`, `MAIL_SERVICE_URL=http://localhost:8091`,
`MAIL_SERVICE_API_KEY` and `MAIL_SERVICE_WEBHOOK_SECRET` set to the same values.

### Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8091` | HTTP port |
| `MAIL_API_KEY` | — (required) | The key the backend sends in `X-Api-Key`; at least 16 characters |
| `MAIL_SECRETS_KEY` | — (required) | Base64 of 32 random bytes: the AES key for the stored client secrets and refresh tokens. Changing it makes every connection *needs renewing* |
| `MAIL_WEBHOOK_URL` | empty | The backend's `/api/mail-service/events`. Empty: events wait in the database until it is set |
| `MAIL_WEBHOOK_SECRET` | — | Signs the webhook; required when `MAIL_WEBHOOK_URL` is set, at least 16 characters |
| `MAIL_DB_URL` | `jdbc:postgresql://localhost:5435/genemail` | Postgres |
| `MAIL_DB_USER` / `MAIL_DB_PASSWORD` | `geneinvoice` / `geneinvoice` | |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | |
| `MAIL_SYNC_ENABLED` | `true` | Read mailboxes (bounces, replies, read) |
| `MAIL_SYNC_INTERVAL_MS` | `60000` | How often every connected mailbox is read |
| `GMAIL_API_BASE_URL`, `GOOGLE_TOKEN_URL`, `GOOGLE_TOKENINFO_URL`, `GOOGLE_REVOKE_URL` | Google's | Point them at a stand-in for tests |

A missing or invalid required setting stops startup with a message naming the variable (the value
itself is never printed). The rest (`mail.send.*`, `mail.tracking.*`, `mail.webhook.interval-ms`,
`batch-size`) are in `src/main/resources/application.yml`. `mail.send.retry-delays` (`PT1M, PT5M`) are
also the TTLs of the delay queues: RabbitMQ will not redeclare a queue with other arguments, so after
changing them delete `mail.send.retry.1m` and `mail.send.retry.5m` (the app declares them again).

### RabbitMQ

The app declares its own topology on its first connection:

| Name | Kind | What it does |
|---|---|---|
| `mail.send` | direct exchange + durable queue (key `send`) | Copies waiting for a worker: `{"id": 123}`, persistent |
| `mail.retry` | direct exchange | Routes a retry to the delay queue for its wait |
| `mail.send.retry.1m` / `.5m` | durable queues, TTL 1 / 5 min | Nobody reads them: a message expires back into `mail.send` |
| `mail.dead` → `mail.send.dead` | fanout exchange + durable queue | Messages the worker could not handle at all, for looking into |

The management UI is at <http://localhost:15672> (guest / guest from localhost).

## Getting the three values (each user, once)

1. In [Google Cloud Console](https://console.cloud.google.com/), create a project, then
   **APIs & Services → Library → Gmail API → Enable**.
2. **Google Auth Platform / OAuth consent screen**: user type **External**, an app name and your email;
   under **Audience → Test users** add your Gmail address.
3. **Credentials → Create credentials → OAuth client ID**, type **Web application**, authorized
   redirect URI `https://developers.google.com/oauthplayground`. Copy the **Client ID** and
   **Client secret**.
4. Open <https://developers.google.com/oauthplayground>, click the gear icon, tick **Use your own OAuth
   credentials** and paste the client ID and secret.
5. In Step 1 enter
   `https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly`, click
   **Authorize APIs**, sign in with your Gmail and allow access (on "Google hasn't verified this app"
   choose **Continue**).
6. In Step 2 click **Exchange authorization code for tokens** and copy the **Refresh token**.
7. Paste the three into **Gmail connection** in the app and click **Connect**.

While the Google app is in **Testing**, Google expires the refresh token after **7 days**: repeat steps
4–7 and click **Reconnect**. Reconnecting the same Gmail keeps its place in the mailbox, so bounces, replies
and reads that came in while it needed renewing are still picked up. A personal Gmail sends at most about
500 messages a day, and each copy counts.

The token must be allowed to send (`gmail.send`, `gmail.compose`, `gmail.modify` or
`https://mail.google.com/`) and to read (`gmail.readonly`, `gmail.modify` or `https://mail.google.com/`);
connecting says which is missing.

## What the service promises

- A copy is sent at most once per attempt: a worker claims it atomically before sending, so a queue
  message delivered twice sends once. An attempt that may have gone out (no answer came back) makes the
  next one look for the copy in the sender's Sent mail first.
- The backend may hand the same copies over any number of times (`externalId` is the key); only new
  copies, or failed and not-sent ones handed over with `retry: true`, are sent.
- Events are saved in the same transaction as the change and delivered in order until the backend
  answers 2xx; after a failure the next try waits 1 s, 2 s, 4 s … up to 5 minutes. Each copy's `seq`
  only moves forward, so the backend can ignore an event it has already passed. Delivered events are
  deleted after a week.
- Restarts lose nothing: RabbitMQ keeps queued messages (durable, persistent); the sweeper publishes
  every queued copy whose message was lost and marks a send that died half way as failed (never sent
  again on its own); the backend's sweeper hands over anything the service has not accepted.

## Known limits

- Gmail gives no delivery or read receipts. *Delivered* is exact only when the recipient's own Gmail is
  connected and has the copy; otherwise it means no bounce came back within 15 minutes (a later bounce
  still turns it *Bounced*). *Read* is known only for recipients whose Gmail is connected — there is no
  tracking pixel, since the app has no public address.
- Replies are found by Gmail thread only: a reply Gmail does not thread with the original is not seen.
- A sender's Gmail address is what customers see in From.

## Tests

```bash
mvn -B verify
```

Unit tests, integration tests on H2 with Google replaced by a local HTTP stand-in (`FakeGoogle`) and a
fake webhook receiver, and one Testcontainers test (`RabbitQueueTest`) that sends through a real
RabbitMQ (`rabbitmq:3.13-management`), including a retry through the delay queue. That test is skipped
when Docker is not available.
