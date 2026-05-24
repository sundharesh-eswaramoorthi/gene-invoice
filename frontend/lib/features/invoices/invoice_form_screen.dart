import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/product.dart';
import '../customer_scope/customer_scope.dart';
import '../customers/customers_screen.dart';
import '../products/products_screen.dart';
import 'invoices_screen.dart';

class _LineDraft {
  Product? product;
  int quantity;
  double? unitPriceOverride;
  _LineDraft({this.product, this.quantity = 1, this.unitPriceOverride});

  double get effectiveUnitPrice => unitPriceOverride ?? product?.price ?? 0;
  double get lineTotal => effectiveUnitPrice * quantity;
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
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _customer = ref.read(customerScopeProvider);
  }

  @override
  void dispose() {
    _notesCtrl.dispose();
    super.dispose();
  }

  double get _total => _lines.fold<double>(0, (sum, l) => sum + l.lineTotal);

  bool get _isValid {
    if (_customer == null) return false;
    if (_lines.isEmpty) return false;
    return _lines.every((l) => l.product != null && l.quantity > 0);
  }

  Future<void> _submit() async {
    if (!_isValid) {
      setState(() => _error = 'Pick a customer and at least one product');
      return;
    }
    setState(() { _saving = true; _error = null; });
    try {
      final dio = ref.read(dioProvider);
      final body = {
        'customerId': _customer!.id,
        'notes': _notesCtrl.text.trim(),
        'items': _lines.map((l) => {
          'productId': l.product!.id,
          'quantity': l.quantity,
          if (l.unitPriceOverride != null) 'unitPrice': l.unitPriceOverride,
        }).toList(),
      };
      await dio.post('/api/invoices', data: body);
      ref.invalidate(invoicesProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Invoice created')),
        );
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
    final customers = ref.watch(customersProvider);
    final products = ref.watch(productsProvider);

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
                error: (e, _) => Text('Failed to load customers: $e'),
                data: (list) => DropdownButtonFormField<Customer>(
                  decoration: const InputDecoration(labelText: 'Customer'),
                  value: _customer,
                  items: list.map((c) => DropdownMenuItem(value: c, child: Text(c.name))).toList(),
                  onChanged: (c) => setState(() => _customer = c),
                ),
              ),
              const SizedBox(height: 16),
              Text('Items', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              products.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Failed to load products: $e'),
                data: (productList) => Column(
                  children: [
                    for (var i = 0; i < _lines.length; i++)
                      _LineRow(
                        line: _lines[i],
                        products: productList,
                        onChanged: () => setState(() {}),
                        onRemove: _lines.length == 1 ? null : () {
                          setState(() => _lines.removeAt(i));
                        },
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
                  Text(_total.toStringAsFixed(2),
                      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                ],
              ),
              if (_error != null) Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
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
                          ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
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
  const _LineRow({required this.line, required this.products,
      required this.onChanged, this.onRemove});

  @override
  Widget build(BuildContext context) {
    final qtyCtrl = TextEditingController(text: line.quantity.toString());
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: DropdownButtonFormField<Product>(
              decoration: const InputDecoration(labelText: 'Product'),
              value: line.product,
              items: products.where((p) => p.active).map((p) =>
                  DropdownMenuItem(value: p, child: Text(p.name))).toList(),
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
              controller: qtyCtrl,
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
                line.lineTotal.toStringAsFixed(2),
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
