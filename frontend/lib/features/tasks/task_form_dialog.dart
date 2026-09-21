import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/reference_picker.dart';
import '../../shared/models/assignee.dart';
import '../../shared/widgets/assignee_picker_field.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../../shared/widgets/status_chip.dart';
import 'task_actions.dart';
import 'task_providers.dart';

/// The longest title and notes the server keeps (`Task.TITLE_MAX`, `Task.NOTES_MAX`), so a field
/// stops taking input where the server would refuse it. They live here rather than in
/// core/field_limits.dart only because that file belongs to no one feature and is being edited
/// elsewhere in this change; they belong there.
const int _titleMax = 200;
const int _notesMax = 2000;

/// Raises or edits a task (T1). Resolves true once it is saved.
///
/// [type] and [entityId] name the record the work hangs off. They are given from a record's own
/// tab and left out on the Tasks list, where the form asks which record first — a task always
/// belongs to one, and there is nowhere else on that page to learn which.
///
/// [existing] edits instead of raising. The record is not offered there: moving a task to another
/// customer's invoice would take its assignees, its history and its customer scope with it, so a
/// task raised on the wrong record is deleted and raised again (T8).
Future<bool?> showTaskDialog({
  required BuildContext context,
  TaskEntityType? type,
  int? entityId,
  String? entityLabel,
  Task? existing,
}) =>
    showDialog<bool>(
      context: context,
      builder: (_) => _TaskFormDialog(
        type: type,
        entityId: entityId,
        entityLabel: entityLabel,
        existing: existing,
      ),
    );

class _TaskFormDialog extends ConsumerStatefulWidget {
  final TaskEntityType? type;
  final int? entityId;
  final String? entityLabel;
  final Task? existing;

  const _TaskFormDialog({this.type, this.entityId, this.entityLabel, this.existing});

  @override
  ConsumerState<_TaskFormDialog> createState() => _TaskFormDialogState();
}

class _TaskFormDialogState extends ConsumerState<_TaskFormDialog> {
  final _title = TextEditingController();
  final _notes = TextEditingController();

  /// The record the task hangs off. Fixed for an edit and when a record's tab opened the form;
  /// otherwise it is what the two fields below are for.
  late TaskEntityType _type;
  int? _entityId;
  String? _entityLabel;

  DateTime? _due;
  TaskStatus _status = TaskStatus.OPEN;

  /// The same tokens the email form's To field produces, so one widget serves both (A1).
  List<EmailToken> _assignees = const [];

  bool _saving = false;
  String? _recordError;
  String? _titleError;
  String? _error;

  bool get _isEdit => widget.existing != null;

  /// True while the record is the caller's to choose: only when raising a task from the list.
  bool get _picksRecord => !_isEdit && widget.entityId == null;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    if (e != null) {
      _type = e.entityType;
      _entityId = e.entityId;
      _entityLabel = e.entityLabel;
      _title.text = e.title;
      _notes.text = e.notes ?? '';
      _due = e.dueDate;
      _status = e.status;
      // The tokens that would produce these rows again, so an edit that only moves the due date
      // hands the same list back rather than clearing it.
      _assignees = e.assigneeTokens;
    } else {
      _type = widget.type ?? TaskEntityType.customer;
      _entityId = widget.entityId;
      _entityLabel = widget.entityLabel;
    }
  }

  @override
  void dispose() {
    _title.dispose();
    _notes.dispose();
    super.dispose();
  }

  /// Names for the people the task is already assigned to. They were picked before this form
  /// opened, so the field cannot name them itself — only the task knows what they are called.
  Map<int, String> _knownNames() => {
        for (final a in widget.existing?.assignees ?? const <Assignee>[])
          if (a.isUser && a.userId != null) a.userId!: a.label,
      };

  /// The chosen record, in the words the reference picker uses.
  ReferenceOption? get _record => _entityId == null
      ? null
      : ReferenceOption(_entityId!, _recordText());

  /// What the record is called: the server's label for it, else its kind and id.
  String _recordText() {
    final label = _entityLabel;
    return label == null || label.isEmpty ? '${_type.label} #$_entityId' : label;
  }

  Future<void> _submit() async {
    final title = _title.text.trim();
    setState(() {
      _titleError = title.isEmpty ? 'A task needs a title' : null;
      _recordError = _entityId == null ? 'Choose the record this task is about' : null;
      _error = null;
    });
    if (_titleError != null || _recordError != null) return;

    final canPick = ref.read(canPickAssigneesProvider);
    setState(() => _saving = true);
    // Taken before the request: the dialog can be gone by the time it answers (the back button
    // still closes it), and a gone dialog's ref throws, which would save the task without a word
    // and leave the lists behind it on their old rows.
    final container = ProviderScope.containerOf(context, listen: false);
    try {
      final dio = ref.read(dioProvider);
      // The whole of the task goes every time, not only what changed: the server reads a missing
      // dueDate or notes as *cleared*, because a JSON body cannot tell "absent" from "null" and
      // taking a due date off a task is an everyday edit (TaskDtos.UpdateTaskRequest).
      final body = {
        if (!_isEdit) 'entityType': _type.wire,
        if (!_isEdit) 'entityId': _entityId,
        'title': title,
        'dueDate': taskDueDateWire(_due),
        'status': _status.name,
        'notes': optionalText(_notes.text),
        // Left out entirely for a caller who was not shown the picker: a null list leaves the
        // assignees as they are, where an empty one would quietly unassign everybody (A1).
        if (canPick) 'assignees': [for (final t in _assignees) t.toJson()],
      };
      final id = widget.existing?.id;
      final res = id == null
          ? await dio.post('/api/tasks', data: body)
          : await dio.patch('/api/tasks/$id', data: body);
      invalidateTasks(container, id: id ?? ((res.data as Map)['id'] as num).toInt());
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
    final canPick = ref.watch(canPickAssigneesProvider);
    // Asked for by record where there is one, and by kind where there is not: a role picked
    // before the record is named is still a real seat, it just cannot yet say who is in it.
    final groups = ref.watch(assigneeRoleOptionsProvider((type: _type, entityId: _entityId)));

    return AlertDialog(
      title: Text(_isEdit ? 'Edit task' : 'New task'),
      content: SizedBox(
        // A phone has nowhere near 520px to give (D-60).
        width: narrow ? double.maxFinite : 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_picksRecord) ...[
                _recordKindField(),
                const SizedBox(height: 12),
                SearchPickerField<ReferenceOption>(
                  label: _type.label,
                  required: true,
                  errorText: _recordError,
                  value: _record,
                  labelOf: (o) => o.label,
                  subtitleOf: (o) => o.subtitle,
                  // The kinds a task hangs off are the three the filter bar's reference picker
                  // already searches, under the very names it knows them by.
                  search: (q) =>
                      ref.read(referenceOptionsProvider(ReferenceSearch(_type.noun, q)).future),
                  onChanged: (o) => setState(() {
                    _entityId = o.id;
                    _entityLabel = o.label;
                    _recordError = null;
                    // The roles on offer belong to the record; the old one's picks do not.
                    _assignees = [for (final t in _assignees) if (t.isUser) t];
                  }),
                ),
              ] else
                // Fixed, and said rather than offered: an edit cannot move a task to another
                // record, and a record's own tab already knows which one this is (T8).
                InputDecorator(
                  decoration: const InputDecoration(labelText: 'About'),
                  child: Text(_recordText()),
                ),
              const SizedBox(height: 12),
              TextField(
                controller: _title,
                autofocus: true,
                inputFormatters: [LengthLimitingTextInputFormatter(_titleMax)],
                decoration: InputDecoration(labelText: 'Title *', errorText: _titleError),
                onChanged: (_) {
                  if (_titleError != null) setState(() => _titleError = null);
                },
              ),
              const SizedBox(height: 12),
              // Side by side when there is room; stacked on a phone, where two fields in a row
              // cut their labels (D-60).
              Flex(
                direction: narrow ? Axis.vertical : Axis.horizontal,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Flexible(child: _dueDateField()),
                  const SizedBox(width: 12, height: 12),
                  Flexible(child: _statusField()),
                ],
              ),
              const SizedBox(height: 12),
              // A caller who may not search people or read a record's roles is given a form
              // without a picker rather than one that could only fail; a task nobody is assigned
              // is work nobody has picked up, which is allowed.
              if (canPick) ...[
                AssigneePickerField(
                  label: 'Assigned to',
                  value: _assignees,
                  onChanged: (tokens) => setState(() => _assignees = tokens),
                  roleGroups: groups.valueOrNull ?? const [],
                  // An existing task's own assignees were not picked here, so their names come
                  // with the task; anyone picked now is remembered by the field itself.
                  personNames: _knownNames(),
                  searchPeople: (q) => searchAssigneePeople(ref.read(dioProvider), q),
                  enabled: !_saving,
                ),
                if (groups.hasError)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      'Could not load who this record\'s roles reach: '
                      '${apiErrorMessage(groups.error!)}',
                      style: TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                  ),
                const SizedBox(height: 12),
              ],
              TextField(
                controller: _notes,
                maxLines: 3,
                inputFormatters: [LengthLimitingTextInputFormatter(_notesMax)],
                decoration: const InputDecoration(labelText: 'Notes'),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child:
                      Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
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
              : Text(_isEdit ? 'Save' : 'Raise task'),
        ),
      ],
    );
  }

  /// Which kind of record the task is about. Changing it drops the record chosen under the old
  /// kind — an invoice id means nothing to the customer list.
  Widget _recordKindField() => InputDecorator(
        decoration: const InputDecoration(labelText: 'About *'),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<TaskEntityType>(
            value: _type,
            isDense: true,
            isExpanded: true,
            items: [
              for (final t in TaskEntityType.values)
                DropdownMenuItem(value: t, child: Text(t.label)),
            ],
            onChanged: _saving
                ? null
                : (t) {
                    if (t == null || t == _type) return;
                    setState(() {
                      _type = t;
                      _entityId = null;
                      _entityLabel = null;
                      _assignees = [for (final token in _assignees) if (token.isUser) token];
                    });
                  },
          ),
        ),
      );

  Widget _dueDateField() => InkWell(
        onTap: _saving
            ? null
            : () async {
                final today = DateTime.now();
                // A year back and three forward is the range anyone picks in; the day already on
                // the task widens it where it has to, because a calendar that cannot open on the
                // date it is showing is an assertion, not a date picker.
                final due = _due;
                final first = today.subtract(const Duration(days: 365));
                final last = today.add(const Duration(days: 365 * 3));
                final picked = await showDatePicker(
                  context: context,
                  initialDate: due ?? today,
                  firstDate: due != null && due.isBefore(first) ? due : first,
                  lastDate: due != null && due.isAfter(last) ? due : last,
                );
                if (picked != null) setState(() => _due = picked);
              },
        child: InputDecorator(
          decoration: InputDecoration(
            labelText: 'Due',
            // A task need not have a due date, and taking one off is an everyday edit, so the
            // field carries its own way to clear it.
            suffixIcon: _due == null
                ? const Icon(Icons.calendar_today, size: 18)
                : IconButton(
                    tooltip: 'No due date',
                    icon: const Icon(Icons.clear, size: 18),
                    onPressed: _saving ? null : () => setState(() => _due = null),
                  ),
          ),
          child: Text(_due == null ? 'No due date' : formatDate(_due)),
        ),
      );

  Widget _statusField() => InputDecorator(
        decoration: const InputDecoration(labelText: 'Status'),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<TaskStatus>(
            value: _status,
            isDense: true,
            isExpanded: true,
            items: [
              // The chip rather than the words alone, so the status reads here exactly as it
              // will on the row this form is about to change.
              for (final s in TaskStatus.values)
                DropdownMenuItem(
                  value: s,
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: TaskStatusChip(status: s),
                  ),
                ),
            ],
            onChanged:
                _saving ? null : (s) => setState(() => _status = s ?? _status),
          ),
        ),
      );
}

