import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/filter_editor.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/widgets/status_chip.dart';
import 'automation_models.dart';
import 'automation_providers.dart';
import 'automation_rule_editor.dart';
import 'automation_runs_dialog.dart';

/// The rules list (R1). Every row reads as the sentence the rule is — when this happens to this
/// kind of record, and it matches these filters, do this — because that is the only way to tell at
/// a glance what a rule will do to records nobody is watching.
///
/// Reading is AUTOMATION_VIEW; everything that writes or runs is AUTOMATION_MANAGE, which the
/// server holds apart for the same reason: running a rule makes it act, and somebody given this
/// screen to look at must not be able to raise a hundred tasks from it.
class AutomationRulesScreen extends ConsumerWidget {
  final TableQuery query;
  const AutomationRulesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canManage = ref.watch(canManageAutomationProvider);

    return Scaffold(
      body: DataTableScaffold<AutomationRule>(
        entity: 'automation',
        path: '/api/automation/rules',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/automation').push(q),
        parse: AutomationRule.fromJson,
        idOf: (r) => r.id,
        // No canExport: there is no POST /api/automation/rules/export behind it, and the flag is
        // also the only thing that would switch row selection on — automation has no bulk action
        // — so it offered a tick box whose one button could only 404. The rules list is read as
        // sentences, not exported as rows (the same call the tasks list makes).
        emptyMessage: 'No rules match this filter',
        // A rule has no page of its own: everything about it is on this row or in the editor, so
        // opening one means opening the form that can change it.
        onRowTap: canManage ? (context, r) => _edit(context, ref, r) : null,
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New rule'),
              onPressed: () => showAutomationRuleEditor(context),
            ),
        ],
        columns: [
          TableColumnSpec(
            label: 'Rule',
            sortKey: 'name',
            maxWidth: 240,
            cell: (context, r) => _NameCell(rule: r),
          ),
          TableColumnSpec(
            label: 'Record',
            sortKey: 'entityType',
            cell: (context, r) => Text(r.recordLabel),
          ),
          TableColumnSpec(
            label: 'When',
            sortKey: 'trigger',
            maxWidth: 260,
            cell: (context, r) => _WhenCell(rule: r),
          ),
          TableColumnSpec(
            label: 'Then',
            sortKey: 'action',
            maxWidth: 220,
            cell: (context, r) => _ThenCell(rule: r),
          ),
          TableColumnSpec(
            label: 'Enabled',
            sortKey: 'enabled',
            cell: (context, r) => StatusChip(
              label: r.enabled ? 'On' : 'Off',
              // A rule that is off is inert: nothing will happen because of it, which is the same
              // meaning grey carries on every other status in the app.
              color: r.enabled
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.outline,
            ),
          ),
          TableColumnSpec(
            label: 'Last run',
            sortKey: 'lastRunAt',
            cell: (context, r) => Text(formatDateTime(r.lastRunAt)),
          ),
          TableColumnSpec(
            label: 'Runs',
            sortKey: 'runCount',
            numeric: true,
            cell: (context, r) => Text('${r.runCount}'),
          ),
        ],
        // Reading what a rule has been up to is AUTOMATION_VIEW, like the endpoint behind it, so
        // the Runs button is on every row; everything that writes or runs keeps to AUTOMATION_MANAGE.
        rowActions: (context, r) => [
          IconButton(
            // Not "Runs": that is the column beside it, and the column counts only the runs that
            // did something. This opens the ones that did not, which is the whole point of it.
            tooltip: 'Run history',
            icon: const Icon(Icons.history, size: 18),
            onPressed: () => showAutomationRuleRuns(context, r),
          ),
          if (canManage) ...[
            IconButton(
              tooltip: r.enabled ? 'Disable' : 'Enable',
              icon: Icon(r.enabled ? Icons.toggle_on : Icons.toggle_off_outlined, size: 22),
              onPressed: () => _setEnabled(context, ref, r, !r.enabled),
            ),
            IconButton(
              tooltip: r.enabled
                  ? 'Run now'
                  // The server refuses to run a rule that is switched off, so the button says
                  // why rather than offering an error.
                  : 'Switch the rule on before running it',
              icon: const Icon(Icons.play_arrow_outlined, size: 18),
              onPressed: r.enabled ? () => _run(context, ref, r) : null,
            ),
            IconButton(
              tooltip: 'Edit',
              icon: const Icon(Icons.edit_outlined, size: 18),
              onPressed: () => _edit(context, ref, r),
            ),
            IconButton(
              tooltip: 'Delete',
              icon: const Icon(Icons.delete_outline, size: 18),
              onPressed: () => _delete(context, ref, r),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _edit(BuildContext context, WidgetRef ref, AutomationRule rule) =>
      showAutomationRuleEditor(context, ruleId: rule.id);

  Future<void> _setEnabled(
      BuildContext context, WidgetRef ref, AutomationRule rule, bool enabled) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await setAutomationRuleEnabled(ref, rule, enabled);
      messenger.showSnackBar(SnackBar(
          content: Text(enabled ? '"${rule.name}" is on' : '"${rule.name}" is off')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(_message(e))));
    }
  }

  Future<void> _run(BuildContext context, WidgetRef ref, AutomationRule rule) async {
    final messenger = ScaffoldMessenger.of(context);
    // Stated before it happens: a rule can match every record of its kind, and running it acts on
    // all of them at once, for real, with nobody else in the loop.
    final confirmed = await _confirm(
      context,
      title: 'Run "${rule.name}" now?',
      message: 'It will ${rule.actionLabel} for every ${rule.recordLabel.toLowerCase()} it '
          'matches right now. This is the same as the rule firing on its own.',
      confirm: 'Run now',
    );
    if (confirmed != true) return;
    try {
      final result = await runAutomationRule(ref, rule.id);
      messenger.showSnackBar(SnackBar(content: Text(result.message)));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(_message(e))));
    }
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, AutomationRule rule) async {
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await _confirm(
      context,
      title: 'Delete "${rule.name}"?',
      // What it already did stays: the tasks, promises and email it raised are real records that
      // people are working from, and the server keeps its run history for the same reason.
      message: 'The rule stops running. Anything it has already done stays as it is.\n\n'
          'This cannot be undone.',
      confirm: 'Delete',
      destructive: true,
    );
    if (confirmed != true) return;
    try {
      await deleteAutomationRule(ref, rule.id);
      messenger.showSnackBar(SnackBar(content: Text('"${rule.name}" deleted')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(_message(e))));
    }
  }

  /// A refusal from the server reads as the server wrote it; anything else is this app's own.
  String _message(Object error) => error is StateError ? error.message : apiErrorMessage(error);
}

Future<bool?> _confirm(
  BuildContext context, {
  required String title,
  required String message,
  required String confirm,
  bool destructive = false,
}) {
  return showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
        FilledButton(
          style: destructive
              ? FilledButton.styleFrom(backgroundColor: Theme.of(dialogContext).colorScheme.error)
              : null,
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: Text(confirm),
        ),
      ],
    ),
  );
}

/// The rule's name, what it was written for, and — on hover — the whole rule as one sentence.
/// Every cell on the row is cut to fit its column, so somewhere has to hold the unabridged thing.
class _NameCell extends ConsumerWidget {
  final AutomationRule rule;
  const _NameCell({required this.rule});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final description = rule.description ?? '';
    final entity = rule.entityType?.tableEntity;
    final schema = entity == null ? null : ref.watch(tableSchemaProvider(entity)).valueOrNull;
    return Tooltip(
      message: [rule.describe(schema), if (description.isNotEmpty) description].join('\n\n'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(rule.name, maxLines: 1, overflow: TextOverflow.ellipsis),
          if (description.isNotEmpty)
            Text(description,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }
}

/// The WHEN and, under it, the WHERE. They are one column because they are one clause: "is
/// updated" alone does not say which invoices, and the filters are what make a rule safe to leave
/// running. The filter columns are named in the words their own list page uses, which is where
/// whoever wrote the rule chose them (R9).
class _WhenCell extends ConsumerWidget {
  final AutomationRule rule;
  const _WhenCell({required this.rule});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final entity = rule.entityType?.tableEntity;
    // The schema is the list page's own, already loaded for anyone who has opened that list; a
    // row must not wait on it, so until it lands the columns read as their field names.
    final schema = entity == null ? null : ref.watch(tableSchemaProvider(entity)).valueOrNull;
    final where = rule.filterChips.map((f) => describeFilter(f, schema)).join(', ');
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(rule.triggerLabel, maxLines: 1, overflow: TextOverflow.ellipsis),
        Tooltip(
          message: where.isEmpty ? '' : 'Where $where',
          child: Text(
            // A rule with no filters acts on every record of its kind. That is a fact about the
            // rule worth reading off the row, not an empty cell.
            where.isEmpty ? 'every ${rule.recordLabel.toLowerCase()}' : 'where $where',
            // One line: a table row is 48px whatever is in it, and two lines of filters under the
            // trigger overflow it. The whole WHERE is in the tooltip, and in the editor.
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}

/// The THEN in the server's words, with what it will say underneath — a task's title, an email's
/// subject — since two rules that both "create a task" are told apart by nothing else.
class _ThenCell extends StatelessWidget {
  final AutomationRule rule;
  const _ThenCell({required this.rule});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final detail = rule.actionSpec.title ?? rule.actionSpec.body ?? '';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(rule.actionLabel, maxLines: 1, overflow: TextOverflow.ellipsis),
        if (detail.isNotEmpty)
          Tooltip(
            message: detail,
            child: Text(detail,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style:
                    theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
      ],
    );
  }
}
