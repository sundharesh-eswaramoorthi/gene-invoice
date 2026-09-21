import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/automation_models.dart';
import 'package:gene_invoice/features/automation/automation_rule_editor.dart';
import 'package:gene_invoice/features/automation/automation_rules_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

const _manager = CurrentUser(
  id: 3,
  username: 'jane',
  fullName: 'Jane Doe',
  role: 'ADMIN',
  privileges: {
    Privileges.automationView,
    Privileges.automationManage,
    Privileges.emailView,
    Privileges.emailSend,
  },
  customerId: null,
);

/// The same person, plus the privilege that puts an "Export selected" button on every other list.
/// Automation has no export endpoint behind one, so this must change nothing about the screen.
const _exporter = CurrentUser(
  id: 3,
  username: 'jane',
  fullName: 'Jane Doe',
  role: 'ADMIN',
  privileges: {
    Privileges.automationView,
    Privileges.automationManage,
    Privileges.exportData,
  },
  customerId: null,
);

/// One row of `GET /api/automation/rules/{id}/runs`.
Map<String, dynamic> _run({
  int id = 1,
  String status = 'SKIPPED',
  int entityId = 42,
  int attempts = 1,
  String? lastError = 'The filters did not match',
}) =>
    {
      'id': id,
      'ruleId': 1,
      'entityType': 'INVOICE',
      'entityId': entityId,
      'trigger': 'CREATED',
      'status': status,
      'attempts': attempts,
      'lastError': lastError,
      'enqueuedAt': '2026-09-17T10:15:00Z',
      'createdAt': '2026-09-17T10:15:00Z',
      'updatedAt': '2026-09-17T10:16:00Z',
    };

/// A table schema with the columns the rule form filters by. Invoices can be filtered by status;
/// customers cannot, which is what a rule moved from one to the other has to notice.
Map<String, dynamic> _schema(String entity, List<Map<String, dynamic>> columns) => {
      'entity': entity,
      'defaultSort': 'id,desc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today'],
      'columns': columns,
    };

Map<String, dynamic> _column(String name, String label, String type,
        {List<String> operators = const ['eq'], List<String> enumValues = const []}) =>
    {
      'name': name,
      'label': label,
      'type': type,
      'sortable': true,
      'filterable': true,
      'operators': operators,
      'enumValues': enumValues,
    };

Map<String, dynamic> _rule({
  int id = 1,
  String name = 'Chase unpaid invoices',
  bool enabled = true,
  String entityType = 'INVOICE',
  List<String> filters = const ['status:eq:UNPAID'],
  String action = 'CREATE_TASK',
  Map<String, dynamic>? actionSpec,
}) =>
    {
      'id': id,
      'name': name,
      'description': 'Nobody chases the small ones otherwise',
      'enabled': enabled,
      'entityType': entityType,
      'trigger': 'CREATED',
      'triggerLabel': 'is created',
      'filters': filters,
      'action': action,
      'actionLabel': 'create a task',
      'actionSpec': actionSpec ??
          {
            'title': 'Call the customer',
            'assignees': [
              {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
            ],
          },
      'runCount': 4,
      'lastRunAt': '2026-09-17T10:15:00Z',
    };

FakeBackend _backend({Map<String, Object? Function(dynamic)>? extra}) => FakeBackend({
      'GET /api/table-schemas/automation': (_) => _schema('automation', [
            _column('name', 'Name', 'TEXT', operators: ['contains']),
          ]),
      'GET /api/table-schemas/invoices': (_) => _schema('invoices', [
            _column('status', 'Status', 'ENUM',
                operators: ['eq', 'in'], enumValues: ['UNPAID', 'FULLY_PAID']),
            _column('total', 'Total', 'MONEY', operators: ['gt']),
          ]),
      'GET /api/table-schemas/customers': (_) => _schema('customers', [
            _column('name', 'Name', 'TEXT', operators: ['contains']),
          ]),
      'GET /api/automation/rules': (_) => {
            'content': [_rule()],
            'page': 0,
            'size': 20,
            'totalElements': 1,
            'totalPages': 1,
          },
      'GET /api/automation/rules/1': (_) => _rule(),
      'GET /api/emails/context': (_) => {
            'entityType': 'INVOICE',
            'delivery': {'configured': true},
            'sender': {
              'restricted': false,
              'self': {'userId': 3, 'name': 'Jane Doe', 'email': 'jane@company.com'},
            },
            'roles': [
              {
                'role': 'COLLECTION_POC',
                'label': 'Collection POC',
                'level': 'CUSTOMER',
                'levelLabel': 'Customer',
                'groupLabel': 'Customer level',
                'people': <Map<String, dynamic>>[],
              },
            ],
            'customerEmails': {'available': true, 'addresses': <Map<String, dynamic>>[]},
          },
      ...?extra,
    });

Future<void> _pump(WidgetTester tester, FakeBackend backend, Widget child,
    {Size size = const Size(1366, 1000), CurrentUser user = _manager}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp(theme: AppTheme.light(), home: child),
  ));
  await tester.pumpAndSettle();
}

Future<void> _openEditor(WidgetTester tester, FakeBackend backend,
    {int? ruleId, Size size = const Size(1366, 1000)}) async {
  await _pump(
    tester,
    backend,
    Scaffold(
      body: Builder(
        builder: (context) => Center(
          child: TextButton(
            onPressed: () => showAutomationRuleEditor(context, ruleId: ruleId),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  group('models', () {
    test('a rule reads back with its filters as the list page\'s own chips', () {
      final rule = AutomationRule.fromJson(_rule());
      expect(rule.entityType, AutomationRecordType.invoice);
      expect(rule.trigger, AutomationTrigger.created);
      expect(rule.action, AutomationAction.createTask);
      expect(rule.filterChips.single, const TableFilter('status', 'eq', ['UNPAID']));
      expect(rule.unreadableFilters, isEmpty);
      expect(rule.actionSpec.assignees.single.role, 'COLLECTION_POC');
      expect(rule.describe(null), 'When an invoice is created, where status is UNPAID, '
          'create a task');
    });

    test('a kind of record, a trigger or an action from a newer server reads as itself', () {
      final rule = AutomationRule.fromJson(_rule(entityType: 'SUBSCRIPTION'));
      expect(rule.entityType, isNull);
      expect(rule.recordLabel, 'Subscription');
    });

    test('a filter the app cannot read is kept and named, never dropped in silence', () {
      final rule = AutomationRule.fromJson(_rule(filters: ['nonsense']));
      expect(rule.filterChips, isEmpty);
      expect(rule.unreadableFilters, ['nonsense']);
    });

    test('Run now says the difference between matching nothing and doing nothing', () {
      expect(const AutomationRunResult().message, 'Nothing matches this rule right now');
      expect(const AutomationRunResult(matched: 3).message, 'All 3 records already had this done');
      expect(const AutomationRunResult(queued: 2, matched: 3).message, 'Queued 2 of 3 records');
      expect(const AutomationRunResult(queued: 5, matched: 5, cap: 5, capped: true).message,
          contains('narrow the filters'));
    });

    test('a dispute cannot be raised about a customer', () {
      expect(AutomationAction.createDispute.allowedOn(AutomationRecordType.customer), isFalse);
      expect(AutomationAction.createDispute.allowedOn(AutomationRecordType.invoice), isTrue);
    });
  });

  group('the rules list', () {
    testWidgets('a row reads as the rule: when, where, then', (tester) async {
      final backend = _backend();
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')));

      expect(find.text('Chase unpaid invoices'), findsOneWidget);
      expect(find.text('Invoice'), findsOneWidget);
      expect(find.text('is created'), findsOneWidget);
      // The filter reads in the words its own list page uses, from that list's schema.
      expect(find.text('where Status is UNPAID'), findsOneWidget);
      expect(find.text('create a task'), findsOneWidget);
      expect(find.text('Call the customer'), findsOneWidget);
      expect(find.text('On'), findsOneWidget);
      expect(find.text('4'), findsOneWidget);
    });

    testWidgets('Run now asks first, then says what it queued', (tester) async {
      final backend = _backend(extra: {
        'POST /api/automation/rules/1/run': (_) =>
            {'queued': 2, 'matched': 3, 'cap': 500, 'capped': false},
      });
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')));

      await tester.tap(find.byTooltip('Run now'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/automation/rules/1/run'), isEmpty);

      await tester.tap(find.text('Run now').last);
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/automation/rules/1/run'), hasLength(1));
      expect(find.text('Queued 2 of 3 records'), findsOneWidget);
    });

    testWidgets('there is nothing to export, so nothing to select either', (tester) async {
      final backend = _backend();
      // Given to somebody who may export everywhere else: the flag is not the privilege's to
      // decide here, because there is no POST /api/automation/rules/export behind it. It was also
      // the only thing switching row selection on — automation has no bulk action — so a tick box
      // led to one button that could only 404.
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')),
          user: _exporter);

      expect(find.text('Chase unpaid invoices'), findsOneWidget);
      expect(find.byType(Checkbox), findsNothing);
      expect(find.text('Export selected'), findsNothing);
    });

    testWidgets('a rule that is off cannot be run from the row', (tester) async {
      final backend = _backend(extra: {
        'GET /api/automation/rules': (_) => {
              'content': [_rule(enabled: false)],
              'page': 0,
              'size': 20,
              'totalElements': 1,
              'totalPages': 1,
            },
      });
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')));

      expect(find.text('Off'), findsOneWidget);
      final run = tester.widget<IconButton>(find.ancestor(
        of: find.byTooltip('Switch the rule on before running it'),
        matching: find.byType(IconButton),
      ));
      expect(run.onPressed, isNull);
    });
  });

  group('the rule editor', () {
    testWidgets('the three sections are named as the rule reads', (tester) async {
      await _openEditor(tester, _backend());
      expect(find.text('WHEN'), findsOneWidget);
      expect(find.text('WHERE'), findsOneWidget);
      expect(find.text('THEN'), findsOneWidget);
      // A rule with no filters acts on everything, and says so rather than leaving it to be found.
      expect(find.text('No filters: this acts on every invoice.'), findsOneWidget);
    });

    testWidgets('an existing rule opens on what the server holds now', (tester) async {
      final backend = _backend();
      await _openEditor(tester, backend, ruleId: 1);
      expect(backend.sent('GET /api/automation/rules/1'), hasLength(1));
      expect(find.widgetWithText(TextField, 'Chase unpaid invoices'), findsOneWidget);
      expect(find.widgetWithText(InputChip, 'Status is UNPAID'), findsOneWidget);
      expect(find.widgetWithText(TextField, 'Call the customer'), findsOneWidget);
    });

    testWidgets('moving the rule to another record drops filters it cannot keep, and says so',
        (tester) async {
      final backend = _backend();
      await _openEditor(tester, backend, ruleId: 1);
      expect(find.widgetWithText(InputChip, 'Status is UNPAID'), findsOneWidget);

      await tester.tap(find.text('Invoice').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Customer').last);
      await tester.pumpAndSettle();

      expect(find.widgetWithText(InputChip, 'Status is UNPAID'), findsNothing);
      expect(find.textContaining('Customers cannot be filtered by status is UNPAID'),
          findsOneWidget);
    });

    testWidgets('a dispute rule cannot be left pointed at a customer', (tester) async {
      final backend = _backend(extra: {
        'GET /api/automation/rules/1': (_) => _rule(
              action: 'CREATE_DISPUTE',
              filters: const [],
              actionSpec: {'body': 'Raised by rule', 'assignees': <Map<String, dynamic>>[]},
            ),
      });
      await _openEditor(tester, backend, ruleId: 1);
      expect(find.text('Raise a dispute'), findsOneWidget);

      await tester.tap(find.text('Invoice').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Customer').last);
      await tester.pumpAndSettle();

      expect(find.text('Create a task'), findsOneWidget);
      expect(find.textContaining('cannot be done to a customer'), findsOneWidget);
    });

    testWidgets('saving sends the whole rule, with the filters as list-page chips',
        (tester) async {
      final backend = _backend(extra: {
        'POST /api/automation/rules': (_) => _rule(id: 9, name: 'New rule'),
      });
      await _openEditor(tester, backend);

      await tester.enterText(
          find.widgetWithText(TextField, 'Name *'), 'Chase the big ones');
      await tester.enterText(
          find.widgetWithText(TextField, 'Task title *'), 'Call the customer');
      // The invoices list's own filter editor, against the invoices schema.
      await tester.tap(find.text('Add filter'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(DropdownButtonFormField<ColumnDef>, 'Status'), findsOneWidget);
      await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Value'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('UNPAID').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(InputChip, 'Status is UNPAID'), findsOneWidget);

      await tester.tap(find.text('Create rule'));
      await tester.pumpAndSettle();

      final posted = backend.sent('POST /api/automation/rules').single.data as Map;
      expect(posted['name'], 'Chase the big ones');
      expect(posted['entityType'], 'INVOICE');
      expect(posted['trigger'], 'CREATED');
      expect(posted['action'], 'CREATE_TASK');
      expect(posted['filters'], ['status:eq:UNPAID']);
      expect((posted['actionSpec'] as Map)['title'], 'Call the customer');
    });

    testWidgets('a rule the server refuses says what the server said', (tester) async {
      final backend = _backend(extra: {
        'POST /api/automation/rules': (_) =>
            const FakeFailure(400, {'message': "Another rule is already called 'Chase'"}),
      });
      await _openEditor(tester, backend);

      await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Chase');
      await tester.enterText(
          find.widgetWithText(TextField, 'Task title *'), 'Call the customer');
      await tester.tap(find.text('Create rule'));
      await tester.pumpAndSettle();

      expect(find.text("Another rule is already called 'Chase'"), findsOneWidget);
    });

    testWidgets('every action\'s fields fit a phone without overflowing', (tester) async {
      await _openEditor(tester, _backend(), size: const Size(400, 820));
      for (final action in const [
        'Record a promise',
        'Raise a dispute',
        'Send an email',
        'Create a task',
      ]) {
        final picker = find.byType(DropdownButtonFormField<AutomationAction>);
        await tester.ensureVisible(picker);
        await tester.pumpAndSettle();
        await tester.tap(picker);
        await tester.pumpAndSettle();
        await tester.tap(find.text(action).last);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull, reason: action);
      }
    });

    testWidgets('an email rule needs a recipient, and can address the customer itself',
        (tester) async {
      final backend = _backend(extra: {
        'POST /api/automation/rules': (_) => _rule(id: 9, action: 'SEND_EMAIL'),
      });
      await _openEditor(tester, backend);

      await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Remind on overdue');
      await tester.tap(find.text('Create a task').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Send an email').last);
      await tester.pumpAndSettle();

      await tester.enterText(find.widgetWithText(TextField, 'Subject *'), 'Payment reminder');
      await tester.tap(find.text('Create rule'));
      await tester.pumpAndSettle();
      expect(find.text('An email rule needs at least one recipient'), findsOneWidget);
      expect(backend.sent('POST /api/automation/rules'), isEmpty);

      final customerBox = find.text("Also the customer's own email addresses");
      await tester.ensureVisible(customerBox);
      await tester.pumpAndSettle();
      await tester.tap(customerBox);
      await tester.pumpAndSettle();

      // A rule has no caller to send as, so the sender is not optional the way a compose form's
      // From is: the form says so before the server has to.
      await tester.tap(find.text('Create rule'));
      await tester.pumpAndSettle();
      expect(find.text('An email rule needs a sender: pick who it goes out as'), findsOneWidget);
      expect(backend.sent('POST /api/automation/rules'), isEmpty);

      final fromField = find.byType(DropdownButton<String>);
      await tester.ensureVisible(fromField);
      await tester.pumpAndSettle();
      await tester.tap(fromField);
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('Collection POC').last);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Create rule'));
      await tester.pumpAndSettle();

      final posted = backend.sent('POST /api/automation/rules').single.data as Map;
      expect((posted['actionSpec'] as Map)['assignees'], [
        {'type': 'CUSTOMER'},
      ]);
      expect((posted['actionSpec'] as Map)['from'],
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'});
    });
  });

  // The row says when the rule last *did* something and how often, and the worker moves neither
  // on any other path: a rule that is asked about a hundred records and skips every one reads
  // there exactly like a rule nothing has ever matched. The runs are where the difference is.
  group('why a rule is doing nothing', () {
    test('a run reads back with the reason the server gave it', () {
      final run = AutomationRuleRun.fromJson(_run(status: 'FAILED', attempts: 3,
          lastError: 'Nobody holds Collection POC on Acme Ltd'));
      expect(run.status, AutomationRunStatus.failed);
      expect(run.statusLabel, 'Failed');
      expect(run.recordLabel, 'Invoice #42');
      expect(run.attempts, 3);
      expect(run.lastError, 'Nobody holds Collection POC on Acme Ltd');
      expect(run.inProgress, isFalse);
    });

    test('a status this build has not heard of still reads as the server\'s own word', () {
      final run = AutomationRuleRun.fromJson(_run(status: 'ABANDONED'));
      expect(run.status, isNull);
      expect(run.statusLabel, 'Abandoned');
    });

    testWidgets('the row opens what the rule has been asked, and what came of each',
        (tester) async {
      final backend = _backend(extra: {
        'GET /api/automation/rules/1/runs': (_) => [
              _run(),
              _run(id: 2, entityId: 43, status: 'FAILED', attempts: 3,
                  lastError: 'Nobody holds Collection POC on Acme Ltd'),
              _run(id: 3, entityId: 44, status: 'DONE', lastError: null),
            ],
      });
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')));
      // The row itself counts only what the rule did: four runs, and not one of these skips.
      expect(find.text('4'), findsOneWidget);

      await tester.tap(find.byTooltip('Run history'));
      await tester.pumpAndSettle();

      expect(find.text('Runs of "Chase unpaid invoices"'), findsOneWidget);
      expect(find.text('Skipped'), findsOneWidget);
      // A skip is an answer, and it says which answer.
      expect(find.text('The filters did not match'), findsOneWidget);
      expect(find.text('Failed'), findsOneWidget);
      expect(find.text('Nobody holds Collection POC on Acme Ltd'), findsOneWidget);
      expect(find.text('after 3 attempts'), findsOneWidget);
      expect(find.text('Done'), findsOneWidget);
      expect(find.text('Invoice #43'), findsOneWidget);
    });

    testWidgets('a rule nothing has set off says that, not that it failed', (tester) async {
      final backend = _backend(extra: {
        'GET /api/automation/rules/1/runs': (_) => <Map<String, dynamic>>[],
      });
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')));

      await tester.tap(find.byTooltip('Run history'));
      await tester.pumpAndSettle();
      expect(
          find.text('Nothing has set this rule off yet, so it has not been asked about any '
              'invoice.'),
          findsOneWidget);
    });

    testWidgets('somebody who may only read rules can still see them', (tester) async {
      final backend = _backend(extra: {
        'GET /api/automation/rules/1/runs': (_) => [_run()],
      });
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')),
          user: const CurrentUser(
            id: 4,
            username: 'ravi',
            fullName: 'Ravi Viewer',
            role: 'VIEWER',
            privileges: {Privileges.automationView},
            customerId: null,
          ));

      // Reading what a rule has been up to makes nothing happen, so it is AUTOMATION_VIEW, like
      // the endpoint. Everything that writes or runs is still gone from the row.
      expect(find.byTooltip('Edit'), findsNothing);
      expect(find.byTooltip('Run now'), findsNothing);
      await tester.tap(find.byTooltip('Run history'));
      await tester.pumpAndSettle();
      expect(find.text('Skipped'), findsOneWidget);
    });

    testWidgets('Run now asks the runs again, since that is what puts rows there', (tester) async {
      final backend = _backend(extra: {
        'GET /api/automation/rules/1/runs': (_) => [_run()],
        'POST /api/automation/rules/1/run': (_) =>
            {'queued': 1, 'matched': 1, 'cap': 500, 'capped': false},
      });
      await _pump(tester, backend,
          const AutomationRulesScreen(query: TableQuery(size: 20, sort: 'id,desc')));

      await tester.tap(find.byTooltip('Run history'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/automation/rules/1/runs'), hasLength(1));
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Run now'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Run now').last);
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Run history'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/automation/rules/1/runs'), hasLength(2));
    });
  });
}
