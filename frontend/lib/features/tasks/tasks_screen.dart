import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'task_actions.dart';
import 'tasks_tab.dart';

/// Every task the caller may see (T2), and — at [mine] — only the ones they are on.
///
/// "My tasks" is this same screen with the `mine` parameter forced on, rather than a screen of
/// its own or a filter chip. It cannot be a chip: `mine` is not a column of the tasks table, so
/// the filter bar would drop it, and what it means — assigned to me as a person, as a seat in a
/// customer's POC book, or as the POC this very record names — is a question only the server can
/// answer (A2). It goes out as a query parameter beside the filters, the way
/// [DataTableScaffold.extraParams] is meant to be used, and the two views get a route each so
/// that either can be linked to and bookmarked.
///
/// The server reports no locked chip for it, because it is the caller's own ask rather than a
/// scope pinned on them, so the screen says so itself: the banner above the filter bar, and the
/// button that switches between the two lists.
class TasksScreen extends ConsumerWidget {
  final TableQuery query;

  /// Holds the list to work assigned to the signed-in user.
  final bool mine;

  const TasksScreen({super.key, required this.query, this.mine = false});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canManage = ref.watch(canManageTasksProvider);
    // A customer login is nobody's assignee — work is always somebody internal's (A1) — so "my
    // tasks" could only ever be empty for them, and the switch is not offered.
    final canHaveOwnTasks = !(ref.watch(currentUserProvider)?.isCustomer ?? false);
    final path = mine ? '/tasks/mine' : '/tasks';

    return Scaffold(
      body: DataTableScaffold<Task>(
        entity: 'tasks',
        path: '/api/tasks',
        // Not a filter: it is the server's own question about who the caller is (A2).
        extraParams: mine ? const {'mine': true} : const {},
        query: query,
        onQueryChanged: (q) => RouteQuery(context, path).push(q),
        parse: Task.fromJson,
        idOf: (t) => t.id,
        header: mine ? const _MyTasksBanner() : null,
        emptyMessage:
            mine ? 'No tasks of yours match this filter' : 'No tasks match this filter',
        onRowTap: (context, t) => context.go('/tasks/${t.id}'),
        // No canExport: there is no POST /api/tasks/export behind it, and a button that can only
        // fail is worse than none.
        actions: [
          if (canHaveOwnTasks)
            OutlinedButton.icon(
              icon: Icon(mine ? Icons.list_alt_outlined : Icons.person_outline, size: 18),
              label: Text(mine ? 'All tasks' : 'My tasks'),
              onPressed: () => context.go(mine ? '/tasks' : '/tasks/mine'),
            ),
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New task'),
              // No record is in hand on a list page, so the form asks which one this is about.
              onPressed: () => showTaskDialog(context: context),
            ),
        ],
        // The two filters a working day starts from. Both name the same column with the same
        // operator, so picking one replaces the other rather than asking for tasks that are at
        // once open and done.
        quickFilters: const [
          QuickFilterSpec(
            label: 'Still open',
            icon: Icons.radio_button_unchecked,
            filter: TableFilter('status', 'in', ['OPEN', 'IN_PROGRESS']),
          ),
          QuickFilterSpec(
            label: 'Done',
            icon: Icons.check_circle_outline,
            filter: TableFilter('status', 'in', ['DONE']),
          ),
        ],
        columns: [
          // Capped like every other free-text column: a 200-character title must not widen the
          // table until the columns after it are off screen (UI-01, D-20).
          TableColumnSpec(
            label: 'Task',
            sortKey: 'title',
            maxWidth: 300,
            cell: (context, t) => Tooltip(
              message: t.title,
              child: Text(t.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600)),
            ),
          ),
          // What the work is about, opening the record itself. The label is the server's, stamped
          // on the task when it was raised, so a row still reads after the record has moved on.
          TableColumnSpec(
            label: 'Record',
            sortKey: 'entityLabel',
            maxWidth: 220,
            cell: (context, t) => _RecordLink(task: t),
          ),
          TableColumnSpec(
            label: 'Due',
            sortKey: 'dueDate',
            cell: (context, t) => TaskDueDate(task: t),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, t) => TaskStatusChip(status: t.status, label: t.statusLabel),
          ),
          // Who a role reaches is read when the row is read, so it is not a column the server can
          // sort by (A2).
          TableColumnSpec(
            label: 'Assigned to',
            maxWidth: 220,
            cell: (context, t) => TaskAssignees(assignees: t.assignees, pillWidth: 200),
          ),
        ],
        rowActions: (context, t) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/tasks/${t.id}'),
          ),
        ],
      ),
    );
  }
}

/// The record a task is about, as a link. A task always has one, but its label and link are
/// written by the server, so a build that has neither still shows the kind and the id.
class _RecordLink extends StatelessWidget {
  final Task task;
  const _RecordLink({required this.task});

  @override
  Widget build(BuildContext context) {
    final label = task.entityLabel.isEmpty
        ? '${task.entityType.label} #${task.entityId}'
        : task.entityLabel;
    final link = task.entityLink;
    final text = Text(label, maxLines: 2, overflow: TextOverflow.ellipsis);
    if (link == null) return Tooltip(message: label, child: text);
    return Tooltip(
      message: label,
      child: InkWell(
        onTap: () => context.go(link),
        child: DefaultTextStyle.merge(
          style: TextStyle(
              color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w600),
          child: text,
        ),
      ),
    );
  }
}

/// Says what the list is holding itself to, since the server reports no locked chip for a scope
/// the caller asked for themselves.
class _MyTasksBanner extends StatelessWidget {
  const _MyTasksBanner();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      color: scheme.surfaceContainerHighest,
      child: Row(
        children: [
          Icon(Icons.person_outline, size: 16, color: scheme.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Only tasks assigned to you — by name, or through a role you hold',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }
}
