import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/activity_screen.dart';
import 'package:gene_invoice/features/automation/automation_providers.dart';
import 'package:gene_invoice/features/automation/rules_screen.dart';
import 'package:gene_invoice/features/automation/run_now_sheet.dart';
import 'package:gene_invoice/features/tasks/task_detail_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:gene_invoice/shared/widgets/status_chip.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

/// NORTH is a branch this person may read; SOUTH is one they hold nothing in. A step hangs off one
/// account in one branch, so the pair is what makes `hasIn` testable (B1).
const _north = RegionGrant(id: 1, code: 'NORTH', name: 'North', rights: {regionRightView});

const _reader = {Privileges.automationView, Privileges.invoiceView, Privileges.taskView};
const _operator0 = {
  Privileges.automationView,
  Privileges.automationManage,
  Privileges.automationRun,
  Privileges.invoiceView,
  Privileges.taskView,
  Privileges.approvalView,
};

CurrentUser _user([Set<String> privileges = _operator0]) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: 'CASHIER',
      privileges: privileges,
      customerId: null,
      regions: const [_north],
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

/// GET /api/table-schemas/automationSteps as AutomationSchemas.STEPS publishes it: the registered
/// column names verbatim, so a chip this client composes is one the server would accept (A5).
Map<String, dynamic> _stepsSchema() => {
      'entity': 'automationSteps',
      'defaultSort': 'id,desc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today', 'last7Days'],
      'columns': [
        _column('id', 'Id', 'NUMBER'),
        _column('createdAt', 'When', 'DATE', operators: ['relative', 'between'], sortable: true),
        _column('ruleId', 'Rule', 'REFERENCE', referenceKind: 'rule', sortable: true),
        _column('ruleName', 'Rule name', 'TEXT', operators: ['contains'], sortable: true),
        _column('subjectType', 'About', 'ENUM',
            operators: ['eq', 'in'], enumValues: ['CUSTOMER', 'INVOICE', 'PAYMENT']),
        _column('subjectId', 'Record id', 'NUMBER'),
        _column('customerId', 'Customer', 'REFERENCE', referenceKind: 'customer'),
        _column('regionId', 'Region', 'REFERENCE',
            operators: ['eq', 'in'], referenceKind: 'region'),
        _column('regionName', 'Region name', 'TEXT', operators: ['contains']),
        _column('actionKind', 'Action', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['CREATE_TASK', 'CREATE_PROMISE', 'CREATE_DISPUTE', 'SEND_EMAIL'],
            sortable: true),
        _column('actionIndex', 'Step', 'NUMBER'),
        _column('source', 'Source', 'ENUM',
            operators: ['eq', 'in'], enumValues: ['EVENT', 'SCHEDULE', 'MANUAL']),
        _column('status', 'Status', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['QUEUED', 'RUNNING', 'DONE', 'SKIPPED', 'POISONED'],
            sortable: true),
        _column('attempts', 'Attempts', 'NUMBER', sortable: true),
        _column('runId', 'Run', 'NUMBER'),
        _column('occasion', 'Occasion', 'TEXT', operators: ['contains']),
        _column('producedType', 'Made', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['TASK', 'PROMISE', 'DISPUTE', 'EMAIL', 'PENDING_CHANGE']),
        _column('producedId', 'Made id', 'NUMBER'),
        _column('result', 'Result', 'TEXT', operators: ['contains']),
        _column('finishedAt', 'Finished', 'DATE', operators: ['relative'], sortable: true),
      ],
    };

/// Comfortably more than a minute old, so a settled list really does stop polling (A5).
const _longAgo = '2026-09-22T08:00:00Z';

Map<String, dynamic> _step(
  int id, {
  String status = 'DONE',
  String actionKind = 'CREATE_TASK',
  String? producedType = 'TASK',
  int? producedId = 12,
  String? result = 'Task #12',
  String? unresolved,
  int attempts = 1,
  int? regionId = 1,
  String subjectType = 'INVOICE',
  int subjectId = 42,
  String? finishedAt = _longAgo,
}) =>
    {
      'id': id,
      'createdAt': '2026-09-22T07:59:00Z',
      'ruleId': 7,
      'ruleName': 'Chase big overdue',
      'ruleVersion': 3,
      'subjectType': subjectType,
      'subjectId': subjectId,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'actionKind': actionKind,
      'actionIndex': 0,
      'source': 'SCHEDULE',
      'status': status,
      'attempts': attempts,
      'runId': 4,
      'occasion': 'S2026-09-22T08:00:00Z',
      'producedType': producedType,
      'producedId': producedId,
      'unresolved': unresolved,
      'result': result,
      'finishedAt': status == 'QUEUED' || status == 'RUNNING' ? null : finishedAt,
      'regionId': regionId,
      'regionName': regionId == 1 ? 'North' : 'South',
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> content) => {
      'content': content,
      'page': 0,
      'size': 20,
      'totalElements': content.length,
      'totalPages': 1,
      'sort': 'id,desc',
      'appliedFilters': const <String>[],
      'lockedFilters': const <String>[],
    };

FakeBackend _backend({
  Object? Function(RequestOptions)? steps,
  Object? Function(RequestOptions)? retry,
  Object? Function(RequestOptions)? run,
  List<Map<String, dynamic>>? rows,
}) =>
    FakeBackend({
      'GET /api/table-schemas/automationSteps': (_) => _stepsSchema(),
      'GET /api/automation/steps': steps ?? (_) => _page(rows ?? const []),
      'GET /api/regions/my': (_) => {
            'allRegions': false,
            'regions': [
              {'id': 1, 'code': 'NORTH', 'name': 'North', 'rights': ['VIEW']},
            ],
          },
      if (retry != null) 'POST /api/automation/steps/9/retry': retry,
      if (run != null) 'POST /api/automation/rules/7/run': run,
    });

Future<void> _sized(WidgetTester tester, [Size size = const Size(1600, 1000)]) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

Map<String, dynamic> _rulesSchema() => {
      'entity': 'automationRules',
      'defaultSort': 'name,asc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today'],
      'columns': [
        _column('id', 'Id', 'NUMBER'),
        _column('name', 'Name', 'TEXT', operators: ['contains', 'eq'], sortable: true),
        _column('subjectType', 'Subject', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['CUSTOMER', 'INVOICE', 'PAYMENT'],
            sortable: true),
        _column('triggerKind', 'Trigger', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: [
              'ON_CREATED',
              'ON_UPDATED',
              'ON_CREATED_OR_UPDATED',
              'SCHEDULE_DAILY',
              'SCHEDULE_WEEKLY',
            ],
            sortable: true),
        _column('enabled', 'Enabled', 'BOOLEAN', sortable: true),
        _column('lastRunAt', 'Last run', 'DATE', operators: ['relative'], sortable: true),
        _column('nextRunAt', 'Next run', 'DATE', operators: ['relative'], sortable: true),
        _column('createdByUserId', 'Author', 'REFERENCE', referenceKind: 'user'),
        _column('createdAt', 'Created', 'DATE', operators: ['relative'], sortable: true),
      ],
    };

Map<String, dynamic> _rule() => {
      'id': 7,
      'name': 'Chase big overdue',
      'description': 'Dunning for the north',
      'subjectType': 'INVOICE',
      'triggerKind': 'SCHEDULE_WEEKLY',
      'scheduleHourUtc': 9,
      'scheduleDayOfWeek': 3,
      'conditions': {
        'op': 'AND',
        'of': [
          {'filter': 'balance:gt:50000'},
        ],
      },
      'actions': [
        {'kind': 'CREATE_TASK', 'title': 'Chase them', 'assignees': const []},
        {'kind': 'SEND_EMAIL', 'to': const [], 'subject': 'Your overdue invoice'},
      ],
      'cooldownDays': 14,
      'enabled': true,
      'definitionVersion': 4,
      'regions': const <Map<String, dynamic>>[],
      'allAuthorRegions': true,
      'nextRunAt': '2026-09-30T09:00:00Z',
      'lastRunAt': '2026-09-16T09:00:00Z',
      'createdByUserId': 3,
      'createdByName': 'Jane Doe',
      'createdAt': '2026-08-01T08:00:00Z',
      'updatedAt': '2026-09-01T08:00:00Z',
      'version': 2,
    };

Future<void> _pumpRules(WidgetTester tester, FakeBackend backend,
    {required CurrentUser user}) async {
  await _sized(tester);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'name,asc')),
    ),
  ));
  await tester.pumpAndSettle();
}

Future<GoRouter> _pumpActivity(WidgetTester tester, FakeBackend backend,
    {CurrentUser? user}) async {
  await _sized(tester);
  final router = GoRouter(
    initialLocation: '/automation/activity',
    routes: [
      GoRoute(
        path: '/automation/activity',
        builder: (_, s) => const AutomationActivityPage(
            query: TableQuery(size: 20, sort: 'id,desc')),
      ),
      GoRoute(path: '/automation/rules/:id', builder: (_, __) => const SizedBox.shrink()),
      GoRoute(path: '/invoices/:id', builder: (_, __) => const SizedBox.shrink()),
      GoRoute(path: '/tasks/:id', builder: (_, __) => const SizedBox.shrink()),
      GoRoute(path: '/approvals/:id', builder: (_, __) => const SizedBox.shrink()),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user ?? _user()),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

void main() {
  // ------------------------------------------------------------------ the poller

  test('the refresh interval is five seconds while anything is live, thirty for a minute after, '
      'and nothing once it has settled', () {
    final now = DateTime.utc(2026, 9, 23, 10, 0);
    AutomationStep step(String status, {DateTime? finishedAt}) => AutomationStep.fromJson({
          'id': 1,
          'status': status,
          'finishedAt': finishedAt?.toIso8601String(),
        });

    expect(automationRefreshInterval([step('RUNNING')], now: now), const Duration(seconds: 5));
    expect(automationRefreshInterval([step('QUEUED')], now: now), const Duration(seconds: 5));
    // Settled thirty seconds ago: something may still be arriving behind it.
    expect(
        automationRefreshInterval(
            [step('DONE', finishedAt: now.subtract(const Duration(seconds: 30)))],
            now: now),
        const Duration(seconds: 30));
    // Settled and quiet. NULL is what stops the timer, and a list that went on asking for ever
    // because somebody left the tab open would be a bug nobody would ever see (A5).
    expect(
        automationRefreshInterval(
            [step('DONE', finishedAt: now.subtract(const Duration(minutes: 5)))],
            now: now),
        isNull);
    expect(automationRefreshInterval(const [], now: now), isNull);
    // One live step among settled ones still means five seconds.
    expect(
        automationRefreshInterval([
          step('DONE', finishedAt: now.subtract(const Duration(days: 1))),
          step('RUNNING'),
        ], now: now),
        const Duration(seconds: 5));
  });

  testWidgets('a running step keeps the list polling and a settled one stops it', (tester) async {
    var settled = false;
    final backend = _backend(
      steps: (_) => _page([
        settled ? _step(9) : _step(9, status: 'RUNNING', producedType: null, producedId: null,
            result: null),
      ]),
    );
    await _pumpActivity(tester, backend);

    expect(find.descendant(of: find.byType(StepStatusChip), matching: find.text('Running')),
        findsOneWidget);
    final first = backend.sent('GET /api/automation/steps').length;

    // Five seconds, because something is still being worked (A5).
    settled = true;
    await tester.pump(const Duration(seconds: 6));
    await tester.pumpAndSettle();
    final second = backend.sent('GET /api/automation/steps').length;
    expect(second, greaterThan(first));
    expect(find.descendant(of: find.byType(StepStatusChip), matching: find.text('Done')),
        findsOneWidget);

    // And now nothing is live and nothing settled in the last minute, so the timer is gone: a
    // full minute more produces not one further request (A5).
    await tester.pump(const Duration(seconds: 60));
    await tester.pumpAndSettle();
    expect(backend.sent('GET /api/automation/steps').length, second);
  });

  // ------------------------------------------------------------------ retry

  testWidgets('a poisoned step offers Retry only to somebody who may manage automation',
      (tester) async {
    final poisoned = _step(9,
        status: 'POISONED',
        producedType: null,
        producedId: null,
        result: 'Invoice 42 is cancelled',
        attempts: 5);

    // A reader holding only AUTOMATION_VIEW is offered nothing to press: retry is gated on
    // AUTOMATION_MANAGE on the server, and a button that could only ever earn a 403 is worse
    // than no button (A5, UI-10).
    final readOnly = _backend(rows: [poisoned]);
    await _pumpActivity(tester, readOnly, user: _user(_reader));
    expect(find.text('Failed'), findsOneWidget);
    expect(find.byTooltip('Try this again'), findsNothing);

    var retried = 0;
    var pollsSinceRetry = 0;
    final backend = _backend(
      steps: (_) {
        if (retried == 0) return _page([poisoned]);
        pollsSinceRetry++;
        // Queued, then worked: the retry hands the step back to the sweeper rather than running
        // it inline, which is why the list has to keep asking (A5).
        return _page([
          pollsSinceRetry <= 1
              ? _step(9,
                  status: 'QUEUED',
                  producedType: null,
                  producedId: null,
                  result: null,
                  attempts: 0)
              : _step(9),
        ]);
      },
      retry: (_) {
        retried++;
        return _step(9,
            status: 'QUEUED', producedType: null, producedId: null, result: null, attempts: 0);
      },
    );
    await _pumpActivity(tester, backend);

    // The step's own words, verbatim: the server writes the result line to be READ, and
    // re-wording it here would be a second vocabulary for the same fact (A5).
    expect(find.text('Invoice 42 is cancelled'), findsOneWidget);

    await tester.tap(find.byTooltip('Try this again'));
    await tester.pumpAndSettle();

    expect(backend.sent('POST /api/automation/steps/9/retry'), hasLength(1));
    expect(retried, 1);
    // Back to QUEUED with its attempts reset, and the list says so rather than waiting for a
    // reload (A5).
    expect(find.descendant(of: find.byType(StepStatusChip), matching: find.text('Queued')),
        findsOneWidget);
    // QUEUED is live, so the list keeps asking until the sweeper has worked it (A5).
    await tester.pump(const Duration(seconds: 6));
    await tester.pumpAndSettle();
    expect(find.descendant(of: find.byType(StepStatusChip), matching: find.text('Done')),
        findsOneWidget);
    expect(find.byTooltip('Try this again'), findsNothing);
  });

  testWidgets('a step that finished is not offered a retry at all', (tester) async {
    final backend = _backend(rows: [_step(9)]);
    await _pumpActivity(tester, backend);

    expect(find.descendant(of: find.byType(StepStatusChip), matching: find.text('Done')),
        findsOneWidget);
    expect(find.byTooltip('Try this again'), findsNothing);
  });

  // ------------------------------------------------------------------ the two links

  testWidgets('a step links to the record it acted on and to what it made', (tester) async {
    final backend = _backend(rows: [_step(9)]);
    final router = await _pumpActivity(tester, backend);

    // The record: "Invoice #42 · Acme Ltd", a link because this reader holds INVOICE_VIEW in the
    // branch the account is in (A5, B1).
    await tester.tap(find.widgetWithText(InkWell, 'Invoice #42 · Acme Ltd'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/invoices/42');

    router.go('/automation/activity');
    await tester.pumpAndSettle();

    // And what it MADE. producedType + producedId is what turns a finished step into a link to
    // the thing it created (A5).
    // The "Made" cell and not the result sentence, which says the same words on purpose: the
    // server writes 'Task #12' to be read, and the link is what makes it reachable (A5).
    await tester.tap(find.widgetWithText(InkWell, 'Task #12'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/tasks/12');

    router.go('/automation/activity');
    await tester.pumpAndSettle();

    // And the rule that decided it.
    await tester.tap(find.widgetWithText(InkWell, 'Chase big overdue'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/rules/7');
  });

  testWidgets('a held action links to the approvals queue and is not shown as a failure',
      (tester) async {
    final backend = _backend(rows: [
      _step(9,
          producedType: 'PENDING_CHANGE',
          producedId: 4,
          result: 'Waiting for approval (change #4): Promise to pay ₹60,000'),
    ]);
    final router = await _pumpActivity(tester, backend);

    // PENDING_CHANGE IS A SUCCESS. An action over its region's threshold was parked for a second
    // pair of eyes, and "made a change that is waiting for somebody" is the honest reading — so
    // the step is DONE and its chip is the successful one, not an error (A5, B2 INTEGRATION).
    expect(find.descendant(of: find.byType(StepStatusChip), matching: find.text('Done')),
        findsOneWidget);
    expect(find.text('Failed'), findsNothing);
    expect(find.text('Waiting for approval (change #4): Promise to pay ₹60,000'), findsOneWidget);

    await tester.tap(find.widgetWithText(InkWell, 'Awaiting approval #4'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/approvals/4');
  });

  testWidgets('a record in a branch this reader holds nothing in is named but not linked',
      (tester) async {
    // regionId 2 is SOUTH, which this person has no grant in. hasIn, not has: holding
    // INVOICE_VIEW somewhere else is not permission here, and offering a link the router would
    // bounce them off is worse than plain text (A5, B1, UI-10).
    final backend = _backend(rows: [_step(9, regionId: 2)]);
    final router = await _pumpActivity(tester, backend);

    expect(find.text('Invoice #42 · Acme Ltd'), findsOneWidget);
    // Named, but not a link: there is nothing to tap.
    expect(find.widgetWithText(InkWell, 'Invoice #42 · Acme Ltd'), findsNothing);
    await tester.tap(find.text('Invoice #42 · Acme Ltd'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/activity');
  });

  testWidgets('roles and placeholders that found nobody are named on the row', (tester) async {
    final backend = _backend(rows: [
      _step(9, unresolved: 'Collection POC (customer), {{Invoice.PoNumber}}'),
    ]);
    await _pumpActivity(tester, backend);

    // The server writes this to be read, so it is rendered as it arrives: an unheld seat does not
    // stop the work, it is recorded (A5, A3).
    expect(find.text('Nobody: Collection POC (customer), {{Invoice.PoNumber}}'), findsOneWidget);
    // And WHICH DOOR the work came in through, which is the whole reason the step keeps a
    // source: nobody should have to infer it from a null run id (A5).
    expect(find.text('Schedule'), findsOneWidget);
  });

  // ------------------------------------------------------------------ the sidebar

  test('Automation is the last entry in the sidebar, below Tasks', () {
    // THE SETTLED ORDER IS REACHED BY INSERTION, not by the order the waves happened to land in:
    // Approvals went in above, Tasks after Regions, and Automation last (A1, A6, B1, B2).
    expect(navEntries.last.label, 'Automation');
    expect(navEntries.last.path, '/automation/rules');
    final labels = [for (final e in navEntries) e.label];
    expect(labels.indexOf('Automation'), greaterThan(labels.indexOf('Tasks')));
    expect(labels.indexOf('Tasks'), greaterThan(labels.indexOf('Approvals')));
    expect(labels.indexOf('Approvals'), greaterThan(labels.indexOf('Promises')));

    // The sidebar's own rule, which the router guards all four automation routes with, so a
    // screen the nav hides cannot be reached by hand-editing the URL either (UI-10).
    expect(navPrivilegesFor('/automation/rules'), const [Privileges.automationView]);
    expect(navEntries.last.hideForCustomer, isTrue);
    // A FOURTH badge flag and not a widening of one of the other three: an entry that does not
    // show a count must never fetch one (A5, A6, B2).
    expect(navEntries.last.poisonedBadge, isTrue);
    expect(navEntries.last.unreadBadge, isFalse);
    expect(navEntries.last.approvalBadge, isFalse);
    expect(navEntries.last.taskBadge, isFalse);
  });

  test('the stuck-step badge is polled off the count endpoint, and never for somebody who may '
      'not see it', () async {
    final backend = FakeBackend({
      'GET /api/automation/steps/poisoned-count': (_) => {'count': 3},
    });
    final container = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_user()),
    ]);
    addTearDown(container.dispose);
    // An autoDispose provider with nobody listening is torn down the moment it is read, which
    // cancels the poll before its first answer arrives.
    container.listen(poisonedStepCountProvider, (_, __) {}, fireImmediately: true);
    expect(await container.read(poisonedStepCountProvider.future), 3);
    expect(backend.requests, hasLength(1));

    final blind = FakeBackend(const {});
    final other = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(blind.dio),
      currentUserProvider.overrideWithValue(_user(const {Privileges.invoiceView})),
    ]);
    addTearDown(other.dispose);
    other.listen(poisonedStepCountProvider, (_, __) {}, fireImmediately: true);
    expect(await other.read(poisonedStepCountProvider.future), 0);
    // Not one request: an endpoint that would 403 every thirty seconds is not asked (UI-10).
    expect(blind.requests, isEmpty);
  });

  testWidgets('the Automation entry carries the stuck count and opens the rules list',
      (tester) async {
    await _sized(tester);
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        ShellRoute(
          builder: (context, state, child) => AppShell(child: child),
          routes: [
            GoRoute(path: '/', builder: (_, __) => const SizedBox.shrink()),
            GoRoute(
                path: '/automation/rules',
                builder: (_, __) => const Center(child: Text('the rules list'))),
          ],
        ),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(_backend().dio),
        currentUserProvider.overrideWithValue(_user()),
        // The poller itself is exercised above; here it is a value, so the badge's wiring is what
        // is under test and no thirty-second timer outlives the widget tree.
        poisonedStepCountProvider.overrideWith((ref) => Stream.value(3)),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));
    await tester.pumpAndSettle();

    final pin = find.byTooltip('Keep the sidebar open');
    if (pin.evaluate().isNotEmpty) {
      await tester.tap(pin);
      await tester.pumpAndSettle();
    }
    expect(find.text('Automation'), findsOneWidget);
    expect(find.descendant(of: find.byType(Badge), matching: find.text('3')), findsOneWidget);

    await tester.tap(find.text('Automation'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/rules');
    expect(find.text('the rules list'), findsOneWidget);
  });

  // ------------------------------------------------------------------ the rules list

  testWidgets('the rules list says what each rule is about, when it fires and what it does',
      (tester) async {
    final backend = FakeBackend({
      'GET /api/table-schemas/automationRules': (_) => _rulesSchema(),
      'GET /api/automation/rules': (_) => _page([_rule()]),
      'GET /api/regions/my': (_) => {'allRegions': false, 'regions': const []},
    });
    await _pumpRules(tester, backend, user: _user());

    expect(find.text('Chase big overdue'), findsOneWidget);
    expect(find.text('Invoice'), findsOneWidget);
    // The honest schedule, in the units it is stored and fired in (A1).
    expect(find.text('Every Wednesday at 09:00 UTC'), findsOneWidget);
    expect(find.text('Create a task, Send an email'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'New rule'), findsOneWidget);
    expect(find.byTooltip('Run now'), findsOneWidget);

    // A reader who may only look is offered nothing that writes: AUTOMATION_MANAGE is what the
    // save, the delete and the bulk mapping are behind, and AUTOMATION_RUN is what running one
    // is behind — two separate privileges on purpose (A1, A5, AUTH-05, UI-10).
    await _pumpRules(tester, backend,
        user: _user(const {Privileges.automationView, Privileges.invoiceView}));
    expect(find.text('Chase big overdue'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'New rule'), findsNothing);
    expect(find.byTooltip('Run now'), findsNothing);
    expect(find.byType(Checkbox), findsNothing);
  });

  testWidgets('the rules list offers no export, because there is no mapping behind one',
      (tester) async {
    FakeBackend rulesBackend() => FakeBackend({
          'GET /api/table-schemas/automationRules': (_) => _rulesSchema(),
          'GET /api/automation/rules': (_) => _page([_rule()]),
          'GET /api/regions/my': (_) => {'allRegions': false, 'regions': const []},
        });

    // The shipped CASHIER shape: EXPORT_DATA and AUTOMATION_VIEW, nothing that writes.
    // DataTableScaffold's `canExport` made the table SELECTABLE on its own, so this reader got
    // checkboxes whose only action was POST /api/automation/rules/export — a mapping
    // AutomationController does not declare, answered 405 "POST is not supported here" (A1).
    final readerBackend = rulesBackend();
    await _pumpRules(tester, readerBackend,
        user: _user(const {
          Privileges.automationView,
          Privileges.invoiceView,
          Privileges.exportData,
        }));

    expect(find.text('Chase big overdue'), findsOneWidget);
    expect(find.byType(Checkbox), findsNothing,
        reason: 'nothing this reader can do to a rule, so nothing to select');
    expect(find.text('Export selected'), findsNothing);

    // And for somebody who DOES have bulk actions, the checkboxes are back and the button that
    // could only ever fail is still gone. A route the fake does not declare answers 404, so this
    // also proves nothing was posted to it.
    final managerBackend = rulesBackend();
    await _pumpRules(tester, managerBackend,
        user: _user(const {
          Privileges.automationView,
          Privileges.automationManage,
          Privileges.invoiceView,
          Privileges.exportData,
        }));
    await tester.tap(find.byType(Checkbox).last);
    await tester.pumpAndSettle();

    expect(find.text('1 selected'), findsOneWidget);
    expect(find.text('Switch off'), findsOneWidget);
    expect(find.text('Export selected'), findsNothing);
    expect(managerBackend.sent('POST /api/automation/rules/export'), isEmpty);
  });

  // ------------------------------------------------------------------ the provenance link

  testWidgets('a task a rule made links back to the rule, for somebody who may open it',
      (tester) async {
    Map<String, dynamic> task() => {
          'id': 9,
          'entityType': 'INVOICE',
          'entityId': 42,
          'entityLabel': 'INV-0042',
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'title': 'Chase the overdue balance',
          'notes': '',
          'dueDate': '2026-09-30',
          'status': 'OPEN',
          'overdue': false,
          'assignees': const <Map<String, dynamic>>[],
          'createdByUserId': null,
          'createdByRuleId': 7,
          'createdByStepId': 21,
          'completedByUserId': null,
          'completedAt': null,
          'createdAt': '2026-09-22T08:00:00Z',
          'updatedAt': '2026-09-22T08:00:00Z',
          'regionId': 1,
          'regionName': 'North',
        };

    Future<GoRouter> pump(CurrentUser user) async {
      final backend = FakeBackend({'GET /api/tasks/9': (_) => task()});
      await _sized(tester);
      final router = GoRouter(
        initialLocation: '/tasks/9',
        routes: [
          // Scaffold, because router.dart mounts these inside the AppShell's (D-71).
          GoRoute(
              path: '/tasks/9',
              builder: (_, __) => const Scaffold(body: TaskDetailScreen(id: 9))),
          GoRoute(path: '/tasks', builder: (_, __) => const SizedBox.shrink()),
          GoRoute(path: '/automation/rules/:id', builder: (_, __) => const SizedBox.shrink()),
        ],
      );
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(user),
        ],
        child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
      ));
      await tester.pumpAndSettle();
      return router;
    }

    // A-UI has declared /automation/rules/:id, so the provenance line is finally a LINK: it says
    // why somebody is being asked to do this, and nobody remembers asking (A6, A5).
    final router = await pump(_user());
    expect(find.text('Created by automation rule #7'), findsOneWidget);
    await tester.tap(find.widgetWithText(InkWell, 'Created by automation rule #7'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/rules/7');

    // But only for somebody who may open it: the route is behind AUTOMATION_VIEW, and offering a
    // link the router would bounce them off is worse than plain text (UI-10).
    final blind = await pump(_user(const {Privileges.taskView, Privileges.invoiceView}));
    expect(find.text('Created by automation rule #7'), findsOneWidget);
    expect(find.widgetWithText(InkWell, 'Created by automation rule #7'), findsNothing);
    expect(blind.routerDelegate.currentConfiguration.uri.path, '/tasks/9');
  });

  // ------------------------------------------------------------------ run now

  testWidgets('Run now shows what would happen before anything happens, and sends the same '
      'request id if it is pressed again', (tester) async {
    var fail = true;
    final backend = _backend(
      rows: const [],
      run: (o) {
        final apply = '${o.queryParameters['apply']}' == 'true';
        if (!apply) {
          return {
            'matched': 2,
            'truncated': false,
            'sample': [
              {
                'id': 42,
                'label': 'INV-0042',
                'renderedTitle': 'Chase Acme Ltd for ₹1,250.00',
                'renderedSubject': null,
              },
              {'id': 43, 'label': 'INV-0043', 'renderedTitle': null, 'renderedSubject': null},
            ],
          };
        }
        if (fail) {
          fail = false;
          return const FakeFailure(503, {'message': 'The engine is busy'});
        }
        return {
          'id': 11,
          'ruleId': 7,
          'ruleName': 'Chase big overdue',
          'source': 'MANUAL',
          'occasion': 'Mui1',
          'status': 'FANNING',
          'matched': 0,
          'truncated': false,
          'stepsPlanned': 0,
          'startedAt': '2026-09-23T10:00:00Z',
          'finishedAt': null,
          'error': null,
        };
      },
    );
    // The sheet, opened the way the rule page and the rules list both open it.
    await _sized(tester);
    final router = GoRouter(
      initialLocation: '/automation/rules/7',
      routes: [
        GoRoute(
          path: '/automation/rules/7',
          builder: (_, __) => Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => showRunNowSheet(context,
                    ruleId: 7, ruleName: 'Chase big overdue', enabled: true),
                child: const Text('Run now'),
              ),
            ),
          ),
        ),
        GoRoute(
          path: '/automation/activity',
          builder: (_, s) => const AutomationActivityPage(
              query: TableQuery(size: 20, sort: 'id,desc')),
        ),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_user()),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(TextButton, 'Run now'));
    await tester.pumpAndSettle();

    // A DRY RUN FIRST, ALWAYS: the first press writes nothing and says what would happen (A5).
    final dry = backend.sent('POST /api/automation/rules/7/run');
    expect(dry, hasLength(1));
    expect('${dry.single.queryParameters['apply']}', 'false');
    expect(find.text('2 records match right now.'), findsOneWidget);
    expect(find.text('Nothing has happened yet.'), findsOneWidget);
    expect(find.text('Task: Chase Acme Ltd for ₹1,250.00'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Run on 2 records'));
    await tester.pumpAndSettle();
    expect(find.text('The engine is busy'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Run on 2 records'));
    await tester.pumpAndSettle();

    final applied = backend
        .sent('POST /api/automation/rules/7/run')
        .where((r) => '${r.queryParameters['apply']}' == 'true')
        .toList();
    expect(applied, hasLength(2));
    // THE DOUBLE-CLICK GUARD. The occasion is "M" + requestId, so a second press carrying the
    // SAME id bounces off uk_run_occasion and answers 200 with the first run rather than opening
    // a second one. A fresh id per press would defeat it exactly (A5).
    final ids = applied.map((r) => (r.data as Map)['requestId']).toSet();
    expect(ids, hasLength(1));
    expect(ids.single, isNotEmpty);

    // And it lands on that run's own history, which is the activity list with one filter in the
    // URL and no second screen or endpoint (A5).
    expect(router.routerDelegate.currentConfiguration.uri.path, '/automation/activity');
    expect(router.routerDelegate.currentConfiguration.uri.queryParameters['f'], 'runId:eq:11');
  });
}
