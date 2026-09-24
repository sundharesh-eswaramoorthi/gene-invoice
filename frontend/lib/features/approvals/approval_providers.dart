import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';

/// One record, as the approvals queue names it: the pair a change points at (B2).
@immutable
class PendingTarget {
  final PendingTargetType type;
  final int id;
  const PendingTarget(this.type, this.id);

  @override
  bool operator ==(Object other) =>
      other is PendingTarget && other.type == type && other.id == id;

  @override
  int get hashCode => Object.hash(type, id);
}

/// Whether this person may read the queue at all. APPROVAL_VIEW is what /api/approvals is behind,
/// so without it the panel has nothing to show and asking would only earn a 403 per detail page
/// (B2, AUTH-08).
final canSeeApprovalsProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.approvalView) ?? false);

/// One change, by its own id — the /approvals/:id page and the panel's refresh after a decision.
final approvalDetailProvider =
    FutureProvider.autoDispose.family<PendingChange, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/approvals/$id');
  return PendingChange.fromJson(res.data as Map<String, dynamic>);
});

/// What is waiting ON a record, for the amber panel on its detail page.
///
/// An ordinary queue read with three filters, not an endpoint of its own: the region predicate,
/// the book and AUTH-08 all come from the same funnel as every other list, so a change in a
/// branch this caller holds nothing in simply is not in the answer (B2, B1).
///
/// Answers null rather than throwing for a caller with no APPROVAL_VIEW: the record is still
/// perfectly readable to them, and a panel they are not entitled to is an absence, not an error.
final pendingChangeForProvider =
    FutureProvider.autoDispose.family<PendingChange?, PendingTarget>((ref, target) async {
  if (!ref.watch(canSeeApprovalsProvider)) return null;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/approvals', queryParameters: {
    // 10 and not 1: TableQuery.parse refuses any size outside [10, 20, 50] with a 400, and one
    // row is all there can be anyway — pending_key allows exactly one waiting change per record
    // (B2).
    'size': 10,
    'filter': [
      'targetType:eq:${target.type.name}',
      'targetId:eq:${target.id}',
      'status:eq:${PendingChangeStatus.PENDING.name}',
    ],
  });
  final rows = ((res.data as Map)['content'] as List?) ?? const [];
  if (rows.isEmpty) return null;
  return PendingChange.fromJson((rows.first as Map).cast<String, dynamic>());
});

/// How many changes are waiting for THIS person to decide — the number on the sidebar's badge.
///
/// It is the queue's own awaitingMyDecisionCount tile and not a count worked out here: "waiting"
/// and "waiting for me" are two different questions and only the server can answer the second,
/// because it knows which branches this caller may approve in (B2).
final approvalsAwaitingMeProvider = FutureProvider.autoDispose<int>((ref) async {
  if (!ref.watch(canSeeApprovalsProvider)) return 0;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/approvals/summary');
  return ((res.data as Map)['awaitingMyDecisionCount'] as num? ?? 0).toInt();
});

/// The three decisions, over one wire shape. `decision` is the path segment the controller
/// declares — approve, reject or withdraw — and the server decides whether this caller may;
/// nothing here re-implements the rule (B2).
Future<void> decideApproval(
  WidgetRef ref, {
  required int id,
  required String decision,
  String? notes,
}) async {
  final trimmed = notes?.trim();
  await ref.read(dioProvider).post('/api/approvals/$id/$decision',
      data: {if (trimmed != null && trimmed.isNotEmpty) 'decisionNotes': trimmed});
  invalidateApprovals(ref, id);
}

/// Everything a decision moves. The record's own detail provider is NOT in here — it lives in the
/// feature that owns it — so each screen passes its own refresh in beside this one (B2).
void invalidateApprovals(WidgetRef ref, [int? id]) {
  if (id != null) ref.invalidate(approvalDetailProvider(id));
  ref.invalidate(pendingChangeForProvider);
  ref.invalidate(approvalsAwaitingMeProvider);
  ref.invalidate(tablePageProvider);
  ref.invalidate(tableSummaryProvider);
  ref.invalidate(auditHistoryProvider);
}
