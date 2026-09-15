# PRD — POC Ownership, Payment Promises, and the List/Detail Framework

**Product:** Gene Invoice (Spring Boot 3.3 backend + Flutter frontend)
**Date:** 2026-09-08
**Status:** Ready for implementation
**Doc type:** Lean PRD — goals, user stories, acceptance criteria. **API contracts, entity/DDL design, and Flutter widget structure are deliberately left to the implementing session.**

> **How to use this doc:** hand this file to a fresh session with "implement this". The session should first read the current code (`backend/src/main/java/com/geneinvoice/**`, `frontend/lib/**`), then propose its own schema, endpoint and migration design that satisfies the acceptance criteria below.

---

## 1. Context — where the app is today

- **Domain:** `Customer` (has `creditBalance`), `Product`, `Invoice` + `InvoiceItem` (`UNPAID` / `PARTIALLY_PAID` / `FULLY_PAID` / `CANCELLED`), `Payment` + `PaymentAllocation` (applied oldest-invoice-first; overpayment becomes customer credit and is consumed by the next invoice), `Dispute` (customer opens → admin approves/denies → applies an invoice edit, invoice cancel, or payment void), `Notification`, `AuditLog`.
- **Access control:** a `User` has **exactly one** `Role`; a `Role` holds a set of `Privilege` rows; endpoints are guarded by `@PreAuthorize("hasAuthority('<PRIVILEGE>')")`. Seeded roles: `ADMIN`, `CASHIER`, `VIEWER`, `CUSTOMER`. A user with a non-null `customerId` is a self-service customer and every list/read is scoped to that customer.
- **Frontend:** go_router + Riverpod, screens for dashboard, customers, products, invoices, invoice form, payments, disputes, dispute detail, notifications, users, roles. There is already a dispute **detail** screen and an **audit history panel** — both are the closest existing precedents for what this PRD asks for.
- **Known gaps this PRD closes:** every list endpoint returns an unbounded, unfiltered, unsorted array; there are no detail screens for Customer / Invoice / Payment; there is no concept of who owns an account or a collection; there is no way to record a customer's promise to pay.

---

## 2. Goals

1. Make **ownership explicit** — every invoice has a salesperson, every customer has named success and collections contacts, every payment records who collected it.
2. Give collections a **first-class Payment Promise** so "the customer said they'd pay ₹X by the 20th" is tracked data, not a note.
3. Replace flat lists with **detail screens** that put the editable facts on top and the conversation (disputes, promises, history) below.
4. Make every table **usable at scale** — server-side pagination, sorting, filtering, inline actions and bulk actions.
5. Make the **numbers on a list page answer the question actually being asked** — summary tiles that reflect the current filter, not the whole database.

## 3. Non-goals (explicitly out of scope for this round)

- Tax, discounts, due dates, PDF export, email delivery, recurring invoices, product stock.
- Changing the existing dispute workflow or the credit/allocation algorithm.
- Multi-tenancy, company branding, or any change to the JWT/auth mechanism.
- A saved-views / shareable-filter-URL feature (see Open Questions).

## 4. Locked decisions

These were decided up front; do not re-litigate them during implementation.

| # | Decision |
|---|---|
| D1 | The three POCs are **real login roles**, seeded alongside `ADMIN`/`CASHIER`/`VIEWER`/`CUSTOMER`. A POC who logs in sees **their own book by default** (their assigned customers/invoices/payments), with the ability to clear that filter if their privileges allow it. |
| D2 | Pagination, sorting, filtering **and the summary tiles** are **server-side**. List endpoints accept page/size/sort/filter inputs and return a page plus a total count; tiles come from an aggregate that honours the same filter. |
| D3 | A Payment Promise is **auto-tracked**: `OPEN → KEPT / PARTIALLY_KEPT / BROKEN`. It flips to `BROKEN` automatically once the promised date passes unmet, auto-links a payment that settles the promised invoices, allows manual override, and notifies the Collection POC. |
| D4 | POC fields are mandatory **on new records only**. Existing rows keep a null POC, surface a "POC missing" badge, and are findable through a dedicated filter. No backfill, no blocking of existing records. |

---

## 5. Feature A — POC roles and assignment

### A.1 The roles

Three new seeded roles: **Sales POC**, **Customer Success POC**, **Collection POC**.

**Constraint to design around:** a `User` today has exactly one `Role`, so one person cannot hold two POC roles. Therefore **assignability must be driven by privileges, not by role name** — a user is offered as a Sales POC if their role carries the "can be assigned as Sales POC" privilege. This keeps the existing one-role model intact, lets `ADMIN` be assignable to all three, and lets an admin compose a custom role ("Success + Collections") on the existing Roles screen without any code change.

### A.2 Assignment rules

| Entity | POC | Cardinality | Required? |
|---|---|---|---|
| Invoice | Sales POC | Exactly one | **Yes, on create** |
| Customer | Customer Success POC | **Many**, add/remove freely, one marked primary | No (but warn when empty) |
| Customer | Collection POC | **Many**, add/remove freely, one marked primary | No (but warn when empty) |
| Payment | Collection POC | Exactly one | **Yes, on create** |

### A.3 User stories

- **US-A1** As an **admin**, I can give a user one of the three POC roles so they appear in the right assignment dropdowns.
- **US-A2** As a **cashier or admin creating an invoice**, I must pick a Sales POC before I can save; the form pre-selects me if I am assignable, otherwise the field starts empty and blocks submission.
- **US-A3** As an **admin or CS POC on a customer's detail screen**, I can add and remove Customer Success POCs and Collection POCs, and mark one of each as primary.
- **US-A4** As a **cashier recording a payment**, the Collection POC field is pre-filled with that customer's primary Collection POC, is editable, and is mandatory.
- **US-A5** As a **Sales POC**, when I open Invoices I see my invoices first, and I can tell at a glance that a "my records only" filter is active.
- **US-A6** As a **Collection POC**, Customers and Payments open scoped to my book the same way.
- **US-A7** As an **admin**, I can filter any list by POC — including "POC missing" — so I can clean up unassigned legacy records.
- **US-A8** As a **self-service customer user**, I never see POC fields anywhere in the UI.

### A.4 Acceptance criteria

- **AC-A1** The three roles exist after a fresh boot **and** after an upgrade of an existing database, created by the same idempotent seeding path that creates today's roles. Re-running the seeder does not duplicate or reset them.
- **AC-A2** Creating an invoice without a Sales POC is rejected by the **backend** with a validation error, not just disabled in the UI. Same for creating a payment without a Collection POC.
- **AC-A3** The assignable-user dropdown returns only **active** users whose role carries the matching assignability privilege, and it is searchable — it must not degrade when the user table is large.
- **AC-A4** A customer can hold multiple CS POCs and multiple Collection POCs. Adding, removing and re-designating the primary all take effect without a page reload. Removing the primary either promotes another automatically or clears the primary flag — never leaves a dangling pointer.
- **AC-A5** Deactivating or deleting a user who is assigned as a POC does not orphan or corrupt existing records. The historical assignment stays readable; the user stops appearing in dropdowns; records naming an inactive POC are findable via filter.
- **AC-A6** A POC's default scope is a **pre-applied, visible, clearable filter chip** — not a hidden server-side restriction. Whether they may clear it is governed by their privileges; when they cannot, the chip is shown as locked rather than silently absent.
- **AC-A7** Every POC assignment change writes an audit entry through the existing audit service, capturing before/after and the acting user, and is visible in that record's History tab.
- **AC-A8** A user whose account is customer-scoped (`customerId` set) sees no POC field, column, filter or dropdown in any screen, and the API does not return POC identity data to them.
- **AC-A9** Records created before this feature shipped display a **"POC missing"** badge and are selectable via a filter; they remain fully editable and payable (per D4).

---

## 6. Feature B — Payment Promise

A Payment Promise records that a customer committed to pay a stated amount by a stated date.

### B.1 Shape

- Raised **against a customer** (a general promise) **or against one to many specific invoices**.
- Carries: promised amount, promised date, the Collection POC responsible, free-text notes, who created it and when.
- Status: **`OPEN` → `KEPT` / `PARTIALLY_KEPT` / `BROKEN`**, plus `CANCELLED` for a promise raised in error.
- Links to the payment(s) that fulfil it.

### B.2 User stories

- **US-B1** As a **Collection POC**, from a customer's detail screen I can raise a promise for an amount and a date, optionally tagging the specific invoices it covers.
- **US-B2** As a **Collection POC**, from an invoice's detail screen I can raise a promise pre-scoped to that invoice.
- **US-B3** As a **cashier recording a payment**, I am shown that customer's open promises and can link the payment to one; if the payment settles the promised invoices, it links automatically.
- **US-B4** As a **Collection POC**, when a promise passes its date unmet I am notified that it broke.
- **US-B5** As a **collections manager**, I can filter promises by status, date range, customer and Collection POC, and see totals for what is promised, kept and broken.
- **US-B6** As a **Collection POC**, I can override an auto-computed status with a reason when reality disagrees with the arithmetic.
- **US-B7** As a **self-service customer**, I can see promises made on my account read-only. *(Flagged in Open Questions — confirm before building.)*

### B.3 Acceptance criteria

- **AC-B1** A promise must have an amount greater than zero and a promised date; a promise against invoices may only reference invoices belonging to that same customer, and never a `CANCELLED` invoice.
- **AC-B2** Promised amount may exceed, equal or fall short of the referenced invoices' outstanding balance. Nothing is blocked; a shortfall/excess is simply displayed.
- **AC-B3** A payment that fully settles every invoice referenced by an open promise links to that promise and marks it `KEPT` **without human action**.
- **AC-B4** A payment that partially settles them marks the promise `PARTIALLY_KEPT` and records how much of the promise remains outstanding.
- **AC-B5** An open promise whose date has passed with insufficient payment becomes `BROKEN` **automatically** — not only when someone happens to open the screen. The mechanism (scheduled job vs. computed-on-read) is the implementing session's call, but the status must be correct in list results, filters and totals for a user who never opens the record.
- **AC-B6** Status transitions are **idempotent and re-entrant**: voiding a linked payment, or a dispute-driven invoice cancellation or edit, re-evaluates every affected promise instead of leaving a stale `KEPT`.
- **AC-B7** A promise raised against a customer with no invoices tracks against that customer's total outstanding balance at the promised date.
- **AC-B8** Only a user with the collections privilege may create, edit, cancel or override a promise. Creation requires a Collection POC; it defaults to the customer's primary.
- **AC-B9** Every status change — automatic or manual — writes an audit entry. Manual overrides require a reason and record who overrode it.
- **AC-B10** The Collection POC receives an in-app notification when a promise they own breaks, using the existing notification service, and the notification deep-links to the promise.
- **AC-B11** Cancelling a promise unlinks any payments without altering those payments or their allocations.
- **AC-B12** A single payment may fulfil more than one promise, and a promise may be fulfilled by more than one payment; neither case double-counts in the totals.

---

## 7. Feature C — Detail screens

Three new screens: **Customer Details**, **Invoice Details**, **Payment Details**. All three share one layout.

### C.1 Layout

```
┌──────────────────────────────────────────────┐
│  TOP  (~50%)                                 │
│  Identity + key figures + EDITABLE fields    │
│  including POCs. Inline edit, explicit save.  │
├──────────────────────────────────────────────┤
│  BOTTOM (~50%)  [ Disputes | Promises | History ] │
│  Tabbed. Lazy-loaded per tab.                │
└──────────────────────────────────────────────┘
```

### C.2 What sits on top

| Screen | Top section |
|---|---|
| Customer Details | Name, phone, email, address, credit balance, outstanding total. **Editable:** contact fields, CS POC list, Collection POC list (add / remove / set primary). |
| Invoice Details | Invoice number, date, customer, status, line items, total, paid, balance. **Editable:** notes, Sales POC. Line items remain editable **only** through the existing dispute-approval path — this PRD does not open direct line editing. |
| Payment Details | Amount, method, paid-at, status, credit applied, and the invoice allocations it produced. **Editable:** notes, Collection POC. |

### C.3 The three tabs

- **Disputes** — disputes filed against this record (or, on Customer, all of that customer's disputes), with the existing create/open actions. Reuse the current dispute screens rather than rebuilding them.
- **Payment Promise** — promises attached to this record, with create/edit/cancel per Feature B.
- **History** — the audit trail. Reuse the existing audit history panel; extend it so it can be anchored to a customer or a payment, not only the entity types it handles today.

### C.4 User stories

- **US-C1** As any user with view rights, clicking a row in Customers, Invoices or Payments opens that record's detail screen at a bookmarkable URL.
- **US-C2** As an admin or POC, I can edit the top-section fields in place and save without leaving the screen; validation errors appear against the offending field.
- **US-C3** As a user, switching tabs does not reload the top section, and the selected tab survives a browser refresh.
- **US-C4** As a Collection POC, I can raise a promise directly from the Promises tab of a customer or invoice, pre-filled with that context.
- **US-C5** As any user, the History tab shows me who changed what and when, newest first, including POC and promise changes made through this feature.

### C.5 Acceptance criteria

- **AC-C1** Each detail screen is reachable by direct URL containing the record id, and deep-links from notifications land on the right screen and tab.
- **AC-C2** Editable fields are gated by the same privileges as the existing update endpoints. A viewer sees the values rendered read-only, with no disabled-looking inputs.
- **AC-C3** Unsaved edits prompt for confirmation before navigating away.
- **AC-C4** Each tab loads its own data only when first opened, and a tab's failure renders an error inside that tab without taking down the screen.
- **AC-C5** After a successful save, the top section, the affected tab, and the originating list page all reflect the new values — no stale cached row when the user navigates back.
- **AC-C6** All three screens are usable at mobile width: the top/bottom split becomes vertical scroll, and the tab bar stays reachable. Flutter targets web, iOS and Android — none may regress.
- **AC-C7** A self-service customer opening their own invoice or payment sees the same layout with POC fields absent and edit controls hidden.
- **AC-C8** A record id that does not exist, or that the caller may not see, renders a clean not-found/forbidden state rather than a crash or an empty shell.

---

## 8. Feature D — Table framework

This applies to **every** list page: Customers, Products, Invoices, Payments, Users, Roles, Disputes, Notifications, and the new Payment Promises list.

### D.1 Pagination

- Page-size selector with **10 / 20 / 50**. Default **20**.
- Page number, total row count and total page count always visible.
- Chosen page size persists per user across sessions and applies per table (a big Invoices page and a small Users page are independent).

### D.2 Inline (row) actions

Per-row actions appropriate to the entity and the caller's privileges — e.g. view, edit, cancel invoice, record payment, raise promise, reassign POC, void payment. Destructive actions confirm first.

### D.3 Bulk actions

- A checkbox column, a select-all-on-page control, and an explicit "select all N matching this filter" affordance distinct from "select all on this page".
- A toolbar appears when a selection exists, showing the count and the permitted actions.
- Minimum bulk set: **reassign POC**, **export selected**, and the entity's safe bulk state change (e.g. bulk cancel unpaid invoices, bulk mark notifications read).

### D.4 Filters

- Filters are **built from the table's own columns** — the user picks a column, then an operator valid for that column's type (text: contains/equals; enum: is/is-any-of; number and money: range; date: range and relative presets; reference such as customer or POC: searchable picker, plus "is empty" for the POC-missing case).
- Multiple filters combine with AND. Each shows as a removable chip. "Clear all" resets to the role's default scope (see AC-A6).
- The active filter set is encoded in the URL so a filtered view can be shared or bookmarked.
- Column sorting is server-side and combines with filters.

### D.5 Acceptance criteria

- **AC-D1** Changing page, page size, sort or filters triggers exactly one backend request and never fetches more rows than the page size.
- **AC-D2** Changing the page size or any filter resets to page 1; the result set is stably ordered so no row is skipped or repeated across pages.
- **AC-D3** Sorting is available on every column the backend can order by, and every such column is documented for the implementing session; columns that cannot be sorted are visibly not sortable.
- **AC-D4** Filters, sort and page are restored from the URL on load, so a shared link reproduces the exact view for a user with the same privileges.
- **AC-D5** Bulk actions are transactional per record with a per-record result: a partial failure reports which rows succeeded and which failed, and never silently drops rows.
- **AC-D6** A bulk action only ever applies to records the caller is privileged to change; ineligible rows are excluded and reported, not attempted.
- **AC-D7** "Select all N matching the filter" applies to the whole filtered set, not just the loaded page, and states the exact count before the user confirms.
- **AC-D8** Every inline and bulk mutation writes audit entries, one per affected record.
- **AC-D9** Filter, sort and pagination inputs are validated server-side; an unknown column, operator or oversized page size is rejected cleanly, and no filter input can reach the persistence layer as raw query text.
- **AC-D10** A customer-scoped user's mandatory scoping cannot be widened through any filter or bulk-selection parameter.
- **AC-D11** Empty state, loading state and error state are distinct and unambiguous — "no rows match this filter" never looks like "still loading" or "request failed".
- **AC-D12** The table behaves on mobile: horizontal scroll or a responsive card layout, with pagination and the selection toolbar reachable.

---

## 9. Feature E — Filter-aware summary tiles

Each list page carries a row of summary tiles **above** the table.

- Tiles reflect the **currently applied filters**, not the whole table and not just the visible page. Filtering to one Collection POC and last month must change every number.
- Suggested tiles: **Invoices** — count, total billed, outstanding, overdue-ish/unpaid count. **Payments** — count, total collected, credit applied. **Customers** — count, total outstanding, total credit balance, count missing a POC. **Payment Promises** — open count and amount, kept, broken, broken amount.

### Acceptance criteria

- **AC-E1** Tile values are computed **server-side over the full filtered set**, never from the current page's rows.
- **AC-E2** Tiles and table are refreshed by the same filter change and never display mutually inconsistent numbers; if they load separately, the tiles show their own loading state rather than a stale value.
- **AC-E3** Monetary tiles use exact decimal arithmetic consistent with the existing money handling — no floating-point drift, and a currency-correct display format.
- **AC-E4** With no filters applied, tiles reflect the caller's full permitted scope (for a POC, their default book — matching what the table shows).
- **AC-E5** Tiles for an empty result set render zeros, not blanks or errors.
- **AC-E6** The existing dashboard screen is reconciled with this pattern — either reusing the same aggregate source or explicitly documented as separate.

---

## 10. Cross-cutting requirements

- **Permissions.** Every new capability is privilege-guarded in the same style as the existing controllers. The implementing session must produce a role × capability matrix covering `ADMIN`, `CASHIER`, `VIEWER`, `CUSTOMER` and the three POC roles, and the seeder must grant them idempotently. `CASHIER` must retain everything it can do today.
- **Auditing.** POC assignment changes, promise lifecycle changes, and inline/bulk mutations all flow through the existing audit service so the History tab shows them.
- **Notifications.** Reuse the existing notification service. At minimum: promise broken → Collection POC. Consider: POC assigned/reassigned → the newly assigned user. Notification links must deep-link to the new detail screens.
- **Backward compatibility.** Existing clients and the existing dispute flow must keep working. If list responses change shape from a bare array to a paged envelope, the frontend is updated in the same change and no endpoint is left half-migrated.
- **Testing.** The repository currently has **no backend tests at all**. This feature must ship with tests, at minimum covering: promise status transitions including the re-entrant cases in AC-B6, POC mandatory-field enforcement, filter/pagination correctness including the customer-scoping boundary in AC-D10, and bulk-action partial failure. `mvn verify` and `flutter analyze` must pass.

## 11. Migration and existing data

- Per **D4**, no backfill. New POC fields are nullable in storage and enforced at the application layer for new records only.
- Legacy records surface a **"POC missing"** badge and are reachable via an "is empty" filter on each POC column.
- Seeding of the three new roles and their privileges must be **idempotent and safe against a populated production database**, matching how roles are seeded today.

## 12. Open questions — resolve before or during implementation

1. **Customer visibility of promises (US-B7).** Should a self-service customer see promises made on their account, and if so read-only or can they propose one? Assumed **read-only visible** unless told otherwise.
2. **Detail-screen tabs.** Only Disputes / Payment Promise / History were specified. Customer Details would naturally also want Invoices and Payments tabs — add them, or keep strictly to three?
3. **Default page size.** Assumed **20**. Confirm, or prefer 10.
4. **Bulk action inventory.** The minimum set in D.3 is a proposal; confirm the exact per-entity list, especially which destructive actions are permitted in bulk.
5. **Saved views.** Should filter combinations be nameable and reusable per user? Currently out of scope; URL-encoded filters cover the sharing case.
6. **Promise reminders.** Auto-tracking was chosen over the reminders variant. Confirm that pre-due reminder notifications are genuinely deferred.
7. **Column visibility.** Filters are column-driven; should users also be able to show/hide and reorder columns? Assumed **no** for this round.

## 13. Suggested build order

1. **Roles, privileges and POC assignment** (Feature A) — everything else references it.
2. **Server-side pagination, sorting and filtering** on Invoices and Payments (Feature D), then the remaining tables.
3. **Summary tiles** (Feature E) on top of the D aggregate work.
4. **Detail screens** (Feature C) with the Disputes and History tabs.
5. **Payment Promises** (Feature B), landing in the Promises tab and its own list page.
6. **Inline and bulk actions** (Feature D.2 / D.3) once the detail screens define what each action does.
