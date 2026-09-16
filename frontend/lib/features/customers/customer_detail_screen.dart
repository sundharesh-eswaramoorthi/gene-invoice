import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../disputes/disputes_tab.dart';
import '../poc/customer_poc_editor.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promises_tab.dart';
import 'customers_screen.dart';

class CustomerDetailScreen extends ConsumerStatefulWidget {
  final int id;
  final String? initialTab;
  const CustomerDetailScreen({super.key, required this.id, this.initialTab});

  @override
  ConsumerState<CustomerDetailScreen> createState() => _CustomerDetailScreenState();
}

class _CustomerDetailScreenState extends ConsumerState<CustomerDetailScreen> {
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _email = TextEditingController();
  final _address = TextEditingController();
  bool _seeded = false;
  bool _dirty = false;
  bool _saving = false;
  String? _error;
  final Map<String, String?> _fieldErrors = {};
  late final UnsavedChanges _unsaved;

  @override
  void initState() {
    super.initState();
    _unsaved = ref.read(unsavedChangesProvider)..register(_confirmDiscard);
  }

  @override
  void dispose() {
    _unsaved.unregister(_confirmDiscard);
    _name.dispose();
    _phone.dispose();
    _email.dispose();
    _address.dispose();
    super.dispose();
  }

  void _seed(Customer c) {
    if (_seeded) return;
    _seeded = true;
    _name.text = c.name;
    _phone.text = c.phone ?? '';
    _email.text = c.email ?? '';
    _address.text = c.address ?? '';
  }

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Discard unsaved changes?'),
        content: const Text('You have edits on this customer that have not been saved.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep editing')),
          FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Discard')),
        ],
      ),
    );
    // Discarded edits are gone: the route's onExit, which runs next, must not ask again.
    if (ok == true) _dirty = false;
    return ok == true;
  }

  Future<void> _save() async {
    setState(() {
      _fieldErrors.clear();
      _error = null;
    });
    if (_name.text.trim().isEmpty) {
      setState(() => _fieldErrors['name'] = 'Name is required');
      return;
    }
    setState(() => _saving = true);
    try {
      await ref.read(dioProvider).put('/api/customers/${widget.id}', data: {
        'name': _name.text.trim(),
        'phone': _phone.text.trim(),
        'email': _email.text.trim(),
        'address': _address.text.trim(),
      });
      ref.invalidate(customerDetailProvider(widget.id));
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      ref.invalidate(auditHistoryProvider);
      setState(() => _dirty = false);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Customer saved')));
      }
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(customerDetailProvider(widget.id));
    final user = ref.watch(currentUserProvider);
    final canEdit = user?.has(Privileges.customerManage) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);
    final canAssignPoc = ref.watch(canAssignPocProvider);
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final canSeePromises = user?.has(Privileges.promiseView) ?? false;
    final canSeeDisputes = user?.hasAny(
            [Privileges.disputeView, Privileges.disputeCreate, Privileges.disputeManage]) ??
        false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'customer'),
        onBack: () => context.go('/customers'),
      ),
      data: (customer) {
        _seed(customer);
        return PopScope(
          canPop: !_dirty,
          // Unsaved edits are asked about once, by goGuarded or else by the route's onExit.
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) goGuarded(context, '/customers');
          },
          child: DetailScaffold(
            title: customer.name,
            subtitle: customer.username == null ? null : '@${customer.username}',
            onBack: () => goGuarded(context, '/customers'),
            titleTrailing: [
              if (canSeePoc && customer.pocMissing) const PocMissingBadge(),
            ],
            initialTabSlug: widget.initialTab,
            onTabChanged: (slug) => context.go('/customers/${widget.id}?tab=$slug'),
            top: _top(customer, canEdit: canEdit, canSeePoc: canSeePoc, canAssignPoc: canAssignPoc),
            tabs: [
              if (canSeeDisputes)
                DetailTab(
                  slug: 'disputes',
                  label: 'Disputes',
                  icon: Icons.flag_outlined,
                  builder: (context) => DisputesTab(customerId: customer.id),
                ),
              if (canSeePromises)
                DetailTab(
                  slug: 'promises',
                  label: 'Payment Promise',
                  icon: Icons.handshake_outlined,
                  builder: (context) =>
                      PromisesTab(customerId: customer.id, customerName: customer.name),
                ),
              if (canViewAudit)
                DetailTab(
                  slug: 'history',
                  label: 'History',
                  icon: Icons.history,
                  builder: (context) => SingleChildScrollView(
                    padding: const EdgeInsets.all(12),
                    child: AuditHistoryPanel(
                        entityType: 'CUSTOMER', entityId: customer.id, includeRelated: true),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _top(
    Customer c, {
    required bool canEdit,
    required bool canSeePoc,
    required bool canAssignPoc,
  }) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _figure(context, 'Outstanding', formatMoney(c.outstanding),
                  accent: c.outstanding > 0 ? Theme.of(context).colorScheme.error : null),
              _figure(context, 'Credit balance', formatMoney(c.creditBalance)),
            ],
          ),
          const SizedBox(height: 12),
          DetailField(
            label: 'Name',
            child: canEdit
                ? TextField(
                    controller: _name,
                    decoration: InputDecoration(errorText: _fieldErrors['name']),
                    onChanged: (v) => setState(() {
                      _dirty = true;
                      // Drop the server's complaint as soon as it no longer applies (D-52).
                      if (v.trim().isNotEmpty) _fieldErrors.remove('name');
                    }),
                  )
                : ReadOnlyValue(c.name),
          ),
          DetailField(
            label: 'Phone',
            child: canEdit
                ? TextField(
                    controller: _phone,
                    onChanged: (_) => setState(() => _dirty = true),
                  )
                : ReadOnlyValue(c.phone ?? ''),
          ),
          DetailField(
            label: 'Email',
            child: canEdit
                ? TextField(
                    controller: _email,
                    onChanged: (_) => setState(() => _dirty = true),
                  )
                : ReadOnlyValue(c.email ?? ''),
          ),
          DetailField(
            label: 'Address',
            child: canEdit
                ? TextField(
                    controller: _address,
                    maxLines: 2,
                    onChanged: (_) => setState(() => _dirty = true),
                  )
                : ReadOnlyValue(c.address ?? ''),
          ),
          if (canSeePoc) ...[
            const Divider(height: 24),
            DetailField(
              label: 'Customer Success POCs',
              child: CustomerPocEditor(
                customerId: c.id,
                type: PocType.SUCCESS,
                pocs: c.successPocs ?? const [],
                editable: canAssignPoc,
                onChanged: () {
                  ref.invalidate(customerDetailProvider(c.id));
                  ref.invalidate(customerPocsProvider(c.id));
                  ref.invalidate(tablePageProvider);
                  ref.invalidate(auditHistoryProvider);
                },
              ),
            ),
            DetailField(
              label: 'Collection POCs',
              child: CustomerPocEditor(
                customerId: c.id,
                type: PocType.COLLECTION,
                pocs: c.collectionPocs ?? const [],
                editable: canAssignPoc,
                onChanged: () {
                  ref.invalidate(customerDetailProvider(c.id));
                  ref.invalidate(customerPocsProvider(c.id));
                  ref.invalidate(tablePageProvider);
                  ref.invalidate(auditHistoryProvider);
                },
              ),
            ),
          ],
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child:
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          if (canEdit)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Row(
                children: [
                  FilledButton.icon(
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('Save changes'),
                    onPressed: (!_dirty || _saving) ? null : _save,
                  ),
                  const SizedBox(width: 12),
                  if (_dirty)
                    Text('Unsaved changes',
                        style: TextStyle(color: Theme.of(context).colorScheme.tertiary)),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _figure(BuildContext context, String label, String value, {Color? accent}) =>
      Container(
        width: 170,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelMedium),
            const SizedBox(height: 4),
            Text(value,
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(color: accent, fontWeight: FontWeight.w600)),
          ],
        ),
      );
}
