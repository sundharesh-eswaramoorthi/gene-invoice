import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/field_limits.dart';

/// A loose check that text looks like one email address; the server makes the real one.
bool looksLikeEmail(String text) => RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(text.trim());

/// The list as a form should save it: with the address still typed in the add box, which a user
/// who never pressed + expects to be kept. Null when that text is not an email address.
List<String>? withTypedEmail(List<String> emails, String typed) {
  final text = typed.trim();
  if (text.isEmpty) return emails;
  if (!looksLikeEmail(text)) return null;
  if (emails.any((e) => e.toLowerCase() == text.toLowerCase())) return emails;
  return [...emails, text];
}

/// A short list of email addresses — a customer's other addresses — as removable chips with a box
/// to add one more. Each address is kept once, however it is capitalised.
class EmailListEditor extends StatefulWidget {
  final List<String> emails;
  final ValueChanged<List<String>> onChanged;
  final bool enabled;
  final int max;

  /// The add box's text. A form passes its own so Save can keep an address that was typed but
  /// never added — see [withTypedEmail].
  final TextEditingController? input;

  const EmailListEditor({
    super.key,
    required this.emails,
    required this.onChanged,
    this.enabled = true,
    this.max = FieldLimits.customerExtraEmails,
    this.input,
  });

  @override
  State<EmailListEditor> createState() => _EmailListEditorState();
}

class _EmailListEditorState extends State<EmailListEditor> {
  TextEditingController? _ownInput;
  String? _error;

  TextEditingController get _input => widget.input ?? (_ownInput ??= TextEditingController());

  @override
  void dispose() {
    _ownInput?.dispose();
    super.dispose();
  }

  void _add() {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    String? problem;
    if (!looksLikeEmail(text)) {
      problem = 'Enter an email address';
    } else if (widget.emails.any((e) => e.toLowerCase() == text.toLowerCase())) {
      problem = 'Already on the list';
    } else if (widget.emails.length >= widget.max) {
      problem = 'At most ${widget.max} addresses';
    }
    setState(() => _error = problem);
    if (problem != null) return;
    _input.clear();
    widget.onChanged([...widget.emails, text]);
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.enabled) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Text(widget.emails.isEmpty ? '—' : widget.emails.join(', ')),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (widget.emails.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final e in widget.emails)
                  InputChip(
                    label: Text(e),
                    onDeleted: () => widget.onChanged(widget.emails.where((x) => x != e).toList()),
                  ),
              ],
            ),
          ),
        TextField(
          controller: _input,
          keyboardType: TextInputType.emailAddress,
          inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.email)],
          decoration: InputDecoration(
            isDense: true,
            hintText: 'Add another address',
            errorText: _error,
            suffixIcon: IconButton(
              tooltip: 'Add address',
              icon: const Icon(Icons.add),
              onPressed: _add,
            ),
          ),
          onChanged: (_) {
            if (_error != null) setState(() => _error = null);
          },
          onSubmitted: (_) => _add(),
        ),
      ],
    );
  }
}
