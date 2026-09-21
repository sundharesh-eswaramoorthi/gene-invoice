import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../models/assignee.dart';

/// Picks who a piece of work is for: the same field the email To line already is (A1).
///
/// An assignee is one of two things, and the picker offers exactly those two: a person, found by
/// searching, or a role at a level — everyone in the customer's POC book holding that seat, or
/// the one POC the record itself stores. Both come out as [EmailToken]s, the very tokens the
/// compose form sends, so a task, an automation rule's THEN and an email all address people the
/// same way and there is one thing for a reader to learn.
///
/// The CUSTOMER token the email form offers is deliberately **not** here: it addresses the
/// customer's own email addresses, and work is never assigned outside the company — a customer
/// cannot be answerable for chasing their own invoice. One that somehow arrives in [value] is
/// neither shown nor kept.
///
/// It is presentational: it is handed [roleGroups] and a way to search people, and hands back
/// tokens. It fetches nothing itself, which is what lets the automation rule form use it with no
/// record at all — a rule's assignees are picked before any record exists, so the role options
/// then come back unresolved ([EmailRoleOption.resolved] null) and the chips name the seat
/// without claiming who is in it.
class AssigneePickerField extends StatefulWidget {
  /// The field's label, e.g. 'Assignees' or 'Assign to'.
  final String label;

  /// Who is picked now. Order is kept: it is the order they were added in.
  final List<EmailToken> value;
  final ValueChanged<List<EmailToken>> onChanged;

  /// The roles on offer, grouped by level and labelled, exactly as `EmailContext.roleGroups`
  /// gives them: customer level first, then the record's own. Empty while they are still loading,
  /// or for a record type that offers none.
  final List<EmailRoleGroup> roleGroups;

  /// Names for people already picked, by user id — an existing task's own assignees, which the
  /// picker was not the one to choose. Anyone picked here is remembered on top of these.
  final Map<int, String> personNames;

  /// Searches internal users for the "Person…" chip. Null leaves the chip out, for a caller who
  /// may not search people — a customer login never addresses staff by name.
  final Future<List<AssigneePerson>> Function(String search)? searchPeople;

  /// False while a save is under way: the chips stay readable but nothing can be added or removed.
  final bool enabled;

  /// The form's validation message, shown under the box like any other field's.
  final String? errorText;

  /// What the box says when nobody is picked.
  final String emptyHint;

  const AssigneePickerField({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
    required this.roleGroups,
    this.personNames = const {},
    this.searchPeople,
    this.enabled = true,
    this.errorText,
    this.emptyHint = 'Add people or roles below',
  });

  @override
  State<AssigneePickerField> createState() => _AssigneePickerFieldState();
}

class _AssigneePickerFieldState extends State<AssigneePickerField> {
  /// People picked here, so the chip can name somebody the caller never knew about. The caller
  /// owns the tokens; only their names are remembered, and only for as long as the form is open.
  final Map<int, AssigneePerson> _picked = {};

  /// What is actually picked. A CUSTOMER token is not an assignee (see the class doc), so it is
  /// dropped on the way in — and, because every change is built from this list, on the way out.
  List<EmailToken> get _tokens =>
      [for (final t in widget.value) if (!t.isCustomer) t];

  void _add(EmailToken token) {
    if (_tokens.contains(token)) return;
    widget.onChanged([..._tokens, token]);
  }

  void _remove(EmailToken token) {
    widget.onChanged([
      for (final t in _tokens)
        if (t != token) t,
    ]);
  }

  Future<void> _addPerson() async {
    final search = widget.searchPeople;
    if (search == null) return;
    final person = await showDialog<AssigneePerson>(
      context: context,
      builder: (_) => _PersonSearchDialog(search: search),
    );
    final userId = person?.userId;
    if (userId == null || !mounted) return;
    setState(() => _picked[userId] = person!);
    _add(EmailToken.user(userId));
  }

  /// The role a token names, or null when the options on offer do not include it — a rule written
  /// against a record type that has since stopped offering that seat, or options still loading.
  EmailRoleOption? _optionOf(EmailToken token) {
    if (!token.isRole) return null;
    for (final group in widget.roleGroups) {
      for (final r in group.roles) {
        if (r.role == token.role && r.level == token.level) return r;
      }
    }
    return null;
  }

  String _personName(int userId) =>
      _picked[userId]?.display ?? widget.personNames[userId] ?? 'User #$userId';

  String _tokenText(EmailToken token) {
    if (token.isUser) return _personName(token.userId!);
    final option = _optionOf(token);
    if (option == null) return assigneeRoleKeyLabel(token.role);
    // A chip in the box stands away from the headings that name the level, so a role picked at
    // both levels says on each chip which POC it is — otherwise the two would read alike.
    final bothLevels =
        _tokens.where((other) => other.isRole && other.role == token.role).length > 1;
    return assigneeRoleText(option, levelled: bothLevels);
  }

  String? _tokenTooltip(EmailToken token) {
    final option = _optionOf(token);
    return option == null ? null : assigneeRoleTooltip(option);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tokens = _tokens;
    final enabled = widget.enabled;

    // A level's chips, without the roles already picked. A group left with none is not shown, as
    // a level the record has no roles at is not.
    final groups = [
      for (final group in widget.roleGroups)
        (
          label: group.label,
          roles: [
            for (final r in group.roles)
              if (!tokens.contains(r.token)) r,
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
          decoration: InputDecoration(labelText: widget.label, errorText: widget.errorText),
          child: tokens.isEmpty
              ? Text(widget.emptyHint, style: TextStyle(color: theme.hintColor))
              : Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final token in tokens)
                      // A chip shows its own tooltip only when it can be pressed, and this one can
                      // only be removed. An empty message adds nothing; over the delete button its
                      // "Remove" wins.
                      Tooltip(
                        message: _tokenTooltip(token) ?? '',
                        child: InputChip(
                          avatar: Icon(
                            token.isUser ? Icons.person_outline : Icons.badge_outlined,
                            size: 18,
                          ),
                          label: Text(_tokenText(token), overflow: TextOverflow.ellipsis),
                          deleteButtonTooltipMessage: 'Remove',
                          onDeleted: enabled ? () => _remove(token) : null,
                        ),
                      ),
                  ],
                ),
        ),
        addRow([
          Text('Add', style: theme.textTheme.labelLarge),
          // The "Add" word stays even without the chip, because it heads the role rows below it
          // just as much: it is the one label that says what all of this is for.
          if (widget.searchPeople != null)
            ActionChip(
              avatar: const Icon(Icons.person_add_alt, size: 18),
              label: const Text('Person…'),
              onPressed: enabled ? _addPerson : null,
            ),
        ]),
        for (final group in groups)
          if (group.roles.isNotEmpty)
            addRow([
              // The same words the compose form's To and From use, so the two lists read as one.
              Text(group.label,
                  style: theme.textTheme.labelLarge
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              for (final r in group.roles)
                ActionChip(
                  avatar: const Icon(Icons.badge_outlined, size: 18),
                  label: Text(assigneeRoleText(r), overflow: TextOverflow.ellipsis),
                  tooltip: assigneeRoleTooltip(r),
                  onPressed: enabled ? () => _add(r.token) : null,
                ),
            ]),
      ],
    );
  }
}

/// Finds one internal user, searching the server as the user types — the same dialog the compose
/// form's "Person…" opens, saying what the search covers.
class _PersonSearchDialog extends StatefulWidget {
  final Future<List<AssigneePerson>> Function(String search) search;

  const _PersonSearchDialog({required this.search});

  @override
  State<_PersonSearchDialog> createState() => _PersonSearchDialogState();
}

class _PersonSearchDialogState extends State<_PersonSearchDialog> {
  late Future<List<AssigneePerson>> _results = widget.search('');
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  // The dialog owns the search, so the list always answers what is in the box now rather than
  // the keystroke before it.
  void _onSearchChanged(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () {
      if (!mounted) return;
      setState(() {
        _results = widget.search(text.trim());
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add a person'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Search by name, username or email',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: _onSearchChanged,
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 300,
              child: FutureBuilder<List<AssigneePerson>>(
                future: _results,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Center(
                        child: Text('Could not search: ${apiErrorMessage(snapshot.error!)}'));
                  }
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  final people = snapshot.data ?? const <AssigneePerson>[];
                  if (people.isEmpty) return const Center(child: Text('Nothing matches'));
                  return ListView.builder(
                    itemCount: people.length,
                    itemBuilder: (context, i) {
                      final person = people[i];
                      final address = person.address;
                      return ListTile(
                        title: Text(person.name),
                        subtitle: address == null || address.isEmpty ? null : Text(address),
                        onTap: () => Navigator.of(context).pop(person),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
      ],
    );
  }
}
