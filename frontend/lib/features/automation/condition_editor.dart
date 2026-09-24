import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/table/filter_editor.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import 'automation_models.dart';

/// THE AND/OR CONDITION TREE (A2).
///
/// PRD A2 asks for AND **and** OR, and a flat row of chips cannot say
/// "unpaid AND (overdue OR over ₹50,000)". So this is a real tree: a recursive card per group
/// with an [All of | Any of] header, its children indented behind a left border, and "Add
/// condition" / "Add group" underneath.
///
/// NOT ONE VALUE WIDGET IS WRITTEN HERE. "Add condition" opens the filter bar's OWN
/// [showFilterEditor] against the subject's published table schema, so every [ColumnType] gets
/// the editor it already has — enum chips, the boolean dropdown, [ReferencePicker], the date
/// pickers, the relative-period dropdown — and a leaf a rule is saved with is byte-identical to a
/// chip somebody typed into the list above it (A2).
///
/// IT MUST NOT GO THROUGH [TableQuery.addFilter], which de-dupes by (field, operator): that would
/// silently eat `balance:gt:1000 OR balance:gt:5000`, which is exactly what an OR is for. The
/// tree is its own model and keeps every leaf it is given (A2).
class ConditionEditor extends ConsumerWidget {
  /// The registered table entity the conditions are written against — `invoices` for an invoice
  /// rule. The SAME schema the server validates the saved rule with (A2).
  final String entity;

  /// What a reader calls these records — "invoices". The schema's own entity name is the wire
  /// key and reads badly in a sentence.
  final String subjectPlural;

  final ConditionGroup root;
  final ValueChanged<ConditionGroup> onChanged;

  /// The branches this rule names, handed to every reference picker the filter editor opens: a
  /// condition has no record to take a branch from, and the POC picker is refused without one
  /// (B1).
  final List<int> regionIds;

  final bool enabled;

  const ConditionEditor({
    super.key,
    required this.entity,
    required this.subjectPlural,
    required this.root,
    required this.onChanged,
    this.regionIds = const [],
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final schemaAsync = ref.watch(tableSchemaProvider(entity));
    final schema = schemaAsync.valueOrNull;
    if (schema == null) {
      return schemaAsync.maybeWhen(
        error: (e, _) => Text('Could not load the $entity columns: $e'),
        orElse: () => const Padding(
          padding: EdgeInsets.all(16),
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }
    return _GroupCard(
      group: root,
      schema: schema,
      subjectPlural: subjectPlural,
      depth: 1,
      leafCount: root.leafCount,
      regionIds: regionIds,
      enabled: enabled,
      onChanged: (next) => onChanged(next),
      onRemove: null,
    );
  }
}

class _GroupCard extends StatelessWidget {
  final ConditionGroup group;
  final TableSchema schema;
  final String subjectPlural;

  /// 1 for the outermost group. A group at [conditionMaxGroupDepth] may hold conditions but not
  /// another group — the server's own MAX_DEPTH arithmetic said before the save rather than after,
  /// with the leaf's own level counted the way `Conditions.walk` counts it (A2).
  final int depth;

  /// Leaves across the WHOLE tree, not this group — the server counts them that way too (A2).
  final int leafCount;

  final List<int> regionIds;
  final bool enabled;
  final ValueChanged<ConditionGroup> onChanged;

  /// Null on the outermost group: a rule always has one, and a tree with no root is not a state
  /// the editor can be in.
  final VoidCallback? onRemove;

  const _GroupCard({
    required this.group,
    required this.schema,
    required this.subjectPlural,
    required this.depth,
    required this.leafCount,
    required this.regionIds,
    required this.enabled,
    required this.onChanged,
    required this.onRemove,
  });

  bool get _mayNest => depth < conditionMaxGroupDepth;
  bool get _mayAddLeaf => leafCount < conditionMaxLeaves;

  Future<void> _addCondition(BuildContext context) async {
    final filter = await showFilterEditor(
      context: context,
      schema: schema,
      regionIds: regionIds,
    );
    if (filter == null) return;
    onChanged(group.withChildren([...group.of, ConditionLeaf(filter)]));
  }

  Future<void> _editLeaf(BuildContext context, int index, ConditionLeaf leaf) async {
    final filter = await showFilterEditor(
      context: context,
      schema: schema,
      existing: leaf.filter,
      regionIds: regionIds,
    );
    if (filter == null) return;
    final children = [...group.of];
    children[index] = ConditionLeaf(filter);
    onChanged(group.withChildren(children));
  }

  void _remove(int index) {
    final children = [...group.of]..removeAt(index);
    onChanged(group.withChildren(children));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Container(
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
        color: depth.isEven ? scheme.surfaceContainerHighest.withValues(alpha: 0.4) : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              SegmentedButton<Connector>(
                segments: const [
                  ButtonSegment(value: Connector.AND, label: Text('All of')),
                  ButtonSegment(value: Connector.OR, label: Text('Any of')),
                ],
                selected: {group.op},
                showSelectedIcon: false,
                onSelectionChanged:
                    enabled ? (picked) => onChanged(group.withOp(picked.first)) : null,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  group.of.isEmpty
                      ? 'every one of these $subjectPlural — add a condition to narrow it'
                      : group.op == Connector.AND
                          ? 'these must all be true'
                          : 'at least one of these must be true',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: scheme.onSurfaceVariant),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (onRemove != null)
                IconButton(
                  tooltip: 'Remove this group',
                  icon: const Icon(Icons.close, size: 18),
                  onPressed: enabled ? onRemove : null,
                ),
            ],
          ),
          // The indent and the left border are the whole readability argument: without them a
          // nested OR looks like a sibling of the AND above it (A2).
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: Container(
              decoration: BoxDecoration(
                border: Border(left: BorderSide(color: scheme.outlineVariant, width: 2)),
              ),
              padding: const EdgeInsets.only(left: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (group.of.whereType<ConditionLeaf>().isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: [
                          for (var i = 0; i < group.of.length; i++)
                            if (group.of[i] case final ConditionLeaf leaf)
                              InputChip(
                                label: Text(describeFilter(leaf.filter, schema)),
                                onPressed: enabled ? () => _editLeaf(context, i, leaf) : null,
                                deleteButtonTooltipMessage: 'Remove',
                                onDeleted: enabled ? () => _remove(i) : null,
                              ),
                        ],
                      ),
                    ),
                  for (var i = 0; i < group.of.length; i++)
                    if (group.of[i] case final ConditionGroup child)
                      _GroupCard(
                        group: child,
                        schema: schema,
                        subjectPlural: subjectPlural,
                        depth: depth + 1,
                        leafCount: leafCount,
                        regionIds: regionIds,
                        enabled: enabled,
                        onChanged: (next) {
                          final children = [...group.of];
                          children[i] = next;
                          onChanged(group.withChildren(children));
                        },
                        onRemove: () => _remove(i),
                      ),
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        Tooltip(
                          message: _mayAddLeaf
                              ? ''
                              : 'A rule may not have more than $conditionMaxLeaves conditions',
                          child: OutlinedButton.icon(
                            icon: const Icon(Icons.add, size: 18),
                            label: const Text('Add condition'),
                            onPressed: enabled && _mayAddLeaf
                                ? () => _addCondition(context)
                                : null,
                          ),
                        ),
                        Tooltip(
                          message: _mayNest
                              ? ''
                              : 'Conditions may not be nested more than '
                                  '$conditionMaxDepth deep',
                          child: OutlinedButton.icon(
                            icon: const Icon(Icons.account_tree_outlined, size: 18),
                            label: const Text('Add group'),
                            onPressed: enabled && _mayNest
                                ? () => onChanged(group.withChildren(
                                    [...group.of, const ConditionGroup(Connector.OR, [])]))
                                : null,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
