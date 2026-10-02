import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/disputes/disputes_screen.dart';
import 'package:gene_invoice/features/disputes/disputes_tab.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/features/payments/payments_screen.dart';
import 'package:gene_invoice/features/promises/promises_screen.dart';
import 'package:gene_invoice/features/promises/promises_tab.dart';
import 'package:gene_invoice/features/tasks/task_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/dispute.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:gene_invoice/shared/widgets/status_chip.dart';
import 'package:go_router/go_router.dart';

import 'support/roboto.dart';

const _viewer = CurrentUser(
  id: 1,
  username: 'admin',
  fullName: 'System Administrator',
  role: 'ADMIN',
  privileges: {
    Privileges.customerView,
    Privileges.invoiceView,
    Privileges.paymentView,
    Privileges.promiseView,
    Privileges.disputeView,
  },
  customerId: null,
);

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 20, 'totalElements': rows.length, 'totalPages': 1};

/// A row as the list endpoint sends it; [pending] null leaves the flag off the row altogether.
Map<String, dynamic> _flag(Map<String, dynamic> row, bool? pending) =>
    pending == null ? row : {...row, 'pendingApproval': pending};

Map<String, dynamic> _invoice(bool? pending) => _flag({
      'id': 1,
      'invoiceNumber': 'INV-20260917-1001',
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-09-17',
      'dueDate': '2026-10-17',
      'total': 125000.0,
      'paidAmount': 0.0,
      'balance': 125000.0,
      'status': 'UNPAID',
      'pocMissing': false,
    }, pending);

Map<String, dynamic> _payment(bool? pending) => _flag({
      'id': 7,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'amount': 500.0,
      'paidAt': '2026-06-10T10:00:00Z',
      'status': 'ACTIVE',
      'invoices': [],
    }, pending);

Map<String, dynamic> _promise(bool? pending) => _flag({
      'id': 3,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'amount': 500.0,
      'remainingAmount': 500.0,
      'promisedDate': '2026-07-15',
      'status': 'OPEN',
    }, pending);

Map<String, dynamic> _dispute(bool? pending) => _flag({
      'id': 11,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'openedByUserId': 9,
      'targetType': 'INVOICE',
      'targetId': 42,
      'targetNumber': 'INV-0042',
      'reason': 'Billed twice',
      'status': 'PENDING',
      'createdAt': '2026-06-02T10:00:00Z',
    }, pending);

Dio _dio(String listPath, Map<String, dynamic> row) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (o, handler) {
    final Object data = o.path.startsWith('/api/table-schemas/')
        ? {'entity': 'x', 'columns': <dynamic>[]}
        : o.path.endsWith('/summary')
            ? <String, dynamic>{}
            : _page(o.path == listPath ? [row] : const []);
    handler.resolve(Response(requestOptions: o, statusCode: 200, data: data));
  }));

Future<void> _pump(
  WidgetTester tester,
  Widget page,
  String listPath,
  Map<String, dynamic> row, {
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [GoRoute(path: '/page', builder: (_, __) => page)],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(_dio(listPath, row)),
      currentUserProvider.overrideWithValue(_viewer),
      unreadCountProvider.overrideWith((ref) => Stream.value(0)),
      inboxUnreadCountProvider.overrideWith((ref) => Stream.value(0)),
      myTasksCountProvider.overrideWith((ref) => Stream.value(0)),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
}

class _Site {
  final String name;
  final Widget page;
  final String listPath;
  final Map<String, dynamic> Function(bool? pending) row;

  /// The widget the row is drawn in, which must hold the chip when the row is held.
  final Finder Function() rowWidget;
  final bool phone;

  const _Site(this.name, this.page, this.listPath, this.row, this.rowWidget,
      {this.phone = false});
}

TableQuery _q(String sort) => TableQuery(page: 0, size: 20, sort: sort);

final _lists = [
  (name: 'invoices list', page: InvoicesScreen(query: _q('invoiceDate,desc')), path: '/api/invoices', row: _invoice, status: 'Unpaid'),
  (name: 'payments list', page: PaymentsScreen(query: _q('paidAt,desc')), path: '/api/payments', row: _payment, status: 'Active'),
  (name: 'promises list', page: PromisesScreen(query: _q('promisedDate,desc')), path: '/api/promises', row: _promise, status: 'Open'),
  (name: 'disputes list', page: DisputesScreen(query: _q('createdAt,desc')), path: '/api/disputes', row: _dispute, status: 'Pending'),
];

final _sites = [
  for (final l in _lists) ...[
    _Site(l.name, l.page, l.path, l.row, () => find.byType(DataTable)),
    // On a phone the rows are cards built from the same cells.
    _Site('${l.name} on a phone', l.page, l.path, l.row, () => find.byType(Card), phone: true),
  ],
  _Site('promise card', const PromisesTab(customerId: 5, customerName: 'Acme Ltd'),
      '/api/promises', _promise, () => find.byType(PromiseCard)),
  _Site(
      'disputes tab',
      const DisputesTab(targetType: DisputeTargetType.INVOICE, targetId: 42, targetLabel: 'INV-0042'),
      '/api/disputes',
      _dispute,
      () => find.byType(ListTile)),
];

void main() {
  setUpAll(loadRoboto);

  for (final site in _sites) {
    final size = site.phone ? const Size(390, 844) : const Size(1366, 900);

    testWidgets('${site.name}: a row with a change held for approval says so', (tester) async {
      await _pump(tester, site.page, site.listPath, site.row(true), size: size);
      expect(tester.takeException(), isNull);

      // The held change has not taken effect, so the row keeps its status and says a change is
      // waiting beside it, rather than looking like any other row until an action gets a 409 (B2).
      final chip = find.descendant(
          of: site.rowWidget(), matching: find.byType(PendingApprovalChip));
      expect(chip, findsOneWidget);
      expect(find.descendant(of: chip, matching: find.text('Awaiting approval')), findsOneWidget);
      expect(
          tester
              .widgetList<Tooltip>(find.ancestor(of: find.text('Awaiting approval'), matching: find.byType(Tooltip)))
              .map((t) => t.message),
          contains('A change is waiting for approval'));
    }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));

    for (final pending in [false, null]) {
      testWidgets(
          '${site.name}: no chip when pendingApproval is ${pending == null ? 'absent' : 'false'}',
          (tester) async {
        await _pump(tester, site.page, site.listPath, site.row(pending), size: size);
        expect(tester.takeException(), isNull);

        expect(site.rowWidget(), findsWidgets, reason: 'the row is drawn');
        expect(find.byType(PendingApprovalChip), findsNothing);
        expect(find.text('Awaiting approval'), findsNothing);
      }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));
    }
  }

  // The status the row shows is still its own: the chip sits beside it, not in its place.
  for (final l in _lists) {
    testWidgets('${l.name}: the held row keeps its own status chip', (tester) async {
      await _pump(tester, l.page, l.path, l.row(true));
      final pair = find.ancestor(
          of: find.byType(PendingApprovalChip), matching: find.byType(Wrap)).first;
      expect(find.descendant(of: pair, matching: find.text(l.status)), findsOneWidget);
      expect(find.descendant(of: pair, matching: find.text('Awaiting approval')), findsOneWidget);
    }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));
  }
}
