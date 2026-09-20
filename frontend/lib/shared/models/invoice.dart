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

  /// When the money is due (§2.2). Null only on a payload from before due dates existed.
  final DateTime? dueDate;

  /// The terms the due date came from, `CUSTOM` when it was typed instead (§2.1).
  final PaymentTerm? paymentTerm;

  /// The server's name for [paymentTerm], so a term this build does not know still reads well.
  final String? paymentTermLabel;

  /// Derived by the server from today's date, never stored (D3).
  final bool overdue;
  final int daysOverdue;

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
  });

  /// What to call this invoice's terms on screen.
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
      );
}

/// A `yyyy-MM-dd` due date, which is a calendar fact rather than a moment: it is read as a local
/// date so it shows the same day wherever the browser is.
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
        createdAt: json['createdAt'] == null
            ? null
            : DateTime.parse(json['createdAt'] as String),
      );
}

/// What `GET /api/invoices/due-date-preview` answers: when an invoice raised for this customer
/// today would fall due, and the terms that date came from (§2.2).
class DueDatePreview {
  final DateTime dueDate;
  final PaymentTerm? paymentTerm;
  final String paymentTermLabel;

  /// True when the terms are the customer's own rather than the system default.
  final bool fromCustomer;

  const DueDatePreview({
    required this.dueDate,
    required this.paymentTerm,
    required this.paymentTermLabel,
    required this.fromCustomer,
  });

  /// The invoice date the server worked the due date out from, so changing the terms on the form
  /// recomputes the date the server itself would have given.
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
