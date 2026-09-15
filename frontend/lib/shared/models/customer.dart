import '../../features/poc/poc_providers.dart';

class Customer {
  final int id;
  final String name;
  final String? phone;
  final String? email;
  final String? address;
  final double creditBalance;
  final String? username;
  final double outstanding;

  /// Null for a self-service customer, who never receives POC identity (AC-A8).
  final List<CustomerPoc>? successPocs;
  final List<CustomerPoc>? collectionPocs;
  final bool pocMissing;
  final DateTime? createdAt;

  const Customer({
    required this.id,
    required this.name,
    this.phone,
    this.email,
    this.address,
    required this.creditBalance,
    this.username,
    this.outstanding = 0,
    this.successPocs,
    this.collectionPocs,
    this.pocMissing = false,
    this.createdAt,
  });

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
      );
}
