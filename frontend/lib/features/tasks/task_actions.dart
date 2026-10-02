import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/view_scope.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import 'task_models.dart';
import 'task_providers.dart';
import 'tasks_tab.dart';

/// Staff who may see tasks. A past date still shows them, as they stood then (B3): only the
/// changes are closed then.
final canViewTasksProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  return user != null && !user.isCustomer && user.has(Privileges.taskView);
});

/// Creating and managing tasks is a change, and a past date is read only (D2).
final canManageTasksProvider = Provider<bool>((ref) {
  if (ref.watch(isHistoricalProvider)) return false;
  final user = ref.watch(currentUserProvider);
  return user != null && !user.isCustomer && user.has(Privileges.taskManage);
});

/// Whether a new task may go on a record in [regionId] (§7.5); the server checks it again.
final canCreateTaskInProvider = Provider.family<bool, int?>((ref, regionId) {
  if (!ref.watch(canManageTasksProvider)) return false;
  return ref.watch(currentUserProvider)?.hasIn(Privileges.taskManage, regionId) ?? false;
});

/// What [user] may do to [t] today: the server's flags first (D14), else the privileges. Nothing
/// on a past date.
({bool canEdit, bool canChangeStatus}) taskRights(TaskItem t,
    {required CurrentUser? user, required bool historical}) {
  final manages = user?.hasIn(Privileges.taskManage, t.regionId) ?? false;
  final canEdit = !historical && (t.canEdit ?? manages);
  return (
    canEdit: canEdit,
    canChangeStatus: !historical && (t.canChangeStatus ?? (manages || t.assignedToMe)),
  );
}

/// A record's Tasks tab. Whether it is there depends only on the privilege, never on the count
/// that is still loading, so the tabs never jump back to the first (G5); the count is the badge.
/// The customer's tab ([customerScope]) also lists the tasks on its invoices and payments, and its
/// badge counts them too.
DetailTab? tasksDetailTab(WidgetRef ref,
    {required TaskEntityType entityType,
    required int entityId,
    bool customerScope = false,
    String? entityLabel,
    int? regionId}) {
  if (!ref.watch(canViewTasksProvider)) return null;
  final count = (customerScope
          ? ref.watch(customerTaskCountProvider(entityId))
          : ref.watch(taskCountProvider((entityType: entityType.wire, entityId: entityId))))
      .valueOrNull;
  return DetailTab(
    slug: 'tasks',
    label: 'Tasks',
    icon: Icons.checklist,
    badgeCount: count,
    builder: (context) => TasksTab(
      entityType: entityType,
      entityId: entityId,
      customerScope: customerScope,
      entityLabel: entityLabel,
      regionId: regionId,
    ),
  );
}

/// The moves [user] may make on [t] from where it stands.
List<TaskMove> taskMoves(TaskItem t, {required CurrentUser? user, required bool historical}) {
  final rights = taskRights(t, user: user, historical: historical);
  return taskMovesFor(t, canEdit: rights.canEdit, canChangeStatus: rights.canChangeStatus);
}

/// Moves [task] as [move] says, asking first before cancelling it. Resolves with the task as the
/// server now has it, or null when nothing changed.
Future<TaskItem?> moveTask(BuildContext context, TaskItem task, TaskMove move) async {
  final messenger = ScaffoldMessenger.of(context);
  final container = ProviderScope.containerOf(context, listen: false);
  if (move == TaskMove.cancel) {
    final sure = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cancel this task?'),
        content: Text('"${task.title}" will no longer need doing. It can be reopened later.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Cancel task'),
          ),
        ],
      ),
    );
    if (sure != true) return null;
  }
  try {
    final saved = await changeTaskStatus(container.read(dioProvider), task, move.to);
    tasksChanged(container, id: task.id);
    messenger.showSnackBar(SnackBar(content: Text(switch (move) {
      TaskMove.start => 'Task started',
      TaskMove.done => 'Task done',
      TaskMove.reopen => 'Task reopened',
      TaskMove.cancel => 'Task cancelled',
    })));
    return saved;
  } catch (e) {
    final stale = e is DioException && e.response?.statusCode == 409;
    if (stale) tasksChanged(container, id: task.id);
    messenger.showSnackBar(SnackBar(
        content: Text(stale
            ? 'Someone else changed this task first. It has been reloaded; try again.'
            : apiErrorMessage(e))));
    return null;
  }
}
