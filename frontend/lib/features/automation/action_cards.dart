import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../assignees/assignee_providers.dart';
import '../assignees/recipient_chips_field.dart';
import '../email/email_entity.dart';
import '../email/email_models.dart';
import 'automation_models.dart';
import 'email_action_dialog.dart';
import 'placeholder_button.dart';

/// The longest reason a dispute keeps (§5.10.5).
const _disputeReasonLimit = 2000;

/// A rule's actions (A3), one card each: what it creates or sends, for whom, whether it acts once
/// per record or every time, and — for tasks and promises — not while one is still open. The
/// last card is followed by "Add action" while there is room for one (at most five, §6.14).
///
/// Each card edits its action's JSON in place, so everything the editor does not know of is sent
/// back as it came, key included.
class ActionCards extends StatelessWidget {
  final TriggerEntity entity;
  final List<RuleAction> actions;
  final ValueChanged<List<RuleAction>> onChanged;
  final bool enabled;

  /// The kinds of action this user may add: those whose privilege they hold somewhere.
  final Set<ActionType> offered;

  /// The server's `fieldErrors`, keyed as it sends them (`actions[1].title`, …).
  final Map<String, String> errors;

  /// Who "the rule's owner" is, for the email's sender.
  final String? ownerName;

  /// Names for the people picked on this page, which a token alone does not carry.
  final Map<int, String> people;

  const ActionCards({
    super.key,
    required this.entity,
    required this.actions,
    required this.onChanged,
    this.enabled = true,
    this.offered = const {...ActionType.values},
    this.errors = const {},
    this.ownerName,
    this.people = const {},
  });

  void _replace(int i, RuleAction next) => onChanged([...actions]..[i] = next);

  void _remove(int i) => onChanged([...actions]..removeAt(i));

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final canAdd = enabled && actions.length < maxRuleActions && offered.isNotEmpty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (actions.isEmpty) Text('No actions yet.', style: theme.textTheme.bodySmall),
        for (final (i, a) in actions.indexed)
          ActionCard(
            key: ValueKey('rule-action-${a.key}'),
            index: i,
            entity: entity,
            action: a,
            enabled: enabled,
            errors: {
              for (final e in errors.entries)
                if (e.key == 'actions[$i]' || e.key.startsWith('actions[$i].'))
                  e.key.substring('actions[$i]'.length).replaceFirst('.', ''): e.value,
            },
            ownerName: ownerName,
            people: people,
            onChanged: (next) => _replace(i, next),
            onRemove: () => _remove(i),
          ),
        if (canAdd)
          Align(
            alignment: Alignment.centerLeft,
            child: PopupMenuButton<ActionType>(
              key: const ValueKey('action-add'),
              tooltip: 'Add an action',
              onSelected: (type) => onChanged([...actions, newRuleAction(type, entity)]),
              itemBuilder: (context) => [
                for (final t in ActionType.values)
                  if (offered.contains(t))
                    PopupMenuItem(
                      key: ValueKey('action-add-${t.name}'),
                      value: t,
                      child: Row(children: [
                        Icon(actionIcon(t), size: 18),
                        const SizedBox(width: 8),
                        Text(t.label),
                      ]),
                    ),
              ],
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.add, size: 18, color: theme.colorScheme.primary),
                    const SizedBox(width: 6),
                    Text('Add action',
                        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.primary)),
                  ],
                ),
              ),
            ),
          )
        else if (enabled && actions.length >= maxRuleActions)
          Text('A rule can take at most $maxRuleActions actions.', style: theme.textTheme.bodySmall),
      ],
    );
  }
}

IconData actionIcon(ActionType? type) => switch (type) {
      ActionType.CREATE_TASK => Icons.checklist,
      ActionType.CREATE_PROMISE => Icons.handshake_outlined,
      ActionType.CREATE_DISPUTE => Icons.flag_outlined,
      ActionType.SEND_EMAIL => Icons.mail_outline,
      null => Icons.bolt_outlined,
    };

/// One action, edited in place. Its text boxes keep what is typed; a change from outside — the
/// rule moved to another kind of record, say — is written into them.
class ActionCard extends ConsumerStatefulWidget {
  final int index;
  final TriggerEntity entity;
  final RuleAction action;
  final bool enabled;

  /// This action's own `fieldErrors`, keyed by the path under it (`title`, `amount.value`, …).
  final Map<String, String> errors;
  final String? ownerName;
  final Map<int, String> people;
  final ValueChanged<RuleAction> onChanged;
  final VoidCallback onRemove;

  const ActionCard({
    super.key,
    required this.index,
    required this.entity,
    required this.action,
    required this.onChanged,
    required this.onRemove,
    this.enabled = true,
    this.errors = const {},
    this.ownerName,
    this.people = const {},
  });

  @override
  ConsumerState<ActionCard> createState() => _ActionCardState();
}

class _ActionCardState extends ConsumerState<ActionCard> {
  /// The action's text fields, by the JSON key they write.
  final Map<String, TextEditingController> _text = {};

  /// The focus of each of those fields that takes placeholders, shared with its button so a pick
  /// can give the field its focus back.
  final Map<String, FocusNode> _focus = {};

  /// Numbers held as typed, since a half-typed one ("-") is not yet a number to send.
  late final _dueOffset = TextEditingController(text: _offsetText('due'));
  late final _dateOffset = TextEditingController(text: _offsetText('promisedDate'));
  late final _amount = TextEditingController(text: _amountText());

  Map<String, dynamic> get _json => widget.action.json;
  String get _k => 'action-${widget.index}';

  @override
  void didUpdateWidget(covariant ActionCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (identical(oldWidget.action, widget.action)) return;
    for (final e in _text.entries) {
      final now = _json[e.key] as String? ?? '';
      if (e.value.text != now) e.value.text = now;
    }
    _sync(_dueOffset, _offsetText('due'));
    _sync(_dateOffset, _offsetText('promisedDate'));
    if (parseMoneyInput(_amount.text) != _amountValue()) _amount.text = _amountText();
  }

  /// A field still being typed ('' or '-') stands for the 0 it sent, so it is left alone.
  static void _sync(TextEditingController c, String now) {
    if ((int.tryParse(c.text) ?? 0) != int.tryParse(now)) c.text = now;
  }

  @override
  void dispose() {
    for (final c in _text.values) {
      c.dispose();
    }
    for (final f in _focus.values) {
      f.dispose();
    }
    _dueOffset.dispose();
    _dateOffset.dispose();
    _amount.dispose();
    super.dispose();
  }

  String _offsetText(String field) {
    final date = _json[field];
    return date is Map && date['offsetDays'] is num ? '${date['offsetDays']}' : '0';
  }

  double? _amountValue() {
    final amount = _json['amount'];
    return amount is Map && amount['value'] is num ? (amount['value'] as num).toDouble() : null;
  }

  String _amountText() {
    final v = _amountValue();
    if (v == null) return '';
    return v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(2);
  }

  /// The errors a field of the card shows under itself; the card lists the rest.
  static bool _shownAtField(String key) =>
      const {
        'title',
        'description',
        'notes',
        'reason',
        'amount.value',
        'due.offsetDays',
        'promisedDate.offsetDays',
      }.contains(key) ||
      key.startsWith('assignees') ||
      key.startsWith('collectionPoc');

  TextEditingController _controller(String field) =>
      _text[field] ??= TextEditingController(text: _json[field] as String? ?? '');

  FocusNode _focusNode(String field) => _focus[field] ??= FocusNode(debugLabel: '$_k-$field');

  void _set(Map<String, dynamic> changes) =>
      widget.onChanged(RuleAction.fromJson({...widget.action.json, ...changes}));

  List<EmailToken> _tokens(String field) => tokensIn(_json[field]);

  List<Object> _wire(List<EmailToken> tokens) => [for (final t in tokens) t.toJson()];

  Future<List<EmailPerson>> _search(String q) async {
    final found = await searchAssignablePeople(ref.read(dioProvider), q);
    for (final p in found) {
      if (p.userId != null) widget.people[p.userId!] = p.name;
    }
    return found;
  }

  Map<EmailToken, String> _known(List<EmailToken> tokens) => {
        for (final t in tokens)
          if (t.isUser && widget.people[t.userId] != null) t: widget.people[t.userId]!,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final a = widget.action;
    final type = ActionType.parse(a.type);
    final rest = [
      for (final e in widget.errors.entries)
        if (!_shownAtField(e.key)) e.key.isEmpty ? e.value : '${humanizeEnum(e.key)}: ${e.value}',
    ];
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 4, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(actionIcon(type), size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(a.summary,
                      style: theme.textTheme.titleSmall,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis),
                ),
                _Choice<String>(
                  key: ValueKey('$_k-repeat'),
                  value: a.repeat,
                  enabled: widget.enabled,
                  items: const {
                    'ONCE_PER_RECORD': 'Once per record',
                    'EVERY_TIME': 'Every time',
                  },
                  onChanged: (v) => _set({'repeat': v}),
                ),
                if (widget.enabled)
                  IconButton(
                    key: ValueKey('$_k-remove'),
                    tooltip: 'Remove action',
                    icon: const Icon(Icons.delete_outline, size: 20),
                    onPressed: widget.onRemove,
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ...switch (a) {
                    CreateTaskAction() => _task(),
                    CreatePromiseAction() => _promise(),
                    CreateDisputeAction() => _dispute(),
                    SendEmailAction() => _email(),
                    UnknownAction() => [
                        Text('This build cannot edit this kind of action; it is kept as it is.',
                            style: theme.textTheme.bodySmall),
                      ],
                  },
                  for (final message in rest)
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text(message, style: TextStyle(color: theme.colorScheme.error)),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// A text the action renders with placeholders (A4), and the button that inserts them.
  Widget _placeholderText(String field, String label,
      {required int maxLength, bool singleLine = true, bool required = false}) {
    final controller = _controller(field);
    final focusNode = _focusNode(field);
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: TextField(
              key: ValueKey('$_k-$field'),
              controller: controller,
              focusNode: focusNode,
              enabled: widget.enabled,
              minLines: singleLine ? 1 : 2,
              maxLines: singleLine ? 1 : 5,
              inputFormatters: [LengthLimitingTextInputFormatter(maxLength)],
              decoration: InputDecoration(
                labelText: required ? '$label *' : label,
                errorText: widget.errors[field],
              ),
              onChanged: (text) => _set({field: text.isEmpty && !required ? null : text}),
            ),
          ),
          PlaceholderButton(
            key: ValueKey('$_k-$field-placeholder'),
            entityType: widget.entity.name,
            controller: controller,
            focusNode: focusNode,
            singleLine: singleLine,
            enabled: widget.enabled,
            onInserted: (text) => _set({field: text}),
          ),
        ],
      ),
    );
  }

  /// "Days after …": a base date and a number of days, or (for a task) no date at all.
  Widget _dateField({
    required String field,
    required TextEditingController offset,
    required String label,
    required int min,
    required int max,
    bool allowNone = false,
  }) {
    final date = _json[field];
    final base = date is Map ? date['base'] as String? ?? 'TODAY' : (allowNone ? 'NONE' : 'TODAY');
    final onInvoice = widget.entity == TriggerEntity.INVOICE;
    final typed = int.tryParse(offset.text);
    final offsetError = widget.errors['$field.offsetDays'] ??
        (offset.text.isNotEmpty && offset.text != '-' && (typed == null || typed < min || typed > max)
            ? 'From $min to $max'
            : null);
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Wrap(
        spacing: 12,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.end,
        children: [
          SizedBox(
            width: 280,
            child: _Choice<String>(
              key: ValueKey('$_k-$field-base'),
              label: label,
              value: base,
              enabled: widget.enabled,
              items: {
                if (allowNone) 'NONE': 'No due date',
                'TODAY': 'Days after it runs',
                if (onInvoice || base == 'INVOICE_DUE_DATE')
                  'INVOICE_DUE_DATE': "Days after the invoice's due date",
              },
              onChanged: (v) => _set({
                field: v == 'NONE' ? null : {'base': v, 'offsetDays': typed ?? 0},
              }),
            ),
          ),
          if (base != 'NONE')
            SizedBox(
              width: 130,
              child: TextField(
                key: ValueKey('$_k-$field-offset'),
                controller: offset,
                enabled: widget.enabled,
                keyboardType: const TextInputType.numberWithOptions(signed: true),
                inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'^-?\d{0,3}'))],
                decoration: InputDecoration(labelText: 'Days', errorText: offsetError),
                onChanged: (text) => _set({
                  field: {'base': base, 'offsetDays': int.tryParse(text) ?? 0},
                }),
              ),
            ),
        ],
      ),
    );
  }

  Widget _guard(String field, String label) => CheckboxListTile(
        key: ValueKey('$_k-guard'),
        contentPadding: EdgeInsets.zero,
        dense: true,
        controlAffinity: ListTileControlAffinity.leading,
        value: _json[field] as bool? ?? false,
        onChanged: widget.enabled ? (v) => _set({field: v ?? false}) : null,
        title: Text(label),
      );

  /// People and roles for one of the action's token lists.
  Widget _people(String field, EmailEntityType type,
      {required String label, bool single = false, Set<String>? roleFilter, String? hint}) {
    final tokens = single ? [if (tokenIn(_json[field]) case final t?) t] : _tokens(field);
    final error = [
      for (final e in widget.errors.entries)
        if (e.key == field || e.key.startsWith('$field[') || e.key.startsWith('$field.')) e.value,
    ];
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: RuleContextBuilder(
        type: type,
        builder: (_, ctx) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            RecipientChipsField(
              key: ValueKey('$_k-$field'),
              ctx: ctx,
              value: tokens,
              label: label,
              single: single,
              roleFilter: roleFilter,
              perRecordLabels: true,
              enabled: widget.enabled,
              knownLabels: _known(tokens),
              errorText: error.isEmpty ? null : error.join('\n'),
              searchPeople: _search,
              onChanged: (next) => _set({
                field: single ? (next.isEmpty ? null : next.last.toJson()) : _wire(next),
              }),
            ),
            if (hint != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(hint, style: Theme.of(context).textTheme.bodySmall),
              ),
          ],
        ),
      ),
    );
  }

  List<Widget> _task() => [
        _placeholderText('title', 'Title', maxLength: FieldLimits.taskTitle, required: true),
        _placeholderText('description', 'Description',
            maxLength: FieldLimits.taskDescription, singleLine: false),
        _dateField(
            field: 'due',
            offset: _dueOffset,
            label: 'Due',
            min: -30,
            max: 365,
            allowNone: true),
        _people('assignees', widget.entity.emailType, label: 'Assignees'),
        _guard('onlyIfNoOpenTask', 'Not while a task from this rule is still open on the record'),
      ];

  List<Widget> _promise() {
    final theme = Theme.of(context);
    final amount = _json['amount'] is Map ? (_json['amount'] as Map) : const {};
    final source = amount['source'] == 'FIXED' ? 'FIXED' : 'FIELD';
    final field = amount['field'] as String?;
    final typed = parseMoneyInput(_amount.text);
    return [
      Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text('Amount', style: theme.textTheme.labelLarge),
      ),
      const SizedBox(height: 6),
      Wrap(
        spacing: 12,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.end,
        children: [
          SegmentedButton<String>(
            key: ValueKey('$_k-amount-source'),
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: 'FIELD', label: Text('From the record')),
              ButtonSegment(value: 'FIXED', label: Text('A fixed amount')),
            ],
            selected: {source},
            onSelectionChanged: widget.enabled
                ? (s) => _set({
                      'amount': s.first == 'FIXED'
                          ? {'source': 'FIXED', 'value': typed}
                          : {
                              'source': 'FIELD',
                              'field': promiseAmountFields.keys
                                  .firstWhere((f) => fieldApplies(f, widget.entity)),
                            },
                    })
                : null,
          ),
          if (source == 'FIELD')
            SizedBox(
              width: 280,
              child: _Choice<String>(
                key: ValueKey('$_k-amount-field'),
                label: 'Take',
                value: field ?? 'Customer.Outstanding',
                enabled: widget.enabled,
                items: {
                  for (final e in promiseAmountFields.entries)
                    if (fieldApplies(e.key, widget.entity) || e.key == field) e.key: e.value,
                },
                onChanged: (v) => _set({
                  'amount': {'source': 'FIELD', 'field': v},
                }),
              ),
            )
          else
            SizedBox(
              width: 200,
              child: TextField(
                key: ValueKey('$_k-amount-value'),
                controller: _amount,
                enabled: widget.enabled,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                  labelText: 'Amount (₹) *',
                  errorText: widget.errors['amount.value'] ??
                      (_amount.text.isNotEmpty && typed == null
                          ? 'Enter an amount like 1500 or 1500.50'
                          : null),
                ),
                onChanged: (text) => _set({
                  'amount': {'source': 'FIXED', 'value': parseMoneyInput(text)},
                }),
              ),
            ),
        ],
      ),
      _dateField(
          field: 'promisedDate', offset: _dateOffset, label: 'Promised for', min: 0, max: 365),
      _people('collectionPoc', widget.entity.emailType,
          label: 'Collection POC',
          single: true,
          roleFilter: const {'COLLECTION_POC'},
          hint: "Nobody chosen: the customer's own Collection POC."),
      _people('assignees', EmailEntityType.promise, label: 'Also assigned to'),
      _placeholderText('notes', 'Notes', maxLength: FieldLimits.promiseNotes, singleLine: false),
      if (widget.entity == TriggerEntity.INVOICE || _json['linkRecord'] == true)
        CheckboxListTile(
          key: ValueKey('$_k-link'),
          contentPadding: EdgeInsets.zero,
          dense: true,
          controlAffinity: ListTileControlAffinity.leading,
          value: _json['linkRecord'] as bool? ?? false,
          onChanged: widget.enabled ? (v) => _set({'linkRecord': v ?? false}) : null,
          title: const Text('Link the promise to the invoice'),
        ),
      _guard(
          'onlyIfNoOpenPromise',
          widget.entity == TriggerEntity.INVOICE
              ? 'Not while the invoice has an open promise'
              : 'Not while the customer has an open promise'),
    ];
  }

  List<Widget> _dispute() {
    final proposal = _json['proposedChange'] as String? ?? 'none';
    return [
      _placeholderText('reason', 'Reason',
          maxLength: _disputeReasonLimit, singleLine: false, required: true),
      Padding(
        padding: const EdgeInsets.only(top: 8),
        child: SizedBox(
          width: 360,
          child: _Choice<String>(
            key: ValueKey('$_k-proposal'),
            label: 'Proposed change',
            value: proposal,
            enabled: widget.enabled,
            items: {
              'none': 'None — for the team to look into',
              if (widget.entity == TriggerEntity.INVOICE || proposal == 'cancel')
                'cancel': 'Cancel the invoice',
              if (widget.entity == TriggerEntity.PAYMENT || proposal == 'void')
                'void': 'Void the payment',
            },
            onChanged: (v) => _set({'proposedChange': v}),
          ),
        ),
      ),
      _people('assignees', EmailEntityType.dispute, label: 'Assignees'),
    ];
  }

  List<Widget> _email() {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final from = tokenIn(_json['from']);
    final to = _tokens('to');
    final subject = _json['subject'] as String? ?? '';
    final body = _json['body'] as String? ?? '';
    return [
      RuleContextBuilder(
        type: widget.entity.emailType,
        builder: (_, ctx) => Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Column(
            key: ValueKey('$_k-email-summary'),
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                  'From: ${from == null ? "the rule's owner" : ruleTokenLabel(from, ctx, people: widget.people)}'
                  '${from != null && (_json['fallbackToOwner'] as bool? ?? true) ? ", or the owner" : ""}'),
              Text(to.isEmpty
                  ? 'To: nobody yet'
                  : 'To: ${to.map((t) => ruleTokenLabel(t, ctx, people: widget.people)).join(', ')}'),
              Text(subject.isEmpty ? 'No subject yet' : 'Subject: $subject'),
              if (body.isNotEmpty)
                Text(body, maxLines: 3, overflow: TextOverflow.ellipsis, style: muted),
            ],
          ),
        ),
      ),
      if (widget.enabled)
        Align(
          alignment: Alignment.centerLeft,
          child: Padding(
            padding: const EdgeInsets.only(top: 8),
            child: OutlinedButton.icon(
              key: ValueKey('$_k-edit-email'),
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('Edit email…'),
              onPressed: () async {
                final fields = await showEmailActionDialog(context,
                    entity: widget.entity,
                    action: _json,
                    ownerName: widget.ownerName,
                    people: widget.people);
                if (fields != null && mounted) _set(fields);
              },
            ),
          ),
        ),
    ];
  }
}

/// A small dropdown whose value is always the one given, so a change from outside shows at once.
class _Choice<T> extends StatelessWidget {
  final T value;
  final Map<T, String> items;
  final ValueChanged<T> onChanged;
  final bool enabled;
  final String? label;

  const _Choice({
    super.key,
    required this.value,
    required this.items,
    required this.onChanged,
    this.enabled = true,
    this.label,
  });

  @override
  Widget build(BuildContext context) {
    final entries = {...items};
    if (!entries.containsKey(value)) entries[value] = '$value';
    final dropdown = DropdownButtonHideUnderline(
      child: DropdownButton<T>(
        value: value,
        isDense: true,
        isExpanded: label != null,
        items: [
          for (final e in entries.entries) DropdownMenuItem(value: e.key, child: Text(e.value)),
        ],
        onChanged: enabled
            ? (v) {
                if (v != null && v != value) onChanged(v);
              }
            : null,
      ),
    );
    if (label == null) return dropdown;
    return InputDecorator(
      decoration: InputDecoration(labelText: label, enabled: enabled),
      child: dropdown,
    );
  }
}
