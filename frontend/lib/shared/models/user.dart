class AppUser {
  final int id;
  final String username;
  final String? email;
  final String? fullName;
  final bool active;
  final String? role;

  const AppUser({
    required this.id,
    required this.username,
    this.email,
    this.fullName,
    required this.active,
    this.role,
  });

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
        id: (json['id'] as num).toInt(),
        username: json['username'] as String,
        email: json['email'] as String?,
        fullName: json['fullName'] as String?,
        active: json['active'] as bool? ?? true,
        role: json['role'] as String?,
      );
}

class AppRole {
  final int id;
  final String name;
  final String? description;

  /// The role's shared mailbox, shown as the sender when an email goes out from the role.
  final String? email;
  final List<String> privileges;

  const AppRole({
    required this.id,
    required this.name,
    this.description,
    this.email,
    required this.privileges,
  });

  factory AppRole.fromJson(Map<String, dynamic> json) => AppRole(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
        description: json['description'] as String?,
        email: json['email'] as String?,
        privileges: ((json['privileges'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}
