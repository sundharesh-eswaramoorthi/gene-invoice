import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/record_assignee.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'task_actions.dart';
import 'task_form_dialog.dart';
import 'task_models.dart';
import 'task_providers.dart';
import 'tasks_screen.dart';

/// One task: what it is on, who it is assigned to and when it is due, with its moves (Start,
/// Mark done, Reopen, Cancel) and Edit — only as the server's flags allow (D14), and never on a
/// past date (D2).
class TaskDetailScreen extends ConsumerStatefulWidget {
  final int id;
  final String? initialTab;
  const TaskDetailScreen({super.key, required this.id, this.initialTab});

  @override
  ConsumerState<TaskDetailScreen> createState() => _TaskDetailScreenState();
}

class _TaskDetailScreenState extends ConsumerState<TaskDetailScreen> {
  bool _busy = false;

  Future<void> _edit(TaskItem t) async {
    final type = t.entityType;
    final entityId = t.entityId;
    if (type == null || entityId == null) return;
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    final saved = await showTaskDialog(context,
        entityType: type, entityId: entityId, entityLabel: t.entityLabel, existing: t);
    if (mounted) setState(() => _busy = false);
    if (saved == null) return;
    tasksChanged(container, id: t.id);
    messenger.showSnackBar(const SnackBar(content: Text('Task saved')));
  }

  Future<void> _move(TaskItem t, TaskMove move) async {
    setState(() => _busy = true);
    await moveTask(context, t, move);
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(taskDetailProvider(widget.id));
    final asOf = ref.watch(viewAsOfProvider);
    final historical = ref.watch(isHistoricalProvider);
    final user = ref.watch(currentUserProvider);
    final canViewAudit = user?.has(Privileges.auditView) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'task', asOf: asOf),
        onBack: () => context.go('/tasks'),
      ),
      data: (t) {
        final rights = taskRights(t, user: user, historical: historical);
        final moves = taskMovesFor(t,
            canEdit: rights.canEdit, canChangeStatus: rights.canChangeStatus);
        return DetailScaffold(
          title: t.title,
          subtitle: [
            t.recordText,
            if (t.customerName != null && t.entityType != TaskEntityType.CUSTOMER)
              t.customerName!,
          ].join(' • '),
          onBack: () => goGuarded(context, '/tasks'),
          titleTrailing: [
            TaskStatusChip(status: t.status),
            if (t.overdue)
              StatusChip(label: 'Overdue', color: Theme.of(context).colorScheme.error),
            for (final move in moves)
              move == TaskMove.done
                  ? FilledButton.icon(
                      key: ValueKey('task-${move.name}'),
                      icon: Icon(taskMoveIcon(move), size: 18),
                      label: Text(move.label),
                      onPressed: _busy ? null : () => _move(t, move),
                    )
                  : OutlinedButton.icon(
                      key: ValueKey('task-${move.name}'),
                      icon: Icon(taskMoveIcon(move), size: 18),
                      label: Text(move.label),
                      onPressed: _busy ? null : () => _move(t, move),
                    ),
            if (rights.canEdit && t.entityType != null && t.entityId != null)
              OutlinedButton.icon(
                key: const ValueKey('task-edit'),
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: _busy ? null : () => _edit(t),
              ),
          ],
          initialTabSlug: widget.initialTab,
          onTabChanged: (slug) => context.go('/tasks/${widget.id}?tab=$slug'),
          top: _top(t, showRegion: user?.multiRegion ?? false),
          tabs: [
            DetailTab(
              slug: 'details',
              label: 'Details',
              icon: Icons.notes_outlined,
              builder: (context) => _DetailsTab(task: t),
            ),
            if (canViewAudit)
              DetailTab(
                slug: 'history',
                label: 'History',
                icon: Icons.history,
                builder: (context) => SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: AuditHistoryPanel(entityType: 'TASK', entityId: t.id),
                ),
              ),
          ],
        );
      },
    );
  }

  Widget _top(TaskItem t, {required bool showRegion}) {
    final scheme = Theme.of(context).colorScheme;
    final linkStyle = TextStyle(color: scheme.primary, fontWeight: FontWeight.w600);
    Widget link(String text, String? route) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: route == null
              ? Text(text)
              : InkWell(onTap: () => goGuarded(context, route), child: Text(text, style: linkStyle)),
        );

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: DetailGrid(items: [
        DetailGridItem(
          label: 'On',
          child: link(t.entityDeleted ? '${t.recordText} (deleted)' : t.recordText, t.recordRoute),
        ),
        if (t.customerName != null && t.entityType != TaskEntityType.CUSTOMER)
          DetailGridItem(
            label: 'Customer',
            child: link(t.customerName!,
                t.customerId == null ? null : TaskEntityType.CUSTOMER.route(t.customerId!)),
          ),
        if (showRegion)
          DetailGridItem(label: 'Region', child: ReadOnlyValue(t.regionName ?? t.regionCode ?? '')),
        DetailGridItem(
          label: 'Due',
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 10),
            child: TaskDueText(task: t),
          ),
        ),
        DetailGridItem(
          label: 'Created',
          child: ReadOnlyValue([
            formatDateTime(t.createdAt),
            if (t.createdByName != null) 'by ${t.createdByName}',
          ].join(' ')),
        ),
        if (t.origin == 'AUTOMATION')
          DetailGridItem(
            label: 'Created by rule',
            child: link(t.ruleName ?? 'Rule #${t.ruleId ?? '?'}',
                t.ruleId == null ? null : '/automation/${t.ruleId}'),
          ),
        if (t.completedAt != null)
          DetailGridItem(label: 'Completed', child: ReadOnlyValue(formatDateTime(t.completedAt))),
      ]),
    );
  }
}

class _DetailsTab extends StatelessWidget {
  final TaskItem task;
  const _DetailsTab({required this.task});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final description = task.description ?? '';
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Description', style: theme.textTheme.titleSmall),
        const SizedBox(height: 6),
        Text(description.isEmpty ? 'No description' : description,
            style: description.isEmpty ? TextStyle(color: theme.hintColor) : null),
        const SizedBox(height: 20),
        Text('Assigned to', style: theme.textTheme.titleSmall),
        const SizedBox(height: 6),
        if (task.assignees.isEmpty)
          Text('Nobody', style: TextStyle(color: theme.hintColor))
        else
          for (final a in task.assignees) _AssigneeTile(assignee: a),
      ],
    );
  }
}

/// One assignee and, for a role, whoever holds it today (A4: the primary first).
class _AssigneeTile extends StatelessWidget {
  final RecordAssignee assignee;
  const _AssigneeTile({required this.assignee});

  @override
  Widget build(BuildContext context) {
    final a = assignee;
    final people = [
      for (final p in a.people) p.primary && a.people.length > 1 ? '${p.name} (primary)' : p.name,
    ];
    final String? detail = a.unresolved
        ? (a.token.isRole ? 'Nobody holds this role now' : 'Can no longer see this task')
        : a.token.isRole
            ? (people.isEmpty ? 'Nobody holds this role now' : people.join(', '))
            : null;
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(a.token.isUser ? Icons.person_outline : Icons.badge_outlined),
      title: Text(a.label.isEmpty ? '—' : a.label),
      subtitle: detail == null ? null : Text(detail),
      trailing: a.unresolved
          ? Icon(Icons.warning_amber_outlined, color: Colors.orange.shade800, size: 18)
          : null,
    );
  }
}
