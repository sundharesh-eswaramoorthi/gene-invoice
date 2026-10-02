import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/unsaved_changes.dart';
import '../../features/approvals/approval_providers.dart';
import '../../features/auth/auth_controller.dart';
import '../../features/auth/change_password_dialog.dart';
import '../../features/email/email_providers.dart';
import '../../features/notifications/notifications_providers.dart';
import '../../features/tasks/task_providers.dart';
import '../models/privileges.dart';
import 'as_of_banner.dart';
import 'as_of_chip.dart';
import 'region_selector.dart';

/// Which count, if any, a nav entry shows on its icon.
enum NavBadge { none, inbox, myTasks, approvals }

class NavEntry {
  final String label;
  final IconData icon;
  final String path;
  final List<String> requiredAnyOf;
  final bool hideForCustomer;

  final NavBadge badge;
  const NavEntry(this.label, this.icon, this.path, this.requiredAnyOf,
      {this.hideForCustomer = false, this.badge = NavBadge.none});
}

const navEntries = <NavEntry>[
  NavEntry('Dashboard', Icons.dashboard_outlined, '/', []),
  // Not hidden from customer logins: mail to their customer's addresses reaches them too (E13).
  NavEntry('Inbox', Icons.inbox_outlined, '/inbox', [Privileges.emailView], badge: NavBadge.inbox),
  NavEntry('Customers', Icons.people_outline, '/customers',
      [Privileges.customerView, Privileges.customerManage],
      hideForCustomer: true),
  NavEntry('Invoices', Icons.receipt_long_outlined, '/invoices',
      [Privileges.invoiceView, Privileges.invoiceManage]),
  NavEntry('Payments', Icons.payments_outlined, '/payments',
      [Privileges.paymentView, Privileges.paymentManage]),
  NavEntry('Promises', Icons.handshake_outlined, '/promises', [Privileges.promiseView]),
  NavEntry('Disputes', Icons.flag_outlined, '/disputes',
      [Privileges.disputeView, Privileges.disputeCreate, Privileges.disputeManage]),
  // Tasks on customers, invoices and payments (A6): mine, counted on the badge, then everyone's.
  // Neither path is a prefix of the other, so each lights up alone (G15).
  NavEntry('My tasks', Icons.assignment_ind_outlined, '/my-tasks',
      [Privileges.taskView, Privileges.taskManage],
      hideForCustomer: true, badge: NavBadge.myTasks),
  NavEntry('Tasks', Icons.checklist, '/tasks', [Privileges.taskView, Privileges.taskManage],
      hideForCustomer: true),
  // Changes held for a second person's approval (B2). Anyone who can see the records they touch
  // may see them; the badge counts those waiting on me.
  NavEntry('Approvals', Icons.fact_check_outlined, '/approvals', [
    Privileges.changeApprove,
    Privileges.invoiceView,
    Privileges.paymentView,
    Privileges.promiseView,
    Privileges.disputeView,
  ], hideForCustomer: true, badge: NavBadge.approvals),
  // Rules that act on customers, invoices and payments by themselves (A1-A5).
  NavEntry('Automation', Icons.bolt_outlined, '/automation',
      [Privileges.automationView, Privileges.automationManage],
      hideForCustomer: true),
  // Staff administration of the regions everything belongs to (B1).
  NavEntry('Regions', Icons.public_outlined, '/regions', [Privileges.regionView],
      hideForCustomer: true),
  NavEntry('Products', Icons.inventory_2_outlined, '/products',
      [Privileges.productView, Privileges.productManage],
      hideForCustomer: true),
  NavEntry('Users', Icons.manage_accounts_outlined, '/users',
      [Privileges.userView, Privileges.userManage]),
  NavEntry('Roles', Icons.admin_panel_settings_outlined, '/roles',
      [Privileges.roleView, Privileges.roleManage]),
];

/// What a user needs to hold for the screen at [path] — the sidebar's own rule, which the router
/// guards the same routes with, so a screen the nav hides cannot be reached by hand-editing the
/// URL either (UI-10). An empty list is a screen everybody signed in may open.
List<String> navPrivilegesFor(String path) {
  for (final e in navEntries) {
    if (e.path == path) return e.requiredAnyOf;
  }
  return const [];
}

class AppShell extends ConsumerWidget {
  final Widget child;

  /// Which route the sidebar should reflect. The shell normally reads this from the router, but a
  /// page built outside any matched route — the "does not exist" page (D-71) — has to pass it:
  /// GoRouterState.of throws there, which left that page blank.
  final String? path;

  const AppShell({super.key, required this.child, this.path});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final width = MediaQuery.sizeOf(context).width;
    final isWide = width >= 900;
    final showAccountLabel = width >= 600;

    final isCustomer = user?.isCustomer ?? false;
    final canConnectGmail = ref.watch(canConnectGmailProvider);
    final visible = navEntries.where((e) {
      if (isCustomer && e.hideForCustomer) return false;
      if (e.requiredAnyOf.isEmpty) return true;
      return user != null && user.hasAny(e.requiredAnyOf);
    }).toList();

    final currentPath = path ?? GoRouterState.of(context).matchedLocation;
    final selected = _selectedIndex(currentPath, visible);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Gene Invoice'),
        actions: [
          if (user != null) ...[
            // Which regions and which day every page answers for (B1, B3). Neither fetches while
            // the shell builds (G13).
            RegionSelector(compact: !showAccountLabel),
            if (!isCustomer) AsOfChip(compact: !showAccountLabel),
            if (user.has(Privileges.notificationView))
              const _NotificationsBell(),
            PopupMenuButton<String>(
              tooltip: 'Account',
              position: PopupMenuPosition.under,
              onSelected: (value) {
                switch (value) {
                  case 'gmail':
                    goGuarded(context, '/me/gmail');
                    break;
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
                if (canConnectGmail)
                  const PopupMenuItem(
                    value: 'gmail',
                    child: ListTile(
                      leading: Icon(Icons.mail_lock_outlined),
                      title: Text('Gmail connection'),
                    ),
                  ),
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
              onSelect: (path) => goGuarded(context, path),
            ),
      body: Row(
        children: [
          if (isWide)
            _HoverRail(
              entries: visible,
              selectedIndex: selected,
              onSelect: (path) => goGuarded(context, path),
            ),
          if (isWide) const VerticalDivider(width: 1),
          // The banner takes no room while live. The page keeps its place in the tree either way,
          // so moving to a past date and back does not rebuild it from scratch.
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const AsOfBanner(),
                // The page is a route of a nested Navigator, and its modal barrier blocks the
                // semantics of everything painted before it in the same container. A container of
                // its own keeps that block to the page, so the banner and the rail stay readable
                // by assistive technology (ASO-R1-1).
                Expanded(
                  child: Semantics(container: true, explicitChildNodes: true, child: child),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Null when the current route is not one of the sidebar entries — Notifications, or a
  /// customer's own customer page. Nothing should look selected then (D-62).
  int? _selectedIndex(String path, List<NavEntry> entries) {
    int? best;
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
      // The same Badge the rail's icons carry, rather than a pill of our own inside the button:
      // it hangs off the button's corner, so a three-figure count ("99+") no longer grows down
      // and across the bell until the control cannot be recognised (UI-06).
      child: Badge(
        isLabelVisible: count > 0,
        label: Text(count > 99 ? '99+' : '$count'),
        child: IconButton(
          tooltip: 'Notifications',
          icon: const Icon(Icons.notifications_outlined),
          onPressed: () => goGuarded(context, '/notifications'),
        ),
      ),
    );
  }
}

class _NavIcon extends ConsumerWidget {
  final NavEntry entry;
  const _NavIcon({required this.entry});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final icon = Icon(entry.icon);
    final counter = switch (entry.badge) {
      NavBadge.inbox => inboxUnreadCountProvider,
      NavBadge.approvals => approvalsAwaitingMeCountProvider,
      NavBadge.myTasks => myTasksCountProvider,
      NavBadge.none => null,
    };
    if (counter == null) return icon;
    final count = ref.watch(counter).maybeWhen(data: (c) => c, orElse: () => 0);
    return Badge(
      isLabelVisible: count > 0,
      label: Text(count > 99 ? '99+' : '$count'),
      child: icon,
    );
  }
}

class _HoverRail extends StatefulWidget {
  final List<NavEntry> entries;

  /// Null when the current route is not one of these entries; nothing looks selected then (D-62).
  final int? selectedIndex;
  final ValueChanged<String> onSelect;

  const _HoverRail({
    required this.entries,
    required this.selectedIndex,
    required this.onSelect,
  });

  @override
  State<_HoverRail> createState() => _HoverRailState();
}

class _HoverRailState extends State<_HoverRail> {
  bool _hovered = false;
  bool _pinned = false;

  @override
  Widget build(BuildContext context) {
    final expanded = _pinned || _hovered;
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: NavigationRail(
        extended: expanded,
        labelType: expanded ? null : NavigationRailLabelType.none,
        selectedIndex: widget.selectedIndex,
        onDestinationSelected: (i) => widget.onSelect(widget.entries[i].path),
        leading: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: IconButton(
            tooltip: _pinned ? 'Let the sidebar contract again' : 'Keep the sidebar open',
            icon: Icon(_pinned ? Icons.menu_open : Icons.menu),
            onPressed: () => setState(() => _pinned = !_pinned),
          ),
        ),
        destinations: widget.entries
            .map((e) => NavigationRailDestination(
                  icon: _NavIcon(entry: e),
                  label: Text(e.label),
                ))
            .toList(),
      ),
    );
  }
}

class _DrawerNav extends StatelessWidget {
  final List<NavEntry> entries;
  final int? selectedIndex;
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
              leading: _NavIcon(entry: e),
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
