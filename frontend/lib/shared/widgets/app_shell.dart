import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/unsaved_changes.dart';
import '../../features/auth/auth_controller.dart';
import '../../features/auth/change_password_dialog.dart';
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
  _NavEntry('Promises', Icons.handshake_outlined, '/promises', [Privileges.promiseView]),
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
    // On a phone the account label crowds out the title and pushes the actions over the menu
    // button, so only the icon shows there; the name and role head the account menu instead.
    final showAccountLabel = width >= 600;

    final isCustomer = user?.isCustomer ?? false;
    final visible = _entries.where((e) {
      if (isCustomer && e.hideForCustomer) return false;
      if (e.requiredAnyOf.isEmpty) return true;
      return user != null && user.hasAny(e.requiredAnyOf);
    }).toList();

    final currentPath = GoRouterState.of(context).matchedLocation;
    final selected = _selectedIndex(currentPath, visible);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Gene Invoice'),
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
              itemBuilder: (_) => [
                PopupMenuItem(
                  enabled: false,
                  child: ListTile(
                    leading: const Icon(Icons.account_circle_outlined),
                    title: Text(user.username),
                    subtitle: Text(user.role),
                  ),
                ),
                const PopupMenuDivider(),
                const PopupMenuItem(
                  value: 'change_password',
                  child: ListTile(
                    leading: Icon(Icons.key_outlined),
                    title: Text('Change password'),
                  ),
                ),
                const PopupMenuDivider(),
                const PopupMenuItem(
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
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.account_circle_outlined),
                    if (showAccountLabel) ...[
                      const SizedBox(width: 6),
                      ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 200),
                        child: Text('${user.username} • ${user.role}',
                            maxLines: 1, overflow: TextOverflow.ellipsis),
                      ),
                    ],
                    const Icon(Icons.arrow_drop_down),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
      drawer: isWide
          ? null
          : _DrawerNav(
              entries: visible,
              selectedIndex: selected,
              // The shell's context, which outlives the drawer that closes before navigating.
              onSelect: (path) => goGuarded(context, path),
            ),
      body: Row(
        children: [
          if (isWide)
            NavigationRail(
              extended: width > 1100,
              selectedIndex: selected,
              onDestinationSelected: (i) => goGuarded(context, visible[i].path),
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
            onPressed: () => goGuarded(context, '/notifications'),
          ),
          if (count > 0)
            Positioned(
              right: 6,
              top: 6,
              // The badge sits over the bell; taps on it must reach the button underneath.
              child: IgnorePointer(
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
            ),
        ],
      ),
    );
  }
}

class _DrawerNav extends StatelessWidget {
  final List<_NavEntry> entries;
  final int selectedIndex;
  final ValueChanged<String> onSelect;
  const _DrawerNav({required this.entries, required this.selectedIndex, required this.onSelect});

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
                onSelect(e.path);
              },
            );
          },
        ),
      ),
    );
  }
}
