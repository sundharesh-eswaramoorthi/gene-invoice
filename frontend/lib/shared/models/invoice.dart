import '../../features/poc/poc_providers.dart';

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

  /// Null for a self-service customer, who never receives POC identity (AC-A8).
  final PocUser? salesPoc;

  /// True when this record predates the POC field and still has none (AC-A9).
  final bool pocMissing;

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
    this.salesPoc,
    this.pocMissing = false,
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
        salesPoc: json['salesPoc'] == null
            ? null
            : PocUser.fromJson(json['salesPoc'] as Map<String, dynamic>),
        pocMissing: json['pocMissing'] as bool? ?? false,
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
  final DateTime? createdAt;

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
    required this.notes,
    required this.items,
    super.salesPoc,
    super.pocMissing,
    this.createdAt,
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
        notes: json['notes'] as String?,
        items: ((json['items'] as List?) ?? const [])
            .map((e) => InvoiceLine.fromJson(e as Map<String, dynamic>))
            .toList(),
        salesPoc: json['salesPoc'] == null
            ? null
            : PocUser.fromJson(json['salesPoc'] as Map<String, dynamic>),
        pocMissing: json['pocMissing'] as bool? ?? false,
        createdAt: json['createdAt'] == null
            ? null
            : DateTime.parse(json['createdAt'] as String),
      );
}
