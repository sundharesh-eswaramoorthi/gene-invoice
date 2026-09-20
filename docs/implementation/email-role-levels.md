# Role levels: customer-level and record-level POCs in email — design and contract

**Date:** 2026-09-20
**Status:** Implementation contract. It replaces decision **E3** of `email.md` and the role column of its §4 table.
The backend and frontend are built against it, and `email.md` is updated to match.

## 1. What the user asked for (2026-09-20)

> "make every entities role map to be always multiple like sales poc for invoice"
> "no always show in to recipient select list customer level and invoice level poc in case of payment then payment
> and customer level"

So the To list always offers **two groups of roles**: the **customer's** POC book, and the **record's own** POC
fields. Either can be picked, or both.

## 2. Decisions

| # | Decision |
|---|---|
| L1 | A role token now carries a **level**: `CUSTOMER` (the customer's POC book) or `RECORD` (what this record stores). The pair (role, level) is what the user picks, what is validated, and what is stored on the recipient. |
| L2 | **Customer level** reaches **every active holder** of that seat on the record's customer (`PocService.activeHolders`), the primary first, then in seating order — for the two roles the customer's POC book holds: `CUSTOMER_SUCCESS_POC` → `PocType.SUCCESS`, `COLLECTION_POC` → `COLLECTION`. **`SALES_POC` is not a customer-level role**: the Sales POC is assigned per invoice (`poc-payment-promise-and-tables.md` §3) and `PocService.add` refuses a customer-level SALES seat outright, so offering it here only ever produced an entry nobody could hold — a recipient that resolved to nobody, a sender the form then refused to send from, and a bulk send that skipped every row (CP-01). Offered on every type that has a customer: CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE, and a USER that is a customer login. |
| L3 | **Record level** is the POC the record itself stores, which is one person: INVOICE → `salesPoc` as `SALES_POC`; PAYMENT → `collectionPoc` as `COLLECTION_POC`; PROMISE → `collectionPoc` as `COLLECTION_POC`; DISPUTE → its target's, i.e. an invoice target's `salesPoc` as `SALES_POC` and a payment target's `collectionPoc` as `COLLECTION_POC`. CUSTOMER, USER, PRODUCT and ROLE have no record-level roles. Which record-level roles a **kind** offers (list page, bulk, picker) is the union of what its records can store: INVOICE `SALES_POC`; PAYMENT and PROMISE `COLLECTION_POC`; DISPUTE `SALES_POC` and `COLLECTION_POC`. |
| L4 | Both groups are **always offered** where they exist, whether or not anyone holds them. A group's role with nobody active is shown and reported as unresolved ("nobody assigned"), exactly as today. A dispute whose target is a payment offers record-level `SALES_POC` as unresolved (nothing holds it), and vice versa. |
| L5 | **From** takes one person, as before: a record-level role is the stored person; a customer-level role is the first holder (the primary, else the next active one). Nobody → the existing 400 / skipped-row behaviour. |
| L6 | **De-duplication is unchanged** (E5): the same person picked at both levels, or added by name as well, is one recipient that keeps every way they were added, e.g. "Sales POC (customer)" and "Sales POC (this invoice)". |
| L7 | **Old data keeps working, and is not given a level it never had.** A stored source or sender role written before this change kept no level (`ROLE:<ROLE>`, `from_role_level` null), so none is claimed for it: it is shown by the role alone — "Sales POC", exactly as it read then — and carries no `level` back to the app. Nothing is rewritten, so such a row still means what it meant when it was written (the record's own POC on a kind that stored that role, the customer's seats otherwise), and nothing tells the reader otherwise. A **request** token without `level` is a different thing, because it is being resolved now: it means `CUSTOMER` when the type offers that role at customer level, otherwise `RECORD`. Since `SALES_POC` is offered at customer level nowhere (L2), on an invoice `{"type":"ROLE","role":"SALES_POC"}` means the **invoice's own** Sales POC, which is what such a token meant before levels existed; `{"type":"ROLE","role":"COLLECTION_POC"}` on a payment, offered at both levels, means the **customer's** Collection POC seats, and a caller that means the payment's own has to send `"level":"RECORD"`. |

## 3. API

### Tokens (`SendEmailRequest.from`, `.to`, bulk `params`)

```jsonc
{"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER"} // everyone holding that seat on the customer
{"type": "ROLE", "role": "COLLECTION_POC", "level": "RECORD"}   // this payment's own Collection POC
{"type": "ROLE", "role": "SALES_POC", "level": "RECORD"}        // this invoice's own Sales POC
{"type": "ROLE", "role": "SALES_POC"}                           // no level: RECORD, the only level it is offered at (L2, L7)
```

`level` is `CUSTOMER` or `RECORD`; anything else is 400 `"level must be CUSTOMER or RECORD"`. A (role, level) the
type does not offer is 400, wording as today plus the level: `"Sales POC (customer) is not a role on invoices"`
(no kind offers that pair, since the Sales POC is not a customer-level seat — L2),
`"Sales POC (this payment) is not a role on payments"` — for a single send and preview against the record, for bulk
against the kind. Every message that names a role names its level the same way, so picking both levels never prints
the same sentence twice: `"No recipients: Collection POC (customer) is not assigned"`, `"Nobody holds Sales POC
(this invoice) on Invoice INV-0042, so it cannot be the sender"`, and an unresolved entry's `reason` (§5 of
`email.md`, otherwise unchanged).

### `GET /api/emails/context`

`roles` becomes one entry per (role, level), customer level first, then record level; customer level in the order
`CUSTOMER_SUCCESS_POC`, `COLLECTION_POC` (L2), record level in the order `SALES_POC`, `COLLECTION_POC`:

```jsonc
{"role": "COLLECTION_POC", "label": "Collection POC",
 "level": "CUSTOMER", "levelLabel": "Customer", "groupLabel": "Customer level",
 "resolved": true,
 "people": [{"userId": 12, "name": "Bob Smith", "email": "bob@…", "gmail": "CONNECTED"}, …],
 "sender": {"userId": 12, …}}
{"role": "SALES_POC", "label": "Sales POC",
 "level": "RECORD", "levelLabel": "Invoice", "groupLabel": "Invoice level",
 "resolved": true, "people": [{"userId": 15, …}], "sender": {"userId": 15, …}}
```

`levelLabel` is `"Customer"` at customer level, and at record level the record's noun with a capital: `Invoice`,
`Payment`, `Promise`, `Dispute`. `groupLabel` is `levelLabel + " level"`. `resolved`, `people` and `sender` keep
their meaning (null / empty without an `entityId` or when masked).

### Stored sources and how they read back

`email_recipients.sources` holds `ROLE:CUSTOMER:<ROLE>` or `ROLE:RECORD:<ROLE>`; `ROLE:<ROLE>` (old rows) is kept
as it is and reads back without a level (L7). `unresolved` on the email uses the same tokens. In
`Participant.sources` and the preview:

```jsonc
{"type": "ROLE", "role": "COLLECTION_POC", "level": "CUSTOMER", "label": "Collection POC (customer)"}
{"type": "ROLE", "role": "SALES_POC", "level": "RECORD", "label": "Sales POC (this invoice)"}
{"type": "ROLE", "role": "SALES_POC", "label": "Sales POC"}   // written before levels: no level at all (L7)
```

The email card shows them as "— as Collection POC (customer)" / "— as Sales POC (this invoice)" / "— as Sales
POC", and an unresolved role as "Collection POC (customer) — nobody assigned" / "Sales POC — nobody assigned".
A stored `ROLE:CUSTOMER:SALES_POC` can only be a row written while the customer level still offered that role
(before CP-01); it is not rewritten either, and reads back as it was.

The **sender's** role keeps its level the same way: `emails.from_role_level` (null on rows written before this
change), given out as `EmailDto.fromRoleLevel`, with `fromRoleLabel` the level-qualified label — without it the
card's "· as {role}" would disagree with the masked name beside it. On a row written before levels both are the
old, level-less pair: `fromRoleLevel` null and `fromRoleLabel` "Sales POC".

### Suggestions (`event` given)

Unchanged in spirit; each suggested role token now names its level: PROMISE → `CUSTOMER`, `ROLE:COLLECTION_POC`
at **both** levels; DISPUTE CREATED → customer-level `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC`, plus the
record-level role its target has; DISPUTE UPDATED → `CUSTOMER`. Duplicates merge (L6).

## 4. Frontend (compose dialog)

- **To** shows the role chips in labelled groups, in this order: **Customer level** (Customer Success POC,
  Collection POC — the Sales POC is not a customer-level seat, L2), then **&lt;Record&gt; level** (e.g. "Invoice
  level": Sales POC), then the "Customer emails" chip. A group with no roles is left out; a role with nobody assigned is shown as today ("· nobody assigned").
- Each chip names who it reaches, as it does now: one person in full, two by name, three or more as "+ N more",
  with the tooltip listing each name and address.
- In **bulk** and **record picker** modes (no record yet) both groups are still shown. A bulk chip reads
  "(each record's)" as today; a picker chip is the plain role as today, since one record will be picked, not each.
- A chip already **in To** stands away from the headings, so where one role has been added at both levels each of
  the two names its own: "Collection POC (customer)" / "Collection POC (this payment)", and in bulk, where neither
  names anybody, "Collection POC (each customer's)" / "Collection POC (each payment's)". Added at one level only —
  which the Sales POC always is, since it is offered at record level only — a chip reads as today.
- **From** offers the same roles, grouped the same way, each naming the one person who would send. The **closed**
  field stands away from the headings, as a chip in To does, so a role offered at both levels names its own level
  there — "Collection POC (customer)" / "Collection POC (this payment)", and in bulk "Collection POC (each
  customer's)" / "Collection POC (each payment's)" — while the items in the open menu keep the plain label their
  heading already qualifies. Bulk has no preview, so the closed field is the only place the chosen sender is stated.
- A customer login sees the role labels only, with no names (E13), grouped the same way.
