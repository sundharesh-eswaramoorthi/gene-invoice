import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/core/unsaved_changes.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/automation_models.dart';
import 'package:gene_invoice/features/automation/placeholder_button.dart';
import 'package:gene_invoice/features/automation/rule_editor_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'automation_condition_builder_test.dart' show invoiceSchema;
import 'support/fake_backend.dart';

const _manager = {
  Privileges.invoiceView,
  Privileges.auditView,
  Privileges.automationView,
  Privileges.automationManage,
};
const _admin = {..._manager, Privileges.automationAdmin};

/// What a rule's actions need in a region, beside seeing its records (§5.10.7).
const _acting = {
  Privileges.invoiceView,
  Privileges.customerView,
  Privileges.taskManage,
  Privileges.promiseManage,
  Privileges.disputeManage,
  Privileges.emailSend,
};

const _north = RegionAccess(
    id: 1, code: 'NORTH', name: 'North', level: 'MANAGE', scope: 'ALL', privileges: _acting);
const _south = RegionAccess(
    id: 2, code: 'SOUTH', name: 'South', level: 'MANAGE', scope: 'ALL', privileges: _acting);

/// Customers can be seen here, invoices cannot: no invoice rule can be restricted to it.
const _west = RegionAccess(
    id: 3,
    code: 'WEST',
    name: 'West',
    level: 'READ',
    scope: 'ALL',
    privileges: {Privileges.customerView});

CurrentUser _user(
        {int id = 5,
        Set<String> privileges = _manager,
        List<RegionAccess> regions = const [_north, _south]}) =>
    CurrentUser(
      id: id,
      username: 'cara',
      fullName: 'Cara Collections',
      role: 'MANAGER',
      privileges: privileges,
      customerId: null,
      regions: regions,
    );

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data, headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

const _taskAction = {
  'key': '5b0c7f0e-1111-4222-8333-444455556666',
  'type': 'CREATE_TASK',
  'repeat': 'EVERY_TIME',
  'title': 'Chase {{Invoice.Number}}',
  'due': {'base': 'TODAY', 'offsetDays': 2},
  'assignees': [
    {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
  ],
  'onlyIfNoOpenTask': true,
};

/// An action the server sent without a key: the editor gives it one, once.
const _emailAction = {
  'type': 'SEND_EMAIL',
  'repeat': 'ONCE_PER_RECORD',
  'subject': 'Invoice {{Invoice.Number}} is overdue',
  'to': [
    {'type': 'CUSTOMER'},
  ],
};

Map<String, dynamic> _rule({
  bool canEdit = true,
  bool canRun = true,
  bool editTakesOwnership = false,
  int? version = 3,
  List<String> warnings = const [],
  String name = 'Chase overdue invoices',
  Map<String, String> people = const {},
}) =>
    {
      'id': 12,
      'name': name,
      'description': null,
      'enabled': true,
      'version': version,
      'status': 'ACTIVE',
      'suspendedReason': null,
      'owner': {'id': 5, 'name': 'Cara Collections', 'active': true},
      'trigger': {
        'kind': 'SCHEDULE_DAILY',
        'entityType': 'INVOICE',
        'time': '09:00',
        'day': null,
        'zone': '+05:30',
        'catchUp': 'ONE',
      },
      'regionIds': [2],
      'condition': {
        'op': 'AND',
        'children': [
          {'filter': 'overdue:eq:true'},
        ],
      },
      'actions': [_taskAction, _emailAction],
      'maxRecordsPerRun': 1000,
      'nextRunAt': '2026-09-27T03:30:00Z',
      'lastRun': {
        'id': 88,
        'status': 'SUCCEEDED',
        'finishedAt': '2026-09-26T03:31:00Z',
        'matched': 14,
        'done': 14,
        'skipped': 0,
        'failed': 0,
      },
      'warnings': warnings,
      'people': people,
      'canEdit': canEdit,
      'editTakesOwnership': editTakesOwnership,
      'canRun': canRun,
    };

const _preview = {
  'entityType': 'INVOICE',
  'asOf': null,
  'asOfNote': null,
  'evaluatedAs': 'CALLER',
  'matchCount': 132,
  'sample': [
    {'id': 77, 'label': 'Invoice INV-0077', 'link': '/invoices/77', 'alreadyDone': []},
    {'hidden': true},
  ],
  'record': null,
};

Map<String, Object? Function(RequestOptions)> _routes({Map<String, dynamic>? rule}) => {
      'GET /api/table-schemas/invoices': (_) => invoiceSchema,
      'GET /api/automation/rules/12': _answer(rule ?? _rule()),
      'POST /api/automation/rules/preview': _answer(_preview),
      'GET /api/audit': _answer(const []),
    };

Future<(FakeBackend, ProviderContainer, GoRouter)> _pump(
  WidgetTester tester, {
  required Map<String, Object? Function(RequestOptions)> routes,
  String initialLocation = '/automation/12',
  CurrentUser? user,
  DateTime? asOf,
  Set<int>? regions,
}) async {
  tester.view.physicalSize = const Size(1400, 2600);
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

  final router = GoRouter(initialLocation: initialLocation, routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/automation', builder: (_, __) => const Text('rules list')),
        // As the app's router builds them (router.dart).
        GoRoute(
            path: '/automation/new',
            builder: (_, __) => const RuleEditorScreen(key: ValueKey('rule-new'))),
        GoRoute(
            path: '/automation/:id',
            builder: (_, s) => RuleEditorScreen(
                  key: ValueKey('rule-${s.pathParameters['id']}'),
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
  return (backend, container, router);
}

Map<String, dynamic> _json(Object? data) => (data as Map).cast<String, dynamic>();

Future<void> _tap(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> _choose(WidgetTester tester, String dropdownKey, String option) async {
  await _tap(tester, find.byKey(ValueKey(dropdownKey)));
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

FilledButton _save(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const ValueKey('rule-save')));

void main() {
  group('a new rule', () {
    testWidgets(
        'posts the whole request, every key in place: trigger, catch-up, regions, condition',
        (tester) async {
      final (backend, _, router) = await _pump(tester, initialLocation: '/automation/new', routes: {
        ..._routes(),
        'POST /api/automation/rules': (_) => _rule(),
      });

      await tester.enterText(find.byKey(const ValueKey('rule-name')), '  Chase overdue invoices ');
      await _choose(tester, 'rule-kind', 'Every week');
      await _choose(tester, 'rule-day', 'Friday');
      await _tap(tester, find.text('Run once for each one missed'));
      await _tap(tester, find.byKey(const ValueKey('rule-region-2')));
      await _tap(tester, find.byKey(const ValueKey('cond-add-c')));
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();

      await _tap(tester, find.byKey(const ValueKey('rule-save')));

      expect(_json(backend.sent('POST /api/automation/rules').single.data), {
        'name': 'Chase overdue invoices',
        'description': null,
        'enabled': true,
        'trigger': {
          'kind': 'SCHEDULE_WEEKLY',
          'entityType': 'INVOICE',
          'time': '09:00',
          'day': 'FRIDAY',
          'zone': deviceZone(),
          'catchUp': 'ALL',
        },
        'regionIds': [2],
        'condition': {
          'op': 'AND',
          'children': [
            {'filter': 'overdue:eq:true'},
          ],
        },
        'actions': <Object>[],
        'maxRecordsPerRun': 1000,
        'version': null,
        'confirmOwnershipChange': false,
      });
      expect(find.text('Rule created'), findsOneWidget);
      expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/12');
    });

    testWidgets(
        '"Only when run by hand" is MANUAL: no time, day or catch-up, and no condition is null',
        (tester) async {
      final (backend, _, _) = await _pump(tester, initialLocation: '/automation/new', routes: {
        ..._routes(),
        'POST /api/automation/rules': (_) => _rule(),
      });

      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'By hand');
      await _choose(tester, 'rule-kind', 'Only when run by hand');
      expect(find.textContaining('only when someone presses Run now'), findsOneWidget);
      await _tap(tester, find.byKey(const ValueKey('rule-enabled')));
      await _tap(tester, find.byKey(const ValueKey('rule-save')));

      final body = _json(backend.sent('POST /api/automation/rules').single.data);
      expect(body['trigger'], {
        'kind': 'MANUAL',
        'entityType': 'INVOICE',
        'time': null,
        'day': null,
        'zone': deviceZone(),
        'catchUp': null,
      });
      expect(body['enabled'], isFalse);
      expect(body['condition'], isNull);
      expect(body['regionIds'], <Object>[]);
    });

    testWidgets('only regions where the kind of record can be seen are offered', (tester) async {
      await _pump(tester,
          initialLocation: '/automation/new',
          user: _user(regions: const [_north, _south, _west]),
          routes: {..._routes(), 'GET /api/table-schemas/customers': (_) => invoiceSchema});

      expect(find.byKey(const ValueKey('rule-region-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('rule-region-2')), findsOneWidget);
      expect(find.byKey(const ValueKey('rule-region-3')), findsNothing);

      await _tap(
          tester,
          find.descendant(
              of: find.byKey(const ValueKey('rule-entity')), matching: find.text('Customer')));
      expect(find.byKey(const ValueKey('rule-region-3')), findsOneWidget);
    });

    testWidgets('the trigger kind shows the time, the day and the zone it needs, and no more',
        (tester) async {
      await _pump(tester, initialLocation: '/automation/new', routes: _routes());

      // Created, updated: nothing about time.
      expect(find.byKey(const ValueKey('rule-time')), findsNothing);
      expect(find.byKey(const ValueKey('rule-day')), findsNothing);
      expect(find.byKey(const ValueKey('rule-zone')), findsNothing);

      await _choose(tester, 'rule-kind', 'Every day');
      expect(find.byKey(const ValueKey('rule-time')), findsOneWidget);
      expect(find.byKey(const ValueKey('rule-day')), findsNothing);
      expect(find.byKey(const ValueKey('rule-zone')), findsOneWidget);
      expect(find.byKey(const ValueKey('rule-catch-up')), findsOneWidget);
      // The zone starts as this device's own offset (or UTC).
      final zone = deviceZone();
      expect(find.text(zone == 'UTC' ? 'UTC' : '$zone (this device)'), findsWidgets);

      await _choose(tester, 'rule-kind', 'Every week');
      expect(find.byKey(const ValueKey('rule-time')), findsOneWidget);
      expect(find.byKey(const ValueKey('rule-day')), findsOneWidget);
      expect(find.text('Monday'), findsOneWidget);

      await _choose(tester, 'rule-kind', 'When one is updated');
      expect(find.byKey(const ValueKey('rule-time')), findsNothing);
      expect(find.byKey(const ValueKey('rule-catch-up')), findsNothing);
    });

    testWidgets('a name is asked for before anything is sent', (tester) async {
      final (backend, _, _) =
          await _pump(tester, initialLocation: '/automation/new', routes: _routes());

      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      expect(find.text('Give the rule a name'), findsOneWidget);
      expect(backend.sent('POST /api/automation/rules'), isEmpty);
    });

    testWidgets('the server\'s reasons show under the sections they are about', (tester) async {
      await _pump(tester, initialLocation: '/automation/new', routes: {
        ..._routes(),
        'POST /api/automation/rules': (_) => const FakeFailure(400, {
              'message': 'Validation failed',
              'fieldErrors': {
                'name': 'A rule with this name already exists',
                'condition.children[0].filter': 'Overdue cannot be used here',
                'actions': 'Add between 1 and 5 actions',
                'trigger.zone': 'Unknown time zone',
              },
            }),
      });

      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Chase');
      await _tap(tester, find.byKey(const ValueKey('rule-save')));

      expect(find.text('A rule with this name already exists'), findsOneWidget);
      expect(find.text('Overdue cannot be used here'), findsOneWidget);
      expect(find.text('Add between 1 and 5 actions'), findsOneWidget);
      expect(find.text('Unknown time zone'), findsOneWidget);
    });

    testWidgets('changing the kind of record asks before dropping the conditions', (tester) async {
      final (backend, _, _) = await _pump(tester, initialLocation: '/automation/new', routes: {
        ..._routes(),
        'GET /api/table-schemas/customers': (_) => {...invoiceSchema, 'entity': 'customers'},
      });

      await _tap(tester, find.byKey(const ValueKey('cond-add-c')));
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();
      expect(find.text('Overdue is true'), findsOneWidget);

      await _tap(tester, find.text('Customer'));
      expect(find.text('Change to customers?'), findsOneWidget);
      await tester.tap(find.text('Cancel').last);
      await tester.pumpAndSettle();
      expect(find.text('Overdue is true'), findsOneWidget);

      await _tap(tester, find.text('Customer'));
      await tester.tap(find.byKey(const ValueKey('rule-entity-confirm')));
      await tester.pumpAndSettle();
      expect(find.text('Overdue is true'), findsNothing);
      expect(find.textContaining('Every customer matches'), findsOneWidget);
      // The customers list's own filters now.
      expect(backend.sent('GET /api/table-schemas/customers'), hasLength(1));
    });

    testWidgets('unsaved edits are guarded by the page\'s one slot', (tester) async {
      final (_, container, _) =
          await _pump(tester, initialLocation: '/automation/new', routes: _routes());

      expect(await container.read(unsavedChangesProvider).mayLeave(), isTrue);
      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Half done');
      await tester.pump();

      final leaving = container.read(unsavedChangesProvider).mayLeave();
      await tester.pumpAndSettle();
      expect(find.text('Discard unsaved changes?'), findsOneWidget);
      await tester.tap(find.text('Keep editing'));
      await tester.pumpAndSettle();
      expect(await leaving, isFalse);
    });
  });

  group('a saved rule', () {
    testWidgets('shows who it runs as, its last run and its warnings, with a History tab',
        (tester) async {
      await _pump(tester,
          routes: _routes(rule: _rule(warnings: const ['Becoming overdue is not an update'])));

      expect(find.text('Chase overdue invoices'), findsWidgets);
      expect(find.text('Runs as Cara Collections'), findsOneWidget);
      expect(find.textContaining('Last run #88: Succeeded'), findsOneWidget);
      expect(find.text('Becoming overdue is not an update'), findsOneWidget);
      expect(find.text('Overdue is true'), findsOneWidget);
      expect(find.text('Create a task: Chase {{Invoice.Number}}'), findsOneWidget);
      expect(find.text('Send an email: Invoice {{Invoice.Number}} is overdue'), findsOneWidget);
      expect(find.text('History'), findsOneWidget);
      // Nothing changed yet: nothing to save.
      expect(_save(tester).onPressed, isNull);
    });

    testWidgets('keeps every action and its key, and gives a keyless one a key it keeps',
        (tester) async {
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes(),
        'PUT /api/automation/rules/12': (_) => _rule(version: 4),
      });

      await _tap(tester, find.byKey(const ValueKey('rule-preview')));
      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Chase overdue invoices!');
      await tester.pump();
      await _tap(tester, find.byKey(const ValueKey('rule-save')));

      final previewed =
          _json(_json(backend.sent('POST /api/automation/rules/preview').single.data)['rule']);
      final put = _json(backend.sent('PUT /api/automation/rules/12').single.data);
      final actions = (put['actions'] as List).cast<Map>();
      expect(actions, hasLength(2));
      expect(actions[0], _taskAction);
      final given = actions[1]['key'] as String;
      expect(
          given,
          matches(
              RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
      expect({...actions[1]}..remove('key'), _emailAction);
      // The same key both times: "once per record" is remembered against it.
      expect(previewed['actions'], put['actions']);

      expect(put, {
        'name': 'Chase overdue invoices!',
        'description': null,
        'enabled': true,
        'trigger': {
          'kind': 'SCHEDULE_DAILY',
          'entityType': 'INVOICE',
          'time': '09:00',
          'day': null,
          'zone': '+05:30',
          'catchUp': 'ONE',
        },
        'regionIds': [2],
        'condition': {
          'op': 'AND',
          'children': [
            {'filter': 'overdue:eq:true'},
          ],
        },
        'actions': put['actions'],
        'maxRecordsPerRun': 1000,
        'version': 3,
        'confirmOwnershipChange': false,
      });
      expect(find.text('Rule saved'), findsOneWidget);
    });

    testWidgets('the server asking about ownership is confirmed, then sent again', (tester) async {
      var calls = 0;
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes(),
        'PUT /api/automation/rules/12': (_) => ++calls == 1
            ? const FakeFailure(400, {
                'message': 'Validation failed',
                'fieldErrors': {
                  'confirmOwnershipChange': 'Saving makes you the owner of this rule'
                },
              })
            : _rule(version: 4),
      });

      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Renamed');
      await tester.pump();
      await _tap(tester, find.byKey(const ValueKey('rule-save')));

      expect(find.text('Take over this rule?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('take-ownership')));
      await tester.pumpAndSettle();

      final sent = backend.sent('PUT /api/automation/rules/12').map((r) => _json(r.data)).toList();
      expect(sent.map((b) => b['confirmOwnershipChange']), [false, true]);
      expect(sent.last['name'], 'Renamed');
      expect(find.text('Rule saved'), findsOneWidget);
    });

    testWidgets('an administrator editing someone else\'s rule is asked before the first save',
        (tester) async {
      final (backend, _, _) = await _pump(tester, user: _user(id: 9, privileges: _admin), routes: {
        ..._routes(rule: _rule(canEdit: false, canRun: false, editTakesOwnership: true)),
        'PUT /api/automation/rules/12': (_) => _rule(version: 4),
      });

      expect(find.textContaining('makes you its owner'), findsOneWidget);
      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Renamed');
      await tester.pump();
      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      expect(find.text('Take over this rule?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('take-ownership')));
      await tester.pumpAndSettle();

      expect(
          _json(backend.sent('PUT /api/automation/rules/12').single.data)['confirmOwnershipChange'],
          isTrue);
    });

    testWidgets('a stale save is explained', (tester) async {
      await _pump(tester, routes: {
        ..._routes(),
        'PUT /api/automation/rules/12': (_) => const FakeFailure(409, {'message': 'Conflict'}),
      });

      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Renamed');
      await tester.pump();
      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      expect(find.textContaining('Someone else changed this rule'), findsOneWidget);
    });

    testWidgets('after a stale save, Undo shows theirs and the next save carries their version',
        (tester) async {
      var theirs = false;
      var puts = 0;
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes(),
        'GET /api/automation/rules/12': (o) =>
            _answer(theirs ? _rule(version: 4, name: 'Their name') : _rule())(o),
        'PUT /api/automation/rules/12': (_) {
          if (puts++ == 0) {
            theirs = true;
            return const FakeFailure(409, {'message': 'Conflict'});
          }
          return _rule(version: 5, name: 'Mine again');
        },
      });

      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Mine');
      await tester.pump();
      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      expect(find.textContaining('Someone else changed this rule'), findsOneWidget);
      // The user's edits stay until they choose to see theirs.
      expect(tester.widget<TextField>(find.byKey(const ValueKey('rule-name'))).controller!.text,
          'Mine');

      await _tap(tester, find.byKey(const ValueKey('rule-undo')));
      expect(tester.widget<TextField>(find.byKey(const ValueKey('rule-name'))).controller!.text,
          'Their name');

      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Mine again');
      await tester.pump();
      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      final put = backend.sent('PUT /api/automation/rules/12');
      expect(put, hasLength(2));
      expect(_json(put.first.data)['version'], 3);
      expect(_json(put.last.data)['version'], 4);
      expect(_json(put.last.data)['name'], 'Mine again');
      expect(find.text('Rule saved'), findsOneWidget);
    });

    testWidgets('a region the editor cannot choose can be taken off, never put back',
        (tester) async {
      final (backend, _, _) =
          await _pump(tester, user: _user(regions: const [_north, _south, _west]), routes: {
        ..._routes(rule: {
          ..._rule(),
          'regionIds': [2, 3]
        }),
        'PUT /api/automation/rules/12': (_) => _rule(),
      });

      final west = find.byKey(const ValueKey('rule-region-3'));
      expect(find.text('WEST'), findsOneWidget);
      expect(tester.widget<FilterChip>(west).selected, isTrue);
      expect(tester.widget<FilterChip>(west).onSelected, isNotNull);

      await _tap(tester, west);
      expect(west, findsNothing);
      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      expect(_json(backend.sent('PUT /api/automation/rules/12').single.data)['regionIds'], [2]);
    });
  });

  group('preview', () {
    testWidgets('an answer goes as soon as the draft, the regions or the day change',
        (tester) async {
      final (_, container, _) = await _pump(tester, routes: _routes());

      await _tap(tester, find.byKey(const ValueKey('rule-preview')));
      expect(find.text('132 invoices match'), findsOneWidget);
      await _tap(tester, find.byKey(const ValueKey('rule-region-1')));
      expect(find.text('132 invoices match'), findsNothing);

      await _tap(tester, find.byKey(const ValueKey('rule-preview')));
      expect(find.text('132 invoices match'), findsOneWidget);
      container.read(viewScopeProvider.notifier).setRegions({2});
      await tester.pumpAndSettle();
      expect(find.text('132 invoices match'), findsNothing);

      await _tap(tester, find.byKey(const ValueKey('rule-preview')));
      expect(find.text('132 invoices match'), findsOneWidget);
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();
      // Today's count never reads as that day's.
      expect(find.textContaining('invoices match'), findsNothing);
    });

    testWidgets('live and all regions: no regionIds or asOf, and no evaluateAs until asked for',
        (tester) async {
      final (backend, _, _) = await _pump(tester, routes: _routes());

      await _tap(tester, find.byKey(const ValueKey('rule-preview')));

      final sent = backend.sent('POST /api/automation/rules/preview').single;
      expect(sent.queryParameters, isEmpty);
      final body = _json(sent.data);
      expect(body.keys.toSet(), {'rule', 'ruleId', 'sampleSize'});
      expect(body['ruleId'], 12);
      expect(body['sampleSize'], 10);
      expect(find.text('132 invoices match'), findsOneWidget);
      expect(find.text('Invoice INV-0077'), findsOneWidget);
      // A record only the owner sees is there, but not named.
      expect(find.text('A record you cannot see'), findsOneWidget);

      await _tap(tester, find.byKey(const ValueKey('preview-as-owner')));
      await _tap(tester, find.byKey(const ValueKey('rule-preview')));
      expect(_json(backend.sent('POST /api/automation/rules/preview').last.data)['evaluateAs'],
          'OWNER');
    });

    testWidgets('with regions and a past date chosen, both go with it, and it answers for that day',
        (tester) async {
      final (backend, _, _) = await _pump(tester,
          routes: {
            ..._routes(rule: {..._rule(canEdit: false, canRun: false), 'version': null}),
            'POST /api/automation/rules/preview': _answer({
              ..._preview,
              'asOf': '2026-06-30',
              'asOfNote': "Actions are shown only for today's view",
            }),
          },
          regions: {2},
          asOf: DateTime(2026, 6, 30));

      await _tap(tester, find.byKey(const ValueKey('rule-preview')));

      final sent = backend.sent('POST /api/automation/rules/preview').single;
      expect(sent.queryParameters, {'regionIds': '2', 'asOf': '2026-06-30'});
      expect(find.text('132 invoices match, on 2026-06-30'), findsOneWidget);
      expect(find.text("Actions are shown only for today's view"), findsOneWidget);
    });

    testWidgets('a single record picked is asked about by id', (tester) async {
      final (backend, _, _) = await _pump(tester, routes: {
        ..._routes(),
        'GET /api/invoices': (_) => {
              'content': [
                {'id': 77, 'invoiceNumber': 'INV-0077', 'customerName': 'Acme Ltd'},
              ],
              'page': 0,
              'size': 20,
              'totalElements': 1,
              'totalPages': 1,
            },
        'POST /api/automation/rules/preview': _answer({
          ..._preview,
          'record': {'id': 77, 'label': 'Invoice INV-0077', 'matches': false, 'actions': []},
        }),
      });

      await _tap(tester, find.byKey(const ValueKey('preview-record')));
      await tester.tap(find.text('INV-0077'));
      await tester.pumpAndSettle();
      await _tap(tester, find.byKey(const ValueKey('rule-preview')));

      expect(_json(backend.sent('POST /api/automation/rules/preview').single.data)['recordId'], 77);
      expect(find.text('Invoice INV-0077 does not match'), findsOneWidget);
    });
  });

  testWidgets(
      'on a past date the rule is that day\'s, and read only: no save, no run; preview works',
      (tester) async {
    final (backend, _, _) = await _pump(tester,
        routes: _routes(
            rule: {..._rule(canEdit: false, canRun: false), 'version': null, 'name': 'Old name'}),
        asOf: DateTime(2026, 6, 30));

    expect(
        backend.sent('GET /api/automation/rules/12').single.queryParameters['asOf'], '2026-06-30');
    expect(find.text('Viewing 30 Jun 2026 (end of day, UTC) — read only'), findsOneWidget);
    expect(find.text('Old name'), findsWidgets);
    expect(_save(tester).onPressed, isNull);
    expect(tester.widget<TextField>(find.byKey(const ValueKey('rule-name'))).enabled, isFalse);
    expect(find.text('Add condition'), findsNothing);
    for (final key in ['rule-run', 'rule-enable', 'rule-disable', 'rule-delete']) {
      final found = find.byKey(ValueKey(key));
      if (found.evaluate().isEmpty) continue;
      expect(
          tester
              .widget<OutlinedButton>(
                  find.descendant(of: found, matching: find.byType(OutlinedButton)))
              .onPressed,
          isNull,
          reason: key);
    }

    await _tap(tester, find.byKey(const ValueKey('rule-preview')));
    expect(backend.sent('POST /api/automation/rules/preview'), hasLength(1));
    expect(find.text('132 invoices match'), findsOneWidget);
  });

  testWidgets('a new rule cannot be started on a past date, and the draft waits for today',
      (tester) async {
    final (backend, container, _) =
        await _pump(tester, initialLocation: '/automation/new', routes: _routes());

    await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Kept');
    container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
    await tester.pumpAndSettle();
    expect(find.text("New rules can't be created on a past date."), findsOneWidget);
    expect(find.byKey(const ValueKey('rule-save')), findsNothing);

    container.read(viewScopeProvider.notifier).setAsOf(null);
    await tester.pumpAndSettle();
    expect(find.text('Kept'), findsOneWidget);
    expect(backend.sent('POST /api/automation/rules'), isEmpty);
  });

  group('the placeholder button', () {
    const catalogue = {
      'entityType': 'INVOICE',
      'groups': [
        {
          'level': 'CUSTOMER',
          'label': 'Customer',
          'items': [
            {'token': '{{Customer.Name}}', 'label': 'Name', 'kind': 'TEXT'},
          ],
        },
        {
          'level': 'RECORD',
          'label': 'Invoice',
          'items': [
            {'token': '{{Invoice.Number}}', 'label': 'Number', 'kind': 'TEXT'},
          ],
        },
      ],
    };

    Future<(FakeBackend, TextEditingController)> pumpField(
        WidgetTester tester, String text, TextSelection at) async {
      final backend = FakeBackend({'GET /api/automation/placeholders': (_) => catalogue});
      final controller = TextEditingController(text: text)..selection = at;
      addTearDown(controller.dispose);
      final focusNode = FocusNode();
      addTearDown(focusNode.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(_user())
        ],
        child: MaterialApp(
          home: Scaffold(
            body: Row(children: [
              Expanded(child: TextField(controller: controller, focusNode: focusNode)),
              PlaceholderButton(
                  entityType: 'INVOICE', controller: controller, focusNode: focusNode),
            ]),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return (backend, controller);
    }

    testWidgets('offers each level\'s placeholders and puts the one picked at the cursor',
        (tester) async {
      final (backend, controller) =
          await pumpField(tester, 'Dear there', const TextSelection.collapsed(offset: 5));

      expect(backend.sent('GET /api/automation/placeholders').single.queryParameters,
          {'entityType': 'INVOICE'});
      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      expect(find.text('Customer'), findsOneWidget);
      expect(find.text('Invoice'), findsOneWidget);
      await tester.tap(find.text('Name'));
      await tester.pumpAndSettle();

      expect(controller.text, 'Dear {{Customer.Name}}there');
      expect(controller.selection, const TextSelection.collapsed(offset: 22));
    });

    testWidgets('replaces the selected text', (tester) async {
      final (_, controller) = await pumpField(
          tester, 'Invoice XXX is due', const TextSelection(baseOffset: 8, extentOffset: 11));

      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Number'));
      await tester.pumpAndSettle();

      expect(controller.text, 'Invoice {{Invoice.Number}} is due');
    });

    testWidgets(
        'goes in at the cursor the field had when the menu opened, even if the field then '
        'selects all of its text (web refocus), and leaves the cursor after it', (tester) async {
      final (_, controller) =
          await pumpField(tester, 'Hello ', const TextSelection.collapsed(offset: 6));

      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      // What the web field does as it gets its focus back while the menu closes.
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 6);
      await tester.tap(find.text('Name'));
      await tester.pumpAndSettle();

      expect(controller.text, 'Hello {{Customer.Name}}');
      expect(controller.selection, const TextSelection.collapsed(offset: 23));
    });

    testWidgets('a select-all landing just after the insert is put back to the cursor',
        (tester) async {
      final (_, controller) =
          await pumpField(tester, 'Hello ', const TextSelection.collapsed(offset: 6));

      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Name'));
      await tester.pumpAndSettle();
      expect(controller.text, 'Hello {{Customer.Name}}');

      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 23);
      await tester.pump();
      expect(controller.text, 'Hello {{Customer.Name}}');
      expect(controller.selection, const TextSelection.collapsed(offset: 23));

      // The web field selects all on each refocus, so a second one is put back as well.
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 23);
      await tester.pump();
      expect(controller.text, 'Hello {{Customer.Name}}');
      expect(controller.selection, const TextSelection.collapsed(offset: 23));

      // Only for that moment: a select-all the user makes afterwards stays.
      await tester.pump(const Duration(seconds: 1));
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 23);
      await tester.pump();
      expect(controller.selection, const TextSelection(baseOffset: 0, extentOffset: 23));
    });

    testWidgets('a select-all after the field has lost its focus again stays', (tester) async {
      final (_, controller) =
          await pumpField(tester, 'Hello ', const TextSelection.collapsed(offset: 6));
      final field = tester.widget<EditableText>(find.byType(EditableText));

      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Name'));
      await tester.pumpAndSettle();
      expect(field.focusNode.hasFocus, isTrue);

      field.focusNode.unfocus();
      await tester.pump();
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 23);
      await tester.pump();
      expect(controller.selection, const TextSelection(baseOffset: 0, extentOffset: 23));
    });

    testWidgets(
        'after a pick the field has its focus back with the caret after the token, though the '
        'press on the button took it away (web mouse)', (tester) async {
      final (_, controller) =
          await pumpField(tester, 'Hello ', const TextSelection.collapsed(offset: 6));
      final field = tester.widget<EditableText>(find.byType(EditableText));
      expect(field.focusNode.hasFocus, isFalse);

      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Name'));
      await tester.pumpAndSettle();

      expect(field.focusNode.hasFocus, isTrue);
      expect(controller.text, 'Hello {{Customer.Name}}');
      expect(controller.selection, const TextSelection.collapsed(offset: 23));
      tester.testTextInput.updateEditingValue(const TextEditingValue(
          text: 'Hello {{Customer.Name}}!', selection: TextSelection.collapsed(offset: 24)));
      await tester.pump();
      expect(controller.text, 'Hello {{Customer.Name}}!');
    });

    testWidgets('closing the menu without a pick gives the field its focus and cursor back',
        (tester) async {
      final (_, controller) =
          await pumpField(tester, 'Dear there', const TextSelection.collapsed(offset: 5));
      final field = tester.widget<EditableText>(find.byType(EditableText));

      await tester.tap(find.byTooltip('Insert placeholder'));
      await tester.pumpAndSettle();
      // What the web field does as it gets its focus back while the menu closes.
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 10);
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();

      expect(find.text('Name'), findsNothing);
      expect(controller.text, 'Dear there');
      expect(field.focusNode.hasFocus, isTrue);
      expect(controller.selection, const TextSelection.collapsed(offset: 5));
    });

    test('goes in at the selection it is given rather than the field\'s own', () {
      final controller = TextEditingController(text: 'Dear there');
      addTearDown(controller.dispose);
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 10);
      final next = insertPlaceholder(controller, '{{Customer.Name}}',
          at: const TextSelection.collapsed(offset: 5));
      expect(next, 'Dear {{Customer.Name}}there');
      expect(controller.text, next);
      expect(controller.selection, const TextSelection.collapsed(offset: 22));

      insertPlaceholder(controller, 'X', at: const TextSelection(baseOffset: 0, extentOffset: 4));
      expect(controller.text, 'X {{Customer.Name}}there');
      expect(controller.selection, const TextSelection.collapsed(offset: 1));
    });

    test('with no cursor it goes at the end, and a single line never takes a line break', () {
      final controller = TextEditingController(text: 'Subject');
      addTearDown(controller.dispose);
      controller.selection = const TextSelection.collapsed(offset: -1);
      insertPlaceholder(controller, ' {{Invoice.Number}}\n', singleLine: true);
      expect(controller.text, 'Subject {{Invoice.Number}} ');
    });
  });

  test('a rule reads tolerantly: unknown kinds, statuses and actions have safe readings', () {
    final rule = Rule.fromJson(const {
      'id': 1,
      'status': 'SOMETHING_NEW',
      'trigger': {'kind': 'ENTITY_DELETED', 'entityType': 'PRODUCT'},
      'actions': [
        {'key': 'k1', 'type': 'CREATE_QUOTE'},
      ],
    });
    expect(rule.name, 'Rule #1');
    expect(rule.status, RuleStatus.ACTIVE);
    expect(rule.trigger.kind, TriggerKind.ENTITY_CREATED);
    expect(rule.actions.single, isA<UnknownAction>());
    expect(rule.actions.single.toJson(), {'key': 'k1', 'type': 'CREATE_QUOTE'});
    expect(navPrivilegesFor('/automation'), isNotEmpty);
  });
  group('actions (07b)', () {
    const actor = {
      ..._manager,
      Privileges.taskManage,
      Privileges.promiseManage,
      Privileges.disputeManage,
      Privileges.emailSend,
    };
    final keyShape =
        RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

    Map<String, dynamic> role(String role, String label, String level, String levelLabel) => {
          'role': role,
          'label': label,
          'level': level,
          'levelLabel': levelLabel,
          'groupLabel': '$levelLabel level',
        };

    // The roles a rule's people are picked from, per kind of record (§6.13, no record).
    Object? assigneeContext(RequestOptions o) {
      final type = o.queryParameters['entityType'];
      return {
        'entityType': type,
        'entityId': null,
        'delivery': {'configured': true},
        'sender': {
          'restricted': false,
          'self': {'userId': 5, 'name': 'Cara Collections'},
        },
        'roles': [
          role('SALES_POC', 'Sales POC', 'CUSTOMER', 'Customer'),
          role('CUSTOMER_SUCCESS_POC', 'Customer Success POC', 'CUSTOMER', 'Customer'),
          role('COLLECTION_POC', 'Collection POC', 'CUSTOMER', 'Customer'),
          if (type == 'INVOICE') role('SALES_POC', 'Sales POC', 'RECORD', 'Invoice'),
        ],
        'customerEmails': {'available': false, 'addresses': []},
      };
    }

    const catalogue = {
      'entityType': 'INVOICE',
      'groups': [
        {
          'level': 'CUSTOMER',
          'label': 'Customer',
          'items': [
            {'token': '{{Customer.Name}}', 'label': 'Name', 'kind': 'TEXT'},
          ],
        },
        {
          'level': 'RECORD',
          'label': 'Invoice',
          'items': [
            {'token': '{{Invoice.Number}}', 'label': 'Number', 'kind': 'TEXT'},
            {'token': '{{Invoice.Balance}}', 'label': 'Balance', 'kind': 'MONEY'},
          ],
        },
      ],
    };

    Map<String, Object? Function(RequestOptions)> routes({Map<String, dynamic>? rule}) => {
          ..._routes(rule: rule),
          'GET /api/table-schemas/customers': (_) => {...invoiceSchema, 'entity': 'customers'},
          'GET /api/assignees/context': assigneeContext,
          'GET /api/assignees/people': (_) => [
                {'userId': 7, 'name': 'Ann Admin', 'username': 'ann', 'email': 'ann@company.com'},
              ],
          'GET /api/automation/placeholders': (_) => catalogue,
          'POST /api/automation/rules': (_) => _rule(),
        };

    Future<FakeBackend> newRule(WidgetTester tester, {Set<String> privileges = actor}) async {
      final (backend, _, _) = await _pump(tester,
          initialLocation: '/automation/new',
          user: _user(privileges: privileges),
          routes: routes());
      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Chase');
      return backend;
    }

    Future<void> addAction(WidgetTester tester, String type) async {
      await _tap(tester, find.byKey(const ValueKey('action-add')));
      await tester.tap(find.byKey(ValueKey('action-add-$type')));
      await tester.pumpAndSettle();
    }

    Future<void> addToken(WidgetTester tester, String field, String token) => _tap(
        tester,
        find.descendant(
            of: find.byKey(ValueKey(field)), matching: find.byKey(ValueKey('assignee-add-$token'))));

    Future<void> placeholder(WidgetTester tester, String button, String item) async {
      await _tap(tester, find.byKey(ValueKey(button)));
      await tester.tap(find.text(item).last);
      await tester.pumpAndSettle();
    }

    Future<Map<String, dynamic>> savedAction(WidgetTester tester, FakeBackend backend) async {
      await _tap(tester, find.byKey(const ValueKey('rule-save')));
      final actions = _json(backend.sent('POST /api/automation/rules').single.data)['actions'] as List;
      final action = _json(actions.single);
      expect(action['key'], matches(keyShape));
      return {...action}..remove('key');
    }

    testWidgets('a task card sends exactly what it shows', (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_TASK');

      await tester.enterText(find.byKey(const ValueKey('action-0-title')), 'Chase ');
      await placeholder(tester, 'action-0-title-placeholder', 'Number');
      await tester.enterText(
          find.byKey(const ValueKey('action-0-description')), '{{Customer.Name}} owes');
      await _choose(tester, 'action-0-due-base', "Days after the invoice's due date");
      await tester.enterText(find.byKey(const ValueKey('action-0-due-offset')), '-3');
      await addToken(tester, 'action-0-assignees', 'role-COLLECTION_POC-CUSTOMER');
      await addToken(tester, 'action-0-assignees', 'role-SALES_POC-RECORD');
      expect(find.text("Collection POC (each customer's)"), findsOneWidget);
      await _tap(tester, find.byKey(const ValueKey('action-0-guard')));
      await _choose(tester, 'action-0-repeat', 'Every time');
      expect(find.text('Create a task: Chase {{Invoice.Number}}'), findsOneWidget);

      expect(await savedAction(tester, backend), {
        'type': 'CREATE_TASK',
        'repeat': 'EVERY_TIME',
        'title': 'Chase {{Invoice.Number}}',
        'description': '{{Customer.Name}} owes',
        'due': {'base': 'INVOICE_DUE_DATE', 'offsetDays': -3},
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
        ],
        'onlyIfNoOpenTask': false,
      });
      // The rule's own kind of people were offered: this record's and its customer's.
      expect(backend.sent('GET /api/assignees/context').map((r) => r.queryParameters).toSet(),
          containsAll([
            {'entityType': 'INVOICE'},
          ]));
    });

    testWidgets('after a pick the field has its focus back with the caret after the token',
        (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_TASK');
      EditableText editable(String key) => tester.widget<EditableText>(
          find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(EditableText)));
      TextEditingController text(String key) =>
          tester.widget<TextField>(find.byKey(ValueKey(key))).controller!;

      Future<void> typeInsertType(
          String key, String before, String item, String token, String after) async {
        await tester.enterText(find.byKey(ValueKey(key)), before);
        expect(text(key).selection, TextSelection.collapsed(offset: before.length));
        // On the web the mouse press on {} takes the focus away from the field.
        FocusManager.instance.primaryFocus?.unfocus();
        await tester.pump();
        expect(editable(key).focusNode.hasFocus, isFalse);

        await placeholder(tester, '$key-placeholder', item);
        await tester.pumpAndSettle();

        expect(editable(key).focusNode.hasFocus, isTrue);
        expect(text(key).text, '$before$token');
        expect(text(key).selection, TextSelection.collapsed(offset: before.length + token.length));

        // Typing carries straight on, into this field, after the token.
        final typed = '$before$token$after';
        tester.testTextInput.updateEditingValue(TextEditingValue(
            text: typed, selection: TextSelection.collapsed(offset: typed.length)));
        await tester.pump();
        expect(text(key).text, typed);
      }

      await typeInsertType('action-0-title', 'Chase ', 'Name', '{{Customer.Name}}', ' now');
      expect(text('action-0-title').text, 'Chase {{Customer.Name}} now');
      await typeInsertType('action-0-description', 'Owes ', 'Balance', '{{Invoice.Balance}}', '!');
      expect(text('action-0-description').text, 'Owes {{Invoice.Balance}}!');

      final saved = await savedAction(tester, backend);
      expect(saved['title'], 'Chase {{Customer.Name}} now');
      expect(saved['description'], 'Owes {{Invoice.Balance}}!');
    });

    testWidgets('a day count can be cleared and retyped, a sign first, without snapping back',
        (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_TASK');
      await tester.enterText(find.byKey(const ValueKey('action-0-title')), 'Chase');
      await _choose(tester, 'action-0-due-base', 'Days after it runs');
      final offset = find.byKey(const ValueKey('action-0-due-offset'));
      String text() => tester.widget<TextField>(offset).controller!.text;

      await tester.enterText(offset, '2');
      await tester.pump();
      await tester.enterText(offset, '');
      await tester.pump();
      expect(text(), '');
      await tester.enterText(offset, '-');
      await tester.pump();
      expect(text(), '-');
      await tester.enterText(offset, '-7');
      await tester.pump();
      expect(text(), '-7');

      expect((await savedAction(tester, backend))['due'], {'base': 'TODAY', 'offsetDays': -7});
    });

    testWidgets('a promise card: a fixed amount, its date, the Collection POC and co-assignees',
        (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_PROMISE');

      await _tap(
          tester,
          find.descendant(
              of: find.byKey(const ValueKey('action-0-amount-source')),
              matching: find.text('A fixed amount')));
      await tester.enterText(find.byKey(const ValueKey('action-0-amount-value')), '1500.50');
      await tester.enterText(find.byKey(const ValueKey('action-0-promisedDate-offset')), '10');
      await addToken(tester, 'action-0-collectionPoc', 'role-COLLECTION_POC-CUSTOMER');
      // Only the Collection POC is offered for the one accountable seat.
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('action-0-collectionPoc')),
              matching: find.byKey(const ValueKey('assignee-add-role-SALES_POC-CUSTOMER'))),
          findsNothing);
      await addToken(tester, 'action-0-assignees', 'role-CUSTOMER_SUCCESS_POC-CUSTOMER');
      await tester.enterText(find.byKey(const ValueKey('action-0-notes')), 'Agreed on the call');
      await _tap(tester, find.byKey(const ValueKey('action-0-link')));

      expect(await savedAction(tester, backend), {
        'type': 'CREATE_PROMISE',
        'repeat': 'ONCE_PER_RECORD',
        'amount': {'source': 'FIXED', 'value': 1500.5},
        'promisedDate': {'base': 'TODAY', 'offsetDays': 10},
        'collectionPoc': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
        'assignees': [
          {'type': 'ROLE', 'role': 'CUSTOMER_SUCCESS_POC', 'level': 'CUSTOMER'},
        ],
        'notes': 'Agreed on the call',
        'linkRecord': false,
        'onlyIfNoOpenPromise': true,
      });
      // Co-assignees are the promise's own people (§7.7.3).
      expect(backend.sent('GET /api/assignees/context').map((r) => r.queryParameters['entityType']),
          containsAll(['INVOICE', 'PROMISE']));
    });

    testWidgets('a promise card takes its amount from the record by default', (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_PROMISE');
      await _choose(tester, 'action-0-amount-field', "Customer's overdue amount");

      final action = await savedAction(tester, backend);
      expect(action['amount'], {'source': 'FIELD', 'field': 'Customer.OverdueAmount'});
      expect(action['collectionPoc'], isNull);
      expect(action['linkRecord'], isTrue);
    });

    testWidgets('a dispute card: its reason, its proposal and who looks into it', (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_DISPUTE');

      await tester.enterText(find.byKey(const ValueKey('action-0-reason')), 'Review ');
      await placeholder(tester, 'action-0-reason-placeholder', 'Number');
      await _choose(tester, 'action-0-proposal', 'Cancel the invoice');
      await addToken(tester, 'action-0-assignees', 'role-COLLECTION_POC-CUSTOMER');

      expect(await savedAction(tester, backend), {
        'type': 'CREATE_DISPUTE',
        'repeat': 'ONCE_PER_RECORD',
        'reason': 'Review {{Invoice.Number}}',
        'proposedChange': 'cancel',
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
        ],
      });
      expect(backend.sent('GET /api/assignees/context').map((r) => r.queryParameters['entityType']),
          contains('DISPUTE'));
    });

    testWidgets('the email dialog: a role sends it, to roles and the customer, with placeholders',
        (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'SEND_EMAIL');
      await _tap(tester, find.byKey(const ValueKey('action-0-edit-email')));
      expect(find.text('Email'), findsOneWidget);

      // Nothing to send to yet: said, and nothing handed back.
      await tester.tap(find.byKey(const ValueKey('email-done')));
      await tester.pumpAndSettle();
      expect(find.text('Add at least one recipient'), findsOneWidget);
      expect(find.text('Enter a subject'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('email-from')));
      await tester.pumpAndSettle();
      await tester.tap(find.text("Collection POC (each customer's)").last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('email-fallback')));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byKey(const ValueKey('email-to')),
          matching: find.byKey(const ValueKey('assignee-add-customer'))));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byKey(const ValueKey('email-to')),
          matching: find.byKey(const ValueKey('assignee-add-role-SALES_POC-RECORD'))));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('email-subject')), 'Invoice  is overdue');
      final subject =
          tester.widget<TextField>(find.byKey(const ValueKey('email-subject'))).controller!;
      subject.selection = const TextSelection.collapsed(offset: 8);
      // On the web the mouse press on {} takes the focus away from the field.
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('email-subject-placeholder')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Number').last);
      await tester.pumpAndSettle();
      final subjectField = tester.widget<EditableText>(find.descendant(
          of: find.byKey(const ValueKey('email-subject')), matching: find.byType(EditableText)));
      expect(subjectField.focusNode.hasFocus, isTrue);
      expect(subject.text, 'Invoice {{Invoice.Number}} is overdue');
      expect(subject.selection, const TextSelection.collapsed(offset: 26));
      await tester.enterText(find.byKey(const ValueKey('email-body')), 'Dear {{Customer.Name}}');
      await tester.tap(find.byKey(const ValueKey('email-done')));
      await tester.pumpAndSettle();

      // The card says what it will send.
      expect(find.text("From: Collection POC (each customer's)"), findsOneWidget);
      expect(find.text("To: Customer emails, Sales POC (each invoice's)"), findsOneWidget);
      expect(find.text('Subject: Invoice {{Invoice.Number}} is overdue'), findsOneWidget);

      expect(await savedAction(tester, backend), {
        'type': 'SEND_EMAIL',
        'repeat': 'ONCE_PER_RECORD',
        'from': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
        'fallbackToOwner': false,
        'to': [
          {'type': 'CUSTOMER'},
          {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
        ],
        'subject': 'Invoice {{Invoice.Number}} is overdue',
        'body': 'Dear {{Customer.Name}}',
      });
    });

    testWidgets('the email can be sent as a person found among those who may be assigned',
        (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'SEND_EMAIL');
      await _tap(tester, find.byKey(const ValueKey('action-0-edit-email')));

      await tester.tap(find.byKey(const ValueKey('email-from')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Someone else…').last);
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ann Admin'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/assignees/people'), isNotEmpty);
      await tester.tap(find.descendant(
          of: find.byKey(const ValueKey('email-to')),
          matching: find.byKey(const ValueKey('assignee-add-customer'))));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('email-subject')), 'Hello');
      await tester.tap(find.byKey(const ValueKey('email-done')));
      await tester.pumpAndSettle();

      expect(find.text('From: Ann Admin, or the owner'), findsOneWidget);
      final action = await savedAction(tester, backend);
      expect(action['from'], {'type': 'USER', 'userId': 7});
      expect(action['fallbackToOwner'], isTrue);
      expect(action['to'], [
        {'type': 'CUSTOMER'}
      ]);
    });

    testWidgets('only the kinds of action the user may take are offered, and at most five',
        (tester) async {
      await newRule(tester, privileges: {..._manager, Privileges.taskManage});
      await _tap(tester, find.byKey(const ValueKey('action-add')));
      expect(find.byKey(const ValueKey('action-add-CREATE_TASK')), findsOneWidget);
      expect(find.byKey(const ValueKey('action-add-SEND_EMAIL')), findsNothing);
      expect(find.byKey(const ValueKey('action-add-CREATE_DISPUTE')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('action-add-CREATE_TASK')));
      await tester.pumpAndSettle();
      for (var i = 1; i < 5; i++) {
        await addAction(tester, 'CREATE_TASK');
      }
      expect(find.byKey(const ValueKey('action-add')), findsNothing);
      expect(find.text('A rule can take at most 5 actions.'), findsOneWidget);

      await _tap(tester, find.byKey(const ValueKey('action-4-remove')));
      expect(find.byKey(const ValueKey('action-add')), findsOneWidget);
    });

    testWidgets('a region is offered only where every action can act', (tester) async {
      const noEmail = RegionAccess(
          id: 2,
          code: 'SOUTH',
          name: 'South',
          level: 'MANAGE',
          scope: 'ALL',
          privileges: {Privileges.invoiceView, Privileges.taskManage});
      await _pump(tester,
          initialLocation: '/automation/new',
          user: _user(privileges: actor, regions: const [_north, noEmail]),
          routes: routes());

      expect(find.byKey(const ValueKey('rule-region-2')), findsOneWidget);
      await addAction(tester, 'CREATE_TASK');
      expect(find.byKey(const ValueKey('rule-region-2')), findsOneWidget);
      await addAction(tester, 'SEND_EMAIL');
      expect(find.byKey(const ValueKey('rule-region-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('rule-region-2')), findsNothing);
    });

    testWidgets('the server\'s reasons about an action show on its card, under its fields',
        (tester) async {
      final (_, _, _) = await _pump(tester,
          initialLocation: '/automation/new',
          user: _user(privileges: actor),
          routes: {
            ...routes(),
            'POST /api/automation/rules': (_) => const FakeFailure(400, {
                  'message': 'Validation failed',
                  'fieldErrors': {
                    'actions[0].title': 'Unknown placeholder {{Invoice.Numbr}}',
                    'actions[0].due.offsetDays': 'must be at most 365',
                    'actions[0].assignees[0]': 'Pat cannot see these invoices',
                  },
                }),
          });
      await tester.enterText(find.byKey(const ValueKey('rule-name')), 'Chase');
      await addAction(tester, 'CREATE_TASK');
      await tester.enterText(find.byKey(const ValueKey('action-0-title')), '{{Invoice.Numbr}}');
      await _tap(tester, find.byKey(const ValueKey('rule-save')));

      expect(find.text('Unknown placeholder {{Invoice.Numbr}}'), findsOneWidget);
      expect(find.text('must be at most 365'), findsOneWidget);
      expect(find.text('Pat cannot see these invoices'), findsOneWidget);

      // Editing the action clears what was said about it.
      await tester.enterText(find.byKey(const ValueKey('action-0-title')), '{{Invoice.Number}}');
      await tester.pump();
      expect(find.text('Unknown placeholder {{Invoice.Numbr}}'), findsNothing);
    });

    testWidgets('another kind of record asks first, then drops what only invoices have',
        (tester) async {
      final backend = await newRule(tester);
      await addAction(tester, 'CREATE_TASK');
      await tester.enterText(find.byKey(const ValueKey('action-0-title')),
          'Chase {{Invoice.Number|the invoice}} for {{Customer.Name}}');
      await _choose(tester, 'action-0-due-base', "Days after the invoice's due date");
      await addToken(tester, 'action-0-assignees', 'role-SALES_POC-RECORD');
      await addToken(tester, 'action-0-assignees', 'role-COLLECTION_POC-CUSTOMER');

      await _tap(
          tester,
          find.descendant(
              of: find.byKey(const ValueKey('rule-entity')), matching: find.text('Customer')));
      expect(find.text('Change to customers?'), findsOneWidget);
      expect(find.textContaining('only invoices have will be taken out'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('rule-entity-confirm')));
      await tester.pumpAndSettle();

      expect(
          tester.widget<TextField>(find.byKey(const ValueKey('action-0-title'))).controller!.text,
          'Chase  for {{Customer.Name}}');
      final action = await savedAction(tester, backend);
      expect(action['title'], 'Chase  for {{Customer.Name}}');
      expect(action['due'], {'base': 'TODAY', 'offsetDays': 2});
      expect(action['assignees'], [
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
      ]);
    });

    testWidgets('a saved rule\'s cards are read only on a past date', (tester) async {
      await _pump(tester,
          user: _user(privileges: actor),
          routes: routes(rule: {..._rule(canEdit: false, canRun: false), 'version': null}),
          asOf: DateTime(2026, 6, 30));

      expect(find.byKey(const ValueKey('action-add')), findsNothing);
      expect(find.byKey(const ValueKey('action-0-remove')), findsNothing);
      expect(find.byKey(const ValueKey('action-1-edit-email')), findsNothing);
      expect(tester.widget<TextField>(find.byKey(const ValueKey('action-0-title'))).enabled,
          isFalse);
    });

    testWidgets('the preview renders what each action would do to the record picked',
        (tester) async {
      await _pump(tester, user: _user(privileges: actor), routes: {
        ...routes(),
        'GET /api/invoices': (_) => {
              'content': [
                {'id': 77, 'invoiceNumber': 'INV-0077', 'customerName': 'Acme Ltd'},
              ],
              'page': 0,
              'size': 20,
              'totalElements': 1,
              'totalPages': 1,
            },
        'POST /api/automation/rules/preview': _answer({
          ..._preview,
          'record': {
            'id': 77,
            'label': 'Invoice INV-0077',
            'matches': true,
            'actions': [
              {
                'key': _taskAction['key'],
                'type': 'CREATE_TASK',
                'rendered': {'title': 'Chase INV-0077', 'dueDate': '2026-09-28'},
                'assignees': [
                  {
                    'token': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
                    'label': 'Collection POC (customer)',
                    'people': [
                      {'userId': 5, 'name': 'Cara', 'primary': true},
                    ],
                    'unresolved': false,
                  },
                ],
                'problems': [],
                'warnings': [],
              },
              {
                'key': 'another',
                'type': 'SEND_EMAIL',
                'rendered': {'subject': 'Invoice INV-0077 is overdue', 'body': 'Dear Acme'},
                'email': {
                  'from': {'name': 'Cara', 'address': 'cara@x.com', 'internal': true},
                  'to': [
                    {'name': 'Acme Ltd', 'address': 'ap@acme.com', 'internal': false},
                  ],
                  'unresolved': [],
                  'problems': ['Nobody holds Sales POC on this invoice'],
                },
                'problems': [],
                'warnings': ['Email delivery is not configured, so this email will be saved'],
              },
            ],
          },
        }),
      });

      await _tap(tester, find.byKey(const ValueKey('preview-record')));
      await tester.tap(find.text('INV-0077'));
      await tester.pumpAndSettle();
      await _tap(tester, find.byKey(const ValueKey('rule-preview')));

      final task = find.byKey(ValueKey('preview-action-${_taskAction['key']}'));
      expect(find.descendant(of: task, matching: find.text('Create a task: Chase {{Invoice.Number}}')),
          findsOneWidget);
      expect(find.descendant(of: task, matching: find.text('Title: Chase INV-0077')),
          findsOneWidget);
      expect(find.descendant(of: task, matching: find.text('Due: 2026-09-28')), findsOneWidget);
      expect(find.descendant(of: task, matching: find.text('Collection POC (customer) · Cara')),
          findsOneWidget);

      final email = find.byKey(const ValueKey('preview-action-another'));
      expect(find.descendant(of: email, matching: find.text('Send an email')), findsOneWidget);
      expect(find.descendant(of: email, matching: find.text('Subject: Invoice INV-0077 is overdue')),
          findsOneWidget);
      expect(find.descendant(of: email, matching: find.text('To Acme Ltd <ap@acme.com>')),
          findsOneWidget);
      expect(
          find.descendant(
              of: email, matching: find.text('Nobody holds Sales POC on this invoice')),
          findsOneWidget);
      expect(
          find.descendant(
              of: email,
              matching: find.text('Email delivery is not configured, so this email will be saved')),
          findsOneWidget);
    });

    testWidgets('a saved rule has a Runs tab', (tester) async {
      final (backend, _, _) = await _pump(tester, routes: {
        ...routes(),
        'GET /api/table-schemas/automation-runs': (_) => {'entity': 'automation-runs', 'columns': []},
        'GET /api/automation/rules/12/runs': _answer({
          'content': [
            {'id': 88, 'status': 'SUCCEEDED', 'triggerType': 'SCHEDULED', 'matched': 14, 'done': 14},
          ],
          'page': 0,
          'size': 20,
          'totalElements': 1,
          'totalPages': 1,
        }),
      });

      await _tap(
          tester, find.descendant(of: find.byType(TabBar), matching: find.text('Runs')));
      expect(find.text('#88'), findsOneWidget);
      expect(backend.sent('GET /api/automation/rules/12/runs').single.queryParameters['sort'],
          'createdAt,desc');
    });

    group('the people a reloaded rule names', () {
      const priya = {'type': 'USER', 'userId': 12};
      Map<String, dynamic> named({Map<String, String> people = const {'12': 'Priya Rao'}}) => {
            ..._rule(people: people),
            'actions': [
              {
                ..._taskAction,
                'assignees': [priya],
              },
              {
                ..._emailAction,
                'from': priya,
                'fallbackToOwner': true,
                'to': [
                  priya,
                  {'type': 'CUSTOMER'},
                ],
              },
            ],
          };

      void expectPriya() {
        expect(
            find.descendant(
                of: find.byKey(const ValueKey('action-0-assignees')),
                matching: find.text('Priya Rao')),
            findsOneWidget);
        expect(find.text('From: Priya Rao, or the owner'), findsOneWidget);
        expect(find.text('To: Priya Rao, Customer emails'), findsOneWidget);
        expect(find.textContaining('User #12'), findsNothing);
      }

      testWidgets('are named as the server names them, in the summary and the chips',
          (tester) async {
        await _pump(tester, user: _user(privileges: actor), routes: routes(rule: named()));
        expectPriya();
      });

      testWidgets('are named on a past date too', (tester) async {
        final (backend, _, _) = await _pump(tester,
            user: _user(privileges: actor),
            asOf: DateTime(2026, 9, 1),
            routes: routes(rule: named()));
        expect(backend.sent('GET /api/automation/rules/12').last.queryParameters['asOf'],
            '2026-09-01');
        expectPriya();
      });

      testWidgets('keep the names picked on the page for people the saved rule does not name',
          (tester) async {
        final (backend, _, _) = await _pump(tester, user: _user(privileges: actor), routes: {
          ...routes(rule: named()),
          // The server names only Priya: it answers with the rule as it was sent, Ann and all.
          'PUT /api/automation/rules/12': (o) => {
                ...named(),
                'version': 4,
                'actions': _json(o.data)['actions'],
              },
        });
        await _tap(tester, find.byKey(const ValueKey('action-1-edit-email')));
        await tester.tap(find.byKey(const ValueKey('email-from')));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Someone else…').last);
        await tester.pumpAndSettle();
        await tester.pump(const Duration(milliseconds: 300));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Ann Admin'));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('email-done')));
        await tester.pumpAndSettle();
        await _tap(tester, find.byKey(const ValueKey('rule-save')));

        final put = _json(backend.sent('PUT /api/automation/rules/12').single.data);
        expect(_json((put['actions'] as List)[1])['from'], {'type': 'USER', 'userId': 7});
        expect(find.text('From: Ann Admin, or the owner'), findsOneWidget);
        expect(find.text('To: Priya Rao, Customer emails'), findsOneWidget);
        expect(find.textContaining('User #'), findsNothing);
      });

      test('a rule with no people reads as naming nobody', () {
        final rule = Rule.fromJson(const {
          'id': 1,
          'trigger': {'kind': 'SCHEDULE_DAILY', 'entityType': 'INVOICE'},
        });
        expect(rule.people, isEmpty);
        expect(Rule.fromJson(named()).people, {12: 'Priya Rao'});
      });
    });
  });
}
