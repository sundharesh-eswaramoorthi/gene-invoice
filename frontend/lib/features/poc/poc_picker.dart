import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'poc_providers.dart';

/// Searchable picker for a point of contact. Only offers users whose role carries the
/// matching assignability privilege, and only while they are active (AC-A3).
class PocPicker extends StatelessWidget {
  final PocType type;
  final PocUser? value;
  final ValueChanged<PocUser?> onChanged;
  final bool enabled;
  final bool required;
  final String? errorText;
  final String? labelOverride;

  /// False where the surrounding layout already labels the field — a DetailGrid cell, say — so
  /// the name does not appear twice, once above the box and again inside it.
  final bool showLabel;

  const PocPicker({
    super.key,
    required this.type,
    required this.value,
    required this.onChanged,
    this.enabled = true,
    this.required = false,
    this.errorText,
    this.labelOverride,
    this.showLabel = true,
  });

  @override
  Widget build(BuildContext context) {
    final label = labelOverride ?? pocTypeLabel(type);
    final theme = Theme.of(context);

    if (!enabled) {
      return InputDecorator(
        decoration:
            InputDecoration(labelText: showLabel ? label : null, border: InputBorder.none),
        child: Text(value?.display ?? '—'),
      );
    }

    return InkWell(
      onTap: () => _openPicker(context),
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: !showLabel ? null : (required ? '$label *' : label),
          errorText: errorText,
          suffixIcon: value != null && !required
              ? IconButton(
                  tooltip: 'Clear',
                  icon: const Icon(Icons.clear, size: 18),
                  onPressed: () => onChanged(null),
                )
              : const Icon(Icons.arrow_drop_down),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                value?.display ?? 'Select…',
                style: value == null ? TextStyle(color: theme.hintColor) : null,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (value != null && !value!.active)
              const Padding(
                padding: EdgeInsets.only(left: 6),
                child: Chip(
                  label: Text('inactive', style: TextStyle(fontSize: 11)),
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _openPicker(BuildContext context) async {
    final selected = await showDialog<PocUser>(
      context: context,
      builder: (_) => _PocPickerDialog(type: type, selectedId: value?.id),
    );
    if (selected != null) onChanged(selected);
  }
}

class _PocPickerDialog extends ConsumerStatefulWidget {
  final PocType type;
  final int? selectedId;
  const _PocPickerDialog({required this.type, this.selectedId});

  @override
  ConsumerState<_PocPickerDialog> createState() => _PocPickerDialogState();
}

class _PocPickerDialogState extends ConsumerState<_PocPickerDialog> {
  String _search = '';
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  // The dialog owns the search, so the list always answers what is in the box now. Held by the
  // field behind the dialog, it rebuilt the field but not the dialog: one keystroke behind.
  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () {
      if (!mounted) return;
      setState(() {
        _search = value.trim();
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(assignablePocsProvider(AssignableQuery(widget.type, _search)));
    return AlertDialog(
      title: Text('Choose ${pocTypeLabel(widget.type)}'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Search by name, username or email',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: _onSearchChanged,
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 280,
              child: async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(child: Text('Failed to load: $e')),
                data: (users) {
                  if (users.isEmpty) {
                    return const Center(
                      child: Padding(
                        padding: EdgeInsets.all(16),
                        child: Text(
                          'Nobody matches. Only active users whose role can hold this '
                          'POC appear here.',
                          textAlign: TextAlign.center,
                        ),
                      ),
                    );
                  }
                  return ListView.builder(
                    itemCount: users.length,
                    itemBuilder: (context, i) {
                      final u = users[i];
                      return ListTile(
                        leading: CircleAvatar(child: Text(u.display.characters.first.toUpperCase())),
                        title: Text(u.display),
                        subtitle: Text('@${u.username}${u.role == null ? '' : ' • ${u.role}'}'),
                        selected: u.id == widget.selectedId,
                        onTap: () => Navigator.of(context).pop(u),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
      ],
    );
  }
}

/// Small badge shown against a record that predates the POC field (AC-A9).
class PocMissingBadge extends StatelessWidget {
  final String label;
  const PocMissingBadge({super.key, this.label = 'POC missing'});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: scheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.person_off_outlined, size: 14, color: scheme.onErrorContainer),
          const SizedBox(width: 4),
          Text(label,
              style: TextStyle(fontSize: 11, color: scheme.onErrorContainer, fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }
}
