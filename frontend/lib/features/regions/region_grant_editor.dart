import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/region/region_providers.dart';
import '../../shared/models/auth_models.dart';
import '../auth/auth_controller.dart';

/// Who may work where: one person's branches and the level they hold in each.
///
/// PUT /api/users/{id}/regions REPLACES the whole set, so this dialog READS the set first — GET
/// /api/users/{id}/regions, the read side of the same endpoint, answering the same shape from the
/// same builder — and ticks what the person holds today. It used to have no read side at all and
/// said so ("their current branches cannot be read back from here, so compose the whole set"),
/// which made every save a blind rewrite: an administrator who opened it to add one branch
/// stripped every other grant, the wildcard included, and nothing on screen could tell them (B1).
///
/// The replace is still a replace. What changed is that the set being replaced is now visible, so
/// the dialog states what a save would TAKE AWAY, and refuses to save a set it cannot read (B1).
Future<bool> showRegionGrantEditor(
  BuildContext context, {
  required int userId,
  required String username,
}) async {
  final saved = await showDialog<bool>(
    context: context,
    builder: (_) => _RegionGrantEditor(userId: userId, username: username),
  );
  return saved == true;
}

/// One branch of the set being composed, and EVERY level held in it. A null regionId is the
/// wildcard: every branch, including ones opened later, which is why it is never expanded into a
/// list (B1).
///
/// A set and not a single right, because the server models a grant as a (regionId, right) PAIR
/// and the ladder is not a total order: MANAGE does not cover APPROVE and APPROVE does not cover
/// MANAGE. A regional manager who both works and signs off in their branch holds two rows there,
/// and the migration gives every administrator two wildcard rows. One right per branch could
/// neither compose that state nor preserve it across a save that REPLACES the whole set (B1).
class _GrantLine {
  final int? regionId;
  final Set<String> rights;
  _GrantLine(this.regionId, this.rights);
}

class _RegionGrantEditor extends ConsumerStatefulWidget {
  final int userId;
  final String username;
  const _RegionGrantEditor({required this.userId, required this.username});

  @override
  ConsumerState<_RegionGrantEditor> createState() => _RegionGrantEditorState();
}

class _RegionGrantEditorState extends ConsumerState<_RegionGrantEditor> {
  /// The set being composed, or null while the roster has not been read. NOT an empty list: an
  /// empty list is "they hold nothing", and this dialog must never show that until the server has
  /// actually said it (B1).
  List<_GrantLine>? _lines;

  /// What they hold TODAY, kept beside the set being composed so the dialog can say what a save
  /// would take away rather than only what it would leave (B1).
  MyRegions? _held;

  String? _loadError;
  bool _saving = false;
  String? _error;
  MyRegions? _result;

  /// The wildcard levels this save ASKED for. The answer cannot carry them — MyRegionsDto's
  /// allRegions is a bool computed at VIEW, and its `regions` list deliberately names only the
  /// branches — so a result panel reading the answer alone would render a wildcard MANAGE and a
  /// wildcard MANAGE+APPROVE identically, and could not show a level this save dropped (B1).
  Set<String> _sentWildcardRights = const {};

  static const _rights = [regionRightView, regionRightManage, regionRightApprove];

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Read the roster, then tick it. A failure leaves _lines null, which is what keeps the editor
  /// off the screen: showing an unticked dialog after a failed read would invite a save that
  /// revokes everything, and the person saving would have been told nothing (B1).
  Future<void> _load() async {
    setState(() {
      _loadError = null;
      _lines = null;
      _held = null;
    });
    try {
      // refresh and not read: this dialog is ABOUT what the person holds right now, and a cached
      // roster from an earlier open — theirs may have been changed by somebody else since — would
      // tick a set that no longer exists and then save it over the real one (B1).
      final roster = await ref.refresh(userRegionGrantsProvider(widget.userId).future);
      if (!mounted) return;
      setState(() {
        _held = roster;
        _lines = [
          // The wildcard leads, and is seeded with NO level, because the roster reports it as a
          // bool: allRegions is `held.allRegions(VIEW)` on the server, so "they hold every branch"
          // is knowable and "at MANAGE and APPROVE" is not. Seeding a level here would be a guess
          // written into a save that replaces the real one (B1).
          if (roster.allRegions) _GrantLine(null, <String>{}),
          for (final g in roster.regions) _GrantLine(g.id, {...g.rights}),
        ];
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _loadError = apiErrorMessage(e));
    }
  }

  String _rightLabel(String right) => switch (right) {
        regionRightView => 'View',
        regionRightManage => 'Manage',
        regionRightApprove => 'Approve',
        _ => right,
      };

  String _rightTooltip(String right) => switch (right) {
        regionRightView => 'Read this branch',
        regionRightManage => 'Read and write in this branch',
        regionRightApprove => 'Sign off changes in this branch',
        _ => right,
      };

  void _toggle(int? regionId, bool on) {
    setState(() {
      _error = null;
      if (on) {
        _lines!.add(_GrantLine(regionId, {regionRightView}));
      } else {
        _lines!.removeWhere((l) => l.regionId == regionId);
      }
    });
  }

  /// Turning the last level off is the same statement as unticking the branch — "they hold
  /// nothing here" — so the line goes rather than becoming a branch with no level, which is not
  /// a state the server can be sent (B1).
  void _toggleRight(_GrantLine line, String right, bool on) {
    setState(() {
      _error = null;
      if (on) {
        line.rights.add(right);
      } else {
        line.rights.remove(right);
        if (line.rights.isEmpty) _lines!.remove(line);
      }
    });
  }

  _GrantLine? _lineFor(int? regionId) {
    for (final l in _lines ?? const <_GrantLine>[]) {
      if (l.regionId == regionId) return l;
    }
    return null;
  }

  /// They hold every branch and the wire cannot say at which levels, and nobody has said yet.
  ///
  /// The one state this dialog will not save. Everything else it can express exactly; this it
  /// cannot, and guessing would replace an administrator's wildcard MANAGE and APPROVE with
  /// whatever was guessed — the precise destruction the read-back exists to prevent (B1).
  bool get _wildcardLevelsUnstated {
    final wildcard = _lineFor(null);
    return wildcard != null && wildcard.rights.isEmpty;
  }

  /// The levels to offer on one line: the ladder, plus any level the SERVER sent that this build
  /// has never heard of. Without the second part a client one release behind the server would
  /// silently revoke the new level on every save, because the save replaces the whole set (B1).
  List<String> _rightsShownFor(_GrantLine line) =>
      [..._rights, ...line.rights.where((r) => !_rights.contains(r))];

  /// The levels to SEND for one line, ladder order first so the wire stays stable.
  List<String> _wireRightsFor(_GrantLine line) => [
        ..._rights.where(line.rights.contains),
        ...line.rights.where((r) => !_rights.contains(r)),
      ];

  /// What this save would TAKE AWAY, spelled out. The replace is still a replace, and the whole
  /// failure this dialog shipped with was that the loss was invisible — so it is named, from the
  /// roster that was read, not inferred from the ticks alone (B1).
  List<String> get _removals {
    final held = _held;
    if (held == null) return const [];
    final out = <String>[];
    if (held.allRegions && _lineFor(null) == null) out.add('Every branch');
    for (final g in held.regions) {
      final kept = _lineFor(g.id)?.rights ?? const <String>{};
      final lost = g.rights.where((r) => !kept.contains(r)).toList();
      if (lost.isEmpty) continue;
      out.add(kept.isEmpty ? g.label : '${g.label} (${lost.join(', ')})');
    }
    return out;
  }

  Future<void> _submit() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final res = await ref.read(dioProvider).put(
        '/api/users/${widget.userId}/regions',
        data: {
          'grants': [
            // One entry per (branch, level) pair, which is what the grant table stores: two
            // levels in one branch are two rows, not one higher row (B1).
            for (final l in _lines!)
              for (final right in _wireRightsFor(l))
                {'regionId': l.regionId, 'right': right},
          ],
        },
      );
      final answer = MyRegions.fromJson((res.data as Map).cast<String, dynamic>());
      final wildcard = _lineFor(null);
      _sentWildcardRights =
          wildcard == null ? const {} : _wireRightsFor(wildcard).toSet();
      // A person's reach is rebuilt on their next request, so nothing here has to be told about
      // it — except this app, if the person edited was the person signed in (B1).
      if (widget.userId == ref.read(currentUserProvider)?.id) {
        ref.invalidate(myRegionsProvider);
      }
      // The roster this dialog read is now stale, and reopening it must not tick the old set (B1).
      ref.invalidate(userRegionGrantsProvider(widget.userId));
      if (mounted) setState(() => _result = answer);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// The branches to list: the whole map, plus every branch this person HOLDS. The second half is
  /// load-bearing — a held branch the map does not carry, because the map read failed or the row
  /// is no longer in it, would otherwise be invisible and unticked, and the save would strip a
  /// grant nobody was shown (B1).
  List<RegionRef> _rowsFor(List<RegionRef> map) {
    final byId = <int, RegionRef>{for (final r in map) r.id: r};
    for (final g in _held?.regions ?? const <RegionGrant>[]) {
      byId.putIfAbsent(g.id, () => RegionRef.of(g));
    }
    final rows = byId.values.toList()..sort((a, b) => a.code.compareTo(b.code));
    return rows;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final result = _result;
    final lines = _lines;
    // The third check, at the control: the button that opens this dialog needs only the VIEW pair,
    // because reading a roster is not changing one. Saving needs USER_MANAGE and REGION_MANAGE —
    // the pair the endpoint itself asks for — so a reader sees the set and is offered no save,
    // rather than being offered one and collecting a 403 (B1).
    final canWrite = ref.watch(canEditRegionGrantsProvider);
    final removals = _removals;

    return AlertDialog(
      title: Text('Branches for @${widget.username}'),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (result != null)
                _Result(result: result, wildcardRights: _sentWildcardRights)
              else if (_loadError != null)
                // NOT an empty editor. A roster that could not be read is unknown, and an editor
                // that renders unknown as "they hold nothing" is one Save away from making it
                // true (B1).
                Text(
                  'What @${widget.username} holds today could not be read, so there is nothing '
                  'here to edit: saving would REPLACE their branches with a set composed from '
                  'nothing.\n\n$_loadError',
                  style: TextStyle(color: theme.colorScheme.error),
                )
              else if (lines == null)
                const Padding(
                  padding: EdgeInsets.all(24),
                  child: Center(child: CircularProgressIndicator()),
                )
              else ...[
                Text(
                  canWrite
                      ? 'Ticked is what @${widget.username} holds today. Saving REPLACES the whole '
                          'set with exactly what is ticked here, so anything you untick is taken '
                          'away — the wildcard included.'
                      : 'Ticked is what @${widget.username} holds today. Changing it needs the '
                          'Users and Regions manage privileges, so this is read-only for you.',
                  style: theme.textTheme.bodySmall,
                ),
                const SizedBox(height: 12),
                CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _lineFor(null) != null,
                  title: const Text('Every branch'),
                  subtitle: const Text('Including branches opened later'),
                  onChanged: canWrite ? (v) => _toggle(null, v ?? false) : null,
                ),
                if (_lineFor(null) != null) _rightChips(_lineFor(null)!, canWrite),
                if (_wildcardLevelsUnstated)
                  Padding(
                    padding: const EdgeInsets.only(left: 32, bottom: 8),
                    child: Text(
                      '@${widget.username} holds every branch, but the roster reports the '
                      'wildcard as a yes or no and cannot say at which levels — so nothing above '
                      'could be ticked for you. Say which levels they are to keep before saving.',
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.colorScheme.error),
                    ),
                  ),
                const Divider(),
                _branchList(canWrite),
                if (removals.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'This save takes away: ${removals.join(', ')}.',
                      style: TextStyle(color: theme.colorScheme.error),
                    ),
                  ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(_error!,
                        style: TextStyle(color: theme.colorScheme.error)),
                  ),
              ],
            ],
          ),
        ),
      ),
      actions: _actions(result: result, lines: lines, canWrite: canWrite),
    );
  }

  List<Widget> _actions({
    required MyRegions? result,
    required List<_GrantLine>? lines,
    required bool canWrite,
  }) {
    if (result != null) {
      return [
        FilledButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('Done'),
        ),
      ];
    }
    if (_loadError != null) {
      return [
        TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Close')),
        FilledButton(onPressed: _load, child: const Text('Try again')),
      ];
    }
    // Read-only, and while the roster is still arriving: no save is offered, so there is no way to
    // send a set nobody has seen (B1).
    if (lines == null || !canWrite) {
      return [
        TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Close')),
      ];
    }
    return [
      TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel')),
      FilledButton(
        onPressed: _saving || _wildcardLevelsUnstated ? null : _submit,
        child: _saving
            ? const SizedBox(
                width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
            : const Text('Replace branches'),
      ),
    ];
  }

  /// The named branches. A map that could not be read no longer empties the dialog: what this
  /// person HOLDS still lists, and only the ability to ADD a branch is lost (B1).
  Widget _branchList(bool canWrite) {
    final map = ref.watch(regionMapProvider);
    final rows = _rowsFor(map.valueOrNull ?? const []);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (map.isLoading)
          const Padding(
            padding: EdgeInsets.all(12),
            child: Center(child: CircularProgressIndicator()),
          ),
        if (rows.isEmpty && !map.isLoading && !map.hasError) const Text('No branches to grant.'),
        for (final r in rows) ...[
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            value: _lineFor(r.id) != null,
            title: Text(r.label),
            subtitle: r.active ? null : const Text('Retired'),
            onChanged: canWrite ? (v) => _toggle(r.id, v ?? false) : null,
          ),
          if (_lineFor(r.id) != null) _rightChips(_lineFor(r.id)!, canWrite),
        ],
        if (map.hasError)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              'The other branches could not be listed, so none can be added here. What '
              '@${widget.username} already holds is shown above and is kept by a save: '
              '${apiErrorMessage(map.error!)}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
      ],
    );
  }

  /// The levels held in one branch, each its own toggle.
  ///
  /// The ladder is not a total order, so somebody who must both work and sign off in one branch
  /// holds MANAGE AND APPROVE there — two rows — and a single-choice control could neither
  /// compose that nor keep it through a save that replaces the whole set (B1).
  Widget _rightChips(_GrantLine line, bool canWrite) => Padding(
        // Keyed by the branch it belongs to, so a test can ask what is ticked FOR ONE BRANCH: the
        // same three labels appear once per ticked branch, and "Manage is selected somewhere" is
        // not the assertion that catches a roster rendered against the wrong row (B1).
        key: ValueKey('rights-${line.regionId ?? 'every'}'),
        padding: const EdgeInsets.only(left: 32, bottom: 8),
        child: Align(
          alignment: Alignment.centerLeft,
          child: Wrap(
            spacing: 6,
            runSpacing: 4,
            children: [
              for (final r in _rightsShownFor(line))
                Tooltip(
                  message: _rightTooltip(r),
                  child: FilterChip(
                    label: Text(_rightLabel(r)),
                    selected: line.rights.contains(r),
                    onSelected: canWrite ? (on) => _toggleRight(line, r, on) : null,
                  ),
                ),
            ],
          ),
        ),
      );
}

class _Result extends StatelessWidget {
  final MyRegions result;

  /// What this save asked for on the wildcard row, because the answer cannot say (B1).
  final Set<String> wildcardRights;

  const _Result({required this.result, this.wildcardRights = const {}});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('They now hold:', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        if (result.allRegions)
          ListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            leading: const Icon(Icons.public),
            title: const Text('Every branch'),
            subtitle:
                wildcardRights.isEmpty ? null : Text(wildcardRights.join(', ')),
          ),
        for (final g in result.regions)
          ListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            leading: const Icon(Icons.account_tree_outlined),
            title: Text(g.label),
            subtitle: Text(g.rights.join(', ')),
          ),
        if (!result.allRegions && result.regions.isEmpty)
          const Text('Nothing. They can see no branch at all, and every regional list will be '
              'empty for them.'),
      ],
    );
  }
}
