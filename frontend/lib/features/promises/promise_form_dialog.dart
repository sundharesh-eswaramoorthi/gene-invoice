import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/status_chip.dart';
import '../approvals/approval_providers.dart';
import '../approvals/pending_approval_panel.dart';
import '../email/email_actions.dart';
import '../poc/poc_providers.dart';
import '../poc/poc_picker.dart';
import 'promise_providers.dart';

/// Raises or edits a payment promise, pre-scoped to a customer and optionally to
/// specific invoices (US-B1, US-B2, US-C4). Resolves true once the promise is saved.
///
/// [preselectedInvoices] are the invoices the screen it was opened from is about. They are the
/// whole invoice, not just its id, so the checklist can always show one — a fully-paid or
/// cancelled invoice is not in the outstanding list the checklist is otherwise built from, and
/// linking it invisibly scoped the promise to an invoice the user never saw (UI-02).
///
/// "Notify through email" is handled here rather than by each caller: once the form has closed,
/// the compose form opens on [context] for the saved promise (E12).
Future<bool?> showPromiseDialog({
  required BuildContext context,
  required int customerId,
  String? customerName,
  List<InvoiceSummary> preselectedInvoices = const [],
  PaymentPromise? existing,
}) async {
  final saved = await showDialog<_SavedPromise>(
    context: context,
    builder: (_) => _PromiseFormDialog(
      customerId: customerId,
      customerName: customerName,
      preselectedInvoices: preselectedInvoices,
      existing: existing,
    ),
  );
  if (saved == null) return false;
  if (context.mounted) {
    await notifyByEmailAfterSave(context,
        notify: saved.notify,
        type: EmailEntityType.promise,
        entityId: saved.id,
        event: existing == null ? EmailEvent.created : EmailEvent.updated);
  }
  return true;
}

typedef _SavedPromise = ({int id, bool notify});

class _PromiseFormDialog extends ConsumerStatefulWidget {
  final int customerId;
  final String? customerName;
  final List<InvoiceSummary> preselectedInvoices;
  final PaymentPromise? existing;

  const _PromiseFormDialog({
    required this.customerId,
    this.customerName,
    this.preselectedInvoices = const [],
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
  bool _notify = false;
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
      _invoiceIds.addAll(widget.preselectedInvoices.map((i) => i.id));
    }
  }

  @override
  void dispose() {
    _amount.dispose();
    _notes.dispose();
    super.dispose();
  }

  void _resolveDefaultPoc(List<CustomerPoc> pocs) {
    if (_pocResolved) return;
    _pocResolved = true;
    final active = pocs.where((p) => p.pocType == PocType.COLLECTION && p.user.active);
    final seat = active.where((p) => p.primary).firstOrNull ?? active.firstOrNull;
    if (seat != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() => _poc = seat.user);
      });
    }
  }

  /// Every outstanding invoice, plus every invoice this promise is already scoped to that the
  /// outstanding list does not carry: one the screen preselected, or — when editing — a linked
  /// one that has since been paid off or cancelled. Each stays visible and can be unticked, and
  /// counts towards the shortfall hint; nothing is ever submitted that has no checkbox (UI-02).
  List<_InvoiceOption> _options(List<InvoiceSummary> outstanding) {
    final options = [for (final i in outstanding) _InvoiceOption.of(i)];
    final shown = {for (final o in options) o.id};
    for (final preselected in widget.preselectedInvoices) {
      if (shown.add(preselected.id)) options.add(_InvoiceOption.of(preselected));
    }
    for (final linked in widget.existing?.invoices ?? const <PromiseInvoiceRef>[]) {
      if (!shown.add(linked.id)) continue;
      final status = InvoiceStatus.values.asNameMap()[linked.status];
      options.add(_InvoiceOption(linked.id, linked.invoiceNumber, status,
          status == null ? linked.status : statusLabel(status), linked.balance,
          live: status != InvoiceStatus.CANCELLED));
    }
    return options;
  }

  /// The save was taken and has not happened: the server is holding the change now, not this
  /// dialog. Nothing was created or changed, so it closes with nothing to report — popping null
  /// is what tells showPromiseDialog's caller that there is no promise to email anybody
  /// about — and the providers are put back in step so the chip and the amber panel appear (B2).
  bool _handleHeld(DioException e) {
    final held = pendingApprovalOf(e);
    if (held == null) return false;
    ref.invalidate(scopedPromisesProvider);
    if (widget.existing != null) ref.invalidate(promiseDetailProvider(widget.existing!.id));
    invalidateApprovals(ref);
    if (mounted) {
      showApprovalSentSnackBar(context, held);
      Navigator.of(context).pop();
    }
    return true;
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
      final res = _isEdit
          ? await dio.put('/api/promises/${widget.existing!.id}', data: body)
          : await dio.post('/api/promises', data: body);
      final id = _isEdit ? widget.existing!.id : ((res.data as Map)['id'] as num).toInt();
      ref.invalidate(scopedPromisesProvider);
      ref.invalidate(promiseDetailProvider(id));
      if (mounted) Navigator.of(context).pop((id: id, notify: _notify));
    } on DioException catch (e) {
      if (_handleHeld(e)) return;
      setState(() => _error = apiErrorMessage(e));
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
                // The account this promise is against decides who may collect it (B1).
                customerId: widget.customerId,
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
              if (invoicesAsync.isLoading) const LinearProgressIndicator(),
              if (invoicesAsync.hasError)
                Text('Could not load invoices: ${apiErrorMessage(invoicesAsync.error!)}'),
              Builder(
                builder: (context) {
                  final options = _options(invoicesAsync.valueOrNull ?? const []);
                  if (options.isEmpty) {
                    return invoicesAsync.hasValue
                        ? const Padding(
                            padding: EdgeInsets.symmetric(vertical: 8),
                            child:
                                Text('No outstanding invoices — this will be a general promise.'),
                          )
                        : const SizedBox.shrink();
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
                                subtitle: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(o.statusText,
                                        style: TextStyle(
                                            color: o.status == null
                                                ? null
                                                : invoiceStatusColor(context, o.status!),
                                            fontWeight: FontWeight.w600)),
                                    Text(o.live
                                        ? ' • balance ${formatMoney(o.balance)}'
                                        : ' • no longer owed'),
                                  ],
                                ),
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
              Builder(
                builder: (context) {
                  final live = _options(invoicesAsync.valueOrNull ?? const [])
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
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _notes,
                maxLines: 2,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.promiseNotes)],
                decoration: const InputDecoration(labelText: 'Notes'),
              ),
              const SizedBox(height: 8),
              NotifyByEmailCheckbox(
                value: _notify,
                onChanged: (v) => setState(() => _notify = v),
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
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
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

class _InvoiceOption {
  final int id;
  final String invoiceNumber;

  final InvoiceStatus? status;
  final String statusText;
  final double balance;

  final bool live;

  const _InvoiceOption(this.id, this.invoiceNumber, this.status, this.statusText, this.balance,
      {required this.live});

  factory _InvoiceOption.of(InvoiceSummary i) => _InvoiceOption(
        i.id,
        i.invoiceNumber,
        i.status,
        statusLabel(i.status),
        i.balance,
        live: i.status != InvoiceStatus.CANCELLED,
      );
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

  /// See _PromiseFormDialogState._handleHeld. An override that needs approval has changed
  /// nothing, so the dialog closes and the promise's status is still whatever it was (B2).
  bool _handleHeld(DioException e) {
    final held = pendingApprovalOf(e);
    if (held == null) return false;
    ref.invalidate(scopedPromisesProvider);
    ref.invalidate(promiseDetailProvider(widget.promise.id));
    invalidateApprovals(ref);
    if (mounted) {
      showApprovalSentSnackBar(context, held);
      Navigator.of(context).pop();
    }
    return true;
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
    } on DioException catch (e) {
      if (_handleHeld(e)) return;
      setState(() => _error = apiErrorMessage(e));
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
                  .map((s) => DropdownMenuItem(
                      value: s,
                      child: Text(promiseStatusLabel(s),
                          style: TextStyle(
                              color: promiseStatusColor(context, s),
                              fontWeight: FontWeight.w600))))
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
