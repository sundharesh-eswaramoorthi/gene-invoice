import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/credit_note.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../customer_scope/customer_scope.dart';
import '../disputes/dispute_create_dialog.dart';

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
final creditNotesProvider =
    FutureProvider.autoDispose.family<List<CreditNote>, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices/$id/credit-notes');
  return (res.data as List)
      .cast<Map<String, dynamic>>()
      .map(CreditNote.fromJson)
      .toList();
});
/// Shows the credit-note issue form for [invoiceId], then submits the
/// entered amount and reason through [issueCreditNote]. Returns the parsed
/// issue result - the committed note plus any warning - or null when the
/// dialog is cancelled. Server refusals propagate as DioExceptions so the
/// caller can surface the server's own message via apiErrorMessage.
Future<IssueCreditNoteResult?> showCreditNoteIssueDialog({
  required BuildContext context,
  required WidgetRef ref,
  required int invoiceId,
}) async {
  final input = await showDialog<_CreditNoteIssueInput>(
    context: context,
    builder: (_) => _CreditNoteIssueDialog(invoiceId: invoiceId),
  );
  if (input == null) return null;
  return issueCreditNote(ref, invoiceId, input.amount, input.reason);
}

/// Issues a credit note against [invoiceId] by posting the entered values
/// unchanged to the invoice-scoped issue endpoint and parsing the
/// note-plus-warning result. No clamping or trimming happens here: a
/// non-positive or over-ceiling amount and a blank reason are refused by the
/// server, and the refusal propagates carrying the server's message.
Future<IssueCreditNoteResult> issueCreditNote(
  WidgetRef ref,
  int invoiceId,
  double amount,
  String reason,
) async {
  final dio = ref.read(dioProvider);
  final res = await dio.post(
    '/api/invoices/$invoiceId/credit-notes',
    data: {'amount': amount, 'reason': reason},
  );
  return IssueCreditNoteResult.fromJson(res.data as Map<String, dynamic>);
}

/// Voids one credit note of [invoiceId] through the invoice-scoped void
/// endpoint and returns the voided note. Server refusals (for example a
/// repeated void of an already-voided note) propagate carrying the server's
/// message.
Future<CreditNote> voidCreditNote(
    WidgetRef ref, int invoiceId, int noteId) async {
  final dio = ref.read(dioProvider);
  final res =
      await dio.post('/api/invoices/$invoiceId/credit-notes/$noteId/void');
  return CreditNote.fromJson(res.data as Map<String, dynamic>);
}

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
                      if (inv.balance > 0)
                        Text('Bal ${inv.balance.toStringAsFixed(2)}',
                            style: TextStyle(color: Theme.of(context).colorScheme.error)),
                      if (inv.creditedAmount > 0)
                        Text('Credited ${inv.creditedAmount.toStringAsFixed(2)}'),
                      Text('Outstanding ${inv.outstandingAmount.toStringAsFixed(2)}'),
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
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final canManage = user?.has(Privileges.invoiceManage) ?? false;
    final creditNotesAsync = ref.watch(creditNotesProvider(id));
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
                _row('Balance', inv.balance, bold: true),
                _row('Credited', inv.creditedAmount),
                _row('Outstanding', inv.outstandingAmount, bold: true),
                if (inv.notes != null && inv.notes!.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Notes: ${inv.notes}', style: const TextStyle(fontStyle: FontStyle.italic)),
                ],
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: Text('Credit notes',
                          style: Theme.of(context).textTheme.titleSmall),
                    ),
                    if (canManage)
                      TextButton.icon(
                        icon: const Icon(Icons.add),
                        label: const Text('Issue credit note'),
                        onPressed: () => _issueCreditNote(context, ref),
                      ),
                  ],
                ),
                const SizedBox(height: 4),
                creditNotesAsync.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.all(12),
                    child: LinearProgressIndicator(),
                  ),
                  error: (e, _) => Text('Failed: $e'),
                  data: (notes) {
                    if (notes.isEmpty) {
                      return const Text('No credit notes yet.');
                    }
                    return Column(
                      children: notes.map((n) {
                        final voided = n.status == CreditNoteStatus.VOIDED;
                        return ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          title: Text(
                            n.reason,
                            style: voided
                                ? const TextStyle(
                                    decoration: TextDecoration.lineThrough)
                                : null,
                          ),
                          subtitle: Text(DateFormat.yMMMd()
                              .add_jm()
                              .format(n.issuedAt.toLocal())),
                          trailing: Wrap(
                            spacing: 8,
                            crossAxisAlignment: WrapCrossAlignment.center,
                            children: [
                              Text(n.amount.toStringAsFixed(2),
                                  style: const TextStyle(
                                      fontWeight: FontWeight.w600)),
                              if (voided) const Chip(label: Text('Voided')),
                              if (canManage && !voided)
                                IconButton(
                                  icon: const Icon(Icons.undo),
                                  tooltip: 'Void credit note',
                                  onPressed: () =>
                                      _voidCreditNote(context, ref, n),
                                ),
                            ],
                          ),
                        );
                      }).toList(),
                    );
                  },
                ),
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

  Future<void> _issueCreditNote(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final result = await showCreditNoteIssueDialog(
          context: context, ref: ref, invoiceId: id);
      if (result == null) return;
      _invalidateCreditState(ref);
      final warning = result.warning;
      if (warning != null && warning.isNotEmpty) {
        messenger.showSnackBar(SnackBar(
          content: Text('Credit note issued. Warning: $warning'),
        ));
      } else {
        messenger.showSnackBar(
            const SnackBar(content: Text('Credit note issued')));
      }
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Future<void> _voidCreditNote(
      BuildContext context, WidgetRef ref, CreditNote note) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await voidCreditNote(ref, id, note.id);
      _invalidateCreditState(ref);
      messenger.showSnackBar(
          const SnackBar(content: Text('Credit note voided')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  void _invalidateCreditState(WidgetRef ref) {
    ref.invalidate(invoicesProvider);
    ref.invalidate(invoiceDetailProvider(id));
    ref.invalidate(creditNotesProvider(id));
    ref.invalidate(auditHistoryProvider((entityType: 'INVOICE', entityId: id)));
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
/// Values entered in the credit-note issue form, passed on exactly as
/// entered (the amount is only parsed so it can be sent as a number; the
/// reason is sent untrimmed).
class _CreditNoteIssueInput {
  final double amount;
  final String reason;
  const _CreditNoteIssueInput({required this.amount, required this.reason});
}

/// Form collecting the credit-note amount and reason. It only collects:
/// validity of the amounts and the reason is the server's call, so the sole
/// client-side check is that the amount text parses as a number. Pops with a
/// [_CreditNoteIssueInput], or null when cancelled.
class _CreditNoteIssueDialog extends StatefulWidget {
  final int invoiceId;
  const _CreditNoteIssueDialog({required this.invoiceId});

  @override
  State<_CreditNoteIssueDialog> createState() => _CreditNoteIssueDialogState();
}

class _CreditNoteIssueDialogState extends State<_CreditNoteIssueDialog> {
  final _formKey = GlobalKey<FormState>();
  final _amountCtrl = TextEditingController();
  final _reasonCtrl = TextEditingController();

  @override
  void dispose() {
    _amountCtrl.dispose();
    _reasonCtrl.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.of(context).pop(_CreditNoteIssueInput(
      amount: double.parse(_amountCtrl.text),
      reason: _reasonCtrl.text,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Issue credit note'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _amountCtrl,
                decoration: const InputDecoration(labelText: 'Amount'),
                keyboardType:
                    const TextInputType.numberWithOptions(decimal: true),
                validator: (v) => double.tryParse(v ?? '') == null
                    ? 'Enter a valid amount'
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _reasonCtrl,
                decoration: const InputDecoration(labelText: 'Reason'),
                maxLines: 2,
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _submit,
          child: const Text('Issue'),
        ),
      ],
    );
  }
}
