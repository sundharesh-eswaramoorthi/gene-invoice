import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/auth_controller.dart';
import '../../features/auth/change_password_dialog.dart';
import '../../features/customer_scope/customer_scope.dart';
import '../../features/notifications/notifications_providers.dart';
import '../models/privileges.dart';

class _NavEntry {
  final String label;
  final IconData icon;
  final String path;
  final List<String> requiredAnyOf;
  final bool hideForCustomer;
  const _NavEntry(this.label, this.icon, this.path, this.requiredAnyOf,
      {this.hideForCustomer = false});
}

const _entries = <_NavEntry>[
  _NavEntry('Dashboard', Icons.dashboard_outlined, '/', []),
  _NavEntry('Invoices', Icons.receipt_long_outlined, '/invoices',
      [Privileges.invoiceView, Privileges.invoiceManage]),
  _NavEntry('Payments', Icons.payments_outlined, '/payments',
      [Privileges.paymentView, Privileges.paymentManage]),
  _NavEntry('Disputes', Icons.flag_outlined, '/disputes',
      [Privileges.disputeView, Privileges.disputeCreate, Privileges.disputeManage]),
  _NavEntry('Customers', Icons.people_outline, '/customers',
      [Privileges.customerView, Privileges.customerManage],
      hideForCustomer: true),
  _NavEntry('Products', Icons.inventory_2_outlined, '/products',
      [Privileges.productView, Privileges.productManage],
      hideForCustomer: true),
  _NavEntry('Users', Icons.manage_accounts_outlined, '/users',
      [Privileges.userView, Privileges.userManage]),
  _NavEntry('Roles', Icons.admin_panel_settings_outlined, '/roles',
      [Privileges.roleView, Privileges.roleManage]),
];

class AppShell extends ConsumerWidget {
  final Widget child;
  const AppShell({super.key, required this.child});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final width = MediaQuery.sizeOf(context).width;
    final isWide = width >= 900;

    final isCustomer = user?.isCustomer ?? false;
    final visible = _entries.where((e) {
      if (isCustomer && e.hideForCustomer) return false;
      if (e.requiredAnyOf.isEmpty) return true;
      return user != null && user.hasAny(e.requiredAnyOf);
    }).toList();

    final currentPath = GoRouterState.of(context).matchedLocation;
    final selected = _selectedIndex(currentPath, visible);

    final showScopeBar = user != null && !isCustomer && user.has(Privileges.customerView);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Gene Invoice'),
        bottom: showScopeBar
            ? const PreferredSize(
                preferredSize: Size.fromHeight(56),
                child: _ScopeBar(),
              )
            : null,
        actions: [
          if (user != null) ...[
            if (user.has(Privileges.notificationView))
              const _NotificationsBell(),
            PopupMenuButton<String>(
              tooltip: 'Account',
              position: PopupMenuPosition.under,
              onSelected: (value) {
                switch (value) {
                  case 'change_password':
                    showChangePasswordDialog(context);
                    break;
                  case 'logout':
                    ref.read(authControllerProvider.notifier).logout();
                    break;
                }
              },
              itemBuilder: (_) => const [
                PopupMenuItem(
                  value: 'change_password',
                  child: ListTile(
                    leading: Icon(Icons.key_outlined),
                    title: Text('Change password'),
                  ),
                ),
                PopupMenuDivider(),
                PopupMenuItem(
                  value: 'logout',
                  child: ListTile(
                    leading: Icon(Icons.logout),
                    title: Text('Logout'),
                  ),
                ),
              ],
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Row(
                  children: [
                    const Icon(Icons.account_circle_outlined),
                    const SizedBox(width: 6),
                    Text('${user.username} • ${user.role}'),
                    const Icon(Icons.arrow_drop_down),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
      drawer: isWide ? null : _DrawerNav(entries: visible, selectedIndex: selected),
      body: Row(
        children: [
          if (isWide)
            NavigationRail(
              extended: width > 1100,
              selectedIndex: selected,
              onDestinationSelected: (i) => context.go(visible[i].path),
              destinations: visible
                  .map((e) => NavigationRailDestination(
                        icon: Icon(e.icon),
                        label: Text(e.label),
                      ))
                  .toList(),
            ),
          if (isWide) const VerticalDivider(width: 1),
          Expanded(child: child),
        ],
      ),
    );
  }

  int _selectedIndex(String path, List<_NavEntry> entries) {
    int best = 0;
    int bestLen = -1;
    for (var i = 0; i < entries.length; i++) {
      final p = entries[i].path;
      if (path == p || (p != '/' && path.startsWith(p))) {
        if (p.length > bestLen) {
          best = i;
          bestLen = p.length;
        }
      }
    }
    return best;
  }
}

class _ScopeBar extends StatelessWidget {
  const _ScopeBar();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      color: Theme.of(context).colorScheme.surface,
      child: const Align(
        alignment: Alignment.centerLeft,
        child: CustomerScopePicker(),
      ),
    );
  }
}

class _NotificationsBell extends ConsumerWidget {
  const _NotificationsBell();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(unreadCountProvider);
    final count = async.maybeWhen(data: (c) => c, orElse: () => 0);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Stack(
        alignment: Alignment.center,
        children: [
          IconButton(
            tooltip: 'Notifications',
            icon: const Icon(Icons.notifications_outlined),
            onPressed: () => context.go('/notifications'),
          ),
          if (count > 0)
            Positioned(
              right: 6,
              top: 6,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.error,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  count > 99 ? '99+' : '$count',
                  style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _DrawerNav extends StatelessWidget {
  final List<_NavEntry> entries;
  final int selectedIndex;
  const _DrawerNav({required this.entries, required this.selectedIndex});

  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: SafeArea(
        child: ListView.builder(
          itemCount: entries.length,
          itemBuilder: (context, i) {
            final e = entries[i];
            return ListTile(
              leading: Icon(e.icon),
              title: Text(e.label),
              selected: i == selectedIndex,
              onTap: () {
                Navigator.of(context).pop();
                context.go(e.path);
              },
            );
          },
        ),
      ),
    );
  }
}
