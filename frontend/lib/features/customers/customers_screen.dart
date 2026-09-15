import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promise_form_dialog.dart';

/// Every customer, for the dropdowns that still need a full list (invoice and payment forms).
final allCustomersProvider = FutureProvider.autoDispose<List<Customer>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/customers', queryParameters: {'size': 50, 'sort': 'name,asc'});
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(Customer.fromJson)
      .toList();
});

final customerDetailProvider =
    FutureProvider.autoDispose.family<Customer, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/customers/$id');
  return Customer.fromJson(res.data as Map<String, dynamic>);
});

class CustomersScreen extends ConsumerWidget {
  final TableQuery query;
  const CustomersScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.customerManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canPromise = user?.has(Privileges.promiseManage) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);
    final canAssignPoc = ref.watch(canAssignPocProvider);

    return Scaffold(
      body: DataTableScaffold<Customer>(
        entity: 'customers',
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New customer'),
              onPressed: () => _openCreateForm(context, ref),
            ),
        ],
        path: '/api/customers',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/customers').push(q),
        parse: Customer.fromJson,
        idOf: (c) => c.id,
        canExport: canExport,
        selectable: canManage,
        emptyMessage: 'No customers match this filter',
        onRowTap: (context, c) => context.go('/customers/${c.id}'),
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Customers', value: '${s['count'] ?? 0}'),
            SummaryTile(
              label: 'Total outstanding',
              value: formatMoneyCompact(s['totalOutstanding']),
              accent: Theme.of(context).colorScheme.error,
            ),
            SummaryTile(
                label: 'Total credit', value: formatMoneyCompact(s['totalCreditBalance'])),
            if (canSeePoc)
              SummaryTile(
                label: 'No Success POC',
                value: '${s['missingSuccessPocCount'] ?? 0}',
                icon: Icons.person_off_outlined,
              ),
            if (canSeePoc)
              SummaryTile(
                label: 'No Collection POC',
                value: '${s['missingCollectionPocCount'] ?? 0}',
                icon: Icons.person_off_outlined,
              ),
          ],
        ),
        bulkActions: [
          if (canAssignPoc)
            const BulkActionSpec(
              action: 'ADD_POC',
              label: 'Add POC',
              icon: Icons.person_add_alt,
              buildParams: _pickCustomerPocParams,
            ),
        ],
        columns: [
          TableColumnSpec(
            label: 'Name',
            sortKey: 'name',
            cell: (context, c) => Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(c.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                if (canSeePoc && c.pocMissing)
                  const Padding(
                    padding: EdgeInsets.only(left: 6),
                    child: PocMissingBadge(),
                  ),
              ],
            ),
          ),
          TableColumnSpec(
              label: 'Phone', sortKey: 'phone', cell: (context, c) => Text(c.phone ?? '—')),
          TableColumnSpec(
              label: 'Email', sortKey: 'email', cell: (context, c) => Text(c.email ?? '—')),
          TableColumnSpec(
            label: 'Outstanding',
            sortKey: 'outstanding',
            numeric: true,
            cell: (context, c) => Text(
              formatMoney(c.outstanding),
              style: TextStyle(
                  color: c.outstanding > 0 ? Theme.of(context).colorScheme.error : null),
            ),
          ),
          TableColumnSpec(
            label: 'Credit',
            sortKey: 'creditBalance',
            numeric: true,
            cell: (context, c) => Text(formatMoney(c.creditBalance)),
          ),
          if (canSeePoc)
            TableColumnSpec(
              label: 'Success POC',
              cell: (context, c) => Text(c.primarySuccessPoc?.user.display ?? '—'),
            ),
          if (canSeePoc)
            TableColumnSpec(
              label: 'Collection POC',
              cell: (context, c) => Text(c.primaryCollectionPoc?.user.display ?? '—'),
            ),
        ],
        rowActions: (context, c) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/customers/${c.id}'),
          ),
          if (canPromise && c.outstanding > 0)
            IconButton(
              tooltip: 'Raise promise',
              icon: const Icon(Icons.handshake_outlined, size: 18),
              onPressed: () => showPromiseDialog(
                context: context,
                customerId: c.id,
                customerName: c.name,
              ),
            ),
        ],
      ),
    );
  }

  Future<void> _openCreateForm(BuildContext context, WidgetRef ref) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => const CustomerFormDialog(),
    );
    if (saved == true) {
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      ref.invalidate(allCustomersProvider);
    }
  }
}

Future<Map<String, dynamic>?> _pickCustomerPocParams(BuildContext context) async {
  PocType type = PocType.COLLECTION;
  PocUser? picked;
  bool primary = false;
  final ok = await showDialog<bool>(
    context: context,
    builder: (_) => StatefulBuilder(
      builder: (context, setState) => AlertDialog(
        title: const Text('Add a POC to the selected customers'),
        content: SizedBox(
          width: 440,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<PocType>(
                initialValue: type,
                decoration: const InputDecoration(labelText: 'Kind'),
                items: const [
                  DropdownMenuItem(
                      value: PocType.SUCCESS, child: Text('Customer Success POC')),
                  DropdownMenuItem(
                      value: PocType.COLLECTION, child: Text('Collection POC')),
                ],
                onChanged: (t) => setState(() {
                  type = t ?? type;
                  picked = null;
                }),
              ),
              const SizedBox(height: 12),
              PocPicker(
                type: type,
                value: picked,
                required: true,
                onChanged: (u) => setState(() => picked = u),
              ),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                value: primary,
                title: const Text('Make primary'),
                onChanged: (v) => setState(() => primary = v ?? false),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(
            onPressed: picked == null ? null : () => Navigator.of(context).pop(true),
            child: const Text('Continue'),
          ),
        ],
      ),
    ),
  );
  if (ok != true || picked == null) return null;
  return {'userId': picked!.id, 'pocType': type.name, 'primary': '$primary'};
}

/// Create / edit form for the customer's own contact fields.
class CustomerFormDialog extends ConsumerStatefulWidget {
  final Customer? existing;
  const CustomerFormDialog({super.key, this.existing});

  @override
  ConsumerState<CustomerFormDialog> createState() => _CustomerFormDialogState();
}

class _CustomerFormDialogState extends ConsumerState<CustomerFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _phone;
  late final TextEditingController _email;
  late final TextEditingController _address;
  final _username = TextEditingController();
  final _password = TextEditingController();
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
  }

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _email.dispose();
    _address.dispose();
    _username.dispose();
    _password.dispose();
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
                TextFormField(
                    controller: _phone, decoration: const InputDecoration(labelText: 'Phone')),
                const SizedBox(height: 8),
                TextFormField(
                    controller: _email, decoration: const InputDecoration(labelText: 'Email')),
                const SizedBox(height: 8),
                TextFormField(
                    controller: _address,
                    decoration: const InputDecoration(labelText: 'Address'),
                    maxLines: 2),
                const Divider(height: 24),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    _isCreate
                        ? 'Login credentials (customer can sign in with these)'
                        : 'Set new password (optional)',
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
                ] else if (widget.existing?.username != null)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    leading: const Icon(Icons.person_outline),
                    title: Text(widget.existing!.username!),
                  ),
                TextFormField(
                  controller: _password,
                  decoration:
                      InputDecoration(labelText: _isCreate ? 'Password' : 'New password'),
                  obscureText: true,
                  validator:
                      _isCreate ? (v) => (v == null || v.isEmpty) ? 'Required' : null : null,
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
