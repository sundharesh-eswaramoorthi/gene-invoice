import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/auth_controller.dart';
import '../api/api_client.dart';
import '../table/table_models.dart';

/// What this installation can answer as of a date, published at GET /api/as-of rather than
/// documented: how far back the mirror goes, what the server calls today, what it does with a date
/// before the floor, and which tables have a mirror behind them (B3).
///
/// The client asks once. Without it a date picker has no bounds and would happily offer a date the
/// server will refuse — or, worse, a date before the floor whose values are the floor's, with
/// nothing on screen to say so.
@immutable
class AsOfCapability {
  /// The day the mirror started, or null while nothing has installed one.
  final DateTime? floor;

  /// The server's own UTC day. A browser an hour ahead of UTC must not offer "tomorrow".
  final DateTime? today;

  /// `seeded` — a pre-floor date is answered with a caveat — or `reject`, where it is a 400.
  final String preFloor;

  /// The table entity names that have a mirror, in the order the server offers them.
  final List<String> entities;

  const AsOfCapability({
    required this.floor,
    required this.today,
    required this.preFloor,
    required this.entities,
  });

  /// What an installation without the history feature looks like, and what is assumed before the
  /// answer arrives: no floor to bound a picker with and nothing that can be asked as of a date.
  /// Assuming the feature is ON before the server has said so would offer a control that 400s.
  static const unknown =
      AsOfCapability(floor: null, today: null, preFloor: 'seeded', entities: []);

  static const policyReject = 'reject';

  bool get isAvailable => entities.isNotEmpty;

  /// True when the operator would rather have no number than a caveated one, which is what bounds
  /// the picker at the floor instead of a year before it.
  bool get refusesPreFloor => preFloor == policyReject;

  bool supports(String entity) =>
      entities.any((e) => e.toLowerCase() == entity.toLowerCase());

  factory AsOfCapability.fromJson(Map<String, dynamic> json) => AsOfCapability(
        floor: parseAsOfDay(json['floor'] as String?),
        today: parseAsOfDay(json['today'] as String?),
        preFloor: json['preFloor'] as String? ?? 'seeded',
        entities: ((json['entities'] as List?) ?? const []).map((e) => '$e').toList(),
      );

  /// The earliest day worth offering. Before the floor the answer is still served — existence is
  /// exact and values are the floor's — so a year of runway is offered and the banner explains
  /// itself. Under `reject` a pre-floor date is a 400, so the picker stops at the floor.
  DateTime get earliestOffered {
    final f = floor;
    if (f == null) return DateTime.utc(2000);
    return refusesPreFloor ? f : f.subtract(const Duration(days: 365));
  }

  /// The latest day worth offering. A date at or after today is served live by the server, so
  /// offering tomorrow would only produce a page that says "as of" and shows now.
  DateTime latestOffered(DateTime fallbackToday) => today ?? fallbackToday;
}

/// Fetched once per signed-in person and kept — it describes the installation, not a record.
///
/// Nobody signed in means no token, and asking anyway produces only a 401 in the console (D-70),
/// so the answer is "nothing is as-of capable" until somebody is.
final asOfCapabilityProvider = FutureProvider<AsOfCapability>((ref) async {
  final userId = ref.watch(currentUserProvider.select((u) => u?.id));
  if (userId == null) return AsOfCapability.unknown;
  final dio = ref.watch(dioProvider);
  try {
    final res = await dio.get('/api/as-of');
    return AsOfCapability.fromJson((res.data as Map).cast<String, dynamic>());
  } catch (_) {
    // An installation built without the history feature, or a server too old to know the
    // endpoint. Offering no control is the honest answer; throwing would break every list that
    // watches this for a date picker it may not even need.
    return AsOfCapability.unknown;
  }
});

/// The date the DASHBOARD is being read as of, or null for now.
///
/// A StateProvider and not part of a URL, exactly as `dashboardMonthsProvider` beside it is: the
/// dashboard is one screen with no query of its own. A LIST keeps its date in the URL instead, so
/// "the ageing as of month-end" survives being shared — that is TableQuery.asOf, not this (B3).
final dashboardAsOfProvider = StateProvider<DateTime?>((ref) => null);
