import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/product.dart';
import '../auth/auth_controller.dart';

/// Active products whose name contains [search], first page by name, for the invoice line
/// pickers. An inactive product cannot go on a new invoice, so it is never offered.
Future<List<Product>> searchActiveProducts(Dio dio, String search) async {
  final res = await dio.get('/api/products', queryParameters: {
    'size': 20,
    'sort': 'name,asc',
    'filter': ['active:eq:true', if (search.isNotEmpty) 'name:contains:$search'],
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(Product.fromJson)
      .toList();
}

class ProductsScreen extends ConsumerWidget {
  final TableQuery query;
  const ProductsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.productManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;

    return Scaffold(
      body: DataTableScaffold<Product>(
        entity: 'products',
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New product'),
              onPressed: () => _openForm(context, ref, null),
            ),
        ],
        path: '/api/products',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/products').push(q),
        parse: Product.fromJson,
        idOf: (p) => p.id,
        canExport: canExport,
        emptyMessage: 'No products match this filter',
        onRowTap: canManage ? (context, p) => _openForm(context, ref, p) : null,
        bulkActions: canManage
            ? const [
                BulkActionSpec(
                    action: 'ACTIVATE', label: 'Activate', icon: Icons.check_circle_outline),
                BulkActionSpec(
                    action: 'DEACTIVATE', label: 'Deactivate', icon: Icons.block),
              ]
            : const [],
        columns: [
          TableColumnSpec(label: 'Name', sortKey: 'name', cell: (context, p) => Text(p.name)),
          TableColumnSpec(
              label: 'Description',
              maxWidth: 320,
              cell: (context, p) => Tooltip(
                    message: p.description ?? '',
                    child: Text(p.description ?? '—', maxLines: 2, overflow: TextOverflow.ellipsis),
                  )),
          TableColumnSpec(
              label: 'Price',
              sortKey: 'price',
              numeric: true,
              cell: (context, p) => Text(formatMoney(p.price))),
          TableColumnSpec(
              label: 'Active',
              sortKey: 'active',
              cell: (context, p) => Text(p.active ? 'Yes' : 'No')),
        ],
        rowActions: canManage
            ? (context, p) => [
                  IconButton(
                    tooltip: 'Edit',
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    onPressed: () => _openForm(context, ref, p),
                  ),
                ]
            : null,
      ),
    );
  }

  Future<void> _openForm(BuildContext context, WidgetRef ref, Product? existing) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _ProductForm(existing: existing),
    );
    if (saved == true) {
      ref.invalidate(tablePageProvider);
    }
  }
}

class _ProductForm extends ConsumerStatefulWidget {
  final Product? existing;
  const _ProductForm({this.existing});
  @override
  ConsumerState<_ProductForm> createState() => _ProductFormState();
}

class _ProductFormState extends ConsumerState<_ProductForm> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _description;
  late final TextEditingController _price;
  late bool _active;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.existing?.name ?? '');
    _description = TextEditingController(text: widget.existing?.description ?? '');
    _price = TextEditingController(text: widget.existing?.price.toStringAsFixed(2) ?? '');
    _active = widget.existing?.active ?? true;
  }

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    _price.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      final body = {
        'name': _name.text.trim(),
        'description': _description.text.trim(),
        'price': double.parse(_price.text.trim()),
        'active': _active,
      };
      if (widget.existing == null) {
        await dio.post('/api/products', data: body);
      } else {
        await dio.put('/api/products/${widget.existing!.id}', data: body);
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
      title: Text(widget.existing == null ? 'New product' : 'Edit product'),
      content: SizedBox(
        width: 380,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _name,
                decoration: const InputDecoration(labelText: 'Name'),
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _description,
                decoration: const InputDecoration(labelText: 'Description'),
                maxLines: 2,
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _price,
                decoration: const InputDecoration(labelText: 'Price'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Required';
                  final d = double.tryParse(v.trim());
                  if (d == null || d < 0) return 'Invalid price';
                  return null;
                },
              ),
              const SizedBox(height: 8),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Active'),
                value: _active,
                onChanged: (v) => setState(() => _active = v),
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
              : const Text('Save'),
        ),
      ],
    );
  }
}
