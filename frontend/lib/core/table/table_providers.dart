import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/auth_controller.dart';
import '../api/api_client.dart';
import 'table_models.dart';

/// Identifies one list request. Value equality keeps a single fetch per distinct view,
/// so changing page, size, sort or filters triggers exactly one backend call (AC-D1).
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
  // Each user gets their own schema (a customer sees no POC columns), so it is fetched afresh
  // whenever a different user signs in rather than kept from the previous one.
  ref.watch(currentUserProvider.select((u) => u?.id));
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

/// Tiles come from their own aggregate over the same filter, never from the loaded page (AC-E1).
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

/// Remembers the page size the user picked, per table, across sessions (D.1).
///
/// Held in memory as well as in preferences so the router can read it synchronously while
/// building a route — the size has to be known before the first request goes out.
class PageSizeStore extends StateNotifier<Map<String, int>> {
  PageSizeStore() : super(const {});

  static const _prefix = 'table.pageSize.';

  /// Loads every remembered size once at startup.
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
      // A missing preference store must never keep the tables from rendering.
    }
  }

  int? sizeFor(String entity) => state[entity];

  Future<void> write(String entity, int size) async {
    state = {...state, entity: size};
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('$_prefix$entity', size);
    } catch (_) {
      // Keep the in-memory choice even when it cannot be persisted.
    }
  }
}

final pageSizeStoreProvider =
    StateNotifierProvider<PageSizeStore, Map<String, int>>((ref) => PageSizeStore());
