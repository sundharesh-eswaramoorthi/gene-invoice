import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/field_limits.dart';
import 'email_models.dart';
import 'email_providers.dart';

/// The one compose surface every Send Email entry point opens (details, list, row menu, bulk).
/// Returns the draft as request parameters, or null when cancelled. The server re-validates
/// and re-resolves everything at send time; this dialog only makes honest input hard to miss.
Future<Map<String, dynamic>?> showEmailComposeDialog(
  BuildContext context, {
  required String contextLabel,
}) {
  return showDialog<Map<String, dynamic>>(
    context: context,
    builder: (_) => EmailComposeDialog(contextLabel: contextLabel),
  );
}

class EmailComposeDialog extends ConsumerStatefulWidget {
  /// What the send applies to, shown in the title: a record's name, or "the selected invoices".
  final String contextLabel;

  const EmailComposeDialog({super.key, required this.contextLabel});

  @override
  ConsumerState<EmailComposeDialog> createState() => _EmailComposeDialogState();
}

class _EmailComposeDialogState extends ConsumerState<EmailComposeDialog> {
  final _formKey = GlobalKey<FormState>();
  final _subject = TextEditingController();
  final _body = TextEditingController();

  EmailSenderOption? _from;
  final Set<int> _toUserIds = {};
  final Set<int> _toRoleIds = {};
  bool _includeCustomerAddress = false;

  @override
  void dispose() {
    _subject.dispose();
    _body.dispose();
    super.dispose();
  }

  bool get _hasRecipient =>
      _toUserIds.isNotEmpty || _toRoleIds.isNotEmpty || _includeCustomerAddress;

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    if (_from == null) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Choose who the Email is from')));
      return;
    }
    if (!_hasRecipient) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Select at least one recipient')));
      return;
    }
    Navigator.of(context).pop({
      if (_from!.kind == 'USER') 'fromUserId': _from!.id,
      if (_from!.kind == 'ROLE') 'fromRoleId': _from!.id,
      'toUserIds': _toUserIds.toList(),
      'toRoleIds': _toRoleIds.toList(),
      'includeCustomerAddress': _includeCustomerAddress,
      'subject': _subject.text.trim(),
      // Body may be empty; it is stored as no-body rather than whitespace.
      'body': _body.text.trim(),
    });
  }

  String _optionLabel(EmailSenderOption o) =>
      o.email == null ? '${o.label} (no address — admin fallback)' : '${o.label} (${o.email})';

  @override
  Widget build(BuildContext context) {
    final options = ref.watch(emailOptionsProvider);
    return AlertDialog(
      title: Text('Send email — ${widget.contextLabel}'),
      content: SizedBox(
        width: 520,
        child: options.when(
          loading: () => const Padding(
              padding: EdgeInsets.all(24), child: LinearProgressIndicator()),
          error: (e, _) => Text('Could not load senders and recipients: $e'),
          data: (o) => Form(
            key: _formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  DropdownButtonFormField<EmailSenderOption>(
                    initialValue: _from,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'From'),
                    items: [
                      for (final s in o.senders)
                        DropdownMenuItem(
                          value: s,
                          child: Text(
                            s.kind == 'ROLE'
                                ? 'Role: ${_optionLabel(s)}'
                                : _optionLabel(s),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (v) => setState(() => _from = v),
                    validator: (v) => v == null ? 'Required' : null,
                  ),
                  const SizedBox(height: 12),
                  Text('To', style: Theme.of(context).textTheme.titleSmall),
                  // Any mix of internal users, roles and the record's Customer address is
                  // allowed; at least one selection is required (checked on Send).
                  for (final r in o.recipients)
                    CheckboxListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(r.kind == 'ROLE' ? 'Role: ${r.label}' : r.label),
                      subtitle: r.email == null ? null : Text(r.email!),
                      value: r.kind == 'ROLE'
                          ? _toRoleIds.contains(r.id)
                          : _toUserIds.contains(r.id),
                      onChanged: (v) => setState(() {
                        final set = r.kind == 'ROLE' ? _toRoleIds : _toUserIds;
                        if (v == true) {
                          set.add(r.id);
                        } else {
                          set.remove(r.id);
                        }
                      }),
                    ),
                  CheckboxListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Customer email address'),
                    subtitle: const Text("The record's current Customer address, "
                        'resolved per record at send time'),
                    value: _includeCustomerAddress,
                    onChanged: (v) =>
                        setState(() => _includeCustomerAddress = v ?? false),
                  ),
                  const SizedBox(height: 8),
                  TextFormField(
                    controller: _subject,
                    decoration: const InputDecoration(labelText: 'Subject'),
                    inputFormatters: [
                      LengthLimitingTextInputFormatter(FieldLimits.emailSubject)
                    ],
                    validator: (v) =>
                        (v == null || v.trim().isEmpty) ? 'Required' : null,
                  ),
                  const SizedBox(height: 8),
                  TextFormField(
                    controller: _body,
                    decoration:
                        const InputDecoration(labelText: 'Body (optional)'),
                    inputFormatters: [
                      LengthLimitingTextInputFormatter(FieldLimits.emailBody)
                    ],
                    minLines: 3,
                    maxLines: 6,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(null),
            child: const Text('Cancel')),
        FilledButton.icon(
          icon: const Icon(Icons.send_outlined, size: 18),
          label: const Text('Send'),
          onPressed: (_from == null || !_hasRecipient) ? null : _submit,
        ),
      ],
    );
  }
}
