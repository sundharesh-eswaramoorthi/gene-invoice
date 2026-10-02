
## E2E r1 F1 — Approval detail: as-of masking, multi-line change cells, readable labels and values

- **ASO-R1-2** (approval_detail_screen.dart). Root cause: the Decided/Note/Replaced-by rows, the failure notice and the 'Open <record>' link were keyed on the live `status`/`decidedAt`, not on the status as of the viewed day. Fix: `shown = historical ? (statusAt ?? status) : status`; `pendingThen = historical && shown == PENDING` hides Decided, Note, Replaced by and the failure notice. The 'Open <record>' button and the Record link's result route are used only when `shown == APPROVED`. This works whether or not the server masks the fields.
- **APP-R1-3** (_ChangesTab). Root cause: the DataTable used the default fixed row height (48 px). Fix: `dataRowMinHeight: 48, dataRowMaxHeight: double.infinity`, and each value cell is wrapped in `Padding(vertical: 8)` with maxWidth 280 kept, so JSON values wrap and show in full.
- **APP-R1-4** (approval_models.dart + _ChangesTab). `FieldChange` now parses `currentText`/`proposedText`, and a cell shows `text ?? _value(...)`. `_value` now formats: yyyy-MM-dd → formatDate, ISO instant → formatDateTime, UPPER_SNAKE → humanizeEnum. Money, number, bool and JSON are handled as before. Label fallback for older servers: salesPocUserId/collectionPocUserId/customerSuccessPocUserId become 'Sales POC'/'Collection POC'/'Customer Success POC' when the label ends in ' user id'.
- Tests (test/approvals_test.dart, 'the approval page' group), 6 new:
  - A historical view with statusAt PENDING hides Decided/Note/'Open '. The same data shown live shows them. A historical view with statusAt APPROVED shows the link.
  - dataRowMaxHeight is infinity, and the multi-line 'windowHours' cell/row fully contains its Text.
  - A proposedText name, formatDateTime, 'Custom'/'Net 30', and the old-server 'Collection poc user id'/'Sales poc user id' labels.
  - Against the pre-fix code (HARDEN-B5), 4 of the new tests failed.
- `flutter analyze`: 0 issues. `flutter test`: 476 passed.

## E2E r1 F2 — Small UI fixes: assignee tooltip, Run now wording, MANUAL dead events, invoice page sends only changed fields

- **TAS-R1-1** (`lib/features/tasks/tasks_screen.dart` TaskAssigneesText). Root cause: the tooltip read an empty `people` list as "nobody", but list endpoints describe role assignees briefly (people [], unresolved false). Fix: `unresolved` gives "<label>: nobody holds this now"; a non-empty people list names them; a brief row (empty people, not unresolved) is left out. With no lines left, the Text is returned with no Tooltip. The doc comment now says this. Tests (`test/tasks_test.dart`, group "the Assigned to tooltip on a list row (TAS-R1-1)"): a brief row has no tooltip and no 'nobody' semantics; unresolved says "nobody holds this now"; people [Sam South, Nora North] are named.
- **AUT-R1-4** (`lib/features/automation/run_now_dialog.dart`). The segment reads `Every ${entity.label.toLowerCase()} that matches`. I removed the unused `noun` local. "Up to N <plural>" stays plural. Tests (`test/rules_screen_test.dart`, group "Run now wording (AUT-R1-4)"): an INVOICE rule shows "Every invoice that matches" and a CUSTOMER rule shows "Every customer that matches". `_row` in the tests gained an `entityType` parameter, default INVOICE.
- **RR-2** (`lib/features/automation/rules_screen.dart` AutomationHealthCard, new `_deadEventAction`). A dead MANUAL event with a ruleId shows `TextButton(key: open-runs-<id>, 'Open runs')`, which calls `goGuarded('/automation/<ruleId>?tab=runs')`. A MANUAL event with no ruleId shows no button. Other kinds keep Requeue. The historical view shows nothing. Tests: MANUAL (ruleId 7) shows Open runs and no Requeue, and routes to /automation/7?tab=runs. CREATED still posts /events/11/requeue. MANUAL without a rule shows no button. On a past date there is no button.
- **APP-R1-2 (frontend)** (`lib/features/invoices/invoice_detail_screen.dart`). Root cause: `_save` always sent `'notes': _notes.text.trim()`, so a terms-only change on an invoice with null notes sent `notes: ''`. Fix: `_savedNotes` is seeded from `inv.notes ?? ''` and updated after a successful save. `notes` is sent only when the trimmed value differs. If notes, POC and terms are all unchanged, no PATCH is sent: dirty is cleared and a "Nothing has changed" snackbar shows. New `test/invoice_detail_save_test.dart` covers: a terms-only change, with null notes and with existing notes, sends paymentTerm and no `notes` key; a notes change sends `notes`, and a later terms change doesn't send it again; notes edited back to the saved value send nothing.
- Checked: with the lib fixes reverted, 10 of the new tests fail. With the fixes in place, `flutter analyze` finds 0 issues and `flutter test` passes (488 tests). No existing assertion was changed or removed.

## E2E r1 F3 — As-of banner exposed to assistive technology

**ASO-R1-1 (low, B3) — fixed.**
- Root cause (confirmed by test): the shell's page is a route of go_router's nested ShellRoute Navigator. That route's ModalBarrier wraps BlockSemantics around the page, and BlockSemantics drops the semantics of everything painted before it in the same semantics container. The shell body (rail + banner + page) had no container boundary, so it dropped the banner **and also the NavigationRail's destinations** on wide screens (probe before the fix: banner nodes=0, rail 'Customers' nodes=0, page nodes=1). The app bar sits in a different container, which is why the chip's 'Back to today' survived.
- Fix: `frontend/lib/shared/widgets/app_shell.dart` wraps the page in `Expanded(child: Semantics(container: true, explicitChildNodes: true, child: child))`, so the route's block stays inside the page's container. Column structure and children order are unchanged (the Semantics wrapper is always present, live or past), so the page keeps its place in the tree. `frontend/lib/shared/widgets/as_of_banner.dart` wraps the banner in `Semantics(container: true, liveRegion: true, ...)` so it is announced when it appears.
- Test: new `frontend/test/as_of_banner_test.dart` pumps the real AppShell under GoRouter + ShellRoute + one GoRoute (nested Navigator), as of 25 Sep 2026, with semantics on. It asserts a semantics label matching `Viewing .* read only`, that the banner's 'Back to today' node has a tap action, that the page and a rail item ('Customers') are in the tree, and that tapping 'Back to today' goes live and removes the banner node. A second test at 700px width checks the banner node and a drawer entry ('Invoices') after opening the drawer. Both tests fail without the fix (the banner label is found 0 times).
- `flutter analyze`: 0 issues. `flutter test`: 490 passed.

## E2E r2 F1 — Approval Changes tab: readable id lists and invoice lines when the server gives no text

**APP-R2-1 (low, B2), frontend part: fixed.**
- Root cause (confirmed): in `frontend/lib/features/approvals/approval_detail_screen.dart`, `_ChangesTab._value` fell through to `JsonDiffView.pretty` for any List or Map whenever `currentText`/`proposedText` was null. So `invoiceIds: [45]` showed as `[\n  45\n]` and invoice items showed `"productId": 1`. `_label` also passed an older server's 'Invoice ids'/'Promise ids' through unchanged.
- Fix: a new `_idListLabels` map sits beside `_pocLabels`. `_label` turns a label ending in " ids" (any case) into "Invoices" for field `invoiceIds` and "Promises" for `promiseIds`. `_value` gets three new cases before the JSON fallback:
  - An empty List shows '—'.
  - A List of only numbers, for a field ending in `Ids`, shows as '#45, #46'.
  - A List where every entry is a Map with `productId` and `quantity` shows one line per entry, joined by '\n': '<qty> × product #<id> at <formatMoney(unitPrice)>', or 'at list price' when there is no unitPrice. A `productName` string replaces 'product #<id>'.
  Server text (`currentText`/`proposedText`) is still preferred and shown as sent. The second tab (record now vs proposed JSON) is untouched.
- Tests: in `frontend/test/approvals_test.dart`, group 'the approval page':
  - 'id lists and invoice lines read as words, not JSON' (older-server shape). It checks that 'Invoices' is in the changes table, that 'Invoice ids' is gone, '#45', and the two-line items text. It also checks that no 'productId' or '[' appears in the DataTable. The 'Invoices' find is scoped to the DataTable because the sidebar has its own 'Invoices'.
  - 'server text for invoices and items is shown as sent' (fixed-server shape). It checks 'INV-20260927-0045' and '150 × E2E Widget at 100.00' exactly, and that '#45' is not shown.
- Checked: with the `_value`/`_label` changes removed, the first test fails; with them in place it passes. `flutter analyze`: 0 issues. `flutter test`: 492 passed. No existing assertion was changed or removed.

## E2E r3 F1 — Insert placeholder goes in at the caret the field had when the menu opened, and leaves the caret there

- RGR-R3-1 (A4, send-email Subject insert sometimes replaced the typed text). Root cause confirmed in
  `frontend/lib/features/automation/placeholder_button.dart`: `insertPlaceholder` read `controller.selection`
  at `onSelected` time; on web the popup route's exit refocuses the single-line field, which selects all,
  racing the pick (select-all first -> whole subject replaced; after -> subject left fully selected).
- Fix (one widget, so the send-email dialog, `action_cards.dart` and `email_action_dialog.dart` are all covered):
  - `PlaceholderButton` is now a `ConsumerStatefulWidget`. It captures `(text, selection)` on pointer down
    (a `Listener` around the `PopupMenuButton`), or in `onOpened` when nothing was captured (keyboard). The
    capture is cleared in `onCanceled` and after `onSelected`.
  - `insertPlaceholder(..., TextSelection? at)`: the pick uses the captured selection when the text is unchanged
    and the selection is valid, otherwise it goes at the end. A deliberate selection is still replaced. It now
    returns the new text. `focusNode?.requestFocus()`, the single-line newline stripping and `onInserted(next)` are kept.
  - A one-shot caret guard (controller listener) turns a whole-text selection of the inserted text back into the
    caret after the token. It is removed on the first text change, after the first correction, after 1s (Timer),
    on a controller swap and in dispose.
- Tests (`frontend/test/automation_rule_editor_test.dart`, placeholder group): the web refocus select-all simulated
  between opening and picking -> 'Hello {{Customer.Name}}', caret at 23; a select-all right after the insert
  is corrected once only; a unit test of `insertPlaceholder(at:)`. The existing cursor/replace-selection/single-line
  tests stay green. With the capture and guard disabled, the new widget tests fail.
- `flutter analyze`: 0 issues; `flutter test`: 495 passed. E2E web dist rebuilt (`build-dist.sh web`).

## E2E r4 F1 — Insert placeholder gives the text field its focus back with the caret after the token

**TSK-R4-1 (A4, low, UI).** Root cause confirmed: no call site passed `PlaceholderButton.focusNode`
(action_cards.dart `_placeholderText`, email_action_dialog.dart `_textWithPlaceholders`,
send_email_dialog.dart `_withPlaceholders`). So `focusNode?.requestFocus()` in `insertPlaceholder`
did nothing. On web a mouse press on `{}` unfocuses the field, and the popup route then hands focus
back to nothing (FLUTTER-VIEW), or to the `{}` button when semantics are on. Not a regression.

Fix (frontend only):
- `placeholder_button.dart`: `focusNode` is now `required FocusNode` (non-nullable), and its doc says it
  is the text field's own node. `_onSelected` keeps the r3 behaviour: capture on pointer down or
  onOpened, insert at the captured selection, strip newlines on single-line fields, call onInserted,
  and do the synchronous `requestFocus` inside `insertPlaceholder`. It also calls
  `widget.focusNode.requestFocus()` in a post-frame callback guarded by `mounted`, so the field wins
  over the route's own focus restoration.
- `onCanceled` now refocuses the field (immediately and after the frame). While the text is unchanged,
  it also restores the selection captured on press.
- `_guardCaret` no longer acts only once. For one second it turns every whole-text selection back into
  the caret while the text is unchanged. It ends on a text change, on the timer, or when the field
  loses focus after having had it. A focus-node listener handles that last case. Both listeners are
  removed in `_clearCaretGuard`, and `didUpdateWidget` clears them when the controller or the node
  changes.
- Call sites own one FocusNode per placeholder field. Each is created in State, disposed in
  `dispose()`, and passed to both `TextField/TextFormField(focusNode:)` and `PlaceholderButton(focusNode:)`:
  - `action_cards.dart`: `_focus` map keyed by field, next to `_controller(field)`. This covers task
    title and description, promise notes and dispute reason.
  - `email_action_dialog.dart`: `_subjectFocus` and `_bodyFocus`.
  - `send_email_dialog.dart`: `_subjectFocus` and `_bodyFocus`. `_withPlaceholders` now takes the node,
    and the `field` passed in is built with the same node.
- Keyboard-opened menus and the menu contents are unchanged.

Tests:
- `automation_rule_editor_test.dart`, actions (07b): "after a pick the field has its focus back with the
  caret after the token". Task card Title: 'Chase ', then unfocus (models the web mouse press), then
  {} → Name. The EditableText has focus, the text is 'Chase {{Customer.Name}}' and the caret is at 23.
  `testTextInput.updateEditingValue(+' now')` gives 'Chase {{Customer.Name}} now'. The same runs for
  Description: 'Owes ', Balance, then '!'. The saved action carries both. A Balance item was added to
  that group's catalogue.
- `automation_rule_editor_test.dart`, the placeholder button group: "after a pick the field has its
  focus back … (web mouse)", "closing the menu without a pick gives the field its focus and cursor
  back", and "a select-all after the field has lost its focus again stays". pumpField now shares one
  FocusNode between the field and the button.
- The r3 select-all simulation now fires twice after the pick, and the caret survives both. Its
  closing assertion is kept unchanged ("a select-all the user makes afterwards stays"). It now runs
  after the guard's one-second window, because the new spec makes every select-all inside the window
  go back to the caret. The r3 insert-at-caret and insert-at-selection tests are unchanged and pass.
- Email-action-dialog test (the email dialog: a role sends it …): unfocus before {} → Number. The
  subject's EditableText has focus, and the text and caret are asserted.
- `send_email_placeholders_test.dart`: new "after a pick the subject has its focus back with the
  caret after the token", including simulated typing.
- Without the fix (refocus calls disabled), all 6 focus tests fail. With the fix, all pass.

Verification: `flutter analyze` reports 0 issues. `flutter test`: all 500 pass. Web dist rebuilt with
`build-dist.sh web` (log e2e/dist-build-r4-F1.log). The backend jar was not rebuilt because the backend
agent is editing backend/ concurrently.

**Not done:** the TAS-UI-25 browser replay on a private env (area fixf1, ports 18150/18250). The
permission layer refused `env/up.sh`, so no env was started. document.activeElement after the pick,
with semantics on and off, is still unverified in a real browser. It should be re-run in the next E2E
round.

## E2E r5 F1 — Approval page: when the record is not accessible, Record/Customer are plain text and 'Open …' is hidden

Defect: ASO-R5-1 (low, UI, B2). Not a regression.

Root cause: in `frontend/lib/features/approvals/approval_detail_screen.dart`, the local `link()` helper in `_top()` and
the `titleTrailing` 'Open ${c.recordText}' button looked only at the route and ignored `blockedReason`. A South
approver whose record had moved to North saw the SC-15 notice 'You no longer have access to this record', yet the
Record and Customer cells were InkWell links. The Record link went to /invoices/{id}, which answers 404 ('That
invoice does not exist.').

Fix (frontend only; the contract is unchanged: the server's `blockedReason` is exactly that string when the caller
cannot open the record):
- `approval_models.dart`: added `const String kNoRecordAccessReason = 'You no longer have access to this record'`
  (doc: the server's reason when the caller cannot open the record, §5.11.9, SC-15), and
  `PendingChangeDetail.recordHidden => blockedReason == kNoRecordAccessReason`.
- `approval_detail_screen.dart`: Record cell `link(c.recordText, c.recordHidden ? null : <existing route choice>)`;
  Customer cell `link(c.customerName!, c.recordHidden ? null : approvalEntityRoute('CUSTOMER', c.customerId))`;
  the 'Open …' button's condition adds `&& !c.recordHidden`.
- Unchanged: the historical 'Read-only in the as-of view' case still links, and so do the Rule link, the
  'Replaced by' link and the customer-login onBack. The notice text is unchanged.

Tests (`frontend/test/approvals_test.dart`, group 'the approval page'):
- 'a change whose record the viewer can no longer see does not link to it': CLOSED with the reason. The notice,
  the record text and the customer name all show. Neither name has an InkWell ancestor. Tapping each leaves the
  router location unchanged, which is the harness's mount point for approval 41, standing in for /approvals/41.
  The page stays on Approval #41, and 'invoice 1203' never shows.
- 'an approved change whose record the viewer can no longer see offers no Open': APPROVED with result
  INVOICE/1203 and the reason. No 'Open ' text shows, and the Record cell is not a link.
- Counterparts, which were already passing and pin the existing behaviour:
  - 'a change whose record the viewer can see links to the record and customer': CLOSED with no reason. Both
    cells are InkWell links, and tapping the Record goes to /invoices/1203.
  - 'an approved change whose record the viewer can see offers Open'.
  - "on a past date, 'Read-only in the as-of view' still links to the record": statusAt APPROVED on a past date.
    'Open …' shows and both cells are links. This pins r1 F1.
- The r1 F1 and r2 F1 tests are unchanged and still green.
- To check the new tests fail without the fix, I made a temporary copy of the screen file with `c.recordHidden`
  replaced by `false`. Both new negative tests failed, and the file was then restored.

Verification: `flutter analyze` reports no issues. `flutter test` passed all 505 tests. A release
`flutter build web` compiled cleanly into a throwaway scratch directory (since deleted).
NOT done: `build-dist.sh` and the `suites/asof/probe-link.js` replay. This package's hard rules say not to
build into scratchpad/e2e and not to start servers or run the harness, and the backend agent is changing
backend/ at the same time, which `build-dist.sh` would also package. The orchestrator should run
`build-dist.sh web` and then the probe. Expected result: sofia's /approvals/{pcM} shows the Record and
Customer as plain text, and the URL stays at #/approvals/{pcM} after a click.
