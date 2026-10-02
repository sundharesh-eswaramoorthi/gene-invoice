import '../../features/poc/poc_providers.dart';
import 'record_assignee.dart';

enum PromiseStatus { OPEN, KEPT, PARTIALLY_KEPT, BROKEN, CANCELLED }

PromiseStatus parsePromiseStatus(String? s) =>
    PromiseStatus.values.firstWhere((e) => e.name == s, orElse: () => PromiseStatus.OPEN);

String promiseStatusLabel(PromiseStatus s) => switch (s) {
      PromiseStatus.OPEN => 'Open',
      PromiseStatus.KEPT => 'Kept',
      PromiseStatus.PARTIALLY_KEPT => 'Partially kept',
      PromiseStatus.BROKEN => 'Broken',
      PromiseStatus.CANCELLED => 'Cancelled',
    };

class PromiseInvoiceRef {
  final int id;
  final String invoiceNumber;
  final double total;
  final double balance;
  final String status;

  const PromiseInvoiceRef({
    required this.id,
    required this.invoiceNumber,
    required this.total,
    required this.balance,
    required this.status,
  });

  factory PromiseInvoiceRef.fromJson(Map<String, dynamic> json) => PromiseInvoiceRef(
        id: (json['id'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
        total: (json['total'] as num? ?? 0).toDouble(),
        balance: (json['balance'] as num? ?? 0).toDouble(),
        status: json['status'] as String? ?? '',
      );
}

class PromisePaymentRef {
  final int id;
  final double amount;
  final DateTime? paidAt;
  final String? method;
  final String status;

  const PromisePaymentRef({
    required this.id,
    required this.amount,
    required this.paidAt,
    required this.method,
    required this.status,
  });

  factory PromisePaymentRef.fromJson(Map<String, dynamic> json) => PromisePaymentRef(
        id: (json['id'] as num).toInt(),
        amount: (json['amount'] as num? ?? 0).toDouble(),
        paidAt: json['paidAt'] == null ? null : DateTime.parse(json['paidAt'] as String),
        method: json['method'] as String?,
        status: json['status'] as String? ?? '',
      );
}

class PaymentPromise {
  final int id;
  final int customerId;
  final String customerName;
  final double amount;
  final double fulfilledAmount;
  final double remainingAmount;
  final DateTime promisedDate;
  final PromiseStatus status;
  final bool statusOverridden;
  final String? overrideReason;
  final int? overriddenByUserId;
  final DateTime? overriddenAt;

  final PocUser? collectionPoc;
  final String? notes;
  final List<PromiseInvoiceRef> invoices;
  final List<PromisePaymentRef> payments;
  final int? createdByUserId;
  final DateTime? createdAt;

  final int? regionId;
  final String? regionCode;
  final String? regionName;

  /// What this user may do to this promise in its region (D14); null in a snapshot.
  final bool? canManage;
  final bool? canOverride;

  /// A change to it is awaiting approval (list rows only).
  final bool? pendingApproval;

  /// Its co-assignees (detail only).
  final List<RecordAssignee>? assignees;

  const PaymentPromise({
    required this.id,
    required this.customerId,
    required this.customerName,
    required this.amount,
    required this.fulfilledAmount,
    required this.remainingAmount,
    required this.promisedDate,
    required this.status,
    required this.statusOverridden,
    this.overrideReason,
    this.overriddenByUserId,
    this.overriddenAt,
    this.collectionPoc,
    this.notes,
    this.invoices = const [],
    this.payments = const [],
    this.createdByUserId,
    this.createdAt,
    this.regionId,
    this.regionCode,
    this.regionName,
    this.canManage,
    this.canOverride,
    this.pendingApproval,
    this.assignees,
  });

  bool get isLive => status != PromiseStatus.CANCELLED;
  bool get isOpen => status == PromiseStatus.OPEN || status == PromiseStatus.PARTIALLY_KEPT;

  double get referencedBalance =>
      invoices.fold<double>(0, (sum, i) => sum + i.balance);

  factory PaymentPromise.fromJson(Map<String, dynamic> json) => PaymentPromise(
        id: (json['id'] as num).toInt(),
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String? ?? '',
        amount: (json['amount'] as num? ?? 0).toDouble(),
        fulfilledAmount: (json['fulfilledAmount'] as num? ?? 0).toDouble(),
        remainingAmount: (json['remainingAmount'] as num? ?? 0).toDouble(),
        promisedDate: DateTime.parse(json['promisedDate'] as String),
        status: parsePromiseStatus(json['status'] as String?),
        statusOverridden: json['statusOverridden'] as bool? ?? false,
        overrideReason: json['overrideReason'] as String?,
        overriddenByUserId: (json['overriddenByUserId'] as num?)?.toInt(),
        overriddenAt: json['overriddenAt'] == null
            ? null
            : DateTime.parse(json['overriddenAt'] as String),
        collectionPoc: json['collectionPoc'] == null
            ? null
            : PocUser.fromJson(json['collectionPoc'] as Map<String, dynamic>),
        notes: json['notes'] as String?,
        invoices: ((json['invoices'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(PromiseInvoiceRef.fromJson)
            .toList(),
        payments: ((json['payments'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(PromisePaymentRef.fromJson)
            .toList(),
        createdByUserId: (json['createdByUserId'] as num?)?.toInt(),
        createdAt:
            json['createdAt'] == null ? null : DateTime.parse(json['createdAt'] as String),
        regionId: (json['regionId'] as num?)?.toInt(),
        regionCode: json['regionCode'] as String?,
        regionName: json['regionName'] as String?,
        canManage: json['canManage'] as bool?,
        canOverride: json['canOverride'] as bool?,
        pendingApproval: json['pendingApproval'] as bool?,
        assignees: RecordAssignee.listFrom(json['assignees']),
      );
}
