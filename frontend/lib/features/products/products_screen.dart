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

/// Products screen with screen-local search state.
///
/// Each edit cancels the previous debounce timer, increments the request
/// generation and trims the text. Empty text restores the complete
/// [productsProvider] list immediately; otherwise a 300 ms one-shot timer
/// sends the server filter request. A completion whose captured generation
/// is no longer current is discarded entirely. An applicable success replaces
/// the results and clears the error together; an applicable failure keeps the
/// last-good results and sets only the error, with no automatic retry.
/// [productsProvider] itself stays unfiltered for the invoice form.
class ProductsScreen extends ConsumerStatefulWidget {
  const ProductsScreen({super.key});

  @override
  ConsumerState<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends ConsumerState<ProductsScreen> {
  static const Duration _debounceDelay = Duration(milliseconds: 300);

  final TextEditingController _search = TextEditingController();
  Timer? _debounce;
  int _generation = 0;
  String _query = '';
  List<Product>? _results;
  String? _searchError;

  bool get _isSearching => _query.isNotEmpty;

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  void _onQueryChanged(String text) {
    _debounce?.cancel();
    final int generation = ++_generation;
    final String trimmed = text.trim();
    if (trimmed.isEmpty) {
      setState(() {
        _query = '';
        _results = null;
        _searchError = null;
      });
      return;
    }
    setState(() => _query = trimmed);
    _debounce = Timer(_debounceDelay, () => _fetchResults(generation, trimmed));
  }

  Future<void> _fetchResults(int generation, String query) async {
    try {
      final dio = ref.read(dioProvider);
      final res =
          await dio.get('/api/products', queryParameters: {'search': query});
      if (!mounted || generation != _generation) return;
      final products = (res.data as List)
          .cast<Map<String, dynamic>>()
          .map(Product.fromJson)
          .toList();
      setState(() {
        _results = products;
        _searchError = null;
      });
    } catch (e) {
      if (!mounted || generation != _generation) return;
      setState(() => _searchError = apiErrorMessage(e));
    }
  }

  Future<void> _refresh() async {
    if (_isSearching) {
      await _fetchResults(++_generation, _query);
    } else {
      // Kept as a plain await so pull-to-refresh completes together with
      // the provider.
      // ignore: unused_result
      await ref.refresh(productsProvider.future);
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
            padding: const EdgeInsets.all(8),
            child: TextField(
              controller: _search,
              decoration: const InputDecoration(
                hintText: 'Search products',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: _onQueryChanged,
            ),
          ),
          if (_searchError != null && _results != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  _searchError!,
                  style:
                      TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            ),
          Expanded(child: _buildBody(async, canManage)),
        ],
      ),
    );
  }

  Widget _buildBody(AsyncValue<List<Product>> async, bool canManage) {
    if (_isSearching) {
      final results = _results;
      if (results == null) {
        final error = _searchError;
        if (error != null) return Center(child: Text(error));
        return const Center(child: CircularProgressIndicator());
      }
      if (results.isEmpty) {
        return const Center(child: Text('No products match'));
      }
      return RefreshIndicator(
        onRefresh: _refresh,
        child: _buildList(results, canManage),
      );
    }
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Failed: $e')),
      data: (list) {
        if (list.isEmpty) return const Center(child: Text('No products yet'));
        return RefreshIndicator(
          onRefresh: () async => ref.refresh(productsProvider.future),
          child: _buildList(list, canManage),
        );
      },
    );
  }

  Widget _buildList(List<Product> list, bool canManage) {
    return ListView.separated(
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
