## FE-01 — Scope core and models

**Built** (frontend only; no visible UI)
- `F/core/scope/view_scope.dart`: `ViewScope {Set<int>? regionIds, DateTime? asOf}` with `apiParams` (only what is set; `regionIds` a sorted comma string; `asOf` via `DateFormat('yyyy-MM-dd')` on the local date), `==`/`hashCode` over the canonical string, `copyWith`, `ViewScope.live`, `isHistorical`; top-level `asOfWire(DateTime)`; `ViewScopeController` (`setRegions`, `setAsOf`, `clear`); `viewScopeProvider` (StateNotifierProvider, session memory only, clears itself when the signed-in user id changes via `ref.listen(currentUserProvider.select(id))`); `isHistoricalProvider`; exported `viewScopeInterceptor(ViewScope Function() current)` (`putIfAbsent` on every request, GET and POST alike).
- `F/core/scope/as_of.dart`: `HistoryRange` (`historyStartsOn`, `latestAsOf` as local calendar dates, `timezone`), `historyRangeProvider` (`FutureProvider.autoDispose`, `GET /api/history/range`, not read by anything yet — FE-02's picker reads it), `enum AsOfApplied { live, historical }`, `AsOfMismatch` (message "This screen can't show history"), `checkAsOf(Response, DateTime?)`.
- `F/core/api/api_client.dart`: `dioProvider` adds `viewScopeInterceptor(() => ref.read(viewScopeProvider))` before the auth interceptor.
- `F/shared/models/privileges.dart`: the nine constants (regionView, regionManage, taskView, taskManage, automationView, automationManage, automationAdmin, changeApprove, approvalThresholdManage).
- `F/shared/models/auth_models.dart`: `RegionAccess {id, code, name, active, defaultRegion, level?, scope?, privileges}` (tolerant `fromJson`); `CurrentUser.regions` (optional, `const []`, so every existing const construction compiles), `hasIn(p, regionId)`, `regionsWith(p)`, `multiRegion`. `CurrentUser.fromJson` (so `/me` and `LoginResult`) parses `regions`.
- `F/features/auth/auth_controller.dart`: `logout` clears the view scope.
- `F/core/table/table_models.dart`: `PagedResult` gains `regionIds`, `bookRegionIds` (`List<int>`, default `const []`) and `asOf` (`String?`); parsed tolerantly and copied by `map`.
- `F/core/table/table_providers.dart`: `tablePageProvider` and `tableSummaryProvider` `ref.watch(viewScopeProvider)` (refetch on change) and call `checkAsOf(res, scope.asOf)`. The params themselves come from the interceptor; nothing goes into `TableRequest.extra` (G1).
- `F/shared/widgets/app_shell.dart`: `enum NavBadge { none, inbox, myTasks, approvals }`; `NavEntry.unreadBadge` replaced by `badge`; Inbox uses `NavBadge.inbox`; `_NavIcon` switches on the badge.
- `FT/support/fake_backend.dart` (additive): `FakeReply(status, data, {headers})` (2xx resolves with status and headers; ≥400 rejects as `badResponse` with the headers); `Dio dioWith(List<Interceptor> before)`; `dio` is now `dioWith(const [])` (same behaviour as before).
- Tests: `FT/view_scope_test.dart` (19: apiParams empty/canonical/local date; controller; interceptor on a bare Dio for GET and POST, only when set, never overriding an explicit param, reads the scope per request; `checkAsOf` equal/`live`/missing/wrong date; `dioWith` ordering; `FakeReply` 202 + headers + 409; `tablePageProvider`/`tableSummaryProvider` refetch with `regionIds` on scope change and parse `regionIds`/`bookRegionIds`/`asOf`; a historical list accepted only with the echoed header, `AsOfMismatch` otherwise; scope cleared on user change and on logout). `FT/current_user_regions_test.dart` (11: parse with and without `regions`, tolerant region parse, `LoginResult`, const construction; `hasIn` per region / absent region / null region / no regions fallback; `regionsWith`; `multiRegion`).

**Deviations from DESIGN.md**
- `ViewScopeController.setRegions` treats an empty set like null ("all my regions"), and `apiParams` never sends an empty `regionIds`, so no request can carry `regionIds=` (which the server would read as nothing or reject). §7.1 did not say what an empty choice means.
- `_NavIcon` maps `NavBadge.myTasks` and `NavBadge.approvals` to no badge for now: their count providers (`myTasksCountProvider`, `approvalsAwaitingMeCountProvider`) are created by FE-06 and FE-04, which add the switch arms. No nav entry uses either value yet.
- `FakeReply` with a status ≥ 400 rejects like `FakeFailure` (but with headers); §7.8 only described 2xx.

**API contract vs §6:** none. (LOG-backend.md did not exist when this slice ran, so no backend deviations were applied.)

**Known gaps / deferred (by design to later slices):** the detail, record-tab, dashboard, notification and inbox providers do not watch the scope yet (FE-02); `historyRangeProvider` has no reader until the as-of chip (FE-02); `hasIn` answers from `regions[].privileges`, which list regional privileges only, so it must not be asked about a global privilege with a region id (the design uses it only for regional create-flow checks).

**Final counts:** `flutter analyze` 0 issues; `flutter test` 91 passed (61 baseline + 30 new: 19 in view_scope_test, 11 in current_user_regions_test).

### FE-01 review fixes

All three findings in FE-01.review.md were valid, and all three are fixed.

- **(medium) The selection survived a change of view scope.** `DataTableScaffold.build` now has `ref.listen<ViewScope>(viewScopeProvider, (_, __) => _clearSelection())`, so other regions or another date drop whatever was ticked. Bulk and export by `ids` are authorised per record (I3), so a stale selection could otherwise act on rows the user had moved away from. `data_table_scaffold.dart` belongs to FE-02, but FE-01 is what made the table reload on a scope change, so the fix lives here. FE-02 must keep this listener when it edits the scaffold. Tests are in `view_scope_test.dart` under "a list's selection". Changing regions clears "2 selected", and a later export sends only the id ticked afterwards. Moving to a past date also clears the selection.
- **(low) Nothing proved that the real `dioProvider` installs the scope interceptor.** A new test, "the app's own Dio sends the scope, alongside the token, on every request", reads the real `dioProvider` with only `tokenStorageProvider` overridden and swaps in a recording `HttpClientAdapter`. It asserts the query parameters and the Bearer header on a GET and a POST, before and after the scope is set. Deleting the `viewScopeInterceptor` line from `api_client.dart` makes it fail; I checked this by temporarily removing the line and then restoring it.
- **(low) `CurrentUser.hasIn` answered false for a global privilege.** `Privileges.global` now mirrors the 13 GLOBAL names in §4.1. `hasIn` and `regionsWith` answer `has(p)` for these in any region the user holds, and still answer false for a region the user does not hold. This is §4.2's `global(p)` term, so it deviates from §7.2's literal wording. Tests are in `current_user_regions_test.dart`: "a global privilege is the role's alone…", plus an extra assertion for a region the user does not hold.

Nothing was rejected.

Final counts: `flutter analyze` reports 0 issues, and `flutter test` passes 95: 61 baseline, 22 in view_scope_test.dart and 12 in current_user_regions_test.dart.

## FE-02 — Region selector, as-of picker, banner, read-only mode, dashboard

**Built** (frontend only)
- New widgets:
  - `F/shared/widgets/region_selector.dart`: `RegionSelector({compact})` is a `PopupMenuButton<int>` of `CheckedPopupMenuItem`s over `currentUser.regions`, plus "All my regions". The label reads "All regions" or the chosen codes; `regionSelectionLabel` is public. It is hidden for customer logins and when `regions.length <= 1`, and it fetches nothing. Below 600px it collapses to an icon, and the tooltip still names the choice.
  - `F/shared/widgets/as_of_chip.dart`: `AsOfChip({compact})` reads "Today" (`ActionChip`) or "As of 30 Jun 2026" (`InputChip` with a × whose tooltip is "Back to today"). A tap holds `historyRangeProvider.future` open with `listenManual` and calls `showDatePicker(firstDate: historyStartsOn ?? DateTime(2000), lastDate: latestAsOf ?? yesterday UTC)`. Today is never offered. When history starts after `latestAsOf` it shows a snack instead of a picker. Below 600px it is an `IconButton`.
  - `F/shared/widgets/as_of_banner.dart`: `AsOfBanner` reads "Viewing 30 Jun 2026 (end of day, UTC) — read only", with a "Back to today" action. It renders nothing while live.
- `app_shell.dart`:
  - Actions, before the bell: `RegionSelector`, then `AsOfChip` (staff only).
  - Body: always `Column([AsOfBanner, Expanded(child)])`, so the page keeps its place in the tree when the date changes.
- `F/core/scope/as_of.dart` (additive):
  - `asOfLabel(DateTime)` formats as `d MMM yyyy`.
  - `viewAsOfProvider` is the date alone, for by-id reads that ignore regions (I3).
  - `viewRegionsProvider` is the canonical `regionIds` string alone, for live-only providers.
- `data_table_scaffold.dart`:
  - Bulk actions are hidden while historical; export stays. A new `bulkWhileHistorical` flag (default false) keeps housekeeping bulk (mark read/unread) on the notifications and inbox lists.
  - When historical and `page.asOf == null`, a strip reads "Showing current data — history isn't kept for this list".
  - When `bookRegionIds` is non-empty and `lockedFilters` is empty, a strip reads "Your book only in: SOUTH" (codes from `currentUser.regions`).
  - FE-01's selection-clearing `ref.listen` is kept.
- `detail_scaffold.dart`: `notFoundMessage(error, noun, {DateTime? asOf})`. A 404 while historical reads "This invoice did not exist on 30 Jun 2026."
- Providers that watch the scope and call `checkAsOf`:
  - The detail providers watch `viewAsOfProvider`: `customerDetailProvider`, `invoiceDetailProvider`, `paymentDetailProvider`, `promiseDetailProvider`, `disputeDetailProvider`, `productDetailProvider`.
  - The record-tab providers watch the whole scope: `scopedPromisesProvider`, `scopedDisputesProvider`, `entityDocumentsProvider`, `documentCountProvider`, `entityEmailsProvider`, `auditHistoryProvider`.
- Providers that watch the regions only (`viewRegionsProvider`), with no as-of check because they answer live:
  - `notificationsProvider`, `unreadCountProvider`, `inboxUnreadCountProvider`. The poll restarts with the regions; a date change alone does not refetch.
  - `myPocScopeProvider`, now `autoDispose` (G22).
  - `assignablePocsProvider`, `referenceOptionsProvider`.
- Dashboard:
  - `Coverage.mixed` (`'MIXED'`); the parser is now public as `parseCoverage`, and unknown values stay `all`.
  - `DashboardScope {regionIds, bookRegionIds, asOf}` is parsed from `scope` on `MonthlySeries`, `OutstandingByAge` and `CustomerRanking`.
  - All 8 providers go through `_dashboardGet`, which watches the scope and calls `checkAsOf`.
  - `dashboardTodayProvider` is `asOf ?? todayUtc()`. It replaces `todayUtc()` in the header, the ageing links, `TopOutstandingTable`/`UpcomingPromisesTable` (which now take a required `today`), the upcoming-promises filter and its "View all" link.
  - The header reads "… as of 30 Jun 2026".
  - `DashboardCard.bookRegions` and `BookBadge(regions:)` render "Your book in SOUTH" for mixed coverage (`mixedBookRegions`). `UpcomingPromises` gains `bookRegionIds`.
  - Quick actions hide New invoice while historical, and the Payments button reads "All payments".
- Read-only gating (`!isHistorical`):
  - Folded into the providers: `canSendEmailProvider`, `canAssignPocProvider`, `canManageDocumentsProvider`, `canUploadDocumentsProvider`.
  - Per-screen sites: customer, invoice, payment, promise and product detail (edit, delete, save, override, cancel, and "Raise dispute" for customer logins); the lists' `canManage`/`canPromise`/`canOverride` (customers, invoices, payments, promises, products, users, roles); `PromisesTab`, `DisputesTab`, `DisputeDetailScreen` (`canResolve`); the user and role detail editors; the email card's Retry.
  - The customer, invoice and payment detail screens seed their edit controllers only from live data (G18), so leaving a past date never edits from historical values. On a past date the POC pickers show the record's POC from that day.

**Deviations from DESIGN.md**
- The banner is always in the tree (`Column([AsOfBanner, Expanded(child)])`, with the banner shrinking to nothing while live), not wrapped in only when historical. Wrapping only when historical would re-mount the page on every date change and lose unsaved edits and scroll position.
- `DataTableScaffold.bulkWhileHistorical` is new. §7.4 hides bulk while historical, but the notifications/inbox mark-read bulk actions are §5.2.4 housekeeping the server allows in historical mode, so those two lists keep them.
- The detail providers watch only the as-of date (`viewAsOfProvider`), not the whole scope. By-id reads ignore `regionIds` (I3), so a region change must not reload a detail page. The interceptor still sends both parameters.
- More sites are gated than §7.4 lists: products, the dashboard quick actions, and email Retry. This follows "every mutating control". The configuration editors (users, roles) were also gated, since the server refuses their writes in historical mode.
- On region-selector semantics, §7.3.1 left these open, so this is the reading taken:
  - With "all" chosen, every region box shows ticked, and unticking one leaves the others.
  - Unticking the last region is ignored.
  - Ticking every region is stored as "all" (null).

**API-contract changes vs §6:** none (LOG-backend.md records none either).

**Known gaps / deferred**
- The users/roles "regions, threshold and rule editors" named in §7.4 do not exist yet. FE-03, FE-04 and FE-07a must AND `!isHistorical` in them, and in `canManageTasksProvider` (FE-06).
- Server capability flags (`canManage` and the rest) arrive in FE-03. Until then the per-record gates are privilege-based plus `!isHistorical`.
- The design says the task and rule editors join `as_of_screens_test`'s table "once they exist", so FE-06 and FE-07a should add rows to `_sites` there.
- Until BE-09a, the real backend answers every past `asOf` with 400 `AS_OF_UNSUPPORTED`. Lists show that message through `apiErrorMessage`, and detail pages show "Could not load this …: <message>".

**Final counts:** `flutter analyze` reports 0 issues. `flutter test` passes 157: 61 baseline, 34 from FE-01, and 62 new (30 in as_of_screens_test, 23 in region_selector_test, 9 in dashboard_coverage_test). No baseline test file was edited, and `list_columns_test` needed no override.

### FE-02 review fixes

All four findings were verified against the code and fixed; none rejected.

- **Invoice form read only on a past date (medium).** `InvoiceFormScreen.build` watches `isHistoricalProvider` and, while historical, shows a `_ReadOnlyNotice` ("New invoices can't be created on a past date.") in place of the form; `_submit` also returns early when historical. The form's `State` stays mounted, so an unsaved draft is all still there on "Back to today" (consistent with the always-mounted banner deviation). Test: `as_of_screens_test` "the new-invoice form is read only on a past date, and keeps its draft" (types notes, switches to 30 Jun via the scope, asserts banner + notice + no fields/Create, back to today restores the notes, no POST sent). Not added as a `_sites` row because the form makes no as-of GET on load, which that table asserts.
- **`dashboardTodayProvider` stale after UTC midnight (medium).** Now `Provider.autoDispose`, and `refreshDashboard` invalidates it first, so pull-to-refresh and every revisit work out today again. The time comes from a new `dashboardClockProvider` (`DateTime Function()`, default `DateTime.now`; `todayUtc([DateTime? at])`) so the rollover is testable. Test: `dashboard_coverage_test` "a refresh after midnight (UTC) moves today on with it" (clock 26 Sep 23:59Z, then 27 Sep 00:01Z + refresh: header day and upcoming promises' `promisedDate:gte` both move to 27); verified it fails without the invalidation.
- **Inbox reader read as of the past date (medium).** New `regionsOnlyExtra` in `core/scope/view_scope.dart`: a request whose `options.extra` carries it gets `regionIds` from the interceptor but not `asOf`. `emailDetailProvider` (read only by the inbox reader; `email_tab` merely invalidates it) now sends it, so a message received after the date viewed still opens, as today's, like the live inbox row it came from. Tests: `as_of_screens_test` group "the inbox reader" (past date + region 2: `GET /api/emails/88` carries exactly `{regionIds: 2}`; an ordinary request still carries both).
- **Documents tab Edit/Remove not gated (low).** `DocumentsTab` watches `isHistoricalProvider` and passes `readOnly` to `_DocumentRow`, which hides Edit and Remove on a past date whatever the server's `canEdit`/`canDelete` say (until BE-04b/BE-10 clears them). `canManageDocumentsProvider` was already unused at baseline (321150e); its `!isHistorical` folding is kept as harmless and correct. Test: new `documents tab` row in `as_of_screens_test._sites` (Upload, Edit, Remove there today; none on 30 Jun; the list asked for as of that day).

Final: `flutter analyze` 0 issues; `flutter test` 163 passed (157 before + 6: documents-tab site ×2, invoice form 1, inbox reader 2, dashboard rollover 1). Only frontend/ touched.

## FE-03 — Regions and rights UI

**Built** (frontend only)
- Regions admin (`F/features/regions/`):
  - `regions_screen.dart`:
    - `Region` model and `RegionGrantHolder`.
    - `regionDetailProvider`, `regionOptionsProvider` (`GET /api/regions?size=50&sort=code,asc`) and `regionGrantsProvider` (`GET /api/regions/{id}/grants`). All three answer live, so there is no `checkAsOf`.
    - `RegionsScreen`, a `DataTableScaffold` on `/api/regions` with columns Code (plus a Default badge), Name, Active and Created. Row actions are Open, Edit and Make default. "New region" needs REGION_MANAGE and is hidden while historical.
    - Shared helpers: `regionColumn<T>()` (label "Region", sortKey `regionName`, shows the code with the name as tooltip), `regionLabel`, `accessLevelLabel`, `recordScopeLabel`, `makeRegionDefault` (confirms, then posts `/make-default`) and `regionsChanged` (invalidates the lists and calls `AuthController.refreshUser`).
  - `region_form_dialog.dart`:
    - Create sends `{code, name, active}`. The code is upper-cased as it is typed and checked against `^[A-Z0-9_-]{2,20}$`. The name is trimmed.
    - Edit sends `{name, active, version}`. The code field is disabled.
    - Server `fieldErrors.code` and `fieldErrors.name` show under their fields, and the dialog stays open.
  - `region_detail_screen.dart`:
    - Header actions: Edit, Make default and Delete, for REGION_MANAGE and not while historical. A refused DELETE shows the server's message.
    - An "Approval thresholds" link to `/approval-thresholds?regionId=` for users with APPROVAL_THRESHOLD_MANAGE or CHANGE_APPROVE in that region.
    - Grants tab (needs USER_VIEW) lists name, role, level and scope; tapping a row opens `/users/{id}?tab=regions`.
    - History tab: `AuditHistoryPanel(entityType: 'REGION')`.
  - Routes `/regions` (nav guard, default sort `code,asc`) and `/regions/:id` (`needs(const [Privileges.regionView])`, `pageForId`).
  - `NavEntry('Regions', Icons.public_outlined, '/regions', [regionView], hideForCustomer: true)`, placed after Disputes. FE-04, FE-06 and FE-07a insert their entries between Disputes and Regions.
- Grants editor (`F/features/users/region_grants_editor.dart`):
  - `RegionGrant` model and `userRegionGrantsProvider` (`GET /api/users/{id}/region-grants`).
  - `GrantBounds` enforces §4.6 on the client. A row is the editor's to change only if the editor's own grant in that region is at least the row's old and new level, and has scope ALL whenever the row does. If `/me` reports no regions, nothing is bounded.
  - `RegionGrantsEditor` rows (keys `grant-level-<id>`, `grant-scope-<id>`, `grant-remove-<id>`, `grant-add`):
    - Region, a READ/MANAGE/APPROVE `SegmentedButton`, and an ALL/BOOK `SegmentedButton`.
    - Levels above the editor's own are disabled, and ALL is disabled where the editor holds only BOOK.
    - Rows beyond the editor are locked, can't be removed, carry "Only someone with this access can change it", and go back to the server unchanged.
    - "Add region" offers only active regions the editor holds.
    - Without SCOPE_OVERRIDE on the role, the scope control is disabled and reads "Book (set by role)".
    - BOOK for a role with no `POC_ASSIGNABLE_*` warns "This role holds no POC seats, so its own book in X is empty."
  - `UserRegionGrantsPanel` is the user page's Regions tab. It saves the whole draft with `PUT …/region-grants {"grants":[…]}` behind "Save access" and offers "Undo changes". It is read-only for yourself ("You cannot change your own region access"), without USER_MANAGE, and while historical.
  - `levelRoleNeeds` and `initialGrantsFor` pre-fill a new user with the DEFAULT region at the highest level the role's privileges need, capped by the editor's own grant.
- Users and roles:
  - `UserFormDialog` (create) embeds the editor. It always sends `regionGrants`, and sends `[]` for the CUSTOMER role, where the editor is hidden. The pre-fill follows the chosen role until the editor is touched.
  - `UserDetailScreen` has a Regions tab (USER_VIEW, staff users only).
  - `roles_screen.dart`: `allPrivilegesProvider` now returns `PrivilegeInfo {name, regionLevel}`. The role form shows a `PrivilegeLevelBadge` (Global/Read/Manage/Approve) beside each privilege.
- Entity models:
  - Nullable `regionId`, `regionCode` and `regionName` on `Customer`, `InvoiceSummary`/`InvoiceDetail`, `PaymentRecord`, `PaymentPromise` and `Dispute`.
  - Flags: `canManage` on the first four; `canMoveRegion` and `version` on Customer; `canOverride` on Promise; `canResolve` on Dispute; `pendingApproval` on invoices, payments, promises and disputes.
  - Dispute also gains `internal`, `origin` and `ruleId`. Promise and Dispute gain `assignees` (new `shared/models/record_assignee.dart`: `RecordAssignee {EmailToken token, label, people[{userId,name,primary}], unresolved}`).
  - `DisputeTargetType.CUSTOMER` is appended, with the label "Customer". `dispute_create_dialog.dart` never iterated `.values`, so it needed no change.
- Per-record gating (D14 and §7.5):
  - Edit, cancel and delete use `x.canManage ?? user.has(xManage)`, ANDed with live. This covers the customer, invoice and payment detail screens and the invoices list's Cancel row action.
  - The promise detail, `PromisesTab`/`PromiseCard` and the promises list use `p.canManage` and `p.canOverride` in the same way.
  - Dispute resolve is `d.canResolve ?? user.has(disputeManage)`. The `isAdmin` gate is gone (G20).
  - Other MANAGE-level actions go through `user.hasIn(p, x.regionId)`:
    - Email send, via the new `canSendEmailInProvider(regionId)` and an optional `regionId` on `sendEmailHeaderButton`, `sendEmailRowAction`, `emailDetailTab` and `EmailTab`.
    - Document upload, via `canUploadDocumentsInProvider((type, regionId))` and an optional `regionId` on `documentsDetailTab` and `DocumentsTab`.
    - Raise promise: on the customers and invoices list rows, and via `PromisesTab.regionId`.
    - POC assign, on the customer, invoice and payment detail screens.
- Region column (only when `user.multiRegion`) on the customers, invoices, payments, promises and disputes lists. Detail pages show a "Region" grid item under the same condition.
- Create flows:
  - The customer dialog shows a required Region dropdown (key `customer-region`) of active `regionsWith(customerManage)` when there is more than one, and sends `regionId`. It preselects the single choice, else the one region in view, else the default.
  - `searchCustomers(dio, q, {regionIds})` adds `filter=regionId:in:<sorted ids>`. The invoice form uses `regionsForPicker(user, invoiceManage)` and the record-payment dialog uses `paymentManage`. `regionsForPicker` returns null, meaning no narrowing, when `/me` has no regions.
  - `PocPicker.customerId` becomes `AssignableQuery.customerId`, sent as `&customerId=` to `/api/pocs/assignable`. It is passed from the invoice form, record payment, the promise form, the invoice and payment detail screens and the customer POC seat editor.
  - The invoice form's "preselect me as Sales POC" is now asked again for each picked customer, whose region decides assignability, until the user picks someone by hand.
- `reference_picker.dart` gains the `region` kind (options from `currentUser.regions`, no request) and the `staffUser` kind (`GET /api/assignees/people?q=`) (G6).
- `poc_providers.dart`: `MyPocScope.regions` (`RegionPocScope` per region, from `/my-scope`) and `inRegion(regionId)`.
- Move customer (`F/features/customers/move_region_dialog.dart`):
  - "Move region" appears in the customer header when `canMoveRegion == true` and the view is live. It is disabled while the page has unsaved edits.
  - The dialog offers active `regionsWith(customerManage)` minus the current region, plus a reason of up to 500 characters.
  - It POSTs `{regionId, version?, reason?}`. On success it shows "X is now in SOUTH", the counts (tasks and closed approvals named from `moved.other`) and the warnings, then invalidates the detail, table page and summary, and audit.
  - A 400 shows the server's message and the dialog stays open.
- `AuthController.refreshUser()` re-reads `/me` for the same user without signing anyone out; a failure keeps the current user. It runs after a region is created, edited, made default or deleted, so the creator's auto-grant reaches the selector and pickers.

**Deviations from DESIGN.md (with reasons)**
- `router.dart`: `routerProvider` now watches only `authControllerProvider.select((s) => s.user?.id)` and reads the user afresh in `redirect`. Before this, any change to the auth state built a new `GoRouter` at `/`. `refreshUser` would then have thrown an admin back to the dashboard after creating a region. `_RouterRefresh` still re-runs the guards on every auth change. Test: `regions_screen_test` "the signed-in user's own regions…" (same router instance after a refresh; a new one after logout).
- Files touched beyond FE-03's list, all additive optional parameters:
  - `email_actions.dart` and `email_tab.dart`, `document_actions.dart` and `documents_tab.dart`: needed to make §7.5's "email send, document manage" region-aware.
  - `poc_picker.dart` and `customer_poc_editor.dart`: needed so POC pickers can pass `customerId`.
  - `auth_controller.dart`: `refreshUser`.
  - `privileges.dart`: `manageLevel`, `approveLevel` and `levelFor`, mirroring §4.1. They pre-fill a new user's level without `/api/privileges`, which needs ROLE_VIEW that a user manager may lack. The roles badge also uses them as a fallback when the server omits `regionLevel`.
  - `shared/models/record_assignee.dart`: new file for §7.2's `assignees`.
- Grants are edited on the user page's Regions tab. The new-user dialog embeds the editor for create only. The edit dialog does not, because list rows carry no `regionGrants` and the tab is the one place that loads them. §7.5 says only "embedded in UserFormDialog and the user detail screen".
- "BOOK is disabled … when the role lacks SCOPE_OVERRIDE": the whole scope control is disabled and the row's scope is sent unchanged. Without the override the role sees only its book whichever scope is chosen.
- The editor's APPROVE option is bounded by the editor's own level only. It does not also require CHANGE_APPROVE: §7.5 bounds by `regions[].level`, and until BE-13 nobody holds CHANGE_APPROVE. The server's 403 remains the authority (§4.6).
- The region detail's "Approval thresholds" link points at `/approval-thresholds?regionId=`, a route FE-04 creates. Only holders of CHANGE_APPROVE or APPROVAL_THRESHOLD_MANAGE see the link, and nobody holds either before BE-13.
- The customer create dialog also sends `regionId` when there is exactly one choice. This is explicit and equal to what the server would pick.

**API-contract changes versus §6:** none. The slice uses §6.2 `/me` `regions`, §6.3 regions, grants and users (`regionGrants`), §6.4 `regionId` on create and `move-region`, §6.5 DTO fields, §6.8 `customerId` on `/pocs/assignable` and `my-scope` `regions`, and §6.13/§6.16 `/api/assignees/people` for `staffUser`. LOG-backend.md records only BE-01, with no API deviations.

**Known gaps and deferred items**
- Until the backend sends the flags (BE-03 to BE-05), every gate falls back to the flat privilege (`?? user.has(...)`), and `hasIn` falls back to `has` while `/me` has no `regions`. Behaviour is therefore unchanged against today's server.
- The promise and dispute `assignees`, `internal`, `origin` and `ruleId` are parsed but not shown yet (FE-07b). `pendingApproval` is parsed but not shown yet (FE-04).
- The Tasks list's Region column comes with the Tasks list (FE-06).
- The user detail's Regions tab has no unsaved-changes guard. It has an explicit "Save access" and "Undo changes", as the page had no guard before.
- Size: about 3,400 changed lines including roughly 1,160 of tests, above §9.0's 2,500 bound. It was not split, because every part is in the slice's own list.

**Final counts:** `flutter analyze` reports 0 issues. `flutter test` passes 199:
- 61 baseline, 102 from FE-01 and FE-02, and 36 new: `region_grants_editor_test` 6, `regions_screen_test` 13, `detail_gating_test` 17.
- No baseline test file was edited, and `list_columns_test` needed no override.

### FE-03 review fixes

Fixed:
- **(medium) An editor with no grants was treated as unbounded.** `CurrentUser` gains `regionsReported` (set by `fromJson` when `/me` carries a `regions` list, which it always does since BE-02) and `regionsKnown` (`regionsReported || regions.isNotEmpty`, so hand-built test users keep their old meaning). `GrantBounds.unbounded` is now `!editor.regionsKnown`: a staff editor reported with `regions: []` is offered nothing, sees every existing row locked, has no "Add region", and a new user is not pre-filled (`regionGrants: []` is sent). Same root cause in `CurrentUser.hasIn`: it now falls back to the flat privilege only with no region asked about, for a customer login, or when regions are unknown, so a staff user with no grants is not offered region-bound actions. `regionsForPicker` is unchanged (a user with no grants sees no customers anyway). Tests: region_grants_editor_test "a staff editor the server reports with no regions holds nothing…", "an editor with no regions sees every row locked…", "is not pre-filled by an editor who holds no region…".
- **(low) APPROVE without CHANGE_APPROVE.** `GrantBounds.offersLevel(r, 'APPROVE')` also needs `editor.hasIn(CHANGE_APPROVE, r)` (§4.6), and `initialGrantsFor` caps the pre-fill at MANAGE where it is not offered. A row the server already has at APPROVE keeps its APPROVE segment enabled (keeping a level is not granting it), so the editor can still lower it or leave it. Until BE-13 creates CHANGE_APPROVE nobody holds it, so APPROVE cannot be newly given from the UI in that window; before BE-13 APPROVE and MANAGE differ only in the approval privileges that do not exist yet, so nothing is lost. The earlier deviation "APPROVE bounded by the editor's own level only" is withdrawn. The test fixture's NORTH editor grant now lists CHANGE_APPROVE. Tests: "APPROVE is given, and pre-filled, only where the editor approves changes", "an APPROVE row may be kept where the editor cannot give APPROVE".
- **(low) Customer create pre-selected the default region.** `_seedRegion` now selects only the single choice, or the single choice in the current view (the same region the server would take, §5 CustomerService.create); otherwise nothing is selected and the "Choose the region this customer belongs to" validator fires. Test: detail_gating_test "with several choices the region is asked for, never assumed".

Recorded, not fixed here (backend contract gap):
- **(low) The move-region stale check never runs.** Valid: §6.5 CustomerDto (and today's `CustomerDtos.CustomerDto`) has no `version`, so the dialog's `if (c.version != null) 'version'` never sends it and the server's `req.version != null` check is skipped. The frontend already parses and sends `version` whenever the server provides it, so no frontend change is needed. **Request to the backend stream:** add `version` (NON_NULL) to `CustomerDto` in the slice that adds `move-region` (or BE-04b's DTO flags). The detail_gating_test move-region case keeps its `'version': 4` fixture, now with a comment that it depends on that field.

Final: `flutter analyze` 0 issues; `flutter test` 205 passed, 0 failed (199 before + 5 region_grants_editor_test + 1 detail_gating_test).

## FE-04 — Approvals UI

**Built** (frontend only)
- `F/features/approvals/approval_models.dart`: `PendingStatus` {PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, FAILED, CLOSED} with `parsePendingStatus` (unknown → PENDING, G25) and `pendingStatusLabel`; `ApprovalPerson`; `PendingChangeBrief` (§6.11 Brief, every field but `id` nullable; `recordText` names a REGION row by its region and a waiting create as "Create invoice for Acme"); `FieldChange`; `ApprovalResult`; `PendingChangeDetail extends PendingChangeBrief` (changes, current/proposed, decision fields, `approverCount`, flags `canApprove/canReject/canWithdraw`, `blockedReason`, `recordRoute`); `ThresholdRow` and `RegionThresholds` (GET/PUT shape incl. `pendingChange`, `warnings`); `approvalEntityRoute`.
- `approval_providers.dart`: `approvalDetailProvider` (family int; watches `viewAsOfProvider`, `checkAsOf`); `pendingForRecordProvider` (family `({entityType, entityId})` → `GET /api/approvals/for-record`; watches the date, `checkAsOf`); `approvalsAwaitingMeCountProvider` (`StreamProvider.autoDispose<int>` via `pollUnreadCount` on `/api/approvals/awaiting-me/count`; `Stream.value(0)` with **no request** unless staff with CHANGE_APPROVE, §7.3.4; otherwise watches the whole scope); `approvalsChanged(ref, {id})` invalidates the badge, `pendingForRecordProvider`, the detail, `tablePageProvider`, `tableSummaryProvider`.
- `approvals_screen.dart`: `ApprovalsScreen` — `DataTableScaffold` (entity `approvals`, `/api/approvals`) with a `SegmentedButton` (Awaiting my approval / My requests / All I can see) sending `extraParams: {'view': 'AWAITING_ME'|'MINE'|'ALL'}` as a scalar (G1); default AWAITING_ME with CHANGE_APPROVE, else MINE (the AWAITING_ME segment is hidden without it). Tiles from `/api/approvals/summary`. Columns Status, Change (summary), Record, Customer, Region (multi-region only, sortKey `regionId`), Amount, Limit, Requested by, Requested; export with EXPORT_DATA. `ApprovalStatusCell` shows `statusAt` in historical mode with "now X" beside it when it differs.
- `approval_detail_screen.dart`: `ApprovalDetailScreen` on `DetailScaffold`: status chip, Approve / Reject / Withdraw **only per the server flags** (and never while historical or busy); top grid (change, record link, customer link — absent for THRESHOLD_CHANGE, region, measure "amount against a limit of threshold", requested by/at, origin with `Rule #N` link to `/automation/N`, decided by/at, note, replaced-by link); notices for FAILED `failureReason`, `blockedReason`, and "No one else can approve…" when `approverCount == 0`; tabs "Changes" (field table, `changedSinceRequest` warning icon) and "Current and proposed" (`JsonDiffView`). Approve takes an optional note, Reject requires 1–500 characters (validated client-side), Withdraw confirms. After any decision (or error) it calls `approvalsChanged` and invalidates `auditHistoryProvider`. APPROVED shows "Open {record}" (`result.link`).
- `pending_banner.dart`: `PendingChangeBanner(entityType, entityId)` ("Awaiting approval: {summary} · requested by {name} on {date}", or "requested on {date}" when `requestedBy` is null; **View** → `/approvals/{id}`; nothing when empty or on error; asks nothing for a customer login's own CUSTOMER page) and the reusable `PendingBriefBanner`. Inserted as the first child of `_top` in the customer, invoice, payment, promise and dispute detail screens (in `top`, never a tab, G5).
- `approval_thresholds_screen.dart`: `ApprovalThresholdsScreen({regionId})` at `/approval-thresholds?regionId=`: region dropdown over `currentUser.regions` where the user holds APPROVAL_THRESHOLD_MANAGE or CHANGE_APPROVE; one card per operation with a money field (empty = never waits) and a "Counts changes over … hours" field; a "Counts changes over [N] h — Apply to all" control; Save PUTs **only changed rows** (`{operation, amount, windowHours?}`; `amount: null` deletes; `windowHours` sent only with an amount); client validation (money format, 0–720 h); server `fieldErrors["thresholds[i].x"]` mapped back to the row; `warnings` shown; the outstanding `pendingChange` shown with `PendingBriefBanner`; on **202** the snack "Raising or removing limits needs a second approver" with **View** → `/approvals/{id}`, and the returned (tightened) rows re-seed the form. Read only unless `canEdit` from the server, the user holds APPROVAL_THRESHOLD_MANAGE in that region, and the view is live.
- `status_chip.dart`: `approvalStatusColor`, `ApprovalStatusChip`.
- `audit_history_panel.dart`: `AuditEntry` parses `origin`, `ruleId`, `pendingChangeId`, `pendingStatus`, `approvedByUserId`, `approvedByUsername`; `stableKey` uses `pending:<pendingChangeId>` for an entry with no id; labels for the seven `CHANGE_*` actions and `APPROVAL_THRESHOLDS_CHANGED`; meta "via approval #N · approved by X" on applied rows; an "Approval #N" link and an `ApprovalStatusChip` from `pendingStatus`. `_AuditDetails`' before/after rendering is extracted as public `JsonDiffView({before, after, beforeLabel, afterLabel, onlyAfterLabel})` (accepts raw JSON text or decoded JSON), unchanged output for audit entries.
- `app_shell.dart`: `NavEntry('Approvals', Icons.fact_check_outlined, '/approvals', [changeApprove, invoiceView, paymentView, promiseView, disputeView], hideForCustomer, badge: NavBadge.approvals)` after Disputes, before Regions; `_NavIcon` maps `NavBadge.approvals` → `approvalsAwaitingMeCountProvider`.
- `router.dart`: `/approvals` (nav guard, `RouteQuery.read(defaultSort: 'requestedAt,desc')`), `/approvals/:id` (nav guard + `pageForId`), `/approval-thresholds` (`needs(const [approvalThresholdManage, changeApprove])`; keyed by `regionId` so a new query param rebuilds it).
- Tests: `FT/approvals_test.dart` (43).

**Deviations from DESIGN.md (with reasons)**
- The thresholds 202 notice is implemented here with a local SnackBar rather than FE-05's `showSentForApproval` (`pending_reply.dart` does not exist yet). §7.7.1 asks FE-04's screen to show "the pending notice of §7.6" on 202 and §8.8 puts that assertion in FE-04's test list; FE-05 may swap in the shared helper.
- `approvalsChanged(WidgetRef, {id})` in `approval_providers.dart` stands in for FE-05's `invalidateApprovals`; FE-05 can make `invalidateApprovals` delegate to it.
- No `approvalThresholdsProvider`: the thresholds screen loads into its own state (it edits the rows, and re-seeds from the PUT's answer, which a provider refetch would overwrite, losing the returned `warnings`).
- `/approvals/:id` also carries the nav guard (G16: a non-nav route with only `pageForId` would be open to anyone signed in). Customer logins holding INVOICE_VIEW etc. pass it, so the banner's View works for them; the server redacts.
- `approvalsAwaitingMeCountProvider` watches the whole view scope (regions and date), since `/api/approvals/awaiting-me/count` is AWARE and honours `regionIds` (§6.1); it does not call `checkAsOf` (a poll, like the other badges).
- The detail page uses `DetailScaffold` with two tabs (Changes; Current and proposed) rather than one scrolling page, to match the other record pages.

**API-contract changes versus §6:** none. Built against §6.11 (list with `view`, summary, awaiting-me count, for-record, `{id}`, approve/reject/withdraw bodies, thresholds GET/PUT incl. 202 + `pendingChange` + `warnings`) and §6.15 (`AuditEntryDto` additions). LOG-backend.md records no approval-endpoint deviations (BE-13..15 have not run yet), so every screen is exercised against the fake backend only.

**Known gaps / deferred**
- 202 handling at the gated write sites (invoice form, record payment, promise form/override, dispute approve, bulk cancel, invoice PATCH) and `pending_reply.dart` are FE-05.
- `pendingApproval` on list rows (parsed in FE-03) is still not shown as a column/filter chip; `list_columns_test` is untouched, so no list gained a column here. The `pendingApproval` filter is available through the server schema's filter editor.
- The approvals list has no mobile card (like the disputes list).
- Until BE-13/BE-15 ship, every approvals endpoint 404s on the real server: the banner hides itself, the badge stays 0 (it only asks for CHANGE_APPROVE holders, which nobody is before BE-13), and the list/detail show the server's error.
- `as_of_screens_test._sites` has no approvals row; the historical behaviour (no decision buttons on a past date, `statusAt` on the list, thresholds read-only) is covered in `approvals_test.dart`.

**Final counts:** `flutter analyze` reports 0 issues. `flutter test` passes 248: 61 baseline + 144 from FE-01..FE-03 + 43 new in `approvals_test.dart`. No baseline test file was edited, and `list_columns_test` needed no override.

### FE-04 review fixes

All six findings were checked against the code and found valid. All six are fixed.

1. **(medium) Switching view kept the page number.** The view now lives in the URL. `ApprovalsScreen` is a `ConsumerWidget` with a `view` parameter, which the router reads from `?view=` via `approvalsViewNamed`. `onQueryChanged` and the SegmentedButton both push through `RouteQuery.push(..., keep: {'view': ...})`, and choosing a view pushes `query.copyWith(page: 0)`. Test: "another view starts again at its first page" (starts on page 2, expects page=0 with view=MINE).
2. **(medium) Changing region during a thresholds save left a permanent spinner.** Two fixes:
   - The region dropdown's `onChanged` is null while `_saving`.
   - `_save` now returns before re-seeding when `saved.regionId != _regionId`.
   Test: "the region cannot change while a save is on its way" holds the PUT with `FakeBackend.held` and checks the dropdown is disabled, then enabled again.
3. **(low) A 202 without a usable pendingChange.** `_save` now branches on `statusCode == 202` first. The change id comes from `pendingChange.id`, or failing that from the `Location` header (`/api/approvals/{id}`). With no id, the notice still appears but has no View action. `RegionThresholds.fromJson` drops a `pendingChange` that has no numeric id instead of throwing, so the tightening rows still re-seed. Tests: "a 202 is held even when its body names no change: the id comes from Location" and "a 202 with no way to name the change still says it waits".
4. **(low) The view was lost on a trip to a change and back.** The new `lastApprovalsViewProvider` is a `StateProvider`, reset whenever the signed-in user id changes. It remembers the last chosen view and is used when the URL names none. The Back link from a change carries no query. The screen `read`s this provider rather than watching it, so a choice does not first re-list the old page in the new view. An AWAITING_ME choice falls back to the default for anyone without CHANGE_APPROVE. Tests: "the view chosen survives a trip to a change and back" and "a URL naming a view opens on it".
5. **(low) THRESHOLD_CHANGE links and the thresholds screen needed REGION_VIEW.**
   - `approvalEntityRoute('REGION', id)` now returns `/approval-thresholds?regionId=id`, not `/regions/id`.
   - The approvals list header has an "Approval thresholds" button (`approvals-thresholds`). It shows when `thresholdRegionsFor(user)` is not empty.
   Tests: the button navigates for an approver, is absent for a viewer, and a unit test covers the route.
6. **(low) Customer logins could reach the approvals list.**
   - The `/approvals` redirect in router.dart sends customer logins to `/`. `/approvals/:id` still admits them, as the banner's View link needs it.
   - On the detail page, a customer's Back, including Back from the error page, goes to `recordRoute`, falling back to `/`.
   Tests: "the list is not for customer logins, though one change is" signs in a customer through the real `routerProvider` and calls both routes' redirects. "a customer's Back returns to the record, not to the staff-only list" covers the detail page.

The test harness in approvals_test.dart gained `/approvals`, built as the app builds it, and a `/approval-thresholds` stub route.

Final: `flutter analyze` reports 0 issues. `flutter test` passes 259 of 259: 61 baseline, 144 from FE-01 to FE-03, and 54 in approvals_test.dart (43 original plus 11 new). No baseline test file was edited.

## FE-05 — 202 pending handling at every write site

**Built** (frontend only)
- New `F/core/api/pending_reply.dart`:
  - `PendingChangeRef {id, summary, operation, operationLabel, regionName, approverCount, supersededId, message}`, parsed tolerantly (`fromJson`, every field optional).
  - `pendingFrom(Response)` is asked **before** any `['id']` parse (critic §3.11). It reads `pendingChange`, then falls back to the `Location` header (`/api/approvals/{id}`) for the id; `message` is the reply's top-level sentence.
  - `pendingIdFromLocation`, `sentForApprovalText`.
  - `showSentForApproval(context, p, {message})` shows "Sent for approval: {summary}" (or `message`) with a **View** action to `/approvals/{id}`. It appends "No one else can approve changes in {regionName} yet. Ask an administrator." when `approverCount == 0`. The router and the unsaved-changes guard are captured when the snack is shown, so View still works after the dialog or page that asked has closed.
  - `invalidateApprovals(WidgetRef)` delegates to FE-04's `approvalsChanged`: the badge, `pendingForRecordProvider`, `tablePageProvider` and `tableSummaryProvider`.
- Call sites (§7.6):
  - `invoice_form_screen.dart` POST: on 202 it shows the notice, invalidates, skips `notifyByEmailAfterSave`, and goes to `/approvals/{id}` (or `/invoices` if the reply names no change).
  - `invoice_detail_screen.dart` PATCH: on 202 it shows the notice and does not update `_saved*`. `_dirty = false`, and `ref.refresh(invoiceDetailProvider(id))` is awaited. `_loadedInto` is then reset and the fields re-seeded from the **live** invoice (G18). Audit and approvals (the banner) are invalidated.
  - `invoices_screen.dart` row cancel: on 202, "Cancellation sent for approval", and the list, tiles and badge are invalidated.
  - `record_payment_dialog.dart`: `_RecordedPayment` is `({int? id, PendingChangeRef? pending, bool notify})`. `showRecordPaymentDialog` shows the notice, skips the email step, and still returns true so callers refresh.
  - `promise_form_dialog.dart`: `_SavedPromise` gains `pending` (and `id` is nullable for a held create). `showPromiseDialog` shows the notice and skips the email step. The override POST shows the notice on 202 and pops `true`. The DELETE override is unchanged.
  - `dispute_detail_screen.dart` approve: on 202 the snack reads "This approval needs a second approver" (with View). There is no email and the page stays put. The dispute, the scoped disputes, audit and approvals are invalidated. Deny is unchanged.
  - `data_table_scaffold.dart` `_showBulkResult`: reads `(result['pending'] as List?) ?? const []`.
    - With no failures, skips or truncation, it gives a snack "N updated, M sent for approval". An all-pending result counts as success.
    - The result dialog gains "M sent for approval" in its count line, and an "Awaiting approval" section (`#id → approval #pid`).
    - It invalidates approvals when any row was held.
  - `approval_thresholds_screen.dart`: the FE-04 local 202 snack now uses `pendingFrom` + `showSentForApproval(message: 'Raising or removing limits needs a second approver')` + `invalidateApprovals`. The private `_idFromLocation` moved into the helper.
  - Unchanged, as §7.6 says: customer create/edit/delete (G19's 409 text stays), payment PATCH, promise cancel, POC seats, dispute create, export, and `move_region_dialog.dart` (FE-03 already shows the server's 400 and keeps the dialog open).
- Tests: `FT/approvals_test.dart` has a new group, "writes held for approval (202)", with 14 cases:
  - `pendingFrom`: null on 200; the full reply; a bare `{id}`; the id from Location; a 202 with no body.
  - The notice text, including the `approverCount == 0` note.
  - Invoice form 202 with notify ticked: the snack, no `GET /api/emails/context`, no "Invoice created", and on `/approvals/41`.
  - Record payment 202 with notify: returns true, the snack, no email, View opens the change.
  - The nobody-can-approve notice.
  - Invoice detail PATCH 202: re-fetched, notes re-seeded to the live value, not dirty, banner asked again, no "Invoice saved".
  - List cancel 202: the notice and the list re-read.
  - Promise create and edit 202: the notice, no email.
  - Override 202: pops true, the notice.
  - Dispute approve 202: the snack, no email, stays on the page, re-read.
  - Bulk: mixed, all-pending and with-failures.

**Deviations from DESIGN.md (with reasons)**
- `pendingFrom` treats **any** 202 from these endpoints as held, not only one whose body has a `pendingChange` map. `PendingChangeRef.id` is therefore nullable: the id comes from `pendingChange.id`, then `Location`, else null, and then no View action is shown. §7.6 says "non-null only when … `data['pendingChange'] is Map`". Under that rule, a 202 with an odd body would fall through to the `['id']` parse and be reported as an error after the server had accepted it. That is exactly critic §3.11's resubmission hazard, and FE-04's review fix 3 already handled that shape on the thresholds screen. A 200 still returns null.
- `PendingChangeRef` also carries the reply's top-level `message` (unused by the notice today, available to callers).
- Bulk: with no failures the snack reads "N updated, M sent for approval" even when N is 0 (an all-pending result). This is §7.6's wording, literally.
- `approval_thresholds_screen.dart` was already FE-04's; only its 202 branch changed, to the shared helper as FE-04's log anticipated. Existing FE-04 tests for it pass unchanged.
- `dart format` re-wrapped two long lines in FE-04's part of `approvals_test.dart` (the customer-login router test). This is whitespace only; no assertion changed.

**API-contract changes versus §6:** none. Built against §6.6 (202 + `pendingChange` + `Location`, no top-level `id`) and §6.7 (`BulkResult.pending[{id, pendingChangeId, summary}]`). LOG-backend.md records no deviations for these; the backend's `BulkResult` has no `pending` yet (BE-13/14), and the frontend reads it as `[]` when absent.

**Known gaps / deferred**
- New write sites from later slices (tasks, rules, run-now/retry/requeue "Queued" on 202) are FE-06/FE-07's; they should use `pendingFrom` only on the §6.6 gated endpoints, since run-now's 202 means "queued", not "held".
- `pendingApproval` on list rows is still not shown as a column (unchanged from FE-04); the refreshed list and the record banner are what show a held change.
- Until BE-13/BE-14 ship, the real server never answers 202, so all of this is exercised against the fake backend only.

**Final counts:** `flutter analyze` 0 issues; `flutter test` 273 passed (259 before + 14 new in `approvals_test.dart`, now 68). No baseline test file edited.

### FE-05 review fixes
- **Medium, accepted (the G18 test could not tell a live re-seed from a stale one):** 'a held invoice edit: the fields go back to what the invoice holds now' now has the fake GET /api/invoices/42 answer different data once the PATCH has been sent (notes 'Changed by Bob', dueDate 2026-07-15, paymentTerm CUSTOM). The test also changes a gated field before saving: the term goes from Net 30 to Net 60, so the due date reads 2026-07-31 and the PATCH sends paymentTerm NET_60. It then asserts that the notes, term dropdown and due date show the live values, and that 2026-07-31 is gone. Re-ran the reviewer's mutant (ref.invalidate plus `_loadedInto = false`, which re-seeds from cached data): it now fails (Expected 'Changed by Bob', Actual 'As agreed').
- **Low, accepted (the held edit used context and ref after the PATCH await without checking mounted):**
  - New `sentForApprovalNotice(context)` in core/api/pending_reply.dart takes the ScaffoldMessenger, GoRouter and unsaved-changes guard up front and returns a `SentForApprovalNotice`. `showSentForApproval` is now a thin wrapper over it, so its behaviour is unchanged.
  - `InvoiceDetailScreen._save` takes the notice before sending. If the page has gone by the time the reply arrives, a held edit still shows "Sent for approval: …" with a working View action, and nothing touches ref or setState. That closes the "user thinks it was discarded" hole.
  - The catch block's setState is now guarded by mounted. The redundant mounted check after the applied-path setState was dropped, because it is covered by the new early return.
  - New test: 'a held invoice edit answered after the page was left is still told of'. It gates the PATCH, navigates away, then releases a 202. It asserts no exception, that the notice is shown, and that View opens /approvals/41. It was red before the fix, with setState() called after dispose().
- **Rejected:** none.
- **Final:** flutter analyze reports 0 issues. flutter test: 274 passed, 0 failed (approvals_test.dart has 69).

## FE-06 — Tasks UI

**Built** (frontend only)
- `F/features/assignees/`:
  - `assignee_providers.dart`: `assigneeContextProvider` (family `({EmailEntityType type, int? entityId})` → `GET /api/assignees/context`, parsed with `EmailContext.fromJson`; live, no scope watch) and `searchAssignablePeople(Dio, q, {entityType, entityId})` → `GET /api/assignees/people` (`q` only when non-empty; `entityType`/`entityId` when given, so people are searched in the record's region, §6.13) → `List<EmailPerson>`.
  - `recipient_chips_field.dart`: `RecipientChipsField({ctx, value, onChanged, allowCustomer=false, single=false, roleFilter, perRecordLabels=false, label='Assignees', searchPeople, knownLabels, errorText, enabled, maxCount=10})`. Written fresh; `send_email_dialog.dart` is untouched. Chips with person/badge/business icons; role chips read "Collection POC · Cara" (both levels → `labelWithLevel`), "· nobody assigned" when unresolved; tooltips list holders. Add rows: "Person…" (only with `searchPeople`) plus one per `ctx.roleGroups`, and "Customer emails" only with `allowCustomer`. `perRecordLabels` reads "Sales POC (each invoice's)" with no holders. Keys: `assignee-<token>`, `assignee-add-<token>`, `assignee-add-person`, `assignee-add-customer` (`assigneeTokenKey`).
  - `people_search_dialog.dart`: generic `PeopleSearchDialog<T>` / `showPeopleSearchDialog<T>` (250 ms debounce).
- `F/features/tasks/`:
  - `task_models.dart`: `TaskStatus {OPEN, IN_PROGRESS, DONE, CANCELLED}` (`parseTaskStatus` falls back to OPEN, G25), `taskStatusLabel`; `TaskEntityType {CUSTOMER, INVOICE, PAYMENT}` (wire, `emailType`, `route`); `TaskItem.fromJson` (§6.12 TaskDto; everything but id/title/status nullable/defaulted; `rule{id,name}`, `createdBy{name}`, `assignees` via `RecordAssignee`); `taskDateWire` (local calendar day, G21); `TaskMove {start, done, reopen, cancel}` with target status, label and bulk action; `taskMovesFor` (assignee: start/done/reopen DONE; TASK_MANAGE: cancel, reopen CANCELLED — §5.9.2).
  - `task_providers.dart`: `taskDetailProvider` (family int; watches `viewAsOfProvider`, `checkAsOf`), `taskCountProvider(({entityType, entityId}))` (`GET /api/tasks/count`; watches the scope, `checkAsOf`; returns `open`, else `count`), `myTasksCountProvider` (`StreamProvider.autoDispose<int>` via `pollUnreadCount` on `/api/tasks/mine/count`; `Stream.empty()` with no request unless staff with TASK_VIEW, §7.3.4; watches the whole scope), `tasksChanged(ProviderContainer, {id})`, `changeTaskStatus` (`POST /api/tasks/{id}/status {status, version}`).
  - `task_actions.dart`: `canViewTasksProvider`, `canManageTasksProvider` (ANDs `!isHistorical`), `canCreateTaskInProvider(regionId)` (`hasIn(taskManage, region)`), `taskRights`/`taskMoves` (server flags `canEdit`/`canChangeStatus` first, else `hasIn(taskManage)` / `assignedToMe`; nothing on a past date), `tasksDetailTab(ref, {entityType, entityId, customerScope, entityLabel, regionId})` (presence depends only on `canViewTasksProvider`; `taskCountProvider` is the badge, G5), `moveTask` (confirms Cancel; 409 → "Someone else changed this task first…" and reload).
  - `tasks_tab.dart`: `TasksTab` — `DataTableScaffold` on `/api/tasks` with local query state (`dueDate,asc`), `extraParams` `{entityType, entityId}` or `{customerId}` (scalars, G1); "New task" (`task-new`) by `canCreateTaskInProvider`; row moves; the Record column only on the customer's tab.
  - `tasks_screen.dart`: `TasksScreen({query, mine})`; `/tasks` → `/api/tasks`, `/my-tasks` → `/api/tasks/mine` (tiles from `…/summary`: count, open, inProgress, overdue, dueToday). Quick filters: "Assigned to me" (`assigneeUserId:eq:<me>`, `/tasks` only), "Overdue" (`overdue:eq:true`), "Open" (`status:eq:OPEN`) — shown only when the schema marks the column filterable (G7). Columns Task, Status, Due (overdue chip), Record, Customer, Assigned to (tooltip: role holders), Region (multi-region only, `regionColumn`). Row actions per `taskMoves` (keys `task-<id>-<move>`) + Open; bulk START/MARK_DONE/REOPEN/CANCEL with TASK_MANAGE; export with EXPORT_DATA; `TaskCard` mobile card; `onBulkDone` → `tasksChanged` (badge, counts, lists). Shared helpers `taskColumns`, `taskRowActions`, `TaskDueText`, `TaskAssigneesText`, `taskMoveIcon`.
  - `task_form_dialog.dart`: `showTaskDialog(context, {entityType, entityId, entityLabel, existing})` → `TaskItem?`. Title (required, 200), description (2000), due date (picker + clear), assignees (`RecipientChipsField` over `assigneeContextProvider((type.emailType, entityId))`, people searched by record). Create: `POST /api/tasks {entityType, entityId, title, description, dueDate, assignees:[EmailToken]}`. Edit: `PATCH /api/tasks/{id}` with only changed fields (`title`, `description`, `dueDate` or `clearDueDate: true`, `assignees`) + `version`; nothing changed → no request. `fieldErrors.title/description/dueDate` under their fields, `fieldErrors.assignees*` under the assignees; 409 explained.
  - `task_detail_screen.dart`: `TaskDetailScreen` on `DetailScaffold`: status chip, overdue chip, move buttons (`task-start`, `task-done`, `task-reopen`, `task-cancel`) and Edit (`task-edit`) per rights; top grid On (record link, "(deleted)"), Customer, Region (multi-region), Due, Created (by), "Created by rule" link to `/automation/{id}` for AUTOMATION, Completed. Tabs Details (description; each assignee with today's holders, primary marked, unresolved warned) and History (`AuditHistoryPanel(entityType: 'TASK')`, AUDIT_VIEW like the other pages). 404 on a past date → "This task did not exist on …".
- Modified:
  - `router.dart`: `/my-tasks` and `/tasks` (nav guard, `RouteQuery.read(defaultSort: 'dueDate,asc')`, page size key `tasks`, distinct `ValueKey`s so the two lists never share state), `/tasks/:id` (nav guard, `onExit: mayLeave`, `pageForId`).
  - `app_shell.dart`: `NavEntry('My tasks', Icons.assignment_ind_outlined, '/my-tasks', [taskView, taskManage], hideForCustomer, badge: NavBadge.myTasks)` and `NavEntry('Tasks', Icons.checklist, '/tasks', …)` after Disputes, before Approvals (no shared prefix, G15); `_NavIcon` maps `NavBadge.myTasks` → `myTasksCountProvider`.
  - Customer, invoice and payment detail screens: `tasksDetailTab(...)` after the Documents tab (customer with `customerScope: true`).
  - `status_chip.dart`: `taskStatusColor`, `TaskStatusChip`. `field_limits.dart`: `taskTitle = 200`, `taskDescription = 2000`.
  - `audit_history_panel.dart`: TASK in `_typeOrder`, `_typeLabels` ("Tasks"), `_routeFor` (`/tasks/{id}`), `_iconFor`; labels for the five `TASK_*` actions; `TASK_STATUS_CHANGED` gets the "Open → Done" headline like promise status changes.
  - `data_table_scaffold.dart` (see deviations): optional `actionPath` and `actionFilters`.
- Tests: `FT/tasks_test.dart` (35): tab query params (`entityType`, `entityId`, `page`, `size`, `sort`) and next page; customer tab by `customerId`; Mark done posts `{status:'DONE', version:3}` (tab row and detail); 409 message + reload; assignee-only and flag-driven moves; the create dialog asks `/api/assignees/context?entityType=INVOICE&entityId=77`, shows role groups and holders, exact POST body with a role token, a searched person (search carries the record) and a due date; title required; 400 `fieldErrors.assignees` under the field; detail view; Cancel confirms; DONE only reopens; PATCH sends only changes + version (+ `clearDueDate`); unchanged edit sends nothing; rule origin; `/my-tasks` hits `/api/tasks/mine` + `/mine/summary`, no locked chip; `/my-tasks` bulk goes to `/api/tasks/bulk` with `assigneeUserId:eq:<me>` added; "Assigned to me" filter; a bulk move rebuilds the badge; nav order/guards; badge shows the count; `myTasksCountProvider` polls `/api/tasks/mine/count` with `regionIds` and asks nothing without TASK_VIEW; the invoice page has no Tasks tab (and asks nothing) without TASK_VIEW, has it with the open count as badge; **a late or changing count never recreates the TabController or moves the selected tab (G5)**; `RecipientChipsField` single / roleFilter / perRecordLabels / knownLabels + remove; tolerant parse. `FT/as_of_screens_test.dart` extended: `_sites` rows "task detail" (Edit/Start/Mark done/Cancel task today, none on 30 Jun, asked as of that day, 404 → "did not exist on", today's 404) and "tasks tab" (New task and row moves); its harness overrides `myTasksCountProvider` (the real one polls on a timer).

**Deviations from DESIGN.md (with reasons)**
- `canViewTasksProvider` does **not** AND `!isHistorical` (§7.7.2 says "both AND `!isHistorical`"). `/api/tasks/**` GETs are AWARE (§6.1) and B3 asks for every list as of a date; hiding the tab on a past date would also change `tabs.length` on a date change and reset the tab (G5). Only `canManageTasksProvider` (and every move/edit/create) is closed on a past date.
- `DataTableScaffold` gained optional `actionPath` and `actionFilters` (default: the list's path, no extra filters). `/my-tasks` lists `/api/tasks/mine`, but §6.12 has bulk and export only on `/api/tasks`; the scaffold would otherwise post to `/api/tasks/mine/bulk`/`export`. `actionFilters: [assigneeUserId:eq:<me>]` keeps "select all matching" to my tasks (same predicate as `/mine`, §5.9.3/§5.9.5). Not shown as chips; existing lists unchanged.
- Additive parameters beyond §7.7.2's signatures: `RecipientChipsField.knownLabels/errorText/enabled/maxCount` (names of people already on a task, the 400 under the field, disabled while saving, the server's 10-token cap); `searchAssignablePeople(..., {entityType, entityId})` (§6.13 allows them; the task form searches in the record's region); `tasksDetailTab(..., entityLabel, regionId)` and `canCreateTaskInProvider(regionId)` (§7.5 region-aware create, like documents).
- `tasksChanged` takes a `ProviderContainer`, not a `WidgetRef`: it runs after a request returns, when the row or dialog that asked may be gone (the pattern of `invalidateDocuments`).
- Task detail History tab is gated on AUDIT_VIEW (as on every other record page), since `GET /api/audit` is `@PreAuthorize(AUDIT_VIEW)`; §6.15's TASK_VIEW is in addition.
- "Open" quick filter is `status:eq:OPEN` (the summary counts OPEN and IN_PROGRESS separately).
- `/tasks/:id` carries the nav guard (G16), like FE-04's `/approvals/:id`.

**API-contract changes versus §6:** none. Built against §6.12 (list with `entityType`/`entityId` or `customerId`, `/summary`, `/mine`, `/mine/summary`, `/mine/count`, `/count`, `{id}`, POST, PATCH with `version`/`clearDueDate`, `/status`, `/bulk`, `/export`), §6.13 (`/api/assignees/context`, `/api/assignees/people`), §6.15 (TASK audit). LOG-backend.md records no task/assignee deviations (BE-12 has not run), so everything is exercised against the fake backend only.

**Known gaps / deferred**
- The customer's Tasks badge asks `GET /api/tasks/count?entityType=CUSTOMER&entityId=<id>` (the only count §6.12 has), while the tab lists by `customerId` (its invoices' and payments' tasks too). **Request to the backend stream (BE-12):** make `/api/tasks/count` for `entityType=CUSTOMER` count by `customerId`, matching the tab; otherwise the badge counts only the customer's own tasks.
- Task notifications' links (`/tasks/{id}`) work; nothing else links to a task yet. Rule links from a task go to `/automation/{id}` (FE-07a creates the route).
- Until BE-12 ships, every task endpoint 404s on the real server: the tab shows the list error, the badge stays 0 (errors are swallowed by the poll), and `/tasks` shows the error.
- Size: ~2,900 changed lines including ~1,050 of tests, above §9.0's 2,500 bound; not split, since every part is in FE-06's own list.

**Final counts:** `flutter analyze` 0 issues; `flutter test` 315 passed (274 before + 35 in `tasks_test.dart` + 6 new `as_of_screens_test` site cases). No baseline test file edited; `list_columns_test` needed no override; `send_email_dialog.dart` and its 32 tests untouched.

### FE-06 review fixes

All three findings in FE-06.review.md held up against the code, and all three are fixed.

- **Medium: the customer's Tasks-tab badge counted different tasks than the tab listed. Fixed.** There is a new `customerTaskCountProvider(customerId)` in `task_providers.dart`. It calls `GET /api/tasks/summary?customerId=<id>` (the list's own filters, §6.12) and counts `open + inProgress`. `tasksDetailTab` uses it when `customerScope` is true, so the badge now counts the invoice and payment tasks that the tab lists (§5.9.5). Invoice and payment tabs still use `/api/tasks/count`. `tasksChanged` invalidates the new provider as well. This withdraws the earlier request to BE-12 to change what `/count` means for CUSTOMER. BE-12 only needs `/summary` to accept `customerId` like the list does (§6.12 already lists it among the list's context parameters). The test "a customer's page asks for its tasks by customer" now also checks that `/count` is not called, that `/summary` is called with `{customerId: 5}`, and that the badge shows 3 (open 2 + in progress 1) while `/count` would have said 0.
- **Low: an IN_PROGRESS task could not be moved back to OPEN. Fixed.** In `taskMovesFor`, `TaskMove.reopen` (target OPEN, bulk `REOPEN`) is now offered for IN_PROGRESS as well as DONE to anyone who may change the status (§5.9.2 OPEN ↔ IN_PROGRESS). This applies to row actions, the detail buttons and bulk. Note for BE-12: bulk `REOPEN` and `POST /status {OPEN}` must accept IN_PROGRESS → OPEN. New test: "a started task can be put back to open, finished or cancelled", which posts `{status: OPEN, version: 3}`.
- **Low: the due-date picker asserted when the due date was more than 5 years out. Fixed.** `_pickDue`'s `lastDate` is now the later of the current due date and today + 5 years, mirroring how `firstDate` already handles past dates. New test: "a due date more than five years out still opens the picker", which checks that no exception is thrown, the DatePickerDialog is shown, and saving the same date sends no PATCH.

Nothing was rejected.

Final result: `flutter analyze` reports 0 issues. `flutter test` passes 317 of 317 (315 before these fixes, plus 2 new cases in tasks_test.dart; tasks_test.dart now has 37).

## FE-07a — Automation UI I: rules list, editor, conditions, run now

**Built** (frontend only)
- `F/features/automation/automation_models.dart`:
  - Enums with tolerant parsers (G25):
    - `RuleStatus` (unknown → ACTIVE) with `ruleStatusLabel`, plus `suspendReasonLabel`.
    - `TriggerKind`, including `MANUAL`, with `isSchedule` and `label`.
    - `TriggerEntity` with `plural`, `schemaEntity` and `emailType`.
    - `RunStatus` with `runStatusLabel`.
  - Helpers: `weekdays`/`weekdayLabel`, `deviceZone()` (`+HH:mm` or `UTC`), `describeTrigger`.
  - `RuleTrigger`: `toJson` always sends all six keys, with null where a key does not apply. `catchUp` is sent for schedules only, and `zone` always.
  - Conditions and actions:
    - `ConditionNode` (sealed) is either `ConditionLeaf{TableFilter}` or `ConditionGroup{op, children}`, each with `toJson`/`fromJson`. `ConditionGroup.empty` exists; `toRequest()` returns null for no condition; `leafCount` is available.
    - `ConditionLimits` (depth 4, 30 leaves, 20 per group).
    - `RuleAction` (sealed) is one of `CreateTaskAction`, `CreatePromiseAction`, `CreateDisputeAction`, `SendEmailAction` or `UnknownAction`. It keeps the whole JSON, so a round trip is exact. `withKey()` gives a keyless action a v4 key once, via `newActionKey()`.
  - DTOs:
    - `RuleOwner`, `RuleSummary` (list row), `RunBrief`, `Rule` (§6.14 RuleDto with the `canEdit`/`editTakesOwnership`/`canRun` flags).
    - `RunTarget`, `RunSummary` (with `statusAt`, `partial` and redacted `entity`), `QueuedRun`, `Effect` (`hidden`).
    - `PlaceholderCatalogue`/`PlaceholderGroup`/`PlaceholderItem`.
    - `RulePreview`/`PreviewSample` (`hidden`, `alreadyDone`)/`PreviewRecord` (the actions are kept raw for 07b).
    - `AutomationHealth`/`DeadEvent`.
- `automation_providers.dart`:
  - `ruleDetailProvider` (family int; watches `viewAsOfProvider`, `checkAsOf`).
  - `placeholdersProvider(entityType)` (live).
  - `runDetailProvider` (as-of, `checkAsOf`).
  - `runEffectsProvider(({runId, page}))` (as-of, `checkAsOf`, size 50).
  - `automationHealthProvider` asks nothing unless the user is staff with AUTOMATION_ADMIN.
  - `rulesChanged(ProviderContainer, {id})`.
  - `asksToTakeOwnership(e)` detects a 400 on `fieldErrors.confirmOwnershipChange`. `ruleFieldErrors(e)`.
  - HTTP helpers:
    - `enableRule` sends `{}` or `{confirmOwnershipChange:true}`. `disableRule`.
    - `resumeRule` sends `{replay, confirmOwnershipChange?}` and returns `skippedWhileSuspended`. `deleteRule`.
    - `runRuleNow` sends `recordIds`/`runAt` (UTC ISO)/`confirmOwnershipChange` only when set; 202 and 200 both parse as `QueuedRun`. `requeueEvent`.
- `rules_screen.dart`:
  - `RulesScreen` is a `DataTableScaffold` on `/api/automation/rules` (entity `automation-rules`, not selectable).
    - Tiles come from `/rules/summary`: Active, Disabled, Suspended and "Failed runs (24 h)".
    - Columns: Rule, Status (`RuleStatusChip`, with the suspended reason as tooltip), Starts, Runs as, Regions (multi-region only), Last run, Next run. There is also a mobile card.
    - "New rule" needs AUTOMATION_MANAGE and a live view.
  - `RuleRights`:
    - It uses the rule's flags when present (editor), else the owner and privileges (list rows carry no flags).
    - `canAct` (Run now, Enable, Resume) belongs to the owner, or to a non-owner AUTOMATION_ADMIN, who takes ownership.
    - `canStop` (Disable, Delete) belongs to the owner or an admin, without taking ownership.
    - Nothing on a past date.
  - `ruleRowActions` (keys `rule-<id>-run|resume|enable|disable|delete`):
    - Run now is hidden for a suspended rule, which offers Resume instead.
  - Ownership and flows:
    - `confirmTakeOwnership` reads "This makes you the rule's owner; it will act with your access".
    - `actTakingOwnership<T>` asks first when ownership is known to change hands. On a 400 `confirmOwnershipChange` it asks, then resends with `true`.
    - `enableRuleFlow`, `disableRuleFlow`, `deleteRuleFlow` (confirm).
    - `resumeRuleFlow`'s dialog has the checkbox "Also act on the changes made while it was suspended" (`replay`). The snack then states the N from `skippedWhileSuspended`.
  - `AutomationHealthCard` (AUTOMATION_ADMIN) shows consumer state, pending/processing/retrying/dead counts, the oldest wait, and dead events with Requeue (hidden while historical). Requeue shows the snack "Event #9 is queued again".
- `run_now_dialog.dart`:
  - `showRunNowDialog(context, {ruleId, ruleName, entity, takesOwnership, ownerName})`.
  - Which records: "Every <entity> that matches" or "Chosen records" (≤ 50, picked with `SearchPickerField` over `searchEmailRecords`).
  - When: "Now" or "At…" (date then time picker). The time must be after now and at most 90 days out.
  - It shows "In: SOUTH" (or "All your regions") from the view scope.
  - Ownership confirmation goes through `actTakingOwnership`. Server `fieldErrors.recordIds`/`.runAt` show under their fields.
  - On 202/200 the dialog shows "Run #88" with a Queued chip and the scheduled time, plus "Open rule" and "Close".
- `rule_editor_screen.dart`:
  - `RuleEditorScreen({id, initialTab})` serves `/automation/new` and `/automation/:id`.
  - A private `_RuleDraft` builds the RuleRequest (every key always present, `regionIds` sorted, `version`, `confirmOwnershipChange`).
  - It holds the one `UnsavedChanges` slot (G17); none of its dialogs register.
  - New rule: a full page in the `InvoiceFormScreen` shape. On a past date it shows a read-only notice, and the draft is kept.
  - Saved rule: a `DetailScaffold`.
    - Title: status chip, then Run now, Resume/Enable/Disable and Delete per `RuleRights`. These are disabled while there are unsaved edits.
    - Top: "Runs as <owner>", the take-ownership note, suspended reason and detail, next run and last run.
    - Tabs: Rule, and History (`AuditHistoryPanel(entityType: 'RULE')`, AUDIT_VIEW).
  - Sections:
    - **Basics:** name (120), description (500), "Most records per run" (1..5000). A new rule also has "Turn it on when it is created".
    - **Trigger:** entity `SegmentedButton` (key `rule-entity`) and kind dropdown (`rule-kind`, including "Only when run by hand"). Schedules add time (`rule-time`), weekday (`rule-day`, weekly only), zone (`rule-zone`: the device offset, UTC, or the stored zone) and catch-up ONE/ALL (`rule-catch-up`). MANUAL shows an explanation.
    - **Regions:** FilterChips over `regionsWith(automationManage)`. Stored regions outside the user's list are kept and shown locked. None chosen reads "every region where the owner can act".
    - **Conditions:** `ConditionBuilder`, with the server's `warnings` inline and a list link when `listLink` is set.
    - **Actions:** read-only summaries of the saved actions (07b adds the cards).
    - **Preview:** a record picker (`searchEmailRecords`), an "As the owner" chip (for the owner or an admin), and a Preview button. It POSTs `/api/automation/rules/preview` `{rule, ruleId?, recordId?, sampleSize: 10, evaluateAs: 'OWNER' only when toggled}` and calls `checkAsOf`. It shows the count, `evaluatedAs`, `asOf`/`asOfNote`, the record's match, the sample (a hidden record reads "A record you cannot see"; "Already acted on"), and problems and warnings.
  - Save:
    - Client checks: the name is required, and max records must be 1..5000.
    - Ownership goes through `actTakingOwnership`, asking first when `editTakesOwnership` and not `canEdit`.
    - POST leads to "Rule created" and `/automation/<id>`. PUT leads to "Rule saved" and a re-seed from the answer.
    - `fieldErrors` are placed under Basics, Trigger, Regions, Conditions or Actions, and the rest go at the bottom. A 409 is explained.
  - Changing the entity with conditions present confirms, then clears the conditions and the preview.
  - On a past date the revision is shown in a separate read-only draft: every input is disabled, Save is disabled, header actions are absent, and Preview still works.
  - Seeding (G18):
    - The editable draft is seeded only from live data.
    - After Enable/Resume/Run it re-reads and re-seeds.
    - A generation key resets the dropdowns on re-seed.
- `condition_builder.dart`:
  - `ConditionBuilder({entity, value, onChanged, enabled, errorText})` loads `tableSchemaProvider(entity.schemaEntity)`.
  - `ConditionGroupEditor` is recursive:
    - An "All of these / Any of these" `SegmentedButton` (`cond-op-<path>`) shows once a group has two or more children, or on nested groups.
    - Leaves are `InputChip`s (`cond-leaf-<path>.<i>`) labelled with `describeFilter`, edited with `showFilterEditor`, and removed with the delete tooltip "Remove condition".
    - "Add condition" (`cond-add-<path>`) and "Add group" (`cond-add-group-<path>`, only while depth < 4).
    - A new group opens with its first condition and the opposite junction.
    - "Remove group" (`cond-remove-<path>`). A nested group left empty is removed.
    - Limits: 20 children per group and 30 leaves overall.
  - Children are appended; `TableQuery.addFilter` is never used.
- `placeholder_button.dart`:
  - `PlaceholderButton({entityType, controller, singleLine, focusNode, enabled, onInserted})` is a `PopupMenuButton` grouped by level (disabled header items), with the tooltip "Insert placeholder".
  - `insertPlaceholder(controller, token, {singleLine})` replaces the selection, or inserts at the end when there is no valid selection. It leaves the cursor after the token. With `singleLine`, line breaks in the inserted text become spaces.
- Modified:
  - `router.dart`:
    - `/automation`: nav guard, `RouteQuery.read(defaultSort: 'name,asc')`, page-size key `automation-rules`.
    - `/automation/new`: `needs(const [automationManage])`, `onExit: mayLeave`. It is declared before `:id`.
    - `/automation/:id`: `needs(const [automationView])`, `onExit: mayLeave`, `pageForId`.
  - `app_shell.dart`: `NavEntry('Automation', Icons.bolt_outlined, '/automation', [automationView, automationManage], hideForCustomer)`, after Approvals and before Regions.
  - `status_chip.dart`: `ruleStatusColor`, `RuleStatusChip` (tooltip with the reason when suspended).
  - `field_limits.dart`: `ruleName = 120`, `ruleDescription = 500`.
  - `audit_history_panel.dart`:
    - RULE is added to `_typeOrder`, `_typeLabels` ("Rules"), `_routeFor` (`/automation/{id}`) and `_iconFor`.
    - Labels are added for the nine `RULE_*` actions.
    - Entries with a `ruleId`, except on the rule's own history, get a "Rule #N" link to `/automation/N`.
- Tests:
  - `FT/automation_condition_builder_test.dart` (10):
    - Builds `AND[leaf, OR[leaf, leaf]]` through the real filter editor, with exact JSON and chip labels.
    - The junction toggle.
    - Edits a leaf in place.
    - Removes a group; a group's last leaf takes the group; the last root leaf makes `toRequest()` null.
    - Two `balance:gt` leaves stay two.
    - No "Add group" at depth 4.
    - Read-only mode.
    - JSON round trip, with tolerant reading of an unknown op and junk nodes.
    - A null condition parses as empty.
  - `FT/rules_screen_test.dart` (17):
    - List params include `regionIds`. Chips, tiles and columns render. There is no health request for a non-admin.
    - Run now, over everything: `{}` body, "Run #88" and Queued, list re-read, "Open rule" navigates.
    - Run now on chosen records at a time: `{recordIds:[77], runAt}` (UTC), `regionIds=2` on the query, "In: SOUTH", and an empty choice is refused client-side.
    - Run now: `fieldErrors.runAt` is shown under its field.
    - Non-owner admin: Run now, Enable and Resume each confirm and send `confirmOwnershipChange: true` (Resume also sends `replay: true`, and the snack gives N). Saying no sends nothing. Disable and Delete need no ownership.
    - A plain manager gets only Open on others' rules.
    - Owner: disable/enable with `{}`. Resume without replay sends `{replay:false}`. A server 400 on ownership leads to a confirm and a resend.
    - The health card with Requeue (and a re-read), and no Requeue while historical.
    - A past-date list sends `asOf`, and no actions or New rule are offered.
    - Nav order and guard.
  - `FT/automation_rule_editor_test.dart` (21):
    - Exact POST body for a new weekly rule (FRIDAY, `catchUp: ALL`, `regionIds: [2]`, condition, `actions: []`, `version: null`, `confirmOwnershipChange: false`), then navigation.
    - The MANUAL trigger shape, with `enabled` false and a null condition.
    - The trigger kind shows time, day and zone as it should.
    - The name is required.
    - Server `fieldErrors` appear per section.
    - An entity change confirms, clears, and loads the customers schema.
    - The unsaved-changes guard.
    - A saved rule shows owner, last run, warnings, actions and the History tab.
    - Stable action keys: the preview and the PUT carry the same keys, the keyless action gets one v4 key, and the exact PUT body is checked.
    - The ownership 400 leads to a confirm and a resend (`[false, true]`).
    - `editTakesOwnership` asks before the first PUT.
    - A 409 is explained.
    - Preview with no scope sends empty query params and no `evaluateAs`. With the toggle it sends `evaluateAs: 'OWNER'`. With regions and a past date it sends `{regionIds:'2', asOf:'2026-06-30'}` and shows the note. A picked record's `recordId` is sent.
    - Past date: Save disabled, the name field disabled, no condition editing, no enabled header actions, Preview works.
    - The new-rule page on a past date shows the notice, and the draft is kept.
    - PlaceholderButton inserts at the cursor, replaces a selection, and falls back to the end in single-line mode.
    - Tolerant rule parsing.
  - `FT/as_of_screens_test.dart` (created by FE-02) gains a "rule editor" `_sites` row. Today it has Run now, Disable, Delete and Add condition; none of them on 30 Jun. The 404 reads "did not exist on" on a past date and "does not exist" today. That is 4 cases.

**Deviations from DESIGN.md (with reasons)**
- **The Actions section is read-only in 07a.** The action cards are FE-07b's. Saved actions are shown as summaries and sent back exactly as the server gave them. A keyless action gets one v4 key on load, which is then stable across preview and save. Until 07b, a *new* rule has no actions, so the server's 400 `fieldErrors.actions` ("1..5 actions") appears under Actions. This is inherent in the 07a/07b split (§9.2), not a narrowing.
- **Resume's offer has no N up front.** §7.7.3 asks for "Also act on the N changes made while it was suspended", but §6.14 gives N (`skippedWhileSuspended`) only in the resume *response*. The checkbox therefore reads "Also act on the changes made while it was suspended", and the snack then states N ("…will act on the 12 changes…" or "…the 3 changes … will not be acted on").
- **"Shows Queued and opens the run".** The run-now dialog turns into the queued run (Run #id, a Queued chip, scheduled time) with "Open rule". Opening the run detail dialog belongs to FE-07b's `run_detail_dialog.dart`, which can replace this.
- **List-row rights come from privileges.** `RuleSummary` (§6.14) has no `canRun`/`editTakesOwnership`, so the list derives rights from the owner id, AUTOMATION_MANAGE and AUTOMATION_ADMIN. The editor uses the server flags, and the server decides either way. A stale owner is covered by the 400 → confirm → resend path.
- **Enabled is switched from the header on a saved rule.** A new rule has the switch "Turn it on when it is created". For a saved rule, `enabled` is sent back unchanged and Enable/Disable live in the header, so a non-owner admin always meets the ownership confirmation on Enable (§5.10.7). The header actions are disabled while there are unsaved edits, since they act on the saved rule and change its version.
- **Past date.** The header actions are absent, not merely disabled: the revision's flags are false (D14) and `RuleRights` gives nothing when historical. Save stays visible but disabled. Preview POSTs the revision's own body to `/rules/preview` with `asOf`, rather than GET `/rules/{id}/preview`. Both evaluate the revision at T; the POST keeps one code path for drafts and revisions.
- **`evaluateAs` is sent only when "As the owner" is on.** CALLER is the server default. This matches §8.8 "evaluateAs when toggled".
- **Entity change.** It confirms and clears the conditions, since they use another list's filters. Dropping action placeholders and tokens that no longer apply goes with the action cards in FE-07b.
- **`PlaceholderButton` has no 07a host field.** Placeholders live in action texts and the manual email compose, both FE-07b. It is built and tested standalone, as §8.8 lists "placeholder inserted at the cursor" in 07a's editor test.
- **Audit panel.** Beyond the RULE type maps and labels, entries carrying a `ruleId` get a "Rule #N" link (§6.15 `ruleId`).
- **The list is not selectable.** §6.14 has no bulk or export for rules. The default sort is `name,asc`, since §6.14 names none.

**API-contract changes versus §6:** none. Built against §6.14:
- `/rules` list and summary; `/rules/{id}`; POST and PUT `/rules`.
- `/enable`, `/disable`, `/resume`, DELETE, and `/run` (202/200).
- `/rules/preview`, `/placeholders`, `/runs/{id}`, `/runs/{id}/effects`, `/health`, `/events/{id}/requeue`.
- §6.15 RULE audit, and §6.16 `automation-rules`.

LOG-backend.md records no automation deviations, because BE-16..19 have not run.

**Known gaps / deferred**
- FE-07b:
  - The action cards and email action dialog.
  - Rendered per-action preview (`PreviewRecord.actions` is parsed and kept raw).
  - The Runs tab and run detail (`runDetailProvider`/`runEffectsProvider` are ready), and `RunStatusChip`.
  - Dispute and promise assignees, and placeholders in `send_email_dialog.dart`.
- Until BE-16..19 ship, every `/api/automation/**` call 404s on the real server:
  - The list and the editor show the error.
  - The health card shows its error, for admins only.
  - The nav entry shows only for holders of AUTOMATION_VIEW or AUTOMATION_MANAGE, which BE-16 creates.
- Size: about 3,450 lines of lib plus about 1,720 lines of new tests (about 5,200), over §9.0's 2,500 bound. It was not split, because every file is in FE-07a's own list.

**Final counts:** `flutter analyze` reports 0 issues. `flutter test` passes 369: 317 before, plus 10 in `automation_condition_builder_test`, 17 in `rules_screen_test`, 21 in `automation_rule_editor_test`, and 4 new `as_of_screens_test` site cases. No baseline test file was edited, `list_columns_test` needed no override, and `send_email_dialog.dart` and its tests are untouched.

### FE-07a review fixes

All four findings were confirmed against the code and fixed (`rule_editor_screen.dart`, `automation_rule_editor_test.dart` only).

- **409 then Undo re-seeded the stale version (medium).** On a 409 from PUT the editor now invalidates `ruleDetailProvider(id)`. The page keeps showing the user's edits while the reload runs (skipLoadingOnRefresh), and Undo re-seeds from the reloaded rule and its version. New test: "after a stale save, Undo shows theirs and the next save carries their version" checks that the first PUT sends version 3, Undo shows the other user's name, and the second PUT sends version 4 and succeeds.
- **Locked region chips made a rule unsaveable (medium).** A region in the rule's `regionIds` that the editor can't offer can now be deselected when the page is editable. Once removed it can't be added back, and it stays locked when read only. The chip shows the region's code when the user has a grant there, otherwise `Region #id`. New test: "a region the editor cannot choose can be taken off, never put back".
- **A stale preview survived draft and scope changes (low).** `_clearPreview()` now runs on every `_changed` draft edit, on every (re)seed, and through `ref.listen(viewScopeProvider)` on any change of regions or date, for both new and saved rules. The chosen preview record and "As the owner" are inputs, so they are kept. New test: "an answer goes as soon as the draft, the regions or the day change".
- **Region choices used the global AUTOMATION_MANAGE (low).** This deviates from §7.7.3's wording to follow §5.10.7: choices are now `regionsWith(<entity VIEW privilege>)` for the trigger entity (CUSTOMER/INVOICE/PAYMENT_VIEW). AUTOMATION_MANAGE is global, so dropping it narrows nothing. Changing the entity re-derives the choices, and a chosen region that is no longer offered becomes a removable chip. **FE-07b should also intersect these choices with each action's privilege** (TASK_MANAGE, PROMISE_MANAGE, DISPUTE_MANAGE, EMAIL_SEND). New test: "only regions where the kind of record can be seen are offered". Test fixture: the regions in `automation_rule_editor_test.dart` now carry INVOICE_VIEW and CUSTOMER_VIEW, and a READ-only WEST region with only CUSTOMER_VIEW was added.

Rejected: none.

Final: `flutter analyze` reports 0 issues, and `flutter test` passes 373 (369 + 4 new).

## FE-07b — Automation UI II: actions, rendered preview, runs; dispute and promise assignment; email placeholders

**Built** (frontend only)
- `F/features/automation/automation_models.dart` (additive):
  - `ActionType` {CREATE_TASK, CREATE_PROMISE, CREATE_DISPUTE, SEND_EMAIL}, with `label`, `privilege` (TASK_MANAGE, PROMISE_MANAGE, DISPUTE_MANAGE, EMAIL_SEND) and a tolerant `parse`. `maxRuleActions = 5`.
  - `promiseAmountFields` and `fieldApplies(field, entity)`.
  - `newRuleAction(type, entity)` builds a fresh action with a v4 key and the §6.14 shape. Defaults: ONCE_PER_RECORD and the guards on; a task is due TODAY+2; a promise takes the record's own amount (Invoice.Balance, Payment.Amount, or Customer.OverdueAmount on customer rules), is due TODAY+7 with no Collection POC (the customer's default), and has `linkRecord` only on invoice rules; a dispute proposes `none`; an email is from the owner with `fallbackToOwner: true`.
  - `actionFor(action, entity)` fits an action to another trigger entity. It removes `{{Invoice.…}}`/`{{Payment.…}}` placeholders (fallbacks included), RECORD-level tokens, `INVOICE_DUE_DATE` bases, Invoice/Payment amount fields, `cancel`/`void` proposals and `linkRecord` that the new entity lacks. It returns the same instance when nothing changes.
- `action_cards.dart`:
  - `ActionCards` shows one `ActionCard` per action (keyed `rule-action-<key>`), followed by an "Add action" menu (`action-add`, items `action-add-<TYPE>`). The menu offers only the types whose privilege the user holds and stops at 5.
  - Every card has a repeat control (`action-<i>-repeat`) and a remove button (`action-<i>-remove`). Each edits its action's JSON in place, so unknown keys and the action key round-trip exactly.
  - Task: title and description with a `PlaceholderButton`; due date as none, TODAY or INVOICE_DUE_DATE (invoice rules) plus a −30..365 offset; assignees; `onlyIfNoOpenTask`.
  - Promise: amount (FIELD dropdown or FIXED money); promised date (base plus 0..365); Collection POC (`single`, `roleFilter: {COLLECTION_POC}`, trigger-entity context, empty means the customer's own); co-assignees (PROMISE context); notes with placeholders; `linkRecord` (invoice rules); `onlyIfNoOpenPromise`.
  - Dispute: reason with placeholders (2000); proposal (`none`, `cancel` on INVOICE, `void` on PAYMENT); assignees (DISPUTE context).
  - Email: a From/To/Subject summary plus "Edit email…" (`action-<i>-edit-email`).
  - Token fields use `RecipientChipsField(perRecordLabels: true)` over `/api/assignees/context?entityType=…` with no record. People are searched with `/api/assignees/people`. Server `fieldErrors` `actions[i].x` show under the field they name (title, description, notes, reason, amount.value, due/promisedDate offsets, assignees, collectionPoc) or at the foot of the card.
- `email_action_dialog.dart`:
  - `showEmailActionDialog(context, {entity, action, ownerName, people})` returns `{from, fallbackToOwner, to, subject, body}`.
  - From (`email-from`): "The rule's owner (X)", each role "(each customer's/invoice's)", or "Someone else…" (people search).
  - "When they can't send it, send it from the owner instead" (`email-fallback`), shown when From is not the owner.
  - To: `RecipientChipsField(allowCustomer: true, perRecordLabels: true)`, 1..10 tokens.
  - Subject (single line) and body, each with a `PlaceholderButton`. Client checks: at least one recipient, and a subject.
  - Shared helpers: `tokensIn`/`tokenIn`, `ruleRoleLabel`, `ruleTokenLabel`, `emptyRuleContext`, and `RuleContextBuilder`. If the roles fail to load, the builder shows an error with Retry but still lets people and customer addresses be added.
- `run_detail_dialog.dart`:
  - `showRunDetailDialog(context, {runId, rule})`. A link followed from the dialog closes it, then goes through `goGuarded`.
  - Shows the status chip (with `statusAt` on a past date), what set the run off (`runTriggerLabel`), who requested it, and the run's record (greyed "An invoice you cannot see" when hidden).
  - Also shows the scheduled time and lateness, the counts (tooltip: current totals), the "Counts only records you can see" note (`run-partial`) when `partial`, truncation, the message and finish time.
  - Effects are paged through `runEffectsProvider` ("Show more"). Each is named as the rule words its action, or by its type when the rule no longer has it, with a target link (`effect-<id>-target`) and a result link (`effect-<id>-result`: Task/Promise/Dispute/Email #, or "Approval #N (waiting)" for PENDING_CHANGE → `/approvals/N`). Messages and warnings are shown too.
  - A hidden target is a disabled (greyed) `ListTile` reading only "An invoice you cannot see", with no label, link, result or message.
  - Retry (`run-retry`) appears for a FAILED run when `RuleRights.canAct`, live only. It POSTs `/api/automation/runs/{id}/retry` with `{}`, or `{confirmOwnershipChange: true}` after the take-over confirmation for a non-owner admin on a MANUAL run (400 fallback via `actTakingOwnership`). It then snacks "Run #N is queued" and switches the dialog to the new run.
  - A QUEUED or RUNNING run is re-read every 2 s for at most 2 minutes, reusing `EmailRefresher`.
- `rule_runs_tab.dart`: `RuleRunsTab(rule)` is a `DataTableScaffold` on `/api/automation/rules/{id}/runs` (entity `automation-runs`, local query, default sort `createdAt,desc`, not selectable).
  - Columns: Run, Status (`RunStatusChip` with `statusAt` and "now X" on a past date), Started by, Record (hidden records greyed), When, Results (tooltip: current totals), Finished. There is a mobile card.
  - A `runs-partial` note appears when any row is `partial`. Tapping a row opens the run detail.
  - It polls the same `tablePageProvider` request every 2 s, for at most 2 min, while any run is in flight.
- `rule_editor_screen.dart`:
  - The Actions section is now `ActionCards`, editable where the page is. Action errors clear on edit, and list-level `actions` errors, or errors for a gone index, show under the section.
  - Rendered preview: `_RenderedAction` (`preview-action-<key>`) shows, per action of the picked record, its name, the rendered texts (Title, Due, Amount, Subject, Message, …), the email's From/To, the assignees with today's holders, and problems and warnings. It shows nothing on a past date, where the server sends `actions: null` and the note.
  - A **Runs** tab sits between Rule and History.
  - Changing the entity now also runs `actionFor` over the actions. It confirms when the conditions or any action would change, with the sentence "Placeholders, people and choices in the actions that only invoices have will be taken out."
  - Region choices are intersected with each action's privilege, as FE-07a's review asked.
  - Names of people picked on the page are kept in a `_people` map for the chips and summaries.
- `status_chip.dart`: `runStatusColor`, and `RunStatusChip({status, statusAt})`.
- `placeholder_button.dart`: gained `hideWhenUnavailable`, which renders nothing until a non-empty catalogue loads.
- `F/features/email/send_email_dialog.dart` (additive):
  - A `PlaceholderButton` (`email-subject-placeholder` with `singleLine`, `email-body-placeholder`) now sits beside Subject and Message, with `hideWhenUnavailable: true`.
  - It is offered for CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE, in single, picker and bulk modes, and not for a restricted (customer-login) context.
  - An insert schedules the preview.
  - A 400 whose `fieldErrors` carry `subject` or `body` shows the message under that field, and the form's general error stays empty when those are the only errors.
  - No existing widget key, label or field order changed. The fields are now wrapped in `Row(Expanded(field), button)`.
- Dispute detail:
  - `titleTrailing` gains an "Internal" chip (`dispute-internal`) and a "Created by rule" `ActionChip` (`dispute-rule`) that goes to `/automation/{ruleId}`.
  - An "Assigned to" grid item shows the assignee chips with Edit (`assignees-edit`), gated by `!historical && staff && hasIn(DISPUTE_MANAGE, regionId)`.
  - The proposed change reads "(none)" for internal disputes.
  - For a CUSTOMER target the Resolve tab is an acknowledge panel: "Acknowledge" (`dispute-acknowledge`, POST approve) and "Close without change" (deny). It has no applied-JSON editor.
- Promise detail: an "Also assigned to" grid item shows the co-assignee chips, with Edit when `canManage`.
- New `F/features/assignees/record_assignees.dart`:
  - `RecordAssigneesView` shows the chips: a role with today's holders, primary first; a person by name; unresolved ones warned.
  - `showRecordAssigneesDialog` uses `RecipientChipsField` over `/api/assignees/context?entityType=&entityId=` and searches people by record. It PUTs `{assignees:[…]}` to `/api/disputes/{id}/assignees` or `/api/promises/{id}/assignees`. An unchanged list sends nothing, and 400 `fieldErrors.assignees*` shows under the field.
- `shared/models/dispute.dart`: `disputeTargetsRaisedByPeople = [INVOICE, PAYMENT]`. `dispute_create_dialog.dart` switches explicitly over all three targets, and asserts that a person's target is in that list.
- `audit_history_panel.dart`: labels for `DISPUTE_ASSIGNEES_CHANGED` and `PROMISE_ASSIGNEES_CHANGED`.
- Tests:
  - `FT/automation_rule_editor_test.dart` gains the 13-case "actions (07b)" group:
    - Exact JSON for a task card (placeholder at the cursor, INVOICE_DUE_DATE −3, customer- and record-level roles, guard off, EVERY_TIME).
    - A promise card with a fixed amount, promised date, Collection POC (only COLLECTION_POC offered), PROMISE-context co-assignees, notes and no link. Also the promise's default FIELD amount.
    - A dispute card (reason with placeholder, `cancel`, DISPUTE-context assignees).
    - The email dialog's exact `from`/`to` tokens (role sender with no fallback, customer plus record role) and inserted subject placeholder, plus its validation. A person as sender (`{type: USER, userId: 7}`).
    - Offered types by privilege, and at most five.
    - Region intersection with action privileges.
    - Per-action server errors under their fields, cleared on edit.
    - An entity change that confirms, then drops the invoice-only placeholder, token and due base.
    - Read-only cards on a past date.
    - The rendered preview.
    - The Runs tab's request.
  - New `FT/run_detail_test.dart` (12 cases):
    - Effects are named from the rule, with target and result links (task, approval) that leave the dialog.
    - A hidden target is a greyed tile with no label, link or result; the hidden run entity is greyed; the partial note shows, and is absent otherwise.
    - Retry posts `{}` to `/retry` and shows the queued run. A non-owner admin's MANUAL retry confirms, then sends `confirmOwnershipChange: true`. There is no Retry for a non-runner or on a past date.
    - The dialog polls a QUEUED→RUNNING→SUCCEEDED run every 2 s and then stops.
    - The runs tab: list parameters, partial note, hidden record and row→dialog; `statusAt` with "now Succeeded" on a past date; polling stops once nothing is in flight.
  - New `FT/send_email_placeholders_test.dart` (8 cases):
    - The subject button inserts `{{Invoice.Number}}` at the cursor (selection after it) and the POST carries it. The body button inserts at its own cursor.
    - The 400 `fieldErrors.subject` shows between the subject and the message, and the form stays open.
    - Bulk mode returns the tokenised subject.
    - There is no button for a customer login, for a product, or when no catalogue is available (no tooltip, no icon). A promise asks for `entityType=PROMISE`.
  - New `FT/record_assignees_test.dart` (11 cases):
    - Dispute: internal badge, "Created by rule" link, and chips. Edit asks for the DISPUTE context and searches people by record, then PUTs the exact list, re-reads and shows it. A 400 appears under the field. There is no Edit without DISPUTE_MANAGE in the region, on a past date, or when the server sends no `assignees`. A CUSTOMER target shows Acknowledge and Close without change, and posts approve with the `none` change.
    - Promise: co-assignees with Edit and the exact PUT. An unchanged list sends nothing. There is no Edit with `canManage: false`.
    - A unit test of the dispute target list.

**Deviations from DESIGN.md (with reasons)**
- **New file `F/features/assignees/record_assignees.dart`**, not in the slice's Create list. The dispute and promise pages need the same chips-plus-edit-dialog UI, so it lives in one place.
- **New test file `FT/record_assignees_test.dart`.** §8.8 names no test for the dispute and promise assignee UI, but §9.0 needs every behaviour covered.
- **Files touched beyond the slice's Modify list:**
  - `automation_models.dart` (action helpers).
  - `placeholder_button.dart` (`hideWhenUnavailable`): hides the button in the compose dialog when there are no placeholders. Otherwise, before BE-18 (a 404), a "could not be loaded" tooltip would appear, and the 32 pinned compose tests assert that no stray tooltips exist.
  - `audit_history_panel.dart` (two labels).
- **Region choices are intersected with every action's privilege.** This follows the FE-07a review request and the §5.10.7 server rule. `automation_rule_editor_test.dart`'s NORTH and SOUTH region fixtures therefore gained TASK/PROMISE/DISPUTE_MANAGE and EMAIL_SEND. This is a fixture change only, and no assertion changed.
- **No manual-email placeholders for customer logins (restricted context).** §7.7.3 lists the entity types but is silent on logins. Customer logins write plain text, since the POC placeholders are about staff they may not see (E13). The server still renders whatever they type.
- **Dispute assignee Edit uses `hasIn(DISPUTE_MANAGE, regionId)`, not `canResolve`.** §6.13 gates the PUT on DISPUTE_MANAGE in the region, while `canResolve` is also false once the dispute is decided. On the promise page Edit follows `canManage`, as §7.7.3 says.
- **The assignee section shows only when the detail DTO carries `assignees`.** A server before BE-19 sends none, and would refuse the PUT.
- **"Acknowledge / Close without change" applies to CUSTOMER targets.** §5.10.11 allows only `none` there. Those targets get no applied-JSON editor, and Approve/Deny are renamed. An internal dispute on an invoice or payment keeps the normal panel, since `cancel`/`void` are valid there.
- **Hidden effect wording.** A hidden effect reads "An invoice you cannot see" (its type only). §8.8's "no label" is read as no record label, link, result, message or warnings.
- **The runs list also polls** (every 2 s, for at most 2 min, while any row is QUEUED or RUNNING), as §7.7.3 states the polling for both the list and the detail.
- **`run_now_dialog.dart` is unchanged.** The queued run appears in the new Runs tab, and a run can be opened from there. The 07a "Open rule" behaviour stays.

**API-contract changes versus §6:** none. Built against:
- §6.14: RuleRequest action shapes; PreviewDto `record.actions` (`rendered`, `assignees` as AssigneeDto, `email` as the email PreviewDto, `problems`, `warnings`); `/rules/{id}/runs`, `/runs/{id}`, `/runs/{id}/effects`, `/runs/{id}/retry` (202 `{runId, eventId, status}`); `/placeholders`.
- §6.13: `/api/assignees/context` and `/people`; PUT `/api/disputes/{id}/assignees` and `/api/promises/{id}/assignees`.
- §6.5: DisputeDto `internal`/`origin`/`ruleId`/`assignees`, PromiseDto `assignees`.
- §5.10.4: 400 `fieldErrors.subject`/`.body` on manual email.

LOG-backend.md records no automation, assignee or dispute deviations (BE-16..19 have not run).

**Known gaps / deferred**
- Until BE-16..19 ship, every endpoint here 404s on the real server:
  - The cards show "Roles could not be loaded" with Retry, but people and customer addresses can still be added.
  - The placeholder buttons are disabled (rule editor) or absent (compose).
  - The Runs tab shows the list error.
  - The dispute and promise assignee sections are hidden, because no `assignees` are sent.
- A USER token's name shows only for people picked on the page. The rule DTO carries no names, so a saved rule's person reads "User #7" until picked again. §6.14 has no names in RuleRequest/RuleDto. The server's preview renders `assignees` with names.
- `PreviewDto.record.actions[].rendered` field names beyond `title`, `dueDate`, `subject` and `body` (§6.14 shows only those) are rendered generically (`humanizeEnum(key): value`, money for `amount`).
- The flaky `region_selector_test` "every dashboard figure" (FE-02, real-time `settled()`) failed once under full-suite load, then passed on three single runs and two full runs. It was not touched here.
- Size: about 2,530 lib lines plus about 1,720 test lines (about 4,250 changed), over §9.0's 2,500 bound. It was not split, because every part is in FE-07b's own scope, apart from the shared assignee widget noted above.

**Final counts:** `flutter analyze` reports 0 issues. `flutter test` passes 417 of 417:
- 373 before this slice.
- 13 new in `automation_rule_editor_test` (now 38).
- 12 in `run_detail_test`.
- 8 in `send_email_placeholders_test`.
- 11 in `record_assignees_test`.

The 32 `send_email_dialog` tests in `email_compose_test.dart` are unchanged and pass. No baseline test file was edited, and `list_columns_test` needed no override.

### FE-07b review fixes

All four findings were checked against the code, found valid, and fixed. None were rejected.

- **(medium) Run now never opened the run (§7.7.3).** `run_now_dialog.dart`: after a run is queued, the dialog now offers **Open run** (key `run-now-open-run`, the primary button). It closes the dialog and opens `showRunDetailDialog` for the queued run over the page it was opened from. `showRunNowDialog` gained an optional `Rule? rule`, which the rule editor passes so the run detail can name the actions. **Open rule** now goes to `/automation/{id}?tab=runs`. Tests: `rules_screen_test` checks `tab=runs` on Open rule, and a new case covers Open run.
- **(low) The "Created by rule" chip linked viewers without AUTOMATION_VIEW to a guarded route.** `dispute_detail_screen.dart`: the chip is an `ActionChip` linking to the rule only when `user.has(AUTOMATION_VIEW)`. For everyone else it is a plain `Chip` with the same key and label. A rule outside the viewer's RuleVisibility still gives a 404 on the rule page, the same as any stale link, because the dispute DTO carries no visibility flag. Tests: in `record_assignees_test`, the existing case (Rita, a cashier without AUTOMATION_VIEW) now expects a plain chip and no navigation. A new case gives her AUTOMATION_VIEW and follows the link to `/automation/7`. The test's `_pump` gained a `user` parameter.
- **(low) The "Days" offset field snapped back to '0'.** `action_cards.dart`: `_sync` now treats a field still being typed (`''` or `'-'`) as the 0 it sent, so it no longer rewrites the text or moves the cursor. `onChanged` sends `int.tryParse(text) ?? 0` for every text, so the special case for `'-'` was dropped. Test: a new case in `automation_rule_editor_test` types 2, clears the field, types `-` and then `-7`. The field shows each text as typed, and the rule saves `offsetDays: -7`.
- **(low) The compose dialog's server subject/body error stayed after an edit.** `send_email_dialog.dart`: the subject listener now clears `_subjectError` when the text changes. A new body listener, which tracks the last text so cursor moves don't count, clears `_bodyError`. Placeholder-button inserts count as edits. No existing key, label or field order changed, and the 32 pinned `email_compose_test` cases pass unchanged. Tests: the subject case in `send_email_placeholders_test` now also checks that the error goes on edit. A new case covers the body.

Final: `flutter analyze` reports 0 issues. `flutter test` passes 421 of 421: the 417 from before plus 4 new cases (rules_screen_test +1, record_assignees_test +1, automation_rule_editor_test +1, send_email_placeholders_test +1).
