# Frontend architecture map (Flutter): conventions, extension points and gotchas for the automation, regions, maker-checker and as-of work

Paths are relative to `frontend/`. Line numbers come from HEAD `321150e`. I did not edit any file.

---

## 0. Summary of what matters most

- **Stack.** Riverpod 2.6.1 (old-style `Provider`, `FutureProvider`, `StateNotifierProvider`, `StateProvider`, with no code generation), go_router 14.8.1, dio 5.11.0. Models are hand-written `fromJson` factories: no freezed and no json_serializable.
- **One route table.** `lib/core/router.dart` holds a `ShellRoute` that wraps every signed-in screen in `AppShell`. The sidebar entries are the `navEntries` list (`lib/shared/widgets/app_shell.dart:24-45`). Route guards reuse that list through `navPrivilegesFor`.
- **One list engine.** Every list screen is a `DataTableScaffold<T>` (`lib/core/table/data_table_scaffold.dart`). It is driven by `TableQuery`, which is held in the URL (`RouteQuery`), and by a server schema (`GET /api/table-schemas/{entity}`). All list rows and all summary tiles go through two providers: `tablePageProvider` and `tableSummaryProvider` (`lib/core/table/table_providers.dart:56-72`). **That is the single choke point for injecting region and as-of into lists.**
- **One detail engine.** `DetailScaffold` with `DetailTab`s (`lib/shared/widgets/detail_scaffold.dart`). Tabs are built only when first opened, and the open tab is kept in the URL as `?tab=`.
- **Recipients.** The existing "role at a level" model for recipients is `EmailToken` / `EmailRoleOption` (`lib/features/email/email_models.dart:23-90, 236-287`). There is also a mode that returns a To/From/subject payload without picking a record: `openBulkEmailParams` (`lib/features/email/send_email_dialog.dart:62-69`). Both are the natural building blocks for the rule-action editor (A3/A4).
- **No global view scope exists.** The only app-wide UI state is page sizes (`PageSizeStore`) and the dashboard's months toggle (`dashboardMonthsProvider`). Region and as-of must be added as a new core provider and wired into roughly 6 choke points (section 15).

---

## 1. Stack, build and lint

**`pubspec.yaml`**
- `pubspec.yaml:9-20` lists the dependencies: `flutter_riverpod ^2.5.1`, `go_router ^14.6.1`, `dio ^5.7.0`, `flutter_secure_storage`, `intl ^0.19.0`, `shared_preferences`, `fl_chart ^1.2.0`, `web: 1.1.1`.
- Locked versions: flutter_riverpod/riverpod 2.6.1, go_router 14.8.1, dio 5.11.0. The lock file requires Dart >=3.12 and Flutter >=3.44 (`pubspec.lock:632-634`).
- Dev dependencies are only `flutter_test` and `flutter_lints ^4.0.0` (`pubspec.yaml:22-25`). There is **no build_runner, riverpod_generator, freezed or mockito**.

**`analysis_options.yaml:1-11`**
- Includes `flutter_lints`.
- Sets `prefer_const_constructors`, `prefer_const_constructors_in_immutables` and `avoid_print: false`.
- Sets **`constant_identifier_names: false`**, so enum constants mirror the backend wire values (`UNPAID`, `PARTIALLY_KEPT`). New enums (`TaskStatus.OPEN`, `ApprovalStatus.PENDING`, `TriggerType.ON_CREATE`) must follow this.

**Running tests:** `cd frontend && flutter test`. Flutter is installed at `/opt/homebrew/bin/flutter`. `test/list_columns_test.dart` needs Roboto from the Flutter SDK cache (`test/support/roboto.dart:6-23`) and runs with `TargetPlatformVariant.only(TargetPlatform.macOS)`.

**Other notes**
- There is only a light theme: `AppTheme.light()` at `lib/core/theme.dart:6-26`. It seeds from `0xFF1E88E5`, uses Material 3, outlined dense inputs, and sets the minimum height of filled and outlined buttons to 46.

---

## 2. Bootstrap and state management (Riverpod conventions)

### Bootstrap
- `lib/main.dart:6-8`: `runApp(const ProviderScope(child: GeneInvoiceApp()))`.
- `lib/app.dart:17-23`: after the first frame, runs `authControllerProvider.notifier.bootstrap()` and `pageSizeStoreProvider.notifier.hydrate()`. **Any new persisted app-wide state, such as a region or as-of store, should be hydrated here.**
- `lib/app.dart:27-33`: `MaterialApp.router(theme: AppTheme.light(), routerConfig: ref.watch(routerProvider))`.

### Provider kinds in use (complete list)

| Kind | Where | Purpose |
|---|---|---|
| `StateNotifierProvider<AuthController, AuthState>` | `features/auth/auth_controller.dart:71-73` | session |
| `Provider<CurrentUser?>` | `auth_controller.dart:75-76` (`currentUserProvider`) | **what the UI watches**; tests override it |
| `StateNotifierProvider<PageSizeStore, Map<String,int>>` | `core/table/table_providers.dart:118-119` | persisted per-entity page size |
| `StateProvider<int>` | `features/dashboard/dashboard_providers.dart:152` (`dashboardMonthsProvider`) | only existing screen-wide control; precedent for a dashboard/global selector |
| `Provider<Dio>` / `Provider<TokenStorage>` | `core/api/api_client.dart:15-46` | HTTP |
| `Provider<GoRouter>` | `core/router.dart:35` | routing |
| `Provider<UnsavedChanges>` | `core/unsaved_changes.dart:20` | leave-page guard |
| `FutureProvider(.autoDispose)(.family)` | everywhere | all reads |
| `StreamProvider.autoDispose<int>` | `features/notifications/notifications_providers.dart:15`, `features/email/email_providers.dart:49` | polled badges (30 s) |
| `Provider<bool>` permission providers | `canSeePocProvider`, `canAssignPocProvider` (`features/poc/poc_providers.dart:103-113`), `canSendEmailProvider` / `canViewEmailProvider` (`features/email/email_actions.dart:18-21`), `canViewDocumentsProvider` / `canManageDocumentsProvider` / `canUploadDocumentsProvider` (`features/documents/document_actions.dart:13-22`), `canConnectGmailProvider` (`email_providers.dart:57-60`) | gating |

### Conventions to copy

**Read providers** are `FutureProvider.autoDispose.family` that `ref.watch(dioProvider)` and parse the response:
```dart
// lib/features/customers/customers_screen.dart:35-40
final customerDetailProvider =
    FutureProvider.autoDispose.family<Customer, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/customers/$id');
  return Customer.fromJson(res.data as Map<String, dynamic>);
});
```

**Detail providers live in the list screen's file:**
- `customerDetailProvider`: `customers_screen.dart:35`
- `invoiceDetailProvider`: `invoices/invoices_screen.dart:22-27`
- `paymentDetailProvider`: `payments/payments_screen.dart:22-27`

Newer features use a separate `*_providers.dart` file instead: `promises/promise_providers.dart`, `disputes/disputes_providers.dart`, `documents/document_providers.dart`, `email/email_providers.dart`, `poc/poc_providers.dart`. **Use the `*_providers.dart` style for Tasks, Automation and Approvals.**

**Family keys come in two styles.** Both need value equality.
- An `@immutable` class with hand-written `==`/`hashCode`: `PromiseScope` (`promise_providers.dart:7-22`), `DisputeScope` (`disputes_providers.dart:7-24`), `TableRequest` (`table_providers.dart:11-44`), `ReferenceSearch`, `AssignableQuery`.
- A Dart **record typedef**, which has structural equality automatically. This is the newer and preferred style:
```dart
// lib/features/documents/document_providers.dart:13-14
typedef DocumentListKey = ({DocumentEntityType type, int entityId, int page, int size});
typedef DocumentRecordKey = ({DocumentEntityType type, int entityId});
```
The same style is used at `email_providers.dart:12, 26` and `audit_history_panel.dart:175`. ⚠ A `List` or `Set` inside a record or a `==` class compares **by identity**. See gotcha G1.

**After a mutation, invalidate whole families, not single instances.** Examples:
- `ref.invalidate(tablePageProvider); ref.invalidate(tableSummaryProvider);` at `customers_screen.dart:205-206`.
- `customer_detail_screen.dart:120-123` also invalidates `customerDetailProvider(widget.id)` and `auditHistoryProvider`.
- `dispute_create_dialog.dart:137-139` invalidates `scopedDisputesProvider`, `tablePageProvider` and `auditHistoryProvider`.
- Helper functions bundle these: `invalidateDocuments(container)` (`document_providers.dart:48-52`), `refreshDashboard(ref)` (`dashboard_providers.dart:236-245`), `invalidateGmailStatus` (`email_providers.dart:81-86`).

**Providers that depend on the signed-in user** watch `currentUserProvider.select((u) => u?.id)` and short-circuit when nobody is signed in (D-70):
- `tableSchemaProvider` at `table_providers.dart:46-54` returns `Completer<TableSchema>().future` when there is no user.
- `inboxUnreadCountProvider` at `email_providers.dart:49-55` returns `Stream.empty()`.
- `myPocScopeProvider` at `poc_providers.dart:115-121` is **not autoDispose**. It is cached for the whole session and keyed only by user id, so it will not refetch when the region changes (G22).

**Mutations use `ref.read(dioProvider)`** inside widget state; no repository or service layer exists. Dialogs that outlive their context capture `ProviderScope.containerOf(context, listen: false)` first (`send_email_dialog.dart:303-311`, `documents_tab.dart` delete flow).

---

## 3. API client and error handling (`lib/core/api/api_client.dart`)

- **Base URL** (`:10-13`): `String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080')`.
- **`dioProvider`** (`:17-46`):
  - Timeouts are 15 s connect and 30 s receive; `contentType: 'application/json'`.
  - The `onRequest` interceptor (`:27-33`) adds `Authorization: Bearer <token>` read from `TokenStorage`.
  - `onError` (`:34-42`) logs in debug mode, and on an expired session calls `authControllerProvider.notifier.logout()`, then `handler.next(e)`. The error is always rethrown to the caller.
- **`_isExpiredSession`** (`:48-52`): a 401 on any path, except `/api/auth/*` other than `/api/auth/me`, signs the user out. **A new endpoint that answers 401 for "not allowed in this region" would log the user out. The backend must use 403.**
- **`apiErrorMessage(Object error)`** (`:54-68`) is the **only** error-to-text function and is used everywhere:
  - `DioException` with `data['fieldErrors']` (a Map) is joined into lines through `_fieldMessage`. A lower-case fragment gets a humanized field label prefixed ("Name must not be blank"); a full sentence is kept as-is (`:79-90`).
  - Otherwise `data['message']`, otherwise `error.message`, otherwise `'Network error'`.
  - `_decoded` (`:70-77`) also decodes byte bodies (used by downloads).
- **There is no status-specific handling in the client.** Status codes are checked ad hoc in two places only:
  - `notFoundMessage(error, noun)` (`shared/widgets/detail_scaffold.dart:328-335`): 403 gives "You do not have permission to view this …", 404 gives "That … does not exist.", anything else gives "Could not load this …: <message>".
  - **409** is handled in exactly one place, `customer_detail_screen.dart:185-189`:
    ```dart
    String _deleteRefusal(Object e) =>
        (e is DioException && e.response?.statusCode == 409)
            ? 'This customer has invoices or payments on it and cannot be deleted. '
                'Nothing was changed.'
            : apiErrorMessage(e);
    ```
- There are **no ETag, If-Match or version fields** anywhere in the frontend, so there is no optimistic concurrency on the client.
- **Dio treats every 2xx as success.** A 202 "pending approval" reply would reach success branches that say "Customer saved" (G14).

**Token storage** (`core/storage/secure_storage.dart:5-37`): `TokenStorage.create()` uses `SharedPreferences` on web (`_WebStorage`) and `FlutterSecureStorage` elsewhere. The key is `gene_invoice_token`.

---

## 4. Auth and privilege model

- **`CurrentUser`** (`shared/models/auth_models.dart:3-38`) has `id`, `username`, `fullName`, `role` (a name string), **`Set<String> privileges`** (flat and global) and `customerId`.
  - `isCustomer => customerId != null` (`:31`); `isAdmin => role == 'ADMIN'` (`:32`).
  - `has(p)` and `hasAny(ps)` (`:34-35`); `canRaiseDispute => isCustomer && has(DISPUTE_CREATE)` (`:37`).
  - The **const constructor has all named parameters required** (`:11-18`), and tests build it directly (G9).
- **`Privileges`** (`shared/models/privileges.dart:1-48`) holds string constants that mirror the backend: USER_, ROLE_, CUSTOMER_, PRODUCT_, INVOICE_, PAYMENT_ (VIEW/MANAGE), DISPUTE_CREATE/VIEW/MANAGE, NOTIFICATION_VIEW, AUDIT_VIEW, POC_*, SCOPE_OVERRIDE, PROMISE_VIEW/MANAGE/OVERRIDE, EXPORT_DATA, EMAIL_VIEW/SEND, DOCUMENT_VIEW/MANAGE. **Add TASK_*, AUTOMATION_*, APPROVAL_* and REGION_* here, spelled exactly as the backend spells them.**
- **Checks are inline and global.** There are 88 `.has(` / `.hasAny(` / `isCustomer` / `isAdmin` sites, for example `final canEdit = user?.has(Privileges.customerManage) ?? false;` (`customer_detail_screen.dart:195`).
  - `AuthController` (`auth_controller.dart:24-69`): `bootstrap` calls `GET /api/auth/me` (`:29-43`); `login` posts to `/api/auth/login` (`:45-61`); `logout` clears the token **and the page-size store** (`:63-68`), following D-57 ("the next person at this browser gets their own defaults"). A region or as-of store must also be cleared here.
- **Per-record capability flags already exist once.** `DocumentItem.canDownload/canEdit/canDelete` come from the server (`features/documents/document_models.dart:91-93, 132-134`) and the UI simply obeys them (`documents_tab.dart:352, 358`). **This is the precedent to follow for rights that differ by region (B1).** The client cannot know the region-specific right on a record from a flat privilege set.
- **Dispute resolution is gated on the role name `ADMIN`, not a privilege** (`features/disputes/dispute_detail_screen.dart:123`: `final canResolve = (user?.isAdmin ?? false) && d.status == DisputeStatus.PENDING;`). Maker-checker approvals must **not** copy this.

---

## 5. Routing (`lib/core/router.dart`) and how to add a top-level screen

### Structure
- `routerProvider = Provider<GoRouter>((ref) { final auth = ref.watch(authControllerProvider); … })` (`:35-36`). **The router is rebuilt on every auth change.** Do not `ref.watch` any other provider here (G4). Use `_RouterRefresh` for extra refresh triggers; it currently listens to auth and page sizes (`:272-277`).
- Helpers:
  - `sizeFor(entity)` (`:38`) returns the remembered page size, or 20.
  - `mayLeave` (`:40-42`) is the `onExit` guard for detail routes and calls `unsavedChangesProvider.mayLeave()`.
  - `needs(List<String> privileges)` (`:44-48`) redirects to `/` if the user holds none of them. **An empty list means everyone may open the route.**
- `errorBuilder` (`:52-62`) shows `RecordUnavailable` inside `AppShell(path: …)` when signed in.
- The global `redirect` (`:64-70`) sends signed-out users to `/login`.
- `ShellRoute(builder: (context, state, child) => AppShell(child: child), routes: [...])` is at `:73-75`.

### List route pattern (copy this)
```dart
// router.dart:86-92
GoRoute(
  path: '/customers',
  redirect: needs(navPrivilegesFor('/customers')),
  builder: (c, s) => CustomersScreen(
    query: RouteQuery.read(s, defaultSize: sizeFor('customers'), defaultSort: 'name,asc'),
  ),
),
```

### Detail route pattern (copy this)
```dart
// router.dart:93-104
GoRoute(
  path: '/customers/:id',
  onExit: mayLeave,
  builder: (c, s) => pageForId(s,
      noun: 'customer',
      backTo: '/customers',
      build: (id) => CustomerDetailScreen(
            key: ValueKey('customer-$id'),
            id: id,
            initialTab: s.uri.queryParameters['tab'],
          )),
),
```
- `pageForId` (`:258-270`) turns a non-integer id into `RecordUnavailable`.
- The `ValueKey('noun-$id')` forces the screen state to be recreated when the id changes.

### Routes that do not follow the pattern
- `/invoices/new` (`:126`) has no guard; it is a full-page form.
- `/me/gmail` (`:84`) has no guard.
- `/disputes/:id` (`:195-205`) has no `onExit: mayLeave`.
- `/notifications` (`:207-213`) is guarded by an explicit list, `needs(const [Privileges.notificationView])`, because it has no sidebar entry. **Use this form for any route without a NavEntry**, for example a rule editor.

### Recipe: adding `/tasks`, `/my-tasks`, `/automation`, `/approvals`
1. Add the privilege constants to `shared/models/privileges.dart`.
2. Add a `NavEntry` to `navEntries` (`app_shell.dart:24-45`). **Order in the list is order in the sidebar**; the last commit reordered Customers above Invoices.
   ```dart
   NavEntry('Tasks', Icons.task_alt_outlined, '/tasks', [Privileges.taskView, Privileges.taskManage]),
   ```
   Use `hideForCustomer: true` for staff-only screens (`:17, 76-77`).
3. Add the list `GoRoute` using `needs(navPrivilegesFor('/tasks'))` and `RouteQuery.read(s, defaultSize: sizeFor('tasks'), defaultSort: 'dueDate,asc')`.
4. Add the detail `GoRoute('/tasks/:id', onExit: mayLeave, builder: pageForId(...))` if the entity has a page. A full-page editor like `/automation/new` or `/automation/:id` should follow `/invoices/new` and declare its own guard.
5. The screen widget takes a `TableQuery query` and renders `DataTableScaffold` (section 7).
6. ⚠ If you write `needs(navPrivilegesFor('/x'))` for a path that has **no** NavEntry, `navPrivilegesFor` returns `const []` (`app_shell.dart:50-55`) and the route is **open to everyone** (G16).

"My tasks" can be either:
- a quick filter on `/tasks`: `QuickFilterSpec(label: 'Assigned to me', filter: TableFilter('assigneeId','eq',['${user.id}']))`. It only shows if the schema marks the column filterable (G7).
- its own route `/my-tasks` with its own NavEntry.

---

## 6. AppShell and sidebar (`lib/shared/widgets/app_shell.dart`)

- **`NavEntry`** (`:12-22`) has `label`, `icon`, `path`, `requiredAnyOf`, `hideForCustomer` and `unreadBadge`.
- **Visibility** (`:76-80`): an entry shows when `requiredAnyOf.isEmpty || user.hasAny(requiredAnyOf)`, and customer logins skip `hideForCustomer` entries.
- **Selection** (`:188-201`): the **longest prefix match** of `GoRouterState.of(context).matchedLocation` against the entry paths. `/` only matches exactly. ⚠ Paths that share a prefix, such as `/tasks` and `/tasks-archive`, will cross-select (G15).
- **AppBar** (`:86-163`): the title is `'Gene Invoice'`. The `actions` are:
  - the notifications bell (`_NotificationsBell`, `:204-226`), shown only with NOTIFICATION_VIEW;
  - the account `PopupMenuButton` (Gmail connection, Change password, Logout), with the username and role label hidden below 600 px (`:72, 148-155`).
  - **This `actions:` list (`app_shell.dart:88-161`) is where a global region selector and an "as of" chip belong.** Every signed-in screen, including the error page (`router.dart:61`), goes through it.
- **Body** (`:171-182`): `Row([_HoverRail (width >= 900), VerticalDivider, Expanded(child)])`. Below 900 px the navigation is a `_DrawerNav` drawer (`:164-170, 296-324`). **A global "Viewing as of … (read-only)" banner can be inserted here by wrapping `child` in a `Column([banner, Expanded(child)])`, so every screen gets it.**
- **Nav badges** (`_NavIcon`, `:228-243`) are **hard-wired to `inboxUnreadCountProvider`** when `entry.unreadBadge` is true. A "My tasks" or "Approvals" count badge requires generalizing `NavEntry`, for example a `ProviderListenable<AsyncValue<int>>? badge` field (G13).
- Navigation always goes through `goGuarded(context, path)` (`core/unsaved_changes.dart:22-26`), which respects unsaved edits.

---

## 7. List screens: `DataTableScaffold`, `TableQuery`, `RouteQuery`, schemas

### Data model (`lib/core/table/table_models.dart`)
- **`TableFilter(field, operator, values, {label})`** (`:3-44`).
  - The wire form is `'$field:$operator:${values.join(',')}'` (`:16`), parsed by `TableFilter.parse` (`:18-31`).
  - Operators that take several values: `{'in','notIn','between','isEmpty','isNotEmpty'}` (`:33`).
  - `label` is display-only and excluded from equality (`:9-12, 35-43`).
- **`TableQuery(page, size, sort, filters)`** (`:46-120`).
  - `toApiParams()` (`:79-84`) produces `page`, `size`, `sort` and `filter` (a List of wire strings, sent as repeated `filter=` params).
  - `toRouteParams()` (`:86-91`) produces `page` (omitted when 0), `size`, `sort` and `f` for the URL.
  - `fromRoute` (`:93-108`) **ignores unknown params**.
  - `withSort`, `withFilters` and `withSize` all reset `page` to 0 (`:63-66`).
  - **Filters combine with AND only.** Nothing in the frontend expresses OR.
- **`PagedResult<T>`** (`:122-176`): `content`, `page`, `size`, `totalElements`, `totalPages`, `sort`, `appliedFilters`, **`lockedFilters`** (server-imposed scope, parsed into `TableFilter`s at `:156-159`) and `map<R>()`.
- **`TableSchema` / `ColumnDef` / `ColumnType`** (`:178-266`): these come from `GET /api/table-schemas/{entity}`.
  - `ColumnType` is TEXT/ENUM/BOOLEAN/NUMBER/MONEY/DATE/REFERENCE.
  - Each column carries `sortable`, `filterable`, `operators`, `enumValues` and `referenceKind`.
  - The schema also carries `pageSizes`, `defaultPageSize` and `datePresets`.
- Display labels: `operatorLabel` (`:268-283`) and `datePresetLabel` (`:285-296`).

### Providers (`lib/core/table/table_providers.dart`)
```dart
// :11-44
class TableRequest { final String entity; final String path; final TableQuery query; final Map<String, dynamic> extra;
  Map<String, dynamic> get apiParams => {...query.toApiParams(), ...extra};
  TableRequest get forSummary => TableRequest(entity: entity, path: path, query: TableQuery(filters: query.filters), extra: extra);
  bool operator ==(Object other) => … && mapEquals(other.extra, extra);   // identity compare of values!
  int get hashCode => Object.hash(entity, path, query, Object.hashAll(extra.entries.map((e) => '${e.key}=${e.value}')));
}
// :56-61
final tablePageProvider = FutureProvider.autoDispose.family<PagedResult<Map<String, dynamic>>, TableRequest>((ref, req) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get(req.path, queryParameters: req.apiParams);
  return PagedResult.fromJson(res.data as Map<String, dynamic>);
});
// :63-72  tableSummaryProvider → GET '${req.path}/summary' with apiParams minus page/size/sort
```
- `tableSchemaProvider` (`:46-54`) is **not autoDispose**; it is keyed by entity and refetches only when the user changes.
- `PageSizeStore` (`:74-116`) persists under the SharedPreferences prefix `table.pageSize.`; `hydrate` and `clear` are the precedent for a persisted scope store.

### URL ↔ query (`lib/core/table/route_query.dart`)
- `RouteQuery.read(state, defaultSize:, defaultSort:)` (`:25-27`).
- `RouteQuery(context, '/customers').push(q, {keep})` (`:29-32`) **rebuilds the whole query string from `keep` plus the query**. `keep` is unused today, so any other URL params, such as `region=` or `asOf=`, are **dropped on every sort, page or filter change** (G3).
- `RouteQuery.location(path, params)` (`:34-42`) is used for dashboard deep links. It encodes spaces as `%20` and leaves `+` literal (TBL-03, `:12-18`).

### `DataTableScaffold<T>` API (`lib/core/table/data_table_scaffold.dart:58-112`)
- **Required:** `entity` (the schema and page-size key), `path` (the API list path), `query`, `onQueryChanged`, `parse`, `idOf`, `columns: List<TableColumnSpec<T>>`.
- **Optional:**
  - `extraParams` (merged into the GET params, **but not into bulk or export**; see G2), `onRowTap`, `rowActions`, `mobileCard`.
  - `tiles: (context, summaryMap) => Widget` (feeds from `/summary`), `bulkActions: List<BulkActionSpec>`, `quickFilters: List<QuickFilterSpec>`, `onBulkDone`.
  - `canExport`, `selectable`, `emptyMessage`, `header` (a widget above everything, `:213`), `actions` (page buttons next to the filter bar, `:200-230`).
- `TableColumnSpec(label, cell, {sortKey, numeric, maxWidth})` (`:10-25`). `sortKey` must be a schema-sortable column, and **free-text columns are capped with `maxWidth` and ellipsised** (UI-08).
- `BulkActionSpec(action, label, icon, {destructive, buildParams, endpoint, successMessage})` (`:27-48`).
  - The default endpoint is `'${path}/bulk'`.
  - The body is `{action, ids | selectAllMatchingFilter, sort, filters, params}` (`:473-480`).
  - The result dialog reads `succeeded`, `failed`, `skipped`, `truncated`, `requested` and `limit` (`:552-607`). **A maker-checker "pending" bucket would need adding here.**
- Export posts to `'${path}/export'` with a similar body (`:496-515`) and shows the CSV in a dialog.
- Behaviour:
  - Width below 760 switches to a card list, with the tiles scrolling inside it (D-61, `:191, 216-222, 266-275`).
  - `_refresh()` invalidates the page and summary for this request (`:180-184`).
  - The error state is shown with Retry (`:247, 1047-1081`).
  - The empty state has "Clear all filters" (`:257-264`); the past-the-end state has "Go to last page" (`:249-256`).
  - The page-size dropdown writes to `PageSizeStore` (`:288-291`).
- `_FilterBar` (`:728-810`) contains:
  - the "Add filter" chip, which opens `showFilterEditor`;
  - the quick filters, **shown only if the schema column is `filterable`** (`:745-747`);
  - the **locked-filter chips**. Their tooltip is `'Your role limits this list to your own records'` and their label comes from `_lockedLabel`, which knows only `myBook` and otherwise says "My records only" (`:774-781, 806-809`);
  - the user filters as `InputChip`s, and "Clear all".
- `SummaryTile(label, value, {icon, accent})` (`:663-726`) is public and also used by the dashboard.

### Canonical list screen (copy this shape)
```dart
// lib/features/customers/customers_screen.dart:57-76 (abridged)
return Scaffold(
  body: DataTableScaffold<Customer>(
    entity: 'customers',
    actions: [ if (canManage) FilledButton.icon(icon: const Icon(Icons.add), label: const Text('New customer'), onPressed: () => _openCreateForm(context, ref)) ],
    path: '/api/customers',
    query: query,
    onQueryChanged: (q) => RouteQuery(context, '/customers').push(q),
    parse: Customer.fromJson,
    idOf: (c) => c.id,
    canExport: canExport,
    emptyMessage: 'No customers match this filter',
    onRowTap: (context, c) => context.go('/customers/${c.id}'),
    tiles: (context, s) => Wrap(spacing: 12, runSpacing: 12, children: [ SummaryTile(label: 'Customers', value: '${s['count'] ?? 0}'), … ]),
    bulkActions: [...], columns: [...], rowActions: (context, c) => [...],
  ),
);
```
The other list screens have the same shape:
- `invoices/invoices_screen.dart:44-230`, which also shows `quickFilters` (`:93-99`);
- `payments/payments_screen.dart:43+`;
- `promises/promises_screen.dart:35-167`;
- `disputes/disputes_screen.dart:26-96`;
- `notifications/notifications_screen.dart:35-86`, which shows `onBulkDone` (`:45`);
- the users, roles and products screens.

**Backend endpoints a new list entity (tasks, rules, approvals) must provide for the scaffold to work:**
- `GET /api/table-schemas/{entity}`
- `GET {path}` returning a `PagedResult` JSON
- `GET {path}/summary` if `tiles` is used
- `POST {path}/bulk` if bulk actions are used
- `POST {path}/export` if `canExport` is set

### Filter editor (reusable for A2 conditions)
- `showFilterEditor({context, schema, existing}) → Future<TableFilter?>` (`lib/core/table/filter_editor.dart:7-16`) builds **one** leaf condition from a `TableSchema`.
  - It has per-type editors (`:194-301`): enum chips or dropdown, a boolean dropdown, `ReferencePicker` for REFERENCE, date pickers or relative presets for DATE, and number or text fields.
  - Validation messages are produced in `_build()` (`:68-124`).
  - `describeFilter(f, schema)` (`:330-339`) gives a human-readable summary.
- `ReferencePicker` / `referenceOptionsProvider` (`lib/core/table/reference_picker.dart:28-166`) supports the kinds `pocUser`, `invoice`, `payment` and `customer`, and **`default:` also falls to customer** (`:75-76`) (G6).

---

## 8. Detail screens and adding a tab (Tasks on customer, invoice and payment)

### `DetailScaffold` (`lib/shared/widgets/detail_scaffold.dart`)
- `DetailTab({slug, label, icon, builder, badgeCount})` (`:6-22`).
- `DetailScaffold({title, top, tabs, subtitle, titleTrailing, initialTabSlug, onTabChanged, onBack})` (`:24-48`).
- **Tabs are built lazily**: a tab's builder runs only once visited (`_visited`, `:53, 126-134`).
- The initial tab comes from the slug (`:71-76`). `onTabChanged` fires after a change settles (`:64-68`).
- ⚠ **If `tabs.length` changes, the `TabController` is recreated** and the selection resets to `initialTabSlug` (`:81-87`) (G5).
- Layout: below 760 px it is a `NestedScrollView` with a pinned tab bar (`:136-148`). Wider, the header sits above a `top` capped at 55 % of the height and scrollable, then the tabs (`:150-175`).
- Also in this file: `DetailGrid` / `DetailGridItem(label, child, span)` (`:269-315`), with 1, 2 or 3 columns at 640 and 1000 px; `ReadOnlyValue` (`:317-326`); `RecordUnavailable` (`:337-363`); `notFoundMessage` (`:328-335`).

### How detail screens are put together (customer, `lib/features/customers/customer_detail_screen.dart`)
- The state is a `ConsumerStatefulWidget` with controllers and the flags `_seeded`, `_dirty`, `_saving`, `_deleting`, `_error` and `_fieldErrors` (`:35-49`).
- It registers the unsaved guard in `initState` (`:51-55`): `_unsaved = ref.read(unsavedChangesProvider)..register(_confirmDiscard);`, and unregisters it in `dispose` (`:57-65`).
- **`_seed(c)` runs once, guarded by `_seeded`** (`:67-75`). After a save, the fields keep what the user typed; they are never re-seeded from the server (G18).
- `build` (`:191-279`):
  - computes the privilege booleans (`:194-202`);
  - uses `async.when(loading: spinner, error: RecordUnavailable(notFoundMessage(e,'customer')), data: …)` (`:204-210`);
  - wraps everything in `PopScope(canPop: !_dirty, …goGuarded)` (`:219-223`);
  - builds `DetailScaffold(… onTabChanged: (slug) => context.go('/customers/${widget.id}?tab=$slug'), top: _top(...), tabs: [...])` (`:224-275`).
- **Tab list order** is Disputes, Payment Promise, History, Documents, Email (`:245-274`). Invoice (`invoice_detail_screen.dart:223-259`) and payment (`payment_detail_screen.dart:175-211`) use the same order.
- `_top` (`:281-406`) holds the figure cards plus the "Save changes" button (`_figuresAndSave`, `:408-433`), a `DetailGrid` of editable `TextField`s or `ReadOnlyValue`, and an `_error` text. `_save` does a `PUT` or `PATCH`, then invalidates, then shows a SnackBar (`:98-134`).

### Recipe: Tasks tab (A6)
1. Create `features/tasks/task_actions.dart` with a factory modelled exactly on `documentsDetailTab`:
   ```dart
   // lib/features/documents/document_actions.dart:24-36 (pattern)
   DetailTab? documentsDetailTab(WidgetRef ref, {required DocumentEntityType type, required int entityId, String? entityLabel}) {
     if (!ref.watch(canViewDocumentsProvider)) return null;
     final count = ref.watch(documentCountProvider((type: type, entityId: entityId))).valueOrNull;
     return DetailTab(slug: 'documents', label: 'Documents', icon: Icons.folder_outlined, badgeCount: count,
       builder: (context) => DocumentsTab(type: type, entityId: entityId, entityLabel: entityLabel));
   }
   ```
   **The tab's presence depends only on a synchronous privilege.** The async count only sets `badgeCount`, which avoids G5.
2. Call it in each of the three detail screens next to `documentsTab` / `emailTab`:
   - `customer_detail_screen.dart:215-218, 272-273`
   - `invoice_detail_screen.dart:185-188, 257-258`
   - `payment_detail_screen.dart:140-143, 209-210`
3. Write a `TasksTab` widget modelled on `PromisesTab` (`promises/promises_tab.dart:36-113`) or `DisputesTab` (`disputes/disputes_tab.dart:13-104`):
   - a `Column([ if (canManage) FilledButton.icon('New task'), Expanded(async.when(loading, error: _TabError + Retry, data: empty text or ListView.separated)) ])`;
   - after the dialog returns true, `ref.invalidate(scopedTasksProvider(scope))`.
4. Add the scope key and providers in `features/tasks/task_providers.dart`:
   - a record typedef `({String entityType, int entityId})`;
   - `scopedTasksProvider` (`GET /api/tasks?entityType=&entityId=`), `taskCountProvider` (`/api/tasks/count`) and `taskDetailProvider`.
5. Invalidate `taskCountProvider` and the tasks family after every task mutation.
6. The customer tab covers the customer only, or includes related records. `AuditHistoryPanel(includeRelated: true)` (`:268-269`) is the precedent for a list that includes related records.

---

## 9. Forms and dialogs

**Canonical pattern:** a public `show…Dialog` function wraps a private `ConsumerStatefulWidget` and returns a typed record.
```dart
// lib/features/promises/promise_form_dialog.dart:27-54
Future<bool?> showPromiseDialog({required BuildContext context, required int customerId, String? customerName,
    List<InvoiceSummary> preselectedInvoices = const [], PaymentPromise? existing}) async {
  final saved = await showDialog<_SavedPromise>(context: context, builder: (_) => _PromiseFormDialog(...));
  if (saved == null) return false;
  if (context.mounted) {
    await notifyByEmailAfterSave(context, notify: saved.notify, type: EmailEntityType.promise,
        entityId: saved.id, event: existing == null ? EmailEvent.created : EmailEvent.updated);
  }
  return true;
}
typedef _SavedPromise = ({int id, bool notify});
```

**Submit shape** (`promise_form_dialog.dart:142-178`): validate by hand (setting `_error`), then set `_saving = true, _error = null`, then:
```dart
try {
  final dio = ref.read(dioProvider);
  final body = { 'customerId': …, 'amount': amount, 'promisedDate': DateFormat('yyyy-MM-dd').format(_date!), if (_poc != null) 'collectionPocUserId': _poc!.id, … };
  final res = _isEdit ? await dio.put('/api/promises/${id}', data: body) : await dio.post('/api/promises', data: body);
  final id = _isEdit ? … : ((res.data as Map)['id'] as num).toInt();
  ref.invalidate(scopedPromisesProvider); ref.invalidate(promiseDetailProvider(id));
  if (mounted) Navigator.of(context).pop((id: id, notify: _notify));
} catch (e) { setState(() => _error = apiErrorMessage(e)); }
finally { if (mounted) setState(() => _saving = false); }
```

**UI conventions**
- `AlertDialog`, with the content in a `SizedBox(width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 520)` (D-60, `:193`). Simpler dialogs use a fixed 420/460 (`customers_screen.dart:369`, `dispute_create_dialog.dart:156`).
- Contents go in a `SingleChildScrollView(Column(mainAxisSize: min))`.
- Two fields sit in a `Flex` that turns vertical below 600 px (`:201-236`).
- Date fields are an `InkWell` over an `InputDecorator` with a calendar suffix, opening `showDatePicker` (`:215-233`).
- Required labels end in `' *'`.
- Text limits use `LengthLimitingTextInputFormatter(FieldLimits.x)` (`:327`).
- The error text uses `colorScheme.error` (`:335-340`).
- Actions are Cancel (disabled while saving) and a `FilledButton` that shows an 18×18 `CircularProgressIndicator(strokeWidth: 2)` while saving (`:345-356`).
- `Form` / `TextFormField` validation uses `AutovalidateMode.onUserInteraction` (CP-09), for example `customers_screen.dart:376-383` and `dispute_create_dialog.dart:157-168`.
- Optional text is sent through `optionalText()` (null rather than an empty string, CP-15; `format.dart:16-19`).
- Money uses `parseMoneyInput` (up to 2 decimals; `format.dart:21-25`).
- Dropdowns set `isExpanded: true` (D-65).
- `NotifyByEmailCheckbox` (`email_actions.dart:88-106`) plus `notifyByEmailAfterSave` (`:108-121`) are the standard "email about it" hook.
- A `StatefulBuilder` dialog that returns bulk params: `_pickCustomerPocParams` (`customers_screen.dart:216-273`) and `pickPocParams` (`promises_screen.dart:171-200`).
- Confirm dialogs for destructive actions use a red `FilledButton` (`backgroundColor: colorScheme.error`) and a safe label on the left ("Keep it"), for example `customer_detail_screen.dart:143-165` and `promises_tab.dart:244-292`.
- Deletes or cancels on list rows show a SnackBar with `apiErrorMessage` (`invoices_screen.dart:233-263`).

**Pickers**
- `SearchPickerField<T>` (`shared/widgets/search_picker_field.dart:7-62`) is a field that opens a debounced (250 ms) search dialog backed by `Future<List<T>> Function(String)`. It is used with `searchCustomers(dio, q)` (`customers_screen.dart:23-33`).
- `PocPicker(type, value, onChanged, {enabled, required, showLabel})` (`poc/poc_picker.dart:8-80`).
- `ReferencePicker` (section 7).

**Full-page form:** `InvoiceFormScreen` (`invoices/invoice_form_screen.dart`) is a `Scaffold` with a server preview call (`:94-120`), `POST /api/invoices` (`:167`), invalidation (`:182-183`), notify, then `context.go('/invoices')` (`:195`). It does **not** register `UnsavedChanges`. This is the model for a full-page `/automation/new` rule editor, but add the unsaved guard.

**`UnsavedChanges`** (`core/unsaved_changes.dart:5-26`) is a **single-slot** registry: `register` replaces, and `unregister` only clears when it is the same function (G17).

---

## 10. Models and JSON parsing

- Models live in `lib/shared/models/*.dart` for the core entities, or in the feature folder (`email_models.dart`, `document_models.dart`, `poc_providers.dart` holds `PocUser` / `CustomerPoc`, and `dashboard_providers.dart` holds the chart DTOs).
- **Style:**
  - plain classes with `final` fields and a `const` constructor;
  - `factory X.fromJson(Map<String, dynamic> json)`;
  - **no `toJson`**: request bodies are built inline as map literals at the call site.
  - `EmailToken.toJson` (`email_models.dart:69-74`) is the exception.
- **Casting idioms** (copy exactly):
  - id: `(json['id'] as num).toInt()`
  - nullable int: `(json['x'] as num?)?.toInt()`
  - money: `(json['total'] as num).toDouble()` or, tolerant, `(json['outstanding'] as num? ?? 0).toDouble()` (`customer.dart:73-76`)
  - bool with default: `json['pocMissing'] as bool? ?? false`
  - date: `DateTime.parse(json['createdAt'] as String)` or `json['x'] == null ? null : DateTime.parse(...)` (`dispute.dart:85-87`)
  - LocalDate: `parseDueDate(Object? wire) => wire == null ? null : DateTime.tryParse(wire.toString())` (`invoice.dart:89-90`)
  - nested list: `((json['items'] as List?) ?? const []).cast<Map<String, dynamic>>().map(X.fromJson).toList()`
  - nested object: `json['salesPoc'] == null ? null : PocUser.fromJson(json['salesPoc'] as Map<String, dynamic>)`
- **Enum pattern:**
  ```dart
  // lib/shared/models/dispute.dart:20-29
  enum DisputeStatus { PENDING, APPROVED, DENIED }
  DisputeStatus parseDisputeStatus(String? s) =>
      DisputeStatus.values.firstWhere((e) => e.name == s, orElse: () => DisputeStatus.PENDING);
  String disputeStatusLabel(DisputeStatus s) => switch (s) { DisputeStatus.PENDING => 'Pending', … };
  ```
  An enum with data is written like `PaymentTerm` (`payment_term.dart:1-27`), parsed with `PaymentTerm.values.asNameMap()[wire]`. ⚠ `parseX` **silently maps unknown wire values to a default** (G31).
- **Status colors and chips** live in `shared/widgets/status_chip.dart`:
  - the color functions are at `:8-51`, the base `StatusChip(label, color, {trailing})` at `:53-76`, and the typed chips at `:78-130`;
  - the exception is `PromiseStatusChip`, which lives in `promises/promises_tab.dart:15-34`.
  - Put `TaskStatusChip`, `ApprovalStatusChip` and `RuleRunStatusChip` in `status_chip.dart`.
- **Inheritance for summary vs detail:** `InvoiceDetail extends InvoiceSummary` (`invoice.dart:119-173`).
- **Layering inversion:** `shared/models/customer.dart:1`, `invoice.dart:1`, `payment.dart:1` and `promise.dart:1` import `features/poc/poc_providers.dart` for `PocUser` / `CustomerPoc`. New shared models may do the same, but watch for import cycles.
- **Primary resolution already exists on the client:** `Customer._primary` picks the POC flagged `primary`, **else the first one** (`customer.dart:56-65`). `EmailRoleOption.sender` is "the one person who sends", meaning the primary (L5).

---

## 11. Formatting, limits and storage helpers

- **`lib/core/format.dart`**
  - `formatMoney` (₹, en_IN, 2 dp; `:3-10`) and `formatMoneyCompact` (Cr/L; `:27-32`).
  - `formatDate` is **`toLocal()`** plus `yyyy-MM-dd` (`:37-40`).
  - `utcDay` / `formatUtcDate` handle LocalDate fields such as invoiceDate (`:42-50`).
  - `formatDateTime` gives `d MMM yyyy, h:mm a` (local; `:52-55`).
  - `humanizeEnum` turns `FOO_BAR` into "Foo bar" (`:63-69`).
  - Request dates are built with `DateFormat('yyyy-MM-dd').format(localDate)` (`promise_form_dialog.dart:161`, `filter_editor.dart:324`).
- **`lib/core/field_limits.dart:1-25`** holds per-field max lengths that mirror the backend, for example `emailSubject = 500`, `emailBody = 20000`, `promiseNotes = 1000`. Add task title, rule name and similar limits here.
- **Dashboard "today"**: `todayUtc()` (`dashboard_providers.dart:154-157`).

---

## 12. Email role-at-a-level model (reuse for A3 recipients and assignees, A4 placeholders)

- **`EmailRoleLevel.customer = 'CUSTOMER'`, `.record = 'RECORD'`** (`email_models.dart:23-26`).
- **`EmailToken`** (`:28-90`) has three constructors:
  - `EmailToken.user(id)` → `{'type':'USER','userId':7}`
  - `EmailToken.role('SALES_POC', level: 'RECORD')` → `{'type':'ROLE','role':'SALES_POC','level':'RECORD'}`
  - `EmailToken.customer()` → `{'type':'CUSTOMER'}`
  - It has value equality (`:76-85`). The same role at two levels is two distinct tokens (L1; tested at `test/email_compose_test.dart:359-373`). A token without a level is read as the customer level (L7).
- **`EmailRoleOption`** (`:236-287`) has `role`, `label`, `level`, `levelLabel`, `groupLabel`, `resolved`, `people` (every holder, for To), `sender` (the single primary, for From), `token` and `labelWithLevel`.
- **`EmailContext`** (`:421-492`) comes from `GET /api/emails/context?entityType=X[&entityId=][&event=]&utcOffsetMinutes=` (`email_providers.dart:14-24`). **Without `entityId` it still returns the roles offered for that entity type, grouped by level** (`roleGroups`, `:482-491`). That is exactly what a rule editor needs to offer "Collection POC (customer level)" or "Sales POC (invoice level)" for a trigger entity type.
- **`SendEmailDialog` in `SendEmailMode.bulk`** (`send_email_dialog.dart:15-21, 62-69`):
  - It returns `_fields()` without sending or previewing: `{'entityType', 'from'?, 'to': [...tokens], 'subject', 'body'?}` (`:146-152, 298-301`).
  - Labels read "Sales POC (each record's)" (`:325-329`).
  - It is invoked through `sendEmailBulkAction(type).buildParams` (`email_actions.dart:56-64`), and the test asserts the payload shape (`email_compose_test.dart:1324-1396`).
  - **This is the closest existing UI to "the Email action of an automation rule"**: a template not tied to a record, with role tokens resolved per record. Reuse it, or extract `_toField` / `_fromField` (`:592-782`) into public widgets.
- **Placeholders (A4):** there is **no placeholder syntax anywhere in the frontend today.** Server suggestions arrive already rendered (for example `'Invoice INV-0042 for ₹1,200.00'`, `email_compose_test.dart:867`).
  - Subject and body are plain `TextFormField`s (`send_email_dialog.dart:562-577`) with `LengthLimitingTextInputFormatter(FieldLimits.emailSubject/emailBody)`.
  - A placeholder picker (chips grouped by "Customer" and "Invoice" level) would insert `{{Customer.Name}}` at `controller.selection`.
  - Note that the subject is cleaned of newlines (`_cleanSubject`, `:144`).
- For **task assignees**, reuse `EmailToken.user` and `EmailToken.role(level)`. A `CUSTOMER` token has no meaning for assignees. Show `sender` as "who it will be assigned to", because a role held by several people resolves to the primary.

---

## 13. Dashboard (B1/B3 impact)

- **Providers** (`dashboard_providers.dart:159-234`), each `FutureProvider.autoDispose` and each `ref.watch(dioProvider)`:
  - `billedByMonthProvider`, `collectedByMonthProvider` (`/api/dashboard/*-by-month?months=`);
  - `outstandingByAgeProvider`, `topOutstandingProvider`, `topPayingProvider`;
  - `invoiceSummaryProvider` (`/api/invoices/summary`), `promiseSummaryProvider` (`/api/promises/summary`);
  - `upcomingPromisesProvider`.
  - The **months toggle** is `dashboardMonthsProvider` (a `StateProvider<int>`), watched inside the providers. **This is the exact pattern for adding `ref.watch(viewScopeProvider)`.**
- **Scope signalling from the server:**
  - `Coverage { all, book, own }` is parsed from `json['coverage']` (`:8-14`) and rendered as `BookBadge` "Your book" (`dashboard_charts.dart:79-109`) through `DashboardCard(book:)` (`:25-77`).
  - Upcoming promises infers `book` from `lockedFilters.isNotEmpty` (`dashboard_providers.dart:232`) (G8).
- **Copy that assumes "today"**:
  - `'balances and promises are as of today (UTC).'` (`dashboard_screen.dart:155-156`);
  - `todayUtc()` is used for the upcoming-promises filter (`dashboard_providers.dart:215-224`), the aging deep links (`AgingBars.linkFor`, `dashboard_charts.dart:562-588`) and the range label (`dashboard_screen.dart:141-144`).
- **Deep links into lists:**
  - `_InvoiceStatusCard._link` (`dashboard_screen.dart:352-353`) and `_PromiseStatusCard._link` (`:397-398`);
  - `/customers?sort=outstanding,desc` (`:437`);
  - `UpcomingPromises.listLink` (`dashboard_providers.dart:209-212`);
  - the aging links.
  - If region or as-of live in global state, these links keep them automatically. If they live in the URL, every link must carry them.
- Access rules: `DashboardAccess.of(user)` (`dashboard_screen.dart:19-53`).

---

## 14. Other patterns worth reusing

- **Audit** (`features/audit/audit_history_panel.dart`):
  - `auditHistoryProvider` calls `GET /api/audit?entityType=&entityId=[&includeRelated=true]` (`:175-187`).
  - Action labels live in the `_actionLabels` map (`:189-228`); unknown actions fall back to `humanizeEnum`.
  - `_typeOrder`, `_typeLabels`, `_routeFor` and `_iconFor` cover only CUSTOMER, INVOICE, PAYMENT, PROMISE and DISPUTE (`:232-257`). **Add TASK, APPROVAL and RULE here for links and icons.**
  - An entry with no `changedByUserId` that is not derived shows **"automatic"** (`:376-381`). Automation-performed actions will read naturally if the backend writes them with a null user.
  - Before/after JSON rendering is in `_AuditDetails` (`:433-478`, private). Extract it to show B2 pending-change diffs.
- **Polling**:
  - `pollUnreadCount(ref, dio, path)` (`notifications_providers.dart:18-35`) runs a 30 s `Timer.periodic`, swallows errors, and cancels in `ref.onDispose`.
  - `EmailRefresher` (`email_tab.dart:37-62`) together with `emailRefreshInterval` (`email_models.dart:725-739`) polls adaptively (5 s while queued, 30 s while tracking). **Use these for "rule run queued, then done" status (A5).**
- **Notifications** (`notifications_screen.dart:89-100`): opening a row marks it read and then does `context.go(n.link)`, rewriting `/admin/disputes/`. New notification types for tasks or approvals must link to routes that exist.
- **Injectable platform seams for tests:** `documentPickerProvider` and `documentSaverProvider` (`document_providers.dart:39-46`) are overridden in tests. Use the same approach for anything platform-bound.

---

## 15. Where a global region selector and an "as of" picker should live

### Current situation
- Nothing app-wide feeds query params. List params come only from `TableQuery` (in the URL) plus `DataTableScaffold.extraParams`, **which no screen uses**.
- There are 52 direct `dio.get` call sites. The ones that answer "which records" are:
  - **Lists and tiles:** `tablePageProvider` and `tableSummaryProvider` (`table_providers.dart:56-72`). **All 10 list screens and all list tiles go through these two.**
  - **Bulk and export:** `data_table_scaffold.dart:473-480, 499-505`. Their bodies carry only `sort` and `filters`.
  - **Dashboard:** `dashboard_providers.dart:159-234` (8 providers).
  - **Record tabs:** `scopedPromisesProvider` / `openPromisesForCustomerProvider` (`promise_providers.dart:24-58`), `scopedDisputesProvider` (`disputes_providers.dart:26-39`), `entityDocumentsProvider` / `documentCountProvider` (`document_providers.dart:16-37`), `entityEmailsProvider` (`email_providers.dart:28-40`), `auditHistoryProvider` (`audit_history_panel.dart:177-187`).
  - **Detail reads:** `customerDetailProvider`, `invoiceDetailProvider`, `paymentDetailProvider`, `promiseDetailProvider`, `disputeDetailProvider`.
  - **Pickers:** `referenceOptionsProvider` (`reference_picker.dart:28-87`), `searchCustomers` (`customers_screen.dart:23-33`), `searchEmailRecords` (`email_providers.dart:96-108`), `_outstandingInvoicesProvider` (`promise_form_dialog.dart:384-396`), `record_payment_dialog.dart:333`, `assignablePocsProvider` / `myPocScopeProvider` (`poc_providers.dart:115-145`, which covers "the POC book is per region").

### Recommended shape
Add a `lib/core/scope/view_scope.dart` containing:
- `@immutable class ViewScope { final Set<int>? regionIds; final DateTime? asOf; … }` with a canonical **string** encoding:
  - `apiParams => { if (regionIds != null) 'regionIds': sortedIds.join(','), if (asOf != null) 'asOf': DateFormat('yyyy-MM-dd').format(asOf!) }`
  - Return an empty map when unset, which is essential for G11.
- A `ViewScopeStore extends StateNotifier<ViewScope>` with `hydrate`, `clear` and `write`, modelled on `PageSizeStore` (`table_providers.dart:74-116`):
  - hydrate in `app.dart:19-22`;
  - clear in `AuthController.logout` (`auth_controller.dart:63-68`), following D-57;
  - optionally reset when the user id changes.
- `isHistoricalProvider` (`asOf != null`), for read-only mode.

**Wiring points, in order of leverage**
1. **`tablePageProvider` and `tableSummaryProvider`**: add `final scope = ref.watch(viewScopeProvider);` and merge `scope.apiParams` into the query parameters. Because the provider watches the scope, every mounted list and its tiles refetch automatically when the scope changes. Do **not** put a `Set` or `List` in `TableRequest.extra` (G1).
2. **`_runBulkAction` and `_runExport` bodies**: add the scope. Otherwise "Select all N matching" acts on every region (G2).
3. **Dashboard providers**: `ref.watch(viewScopeProvider)`. Also replace `todayUtc()` with `asOf ?? todayUtc()` in the header copy, upcoming filters and aging links.
4. **Record-tab and detail providers**: add `asOf` so a record reads "as of". The region is implied by the record itself.
5. **Pickers used in create flows**: the backend should restrict them to regions where the user may **manage**. The picker call sites are listed above.
6. **`myPocScopeProvider`**: make it watch the scope (POC book per region).

**UI placement**
- The selector and date chip go in `AppShell` AppBar `actions` before the bell (`app_shell.dart:88-91`).
  - Region: a multi-select menu of the user's regions, taken from `CurrentUser` (extend `/api/auth/me`).
  - As-of: a chip that opens `showDatePicker` and has a clear ×.
  - Below 600 px (`showAccountLabel` threshold, `:72`), collapse both into icon buttons.
- A read-only banner goes in the `AppShell` body (`:171-182`).

**Read-only when as-of is set:** every `canEdit` / `canManage` must AND in `!isHistorical`. The sites are:
- `customer_detail_screen.dart:195`, `invoice_detail_screen.dart:166`, `payment_detail_screen.dart:120`;
- the list screens' `canManage` values (`customers_screen.dart:49`, `invoices_screen.dart:36`, `payments_screen.dart:36`, `promises_screen.dart:28`);
- `PromisesTab` (`promises_tab.dart:58-59`), `DisputesTab` (`disputes_tab.dart:30-31`), `canUploadDocumentsProvider` and `canSendEmailProvider`.

Folding it into the permission `Provider<bool>`s (`canSendEmailProvider`, `canAssignPocProvider`, `canManageDocumentsProvider`) covers many sites at once.

**URL vs. global state**
- The app's philosophy is that "a shared link reproduces the exact view" (`route_query.dart:6-7`, AC-D4). Region and as-of held only in global state are not shareable.
- To make them shareable, add `regionIds` and `asOf` to `TableQuery` (fields, `==`, `toRouteParams`, `fromRoute`, `copyWith`) and initialise the global store from the URL on entry.
- **Never** rely on untyped extra URL params, because `RouteQuery.push` drops them (G3).
- The router must not `ref.watch` the scope provider (G4).

**Privileges per region (B1)**
- Keep `CurrentUser.privileges` as the **union across regions**, so the 88 existing `has` / `hasAny` sites, the nav and the route guards keep working.
- Add optional fields, for example `List<RegionGrant> regions = const []` with `{id, name, privileges}`, and `bool hasIn(String p, int regionId)`.
- Rights on a specific record should come from **server flags on the DTO** (`canEdit`, `canApprove`, `pendingChange`), as `DocumentItem` does.

---

## 16. PRD extension points in the frontend, item by item

**A1 Triggers / A2 Conditions / A3 Actions (rule editor)**
- New feature folder: `features/automation/`, containing `automation_models.dart`, `automation_providers.dart`, `rules_screen.dart` (a `DataTableScaffold` list, entity e.g. `automation-rules`), `rule_editor_screen.dart` (full page like `invoice_form_screen.dart` plus the `UnsavedChanges` guard) and `rule_runs_tab.dart`.
- **Trigger:** a segmented control for entity (CUSTOMER, INVOICE, PAYMENT) × event (CREATED, UPDATED), or a schedule (DAILY or WEEKLY with a weekday and time). Enums follow the UPPER_SNAKE wire convention.
- **Conditions:** load `tableSchemaProvider(entityKey)` for the trigger's entity and use `showFilterEditor(context: …, schema: …)` for each leaf. It returns a `TableFilter` whose `wire` string the backend already parses. **AND/OR grouping is new**: build a small tree widget with nested groups, each holding an "all of" or "any of" choice. Relative date presets ("past", "future", "last7Days") are relative to today; define as-of semantics with the backend.
- **Actions:**
  - Create Task: title, due offset, assignees as `EmailToken` role/user tokens.
  - Create Promise: amount, date offset, Collection POC token.
  - Create Dispute: ⚠ the UI only lets **customer logins** raise disputes (`auth_models.dart:37`, used at `disputes_tab.dart:30-31` and `invoice_detail_screen.dart:206`). Staff-side rule actions creating disputes are new UI territory.
  - Send Email: reuse the bulk-mode compose payload (section 12).
- **Recipients and assignees:** fetch `emailContextProvider((type: EmailEntityType.invoice, entityId: null, event: null))` to get the role groups for the trigger type.

**A4 Placeholders:** add a placeholder picker next to the subject and body fields. Offer both the Customer and the record level; the level labels come from `EmailRoleOption.levelLabel` or `groupLabel`. There is no client-side rendering today, so show previews via a server endpoint, following the `POST /api/emails/preview` precedent (`send_email_dialog.dart:162-192`).

**A5 Durable execution / run now**
- A rules-list row action, and an editor button, "Run now" → `POST /api/automation/rules/{id}/run`. Show a snackbar ("Queued"), then poll the run status with the `EmailRefresher` pattern.
- The save flows need **no** changes to wait for rules. Do not add synchronous waiting.
- Records created by automation arrive later, so the task and approval badges should poll with `pollUnreadCount`, or be invalidated when the user returns to the screen.

**A6 Tasks:** see section 8's recipe, plus:
- a top-level `/tasks` list (and optionally `/my-tasks`);
- a `NavEntry`, privileges, a `TaskStatus` enum with a chip in `status_chip.dart`, and a task form dialog in the `showPromiseDialog` style;
- audit type entries (section 14).

**B1 Regions**
- Section 15 covers the selector and wiring.
- Add optional `regionId` / `regionName` to `Customer`, `InvoiceSummary`, `PaymentRecord`, `PaymentPromise` and `Dispute`, parsed as nullable (G10).
- Add a region column to lists as a `TableColumnSpec(label: 'Region', sortKey: 'regionName')`, which requires the schema to publish it.
- Region management screens, if in scope, would follow the users/roles pattern (`users/roles_screen.dart:18-40, 139-164`).
- Per-region rights on users would change `roles_screen` / `users_screen` forms, which currently post a flat `privileges` list (`roles_screen.dart:156`).

**B2 Maker-checker**
- **Every save path must handle a "pending" answer**, with a distinct snackbar and without pretending the change applied. The paths are:
  - `customer_detail_screen.dart:108-133`, `CustomerFormDialog._submit` (`customers_screen.dart:329-361`);
  - `invoice_detail_screen.dart:127-160`, `payment_detail_screen.dart:88-114`;
  - `promise_form_dialog.dart:156-177`, `invoice_form_screen.dart:167-195`, `record_payment_dialog.dart`;
  - `_showBulkResult` (`data_table_scaffold.dart:552-607`).
- After a pending result, **reset `_seeded = false` / `_loadedInto = false`** and re-seed from the server; otherwise the fields show values that have not taken effect (G18).
- Show the pending state to everyone who can see the record: a `StatusChip('Pending approval')` in `titleTrailing`, a banner in `top`, and a diff built from an extracted `_AuditDetails`.
- An `/approvals` list (DataTableScaffold) and detail page with Approve/Reject, modelled on `DisputeDetailScreen._resolve` (`dispute_detail_screen.dart:81-117`), but gated on a region-specific **server flag** (`canApprove`, false for the maker), **not** `isAdmin`.
- Per-region threshold settings screen: money input via `parseMoneyInput` and `formatMoney`.

**B3 As-of**
- Section 15 (the `asOf` param everywhere, read-only mode, dashboard "today" replaced).
- Lists show the region the record belonged to at that date (server-provided `regionName`), plus approvals outstanding at that date. The `/approvals` list takes the same `asOf`.

---

## 17. Risks and gotchas (things that silently break)

- **G1. A `List` or `Set` in `TableRequest.extra` causes an endless refetch loop.** `==` uses `mapEquals`, which compares values with `!=`, so a new list every build (`data_table_scaffold.dart:132-137`) makes a new provider key every build. The result is load, rebuild, new key, load again. Encode multi-values as a canonical string (`'1,2'`), or read the scope inside the provider instead. The same applies to any new family key class or record containing collections.
- **G2. Bulk and export ignore `extraParams`.** Their bodies (`data_table_scaffold.dart:473-480, 499-505`) carry only `sort` and `filters`. A region or as-of passed via `extraParams` would not constrain "Select all N matching", which would then act across all regions.
- **G3. `RouteQuery.push` rebuilds the URL from the query alone** (`route_query.dart:29-32`; `keep` is never used). Loose `?region=` or `?asOf=` URL params vanish on the first sort, page or filter.
- **G4. `routerProvider` watches `authControllerProvider`** (`router.dart:36`). Watching anything else there recreates the `GoRouter` and resets navigation. Use `_RouterRefresh` (`:272-277`) for refresh-only triggers.
- **G5. `DetailScaffold` recreates the `TabController` whenever `tabs.length` changes** (`detail_scaffold.dart:81-87`), which jumps back to the initial tab. Tab presence must depend only on synchronous privileges, never on async data. The async count goes in `badgeCount`, as `documentsDetailTab` does.
- **G6. `ReferencePicker` falls through to customer search** for unknown `referenceKind` values (`reference_picker.dart:75-76`). A new `REFERENCE` column (region, user, task) silently searches customers until a case is added.
- **G7. Quick filters are hidden unless the schema column is `filterable`** (`data_table_scaffold.dart:745-747`). A "My tasks" chip will not appear if the backend schema does not mark `assigneeId` filterable.
- **G8. Locked filters mean "my book" in two places.** `_lockedLabel` knows only `myBook` (`data_table_scaffold.dart:806-809`), and the chip tooltip says "Your role limits this list to your own records". The dashboard sets `book = lockedFilters.isNotEmpty` (`dashboard_providers.dart:232`). If the backend expresses region scoping as a locked filter, lists mislabel it and the dashboard shows "Your book" wrongly. Extend both.
- **G9. `CurrentUser` has an all-required const constructor.** Tests construct it directly (`list_columns_test.dart:22-38`, `documents_tab_test.dart:32-39`, `email_compose_test.dart:19-35`). New fields (regions, grants) must be optional with `const` defaults.
- **G10. `fromJson` uses non-null casts** (`(json['x'] as num).toInt()`). A new required field breaks every test fixture that lacks it, for example `_invoice` and `_customerWithInactiveSuccessPoc` in `list_columns_test.dart:53-92`. Parse new fields as nullable.
- **G11. Tests assert exact query-parameter maps:**
  - `documents_tab_test.dart:163-164, 206-207` expects `{'entityType':'INVOICE','entityId':42,'page':0,'size':20}`;
  - `email_compose_test.dart:668-669, 678-679, 687-688` expects `utcOffsetMinutes` and nothing else.
  - Add region or as-of params **only when set**.
- **G12. `list_columns_test.dart:193-194` expects exactly 8 invoice `SummaryTile`s.** Adding a tile such as "Pending approval" breaks it.
- **G13. `list_columns_test`'s fake Dio answers every path with a page map** (`:108-116`). A new provider watched by `AppShell`, for example a regions list or a tasks badge, that expects a `List` or a `{count}` would throw during parsing. Only `pollUnreadCount` swallows errors. Override new shell providers in the test's `ProviderScope`, as `:157-158` does for the unread counts. Also, the nav badge is hard-wired to `inboxUnreadCountProvider` (`app_shell.dart:235-236`).
- **G14. Every save flow assumes a 2xx means "applied"** ("Customer saved", "Invoice saved", "Payment saved"; `customer_detail_screen.dart:126-127`, etc.). With maker-checker a 202 or `pending` payload would be announced as saved.
- **G15. Sidebar selection is a longest-prefix match** (`app_shell.dart:188-201`). Sibling paths sharing a prefix cross-select.
- **G16. `needs(navPrivilegesFor(path))` for a path without a `NavEntry` returns `[]`**, which lets everyone in (`app_shell.dart:50-55`, `router.dart:44-48`). Use `needs(const [Privileges.x])` for non-nav routes.
- **G17. `UnsavedChanges` is a single slot** (`unsaved_changes.dart:5-18`). A second editor, or a dialog that registers, displaces the page's guard. `unregister` only clears when the function is identical.
- **G18. Detail screens seed their controllers once** (`_seeded` / `_loadedInto`; `customer_detail_screen.dart:67-75`, `invoice_detail_screen.dart:64-74`, `payment_detail_screen.dart:59-65`). After a pending (B2) save, or a scope or as-of change, the fields keep stale or unapplied values unless re-seeded.
- **G19. Hard-coded 409 meaning.** Customer delete maps *any* 409 to "has invoices or payments" (`customer_detail_screen.dart:185-189`). If B2 uses 409 for "a change is already pending", this message lies.
- **G20. Dispute approval checks `role == 'ADMIN'`** (`dispute_detail_screen.dart:123`), not a privilege and not a region. Do not copy it for approvals.
- **G21. Local vs UTC dates.** `formatDate` uses `toLocal()` (`format.dart:37-40`). LocalDate wire values need `formatUtcDate` / `utcDay` to avoid off-by-one days. Build `asOf` with `DateFormat('yyyy-MM-dd')` on the picked local date; never use `toUtc().toIso8601String()`. The dashboard's "as of today (UTC)" copy and `todayUtc()` must change for B3.
- **G22. Cached providers that will not follow a region switch:**
  - `tableSchemaProvider` (not autoDispose; `table_providers.dart:46-54`);
  - `myPocScopeProvider` (not autoDispose; `poc_providers.dart:115-121`).
  - They must `ref.watch` the scope if they become region-dependent.
- **G23. Tests bypass `dioProvider`'s interceptors.** Tests override `dioProvider` with a bare `Dio` (`FakeBackend.dio`). Scope params injected in an interceptor would be invisible to tests and untestable. Prefer adding them explicitly in the providers.
- **G24. Exhaustive enum switches.** `EmailEntityType` (`email_entity.dart:34-96`) and `DocumentEntityType` have exhaustive switches. Making Task an email or document entity requires updating every switch (the compiler will list them). The audit `_routeFor` / `_iconFor` / `_typeOrder` maps (`audit_history_panel.dart:232-257`) are **not** exhaustive, so new types silently get no link.
- **G25. `parseX` enum helpers fall back silently** (for example unknown becomes `PENDING` or `UNPAID`; `dispute.dart:22-23`, `invoice.dart:6-11`). A new backend status value, such as `PENDING_APPROVAL`, would render as the fallback with no error. Add enum values in lockstep with the backend.
- **G26. Any 401 outside `/api/auth/*` logs the user out** (`api_client.dart:48-52`). Region denials must be 403, which renders "You do not have permission to view this …" through `notFoundMessage`.
- **G27. Two fake backends exist.** `email_compose_test.dart:125-150` has its own `_Backend` (a copy of `FakeBackend`, but its rejections omit `type: badResponse`). New tests should use `test/support/fake_backend.dart`.
- **G28. Import cycles.** `shared/models` imports feature files (`customer.dart:1` and others import `poc_providers.dart`). New shared models that import feature providers risk cycles.

---

## 18. How the tests fake the backend

- **`test/support/fake_backend.dart:1-38`**
  ```dart
  class FakeFailure { final int status; final Object? data; const FakeFailure(this.status, this.data); }
  class FakeBackend {
    final Map<String, Object? Function(RequestOptions)> routes;   // key: 'GET /api/documents'  (path only, no query)
    final List<RequestOptions> requests = [];
    final Map<String, Future<void>> held = {};                     // gate a route to test loading states
    List<RequestOptions> sent(String key) => requests.where((r) => '${r.method} ${r.path}' == key).toList();
    Dio get dio => Dio()..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async { … 404 {'message':'no fake route'} if unknown; FakeFailure → handler.reject(DioException(badResponse)); else handler.resolve(Response(200, data: answer)); }));
  }
  ```
  - Routes are keyed by method plus path. Assertions inspect `sent(key).single.queryParameters` or `.data`.
  - Routes can be added after construction: `backend.routes['DELETE /api/documents/1'] = (_) => null;` (`documents_tab_test.dart:583`).
  - A route can branch on `options.queryParameters` (`:183-186`).
  - An error body can be bytes (`:571-572`).
- **Harness pattern** (`documents_tab_test.dart:88-139`):
  - Size the window: `tester.view.physicalSize = size; tester.view.devicePixelRatio = 1; addTearDown(tester.view.reset);`
  - Pump `ProviderScope(overrides: [dioProvider.overrideWithValue(backend.dio), currentUserProvider.overrideWithValue(user), …])` around `MaterialApp(theme: AppTheme.light(), home: Scaffold(body: Consumer(builder: … DetailScaffold(tabs: [ if (tab != null) tab ]))))`.
  - `pumpAndSettle`, or `pump()` plus `pump(1ms)` to observe loading while a route is held.
- **Router-level harness** (`list_columns_test.dart:118-164`):
  - builds its own `GoRouter` with a `ShellRoute(AppShell)` and only the routes under test;
  - overrides `dioProvider`, `currentUserProvider`, `unreadCountProvider` and `inboxUnreadCountProvider` (`:153-159`);
  - asserts URL changes via `router.routerDelegate.currentConfiguration.uri` (`:229-230`);
  - loads Roboto for real text measurement (`support/roboto.dart`); runs as the macOS variant.
- **Dialog harness** (`email_compose_test.dart:183-212`): a page with an "open" button whose `onPressed` calls the `show…Dialog`. The same pattern tests a `showTaskDialog` or a rule-action editor.
- **The tests override `currentUserProvider`, not `authControllerProvider`.** Any widget reading `authControllerProvider` directly (only `AppShell` logout and the router do) is untouched. New code should read `currentUserProvider`, or a derived `Provider`, so tests can drive it.
- **What exists today:** 3 test files (documents tab, email compose, list columns). There are no tests for the router, customers detail, promises or disputes. New features should add a `test/<feature>_test.dart` using `FakeBackend`, and cover the privilege-gated UI, the exact request payloads (as `email_compose_test.dart:742-757` does), loading, error with Retry, and empty states.