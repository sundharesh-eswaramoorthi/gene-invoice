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
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/roboto.dart';

// A customer's name is theirs to choose, and the API takes 120 characters of it
// (FieldLimits.FULL_NAME). One such name used to widen the Name column to the text itself and
// push Phone, Email, the figures and the POC columns off a 1366x900 screen, leaving a checkbox
// and a wall of letters (UI-01). Measured here as that browser shows it: the real screen in the
// real shell, its sidebar contracted as it starts, desktop density, text in Roboto.

const _longName = 'NNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN'
    'NNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN'; // 120, as the API allows

const _admin = CurrentUser(
  id: 1,
  username: 'admin',
  fullName: 'System Administrator',
  role: 'ADMIN',
  privileges: {
    Privileges.customerView,
    Privileges.customerManage,
    Privileges.pocView,
    Privileges.pocAssign,
  },
  customerId: null,
);

Map<String, dynamic> _person(int id) =>
    {'id': id, 'username': 'user$id', 'fullName': 'Anita Rao', 'email': 'anita@company.com'};

Map<String, dynamic> _customer(int id, String name) => {
      'id': id,
      'name': name,
      'phone': '+91 98450 12345',
      'email': 'accounts@acme.example',
      'outstanding': 125000.0,
      'creditBalance': 4500.0,
      'successPocs': [
        {'id': id * 10, 'pocType': 'SUCCESS', 'primary': true, 'user': _person(8)},
      ],
      'collectionPocs': [
        {'id': id * 10 + 1, 'pocType': 'COLLECTION', 'primary': true, 'user': _person(9)},
      ],
      'pocMissing': false,
    };

Dio _dio(List<Map<String, dynamic>> rows) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (o, handler) {
    final Object data = o.path.startsWith('/api/table-schemas/')
        ? {'entity': 'customers', 'columns': <dynamic>[]}
        : o.path.endsWith('/summary')
            ? <String, dynamic>{}
            : {
                'content': rows,
                'page': 0,
                'size': 20,
                'totalElements': rows.length,
                'totalPages': 1,
              };
    handler.resolve(Response(requestOptions: o, statusCode: 200, data: data));
  }));

Future<void> _pump(WidgetTester tester, List<Map<String, dynamic>> rows) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: '/customers',
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(
              path: '/customers',
              builder: (_, __) => const CustomersScreen(query: TableQuery(sort: 'name,asc'))),
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
}

/// The columns the customers list is there for, beyond the name.
const _otherColumns = ['Phone', 'Email', 'Outstanding', 'Credit', 'Success POC', 'Collection POC'];

void main() {
  setUpAll(loadRoboto);

  testWidgets('a 120-character name does not push the other columns off a 1366px screen',
      (tester) async {
    await _pump(tester, [
      _customer(1, 'Acme Ltd'),
      _customer(2, _longName),
      _customer(3, 'Zenith Traders'),
    ]);
    expect(tester.takeException(), isNull);

    // The name itself is cut to the column, not laid out at its full length.
    final name = tester.getRect(find.text(_longName));
    expect(name.width, lessThanOrEqualTo(320), reason: 'name cell is $name');
    final text = tester.widget<Text>(find.text(_longName));
    expect(text.overflow, TextOverflow.ellipsis);

    // So every other column is still on screen, beside the 80px contracted rail.
    for (final label in _otherColumns) {
      final header = tester.getRect(find.text(label));
      expect(header.right, lessThanOrEqualTo(1366), reason: '$label header at $header');
      expect(header.left, greaterThanOrEqualTo(80), reason: '$label header at $header');
    }
    // Whoever needs the whole name can read it without leaving the list.
    expect(
        tester.widgetList<Tooltip>(find.byType(Tooltip)).where((t) => t.message == _longName),
        isNotEmpty);
  }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));

  testWidgets('the long name costs the other columns nothing they were not already using',
      (tester) async {
    double rightEdgeOfCollectionPoc(WidgetTester tester) =>
        tester.getRect(find.text('Collection POC')).right;

    await _pump(tester, [_customer(1, 'Acme Ltd'), _customer(3, 'Zenith Traders')]);
    final without = rightEdgeOfCollectionPoc(tester);

    await _pump(tester, [
      _customer(1, 'Acme Ltd'),
      _customer(2, _longName),
      _customer(3, 'Zenith Traders'),
    ]);
    final with120 = rightEdgeOfCollectionPoc(tester);

    // The Name column grows to its cap and stops; it used to grow to the text, which ran the
    // table out to x≈2138 on a 1366 screen.
    expect(with120 - without, lessThanOrEqualTo(320));
  }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));
}
