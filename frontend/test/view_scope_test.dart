import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/as_of.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/storage/secure_storage.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/table/table_providers.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/fake_backend.dart';

/// A Dio whose last interceptor records each request and answers it, standing in for the network.
Dio _bareDio(List<Interceptor> before, List<RequestOptions> seen) => Dio()
  ..interceptors.addAll(before)
  ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    seen.add(options);
    handler.resolve(Response(requestOptions: options, statusCode: 200, data: const {}));
  }));

Response<dynamic> _response({String? asOfHeader}) => Response(
      requestOptions: RequestOptions(path: '/api/invoices'),
      statusCode: 200,
      headers: Headers.fromMap({
        if (asOfHeader != null) 'X-View-As-Of': [asOfHeader],
      }),
    );

Map<String, dynamic> _page({List<int> regionIds = const [], List<int> book = const [], String? asOf}) => {
      'content': const [],
      'page': 0,
      'size': 20,
      'totalElements': 0,
      'totalPages': 0,
      'appliedFilters': const [],
      'lockedFilters': const [],
      'regionIds': regionIds,
      'bookRegionIds': book,
      'asOf': asOf,
    };

CurrentUser _user(int id) => CurrentUser(
      id: id,
      username: 'u$id',
      fullName: null,
      role: 'CASHIER',
      privileges: const {},
      customerId: null,
    );

class _MemoryTokens implements TokenStorage {
  String? token = 'abc';
  @override
  Future<void> save(String t) async => token = t;
  @override
  Future<String?> read() async => token;
  @override
  Future<void> clear() async => token = null;
}

/// Stands in for the network under the real [dioProvider], so every interceptor it installs runs.
class _RecordingAdapter implements HttpClientAdapter {
  final List<RequestOptions> seen = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    seen.add(options);
    return ResponseBody.fromString('{}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  group('ViewScope', () {
    test('sends nothing when nothing is chosen', () {
      expect(ViewScope.live.apiParams, isEmpty);
      expect(const ViewScope().isHistorical, isFalse);
      // Choosing no region is not a region filter either.
      expect(const ViewScope(regionIds: {}).apiParams, isEmpty);
    });

    test('region ids are one sorted string, so equal choices are equal scopes', () {
      const a = ViewScope(regionIds: {3, 1, 2});
      const b = ViewScope(regionIds: {2, 3, 1});

      expect(a.apiParams, {'regionIds': '1,2,3'});
      expect(a, b);
      expect(a.hashCode, b.hashCode);
      expect(a == const ViewScope(regionIds: {1, 2}), isFalse);
    });

    test('the as-of date is the calendar date picked, never shifted to UTC', () {
      final evening = ViewScope(asOf: DateTime(2026, 6, 30, 23, 30));

      expect(evening.apiParams, {'asOf': '2026-06-30'});
      expect(evening.isHistorical, isTrue);
      expect(evening, ViewScope(asOf: DateTime(2026, 6, 30)));
      expect(
        ViewScope(regionIds: const {2}, asOf: DateTime(2026, 1, 5)).apiParams,
        {'regionIds': '2', 'asOf': '2026-01-05'},
      );
    });

    test('the controller keeps a date only, treats no regions as all, and clears', () {
      final c = ViewScopeController();

      c.setRegions({2, 1});
      c.setAsOf(DateTime(2026, 6, 30, 15, 45));
      expect(c.state.apiParams, {'regionIds': '1,2', 'asOf': '2026-06-30'});
      expect(c.state.asOf, DateTime(2026, 6, 30));

      c.setRegions(<int>{});
      expect(c.state.regionIds, isNull);
      expect(c.state.asOf, DateTime(2026, 6, 30));

      c.setRegions({4});
      c.setAsOf(null);
      expect(c.state.apiParams, {'regionIds': '4'});

      c.clear();
      expect(c.state, ViewScope.live);
    });
  });

  group('viewScopeInterceptor', () {
    test('adds the scope to GET and POST requests when set', () async {
      final seen = <RequestOptions>[];
      final dio = _bareDio([
        viewScopeInterceptor(() => ViewScope(regionIds: const {2, 1}, asOf: DateTime(2026, 6, 30))),
      ], seen);

      await dio.get('/api/invoices', queryParameters: {'page': 0});
      await dio.post('/api/invoices/bulk', data: {'action': 'CANCEL'});

      expect(seen[0].queryParameters, {'page': 0, 'regionIds': '1,2', 'asOf': '2026-06-30'});
      expect(seen[1].queryParameters, {'regionIds': '1,2', 'asOf': '2026-06-30'});
      expect(seen[1].data, {'action': 'CANCEL'});
    });

    test('adds nothing when nothing is set', () async {
      final seen = <RequestOptions>[];
      final dio = _bareDio([viewScopeInterceptor(() => ViewScope.live)], seen);

      await dio.get('/api/documents', queryParameters: {'entityType': 'INVOICE', 'entityId': 42});
      await dio.post('/api/invoices/export');

      expect(seen[0].queryParameters, {'entityType': 'INVOICE', 'entityId': 42});
      expect(seen[1].queryParameters, isEmpty);
    });

    test('never replaces a parameter the caller set itself', () async {
      final seen = <RequestOptions>[];
      final dio = _bareDio([
        viewScopeInterceptor(() => ViewScope(regionIds: const {1, 2}, asOf: DateTime(2026, 6, 30))),
      ], seen);

      await dio.get('/api/automation/rules/preview', queryParameters: {'regionIds': '5'});

      expect(seen.single.queryParameters, {'regionIds': '5', 'asOf': '2026-06-30'});
    });

    test('reads the scope as each request goes out', () async {
      final seen = <RequestOptions>[];
      var scope = ViewScope.live;
      final dio = _bareDio([viewScopeInterceptor(() => scope)], seen);

      await dio.get('/api/customers');
      scope = const ViewScope(regionIds: {3});
      await dio.get('/api/customers');

      expect(seen[0].queryParameters, isEmpty);
      expect(seen[1].queryParameters, {'regionIds': '3'});
    });
  });

  test('the app\'s own Dio sends the scope, alongside the token, on every request', () async {
    final container = ProviderContainer(overrides: [tokenStorageProvider.overrideWithValue(_MemoryTokens())]);
    addTearDown(container.dispose);
    final adapter = _RecordingAdapter();
    final dio = container.read(dioProvider)..httpClientAdapter = adapter;

    await dio.get('/api/invoices', queryParameters: {'page': 0});
    container.read(viewScopeProvider.notifier)
      ..setRegions({2, 1})
      ..setAsOf(DateTime(2026, 6, 30));
    await dio.get('/api/invoices', queryParameters: {'page': 0});
    await dio.post('/api/invoices/export', data: const {'action': 'EXPORT'});

    expect(adapter.seen.map((o) => o.queryParameters), [
      {'page': 0},
      {'page': 0, 'regionIds': '1,2', 'asOf': '2026-06-30'},
      {'regionIds': '1,2', 'asOf': '2026-06-30'},
    ]);
    expect(adapter.seen.map((o) => o.headers['Authorization']), everyElement('Bearer abc'));
    expect(adapter.seen[1].uri.query, 'page=0&regionIds=1%2C2&asOf=2026-06-30');
  });

  group('checkAsOf', () {
    final asOf = DateTime(2026, 6, 30);

    test('a live view accepts any answer', () {
      expect(checkAsOf(_response(), null), AsOfApplied.live);
      expect(checkAsOf(_response(asOfHeader: 'live'), null), AsOfApplied.live);
    });

    test('the date echoed back is history', () {
      expect(checkAsOf(_response(asOfHeader: '2026-06-30'), asOf), AsOfApplied.historical);
    });

    test('"live" is an endpoint that always answers live', () {
      expect(checkAsOf(_response(asOfHeader: 'live'), asOf), AsOfApplied.live);
    });

    test('no header, or another date, is a mismatch', () {
      expect(() => checkAsOf(_response(), asOf), throwsA(isA<AsOfMismatch>()));
      expect(() => checkAsOf(_response(asOfHeader: '2026-06-29'), asOf), throwsA(isA<AsOfMismatch>()));
      expect(const AsOfMismatch().toString(), "This screen can't show history");
    });
  });

  group('FakeBackend', () {
    test('dioWith runs the given interceptors ahead of its routing', () async {
      final backend = FakeBackend({'GET /api/invoices': (_) => _page()});
      final dio = backend.dioWith([viewScopeInterceptor(() => const ViewScope(regionIds: {2}))]);

      await dio.get('/api/invoices', queryParameters: {'page': 0});

      expect(backend.sent('GET /api/invoices').single.queryParameters, {'page': 0, 'regionIds': '2'});
    });

    test('FakeReply answers with its status and headers', () async {
      final backend = FakeBackend({
        'POST /api/invoices': (_) => const FakeReply(202, {'status': 'PENDING_APPROVAL'},
            headers: {'Location': ['/api/approvals/41']}),
        'GET /api/invoices': (_) => FakeReply(200, _page(), headers: {
              'X-View-As-Of': ['2026-06-30'],
            }),
        'PUT /api/invoices/1': (_) => const FakeReply(409, {'message': 'stale'}),
      });
      final dio = backend.dio;

      final held = await dio.post('/api/invoices', data: const {});
      expect(held.statusCode, 202);
      expect(held.data, {'status': 'PENDING_APPROVAL'});
      expect(held.headers.value('location'), '/api/approvals/41');

      final list = await dio.get('/api/invoices');
      expect(checkAsOf(list, DateTime(2026, 6, 30)), AsOfApplied.historical);

      await expectLater(
        dio.put('/api/invoices/1'),
        throwsA(isA<DioException>().having((e) => e.response?.statusCode, 'status', 409)),
      );
    });
  });

  group('table providers', () {
    const req = TableRequest(entity: 'invoices', path: '/api/invoices', query: TableQuery());

    late FakeBackend backend;
    late ProviderContainer container;

    setUp(() {
      backend = FakeBackend({});
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [dioProvider.overrideWithValue(dio)]);
      addTearDown(container.dispose);
    });

    test('a list and its tiles ask again, with the regions, when the scope changes', () async {
      backend.routes['GET /api/invoices'] = (o) =>
          _page(regionIds: o.queryParameters['regionIds'] == '1,2' ? [1, 2] : [1, 2, 3], book: [2]);
      backend.routes['GET /api/invoices/summary'] = (_) => {'count': 0};
      container.listen(tablePageProvider(req), (_, __) {});
      container.listen(tableSummaryProvider(req.forSummary), (_, __) {});

      final first = await container.read(tablePageProvider(req).future);
      await container.read(tableSummaryProvider(req.forSummary).future);
      expect(first.regionIds, [1, 2, 3]);
      expect(first.bookRegionIds, [2]);
      expect(first.asOf, isNull);
      expect(backend.sent('GET /api/invoices').single.queryParameters, {'page': 0, 'size': 20});

      container.read(viewScopeProvider.notifier).setRegions({2, 1});
      final second = await container.read(tablePageProvider(req).future);
      await container.read(tableSummaryProvider(req.forSummary).future);

      expect(second.regionIds, [1, 2]);
      expect(backend.sent('GET /api/invoices').last.queryParameters,
          {'page': 0, 'size': 20, 'regionIds': '1,2'});
      expect(backend.sent('GET /api/invoices/summary').map((r) => r.queryParameters),
          [<String, dynamic>{}, {'regionIds': '1,2'}]);
    });

    test('a historical list is accepted only when the server says it answered that date', () async {
      backend.routes['GET /api/invoices'] = (o) => FakeReply(200, _page(asOf: '2026-06-30'), headers: {
            'X-View-As-Of': [o.queryParameters['asOf'] as String],
          });
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      container.listen(tablePageProvider(req), (_, __) {});

      final page = await container.read(tablePageProvider(req).future);

      expect(page.asOf, '2026-06-30');
      expect(backend.sent('GET /api/invoices').single.queryParameters['asOf'], '2026-06-30');
    });

    test('a historical list the server answered live without saying so is an error', () async {
      backend.routes['GET /api/invoices'] = (_) => _page();
      backend.routes['GET /api/invoices/summary'] = (_) => {'count': 0};
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      container.listen(tablePageProvider(req), (_, __) {});
      container.listen(tableSummaryProvider(req.forSummary), (_, __) {});

      await expectLater(container.read(tablePageProvider(req).future), throwsA(isA<AsOfMismatch>()));
      await expectLater(
          container.read(tableSummaryProvider(req.forSummary).future), throwsA(isA<AsOfMismatch>()));
    });
  });

  group('a list\'s selection', () {
    final rows = [
      for (final id in [1, 2, 3]) {'id': id, 'invoiceNumber': 'INV-$id'},
    ];

    Future<(FakeBackend, ProviderContainer)> pumpList(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1366, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      late ProviderContainer container;
      final backend = FakeBackend({
        'GET /api/invoices': (o) => FakeReply(
              200,
              {..._page(), 'content': rows, 'totalElements': rows.length, 'totalPages': 1},
              headers: {'X-View-As-Of': ['${o.queryParameters['asOf'] ?? 'live'}']},
            ),
        'GET /api/table-schemas/invoices': (_) => {'entity': 'invoices', 'columns': const []},
        'POST /api/invoices/export': (_) => 'id\n',
      });
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [dioProvider.overrideWithValue(dio)]);
      addTearDown(container.dispose);

      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(
            body: DataTableScaffold<Map<String, dynamic>>(
              entity: 'invoices',
              path: '/api/invoices',
              query: const TableQuery(),
              onQueryChanged: (_) {},
              parse: (json) => json,
              idOf: (row) => row['id'] as int,
              columns: [
                TableColumnSpec(label: 'Number', cell: (_, row) => Text(row['invoiceNumber'] as String)),
              ],
              canExport: true,
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return (backend, container);
    }

    // The heading's checkbox comes first, then one per row in order.
    Future<void> tickRow(WidgetTester tester, int id) async {
      await tester.tap(find.byType(Checkbox).at(id));
      await tester.pumpAndSettle();
    }

    testWidgets('is dropped when the regions change, so an export never carries rows no longer shown',
        (tester) async {
      final (backend, container) = await pumpList(tester);
      await tickRow(tester, 1);
      await tickRow(tester, 2);
      expect(find.text('2 selected'), findsOneWidget);

      container.read(viewScopeProvider.notifier).setRegions({2});
      await tester.pumpAndSettle();

      expect(find.textContaining('selected'), findsNothing);
      expect(backend.sent('GET /api/invoices').last.queryParameters['regionIds'], '2');

      // What is ticked now is all that goes out.
      await tickRow(tester, 3);
      await tester.tap(find.text('Export selected'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/invoices/export').single.data['ids'], [3]);
    });

    testWidgets('is dropped on moving between today and a past date', (tester) async {
      final (_, container) = await pumpList(tester);
      await tickRow(tester, 1);
      expect(find.text('1 selected'), findsOneWidget);

      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();

      expect(find.textContaining('selected'), findsNothing);
      expect(find.text('INV-1'), findsOneWidget);
    });
  });

  group('clearing the scope', () {
    test('when someone else signs in', () {
      final signedIn = StateProvider<CurrentUser?>((_) => _user(1));
      final container = ProviderContainer(overrides: [
        currentUserProvider.overrideWith((ref) => ref.watch(signedIn)),
      ]);
      addTearDown(container.dispose);

      container.read(viewScopeProvider.notifier)
        ..setRegions({2})
        ..setAsOf(DateTime(2026, 6, 30));
      container.read(signedIn.notifier).state = _user(1);
      expect(container.read(viewScopeProvider).apiParams, {'regionIds': '2', 'asOf': '2026-06-30'});
      expect(container.read(isHistoricalProvider), isTrue);

      container.read(signedIn.notifier).state = _user(2);
      expect(container.read(viewScopeProvider), ViewScope.live);
      expect(container.read(isHistoricalProvider), isFalse);
    });

    test('on sign out', () async {
      SharedPreferences.setMockInitialValues({});
      final tokens = _MemoryTokens();
      final container = ProviderContainer(overrides: [tokenStorageProvider.overrideWithValue(tokens)]);
      addTearDown(container.dispose);

      container.read(viewScopeProvider.notifier)
        ..setRegions({2})
        ..setAsOf(DateTime(2026, 6, 30));
      await container.read(authControllerProvider.notifier).logout();

      expect(container.read(viewScopeProvider), ViewScope.live);
      expect(tokens.token, isNull);
    });
  });
}
