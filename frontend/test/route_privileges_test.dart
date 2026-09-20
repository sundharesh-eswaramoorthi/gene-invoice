import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/router.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// A screen the sidebar hides is a screen the URL cannot reach either: a hand-edited or stale
// hash used to land on a list whose header still offered "Add filter" and "Send email" above a
// 403, with a Retry that could only fail again (UI-10).

/// Signed in as [user], without the network round trip the real controller makes.
class _SignedIn extends AuthController {
  _SignedIn(super.ref, CurrentUser user) {
    state = AuthState(user: user);
  }
}

CurrentUser _user(String role, Set<String> privileges) => CurrentUser(
      id: 4,
      username: role.toLowerCase(),
      fullName: 'Test $role',
      role: role,
      privileges: privileges,
      customerId: null,
    );

// A Sales POC: everything their day needs, and neither USER_VIEW nor ROLE_VIEW.
final _priya = _user('SALES_POC', {
  Privileges.customerView,
  Privileges.invoiceView,
  Privileges.invoiceManage,
  Privileges.paymentView,
  Privileges.promiseView,
  Privileges.pocView,
});

final _admin = _user('ADMIN', {
  Privileges.userView,
  Privileges.userManage,
  Privileges.roleView,
  Privileges.invoiceView,
});

Map<String, dynamic> _emptyPage(RequestOptions _) => const {
      'content': <Map<String, dynamic>>[],
      'page': 0,
      'size': 20,
      'totalElements': 0,
      'totalPages': 1,
    };

FakeBackend _backend() => FakeBackend({
      'GET /api/table-schemas/users': (_) => {'entity': 'users', 'columns': <dynamic>[]},
      'GET /api/users': _emptyPage,
      'GET /api/users/summary': (_) => <String, dynamic>{},
    });

Future<GoRouter> _pump(WidgetTester tester, CurrentUser user, FakeBackend backend) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(backend.dio),
    authControllerProvider.overrideWith((ref) => _SignedIn(ref, user)),
    unreadCountProvider.overrideWith((ref) => Stream.value(0)),
    inboxUnreadCountProvider.overrideWith((ref) => Stream.value(0)),
  ]);
  addTearDown(container.dispose);

  final router = container.read(routerProvider);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

String _location(GoRouter router) =>
    router.routerDelegate.currentConfiguration.uri.toString();

void main() {
  testWidgets('a user without USER_VIEW is sent home from #/users, not to a 403 list',
      (tester) async {
    final backend = _backend();
    final router = await _pump(tester, _priya, backend);

    router.go('/users');
    await tester.pumpAndSettle();

    expect(_location(router), '/');
    // Nothing was asked for that could only come back 403, and no list header was offered.
    expect(backend.sent('GET /api/users'), isEmpty);
    expect(find.text('Add filter'), findsNothing);
    expect(find.text('Retry'), findsNothing);
  });

  testWidgets('and from #/roles too', (tester) async {
    final backend = _backend();
    final router = await _pump(tester, _priya, backend);

    router.go('/roles');
    await tester.pumpAndSettle();

    expect(_location(router), '/');
  });

  testWidgets('and from #/notifications, which the bell is gated on the same way',
      (tester) async {
    final backend = _backend();
    final router = await _pump(tester, _priya, backend);

    router.go('/notifications');
    await tester.pumpAndSettle();

    expect(_location(router), '/');
  });

  testWidgets('a user who holds the privilege still opens the screen', (tester) async {
    final backend = _backend();
    final router = await _pump(tester, _admin, backend);

    router.go('/users');
    await tester.pumpAndSettle();

    expect(_location(router), '/users');
    expect(backend.sent('GET /api/users'), isNotEmpty);
  });
}
