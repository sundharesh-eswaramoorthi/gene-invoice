import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/user.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../customers/customers_screen.dart';
import '../email/email_actions.dart';
import 'users_screen.dart';

/// A user's account, read-only, with its emails and history below. Edits go through the same
/// form as the list, so there is nothing unsaved to guard here (E15).
class UserDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const UserDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(userDetailProvider(id));
    final viewer = ref.watch(currentUserProvider);
    final canEdit = viewer?.has(Privileges.userManage) ?? false;
    final canViewAudit =
        (viewer?.has(Privileges.auditView) ?? false) && (viewer?.has(Privileges.userView) ?? false);
    final canOpenCustomer = viewer?.has(Privileges.customerView) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'user'),
        onBack: () => context.go('/users'),
      ),
      data: (user) {
        final label = 'User ${user.username}';
        final send = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.user, entityId: user.id, entityLabel: label);
        final email = emailDetailTab(ref,
            type: EmailEntityType.user, entityId: user.id, entityLabel: label);
        return DetailScaffold(
          title: user.username,
          subtitle: user.fullName,
          onBack: () => goGuarded(context, '/users'),
          titleTrailing: [
            if (canEdit)
              OutlinedButton.icon(
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: () => openUserForm(context, ref, existing: user),
              ),
            if (send != null) send,
          ],
          initialTabSlug: initialTab,
          onTabChanged: (slug) => context.go('/users/$id?tab=$slug'),
          top: _top(user, canOpenCustomer: canOpenCustomer),
          tabs: [
            if (canViewAudit)
              DetailTab(
                slug: 'history',
                label: 'History',
                icon: Icons.history,
                builder: (context) => SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: AuditHistoryPanel(entityType: 'USER', entityId: user.id),
                ),
              ),
            if (email != null) email,
          ],
        );
      },
    );
  }

  Widget _top(AppUser u, {required bool canOpenCustomer}) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: DetailGrid(items: [
          DetailGridItem(label: 'Username', child: ReadOnlyValue(u.username)),
          DetailGridItem(label: 'Full name', child: ReadOnlyValue(u.fullName ?? '')),
          DetailGridItem(label: 'Email', child: ReadOnlyValue(u.email ?? '')),
          DetailGridItem(label: 'Role', child: ReadOnlyValue(u.role ?? '')),
          DetailGridItem(label: 'Active', child: ReadOnlyValue(u.active ? 'Yes' : 'No')),
          // Staff send email from their own Gmail; customer logins never connect one.
          if (u.customerId == null)
            DetailGridItem(label: 'Gmail', child: UserGmailStatus(userId: u.id)),
          if (u.customerId != null)
            DetailGridItem(
              label: 'Customer account',
              child: canOpenCustomer
                  ? _CustomerLink(customerId: u.customerId!)
                  : ReadOnlyValue('Customer #${u.customerId}'),
            ),
          if (u.createdAt != null)
            DetailGridItem(label: 'Created', child: ReadOnlyValue(formatDateTime(u.createdAt))),
        ]),
      );
}

/// The customer a customer login signs in to, by name once it has loaded. The user record carries
/// only the id, and a customer this viewer may not open still reads as its number.
class _CustomerLink extends ConsumerWidget {
  final int customerId;
  const _CustomerLink({required this.customerId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final name = ref.watch(customerDetailProvider(customerId)).valueOrNull?.name;
    return Align(
      alignment: Alignment.centerLeft,
      child: TextButton.icon(
        style: TextButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 4)),
        icon: const Icon(Icons.open_in_new, size: 16),
        label: Text(name ?? 'Customer #$customerId'),
        onPressed: () => context.go('/customers/$customerId'),
      ),
    );
  }
}
