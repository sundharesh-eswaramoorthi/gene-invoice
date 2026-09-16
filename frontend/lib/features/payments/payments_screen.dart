import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/payment.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promises_screen.dart' show pickPocParams;
import 'record_payment_dialog.dart';

final paymentDetailProvider =
    FutureProvider.autoDispose.family<PaymentRecord, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/payments/$id');
  return PaymentRecord.fromJson(res.data as Map<String, dynamic>);
});

class PaymentsScreen extends ConsumerWidget {
  final TableQuery query;
  const PaymentsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.paymentManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);
    final canAssignPoc = ref.watch(canAssignPocProvider);

    return Scaffold(
      body: DataTableScaffold<PaymentRecord>(
        entity: 'payments',
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('Record payment'),
              onPressed: () async {
                final saved = await showRecordPaymentDialog(context: context);
                if (saved == true) {
                  ref.invalidate(tablePageProvider);
                  ref.invalidate(tableSummaryProvider);
                }
              },
            ),
        ],
        path: '/api/payments',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/payments').push(q),
        parse: PaymentRecord.fromJson,
        idOf: (p) => p.id,
        canExport: canExport,
        emptyMessage: 'No payments match this filter',
        onRowTap: (context, p) => context.go('/payments/${p.id}'),
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Payments', value: '${s['count'] ?? 0}'),
            SummaryTile(
              label: 'Total collected',
              value: formatMoneyCompact(s['totalCollected']),
              icon: Icons.payments_outlined,
              accent: Colors.green.shade700,
            ),
            SummaryTile(
                label: 'Credit applied', value: formatMoneyCompact(s['creditApplied'])),
            SummaryTile(label: 'Voided', value: '${s['voidedCount'] ?? 0}'),
            if (canSeePoc)
              SummaryTile(
                label: 'POC missing',
                value: '${s['pocMissingCount'] ?? 0}',
                icon: Icons.person_off_outlined,
              ),
          ],
        ),
        bulkActions: [
          // Voiding moves money, so it stays a one-at-a-time action through the dispute flow.
          // Bulk changes need PAYMENT_MANAGE as well as the right to assign POCs.
          if (canManage && canAssignPoc)
            BulkActionSpec(
              action: 'REASSIGN_COLLECTION_POC',
              label: 'Reassign Collection POC',
              icon: Icons.person_search_outlined,
              buildParams: (context) => pickPocParams(context, PocType.COLLECTION),
            ),
        ],
        columns: [
          TableColumnSpec(
            label: 'Payment',
            sortKey: 'id',
            cell: (context, p) => Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('#${p.id}', style: const TextStyle(fontWeight: FontWeight.w600)),
                if (canSeePoc && p.pocMissing)
                  const Padding(
                    padding: EdgeInsets.only(left: 6),
                    child: PocMissingBadge(),
                  ),
              ],
            ),
          ),
          TableColumnSpec(
              label: 'Customer',
              sortKey: 'customerName',
              cell: (context, p) => Text(p.customerName)),
          TableColumnSpec(
            label: 'Amount',
            sortKey: 'amount',
            numeric: true,
            cell: (context, p) => Text(formatMoney(p.amount)),
          ),
          TableColumnSpec(
            label: 'Credit applied',
            sortKey: 'creditApplied',
            numeric: true,
            cell: (context, p) => Text(formatMoney(p.creditApplied)),
          ),
          TableColumnSpec(
              label: 'Method', sortKey: 'method', cell: (context, p) => Text(p.method ?? '—')),
          TableColumnSpec(
              label: 'Paid at',
              sortKey: 'paidAt',
              cell: (context, p) => Text(formatDateTime(p.paidAt))),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, p) => PaymentStatusChip(status: p.status),
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
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/payments/${p.id}'),
          ),
        ],
      ),
    );
  }
}
