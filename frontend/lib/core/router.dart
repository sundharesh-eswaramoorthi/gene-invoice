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
import '../features/email/gmail_connection_screen.dart';
import '../features/email/inbox_screen.dart';
import '../features/invoices/invoice_detail_screen.dart';
import '../features/invoices/invoice_form_screen.dart';
import '../features/invoices/invoices_screen.dart';
import '../features/notifications/notifications_screen.dart';
import '../features/payments/payment_detail_screen.dart';
import '../features/payments/payments_screen.dart';
import '../features/products/product_detail_screen.dart';
import '../features/products/products_screen.dart';
import '../features/promises/promise_detail_screen.dart';
import '../features/automation/automation_rules_screen.dart';
import '../features/promises/promises_screen.dart';
import '../features/users/role_detail_screen.dart';
import '../features/tasks/task_detail_screen.dart';
import '../features/tasks/tasks_screen.dart';
import '../features/users/roles_screen.dart';
import '../features/users/user_detail_screen.dart';
import '../features/users/users_screen.dart';
import '../shared/models/privileges.dart';
import '../shared/widgets/app_shell.dart';
import '../shared/widgets/detail_scaffold.dart';
import 'table/route_query.dart';
import 'table/table_providers.dart';
import 'unsaved_changes.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final auth = ref.watch(authControllerProvider);

  /// The page size this user last chose for a table, or the default.
  int sizeFor(String entity) => ref.read(pageSizeStoreProvider)[entity] ?? 20;

  /// Leaving an editable detail screen asks about its unsaved edits first, however the user
  /// leaves (AC-C3). A sign-out is never held up: the session is already gone.
  Future<bool> mayLeave(BuildContext context, GoRouterState state) async =>
      ref.read(authControllerProvider).user == null ||
      await ref.read(unsavedChangesProvider).mayLeave();

  /// A screen a user may not open is one the URL cannot reach either. Holding none of
  /// [privileges], a hand-edited or stale link lands on the dashboard rather than on a list that
  /// offers "Add filter" and "Send email" above a 403, with a Retry that can only fail the same
  /// way (UI-10, AC-C22). Each list route is guarded by the very privileges its sidebar entry
  /// asks for, so the nav and the router can never disagree about who may open a screen.
  GoRouterRedirect needs(List<String> privileges) => (context, state) {
        final user = ref.read(authControllerProvider).user;
        // Signed out, the redirect above sends them to the login screen instead.
        if (user == null) return null;
        return privileges.isEmpty || user.hasAny(privileges) ? null : '/';
      };

  return GoRouter(
    initialLocation: '/',
    // An unknown path — a mistyped link, or an old notification's /admin/... link — gets the same
    // "not found" page as a missing record, with a way home.
    errorBuilder: (context, state) {
      final page = RecordUnavailable(
        message: 'That page does not exist.',
        onBack: () => context.go('/'),
      );
      // Signed in, this belongs inside the app — sidebar and top bar included — like every
      // other "does not exist" page (D-71).
      return auth.user == null
          ? Scaffold(body: page)
          : AppShell(path: state.uri.toString(), child: page);
    },
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
          GoRoute(
            path: '/inbox',
            redirect: needs(navPrivilegesFor('/inbox')),
            builder: (c, s) => InboxScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('inbox'), defaultSort: 'occurredAt,desc'),
            ),
          ),
          // The signed-in user's own Gmail, from the account menu (mail-service.md §6).
          GoRoute(path: '/me/gmail', builder: (c, s) => const GmailConnectionScreen()),

          // List pages open unfiltered for every role; a scope the server enforces comes back
          // with the page and is shown as a locked chip.
          GoRoute(
            path: '/customers',
            redirect: needs(navPrivilegesFor('/customers')),
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
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'customer',
                backTo: '/customers',
                build: (id) => CustomerDetailScreen(
                      key: ValueKey('customer-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          GoRoute(
            path: '/products',
            redirect: needs(navPrivilegesFor('/products')),
            builder: (c, s) => ProductsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('products'), defaultSort: 'name,asc'),
            ),
          ),
          GoRoute(
            path: '/products/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'product',
                backTo: '/products',
                build: (id) => ProductDetailScreen(
                      key: ValueKey('product-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          GoRoute(path: '/invoices/new', builder: (c, s) => const InvoiceFormScreen()),
          GoRoute(
            path: '/invoices',
            redirect: needs(navPrivilegesFor('/invoices')),
            builder: (c, s) => InvoicesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('invoices'), defaultSort: 'invoiceDate,desc'),
            ),
          ),
          GoRoute(
            path: '/invoices/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'invoice',
                backTo: '/invoices',
                build: (id) => InvoiceDetailScreen(
                      key: ValueKey('invoice-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          GoRoute(
            path: '/payments',
            redirect: needs(navPrivilegesFor('/payments')),
            builder: (c, s) => PaymentsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('payments'), defaultSort: 'paidAt,desc'),
            ),
          ),
          GoRoute(
            path: '/payments/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'payment',
                backTo: '/payments',
                build: (id) => PaymentDetailScreen(
                      key: ValueKey('payment-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          // /tasks/mine before /tasks/:id, exactly as /invoices/new is before /invoices/:id:
          // otherwise go_router reads "mine" as an id and the page says the task does not exist.
          GoRoute(
            path: '/tasks/mine',
            redirect: needs(navPrivilegesFor('/tasks')),
            builder: (c, s) => TasksScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('tasks'), defaultSort: 'dueDate,asc'),
              mine: true,
            ),
          ),
          GoRoute(
            path: '/tasks',
            redirect: needs(navPrivilegesFor('/tasks')),
            builder: (c, s) => TasksScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('tasks'), defaultSort: 'dueDate,asc'),
            ),
          ),
          GoRoute(
            path: '/tasks/:id',
            builder: (c, s) => pageForId(s,
                noun: 'task',
                backTo: '/tasks',
                build: (id) => TaskDetailScreen(
                      key: ValueKey('task-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          // The rules list is the whole feature: a rule is written in a dialog over it, so there
          // is no detail route to deep-link to.
          GoRoute(
            path: '/automation',
            redirect: needs(navPrivilegesFor('/automation')),
            builder: (c, s) => AutomationRulesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('automation'), defaultSort: 'id,desc'),
            ),
          ),

          GoRoute(
            path: '/promises',
            redirect: needs(navPrivilegesFor('/promises')),
            builder: (c, s) => PromisesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('promises'), defaultSort: 'promisedDate,desc'),
            ),
          ),
          // A promise notification or an email about a promise deep-links here (E15).
          GoRoute(
            path: '/promises/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'promise',
                backTo: '/promises',
                build: (id) => PromiseDetailScreen(
                      key: ValueKey('promise-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          GoRoute(
            path: '/disputes',
            redirect: needs(navPrivilegesFor('/disputes')),
            builder: (c, s) => DisputesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('disputes'), defaultSort: 'createdAt,desc'),
            ),
          ),
          GoRoute(
            path: '/disputes/:id',
            builder: (c, s) => pageForId(s,
                noun: 'dispute',
                backTo: '/disputes',
                build: (id) => DisputeDetailScreen(
                      key: ValueKey('dispute-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          // Not a sidebar entry — the bell leads here — but gated all the same.
          GoRoute(
            path: '/notifications',
            redirect: needs(const [Privileges.notificationView]),
            builder: (c, s) => NotificationsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('notifications'), defaultSort: 'createdAt,desc'),
            ),
          ),
          GoRoute(
            path: '/users',
            redirect: needs(navPrivilegesFor('/users')),
            builder: (c, s) => UsersScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('users'), defaultSort: 'username,asc'),
            ),
          ),
          GoRoute(
            path: '/users/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'user',
                backTo: '/users',
                build: (id) => UserDetailScreen(
                      key: ValueKey('user-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),
          GoRoute(
            path: '/roles',
            redirect: needs(navPrivilegesFor('/roles')),
            builder: (c, s) => RolesScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('roles'), defaultSort: 'name,asc'),
            ),
          ),
          GoRoute(
            path: '/roles/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'role',
                backTo: '/roles',
                build: (id) => RoleDetailScreen(
                      key: ValueKey('role-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),
        ],
      ),
    ],
  );
});

/// A detail page for the record id in the URL. An id that is not a number — a mistyped or cut-off
/// link — shows the same "not found" state as a record that does not exist, never an error box.
Widget pageForId(GoRouterState state,
    {required String noun, required String backTo, required Widget Function(int id) build}) {
  final id = int.tryParse(state.pathParameters['id'] ?? '');
  if (id == null) {
    return Builder(
      builder: (context) => RecordUnavailable(
        message: 'That $noun does not exist.',
        onBack: () => context.go(backTo),
      ),
    );
  }
  return build(id);
}

/// Rebuilds the routes when sign-in changes, and again once the remembered page sizes have
/// loaded — they feed the defaults a route is built with.
class _RouterRefresh extends ChangeNotifier {
  _RouterRefresh(Ref ref) {
    ref.listen(authControllerProvider, (_, __) => notifyListeners());
    ref.listen(pageSizeStoreProvider, (_, __) => notifyListeners());
  }
}
