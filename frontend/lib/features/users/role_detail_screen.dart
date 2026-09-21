import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/user.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import 'roles_screen.dart';

/// A role and the privileges it grants, read-only, with its emails below. Roles keep no audit
/// history, so Email is the only tab (E15).
class RoleDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const RoleDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(roleDetailProvider(id));
    final canEdit = ref.watch(currentUserProvider)?.has(Privileges.roleManage) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'role'),
        onBack: () => context.go('/roles'),
      ),
      data: (role) {
        final label = 'Role ${role.name}';
        final send = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.role, entityId: role.id, entityLabel: label);
        final email = emailDetailTab(ref,
            type: EmailEntityType.role, entityId: role.id, entityLabel: label);
        return DetailScaffold(
          title: role.name,
          onBack: () => goGuarded(context, '/roles'),
          titleTrailing: [
            if (canEdit)
              OutlinedButton.icon(
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: () => openRoleForm(context, ref, existing: role),
              ),
            if (send != null) send,
          ],
          initialTabSlug: initialTab,
          onTabChanged: (slug) => context.go('/roles/$id?tab=$slug'),
          top: _top(role),
          tabs: [if (email != null) email],
        );
      },
    );
  }

  Widget _top(AppRole r) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: DetailGrid(items: [
          DetailGridItem(label: 'Name', child: ReadOnlyValue(r.name)),
          DetailGridItem(label: 'Description', span: 2, child: ReadOnlyValue(r.description ?? '')),
          DetailGridItem(
            label: 'Privileges (${r.privileges.length})',
            span: 3,
            child: r.privileges.isEmpty
                ? const ReadOnlyValue('')
                : Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final p in r.privileges)
                        Chip(label: Text(p), visualDensity: VisualDensity.compact),
                    ],
                  ),
          ),
        ]),
      );
}
