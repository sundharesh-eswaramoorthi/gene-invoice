import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/auth_controller.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../api/api_client.dart';
import '../table/table_models.dart';

/// The wire name of the region column, identical on the API, in a locked chip, in the URL and
/// here. Nothing about the region selector is a new query parameter (B1).
const regionFilterField = 'regionId';

@immutable
class RegionRef {
  final int id;
  final String code;
  final String name;
  final bool active;
  final DateTime? createdAt;

  const RegionRef({
    required this.id,
    required this.code,
    required this.name,
    this.active = true,
    this.createdAt,
  });

  /// The code leads, because it is what every grant, refusal message and import names a branch
  /// by, and the name is what a reader recognises (B1).
  String get label => name.isEmpty ? code : '$code — $name';

  factory RegionRef.fromJson(Map<String, dynamic> json) => RegionRef(
        id: (json['id'] as num).toInt(),
        code: json['code'] as String? ?? '',
        name: json['name'] as String? ?? '',
        active: json['active'] as bool? ?? true,
        createdAt:
            json['createdAt'] == null ? null : DateTime.tryParse('${json['createdAt']}'),
      );

  static RegionRef of(RegionGrant g) => RegionRef(id: g.id, code: g.code, name: g.name);

  @override
  bool operator ==(Object other) => other is RegionRef && other.id == id;

  @override
  int get hashCode => id.hashCode;
}

/// The caller's own roster: which branches they may work in and at what level.
///
/// GET /api/pocs/my-scope cannot serve this — it needs POC_VIEW, which somebody who works in a
/// branch may not hold — so the roster has its own endpoint behind isAuthenticated (B1).
@immutable
class MyRegions {
  final bool allRegions;
  final List<RegionGrant> regions;

  const MyRegions({required this.allRegions, required this.regions});

  static const none = MyRegions(allRegions: false, regions: []);

  factory MyRegions.fromJson(Map<String, dynamic> json) => MyRegions(
        allRegions: json['allRegions'] as bool? ?? false,
        regions: ((json['regions'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(RegionGrant.fromJson)
            .toList(),
      );
}

final canViewRegionsProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.regionView);
});

final canManageRegionsProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.regionManage);
});

/// Editing somebody else's branches needs both privileges, because it edits a person AND hands
/// out region reach, and either alone is half an answer — the same pair the endpoint asks for.
final canEditRegionGrantsProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.userManage) && user.has(Privileges.regionManage);
});

/// Seeing where somebody else may work needs both VIEW privileges — the pair
/// GET /api/users/{id}/regions asks for, and strictly less than the MANAGE pair that CHANGES
/// them. A reader may legitimately look at a roster they may not rewrite, which is why this is a
/// second provider and not a rename of the one above (B1).
final canViewRegionGrantsProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.userView) && user.has(Privileges.regionView);
});

/// Why a roster could not be read, as the sentence to show for it.
///
/// It exists so "you may not read this" arrives as a FAILURE and never as an empty roster.
/// MyRegions.none is a real answer — "they hold nothing, anywhere" — and an editor that REPLACES
/// the whole set would take that answer at its word and strip every grant the person has (B1).
class RegionGrantsUnreadable implements Exception {
  final String reason;
  const RegionGrantsUnreadable(this.reason);

  /// apiErrorMessage falls through to toString for anything that is not a DioException, so the
  /// reason is what a dialog renders, without a "Bad state:" prefix in front of it (B1).
  @override
  String toString() => reason;
}

/// ONE PERSON'S grants — somebody else's, read from the grant table.
///
/// GET /api/regions/my cannot serve this: it answers for the CALLER, off their principal. This is
/// the read side of the very endpoint that REPLACES the set, in the identical shape and from one
/// builder on the server, so what an editor shows and what a save writes cannot drift (B1).
final userRegionGrantsProvider = FutureProvider.family<MyRegions, int>((ref, userId) async {
  if (!ref.watch(canViewRegionGrantsProvider)) {
    throw const RegionGrantsUnreadable(
        'Reading which branches somebody holds needs the Users and Regions view privileges.');
  }
  final dio = ref.watch(dioProvider);
  // No asOf, ever: user_region_grants keeps no interval history, the endpoint is registered in
  // AsOfEndpoints.NOT_AS_OF, and asking it as-of is a 400 by design (B3).
  final res = await dio.get('/api/users/$userId/regions');
  return MyRegions.fromJson((res.data as Map).cast<String, dynamic>());
});

final myRegionsProvider = FutureProvider<MyRegions>((ref) async {
  final user = ref.watch(currentUserProvider);
  // A customer login holds no grants by design: its reach is its own account (B1).
  if (user == null || user.isCustomer) return MyRegions.none;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/regions/my');
  return MyRegions.fromJson((res.data as Map).cast<String, dynamic>());
});

/// The whole region map. Only readable by somebody holding REGION_VIEW, which is company-wide,
/// so this returns an empty list rather than a 403 for everybody else (B1).
final regionMapProvider = FutureProvider<List<RegionRef>>((ref) async {
  if (!ref.watch(canViewRegionsProvider)) return const [];
  final dio = ref.watch(dioProvider);
  // Paged, not asked for in one go. TableQuery.ALLOWED_SIZES is [10, 20, 50] and the server
  // answers anything else with 400 "size must be one of [10, 20, 50]" — a single size:200 read
  // meant the map never loaded at all, so the branch picker and the grant editor showed only
  // "Could not load the branches" and a company with more than one branch could not be
  // administered from the UI. 50 is the largest the server will serve (B1).
  const pageSize = 50;
  final all = <RegionRef>[];
  for (var page = 0;; page++) {
    final res = await dio.get('/api/regions', queryParameters: {
      'page': page,
      'size': pageSize,
      'sort': 'code,asc',
    });
    final body = (res.data as Map);
    final rows = (body['content'] as List? ?? const [])
        .cast<Map<String, dynamic>>()
        .map(RegionRef.fromJson);
    all.addAll(rows);
    final totalPages = (body['totalPages'] as num?)?.toInt() ?? 1;
    if (page + 1 >= totalPages || rows.isEmpty) break;
  }
  return all;
});

/// Where this person may work, taking the roster over the sign-in payload.
///
/// The two say the same thing, but the payload is only rebuilt when they sign in: somebody given
/// a branch this morning would not see it until tomorrow. The server reads the grant table on
/// every request, so the roster is the answer that matches what a write will be judged by. When
/// it cannot be reached the payload still answers, which is never wider than the truth (B1).
Future<MyRegions> _roster(Ref ref, CurrentUser user) async {
  try {
    return await ref.watch(myRegionsProvider.future);
  } catch (_) {
    return MyRegions(allRegions: user.allRegions, regions: user.regions);
  }
}

List<RegionRef> _sorted(Iterable<RegionGrant> grants) {
  final out = [for (final g in grants) RegionRef.of(g)];
  out.sort((a, b) => a.code.compareTo(b.code));
  return out;
}

/// The branches a list may be narrowed to: the ones this person works in, or — for a wildcard
/// holder, whose grants name none — the whole map. A wildcard holder who cannot read the map is
/// offered nothing to choose between, which is honest: the client does not know the branches (B1).
final selectableRegionsProvider = FutureProvider<List<RegionRef>>((ref) async {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return const [];
  final roster = await _roster(ref, user);
  if (!roster.allRegions) return _sorted(roster.regions);
  return ref.watch(regionMapProvider.future);
});

/// The branches a write may NAME: a new account's home, or a move's destination. Naming one you
/// cannot manage is a 403 and not a 404, because no id space is being probed (D-46), so the
/// picker offers only the ones that will be accepted (B1).
final manageableRegionsProvider = FutureProvider<List<RegionRef>>((ref) async {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return const [];
  final roster = await _roster(ref, user);
  if (roster.allRegions) {
    final all = await ref.watch(regionMapProvider.future);
    // A retired branch keeps its history and its accounts; nothing new is opened in one (B1).
    return all.where((r) => r.active).toList();
  }
  return _sorted(roster.regions.where((g) => g.canManage));
});

/// Branch id to the name a reader recognises, for the locked chip and the selector's label. The
/// roster answers for everybody who holds a named grant; the map fills in the rest for a
/// wildcard holder (B1).
final regionNamesProvider = Provider<Map<int, String>>((ref) {
  final out = <int, String>{};
  for (final r in ref.watch(regionMapProvider).valueOrNull ?? const <RegionRef>[]) {
    out[r.id] = r.code;
  }
  for (final g in ref.watch(currentUserProvider)?.regions ?? const <RegionGrant>[]) {
    out[g.id] = g.code;
  }
  return out;
});

/// The ONE filter the branch selector owns and renders: `regionId:in:...`.
///
/// The selector writes this shape and reads no other, so this is exactly the filter a list must
/// hide from the chip row and keep through "Clear all". Every other regionId filter — the
/// `regionId:eq:7` the ordinary "Add filter" dialog composes, because REFERENCE's first operator
/// is `eq`, and neq/isEmpty/isNotEmpty — is an ordinary row filter and has to be shown, counted
/// and cleared like one, or it narrows the list with no control anywhere on the page (B1).
bool isRegionSelectorFilter(TableFilter f) =>
    f.field == regionFilterField && f.operator == 'in';

/// The branch ids named by a `regionId:in:3,7` filter, applied or locked. Anything else — the
/// `regionId:isEmpty:` chip a person with no grant at all is sent — names no branch (B1).
List<int> regionIdsIn(List<TableFilter> filters) {
  for (final f in filters) {
    if (!isRegionSelectorFilter(f)) continue;
    return f.values.map(int.tryParse).whereType<int>().toList();
  }
  return const [];
}

/// Which branches a picker opened from a list should ask about, in the order the server's own
/// refusal implies: what this list has been narrowed to, else what it is locked to, else every
/// branch this person works in. Empty means "name no branch", which is only ever right for a
/// wildcard holder — and for them the server answers across the company (B1, R8).
List<int> pickerRegionsFor(
  CurrentUser? user, {
  List<TableFilter> applied = const [],
  List<TableFilter> locked = const [],
}) {
  final narrowed = regionIdsIn(applied);
  if (narrowed.isNotEmpty) return narrowed;
  final pinned = regionIdsIn(locked);
  if (pinned.isNotEmpty) return pinned;
  if (user == null || user.allRegions || user.isCustomer) return const [];
  return user.workingSet.toList();
}
