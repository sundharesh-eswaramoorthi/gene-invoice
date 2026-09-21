import '../../core/format.dart';
import 'assignee.dart';

enum DisputeTargetType { INVOICE, PAYMENT }

String disputeTargetLabel(DisputeTargetType t) => switch (t) {
      DisputeTargetType.INVOICE => 'Invoice',
      DisputeTargetType.PAYMENT => 'Payment',
    };

/// "Invoice INV-20260915-0003 — ₹4,51,234.50" or "Payment #158 — ₹1,000.00". Falls back to the
/// server's plain summary when the record is gone and has no number.
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

  /// Everyone answerable for the dispute, resolved as of this read (A1). Empty for a customer
  /// login, which never learns staff identity — so it means "not shown here", not "nobody"
  /// (AC-A8). It is never set on the form that opens a dispute: that form is worked from the
  /// customer's side, where there is no staff to pick, so assigning is a staff act of its own
  /// (`PATCH /api/disputes/{id}/assignees`).
  final List<Assignee> assignees;
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
    this.targetNumber,
    this.targetAmount,
    required this.reason,
    required this.proposedChangeJson,
    required this.status,
    required this.adminNotes,
    this.assignees = const [],
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
        targetNumber: json['targetNumber'] as String?,
        targetAmount: (json['targetAmount'] as num?)?.toDouble(),
        reason: json['reason'] as String,
        proposedChangeJson: json['proposedChangeJson'] as String?,
        status: parseDisputeStatus(json['status'] as String?),
        adminNotes: json['adminNotes'] as String?,
        assignees: ((json['assignees'] as List?) ?? const [])
            .cast<Map>()
            .map((m) => Assignee.fromJson(m.cast<String, dynamic>()))
            .toList(),
        resolvedByUserId: (json['resolvedByUserId'] as num?)?.toInt(),
        resolvedAt: json['resolvedAt'] == null ? null : DateTime.parse(json['resolvedAt'] as String),
        createdAt: DateTime.parse(json['createdAt'] as String),
        updatedAt: json['updatedAt'] == null ? null : DateTime.parse(json['updatedAt'] as String),
      );

  /// The tokens that would name these people again: what the assign dialog opens on (A1). A row
  /// that names neither a person nor a role has no token and is dropped, rather than sent back as
  /// something the server would refuse.
  List<EmailToken> get assigneeTokens => [
        for (final a in assignees)
          if (a.token != null) a.token!,
      ];
}
