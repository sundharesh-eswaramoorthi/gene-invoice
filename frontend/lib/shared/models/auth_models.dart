import 'privileges.dart';

/// The three levels of the region ladder, exactly as the server spells them on the wire (B1).
const regionRightView = 'VIEW';
const regionRightManage = 'MANAGE';
const regionRightApprove = 'APPROVE';

/// What this person may do in one branch.
///
/// The ladder is deliberately NOT a total order: MANAGE does not cover APPROVE, because
/// four-eyes needs a checker who is not the maker, and APPROVE does not cover MANAGE, because
/// signing a change off is not making one. Both cover VIEW, so somebody who must work and
/// approve in the same branch holds two rights there (B1).
class RegionGrant {
  final int id;
  final String code;
  final String name;
  final Set<String> rights;

  const RegionGrant({
    required this.id,
    required this.code,
    required this.name,
    this.rights = const {},
  });

  bool get canView => rights.isNotEmpty;
  bool get canManage => rights.contains(regionRightManage);
  bool get canApprove => rights.contains(regionRightApprove);

  /// Mirrors RegionRight.covers on the server: everything covers VIEW, every right covers
  /// itself, and nothing else is implied (B1).
  bool covers(String right) =>
      right == regionRightView ? rights.isNotEmpty : rights.contains(right);

  /// What a branch is called on screen. The code is what every grant, refusal message and
  /// import names a branch by, so it leads (B1).
  String get label => name.isEmpty ? code : '$code — $name';

  factory RegionGrant.fromJson(Map<String, dynamic> json) => RegionGrant(
        id: (json['id'] as num).toInt(),
        code: json['code'] as String? ?? '',
        name: json['name'] as String? ?? '',
        rights: ((json['rights'] as List?) ?? const []).map((e) => e.toString()).toSet(),
      );

  @override
  bool operator ==(Object other) => other is RegionGrant && other.id == id;

  @override
  int get hashCode => id.hashCode;
}

/// The region right each privilege needs, mirroring the server's RegionRights partition. A
/// privilege that is absent is company-wide — the branch a record is in says nothing about
/// whether you may exercise it, which is why USER_MANAGE and REGION_MANAGE are not listed (B1).
///
/// A constant this client has never heard of also lands here as "company-wide", so a server that
/// grows a privilege before the client does offers the control rather than hiding it; the server
/// still refuses the write, which is the direction that cannot leak (B1).
const _regionRightNeeded = <String, String>{
  Privileges.customerView: regionRightView,
  Privileges.customerManage: regionRightManage,
  Privileges.invoiceView: regionRightView,
  Privileges.invoiceManage: regionRightManage,
  Privileges.paymentView: regionRightView,
  Privileges.paymentManage: regionRightManage,
  Privileges.promiseView: regionRightView,
  Privileges.promiseManage: regionRightManage,
  Privileges.promiseOverride: regionRightManage,
  Privileges.disputeView: regionRightView,
  Privileges.disputeCreate: regionRightManage,
  Privileges.disputeManage: regionRightManage,
  Privileges.pocView: regionRightView,
  Privileges.pocAssign: regionRightManage,
  Privileges.documentView: regionRightView,
  Privileges.documentManage: regionRightManage,
  Privileges.emailView: regionRightView,
  Privileges.emailSend: regionRightManage,
  Privileges.auditView: regionRightView,
  Privileges.exportData: regionRightView,
  Privileges.approvalView: regionRightView,
  Privileges.approvalApprove: regionRightApprove,
  Privileges.approvalConfigure: regionRightManage,
  Privileges.taskView: regionRightView,
  Privileges.taskManage: regionRightManage,
};

String? regionRightNeededFor(String privilege) => _regionRightNeeded[privilege];

class CurrentUser {
  final int id;
  final String username;
  final String? fullName;
  final String role;
  final Set<String> privileges;
  final int? customerId;

  /// Holds a wildcard grant: every branch, including ones opened later. The wire deliberately
  /// does not expand it into a list, because that would go stale the day a branch opens (B1).
  final bool allRegions;

  /// The NAMED branches this person works in, never including the wildcard. A branch they hold
  /// nothing in is simply absent, and an empty list with allRegions false is a real state (B1).
  final List<RegionGrant> regions;

  const CurrentUser({
    required this.id,
    required this.username,
    required this.fullName,
    required this.role,
    required this.privileges,
    required this.customerId,
    this.allRegions = false,
    this.regions = const [],
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
        allRegions: json['allRegions'] as bool? ?? false,
        regions: ((json['regions'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(RegionGrant.fromJson)
            .toList(),
      );

  bool get isCustomer => customerId != null;
  bool get isAdmin => role == 'ADMIN';

  /// "May I do X, somewhere." Unchanged in signature and in meaning, and still the gate for
  /// navigation: which sidebar entries exist and whether a "New invoice" button exists at all.
  /// The server already drops a privilege exercisable in no branch, so this is never wider than
  /// what the server will allow (B1).
  bool has(String privilege) => privileges.contains(privilege);
  bool hasAny(Iterable<String> privs) => privs.any(privileges.contains);

  /// "May I do X to THIS record." Called where the button is, not where the page is: a person
  /// who manages one branch and only reads another holds the privilege — so has() is true — and
  /// must still not be offered Edit on a row from the branch they only read (B1).
  ///
  /// A null regionId is a record that does not say which branch it is in, and a customer login
  /// holds no grants at all because its reach is its own account; both fall back to has().
  bool hasIn(String privilege, int? regionId) {
    if (!has(privilege)) return false;
    if (regionId == null || isCustomer) return true;
    final needed = regionRightNeededFor(privilege);
    // Company-wide: the branch a record is in says nothing about it (B1).
    if (needed == null) return true;
    if (allRegions) return true;
    for (final g in regions) {
      if (g.id == regionId) return g.covers(needed);
    }
    return false;
  }

  /// The branches this list is about when nothing has been narrowed. Empty for a wildcard
  /// holder, who is asking about all of them, and for a customer login (B1).
  Set<int> get workingSet => regions.map((g) => g.id).toSet();

  /// One branch and no wildcard: there is nothing to choose between, so the UI states the
  /// branch rather than offering a picker (B1).
  bool get isSingleRegion => !allRegions && regions.length == 1;

  /// The branches a write may name — a create, or a move's destination. Empty for a wildcard
  /// holder, whose answer is "every branch" and has to come from the region map (B1).
  Set<int> get manageableRegions =>
      regions.where((g) => g.canManage).map((g) => g.id).toSet();

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
