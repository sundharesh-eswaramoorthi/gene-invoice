import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../approvals/approval_limit_providers.dart';
import '../approvals/pending_approval_panel.dart';
import '../auth/auth_controller.dart';

/// A branch's approval limit, on the branch list.
///
/// The number alone is not the fact. A limit that came from the deployment default is not one
/// somebody chose here, and a branch that holds nothing is not a branch with a limit of zero —
/// so the cell carries the provenance beside the figure and says "nothing is held here" in words
/// where there is no figure at all (B2).
class RegionLimitCell extends ConsumerWidget {
  final RegionRef region;
  const RegionLimitCell({super.key, required this.region});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(approvalLimitProvider(region.id));
    final waiting = ref.watch(pendingApprovalLimitChangesProvider).valueOrNull?[region.id];

    return async.when(
      // A plain ellipsis and not a spinner: a cell per branch of animation on a fifty-row page
      // is a page that never settles, for a value that arrives in one round trip.
      loading: () => Text('…', style: TextStyle(color: theme.colorScheme.outline)),
      // NOT "nothing is held here": a limit that could not be read is unknown, and rendering a
      // failed read as an answer is how a screen tells somebody their money is unguarded when it
      // is guarded, or the reverse (B2).
      error: (e, _) => Tooltip(
        message: 'This branch\'s approval limit could not be read: ${apiErrorMessage(e)}',
        child: Text('Unknown',
            style: TextStyle(color: theme.colorScheme.error, fontStyle: FontStyle.italic)),
      ),
      data: (limit) {
        if (limit == null) return const SizedBox.shrink();
        final holdsNothing = limit.state == ApprovalLimitState.switchedOff ||
            limit.state == ApprovalLimitState.nothingAnywhere;
        return Tooltip(
          message: waiting == null
              ? limit.sentence
              : '${limit.sentence}\n\n${waiting.title} — waiting for approval, so this is still '
                  'the limit in force.',
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Flexible(
                child: Text(
                  limit.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontWeight: holdsNothing ? FontWeight.w400 : FontWeight.w600,
                    fontStyle: holdsNothing ? FontStyle.italic : FontStyle.normal,
                    color: holdsNothing ? theme.colorScheme.outline : null,
                  ),
                ),
              ),
              if (limit.provenance != null) ...[
                const SizedBox(width: 6),
                StatusChip(label: limit.provenance!, color: theme.colorScheme.outline),
              ],
              // The same amber dot a record with a change waiting on it wears, for the same
              // reason: the figure on this row is the LIVE one and somebody has asked for it to
              // become something else (B2).
              if (waiting != null) ...[
                const SizedBox(width: 6),
                const ApprovalPendingDot(),
              ],
            ],
          ),
        );
      },
    );
  }
}

/// Opens the editor for one branch's limit.
///
/// It reads the current limit FIRST and refuses to open without it. An editor that cannot show
/// what it is about to replace is the region grant editor's defect — "compose the whole set,
/// their current branches cannot be read back from here" — and an operator who cannot see the
/// limit they are overwriting cannot tell whether they are raising it, lowering it or switching
/// maker-checker off altogether (B2, B1).
Future<void> showRegionLimitDialog(
  BuildContext context,
  WidgetRef ref, {
  required RegionRef region,
}) async {
  ApprovalLimit? current;
  String? failure;
  try {
    current = await ref.read(approvalLimitProvider(region.id).future);
  } catch (e) {
    failure = apiErrorMessage(e);
  }
  if (!context.mounted) return;
  if (current == null) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(failure == null
          ? 'The approval limit for ${region.code} cannot be read with your privileges, so it '
              'cannot be edited here.'
          : 'The approval limit for ${region.code} could not be read, so it cannot be edited: '
              '$failure'),
    ));
    return;
  }
  final settled = current;
  final outcome = await showDialog<ApprovalLimitOutcome>(
    context: context,
    builder: (_) => _RegionLimitDialog(region: region, current: settled),
  );
  if (outcome == null || outcome == ApprovalLimitOutcome.cancelled) return;
  // Held or applied, both refresh: after a 202 the limit reads back unchanged — which is the
  // point — and the branch now has a change waiting on it that the row has to show (B2).
  invalidateApprovalLimits(ref);
}

class _RegionLimitDialog extends ConsumerStatefulWidget {
  final RegionRef region;
  final ApprovalLimit current;

  const _RegionLimitDialog({required this.region, required this.current});

  @override
  ConsumerState<_RegionLimitDialog> createState() => _RegionLimitDialogState();
}

class _RegionLimitDialogState extends ConsumerState<_RegionLimitDialog> {
  /// Seeded from the limit in force — including one that came from the deployment default, which
  /// is the number this branch is actually being judged against today (B2).
  late final TextEditingController _amount = TextEditingController(
      text: widget.current.hasAmount ? widget.current.amount.toStringAsFixed(2) : '');
  late bool _enabled = widget.current.enabled;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  /// The third check, at the control itself. The nav entry and the route are REGION_VIEW's, the
  /// column is APPROVAL_VIEW's, and this one is APPROVAL_CONFIGURE **in this branch** — the same
  /// narrowing the service does with regionAccess.require(MANAGE), so a button is never offered
  /// where the answer would be 403 (B1, B2, D-46).
  bool get _mayConfigureHere =>
      ref.watch(currentUserProvider)?.hasIn(Privileges.approvalConfigure, widget.region.id) ??
      false;

  Future<void> _submit() async {
    final typed = _amount.text.trim();
    // Switching checking off still has to send an amount — the server's ThresholdRequest is
    // @NotNull @PositiveOrZero — and a branch that has never had a limit has nothing to send, so
    // an empty box with the switch off means zero rather than a refusal the operator cannot act
    // on (B2).
    final amount = typed.isEmpty && !_enabled ? 0.0 : parseMoneyInput(typed);
    if (amount == null) {
      setState(() => _error = 'Enter a limit as a number, with at most 2 decimal places');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await proposeApprovalLimit(ref,
          regionId: widget.region.id, amount: amount, enabled: _enabled);
      // Only reachable if the server ever stopped holding these. APPROVAL_THRESHOLD_SET is
      // alwaysChecked, so today every PUT lands in the catch below as a PendingApproval (B2).
      if (mounted) Navigator.of(context).pop(ApprovalLimitOutcome.applied);
    } on DioException catch (e) {
      final held = pendingApprovalOf(e);
      if (held != null) {
        if (mounted) {
          // The amber first and the pop second: the SnackBar belongs to the app-level messenger,
          // so it outlives this dialog, and looking the messenger up while the dialog is
          // certainly still mounted is the safe order (B2).
          showApprovalSentSnackBar(context, held);
          Navigator.of(context).pop(ApprovalLimitOutcome.heldForApproval);
        }
        return;
      }
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final waiting =
        ref.watch(pendingApprovalLimitChangesProvider).valueOrNull?[widget.region.id];
    final typed = parseMoneyInput(_amount.text.trim());
    final mayConfigureHere = _mayConfigureHere;

    return AlertDialog(
      title: Text('Approval limit — ${widget.region.code}'),
      content: SizedBox(
        // A phone has nowhere near 460px to give (D-60).
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // What is true NOW, in words, before anything is typed over it.
              Text(widget.current.sentence, style: theme.textTheme.bodyMedium),
              if (waiting != null) ...[
                const SizedBox(height: 10),
                _note(
                  scheme.tertiaryContainer,
                  scheme.onTertiaryContainer,
                  Icons.rule_outlined,
                  '${waiting.title} is already waiting for approval (change #${waiting.id}). '
                      'Only one change can wait on a branch at a time, so another will be '
                      'refused until that one is decided.',
                ),
              ],
              const SizedBox(height: 12),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                value: _enabled,
                title: const Text('Hold changes above this amount'),
                subtitle: const Text(
                    'Unticked, nothing in this branch is ever held for approval, whatever it is '
                    'worth.'),
                onChanged: _saving ? null : (v) => setState(() => _enabled = v ?? false),
              ),
              const SizedBox(height: 4),
              TextField(
                controller: _amount,
                autofocus: true,
                enabled: !_saving,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(
                  labelText: 'Limit *',
                  prefixText: '₹ ',
                  helperText: 'A change worth MORE than this waits. Exactly this much does not.',
                  helperMaxLines: 2,
                ),
                onChanged: (_) => setState(() => _error = null),
              ),
              // Zero is a legal limit and it is not "off": it holds every change in the branch.
              // Said here because the two are one tick apart on this form (B2).
              if (_enabled && typed == 0)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text('A limit of ₹0.00 holds every change in this branch.',
                      style: theme.textTheme.bodySmall),
                ),
              const SizedBox(height: 12),
              // Said BEFORE the button is pressed, not only in the amber afterwards: somebody
              // raising a limit needs to know it will not move on their say-so (B2).
              _note(
                scheme.tertiaryContainer,
                scheme.onTertiaryContainer,
                Icons.groups_outlined,
                'Changing a limit is itself always checked. Saving sends it for approval — the '
                    'limit does not move until somebody else approves it.',
              ),
              if (!mayConfigureHere) ...[
                const SizedBox(height: 10),
                Text(
                    'You cannot change the approval limit in ${widget.region.code}: it is not a '
                    'branch you manage.',
                    style: TextStyle(color: scheme.error)),
              ],
              if (_error != null) ...[
                const SizedBox(height: 10),
                Text(_error!, style: TextStyle(color: scheme.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed:
              _saving ? null : () => Navigator.of(context).pop(ApprovalLimitOutcome.cancelled),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: (_saving || !mayConfigureHere) ? null : _submit,
          // Named for what it does. "Save" would be a lie on an endpoint that answers 202 (B2).
          child: _saving
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Send for approval'),
        ),
      ],
    );
  }

  Widget _note(Color background, Color foreground, IconData icon, String text) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(8)),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: foreground),
            const SizedBox(width: 8),
            Expanded(child: Text(text, style: TextStyle(color: foreground))),
          ],
        ),
      );
}
