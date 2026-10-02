import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../api/api_client.dart';
import 'view_scope.dart';

/// The dates the server can answer "as of": nothing before history began, nothing later than
/// yesterday (today is simply live).
@immutable
class HistoryRange {
  /// Null when history has no lower bound.
  final DateTime? historyStartsOn;
  final DateTime? latestAsOf;
  final String timezone;

  const HistoryRange({this.historyStartsOn, this.latestAsOf, this.timezone = 'UTC'});

  factory HistoryRange.fromJson(Map<String, dynamic> json) => HistoryRange(
        historyStartsOn: _calendarDate(json['historyStartsOn']),
        latestAsOf: _calendarDate(json['latestAsOf']),
        timezone: json['timezone'] as String? ?? 'UTC',
      );

  static DateTime? _calendarDate(Object? raw) {
    final parsed = raw is String ? DateTime.tryParse(raw) : null;
    return parsed == null ? null : DateTime(parsed.year, parsed.month, parsed.day);
  }
}

/// Read only when the as-of picker opens, never while the shell builds (G13).
final historyRangeProvider = FutureProvider.autoDispose<HistoryRange>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/history/range');
  return HistoryRange.fromJson((res.data as Map).cast<String, dynamic>());
});

/// How a past date reads on screen: "30 Jun 2026".
String asOfLabel(DateTime date) => DateFormat('d MMM yyyy').format(date);

/// Just the date of the view scope, for reads that ignore regions — a record by id (I3) — so a
/// change of regions does not load the page again.
final viewAsOfProvider = Provider<DateTime?>((ref) => ref.watch(viewScopeProvider.select((s) => s.asOf)));

/// Just the regions of the view scope, as their one canonical string, for lists that answer live
/// whatever the date — notifications, the inbox, pickers (D9) — but still only for those regions.
final viewRegionsProvider =
    Provider<String?>((ref) => ref.watch(viewScopeProvider.select((s) => s.apiParams['regionIds'])));

enum AsOfApplied { live, historical }

/// A historical view got an answer that is not the history it asked for.
class AsOfMismatch implements Exception {
  final String message;
  const AsOfMismatch([this.message = "This screen can't show history"]);

  @override
  String toString() => message;
}

/// What the server says it answered for [asOf]: the date itself (history), or `live` (an endpoint
/// that always answers live). Anything else — above all no header, from a server that ignored the
/// parameter — throws, so a screen can never pass today's data off as history.
AsOfApplied checkAsOf(Response<dynamic> r, DateTime? asOf) {
  if (asOf == null) return AsOfApplied.live;
  final header = r.headers.value('x-view-as-of');
  if (header == asOfWire(asOf)) return AsOfApplied.historical;
  if (header == 'live') return AsOfApplied.live;
  throw const AsOfMismatch();
}
