import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/credit_note.dart';

/// Retained credit-note history of one invoice: active AND voided rows, newest first.
final creditNotesProvider =
    FutureProvider.autoDispose.family<List<CreditNote>, int>((ref, invoiceId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices/$invoiceId/credit-notes');
  return (res.data as List)
      .cast<Map<String, dynamic>>()
      .map(CreditNote.fromJson)
      .toList();
});

/// Opens the per-invoice credit-note history. Every invoice-viewing user may open it;
/// issue/void controls appear only when [canManage] is true (the server stays authoritative).
/// [onMutated] is invoked after a committed issue/void so callers can refresh invoice data.
Future<void> showCreditNoteHistory(
  BuildContext context, {
  required int invoiceId,
  required String invoiceNumber,
  required bool canManage,
  VoidCallback? onMutated,
}) {
  return showDialog<void>(
    context: context,
    builder: (_) => _CreditNoteHistoryDialog(
      invoiceId: invoiceId,
      invoiceNumber: invoiceNumber,
      canManage: canManage,
      onMutated: onMutated,
    ),
  );
}

class _CreditNoteHistoryDialog extends ConsumerWidget {
  final int invoiceId;
  final String invoiceNumber;
  final bool canManage;
  final VoidCallback? onMutated;

  const _CreditNoteHistoryDialog({
    required this.invoiceId,
    required this.invoiceNumber,
    required this.canManage,
    this.onMutated,
  });

  Future<void> _voidNote(BuildContext context, WidgetRef ref, CreditNote note) async {
    try {
      final dio = ref.read(dioProvider);
      await dio.post('/api/invoices/$invoiceId/credit-notes/${note.id}/void');
      ref.invalidate(creditNotesProvider(invoiceId));
      onMutated?.call();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Credit note voided')),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(apiErrorMessage(e))),
        );
      }
    }
  }

  Future<void> _issue(BuildContext context, WidgetRef ref) async {
    final result = await showDialog<IssueCreditNoteResult>(
      context: context,
      builder: (_) => _IssueCreditNoteDialog(invoiceId: invoiceId),
    );
    if (result == null || !context.mounted) return;
    ref.invalidate(creditNotesProvider(invoiceId));
    onMutated?.call();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(result.notificationWarning
            ? result.notificationWarningMessage ??
                'Credit note issued, but admin notifications could not be delivered'
            : 'Credit note issued'),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(creditNotesProvider(invoiceId));
    final df = DateFormat.yMMMd().add_jm();
    return Dialog(
      child: SizedBox(
        width: 560,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Credit notes • $invoiceNumber',
                        style: Theme.of(context).textTheme.titleLarge),
                  ),
                  if (canManage)
                    FilledButton.icon(
                      icon: const Icon(Icons.add),
                      label: const Text('Issue credit note'),
                      onPressed: () => _issue(context, ref),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              Flexible(
                child: async.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.all(24),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                  error: (e, _) => Text('Failed: ${apiErrorMessage(e)}'),
                  data: (notes) {
                    if (notes.isEmpty) {
                      return const Text('No credit notes for this invoice.');
                    }
                    return ListView.separated(
                      shrinkWrap: true,
                      itemCount: notes.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (context, i) {
                        final n = notes[i];
                        return ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          title: Text('${n.amount.toStringAsFixed(2)} • ${n.reason}'),
                          subtitle: Text(
                            'Issued by ${n.issuedByName ?? 'user #${n.issuedByUserId}'} • '
                            '${df.format(n.issuedAt.toLocal())}',
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Chip(
                                label: Text(n.voided ? 'Voided' : 'Active'),
                                backgroundColor: n.voided
                                    ? Theme.of(context).colorScheme.surfaceContainerHighest
                                    : null,
                              ),
                              if (canManage && !n.voided)
                                IconButton(
                                  tooltip: 'Void credit note',
                                  icon: const Icon(Icons.undo),
                                  onPressed: () => _voidNote(context, ref, n),
                                ),
                            ],
                          ),
                        );
                      },
                    );
                  },
                ),
              ),
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Close'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _IssueCreditNoteDialog extends ConsumerStatefulWidget {
  final int invoiceId;
  const _IssueCreditNoteDialog({required this.invoiceId});

  @override
  ConsumerState<_IssueCreditNoteDialog> createState() => _IssueCreditNoteDialogState();
}

class _IssueCreditNoteDialogState extends ConsumerState<_IssueCreditNoteDialog> {
  final _formKey = GlobalKey<FormState>();
  final _amountCtrl = TextEditingController();
  final _reasonCtrl = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _amountCtrl.dispose();
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      final res = await dio.post(
        '/api/invoices/${widget.invoiceId}/credit-notes',
        data: {
          'amount': double.parse(_amountCtrl.text.trim()),
          'reason': _reasonCtrl.text,
        },
      );
      final result =
          IssueCreditNoteResult.fromJson(res.data as Map<String, dynamic>);
      if (mounted) Navigator.of(context).pop(result);
    } catch (e) {
      // A refused issuance (e.g. over the creditable amount) lands here with the
      // server's message, which states the amount still creditable.
      setState(() => _error = e is String ? e : apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Issue credit note'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _amountCtrl,
                decoration: const InputDecoration(labelText: 'Amount'),
                keyboardType:
                    const TextInputType.numberWithOptions(decimal: true),
                validator: (v) {
                  final value = double.tryParse((v ?? '').trim());
                  if (value == null || value <= 0) {
                    return 'Enter a positive amount';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _reasonCtrl,
                decoration: const InputDecoration(labelText: 'Reason'),
                maxLines: 2,
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!,
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
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
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Issue'),
        ),
      ],
    );
  }
}
