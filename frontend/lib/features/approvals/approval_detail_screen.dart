import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'approval_models.dart';
import 'approval_providers.dart';

/// The longest decision note the server keeps (§6.11).
const approvalNoteMaxLength = 500;

/// A field whose number is an amount of money: total, paidAmount, unitPrice, creditLimit, a
/// threshold's limit, and so on.
final _moneyField = RegExp(r'(amount|total|balance|price|credit|outstanding|threshold|limit)s?$',
    caseSensitive: false);

/// [v] with every number held by a money field (by its own key, or the list's it sits in) turned
/// into money text, however deep, so the JSON shows "₹1,234.50" rather than the 1234.5 the wire
/// leaves it as.
Object? _withMoney(Object? v, String field) => switch (v) {
      num n when _moneyField.hasMatch(field) => formatMoney(n),
      Map m => {for (final e in m.entries) e.key: _withMoney(e.value, '${e.key}')},
      List l => [for (final x in l) _withMoney(x, field)],
      _ => v,
    };

/// One change held for approval: what it would do, who asked, and — only as the server's flags
/// allow (D14) — Approve, Reject or Withdraw.
class ApprovalDetailScreen extends ConsumerStatefulWidget {
  final int id;
  final String? initialTab;
  const ApprovalDetailScreen({super.key, required this.id, this.initialTab});

  @override
  ConsumerState<ApprovalDetailScreen> createState() => _ApprovalDetailScreenState();
}

class _ApprovalDetailScreenState extends ConsumerState<ApprovalDetailScreen> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(approvalDetailProvider(widget.id));
    final asOf = ref.watch(viewAsOfProvider);
    // The server clears every flag on a past date; the page does not rely on it (D2).
    final historical = ref.watch(isHistoricalProvider);
    // A customer login reaches a change only from its record's banner; the list is staff only.
    final isCustomer = ref.watch(currentUserProvider)?.isCustomer ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'change', asOf: asOf),
        onBack: () => context.go(isCustomer ? '/' : '/approvals'),
      ),
      data: (c) {
        final live = !historical && !_busy;
        // On a past date the page shows the change as it stood then (B3): a record it produced
        // later is not there to open.
        final shown = historical ? (c.statusAt ?? c.status) : c.status;
        return DetailScaffold(
          title: 'Approval #${c.id}',
          subtitle: c.summary.isEmpty ? c.operationLabel : c.summary,
          onBack: () => goGuarded(context, isCustomer ? (c.recordRoute ?? '/') : '/approvals'),
          titleTrailing: [
            ApprovalStatusChip(status: shown),
            // No way into a record the viewer can no longer open (SC-15).
            if (shown == PendingStatus.APPROVED && c.recordRoute != null && !c.recordHidden)
              TextButton.icon(
                icon: const Icon(Icons.open_in_new, size: 18),
                label: Text('Open ${c.recordText}'),
                onPressed: () => goGuarded(context, c.recordRoute!),
              ),
            if (c.canApprove && live)
              FilledButton.icon(
                key: const ValueKey('approval-approve'),
                icon: const Icon(Icons.check, size: 18),
                label: const Text('Approve'),
                onPressed: () => _approve(c),
              ),
            if (c.canReject && live)
              OutlinedButton.icon(
                key: const ValueKey('approval-reject'),
                icon: const Icon(Icons.close, size: 18),
                label: const Text('Reject'),
                style:
                    OutlinedButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                onPressed: () => _reject(c),
              ),
            if (c.canWithdraw && live)
              OutlinedButton.icon(
                key: const ValueKey('approval-withdraw'),
                icon: const Icon(Icons.undo, size: 18),
                label: const Text('Withdraw'),
                onPressed: () => _withdraw(c),
              ),
          ],
          initialTabSlug: widget.initialTab,
          onTabChanged: (slug) => context.go('/approvals/${widget.id}?tab=$slug'),
          top: _top(c, historical: historical),
          tabs: [
            DetailTab(
              slug: 'changes',
              label: 'Changes',
              icon: Icons.compare_arrows,
              builder: (context) => _ChangesTab(change: c),
            ),
            DetailTab(
              slug: 'values',
              label: 'Current and proposed',
              icon: Icons.data_object,
              builder: (context) => SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: c.current == null && c.proposed == null
                    ? const Text('There is nothing to compare.')
                    : JsonDiffView(
                        before: _withMoney(c.current, ''),
                        after: _withMoney(c.proposed, ''),
                        beforeLabel: 'Current',
                        afterLabel: 'Proposed',
                        onlyAfterLabel: 'Proposed',
                      ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _top(PendingChangeDetail c, {required bool historical}) {
    // Still waiting on the day viewed: whatever was decided after it has not happened yet, even
    // when an older server still sends it (B3).
    final pendingThen = historical && (c.statusAt ?? c.status) == PendingStatus.PENDING;
    final shown = historical ? (c.statusAt ?? c.status) : c.status;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final linkStyle = TextStyle(color: scheme.primary, fontWeight: FontWeight.w600);
    Widget link(String text, String? route) => route == null
        ? Text(text)
        : InkWell(
            onTap: () => goGuarded(context, route),
            child: Text(text, style: linkStyle),
          );

    final String origin = switch (c.origin) {
      'AUTOMATION' => 'An automation rule',
      'USER' || null => 'A person',
      final other => humanizeEnum(other),
    };

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!pendingThen && c.status == PendingStatus.FAILED && c.failureReason != null)
            _Notice(
              key: const ValueKey('approval-failure'),
              icon: Icons.error_outline,
              color: scheme.error,
              text: 'It could not be applied: ${c.failureReason}',
            ),
          if (c.blockedReason != null)
            _Notice(
              key: const ValueKey('approval-blocked'),
              icon: Icons.info_outline,
              color: scheme.outline,
              text: c.blockedReason!,
            ),
          if (c.status == PendingStatus.PENDING && c.approverCount == 0)
            _Notice(
              icon: Icons.warning_amber_outlined,
              color: Colors.orange.shade800,
              text: 'No one else can approve changes in ${c.regionName ?? 'this region'} yet. '
                  'Ask an administrator.',
            ),
          DetailGrid(items: [
            DetailGridItem(
              label: 'Change',
              child: ReadOnlyValue(c.operationLabel ?? humanizeEnum(c.operation)),
            ),
            DetailGridItem(
              label: 'Record',
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                // Plain text when the viewer can no longer open the record (SC-15).
                child: link(
                    c.recordText,
                    c.recordHidden
                        ? null
                        : shown == PendingStatus.APPROVED
                            ? c.recordRoute
                            : approvalEntityRoute(c.entityType, c.entityId)),
              ),
            ),
            // A threshold change is about a region, not a customer.
            if (c.customerName != null)
              DetailGridItem(
                label: 'Customer',
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: link(c.customerName!,
                      c.recordHidden ? null : approvalEntityRoute('CUSTOMER', c.customerId)),
                ),
              ),
            if (c.regionName != null)
              DetailGridItem(label: 'Region', child: ReadOnlyValue(c.regionName!)),
            if (c.amount != null && c.operation != 'THRESHOLD_CHANGE')
              DetailGridItem(
                label: c.measureLabel ?? 'Amount',
                child: ReadOnlyValue(c.threshold == null
                    ? formatMoney(c.amount)
                    : '${formatMoney(c.amount)} against a limit of ${formatMoney(c.threshold)}'),
              ),
            DetailGridItem(
              label: 'Requested',
              child: ReadOnlyValue([
                if (c.requestedBy != null) 'by ${c.requestedBy!.name}',
                if (c.requestedAt != null) 'on ${formatDateTime(c.requestedAt)}',
              ].join(' ')),
            ),
            if (c.origin != null)
              DetailGridItem(
                label: 'Raised by',
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: c.ruleId == null
                      ? Text(origin)
                      : link('Rule #${c.ruleId}', '/automation/${c.ruleId}'),
                ),
              ),
            if (!pendingThen && (c.decidedAt != null || c.decidedBy != null))
              DetailGridItem(
                label: 'Decided',
                child: ReadOnlyValue([
                  if (c.decidedBy != null) 'by ${c.decidedBy!.name}',
                  if (c.decidedAt != null) 'on ${formatDateTime(c.decidedAt)}',
                ].join(' ')),
              ),
            if (!pendingThen && c.decisionNote != null && c.decisionNote!.isNotEmpty)
              DetailGridItem(label: 'Note', child: ReadOnlyValue(c.decisionNote!)),
            if (!pendingThen && c.supersededById != null)
              DetailGridItem(
                label: 'Replaced by',
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: link('Approval #${c.supersededById}', '/approvals/${c.supersededById}'),
                ),
              ),
          ]),
        ],
      ),
    );
  }

  Future<void> _approve(PendingChangeDetail c) async {
    final note = await showDialog<String>(
      context: context,
      builder: (_) => const _DecisionDialog(
        title: 'Approve this change?',
        message: 'It takes effect now, as the person who asked for it.',
        action: 'Approve',
        noteRequired: false,
      ),
    );
    if (note == null) return;
    await _decide(c, 'approve', {if (note.isNotEmpty) 'note': note}, (d) {
      if (d.status == PendingStatus.FAILED) {
        return 'Approved, but it could not be applied: ${d.failureReason ?? 'the record has changed'}';
      }
      return 'Change approved';
    });
  }

  Future<void> _reject(PendingChangeDetail c) async {
    final note = await showDialog<String>(
      context: context,
      builder: (_) => const _DecisionDialog(
        title: 'Reject this change?',
        message: 'Nothing changes. The person who asked is told, with your note.',
        action: 'Reject',
        noteRequired: true,
        destructive: true,
      ),
    );
    if (note == null) return;
    await _decide(c, 'reject', {'note': note}, (_) => 'Change rejected');
  }

  Future<void> _withdraw(PendingChangeDetail c) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Withdraw this request?'),
        content: const Text('Nothing changes, and nobody can approve it any more.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
              key: const ValueKey('approval-withdraw-confirm'),
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Withdraw')),
        ],
      ),
    );
    if (ok != true) return;
    await _decide(c, 'withdraw', null, (_) => 'Request withdrawn');
  }

  Future<void> _decide(PendingChangeDetail c, String verb, Map<String, dynamic>? body,
      String Function(PendingChangeDetail decided) message) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final res = await ref.read(dioProvider).post('/api/approvals/${c.id}/$verb', data: body);
      final decided = res.data is Map
          ? PendingChangeDetail.fromJson((res.data as Map).cast<String, dynamic>())
          : c;
      messenger.showSnackBar(SnackBar(content: Text(message(decided))));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
    if (!mounted) return;
    setState(() => _busy = false);
    // Decided or not, the change may have moved on: another approver got there first, say (409).
    approvalsChanged(ref, id: c.id);
    ref.invalidate(auditHistoryProvider);
  }
}

class _Notice extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String text;
  const _Notice({super.key, required this.icon, required this.color, required this.text});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: color),
            const SizedBox(width: 8),
            Expanded(child: Text(text, style: TextStyle(color: color))),
          ],
        ),
      );
}

/// The field-by-field table: each field's value now, and what the change would make it.
class _ChangesTab extends StatelessWidget {
  final PendingChangeDetail change;
  const _ChangesTab({required this.change});

  static final _day = RegExp(r'^\d{4}-\d{2}-\d{2}$');
  static final _enumValue = RegExp(r'^[A-Z][A-Z0-9_]+$');

  /// What an older server calls a POC's user id ('Sales poc user id'), as the page names it.
  static const _pocLabels = {
    'salesPocUserId': 'Sales POC',
    'collectionPocUserId': 'Collection POC',
    'customerSuccessPocUserId': 'Customer Success POC',
  };

  /// What an older server calls a payment's or dispute's id lists ('Invoice ids'), as the page
  /// names them.
  static const _idListLabels = {
    'invoiceIds': 'Invoices',
    'promiseIds': 'Promises',
  };

  static String _label(FieldChange f) {
    final label = f.label.toLowerCase();
    final poc = _pocLabels[f.field];
    if (poc != null && label.endsWith(' user id')) return poc;
    final ids = _idListLabels[f.field];
    if (ids != null && label.endsWith(' ids')) return ids;
    return f.label;
  }

  /// An invoice's line as an approver reads it: '150 × product #1 at ₹100.00', or its product's
  /// name when the server sends one.
  static String _line(Map m) {
    final name = m['productName'];
    final product = name is String && name.isNotEmpty ? name : 'product #${m['productId']}';
    final price = m['unitPrice'];
    return '${m['quantity']} × $product at ${price is num ? formatMoney(price) : 'list price'}';
  }

  static bool _isLine(Object? x) =>
      x is Map && x.containsKey('productId') && x.containsKey('quantity');

  static String _value(String field, Object? v) => switch (v) {
        null => '—',
        String s when s.isEmpty => '—',
        String s when _day.hasMatch(s) && DateTime.tryParse(s) != null =>
          formatDate(DateTime.parse(s)),
        String s when s.contains('T') && DateTime.tryParse(s) != null => formatDateTime(s),
        String s when _enumValue.hasMatch(s) => humanizeEnum(s),
        String s => s,
        num n when _moneyField.hasMatch(field) => formatMoney(n),
        num n => '$n',
        bool b => b ? 'Yes' : 'No',
        List l when l.isEmpty => '—',
        List l when field.endsWith('Ids') && l.every((x) => x is num)
          => l.map((id) => '#$id').join(', '),
        List l when l.every(_isLine) => l.map((m) => _line(m as Map)).join('\n'),
        _ => JsonDiffView.pretty(_withMoney(v, field)!),
      };

  static Widget _cell(String text, {TextStyle? style}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 280),
          child: Text(text, style: style),
        ),
      );

  @override
  Widget build(BuildContext context) {
    if (change.changes.isEmpty) {
      return const Center(
        child: Padding(padding: EdgeInsets.all(20), child: Text('No field-by-field changes.')),
      );
    }
    final warn = Colors.orange.shade800;
    return SingleChildScrollView(
      padding: const EdgeInsets.all(12),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: DataTable(
          // A value may run to several lines (a threshold's window, an invoice's items): each row
          // is as tall as its tallest value, so none is cut off.
          dataRowMinHeight: 48,
          dataRowMaxHeight: double.infinity,
          columns: const [
            DataColumn(label: Text('Field')),
            DataColumn(label: Text('Current')),
            DataColumn(label: Text('Proposed')),
          ],
          rows: [
            for (final f in change.changes)
              DataRow(cells: [
                DataCell(Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(_label(f)),
                    if (f.changedSinceRequest) ...[
                      const SizedBox(width: 6),
                      Tooltip(
                        message: 'Changed since the request was made',
                        child: Icon(Icons.warning_amber_outlined, size: 16, color: warn),
                      ),
                    ],
                  ],
                )),
                DataCell(_cell(f.currentText ?? _value(f.field, f.current))),
                DataCell(_cell(f.proposedText ?? _value(f.field, f.proposed),
                    style: const TextStyle(fontWeight: FontWeight.w600))),
              ]),
          ],
        ),
      ),
    );
  }
}

/// Asks for the decision's note: optional to approve, 1–500 characters to reject. Pops the
/// trimmed note ("" for none), or null when cancelled.
class _DecisionDialog extends StatefulWidget {
  final String title;
  final String message;
  final String action;
  final bool noteRequired;
  final bool destructive;

  const _DecisionDialog({
    required this.title,
    required this.message,
    required this.action,
    required this.noteRequired,
    this.destructive = false,
  });

  @override
  State<_DecisionDialog> createState() => _DecisionDialogState();
}

class _DecisionDialogState extends State<_DecisionDialog> {
  final _formKey = GlobalKey<FormState>();
  final _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.of(context).pop(_note.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    final error = Theme.of(context).colorScheme.error;
    return AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(widget.message),
              const SizedBox(height: 12),
              TextFormField(
                key: const ValueKey('approval-note'),
                controller: _note,
                autofocus: true,
                minLines: 2,
                maxLines: 5,
                inputFormatters: [LengthLimitingTextInputFormatter(approvalNoteMaxLength)],
                decoration: InputDecoration(
                  labelText: widget.noteRequired ? 'Why? (required)' : 'Note (optional)',
                  border: const OutlineInputBorder(),
                ),
                validator: (v) => widget.noteRequired && (v ?? '').trim().isEmpty
                    ? 'Say why, so the person who asked knows'
                    : null,
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          key: const ValueKey('approval-decide'),
          style: widget.destructive ? FilledButton.styleFrom(backgroundColor: error) : null,
          onPressed: _submit,
          child: Text(widget.action),
        ),
      ],
    );
  }
}
