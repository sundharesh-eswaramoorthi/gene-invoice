import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../auth/auth_controller.dart';

/// Moving an account to another branch.
///
/// The account moves and everything hanging off it moves with it in the same breath — its
/// invoices, payments, promises and disputes all take their branch from the account — and any
/// POC seat whose holder cannot manage the destination is vacated on the way. Both ends must be
/// branches the caller may MANAGE, and a refusal about either is a 403 and not a 404 because the
/// caller NAMED them (D-46, B1).
Future<bool> showMoveRegionDialog(
  BuildContext context, {
  required int customerId,
  required String customerName,
  required int? currentRegionId,
}) async {
  final moved = await showDialog<bool>(
    context: context,
    builder: (_) => _MoveRegionDialog(
      customerId: customerId,
      customerName: customerName,
      currentRegionId: currentRegionId,
    ),
  );
  return moved == true;
}

class _MoveRegionDialog extends ConsumerStatefulWidget {
  final int customerId;
  final String customerName;
  final int? currentRegionId;

  const _MoveRegionDialog({
    required this.customerId,
    required this.customerName,
    required this.currentRegionId,
  });

  @override
  ConsumerState<_MoveRegionDialog> createState() => _MoveRegionDialogState();
}

class _MoveRegionDialogState extends ConsumerState<_MoveRegionDialog> {
  int? _destination;
  DateTime? _effectiveFrom;
  final _reason = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final to = _destination;
    if (to == null) {
      setState(() => _error = 'Choose the branch it moves to');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(dioProvider).post('/api/customers/${widget.customerId}/region', data: {
        'toRegionId': to,
        // Null is "today". A date before the account arrived in its current branch is refused
        // by the server, naming that date (B1).
        'effectiveFrom': _effectiveFrom == null
            ? null
            : DateFormat('yyyy-MM-dd').format(_effectiveFrom!),
        'reason': optionalText(_reason.text),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// Holds a grant on every branch, including ones opened later — the roster if it answered,
  /// else what sign-in remembered (B1).
  bool _wildcard(WidgetRef ref) =>
      ref.watch(myRegionsProvider).valueOrNull?.allRegions ??
      (ref.watch(currentUserProvider)?.allRegions ?? false);

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(manageableRegionsProvider);
    // Both ends need MANAGE, so the destination list is the same list a create offers, less the
    // branch the account is already in — a move to where it already is does nothing (B1).
    final destinations = (async.valueOrNull ?? const <RegionRef>[])
        .where((r) => r.id != widget.currentRegionId)
        .toList();

    return AlertDialog(
      title: const Text('Move to another branch'),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${widget.customerName} moves with everything on it — its invoices, payments, '
                'promises and disputes. A POC seat whose holder does not work in the new branch '
                'is given up, and the next holder becomes primary.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 12),
              if (async.isLoading)
                const LinearProgressIndicator()
              else if (destinations.isEmpty)
                // A WILDCARD holder may move it anywhere — "no other branch" would be false for
                // them. They simply cannot be shown the branches, because the map is behind
                // REGION_VIEW and they do not hold it (B1).
                Text(_wildcard(ref)
                    ? 'You may move this account to any branch, but this app cannot list them: '
                        'that needs the Regions privilege. Ask an administrator for it.'
                    : 'There is no other branch you may move this account to.')
              else
                DropdownButtonFormField<int>(
                  initialValue: _destination,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Moves to *', isDense: true),
                  items: destinations
                      .map((r) => DropdownMenuItem(value: r.id, child: Text(r.label)))
                      .toList(),
                  onChanged: (v) => setState(() {
                    _destination = v;
                    _error = null;
                  }),
                ),
              const SizedBox(height: 8),
              InkWell(
                onTap: () async {
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: _effectiveFrom ?? DateTime.now(),
                    firstDate: DateTime(2000),
                    lastDate: DateTime(2100),
                  );
                  if (picked != null) setState(() => _effectiveFrom = picked);
                },
                child: InputDecorator(
                  decoration: const InputDecoration(
                    labelText: 'Effective from',
                    isDense: true,
                    helperText: 'Leave blank for today',
                    suffixIcon: Icon(Icons.calendar_today, size: 18),
                  ),
                  child: Text(_effectiveFrom == null ? 'Today' : formatDate(_effectiveFrom)),
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _reason,
                maxLines: 2,
                decoration: const InputDecoration(labelText: 'Reason', isDense: true),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(false),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving || destinations.isEmpty ? null : _submit,
          child: _saving
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Move account'),
        ),
      ],
    );
  }
}
