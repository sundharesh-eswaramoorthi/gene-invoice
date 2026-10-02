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

  /// The region this customer — and everything raised against it — belongs to (B1).
  final int? regionId;
  final String? regionCode;
  final String? regionName;

  /// What the server says this user may do to this customer in its region (D14). Null in a
  /// snapshot, where the screen falls back to the flat privilege.
  final bool? canManage;
  final bool? canMoveRegion;

  /// The optimistic-lock version a region move is checked against, when the server sends it.
  final int? version;

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
    this.regionCode,
    this.regionName,
    this.canManage,
    this.canMoveRegion,
    this.version,
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
        regionCode: json['regionCode'] as String?,
        regionName: json['regionName'] as String?,
        canManage: json['canManage'] as bool?,
        canMoveRegion: json['canMoveRegion'] as bool?,
        version: (json['version'] as num?)?.toInt(),
      );
}
