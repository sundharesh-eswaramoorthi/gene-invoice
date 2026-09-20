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

Future<void> _pumpShell(WidgetTester tester, Size size, {int unread = 3}) async {
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
      currentUserProvider.overrideWithValue(_longNamedUser),
      unreadCountProvider.overrideWith((ref) => Stream.value(unread)),
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
}

void main() {
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

  testWidgets('a three-figure unread count sits at the corner of the bell, not over it',
      (tester) async {
    await _pumpShell(tester, const Size(1366, 900), unread: 150);

    final bell = tester.getRect(find.byIcon(Icons.notifications_outlined));
    final badge = tester.getRect(find.text('99+'));
    expect(badge.right, lessThanOrEqualTo(1366));

    // The count belongs at the icon's corner. It used to grow down and to the left over the
    // bell, leaving a sliver of it and no way to tell what the button was (UI-06).
    final over = Rect.fromLTRB(
      badge.left > bell.left ? badge.left : bell.left,
      badge.top > bell.top ? badge.top : bell.top,
      badge.right < bell.right ? badge.right : bell.right,
      badge.bottom < bell.bottom ? badge.bottom : bell.bottom,
    );
    final covered =
        over.width <= 0 || over.height <= 0 ? 0.0 : over.width * over.height;
    expect(covered / (bell.width * bell.height), lessThan(0.2),
        reason: 'badge $badge covers bell $bell');
  });

  testWidgets('on a desktop the account label shows, cut short', (tester) async {
    await _pumpShell(tester, const Size(1366, 900));

    final label = find.textContaining('collections.representative.north •');
    expect(label, findsOneWidget);
    expect(tester.getSize(label).width, lessThanOrEqualTo(200));
  });
}
