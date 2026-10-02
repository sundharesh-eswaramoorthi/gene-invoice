import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import '../regions/regions_screen.dart';
import 'task_actions.dart';
import 'task_models.dart';
import 'task_providers.dart';

/// Every task the user can see (`/tasks`), or only those assigned to them (`/my-tasks`, [mine]):
/// directly, or through a role they hold on the record (§5.9.5). Rows can be moved along from
/// here, one at a time or in bulk.
class TasksScreen extends ConsumerWidget {
  final TableQuery query;
  final bool mine;
  const TasksScreen({super.key, required this.query, this.mine = false});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final historical = ref.watch(isHistoricalProvider);
    final canManage = ref.watch(canManageTasksProvider);
    final canExport = user?.has(Privileges.exportData) ?? false;
    final container = ProviderScope.containerOf(context, listen: false);

    return Scaffold(
      body: DataTableScaffold<TaskItem>(
        entity: 'tasks',
        path: mine ? '/api/tasks/mine' : '/api/tasks',
        // Bulk and export exist once, on every task (§6.12); from "My tasks" they are kept to
        // mine, so "select all matching" can never reach anyone else's.
        actionPath: '/api/tasks',
        actionFilters: [
          if (mine && user != null) TableFilter('assigneeUserId', 'eq', ['${user.id}']),
        ],
        query: query,
        onQueryChanged: (q) => RouteQuery(context, mine ? '/my-tasks' : '/tasks').push(q),
        parse: TaskItem.fromJson,
        idOf: (t) => t.id,
        canExport: canExport,
        emptyMessage: mine ? 'None of your tasks match this filter' : 'No tasks match this filter',
        header: mine
            ? Padding(
                padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
                child: Text('Tasks assigned to you, by name or through a role you hold',
                    style: Theme.of(context).textTheme.bodyMedium),
              )
            : null,
        onRowTap: (context, t) => context.go('/tasks/${t.id}'),
        // Other rows move with a bulk action, and so does the count on "My tasks".
        onBulkDone: () => tasksChanged(container),
        tiles: (context, s) => Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SummaryTile(label: 'Tasks', value: '${s['count'] ?? 0}'),
            SummaryTile(label: 'Open', value: '${s['open'] ?? 0}'),
            SummaryTile(label: 'In progress', value: '${s['inProgress'] ?? 0}'),
            SummaryTile(
              label: 'Overdue',
              value: '${s['overdue'] ?? 0}',
              icon: Icons.schedule_outlined,
              accent: Theme.of(context).colorScheme.error,
            ),
            SummaryTile(label: 'Due today', value: '${s['dueToday'] ?? 0}'),
          ],
        ),
        quickFilters: [
          if (!mine && user != null)
            QuickFilterSpec(
              label: 'Assigned to me',
              icon: Icons.person_outline,
              filter: TableFilter('assigneeUserId', 'eq', ['${user.id}']),
            ),
          const QuickFilterSpec(
            label: 'Overdue',
            icon: Icons.schedule_outlined,
            filter: TableFilter('overdue', 'eq', ['true']),
          ),
          const QuickFilterSpec(
            label: 'Open',
            icon: Icons.radio_button_unchecked,
            filter: TableFilter('status', 'eq', ['OPEN']),
          ),
        ],
        bulkActions: [
          if (canManage)
            for (final move in TaskMove.values)
              BulkActionSpec(
                action: move.bulkAction,
                label: move.label,
                icon: taskMoveIcon(move),
              ),
        ],
        columns: taskColumns(context, user: user, showRecord: true),
        rowActions: (context, t) =>
            taskRowActions(context, t, user: user, historical: historical),
        mobileCard: (context, t) => TaskCard(task: t),
      ),
    );
  }
}

IconData taskMoveIcon(TaskMove move) => switch (move) {
      TaskMove.start => Icons.play_arrow_outlined,
      TaskMove.done => Icons.check_circle_outline,
      TaskMove.reopen => Icons.replay,
      TaskMove.cancel => Icons.cancel_outlined,
    };

/// The columns a list of tasks shows. [showRecord] names the record each is on — every list but
/// one record's own tab.
List<TableColumnSpec<TaskItem>> taskColumns(BuildContext context,
    {required CurrentUser? user, required bool showRecord, bool showCustomer = true}) {
  return [
    TableColumnSpec(
      label: 'Task',
      sortKey: 'title',
      maxWidth: 280,
      cell: (context, t) => Tooltip(
        message: t.description ?? '',
        child: Text(t.title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontWeight: FontWeight.w600)),
      ),
    ),
    TableColumnSpec(
      label: 'Status',
      sortKey: 'status',
      cell: (context, t) => TaskStatusChip(status: t.status),
    ),
    TableColumnSpec(
      label: 'Due',
      sortKey: 'dueDate',
      cell: (context, t) => TaskDueText(task: t),
    ),
    if (showRecord)
      TableColumnSpec(
        label: 'Record',
        sortKey: 'entityLabel',
        maxWidth: 180,
        cell: (context, t) => Text(t.recordText, maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
    if (showRecord && showCustomer)
      TableColumnSpec(
        label: 'Customer',
        maxWidth: 160,
        cell: (context, t) =>
            Text(t.customerName ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
    TableColumnSpec(
      label: 'Assigned to',
      maxWidth: 220,
      cell: (context, t) => TaskAssigneesText(task: t),
    ),
    if (user?.multiRegion ?? false) regionColumn<TaskItem>((t) => t.regionCode, (t) => t.regionName),
  ];
}

/// Each row's own moves, then Open.
List<Widget> taskRowActions(BuildContext context, TaskItem t,
    {required CurrentUser? user, required bool historical}) {
  return [
    for (final move in taskMoves(t, user: user, historical: historical))
      IconButton(
        key: ValueKey('task-${t.id}-${move.name}'),
        tooltip: move.label,
        icon: Icon(taskMoveIcon(move), size: 18),
        onPressed: () => moveTask(context, t, move),
      ),
    IconButton(
      tooltip: 'Open',
      icon: const Icon(Icons.open_in_new, size: 18),
      onPressed: () => context.go('/tasks/${t.id}'),
    ),
  ];
}

/// "2026-09-28", in the error colour and marked once it has passed while the task is open.
class TaskDueText extends StatelessWidget {
  final TaskItem task;
  const TaskDueText({super.key, required this.task});

  @override
  Widget build(BuildContext context) {
    if (task.dueDate == null) return const Text('—');
    final text = Text(formatDate(task.dueDate),
        style: task.overdue ? TextStyle(color: Theme.of(context).colorScheme.error) : null);
    if (!task.overdue) return text;
    return Wrap(
      spacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [text, StatusChip(label: 'Overdue', color: Theme.of(context).colorScheme.error)],
    );
  }
}

/// Who a task is assigned to. The tooltip names whoever holds each role today when the row
/// carries them, and says when nobody holds a role. List rows describe roles briefly (no people,
/// not unresolved); those roles are left out of the tooltip rather than called unheld.
class TaskAssigneesText extends StatelessWidget {
  final TaskItem task;
  const TaskAssigneesText({super.key, required this.task});

  @override
  Widget build(BuildContext context) {
    final holders = [
      for (final a in task.assignees)
        if (a.token.isRole)
          if (a.unresolved)
            '${a.label}: nobody holds this now'
          else if (a.people.isNotEmpty)
            '${a.label}: ${a.people.map((p) => p.name).join(', ')}',
    ];
    final text = Text(task.assigneesText, maxLines: 2, overflow: TextOverflow.ellipsis);
    if (holders.isEmpty) return text;
    return Tooltip(message: holders.join('\n'), child: text);
  }
}

/// A task on a phone.
class TaskCard extends StatelessWidget {
  final TaskItem task;
  const TaskCard({super.key, required this.task});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(task.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleSmall),
            ),
            const SizedBox(width: 8),
            TaskStatusChip(status: task.status),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          [
            task.recordText,
            if (task.customerName != null && task.entityType != TaskEntityType.CUSTOMER)
              task.customerName!,
          ].join(' · '),
          style: theme.textTheme.bodySmall,
        ),
        const SizedBox(height: 4),
        Row(
          children: [
            Text('Due ', style: theme.textTheme.bodySmall),
            Flexible(child: TaskDueText(task: task)),
          ],
        ),
        const SizedBox(height: 2),
        TaskAssigneesText(task: task),
      ],
    );
  }
}
