import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/payment_term.dart';
import '../../shared/models/privileges.dart';
import '../approvals/pending_approval_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import '../poc/poc_name_cell.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promise_form_dialog.dart';
import 'payment_term_field.dart';

Future<List<Customer>> searchCustomers(Dio dio, String search) async {
  final res = await dio.get('/api/customers', queryParameters: {
    'size': 20,
    'sort': 'name,asc',
    if (search.isNotEmpty) 'filter': ['name:contains:$search'],
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(Customer.fromJson)
      .toList();
}

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
    final canSeePoc = ref.watch(canSeePocProvider);
    final canAssignPoc = ref.watch(canAssignPocProvider);
    final canSendEmail = ref.watch(canSendEmailProvider);
    final sendEmail = sendEmailPageAction(context, ref, type: EmailEntityType.customer);

    return Scaffold(
      body: DataTableScaffold<Customer>(
        entity: 'customers',
        actions: [
          if (sendEmail != null) sendEmail,
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
          if (canManage && canAssignPoc)
            const BulkActionSpec(
              action: 'ADD_POC',
              label: 'Add POC',
              icon: Icons.person_add_alt,
              buildParams: _pickCustomerPocParams,
            ),
          if (canSendEmail) sendEmailBulkAction(EmailEntityType.customer),
        ],
        columns: [
          TableColumnSpec(
            label: 'Name',
            sortKey: 'name',
            maxWidth: 320,
            // Wrap, not Row: on a phone card the badge belongs under the name rather than
            // under the Open icon (D-51).
            cell: (context, c) => Wrap(
              spacing: 6,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Tooltip(
                  message: c.name,
                  child: Text(c.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
                if (canSeePoc && c.pocMissing) const PocMissingBadge(),
                if (c.approvalPending) const ApprovalPendingDot(),
              ],
            ),
          ),
          TableColumnSpec(
              label: 'Phone', sortKey: 'phone', cell: (context, c) => Text(c.phone ?? '—')),
          TableColumnSpec(
            label: 'Email',
            sortKey: 'email',
            maxWidth: 320,
            cell: (context, c) => Tooltip(
              message: c.email ?? '',
              child: Text(c.email ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
          ),
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
              maxWidth: 180,
              cell: (context, c) => PocNameCell(user: c.primarySuccessPoc?.user),
            ),
          if (canSeePoc)
            TableColumnSpec(
              label: 'Collection POC',
              maxWidth: 180,
              cell: (context, c) => PocNameCell(user: c.primaryCollectionPoc?.user),
            ),
        ],
        rowActions: (context, c) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/customers/${c.id}'),
          ),
          sendEmailRowAction(context,
              type: EmailEntityType.customer,
              entityId: c.id,
              entityLabel: c.name,
              regionId: c.regionId),
          // hasIn, not has: the privilege is held somewhere, the account is in one branch,
          // and only the second question decides whether this button does anything (B1).
          if ((user?.hasIn(Privileges.promiseManage, c.regionId) ?? false) && c.outstanding > 0)
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
    final saved = await showDialog<CustomerSaved>(
      context: context,
      builder: (_) => const CustomerFormDialog(),
    );
    if (saved == null) return;
    ref.invalidate(tablePageProvider);
    ref.invalidate(tableSummaryProvider);
    if (!context.mounted) return;
    await notifyByEmailAfterSave(context,
        notify: saved.notify,
        type: EmailEntityType.customer,
        entityId: saved.id,
        event: EmailEvent.created);
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
              // No customerId: the selection can span as many branches as the filter does,
              // so the picker offers everybody assignable in any branch the caller works in and
              // a row in a branch this person does not work in is refused per row (B1).
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

typedef CustomerSaved = ({int id, bool notify});

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

  /// Null means the system default, which is what a customer starts on (D1).
  PaymentTerm? _term;

  /// Which branch this account is opened in. The server resolves null to the caller's only
  /// manageable branch and refuses it with fieldErrors.regionId == "Choose a region" the day a
  /// second one opens, so this is load-bearing and not decoration (B1).
  int? _regionId;
  bool _saving = false;
  bool _notify = false;
  String? _error;

  bool get _isCreate => widget.existing == null;

  @override
  void initState() {
    super.initState();
    _term = widget.existing?.paymentTerm;
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
      final int id;
      if (_isCreate) {
        final res = await dio.post('/api/customers', data: {
          'name': _name.text.trim(),
          // Omitted rather than sent as null when there is nothing to say, so the server's own
          // "your only branch" resolution still applies (B1).
          if (_regionId != null) 'regionId': _regionId,
          // A box left empty means the customer has no phone or address, not that it has an
          // empty one: the list then reads "—" rather than an empty cell (CP-15).
          'phone': optionalText(_phone.text),
          'email': optionalText(_email.text),
          'address': optionalText(_address.text),
          'paymentTerm': _term?.name,
          'username': _username.text.trim(),
          'password': _password.text,
        });
        id = (res.data as Map)['id'] as int;
      } else {
        id = widget.existing!.id;
        await dio.put('/api/customers/${widget.existing!.id}', data: {
          'name': _name.text.trim(),
          'phone': optionalText(_phone.text),
          'email': optionalText(_email.text),
          'address': optionalText(_address.text),
          'paymentTerm': _term?.name,
          if (_password.text.isNotEmpty) 'password': _password.text,
        });
      }
      if (mounted) Navigator.of(context).pop<CustomerSaved>((id: id, notify: _notify));
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
                  // Once the field has been touched its complaint goes as soon as it no longer
                  // applies, rather than waiting for the next Save (CP-09).
                  autovalidateMode: AutovalidateMode.onUserInteraction,
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
                const SizedBox(height: 8),
                PaymentTermField(
                  label: 'Payment terms',
                  value: _term,
                  onChanged: (t) => setState(() => _term = t),
                ),
                // Only on a create: an account changes branch through the move, which closes
                // its placement and opens the next one, not by editing a field (B1).
                if (_isCreate) ...[
                  const SizedBox(height: 8),
                  _RegionField(
                    value: _regionId,
                    onChanged: (id) => setState(() => _regionId = id),
                  ),
                ],
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
                    autovalidateMode: AutovalidateMode.onUserInteraction,
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
                  autovalidateMode: AutovalidateMode.onUserInteraction,
                  validator:
                      _isCreate ? (v) => (v == null || v.isEmpty) ? 'Required' : null : null,
                ),
                if (_isCreate)
                  NotifyByEmailCheckbox(
                    value: _notify,
                    onChanged: (v) => setState(() => _notify = v),
                    // Writing to the account is a write in the branch it is being opened in
                    // (B1). Null until one is chosen, which falls back to the global answer.
                    regionId: _regionId ?? widget.existing?.regionId,
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

/// Which branch a new account is opened in.
///
/// Offers only branches the caller may MANAGE, because naming one you cannot manage is a 403 and
/// not a 404 — the branch was NAMED, so no id space is being probed (D-46, B1). With exactly one
/// candidate it is chosen without asking, which is what the server does anyway.
class _RegionField extends ConsumerWidget {
  final int? value;
  final ValueChanged<int?> onChanged;

  const _RegionField({required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(manageableRegionsProvider);
    final regions = async.valueOrNull ?? const <RegionRef>[];
    if (async.isLoading) {
      return const InputDecorator(
        decoration: InputDecoration(labelText: 'Branch', isDense: true),
        child: LinearProgressIndicator(),
      );
    }
    if (regions.isEmpty) {
      // Nothing to choose, and the two reasons are not the same refusal.
      //
      // A WILDCARD holder may open an account in any branch — the server would accept one — but
      // the whole map is behind REGION_VIEW, which is a separate privilege, so this client has
      // no branch id to send. It omits regionId, and the server answers 400 with
      // fieldErrors.regionId "Choose a region" as soon as a second branch exists. Hiding the
      // field left that error pointing at a control that was not on the form, with nothing on
      // screen saying why. Say why (B1).
      final roster = ref.watch(myRegionsProvider).valueOrNull;
      final user = ref.watch(currentUserProvider);
      final wildcard = roster?.allRegions ?? user?.allRegions ?? false;
      return InputDecorator(
        decoration: InputDecoration(
          labelText: 'Branch',
          isDense: true,
          errorText: wildcard
              ? 'You may open an account in any branch, but this app cannot list them: that '
                  'needs the Regions privilege. Ask an administrator for it, or for a named '
                  'branch.'
              : 'You manage no branch, so an account cannot be opened. Ask an administrator '
                  'for Manage in the branch it belongs to.',
          errorMaxLines: 3,
        ),
        child: const Text('—'),
      );
    }
    if (regions.length == 1) {
      final only = regions.single;
      if (value != only.id) {
        WidgetsBinding.instance.addPostFrameCallback((_) => onChanged(only.id));
      }
      return InputDecorator(
        decoration: const InputDecoration(labelText: 'Branch', isDense: true),
        child: Text(only.label),
      );
    }
    return DropdownButtonFormField<int>(
      initialValue: value,
      isExpanded: true,
      decoration: const InputDecoration(labelText: 'Branch *', isDense: true),
      items: regions
          .map((r) => DropdownMenuItem(value: r.id, child: Text(r.label)))
          .toList(),
      onChanged: onChanged,
    );
  }
}
