import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/widgets/status_chip.dart';
import '../email/email_tab.dart' show EmailRefresher;
import 'automation_models.dart';
import 'run_detail_dialog.dart';

/// A rule's runs, newest first (§6.14 `/rules/{id}/runs`). On a past date each shows how it
/// stood then beside how it stands now (§5.10.10). Someone who sees only some of a rule's
/// records is told the counts are theirs alone. While a run is queued or running the list asks
/// again every two seconds, for two minutes at most.
class RuleRunsTab extends ConsumerStatefulWidget {
  final Rule rule;
  const RuleRunsTab({super.key, required this.rule});

  @override
  ConsumerState<RuleRunsTab> createState() => _RuleRunsTabState();
}

class _RuleRunsTabState extends ConsumerState<RuleRunsTab> {
  static const _entity = 'automation-runs';

  late TableQuery _query = TableQuery(
    size: ref.read(pageSizeStoreProvider)[_entity] ?? 20,
    sort: 'createdAt,desc',
  );

  DateTime? _inFlightSince;
  late final _refresher = EmailRefresher(() => ref.invalidate(tablePageProvider(_request)));

  String get _path => '/api/automation/rules/${widget.rule.id}/runs';

  /// The very request the table makes, so its answer is the one watched here.
  TableRequest get _request => TableRequest(entity: _entity, path: _path, query: _query);

  @override
  void dispose() {
    _refresher.dispose();
    super.dispose();
  }

  void _poll(AsyncValue<PagedResult<Map<String, dynamic>>> page, List<RunSummary> runs) {
    if (!runs.any((r) => r.inFlight)) {
      _inFlightSince = null;
      _refresher.update(null);
      return;
    }
    final since = _inFlightSince ??= DateTime.now();
    final polling = DateTime.now().difference(since) < runPollFor;
    _refresher.update(polling ? runPollEvery : null, busy: page.isLoading);
  }

  @override
  Widget build(BuildContext context) {
    final historical = ref.watch(isHistoricalProvider);
    final page = ref.watch(tablePageProvider(_request));
    final runs = [
      for (final row in page.valueOrNull?.content ?? const <Map<String, dynamic>>[])
        if (row['id'] is num) RunSummary.fromJson(row),
    ];
    _poll(page, runs);
    final partial = runs.any((r) => r.partial);
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);

    return DataTableScaffold<RunSummary>(
      entity: _entity,
      path: _path,
      query: _query,
      onQueryChanged: (q) => setState(() => _query = q),
      parse: RunSummary.fromJson,
      idOf: (r) => r.id,
      selectable: false,
      emptyMessage: 'No runs match this filter',
      header: partial
          ? Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
              child: Row(
                children: [
                  Icon(Icons.visibility_outlined, size: 16, color: theme.colorScheme.outline),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                        'Counts only records you can see: this rule also acts on records you '
                        'may not open.',
                        key: const ValueKey('runs-partial'),
                        style: muted),
                  ),
                ],
              ),
            )
          : null,
      onRowTap: (context, r) => showRunDetailDialog(context, runId: r.id, rule: widget.rule),
      columns: [
        TableColumnSpec(label: 'Run', sortKey: 'id', cell: (context, r) => Text('#${r.id}')),
        TableColumnSpec(
          label: 'Status',
          sortKey: 'status',
          cell: (context, r) =>
              RunStatusChip(status: r.status, statusAt: historical ? r.statusAt : null),
        ),
        TableColumnSpec(
          label: 'Started by',
          maxWidth: 220,
          cell: (context, r) => Text(
            [
              runTriggerLabel(r.triggerType),
              if (r.requestedBy != null) '(${r.requestedBy!.name})',
            ].join(' '),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        ),
        TableColumnSpec(
          label: 'Record',
          maxWidth: 220,
          cell: (context, r) => _RunRecord(run: r),
        ),
        TableColumnSpec(
          label: 'When',
          sortKey: 'createdAt',
          cell: (context, r) => Text(formatDateTime(r.scheduledFor ?? r.createdAt)),
        ),
        TableColumnSpec(
          label: 'Results',
          cell: (context, r) => Tooltip(message: runCountsTooltip, child: Text(runCounts(r))),
        ),
        TableColumnSpec(
          label: 'Finished',
          sortKey: 'finishedAt',
          cell: (context, r) => Text(r.finishedAt == null ? '—' : formatDateTime(r.finishedAt)),
        ),
      ],
      mobileCard: (context, r) => Card(
        child: ListTile(
          title: Row(
            children: [
              Text('Run #${r.id}'),
              const SizedBox(width: 8),
              RunStatusChip(status: r.status, statusAt: historical ? r.statusAt : null),
            ],
          ),
          subtitle: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(runTriggerLabel(r.triggerType)),
              _RunRecord(run: r),
              Text(runCounts(r), style: muted),
            ],
          ),
          onTap: () => showRunDetailDialog(context, runId: r.id, rule: widget.rule),
        ),
      ),
    );
  }
}

/// The record a run was about, when it was about one; greyed and unnamed when it is not the
/// viewer's to see.
class _RunRecord extends StatelessWidget {
  final RunSummary run;
  const _RunRecord({required this.run});

  @override
  Widget build(BuildContext context) {
    final entity = run.entity;
    if (entity == null) return const Text('—');
    if (entity.hidden) {
      return Text(hiddenRecordText(entity.type),
          style: TextStyle(color: Theme.of(context).disabledColor));
    }
    return Text(entity.label ?? '#${entity.id}', maxLines: 2, overflow: TextOverflow.ellipsis);
  }
}
