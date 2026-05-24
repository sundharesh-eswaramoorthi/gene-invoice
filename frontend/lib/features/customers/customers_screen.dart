import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';

final customersProvider = FutureProvider.autoDispose<List<Customer>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/customers');
  return (res.data as List).cast<Map<String, dynamic>>().map(Customer.fromJson).toList();
});

class CustomersScreen extends ConsumerWidget {
  const CustomersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.customerManage) ?? false;
    final async = ref.watch(customersProvider);

    return Scaffold(
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              icon: const Icon(Icons.add),
              label: const Text('New customer'),
              onPressed: () => _openForm(context, ref, null),
            )
          : null,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) {
          if (list.isEmpty) {
            return const Center(child: Text('No customers yet'));
          }
          return RefreshIndicator(
            onRefresh: () async => ref.refresh(customersProvider.future),
            child: ListView.separated(
              padding: const EdgeInsets.all(8),
              itemCount: list.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final c = list[i];
                final subtitleBits = [
                  if (c.username != null) '@${c.username}',
                  if (c.phone != null && c.phone!.isNotEmpty) c.phone!,
                  if (c.email != null && c.email!.isNotEmpty) c.email!,
                ];
                return ListTile(
                  title: Text(c.name),
                  subtitle: Text(subtitleBits.join(' • ')),
                  trailing: Wrap(
                    spacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      if (c.creditBalance > 0)
                        Chip(label: Text('Credit ${c.creditBalance.toStringAsFixed(2)}')),
                      if (canManage)
                        IconButton(
                          icon: const Icon(Icons.edit_outlined),
                          onPressed: () => _openForm(context, ref, c),
                        ),
                    ],
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  Future<void> _openForm(BuildContext context, WidgetRef ref, Customer? existing) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _CustomerForm(existing: existing),
    );
    if (saved == true) ref.invalidate(customersProvider);
  }
}

class _CustomerForm extends ConsumerStatefulWidget {
  final Customer? existing;
  const _CustomerForm({this.existing});
  @override
  ConsumerState<_CustomerForm> createState() => _CustomerFormState();
}

class _CustomerFormState extends ConsumerState<_CustomerForm> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _phone;
  late final TextEditingController _email;
  late final TextEditingController _address;
  late final TextEditingController _username;
  late final TextEditingController _password;
  bool _saving = false;
  String? _error;

  bool get _isCreate => widget.existing == null;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.existing?.name ?? '');
    _phone = TextEditingController(text: widget.existing?.phone ?? '');
    _email = TextEditingController(text: widget.existing?.email ?? '');
    _address = TextEditingController(text: widget.existing?.address ?? '');
    _username = TextEditingController();
    _password = TextEditingController();
  }

  @override
  void dispose() {
    _name.dispose(); _phone.dispose(); _email.dispose(); _address.dispose();
    _username.dispose(); _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() { _saving = true; _error = null; });
    try {
      final dio = ref.read(dioProvider);
      if (_isCreate) {
        await dio.post('/api/customers', data: {
          'name': _name.text.trim(),
          'phone': _phone.text.trim(),
          'email': _email.text.trim(),
          'address': _address.text.trim(),
          'username': _username.text.trim(),
          'password': _password.text,
        });
      } else {
        await dio.put('/api/customers/${widget.existing!.id}', data: {
          'name': _name.text.trim(),
          'phone': _phone.text.trim(),
          'email': _email.text.trim(),
          'address': _address.text.trim(),
          if (_password.text.isNotEmpty) 'password': _password.text,
        });
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
      title: Text(_isCreate ? 'New customer' : 'Edit customer'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: _name,
                  decoration: const InputDecoration(labelText: 'Name'),
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(controller: _phone, decoration: const InputDecoration(labelText: 'Phone')),
                const SizedBox(height: 8),
                TextFormField(controller: _email, decoration: const InputDecoration(labelText: 'Email')),
                const SizedBox(height: 8),
                TextFormField(controller: _address, decoration: const InputDecoration(labelText: 'Address'), maxLines: 2),
                const Divider(height: 24),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    _isCreate ? 'Login credentials (customer can sign in with these)' : 'Set new password (optional)',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
                const SizedBox(height: 6),
                if (_isCreate) ...[
                  TextFormField(
                    controller: _username,
                    decoration: const InputDecoration(labelText: 'Username'),
                    validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                  ),
                  const SizedBox(height: 8),
                ] else if (widget.existing?.username != null) ...[
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    leading: const Icon(Icons.person_outline),
                    title: Text(widget.existing!.username!),
                  ),
                ],
                TextFormField(
                  controller: _password,
                  decoration: InputDecoration(
                    labelText: _isCreate ? 'Password' : 'New password',
                  ),
                  obscureText: true,
                  validator: _isCreate
                      ? (v) => (v == null || v.isEmpty) ? 'Required' : null
                      : null,
                ),
                if (_error != null) Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
              ],
            ),
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
