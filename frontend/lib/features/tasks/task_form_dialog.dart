import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../../core/table/reference_picker.dart';
import '../auth/auth_controller.dart';
import 'task_providers.dart';

/// Creates or edits a task. Resolves true once it is saved (A6).
///
/// [type], [entityId] and [entityLabel] pin the subject when the dialog is opened from a record's
/// Tasks tab: the task is about THAT record and the subject picker is not offered. Opened from the
/// list with none of them, the subject is chosen here. [regionId] is the branch that record lives
/// in, and is what the assignee picker is opened for (A6, B1).
Future<bool?> showTaskDialog({
  required BuildContext context,
  Task? existing,
  TaskEntityType? type,
  int? entityId,
  String? entityLabel,
  int? regionId,
}) =>
    showDialog<bool>(
      context: context,
      builder: (_) => _TaskFormDialog(
        existing: existing,
        type: type,
        entityId: entityId,
        entityLabel: entityLabel,
        regionId: regionId,
      ),
    );

class _TaskFormDialog extends ConsumerStatefulWidget {
  final Task? existing;
  final TaskEntityType? type;
  final int? entityId;
  final String? entityLabel;
  final int? regionId;

  const _TaskFormDialog({
    this.existing,
    this.type,
    this.entityId,
    this.entityLabel,
    this.regionId,
  });

  @override
  ConsumerState<_TaskFormDialog> createState() => _TaskFormDialogState();
}

class _TaskFormDialogState extends ConsumerState<_TaskFormDialog> {
  final _title = TextEditingController();
  final _notes = TextEditingController();

  late TaskEntityType _type;
  ReferenceOption? _subject;
  DateTime? _due;
  final List<ReferenceOption> _assignees = [];
  bool _saving = false;
  String? _error;

  bool get _isEdit => widget.existing != null;

  /// The subject cannot be moved once the task exists: PATCH carries no entityType or entityId,
  /// because a task about a different record is a different task (A6).
  bool get _subjectFixed => _isEdit || widget.entityId != null;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _type = e?.entityType ?? widget.type ?? TaskEntityType.customer;
    if (e != null) {
      _title.text = e.title;
      _notes.text = e.notes ?? '';
      _due = e.dueDate;
      for (final a in e.assignees) {
        if (a.userId != null) _assignees.add(ReferenceOption(a.userId!, a.name));
      }
      if (e.entityId != null) _subject = ReferenceOption(e.entityId!, e.recordLabel);
    } else if (widget.entityId != null) {
      _subject = ReferenceOption(widget.entityId!, widget.entityLabel ?? '#${widget.entityId}');
    }
  }

  @override
  void dispose() {
    _title.dispose();
    _notes.dispose();
    super.dispose();
  }

  /// The branches the pickers this dialog opens should ask about: the one the record it was opened
  /// from lives in, else every branch this person works in. A picker that names no branch is only
  /// ever right for a wildcard holder, and for them the server answers across the company (B1, R8).
  List<int> get _regionIds => widget.regionId != null
      ? [widget.regionId!]
      : pickerRegionsFor(ref.read(currentUserProvider));

  Future<void> _submit() async {
    final title = _title.text.trim();
    if (title.isEmpty) {
      setState(() => _error = 'Give the task a title');
      return;
    }
    if (!_isEdit && _subject == null) {
      setState(() => _error = 'Choose the ${_type.noun} this task is about');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      final ids = _assignees.map((a) => a.id).toList();
      if (_isEdit) {
        // PATCH: title and assigneeUserIds are left alone when null and REPLACED when sent, while
        // notes and dueDate are replaced by whatever arrives, null included — so "this no longer
        // has a due date" is expressible. Both are therefore always sent from a form that shows
        // them both (A6).
        await dio.patch('/api/tasks/${widget.existing!.id}', data: {
          'title': title,
          'notes': optionalText(_notes.text),
          'dueDate': _due == null ? null : DateFormat('yyyy-MM-dd').format(_due!),
          'assigneeUserIds': ids,
        });
      } else {
        await dio.post('/api/tasks', data: {
          'entityType': _type.wire,
          'entityId': _subject!.id,
          'title': title,
          'notes': optionalText(_notes.text),
          if (_due != null) 'dueDate': DateFormat('yyyy-MM-dd').format(_due!),
          'assigneeUserIds': ids,
        });
      }
      invalidateTasks(ref, widget.existing?.id);
      if (mounted) Navigator.of(context).pop(true);
    } on DioException catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = ref.watch(currentUserProvider);
    final alreadyMine = me != null && _assignees.any((a) => a.id == me.id);

    return AlertDialog(
      title: Text(_isEdit ? 'Edit task' : 'New task'),
      content: SizedBox(
        // A phone has nowhere near 520px to give (D-60).
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_subjectFixed)
                InputDecorator(
                  decoration: const InputDecoration(labelText: 'About'),
                  child: Text('${_type.label} • ${_subject?.label ?? '—'}'),
                )
              else ...[
                DropdownButtonFormField<TaskEntityType>(
                  initialValue: _type,
                  decoration: const InputDecoration(labelText: 'About *'),
                  items: [
                    for (final t in TaskEntityType.values)
                      DropdownMenuItem(value: t, child: Text(t.label)),
                  ],
                  onChanged: (t) => setState(() {
                    if (t == null || t == _type) return;
                    _type = t;
                    // A record of the old kind is not a record of the new one, and sending its id
                    // under a different entityType would name a different row entirely (A6).
                    _subject = null;
                  }),
                ),
                const SizedBox(height: 12),
                _pickerRow(
                  label: '${_type.label} *',
                  value: _subject?.label,
                  placeholder: 'Choose a ${_type.noun}',
                  onPick: () async {
                    final picked = await _pickReference(
                        context: context,
                        title: 'Choose a ${_type.noun}',
                        kind: _type.noun,
                        regionIds: _regionIds);
                    if (picked != null) setState(() => _subject = picked);
                  },
                ),
              ],
              const SizedBox(height: 12),
              TextField(
                controller: _title,
                autofocus: true,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.taskTitle)],
                decoration: const InputDecoration(labelText: 'Title *'),
              ),
              const SizedBox(height: 12),
              InkWell(
                onTap: () async {
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: _due ?? DateTime.now(),
                    firstDate: DateTime.now().subtract(const Duration(days: 365)),
                    lastDate: DateTime.now().add(const Duration(days: 365 * 3)),
                  );
                  if (picked != null) setState(() => _due = picked);
                },
                child: InputDecorator(
                  decoration: InputDecoration(
                    labelText: 'Due',
                    suffixIcon: _due == null
                        ? const Icon(Icons.calendar_today, size: 18)
                        : IconButton(
                            tooltip: 'No due date',
                            icon: const Icon(Icons.clear, size: 18),
                            onPressed: () => setState(() => _due = null),
                          ),
                  ),
                  child: Text(_due == null ? 'No due date' : formatDate(_due)),
                ),
              ),
              const SizedBox(height: 12),
              Text('Assigned to', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 4),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  for (final a in _assignees)
                    InputChip(
                      avatar: const Icon(Icons.person_outline, size: 16),
                      label: Text(a.label),
                      onDeleted: () => setState(() => _assignees.remove(a)),
                    ),
                  if (me != null && !alreadyMine)
                    ActionChip(
                      avatar: const Icon(Icons.person_add_alt, size: 16),
                      label: const Text('Assign to me'),
                      onPressed: () => setState(() =>
                          _assignees.add(ReferenceOption(me.id, me.fullName ?? me.username))),
                    ),
                  ActionChip(
                    avatar: const Icon(Icons.group_add_outlined, size: 16),
                    label: const Text('Add someone'),
                    onPressed: () async {
                      // The SAME picker the assigneeUserId filter column declares, so the list's
                      // filter bar and this form offer the same people and no new endpoint is
                      // bought. NAMED LIMIT: /api/pocs/assignable answers with people assignable as
                      // SOME kind of POC, while the server accepts ANY active internal user — so
                      // somebody assignable as no POC cannot be picked here although a save naming
                      // them would succeed. Widening that is a UI-only change (A6, B1).
                      final picked = await _pickReference(
                          context: context,
                          title: 'Assign someone',
                          kind: 'pocUser',
                          regionIds: _regionIds);
                      if (picked == null) return;
                      setState(() {
                        if (!_assignees.any((a) => a.id == picked.id)) _assignees.add(picked);
                      });
                    },
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _notes,
                maxLines: 3,
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.taskNotes)],
                decoration: const InputDecoration(labelText: 'Notes'),
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
              : Text(_isEdit ? 'Save' : 'Create task'),
        ),
      ],
    );
  }

  Widget _pickerRow({
    required String label,
    required String? value,
    required String placeholder,
    required VoidCallback onPick,
  }) =>
      InkWell(
        onTap: onPick,
        child: InputDecorator(
          decoration: InputDecoration(
            labelText: label,
            suffixIcon: const Icon(Icons.search, size: 18),
          ),
          child: Text(value ?? placeholder),
        ),
      );
}

/// One search, in a dialog of its own, so a form with two references does not stack two 220px
/// lists on top of each other. The picker itself is the shared [ReferencePicker] — every kind it
/// already knows about is available here without a line of new search code (A6).
Future<ReferenceOption?> _pickReference({
  required BuildContext context,
  required String title,
  required String kind,
  required List<int> regionIds,
}) {
  return showDialog<ReferenceOption>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(title),
      content: SizedBox(
        width: 420,
        child: ReferencePicker(
          kind: kind,
          value: null,
          regionIds: regionIds,
          onChanged: (o) => Navigator.of(dialogContext).pop(o),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(), child: const Text('Cancel')),
      ],
    ),
  );
}
