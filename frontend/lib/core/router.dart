import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/auth/auth_controller.dart';
import '../features/approvals/approval_detail_screen.dart';
import '../features/automation/activity_screen.dart';
import '../features/automation/rule_detail_screen.dart';
import '../features/automation/rule_form_screen.dart';
import '../features/automation/rules_screen.dart';
import '../features/approvals/approvals_screen.dart';
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
import '../features/promises/promises_screen.dart';
import '../features/regions/regions_screen.dart';
import '../features/tasks/task_detail_screen.dart';
import '../features/tasks/tasks_screen.dart';
import '../features/users/role_detail_screen.dart';
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

  int sizeFor(String entity) => ref.read(pageSizeStoreProvider)[entity] ?? 20;

  Future<bool> mayLeave(BuildContext context, GoRouterState state) async =>
      ref.read(authControllerProvider).user == null ||
      await ref.read(unsavedChangesProvider).mayLeave();

  GoRouterRedirect needs(List<String> privileges) => (context, state) {
        final user = ref.read(authControllerProvider).user;
        if (user == null) return null;
        return privileges.isEmpty || user.hasAny(privileges) ? null : '/';
      };

  return GoRouter(
    initialLocation: '/',
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
          GoRoute(path: '/me/gmail', builder: (c, s) => const GmailConnectionScreen()),

          GoRoute(
            path: '/customers',
            redirect: needs(navPrivilegesFor('/customers')),
            builder: (c, s) => CustomersScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('customers'), defaultSort: 'name,asc'),
            ),
          ),
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

          // The queue, and one change's own page. Both sit behind APPROVAL_VIEW, the same
          // privilege the sidebar hides the entry on, so a hand-edited URL is refused too
          // (UI-10, B2).
          GoRoute(
            path: '/approvals',
            redirect: needs(navPrivilegesFor('/approvals')),
            builder: (c, s) => ApprovalsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('approvals'), defaultSort: 'requestedAt,desc'),
            ),
          ),
          GoRoute(
            path: '/approvals/:id',
            builder: (c, s) => pageForId(s,
                noun: 'change',
                backTo: '/approvals',
                build: (id) => ApprovalDetailScreen(
                      key: ValueKey('approval-$id'),
                      id: id,
                      initialTab: s.uri.queryParameters['tab'],
                    )),
          ),

          // The list, and one task's own page. Both sit behind TASK_VIEW, the same privilege
          // the sidebar hides the entry on, so a hand-edited URL is refused too (UI-10, A6).
          //
          // There is no separate "my work" route: the sidebar entry navigates to THIS one with
          // the filters that make the list mine already in the URL, which is why the seeded link
          // needs no screen, no endpoint and no state of its own (A6).
          GoRoute(
            path: '/tasks',
            redirect: needs(navPrivilegesFor('/tasks')),
            builder: (c, s) => TasksScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('tasks'), defaultSort: 'dueDate,asc'),
            ),
          ),
          // A TASK_ASSIGNED notification deep-links here (A6).
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

          // THE RULES, THE BUILDER, ONE RULE AND THE RUN HISTORY. All four sit behind
          // AUTOMATION_VIEW, the same privilege the sidebar hides the entry on, so a hand-edited
          // URL is refused too (UI-10, A1).
          //
          // /automation/rules/new is declared ABOVE /automation/rules/:id, the /invoices/new
          // precedent: go_router matches in declaration order and "new" would otherwise be read
          // as an id and answered with "That rule does not exist" (A1).
          GoRoute(
            path: '/automation/rules/new',
            redirect: needs(navPrivilegesFor('/automation/rules')),
            onExit: mayLeave,
            builder: (c, s) => const RuleFormScreen(),
          ),
          GoRoute(
            path: '/automation/rules',
            redirect: needs(navPrivilegesFor('/automation/rules')),
            builder: (c, s) => AutomationRulesScreen(
              query: RouteQuery.read(s,
                  defaultSize: sizeFor('automationRules'), defaultSort: 'name,asc'),
            ),
          ),
          // ?edit=true is the BUILDER on this rule, and it is a state of this page rather than a
          // fifth route: the URL is the state here as it is for ?tab=, and onExit guards both
          // (A1, AC-D4).
          GoRoute(
            path: '/automation/rules/:id',
            onExit: mayLeave,
            builder: (c, s) => pageForId(s,
                noun: 'rule',
                backTo: '/automation/rules',
                build: (id) => s.uri.queryParameters['edit'] == 'true'
                    ? RuleEditGate(key: ValueKey('rule-edit-$id'), id: id)
                    : RuleDetailScreen(
                        key: ValueKey('rule-$id'),
                        id: id,
                        initialTab: s.uri.queryParameters['tab'],
                      )),
          ),
          GoRoute(
            path: '/automation/activity',
            redirect: needs(navPrivilegesFor('/automation/rules')),
            builder: (c, s) => AutomationActivityPage(
              query: RouteQuery.read(s,
                  defaultSize: sizeFor('automationSteps'), defaultSort: 'id,desc'),
            ),
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
            path: '/regions',
            redirect: needs(navPrivilegesFor('/regions')),
            builder: (c, s) => RegionsScreen(
              query: RouteQuery.read(s, defaultSize: sizeFor('regions'), defaultSort: 'code,asc'),
            ),
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

class _RouterRefresh extends ChangeNotifier {
  _RouterRefresh(Ref ref) {
    ref.listen(authControllerProvider, (_, __) => notifyListeners());
    ref.listen(pageSizeStoreProvider, (_, __) => notifyListeners());
  }
}
