import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import '../notifications/notifications_providers.dart';
import 'task_models.dart';

export 'task_models.dart' show Task, TaskAssignee, TaskEntityType, TaskStatus, TaskSummary;

/// Whether this person may read tasks at all. TASK_VIEW is what all four GETs are behind, so
/// without it every screen here has nothing to show and asking would only earn a 403 (A6).
final canViewTasksProvider =
    Provider<bool>((ref) => ref.watch(currentUserProvider)?.has(Privileges.taskView) ?? false);

/// "May I create, edit or finish a task on a record in THIS branch."
///
/// TASK_MANAGE is a MANAGE-level privilege in the region partition — R10 already put it in
/// auth_models' `_regionRightNeeded` map, so nothing is re-declared here — and holding it in one
/// branch says nothing about a task in another. hasIn, not has: the global answer is "somewhere",
/// which is the nav gate and not the button gate. A null regionId is a task that does not say
/// which branch it is in, and falls back to the global answer (A6, B1).
final canManageTasksInProvider = Provider.family<bool, int?>((ref, regionId) =>
    ref.watch(currentUserProvider)?.hasIn(Privileges.taskManage, regionId) ?? false);

/// The same question with no record in hand: may this person do it anywhere (A6, B1).
final canManageTasksProvider = Provider<bool>((ref) => ref.watch(canManageTasksInProvider(null)));

final canExportTasksProvider =
    Provider<bool>((ref) => ref.watch(currentUserProvider)?.has(Privileges.exportData) ?? false);

/// One task, by its own id — the /tasks/:id page and every refresh after a decision on it.
final taskDetailProvider = FutureProvider.autoDispose.family<Task, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks/$id');
  return Task.fromJson((res.data as Map).cast<String, dynamic>());
});

/// How much work is waiting for THIS person — the number on the sidebar's badge.
///
/// `pollUnreadCount` verbatim, the poller the notification bell and the inbox already use, against
/// the endpoint that answers `{"count": N}` in exactly the shape it reads. It is the server's own
/// count and not one worked out here: "mine" means the seats this caller holds, which only the
/// server can resolve (A6).
///
/// Nobody without TASK_VIEW is ever shown the entry, and the stream answers 0 for them rather than
/// polling an endpoint that would 403 every thirty seconds (A6, UI-10).
final myOpenTaskCountProvider = StreamProvider.autoDispose<int>((ref) {
  if (!ref.watch(canViewTasksProvider)) return Stream.value(0);
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/tasks/count?mine=true');
});

/// MY WORK IS NOT A SECOND SCREEN AND NOT A SECOND ENDPOINT.
///
/// It is the tasks list with three parameters in the URL, which URL-is-the-state makes free: the
/// filter bar shows them as ordinary chips, the back button works, and the link can be shared. A
/// bespoke "my tasks" screen would have needed its own endpoint, its own scope and its own bugs
/// (A6, AC-D4).
String myWorkLocation(CurrentUser? user) {
  if (user == null) return '/tasks';
  return RouteQuery.location('/tasks', {
    'f': ['assigneeUserId:eq:${user.id}', 'status:in:OPEN,IN_PROGRESS'],
    'sort': 'dueDate,asc',
  });
}

/// Finishing a task. One POST and then everything it moves, explicitly — the house rule that a
/// mutation names what it invalidates rather than hoping a parent rebuilds (A6).
///
/// There is no held-for-approval path here and there must not be one: nothing about a task is
/// threshold-eligible, PendingTargetType has no TASK constant, and /api/tasks/{id}/complete cannot
/// answer 202 (A6, B2).
Future<void> completeTask(WidgetRef ref, int id) async {
  await ref.read(dioProvider).post('/api/tasks/$id/complete');
  invalidateTasks(ref, id);
}

/// Cancelling a task is a PATCH to CANCELLED, not an endpoint of its own — and the body has to
/// carry the task's notes and due date back UNCHANGED alongside the status.
///
/// UpdateTaskRequest does not treat its five components alike: `title`, `status` and
/// `assigneeUserIds` are left alone when null, but `notes` and `dueDate` are REPLACED by whatever
/// arrives, null included, so that "this no longer has a due date" stays expressible over a wire
/// on which Jackson cannot tell an absent field from a null one. A body naming only the status
/// therefore erases both — silently, on a button whose confirm dialog promises the opposite. The
/// form dialog sends both for the same reason and says so; this is the same rule read the same
/// way, from a screen that shows neither field (A6).
///
/// The whole [task] is taken rather than its id because what is being re-sent is what the reader
/// is looking at: re-sending the row on screen is the only honest answer a client that shows no
/// notes field can give.
Future<void> cancelTask(WidgetRef ref, Task task) async {
  await ref.read(dioProvider).patch('/api/tasks/${task.id}', data: {
    'status': TaskStatus.CANCELLED.name,
    'notes': task.notes,
    'dueDate': task.dueDate == null ? null : DateFormat('yyyy-MM-dd').format(task.dueDate!),
  });
  invalidateTasks(ref, task.id);
}

/// Everything a change to a task moves: the row it is in, the tiles counted over the same filters,
/// the task's own page and the sidebar badge. The generic table providers are family-keyed on the
/// whole request, so they are invalidated wholesale — the same thing invalidateApprovals does (A6).
void invalidateTasks(WidgetRef ref, [int? id]) {
  if (id != null) ref.invalidate(taskDetailProvider(id));
  ref.invalidate(tablePageProvider);
  ref.invalidate(tableSummaryProvider);
  ref.invalidate(myOpenTaskCountProvider);
}
