class AppUser {
  final int id;
  final String username;
  final String? email;
  final String? fullName;
  final bool active;
  final String? role;

  /// Set for a customer login: the customer account it signs in to.
  final int? customerId;

  /// Null until the server sends it; the details page then leaves the date out.
  final DateTime? createdAt;

  const AppUser({
    required this.id,
    required this.username,
    this.email,
    this.fullName,
    required this.active,
    this.role,
    this.customerId,
    this.createdAt,
  });

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
        id: (json['id'] as num).toInt(),
        username: json['username'] as String,
        email: json['email'] as String?,
        fullName: json['fullName'] as String?,
        active: json['active'] as bool? ?? true,
        role: json['role'] as String?,
        customerId: (json['customerId'] as num?)?.toInt(),
        createdAt:
            json['createdAt'] == null ? null : DateTime.parse(json['createdAt'] as String),
      );
}

class AppRole {
  final int id;
  final String name;
  final String? description;
  final List<String> privileges;

  const AppRole({
    required this.id,
    required this.name,
    this.description,
    required this.privileges,
  });

  factory AppRole.fromJson(Map<String, dynamic> json) => AppRole(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
        description: json['description'] as String?,
        privileges: ((json['privileges'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}
