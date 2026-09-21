import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/payment_term.dart';
import '../../shared/models/product.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../customers/customers_screen.dart';
import '../email/email_actions.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../products/products_screen.dart';

final DateFormat _dueDateInWords = DateFormat('d MMM yyyy');

class _LineDraft {
  Product? product;
  int quantity = 1;
  double? unitPriceOverride;
  final TextEditingController qtyController = TextEditingController(text: '1');

  double get effectiveUnitPrice => unitPriceOverride ?? product?.price ?? 0;
  double get lineTotal => effectiveUnitPrice * quantity;

  void dispose() => qtyController.dispose();
}

class InvoiceFormScreen extends ConsumerStatefulWidget {
  const InvoiceFormScreen({super.key});
  @override
  ConsumerState<InvoiceFormScreen> createState() => _InvoiceFormScreenState();
}

class _InvoiceFormScreenState extends ConsumerState<InvoiceFormScreen> {
  Customer? _customer;
  final List<_LineDraft> _lines = [_LineDraft()];
  final _notesCtrl = TextEditingController();
  PocUser? _salesPoc;
  bool _pocPreselected = false;
  bool _saving = false;
  bool _submitted = false;
  bool _notify = false;
  String? _error;

  PaymentTerm? _term;
  String? _termLabel;
  DateTime? _dueDate;

  DateTime? _invoiceDate;
  bool _previewing = false;
  String? _previewError;

  @override
  void dispose() {
    for (final l in _lines) {
      l.dispose();
    }
    _notesCtrl.dispose();
    super.dispose();
  }

  double get _total => _lines.fold<double>(0, (sum, l) => sum + l.lineTotal);

  void _preselectSelf(List<PocUser> assignable, int? myUserId) {
    if (_pocPreselected || myUserId == null) return;
    _pocPreselected = true;
    for (final u in assignable) {
      if (u.id == myUserId) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) setState(() => _salesPoc = u);
        });
        return;
      }
    }
  }

  bool get _linesValid =>
      _lines.isNotEmpty && _lines.every((l) => l.product != null && l.quantity > 0);

  void _customerPicked(Customer? customer) {
    setState(() => _customer = customer);
    if (customer != null) _previewDueDate(customer);
  }

  Future<void> _previewDueDate(Customer customer) async {
    setState(() {
      _previewing = true;
      _previewError = null;
      _term = null;
      _termLabel = null;
      _dueDate = null;
      _invoiceDate = null;
    });
    try {
      final res = await ref.read(dioProvider).get('/api/invoices/due-date-preview',
          queryParameters: {'customerId': customer.id});
      if (!mounted || _customer?.id != customer.id) return;
      final preview = DueDatePreview.fromJson((res.data as Map).cast<String, dynamic>());
      setState(() {
        _term = preview.paymentTerm;
        _termLabel = preview.paymentTermLabel;
        _dueDate = preview.dueDate;
        _invoiceDate = preview.invoiceDate;
      });
    } catch (e) {
      if (mounted && _customer?.id == customer.id) {
        setState(() => _previewError = apiErrorMessage(e));
      }
    } finally {
      if (mounted) setState(() => _previewing = false);
    }
  }

  void _termChanged(PaymentTerm? term) {
    if (term == null) return;
    setState(() {
      _term = term;
      _termLabel = term.label;
      final due = _invoiceDate == null ? null : term.due(_invoiceDate!);
      if (due != null) _dueDate = due;
    });
  }

  Future<void> _pickDueDate() async {
    final basis = _invoiceDate ?? DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _dueDate ?? basis,
      firstDate: DateTime(basis.year, basis.month, basis.day),
      lastDate: DateTime(basis.year + 5, basis.month, basis.day),
    );
    if (picked == null) return;
    setState(() {
      _dueDate = DateTime(picked.year, picked.month, picked.day);
      _term = PaymentTerm.CUSTOM;
      _termLabel = PaymentTerm.CUSTOM.label;
    });
  }

  bool get _beyondHorizon {
    if (_dueDate == null) return false;
    final today = DateTime.now();
    final basis = _invoiceDate ?? DateTime(today.year, today.month, today.day);
    return _dueDate!.difference(basis).inDays > FieldLimits.dueDateHorizonDays;
  }

  Future<void> _submit() async {
    setState(() {
      _submitted = true;
      _error = null;
    });
    if (_customer == null || !_linesValid || _salesPoc == null) {
      setState(() => _error = 'Fill in the customer, the Sales POC and at least one line');
      return;
    }
    setState(() => _saving = true);
    try {
      final dio = ref.read(dioProvider);
      final res = await dio.post('/api/invoices', data: {
        'customerId': _customer!.id,
        'notes': _notesCtrl.text.trim(),
        'salesPocUserId': _salesPoc!.id,
        if (_term == PaymentTerm.CUSTOM && _dueDate != null)
          'dueDate': DateFormat('yyyy-MM-dd').format(_dueDate!),
        if (_term != null && _term != PaymentTerm.CUSTOM) 'paymentTerm': _term!.name,
        'items': _lines
            .map((l) => {
                  'productId': l.product!.id,
                  'quantity': l.quantity,
                  if (l.unitPriceOverride != null) 'unitPrice': l.unitPriceOverride,
                })
            .toList(),
      });
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      var compose = EmailComposeOutcome.closed;
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Invoice created')));
        setState(() => _saving = false);
        compose = await notifyByEmailAfterSave(context,
            notify: _notify,
            type: EmailEntityType.invoice,
            entityId: (res.data as Map)['id'] as int,
            event: EmailEvent.created);
      }
      if (mounted && compose != EmailComposeOutcome.leftForGmail) context.go('/invoices');
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scope = ref.watch(myPocScopeProvider).valueOrNull;
    final canSeePoc = ref.watch(canSeePocProvider);

    if (canSeePoc && scope != null) {
      ref.watch(assignablePocsProvider(const AssignableQuery(PocType.SALES, '')))
          .whenData((users) => _preselectSelf(users, scope.userId));
    }

    return Scaffold(
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 800),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('New invoice', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 16),
              SearchPickerField<Customer>(
                label: 'Customer',
                required: true,
                value: _customer,
                labelOf: (c) => c.name,
                subtitleOf: (c) => c.email,
                search: (q) => searchCustomers(ref.read(dioProvider), q),
                errorText: _submitted && _customer == null ? 'Pick a customer' : null,
                onChanged: _customerPicked,
              ),
              const SizedBox(height: 12),
              _termsAndDueDate(),
              const SizedBox(height: 12),
              if (canSeePoc)
                PocPicker(
                  type: PocType.SALES,
                  value: _salesPoc,
                  required: true,
                  errorText: _submitted && _salesPoc == null
                      ? 'A Sales POC is required before this invoice can be saved'
                      : null,
                  onChanged: (u) => setState(() => _salesPoc = u),
                ),
              const SizedBox(height: 16),
              Text('Items', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Column(
                children: [
                  for (var i = 0; i < _lines.length; i++)
                    _LineRow(
                      key: ValueKey(_lines[i]),
                      line: _lines[i],
                      searchProducts: (q) => searchActiveProducts(ref.read(dioProvider), q),
                      onChanged: () => setState(() {}),
                      onRemove: _lines.length == 1
                          ? null
                          : () => setState(() => _lines.removeAt(i).dispose()),
                    ),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      icon: const Icon(Icons.add),
                      label: const Text('Add line'),
                      onPressed: () => setState(() => _lines.add(_LineDraft())),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _notesCtrl,
                decoration: const InputDecoration(labelText: 'Notes'),
                maxLines: 2,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.invoiceNotes)],
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  const Text('Total', style: TextStyle(fontSize: 18)),
                  const Spacer(),
                  Text(formatMoney(_total),
                      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                ],
              ),
              NotifyByEmailCheckbox(
                value: _notify,
                onChanged: (v) => setState(() => _notify = v),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _saving ? null : () => context.go('/invoices'),
                      child: const Text('Cancel'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: _saving ? null : _submit,
                      child: _saving
                          ? const SizedBox(
                              width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Create invoice'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _termsAndDueDate() {
    final theme = Theme.of(context);
    final narrow = MediaQuery.sizeOf(context).width < 600;
    final picked = _customer != null;

    final String dueText;
    if (_previewing) {
      dueText = 'Working out the due date…';
    } else if (_dueDate == null) {
      dueText = picked ? 'Pick a date' : 'Pick a customer first';
    } else {
      dueText = formatDate(_dueDate);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Stacked on a phone, where two fields in a row cut their labels (D-60).
        Flex(
          direction: narrow ? Axis.vertical : Axis.horizontal,
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: InputDecorator(
                decoration: const InputDecoration(labelText: 'Payment terms'),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<PaymentTerm>(
                    isExpanded: true,
                    isDense: true,
                    value: _term,
                    hint: const Text('From the customer'),
                    items: [
                      for (final t in PaymentTerm.values)
                        DropdownMenuItem(value: t, child: Text(t.label)),
                    ],
                    onChanged: picked ? _termChanged : null,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12, height: 12),
            Flexible(
              child: InkWell(
                onTap: picked ? _pickDueDate : null,
                child: InputDecorator(
                  decoration: const InputDecoration(
                    labelText: 'Due date',
                    suffixIcon: Icon(Icons.calendar_today, size: 18),
                  ),
                  child: Text(dueText),
                ),
              ),
            ),
          ],
        ),
        if (_dueDate != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              '${_termLabel ?? _term?.label ?? ''} — due ${_dueDateInWords.format(_dueDate!)}',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
        if (_beyondHorizon)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text('That is more than a year away — is it right?',
                style: TextStyle(color: theme.colorScheme.tertiary)),
          ),
        if (_previewError != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              'Could not work out the due date: $_previewError. '
              'Saving will use the customer\'s terms.',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
            ),
          ),
      ],
    );
  }
}

class _LineRow extends StatelessWidget {
  final _LineDraft line;
  final Future<List<Product>> Function(String search) searchProducts;
  final VoidCallback onChanged;
  final VoidCallback? onRemove;

  const _LineRow({
    super.key,
    required this.line,
    required this.searchProducts,
    required this.onChanged,
    this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: SearchPickerField<Product>(
              label: 'Product',
              value: line.product,
              labelOf: (p) => p.name,
              subtitleOf: (p) => formatMoney(p.price),
              search: searchProducts,
              onChanged: (p) {
                line.product = p;
                onChanged();
              },
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 2,
            child: TextFormField(
              controller: line.qtyController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Qty'),
              onChanged: (v) {
                line.quantity = int.tryParse(v) ?? 0;
                onChanged();
              },
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 2,
            child: Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                formatMoney(line.lineTotal),
                style: const TextStyle(fontWeight: FontWeight.w600),
                textAlign: TextAlign.right,
              ),
            ),
          ),
          IconButton(
            tooltip: onRemove == null ? null : 'Remove',
            onPressed: onRemove,
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
    );
  }
}
