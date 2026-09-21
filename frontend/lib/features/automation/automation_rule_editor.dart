import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../core/table/filter_editor.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/assignee.dart' show AssigneePerson;
import '../../shared/widgets/assignee_picker_field.dart';
import '../email/email_actions.dart' show canSendEmailProvider;
import '../email/email_models.dart';
import '../email/email_providers.dart';
import '../email/send_email_dialog.dart' show pickEmailPerson;
import 'automation_models.dart';
import 'automation_providers.dart';

/// Writes a rule, or edits one whole (R1). Resolves true once it is saved.
///
/// An existing rule is named by id rather than handed over as a row: the form reloads it, so an
/// edit always starts from what the server holds now and cannot quietly put a stale copy back.
Future<bool?> showAutomationRuleEditor(BuildContext context, {int? ruleId}) {
  return showDialog<bool>(
    context: context,
    // A tap beside the form must not throw away a half-written rule.
    barrierDismissible: false,
    builder: (_) => AutomationRuleEditor(ruleId: ruleId),
  );
}

/// The form a rule is written on. Three sections, labelled as the rule reads: WHEN something
/// happens to a kind of record, WHERE the record matches these filters, THEN do this to it.
///
/// The WHERE is the list page's own filter editor, against the schema of the very table that kind
/// of record is listed from. That is not a convenience: a rule may only say what somebody could
/// have typed into that filter bar, the server checks it against that same schema when the rule is
/// written, and a second filter UI here would be a second opinion about what a filter means (R9).
class AutomationRuleEditor extends ConsumerStatefulWidget {
  final int? ruleId;
  const AutomationRuleEditor({super.key, this.ruleId});

  @override
  ConsumerState<AutomationRuleEditor> createState() => _AutomationRuleEditorState();
}

class _AutomationRuleEditorState extends ConsumerState<AutomationRuleEditor> {
  final _name = TextEditingController();
  final _description = TextEditingController();

  /// The task's title or the email's subject.
  final _title = TextEditingController();

  /// The email's body, the task's notes, the promise's notes, or the dispute's reason.
  final _body = TextEditingController();
  final _dueInDays = TextEditingController();
  final _amount = TextEditingController();

  bool _enabled = true;
  AutomationRecordType _type = AutomationRecordType.invoice;
  AutomationTrigger _trigger = AutomationTrigger.created;
  List<TableFilter> _filters = const [];
  AutomationAction _action = AutomationAction.createTask;
  AutomationTaskStatus? _taskStatus;

  /// The email's From. Null is "not chosen yet", not a default: the server refuses a SEND_EMAIL
  /// rule with no sender, because a rule has no caller to send as
  /// when it is left out.
  EmailToken? _from;

  /// Names for a sender picked by hand: a token carries only the id.
  final Map<int, EmailPerson> _senderPeople = {};

  /// The assignees, or the email's recipients — one field, because it is one picker.
  List<EmailToken> _people = const [];

  /// Whether an email rule also writes to the customer's own addresses. Held apart from [_people]
  /// because it is not an assignee: no person is answerable for it, and only email can use it.
  bool _toCustomer = false;

  bool _seeded = false;
  bool _saving = false;
  String? _error;
  String? _peopleError;

  /// What was changed for the author rather than by them — filters dropped because they name a
  /// column the new kind of record has not got, an action the new kind cannot take. Never silent:
  /// a rule that lost half its WHERE without a word would go on running as something else.
  String? _notice;

  bool get _isEdit => widget.ruleId != null;

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    _title.dispose();
    _body.dispose();
    _dueInDays.dispose();
    _amount.dispose();
    super.dispose();
  }

  // ---- loading an existing rule ------------------------------------------------------

  /// Fills the form from the loaded rule, once. A rule written for a newer server — a kind of
  /// record, a trigger or an action this build does not know — cannot be edited without being
  /// rewritten as something else, so that is said rather than guessed at.
  void _seed(AutomationRule rule) {
    if (_seeded) return;
    _seeded = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      setState(() {
        _name.text = rule.name;
        _description.text = rule.description ?? '';
        _enabled = rule.enabled;
        _type = rule.entityType ?? _type;
        _trigger = rule.trigger ?? _trigger;
        _filters = rule.filterChips;
        _action = rule.action ?? _action;
        final spec = rule.actionSpec;
        _title.text = spec.title ?? '';
        _body.text = spec.body ?? '';
        _dueInDays.text = spec.dueInDays?.toString() ?? '';
        _amount.text = spec.amount?.toStringAsFixed(2) ?? '';
        _taskStatus = AutomationTaskStatus.fromWire(spec.status);
        _from = spec.from;
        _people = [for (final t in spec.assignees) if (!t.isCustomer) t];
        _toCustomer = spec.assignees.any((t) => t.isCustomer);
        final unknown = [
          if (rule.entityType == null) 'the kind of record',
          if (rule.trigger == null) 'what sets it off',
          if (rule.action == null) 'what it does',
        ];
        final notices = [
          if (unknown.isNotEmpty)
            'This rule was written for a newer version of the app: ${unknown.join(', ')} '
                'could not be read, and saving would rewrite it. Cancel unless you mean to.',
          if (rule.unreadableFilters.isNotEmpty)
            'These filters could not be read and would be dropped if you save: '
                '${rule.unreadableFilters.join(', ')}',
        ];
        _notice = notices.isEmpty ? null : notices.join('\n\n');
      });
    });
  }

  // ---- the WHEN ----------------------------------------------------------------------

  /// Moving the rule to another kind of record. The WHERE is the new kind's list filters, so a
  /// filter naming a column it has not got cannot come along — and is named on its way out, not
  /// dropped behind the author's back. The THEN can fall away too: there is nothing on a customer
  /// to raise a dispute about.
  Future<void> _setType(AutomationRecordType type) async {
    if (type == _type) return;
    final previousType = _type;
    final previousAction = _action;
    // A dispute is about one invoice or one payment; on a customer there is nothing to dispute,
    // and the server refuses the pairing when the rule is written.
    final nextAction =
        previousAction.allowedOn(type) ? previousAction : AutomationAction.createTask;
    // Who a role reaches is read against the record on the run, but which roles a kind of record
    // offers at all is fixed by its kind. A task's and an email's come from the record itself, so
    // they have to be picked again; a promise's and a dispute's come from the promise or the
    // dispute, which the record type does not change — those picks stand.
    final bookChanged =
        previousAction.peopleType(previousType) != nextAction.peopleType(type);
    final notices = [
      if (nextAction != previousAction)
        'A dispute is about one invoice or one payment, so "${previousAction.label}" cannot be '
            'done to ${type.nounWithArticle}. The rule now creates a task instead.',
      if (bookChanged && _people.isNotEmpty)
        'The people this rule is for were picked against the other kind of record, which offers '
            'different roles, so they have been cleared. Pick them again below.',
    ];
    setState(() {
      _type = type;
      _action = nextAction;
      _peopleError = null;
      if (bookChanged) _people = const [];
      if (nextAction != AutomationAction.sendEmail) {
        _toCustomer = false;
        _from = null;
      }
      _notice = notices.isEmpty ? null : notices.join('\n\n');
    });
    if (_filters.isEmpty) return;

    // The new kind's schema decides which filters survive, so it has to be in hand before any is
    // dropped — a filter is not wrong merely because its schema has not arrived yet.
    final TableSchema schema;
    try {
      schema = await ref.read(tableSchemaProvider(type.tableEntity).future);
    } catch (e) {
      if (mounted) {
        setState(() => _notice = [
              if (notices.isNotEmpty) notices.join('\n\n'),
              'Could not read what ${type.tableEntity} can be filtered by: ${apiErrorMessage(e)}. '
                  'Check the filters below before saving.',
            ].join('\n\n'));
      }
      return;
    }
    if (!mounted) return;
    final kept = [
      for (final f in _filters)
        if (schema.column(f.field)?.filterable ?? false) f,
    ];
    final dropped = [
      for (final f in _filters)
        if (!kept.contains(f)) describeFilter(f, null),
    ];
    if (dropped.isEmpty) return;
    setState(() {
      _filters = kept;
      _notice = [
        if (notices.isNotEmpty) notices.join('\n\n'),
        '${type.label}s cannot be filtered by ${dropped.join(', ')}, so '
            '${dropped.length == 1 ? 'that filter has' : 'those filters have'} been removed.',
      ].join('\n\n');
    });
  }

  // ---- the WHERE ---------------------------------------------------------------------

  Future<void> _addFilter(TableSchema schema) async {
    if (_filters.length >= AutomationLimits.maxFilters) return;
    final added = await showFilterEditor(context: context, schema: schema);
    if (added == null || !mounted) return;
    setState(() {
      // One column and operator at a time, as a list page's own filter bar does.
      _filters = [
        for (final f in _filters)
          if (f.field != added.field || f.operator != added.operator) f,
        added,
      ];
    });
  }

  Future<void> _editFilter(TableSchema schema, TableFilter existing) async {
    final edited = await showFilterEditor(context: context, schema: schema, existing: existing);
    if (edited == null || !mounted) return;
    setState(() {
      _filters = [
        for (final f in _filters)
          if (f == existing) edited else f,
      ];
    });
  }

  // ---- the THEN ----------------------------------------------------------------------

  void _setAction(AutomationAction action) {
    if (action == _action) return;
    // A promise and a dispute are records of their own, so their assignees come from a different
    // book than a task's or an email's. Carrying the picks across would keep roles the new kind
    // does not offer, which the server refuses when the rule is saved.
    final previous = _action;
    final sameBook = previous.peopleType(_type) == action.peopleType(_type);
    final hadPeople = _people.isNotEmpty;
    setState(() {
      _action = action;
      _peopleError = null;
      _notice = sameBook || !hadPeople
          ? null
          : 'The people this rule is for were picked for "${previous.label}", which draws on a '
              'different set of roles, so they have been cleared. Pick them again below.';
      if (!sameBook) _people = const [];
      if (action != AutomationAction.sendEmail) {
        _toCustomer = false;
        _from = null;
      }
    });
  }

  Future<void> _pickSender() async {
    final person = await pickEmailPerson(context, ref.read(dioProvider),
        title: 'Send as someone else');
    if (person?.userId == null || !mounted) return;
    _senderPeople[person!.userId!] = person;
    setState(() => _from = EmailToken.user(person.userId!));
  }

  /// Who the action is for, as the API takes it: the picked people, and the customer's own
  /// addresses when an email rule asked for them.
  List<EmailToken> get _tokens =>
      [..._people, if (_toCustomer) const EmailToken.customer()];

  /// The people the "Person…" chip searches: the same internal users the compose form offers,
  /// in the shape the picker reads them in.
  Future<List<AssigneePerson>> _searchPeople(String search) async {
    final people = await searchEmailPeople(ref.read(dioProvider), search);
    return [
      for (final person in people)
        AssigneePerson(userId: person.userId, name: person.name, address: person.email),
    ];
  }

  // ---- saving ------------------------------------------------------------------------

  /// What the form can tell before asking. Everything here is also checked by the server, which
  /// stays the authority — this only spares a round trip for the obvious, and each message names
  /// the box to go back to rather than saying the rule is invalid.
  String? _check() {
    if (_name.text.trim().isEmpty) return 'Give the rule a name';
    switch (_action) {
      case AutomationAction.createTask:
        if (_title.text.trim().isEmpty) return 'A task rule needs a title';
      case AutomationAction.createPromise:
        final amount = parseMoneyInput(_amount.text);
        if (amount == null || amount <= 0) {
          return 'A promise rule needs an amount greater than zero, with at most 2 decimals';
        }
      case AutomationAction.createDispute:
        if (_body.text.trim().isEmpty) return 'A dispute rule needs a reason';
      case AutomationAction.sendEmail:
        if (_title.text.trim().isEmpty) return 'An email rule needs a subject';
        if (_tokens.isEmpty) {
          setState(() => _peopleError = 'Add at least one recipient');
          return 'An email rule needs at least one recipient';
        }
        // A rule has nobody to send as unless it names somebody: the server refuses a senderless
        // email rule outright, so the form says so here rather than letting the save round-trip.
        if (_from == null) {
          return 'An email rule needs a sender: pick who it goes out as';
        }
    }
    // Only where this action has a day count to offer: a number left behind by another action is
    // not sent, so it must not be able to stop a save either.
    final days = _action == AutomationAction.createTask ||
            _action == AutomationAction.createPromise
        ? _dueInDays.text.trim()
        : '';
    if (days.isNotEmpty) {
      final parsed = int.tryParse(days);
      if (parsed == null || parsed < 0 || parsed > AutomationLimits.maxDueInDays) {
        return 'The number of days must be between 0 and ${AutomationLimits.maxDueInDays}';
      }
    }
    return null;
  }

  /// The THEN's parameters, carrying only what this action uses. A field left over from another
  /// action — a promised amount on a rule that now sends email — would be stored and read back,
  /// and the next person to open the form would find it filled in for no reason.
  Map<String, dynamic> _actionSpec() {
    final days = int.tryParse(_dueInDays.text.trim());
    final tokens = [for (final t in _tokens) t.toJson()];
    return switch (_action) {
      AutomationAction.createTask => {
          'title': _title.text.trim(),
          'body': optionalText(_body.text),
          if (days != null) 'dueInDays': days,
          if (_taskStatus != null) 'status': _taskStatus!.wire,
          'assignees': tokens,
        },
      AutomationAction.createPromise => {
          'body': optionalText(_body.text),
          if (days != null) 'dueInDays': days,
          'amount': parseMoneyInput(_amount.text),
          'assignees': tokens,
        },
      AutomationAction.createDispute => {
          'body': _body.text.trim(),
          'assignees': tokens,
        },
      AutomationAction.sendEmail => {
          'title': _title.text.trim(),
          'body': optionalText(_body.text),
          if (_from != null) 'from': _from!.toJson(),
          'assignees': tokens,
        },
    };
  }

  Future<void> _submit() async {
    final problem = _check();
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final saved = await saveAutomationRule(ref,
          id: widget.ruleId,
          body: automationRuleBody(
            name: _name.text.trim(),
            description: optionalText(_description.text),
            enabled: _enabled,
            entityType: _type,
            trigger: _trigger,
            filters: [for (final f in _filters) f.wire],
            action: _action,
            actionSpec: _actionSpec(),
          ));
      if (!mounted) return;
      Navigator.of(context).pop(true);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('"${saved.name}" saved')));
    } catch (e) {
      // A rule the server will not take comes back as a 400 whose message names what is wrong —
      // a column that kind of record has not got, a role it does not offer. Shown as written.
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  // ---- build -------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final ruleId = widget.ruleId;
    final loading = ruleId == null ? null : ref.watch(automationRuleProvider(ruleId));
    if (loading?.valueOrNull != null) _seed(loading!.value!);
    final narrow = MediaQuery.sizeOf(context).width < 600;
    final ready = ruleId == null || _seeded;

    return AlertDialog(
      title: Text(_isEdit ? 'Edit rule' : 'New rule'),
      content: SizedBox(
        // A phone has nowhere near 640px to give (D-60).
        width: narrow ? double.maxFinite : 640,
        child: !ready
            ? SizedBox(
                height: 160,
                child: loading!.hasError
                    ? Center(
                        child: Text('Could not load the rule: ${apiErrorMessage(loading.error!)}'))
                    : const Center(child: CircularProgressIndicator()),
              )
                : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Flexible(child: SingleChildScrollView(child: _form())),
                  // Outside the scrolling form, right above the buttons, as the compose form's
                  // warnings are: what was changed for the author, and what the server refused,
                  // are both things to read before pressing Save — and the form is long enough
                  // that anything inside it can be off the screen when they do.
                  if (_notice != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: _Notice(text: _notice!, icon: Icons.warning_amber_rounded),
                    ),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(_error!,
                          style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ),
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving || !ready ? null : _submit,
          child: _saving
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(_isEdit ? 'Save' : 'Create rule'),
        ),
      ],
    );
  }

  Widget _form() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        TextField(
          controller: _name,
          autofocus: !_isEdit,
          decoration: const InputDecoration(labelText: 'Name *'),
          inputFormatters: [LengthLimitingTextInputFormatter(AutomationLimits.name)],
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _description,
          maxLines: 2,
          decoration: const InputDecoration(
            labelText: 'What it is for',
            // A rule runs for everybody, months after it was written, and the next person to read
            // it will not have been in the conversation it came out of.
            helperText: 'Why this rule exists, for whoever reads it next',
          ),
          inputFormatters: [LengthLimitingTextInputFormatter(AutomationLimits.description)],
        ),
        const SizedBox(height: 8),
        SwitchListTile(
          value: _enabled,
          onChanged: _saving ? null : (on) => setState(() => _enabled = on),
          title: const Text('Enabled'),
          subtitle: Text(_enabled
              ? 'It will run whenever it is set off'
              : 'It is written down but nothing sets it off'),
          contentPadding: EdgeInsets.zero,
          dense: true,
        ),
        const SizedBox(height: 8),
        _whenSection(),
        const SizedBox(height: 16),
        _whereSection(),
        const SizedBox(height: 16),
        _thenSection(),
      ],
    );
  }

  Widget _section(String heading, String explanation, List<Widget> children) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(heading,
            style: theme.textTheme.titleSmall?.copyWith(
                color: theme.colorScheme.primary, letterSpacing: 0.6)),
        Text(explanation, style: theme.textTheme.bodySmall),
        const SizedBox(height: 8),
        ...children,
      ],
    );
  }

  // ---- WHEN --------------------------------------------------------------------------

  Widget _whenSection() {
    final narrow = MediaQuery.sizeOf(context).width < 600;
    return _section('WHEN', 'What kind of record, and what has to happen to it', [
      Flex(
        direction: narrow ? Axis.vertical : Axis.horizontal,
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: DropdownButtonFormField<AutomationRecordType>(
              decoration: const InputDecoration(labelText: 'Record'),
              initialValue: _type,
              isExpanded: true,
              items: [
                for (final type in AutomationRecordType.values)
                  DropdownMenuItem(value: type, child: Text(type.label)),
              ],
              onChanged: _saving ? null : (t) => t == null ? null : _setType(t),
            ),
          ),
          const SizedBox(width: 12, height: 12),
          Flexible(
            child: DropdownButtonFormField<AutomationTrigger>(
              decoration: const InputDecoration(labelText: 'Happens'),
              initialValue: _trigger,
              isExpanded: true,
              items: [
                for (final trigger in AutomationTrigger.values)
                  DropdownMenuItem(value: trigger, child: Text(trigger.label)),
              ],
              onChanged: _saving ? null : (t) => setState(() => _trigger = t ?? _trigger),
            ),
          ),
        ],
      ),
      const SizedBox(height: 6),
      Text(
        _trigger.isScheduled
            // A sweep acts on everything that matches, every time — which is why the WHERE below
            // it is the whole of what keeps such a rule safe to leave running.
            ? '${_trigger.label}, this runs over every ${_type.noun} that matches the filters below.'
            : 'This runs for one ${_type.noun} at a time, as soon as it '
                '${_trigger == AutomationTrigger.created ? 'is created' : 'is changed'}.',
        style: Theme.of(context).textTheme.bodySmall,
      ),
    ]);
  }

  // ---- WHERE -------------------------------------------------------------------------

  Widget _whereSection() {
    final schemaAsync = ref.watch(tableSchemaProvider(_type.tableEntity));
    final schema = schemaAsync.valueOrNull;
    final theme = Theme.of(context);
    final full = _filters.length >= AutomationLimits.maxFilters;

    return _section(
      'WHERE',
      'The same filters the ${_type.tableEntity} list is filtered by',
      [
        if (schemaAsync.hasError)
          Text('Could not load what ${_type.tableEntity} can be filtered by: '
              '${apiErrorMessage(schemaAsync.error!)}'),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            ActionChip(
              avatar: const Icon(Icons.filter_alt_outlined, size: 18),
              label: const Text('Add filter'),
              // Nothing is offered until this kind of record's own schema is in hand: it is what
              // says which columns exist and what may be asked of each (D.4).
              onPressed: schema == null || _saving || full ? null : () => _addFilter(schema),
            ),
            for (final filter in _filters)
              InputChip(
                label: Text(describeFilter(filter, schema)),
                onPressed:
                    schema == null || _saving ? null : () => _editFilter(schema, filter),
                deleteButtonTooltipMessage: 'Remove',
                onDeleted: _saving
                    ? null
                    : () => setState(() =>
                        _filters = [for (final f in _filters) if (f != filter) f]),
              ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          full
              ? 'A rule takes at most ${AutomationLimits.maxFilters} filters.'
              : _filters.isEmpty
                  // Said plainly, because it is the difference between a rule that chases a
                  // handful of records and one that acts on the whole book.
                  ? 'No filters: this acts on every ${_type.noun}.'
                  : 'A ${_type.noun} has to match all of these.',
          style: theme.textTheme.bodySmall?.copyWith(
              color: full || _filters.isEmpty ? theme.colorScheme.error : null),
        ),
      ],
    );
  }

  // ---- THEN --------------------------------------------------------------------------

  Widget _thenSection() {
    final allowed = [
      for (final action in AutomationAction.values)
        // The one already chosen stays on the list whatever happens, so the field always has an
        // item for the value it is showing.
        if (action.allowedOn(_type) || action == _action) action,
    ];
    return _section('THEN', 'What it does to the ${_type.noun} it matched', [
      DropdownButtonFormField<AutomationAction>(
        decoration: const InputDecoration(labelText: 'Do this'),
        initialValue: _action,
        isExpanded: true,
        items: [
          for (final action in allowed)
            DropdownMenuItem(value: action, child: Text(action.label)),
        ],
        onChanged: _saving ? null : (a) => a == null ? null : _setAction(a),
      ),
      const SizedBox(height: 12),
      ..._actionFields(),
      const SizedBox(height: 12),
      _peopleField(),
    ]);
  }

  List<Widget> _actionFields() => switch (_action) {
        AutomationAction.createTask => [
            TextField(
              controller: _title,
              decoration: const InputDecoration(labelText: 'Task title *'),
              inputFormatters: [
                LengthLimitingTextInputFormatter(AutomationLimits.taskTitle),
              ],
            ),
            const SizedBox(height: 12),
            _notesField('Notes', AutomationLimits.taskNotes),
            const SizedBox(height: 12),
            // Side by side where there is room; stacked on a phone, where two fields in a row cut
            // both labels short (D-60).
            Flex(
              direction:
                  MediaQuery.sizeOf(context).width < 600 ? Axis.vertical : Axis.horizontal,
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Flexible(child: _dueInDaysField('Due in (days)')),
                const SizedBox(width: 12, height: 12),
                Flexible(
                  child: DropdownButtonFormField<AutomationTaskStatus?>(
                    decoration: const InputDecoration(labelText: 'Opens as'),
                    initialValue: _taskStatus,
                    isExpanded: true,
                    items: [
                      const DropdownMenuItem(value: null, child: Text('Open (default)')),
                      for (final status in AutomationTaskStatus.values)
                        DropdownMenuItem(value: status, child: Text(status.label)),
                    ],
                    onChanged: _saving ? null : (s) => setState(() => _taskStatus = s),
                  ),
                ),
              ],
            ),
          ],
        AutomationAction.createPromise => [
            TextField(
              controller: _amount,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(
                labelText: 'Promised amount *',
                // The rule states it; it is never read off the record. A rule that guessed the
                // amount from an invoice's balance would commit a customer to a number no person
                // ever agreed to.
                helperText: 'The same amount every time this rule fires',
              ),
            ),
            const SizedBox(height: 12),
            _dueInDaysField('Promised in (days)'),
            const SizedBox(height: 12),
            _notesField('Notes', FieldLimits.promiseNotes),
          ],
        AutomationAction.createDispute => [
            _notesField('Reason *', AutomationLimits.disputeReason),
          ],
        AutomationAction.sendEmail => [
            _senderField(),
            const SizedBox(height: 12),
            TextField(
              controller: _title,
              decoration: const InputDecoration(labelText: 'Subject *'),
              inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.emailSubject)],
            ),
            const SizedBox(height: 12),
            _notesField('Message', FieldLimits.emailBody, lines: 4),
            const SizedBox(height: 6),
            // The compose form offers this record's placeholders with the value each takes on it
            // (M4). A rule has no record, so there are no samples to offer and no list that would
            // mean anything; what a placeholder is, and that it is filled in per record when the
            // rule fires, is said here instead of faking a picker.
            Text(
              'Placeholders such as {{Customer.Name}} and {{Invoice.Balance}} are filled in from '
              'each ${_type.noun} when the rule runs.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
      };

  Widget _notesField(String label, int limit, {int lines = 2}) => TextField(
        controller: _body,
        minLines: lines,
        maxLines: lines + 6,
        keyboardType: TextInputType.multiline,
        decoration: InputDecoration(labelText: label, alignLabelWithHint: true),
        inputFormatters: [LengthLimitingTextInputFormatter(limit)],
      );

  Widget _dueInDaysField(String label) => TextField(
        controller: _dueInDays,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        decoration: InputDecoration(
          labelText: label,
          // Counted from the run, not from anything on the record: the rule may fire long after
          // the record was made, and the work is due from when it was raised.
          helperText: 'From the day it runs',
        ),
      );

  /// Who an email rule writes as. Roles rather than "me": a rule fires with nobody at the
  /// keyboard, and the seat is answerable for as long as somebody holds it. A named person is
  /// still offered, for a rule that really is one person's.
  Widget _senderField() {
    final async = ref.watch(emailContextProvider(
        (type: _type.emailType, entityId: null, event: null)));
    final ctx = async.valueOrNull;
    final from = _from;
    final value = from == null
        ? 'default'
        : from.isRole
            ? 'role:${from.role}:${from.level ?? ''}'
            : 'user:${from.userId}';

    final items = <DropdownMenuItem<String>>[
      // No "leave it to the server" option: a rule has no caller to fall back on, so an unnamed
      // sender is an email that can never go, and the server refuses one at authoring time.
      const DropdownMenuItem(
          value: 'default',
          child: Text('Choose who it sends as…', overflow: TextOverflow.ellipsis)),
      for (final role in ctx?.roles ?? const <EmailRoleOption>[])
        DropdownMenuItem(
          value: 'role:${role.role}:${role.level}',
          // No record, so no holders to name: the level is all that tells two offers of one role
          // apart, and the seat is filled against the record when the email goes out.
          child: Text(role.labelWithLevel, overflow: TextOverflow.ellipsis),
        ),
    ];
    // A sender the rule already names that this list does not offer — a person, or a role the
    // kind of record no longer has — keeps an item of its own, so the field shows what the rule
    // actually says rather than silently reading as the default.
    if (items.every((item) => item.value != value)) {
      items.add(DropdownMenuItem(
        value: value,
        child: Text(
          from!.isUser
              ? _senderPeople[from.userId!]?.display ?? 'User #${from.userId}'
              : humanizeEnum(from.role),
          overflow: TextOverflow.ellipsis,
        ),
      ));
    }
    items.add(const DropdownMenuItem(value: 'other', child: Text('Someone else…')));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        // A plain DropdownButton rather than the form-field one, as the compose form's From is:
        // what it shows follows [_from] on every build, so "Someone else…" closed without picking
        // anybody leaves the field saying what the rule still actually says.
        InputDecorator(
          decoration: const InputDecoration(labelText: 'From'),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<String>(
              value: value,
              isDense: true,
              isExpanded: true,
              items: items,
              onChanged: _saving
                  ? null
                  : (v) {
                      if (v == null || v == value) return;
                      if (v == 'default') {
                        setState(() => _from = null);
                      } else if (v == 'other') {
                        _pickSender();
                      } else if (v.startsWith('role:')) {
                        final parts = v.substring('role:'.length).split(':');
                        setState(() => _from = EmailToken.role(parts.first,
                            level: parts.length > 1 && parts[1].isNotEmpty ? parts[1] : null));
                      }
                    },
            ),
          ),
        ),
        if (async.hasError)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text('Could not load the roles to send as: ${apiErrorMessage(async.error!)}',
                style: Theme.of(context).textTheme.bodySmall),
          ),
      ],
    );
  }

  /// Who the action is for, picked with the same widget as everywhere else — a person, or a role
  /// at a level. Which roles are offered depends on the kind of record the thing being made hangs
  /// off, which is exactly what the server checks the picks against.
  Widget _peopleField() {
    final peopleType = _action.peopleType(_type);
    final async =
        ref.watch(emailContextProvider((type: peopleType, entityId: null, event: null)));
    final ctx = async.valueOrNull;
    // The people search is the compose form's, and so is refused to anyone without EMAIL_SEND.
    // Without it the chip is left out rather than offered and then failing: a rule can still be
    // written for roles, which is what most rules address anyway.
    final canSearchPeople = ref.watch(canSendEmailProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        AssigneePickerField(
          label: _action.peopleLabel,
          value: _people,
          // No record, so no holders to name: a role is a seat here, filled against the record
          // the rule runs on (A2). The picker shows the seats; who is in them is settled on the
          // run — which is why its options come back unresolved and it names them alone.
          roleGroups: ctx?.roleGroups ?? const [],
          searchPeople: canSearchPeople ? _searchPeople : null,
          enabled: !_saving,
          errorText: _peopleError,
          onChanged: (tokens) => setState(() {
            _people = tokens;
            _peopleError = null;
          }),
        ),
        if (async.hasError)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text('Could not load the roles to pick from: ${apiErrorMessage(async.error!)}',
                style: Theme.of(context).textTheme.bodySmall),
          ),
        if (_action == AutomationAction.sendEmail)
          CheckboxListTile(
            value: _toCustomer,
            onChanged: _saving
                ? null
                : (on) => setState(() {
                      _toCustomer = on ?? false;
                      if (_toCustomer) _peopleError = null;
                    }),
            title: const Text("Also the customer's own email addresses"),
            subtitle: const Text('Whatever is on file for the customer the record belongs to'),
            controlAffinity: ListTileControlAffinity.leading,
            contentPadding: EdgeInsets.zero,
            dense: true,
          ),
      ],
    );
  }
}

/// A line of warning inside the form, in the amber the compose form's warnings use: something to
/// read before saving, not something that stops it.
class _Notice extends StatelessWidget {
  final String text;
  final IconData icon;
  const _Notice({required this.text, required this.icon});

  @override
  Widget build(BuildContext context) {
    final foreground = Colors.brown.shade800;
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.amber.shade100,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: foreground),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: TextStyle(color: foreground))),
        ],
      ),
    );
  }
}
