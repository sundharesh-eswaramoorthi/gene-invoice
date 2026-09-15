import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../customers/customers_screen.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promise_providers.dart';

Future<bool?> showRecordPaymentDialog({
  required BuildContext context,
  Customer? customer,
}) {
  return showDialog<bool>(
    context: context,
    builder: (_) => _RecordPaymentDialog(initialCustomer: customer),
  );
}

class _RecordPaymentDialog extends ConsumerStatefulWidget {
  final Customer? initialCustomer;
  const _RecordPaymentDialog({this.initialCustomer});

  @override
  ConsumerState<_RecordPaymentDialog> createState() => _RecordPaymentDialogState();
}

class _RecordPaymentDialogState extends ConsumerState<_RecordPaymentDialog> {
  Customer? _customer;
  final _amountCtrl = TextEditingController();
  final _methodCtrl = TextEditingController(text: 'Cash');
  final _notesCtrl = TextEditingController();
  final Set<int> _selectedInvoices = {};
  final Set<int> _selectedPromises = {};
  PocUser? _collectionPoc;
  int? _pocResolvedFor;

  /// True once the cashier picks a POC themselves; the customer's default then never replaces it.
  bool _pocChosen = false;
  bool _saving = false;
  bool _submitted = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _customer = widget.initialCustomer;
  }

  @override
  void dispose() {
    _amountCtrl.dispose();
    _methodCtrl.dispose();
    _notesCtrl.dispose();
    super.dispose();
  }

  /// Pre-fills with the customer's primary Collection POC, still editable (AC-A4/US-A4). A POC the
  /// cashier already picked is kept, and a deactivated seat holder is never the default.
  void _resolveDefaultPoc(int customerId, List<CustomerPoc> pocs) {
    if (_pocResolvedFor == customerId) return;
    _pocResolvedFor = customerId;
    final active = pocs.where((p) => p.pocType == PocType.COLLECTION && p.user.active);
    final seat = active.where((p) => p.primary).firstOrNull ?? active.firstOrNull;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_pocChosen) setState(() => _collectionPoc = seat?.user);
    });
  }

  Future<void> _submit() async {
    setState(() {
      _submitted = true;
      _error = null;
    });
    if (_customer == null) {
      setState(() => _error = 'Pick a customer');
      return;
    }
    final amount = parseMoneyInput(_amountCtrl.text);
    if (amount == null || amount <= 0) {
      setState(() => _error = 'Enter an amount greater than zero, with at most 2 decimal places');
      return;
    }
    if (_collectionPoc == null) {
      setState(() => _error = 'A Collection POC is required');
      return;
    }
    setState(() => _saving = true);
    try {
      await ref.read(dioProvider).post('/api/payments', data: {
        'customerId': _customer!.id,
        'amount': amount,
        'method': _methodCtrl.text.trim(),
        'notes': _notesCtrl.text.trim(),
        'collectionPocUserId': _collectionPoc!.id,
        if (_selectedInvoices.isNotEmpty) 'invoiceIds': _selectedInvoices.toList(),
        if (_selectedPromises.isNotEmpty) 'promiseIds': _selectedPromises.toList(),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final canSeePoc = ref.watch(canSeePocProvider);
    if (_customer != null) {
      ref.watch(customerPocsProvider(_customer!.id)).whenData(
            (pocs) => _resolveDefaultPoc(_customer!.id, pocs),
          );
    }

    return AlertDialog(
      title: const Text('Record payment'),
      content: SizedBox(
        width: 540,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SearchPickerField<Customer>(
                label: 'Customer',
                required: true,
                value: _customer,
                labelOf: (c) => c.name,
                subtitleOf: (c) => c.email,
                search: (q) => searchCustomers(ref.read(dioProvider), q),
                errorText: _submitted && _customer == null ? 'Pick a customer' : null,
                onChanged: (c) => setState(() {
                  _customer = c;
                  _selectedInvoices.clear();
                  _selectedPromises.clear();
                  _pocResolvedFor = null;
                }),
              ),
              const SizedBox(height: 12),
              if (canSeePoc)
                PocPicker(
                  type: PocType.COLLECTION,
                  value: _collectionPoc,
                  required: true,
                  errorText: _submitted && _collectionPoc == null
                      ? 'A Collection POC is required before this payment can be saved'
                      : null,
                  onChanged: (u) => setState(() {
                    _collectionPoc = u;
                    _pocChosen = u != null;
                  }),
                ),
              const SizedBox(height: 12),
              if (_customer != null) _outstandingInvoices(_customer!.id),
              if (_customer != null) _openPromises(_customer!.id),
              const SizedBox(height: 12),
              TextField(
                controller: _amountCtrl,
                decoration: const InputDecoration(labelText: 'Amount *'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _methodCtrl,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.paymentMethod)],
                decoration: const InputDecoration(labelText: 'Method (cash/card/transfer)'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _notesCtrl,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.paymentNotes)],
                decoration: const InputDecoration(labelText: 'Notes'),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(false),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Record'),
        ),
      ],
    );
  }

  Widget _outstandingInvoices(int customerId) {
    final async = ref.watch(_outstandingProvider(customerId));
    return async.when(
      loading: () => const LinearProgressIndicator(),
      error: (e, _) => Text('Could not load invoices: ${apiErrorMessage(e)}'),
      data: (invoices) {
        if (invoices.isEmpty) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 6),
            child: Text('No outstanding invoices — the payment will go to customer credit'),
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Outstanding invoices (optional — pick to limit; else oldest first)'),
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 170),
              child: ListView(
                shrinkWrap: true,
                children: invoices
                    .map((inv) => CheckboxListTile(
                          dense: true,
                          value: _selectedInvoices.contains(inv.id),
                          title: Text('${inv.invoiceNumber} • ${formatMoney(inv.balance)} left'),
                          subtitle: Text(statusLabel(inv.status)),
                          onChanged: (on) => setState(() {
                            if (on == true) {
                              _selectedInvoices.add(inv.id);
                            } else {
                              _selectedInvoices.remove(inv.id);
                            }
                          }),
                        ))
                    .toList(),
              ),
            ),
          ],
        );
      },
    );
  }

  /// The customer's open promises, so the cashier can attach this payment to one (US-B3).
  Widget _openPromises(int customerId) {
    final async = ref.watch(openPromisesForCustomerProvider(customerId));
    return async.maybeWhen(
      data: (promises) {
        if (promises.isEmpty) return const SizedBox.shrink();
        return Padding(
          padding: const EdgeInsets.only(top: 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Open promises', style: Theme.of(context).textTheme.titleSmall),
              const Text(
                'A payment that settles the promised invoices links itself; tick one to link '
                'it deliberately.',
                style: TextStyle(fontSize: 12),
              ),
              ...promises.map((p) => CheckboxListTile(
                    dense: true,
                    value: _selectedPromises.contains(p.id),
                    title: Text(
                        '${formatMoney(p.amount)} by ${formatDate(p.promisedDate)} • ${promiseStatusLabel(p.status)}'),
                    subtitle: Text('Remaining ${formatMoney(p.remainingAmount)}'),
                    onChanged: (on) => setState(() {
                      if (on == true) {
                        _selectedPromises.add(p.id);
                      } else {
                        _selectedPromises.remove(p.id);
                      }
                    }),
                  )),
            ],
          ),
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }
}

final _outstandingProvider =
    FutureProvider.autoDispose.family<List<InvoiceSummary>, int>((ref, customerId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices', queryParameters: {
    'size': 50,
    'customerId': customerId,
    'filter': ['status:in:UNPAID,PARTIALLY_PAID'],
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(InvoiceSummary.fromJson)
      .toList();
});
