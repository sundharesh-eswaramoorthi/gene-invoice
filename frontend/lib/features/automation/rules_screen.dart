import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'automation_models.dart';
import 'automation_providers.dart';
import 'run_now_dialog.dart';

/// What the rules list answers on a past date: its runs are not kept as of a date, so neither
/// the last-run and next-run sorts nor filters on them are (§5.10.10, B3).
const rulesPastSortRefused = {'lastRunAt', 'nextRunAt', 'lastRunStatus'};
const rulesPastFilterFields = {
  'name',
  'enabled',
  'status',
  'suspendedReason',
  'triggerKind',
  'entityType',
  'ownerUserId',
  'regionIds',
};

/// [query] as a past date can answer it: a refused sort becomes the default, and filters on
/// fields the past cannot filter go. The same query when nothing needs to change.
TableQuery rulesQueryForPast(TableQuery query) {
  final sortField = query.sort?.split(',').first;
  final sortRefused = sortField != null && rulesPastSortRefused.contains(sortField);
  final kept = query.filters.where((f) => rulesPastFilterFields.contains(f.field)).toList();
  if (!sortRefused && kept.length == query.filters.length) return query;
  var next = query.withFilters(kept);
  if (sortRefused) next = next.withSort('name,asc');
  return next;
}

/// The rules the user can see (A1-A5), for the regions in view (§5.10.10): whether each is on,
/// what starts it, who it runs as, and how its last run went. Rows run, switch on and off, resume
/// and delete from here; an administrator also sees how the consumer is keeping up.
class RulesScreen extends ConsumerWidget {
  final TableQuery query;
  const RulesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final historical = ref.watch(isHistoricalProvider);
    // A past date refuses some of today's sorts and filters: ask it only what it answers, and
    // put that in the URL so the link says what is shown.
    final query = historical ? rulesQueryForPast(this.query) : this.query;
    if (!identical(query, this.query)) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (context.mounted) RouteQuery(context, '/automation').push(query);
      });
    }
    final canCreate = !historical && (user?.has(Privileges.automationManage) ?? false);
    final admin = user?.has(Privileges.automationAdmin) ?? false;

    return Scaffold(
      body: DataTableScaffold<RuleSummary>(
        entity: 'automation-rules',
        path: '/api/automation/rules',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/automation').push(q),
        canSort: historical ? (key) => !rulesPastSortRefused.contains(key) : null,
        filterFields: historical ? rulesPastFilterFields : null,
        parse: RuleSummary.fromJson,
        idOf: (r) => r.id,
        selectable: false,
        emptyMessage: 'No rules match this filter',
        onRowTap: (context, r) => context.go('/automation/${r.id}'),
        header: admin ? const AutomationHealthCard() : null,
        actions: [
          if (canCreate)
            FilledButton.icon(
              key: const ValueKey('rule-new'),
              icon: const Icon(Icons.add),
              label: const Text('New rule'),
              onPressed: () => context.go('/automation/new'),
            ),
        ],
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Active', value: '${s['active'] ?? 0}'),
            SummaryTile(label: 'Disabled', value: '${s['disabled'] ?? 0}'),
            SummaryTile(
              label: 'Suspended',
              value: '${s['suspended'] ?? 0}',
              icon: Icons.pause_circle_outline,
              accent: Theme.of(context).colorScheme.error,
            ),
            SummaryTile(label: 'Failed runs (24 h)', value: '${s['failedRuns24h'] ?? 0}'),
          ],
        ),
        columns: [
          TableColumnSpec(
            label: 'Rule',
            sortKey: 'name',
            maxWidth: 260,
            cell: (context, r) => Text(r.name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          TableColumnSpec(
            label: 'Status',
            cell: (context, r) =>
                RuleStatusChip(status: r.status, suspendedReason: r.suspendedReason),
          ),
          TableColumnSpec(
            label: 'Starts',
            sortKey: 'triggerKind',
            maxWidth: 220,
            cell: (context, r) => Text('${r.entity.label} · ${r.triggerKind.label}',
                maxLines: 2, overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Runs as',
            maxWidth: 180,
            cell: (context, r) =>
                Text(ownerText(r.owner), maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          if (user?.multiRegion ?? false)
            TableColumnSpec(
              label: 'Regions',
              maxWidth: 160,
              cell: (context, r) => Text(ruleRegionsText(user, r.regionIds),
                  maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          TableColumnSpec(
            label: 'Last run',
            sortKey: 'lastRunAt',
            cell: (context, r) => Text(r.lastRunAt == null
                ? '—'
                : '${humanizeEnum(r.lastRunStatus)} · ${formatDateTime(r.lastRunAt)}'),
          ),
          TableColumnSpec(
            label: 'Next run',
            sortKey: 'nextRunAt',
            cell: (context, r) => Text(formatDateTime(r.nextRunAt)),
          ),
        ],
        rowActions: (context, r) => ruleRowActions(context, r, user: user, historical: historical),
        mobileCard: (context, r) => _RuleCard(rule: r),
      ),
    );
  }
}

String ownerText(RuleOwner? owner) {
  if (owner == null) return '—';
  return owner.active ? owner.name : '${owner.name} (inactive)';
}

/// The codes of a rule's regions, or that it works wherever its owner can act (§5.10.7).
String ruleRegionsText(CurrentUser? user, List<int> regionIds) {
  if (regionIds.isEmpty) return 'All the owner\'s regions';
  final codes = {for (final r in user?.regions ?? const <RegionAccess>[]) r.id: r.code};
  return regionIds.map((id) => codes[id] ?? '#$id').join(', ');
}

/// What the signed-in user may do to one rule (§5.10.7). Whoever makes a rule act — Run now,
/// Enable, Resume — is its owner, so a non-owner administrator may, but takes it over first
/// ([takesOwnership]). Disabling and deleting only stop it, so they take nothing over. The rule's
/// own flags decide when the server sent them (D14); a list row has none, so there the privileges
/// and the owner do. Nothing on a past date.
class RuleRights {
  final bool canAct;
  final bool takesOwnership;
  final bool canStop;
  const RuleRights({required this.canAct, required this.takesOwnership, required this.canStop});

  static const none = RuleRights(canAct: false, takesOwnership: false, canStop: false);

  factory RuleRights.of(CurrentUser? user,
      {required int? ownerId,
      required bool historical,
      bool? canRun,
      bool? canEdit,
      bool? editTakesOwnership}) {
    if (historical || user == null || user.isCustomer) return none;
    final owner = ownerId != null && ownerId == user.id;
    final manage = user.has(Privileges.automationManage);
    final admin = user.has(Privileges.automationAdmin);
    final takes = editTakesOwnership ?? (!owner && admin && manage);
    final runs = canRun ?? (owner && manage);
    return RuleRights(
      canAct: runs || takes,
      takesOwnership: !runs && takes,
      canStop: (canEdit ?? (owner && manage)) || takes || admin,
    );
  }

  factory RuleRights.forRule(CurrentUser? user, Rule rule, {required bool historical}) =>
      RuleRights.of(
        user,
        ownerId: rule.owner?.id,
        historical: historical,
        canRun: rule.canRun,
        canEdit: rule.canEdit,
        editTakesOwnership: rule.editTakesOwnership,
      );
}

/// A row's own actions, then Open.
List<Widget> ruleRowActions(BuildContext context, RuleSummary r,
    {required CurrentUser? user, required bool historical}) {
  final rights = RuleRights.of(user, ownerId: r.owner?.id, historical: historical);
  final suspended = r.status == RuleStatus.SUSPENDED;
  return [
    if (rights.canAct && !suspended)
      IconButton(
        key: ValueKey('rule-${r.id}-run'),
        tooltip: 'Run now',
        icon: const Icon(Icons.play_arrow_outlined, size: 18),
        onPressed: () => showRunNowDialog(context,
            ruleId: r.id,
            ruleName: r.name,
            entity: r.entity,
            takesOwnership: rights.takesOwnership,
            ownerName: r.owner?.name),
      ),
    if (rights.canAct && suspended)
      IconButton(
        key: ValueKey('rule-${r.id}-resume'),
        tooltip: 'Resume',
        icon: const Icon(Icons.restart_alt, size: 18),
        onPressed: () => resumeRuleFlow(context,
            id: r.id,
            name: r.name,
            suspendedReason: r.suspendedReason,
            takesOwnership: rights.takesOwnership,
            ownerName: r.owner?.name),
      ),
    if (rights.canAct && !r.enabled && !suspended)
      IconButton(
        key: ValueKey('rule-${r.id}-enable'),
        tooltip: 'Enable',
        icon: const Icon(Icons.toggle_off_outlined, size: 18),
        onPressed: () => enableRuleFlow(context,
            id: r.id,
            name: r.name,
            takesOwnership: rights.takesOwnership,
            ownerName: r.owner?.name),
      ),
    if (rights.canStop && r.enabled)
      IconButton(
        key: ValueKey('rule-${r.id}-disable'),
        tooltip: 'Disable',
        icon: const Icon(Icons.toggle_on_outlined, size: 18),
        onPressed: () => disableRuleFlow(context, id: r.id, name: r.name),
      ),
    if (rights.canStop)
      IconButton(
        key: ValueKey('rule-${r.id}-delete'),
        tooltip: 'Delete',
        icon: const Icon(Icons.delete_outline, size: 18),
        onPressed: () => deleteRuleFlow(context, id: r.id, name: r.name),
      ),
    IconButton(
      tooltip: 'Open',
      icon: const Icon(Icons.open_in_new, size: 18),
      onPressed: () => context.go('/automation/${r.id}'),
    ),
  ];
}

/// "This makes you the rule's owner; it will act with your access" (§5.10.7).
Future<bool> confirmTakeOwnership(BuildContext context, {String? ownerName}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('Take over this rule?'),
      content: Text(
        'This makes you the rule\'s owner; it will act with your access.'
        '${ownerName == null ? '' : ' $ownerName will no longer own it.'}',
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
        FilledButton(
          key: const ValueKey('take-ownership'),
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: const Text('Take over'),
        ),
      ],
    ),
  );
  return ok == true;
}

/// Sends [send] as someone who may take the rule over: asked first when [takesOwnership] is
/// already known, and asked when the server says so (400 on `confirmOwnershipChange`) — the rule
/// may have changed hands since it was loaded. Null when the user said no.
Future<T?> actTakingOwnership<T>(BuildContext context,
    {required bool takesOwnership,
    String? ownerName,
    required Future<T> Function(bool confirm) send}) async {
  var confirm = false;
  if (takesOwnership) {
    if (!await confirmTakeOwnership(context, ownerName: ownerName)) return null;
    confirm = true;
  }
  try {
    return await send(confirm);
  } catch (e) {
    if (confirm || !asksToTakeOwnership(e) || !context.mounted) rethrow;
    if (!await confirmTakeOwnership(context, ownerName: ownerName)) return null;
    return send(true);
  }
}

/// Switches a rule on; true once it is.
Future<bool> enableRuleFlow(BuildContext context,
    {required int id,
    required String name,
    required bool takesOwnership,
    String? ownerName}) async {
  final messenger = ScaffoldMessenger.of(context);
  final container = ProviderScope.containerOf(context, listen: false);
  final dio = container.read(dioProvider);
  try {
    final rule = await actTakingOwnership(context,
        takesOwnership: takesOwnership,
        ownerName: ownerName,
        send: (confirm) => enableRule(dio, id, confirmOwnershipChange: confirm));
    if (rule == null) return false;
    rulesChanged(container, id: id);
    messenger.showSnackBar(SnackBar(content: Text('"$name" is on')));
    return true;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    return false;
  }
}

/// Switches a rule off. Anyone who may stop it may, without taking it over.
Future<bool> disableRuleFlow(BuildContext context, {required int id, required String name}) async {
  final messenger = ScaffoldMessenger.of(context);
  final container = ProviderScope.containerOf(context, listen: false);
  try {
    await disableRule(container.read(dioProvider), id);
    rulesChanged(container, id: id);
    messenger.showSnackBar(SnackBar(content: Text('"$name" is off')));
    return true;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    return false;
  }
}

/// Resumes a suspended rule, asking whether it should also act on what changed meanwhile
/// (`replay`, §5.10.7).
Future<bool> resumeRuleFlow(BuildContext context,
    {required int id,
    required String name,
    String? suspendedReason,
    required bool takesOwnership,
    String? ownerName}) async {
  final replay = await showDialog<bool>(
    context: context,
    builder: (_) => _ResumeDialog(name: name, suspendedReason: suspendedReason),
  );
  if (replay == null || !context.mounted) return false;
  final messenger = ScaffoldMessenger.of(context);
  final container = ProviderScope.containerOf(context, listen: false);
  final dio = container.read(dioProvider);
  try {
    final resumed = await actTakingOwnership(context,
        takesOwnership: takesOwnership,
        ownerName: ownerName,
        send: (confirm) => resumeRule(dio, id, replay: replay, confirmOwnershipChange: confirm));
    if (resumed == null) return false;
    rulesChanged(container, id: id);
    final n = resumed.skippedWhileSuspended;
    final changes = '$n change${n == 1 ? '' : 's'} made while it was suspended';
    messenger.showSnackBar(SnackBar(
      content: Text(n == 0
          ? '"$name" is running again'
          : replay
              ? '"$name" is running again, and will act on the $changes'
              : '"$name" is running again; the $changes will not be acted on'),
    ));
    return true;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    return false;
  }
}

class _ResumeDialog extends StatefulWidget {
  final String name;
  final String? suspendedReason;
  const _ResumeDialog({required this.name, this.suspendedReason});

  @override
  State<_ResumeDialog> createState() => _ResumeDialogState();
}

class _ResumeDialogState extends State<_ResumeDialog> {
  bool _replay = false;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Resume "${widget.name}"?'),
      content: SizedBox(
        width: 440,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('It was suspended: ${suspendReasonLabel(widget.suspendedReason)}.'),
            const SizedBox(height: 8),
            CheckboxListTile(
              key: const ValueKey('resume-replay'),
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: _replay,
              onChanged: (v) => setState(() => _replay = v ?? false),
              title: const Text('Also act on the changes made while it was suspended'),
              subtitle: const Text('Otherwise it starts again from now.'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          key: const ValueKey('resume-confirm'),
          onPressed: () => Navigator.of(context).pop(_replay),
          child: const Text('Resume'),
        ),
      ],
    );
  }
}

/// Deletes a rule after asking; true once it is gone.
Future<bool> deleteRuleFlow(BuildContext context, {required int id, required String name}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text('Delete "$name"?'),
      content: const Text('It stops acting at once. What it has already done stays.'),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
        FilledButton(
          key: const ValueKey('rule-delete-confirm'),
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: const Text('Delete'),
        ),
      ],
    ),
  );
  if (ok != true || !context.mounted) return false;
  final messenger = ScaffoldMessenger.of(context);
  final container = ProviderScope.containerOf(context, listen: false);
  try {
    await deleteRule(container.read(dioProvider), id);
    rulesChanged(container);
    messenger.showSnackBar(SnackBar(content: Text('"$name" deleted')));
    return true;
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    return false;
  }
}

/// How the automation consumer is keeping up, for an administrator (§6.14 `/health`): what is
/// waiting, and each event it gave up on, with Requeue.
class AutomationHealthCard extends ConsumerWidget {
  const AutomationHealthCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(automationHealthProvider);
    final historical = ref.watch(isHistoricalProvider);
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
      child: Card(
        key: const ValueKey('automation-health'),
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: async.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text('Could not load the automation health: ${apiErrorMessage(e)}',
                style: TextStyle(color: theme.colorScheme.error)),
            data: (h) {
              if (h == null) return const SizedBox.shrink();
              final oldest = h.oldestPendingSeconds;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                          h.consumerEnabled
                              ? Icons.check_circle_outline
                              : Icons.pause_circle_outline,
                          size: 18,
                          color:
                              h.consumerEnabled ? Colors.green.shade700 : theme.colorScheme.error),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          h.consumerEnabled
                              ? 'Rules are being run'
                              : 'Rules are not being run right now',
                          style: theme.textTheme.titleSmall,
                        ),
                      ),
                      IconButton(
                        tooltip: 'Refresh',
                        icon: const Icon(Icons.refresh, size: 18),
                        onPressed: () => ref.invalidate(automationHealthProvider),
                      ),
                    ],
                  ),
                  Text(
                    [
                      '${h.pending} waiting',
                      '${h.processing} running',
                      '${h.retrying} retrying',
                      '${h.dead} given up',
                      if (oldest != null && h.pending > 0) 'oldest waiting ${_duration(oldest)}',
                    ].join(' · '),
                    style: theme.textTheme.bodySmall,
                  ),
                  for (final e in h.deadEvents)
                    ListTile(
                      key: ValueKey('dead-event-${e.id}'),
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.error_outline, color: theme.colorScheme.error, size: 18),
                      title: Text([
                        'Event #${e.id}',
                        humanizeEnum(e.kind),
                        if (e.entityType != null)
                          '${humanizeEnum(e.entityType)} #${e.entityId ?? '?'}',
                        if (e.ruleId != null) 'rule #${e.ruleId}',
                        '${e.attempts} attempt${e.attempts == 1 ? '' : 's'}',
                      ].join(' · ')),
                      subtitle: e.lastError == null
                          ? null
                          : Text(e.lastError!, maxLines: 2, overflow: TextOverflow.ellipsis),
                      trailing: _deadEventAction(context, ref, e, historical: historical),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  /// A run started by hand is retried from its run, not by requeueing its event; a manual event
  /// with no rule has nothing to open.
  Widget? _deadEventAction(BuildContext context, WidgetRef ref, DeadEvent e,
      {required bool historical}) {
    if (historical) return null;
    if (e.kind == 'MANUAL') {
      final ruleId = e.ruleId;
      if (ruleId == null) return null;
      return TextButton(
        key: ValueKey('open-runs-${e.id}'),
        onPressed: () => goGuarded(context, '/automation/$ruleId?tab=runs'),
        child: const Text('Open runs'),
      );
    }
    return TextButton(
      key: ValueKey('requeue-${e.id}'),
      onPressed: () => _requeue(context, ref, e.id),
      child: const Text('Requeue'),
    );
  }

  static String _duration(int seconds) {
    if (seconds < 60) return '${seconds}s';
    if (seconds < 3600) return '${seconds ~/ 60} min';
    return '${seconds ~/ 3600} h';
  }

  Future<void> _requeue(BuildContext context, WidgetRef ref, int eventId) async {
    final messenger = ScaffoldMessenger.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    try {
      await requeueEvent(container.read(dioProvider), eventId);
      container.invalidate(automationHealthProvider);
      messenger.showSnackBar(SnackBar(content: Text('Event #$eventId is queued again')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }
}

/// A rule on a phone.
class _RuleCard extends StatelessWidget {
  final RuleSummary rule;
  const _RuleCard({required this.rule});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(rule.name,
                  maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleSmall),
            ),
            const SizedBox(width: 8),
            RuleStatusChip(status: rule.status, suspendedReason: rule.suspendedReason),
          ],
        ),
        const SizedBox(height: 4),
        Text('${rule.entity.label} · ${rule.triggerKind.label}', style: theme.textTheme.bodySmall),
        Text('Runs as ${ownerText(rule.owner)}', style: theme.textTheme.bodySmall),
      ],
    );
  }
}
