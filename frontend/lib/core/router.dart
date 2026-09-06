import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/auth/auth_controller.dart';
import '../features/auth/login_screen.dart';
import '../features/customers/customers_screen.dart';
import '../features/dashboard/dashboard_screen.dart';
import '../features/disputes/dispute_detail_screen.dart';
import '../features/disputes/disputes_screen.dart';
import '../features/invoices/invoice_form_screen.dart';
import '../features/invoices/invoices_screen.dart';
import '../features/notifications/notifications_screen.dart';
import '../features/payments/payments_screen.dart';
import '../features/products/products_screen.dart';
import '../features/strategies/strategies_providers.dart';
import '../features/strategies/strategies_screen.dart';
import '../features/strategies/strategy_form_screen.dart';
import '../shared/models/notification_strategy.dart';
import '../features/users/roles_screen.dart';
import '../features/users/users_screen.dart';
import '../shared/widgets/app_shell.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final auth = ref.watch(authControllerProvider);

  return GoRouter(
    initialLocation: '/',
    refreshListenable: _AuthChangeNotifier(ref),
    redirect: (context, state) {
      final loggedIn = auth.user != null;
      final goingToLogin = state.matchedLocation == '/login';
      if (!loggedIn && !goingToLogin) return '/login';
      if (loggedIn && goingToLogin) return '/';
      // Strategy administration is admin-only: a signed-in non-admin is redirected
      // away even on a direct URL, matching the backend's ROLE_ADMIN enforcement.
      if (loggedIn &&
          state.matchedLocation.startsWith('/strategies') &&
          !(auth.user!.isAdmin)) {
        return '/';
      }
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (c, s) => const LoginScreen()),
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/', builder: (c, s) => const DashboardScreen()),
          GoRoute(path: '/customers', builder: (c, s) => const CustomersScreen()),
          GoRoute(path: '/products', builder: (c, s) => const ProductsScreen()),
          GoRoute(path: '/invoices', builder: (c, s) => const InvoicesScreen()),
          GoRoute(path: '/invoices/new', builder: (c, s) => const InvoiceFormScreen()),
          GoRoute(path: '/payments', builder: (c, s) => const PaymentsScreen()),
          GoRoute(path: '/disputes', builder: (c, s) => const DisputesScreen()),
          GoRoute(
            path: '/disputes/:id',
            builder: (c, s) => DisputeDetailScreen(
              id: int.parse(s.pathParameters['id']!),
            ),
          ),
          GoRoute(path: '/notifications', builder: (c, s) => const NotificationsScreen()),          GoRoute(path: '/strategies', builder: (c, s) => const StrategiesScreen()),
          GoRoute(path: '/strategies/new', builder: (c, s) => const StrategyFormScreen()),
          GoRoute(
            path: '/strategies/:id/edit',
            builder: (c, s) {
              final id = int.parse(s.pathParameters['id']!);
              return FutureBuilder<List<NotificationStrategy>>(
                future: ProviderScope.containerOf(c).read(strategiesProvider.future),
                builder: (context, snapshot) {
                  final match =
                      snapshot.data?.where((st) => st.id == id).firstOrNull;
                  if (snapshot.hasData && match == null) {
                    return Scaffold(
                      appBar: AppBar(title: const Text('Edit strategy')),
                      body: const Center(child: Text('Strategy not found')),
                    );
                  }
                  if (match == null) {
                    return const Scaffold(
                        body: Center(child: CircularProgressIndicator()));
                  }
                  return StrategyFormScreen(existing: match);
                },
              );
            },
          ),
          GoRoute(path: '/users', builder: (c, s) => const UsersScreen()),
          GoRoute(path: '/roles', builder: (c, s) => const RolesScreen()),
        ],
      ),
    ],
  );
});

class _AuthChangeNotifier extends ChangeNotifier {
  _AuthChangeNotifier(Ref ref) {
    ref.listen(authControllerProvider, (_, __) => notifyListeners());
  }
}
