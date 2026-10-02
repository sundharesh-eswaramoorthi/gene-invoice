import '../../core/format.dart';
import 'record_assignee.dart';

/// CUSTOMER is appended last, as the server's enum is (D12). Only a rule raises one; people raise
/// disputes against an invoice or a payment.
enum DisputeTargetType { INVOICE, PAYMENT, CUSTOMER }

/// What a person may raise a dispute on — never [DisputeTargetType.values], which has CUSTOMER
/// for the rules' own (§5.10.11).
const disputeTargetsRaisedByPeople = [DisputeTargetType.INVOICE, DisputeTargetType.PAYMENT];

String disputeTargetLabel(DisputeTargetType t) => switch (t) {
      DisputeTargetType.INVOICE => 'Invoice',
      DisputeTargetType.PAYMENT => 'Payment',
      DisputeTargetType.CUSTOMER => 'Customer',
    };

String disputeTargetText(Dispute d) {
  final number = d.targetNumber;
  if (number == null) return d.targetSummary ?? '${disputeTargetLabel(d.targetType)} #${d.targetId}';
  final label = '${disputeTargetLabel(d.targetType)} $number';
  return d.targetAmount == null ? label : '$label — ${formatMoney(d.targetAmount)}';
}

DisputeTargetType parseDisputeTarget(String? s) =>
    DisputeTargetType.values.firstWhere((e) => e.name == s, orElse: () => DisputeTargetType.INVOICE);

enum DisputeStatus { PENDING, APPROVED, DENIED }

DisputeStatus parseDisputeStatus(String? s) =>
    DisputeStatus.values.firstWhere((e) => e.name == s, orElse: () => DisputeStatus.PENDING);

String disputeStatusLabel(DisputeStatus s) => switch (s) {
      DisputeStatus.PENDING => 'Pending',
      DisputeStatus.APPROVED => 'Approved',
      DisputeStatus.DENIED => 'Denied',
    };

class Dispute {
  final int id;
  final int customerId;
  final String? customerName;
  final int openedByUserId;
  final DisputeTargetType targetType;
  final int targetId;
  final String? targetSummary;
  final String? targetNumber;
  final double? targetAmount;
  final String reason;
  final String? proposedChangeJson;
  final DisputeStatus status;
  final String? adminNotes;
  final int? resolvedByUserId;
  final DateTime? resolvedAt;
  final DateTime createdAt;
  final DateTime? updatedAt;

  final int? regionId;
  final String? regionCode;
  final String? regionName;

  /// Whether this user may approve or deny it, in its region, now (D14); null in a snapshot.
  final bool? canResolve;

  /// Raised by a rule for the team's own follow-up, never shown to the customer.
  final bool? internal;

  /// USER, AUTOMATION and so on; with [ruleId] when a rule raised it.
  final String? origin;
  final int? ruleId;

  /// A change to it is awaiting approval (list rows only).
  final bool? pendingApproval;

  /// Who it is assigned to (detail only).
  final List<RecordAssignee>? assignees;

  const Dispute({
    required this.id,
    required this.customerId,
    required this.customerName,
    required this.openedByUserId,
    required this.targetType,
    required this.targetId,
    required this.targetSummary,
    this.targetNumber,
    this.targetAmount,
    required this.reason,
    required this.proposedChangeJson,
    required this.status,
    required this.adminNotes,
    required this.resolvedByUserId,
    required this.resolvedAt,
    required this.createdAt,
    required this.updatedAt,
    this.regionId,
    this.regionCode,
    this.regionName,
    this.canResolve,
    this.internal,
    this.origin,
    this.ruleId,
    this.pendingApproval,
    this.assignees,
  });

  factory Dispute.fromJson(Map<String, dynamic> json) => Dispute(
        id: (json['id'] as num).toInt(),
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String?,
        openedByUserId: (json['openedByUserId'] as num).toInt(),
        targetType: parseDisputeTarget(json['targetType'] as String?),
        targetId: (json['targetId'] as num).toInt(),
        targetSummary: json['targetSummary'] as String?,
        targetNumber: json['targetNumber'] as String?,
        targetAmount: (json['targetAmount'] as num?)?.toDouble(),
        reason: json['reason'] as String,
        proposedChangeJson: json['proposedChangeJson'] as String?,
        status: parseDisputeStatus(json['status'] as String?),
        adminNotes: json['adminNotes'] as String?,
        resolvedByUserId: (json['resolvedByUserId'] as num?)?.toInt(),
        resolvedAt: json['resolvedAt'] == null ? null : DateTime.parse(json['resolvedAt'] as String),
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: json['updatedAt'] == null ? null : DateTime.parse(json['updatedAt'] as String),
        regionId: (json['regionId'] as num?)?.toInt(),
        regionCode: json['regionCode'] as String?,
        regionName: json['regionName'] as String?,
        canResolve: json['canResolve'] as bool?,
        internal: json['internal'] as bool?,
        origin: json['origin'] as String?,
        ruleId: (json['ruleId'] as num?)?.toInt(),
        pendingApproval: json['pendingApproval'] as bool?,
        assignees: RecordAssignee.listFrom(json['assignees']),
      );
}
