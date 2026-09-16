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
import '../../shared/models/payment.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../disputes/dispute_create_dialog.dart';
import '../disputes/disputes_tab.dart';
import '../poc/poc_picker.dart';
import '../poc/poc_providers.dart';
import '../promises/promises_tab.dart';
import 'payments_screen.dart';

class PaymentDetailScreen extends ConsumerStatefulWidget {
  final int id;
  final String? initialTab;
  const PaymentDetailScreen({super.key, required this.id, this.initialTab});

  @override
  ConsumerState<PaymentDetailScreen> createState() => _PaymentDetailScreenState();
}

class _PaymentDetailScreenState extends ConsumerState<PaymentDetailScreen> {
  final _notes = TextEditingController();
  PocUser? _collectionPoc;
  int? _savedCollectionPocId;
  bool _seeded = false;
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

  void _seed(PaymentRecord p) {
    if (_seeded) return;
    _seeded = true;
    _notes.text = p.notes ?? '';
    _collectionPoc = p.collectionPoc;
    _savedCollectionPocId = p.collectionPoc?.id;
  }

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Discard unsaved changes?'),
        content: const Text('You have edits on this payment that have not been saved.'),
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
      final pocChanged = _collectionPoc != null && _collectionPoc!.id != _savedCollectionPocId;
      await ref.read(dioProvider).patch('/api/payments/${widget.id}', data: {
        'notes': _notes.text.trim(),
        // Sent only when changed: an unchanged POC may since have been deactivated, or this user
        // may not assign POCs, and neither should block a notes edit (AC-A5).
        if (pocChanged) 'collectionPocUserId': _collectionPoc!.id,
      });
      _savedCollectionPocId = _collectionPoc?.id;
      ref.invalidate(paymentDetailProvider(widget.id));
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      ref.invalidate(auditHistoryProvider);
      setState(() => _dirty = false);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Payment saved')));
      }
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(paymentDetailProvider(widget.id));
    final user = ref.watch(currentUserProvider);
    final canEdit = user?.has(Privileges.paymentManage) ?? false;
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
        message: notFoundMessage(e, 'payment'),
        onBack: () => context.go('/payments'),
      ),
      data: (payment) {
        _seed(payment);
        return PopScope(
          canPop: !_dirty,
          // Unsaved edits are asked about once, by goGuarded or else by the route's onExit.
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) goGuarded(context, '/payments');
          },
          child: DetailScaffold(
            title: 'Payment #${payment.id}',
            subtitle: '${payment.customerName} • ${formatDateTime(payment.paidAt)}',
            onBack: () => goGuarded(context, '/payments'),
            titleTrailing: [
              if (canSeePoc && payment.pocMissing) const PocMissingBadge(),
              PaymentStatusChip(status: payment.status),
              if (canSeeDisputes &&
                  user!.canRaiseDispute &&
                  payment.status != PaymentStatus.VOIDED)
                TextButton.icon(
                  icon: const Icon(Icons.flag_outlined, size: 18),
                  label: const Text('Raise dispute'),
                  onPressed: () => showDisputeDialog(
                    context: context,
                    targetType: DisputeTargetType.PAYMENT,
                    targetId: payment.id,
                    targetLabel: 'Payment #${payment.id}',
                  ),
                ),
            ],
            initialTabSlug: widget.initialTab,
            onTabChanged: (slug) => context.go('/payments/${widget.id}?tab=$slug'),
            top: _top(payment,
                canEdit: canEdit, canSeePoc: canSeePoc, canAssignPoc: canAssignPoc),
            tabs: [
              if (canSeeDisputes)
                DetailTab(
                  slug: 'disputes',
                  label: 'Disputes',
                  icon: Icons.flag_outlined,
                  builder: (context) => DisputesTab(
                    targetType: DisputeTargetType.PAYMENT,
                    targetId: payment.id,
                    targetLabel: 'Payment #${payment.id}',
                  ),
                ),
              if (canSeePromises)
                DetailTab(
                  slug: 'promises',
                  label: 'Payment Promise',
                  icon: Icons.handshake_outlined,
                  builder: (context) => PromisesTab(
                    customerId: payment.customerId,
                    customerName: payment.customerName,
                    paymentId: payment.id,
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
                        entityType: 'PAYMENT', entityId: payment.id, includeRelated: true),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _top(
    PaymentRecord p, {
    required bool canEdit,
    required bool canSeePoc,
    required bool canAssignPoc,
  }) {
    // Fields in columns, Save beside the figures and the allocations as compact rows, so the top
    // of an ordinary payment fits without scrolling. A phone stacks and scrolls the page.
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          _figuresAndSave(
            [
              _figure(context, 'Amount', formatMoney(p.amount)),
              _figure(context, 'Credit applied', formatMoney(p.creditApplied)),
              _figure(context, 'Method', p.method ?? '—'),
            ],
            canEdit: canEdit,
          ),
          const SizedBox(height: 12),
          DetailGrid(items: [
            if (canSeePoc)
              DetailGridItem(
                label: 'Collection POC',
                child: PocPicker(
                  type: PocType.COLLECTION,
                  value: _collectionPoc,
                  showLabel: false,
                  enabled: canEdit && canAssignPoc,
                  required: true,
                  onChanged: (u) => setState(() {
                    _collectionPoc = u;
                    _dirty = true;
                  }),
                ),
              ),
            DetailGridItem(
              label: 'Notes',
              span: 2,
              child: canEdit
                  ? TextField(
                      controller: _notes,
                      minLines: 1,
                      maxLines: 2,
                      inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.paymentNotes)],
                      decoration: const InputDecoration(isDense: true),
                      onChanged: (_) => setState(() => _dirty = true),
                    )
                  : ReadOnlyValue(p.notes ?? ''),
            ),
          ]),
          const SizedBox(height: 12),
          Text('Invoice allocations', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          if (p.invoices.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Text('No invoices linked — the whole amount went to customer credit'),
            )
          else
            _allocations(p),
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

  /// The figures on the left and, for someone who may edit, Save on the right of the same line —
  /// a row of its own under the fields was a whole line of height spent on one button.
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

  /// The invoices this payment went to: one short row each, and a row opens its invoice.
  Widget _allocations(PaymentRecord p) {
    final theme = Theme.of(context);
    final head = theme.textTheme.labelMedium;
    Widget cells(List<Widget> c) => Row(children: [
          Expanded(flex: 3, child: c[0]),
          Expanded(flex: 2, child: c[1]),
          Expanded(flex: 2, child: c[2]),
          Expanded(flex: 2, child: c[3]),
          Expanded(flex: 2, child: c[4]),
        ]);
    Widget right(String s, {TextStyle? style}) => Text(s, textAlign: TextAlign.right, style: style);
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 900),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 6),
            child: cells([
              Text('Invoice', style: head),
              Text('Status', style: head),
              right('Allocated', style: head),
              right('Total', style: head),
              right('Balance', style: head),
            ]),
          ),
          for (final inv in p.invoices)
            DecoratedBox(
              decoration:
                  BoxDecoration(border: Border(top: BorderSide(color: theme.dividerColor))),
              child: InkWell(
                onTap: () => goGuarded(context, '/invoices/${inv.id}'),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 6),
                  child: cells([
                    Text(inv.invoiceNumber,
                        style: TextStyle(
                            color: theme.colorScheme.primary, fontWeight: FontWeight.w600)),
                    Align(
                        alignment: Alignment.centerLeft,
                        child: InvoiceStatusChip(status: inv.status)),
                    right(formatMoney(inv.allocatedAmount)),
                    right(formatMoney(inv.total)),
                    right(formatMoney(inv.balance)),
                  ]),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _figure(BuildContext context, String label, String value) => Container(
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
                    ?.copyWith(fontWeight: FontWeight.w600)),
          ],
        ),
      );
}
