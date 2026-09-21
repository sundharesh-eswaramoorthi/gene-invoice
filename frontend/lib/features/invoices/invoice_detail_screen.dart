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
import '../../shared/models/payment_term.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../disputes/dispute_create_dialog.dart';
import '../disputes/disputes_tab.dart';
import '../documents/document_actions.dart';
import '../tasks/task_actions.dart';
import '../email/email_actions.dart';
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
  PaymentTerm? _term;
  DateTime? _dueDate;
  PaymentTerm? _savedTerm;
  DateTime? _savedDueDate;
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
    _term = inv.paymentTerm;
    _dueDate = inv.dueDate;
    _savedTerm = inv.paymentTerm;
    _savedDueDate = inv.dueDate;
  }

  /// The day the current terms were counted from, worked back from the due date the server
  /// stored. Read that way rather than from the invoice timestamp, so a recomputed date is the
  /// one the save will come back with. Custom terms and an invoice with no due date leave
  /// nothing to work back from, and then it is the invoice's UTC day — the day the server counts
  /// from (§2.1) — never this browser's reading of the instant.
  DateTime _termsCountedFrom(InvoiceDetail inv) =>
      (inv.dueDate == null ? null : inv.paymentTerm?.basisOf(inv.dueDate!)) ??
      utcDay(inv.invoiceDate)!;

  /// Named terms recompute the date; Custom keeps whatever is showing (§2.2, US-A3).
  void _termChanged(PaymentTerm? term, InvoiceDetail inv) {
    if (term == null) return;
    setState(() {
      _term = term;
      final due = term.due(_termsCountedFrom(inv));
      if (due != null) _dueDate = due;
      _dirty = true;
    });
  }

  Future<void> _pickDueDate(InvoiceDetail inv) async {
    final basis = _termsCountedFrom(inv);
    final picked = await showDatePicker(
      context: context,
      initialDate: _dueDate ?? basis,
      // Earlier than the invoice date is a 400 from the server, so it cannot be picked (AC-A5).
      firstDate: DateTime(basis.year, basis.month, basis.day),
      lastDate: DateTime(basis.year + 5, basis.month, basis.day),
    );
    if (picked == null) return;
    // A date chosen by hand is an override, whatever the terms were (US-A3).
    setState(() {
      _dueDate = DateTime(picked.year, picked.month, picked.day);
      _term = PaymentTerm.CUSTOM;
      _dirty = true;
    });
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
      final termsChanged = _term != _savedTerm || _dueDate != _savedDueDate;
      await ref.read(dioProvider).patch('/api/invoices/${widget.id}', data: {
        'notes': _notes.text.trim(),
        // Sent only when changed: an unchanged POC may since have been deactivated, or this user
        // may not assign POCs, and neither should block a notes edit (AC-A5).
        if (pocChanged) 'salesPocUserId': _salesPoc!.id,
        // One or the other, never both: a named term is the rule the server recomputes the date
        // from, a date of its own is the override it records as Custom (§2.2).
        if (termsChanged && _term == PaymentTerm.CUSTOM && _dueDate != null)
          'dueDate': formatDate(_dueDate),
        if (termsChanged && _term != null && _term != PaymentTerm.CUSTOM)
          'paymentTerm': _term!.name,
      });
      _savedSalesPocId = _salesPoc?.id;
      _savedTerm = _term;
      _savedDueDate = _dueDate;
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
        final sendEmail = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.invoice, entityId: inv.id, entityLabel: inv.invoiceNumber);
        final tasksTab = tasksDetailTab(ref,
            type: TaskEntityType.invoice, entityId: inv.id, entityLabel: inv.invoiceNumber);
        final documentsTab = documentsDetailTab(ref,
            type: DocumentEntityType.invoice, entityId: inv.id, entityLabel: inv.invoiceNumber);
        final emailTab = emailDetailTab(ref,
            type: EmailEntityType.invoice, entityId: inv.id, entityLabel: inv.invoiceNumber);
        return PopScope(
          canPop: !_dirty,
          // Unsaved edits are asked about once, by goGuarded or else by the route's onExit.
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop) goGuarded(context, '/invoices');
          },
          child: DetailScaffold(
            title: inv.invoiceNumber,
            subtitle: [
              inv.customerName,
              // The UTC day, because the due date beside it was counted from that day: read in
              // the browser's zone the pair would not add up to the stated terms (§2.1).
              formatUtcDate(inv.invoiceDate),
              if (inv.dueDate != null) 'due ${formatDate(inv.dueDate)}',
            ].join(' • '),
            onBack: () => goGuarded(context, '/invoices'),
            titleTrailing: [
              if (canSeePoc && inv.pocMissing) const PocMissingBadge(),
              InvoiceStatusChip(status: inv.status),
              // Not a status of its own: it is true of an unpaid invoice whose date has passed,
              // and says by how long (US-A4).
              if (inv.overdue) OverdueBadge(daysOverdue: inv.daysOverdue),
              if (canSeeDisputes && user!.canRaiseDispute)
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
              if (sendEmail != null) sendEmail,
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
                    invoice: inv,
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
              if (documentsTab != null) documentsTab,
              if (tasksTab != null) tasksTab,
              if (emailTab != null) emailTab,
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
    // Fields in columns, Save beside the figures and the line items as a compact table, so the
    // top of an ordinary invoice fits without scrolling. A phone stacks and scrolls the page.
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          _figuresAndSave(
            [
              _figure(context, 'Total', formatMoney(inv.total)),
              _figure(context, 'Paid', formatMoney(inv.paidAmount)),
              _figure(context, 'Balance', formatMoney(inv.balance),
                  accent: inv.balance > 0 ? Theme.of(context).colorScheme.error : null),
            ],
            canEdit: canEdit,
          ),
          const SizedBox(height: 12),
          DetailGrid(items: [
            if (canSeePoc)
              DetailGridItem(
                label: 'Sales POC',
                child: PocPicker(
                  type: PocType.SALES,
                  value: _salesPoc,
                  showLabel: false,
                  enabled: canEdit && canAssignPoc,
                  required: true,
                  onChanged: (u) => setState(() {
                    _salesPoc = u;
                    _dirty = true;
                  }),
                ),
              ),
            // Where the due date came from — "Custom" when it was typed (US-A3). Anyone who may
            // change the invoice may move the deadline the customer renegotiated, and every
            // move is written to the History tab (AC-A8).
            DetailGridItem(
              label: 'Payment terms',
              child: canEdit
                  ? DropdownButtonHideUnderline(
                      child: DropdownButton<PaymentTerm>(
                        isExpanded: true,
                        isDense: true,
                        value: _term,
                        hint: const Text('From the customer'),
                        items: [
                          for (final t in PaymentTerm.values)
                            DropdownMenuItem(value: t, child: Text(t.label)),
                        ],
                        onChanged: (t) => _termChanged(t, inv),
                      ),
                    )
                  : ReadOnlyValue(inv.termsLabel),
            ),
            DetailGridItem(
              label: 'Due date',
              child: canEdit
                  ? InkWell(
                      onTap: () => _pickDueDate(inv),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        child: Row(
                          children: [
                            Text(formatDate(_dueDate)),
                            const SizedBox(width: 6),
                            const Icon(Icons.calendar_today, size: 16),
                          ],
                        ),
                      ),
                    )
                  : ReadOnlyValue(formatDate(inv.dueDate)),
            ),
            DetailGridItem(
              label: 'Notes',
              span: 2,
              child: canEdit
                  ? TextField(
                      controller: _notes,
                      minLines: 1,
                      maxLines: 2,
                      inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.invoiceNotes)],
                      decoration:
                          const InputDecoration(isDense: true, hintText: 'Internal notes'),
                      onChanged: (_) => setState(() => _dirty = true),
                    )
                  : ReadOnlyValue(inv.notes ?? ''),
            ),
          ]),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            crossAxisAlignment: WrapCrossAlignment.end,
            children: [
              Text('Line items', style: Theme.of(context).textTheme.titleSmall),
              Text('Change only through an approved dispute.',
                  style: TextStyle(
                      fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant)),
            ],
          ),
          const SizedBox(height: 4),
          _lineItems(inv),
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

  /// The line items as a compact table: one short row each, instead of a two-line list tile per item.
  Widget _lineItems(InvoiceDetail inv) {
    final theme = Theme.of(context);
    final head = theme.textTheme.labelMedium;
    Widget cell(Widget child) =>
        Padding(padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 6), child: child);
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 760),
      child: Table(
        columnWidths: const {
          0: FlexColumnWidth(3),
          1: FlexColumnWidth(1),
          2: FlexColumnWidth(1.5),
          3: FlexColumnWidth(1.5),
        },
        defaultVerticalAlignment: TableCellVerticalAlignment.middle,
        children: [
          TableRow(children: [
            cell(Text('Product', style: head)),
            cell(Text('Qty', style: head, textAlign: TextAlign.right)),
            cell(Text('Unit price', style: head, textAlign: TextAlign.right)),
            cell(Text('Line total', style: head, textAlign: TextAlign.right)),
          ]),
          for (final it in inv.items)
            TableRow(
              decoration: BoxDecoration(border: Border(top: BorderSide(color: theme.dividerColor))),
              children: [
                cell(Text(it.productName)),
                cell(Text('${it.quantity}', textAlign: TextAlign.right)),
                cell(Text(formatMoney(it.unitPrice), textAlign: TextAlign.right)),
                cell(Text(formatMoney(it.lineTotal), textAlign: TextAlign.right)),
              ],
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
