import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

const _longNamedUser = CurrentUser(
  id: 7,
  username: 'collections.representative.north',
  fullName: 'Collections Representative',
  role: 'COLLECTION_POC',
  privileges: {Privileges.notificationView},
  customerId: null,
);

Future<void> _pumpShell(WidgetTester tester, Size size, {CurrentUser user = _longNamedUser}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: '/',
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/', builder: (_, __) => const Text('dashboard page')),
          GoRoute(path: '/notifications', builder: (_, __) => const Text('notifications page')),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      currentUserProvider.overrideWithValue(user),
      unreadCountProvider.overrideWith((ref) => Stream.value(3)),
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
}

List<String> _railLabels(WidgetTester tester) => tester
    .widget<NavigationRail>(find.byType(NavigationRail))
    .destinations
    .map((d) => (d.label as Text).data!)
    .toList();

void main() {
  testWidgets('Inbox sits directly below Dashboard for staff, and customers have none', (tester) async {
    await _pumpShell(tester, const Size(1366, 900));
    expect(_railLabels(tester).take(2), ['Dashboard', 'Inbox']);

    await _pumpShell(tester, const Size(1366, 900), user: const CurrentUser(
      id: 9,
      username: 'acme',
      fullName: 'Acme Ltd',
      role: 'CUSTOMER',
      privileges: {Privileges.notificationView},
      customerId: 3,
    ));
    expect(_railLabels(tester), isNot(contains('Inbox')));
  });

  testWidgets('on a phone a long account label never covers the menu button', (tester) async {
    await _pumpShell(tester, const Size(400, 820));

    final menu = tester.getRect(find.byTooltip('Open navigation menu'));
    final bell = tester.getRect(find.byTooltip('Notifications'));
    expect(menu.overlaps(bell), isFalse);
    expect(find.textContaining('collections.representative.north •'), findsNothing);

    await tester.tap(find.byTooltip('Open navigation menu'));
    await tester.pumpAndSettle();
    expect(find.byType(Drawer), findsOneWidget);
    expect(find.text('notifications page'), findsNothing);
  });

  testWidgets('on a desktop the account label shows, cut short', (tester) async {
    await _pumpShell(tester, const Size(1366, 900));

    final label = find.textContaining('collections.representative.north •');
    expect(label, findsOneWidget);
    expect(tester.getSize(label).width, lessThanOrEqualTo(200));
  });
}
