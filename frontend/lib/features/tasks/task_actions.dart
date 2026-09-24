import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'task_form_dialog.dart';
import 'task_models.dart';
import 'task_providers.dart';
import 'tasks_screen.dart';

export 'task_models.dart' show Task, TaskEntityType, TaskStatus;

/// The Tasks tab for a customer, an invoice or a payment — the `documentsDetailTab` shape (A6).
///
/// Null for somebody without TASK_VIEW: every task endpoint is behind it, so the tab would have
/// nothing to show and asking would only earn a 403 per record (A6, UI-10).
///
/// [regionId] is the branch the record lives in, and is what "New task" is offered on — holding
/// TASK_MANAGE somewhere is not holding it here (B1).
DetailTab? tasksDetailTab(
  WidgetRef ref, {
  required TaskEntityType type,
  required int entityId,
  String? entityLabel,
  int? regionId,
}) {
  if (!ref.watch(canViewTasksProvider)) return null;
  return DetailTab(
    slug: 'tasks',
    label: 'Tasks',
    icon: Icons.task_alt_outlined,
    builder: (context) => TasksTab(
        type: type, entityId: entityId, entityLabel: entityLabel, regionId: regionId),
  );
}

/// What is outstanding on one record.
///
/// The REGISTERED `tasks` schema and the generic table, not an ad-hoc list: the tab is filterable,
/// sortable and paged, and — far more importantly — it is scoped by the very same funnel the main
/// list is, so a task on a record in a branch this caller cannot see is absent here for exactly
/// the reason it is absent there (A6, B1).
///
/// The record is named through [DataTableScaffold.extraParams] and NOT as a filter chip. The
/// server merges entityType/entityId into the chips itself (TaskController.withContext), and a
/// chip would be removable — one click and the tab would be listing every task in the company
/// under a record's heading (A6).
class TasksTab extends ConsumerStatefulWidget {
  final TaskEntityType type;
  final int entityId;
  final String? entityLabel;
  final int? regionId;

  const TasksTab({
    super.key,
    required this.type,
    required this.entityId,
    this.entityLabel,
    this.regionId,
  });

  @override
  ConsumerState<TasksTab> createState() => _TasksTabState();
}

class _TasksTabState extends ConsumerState<TasksTab> {
  /// The query lives here rather than in the URL: a tab is not a page, and pushing its paging and
  /// sorting into the record's own address would fight the `?tab=` parameter the scaffold already
  /// keeps there (A6).
  TableQuery _query = const TableQuery(sort: 'dueDate,asc');

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final canManage = ref.watch(canManageTasksInProvider(widget.regionId));

    return DataTableScaffold<Task>(
      entity: 'tasks',
      path: '/api/tasks',
      query: _query,
      onQueryChanged: (q) => setState(() => _query = q),
      extraParams: {'entityType': widget.type.wire, 'entityId': widget.entityId},
      parse: Task.fromJson,
      idOf: (t) => t.id,
      // No bulk actions, no export and nothing to tick. A bulk run posts the filter bar's chips
      // and NOT extraParams, so "select all matching this filter" inside a tab would reach every
      // task in the caller's book rather than this record's — the record scope would silently
      // fall off exactly where it matters most. Those controls belong on /tasks, which has the
      // scope in its own chips (A6).
      selectable: false,
      canExport: false,
      emptyMessage: 'No tasks match this filter',
      onRowTap: (context, t) => context.go('/tasks/${t.id}'),
      actions: [
        if (canManage)
          FilledButton.icon(
            icon: const Icon(Icons.add),
            label: const Text('New task'),
            onPressed: () => showTaskDialog(
              context: context,
              type: widget.type,
              entityId: widget.entityId,
              entityLabel: widget.entityLabel,
              regionId: widget.regionId,
            ),
          ),
      ],
      columns: [
        TableColumnSpec(
          label: 'Title',
          sortKey: 'title',
          maxWidth: 280,
          cell: (context, t) => Tooltip(
            message: t.title,
            child: Text(t.title, maxLines: 2, overflow: TextOverflow.ellipsis),
          ),
        ),
        TableColumnSpec(
          label: 'Due',
          sortKey: 'dueDate',
          cell: (context, t) => Wrap(
            spacing: 6,
            runSpacing: 2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(t.dueDate == null ? '—' : formatDate(t.dueDate)),
              if (t.overdue) const OverdueBadge(daysOverdue: 0, compact: true),
            ],
          ),
        ),
        TableColumnSpec(
          label: 'Status',
          sortKey: 'status',
          cell: (context, t) => TaskStatusChip(status: t.status),
        ),
        TableColumnSpec(
          label: 'Assigned to',
          maxWidth: 200,
          cell: (context, t) => TaskAssigneesCell(task: t),
        ),
      ],
      rowActions: (context, t) => [
        // hasIn against the TASK's own branch and not the page's: they are the same account
        // today, and reading the row's answer is what keeps being true if that ever stops being
        // so (A6, B1).
        if ((user?.hasIn(Privileges.taskManage, t.regionId) ?? false) && !t.status.terminal)
          IconButton(
            tooltip: 'Mark complete',
            icon: const Icon(Icons.check_circle_outline, size: 18),
            onPressed: () => completeTaskFromList(context, ref, t),
          ),
      ],
    );
  }
}
