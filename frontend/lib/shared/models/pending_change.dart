/// Where a held change stands. UNKNOWN is not a server value: it is what a status this build has
/// never heard of becomes, so a constant added to the backend in 2027 reads as an unfamiliar
/// label instead of crashing the queue (B2).
enum PendingChangeStatus { PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, UNKNOWN }

PendingChangeStatus parsePendingChangeStatus(String? s) => PendingChangeStatus.values
    .firstWhere((e) => e.name == s, orElse: () => PendingChangeStatus.UNKNOWN);

String pendingChangeStatusLabel(PendingChangeStatus s) => switch (s) {
      PendingChangeStatus.PENDING => 'Waiting',
      PendingChangeStatus.APPROVED => 'Approved',
      PendingChangeStatus.REJECTED => 'Rejected',
      PendingChangeStatus.WITHDRAWN => 'Withdrawn',
      PendingChangeStatus.SUPERSEDED => 'Superseded',
      PendingChangeStatus.UNKNOWN => 'Unknown',
    };

/// The kind of record a change is about. REGION is a change to a branch's own approval limit and
/// is not a business record, so it has no page of its own to open (B2).
enum PendingTargetType { CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE, REGION, UNKNOWN }

PendingTargetType parsePendingTargetType(String? s) => PendingTargetType.values
    .firstWhere((e) => e.name == s, orElse: () => PendingTargetType.UNKNOWN);

String pendingTargetLabel(PendingTargetType t) => switch (t) {
      PendingTargetType.CUSTOMER => 'Customer',
      PendingTargetType.INVOICE => 'Invoice',
      PendingTargetType.PAYMENT => 'Payment',
      PendingTargetType.PROMISE => 'Promise',
      PendingTargetType.DISPUTE => 'Dispute',
      PendingTargetType.REGION => 'Branch limit',
      PendingTargetType.UNKNOWN => 'Record',
    };

/// The fourteen interceptable actions, plus the same UNKNOWN fallback. The list is the server's
/// PendingAction and it is only ever read here — nothing in this client decides what is gated.
enum PendingAction {
  PAYMENT_RECORD,
  PAYMENT_UPDATE_AMOUNT,
  PAYMENT_VOID,
  INVOICE_CREATE,
  INVOICE_CANCEL,
  INVOICE_CANCEL_WITH_REFUND,
  INVOICE_REPLACE_ITEMS,
  PROMISE_CREATE,
  PROMISE_UPDATE,
  PROMISE_CANCEL,
  PROMISE_OVERRIDE,
  CUSTOMER_DELETE,
  DISPUTE_APPROVE,
  APPROVAL_THRESHOLD_SET,
  UNKNOWN,
}

PendingAction parsePendingAction(String? s) =>
    PendingAction.values.firstWhere((e) => e.name == s, orElse: () => PendingAction.UNKNOWN);

String pendingActionLabel(PendingAction a) => switch (a) {
      PendingAction.PAYMENT_RECORD => 'Record payment',
      PendingAction.PAYMENT_UPDATE_AMOUNT => 'Change payment amount',
      PendingAction.PAYMENT_VOID => 'Void payment',
      PendingAction.INVOICE_CREATE => 'Raise invoice',
      PendingAction.INVOICE_CANCEL => 'Cancel invoice',
      PendingAction.INVOICE_CANCEL_WITH_REFUND => 'Cancel invoice with refund',
      PendingAction.INVOICE_REPLACE_ITEMS => 'Replace invoice lines',
      PendingAction.PROMISE_CREATE => 'Raise promise',
      PendingAction.PROMISE_UPDATE => 'Change promise',
      PendingAction.PROMISE_CANCEL => 'Cancel promise',
      PendingAction.PROMISE_OVERRIDE => 'Override promise status',
      PendingAction.CUSTOMER_DELETE => 'Delete customer',
      PendingAction.DISPUTE_APPROVE => 'Approve dispute',
      PendingAction.APPROVAL_THRESHOLD_SET => 'Change branch approval limit',
      PendingAction.UNKNOWN => 'Change',
    };

/// The queue's own record: a change somebody asked for that has not happened yet (B2).
///
/// Mirrors ApprovalDtos.PendingChangeDto field for field, including the three the server works
/// out per reader — [mine], [canDecide] and [cannotDecideReason] — so this client never
/// re-implements the maker-is-not-the-checker rule. Null in any of the three means "the server
/// was not asked", the pocMissing convention, and a screen must then offer no decision at all.
class PendingChange {
  final int id;
  final PendingAction action;
  final PendingTargetType targetType;

  /// Null for a change that would CREATE the record: there is nothing to point at yet, and
  /// [customerId] is the anchor instead (B2).
  final int? targetId;
  final int? customerId;
  final String? customerName;
  final int? regionId;
  final String? regionName;
  final String? summary;

  /// The money this change moves, and the branch limit it went over. Both are null on an
  /// always-checked action, which is held whatever the amount.
  final double? exposure;
  final double? thresholdApplied;
  final bool alwaysChecked;

  final PendingChangeStatus status;
  final int? requestedByUserId;
  final String? requestedByName;
  final DateTime? requestedAt;
  final int? decidedByUserId;
  final String? decidedByName;
  final DateTime? decidedAt;
  final String? decisionNotes;

  /// What would be done, and the record as it stood when it was asked for. Both are raw JSON
  /// strings on the wire, shown side by side so a checker can see what actually changes.
  final String? payloadJson;
  final String? beforeJson;

  final int? targetVersion;

  /// One bulk run stamps one id on every change it raised, so the whole run is decidable at once.
  final String? batchId;

  /// Answered FOR the caller by the server, never worked out here. Null means the server was not
  /// asked — an audit-shaped read — and nothing may be offered on the strength of it (B2).
  final bool? mine;
  final bool? canDecide;
  final String? cannotDecideReason;

  const PendingChange({
    required this.id,
    required this.action,
    required this.targetType,
    this.targetId,
    this.customerId,
    this.customerName,
    this.regionId,
    this.regionName,
    this.summary,
    this.exposure,
    this.thresholdApplied,
    this.alwaysChecked = false,
    required this.status,
    this.requestedByUserId,
    this.requestedByName,
    this.requestedAt,
    this.decidedByUserId,
    this.decidedByName,
    this.decidedAt,
    this.decisionNotes,
    this.payloadJson,
    this.beforeJson,
    this.targetVersion,
    this.batchId,
    this.mine,
    this.canDecide,
    this.cannotDecideReason,
  });

  bool get isWaiting => status == PendingChangeStatus.PENDING;

  /// Approve and Reject are offered only on the server's own say-so. A missing answer is a no.
  bool get decidable => isWaiting && canDecide == true;

  /// Withdrawing is the maker's own right and is not an approval, so it is offered on [mine]
  /// rather than on [canDecide] — which is false for a maker by design (B2).
  bool get withdrawable => isWaiting && mine == true;

  /// The record this change is about, in Flutter's route space. Null for a create (there is no
  /// record yet) and for a branch limit (a branch has no page).
  String? get targetRoute {
    if (targetId == null) return null;
    return switch (targetType) {
      PendingTargetType.CUSTOMER => '/customers/$targetId',
      PendingTargetType.INVOICE => '/invoices/$targetId',
      PendingTargetType.PAYMENT => '/payments/$targetId',
      PendingTargetType.PROMISE => '/promises/$targetId',
      PendingTargetType.DISPUTE => '/disputes/$targetId',
      PendingTargetType.REGION || PendingTargetType.UNKNOWN => null,
    };
  }

  /// The entityType GET /api/audit will answer for, or null when it will not. AuditController's
  /// SUPPORTED set is {INVOICE, PAYMENT, CUSTOMER, PROMISE, USER, PRODUCT}, so a DISPUTE change
  /// and a branch-limit change have no timeline of their own and asking for one is a 400 rather
  /// than an empty list (B2).
  String? get auditEntityType => switch (targetType) {
        PendingTargetType.CUSTOMER ||
        PendingTargetType.INVOICE ||
        PendingTargetType.PAYMENT ||
        PendingTargetType.PROMISE =>
          targetId == null ? null : targetType.name,
        PendingTargetType.DISPUTE ||
        PendingTargetType.REGION ||
        PendingTargetType.UNKNOWN =>
          null,
      };

  String get title => summary?.isNotEmpty == true ? summary! : pendingActionLabel(action);

  factory PendingChange.fromJson(Map<String, dynamic> json) => PendingChange(
        id: (json['id'] as num).toInt(),
        action: parsePendingAction(json['action'] as String?),
        targetType: parsePendingTargetType(json['targetType'] as String?),
        targetId: (json['targetId'] as num?)?.toInt(),
        customerId: (json['customerId'] as num?)?.toInt(),
        customerName: json['customerName'] as String?,
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        summary: json['summary'] as String?,
        exposure: (json['exposure'] as num?)?.toDouble(),
        thresholdApplied: (json['thresholdApplied'] as num?)?.toDouble(),
        alwaysChecked: json['alwaysChecked'] as bool? ?? false,
        status: parsePendingChangeStatus(json['status'] as String?),
        requestedByUserId: (json['requestedByUserId'] as num?)?.toInt(),
        requestedByName: json['requestedByName'] as String?,
        requestedAt: json['requestedAt'] == null
            ? null
            : DateTime.tryParse(json['requestedAt'].toString()),
        decidedByUserId: (json['decidedByUserId'] as num?)?.toInt(),
        decidedByName: json['decidedByName'] as String?,
        decidedAt:
            json['decidedAt'] == null ? null : DateTime.tryParse(json['decidedAt'].toString()),
        decisionNotes: json['decisionNotes'] as String?,
        payloadJson: json['payloadJson'] as String?,
        beforeJson: json['beforeJson'] as String?,
        targetVersion: (json['targetVersion'] as num?)?.toInt(),
        batchId: json['batchId'] as String?,
        mine: json['mine'] as bool?,
        canDecide: json['canDecide'] as bool?,
        cannotDecideReason: json['cannotDecideReason'] as String?,
      );
}
