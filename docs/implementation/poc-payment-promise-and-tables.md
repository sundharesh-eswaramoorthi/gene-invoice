# Implementation notes — POC ownership, Payment Promises, and the list/detail framework

Companion to `docs/requirements/poc-payment-promise-and-tables.md`. It records the design the
PRD deliberately left open, the two artefacts the PRD asks the implementing session to produce
(the role × capability matrix, and the documented sortable columns), and the answers taken to the
open questions.

---

## 1. Privileges added

| Privilege | Meaning |
|---|---|
| `POC_VIEW` | May see POC identity fields, columns, filters and dropdowns |
| `POC_ASSIGN` | May change POC assignments on invoices, payments and customers |
| `POC_ASSIGNABLE_SALES` | A user holding a role with this marker is offered as a **Sales POC** |
| `POC_ASSIGNABLE_SUCCESS` | …as a **Customer Success POC** |
| `POC_ASSIGNABLE_COLLECTION` | …as a **Collection POC** |
| `SCOPE_OVERRIDE` | May clear the "my records only" default scope on list pages |
| `PROMISE_VIEW` | May see payment promises |
| `PROMISE_MANAGE` | May create, edit and cancel promises |
| `PROMISE_OVERRIDE` | May pin a promise's status by hand, and clear that override |
| `EXPORT_DATA` | May export the current selection as CSV |

Assignability is a **privilege**, not a role name (PRD §A.1). One person still holds exactly one
role, but a role may carry several assignability markers — so `ADMIN` is offerable as all three,
and an admin can compose a "Success + Collections" role on the existing Roles screen with no code
change.

## 2. Role × capability matrix

`✓` granted, `—` not granted. Seeded by `DataSeeder`.

| Capability (privilege) | ADMIN | CASHIER | VIEWER | CUSTOMER | SALES_POC | CUSTOMER_SUCCESS_POC | COLLECTION_POC |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `CUSTOMER_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `CUSTOMER_MANAGE` | ✓ | ✓ | — | — | — | ✓ | — |
| `PRODUCT_VIEW` | ✓ | ✓ | ✓ | — | ✓ | ✓ | — |
| `PRODUCT_MANAGE` | ✓ | — | — | — | — | — | — |
| `INVOICE_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `INVOICE_MANAGE` | ✓ | ✓ | — | — | ✓ | — | — |
| `PAYMENT_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `PAYMENT_MANAGE` | ✓ | ✓ | — | — | — | — | ✓ |
| `DISPUTE_CREATE` | ✓ | — | — | ✓ | — | — | — |
| `DISPUTE_VIEW` | ✓ | — | — | ✓ | ✓ | ✓ | ✓ |
| `DISPUTE_MANAGE` | ✓ | — | — | — | — | — | — |
| `NOTIFICATION_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `AUDIT_VIEW` | ✓ | — | — | ✓ | ✓ | ✓ | ✓ |
| `USER_VIEW` / `USER_MANAGE` | ✓ | — | — | — | — | — | — |
| `ROLE_VIEW` / `ROLE_MANAGE` | ✓ | — | — | — | — | — | — |
| **`POC_VIEW`** | ✓ | ✓ | ✓ | **—** | ✓ | ✓ | ✓ |
| **`POC_ASSIGN`** | ✓ | ✓ | — | — | ✓ | ✓ | ✓ |
| **`POC_ASSIGNABLE_SALES`** | ✓ | — | — | — | ✓ | — | — |
| **`POC_ASSIGNABLE_SUCCESS`** | ✓ | — | — | — | — | ✓ | — |
| **`POC_ASSIGNABLE_COLLECTION`** | ✓ | — | — | — | — | — | ✓ |
| **`SCOPE_OVERRIDE`** | ✓ | ✓ | ✓ | — | **—** | ✓ | ✓ |
| **`PROMISE_VIEW`** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **`PROMISE_MANAGE`** | ✓ | — | — | — | — | — | ✓ |
| **`PROMISE_OVERRIDE`** | ✓ | — | — | — | — | — | ✓ |
| **`EXPORT_DATA`** | ✓ | ✓ | — | — | ✓ | ✓ | ✓ |

Three entries deserve a word:

- **`CUSTOMER_MANAGE` includes removing a customer.** The customer detail screen offers "Delete
  customer" to holders of it and to nobody else, confirming by name and saying what goes with the
  record — its login, its POC seats and every document on it and on its invoices and payments.
  `DELETE /api/customers/{id}` had existed behind this privilege since the beginning with no
  caller anywhere in the app, so a customer entered by mistake was permanent and went on
  appearing in every list, picker, POC-missing tile and recipient list (CP-03). A customer with
  invoices or payments is refused by the database's own foreign keys; the screen says so in
  words, since the 409 carries only "This change conflicts with existing data".
- **`CUSTOMER` never gets `POC_VIEW`.** A customer-scoped account must see no POC field, column,
  filter or dropdown, and the API must not return POC identity to them (AC-A8). Withholding the
  privilege makes that structural rather than a per-endpoint reminder — and the DTO layer also
  checks `customerId != null` directly, so granting it by accident would still not leak.
- **`SALES_POC` deliberately lacks `SCOPE_OVERRIDE`.** Sales books are usually confidential per
  rep, so a salesperson sees their own invoices and the chip is rendered **locked**. The other two
  POC roles keep the override, so their lists open unfiltered like everyone else's (no chip is
  pre-applied for any role — see §10). An admin can change either by editing the role.

`CASHIER` keeps every privilege it had before this change, and gains only reads
(`POC_VIEW`, `PROMISE_VIEW`), the assignment right it needs to fill in the now-mandatory POC
fields (`POC_ASSIGN`), `SCOPE_OVERRIDE` and `EXPORT_DATA`.

**Seeding.** The four built-in roles are re-synchronised on every boot, so a privilege added in
code reaches them on upgrade. The three POC roles are **created once and never reset** — an admin
who tailors them keeps their edits across restarts (AC-A1). Both paths are idempotent and safe
against a populated database.

## 3. POC data model

| Entity | Column / table | Cardinality |
|---|---|---|
| `Invoice` | `sales_poc_user_id` → `users` | exactly one, required on create |
| `Payment` | `collection_poc_user_id` → `users` | exactly one, required on create |
| `Customer` | `customer_pocs` (customer, user, `poc_type`, `is_primary`) | many of each kind, at most one primary each |

Nullable in storage, enforced in the service layer for new records only (D4). Legacy rows keep a
null POC, render a **"POC missing"** badge and are selectable with the `isEmpty` filter.

`DELETE /api/users/{id}` **deactivates** a user who is named as a POC anywhere instead of deleting
them: the historical assignment stays readable, they drop out of the dropdowns, and records naming
them remain findable (AC-A5). The response says which happened.

Wherever a screen still names a deactivated holder — the POC editor, a detail page, a list column
— the name is followed by **"(inactive)"**. `PocService.activeHolders` / `defaultAssignee` skip
them, so email and the defaults on new records go to the next active holder; an unmarked name
would present somebody as the record's POC whom the app would not in fact write to (CP-07).

## 4. Payment Promise status

Status is a **pure function of current facts**, recomputed on every relevant change, which makes it
idempotent and re-entrant (AC-B6). Fulfilment is split by whether the money arrived **by** the
promised date.

```
live            = referenced invoices excluding CANCELLED
onTime / late   = fulfilment from linked ACTIVE payments, split at end of the promised date (UTC)

if invoice-scoped and live is empty        -> KEPT      # a dispute erased the debt
if the promised date has not passed:
    complete = onTime+late >= amount                    # the promised money has arrived
               or (invoice-scoped ? every live invoice has zero balance
                                  : date reached and the account owes nothing)
    complete -> KEPT ; any fulfilment -> PARTIALLY_KEPT ; else OPEN
else:                                                   # the date has gone
    onTime >= amount                                    -> KEPT
    invoice-scoped, all live settled, nothing arrived late -> KEPT
    general, nothing late, account owes nothing         -> KEPT
    onTime > 0                                          -> PARTIALLY_KEPT
    else                                                -> BROKEN
```

**The promised money arriving keeps the promise whichever side of the date it is read from.** Both
branches test `>= amount` first, so an invoice-scoped promise for part of a larger invoice, paid in
full and on time, is KEPT the moment the money lands rather than sitting at PARTIALLY_KEPT with
"Remaining 0.00" until the date goes by (PPD-02). The extra ways to be complete differ, because
only after the date can the split into on-time and late money be judged.

**Paying late does not un-break a promise.** The customer did break their word; the money is still
recorded in `fulfilledAmount`. AC-B3 and AC-B4 both describe transitions out of an *open* promise,
so this reading is the one the PRD asks for, and it is what a collections team means by "broken".

Re-entry points: recording a payment, changing a payment's amount, voiding a payment, cancelling
an invoice, a dispute-driven invoice edit, creating or editing a promise, and the sweeper.

**The sweeper** (`PromiseSweepScheduler`) runs on startup and every 15 minutes
(`app.promises.sweep-interval-ms`). A promised date has day granularity, so the persisted status is
correct in list results, filters and totals for a user who never opens the record (AC-B5).
`brokenNotifiedAt` makes the "promise broke" notification fire exactly once.

**Overrides** pin a status until cleared; fulfilment keeps being tracked underneath.
`DELETE /api/promises/{id}/override` hands the promise back to automatic tracking.

**A promise covers the invoices the form showed, and only those.** The checklist in
`promise_form_dialog.dart` is the customer's outstanding invoices *plus* every invoice the promise
is already scoped to that they do not include: the invoice the screen was opened on (the callers
hand over the `InvoiceSummary`, not a bare id) and, when editing, one linked earlier and since
paid off or cancelled. Each appears ticked, can be unticked, and counts towards the shortfall
hint — including while the outstanding list is still loading and after a request for it has
failed, since an invoice with no checkbox is one nobody can see or untick. Building the list from
the outstanding invoices alone meant a promise raised from a
fully-paid or cancelled invoice was scoped to it with no checkbox to show for it: invoice-scoped
with nothing owed, the tracker read it as KEPT the moment it was saved, and the brand-new promise
came back already closed with money still shown as remaining (UI-02).

## 5. List API contract

Every list endpoint takes the same inputs and returns the same envelope.

```
GET /api/{entity}?page=0&size=20&sort=total,desc&filter=status:in:UNPAID,PARTIALLY_PAID&filter=…
```

- `size` must be one of **10 / 20 / 50** (default 20); anything else is a 400.
- `sort` is `column,asc|desc`; an `id` tiebreak is always appended so paging is stable (AC-D2).
- `filter` is repeated, each `field:operator:value`. Only the first two colons are separators, so a
  text value may itself contain colons. Values are comma-separated **only** for the multi-value
  operators (`in`, `notIn`, `between`).
  The parameter is read via `HttpServletRequest.getParameterValues` rather than bound as a
  `List<String>`, because Spring's default converter would split a single value on its commas and
  silently mangle `total:between:100,500`.

```jsonc
{
  "content": [ … ],
  "page": 0, "size": 20, "totalElements": 137, "totalPages": 7,
  "sort": "invoiceDate,desc",
  "appliedFilters": ["status:in:UNPAID"],
  "lockedFilters": ["salesPocUserId:eq:12"]   // scope the server pinned on; shown as a locked chip
}
```

Companion endpoints per entity: `GET /api/{entity}/summary` (tiles over the whole filtered set),
`POST /api/{entity}/bulk`, `POST /api/{entity}/export`.
`GET /api/table-schemas/{entity}` publishes the columns the frontend builds its filter UI from,
under that table's own view privilege; `/all` and the entity list return only the tables the
caller may see (AUTH-07).

Filter values reach the database only through criteria parameter binding — never as query text —
and an unknown column, operator, value type or page size is rejected with a 400 (AC-D9).

### 5.1 The same query in the browser's URL (AC-D4)

A list page carries its query in its own URL — `?page=&size=&sort=&f=field:operator:value`, `f`
repeated per chip — and `RouteQuery` (`lib/core/table/route_query.dart`) is the only thing that
writes or reads it.

Both directions treat a value as an opaque component: `Uri.encodeComponent` on the way out (a
space becomes `%20`, a `+` becomes `%2B`) and a component decode on the way back, rather than
`Uri(queryParameters:)` and `Uri.queryParametersAll`, which use the HTML-form convention where a
space is written `+` and a `+` therefore only survives while its escape does. It does not survive:
the address bar shows the escapes decoded, and the decoded form is what people copy, bookmark and
paste. A filter on "+91 5551234" reopened as " 91 5551234" — a different query, no warning, and
different rows (TBL-03). With neither character written literally, the link means the same thing
encoded or decoded. A `+` in a URL is now always the character the user typed.

## 6. Sortable and filterable columns (AC-D3)

`S` sortable, `F` filterable. Operators come from the column's type:
TEXT `contains, eq, neq, isEmpty, isNotEmpty` · ENUM `eq, neq, in, notIn` · BOOLEAN `eq` ·
NUMBER/MONEY `eq, neq, gt, gte, lt, lte, between` · DATE `gte, lte, between, relative` ·
REFERENCE `eq, neq, in, isEmpty, isNotEmpty`.
Date presets: `today, yesterday, last7Days, last30Days, thisMonth, lastMonth, thisYear, past, future`.

| Table | Columns |
|---|---|
| **invoices** (default `invoiceDate,desc`) | `id` SF · `invoiceNumber` SF · `customerId` F(ref) · `customerName` SF · `invoiceDate` SF · `total` SF · `paidAmount` SF · `balance` SF *(computed `total − paid`)* · `status` SF · `notes` F · `salesPocUserId` F(ref, POC) · `salesPocName` SF(POC) · `createdAt` SF |
| **payments** (default `paidAt,desc`) | `id` SF · `customerId` F(ref) · `customerName` SF · `amount` SF · `creditApplied` SF · `method` SF · `notes` F · `paidAt` SF · `status` SF · `collectionPocUserId` F(ref, POC) · `collectionPocName` SF(POC) |
| **customers** (default `name,asc`) | `id` SF · `name` SF · `phone` SF · `email` SF · `address` F · `creditBalance` SF · `outstanding` SF *(subquery over live invoices)* · `successPocUserId` F(seat, POC) · `collectionPocUserId` F(seat, POC) · `createdAt` SF |
| **promises** (default `promisedDate,desc`) | `id` SF · `customerId` F(ref) · `customerName` SF · `amount` SF · `fulfilledAmount` SF · `remainingAmount` SF *(computed, floored at 0, and zero outright once the promise is KEPT or CANCELLED)* · `promisedDate` SF · `status` SF · `statusOverridden` SF · `collectionPocUserId` F(ref, POC) · `collectionPocName` SF(POC) · `notes` F · `invoiceId` F(link table) · `createdAt` SF |
| **products** (default `name,asc`) | `id` SF · `name` SF · `description` F · `price` SF · `active` SF · `createdAt` SF |
| **users** (default `username,asc`) | `id` SF · `username` SF · `email` SF · `fullName` SF · `active` SF · `roleName` SF · `customerId` SF · `createdAt` SF |
| **roles** (default `name,asc`) | `id` SF · `name` SF · `description` F |
| **disputes** (default `createdAt,desc`) | `id` SF · `customerId` SF(ref) · `targetType` SF · `targetId` SF · `status` SF · `reason` F · `createdAt` SF · `resolvedAt` SF |
| **notifications** (default `createdAt,desc`) | `id` SF · `type` SF · `title` SF · `message` F · `read` SF · `createdAt` SF |

A column that renders free text somebody typed — a customer's name or email, a person's name in a
POC column, a product description, a dispute reason, an email subject — is given a
`TableColumnSpec.maxWidth` and ellipsised, with the whole value on hover. Uncapped, the column
takes the width of its longest value: one customer with a legal 120-character name
(`FieldLimits.FULL_NAME`) ran the customers table out to x≈2138 on a 1366px screen, leaving a
checkbox and a wall of letters with every other column off the right-hand edge (UI-01, D-20).

Columns marked **POC** are stripped from the schema and from every payload for a customer-scoped
caller. The `successPocUserId` / `collectionPocUserId` columns on **customers** are to-many seats:
`is` means "has a seat held by", `isEmpty` means "holds no seat of this kind" — the POC-missing
filter of AC-A9. Columns not listed as `S` are rendered visibly unsortable.

## 7. Scoping

Two different mechanisms share the same machinery (`ScopeResolver`):

- **Restriction.** A customer-scoped account is confined to its own rows. No filter, bulk id list
  or "select all matching" parameter can widen it — the predicate is `AND`-ed onto every query,
  including the aggregates behind the tiles (AC-D10, AC-E4).
- **Default book.** A POC without `SCOPE_OVERRIDE` has the same predicate applied and gets it back
  in `lockedFilters`, so the UI shows a locked chip rather than silently hiding rows. A POC *with*
  the override has nothing forced, and **lists open unfiltered for every role** — no chip is
  pre-applied (product decision, 2026-09-15; see §10). They narrow to their own book with the POC
  column filter.

"My book" means: invoices where I am the Sales POC · payments and promises where I am the
Collection POC · customers where I hold a POC seat **or** own one of their invoices.

## 8. Bulk actions

`POST /api/{entity}/bulk` with `{action, ids | selectAllMatchingFilter, sort, filters, params}`.
Ids are always re-resolved through the caller's scope, so ineligible rows are excluded rather than
attempted (AC-D6). Each record runs in its own transaction, and every requested id lands in exactly
one of `succeeded` / `failed` / `skipped` — nothing is dropped silently (AC-D5). A filtered set
larger than 5000 comes back with `truncated: true` and the limit, rather than a silent cap; an
explicit `ids` list longer than the same limit is refused as a field error on `ids` (TBL-08).

`skipped` is for a row that did not qualify — already cancelled, already inactive, holding a
payment that must be refunded first — and for one a concurrent writer reached first; `failed` is
for something going wrong, which the dialog renders as an error. The single-record endpoints keep
answering 400 for the same refusals (TBL-05, TBL-07).

| Entity | Actions |
|---|---|
| invoices | `CANCEL`, `REASSIGN_SALES_POC`, export |
| payments | `REASSIGN_COLLECTION_POC`, export |
| customers | `ADD_POC`, export |
| promises | `CANCEL`, `REASSIGN_COLLECTION_POC`, export |
| products | `ACTIVATE`, `DEACTIVATE`, export |
| users | `ACTIVATE`, `DEACTIVATE`, export |
| notifications | `MARK_READ`, `MARK_UNREAD` |
| roles, disputes | export only |

**Bulk void of payments is deliberately absent.** Voiding moves money and reverses allocations; it
stays a one-at-a-time action through the existing dispute flow. Bulk cancel of invoices is offered
because it already refuses any invoice with a payment against it.

## 9. Answers to the PRD's open questions

| # | Question | Answer taken |
|---|---|---|
| 1 | Customer visibility of promises | **Read-only visible.** `CUSTOMER` holds `PROMISE_VIEW`; promises are scoped to their own account and the Collection POC is stripped from the payload. They cannot propose one. |
| 2 | Detail-screen tabs | **Kept to the specified three** (Disputes / Payment Promise / History). Invoices and Payments for a customer are one filtered click away from the list pages, which are now URL-addressable. |
| 3 | Default page size | **20**, with 10 / 20 / 50 offered and the choice remembered per table per user. |
| 4 | Bulk action inventory | As in §8. The only destructive bulk actions are invoice cancel (already refuses paid invoices), promise cancel and user deactivate; each confirms with an exact count first. |
| 5 | Saved views | Out of scope. URL-encoded filters cover sharing and bookmarking. |
| 6 | Promise reminders | Deferred. Only the "promise broke" notification ships. |
| 7 | Column visibility / reordering | Not built. |

## 10. Deliberate departures

- **No filter is pre-applied on any list (product decision, 2026-09-15).** D1 and AC-A6 asked for a
  POC's own book to open as a pre-applied, removable chip. In practice that opened an admin — who
  is assignable as every kind of POC — on an empty Invoices list. Lists now open unfiltered for
  every role, and a POC narrows to their own book with the POC column filter. The server-enforced
  scope for a POC without `SCOPE_OVERRIDE` is unchanged and still shows as a locked chip.
- **The global "filter by customer" scope bar is gone.** It was a second, URL-invisible filtering
  mechanism competing with the new column-driven filters. Its behaviour is now a `customerId`
  filter chip, which is shareable, bookmarkable and consistent with every other filter.
- **CSV export opens a copyable dialog** rather than triggering a file download. A browser download
  needs `dart:html`, which would break the iOS and Android targets the PRD says must not regress.
- **All list endpoints moved to the paged envelope in one go**, and the frontend moved with them;
  no endpoint is left half-migrated.
- **Promise mutators return DTOs rendered inside their own transaction.** Mapping a detached entity
  in the controller worked only because `spring.jpa.open-in-view` is on; the promise's links are
  lazy many-to-many collections and would have failed outside a web request.

## 11. Tests

`mvn verify` — 107 tests, all green.

| Suite | Covers |
|---|---|
| `DataSeederTest` | AC-A1 — roles seeded, re-running neither duplicates nor resets, admin customisation survives, `CASHIER` keeps what it had, `CUSTOMER` never gains POC sight |
| `PocAssignmentTest` | AC-A2 to AC-A9 — mandatory fields rejected server-side, assignability by privilege, many POCs per customer with a safe primary, deleting an assigned user deactivates instead, audit entries, customer-scoped blindness, legacy rows badged, findable and still payable |
| `PromiseLifecycleTest` | AC-B1 to AC-B12 — validation, auto-link and auto-status, the sweeper, notify-once, the re-entrant cases (void, dispute cancel, dispute edit, amount change), late payment not un-breaking, overrides, cancel unlinking without touching payments, many-to-many without double counting |
| `TableQueryTest` | AC-D1 to AC-D4, AC-D9, AC-D10 and AC-E1 to AC-E5 — page size honoured, stable ordering, server-side sort, rejected columns/operators/values/sizes, bound (not interpolated) values, multi-value operators arriving as one parameter, customer-scope boundary, locked POC book, tiles over the filtered set |
| `BulkActionTest` | AC-D5 to AC-D8 — per-record partial failure, no rollback of the rows that worked, skipped vs failed, ids outside scope excluded, select-all-matching, per-record audit, CSV escaping |

`flutter analyze` — clean.
