import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'poc_providers.dart';

/// Searchable picker for a point of contact. Only offers users whose role carries the
/// matching assignability privilege, and only while they are active (AC-A3).
class PocPicker extends ConsumerStatefulWidget {
  final PocType type;
  final PocUser? value;
  final ValueChanged<PocUser?> onChanged;
  final bool enabled;
  final bool required;
  final String? errorText;
  final String? labelOverride;

  const PocPicker({
    super.key,
    required this.type,
    required this.value,
    required this.onChanged,
    this.enabled = true,
    this.required = false,
    this.errorText,
    this.labelOverride,
  });

  @override
  ConsumerState<PocPicker> createState() => _PocPickerState();
}

class _PocPickerState extends ConsumerState<PocPicker> {
  String _search = '';
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () {
      if (mounted) setState(() => _search = value.trim());
    });
  }

  @override
  Widget build(BuildContext context) {
    final label = widget.labelOverride ?? pocTypeLabel(widget.type);
    final theme = Theme.of(context);

    if (!widget.enabled) {
      return InputDecorator(
        decoration: InputDecoration(labelText: label, border: InputBorder.none),
        child: Text(widget.value?.display ?? '—'),
      );
    }

    return InkWell(
      onTap: () => _openPicker(context),
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: widget.required ? '$label *' : label,
          errorText: widget.errorText,
          suffixIcon: widget.value != null && !widget.required
              ? IconButton(
                  tooltip: 'Clear',
                  icon: const Icon(Icons.clear, size: 18),
                  onPressed: () => widget.onChanged(null),
                )
              : const Icon(Icons.arrow_drop_down),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                widget.value?.display ?? 'Select…',
                style: widget.value == null
                    ? TextStyle(color: theme.hintColor)
                    : null,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (widget.value != null && !widget.value!.active)
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
      builder: (_) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
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
                  onChanged: (v) {
                    _onSearchChanged(v);
                    setDialogState(() {});
                  },
                ),
                const SizedBox(height: 12),
                SizedBox(
                  height: 280,
                  child: Consumer(
                    builder: (context, ref, _) {
                      final async = ref.watch(
                          assignablePocsProvider(AssignableQuery(widget.type, _search)));
                      return async.when(
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
                                leading: CircleAvatar(
                                    child: Text(u.display.characters.first.toUpperCase())),
                                title: Text(u.display),
                                subtitle: Text('@${u.username}${u.role == null ? '' : ' • ${u.role}'}'),
                                selected: u.id == widget.value?.id,
                                onTap: () => Navigator.of(context).pop(u),
                              );
                            },
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
        ),
      ),
    );
    if (selected != null) widget.onChanged(selected);
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
