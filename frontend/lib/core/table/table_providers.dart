import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/auth_controller.dart';
import '../api/api_client.dart';
import 'table_models.dart';

@immutable
class TableRequest {
  final String entity;
  final String path;
  final TableQuery query;
  final Map<String, dynamic> extra;

  const TableRequest({
    required this.entity,
    required this.path,
    required this.query,
    this.extra = const {},
  });

  Map<String, dynamic> get apiParams => {...query.toApiParams(), ...extra};

  /// The same question without the paging: the tiles describe the SET the list is showing.
  ///
  /// It builds a TableQuery from scratch and so drops every field it does not name. asOf has to be
  /// copied here by hand, or the tiles go live while the rows underneath them are historical —
  /// the single most misleading thing this feature can produce, because both halves look right
  /// and only their combination is a lie (B3).
  TableRequest get forSummary => TableRequest(
        entity: entity,
        path: path,
        query: TableQuery(filters: query.filters, asOf: query.asOf),
        extra: extra,
      );

  @override
  bool operator ==(Object other) =>
      other is TableRequest &&
      other.entity == entity &&
      other.path == path &&
      other.query == query &&
      mapEquals(other.extra, extra);

  @override
  int get hashCode => Object.hash(entity, path, query, Object.hashAll(extra.entries.map((e) => '${e.key}=${e.value}')));
}

final tableSchemaProvider = FutureProvider.family<TableSchema, String>((ref, entity) async {
  final userId = ref.watch(currentUserProvider.select((u) => u?.id));
  // Signing out changes that to nobody. There is no schema to fetch then, and asking for one
  // without a token only produces a 401 in the console (D-70); the app is leaving the page anyway.
  if (userId == null) return Completer<TableSchema>().future;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/table-schemas/$entity');
  return TableSchema.fromJson(res.data as Map<String, dynamic>);
});

final tablePageProvider =
    FutureProvider.autoDispose.family<PagedResult<Map<String, dynamic>>, TableRequest>((ref, req) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get(req.path, queryParameters: req.apiParams);
  return PagedResult.fromJson(res.data as Map<String, dynamic>);
});

final tableSummaryProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, TableRequest>((ref, req) async {
  final dio = ref.watch(dioProvider);
  final params = Map<String, dynamic>.from(req.apiParams)
    ..remove('page')
    ..remove('size')
    ..remove('sort');
  final res = await dio.get('${req.path}/summary', queryParameters: params);
  return (res.data as Map).cast<String, dynamic>();
});

class PageSizeStore extends StateNotifier<Map<String, int>> {
  PageSizeStore() : super(const {});

  static const _prefix = 'table.pageSize.';

  Future<void> hydrate() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final loaded = <String, int>{};
      for (final key in prefs.getKeys()) {
        if (!key.startsWith(_prefix)) continue;
        final size = prefs.getInt(key);
        if (size != null) loaded[key.substring(_prefix.length)] = size;
      }
      state = loaded;
    } catch (_) {
    }
  }

  int? sizeFor(String entity) => state[entity];

  /// Forgets every remembered size. Called when a user signs out, so the next person at this
  /// browser gets their own defaults rather than inheriting the last one's (D-57).
  Future<void> clear() async {
    state = const {};
    try {
      final prefs = await SharedPreferences.getInstance();
      for (final key in prefs.getKeys().where((k) => k.startsWith(_prefix)).toList()) {
        await prefs.remove(key);
      }
    } catch (_) {
    }
  }

  Future<void> write(String entity, int size) async {
    state = {...state, entity: size};
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('$_prefix$entity', size);
    } catch (_) {
    }
  }
}

final pageSizeStoreProvider =
    StateNotifierProvider<PageSizeStore, Map<String, int>>((ref) => PageSizeStore());
