# E2E triage, round 4

I checked both defects against the working tree. Both are genuine and neither is rejected. The code shows each root
cause directly, so I did not start a private env (no PIDs to clean up).

- RGN-R4-1 (medium, backend). This is not a regression of any code change. It is an incomplete application of the
  RGN-R3-1 fix: that fix routed `current` and `blockedReason` through `scope.canSeeRecord(pc)`, but `canSeeRecord`
  itself asks about the wrong region. `PendingChangeScope.canSeeRecord` (lines 121-130) and the create branch of
  `isVisible` (line 116-117) pass `pc.getRegionId()` to `ScopeResolver.requireVisible`. That is the region the change
  was asked in, and it is frozen. For a FULL tier, `requireVisible` does no SQL (ScopeResolver.java:276-277). It only
  checks `tier(kind, VIEW, South)`, so a South-only approver passes even though the invoice now lives in North. The
  by-id reads (`InvoiceService:409`, `CustomerService:176` and the rest) pass the record's own region
  (`Region.idOf(inv.getRegion())`), which is why the invoice and the customer are 404 for her while the approval shows
  their live values.
- TSK-R4-1 (low, frontend). This is not a regression. The r3 F1 fix (FIXLOG-frontend "E2E r3 F1") kept
  `focusNode?.requestFocus()`, but none of the three call sites passes a `focusNode`: `action_cards.dart:357`,
  `email_action_dialog.dart:280` and `send_email_dialog.dart:380`. So nothing gives the field its focus back. On web,
  a mouse press on the `{}` button unfocuses the TextField. After that, the popup route restores focus either to
  nothing (FLUTTER-VIEW) or to the button (with semantics on). The r3 triage assumed the route hands focus back to the
  field. The tester shows that it does not do so on a mouse flow. The r3 intent is still unmet: the caret after the
  token is preserved, but the field that should own it is not focused.

Streams: B1 and F1 are independent. There is no API contract change. `blockedReason` gains no new strings: "You no
longer have access to this record" already exists and the frontend shows it verbatim.

Isolation for every package: never touch the user's services. Use only gi-e2e-db (:18432, e2e_* databases) and ports
18100-18299.

## Backend

### B1: an approval's record visibility follows the record's own region, live and as of a date [RGN-R4-1]
File: `backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java`.

1. Add a private helper `Long recordRegion(ScopeResolver.Kind kind, Long id, Long fallback)`. It returns the region
   the record belongs to now or, when an as-of point is bound, at that point.
   - Build it as one Criteria query over `kind.type()` through `Temporal.fromValid(q, kind.type(), cb, where)`.
     Select `root.get("region").get("id")` where `root.get("id") == id`. Version rows (`VersionRow.id`,
     `InvoiceVersion.region`, and the others) carry both attributes, so the same code works live and as of a date.
   - Selecting a scalar loads no versioned entity, so `LiveReadGuard` does not fire.
   - Use `setMaxResults(1)` and `getResultList()`. Do not use `getSingleResult`, because it throws and would mark the
     read-only transaction rollback-only.
   - If there is no row, return `fallback`. This covers a record that is gone, or one that did not exist yet at the
     as-of point. The fallback is `pc.getRegionId()`, so those cases behave exactly as they do today. `currentView`
     already returns null for a missing record.
2. In `canSeeRecord`, the THRESHOLD_CHANGE branch is unchanged, because a region's limits are about that region.
   The other two lines become:
   - `visible(CUSTOMER, pc.getCustomerId(), recordRegion(CUSTOMER, pc.getCustomerId(), pc.getRegionId()))`
   - `visible(kind, pc.getEntityId(), recordRegion(kind, pc.getEntityId(), pc.getRegionId()))`
3. In `isVisible`, the last line (a create judged by the POC, or by customer visibility) must use the customer's
   region from `recordRegion` in the same way. Do not change the maker, approver, `viewPrivilege(region)` or
   THRESHOLD_CHANGE gates above it. Approval stays a right of the region the change was asked in (SC-15): a South
   approver still opens a South change, but without now-values.
4. Do not change `PendingChangeViews.detail()` or `blockedReason`. RGN-R3-1's precedence must still hold: historical
   first, then `!visible`, then the existing rules. `canApprove`, `canReject` and `canWithdraw` stay unchanged.
5. Check `forList` for the same flaw. Its record subqueries are built from each kind's own region column, not
   `pc.regionId`. If a list or count path does compare the record against `pc.regionId`, align it the same way and
   say so in the FIXLOG.

Test: in `backend/src/test/java/com/geneinvoice/approval/ApprovalVisibilityTest.java`, or in
`region/CustomerRegionMoveTest.java` if its move helpers fit better, add
`aChangeLeftBehindByARegionMoveShowsNoLiveValuesToTheOldRegion`. Set it up as follows:
- South INVOICE_TERMS threshold 1000.
- sally: APPROVE/ALL in South only.
- vic: a South viewer with INVOICE_VIEW/CUSTOMER_VIEW (FULL) in South only, and no approval right.
- A South customer whose invoice is 2500. Its Sales and Collection POC is sam.
- sam PATCHes `dueDate` and `paymentTerm: CUSTOM`, and gets 202 with a pc id.

Assert:
- Before the move:
  - sally: 200, `current` not null, `changes[].current` filled, `blockedReason` null, `canApprove` true.
  - vic: 200 with `current` filled. This pins that "visible to everyone who can see the record" still holds.
- The admin moves the customer from South to North (200), and the pc goes CLOSED.
- sally, after the move:
  - GET /api/invoices/{id} returns 404.
  - GET /api/approvals/{pc} returns 200 with status CLOSED, `current` null, every `changes[].current` and
    `currentText` null, and `blockedReason` "You no longer have access to this record".
- vic, after the move: GET /api/approvals/{pc} returns 404.
- The admin, who holds North: 200 with `current` filled. So does a North-only viewer, if one is easy to seed.
- Move the clock forward. Then, as of the moment before the move:
  - sally gets 200 with `blockedReason` "Read-only in the as-of view" and `current` null.
  - vic gets 200, because the record was in South then.

Without the fix, the sally-after-move assertions fail. Keep the existing assertions of
`anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout` (the RGN-R3-1 pin) unchanged and passing. Run
`cd backend && mvn -q -o test`.

## Frontend

### F1: Insert placeholder gives the field its focus back, with the caret after the token [TSK-R4-1]
Files: `frontend/lib/features/automation/placeholder_button.dart`, `action_cards.dart`, `email_action_dialog.dart`,
`frontend/lib/features/email/send_email_dialog.dart`.

1. Make `PlaceholderButton.focusNode` required (`required FocusNode this.focusNode`, non-nullable). No call site can
   then forget it again. Update the doc to say that it is the text field's own node.
2. Each call site owns one `FocusNode` per placeholder field, created in state and disposed in `dispose()`. It passes
   that node to both the `TextField(focusNode: …)` and `PlaceholderButton(focusNode: …)`.
   - `action_cards.dart`: nodes are keyed by `field`, next to `_controller(field)`, and cover the task Title and
     Description and every other `_placeholderText`.
   - `email_action_dialog.dart`: the subject and body.
   - `send_email_dialog.dart`: the subject and body. The `field` widget passed into `_withPlaceholders` must be built
     with the same node.
3. In `_PlaceholderButtonState._onSelected`, after `insertPlaceholder` has set the value, give focus back after the
   menu route has finished popping, not only synchronously. Call `widget.focusNode.requestFocus()` in
   `WidgetsBinding.instance.addPostFrameCallback`, checking `mounted` first. Keep the synchronous call in
   `insertPlaceholder` as well. The popup's own focus restoration to the button runs during the pop, so the
   post-frame request must be the last one.
   - Keep the r3 F1 behaviour: capture on pointer down or `onOpened`, insert at the captured caret or selection, and
     keep the single-line newline stripping and `onInserted`.
   - Refocusing a single-line field on web may select all of it more than once (once for the synchronous request, once
     for the post-frame one). So `_guardCaret` must no longer be "once". For its one-second window it turns every
     whole-text selection back into the caret, as long as the text is unchanged. It ends on any text change, when
     the timer fires, or when the field loses focus.
   - `onCanceled` should also give the field its focus back, with its captured selection restored if the text is
     unchanged. A user who opens the menu and presses Esc keeps typing where they were.
4. Opening the menu from the keyboard must still work, and the menu must still show the same groups and items.

Tests (`frontend/test/automation_rule_editor_test.dart`, 'the placeholder button' group, plus
`send_email_placeholders_test.dart`):
- New testWidgets "after a pick the field has its focus back with the caret after the token":
  - Build the rule editor's task action card.
  - Enter "Chase " in Title, with the caret at 6.
  - Then call `FocusManager.instance.primaryFocus?.unfocus()` and pump. This models the web mouse press taking focus.
  - Tap the Title's `{}` and pick "Name", then pumpAndSettle.
  - Assert that the Title's EditableText `focusNode.hasFocus` is true, the text is "Chase {{Customer.Name}}", and the
    selection is collapsed at 23.
  - Simulate typing through `tester.testTextInput.updateEditingValue` with the text + " now" and the caret at the end.
    Assert that the controller reads "Chase {{Customer.Name}} now".
  - Do the same for Description ("Owes " + Balance + "!").
- Add the same focus assertion for the email subject in `send_email_placeholders_test.dart` and in the
  email-action-dialog test (line ~1168).
- The r3 F1 tests must still pass unchanged (the web refocus select-all simulation, and the caret or selection in the
  middle). Extend the select-all simulation to fire the select-all twice. The caret must survive both.
- Run `cd frontend && flutter analyze && flutter test`, then rebuild the web dist (`scripts/build-dist.sh`).
  Replay TAS-UI-25 on your own env (ports 18100-18299) as a mouse user, with semantics on and off. The expected result
  is `document.activeElement` INPUT or TEXTAREA after the pick, and "Chase {{Customer.Name}} now" / "Owes
  {{Invoice.Balance}}!".

## User decisions (defaults applied)
- A change asked in a region stays visible to that region's approvers after its customer moves out, because approval
  is a region right (SC-15). Its now-values are then hidden, with "You no longer have access to this record". A plain
  viewer of the old region, with no approval right, no longer sees the change (404), because it is visible only to
  those who can see the record. As of a date, the record's region at that date decides.
- When the record a change is about cannot be found (deleted, or not yet created at the as-of point), visibility falls
  back to the change's own region, which is today's behaviour.

## Harness fixes
None.
