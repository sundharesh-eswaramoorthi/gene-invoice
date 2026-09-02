enum InvoiceStatus { UNPAID, PARTIALLY_PAID, FULLY_PAID, CANCELLED }

InvoiceStatus parseStatus(String? s) {
  return InvoiceStatus.values.firstWhere(
    (e) => e.name == s,
    orElse: () => InvoiceStatus.UNPAID,
  );
}

String statusLabel(InvoiceStatus s) => switch (s) {
      InvoiceStatus.UNPAID => 'Unpaid',
      InvoiceStatus.PARTIALLY_PAID => 'Partially paid',
      InvoiceStatus.FULLY_PAID => 'Fully paid',
      InvoiceStatus.CANCELLED => 'Cancelled',
    };
/// Lifecycle of a credit note: issued ACTIVE, may transition once to VOIDED.
/// Voided records are retained and remain visible; only ACTIVE notes count
/// toward the invoice's active credited total.
enum CreditNoteStatus { ACTIVE, VOIDED }

CreditNoteStatus parseCreditNoteStatus(String? s) =>
    CreditNoteStatus.values.firstWhere(
      (e) => e.name == s,
      orElse: () => CreditNoteStatus.ACTIVE,
    );

String creditNoteStatusLabel(CreditNoteStatus s) => switch (s) {
      CreditNoteStatus.ACTIVE => 'Active',
      CreditNoteStatus.VOIDED => 'Voided',
    };

/// One permanently retained credit note against an invoice. Amount, reason,
/// issuer and issue time are fixed at issuance; only [status] can change.
class CreditNote {
  final int id;
  final double amount;
  final String reason;
  final String? issuedBy;
  final DateTime issuedAt;
  final CreditNoteStatus status;

  const CreditNote({
    required this.id,
    required this.amount,
    required this.reason,
    required this.issuedBy,
    required this.issuedAt,
    required this.status,
  });

  bool get isVoided => status == CreditNoteStatus.VOIDED;

  factory CreditNote.fromJson(Map<String, dynamic> json) => CreditNote(
        id: (json['id'] as num).toInt(),
        amount: (json['amount'] as num).toDouble(),
        reason: json['reason'] as String,
        issuedBy: json['issuedBy'] as String?,
        issuedAt: DateTime.parse(json['issuedAt'] as String),
        status: parseCreditNoteStatus(json['status'] as String?),
      );
}

class InvoiceSummary {
  final int id;
  final String invoiceNumber;
  final int customerId;
  final String customerName;
  final DateTime invoiceDate;
  final double total;
  final double paidAmount;
  final double balance;

  /// Sum of the invoice's ACTIVE (non-voided) credit notes, as computed by
  /// the backend. 0 when the response carries no `activeCreditedTotal` key.
  final double activeCreditedTotal;

  /// The shared outstanding amount computed by the backend
  /// (total - paidAmount - active credits, floored at zero). Falls back to
  /// the legacy cash `balance` when the response carries no `outstanding`
  /// key, so older responses keep rendering exactly what they did before.
  final double outstanding;

  final InvoiceStatus status;

  const InvoiceSummary({
    required this.id,
    required this.invoiceNumber,
    required this.customerId,
    required this.customerName,
    required this.invoiceDate,
    required this.total,
    required this.paidAmount,
    required this.balance,
    this.activeCreditedTotal = 0,
    double? outstanding,
    required this.status,
  }) : outstanding = outstanding ?? balance;

  factory InvoiceSummary.fromJson(Map<String, dynamic> json) {
    final balance = (json['balance'] as num).toDouble();
    return InvoiceSummary(
      id: (json['id'] as num).toInt(),
      invoiceNumber: json['invoiceNumber'] as String,
      customerId: (json['customerId'] as num).toInt(),
      customerName: json['customerName'] as String,
      invoiceDate: DateTime.parse(json['invoiceDate'] as String),
      total: (json['total'] as num).toDouble(),
      paidAmount: (json['paidAmount'] as num).toDouble(),
      balance: balance,
      activeCreditedTotal: (json['activeCreditedTotal'] as num?)?.toDouble() ?? 0,
      outstanding: (json['outstanding'] as num?)?.toDouble() ?? balance,
      status: parseStatus(json['status'] as String?),
    );
  }
}

class InvoiceLine {
  final int? id;
  final int productId;
  final String productName;
  final int quantity;
  final double unitPrice;
  final double lineTotal;

  const InvoiceLine({
    this.id,
    required this.productId,
    required this.productName,
    required this.quantity,
    required this.unitPrice,
    required this.lineTotal,
  });

  factory InvoiceLine.fromJson(Map<String, dynamic> json) => InvoiceLine(
        id: (json['id'] as num?)?.toInt(),
        productId: (json['productId'] as num).toInt(),
        productName: json['productName'] as String,
        quantity: (json['quantity'] as num).toInt(),
        unitPrice: (json['unitPrice'] as num).toDouble(),
        lineTotal: (json['lineTotal'] as num).toDouble(),
      );
}

class InvoiceDetail extends InvoiceSummary {
  final String? notes;
  final List<InvoiceLine> items;

  /// Every retained credit note of this invoice, active and voided alike.
  /// Empty when the response carries no `creditNotes` key.
  final List<CreditNote> creditNotes;

  const InvoiceDetail({
    required super.id,
    required super.invoiceNumber,
    required super.customerId,
    required super.customerName,
    required super.invoiceDate,
    required super.total,
    required super.paidAmount,
    required super.balance,
    super.activeCreditedTotal,
    super.outstanding,
    required super.status,
    required this.notes,
    required this.items,
    this.creditNotes = const [],
  });

  factory InvoiceDetail.fromJson(Map<String, dynamic> json) {
    final balance = (json['balance'] as num).toDouble();
    return InvoiceDetail(
      id: (json['id'] as num).toInt(),
      invoiceNumber: json['invoiceNumber'] as String,
      customerId: (json['customerId'] as num).toInt(),
      customerName: json['customerName'] as String,
      invoiceDate: DateTime.parse(json['invoiceDate'] as String),
      total: (json['total'] as num).toDouble(),
      paidAmount: (json['paidAmount'] as num).toDouble(),
      balance: balance,
      activeCreditedTotal: (json['activeCreditedTotal'] as num?)?.toDouble() ?? 0,
      outstanding: (json['outstanding'] as num?)?.toDouble() ?? balance,
      status: parseStatus(json['status'] as String?),
      notes: json['notes'] as String?,
      items: ((json['items'] as List?) ?? const [])
          .map((e) => InvoiceLine.fromJson(e as Map<String, dynamic>))
          .toList(),
      creditNotes: ((json['creditNotes'] as List?) ?? const [])
          .map((e) => CreditNote.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}
