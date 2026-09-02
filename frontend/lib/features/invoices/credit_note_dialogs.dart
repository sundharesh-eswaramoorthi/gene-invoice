import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';

/// Outcome of a committed credit-note issuance. The note is already saved by
/// the backend; [warning] carries the backend's notice-delivery warning, so
/// the caller can surface it without treating the issuance as failed.
class CreditNoteIssueResult {
  final String? warning;
  const CreditNoteIssueResult({this.warning});
}

/// Shows the issue-credit-note form for one invoice.
/// Returns a [CreditNoteIssueResult] when a note was committed, null when
/// the dialog was cancelled. Submission failures are shown inline.
Future<CreditNoteIssueResult?> showCreditNoteIssueDialog({
  required BuildContext context,
  required int invoiceId,
  required double maxCreditable,
}) {
  return showDialog<CreditNoteIssueResult>(
    context: context,
    builder: (_) => _CreditNoteIssueDialog(
      invoiceId: invoiceId,
      maxCreditable: maxCreditable,
    ),
  );
}

class _CreditNoteIssueDialog extends ConsumerStatefulWidget {
  final int invoiceId;
  final double maxCreditable;
  const _CreditNoteIssueDialog({
    required this.invoiceId,
    required this.maxCreditable,
  });

  @override
  ConsumerState<_CreditNoteIssueDialog> createState() =>
      _CreditNoteIssueDialogState();
}

class _CreditNoteIssueDialogState
    extends ConsumerState<_CreditNoteIssueDialog> {
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
          'reason': _reasonCtrl.text.trim(),
        },
      );
      final data = res.data;
      final warning =
          (data is Map && data['warning'] is String) ? data['warning'] as String : null;
      if (mounted) {
        Navigator.of(context).pop(CreditNoteIssueResult(warning: warning));
      }
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Issue credit note'),
      content: SizedBox(
        width: 460,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextFormField(
                  controller: _amountCtrl,
                  decoration: InputDecoration(
                    labelText: 'Amount (max ${widget.maxCreditable.toStringAsFixed(2)})',
                  ),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  validator: (v) {
                    final amount = double.tryParse((v ?? '').trim());
                    if (amount == null || amount <= 0) {
                      return 'Enter a positive amount';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _reasonCtrl,
                  decoration: const InputDecoration(labelText: 'Reason'),
                  maxLines: 3,
                  validator: (v) =>
                      (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      _error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
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
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Issue'),
        ),
      ],
    );
  }
}

/// Confirms and performs the one-way void of a credit note.
/// Returns true when the note was voided. Submission failures are shown
/// inline, leaving the dialog open.
Future<bool?> showCreditNoteVoidDialog({
  required BuildContext context,
  required int invoiceId,
  required int creditNoteId,
  required double amount,
}) {
  return showDialog<bool>(
    context: context,
    builder: (_) => _CreditNoteVoidDialog(
      invoiceId: invoiceId,
      creditNoteId: creditNoteId,
      amount: amount,
    ),
  );
}

class _CreditNoteVoidDialog extends ConsumerStatefulWidget {
  final int invoiceId;
  final int creditNoteId;
  final double amount;
  const _CreditNoteVoidDialog({
    required this.invoiceId,
    required this.creditNoteId,
    required this.amount,
  });

  @override
  ConsumerState<_CreditNoteVoidDialog> createState() =>
      _CreditNoteVoidDialogState();
}

class _CreditNoteVoidDialogState extends ConsumerState<_CreditNoteVoidDialog> {
  bool _saving = false;
  String? _error;

  Future<void> _confirm() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      await dio.post(
        '/api/invoices/${widget.invoiceId}/credit-notes/${widget.creditNoteId}/void',
      );
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Void credit note'),
      content: SizedBox(
        width: 460,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Void the credit note of ${widget.amount.toStringAsFixed(2)}? '
              'This cannot be undone: the amount is restored to the invoice and '
              'the record stays visible as voided.',
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _confirm,
          child: _saving
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Void'),
        ),
      ],
    );
  }
}
