import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../assignees/assignee_providers.dart';
import '../assignees/recipient_chips_field.dart';
import '../email/email_models.dart';
import 'task_models.dart';

/// Creates a task on a record, or edits [existing]: title, description, due date and assignees
/// (A6). Resolves with the saved task, or null when nothing was saved.
Future<TaskItem?> showTaskDialog(
  BuildContext context, {
  required TaskEntityType entityType,
  required int entityId,
  String? entityLabel,
  TaskItem? existing,
}) =>
    showDialog<TaskItem>(
      context: context,
      builder: (_) => TaskFormDialog(
        entityType: entityType,
        entityId: entityId,
        entityLabel: entityLabel,
        existing: existing,
      ),
    );

class TaskFormDialog extends ConsumerStatefulWidget {
  final TaskEntityType entityType;
  final int entityId;
  final String? entityLabel;
  final TaskItem? existing;

  const TaskFormDialog({
    super.key,
    required this.entityType,
    required this.entityId,
    this.entityLabel,
    this.existing,
  });

  @override
  ConsumerState<TaskFormDialog> createState() => _TaskFormDialogState();
}

class _TaskFormDialogState extends ConsumerState<TaskFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _title =
      TextEditingController(text: widget.existing?.title ?? '');
  late final TextEditingController _description =
      TextEditingController(text: widget.existing?.description ?? '');
  late DateTime? _due = widget.existing?.dueDate;
  late List<EmailToken> _assignees = [
    for (final a in widget.existing?.assignees ?? const []) a.token,
  ];

  /// The names that came with the task, for the people already on it.
  late final Map<EmailToken, String> _knownLabels = {
    for (final a in widget.existing?.assignees ?? const [])
      a.token: a.people.length == 1 && a.token.isUser ? a.people.single.name : a.label,
  };

  Map<String, String> _fieldErrors = const {};
  String? _error;
  bool _saving = false;

  bool get _isEdit => widget.existing != null;

  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _pickDue() async {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final initial = _due ?? today;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: initial.isBefore(today) ? initial : today.subtract(const Duration(days: 365)),
      // A rule may have set a date further out than the usual five years; the picker must still open.
      lastDate: initial.isAfter(today.add(const Duration(days: 365 * 5)))
          ? initial
          : today.add(const Duration(days: 365 * 5)),
    );
    if (picked != null && mounted) setState(() => _due = picked);
  }

  Map<String, dynamic> _createBody() => {
        'entityType': widget.entityType.wire,
        'entityId': widget.entityId,
        'title': _title.text.trim(),
        'description': optionalText(_description.text),
        'dueDate': _due == null ? null : taskDateWire(_due!),
        'assignees': [for (final t in _assignees) t.toJson()],
      };

  /// Only what changed, and the version it was read at (§6.12).
  Map<String, dynamic> _patchBody(TaskItem t) {
    final title = _title.text.trim();
    final description = _description.text.trim();
    final dueChanged = _due != t.dueDate;
    return {
      if (title != t.title) 'title': title,
      if (description != (t.description ?? '')) 'description': description,
      if (dueChanged && _due != null) 'dueDate': taskDateWire(_due!),
      if (dueChanged && _due == null) 'clearDueDate': true,
      if (!listEquals(_assignees, [for (final a in t.assignees) a.token]))
        'assignees': [for (final a in _assignees) a.toJson()],
      'version': t.version,
    };
  }

  Future<void> _submit() async {
    setState(() {
      _fieldErrors = const {};
      _error = null;
    });
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final existing = widget.existing;
    final body = existing == null ? _createBody() : _patchBody(existing);
    if (existing != null && body.length == 1) {
      Navigator.of(context).pop();
      return;
    }
    setState(() => _saving = true);
    try {
      final dio = ref.read(dioProvider);
      final res = existing == null
          ? await dio.post('/api/tasks', data: body)
          : await dio.patch('/api/tasks/${existing.id}', data: body);
      if (!mounted) return;
      Navigator.of(context).pop(TaskItem.fromJson((res.data as Map).cast<String, dynamic>()));
    } catch (e) {
      if (!mounted) return;
      final fields = _fieldErrorsOf(e);
      setState(() {
        _fieldErrors = fields;
        _error = fields.isNotEmpty && fields.keys.every(_shownAtField)
            ? null
            : _stale(e)
                ? 'Someone else changed this task while you were editing it. Close this and '
                    'open it again to see their change.'
                : apiErrorMessage(e);
      });
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  static bool _stale(Object e) => e is DioException && e.response?.statusCode == 409;

  static bool _shownAtField(String key) =>
      key == 'title' || key == 'description' || key == 'dueDate' || key.startsWith('assignees');

  /// A complaint about a person who cannot see the record reads under the assignees (§6.12).
  String? get _assigneesError {
    final messages = [
      for (final e in _fieldErrors.entries)
        if (e.key.startsWith('assignees')) e.value,
    ];
    return messages.isEmpty ? null : messages.join('\n');
  }

  static Map<String, String> _fieldErrorsOf(Object error) {
    final data = error is DioException ? error.response?.data : null;
    final fields = data is Map ? data['fieldErrors'] : null;
    if (fields is! Map) return const {};
    return {for (final e in fields.entries) '${e.key}': '${e.value}'};
  }

  @override
  Widget build(BuildContext context) {
    final type = widget.entityType;
    final contextAsync =
        ref.watch(assigneeContextProvider((type: type.emailType, entityId: widget.entityId)));
    final dio = ref.watch(dioProvider);
    final record = widget.entityLabel ?? widget.existing?.entityLabel;

    return AlertDialog(
      title: Text(_isEdit ? 'Edit task' : 'New task${record == null ? '' : ' • $record'}'),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 520,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  key: const ValueKey('task-title'),
                  controller: _title,
                  autofocus: !_isEdit,
                  decoration: InputDecoration(
                    labelText: 'Title *',
                    errorText: _fieldErrors['title'],
                  ),
                  inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.taskTitle)],
                  validator: (v) =>
                      (v ?? '').trim().isEmpty ? 'Say what needs doing' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  key: const ValueKey('task-description'),
                  controller: _description,
                  minLines: 2,
                  maxLines: 5,
                  decoration: InputDecoration(
                    labelText: 'Description',
                    errorText: _fieldErrors['description'],
                  ),
                  inputFormatters: [
                    LengthLimitingTextInputFormatter(FieldLimits.taskDescription),
                  ],
                ),
                const SizedBox(height: 12),
                InkWell(
                  key: const ValueKey('task-due'),
                  onTap: _saving ? null : _pickDue,
                  child: InputDecorator(
                    decoration: InputDecoration(
                      labelText: 'Due date',
                      errorText: _fieldErrors['dueDate'],
                      suffixIcon: _due == null
                          ? const Icon(Icons.calendar_today, size: 18)
                          : IconButton(
                              key: const ValueKey('task-due-clear'),
                              tooltip: 'No due date',
                              icon: const Icon(Icons.clear, size: 18),
                              onPressed: _saving ? null : () => setState(() => _due = null),
                            ),
                    ),
                    child: Text(_due == null ? 'None' : formatDate(_due)),
                  ),
                ),
                const SizedBox(height: 12),
                contextAsync.when(
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
                        onPressed: () => ref.invalidate(assigneeContextProvider),
                        child: const Text('Retry'),
                      ),
                    ],
                  ),
                  data: (ctx) => RecipientChipsField(
                    key: const ValueKey('task-assignees'),
                    ctx: ctx,
                    value: _assignees,
                    enabled: !_saving,
                    knownLabels: _knownLabels,
                    errorText: _assigneesError,
                    searchPeople: (q) => searchAssignablePeople(dio, q,
                        entityType: type.emailType, entityId: widget.entityId),
                    onChanged: (tokens) => setState(() => _assignees = tokens),
                  ),
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(_error!,
                        style: TextStyle(color: Theme.of(context).colorScheme.error)),
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
          key: const ValueKey('task-save'),
          onPressed: _saving ? null : _submit,
          child: Text(_isEdit ? 'Save' : 'Create task'),
        ),
      ],
    );
  }
}
