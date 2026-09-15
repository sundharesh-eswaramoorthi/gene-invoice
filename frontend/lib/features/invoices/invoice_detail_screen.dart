import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../disputes/dispute_create_dialog.dart';
import '../disputes/disputes_tab.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promises_tab.dart';
import 'invoices_screen.dart';

class InvoiceDetailScreen extends ConsumerStatefulWidget {
  final int id;
  final String? initialTab;
  const InvoiceDetailScreen({super.key, required this.id, this.initialTab});

  @override
  ConsumerState<InvoiceDetailScreen> createState() => _InvoiceDetailScreenState();
}

class _InvoiceDetailScreenState extends ConsumerState<InvoiceDetailScreen> {
  final _notes = TextEditingController();
  PocUser? _salesPoc;
  int? _savedSalesPocId;
  bool _loadedInto = false;
  bool _dirty = false;
  bool _saving = false;
  String? _error;
  late final UnsavedChanges _unsaved;

  @override
  void initState() {
    super.initState();
    _unsaved = ref.read(unsavedChangesProvider)..register(_confirmDiscard);
  }

  @override
  void dispose() {
    _unsaved.unregister(_confirmDiscard);
    _notes.dispose();
    super.dispose();
  }

  void _seed(InvoiceDetail inv) {
    if (_loadedInto) return;
    _loadedInto = true;
    _notes.text = inv.notes ?? '';
    _salesPoc = inv.salesPoc;
    _savedSalesPocId = inv.salesPoc?.id;
  }

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Discard unsaved changes?'),
        content: const Text('You have edits on this invoice that have not been saved.'),
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
      _saving = true;
      _error = null;
    });
    try {
      final pocChanged = _salesPoc != null && _salesPoc!.id != _savedSalesPocId;
      await ref.read(dioProvider).patch('/api/invoices/${widget.id}', data: {
        'notes': _notes.text.trim(),
        // Sent only when changed: an unchanged POC may since have been deactivated, or this user
        // may not assign POCs, and neither should block a notes edit (AC-A5).
        if (pocChanged) 'salesPocUserId': _salesPoc!.id,
      });
      _savedSalesPocId = _salesPoc?.id;
      // Top section, tabs and the list the user came from all pick up the new values (AC-C5).
      ref.invalidate(invoiceDetailProvider(widget.id));
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      ref.invalidate(auditHistoryProvider);
      setState(() => _dirty = false);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Invoice saved')));
      }
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(invoiceDetailProvider(widget.id));
    final user = ref.watch(currentUserProvider);
    final canEdit = user?.has(Privileges.invoiceManage) ?? false;
    final canAssignPoc = ref.watch(canAssignPocProvider);
    final canSeePoc = ref.watch(canSeePocProvider);
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final canSeePromises = user?.has(Privileges.promiseView) ?? false;
    final canSeeDisputes = user?.hasAny(
            [Privileges.disputeView, Privileges.disputeCreate, Privileges.disputeManage]) ??
        false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'invoice'),
        onBack: () => context.go('/invoices'),
      ),
      data: (inv) {
        _seed(inv);
        return PopScope(
          canPop: !_dirty,
          // Unsaved edits are asked about once, by goGuarded or else by the route's onExit.
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) goGuarded(context, '/invoices');
          },
          child: DetailScaffold(
            title: inv.invoiceNumber,
            subtitle: '${inv.customerName} • ${formatDate(inv.invoiceDate)}',
            onBack: () => goGuarded(context, '/invoices'),
            titleTrailing: [
              if (canSeePoc && inv.pocMissing) const PocMissingBadge(),
              Chip(label: Text(statusLabel(inv.status))),
              if (canSeeDisputes && user!.has(Privileges.disputeCreate))
                TextButton.icon(
                  icon: const Icon(Icons.flag_outlined, size: 18),
                  label: const Text('Raise dispute'),
                  onPressed: () => showDisputeDialog(
                    context: context,
                    targetType: DisputeTargetType.INVOICE,
                    targetId: inv.id,
                    targetLabel: inv.invoiceNumber,
                  ),
                ),
            ],
            initialTabSlug: widget.initialTab,
            onTabChanged: (slug) =>
                context.go('/invoices/${widget.id}?tab=$slug'),
            top: _top(inv, canEdit: canEdit, canAssignPoc: canAssignPoc, canSeePoc: canSeePoc),
            tabs: [
              if (canSeeDisputes)
                DetailTab(
                  slug: 'disputes',
                  label: 'Disputes',
                  icon: Icons.flag_outlined,
                  builder: (context) => DisputesTab(
                    targetType: DisputeTargetType.INVOICE,
                    targetId: inv.id,
                    targetLabel: inv.invoiceNumber,
                  ),
                ),
              if (canSeePromises)
                DetailTab(
                  slug: 'promises',
                  label: 'Payment Promise',
                  icon: Icons.handshake_outlined,
                  builder: (context) => PromisesTab(
                    customerId: inv.customerId,
                    customerName: inv.customerName,
                    invoiceId: inv.id,
                  ),
                ),
              if (canViewAudit)
                DetailTab(
                  slug: 'history',
                  label: 'History',
                  icon: Icons.history,
                  builder: (context) => SingleChildScrollView(
                    padding: const EdgeInsets.all(12),
                    child: AuditHistoryPanel(
                        entityType: 'INVOICE', entityId: inv.id, includeRelated: true),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _top(
    InvoiceDetail inv, {
    required bool canEdit,
    required bool canAssignPoc,
    required bool canSeePoc,
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
              _figure(context, 'Total', formatMoney(inv.total)),
              _figure(context, 'Paid', formatMoney(inv.paidAmount)),
              _figure(context, 'Balance', formatMoney(inv.balance),
                  accent: inv.balance > 0 ? Theme.of(context).colorScheme.error : null),
            ],
          ),
          const SizedBox(height: 12),
          if (canSeePoc)
            DetailField(
              label: 'Sales POC',
              child: PocPicker(
                type: PocType.SALES,
                value: _salesPoc,
                enabled: canEdit && canAssignPoc,
                required: true,
                onChanged: (u) => setState(() {
                  _salesPoc = u;
                  _dirty = true;
                }),
              ),
            ),
          DetailField(
            label: 'Notes',
            child: canEdit
                ? TextField(
                    controller: _notes,
                    maxLines: 2,
                    inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.invoiceNotes)],
                    decoration: const InputDecoration(hintText: 'Internal notes'),
                    onChanged: (_) => setState(() => _dirty = true),
                  )
                : ReadOnlyValue(inv.notes ?? ''),
          ),
          const SizedBox(height: 8),
          Text('Line items', style: Theme.of(context).textTheme.titleSmall),
          const Text(
            'Line items change only through an approved dispute.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 4),
          ...inv.items.map((it) => ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(it.productName),
                subtitle: Text('${it.quantity} × ${formatMoney(it.unitPrice)}'),
                trailing: Text(formatMoney(it.lineTotal)),
              )),
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

  Widget _figure(BuildContext context, String label, String value, {Color? accent}) => Container(
        width: 160,
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
