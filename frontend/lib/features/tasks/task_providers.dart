import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../notifications/notifications_providers.dart' show pollUnreadCount;
import 'task_models.dart';

// A task by id answers as of the date viewed, like any record by id (I3): the date reloads it,
// the regions do not.
final taskDetailProvider = FutureProvider.autoDispose.family<TaskItem, int>((ref, id) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks/$id');
  checkAsOf(res, asOf);
  return TaskItem.fromJson((res.data as Map).cast<String, dynamic>());
});

typedef TaskRecordKey = ({String entityType, int entityId});

/// How many of a record's tasks are still to do — the badge on its Tasks tab. Only the badge:
/// whether the tab is there never waits on it (G5).
final taskCountProvider = FutureProvider.autoDispose.family<int, TaskRecordKey>((ref, key) async {
  final view = ref.watch(viewScopeProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks/count', queryParameters: {
    'entityType': key.entityType,
    'entityId': key.entityId,
  });
  checkAsOf(res, view.asOf);
  final data = res.data as Map;
  return ((data['open'] ?? data['count']) as num?)?.toInt() ?? 0;
});

/// The badge on a customer's Tasks tab. That tab lists by customer, so its invoices' and payments'
/// tasks too (§5.9.5), and the badge counts the same tasks: the open and in-progress ones in
/// `/api/tasks/summary?customerId=` rather than `/count`, which counts only the customer's own.
final customerTaskCountProvider = FutureProvider.autoDispose.family<int, int>((ref, customerId) async {
  final view = ref.watch(viewScopeProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks/summary', queryParameters: {'customerId': customerId});
  checkAsOf(res, view.asOf);
  final data = res.data as Map;
  return ((data['open'] as num?)?.toInt() ?? 0) + ((data['inProgress'] as num?)?.toInt() ?? 0);
});

/// The "My tasks" badge: my open and in-progress tasks. Nobody without TASK_VIEW can have any, so
/// for them it asks nothing at all — the shell builds this for everyone who sees the entry
/// (§7.3.4, G13).
final myTasksCountProvider = StreamProvider.autoDispose<int>((ref) {
  final sees = ref.watch(currentUserProvider
      .select((u) => u != null && !u.isCustomer && u.has(Privileges.taskView)));
  if (!sees) return const Stream.empty();
  // For the regions and the date asked about, like the list it counts.
  ref.watch(viewScopeProvider);
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/tasks/mine/count');
});

/// After a task is created, edited or moved: everything that shows one. It takes the container,
/// not a widget's ref, as it runs once a request has come back — the row or dialog that asked may
/// have gone by then.
void tasksChanged(ProviderContainer container, {int? id}) {
  if (id != null) container.invalidate(taskDetailProvider(id));
  container.invalidate(taskCountProvider);
  container.invalidate(customerTaskCountProvider);
  container.invalidate(myTasksCountProvider);
  container.invalidate(tablePageProvider);
  container.invalidate(tableSummaryProvider);
  container.invalidate(auditHistoryProvider);
}

/// Moves [task] to [to]. The server refuses a stale version (409) and a move the user may not
/// make (403).
Future<TaskItem> changeTaskStatus(Dio dio, TaskItem task, TaskStatus to) async {
  final res = await dio.post('/api/tasks/${task.id}/status', data: {
    'status': to.name,
    'version': task.version,
  });
  return TaskItem.fromJson((res.data as Map).cast<String, dynamic>());
}
