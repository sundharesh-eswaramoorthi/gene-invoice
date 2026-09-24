import 'package:flutter/material.dart';

import '../../features/email/email_models.dart';

/// The role-at-a-level recipient picker, lifted OUT of `send_email_dialog.dart`'s `_toField` so
/// the automation rule builder can address people the same way the email form does (A3, L1).
///
/// It is the same widget tree the To field has always rendered — an [InputDecorator] holding one
/// [InputChip] per chosen token, then one "add" row per level group with the group's label beside
/// its [ActionChip]s — because `email_compose_test.dart` reads it structurally
/// (`_chipsUnder(tester, 'Customer level')` finds the [Wrap] that contains that heading). A
/// rewrite that merely looked the same would have moved those chips into a different ancestor and
/// silently changed what the shipped email form does.
///
/// Everything that needs a record — what a role resolves to, who holds it, whether there is a
/// customer to write to — arrives as a callback, because the two callers know different things:
/// the email form has one invoice in front of it, and a rule editor has none at all. The field
/// itself knows only about tokens (A3).
class RoleTokenField extends StatelessWidget {
  /// The decorator's label, including its own ` *` when the caller requires a token.
  final String label;

  final String? errorText;

  /// What stands in the box before anything is chosen — "Add recipients below".
  final String emptyHint;

  final List<EmailToken> tokens;

  /// The roles this record (or this subject type) offers, already grouped by level. Empty groups
  /// are left out, and so is a role that is already chosen: the chip moves from the add row into
  /// the box rather than appearing twice (L4).
  final List<EmailRoleGroup> roleGroups;

  final String Function(EmailToken token) tokenText;
  final String? Function(EmailToken token)? tokenTooltip;

  final String Function(EmailRoleOption role) roleText;
  final String? Function(EmailRoleOption role)? roleTooltip;

  /// Whether the customer's own addresses may be added. A task assignee may never be a customer —
  /// the server refuses that token outright — so the rule builder turns this off rather than
  /// offering a chip that earns a 400 (A3).
  final bool offerCustomer;
  final String customerText;
  final bool customerEnabled;
  final String? customerTooltip;

  /// Whether somebody may be addressed by name. A customer login may not (E13).
  final bool offerPerson;
  final String personLabel;

  final bool enabled;

  final VoidCallback? onAddPerson;
  final ValueChanged<EmailToken> onAdd;
  final ValueChanged<EmailToken> onRemove;

  const RoleTokenField({
    super.key,
    required this.label,
    required this.tokens,
    required this.roleGroups,
    required this.tokenText,
    required this.roleText,
    required this.onAdd,
    required this.onRemove,
    this.errorText,
    this.emptyHint = 'Add recipients below',
    this.tokenTooltip,
    this.roleTooltip,
    this.offerCustomer = true,
    this.customerText = 'Customer emails',
    this.customerEnabled = true,
    this.customerTooltip,
    this.offerPerson = true,
    this.personLabel = 'Person…',
    this.enabled = true,
    this.onAddPerson,
  });

  static IconData iconOf(EmailToken t) => t.isUser
      ? Icons.person_outline
      : t.isRole
          ? Icons.badge_outlined
          : Icons.business_outlined;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    final groups = [
      for (final group in roleGroups)
        (
          label: group.label,
          roles: [
            for (final r in group.roles)
              if (!tokens.contains(r.token)) r,
          ],
        ),
    ];
    const customer = EmailToken.customer();

    Widget groupLabel(String text) => Text(text,
        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant));

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
          decoration: InputDecoration(labelText: label, errorText: errorText),
          child: tokens.isEmpty
              ? Text(emptyHint, style: TextStyle(color: theme.hintColor))
              : Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final t in tokens)
                      Tooltip(
                        message: tokenTooltip?.call(t) ?? '',
                        child: InputChip(
                          avatar: Icon(iconOf(t), size: 18),
                          label: Text(tokenText(t), overflow: TextOverflow.ellipsis),
                          deleteButtonTooltipMessage: 'Remove',
                          onDeleted: enabled ? () => onRemove(t) : null,
                        ),
                      ),
                  ],
                ),
        ),
        addRow([
          Text('Add', style: theme.textTheme.labelLarge),
          // Only staff may address other staff by name (E13).
          if (offerPerson)
            ActionChip(
              avatar: const Icon(Icons.person_add_alt, size: 18),
              label: Text(personLabel),
              onPressed: enabled ? onAddPerson : null,
            ),
        ]),
        for (final group in groups)
          if (group.roles.isNotEmpty)
            addRow([
              groupLabel(group.label),
              for (final r in group.roles)
                ActionChip(
                  avatar: const Icon(Icons.badge_outlined, size: 18),
                  label: Text(roleText(r), overflow: TextOverflow.ellipsis),
                  tooltip: roleTooltip?.call(r),
                  onPressed: enabled ? () => onAdd(r.token) : null,
                ),
            ]),
        if (offerCustomer && !tokens.contains(customer))
          addRow([
            ActionChip(
              avatar: const Icon(Icons.business_outlined, size: 18),
              label: Text(customerText, overflow: TextOverflow.ellipsis),
              tooltip: customerTooltip,
              onPressed: enabled && customerEnabled ? () => onAdd(customer) : null,
            ),
          ]),
      ],
    );
  }
}
