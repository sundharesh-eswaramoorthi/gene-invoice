import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import 'approval_providers.dart';

/// Where a branch's approval limit came from, which is a different question from what it is (B2).
///
/// Four states and not two, because "₹1,00,000 because somebody set it here" and "₹1,00,000
/// because the deployment defaults to it" are different facts an operator has to act on
/// differently, and so are the two ways of holding nothing: a branch where checking was
/// deliberately switched off, and a branch nobody has ever configured at all.
enum ApprovalLimitState {
  /// This branch has a row of its own and it is on: the number was chosen here.
  branchLimit,

  /// No row for this branch. The number is app.approvals.default-threshold, so NOBODY chose it
  /// here and it will move the day the deployment's own default moves (B2).
  deploymentDefault,

  /// This branch has a row and it is off. Nothing is held here whatever the amount, and somebody
  /// decided that.
  switchedOff,

  /// No row and no deployment default: maker-checker holds NOTHING in this branch and never has.
  /// A blank cell would read as zero, which is the exact opposite of the truth (B2).
  nothingAnywhere,
}

/// One branch's approval limit, field for field as ApprovalDtos.ThresholdDto puts it on the wire.
///
/// Hand-written with defensive casts like every other DTO in this client: a limit that arrives
/// with a null amount is a zero to read, never an exception on the branch list (B2).
@immutable
class ApprovalLimit {
  final int regionId;
  final String? regionName;

  /// Meaningless on its own: [enabled] false holds nothing whatever this says, and the server
  /// sends the scaled zero rather than null for a branch nobody has configured (B2).
  final double amount;
  final bool enabled;
  final int? updatedByUserId;
  final DateTime? updatedAt;

  /// True when this branch has NO row of its own and the answer came from the deployment default.
  /// It is the whole reason the server publishes the flag, and rendering a default as though
  /// somebody chose it is the one thing this screen must not do (B2).
  final bool fromDefault;

  const ApprovalLimit({
    required this.regionId,
    this.regionName,
    this.amount = 0,
    this.enabled = false,
    this.updatedByUserId,
    this.updatedAt,
    this.fromDefault = false,
  });

  factory ApprovalLimit.fromJson(Map<String, dynamic> json) => ApprovalLimit(
        regionId: (json['regionId'] as num).toInt(),
        regionName: json['regionName'] as String?,
        amount: (json['amount'] as num?)?.toDouble() ?? 0,
        enabled: json['enabled'] as bool? ?? false,
        updatedByUserId: (json['updatedByUserId'] as num?)?.toInt(),
        updatedAt:
            json['updatedAt'] == null ? null : DateTime.tryParse('${json['updatedAt']}'),
        fromDefault: json['fromDefault'] as bool? ?? false,
      );

  ApprovalLimitState get state => enabled
      ? (fromDefault ? ApprovalLimitState.deploymentDefault : ApprovalLimitState.branchLimit)
      : (fromDefault ? ApprovalLimitState.nothingAnywhere : ApprovalLimitState.switchedOff);

  /// Whether [amount] is a figure worth showing at all. A branch that holds nothing has a zero
  /// here that no operator typed, and prefilling an editor with it would invent a decision (B2).
  bool get hasAmount => state != ApprovalLimitState.nothingAnywhere;

  /// The cell. Words and never a blank for the two states that hold nothing, because an empty
  /// cell in a money column reads as zero — and zero would mean everything is held, which is the
  /// opposite of what is true (B2).
  String get label => switch (state) {
        ApprovalLimitState.branchLimit ||
        ApprovalLimitState.deploymentDefault =>
          formatMoney(amount),
        ApprovalLimitState.switchedOff ||
        ApprovalLimitState.nothingAnywhere =>
          'Nothing is held here',
      };

  /// The badge beside the figure: where it came from, or why there is none. Null for a branch's
  /// own live limit, which is the unremarkable case (B2).
  String? get provenance => switch (state) {
        ApprovalLimitState.branchLimit => null,
        ApprovalLimitState.deploymentDefault => 'Deployment default',
        ApprovalLimitState.switchedOff => 'Switched off',
        ApprovalLimitState.nothingAnywhere => 'No limit set',
      };

  String get _branch => regionName ?? 'this branch';

  /// The whole fact in one sentence, for the cell's tooltip and the top of the editor. It says
  /// what is held AND where the rule came from, because either half alone misleads (B2).
  String get sentence => switch (state) {
        ApprovalLimitState.branchLimit =>
          'A change worth more than ${formatMoney(amount)} in $_branch waits for a second pair '
              'of eyes. Exactly that much does not.',
        ApprovalLimitState.deploymentDefault =>
          '$_branch has no limit of its own. ${formatMoney(amount)} is this deployment\'s '
              'default, so nobody chose it here — and it moves if the deployment\'s does.',
        ApprovalLimitState.switchedOff =>
          'Approval checking is switched off in $_branch, so nothing there is ever held for '
              'approval, whatever it is worth.',
        ApprovalLimitState.nothingAnywhere =>
          '$_branch has no limit and this deployment has no default, so maker-checker holds '
              'nothing here: no amount is too large to go straight through.',
      };
}

/// What came of proposing a limit. Held is neither a success nor a failure: the server took the
/// proposal, the limit has NOT moved, and somebody else has to approve it (B2).
enum ApprovalLimitOutcome { cancelled, heldForApproval, applied }

/// Whether this person may propose a limit change SOMEWHERE — the "is there a control at all"
/// question. Whether they may in a PARTICULAR branch is `hasIn(approvalConfigure, regionId)`,
/// because the server narrows the write with regionAccess.require(MANAGE) and answers 403 for a
/// branch this caller cannot manage (B2, B1, D-46).
final canConfigureApprovalLimitsProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.approvalConfigure);
});

/// One branch's limit. Null for a caller without APPROVAL_VIEW, which is what GET
/// /api/approvals/thresholds/{regionId} is behind: a figure they may not read is an absence on
/// the branch list, never an error (B2, AUTH-08).
///
/// One read per branch, because the server publishes no bulk shape for these and the page is at
/// most 50 rows (TableQuery.ALLOWED_SIZES). Inventing a client-side join over a second endpoint
/// would put the screen and the gate at risk of disagreeing about what a branch's limit is, which
/// is the one thing ApprovalThresholds exists to prevent (B2).
final approvalLimitProvider =
    FutureProvider.autoDispose.family<ApprovalLimit?, int>((ref, regionId) async {
  if (!ref.watch(canSeeApprovalsProvider)) return null;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/approvals/thresholds/$regionId');
  return ApprovalLimit.fromJson((res.data as Map).cast<String, dynamic>());
});

/// Which branches have a limit change WAITING, by branch id.
///
/// One read for the whole page rather than one per row: the queue is an ordinary table endpoint,
/// and APPROVAL_THRESHOLD_SET parks its row with targetType REGION and targetId = the branch id,
/// so the pending marker for every branch on screen is one filtered page of it (B2).
final pendingApprovalLimitChangesProvider =
    FutureProvider.autoDispose<Map<int, PendingChange>>((ref) async {
  if (!ref.watch(canSeeApprovalsProvider)) return const {};
  final dio = ref.watch(dioProvider);
  final out = <int, PendingChange>{};
  // PAGED, and 50 because the server refuses any size outside [10, 20, 50] with a 400. A company
  // with more than fifty branches would otherwise silently lose the markers on the rest, which is
  // the same bug that broke the branch picker (B2, B1).
  const pageSize = 50;
  for (var page = 0;; page++) {
    final res = await dio.get('/api/approvals', queryParameters: {
      'page': page,
      'size': pageSize,
      'filter': [
        'targetType:eq:${PendingTargetType.REGION.name}',
        'status:eq:${PendingChangeStatus.PENDING.name}',
      ],
    });
    final body = (res.data as Map);
    final rows = (body['content'] as List? ?? const []).cast<Map<String, dynamic>>();
    for (final row in rows) {
      final change = PendingChange.fromJson(row);
      final id = change.targetId;
      if (id != null) out[id] = change;
    }
    final totalPages = (body['totalPages'] as num?)?.toInt() ?? 1;
    if (page + 1 >= totalPages || rows.isEmpty) break;
  }
  return out;
});

/// Proposes a branch's limit. It does not set it.
///
/// PendingAction.APPROVAL_THRESHOLD_SET.alwaysChecked() is true, so the server answers 202
/// PENDING_APPROVAL for every one of these however small the change, and api_client's response
/// interceptor turns that into a PendingApproval on a DioException. A caller that treats the
/// absence of a throw as "the limit is now this" is wrong about the only thing that matters (B2).
Future<void> proposeApprovalLimit(
  WidgetRef ref, {
  required int regionId,
  required double amount,
  required bool enabled,
}) async {
  await ref.read(dioProvider).put('/api/approvals/thresholds/$regionId',
      data: {'amount': amount, 'enabled': enabled});
}

/// Everything a limit proposal moves. The limit itself is in here deliberately: after a 202 it
/// reads back UNCHANGED, and showing that is the honest outcome rather than an oversight. The
/// branch list's own rows are not, because a limit change does not touch a branch (B2).
void invalidateApprovalLimits(WidgetRef ref) {
  ref.invalidate(approvalLimitProvider);
  ref.invalidate(pendingApprovalLimitChangesProvider);
  // Somebody now has a decision to make, and the sidebar's badge is how they find out (B2).
  ref.invalidate(approvalsAwaitingMeProvider);
}
