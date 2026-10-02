import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/approvals/approval_providers.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/payment_term.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// APP-R1-2: the invoice page sends only the fields the user changed. PATCH /api/invoices/{id}
// treats an absent field as unchanged, so a held terms change never claims the notes.

const _managing = {
  Privileges.customerView,
  Privileges.invoiceView,
  Privileges.invoiceManage,
};

const _north = RegionAccess(
    id: 1,
    code: 'NORTH',
    name: 'North',
    defaultRegion: true,
    level: 'MANAGE',
    scope: 'ALL',
    privileges: _managing);

const _mia = CurrentUser(
  id: 3,
  username: 'mia',
  fullName: 'Mia Maker',
  role: 'CASHIER',
  privileges: _managing,
  customerId: null,
  regions: [_north],
);

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

Map<String, dynamic> _invoice({String? notes}) => {
      'id': 42,
      'invoiceNumber': 'INV-0042',
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-09-01',
      'dueDate': '2026-10-01',
      'paymentTerm': 'NET_30',
      'total': 2500.0,
      'paidAmount': 0.0,
      'balance': 2500.0,
      'status': 'UNPAID',
      'notes': notes,
      'items': [],
      'regionId': 1,
      'regionCode': 'NORTH',
      'regionName': 'North',
      'canManage': true,
    };

Future<FakeBackend> _pump(WidgetTester tester, {String? notes}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend({
    'GET /api/invoices/42': (_) => _invoice(notes: notes),
    'PATCH /api/invoices/42': (_) => _invoice(notes: notes),
    'GET /api/disputes': (_) => _page(const []),
    'GET /api/promises': (_) => _page(const []),
    'GET /api/approvals/for-record': (_) => const [],
  });
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(_mia),
    approvalsAwaitingMeCountProvider.overrideWith((ref) => Stream.value(0)),
  ]);
  addTearDown(container.dispose);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [GoRoute(path: '/page', builder: (_, __) => const InvoiceDetailScreen(id: 42))],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return backend;
}

Future<void> _tapShown(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

final _notes =
    find.byWidgetPredicate((w) => w is TextField && w.decoration?.hintText == 'Internal notes');

Future<void> _chooseTerm(WidgetTester tester, String label) async {
  await _tapShown(tester, find.byType(DropdownButton<PaymentTerm>));
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

Map<String, dynamic> _body(RequestOptions r) => (r.data as Map).cast<String, dynamic>();

void main() {
  testWidgets('a terms change on an invoice with no notes sends paymentTerm and no notes',
      (tester) async {
    final backend = await _pump(tester);

    await _chooseTerm(tester, 'Net 90');
    await _tapShown(tester, find.text('Save changes'));

    expect(tester.takeException(), isNull);
    final body = _body(backend.sent('PATCH /api/invoices/42').single);
    expect(body['paymentTerm'], 'NET_90');
    expect(body.containsKey('notes'), isFalse);
  });

  testWidgets('a terms change on an invoice with notes leaves the notes out too', (tester) async {
    final backend = await _pump(tester, notes: 'As agreed');

    await _chooseTerm(tester, 'Net 60');
    await _tapShown(tester, find.text('Save changes'));

    final body = _body(backend.sent('PATCH /api/invoices/42').single);
    expect(body['paymentTerm'], 'NET_60');
    expect(body.containsKey('notes'), isFalse);
  });

  testWidgets('a notes change sends the notes, and only once they have been saved', (tester) async {
    final backend = await _pump(tester);

    await tester.enterText(_notes, '  call Friday ');
    await tester.pump();
    await _tapShown(tester, find.text('Save changes'));

    final first = _body(backend.sent('PATCH /api/invoices/42').single);
    expect(first['notes'], 'call Friday');
    expect(first.containsKey('paymentTerm'), isFalse);
    expect(find.text('Invoice saved'), findsOneWidget);

    // What was saved is now the baseline: a later terms change does not send the notes again.
    await _chooseTerm(tester, 'Net 45');
    await _tapShown(tester, find.text('Save changes'));
    final second = _body(backend.sent('PATCH /api/invoices/42')[1]);
    expect(second['paymentTerm'], 'NET_45');
    expect(second.containsKey('notes'), isFalse);
  });

  testWidgets('notes typed and put back as they were send nothing', (tester) async {
    final backend = await _pump(tester, notes: 'As agreed');

    await tester.enterText(_notes, 'Something else');
    await tester.pump();
    await tester.enterText(_notes, 'As agreed ');
    await tester.pump();
    expect(find.text('Unsaved changes'), findsOneWidget);
    await _tapShown(tester, find.text('Save changes'));

    expect(backend.sent('PATCH /api/invoices/42'), isEmpty);
    expect(find.text('Unsaved changes'), findsNothing);
  });
}
