# Gene Invoice

Spring Boot backend + Flutter frontend (web, iOS, Android) for an invoice / billing application,
with a separate mail service (`mail-service/`) that sends and tracks email through each user's Gmail.

## Stack

| Layer       | Technology                                            |
|-------------|--------------------------------------------------------|
| Backend     | Spring Boot 3.3 (Java 17), Spring Security + JWT, JPA  |
| Database    | PostgreSQL 16 (H2 profile available for quick demos)   |
| Frontend    | Flutter 3.x + Riverpod + go_router + dio               |
| Auth        | JWT bearer tokens with role + privilege model          |

## Domain model

- **Privilege** — atomic permission (`CUSTOMER_VIEW`, `INVOICE_MANAGE`, …)
- **Role** — group of privileges (seeded: `ADMIN`, `CASHIER`, `VIEWER`)
- **User** — has exactly one `Role`; JWT carries username, role + privileges resolved on each request
- **Customer** — billing party with `creditBalance`
- **Product** — sellable item with `price`
- **Invoice** — many `InvoiceItem`s, derived `status` (`UNPAID` / `PARTIALLY_PAID` / `FULLY_PAID` / `CANCELLED`), and a `dueDate` from the customer's payment terms
- **Payment** — links to one or more invoices (oldest first); overpayment is added to the customer's credit balance and consumed by the next invoice automatically
- **Document** — a file attached to a customer, invoice or payment; the bytes live outside the database and the row is soft-deleted

## Quick start

### 1. Database

```bash
docker compose up -d postgres
```

Or, for a zero-setup demo, run the backend with the `h2` profile (see below).

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run           # default profile = dev (PostgreSQL)
# Or with in-memory H2:
SPRING_PROFILES_ACTIVE=h2 mvn spring-boot:run
```

The backend listens on `http://localhost:8080`.

On first start the data seeder creates:

| Username | Password   | Role    |
|----------|------------|---------|
| `admin`  | `admin123` | ADMIN   |
| `cashier`| `cashier123` | CASHIER |

> The default JWT secret is in `application.yml` — change `JWT_SECRET` for any non-local environment.

### 3. Frontend (Flutter)

Install Flutter SDK: <https://docs.flutter.dev/get-started/install>.

```bash
cd frontend
flutter pub get

# Web
flutter run -d chrome

# Android (emulator or device): backend at 10.0.2.2:8080 for emulator
flutter run -d <android-device> --dart-define=API_BASE_URL=http://10.0.2.2:8080

# iOS simulator
flutter run -d ios
```

Override the API base URL at build time:

```bash
flutter run --dart-define=API_BASE_URL=http://192.168.1.20:8080
```

## Email (Gmail)

Every record — customer, invoice, product, payment, promise, dispute, user and role — can send and
receive email: from its details page, its list page, a row action, or a bulk action that sends a
separate email for each row. Each record has an Email tab, and each user has an Inbox.

Every email is saved in the app before anything else happens. Sending it, and reading the replies,
is the job of a separate **mail service** (`mail-service/`, Spring Boot + RabbitMQ; how to run it is in
[mail-service/README.md](mail-service/README.md), the design in
[docs/implementation/mail-service.md](docs/implementation/mail-service.md)):

- Every internal user connects **their own Gmail** under *Gmail connection* in the account menu, with
  three values from their own Google OAuth client: client ID, client secret and refresh token (the
  screen and the mail service's README say how to make them). Emails they send go out from that Gmail.
- The service queues **one copy per To recipient**, so each person is tracked on their own, and
  reports each copy back to the backend through a signed webhook: queued, sent, delivered, read,
  bounced, failed or not sent. The Email tab shows it beside every To recipient.
- Replies in a thread the app started land on the record, in the Inbox of whoever sent the email they
  answer. Nothing else in anyone's mailbox is read.

**Without the mail service** (`MAIL_TRANSPORT=none`, the default) emails are still saved and shown in
the Email tab and in recipients' Inboxes, with the status `NOT_SENT` and the reason "Email delivery is
not configured (mail service)"; nothing is sent or received. The compose dialog says so before you send.

**With the mail service:** start it (its README), pointing its `MAIL_WEBHOOK_URL` at this backend's
`/api/mail-service/events` (e.g. `http://localhost:8080/api/mail-service/events`), then set these on
the backend and restart it. A missing value stops startup with a message naming it.

| Variable | Default | Meaning |
|---|---|---|
| `MAIL_TRANSPORT` | `none` | `mail-service` to send and receive through the mail service |
| `MAIL_SERVICE_URL` | `http://localhost:8091` | Where the mail service listens |
| `MAIL_SERVICE_API_KEY` | — | Sent with every call; the mail service's `MAIL_API_KEY` |
| `MAIL_SERVICE_WEBHOOK_SECRET` | — | Checks the webhook's signature; the mail service's `MAIL_WEBHOOK_SECRET` |

Good to know:

- A sender who has not connected Gmail, or whose connection needs renewing, still gets their email
  saved; its copies are *Not sent* with the reason, and **Retry** sends them once they have connected.
  When Google stops accepting a connection, its owner gets a notification to reconnect. A Google app
  left in *Testing* issues refresh tokens that stop working after 7 days.
- A customer login's email is saved in the app but not sent (customer logins do not connect Gmail).
- *Delivered* is exact only when the recipient's own Gmail is connected to the app; otherwise it means
  no bounce came back within 15 minutes. *Read* is known only for recipients whose Gmail is connected
  (there is no tracking pixel); in-app reads show as "read in app".
- `GET /api/emails/delivery` says whether delivery is set up and how the caller's Gmail connection
  stands (last read, last error); `POST /api/emails/sync` reads the caller's mailbox right away.

## Due dates and ageing

Every invoice has a **due date**. It comes from the customer's payment terms — `Due on receipt`,
`Net 15/30/45/60/90` — or from the system default when the customer has none, and anyone with
`INVOICE_MANAGE` can override it on the invoice, which sets its terms to `Custom` and is audited.
Changing a customer's terms never moves an existing invoice's date.

An invoice is **overdue** when its due date has gone, it still owes something, and it was not
cancelled. That is worked out from the clock on every read, never stored, so an invoice falls
overdue at the start of the day after it was due without anything having to write to it. The
invoice list has a Due date column, an "Overdue only" filter and *Overdue* tiles; the dashboard's
ageing chart bands outstanding money by **days past due** (`Not yet due`, `1–30`, `31–60`, `61–90`,
`Over 90`), and each bar opens the invoice list at exactly the invoices behind it.

On first start against an existing database, `InvoiceSchemaUpgrade` gives every invoice that has no
due date one — the invoice date plus the default term — logs how many it filled, writes a single
audit entry, and then makes the column `not null`. It touches nothing else, and a later start finds
nothing to do.

| Variable | Default | Meaning |
|---|---|---|
| `INVOICE_DEFAULT_TERM` | `NET_30` | Terms for a customer that has none, and for the backfill |
| `INVOICE_DUE_DATE_HORIZON_DAYS` | `365` | Past this the invoice form asks whether the date is right; the API accepts it either way |

## Documents

Customers, invoices and payments each have a **Documents** tab: PDF, PNG, JPEG, Word or Excel, up to
10 MB. The type is read from the file's own first bytes rather than from what the browser claims, and
the uploaded name is kept for display only — never used as a path. A document is visible to whoever
can see the record it hangs off, so a POC sees only their book's and a customer login only its own,
and only those marked *Shared with customer*. Customer logins may upload on their own records; their
uploads are shared automatically and they cannot edit or delete. Removing a document is a soft delete:
the row stays for the audit trail and the file stops coming down. Every upload, edit and delete shows
in the record's History tab.

`DOCUMENT_VIEW` and `DOCUMENT_MANAGE` are seeded into the built-in roles on upgrade. Taking
`DOCUMENT_MANAGE` away from the CUSTOMER role on the Roles screen turns customer uploads off for
good: unlike the rest of a built-in role, it is not put back by the next restart.

| Variable | Default | Meaning |
|---|---|---|
| `DOCUMENT_STORAGE` | `local` | `local`, or `none` to run without storage (uploads answer 503, everything else works) |
| `DOCUMENT_ROOT` | `./data/documents` | Where the bytes live; created at startup when it is not there |
| `DOCUMENT_MAX_BYTES` | `10485760` | Largest file kept, 10 MB; the upload container's own limit reads this too |
| `DOCUMENT_MAX_REQUEST_BYTES` | `12582912` | Largest whole upload form: the file, plus room for its fields |

## API overview

All `/api/**` endpoints (except `/api/auth/**` and the mail service's webhook, which is signed
instead) require `Authorization: Bearer <jwt>`.

| Method | Path                                | Required privilege         |
|--------|-------------------------------------|----------------------------|
| POST   | `/api/auth/login`                   | —                          |
| GET    | `/api/auth/me`                      | (authenticated)            |
| GET    | `/api/users` and `GET /{id}`        | `USER_VIEW`                |
| POST/PUT/DELETE | `/api/users`               | `USER_MANAGE`              |
| GET    | `/api/roles`, `/api/privileges`     | `ROLE_VIEW`                |
| POST/PUT/DELETE | `/api/roles`               | `ROLE_MANAGE`              |
| GET    | `/api/customers`                    | `CUSTOMER_VIEW`            |
| POST/PUT/DELETE | `/api/customers`           | `CUSTOMER_MANAGE`          |
| GET    | `/api/products`                     | `PRODUCT_VIEW`             |
| POST/PUT/DELETE | `/api/products`            | `PRODUCT_MANAGE`           |
| GET    | `/api/invoices`                     | `INVOICE_VIEW`             |
| POST   | `/api/invoices`                     | `INVOICE_MANAGE`           |
| GET    | `/api/invoices/due-date-preview?customerId=&invoiceDate=` | `INVOICE_MANAGE` |
| POST   | `/api/invoices/{id}/cancel`         | `INVOICE_MANAGE`           |
| GET    | `/api/payments`                     | `PAYMENT_VIEW`             |
| POST   | `/api/payments`                     | `PAYMENT_MANAGE`           |
| GET    | `/api/payments/credits/{customerId}`| `PAYMENT_VIEW`             |
| POST   | `/api/documents` (multipart)        | `DOCUMENT_MANAGE` + the record's manage privilege |
| GET    | `/api/documents?entityType=&entityId=`, `/api/documents/count` | `DOCUMENT_VIEW` + the record's view privilege |
| GET    | `/api/documents/{id}/download`      | `DOCUMENT_VIEW` + the record's view privilege |
| PATCH  | `/api/documents/{id}`               | `DOCUMENT_MANAGE` + the record's manage privilege |
| DELETE | `/api/documents/{id}`               | `DOCUMENT_MANAGE` + record manage, or the uploader |
| GET    | `/api/emails/context`, `/api/emails/people` | `EMAIL_SEND` (people: staff only) |
| POST   | `/api/emails`, `/api/emails/preview`, `/api/emails/bulk`, `/api/emails/{id}/retry` | `EMAIL_SEND` |
| GET    | `/api/emails?entityType=&entityId=`, `/api/emails/{id}` | `EMAIL_VIEW` + the record's view privilege |
| GET    | `/api/emails/delivery`              | `EMAIL_VIEW`               |
| POST   | `/api/emails/sync`                  | `EMAIL_VIEW` (staff only)  |
| GET/PUT | `/api/me/gmail`                    | `EMAIL_SEND` (staff only)  |
| DELETE | `/api/me/gmail`                     | any staff user             |
| GET    | `/api/users/{id}/gmail`             | `USER_VIEW`                |
| DELETE | `/api/users/{id}/gmail`             | `USER_MANAGE`              |
| POST   | `/api/mail-service/events`          | — (HMAC-signed by the mail service) |
| GET    | `/api/inbox`, `/api/inbox/unread-count` | `EMAIL_VIEW`           |
| POST   | `/api/inbox/{id}/read`, `/{id}/unread`, `/mark-all-read`, `/bulk` | `EMAIL_VIEW` |

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
```

Response:

```json
{
  "token": "eyJhbGciOi...",
  "expiresInMs": 86400000,
  "user": {
    "id": 1, "username": "admin", "fullName": "System Administrator",
    "role": "ADMIN",
    "privileges": ["USER_VIEW","USER_MANAGE", "..."]
  }
}
```

### Create invoice

```bash
TOKEN=...

curl -X POST http://localhost:8080/api/invoices \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": 1,
    "items": [
      {"productId": 1, "quantity": 2},
      {"productId": 2, "quantity": 1, "unitPrice": 9.99}
    ],
    "notes": "Walk-in"
  }'
```

### Record a payment

```bash
curl -X POST http://localhost:8080/api/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"customerId": 1, "amount": 50.00, "method": "Cash"}'
```

If `invoiceIds` is omitted, the payment is applied to that customer's oldest outstanding invoices first; any leftover is added to `customer.creditBalance` and consumed when their next invoice is created.

## Project layout

```
gene-invoice/
├── backend/                  # Spring Boot project (Maven)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/geneinvoice/
│       │   ├── auth/         # JWT, AuthController, UserDetails
│       │   ├── common/       # Exceptions, error response, advice
│       │   ├── config/       # SecurityConfig, DataSeeder
│       │   ├── customer/     # Customer + repo + controller
│       │   ├── invoice/      # Invoice, InvoiceItem, service, controller
│       │   ├── payment/      # Payment, service, controller
│       │   ├── privilege/    # Privilege + canonical privilege names
│       │   ├── product/      # Product + repo + controller
│       │   ├── role/         # Role + repo + controller
│       │   └── user/         # User + repo + controller
│       └── resources/application.yml
├── frontend/                 # Flutter project
│   ├── pubspec.yaml
│   └── lib/
│       ├── core/             # api client, router, theme, storage
│       ├── features/         # auth, dashboard, customers, products, invoices, payments, users
│       ├── shared/           # models + reusable widgets
│       ├── app.dart
│       └── main.dart
├── docker-compose.yml        # Postgres for local dev
└── README.md
```

## Next steps / ideas

- PDF invoice export (e.g. iText / openhtmltopdf)
- Invoice edit / line-level discounts / tax
- Refund flow (reverse `paidAmount`)
- Audit log
- Multi-tenant / company branding
- CI: `mvn verify` + `flutter analyze` + `flutter test`
