import '../../features/poc/poc_providers.dart';
import 'payment_term.dart';

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

  final DateTime? dueDate;

  final PaymentTerm? paymentTerm;

  final String? paymentTermLabel;

  /// Derived by the server from today's date, never stored (D3).
  final bool overdue;
  final int daysOverdue;

  final double total;
  final double paidAmount;
  final double balance;
  final InvoiceStatus status;

  final PocUser? salesPoc;

  final bool pocMissing;

  /// The branch this invoice's account is in. Null means the server did not say (B1).
  final int? regionId;
  final String? regionName;

  /// True when a change on this record is waiting for approval. The figures beside it are the
  /// LIVE ones — nothing pending has taken effect — so the flag is the only thing that differs
  /// from a record with nothing waiting. Defaults false: null on the wire means the server was
  /// not asked, and "not asked" must never read as "something is waiting" (B2).
  final bool approvalPending;

  const InvoiceSummary({
    required this.id,
    required this.invoiceNumber,
    required this.customerId,
    required this.customerName,
    required this.invoiceDate,
    this.dueDate,
    this.paymentTerm,
    this.paymentTermLabel,
    this.overdue = false,
    this.daysOverdue = 0,
    required this.total,
    required this.paidAmount,
    required this.balance,
    required this.status,
    this.salesPoc,
    this.pocMissing = false,
    this.regionId,
    this.regionName,
    this.approvalPending = false,
  });

  String get termsLabel => paymentTermLabel ?? paymentTerm?.label ?? '—';

  factory InvoiceSummary.fromJson(Map<String, dynamic> json) => InvoiceSummary(
        id: (json['id'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String,
        invoiceDate: DateTime.parse(json['invoiceDate'] as String),
        dueDate: parseDueDate(json['dueDate']),
        paymentTerm: parsePaymentTerm(json['paymentTerm'] as String?),
        paymentTermLabel: json['paymentTermLabel'] as String?,
        overdue: json['overdue'] as bool? ?? false,
        daysOverdue: (json['daysOverdue'] as num? ?? 0).toInt(),
        total: (json['total'] as num).toDouble(),
        paidAmount: (json['paidAmount'] as num).toDouble(),
        balance: (json['balance'] as num).toDouble(),
        status: parseStatus(json['status'] as String?),
        salesPoc: json['salesPoc'] == null
            ? null
            : PocUser.fromJson(json['salesPoc'] as Map<String, dynamic>),
        pocMissing: json['pocMissing'] as bool? ?? false,
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        approvalPending: json['approvalPending'] as bool? ?? false,
      );
}

DateTime? parseDueDate(Object? wire) =>
    wire == null ? null : DateTime.tryParse(wire.toString());

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
    super.dueDate,
    super.paymentTerm,
    super.paymentTermLabel,
    super.overdue,
    super.daysOverdue,
    required super.total,
    required super.paidAmount,
    required super.balance,
    required super.status,
    required this.notes,
    required this.items,
    super.salesPoc,
    super.pocMissing,
    super.regionId,
    super.regionName,
    super.approvalPending,
    this.createdAt,
  });

  factory InvoiceDetail.fromJson(Map<String, dynamic> json) => InvoiceDetail(
        id: (json['id'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String,
        invoiceDate: DateTime.parse(json['invoiceDate'] as String),
        dueDate: parseDueDate(json['dueDate']),
        paymentTerm: parsePaymentTerm(json['paymentTerm'] as String?),
        paymentTermLabel: json['paymentTermLabel'] as String?,
        overdue: json['overdue'] as bool? ?? false,
        daysOverdue: (json['daysOverdue'] as num? ?? 0).toInt(),
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
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        approvalPending: json['approvalPending'] as bool? ?? false,
        createdAt: json['createdAt'] == null
            ? null
            : DateTime.parse(json['createdAt'] as String),
      );
}

class DueDatePreview {
  final DateTime dueDate;
  final PaymentTerm? paymentTerm;
  final String paymentTermLabel;

  final bool fromCustomer;

  const DueDatePreview({
    required this.dueDate,
    required this.paymentTerm,
    required this.paymentTermLabel,
    required this.fromCustomer,
  });

  DateTime? get invoiceDate => paymentTerm?.basisOf(dueDate);

  factory DueDatePreview.fromJson(Map<String, dynamic> json) {
    final term = parsePaymentTerm(json['paymentTerm'] as String?);
    return DueDatePreview(
      dueDate: DateTime.parse(json['dueDate'] as String),
      paymentTerm: term,
      paymentTermLabel: json['paymentTermLabel'] as String? ?? term?.label ?? '',
      fromCustomer: json['source'] == 'CUSTOMER',
    );
  }
}
