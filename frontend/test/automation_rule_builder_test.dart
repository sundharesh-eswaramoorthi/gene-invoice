import 'dart:async';

import 'package:dio/dio.dart';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/reference_picker.dart';
import 'package:gene_invoice/core/table/table_models.dart' show ColumnDef;
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/automation_providers.dart';
import 'package:gene_invoice/features/automation/rule_detail_screen.dart';
import 'package:gene_invoice/features/automation/rule_form_screen.dart';
import 'package:gene_invoice/features/email/email_actions.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/role_token_field.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _north =
    RegionGrant(id: 1, code: 'NORTH', name: 'North', rights: {regionRightView, regionRightManage});
const _south = RegionGrant(id: 2, code: 'SOUTH', name: 'South', rights: {regionRightView});

const _author = {
  Privileges.automationView,
  Privileges.automationManage,
  Privileges.automationRun,
  Privileges.invoiceView,
  Privileges.emailView,
  Privileges.emailSend,
};

CurrentUser _user([Set<String> privileges = _author]) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: 'CASHIER',
      privileges: privileges,
      customerId: null,
      regions: const [_north, _south],
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

/// GET /api/table-schemas/invoices, close enough to what TableSchemas.INVOICES publishes that a
/// chip this builder composes is one the server would accept. `balance` leads because the filter
/// editor opens on the first filterable column (A2).
Map<String, dynamic> _invoicesSchema() => {
      'entity': 'invoices',
      'defaultSort': 'invoiceDate,desc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today', 'last7Days', 'past'],
      'columns': [
        _column('balance', 'Balance', 'MONEY',
            operators: ['gt', 'gte', 'lt', 'lte', 'eq', 'between'], sortable: true),
        _column('id', 'Id', 'NUMBER'),
        _column('status', 'Status', 'ENUM',
            operators: ['eq', 'in'],
            enumValues: ['UNPAID', 'PARTIALLY_PAID', 'FULLY_PAID', 'CANCELLED']),
        _column('overdue', 'Overdue', 'BOOLEAN'),
        _column('customerId', 'Customer', 'REFERENCE', referenceKind: 'customer'),
        _column('dueDate', 'Due', 'DATE', operators: ['eq', 'lte', 'between', 'relative']),
      ],
    };

Map<String, dynamic> _customersSchema() => {
      'entity': 'customers',
      'defaultSort': 'name,asc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today'],
      'columns': [
        _column('name', 'Name', 'TEXT', operators: ['contains', 'eq']),
        _column('id', 'Id', 'NUMBER'),
      ],
    };

const _bob = {'userId': 12, 'name': 'Bob Smith', 'email': 'bob@company.com'};
const _divya = {'userId': 15, 'name': 'Divya Nair', 'email': 'divya@company.com'};

/// email_compose_test.dart's `_context` shape, kept deliberately identical: the two role pickers
/// are now ONE widget and the fixture that proves it must be the same fixture (A3, L1).
Map<String, dynamic> _context({bool withRecord = false}) {
  Map<String, dynamic> role(
      String key, String label, String levelLabel, List<Map<String, dynamic>> holders) {
    final seen = withRecord ? holders : const <Map<String, dynamic>>[];
    return {
      'role': key,
      'label': label,
      'level': levelLabel == 'Customer' ? 'CUSTOMER' : 'RECORD',
      'levelLabel': levelLabel,
      'groupLabel': '$levelLabel level',
      'resolved': withRecord ? holders.isNotEmpty : null,
      'people': seen,
      'sender': seen.isEmpty ? null : seen.first,
    };
  }

  return {
    'entityType': 'INVOICE',
    'entityId': withRecord ? 42 : null,
    'entityLabel': withRecord ? 'Invoice INV-0042' : null,
    'entityLink': withRecord ? '/invoices/42' : null,
    'delivery': {'configured': false},
    'sender': {
      'restricted': false,
      'self': {'userId': 3, 'name': 'Jane Doe', 'email': 'me@x.com'},
    },
    'roles': [
      role('SALES_POC', 'Sales POC', 'Customer', const []),
      role('CUSTOMER_SUCCESS_POC', 'Customer Success POC', 'Customer', const []),
      role('COLLECTION_POC', 'Collection POC', 'Customer', const [_bob]),
      role('SALES_POC', 'Sales POC', 'Invoice', const [_divya]),
    ],
    'customerEmails': {
      'available': true,
      'addresses': withRecord
          ? [
              {'name': 'Acme Ltd', 'address': 'ap@acme.com'},
            ]
          : [],
    },
    'suggestion': null,
  };
}

/// Both levels, because PRD A4 asks for both in the subject AND in the body.
List<Map<String, dynamic>> _placeholders() => [
      {
        'key': '{{Customer.Name}}',
        'label': 'Name',
        'group': 'Customer level',
        'type': 'TEXT',
        'example': 'Acme Ltd',
      },
      {
        'key': '{{Role.CUSTOMER.COLLECTION_POC.Name}}',
        'label': 'Collection POC - Name',
        'group': 'Customer level',
        'type': 'TEXT',
        'example': 'Sam Sales',
      },
      {
        'key': '{{Invoice.Balance}}',
        'label': 'Balance',
        'group': 'Invoice level',
        'type': 'MONEY',
        'example': '₹1,250.00',
      },
    ];

Map<String, dynamic> _savedRule(Map<String, dynamic> body) => {
      'id': 7,
      'name': body['name'],
      'description': body['description'],
      'subjectType': body['subjectType'],
      'triggerKind': body['triggerKind'],
      'scheduleHourUtc': body['scheduleHourUtc'],
      'scheduleDayOfWeek': body['scheduleDayOfWeek'],
      'conditions': body['conditions'],
      'actions': body['actions'],
      'cooldownDays': body['cooldownDays'],
      'enabled': true,
      'definitionVersion': 1,
      'regions': const [],
      'allAuthorRegions': true,
      'nextRunAt': null,
      'lastRunAt': null,
      'createdByUserId': 3,
      'createdByName': 'Jane Doe',
      'createdAt': '2026-09-23T08:00:00Z',
      'updatedAt': '2026-09-23T08:00:00Z',
      'version': 0,
    };

/// Every route the builder touches. A route left out answers 404 through the fake, which is a
/// silent wrong answer rather than a failure, so they are all declared in one place.
FakeBackend _backend({
  Object? Function(RequestOptions)? preview,
  Object? Function(RequestOptions)? create,
}) =>
    FakeBackend({
      'GET /api/table-schemas/invoices': (_) => _invoicesSchema(),
      'GET /api/table-schemas/customers': (_) => _customersSchema(),
      'GET /api/emails/context': (_) => _context(),
      'GET /api/automation/placeholders': (_) => _placeholders(),
      'GET /api/regions/my': (_) => {
            'allRegions': false,
            'regions': [
              {'id': 1, 'code': 'NORTH', 'name': 'North', 'rights': ['VIEW', 'MANAGE']},
              {'id': 2, 'code': 'SOUTH', 'name': 'South', 'rights': ['VIEW']},
            ],
          },
      'GET /api/invoices': (_) => {
            'content': [
              {'id': 42, 'invoiceNumber': 'INV-0042', 'customerName': 'Acme Ltd'},
            ],
            'page': 0,
            'size': 20,
            'totalElements': 1,
            'totalPages': 1,
            'sort': null,
            'appliedFilters': const <String>[],
            'lockedFilters': const <String>[],
          },
      'GET /api/customers': (_) => {
            'content': [
              {'id': 5, 'name': 'Acme Ltd', 'email': 'ap@acme.com'},
            ],
            'page': 0,
            'size': 20,
            'totalElements': 1,
            'totalPages': 1,
            'sort': null,
            'appliedFilters': const <String>[],
            'lockedFilters': const <String>[],
          },
      'POST /api/automation/preview': preview ??
          (o) {
            final body = (o.data as Map).cast<String, dynamic>();
            return {
              'subjectType': 'INVOICE',
              'subjectId': body['subjectId'],
              'subjectLabel': 'INV-0042',
              'asOf': '2026-09-23',
              'title': body['title'] == null
                  ? null
                  : {'text': 'RENDERED:${body['title']}', 'unresolved': const <String>[]},
              'subject': body['subject'] == null
                  ? null
                  : {'text': 'RENDERED:${body['subject']}', 'unresolved': const <String>[]},
              'body': null,
            };
          },
      'POST /api/automation/rules':
          create ?? ((o) => _savedRule((o.data as Map).cast<String, dynamic>())),
      'GET /api/automation/rules/7': (_) => _storedRule(),
      'PUT /api/automation/rules/7': (o) => _savedRule((o.data as Map).cast<String, dynamic>()),
    });

/// One rule as GET /api/automation/rules/7 answers it: a saved AND/OR tree, two actions and a
/// weekly schedule, so re-opening the builder has something real to seed from (A1, A2).
Map<String, dynamic> _storedRule() => {
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
          {'filter': 'status:in:UNPAID,PARTIALLY_PAID'},
          {
            'op': 'OR',
            'of': [
              {'filter': 'balance:gt:50000'},
              {'filter': 'balance:gt:1000'},
            ],
          },
        ],
      },
      'actions': [
        {
          'kind': 'CREATE_TASK',
          'title': 'Chase {{Customer.Name}}',
          'notes': '',
          'assignees': [
            {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
          ],
          'dueInDays': 3,
        },
      ],
      'cooldownDays': 14,
      'enabled': true,
      'definitionVersion': 4,
      'regions': [
        {'id': 1, 'code': 'NORTH', 'name': 'North'},
      ],
      'allAuthorRegions': false,
      'nextRunAt': '2026-09-30T09:00:00Z',
      'lastRunAt': '2026-09-16T09:00:00Z',
      'createdByUserId': 3,
      'createdByName': 'Jane Doe',
      'createdAt': '2026-08-01T08:00:00Z',
      'updatedAt': '2026-09-01T08:00:00Z',
      'version': 2,
    };

Future<void> _sized(WidgetTester tester, [Size size = const Size(1400, 2400)]) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

Future<GoRouter> _pumpBuilder(WidgetTester tester, FakeBackend backend,
    {CurrentUser? user, String location = '/automation/rules/new'}) async {
  await _sized(tester);
  final router = GoRouter(
    initialLocation: location,
    routes: [
      GoRoute(path: '/automation/rules', builder: (_, __) => const SizedBox.shrink()),
      GoRoute(path: '/automation/rules/new', builder: (_, __) => const RuleFormScreen()),
      // ?edit=true is the BUILDER on this rule and a plain open is the detail page — the same
      // shape router.dart declares, so this test drives the real route and not a stand-in.
      GoRoute(
        path: '/automation/rules/:id',
        builder: (_, s) => s.uri.queryParameters['edit'] == 'true'
            ? RuleEditGate(id: int.parse(s.pathParameters['id']!))
            : const SizedBox.shrink(),
      ),
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

/// Opens the filter editor from the FIRST "Add condition" button in tree order, which is the
/// INNERMOST group's: a group card renders its children before its own add row.
Future<void> _addCondition(WidgetTester tester,
    {String? column, String? operator, String? value}) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Add condition').first);
  await tester.pumpAndSettle();
  if (column != null) {
    await tester.tap(find.byType(DropdownButtonFormField<ColumnDef>));
    await tester.pumpAndSettle();
    await tester.tap(find.text(column).last);
    await tester.pumpAndSettle();
  }
  if (operator != null) {
    await tester.tap(find.byType(DropdownButtonFormField<String>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text(operator).last);
    await tester.pumpAndSettle();
  }
  if (value != null) {
    await tester.enterText(find.widgetWithText(TextField, 'Value'), value);
    await tester.pumpAndSettle();
  }
  await tester.tap(find.widgetWithText(FilledButton, 'Apply'));
  await tester.pumpAndSettle();
}

Future<Map<String, dynamic>> _save(WidgetTester tester, FakeBackend backend) async {
  await tester.tap(find.widgetWithText(FilledButton, 'Save'));
  await tester.pumpAndSettle();
  return (backend.sent('POST /api/automation/rules').last.data as Map).cast<String, dynamic>();
}

/// `Conditions.walk` re-implemented over the WIRE shape, so the assertion above is the server's
/// own arithmetic and not a second copy of the client's: the root group is depth 1, every child is
/// charged one more level, and a LEAF is charged its own level too. A tree deeper than
/// `Conditions.MAX_DEPTH` (5) is one the save refuses (A2).
int _serverWalkDepth(Object? node, [int depth = 1]) {
  if (node is! Map) return depth;
  final children = node['of'];
  if (children is! List) return depth;
  var deepest = depth;
  for (final child in children) {
    final d = _serverWalkDepth(child, depth + 1);
    if (d > deepest) deepest = d;
  }
  return deepest;
}

void main() {
  // ------------------------------------------------------------------ the condition tree (A2)

  testWidgets('two conditions on the same column survive inside an Any-of group', (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    await tester.enterText(find.widgetWithText(TextFormField, 'Name *'), 'Big and late');
    await tester.pumpAndSettle();

    // Any of, then two conditions that differ only in their value. THIS is the case
    // TableQuery.addFilter would silently eat: it de-dupes by (field, operator), so a flat chip
    // row cannot express `balance > 1000 OR balance > 5000` at all (A2).
    await tester.tap(find.widgetWithText(SegmentedButton<Connector>, 'Any of'));
    await tester.pumpAndSettle();
    await _addCondition(tester, value: '1000');
    await _addCondition(tester, value: '5000');

    expect(find.text('Balance greater than 1000'), findsOneWidget);
    expect(find.text('Balance greater than 5000'), findsOneWidget);

    final body = await _save(tester, backend);
    // The EXACT wire shape ConditionJson reads, with both leaves intact and the connector the
    // header shows. This is the client/server boundary and the whole reason the tree is its own
    // model (A2).
    expect(body['conditions'], {
      'op': 'OR',
      'of': [
        {'filter': 'balance:gt:1000'},
        {'filter': 'balance:gt:5000'},
      ],
    });
    expect(body['subjectType'], 'INVOICE');
  });

  testWidgets('adding a condition reuses the filter bar\'s own editor for every column type',
      (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    await tester.tap(find.widgetWithText(OutlinedButton, 'Add condition'));
    await tester.pumpAndSettle();

    // It is the filter bar's dialog, not a second one: same title, same Column/Condition
    // dropdowns, same Apply (A2).
    expect(find.text('Add filter'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Apply'), findsOneWidget);

    // MONEY opens on a numeric box.
    expect(find.widgetWithText(TextField, 'Value'), findsOneWidget);

    // ENUM gets the dropdown of the server's own constants — not one value widget is written in
    // the condition editor (A2).
    await tester.tap(find.byType(DropdownButtonFormField<ColumnDef>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Status').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>).last);
    await tester.pumpAndSettle();
    expect(find.text('PARTIALLY_PAID'), findsWidgets);
    await tester.tap(find.text('PARTIALLY_PAID').last);
    await tester.pumpAndSettle();

    // BOOLEAN gets Yes/No.
    await tester.tap(find.byType(DropdownButtonFormField<ColumnDef>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Overdue').last);
    await tester.pumpAndSettle();
    expect(find.text('Yes'), findsWidgets);

    // REFERENCE gets the ReferencePicker, which is what makes a customer condition say a name
    // rather than an id (A2, B1).
    await tester.tap(find.byType(DropdownButtonFormField<ColumnDef>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Customer').last);
    await tester.pumpAndSettle();
    expect(
        find.descendant(
            of: find.byType(AlertDialog), matching: find.byType(ReferencePicker)),
        findsOneWidget);

    await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
    await tester.pumpAndSettle();
  });

  testWidgets('the builder stops nesting at the deepest group the server would accept a condition in',
      (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);
    await tester.enterText(find.widgetWithText(TextFormField, 'Name *'), 'Deeply nested');
    await tester.pumpAndSettle();

    Finder addGroup() => find.widgetWithText(OutlinedButton, 'Add group');
    // First in tree order is the innermost group: a card renders its children before its own
    // add row.
    bool innermostEnabled() =>
        tester.widget<OutlinedButton>(addGroup().first).onPressed != null;

    // Conditions.walk starts the ROOT group at depth 1 and charges every child its own level,
    // INCLUDING a leaf — so a condition inside a group at depth D is walked at D + 1, and four
    // groups is the most that can hold one. The root plus three presses.
    for (var i = 1; i <= 3; i++) {
      expect(innermostEnabled(), isTrue, reason: 'a group at depth $i may still nest');
      await tester.tap(addGroup().first);
      await tester.pumpAndSettle();
    }

    // A fifth group could hold NOTHING: a condition in it is walked at 6 and refused with
    // "Conditions may not be nested more than 5 deep", and leaving it empty is refused with "A
    // group needs at least one condition". The builder used to offer exactly that card (A2).
    expect(innermostEnabled(), isFalse);
    expect(find.byTooltip('Conditions may not be nested more than 5 deep'), findsOneWidget);

    // A condition may still be added at the deepest level — it is the NESTING that is capped.
    expect(
        tester.widget<OutlinedButton>(
            find.widgetWithText(OutlinedButton, 'Add condition').first).onPressed,
        isNotNull);
    await _addCondition(tester, value: '1000');

    // AND THE BODY IS ONE THE SERVER WOULD ACCEPT, measured the way the SERVER measures it rather
    // than by counting the taps again: this is the boundary the old test did not cross, so it
    // locked in the builder's arithmetic instead of checking it against the save's (A2).
    final body = await _save(tester, backend);
    expect(_serverWalkDepth(body['conditions']), lessThanOrEqualTo(5));
    expect(_serverWalkDepth(body['conditions']), 5,
        reason: 'the builder should offer every level the save allows, and no more');
  });

  testWidgets('changing the subject clears the conditions only after a confirm', (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    await _addCondition(tester, value: '1000');
    expect(find.text('Balance greater than 1000'), findsOneWidget);

    Future<void> chooseCustomers() async {
      await tester.tap(find.byType(DropdownButtonFormField<AutomationSubject>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('customers').last);
      await tester.pumpAndSettle();
    }

    await chooseCustomers();
    expect(find.text('Change the subject to customers?'), findsOneWidget);

    // Backing out changes NOTHING — not the subject and not the tree (A1).
    await tester.tap(find.widgetWithText(TextButton, 'Keep what I have'));
    await tester.pumpAndSettle();
    expect(find.text('Balance greater than 1000'), findsOneWidget);

    await chooseCustomers();
    await tester.tap(find.widgetWithText(FilledButton, 'Change and clear'));
    await tester.pumpAndSettle();

    // Every condition named a column on the invoices table, so keeping them would have produced
    // a rule the server refuses in words about columns the author can no longer see (A1, A2).
    expect(find.text('Balance greater than 1000'), findsNothing);
    expect(backend.sent('GET /api/table-schemas/customers'), isNotEmpty);
  });

  // ------------------------------------------------------------------ placeholders (A4)

  testWidgets('a placeholder chip is inserted at the cursor and the preview follows the last '
      'edit only', (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    final title = find.widgetWithText(TextField, 'Task title');
    await tester.enterText(title, 'Dear , please pay');
    await tester.pumpAndSettle();

    // The caret goes right after "Dear ", which is where somebody writing this sentence left it.
    final controller = tester.widget<TextField>(title).controller!;
    controller.selection = const TextSelection.collapsed(offset: 5);
    await tester.pump();

    // The title's button, not the notes' — both fields carry one (A4).
    await tester.tap(find.byTooltip('Insert a placeholder').first);
    await tester.pumpAndSettle();

    // PRD A4: BOTH levels are offered, in the server's own group names — the very names the To
    // field uses for the same two levels. Scoped to the sheet, because the assignee field behind
    // it groups by those same two names, which is the point (A4, L4).
    final sheet = find.byType(BottomSheet);
    expect(find.descendant(of: sheet, matching: find.text('Customer level')), findsOneWidget);
    expect(find.descendant(of: sheet, matching: find.text('Invoice level')), findsOneWidget);
    expect(find.descendant(of: sheet, matching: find.text('Balance')), findsOneWidget);

    await tester.tap(find.widgetWithText(ActionChip, 'Name'));
    await tester.pumpAndSettle();

    expect(controller.text, 'Dear {{Customer.Name}}, please pay');
    // And the caret is after what was inserted, so the next keystroke continues the sentence.
    expect(controller.selection.baseOffset, 5 + '{{Customer.Name}}'.length);

    // ---- the preview: one request per pause, carrying the LAST text ----
    // One record to render against. The preview reads the LIVE record, which is why the panel
    // says "values as of today" rather than pretending to be historical (A4, B3).
    final record = find.widgetWithText(ListTile, 'INV-0042');
    await tester.ensureVisible(record);
    await tester.pumpAndSettle();
    await tester.tap(record);
    // Past the 400 ms debounce: nothing is rendered until the typing stops (A4).
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    expect(backend.sent('POST /api/automation/preview'), isNotEmpty);

    final before = backend.sent('POST /api/automation/preview').length;
    await tester.enterText(title, 'First draft');
    await tester.pump(const Duration(milliseconds: 100));
    await tester.enterText(title, 'Second draft');
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    final after = backend.sent('POST /api/automation/preview');
    // ONE more, not two: the 400 ms debounce is what stops a nine-word title being nine renders,
    // and the one that is sent carries the text as it finally stands (A4).
    expect(after.length, before + 1);
    expect((after.last.data as Map)['title'], 'Second draft');
    expect(find.text('RENDERED:Second draft'), findsOneWidget);

    // ---- and a render that lost the race is dropped, not shown ----
    final gate = Completer<void>();
    backend.held['POST /api/automation/preview'] = gate.future;
    await tester.enterText(title, 'Third draft');
    await tester.pump(const Duration(milliseconds: 500));

    // The record goes while that render is still in flight. `_runPreview` bumps the sequence and
    // clears the panel, so the answer that arrives afterwards belongs to a question nobody is
    // asking any more (A4).
    await tester.tap(find.descendant(
        of: find.byType(Chip), matching: find.byIcon(Icons.cancel)));
    await tester.pump(const Duration(milliseconds: 500));
    gate.complete();
    await tester.pumpAndSettle();

    expect(find.text('RENDERED:Third draft'), findsNothing);
    expect(find.text('Pick a record above.'), findsOneWidget);
  });

  // ------------------------------------------------------------------ the extracted To field

  testWidgets('the role token field still groups people by level in the email dialog it came '
      'from', (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    await tester.enterText(find.widgetWithText(TextFormField, 'Name *'), 'Chase the POC');
    await tester.pumpAndSettle();

    // The rule builder addresses a task's assignees through the extracted widget, and the groups
    // are the server's: the customer's book, then the invoice's own (A3, L4).
    expect(find.byType(RoleTokenField), findsOneWidget);
    expect(_chipsUnder(tester, 'Customer level'),
        ['Sales POC', 'Customer Success POC', 'Collection POC']);
    // The plain label, exactly as the To field renders it: the row is already headed by its
    // level, so the chip does not say it twice (A3, L4).
    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC']);
    // A task assignee may never be a customer — the server refuses that token outright — so the
    // chip is not offered here even though the To field offers it (A3, A6).
    expect(find.textContaining("customer's own email addresses"), findsNothing);

    await tester.tap(_chipIn('Customer level', 'Collection POC'));
    await tester.pumpAndSettle();

    final body = await _save(tester, backend);
    final assignees = ((body['actions'] as List).first as Map)['assignees'] as List;
    expect(assignees, [
      {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
    ]);
  });

  testWidgets('the same widget is what the email dialog renders, level groups and all',
      (tester) async {
    // The extraction guard from the other side: the To field is now RoleTokenField, and its
    // groups still read the way email_compose_test.dart asserts they do (A3, L4).
    final backend = FakeBackend({
      'GET /api/emails/context': (_) => _context(withRecord: true),
    });
    await _sized(tester, const Size(1200, 1000));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_user()),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showSendEmailDialog(context,
                  type: EmailEntityType.invoice, entityId: 42, entityLabel: 'INV-0042'),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.byType(RoleTokenField), findsOneWidget);
    expect(_chipsUnder(tester, 'Customer level'), [
      'Sales POC · nobody assigned',
      'Customer Success POC · nobody assigned',
      'Collection POC · Bob Smith <bob@company.com>',
    ]);
    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC · Divya Nair <divya@company.com>']);
    expect(_chipsUnder(tester, 'Add'), ['Person…']);
  });

  _editing();
}

void _editing() {
  testWidgets('re-opening a saved rule seeds the builder with what was saved, and saves it back '
      'whole', (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend, location: '/automation/rules/7?edit=true');

    // Everything the server sent comes back on screen: the name, the weekly slot in UTC, the
    // cooldown, and the AND-of-OR tree as chips rather than as JSON (A1, A2).
    expect(find.text('Edit rule'), findsOneWidget);
    expect(tester.widget<TextFormField>(find.widgetWithText(TextFormField, 'Name *')).controller
        ?.text, 'Chase big overdue');
    expect(find.text('Wednesday'), findsOneWidget);
    expect(find.text('09:00 UTC'), findsWidgets);
    expect(find.text('Status is any of UNPAID, PARTIALLY_PAID'), findsOneWidget);
    expect(find.text('Balance greater than 50000'), findsOneWidget);
    expect(find.text('Balance greater than 1000'), findsOneWidget);
    // The nested OR is a group of its own, not two more chips on the outer AND (A2).
    expect(find.widgetWithText(SegmentedButton<Connector>, 'Any of'), findsWidgets);

    await tester.enterText(
        find.widgetWithText(TextFormField, 'Name *'), 'Chase big overdue (north)');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    // PUT and not PATCH: the WHOLE rule goes back, because a half-sent rule is a rule that does
    // something nobody asked for (A1).
    final sent = backend.sent('PUT /api/automation/rules/7');
    expect(sent, hasLength(1));
    final body = (sent.single.data as Map).cast<String, dynamic>();
    expect(body['name'], 'Chase big overdue (north)');
    expect(body['triggerKind'], 'SCHEDULE_WEEKLY');
    expect(body['scheduleHourUtc'], 9);
    expect(body['scheduleDayOfWeek'], 3);
    expect(body['cooldownDays'], 14);
    // The branches the rule NAMED, sent back as they stand: an empty list is a different state
    // and means "every branch its author manages" (A1, B1).
    expect(body['regionIds'], [1]);
    // The tree round-trips unchanged, connector and all.
    expect(body['conditions'], {
      'op': 'AND',
      'of': [
        {'filter': 'status:in:UNPAID,PARTIALLY_PAID'},
        {
          'op': 'OR',
          'of': [
            {'filter': 'balance:gt:50000'},
            {'filter': 'balance:gt:1000'},
          ],
        },
      ],
    });
    // And the action, with its role token and its due window (A3, A6).
    expect(body['actions'], [
      {
        'kind': 'CREATE_TASK',
        'title': 'Chase {{Customer.Name}}',
        'assignees': [
          {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
        ],
        'dueInDays': 3,
      },
    ]);
  });

  // ------------------------------------------------------------ the detail page's write buttons

  /// The rule's own page, with a stub for the list behind it. The Runs tab is not opened, so the
  /// steps table is never asked for.
  Future<void> pumpDetail(WidgetTester tester, FakeBackend backend,
      {CurrentUser? user}) async {
    await _sized(tester);
    final router = GoRouter(
      initialLocation: '/automation/rules/7',
      routes: [
        GoRoute(path: '/automation/rules', builder: (_, __) => const SizedBox.shrink()),
        GoRoute(
          path: '/automation/rules/:id',
          // Scaffold, because router.dart mounts these inside the AppShell's (D-71).
          builder: (_, s) =>
              Scaffold(body: RuleDetailScreen(id: int.parse(s.pathParameters['id']!))),
        ),
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
  }

  Map<String, dynamic> bulkResult({
    List<int> succeeded = const [],
    List<Map<String, dynamic>> skipped = const [],
    List<Map<String, dynamic>> failed = const [],
  }) =>
      {
        'action': 'DISABLE',
        'requested': 1,
        'succeeded': succeeded,
        'failed': failed,
        'skipped': skipped,
        'truncated': false,
        'limit': 5000,
        'pending': const <Map<String, dynamic>>[],
        'pendingLimitReached': false,
      };

  testWidgets('switching a rule off goes through the flag-only door, not the save door',
      (tester) async {
    final backend = _backend();
    // THE SAVE DOOR IS SHUT FOR THIS CALLER, and that is an ordinary B1 configuration rather than
    // a contrivance: AutomationRuleService.update -> validate -> regions() calls
    // regionAccess.requireManage for every branch the rule NAMES, against the CALLER. Somebody
    // holding AUTOMATION_MANAGE company-wide but only VIEW in the branch this rule names can see
    // the rule, so the page and its three buttons render — and a PUT of the whole rule 403s. The
    // same shape arrives with no split grant at all when a named branch has been retired, which
    // regions() answers 400 "<CODE> is retired, so a rule cannot be pointed at it".
    //
    // Switching a rule off is the safety valve for a rule that is misbehaving, so it must not
    // re-open the question of whether the rule would still SAVE (A1, A5, B1).
    backend.routes['PUT /api/automation/rules/7'] =
        (_) => const FakeFailure(403, {'message': 'You do not have permission for this action'});
    backend.routes['POST /api/automation/rules/bulk'] =
        (_) => bulkResult(succeeded: const [7]);
    await pumpDetail(tester, backend);

    expect(find.text('On'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Switch off'));
    await tester.pumpAndSettle();

    expect(find.text('Rule switched off'), findsOneWidget);
    expect(find.text('You do not have permission for this action'), findsNothing);
    expect(backend.sent('PUT /api/automation/rules/7'), isEmpty,
        reason: 'a toggle is not a save and must not be posted as one');

    // The bulk ENABLE/DISABLE arm, which calls setEnabled and validates nothing else — the SAME
    // door the list's own Switch off uses, so two controls with one name cannot give two answers.
    final sent = backend.sent('POST /api/automation/rules/bulk');
    expect(sent, hasLength(1));
    expect((sent.single.data as Map).cast<String, dynamic>(),
        {'action': 'DISABLE', 'ids': [7]});

    // And the page was re-read rather than left showing what it guessed.
    expect(backend.sent('GET /api/automation/rules/7').length, greaterThan(1));
  });

  testWidgets('switching a rule on asks for ENABLE, and a rule left alone says why',
      (tester) async {
    final backend = _backend();
    backend.routes['GET /api/automation/rules/7'] = (_) => {..._storedRule(), 'enabled': false};
    // BulkExecutor.eligibility turns setEnabled's "This rule is already off" into a SKIPPED row
    // rather than a failure, and a skipped row is not a success: the page thought otherwise and
    // the reader is owed the correction in the server's own words (A1, TBL-05).
    backend.routes['POST /api/automation/rules/bulk'] = (_) => bulkResult(
        skipped: const [
          {'id': 7, 'reason': 'This rule is already on'},
        ]);
    await pumpDetail(tester, backend);

    expect(find.text('Off'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Switch on'));
    await tester.pumpAndSettle();

    expect((backend.sent('POST /api/automation/rules/bulk').single.data as Map)['action'],
        'ENABLE');
    expect(find.text('This rule is already on'), findsOneWidget);
    expect(find.text('Rule switched on'), findsNothing);
  });

  testWidgets('backing out of the editor leaves the rule exactly as it was saved', (tester) async {
    final backend = _backend();
    await _sized(tester);
    final router = GoRouter(
      initialLocation: '/automation/rules/7?edit=true',
      routes: [
        GoRoute(path: '/automation/rules', builder: (_, __) => const SizedBox.shrink()),
        GoRoute(
          path: '/automation/rules/:id',
          // Scaffold, because router.dart mounts these inside the AppShell's (D-71).
          builder: (_, s) => Scaffold(
            body: s.uri.queryParameters['edit'] == 'true'
                ? RuleEditGate(id: int.parse(s.pathParameters['id']!))
                : RuleDetailScreen(id: int.parse(s.pathParameters['id']!)),
          ),
        ),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_user()),
      ],
      // Somebody still has the rule open — the list behind, or the detail page's own provider
      // before it is torn down. That is what makes the ANSWER here about the objects and not
      // about a lucky refetch.
      child: Consumer(
        builder: (context, ref, child) {
          ref.watch(ruleDetailProvider(7));
          return child!;
        },
        child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.enterText(
        find.widgetWithText(TextField, 'Task title'), 'Something else entirely');
    await tester.pumpAndSettle();

    // Away without saving. A rule's actions are MUTABLE objects, so a builder that edited the
    // provider's own copies would leave the detail page showing work nobody saved (A3).
    router.go('/automation/rules/7');
    await tester.pumpAndSettle();

    expect(backend.sent('GET /api/automation/rules/7'), hasLength(1),
        reason: 'nothing was re-fetched, so this is about the objects');
    expect(find.text('Chase {{Customer.Name}}'), findsOneWidget);
    expect(find.text('Something else entirely'), findsNothing);
    expect(backend.sent('PUT /api/automation/rules/7'), isEmpty);
  });

  testWidgets('moving the caret is not an edit: it renders nothing and marks nothing unsaved',
      (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    final title = find.widgetWithText(TextField, 'Task title');
    await tester.enterText(title, 'Chase them');
    await tester.pumpAndSettle();
    final record = find.widgetWithText(ListTile, 'INV-0042');
    await tester.ensureVisible(record);
    await tester.pumpAndSettle();
    await tester.tap(record);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    final rendered = backend.sent('POST /api/automation/preview').length;
    expect(rendered, greaterThan(0));

    // A TextEditingController notifies on a SELECTION change too. Without the guard, clicking
    // into a box would re-render the preview and mark the page unsaved for somebody who has
    // typed nothing at all (A4).
    tester.widget<TextField>(title).controller!.selection =
        const TextSelection.collapsed(offset: 3);
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pumpAndSettle();
    expect(backend.sent('POST /api/automation/preview').length, rendered);
  });

  testWidgets('an empty group somebody added and left blank is dropped rather than refused',
      (tester) async {
    final backend = _backend();
    await _pumpBuilder(tester, backend);

    await tester.enterText(find.widgetWithText(TextFormField, 'Name *'), 'Blank group');
    await tester.pumpAndSettle();
    await _addCondition(tester, value: '1000');
    await tester.tap(find.widgetWithText(OutlinedButton, 'Add group').first);
    await tester.pumpAndSettle();

    final body = await _save(tester, backend);
    // The server refuses a nested empty group with "A group needs at least one condition", and
    // somebody who pressed Add group and changed their mind should not have their save refused
    // for a card they can see is blank (A2).
    expect(body['conditions'], {
      'op': 'AND',
      'of': [
        {'filter': 'balance:gt:1000'},
      ],
    });
  });
}

/// email_compose_test.dart's own helpers, kept byte-identical: they read the widget tree
/// STRUCTURALLY — the Wrap that contains the heading — which is exactly the property the
/// extraction had to preserve.
List<String> _chipsUnder(WidgetTester tester, String heading) => [
      for (final chip in tester.widgetList<ActionChip>(
          find.descendant(of: _row(heading), matching: find.byType(ActionChip))))
        (chip.label as Text).data ?? '',
    ];

Finder _chipIn(String heading, String text) =>
    find.descendant(of: _row(heading), matching: find.widgetWithText(ActionChip, text));

Finder _row(String heading) =>
    find.ancestor(of: find.text(heading), matching: find.byType(Wrap)).first;
