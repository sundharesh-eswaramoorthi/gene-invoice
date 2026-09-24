import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/tasks/task_actions.dart';
import 'package:gene_invoice/features/tasks/task_detail_screen.dart';
import 'package:gene_invoice/features/tasks/task_providers.dart';
import 'package:gene_invoice/features/tasks/tasks_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:gene_invoice/shared/widgets/detail_scaffold.dart';
import 'package:gene_invoice/shared/widgets/status_chip.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// NORTH is a branch this person MANAGES; SOUTH is one they only read. Every per-record control
// here is gated on the record's own branch, so the pair is what makes hasIn testable (B1).
const _north = RegionGrant(
    id: 1, code: 'NORTH', name: 'North', rights: {regionRightView, regionRightManage});
const _south = RegionGrant(id: 2, code: 'SOUTH', name: 'South', rights: {regionRightView});

const _staff = {
  Privileges.taskView,
  Privileges.taskManage,
  Privileges.invoiceView,
  Privileges.customerView,
};

const _viewer = {Privileges.taskView, Privileges.invoiceView};

CurrentUser _user(Set<String> privileges,
        {List<RegionGrant> regions = const [_north, _south], int? customerId}) =>
    CurrentUser(
      id: 7,
      username: 'nita',
      fullName: 'Nita Nair',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: privileges,
      customerId: customerId,
      regions: regions,
    );

Map<String, dynamic> _column(String name, String label, String type,
        {List<String> operators = const ['eq'],
        bool sortable = false,
        List<String> enumValues = const [],
        String? referenceKind}) =>
    {
      'name': name,
      'label': label,
      'type': type,
      'sortable': sortable,
      'filterable': true,
      'operators': operators,
      'enumValues': enumValues,
      'referenceKind': referenceKind,
    };

/// GET /api/table-schemas/tasks as TaskSchemas.TASKS publishes it: the registered column names,
/// verbatim, so a chip this client composes is one the server would accept (A6).
Map<String, dynamic> _tasksSchema() => {
      'entity': 'tasks',
      'defaultSort': 'dueDate,asc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today', 'last7Days'],
      'columns': [
        _column('id', 'Id', 'NUMBER'),
        _column('title', 'Title', 'TEXT', operators: ['contains', 'eq'], sortable: true),
        _column('entityType', 'About', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['CUSTOMER', 'INVOICE', 'PAYMENT'],
            sortable: true),
        _column('entityId', 'Record id', 'NUMBER'),
        _column('entityLabel', 'Record', 'TEXT', operators: ['contains'], sortable: true),
        _column('customerId', 'Customer', 'REFERENCE', referenceKind: 'customer'),
        _column('regionId', 'Region', 'REFERENCE',
            operators: ['eq', 'in'], referenceKind: 'region'),
        _column('regionName', 'Region name', 'TEXT', operators: ['contains']),
        _column('dueDate', 'Due', 'DATE',
            operators: ['eq', 'lte', 'between', 'relative'], sortable: true),
        _column('status', 'Status', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED'],
            sortable: true),
        _column('assigneeUserId', 'Assigned to', 'REFERENCE',
            operators: ['eq', 'in', 'isEmpty', 'isNotEmpty'], referenceKind: 'pocUser'),
        _column('overdue', 'Overdue', 'BOOLEAN'),
        _column('createdByRuleId', 'Created by rule', 'NUMBER', sortable: true),
        _column('createdAt', 'Created', 'DATE', operators: ['relative'], sortable: true),
      ],
    };

Map<String, dynamic> _task(
  int id, {
  String title = 'Chase the overdue balance',
  String status = 'OPEN',
  String? dueDate = '2026-09-20',
  bool overdue = false,
  int? regionId = 1,
  String entityType = 'INVOICE',
  int entityId = 42,
  String entityLabel = 'INV-0042',
  int? createdByRuleId,
  List<Map<String, dynamic>>? assignees,
}) =>
    {
      'id': id,
      'entityType': entityType,
      'entityId': entityId,
      'entityLabel': entityLabel,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'title': title,
      'notes': 'Ring the accounts desk',
      'dueDate': dueDate,
      'status': status,
      'overdue': overdue,
      'assignees': assignees ??
          [
            {'userId': 7, 'name': 'Nita Nair', 'username': 'nita', 'source': 'USER'},
          ],
      'createdByUserId': 7,
      'createdByRuleId': createdByRuleId,
      'createdByStepId': null,
      'completedByUserId': status == 'DONE' ? 7 : null,
      'completedAt': status == 'DONE' ? '2026-09-23T09:00:00Z' : null,
      'createdAt': '2026-09-18T10:15:00Z',
      'updatedAt': '2026-09-18T10:15:00Z',
      'regionId': regionId,
      'regionName': regionId == 2 ? 'South' : 'North',
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> content,
    {int page = 0, int? total, List<String> locked = const []}) {
  final count = total ?? content.length;
  return {
    'content': content,
    'page': page,
    'size': 20,
    'totalElements': count,
    'totalPages': count == 0 ? 1 : (count / 20).ceil(),
    'sort': 'dueDate,asc',
    'appliedFilters': const <String>[],
    'lockedFilters': locked,
  };
}

Map<String, dynamic> _summary({
  int open = 2,
  int inProgress = 1,
  int overdue = 1,
  int dueThisWeek = 1,
  int done = 0,
}) =>
    {
      'open': open,
      'inProgress': inProgress,
      'overdue': overdue,
      'dueThisWeek': dueThisWeek,
      'done': done,
    };

/// Every route the task screens touch, plus the roster the branch selector asks for. A route left
/// out answers 404 through the fake, which is a silent wrong answer rather than a failure — so
/// they are all declared in one place.
FakeBackend _backend({
  Object? Function(RequestOptions)? list,
  Object? Function(RequestOptions)? summary,
  Object? Function(RequestOptions)? complete,
  List<Map<String, dynamic>>? tasks,
}) =>
    FakeBackend({
      'GET /api/table-schemas/tasks': (_) => _tasksSchema(),
      'GET /api/tasks': list ?? (_) => _page(tasks ?? const []),
      'GET /api/tasks/summary': summary ?? (_) => _summary(),
      'GET /api/regions/my': (_) => {
            'allRegions': false,
            'regions': [
              {'id': 1, 'code': 'NORTH', 'name': 'North', 'rights': ['VIEW', 'MANAGE']},
              {'id': 2, 'code': 'SOUTH', 'name': 'South', 'rights': ['VIEW']},
            ],
          },
      if (complete != null) 'POST /api/tasks/1/complete': complete,
    });

Future<void> _sized(WidgetTester tester, [Size size = const Size(1366, 1100)]) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// The list, inside the shell, behind a real router — the my-work link is a URL and nothing else,
/// so it can only be tested through one (A6).
Future<GoRouter> _pumpList(
  WidgetTester tester,
  FakeBackend backend, {
  required CurrentUser user,
  String location = '/tasks',
  int myOpenTasks = 0,
}) async {
  await _sized(tester);
  final router = GoRouter(
    initialLocation: location,
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/', builder: (_, __) => const SizedBox.shrink()),
          GoRoute(
            path: '/tasks',
            builder: (_, s) => TasksScreen(
                query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'dueDate,asc')),
          ),
          GoRoute(
            path: '/tasks/:id',
            builder: (_, s) => TaskDetailScreen(
                id: int.parse(s.pathParameters['id']!),
                initialTab: s.uri.queryParameters['tab']),
          ),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
      // The poller itself is exercised on its own below. Here it is a value, so the badge's
      // wiring is what is under test and no 30-second timer outlives the widget tree.
      myOpenTaskCountProvider.overrideWith((ref) => Stream.value(myOpenTasks)),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

/// The tab, on an invoice, in the DetailScaffold it is mounted in.
Future<void> _pumpTab(
  WidgetTester tester,
  FakeBackend backend, {
  required CurrentUser user,
  String initialTab = 'tasks',
  bool settle = true,
  int? regionId = 1,
}) async {
  await _sized(tester);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Consumer(builder: (context, ref, _) {
          final tab = tasksDetailTab(ref,
              type: TaskEntityType.invoice,
              entityId: 42,
              entityLabel: 'INV-0042',
              regionId: regionId);
          return DetailScaffold(
            title: 'INV-0042',
            top: const SizedBox(height: 40),
            initialTabSlug: initialTab,
            tabs: [
              DetailTab(
                  slug: 'history',
                  label: 'History',
                  icon: Icons.history,
                  builder: (_) => const SizedBox()),
              if (tab != null) tab,
            ],
          );
        }),
      ),
    ),
  ));
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));
  }
}

String _tileValue(WidgetTester tester, String label) => tester
    .widgetList<SummaryTile>(find.byType(SummaryTile))
    .firstWhere((t) => t.label == label)
    .value;

Finder _chipText(String text) =>
    find.descendant(of: find.byType(TaskStatusChip), matching: find.text(text));

void main() {
  // ------------------------------------------------------------------ my work

  testWidgets('the my-work entry opens the list already seeded with my open tasks',
      (tester) async {
    final backend = _backend(tasks: [_task(1)]);
    final router = await _pumpList(tester, backend,
        user: _user(_staff), location: '/', myOpenTasks: 4);

    // The rail keeps its labels behind the pin until it is extended.
    final pin = find.byTooltip('Keep the sidebar open');
    if (pin.evaluate().isNotEmpty) {
      await tester.tap(pin);
      await tester.pumpAndSettle();
    }
    // The sidebar's own rule, which the router guards the same route with, so a screen the nav
    // hides cannot be reached by hand-editing the URL either (UI-10).
    expect(find.text('Tasks'), findsOneWidget);
    expect(navPrivilegesFor('/tasks'), const [Privileges.taskView]);
    // The badge is what is waiting for THIS person, from the endpoint that answers that question.
    expect(find.descendant(of: find.byType(Badge), matching: find.text('4')), findsOneWidget);

    await tester.tap(find.text('Tasks'));
    await tester.pumpAndSettle();

    // MY WORK IS NOT A SECOND SCREEN AND NOT A SECOND ENDPOINT: it is /tasks with three
    // parameters in the URL, which is why the filter bar shows them as ordinary chips and the
    // back button works (A6).
    expect(router.routerDelegate.currentConfiguration.uri.path, '/tasks');
    final sent = backend.sent('GET /api/tasks').last;
    expect(sent.queryParameters['filter'],
        ['assigneeUserId:eq:7', 'status:in:OPEN,IN_PROGRESS']);
    expect(sent.queryParameters['sort'], 'dueDate,asc');
    expect(find.text('Chase the overdue balance'), findsOneWidget);
  });

  test('the sidebar badge is polled off the count endpoint, with mine set', () async {
    final backend = FakeBackend({
      'GET /api/tasks/count?mine=true': (_) => {'count': 4},
    });
    final container = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_user(_staff)),
    ]);
    addTearDown(container.dispose);
    // An autoDispose provider with nobody listening is torn down the moment it is read, which
    // cancels the poll before its first answer arrives. The listener is what keeps it alive.
    container.listen(myOpenTaskCountProvider, (_, __) {}, fireImmediately: true);

    expect(await container.read(myOpenTaskCountProvider.future), 4);
    // ?mine=true and not a count worked out here: "waiting" and "waiting for me" are different
    // questions and only the server knows which seats this caller holds (A6).
    expect(backend.requests.where((r) => r.path.startsWith('/api/tasks/count')), hasLength(1));
    expect(backend.requests.single.path, contains('mine=true'));
  });

  test('somebody who cannot view tasks is never polled for a count they cannot have', () async {
    final backend = FakeBackend(const {});
    final container = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_user(const {Privileges.invoiceView})),
    ]);
    addTearDown(container.dispose);
    container.listen(myOpenTaskCountProvider, (_, __) {}, fireImmediately: true);

    expect(await container.read(myOpenTaskCountProvider.future), 0);
    expect(backend.requests, isEmpty);
  });

  // ------------------------------------------------------------------ the tab

  testWidgets('the tasks tab is absent for somebody who cannot view tasks', (tester) async {
    final backend = _backend(tasks: [_task(1)]);
    await _pumpTab(tester, backend, user: _user(const {Privileges.invoiceView}));

    // TASK_VIEW is what all four task endpoints are behind, so a tab without it could only ever
    // show a 403 (A6, UI-10).
    expect(find.text('Tasks'), findsNothing);
    expect(backend.sent('GET /api/tasks'), isEmpty);
    expect(find.text('History'), findsOneWidget);
  });

  testWidgets('the tab lists only the tasks about the record it is on', (tester) async {
    final backend = _backend(tasks: [
      _task(1, title: 'Chase the overdue balance'),
      _task(2, title: 'Send the statement', status: 'IN_PROGRESS'),
    ]);
    await _pumpTab(tester, backend, user: _user(_viewer));

    // The record is named through extraParams and NOT as a removable chip: the server merges
    // entityType/entityId into the chips itself, and a chip would be one click away from turning
    // a record's tab into the whole company's list (A6).
    final sent = backend.sent('GET /api/tasks').single;
    expect(sent.queryParameters['entityType'], 'INVOICE');
    expect(sent.queryParameters['entityId'], 42);
    expect(sent.queryParameters['sort'], 'dueDate,asc');

    expect(find.text('Chase the overdue balance'), findsOneWidget);
    expect(find.text('Send the statement'), findsOneWidget);
    expect(_chipText('Open'), findsOneWidget);
    expect(_chipText('In progress'), findsOneWidget);
    // A reader with no TASK_MANAGE is offered nothing to press (A6).
    expect(find.byTooltip('Mark complete'), findsNothing);
    // And no bulk or export controls, which would post the filter bar's chips WITHOUT the record
    // the tab is about (A6).
    expect(find.byType(Checkbox), findsNothing);
  });

  testWidgets('a tab still loading says so, rather than reading as empty', (tester) async {
    final backend = _backend(tasks: [_task(1)]);
    final gate = Completer<void>();
    backend.held['GET /api/tasks'] = gate.future;
    await _pumpTab(tester, backend, user: _user(_staff), settle: false);

    expect(find.text('Loading…'), findsOneWidget);
    expect(find.text('Nothing here yet'), findsNothing);

    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Chase the overdue balance'), findsOneWidget);
  });

  testWidgets('a tab that cannot load says what the server said, and offers to try again',
      (tester) async {
    final backend = _backend(
        list: (_) => const FakeFailure(500, {'message': 'Tasks are unavailable'}));
    await _pumpTab(tester, backend, user: _user(_staff));

    expect(find.text('Request failed'), findsOneWidget);
    expect(find.text('Tasks are unavailable'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Retry'), findsOneWidget);
  });

  testWidgets('a record with nothing outstanding says so', (tester) async {
    final backend = _backend(tasks: const []);
    await _pumpTab(tester, backend, user: _user(_staff));

    expect(find.text('Nothing here yet'), findsOneWidget);
    // Nothing was filtered out, so there is nothing to clear (A6).
    expect(find.text('Clear all filters'), findsNothing);
    // Somebody who may write here is still offered the way to start (A6, B1).
    expect(find.widgetWithText(FilledButton, 'New task'), findsOneWidget);
  });

  // ------------------------------------------------------------------ the list

  testWidgets('completing a task from the list refreshes both the rows and the tiles',
      (tester) async {
    var completed = false;
    final backend = _backend(
      list: (_) => _page([_task(1, status: completed ? 'DONE' : 'OPEN')]),
      summary: (_) => _summary(open: completed ? 0 : 1, done: completed ? 1 : 0),
      complete: (_) {
        completed = true;
        return _task(1, status: 'DONE');
      },
    );
    await _pumpList(tester, backend, user: _user(_staff));

    expect(_tileValue(tester, 'Open'), '1');
    expect(_tileValue(tester, 'Done'), '0');
    expect(_chipText('Open'), findsOneWidget);

    await tester.tap(find.byTooltip('Mark complete'));
    await tester.pumpAndSettle();

    expect(backend.sent('POST /api/tasks/1/complete'), hasLength(1));
    // BOTH. The tiles are counted over the SAME filters the list ran, so a row that moved while
    // the figure above it did not would read as a lost save (A6).
    expect(backend.sent('GET /api/tasks'), hasLength(2));
    expect(backend.sent('GET /api/tasks/summary'), hasLength(2));
    expect(_tileValue(tester, 'Open'), '0');
    expect(_tileValue(tester, 'Done'), '1');
    expect(_chipText('Done'), findsOneWidget);
    // And a terminal task is offered no second helping (A6).
    expect(find.byTooltip('Mark complete'), findsNothing);
  });

  testWidgets('a task in a branch I do not manage offers no Complete button', (tester) async {
    final backend = _backend(tasks: [
      _task(1, title: 'Northern chase', regionId: 1),
      _task(2, title: 'Southern chase', regionId: 2),
    ]);
    await _pumpList(tester, backend, user: _user(_staff));

    expect(find.text('Northern chase'), findsOneWidget);
    expect(find.text('Southern chase'), findsOneWidget);
    // hasIn, not has: TASK_MANAGE is MANAGE-level in the region partition, and this person holds
    // MANAGE in NORTH and only VIEW in SOUTH. Both rows are readable; only one is actionable, and
    // the server would refuse the other anyway (A6, B1).
    expect(find.byTooltip('Mark complete'), findsOneWidget);
  });

  testWidgets('the list offers my-work as a chip, and the overdue filter the schema publishes',
      (tester) async {
    final backend = _backend(tasks: [_task(1, overdue: true)]);
    await _pumpList(tester, backend, user: _user(_staff));

    expect(find.widgetWithText(FilterChip, 'Overdue only'), findsOneWidget);
    expect(find.widgetWithText(FilterChip, 'Assigned to me'), findsOneWidget);
    expect(find.text('Overdue'), findsWidgets);

    await tester.tap(find.widgetWithText(FilterChip, 'Assigned to me'));
    await tester.pumpAndSettle();
    expect(backend.sent('GET /api/tasks').last.queryParameters['filter'],
        ['assigneeUserId:eq:7']);
  });

  // ------------------------------------------------------------------ one task's page

  testWidgets('a task page says what it is about, who is on it and why, and who put it there',
      (tester) async {
    final backend = _backend(tasks: const []);
    backend.routes['GET /api/tasks/1'] = (_) => _task(1, createdByRuleId: 9, assignees: [
          {'userId': 7, 'name': 'Nita Nair', 'username': 'nita', 'source': 'USER'},
          {
            'userId': 8,
            'name': 'Cleo Collections',
            'username': 'cleo',
            'source': 'ROLE:CUSTOMER:COLLECTION_POC',
          },
        ]);
    await _pumpList(tester, backend, user: _user(_staff), location: '/tasks/1');

    expect(find.text('Chase the overdue balance'), findsOneWidget);
    expect(find.text('INV-0042'), findsWidgets);
    expect(find.text('Nita Nair (@nita)'), findsOneWidget);
    expect(find.text('Cleo Collections (@cleo)'), findsOneWidget);
    // The seat's own source, decoded the way RoleRef.label does on the server: which seat
    // somebody holds is WHY they are being asked, and a stored token says nothing to a reader
    // (A6, A3).
    final tips = tester.widgetList<Tooltip>(find.byType(Tooltip)).map((t) => t.message).toList();
    expect(tips, contains('Picked by name'));
    expect(tips, contains('Collection POC (customer)'));
    // A rule made it, and there is nowhere to link to until A-UI declares the rules page (A6, A5).
    expect(find.text('Created by automation rule #9'), findsOneWidget);
    // All three write actions, on hasIn against the task's own branch (A6, B1).
    expect(find.widgetWithText(TextButton, 'Complete'), findsOneWidget);
    expect(find.widgetWithText(TextButton, 'Edit'), findsOneWidget);
    expect(find.widgetWithText(TextButton, 'Cancel'), findsOneWidget);
    // No History tab: "TASK" is not in AuditController.SUPPORTED, so one could only ever error
    // (A6).
    expect(find.text('History'), findsNothing);
    expect(find.text('Notes'), findsOneWidget);
  });

  testWidgets('cancelling a task from its page keeps its notes and its due date', (tester) async {
    final backend = _backend(tasks: const []);
    // A FAITHFUL SERVER, because the defect lives in the gap between the two halves.
    // TaskService.update leaves `title`, `status` and `assigneeUserIds` alone when they arrive
    // null, and REPLACES `notes` and `dueDate` with whatever arrived, null included — so that
    // "this no longer has a due date" stays expressible over a wire on which Jackson cannot tell
    // an absent field from a null one. A fake that merged the patch instead would pass whatever
    // the client sent (A6).
    final stored = _task(1);
    backend.routes['GET /api/tasks/1'] = (_) => stored;
    backend.routes['PATCH /api/tasks/1'] = (o) {
      final body = (o.data as Map).cast<String, dynamic>();
      if (body['title'] != null) stored['title'] = body['title'];
      if (body['status'] != null) stored['status'] = body['status'];
      stored['notes'] = body['notes'];
      stored['dueDate'] = body['dueDate'];
      return stored;
    };
    await _pumpList(tester, backend, user: _user(_staff), location: '/tasks/1');

    expect(find.text('Ring the accounts desk'), findsOneWidget);

    await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
    await tester.pumpAndSettle();
    // The dialog's own promise, which is the thing that has to be true afterwards.
    expect(find.text('It stays on the record as called off. This cannot be undone.'),
        findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Cancel task'));
    await tester.pumpAndSettle();

    final body =
        (backend.sent('PATCH /api/tasks/1').single.data as Map).cast<String, dynamic>();
    expect(body['status'], 'CANCELLED');
    expect(body['notes'], 'Ring the accounts desk');
    expect(body['dueDate'], '2026-09-20');

    // And the record afterwards, read back from the server rather than from the widget that sent
    // the patch: the task is called off and has lost nothing.
    expect(find.text('Task cancelled'), findsOneWidget);
    expect(find.text('Ring the accounts desk'), findsOneWidget);
    expect(find.text('No notes on this task.'), findsNothing);
    expect(find.textContaining('due 2026-09-20'), findsWidgets);
  });

  testWidgets('cancelling a task that never had a due date says so rather than guessing',
      (tester) async {
    final backend = _backend(tasks: const []);
    final stored = _task(1, dueDate: null);
    backend.routes['GET /api/tasks/1'] = (_) => stored;
    backend.routes['PATCH /api/tasks/1'] = (o) {
      final body = (o.data as Map).cast<String, dynamic>();
      if (body['status'] != null) stored['status'] = body['status'];
      stored['notes'] = body['notes'];
      stored['dueDate'] = body['dueDate'];
      return stored;
    };
    await _pumpList(tester, backend, user: _user(_staff), location: '/tasks/1');

    await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Cancel task'));
    await tester.pumpAndSettle();

    // An explicit null and not an omission: on this endpoint they mean the same thing, and
    // sending the field is what makes the rule readable at the call site (A6).
    final body =
        (backend.sent('PATCH /api/tasks/1').single.data as Map).cast<String, dynamic>();
    expect(body.containsKey('dueDate'), isTrue);
    expect(body['dueDate'], isNull);
    expect(body['notes'], 'Ring the accounts desk');
  });

  testWidgets('a task page in a branch I only read offers no write action at all', (tester) async {
    final backend = _backend(tasks: const []);
    backend.routes['GET /api/tasks/1'] = (_) => _task(1, regionId: 2);
    await _pumpList(tester, backend, user: _user(_staff), location: '/tasks/1');

    expect(find.text('Chase the overdue balance'), findsOneWidget);
    expect(find.widgetWithText(TextButton, 'Complete'), findsNothing);
    expect(find.widgetWithText(TextButton, 'Edit'), findsNothing);
    expect(find.widgetWithText(TextButton, 'Cancel'), findsNothing);
  });

  // ------------------------------------------------------------------ making one

  testWidgets('a task raised from a record is about that record and cannot be pointed elsewhere',
      (tester) async {
    final backend = _backend(tasks: const []);
    backend.routes['POST /api/tasks'] = (_) => _task(3, title: 'Ring the accounts desk');
    await _pumpTab(tester, backend, user: _user(_staff));

    await tester.tap(find.widgetWithText(FilledButton, 'New task'));
    await tester.pumpAndSettle();

    // The subject is stated, not offered: PATCH carries no entityType, and a task about another
    // record is another task (A6).
    expect(find.text('Invoice • INV-0042'), findsOneWidget);
    expect(find.byType(DropdownButtonFormField<TaskEntityType>), findsNothing);

    await tester.enterText(find.widgetWithText(TextField, 'Title *'), 'Ring the accounts desk');
    await tester.tap(find.text('Assign to me'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create task'));
    await tester.pumpAndSettle();

    final sent = backend.sent('POST /api/tasks').single.data as Map;
    expect(sent['entityType'], 'INVOICE');
    expect(sent['entityId'], 42);
    expect(sent['title'], 'Ring the accounts desk');
    expect(sent['assigneeUserIds'], [7]);
    // The list behind it is put back in step, or the new task is invisible until a reload (A6).
    expect(backend.sent('GET /api/tasks'), hasLength(2));
  });

  testWidgets('a task with no title is refused here rather than at the server', (tester) async {
    final backend = _backend(tasks: const []);
    await _pumpTab(tester, backend, user: _user(_staff));

    await tester.tap(find.widgetWithText(FilledButton, 'New task'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create task'));
    await tester.pumpAndSettle();

    expect(find.text('Give the task a title'), findsOneWidget);
    expect(backend.sent('POST /api/tasks'), isEmpty);
  });
}
