import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'approval_providers.dart';
import 'approvals_screen.dart';
import 'pending_approval_panel.dart';

/// One held change, on its own page — where the amber panel on a record links to, where a
/// notification about a change lands, and where the queue's rows open (B2).
class ApprovalDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const ApprovalDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(approvalDetailProvider(id));
    final user = ref.watch(currentUserProvider);
    final canViewAudit = user?.has(Privileges.auditView) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      // A change in a branch this person holds nothing in is a 404 and reads as one: naming an
      // id must not tell anybody that it exists (AUTH-08).
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'change'),
        onBack: () => context.go('/approvals'),
      ),
      data: (c) => DetailScaffold(
        title: 'Change #${c.id}',
        subtitle: c.summary,
        onBack: () => goGuarded(context, '/approvals'),
        titleTrailing: [PendingChangeStatusChip(status: c.status)],
        initialTabSlug: initialTab,
        onTabChanged: (slug) => context.go('/approvals/$id?tab=$slug'),
        top: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            PendingApprovalPanel(
              change: c,
              linkToRecord: true,
              onDecided: () => ref.invalidate(approvalDetailProvider(id)),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
              child: DetailGrid(items: [
                DetailGridItem(
                  label: 'Record',
                  child: ReadOnlyValue(c.targetId == null
                      ? 'A new ${pendingTargetLabel(c.targetType).toLowerCase()} that does not exist yet'
                      : '${pendingTargetLabel(c.targetType)} #${c.targetId}'),
                ),
                DetailGridItem(
                    label: 'Customer', child: ReadOnlyValue(c.customerName ?? '—')),
                DetailGridItem(label: 'Branch', child: ReadOnlyValue(c.regionName ?? '—')),
                DetailGridItem(
                  label: 'Amount',
                  child: ReadOnlyValue(c.exposure == null ? '—' : formatMoney(c.exposure)),
                ),
                DetailGridItem(
                  label: 'Limit applied',
                  child: ReadOnlyValue(c.alwaysChecked
                      ? 'Always checked, whatever the amount'
                      : (c.thresholdApplied == null ? '—' : formatMoney(c.thresholdApplied))),
                ),
                DetailGridItem(
                  label: 'Raised',
                  child: ReadOnlyValue(
                      '${c.requestedByName ?? 'the system'} • ${formatDateTime(c.requestedAt)}'),
                ),
                if (!c.isWaiting)
                  DetailGridItem(
                    label: 'Decided',
                    child: ReadOnlyValue(
                        '${c.decidedByName ?? '—'} • ${formatDateTime(c.decidedAt)}'),
                  ),
                // The one place a bulk run is visible as a run. Everything raised by one click
                // carries the same id and can be decided in one act (B2).
                if (c.batchId != null)
                  DetailGridItem(
                      label: 'Raised in a bulk run', child: ReadOnlyValue(c.batchId!)),
              ]),
            ),
          ],
        ),
        tabs: [
          // auditEntityType and not targetId: GET /api/audit answers 400 for a type outside its
          // SUPPORTED set, so a dispute change and a branch-limit change get the About tab
          // rather than a timeline that cannot load (B2).
          if (canViewAudit && c.auditEntityType != null)
            DetailTab(
              slug: 'history',
              label: '${pendingTargetLabel(c.targetType)} history',
              icon: Icons.history,
              builder: (context) => SingleChildScrollView(
                padding: const EdgeInsets.all(12),
                child: AuditHistoryPanel(
                    entityType: c.auditEntityType!, entityId: c.targetId!),
              ),
            )
          else
            // DetailScaffold is built around tabs and cannot render none, so a change with no
            // record yet — a create, or a branch limit — still has one, and it says why.
            DetailTab(
              slug: 'about',
              label: 'About',
              icon: Icons.info_outline,
              builder: (context) => Padding(
                padding: const EdgeInsets.all(16),
                child: Text(c.targetId == null
                    ? 'There is no history to show: this change would create a record that does '
                        'not exist yet.'
                    : 'There is no history to show for a '
                        '${pendingTargetLabel(c.targetType).toLowerCase()}.'),
              ),
            ),
        ],
      ),
    );
  }
}
