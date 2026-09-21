import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_form_screen.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/payment_term.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/status_chip.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

CurrentUser _user(Set<String> privileges) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: 'CASHIER',
      privileges: privileges,
      customerId: null,
    );

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 20, 'totalElements': rows.length, 'totalPages': 1};

Map<String, dynamic> _column(String name, String type,
        {bool sortable = true, bool filterable = true, List<String> operators = const []}) =>
    {
      'name': name,
      'label': name,
      'type': type,
      'sortable': sortable,
      'filterable': filterable,
      'operators': operators,
    };

Map<String, dynamic> _invoiceSchema({bool withOverdue = true}) => {
      'entity': 'invoices',
      'columns': [
        _column('invoiceDate', 'DATE', operators: const ['eq', 'between', 'gte', 'lte']),
        _column('dueDate', 'DATE', operators: const ['eq', 'between', 'gte', 'lte']),
        if (withOverdue)
          _column('overdue', 'BOOLEAN', sortable: false, operators: const ['eq']),
      ],
    };

const _acme = {'id': 5, 'name': 'Acme Ltd', 'email': 'ap@acme.com', 'creditBalance': 0};

Future<void> _pump(
  WidgetTester tester, {
  required FakeBackend backend,
  required CurrentUser user,
  required String location,
  required Map<String, Widget Function(GoRouterState state)> pages,
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: location,
    routes: [
      for (final e in pages.entries)
        GoRoute(path: e.key, builder: (_, s) => Scaffold(body: e.value(s))),
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
}

void main() {
  group('the invoice form', () {
    FakeBackend backend({Map<String, Object?>? preview}) => FakeBackend({
          'GET /api/pocs/my-scope': (_) =>
              {'userId': 3, 'sales': true, 'success': false, 'collection': false},
          'GET /api/pocs/assignable': (_) => [
                {'id': 3, 'username': 'jane', 'fullName': 'Jane Doe'},
              ],
          'GET /api/customers': (_) => _page([_acme]),
          'GET /api/products': (_) => _page([
                {'id': 8, 'name': 'Widget', 'price': 100},
              ]),
          'GET /api/invoices/due-date-preview': (_) =>
              preview ??
              {
                'dueDate': '2026-10-20',
                'paymentTerm': 'NET_30',
                'paymentTermLabel': 'Net 30',
                'source': 'CUSTOMER',
              },
          'POST /api/invoices': (_) => {'id': 42, 'invoiceNumber': 'INV-0042'},
        });

    Future<void> pumpForm(WidgetTester tester, FakeBackend api) => _pump(tester,
        backend: api,
        user: _user({Privileges.invoiceView, Privileges.invoiceManage, Privileges.pocView}),
        location: '/invoices/new',
        pages: {
          '/invoices/new': (_) => const InvoiceFormScreen(),
          '/invoices': (_) => const Text('invoices page'),
        });

    Future<void> pick(WidgetTester tester, int field, String label) async {
      await tester.tap(find.text('Select…').at(field));
      await tester.pumpAndSettle();
      await tester.tap(find.text(label));
      await tester.pumpAndSettle();
    }

    Map<String, dynamic> posted(FakeBackend api) =>
        (api.sent('POST /api/invoices').single.data as Map).cast<String, dynamic>();

    testWidgets('picking a customer fills the terms and the date they give (US-A2)',
        (tester) async {
      final api = backend();
      await pumpForm(tester, api);

      expect(find.text('Pick a customer first'), findsOneWidget);
      await pick(tester, 0, 'Acme Ltd');

      expect(api.sent('GET /api/invoices/due-date-preview').single.queryParameters,
          containsPair('customerId', 5));
      expect(find.text('Net 30 — due 20 Oct 2026'), findsOneWidget);
      expect(find.text('2026-10-20'), findsOneWidget);
    });

    testWidgets('changing the terms recomputes the date from the same invoice date',
        (tester) async {
      final api = backend();
      await pumpForm(tester, api);
      await pick(tester, 0, 'Acme Ltd');

      await tester.tap(find.text('Net 30').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Net 60').last);
      await tester.pumpAndSettle();

      expect(find.text('Net 60 — due 19 Nov 2026'), findsOneWidget);
      expect(find.text('2026-11-19'), findsOneWidget);

      await pick(tester, 0, 'Widget');
      await tester.tap(find.text('Create invoice'));
      await tester.pumpAndSettle();

      expect(posted(api), containsPair('paymentTerm', 'NET_60'));
      expect(posted(api).containsKey('dueDate'), isFalse);
    });

    testWidgets('a date chosen by hand makes the terms Custom and goes up as a date (US-A3)',
        (tester) async {
      final api = backend();
      await pumpForm(tester, api);
      await pick(tester, 0, 'Acme Ltd');

      await tester.tap(find.text('2026-10-20'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byType(DatePickerDialog), matching: find.text('25')));
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      expect(find.text('Custom — due 25 Oct 2026'), findsOneWidget);

      await pick(tester, 0, 'Widget');
      await tester.tap(find.text('Create invoice'));
      await tester.pumpAndSettle();

      expect(posted(api), containsPair('dueDate', '2026-10-25'));
      expect(posted(api).containsKey('paymentTerm'), isFalse);
    });

    testWidgets('a date more than a year out is warned about, not refused (AC-A5)',
        (tester) async {
      final api = backend();
      await pumpForm(tester, api);
      await pick(tester, 0, 'Acme Ltd');
      expect(find.textContaining('more than a year away'), findsNothing);

      await tester.tap(find.text('2026-10-20'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('October 2026'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('2028'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byType(DatePickerDialog), matching: find.text('25')));
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      expect(find.text('That is more than a year away — is it right?'), findsOneWidget);

      await pick(tester, 0, 'Widget');
      await tester.tap(find.text('Create invoice'));
      await tester.pumpAndSettle();

      expect(posted(api), containsPair('dueDate', '2028-10-25'));
      expect(find.text('invoices page'), findsOneWidget);
    });

    testWidgets('a due date the server could not work out is said so, and does not stop the save',
        (tester) async {
      final api = FakeBackend({
        ...backend().routes,
        'GET /api/invoices/due-date-preview': (_) =>
            const FakeFailure(500, {'message': 'nope'}),
      });
      await pumpForm(tester, api);
      await pick(tester, 0, 'Acme Ltd');

      expect(find.textContaining('Could not work out the due date'), findsOneWidget);

      await pick(tester, 0, 'Widget');
      await tester.tap(find.text('Create invoice'));
      await tester.pumpAndSettle();

      expect(posted(api).containsKey('dueDate'), isFalse);
      expect(posted(api).containsKey('paymentTerm'), isFalse);
      expect(find.text('invoices page'), findsOneWidget);
    });

    testWidgets('a date years out is still queried after the preview failed', (tester) async {
      final api = FakeBackend({
        ...backend().routes,
        'GET /api/invoices/due-date-preview': (_) =>
            const FakeFailure(500, {'message': 'nope'}),
      });
      await pumpForm(tester, api);
      await pick(tester, 0, 'Acme Ltd');
      expect(find.textContaining('Could not work out the due date'), findsOneWidget);

      await tester.tap(find.text('Pick a date'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byType(DatePickerDialog), matching: find.byIcon(Icons.arrow_drop_down)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('${DateTime.now().year + 3}'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byType(DatePickerDialog), matching: find.text('25')));
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      expect(find.text('That is more than a year away — is it right?'), findsOneWidget);
    });
  });

  group('the invoice detail page', () {
    Map<String, dynamic> invoice({required bool overdue}) => {
          'id': 42,
          'invoiceNumber': 'INV-0042',
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'invoiceDate': '2026-08-20',
          'dueDate': '2026-09-19',
          'paymentTerm': 'NET_30',
          'paymentTermLabel': 'Net 30',
          'overdue': overdue,
          'daysOverdue': overdue ? 12 : 0,
          'total': 1200,
          'paidAmount': 0,
          'balance': 1200,
          'status': 'UNPAID',
          'items': [],
        };

    Future<void> pumpDetail(WidgetTester tester, {required bool overdue}) => _pump(tester,
        backend: FakeBackend({
          'GET /api/invoices/42': (_) => invoice(overdue: overdue),
          'GET /api/audit': (_) => _page([]),
        }),
        user: _user({Privileges.invoiceView, Privileges.auditView}),
        location: '/invoices/42',
        pages: {'/invoices/:id': (_) => const InvoiceDetailScreen(id: 42)});

    testWidgets('an overdue invoice says so, and by how long (US-A4)', (tester) async {
      await pumpDetail(tester, overdue: true);

      expect(find.text('Acme Ltd • 2026-08-20 • due 2026-09-19'), findsOneWidget);
      expect(find.text('Overdue by 12 days'), findsOneWidget);
      // In the error colour, beside the status rather than instead of it (D3).
      final badge = tester.widget<StatusChip>(find.ancestor(
          of: find.text('Overdue by 12 days'), matching: find.byType(StatusChip)));
      expect(badge.color,
          Theme.of(tester.element(find.byType(StatusChip).first)).colorScheme.error);
      expect(find.text('Unpaid'), findsOneWidget);
      expect(find.text('Payment terms'), findsOneWidget);
      expect(find.text('Net 30'), findsOneWidget);
    });

    testWidgets('an invoice inside its terms keeps the date and loses the badge', (tester) async {
      await pumpDetail(tester, overdue: false);

      expect(find.text('Acme Ltd • 2026-08-20 • due 2026-09-19'), findsOneWidget);
      expect(find.textContaining('Overdue'), findsNothing);
    });

    Future<FakeBackend> pumpEditable(WidgetTester tester) async {
      final api = FakeBackend({
        'GET /api/invoices/42': (_) => invoice(overdue: false),
        'GET /api/audit': (_) => _page([]),
        'PATCH /api/invoices/42': (_) => invoice(overdue: false),
      });
      await _pump(tester,
          backend: api,
          user: _user(
              {Privileges.invoiceView, Privileges.invoiceManage, Privileges.auditView}),
          location: '/invoices/42',
          pages: {'/invoices/:id': (_) => const InvoiceDetailScreen(id: 42)});
      return api;
    }

    testWidgets('longer terms recompute the date and go up as terms', (tester) async {
      final api = await pumpEditable(tester);

      await tester.tap(find.text('Net 30').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Net 45').last);
      await tester.pumpAndSettle();

      expect(find.text('2026-10-04'), findsOneWidget);
      expect(find.text('Unsaved changes'), findsOneWidget);

      await tester.tap(find.text('Save changes'));
      await tester.pumpAndSettle();

      final sent = (api.sent('PATCH /api/invoices/42').single.data as Map);
      expect(sent['paymentTerm'], 'NET_45');
      expect(sent.containsKey('dueDate'), isFalse);
    });

    testWidgets('a date chosen by hand goes up as a date, and the terms become Custom',
        (tester) async {
      final api = await pumpEditable(tester);

      await tester.tap(find.text('2026-09-19'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byType(DatePickerDialog), matching: find.text('25')));
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      expect(find.text('Custom'), findsOneWidget);

      await tester.tap(find.text('Save changes'));
      await tester.pumpAndSettle();

      final sent = (api.sent('PATCH /api/invoices/42').single.data as Map);
      expect(sent['dueDate'], '2026-09-25');
      expect(sent.containsKey('paymentTerm'), isFalse);
    });

    testWidgets('without INVOICE_MANAGE the terms and the date are read-only', (tester) async {
      await pumpDetail(tester, overdue: false);

      expect(find.text('Net 30'), findsOneWidget);
      expect(find.byType(DropdownButton<PaymentTerm>), findsNothing);
      await tester.tap(find.text('2026-09-19').first);
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsNothing);
    });
  });

  group('the invoice list', () {
    FakeBackend backend({bool withOverdue = true}) => FakeBackend({
          'GET /api/table-schemas/invoices': (_) => _invoiceSchema(withOverdue: withOverdue),
          'GET /api/invoices': (_) => _page([
                {
                  'id': 1,
                  'invoiceNumber': 'INV-0001',
                  'customerId': 5,
                  'customerName': 'Acme Ltd',
                  'invoiceDate': '2026-08-20',
                  'dueDate': '2026-09-19',
                  'paymentTerm': 'NET_30',
                  'paymentTermLabel': 'Net 30',
                  'overdue': true,
                  'daysOverdue': 12,
                  'total': 1200,
                  'paidAmount': 0,
                  'balance': 1200,
                  'status': 'UNPAID',
                },
                {
                  'id': 2,
                  'invoiceNumber': 'INV-0002',
                  'customerId': 5,
                  'customerName': 'Acme Ltd',
                  'invoiceDate': '2026-09-15',
                  'dueDate': '2026-10-15',
                  'paymentTerm': 'NET_30',
                  'paymentTermLabel': 'Net 30',
                  'overdue': false,
                  'daysOverdue': 0,
                  'total': 500,
                  'paidAmount': 0,
                  'balance': 500,
                  'status': 'UNPAID',
                },
              ]),
          'GET /api/invoices/summary': (_) => {
                'count': 2,
                'totalBilled': 1700,
                'outstanding': 1700,
                'overdueAmount': 1200,
                'overdueCount': 1,
                'unpaidCount': 2,
                'partiallyPaidCount': 0,
              },
        });

    Future<void> pumpList(WidgetTester tester, FakeBackend api,
            {TableQuery query = const TableQuery(sort: 'invoiceDate,desc')}) =>
        _pump(tester,
            backend: api,
            user: _user({Privileges.invoiceView}),
            location: '/invoices',
            pages: {
              '/invoices': (s) => InvoicesScreen(
                  query: TableQuery.fromRoute(s.uri.queryParametersAll,
                      defaultSize: 20, defaultSort: query.sort)),
            });

    List<String> filtersSent(FakeBackend api) =>
        ((api.sent('GET /api/invoices').last.queryParameters['filter'] as List?) ?? const [])
            .map((f) => f.toString())
            .toList();

    testWidgets('every row shows its due date, and the late one is badged (US-A4)',
        (tester) async {
      await pumpList(tester, backend());

      expect(find.text('Due date'), findsOneWidget);
      expect(find.text('2026-09-19'), findsOneWidget);
      expect(find.text('2026-10-15'), findsOneWidget);
      expect(find.byTooltip('Overdue by 12 days'), findsOneWidget);
    });

    testWidgets('the tiles count the overdue money and invoices over the filtered set (AC-A7)',
        (tester) async {
      await pumpList(tester, backend());

      SummaryTile tile(String label) => tester.widget<SummaryTile>(
          find.ancestor(of: find.text(label), matching: find.byType(SummaryTile)));

      expect(tile('Overdue').value, '₹1,200.00');
      expect(tile('Overdue invoices').value, '1');
    });

    testWidgets('"Overdue only" asks the server for exactly that, and lets go again (US-A5)',
        (tester) async {
      final api = backend();
      await pumpList(tester, api);
      expect(filtersSent(api), isEmpty);

      await tester.tap(find.text('Overdue only'));
      await tester.pumpAndSettle();
      expect(filtersSent(api), ['overdue:eq:true']);
      expect(find.text('Overdue only'), findsOneWidget);
      expect(find.textContaining('is true'), findsNothing);

      await tester.tap(find.text('Overdue only'));
      await tester.pumpAndSettle();
      expect(filtersSent(api), isEmpty);
    });

    testWidgets('a phone fits the Overdue badge beside the due date (US-A4)', (tester) async {
      await _pump(tester,
          backend: backend(),
          user: _user({Privileges.invoiceView}),
          location: '/invoices',
          pages: {
            '/invoices': (s) => InvoicesScreen(
                query: TableQuery.fromRoute(s.uri.queryParametersAll,
                    defaultSize: 20, defaultSort: 'invoiceDate,desc')),
          },
          size: const Size(390, 1600));

      expect(tester.takeException(), isNull);
      expect(find.text('2026-09-19'), findsOneWidget);
      expect(find.byTooltip('Overdue by 12 days'), findsOneWidget);
    });

    testWidgets('a caller whose schema has no overdue column is not offered the chip',
        (tester) async {
      await pumpList(tester, backend(withOverdue: false));

      expect(find.text('Overdue only'), findsNothing);
      expect(find.text('Due date'), findsOneWidget);
    });
  });

  group('an invoice stamped either side of midnight UTC', () {
    Map<String, dynamic> lateEvening({
      String? paymentTerm = 'NET_30',
      String? paymentTermLabel = 'Net 30',
      String? dueDate = '2026-10-20',
    }) =>
        {
          'id': 42,
          'invoiceNumber': 'INV-0042',
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'invoiceDate': '2026-09-20T23:30:00Z',
          'dueDate': dueDate,
          'paymentTerm': paymentTerm,
          'paymentTermLabel': paymentTermLabel,
          'overdue': false,
          'daysOverdue': 0,
          'total': 1200,
          'paidAmount': 0,
          'balance': 1200,
          'status': 'UNPAID',
          'items': const [],
        };

    test('a day is read where the server counted it, not where the browser sits', () {
      expect(formatUtcDate('2026-09-20T23:30:00Z'), '2026-09-20');
      expect(formatUtcDate('2026-09-21T00:30:00Z'), '2026-09-21');
      expect(formatUtcDate('2026-09-21T05:00:00+05:30'), '2026-09-20');
      expect(formatUtcDate('2026-08-20'), '2026-08-20');
      expect(formatUtcDate(null), '—');
    });

    testWidgets('the detail header pairs the date its terms were counted from with the due date',
        (tester) async {
      await _pump(tester,
          backend: FakeBackend({
            'GET /api/invoices/42': (_) => lateEvening(),
            'GET /api/audit': (_) => _page([]),
          }),
          user: _user({Privileges.invoiceView, Privileges.auditView}),
          location: '/invoices/42',
          pages: {'/invoices/:id': (_) => const InvoiceDetailScreen(id: 42)});

      expect(find.text('Acme Ltd • 2026-09-20 • due 2026-10-20'), findsOneWidget);
      expect(find.text('Net 30'), findsOneWidget);
    });

    testWidgets('the list dates two such invoices to different days', (tester) async {
      await _pump(tester,
          backend: FakeBackend({
            'GET /api/table-schemas/invoices': (_) => _invoiceSchema(),
            'GET /api/invoices': (_) => _page([
                  lateEvening(),
                  {
                    ...lateEvening(dueDate: '2026-10-21'),
                    'id': 43,
                    'invoiceNumber': 'INV-0043',
                    'invoiceDate': '2026-09-21T00:30:00Z',
                  },
                ]),
            'GET /api/invoices/summary': (_) => {
                  'count': 2,
                  'totalBilled': 2400,
                  'outstanding': 2400,
                  'overdueAmount': 0,
                  'overdueCount': 0,
                  'unpaidCount': 2,
                  'partiallyPaidCount': 0,
                },
          }),
          user: _user({Privileges.invoiceView}),
          location: '/invoices',
          pages: {
            '/invoices': (s) => InvoicesScreen(
                query: TableQuery.fromRoute(s.uri.queryParametersAll,
                    defaultSize: 20, defaultSort: 'invoiceDate,desc')),
          });

      expect(find.text('2026-09-20'), findsOneWidget);
      expect(find.text('2026-10-20'), findsOneWidget);
      expect(find.text('2026-09-21'), findsOneWidget);
      expect(find.text('2026-10-21'), findsOneWidget);
    });

    testWidgets('new terms are counted from the UTC day when there is no term to undo',
        (tester) async {
      await _pump(tester,
          backend: FakeBackend({
            'GET /api/invoices/42': (_) => lateEvening(
                paymentTerm: 'CUSTOM', paymentTermLabel: 'Custom', dueDate: '2026-10-05'),
            'GET /api/audit': (_) => _page([]),
            'PATCH /api/invoices/42': (_) => lateEvening(),
          }),
          user: _user(
              {Privileges.invoiceView, Privileges.invoiceManage, Privileges.auditView}),
          location: '/invoices/42',
          pages: {'/invoices/:id': (_) => const InvoiceDetailScreen(id: 42)});

      await tester.tap(find.text('Custom').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Net 30').last);
      await tester.pumpAndSettle();

      expect(find.text('2026-10-20'), findsOneWidget);
    });
  });

  group('a customer', () {
    Map<String, dynamic> customer({String? term, double overdue = 400}) => {
          ..._acme,
          'outstanding': 1000,
          'overdueAmount': overdue,
          'paymentTerm': term,
          'paymentTermLabel': term == null ? 'Net 30' : 'Net 45',
        };

    FakeBackend backend(Map<String, dynamic> customer) => FakeBackend({
          'GET /api/customers/5': (_) => customer,
          'GET /api/audit': (_) => _page([]),
        });

    Future<void> pumpDetail(WidgetTester tester, FakeBackend api,
            {required Set<String> privileges}) =>
        _pump(tester,
            backend: api,
            user: _user({...privileges, Privileges.auditView}),
            location: '/customers/5',
            pages: {'/customers/:id': (_) => const CustomerDetailScreen(id: 5)});

    testWidgets('sees how much of what they owe is late (US-A6)', (tester) async {
      await pumpDetail(tester, backend(customer(term: 'NET_45')),
          privileges: {Privileges.customerView});

      expect(find.text('₹1,000.00'), findsOneWidget);
      expect(find.text('of which ₹400.00 overdue'), findsOneWidget);
    });

    testWidgets('owing nothing late says nothing about it', (tester) async {
      await pumpDetail(tester, backend(customer(term: 'NET_45', overdue: 0)),
          privileges: {Privileges.customerView});

      expect(find.textContaining('overdue'), findsNothing);
    });

    testWidgets('without CUSTOMER_MANAGE the terms are shown but not editable (US-A1)',
        (tester) async {
      await pumpDetail(tester, backend(customer(term: 'NET_45')),
          privileges: {Privileges.customerView});

      expect(find.text('Payment terms'), findsOneWidget);
      expect(find.text('Net 45'), findsOneWidget);
      expect(find.byType(DropdownButton<PaymentTerm?>), findsNothing);
    });

    testWidgets('no terms of their own reads as the system default, and names it', (tester) async {
      await pumpDetail(tester, backend(customer()), privileges: {Privileges.customerView});

      expect(find.text('System default (Net 30)'), findsOneWidget);
    });

    testWidgets('an admin changes the terms and saves them', (tester) async {
      final api = FakeBackend({
        'GET /api/customers/5': (_) => customer(term: 'NET_45'),
        'GET /api/audit': (_) => _page([]),
        'PUT /api/customers/5': (_) => customer(term: 'NET_60'),
      });
      await pumpDetail(tester, api,
          privileges: {Privileges.customerView, Privileges.customerManage});

      await tester.tap(find.text('Net 45').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Net 60').last);
      await tester.pumpAndSettle();
      expect(find.text('Unsaved changes'), findsOneWidget);

      await tester.tap(find.text('Save changes'));
      await tester.pumpAndSettle();

      expect((api.sent('PUT /api/customers/5').single.data as Map)['paymentTerm'], 'NET_60');
    });

    testWidgets('a new customer can be given terms on the form (US-A1)', (tester) async {
      final api = FakeBackend({
        'GET /api/table-schemas/customers': (_) => {'entity': 'customers', 'columns': []},
        'GET /api/customers': (_) => _page([_acme]),
        'GET /api/customers/summary': (_) => {'count': 1},
        'POST /api/customers': (_) => {..._acme, 'id': 17, 'name': 'Globex'},
      });
      await _pump(tester,
          backend: api,
          user: _user({Privileges.customerView, Privileges.customerManage}),
          location: '/customers',
          pages: {
            '/customers': (_) => const CustomersScreen(query: TableQuery(sort: 'name,asc')),
          });

      await tester.tap(find.text('New customer'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-pass');
      await tester.tap(find.text('System default'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Net 15').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect((api.sent('POST /api/customers').single.data as Map)['paymentTerm'], 'NET_15');
    });
  });
}
