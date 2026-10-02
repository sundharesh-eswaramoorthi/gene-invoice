# E2E triage, round 3

I checked all three defects against the working tree. All three are genuine and none is rejected. The code shows each
root cause directly, so I did not need a private reproduction (no env was started, no PIDs to clean up).

- ASO-R3-1 (high, REGRESSION of E2E r2 B2 / APP-R2-1): `PendingChangeViews.changes()` resolves names with
  `customerRepository`, `invoiceRepository`, `promiseRepository` and `productRepository.findAllById`. Customer,
  Invoice, PaymentPromise and Product are all versioned (`HistoryModel.TYPES`). When an as-of point is bound,
  `LiveReadGuard.onPostLoad` throws `AsOfException.unsupported()` on each of those loads. Changes whose rows carry
  only a status, or no ids, never reach those loads, which is why /1 and /3 still work. Customer is not "latent":
  it fails the same way the moment a `customerId` row appears in a change viewed as of a date.
- RGN-R3-1 (low): `blockedReason(...)` never consults `scope.canSeeRecord(pc)`, although `detail()` already
  computes that value for `current`. DESIGN §5.11.9 (line 2828, SC-15) requires "You no longer have access to this
  record". The value list in §6.11 (line 3236) omits it, but §5.11.9 is the specific rule and governs.
- RGR-R3-1 (low, not a regression; no earlier fix touched `placeholder_button.dart`): `insertPlaceholder` reads
  `controller.selection` when `onSelected` runs. On web, the popup route's exit hands focus back to the single-line
  field, and EditableText selects all on refocus. Whether that happens before or after `onSelected` is a race. If
  it happens before, the whole subject is replaced. If after, the subject is left fully selected, so the next
  keystroke replaces everything.

Streams: backend B1 and frontend F1 are independent. No API contract changes, except that `blockedReason` gains
one more possible string, which the frontend already shows verbatim (`approval_detail_screen.dart:173`).

Isolation for every package: never touch the user's services. Only gi-e2e-db (:18432, e2e_* databases) and ports
18100-18299.

## Backend

### B1: approval detail as of a date resolves names from history; blockedReason for a record the caller cannot see [ASO-R3-1, RGN-R3-1]
File: `backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java`. Both parts touch this one file,
which is why they form a single package.

Part 1, ASO-R3-1 (a regression; keep the APP-R2-1 intent):
- Inject `com.geneinvoice.history.VersionStore`.
- Replace the four `findAllById` look-ups in `changes()` (customers, invoices, promises, products) with one
  helper. Users are not versioned, so leave them as they are.
  - Live (`!AsOf.active()`): exactly as today, via the repository.
  - As of a date: read the version rows valid at `AsOf.current().instant()` with
    `versionStore.validAt(CustomerVersion|InvoiceVersion|PromiseVersion|ProductVersion.class, ids, instant)` and
    take the name from the version: `CustomerVersion.name`, `InvoiceVersion.invoiceNumber`,
    `"Promise of " + Money.format(amount) + " by " + promisedDate` from `PromiseVersion`, and
    `ProductVersion.name`. Do not rehydrate, because `Rehydration.at` throws on gaps.
  - For ids with no version at the point, fall back to the live name via
    `LiveReadGuard.allow(() -> repo.findAllById(missing))`. Anything still missing keeps today's fallbacks
    ("invoice #id", and so on).
  - Never catch an exception thrown through a `@Transactional` proxy. `ApprovalService.get` is
    `@Transactional(readOnly = true)`, so a caught exception would mark it rollback-only. The version-row read
    above cannot throw AS_OF_UNSUPPORTED.
- Keep every APP-R2-1 behaviour: the "Invoices"/"Promises" labels, invoice numbers in list order, the promise
  text, item lines "<qty> × <product> at <price>" / "at list price", "None" for an empty list, and dropping empty
  `*Ids` rows on a create. Masking and `current == null` as of a date stay untouched.
- Also check that nothing else on the `GET /api/approvals/{id}?asOf=` path loads a live versioned entity.
  `current` is already skipped when historical, and `result()` only reads ids.

Part 2, RGN-R3-1:
- `detail()` computes `boolean visible = !historical && scope.canSeeRecord(pc)` once. It uses `visible` for
  `current` and passes it to `blockedReason`.
- New precedence in `blockedReason`:
  1. historical → "Read-only in the as-of view"
  2. `!visible` → "You no longer have access to this record" (any status, any caller)
  3. then the existing rules: not pending or customer → null; mine → "You requested this change"; not an
     approver → "You don't hold the approval right in <region>"; otherwise null.
- `canApprove`, `canReject` and `canWithdraw` are unchanged. Approval is a region right (SC-15), so a book-less
  approver may still decide.
- A held create (entityId null) is judged by customer visibility, as `canSeeRecord` already does. A caller who
  can see the customer therefore gets no access message, even though `current` is null.
- Add the new string to the value list in the `PendingChangeViews` Javadoc/comments, if one exists.

Tests (they must pin both the r2 fix and the r3 fix):
- `ApprovalAsOfTest`: add a new test, `aHeldChangeNamingInvoicesPromisesAndProductsReadsAsOfADay`, using the
  AsOfScenario day clock.
  - Before day T:
    - hold an invoice create with items on product P;
    - hold a payment with `invoiceIds [X]` and, if the helpers allow, `promiseIds`;
    - hold a promise update setting `invoiceIds`;
    - approve one held invoice create.
  - After T, rename product P and the customer.
  - As of T, `GET /api/approvals/{id}` for each change must return 200, with:
    - `statusAt` PENDING, or APPROVED for the one decided before T;
    - canApprove, canReject and canWithdraw false;
    - blockedReason "Read-only in the as-of view";
    - `current` null;
    - "Invoices" proposedText = X's invoice number;
    - the items proposedText naming the product by its OLD name;
    - the customerId text, where present, with the old customer name.
  - Live `GET` for the same ids returns 200 with the NEW product name and the same invoice number.
  - Keep `ApproveFlowTest.heldPaymentAndInvoiceNameTheirInvoicesAndProducts` and
    `GatePaymentPromiseTest.heldChangesNameInvoicesAndPromisesNotIds` green unchanged.
- `ApprovalVisibilityTest.anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout`: assert
  `blockedReason == "You no longer have access to this record"` and that `canApprove`/`canReject` are still true.
  The approver who can see it gets `blockedReason` null. The same book-less approver, as of yesterday, gets
  "Read-only in the as-of view". A maker who can see their own held change still gets "You requested this change".
- Run `cd backend && mvn -q -o test`. Everything must pass.

## Frontend

### F1: Insert placeholder goes in at the caret the field had when the menu opened, and leaves the caret there [RGR-R3-1]
File: `frontend/lib/features/automation/placeholder_button.dart`. This fixes all three call sites: the send-email
dialog, `action_cards.dart` and `email_action_dialog.dart`.
- Turn `PlaceholderButton` into a `ConsumerStatefulWidget`. Capture `(controller.text, controller.selection)`
  when the user presses the button: wrap the `PopupMenuButton` in `Listener(onPointerDown: ...)`, and also capture
  in `PopupMenuButton.onOpened` if nothing was captured yet (keyboard activation). Clear the capture in
  `onCanceled` and after `onSelected`.
- `insertPlaceholder` gets an optional `TextSelection? at` parameter. `onSelected` passes the captured selection
  when `controller.text` still equals the captured text and the selection is valid. Otherwise the token goes at the
  end. A deliberate selection made before opening the menu is still replaced, so the existing "replaces the
  selected text" test must stay green.
- After setting the value, `focusNode?.requestFocus()` as now. Then guard against the late web select-all: add a
  one-shot controller listener that, while `controller.text == next`, turns a selection spanning the whole text
  (base 0, extent `next.length`, not collapsed) back into `TextSelection.collapsed(offset: start + insert.length)`.
  Remove the listener on the first text change, after the first correction, or after about 1 s (a `Timer`), and
  in `dispose`.
- Keep the single-line newline stripping. Keep `onInserted` receiving the new text.

Tests (in `frontend/test/automation_rule_editor_test.dart`, next to the existing placeholder tests):
- "a select-all while the menu is open does not replace the text": pump a field with 'Hello ' and the caret at 6,
  then tap the button and pumpAndSettle. Set `controller.selection = TextSelection(baseOffset: 0, extentOffset: 6)`
  to simulate the web refocus, then tap 'Name'. Expect 'Hello {{Customer.Name}}' and the caret collapsed at 23.
- "a select-all right after the insert is undone": after inserting, set
  `controller.selection = TextSelection(baseOffset: 0, extentOffset: controller.text.length)` and pump. Expect the
  selection to be collapsed after the token again.
- A unit test of `insertPlaceholder(..., at: ...)`.
- Keep "puts the one picked at the cursor", "replaces the selected text" and the single-line test green.
- Run `cd frontend && flutter analyze && flutter test`, then rebuild the E2E dist
  (`scripts/build-dist.sh`), as in earlier rounds.

## Rejected
None.

## Harness fixes
None. The failing cases (ASO-API-34/39/39c/39d, ASO-UI-09/10/10c/10d, the regions ext3 check and REG-UI-23)
assert the right behaviour and should pass unchanged after B1 and F1.

## User decisions (defaults applied)
- RGN-R3-1: `blockedReason` precedence is: as-of view → "Read-only in the as-of view"; the caller cannot see the
  record (or, for a create, its customer) → "You no longer have access to this record", at any status; then the
  existing reasons. Losing sight of the record does not remove the right to decide. Approval is a region right
  (SC-15, BE-15 "an approver in the region without book access sees it"), so canApprove/canReject stay as they are.
- ASO-R3-1: as of a date, the names in the Changes table (invoice numbers, customer, product and promise
  descriptions) are read as those records stood on that date. If history has no version then, the name falls back
  to today's name, then to "invoice #id" and the like. It never refuses the page. Live reads are unchanged.
- RGR-R3-1: a placeholder goes in at the caret or selection the field had when the placeholder button was
  pressed. A select-all caused only by focus returning from the menu is ignored, and after the insert the caret
  sits just after the token.
