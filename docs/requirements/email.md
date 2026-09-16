# Email feature: requirements

Design and the answers taken to what this leaves open: [`docs/implementation/email.md`](../implementation/email.md).

## 1. Scope

Emails exist only inside the app, as stored Email records. Nothing is delivered to a real mailbox.

An email is linked to either a Customer or an Invoice.

## 2. Where emails can be sent from

The same entry points exist for both customers and invoices:

| Entry point | Behaviour |
|---|---|
| Details page | "Send Email" button |
| List page | "Send Email" option |
| Table row quick action | "Send Email" in a row's action menu |
| Table bulk action | Select several rows, then "Send Email" |

Bulk sends: the user fills in the form once, and the app creates a separate email for each selected
row. Each email is linked to that row's customer or invoice.

## 3. Compose form

| Field | Rules |
|---|---|
| From | Required. Pick an internal user by name, or pick a role. If a role is picked, the role's email address is looked up at send time and shown on the email. |
| To | Required, with at least one recipient. Can be any mix of the three types below. |
| Subject | Required. It can't be empty or only spaces. |
| Body | Optional. It can be empty. |

To recipient types:

- Internal users, picked by name.
- Roles. Everyone in the role at send time becomes a recipient. People who join the role later
  don't get emails sent before they joined.
- Customer emails. Every email address on the customer can be picked. For an invoice, these are the
  addresses of the invoice's customer. In a bulk send, choosing customer emails adds all addresses
  of each row's own customer.

How recipients are worked out:

- If a user is named directly and also belongs to a role in To, they get the email only once.
- In a bulk send, a row whose customer has no email address still gets its email as long as another
  recipient is in To. A row that ends up with no recipients at all is skipped. After the send, the
  user sees how many emails were created and how many rows were skipped.

## 4. Email tab on the details pages

- Add an Email tab to the Customer Details and Invoice Details pages.
- Emails are listed newest first.
- Each email shows:
  - From: the user, or the role with its email address
  - To: the users, the roles with their members at send time, and the customer email addresses
  - Subject and Body
  - Sent by (the logged-in user who sent it)
  - Date and time sent
- The Customer Email tab shows the customer's own emails plus emails sent for that customer's
  invoices. Invoice emails are labelled with the invoice number.

## 5. Inbox

- Add an Inbox item to the left pane, directly below Dashboard.
- It lists the emails where the logged-in user is a recipient, either named directly or through a
  role at send time, newest first.
- Unread and read emails look different.
- Opening an email shows everything about it and marks it as read.
- Mark as read works on a single email.
- Mark all as read marks every unread email for that user, not just the current page.
- The list has pagination.
- Read status is tracked per user. When one person reads an email, it doesn't change the status for
  anyone else.
- Customer email addresses are recorded on the email but have no Inbox.

## 6. Not included in this phase

- Real email delivery
- Attachments
- Mark as unread
- Search and filters in the Inbox
- An unread count badge
