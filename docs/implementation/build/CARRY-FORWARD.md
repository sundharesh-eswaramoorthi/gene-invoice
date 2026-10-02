# Carry-forward items for the final integration/review wave

Items deliberately left open by a slice (low severity, out of that slice's file list, or needing a cross-stream contract change).
The final wave must fix each one or record why not.

## From wave 1
- [BE-01 low] ClockReadGuardTest does not catch a clock read through a zone constant or variable.
- [BE-02 low] Bridge grant in UserController.create not atomic with the user save; PG: failure creating uk_regions_default partial index skips the whole region backfill.
- [BE-03/BE-04b low] Inbox and notifications `mark-all-read` ignore `regionIds` (housekeeping writes) while list/count honour them.
- [BE-03/BE-04b low] With `regionIds` set, the inbox drops emails about deleted records (`findLatestRegionId` now exists for the fix).
- [BE-03 low] Dashboard topPaying tie-break uses Java string order instead of DB ORDER BY name.
- [BE-04a low] Customer update/delete authorise against a region read before the customer lock.
- [BE-05 low] PUT /api/regions/{id} without `version` skips the stale check; deleting a region makes history of customers once deleted from it unreadable.
- [BE-05 gaps] APPROVE grants should require CHANGE_APPROVE + notify approvers (BE-13); RegionCreatedListener copying default thresholds (BE-15); grant replacement should re-check the user's automation rules (BE-17). Verify those slices did it.
- [FE-03 rejected, cross-stream] Move-region stale check can never run: CustomerDto has no `version` in §6.5 or the backend. Add `version` to CustomerDto (backend) and use it in the move dialog (frontend), or drop the check.
- [FE-03 low] Customer create dialog pre-selects the default region, so the required Region choice is never actually required.
- [BE-06 hand-off → BE-12] Add `TaskStatusName`/`TaskEntityTypeName` to `history/versions/EnumNames` (not in BE-12's Modify list), and extend `VersionTablesHaveNoConstraintsTest` (`VERSION_TABLES` + the exact enum-column list gain `TASK_VERSIONS`, `.STATUS`/`.ENTITY_TYPE`) and `VersionShapeTest`'s exact code list (TASK) — they will go red otherwise.

## From the frontend stream (FE-04..FE-07b) — cross-stream items
- [FE-04..07b] Every frontend feature was built against the §6 contract and the fake backend only. The final wave must exercise the real backend end to end (approvals, 202 pending, tasks, automation, assignees) and reconcile any contract drift recorded in LOG-backend.md.
- [FE-04/05 gap] `pendingApproval` on list rows is parsed but not displayed as a list column anywhere (only the record banner shows it). B2 says a pending change is visible to everyone who can see the record — decide whether a list badge is needed.
- [FE-06 → BE-12] Customer Tasks-tab badge uses GET /api/tasks/count?entityType=CUSTOMER&entityId=<id> but the tab lists by customerId; BE-12 should make the CUSTOMER count go by customerId (check it did).
- [FE-07b gap] A person chosen in a rule action shows as 'User #id' after reload: RuleRequest/RuleDto (§6.14) carry no display names. Consider adding names to RuleDto.
- [FE-07b] `region_selector_test` 'every dashboard figure' waits on real time and failed once under full-suite load (flaky) — make it deterministic.
- [FE-06, FE-07a, FE-07b] Slices exceeded the 2,500-line bound (2.9k, 5.2k, 4.25k) — give them extra scrutiny in the final review.

## From BE-09b review
- [BE-09b → BE-10 hand-off, §8.4 CF-23] The product email tab over HTTP (`GET /api/emails?entityType=PRODUCT&entityId=…&asOf=D`) still returns 400 AS_OF_UNSUPPORTED from the EmailController guard (list :67, get :76). BE-10 must add `occurredAt <= p` to `EmailService.list`/`get`, remove both guards, and assert in `AsOfTabsTest` that it returns 200 with only the emails up to D. The record lookup (`EmailTargets.requireVisible`) already answers at T for every type.
- [BE-09b, flaky, BE-07 code] `HistoryConcurrencyTest.twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink` failed once (a link was live but had no open version). Likely a race in HistoryWriter's link step; lock the owner or its link rows there. See LOG-backend.md BE-09b.

## From wave 2 (BE-06..BE-10)
- [BE-09b/BE-10, MUST FIX] Flaky: HistoryConcurrencyTest.twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink failed once in a full run (historyIsInStep: a promise link live with no open version). Could be a real race in link versioning (no owner lock) — investigate as a possible production bug, not just a test flake.
- [BE-08 low, unfixed] A failed first baseline whose chunks all committed leaves history_meta.started_at null (no lower bound); customer_region_moves.moved_at is not the move's history instant; rollback test only covers a veto raised before anything is written.
- [BE-09b rejected] EmailTargets.load at a past date for every type (recipient list is built from the live POC book) — decide whether as-of email tab needs it.
- [BE-10 low] Audit of a PRODUCT as of a date before it existed returns 200 [] instead of 404; derived audit entries carry today's state as of a date (D8, by design) — confirm acceptable.
- [BE-06 → BE-12] BE-12 must add TaskStatusName/TaskEntityTypeName to EnumNames and extend VersionTablesHaveNoConstraintsTest / VersionShapeTest lists for TASK_VERSIONS (check it did).

## From BE-11 review
- [BE-11 low → BE-17/BE-18 hand-off] `automation/event/AutomationEventRepository` ships only `findByHistoryTxIdOrderByIdAsc`. §5.8.1/§5.10.6 give it the consumer queries, but the BE-17/BE-18 Modify lists do not name the file: BE-17 must add the conditional-UPDATE `claim`/`finish`, `findDueIds` and `findExpiredLeaseIds`; BE-18 must add `purge` of DONE `automation_events` together with old `history_transactions` (a `HistoryTransactionRowRepository` purge query). Check both did.

## From wave 3 (BE-11..BE-15)
- [BE-13 low, cross-stream] Approval Detail current/proposed/changes[] serialise money as doubles (500.0), not 2-decimal values — check the frontend's JsonDiffView/FieldChange parsing against the real payload.
- [BE-13 low, cross-stream] 202 `Location` header is not in CORS exposed headers, so the frontend's Location fallback cannot read it cross-origin (web build). Add it to SecurityConfig exposed headers.
- [BE-13 low] Detail never flags a strict-check field (total/paidAmount) that changed since the request; 'customer login → 403' test satisfied by @PreAuthorize, never reaches check 4.
- [BE-14 low] Customer logins created before BE-14 have no credentials_set_by_user_id (SC-10 unprotected for them); no test approves PROMISE_CREATE, PROMISE_OVERRIDE or INVOICE_TERMS end to end (override strict check and terms three-way check untested).
- [BE-15 low, privacy] A customer login can read the checker's decision note through the audit trail although the approvals API hides it; a customer login's audit trail hides the applied change itself (not just the request) when it came from an internal dispute.
- [BE-15 low] As of a date, the approvals list's status filter compares today's status.
- [BE-15 medium, decided by fixer — verify] Regions with approval limits / new regions starting with copied limits vs region deletion (FK) — confirm region delete still works as designed.
- [BE-12 → BE-16] BE-16 must add AUTOMATION_MANAGE to /api/assignees/context and AUTOMATION_VIEW to /api/assignees/people; TaskDto.rule.name null until rules exist.
- [BE-12 gap] A USER assignee without TASK_VIEW can be assigned but is not notified and cannot move the task — confirm intended.
- [FE-06 → BE-12] Verify the customer Tasks-tab count endpoint counts by customerId (badge must match the tab).

## From wave 4 (BE-16..BE-19)
- [BE-16] PUT without `version` skips the stale check on rules (house convention: optional version) — accepted, but make sure the frontend always sends version.
- [BE-17 low] Per-rule CHILDREN events ignore active_since (resumed/re-enabled rule may act on backlog); run progress/finish not fenced by the token; runs list filters on unredacted columns (partial viewer can infer hidden rows).
- [BE-18 low] Events that happened before a suspension but were processed during it are neither replayed nor counted; long set runs spend a retry attempt per transient page failure; {{Rule.Name}} accepted in hand-written emails but renders blank; purge scans automation_events/history_transactions every 30 s with no usable index.
- [BE-19 medium→verify fixed] Customer logins could see staff assignees on disputes/promises via the audit trail; as of a date a record-level ROLE assignee on a dispute/promise shows nobody.
- [BE-19 low] Dispute list/export accept staff-only filters (internal, assigneeUserId) from customer logins (results still limited); dispute/promise actions compute notifications while holding the customer lock; promise action on a zero-balance record with no Collection POC fails instead of skipping (preview disagrees with execution).
- [BE-17/19] RunNowTest SC-2 assertion ("after running the rule's CREATE_DISPUTE the new owner cannot approve the resulting change") — verify BE-19 added it.
