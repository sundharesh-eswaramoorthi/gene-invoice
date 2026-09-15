import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/product.dart';
import '../customers/customers_screen.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../products/products_screen.dart';

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
  String? _error;

  @override
  void dispose() {
    for (final l in _lines) {
      l.dispose();
    }
    _notesCtrl.dispose();
    super.dispose();
  }

  double get _total => _lines.fold<double>(0, (sum, l) => sum + l.lineTotal);

  /// Pre-selects the signed-in user when they are themselves assignable; otherwise the
  /// field starts empty and blocks submission (US-A2).
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
      await dio.post('/api/invoices', data: {
        'customerId': _customer!.id,
        'notes': _notesCtrl.text.trim(),
        'salesPocUserId': _salesPoc!.id,
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
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Invoice created')));
        context.go('/invoices');
      }
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final customers = ref.watch(allCustomersProvider);
    final products = ref.watch(productsProvider);
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
              customers.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Failed to load customers: ${apiErrorMessage(e)}'),
                data: (list) => DropdownButtonFormField<Customer>(
                  decoration: InputDecoration(
                    labelText: 'Customer *',
                    errorText:
                        _submitted && _customer == null ? 'Pick a customer' : null,
                  ),
                  initialValue: _customer,
                  items: list
                      .map((c) => DropdownMenuItem(value: c, child: Text(c.name)))
                      .toList(),
                  onChanged: (c) => setState(() => _customer = c),
                ),
              ),
              const SizedBox(height: 12),
              // Mandatory: the backend rejects an invoice without one too (AC-A2).
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
              products.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Failed to load products: ${apiErrorMessage(e)}'),
                data: (productList) => Column(
                  children: [
                    for (var i = 0; i < _lines.length; i++)
                      _LineRow(
                        key: ValueKey(_lines[i]),
                        line: _lines[i],
                        products: productList,
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
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _notesCtrl,
                decoration: const InputDecoration(labelText: 'Notes'),
                maxLines: 2,
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
}

class _LineRow extends StatelessWidget {
  final _LineDraft line;
  final List<Product> products;
  final VoidCallback onChanged;
  final VoidCallback? onRemove;

  const _LineRow({
    super.key,
    required this.line,
    required this.products,
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
            child: DropdownButtonFormField<Product>(
              decoration: const InputDecoration(labelText: 'Product'),
              initialValue: line.product,
              items: products
                  .where((p) => p.active)
                  .map((p) => DropdownMenuItem(value: p, child: Text(p.name)))
                  .toList(),
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
