# FE-07b review

Definition of done, re-run by the reviewer: `flutter analyze` reported no issues, and `flutter test` passed 417 of 417 ("All tests passed!").

The slice covers every item in its §9 entry and §7.7.3:
- action cards and the email action dialog;
- the rendered preview;
- the Runs tab and run detail, with redaction, the partial note, Retry, 2 s polling for at most 2 minutes, and `statusAt`;
- dispute and promise assignees;
- the CUSTOMER dispute target, with Acknowledge and Close without change;
- the additive compose `PlaceholderButton`;
- `RunStatusChip`.

§8.8's acceptance tests are present and exercise the paths they name. The table, run and effect providers watch the view scope (`tablePageProvider` watches `viewScopeProvider`; `runDetailProvider` and `runEffectsProvider` watch `viewAsOfProvider`). Writes are gated on `!historical`. Hidden effects show only their kind, with no label, link, result or message.

No critical or high findings.

## Findings

### 1. [medium] Run now still never opens the queued run (§7.7.3)
- §7.7.3's `run_now_dialog.dart` row says: "POSTs /run; shows 'Queued' and opens the run".
- FE-07a deferred the "opens the run" part to FE-07b (LOG-frontend FE-07a: "Opening the run detail dialog belongs to FE-07b's run_detail_dialog.dart, which can replace this").
- FE-07b left `run_now_dialog.dart` unchanged. Its done state has only "Open rule", which runs `router.go('/automation/${widget.ruleId}')` (`run_now_dialog.dart:183-189`). That lands on the Rule tab, not on the run and not even on `?tab=runs`.
- The editor's Run now (`rule_editor_screen.dart:486-492`) only refreshes the rule afterwards.
- So the spec step is unmet in both slices, and the user has to find the run by hand.
- Fix: in the done state, offer "Open run", which pops and calls `showRunDetailDialog(context, runId: queued.runId, rule: …)`, or at least go to `/automation/{id}?tab=runs`.

### 2. [low] "Created by rule" chip links viewers without AUTOMATION_VIEW to a page the router refuses
- `dispute_detail_screen.dart:196-203` always renders an `ActionChip` that calls `goGuarded(context, '/automation/${d.ruleId}')`.
- The `/automation/:id` route has `redirect: needs(const [Privileges.automationView])` (`core/router.dart:303-304`).
- A dispute handler with DISPUTE_MANAGE but no AUTOMATION_VIEW, which is the usual collections or cashier role, taps the chip and is redirected away.
- A rule outside the viewer's `RuleVisibility` 404s the same way.
- Fix: show a plain chip, or gate `onPressed` on `user.has(automationView)`.

### 3. [low] The action cards' "Days" field cannot be emptied: it snaps back to "0" and moves the cursor
- In `action_cards.dart:420-429`, `onChanged('')` sends `offsetDays: int.tryParse('') ?? 0`.
- The rebuilt card's `didUpdateWidget` then calls `_sync(controller, '0')` (`action_cards.dart:185-192`). Because `int.tryParse('')` (null) differs from 0 and `'' != '0'`, it sets `controller.text = '0'`, which also resets the selection.
- Backspacing to change "2" into "7" therefore gives "0" and then "07" or "70", depending on where the cursor lands.
- This affects both the task due offset and the promise date offset.
- Fix: treat an empty field like '-' (update local state only), or have `_sync` skip the write while the field is empty.

### 4. [low] The compose dialog's server error for subject or body stays after the user edits the field
- `_subjectError` and `_bodyError` are set from a 400 (`send_email_dialog.dart:328-329`) and cleared only at the start of the next send (`:300-301`).
- The subject listener (`:134-138`) and the body field never clear them.
- After the user fixes the unknown `{{X}}`, the red "Unknown placeholder {{X}}" message stays under the field until they press Send again.
- Fix: clear the matching error in the subject listener and in a body `onChanged` or listener.

## Checked and not a defect
- Region choices are intersected with each action's privilege via `regionsWith`, which honours the per-region privilege sets.
- Each card's `errors` mapping is correct for `actions[10]` versus `actions[1]`, and list-level or out-of-range errors show under the section.
- `actionFor` drops only Invoice and Payment placeholders, RECORD-level tokens, `INVOICE_DUE_DATE`, foreign amount fields, cancel and void, and `linkRecord`. It returns the same instance when nothing changes, so the confirm appears only when needed.
- The runs tab invalidates exactly the `TableRequest` the scaffold watches, and a test proves it stops polling.
- Retry follows §6.14: FAILED only, live only, `confirmOwnershipChange` only for a non-owner admin on a MANUAL run.
- The compose dialog keeps every existing key, label and field order, and `email_compose_test.dart` is unchanged and green.
- Assignee Edit is gated on DISPUTE_MANAGE in the dispute's region and `!historical`. On promises it follows `canManage`, which is already `asOf == null`.

## About the relayed status question
This reviewer is one build step and cannot see how many waves remain, or the run's token usage and API cost. The orchestrating session has to answer that.
