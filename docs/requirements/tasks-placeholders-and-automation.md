# PRD — Tasks, Assignees, Smarter Emails, and Automation

## Problem

Collections work lives in people's heads. There is no way to say "someone chase this invoice by Friday", emails are retyped by hand every time, documents on a record can't be sent with them, and every follow-up is manual — nobody is told an invoice went overdue unless they look.

## What we're building

**1. Tasks.** A task on a customer, invoice or payment: title, due date, status, assignees. Visible on the record and in a "my tasks" list.

**2. Assignees, one model everywhere.** Task, Dispute and Promise all take **several** assignees, each either a person or a role at a level — customer-level (everyone in the customer's POC book) or record-level (the POC on that invoice/payment). Same picker the email To field already uses.

**3. Emails that write themselves.**
- Attach documents already on the record or its customer — no re-uploading.
- Placeholders in subject and body, offered for both levels: on an invoice, `Customer.*` and `Invoice.*`; on a payment, `Customer.*` and `Payment.*`. A role held by several people fills in the primary.

**4. Automation.** A rule anyone can write in the UI:
- **When** — a Customer, Invoice or Payment is created or updated, or on a daily/weekly run.
- **Where** — filters over that entity's fields, the same ones the list page filters by.
- **Then** — create a Task, a Promise or a Dispute, or send an Email, with assignees/recipients picked the same way as everywhere else.

Rules run **through a queue**: the trigger publishes an event, a consumer creates the action. A rule can never slow down or break the user's save, and nothing is lost if the consumer is down.

## Out of scope

Branching/multi-step workflows, approvals, delays inside a rule. Triggers on Task/Dispute/Promise/Document. Saved email templates. SMS/WhatsApp/push. Subtasks, time tracking, Kanban.

## How we know it worked

- Overdue invoices get a named owner and a date without anyone asking.
- The common follow-up emails are sent by rules, not by people.
- Nothing an automation sends or assigns escapes existing permissions or the internal/shared rule on documents.