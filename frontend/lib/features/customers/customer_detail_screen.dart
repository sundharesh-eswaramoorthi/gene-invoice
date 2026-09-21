import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/payment_term.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../disputes/disputes_tab.dart';
import '../documents/document_actions.dart';
import '../email/email_actions.dart';
import '../poc/customer_poc_editor.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promises_tab.dart';
import 'customers_screen.dart';
import 'payment_term_field.dart';

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

  /// The terms this customer's new invoices take. Null means the system default (D1).
  PaymentTerm? _term;
  bool _seeded = false;
  bool _dirty = false;
  bool _saving = false;
  bool _deleting = false;
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
    _term = c.paymentTerm;
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
        // A box cleared means the customer has no phone or address, not that it has an empty
        // one: the page and the list then read "—" rather than nothing at all (CP-15).
        'phone': optionalText(_phone.text),
        'email': optionalText(_email.text),
        'address': optionalText(_address.text),
        // Null is a legitimate value meaning "use the system default", so it is sent as one.
        // Invoices already raised keep the date they were given (D1).
        'paymentTerm': _term?.name,
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

  /// Removes the customer, after a confirmation that names it and says what goes with it.
  ///
  /// `DELETE /api/customers/{id}` has always existed behind `CUSTOMER_MANAGE`, and nothing in the
  /// app called it: a customer entered by mistake — a duplicate, the wrong name, the wrong login
  /// — was permanent, and went on appearing in every list, picker and recipient list (CP-03).
  Future<void> _delete(Customer c) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Delete this customer?'),
        content: Text(
          '${c.name} is removed for good, and with it '
          '${c.username == null ? 'its login' : 'its login @${c.username}'}, its POC seats, and '
          'every document on it and on its invoices and payments. This cannot be undone.\n\n'
          'A customer that still has invoices or payments is refused, and nothing changes.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            style:
                FilledButton.styleFrom(backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Delete customer'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _deleting = true;
      _error = null;
    });
    try {
      await ref.read(dioProvider).delete('/api/customers/${widget.id}');
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      _dirty = false;
      messenger.showSnackBar(SnackBar(content: Text('${c.name} deleted')));
      if (mounted) context.go('/customers');
    } catch (e) {
      if (mounted) setState(() => _error = _deleteRefusal(e));
    } finally {
      if (mounted) setState(() => _deleting = false);
    }
  }

  String _deleteRefusal(Object e) =>
      (e is DioException && e.response?.statusCode == 409)
          ? 'This customer has invoices or payments on it and cannot be deleted. '
              'Nothing was changed.'
          : apiErrorMessage(e);

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
        // A customer login reaches its own customer here, and may write about it too (E13).
        final sendEmail = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.customer, entityId: customer.id, entityLabel: customer.name);
        final documentsTab = documentsDetailTab(ref,
            type: DocumentEntityType.customer, entityId: customer.id, entityLabel: customer.name);
        final emailTab = emailDetailTab(ref,
            type: EmailEntityType.customer, entityId: customer.id, entityLabel: customer.name);
        return PopScope(
          canPop: !_dirty,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) goGuarded(context, '/customers');
          },
          child: DetailScaffold(
            title: customer.name,
            subtitle: customer.username == null ? null : '@${customer.username}',
            onBack: () => goGuarded(context, '/customers'),
            titleTrailing: [
              if (canSeePoc && customer.pocMissing) const PocMissingBadge(),
              if (sendEmail != null) sendEmail,
              // Only for those who may manage customers: the endpoint is behind the same
              // privilege, so anyone else would only be offered a 403 (CP-03).
              if (canEdit)
                OutlinedButton.icon(
                  icon: const Icon(Icons.delete_outline, size: 18),
                  label: const Text('Delete customer'),
                  style: OutlinedButton.styleFrom(
                      foregroundColor: Theme.of(context).colorScheme.error),
                  onPressed: _deleting ? null : () => _delete(customer),
                ),
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
              if (documentsTab != null) documentsTab,
              if (emailTab != null) emailTab,
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
    void pocsChanged() {
      ref.invalidate(customerDetailProvider(c.id));
      ref.invalidate(customerPocsProvider(c.id));
      ref.invalidate(tablePageProvider);
      ref.invalidate(auditHistoryProvider);
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          _figuresAndSave(
            [
              _figure(context, 'Outstanding', formatMoney(c.outstanding),
                  accent: c.outstanding > 0 ? Theme.of(context).colorScheme.error : null,
                  note: c.overdueAmount > 0
                      ? 'of which ${formatMoney(c.overdueAmount)} overdue'
                      : null),
              _figure(context, 'Credit balance', formatMoney(c.creditBalance)),
            ],
            canEdit: canEdit,
          ),
          const SizedBox(height: 12),
          DetailGrid(items: [
            DetailGridItem(
              label: 'Name',
              child: canEdit
                  ? TextField(
                      controller: _name,
                      decoration:
                          InputDecoration(isDense: true, errorText: _fieldErrors['name']),
                      onChanged: (v) => setState(() {
                        _dirty = true;
                        // Drop the server's complaint as soon as it no longer applies (D-52).
                        if (v.trim().isNotEmpty) _fieldErrors.remove('name');
                      }),
                    )
                  : ReadOnlyValue(c.name),
            ),
            DetailGridItem(
              label: 'Email',
              child: canEdit
                  ? TextField(
                      controller: _email,
                      decoration: const InputDecoration(isDense: true),
                      onChanged: (_) => setState(() => _dirty = true),
                    )
                  : ReadOnlyValue(c.email ?? ''),
            ),
            DetailGridItem(
              label: 'Phone',
              child: canEdit
                  ? TextField(
                      controller: _phone,
                      decoration: const InputDecoration(isDense: true),
                      onChanged: (_) => setState(() => _dirty = true),
                    )
                  : ReadOnlyValue(c.phone ?? ''),
            ),
            DetailGridItem(
              label: 'Address',
              child: canEdit
                  ? TextField(
                      controller: _address,
                      minLines: 1,
                      maxLines: 2,
                      decoration: const InputDecoration(isDense: true),
                      onChanged: (_) => setState(() => _dirty = true),
                    )
                  : ReadOnlyValue(c.address ?? ''),
            ),
            // What this customer's new invoices default to (US-A1). Invoices already raised keep
            // the date they were given, whatever this is changed to (D1).
            DetailGridItem(
              label: 'Payment terms',
              child: canEdit
                  ? PaymentTermField(
                      value: _term,
                      onChanged: (t) => setState(() {
                        _term = t;
                        _dirty = true;
                      }),
                    )
                  : ReadOnlyValue(c.termsLabel),
            ),
            if (canSeePoc)
              DetailGridItem(
                label: 'Customer Success POCs',
                child: CustomerPocEditor(
                  customerId: c.id,
                  type: PocType.SUCCESS,
                  pocs: c.successPocs ?? const [],
                  editable: canAssignPoc,
                  onChanged: pocsChanged,
                ),
              ),
            if (canSeePoc)
              DetailGridItem(
                label: 'Collection POCs',
                child: CustomerPocEditor(
                  customerId: c.id,
                  type: PocType.COLLECTION,
                  pocs: c.collectionPocs ?? const [],
                  editable: canAssignPoc,
                  onChanged: pocsChanged,
                ),
              ),
          ]),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child:
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
        ],
      ),
    );
  }

  Widget _figuresAndSave(List<Widget> figures, {required bool canEdit}) => Wrap(
        spacing: 12,
        runSpacing: 12,
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Wrap(spacing: 12, runSpacing: 12, children: figures),
          if (canEdit)
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_dirty)
                  Padding(
                    padding: const EdgeInsets.only(right: 12),
                    child: Text('Unsaved changes',
                        style: TextStyle(color: Theme.of(context).colorScheme.tertiary)),
                  ),
                FilledButton.icon(
                  icon: const Icon(Icons.save_outlined),
                  label: const Text('Save changes'),
                  onPressed: (!_dirty || _saving) ? null : _save,
                ),
              ],
            ),
        ],
      );

  Widget _figure(BuildContext context, String label, String value,
          {Color? accent, String? note}) =>
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
            if (note != null)
              Text(note,
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: Theme.of(context).colorScheme.error)),
          ],
        ),
      );
}
