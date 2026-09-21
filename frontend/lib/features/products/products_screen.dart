import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/product.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';

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

final productDetailProvider =
    FutureProvider.autoDispose.family<Product, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/products/$id');
  return Product.fromJson(res.data as Map<String, dynamic>);
});

Future<void> openProductForm(BuildContext context, WidgetRef ref, {Product? existing}) async {
  final saved = await showDialog<({int id, bool notify})>(
    context: context,
    builder: (_) => ProductFormDialog(existing: existing),
  );
  if (saved == null) return;
  ref.invalidate(tablePageProvider);
  ref.invalidate(productDetailProvider(saved.id));
  ref.invalidate(auditHistoryProvider);
  if (!context.mounted) return;
  await notifyByEmailAfterSave(context,
      notify: saved.notify,
      type: EmailEntityType.product,
      entityId: saved.id,
      event: EmailEvent.created);
}

class ProductsScreen extends ConsumerWidget {
  final TableQuery query;
  const ProductsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.productManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSendEmail = ref.watch(canSendEmailProvider);
    final sendEmail = sendEmailPageAction(context, ref, type: EmailEntityType.product);

    return Scaffold(
      body: DataTableScaffold<Product>(
        entity: 'products',
        actions: [
          if (sendEmail != null) sendEmail,
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New product'),
              onPressed: () => openProductForm(context, ref),
            ),
        ],
        path: '/api/products',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/products').push(q),
        parse: Product.fromJson,
        idOf: (p) => p.id,
        canExport: canExport,
        emptyMessage: 'No products match this filter',
        onRowTap: (context, p) => context.go('/products/${p.id}'),
        bulkActions: [
          if (canManage) ...const [
            BulkActionSpec(
                action: 'ACTIVATE', label: 'Activate', icon: Icons.check_circle_outline),
            BulkActionSpec(
                action: 'DEACTIVATE', label: 'Deactivate', icon: Icons.block),
          ],
          if (canSendEmail) sendEmailBulkAction(EmailEntityType.product),
        ],
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
        rowActions: canManage || canSendEmail
            ? (context, p) => [
                  if (canManage)
                    IconButton(
                      tooltip: 'Edit',
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      onPressed: () => openProductForm(context, ref, existing: p),
                    ),
                  sendEmailRowAction(context,
                      type: EmailEntityType.product,
                      entityId: p.id,
                      entityLabel: 'Product ${p.name}'),
                ]
            : null,
      ),
    );
  }
}

class ProductFormDialog extends ConsumerStatefulWidget {
  final Product? existing;
  const ProductFormDialog({super.key, this.existing});
  @override
  ConsumerState<ProductFormDialog> createState() => _ProductFormDialogState();
}

class _ProductFormDialogState extends ConsumerState<ProductFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _description;
  late final TextEditingController _price;
  late bool _active;
  bool _notify = false;
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
        // A box left empty means the product has no description, not that it has an empty one:
        // the list then reads "—" rather than an empty cell (CP-15).
        'description': optionalText(_description.text),
        'price': double.parse(_price.text.trim()),
        'active': _active,
      };
      final int id;
      if (widget.existing == null) {
        final res = await dio.post('/api/products', data: body);
        id = ((res.data as Map)['id'] as num).toInt();
      } else {
        id = widget.existing!.id;
        await dio.put('/api/products/$id', data: body);
      }
      if (mounted) Navigator.of(context).pop((id: id, notify: _notify));
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
                // Once the field has been touched its complaint goes as soon as it no longer
                // applies, rather than waiting for the next Save (CP-09).
                autovalidateMode: AutovalidateMode.onUserInteraction,
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
                autovalidateMode: AutovalidateMode.onUserInteraction,
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
              if (widget.existing == null)
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
              : const Text('Save'),
        ),
      ],
    );
  }
}
