import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/rules_screen.dart';
import 'package:gene_invoice/features/automation/run_detail_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _manager = {Privileges.invoiceView, Privileges.automationView, Privileges.automationManage};
const _admin = {..._manager, Privileges.automationAdmin};

const _south = RegionAccess(id: 2, code: 'SOUTH', name: 'South', level: 'MANAGE', scope: 'ALL');
const _north = RegionAccess(id: 1, code: 'NORTH', name: 'North', level: 'MANAGE', scope: 'ALL');

CurrentUser _user({int id = 5, Set<String> privileges = _manager}) => CurrentUser(
      id: id,
      username: 'cara',
      fullName: 'Cara Collections',
      role: 'MANAGER',
      privileges: privileges,
      customerId: null,
      regions: const [_north, _south],
    );

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data, headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Map<String, dynamic> _row({
  int id = 12,
  String name = 'Chase overdue invoices',
  bool enabled = true,
  String status = 'ACTIVE',
  String? suspendedReason,
  int ownerId = 5,
  String ownerName = 'Cara Collections',
  String entityType = 'INVOICE',
}) =>
    {
      'id': id,
      'name': name,
      'enabled': enabled,
      'status': status,
      'suspendedReason': suspendedReason,
      'triggerKind': 'SCHEDULE_DAILY',
      'entityType': entityType,
      'owner': {'id': ownerId, 'name': ownerName, 'active': true},
      'regionIds': [2],
      'lastRunStatus': 'SUCCEEDED',
      'lastRunAt': '2026-09-26T03:30:00Z',
      'nextRunAt': '2026-09-27T03:30:00Z',
      'createdAt': '2026-09-01T09:00:00Z',
      'updatedAt': '2026-09-20T09:00:00Z',
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'regionIds': const [1, 2],
      'lockedFilters': const [],
    };

Map<String, dynamic> _ruleDto(
        {int id = 12, bool enabled = true, String status = 'ACTIVE', int ownerId = 5}) =>
    {
      'id': id,
      'name': 'Chase overdue invoices',
      'enabled': enabled,
      'status': status,
      'version': 4,
      'owner': {'id': ownerId, 'name': 'Cara Collections', 'active': true},
      'trigger': {
        'kind': 'SCHEDULE_DAILY',
        'entityType': 'INVOICE',
        'time': '09:00',
        'zone': '+05:30'
      },
      'regionIds': [2],
      'condition': null,
      'actions': const [],
      'canEdit': true,
      'canRun': true,
      'editTakesOwnership': false,
    };

const _health = {
  'consumerEnabled': true,
  'pending': 17,
  'processing': 1,
  'retrying': 2,
  'dead': 1,
  'oldestPendingSeconds': 3,
  'deadEvents': [
    {
      'id': 9,
      'kind': 'UPDATED',
      'entityType': 'INVOICE',
      'entityId': 7,
      'ruleId': null,
      'attempts': 5,
      'lastError': 'Connection reset',
      'updatedAt': '2026-09-26T08:00:00Z',
    },
  ],
};

/// Refuses what the server refuses: a sort on status at all, and on a past date the sorts and
/// filters its history cannot answer (TableSchemas.AUTOMATION_RULES, RuleAsOfLists).
Object? Function(RequestOptions) _rulesAnswer(Object? data) => (o) {
      final sort = '${o.queryParameters['sort'] ?? ''}'.split(',').first;
      if (sort == 'status') return const FakeFailure(400, {'message': 'Column is not sortable: status'});
      if (o.queryParameters['asOf'] != null) {
        if (const {'lastRunAt', 'nextRunAt', 'lastRunStatus'}.contains(sort)) {
          return const FakeFailure(400, {'message': "This sort isn't available for past dates"});
        }
        final filters = (o.queryParameters['filter'] as List?) ?? const [];
        if (filters.any((f) => '$f'.startsWith('last') || '$f'.startsWith('next'))) {
          return const FakeFailure(400, {'message': "This filter isn't available for past dates"});
        }
      }
      return _answer(data)(o);
    };

/// The rules list's header labelled [label], as the table builds it.
DataColumn _header(WidgetTester tester, String label) =>
    tester.widget<DataTable>(find.byType(DataTable).first).columns.firstWhere((c) {
      final w = c.label;
      final text = w is Text ? w : (w is ConstrainedBox ? w.child : null);
      return text is Text && text.data == label;
    });

Map<String, Object? Function(RequestOptions)> _routes(List<Map<String, dynamic>> rows) => {
      'GET /api/automation/rules': _rulesAnswer(_page(rows)),
      'GET /api/automation/rules/summary':
          _answer({'active': 4, 'disabled': 1, 'suspended': 1, 'failedRuns24h': 2}),
      'GET /api/table-schemas/automation-rules': (_) => automationRulesSchema,
      'GET /api/automation/health': (_) => _health,
    };

Future<(FakeBackend, ProviderContainer, GoRouter)> _pump(
  WidgetTester tester, {
  required Map<String, Object? Function(RequestOptions)> routes,
  CurrentUser? user,
  DateTime? asOf,
  Set<int>? regions,
  String location = '/automation',
}) async {
  tester.view.physicalSize = const Size(1500, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes));
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user ?? _user()),
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);
  if (regions != null) container.read(viewScopeProvider.notifier).setRegions(regions);

  final router = GoRouter(initialLocation: location, routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        // As the app's router builds it (router.dart).
        GoRoute(
            path: '/automation',
            builder: (_, s) =>
                RulesScreen(query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'name,asc'))),
        GoRoute(path: '/automation/new', builder: (_, __) => const Text('new rule page')),
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
  return (backend, container, router);
}

Map<String, dynamic> _json(Object? data) => (data as Map).cast<String, dynamic>();

Future<void> _takeOver(WidgetTester tester) async {
  expect(find.text('Take over this rule?'), findsOneWidget);
  expect(find.textContaining("This makes you the rule's owner; it will act with your access"),
      findsOneWidget);
  await tester.tap(find.byKey(const ValueKey('take-ownership')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('lists the rules in view: status, what starts them, who they run as, and the tiles',
      (tester) async {
    final (backend, _, _) = await _pump(tester,
        routes: _routes([
          _row(),
          _row(id: 13, name: 'Weekly review', enabled: false, status: 'DISABLED'),
          _row(
              id: 14,
              name: 'Broken rule',
              status: 'SUSPENDED',
              suspendedReason: 'OWNER_LACKS_RIGHTS'),
        ]),
        regions: {2});

    expect(backend.sent('GET /api/automation/rules').single.queryParameters,
        {'page': 0, 'size': 20, 'sort': 'name,asc', 'regionIds': '2'});
    expect(find.text('Chase overdue invoices'), findsOneWidget);
    expect(find.text('Active'), findsWidgets);
    expect(find.text('Disabled'), findsWidgets);
    expect(find.text('Suspended'), findsWidgets);
    expect(find.byTooltip('Its owner no longer holds the access it needs'), findsOneWidget);
    expect(find.text('Invoice · Every day'), findsNWidgets(3));
    expect(find.text('Cara Collections'), findsNWidgets(3));
    // Two regions held: which each rule works in.
    expect(find.text('SOUTH'), findsWidgets);
    expect(find.text('Failed runs (24 h)'), findsOneWidget);
    expect(find.text('New rule'), findsOneWidget);
    // A manager is no administrator: the consumer's health is not theirs to ask about.
    expect(find.byKey(const ValueKey('automation-health')), findsNothing);
    expect(backend.sent('GET /api/automation/health'), isEmpty);
  });

  group('Run now', () {
    testWidgets('over everything that matches, now: an empty body, and the queued run is shown',
        (tester) async {
      final (backend, _, router) = await _pump(tester, routes: {
        ..._routes([_row()]),
        'POST /api/automation/rules/12/run': (_) => const FakeReply(202, {
              'runId': 88,
              'eventId': 9031,
              'status': 'QUEUED',
              'scheduledFor': '2026-09-26T10:00:00Z'
            }),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      expect(find.text('Run "Chase overdue invoices"'), findsOneWidget);
      expect(find.text('In: All your regions'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();

      expect(_json(backend.sent('POST /api/automation/rules/12/run').single.data),
          <String, dynamic>{});
      expect(find.text('Run #88'), findsOneWidget);
      expect(find.text('Queued'), findsOneWidget);
      // Queuing a run changes the list's last and next runs.
      expect(backend.sent('GET /api/automation/rules').length, 2);

      await tester.tap(find.byKey(const ValueKey('run-now-open-rule')));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/12');
      // Straight to the rule's runs, where the one just queued is at the top.
      expect(router.routerDelegate.currentConfiguration.uri.queryParameters['tab'], 'runs');
    });

    testWidgets('Open run opens the run just queued, over the page it was asked from',
        (tester) async {
      final (backend, _, router) = await _pump(tester, routes: {
        ..._routes([_row()]),
        'POST /api/automation/rules/12/run': (_) => const FakeReply(
            202, {'runId': 88, 'eventId': 9031, 'status': 'QUEUED', 'scheduledFor': null}),
        'GET /api/automation/runs/88': (_) => {
              'id': 88,
              'ruleId': 12,
              'ruleVersion': 3,
              'eventId': 9031,
              'triggerType': 'MANUAL',
              'status': 'SUCCEEDED',
              'matched': 2,
              'done': 2,
              'skipped': 0,
              'failed': 0,
              'createdAt': '2026-09-26T10:00:00Z',
            },
        'GET /api/automation/runs/88/effects': (_) =>
            {'content': [], 'page': 0, 'size': 50, 'totalElements': 0, 'totalPages': 0},
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('run-now-open-run')));
      await tester.pumpAndSettle();

      expect(find.text('Run "Chase overdue invoices"'), findsNothing);
      expect(backend.sent('GET /api/automation/runs/88'), isNotEmpty);
      expect(find.byType(RunDetailDialog), findsOneWidget);
      expect(router.routerDelegate.currentConfiguration.uri.path, '/automation');
    });

    testWidgets('on chosen records, at a chosen time: recordIds and runAt, in the regions in view',
        (tester) async {
      final (backend, _, _) = await _pump(tester, regions: {
        2
      }, routes: {
        ..._routes([_row()]),
        'GET /api/invoices': (_) => _page([
              {'id': 77, 'invoiceNumber': 'INV-0077', 'customerName': 'Acme Ltd', 'total': 1000.0},
              {'id': 78, 'invoiceNumber': 'INV-0078', 'customerName': 'Acme Ltd', 'total': 500.0},
            ]),
        'POST /api/automation/rules/12/run': (_) =>
            const FakeReply(202, {'runId': 89, 'eventId': 9032, 'status': 'QUEUED'}),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      expect(find.text('In: SOUTH'), findsOneWidget);

      await tester.tap(find.text('Chosen records'));
      await tester.pumpAndSettle();
      // Nothing picked yet is refused here, before anything is sent.
      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();
      expect(find.text('Pick at least one record'), findsOneWidget);
      expect(backend.sent('POST /api/automation/rules/12/run'), isEmpty);

      await tester.tap(find.byKey(const ValueKey('run-record-picker')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('INV-0077'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('run-record-77')), findsOneWidget);

      await tester.tap(find.text('At…'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('run-at-pick')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      final before = DateTime.now();
      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();

      final sent = backend.sent('POST /api/automation/rules/12/run').single;
      final body = _json(sent.data);
      expect(body.keys.toSet(), {'recordIds', 'runAt'});
      expect(body['recordIds'], [77]);
      final runAt = DateTime.parse(body['runAt'] as String);
      expect(runAt.isUtc, isTrue);
      expect(runAt.isAfter(before.subtract(const Duration(minutes: 1))), isTrue);
      // The run is kept to the regions the list showed (§5.10.9).
      expect(sent.queryParameters['regionIds'], '2');
      expect(find.text('Run #89'), findsOneWidget);
    });

    testWidgets('the server\'s reasons for refusing show under their fields', (tester) async {
      final (_, _, _) = await _pump(tester, routes: {
        ..._routes([_row()]),
        'POST /api/automation/rules/12/run': (_) => const FakeFailure(400, {
              'message': 'Validation failed',
              'fieldErrors': {'runAt': 'must be at most 90 days ahead'},
            }),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('At…'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('run-at-pick')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();

      expect(find.text('must be at most 90 days ahead'), findsOneWidget);
      expect(find.text('Run #88'), findsNothing);
    });
  });

  group('Run now wording (AUT-R1-4)', () {
    testWidgets('an invoice rule offers "Every invoice that matches"', (tester) async {
      await _pump(tester, routes: _routes([_row()]));
      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      expect(find.text('Every invoice that matches'), findsOneWidget);
      expect(find.text('Every invoices that matches'), findsNothing);
    });

    testWidgets('a customer rule offers "Every customer that matches"', (tester) async {
      await _pump(tester, routes: _routes([_row(entityType: 'CUSTOMER')]));
      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      expect(find.text('Every customer that matches'), findsOneWidget);
      expect(find.text('Every customers that matches'), findsNothing);
    });
  });

  group('someone else\'s rule', () {
    final others = [_row(ownerId: 7, ownerName: 'Olga Owner')];

    testWidgets('an administrator\'s Run now asks first, then sends confirmOwnershipChange',
        (tester) async {
      final (backend, _, _) = await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes(others),
        'POST /api/automation/rules/12/run': (_) =>
            const FakeReply(202, {'runId': 88, 'eventId': 9031, 'status': 'QUEUED'}),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      expect(find.textContaining('Olga Owner owns this rule'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();
      await _takeOver(tester);

      expect(_json(backend.sent('POST /api/automation/rules/12/run').single.data),
          {'confirmOwnershipChange': true});
      expect(find.text('Run #88'), findsOneWidget);
    });

    testWidgets('saying no to taking it over sends nothing', (tester) async {
      final (backend, _, _) =
          await _pump(tester, user: _user(privileges: _admin), routes: _routes(others));

      await tester.tap(find.byKey(const ValueKey('rule-12-run')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('run-now-submit')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel').last);
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/automation/rules/12/run'), isEmpty);
    });

    testWidgets('an administrator\'s Enable asks, then sends confirmOwnershipChange',
        (tester) async {
      final (backend, _, _) = await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes([_row(ownerId: 7, ownerName: 'Olga Owner', enabled: false, status: 'DISABLED')]),
        'POST /api/automation/rules/12/enable': (_) => _ruleDto(ownerId: 5),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-enable')));
      await tester.pumpAndSettle();
      await _takeOver(tester);

      expect(_json(backend.sent('POST /api/automation/rules/12/enable').single.data),
          {'confirmOwnershipChange': true});
      expect(find.text('"Chase overdue invoices" is on'), findsOneWidget);
    });

    testWidgets('an administrator\'s Resume asks, then sends replay and confirmOwnershipChange',
        (tester) async {
      final (backend, _, _) = await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes([
          _row(
              ownerId: 7,
              ownerName: 'Olga Owner',
              status: 'SUSPENDED',
              suspendedReason: 'OWNER_INACTIVE'),
        ]),
        'POST /api/automation/rules/12/resume': (_) => {..._ruleDto(), 'skippedWhileSuspended': 12},
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-resume')));
      await tester.pumpAndSettle();
      expect(find.text('It was suspended: Its owner is no longer active.'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('resume-replay')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('resume-confirm')));
      await tester.pumpAndSettle();
      await _takeOver(tester);

      expect(_json(backend.sent('POST /api/automation/rules/12/resume').single.data),
          {'replay': true, 'confirmOwnershipChange': true});
      expect(find.textContaining('will act on the 12 changes made while it was suspended'),
          findsOneWidget);
    });

    testWidgets('an administrator may switch it off and delete it without taking it over',
        (tester) async {
      final (backend, _, _) = await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes(others),
        'POST /api/automation/rules/12/disable': (_) =>
            _ruleDto(enabled: false, status: 'DISABLED', ownerId: 7),
        'DELETE /api/automation/rules/12': (_) => const FakeReply(204, null),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-disable')));
      await tester.pumpAndSettle();
      expect(find.text('Take over this rule?'), findsNothing);
      expect(backend.sent('POST /api/automation/rules/12/disable'), hasLength(1));
      // Let its note go, so the next one shows.
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('rule-12-delete')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('rule-delete-confirm')));
      await tester.pumpAndSettle();
      expect(backend.sent('DELETE /api/automation/rules/12'), hasLength(1));
      expect(find.text('"Chase overdue invoices" deleted'), findsOneWidget);
    });

    testWidgets('a manager who is not an administrator can only open it', (tester) async {
      await _pump(tester, routes: _routes(others));

      expect(find.byKey(const ValueKey('rule-12-run')), findsNothing);
      expect(find.byKey(const ValueKey('rule-12-disable')), findsNothing);
      expect(find.byKey(const ValueKey('rule-12-delete')), findsNothing);
      expect(find.byTooltip('Open'), findsOneWidget);
    });
  });

  group('the owner', () {
    testWidgets('switches a rule off and on: no ownership asked, and the list is read again',
        (tester) async {
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes(
            [_row(), _row(id: 13, name: 'Weekly review', enabled: false, status: 'DISABLED')]),
        'POST /api/automation/rules/12/disable': (_) =>
            _ruleDto(enabled: false, status: 'DISABLED'),
        'POST /api/automation/rules/13/enable': (_) => _ruleDto(id: 13),
      });

      expect(find.byKey(const ValueKey('rule-12-enable')), findsNothing);
      expect(find.byKey(const ValueKey('rule-13-disable')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('rule-12-disable')));
      await tester.pumpAndSettle();
      expect(find.text('"Chase overdue invoices" is off'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('rule-13-enable')));
      await tester.pumpAndSettle();
      expect(find.text('Take over this rule?'), findsNothing);
      expect(_json(backend.sent('POST /api/automation/rules/13/enable').single.data),
          <String, dynamic>{});
      expect(backend.sent('GET /api/automation/rules').length, 3);
    });

    testWidgets('resumes without replay: it starts again from now', (tester) async {
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes([_row(status: 'SUSPENDED', suspendedReason: 'RATE_LIMITED')]),
        'POST /api/automation/rules/12/resume': (_) => {..._ruleDto(), 'skippedWhileSuspended': 3},
      });

      // A suspended rule is resumed, not run.
      expect(find.byKey(const ValueKey('rule-12-run')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('rule-12-resume')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('resume-confirm')));
      await tester.pumpAndSettle();

      expect(_json(backend.sent('POST /api/automation/rules/12/resume').single.data),
          {'replay': false});
      expect(find.textContaining('the 3 changes made while it was suspended will not be acted on'),
          findsOneWidget);
    });

    testWidgets('the server asking about ownership after all is asked, then sent again',
        (tester) async {
      var calls = 0;
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes([_row(enabled: false, status: 'DISABLED')]),
        'POST /api/automation/rules/12/enable': (_) => ++calls == 1
            ? const FakeFailure(400, {
                'message': 'Validation failed',
                'fieldErrors': {'confirmOwnershipChange': 'Someone else owns this rule now'},
              })
            : _ruleDto(),
      });

      await tester.tap(find.byKey(const ValueKey('rule-12-enable')));
      await tester.pumpAndSettle();
      await _takeOver(tester);

      final sent = backend.sent('POST /api/automation/rules/12/enable');
      expect(sent.map((r) => _json(r.data)).toList(), [
        <String, dynamic>{},
        {'confirmOwnershipChange': true},
      ]);
    });
  });

  group('the consumer\'s health', () {
    testWidgets('an administrator sees what is waiting and requeues a dead event', (tester) async {
      final (backend, _, _) = await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes([_row()]),
        'POST /api/automation/events/9/requeue': (_) => const FakeReply(202, null),
      });

      expect(find.byKey(const ValueKey('automation-health')), findsOneWidget);
      expect(find.text('Rules are being run'), findsOneWidget);
      expect(find.textContaining('17 waiting'), findsOneWidget);
      expect(find.textContaining('1 given up'), findsOneWidget);
      expect(find.text('Connection reset'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('requeue-9')));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/automation/events/9/requeue'), hasLength(1));
      expect(find.text('Event #9 is queued again'), findsOneWidget);
      // And asked again, to show it gone.
      expect(backend.sent('GET /api/automation/health'), hasLength(2));
    });

    Map<String, dynamic> deadEvent(int id, String kind, int? ruleId) => {
          'id': id,
          'kind': kind,
          'entityType': kind == 'MANUAL' ? null : 'INVOICE',
          'entityId': kind == 'MANUAL' ? null : 7,
          'ruleId': ruleId,
          'attempts': 5,
          'lastError': 'Connection reset',
          'updatedAt': '2026-09-26T08:00:00Z',
        };
    Map<String, dynamic> healthWith(List<Map<String, dynamic>> dead) =>
        {..._health, 'dead': dead.length, 'deadEvents': dead};

    testWidgets('a dead MANUAL event opens its rule\'s runs instead of being requeued (RR-2)',
        (tester) async {
      final (backend, _, router) = await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes([_row()]),
        'GET /api/automation/health': (_) =>
            healthWith([deadEvent(10, 'MANUAL', 7), deadEvent(11, 'CREATED', null)]),
        'POST /api/automation/events/11/requeue': (_) => const FakeReply(202, null),
      });

      final manual = find.byKey(const ValueKey('dead-event-10'));
      expect(find.descendant(of: manual, matching: find.text('Open runs')), findsOneWidget);
      expect(find.descendant(of: manual, matching: find.text('Requeue')), findsNothing);
      expect(find.byKey(const ValueKey('requeue-10')), findsNothing);
      // Any other kind is still requeued as before.
      expect(find.byKey(const ValueKey('requeue-11')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('requeue-11')));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/automation/events/11/requeue'), hasLength(1));

      await tester.tap(find.byKey(const ValueKey('open-runs-10')));
      await tester.pumpAndSettle();
      final uri = router.routerDelegate.currentConfiguration.uri;
      expect(uri.path, '/automation/7');
      expect(uri.queryParameters['tab'], 'runs');
      expect(backend.requests.where((r) => r.path.contains('/events/10/')), isEmpty);
    });

    testWidgets('a dead MANUAL event with no rule offers nothing to press', (tester) async {
      await _pump(tester, user: _user(privileges: _admin), routes: {
        ..._routes([_row()]),
        'GET /api/automation/health': (_) => healthWith([deadEvent(10, 'MANUAL', null)]),
      });

      expect(find.byKey(const ValueKey('dead-event-10')), findsOneWidget);
      expect(find.byKey(const ValueKey('requeue-10')), findsNothing);
      expect(find.byKey(const ValueKey('open-runs-10')), findsNothing);
      expect(find.text('Requeue'), findsNothing);
    });

    testWidgets('on a past date a dead MANUAL event offers nothing either', (tester) async {
      await _pump(tester,
          user: _user(privileges: _admin),
          routes: {
            ..._routes([_row()]),
            'GET /api/automation/health': (_) => healthWith([deadEvent(10, 'MANUAL', 7)]),
          },
          asOf: DateTime(2026, 6, 30));

      expect(find.byKey(const ValueKey('dead-event-10')), findsOneWidget);
      expect(find.byKey(const ValueKey('open-runs-10')), findsNothing);
      expect(find.byKey(const ValueKey('requeue-10')), findsNothing);
    });

    testWidgets('on a past date it is still shown, but nothing is requeued', (tester) async {
      await _pump(tester,
          user: _user(privileges: _admin), routes: _routes([_row()]), asOf: DateTime(2026, 6, 30));

      expect(find.byKey(const ValueKey('automation-health')), findsOneWidget);
      expect(find.byKey(const ValueKey('requeue-9')), findsNothing);
    });
  });

  testWidgets('on a past date the list is that day\'s, and nothing changes a rule', (tester) async {
    final (backend, _, _) = await _pump(tester,
        user: _user(privileges: _admin), routes: _routes([_row()]), asOf: DateTime(2026, 6, 30));

    expect(backend.sent('GET /api/automation/rules').single.queryParameters['asOf'], '2026-06-30');
    expect(find.text('Chase overdue invoices'), findsOneWidget);
    expect(find.text('New rule'), findsNothing);
    for (final action in ['run', 'enable', 'disable', 'resume', 'delete']) {
      expect(find.byKey(ValueKey('rule-12-$action')), findsNothing, reason: action);
    }
  });

  group('sorts and filters the server answers', () {
    List<String?> sorts(FakeBackend backend) => backend
        .sent('GET /api/automation/rules')
        .map((r) => r.queryParameters['sort'] as String?)
        .toList();

    /// The columns the filter editor offers, by name.
    Future<Set<String>> filterColumns(WidgetTester tester) async {
      await tester.tap(find.text('Add filter'));
      await tester.pumpAndSettle();
      final names = tester
          .widgetList<DropdownMenuItem<ColumnDef>>(
              find.byType(DropdownMenuItem<ColumnDef>, skipOffstage: false))
          .map((i) => i.value!.name)
          .toSet();
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      return names;
    }

    testWidgets('the Status header does not sort: the server refuses sort=status', (tester) async {
      final (backend, _, router) = await _pump(tester, routes: _routes([_row()]));
      expect(_header(tester, 'Status').onSort, isNull);
      // Live, the run columns sort.
      expect(_header(tester, 'Last run').onSort, isNotNull);
      expect(_header(tester, 'Next run').onSort, isNotNull);

      await tester.tap(find.text('Status'));
      await tester.pumpAndSettle();
      expect(sorts(backend).where((s) => s?.startsWith('status') ?? false), isEmpty);
      expect(router.routerDelegate.currentConfiguration.uri.queryParameters['sort'], isNot('status,asc'));

      // A column the schema publishes as sortable still sorts.
      await tester.tap(find.text('Rule'));
      await tester.pumpAndSettle();
      expect(sorts(backend).last, 'name,desc');
    });

    testWidgets('on a past date a last-run sort in the URL goes out, and stays, as name,asc',
        (tester) async {
      final (backend, _, router) = await _pump(tester,
          routes: _routes([_row()]),
          asOf: DateTime(2026, 6, 30),
          location: '/automation?sort=lastRunAt,desc'
              '&f=lastRunStatus:eq:FAILED&f=status:eq:ACTIVE');

      expect(sorts(backend), everyElement('name,asc'));
      final sent = backend.sent('GET /api/automation/rules').last.queryParameters;
      expect(sent['asOf'], '2026-06-30');
      // A filter the past cannot answer goes; one it can stays.
      expect(sent['filter'], ['status:eq:ACTIVE']);
      final uri = router.routerDelegate.currentConfiguration.uri;
      expect(uri.queryParameters['sort'], 'name,asc');
      expect(uri.queryParametersAll['f'], ['status:eq:ACTIVE']);
      expect(find.text('Chase overdue invoices'), findsOneWidget);
      expect(find.text('Request failed'), findsNothing);

      // Last run and Next run do not sort on a past date; Starts still does.
      expect(_header(tester, 'Last run').onSort, isNull);
      expect(_header(tester, 'Next run').onSort, isNull);
      expect(_header(tester, 'Status').onSort, isNull);
      expect(_header(tester, 'Starts').onSort, isNotNull);
      await tester.tap(find.text('Starts'));
      await tester.pumpAndSettle();
      expect(sorts(backend).last, 'triggerKind,asc');
    });

    testWidgets('moving to a past date while sorted by Next run goes back to name,asc',
        (tester) async {
      final (backend, container, router) = await _pump(tester,
          routes: _routes([_row()]), location: '/automation?sort=nextRunAt,asc');
      expect(sorts(backend).last, 'nextRunAt,asc');

      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();
      // The list shown asks the past date by name. (The page asked before the date changed may be
      // asked once more as the scope moves, but nothing shows its answer.)
      final last = backend.sent('GET /api/automation/rules').last.queryParameters;
      expect(last['asOf'], '2026-06-30');
      expect(last['sort'], 'name,asc');
      expect(router.routerDelegate.currentConfiguration.uri.queryParameters['sort'], 'name,asc');
      expect(find.text('Request failed'), findsNothing);
      expect(find.text('Chase overdue invoices'), findsOneWidget);
    });

    testWidgets('live, the filter editor offers Last run and Next run', (tester) async {
      await _pump(tester, routes: _routes([_row()]));
      expect(await filterColumns(tester), containsAll(['lastRunStatus', 'lastRunAt', 'nextRunAt']));
    });

    testWidgets('on a past date the filter editor offers only what the past answers: no Last run '
        'or Next run', (tester) async {
      await _pump(tester, routes: _routes([_row()]), asOf: DateTime(2026, 6, 30));
      expect(await filterColumns(tester), {
        'name',
        'enabled',
        'status',
        'suspendedReason',
        'triggerKind',
        'entityType',
        'ownerUserId',
        'regionIds',
      });
    });
  });

  testWidgets('the Automation entry is in the sidebar, after Approvals and before Regions',
      (tester) async {
    final labels = navEntries.map((e) => e.label).toList();
    expect(labels.indexOf('Automation'), labels.indexOf('Approvals') + 1);
    expect(labels.indexOf('Regions'), labels.indexOf('Automation') + 1);
    expect(
        navPrivilegesFor('/automation'), [Privileges.automationView, Privileges.automationManage]);
    expect(navEntries.firstWhere((e) => e.path == '/automation').hideForCustomer, isTrue);

    await _pump(tester, routes: _routes([_row()]));
    expect(find.text('Automation'), findsWidgets);
  });
}
