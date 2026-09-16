# Gene Invoice

Spring Boot backend + Flutter frontend (web, iOS, Android) for an invoice / billing application.

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
- **Invoice** — many `InvoiceItem`s, derived `status` (`UNPAID` / `PARTIALLY_PAID` / `FULLY_PAID` / `CANCELLED`)
- **Payment** — links to one or more invoices (oldest first); overpayment is added to the customer's credit balance and consumed by the next invoice automatically
- **Email** — kept inside the app, never delivered: about a customer or an invoice, from a user or a role, to users, roles and the customer's addresses; staff read theirs in the Inbox ([design notes](docs/implementation/email.md))

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

## API overview

All `/api/**` endpoints (except `/api/auth/**`) require `Authorization: Bearer <jwt>`.

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
| POST   | `/api/invoices/{id}/cancel`         | `INVOICE_MANAGE`           |
| GET    | `/api/payments`                     | `PAYMENT_VIEW`             |
| POST   | `/api/payments`                     | `PAYMENT_MANAGE`           |
| GET    | `/api/payments/credits/{customerId}`| `PAYMENT_VIEW`             |
| GET    | `/api/emails?customerId=` / `?invoiceId=` | `EMAIL_VIEW`         |
| POST   | `/api/emails`, `/api/emails/bulk`   | `EMAIL_SEND`               |
| GET/POST | `/api/inbox/**`                   | (any staff login)          |

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
