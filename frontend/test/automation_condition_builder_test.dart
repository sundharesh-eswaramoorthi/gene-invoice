import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/automation/automation_models.dart';
import 'package:gene_invoice/features/automation/condition_builder.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

const _user = CurrentUser(
  id: 5,
  username: 'cara',
  fullName: 'Cara Collections',
  role: 'MANAGER',
  privileges: {Privileges.invoiceView, Privileges.automationView, Privileges.automationManage},
  customerId: null,
);

/// The invoices list's filters, as the server publishes them (§6.16): what a condition is made of.
const invoiceSchema = {
  'entity': 'invoices',
  'defaultSort': 'invoiceDate,desc',
  'pageSizes': [10, 20, 50],
  'defaultPageSize': 20,
  'datePresets': ['today', 'past'],
  'columns': [
    {
      'name': 'overdue',
      'label': 'Overdue',
      'type': 'BOOLEAN',
      'filterable': true,
      'operators': ['eq']
    },
    {
      'name': 'status',
      'label': 'Status',
      'type': 'ENUM',
      'filterable': true,
      'operators': ['in', 'eq'],
      'enumValues': ['UNPAID', 'PARTIALLY_PAID', 'FULLY_PAID', 'CANCELLED'],
    },
    {
      'name': 'balance',
      'label': 'Balance',
      'type': 'MONEY',
      'filterable': true,
      'operators': ['gt', 'lt']
    },
    {'name': 'notes', 'label': 'Notes', 'type': 'TEXT', 'filterable': false, 'operators': []},
  ],
};

/// Holds the tree the builder hands back, as the rule editor does.
class _Holder extends StatefulWidget {
  final ConditionGroup initial;
  final void Function(ConditionGroup) onChanged;
  const _Holder({required this.initial, required this.onChanged});

  @override
  State<_Holder> createState() => _HolderState();
}

class _HolderState extends State<_Holder> {
  late ConditionGroup _value = widget.initial;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SingleChildScrollView(
          child: ConditionBuilder(
            entity: TriggerEntity.INVOICE,
            value: _value,
            onChanged: (g) {
              setState(() => _value = g);
              widget.onChanged(g);
            },
          ),
        ),
      );
}

Future<(FakeBackend, ConditionGroup Function())> _pump(WidgetTester tester,
    {ConditionGroup initial = ConditionGroup.empty}) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = FakeBackend({'GET /api/table-schemas/invoices': (_) => invoiceSchema});
  var latest = initial;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_user),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: _Holder(initial: initial, onChanged: (g) => latest = g),
    ),
  ));
  await tester.pumpAndSettle();
  return (backend, () => latest);
}

/// The filter editor's first column is Overdue, and "is Yes" its default: Apply as it opens.
Future<void> _applyAsOpened(WidgetTester tester) async {
  await tester.tap(find.text('Apply'));
  await tester.pumpAndSettle();
}

Future<void> _chooseColumn(WidgetTester tester, String label) async {
  await tester.tap(find.text('Overdue').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

Future<void> _balanceOver(WidgetTester tester, String amount) async {
  await _chooseColumn(tester, 'Balance');
  await tester.enterText(find.widgetWithText(TextField, 'Value'), amount);
  await tester.tap(find.text('Apply'));
  await tester.pumpAndSettle();
}

Future<void> _statusIn(WidgetTester tester, String status) async {
  await _chooseColumn(tester, 'Status');
  await tester.tap(find.widgetWithText(FilterChip, status));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Apply'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('builds AND[leaf, OR[leaf, leaf]] and hands back exactly that JSON', (tester) async {
    final (backend, latest) = await _pump(tester);
    expect(backend.sent('GET /api/table-schemas/invoices'), hasLength(1));
    expect(find.textContaining('Every invoice matches'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('cond-add-c')));
    await tester.pumpAndSettle();
    await _applyAsOpened(tester);

    // A group opens with its first condition, and reads the other way from its parent.
    await tester.tap(find.byKey(const ValueKey('cond-add-group-c')));
    await tester.pumpAndSettle();
    await _balanceOver(tester, '100000');

    await tester.tap(find.byKey(const ValueKey('cond-add-c.1')));
    await tester.pumpAndSettle();
    await _statusIn(tester, 'UNPAID');

    expect(latest().toJson(), {
      'op': 'AND',
      'children': [
        {'filter': 'overdue:eq:true'},
        {
          'op': 'OR',
          'children': [
            {'filter': 'balance:gt:100000'},
            {'filter': 'status:in:UNPAID'},
          ],
        },
      ],
    });
    expect(latest().toRequest(), latest().toJson());
    // Each condition is a chip labelled as the list page labels a filter.
    expect(find.text('Overdue is true'), findsOneWidget);
    expect(find.text('Balance greater than 100000'), findsOneWidget);
    expect(find.text('Status is any of UNPAID'), findsOneWidget);
    expect(find.textContaining('Every invoice matches'), findsNothing);
  });

  testWidgets('"Any of these" turns a group into OR', (tester) async {
    final (_, latest) = await _pump(tester,
        initial: const ConditionGroup('AND', [
          ConditionLeaf(TableFilter('overdue', 'eq', ['true'])),
          ConditionLeaf(TableFilter('balance', 'gt', ['100'])),
        ]));

    await tester.tap(find.descendant(
        of: find.byKey(const ValueKey('cond-op-c')), matching: find.text('Any of these')));
    await tester.pumpAndSettle();

    expect(latest().toJson()['op'], 'OR');
    expect(latest().children, hasLength(2));
  });

  testWidgets('editing a leaf changes that leaf, in its place', (tester) async {
    final (_, latest) = await _pump(tester,
        initial: const ConditionGroup('AND', [
          ConditionLeaf(TableFilter('balance', 'gt', ['100'])),
          ConditionLeaf(TableFilter('overdue', 'eq', ['true'])),
        ]));

    await tester.tap(find.byKey(const ValueKey('cond-leaf-c.0')));
    await tester.pumpAndSettle();
    expect(find.text('Edit filter'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Value'), '250');
    await tester.tap(find.text('Apply'));
    await tester.pumpAndSettle();

    expect(latest().toJson(), {
      'op': 'AND',
      'children': [
        {'filter': 'balance:gt:250'},
        {'filter': 'overdue:eq:true'},
      ],
    });
  });

  testWidgets(
      'removing a group takes all of it; removing a group\'s last condition takes the group',
      (tester) async {
    final (_, latest) = await _pump(tester,
        initial: const ConditionGroup('AND', [
          ConditionLeaf(TableFilter('overdue', 'eq', ['true'])),
          ConditionGroup('OR', [
            ConditionLeaf(TableFilter('balance', 'gt', ['100'])),
            ConditionLeaf(TableFilter('status', 'in', ['UNPAID'])),
          ]),
          ConditionGroup('OR', [
            ConditionLeaf(TableFilter('balance', 'lt', ['5']))
          ]),
        ]));

    await tester.tap(find.byKey(const ValueKey('cond-remove-c.1')));
    await tester.pumpAndSettle();
    expect(latest().toJson(), {
      'op': 'AND',
      'children': [
        {'filter': 'overdue:eq:true'},
        {
          'op': 'OR',
          'children': [
            {'filter': 'balance:lt:5'},
          ],
        },
      ],
    });

    // The server takes no empty group, so the one left with nothing goes with it.
    final lastChip = find.byKey(const ValueKey('cond-leaf-c.1.0'));
    await tester.tap(find.descendant(of: lastChip, matching: find.byTooltip('Remove condition')));
    await tester.pumpAndSettle();
    expect(latest().toJson(), {
      'op': 'AND',
      'children': [
        {'filter': 'overdue:eq:true'},
      ],
    });

    // And with the last condition gone, there is no condition at all: every record.
    await tester.tap(find.descendant(
        of: find.byKey(const ValueKey('cond-leaf-c.0')),
        matching: find.byTooltip('Remove condition')));
    await tester.pumpAndSettle();
    expect(latest().isEmpty, isTrue);
    expect(latest().toRequest(), isNull);
  });

  testWidgets('never folds two conditions on the same field and operator into one', (tester) async {
    final (_, latest) = await _pump(tester);

    await tester.tap(find.byKey(const ValueKey('cond-add-c')));
    await tester.pumpAndSettle();
    await _balanceOver(tester, '100');
    await tester.tap(find.byKey(const ValueKey('cond-add-c')));
    await tester.pumpAndSettle();
    await _balanceOver(tester, '5000');

    expect(latest().toJson(), {
      'op': 'AND',
      'children': [
        {'filter': 'balance:gt:100'},
        {'filter': 'balance:gt:5000'},
      ],
    });
    expect(find.text('Balance greater than 100'), findsOneWidget);
    expect(find.text('Balance greater than 5000'), findsOneWidget);
  });

  testWidgets('a group four deep offers no further group', (tester) async {
    await _pump(tester,
        initial: const ConditionGroup('AND', [
          ConditionGroup('OR', [
            ConditionGroup('AND', [
              ConditionGroup('OR', [
                ConditionLeaf(TableFilter('overdue', 'eq', ['true']))
              ]),
            ]),
          ]),
        ]));

    expect(find.byKey(const ValueKey('cond-add-group-c.0.0')), findsOneWidget);
    expect(find.byKey(const ValueKey('cond-add-group-c.0.0.0')), findsNothing);
    expect(find.byKey(const ValueKey('cond-add-c.0.0.0')), findsOneWidget);
  });

  testWidgets('read only shows the conditions and offers no change', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final backend = FakeBackend({'GET /api/table-schemas/invoices': (_) => invoiceSchema});
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_user)
      ],
      child: MaterialApp(
        home: Scaffold(
          body: ConditionBuilder(
            entity: TriggerEntity.INVOICE,
            enabled: false,
            value: const ConditionGroup('AND', [
              ConditionLeaf(TableFilter('overdue', 'eq', ['true']))
            ]),
            onChanged: (_) => fail('read only'),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Overdue is true'), findsOneWidget);
    expect(find.text('Add condition'), findsNothing);
    expect(find.text('Add group'), findsNothing);
    expect(find.byTooltip('Remove condition'), findsNothing);
  });

  group('the condition JSON', () {
    test('reads the wire format back, groups and leaves alike', () {
      final json = {
        'op': 'AND',
        'children': [
          {'filter': 'status:in:UNPAID,PARTIALLY_PAID'},
          {
            'op': 'OR',
            'children': [
              {'filter': 'overdue:eq:true'},
              {'filter': 'balance:gt:100000'},
            ],
          },
        ],
      };
      final tree = ConditionGroup.fromJson(json);
      expect(tree.toJson(), json);
      expect(tree.leafCount, 3);
      final first = tree.children.first as ConditionLeaf;
      expect(first.filter.values, ['UNPAID', 'PARTIALLY_PAID']);
    });

    test('an unknown op reads as AND, and an unreadable node is dropped', () {
      final tree = ConditionGroup.fromJson(const {
        'op': 'XOR',
        'children': [
          {'filter': 'nonsense'},
          {'filter': 'overdue:eq:true'},
          'junk',
        ],
      });
      expect(tree.toJson(), {
        'op': 'AND',
        'children': [
          {'filter': 'overdue:eq:true'},
        ],
      });
    });
  });

  test('a rule without a condition sends null: every record', () {
    expect(ConditionGroup.empty.toRequest(), isNull);
    final rule = Rule.fromJson(const {
      'id': 1,
      'name': 'R',
      'enabled': true,
      'status': 'ACTIVE',
      'trigger': {'kind': 'MANUAL', 'entityType': 'INVOICE'},
      'condition': null,
    });
    expect(rule.condition.isEmpty, isTrue);
  });
}
