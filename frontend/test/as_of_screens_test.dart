import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/rule_editor_screen.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/dashboard/dashboard_screen.dart';
import 'package:gene_invoice/features/disputes/dispute_detail_screen.dart';
import 'package:gene_invoice/features/disputes/disputes_tab.dart';
import 'package:gene_invoice/features/documents/document_models.dart';
import 'package:gene_invoice/features/documents/documents_tab.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/invoices/invoice_form_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/payments/payment_detail_screen.dart';
import 'package:gene_invoice/features/promises/promise_detail_screen.dart';
import 'package:gene_invoice/features/promises/promises_tab.dart';
import 'package:gene_invoice/features/tasks/task_detail_screen.dart';
import 'package:gene_invoice/features/tasks/task_models.dart';
import 'package:gene_invoice/features/tasks/task_providers.dart';
import 'package:gene_invoice/features/tasks/tasks_tab.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/dispute.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

final _june30 = DateTime(2026, 6, 30);
const _banner = 'Viewing 30 Jun 2026 (end of day, UTC) — read only';

/// Everyone who could change anything on these pages, were it today.
const _manager = CurrentUser(
  id: 1,
  username: 'admin',
  fullName: 'System Administrator',
  role: 'ADMIN',
  privileges: {
    Privileges.customerView,
    Privileges.customerManage,
    Privileges.invoiceView,
    Privileges.invoiceManage,
    Privileges.paymentView,
    Privileges.paymentManage,
    Privileges.promiseView,
    Privileges.promiseManage,
    Privileges.promiseOverride,
    Privileges.disputeView,
    Privileges.disputeManage,
    Privileges.emailSend,
  },
  customerId: null,
);

/// Only a customer login raises disputes.
const _customer = CurrentUser(
  id: 9,
  username: 'acme',
  fullName: 'Acme Ltd',
  role: 'CUSTOMER',
  privileges: {Privileges.invoiceView, Privileges.disputeView, Privileges.disputeCreate},
  customerId: 5,
);

/// Someone who may upload, edit and remove an invoice's documents today.
const _documentKeeper = CurrentUser(
  id: 3,
  username: 'dora',
  fullName: 'Dora',
  role: 'CASHIER',
  privileges: {
    Privileges.invoiceView,
    Privileges.invoiceManage,
    Privileges.documentView,
    Privileges.documentManage,
  },
  customerId: null,
);

/// Someone who manages tasks, and so could start, finish, cancel and edit one today.
const _tasker = CurrentUser(
  id: 4,
  username: 'tess',
  fullName: 'Tess',
  role: 'MANAGER',
  privileges: {Privileges.invoiceView, Privileges.taskView, Privileges.taskManage},
  customerId: null,
);

/// A rule's owner, who could run, switch, delete and edit it today.
const _ruleOwner = CurrentUser(
  id: 6,
  username: 'rhea',
  fullName: 'Rhea Rules',
  role: 'MANAGER',
  privileges: {Privileges.invoiceView, Privileges.automationView, Privileges.automationManage},
  customerId: null,
);

const _ruleJson = {
  'id': 12,
  'name': 'Chase overdue invoices',
  'enabled': true,
  'version': 3,
  'status': 'ACTIVE',
  'owner': {'id': 6, 'name': 'Rhea Rules', 'active': true},
  'trigger': {'kind': 'ENTITY_CREATED', 'entityType': 'INVOICE', 'zone': 'UTC'},
  'regionIds': [],
  'condition': {
    'op': 'AND',
    'children': [
      {'filter': 'overdue:eq:true'},
    ],
  },
  'actions': [],
  'people': <String, String>{},
  'canEdit': true,
  'canRun': true,
  'editTakesOwnership': false,
};

const _invoiceSchema = {
  'entity': 'invoices',
  'columns': [
    {'name': 'overdue', 'label': 'Overdue', 'type': 'BOOLEAN', 'filterable': true, 'operators': ['eq']},
  ],
};

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data, headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows, {String? asOf}) => {
      'content': rows,
      'page': 0,
      'size': 50,
      'totalElements': rows.length,
      'totalPages': 1,
      'asOf': asOf,
    };

const _customerJson = {
  'id': 5,
  'name': 'Acme Ltd',
  'outstanding': 1000.0,
  'creditBalance': 0.0,
  'pocMissing': false,
};

const _invoiceJson = {
  'id': 42,
  'invoiceNumber': 'INV-0042',
  'customerId': 5,
  'customerName': 'Acme Ltd',
  'invoiceDate': '2026-06-01',
  'dueDate': '2026-07-01',
  'total': 1000.0,
  'paidAmount': 0.0,
  'balance': 1000.0,
  'status': 'UNPAID',
  'notes': 'Net 30',
  'items': [],
};

const _paymentJson = {
  'id': 7,
  'customerId': 5,
  'customerName': 'Acme Ltd',
  'amount': 500.0,
  'paidAt': '2026-06-10T10:00:00Z',
  'status': 'ACTIVE',
  'invoices': [],
};

const _promiseJson = {
  'id': 3,
  'customerId': 5,
  'customerName': 'Acme Ltd',
  'amount': 500.0,
  'remainingAmount': 500.0,
  'promisedDate': '2026-07-15',
  'status': 'OPEN',
};

const _disputeJson = {
  'id': 11,
  'customerId': 5,
  'customerName': 'Acme Ltd',
  'openedByUserId': 9,
  'targetType': 'INVOICE',
  'targetId': 42,
  'targetNumber': 'INV-0042',
  'reason': 'Billed twice',
  'status': 'PENDING',
  'createdAt': '2026-06-02T10:00:00Z',
};

/// Until BE-10 the server still flags a document editable on a past date, so the tab must not.
const _documentJson = {
  'id': 21,
  'entityType': 'INVOICE',
  'entityId': 42,
  'filename': 'purchase-order.pdf',
  'contentType': 'application/pdf',
  'sizeBytes': 2048,
  'visibility': 'INTERNAL',
  'canDownload': true,
  'canEdit': true,
  'canDelete': true,
};

/// Until the backend clears them on a past date, the flags would still allow everything.
const _taskJson = {
  'id': 41,
  'title': 'Chase INV-0042',
  'status': 'OPEN',
  'dueDate': '2026-07-01',
  'entityType': 'INVOICE',
  'entityId': 42,
  'entityLabel': 'INV-0042',
  'customerId': 5,
  'customerName': 'Acme Ltd',
  'assignees': [],
  'version': 2,
  'canEdit': true,
  'canChangeStatus': true,
};

/// One page of §7.4's list: what it shows, what it fetches, and the controls that change things —
/// each on offer today, and none of them on a past date.
class _Site {
  final String name;
  final Widget page;
  final CurrentUser user;
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<Finder Function()> controls;

  /// The by-id read whose 404 on a past date means "not there that day", and the noun it uses.
  final String? detailRoute;
  final String? noun;

  const _Site(this.name, this.page,
      {required this.user, required this.routes, required this.controls, this.detailRoute, this.noun});
}

Finder _text(String text) => find.text(text);

final _sites = [
  _Site('customer detail', const CustomerDetailScreen(id: 5),
      user: _manager,
      routes: {
        'GET /api/customers/5': _answer(_customerJson),
        'GET /api/disputes': _answer(_page(const [])),
        'GET /api/promises': _answer(_page(const [])),
      },
      controls: [() => _text('Delete customer'), () => _text('Save changes'), () => _text('Send email')],
      detailRoute: 'GET /api/customers/5',
      noun: 'customer'),
  _Site('invoice detail', const InvoiceDetailScreen(id: 42),
      user: _manager,
      routes: {
        'GET /api/invoices/42': _answer(_invoiceJson),
        'GET /api/disputes': _answer(_page(const [])),
        'GET /api/promises': _answer(_page(const [])),
      },
      controls: [() => _text('Save changes'), () => _text('Send email'), () => find.byType(TextField)],
      detailRoute: 'GET /api/invoices/42',
      noun: 'invoice'),
  _Site('payment detail', const PaymentDetailScreen(id: 7),
      user: _manager,
      routes: {
        'GET /api/payments/7': _answer(_paymentJson),
        'GET /api/disputes': _answer(_page(const [])),
        'GET /api/promises': _answer(_page(const [])),
      },
      controls: [() => _text('Save changes'), () => _text('Send email'), () => find.byType(TextField)],
      detailRoute: 'GET /api/payments/7',
      noun: 'payment'),
  _Site('promise detail', const PromiseDetailScreen(id: 3),
      user: _manager,
      routes: {'GET /api/promises/3': _answer(_promiseJson)},
      controls: [
        () => _text('Edit'),
        () => _text('Override status'),
        () => _text('Cancel'),
        () => _text('Send email'),
      ],
      detailRoute: 'GET /api/promises/3',
      noun: 'promise'),
  _Site('promises tab', const PromisesTab(customerId: 5, customerName: 'Acme Ltd'),
      user: _manager,
      routes: {
        'GET /api/promises': _answer(_page(const [_promiseJson])),
      },
      controls: [() => _text('Raise promise'), () => _text('Edit'), () => _text('Cancel')]),
  _Site('disputes tab',
      const DisputesTab(targetType: DisputeTargetType.INVOICE, targetId: 42, targetLabel: 'INV-0042'),
      user: _customer,
      routes: {'GET /api/disputes': _answer(_page(const [_disputeJson]))},
      controls: [() => _text('Raise dispute')]),
  _Site('documents tab',
      const DocumentsTab(type: DocumentEntityType.invoice, entityId: 42, entityLabel: 'INV-0042'),
      user: _documentKeeper,
      routes: {'GET /api/documents': _answer(_page(const [_documentJson]))},
      controls: [
        () => _text('Upload'),
        () => find.byTooltip('Edit'),
        () => find.byTooltip('Remove'),
      ]),
  _Site('task detail', const TaskDetailScreen(id: 41),
      user: _tasker,
      routes: {'GET /api/tasks/41': _answer(_taskJson)},
      controls: [
        () => _text('Edit'),
        () => _text('Start'),
        () => _text('Mark done'),
        () => _text('Cancel task'),
      ],
      detailRoute: 'GET /api/tasks/41',
      noun: 'task'),
  _Site('tasks tab', const TasksTab(entityType: TaskEntityType.INVOICE, entityId: 42),
      user: _tasker,
      routes: {'GET /api/tasks': _answer(_page(const [_taskJson]))},
      controls: [
        () => _text('New task'),
        () => find.byTooltip('Start'),
        () => find.byTooltip('Mark done'),
        () => find.byTooltip('Cancel task'),
      ]),
  // Save stays on the page, but disabled; everything that acts or edits goes.
  _Site('rule editor', const RuleEditorScreen(id: 12),
      user: _ruleOwner,
      routes: {
        'GET /api/automation/rules/12': _answer(_ruleJson),
        'GET /api/table-schemas/invoices': _answer(_invoiceSchema),
      },
      controls: [
        () => _text('Run now'),
        () => _text('Disable'),
        () => _text('Delete'),
        () => _text('Add condition'),
      ],
      detailRoute: 'GET /api/automation/rules/12',
      noun: 'rule'),
  _Site('dispute detail', const DisputeDetailScreen(id: 11),
      user: _manager,
      routes: {'GET /api/disputes/11': _answer(_disputeJson)},
      controls: [() => _text('Resolve'), () => _text('Send email')],
      detailRoute: 'GET /api/disputes/11',
      noun: 'dispute'),
];

Future<(FakeBackend, ProviderContainer)> _pump(
  WidgetTester tester, {
  required Widget page,
  required CurrentUser user,
  required Map<String, Object? Function(RequestOptions)> routes,
  DateTime? asOf,
  List<RouteBase> extraRoutes = const [],
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes));
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user),
    // The "My tasks" badge polls on a timer for anyone who sees tasks; these pages are the point.
    myTasksCountProvider.overrideWith((ref) => Stream.value(0)),
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/page', builder: (_, __) => page),
        ...extraRoutes,
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

void main() {
  group('on a past date nothing can be changed', () {
    for (final site in _sites) {
      testWidgets('${site.name}: its controls are there today', (tester) async {
        await _pump(tester, page: site.page, user: site.user, routes: site.routes);
        expect(tester.takeException(), isNull);

        for (final control in site.controls) {
          expect(control(), findsWidgets, reason: '${control()} on ${site.name} today');
        }
        expect(find.text(_banner), findsNothing);
      });

      testWidgets('${site.name}: none of its controls on 30 Jun 2026, and the banner says why',
          (tester) async {
        final (backend, _) =
            await _pump(tester, page: site.page, user: site.user, routes: site.routes, asOf: _june30);
        expect(tester.takeException(), isNull);

        for (final control in site.controls) {
          expect(control(), findsNothing, reason: '${control()} on ${site.name} as of a past date');
        }
        expect(find.text(_banner), findsOneWidget);
        // What it shows is that day's, asked for as that day's.
        final asked = backend.requests.where((r) => site.routes.containsKey('${r.method} ${r.path}'));
        expect(asked, isNotEmpty);
        expect(asked.map((r) => r.queryParameters['asOf']).toSet(), {'2026-06-30'});
      });

      if (site.detailRoute != null) {
        testWidgets('${site.name}: a record with no version that day did not exist then',
            (tester) async {
          await _pump(tester,
              page: site.page,
              user: site.user,
              routes: {
                ...site.routes,
                site.detailRoute!: (_) => const FakeFailure(404, {'message': 'Not found'}),
              },
              asOf: _june30);

          expect(find.text('This ${site.noun} did not exist on 30 Jun 2026.'), findsOneWidget);
        });

        testWidgets('${site.name}: today, a 404 still reads as missing', (tester) async {
          await _pump(tester, page: site.page, user: site.user, routes: {
            ...site.routes,
            site.detailRoute!: (_) => const FakeFailure(404, {'message': 'Not found'}),
          });

          expect(find.text('That ${site.noun} does not exist.'), findsOneWidget);
        });
      }
    }

    testWidgets('a page the server answered live when asked for a past day says it cannot show it',
        (tester) async {
      await _pump(tester,
          page: const InvoiceDetailScreen(id: 42),
          user: _manager,
          // No X-View-As-Of: an answer that ignored the date.
          routes: {'GET /api/invoices/42': (_) => _invoiceJson},
          asOf: _june30);

      expect(find.textContaining("This screen can't show history"), findsOneWidget);
      expect(find.text('INV-0042'), findsNothing);
    });

    testWidgets('the new-invoice form is read only on a past date, and keeps its draft',
        (tester) async {
      final (backend, container) = await _pump(tester,
          page: const InvoiceFormScreen(), user: _manager, routes: const {});
      expect(tester.takeException(), isNull);
      expect(find.text('Create invoice'), findsOneWidget);
      await tester.enterText(find.widgetWithText(TextField, 'Notes'), 'Deliver by Friday');

      // The chip is in the app bar over this page too.
      container.read(viewScopeProvider.notifier).setAsOf(_june30);
      await tester.pumpAndSettle();

      expect(find.text(_banner), findsOneWidget);
      expect(find.text("New invoices can't be created on a past date."), findsOneWidget);
      expect(find.text('Create invoice'), findsNothing);
      expect(find.byType(TextField), findsNothing);

      await tester.tap(find.text('Back to today'));
      await tester.pumpAndSettle();

      expect(find.text('Create invoice'), findsOneWidget);
      expect(find.text('Deliver by Friday'), findsOneWidget);
      expect(backend.requests.where((r) => r.method == 'POST'), isEmpty);
    });

    testWidgets('fields seeded on a past date are not what today edits from', (tester) async {
      Map<String, dynamic> invoiceNoting(String notes) => {..._invoiceJson, 'notes': notes};
      final (_, container) = await _pump(tester,
          page: const InvoiceDetailScreen(id: 42),
          user: _manager,
          routes: {
            'GET /api/invoices/42': (o) => o.queryParameters['asOf'] == null
                ? invoiceNoting('Today')
                : FakeReply(200, invoiceNoting('Back then'),
                    headers: const {'X-View-As-Of': ['2026-06-30']}),
            'GET /api/disputes': _answer(_page(const [])),
            'GET /api/promises': _answer(_page(const [])),
          },
          asOf: _june30);
      expect(find.text('Back then'), findsOneWidget);

      await tester.tap(find.text('Back to today'));
      await tester.pumpAndSettle();

      expect(container.read(viewScopeProvider).asOf, isNull);
      expect(find.text(_banner), findsNothing);
      final notes = tester.widget<TextField>(find.byType(TextField));
      expect(notes.controller!.text, 'Today');
    });
  });

  group('the dashboard on a past date', () {
    const reader = CurrentUser(
      id: 2,
      username: 'rita',
      fullName: 'Rita',
      role: 'CASHIER',
      privileges: {Privileges.invoiceView, Privileges.invoiceManage, Privileges.customerView},
      customerId: null,
    );

    Map<String, Object? Function(RequestOptions)> routes() => {
          'GET /api/dashboard/billed-by-month': _answer({
            'coverage': 'ALL',
            'months': [
              {'month': '2026-06', 'amount': 1000.0, 'count': 1},
            ],
          }),
          'GET /api/invoices/summary': _answer({'outstanding': 1000.0, 'unpaidCount': 1}),
          'GET /api/dashboard/outstanding-by-age': _answer({
            'coverage': 'ALL',
            'buckets': [
              {'label': '1–30 days', 'fromDays': 1, 'toDays': 30, 'amount': 1000.0, 'count': 1},
            ],
          }),
          'GET /api/dashboard/top-outstanding-customers': _answer({'coverage': 'ALL', 'customers': []}),
        };

    testWidgets('an ageing band opens the invoices due in it, counted back from that day',
        (tester) async {
      String? opened;
      final (backend, _) = await _pump(tester,
          page: const DashboardScreen(),
          user: reader,
          routes: routes(),
          asOf: _june30,
          extraRoutes: [
            GoRoute(
              path: '/invoices',
              builder: (_, state) {
                opened = state.uri.toString();
                return const Text('Invoices list');
              },
            ),
          ]);
      expect(tester.takeException(), isNull);

      expect(find.textContaining('as of 30 Jun 2026'), findsOneWidget);
      expect(find.text('New invoice'), findsNothing);
      expect(backend.sent('GET /api/dashboard/outstanding-by-age').single.queryParameters['asOf'],
          '2026-06-30');

      await tester.tap(find.text('1–30 days'));
      await tester.pumpAndSettle();

      // 1 to 30 days overdue on 30 June: due between 31 May and 29 June.
      expect(Uri.decodeFull(opened!), contains('dueDate:between:2026-05-31,2026-06-29'));
    });

    testWidgets('today it reads as today and offers New invoice', (tester) async {
      await _pump(tester, page: const DashboardScreen(), user: reader, routes: routes());

      expect(find.textContaining('as of today (UTC)'), findsOneWidget);
      expect(find.text('New invoice'), findsOneWidget);
    });
  });

  group('the inbox reader', () {
    test('opens a message as it is today, like the inbox it was opened from', () async {
      late ProviderContainer container;
      final backend = FakeBackend({
        'GET /api/emails/88': (_) => const {'id': 88, 'subject': 'Remittance advice'},
      });
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(_manager),
      ]);
      addTearDown(container.dispose);
      container.read(viewScopeProvider.notifier)
        ..setRegions({2})
        ..setAsOf(_june30);
      container.listen(emailDetailProvider(88), (_, __) {});

      final email = await container.read(emailDetailProvider(88).future);

      expect(email.subject, 'Remittance advice');
      // The regions still go with it; the date does not.
      expect(backend.sent('GET /api/emails/88').single.queryParameters, {'regionIds': '2'});
    });

    test('every other request still carries the date', () async {
      final backend = FakeBackend({'GET /api/emails': (_) => _page(const [])});
      final dio = backend.dioWith(
          [viewScopeInterceptor(() => ViewScope(regionIds: const {2}, asOf: _june30))]);

      await dio.get('/api/emails');

      expect(backend.sent('GET /api/emails').single.queryParameters,
          {'regionIds': '2', 'asOf': '2026-06-30'});
    });
  });

  group('a list that keeps no history', () {
    Future<void> pumpList(WidgetTester tester, {required bool keepsHistory}) async {
      await _pump(tester,
          page: DataTableScaffold<Map<String, dynamic>>(
            entity: 'notifications',
            path: '/api/notifications',
            query: const TableQuery(),
            onQueryChanged: (_) {},
            parse: (json) => json,
            idOf: (row) => row['id'] as int,
            columns: [TableColumnSpec(label: 'Title', cell: (_, row) => Text('${row['title']}'))],
          ),
          user: _manager,
          routes: {
            'GET /api/notifications': (o) => FakeReply(
                  200,
                  _page(const [
                    {'id': 1, 'title': 'Promise broken'},
                  ], asOf: keepsHistory ? '2026-06-30' : null),
                  headers: {
                    'X-View-As-Of': [keepsHistory ? '2026-06-30' : 'live'],
                  },
                ),
            'GET /api/table-schemas/notifications': (_) => {'entity': 'notifications', 'columns': const []},
          },
          asOf: _june30);
    }

    testWidgets('says it is showing current data on a past date', (tester) async {
      await pumpList(tester, keepsHistory: false);

      expect(find.text("Showing current data — history isn't kept for this list"), findsOneWidget);
      expect(find.text('Promise broken'), findsOneWidget);
    });

    testWidgets('a list answered as of the date says nothing of the kind', (tester) async {
      await pumpList(tester, keepsHistory: true);

      expect(find.text("Showing current data — history isn't kept for this list"), findsNothing);
      expect(find.text('Promise broken'), findsOneWidget);
    });
  });
}
