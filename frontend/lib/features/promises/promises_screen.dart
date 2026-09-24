import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../approvals/pending_approval_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import '../poc/poc_providers.dart';
import '../poc/poc_name_cell.dart';
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
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);
    final canSendEmail = ref.watch(canSendEmailProvider);
    final sendEmail = sendEmailPageAction(context, ref, type: EmailEntityType.promise);

    return Scaffold(
      body: DataTableScaffold<PaymentPromise>(
        entity: 'promises',
        path: '/api/promises',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/promises').push(q),
        parse: PaymentPromise.fromJson,
        idOf: (p) => p.id,
        canExport: canExport,
        emptyMessage: 'No promises match this filter',
        onRowTap: (context, p) => context.go('/promises/${p.id}'),
        actions: [if (sendEmail != null) sendEmail],
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
          if (canSendEmail) sendEmailBulkAction(EmailEntityType.promise),
        ],
        columns: [
          TableColumnSpec(
            label: 'Customer',
            sortKey: 'customerName',
            maxWidth: 240,
            cell: (context, p) => Tooltip(
              message: p.customerName,
              child: Text(p.customerName, maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
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
            cell: (context, p) => Wrap(
              spacing: 6,
              runSpacing: 2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                PromiseStatusChip(status: p.status, overridden: p.statusOverridden),
                if (p.approvalPending) const ApprovalPendingDot(),
              ],
            ),
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
              maxWidth: 180,
              cell: (context, p) => PocNameCell(user: p.collectionPoc),
            ),
        ],
        rowActions: (context, p) => [
          IconButton(
            tooltip: 'Open customer',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/customers/${p.customerId}?tab=promises'),
          ),
          sendEmailRowAction(context,
              type: EmailEntityType.promise,
              entityId: p.id,
              entityLabel: 'Promise #${p.id}',
              regionId: p.regionId),
          // hasIn, not has: overriding is a write on this promise's account, in its branch (B1).
          if ((user?.hasIn(Privileges.promiseOverride, p.regionId) ?? false) && p.isLive)
            IconButton(
              tooltip: 'Override status',
              icon: const Icon(Icons.rule, size: 18),
              onPressed: () async {
                final saved = await showOverrideDialog(context: context, promise: p);
                ref.invalidate(promiseDetailProvider(p.id));
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

Future<Map<String, dynamic>?> pickPocParams(BuildContext context, PocType type) async {
  PocUser? picked;
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (_) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: Text('Reassign ${pocTypeLabel(type)}'),
        content: SizedBox(
          width: 420,
          // No customerId: a bulk reassignment runs over a selection that can span as many
          // branches as the filter does, so there is no single account to name. The picker
          // therefore offers everybody assignable in any branch the caller works in, and the
          // server refuses the rows where this person does not work (B1).
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
