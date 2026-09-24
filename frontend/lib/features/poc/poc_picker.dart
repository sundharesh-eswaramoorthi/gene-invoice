import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../shared/models/auth_models.dart';
import '../auth/auth_controller.dart';
import 'poc_providers.dart';

class PocPicker extends ConsumerWidget {
  final PocType type;
  final PocUser? value;
  final ValueChanged<PocUser?> onChanged;
  final bool enabled;
  final bool required;
  final String? errorText;
  final String? labelOverride;

  final bool showLabel;

  /// The account this seat is being filled on, wherever there is one. Who may hold a seat is a
  /// question about a branch, and the account is the only answer that cannot be a guess (B1).
  final int? customerId;

  /// With no account in scope, the branches to ask about. Left null, the picker falls back to
  /// every branch the viewer works in, so no call site can silently ask about none and be
  /// refused — which is what the shipped client did at every one of them (B1).
  final List<int>? regionIds;

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
    this.customerId,
    this.regionIds,
  });

  /// The branches this picker asks about when it has no account: the ones it was given, else
  /// every branch the viewer works in. Empty for a wildcard holder, who may browse the whole
  /// directory because for them "anywhere" is an answer (B1).
  List<int> _branches(CurrentUser? viewer) {
    if (customerId != null) return const [];
    if (regionIds != null) return regionIds!;
    if (viewer == null || viewer.allRegions || viewer.isCustomer) return const [];
    return viewer.workingSet.toList();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final label = labelOverride ?? pocTypeLabel(type);
    final theme = Theme.of(context);
    final branches = _branches(ref.watch(currentUserProvider));

    if (!enabled) {
      return InputDecorator(
        decoration:
            InputDecoration(labelText: showLabel ? label : null, border: InputBorder.none),
        child: Text(value?.display ?? '—'),
      );
    }

    return InkWell(
      onTap: () => _openPicker(context, branches),
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

  Future<void> _openPicker(BuildContext context, List<int> branches) async {
    final selected = await showDialog<PocUser>(
      context: context,
      builder: (_) => _PocPickerDialog(
        type: type,
        selectedId: value?.id,
        customerId: customerId,
        regionIds: branches,
      ),
    );
    if (selected != null) onChanged(selected);
  }
}

class _PocPickerDialog extends ConsumerStatefulWidget {
  final PocType type;
  final int? selectedId;
  final int? customerId;
  final List<int> regionIds;
  const _PocPickerDialog({
    required this.type,
    this.selectedId,
    this.customerId,
    this.regionIds = const [],
  });

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
    final async = ref.watch(assignablePocsProvider(AssignableQuery(
      widget.type,
      _search,
      customerId: widget.customerId,
      regionIds: widget.regionIds,
    )));
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
                        // A seat is only valid if its holder can manage the account's branch, so
                        // the picker offers nobody from a branch this person does not work in
                        // — which is why an empty list is no longer only about the role (B1).
                        child: Text(
                          'Nobody matches. Only active users whose role can hold this POC, and '
                          'who work in this branch, appear here.',
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
