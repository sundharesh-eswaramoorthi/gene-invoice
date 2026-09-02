import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/products/products_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

/// Records every request and holds its response until the test completes it,
/// so search requests can be finished out of order through the same dio the
/// running app uses.
class _RecordedRequest {
  final String label;
  final Completer<Object> completer = Completer<Object>();

  _RecordedRequest(this.label);
}

class _FakeAdapter implements HttpClientAdapter {
  final List<_RecordedRequest> requests = [];

  int count(String label) => requests.where((r) => r.label == label).length;

  List<_RecordedRequest> pending(String label) => requests
      .where((r) => r.label == label && !r.completer.isCompleted)
      .toList();

  void succeed(String label, Object payload) =>
      pending(label).single.completer.complete(payload);

  void fail(String label, String message) =>
      pending(label).single.completer.complete(DioException(
            requestOptions: RequestOptions(path: '/api/products'),
            message: message,
          ));

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final search = options.uri.queryParameters['search'];
    final label = search == null
        ? '${options.method} ${options.uri.path}'
        : '${options.method} ${options.uri.path}?search=$search';
    final request = _RecordedRequest(label);
    requests.add(request);
    final payload = await request.completer.future;
    if (payload is Exception) throw payload;
    return ResponseBody.fromString(
      jsonEncode(payload),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

const _viewerUser = CurrentUser(
  id: 1,
  username: 'viewer1',
  fullName: 'Vera Viewer',
  role: 'CASHIER',
  privileges: {Privileges.productView},
  customerId: null,
);

final _allProductsJson = [
  {'id': 1, 'name': 'Apple', 'description': 'Green fruit', 'price': 1.5, 'active': true},
  {'id': 2, 'name': 'Banana', 'description': 'Yellow fruit', 'price': 0.75, 'active': true},
];

final _apricotJson = [
  {'id': 3, 'name': 'Apricot', 'description': null, 'price': 2.0, 'active': true},
];

final _plumJson = [
  {'id': 4, 'name': 'Plum', 'description': null, 'price': 3.0, 'active': true},
];

Map<String, dynamic> _product(int id, String name) =>
    {'id': id, 'name': name, 'description': null, 'price': 1.0, 'active': true};

void main() {
  // In flutter_test's fake-async zone the dio request/response chain only
  // advances on pumps carrying a non-zero duration, so every stage of a
  // request is followed by one.
  Future<void> flushChain(WidgetTester tester) =>
      tester.pump(const Duration(milliseconds: 50));

  Future<_FakeAdapter> pumpProductsScreen(WidgetTester tester) async {
    final adapter = _FakeAdapter();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(Dio()..httpClientAdapter = adapter),
          currentUserProvider.overrideWithValue(_viewerUser),
        ],
        child: const MaterialApp(home: ProductsScreen()),
      ),
    );
    await flushChain(tester);
    adapter.succeed('GET /api/products', _allProductsJson);
    await flushChain(tester);
    await flushChain(tester);
    expect(find.text('Apple'), findsOneWidget);
    expect(find.text('Banana'), findsOneWidget);
    return adapter;
  }

  Future<void> typeQuery(WidgetTester tester, String text) async {
    await tester.enterText(find.byType(TextField), text);
    await tester.pump();
  }

  /// Advances the fake clock by [duration] (firing due debounce timers) and
  /// then flushes the dio chain so any fired request reaches the adapter.
  Future<void> elapse(WidgetTester tester, Duration duration) async {
    await tester.pump(duration);
    await flushChain(tester);
  }

  /// Completes the given pending request and flushes the response back
  /// through dio into the widget tree.
  Future<void> deliver(
      WidgetTester tester, void Function() complete) async {
    complete();
    await flushChain(tester);
    await tester.pump();
  }

  group('AC14 debounced latest-query ownership', () {
    testWidgets(
        'sends the server filter request only 300 ms after the last keystroke, '
        'with a keystroke in the window restarting the wait and no submit action',
        (tester) async {
      final adapter = await pumpProductsScreen(tester);

      await typeQuery(tester, 'a');
      await tester.pump(const Duration(milliseconds: 299));
      expect(adapter.count('GET /api/products?search=a'), 0);

      // The keystroke inside the window restarts the wait.
      await typeQuery(tester, 'ap');
      await tester.pump(const Duration(milliseconds: 299));
      expect(adapter.count('GET /api/products?search=a'), 0);
      expect(adapter.count('GET /api/products?search=ap'), 0);

      // Only when the restarted quiet period runs out is one request sent,
      // for the latest text only.
      await elapse(tester, const Duration(milliseconds: 5));
      expect(adapter.count('GET /api/products?search=a'), 0);
      expect(adapter.count('GET /api/products?search=ap'), 1);

      await deliver(
          tester, () => adapter.succeed('GET /api/products?search=ap', _apricotJson));
      expect(find.text('Apricot'), findsOneWidget);
    });

    testWidgets(
        'responses for superseded or older queries never replace the current '
        'results and never appear as a failure', (tester) async {
      final adapter = await pumpProductsScreen(tester);

      await typeQuery(tester, 'a');
      await elapse(tester, const Duration(milliseconds: 300));
      expect(adapter.pending('GET /api/products?search=a'), hasLength(1));

      await typeQuery(tester, 'ab');
      await elapse(tester, const Duration(milliseconds: 300));
      expect(adapter.pending('GET /api/products?search=ab'), hasLength(1));

      await typeQuery(tester, 'abc');
      await elapse(tester, const Duration(milliseconds: 300));
      expect(adapter.pending('GET /api/products?search=abc'), hasLength(1));

      // The latest applicable request succeeds and updates the screen.
      await deliver(
          tester, () => adapter.succeed('GET /api/products?search=abc', _plumJson));
      expect(find.text('Plum'), findsOneWidget);

      // A superseded failure is discarded: no error, results untouched.
      await deliver(
          tester, () => adapter.fail('GET /api/products?search=a', 'stale boom'));
      expect(find.text('Plum'), findsOneWidget);
      expect(find.text('stale boom'), findsNothing);

      // A superseded success is discarded too.
      await deliver(
          tester,
          () => adapter.succeed(
              'GET /api/products?search=ab', [_product(5, 'Quince')]));
      expect(find.text('Quince'), findsNothing);
      expect(find.text('Plum'), findsOneWidget);
      expect(find.text('stale boom'), findsNothing);
    });
  });

  group('AC15 last-good continuity on failure', () {
    testWidgets(
        'an applicable failure keeps the last successful products with a '
        'search error and no retry; the next applicable success replaces the '
        'products and clears the error', (tester) async {
      final adapter = await pumpProductsScreen(tester);

      // A successful applicable search.
      await typeQuery(tester, 'ap');
      await elapse(tester, const Duration(milliseconds: 300));
      await deliver(
          tester, () => adapter.succeed('GET /api/products?search=ap', _apricotJson));
      expect(find.text('Apricot'), findsOneWidget);

      // The next applicable request fails.
      await typeQuery(tester, 'apr');
      await elapse(tester, const Duration(milliseconds: 300));
      await deliver(
          tester, () => adapter.fail('GET /api/products?search=apr', 'search blew up'));

      // Last-good products remain visible beside the search error; the
      // failed request did not clear the screen.
      expect(find.text('Apricot'), findsOneWidget);
      expect(find.text('search blew up'), findsOneWidget);

      // No automatic retry is ever scheduled: even after a long idle period
      // the failed query was sent exactly once.
      await elapse(tester, const Duration(seconds: 10));
      expect(adapter.count('GET /api/products?search=apr'), 1);

      // The next applicable success replaces the results and clears the
      // earlier error together.
      await typeQuery(tester, 'apri');
      await elapse(tester, const Duration(milliseconds: 300));
      await deliver(
          tester, () => adapter.succeed('GET /api/products?search=apri', _plumJson));

      expect(find.text('search blew up'), findsNothing);
      expect(find.text('Apricot'), findsNothing);
      expect(find.text('Plum'), findsOneWidget);
    });

    testWidgets(
        'a failure with no prior successful search shows only the error, '
        'and clearing the box restores the full product list immediately',
        (tester) async {
      final adapter = await pumpProductsScreen(tester);

      await typeQuery(tester, 'zz');
      await elapse(tester, const Duration(milliseconds: 300));
      await deliver(
          tester, () => adapter.fail('GET /api/products?search=zz', 'search blew up'));
      expect(find.text('search blew up'), findsOneWidget);
      expect(adapter.count('GET /api/products?search=zz'), 1);

      // Clearing back to empty restores the complete list without waiting.
      await typeQuery(tester, '');
      await flushChain(tester);
      expect(find.text('Apple'), findsOneWidget);
      expect(find.text('Banana'), findsOneWidget);
      expect(find.text('search blew up'), findsNothing);
      // No re-fetch was needed and none was issued.
      expect(adapter.count('GET /api/products'), 1);
    });
  });
}
