import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import '../notifications/notifications_providers.dart';
import 'approval_models.dart';

// A change by id answers as of the date viewed, like any record by id (I3): the date reloads it,
// the regions do not.
final approvalDetailProvider =
    FutureProvider.autoDispose.family<PendingChangeDetail, int>((ref, id) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/approvals/$id');
  checkAsOf(res, asOf);
  return PendingChangeDetail.fromJson((res.data as Map).cast<String, dynamic>());
});

typedef PendingRecordKey = ({String entityType, int entityId});

/// The changes waiting on one record — outstanding on the date viewed, when a past one is (B3).
/// For a customer, the creates waiting for it.
final pendingForRecordProvider =
    FutureProvider.autoDispose.family<List<PendingChangeBrief>, PendingRecordKey>((ref, key) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/approvals/for-record', queryParameters: {
    'entityType': key.entityType,
    'entityId': key.entityId,
  });
  checkAsOf(res, asOf);
  return (res.data as List)
      .whereType<Map>()
      .map((m) => PendingChangeBrief.fromJson(m.cast<String, dynamic>()))
      .toList();
});

/// The Approvals badge: changes someone else asked for that the user can approve. Nobody without
/// CHANGE_APPROVE can have any, so for them it asks nothing at all — the shell builds this for
/// everyone who sees the entry (§7.3.4, G13).
final approvalsAwaitingMeCountProvider = StreamProvider.autoDispose<int>((ref) {
  final approves = ref.watch(currentUserProvider
      .select((u) => u != null && !u.isCustomer && u.has(Privileges.changeApprove)));
  if (!approves) return Stream.value(0);
  // For the regions and the date asked about, like the list it counts.
  ref.watch(viewScopeProvider);
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/approvals/awaiting-me/count');
});

/// After a change is requested, decided or withdrawn: everything that shows one.
void approvalsChanged(WidgetRef ref, {int? id}) {
  ref.invalidate(approvalsAwaitingMeCountProvider);
  ref.invalidate(pendingForRecordProvider);
  if (id != null) ref.invalidate(approvalDetailProvider(id));
  ref.invalidate(tablePageProvider);
  ref.invalidate(tableSummaryProvider);
}
