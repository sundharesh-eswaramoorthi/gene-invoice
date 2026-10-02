import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'region_form_dialog.dart';
import 'regions_screen.dart';

/// A region's own page: what it is, who holds access in it, and its history. Edits go through
/// the same form as the list, so there is nothing unsaved to guard here.
class RegionDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const RegionDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(regionDetailProvider(id));
    final viewer = ref.watch(currentUserProvider);
    // A past date is read only (D2).
    final canManage =
        !ref.watch(isHistoricalProvider) && (viewer?.has(Privileges.regionManage) ?? false);
    // The grants list needs both: it names users (§6.3).
    final canSeeGrants = viewer?.has(Privileges.userView) ?? false;
    final canViewAudit = viewer?.has(Privileges.auditView) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'region'),
        onBack: () => context.go('/regions'),
      ),
      data: (region) {
        final canSeeThresholds = viewer != null &&
            (viewer.hasIn(Privileges.approvalThresholdManage, region.id) ||
                viewer.hasIn(Privileges.changeApprove, region.id));
        return DetailScaffold(
          title: region.code,
          subtitle: region.name,
          onBack: () => goGuarded(context, '/regions'),
          titleTrailing: [
            if (region.defaultRegion) const RegionDefaultBadge(),
            if (canSeeThresholds)
              TextButton.icon(
                icon: const Icon(Icons.rule_outlined, size: 18),
                label: const Text('Approval thresholds'),
                onPressed: () => goGuarded(context, '/approval-thresholds?regionId=${region.id}'),
              ),
            if (canManage)
              OutlinedButton.icon(
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: () => openRegionForm(context, ref, existing: region),
              ),
            if (canManage && !region.defaultRegion && region.active)
              OutlinedButton.icon(
                icon: const Icon(Icons.star_outline, size: 18),
                label: const Text('Make default'),
                onPressed: () => makeRegionDefault(context, ref, region),
              ),
            if (canManage && !region.defaultRegion)
              OutlinedButton.icon(
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('Delete'),
                style: OutlinedButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error),
                onPressed: () => _delete(context, ref, region),
              ),
          ],
          initialTabSlug: initialTab,
          onTabChanged: (slug) => context.go('/regions/$id?tab=$slug'),
          top: _top(region),
          tabs: [
            if (canSeeGrants)
              DetailTab(
                slug: 'grants',
                label: 'Grants',
                icon: Icons.key_outlined,
                builder: (context) => _GrantsTab(regionId: region.id),
              ),
            if (canViewAudit)
              DetailTab(
                slug: 'history',
                label: 'History',
                icon: Icons.history,
                builder: (context) => SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: AuditHistoryPanel(entityType: 'REGION', entityId: region.id),
                ),
              ),
          ],
        );
      },
    );
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, Region r) async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Delete ${r.code}?'),
        content: const Text(
            'A region that still has customers, disputes or grants cannot be deleted; deactivate '
            'it instead. This cannot be undone.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Delete region'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(dioProvider).delete('/api/regions/${r.id}');
      messenger.showSnackBar(SnackBar(content: Text('${r.code} deleted')));
      if (context.mounted) context.go('/regions');
      await regionsChanged(ref);
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Widget _top(Region r) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: DetailGrid(items: [
          DetailGridItem(label: 'Code', child: ReadOnlyValue(r.code)),
          DetailGridItem(label: 'Name', child: ReadOnlyValue(r.name)),
          DetailGridItem(label: 'Active', child: ReadOnlyValue(r.active ? 'Yes' : 'No')),
          DetailGridItem(label: 'Default', child: ReadOnlyValue(r.defaultRegion ? 'Yes' : 'No')),
          if (r.createdAt != null)
            DetailGridItem(label: 'Created', child: ReadOnlyValue(formatDateTime(r.createdAt))),
        ]),
      );
}

/// Who holds access in this region, at which level and over which records. Access is changed
/// on each user's page, where the editor is bounded by what the editor holds (§4.6).
class _GrantsTab extends ConsumerWidget {
  final int regionId;
  const _GrantsTab({required this.regionId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(regionGrantsProvider(regionId));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Text(apiErrorMessage(e), textAlign: TextAlign.center),
        ),
      ),
      data: (grants) {
        if (grants.isEmpty) {
          return const Center(child: Text('Nobody holds access in this region'));
        }
        return ListView.separated(
          padding: const EdgeInsets.all(12),
          itemCount: grants.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (context, i) {
            final g = grants[i];
            final name = (g.fullName ?? '').isEmpty ? g.username : g.fullName!;
            return ListTile(
              leading: CircleAvatar(child: Text(name.characters.first.toUpperCase())),
              title: Text(name),
              subtitle: Text('@${g.username}${g.roleName == null ? '' : ' • ${g.roleName}'}'),
              trailing: Text('${accessLevelLabel(g.level)} • ${recordScopeLabel(g.scope)}'),
              onTap: () => goGuarded(context, '/users/${g.userId}?tab=regions'),
            );
          },
        );
      },
    );
  }
}
