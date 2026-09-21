import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../shared/widgets/status_chip.dart';
import 'automation_models.dart';
import 'automation_providers.dart';

/// Why a rule is not doing anything (R4).
///
/// The rules list can only say when a rule last *did* something and how often: the worker moves
/// `lastRunAt` and `runCount` when an action happens, and on no other path. So a rule that is
/// asked about a hundred records a day and skips every one of them reads on that row exactly like
/// a rule nothing has ever matched — and those are the two things somebody staring at a rule that
/// "does nothing" is trying to tell apart. The runs are where the server says which it is, in the
/// event's own `lastError`: "the filters did not match", "already done for this record", or the
/// refusal that a failing action came back with.
///
/// It is a dialog off the row rather than a page, because a rule has no page of its own — the
/// editor is the only other thing you can open on one — and this is read once, while wondering.
/// AUTOMATION_VIEW, like the endpoint: reading what a rule has been up to makes nothing happen.
Future<void> showAutomationRuleRuns(BuildContext context, AutomationRule rule) => showDialog<void>(
      context: context,
      builder: (_) => _RunsDialog(rule: rule),
    );

class _RunsDialog extends ConsumerWidget {
  final AutomationRule rule;
  const _RunsDialog({required this.rule});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(automationRuleRunsProvider(rule.id));
    final theme = Theme.of(context);
    final narrow = MediaQuery.sizeOf(context).width < 600;

    return AlertDialog(
      title: Text('Runs of "${rule.name}"'),
      content: SizedBox(
        // A phone has nowhere near 560px to give (D-60).
        width: narrow ? double.maxFinite : 560,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'What the rule has been asked about lately, newest first. A skip is an ordinary '
              'answer and says why: the record no longer matched, or the rule had already done '
              'this for it and does not do it twice.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            Flexible(child: _body(context, ref, async)),
          ],
        ),
      ),
      actions: [
        TextButton.icon(
          icon: const Icon(Icons.refresh, size: 18),
          label: const Text('Refresh'),
          onPressed: () => ref.invalidate(automationRuleRunsProvider(rule.id)),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }

  Widget _body(BuildContext context, WidgetRef ref, AsyncValue<List<AutomationRuleRun>> async) {
    final theme = Theme.of(context);
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Text('Could not load the runs: ${apiErrorMessage(e)}'),
      ),
      data: (runs) {
        if (runs.isEmpty) {
          // Never asked at all is its own answer, and a different one from asked and skipped: the
          // trigger has not fired since the rule was written, or the clock has not come round yet.
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'Nothing has set this rule off yet, so it has not been asked about any '
              '${rule.recordLabel.toLowerCase()}.',
              style: theme.textTheme.bodyMedium,
            ),
          );
        }
        return ListView.separated(
          shrinkWrap: true,
          itemCount: runs.length,
          separatorBuilder: (_, __) => const Divider(height: 12),
          itemBuilder: (context, i) => _RunRow(run: runs[i]),
        );
      },
    );
  }
}

/// One run: which record, what came of it, when — and, underneath, why.
class _RunRow extends StatelessWidget {
  final AutomationRuleRun run;
  const _RunRow({required this.run});

  /// The shared palette (shared/widgets/status_chip.dart): queued is waiting on the worker,
  /// running is under way, done is the good ending, failed went wrong, and a skip is inert —
  /// nothing happened and nothing is owed, which is exactly what grey means everywhere else.
  Color _color(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return switch (run.status) {
      AutomationRunStatus.queued => scheme.primary,
      AutomationRunStatus.running => Colors.orange.shade800,
      AutomationRunStatus.done => Colors.green.shade700,
      AutomationRunStatus.failed => scheme.error,
      _ => scheme.outline,
    };
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall;
    final why = run.lastError;
    final next = run.nextAttemptAt;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            StatusChip(label: run.statusLabel, color: _color(context)),
            Text(run.recordLabel, style: theme.textTheme.bodyMedium),
            if (run.when != null) Text(formatDateTime(run.when), style: muted),
            // Worth saying only when it has been taken on more than once: every other row has
            // been tried exactly once, and saying so on all of them says nothing.
            if (run.attempts > 1) Text('after ${run.attempts} attempts', style: muted),
          ],
        ),
        if (why != null && why.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: SelectableText(
              why,
              style: muted?.copyWith(
                  color: run.status == AutomationRunStatus.failed
                      ? theme.colorScheme.error
                      : theme.colorScheme.onSurfaceVariant),
            ),
          ),
        if (next != null && run.inProgress)
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Text('Next attempt ${formatDateTime(next)}', style: muted),
          ),
      ],
    );
  }
}
