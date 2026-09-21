import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/assignee.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'task_actions.dart';
import 'task_providers.dart';
import 'tasks_tab.dart';

/// A task's own page: what the work is, what it hangs off, and every change ever made to it.
///
/// Like a promise, a task changes only through its dialogs — the form, "Mark done", Delete — so
/// the top is read-only and there is nothing unsaved to guard. Leaving still goes through
/// [goGuarded], because a screen further back may be holding edits of its own.
class TaskDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const TaskDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(taskDetailProvider(id));
    final canManage = ref.watch(canManageTasksProvider);
    final canViewAudit = ref.watch(currentUserProvider)?.has(Privileges.auditView) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'task'),
        onBack: () => context.go('/tasks'),
      ),
      data: (task) => DetailScaffold(
        title: task.title,
        subtitle: _subtitle(task),
        onBack: () => goGuarded(context, '/tasks'),
        titleTrailing: [
          TaskStatusChip(status: task.status, label: task.statusLabel),
          if (canManage && !task.isTerminal)
            TextButton.icon(
              icon: const Icon(Icons.check_circle_outline, size: 18),
              label: const Text('Mark done'),
              onPressed: () => _markDone(context, ref, task),
            ),
          if (canManage)
            TextButton.icon(
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('Edit'),
              onPressed: () => showTaskDialog(context: context, existing: task),
            ),
          if (canManage)
            TextButton.icon(
              icon: const Icon(Icons.delete_outline, size: 18),
              style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              label: const Text('Delete'),
              onPressed: () => _delete(context, ref, task),
            ),
        ],
        initialTabSlug: initialTab,
        onTabChanged: (slug) => context.go('/tasks/$id?tab=$slug'),
        top: _top(context, task),
        tabs: [
          if (canViewAudit)
            DetailTab(
              slug: 'history',
              label: 'History',
              icon: Icons.history,
              builder: (context) => SingleChildScrollView(
                padding: const EdgeInsets.all(12),
                child: AuditHistoryPanel(entityType: 'TASK', entityId: task.id),
              ),
            ),
        ],
      ),
    );
  }

  /// The record and the due date, which together say what this task is and when it matters.
  String _subtitle(Task task) {
    final record = _recordText(task);
    return task.dueDate == null ? record : '$record • due ${formatDate(task.dueDate)}';
  }

  /// What the record is called: the label the server stamped on the task when it was raised, so
  /// the line still reads after the record itself has moved on.
  String _recordText(Task task) => task.entityLabel.isEmpty
      ? '${task.entityType.label} #${task.entityId}'
      : task.entityLabel;

  Widget _top(BuildContext context, Task task) {
    final notes = task.notes ?? '';
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: DetailGrid(items: [
        DetailGridItem(
          label: 'About',
          child: _record(context, task),
        ),
        DetailGridItem(label: 'Due', child: TaskDueDate(task: task)),
        DetailGridItem(
          label: 'Status',
          child: TaskStatusChip(status: task.status, label: task.statusLabel),
        ),
        // The whole list, not the table's compacted two: this is the page somebody opens to
        // find out who is on it, and a role shows who it reaches right now (A2).
        DetailGridItem(
          label: 'Assigned to',
          span: 2,
          child: task.assignees.isEmpty
              // Not "—": a task with nobody on it is work nobody has picked up, which is a
              // thing to say rather than a blank.
              ? const ReadOnlyValue('Unassigned')
              : Wrap(
                  spacing: 12,
                  runSpacing: 4,
                  children: [for (final a in task.assignees) _assignee(context, a)],
                ),
        ),
        DetailGridItem(label: 'Notes', span: 2, child: ReadOnlyValue(notes)),
        DetailGridItem(label: 'Raised', child: ReadOnlyValue(formatDateTime(task.createdAt))),
        if (task.completedAt != null)
          DetailGridItem(
            label: 'Finished',
            child: ReadOnlyValue(formatDateTime(task.completedAt)),
          ),
      ]),
    );
  }

  /// The record the task hangs off, as a link where the server gave one.
  Widget _record(BuildContext context, Task task) {
    final label = _recordText(task);
    final link = task.entityLink;
    if (link == null) return ReadOnlyValue(label);
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: InkWell(
        onTap: () => goGuarded(context, link),
        child: Text(label,
            style: TextStyle(color: scheme.primary, fontWeight: FontWeight.w600),
            maxLines: 2,
            overflow: TextOverflow.ellipsis),
      ),
    );
  }

  /// One assignee: the seat or the person, and who it reaches now. A seat nobody holds says so,
  /// in the error colour — a task that looks assigned but reaches nobody is the thing worth
  /// noticing on this page.
  Widget _assignee(BuildContext context, Assignee a) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: TaskAssignees.describe(a),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(a.isUser ? Icons.person_outline : Icons.badge_outlined,
              size: 16, color: a.resolved ? scheme.onSurfaceVariant : scheme.error),
          const SizedBox(width: 4),
          Text(a.display, style: TextStyle(color: a.resolved ? null : scheme.error)),
        ],
      ),
    );
  }

  Future<void> _markDone(BuildContext context, WidgetRef ref, Task task) async {
    final messenger = ScaffoldMessenger.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    try {
      await setTaskStatus(ref.read(dioProvider), task, TaskStatus.DONE);
      invalidateTasks(container, id: task.id);
      messenger.showSnackBar(const SnackBar(content: Text('Task marked done')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, Task task) async {
    final messenger = ScaffoldMessenger.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Delete this task?'),
        content: const Text(
            'The task goes for good. What it said, who raised it and who took it away stay on '
            'the record\'s history.'),
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
    if (confirmed != true) return;
    try {
      await deleteTask(ref.read(dioProvider), task.id);
      invalidateTasks(container, id: task.id);
      messenger.showSnackBar(const SnackBar(content: Text('Task deleted')));
      // The page it was is gone; the list is where its reader belongs.
      if (context.mounted) context.go('/tasks');
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }
}
