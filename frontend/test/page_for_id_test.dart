import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/router.dart';
import 'package:go_router/go_router.dart';

void main() {
  // int.parse on "abc" threw inside the route builder, which shows a blank grey error box.
  testWidgets('a detail URL whose id is not a number shows the not-found state', (tester) async {
    final router = GoRouter(
      initialLocation: '/invoices/abc',
      routes: [
        GoRoute(path: '/invoices', builder: (_, __) => const Text('invoice list')),
        GoRoute(
          path: '/invoices/:id',
          builder: (c, s) => pageForId(s,
              noun: 'invoice', backTo: '/invoices', build: (id) => Text('invoice $id')),
        ),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();

    expect(find.text('That invoice does not exist.'), findsOneWidget);
    await tester.tap(find.text('Go back'));
    await tester.pumpAndSettle();
    expect(find.text('invoice list'), findsOneWidget);

    router.go('/invoices/12');
    await tester.pumpAndSettle();
    expect(find.text('invoice 12'), findsOneWidget);
  });
}
