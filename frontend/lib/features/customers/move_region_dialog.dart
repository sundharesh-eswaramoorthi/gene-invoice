import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/customer.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../regions/regions_screen.dart';
import 'customers_screen.dart';

/// Moves a customer, and everything raised against it, to another region (§6.4). Offered only
/// when the server says this user may (`canMoveRegion`). Returns whether it moved.
Future<bool> showMoveRegionDialog(BuildContext context, WidgetRef ref, Customer customer) async {
  final moved = await showDialog<bool>(
    context: context,
    builder: (_) => MoveRegionDialog(customer: customer),
  );
  return moved ?? false;
}

/// What the server moved along with the customer, and anything the mover should know.
class MoveRegionResult {
  final int invoices;
  final int payments;
  final int promises;
  final int disputes;
  final Map<String, int> other;
  final List<String> warnings;

  const MoveRegionResult({
    this.invoices = 0,
    this.payments = 0,
    this.promises = 0,
    this.disputes = 0,
    this.other = const {},
    this.warnings = const [],
  });

  factory MoveRegionResult.fromJson(Map<String, dynamic> json) {
    final moved = json['moved'] is Map ? (json['moved'] as Map).cast<String, dynamic>() : const {};
    int count(Object? v) => (v as num?)?.toInt() ?? 0;
    final other = moved['other'] is Map ? (moved['other'] as Map) : const {};
    return MoveRegionResult(
      invoices: count(moved['invoices']),
      payments: count(moved['payments']),
      promises: count(moved['promises']),
      disputes: count(moved['disputes']),
      other: {for (final e in other.entries) '${e.key}': count(e.value)},
      warnings: ((json['warnings'] as List?) ?? const []).map((e) => '$e').toList(),
    );
  }

  /// "12 invoices, 7 payments, 1 dispute" — only what there was.
  List<String> get lines {
    String n(int count, String one, String many) => '$count ${count == 1 ? one : many}';
    return [
      if (invoices > 0) n(invoices, 'invoice', 'invoices'),
      if (payments > 0) n(payments, 'payment', 'payments'),
      if (promises > 0) n(promises, 'promise', 'promises'),
      if (disputes > 0) n(disputes, 'dispute', 'disputes'),
      for (final e in other.entries)
        if (e.value > 0)
          switch (e.key) {
            'TaskMoveParticipant' => n(e.value, 'task', 'tasks'),
            'PendingChangeMoveParticipant' =>
              '${n(e.value, 'change', 'changes')} awaiting approval closed',
            _ => '${e.value} ${humanizeParticipant(e.key)}',
          },
    ];
  }

  static String humanizeParticipant(String key) => key
      .replaceAll('MoveParticipant', '')
      .replaceAllMapped(RegExp(r'(?<=[a-z])([A-Z])'), (m) => ' ${m[1]}')
      .toLowerCase();
}

class MoveRegionDialog extends ConsumerStatefulWidget {
  final Customer customer;
  const MoveRegionDialog({super.key, required this.customer});

  @override
  ConsumerState<MoveRegionDialog> createState() => _MoveRegionDialogState();
}

class _MoveRegionDialogState extends ConsumerState<MoveRegionDialog> {
  static const _reasonLength = 500;

  final _reason = TextEditingController();
  int? _target;
  bool _saving = false;
  String? _error;
  MoveRegionResult? _result;
  String? _movedTo;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  /// Active regions this user manages customers in, other than the one it is in now (§7.5).
  List<RegionAccess> _choices(CurrentUser? user) => [
        for (final r in user?.regions ?? const <RegionAccess>[])
          if (r.active &&
              r.id != widget.customer.regionId &&
              user!.hasIn(Privileges.customerManage, r.id))
            r
      ];

  Future<void> _move(List<RegionAccess> choices) async {
    if (_target == null) {
      setState(() => _error = 'Choose the region to move it to');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final c = widget.customer;
      final res = await ref.read(dioProvider).post('/api/customers/${c.id}/move-region', data: {
        'regionId': _target,
        if (c.version != null) 'version': c.version,
        if (_reason.text.trim().isNotEmpty) 'reason': _reason.text.trim(),
      });
      ref.invalidate(customerDetailProvider(c.id));
      ref.invalidate(tablePageProvider);
      ref.invalidate(tableSummaryProvider);
      ref.invalidate(auditHistoryProvider);
      final target = choices.firstWhere((r) => r.id == _target);
      if (mounted) {
        setState(() {
          _result = MoveRegionResult.fromJson((res.data as Map).cast<String, dynamic>());
          _movedTo = target.code;
        });
      }
    } catch (e) {
      // Changes still awaiting approval, a stale page or an inactive region: the server's words,
      // and the dialog stays so the mover can act on them.
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
    if (result != null) return _done(context, result);

    final c = widget.customer;
    final choices = _choices(ref.watch(currentUserProvider));
    final theme = Theme.of(context);
    return AlertDialog(
      title: Text('Move ${c.name} to another region'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Now in ${regionLabel(c.regionCode, c.regionName)}. Its invoices, payments, '
                  'promises, disputes and tasks move with it; people who hold its POC seats '
                  'keep them.'),
              const SizedBox(height: 12),
              if (choices.isEmpty)
                Text('There is no other active region where you manage customers.',
                    style: theme.textTheme.bodySmall)
              else
                DropdownButtonFormField<int>(
                  key: const ValueKey('move-region-target'),
                  initialValue: _target,
                  decoration: const InputDecoration(labelText: 'Move to'),
                  items: [
                    for (final r in choices)
                      DropdownMenuItem(value: r.id, child: Text('${r.code} — ${r.name}')),
                  ],
                  onChanged: _saving ? null : (v) => setState(() => _target = v),
                ),
              const SizedBox(height: 8),
              TextField(
                key: const ValueKey('move-region-reason'),
                controller: _reason,
                enabled: !_saving,
                minLines: 1,
                maxLines: 3,
                inputFormatters: [LengthLimitingTextInputFormatter(_reasonLength)],
                decoration: const InputDecoration(labelText: 'Reason (kept in its history)'),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
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
          onPressed: _saving || choices.isEmpty ? null : () => _move(choices),
          child: _saving
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Move'),
        ),
      ],
    );
  }

  Widget _done(BuildContext context, MoveRegionResult result) {
    final theme = Theme.of(context);
    final lines = result.lines;
    return AlertDialog(
      title: Text('${widget.customer.name} is now in $_movedTo'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(lines.isEmpty ? 'Nothing else was on it.' : 'Moved with it: ${lines.join(', ')}.'),
              if (result.warnings.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text('Worth a look', style: theme.textTheme.titleSmall),
                const SizedBox(height: 4),
                for (final w in result.warnings)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.warning_amber_outlined, size: 16, color: theme.colorScheme.error),
                        const SizedBox(width: 6),
                        Expanded(child: Text(w)),
                      ],
                    ),
                  ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        FilledButton(
            onPressed: () => Navigator.of(context).pop(true), child: const Text('Done')),
      ],
    );
  }
}
