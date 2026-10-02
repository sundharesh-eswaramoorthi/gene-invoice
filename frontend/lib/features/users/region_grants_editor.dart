import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/view_scope.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../regions/regions_screen.dart';

const accessLevels = ['READ', 'MANAGE', 'APPROVE'];
const recordScopes = ['ALL', 'BOOK'];

int _rank(String? level) => accessLevels.indexOf(level ?? '');

/// One user's access in one region: a level (READ < MANAGE < APPROVE) and which records it
/// covers, all of them or only the user's own book (B1).
@immutable
class RegionGrant {
  final int regionId;
  final String? regionCode;
  final String? regionName;
  final String level;
  final String scope;

  const RegionGrant({
    required this.regionId,
    this.regionCode,
    this.regionName,
    required this.level,
    required this.scope,
  });

  factory RegionGrant.fromJson(Map<String, dynamic> json) => RegionGrant(
        regionId: (json['regionId'] as num).toInt(),
        regionCode: json['regionCode'] as String?,
        regionName: json['regionName'] as String?,
        level: json['level'] as String? ?? 'READ',
        scope: json['scope'] as String? ?? 'ALL',
      );

  RegionGrant copyWith({String? level, String? scope}) => RegionGrant(
        regionId: regionId,
        regionCode: regionCode,
        regionName: regionName,
        level: level ?? this.level,
        scope: scope ?? this.scope,
      );

  Map<String, dynamic> toJson() => {'regionId': regionId, 'level': level, 'scope': scope};

  @override
  bool operator ==(Object other) =>
      other is RegionGrant &&
      other.regionId == regionId &&
      other.level == level &&
      other.scope == scope;

  @override
  int get hashCode => Object.hash(regionId, level, scope);
}

// Grants are configuration and answer live whatever the date viewed (D9).
final userRegionGrantsProvider =
    FutureProvider.autoDispose.family<List<RegionGrant>, int>((ref, userId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/users/$userId/region-grants');
  return (res.data as List)
      .cast<Map>()
      .map((m) => RegionGrant.fromJson(m.cast<String, dynamic>()))
      .toList();
});

/// The highest level a role's own privileges call for in a region: what a new user of that role
/// is given in the default region to start with.
String levelRoleNeeds(Iterable<String> rolePrivileges) {
  var need = 'READ';
  for (final p in rolePrivileges) {
    final level = Privileges.levelFor(p);
    if (level != null && _rank(level) > _rank(need)) need = level;
  }
  return need;
}

/// The grants a new user starts with: the default region, at the level the role needs, bounded
/// by what the editor holds there. Empty when the editor cannot grant the default region.
List<RegionGrant> initialGrantsFor(
    CurrentUser editor, Iterable<String> rolePrivileges, List<Region> regions) {
  Region? home;
  for (final r in regions) {
    if (r.defaultRegion && r.active) home = r;
  }
  if (home == null) {
    for (final r in editor.regions) {
      if (r.defaultRegion && r.active) {
        home = Region(id: r.id, code: r.code, name: r.name, defaultRegion: true);
      }
    }
  }
  if (home == null) return const [];
  final bounds = GrantBounds(editor);
  final mine = bounds.editorGrantIn(home.id);
  if (!bounds.unbounded && mine == null) return const [];
  var level = levelRoleNeeds(rolePrivileges);
  if (!bounds.unbounded && _rank(level) > _rank(mine!.level)) level = mine.level!;
  // APPROVE is given only by someone who approves changes there themselves (§4.6).
  if (!bounds.offersLevel(home.id, level)) level = 'MANAGE';
  final scope = bounds.unbounded || mine!.scope == 'ALL' ? 'ALL' : 'BOOK';
  return [
    RegionGrant(
        regionId: home.id, regionCode: home.code, regionName: home.name, level: level, scope: scope)
  ];
}

/// What the person editing may give: nobody grants more than they hold (§4.6). A row in a region
/// is theirs to change only when their own grant there is at least as high as the row's old and
/// new level, and covers all records whenever either row does. The server's 403 stays the
/// authority; this only keeps the form from offering what would be refused.
class GrantBounds {
  final CurrentUser editor;
  const GrantBounds(this.editor);

  /// Before the server reports regions on `/me` there is nothing to bound by. An editor the server
  /// reports with no regions at all holds nothing, so may give nothing.
  bool get unbounded => !editor.regionsKnown;

  RegionAccess? editorGrantIn(int regionId) {
    for (final r in editor.regions) {
      if (r.id == regionId) return r;
    }
    return null;
  }

  bool covers(RegionGrant row) {
    if (unbounded) return true;
    final mine = editorGrantIn(row.regionId);
    if (mine == null) return false;
    return _rank(mine.level) >= _rank(row.level) && (row.scope != 'ALL' || mine.scope == 'ALL');
  }

  /// Whether [level] may be given in [regionId]. APPROVE also needs CHANGE_APPROVE there (§4.6).
  bool offersLevel(int regionId, String level) {
    if (unbounded) return true;
    final mine = editorGrantIn(regionId);
    if (mine == null || _rank(mine.level) < _rank(level)) return false;
    return level != 'APPROVE' || editor.hasIn(Privileges.changeApprove, regionId);
  }

  bool offersAllRecords(int regionId) => unbounded || editorGrantIn(regionId)?.scope == 'ALL';
}

/// The rows of a user's region access: a region, a level and which records (§7.5). Rows the
/// editor could not have granted are shown but locked, and go back to the server unchanged.
class RegionGrantsEditor extends ConsumerWidget {
  /// The rows as they stand in the form.
  final List<RegionGrant> value;

  /// The rows as the server has them, which decide what is locked. Empty for a new user.
  final List<RegionGrant> original;
  final ValueChanged<List<RegionGrant>> onChanged;

  /// The privileges of the user's role, when known: they decide whether the record scope means
  /// anything, and whether a book-only grant would show anything at all.
  final Set<String>? rolePrivileges;

  final bool readOnly;
  final String? readOnlyReason;

  const RegionGrantsEditor({
    super.key,
    required this.value,
    required this.onChanged,
    this.original = const [],
    this.rolePrivileges,
    this.readOnly = false,
    this.readOnlyReason,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final editor = ref.watch(currentUserProvider);
    if (editor == null) return const SizedBox.shrink();
    final bounds = GrantBounds(editor);
    final regions = ref.watch(regionOptionsProvider);
    final regionList = regions.valueOrNull ?? const <Region>[];
    final theme = Theme.of(context);

    bool locked(RegionGrant row) {
      if (readOnly) return true;
      for (final o in original) {
        if (o.regionId == row.regionId) return !bounds.covers(o);
      }
      return false;
    }

    final addable = [
      for (final r in regionList)
        if (r.active &&
            !value.any((g) => g.regionId == r.id) &&
            (bounds.unbounded || bounds.editorGrantIn(r.id) != null))
          r
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (readOnly && readOnlyReason != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(readOnlyReason!,
                style: theme.textTheme.bodySmall?.copyWith(fontStyle: FontStyle.italic)),
          ),
        if (value.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text('No access in any region: this user sees no customers or their records.',
                style: theme.textTheme.bodySmall),
          ),
        for (final row in value)
          _GrantRow(
            key: ValueKey('grant-${row.regionId}'),
            row: row,
            region: _regionOf(row, regionList),
            originalLevel: _originalOf(row)?.level,
            locked: locked(row),
            showLockNote: !readOnly && locked(row),
            bounds: bounds,
            rolePrivileges: rolePrivileges,
            onChanged: (next) =>
                onChanged([for (final g in value) g.regionId == row.regionId ? next : g]),
            onRemove: () => onChanged([for (final g in value) if (g.regionId != row.regionId) g]),
          ),
        if (!readOnly && addable.isNotEmpty)
          Align(
            alignment: Alignment.centerLeft,
            child: PopupMenuButton<Region>(
              key: const ValueKey('grant-add'),
              tooltip: 'Give access in another region',
              onSelected: (r) {
                final scope = bounds.offersAllRecords(r.id) ? 'ALL' : 'BOOK';
                onChanged([
                  ...value,
                  RegionGrant(
                      regionId: r.id,
                      regionCode: r.code,
                      regionName: r.name,
                      level: 'READ',
                      scope: scope),
                ]);
              },
              itemBuilder: (_) => [
                for (final r in addable)
                  PopupMenuItem(value: r, child: Text('${r.code} — ${r.name}')),
              ],
              child: const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.add, size: 18),
                    SizedBox(width: 6),
                    Text('Add region'),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }

  RegionGrant? _originalOf(RegionGrant row) {
    for (final o in original) {
      if (o.regionId == row.regionId) return o;
    }
    return null;
  }

  static Region? _regionOf(RegionGrant row, List<Region> regions) {
    for (final r in regions) {
      if (r.id == row.regionId) return r;
    }
    return null;
  }
}

class _GrantRow extends StatelessWidget {
  final RegionGrant row;
  final Region? region;

  /// The level the server has for this row, which may always be kept.
  final String? originalLevel;
  final bool locked;
  final bool showLockNote;
  final GrantBounds bounds;
  final Set<String>? rolePrivileges;
  final ValueChanged<RegionGrant> onChanged;
  final VoidCallback onRemove;

  const _GrantRow({
    super.key,
    required this.row,
    required this.region,
    required this.originalLevel,
    required this.locked,
    required this.showLockNote,
    required this.bounds,
    required this.rolePrivileges,
    required this.onChanged,
    required this.onRemove,
  });

  /// Without SCOPE_OVERRIDE the role sees only its own book whatever the grant says, so the choice
  /// means nothing (§1.3 RecordScope).
  bool get _scopeSetByRole =>
      rolePrivileges != null && !rolePrivileges!.contains(Privileges.scopeOverride);

  bool get _holdsNoBook =>
      rolePrivileges != null &&
      !rolePrivileges!.any({
        Privileges.pocAssignableSales,
        Privileges.pocAssignableSuccess,
        Privileges.pocAssignableCollection,
      }.contains);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final code = region?.code ?? row.regionCode ?? 'Region #${row.regionId}';
    final name = region?.name ?? row.regionName;
    final inactive = region != null && !region!.active;
    final note = theme.textTheme.bodySmall;

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text.rich(TextSpan(children: [
                    TextSpan(text: code, style: const TextStyle(fontWeight: FontWeight.w600)),
                    if (name != null && name != code) TextSpan(text: ' — $name'),
                    if (inactive) const TextSpan(text: ' (inactive)'),
                  ])),
                ),
                if (!locked)
                  IconButton(
                    key: ValueKey('grant-remove-${row.regionId}'),
                    tooltip: 'Remove access in $code',
                    icon: const Icon(Icons.close, size: 18),
                    onPressed: onRemove,
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SegmentedButton<String>(
                  key: ValueKey('grant-level-${row.regionId}'),
                  showSelectedIcon: false,
                  style: const ButtonStyle(visualDensity: VisualDensity.compact),
                  segments: [
                    for (final l in accessLevels)
                      ButtonSegment(
                        value: l,
                        label: Text(accessLevelLabel(l)),
                        enabled: !locked &&
                            (l == originalLevel || bounds.offersLevel(row.regionId, l)),
                      ),
                  ],
                  selected: {row.level},
                  onSelectionChanged:
                      locked ? null : (s) => onChanged(row.copyWith(level: s.first)),
                ),
                if (_scopeSetByRole)
                  Text('Book (set by role)', key: ValueKey('grant-book-by-role-${row.regionId}'),
                      style: note),
                SegmentedButton<String>(
                  key: ValueKey('grant-scope-${row.regionId}'),
                  showSelectedIcon: false,
                  style: const ButtonStyle(visualDensity: VisualDensity.compact),
                  segments: [
                    ButtonSegment(
                      value: 'ALL',
                      label: Text(recordScopeLabel('ALL')),
                      enabled: !locked && !_scopeSetByRole && bounds.offersAllRecords(row.regionId),
                    ),
                    ButtonSegment(
                      value: 'BOOK',
                      label: Text(recordScopeLabel('BOOK')),
                      enabled: !locked && !_scopeSetByRole,
                    ),
                  ],
                  selected: {row.scope},
                  onSelectionChanged: locked || _scopeSetByRole
                      ? null
                      : (s) => onChanged(row.copyWith(scope: s.first)),
                ),
              ],
            ),
            if (row.scope == 'BOOK' && !_scopeSetByRole && _holdsNoBook)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  'This role holds no POC seats, so its own book in $code is empty.',
                  style: note?.copyWith(color: theme.colorScheme.error),
                ),
              ),
            if (showLockNote)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text('Only someone with this access can change it',
                    style: note?.copyWith(fontStyle: FontStyle.italic)),
              ),
          ],
        ),
      ),
    );
  }
}

/// The Regions tab of a user's page: their access per region, changed with one PUT (§6.3).
class UserRegionGrantsPanel extends ConsumerStatefulWidget {
  final int userId;
  final Set<String>? rolePrivileges;
  const UserRegionGrantsPanel({super.key, required this.userId, this.rolePrivileges});

  @override
  ConsumerState<UserRegionGrantsPanel> createState() => _UserRegionGrantsPanelState();
}

class _UserRegionGrantsPanelState extends ConsumerState<UserRegionGrantsPanel> {
  List<RegionGrant>? _loaded;
  List<RegionGrant> _draft = const [];
  bool _saving = false;
  String? _error;

  bool get _dirty => _loaded != null && !listEquals(_loaded, _draft);

  void _seed(List<RegionGrant> grants) {
    if (identical(_loaded, grants)) return;
    _loaded = grants;
    _draft = List.of(grants);
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(dioProvider).put('/api/users/${widget.userId}/region-grants',
          data: {'grants': _draft.map((g) => g.toJson()).toList()});
      ref.invalidate(userRegionGrantsProvider(widget.userId));
      ref.invalidate(regionGrantsProvider);
      ref.invalidate(auditHistoryProvider);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Region access saved')));
      }
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(userRegionGrantsProvider(widget.userId));
    final viewer = ref.watch(currentUserProvider);
    final self = viewer?.id == widget.userId;
    // A past date is read only (D2).
    final canEdit = !ref.watch(isHistoricalProvider) &&
        (viewer?.has(Privileges.userManage) ?? false) &&
        !self;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Text(apiErrorMessage(e), textAlign: TextAlign.center),
        ),
      ),
      data: (grants) {
        _seed(grants);
        return SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                RegionGrantsEditor(
                  value: _draft,
                  original: grants,
                  rolePrivileges: widget.rolePrivileges,
                  readOnly: !canEdit,
                  readOnlyReason: self ? 'You cannot change your own region access' : null,
                  onChanged: (next) => setState(() {
                    _draft = next;
                    _error = null;
                  }),
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(_error!,
                        style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  ),
                if (canEdit)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Row(
                      children: [
                        FilledButton.icon(
                          icon: const Icon(Icons.save_outlined),
                          label: const Text('Save access'),
                          onPressed: !_dirty || _saving ? null : _save,
                        ),
                        if (_dirty) ...[
                          const SizedBox(width: 8),
                          TextButton(
                            onPressed: _saving
                                ? null
                                : () => setState(() {
                                      _draft = List.of(grants);
                                      _error = null;
                                    }),
                            child: const Text('Undo changes'),
                          ),
                        ],
                      ],
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}
