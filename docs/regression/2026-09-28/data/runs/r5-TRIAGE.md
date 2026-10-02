# E2E triage, round 5

I checked both defects against the working tree. Both are genuine and neither is rejected. The code shows each root
cause directly, so I did not start a private env (no PIDs to clean up).

- RGN-R5-1 (low, backend). Not a regression. It is a gap that r4 B1 left open: r4 moved `canSeeRecord` onto the
  record's own region, but `PendingChangeScope.isVisible` still refuses first at line 113
  (`!rights.has(pc.getOperation().viewPrivilege(), pc.getRegionId())`), before it ever asks `canSeeRecord`. `forList`
  does the same twice: in the record branches (`region.in(kindRegions.get(i))`, line 225, on the change's region) and
  in the separate `region.in(askedIds)` predicate (line 241, also on the change's region). A North-only reader
  (nico) holds nothing in South, so a change asked in South about an invoice that now lives in North is 404 for her,
  and missing from her list, even though she can open the invoice and its History tab links the change. DESIGN
  §5.11.9 VISIBLE has no change-region gate on the record branch for an existing record. The r4 plan listed "a
  North-only viewer: 200 with current filled" as optional, and the r4 test did not add it. Only decided changes can
  be affected, because a move closes every pending change on the customer. So the awaiting-me count and the summary's
  waiting totals do not change.
- ASO-R5-1 (low, frontend). Not a regression. `approval_detail_screen.dart` `_top().link()` (line 147) and the
  `titleTrailing` "Open <record>" button (line 76) never look at `blockedReason`. So when the server says "You no
  longer have access to this record", the Record and Customer cells and, for an approved change, the "Open …" button
  still route to a record that answers 404.

Streams: B1 and F1 run in parallel. The only contract is an existing one, and it does not change: when the
caller cannot open the record, `Detail.blockedReason` is exactly `"You no longer have access to this record"`
(`PendingChangeViews.blockedReason`, line 191). F1 keys on that string. B1 does not change that string, when it is
sent, or any response shape. After B1, a viewer who can see the record now (nico) no longer gets that reason, so
F1's links show for her, which is correct.

Isolation for every package: never touch the user's services. Use only gi-e2e-db (:18432, e2e_* databases) and ports
18100-18299.

## Backend

### B1: a change on a customer's record is visible to whoever can see that record now, in its own region [RGN-R5-1]
File: `backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java`.

The rule after this fix: the region the change was asked in (`pc.region`) governs only the maker path (SC-15),
the approver path, the threshold path and the "named POC of a create" path. The "can see the record" path
depends only on the record: for a create, that is the customer. It is judged in the region the record is in now,
or, as of a date, the region it was in then. This keeps r4 B1's intent (the old region's plain viewer vic loses the
change, and the old region's approver sally keeps it without now-values), and it adds the new region's viewers.

1. `isVisible(pc)`. Keep the as-of `requestedAt` check, the customer-login branch, the maker line (111) and the
   approver line (112) exactly as they are. Replace lines 113-118 with:
   - THRESHOLD_CHANGE: `return rights.has(APPROVAL_THRESHOLD_MANAGE, region);`. This is the same result as today.
   - Let `p = pc.getOperation().viewPrivilege()`.
   - Existing record (`entityId != null`):
     - `kind = ScopeResolver.Kind.valueOf(entityType)`
     - `at = recordRegion(kind, entityId, region)`
     - `return rights.has(p, at) && visible(kind, entityId, at);`
     - Do not gate on `rights.has(p, region)`.
   - Create (`entityId == null`):
     - If `rights.has(p, region)` and `recordPocUserId == me`, return true. This is the named-POC path and it stays
       on the change's region.
     - Otherwise: `at = recordRegion(CUSTOMER, customerId, region)`, then
       `return rights.has(p, at) && visible(CUSTOMER, customerId, at);`.
   - Compute `recordRegion` once per call: one scalar query, as in r4.
   - `canSeeRecord` stays as r4 left it (the record's own region). `PendingChangeViews.detail`/`blockedReason`
     precedence and `canApprove`/`canReject`/`canWithdraw` stay unchanged.
2. `forList(view)`. Restructure the `visible` predicate and remove the separate
   `root.get("region").get("id").in(askedIds)` predicate (line 241), keeping the `asked.isEmpty()` early return.
   - Change-region branches, all ANDed with `pc.region IN askedIds`:
     - the maker branch;
     - the approver branch;
     - the threshold branch;
     - the named-POC create branch: `entityId IS NULL AND recordPocUserId = me AND OR_i(operation = op_i AND
       pc.region IN createRegions(i))`.
   - Record branches, with no `pc.region` condition at all:
     - For each record kind i: `entityType = k AND entityId IS NOT NULL AND entityId IN ids(k.type(), kindScope_i
       plus [record.region.id IN kindRegions(i)])`.
     - For each create op i: `entityId IS NULL AND operation = op_i AND customerId IN ids(Customer, customers plus
       [record.region.id IN createRegions(i)])`.
     - The scopes are `Reach.SELECTED`, so they are already narrowed to the asked regions (ScopeResolver line 178),
       and `Temporal.subquery` makes them as of the date. Version rows carry `region`, as r4 established.
     - Add the extra region predicate by passing `ids()` a list of predicates, for example an overload
       `ids(type, scope, extra)`, or a new `Scope` with the extra `PredicateFactory` appended. Do not mutate the
       scope's list.
   - The AWAITING_ME, MINE and as-of `outstanding` predicates stay as they are.
   - The `regionId` column filter and `Brief.regionId`/`regionName` still mean the region the change was asked in.
     Write this in the javadoc.
3. Update the class javadoc and the `forList` javadoc so that they state the rule above.
4. FIXLOG: note that `summary()` and `awaitingMeCount()` are unaffected live, because a move closes every pending
   change, so record region and change region can differ only on decided changes.

Test: extend `ApprovalVisibilityTest.aChangeLeftBehindByARegionMoveShowsNoLiveValuesToTheOldRegion` (the r4 pin).
Keep every existing assertion. Add nico: `staffIn("nico", "VIEWER", north, AccessLevel.READ)`, a READ/ALL grant in
North only.

Assertions to add before the move:
- nico: GET /api/approvals/{pc} returns 404.
- nico's list (`list(nico, null)`) does not contain the pc.

Assertions to add after the move:
- nico: GET /api/invoices/{id} returns 200.
- nico: GET /api/approvals/{pc} returns 200 with:
  - status CLOSED;
  - `current` not null;
  - every `changes[].current` not null;
  - `blockedReason` null;
  - `canApprove` false.
- nico's list without regionIds contains the pc.
- nico's list with `filter=id:eq:{pc}` returns exactly [pc].
- nico's list with `regionIds={north}` contains the pc.
- sally's list (South) still contains the pc. This pins r4: an approver keeps the change.
- vic's list does not contain the pc, and vic's detail is still 404. This pins r4.
- The admin's list contains the pc with `regionIds={north}` (record branch) and with `regionIds={south}`
  (approver branch).

Assertions to add as of the day before the move:
- nico: GET /api/approvals/{pc}?asOf=before returns 404. The record was in South then.
- nico's list with that `asOf` does not contain the pc. This pins the as-of record region in the list subquery.
- The existing sally and vic as-of assertions stay.

Add a sibling test `aHeldCreateLeftBehindByARegionMoveFollowsTheCustomer`:
- Setup:
  - South INVOICE_CREATE threshold 1000.
  - sam (South maker) POSTs an invoice of 2500 for a South customer and gets 202, with a pc whose `entityId` is null.
  - The admin moves the customer to North, and the pc is CLOSED.
- Assert:
  - nico: GET returns 200, and nico's list contains the pc.
  - vic: GET returns 404, and vic's list does not contain the pc.
  - sally: GET returns 200 with `blockedReason` "You no longer have access to this record".
  - sam (maker, still holds South): GET returns 200.

Without the fix, the nico assertions fail with 404 and an empty list. Keep these passing and unchanged:
- `anApproverWithoutTheBookSeesTheChangeButNotTheRecordItIsAbout` (the RGN-R3-1 pin);
- `theRegionsAskedAboutNarrowTheList`;
- `theViewSaysWhoseChangesTheListIsAbout`;
- `aChangeOfLimitsIsSeenOnlyByTheRegionsApproversAndLimitSetters`;
- `aMakerWhoLostTheRegionNoLongerSeesTheirChange`;
- `aBookColleagueSeesItAndSomeoneOutsideTheBookDoesNot`.

Run `cd backend && mvn -q -o test` (all green), and `cd mail-service && mvn -q -o test`.
Then replay REG-155 on your own env (`env/up.sh b1 <n> --fresh`, ports 18100-18199):
- nico gets 200 on `/api/approvals/{pc}`;
- the `id:eq` list returns 1 row;
- the History-tab "Approval #N" link opens the change.

## Frontend

### F1: an approval whose record the viewer cannot open shows the record and customer as plain text [ASO-R5-1]
Files: `frontend/lib/features/approvals/approval_models.dart`,
`frontend/lib/features/approvals/approval_detail_screen.dart`.

1. In `approval_models.dart`:
   - Add `const String kNoRecordAccessReason = 'You no longer have access to this record';` with a doc comment that
     it is the server's `blockedReason` when the caller cannot open the record (§5.11.9, SC-15).
   - Add a `PendingChangeDetail` getter `bool get recordHidden => blockedReason == kNoRecordAccessReason;`.
2. In `approval_detail_screen.dart`:
   - The Record cell: `link(c.recordText, c.recordHidden ? null : (…existing route choice…))`, so that it renders as
     plain `Text`.
   - The Customer cell: `link(c.customerName!, c.recordHidden ? null : approvalEntityRoute('CUSTOMER',
     c.customerId))`.
   - The `titleTrailing` "Open ${c.recordText}" button: add `&& !c.recordHidden` to its condition.
   - Leave everything else unchanged:
     - the historical case ("Read-only in the as-of view"): links behave as today;
     - the Rule link;
     - the "Replaced by" approval link;
     - the customer-login `onBack` route. A customer never receives this reason, because an invisible change is a
       404 for them.
3. The notice line itself, which shows `blockedReason` verbatim, is unchanged.

Tests (`frontend/test/approvals_test.dart`, 'the approval page' group):
- New testWidgets "a change whose record the viewer can no longer see does not link to it".
  - Set up `_detail(status: 'CLOSED', blockedReason: 'You no longer have access to this record')`, with
    `customerName` set.
  - Assert:
    - the notice text is shown;
    - the record text and the customer name are found;
    - neither has an `InkWell` ancestor (`find.ancestor(of: …, matching: find.byType(InkWell))` finds nothing);
    - tapping each leaves the router location at `/approvals/41`.
- A second case: `status: 'APPROVED'` with a `result` (`{'entityType':'INVOICE','entityId':1203}`) and the same
  `blockedReason`. Assert that no "Open " button is found.
- Counterpart (pins the existing behaviour): the same two details with `blockedReason: null`. Assert:
  - the Record and Customer cells are InkWell links;
  - the Record link routes to `/invoices/1203`;
  - the APPROVED detail shows "Open …".
- Historical counterpart: with `blockedReason: 'Read-only in the as-of view'` and `statusAt: 'APPROVED'`, the "Open …"
  link still shows. This pins r1 F1.
- The r1 F1 as-of masking tests and the r2 F1 changes-tab tests stay green, unchanged.

Run `cd frontend && flutter analyze && flutter test`, then rebuild the web dist (`scripts/build-dist.sh`).
Replay `suites/asof/probe-link.js` on your own env (ports 18100-18299). Expect:
- sofia's `/approvals/{pcM}` shows 'Record: INV-…' and 'Customer: South S3 …' as plain text;
- a click leaves the URL at `#/approvals/{pcM}`.

## User decisions (defaults applied)
- A change about a record, or a held create about a customer, is visible to whoever can see that record (for a
  create, the customer) now. That is judged in the record's own region: as of a date, the region it was in then.
  The region the change was asked in still governs the maker (SC-15), approver, threshold-setter and named-POC paths.
  After a region move, the new region's readers see the closed change with its now-values. The old region's
  approvers keep it without now-values. The old region's plain readers lose it. This extends the r4 decision.
- In the approvals list, "which regions am I asking about" matches a change either by the region it was asked in
  (maker, approver, threshold and POC paths) or by the region its record is in (record path). So a closed change
  whose customer moved from South to North can appear under both South and North for someone who holds both.
  The `regionId` column and filter still mean the region the change was asked in.
- When the viewer cannot open the record ("You no longer have access to this record"), the approval page shows the
  Record and Customer as plain text and hides "Open …". It does so even in the rare case (a BOOK-scoped approver)
  where the customer alone might still be visible. The server gives no separate customer-visibility flag.

## Harness fixes
None.
