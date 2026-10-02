import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../features/auth/auth_controller.dart';

/// The wire form of an as-of date: the calendar date the user picked, as it reads to them. Never
/// converted to UTC first, which would move an evening pick in a western timezone a day later (G21).
String asOfWire(DateTime date) => DateFormat('yyyy-MM-dd').format(date);

/// "Which regions am I asking about" and "as of when" — the two things every set-returning request
/// carries (B1, B3). Held for the session only: not persisted and not in the URL.
@immutable
class ViewScope {
  /// Null means all of my regions.
  final Set<int>? regionIds;

  /// A local calendar date; null means live.
  final DateTime? asOf;

  const ViewScope({this.regionIds, this.asOf});

  static const live = ViewScope();

  bool get isHistorical => asOf != null;

  /// Only what is set, so a request made with nothing chosen is exactly the request it was before (G11).
  /// Region ids are one canonical string rather than a list, so equal scopes are equal keys (G1).
  Map<String, String> get apiParams => {
        if (regionIds != null && regionIds!.isNotEmpty)
          'regionIds': (regionIds!.toList()..sort()).join(','),
        if (asOf != null) 'asOf': asOfWire(asOf!),
      };

  ViewScope copyWith({Set<int>? regionIds, DateTime? asOf, bool allRegions = false, bool clearAsOf = false}) =>
      ViewScope(
        regionIds: allRegions ? null : (regionIds ?? this.regionIds),
        asOf: clearAsOf ? null : (asOf ?? this.asOf),
      );

  String get _canonical => apiParams.entries.map((e) => '${e.key}=${e.value}').join('&');

  @override
  bool operator ==(Object other) => other is ViewScope && other._canonical == _canonical;

  @override
  int get hashCode => _canonical.hashCode;

  @override
  String toString() => 'ViewScope($_canonical)';
}

class ViewScopeController extends StateNotifier<ViewScope> {
  ViewScopeController([super.initial = ViewScope.live]);

  /// Choosing no region at all means every region I hold, never "none".
  void setRegions(Set<int>? regionIds) => state = state.copyWith(
        regionIds: regionIds == null ? null : Set.unmodifiable(regionIds),
        allRegions: regionIds == null || regionIds.isEmpty,
      );

  void setAsOf(DateTime? date) => state = date == null
      ? state.copyWith(clearAsOf: true)
      : state.copyWith(asOf: DateTime(date.year, date.month, date.day));

  void clear() => state = ViewScope.live;
}

final viewScopeProvider = StateNotifierProvider<ViewScopeController, ViewScope>((ref) {
  final controller = ViewScopeController();
  // Someone else signing in at this browser starts from today and all of their own regions.
  ref.listen<int?>(currentUserProvider.select((u) => u?.id), (previous, next) {
    if (previous != next) controller.clear();
  });
  return controller;
});

/// Viewing a past date: every mutating control is off, and the server refuses writes anyway (D2).
final isHistoricalProvider = Provider<bool>((ref) => ref.watch(viewScopeProvider).asOf != null);

/// A request's `extra` for a read that stays live whatever the date, on an endpoint that would
/// otherwise answer as of it: the regions still go, the date does not (D9).
const Map<String, Object?> regionsOnlyExtra = {'viewScope': 'regionsOnly'};

/// Adds the scope's parameters to every request — lists, by-id reads, bulk and export POSTs alike
/// (G2) — without replacing one the caller set itself, and without the date on a request marked
/// [regionsOnlyExtra]. Exported so tests can put it ahead of the fake backend's routing (G23).
InterceptorsWrapper viewScopeInterceptor(ViewScope Function() current) =>
    InterceptorsWrapper(onRequest: (options, handler) {
      final regionsOnly = options.extra['viewScope'] == regionsOnlyExtra['viewScope'];
      current().apiParams.forEach((k, v) {
        if (regionsOnly && k == 'asOf') return;
        options.queryParameters.putIfAbsent(k, () => v);
      });
      handler.next(options);
    });
