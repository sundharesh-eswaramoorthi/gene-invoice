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

class InvoiceSummary {
  final int id;
  final String invoiceNumber;
  final int customerId;
  final String customerName;
  final DateTime invoiceDate;
  final double total;
  final double paidAmount;
  final double balance;
  final InvoiceStatus status;
  final double creditedAmount;
  final double outstandingAmount;

  const InvoiceSummary({
    required this.id,
    required this.invoiceNumber,
    required this.customerId,
    required this.customerName,
    required this.invoiceDate,
    required this.total,
    required this.paidAmount,
    required this.balance,
    required this.status,
    this.creditedAmount = 0,
    this.outstandingAmount = 0,
  });

  factory InvoiceSummary.fromJson(Map<String, dynamic> json) => InvoiceSummary(
        id: (json['id'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String,
        invoiceDate: DateTime.parse(json['invoiceDate'] as String),
        total: (json['total'] as num).toDouble(),
        paidAmount: (json['paidAmount'] as num).toDouble(),
        balance: (json['balance'] as num).toDouble(),
        status: parseStatus(json['status'] as String?),
        creditedAmount: (json['creditedAmount'] as num?)?.toDouble() ?? 0,
        outstandingAmount: (json['outstandingAmount'] as num?)?.toDouble() ?? 0,
      );
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

  const InvoiceDetail({
    required super.id,
    required super.invoiceNumber,
    required super.customerId,
    required super.customerName,
    required super.invoiceDate,
    required super.total,
    required super.paidAmount,
    required super.balance,
    required super.status,
    super.creditedAmount = 0,
    super.outstandingAmount = 0,
    required this.notes,
    required this.items,
  });

  factory InvoiceDetail.fromJson(Map<String, dynamic> json) => InvoiceDetail(
        id: (json['id'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String,
        invoiceDate: DateTime.parse(json['invoiceDate'] as String),
        total: (json['total'] as num).toDouble(),
        paidAmount: (json['paidAmount'] as num).toDouble(),
        balance: (json['balance'] as num).toDouble(),
        status: parseStatus(json['status'] as String?),
        creditedAmount: (json['creditedAmount'] as num?)?.toDouble() ?? 0,
        outstandingAmount: (json['outstandingAmount'] as num?)?.toDouble() ?? 0,
        notes: json['notes'] as String?,
        items: ((json['items'] as List?) ?? const [])
            .map((e) => InvoiceLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
