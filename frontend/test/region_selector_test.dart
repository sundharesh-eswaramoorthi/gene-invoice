import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/features/poc/poc_picker.dart';
import 'package:gene_invoice/features/poc/poc_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _north = RegionGrant(
    id: 3, code: 'NORTH', name: 'North Branch', rights: {regionRightManage});
const _westReadOnly =
    RegionGrant(id: 7, code: 'WEST', name: 'West Branch', rights: {regionRightView});
const _westManage =
    RegionGrant(id: 7, code: 'WEST', name: 'West Branch', rights: {regionRightManage});

CurrentUser _staff({
  required List<RegionGrant> regions,
  bool allRegions = false,
  Set<String> privileges = const {
    Privileges.customerView,
    Privileges.invoiceView,
    Privileges.invoiceManage,
    Privileges.pocView,
    Privileges.pocAssign,
  },
}) =>
    CurrentUser(
      id: 5,
      username: 'priya',
      fullName: 'Priya R',
      role: 'COLLECTION_POC',
      privileges: privileges,
      customerId: null,
      allRegions: allRegions,
      regions: regions,
    );

Map<String, dynamic> _invoice(int id, {required int regionId, required String regionName}) => {
      'id': id,
      'invoiceNumber': 'INV-20260917-100$id',
      'customerId': 40 + id,
      'customerName': 'Acme $id',
      'invoiceDate': '2026-09-17',
      'dueDate': '2026-10-17',
      'total': 1000.0,
      'paidAmount': 0.0,
      'balance': 1000.0,
      'status': 'UNPAID',
      'pocMissing': false,
      'regionId': regionId,
      'regionName': regionName,
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows, {List<String> locked = const []}) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'sort': 'invoiceDate,desc',
      'appliedFilters': const <String>[],
      'lockedFilters': locked,
    };

/// A schema whose only filterable column is the POC reference, so the filter dialog opens with
/// the reference picker already live and the test does not have to drive a dropdown.
Map<String, dynamic> _pocOnlySchema() => {
      'entity': 'invoices',
      'defaultSort': 'invoiceDate,desc',
      'columns': [
        {
          'name': 'salesPocUserId',
          'label': 'Sales POC',
          'type': 'REFERENCE',
          'sortable': false,
          'filterable': true,
          'operators': ['eq'],
          'referenceKind': 'pocUser',
        },
      ],
    };

/// A schema that publishes the region column exactly as TableSchemas does: REFERENCE, whose
/// FIRST operator is `eq` — which is what the "Add filter" dialog defaults to.
Map<String, dynamic> _regionColumnSchema() => {
      'entity': 'invoices',
      'defaultSort': 'invoiceDate,desc',
      'columns': [
        {
          'name': 'regionId',
          'label': 'Region',
          'type': 'REFERENCE',
          'sortable': false,
          'filterable': true,
          'operators': ['eq', 'neq', 'in', 'isEmpty', 'isNotEmpty'],
          'referenceKind': 'region',
        },
      ],
    };

Map<String, dynamic> _person(int id, String name) => {
      'id': id,
      'username': 'user$id',
      'fullName': name,
      'email': 'user$id@company.com',
      'active': true,
    };

FakeBackend _backend({
  required List<Map<String, dynamic>> rows,
  List<String> locked = const [],
  Map<String, dynamic>? schema,
  Map<int, List<Map<String, dynamic>>> assignableByRegion = const {},
}) =>
    FakeBackend({
      'GET /api/invoices': (o) => _page(rows, locked: locked),
      'GET /api/invoices/summary': (o) => <String, dynamic>{},
      'GET /api/table-schemas/invoices': (o) =>
          schema ?? {'entity': 'invoices', 'columns': <dynamic>[]},
      'GET /api/pocs/assignable': (o) {
        final region = o.queryParameters['regionId'];
        if (region == null) return const <Map<String, dynamic>>[];
        return assignableByRegion[region as int] ?? const <Map<String, dynamic>>[];
      },
    });

Future<GoRouter> _pumpInvoices(
  WidgetTester tester,
  FakeBackend backend,
  CurrentUser user, {
  String location = '/invoices',
}) async {
  tester.view.physicalSize = const Size(1400, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: location,
    routes: [
      GoRoute(
        path: '/invoices',
        builder: (_, s) => InvoicesScreen(
            query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'invoiceDate,desc')),
      ),
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
  return router;
}

String _url(GoRouter router) =>
    Uri.decodeComponent(router.routerDelegate.currentConfiguration.uri.toString());

List<String> _filtersSent(FakeBackend backend) {
  final sent = backend.sent('GET /api/invoices');
  if (sent.isEmpty) return const [];
  final raw = sent.last.queryParameters['filter'];
  if (raw == null) return const [];
  return raw is List ? raw.map((e) => '$e').toList() : ['$raw'];
}

void main() {
  group('hasIn', () {
    test('holding manage in one branch and view in another unlocks only the first', () {
      final user = _staff(regions: const [_north, _westReadOnly]);
      expect(user.has(Privileges.invoiceManage), isTrue);
      expect(user.hasIn(Privileges.invoiceManage, 3), isTrue);
      expect(user.hasIn(Privileges.invoiceManage, 7), isFalse);
      // Every right covers VIEW, so reading is unlocked in both (B1).
      expect(user.hasIn(Privileges.invoiceView, 7), isTrue);
    });

    test('a branch you hold nothing in unlocks nothing', () {
      final user = _staff(regions: const [_north]);
      expect(user.hasIn(Privileges.invoiceView, 7), isFalse);
      expect(user.hasIn(Privileges.invoiceManage, 7), isFalse);
    });

    test('a record that does not say which branch it is in falls back to the privilege', () {
      final user = _staff(regions: const [_north]);
      expect(user.hasIn(Privileges.invoiceManage, null), isTrue);
    });

    test('a company-wide privilege is not narrowed by a branch', () {
      const user = CurrentUser(
        id: 9,
        username: 'ann',
        fullName: 'Ann',
        role: 'ADMIN',
        privileges: {Privileges.userManage, Privileges.regionManage},
        customerId: null,
        regions: [_north],
      );
      // Where a person is edited is not a branch question: USER_MANAGE and REGION_MANAGE are
      // company-wide by the settled partition (B1).
      expect(user.hasIn(Privileges.userManage, 7), isTrue);
      expect(user.hasIn(Privileges.regionManage, 7), isTrue);
    });

    test('a customer login is never narrowed, because its reach is its own account', () {
      const customer = CurrentUser(
        id: 30,
        username: 'acme',
        fullName: 'Acme Ltd',
        role: 'CUSTOMER',
        privileges: {Privileges.disputeCreate},
        customerId: 42,
      );
      expect(customer.hasIn(Privileges.disputeCreate, 7), isTrue);
    });

    test('a wildcard holder is unlocked everywhere, including branches opened later', () {
      final user = _staff(regions: const [], allRegions: true);
      expect(user.hasIn(Privileges.invoiceManage, 99), isTrue);
      expect(user.workingSet, isEmpty);
      expect(user.isSingleRegion, isFalse);
    });

    test('one named branch and no wildcard is a single-branch person', () {
      expect(_staff(regions: const [_north]).isSingleRegion, isTrue);
      expect(_staff(regions: const [_north, _westManage]).isSingleRegion, isFalse);
    });
  });

  testWidgets('a user with rights in one region sees a locked chip, not a picker',
      (tester) async {
    final backend = _backend(
      rows: [_invoice(1, regionId: 3, regionName: 'North Branch')],
      locked: const ['regionId:in:3'],
    );
    await _pumpInvoices(tester, backend, _staff(regions: const [_north]));

    // One branch is not a choice: the server has already said which branch this list covers,
    // and offering a picker with a single entry would invite a click that changes nothing.
    expect(find.text('Regions: NORTH'), findsOneWidget);
    expect(find.byType(PopupMenuButton<int>), findsNothing);
  });

  testWidgets('a person who can see no branch at all is told so rather than shown an empty list',
      (tester) async {
    final backend = _backend(rows: const [], locked: const ['regionId:isEmpty:']);
    await _pumpInvoices(tester, backend, _staff(regions: const []));

    // 200 with zero rows, not an error — so the page has to explain itself (B1).
    expect(find.text('No region access — ask an administrator'), findsOneWidget);
  });

  testWidgets('narrowing to one region puts regionId:in in the url and asks the server for it',
      (tester) async {
    final backend = _backend(rows: [
      _invoice(1, regionId: 3, regionName: 'North Branch'),
      _invoice(2, regionId: 7, regionName: 'West Branch'),
    ]);
    final router = await _pumpInvoices(
        tester, backend, _staff(regions: const [_north, _westManage]));

    expect(find.text('All my branches'), findsOneWidget);
    await tester.tap(find.text('All my branches'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(CheckedPopupMenuItem<int>, 'NORTH — North Branch'));
    await tester.pumpAndSettle();

    // A real TableFilter inside the query, so it survives the URL, the back button and a
    // shared link with no new plumbing (B1).
    expect(_url(router), contains('f=regionId:in:3'));
    expect(_filtersSent(backend), contains('regionId:in:3'));
    expect(find.text('Regions: NORTH'), findsOneWidget);
  });

  testWidgets('clearing all filters keeps the region narrowing', (tester) async {
    final backend = _backend(rows: [_invoice(1, regionId: 3, regionName: 'North Branch')]);
    final router = await _pumpInvoices(
      tester,
      backend,
      _staff(regions: const [_north, _westManage]),
      location: '/invoices?f=status%3Aeq%3AUNPAID&f=regionId%3Ain%3A3',
    );

    expect(_url(router), contains('f=status:eq:UNPAID'));
    await tester.tap(find.text('Clear all'));
    await tester.pumpAndSettle();

    // Which branches the list is about is not one more row filter: clearing the filters must
    // not silently widen it back to every branch the caller can see (B1).
    expect(_url(router), contains('f=regionId:in:3'));
    expect(_url(router), isNot(contains('status:eq:UNPAID')));
    expect(_filtersSent(backend), ['regionId:in:3']);
  });

  testWidgets('a region filter that is not the selector\'s own is a chip like any other',
      (tester) async {
    final backend = _backend(
      rows: [_invoice(1, regionId: 7, regionName: 'West Branch')],
      schema: _regionColumnSchema(),
    );
    final router = await _pumpInvoices(
      tester,
      backend,
      _staff(regions: const [_north, _westManage]),
      location: '/invoices?f=regionId%3Aeq%3A7',
    );

    // The selector writes and reads `regionId:in` and nothing else. A `regionId:eq:7` — which
    // is what "Add filter" composes, REFERENCE's first operator being `eq` — was excluded from
    // the chip row by FIELD, so it narrowed the list with no control anywhere on the page,
    // was not counted, and "Clear all" was not even rendered (B1).
    expect(find.text('Region is 7'), findsOneWidget);
    expect(find.text('All my branches'), findsOneWidget);
    expect(find.text('Clear all'), findsOneWidget);

    await tester.tap(find.text('Clear all'));
    await tester.pumpAndSettle();
    expect(_url(router), isNot(contains('regionId')));
    expect(_filtersSent(backend), isEmpty);
  });

  testWidgets('picking a branch leaves an ordinary region filter alone', (tester) async {
    final backend = _backend(
      rows: [_invoice(1, regionId: 7, regionName: 'West Branch')],
      schema: _regionColumnSchema(),
    );
    final router = await _pumpInvoices(
      tester,
      backend,
      _staff(regions: const [_north, _westManage]),
      location: '/invoices?f=regionId%3Aeq%3A7',
    );

    await tester.tap(find.text('All my branches'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(CheckedPopupMenuItem<int>, 'NORTH — North Branch'));
    await tester.pumpAndSettle();

    // The selector replaces its OWN filter. The chip the operator built is theirs, is on
    // screen, and is not this control's to drop silently (B1).
    expect(_url(router), contains('f=regionId:eq:7'));
    expect(_url(router), contains('f=regionId:in:3'));
  });

  testWidgets('an edit button is hidden on a row in a region the user only reads',
      (tester) async {
    final backend = _backend(rows: [
      _invoice(1, regionId: 3, regionName: 'North Branch'),
      _invoice(2, regionId: 7, regionName: 'West Branch'),
    ]);
    await _pumpInvoices(tester, backend, _staff(regions: const [_north, _westReadOnly]));

    // Both rows are readable and both are unpaid; INVOICE_MANAGE is held, but only in NORTH.
    expect(find.text('INV-20260917-1001'), findsOneWidget);
    expect(find.text('INV-20260917-1002'), findsOneWidget);
    expect(find.byTooltip('Cancel invoice'), findsOneWidget);
  });

  testWidgets('Send email is offered only on the row whose branch the user may write in',
      (tester) async {
    final backend = _backend(rows: [
      _invoice(1, regionId: 3, regionName: 'North Branch'),
      _invoice(2, regionId: 7, regionName: 'West Branch'),
    ]);
    await _pumpInvoices(
      tester,
      backend,
      _staff(
        regions: const [_north, _westReadOnly],
        privileges: const {
          Privileges.customerView,
          Privileges.invoiceView,
          Privileges.emailView,
          Privileges.emailSend,
        },
      ),
    );

    // EMAIL_SEND is MANAGE-level in the region partition, so holding it in NORTH is not
    // permission to write to a West account under the company's name (B1).
    expect(find.byTooltip('Send email'), findsOneWidget);
  });

  testWidgets('the POC filter picker resolves for somebody who is not a wildcard holder',
      (tester) async {
    final backend = _backend(
      rows: [_invoice(1, regionId: 3, regionName: 'North Branch')],
      schema: _pocOnlySchema(),
      assignableByRegion: {
        3: [_person(11, 'Nina North')],
        7: [_person(12, 'Wanda West')],
      },
    );
    await _pumpInvoices(tester, backend, _staff(regions: const [_north, _westManage]));

    await tester.tap(find.text('Add filter'));
    await tester.pumpAndSettle();

    // The regression this closes: the shipped client named neither a customerId nor a regionId,
    // and GET /api/pocs/assignable refuses to guess a branch for anybody but a wildcard holder,
    // so the picker was a 400 for every other user (B1, R8).
    final asked = backend.sent('GET /api/pocs/assignable');
    expect(asked, isNotEmpty);
    expect(asked.every((r) => r.queryParameters['regionId'] != null), isTrue,
        reason: 'every call must name the branch it is asking about');
    expect(asked.map((r) => r.queryParameters['regionId']).toSet(), {3, 7});
    expect(find.text('Nina North'), findsOneWidget);
    expect(find.text('Wanda West'), findsOneWidget);
    expect(find.textContaining('Failed to load'), findsNothing);
  });

  testWidgets('the selector offers the branches the roster names, not the ones sign-in remembers',
      (tester) async {
    final backend = _backend(rows: [_invoice(1, regionId: 3, regionName: 'North Branch')]);
    backend.routes['GET /api/regions/my'] = (o) => {
          'allRegions': false,
          'regions': [
            {'id': 3, 'code': 'NORTH', 'name': 'North Branch', 'rights': ['MANAGE']},
            {'id': 7, 'code': 'WEST', 'name': 'West Branch', 'rights': ['VIEW']},
            {'id': 9, 'code': 'EAST', 'name': 'East Branch', 'rights': ['MANAGE']},
          ],
        };
    await _pumpInvoices(tester, backend, _staff(regions: const [_north, _westManage]));

    await tester.tap(find.text('All my branches'));
    await tester.pumpAndSettle();

    // The sign-in payload is only rebuilt when they sign in; the server reads the grant table on
    // every request. A branch granted this morning is usable this morning (B1).
    expect(backend.sent('GET /api/regions/my'), isNotEmpty);
    expect(find.text('EAST — East Branch'), findsOneWidget);
  });

  testWidgets('a wildcard holder still asks about no branch, because anywhere is their answer',
      (tester) async {
    final backend = FakeBackend({
      'GET /api/invoices': (o) => _page([_invoice(1, regionId: 3, regionName: 'North Branch')]),
      'GET /api/invoices/summary': (o) => <String, dynamic>{},
      'GET /api/table-schemas/invoices': (o) => _pocOnlySchema(),
      'GET /api/regions/my': (o) => {'allRegions': true, 'regions': const []},
      'GET /api/pocs/assignable': (o) => o.queryParameters['regionId'] == null
          ? [_person(11, 'Nina North')]
          : const <Map<String, dynamic>>[],
    });
    await _pumpInvoices(tester, backend, _staff(regions: const [], allRegions: true));

    await tester.tap(find.text('Add filter'));
    await tester.pumpAndSettle();

    final asked = backend.sent('GET /api/pocs/assignable');
    expect(asked, isNotEmpty);
    expect(asked.every((r) => r.queryParameters['regionId'] == null), isTrue);
    expect(find.text('Nina North'), findsOneWidget);
  });

  testWidgets('a POC picker opened on a record names that record rather than a branch',
      (tester) async {
    final backend = FakeBackend({
      'GET /api/pocs/assignable': (o) =>
          o.queryParameters['customerId'] == 42 ? [_person(11, 'Nina North')] : const [],
    });
    tester.view.physicalSize = const Size(1000, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff(regions: const [_north, _westManage])),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: Scaffold(
          body: PocPicker(
            type: PocType.COLLECTION,
            value: null,
            customerId: 42,
            onChanged: (_) {},
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Select…'));
    await tester.pumpAndSettle();

    // The account's own branch is the only answer that cannot be a guess, and the server
    // resolves it through the already-scoped read so an account the caller cannot see is a 404
    // rather than a way to probe for one (B1, AUTH-08).
    final asked = backend.sent('GET /api/pocs/assignable');
    expect(asked, isNotEmpty);
    expect(asked.every((r) => r.queryParameters['customerId'] == 42), isTrue);
    expect(asked.every((r) => r.queryParameters['regionId'] == null), isTrue);
    expect(find.text('Nina North'), findsOneWidget);
  });
}
