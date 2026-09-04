import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/products/products_screen.dart';

Map<String, dynamic> _productJson(int id, String name, [String? description]) => {
      'id': id,
      'name': name,
      'description': description,
      'price': 9.99,
      'active': true,
    };

/// A fake transport: records every request and answers from [responder] without
/// touching the network. The response body shape matches what the products
/// screen parses (a List of product JSON maps).
Dio _fakeDio(
  Future<Object> Function(RequestOptions options) responder,
  List<RequestOptions> requests,
) {
  final dio = Dio(BaseOptions(baseUrl: 'http://test'));
  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) async {
      requests.add(options);
      try {
        final data = await responder(options);
        handler.resolve(Response(requestOptions: options, data: data, statusCode: 200));
      } on DioException catch (e) {
        handler.reject(e);
      }
    },
  ));
  return dio;
}

Future<void> _pumpScreen(WidgetTester tester, Dio dio) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(dio),
      currentUserProvider.overrideWith((ref) => null),
    ],
    child: const MaterialApp(home: ProductsScreen()),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('sends one trimmed search request 300 ms after the last keystroke, no submit',
      (tester) async {
    final requests = <RequestOptions>[];
    Future<Object> responder(RequestOptions o) async {
      if (o.queryParameters['search'] == null) {
        return [_productJson(1, 'Copier Paper', 'A4 white paper'), _productJson(2, 'Stapler')];
      }
      return [_productJson(1, 'Copier Paper', 'A4 white paper')];
    }

    await _pumpScreen(tester, _fakeDio(responder, requests));
    // The initial unfiltered load.
    expect(requests.length, 1);
    expect(requests.single.path, '/api/products');
    expect(requests.single.queryParameters.containsKey('search'), isFalse);
    expect(find.text('Stapler'), findsOneWidget);

    final box = find.byType(TextField);
    await tester.enterText(box, '  c');
    await tester.pump(const Duration(milliseconds: 100));
    // Keystroke inside the window restarts the wait: still nothing sent.
    await tester.enterText(box, '  cop');
    await tester.pump(const Duration(milliseconds: 200));
    expect(requests.length, 1);

    // 300 ms elapsed since the last keystroke: exactly one search request,
    // carrying the trimmed text.
    await tester.pump(const Duration(milliseconds: 150));
    expect(requests.length, 2);
    expect(requests.last.path, '/api/products');
    expect(requests.last.queryParameters['search'], 'cop');

    await tester.pumpAndSettle();
    expect(find.text('Copier Paper'), findsOneWidget);
    expect(find.text('Stapler'), findsNothing);
  });

  testWidgets('whitespace-only text shows all products immediately and clears a prior search',
      (tester) async {
    final requests = <RequestOptions>[];
    Future<Object> responder(RequestOptions o) async {
      final q = o.queryParameters['search'] as String?;
      if (q == null) {
        return [_productJson(1, 'Alpha Widget'), _productJson(2, 'Beta Gadget')];
      }
      if (q == 'alpha') return [_productJson(1, 'Alpha Widget')];
      return <Map<String, dynamic>>[];
    }

    await _pumpScreen(tester, _fakeDio(responder, requests));
    final box = find.byType(TextField);

    // Narrow the list first.
    await tester.enterText(box, 'alpha');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('Alpha Widget'), findsOneWidget);
    expect(find.text('Beta Gadget'), findsNothing);
    expect(requests.length, 2);

    // Whitespace-only text restores the full list immediately.
    await tester.enterText(box, '   ');
    await tester.pump();
    expect(find.text('Alpha Widget'), findsOneWidget);
    expect(find.text('Beta Gadget'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 500));
    expect(requests.length, 2); // no search request was sent

    // Search again, then clear the box: full list back without a request.
    await tester.enterText(box, 'alpha');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('Beta Gadget'), findsNothing);
    expect(requests.length, 3);

    await tester.enterText(box, '');
    await tester.pump();
    expect(find.text('Alpha Widget'), findsOneWidget);
    expect(find.text('Beta Gadget'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 500));
    expect(requests.length, 3);
  });

  testWidgets('successful search with no matches shows an empty list and no error', (tester) async {
    final requests = <RequestOptions>[];
    Future<Object> responder(RequestOptions o) async {
      if (o.queryParameters['search'] == null) return [_productJson(1, 'Alpha Widget')];
      return <Map<String, dynamic>>[];
    }

    await _pumpScreen(tester, _fakeDio(responder, requests));
    await tester.enterText(find.byType(TextField), 'zzzz nothing');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();

    expect(requests.length, 2);
    expect(requests.last.queryParameters['search'], 'zzzz nothing');
    expect(find.text('Alpha Widget'), findsNothing);
    expect(find.byType(ListTile), findsNothing);
    expect(find.text('No products match your search'), findsOneWidget);
    expect(find.text('Connection timed out'), findsNothing);
  });

  testWidgets('failed or timed-out search keeps the last list, shows the error, and does not retry',
      (tester) async {
    final requests = <RequestOptions>[];
    Future<Object> responder(RequestOptions o) async {
      final q = o.queryParameters['search'] as String?;
      if (q == null) return [_productJson(1, 'Alpha Widget'), _productJson(2, 'Beta Gadget')];
      if (q == 'alpha') return [_productJson(1, 'Alpha Widget')];
      throw DioException(
        requestOptions: o,
        type: DioExceptionType.connectionTimeout,
        message: 'Connection timed out',
      );
    }

    await _pumpScreen(tester, _fakeDio(responder, requests));
    final box = find.byType(TextField);

    // Establish a successful filtered list.
    await tester.enterText(box, 'alpha');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('Alpha Widget'), findsOneWidget);
    expect(find.text('Beta Gadget'), findsNothing);
    expect(requests.length, 2);

    // The next search times out: error shown, previous list retained.
    await tester.enterText(box, 'beta x');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(requests.length, 3);
    expect(find.text('Connection timed out'), findsOneWidget);
    expect(find.text('Alpha Widget'), findsOneWidget);

    // No automatic retry happens afterwards.
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(requests.length, 3);
    expect(find.text('Connection timed out'), findsOneWidget);
    expect(find.text('Alpha Widget'), findsOneWidget);
  });

  testWidgets('a response whose text is no longer current is discarded silently', (tester) async {
    final requests = <RequestOptions>[];
    final pending = <String, Completer<Object>>{};
    Future<Object> responder(RequestOptions o) {
      final q = o.queryParameters['search'] as String?;
      if (q == null) {
        return Future.value([_productJson(1, 'Alpha Widget'), _productJson(2, 'Beta Gadget')]);
      }
      final c = Completer<Object>();
      pending[q] = c;
      return c.future;
    }

    await _pumpScreen(tester, _fakeDio(responder, requests));
    final box = find.byType(TextField);

    await tester.enterText(box, 'alp');
    await tester.pump(const Duration(milliseconds: 400));
    expect(pending.containsKey('alp'), isTrue);

    // Retype before the first response arrives.
    await tester.enterText(box, 'bet');
    await tester.pump(const Duration(milliseconds: 400));
    expect(pending.containsKey('bet'), isTrue);
    expect(requests.length, 3);

    // The current request resolves first and is applied.
    pending['bet']!.complete([_productJson(2, 'Beta Gadget')]);
    await tester.pumpAndSettle();
    expect(find.text('Beta Gadget'), findsOneWidget);
    expect(find.text('Alpha Widget'), findsNothing);

    // The superseded response is discarded: no list change, no error.
    pending['alp']!.complete(<Map<String, dynamic>>[]);
    await tester.pumpAndSettle();
    expect(find.text('Beta Gadget'), findsOneWidget);
    expect(find.text('No products match your search'), findsNothing);
    expect(find.text('Connection timed out'), findsNothing);
  });
}
