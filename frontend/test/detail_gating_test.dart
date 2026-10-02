import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/disputes/dispute_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/poc/poc_picker.dart';
import 'package:gene_invoice/features/poc/poc_providers.dart';
import 'package:gene_invoice/features/promises/promise_detail_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _managing = {
  Privileges.customerView,
  Privileges.customerManage,
  Privileges.invoiceView,
  Privileges.invoiceManage,
  Privileges.promiseView,
  Privileges.promiseManage,
  Privileges.promiseOverride,
  Privileges.emailSend,
};

// Rita manages NORTH and SOUTH; EAST is closed; in WEST she may only look.
const _north = RegionAccess(
    id: 1,
    code: 'NORTH',
    name: 'North',
    defaultRegion: true,
    level: 'MANAGE',
    scope: 'ALL',
    privileges: _managing);
const _south =
    RegionAccess(id: 2, code: 'SOUTH', name: 'South', level: 'MANAGE', scope: 'ALL', privileges: _managing);
const _east = RegionAccess(
    id: 3, code: 'EAST', name: 'East', active: false, level: 'MANAGE', scope: 'ALL', privileges: _managing);
const _west = RegionAccess(
    id: 4,
    code: 'WEST',
    name: 'West',
    level: 'READ',
    scope: 'ALL',
    privileges: {Privileges.customerView, Privileges.invoiceView, Privileges.promiseView});

CurrentUser _rita({
  Set<String> privileges = _managing,
  List<RegionAccess> regions = const [_north, _south, _east, _west],
  String role = 'CASHIER',
}) =>
    CurrentUser(
      id: 3,
      username: 'rita',
      fullName: 'Rita',
      role: role,
      privileges: privileges,
      customerId: null,
      regions: regions,
    );

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

Map<String, dynamic> _customer({bool? canManage = true, bool? canMoveRegion, int regionId = 1}) => {
      'id': 5,
      'name': 'Acme Ltd',
      'outstanding': 1000.0,
      'creditBalance': 0.0,
      'regionId': regionId,
      'regionCode': regionId == 1 ? 'NORTH' : 'WEST',
      'regionName': regionId == 1 ? 'North' : 'West',
      'canManage': canManage,
      'canMoveRegion': canMoveRegion,
      'version': 4,
    };

Map<String, dynamic> _invoice({bool? canManage, int regionId = 1}) => {
      'id': 42,
      'invoiceNumber': 'INV-0042',
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-09-01',
      'dueDate': '2026-10-01',
      'total': 1000.0,
      'paidAmount': 0.0,
      'balance': 1000.0,
      'status': 'UNPAID',
      'notes': 'Net 30',
      'items': [],
      'regionId': regionId,
      'regionCode': 'NORTH',
      'regionName': 'North',
      if (canManage != null) 'canManage': canManage,
    };

Map<String, dynamic> _dispute({bool? canResolve}) => {
      'id': 11,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'openedByUserId': 9,
      'targetType': 'INVOICE',
      'targetId': 42,
      'targetNumber': 'INV-0042',
      'reason': 'Billed twice',
      'status': 'PENDING',
      'createdAt': '2026-09-02T10:00:00Z',
      'regionId': 1,
      if (canResolve != null) 'canResolve': canResolve,
    };

Map<String, dynamic> _promise({bool? canManage, bool? canOverride}) => {
      'id': 3,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'amount': 500.0,
      'remainingAmount': 500.0,
      'promisedDate': '2026-10-15',
      'status': 'OPEN',
      'regionId': 1,
      if (canManage != null) 'canManage': canManage,
      if (canOverride != null) 'canOverride': canOverride,
    };

final _tabs = <String, Object? Function(RequestOptions)>{
  'GET /api/disputes': (_) => _page(const []),
  'GET /api/promises': (_) => _page(const []),
};

Future<(FakeBackend, ProviderContainer)> _pump(
  WidgetTester tester, {
  required Widget page,
  required CurrentUser user,
  required Map<String, Object? Function(RequestOptions)> routes,
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend({..._tabs, ...routes});
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user),
  ]);
  addTearDown(container.dispose);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [GoRoute(path: '/page', builder: (_, __) => page)],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return (backend, container);
}

void main() {
  group("a record's own controls follow the server's flags (D14)", () {
    testWidgets('canManage: false hides Save even with INVOICE_MANAGE', (tester) async {
      await _pump(tester,
          page: const InvoiceDetailScreen(id: 42),
          user: _rita(),
          routes: {'GET /api/invoices/42': (_) => _invoice(canManage: false)});
      expect(tester.takeException(), isNull);

      expect(find.text('Save changes'), findsNothing);
      expect(find.byType(TextField), findsNothing);
      expect(find.text('Net 30'), findsOneWidget);
    });

    for (final flag in [true, null]) {
      testWidgets('canManage: $flag offers Save (a snapshot without the flag falls back)',
          (tester) async {
        await _pump(tester,
            page: const InvoiceDetailScreen(id: 42),
            user: _rita(),
            routes: {'GET /api/invoices/42': (_) => _invoice(canManage: flag)});
        expect(find.text('Save changes'), findsOneWidget);
      });
    }

    testWidgets('canManage: false on a customer hides its edits and Delete', (tester) async {
      await _pump(tester,
          page: const CustomerDetailScreen(id: 5),
          user: _rita(),
          routes: {'GET /api/customers/5': (_) => _customer(canManage: false)});
      expect(find.text('Delete customer'), findsNothing);
      expect(find.text('Save changes'), findsNothing);
    });

    testWidgets("a promise's flags decide Edit, Override and Cancel", (tester) async {
      await _pump(tester,
          page: const PromiseDetailScreen(id: 3),
          user: _rita(),
          routes: {'GET /api/promises/3': (_) => _promise(canManage: false, canOverride: true)});
      expect(find.text('Edit'), findsNothing);
      expect(find.text('Cancel'), findsNothing);
      expect(find.text('Override status'), findsOneWidget);
    });

    testWidgets('actions on a record need the right in its region', (tester) async {
      // WEST is only readable to Rita, so writing about an invoice there is not offered, however
      // many regions she may send email in.
      await _pump(tester,
          page: const InvoiceDetailScreen(id: 42),
          user: _rita(),
          routes: {'GET /api/invoices/42': (_) => _invoice(canManage: false, regionId: 4)});
      expect(find.text('Send email'), findsNothing);
    });

    testWidgets('the region is shown to someone who holds several', (tester) async {
      await _pump(tester,
          page: const InvoiceDetailScreen(id: 42),
          user: _rita(),
          routes: {'GET /api/invoices/42': (_) => _invoice(canManage: true)});
      expect(find.text('Send email'), findsOneWidget);
      expect(find.text('NORTH — North'), findsOneWidget);
    });
  });

  group('resolving a dispute', () {
    testWidgets('canResolve: true offers it without DISPUTE_MANAGE on the flat list or ADMIN',
        (tester) async {
      await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          user: _rita(privileges: const {Privileges.disputeView}),
          routes: {'GET /api/disputes/11': (_) => _dispute(canResolve: true)});
      expect(find.text('Resolve'), findsOneWidget);
    });

    testWidgets('canResolve: false withholds it even from an ADMIN with DISPUTE_MANAGE',
        (tester) async {
      await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          user: _rita(
              role: 'ADMIN', privileges: const {Privileges.disputeView, Privileges.disputeManage}),
          routes: {'GET /api/disputes/11': (_) => _dispute(canResolve: false)});
      expect(find.text('Resolve'), findsNothing);
    });

    testWidgets('without the flag it is the privilege, not the role name, that decides (G20)',
        (tester) async {
      await _pump(tester,
          page: const DisputeDetailScreen(id: 11),
          user: _rita(privileges: const {Privileges.disputeView, Privileges.disputeManage}),
          routes: {'GET /api/disputes/11': (_) => _dispute()});
      expect(find.text('Resolve'), findsOneWidget);
    });
  });

  group('moving a customer to another region', () {
    testWidgets('is offered only when the server says so', (tester) async {
      await _pump(tester,
          page: const CustomerDetailScreen(id: 5),
          user: _rita(),
          routes: {'GET /api/customers/5': (_) => _customer(canMoveRegion: false)});
      expect(find.text('Move region'), findsNothing);
    });

    testWidgets('posts the region, version and reason, then shows what moved', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const CustomerDetailScreen(id: 5),
          user: _rita(),
          routes: {
            'GET /api/customers/5': (_) => _customer(canMoveRegion: true),
            'POST /api/customers/5/move-region': (_) => {
                  'customer': {..._customer(canMoveRegion: true), 'regionId': 2},
                  'moved': {
                    'invoices': 12,
                    'payments': 1,
                    'promises': 0,
                    'disputes': 2,
                    'other': {'TaskMoveParticipant': 3, 'PendingChangeMoveParticipant': 0},
                  },
                  'warnings': ['sam.sales is Sales POC on 4 invoices but has no access to South'],
                },
          });

      await tester.tap(find.text('Move region'));
      await tester.pumpAndSettle();
      expect(find.text('Move Acme Ltd to another region'), findsOneWidget);

      // Only active regions Rita manages customers in, other than the one it is in.
      await tester.tap(find.byKey(const ValueKey('move-region-target')));
      await tester.pumpAndSettle();
      Finder item(String text) => find.widgetWithText(DropdownMenuItem<int>, text);
      expect(item('SOUTH — South'), findsWidgets);
      expect(item('NORTH — North'), findsNothing);
      expect(item('EAST — East'), findsNothing);
      expect(item('WEST — West'), findsNothing);
      await tester.tap(find.text('SOUTH — South').last);
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const ValueKey('move-region-reason')), 'Account moved to the South team');
      final fetchesBefore = backend.sent('GET /api/customers/5').length;
      await tester.tap(find.text('Move'));
      await tester.pumpAndSettle();

      final post = backend.sent('POST /api/customers/5/move-region');
      expect(post, hasLength(1));
      // `version` goes only when the customer carries one; §6.5's CustomerDto does not yet
      // (LOG-frontend, FE-03 review fixes), so this fixture adds it.
      expect(post.single.data,
          {'regionId': 2, 'version': 4, 'reason': 'Account moved to the South team'});

      expect(find.text('Acme Ltd is now in SOUTH'), findsOneWidget);
      expect(find.text('Moved with it: 12 invoices, 1 payment, 2 disputes, 3 tasks.'),
          findsOneWidget);
      expect(find.text('sam.sales is Sales POC on 4 invoices but has no access to South'),
          findsOneWidget);
      // The page behind is read again.
      expect(backend.sent('GET /api/customers/5').length, greaterThan(fetchesBefore));

      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
    });

    testWidgets("a refusal reads in the server's words and the dialog stays open",
        (tester) async {
      const refusal = 'Acme Ltd has 2 changes awaiting approval; they must be decided first';
      final (backend, _) = await _pump(tester,
          page: const CustomerDetailScreen(id: 5),
          user: _rita(),
          routes: {
            'GET /api/customers/5': (_) => _customer(canMoveRegion: true),
            'POST /api/customers/5/move-region': (_) =>
                const FakeFailure(400, {'status': 400, 'message': refusal}),
          });

      await tester.tap(find.text('Move region'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('move-region-target')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('SOUTH — South').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Move'));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/customers/5/move-region'), hasLength(1));
      expect(find.text(refusal), findsOneWidget);
      expect(find.byType(AlertDialog), findsOneWidget);
      expect(find.text('Move'), findsOneWidget);
    });
  });

  group('create flows offer only regions the user may write in', () {
    final listRoutes = <String, Object? Function(RequestOptions)>{
      'GET /api/table-schemas/customers': (_) => {'entity': 'customers', 'columns': const []},
      'GET /api/customers/summary': (_) => const <String, dynamic>{},
      'GET /api/customers': (_) => _page([_customer()]),
    };

    testWidgets('a new customer names its region, from those where customers are managed',
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: const CustomersScreen(query: TableQuery(sort: 'name,asc')),
          user: _rita(),
          routes: {
            ...listRoutes,
            'POST /api/customers': (_) => {'id': 77, 'name': 'Globex'},
          });

      // Several regions: the list says which each customer is in.
      expect(find.text('Region'), findsOneWidget);
      expect(find.text('NORTH'), findsOneWidget);

      await tester.tap(find.text('New customer'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('customer-region')));
      await tester.pumpAndSettle();
      expect(find.text('WEST — West'), findsNothing);
      expect(find.text('EAST — East'), findsNothing);
      await tester.tap(find.text('SOUTH — South').last);
      await tester.pumpAndSettle();

      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-1');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      final post = backend.sent('POST /api/customers');
      expect(post, hasLength(1));
      expect((post.single.data as Map)['regionId'], 2);
    });

    testWidgets('with several choices the region is asked for, never assumed', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const CustomersScreen(query: TableQuery(sort: 'name,asc')),
          user: _rita(),
          routes: {
            ...listRoutes,
            'POST /api/customers': (_) => {'id': 77, 'name': 'Globex'},
          });

      await tester.tap(find.text('New customer'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-1');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      // NORTH is the default region, but nothing is chosen for the user.
      expect(find.text('Choose the region this customer belongs to'), findsOneWidget);
      expect(backend.sent('POST /api/customers'), isEmpty);
    });

    testWidgets('with one region there is nothing to ask, and no Region column', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const CustomersScreen(query: TableQuery(sort: 'name,asc')),
          user: _rita(regions: const [_north]),
          routes: {
            ...listRoutes,
            'POST /api/customers': (_) => {'id': 77, 'name': 'Globex'},
          });
      expect(find.text('Region'), findsNothing);

      await tester.tap(find.text('New customer'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('customer-region')), findsNothing);
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-1');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect((backend.sent('POST /api/customers').single.data as Map)['regionId'], 1);
    });

    test('customer pickers keep to the regions the user may write in', () async {
      final backend = FakeBackend({'GET /api/customers': (_) => _page(const [])});
      final dio = backend.dio;
      await searchCustomers(dio, 'ac', regionIds: {2, 1});
      await searchCustomers(dio, '', regionIds: null);

      final sent = backend.sent('GET /api/customers');
      expect(sent[0].queryParameters['filter'], ['name:contains:ac', 'regionId:in:1,2']);
      expect(sent[1].queryParameters.containsKey('filter'), isFalse);
      expect(regionsForPicker(_rita(), Privileges.invoiceManage), {1, 2, 3});
      expect(regionsForPicker(_rita(regions: const []), Privileges.invoiceManage), isNull);
    });

    testWidgets("a POC picker asks who may hold the seat in the customer's region",
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: Center(
            child: PocPicker(
                type: PocType.COLLECTION, customerId: 5, value: null, onChanged: (_) {}),
          ),
          user: _rita(),
          routes: {'GET /api/pocs/assignable': (_) => const []});
      await tester.tap(find.text('Select…'));
      await tester.pumpAndSettle();

      final asked = backend.sent('GET /api/pocs/assignable').single.queryParameters;
      expect(asked['type'], 'COLLECTION');
      expect(asked['customerId'], 5);
    });
  });
}
