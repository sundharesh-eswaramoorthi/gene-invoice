import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/filter_editor.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/widgets/detail_scaffold.dart';
import 'activity_screen.dart';
import 'automation_providers.dart';
import 'rules_screen.dart' show describeTrigger;
import 'run_now_sheet.dart';

/// ONE RULE'S OWN PAGE (A1, A5).
///
/// Read-only at the top and edited on its own full page, because a rule is a paragraph of
/// consequences and a dialog is the wrong shape for one. The Runs tab is the activity list with
/// `ruleId:eq:<id>` pinned — the same table, narrowed, which is why there is no second endpoint
/// and no second screen (A5).
class RuleDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const RuleDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(ruleDetailProvider(id));
    final canManage = ref.watch(canManageAutomationProvider);
    final canRun = ref.watch(canRunAutomationProvider);

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'rule'),
        onBack: () => context.go('/automation/rules'),
      ),
      data: (rule) => DetailScaffold(
        title: rule.name,
        subtitle: [
          rule.subjectType?.plural ?? 'records',
          describeTrigger(rule).toLowerCase(),
          if (rule.createdByName != null) 'by ${rule.createdByName}',
        ].join(' • '),
        onBack: () => goGuarded(context, '/automation/rules'),
        titleTrailing: [
          _EnabledChip(enabled: rule.enabled),
          if (canRun)
            OutlinedButton.icon(
              icon: const Icon(Icons.play_arrow, size: 18),
              label: const Text('Run now'),
              onPressed: rule.enabled
                  ? () => showRunNowSheet(context,
                      ruleId: rule.id, ruleName: rule.name, enabled: rule.enabled)
                  : null,
            ),
          if (canManage)
            TextButton.icon(
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('Edit'),
              onPressed: () => goGuarded(context, '/automation/rules/${rule.id}'
                  '?edit=true'),
            ),
          if (canManage)
            TextButton.icon(
              icon: Icon(rule.enabled
                  ? Icons.toggle_off_outlined
                  : Icons.toggle_on_outlined),
              label: Text(rule.enabled ? 'Switch off' : 'Switch on'),
              onPressed: () => _toggle(context, ref, rule),
            ),
          if (canManage)
            TextButton.icon(
              icon: const Icon(Icons.delete_outline, size: 18),
              style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              label: const Text('Delete'),
              onPressed: () => _delete(context, ref, rule),
            ),
        ],
        initialTabSlug: initialTab,
        onTabChanged: (slug) => context.go('/automation/rules/$id?tab=$slug'),
        top: _top(context, ref, rule),
        tabs: [
          DetailTab(
            slug: 'does',
            label: 'What it does',
            icon: Icons.bolt_outlined,
            builder: (context) => _Actions(rule: rule),
          ),
          DetailTab(
            slug: 'runs',
            label: 'Runs',
            icon: Icons.history,
            // The activity list, pinned to this rule. ONE schema behind both, so a reader does
            // not have to learn two tables (A5).
            builder: (context) => AutomationActivityScreen(
              query: const TableQuery(size: 20, sort: 'id,desc'),
              pinned: TableFilter('ruleId', 'eq', ['${rule.id}']),
              showHeader: false,
            ),
          ),
        ],
      ),
    );
  }

  Widget _top(BuildContext context, WidgetRef ref, AutomationRule rule) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            if ((rule.description ?? '').isNotEmpty) ...[
              Text(rule.description!),
              const SizedBox(height: 12),
            ],
            DetailGrid(items: [
              DetailGridItem(label: 'About', child: Text(rule.subjectType?.plural ?? '—')),
              DetailGridItem(label: 'Trigger', child: Text(describeTrigger(rule))),
              DetailGridItem(
                label: 'Branches',
                span: 2,
                child: ReadOnlyValue(rule.reachLabel),
              ),
              DetailGridItem(
                label: 'Conditions',
                span: 2,
                child: _Conditions(rule: rule),
              ),
              DetailGridItem(
                label: 'Cooldown',
                child: Text(rule.cooldownDays == null
                    ? 'None'
                    : 'Once every ${rule.cooldownDays} day${rule.cooldownDays == 1 ? '' : 's'} '
                        'per record'),
              ),
              DetailGridItem(
                label: 'Next run',
                child: Text(rule.nextRunAt == null ? '—' : formatDateTime(rule.nextRunAt)),
              ),
              DetailGridItem(
                label: 'Last run',
                child: Text(rule.lastRunAt == null ? 'Never' : formatDateTime(rule.lastRunAt)),
              ),
              DetailGridItem(
                label: 'Version',
                // definitionVersion is what a step froze when it was planned, so it is the thing
                // a reader compares against 'The rule changed before this ran' (A5).
                child: ReadOnlyValue('#${rule.definitionVersion}'),
              ),
            ]),
          ],
        ),
      );

  Future<void> _toggle(BuildContext context, WidgetRef ref, AutomationRule rule) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await setRuleEnabled(ref, rule, !rule.enabled);
      messenger.showSnackBar(SnackBar(
          content: Text(rule.enabled ? 'Rule switched off' : 'Rule switched on')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, AutomationRule rule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Delete this rule?'),
        content: const Text(
            'It stops running and leaves the list. Everything it has already done stays in the '
            'history and still names it.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await deleteRule(ref, rule.id);
      messenger.showSnackBar(const SnackBar(content: Text('Rule deleted')));
      if (context.mounted) goGuarded(context, '/automation/rules');
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }
}

class _EnabledChip extends StatelessWidget {
  final bool enabled;
  const _EnabledChip({required this.enabled});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = enabled ? Colors.green.shade700 : scheme.outline;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Text(enabled ? 'On' : 'Off',
          style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

/// The saved tree, read back the way it was written — the chips are [describeFilter] against the
/// subject's own schema, which is what the filter bar shows for the very same string (A2).
class _Conditions extends ConsumerWidget {
  final AutomationRule rule;
  const _Conditions({required this.rule});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tree = rule.conditions;
    final subject = rule.subjectType;
    if (tree == null || tree.leafCount == 0 || subject == null) {
      return ReadOnlyValue('Every one of these ${subject?.plural ?? 'records'}');
    }
    final schema = ref.watch(tableSchemaProvider(subject.tableEntity)).valueOrNull;
    return Align(
      alignment: Alignment.centerLeft,
      child: _node(context, tree, schema, 0),
    );
  }

  Widget _node(BuildContext context, ConditionNode node, TableSchema? schema, int depth) {
    if (node is ConditionLeaf) {
      return Chip(label: Text(describeFilter(node.filter, schema)));
    }
    final group = node as ConditionGroup;
    return Padding(
      padding: EdgeInsets.only(left: depth == 0 ? 0 : 12, top: depth == 0 ? 0 : 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(group.op == Connector.AND ? 'All of' : 'Any of',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant)),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final child in group.of)
                if (child is ConditionLeaf) _node(context, child, schema, depth + 1),
            ],
          ),
          for (final child in group.of)
            if (child is ConditionGroup) _node(context, child, schema, depth + 1),
        ],
      ),
    );
  }
}

class _Actions extends StatelessWidget {
  final AutomationRule rule;
  const _Actions({required this.rule});

  @override
  Widget build(BuildContext context) {
    if (rule.actions.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Text('This rule does nothing, which cannot happen through the builder.'),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: rule.actions.length,
      itemBuilder: (context, i) {
        final action = rule.actions[i];
        return Card(
          margin: const EdgeInsets.only(bottom: 12),
          child: ListTile(
            leading: CircleAvatar(child: Text('${i + 1}')),
            title: Text(action.kind.label),
            subtitle: Text(action.summary),
          ),
        );
      },
    );
  }
}
