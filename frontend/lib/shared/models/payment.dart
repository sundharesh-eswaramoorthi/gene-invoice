import '../../features/poc/poc_providers.dart';
import 'invoice.dart';

enum PaymentStatus { ACTIVE, VOIDED }

PaymentStatus parsePaymentStatus(String? s) =>
    PaymentStatus.values.firstWhere((e) => e.name == s, orElse: () => PaymentStatus.ACTIVE);

class PaidInvoice {
  final int id;
  final String invoiceNumber;
  final double total;
  final double paidAmount;
  final double balance;
  final InvoiceStatus status;
  final double allocatedAmount;

  const PaidInvoice({
    required this.id,
    required this.invoiceNumber,
    required this.total,
    required this.paidAmount,
    required this.balance,
    required this.status,
    required this.allocatedAmount,
  });

  factory PaidInvoice.fromJson(Map<String, dynamic> json) => PaidInvoice(
        id: (json['id'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        total: (json['total'] as num).toDouble(),
        paidAmount: (json['paidAmount'] as num).toDouble(),
        balance: (json['balance'] as num).toDouble(),
        status: parseStatus(json['status'] as String?),
        allocatedAmount: (json['allocatedAmount'] as num? ?? 0).toDouble(),
      );
}

class PaymentRecord {
  final int id;
  final int customerId;
  final String customerName;
  final double amount;
  final double creditApplied;
  final String? method;
  final String? notes;
  final DateTime paidAt;
  final PaymentStatus status;
  final List<PaidInvoice> invoices;
  final double customerCreditBalance;

  final PocUser? collectionPoc;
  final bool pocMissing;

  /// The branch this payment's account is in. Null means the server did not say (B1).
  final int? regionId;
  final String? regionName;

  /// True when a change on this record is waiting for approval. The figures beside it are the
  /// LIVE ones — nothing pending has taken effect — so the flag is the only thing that differs
  /// from a record with nothing waiting. Defaults false: null on the wire means the server was
  /// not asked, and "not asked" must never read as "something is waiting" (B2).
  final bool approvalPending;

  const PaymentRecord({
    required this.id,
    required this.customerId,
    required this.customerName,
    required this.amount,
    required this.creditApplied,
    required this.method,
    required this.notes,
    required this.paidAt,
    required this.status,
    required this.invoices,
    required this.customerCreditBalance,
    this.collectionPoc,
    this.pocMissing = false,
    this.regionId,
    this.regionName,
    this.approvalPending = false,
  });

  factory PaymentRecord.fromJson(Map<String, dynamic> json) => PaymentRecord(
        id: (json['id'] as num).toInt(),
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String,
        amount: (json['amount'] as num).toDouble(),
        creditApplied: (json['creditApplied'] as num? ?? 0).toDouble(),
        method: json['method'] as String?,
        notes: json['notes'] as String?,
        paidAt: DateTime.parse(json['paidAt'] as String),
        status: parsePaymentStatus(json['status'] as String?),
        invoices: ((json['invoices'] as List?) ?? const [])
            .map((e) => PaidInvoice.fromJson(e as Map<String, dynamic>))
            .toList(),
        customerCreditBalance: (json['customerCreditBalance'] as num? ?? 0).toDouble(),
        collectionPoc: json['collectionPoc'] == null
            ? null
            : PocUser.fromJson(json['collectionPoc'] as Map<String, dynamic>),
        pocMissing: json['pocMissing'] as bool? ?? false,
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        approvalPending: json['approvalPending'] as bool? ?? false,
      );
}
