import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../auth/auth_controller.dart';
import '../poc/poc_providers.dart';
import '../poc/poc_picker.dart';
import 'promise_form_dialog.dart';
import 'promise_providers.dart';
import 'promises_tab.dart';

class PromisesScreen extends ConsumerWidget {
  final TableQuery query;
  const PromisesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.promiseManage) ?? false;
    final canOverride = user?.has(Privileges.promiseOverride) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);

    return Scaffold(
      body: DataTableScaffold<PaymentPromise>(
        entity: 'promises',
        path: '/api/promises',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/promises').push(q),
        parse: PaymentPromise.fromJson,
        idOf: (p) => p.id,
        canExport: canExport,
        selectable: canManage,
        emptyMessage: 'No promises match this filter',
        onRowTap: (context, p) => context.go('/customers/${p.customerId}?tab=promises'),
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Promises', value: '${s['total'] ?? 0}'),
            SummaryTile(
              label: 'Open',
              value: '${s['openCount'] ?? 0} • ${formatMoneyCompact(s['openAmount'])}',
              icon: Icons.schedule,
            ),
            SummaryTile(
              label: 'Kept',
              value: '${s['keptCount'] ?? 0} • ${formatMoneyCompact(s['keptAmount'])}',
              icon: Icons.check_circle_outline,
              accent: Colors.green.shade700,
            ),
            SummaryTile(
              label: 'Partially kept',
              value:
                  '${s['partiallyKeptCount'] ?? 0} • ${formatMoneyCompact(s['partiallyKeptAmount'])}',
              icon: Icons.timelapse,
              accent: Colors.orange.shade800,
            ),
            SummaryTile(
              label: 'Broken',
              value: '${s['brokenCount'] ?? 0} • ${formatMoneyCompact(s['brokenAmount'])}',
              icon: Icons.heart_broken_outlined,
              accent: Theme.of(context).colorScheme.error,
            ),
            SummaryTile(
                label: 'Promised total', value: formatMoneyCompact(s['promisedAmount'])),
          ],
        ),
        bulkActions: [
          if (canManage)
            const BulkActionSpec(
              action: 'CANCEL',
              label: 'Cancel promises',
              icon: Icons.cancel_outlined,
              destructive: true,
            ),
          if (canManage && canSeePoc)
            BulkActionSpec(
              action: 'REASSIGN_COLLECTION_POC',
              label: 'Reassign Collection POC',
              icon: Icons.person_search_outlined,
              buildParams: (context) => pickPocParams(context, PocType.COLLECTION),
            ),
        ],
        columns: [
          TableColumnSpec(
            label: 'Customer',
            sortKey: 'customerName',
            cell: (context, p) => Text(p.customerName),
          ),
          TableColumnSpec(
            label: 'Amount',
            sortKey: 'amount',
            numeric: true,
            cell: (context, p) => Text(formatMoney(p.amount)),
          ),
          TableColumnSpec(
            label: 'Promised by',
            sortKey: 'promisedDate',
            cell: (context, p) => Text(formatDate(p.promisedDate)),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, p) =>
                PromiseStatusChip(status: p.status, overridden: p.statusOverridden),
          ),
          TableColumnSpec(
            label: 'Fulfilled',
            sortKey: 'fulfilledAmount',
            numeric: true,
            cell: (context, p) => Text(formatMoney(p.fulfilledAmount)),
          ),
          TableColumnSpec(
            label: 'Remaining',
            sortKey: 'remainingAmount',
            numeric: true,
            cell: (context, p) => Text(formatMoney(p.remainingAmount)),
          ),
          if (canSeePoc)
            TableColumnSpec(
              label: 'Collection POC',
              sortKey: 'collectionPocName',
              cell: (context, p) => Text(p.collectionPoc?.display ?? '—'),
            ),
        ],
        rowActions: (context, p) => [
          IconButton(
            tooltip: 'Open customer',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/customers/${p.customerId}?tab=promises'),
          ),
          if (canOverride && p.isLive)
            IconButton(
              tooltip: 'Override status',
              icon: const Icon(Icons.rule, size: 18),
              onPressed: () async {
                final saved = await showOverrideDialog(context: context, promise: p);
                ref.invalidate(promiseDetailProvider(p.id));
                // The row's status and the tiles above the table both move with an override.
                if (saved == true) {
                  ref.invalidate(tablePageProvider);
                  ref.invalidate(tableSummaryProvider);
                }
              },
            ),
        ],
      ),
    );
  }
}

/// Shared "pick a user" step for the reassign-POC bulk action.
Future<Map<String, dynamic>?> pickPocParams(BuildContext context, PocType type) async {
  PocUser? picked;
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (_) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: Text('Reassign ${pocTypeLabel(type)}'),
        content: SizedBox(
          width: 420,
          child: PocPicker(
            type: type,
            value: picked,
            required: true,
            onChanged: (u) => setState(() => picked = u),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(
            onPressed: picked == null ? null : () => Navigator.of(context).pop(true),
            child: const Text('Continue'),
          ),
        ],
      ),
    ),
  );
  if (confirmed != true || picked == null) return null;
  return {'userId': picked!.id};
}

/// A "promise broken" notification links to /promises/{id}. The promise itself lives on its
/// customer's detail screen, so this resolves the customer and lands the user on the right
/// screen and tab (AC-C1, AC-B10).
class PromiseRedirectScreen extends ConsumerWidget {
  final int id;
  const PromiseRedirectScreen({super.key, required this.id});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(promiseDetailProvider(id));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.search_off, size: 44, color: Theme.of(context).colorScheme.outline),
              const SizedBox(height: 12),
              Text('That promise is not available: ${apiErrorMessage(e)}',
                  textAlign: TextAlign.center),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () => context.go('/promises'),
                child: const Text('All promises'),
              ),
            ],
          ),
        ),
      ),
      data: (promise) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (context.mounted) {
            context.go('/customers/${promise.customerId}?tab=promises');
          }
        });
        return const Center(child: CircularProgressIndicator());
      },
    );
  }
}
