import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promise_form_dialog.dart';
import '../promises/promises_screen.dart' show pickPocParams;

final invoiceDetailProvider =
    FutureProvider.autoDispose.family<InvoiceDetail, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices/$id');
  return InvoiceDetail.fromJson(res.data as Map<String, dynamic>);
});

class InvoicesScreen extends ConsumerWidget {
  final TableQuery query;
  const InvoicesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.invoiceManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canPromise = user?.has(Privileges.promiseManage) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);
    final canAssignPoc = ref.watch(canAssignPocProvider);

    return Scaffold(
      body: DataTableScaffold<InvoiceSummary>(
        entity: 'invoices',
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New invoice'),
              onPressed: () => context.go('/invoices/new'),
            ),
        ],
        path: '/api/invoices',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/invoices').push(q),
        parse: InvoiceSummary.fromJson,
        idOf: (i) => i.id,
        canExport: canExport,
        emptyMessage: 'No invoices match this filter',
        onRowTap: (context, inv) => context.go('/invoices/${inv.id}'),
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Invoices', value: '${s['count'] ?? 0}'),
            SummaryTile(label: 'Total billed', value: formatMoneyCompact(s['totalBilled'])),
            SummaryTile(
              label: 'Outstanding',
              value: formatMoneyCompact(s['outstanding']),
              icon: Icons.account_balance_wallet_outlined,
              accent: Theme.of(context).colorScheme.error,
            ),
            SummaryTile(label: 'Unpaid', value: '${s['unpaidCount'] ?? 0}'),
            SummaryTile(label: 'Partially paid', value: '${s['partiallyPaidCount'] ?? 0}'),
            if (canSeePoc)
              SummaryTile(
                label: 'POC missing',
                value: '${s['pocMissingCount'] ?? 0}',
                icon: Icons.person_off_outlined,
              ),
          ],
        ),
        bulkActions: [
          if (canManage)
            const BulkActionSpec(
              action: 'CANCEL',
              label: 'Cancel unpaid',
              icon: Icons.block,
              destructive: true,
            ),
          // Bulk changes need INVOICE_MANAGE as well as the right to assign POCs.
          if (canManage && canAssignPoc)
            BulkActionSpec(
              action: 'REASSIGN_SALES_POC',
              label: 'Reassign Sales POC',
              icon: Icons.person_search_outlined,
              buildParams: (context) => pickPocParams(context, PocType.SALES),
            ),
        ],
        columns: [
          TableColumnSpec(
            label: 'Invoice #',
            sortKey: 'invoiceNumber',
            cell: (context, inv) => Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(inv.invoiceNumber,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                if (canSeePoc && inv.pocMissing)
                  const Padding(
                    padding: EdgeInsets.only(left: 6),
                    child: PocMissingBadge(label: 'POC missing'),
                  ),
              ],
            ),
          ),
          TableColumnSpec(
            label: 'Customer',
            sortKey: 'customerName',
            cell: (context, inv) => Text(inv.customerName),
          ),
          TableColumnSpec(
            label: 'Date',
            sortKey: 'invoiceDate',
            cell: (context, inv) => Text(formatDate(inv.invoiceDate)),
          ),
          TableColumnSpec(
            label: 'Total',
            sortKey: 'total',
            numeric: true,
            cell: (context, inv) => Text(formatMoney(inv.total)),
          ),
          TableColumnSpec(
            label: 'Paid',
            sortKey: 'paidAmount',
            numeric: true,
            cell: (context, inv) => Text(formatMoney(inv.paidAmount)),
          ),
          TableColumnSpec(
            label: 'Balance',
            sortKey: 'balance',
            numeric: true,
            cell: (context, inv) => Text(
              formatMoney(inv.balance),
              style: TextStyle(
                color: inv.balance > 0 ? Theme.of(context).colorScheme.error : null,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, inv) => InvoiceStatusChip(status: inv.status),
          ),
          if (canSeePoc)
            TableColumnSpec(
              label: 'Sales POC',
              sortKey: 'salesPocName',
              cell: (context, inv) => Text(inv.salesPoc?.display ?? '—'),
            ),
        ],
        rowActions: (context, inv) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/invoices/${inv.id}'),
          ),
          // A cancelled invoice owes nothing, whatever balance it last showed, and the backend
          // refuses a promise on it (D-49).
          if (canPromise && inv.balance > 0 && inv.status != InvoiceStatus.CANCELLED)
            IconButton(
              tooltip: 'Raise promise',
              icon: const Icon(Icons.handshake_outlined, size: 18),
              onPressed: () => showPromiseDialog(
                context: context,
                customerId: inv.customerId,
                customerName: inv.customerName,
                preselectedInvoiceIds: [inv.id],
              ),
            ),
          if (canManage && inv.status == InvoiceStatus.UNPAID)
            IconButton(
              tooltip: 'Cancel invoice',
              icon: const Icon(Icons.block, size: 18),
              onPressed: () => _cancel(context, ref, inv),
            ),
        ],
      ),
    );
  }

  Future<void> _cancel(BuildContext context, WidgetRef ref, InvoiceSummary inv) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Cancel ${inv.invoiceNumber}?'),
        content: const Text('This cannot be undone.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Cancel invoice'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(dioProvider).post('/api/invoices/${inv.id}/cancel');
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }
}
