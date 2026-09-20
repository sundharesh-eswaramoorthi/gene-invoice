import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// `DELETE /api/customers/{id}` has always been there, behind CUSTOMER_MANAGE, and nothing in the
// app ever called it: a customer entered by mistake — a duplicate, the wrong name, the wrong
// login — could never be removed, and went on appearing in every list, picker and recipient
// list (CP-03). The detail screen now offers it, to those who may manage customers.

CurrentUser _user(Set<String> privileges) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: 'CASHIER',
      privileges: {...privileges, Privileges.auditView},
      customerId: null,
    );

const _acme = {
  'id': 5,
  'name': 'Acme Ltd',
  'username': 'acme',
  'email': 'ap@acme.example',
  'outstanding': 0,
  'creditBalance': 0,
};

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 20, 'totalElements': rows.length, 'totalPages': 1};

FakeBackend _backend({Object? Function(dynamic)? onDelete}) => FakeBackend({
      'GET /api/customers/5': (_) => _acme,
      'GET /api/audit': (_) => _page([]),
      if (onDelete != null) 'DELETE /api/customers/5': onDelete,
    });

Future<GoRouter> _pump(WidgetTester tester, FakeBackend api, CurrentUser user) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: '/customers/5',
    routes: [
      GoRoute(
          path: '/customers',
          builder: (_, __) => const Scaffold(body: Text('customers list'))),
      GoRoute(
        path: '/customers/:id',
        builder: (_, __) => const Scaffold(body: CustomerDetailScreen(id: 5)),
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(api.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

Finder _inDialog(String text) =>
    find.descendant(of: find.byType(AlertDialog), matching: find.text(text));

void main() {
  testWidgets('CUSTOMER_MANAGE deletes a customer, after a confirmation that names it',
      (tester) async {
    final api = _backend(onDelete: (_) => null);
    final router = await _pump(
        tester, api, _user({Privileges.customerView, Privileges.customerManage}));

    expect(find.text('Delete customer'), findsOneWidget);
    await tester.tap(find.text('Delete customer'));
    await tester.pumpAndSettle();

    // Which customer, and what goes with it.
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(_inDialog('Delete this customer?'), findsOneWidget);
    final warning = tester
        .widgetList<Text>(find.descendant(of: find.byType(AlertDialog), matching: find.byType(Text)))
        .map((t) => t.data ?? '')
        .join(' ');
    expect(warning, contains('Acme Ltd'));
    expect(warning, contains('@acme'));
    expect(warning, contains('document'));
    expect(warning, contains('cannot be undone'));
    // Nothing is sent while the question is still open.
    expect(api.sent('DELETE /api/customers/5'), isEmpty);

    await tester.tap(_inDialog('Delete customer'));
    await tester.pumpAndSettle();

    expect(api.sent('DELETE /api/customers/5'), hasLength(1));
    expect(router.routerDelegate.currentConfiguration.uri.path, '/customers');
    expect(find.text('Acme Ltd deleted'), findsOneWidget);
  });

  testWidgets('changing your mind deletes nothing', (tester) async {
    final api = _backend(onDelete: (_) => null);
    final router = await _pump(
        tester, api, _user({Privileges.customerView, Privileges.customerManage}));

    await tester.tap(find.text('Delete customer'));
    await tester.pumpAndSettle();
    await tester.tap(_inDialog('Keep it'));
    await tester.pumpAndSettle();

    expect(api.sent('DELETE /api/customers/5'), isEmpty);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/customers/5');
  });

  testWidgets('a customer with invoices on it is refused, in words, and stays put',
      (tester) async {
    final api = _backend(
        onDelete: (_) =>
            const FakeFailure(409, {'message': 'This change conflicts with existing data'}));
    final router = await _pump(
        tester, api, _user({Privileges.customerView, Privileges.customerManage}));

    await tester.tap(find.text('Delete customer'));
    await tester.pumpAndSettle();
    await tester.tap(_inDialog('Delete customer'));
    await tester.pumpAndSettle();

    expect(find.textContaining('invoices or payments'), findsOneWidget);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/customers/5');
    // The record is still there to read.
    expect(find.text('Acme Ltd'), findsWidgets);
  });

  testWidgets('a refusal the server explains is shown in its own words', (tester) async {
    final api = _backend(
        onDelete: (_) =>
            const FakeFailure(403, {'message': 'This customer is not in your book'}));
    await _pump(tester, api, _user({Privileges.customerView, Privileges.customerManage}));

    await tester.tap(find.text('Delete customer'));
    await tester.pumpAndSettle();
    await tester.tap(_inDialog('Delete customer'));
    await tester.pumpAndSettle();

    expect(find.text('This customer is not in your book'), findsOneWidget);
  });

  testWidgets('without CUSTOMER_MANAGE there is nothing to delete with', (tester) async {
    final api = _backend();
    await _pump(tester, api, _user({Privileges.customerView}));

    expect(find.text('Acme Ltd'), findsWidgets);
    expect(find.text('Delete customer'), findsNothing);
  });
}
