import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../shared/widgets/role_token_field.dart';
import '../email/email_models.dart';
import '../email/email_providers.dart';
import '../email/send_email_dialog.dart' show roleKeyLabel;
import 'automation_models.dart';
import 'placeholder_picker.dart';

/// WHAT A RULE DOES — one to five actions, in order (A3).
///
/// The order is significant and is why this is a [ReorderableListView] rather than a column: a
/// later action waits for an earlier one, which is how "create the task, then email the customer
/// about it" is expressible at all.
///
/// Assignees and recipients are picked with [RoleTokenField], THE SAME widget the email To field
/// uses — "the collection POC on this account" is a seat and not a person, and a rule written
/// against a person breaks silently the day that person changes desks. The field is fed by
/// `GET /api/emails/context` with NO entityId, which already answers `resolved: null, people: []`:
/// exactly a rule editor's state, and no new endpoint (A3, A6).
class ActionEditor extends ConsumerWidget {
  final AutomationSubject subject;
  final List<AutomationAction> actions;

  /// Called whenever anything inside an action changes, so the page above can mark itself dirty
  /// and re-render the live preview.
  final VoidCallback onChanged;

  final bool enabled;

  /// The server's own cap. A rule with fifty actions is a mistake at authoring time and a runaway
  /// at run time, so the button goes rather than the save failing (A3).
  static const int maxActions = 5;

  const ActionEditor({
    super.key,
    required this.subject,
    required this.actions,
    required this.onChanged,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ctx = ref
        .watch(emailContextProvider((type: subject.emailType, entityId: null, event: null)))
        .valueOrNull;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        ReorderableListView(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          buildDefaultDragHandles: false,
          // onReorderItem and not the deprecated onReorder: it hands back a newIndex already
          // adjusted for the removed row, so the off-by-one dance is the framework's and not a
          // line here that could be got wrong.
          onReorderItem: (oldIndex, newIndex) {
            if (!enabled) return;
            final moved = actions.removeAt(oldIndex);
            actions.insert(newIndex, moved);
            onChanged();
          },
          children: [
            for (var i = 0; i < actions.length; i++)
              _ActionCard(
                // The action object itself, so the card's controllers follow it across a reorder
                // rather than following the slot it used to sit in.
                key: ObjectKey(actions[i]),
                index: i,
                action: actions[i],
                subject: subject,
                context0: ctx,
                enabled: enabled,
                canRemove: actions.length > 1,
                onChanged: onChanged,
                onRemove: () {
                  actions.removeAt(i);
                  onChanged();
                },
              ),
          ],
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: Tooltip(
            message: actions.length < maxActions
                ? ''
                : 'A rule may not have more than $maxActions actions',
            child: OutlinedButton.icon(
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Add action'),
              onPressed: enabled && actions.length < maxActions
                  ? () {
                      actions.add(AutomationAction(kind: AutomationActionKind.CREATE_TASK));
                      onChanged();
                    }
                  : null,
            ),
          ),
        ),
      ],
    );
  }
}

class _ActionCard extends ConsumerStatefulWidget {
  final int index;
  final AutomationAction action;
  final AutomationSubject subject;

  /// The role catalogue for this subject type, or null while it is still loading. Named
  /// `context0` because `context` is taken.
  final EmailContext? context0;

  final bool enabled;
  final bool canRemove;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  const _ActionCard({
    super.key,
    required this.index,
    required this.action,
    required this.subject,
    required this.context0,
    required this.enabled,
    required this.canRemove,
    required this.onChanged,
    required this.onRemove,
  });

  @override
  ConsumerState<_ActionCard> createState() => _ActionCardState();
}

class _ActionCardState extends ConsumerState<_ActionCard> {
  late final _title = _bind(() => widget.action.title, (v) => widget.action.title = v);
  late final _notes = _bind(() => widget.action.notes, (v) => widget.action.notes = v);
  late final _reason = _bind(() => widget.action.reason, (v) => widget.action.reason = v);
  late final _subject = _bind(() => widget.action.subject, (v) => widget.action.subject = v);
  late final _body = _bind(() => widget.action.body, (v) => widget.action.body = v);
  late final _amount =
      _bind(() => widget.action.fixedAmount, (v) => widget.action.fixedAmount = v);
  late final _dueInDays = _bind(
      () => widget.action.dueInDays?.toString() ?? '',
      (v) => widget.action.dueInDays = int.tryParse(v.trim()));
  late final _promisedInDays = _bind(
      () => widget.action.promisedInDays?.toString() ?? '',
      (v) => widget.action.promisedInDays = int.tryParse(v.trim()));

  /// People added by name, so a chip can say who they are rather than "User #12".
  final Map<int, EmailPerson> _people = {};

  final List<TextEditingController> _controllers = [];

  /// The model is the single copy: a controller writes straight through on every keystroke, so
  /// the live preview and the save read the same text and there is no "apply" step to forget.
  ///
  /// The guard is not an optimisation. A [TextEditingController] notifies on a SELECTION change
  /// too, so without it merely clicking into a box would mark the page dirty and put the
  /// "discard unsaved changes?" question in front of somebody who typed nothing — and would
  /// re-render the preview on every caret move. It is the same guard the send-email dialog keeps
  /// against `_previewedSubject` (A4).
  TextEditingController _bind(String Function() read, void Function(String) write) {
    final controller = TextEditingController(text: read());
    var last = controller.text;
    controller.addListener(() {
      if (controller.text == last) return;
      last = controller.text;
      write(controller.text);
      widget.onChanged();
    });
    _controllers.add(controller);
    return controller;
  }

  @override
  void dispose() {
    for (final c in _controllers) {
      c.dispose();
    }
    super.dispose();
  }

  void _change(VoidCallback mutate) {
    setState(mutate);
    widget.onChanged();
  }

  @override
  Widget build(BuildContext context) {
    final action = widget.action;
    final theme = Theme.of(context);

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                ReorderableDragStartListener(
                  index: widget.index,
                  child: Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Tooltip(
                      message: 'Drag to reorder — a later action waits for an earlier one',
                      child: Icon(Icons.drag_indicator, color: theme.colorScheme.outline),
                    ),
                  ),
                ),
                Text('${widget.index + 1}.', style: theme.textTheme.titleMedium),
                const SizedBox(width: 8),
                Expanded(
                  child: DropdownButtonFormField<AutomationActionKind>(
                    decoration: const InputDecoration(labelText: 'Action', isDense: true),
                    initialValue: action.kind,
                    isExpanded: true,
                    items: [
                      for (final kind in AutomationActionKind.values)
                        if (kind.offeredOn(widget.subject) || kind == action.kind)
                          DropdownMenuItem(value: kind, child: Text(kind.label)),
                    ],
                    onChanged: widget.enabled
                        ? (kind) => _change(() {
                              if (kind != null) action.kind = kind;
                            })
                        : null,
                  ),
                ),
                if (widget.canRemove)
                  IconButton(
                    tooltip: 'Remove this action',
                    icon: const Icon(Icons.close, size: 18),
                    onPressed: widget.enabled ? widget.onRemove : null,
                  ),
              ],
            ),
            const SizedBox(height: 8),
            ...switch (action.kind) {
              AutomationActionKind.CREATE_TASK => _task(),
              AutomationActionKind.CREATE_PROMISE => _promise(),
              AutomationActionKind.CREATE_DISPUTE => _dispute(),
              AutomationActionKind.SEND_EMAIL => _email(),
            },
          ],
        ),
      ),
    );
  }

  // ------------------------------------------------------------------ the four typed forms

  List<Widget> _task() => [
        TemplateField(
          controller: _title,
          label: 'Task title',
          subject: widget.subject,
          maxLength: FieldLimits.taskTitle,
          enabled: widget.enabled,
        ),
        const SizedBox(height: 12),
        TemplateField(
          controller: _notes,
          label: 'Notes',
          subject: widget.subject,
          maxLength: FieldLimits.taskNotes,
          minLines: 3,
          maxLines: 8,
          enabled: widget.enabled,
        ),
        const SizedBox(height: 12),
        _days(_dueInDays, 'Due in (days)', 'Leave empty for no due date'),
        const SizedBox(height: 12),
        // No customer chip: a task assignee may never be a customer, and the server refuses that
        // token outright. Offering it would only earn a 400 (A3, A6).
        _tokenField(
          label: 'Assign to',
          tokens: widget.action.assignees,
          offerCustomer: false,
          emptyHint: 'Nobody yet — add a seat below',
        ),
      ];

  List<Widget> _promise() => [
        DropdownButtonFormField<AmountSource>(
          decoration: const InputDecoration(labelText: 'Amount'),
          initialValue: widget.action.amountFrom,
          isExpanded: true,
          items: [
            for (final source in AmountSource.values)
              if (source.offeredOn(widget.subject) || source == widget.action.amountFrom)
                DropdownMenuItem(value: source, child: Text(source.label)),
          ],
          onChanged: widget.enabled
              ? (source) => _change(() {
                    if (source != null) widget.action.amountFrom = source;
                  })
              : null,
        ),
        if (widget.action.amountFrom == AmountSource.FIXED) ...[
          const SizedBox(height: 12),
          TextField(
            controller: _amount,
            enabled: widget.enabled,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Fixed amount *', prefixText: '₹ '),
          ),
        ],
        const SizedBox(height: 12),
        _days(_promisedInDays, 'Promised in (days)', 'Counted from the day the rule runs'),
        const SizedBox(height: 12),
        _singleToken(
          label: 'Collection POC',
          value: widget.action.collectionPoc,
          onChanged: (t) => _change(() => widget.action.collectionPoc = t),
          noneLabel: "Leave the account's own",
          offerCustomer: false,
        ),
        const SizedBox(height: 12),
        TemplateField(
          controller: _notes,
          label: 'Notes',
          subject: widget.subject,
          maxLength: FieldLimits.promiseNotes,
          minLines: 2,
          maxLines: 6,
          enabled: widget.enabled,
        ),
      ];

  List<Widget> _dispute() => [
        TemplateField(
          controller: _reason,
          label: 'Reason',
          subject: widget.subject,
          // The server's DISPUTE_TEXT.
          maxLength: 2000,
          minLines: 2,
          maxLines: 6,
          enabled: widget.enabled,
        ),
        const SizedBox(height: 8),
        Text(
          'A rule proposes nothing: an automated dispute asks for a look, never for a price.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      ];

  List<Widget> _email() => [
        // A rule has no signed-in user to fall back on, so the sender is always explicit and is
        // never the customer (A3).
        _singleToken(
          label: 'From *',
          value: widget.action.from,
          onChanged: (t) => _change(() => widget.action.from = t),
          noneLabel: 'Choose a sender…',
          offerCustomer: false,
        ),
        const SizedBox(height: 12),
        _tokenField(
          label: 'To *',
          tokens: widget.action.to,
          offerCustomer: true,
          emptyHint: 'Nobody yet — add a recipient below',
        ),
        const SizedBox(height: 12),
        TemplateField(
          controller: _subject,
          label: 'Subject',
          subject: widget.subject,
          maxLength: FieldLimits.emailSubject,
          enabled: widget.enabled,
        ),
        const SizedBox(height: 12),
        TemplateField(
          controller: _body,
          label: 'Message',
          subject: widget.subject,
          maxLength: FieldLimits.emailBody,
          minLines: 4,
          maxLines: 12,
          enabled: widget.enabled,
        ),
      ];

  Widget _days(TextEditingController controller, String label, String helper) => TextField(
        controller: controller,
        enabled: widget.enabled,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        decoration: InputDecoration(labelText: label, helperText: helper),
      );

  // ------------------------------------------------------------------ the two token pickers

  Widget _tokenField({
    required String label,
    required List<EmailToken> tokens,
    required bool offerCustomer,
    required String emptyHint,
  }) {
    final ctx = widget.context0;
    if (ctx == null) {
      return InputDecorator(
        decoration: InputDecoration(labelText: label),
        child: const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: LinearProgressIndicator(),
        ),
      );
    }
    return RoleTokenField(
      label: label,
      emptyHint: emptyHint,
      tokens: tokens,
      roleGroups: ctx.roleGroups,
      tokenText: (t) => _tokenText(ctx, tokens, t),
      roleText: (r) => _roleText(ctx, r),
      offerCustomer: offerCustomer,
      customerText: "The customer's own email addresses",
      enabled: widget.enabled,
      onAddPerson: () => unawaited(_addPerson(tokens)),
      onAdd: (t) => _change(() {
        if (!tokens.contains(t)) tokens.add(t);
      }),
      onRemove: (t) => _change(() => tokens.remove(t)),
    );
  }

  /// One seat, not a list — a promise has one collection POC and an email has one sender.
  Widget _singleToken({
    required String label,
    required EmailToken? value,
    required ValueChanged<EmailToken?> onChanged,
    required String noneLabel,
    required bool offerCustomer,
  }) {
    final ctx = widget.context0;
    final options = ctx?.roles ?? const <EmailRoleOption>[];
    String keyOf(EmailToken t) =>
        t.isUser ? 'user:${t.userId}' : 'role:${t.role}:${t.level ?? ''}';
    final current = value == null ? 'none' : keyOf(value);

    final items = <DropdownMenuItem<String>>[
      DropdownMenuItem(value: 'none', child: Text(noneLabel)),
      for (final r in options)
        DropdownMenuItem(value: keyOf(r.token), child: Text(_roleOptionText(ctx!, r))),
      if (value != null && value.isUser)
        DropdownMenuItem(value: current, child: Text(_personText(value.userId!))),
      if (value != null && value.isRole && ctx?.roleOf(value) == null)
        DropdownMenuItem(value: current, child: Text(roleKeyLabel(value.role!))),
      if (offerCustomer) const DropdownMenuItem(value: 'customer', child: Text('The customer')),
      const DropdownMenuItem(value: 'person', child: Text('Someone by name…')),
    ];

    return DropdownButtonFormField<String>(
      decoration: InputDecoration(labelText: label),
      initialValue: items.any((i) => i.value == current) ? current : 'none',
      isExpanded: true,
      items: items,
      onChanged: !widget.enabled
          ? null
          : (picked) async {
              if (picked == null || picked == current) return;
              if (picked == 'none') return onChanged(null);
              if (picked == 'customer') return onChanged(const EmailToken.customer());
              if (picked == 'person') {
                final person = await _pickPerson();
                if (person?.userId == null || !mounted) return;
                _people[person!.userId!] = person;
                onChanged(EmailToken.user(person.userId!));
                return;
              }
              for (final r in options) {
                if (keyOf(r.token) == picked) return onChanged(r.token);
              }
            },
    );
  }

  /// A chip in an ADD ROW carries the plain label, exactly as the To field's does: the row it
  /// sits in is already headed by its level, so "Sales POC (customer)" under "Customer level"
  /// says it twice (A3, L4).
  String _roleText(EmailContext ctx, EmailRoleOption r) => r.label;

  /// A FLAT dropdown has no level heading to lean on, so the same role at two levels has to say
  /// which one it is or the two entries read identically (A3, L1).
  String _roleOptionText(EmailContext ctx, EmailRoleOption r) =>
      ctx.roles.where((other) => other.role == r.role).length > 1 ? r.labelWithLevel : r.label;

  String _tokenText(EmailContext ctx, List<EmailToken> tokens, EmailToken t) {
    if (t.isUser) return _personText(t.userId!);
    if (t.isCustomer) return "The customer's own email addresses";
    final option = ctx.roleOf(t);
    if (option == null) return roleKeyLabel(t.role!);
    final bothLevels = tokens.where((o) => o.isRole && o.role == t.role).length > 1;
    return bothLevels ? option.labelWithLevel : option.label;
  }

  String _personText(int userId) => _people[userId]?.display ?? 'User #$userId';

  Future<void> _addPerson(List<EmailToken> tokens) async {
    final person = await _pickPerson();
    if (person?.userId == null || !mounted) return;
    _people[person!.userId!] = person;
    _change(() {
      final token = EmailToken.user(person.userId!);
      if (!tokens.contains(token)) tokens.add(token);
    });
  }

  Future<EmailPerson?> _pickPerson() {
    final dio = ref.read(dioProvider);
    return showDialog<EmailPerson>(
      context: context,
      builder: (_) => _PersonSearchDialog(search: (q) => searchEmailPeople(dio, q)),
    );
  }
}

/// A text box that a placeholder can be dropped into.
///
/// The button beside the label is the whole point: a template is written by somebody who does not
/// know the token vocabulary, and typing `{{Customer.Name}}` from memory is how an unknown
/// placeholder reaches a save (A4).
class TemplateField extends ConsumerWidget {
  final TextEditingController controller;
  final String label;
  final AutomationSubject subject;
  final int maxLength;
  final int minLines;
  final int maxLines;
  final bool enabled;

  const TemplateField({
    super.key,
    required this.controller,
    required this.label,
    required this.subject,
    required this.maxLength,
    this.minLines = 1,
    this.maxLines = 1,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextField(
            controller: controller,
            enabled: enabled,
            minLines: minLines,
            maxLines: maxLines,
            keyboardType: maxLines > 1 ? TextInputType.multiline : null,
            inputFormatters: [LengthLimitingTextInputFormatter(maxLength)],
            decoration: InputDecoration(labelText: label, alignLabelWithHint: maxLines > 1),
          ),
        ),
        const SizedBox(width: 4),
        IconButton(
          tooltip: 'Insert a placeholder',
          icon: const Icon(Icons.data_object),
          onPressed: enabled
              ? () => showPlaceholderPicker(context,
                  subject: subject, controller: controller, fieldLabel: label)
              : null,
        ),
      ],
    );
  }
}

class _PersonSearchDialog extends StatefulWidget {
  final Future<List<EmailPerson>> Function(String search) search;
  const _PersonSearchDialog({required this.search});

  @override
  State<_PersonSearchDialog> createState() => _PersonSearchDialogState();
}

class _PersonSearchDialogState extends State<_PersonSearchDialog> {
  late Future<List<EmailPerson>> _results = widget.search('');
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final narrow = MediaQuery.sizeOf(context).width < 600;
    return AlertDialog(
      title: const Text('Add a person'),
      content: SizedBox(
        // A phone has nowhere near 420px to give (D-60).
        width: narrow ? double.maxFinite : 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Search by name, username or email',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: (text) {
                _debounce?.cancel();
                _debounce = Timer(const Duration(milliseconds: 250), () {
                  if (mounted) setState(() => _results = widget.search(text.trim()));
                });
              },
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 260,
              child: FutureBuilder<List<EmailPerson>>(
                future: _results,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Center(
                        child: Text('Could not search: ${apiErrorMessage(snapshot.error!)}'));
                  }
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  final people = snapshot.data ?? const <EmailPerson>[];
                  if (people.isEmpty) return const Center(child: Text('Nothing matches'));
                  return ListView.builder(
                    itemCount: people.length,
                    itemBuilder: (context, i) => ListTile(
                      title: Text(people[i].name),
                      subtitle: people[i].email == null ? null : Text(people[i].email!),
                      onTap: () => Navigator.of(context).pop(people[i]),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
      ],
    );
  }
}
