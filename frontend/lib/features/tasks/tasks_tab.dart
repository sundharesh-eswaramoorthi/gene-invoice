import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/assignee.dart';
import '../../shared/widgets/status_chip.dart';
import 'task_actions.dart';
import 'task_providers.dart';

/// One record's tasks, newest first. A record's tab is a short list rather than a table: the
/// count that matters is already on the tab's badge, and anyone who wants to sort or filter has
/// the Tasks list itself.
///
/// It lives here rather than in task_providers.dart because it belongs to this tab alone — the
/// list screen goes through the table providers, and a task's own page through
/// [taskDetailProvider].
final tasksForRecordProvider =
    FutureProvider.autoDispose.family<List<Task>, TaskRecordKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks', queryParameters: {
    'size': 50,
    'entityType': key.type.wire,
    'entityId': key.entityId,
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(Task.fromJson)
      .toList();
});

/// When a task is due, in red once it is late (T9). Overdue is not a status — it is the due date
/// read against today, and a finished task is never late — so the server works it out and this
/// only shows it.
class TaskDueDate extends StatelessWidget {
  final Task task;
  const TaskDueDate({super.key, required this.task});

  @override
  Widget build(BuildContext context) {
    if (task.dueDate == null) return const Text('—');
    final scheme = Theme.of(context).colorScheme;
    final text = Text(
      formatDate(task.dueDate),
      style: task.overdue ? TextStyle(color: scheme.error, fontWeight: FontWeight.w600) : null,
    );
    return task.overdue ? Tooltip(message: 'Overdue', child: text) : text;
  }
}

/// Who the work is on, in as little width as a table row can spare: the first few by name or
/// seat, then "+2" for the rest, with the whole list in the tooltip (A2).
///
/// A seat nobody holds is shown in the error colour rather than dropped: "Collection POC" with
/// nobody in it is exactly the thing somebody needs to see and fix, and silently leaving it out
/// would read as a task assigned to fewer people than it is.
class TaskAssignees extends StatelessWidget {
  final List<Assignee> assignees;

  /// How many are named before the rest become "+N".
  final int max;

  /// Caps each name, for a table cell that must not stretch the column (D-20).
  final double? pillWidth;

  const TaskAssignees({super.key, required this.assignees, this.max = 2, this.pillWidth});

  /// One assignee in a sentence, for the tooltip: the seat or person, who it reaches now, and —
  /// where the chip had to shorten that list — each of them with their address.
  static String describe(Assignee a) {
    final reaches = a.reaches;
    return reaches == null ? a.display : '${a.display}\n$reaches';
  }

  @override
  Widget build(BuildContext context) {
    if (assignees.isEmpty) {
      return Text('Unassigned', style: TextStyle(color: Theme.of(context).hintColor));
    }
    final shown = assignees.take(max).toList();
    final rest = assignees.length - shown.length;
    return Tooltip(
      message: assignees.map(describe).join('\n'),
      child: Wrap(
        spacing: 8,
        runSpacing: 2,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          for (final a in shown) _pill(context, a),
          if (rest > 0)
            Text('+$rest', style: Theme.of(context).textTheme.labelMedium),
        ],
      ),
    );
  }

  Widget _pill(BuildContext context, Assignee a) {
    final scheme = Theme.of(context).colorScheme;
    // A person is answerable until somebody changes the row; a role is answerable for as long as
    // they hold it, and the two icons say which this is (A2).
    final color = a.resolved ? scheme.onSurfaceVariant : scheme.error;
    final row = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(a.isUser ? Icons.person_outline : Icons.badge_outlined, size: 14, color: color),
        const SizedBox(width: 3),
        Flexible(
          child: Text(a.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: color)),
        ),
      ],
    );
    return pillWidth == null
        ? row
        : ConstrainedBox(constraints: BoxConstraints(maxWidth: pillWidth!), child: row);
  }
}

/// The Tasks tab on a record's page: the work raised against this customer, invoice or payment,
/// and — with TASK_MANAGE — the button that raises more (T1).
class TasksTab extends ConsumerWidget {
  final TaskEntityType type;
  final int entityId;

  /// What the record is called, for the form's "About:" line. The server stamps its own label on
  /// the task, so this is only what the form shows while it is being written.
  final String? entityLabel;

  const TasksTab({
    super.key,
    required this.type,
    required this.entityId,
    this.entityLabel,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canManage = ref.watch(canManageTasksProvider);
    final key = (type: type, entityId: entityId);
    final async = ref.watch(tasksForRecordProvider(key));

    return Column(
      children: [
        if (canManage)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: Align(
              alignment: Alignment.centerLeft,
              child: FilledButton.icon(
                icon: const Icon(Icons.add),
                label: const Text('New task'),
                onPressed: () => showTaskDialog(
                  context: context,
                  type: type,
                  entityId: entityId,
                  entityLabel: entityLabel,
                ),
              ),
            ),
          ),
        Expanded(
          child: async.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => _TabError(
              message: apiErrorMessage(e),
              onRetry: () => ref.invalidate(tasksForRecordProvider(key)),
            ),
            data: (tasks) {
              if (tasks.isEmpty) {
                return const Center(child: Text('No tasks on this record'));
              }
              return ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: tasks.length,
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (context, i) => TaskCard(task: tasks[i], canManage: canManage),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// One task in a record's tab: what it is, when it is due, who is on it, and the two things
/// anybody does to it from here — tick it off, or open the form.
class TaskCard extends ConsumerStatefulWidget {
  final Task task;
  final bool canManage;

  const TaskCard({super.key, required this.task, required this.canManage});

  @override
  ConsumerState<TaskCard> createState() => _TaskCardState();
}

class _TaskCardState extends ConsumerState<TaskCard> {
  /// A request of this card's own is under way; its buttons wait for it.
  bool _busy = false;

  Future<void> _markDone() async {
    final messenger = ScaffoldMessenger.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    setState(() => _busy = true);
    try {
      await setTaskStatus(ref.read(dioProvider), widget.task, TaskStatus.DONE);
      invalidateTasks(container, id: widget.task.id);
      messenger.showSnackBar(const SnackBar(content: Text('Task marked done')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final task = widget.task;
    final notes = task.notes ?? '';
    return Card(
      margin: EdgeInsets.zero,
      child: InkWell(
        onTap: () => goGuarded(context, '/tasks/${task.id}'),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(task.title,
                        style: const TextStyle(fontWeight: FontWeight.w600),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis),
                  ),
                  const SizedBox(width: 8),
                  TaskStatusChip(status: task.status, label: task.statusLabel),
                ],
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 16,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.event_outlined, size: 14),
                      const SizedBox(width: 4),
                      TaskDueDate(task: task),
                    ],
                  ),
                  TaskAssignees(assignees: task.assignees, max: 3),
                ],
              ),
              if (notes.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(notes, maxLines: 2, overflow: TextOverflow.ellipsis),
              ],
              if (widget.canManage)
                Align(
                  alignment: Alignment.centerRight,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (!task.isTerminal)
                        TextButton.icon(
                          icon: const Icon(Icons.check_circle_outline, size: 18),
                          label: const Text('Mark done'),
                          onPressed: _busy ? null : _markDone,
                        ),
                      TextButton.icon(
                        icon: const Icon(Icons.edit_outlined, size: 18),
                        label: const Text('Edit'),
                        onPressed: _busy
                            ? null
                            : () => showTaskDialog(context: context, existing: task),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A failed tab, with the server's own sentence and a way to ask again.
class _TabError extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _TabError({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
              const SizedBox(height: 8),
              Text(message, textAlign: TextAlign.center),
              TextButton(onPressed: onRetry, child: const Text('Retry')),
            ],
          ),
        ),
      );
}
