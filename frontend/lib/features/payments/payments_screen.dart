import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/payment.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../customer_scope/customer_scope.dart';
import '../customers/customers_screen.dart';
import '../disputes/dispute_create_dialog.dart';

final paymentsProvider = FutureProvider.autoDispose<List<PaymentRecord>>((ref) async {
  final dio = ref.watch(dioProvider);
  final scope = ref.watch(customerScopeProvider);
  final res = await dio.get('/api/payments', queryParameters: {
    if (scope != null) 'customerId': scope.id,
  });
  return (res.data as List).cast<Map<String, dynamic>>().map(PaymentRecord.fromJson).toList();
});

class PaymentsScreen extends ConsumerWidget {
  const PaymentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.paymentManage) ?? false;
    final canDispute = user?.has(Privileges.disputeCreate) ?? false;
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final async = ref.watch(paymentsProvider);
    final df = DateFormat.yMMMd().add_jm();

    return Scaffold(
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              icon: const Icon(Icons.add),
              label: const Text('Record payment'),
              onPressed: () => _record(context, ref),
            )
          : null,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) {
          if (list.isEmpty) return const Center(child: Text('No payments yet'));
          return RefreshIndicator(
            onRefresh: () async => ref.refresh(paymentsProvider.future),
            child: ListView.separated(
              padding: const EdgeInsets.all(8),
              itemCount: list.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final p = list[i];
                final voided = p.status == PaymentStatus.VOIDED;
                return ExpansionTile(
                  title: Row(
                    children: [
                      Expanded(child: Text('${p.customerName} • ${p.amount.toStringAsFixed(2)}')),
                      if (voided)
                        const Chip(
                          label: Text('VOIDED'),
                          padding: EdgeInsets.symmetric(horizontal: 2),
                        ),
                    ],
                  ),
                  subtitle: Text('${df.format(p.paidAt.toLocal())} • ${p.method ?? "—"}'),
                  trailing: p.creditApplied > 0
                      ? Chip(label: Text('Credit +${p.creditApplied.toStringAsFixed(2)}'))
                      : null,
                  children: [
                    if (p.invoices.isEmpty && !voided)
                      const Padding(
                        padding: EdgeInsets.all(12),
                        child: Text('No invoices linked (entire amount went to credit)'),
                      ),
                    ...p.invoices.map((inv) => ListTile(
                          dense: true,
                          title: Text(inv.invoiceNumber),
                          subtitle: Text('${statusLabel(inv.status)} • allocated ${inv.allocatedAmount.toStringAsFixed(2)}'),
                          trailing: Text('Total ${inv.total.toStringAsFixed(2)} • Bal ${inv.balance.toStringAsFixed(2)}'),
                        )),
                    if (canViewAudit) ...[
                      const Divider(height: 1),
                      Padding(
                        padding: const EdgeInsets.all(8),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('History', style: Theme.of(context).textTheme.titleSmall),
                            AuditHistoryPanel(entityType: 'PAYMENT', entityId: p.id),
                          ],
                        ),
                      ),
                    ],
                    if (canDispute && !voided)
                      Padding(
                        padding: const EdgeInsets.all(8),
                        child: Align(
                          alignment: Alignment.centerRight,
                          child: TextButton.icon(
                            icon: const Icon(Icons.flag_outlined),
                            label: const Text('Raise dispute'),
                            onPressed: () => showDisputeDialog(
                              context: context,
                              targetType: DisputeTargetType.PAYMENT,
                              targetId: p.id,
                              targetLabel: 'Payment #${p.id}',
                            ),
                          ),
                        ),
                      ),
                  ],
                );
              },
            ),
          );
        },
      ),
    );
  }

  Future<void> _record(BuildContext context, WidgetRef ref) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => const _RecordPaymentDialog(),
    );
    if (saved == true) {
      ref.invalidate(paymentsProvider);
    }
  }
}

class _RecordPaymentDialog extends ConsumerStatefulWidget {
  const _RecordPaymentDialog();
  @override
  ConsumerState<_RecordPaymentDialog> createState() => _RecordPaymentDialogState();
}

class _RecordPaymentDialogState extends ConsumerState<_RecordPaymentDialog> {
  Customer? _customer;
  final _amountCtrl = TextEditingController();
  final _methodCtrl = TextEditingController(text: 'Cash');
  final _notesCtrl = TextEditingController();
  final Set<int> _selectedInvoices = {};
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _customer = ref.read(customerScopeProvider);
  }

  @override
  void dispose() {
    _amountCtrl.dispose(); _methodCtrl.dispose(); _notesCtrl.dispose();
    super.dispose();
  }

  Future<List<InvoiceSummary>> _loadInvoices(int customerId) async {
    final dio = ref.read(dioProvider);
    final res = await dio.get('/api/invoices', queryParameters: {'customerId': customerId});
    return (res.data as List).cast<Map<String, dynamic>>()
        .map(InvoiceSummary.fromJson)
        .where((i) => i.status != InvoiceStatus.FULLY_PAID && i.status != InvoiceStatus.CANCELLED)
        .toList();
  }

  Future<void> _submit() async {
    if (_customer == null) {
      setState(() => _error = 'Pick a customer');
      return;
    }
    final amount = double.tryParse(_amountCtrl.text.trim());
    if (amount == null || amount <= 0) {
      setState(() => _error = 'Enter a positive amount');
      return;
    }
    setState(() { _saving = true; _error = null; });
    try {
      final dio = ref.read(dioProvider);
      final body = {
        'customerId': _customer!.id,
        'amount': amount,
        'method': _methodCtrl.text.trim(),
        'notes': _notesCtrl.text.trim(),
        if (_selectedInvoices.isNotEmpty) 'invoiceIds': _selectedInvoices.toList(),
      };
      await dio.post('/api/payments', data: body);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final customers = ref.watch(customersProvider);
    return Dialog(
      child: SizedBox(
        width: 520,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Record payment', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              customers.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Failed: $e'),
                data: (list) => DropdownButtonFormField<Customer>(
                  decoration: const InputDecoration(labelText: 'Customer'),
                  value: _customer,
                  items: list.map((c) => DropdownMenuItem(value: c, child: Text(c.name))).toList(),
                  onChanged: (c) => setState(() {
                    _customer = c;
                    _selectedInvoices.clear();
                  }),
                ),
              ),
              const SizedBox(height: 12),
              if (_customer != null)
                FutureBuilder<List<InvoiceSummary>>(
                  future: _loadInvoices(_customer!.id),
                  builder: (context, snap) {
                    if (snap.connectionState != ConnectionState.done) {
                      return const LinearProgressIndicator();
                    }
                    final invs = snap.data ?? [];
                    if (invs.isEmpty) {
                      return const Padding(
                        padding: EdgeInsets.symmetric(vertical: 6),
                        child: Text('No outstanding invoices (payment will go to customer credit)'),
                      );
                    }
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Outstanding invoices (optional — pick to limit; else applied oldest first)'),
                        ConstrainedBox(
                          constraints: const BoxConstraints(maxHeight: 200),
                          child: ListView(
                            shrinkWrap: true,
                            children: invs.map((inv) {
                              final checked = _selectedInvoices.contains(inv.id);
                              return CheckboxListTile(
                                dense: true,
                                value: checked,
                                title: Text('${inv.invoiceNumber} • Bal ${inv.balance.toStringAsFixed(2)}'),
                                subtitle: Text(statusLabel(inv.status)),
                                onChanged: (v) => setState(() {
                                  if (v == true) {
                                    _selectedInvoices.add(inv.id);
                                  } else {
                                    _selectedInvoices.remove(inv.id);
                                  }
                                }),
                              );
                            }).toList(),
                          ),
                        ),
                      ],
                    );
                  },
                ),
              const SizedBox(height: 12),
              TextField(
                controller: _amountCtrl,
                decoration: const InputDecoration(labelText: 'Amount'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _methodCtrl,
                decoration: const InputDecoration(labelText: 'Method (cash/card/transfer)'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _notesCtrl,
                decoration: const InputDecoration(labelText: 'Notes'),
              ),
              if (_error != null) Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(child: OutlinedButton(
                      onPressed: _saving ? null : () => Navigator.of(context).pop(false),
                      child: const Text('Cancel'))),
                  const SizedBox(width: 12),
                  Expanded(child: FilledButton(
                    onPressed: _saving ? null : _submit,
                    child: _saving
                        ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Record'),
                  )),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
