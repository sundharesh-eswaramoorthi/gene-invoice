# E2E triage, round 2

I checked both defects against the working tree. Both are genuine, and I rejected neither. The code shows each root
cause directly, so I did not need a private reproduction:
- MIG-R2-1: Hibernate's `ddl-auto: update` runs before `AutomationSchemaUpgrade`. The constructor takes the
  EntityManagerFactory, so the upgrade can only run after the DDL. On Postgres, the DDL adds `claims`, `depth`,
  `kind` and `occurred_at` (events) and `owner_user_id`, `schedule_zone`, `catch_up`, `actions_json` and
  `max_records_per_run` (rules) as NOT NULL with no default. That cannot work on a table that has rows.
  `archive()` then skips the `kind is null` predicate when `kind` does not exist, so a legacy DONE row stays in the
  live table.
- APP-R2-1: `PendingChangeViews.text()` resolves only `*UserId` and `customerId`. `LABELS` has no entry for
  `invoiceIds` or `promiseIds`. The Flutter `_ChangesTab._value` pretty-prints any list or map as JSON.

Streams: backend B1 and B2 are independent and can run in any order. Frontend F1 runs in parallel with both and
builds against the contract stated in F1. It must render the current server's replies and the fixed server's.

Isolation for every package: never touch the user's services. Postgres checks use only gi-e2e-db (:18432), with
databases named e2e_*, and ports 18100-18299.

## Backend
- B1: upgrading a database whose legacy automation tables have rows. Archive the unreadable rows before
  Hibernate's DDL. A required column that the table lacks makes every row unreadable. Add `@ColumnDefault("0")` to
  the event counters. [MIG-R2-1]
- B2: the approval changes table gives readable text for invoice ids, promise ids and invoice line products, and
  labels them "Invoices" and "Promises". A create omits id-list rows that set nothing. [APP-R2-1 (be)]

## Frontend
- F1: the approval Changes tab falls back to readable text for id lists and line items when `proposedText` or
  `currentText` is null (an older server). It also relabels the older server's "Invoice ids" and "Promise ids".
  [APP-R2-1 (fe)]

## Rejected
None.

## User decisions (defaults applied)
- MIG-R2-1: this extends the r1 decision for MIG-R1-1/2. Suppose a legacy automation table (one that has
  `idempotency_key`/`trigger_kind` on events, or `action`/`filters_json` on rules) lacks a column this build
  requires. Then every row in it was written by the earlier build and cannot be read. Before Hibernate's schema
  update runs, those rows are copied to `<table>_legacy` and deleted from the live table, with a WARN. No table is
  ever dropped. A database left half-migrated by a `SCHEMA_HALT_ON_ERROR=false` boot repairs itself on the next
  boot.
- APP-R2-1: the approval changes table shows the following:
  - `invoiceIds` is labelled "Invoices". Its text is the invoice numbers, comma-separated.
  - `promiseIds` is labelled "Promises". Its text is "Promise of <amount> by <date>" per promise.
  - `items` is one line per item: "<qty> × <product name> at <unit price>", or "at list price" when there is no
    unit price.
  - An id the server cannot find reads "invoice #<id>", "promise #<id>" or "product #<id>".
  - On a create, an id-list field that is null or `[]` is left out.

## Harness fixes (tester applies next round)
- APP-D-28 / APP-UI-32 (suites/approvals): assert the contract above. For the payment's row `invoiceIds`, check
  label == "Invoices", proposedText == "INV-20260927-0045", and that the UI cell shows that text. Do not assert
  the id-equals-suffix coincidence. For the invoice-create row `items`, check that proposedText contains
  "150 × E2E Widget". On a create with no promises, check there is no `promiseIds` row. Do not assert a
  "Promise ids" row.
- MIG-C-02 (suites/migration): after the fix, a default-halt boot succeeds. With SCHEMA_HALT_ON_ERROR=false, the
  expected result is: eventsLive 0; eventsLegacy holds legacy-k1, legacy-k2 and legacy-k3 (all 3, including the
  DONE row); rulesLegacy holds 2 rules. The live tables have all mapped columns.

The per-package instructions are in the triage agent's StructuredOutput. The text is the same.
