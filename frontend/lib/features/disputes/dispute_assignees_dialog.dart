import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/assignee.dart';
import '../../shared/models/dispute.dart';
import '../../shared/widgets/assignee_picker_field.dart';
import '../email/email_actions.dart';
import '../email/email_providers.dart';
import '../tasks/task_providers.dart';
import 'disputes_providers.dart';

/// Names who is answerable for a dispute (A1). Resolves true once it is saved.
///
/// A dispute is the one record whose assignees cannot be a field on the form that raises it: that
/// form is worked from the customer's side, where staff cannot be seen at all, let alone picked
/// (AC-A8). So the server makes assigning an act of its own — `PATCH /api/disputes/{id}/assignees`,
/// DISPUTE_MANAGE — and this is it: a dialog off the dispute's own page, opened by whoever is
/// picking the dispute up or handing it on while it is still open.
///
/// The picker is the very one the task form and the email To field use, so a person or a seat is
/// chosen the same way wherever work is given out (A1).
Future<bool?> showDisputeAssigneesDialog({
  required BuildContext context,
  required Dispute dispute,
}) =>
    showDialog<bool>(
      context: context,
      builder: (_) => _DisputeAssigneesDialog(dispute: dispute),
    );

class _DisputeAssigneesDialog extends ConsumerStatefulWidget {
  final Dispute dispute;
  const _DisputeAssigneesDialog({required this.dispute});

  @override
  ConsumerState<_DisputeAssigneesDialog> createState() => _DisputeAssigneesDialogState();
}

class _DisputeAssigneesDialogState extends ConsumerState<_DisputeAssigneesDialog> {
  /// The tokens that would name the people already on it, so a dialog opened to add one person
  /// hands the rest back untouched: the request replaces the whole list.
  late List<EmailToken> _assignees = widget.dispute.assigneeTokens;

  bool _saving = false;
  String? _error;

  /// Names for the people the dispute is already assigned to. They were picked before this dialog
  /// opened, so the field cannot name them itself — only the dispute knows what they are called.
  Map<int, String> _knownNames() => {
        for (final a in widget.dispute.assignees)
          if (a.isUser && a.userId != null) a.userId!: a.label,
      };

  Future<void> _submit() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    // Taken before the request: the dialog can be gone by the time it answers (the back button
    // still closes it), and a gone dialog's ref throws, which would save the change without a
    // word and leave the page behind it on its old list.
    final container = ProviderScope.containerOf(context, listen: false);
    try {
      final id = widget.dispute.id;
      // The whole list every time, as `SetAssigneesRequest` takes it: an empty one leaves the
      // dispute assigned to nobody, which is a thing somebody may mean to say.
      await ref.read(dioProvider).patch('/api/disputes/$id/assignees',
          data: {'assignees': [for (final t in _assignees) t.toJson()]});
      container.invalidate(disputeDetailProvider(id));
      container.invalidate(scopedDisputesProvider);
      container.invalidate(tablePageProvider);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final narrow = MediaQuery.sizeOf(context).width < 600;
    // The seats a dispute offers and who holds each one on this one: its customer's POC book, and
    // the two the disputed record itself stores (L4).
    final rolesAsync = ref.watch(emailContextProvider(
        (type: EmailEntityType.dispute, entityId: widget.dispute.id, event: null)));

    return AlertDialog(
      title: const Text('Assign this dispute'),
      content: SizedBox(
        // A phone has nowhere near 520px to give (D-60).
        width: narrow ? double.maxFinite : 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AssigneePickerField(
                label: 'Assigned to',
                value: _assignees,
                onChanged: (tokens) => setState(() => _assignees = tokens),
                roleGroups: rolesAsync.valueOrNull?.roleGroups ?? const [],
                personNames: _knownNames(),
                searchPeople: (q) => searchAssigneePeople(ref.read(dioProvider), q),
                enabled: !_saving,
                emptyHint: 'Nobody — add people or roles below',
              ),
              if (rolesAsync.hasError)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    'Could not load who this dispute\'s roles reach: '
                    '${apiErrorMessage(rolesAsync.error!)}',
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Text(_error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
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
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
