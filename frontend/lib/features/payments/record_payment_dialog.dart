import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/promise.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../../shared/widgets/status_chip.dart';
import '../approvals/pending_approval_panel.dart';
import '../auth/auth_controller.dart';
import '../customers/customers_screen.dart';
import '../email/email_actions.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promise_providers.dart';

Future<bool?> showRecordPaymentDialog({
  required BuildContext context,
  Customer? customer,
}) async {
  final saved = await showDialog<_RecordedPayment>(
    context: context,
    builder: (_) => _RecordPaymentDialog(initialCustomer: customer),
  );
  if (saved == null) return false;
  if (context.mounted) {
    await notifyByEmailAfterSave(context,
        notify: saved.notify,
        type: EmailEntityType.payment,
        entityId: saved.id,
        event: EmailEvent.created);
  }
  return true;
}

typedef _RecordedPayment = ({int id, bool notify});

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

  bool _pocChosen = false;
  bool _saving = false;
  bool _submitted = false;
  bool _notify = false;
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

  void _resolveDefaultPoc(int customerId, List<CustomerPoc> pocs) {
    if (_pocResolvedFor == customerId) return;
    _pocResolvedFor = customerId;
    final active = pocs.where((p) => p.pocType == PocType.COLLECTION && p.user.active);
    final seat = active.where((p) => p.primary).firstOrNull ?? active.firstOrNull;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_pocChosen) setState(() => _collectionPoc = seat?.user);
    });
  }

  /// Drops the form's complaint as soon as the field it named has changed, the way the customer
  /// page drops the server's (D-52): a message that asks for something already done reads as a
  /// refusal to save (UI-04).
  void _clearError() {
    if (_error != null) setState(() => _error = null);
  }

  /// Whether this account is in a branch this person may record payments in. Recording NAMES
  /// the account, so the server answers 403 rather than 404 (D-46) — better said here than
  /// after the amount has been typed (B1).
  bool get _outsideMyBranches =>
      _customer != null &&
      !(ref.read(currentUserProvider)?.hasIn(Privileges.paymentManage, _customer!.regionId) ??
          false);

  String get _branchRefusal =>
      'You cannot record payments in ${_customer?.regionName ?? 'that branch'}.';

  Future<void> _submit() async {
    setState(() {
      _submitted = true;
      _error = null;
    });
    if (_customer == null) {
      setState(() => _error = 'Pick a customer');
      return;
    }
    if (_outsideMyBranches) {
      setState(() => _error = _branchRefusal);
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
      final res = await ref.read(dioProvider).post('/api/payments', data: {
        'customerId': _customer!.id,
        'amount': amount,
        'method': _methodCtrl.text.trim(),
        'notes': _notesCtrl.text.trim(),
        'collectionPocUserId': _collectionPoc!.id,
        if (_selectedInvoices.isNotEmpty) 'invoiceIds': _selectedInvoices.toList(),
        if (_selectedPromises.isNotEmpty) 'promiseIds': _selectedPromises.toList(),
      });
      if (mounted) {
        Navigator.of(context)
            .pop<_RecordedPayment>((id: (res.data as Map)['id'] as int, notify: _notify));
      }
    } on DioException catch (e) {
      final held = pendingApprovalOf(e);
      if (held != null) {
        if (mounted) {
          // The amber first and the pop second: the SnackBar is the app-level messenger's, so it
          // outlives this dialog, and looking the messenger up while the dialog is certainly
          // still mounted is the safe order (B2).
          showApprovalSentSnackBar(context, held);
          // Nothing was created, so there is no payment to notify anybody about — and popping
          // null is exactly what tells showRecordPaymentDialog's caller so (B2).
          Navigator.of(context).pop<_RecordedPayment>(null);
        }
        return;
      }
      setState(() => _error = apiErrorMessage(e));
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
                errorText: _customer == null
                    ? (_submitted ? 'Pick a customer' : null)
                    : (_outsideMyBranches ? _branchRefusal : null),
                onChanged: (c) => setState(() {
                  _customer = c;
                  _selectedInvoices.clear();
                  _selectedPromises.clear();
                  _pocResolvedFor = null;
                  _error = null;
                }),
              ),
              const SizedBox(height: 12),
              if (canSeePoc)
                PocPicker(
                  type: PocType.COLLECTION,
                  value: _collectionPoc,
                  required: true,
                  // Null until a customer is chosen, and then the account's own branch decides
                  // who may hold the seat. Before that the picker falls back to every branch
                  // this person works in rather than asking about none (B1).
                  customerId: _customer?.id,
                  errorText: _submitted && _collectionPoc == null
                      ? 'A Collection POC is required before this payment can be saved'
                      : null,
                  onChanged: (u) => setState(() {
                    _collectionPoc = u;
                    _pocChosen = u != null;
                    _error = null;
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
                onChanged: (_) => _clearError(),
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
              NotifyByEmailCheckbox(
                value: _notify,
                onChanged: (v) => setState(() => _notify = v),
                // Writing to the customer about their payment is a write in the account's
                // branch, not a company-wide one (B1).
                regionId: _customer?.regionId,
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
            onPressed: _saving ? null : () => Navigator.of(context).pop(),
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
                          subtitle: Text(
                            statusLabel(inv.status),
                            style: TextStyle(color: invoiceStatusColor(context, inv.status)),
                          ),
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
                    title: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Flexible(
                          child: Text(
                              '${formatMoney(p.amount)} by ${formatDate(p.promisedDate)} • '),
                        ),
                        Text(promiseStatusLabel(p.status),
                            style: TextStyle(
                                color: promiseStatusColor(context, p.status),
                                fontWeight: FontWeight.w600)),
                      ],
                    ),
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
