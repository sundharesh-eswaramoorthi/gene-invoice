import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';

/// What a status looks like on the queue. Waiting is the app's amber "accepted, not done", and
/// the three endings are the colours the rest of the app already uses for them (B2).
Color pendingChangeStatusColor(BuildContext context, PendingChangeStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    PendingChangeStatus.PENDING => scheme.tertiary,
    PendingChangeStatus.APPROVED => Colors.green.shade700,
    PendingChangeStatus.REJECTED => scheme.error,
    PendingChangeStatus.WITHDRAWN => scheme.outline,
    PendingChangeStatus.SUPERSEDED => scheme.outline,
    PendingChangeStatus.UNKNOWN => scheme.outline,
  };
}

class PendingChangeStatusChip extends StatelessWidget {
  final PendingChangeStatus status;
  const PendingChangeStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: pendingChangeStatusLabel(status),
        color: pendingChangeStatusColor(context, status),
      );
}

/// The queue. The generic table against the published `approvals` schema and nothing bespoke, so
/// the region narrowing, the locked chips, every filter operator, sorting, paging and the export
/// all come from the same funnel as /api/invoices (B2, B1).
class ApprovalsScreen extends ConsumerWidget {
  final TableQuery query;
  const ApprovalsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canExport = user?.has(Privileges.exportData) ?? false;

    return Scaffold(
      body: DataTableScaffold<PendingChange>(
        entity: 'approvals',
        path: '/api/approvals',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/approvals').push(q),
        parse: PendingChange.fromJson,
        idOf: (c) => c.id,
        canExport: canExport,
        emptyMessage: 'No changes match this filter',
        onRowTap: (context, c) => context.go('/approvals/${c.id}'),
        quickFilters: const [
          QuickFilterSpec(
            label: 'Waiting only',
            icon: Icons.hourglass_empty,
            filter: TableFilter('status', 'eq', ['PENDING']),
          ),
        ],
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Changes', value: '${s['count'] ?? 0}'),
            SummaryTile(
              label: 'Waiting',
              value: '${s['pendingCount'] ?? 0}',
              icon: Icons.hourglass_empty,
              accent: Theme.of(context).colorScheme.tertiary,
            ),
            // Never netted into any figure on any other screen: nothing waiting has taken
            // effect, so an amount mixing applied and unapplied money would be wrong (B2).
            SummaryTile(
                label: 'Money waiting', value: formatMoneyCompact(s['pendingExposure'])),
            SummaryTile(
              label: 'Waiting for me',
              value: '${s['awaitingMyDecisionCount'] ?? 0}',
              icon: Icons.how_to_reg_outlined,
              accent: Theme.of(context).colorScheme.tertiary,
            ),
            SummaryTile(label: 'Raised by me', value: '${s['mineCount'] ?? 0}'),
            SummaryTile(label: 'Approved', value: '${s['approvedCount'] ?? 0}'),
            SummaryTile(label: 'Rejected', value: '${s['rejectedCount'] ?? 0}'),
            SummaryTile(label: 'Withdrawn', value: '${s['withdrawnCount'] ?? 0}'),
            SummaryTile(label: 'Superseded', value: '${s['supersededCount'] ?? 0}'),
          ],
        ),
        columns: [
          TableColumnSpec(
            label: 'Change',
            sortKey: 'action',
            maxWidth: 200,
            cell: (context, c) => Text(pendingActionLabel(c.action),
                maxLines: 2, overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Record',
            sortKey: 'targetType',
            maxWidth: 140,
            cell: (context, c) => Text(
              c.targetId == null
                  ? 'New ${pendingTargetLabel(c.targetType).toLowerCase()}'
                  : '${pendingTargetLabel(c.targetType)} #${c.targetId}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          TableColumnSpec(
            label: 'Customer',
            sortKey: 'customerId',
            maxWidth: 160,
            cell: (context, c) => Tooltip(
              message: c.customerName ?? '',
              child: Text(c.customerName ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
            label: 'Summary',
            sortKey: 'summary',
            maxWidth: 280,
            cell: (context, c) => Tooltip(
              message: c.summary ?? '',
              child: Text(c.summary ?? '—', maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
            label: 'Amount',
            sortKey: 'exposure',
            numeric: true,
            cell: (context, c) => Text(c.exposure == null ? '—' : formatMoney(c.exposure)),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, c) => Wrap(
              spacing: 6,
              runSpacing: 2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                PendingChangeStatusChip(status: c.status),
                // Whose turn it is, worked out by the server per row: a maker is never their own
                // checker, so "waiting" and "waiting for you" are different facts (B2).
                if (c.decidable)
                  StatusChip(
                      label: 'You can decide',
                      color: Theme.of(context).colorScheme.primary),
              ],
            ),
          ),
          TableColumnSpec(
            label: 'Raised by',
            sortKey: 'requestedByUserId',
            maxWidth: 160,
            cell: (context, c) => Text(c.requestedByName ?? '—',
                maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Raised',
            sortKey: 'requestedAt',
            cell: (context, c) => Text(formatDateTime(c.requestedAt)),
          ),
        ],
        rowActions: (context, c) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/approvals/${c.id}'),
          ),
        ],
      ),
    );
  }
}
