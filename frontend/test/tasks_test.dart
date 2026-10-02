import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/approvals/approval_providers.dart';
import 'package:gene_invoice/features/assignees/recipient_chips_field.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/email/email_models.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/tasks/task_detail_screen.dart';
import 'package:gene_invoice/features/tasks/task_models.dart';
import 'package:gene_invoice/features/tasks/task_providers.dart';
import 'package:gene_invoice/features/tasks/tasks_screen.dart';
import 'package:gene_invoice/features/tasks/tasks_tab.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import 'support/fake_backend.dart';

const _tasker = {
  Privileges.customerView,
  Privileges.invoiceView,
  Privileges.disputeView,
  Privileges.taskView,
  Privileges.taskManage,
  Privileges.exportData,
};

const _south = RegionAccess(
    id: 2, code: 'SOUTH', name: 'South', level: 'MANAGE', scope: 'ALL', privileges: _tasker);

CurrentUser _user({Set<String> privileges = _tasker}) => CurrentUser(
      id: 3,
      username: 'tom',
      fullName: 'Tom Tasker',
      role: 'MANAGER',
      privileges: privileges,
      customerId: null,
      regions: const [_south],
    );

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data, headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'regionIds': const [2],
      'lockedFilters': const [],
    };

Map<String, dynamic> _task({
  int id = 41,
  String status = 'OPEN',
  String? dueDate = '2026-09-28',
  String? description,
  bool canEdit = true,
  bool canChangeStatus = true,
  int? version = 3,
  String entityType = 'INVOICE',
  int entityId = 77,
  String entityLabel = 'INV-0077',
  List<Map<String, dynamic>>? assignees,
}) =>
    {
      'id': id,
      'title': 'Chase INV-0077',
      'description': description,
      'status': status,
      'dueDate': dueDate,
      'overdue': false,
      'entityType': entityType,
      'entityId': entityId,
      'entityLabel': entityLabel,
      'entityDeleted': false,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'regionId': 2,
      'regionCode': 'SOUTH',
      'regionName': 'South',
      'assignees': assignees ??
          [
            {
              'token': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
              'label': 'Collection POC (customer)',
              'people': [
                {'userId': 5, 'name': 'Cara Collections', 'primary': true},
              ],
              'unresolved': false,
            },
          ],
      'assignedToMe': true,
      'origin': 'USER',
      'rule': null,
      'createdBy': {'id': 5, 'name': 'Cara Collections'},
      'createdAt': '2026-09-26T09:00:00Z',
      'updatedAt': '2026-09-26T09:00:00Z',
      'completedAt': null,
      'version': version,
      'canEdit': canEdit,
      'canChangeStatus': canChangeStatus,
    };

/// `/api/assignees/context` for invoice 77: the email To field's shape (§6.13).
const _context = {
  'entityType': 'INVOICE',
  'entityId': 77,
  'entityLabel': 'INV-0077',
  'sender': {
    'restricted': false,
    'self': {'userId': 3, 'name': 'Tom Tasker'},
  },
  'roles': [
    {
      'role': 'COLLECTION_POC',
      'label': 'Collection POC',
      'level': 'CUSTOMER',
      'levelLabel': 'Customer',
      'groupLabel': 'Customer level',
      'resolved': true,
      'people': [
        {'userId': 5, 'name': 'Cara Collections', 'email': 'cara@example.com'},
      ],
    },
    {
      'role': 'SALES_POC',
      'label': 'Sales POC',
      'level': 'RECORD',
      'levelLabel': 'Invoice',
      'groupLabel': 'This invoice',
      'resolved': true,
      'people': [
        {'userId': 6, 'name': 'Sam Sales'},
        {'userId': 7, 'name': 'Sue Sales'},
      ],
    },
  ],
  'customerEmails': {'available': true, 'addresses': []},
};

const _invoiceJson = {
  'id': 77,
  'invoiceNumber': 'INV-0077',
  'customerId': 5,
  'customerName': 'Acme Ltd',
  'invoiceDate': '2026-09-01',
  'dueDate': '2026-10-01',
  'total': 1000.0,
  'paidAmount': 0.0,
  'balance': 1000.0,
  'status': 'UNPAID',
  'notes': '',
  'items': [],
  'regionId': 2,
};

const _customerJson = {
  'id': 5,
  'name': 'Acme Ltd',
  'outstanding': 1000.0,
  'creditBalance': 0.0,
  'pocMissing': false,
  'regionId': 2,
};

/// The "My tasks" badge polls on its own; most cases here are about something else.
final _noBadge = myTasksCountProvider.overrideWith((ref) => Stream.value(0));
final _noApprovals = approvalsAwaitingMeCountProvider.overrideWith((ref) => Stream.value(0));

Future<(FakeBackend, ProviderContainer)> _pump(
  WidgetTester tester, {
  required Widget page,
  required Map<String, Object? Function(RequestOptions)> routes,
  CurrentUser? user,
  DateTime? asOf,
  List<Override> overrides = const [],
  String initialLocation = '/page',
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes));
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user ?? _user()),
    _noApprovals,
    ...overrides,
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: initialLocation, routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/page', builder: (_, __) => page),
        // As the app's router builds them (router.dart).
        GoRoute(
            path: '/my-tasks',
            builder: (_, s) => TasksScreen(
                  key: const ValueKey('my-tasks'),
                  mine: true,
                  query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'dueDate,asc'),
                )),
        GoRoute(
            path: '/tasks',
            builder: (_, s) => TasksScreen(
                  key: const ValueKey('tasks'),
                  query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'dueDate,asc'),
                )),
        GoRoute(path: '/tasks/:id', builder: (_, s) => Text('task ${s.pathParameters['id']}')),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return (backend, container);
}

Map<String, dynamic> _json(Object? data) => (data as Map).cast<String, dynamic>();

TabController _tabs(WidgetTester tester) =>
    tester.widget<TabBar>(find.byType(TabBar)).controller!;

void main() {
  group('a record\'s Tasks tab', () {
    testWidgets('asks for the record\'s tasks, a page at a time, soonest due first',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 77, regionId: 2),
          routes: {'GET /api/tasks': _answer(_page([_task()]))},
          overrides: [_noBadge]);

      final asked = backend.sent('GET /api/tasks').single.queryParameters;
      expect(asked['entityType'], 'INVOICE');
      expect(asked['entityId'], 77);
      expect(asked['page'], 0);
      expect(asked['size'], 20);
      expect(asked['sort'], 'dueDate,asc');
      expect(asked.containsKey('customerId'), isFalse);
      expect(find.text('Chase INV-0077'), findsOneWidget);
      expect(find.text('Collection POC (customer)'), findsOneWidget);
      // The tab is about one record: it need not name it on every row.
      expect(find.text('Record'), findsNothing);
    });

    testWidgets('a customer\'s tab asks by customer, and names the record each task is on',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TasksTab(
              entityType: TaskEntityType.CUSTOMER, entityId: 5, customerScope: true, regionId: 2),
          routes: {'GET /api/tasks': _answer(_page([_task()]))},
          overrides: [_noBadge]);

      final asked = backend.sent('GET /api/tasks').single.queryParameters;
      expect(asked['customerId'], 5);
      expect(asked.containsKey('entityType'), isFalse);
      expect(asked.containsKey('entityId'), isFalse);
      expect(find.text('Record'), findsOneWidget);
      expect(find.text('INV-0077'), findsOneWidget);
    });

    testWidgets('the next page is asked for with the same record', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.PAYMENT, entityId: 9, regionId: 2),
          routes: {
            'GET /api/tasks': _answer({
              ..._page([_task(entityType: 'PAYMENT', entityId: 9)]),
              'totalElements': 45,
              'totalPages': 3,
            }),
          },
          overrides: [_noBadge]);

      await tester.tap(find.byTooltip('Next page'));
      await tester.pumpAndSettle();
      final second = backend.sent('GET /api/tasks').last.queryParameters;
      expect(second['page'], 1);
      expect(second['entityType'], 'PAYMENT');
      expect(second['entityId'], 9);
    });

    testWidgets('Mark done posts the new status with the version it was read at', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 77, regionId: 2),
          routes: {
            'GET /api/tasks': _answer(_page([_task()])),
            'POST /api/tasks/41/status': (o) => _task(status: 'DONE', version: 4),
          },
          overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-41-done')));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/tasks/41/status').single.data, {'status': 'DONE', 'version': 3});
      expect(find.text('Task done'), findsOneWidget);
      // The list is asked again, and shows what the server now holds.
      expect(backend.sent('GET /api/tasks').length, 2);
    });

    testWidgets('someone else\'s change first is a 409: said plainly, and the list reloads',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 77, regionId: 2),
          routes: {
            'GET /api/tasks': _answer(_page([_task()])),
            'POST /api/tasks/41/status': (o) =>
                const FakeFailure(409, {'message': 'This task was changed by someone else'}),
          },
          overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-41-start')));
      await tester.pumpAndSettle();

      expect(find.text('Someone else changed this task first. It has been reloaded; try again.'),
          findsOneWidget);
      expect(backend.sent('GET /api/tasks').length, 2);
    });

    testWidgets('an assignee who cannot manage tasks may move one along, but not cancel it',
        (tester) async {
      await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 77, regionId: 2),
          user: _user(privileges: {Privileges.invoiceView, Privileges.taskView}),
          routes: {'GET /api/tasks': _answer(_page([_task(canEdit: false)]))},
          overrides: [_noBadge]);

      expect(find.byKey(const ValueKey('task-41-start')), findsOneWidget);
      expect(find.byKey(const ValueKey('task-41-done')), findsOneWidget);
      expect(find.byKey(const ValueKey('task-41-cancel')), findsNothing);
      expect(find.byKey(const ValueKey('task-new')), findsNothing);
    });

    testWidgets('the server\'s flags decide, whatever the privileges', (tester) async {
      await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 77, regionId: 2),
          routes: {
            'GET /api/tasks':
                _answer(_page([_task(canEdit: false, canChangeStatus: false)])),
          },
          overrides: [_noBadge]);

      for (final move in TaskMove.values) {
        expect(find.byKey(ValueKey('task-41-${move.name}')), findsNothing);
      }
    });
  });

  group('the task form', () {
    Map<String, Object? Function(RequestOptions)> routes() => {
          'GET /api/tasks': _answer(_page(const [])),
          'GET /api/assignees/context': (_) => _context,
          'GET /api/assignees/people': (_) => [
                {'userId': 8, 'name': 'Pat Person', 'username': 'pat', 'email': 'pat@example.com'},
              ],
          'POST /api/tasks': (o) => _task(id: 52),
        };

    Future<FakeBackend> openNew(WidgetTester tester,
        {Map<String, Object? Function(RequestOptions)>? more}) async {
      final (backend, _) = await _pump(tester,
          page: const TasksTab(
              entityType: TaskEntityType.INVOICE,
              entityId: 77,
              entityLabel: 'INV-0077',
              regionId: 2),
          routes: {...routes(), ...?more},
          overrides: [_noBadge]);
      await tester.tap(find.byKey(const ValueKey('task-new')));
      await tester.pumpAndSettle();
      return backend;
    }

    testWidgets('offers the record\'s roles from /api/assignees/context, with who holds them',
        (tester) async {
      final backend = await openNew(tester);

      final asked = backend.sent('GET /api/assignees/context').single.queryParameters;
      expect(asked, {'entityType': 'INVOICE', 'entityId': 77});
      expect(find.text('New task • INV-0077'), findsOneWidget);
      expect(find.text('Customer level'), findsOneWidget);
      expect(find.text('This invoice'), findsOneWidget);
      expect(find.text('Collection POC · Cara Collections'), findsOneWidget);
      expect(find.text('Sales POC · Sam Sales, Sue Sales'), findsOneWidget);
      // Only an email has any use for the customer's own addresses.
      expect(find.byKey(const ValueKey('assignee-add-customer')), findsNothing);
    });

    testWidgets('creates the task with its role and person assignees, as tokens', (tester) async {
      final backend = await openNew(tester);

      await tester.enterText(find.byKey(const ValueKey('task-title')), '  Call about INV-0077 ');
      await tester.enterText(find.byKey(const ValueKey('task-description')), 'Ask for a date');
      await tester.tap(find.byKey(const ValueKey('assignee-add-role-COLLECTION_POC-CUSTOMER')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('assignee-add-person')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('people-search')), 'pat');
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Pat Person'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('task-due')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('assignee-role-COLLECTION_POC-CUSTOMER')), findsOneWidget);
      expect(find.byKey(const ValueKey('assignee-user-8')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('task-save')));
      await tester.pumpAndSettle();

      // People are searched among those who can see this invoice (§6.13).
      final search = backend.sent('GET /api/assignees/people').last.queryParameters;
      expect(search, {'q': 'pat', 'entityType': 'INVOICE', 'entityId': 77});
      expect(_json(backend.sent('POST /api/tasks').single.data), {
        'entityType': 'INVOICE',
        'entityId': 77,
        'title': 'Call about INV-0077',
        'description': 'Ask for a date',
        'dueDate': DateFormat('yyyy-MM-dd').format(DateTime.now()),
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          {'type': 'USER', 'userId': 8},
        ],
      });
      expect(find.text('Task created'), findsOneWidget);
      expect(find.byType(AlertDialog), findsNothing);
    });

    testWidgets('a title is needed; nothing is sent without one', (tester) async {
      final backend = await openNew(tester);

      await tester.tap(find.byKey(const ValueKey('task-save')));
      await tester.pumpAndSettle();

      expect(find.text('Say what needs doing'), findsOneWidget);
      expect(backend.sent('POST /api/tasks'), isEmpty);
    });

    testWidgets('a person who cannot see the record is refused under the assignees',
        (tester) async {
      final backend = await openNew(tester, more: {
        'POST /api/tasks': (_) => const FakeFailure(400, {
              'message': 'Validation failed',
              'fieldErrors': {'assignees': 'Pat Person cannot see this invoice'},
            }),
      });

      await tester.enterText(find.byKey(const ValueKey('task-title')), 'Call');
      await tester.tap(find.byKey(const ValueKey('task-save')));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/tasks'), hasLength(1));
      expect(find.byType(AlertDialog), findsOneWidget);
      final field = find.byKey(const ValueKey('task-assignees'));
      expect(find.descendant(of: field, matching: find.text('Pat Person cannot see this invoice')),
          findsOneWidget);
    });
  });

  group('one task', () {
    Map<String, Object? Function(RequestOptions)> routes(Map<String, dynamic> task) => {
          'GET /api/tasks/41': _answer(task),
          'GET /api/assignees/context': (_) => _context,
          'PATCH /api/tasks/41': (o) => {...task, 'version': 4},
          'POST /api/tasks/41/status': (o) => {...task, 'status': 'DONE', 'version': 4},
        };

    testWidgets('shows what it is on, when it is due and who holds each role', (tester) async {
      await _pump(tester,
          page: const TaskDetailScreen(id: 41),
          routes: routes(_task(description: 'Ask for a date')),
          overrides: [_noBadge]);

      expect(find.text('Chase INV-0077'), findsOneWidget);
      expect(find.text('Open'), findsOneWidget);
      expect(find.text('INV-0077'), findsOneWidget);
      expect(find.text('2026-09-28'), findsOneWidget);
      expect(find.text('Ask for a date'), findsOneWidget);
      expect(find.text('Collection POC (customer)'), findsOneWidget);
      expect(find.text('Cara Collections'), findsOneWidget);
    });

    testWidgets('Mark done posts {status: DONE, version}', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TaskDetailScreen(id: 41), routes: routes(_task()), overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-done')));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/tasks/41/status').single.data, {'status': 'DONE', 'version': 3});
      expect(backend.sent('GET /api/tasks/41'), hasLength(2));
    });

    testWidgets('Cancel asks first', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TaskDetailScreen(id: 41), routes: routes(_task()), overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-cancel')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Keep it'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/tasks/41/status'), isEmpty);

      await tester.tap(find.byKey(const ValueKey('task-cancel')));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Cancel task'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/tasks/41/status').single.data,
          {'status': 'CANCELLED', 'version': 3});
    });

    testWidgets('a finished task can only be reopened', (tester) async {
      await _pump(tester,
          page: const TaskDetailScreen(id: 41),
          routes: routes(_task(status: 'DONE')),
          overrides: [_noBadge]);

      expect(find.byKey(const ValueKey('task-reopen')), findsOneWidget);
      expect(find.byKey(const ValueKey('task-done')), findsNothing);
      expect(find.byKey(const ValueKey('task-start')), findsNothing);
      expect(find.byKey(const ValueKey('task-cancel')), findsNothing);
    });

    testWidgets('a started task can be put back to open, finished or cancelled', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TaskDetailScreen(id: 41),
          routes: routes(_task(status: 'IN_PROGRESS')),
          overrides: [_noBadge]);

      expect(find.byKey(const ValueKey('task-start')), findsNothing);
      expect(find.byKey(const ValueKey('task-done')), findsOneWidget);
      expect(find.byKey(const ValueKey('task-cancel')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('task-reopen')));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/tasks/41/status').single.data, {'status': 'OPEN', 'version': 3});
    });

    testWidgets('a due date more than five years out still opens the picker', (tester) async {
      final far = DateTime.now().add(const Duration(days: 365 * 8));
      final (backend, _) = await _pump(tester,
          page: const TaskDetailScreen(id: 41),
          routes: routes(_task(dueDate: DateFormat('yyyy-MM-dd').format(far))),
          overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('task-due')));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byType(DatePickerDialog), findsOneWidget);
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('task-save')));
      await tester.pumpAndSettle();
      // The same date back is no change.
      expect(backend.sent('PATCH /api/tasks/41'), isEmpty);
    });

    testWidgets('an edit sends only what changed, with the version', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TaskDetailScreen(id: 41), routes: routes(_task()), overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-edit')));
      await tester.pumpAndSettle();
      expect(find.text('Edit task'), findsOneWidget);
      // The role already assigned is named, and not offered again.
      expect(find.byKey(const ValueKey('assignee-role-COLLECTION_POC-CUSTOMER')), findsOneWidget);
      expect(find.byKey(const ValueKey('assignee-add-role-COLLECTION_POC-CUSTOMER')), findsNothing);

      await tester.enterText(find.byKey(const ValueKey('task-title')), 'Chase INV-0077 again');
      await tester.tap(find.byKey(const ValueKey('task-due-clear')));
      await tester.tap(find.byKey(const ValueKey('assignee-add-role-SALES_POC-RECORD')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('task-save')));
      await tester.pumpAndSettle();

      expect(_json(backend.sent('PATCH /api/tasks/41').single.data), {
        'title': 'Chase INV-0077 again',
        'clearDueDate': true,
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
        ],
        'version': 3,
      });
      expect(find.text('Task saved'), findsOneWidget);
    });

    testWidgets('an edit that changes nothing sends nothing', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const TaskDetailScreen(id: 41), routes: routes(_task()), overrides: [_noBadge]);

      await tester.tap(find.byKey(const ValueKey('task-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('task-save')));
      await tester.pumpAndSettle();

      expect(backend.sent('PATCH /api/tasks/41'), isEmpty);
      expect(find.byType(AlertDialog), findsNothing);
    });

    testWidgets('a rule\'s task says which rule made it', (tester) async {
      await _pump(tester,
          page: const TaskDetailScreen(id: 41),
          routes: routes({
            ..._task(),
            'origin': 'AUTOMATION',
            'rule': {'id': 12, 'name': 'Chase overdue invoices'},
          }),
          overrides: [_noBadge]);

      expect(find.text('Created by rule'), findsOneWidget);
      expect(find.text('Chase overdue invoices'), findsOneWidget);
    });
  });

  group('the lists', () {
    testWidgets('/my-tasks asks /api/tasks/mine, and its tiles /api/tasks/mine/summary',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const SizedBox(),
          initialLocation: '/my-tasks',
          routes: {
            'GET /api/tasks/mine': _answer(_page([_task()])),
            'GET /api/tasks/mine/summary': (_) =>
                {'count': 1, 'open': 1, 'inProgress': 0, 'overdue': 0, 'dueToday': 0},
          },
          overrides: [_noBadge]);

      expect(backend.sent('GET /api/tasks/mine'), hasLength(1));
      expect(backend.sent('GET /api/tasks/mine/summary'), hasLength(1));
      expect(backend.sent('GET /api/tasks'), isEmpty);
      expect(find.text('Chase INV-0077'), findsOneWidget);
      // Mine by assignment, not "my records only": no locked chip (R14).
      expect(find.byIcon(Icons.lock_outline), findsNothing);
    });

    testWidgets('from /my-tasks a bulk move goes to /api/tasks/bulk, kept to my tasks',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const SizedBox(),
          initialLocation: '/my-tasks?f=overdue%3Aeq%3Atrue',
          routes: {
            'GET /api/tasks/mine': _answer(_page([_task()])),
            'GET /api/tasks/mine/summary': (_) => {'count': 1},
            'POST /api/tasks/bulk': (_) => {
                  'action': 'START',
                  'requested': 1,
                  'succeeded': [41],
                  'failed': [],
                  'skipped': [],
                },
          },
          overrides: [_noBadge]);

      await tester.tap(find.byType(Checkbox).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Start'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      final sent = _json(backend.sent('POST /api/tasks/bulk').single.data);
      expect(sent['action'], 'START');
      expect(sent['ids'], [41]);
      // "Every row matching" from here means every one of mine matching.
      expect(sent['filters'], ['overdue:eq:true', 'assigneeUserId:eq:3']);
      expect(backend.requests.where((r) => r.path == '/api/tasks/mine/bulk'), isEmpty);
    });

    testWidgets('/tasks offers "Assigned to me" as a filter on my id', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const SizedBox(),
          initialLocation: '/tasks',
          routes: {
            'GET /api/tasks': _answer(_page([_task()])),
            'GET /api/tasks/summary': (_) => {'count': 1},
            'GET /api/table-schemas/tasks': (_) => {
                  'entity': 'tasks',
                  'columns': [
                    {
                      'name': 'assigneeUserId',
                      'label': 'Assignee',
                      'type': 'REFERENCE',
                      'filterable': true,
                      'operators': ['eq'],
                      'referenceKind': 'staffUser',
                    },
                  ],
                },
          },
          overrides: [_noBadge]);

      await tester.tap(find.text('Assigned to me'));
      await tester.pumpAndSettle();

      expect(backend.sent('GET /api/tasks').last.queryParameters['filter'],
          ['assigneeUserId:eq:3']);
    });

    testWidgets('a bulk move reaches the badge too', (tester) async {
      var badgeBuilds = 0;
      final (backend, _) = await _pump(tester,
          page: const SizedBox(),
          initialLocation: '/tasks',
          routes: {
            'GET /api/tasks': _answer(_page([_task()])),
            'GET /api/tasks/summary': (_) => {'count': 1},
            'POST /api/tasks/bulk': (_) => {
                  'action': 'MARK_DONE',
                  'requested': 1,
                  'succeeded': [41],
                  'failed': [],
                  'skipped': [],
                },
          },
          overrides: [
            myTasksCountProvider.overrideWith((ref) => Stream.value(++badgeBuilds)),
          ]);
      expect(badgeBuilds, 1);

      await tester.tap(find.byType(Checkbox).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Mark done'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/tasks/bulk').single.data['action'], 'MARK_DONE');
      expect(backend.sent('POST /api/tasks/bulk').single.data['ids'], [41]);
      expect(badgeBuilds, 2);
      expect(backend.sent('GET /api/tasks'), hasLength(2));
    });
  });

  group('navigation', () {
    test('My tasks and Tasks come after Disputes, before Approvals', () {
      final paths = navEntries.map((e) => e.path).toList();
      final disputes = paths.indexOf('/disputes');
      expect(paths.sublist(disputes + 1, disputes + 4), ['/my-tasks', '/tasks', '/approvals']);
      final mine = navEntries.firstWhere((e) => e.path == '/my-tasks');
      expect(mine.badge, NavBadge.myTasks);
      expect(mine.hideForCustomer, isTrue);
      expect(navPrivilegesFor('/tasks'), [Privileges.taskView, Privileges.taskManage]);
    });

    testWidgets('the My tasks badge shows the count', (tester) async {
      await _pump(tester,
          page: const SizedBox(),
          routes: const {},
          overrides: [myTasksCountProvider.overrideWith((ref) => Stream.value(4))]);

      final badge = find.ancestor(
          of: find.byIcon(Icons.assignment_ind_outlined), matching: find.byType(Badge));
      expect(badge, findsOneWidget);
      expect(find.descendant(of: badge, matching: find.text('4')), findsOneWidget);
    });

    test('the count is my open tasks, for the regions asked about', () async {
      late ProviderContainer container;
      final backend = FakeBackend({'GET /api/tasks/mine/count': (_) => {'count': 4}});
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(_user()),
      ]);
      addTearDown(container.dispose);
      final seen = <int>[];
      container.listen(myTasksCountProvider, (_, next) => next.whenData(seen.add));
      await container.read(myTasksCountProvider.future);
      expect(seen, [4]);

      container.read(viewScopeProvider.notifier).setRegions({2});
      // Reading the count again builds it for the new regions, and waits for that answer.
      await container.read(myTasksCountProvider.future);
      expect(backend.sent('GET /api/tasks/mine/count').map((r) => r.queryParameters['regionIds']),
          [null, '2']);
    });

    testWidgets('without TASK_VIEW there is no entry and nothing is asked', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const SizedBox(),
          user: _user(privileges: {Privileges.invoiceView}),
          routes: {'GET /api/tasks/mine/count': (_) => {'count': 4}});

      expect(find.byIcon(Icons.assignment_ind_outlined), findsNothing);
      expect(backend.sent('GET /api/tasks/mine/count'), isEmpty);
    });
  });

  group('the Tasks tab on a record\'s page', () {
    Map<String, Object? Function(RequestOptions)> invoiceRoutes() => {
          'GET /api/invoices/77': _answer(_invoiceJson),
          'GET /api/disputes': _answer(_page(const [])),
          'GET /api/tasks': _answer(_page([_task()])),
          'GET /api/tasks/count': _answer({'count': 3, 'open': 2}),
        };

    testWidgets('is not there without TASK_VIEW, and nothing about tasks is asked',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const InvoiceDetailScreen(id: 77),
          user: _user(privileges: {Privileges.invoiceView, Privileges.disputeView}),
          routes: invoiceRoutes(),
          overrides: [_noBadge]);

      expect(find.text('Disputes'), findsWidgets);
      expect(find.widgetWithText(Tab, 'Tasks'), findsNothing);
      expect(backend.sent('GET /api/tasks/count'), isEmpty);
      expect(backend.sent('GET /api/tasks'), isEmpty);
    });

    testWidgets('is there with TASK_VIEW, its badge the open count, and lists the invoice\'s tasks',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const InvoiceDetailScreen(id: 77, initialTab: 'tasks'),
          routes: invoiceRoutes(),
          overrides: [_noBadge]);

      expect(find.widgetWithText(Tab, 'Tasks'), findsOneWidget);
      expect(backend.sent('GET /api/tasks/count').single.queryParameters,
          {'entityType': 'INVOICE', 'entityId': 77});
      final tab = find.widgetWithText(Tab, 'Tasks');
      expect(find.descendant(of: tab, matching: find.text('2')), findsOneWidget);
      expect(backend.sent('GET /api/tasks').single.queryParameters['entityId'], 77);
      expect(find.text('Chase INV-0077'), findsOneWidget);
    });

    testWidgets('a count arriving late, or changing, never moves the tabs back (G5)',
        (tester) async {
      final gate = Completer<void>();
      final routes = invoiceRoutes();
      final (backend, _) = await _pumpHeld(tester,
          routes: routes,
          hold: {'GET /api/tasks/count': gate.future});

      // The tab is there while its count is still on its way.
      expect(find.widgetWithText(Tab, 'Tasks'), findsOneWidget);
      await tester.tap(find.widgetWithText(Tab, 'Tasks'));
      await tester.pumpAndSettle();
      final controller = _tabs(tester);
      final tasksIndex = controller.index;
      expect(tasksIndex, greaterThan(0));

      gate.complete();
      await tester.pumpAndSettle();
      expect(identical(_tabs(tester), controller), isTrue);
      expect(_tabs(tester).index, tasksIndex);

      // Finishing a task changes the count; the tab stays where it is.
      backend.routes['POST /api/tasks/41/status'] = (o) => _task(status: 'DONE', version: 4);
      await tester.tap(find.byKey(const ValueKey('task-41-done')));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/tasks/count').length, greaterThan(1));
      expect(identical(_tabs(tester), controller), isTrue);
      expect(_tabs(tester).index, tasksIndex);
      expect(find.text('Chase INV-0077'), findsOneWidget);
    });

    testWidgets('a customer\'s page asks for its tasks by customer', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const CustomerDetailScreen(id: 5, initialTab: 'tasks'),
          routes: {
            'GET /api/customers/5': _answer(_customerJson),
            'GET /api/disputes': _answer(_page(const [])),
            'GET /api/tasks': _answer(_page([_task()])),
            // The customer's own tasks: none. Its invoices' and payments' are in the summary.
            'GET /api/tasks/count': _answer({'count': 0, 'open': 0}),
            'GET /api/tasks/summary': _answer(
                {'count': 5, 'open': 2, 'inProgress': 1, 'overdue': 0, 'dueToday': 0}),
          },
          overrides: [_noBadge]);

      final asked = backend.sent('GET /api/tasks').single.queryParameters;
      expect(asked['customerId'], 5);
      expect(asked.containsKey('entityType'), isFalse);
      expect(find.text('INV-0077'), findsOneWidget);

      // The badge counts what the tab lists: open and in progress, by customer.
      expect(backend.sent('GET /api/tasks/count'), isEmpty);
      expect(backend.sent('GET /api/tasks/summary').single.queryParameters, {'customerId': 5});
      final tab = find.widgetWithText(Tab, 'Tasks');
      expect(find.descendant(of: tab, matching: find.text('3')), findsOneWidget);
    });
  });

  group('the assignee field', () {
    final ctx = EmailContext.fromJson(_context);

    Future<List<List<EmailToken>>> pumpField(WidgetTester tester,
        {List<EmailToken> value = const [],
        bool single = false,
        Set<String>? roleFilter,
        bool perRecordLabels = false,
        bool allowCustomer = false,
        Map<EmailToken, String> knownLabels = const {}}) async {
      final changes = <List<EmailToken>>[];
      var current = value;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) => RecipientChipsField(
              ctx: ctx,
              value: current,
              single: single,
              roleFilter: roleFilter,
              perRecordLabels: perRecordLabels,
              allowCustomer: allowCustomer,
              knownLabels: knownLabels,
              onChanged: (v) {
                changes.add(v);
                setState(() => current = v);
              },
            ),
          ),
        ),
      ));
      return changes;
    }

    testWidgets('single: another choice replaces the first', (tester) async {
      final changes = await pumpField(tester, single: true);
      await tester.tap(find.byKey(const ValueKey('assignee-add-role-COLLECTION_POC-CUSTOMER')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('assignee-add-role-SALES_POC-RECORD')));
      await tester.pump();
      expect(changes.last, [const EmailToken.role('SALES_POC', level: 'RECORD')]);
    });

    testWidgets('a role filter offers only those roles', (tester) async {
      await pumpField(tester, roleFilter: {'COLLECTION_POC'});
      expect(find.byKey(const ValueKey('assignee-add-role-COLLECTION_POC-CUSTOMER')), findsOneWidget);
      expect(find.byKey(const ValueKey('assignee-add-role-SALES_POC-RECORD')), findsNothing);
    });

    testWidgets('per-record labels name each record\'s seat, not today\'s holder', (tester) async {
      await pumpField(tester, perRecordLabels: true, allowCustomer: true);
      expect(find.text("Collection POC (each customer's)"), findsOneWidget);
      expect(find.text("Sales POC (each invoice's)"), findsOneWidget);
      expect(find.textContaining('Cara Collections'), findsNothing);
      expect(find.byKey(const ValueKey('assignee-add-customer')), findsOneWidget);
    });

    testWidgets('people already assigned keep their names; removing one leaves the rest',
        (tester) async {
      final changes = await pumpField(tester,
          value: const [EmailToken.user(8), EmailToken.role('COLLECTION_POC', level: 'CUSTOMER')],
          knownLabels: {const EmailToken.user(8): 'Pat Person'});
      expect(find.text('Pat Person'), findsOneWidget);
      expect(find.byIcon(Icons.person_outline), findsOneWidget);
      expect(find.byIcon(Icons.badge_outlined), findsWidgets);

      await tester.tap(find.descendant(
          of: find.byKey(const ValueKey('assignee-user-8')), matching: find.byTooltip('Remove')));
      await tester.pump();
      expect(changes.last, [const EmailToken.role('COLLECTION_POC', level: 'CUSTOMER')]);
    });
  });

  group('the Assigned to tooltip on a list row (TAS-R1-1)', () {
    Map<String, dynamic> role(
            {List<Map<String, dynamic>> people = const [], bool unresolved = false}) =>
        {
          'token': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          'label': 'Collection POC (customer)',
          'people': people,
          'unresolved': unresolved,
        };

    Future<List<String>> tooltips(WidgetTester tester, Map<String, dynamic> assignee) async {
      await _pump(tester,
          page: const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 77, regionId: 2),
          routes: {
            'GET /api/tasks': _answer(_page([
              _task(assignees: [assignee])
            ])),
          },
          overrides: [
            _noBadge
          ]);
      expect(find.byType(TaskAssigneesText), findsOneWidget);
      return [
        for (final t in tester.widgetList<Tooltip>(
            find.descendant(of: find.byType(TaskAssigneesText), matching: find.byType(Tooltip))))
          t.message ?? '',
      ];
    }

    testWidgets('a role described briefly (no people, not unresolved) is not called unheld',
        (tester) async {
      final handle = tester.ensureSemantics();
      final messages = await tooltips(tester, role());
      expect(messages, isEmpty);
      expect(find.text('Collection POC (customer)'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('nobody')), findsNothing);
      expect(find.byTooltip(RegExp('nobody')), findsNothing);
      handle.dispose();
    });

    testWidgets('a role nobody holds says so', (tester) async {
      final messages = await tooltips(tester, role(unresolved: true));
      expect(messages, ['Collection POC (customer): nobody holds this now']);
    });

    testWidgets('a role with its holders names them', (tester) async {
      final messages = await tooltips(
          tester,
          role(people: [
            {'userId': 9, 'name': 'Sam South', 'primary': true},
            {'userId': 10, 'name': 'Nora North', 'primary': false},
          ]));
      expect(messages, ['Collection POC (customer): Sam South, Nora North']);
      expect(messages.single, isNot(contains('nobody')));
    });
  });

  test('a task parses tolerantly: unknown status reads as open, missing fields as none', () {
    final t = TaskItem.fromJson({'id': 1, 'title': 'x', 'status': 'SNOOZED'});
    expect(t.status, TaskStatus.OPEN);
    expect(t.dueDate, isNull);
    expect(t.assignees, isEmpty);
    expect(t.canEdit, isNull);
    expect(t.recordRoute, isNull);
    final full = TaskItem.fromJson(_task());
    expect(full.dueDate, DateTime(2026, 9, 28));
    expect(full.recordRoute, '/invoices/77');
    expect(full.ruleId, isNull);
    expect(full.assignees.single.people.single.name, 'Cara Collections');
  });
}

/// Invoice 77's page as the app's router builds it — choosing a tab puts it in the URL — with
/// some routes held until their future completes.
Future<(FakeBackend, ProviderContainer)> _pumpHeld(
  WidgetTester tester, {
  required Map<String, Object? Function(RequestOptions)> routes,
  required Map<String, Future<void>> hold,
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes))..held.addAll(hold);
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(_user()),
    _noApprovals,
    _noBadge,
  ]);
  addTearDown(container.dispose);

  final router = GoRouter(initialLocation: '/invoices/77', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(
            path: '/invoices/:id',
            builder: (_, s) => InvoiceDetailScreen(
                  key: ValueKey('invoice-${s.pathParameters['id']}'),
                  id: int.parse(s.pathParameters['id']!),
                  initialTab: s.uri.queryParameters['tab'],
                )),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return (backend, container);
}
