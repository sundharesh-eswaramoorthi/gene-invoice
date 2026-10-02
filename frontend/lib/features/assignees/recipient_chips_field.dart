import 'package:flutter/material.dart';

import '../../core/format.dart';
import '../email/email_models.dart';
import 'people_search_dialog.dart';

/// A stable key for one token's chip or "add" chip, for tests and for the eye.
String assigneeTokenKey(EmailToken t) => switch (t.type) {
      'USER' => 'user-${t.userId}',
      'ROLE' => 'role-${t.role}-${t.level ?? EmailRoleLevel.customer}',
      _ => t.type.toLowerCase(),
    };

/// Who something is for — a task's assignees, a rule's recipients — in the email To field's
/// model (A3): named people, and roles at a level ("Collection POC of the customer", "Sales POC of
/// this invoice") that stand for whoever holds the seat when it is used.
///
/// The roles offered and their holders come from [ctx] (`/api/assignees/context`, or the email
/// form's context). [searchPeople] finds a person; without it no person can be added.
class RecipientChipsField extends StatefulWidget {
  final EmailContext ctx;
  final List<EmailToken> value;
  final ValueChanged<List<EmailToken>> onChanged;

  /// Offer the customer's own email addresses (only an email has any use for them).
  final bool allowCustomer;

  /// At most one token; adding another replaces it.
  final bool single;

  /// Only these role keys are offered (a promise's Collection POC, say); null offers every one.
  final Set<String>? roleFilter;

  /// Describe roles as each record's ("Sales POC (each invoice's)") rather than naming who holds
  /// them today — for a rule, which acts on many records.
  final bool perRecordLabels;
  final String label;
  final Future<List<EmailPerson>> Function(String search)? searchPeople;

  /// What to call tokens the context cannot name — the people already assigned, whose names came
  /// with the record.
  final Map<EmailToken, String> knownLabels;
  final String? errorText;
  final bool enabled;

  /// The server keeps at most this many (§5.9.1).
  final int maxCount;

  const RecipientChipsField({
    super.key,
    required this.ctx,
    required this.value,
    required this.onChanged,
    this.allowCustomer = false,
    this.single = false,
    this.roleFilter,
    this.perRecordLabels = false,
    this.label = 'Assignees',
    this.searchPeople,
    this.knownLabels = const {},
    this.errorText,
    this.enabled = true,
    this.maxCount = 10,
  });

  @override
  State<RecipientChipsField> createState() => _RecipientChipsFieldState();
}

class _RecipientChipsFieldState extends State<RecipientChipsField> {
  /// People picked in this field, so their chips can show their names.
  final Map<int, EmailPerson> _people = {};

  static const _customer = EmailToken.customer();

  bool get _full => !widget.single && widget.value.length >= widget.maxCount;

  void _add(EmailToken t) {
    if (widget.value.contains(t)) return;
    widget.onChanged(widget.single ? [t] : [...widget.value, t]);
  }

  void _remove(EmailToken t) => widget.onChanged(widget.value.where((x) => x != t).toList());

  Future<void> _addPerson() async {
    final search = widget.searchPeople;
    if (search == null) return;
    final person = await showPeopleSearchDialog<EmailPerson>(
      context,
      title: 'Add a person',
      search: search,
      labelOf: (p) => p.name,
      subtitleOf: (p) => [
        if (p.username != null) '@${p.username}',
        if (p.email != null && p.email!.isNotEmpty) p.email!,
      ].join(' · '),
    );
    if (person?.userId == null || !mounted) return;
    setState(() => _people[person!.userId!] = person);
    _add(EmailToken.user(person!.userId!));
  }

  List<EmailRoleOption> get _offered => [
        for (final r in widget.ctx.roles)
          if (widget.roleFilter == null || widget.roleFilter!.contains(r.role)) r,
      ];

  bool _atBothLevels(EmailRoleOption r) =>
      widget.ctx.roles.where((other) => other.role == r.role).length > 1;

  String _roleText(EmailRoleOption r) {
    if (widget.perRecordLabels) return "${r.label} (each ${r.levelLabel.toLowerCase()}'s)";
    final role = _atBothLevels(r) ? r.labelWithLevel : r.label;
    if (widget.ctx.entityId == null || r.resolved == null) return role;
    if (r.resolved == false) return '$role · nobody assigned';
    return switch (r.people.length) {
      0 => role,
      1 => '$role · ${r.people.single.name}',
      2 => '$role · ${r.people[0].name}, ${r.people[1].name}',
      _ => '$role · ${r.people.first.name} + ${r.people.length - 1} more',
    };
  }

  /// Everyone holding the role today, one to a line; nothing when that is not known.
  String? _holders(EmailRoleOption r) => widget.perRecordLabels || r.people.isEmpty
      ? null
      : r.people.map((p) => p.display).join('\n');

  String _tokenText(EmailToken t) {
    final known = widget.knownLabels[t];
    if (t.isUser) {
      final person = _people[t.userId];
      return person?.name ?? known ?? 'User #${t.userId}';
    }
    if (t.isCustomer) return 'Customer emails';
    final option = widget.ctx.roleOf(t);
    if (option == null) return known ?? humanizeEnum(t.role);
    return _roleText(option);
  }

  IconData _iconOf(EmailToken t) => t.isUser
      ? Icons.person_outline
      : t.isRole
          ? Icons.badge_outlined
          : Icons.business_outlined;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ctx = widget.ctx;
    final enabled = widget.enabled;
    final canAdd = enabled && !_full;

    final groups = [
      for (final group in ctx.roleGroups)
        (
          label: group.label,
          roles: [
            for (final r in group.roles)
              if (_offered.contains(r) && !widget.value.contains(r.token)) r,
          ],
        ),
    ];

    Widget addRow(List<Widget> children) => Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Wrap(
            spacing: 6,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: children,
          ),
        );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        InputDecorator(
          decoration: InputDecoration(
            labelText: widget.label,
            errorText: widget.errorText,
            errorMaxLines: 3,
            helperText: _full ? 'At most ${widget.maxCount}' : null,
          ),
          child: widget.value.isEmpty
              ? Text(widget.single ? 'Nobody chosen' : 'Nobody yet — add below',
                  style: TextStyle(color: theme.hintColor))
              : Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final t in widget.value)
                      Tooltip(
                        message: (ctx.roleOf(t) == null ? null : _holders(ctx.roleOf(t)!)) ?? '',
                        child: InputChip(
                          key: ValueKey('assignee-${assigneeTokenKey(t)}'),
                          avatar: Icon(_iconOf(t), size: 18),
                          label: Text(_tokenText(t), overflow: TextOverflow.ellipsis),
                          deleteButtonTooltipMessage: 'Remove',
                          onDeleted: enabled ? () => _remove(t) : null,
                        ),
                      ),
                  ],
                ),
        ),
        if (canAdd) ...[
          addRow([
            Text('Add', style: theme.textTheme.labelLarge),
            if (widget.searchPeople != null)
              ActionChip(
                key: const ValueKey('assignee-add-person'),
                avatar: const Icon(Icons.person_add_alt, size: 18),
                label: const Text('Person…'),
                onPressed: _addPerson,
              ),
          ]),
          for (final group in groups)
            if (group.roles.isNotEmpty)
              addRow([
                Text(group.label,
                    style: theme.textTheme.labelLarge
                        ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                for (final r in group.roles)
                  ActionChip(
                    key: ValueKey('assignee-add-${assigneeTokenKey(r.token)}'),
                    avatar: const Icon(Icons.badge_outlined, size: 18),
                    label: Text(_roleText(r), overflow: TextOverflow.ellipsis),
                    tooltip: _holders(r),
                    onPressed: () => _add(r.token),
                  ),
              ]),
          if (widget.allowCustomer && !widget.value.contains(_customer))
            addRow([
              ActionChip(
                key: const ValueKey('assignee-add-customer'),
                avatar: const Icon(Icons.business_outlined, size: 18),
                label: const Text('Customer emails'),
                tooltip: ctx.customerEmailsAvailable || widget.perRecordLabels
                    ? null
                    : 'There is no customer to write to',
                onPressed: ctx.customerEmailsAvailable || widget.perRecordLabels
                    ? () => _add(_customer)
                    : null,
              ),
            ]),
        ],
      ],
    );
  }
}
