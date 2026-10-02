import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/filter_editor.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import 'automation_models.dart';

/// A rule's condition (A2): the list page's own filters for [entity], combined with "all of
/// these" and "any of these" groups. The empty tree means every record.
class ConditionBuilder extends ConsumerWidget {
  final TriggerEntity entity;
  final ConditionGroup value;
  final ValueChanged<ConditionGroup> onChanged;
  final bool enabled;
  final String? errorText;

  const ConditionBuilder({
    super.key,
    required this.entity,
    required this.value,
    required this.onChanged,
    this.enabled = true,
    this.errorText,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(tableSchemaProvider(entity.schemaEntity));
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(12),
        child: LinearProgressIndicator(),
      ),
      error: (e, _) => Text(
          'Could not load the ${entity.label.toLowerCase()} filters: ${apiErrorMessage(e)}',
          style: TextStyle(color: theme.colorScheme.error)),
      data: (schema) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (value.isEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                'Every ${entity.label.toLowerCase()} matches. Add a condition to narrow it down.',
                style:
                    theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ),
          ConditionGroupEditor(
            schema: schema,
            group: value,
            onChanged: onChanged,
            enabled: enabled,
            leavesLeft: ConditionLimits.leaves - value.leafCount,
          ),
          if (errorText != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(errorText!, style: TextStyle(color: theme.colorScheme.error)),
            ),
        ],
      ),
    );
  }
}

/// One group of the tree, and every group inside it. Each change hands back a whole new group;
/// a nested group left with nothing in it goes with its last condition, since the server takes no
/// empty group (§5.10.2).
///
/// Conditions are appended as they are, never through `TableQuery.addFilter`, which would fold two
/// conditions on the same field and operator into one: "balance over 100 or balance over 5,000
/// with…" is two conditions here.
class ConditionGroupEditor extends StatelessWidget {
  final TableSchema schema;
  final ConditionGroup group;
  final ValueChanged<ConditionGroup> onChanged;

  /// Present on nested groups only: the root is the rule's whole condition.
  final VoidCallback? onRemove;
  final bool enabled;

  /// 1 for the root; groups go no deeper than [ConditionLimits.depth].
  final int depth;

  /// Where this group sits, for the keys of its controls: `c`, `c.1`, `c.1.0`, … .
  final String path;

  /// How many more conditions the whole tree may take.
  final int leavesLeft;

  const ConditionGroupEditor({
    super.key,
    required this.schema,
    required this.group,
    required this.onChanged,
    required this.leavesLeft,
    this.onRemove,
    this.enabled = true,
    this.depth = 1,
    this.path = 'c',
  });

  bool get _roomForChild =>
      group.children.length < ConditionLimits.childrenPerGroup && leavesLeft > 0;

  void _replace(int i, ConditionNode? node) {
    final next = [...group.children];
    if (node == null || (node is ConditionGroup && node.isEmpty)) {
      next.removeAt(i);
    } else {
      next[i] = node;
    }
    onChanged(group.withChildren(next));
  }

  Future<void> _addCondition(BuildContext context) async {
    final f = await showFilterEditor(context: context, schema: schema);
    if (f == null) return;
    onChanged(group.withChildren([...group.children, ConditionLeaf(f)]));
  }

  /// A group starts with its first condition, and reads the other way from this one: inside "all
  /// of these", the usual reason for a group is "any of these".
  Future<void> _addGroup(BuildContext context) async {
    final f = await showFilterEditor(context: context, schema: schema);
    if (f == null) return;
    final inner = ConditionGroup(group.op == 'AND' ? 'OR' : 'AND', [ConditionLeaf(f)]);
    onChanged(group.withChildren([...group.children, inner]));
  }

  Future<void> _editLeaf(BuildContext context, int i, ConditionLeaf leaf) async {
    final f = await showFilterEditor(context: context, schema: schema, existing: leaf.filter);
    if (f == null) return;
    _replace(i, ConditionLeaf(f));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final children = group.children;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (children.length > 1 || depth > 1)
          Wrap(
            spacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SegmentedButton<String>(
                key: ValueKey('cond-op-$path'),
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(value: 'AND', label: Text('All of these')),
                  ButtonSegment(value: 'OR', label: Text('Any of these')),
                ],
                selected: {group.op},
                onSelectionChanged: enabled ? (s) => onChanged(group.withOp(s.first)) : null,
              ),
              if (onRemove != null && enabled)
                IconButton(
                  key: ValueKey('cond-remove-$path'),
                  tooltip: 'Remove group',
                  icon: const Icon(Icons.delete_outline, size: 18),
                  onPressed: onRemove,
                ),
            ],
          ),
        for (var i = 0; i < children.length; i++)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: switch (children[i]) {
              final ConditionLeaf leaf => Align(
                  alignment: Alignment.centerLeft,
                  child: InputChip(
                    key: ValueKey('cond-leaf-$path.$i'),
                    label: Text(describeFilter(leaf.filter, schema)),
                    onPressed: enabled ? () => _editLeaf(context, i, leaf) : null,
                    onDeleted: enabled ? () => _replace(i, null) : null,
                    deleteButtonTooltipMessage: 'Remove condition',
                    isEnabled: true,
                  ),
                ),
              final ConditionGroup inner => Container(
                  key: ValueKey('cond-group-$path.$i'),
                  padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
                  decoration: BoxDecoration(
                    border: Border(left: BorderSide(color: theme.colorScheme.primary, width: 3)),
                    color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.35),
                  ),
                  child: ConditionGroupEditor(
                    schema: schema,
                    group: inner,
                    onChanged: (g) => _replace(i, g),
                    onRemove: () => _replace(i, null),
                    enabled: enabled,
                    depth: depth + 1,
                    path: '$path.$i',
                    leavesLeft: leavesLeft,
                  ),
                ),
            },
          ),
        if (enabled)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Wrap(
              spacing: 4,
              children: [
                TextButton.icon(
                  key: ValueKey('cond-add-$path'),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Add condition'),
                  onPressed: _roomForChild ? () => _addCondition(context) : null,
                ),
                if (depth < ConditionLimits.depth)
                  TextButton.icon(
                    key: ValueKey('cond-add-group-$path'),
                    icon: const Icon(Icons.account_tree_outlined, size: 18),
                    label: const Text('Add group'),
                    onPressed: _roomForChild ? () => _addGroup(context) : null,
                  ),
              ],
            ),
          ),
      ],
    );
  }
}
