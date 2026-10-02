import 'privileges.dart';

/// One region the user holds a grant in, and what that grant lets them do there (B1).
class RegionAccess {
  final int id;
  final String code;
  final String name;
  final bool active;
  final bool defaultRegion;

  /// READ, MANAGE or APPROVE.
  final String? level;

  /// ALL or BOOK.
  final String? scope;

  /// The regional privileges effective in this region; global ones appear only on the user.
  final Set<String> privileges;

  const RegionAccess({
    required this.id,
    required this.code,
    required this.name,
    this.active = true,
    this.defaultRegion = false,
    this.level,
    this.scope,
    this.privileges = const {},
  });

  factory RegionAccess.fromJson(Map<String, dynamic> json) => RegionAccess(
        id: (json['id'] as num).toInt(),
        code: json['code'] as String? ?? '',
        name: json['name'] as String? ?? json['code'] as String? ?? '',
        active: json['active'] as bool? ?? true,
        defaultRegion: json['defaultRegion'] as bool? ?? false,
        level: json['level'] as String?,
        scope: json['scope'] as String?,
        privileges: ((json['privileges'] as List?) ?? const []).map((e) => e.toString()).toSet(),
      );
}

class CurrentUser {
  final int id;
  final String username;
  final String? fullName;
  final String role;
  final Set<String> privileges;
  final int? customerId;

  /// Empty for customer logins, whose one region comes from their customer.
  final List<RegionAccess> regions;

  /// Whether the server said which regions the user holds. An empty [regions] then means a staff
  /// user deliberately left with no access (§4.6), not a server that predates regions.
  final bool regionsReported;

  const CurrentUser({
    required this.id,
    required this.username,
    required this.fullName,
    required this.role,
    required this.privileges,
    required this.customerId,
    this.regions = const [],
    this.regionsReported = false,
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
        regions: ((json['regions'] as List?) ?? const [])
            .whereType<Map>()
            .map((e) => RegionAccess.fromJson(e.cast<String, dynamic>()))
            .toList(),
        regionsReported: json['regions'] is List,
      );

  /// Whether [regions] is the user's actual access, empty or not.
  bool get regionsKnown => regionsReported || regions.isNotEmpty;

  bool get isCustomer => customerId != null;
  bool get isAdmin => role == 'ADMIN';

  bool has(String privilege) => privileges.contains(privilege);
  bool hasAny(Iterable<String> privs) => privs.any(privileges.contains);

  /// Whether [privilege] holds in one region — for offering choices in create flows. A record's own
  /// rights come from the server's flags, never from here (D14). With no region to ask about, no
  /// regions known, or a customer login, it falls back to [has]. A global privilege is the role's
  /// alone (§4.2), so it holds in any region the user can see.
  bool hasIn(String privilege, int? regionId) {
    if (regionId == null || isCustomer || !regionsKnown) return has(privilege);
    for (final r in regions) {
      if (r.id == regionId) return _holdsIn(r, privilege);
    }
    return false;
  }

  Set<int> regionsWith(String privilege) =>
      {for (final r in regions) if (_holdsIn(r, privilege)) r.id};

  bool _holdsIn(RegionAccess region, String privilege) =>
      Privileges.global.contains(privilege) ? has(privilege) : region.privileges.contains(privilege);

  bool get multiRegion => regions.length > 1;

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
