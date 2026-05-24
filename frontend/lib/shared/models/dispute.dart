enum DisputeTargetType { INVOICE, PAYMENT }

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
  final String reason;
  final String? proposedChangeJson;
  final DisputeStatus status;
  final String? adminNotes;
  final int? resolvedByUserId;
  final DateTime? resolvedAt;
  final DateTime createdAt;
  final DateTime? updatedAt;

  const Dispute({
    required this.id,
    required this.customerId,
    required this.customerName,
    required this.openedByUserId,
    required this.targetType,
    required this.targetId,
    required this.targetSummary,
    required this.reason,
    required this.proposedChangeJson,
    required this.status,
    required this.adminNotes,
    required this.resolvedByUserId,
    required this.resolvedAt,
    required this.createdAt,
    required this.updatedAt,
  });

  factory Dispute.fromJson(Map<String, dynamic> json) => Dispute(
        id: (json['id'] as num).toInt(),
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String?,
        openedByUserId: (json['openedByUserId'] as num).toInt(),
        targetType: parseDisputeTarget(json['targetType'] as String?),
        targetId: (json['targetId'] as num).toInt(),
        targetSummary: json['targetSummary'] as String?,
        reason: json['reason'] as String,
        proposedChangeJson: json['proposedChangeJson'] as String?,
        status: parseDisputeStatus(json['status'] as String?),
        adminNotes: json['adminNotes'] as String?,
        resolvedByUserId: (json['resolvedByUserId'] as num?)?.toInt(),
        resolvedAt: json['resolvedAt'] == null ? null : DateTime.parse(json['resolvedAt'] as String),
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: json['updatedAt'] == null ? null : DateTime.parse(json['updatedAt'] as String),
      );
}
