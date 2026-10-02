# FE-07a review (adversarial)

Definition of done re-run by the reviewer:
- `flutter analyze`: No issues found (log: build/fe07a-review-analyze.log)
- `flutter test`: 369 passed, 0 failed (log: build/fe07a-review-test.log)
- Frontend tree is identical to `refs/snapshots/FE-07a-impl`. The only non-frontend changes since FE-06 are from the backend stream (history/region), not from this slice.

Checked and found conforming: §6.14 request/response shapes (RuleRequest keys, run/enable/resume/disable/delete bodies, 202/200 run, preview body, placeholders, health, requeue); G11 (scope params only when set, via the interceptor; `evaluateAs` only when toggled); G17 (one UnsavedChanges slot, dialogs don't register); no `addFilter` in the condition builder; ownership confirmation on Run now, Enable and Resume for a non-owner admin, plus a 400 → confirm → resend fallback; no mutation offered on a past date (list row actions, New rule, Requeue and editor header are absent; Save is disabled; Preview still works); `ruleDetailProvider`, `runDetailProvider` and `runEffectsProvider` watch the date and call `checkAsOf`; the rules list and tiles go through `tablePageProvider`/`tableSummaryProvider`, which watch the scope; the §8.8 acceptance cases for the three new test files are present.

## Findings

### 1. [medium] A 409 on save can't be recovered from the page. "Undo your changes to see theirs" re-seeds the same stale version
- rule_editor_screen.dart:294-306: the catch shows the 409 text but never invalidates or refreshes `ruleDetailProvider(id)`.
- rule_editor_screen.dart:620-633: "Undo changes" calls `_seed(rule)` with the `rule` still cached in the provider, so `version` stays at the stale value.
- Scenario: another user saves rule 12 (v3 → v4). This user edits and saves, and gets a 409. They follow the message and press Undo. They see the same old values, not the other user's, with `version` still 3. They re-edit and save, and get a 409 again, indefinitely. Only leaving the page (autoDispose) or a header action (which calls `_afterAction` → `ref.refresh`) gets them out.
- Compare task_actions.dart:118-119, where a 409 calls `tasksChanged(...)` and says "It has been reloaded; try again".
- The test `a stale save is explained` (automation_rule_editor_test.dart:511-522) only checks the message text. It never checks that the rule is re-read or that a second save sends the new version.
- Fix:
  - On 409, `ref.invalidate(ruleDetailProvider(rule.id))`.
  - Make Undo re-seed from the refreshed value, for example by awaiting `ref.refresh(...future)` and then `_seed`.
  - Extend the test: a GET returning version 4 after the 409, Undo, then a PUT carrying `version: 4`.

### 2. [medium] Regions the editor cannot act in are shown locked and can't be removed, so such a rule can never be saved
- rule_editor_screen.dart:843 collects the rule's regionIds that are not among the user's choices ("others").
- rule_editor_screen.dart:874-881 renders them as selected `FilterChip`s with `onSelected: null`, "kept as it is". `_draft.regionIds` always keeps them.
- The server re-checks every listed region against the owner at save, and against the caller when a non-owner admin takes ownership (§5.10.7 "each must be a region where the owner holds every RequiredPrivileges privilege (400 fieldErrors.regionIds)"; "RequiredPrivileges and the rule's regions are checked against the caller").
- Scenario A: an owner loses the grant for region 3. Rule 12 has `regionIds [2,3]`, and the rule is not suspended because `recheckOwner` checks the privilege union. Any edit to rule 12 now gets 400 `regionIds`, and the owner cannot deselect region 3.
- Scenario B: an AUTOMATION_ADMIN tries to take over a rule restricted to a region they are not granted. The same dead end follows.
- The only way out is to delete the rule.
- Fix: when `editable`, let these chips be deselected (removable, not re-addable), and keep them locked only when read-only.

### 3. [low] A preview result survives a change of date or conditions and can read as the wrong day's answer
- rule_editor_screen.dart:1028 renders `_preview` whenever it is set. It is cleared only when the entity changes (rule_editor_screen.dart:216-222), never when `viewAsOfProvider` changes or the conditions, trigger or regions are edited.
- A live preview ("132 invoices match", with no date because the live `asOf` is null) stays on screen after switching to 30 Jun 2026. It then reads as that day's count, which is the "silently show live data as history" case D13 guards against.
- Fix: clear `_preview` and `_previewError` on any draft change, and in a `ref.listen(viewScopeProvider, ...)`.

### 4. [low] Region choices filter on a global privilege, so the filter does nothing
- rule_editor_screen.dart:838 uses `user.regionsWith(Privileges.automationManage)`. AUTOMATION_MANAGE is global (§4.1), and `_holdsIn` (auth_models.dart:109-110) returns `has(p)` for every region of a global privilege, so every granted region is offered, READ-only ones included.
- The server then rejects those with 400 `regionIds` (§5.10.7 requires every RequiredPrivileges privilege in the region, including the entity's VIEW and the action privileges).
- This follows §7.7.3 literally, so the fault is in the spec. The better client filter is `regionsWith(<entity VIEW privilege>)`, intersected with the action privileges once FE-07b adds actions.

## Not findings (accepted, logged deviations)
- Header actions are absent rather than disabled on a past date. Resume has no N up front (it is not in §6.14). The run dialog shows the queued run instead of opening a run detail (FE-07b). A new rule has no actions until FE-07b.
- The slice is about 5,200 lines, over the §9.0 bound. This was reported as §9.0 requires.
- The detail and preview providers do not refetch on a region change, following the existing I3 convention for by-id reads.
