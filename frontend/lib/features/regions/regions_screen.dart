import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import 'region_form_dialog.dart';

/// A region (B1): every customer, and everything raised against it, belongs to exactly one.
class Region {
  final int id;
  final String code;
  final String name;
  final bool active;
  final bool defaultRegion;
  final int? version;
  final DateTime? createdAt;

  const Region({
    required this.id,
    required this.code,
    required this.name,
    this.active = true,
    this.defaultRegion = false,
    this.version,
    this.createdAt,
  });

  factory Region.fromJson(Map<String, dynamic> json) => Region(
        id: (json['id'] as num).toInt(),
        code: json['code'] as String? ?? '',
        name: json['name'] as String? ?? json['code'] as String? ?? '',
        active: json['active'] as bool? ?? true,
        defaultRegion: json['defaultRegion'] as bool? ?? false,
        version: (json['version'] as num?)?.toInt(),
        createdAt:
            json['createdAt'] == null ? null : DateTime.tryParse(json['createdAt'] as String),
      );
}

/// Someone holding a grant in a region, as the region's Grants tab lists them.
class RegionGrantHolder {
  final int userId;
  final String username;
  final String? fullName;
  final String? roleName;
  final String level;
  final String scope;

  const RegionGrantHolder({
    required this.userId,
    required this.username,
    this.fullName,
    this.roleName,
    required this.level,
    required this.scope,
  });

  factory RegionGrantHolder.fromJson(Map<String, dynamic> json) => RegionGrantHolder(
        userId: (json['userId'] as num).toInt(),
        username: json['username'] as String? ?? '',
        fullName: json['fullName'] as String?,
        roleName: json['roleName'] as String?,
        level: json['level'] as String? ?? 'READ',
        scope: json['scope'] as String? ?? 'ALL',
      );
}

// Regions are configuration: they answer live whatever the date viewed (D9).
final regionDetailProvider = FutureProvider.autoDispose.family<Region, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/regions/$id');
  return Region.fromJson((res.data as Map).cast<String, dynamic>());
});

/// Every region, for pickers such as the grants editor's.
final regionOptionsProvider = FutureProvider.autoDispose<List<Region>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/regions', queryParameters: {'size': 50, 'sort': 'code,asc'});
  return ((res.data as Map)['content'] as List)
      .cast<Map>()
      .map((m) => Region.fromJson(m.cast<String, dynamic>()))
      .toList();
});

final regionGrantsProvider =
    FutureProvider.autoDispose.family<List<RegionGrantHolder>, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/regions/$id/grants');
  return (res.data as List)
      .cast<Map>()
      .map((m) => RegionGrantHolder.fromJson(m.cast<String, dynamic>()))
      .toList();
});

String accessLevelLabel(String? level) => switch (level) {
      'READ' => 'Read',
      'MANAGE' => 'Manage',
      'APPROVE' => 'Approve',
      null => '—',
      _ => humanizeEnum(level),
    };

String recordScopeLabel(String? scope) => switch (scope) {
      'ALL' => 'All records',
      'BOOK' => 'Own book',
      null => '—',
      _ => humanizeEnum(scope),
    };

/// "SOUTH — South", or whichever of the two is known.
String regionLabel(String? code, String? name) {
  if (code == null) return name ?? '—';
  return name == null || name == code ? code : '$code — $name';
}

/// The Region column of a record list (§7.5). Shown only to someone who holds more than one
/// region: with one, every row would say the same thing.
TableColumnSpec<T> regionColumn<T>(String? Function(T row) code, String? Function(T row) name) =>
    TableColumnSpec<T>(
      label: 'Region',
      sortKey: 'regionName',
      maxWidth: 140,
      cell: (context, row) => Tooltip(
        message: name(row) ?? '',
        child: Text(code(row) ?? name(row) ?? '—', maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
    );

/// After a change to regions the signed-in user's own list of them — which the region selector
/// and every create flow read — is out of date: the creator of a region is granted it, say.
Future<void> regionsChanged(WidgetRef ref, {int? id}) async {
  ref.invalidate(tablePageProvider);
  ref.invalidate(regionOptionsProvider);
  if (id != null) ref.invalidate(regionDetailProvider(id));
  await ref.read(authControllerProvider.notifier).refreshUser();
}

Future<void> makeRegionDefault(BuildContext context, WidgetRef ref, Region r) async {
  final messenger = ScaffoldMessenger.of(context);
  final ok = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text('Make ${r.code} the default region?'),
      content: const Text(
          'Customers created without a region choice, and new staff given default access, go '
          'here from now on. Nothing already in another region moves.'),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Cancel')),
        FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Make default')),
      ],
    ),
  );
  if (ok != true) return;
  try {
    await ref.read(dioProvider).post('/api/regions/${r.id}/make-default');
    messenger.showSnackBar(SnackBar(content: Text('${r.code} is now the default region')));
    await regionsChanged(ref, id: r.id);
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
  }
}

class RegionsScreen extends ConsumerWidget {
  final TableQuery query;
  const RegionsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    // A past date is read only (D2).
    final historical = ref.watch(isHistoricalProvider);
    final canManage = !historical && (user?.has(Privileges.regionManage) ?? false);

    return Scaffold(
      body: DataTableScaffold<Region>(
        entity: 'regions',
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New region'),
              onPressed: () => openRegionForm(context, ref),
            ),
        ],
        path: '/api/regions',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/regions').push(q),
        parse: Region.fromJson,
        idOf: (r) => r.id,
        selectable: false,
        emptyMessage: 'No regions match this filter',
        onRowTap: (context, r) => context.go('/regions/${r.id}'),
        columns: [
          TableColumnSpec(
            label: 'Code',
            sortKey: 'code',
            cell: (context, r) => Wrap(
              spacing: 6,
              runSpacing: 2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text(r.code, style: const TextStyle(fontWeight: FontWeight.w600)),
                if (r.defaultRegion) const RegionDefaultBadge(),
              ],
            ),
          ),
          TableColumnSpec(
            label: 'Name',
            sortKey: 'name',
            maxWidth: 280,
            cell: (context, r) => Tooltip(
              message: r.name,
              child: Text(r.name, maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
              label: 'Active',
              sortKey: 'active',
              cell: (context, r) => Text(r.active ? 'Yes' : 'No')),
          TableColumnSpec(
              label: 'Created',
              sortKey: 'createdAt',
              cell: (context, r) => Text(formatDateTime(r.createdAt))),
        ],
        rowActions: (context, r) => [
          IconButton(
            tooltip: 'Open',
            icon: const Icon(Icons.open_in_new, size: 18),
            onPressed: () => context.go('/regions/${r.id}'),
          ),
          if (canManage)
            IconButton(
              tooltip: 'Edit',
              icon: const Icon(Icons.edit_outlined, size: 18),
              onPressed: () => openRegionForm(context, ref, existing: r),
            ),
          if (canManage && !r.defaultRegion && r.active)
            IconButton(
              tooltip: 'Make default',
              icon: const Icon(Icons.star_outline, size: 18),
              onPressed: () => makeRegionDefault(context, ref, r),
            ),
        ],
      ),
    );
  }
}

class RegionDefaultBadge extends StatelessWidget {
  const RegionDefaultBadge({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: scheme.secondaryContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text('Default',
          style: TextStyle(
              fontSize: 11, color: scheme.onSecondaryContainer, fontWeight: FontWeight.w500)),
    );
  }
}
