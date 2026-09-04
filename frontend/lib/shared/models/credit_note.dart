class CreditNote {
  final int id;
  final int invoiceId;
  final String invoiceNumber;
  final double amount;
  final String reason;
  final int issuedByUserId;
  final String? issuedByName;
  final DateTime issuedAt;
  final bool voided;

  const CreditNote({
    required this.id,
    required this.invoiceId,
    required this.invoiceNumber,
    required this.amount,
    required this.reason,
    required this.issuedByUserId,
    required this.issuedByName,
    required this.issuedAt,
    required this.voided,
  });

  factory CreditNote.fromJson(Map<String, dynamic> json) => CreditNote(
        id: (json['id'] as num).toInt(),
        invoiceId: (json['invoiceId'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        amount: (json['amount'] as num).toDouble(),
        reason: json['reason'] as String,
        issuedByUserId: (json['issuedByUserId'] as num).toInt(),
        issuedByName: json['issuedByName'] as String?,
        issuedAt: DateTime.parse(json['issuedAt'] as String),
        voided: json['voided'] as bool? ?? false,
      );
}

/// Result of a committed issuance. The credit note is durable either way;
/// [notificationWarning] reports a post-commit admin notification failure.
class IssueCreditNoteResult {
  final CreditNote creditNote;
  final bool notificationWarning;
  final String? notificationWarningMessage;

  const IssueCreditNoteResult({
    required this.creditNote,
    required this.notificationWarning,
    required this.notificationWarningMessage,
  });

  factory IssueCreditNoteResult.fromJson(Map<String, dynamic> json) =>
      IssueCreditNoteResult(
        creditNote:
            CreditNote.fromJson(json['creditNote'] as Map<String, dynamic>),
        notificationWarning: json['notificationWarning'] as bool? ?? false,
        notificationWarningMessage: json['notificationWarningMessage'] as String?,
      );
}
