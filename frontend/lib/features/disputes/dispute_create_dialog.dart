import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/dispute.dart';
import '../../core/table/table_providers.dart';
import '../audit/audit_history_panel.dart';
import '../email/email_actions.dart';
import 'disputes_providers.dart';

/// Shows a modal dispute form for a specific invoice or payment.
/// Returns true if a dispute was filed.
///
/// "Notify through email" is handled here rather than by each caller: once the form has closed,
/// the compose form opens on [context] for the new dispute (E12). Only customer logins file
/// disputes, so that compose form is their restricted one (E13).
Future<bool?> showDisputeDialog({
  required BuildContext context,
  required DisputeTargetType targetType,
  required int targetId,
  required String targetLabel,
}) async {
  final filed = await showDialog<({int id, bool notify})>(
    context: context,
    builder: (_) => _DisputeCreateDialog(
      targetType: targetType,
      targetId: targetId,
      targetLabel: targetLabel,
    ),
  );
  if (filed == null) return false;
  // The caller's context, never the closed form's, and before handing back, so a caller that
  // moves on after filing has not yet taken that context away.
  if (context.mounted) {
    await notifyByEmailAfterSave(context,
        notify: filed.notify,
        type: EmailEntityType.dispute,
        entityId: filed.id,
        event: EmailEvent.created);
  }
  return true;
}

class _DisputeCreateDialog extends ConsumerStatefulWidget {
  final DisputeTargetType targetType;
  final int targetId;
  final String targetLabel;
  const _DisputeCreateDialog({
    required this.targetType,
    required this.targetId,
    required this.targetLabel,
  });

  @override
  ConsumerState<_DisputeCreateDialog> createState() => _DisputeCreateDialogState();
}

class _DisputeCreateDialogState extends ConsumerState<_DisputeCreateDialog> {
  final _formKey = GlobalKey<FormState>();
  final _reasonCtrl = TextEditingController();
  String _action = '';
  final _amountCtrl = TextEditingController();
  final _methodCtrl = TextEditingController();
  final _notesCtrl = TextEditingController();
  bool _notify = false;
  bool _saving = false;
  String? _error;

  List<DropdownMenuItem<String>> get _actionOptions {
    if (widget.targetType == DisputeTargetType.INVOICE) {
      return const [
        DropdownMenuItem(value: '', child: Text('Just describe (no specific change)')),
        DropdownMenuItem(value: 'cancel', child: Text('Cancel this invoice')),
        DropdownMenuItem(value: 'update_notes', child: Text('Correct the notes only')),
      ];
    }
    return const [
      DropdownMenuItem(value: '', child: Text('Just describe (no specific change)')),
      DropdownMenuItem(value: 'void', child: Text('Void / never happened')),
      DropdownMenuItem(value: 'update_amount', child: Text('Correct the amount')),
      DropdownMenuItem(value: 'update_meta', child: Text('Correct method / notes')),
    ];
  }

  @override
  void dispose() {
    _reasonCtrl.dispose();
    _amountCtrl.dispose();
    _methodCtrl.dispose();
    _notesCtrl.dispose();
    super.dispose();
  }

  String? _buildProposedJson() {
    if (_action.isEmpty) return null;
    final map = <String, dynamic>{'action': _action};
    switch (_action) {
      case 'update_amount':
        final amt = double.tryParse(_amountCtrl.text.trim());
        if (amt == null || amt <= 0) {
          throw 'Enter a positive amount for the correction';
        }
        map['amount'] = amt;
        if (_methodCtrl.text.trim().isNotEmpty) map['method'] = _methodCtrl.text.trim();
        if (_notesCtrl.text.trim().isNotEmpty) map['notes'] = _notesCtrl.text.trim();
        break;
      case 'update_meta':
        if (_methodCtrl.text.trim().isNotEmpty) map['method'] = _methodCtrl.text.trim();
        if (_notesCtrl.text.trim().isNotEmpty) map['notes'] = _notesCtrl.text.trim();
        break;
      case 'update_notes':
        if (_notesCtrl.text.trim().isEmpty) {
          throw 'Enter the corrected notes';
        }
        map['notes'] = _notesCtrl.text.trim();
        break;
    }
    return jsonEncode(map);
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final proposed = _buildProposedJson();
      final dio = ref.read(dioProvider);
      final res = await dio.post('/api/disputes', data: {
        'targetType': widget.targetType.name,
        'targetId': widget.targetId,
        'reason': _reasonCtrl.text.trim(),
        if (proposed != null) 'proposedChangeJson': proposed,
      });
      final id = ((res.data as Map)['id'] as num).toInt();
      ref.invalidate(scopedDisputesProvider);
      ref.invalidate(tablePageProvider);
      // The invoice's or payment's History tab now shows "Dispute opened".
      ref.invalidate(auditHistoryProvider);
      if (mounted) Navigator.of(context).pop((id: id, notify: _notify));
    } catch (e) {
      setState(() => _error = e is String ? e : apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final needsAmount = _action == 'update_amount';
    final needsMeta = _action == 'update_meta' || _action == 'update_amount';
    final needsNotes = _action == 'update_notes';
    return AlertDialog(
      title: Text('Raise dispute • ${widget.targetLabel}'),
      content: SizedBox(
        width: 460,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: _reasonCtrl,
                  decoration: const InputDecoration(labelText: 'Why is this wrong?'),
                  maxLines: 3,
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _action,
                  // Without this the text keeps its natural width and runs under the arrow on a
                  // phone (D-65).
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'What should change?'),
                  items: _actionOptions,
                  onChanged: (v) => setState(() => _action = v ?? ''),
                ),
                if (needsAmount) ...[
                  const SizedBox(height: 8),
                  TextFormField(
                    controller: _amountCtrl,
                    decoration: const InputDecoration(labelText: 'Corrected amount'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  ),
                ],
                if (needsMeta) ...[
                  const SizedBox(height: 8),
                  TextFormField(
                    controller: _methodCtrl,
                    decoration: const InputDecoration(labelText: 'Corrected method (optional)'),
                  ),
                ],
                if (needsMeta || needsNotes) ...[
                  const SizedBox(height: 8),
                  TextFormField(
                    controller: _notesCtrl,
                    decoration: InputDecoration(
                      labelText: needsNotes ? 'Corrected notes' : 'Corrected notes (optional)',
                    ),
                    maxLines: 2,
                  ),
                ],
                const SizedBox(height: 8),
                NotifyByEmailCheckbox(
                  value: _notify,
                  onChanged: (v) => setState(() => _notify = v),
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Submit'),
        ),
      ],
    );
  }
}
