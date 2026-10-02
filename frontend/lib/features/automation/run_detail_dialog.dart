import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import '../email/email_tab.dart' show EmailRefresher;
import 'automation_models.dart';
import 'automation_providers.dart';
import 'rules_screen.dart';

/// A run in flight is asked about again this often, for this long at most (the email tab's way).
const runPollEvery = Duration(seconds: 2);
const runPollFor = Duration(minutes: 2);

/// What set a run off (§3.6 `triggerType`), in words.
String runTriggerLabel(String? triggerType) => switch (triggerType) {
      'CREATED' => 'A record was created',
      'UPDATED' => 'A record was updated',
      'CHILDREN' => "A customer's records moved",
      'SCHEDULED' => 'Its schedule',
      'MANUAL' => 'Run by hand',
      'REPLAY' => 'Caught up after a suspension',
      null => '—',
      _ => humanizeEnum(triggerType),
    };

/// "An invoice you cannot see": a record named by its kind alone, for someone who may not see it
/// (§5.10.10). Nothing that would identify it.
String hiddenRecordText(String? type) => switch (type) {
      'INVOICE' => 'An invoice you cannot see',
      'PAYMENT' => 'A payment you cannot see',
      'CUSTOMER' => 'A customer you cannot see',
      _ => 'A record you cannot see',
    };

/// "14 matched · 12 done · 1 skipped · 1 failed".
String runCounts(RunSummary r) =>
    '${r.matched} matched · ${r.done} done · ${r.skipped} skipped · ${r.failed} failed';

const runCountsTooltip = "The run's totals now; final once it has finished";

/// Opens one run: what set it off, how it went, and what it did record by record, with Retry
/// for a failed one. A link followed from it leaves the dialog and goes through the page's
/// unsaved-changes guard.
Future<void> showRunDetailDialog(BuildContext context, {required int runId, Rule? rule}) async {
  final link = await showDialog<String>(
    context: context,
    builder: (_) => RunDetailDialog(runId: runId, rule: rule),
  );
  if (link != null && context.mounted) await goGuarded(context, link);
}

class RunDetailDialog extends ConsumerStatefulWidget {
  final int runId;

  /// The rule it belongs to, for its actions' names and who may retry it.
  final Rule? rule;

  const RunDetailDialog({super.key, required this.runId, this.rule});

  @override
  ConsumerState<RunDetailDialog> createState() => _RunDetailDialogState();
}

class _RunDetailDialogState extends ConsumerState<RunDetailDialog> {
  late int _runId = widget.runId;
  int _pages = 1;
  DateTime? _inFlightSince;
  bool _retrying = false;
  String? _error;

  late final _refresher = EmailRefresher(() {
    ref.invalidate(runDetailProvider(_runId));
    for (var page = 0; page < _pages; page++) {
      ref.invalidate(runEffectsProvider((runId: _runId, page: page)));
    }
  });

  @override
  void dispose() {
    _refresher.dispose();
    super.dispose();
  }

  void _poll(AsyncValue<RunSummary> async) {
    final run = async.valueOrNull;
    if (run == null || !run.inFlight) {
      _inFlightSince = null;
      _refresher.update(null);
      return;
    }
    final since = _inFlightSince ??= DateTime.now();
    final polling = DateTime.now().difference(since) < runPollFor;
    _refresher.update(polling ? runPollEvery : null, busy: async.isLoading);
  }

  Future<void> _retry(RunSummary run, RuleRights rights) async {
    final dio = ref.read(dioProvider);
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _retrying = true;
      _error = null;
    });
    try {
      final queued = await actTakingOwnership<QueuedRun>(
        context,
        // Retrying a run someone asked for by hand acts as its asker did (§6.14).
        takesOwnership: rights.takesOwnership && run.triggerType == 'MANUAL',
        ownerName: widget.rule?.owner?.name,
        send: (confirm) async {
          final res = await dio.post('/api/automation/runs/${run.id}/retry',
              data: {if (confirm) 'confirmOwnershipChange': true});
          return QueuedRun.fromJson((res.data as Map).cast<String, dynamic>());
        },
      );
      if (queued == null) return;
      rulesChanged(container, id: run.ruleId ?? widget.rule?.id);
      messenger.showSnackBar(SnackBar(content: Text('Run #${queued.runId} is queued')));
      if (!mounted) return;
      setState(() {
        _runId = queued.runId;
        _pages = 1;
        _inFlightSince = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _retrying = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(runDetailProvider(_runId));
    final historical = ref.watch(isHistoricalProvider);
    final user = ref.watch(currentUserProvider);
    final run = async.valueOrNull;
    _poll(async);
    final rule = widget.rule;
    final rights =
        rule == null ? RuleRights.none : RuleRights.forRule(user, rule, historical: historical);
    final canRetry =
        run != null && run.status == RunStatus.FAILED && rights.canAct && !historical;
    final narrow = MediaQuery.sizeOf(context).width < 600;

    return AlertDialog(
      title: Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text('Run #$_runId'),
          if (run != null)
            RunStatusChip(status: run.status, statusAt: historical ? run.statusAt : null),
        ],
      ),
      content: SizedBox(
        width: narrow ? double.maxFinite : 640,
        child: async.when(
          skipLoadingOnRefresh: true,
          loading: () => const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Text(apiErrorMessage(e),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
          data: (run) => SingleChildScrollView(child: _body(run)),
        ),
      ),
      actions: [
        if (canRetry)
          FilledButton.icon(
            key: const ValueKey('run-retry'),
            icon: const Icon(Icons.replay, size: 18),
            label: const Text('Retry'),
            onPressed: _retrying ? null : () => _retry(run, rights),
          ),
        TextButton(
          key: const ValueKey('run-close'),
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }

  Widget _body(RunSummary run) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final entity = run.entity;
    final late = run.lateBySeconds ?? 0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text([
          runTriggerLabel(run.triggerType),
          if (run.requestedBy != null) 'by ${run.requestedBy!.name}',
        ].join(' ')),
        if (entity != null)
          entity.hidden || entity.link == null
              ? Text(entity.hidden ? hiddenRecordText(entity.type) : (entity.label ?? '—'),
                  key: const ValueKey('run-entity'),
                  style: entity.hidden ? TextStyle(color: theme.disabledColor) : null)
              : _Link(
                  key: const ValueKey('run-entity'),
                  label: entity.label ?? '#${entity.id}',
                  onTap: () => Navigator.of(context).pop(entity.link)),
        if (run.scheduledFor != null)
          Text(
            'For ${formatDateTime(run.scheduledFor)}'
            '${late >= 60 ? ' · started ${late ~/ 60} min late' : ''}',
            style: muted,
          ),
        const SizedBox(height: 8),
        Tooltip(
          message: runCountsTooltip,
          child: Text(runCounts(run), key: const ValueKey('run-counts')),
        ),
        if (run.partial)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text('Counts only records you can see',
                key: const ValueKey('run-partial'), style: muted),
          ),
        if (run.truncated)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text("It stopped at the rule's most records per run.", style: muted),
          ),
        if (run.message != null && run.message!.isNotEmpty)
          Padding(padding: const EdgeInsets.only(top: 4), child: Text(run.message!)),
        if (run.finishedAt != null)
          Text('Finished ${formatDateTime(run.finishedAt)}', style: muted),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
          ),
        const Divider(height: 24),
        Text('What it did', style: theme.textTheme.titleSmall),
        ..._effects(),
      ],
    );
  }

  List<Widget> _effects() {
    final pages = [
      for (var page = 0; page < _pages; page++)
        ref.watch(runEffectsProvider((runId: _runId, page: page))),
    ];
    final last = pages.last;
    final effects = [
      for (final p in pages) ...?p.valueOrNull?.content,
    ];
    final more = last.valueOrNull != null && _pages < last.valueOrNull!.totalPages;
    return [
      if (effects.isEmpty && last.hasValue)
        const Padding(padding: EdgeInsets.only(top: 8), child: Text('Nothing yet.')),
      for (final e in effects)
        _EffectTile(
          effect: e,
          actionLabel: _actionLabel(e),
          onOpen: (link) => Navigator.of(context).pop(link),
        ),
      if (last.isLoading && !last.hasValue)
        const Padding(padding: EdgeInsets.only(top: 8), child: LinearProgressIndicator()),
      if (last.hasError)
        Text(apiErrorMessage(last.error!),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
      if (more)
        TextButton(
          key: const ValueKey('run-effects-more'),
          onPressed: () => setState(() => _pages++),
          child: const Text('Show more'),
        ),
    ];
  }

  /// The action as the rule words it ("Create a task: Chase …"), or its kind when the rule no
  /// longer has it.
  String _actionLabel(Effect e) {
    for (final a in widget.rule?.actions ?? const <RuleAction>[]) {
      if (a.key == e.actionKey && a.key.isNotEmpty) return a.summary;
    }
    return ActionType.parse(e.actionType)?.label ?? humanizeEnum(e.actionType);
  }
}

class _EffectTile extends StatelessWidget {
  final Effect effect;
  final String actionLabel;
  final ValueChanged<String> onOpen;
  const _EffectTile({required this.effect, required this.actionLabel, required this.onOpen});

  static String _status(String? s) => switch (s) {
        'DONE' => 'Done',
        'SKIPPED' => 'Skipped',
        'FAILED' => 'Failed',
        'PENDING' => 'Waiting',
        _ => humanizeEnum(s),
      };

  static IconData _icon(String? s) => switch (s) {
        'DONE' => Icons.check_circle_outline,
        'SKIPPED' => Icons.skip_next_outlined,
        'FAILED' => Icons.error_outline,
        _ => Icons.hourglass_empty,
      };

  /// "Task #41", "Approval #7 (waiting)", …: what the action made.
  static String? _result(Effect e) {
    final id = e.resultId;
    return switch (e.resultType) {
      null => null,
      'TASK' => 'Task #$id',
      'PROMISE' => 'Promise #$id',
      'DISPUTE' => 'Dispute #$id',
      'EMAIL' => 'Email #$id',
      'PENDING_CHANGE' => 'Approval #$id (waiting)',
      _ => '${humanizeEnum(e.resultType)} #$id',
    };
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final e = effect;
    final target = e.target;
    // Someone who may not see the record learns only that an action touched one of its kind:
    // no name, no link, no result, no message (§5.10.10).
    if (e.hidden) {
      return ListTile(
        key: ValueKey('effect-${e.id}'),
        enabled: false,
        dense: true,
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.visibility_off_outlined, size: 20),
        title: Text(actionLabel),
        subtitle: Text(hiddenRecordText(target?.type)),
        trailing: Text(_status(e.status)),
      );
    }
    final result = _result(e);
    final error = e.status == 'FAILED';
    return ListTile(
      key: ValueKey('effect-${e.id}'),
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(_icon(e.status), size: 20, color: error ? theme.colorScheme.error : null),
      title: Text(actionLabel),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (target != null)
            target.link == null
                ? Text(target.label ?? '#${target.id}')
                : _Link(
                    key: ValueKey('effect-${e.id}-target'),
                    label: target.label ?? '#${target.id}',
                    onTap: () => onOpen(target.link!)),
          if (e.message != null && e.message!.isNotEmpty)
            Text(e.message!, style: TextStyle(color: error ? theme.colorScheme.error : null)),
          for (final w in e.warnings) Text(w, style: theme.textTheme.bodySmall),
        ],
      ),
      trailing: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(_status(e.status)),
          if (result != null)
            e.resultLink == null
                ? Text(result, style: theme.textTheme.bodySmall)
                : _Link(
                    key: ValueKey('effect-${e.id}-result'),
                    label: result,
                    onTap: () => onOpen(e.resultLink!)),
        ],
      ),
    );
  }
}

class _Link extends StatelessWidget {
  final String label;
  final VoidCallback onTap;
  const _Link({super.key, required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Text(label, style: TextStyle(color: scheme.primary, fontWeight: FontWeight.w600)),
    );
  }
}
