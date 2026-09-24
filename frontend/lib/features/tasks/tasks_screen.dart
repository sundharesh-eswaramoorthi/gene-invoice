import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/reference_picker.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'task_form_dialog.dart';
import 'task_models.dart';
import 'task_providers.dart';

/// The tasks list — and, with three parameters in the URL, "my work" as well.
///
/// One `DataTableScaffold` against the published `tasks` schema and nothing bespoke, so the region
/// narrowing, the locked chips, every filter operator, sorting, paging, bulk and the export all
/// come from the same funnel as /api/invoices (A6, B1).
class TasksScreen extends ConsumerWidget {
  final TableQuery query;
  const TasksScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = ref.watch(canManageTasksProvider);
    final canExport = ref.watch(canExportTasksProvider);

    return Scaffold(
      body: DataTableScaffold<Task>(
        entity: 'tasks',
        path: '/api/tasks',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/tasks').push(q),
        parse: Task.fromJson,
        idOf: (t) => t.id,
        canExport: canExport,
        emptyMessage: 'No tasks match this filter',
        onRowTap: (context, t) => context.go('/tasks/${t.id}'),
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New task'),
              onPressed: () => showTaskDialog(context: context),
            ),
        ],
        quickFilters: [
          const QuickFilterSpec(
            label: 'Overdue only',
            icon: Icons.warning_amber_outlined,
            filter: TableFilter('overdue', 'eq', ['true']),
          ),
          // The same chip "my work" seeds the URL with, so arriving from the sidebar and ticking
          // this here produce the very same list — there is one filter, not two notions of mine
          // (A6).
          if (user != null)
            QuickFilterSpec(
              label: 'Assigned to me',
              icon: Icons.person_outline,
              filter: TableFilter('assigneeUserId', 'eq', ['${user.id}']),
            ),
        ],
        tiles: (context, summary) {
          final s = TaskSummary.fromJson(summary);
          return Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              SummaryTile(label: 'Open', value: '${s.open}', icon: Icons.radio_button_unchecked),
              SummaryTile(
                label: 'In progress',
                value: '${s.inProgress}',
                icon: Icons.timelapse,
                accent: Colors.orange.shade800,
              ),
              SummaryTile(
                label: 'Overdue',
                value: '${s.overdue}',
                icon: Icons.warning_amber_outlined,
                accent: Theme.of(context).colorScheme.error,
              ),
              // "Due in 7 days" and not "due this week": the server counts seven days from today
              // inclusive (TaskService.DUE_SOON_DAYS), which on a Friday is not what a reader
              // means by the rest of the week. The label states the rule the figure obeys (A6).
              SummaryTile(
                label: 'Due in 7 days',
                value: '${s.dueThisWeek}',
                icon: Icons.event_outlined,
              ),
              SummaryTile(
                label: 'Done',
                value: '${s.done}',
                icon: Icons.check_circle_outline,
                accent: Colors.green.shade700,
              ),
            ],
          );
        },
        bulkActions: [
          if (canManage) ...[
            const BulkActionSpec(
              action: 'COMPLETE',
              label: 'Mark complete',
              icon: Icons.check_circle_outline,
            ),
            const BulkActionSpec(
              action: 'CANCEL',
              label: 'Cancel tasks',
              icon: Icons.cancel_outlined,
              destructive: true,
            ),
            BulkActionSpec(
              action: 'REASSIGN',
              label: 'Reassign',
              icon: Icons.person_search_outlined,
              buildParams: (context) => pickAssigneeParams(context, ref),
            ),
          ],
        ],
        columns: [
          TableColumnSpec(
            label: 'Title',
            sortKey: 'title',
            maxWidth: 260,
            cell: (context, t) => Tooltip(
              message: t.title,
              child: Text(t.title, maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
            label: 'About',
            sortKey: 'entityLabel',
            maxWidth: 200,
            cell: (context, t) => TaskRecordLink(task: t),
          ),
          // customerId is a REFERENCE column and notSortable in the published schema, so the
          // heading is a plain one: offering a sort the server answers 400 to is worse than
          // offering none (A6, TBL-10).
          TableColumnSpec(
            label: 'Customer',
            maxWidth: 200,
            cell: (context, t) => Tooltip(
              message: t.customerName ?? '',
              child:
                  Text(t.customerName ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
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
                // Overdue is not a status — it is worked out from today's date — so it sits
                // beside the status chip rather than replacing it, exactly as on an invoice (D3).
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
          if (t.recordLink != null && (user?.hasIn(t.entityType!.recordViewPrivilege, t.regionId) ?? false))
            IconButton(
              tooltip: 'Open ${t.entityType!.noun}',
              icon: const Icon(Icons.open_in_new, size: 18),
              onPressed: () => goGuarded(context, t.recordLink!),
            ),
          // hasIn, not has: finishing a task is a write on the account it hangs off, in ITS
          // branch, and holding TASK_MANAGE somewhere else is not permission here (A6, B1).
          if ((user?.hasIn(Privileges.taskManage, t.regionId) ?? false) && !t.status.terminal)
            IconButton(
              tooltip: 'Mark complete',
              icon: const Icon(Icons.check_circle_outline, size: 18),
              onPressed: () => completeTaskFromList(context, ref, t),
            ),
        ],
      ),
    );
  }
}

/// Finishing one task from a row. The rows AND the tiles are put back in step, because the tiles
/// are counted over the same filters the list ran and a "Done" that moved a row without moving the
/// figure above it would read as a lost save (A6).
Future<void> completeTaskFromList(BuildContext context, WidgetRef ref, Task task) async {
  final messenger = ScaffoldMessenger.of(context);
  try {
    await completeTask(ref, task.id);
    messenger.showSnackBar(const SnackBar(content: Text('Task marked complete')));
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
  }
}

/// The params a bulk REASSIGN needs.
///
/// One person, and the server REPLACES the whole seat list with them — it does not add them to it.
/// The confirmation the scaffold shows already says how many rows it will touch; this says who
/// they will belong to afterwards (A6).
Future<Map<String, dynamic>?> pickAssigneeParams(BuildContext context, WidgetRef ref) async {
  ReferenceOption? picked;
  // No record to take a branch from: a bulk run spans as many branches as the filter does, so the
  // picker offers everybody assignable in any branch the caller works in and the server refuses
  // the rows where this person does not (B1).
  final regionIds = pickerRegionsFor(ref.read(currentUserProvider));
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => StatefulBuilder(
      builder: (dialogContext, setState) => AlertDialog(
        title: const Text('Reassign tasks'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Whoever is on these tasks now is replaced by the person you pick.'),
              const SizedBox(height: 8),
              ReferencePicker(
                kind: 'pocUser',
                value: picked,
                regionIds: regionIds,
                onChanged: (o) => setState(() => picked = o),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Cancel')),
          FilledButton(
            onPressed:
                picked == null ? null : () => Navigator.of(dialogContext).pop(true),
            child: const Text('Continue'),
          ),
        ],
      ),
    ),
  );
  if (confirmed != true || picked == null) return null;
  return {'userId': picked!.id};
}

/// The record a task is about, as a link when the reader may open it and as plain text when they
/// may not. A task carries its subject's LABEL, so offering a link to a page the router would
/// bounce them straight off is worse than saying nothing (A6, UI-10).
class TaskRecordLink extends ConsumerWidget {
  final Task task;
  const TaskRecordLink({super.key, required this.task});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final link = task.recordLink;
    final label = task.recordLabel;
    final mayOpen = link != null &&
        (user?.hasIn(task.entityType!.recordViewPrivilege, task.regionId) ?? false);
    final text = Text(label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: mayOpen
            ? TextStyle(
                color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w600)
            : null);
    if (!mayOpen) return Tooltip(message: label, child: text);
    return Tooltip(
      message: label,
      child: InkWell(onTap: () => goGuarded(context, link), child: text),
    );
  }
}

/// Who is on a task. Two names and a count, because a rule that fans a role out can put half a
/// team on one row and a cell that grew with it would push every other column off the page (A6).
class TaskAssigneesCell extends StatelessWidget {
  final Task task;
  const TaskAssigneesCell({super.key, required this.task});

  @override
  Widget build(BuildContext context) {
    if (task.assignees.isEmpty) return const Text('—');
    final shown = task.assignees.take(2).map((a) => a.name).join(', ');
    final extra = task.assignees.length - 2;
    return Tooltip(
      message: task.assignees.map((a) => '${a.display} • ${a.sourceLabel(task.entityType)}').join('\n'),
      child: Text(extra > 0 ? '$shown +$extra' : shown,
          maxLines: 1, overflow: TextOverflow.ellipsis),
    );
  }
}
