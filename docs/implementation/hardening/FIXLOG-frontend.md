## F1 — Table scaffold honours schema sortability; export and bulk carry extraParams; rules list limits on a past date

### Fixed
- **CT-1 / CT-2 / PC-3 (refused sorts)**: `core/table/data_table_scaffold.dart` makes a header sortable only when
  `sortKey != null`, the live schema's `column(sortKey)?.sortable == true`, and the new optional
  `canSort(key)` allows it. Nothing sorts while the schema loads. As a second layer, `sortKey: 'status'` is
  removed from the rules list's Status column and `sortKey: 'requestedByUserId'` from the approvals list's
  'Requested by' column.
- **CT-3 (export ignores the view)**: `_runExport` and `_runBulkAction` now pass
  `queryParameters: Map.of(widget.extraParams)` to `dio.post` when extraParams is non-empty. The approvals
  export therefore carries `?view=AWAITING_ME|MINE|ALL`, which `ApprovalController` reads.
- **PC-2 (rules list on a past date)**, `features/automation/rules_screen.dart`:
  - `rulesPastSortRefused = {lastRunAt, nextRunAt, lastRunStatus}` is passed as `canSort` when
    `isHistoricalProvider` is true.
  - `rulesPastFilterFields = {name, enabled, status, suspendedReason, triggerKind, entityType, ownerUserId,
    regionIds}` is passed as the scaffold's new `filterFields`. That set limits the filter editor through the
    new `allowedFields` on `showFilterEditor`/`_FilterEditorDialog`, and hides quick filters outside it.
  - `rulesQueryForPast(query)` swaps a refused sort for `name,asc` and drops filters outside the set. On a past
    date the screen uses that query at once, so the list shown asks only what the server answers. It then
    pushes the same query through `RouteQuery` after the frame, so the URL matches. This covers both
    arriving with `asOf` already set and moving to a past date while on a run sort.
  - triggerKind stays sortable on a past date, per the contract.

### Rejected
- None. All five findings were re-verified against the current code. They describe the same underlying
  defects: CT-2 and PC-3 are one defect, and PC-2 includes CT-1.

### Tests (each fails with its fix reverted; checked by reverting it temporarily)
- `test/support/fake_backend.dart`: added `automationRulesSchema` and `approvalsSchema`, copying
  TableSchemas.AUTOMATION_RULES and APPROVALS. In them `status` (rules) and `requestedByUserId` (approvals)
  are `sortable:false`, as are the other columns the server marks notSortable.
- `test/rules_screen_test.dart`:
  - It now uses `automationRulesSchema`, and its rules route refuses what the server refuses: `sort=status`,
    plus last/next-run sorts and filters when `asOf` is set.
  - `_pump` gained a `location` parameter.
  - New group "sorts and filters the server answers":
    - The Status header's onSort is null, and tapping it sends no `sort=status`. Rule still sorts (`name,desc`).
    - `asOf` set, with route `sort=lastRunAt,desc&f=lastRunStatus:eq:FAILED&f=status:eq:ACTIVE`: every request
      goes out as `sort=name,asc` with only `status:eq:ACTIVE`. The URL is rewritten to match and no error
      shows. Last run, Next run and Status have no onSort, and Starts sorts as `triggerKind,asc`.
    - Moving to a past date while sorted by `nextRunAt,asc`: the list's request is `name,asc`, the URL
      follows, and no error shows.
    - Live, the filter editor offers lastRunStatus, lastRunAt and nextRunAt (control).
    - On a past date the filter editor offers exactly the 8 allowed fields: no Last run or Next run.
- `test/approvals_test.dart`:
  - The list group's schema route now answers `approvalsSchema`.
  - New: the 'Requested by' header has no onSort, and tapping it sends no `requestedByUserId` sort.
    'Requested' has onSort, and Amount sorts as `amount,asc`.
  - New: in 'Awaiting my approval', ticking a row, 'Select all 30 matching this filter' and then 'Export
    selected' posts `/api/approvals/export` with `queryParameters['view'] == 'AWAITING_ME'` and
    `selectAllMatchingFilter: true`.
- Revert checks:
  - Scaffold ignoring `sortable`, with the sortKeys put back: 3 tests fail.
  - No export `queryParameters`: the export test fails.
  - No past-date query sanitising: 2 tests fail.
  - No `canSort`: 1 test fails.
  - No `filterFields`: 1 test fails.
  - Putting the sortKeys back while keeping the scaffold guard passes, which is expected: the guard alone
    blocks those sorts.
- **Baseline fixture change** (`test/list_columns_test.dart`, HEAD file): 'the Disputes list offers the
  Customer sort its schema publishes' used a schema with no columns, which the new strict scaffold rightly
  treats as "not sortable". The fixture now serves the disputes schema with `customerId` `sortable:true`,
  which is what the test's name and the backend (TableSchemas.DISPUTES) say. The test's assertion is
  unchanged.

### Notes
- When the date changes, the page provider for the *old* query may be re-asked once, because it watches the
  view scope. Its answer (a 400 for a run sort) is never shown, since the screen swaps to the sanitised query
  in the same frame. The test asserts that no error is shown, not that no stray request is sent. Every list
  shares this pattern in `tablePageProvider`, and changing it is outside F1.
- Editing an existing filter chip whose column is outside `allowedFields` would open the editor on a column
  missing from the dropdown. On the rules list this cannot happen, because those chips are dropped on a past
  date.

### Final counts
- `flutter analyze`: No issues found.
- `flutter test`: 428 passed, 0 failed. Before F1 there were 421; F1 adds 7 tests (5 rules, 2 approvals). Tap-miss warnings come from untouched tests (region selector, invoice
  form) and are not failures.

## F2 — Show 'Awaiting approval' on list rows with a held change

**Re-verified:** before the change, `grep -rn pendingApproval frontend/lib` found only the model parsers (invoice.dart, payment.dart, promise.dart, dispute.dart). No list cell, mobile card, PromiseCard or disputes tab read the flag. None of the four list screens passes `mobileCard`, so the phone cards are the scaffold's default. It builds them from the same column `cell`s (data_table_scaffold.dart `_cardList`), which means a fix in the Status cell also covers the phone cards. PromisesTab and DisputesTab load from `/api/promises` and `/api/disputes` (the list endpoints that carry the flag).

**Fixed**
- PC-1 (medium, B2), and CF-11 (low; same gap, now resolved in favour of showing the chip):
  - `shared/widgets/status_chip.dart`: new `PendingApprovalChip`. It uses the StatusChip style in the approval-pending orange, is labelled 'Awaiting approval' and has the tooltip 'A change is waiting for approval'. `PendingApprovalChip.beside(status, pendingApproval)` returns the status chip unchanged, or, when the flag is `true`, a `Wrap(spacing: 4)` with the status chip and the new chip.
  - It is used in the Status cells of `invoices_screen.dart`, `payments_screen.dart`, `promises_screen.dart` (added the status_chip import) and `disputes_screen.dart`. Through the default card, this also covers the phone cards. It is also used in `PromiseCard` (`promises/promises_tab.dart`) and in the ListTile trailing of `disputes/disputes_tab.dart`.

**Rejected:** none. CF-11's "needs user decision" is settled by the package instructions: show the chip, and keep the backend flag.

**Tests:** new `test/pending_chip_test.dart` (34 tests).
- Ten sites: the four lists at desktop width, the four lists at phone width (cards), PromiseCard via PromisesTab, and the disputes tab. For each site:
  - a row with `pendingApproval: true` shows exactly one PendingApprovalChip inside the row widget (DataTable, Card, PromiseCard or ListTile), with the label and tooltip;
  - a row with `pendingApproval: false` shows no chip;
  - a row without the flag shows no chip.
- For each of the four lists, the held row keeps its own status label beside the chip, in the same Wrap.
- Checked that the tests catch the bug: with `beside` temporarily returning the status chip alone, 14 tests failed (all 10 "says so" tests and all 4 "keeps its own status" tests). After restoring the fix, all tests pass.

**Final:** `flutter analyze`: No issues found. `flutter test`: 462 passed, 0 failed (no flaky tests seen).

## F3 — Money formatting in the approval detail, no Rule.* in the manual composer, names of people in reloaded rules

**Re-verified**
- CT-4 / CF-6: `_ChangesTab._value` rendered every number as `'$n'`, and the Current/Proposed tab pretty-printed the raw maps. Dart's `jsonDecode` turns `500.00` into `500.0`, so an approver saw "1234.5" or "500.0". Valid.
- CF-5: `PlaceholderButton` offered every group from `/api/automation/placeholders`. The backend catalogue for CUSTOMER/INVOICE/PAYMENT always ends with a "General" group holding `{{Today}}` and `{{Rule.Name}}` (PlaceholderCatalogue.java:141), and `offered(EmailEntityType)` rejects `Rule.Name` on a manual send. Valid.
- CT-6 / PC-6 / CF-10: `_people` in rule_editor_screen started empty and was filled only by picks made on the page, so a reloaded rule showed 'User #12'. Valid. The contract after B1 (`people: {"<userId>": "<name>"}` on the rule detail, live and asOf) is what the frontend now reads.

**Fixed**
- CT-4 + CF-6 (`features/approvals/approval_detail_screen.dart`):
  - New `_moneyField = RegExp(r'(amount|total|balance|price|credit|outstanding|threshold|limit)s?$', caseSensitive: false)`.
  - New `_withMoney(v, field)` makes a recursive copy of the JSON. A number under a money key (the map key, or, for list elements, the list's field) becomes a `formatMoney` string. Other values are unchanged.
  - `_value(String field, Object? v)` shows money numbers with `formatMoney` and other numbers as before. Nested values (invoice lines, a threshold's `limit.amount`) are pretty-printed from the `_withMoney` copy.
  - The Current/Proposed `JsonDiffView` now gets `_withMoney(c.current, '')` and `_withMoney(c.proposed, '')`.
- CF-5:
  - `features/automation/placeholder_button.dart`: new `bool Function(String key)? exclude`. It filters items by key (the token without its braces, via the new `PlaceholderItem.key` in automation_models.dart) and drops groups left empty. Both `hideWhenUnavailable` and `enabled` now look at the filtered groups.
  - `features/email/send_email_dialog.dart`: the composer passes `exclude: (k) => k.startsWith('Rule.')`. Rule editors are unchanged.
- CT-6 + PC-6 + CF-10:
  - `features/automation/automation_models.dart`: `Rule.people` (`Map<int,String>`, default `{}`) is parsed from `json['people']`. It is `{}` when absent. Keys are read with `int.tryParse`, not `int.parse`, so one malformed key is skipped instead of failing to load the whole rule.
  - `features/automation/rule_editor_screen.dart`: `_seed(rule)` does `_people.addAll(rule.people)`. The server's names win, and names picked this session stay for ids the rule does not list. On a past date, where `_seed` is not called, the read-only action cards get `{..._people, ...?rule?.people}`, so the snapshot's names show too. The live draft still gets `_people` itself, because ActionCards writes new picks into that map.
  - Fixtures: `test/support/fake_backend.dart` has no rule fixtures, so `people` was added to the rule fixtures that exist: `_rule(people:)` in automation_rule_editor_test.dart, and `'people': {}` in as_of_screens_test.dart `_ruleJson`.

**Rejected:** none. The backend side (USE_BIG_DECIMAL_FOR_FLOATS in PayloadCodec, and RuleDto.people) belongs to the backend stream and was not touched here.

**Tests** (8 new; each checked to fail with the fix temporarily reverted)
- approvals_test:
  - 'the change table shows money as money, and other numbers as they are':
    - `total` 1234.5 / 500.0 shows as `formatMoney(1234.5)` and `formatMoney(500)`, never '1234.5' or '500.0';
    - quantity 3/4 stays plain;
    - an invoice line's `unitPrice` shows as money inside the pretty JSON.
  - 'the record now and as proposed show money as money, however deep': `total` and a nested `limit.amount` are formatted, `quantity` is not.
  - Reverted: both tests fail.
- send_email_placeholders_test:
  - "offers today but not a rule's name…": the real catalogue shape (General group with Today and Rule.Name). The picker lists Today, General and Number but not 'Rule name', and Today inserts `{{Today}}`.
  - 'a group with nothing left to offer is not shown': a General group holding only Rule.Name disappears, with no divider.
  - Reverted (exclude line removed): both tests fail.
- automation_rule_editor_test, group 'the people a reloaded rule names'. The rule has a task assignee, email from and email to all `{type:USER,userId:12}`, and `people {"12":"Priya Rao"}`.
  - Live: the assignee chip, 'From: Priya Rao, or the owner' and 'To: Priya Rao, Customer emails' show, and 'User #12' appears nowhere.
  - The same on a past date (asOf=2026-09-01).
  - Picking Ann Admin (id 7, not in the server's people) and saving keeps 'From: Ann Admin'.
  - Unit test: absent `people` reads as `{}`, and `{"12": …}` parses to `{12: 'Priya Rao'}`.
  - Reverted (no merge, plain `_people`): 3 widget tests fail. With only the past-date merge reverted, the past-date test fails. With `_people..clear()..addAll(...)`, the keep-picks test fails.

**Final:** `flutter analyze`: No issues found. `flutter test`: 470 passed, 0 failed (462 before F3, plus 8). No flaky tests seen.

## F4 — Make region_selector_test deterministic

**Fixed:** PC-7, CR-2 and CF-12. All three are the same defect, and all three were still valid. `settled()` waited 20 ms of real time. On a scratch copy with the wait cut to 1 ms, 'every dashboard figure' failed: after `setRegions({2})` the `again` set was empty. 'the bell and the inbox badge' also failed (`[null]`, not `[null, '2']`).
- `test/region_selector_test.dart`: `settled()` is gone. The new helper `answered(Iterable<ProviderListenable<Future<Object?>>>)` does `Future.wait` over `container.read(f)`. Reading `.future` builds again any autoDispose provider the view scope made stale, so its request has gone out and its answer has come back by the time the future completes. Nothing depends on wall-clock time.
  - 'every dashboard figure': the list is now typed `<AutoDisposeFutureProvider<Object?>>`, so `.future` is readable. The test awaits `answered([for (final p in providers) p.future])` after the listens, and again after `setRegions({2})`.
  - 'the bell and the inbox badge': awaits `unreadCountProvider.future` and `inboxUnreadCountProvider.future` (both StreamProviders; `.future` is their first value), before and after `setRegions({2})`.
  - 'the counts answer live…': awaits `unreadCountProvider.future` before and after `setAsOf`. This negative check is now stronger. If the date made the count stale, the read would itself rebuild the count and send a second request, and the test would see it.
- `test/tasks_test.dart` ('the count is my open tasks…') and `test/approvals_test.dart` ('an approver gets the count…'): the two 10 ms delays in each are replaced by `await container.read(<countProvider>.future)`.
- approvals_test:938 (`Future<void>.delayed(Duration.zero)` in 'asks nothing of someone who cannot approve') is left alone. It orders by the event loop, not by wall-clock time, and the provider there is `Stream.value(0)` with no request.
- No assertion was removed or loosened. All three files are new in this build (untracked), not baseline files.

**Rejected:** none.

**Tests / proof**
- Before the fix: the 1 ms probe copy fails 2 of the 3 group tests, as described above.
- After the fix, no real-time delay is left in region_selector_test, so there was nothing to set to 1 ms. The file passed 20/20 runs. The tasks and approvals count tests passed 10/10 runs.
- Mutation checks. Each was reverted afterwards, and the lib files were diff-checked against backups.
  - `_dashboardGet` reads the view scope instead of watching it: 'every dashboard figure' fails (`again` is empty).
  - `unreadCountProvider` also watches `viewAsOfProvider`: 'the counts answer live' fails (2 requests).
  - `myTasksCountProvider` and `approvalsAwaitingMeCountProvider` read the view scope instead of watching it: both count tests fail (`[null]`).
- No lib code was changed. Only the test files changed.

**Final:** `flutter analyze`: No issues found. `flutter test`: 470 passed, 0 failed. No flaky tests seen.
