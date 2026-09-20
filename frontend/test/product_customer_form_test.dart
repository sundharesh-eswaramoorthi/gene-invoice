import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/products/products_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

/// An administrator with nothing to do with email: the forms then show no Notify box, and the
/// only messages on screen are the forms' own.
CurrentUser _admin() => const CurrentUser(
      id: 1,
      username: 'admin',
      fullName: 'Ada Admin',
      role: 'ADMIN',
      privileges: {Privileges.productManage, Privileges.customerManage},
      customerId: null,
    );

FakeBackend _backend() => FakeBackend({
      'POST /api/products': (o) => {...(o.data as Map).cast<String, dynamic>(), 'id': 77},
      'POST /api/customers': (o) => {...(o.data as Map).cast<String, dynamic>(), 'id': 42},
    });

/// Opens [dialog] the way a list page's "New …" button does, on a page of its own.
Future<void> _open(WidgetTester tester, FakeBackend backend, Widget dialog) async {
  tester.view.physicalSize = const Size(1000, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_admin()),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog(context: context, builder: (_) => dialog),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

Future<void> _save(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(FilledButton, 'Save'));
  await tester.pumpAndSettle();
}

void main() {
  group('a "Required" caption goes as soon as the field is filled in (CP-09)', () {
    testWidgets('the product form', (tester) async {
      final backend = _backend();
      await _open(tester, backend, const ProductFormDialog());

      await _save(tester);
      // Name and Price, both empty.
      expect(find.text('Required'), findsNWidgets(2));
      expect(backend.sent('POST /api/products'), isEmpty);

      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'TRI Widget');
      await tester.pump();
      expect(find.text('Required'), findsOneWidget);

      await tester.enterText(find.widgetWithText(TextFormField, 'Price'), '19.95');
      await tester.pump();
      expect(find.text('Required'), findsNothing);
      // A price that is not a price still says so, without waiting for another Save.
      await tester.enterText(find.widgetWithText(TextFormField, 'Price'), 'free');
      await tester.pump();
      expect(find.text('Invalid price'), findsOneWidget);
    });

    testWidgets('the customer form', (tester) async {
      final backend = _backend();
      await _open(tester, backend, const CustomerFormDialog());

      await _save(tester);
      // Name, Username and Password.
      expect(find.text('Required'), findsNWidgets(3));
      expect(backend.sent('POST /api/customers'), isEmpty);

      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'TRI Traders');
      await tester.pump();
      expect(find.text('Required'), findsNWidgets(2));

      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'tri');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'Demo1234!');
      await tester.pump();
      expect(find.text('Required'), findsNothing);
    });
  });

  group('a box left empty is sent as no value, not as an empty one (CP-15)', () {
    testWidgets('a product with no description', (tester) async {
      final backend = _backend();
      await _open(tester, backend, const ProductFormDialog());

      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), '  TRI Widget  ');
      await tester.enterText(find.widgetWithText(TextFormField, 'Price'), '19.95');
      await _save(tester);

      final sent = backend.sent('POST /api/products').single.data as Map;
      expect(sent['name'], 'TRI Widget');
      expect(sent['description'], isNull);
    });

    testWidgets('a product whose description is only spaces', (tester) async {
      final backend = _backend();
      await _open(tester, backend, const ProductFormDialog());

      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'TRI Widget');
      await tester.enterText(find.widgetWithText(TextFormField, 'Description'), '   ');
      await tester.enterText(find.widgetWithText(TextFormField, 'Price'), '19.95');
      await _save(tester);

      expect((backend.sent('POST /api/products').single.data as Map)['description'], isNull);
    });

    testWidgets('a customer with no phone, email or address', (tester) async {
      final backend = _backend();
      await _open(tester, backend, const CustomerFormDialog());

      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'TRI Traders');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'tri');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'Demo1234!');
      await _save(tester);

      final sent = backend.sent('POST /api/customers').single.data as Map;
      expect(sent['name'], 'TRI Traders');
      expect(sent['phone'], isNull);
      expect(sent['email'], isNull);
      expect(sent['address'], isNull);
      // What was typed still goes, without the spaces around it.
      expect(sent['username'], 'tri');
    });
  });
}
