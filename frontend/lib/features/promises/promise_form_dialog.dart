import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/promise.dart';
import '../poc/poc_providers.dart';
import '../poc/poc_picker.dart';

/// Raises or edits a payment promise, pre-scoped to a customer and optionally to
/// specific invoices (US-B1, US-B2, US-C4).
Future<bool?> showPromiseDialog({
  required BuildContext context,
  required int customerId,
  String? customerName,
  List<int> preselectedInvoiceIds = const [],
  PaymentPromise? existing,
}) {
  return showDialog<bool>(
    context: context,
    builder: (_) => _PromiseFormDialog(
      customerId: customerId,
      customerName: customerName,
      preselectedInvoiceIds: preselectedInvoiceIds,
      existing: existing,
    ),
  );
}

class _PromiseFormDialog extends ConsumerStatefulWidget {
  final int customerId;
  final String? customerName;
  final List<int> preselectedInvoiceIds;
  final PaymentPromise? existing;

  const _PromiseFormDialog({
    required this.customerId,
    this.customerName,
    this.preselectedInvoiceIds = const [],
    this.existing,
  });

  @override
  ConsumerState<_PromiseFormDialog> createState() => _PromiseFormDialogState();
}

class _PromiseFormDialogState extends ConsumerState<_PromiseFormDialog> {
  final _amount = TextEditingController();
  final _notes = TextEditingController();
  DateTime? _date;
  PocUser? _poc;
  final Set<int> _invoiceIds = {};
  bool _saving = false;
  bool _pocResolved = false;
  String? _error;

  bool get _isEdit => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    if (e != null) {
      _amount.text = e.amount.toStringAsFixed(2);
      _notes.text = e.notes ?? '';
      _date = e.promisedDate;
      _poc = e.collectionPoc;
      _pocResolved = true;
      _invoiceIds.addAll(e.invoices.map((i) => i.id));
    } else {
      _date = DateTime.now().add(const Duration(days: 7));
      _invoiceIds.addAll(widget.preselectedInvoiceIds);
    }
  }

  @override
  void dispose() {
    _amount.dispose();
    _notes.dispose();
    super.dispose();
  }

  /// Defaults to the customer's primary Collection POC, which stays editable (AC-B8).
  void _resolveDefaultPoc(List<CustomerPoc> pocs) {
    if (_pocResolved) return;
    _pocResolved = true;
    // A deactivated seat holder is never the default; the next active one is.
    final active = pocs.where((p) => p.pocType == PocType.COLLECTION && p.user.active);
    final seat = active.where((p) => p.primary).firstOrNull ?? active.firstOrNull;
    if (seat != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() => _poc = seat.user);
      });
    }
  }

  /// Every outstanding invoice, plus — when editing — any linked one that has since been paid off
  /// or cancelled, so it stays visible and can be unticked.
  List<_InvoiceOption> _options(List<InvoiceSummary> outstanding) {
    final options = [
      for (final i in outstanding)
        _InvoiceOption(i.id, i.invoiceNumber, statusLabel(i.status), i.balance, live: true),
    ];
    final shown = {for (final o in options) o.id};
    for (final linked in widget.existing?.invoices ?? const <PromiseInvoiceRef>[]) {
      if (shown.contains(linked.id)) continue;
      final status = InvoiceStatus.values.asNameMap()[linked.status];
      options.add(_InvoiceOption(linked.id, linked.invoiceNumber,
          status == null ? linked.status : statusLabel(status), linked.balance,
          live: status != InvoiceStatus.CANCELLED));
    }
    return options;
  }

  Future<void> _submit() async {
    final amount = parseMoneyInput(_amount.text);
    if (amount == null || amount <= 0) {
      setState(() => _error = 'Enter an amount greater than zero, with at most 2 decimal places');
      return;
    }
    if (_date == null) {
      setState(() => _error = 'Pick the date the customer promised to pay by');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      final body = {
        'customerId': widget.customerId,
        'amount': amount,
        'promisedDate': DateFormat('yyyy-MM-dd').format(_date!),
        if (_poc != null) 'collectionPocUserId': _poc!.id,
        'notes': _notes.text.trim(),
        'invoiceIds': _invoiceIds.toList(),
      };
      if (_isEdit) {
        await dio.put('/api/promises/${widget.existing!.id}', data: body);
      } else {
        await dio.post('/api/promises', data: body);
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final pocsAsync = ref.watch(customerPocsProvider(widget.customerId));
    pocsAsync.whenData(_resolveDefaultPoc);
    final invoicesAsync = ref.watch(_outstandingInvoicesProvider(widget.customerId));
    final promisedAmount = double.tryParse(_amount.text.trim()) ?? 0;

    return AlertDialog(
      title: Text(_isEdit
          ? 'Edit promise'
          : 'Payment promise${widget.customerName == null ? '' : ' • ${widget.customerName}'}'),
      content: SizedBox(
        // A phone has nowhere near 520px to give (D-60).
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Side by side when there is room; stacked on a phone, where two fields in a row
              // cut the amount's label and push the date onto a second line (D-60).
              Flex(
                direction: MediaQuery.sizeOf(context).width < 600 ? Axis.vertical : Axis.horizontal,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Flexible(
                    child: TextField(
                      controller: _amount,
                      autofocus: true,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Promised amount *'),
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: 12, height: 12),
                  Flexible(
                    child: InkWell(
                      onTap: () async {
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: _date ?? DateTime.now(),
                          firstDate: DateTime.now().subtract(const Duration(days: 365)),
                          lastDate: DateTime.now().add(const Duration(days: 365 * 3)),
                        );
                        if (picked != null) setState(() => _date = picked);
                      },
                      child: InputDecorator(
                        decoration: const InputDecoration(
                          labelText: 'Promised by *',
                          suffixIcon: Icon(Icons.calendar_today, size: 18),
                        ),
                        child: Text(_date == null ? 'Pick a date' : formatDate(_date)),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              PocPicker(
                type: PocType.COLLECTION,
                value: _poc,
                required: true,
                onChanged: (u) => setState(() => _poc = u),
              ),
              const SizedBox(height: 12),
              Text('Invoices this covers (optional)',
                  style: Theme.of(context).textTheme.titleSmall),
              const Text(
                'Leave empty for a general promise against the account balance.',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 4),
              invoicesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Could not load invoices: ${apiErrorMessage(e)}'),
                data: (outstanding) {
                  final options = _options(outstanding);
                  if (options.isEmpty) {
                    return const Padding(
                      padding: EdgeInsets.symmetric(vertical: 8),
                      child: Text('No outstanding invoices — this will be a general promise.'),
                    );
                  }
                  return ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: 180),
                    child: ListView(
                      shrinkWrap: true,
                      children: options
                          .map((o) => CheckboxListTile(
                                dense: true,
                                value: _invoiceIds.contains(o.id),
                                title: Text(o.invoiceNumber),
                                subtitle: Text(o.live
                                    ? '${o.status} • balance ${formatMoney(o.balance)}'
                                    : '${o.status} • no longer owed'),
                                onChanged: (on) => setState(() {
                                  if (on == true) {
                                    _invoiceIds.add(o.id);
                                  } else {
                                    _invoiceIds.remove(o.id);
                                  }
                                }),
                              ))
                          .toList(),
                    ),
                  );
                },
              ),
              // A shortfall or excess against the covered invoices is shown, never blocked (AC-B2).
              // A cancelled invoice owes nothing, so it counts towards neither.
              invoicesAsync.maybeWhen(
                data: (outstanding) {
                  final live = _options(outstanding)
                      .where((o) => o.live && _invoiceIds.contains(o.id))
                      .toList();
                  if (live.isEmpty || promisedAmount <= 0) return const SizedBox.shrink();
                  final covered = live.fold<double>(0, (sum, o) => sum + o.balance);
                  final diff = promisedAmount - covered;
                  if (diff.abs() < 0.005) return const SizedBox.shrink();
                  return Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      diff > 0
                          ? 'Promised ${formatMoney(diff)} more than those invoices owe.'
                          : 'Promised ${formatMoney(-diff)} less than those invoices owe.',
                      style: TextStyle(color: Theme.of(context).colorScheme.tertiary),
                    ),
                  );
                },
                orElse: () => const SizedBox.shrink(),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _notes,
                maxLines: 2,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.promiseNotes)],
                decoration: const InputDecoration(labelText: 'Notes'),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
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
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(_isEdit ? 'Save' : 'Raise promise'),
        ),
      ],
    );
  }
}

/// A row in the promise's invoice checklist.
class _InvoiceOption {
  final int id;
  final String invoiceNumber;
  final String status;
  final double balance;

  /// False for a cancelled invoice, which owes nothing any more.
  final bool live;

  const _InvoiceOption(this.id, this.invoiceNumber, this.status, this.balance, {required this.live});
}

final _outstandingInvoicesProvider =
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

/// Pins a status by hand when reality disagrees with the arithmetic (US-B6, AC-B9).
Future<bool?> showOverrideDialog({
  required BuildContext context,
  required PaymentPromise promise,
}) {
  return showDialog<bool>(
    context: context,
    builder: (_) => _OverrideDialog(promise: promise),
  );
}

class _OverrideDialog extends ConsumerStatefulWidget {
  final PaymentPromise promise;
  const _OverrideDialog({required this.promise});

  @override
  ConsumerState<_OverrideDialog> createState() => _OverrideDialogState();
}

class _OverrideDialogState extends ConsumerState<_OverrideDialog> {
  late PromiseStatus _status = widget.promise.status == PromiseStatus.CANCELLED
      ? PromiseStatus.KEPT
      : widget.promise.status;
  final _reason = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit({required bool clear}) async {
    if (!clear && _reason.text.trim().isEmpty) {
      setState(() => _error = 'A reason is required');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      if (clear) {
        await dio.delete('/api/promises/${widget.promise.id}/override');
      } else {
        await dio.post('/api/promises/${widget.promise.id}/override',
            data: {'status': _status.name, 'reason': _reason.text.trim()});
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Override status'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'The status is normally worked out from the payments. Overriding pins it until '
              'you clear the override.',
              style: TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<PromiseStatus>(
              initialValue: _status,
              decoration: const InputDecoration(labelText: 'Status'),
              items: PromiseStatus.values
                  .where((s) => s != PromiseStatus.CANCELLED)
                  .map((s) =>
                      DropdownMenuItem(value: s, child: Text(promiseStatusLabel(s))))
                  .toList(),
              onChanged: (s) => setState(() => _status = s ?? _status),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _reason,
              maxLines: 2,
              decoration: const InputDecoration(labelText: 'Reason *'),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
          ],
        ),
      ),
      actions: [
        if (widget.promise.statusOverridden)
          TextButton(
            onPressed: _saving ? null : () => _submit(clear: true),
            child: const Text('Clear override'),
          ),
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : () => _submit(clear: false),
          child: const Text('Override'),
        ),
      ],
    );
  }
}
