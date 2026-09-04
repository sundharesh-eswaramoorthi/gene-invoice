import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/product.dart';
import '../auth/auth_controller.dart';

final productsProvider = FutureProvider.autoDispose<List<Product>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/products');
  return (res.data as List).cast<Map<String, dynamic>>().map(Product.fromJson).toList();
});

class ProductsScreen extends ConsumerStatefulWidget {
  const ProductsScreen({super.key});

  @override
  ConsumerState<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends ConsumerState<ProductsScreen> {
  final TextEditingController _searchController = TextEditingController();
  Timer? _debounce;
  int _generation = 0;

  /// The list returned by the last successful search; null when no filter is
  /// active, in which case the shared [productsProvider] result is displayed.
  List<Product>? _lastSuccessfulList;
  String? _searchError;
  bool _searchPending = false;

  String get _currentQuery => _searchController.text.trim();

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String value) {
    _generation++;
    _debounce?.cancel();
    final query = value.trim();
    if (query.isEmpty) {
      // An empty or whitespace-only box means no filtering: restore the
      // unfiltered all-products source immediately, without a request.
      setState(() {
        _lastSuccessfulList = null;
        _searchError = null;
        _searchPending = false;
      });
      return;
    }
    setState(() => _searchPending = true);
    final generation = _generation;
    _debounce = Timer(
      const Duration(milliseconds: 300),
      () => _runSearch(query, generation),
    );
  }

  void _clearSearch() {
    _searchController.clear();
    _onSearchChanged(_searchController.text);
  }

  Future<void> _runSearch(String query, int generation) async {
    bool isCurrent() => generation == _generation && query == _currentQuery;
    try {
      final dio = ref.read(dioProvider);
      final res = await dio.get('/api/products', queryParameters: {'search': query});
      if (!mounted || !isCurrent()) return; // superseded: discard silently
      setState(() {
        _lastSuccessfulList = (res.data as List)
            .cast<Map<String, dynamic>>()
            .map(Product.fromJson)
            .toList();
        _searchError = null;
        _searchPending = false;
      });
    } catch (e) {
      if (!mounted || !isCurrent()) return; // superseded: discard silently
      setState(() {
        // _lastSuccessfulList is left untouched and no retry is scheduled.
        _searchError = apiErrorMessage(e);
        _searchPending = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.productManage) ?? false;
    final async = ref.watch(productsProvider);

    return Scaffold(
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              icon: const Icon(Icons.add),
              label: const Text('New product'),
              onPressed: () => _openForm(context, ref, null),
            )
          : null,
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: TextField(
              controller: _searchController,
              onChanged: _onSearchChanged,
              decoration: InputDecoration(
                hintText: 'Search products',
                prefixIcon: const Icon(Icons.search),
                border: const OutlineInputBorder(),
                suffixIcon: _searchPending
                    ? const Padding(
                        padding: EdgeInsets.all(12),
                        child: SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      )
                    : _searchController.text.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear),
                            onPressed: _clearSearch,
                          )
                        : null,
              ),
            ),
          ),
          if (_searchError != null)
            Container(
              width: double.infinity,
              color: Theme.of(context).colorScheme.errorContainer,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Text(
                _searchError!,
                style: TextStyle(color: Theme.of(context).colorScheme.onErrorContainer),
              ),
            ),
          Expanded(
            child: _lastSuccessfulList != null
                ? _buildList(context, ref, _lastSuccessfulList!, canManage)
                : async.when(
                    loading: () => const Center(child: CircularProgressIndicator()),
                    error: (e, _) => Center(child: Text('Failed: $e')),
                    data: (list) => _buildList(context, ref, list, canManage),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildList(BuildContext context, WidgetRef ref, List<Product> list, bool canManage) {
    if (list.isEmpty) {
      return Center(
        child: Text(_currentQuery.isEmpty ? 'No products yet' : 'No products match your search'),
      );
    }
    return RefreshIndicator(
      onRefresh: () async => ref.refresh(productsProvider.future),
      child: ListView.separated(
        padding: const EdgeInsets.all(8),
        itemCount: list.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, i) {
          final p = list[i];
          return ListTile(
            title: Text(p.name),
            subtitle: Text(p.description ?? ''),
            trailing: Wrap(
              spacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text(p.price.toStringAsFixed(2),
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                if (!p.active) const Chip(label: Text('Inactive')),
                if (canManage)
                  IconButton(
                    icon: const Icon(Icons.edit_outlined),
                    onPressed: () => _openForm(context, ref, p),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }

  Future<void> _openForm(BuildContext context, WidgetRef ref, Product? existing) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _ProductForm(existing: existing),
    );
    if (saved == true) ref.invalidate(productsProvider);
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
    _name.dispose(); _description.dispose(); _price.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() { _saving = true; _error = null; });
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
              if (_error != null) Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _saving ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Save'),
        ),
      ],
    );
  }
}
