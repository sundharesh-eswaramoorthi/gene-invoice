enum CreditNoteStatus { ACTIVE, VOIDED }

CreditNoteStatus parseCreditNoteStatus(String? s) {
  return CreditNoteStatus.values.firstWhere(
    (e) => e.name == s,
    orElse: () => CreditNoteStatus.ACTIVE,
  );
}

String creditNoteStatusLabel(CreditNoteStatus s) => switch (s) {
      CreditNoteStatus.ACTIVE => 'Active',
      CreditNoteStatus.VOIDED => 'Voided',
    };

class CreditNote {
  final int id;
  final int invoiceId;
  final double amount;
  final String reason;
  final int issuedByUserId;
  final DateTime issuedAt;
  final CreditNoteStatus status;

  const CreditNote({
    required this.id,
    required this.invoiceId,
    required this.amount,
    required this.reason,
    required this.issuedByUserId,
    required this.issuedAt,
    required this.status,
  });

  factory CreditNote.fromJson(Map<String, dynamic> json) => CreditNote(
        id: (json['id'] as num).toInt(),
        invoiceId: (json['invoiceId'] as num).toInt(),
        amount: (json['amount'] as num).toDouble(),
        reason: json['reason'] as String,
        issuedByUserId: (json['issuedByUserId'] as num).toInt(),
        issuedAt: DateTime.parse(json['issuedAt'] as String),
        status: parseCreditNoteStatus(json['status'] as String?),
      );
}

class IssueCreditNoteResult {
  final CreditNote creditNote;
  final String? warning;

  const IssueCreditNoteResult({
    required this.creditNote,
    this.warning,
  });

  factory IssueCreditNoteResult.fromJson(Map<String, dynamic> json) =>
      IssueCreditNoteResult(
        creditNote:
            CreditNote.fromJson(json['creditNote'] as Map<String, dynamic>),
        warning: json['warning'] as String?,
      );
}
