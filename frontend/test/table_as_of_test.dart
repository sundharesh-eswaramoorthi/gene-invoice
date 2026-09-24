import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/as_of/as_of_providers.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/dashboard/dashboard_screen.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

/// The as-of control, driven through the real screens rather than asserted on the models.
///
/// This build has already shipped a client regression that 224 backend tests and 61 widget tests
/// sailed past, because nothing crossed the client/server boundary. So every test here asserts
/// what actually went out on the wire — backend.sent(...).queryParameters — or what a person can
/// actually read on screen, and never merely that a Dart field was assigned (B3).
///
/// ONE PREMISE THIS UNIT WAS HANDED DOES NOT HOLD, AND THE TEST THAT COVERS IT IS HONEST ABOUT IT.
/// "ColumnDto.asOfMode is CURRENT on customerName, salesPocName, collectionPocName and the task
/// assignee" is false against the real server: TableSchemaController.describe() maps the LIVE
/// ColumnDef, no live ColumnDef in TableSchemas calls .current(), and the twins that do carry the
/// flag are never published. The backend pinned this itself in
/// AsOfListTest#aColumnMarkedCurrentReadsTodaysValueEvenUnderAsOfAndTheSchemaSaysSo, which ASSERTS
/// the wire says EXACT for salesPocName. So `_schema(salesPocMode: 'CURRENT')` below is a schema
/// no server sends today: the "(current value)" tests prove the CLIENT renders the flag correctly
/// and prove nothing about anything ever setting it.

const _north = RegionGrant(
    id: 3, code: 'NORTH', name: 'North Branch', rights: {regionRightManage});
const _west =
    RegionGrant(id: 7, code: 'WEST', name: 'West Branch', rights: {regionRightManage});

const _january = '2026-01-31';
const _januaryLabel = '31 Jan 2026';

/// The `today` GET /api/as-of publishes, which is also the latest day the picker offers and its
/// default selection. Fixed, so the calendar the tests drive opens on a known month.
const _serverToday = '2026-09-23';
const _serverTodayLabel = '23 Sep 2026';

CurrentUser _staff({
  List<RegionGrant> regions = const [],
  bool allRegions = true,
  Set<String> privileges = const {
    Privileges.customerView,
    Privileges.invoiceView,
    Privileges.invoiceManage,
    Privileges.paymentView,
    Privileges.promiseView,
    Privileges.exportData,
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

Map<String, dynamic> _invoice(int id) => {
      'id': id,
      'invoiceNumber': 'INV-20260117-100$id',
      'customerId': 40 + id,
      'customerName': 'Acme $id',
      'invoiceDate': '2026-01-17',
      'dueDate': '2026-02-17',
      'total': 1000.0,
      'paidAmount': 0.0,
      'balance': 1000.0,
      'status': 'UNPAID',
      'pocMissing': false,
      'regionId': 3,
      'regionName': 'North Branch',
    };

/// The page envelope exactly as PageResponse serialises it, including the trailing `asOf`
/// component B3-CONTEXT added and the "asOf:eq:<date>" chip the slice units append.
Map<String, dynamic> _page(
  List<Map<String, dynamic>> rows, {
  List<String> locked = const [],
  Map<String, dynamic>? asOf,
}) =>
    {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'sort': 'invoiceDate,desc',
      'appliedFilters': const <String>[],
      'lockedFilters': locked,
      'asOf': asOf,
    };

Map<String, dynamic> _asOfInfo({
  String date = _january,
  bool exact = true,
  String origin = 'RECONSTRUCTED',
  int omittedDeleted = 0,
  List<String> notes = const [],
}) =>
    {
      'date': date,
      'floor': '2026-01-01',
      'exact': exact,
      'origin': origin,
      'appliesTo': 'records',
      'omittedDeleted': omittedDeleted,
      'notes': notes,
    };

/// The invoices schema as TableSchemaController serves it, with the two keys this unit reads:
/// `asOfSupported` on the table and `asOfMode` on each column.
Map<String, dynamic> _schema({
  bool asOfSupported = true,
  String salesPocMode = 'EXACT',
}) =>
    {
      'entity': 'invoices',
      'defaultSort': 'invoiceDate,desc',
      'asOfSupported': asOfSupported,
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': const <String>[],
      'columns': [
        {
          'name': 'salesPocName',
          'label': 'Sales POC',
          'type': 'TEXT',
          'sortable': true,
          'filterable': true,
          'operators': ['contains', 'eq'],
          'asOfMode': salesPocMode,
        },
        {
          'name': 'status',
          'label': 'Status',
          'type': 'ENUM',
          'sortable': true,
          'filterable': true,
          'operators': ['eq', 'in'],
          'enumValues': ['UNPAID', 'PARTIALLY_PAID', 'FULLY_PAID', 'CANCELLED'],
          'asOfMode': 'EXACT',
        },
        {
          'name': 'overdue',
          'label': 'Overdue',
          'type': 'BOOLEAN',
          'sortable': false,
          'filterable': true,
          'operators': ['eq'],
          'asOfMode': 'EXACT',
        },
      ],
    };

/// GET /api/as-of, the contract the picker bounds itself with. `today` is fixed so the calendar
/// the test drives opens on a known month however long from now this suite is run.
Map<String, dynamic> _capability({
  String? floor = '2026-01-01',
  String preFloor = 'seeded',
  List<String> entities = const [
    'invoices',
    'customers',
    'payments',
    'promises',
    'disputes',
    'tasks',
  ],
}) =>
    {
      'floor': floor,
      'today': _serverToday,
      'preFloor': preFloor,
      'entities': entities,
    };

FakeBackend _backend({
  List<Map<String, dynamic>>? rows,
  List<String> locked = const [],
  Map<String, dynamic>? asOf,
  Map<String, dynamic>? schema,
  Map<String, dynamic>? capability,
}) =>
    FakeBackend({
      // The fake answers with an as-of envelope exactly when the request carried a PAST date,
      // which is what makes "the tiles went live while the list was historical" visible here.
      // Past and not merely present: AsOfDates.parse short-circuits any date that is not BEFORE
      // the server's today to live and echoes asOf:null, and today is the one date the picker
      // offers by default — so a fake that answered history for it would hide the very disagree-
      // ment between what was asked and what was answered that this bar exists to show (B3).
      'GET /api/invoices': (o) {
        final asked = o.queryParameters['asOf']?.toString();
        final historical = asked != null && asked.compareTo(_serverToday) < 0;
        return _page(rows ?? [_invoice(1), _invoice(2)],
            locked: locked, asOf: historical ? (asOf ?? _asOfInfo()) : null);
      },
      'GET /api/invoices/summary': (o) => <String, dynamic>{'count': 2},
      'GET /api/table-schemas/invoices': (o) => schema ?? _schema(),
      'GET /api/as-of': (o) => capability ?? _capability(),
      'GET /api/regions/my': (o) => {'allRegions': true, 'regions': const []},
      'GET /api/regions': (o) => {'content': const <Map<String, dynamic>>[]},
    });

Future<GoRouter> _pumpInvoices(
  WidgetTester tester,
  FakeBackend backend,
  CurrentUser user, {
  String location = '/invoices',
}) async {
  tester.view.physicalSize = const Size(1400, 1200);
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
      GoRoute(path: '/invoices/new', builder: (_, __) => const Placeholder()),
      GoRoute(path: '/invoices/:id', builder: (_, __) => const Placeholder()),
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

String? _asOfSent(FakeBackend backend, String key) {
  final sent = backend.sent(key);
  if (sent.isEmpty) return null;
  final raw = sent.last.queryParameters['asOf'];
  return raw == null ? null : '$raw';
}

List<String> _filtersSent(FakeBackend backend) {
  final sent = backend.sent('GET /api/invoices');
  if (sent.isEmpty) return const [];
  final raw = sent.last.queryParameters['filter'];
  if (raw == null) return const [];
  return raw is List ? raw.map((e) => '$e').toList() : ['$raw'];
}

void main() {
  testWidgets('the summary request carries the as-of date', (tester) async {
    final backend = _backend();
    await _pumpInvoices(tester, backend, _staff(),
        location: '/invoices?asOf=$_january');

    // The trap this unit was warned about: TableRequest.forSummary builds a TableQuery from
    // scratch and drops every field it does not name. Tiles served live above historical rows is
    // the single most misleading thing this feature can produce, because each half looks right.
    expect(_asOfSent(backend, 'GET /api/invoices'), _january);
    expect(backend.sent('GET /api/invoices/summary'), isNotEmpty,
        reason: 'the tiles must have been asked for at all');
    expect(_asOfSent(backend, 'GET /api/invoices/summary'), _january);
  });

  testWidgets('the as-of date survives a route round trip', (tester) async {
    final backend = _backend();
    final router = await _pumpInvoices(tester, backend, _staff());

    // Nothing is set: the bar is the offer, not yet the statement.
    expect(find.text('As of a date…'), findsOneWidget);
    expect(_asOfSent(backend, 'GET /api/invoices'), isNull);

    await tester.tap(find.text('As of a date…'));
    await tester.pumpAndSettle();
    // The picker is bounded by the floor GET /api/as-of published and by the SERVER's today, so
    // it opens on September 2026 whatever day this suite is run.
    await tester.tap(find.text('15'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Show'));
    await tester.pumpAndSettle();

    // Into the URL, out of the URL and onto the wire: the whole point of scoping it to the route
    // rather than to the app is that "the ageing as of month-end" survives being shared.
    expect(_url(router), contains('asOf=2026-09-15'));
    expect(_asOfSent(backend, 'GET /api/invoices'), '2026-09-15');
    expect(find.text('As of 15 Sep 2026 — showing history'), findsOneWidget);

    // And back again, which is the only way out of the past.
    await tester.tap(find.text('Back to today'));
    await tester.pumpAndSettle();
    expect(_url(router), isNot(contains('asOf')));
    expect(_asOfSent(backend, 'GET /api/invoices'), isNull);
  });

  testWidgets('the as-of date survives a sort change and a filter change', (tester) async {
    final backend = _backend();
    final router = await _pumpInvoices(tester, backend, _staff(),
        location: '/invoices?asOf=$_january');

    // withSort and withFilters construct a TableQuery directly rather than through copyWith, so
    // each of them would silently drop the date and return the list to today.
    await tester.tap(find.text('Total'));
    await tester.pumpAndSettle();
    expect(_url(router), contains('sort=total,asc'));
    expect(_asOfSent(backend, 'GET /api/invoices'), _january);

    await tester.tap(find.text('Overdue only'));
    await tester.pumpAndSettle();
    expect(_filtersSent(backend), contains('overdue:eq:true'));
    expect(_asOfSent(backend, 'GET /api/invoices'), _january);
    expect(_asOfSent(backend, 'GET /api/invoices/summary'), _january);
  });

  testWidgets('clear all filters keeps both the region narrowing and the as-of date',
      (tester) async {
    final backend = _backend(locked: const []);
    final router = await _pumpInvoices(
      tester,
      backend,
      _staff(regions: const [_north, _west], allRegions: false),
      location:
          '/invoices?f=status%3Aeq%3AUNPAID&f=regionId%3Ain%3A3&asOf=$_january',
    );

    expect(find.text('Clear all'), findsOneWidget);
    await tester.tap(find.text('Clear all'));
    await tester.pumpAndSettle();

    // R10's invariant, kept: which branches the list covers is not one more row filter. And this
    // unit's own decision beside it: clearing the filters is not leaving history either — the
    // only way back to today is the bar's own control (B1, B3).
    expect(_url(router), contains('f=regionId:in:3'));
    expect(_url(router), contains('asOf=$_january'));
    expect(_url(router), isNot(contains('status:eq:UNPAID')));
    expect(_filtersSent(backend), ['regionId:in:3']);
    expect(_asOfSent(backend, 'GET /api/invoices'), _january);
  });

  testWidgets('write actions are disabled while a date is set', (tester) async {
    final backend = _backend();
    final router = await _pumpInvoices(tester, backend, _staff(),
        location: '/invoices?asOf=$_january');

    // Not hidden — dimmed and explained. The refusal itself is the server's; this is the client
    // agreeing with it rather than offering a button that can only 400.
    expect(find.byTooltip('Read-only: you are looking at $_januaryLabel'), findsWidgets);

    await tester.tap(find.text('New invoice'), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(_url(router), isNot(contains('/invoices/new')));

    await tester.tap(find.byTooltip('Cancel invoice').first, warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(find.text('This cannot be undone.'), findsNothing,
        reason: 'a row action must not open its confirmation while the date is set');
  });

  testWidgets('nothing is disabled and nothing claims to be history on a live list',
      (tester) async {
    final backend = _backend();
    final router = await _pumpInvoices(tester, backend, _staff());

    expect(find.byTooltip('Read-only: you are looking at $_januaryLabel'), findsNothing);
    await tester.tap(find.text('New invoice'));
    await tester.pumpAndSettle();
    expect(_url(router), contains('/invoices/new'));
  });

  testWidgets('the export stays offered while the bulk actions do not', (tester) async {
    final backend = _backend();
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    await tester.tap(find.byType(Checkbox).last);
    await tester.pumpAndSettle();

    final readOnly = find.byTooltip('Read-only: you are looking at $_januaryLabel');
    // An export is a READ that happens to be spelled POST, and the server registers it as as-of
    // capable and writes its own caveat row into the file. A bulk action is a write (B3).
    expect(find.ancestor(of: find.text('Export selected'), matching: readOnly), findsNothing);
    expect(
        find.ancestor(of: find.text('Cancel unpaid'), matching: readOnly), findsOneWidget);
  });

  testWidgets('the export downloads the same date the list is showing', (tester) async {
    final backend = _backend();
    backend.routes['POST /api/invoices/export'] = (o) =>
        'As of $_january — this file shows history, not today.\nid,total\n1,1000.00\n';
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    await tester.tap(find.byType(Checkbox).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Export selected'));
    await tester.pumpAndSettle();

    // AS A QUERY PARAMETER, not in the body: AsOfInterceptor reads req.getParameter and never
    // reads the body, so a date folded into the JSON would be invisible to it and the download
    // would silently be today's rows under a historical list (B3).
    final sent = backend.sent('POST /api/invoices/export');
    expect(sent, isNotEmpty);
    expect('${sent.last.queryParameters['asOf']}', _january);
    expect(find.textContaining('this file shows history, not today.'), findsOneWidget);
  });

  testWidgets('a live export asks for no date at all', (tester) async {
    final backend = _backend();
    backend.routes['POST /api/invoices/export'] = (o) => 'id,total\n1,1000.00\n';
    await _pumpInvoices(tester, backend, _staff());

    await tester.tap(find.byType(Checkbox).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Export selected'));
    await tester.pumpAndSettle();

    // Every existing client request stays byte-identical on the live path (B3).
    expect(backend.sent('POST /api/invoices/export').last.queryParameters, isEmpty);
  });

  testWidgets('a seeded answer shows the amber banner and the server\'s own note',
      (tester) async {
    const note =
        '2026-01-31 is before the history floor 2026-10-14. Which records existed on that date '
        'is exact, because seed rows carry each record\'s own creation date.';
    final backend = _backend(
      asOf: _asOfInfo(exact: false, origin: 'SEEDED', omittedDeleted: 2, notes: const [note]),
    );
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    // Verbatim, and not a sentence the client composed: only the server knows why an answer is
    // approximate, and a second wording here would drift from the one in the CSV.
    expect(find.text(note), findsOneWidget);
    expect(find.text('As of $_januaryLabel — showing history'), findsOneWidget);
    expect(
        find.textContaining('2 records deleted before the history floor'), findsOneWidget);
  });

  testWidgets('an exact answer says it is the past without raising an alarm', (tester) async {
    final backend = _backend(asOf: _asOfInfo());
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    expect(find.text('As of $_januaryLabel — showing history'), findsOneWidget);
    expect(find.textContaining('before the history floor'), findsNothing);
    expect(find.textContaining('approximate'), findsNothing);
  });

  testWidgets('a date the server answered live is not reported as history', (tester) async {
    // The server short-circuits any date that is not in the past and echoes asOf:null. Saying
    // "showing history" over live rows is the same quiet lie in reverse (B3).
    final backend = FakeBackend({
      'GET /api/invoices': (o) => _page([_invoice(1)]),
      'GET /api/invoices/summary': (o) => <String, dynamic>{'count': 1},
      'GET /api/table-schemas/invoices': (o) => _schema(),
      'GET /api/as-of': (o) => _capability(),
      'GET /api/regions/my': (o) => {'allRegions': true, 'regions': const []},
      'GET /api/regions': (o) => {'content': const <Map<String, dynamic>>[]},
    });
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    expect(find.text('$_januaryLabel is not in the past — showing today'), findsOneWidget);
    expect(find.text('As of $_januaryLabel — showing history'), findsNothing);
  });

  testWidgets('picking today leaves the page writable, exactly as the chip says it is',
      (tester) async {
    final backend = _backend();
    final router = await _pumpInvoices(tester, backend, _staff());

    // THE PICKER'S OWN DEFAULT SELECTION, reached without moving anything. AsOfCapability
    // .latestOffered bounds the calendar at the SERVER's today and `initial` is that same day, so
    // "tap the chip, press Show" asks for today — and AsOfDates.parse short-circuits any date
    // that is not in the past to live.
    await tester.tap(find.text('As of a date…'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Show'));
    await tester.pumpAndSettle();

    expect(_url(router), contains('asOf=$_serverToday'));
    expect(_asOfSent(backend, 'GET /api/invoices'), _serverToday);

    // The bar tells the truth about the ROWS: they are today's, and the server said so.
    expect(find.text('$_serverTodayLabel is not in the past — showing today'), findsOneWidget);
    expect(find.text('As of $_serverTodayLabel — showing history'), findsNothing);

    // So the writes beside it must not claim otherwise. They are driven off the SERVER's answer
    // and not off the date the client asked for, or this screen would dim every button with a
    // tooltip saying the reader is looking at the past, beside a chip correctly saying they are
    // looking at today — and the writes would in fact have succeeded, because nothing they send
    // carries the date (B3).
    expect(find.byTooltip('Read-only: you are looking at $_serverTodayLabel'), findsNothing);
    await tester.tap(find.text('New invoice'));
    await tester.pumpAndSettle();
    expect(_url(router), contains('/invoices/new'));
  });

  testWidgets('a row action is live too on a date the server answered live', (tester) async {
    final backend = _backend();
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_serverToday');

    expect(find.byTooltip('Read-only: you are looking at $_serverTodayLabel'), findsNothing);
    await tester.tap(find.byTooltip('Cancel invoice').first);
    await tester.pumpAndSettle();
    // The confirmation the dimming would have swallowed. A PAST date still blocks it — that is
    // the test above this one, and it still passes (B3).
    expect(find.text('This cannot be undone.'), findsOneWidget);
  });

  testWidgets('the server\'s own as-of chip is not rendered as an unexplained locked filter',
      (tester) async {
    final backend = _backend(locked: const ['myBook:eq:12', 'asOf:eq:$_january']);
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    // The generic locked-chip label falls back to "My records only" for anything it does not
    // recognise, which would be a flat lie about a date. The bar says it instead, and offers the
    // way back that a chip cannot (B1, B3).
    expect(find.text('My book only'), findsOneWidget);
    expect(find.text('My records only'), findsNothing);
    expect(find.text('As of $_januaryLabel — showing history'), findsOneWidget);
  });

  testWidgets('a column marked current is labelled current value in the filter editor',
      (tester) async {
    final backend = _backend(schema: _schema(salesPocMode: 'CURRENT'));
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    await tester.tap(find.text('Add filter'));
    await tester.pumpAndSettle();

    // A CURRENT column is filtered on TODAY's value even on a historical page, because nothing
    // mirrors what it reads. Saying so where the filter is built is the difference between a
    // surprising result and a wrong one.
    expect(find.textContaining('Sales POC (current value)'), findsWidgets);
    expect(find.textContaining('Status (current value)'), findsNothing);
  });

  testWidgets('no column is labelled current value while the list is live', (tester) async {
    final backend = _backend(schema: _schema(salesPocMode: 'CURRENT'));
    await _pumpInvoices(tester, backend, _staff());

    await tester.tap(find.text('Add filter'));
    await tester.pumpAndSettle();
    expect(find.textContaining('(current value)'), findsNothing);
  });

  testWidgets('the as-of bar is not mounted for a table that cannot answer as of a date',
      (tester) async {
    final backend = _backend(schema: _schema(asOfSupported: false));
    await _pumpInvoices(tester, backend, _staff());

    // The gate is the server's flag and never a list of names held here: a table gets a mirror
    // behind it, or it does not, and only the server knows which.
    expect(find.text('As of a date…'), findsNothing);
    expect(find.text('Add filter'), findsOneWidget);
  });

  testWidgets('a date already in the link is shown even by a table that denies supporting it',
      (tester) async {
    final backend = _backend(schema: _schema(asOfSupported: false));
    await _pumpInvoices(tester, backend, _staff(), location: '/invoices?asOf=$_january');

    // The failure this closes is the one that matters: historical rows on screen with nothing at
    // all to say so. The offer is gated on the flag; saying what is already being shown is not.
    expect(find.text('As of $_januaryLabel — showing history'), findsOneWidget);
  });

  group('dashboard', () {
    Map<String, dynamic> series(String? asOf) => {
          'coverage': 'ALL',
          'regionCoverage': {'allRegions': true, 'regions': const []},
          'asOf': asOf == null ? null : _asOfInfo(date: asOf),
          'months': [
            {'month': '2026-01', 'amount': 5000.0, 'count': 3},
          ],
        };

    Map<String, dynamic> ranking(String? asOf, String amountKey, String countKey) => {
          'coverage': 'ALL',
          'regionCoverage': {'allRegions': true, 'regions': const []},
          'asOf': asOf == null ? null : _asOfInfo(date: asOf),
          'customers': [
            {
              'customerId': 1,
              'customerName': 'Acme',
              amountKey: 1000.0,
              countKey: 2,
            },
          ],
        };

    FakeBackend dashboardBackend({Map<String, dynamic>? asOfOverride}) {
      Map<String, dynamic>? stamp(Object? raw) =>
          raw == null ? null : (asOfOverride ?? _asOfInfo(date: '$raw'));
      return FakeBackend({
        'GET /api/dashboard/billed-by-month': (o) => {
              ...series(null),
              'asOf': stamp(o.queryParameters['asOf']),
            },
        'GET /api/dashboard/collected-by-month': (o) => {
              ...series(null),
              'asOf': stamp(o.queryParameters['asOf']),
            },
        'GET /api/dashboard/outstanding-by-age': (o) => {
              'coverage': 'ALL',
              'regionCoverage': {'allRegions': true, 'regions': const []},
              'asOf': stamp(o.queryParameters['asOf']),
              'buckets': const <Map<String, dynamic>>[],
            },
        'GET /api/dashboard/top-outstanding-customers': (o) => {
              ...ranking(null, 'outstanding', 'openInvoices'),
              'asOf': stamp(o.queryParameters['asOf']),
            },
        'GET /api/dashboard/top-paying-customers': (o) => {
              ...ranking(null, 'collected', 'payments'),
              'asOf': stamp(o.queryParameters['asOf']),
            },
        'GET /api/invoices/summary': (o) => <String, dynamic>{'outstanding': 1000.0},
        'GET /api/promises/summary': (o) => <String, dynamic>{'openCount': 1},
        'GET /api/promises': (o) => _page(const []),
        'GET /api/as-of': (o) => _capability(),
        'GET /api/regions/my': (o) => {'allRegions': true, 'regions': const []},
        'GET /api/regions': (o) => {'content': const <Map<String, dynamic>>[]},
      });
    }

    Future<ProviderContainer> pumpDashboard(
        WidgetTester tester, FakeBackend backend) async {
      tester.view.physicalSize = const Size(1400, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(_staff()),
        ],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: const Scaffold(body: DashboardScreen()),
        ),
      ));
      await tester.pumpAndSettle();
      return ProviderScope.containerOf(tester.element(find.byType(DashboardScreen)));
    }

    testWidgets('every figure on the dashboard is asked as of the one date the header names',
        (tester) async {
      final backend = dashboardBackend();
      final container = await pumpDashboard(tester, backend);

      expect(find.textContaining('as of today (UTC)'), findsOneWidget);
      expect(find.text('As of a date…'), findsOneWidget);

      container.read(dashboardAsOfProvider.notifier).state = DateTime.utc(2026, 1, 31);
      await tester.pumpAndSettle();

      // Half a dashboard in January under one date control is the misreading this has to make
      // impossible, so the tiles and the promise cards carry it too — not only the five figures.
      for (final key in const [
        'GET /api/dashboard/billed-by-month',
        'GET /api/dashboard/collected-by-month',
        'GET /api/dashboard/outstanding-by-age',
        'GET /api/dashboard/top-outstanding-customers',
        'GET /api/dashboard/top-paying-customers',
        'GET /api/invoices/summary',
        'GET /api/promises/summary',
        'GET /api/promises',
      ]) {
        expect(_asOfSent(backend, key), _january, reason: '$key must carry the date');
      }
      expect(find.text('As of $_januaryLabel — showing history'), findsOneWidget);
      expect(find.textContaining('as of today (UTC)'), findsNothing);
      expect(find.textContaining('as of $_januaryLabel'), findsOneWidget);
    });

    testWidgets('a dashboard figure answered from the seed rows says so in the server\'s words',
        (tester) async {
      const note = 'Their values are the values as first recorded and are not what was true then.';
      final backend = dashboardBackend(
          asOfOverride: _asOfInfo(exact: false, origin: 'SEEDED', notes: const [note]));
      final container = await pumpDashboard(tester, backend);

      container.read(dashboardAsOfProvider.notifier).state = DateTime.utc(2026, 1, 31);
      await tester.pumpAndSettle();

      expect(find.text(note), findsOneWidget);
    });
  });

  group('the as-of capability', () {
    test('an installation with no mirrors offers nothing to ask as of a date', () {
      const nothing = AsOfCapability.unknown;
      expect(nothing.isAvailable, isFalse);
      expect(nothing.supports('invoices'), isFalse);
    });

    test('a pre-floor date is offered when the policy answers it and not when it refuses', () {
      final seeded = AsOfCapability.fromJson(_capability());
      final rejecting = AsOfCapability.fromJson(_capability(preFloor: 'reject'));
      expect(seeded.earliestOffered, DateTime.utc(2025, 1, 1));
      // "I would rather have no number than a caveated one" — so the picker stops at the floor
      // instead of offering a date the server answers with 400 (B3).
      expect(rejecting.earliestOffered, DateTime.utc(2026, 1, 1));
      expect(seeded.supports('INVOICES'), isTrue);
    });

    test('the server\'s today bounds the picker, not the browser\'s', () {
      final capability = AsOfCapability.fromJson(_capability());
      expect(capability.latestOffered(DateTime.utc(2030, 1, 1)), DateTime.utc(2026, 9, 23));
    });
  });
}
