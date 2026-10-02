import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:gene_invoice/shared/widgets/as_of_banner.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _staff = CurrentUser(
  id: 1,
  username: 'admin',
  fullName: 'System Administrator',
  role: 'ADMIN',
  privileges: {Privileges.customerView, Privileges.invoiceView},
  customerId: null,
);

/// The real shell over a nested Navigator, as the app has it: the page is a route of its own, and
/// that route's modal barrier is what could hide the banner from assistive technology (ASO-R1-1).
Future<ProviderContainer> _pumpShell(WidgetTester tester,
    {required Size size, DateTime? asOf}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(FakeBackend({}).dio),
    currentUserProvider.overrideWithValue(_staff),
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: '/customers', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(
          path: '/customers',
          builder: (_, __) => const Center(child: Text('The customers page')),
        ),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return container;
}

final _bannerLabel = RegExp(r'Viewing .* read only');

Finder get _bannerBack =>
    find.descendant(of: find.byType(AsOfBanner), matching: find.text('Back to today'));

void main() {
  group('the as-of banner is exposed to assistive technology (ASO-R1-1)', () {
    testWidgets('on a past date its text and its Back to today action are in the semantics tree',
        (tester) async {
      final handle = tester.ensureSemantics();
      final container =
          await _pumpShell(tester, size: const Size(1366, 900), asOf: DateTime(2026, 9, 25));

      expect(find.text('Viewing 25 Sep 2026 (end of day, UTC) — read only'), findsOneWidget);
      expect(find.bySemanticsLabel(_bannerLabel), findsOneWidget);
      expect(_bannerBack, findsOneWidget);
      expect(tester.getSemantics(_bannerBack).getSemanticsData().hasAction(SemanticsAction.tap),
          isTrue);

      // The page and the rail are still there to be read.
      expect(find.bySemanticsLabel('The customers page'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('Customers')), findsWidgets);

      // The action it announces works.
      await tester.tap(_bannerBack);
      await tester.pumpAndSettle();
      expect(container.read(viewScopeProvider).asOf, isNull);
      expect(find.bySemanticsLabel(_bannerLabel), findsNothing);
      handle.dispose();
    });

    testWidgets('on a narrow screen the drawer entries are still in the semantics tree',
        (tester) async {
      final handle = tester.ensureSemantics();
      await _pumpShell(tester, size: const Size(700, 900), asOf: DateTime(2026, 9, 25));

      expect(find.bySemanticsLabel(_bannerLabel), findsOneWidget);
      final scaffold = tester.firstState<ScaffoldState>(find.byType(Scaffold));
      scaffold.openDrawer();
      await tester.pumpAndSettle();
      expect(find.bySemanticsLabel(RegExp('Invoices')), findsWidgets);
      handle.dispose();
    });
  });
}
