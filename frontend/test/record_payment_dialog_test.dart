import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/payments/record_payment_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

// What the Record payment dialog says while it is being filled in: a complaint about a field
// belongs to the state the form was in when it was made, and goes once that state has gone
// (UI-04).

CurrentUser _cashier() => const CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: 'CASHIER',
      privileges: {Privileges.paymentView, Privileges.paymentManage},
      customerId: null,
    );

const _acme = {'id': 5, 'name': 'Acme Ltd', 'email': 'ap@acme.com', 'creditBalance': 0};

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

FakeBackend _backend() => FakeBackend({
      'GET /api/customers': (_) => _page([_acme]),
      'GET /api/customers/5/pocs': (_) => [
            {
              'id': 1,
              'pocType': 'COLLECTION',
              'primary': true,
              'user': {'id': 12, 'username': 'bob', 'fullName': 'Bob Smith'},
            },
          ],
      'GET /api/invoices': (_) => _page([]),
      'GET /api/promises': (_) => _page([]),
      'POST /api/payments': (_) => {'id': 77},
    });

/// Opens the dialog with no customer chosen, the way the payments page's button does.
Future<void> _open(WidgetTester tester, FakeBackend backend) async {
  tester.view.physicalSize = const Size(1000, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_cashier()),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showRecordPaymentDialog(context: context),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

Future<void> _pickAcme(WidgetTester tester) async {
  await tester.tap(find.text('Select…'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Acme Ltd').last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('"Pick a customer" goes as soon as one is picked', (tester) async {
    final backend = _backend();
    await _open(tester, backend);

    await tester.tap(find.widgetWithText(FilledButton, 'Record'));
    await tester.pumpAndSettle();
    // The field says it, and so does the form below it.
    expect(find.text('Pick a customer'), findsWidgets);
    expect(backend.sent('POST /api/payments'), isEmpty);

    await _pickAcme(tester);

    expect(find.text('Pick a customer'), findsNothing);
  });

  testWidgets('the amount complaint goes as soon as an amount is typed', (tester) async {
    final backend = _backend();
    await _open(tester, backend);
    await _pickAcme(tester);

    await tester.tap(find.widgetWithText(FilledButton, 'Record'));
    await tester.pumpAndSettle();
    const complaint = 'Enter an amount greater than zero, with at most 2 decimal places';
    expect(find.text(complaint), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'Amount *'), '250');
    await tester.pumpAndSettle();

    expect(find.text(complaint), findsNothing);
    // The form still works once the complaint has gone.
    await tester.tap(find.widgetWithText(FilledButton, 'Record'));
    await tester.pumpAndSettle();
    expect((backend.sent('POST /api/payments').single.data as Map)['amount'], 250);
  });
}
