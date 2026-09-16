# Implementation notes — in-app email

Companion to [`docs/requirements/email.md`](../requirements/email.md). It records the design, the
answers taken to what the requirements leave open, and how it was verified.

---

## 1. Decisions the requirements left open

| # | Question | Answer taken |
|---|---|---|
| 1 | Roles have no email address, but "the role's email address is looked up at send time" | **Roles gain an optional `email`** (the role's shared mailbox), edited on the Roles screen and shown in its table. A role without one can still send; the email then reads "COLLECTION_POC (role) — no email address". |
| 2 | "Every email address on the customer" — customers had one | **Customers gain up to 10 other addresses** (`additionalEmails`) beside the main `email`, which the customer's login keeps sharing. Edited on the New customer form and the customer's details page. Duplicates (ignoring case) and a copy of the main address are dropped. A PUT that leaves the list out keeps it; an empty list clears it. |
| 3 | Who may send and read emails | Two new privileges: **`EMAIL_VIEW`** (the Email tab) and **`EMAIL_SEND`** (every Send entry point and the compose form's lookups). Each also needs the view privilege of what the email is about (`CUSTOMER_VIEW` / `INVOICE_VIEW`) and reaches only records in the caller's scope. The **Inbox needs no privilege**: every staff login may read what was sent to it. **Customer logins get nothing** — no tab, no Inbox, no sending — whatever their role holds, checked structurally like POC identity. |
| 4 | Who counts as "everyone in the role" | Its **active internal users**. Deactivated users and customer logins are never recipients. The `CUSTOMER` role cannot be a sender or a recipient. |
| 5 | What "internal user" means in the pickers | Active users that are not customer logins. |
| 6 | A single email that would reach nobody (an empty role, or a customer with no address) | **Refused with 400**, naming why. In a bulk send, a To line that could reach nobody on any row (only empty roles, no customer emails) is one 400 rather than every row skipped. |
| 7 | Picking particular customer addresses in a bulk send | Not offered: rows have different customers. The bulk form has one choice, "Every email address of each customer / each invoice's customer"; the API refuses `customerEmails` there. |
| 8 | "Table row quick action … in a row's action menu" | The tables' row actions are an icon strip, not a menu, so Send email is an envelope icon there beside Open and Raise promise. |
| 9 | What "List page: Send Email option" sends about | The page's **Send email** button opens the form with a Customer (or Invoice) picker as its first field. |
| 10 | Names on sent emails | Everything is **stored as it was at send time**: sender name and address, each recipient's name and address, each role's members. Renaming a user, changing a role's mailbox or its members never rewrites a sent email. "Sent by" is the signed-in user, whoever the From line names. |
| 11 | Which invoice emails a customer's tab shows | All of them, except that a Sales POC held to their own book sees only emails about invoices they can see — the same rule as the invoice list. |
| 12 | Deleting a customer | Its emails go too, out of every Inbox. (A customer with invoices still cannot be deleted, as before.) |
| 13 | Where the From line starts | Pre-filled with the signed-in user; switchable to another user or a role. |

## 2. Privileges and seeding

| Privilege | ADMIN | CASHIER | VIEWER | CUSTOMER | SALES_POC | CUSTOMER_SUCCESS_POC | COLLECTION_POC |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `EMAIL_VIEW` | ✓ | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `EMAIL_SEND` | ✓ | ✓ | — | — | ✓ | ✓ | ✓ |

The POC roles are created once and never reset, so a new privilege used never to reach them on an
existing database. `DataSeeder` now adds **a privilege the database has never had** to the POC roles
seeded with it. No admin can have removed a privilege that did not exist yet, so this upgrades them
without undoing any edit; a privilege an admin later takes away stays taken away.

## 3. Data model

| Table | What it holds |
|---|---|
| `emails` | `customer_id` (always), `invoice_id` (when about an invoice), `from_type` USER/ROLE, `from_user_id` / `from_role_id`, `from_name`, `from_address`, `subject` (≤ 200), `body` (≤ 10,000, may be empty), `sent_by_user_id`, `sent_by_name`, `sent_at` |
| `email_recipients` | The To line in order: `type` USER / ROLE / CUSTOMER_EMAIL, `user_id` / `role_id`, `name`, `address` |
| `email_role_members` | For a ROLE recipient, who was in it at send time (`user_id`, `name`, `address`) |
| `email_deliveries` | One row per staff recipient — unique `(email_id, user_id)` — with that person's `read_at`. This is the Inbox, and why a user named directly and through a role gets the email once. |
| `customer_emails` | A customer's other addresses, ordered |
| `roles.email` | A role's mailbox |

User and role references are plain ids plus the stored names, like notifications, so deleting a user
or role never breaks a sent email.

## 4. API

| Method | Path | Needs | Notes |
|---|---|---|---|
| GET | `/api/emails?customerId=` or `?invoiceId=` `&page&size` | `EMAIL_VIEW` | The Email tab, newest first; size 10/20/50. A customer's includes its invoices' emails, each with `invoiceNumber`. |
| POST | `/api/emails` | `EMAIL_SEND` | `{customerId \| invoiceId, from:{type,id}, to:{userIds, roleIds, customerEmails}, subject, body}` |
| POST | `/api/emails/bulk` | `EMAIL_SEND` | `{targetType: CUSTOMER\|INVOICE, ids \| selectAllMatchingFilter, sort, filters, email:{from, to:{userIds, roleIds, allCustomerEmails}, subject, body}}` → the standard bulk result: `succeeded` are the rows that got an email, `skipped` carries each reason. |
| GET | `/api/emails/staff?q=&limit=` | `EMAIL_SEND` | Internal users for From and To |
| GET | `/api/emails/roles` | `EMAIL_SEND` | Roles with mailbox and current member count |
| GET | `/api/emails/addresses?customerId=` or `?invoiceId=` | `EMAIL_SEND` | Every address of the (invoice's) customer |
| GET | `/api/inbox?page&size` | staff login | Newest first, with the caller's own `read` / `readAt`. No filters (400 if given). |
| GET | `/api/inbox/{emailId}` | staff login | 404 unless the caller received it |
| POST | `/api/inbox/{emailId}/read` | staff login | Idempotent; the first read time stands |
| POST | `/api/inbox/read-all` | staff login | Every unread email of the caller's, on every page → `{updated}` |

## 5. Frontend

- **Entry points** (`EMAIL_SEND`): Send email button on the Customers and Invoices list pages (with a
  target picker), an envelope row action, a Send email bulk action, and a Send email button in the
  header of Customer and Invoice details.
- **Compose form** (`features/email/compose_email_dialog.dart`): From (User | Role), To (Add user,
  Add role, customer address chips — or the "every address" checkbox in bulk), Subject, Body. It
  checks the same rules as the server before sending, and a tap outside does not discard it. A bulk
  send ends in "N emails created, M rows skipped", with each skipped row's reason when there are any.
- **Email tab** (`EMAIL_VIEW`) on both details pages, paged, each email in full.
- **Inbox** directly below Dashboard for staff logins: bold with a dot when unread, an envelope when
  read, "Mark as read" on unread rows, "Mark all as read" above the list, pagination; no filter bar.
  Opening an email (`/inbox/:id`) marks it read and links to its customer or invoice.
- `DataTableScaffold` gained `BulkActionSpec.run` (a bulk action with its own flow, handed the
  selection) and `showFilterBar`.

## 6. Tests and verification

- `mvn test` — 225 tests, all green. New: `EmailSendTest` (14: recipients worked out once, snapshots
  at send time, validation, invoice emails on both tabs, paging, bulk over customers and invoices,
  access for customer logins, viewers and a Sales POC's book, the compose lookups, customer delete),
  `InboxTest` (5), `CustomerAndRoleAddressesTest` (3), and two `DataSeederTest` cases for the upgrade rule.
- `flutter analyze` clean; `flutter test` — 68 tests. New: `compose_email_test.dart` (a typed-but-unadded address, single, role
  sender, bulk with a skipped row), `inbox_and_email_view_test.dart`, and cases in
  `app_shell_test.dart` and `data_table_scaffold_test.dart`.
- Checked by hand in a browser against a local build (1366 px and 400 px): single send from a row, bulk
  send over invoices with a skipped row, both Email tabs, the list page's picker, the Inbox before and
  after opening an email, and what a viewer and a customer login see.
- Checked on **Postgres** (a disposable container): booted the pre-email build first, then this one —
  the POC roles gained the email privileges while an admin's earlier removal stayed; the new tables and
  `roles.email` were created; and an API run of 15 checks passed (null-parameter search, the Sales POC
  scope subquery, bulk over a filter, Inbox paging and read-all, customer delete).
