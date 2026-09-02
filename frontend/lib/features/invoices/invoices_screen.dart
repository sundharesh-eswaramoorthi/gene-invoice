import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../customer_scope/customer_scope.dart';
import '../disputes/dispute_create_dialog.dart';
import 'credit_note_dialogs.dart';

final invoicesProvider = FutureProvider.autoDispose<List<InvoiceSummary>>((ref) async {
  final dio = ref.watch(dioProvider);
  final scope = ref.watch(customerScopeProvider);
  final res = await dio.get('/api/invoices', queryParameters: {
    if (scope != null) 'customerId': scope.id,
  });
  return (res.data as List).cast<Map<String, dynamic>>().map(InvoiceSummary.fromJson).toList();
});

final invoiceDetailProvider =
    FutureProvider.autoDispose.family<InvoiceDetail, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices/$id');
  return InvoiceDetail.fromJson(res.data as Map<String, dynamic>);
});

class InvoicesScreen extends ConsumerWidget {
  const InvoicesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.invoiceManage) ?? false;
    final async = ref.watch(invoicesProvider);
    final df = DateFormat('yyyy-MM-dd');

    return Scaffold(
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              icon: const Icon(Icons.add),
              label: const Text('New invoice'),
              onPressed: () => context.go('/invoices/new'),
            )
          : null,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) {
          if (list.isEmpty) return const Center(child: Text('No invoices yet'));
          return RefreshIndicator(
            onRefresh: () async => ref.refresh(invoicesProvider.future),
            child: ListView.separated(
              padding: const EdgeInsets.all(8),
              itemCount: list.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final inv = list[i];
                return ListTile(
                  onTap: () => _openDetails(context, ref, inv.id),
                  title: Text('${inv.invoiceNumber} • ${inv.customerName}'),
                  subtitle: Text('${df.format(inv.invoiceDate.toLocal())} • ${statusLabel(inv.status)}'),
                  trailing: Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text('Total ${inv.total.toStringAsFixed(2)}',
                          style: const TextStyle(fontWeight: FontWeight.w600)),
                      if (inv.activeCreditedTotal > 0)
                        Text('Credited ${inv.activeCreditedTotal.toStringAsFixed(2)}'),
                      if (inv.outstanding > 0)
                        Text('Outstanding ${inv.outstanding.toStringAsFixed(2)}',
                            style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ],
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  void _openDetails(BuildContext context, WidgetRef ref, int id) {
    showDialog(
      context: context,
      builder: (_) => _InvoiceDetailDialog(id: id),
    );
  }
}

class _InvoiceDetailDialog extends ConsumerWidget {
  final int id;
  const _InvoiceDetailDialog({required this.id});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(invoiceDetailProvider(id));
    final user = ref.watch(currentUserProvider);
    final canDispute = user?.has(Privileges.disputeCreate) ?? false;
    final canViewAudit = user?.has(Privileges.auditView) ?? false;    final canManage = user?.has(Privileges.invoiceManage) ?? false;
    return Dialog(
      child: SizedBox(
        width: 640,
        child: async.when(
          loading: () => const Padding(padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Padding(padding: const EdgeInsets.all(20), child: Text('Failed: $e')),
          data: (inv) => SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(inv.invoiceNumber,
                          style: Theme.of(context).textTheme.titleLarge),
                    ),
                    Chip(label: Text(statusLabel(inv.status))),
                  ],
                ),
                const SizedBox(height: 4),
                Text(inv.customerName),
                Text(DateFormat.yMMMd().add_jm().format(inv.invoiceDate.toLocal())),
                const Divider(height: 24),
                ...inv.items.map((it) => ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(it.productName),
                      subtitle: Text('${it.quantity} × ${it.unitPrice.toStringAsFixed(2)}'),
                      trailing: Text(it.lineTotal.toStringAsFixed(2)),
                    )),
                const Divider(height: 24),
                _row('Total', inv.total),
                _row('Paid', inv.paidAmount),
                _row('Balance', inv.balance),
                if (inv.activeCreditedTotal > 0) _row('Credited', inv.activeCreditedTotal),
                _row('Outstanding', inv.outstanding, bold: true),
                if (inv.notes != null && inv.notes!.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Notes: ${inv.notes}', style: const TextStyle(fontStyle: FontStyle.italic)),
                ],
                if (canManage || inv.creditNotes.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: Text('Credit notes',
                            style: Theme.of(context).textTheme.titleSmall),
                      ),
                      if (canManage && inv.status != InvoiceStatus.CANCELLED)
                        TextButton.icon(
                          icon: const Icon(Icons.add_card_outlined),
                          label: const Text('Issue credit note'),
                          onPressed: () => _issueCreditNote(context, ref, inv),
                        ),
                    ],
                  ),
                  if (inv.creditNotes.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 4),
                      child: Text('No credit notes'),
                    ),
                  ...inv.creditNotes.map((cn) {
                    final voided = cn.isVoided;
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        '${cn.amount.toStringAsFixed(2)} • ${cn.reason}',
                        style: voided
                            ? const TextStyle(
                                decoration: TextDecoration.lineThrough,
                                color: Colors.grey,
                              )
                            : null,
                      ),
                      subtitle: Text(
                        '${creditNoteStatusLabel(cn.status)} • issued '
                        '${DateFormat.yMMMd().add_jm().format(cn.issuedAt.toLocal())}'
                        '${cn.issuedBy != null ? ' by ${cn.issuedBy}' : ''}',
                      ),
                      trailing: voided
                          ? const Chip(label: Text('VOIDED'))
                          : (canManage
                              ? IconButton(
                                  tooltip: 'Void credit note',
                                  icon: const Icon(Icons.remove_circle_outline),
                                  onPressed: () => _voidCreditNote(context, ref, inv, cn),
                                )
                              : null),
                    );
                  }),
                ],
                if (canViewAudit) ...[
                  const SizedBox(height: 16),
                  Text('History', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  AuditHistoryPanel(entityType: 'INVOICE', entityId: inv.id),
                ],
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    if (canDispute)
                      TextButton.icon(
                        icon: const Icon(Icons.flag_outlined),
                        label: const Text('Raise dispute'),
                        onPressed: () async {
                          final ok = await showDisputeDialog(
                            context: context,
                            targetType: DisputeTargetType.INVOICE,
                            targetId: inv.id,
                            targetLabel: inv.invoiceNumber,
                          );
                          if (ok == true && context.mounted) {
                            Navigator.of(context).pop();
                          }
                        },
                      ),
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: const Text('Close'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _issueCreditNote(
      BuildContext context, WidgetRef ref, InvoiceDetail inv) async {
    final result = await showCreditNoteIssueDialog(
      context: context,
      invoiceId: inv.id,
      maxCreditable: inv.outstanding,
    );
    if (result == null) return;
    // The note is committed before this point, so refresh the data even when
    // the backend reports an admin-notification warning.
    ref.invalidate(invoiceDetailProvider(inv.id));
    ref.invalidate(invoicesProvider);
    final warning = result.warning;
    if (warning != null && warning.isNotEmpty && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(warning)),
      );
    }
  }

  Future<void> _voidCreditNote(BuildContext context, WidgetRef ref,
      InvoiceDetail inv, CreditNote cn) async {
    final voided = await showCreditNoteVoidDialog(
      context: context,
      invoiceId: inv.id,
      creditNoteId: cn.id,
      amount: cn.amount,
    );
    if (voided == true) {
      ref.invalidate(invoiceDetailProvider(inv.id));
      ref.invalidate(invoicesProvider);
    }
  }

  Widget _row(String label, double value, {bool bold = false}) {
    final style = TextStyle(fontWeight: bold ? FontWeight.bold : FontWeight.normal);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value.toStringAsFixed(2), style: style),
        ],
      ),
    );
  }
}
