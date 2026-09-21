import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import 'poc_picker.dart';
import 'poc_providers.dart';

class CustomerPocEditor extends ConsumerStatefulWidget {
  final int customerId;
  final PocType type;
  final List<CustomerPoc> pocs;
  final bool editable;
  final VoidCallback onChanged;

  const CustomerPocEditor({
    super.key,
    required this.customerId,
    required this.type,
    required this.pocs,
    required this.editable,
    required this.onChanged,
  });

  @override
  ConsumerState<CustomerPocEditor> createState() => _CustomerPocEditorState();
}

class _CustomerPocEditorState extends ConsumerState<CustomerPocEditor> {
  bool _busy = false;

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
      widget.onChanged();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _add() async {
    PocUser? picked;
    bool primary = widget.pocs.isEmpty;
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => StatefulBuilder(
        builder: (context, setState) => AlertDialog(
          title: Text('Add ${pocTypeLabel(widget.type)}'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                PocPicker(
                  type: widget.type,
                  value: picked,
                  required: true,
                  onChanged: (u) => setState(() => picked = u),
                ),
                CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  value: primary,
                  title: const Text('Make primary'),
                  onChanged: (v) => setState(() => primary = v ?? false),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Cancel')),
            FilledButton(
              onPressed: picked == null ? null : () => Navigator.of(context).pop(true),
              child: const Text('Add'),
            ),
          ],
        ),
      ),
    );
    if (ok != true || picked == null) return;
    await _run(() => ref.read(dioProvider).post(
          '/api/customers/${widget.customerId}/pocs',
          data: {'pocType': widget.type.name, 'userId': picked!.id, 'primary': primary},
        ));
  }

  Future<void> _remove(CustomerPoc poc) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Remove ${poc.user.display}?'),
        content: Text(poc.primary
            ? 'They are the primary ${pocTypeLabel(widget.type)}. Another holder is promoted '
                'automatically, or the primary is cleared if there is nobody else.'
            : 'They stop being a ${pocTypeLabel(widget.type)} for this customer.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _run(() => ref
        .read(dioProvider)
        .delete('/api/customers/${widget.customerId}/pocs/${poc.id}'));
  }

  Future<void> _setPrimary(CustomerPoc poc) => _run(() => ref
      .read(dioProvider)
      .post('/api/customers/${widget.customerId}/pocs/${poc.id}/primary'));

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (widget.pocs.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              children: [
                Icon(Icons.warning_amber_outlined,
                    size: 16, color: Theme.of(context).colorScheme.error),
                const SizedBox(width: 6),
                Expanded(
                  child: Text('No ${pocTypeLabel(widget.type)} yet',
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
              ],
            ),
          ),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            for (final poc in widget.pocs)
              // A chip with no handlers is drawn in Material's disabled style, which reads as
              // "broken" to someone who simply may not edit. They get a plain chip (D-58).
              if (!widget.editable)
                Chip(
                  avatar: poc.primary
                      ? const Icon(Icons.star, size: 16)
                      : const Icon(Icons.person_outline, size: 16),
                  label: Text(
                    poc.user.display + (poc.user.active ? '' : ' (inactive)'),
                    style: TextStyle(fontWeight: poc.primary ? FontWeight.w600 : null),
                  ),
                )
              else
                InputChip(
                  avatar: poc.primary
                      ? const Icon(Icons.star, size: 16)
                      : const Icon(Icons.person_outline, size: 16),
                  label: Text(
                    poc.user.display + (poc.user.active ? '' : ' (inactive)'),
                    style: TextStyle(fontWeight: poc.primary ? FontWeight.w600 : null),
                  ),
                  onPressed: (_busy || poc.primary) ? null : () => _setPrimary(poc),
                  tooltip: poc.primary ? 'Primary' : 'Tap to make primary',
                  onDeleted: _busy ? null : () => _remove(poc),
                ),
            if (widget.editable)
              ActionChip(
                avatar: const Icon(Icons.add, size: 16),
                label: const Text('Add'),
                onPressed: _busy ? null : _add,
              ),
            if (_busy)
              const SizedBox(
                  width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
          ],
        ),
      ],
    );
  }
}
