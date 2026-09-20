import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/products/product_detail_screen.dart';
import 'package:gene_invoice/features/products/products_screen.dart';
import 'package:gene_invoice/features/users/role_detail_screen.dart';
import 'package:gene_invoice/features/users/roles_screen.dart';
import 'package:gene_invoice/features/users/user_detail_screen.dart';
import 'package:gene_invoice/features/users/users_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/user.dart';
import 'package:go_router/go_router.dart';

CurrentUser _user(Set<String> privileges) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: 'ADMIN',
      privileges: privileges,
      customerId: null,
    );

Map<String, dynamic> _product() => {
      'id': 7,
      'name': 'Widget',
      'description': 'A small widget',
      'price': 12.5,
      'active': true,
    };

Map<String, dynamic> _emailContext(String type, int id, String label) => {
      'entityType': type,
      'entityId': id,
      'entityLabel': label,
      'delivery': {'configured': true, 'mailbox': 'billing@company.com'},
      'sender': {
        'restricted': false,
        'self': {'userId': 3, 'name': 'Jane Doe', 'email': 'jane@company.com'},
      },
      'roles': [],
      'customerEmails': {'available': false, 'addresses': []},
      'suggestion': {'subject': 'New $label', 'body': '', 'to': []},
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

/// A fake backend: each "METHOD /path" answers with what its handler returns, and every request
/// is kept so a test can read what the screen sent.
class _Backend {
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];
  _Backend(this.routes);

  List<RequestOptions> sent(String key) =>
      requests.where((r) => '${r.method} ${r.path}' == key).toList();

  Dio get dio => Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
      requests.add(options);
      final route = routes['${options.method} ${options.path}'];
      if (route == null) {
        handler.reject(DioException(
          requestOptions: options,
          response: Response(
              requestOptions: options, statusCode: 404, data: {'message': 'no fake route'}),
        ));
        return;
      }
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: route(options)));
    }));
}

_Backend _backend([Map<String, Object? Function(RequestOptions)> extra = const {}]) => _Backend({
      'GET /api/table-schemas/products': (_) => {'entity': 'products', 'columns': []},
      'GET /api/products': (_) => _page([_product()]),
      'GET /api/products/7': (_) => _product(),
      'POST /api/products': (o) => {...(o.data as Map).cast<String, dynamic>(), 'id': 77},
      'PUT /api/products/7': (o) => {...(o.data as Map).cast<String, dynamic>(), 'id': 7},
      'GET /api/roles': (_) => _page([
            {'id': 2, 'name': 'CASHIER', 'description': null, 'privileges': []},
          ]),
      'POST /api/users': (o) => {...(o.data as Map).cast<String, dynamic>(), 'id': 31},
      'GET /api/privileges': (_) => [
            {'name': 'PRODUCT_VIEW'},
          ],
      'POST /api/roles': (o) => {...(o.data as Map).cast<String, dynamic>(), 'id': 9},
      'GET /api/audit': (_) => [],
      'GET /api/emails': (_) => _page([]),
      'GET /api/emails/context': (o) {
        final q = o.queryParameters;
        final type = q['entityType'] as String;
        final id = q['entityId'] as int;
        final names = {'PRODUCT': 'Product Gadget', 'USER': 'User sam', 'ROLE': 'Role Auditor'};
        return _emailContext(type, id, names[type]!);
      },
      ...extra,
    });

Future<void> _pump(
  WidgetTester tester, {
  required _Backend backend,
  required CurrentUser user,
  required String location,
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  int id(GoRouterState s) => int.parse(s.pathParameters['id']!);
  final router = GoRouter(
    initialLocation: location,
    routes: [
      // Stands in for the list pages' "New …" buttons, which open these same forms.
      GoRoute(
        path: '/forms',
        builder: (_, __) => Scaffold(
          body: Consumer(
            builder: (context, ref, _) => Column(children: [
              TextButton(
                  onPressed: () => openProductForm(context, ref), child: const Text('new product')),
              TextButton(onPressed: () => openUserForm(context, ref), child: const Text('new user')),
              TextButton(onPressed: () => openRoleForm(context, ref), child: const Text('new role')),
            ]),
          ),
        ),
      ),
      GoRoute(
          path: '/products',
          builder: (_, __) => const ProductsScreen(query: TableQuery(sort: 'name,asc'))),
      GoRoute(
          path: '/products/:id',
          builder: (_, s) => Scaffold(
              body: ProductDetailScreen(id: id(s), initialTab: s.uri.queryParameters['tab']))),
      GoRoute(
          path: '/users/:id',
          builder: (_, s) => Scaffold(body: UserDetailScreen(id: id(s)))),
      GoRoute(
          path: '/roles/:id',
          builder: (_, s) => Scaffold(body: RoleDetailScreen(id: id(s)))),
      GoRoute(
          path: '/customers/:id',
          builder: (_, s) => Text('customer page ${s.pathParameters['id']}')),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
}

Future<void> _tickNotifyAndSave(WidgetTester tester) async {
  await tester.tap(find.text('Notify through email'));
  await tester.pump();
  await tester.tap(find.text('Save'));
  await tester.pumpAndSettle();
}

/// The compose dialog asked about exactly this record and event.
void _expectComposeFor(_Backend backend, String type, int id) {
  final asked = backend.sent('GET /api/emails/context').single.queryParameters;
  expect(asked, {
    'entityType': type,
    'entityId': id,
    'event': 'CREATED',
    'utcOffsetMinutes': DateTime.now().timeZoneOffset.inMinutes,
  });
}

void main() {
  group('create forms', () {
    const sender = {
      Privileges.productManage,
      Privileges.userManage,
      Privileges.roleManage,
      Privileges.emailSend,
    };

    testWidgets('a new product with Notify ticked opens the compose dialog for the saved product',
        (tester) async {
      final backend = _backend();
      await _pump(tester, backend: backend, user: _user(sender), location: '/forms');

      await tester.tap(find.text('new product'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Gadget');
      await tester.enterText(find.widgetWithText(TextFormField, 'Price'), '9.99');
      await _tickNotifyAndSave(tester);

      expect(backend.sent('POST /api/products').single.data['name'], 'Gadget');
      _expectComposeFor(backend, 'PRODUCT', 77);
      expect(find.text('New product'), findsNothing);
      expect(find.text('About: Product Gadget'), findsOneWidget);
    });

    testWidgets('a new user with Notify ticked opens the compose dialog for the saved user',
        (tester) async {
      final backend = _backend();
      await _pump(tester, backend: backend, user: _user(sender), location: '/forms');

      await tester.tap(find.text('new user'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'sam');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'Secret123!');
      await tester.tap(find.byType(DropdownButtonFormField<AppRole>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CASHIER').last);
      await tester.pumpAndSettle();
      await _tickNotifyAndSave(tester);

      expect(backend.sent('POST /api/users').single.data['roleId'], 2);
      _expectComposeFor(backend, 'USER', 31);
      expect(find.text('About: User sam'), findsOneWidget);
    });

    testWidgets('a new role with Notify ticked opens the compose dialog for the saved role',
        (tester) async {
      final backend = _backend();
      await _pump(tester, backend: backend, user: _user(sender), location: '/forms');

      await tester.tap(find.text('new role'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Auditor');
      await _tickNotifyAndSave(tester);

      expect(backend.sent('POST /api/roles').single.data['name'], 'Auditor');
      _expectComposeFor(backend, 'ROLE', 9);
      expect(find.text('About: Role Auditor'), findsOneWidget);
    });

    testWidgets('left unticked, saving a new product opens no compose dialog', (tester) async {
      final backend = _backend();
      await _pump(tester, backend: backend, user: _user(sender), location: '/forms');

      await tester.tap(find.text('new product'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Gadget');
      await tester.enterText(find.widgetWithText(TextFormField, 'Price'), '9.99');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/products'), hasLength(1));
      expect(backend.sent('GET /api/emails/context'), isEmpty);
      expect(find.text('new product'), findsOneWidget);
    });

    testWidgets('the Notify checkbox needs EMAIL_SEND', (tester) async {
      await _pump(tester,
          backend: _backend(),
          user: _user({Privileges.productManage, Privileges.userManage, Privileges.roleManage}),
          location: '/forms');

      for (final form in ['new product', 'new user', 'new role']) {
        await tester.tap(find.text(form));
        await tester.pumpAndSettle();
        expect(find.text('Save'), findsOneWidget, reason: form);
        expect(find.text('Notify through email'), findsNothing, reason: form);
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }
    });
  });

  group('products list', () {
    testWidgets('a sender who cannot manage products still gets every email action',
        (tester) async {
      await _pump(tester,
          backend: _backend(),
          user: _user({Privileges.productView, Privileges.emailSend}),
          location: '/products');

      expect(find.widgetWithText(OutlinedButton, 'Send email'), findsOneWidget);
      expect(find.byTooltip('Send email'), findsOneWidget);
      expect(find.byTooltip('Edit'), findsNothing);
      expect(find.text('New product'), findsNothing);

      await tester.tap(find.byType(Checkbox).last);
      await tester.pumpAndSettle();
      expect(find.widgetWithText(TextButton, 'Send email'), findsOneWidget);
      expect(find.text('Activate'), findsNothing);
    });

    testWidgets('a row opens the product page, whoever is looking', (tester) async {
      await _pump(tester,
          backend: _backend(), user: _user({Privileges.productView}), location: '/products');

      // Someone who may neither edit nor send has nothing in the row but the row itself.
      expect(find.byTooltip('Send email'), findsNothing);
      await tester.tap(find.text('Widget'));
      await tester.pumpAndSettle();
      expect(find.text('A small widget'), findsOneWidget);
      expect(find.text('₹12.50'), findsOneWidget);
    });
  });

  group('details pages', () {
    testWidgets('a product page offers Edit and Send email, with Email as the last tab',
        (tester) async {
      final backend = _backend();
      await _pump(tester,
          backend: backend,
          user: _user({
            Privileges.productView,
            Privileges.productManage,
            Privileges.auditView,
            Privileges.emailView,
            Privileges.emailSend,
          }),
          location: '/products/7');

      expect(find.widgetWithText(OutlinedButton, 'Edit'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Send email'), findsOneWidget);
      final tabs = tester.widgetList<Tab>(find.byType(Tab)).map((t) => t.text).toList();
      expect(tabs, ['History', 'Email']);

      // Edit reuses the list's form, without the create-only Notify box, and refreshes the page.
      final loads = backend.sent('GET /api/products/7').length;
      await tester.tap(find.widgetWithText(OutlinedButton, 'Edit'));
      await tester.pumpAndSettle();
      expect(find.text('Edit product'), findsOneWidget);
      expect(find.text('Notify through email'), findsNothing);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(backend.sent('PUT /api/products/7'), hasLength(1));
      expect(backend.sent('GET /api/products/7').length, greaterThan(loads));
      expect(backend.sent('GET /api/emails/context'), isEmpty);
    });

    testWidgets('a viewer of a product page gets neither button, nor History without AUDIT_VIEW',
        (tester) async {
      await _pump(tester,
          backend: _backend(),
          user: _user({Privileges.productView, Privileges.emailView}),
          location: '/products/7?tab=email');

      expect(find.text('Edit'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, 'Send email'), findsNothing);
      final tabs = tester.widgetList<Tab>(find.byType(Tab)).map((t) => t.text).toList();
      expect(tabs, ['Email']);
      expect(find.text('No emails about this product yet.'), findsOneWidget);
    });

    testWidgets('a customer login\'s user page links to its customer', (tester) async {
      await _pump(tester,
          backend: _backend({
            'GET /api/users/31': (_) => {
                  'id': 31,
                  'username': 'acme',
                  'fullName': 'Acme Buyer',
                  'email': 'buyer@acme.com',
                  'active': true,
                  'role': 'CUSTOMER',
                  'customerId': 5,
                },
            'GET /api/customers/5': (_) => {'id': 5, 'name': 'Acme Ltd', 'creditBalance': 0},
          }),
          user: _user({
            Privileges.userView,
            Privileges.userManage,
            Privileges.customerView,
            Privileges.auditView,
            Privileges.emailView,
            Privileges.emailSend,
          }),
          location: '/users/31');

      expect(find.text('buyer@acme.com'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Edit'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Send email'), findsOneWidget);
      final tabs = tester.widgetList<Tab>(find.byType(Tab)).map((t) => t.text).toList();
      expect(tabs, ['History', 'Email']);

      await tester.tap(find.text('Acme Ltd'));
      await tester.pumpAndSettle();
      expect(find.text('customer page 5'), findsOneWidget);
    });

    testWidgets('a role page lists its privileges, and has no tabs without EMAIL_VIEW',
        (tester) async {
      Future<void> open(Set<String> privileges) => _pump(tester,
          backend: _backend({
            'GET /api/roles/4': (_) => {
                  'id': 4,
                  'name': 'Auditor',
                  'description': 'Reads everything',
                  'privileges': ['AUDIT_VIEW', 'INVOICE_VIEW'],
                },
          }),
          user: _user(privileges),
          location: '/roles/4');

      await open({Privileges.roleView, Privileges.emailView});
      expect(find.widgetWithText(Chip, 'AUDIT_VIEW'), findsOneWidget);
      expect(find.widgetWithText(Chip, 'INVOICE_VIEW'), findsOneWidget);
      expect(tester.widgetList<Tab>(find.byType(Tab)).map((t) => t.text), ['Email']);

      await open({Privileges.roleView});
      expect(find.text('Reads everything'), findsOneWidget);
      expect(find.byType(Tab), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('the three pages fit a phone', (tester) async {
      final backend = _backend({
        'GET /api/users/31': (_) =>
            {'id': 31, 'username': 'acme', 'active': true, 'role': 'CUSTOMER', 'customerId': 5},
        'GET /api/customers/5': (_) => {'id': 5, 'name': 'Acme Ltd', 'creditBalance': 0},
        'GET /api/roles/4': (_) => {
              'id': 4,
              'name': 'Administrators of everything',
              'privileges': [for (var i = 0; i < 30; i++) 'PRIVILEGE_NUMBER_$i'],
            },
      });
      final everything = _user({
        Privileges.productView,
        Privileges.productManage,
        Privileges.userView,
        Privileges.userManage,
        Privileges.roleView,
        Privileges.roleManage,
        Privileges.customerView,
        Privileges.auditView,
        Privileges.emailView,
        Privileges.emailSend,
      });
      for (final page in ['/products/7', '/users/31', '/roles/4']) {
        await _pump(tester,
            backend: backend, user: everything, location: page, size: const Size(400, 820));
        expect(find.widgetWithText(OutlinedButton, 'Send email'), findsOneWidget, reason: page);
        expect(tester.takeException(), isNull, reason: page);
      }
    });
  });
}
