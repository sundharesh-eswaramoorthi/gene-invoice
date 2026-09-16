import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

/// AC2: both record kinds must expose Send Email from Details, List, each row's action menu,
/// and a selection-based table bulk action — and AC16: their Details must show an Emails tab
/// even for a user without dispute privileges.

const _customerManager = CurrentUser(
  id: 1,
  username: 'mgr',
  fullName: 'Manager',
  role: 'ADMIN',
  privileges: {Privileges.customerView, Privileges.customerManage},
  customerId: null,
);

const _invoiceManager = CurrentUser(
  id: 1,
  username: 'mgr',
  fullName: 'Manager',
  role: 'ADMIN',
  privileges: {Privileges.invoiceView, Privileges.invoiceManage},
  customerId: null,
);

Map<String, dynamic> _customerRow() => {'id': 7, 'name': 'Acme Ltd'};

Map<String, dynamic> _invoiceRow() => {
      'id': 8,
      'invoiceNumber': 'INV-8',
      'customerId': 7,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2024-05-01',
      'total': 100,
      'paidAmount': 0,
      'balance': 100,
      'status': 'UNPAID',
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

/// Answers table schemas with no columns, summaries with an empty map, detail and Email-list
/// requests from [answers] (matched in order), and anything else with an empty page.
Dio _dio(Map<Pattern, Object> answers) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    for (final entry in answers.entries) {
      final key = entry.key;
      final hit = key is String ? options.path == key : (key as RegExp).hasMatch(options.path);
      if (hit) {
        handler.resolve(
            Response(requestOptions: options, statusCode: 200, data: entry.value));
        return;
      }
    }
    final Object data = options.path.startsWith('/api/table-schemas/')
        ? <String, dynamic>{'entity': 'x', 'columns': <dynamic>[]}
        : options.path.endsWith('/summary')
            ? <String, dynamic>{}
            : _page(const []);
    handler.resolve(Response(requestOptions: options, statusCode: 200, data: data));
  }));

Future<void> _pump(WidgetTester tester, Widget home, CurrentUser user, Dio dio) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp(home: home),
  ));
  await tester.pumpAndSettle();
}

/// The three list-side entry points on one list screen: the top action, the row menu item,
/// and the bulk action that appears once a row is selected.
Future<void> _expectListEntryPoints(WidgetTester tester) async {
  expect(find.widgetWithText(OutlinedButton, 'Send email'), findsOneWidget);   // List page
  expect(find.byTooltip('Send email'), findsOneWidget);                        // row menu

  await tester.tap(find.byType(Checkbox).last);   // select the only row
  await tester.pumpAndSettle();
  expect(find.text('1 selected'), findsOneWidget);
  expect(find.text('Send email'), findsNWidgets(2));   // List action + selection bulk action
}

void main() {
  testWidgets('Customers expose Send Email from the list, its row menus and selection',
      (tester) async {
    await _pump(
      tester,
      const CustomersScreen(query: TableQuery(size: 20)),
      _customerManager,
      _dio({'/api/customers': _page([_customerRow()])}),
    );
    await _expectListEntryPoints(tester);
  });

  testWidgets('Invoices expose Send Email from the list, its row menus and selection',
      (tester) async {
    await _pump(
      tester,
      const InvoicesScreen(query: TableQuery(size: 20)),
      _invoiceManager,
      _dio({'/api/invoices': _page([_invoiceRow()])}),
    );
    await _expectListEntryPoints(tester);
  });

  testWidgets('Customer Details carries a Send email action and an Emails tab '
      'without needing dispute privileges', (tester) async {
    await _pump(
      tester,
      const Scaffold(body: CustomerDetailScreen(id: 7)),
      _customerManager,
      _dio({
        '/api/customers/7': _customerRow(),
        RegExp(r'^/api/customers/7/emails'): <dynamic>[],
      }),
    );

    expect(find.text('Send email'), findsOneWidget);   // Details-page entry point
    expect(find.text('Emails'), findsOneWidget);       // the Email tab itself (FR10)
  });

  testWidgets('Invoice Details carries a Send email action and an Emails tab '
      'without needing dispute privileges', (tester) async {
    await _pump(
      tester,
      const Scaffold(body: InvoiceDetailScreen(id: 8)),
      _invoiceManager,
      _dio({
        '/api/invoices/8': {..._invoiceRow(), 'notes': null, 'items': <dynamic>[]},
        RegExp(r'^/api/invoices/8/emails'): <dynamic>[],
      }),
    );

    expect(find.text('Send email'), findsOneWidget);   // Details-page entry point
    expect(find.text('Emails'), findsOneWidget);       // the Email tab itself (FR10)
  });
}
