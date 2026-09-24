import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/unsaved_changes.dart';
import 'automation_providers.dart';
import 'run_now_sheet.dart';

/// THE RULES LIST (A1).
///
/// One [DataTableScaffold] against the published `automationRules` schema and nothing bespoke, so
/// every filter operator, the sorting, the paging and the bulk actions all come from the same
/// funnel as /api/invoices — the export alone does not, because this controller has no export. A rule belongs to no branch — the axis is NONE — and WHICH
/// rules a reader may see is answered by the server's own visibility predicate over the branches
/// each rule names (A1, B1).
class AutomationRulesScreen extends ConsumerWidget {
  final TableQuery query;
  const AutomationRulesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canManage = ref.watch(canManageAutomationProvider);
    final canRun = ref.watch(canRunAutomationProvider);

    return Scaffold(
      body: DataTableScaffold<AutomationRule>(
        entity: 'automationRules',
        path: '/api/automation/rules',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/automation/rules').push(q),
        parse: AutomationRule.fromJson,
        idOf: (r) => r.id,
        // NO EXPORT, and its absence is the point: `canExport` makes DataTableScaffold post to
        // `<path>/export`, and AutomationController has no such mapping — the ten controllers that
        // do are enumerated in AsOfEndpoints, and automation is not among them. Offering the button
        // put a 405 "POST is not supported here" behind it, and, worse, `_selectable` is true as
        // soon as canExport is, so a read-only holder of EXPORT_DATA got checkboxes whose only
        // action failed. A rule is a definition, not a report; if an export is ever wanted the
        // mapping comes first (A1).
        emptyMessage: 'No rules match this filter',
        onRowTap: (context, r) => goGuarded(context, '/automation/rules/${r.id}'),
        actions: [
          OutlinedButton.icon(
            icon: const Icon(Icons.history),
            label: const Text('Activity'),
            onPressed: () => goGuarded(context, '/automation/activity'),
          ),
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New rule'),
              onPressed: () => goGuarded(context, '/automation/rules/new'),
            ),
        ],
        quickFilters: const [
          QuickFilterSpec(
            label: 'Switched on',
            icon: Icons.toggle_on_outlined,
            filter: TableFilter('enabled', 'eq', ['true']),
          ),
        ],
        bulkActions: [
          if (canManage) ...[
            BulkActionSpec(
              action: 'ENABLE',
              label: 'Switch on',
              icon: Icons.toggle_on_outlined,
              successMessage: (n) => 'Switched on $n rule${n == 1 ? '' : 's'}',
            ),
            BulkActionSpec(
              action: 'DISABLE',
              label: 'Switch off',
              icon: Icons.toggle_off_outlined,
              successMessage: (n) => 'Switched off $n rule${n == 1 ? '' : 's'}',
            ),
            // BOTH privileges, and that is not belt-and-braces: the bulk MAPPING is gated on
            // AUTOMATION_MANAGE and the RUN_NOW arm re-checks AUTOMATION_RUN inside it. Offering
            // the button on MANAGE alone would hand somebody a 403 after they had picked the rows
            // (A5, AUTH-05).
            if (canRun)
              BulkActionSpec(
                action: 'RUN_NOW',
                label: 'Run now',
                icon: Icons.play_arrow,
                successMessage: (n) => 'Queued $n rule${n == 1 ? '' : 's'}',
              ),
          ],
        ],
        columns: [
          TableColumnSpec(
            label: 'Name',
            sortKey: 'name',
            maxWidth: 260,
            cell: (context, r) => Tooltip(
              message: r.description ?? r.name,
              child: Text(r.name, maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
            label: 'Subject',
            sortKey: 'subjectType',
            cell: (context, r) => Text(r.subjectType?.label ?? '—'),
          ),
          TableColumnSpec(
            label: 'Trigger',
            sortKey: 'triggerKind',
            maxWidth: 200,
            cell: (context, r) => Text(describeTrigger(r), maxLines: 2,
                overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Actions',
            maxWidth: 220,
            cell: (context, r) => Tooltip(
              message: r.actions.map((a) => '${a.kind.label}: ${a.summary}').join('\n'),
              child: Text(
                r.actions.isEmpty
                    ? '—'
                    : r.actions.map((a) => a.kind.label).join(', '),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
          TableColumnSpec(
            label: 'On',
            sortKey: 'enabled',
            cell: (context, r) => Icon(
              r.enabled ? Icons.check_circle_outline : Icons.remove_circle_outline,
              size: 18,
              color: r.enabled
                  ? Colors.green.shade700
                  : Theme.of(context).colorScheme.outline,
            ),
          ),
          TableColumnSpec(
            label: 'Last run',
            sortKey: 'lastRunAt',
            cell: (context, r) => Text(
                r.lastRunAt == null ? 'Never' : formatDateTime(r.lastRunAt)),
          ),
        ],
        rowActions: (context, r) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => goGuarded(context, '/automation/rules/${r.id}'),
          ),
          if (canRun)
            IconButton(
              tooltip: r.enabled ? 'Run now' : 'Switched off',
              icon: const Icon(Icons.play_arrow, size: 18),
              onPressed: r.enabled
                  ? () => showRunNowSheet(context,
                      ruleId: r.id, ruleName: r.name, enabled: r.enabled)
                  : null,
            ),
        ],
      ),
    );
  }
}

/// The trigger in a sentence, with the honest drift stated where it matters: a daily rule fires
/// WITHIN A MINUTE of its hour, because the sweeper runs every 60 seconds (A1).
String describeTrigger(AutomationRule rule) {
  final kind = rule.triggerKind;
  if (!kind.scheduled) return kind.label;
  final hour = '${(rule.scheduleHourUtc ?? 0).toString().padLeft(2, '0')}:00 UTC';
  if (kind == AutomationTrigger.SCHEDULE_DAILY) return 'Every day at $hour';
  return 'Every ${weekdayName(rule.scheduleDayOfWeek)} at $hour';
}

/// ISO day numbering, 1 = Monday, exactly as the server reads `scheduleDayOfWeek`.
String weekdayName(int? day) => switch (day) {
      1 => 'Monday',
      2 => 'Tuesday',
      3 => 'Wednesday',
      4 => 'Thursday',
      5 => 'Friday',
      6 => 'Saturday',
      7 => 'Sunday',
      _ => 'Monday',
    };
