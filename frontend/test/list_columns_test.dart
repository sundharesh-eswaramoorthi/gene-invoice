import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/disputes/disputes_screen.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/roboto.dart';

// What the list columns show and offer, measured as a browser shows them — mostly a 1366x900
// desktop, once a 390x844 phone: the real screens in the real shell, its sidebar contracted as it
// starts, desktop density, text in Roboto (see loadRoboto).

const _admin = CurrentUser(
  id: 1,
  username: 'admin',
  fullName: 'System Administrator',
  role: 'ADMIN',
  privileges: {
    Privileges.customerView,
    Privileges.customerManage,
    Privileges.invoiceView,
    Privileges.invoiceManage,
    Privileges.disputeView,
    Privileges.disputeManage,
    Privileges.pocView,
    Privileges.pocAssign,
  },
  customerId: null,
);

// Longer than the 180px the column has, and a name a person really can have.
const _longPoc = 'Venkataraghavan Balasubramaniam';

Map<String, dynamic> _person(int id, String name, {bool active = true}) => {
      'id': id,
      'username': 'user$id',
      'fullName': name,
      'email': 'user$id@company.com',
      'active': active,
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 20, 'totalElements': rows.length, 'totalPages': 1};

Map<String, dynamic> _invoice(int id) => {
      'id': id,
      'invoiceNumber': 'INV-20260917-100$id',
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-09-17',
      'dueDate': '2026-10-17',
      'total': 125000.0,
      'paidAmount': 0.0,
      'balance': 125000.0,
      'status': 'UNPAID',
      'salesPoc': _person(7, _longPoc),
      'pocMissing': false,
    };

/// A customer whose primary Success POC has since been deactivated — the seat stays, but
/// PocService skips inactive holders, so the app would not write to them (AC-A5).
Map<String, dynamic> _customerWithInactiveSuccessPoc(int id) => {
      'id': id,
      'name': 'Acme Ltd',
      'phone': '+91 98450 12345',
      'email': 'accounts@acme.example',
      'outstanding': 125000.0,
      'creditBalance': 0.0,
      'successPocs': [
        {
          'id': 10,
          'pocType': 'SUCCESS',
          'primary': true,
          'user': _person(8, 'Anita Rao', active: false),
        },
      ],
      'collectionPocs': [
        {
          'id': 11,
          'pocType': 'COLLECTION',
          'primary': true,
          'user': _person(9, 'Cleo Collections'),
        },
      ],
      'pocMissing': false,
    };

Map<String, dynamic> _dispute(int id, String customerName) => {
      'id': id,
      'customerId': id,
      'customerName': customerName,
      'openedByUserId': 3,
      'targetType': 'INVOICE',
      'targetId': 1,
      'targetNumber': 'INV-20260917-1001',
      'targetAmount': 125000.0,
      'reason': 'Billed for a licence we cancelled',
      'status': 'PENDING',
      'createdAt': '2026-09-17T10:00:00Z',
    };

Dio _dio(Map<String, List<Map<String, dynamic>>> rowsByPath) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (o, handler) {
    final Object data = o.path.startsWith('/api/table-schemas/')
        ? {'entity': 'x', 'columns': <dynamic>[]}
        : o.path.endsWith('/summary')
            ? <String, dynamic>{}
            : _page(rowsByPath[o.path] ?? const []);
    handler.resolve(Response(requestOptions: o, statusCode: 200, data: data));
  }));

Future<GoRouter> _pump(
  WidgetTester tester,
  String location,
  Map<String, List<Map<String, dynamic>>> rows, {
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: location,
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(
            path: '/invoices',
            builder: (_, s) => InvoicesScreen(
                query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'invoiceDate,desc')),
          ),
          GoRoute(
            path: '/customers',
            builder: (_, s) =>
                CustomersScreen(query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'name,asc')),
          ),
          GoRoute(
            path: '/disputes',
            builder: (_, s) => DisputesScreen(
                query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'createdAt,desc')),
          ),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(_dio(rows)),
      currentUserProvider.overrideWithValue(_admin),
      unreadCountProvider.overrideWith((ref) => Stream.value(0)),
      inboxUnreadCountProvider.overrideWith((ref) => Stream.value(0)),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

void main() {
  setUpAll(loadRoboto);

  testWidgets('a long Sales POC name ends in an ellipsis instead of being cut mid-letter',
      (tester) async {
    await _pump(tester, '/invoices', {
      '/api/invoices': [_invoice(1), _invoice(2)],
    });
    expect(tester.takeException(), isNull);

    // Capped like every other free-text column, so the cut is visible and the whole name is one
    // hover away rather than lost (UI-08).
    final poc = find.text(_longPoc).first;
    expect(tester.getSize(poc).width, lessThanOrEqualTo(180),
        reason: 'Sales POC cell is ${tester.getRect(poc)}');
    expect(tester.widget<Text>(poc).overflow, TextOverflow.ellipsis);
    expect(tester.widgetList<Tooltip>(find.byType(Tooltip)).where((t) => t.message == _longPoc),
        isNotEmpty);
  }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));

  testWidgets('on a phone the invoice list tiles pair up instead of a column of eight',
      (tester) async {
    await _pump(tester, '/invoices', {
      '/api/invoices': [_invoice(1), _invoice(2)],
    }, size: const Size(390, 844));
    expect(tester.takeException(), isNull);

    final tiles = find.byType(SummaryTile);
    expect(tiles, findsNWidgets(8));
    final first = tester.getRect(tiles.at(0));
    final second = tester.getRect(tiles.at(1));

    // Two to a row, using the width, as the dashboard's tiles already do at this size — eight
    // tiles in one 170px column buried the rows under a screen and a half of them (UI-07).
    expect(second.top, first.top);
    expect(second.left, greaterThan(first.right));
    expect(second.right, greaterThan(350));
    expect(tester.getRect(tiles.at(2)).top, greaterThan(first.bottom));
  }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));

  testWidgets('a deactivated POC is marked on the customers list, as the POC editor marks it',
      (tester) async {
    await _pump(tester, '/customers', {
      '/api/customers': [_customerWithInactiveSuccessPoc(1)],
    });
    expect(tester.takeException(), isNull);

    // The app would write to the next active holder instead, so the list must not present this
    // one as the customer's POC without saying so (CP-07).
    expect(find.text('Anita Rao (inactive)'), findsOneWidget);
    expect(find.text('Anita Rao'), findsNothing);
    // An active holder is named plainly.
    expect(find.text('Cleo Collections'), findsOneWidget);
  }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));

  testWidgets('the Disputes list offers the Customer sort its schema publishes', (tester) async {
    final router = await _pump(tester, '/disputes', {
      '/api/disputes': [_dispute(1, 'Acme Ltd'), _dispute(2, 'Globex')],
    });

    // disputes.customerId is sortable over the API and documented as sortable
    // (poc-payment-promise-and-tables.md §6), so the column is not a plain heading (TBL-10).
    await tester.tap(find.text('Customer'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.toString(),
        contains('sort=customerId'));
  }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));
}
