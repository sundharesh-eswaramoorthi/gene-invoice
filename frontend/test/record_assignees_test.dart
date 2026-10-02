import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/disputes/dispute_detail_screen.dart';
import 'package:gene_invoice/features/promises/promise_detail_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/dispute.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _managing = {
  Privileges.disputeView,
  Privileges.disputeManage,
  Privileges.promiseView,
  Privileges.promiseManage,
};

// Rita manages disputes and promises in NORTH; in SOUTH she may only look.
const _north = RegionAccess(
    id: 1, code: 'NORTH', name: 'North', level: 'MANAGE', scope: 'ALL', privileges: _managing);
const _south = RegionAccess(
    id: 2,
    code: 'SOUTH',
    name: 'South',
    level: 'READ',
    scope: 'ALL',
    privileges: {Privileges.disputeView, Privileges.promiseView});

const _rita = CurrentUser(
  id: 3,
  username: 'rita',
  fullName: 'Rita',
  role: 'CASHIER',
  privileges: _managing,
  customerId: null,
  regions: [_north, _south],
);

const _assignees = [
  {
    'token': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
    'label': 'Collection POC (customer)',
    'people': [
      {'userId': 5, 'name': 'Cara', 'primary': true},
    ],
    'unresolved': false,
  },
  {
    'token': {'type': 'USER', 'userId': 7},
    'label': 'Ann Admin',
    'people': [
      {'userId': 7, 'name': 'Ann Admin', 'primary': false},
    ],
    'unresolved': false,
  },
];

Map<String, dynamic> _dispute({
  int regionId = 1,
  String targetType = 'INVOICE',
  bool internal = true,
  List<Object>? assignees = _assignees,
}) =>
    {
      'id': 11,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'openedByUserId': 5,
      'targetType': targetType,
      'targetId': targetType == 'CUSTOMER' ? 5 : 42,
      if (targetType == 'INVOICE') 'targetNumber': 'INV-0042',
      if (targetType == 'CUSTOMER') 'targetSummary': 'Customer Acme Ltd',
      'reason': 'Review INV-0042',
      'proposedChangeJson': '{"action":"none"}',
      'status': 'PENDING',
      'createdAt': '2026-09-02T10:00:00Z',
      'regionId': regionId,
      'canResolve': regionId == 1,
      'internal': internal,
      'origin': 'AUTOMATION',
      'ruleId': 7,
      if (assignees != null) 'assignees': assignees,
    };

Map<String, dynamic> _promise({bool canManage = true}) => {
      'id': 3,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'amount': 500.0,
      'remainingAmount': 500.0,
      'promisedDate': '2026-10-15',
      'status': 'OPEN',
      'regionId': 1,
      'canManage': canManage,
      'assignees': [_assignees.first],
    };

Object? _context(RequestOptions o) => {
      'entityType': o.queryParameters['entityType'],
      'entityId': o.queryParameters['entityId'],
      'delivery': {'configured': true},
      'sender': {
        'restricted': false,
        'self': {'userId': 3, 'name': 'Rita'},
      },
      'roles': [
        {'role': 'SALES_POC', 'label': 'Sales POC', 'level': 'CUSTOMER', 'levelLabel': 'Customer'},
        {
          'role': 'COLLECTION_POC',
          'label': 'Collection POC',
          'level': 'CUSTOMER',
          'levelLabel': 'Customer'
        },
      ],
      'customerEmails': {'available': false, 'addresses': []},
    };

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data, headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Future<(FakeBackend, GoRouter)> _pump(
  WidgetTester tester, {
  required Widget page,
  required Map<String, Object? Function(RequestOptions)> routes,
  DateTime? asOf,
  CurrentUser user = _rita,
}) async {
  tester.view.physicalSize = const Size(1366, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend({
    'GET /api/assignees/context': _context,
    'GET /api/assignees/people': (_) => [
          {'userId': 8, 'name': 'Bala Raman', 'username': 'bala'},
        ],
    ...routes,
  });
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user),
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/page', builder: (_, __) => page),
        GoRoute(path: '/disputes', builder: (_, __) => const Text('disputes list')),
        GoRoute(path: '/automation/:id', builder: (_, s) => Text('rule ${s.pathParameters['id']}')),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return (backend, router);
}

Future<void> _tap(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  group('a dispute a rule raised', () {
    testWidgets('says so, is marked internal, and lists who it is assigned to', (tester) async {
      final (_, router) = await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          routes: {'GET /api/disputes/11': (_) => _dispute()});
      expect(tester.takeException(), isNull);

      expect(find.byKey(const ValueKey('dispute-internal')), findsOneWidget);
      expect(find.text('Collection POC (customer) · Cara'), findsOneWidget);
      expect(find.text('Ann Admin'), findsOneWidget);
      expect(find.text('(none)'), findsNothing);

      // Rita may not see rules, so the chip only says where it came from.
      expect(find.text('Created by rule'), findsOneWidget);
      expect(tester.widget(find.byKey(const ValueKey('dispute-rule'))), isA<Chip>());
      await _tap(tester, find.byKey(const ValueKey('dispute-rule')));
      expect(router.routerDelegate.currentConfiguration.uri.path, '/page');
    });

    testWidgets('links to its rule for someone who may see rules', (tester) async {
      final (_, router) = await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          user: const CurrentUser(
            id: 3,
            username: 'rita',
            fullName: 'Rita',
            role: 'MANAGER',
            privileges: {..._managing, Privileges.automationView},
            customerId: null,
            regions: [_north, _south],
          ),
          routes: {'GET /api/disputes/11': (_) => _dispute()});

      await _tap(tester, find.byKey(const ValueKey('dispute-rule')));
      expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/7');
    });

    testWidgets('Edit puts the whole new list, found among who may see it', (tester) async {
      var saved = false;
      final (backend, _) = await _pump(tester, page: const DisputeDetailScreen(id: 11), routes: {
        'GET /api/disputes/11': (_) => saved
            ? _dispute(assignees: [
                ..._assignees,
                {
                  'token': {'type': 'USER', 'userId': 8},
                  'label': 'Bala Raman',
                  'people': [
                    {'userId': 8, 'name': 'Bala Raman'},
                  ],
                },
              ])
            : _dispute(),
        'PUT /api/disputes/11/assignees': (_) {
          saved = true;
          return _dispute();
        },
      });

      await _tap(tester, find.byKey(const ValueKey('assignees-edit')));
      expect(backend.sent('GET /api/assignees/context').single.queryParameters,
          {'entityType': 'DISPUTE', 'entityId': 11});
      // Those already on it keep the names they came with.
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('record-assignees-field')),
              matching: find.text('Ann Admin')),
          findsOneWidget);

      await _tap(tester, find.byKey(const ValueKey('assignee-add-person')));
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bala Raman'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/assignees/people').last.queryParameters,
          {'entityType': 'DISPUTE', 'entityId': 11});
      await _tap(tester, find.byKey(const ValueKey('record-assignees-save')));

      expect(backend.sent('PUT /api/disputes/11/assignees').single.data, {
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          {'type': 'USER', 'userId': 7},
          {'type': 'USER', 'userId': 8},
        ],
      });
      expect(find.text('Assignees saved'), findsOneWidget);
      expect(backend.sent('GET /api/disputes/11'), hasLength(2));
      expect(find.text('Bala Raman'), findsOneWidget);
    });

    testWidgets('someone who cannot see it is refused under the field, and the dialog stays',
        (tester) async {
      final (backend, _) = await _pump(tester, page: const DisputeDetailScreen(id: 11), routes: {
        'GET /api/disputes/11': (_) => _dispute(),
        'PUT /api/disputes/11/assignees': (_) => const FakeFailure(400, {
              'message': 'Validation failed',
              'fieldErrors': {'assignees': 'Bala Raman cannot see this dispute'},
            }),
      });

      await _tap(tester, find.byKey(const ValueKey('assignees-edit')));
      await _tap(
          tester,
          find.descendant(
              of: find.byKey(const ValueKey('record-assignees-field')),
              matching: find.byKey(const ValueKey('assignee-add-role-SALES_POC-CUSTOMER'))));
      await _tap(tester, find.byKey(const ValueKey('record-assignees-save')));

      expect(backend.sent('PUT /api/disputes/11/assignees'), hasLength(1));
      expect(find.text('Bala Raman cannot see this dispute'), findsOneWidget);
      expect(find.byKey(const ValueKey('record-assignees-save')), findsOneWidget);
    });

    testWidgets('nobody without DISPUTE_MANAGE in its region may change them', (tester) async {
      await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          routes: {'GET /api/disputes/11': (_) => _dispute(regionId: 2)});
      expect(find.text('Collection POC (customer) · Cara'), findsOneWidget);
      expect(find.byKey(const ValueKey('assignees-edit')), findsNothing);
    });

    testWidgets('nor on a past date', (tester) async {
      await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          routes: {'GET /api/disputes/11': _answer(_dispute())},
          asOf: DateTime(2026, 6, 30));
      expect(find.text('Collection POC (customer) · Cara'), findsOneWidget);
      expect(find.byKey(const ValueKey('assignees-edit')), findsNothing);
    });

    testWidgets('a server that sends no assignees shows none, and offers no Edit', (tester) async {
      await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          routes: {'GET /api/disputes/11': (_) => _dispute(assignees: null, internal: false)});
      expect(find.text('Assigned to'), findsNothing);
      expect(find.byKey(const ValueKey('assignees-edit')), findsNothing);
      expect(find.byKey(const ValueKey('dispute-internal')), findsNothing);
    });

    testWidgets('on a customer it is acknowledged or closed without change', (tester) async {
      final (backend, router) =
          await _pump(tester, page: const DisputeDetailScreen(id: 11), routes: {
        'GET /api/disputes/11': (_) => _dispute(targetType: 'CUSTOMER'),
        'POST /api/disputes/11/approve': (_) => _dispute(targetType: 'CUSTOMER'),
      });

      expect(find.text('Customer Acme Ltd'), findsWidgets);
      await _tap(tester, find.text('Resolve'));
      expect(find.byKey(const ValueKey('dispute-acknowledge')), findsOneWidget);
      expect(find.text('Close without change'), findsOneWidget);
      // Nothing about a customer changes, so there is no change to edit.
      expect(find.text('Applied change (JSON)'), findsNothing);
      expect(find.text('Approve'), findsNothing);

      await _tap(tester, find.byKey(const ValueKey('dispute-acknowledge')));
      expect(backend.sent('POST /api/disputes/11/approve').single.data,
          {'appliedChangeJson': '{\n  "action": "none"\n}'});
      expect(router.routerDelegate.currentConfiguration.uri.path, '/disputes');
    });
  });

  group("a promise's co-assignees", () {
    testWidgets('sit beside its Collection POC, and are changed from its page', (tester) async {
      final (backend, _) = await _pump(tester, page: const PromiseDetailScreen(id: 3), routes: {
        'GET /api/promises/3': (_) => _promise(),
        'PUT /api/promises/3/assignees': (_) => _promise(),
      });

      expect(find.text('Also assigned to'), findsOneWidget);
      expect(find.text('Collection POC (customer) · Cara'), findsOneWidget);

      await _tap(tester, find.byKey(const ValueKey('assignees-edit')));
      expect(backend.sent('GET /api/assignees/context').single.queryParameters,
          {'entityType': 'PROMISE', 'entityId': 3});
      await _tap(
          tester,
          find.descendant(
              of: find.byKey(const ValueKey('record-assignees-field')),
              matching: find.byKey(const ValueKey('assignee-add-role-SALES_POC-CUSTOMER'))));
      await _tap(tester, find.byKey(const ValueKey('record-assignees-save')));

      expect(backend.sent('PUT /api/promises/3/assignees').single.data, {
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'CUSTOMER'},
        ],
      });
      expect(backend.sent('GET /api/promises/3'), hasLength(2));
    });

    testWidgets('an unchanged list sends nothing', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const PromiseDetailScreen(id: 3),
          routes: {'GET /api/promises/3': (_) => _promise()});
      await _tap(tester, find.byKey(const ValueKey('assignees-edit')));
      await _tap(tester, find.byKey(const ValueKey('record-assignees-save')));
      expect(backend.sent('PUT /api/promises/3/assignees'), isEmpty);
    });

    testWidgets('canManage: false leaves them to be read', (tester) async {
      await _pump(tester,
          page: const PromiseDetailScreen(id: 3),
          routes: {'GET /api/promises/3': (_) => _promise(canManage: false)});
      expect(find.text('Collection POC (customer) · Cara'), findsOneWidget);
      expect(find.byKey(const ValueKey('assignees-edit')), findsNothing);
    });
  });

  test('people raise disputes on invoices and payments, never on a customer', () {
    expect(disputeTargetsRaisedByPeople, [DisputeTargetType.INVOICE, DisputeTargetType.PAYMENT]);
    expect(parseDisputeTarget('CUSTOMER'), DisputeTargetType.CUSTOMER);
    expect(disputeTargetLabel(DisputeTargetType.CUSTOMER), 'Customer');
  });
}
