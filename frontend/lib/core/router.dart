import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/auth/auth_controller.dart';
import '../features/auth/login_screen.dart';
import '../features/customers/customer_detail_screen.dart';
import '../features/customers/customers_screen.dart';
import '../features/dashboard/dashboard_screen.dart';
import '../features/disputes/dispute_detail_screen.dart';
import '../features/disputes/disputes_screen.dart';
import '../features/invoices/invoice_detail_screen.dart';
import '../features/invoices/invoice_form_screen.dart';
import '../features/invoices/invoices_screen.dart';
import '../features/notifications/notifications_screen.dart';
import '../features/payments/payment_detail_screen.dart';
import '../features/payments/payments_screen.dart';
import '../features/products/products_screen.dart';
import '../features/promises/promises_screen.dart';
import '../features/users/roles_screen.dart';
import '../features/users/users_screen.dart';
import '../shared/widgets/app_shell.dart';
import 'table/route_query.dart';
import 'table/table_providers.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final auth = ref.watch(authControllerProvider);

  /// The page size this user last chose for a table, or the default.
  int sizeFor(String entity) => ref.read(pageSizeStoreProvider)[entity] ?? 20;

  return GoRouter(
    initialLocation: '/',
    refreshListenable: _RouterRefresh(ref),
    redirect: (context, state) {
      final loggedIn = auth.user != null;
      final goingToLogin = state.matchedLocation == '/login';
      if (!loggedIn && !goingToLogin) return '/login';
      if (loggedIn && goingToLogin) return '/';
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (c, s) => const LoginScreen()),
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/', builder: (c, s) => const DashboardScreen()),

          // List pages open unfiltered for every role; a scope the server enforces comes back
          // with the page and is shown as a locked chip.
          GoRoute(
            path: '/customers',
            builder: (c, s) => CustomersScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('customers'), defaultSort: 'name,asc'),
            ),
          ),
          // Detail screens are keyed by record id. go_router keys a page by its route pattern, so
          // without this, moving from one customer to another reuses the screen's state and its
          // form keeps the previous record's values — which Save would then write onto this one.
          // A tab change keeps the same id, so it still reuses the screen.
          GoRoute(
            path: '/customers/:id',
            builder: (c, s) => CustomerDetailScreen(
              key: ValueKey('customer-${s.pathParameters['id']}'),
              id: int.parse(s.pathParameters['id']!),
              initialTab: s.uri.queryParameters['tab'],
            ),
          ),

          GoRoute(
            path: '/products',
            builder: (c, s) => ProductsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('products'), defaultSort: 'name,asc'),
            ),
          ),

          GoRoute(path: '/invoices/new', builder: (c, s) => const InvoiceFormScreen()),
          GoRoute(
            path: '/invoices',
            builder: (c, s) => InvoicesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('invoices'), defaultSort: 'invoiceDate,desc'),
            ),
          ),
          GoRoute(
            path: '/invoices/:id',
            builder: (c, s) => InvoiceDetailScreen(
              key: ValueKey('invoice-${s.pathParameters['id']}'),
              id: int.parse(s.pathParameters['id']!),
              initialTab: s.uri.queryParameters['tab'],
            ),
          ),

          GoRoute(
            path: '/payments',
            builder: (c, s) => PaymentsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('payments'), defaultSort: 'paidAt,desc'),
            ),
          ),
          GoRoute(
            path: '/payments/:id',
            builder: (c, s) => PaymentDetailScreen(
              key: ValueKey('payment-${s.pathParameters['id']}'),
              id: int.parse(s.pathParameters['id']!),
              initialTab: s.uri.queryParameters['tab'],
            ),
          ),

          GoRoute(
            path: '/promises',
            builder: (c, s) => PromisesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('promises'), defaultSort: 'promisedDate,desc'),
            ),
          ),
          // A promise notification deep-links here; the promise lives on its customer's screen.
          GoRoute(
            path: '/promises/:id',
            builder: (c, s) => PromiseRedirectScreen(
              key: ValueKey('promise-${s.pathParameters['id']}'),
              id: int.parse(s.pathParameters['id']!),
            ),
          ),

          GoRoute(
            path: '/disputes',
            builder: (c, s) => DisputesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('disputes'), defaultSort: 'createdAt,desc'),
            ),
          ),
          GoRoute(
            path: '/disputes/:id',
            builder: (c, s) => DisputeDetailScreen(
              key: ValueKey('dispute-${s.pathParameters['id']}'),
              id: int.parse(s.pathParameters['id']!),
            ),
          ),

          GoRoute(
            path: '/notifications',
            builder: (c, s) => NotificationsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('notifications'), defaultSort: 'createdAt,desc'),
            ),
          ),
          GoRoute(
            path: '/users',
            builder: (c, s) => UsersScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('users'), defaultSort: 'username,asc'),
            ),
          ),
          GoRoute(
            path: '/roles',
            builder: (c, s) => RolesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('roles'), defaultSort: 'name,asc'),
            ),
          ),
        ],
      ),
    ],
  );
});

/// Rebuilds the routes when sign-in changes, and again once the remembered page sizes have
/// loaded — they feed the defaults a route is built with.
class _RouterRefresh extends ChangeNotifier {
  _RouterRefresh(Ref ref) {
    ref.listen(authControllerProvider, (_, __) => notifyListeners());
    ref.listen(pageSizeStoreProvider, (_, __) => notifyListeners());
  }
}
