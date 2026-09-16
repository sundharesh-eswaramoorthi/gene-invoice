# Confirmed defects — regression run 15 Sep 2026

72 confirmed defects (12 high, 28 medium, 32 low): 67 from this run after independent re-verification and de-duplication, plus D-68…D-72, found while re-checking the medium fixes on 16 Sep 2026. Severity: high = core flow, money or security wrong; medium = partly wrong or confusing; low = cosmetic or minor.

Status column is for tracking fixes in later sessions (all OPEN as of this run). Medium-severity fixes were re-verified on 16 Sep 2026 against the same environment: API checks in `scripts/verify-medium-fixes/api-group1.js`…`api-group5.js` (ids M1-…M5-, results next to each script), UI checks in `scripts/verify-medium-fixes/ui/` (W-01…W-18 in `ui-results.json`; W-12 re-checked after the follow-up fix by `w12r.js`). Low-severity fixes were re-verified on 16 Sep 2026 too: `scripts/verify-low-fixes/api-low.js` (L-01…L-09) and `scripts/verify-low-fixes/ui-low.js` (L-UI-01…L-UI-04, plus screenshots of the layout-only fixes). D-72 is the one defect left open. High-severity fixes were re-verified on 15 Sep 2026 against the rebuilt regression environment: API checks in `scripts/verify-high-fixes/api.js` (results in `api-results.json`, ids V-01…V-12), UI checks in `scripts/verify-high-fixes/ui/`.

| ID | Severity | Kind | Area | Title | Status |
|---|---|---|---|---|---|
| D-01 | high | API | auth-users-roles | A deactivated user's existing JWT keeps full read/write API access for up to 24h | FIXED (51619ef; V-01, V-02 pass) |
| D-02 | high | API | permissions-scoping | Export endpoints check only EXPORT_DATA, so roles without the view privilege can export all users, roles, disputes and products | FIXED (51619ef; V-03 pass) |
| D-03 | high | API | payments-credit | Voiding a payment doesn't reverse money that already moved through customer credit, so credit the customer can spend (or paid invoices with no payment) is left behind | FIXED (1e4d127; V-04, V-05, V-06 pass) |
| D-04 | high | UI | disputes-notifications | The FilledButton theme forces infinite width: dispute Approve/Deny and the Payment/Invoice 'Save changes' button are not painted, and a click on the blank row approves | FIXED (a04c5e8; U-01…U-04 pass) |
| D-05 | high | API | invoices | Creating invoices at the same time fails with 500 duplicate invoice number | FIXED (1e4d127; V-07 pass) |
| D-06 | high | UI | customers-products | The invoice form and Record payment dialog offer only the first 50 customers and products by name, so later customers can't be invoiced or paid from the UI | FIXED (a04c5e8; U-05…U-09 pass) |
| D-07 | high | API+UI | invoices | Notes-only saves on Invoice and Payment details fail when the POC has been deactivated or the role lacks POC_ASSIGN | FIXED (691c39d; V-08, V-09, U-10, U-11 pass) |
| D-08 | high | API | table-framework | Customer logins can filter and sort on POC-restricted columns, and the match counts reveal who their Sales and Collection POCs are | FIXED (51619ef; V-10 pass) |
| D-09 | high | API | promises | A KEPT general promise flips to BROKEN, and notifies the POC, when a later invoice is raised | FIXED (691c39d; V-11 pass) |
| D-10 | high | API+UI | promises | A promise can't be edited once one of its invoices is cancelled | FIXED (691c39d; V-12, U-12, U-13 pass) |
| D-11 | high | UI | detail-history-dashboard | The unsaved-changes guard only covers the Back arrow: sidebar, browser Back, the bell and in-page links drop edits without a prompt | FIXED (a04c5e8, 8ffed8f; U-14…U-19, U-23…U-25 pass) |
| D-12 | high | UI | ui-sweep | On phones, a long 'username • ROLE' chip pushes the Notifications bell over the hamburger, so a tap opens Notifications instead of the drawer; the app title is hidden | FIXED (a04c5e8; U-20…U-22 pass) |
| D-13 | medium | API | table-framework | Malformed client input (bad JSON, bad enum or number, non-numeric id or param, missing param) returns 500 with internal class names instead of 400 | FIXED (8cb2f87; M1-01 pass) |
| D-14 | medium | API | table-framework | Bulk actions silently drop ids that are unknown or outside the caller's scope or filter | FIXED (44fc64f; M3-01 pass) |
| D-15 | medium | API | permissions-scoping | The SALES_POC locked book applies only to lists and bulk: another rep's invoice can be read, edited and cancelled by id, and seats can be edited on customers outside the book | FIXED (d57a846; M2-01 pass) |
| D-16 | medium | API | permissions-scoping | Promise DTOs expose staff user ids to customers: createdByUserId and overriddenByUserId are often the Collection POC's own id | FIXED (d57a846; M2-02 pass) |
| D-17 | medium | API | permissions-scoping | USER audit history (email, role, privileges) is readable by staff without USER_VIEW | FIXED (d57a846; M2-03 pass) |
| D-18 | medium | UI | customers-products | POC picker search is one keystroke behind, so a typed username doesn't filter the list | FIXED (7552dd6; W-10 pass) |
| D-19 | medium | UI | ui-sweep | Desktop tables are sized to the full window, so the last columns and row actions sit off-screen even at 1920px | FIXED (7552dd6; W-11 pass) |
| D-20 | medium | UI | ui-sweep | Long reason or message text makes the Disputes and Notifications tables thousands of pixels wide | FIXED (7552dd6, bf1613f; W-12 re-check pass) |
| D-21 | medium | UI | table-framework | Roles with EXPORT_DATA but no *_MANAGE can't select rows, so 'Export selected' is unreachable | FIXED (7552dd6; W-13 pass) |
| D-22 | medium | UI | table-framework | Table schemas are cached across logout, so a customer who signs in after an admin is offered the Sales POC filter columns | FIXED (d57a846; W-04 pass) |
| D-23 | medium | API | table-framework | Date upper bounds (lte, between, yesterday, past, lastMonth) include rows stamped at 00:00:00 of the next day | FIXED (7552dd6; M4-01 pass) |
| D-24 | medium | UI | table-framework | A page past the end shows 'No invoices match this filter' and a garbled pager ('991–990 of 4') | FIXED (7552dd6; W-14 pass; pager wording left as D-69) |
| D-25 | medium | UI | detail-history-dashboard | Malformed detail URLs (#/invoices/abc, /customers/abc, /payments/abc) show a blank grey error box | FIXED (7552dd6; W-15 pass) |
| D-26 | medium | UI | payments-credit | A payment's Payment Promise tab lists every promise of the customer, not the ones attached to that payment | FIXED (d83d1d8; M5-05 pass) |
| D-27 | medium | API+UI | auth-users-roles | Users/Roles admin: a duplicate email, a duplicate role name, or deleting a role still in use returns 500 with raw SQL shown in the dialog | FIXED (8cb2f87; M1-02, W-01, W-02 pass) |
| D-28 | medium | API+UI | auth-users-roles | A user with no email can't be edited, reactivated or re-roled from the UI; a blank email is stored as '' and collides on the unique constraint | FIXED (8cb2f87; M1-03 pass) |
| D-29 | medium | API | payments-credit | Overlong text fields return 500 with SQL instead of a 400 field error (username >80, invoice notes >500, payment notes >300) | FIXED (8cb2f87; M1-04, W-01 pass) |
| D-30 | medium | API | payments-credit | Payment amounts with more than 2 decimals are accepted: 0.001 is stored as a ₹0.00 payment, and the POST response shows a different amount from the one stored | FIXED (8cb2f87; M1-05, W-03 pass) |
| D-31 | medium | UI | payments-credit | Record payment: choosing a customer silently replaces a Collection POC the cashier already picked | FIXED (44fc64f; W-06 pass) |
| D-32 | medium | API | disputes-notifications | Dispute replace_items accepts quantity 0 or negative, producing ₹0 or negative FULLY_PAID invoices | FIXED (8cb2f87; M1-06 pass) |
| D-33 | medium | API | promises | An override revives a CANCELLED promise, and clearing the override re-links the payment | FIXED (44fc64f; M3-02 pass) |
| D-34 | medium | API | promises | Promise totals double-count a payment shared by overlapping or general promises | FIXED (d83d1d8; M5-01, M5-02, M5-06 pass) |
| D-35 | medium | API | promises | A payment ticked to a promise but allocated to a different invoice shows as linked yet contributes 0 | FIXED (d83d1d8; M5-03, M5-04 pass) |
| D-36 | medium | API | promises | The default Collection POC for a new promise can be a deactivated user | FIXED (44fc64f; M3-03, W-07 pass) |
| D-37 | medium | UI | promises | After an override from a Promises list row, the row and tiles stay stale until the user navigates away | FIXED (44fc64f; W-08 pass) |
| D-38 | medium | UI | ui-sweep | On phones the detail header breaks the invoice number mid-token and squeezes the subtitle | FIXED (7552dd6; W-16 pass) |
| D-39 | medium | API+UI | ui-sweep | The dispute target text shows money unformatted with a raw enum: 'INVOICE INV-… — 451234.50' | FIXED (7552dd6; M4-02, W-17 pass) |
| D-40 | low | API | table-framework | Paging and sort validation gaps: a huge page number overflows int and returns 500; an invalid sort direction is silently treated as asc | FIXED (9b6a2a2; L-01 pass) |
| D-41 | low | API | auth-users-roles | CSV export ignores the requested sort for users, roles and products (rows come back in id or undefined order) | FIXED (9b6a2a2; L-02 pass) |
| D-42 | low | API | auth-users-roles | Admin can set a 1-character password through the user create and update API | FIXED (9b6a2a2; L-03 pass) |
| D-43 | low | API | auth-users-roles | DELETE /api/roles/{unknown id} returns 200 | FIXED (9b6a2a2; L-04 pass (came with the D-27 fix)) |
| D-44 | low | API | customers-products | Automatic primary-POC changes (promotion on remove, demotion on add-as-primary) are not audited | FIXED (9b6a2a2; L-05 pass) |
| D-45 | low | API | customers-products | An invalid bulk ADD_POC request (pocType SALES, an inactive user) is reported as every row skipped instead of one 400 | FIXED (9b6a2a2; L-06 pass) |
| D-46 | low | API | customers-products | Bulk ADD_POC without POC_ASSIGN returns 400 instead of 403 | FIXED (9b6a2a2; L-06 pass) |
| D-47 | low | API | customers-products | The backend accepts an inactive product on a new invoice | FIXED (9b6a2a2; L-07 pass) |
| D-48 | low | API | payments-credit | An unknown invoiceId on a payment is silently ignored and the money becomes credit | FIXED (9b6a2a2; L-08 pass) |
| D-49 | low | UI | invoices | Cancelled invoices still offer 'Raise promise', which the backend then rejects | FIXED (89d151b; code change, no browser check) |
| D-50 | low | UI | table-framework | Reference filter chips show the raw id ('Customer is 184') instead of the name | FIXED (89d151b; code change, no browser check) |
| D-51 | low | UI | customers-products | The 'POC missing' badge on phone customer cards runs under the Open icon | FIXED (89d151b; screenshot lo-51-customers-1366) |
| D-52 | low | UI | customers-products | The 'Name is required' error stays after a valid name is typed on Customer Details | FIXED (89d151b; code change, no browser check) |
| D-53 | low | API | disputes-notifications | The DISPUTE_OPENED notification link /admin/disputes/{id} is not a frontend route | FIXED (9b6a2a2; L-09 pass) |
| D-54 | low | UI | disputes-notifications | Dispute detail shows a raw DioException on 403 or 404 | FIXED (89d151b; code change, no browser check) |
| D-55 | low | UI | disputes-notifications | The bell badge isn't refreshed after bulk Mark read and stays stale until the 30s poll | FIXED (89d151b; code change, no browser check) |
| D-56 | low | UI | table-framework | Summary tiles are refetched on every page change, so each page change makes two requests (AC-D1) | FIXED (89d151b; code change, no browser check) |
| D-57 | low | UI | table-framework | Page size is remembered per device, not per user | FIXED (89d151b; code change, no browser check) |
| D-58 | low | UI | permissions-scoping | A viewer sees POC seats as grey, disabled-looking chips | FIXED (89d151b; screenshot lo-58-poc-chips-viewer-1366) |
| D-59 | low | UI | detail-history-dashboard | Screen-reader users can't expand single-link History rows; activating the row follows the link | FIXED (89d151b; code change, no browser check) |
| D-60 | low | UI | ui-sweep | On phones the Raise promise dialog wraps the date onto a second line and cuts the amount label | FIXED (89d151b; screenshot lo-60-customer-promises-400) |
| D-61 | low | UI | ui-sweep | Phone list pages leave about 350px (roughly 1.3 cards) for rows | FIXED (89d151b; screenshot lo-61-invoices-400) |
| D-62 | low | UI | ui-sweep | The sidebar highlights 'Dashboard' on Notifications and on the customer's own customer page | FIXED (89d151b; code change, no browser check) |
| D-63 | low | UI | ui-sweep | Date-time formats differ between screens | FIXED (89d151b; code change, no browser check) |
| D-64 | low | UI | ui-sweep | The Customer Details top pane clips the POC editors mid-label with no scroll cue | FIXED (89d151b; screenshot lo-64-customer-detail-1366) |
| D-65 | low | UI | ui-sweep | On phones the 'What should change?' dropdown text runs under the arrow | FIXED (89d151b; code change, no browser check) |
| D-66 | low | UI | ui-sweep | Dispute detail shows developer wording to users: 'INVOICE history', 'Proposed change (JSON)' | FIXED (89d151b; L-UI-03 pass) |
| D-67 | low | UI | ui-sweep | The dashboard shows staff without PAYMENT_MANAGE a 'My payments' button that opens all payments | FIXED (89d151b; L-UI-04 pass) |
| D-68 | medium | API | disputes-notifications | A dispute reason of 1,001–2,000 characters fails with 409 'This change conflicts with existing data' | FIXED (bf1613f; M5-07 pass) |
| D-69 | low | UI | table-framework | A page past the end still reads 'Page 100 of 24' in the pager | FIXED (89d151b; L-UI-01 pass) |
| D-70 | low | UI | table-framework | Signing out fires a request that fails with 401 (the table schema is re-fetched for nobody) | FIXED (89d151b; code change, no browser check) |
| D-71 | low | UI | ui-sweep | The 'That page does not exist.' page has no sidebar or top bar | FIXED (89d151b, 358e30a; L-UI-02 pass) |
| D-72 | low | UI | ui-sweep | Table row checkboxes are missing from the accessibility tree | OPEN |

## D-01 — A deactivated user's existing JWT keeps full read/write API access for up to 24h

- **Severity:** high  ·  **Kind:** API  ·  **Area:** auth-users-roles (also permissions-scoping)
- **Test cases:** AUR-009, PS-039

**Reproduce**

```text
Create a user and log in; keep the token. As admin, PUT /api/users/{id} {active:false}, or DELETE a user named as a POC (which deactivates them), or bulk DEACTIVATE. Call GET /api/auth/me, GET /api/invoices, POST /api/customers, PATCH /api/invoices/{id} and POST /api/users/export with the old token.
```

**Expected**


401 on every call once the account is inactive, the same as a new login ('User is disabled') and a hard-deleted user's token.


**Actual**


All calls succeed. /me returns 200 with the inactive user, writes succeed (a customer and an invoice were created and invoice notes were changed), and users export returns 200. A new login is correctly refused with 401.


**Root cause**

```text
backend/.../auth/JwtAuthFilter.java:35-40 loads UserDetails and sets the Authentication without checking userDetails.isEnabled(). AppUserDetails.isEnabled() returns user.isActive(), but only the login path (DaoAuthenticationProvider) checks it. Tokens live 24h (application.yml expiration-ms 86400000). Since deleting a POC user now deactivates them (AC-A5), a removed employee keeps access.
```

**Suggested fix**


In JwtAuthFilter, after loadUserByUsername, skip authentication when !userDetails.isEnabled() (and when the account is locked), so the request reaches the 401 entry point. Add a test: deactivate the user, then the old token must get 401. Optionally add a token-version claim so tokens can also be revoked on password change.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-auth-users-roles/recheck.js (deactivated token: /me 200, invoices 200, POST customer 200 id 194, change-password 200); rt/verify-permissions-scoping/recheck-result.json R2_deactivated {login:401, oldTokenInvoices:200}
```

## D-02 — Export endpoints check only EXPORT_DATA, so roles without the view privilege can export all users, roles, disputes and products

- **Severity:** high  ·  **Kind:** API  ·  **Area:** permissions-scoping (also auth-users-roles, disputes-notifications, table-framework)
- **Test cases:** AUR-032, DN-API-25, TF-083, PS-010

**Reproduce**

```text
As the seeded cashier (or CASHIER, SALES_POC, CS_POC or COLLECTION_POC users), send POST /api/users/export, /api/roles/export, /api/disputes/export and /api/products/export with {selectAllMatchingFilter:true}. Compare with GET on the same lists.
```

**Expected**


403 wherever the matching list is 403. Design §2 gives USER_VIEW and ROLE_VIEW only to ADMIN, CASHIER has no DISPUTE_VIEW, and COLLECTION_POC has no PRODUCT_VIEW. EXPORT_DATA means exporting the current selection of a list the caller can see.


**Actual**


Every export returns 200 text/csv with the whole table, while the matching GET returns 403. Users export had about 400 rows including '1,admin,System Administrator,admin@geneinvoice.local,ADMIN,true', roles export listed every role with its privileges, disputes export covered every customer's disputes with their reasons, and COLLECTION_POC could export products.


**Root cause**

```text
UserController.java:210-213, RoleController.java:64-65, DisputeController.java:80-81 and ProductController.java:140-141 use @PreAuthorize(EXPORT_DATA) only. Their resolveIds passes an empty scope (List.of()), and ScopeResolver.forDisputes() is empty for staff.
```

**Suggested fix**


Require both privileges on every export, e.g. hasAuthority('EXPORT_DATA') and hasAuthority('USER_VIEW'), and likewise ROLE_VIEW, DISPUTE_VIEW and PRODUCT_VIEW (and the *_VIEW privilege on invoices, payments, customers and promises for consistency). Add a matrix test that every export is 403 wherever the matching GET list is 403.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-permissions-scoping/api-result.json and recheck-result.json (PS010); rt/verify-disputes-notifications/api.js (cashier list 403, get 403, export(all) 200 with 101-118 rows)
```

## D-03 — Voiding a payment doesn't reverse money that already moved through customer credit, so credit the customer can spend (or paid invoices with no payment) is left behind

- **Severity:** high  ·  **Kind:** API  ·  **Area:** payments-credit (also disputes-notifications)
- **Test cases:** DN-API-23, DN-API-24, PAYCR-031

**Reproduce**

```text
(a) Invoice 100 paid by payment P of 100. Approve a cancel dispute on the invoice (100 is refunded to credit), then approve a void dispute on P. (b) Invoice 300 paid by P of 300. Approve replace_items down to 1 x 50 (250 refunded to credit), then void P. (c) P overpays and 123 goes to credit; the next invoice L uses that credit; then void P.
```

**Expected**


After the void, money on invoices plus the customer's credit is no more than active collections. Either the credit is taken back, or the void is refused.


**Actual**


(a) Credit stays at 100 after the void. (b) Credit stays at 250 and a new 200 invoice was auto-paid from it. (c) Credit is floored at 0 and invoice L stays paid 123 with no active payment behind it (invoices hold 146 paid against 23 collected). All reproduced on two runs.


**Root cause**

```text
PaymentService.reverseAllocations (PaymentService.java:199-219) subtracts each allocation from invoice paid (clamped at 0) and removes only p.creditApplied from credit (floored at 0). InvoiceService.cancelWithRefund (~247-262) and replaceItems (~296-302) move paid money into credit but leave the PaymentAllocation and creditApplied unchanged. When an invoice uses credit (applyCustomerCreditIfAny), there is no link back to the payment the credit came from.
```

**Suggested fix**


Keep a ledger that ties credit to the payment it came from. When a refund moves money to credit, reduce the payment's allocation and increase its creditApplied. Record credit consumption as allocations tied to the source payment, so a void can reverse it exactly. Until then, have reverseAllocations take any shortfall from credit or from the invoices that used it, or refuse the void with a clear 400.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-disputes-notifications/api.js (DN-API-22/22b); rt/verify-payments-credit/api-verify.js PAY-33 {paidOnInvoicesAfterVoid:146, activeCollected:23}
```

## D-04 — The FilledButton theme forces infinite width: dispute Approve/Deny and the Payment/Invoice 'Save changes' button are not painted, and a click on the blank row approves

- **Severity:** high  ·  **Kind:** UI  ·  **Area:** disputes-notifications (also payments-credit, ui-sweep)
- **Test cases:** DN-UI-11, PAYCR-048, UIS-10

**Reproduce**

```text
As admin, open a pending dispute at 1366x900 or 1920x1080 and click anywhere on the blank row under 'Admin notes'. Separately, open #/payments/{id} or #/invoices/{id} and edit Notes. Also look at the dashboard and dialog buttons.
```

**Expected**


Visible Approve and Deny buttons, each clickable on its own; a visible 'Save changes' button with an 'Unsaved changes' hint; buttons at their normal sizes.


**Actual**


Nothing is drawn where the buttons belong. Approve's invisible hit area is the full row width (1093px), so one click on empty space approved the dispute. Deny can only be clicked at an invisible 35x18 spot. On Payment and Invoice details, Save changes is invisible but a blind click saves. Elsewhere the same theme stretches 'New invoice' and the tab buttons full-width and stacks dialog Save under Cancel.


**Root cause**

```text
frontend/lib/core/theme.dart:15-19 sets FilledButton minimumSize to Size.fromHeight(46), which means an infinite minimum width. A FilledButton.icon placed directly in a Row gets unbounded width, and its layout fails: dispute_detail_screen.dart:185-198, payment_detail_screen.dart:268-280 and invoice_detail_screen.dart:264-276. customer_detail_screen.dart:289 has the same pattern. data_table_scaffold.dart:160-166 already works around this locally.
```

**Suggested fix**


Change the theme to minimumSize: const Size(64, 46) and set full width only where it is wanted, such as login. Then re-check Approve/Deny and Save changes on the dispute, payment, invoice and customer details in a headed browser.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-disputes-notifications/shots/ui11-1366-detail-r1.png and ui11b-134-before-click.png (a click at x=1330 or x=400 approved); rt/verify-payments-credit/shots/v048-b-dirty-1366x900.png, v048x-after-click.png
```

**Screenshot:** [report/shots/D-04-1.png](report/shots/D-04-1.png)

## D-05 — Creating invoices at the same time fails with 500 duplicate invoice number

- **Severity:** high  ·  **Kind:** API  ·  **Area:** invoices
- **Test cases:** INV-023

**Reproduce**

```text
Send 6 POST /api/invoices at once (Promise.all) for one customer; repeat.
```

**Expected**


All 6 succeed with unique sequential INV-yyyyMMdd-NNNN numbers.


**Actual**


On both runs one returned 200 and five returned 500: 'could not execute statement [ERROR: duplicate key value violates unique constraint ... Key (invoice_number)=(INV-20260915-02..)'. Sequential creates work.


**Root cause**

```text
InvoiceService.java:146-150 nextInvoiceNumber() computes countByInvoiceNumberStartingWith(prefix)+1 with no lock, sequence or retry, and Invoice.java:26 enforces a unique constraint. GlobalExceptionHandler.java:60-63 turns the violation into a 500 that includes the SQL.
```

**Suggested fix**


Generate the number from a per-day counter row read with SELECT ... FOR UPDATE, or from a Postgres sequence, or retry on DataIntegrityViolationException for invoice_number. Map DataIntegrityViolationException to 409 without SQL text.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-invoices/api.js (6 concurrent: one 200, five 500, on two runs; sequential control 200/200/200)
```

## D-06 — The invoice form and Record payment dialog offer only the first 50 customers and products by name, so later customers can't be invoiced or paid from the UI

- **Severity:** high  ·  **Kind:** UI  ·  **Area:** customers-products (also invoices, payments-credit)
- **Test cases:** UI-017, INV-045, PAYCR-046

**Reproduce**

```text
With more than 50 customers or products, create customer 'zz...' and product 'zz...'. Open #/invoices/new or Payments → Record payment and scroll the Customer and Product dropdowns to the end.
```

**Expected**


Every customer and every active product can be chosen, for example through a searchable picker.


**Actual**


The requests are /api/customers?size=50&sort=name,asc (50 of 192-218) and /api/products?size=50&sort=name,asc (50 of 96-109, some of them inactive). Menus end at the 50th name, and the zz records are missing. The dropdowns have no search, and the API caps size at 50.


**Root cause**

```text
customers_screen.dart:19-26 (allCustomersProvider, size 50, no paging) and products_screen.dart:15-22 (productsProvider, size 50, no active filter) feed plain DropdownButtonFormFields at invoice_form_screen.dart:112-113, 135, 264-267 and record_payment_dialog.dart:112. TableQuery allows only sizes 10, 20 and 50.
```

**Suggested fix**


Replace both dropdowns with server-backed searchable pickers (debounced name:contains, size 20-25; products with filter active:eq:true), following the PocPicker/reference_picker pattern. Consider adding 'Record payment' on Customer Details.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/shots/c-customer-menu-end.png, c-product-menu-end.png; rt/verify-invoices/shots/customers-v2-menu-end.png; rt/verify-payments-credit/shots/v046-dropdown-bottom.png
```

**Screenshot:** [report/shots/D-06-1.png](report/shots/D-06-1.png)

## D-07 — Notes-only saves on Invoice and Payment details fail when the POC has been deactivated or the role lacks POC_ASSIGN

- **Severity:** high  ·  **Kind:** API+UI  ·  **Area:** invoices (also payments-credit)
- **Test cases:** INV-024, PAYCR-022, INV-025, PAYCR-023

**Reproduce**

```text
(a) Name a SALES_POC or COLLECTION_POC on an invoice or payment, then DELETE that user (it gets deactivated). Open the detail, edit only Notes, and click Save changes. (b) Create a custom role with INVOICE_MANAGE or PAYMENT_MANAGE but no POC_ASSIGN, then do the same notes-only save.
```

**Expected**


The notes are saved. The POC is unchanged, so it shouldn't be re-validated (AC-A5: records naming an inactive POC stay editable).


**Actual**


(a) 400 'User ... is inactive and cannot be assigned'. (b) 400 'You may not change the Sales POC' or '...Collection POC'. The UI shows the red error with 'Unsaved changes' and the notes are not saved. PATCH {notes} alone succeeds.


**Root cause**

```text
invoice_detail_screen.dart:79 and payment_detail_screen.dart:78-81 always resend the unchanged POC id. InvoiceService.java:106-107 and PaymentService.java:100-101 call requireAssignable before comparing the id with the current POC. InvoiceController.java:78-80 and PaymentController.java:87-89 reject any non-null POC id when the caller lacks POC_ASSIGN.
```

**Suggested fix**


Backend: compare the requested POC id with the current one first, and validate it and check POC_ASSIGN only when it differs. Frontend: send salesPocUserId or collectionPocUserId only when the user actually changed it.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-invoices/shots/poc-inactive-v2-saved.png, mgr-v2-saved.png; rt/verify-payments-credit/shots/v022-inactive-save.png; api-verify.js PAY-24
```

**Screenshot:** [report/shots/D-07-1.png](report/shots/D-07-1.png)

## D-08 — Customer logins can filter and sort on POC-restricted columns, and the match counts reveal who their Sales and Collection POCs are

- **Severity:** high  ·  **Kind:** API  ·  **Area:** table-framework (also invoices, promises, permissions-scoping)
- **Test cases:** INV-029, PRM-CU03, TF-081, PS-022

**Reproduce**

```text
As a customer login, send GET /api/invoices?filter=salesPocUserId:eq:<id> or salesPocName:contains:<prefix> or sort=salesPocName,asc. Send /api/promises and /api/payments?filter=collectionPocUserId:eq:<id>, and /api/customers?filter=successPocUserId:eq:<id>.
```

**Expected**


400 'Unknown column'. These columns are removed from the customer's schema, and AC-A8 says the API does not return POC identity to customer-scoped users.


**Actual**


All return 200. The counts identify the rep: the own POC id returns 2-10 rows, any other id returns 0, and name-prefix probing finds the name. Sort on the POC name is accepted. The row payloads correctly have POC fields set to null.


**Root cause**

```text
TableQuery.java:40,48 (requireSortable/requireFilterable) and TableSchema.java:24-50 never check ColumnDef.pocRestricted. Only TableSchemaController.java:39-41 hides these columns, and only from the published schema.
```

**Suggested fix**


Make parsing caller-aware: when the caller is customer-scoped (or cannot see POCs), treat pocRestricted columns as unknown in filter and sort, on list, summary, bulk and export alike. Add TableQueryTest cases. Severity: kept at high, the highest verifier rating (three verifiers said medium), because this breaks an explicit acceptance criterion, any customer can do it, and it is confirmed on four entities.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/api.js (salesPocUserId:eq:408 → 4 vs 999999 → 0); rt/verify-permissions-scoping/recheck-result.json R4_customerPocOracle
```

## D-09 — A KEPT general promise flips to BROKEN, and notifies the POC, when a later invoice is raised

- **Severity:** high  ·  **Kind:** API  ·  **Area:** promises
- **Test cases:** PRM-L01

**Reproduce**

```text
Customer with no invoices and a primary Collection POC. Create a general promise of 500 dated today-2; it becomes KEPT because nothing is owed. Then POST a new invoice of 300 today and GET the promise.
```

**Expected**


It stays KEPT. AC-B7 says a general promise is judged on the balance at the promised date.


**Actual**


It becomes BROKEN, a PROMISE_STATUS_CHANGED audit row is written, and the Collection POC gets a 'Promise broken' notification. A future-dated control correctly stays OPEN.


**Root cause**

```text
PaymentPromiseService.java:274-280 judges past-dated general promises with customerOutstanding() (:355-363), which uses current balances including invoices raised after the date. InvoiceService.java:95 re-evaluates every live promise on each invoice create.
```

**Suggested fix**


Measure outstanding as of the promised date (invoices dated on or before promisedDate, net of payments by the end of that day), or freeze the verdict at the deadline and re-judge only on events that change facts before the deadline.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/api.js L01 (KEPT → BROKEN, 1 notification, on two runs)
```

## D-10 — A promise can't be edited once one of its invoices is cancelled

- **Severity:** high  ·  **Kind:** API+UI  ·  **Area:** promises
- **Test cases:** PRM-R04

**Reproduce**

```text
Create a promise of 200 on invoices [A,B]. Cancel B (directly or through a dispute). Open Customer Details → Payment Promise → Edit, change the notes, and Save.
```

**Expected**


The edit succeeds. An invoice that is already linked and later cancelled doesn't block edits, or it can be unticked.


**Actual**


PUT with invoiceIds [A,B] returns 400 'Invoice INV-... is cancelled and cannot be promised against'. The dialog lists only A, so B can't be unticked, and it shows a misleading 'Promised ₹100.00 more than those invoices owe.' The dialog stays open.


**Root cause**

```text
PaymentPromiseService.java:126-128 re-resolves every submitted invoice, and resolveInvoices (:556-559) rejects cancelled invoices even when they are already linked. promise_form_dialog.dart:71,123 always resends every linked invoice, and its checklist (:292-299) loads only UNPAID and PARTIALLY_PAID invoices.
```

**Suggested fix**


Backend: reject cancelled invoices only when they are newly added. Frontend: show the invoices already linked (with their status) so they can be unticked, or send invoiceIds only when the selection changed. Compute the excess/shortfall hint over live invoices only.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/shots/r2-r04-3-after-save.png; api.js R04 (PUT [A,B] 400, [A] 200)
```

**Screenshot:** [report/shots/D-10-1.png](report/shots/D-10-1.png)

## D-11 — The unsaved-changes guard only covers the Back arrow: sidebar, browser Back, the bell and in-page links drop edits without a prompt

- **Severity:** high  ·  **Kind:** UI  ·  **Area:** detail-history-dashboard (also payments-credit)
- **Test cases:** DET-012, DET-013, DET-014, PAYCR-052

**Reproduce**

```text
On Customer, Invoice or Payment Details, edit a field. Then click a sidebar item, press browser Back, click the notifications bell, or tap a payment allocation row.
```

**Expected**


The same 'Discard unsaved changes?' prompt that the Back arrow shows (AC-C3).


**Actual**


The page navigates at once with no prompt, the edits are lost, and the server values are unchanged. The Back arrow control does prompt.


**Root cause**

```text
The only guard is a PopScope and onBack (customer_detail_screen.dart:133-141, invoice_detail_screen.dart:119-127, payment_detail_screen.dart:119-127). The sidebar (app_shell.dart:120, drawer :208), the bell (:165) and the allocation row (payment_detail_screen.dart:257) call context.go, which replaces the route without a pop. Browser history changes are location changes, so PopScope never runs.
```

**Suggested fix**


Use GoRoute.onExit on /customers/:id, /invoices/:id and /payments/:id, backed by a dirty-state provider that runs _confirmDiscard. This covers the sidebar, drawer, bell, links and browser history. Optionally add beforeunload on web.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-detail-history-dashboard/shots/h2-customer-sidebar-Invoices.png, h5-browser-back-0.png, h7-alloc-tap-0.png; rt/verify-payments-credit/shots/v052p3-a-after-sidebar.png
```

**Screenshot:** [report/shots/D-11-1.png](report/shots/D-11-1.png)

## D-12 — On phones, a long 'username • ROLE' chip pushes the Notifications bell over the hamburger, so a tap opens Notifications instead of the drawer; the app title is hidden

- **Severity:** high  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-01, UIS-02

**Reproduce**

```text
Open the app at 400x820 as a user whose account label is long, such as a COLLECTION_POC user. Tap the hamburger at (28,28).
```

**Expected**


The drawer opens for every role, and the title and actions never overlap.


**Actual**


The bell sits at x≈35 with its badge drawn over the hamburger, and the tap navigates to #/notifications with the drawer closed. The title is cut to 'Gene I…' for admin and missing for VIEWER, SALES_POC, CUSTOMER and COLLECTION_POC. Shorter labels still work.


**Root cause**

```text
app_shell.dart:98-108 puts an unconstrained Row with Text('${user.username} • ${user.role}') in AppBar.actions, with no maxWidth or ellipsis. The actions spill left over the leading widget and take its taps.
```

**Suggested fix**


Cap the label width (ConstrainedBox around 140px with ellipsis), or show only the account icon below about 600px and put the username and role at the top of the popup menu.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/phone/shots/coll-after-hamburger-click.png; rt/ui-sweep/appbar/shots/coll-bar.png
```

**Screenshot:** [report/shots/D-12-1.png](report/shots/D-12-1.png)

## D-13 — Malformed client input (bad JSON, bad enum or number, non-numeric id or param, missing param) returns 500 with internal class names instead of 400

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** table-framework (also customers-products, invoices, disputes-notifications, promises, detail-history-dashboard)
- **Test cases:** POC-008, VAL-001, BLK-003, INV-031, DN-API-04, DN-API-18, PRM-O07, TF-012, TF-093, API-500-001

**Reproduce**

```text
Send GET /api/invoices/abc, /api/invoices?size=abc, /api/pocs/assignable?type=bogus or with no type, POST /api/products {price:'abc'}, POST with body '{bad json', /override {status:'FOO'}, or bulk ADD_POC params {pocType:'foo'} or {userId:'abc'}. Approve a dispute with update_amount amount:'abc'.
```

**Expected**


400 with a short message (AC-D9).


**Actual**


500 'Internal Server Error' with messages such as "Method parameter 'id': Failed to convert ... java.lang.Long", 'JSON parse error: Cannot deserialize value of type com.geneinvoice.poc.PocType', 'No enum constant com.geneinvoice.poc.PocType.FOO', 'Character a is neither a decimal digit number...'. These expose internal class and method signatures.


**Root cause**

```text
GlobalExceptionHandler.java:60-64 has a catch-all Exception → 500 that echoes ex.getMessage(). There are no handlers for HttpMessageNotReadableException, MethodArgumentTypeMismatchException, MissingServletRequestParameterException or HttpMediaTypeNotSupportedException. Parsing by hand also throws uncaught exceptions in CustomerController.java:136 (PocType.valueOf), BulkDtos.java:30 (Long.valueOf) and DisputeService.java:247,269 (new BigDecimal).
```

**Suggested fix**


Add 400 handlers (and 415 for media type), or extend ResponseEntityExceptionHandler while keeping the ApiError shape. Parse bulk params and dispute JSON defensively and throw BadRequestException. Stop echoing ex.getMessage() from the 500 handler.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/api-run1.log, api-run2.log; rt/verify-table-framework/api.js (size=abc 500); rt/verify-disputes-notifications/api.js
```

## D-14 — Bulk actions silently drop ids that are unknown or outside the caller's scope or filter

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** table-framework (also auth-users-roles, customers-products, invoices, payments-credit, disputes-notifications, permissions-scoping)
- **Test cases:** AUR-028, BLK-007, INV-027, PAYCR-034, DN-API-29, TF-091, PS-018

**Reproduce**

```text
Send POST /api/{users,customers,products,invoices,payments,notifications}/bulk with ids containing 99999999, or with another rep's or another user's record.
```

**Expected**


Every requested id appears in exactly one of succeeded, failed or skipped with a reason, and requested equals the number of ids sent (AC-D5, design §8, BulkResult javadoc).


**Actual**


{requested:1, succeeded:[own], failed:[], skipped:[]} for two ids, and requested:0 with every list empty when all the ids are excluded. Scope itself holds: no foreign record changed.


**Root cause**

```text
Each controller's resolveIds does req.ids().stream().filter(permitted::contains) before BulkExecutor.run (e.g. InvoiceController.java:154, UserController.java:233, ProductController.java:163, CustomerController.java:184, PaymentController.java:165, NotificationController.java:117). permitted is also capped at the first 5000 ids in sort order. BulkActionTest.java:155 asserts the drop.
```

**Suggested fix**


Keep the full requested list. Check permission with an id-in-list query plus the scope predicates, report excluded ids as skipped with 'Not found or outside your scope', and set requested to the number of distinct ids. Update BulkActionTest.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/api.js (requested:0 with every list empty); rt/verify-invoices (admin CANCEL [987654320,987654321] → requested 0)
```

## D-15 — The SALES_POC locked book applies only to lists and bulk: another rep's invoice can be read, edited and cancelled by id, and seats can be edited on customers outside the book

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** permissions-scoping (also invoices)
- **Test cases:** INV-026, PS-029, PS-034

**Reproduce**

```text
As SALES_POC s1 (locked to salesPocUserId:eq:s1), send GET /api/invoices/{s2's invoice}, PATCH its notes, and POST /{id}/cancel. Send GET /api/customers/{Y outside the book} and POST /api/customers/{Y}/pocs.
```

**Expected**


403 or 404, the same as the list and bulk paths, which exclude these records (§7, AC-D6, AC-C8).


**Actual**


GET, PATCH and cancel all return 200: the notes changed and the invoice became CANCELLED. Customer Y and its seats return 200, and a new seat was created and made primary. Bulk CANCEL of the same invoice returns requested 0.


**Root cause**

```text
InvoiceService.get (:152-161) checks only customer scope. update (:102) and cancel (:226) use getInternal (:163-167) with no ScopeResolver check. CustomerService.get (:120-126) and the POC seat endpoints (CustomerController.java:92-113, PocService.add :98) never apply forCustomers().
```

**Suggested fix**


Add a single-record scope check (e.g. ScopeResolver.requireInScope using the same predicates as the list, with id:eq) and call it from get, update, cancel and reassign on invoices, payments, customers and the seat endpoints. Return 404 or 403.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-permissions-scoping/recheck-result.json R3_salesOutsideBook; rt/verify-invoices/api.js (status CANCELLED, notes 'touched-by-s1')
```

## D-16 — Promise DTOs expose staff user ids to customers: createdByUserId and overriddenByUserId are often the Collection POC's own id

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** permissions-scoping
- **Test cases:** PS-023

**Reproduce**

```text
A Collection POC creates a promise for customer A and overrides it. As A's login, send GET /api/promises/{id} and the list.
```

**Expected**


No POC identity in customer payloads (AC-A8, design §6).


**Actual**


collectionPoc is null, but createdByUserId=417 and overriddenByUserId=417, which is the Collection POC's id. The audit endpoint hides the same actor (actorHidden). Disputes also expose resolvedByUserId, which is an admin id.


**Root cause**

```text
PaymentPromiseService.toDto (:510-529) nulls only collectionPoc when !canSeePoc. PromiseDtos.java:57,64 and DisputeDtos.java:27 are always filled.
```

**Suggested fix**


Null createdByUserId and overriddenByUserId (and resolvedByUserId) for customer-scoped callers. Add a test that no staff user id appears in any customer-facing payload.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-permissions-scoping/probe.js; recheck-result.json R8_actorIds
```

## D-17 — USER audit history (email, role, privileges) is readable by staff without USER_VIEW

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** permissions-scoping
- **Test cases:** PS-035

**Reproduce**

```text
As admin, update a user. As SALES_POC, COLLECTION_POC or CS_POC, send GET /api/audit?entityType=USER&entityId={id}, or entityId=1.
```

**Expected**


403, because USER_VIEW is ADMIN-only (§2).


**Actual**


200 with the before/after snapshots, including email and the full privilege list. GET /api/users/{id} is 403 for the same caller. Users can be enumerated by sequential id.


**Root cause**

```text
AuditController.java:34-35 includes USER and PRODUCT in SUPPORTED, and ensureCallerCanSee returns early for any staff caller (:97).
```

**Suggested fix**


Check type-level privileges for staff too: USER needs USER_VIEW, PRODUCT needs PRODUCT_VIEW, and the others need their *_VIEW plus book scope.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-permissions-scoping/recheck-result.json R5_userAudit {s1:200, hasEmail:true}
```

## D-18 — POC picker search is one keystroke behind, so a typed username doesn't filter the list

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** customers-products (also invoices, payments-credit)
- **Test cases:** UI-012, INV-046, PAYCR-045

**Reproduce**

```text
Open any POC picker (customer seat, invoice Sales POC, payment Collection POC), type a full username, and wait 2-5 seconds.
```

**Expected**


After the 250ms debounce the list shows only the match (AC-A3).


**Actual**


The list stays unfiltered and no q= request is sent. The match appears only after one more keystroke, which then shows the result for the previous text.


**Root cause**

```text
poc_picker.dart:44-49: the debounce calls setState on the outer State, which doesn't rebuild the dialog route. setDialogState at :120-123 runs before _search updates, so the Consumer at :128-131 reads a stale value.
```

**Suggested fix**


Keep the search text and debounce inside the dialog (its own StatefulWidget or a ValueNotifier), or call the captured setDialogState inside the debounce Timer.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/shots/b-picker-typed.png, b-picker-extra.png; rt/verify-payments-credit/shots/v045r-picker-3s.png
```

**Screenshot:** [report/shots/D-18-1.png](report/shots/D-18-1.png)

## D-19 — Desktop tables are sized to the full window, so the last columns and row actions sit off-screen even at 1920px

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** ui-sweep (also auth-users-roles)
- **Test cases:** AUR-057, UIS-03

**Reproduce**

```text
As admin at 1366x900 or 1920x900, open Users, Roles, Invoices, Customers and Payments. As a customer at 1366, open Invoices.
```

**Expected**


Tables fit the content area beside the 256px nav rail, with row actions visible.


**Actual**


At 1920 the invoice Open, Raise promise and Cancel actions, the users Edit action and the customers Open action are beyond x=1920. At 1366 the Users Active column, the Roles Privileges column and the customer's Status column are clipped. No scrollbar hints that the table scrolls.


**Root cause**

```text
data_table_scaffold.dart:239-240 uses minWidth: MediaQuery.sizeOf(context).width - 24, which ignores the NavigationRail (app_shell.dart:114-129).
```

**Suggested fix**


Use a LayoutBuilder and minWidth: constraints.maxWidth, and wrap the horizontal scroll in Scrollbar(thumbVisibility: true).


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/desktop/shots/admin-1920-invoices.png, cust-invoices.png; rt/verify-auth-users-roles/shots/057b-users-1920.png
```

**Screenshot:** [report/shots/D-19-1.png](report/shots/D-19-1.png)

## D-20 — Long reason or message text makes the Disputes and Notifications tables thousands of pixels wide

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-04

**Reproduce**

```text
Create a dispute with a long unbroken reason, then as admin at 1366 open #/disputes and #/notifications.
```

**Expected**


Reason and Message cells are capped in width and ellipsised after 2 lines.


**Actual**


The Reason and Message columns grow to the full text width. Disputes' Open action sits at x≈4227, and the Notifications Type column starts at x≈3707.


**Root cause**

```text
disputes_screen.dart:55-58 and notifications_screen.dart:58-61 put Text(maxLines:2) in a DataCell with no width constraint, and DataTable sizes the column to the text's intrinsic width.
```

**Suggested fix**


Wrap long-text cells in ConstrainedBox(maxWidth ~360), or add a maxWidth option to TableColumnSpec that the scaffold applies.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/desktop/shots/admin-disputes.png, admin-notifications.png
```

**Screenshot:** [report/shots/D-20-1.png](report/shots/D-20-1.png)

## D-21 — Roles with EXPORT_DATA but no *_MANAGE can't select rows, so 'Export selected' is unreachable

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** table-framework (also permissions-scoping)
- **Test cases:** UI-22, PS-046

**Reproduce**

```text
Open Products or Promises as CASHIER, Invoices as COLLECTION_POC, or Payments as SALES_POC.
```

**Expected**


A checkbox column and 'Export selected' for any role holding EXPORT_DATA (D.3).


**Actual**


No checkbox column and no selection toolbar, so there is no way to export, although the API allows it.


**Root cause**

```text
invoices_screen.dart:56, payments_screen.dart:61, products_screen.dart:51, promises_screen.dart:40 and customers_screen.dart:65 pass selectable: canManage, and Export exists only in the selection toolbar (data_table_scaffold.dart:175). disputes_screen and roles_screen already use canExport.
```

**Suggested fix**


Use selectable: canManage || canExport, and keep bulkActions gated on canManage.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/shots/ui22-cashier-products-1.png; rt/verify-permissions-scoping/shots/PS046-collection-invoices.png
```

**Screenshot:** [report/shots/D-21-1.png](report/shots/D-21-1.png)

## D-22 — Table schemas are cached across logout, so a customer who signs in after an admin is offered the Sales POC filter columns

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** table-framework
- **Test cases:** UI-23

**Reproduce**

```text
Admin opens #/invoices and signs out. A customer signs in on the same page, opens #/invoices and clicks Add filter → Column.
```

**Expected**


Only the columns the server publishes to the customer.


**Actual**


'Sales POC' and 'Sales POC name' are offered. A fresh browser correctly omits them. Together with D-08, the customer can then filter by rep from the UI.


**Root cause**

```text
table_providers.dart:38-42: tableSchemaProvider isn't autoDispose and doesn't depend on the user, and AuthController.logout (auth_controller.dart:62-65) invalidates nothing.
```

**Suggested fix**


Key user-dependent providers on the current user id, or invalidate tableSchemaProvider, tablePageProvider and tableSummaryProvider on login and logout.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/shots/ui23-customer-filter-columns-1.png
```

**Screenshot:** [report/shots/D-22-1.png](report/shots/D-22-1.png)

## D-23 — Date upper bounds (lte, between, yesterday, past, lastMonth) include rows stamped at 00:00:00 of the next day

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** table-framework
- **Test cases:** TF-040

**Reproduce**

```text
With invoices at 2026-09-15T00:00:00Z, filter invoiceDate:lte:2026-09-14, relative:yesterday, relative:past, and between:2026-09-14,2026-09-14.
```

**Expected**


Today's midnight rows are excluded.


**Actual**


All four include them (e.g. yesterday returns 3 instead of 1). An explicit ...59.999999Z bound is correct, while the server's own ...59.999999999Z is rounded up to midnight.


**Root cause**

```text
FilterPredicates.java:75-80 and ValueCoercion.java:49-54 use nextDay.atStartOfDay().minusNanos(1) with <=. Postgres stores microseconds, so the JDBC driver rounds the value up to the next day's midnight.
```

**Suggested fix**


Use exclusive bounds: < start of the next day, and split BETWEEN into >= lo AND < next-day start.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/table-framework/datebug.js; rt/verify-table-framework/api.js
```

## D-24 — A page past the end shows 'No invoices match this filter' and a garbled pager ('991–990 of 4')

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** table-framework
- **Test cases:** UI-12

**Reproduce**

```text
Open #/invoices?page=99&size=10&f=customerId:eq:<id> for a customer with 4 invoices.
```

**Expected**


Clamp to the last page or show a page-out-of-range state (AC-D11, AC-D4).


**Actual**


The tiles say 4 invoices, but the body shows the empty-filter state and the pager reads '991–990 of 4 · Page 100 of 1'.


**Root cause**

```text
data_table_scaffold.dart:193-200 treats page.isEmpty as the empty state regardless of totalElements, and table_models.dart:178-179 computes the row range without clamping.
```

**Suggested fix**


When the page is empty but totalElements > 0, move to the last page. Clamp lastRowNumber so it is never below firstRowNumber.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/shots/ui12-out-of-range-1.png
```

**Screenshot:** [report/shots/D-24-1.png](report/shots/D-24-1.png)

## D-25 — Malformed detail URLs (#/invoices/abc, /customers/abc, /payments/abc) show a blank grey error box

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** detail-history-dashboard (also invoices)
- **Test cases:** INV-048, DET-016

**Reproduce**

```text
Navigate in-app to #/invoices/abc, #/customers/abc, #/payments/abc or #/invoices/12abc.
```

**Expected**


A clean not-found state with Go back (AC-C8), as for #/invoices/999999.


**Actual**


The content area is a flat grey box (Flutter's release ErrorWidget), with no message and no back button.


**Root cause**

```text
router.dart:65, 88, 103 (and 119, 133) call int.parse on the path id inside GoRoute builders, and there is no errorBuilder.
```

**Suggested fix**


Use int.tryParse and render RecordUnavailable (or redirect) when it returns null. Add a GoRouter errorBuilder.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-detail-history-dashboard/shots/m5-__invoices_abc.png, h11-payments-abc.png; rt/verify-invoices/shots/abc-1.png
```

**Screenshot:** [report/shots/D-25-1.png](report/shots/D-25-1.png)

## D-26 — A payment's Payment Promise tab lists every promise of the customer, not the ones attached to that payment

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** payments-credit (also detail-history-dashboard)
- **Test cases:** PAYCR-054, DET-006

**Reproduce**

```text
Open #/payments/{id}?tab=promises for a customer with several promises, only some linked to this payment (compare with GET /api/promises/{id}.payments).
```

**Expected**


Only promises attached to this payment (C.3).


**Actual**


All the customer's promises are listed, including ones on invoices this payment never touched, plus a customer-level 'Raise promise' button.


**Root cause**

```text
payment_detail_screen.dart:169-178 builds PromisesTab(customerId) with no payment scope. PromiseScope has only customerId and invoiceId, and the API has no paymentId filter even though promiseRepository.findByPaymentId exists.
```

**Suggested fix**


Add a paymentId scope end to end (a controller link-table filter, PromiseScope.paymentId, and pass it from the payment screen). Hide 'Raise promise' there or pre-link it to the payment.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-detail-history-dashboard/shots/m1-payment-promises-tab.png; rt/verify-payments-credit/shots/v054-promise-tab.png
```

**Screenshot:** [report/shots/D-26-1.png](report/shots/D-26-1.png)

## D-27 — Users/Roles admin: a duplicate email, a duplicate role name, or deleting a role still in use returns 500 with raw SQL shown in the dialog

- **Severity:** medium  ·  **Kind:** API+UI  ·  **Area:** auth-users-roles
- **Test cases:** AUR-016, AUR-038, AUR-039

**Reproduce**

```text
POST or PUT /api/users with another user's email. POST /api/roles with the name 'ADMIN', or rename a role to 'VIEWER'. DELETE /api/roles/{id} for a role still assigned to a user. Or do the same through the Users and Roles dialogs.
```

**Expected**


400/409 'Email already exists', 'Role name already exists', or 'Role is assigned to N users'.


**Actual**


500 'could not execute statement [ERROR: duplicate key value violates unique constraint "uk..." ...] [insert into ...]', or a foreign-key violation for the role delete. The dialogs show the SQL verbatim; on the Roles dialog it is hidden below the privilege list. The data stays intact.


**Root cause**

```text
UserController.java:106-123 and :131 have no existsByEmail check. RoleController.java:96-101 and :106-111 have no name check, and :116-117 deletes with no usage check. GlobalExceptionHandler.java:60-63 returns the DataIntegrityViolation message as a 500.
```

**Suggested fix**


Add explicit email and role-name uniqueness checks (case-insensitive, excluding the same id on update) and a role usage check before delete. Map DataIntegrityViolationException to a generic 409. Show dialog errors near the Save button.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-auth-users-roles/shots/016b-2-after-save.png, 038b-2-after-save.png; recheck.js R11
```

**Screenshot:** [report/shots/D-27-1.png](report/shots/D-27-1.png)

## D-28 — A user with no email can't be edited, reactivated or re-roled from the UI; a blank email is stored as '' and collides on the unique constraint

- **Severity:** medium  ·  **Kind:** API+UI  ·  **Area:** auth-users-roles
- **Test cases:** AUR-017

**Reproduce**

```text
Create a user without an email through the API. In the Users UI, open Edit, toggle Active, and Save. Or POST /api/users twice with email ''.
```

**Expected**


The save succeeds; a blank email is stored as null.


**Actual**


PUT returns 500 'duplicate key ... Key (email)=() already exists', the dialog shows the SQL, and the user stays inactive. Only one blank-email user can exist system-wide. The verifier lowered the severity to medium because bulk Activate/Deactivate still works and entering an email is a workaround.


**Root cause**

```text
users_screen.dart:160,168 always send 'email': _email.text.trim(). UserController.java:117,131 store '' without normalising it, and User.email is unique.
```

**Suggested fix**


Normalise a blank email to null in create and update and validate the format with @Email. The UI should send null when the field is empty. Migrate the data with UPDATE users SET email=NULL WHERE email=''.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-auth-users-roles/shots/017b-4-after-save.png
```

**Screenshot:** [report/shots/D-28-1.png](report/shots/D-28-1.png)

## D-29 — Overlong text fields return 500 with SQL instead of a 400 field error (username >80, invoice notes >500, payment notes >300)

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** payments-credit (also auth-users-roles, invoices)
- **Test cases:** AUR-018, INV-030, PAYCR-021

**Reproduce**

```text
POST /api/users with an 81-character username. PATCH or POST an invoice with 501-character notes. PATCH or POST a payment with 301-character notes.
```

**Expected**


400 with a field error.


**Actual**


500 'could not execute statement [ERROR: value too long for type character varying(N)] [insert into ... / update ...]'. The limit value itself is accepted with 200.


**Root cause**

```text
CreateUserRequest (UserController.java:67-74), InvoiceDtos.java:18,26 and PaymentDtos.java:19,29 have no @Size annotations, and the PATCH bodies are not @Valid. The DataIntegrityViolation becomes a 500 in GlobalExceptionHandler.java:60-63. The UI notes fields have no maxLength.
```

**Suggested fix**


Add @Size(max) matching each column (username 80; email and fullName 120; invoice notes 500; payment notes 300), add @Valid on the PATCH bodies, and set maxLength on the UI fields.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-payments-credit/api-verify.js (301 → 500); rt/verify-invoices/api.js; rt/verify-auth-users-roles/api.js
```

## D-30 — Payment amounts with more than 2 decimals are accepted: 0.001 is stored as a ₹0.00 payment, and the POST response shows a different amount from the one stored

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** payments-credit
- **Test cases:** PAYCR-015

**Reproduce**

```text
POST /api/payments amount 10.555, then GET it. POST amount 0.001.
```

**Expected**


400 for more than 2 decimals or an amount below 0.01.


**Actual**


10.555 returns 200 and the response says 10.555, but 10.56 is stored and credited. 0.001 returns 200 and is stored as 0.00, a zero-amount payment, even though amount 0 is rejected. The verifier raised the severity from low to medium because of the zero-amount payment.


**Root cause**

```text
PaymentDtos.java:17 has only @Positive with no @Digits(fraction=2). The column has scale 2 (Payment.java:30-35), and PaymentController.java:80 maps the unrounded entity into the response.
```

**Suggested fix**


Add @Digits(integer=12, fraction=2) and @DecimalMin("0.01"), or setScale(2) in the service and reject 0. Apply the same to update_amount.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-payments-credit/api-verify.js PAY-16
```

## D-31 — Record payment: choosing a customer silently replaces a Collection POC the cashier already picked

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** payments-credit
- **Test cases:** PAYCR-044

**Reproduce**

```text
Open Record payment, pick POC B in the picker, then choose a customer whose primary POC is A.
```

**Expected**


The explicit pick is kept; the default only fills an empty field (US-A4).


**Actual**


The field changes to A with no notice.


**Root cause**

```text
record_payment_dialog.dart:138-143 resets _pocResolvedFor on a customer change, and _resolveDefaultPoc (:60-70) then overwrites _collectionPoc.
```

**Suggested fix**


Track whether the user touched the field, and apply the default only when the field is empty or untouched.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-payments-credit/shots/v044-a-poc-picked.png, v044-b-after-customer.png
```

**Screenshot:** [report/shots/D-31-1.png](report/shots/D-31-1.png)

## D-32 — Dispute replace_items accepts quantity 0 or negative, producing ₹0 or negative FULLY_PAID invoices

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** disputes-notifications
- **Test cases:** DN-API-15

**Reproduce**

```text
Approve an invoice dispute with appliedChangeJson {action:replace_items, items:[{productId, quantity:0}]}, and again with quantity -2.
```

**Expected**


400 'Quantity must be positive', which normal invoice creation already enforces.


**Actual**


200. The invoice total becomes 0.00 (or -100.00 with qty -2), status FULLY_PAID, and the amount paid is refunded to credit.


**Root cause**

```text
DisputeService.java:243-248 builds LineInput from JSON with no validation, and InvoiceService.replaceItems (~282-292) checks only unitPrice < 0.
```

**Suggested fix**


Reject quantity <= 0 and a missing or non-numeric productId or quantity in replaceItems and DisputeService. Consider rejecting a total <= 0.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-disputes-notifications/api.js (qty 0 → total 0 FULLY_PAID; qty -2 → total -100)
```

## D-33 — An override revives a CANCELLED promise, and clearing the override re-links the payment

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** promises
- **Test cases:** PRM-C03

**Reproduce**

```text
Create a promise, pay it (KEPT), and cancel it. POST /override {status:OPEN, reason}, then DELETE /override.
```

**Expected**


400: a cancelled promise cannot be overridden (cancel is final; PUT on it is already refused).


**Actual**


The override returns 200 OPEN, and the clear returns 200 KEPT with the payment re-linked. The UI hides Override for cancelled promises, so this path is API-only.


**Root cause**

```text
PaymentPromiseService.java:165-188 override() never checks whether the current status is CANCELLED. clearOverride (:191-205) then re-evaluates and re-links.
```

**Suggested fix**


Throw BadRequestException('A cancelled promise cannot be overridden') in override(), and guard clearOverride the same way.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/api.js C03
```

## D-34 — Promise totals double-count a payment shared by overlapping or general promises

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** promises
- **Test cases:** PRM-M03, PRM-M04

**Reproduce**

```text
(a) Two promises of 100 on the same invoice and one payment of 100. (b) Two general promises and one payment of 100. (c) An invoice-scoped promise and a general promise, with a payment aimed at the scoped invoice. Check GET /api/promises/summary.
```

**Expected**


The single payment is counted once (AC-B12).


**Actual**


(a) and (b): both promises are KEPT, and fulfilledAmount is 200 against 100 collected. (c) The unrelated general promise is KEPT with fulfilled 250, more than its own 75.


**Root cause**

```text
computeFulfilment (PaymentPromiseService.java:314-342) credits each promise with the full allocation. linkPayments (:299-311) links every payment to general promises. tiles() (:497) sums fulfilledAmount.
```

**Suggested fix**


Distribute each allocation across the live promises once (scoped promises first, then general ones by date, capped at what each promise still needs), and base the summary on distinct allocations.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/api.js M03, M04
```

## D-35 — A payment ticked to a promise but allocated to a different invoice shows as linked yet contributes 0

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** promises
- **Test cases:** PRM-L02

**Reproduce**

```text
Customer with an older INV-1 and a newer INV-2, and a promise on INV-2. POST /api/payments with promiseIds [P] and no invoiceIds, which is what the dialog sends when only the promise is ticked.
```

**Expected**


The payment counts toward the promise (it is applied to the promise's invoices first).


**Actual**


All of the money is allocated to INV-1. The promise stays OPEN with 'Linked payments: #N' and fulfilled 0, and it will go BROKEN on its date.


**Root cause**

```text
PaymentService.java:57-68 ignores the invoices of the selected promises. attachPayment (PaymentPromiseService.java:371-387) links the payment unconditionally, and computeFulfilment counts only allocations on the covered invoices.
```

**Suggested fix**


When promiseIds are given and invoiceIds are empty, target the promises' live invoices first. Otherwise reject links that contribute nothing, or drop them.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/api.js L02
```

## D-36 — The default Collection POC for a new promise can be a deactivated user

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** promises
- **Test cases:** PRM-L03

**Reproduce**

```text
Make a Collection POC a customer's primary, DELETE that user (it gets deactivated), then POST a promise without collectionPocUserId.
```

**Expected**


400 asking for an active POC, or another active seat is chosen.


**Actual**


200 with collectionPoc set to the inactive user, who will receive the 'promise broken' notifications. Passing the same user explicitly gives 400.


**Root cause**

```text
resolveCollectionPoc (PaymentPromiseService.java:534-542) returns primaryFor() without calling requireAssignable.
```

**Suggested fix**


Validate the defaulted POC, or skip inactive primaries and fall back to another active seat, and return 400 if none remains.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/api.js L03
```

## D-37 — After an override from a Promises list row, the row and tiles stay stale until the user navigates away

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** promises
- **Test cases:** PRM-UI11

**Reproduce**

```text
On #/promises, use a row's Override status action to set Kept with a reason.
```

**Expected**


The row and tiles update right away (AC-E2).


**Actual**


The API says KEPT, but the row still reads Open and the tiles still count it as Open, 4 seconds later too, until the user navigates away and back.


**Root cause**

```text
promises_screen.dart:140-147 invalidates only promiseDetailProvider, not the table page or summary providers.
```

**Suggested fix**


Give rowActions a refresh callback (the same _refresh the bulk path uses), or invalidate tablePageProvider and tableSummaryProvider.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-promises/shots/r2-ui11-6-after-wait.png, r2-ui11-7-after-nav.png
```

**Screenshot:** [report/shots/D-37-1.png](report/shots/D-37-1.png)

## D-38 — On phones the detail header breaks the invoice number mid-token and squeezes the subtitle

- **Severity:** medium  ·  **Kind:** UI  ·  **Area:** ui-sweep (also detail-history-dashboard)
- **Test cases:** UIS-06, DET-020

**Reproduce**

```text
At 400x820, open an invoice or payment detail.
```

**Expected**


The title stays on one line, or the trailing actions wrap below it (AC-C6).


**Actual**


The title wraps as 'INV-20 / 26091 / 5-0374' in a 75px column, with the subtitle over 3-4 lines. The status chip and Raise dispute take most of the row. The tabs are still reachable.


**Root cause**

```text
detail_scaffold.dart:177-209: the _Header Row has an Expanded title plus an unconstrained trailing Wrap, with no narrow layout.
```

**Suggested fix**


When the screen is narrow, put the trailing Wrap on its own line under the title.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/phone/shots/admin-invoice-top.png; rt/verify-detail-history-dashboard/shots/x0-invoice-400.png
```

**Screenshot:** [report/shots/D-38-1.png](report/shots/D-38-1.png)

## D-39 — The dispute target text shows money unformatted with a raw enum: 'INVOICE INV-… — 451234.50'

- **Severity:** medium  ·  **Kind:** API+UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-05

**Reproduce**

```text
Open #/disputes, a Disputes tab, or a dispute detail, as any role including a customer.
```

**Expected**


₹ with en-IN grouping (₹4,51,234.50) and a readable 'Invoice' label (AC-E3).


**Actual**


'INVOICE INV-20260915-0375 — 451234.50' and 'PAYMENT Payment #158 — 1000.00'.


**Root cause**

```text
DisputeService.java:207,210 concatenate BigDecimal.toString(), and disputes_screen.dart:39, dispute_detail_screen.dart:117 and disputes_tab.dart:92 prefix the enum name.
```

**Suggested fix**


Return structured targetNumber and targetAmount, and format them on the client with formatMoney and a human-readable label.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/ui-sweep/admin-desktop/shots/screen-disputes.png; rt/ui-sweep/cust-desktop/shots/dispute-detail.png
```

**Screenshot:** [report/shots/D-39-1.png](report/shots/D-39-1.png)

## D-40 — Paging and sort validation gaps: a huge page number overflows int and returns 500; an invalid sort direction is silently treated as asc

- **Severity:** low  ·  **Kind:** API  ·  **Area:** table-framework (also invoices)
- **Test cases:** INV-032, TF-012, TF-032

**Reproduce**

```text
GET /api/invoices?page=50000000&size=50; ?page=2147483647&size=10; ?sort=total,sideways.
```

**Expected**


An empty page or 400 for an out-of-range page; 400 for a direction other than asc or desc (AC-D9).


**Actual**


500 'first-result value cannot be negative : -1794967296'. 'sideways' returns 200 echoed as total,asc.


**Root cause**

```text
TableQueryExecutor.java:50 setFirstResult(page*size) is int arithmetic. TableQuery.java:39 treats anything other than 'desc' as asc. Severity lowered from TF-012's medium: the non-numeric page/size part of TF-012 is covered by D-13, and the overflow needs an absurd page number.
```

**Suggested fix**


Compute the offset as a long and return an empty page, or 400, above Integer.MAX_VALUE. Accept only asc or desc.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/api.js; rt/verify-invoices/api.js
```

## D-41 — CSV export ignores the requested sort for users, roles and products (rows come back in id or undefined order)

- **Severity:** low  ·  **Kind:** API  ·  **Area:** auth-users-roles (also customers-products)
- **Test cases:** AUR-031, PRD-005

**Reproduce**

```text
POST /api/users/export or /api/products/export with selectAllMatchingFilter and sort 'username,desc' or 'price,desc'.
```

**Expected**


Rows in the requested order, as the customers and invoices exports return them.


**Actual**


Rows come back in id order or an undefined order, whatever the sort.


**Root cause**

```text
UserController.java:213, RoleController.java:77 and ProductController.java:144 use findAllById(ids), which discards the order resolveIds computed.
```

**Suggested fix**


Re-order the loaded entities by the resolved id list, or query with the parsed TableQuery the way CustomerController.export does.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-auth-users-roles/recheck.js; rt/verify-customers-products/api-run2.log
```

## D-42 — Admin can set a 1-character password through the user create and update API

- **Severity:** low  ·  **Kind:** API  ·  **Area:** auth-users-roles
- **Test cases:** AUR-021

**Reproduce**

```text
PUT /api/users/{id} {password:'a'} (or POST with 'b'), then log in with that password.
```

**Expected**


400. Self-service change-password enforces 6 characters.


**Actual**


200, and the login succeeds.


**Root cause**

```text
UserController.java:133 checks only for non-blank, and CreateUserRequest.password (:71) has only @NotBlank. AuthController.java:73 enforces 6.
```

**Suggested fix**


Share one password-policy helper and enforce it on create and update.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-auth-users-roles/recheck.js
```

## D-43 — DELETE /api/roles/{unknown id} returns 200

- **Severity:** low  ·  **Kind:** API  ·  **Area:** auth-users-roles
- **Test cases:** AUR-041

**Reproduce**

```text
DELETE /api/roles/99999999.
```

**Expected**


404 'Role not found', as GET and PUT on roles and DELETE on users return.


**Actual**


200 with an empty body.


**Root cause**

```text
RoleController.java:116-117 calls deleteById with no findById first.
```

**Suggested fix**


Load the role with findById(...).orElseThrow(NotFound) before deleting, combined with the in-use check from D-27.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-auth-users-roles/recheck.js R13
```

## D-44 — Automatic primary-POC changes (promotion on remove, demotion on add-as-primary) are not audited

- **Severity:** low  ·  **Kind:** API  ·  **Area:** customers-products
- **Test cases:** POC-010

**Reproduce**

```text
Add cs1 (it becomes primary automatically), add cs2 with primary:true, then remove cs2. Read the customer's audit trail.
```

**Expected**


POC_PRIMARY_CHANGED rows recording cs1 losing and then regaining primary (AC-A7).


**Actual**


Only POC_ASSIGNED and POC_REMOVED rows; the primary changes are not recorded.


**Root cause**

```text
PocService.java:148-155 (promotion) and :187-195 (clearPrimary) save without calling auditService.
```

**Suggested fix**


Record POC_PRIMARY_CHANGED with before and after snapshots on both paths.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/api-run1.log
```

## D-45 — An invalid bulk ADD_POC request (pocType SALES, an inactive user) is reported as every row skipped instead of one 400

- **Severity:** low  ·  **Kind:** API  ·  **Area:** customers-products
- **Test cases:** BLK-004

**Reproduce**

```text
POST /api/customers/bulk ADD_POC ids [c1,c2] with {pocType:SALES} or with an inactive user.
```

**Expected**


One 400 for the invalid request.


**Actual**


200 with both rows skipped with the same reason. An unknown user is instead reported as failed, so the classification is inconsistent.


**Root cause**

```text
CustomerController.java:138-144 turns every BadRequestException from pocService.add into an IneligibleException, although the comment intends this only for 'already holds the seat'.
```

**Suggested fix**


Validate pocType and the user's assignability once before the loop, and convert only the already-assigned case to skipped.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/api-run1.log, api-run2.log
```

## D-46 — Bulk ADD_POC without POC_ASSIGN returns 400 instead of 403

- **Severity:** low  ·  **Kind:** API  ·  **Area:** customers-products
- **Test cases:** BLK-006

**Reproduce**

```text
Create a custom role with CUSTOMER_MANAGE but no POC_ASSIGN, then POST /api/customers/bulk ADD_POC.
```

**Expected**


403, as the single-seat endpoint returns.


**Actual**


400 'You may not change POC assignments'. No data changes.


**Root cause**

```text
CustomerController.java:128-130 throws BadRequestException instead of AccessDeniedException.
```

**Suggested fix**


Throw AccessDeniedException there.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/api-run1.log
```

## D-47 — The backend accepts an inactive product on a new invoice

- **Severity:** low  ·  **Kind:** API  ·  **Area:** customers-products
- **Test cases:** PRD-007

**Reproduce**

```text
Deactivate a product, then POST /api/invoices with that product on a line.
```

**Expected**


400; the UI already filters inactive products out.


**Actual**


200: the invoice is created with the inactive product.


**Root cause**

```text
InvoiceService.create (~69-70) and replaceItems (~282-283) never check p.isActive().
```

**Suggested fix**


Reject inactive products on new lines.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/api-run2.log (INV-20260915-0286)
```

## D-48 — An unknown invoiceId on a payment is silently ignored and the money becomes credit

- **Severity:** low  ·  **Kind:** API  ·  **Area:** payments-credit
- **Test cases:** PAYCR-012

**Reproduce**

```text
POST /api/payments with invoiceIds [99999999], or [validId, 99999998].
```

**Expected**


404 or 400 naming the missing ids, as an unknown customer or promise gets.


**Actual**


200. The unknown id is dropped and the amount goes to credit; the real unpaid invoice is untouched.


**Root cause**

```text
PaymentService.java:58-65 never compares the findAllById result size with the requested ids.
```

**Suggested fix**


Throw NotFoundException for the missing ids before any money moves.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-payments-credit/api-verify.js PAY-13
```

## D-49 — Cancelled invoices still offer 'Raise promise', which the backend then rejects

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** invoices
- **Test cases:** INV-047

**Reproduce**

```text
On the invoices list, look at the row actions of a CANCELLED invoice.
```

**Expected**


No Raise promise action.


**Actual**


The icon is shown, and using it gives 400 'is cancelled and cannot be promised against'.


**Root cause**

```text
invoices_screen.dart:166 checks only canPromise && balance > 0, and a cancelled invoice keeps its balance.
```

**Suggested fix**


Also require status != CANCELLED.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-invoices/shots/promise-1-list.png
```

**Screenshot:** [report/shots/D-49-1.png](report/shots/D-49-1.png)

## D-50 — Reference filter chips show the raw id ('Customer is 184') instead of the name

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** table-framework (also invoices)
- **Test cases:** INV-049, UI-25

**Reproduce**

```text
Open #/invoices?f=customerId:eq:<id>, as from a shared link.
```

**Expected**


A readable chip, e.g. 'Customer is <name>' (D.4).


**Actual**


'Customer is 184'.


**Root cause**

```text
filter_editor.dart:333-342 describeFilter joins raw values, and TableFilter keeps no label.
```

**Suggested fix**


Resolve reference labels through a small cached lookup, falling back to the id.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/shots/ui25-chip-1.png
```

**Screenshot:** [report/shots/D-50-1.png](report/shots/D-50-1.png)

## D-51 — The 'POC missing' badge on phone customer cards runs under the Open icon

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** customers-products (also ui-sweep)
- **Test cases:** UI-002, UIS-08

**Reproduce**

```text
Open #/customers at 400px width.
```

**Expected**


The badge wraps inside the card.


**Actual**


The badge is clipped ('POC mi…') under the action icon.


**Root cause**

```text
customers_screen.dart:107-116: the Row has no Flexible or Wrap.
```

**Suggested fix**


Use Wrap(spacing: 6) for the name and badge.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/shots/a-narrow.png
```

**Screenshot:** [report/shots/D-51-1.png](report/shots/D-51-1.png)

## D-52 — The 'Name is required' error stays after a valid name is typed on Customer Details

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** customers-products
- **Test cases:** UI-008

**Reproduce**

```text
Clear Name and Save, then type a valid name.
```

**Expected**


The error clears.


**Actual**


The red error stays until the next Save.


**Root cause**

```text
customer_detail_screen.dart:213-215: onChanged never clears _fieldErrors['name'].
```

**Suggested fix**


Remove the error in onChanged once the value is non-empty.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-customers-products/shots/d-typed.png
```

**Screenshot:** [report/shots/D-52-1.png](report/shots/D-52-1.png)

## D-53 — The DISPUTE_OPENED notification link /admin/disputes/{id} is not a frontend route

- **Severity:** low  ·  **Kind:** API  ·  **Area:** disputes-notifications
- **Test cases:** DN-API-31

**Reproduce**

```text
Open a dispute and read the admin's notification link.
```

**Expected**


/disputes/{id}, as the approved and denied notifications use.


**Actual**


'/admin/disputes/111'. The in-app screen rewrites it, but deep links and other clients get a dead route.


**Root cause**

```text
DisputeService.java:84.
```

**Suggested fix**


Use '/disputes/' + id, remove the client-side rewrite, and migrate existing rows.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-disputes-notifications/api.js
```

## D-54 — Dispute detail shows a raw DioException on 403 or 404

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** disputes-notifications
- **Test cases:** DN-UI-06

**Reproduce**

```text
As customer C1, open #/disputes/{C2's id} or #/disputes/99999999.
```

**Expected**


RecordUnavailable with Go back (AC-C8).


**Actual**


'Failed: DioException [bad response]: This exception was thrown because the response has a status code of 403…'. No data leaks.


**Root cause**

```text
dispute_detail_screen.dart:26 renders Text('Failed: $e').
```

**Suggested fix**


Use RecordUnavailable with notFoundMessage(e, 'dispute').


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-disputes-notifications/shots/ui06-other-dispute-r1.png
```

**Screenshot:** [report/shots/D-54-1.png](report/shots/D-54-1.png)

## D-55 — The bell badge isn't refreshed after bulk Mark read and stays stale until the 30s poll

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** disputes-notifications
- **Test cases:** DN-UI-16

**Reproduce**

```text
With 3 unread notifications, select them and choose Mark read → Confirm.
```

**Expected**


The badge drops to 0 immediately.


**Actual**


The badge still shows 3 for about 12-14 seconds.


**Root cause**

```text
data_table_scaffold.dart _refresh invalidates only the page and summary providers, and notifications_screen gives no hook to invalidate unreadCountProvider.
```

**Suggested fix**


Add an onBulkDone callback and invalidate unreadCountProvider.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-disputes-notifications/shots/ui16-after-confirm-r1.png
```

**Screenshot:** [report/shots/D-55-1.png](report/shots/D-55-1.png)

## D-56 — Summary tiles are refetched on every page change, so each page change makes two requests (AC-D1)

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** table-framework
- **Test cases:** UI-06

**Reproduce**

```text
On #/invoices, click Next page while recording network requests.
```

**Expected**


One request.


**Actual**


GET /api/invoices plus an identical GET /api/invoices/summary.


**Root cause**

```text
table_providers.dart:52-61: the summary family is keyed by a TableRequest that includes page, size and sort.
```

**Suggested fix**


Key the summary provider on the filters only.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/ui.js a
```

## D-57 — Page size is remembered per device, not per user

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** table-framework
- **Test cases:** UI-24

**Reproduce**

```text
Admin sets Rows to 50 and signs out; a customer signs in on the same browser.
```

**Expected**


The customer gets the default of 20 (D.1, design §9).


**Actual**


The customer inherits Rows 50.


**Root cause**

```text
table_providers.dart:70,90-98: the key 'table.pageSize.<entity>' doesn't include the user id.
```

**Suggested fix**


Include the user id in the key, or clear the stored sizes on logout.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-table-framework/ui.js b
```

## D-58 — A viewer sees POC seats as grey, disabled-looking chips

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** permissions-scoping
- **Test cases:** PS-045

**Reproduce**

```text
As VIEWER, open #/customers/{id}.
```

**Expected**


Read-only values with no disabled-looking inputs (AC-C2).


**Actual**


An outlined InputChip with greyed text.


**Root cause**

```text
customer_poc_editor.dart:155-167 renders an InputChip with no handlers, which Material draws in the disabled style.
```

**Suggested fix**


Render plain text or a normal Chip when the section isn't editable.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-permissions-scoping/shots/PS045-viewer-customer.png
```

**Screenshot:** [report/shots/D-58-1.png](report/shots/D-58-1.png)

## D-59 — Screen-reader users can't expand single-link History rows; activating the row follows the link

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** detail-history-dashboard
- **Test cases:** HUI-007

**Reproduce**

```text
With semantics enabled, activate a 'Dispute denied / Dispute #N' History row.
```

**Expected**


The row expands, and the link is a separate target.


**Actual**


It navigates to the dispute. Without semantics, a click expands the row as expected.


**Root cause**

```text
audit_history_panel.dart:353-376: the InkWell link inside the ExpansionTile subtitle has no Semantics boundary.
```

**Suggested fix**


Give each link its own Semantics(container: true, link: true) node.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-detail-history-dashboard/shots/h9-history-chevron-click.png, k-hist-off-chevron.png
```

**Screenshot:** [report/shots/D-59-1.png](report/shots/D-59-1.png)

## D-60 — On phones the Raise promise dialog wraps the date onto a second line and cuts the amount label

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-07

**Reproduce**

```text
At 400px, open Customer → Payment Promise → Raise promise.
```

**Expected**


Readable fields.


**Actual**


'2026-09-2' with the final '2' on a second line, and the label 'Promised amou…'.


**Root cause**

```text
promise_form_dialog.dart:156-188: two Expanded fields in a Row with no narrow breakpoint.
```

**Suggested fix**


Stack the fields when the dialog is narrow.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/phone/shots/admin-raise-promise-dialog.png
```

**Screenshot:** [report/shots/D-60-1.png](report/shots/D-60-1.png)

## D-61 — Phone list pages leave about 350px (roughly 1.3 cards) for rows

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-09

**Reproduce**

```text
At 400x820, open #/invoices and scroll.
```

**Expected**


The list gets most of the screen.


**Actual**


The tiles, filter bar and a two-row pager stay fixed.


**Root cause**

```text
data_table_scaffold.dart:141-222: tiles and the pager sit outside the scrolling area.
```

**Suggested fix**


Scroll the tiles with the content on narrow screens and use a compact pager.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/phone/shots/admin-invoices-scrolled.png
```

**Screenshot:** [report/shots/D-61-1.png](report/shots/D-61-1.png)

## D-62 — The sidebar highlights 'Dashboard' on Notifications and on the customer's own customer page

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-11

**Reproduce**

```text
Open #/notifications.
```

**Expected**


No item highlighted.


**Actual**


Dashboard is shown as selected.


**Root cause**

```text
app_shell.dart:135-148: _selectedIndex defaults to 0.
```

**Suggested fix**


Return null when nothing matches.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/desktop/shots/admin-notifications.png
```

**Screenshot:** [report/shots/D-62-1.png](report/shots/D-62-1.png)

## D-63 — Date-time formats differ between screens

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-12

**Reproduce**

```text
Compare the Payments list with the History tab and the dispute detail.
```

**Expected**


One format.


**Actual**


'15 Sep 2026, 9:10 PM' versus 'Sep 15, 2026 9:10 PM'.


**Root cause**

```text
audit_history_panel.dart:331 and dispute_detail_screen.dart:106 bypass core/format.dart:22.
```

**Suggested fix**


Use formatDateTime() everywhere.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/desktop/shots/admin-customer-history.png
```

**Screenshot:** [report/shots/D-63-1.png](report/shots/D-63-1.png)

## D-64 — The Customer Details top pane clips the POC editors mid-label with no scroll cue

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-13

**Reproduce**

```text
As admin at 1366x900, open #/customers/{id}.
```

**Expected**


The POC editors are visible, or the pane is clearly scrollable.


**Actual**


'Customer Success POCs' is cut in half, and Collection POCs are hidden below.


**Root cause**

```text
detail_scaffold.dart:154-172: a 5/5 split with no visible Scrollbar.
```

**Suggested fix**


Add Scrollbar(thumbVisibility: true), or rebalance the split or the field layout.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/desktop/shots/admin-customer-top.png
```

**Screenshot:** [report/shots/D-64-1.png](report/shots/D-64-1.png)

## D-65 — On phones the 'What should change?' dropdown text runs under the arrow

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-14

**Reproduce**

```text
At 400px, open an invoice and tap Raise dispute.
```

**Expected**


The selected option is ellipsised before the arrow.


**Actual**


The text overlaps the arrow.


**Root cause**

```text
dispute_create_dialog.dart:155-160: isExpanded isn't set.
```

**Suggested fix**


Set isExpanded: true and add an ellipsis.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/phone/shots/admin-raise-dispute-dialog.png
```

**Screenshot:** [report/shots/D-65-1.png](report/shots/D-65-1.png)

## D-66 — Dispute detail shows developer wording to users: 'INVOICE history', 'Proposed change (JSON)'

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-17

**Reproduce**

```text
Open #/disputes/{id} as a customer.
```

**Expected**


Readable labels such as 'Invoice history' and 'Requested change'.


**Actual**


A raw enum heading and a JSON label shown to customers.


**Root cause**

```text
dispute_detail_screen.dart:203 and :134.
```

**Suggested fix**


Use humanizeEnum and describe the proposal in plain language for customers; keep the JSON editor for admins.


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/verify-ui-sweep/desktop/shots/cust-dispute-detail.png
```

**Screenshot:** [report/shots/D-66-1.png](report/shots/D-66-1.png)

## D-67 — The dashboard shows staff without PAYMENT_MANAGE a 'My payments' button that opens all payments

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Test cases:** UIS-18

**Reproduce**

```text
Log in as VIEWER or SALES_POC and follow the dashboard's 'My payments' button.
```

**Expected**


The label is 'All payments' for staff.


**Actual**


'My payments' opens all 150 payments in the organisation.


**Root cause**

```text
dashboard_screen.dart:125-129: the label ignores isCustomer.
```

**Suggested fix**


Use paymentManage ? 'Record payment' : (isCustomer ? 'My payments' : 'All payments').


**Evidence**

```text
/private/tmp/claude-501/-Users-srinivasans-Git-DooD-Test-gene-invoice-main/320ba3a2-3a45-4782-b3a9-c7047d64db55/scratchpad/rt/ui-sweep/sales-desktop/shots/screen-payments.png
```

**Screenshot:** [report/shots/D-67-1.png](report/shots/D-67-1.png)

## D-68 — A dispute reason of 1,001–2,000 characters fails with 409 'This change conflicts with existing data'

- **Severity:** medium  ·  **Kind:** API  ·  **Area:** disputes-notifications
- **Found by:** medium re-check, 16 Sep 2026 (while setting up W-12)

**Reproduce**

```text
As a customer login, POST /api/disputes {targetType: INVOICE, targetId: <own invoice>, reason: <1,500 characters>}.
```

**Expected**


The dispute is opened: the request allows a reason of up to 2,000 characters.


**Actual**


409 'This change conflicts with existing data'; nothing is saved. Reasons of up to 1,000 characters work.


**Root cause**

```text
DisputeService.java:82-85 copies the reason into the admin notification, and Notification.message is
VARCHAR(1000) (Notification.java:33), so the notification insert fails and takes the dispute with it.
```

**Suggested fix**


Shorten notification text to fit, ending in '…'; the full reason stays on the dispute the notification links to.


**Evidence**

```text
scripts/verify-medium-fixes/ui/ui-results.json (W-12 steps)
```

## D-69 — A page past the end still reads 'Page 100 of 24' in the pager

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** table-framework
- **Found by:** medium re-check, 16 Sep 2026 (W-14)

**Reproduce**

```text
Open #/invoices?page=99 with 469 invoices at 20 per page.
```

**Expected**


The pager gives the real page count, e.g. 'Past the last page · 24 pages'.


**Actual**


The D-24 fix shows the 'This page is past the end' message and a working 'Go to last page', but the pager still reads '0–0 of 469 · Page 100 of 24'.


**Root cause**

```text
data_table_scaffold.dart:859 prints the requested page number whether or not it exists.
```

**Suggested fix**


When page >= totalPages, show the page count without a current page.


**Evidence**

```text
scripts/verify-medium-fixes/ui/ui-results.json (W-14)
```

## D-70 — Signing out fires a request that fails with 401 (the table schema is re-fetched for nobody)

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** table-framework
- **Found by:** medium re-check, 16 Sep 2026

**Reproduce**

```text
Sign in, open any list page, sign out, and watch the network log.
```

**Expected**


No API requests after the token is cleared.


**Actual**


GET /api/table-schemas/invoices is sent without a token and fails with 401. Nothing shows on screen.


**Root cause**

```text
table_providers.dart:40-45: tableSchemaProvider watches the current user id (the D-22 fix) and re-fetches
when it changes, including to null at logout (auth_controller.dart:62-64).
```

**Suggested fix**


Skip the fetch while nobody is signed in.


**Evidence**

```text
Browser console during the medium re-check (scripts/verify-medium-fixes/ui/)
```

## D-71 — The 'That page does not exist.' page has no sidebar or top bar

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Found by:** medium re-check, 16 Sep 2026 (W-15)

**Reproduce**

```text
Signed in as admin, open #/admin/disputes/1.
```

**Expected**


The message shows inside the app, like the 'That invoice does not exist.' pages.


**Actual**


A bare page with only the message and 'Go back'; the sidebar and top bar are gone.


**Root cause**

```text
router.dart:44-47: GoRouter's errorBuilder renders a plain Scaffold outside the ShellRoute.
```

**Suggested fix**


For signed-in users, send unknown paths to a catch-all route inside the shell.


**Evidence**

```text
scripts/verify-medium-fixes/ui/ui-results.json (W-15)
```

## D-72 — Table row checkboxes are missing from the accessibility tree

- **Severity:** low  ·  **Kind:** UI  ·  **Area:** ui-sweep
- **Found by:** medium re-check, 16 Sep 2026 (W-13)

**Reproduce**

```text
Open any list page as a role that can select rows and inspect the accessibility tree.
```

**Expected**


Each row checkbox is exposed with a label such as 'Select row'.


**Actual**


The checkboxes are drawn and clickable but not exposed, so the re-check had to click them by position and screen readers probably can't reach them.


**Root cause**

```text
The checkboxes come from Flutter's DataTable selection column (showCheckboxColumn in
data_table_scaffold.dart), which builds them itself: there is nothing in this codebase to label.
```

**Suggested fix**


Replace the built-in selection column with a leading column of our own, each cell a labelled
Checkbox ("Select row <name>"), and keep DataTable's `showCheckboxColumn` off.


**Left open on purpose (16 Sep 2026)**


The rest of the low defects were fixed in this session; this one was not. It needs its own
selection column in the shared table, which every list screen and the bulk and export flows
depend on (W-13 covers them), and the result can only be judged with a real screen reader —
neither the analyzer nor the semantics snapshots this session used would show whether it works.
Worth doing together with the other accessibility work (D-59) and a screen-reader pass.


**Evidence**

```text
scripts/verify-medium-fixes/ui/ui-results.json (W-13)
```

## Reported but not confirmed

| Case | Title | Why not a defect |
|---|---|---|
| TF-095 | Ineligible invoices in bulk CANCEL reported as failed instead of skipped | The verifier reproduced the behaviour but judged it intended. BulkActionTest (the partial-failure tests at lines 84-126) asserts that a paid or already-cancelled invoice lands in failed as the AC-D5 partial-failure example. Design doc line 214 uses 'ineligible' for rows outside the caller's scope, which are excluded. Every id is accounted for. |
| INV-028 | Bulk CANCEL reports paid/cancelled invoices as failed, not skipped | The invoices verifier confirmed this, but it conflicts with the TF-095 verdict on the same behaviour. I checked the repo and resolved it as not a defect: design doc §8 (line 214) defines 'ineligible' as out-of-scope, and BulkActionTest encodes 'failed' for a paid invoice. Wording consistency with products ('Already active' is reported as skipped) is optional polish. |
| PS-033 | SALES_POC sees every payment, promise and dispute | Reproduced, but it matches design §7. A Sales POC's book is defined only for invoices; payment and promise books belong to Collection POCs, and disputes carry no POC. Making this confidential is a product decision. |
| PS-036 | A role with PROMISE_MANAGE but no POC_ASSIGN can reassign a promise's Collection POC | Reproduced, but consistent with design §1 (POC_ASSIGN covers invoices, payments and customers, not promises) and AC-B8. Only a hand-made custom role can hit it; every seeded role with PROMISE_MANAGE also holds POC_ASSIGN. |
| HIST-006 | Staff user id (resolvedByUserId) in a customer-visible dispute audit snapshot | The same id is already returned by GET /api/disputes/{id} to that customer. DISPUTE_MANAGE is ADMIN-only, so the id is never a POC, and it can't be resolved to a name (GET /api/users/1 returns 403). The real POC-id leak on promises is covered by D-16. |
| DASH-006 | Dashboard Kept promise tile shows a count but no amount | This matches the requirement: Feature E line 230 lists 'open count and amount, kept, broken, broken amount'. |
| UIS-16 | Dashboard Kept tile has no amount (inconsistent with the Promises page) | The ui-sweep verifier confirmed it as an inconsistency, but it conflicts with DASH-006, and the requirement's suggested tile is 'kept' as a count only. Resolved as optional polish (see recommendations), not a defect. |
| UIS-15 | Two 'Raise dispute' actions on Invoice/Payment details | Requirement C.3 requires the Disputes tab to carry the create action, and the header button is the older entry point. Both work, so this is a design choice. |
