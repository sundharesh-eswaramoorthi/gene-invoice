import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/features/payments/payments_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/roboto.dart';

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
    Privileges.paymentView,
    Privileges.paymentManage,
    Privileges.promiseView,
    Privileges.promiseManage,
    Privileges.pocView,
    Privileges.pocAssign,
    Privileges.exportData,
    Privileges.emailView,
    Privileges.emailSend,
  },
  customerId: null,
);

const _customer = 'Sri Venkateswara Chemicals 104';
const _poc = 'Karthikeyan Subramaniam';
const _crores = 12345678.90;

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
    {'content': rows, 'page': 0, 'size': 20, 'totalElements': rows.length, 'totalPages': 1};

Map<String, dynamic> _person(int id) =>
    {'id': id, 'username': 'user$id', 'fullName': _poc, 'email': 'user$id@company.com'};

Object? _answer(RequestOptions o) {
  if (o.path.startsWith('/api/table-schemas/')) return {'entity': 'x', 'columns': []};
  if (o.path.endsWith('/summary')) return <String, dynamic>{};
  return switch (o.path) {
    '/api/invoices' => _page([
        for (var i = 1; i <= 3; i++)
          {
            'id': i,
            'invoiceNumber': 'INV-20260917-100$i',
            'customerId': 5,
            'customerName': _customer,
            'invoiceDate': '2026-09-17',
            'total': _crores,
            'paidAmount': i == 2 ? _crores - 100 : 0,
            'balance': i == 2 ? 100 : _crores,
            'status': i == 2 ? 'PARTIALLY_PAID' : 'UNPAID',
            'salesPoc': _person(7),
            'pocMissing': i == 1,
          },
      ]),
    '/api/customers' => _page([
        for (var i = 1; i <= 3; i++)
          {
            'id': i,
            'name': _customer,
            'phone': '+91 98450 12345',
            'email': 'accounts.payable@svchemicals.co.in',
            'creditBalance': _crores,
            'outstanding': _crores,
            'successPocs': [
              {'id': 1, 'pocType': 'SUCCESS', 'primary': true, 'user': _person(8)},
            ],
            'collectionPocs': [
              {'id': 2, 'pocType': 'COLLECTION', 'primary': true, 'user': _person(9)},
            ],
            'pocMissing': i == 1,
          },
      ]),
    '/api/payments' => _page([
        for (var i = 1; i <= 3; i++)
          {
            'id': 10000 + i,
            'customerId': 5,
            'customerName': _customer,
            'amount': _crores,
            'creditApplied': _crores,
            'method': 'Bank transfer (NEFT)',
            'paidAt': '2026-09-17T18:22:00Z',
            'status': 'ACTIVE',
            'collectionPoc': _person(9),
            'pocMissing': i == 1,
          },
      ]),
    _ => null,
  };
}

Future<void> _pump(WidgetTester tester, String location) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final dio = Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (o, handler) {
      handler.resolve(Response(requestOptions: o, statusCode: 200, data: _answer(o)));
    }));
  final router = GoRouter(
    initialLocation: location,
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(
              path: '/invoices',
              builder: (_, __) =>
                  const InvoicesScreen(query: TableQuery(sort: 'invoiceDate,desc'))),
          GoRoute(
              path: '/customers',
              builder: (_, __) => const CustomersScreen(query: TableQuery(sort: 'name,asc'))),
          GoRoute(
              path: '/payments',
              builder: (_, __) => const PaymentsScreen(query: TableQuery(sort: 'paidAt,desc'))),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(dio),
      currentUserProvider.overrideWithValue(_admin),
      unreadCountProvider.overrideWith((ref) => Stream.value(0)),
      inboxUnreadCountProvider.overrideWith((ref) => Stream.value(0)),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(loadRoboto);

  const lists = {
    '/invoices': ['Open', 'Send email', 'Raise promise', 'Cancel invoice'],
    '/customers': ['Open', 'Send email', 'Raise promise'],
    '/payments': ['Open', 'Send email'],
  };

  for (final MapEntry(key: location, value: tooltips) in lists.entries) {
    testWidgets('at 1366px the $location row actions are on screen, however wide the columns',
        (tester) async {
      await _pump(tester, location);
      expect(tester.takeException(), isNull);

      expect(tester.getSize(find.byType(NavigationRail)).width, 80);
      final sideways = tester
          .widgetList<SingleChildScrollView>(find.byType(SingleChildScrollView))
          .singleWhere((s) => s.scrollDirection == Axis.horizontal)
          .controller!;
      expect(sideways.position.maxScrollExtent, greaterThan(0));
      final scrollbars = tester.widgetList<Scrollbar>(find.byType(Scrollbar));
      expect(scrollbars.where((s) => s.thumbVisibility == true), hasLength(1));
      expect(tester.widget<DataTable>(find.byType(DataTable).first).columnSpacing, 24);

      for (final tip in tooltips) {
        final buttons = find.byTooltip(tip);
        final count = buttons.evaluate().length;
        expect(count, greaterThan(0), reason: tip);
        for (var i = 0; i < count; i++) {
          final rect = tester.getRect(buttons.at(i));
          expect(rect.left, greaterThanOrEqualTo(81), reason: '$location $tip at $rect');
          expect(rect.right, lessThanOrEqualTo(1366), reason: '$location $tip at $rect');
          expect(rect.width, greaterThanOrEqualTo(32), reason: '$location $tip is $rect');
          expect(rect.height, greaterThanOrEqualTo(32), reason: '$location $tip is $rect');
        }
        expect(buttons.hitTestable(), findsNWidgets(count), reason: '$location $tip');
      }
      final open = find.byTooltip('Open');
      final checkboxes = find.byType(Checkbox);
      expect(open, findsNWidgets(3));
      expect(checkboxes, findsNWidgets(4));
      for (var i = 0; i < 3; i++) {
        expect(tester.getCenter(open.at(i)).dy,
            moreOrLessEquals(tester.getCenter(checkboxes.at(i + 1)).dy, epsilon: 0.5),
            reason: '$location row $i');
      }
    }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));
  }
}
