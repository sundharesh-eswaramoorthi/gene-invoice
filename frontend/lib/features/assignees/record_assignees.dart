import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/record_assignee.dart';
import '../email/email_entity.dart';
import '../email/email_models.dart';
import 'assignee_providers.dart';
import 'recipient_chips_field.dart';

/// Who a promise or a dispute is assigned to (§6.13), as the server resolved each token today —
/// a role reads with whoever holds it now, its primary holder first — and, for someone who may,
/// "Edit".
class RecordAssigneesView extends StatelessWidget {
  final List<RecordAssignee> assignees;

  /// Opens the editor; null when this user may not change them.
  final VoidCallback? onEdit;
  const RecordAssigneesView({super.key, required this.assignees, this.onEdit});

  static String _text(RecordAssignee a) {
    if (a.token.isUser && a.people.length == 1) return a.people.single.name;
    final people = [...a.people]..sort((x, y) => (y.primary ? 1 : 0) - (x.primary ? 1 : 0));
    return [
      a.label,
      if (people.isNotEmpty) people.map((p) => p.name).join(', '),
      if (a.unresolved) 'nobody assigned',
    ].join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (assignees.isEmpty) Text('Nobody', style: TextStyle(color: theme.hintColor)),
        for (final a in assignees)
          Tooltip(
            message: a.unresolved ? 'Nobody holds this now, so nobody is told' : '',
            child: Chip(
              key: ValueKey('record-assignee-${assigneeTokenKey(a.token)}'),
              avatar: Icon(
                a.unresolved
                    ? Icons.warning_amber_outlined
                    : a.token.isUser
                        ? Icons.person_outline
                        : Icons.badge_outlined,
                size: 18,
                color: a.unresolved ? theme.colorScheme.error : null,
              ),
              label: Text(_text(a), overflow: TextOverflow.ellipsis),
            ),
          ),
        if (onEdit != null)
          TextButton.icon(
            key: const ValueKey('assignees-edit'),
            icon: const Icon(Icons.edit_outlined, size: 18),
            label: const Text('Edit'),
            onPressed: onEdit,
          ),
      ],
    );
  }
}

/// Edits the assignees of the [type] record [entityId] and PUTs them to [path] (§6.13). True once
/// they are saved.
Future<bool> showRecordAssigneesDialog(
  BuildContext context, {
  required EmailEntityType type,
  required int entityId,
  required String path,
  required String title,
  required List<RecordAssignee> current,
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (_) => _RecordAssigneesDialog(
        type: type,
        entityId: entityId,
        path: path,
        title: title,
        current: current,
      ),
    ) ??
    false;

class _RecordAssigneesDialog extends ConsumerStatefulWidget {
  final EmailEntityType type;
  final int entityId;
  final String path;
  final String title;
  final List<RecordAssignee> current;

  const _RecordAssigneesDialog({
    required this.type,
    required this.entityId,
    required this.path,
    required this.title,
    required this.current,
  });

  @override
  ConsumerState<_RecordAssigneesDialog> createState() => _RecordAssigneesDialogState();
}

class _RecordAssigneesDialogState extends ConsumerState<_RecordAssigneesDialog> {
  late List<EmailToken> _tokens = [for (final a in widget.current) a.token];

  /// The names that came with the record, for the people already on it.
  late final Map<EmailToken, String> _known = {
    for (final a in widget.current)
      a.token: a.token.isUser && a.people.length == 1 ? a.people.single.name : a.label,
  };

  bool _saving = false;
  String? _fieldError;
  String? _error;

  Future<void> _save() async {
    if (listEquals(_tokens, [for (final a in widget.current) a.token])) {
      Navigator.of(context).pop(false);
      return;
    }
    setState(() {
      _saving = true;
      _fieldError = null;
      _error = null;
    });
    try {
      await ref.read(dioProvider).put(widget.path, data: {
        'assignees': [for (final t in _tokens) t.toJson()],
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      // Someone who cannot see the record is refused under the field (§6.13).
      final data = e is DioException ? e.response?.data : null;
      final fields = data is Map && data['fieldErrors'] is Map ? data['fieldErrors'] as Map : {};
      final mine = [
        for (final entry in fields.entries)
          if ('${entry.key}'.startsWith('assignees')) '${entry.value}',
      ];
      setState(() {
        _fieldError = mine.isEmpty ? null : mine.join('\n');
        _error = mine.isEmpty ? apiErrorMessage(e) : null;
      });
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final key = (type: widget.type, entityId: widget.entityId);
    final dio = ref.watch(dioProvider);
    return AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 520,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              ref.watch(assigneeContextProvider(key)).when(
                    loading: () => const Padding(
                      padding: EdgeInsets.symmetric(vertical: 12),
                      child: LinearProgressIndicator(),
                    ),
                    error: (e, _) => Row(
                      children: [
                        Expanded(
                          child: Text('Assignees unavailable: ${apiErrorMessage(e)}',
                              style: TextStyle(color: Theme.of(context).colorScheme.error)),
                        ),
                        TextButton(
                          onPressed: () => ref.invalidate(assigneeContextProvider(key)),
                          child: const Text('Retry'),
                        ),
                      ],
                    ),
                    data: (ctx) => RecipientChipsField(
                      key: const ValueKey('record-assignees-field'),
                      ctx: ctx,
                      value: _tokens,
                      enabled: !_saving,
                      knownLabels: _known,
                      errorText: _fieldError,
                      searchPeople: (q) => searchAssignablePeople(dio, q,
                          entityType: widget.type, entityId: widget.entityId),
                      onChanged: (tokens) => setState(() {
                        _tokens = tokens;
                        _fieldError = null;
                      }),
                    ),
                  ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const ValueKey('record-assignees-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
