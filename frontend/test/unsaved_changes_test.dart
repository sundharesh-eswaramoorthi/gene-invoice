import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/unsaved_changes.dart';
import 'package:go_router/go_router.dart';

void main() {
  group('UnsavedChanges', () {
    test('lets the app leave when no screen has registered', () async {
      expect(await UnsavedChanges().mayLeave(), isTrue);
    });

    test('asks the registered screen', () async {
      final unsaved = UnsavedChanges();
      Future<bool> keepEditing() async => false;
      unsaved.register(keepEditing);
      expect(await unsaved.mayLeave(), isFalse);
    });

    test('a screen going away leaves a newer screen\'s question in place', () async {
      final unsaved = UnsavedChanges();
      Future<bool> older() async => true;
      Future<bool> newer() async => false;
      unsaved.register(older);
      unsaved.register(newer);
      unsaved.unregister(older);
      expect(await unsaved.mayLeave(), isFalse);
    });
  });

  // The sidebar, the drawer, the bell and links all navigate with go(), which a PopScope never
  // sees; a route's onExit does.
  testWidgets('a go() away from a route with unsaved edits is held until the user agrees',
      (tester) async {
    final unsaved = UnsavedChanges();
    var discard = false;
    unsaved.register(() async => discard);

    final router = GoRouter(
      initialLocation: '/invoices/1',
      routes: [
        GoRoute(
          path: '/invoices/:id',
          onExit: (context, state) => unsaved.mayLeave(),
          builder: (_, __) => const Text('invoice page'),
        ),
        GoRoute(path: '/customers', builder: (_, __) => const Text('customers page')),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));

    router.go('/customers');
    await tester.pumpAndSettle();
    expect(find.text('invoice page'), findsOneWidget);

    router.go('/invoices/1?tab=history');
    await tester.pumpAndSettle();
    expect(find.text('invoice page'), findsOneWidget);

    discard = true;
    router.go('/customers');
    await tester.pumpAndSettle();
    expect(find.text('customers page'), findsOneWidget);
  });

  // A refused navigation must never start: one onExit cancels still leaves a duplicate entry in
  // the browser history that swallows the next Back press.
  testWidgets('goGuarded asks first and navigates only when the user agrees', (tester) async {
    final unsaved = UnsavedChanges();
    var asked = 0;
    var discard = false;
    unsaved.register(() async {
      asked++;
      return discard;
    });

    final router = GoRouter(
      initialLocation: '/invoices/1',
      routes: [
        GoRoute(
          path: '/invoices/:id',
          onExit: (context, state) => unsaved.mayLeave(),
          builder: (context, _) => TextButton(
            onPressed: () => goGuarded(context, '/customers'),
            child: const Text('Customers'),
          ),
        ),
        GoRoute(path: '/customers', builder: (_, __) => const Text('customers page')),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [unsavedChangesProvider.overrideWithValue(unsaved)],
      child: MaterialApp.router(routerConfig: router),
    ));

    await tester.tap(find.text('Customers'));
    await tester.pumpAndSettle();
    expect(asked, 1);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/invoices/1');

    discard = true;
    await tester.tap(find.text('Customers'));
    await tester.pumpAndSettle();
    expect(find.text('customers page'), findsOneWidget);
  });
}
