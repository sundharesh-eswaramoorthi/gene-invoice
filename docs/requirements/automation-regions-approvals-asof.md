# PRD — Automation engine, regions, maker-checker and as-of reads

This is the requirement the work on branch `feature/automation-regions-approvals-asof` was built from. It
supersedes the earlier one-page PRD for tasks, assignees, email placeholders and automation, which is in git
history at `ae7e378:docs/requirements/tasks-placeholders-and-automation.md`. Where the two differ, this one
wins; where this one is silent, the earlier one records intent (several assignees, a "My tasks" list,
placeholders by level).

## Part A — The automation engine

Build the engine that doesn't exist yet.

- **A1. Triggers.** A rule fires when a Customer, Invoice or Payment is created or updated, or on a daily/weekly
  schedule.
- **A2. Conditions.** Filters over that entity's fields — with AND and OR operations.
- **A3. Actions.** Create a Task, Promise or Dispute, or send an Email. Assignees and recipients are picked with the
  same role-at-a-level model the email To field already uses.
- **A4. Placeholders.** `{{Customer.Name}}`, `{{Invoice.Balance}}` and so on, in subject and body, offered for both
  levels. A role held by several people resolves to the primary.
- **A5. Durable execution.** The trigger publishes an event; a consumer performs the action. A rule can never slow
  down or break the user's save, and nothing is lost if the consumer is down. A rule can be scheduled or run from
  the UI whenever wanted.
- **A6. Tasks.** A task on a customer, invoice or payment — title, due date, status, assignees — since automation
  needs something to create and people need somewhere to see their work.

## Part B — The dimensions everything now has

- **B1. Regions.** Every record belongs to exactly one. A user holds different rights in different regions — read
  in one, manage in another, nothing in a third. The POC book is per region. Every list, filter, count and
  dashboard figure becomes a question about which regions am I asking about.
- **B2. Maker-checker.** Any change above a configurable threshold doesn't take effect on save. It becomes pending
  and needs approval from someone else holding the approval right in that record's region. Thresholds are per
  region. A pending change is visible to everyone who can see the record, without having taken effect.
- **B3. As-of reads.** Every list, report and rule evaluation can be asked as of a date — including which region a
  record belonged to then, and which approvals were outstanding.

## Where the answers are

- How each clause was designed: [`../design/DESIGN.md`](../design/DESIGN.md) and the four
  [decision records](../design/decision-records/).
- Open product questions and the defaults applied: DESIGN.md §10.2, plus the `userDecisions` in the triage
  files under [`../implementation/hardening/`](../implementation/hardening/) and
  [`../regression/2026-09-28/data/runs/`](../regression/2026-09-28/data/runs/).
- Proof that each clause works: the final test run, [`../regression/2026-09-28/`](../regression/2026-09-28/).
