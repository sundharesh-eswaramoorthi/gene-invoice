import '../../features/poc/poc_providers.dart';
import 'payment_term.dart';

class Customer {
  final int id;
  final String name;
  final String? phone;
  final String? email;
  final String? address;
  final double creditBalance;
  final String? username;
  final double outstanding;

  final double overdueAmount;

  /// The terms this customer's new invoices default to. Null means the system default (D1).
  final PaymentTerm? paymentTerm;

  final String? paymentTermLabel;

  final List<CustomerPoc>? successPocs;
  final List<CustomerPoc>? collectionPocs;
  final bool pocMissing;
  final DateTime? createdAt;

  /// Which branch this account is in. Null means the server did not say — an older payload, or
  /// a call that does not carry it — and a record that does not say cannot be narrowed by a
  /// branch, so every per-record check falls back to the plain privilege (B1).
  final int? regionId;
  final String? regionName;

  /// True when a change on this record is waiting for approval. The figures beside it are the
  /// LIVE ones — nothing pending has taken effect — so the flag is the only thing that differs
  /// from a record with nothing waiting. Defaults false: null on the wire means the server was
  /// not asked, and "not asked" must never read as "something is waiting" (B2).
  final bool approvalPending;

  const Customer({
    required this.id,
    required this.name,
    this.phone,
    this.email,
    this.address,
    required this.creditBalance,
    this.username,
    this.outstanding = 0,
    this.overdueAmount = 0,
    this.paymentTerm,
    this.paymentTermLabel,
    this.successPocs,
    this.collectionPocs,
    this.pocMissing = false,
    this.createdAt,
    this.regionId,
    this.regionName,
    this.approvalPending = false,
  });

  /// What to call this customer's terms on screen. No terms of their own is a legitimate state
  /// meaning "whatever the system default is" (D1), not a missing value, and the server names
  /// that default alongside it.
  String get termsLabel {
    if (paymentTerm != null) return paymentTermLabel ?? paymentTerm!.label;
    return paymentTermLabel == null
        ? systemDefaultTerms
        : '$systemDefaultTerms ($paymentTermLabel)';
  }

  static const systemDefaultTerms = 'System default';

  CustomerPoc? get primarySuccessPoc => _primary(successPocs);
  CustomerPoc? get primaryCollectionPoc => _primary(collectionPocs);

  static CustomerPoc? _primary(List<CustomerPoc>? list) {
    if (list == null || list.isEmpty) return null;
    for (final p in list) {
      if (p.primary) return p;
    }
    return list.first;
  }

  factory Customer.fromJson(Map<String, dynamic> json) => Customer(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
        phone: json['phone'] as String?,
        email: json['email'] as String?,
        address: json['address'] as String?,
        creditBalance: (json['creditBalance'] as num? ?? 0).toDouble(),
        username: json['username'] as String?,
        outstanding: (json['outstanding'] as num? ?? 0).toDouble(),
        overdueAmount: (json['overdueAmount'] as num? ?? 0).toDouble(),
        paymentTerm: parsePaymentTerm(json['paymentTerm'] as String?),
        paymentTermLabel: json['paymentTermLabel'] as String?,
        successPocs: (json['successPocs'] as List?)
            ?.cast<Map<String, dynamic>>()
            .map(CustomerPoc.fromJson)
            .toList(),
        collectionPocs: (json['collectionPocs'] as List?)
            ?.cast<Map<String, dynamic>>()
            .map(CustomerPoc.fromJson)
            .toList(),
        pocMissing: json['pocMissing'] as bool? ?? false,
        createdAt:
            json['createdAt'] == null ? null : DateTime.parse(json['createdAt'] as String),
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        approvalPending: json['approvalPending'] as bool? ?? false,
      );
}
