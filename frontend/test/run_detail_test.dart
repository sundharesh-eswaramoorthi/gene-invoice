import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/automation_models.dart';
import 'package:gene_invoice/features/automation/rule_editor_screen.dart';
import 'package:gene_invoice/features/automation/run_detail_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'automation_condition_builder_test.dart' show invoiceSchema;
import 'support/fake_backend.dart';

const _manager = {
  Privileges.invoiceView,
  Privileges.automationView,
  Privileges.automationManage,
};

CurrentUser _user({int id = 5, Set<String> privileges = _manager}) => CurrentUser(
      id: id,
      username: 'cara',
      fullName: 'Cara Collections',
      role: 'MANAGER',
      privileges: privileges,
      customerId: null,
    );

const _taskKey = '5b0c7f0e-1111-4222-8333-444455556666';
const _promiseKey = 'c3d17f0e-1111-4222-8333-444455556666';

Map<String, dynamic> _rule({bool canRun = true, bool editTakesOwnership = false}) => {
      'id': 12,
      'name': 'Chase overdue invoices',
      'enabled': true,
      'version': 3,
      'status': 'ACTIVE',
      'owner': {'id': 5, 'name': 'Cara Collections', 'active': true},
      'trigger': {'kind': 'SCHEDULE_DAILY', 'entityType': 'INVOICE', 'time': '09:00', 'zone': 'UTC'},
      'regionIds': [],
      'condition': null,
      'actions': [
        {'key': _taskKey, 'type': 'CREATE_TASK', 'title': 'Chase {{Invoice.Number}}'},
        {'key': _promiseKey, 'type': 'CREATE_PROMISE', 'notes': 'Auto'},
      ],
      'canEdit': canRun,
      'canRun': canRun,
      'editTakesOwnership': editTakesOwnership,
    };

Map<String, dynamic> _run({
  int id = 88,
  String status = 'PARTIAL',
  String triggerType = 'SCHEDULED',
  bool partial = false,
  Map<String, dynamic>? entity,
  String? statusAt,
}) =>
    {
      'id': id,
      'ruleId': 12,
      'ruleVersion': 3,
      'eventId': 9031,
      'triggerType': triggerType,
      'status': status,
      'statusAt': statusAt,
      'scheduledFor': '2026-09-26T03:30:00Z',
      'lateBySeconds': 0,
      'entity': entity,
      'requestedBy': null,
      'matched': 4,
      'done': 3,
      'skipped': 0,
      'failed': 1,
      'partial': partial,
      'truncated': false,
      'message': null,
      'createdAt': '2026-09-26T03:30:00Z',
      'startedAt': '2026-09-26T03:30:01Z',
      'finishedAt': '2026-09-26T03:30:05Z',
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 50, 'totalElements': rows.length, 'totalPages': 1};

final _effects = _page([
  {
    'id': 901,
    'actionKey': _taskKey,
    'actionType': 'CREATE_TASK',
    'target': {
      'type': 'INVOICE',
      'id': 77,
      'label': 'Invoice INV-0077',
      'link': '/invoices/77',
      'hidden': false
    },
    'status': 'DONE',
    'result': {'type': 'TASK', 'id': 41, 'link': '/tasks/41'},
    'message': null,
    'warnings': ['Pat cannot see this invoice, so was not assigned'],
    'createdAt': '2026-09-26T03:30:02Z',
  },
  // Someone else's record: its kind, and nothing that names it.
  {
    'id': 902,
    'actionKey': _taskKey,
    'actionType': 'CREATE_TASK',
    'target': {'type': 'INVOICE', 'hidden': true},
    'status': 'DONE',
    'result': null,
    'message': null,
    'warnings': null,
    'createdAt': '2026-09-26T03:30:03Z',
  },
  {
    'id': 903,
    'actionKey': 'no-longer-in-the-rule',
    'actionType': 'SEND_EMAIL',
    'target': {
      'type': 'INVOICE',
      'id': 78,
      'label': 'Invoice INV-0078',
      'link': '/invoices/78',
      'hidden': false
    },
    'status': 'FAILED',
    'result': null,
    'message': 'Nobody holds Sales POC on INV-0078',
    'warnings': [],
    'createdAt': '2026-09-26T03:30:04Z',
  },
  {
    'id': 904,
    'actionKey': _promiseKey,
    'actionType': 'CREATE_PROMISE',
    'target': {
      'type': 'INVOICE',
      'id': 79,
      'label': 'Invoice INV-0079',
      'link': '/invoices/79',
      'hidden': false
    },
    'status': 'DONE',
    'result': {'type': 'PENDING_CHANGE', 'id': 7, 'link': '/approvals/7'},
    'message': null,
    'warnings': [],
    'createdAt': '2026-09-26T03:30:05Z',
  },
]);

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? Function() data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data(), headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Future<(FakeBackend, GoRouter)> _pump(
  WidgetTester tester, {
  required Map<String, Object? Function(RequestOptions)> routes,
  Map<String, dynamic>? rule,
  CurrentUser? user,
  String initialLocation = '/runs',
  DateTime? asOf,
}) async {
  tester.view.physicalSize = const Size(1400, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend({
    'GET /api/table-schemas/invoices': (_) => invoiceSchema,
    'GET /api/automation/rules/12': _answer(() => rule ?? _rule()),
    ...routes,
  });
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user ?? _user()),
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: initialLocation, routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(
          path: '/runs',
          builder: (_, __) => Builder(
            builder: (context) => Center(
              child: TextButton(
                onPressed: () => showRunDetailDialog(context,
                    runId: 88, rule: Rule.fromJson(rule ?? _rule())),
                child: const Text('open run'),
              ),
            ),
          ),
        ),
        GoRoute(
            path: '/automation/:id',
            builder: (_, s) => RuleEditorScreen(
                id: int.parse(s.pathParameters['id']!),
                initialTab: s.uri.queryParameters['tab'])),
        GoRoute(path: '/invoices/:id', builder: (_, s) => Text('invoice ${s.pathParameters['id']}')),
        GoRoute(path: '/tasks/:id', builder: (_, s) => Text('task ${s.pathParameters['id']}')),
        GoRoute(
            path: '/approvals/:id', builder: (_, s) => Text('approval ${s.pathParameters['id']}')),
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

Future<void> _open(WidgetTester tester) async {
  await tester.tap(find.text('open run'));
  await tester.pumpAndSettle();
}

Finder _effect(int id) => find.byKey(ValueKey('effect-$id'));

void main() {
  group('a run, opened', () {
    testWidgets('says what set it off and how it went, then what it did record by record',
        (tester) async {
      await _pump(tester, routes: {
        'GET /api/automation/runs/88': _answer(() => _run()),
        'GET /api/automation/runs/88/effects': _answer(() => _effects),
      });
      await _open(tester);

      expect(find.text('Run #88'), findsOneWidget);
      expect(find.text('Partly done'), findsOneWidget);
      expect(find.text('Its schedule'), findsOneWidget);
      expect(find.text('4 matched · 3 done · 0 skipped · 1 failed'), findsOneWidget);

      // Named as the rule names its actions; one the rule no longer has, by its kind.
      expect(
          find.descendant(
              of: _effect(901), matching: find.text('Create a task: Chase {{Invoice.Number}}')),
          findsOneWidget);
      expect(find.descendant(of: _effect(901), matching: find.text('Invoice INV-0077')),
          findsOneWidget);
      expect(find.descendant(of: _effect(901), matching: find.text('Task #41')), findsOneWidget);
      expect(
          find.descendant(
              of: _effect(901),
              matching: find.text('Pat cannot see this invoice, so was not assigned')),
          findsOneWidget);
      expect(find.descendant(of: _effect(903), matching: find.text('Send an email')),
          findsOneWidget);
      expect(
          find.descendant(
              of: _effect(903), matching: find.text('Nobody holds Sales POC on INV-0078')),
          findsOneWidget);
      expect(find.descendant(of: _effect(903), matching: find.text('Failed')), findsOneWidget);
      // A promise held for approval is the pending change it became.
      expect(find.descendant(of: _effect(904), matching: find.text('Approval #7 (waiting)')),
          findsOneWidget);
    });

    testWidgets('its links leave the dialog for the record, and what it made', (tester) async {
      final (_, router) = await _pump(tester, routes: {
        'GET /api/automation/runs/88': _answer(() => _run()),
        'GET /api/automation/runs/88/effects': _answer(() => _effects),
      });
      await _open(tester);
      await tester.tap(find.byKey(const ValueKey('effect-901-result')));
      await tester.pumpAndSettle();
      expect(find.text('Run #88'), findsNothing);
      expect(router.routerDelegate.currentConfiguration.uri.path, '/tasks/41');

      router.go('/runs');
      await tester.pumpAndSettle();
      await _open(tester);
      await tester.tap(find.byKey(const ValueKey('effect-901-target')));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/invoices/77');

      router.go('/runs');
      await tester.pumpAndSettle();
      await _open(tester);
      await tester.tap(find.byKey(const ValueKey('effect-904-result')));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/approvals/7');
    });

    testWidgets('a record the viewer cannot see is greyed, with no name, link or result',
        (tester) async {
      await _pump(tester, routes: {
        'GET /api/automation/runs/88': _answer(() => _run(
            triggerType: 'CREATED', entity: {'type': 'INVOICE', 'hidden': true}, partial: true)),
        'GET /api/automation/runs/88/effects': _answer(() => _effects),
      });
      await _open(tester);

      final hidden = tester.widget<ListTile>(_effect(902));
      expect(hidden.enabled, isFalse);
      expect(hidden.onTap, isNull);
      expect(find.descendant(of: _effect(902), matching: find.text('An invoice you cannot see')),
          findsOneWidget);
      final words = [
        for (final t in tester.widgetList<Text>(
            find.descendant(of: _effect(902), matching: find.byType(Text))))
          t.data ?? '',
      ];
      expect(words.where((w) => w.contains('INV-') || w.contains('#')), isEmpty);
      expect(find.byKey(const ValueKey('effect-902-target')), findsNothing);
      expect(find.byKey(const ValueKey('effect-902-result')), findsNothing);

      // The run's own record, likewise.
      final entity = tester.widget<Text>(find.byKey(const ValueKey('run-entity')));
      expect(entity.data, 'An invoice you cannot see');
      expect(entity.style?.color, isNotNull);
      // And its counts are this viewer's alone.
      expect(find.byKey(const ValueKey('run-partial')), findsOneWidget);
      expect(find.text('Counts only records you can see'), findsOneWidget);
    });

    testWidgets('counts the viewer sees in full carry no note', (tester) async {
      await _pump(tester, routes: {
        'GET /api/automation/runs/88': _answer(() => _run(partial: false)),
        'GET /api/automation/runs/88/effects': _answer(() => _page(const [])),
      });
      await _open(tester);
      expect(find.byKey(const ValueKey('run-partial')), findsNothing);
      expect(find.text('Nothing yet.'), findsOneWidget);
    });

    testWidgets('Retry posts /retry for a failed run, then shows the run it queued',
        (tester) async {
      final (backend, _) = await _pump(tester, routes: {
        'GET /api/automation/runs/88': _answer(() => _run(status: 'FAILED')),
        'GET /api/automation/runs/88/effects': _answer(() => _effects),
        'POST /api/automation/runs/88/retry': (_) =>
            const FakeReply(202, {'runId': 90, 'eventId': 9040, 'status': 'QUEUED'}),
        'GET /api/automation/runs/90': _answer(() => _run(id: 90, status: 'SUCCEEDED')),
        'GET /api/automation/runs/90/effects': _answer(() => _page(const [])),
      });
      await _open(tester);

      await tester.tap(find.byKey(const ValueKey('run-retry')));
      await tester.pumpAndSettle();

      final sent = backend.sent('POST /api/automation/runs/88/retry').single;
      expect(sent.data, <String, dynamic>{});
      expect(find.text('Run #90 is queued'), findsOneWidget);
      expect(find.text('Run #90'), findsOneWidget);
      expect(backend.sent('GET /api/automation/runs/90'), isNotEmpty);
      // A run that succeeded is not retried.
      expect(find.byKey(const ValueKey('run-retry')), findsNothing);
    });

    testWidgets('an administrator retrying someone else\'s run by hand is asked first',
        (tester) async {
      final (backend, _) = await _pump(tester,
          user: _user(id: 9, privileges: {..._manager, Privileges.automationAdmin}),
          rule: _rule(canRun: false, editTakesOwnership: true),
          routes: {
            'GET /api/automation/runs/88': _answer(() => _run(status: 'FAILED', triggerType: 'MANUAL')),
            'GET /api/automation/runs/88/effects': _answer(() => _page(const [])),
            'POST /api/automation/runs/88/retry': (_) =>
                const FakeReply(202, {'runId': 91, 'eventId': 9041, 'status': 'QUEUED'}),
            'GET /api/automation/runs/91': _answer(() => _run(id: 91, status: 'QUEUED')),
            'GET /api/automation/runs/91/effects': _answer(() => _page(const [])),
          });
      await _open(tester);

      await tester.tap(find.byKey(const ValueKey('run-retry')));
      await tester.pumpAndSettle();
      expect(find.text('Take over this rule?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('take-ownership')));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/automation/runs/88/retry').single.data,
          {'confirmOwnershipChange': true});
      await tester.tap(find.byKey(const ValueKey('run-close')));
      await tester.pumpAndSettle();
    });

    for (final (name, user, rule, asOf) in [
      ('someone who may not run the rule', _user(id: 9), _rule(canRun: false), null),
      ('a past date', _user(), _rule(), DateTime(2026, 6, 30)),
    ]) {
      testWidgets('no Retry for $name', (tester) async {
        await _pump(tester, user: user, rule: rule, asOf: asOf, routes: {
          'GET /api/automation/runs/88': _answer(() => _run(status: 'FAILED')),
          'GET /api/automation/runs/88/effects': _answer(() => _page(const [])),
        });
        await _open(tester);
        expect(find.text('Run #88'), findsOneWidget);
        expect(find.byKey(const ValueKey('run-retry')), findsNothing);
      });
    }

    testWidgets('a run still going is asked about every two seconds until it is done',
        (tester) async {
      var asked = 0;
      final (backend, _) = await _pump(tester, routes: {
        'GET /api/automation/runs/88': _answer(() => ++asked < 3
            ? _run(status: asked == 1 ? 'QUEUED' : 'RUNNING')
            : _run(status: 'SUCCEEDED')),
        'GET /api/automation/runs/88/effects': _answer(() => _page(const [])),
      });
      await _open(tester);
      expect(find.text('Queued'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      expect(find.text('Running'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      expect(find.text('Succeeded'), findsOneWidget);

      await tester.pump(const Duration(seconds: 6));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/automation/runs/88'), hasLength(3));
      expect(backend.sent('GET /api/automation/runs/88/effects'), hasLength(3));
    });
  });

  group("a rule's runs", () {
    Map<String, Object? Function(RequestOptions)> routes(List<Map<String, dynamic>> runs) => {
          'GET /api/table-schemas/automation-runs': (_) =>
              {'entity': 'automation-runs', 'columns': []},
          'GET /api/automation/rules/12/runs': _answer(() => {
                'content': runs,
                'page': 0,
                'size': 20,
                'totalElements': runs.length,
                'totalPages': 1,
                'asOf': null,
              }),
          'GET /api/audit': _answer(() => const []),
        };

    testWidgets('lists them newest first, notes counts that are the viewer\'s own, and opens one',
        (tester) async {
      final (backend, _) = await _pump(tester,
          initialLocation: '/automation/12?tab=runs',
          routes: {
            ...routes([
              _run(partial: true),
              _run(
                  id: 87,
                  status: 'SUCCEEDED',
                  triggerType: 'CREATED',
                  entity: {'type': 'INVOICE', 'hidden': true}),
            ]),
            'GET /api/automation/runs/88': _answer(() => _run(partial: true)),
            'GET /api/automation/runs/88/effects': _answer(() => _effects),
          });

      expect(backend.sent('GET /api/automation/rules/12/runs').single.queryParameters,
          {'page': 0, 'size': 20, 'sort': 'createdAt,desc'});
      expect(find.text('#88'), findsOneWidget);
      expect(find.text('#87'), findsOneWidget);
      expect(find.byKey(const ValueKey('runs-partial')), findsOneWidget);
      expect(find.text('An invoice you cannot see'), findsOneWidget);

      await tester.tap(find.text('#88'));
      await tester.pumpAndSettle();
      expect(find.text('Run #88'), findsOneWidget);
      expect(find.text('Task #41'), findsOneWidget);
    });

    testWidgets('on a past date each shows how it stood then, beside how it stands now',
        (tester) async {
      final (backend, _) = await _pump(tester,
          initialLocation: '/automation/12?tab=runs',
          asOf: DateTime(2026, 6, 30),
          routes: routes([_run(status: 'SUCCEEDED', statusAt: 'QUEUED')]));

      expect(backend.sent('GET /api/automation/rules/12/runs').single.queryParameters['asOf'],
          '2026-06-30');
      expect(find.text('Queued'), findsOneWidget);
      expect(find.text('now Succeeded'), findsOneWidget);
    });

    testWidgets('a run in flight keeps the list asking, and stops once none is', (tester) async {
      var asked = 0;
      final (backend, _) = await _pump(tester,
          initialLocation: '/automation/12?tab=runs',
          routes: {
            ...routes(const []),
            'GET /api/automation/rules/12/runs': _answer(() {
              asked++;
              return _page([_run(status: asked < 2 ? 'RUNNING' : 'SUCCEEDED')]);
            }),
          });
      expect(find.text('Running'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      expect(find.text('Succeeded'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/automation/rules/12/runs'), hasLength(2));
    });
  });
}
