import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'approval_models.dart';
import 'approval_thresholds_screen.dart';

/// Which changes the list is about (§6.11 `view`).
enum ApprovalsView { AWAITING_ME, MINE, ALL }

String approvalsViewLabel(ApprovalsView v) => switch (v) {
      ApprovalsView.AWAITING_ME => 'Awaiting my approval',
      ApprovalsView.MINE => 'My requests',
      ApprovalsView.ALL => 'All I can see',
    };

/// The view named in the list's URL, or null for none (or one this build does not know).
ApprovalsView? approvalsViewNamed(String? name) =>
    ApprovalsView.values.where((v) => v.name == name).firstOrNull;

/// The view last chosen, so a trip to a change and back — whose Back link carries no query —
/// returns to it. It starts over for each person who signs in.
final lastApprovalsViewProvider = StateProvider<ApprovalsView?>((ref) {
  ref.watch(currentUserProvider.select((u) => u?.id));
  return null;
});

/// Changes held for approval (B2): those waiting on me, those I asked for, or every one I can
/// see. The choice travels as one scalar parameter, never a collection (G1), and lives in the URL
/// beside the page, so choosing another view starts again at its first page.
class ApprovalsScreen extends ConsumerWidget {
  final TableQuery query;

  /// The view the URL names; without one, the last chosen or the default.
  final ApprovalsView? view;
  const ApprovalsScreen({super.key, required this.query, this.view});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final approves = user?.has(Privileges.changeApprove) ?? false;
    // Someone who cannot approve anything has nothing awaiting them; their own requests first.
    final fallback = approves ? ApprovalsView.AWAITING_ME : ApprovalsView.MINE;
    // Read, not watched: a choice goes into the URL, and re-listing this page for it first would
    // ask for the old page number in the new view.
    final chosen = this.view ?? ref.read(lastApprovalsViewProvider);
    final view = chosen == null || (chosen == ApprovalsView.AWAITING_ME && !approves)
        ? fallback
        : chosen;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSeeLimits = thresholdRegionsFor(user).isNotEmpty;
    final historical = ref.watch(isHistoricalProvider);

    void push(TableQuery q, ApprovalsView v) =>
        RouteQuery(context, '/approvals').push(q, keep: {'view': v.name});

    return Scaffold(
      body: DataTableScaffold<PendingChangeBrief>(
        entity: 'approvals',
        path: '/api/approvals',
        query: query,
        extraParams: {'view': view.name},
        onQueryChanged: (q) => push(q, view),
        parse: PendingChangeBrief.fromJson,
        idOf: (p) => p.id,
        canExport: canExport,
        emptyMessage: switch (view) {
          ApprovalsView.AWAITING_ME => 'Nothing is waiting for your approval',
          ApprovalsView.MINE => 'You have no changes matching this filter',
          ApprovalsView.ALL => 'No changes match this filter',
        },
        onRowTap: (context, p) => context.go('/approvals/${p.id}'),
        header: Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
          child: Wrap(
            spacing: 12,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: SegmentedButton<ApprovalsView>(
                  key: const ValueKey('approvals-view'),
                  showSelectedIcon: false,
                  segments: [
                    for (final v in ApprovalsView.values)
                      if (v != ApprovalsView.AWAITING_ME || approves)
                        ButtonSegment(value: v, label: Text(approvalsViewLabel(v))),
                  ],
                  selected: {view},
                  onSelectionChanged: (s) {
                    ref.read(lastApprovalsViewProvider.notifier).state = s.first;
                    // Another view is another list: its first page, as a new sort or filter is.
                    push(query.copyWith(page: 0), s.first);
                  },
                ),
              ),
              // The limits themselves, for whoever approves or manages them somewhere — the
              // way in that does not go through a region's page (which needs REGION_VIEW).
              if (canSeeLimits)
                TextButton.icon(
                  key: const ValueKey('approvals-thresholds'),
                  icon: const Icon(Icons.tune, size: 18),
                  label: const Text('Approval thresholds'),
                  onPressed: () => context.go('/approval-thresholds'),
                ),
            ],
          ),
        ),
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Awaiting approval', value: '${s['pendingCount'] ?? 0}'),
            SummaryTile(label: 'Amount waiting', value: formatMoneyCompact(s['pendingAmount'])),
            if (approves)
              SummaryTile(
                label: 'Awaiting me',
                value: '${s['awaitingMeCount'] ?? 0}',
                icon: Icons.how_to_reg_outlined,
                accent: Colors.orange.shade800,
              ),
            SummaryTile(label: 'My requests', value: '${s['mineCount'] ?? 0}'),
          ],
        ),
        columns: [
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, p) => ApprovalStatusCell(change: p, historical: historical),
          ),
          TableColumnSpec(
            label: 'Change',
            sortKey: 'operation',
            maxWidth: 320,
            cell: (context, p) => Tooltip(
              message: p.summary,
              child: Text(p.summary.isEmpty ? (p.operationLabel ?? '—') : p.summary,
                  maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
            label: 'Record',
            sortKey: 'entityLabel',
            maxWidth: 180,
            cell: (context, p) => Text(p.recordText, maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Customer',
            sortKey: 'customerName',
            maxWidth: 160,
            cell: (context, p) => Tooltip(
              message: p.customerName ?? '',
              child: Text(p.customerName ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
          ),
          if (user?.multiRegion ?? false)
            TableColumnSpec(
              label: 'Region',
              sortKey: 'regionId',
              maxWidth: 140,
              cell: (context, p) => Text(p.regionName ?? '—'),
            ),
          TableColumnSpec(
            label: 'Amount',
            sortKey: 'amount',
            numeric: true,
            cell: (context, p) => Text(p.amount == null ? '—' : formatMoney(p.amount)),
          ),
          TableColumnSpec(
            label: 'Limit',
            sortKey: 'threshold',
            numeric: true,
            cell: (context, p) => Text(p.threshold == null ? '—' : formatMoney(p.threshold)),
          ),
          TableColumnSpec(
            label: 'Requested by',
            maxWidth: 160,
            cell: (context, p) =>
                Text(p.requestedBy?.name ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Requested',
            sortKey: 'requestedAt',
            cell: (context, p) => Text(formatDateTime(p.requestedAt)),
          ),
        ],
        rowActions: (context, p) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/approvals/${p.id}'),
          ),
        ],
      ),
    );
  }
}

/// A change's status in the list. On a past date it is the status then (`statusAt`, B3), with
/// where it stands today beside it when that differs.
class ApprovalStatusCell extends StatelessWidget {
  final PendingChangeBrief change;
  final bool historical;
  const ApprovalStatusCell({super.key, required this.change, required this.historical});

  @override
  Widget build(BuildContext context) {
    final then = historical ? change.statusAt : null;
    if (then == null) return ApprovalStatusChip(status: change.status);
    return Wrap(
      spacing: 6,
      runSpacing: 2,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        ApprovalStatusChip(status: then),
        if (then != change.status)
          Text('now ${pendingStatusLabel(change.status)}',
              style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}
