import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import 'approval_providers.dart';

/// Says a record has a change waiting on it. Amber, in the header beside the status chip, because
/// nothing about the record has changed and nothing has failed — the figures on the page are all
/// live, and somebody has asked for them to become something else (B2).
class ApprovalPendingChip extends StatelessWidget {
  const ApprovalPendingChip({super.key});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: 'Change pending approval',
        color: Theme.of(context).colorScheme.tertiary,
      );
}

/// The same fact on a list row. A dot and not the whole chip: a list is read by scanning one
/// column down, and a second full-width chip in the status cell would push the real status out
/// of the row. The filter beside it comes from the published ColumnDef with no per-screen work
/// (B2).
class ApprovalPendingDot extends StatelessWidget {
  const ApprovalPendingDot({super.key});

  @override
  Widget build(BuildContext context) => Tooltip(
        message: 'A change on this record is waiting for approval',
        child: Icon(Icons.circle, size: 10, color: Theme.of(context).colorScheme.tertiary),
      );
}

/// Tells the maker the save was taken but has not happened, and offers the change it raised.
///
/// Not an error colour and not a success one: the amber says "accepted, not done". The link is
/// the server's own, so what the queue is called is decided in one place (B2).
void showApprovalSentSnackBar(BuildContext context, PendingApproval held) {
  final scheme = Theme.of(context).colorScheme;
  final router = GoRouter.maybeOf(context);
  final route = held.route;
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    backgroundColor: scheme.tertiaryContainer,
    // Longer than the default four seconds: it is the only place the maker is told that the
    // thing they just did has not taken effect (B2).
    duration: const Duration(seconds: 8),
    content: Text(held.message, style: TextStyle(color: scheme.onTertiaryContainer)),
    action: (router == null || route == null)
        ? null
        : SnackBarAction(
            label: 'View request',
            textColor: scheme.onTertiaryContainer,
            // The router and not this context: by the time anybody taps, the dialog that showed
            // this may be long gone.
            onPressed: () => router.go(route),
          ),
  ));
}

/// The panel above the body of a record that has a change waiting on it.
///
/// Fetches nothing when there is nothing waiting: every detail screen already knows from its own
/// `approvalPending` flag, which costs the server one indexed lookup it was making anyway (B2).
class PendingApprovalBanner extends ConsumerWidget {
  final PendingTarget target;

  /// The record's own detail provider, refreshed by the screen that owns it once a decision has
  /// actually changed something (B2).
  final VoidCallback? onDecided;

  const PendingApprovalBanner({super.key, required this.target, this.onDecided});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(pendingChangeForProvider(target));
    return async.maybeWhen(
      data: (change) => change == null
          ? const SizedBox.shrink()
          : PendingApprovalPanel(change: change, onDecided: onDecided),
      // A queue this caller may not read, or a queue that is momentarily unreachable, is an
      // absence rather than an error on somebody else's page: the record below is still correct.
      orElse: () => const SizedBox.shrink(),
    );
  }
}

class PendingApprovalPanel extends ConsumerStatefulWidget {
  final PendingChange change;
  final VoidCallback? onDecided;

  /// True on the change's own page, where the record it is about is worth linking to. On a record
  /// it would only link back to the page you are already on.
  final bool linkToRecord;

  const PendingApprovalPanel({
    super.key,
    required this.change,
    this.onDecided,
    this.linkToRecord = false,
  });

  @override
  ConsumerState<PendingApprovalPanel> createState() => _PendingApprovalPanelState();
}

class _PendingApprovalPanelState extends ConsumerState<PendingApprovalPanel> {
  bool _busy = false;
  String? _error;

  Future<void> _decide(String decision, {String? notes}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await decideApproval(ref, id: widget.change.id, decision: decision, notes: notes);
      widget.onDecided?.call();
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// A rejection with no reason is a rejection nobody can act on, and the server refuses one, so
  /// it is asked for here rather than turned into a 400 the maker never sees (B2).
  Future<void> _reject() async {
    final notes = await showApprovalNotesDialog(
      context,
      title: 'Reject this change?',
      hint: 'Say why, so the person who raised it knows what to do',
      required: true,
      confirmLabel: 'Reject',
    );
    if (notes == null) return;
    await _decide('reject', notes: notes);
  }

  Future<void> _approve() async {
    final notes = await showApprovalNotesDialog(
      context,
      title: 'Approve this change?',
      hint: 'Notes (optional)',
      required: false,
      confirmLabel: 'Approve',
      body: widget.change.summary,
    );
    if (notes == null) return;
    await _decide('approve', notes: notes);
  }

  Future<void> _withdraw() async {
    final notes = await showApprovalNotesDialog(
      context,
      title: 'Withdraw this change?',
      hint: 'Notes (optional)',
      required: false,
      confirmLabel: 'Withdraw',
      body: 'It will not take effect, and nothing on the record changes.',
    );
    if (notes == null) return;
    await _decide('withdraw', notes: notes);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final c = widget.change;
    final decided = !c.isWaiting;

    return Card(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      color: scheme.tertiaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 6,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Icon(Icons.rule_outlined, size: 18, color: scheme.onTertiaryContainer),
                Text(
                  decided
                      ? '${pendingChangeStatusLabel(c.status)} change'
                      : 'Waiting for approval',
                  style: theme.textTheme.titleSmall
                      ?.copyWith(color: scheme.onTertiaryContainer),
                ),
                StatusChip(label: pendingActionLabel(c.action), color: scheme.onTertiaryContainer),
                Text('#${c.id}',
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: scheme.onTertiaryContainer)),
              ],
            ),
            const SizedBox(height: 6),
            // The figures on the page below are the LIVE ones. This sentence is the only place
            // that says what they would become (B2).
            Text(c.title, style: TextStyle(color: scheme.onTertiaryContainer)),
            const SizedBox(height: 4),
            Text(_provenance(c),
                style:
                    theme.textTheme.bodySmall?.copyWith(color: scheme.onTertiaryContainer)),
            if (widget.linkToRecord && c.targetRoute != null) ...[
              const SizedBox(height: 6),
              TextButton.icon(
                icon: const Icon(Icons.open_in_new, size: 16),
                label: Text('Open the ${pendingTargetLabel(c.targetType).toLowerCase()}'),
                onPressed: () => context.go(c.targetRoute!),
              ),
            ],
            const SizedBox(height: 10),
            _beforeAndAfter(context, c),
            if (c.cannotDecideReason != null && !decided) ...[
              const SizedBox(height: 10),
              // Why the buttons are not there. Worked out by the server per row, so the rule is
              // stated once and this client never guesses at it (B2).
              Text(c.cannotDecideReason!,
                  style: theme.textTheme.bodySmall?.copyWith(
                      color: scheme.onTertiaryContainer, fontStyle: FontStyle.italic)),
            ],
            if (decided && c.decisionNotes != null && c.decisionNotes!.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text('Note: ${c.decisionNotes}',
                  style: TextStyle(color: scheme.onTertiaryContainer)),
            ],
            if (_error != null) ...[
              const SizedBox(height: 10),
              Text(_error!, style: TextStyle(color: scheme.error)),
            ],
            if (c.decidable || c.withdrawable) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  if (c.decidable)
                    FilledButton.icon(
                      icon: const Icon(Icons.check, size: 18),
                      label: const Text('Approve'),
                      onPressed: _busy ? null : _approve,
                    ),
                  if (c.decidable)
                    OutlinedButton.icon(
                      icon: const Icon(Icons.close, size: 18),
                      label: const Text('Reject'),
                      onPressed: _busy ? null : _reject,
                    ),
                  if (c.withdrawable)
                    TextButton.icon(
                      icon: const Icon(Icons.undo, size: 18),
                      label: const Text('Withdraw'),
                      onPressed: _busy ? null : _withdraw,
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _provenance(PendingChange c) {
    final who = c.requestedByName ?? (c.requestedByUserId == null ? 'the system' : null);
    return [
      'Raised${who == null ? '' : ' by $who'}'
          '${c.requestedAt == null ? '' : ' ${formatDateTime(c.requestedAt)}'}',
      if (c.regionName != null) 'in ${c.regionName}',
      if (c.exposure != null) formatMoney(c.exposure),
      if (c.alwaysChecked)
        'always checked'
      else if (c.thresholdApplied != null)
        'over the ${formatMoney(c.thresholdApplied)} limit',
      if (!c.isWaiting && c.decidedByName != null)
        '${pendingChangeStatusLabel(c.status).toLowerCase()} by ${c.decidedByName}',
    ].join(' • ');
  }

  Widget _beforeAndAfter(BuildContext context, PendingChange c) => DetailGrid(items: [
        DetailGridItem(
          label: 'Now',
          child: _json(context, c.beforeJson, empty: 'The record does not exist yet'),
        ),
        DetailGridItem(
          label: 'Proposed',
          child: _json(context, c.payloadJson, empty: 'Nothing to change — the action is enough'),
        ),
      ]);

  Widget _json(BuildContext context, String? raw, {required String empty}) {
    final pretty = prettyJson(raw);
    return ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 220),
      child: SingleChildScrollView(
        child: SelectableText(
          pretty.isEmpty ? empty : pretty,
          style: TextStyle(
            fontFamily: 'monospace',
            fontSize: 12,
            color: Theme.of(context).colorScheme.onTertiaryContainer,
          ),
        ),
      ),
    );
  }
}

/// The dispute_detail_screen shape: raw JSON, indented so a person can read it, and left exactly
/// as it came if it will not parse — a payload nobody can decode is still evidence (B2).
String prettyJson(String? raw) {
  if (raw == null || raw.isEmpty || raw == '{}') return '';
  try {
    return const JsonEncoder.withIndent('  ').convert(jsonDecode(raw));
  } catch (_) {
    return raw;
  }
}

/// Asks for the note that goes with a decision. Returns null when the person backed out, and the
/// (possibly empty) note when they went ahead — so "cancelled" and "approved with no note" are
/// two different answers (B2).
Future<String?> showApprovalNotesDialog(
  BuildContext context, {
  required String title,
  required String hint,
  required bool required,
  required String confirmLabel,
  String? body,
}) =>
    showDialog<String>(
      context: context,
      builder: (dialogContext) => _NotesDialog(
        title: title,
        hint: hint,
        required: required,
        confirmLabel: confirmLabel,
        body: body,
      ),
    );

class _NotesDialog extends StatefulWidget {
  final String title;
  final String hint;
  final bool required;
  final String confirmLabel;
  final String? body;

  const _NotesDialog({
    required this.title,
    required this.hint,
    required this.required,
    required this.confirmLabel,
    this.body,
  });

  @override
  State<_NotesDialog> createState() => _NotesDialogState();
}

class _NotesDialogState extends State<_NotesDialog> {
  // Owned here and not by the caller: a controller disposed the moment showDialog resolves is
  // still being read by the dialog's own exit animation.
  final TextEditingController _notes = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _notes.dispose();
    super.dispose();
  }

  void _confirm() {
    final text = _notes.text.trim();
    if (widget.required && text.isEmpty) {
      setState(() => _error = 'A reason is required');
      return;
    }
    Navigator.of(context).pop(text);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(widget.title),
        content: SizedBox(
          // A phone has nowhere near 420px to give (D-60).
          width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (widget.body != null) ...[
                Text(widget.body!),
                const SizedBox(height: 12),
              ],
              TextField(
                controller: _notes,
                autofocus: true,
                minLines: 2,
                maxLines: 4,
                decoration: InputDecoration(
                  labelText: widget.hint,
                  border: const OutlineInputBorder(),
                  errorText: _error,
                ),
                onChanged: (_) {
                  if (_error != null) setState(() => _error = null);
                },
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(onPressed: _confirm, child: Text(widget.confirmLabel)),
        ],
      );
}
