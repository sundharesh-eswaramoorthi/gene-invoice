import 'privileges.dart';

class CurrentUser {
  final int id;
  final String username;
  final String? fullName;
  final String role;
  final Set<String> privileges;
  final int? customerId;

  const CurrentUser({
    required this.id,
    required this.username,
    required this.fullName,
    required this.role,
    required this.privileges,
    required this.customerId,
  });

  factory CurrentUser.fromJson(Map<String, dynamic> json) => CurrentUser(
        id: (json['id'] as num).toInt(),
        username: json['username'] as String,
        fullName: json['fullName'] as String?,
        role: json['role'] as String,
        privileges: ((json['privileges'] as List?) ?? const [])
            .map((e) => e.toString())
            .toSet(),
        customerId: (json['customerId'] as num?)?.toInt(),
      );

  bool get isCustomer => customerId != null;
  bool get isAdmin => role == 'ADMIN';

  bool has(String privilege) => privileges.contains(privilege);
  bool hasAny(Iterable<String> privs) => privs.any(privileges.contains);

  /// Only a customer's own login may open a dispute (staff resolve them, and the backend refuses
  /// staff), so staff are never offered the button even when their role holds DISPUTE_CREATE.
  bool get canRaiseDispute => isCustomer && has(Privileges.disputeCreate);
}

class LoginResult {
  final String token;
  final int expiresInMs;
  final CurrentUser user;

  const LoginResult({required this.token, required this.expiresInMs, required this.user});

  factory LoginResult.fromJson(Map<String, dynamic> json) => LoginResult(
        token: json['token'] as String,
        expiresInMs: (json['expiresInMs'] as num).toInt(),
        user: CurrentUser.fromJson(json['user'] as Map<String, dynamic>),
      );
}
