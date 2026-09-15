import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';

class DisputesScreen extends ConsumerWidget {
  final TableQuery query;
  const DisputesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canExport = user?.has(Privileges.exportData) ?? false;

    return Scaffold(
      body: DataTableScaffold<Dispute>(
        entity: 'disputes',
        path: '/api/disputes',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/disputes').push(q),
        parse: Dispute.fromJson,
        idOf: (d) => d.id,
        canExport: canExport,
        emptyMessage: 'No disputes match this filter',
        onRowTap: (context, d) => context.go('/disputes/${d.id}'),
        columns: [
          TableColumnSpec(
            label: 'Target',
            sortKey: 'targetType',
            cell: (context, d) => Text(disputeTargetText(d)),
          ),
          TableColumnSpec(
            label: 'Customer',
            cell: (context, d) => Text(d.customerName ?? '—'),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, d) => Chip(label: Text(disputeStatusLabel(d.status))),
          ),
          TableColumnSpec(
            label: 'Opened',
            sortKey: 'createdAt',
            cell: (context, d) => Text(formatDateTime(d.createdAt)),
          ),
          TableColumnSpec(
            label: 'Reason',
            maxWidth: 360,
            cell: (context, d) => Tooltip(
              message: d.reason,
              child: Text(d.reason, maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ),
        ],
        rowActions: (context, d) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/disputes/${d.id}'),
          ),
        ],
      ),
    );
  }
}
