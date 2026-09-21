import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/task.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'task_providers.dart';
import 'tasks_tab.dart';

export '../../shared/models/task.dart' show Task, TaskEntityType, TaskStatus;
export 'task_form_dialog.dart' show showTaskDialog;

// The tasks feature as the rest of the app sees it. Detail screens import only this file, so the
// tab, the form and their providers can change without touching every record page.

/// True when the signed-in user holds TASK_VIEW / TASK_MANAGE.
final canViewTasksProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.taskView) ?? false);
final canManageTasksProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.taskManage) ?? false);

/// The Tasks tab for DetailScaffold.tabs (slug 'tasks', label 'Tasks', Icons.task_alt_outlined),
/// badged with how much work is still open on the record (T5). Null when the user lacks
/// TASK_VIEW, so callers write `if (tab != null) tab`.
DetailTab? tasksDetailTab(WidgetRef ref,
    {required TaskEntityType type, required int entityId, String? entityLabel}) {
  if (!ref.watch(canViewTasksProvider)) return null;
  // Asked for with the page rather than with the tab, so the badge is there before anyone opens
  // it — the same bargain the Documents tab makes. A count that has not arrived, or could not be
  // had, simply shows no badge.
  final open = ref.watch(taskOpenCountProvider((type: type, entityId: entityId))).valueOrNull;
  return DetailTab(
    slug: 'tasks',
    label: 'Tasks',
    icon: Icons.task_alt_outlined,
    badgeCount: open,
    builder: (context) => TasksTab(type: type, entityId: entityId, entityLabel: entityLabel),
  );
}

// ---- writing -------------------------------------------------------------------------

/// A due date as the API spells it: the calendar day, with no time of day and no zone. The field
/// is a `LocalDate` on the server, so anything else would arrive as a different day in half the
/// world.
String? taskDueDateWire(DateTime? day) =>
    day == null ? null : DateFormat('yyyy-MM-dd').format(day);

/// Moves one task's status without opening the form — "Mark done" from a row or the task's page.
///
/// The whole of [task] goes with it, not just the status. A PATCH body cannot tell "absent" from
/// "null", so the server reads a missing `dueDate` or `notes` as *cleared* (see
/// `TaskDtos.UpdateTaskRequest`); ticking a task off would otherwise quietly take its due date and
/// its notes with it. `assignees` is deliberately left out, which is the one field a null leaves
/// alone: who the work is on is not this action's business.
Future<void> setTaskStatus(Dio dio, Task task, TaskStatus status) => dio.patch(
      '/api/tasks/${task.id}',
      data: {
        'title': task.title,
        'dueDate': taskDueDateWire(task.dueDate),
        'status': status.name,
        'notes': task.notes,
      },
    );

/// Removes a task raised in error. The row goes for good; what it said stays in the record's
/// history, with who took it away.
Future<void> deleteTask(Dio dio, int id) => dio.delete('/api/tasks/$id');

/// After a task is raised, edited, moved or deleted: every list that may be showing it, the tab
/// badges, the task's own page and the record's History all ask again.
///
/// Takes the container rather than a ref, so a form already closed by the time its request
/// answers still refreshes what is on screen.
void invalidateTasks(ProviderContainer container, {int? id}) {
  container.invalidate(tasksForRecordProvider);
  // The Tasks list page, which is a table like any other.
  container.invalidate(tablePageProvider);
  container.invalidate(taskOpenCountProvider);
  if (id != null) container.invalidate(taskDetailProvider(id));
  container.invalidate(auditHistoryProvider);
}
