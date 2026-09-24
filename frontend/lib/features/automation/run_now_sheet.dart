import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/unsaved_changes.dart';
import 'automation_providers.dart';

/// RUN NOW — a dry run first, always (A5).
///
/// The first press asks `apply=false`, which writes NOTHING and answers the count, a truncation
/// warning and the rendered title/subject for the first twenty records. The confirm button is the
/// only thing that posts `apply=true`. Two presses and not one, because "run this rule against
/// everything that matches" is not a thing to find out the shape of afterwards.
///
/// THE REQUEST ID IS MINTED ONCE PER SHEET AND KEPT. That is the whole double-click guard: the
/// server's occasion is `"M" + requestId`, so a second confirm carrying the same id bounces off
/// `uk_run_occasion` and answers 200 with the first run rather than opening a second one. A new id
/// per press would defeat it exactly (A5).
Future<void> showRunNowSheet(
  BuildContext context, {
  required int ruleId,
  required String ruleName,
  required bool enabled,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (_) => _RunNowSheet(ruleId: ruleId, ruleName: ruleName, ruleEnabled: enabled),
  );
}

class _RunNowSheet extends ConsumerStatefulWidget {
  final int ruleId;
  final String ruleName;
  final bool ruleEnabled;

  const _RunNowSheet({
    required this.ruleId,
    required this.ruleName,
    required this.ruleEnabled,
  });

  @override
  ConsumerState<_RunNowSheet> createState() => _RunNowSheetState();
}

class _RunNowSheetState extends ConsumerState<_RunNowSheet> {
  /// One id for this sheet, minted before the first confirm and never replaced. See the doc above.
  late final String _requestId =
      'ui${DateTime.now().microsecondsSinceEpoch}r${widget.ruleId}';

  DryRun? _dry;
  String? _error;
  bool _busy = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _loadDryRun());
  }

  Future<void> _loadDryRun() async {
    try {
      final dry = await dryRunRule(ref, widget.ruleId);
      if (!mounted) return;
      setState(() {
        _dry = dry;
        _error = null;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = apiErrorMessage(e);
        _busy = false;
      });
    }
  }

  Future<void> _apply() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final messenger = ScaffoldMessenger.of(context);
    try {
      final run = await runRule(ref, widget.ruleId, requestId: _requestId);
      if (!mounted) return;
      Navigator.of(context).pop();
      messenger.showSnackBar(SnackBar(
        content: Text('Run #${run.id} started — ${run.status.label.toLowerCase()}'),
      ));
      // Straight to the run's own history, which is the activity list with one filter in the URL
      // and no second screen (A5).
      if (context.mounted) goGuarded(context, runActivityLocation(run.id));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = apiErrorMessage(e);
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dry = _dry;
    final height = MediaQuery.sizeOf(context).height;

    return SafeArea(
      child: ConstrainedBox(
        // A phone has nowhere near a full sheet to give (D-60).
        constraints: BoxConstraints(maxHeight: height * 0.8),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Run “${widget.ruleName}” now',
                        style: theme.textTheme.titleMedium,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              if (!widget.ruleEnabled)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    'This rule is switched off. Switch it on before running it.',
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                ),
              if (_busy && dry == null)
                const Padding(
                  padding: EdgeInsets.all(24),
                  child: Center(child: CircularProgressIndicator()),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                ),
              if (dry != null) ...[
                const SizedBox(height: 8),
                Text(
                  dry.matched == 1
                      ? '1 record matches right now.'
                      : '${dry.matched} records match right now.',
                  style: theme.textTheme.titleSmall,
                ),
                Text('Nothing has happened yet.',
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                if (dry.truncated)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.warning_amber_rounded,
                            size: 18, color: Colors.brown.shade800),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            'More records match than one run will take. The run will stop at the '
                            'limit and say so.',
                            style: TextStyle(color: Colors.brown.shade800),
                          ),
                        ),
                      ],
                    ),
                  ),
                const SizedBox(height: 8),
                if (dry.sample.isEmpty)
                  Text(
                    dry.matched == 0
                        ? 'No record matches, so this run would do nothing.'
                        : 'None of the matching records are ones you may see, so there is nothing '
                            'to show you.',
                    style: theme.textTheme.bodySmall,
                  )
                else ...[
                  Text('The first ${dry.sample.length}', style: theme.textTheme.labelLarge),
                  Flexible(
                    child: ListView.builder(
                      shrinkWrap: true,
                      itemCount: dry.sample.length,
                      itemBuilder: (context, i) {
                        final s = dry.sample[i];
                        final lines = [
                          if ((s.renderedTitle ?? '').isNotEmpty) 'Task: ${s.renderedTitle}',
                          if ((s.renderedSubject ?? '').isNotEmpty) 'Email: ${s.renderedSubject}',
                        ];
                        return ListTile(
                          dense: true,
                          title: Text(s.label),
                          subtitle: lines.isEmpty ? null : Text(lines.join('\n')),
                          isThreeLine: lines.length > 1,
                        );
                      },
                    ),
                  ),
                ],
              ],
              const SizedBox(height: 12),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: _busy ? null : () => Navigator.of(context).pop(),
                    child: const Text('Cancel'),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    icon: _busy && dry != null
                        ? const SizedBox(
                            width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.play_arrow, size: 18),
                    label: Text(dry == null
                        ? 'Run'
                        : dry.matched == 1
                            ? 'Run on 1 record'
                            : 'Run on ${dry.matched} records'),
                    onPressed:
                        _busy || dry == null || dry.matched == 0 || !widget.ruleEnabled
                            ? null
                            : _apply,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
