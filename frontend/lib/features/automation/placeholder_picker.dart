import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import 'automation_providers.dart';

/// The placeholder picker (A4).
///
/// PRD A4 asks for placeholders at BOTH levels — the customer's POC book and the record's own —
/// in the subject AND in the body. That grouping is not invented here: the server answers each
/// slot with the `group` the To field already uses for the same level ("Customer level",
/// "Invoice level"), from the same method, so a rename there renames both. This sheet groups by
/// whatever the server said and adds no vocabulary of its own (A4, L4).
///
/// A chip inserts its token AT THE CURSOR, which is the whole reason this is a sheet over a text
/// field rather than a list beside one: somebody writing "Dear , your invoice" wants the name
/// where they left the caret.
Future<void> showPlaceholderPicker(
  BuildContext context, {
  required AutomationSubject subject,
  required TextEditingController controller,
  required String fieldLabel,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _PlaceholderSheet(
      subject: subject,
      controller: controller,
      fieldLabel: fieldLabel,
    ),
  );
}

/// Puts [token] where the caret is, and leaves the caret after it.
///
/// A controller that was never focused reports `TextSelection(-1, -1)`, which is not a position:
/// appending is the honest answer there, and it is what the field looks like it would do.
void insertAtCursor(TextEditingController controller, String token) {
  final text = controller.text;
  final selection = controller.selection;
  final start = selection.start < 0 ? text.length : selection.start;
  final end = selection.end < 0 ? text.length : selection.end;
  final next = text.replaceRange(start, end, token);
  controller.value = TextEditingValue(
    text: next,
    selection: TextSelection.collapsed(offset: start + token.length),
  );
}

class _PlaceholderSheet extends ConsumerWidget {
  final AutomationSubject subject;
  final TextEditingController controller;
  final String fieldLabel;

  const _PlaceholderSheet({
    required this.subject,
    required this.controller,
    required this.fieldLabel,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(placeholdersProvider(subject));
    final height = MediaQuery.sizeOf(context).height;

    return SafeArea(
      child: ConstrainedBox(
        // A phone has nowhere near a full sheet to give (D-60).
        constraints: BoxConstraints(maxHeight: height * 0.7),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Insert into $fieldLabel', style: theme.textTheme.titleMedium),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              Text(
                'A placeholder is filled in for each record when the rule runs.',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 8),
              Flexible(
                child: async.when(
                  loading: () => const Padding(
                    padding: EdgeInsets.all(24),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                  error: (e, _) => Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text('Could not load the placeholders: ${apiErrorMessage(e)}'),
                  ),
                  data: (slots) => _groups(context, slots),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _groups(BuildContext context, List<PlaceholderSlot> slots) {
    final theme = Theme.of(context);
    // The server's own order, preserved: customer level whole and first, then the record's own,
    // exactly as the To picker orders its two groups (A4, L4).
    final groups = <String, List<PlaceholderSlot>>{};
    for (final slot in slots) {
      (groups[slot.group] ??= []).add(slot);
    }
    if (groups.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Text('No placeholders are offered for this kind of record.'),
      );
    }
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final entry in groups.entries) ...[
            Padding(
              padding: const EdgeInsets.only(top: 12, bottom: 4),
              child: Text(entry.key,
                  style: theme.textTheme.labelLarge
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final slot in entry.value)
                  Tooltip(
                    // The token AND the server-rendered example, because "Balance" alone does not
                    // say what will land in the sentence (A4).
                    message: [slot.key, if ((slot.example ?? '').isNotEmpty) 'e.g. ${slot.example}']
                        .join('\n'),
                    child: ActionChip(
                      label: Text(slot.label),
                      onPressed: () {
                        insertAtCursor(controller, slot.key);
                        Navigator.of(context).pop();
                      },
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
