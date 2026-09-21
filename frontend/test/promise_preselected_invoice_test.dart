import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/promises/promise_form_dialog.dart';
import 'package:gene_invoice/features/promises/promises_tab.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/invoice.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

// A promise is raised from a screen that is about one invoice, and that invoice is ticked for
// the user. It was ticked invisibly: the checklist was built from the customer's *outstanding*
// invoices, so an invoice with nothing left owed — fully paid, or cancelled — had no line and no
// checkbox, yet was still submitted. The promise came back scoped to an invoice the user never
// saw, instantly KEPT with money still shown as remaining (UI-02).

const _user = CurrentUser(
  id: 1,
  username: 'admin',
  fullName: 'Ada Admin',
  role: 'ADMIN',
  privileges: {Privileges.promiseView, Privileges.promiseManage, Privileges.pocView},
  customerId: null,
);

Map<String, dynamic> _invoice(int id, String number,
        {required String status, required double total, required double balance}) =>
    {
      'id': id,
      'invoiceNumber': number,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-08-01T00:00:00Z',
      'dueDate': '2026-08-31',
      'total': total,
      'paidAmount': total - balance,
      'balance': balance,
      'status': status,
    };

final _settled =
    _invoice(150, 'INV-0150', status: 'FULLY_PAID', total: 1000, balance: 0);

final _open = _invoice(151, 'INV-0151', status: 'UNPAID', total: 400, balance: 400);

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 20, 'totalElements': rows.length, 'totalPages': 1};

FakeBackend _backend() => FakeBackend({
      'GET /api/customers/5/pocs': (_) => [],
      'GET /api/pocs/assignable': (_) => [],
      'GET /api/invoices': (_) => _page([_open]),
      'GET /api/promises': (_) => _page([]),
      'POST /api/promises': (_) => {'id': 90},
    });

Future<void> _pump(WidgetTester tester, FakeBackend api, Widget home) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(api.dio),
      currentUserProvider.overrideWithValue(_user),
    ],
    child: MaterialApp(theme: AppTheme.light(), home: Scaffold(body: home)),
  ));
  await tester.pumpAndSettle();
}

Future<void> _openDialogOn(WidgetTester tester, FakeBackend api, Map<String, dynamic> on) async {
  await _pump(
    tester,
    api,
    Builder(
      builder: (context) => Center(
        child: TextButton(
          onPressed: () => showPromiseDialog(
            context: context,
            customerId: 5,
            customerName: 'Acme Ltd',
            preselectedInvoices: [InvoiceSummary.fromJson(on)],
          ),
          child: const Text('open'),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

List<int> _invoiceIdsSent(FakeBackend api) =>
    ((api.sent('POST /api/promises').single.data as Map)['invoiceIds'] as List).cast<int>();

void main() {
  testWidgets('an invoice with nothing left owed is shown ticked, not linked behind the scenes',
      (tester) async {
    final api = _backend();
    await _openDialogOn(tester, api, _settled);

    expect(find.text('INV-0150'), findsOneWidget);
    expect(find.text('INV-0151'), findsOneWidget);
    expect(find.text('Fully paid'), findsOneWidget);
    expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'INV-0150')).value,
        isTrue);
    expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'INV-0151')).value,
        isFalse);

    await tester.enterText(find.byType(TextField).first, '1000');
    await tester.pumpAndSettle();
    expect(find.text('Promised ₹1,000.00 more than those invoices owe.'), findsOneWidget);

    await tester.tap(find.text('Raise promise'));
    await tester.pumpAndSettle();
    expect(_invoiceIdsSent(api), [150]);
  });

  testWidgets('and can be unticked, which makes it a general promise', (tester) async {
    final api = _backend();
    await _openDialogOn(tester, api, _settled);

    await tester.tap(find.widgetWithText(CheckboxListTile, 'INV-0150'));
    await tester.pumpAndSettle();
    expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'INV-0150')).value,
        isFalse);

    await tester.enterText(find.byType(TextField).first, '1000');
    await tester.tap(find.text('Raise promise'));
    await tester.pumpAndSettle();
    expect(_invoiceIdsSent(api), isEmpty);
  });

  testWidgets('an outstanding invoice is offered once, ticked', (tester) async {
    final api = _backend();
    await _openDialogOn(tester, api, _open);

    expect(find.text('INV-0151'), findsOneWidget);
    expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'INV-0151')).value,
        isTrue);

    await tester.enterText(find.byType(TextField).first, '400');
    await tester.tap(find.text('Raise promise'));
    await tester.pumpAndSettle();
    expect(_invoiceIdsSent(api), [151]);
  });

  testWidgets('an invoice the promise is scoped to is shown even when the list cannot be fetched',
      (tester) async {
    final api = FakeBackend({
      'GET /api/customers/5/pocs': (_) => [],
      'GET /api/pocs/assignable': (_) => [],
      'GET /api/invoices': (_) => const FakeFailure(500, {'message': 'Unexpected error'}),
      'POST /api/promises': (_) => {'id': 90},
    });
    await _openDialogOn(tester, api, _settled);

    expect(find.textContaining('Could not load invoices'), findsOneWidget);
    expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'INV-0150')).value,
        isTrue);

    await tester.tap(find.widgetWithText(CheckboxListTile, 'INV-0150'));
    await tester.enterText(find.byType(TextField).first, '1000');
    await tester.tap(find.text('Raise promise'));
    await tester.pumpAndSettle();
    expect(_invoiceIdsSent(api), isEmpty);
  });

  testWidgets('the Payment Promise tab of a settled invoice hands the dialog that invoice',
      (tester) async {
    final api = _backend();
    await _pump(
      tester,
      api,
      PromisesTab(
        customerId: 5,
        customerName: 'Acme Ltd',
        invoice: InvoiceSummary.fromJson(_settled),
      ),
    );

    await tester.tap(find.text('Raise promise'));
    await tester.pumpAndSettle();

    expect(find.widgetWithText(CheckboxListTile, 'INV-0150'), findsOneWidget);
    expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'INV-0150')).value,
        isTrue);
  });
}
