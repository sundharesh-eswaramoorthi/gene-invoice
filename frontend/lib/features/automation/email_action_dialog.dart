import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../assignees/assignee_providers.dart';
import '../assignees/people_search_dialog.dart';
import '../assignees/recipient_chips_field.dart';
import '../email/email_entity.dart';
import '../email/email_models.dart';
import 'automation_models.dart';
import 'placeholder_button.dart';

/// The tokens a rule's action JSON holds under [field], read tolerantly: anything that is not a
/// token is left out rather than guessed at.
List<EmailToken> tokensIn(Object? raw) => [
      for (final t in (raw is List ? raw : const []))
        if (t is Map && t['type'] is String) EmailToken.fromJson(t.cast<String, dynamic>()),
    ];

EmailToken? tokenIn(Object? raw) =>
    raw is Map && raw['type'] is String ? EmailToken.fromJson(raw.cast<String, dynamic>()) : null;

/// "Collection POC (each customer's)": a role as a rule means it — whoever holds it for each record
/// the rule acts on, not anyone by name.
String ruleRoleLabel(EmailRoleOption r) => "${r.label} (each ${r.levelLabel.toLowerCase()}'s)";

/// How a token reads in a rule: a person by name (when known), the customer's own addresses, or a
/// role for each record.
String ruleTokenLabel(EmailToken t, EmailContext? ctx, {Map<int, String> people = const {}}) {
  if (t.isUser) return people[t.userId] ?? 'User #${t.userId}';
  if (t.isCustomer) return 'Customer emails';
  final option = ctx?.roleOf(t);
  if (option != null) return ruleRoleLabel(option);
  return (t.role ?? '')
      .split('_')
      .where((w) => w.isNotEmpty)
      .map((w) => w == 'POC' ? w : '${w[0]}${w.substring(1).toLowerCase()}')
      .join(' ');
}

/// What a rule offers when the roles could not be loaded: people and the customer's addresses
/// can still be added, and the tokens already there still read.
EmailContext emptyRuleContext(EmailEntityType type) => EmailContext(
      entityType: type,
      entityId: null,
      entityLabel: null,
      entityLink: null,
      delivery: const EmailDelivery(configured: true),
      restricted: false,
      self: const EmailPerson(name: ''),
      roles: const [],
      customerEmailsAvailable: false,
      customerAddresses: const [],
      suggestion: null,
    );

/// The roles a rule's people can be picked from, for records of [type] (§6.13, no record): a
/// progress bar while they load, and [builder] with the empty offer and a way to try again when
/// they cannot be.
class RuleContextBuilder extends ConsumerWidget {
  final EmailEntityType type;
  final Widget Function(BuildContext context, EmailContext ctx) builder;
  const RuleContextBuilder({super.key, required this.type, required this.builder});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = (type: type, entityId: null);
    return ref.watch(assigneeContextProvider(key)).when(
          loading: () => const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: LinearProgressIndicator(),
          ),
          error: (e, _) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Roles could not be loaded: ${apiErrorMessage(e)}',
                        style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  ),
                  TextButton(
                    onPressed: () => ref.invalidate(assigneeContextProvider(key)),
                    child: const Text('Retry'),
                  ),
                ],
              ),
              builder(context, emptyRuleContext(type)),
            ],
          ),
          data: (ctx) => builder(context, ctx),
        );
  }
}

/// Edits a rule's "Send an email" action (A3): who it is from, with the owner to fall back on,
/// who it goes to — people, roles for each record, the customer's own addresses — and the subject
/// and body, which may use placeholders (A4). Resolves with the action's email fields, or null
/// when nothing was changed. People picked here are named in [people] for the card to show.
Future<Map<String, dynamic>?> showEmailActionDialog(
  BuildContext context, {
  required TriggerEntity entity,
  required Map<String, dynamic> action,
  String? ownerName,
  Map<int, String>? people,
}) =>
    showDialog<Map<String, dynamic>>(
      context: context,
      barrierDismissible: false,
      builder: (_) => EmailActionDialog(
        entity: entity,
        action: action,
        ownerName: ownerName,
        people: people ?? {},
      ),
    );

class EmailActionDialog extends ConsumerStatefulWidget {
  final TriggerEntity entity;
  final Map<String, dynamic> action;
  final String? ownerName;
  final Map<int, String> people;

  const EmailActionDialog({
    super.key,
    required this.entity,
    required this.action,
    this.ownerName,
    required this.people,
  });

  @override
  ConsumerState<EmailActionDialog> createState() => _EmailActionDialogState();
}

class _EmailActionDialogState extends ConsumerState<EmailActionDialog> {
  late EmailToken? _from = tokenIn(widget.action['from']);
  late bool _fallback = widget.action['fallbackToOwner'] as bool? ?? true;
  late List<EmailToken> _to = tokensIn(widget.action['to']);
  late final _subject = TextEditingController(text: widget.action['subject'] as String? ?? '');
  late final _body = TextEditingController(text: widget.action['body'] as String? ?? '');
  final _subjectFocus = FocusNode(debugLabel: 'email-subject');
  final _bodyFocus = FocusNode(debugLabel: 'email-body');
  String? _toError;
  String? _subjectError;

  EmailEntityType get _type => widget.entity.emailType;

  @override
  void dispose() {
    _subject.dispose();
    _body.dispose();
    _subjectFocus.dispose();
    _bodyFocus.dispose();
    super.dispose();
  }

  Future<List<EmailPerson>> _search(String q) => searchAssignablePeople(ref.read(dioProvider), q);

  Future<void> _pickSender() async {
    final person = await showPeopleSearchDialog<EmailPerson>(
      context,
      title: 'Send as',
      search: _search,
      labelOf: (p) => p.name,
      subtitleOf: (p) => [
        if (p.username != null) '@${p.username}',
        if (p.email != null && p.email!.isNotEmpty) p.email!,
      ].join(' · '),
    );
    if (person?.userId == null || !mounted) return;
    setState(() {
      widget.people[person!.userId!] = person.name;
      _from = EmailToken.user(person.userId!);
    });
  }

  void _done() {
    final subject = _subject.text.replaceAll(RegExp(r'\s*[\r\n]+\s*'), ' ').trim();
    setState(() {
      _toError = _to.isEmpty ? 'Add at least one recipient' : null;
      _subjectError = subject.isEmpty ? 'Enter a subject' : null;
    });
    if (_toError != null || _subjectError != null) return;
    Navigator.of(context).pop(<String, dynamic>{
      'from': _from?.toJson(),
      'fallbackToOwner': _fallback,
      'to': [for (final t in _to) t.toJson()],
      'subject': subject,
      'body': _body.text,
    });
  }

  static String _roleValue(EmailToken t) => 'role:${t.role}:${t.level ?? ''}';

  Widget _fromField(EmailContext ctx) {
    final from = _from;
    final value = from == null
        ? 'owner'
        : from.isRole
            ? _roleValue(ctx.roleOf(from)?.token ?? from)
            : 'user:${from.userId}';
    final items = <DropdownMenuItem<String>>[
      DropdownMenuItem(
        value: 'owner',
        child: Text(widget.ownerName == null
            ? "The rule's owner"
            : "The rule's owner (${widget.ownerName})"),
      ),
      for (final group in ctx.roleGroups) ...[
        DropdownMenuItem(
          value: 'group:${group.level}',
          enabled: false,
          child: Text(group.label, style: Theme.of(context).textTheme.labelLarge),
        ),
        for (final r in group.roles)
          DropdownMenuItem(value: _roleValue(r.token), child: Text(ruleRoleLabel(r))),
      ],
      if (from != null && from.isRole && ctx.roleOf(from) == null)
        DropdownMenuItem(value: value, child: Text(ruleTokenLabel(from, ctx))),
      if (from != null && from.isUser)
        DropdownMenuItem(value: value, child: Text(ruleTokenLabel(from, ctx, people: widget.people))),
      const DropdownMenuItem(value: 'person', child: Text('Someone else…')),
    ];
    // Held here rather than in a form field, so a person picked from the search shows at once.
    return InputDecorator(
      decoration: const InputDecoration(labelText: 'From'),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          key: const ValueKey('email-from'),
          value: value,
          isDense: true,
          isExpanded: true,
          items: items,
          onChanged: (v) {
            if (v == null || v == value) return;
            if (v == 'owner') {
              setState(() => _from = null);
            } else if (v == 'person') {
              _pickSender();
            } else if (v.startsWith('role:')) {
              final parts = v.substring('role:'.length).split(':');
              setState(() => _from = EmailToken.role(parts.first,
                  level: parts.length > 1 && parts[1].isNotEmpty ? parts[1] : null));
            }
          },
        ),
      ),
    );
  }

  Widget _textWithPlaceholders({
    required String key,
    required TextEditingController controller,
    required FocusNode focusNode,
    required String label,
    required int maxLength,
    bool singleLine = false,
    String? errorText,
  }) =>
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: TextField(
              key: ValueKey(key),
              controller: controller,
              focusNode: focusNode,
              minLines: singleLine ? 1 : 4,
              maxLines: singleLine ? 1 : 10,
              keyboardType: singleLine ? TextInputType.text : TextInputType.multiline,
              inputFormatters: [LengthLimitingTextInputFormatter(maxLength)],
              decoration: InputDecoration(
                labelText: label,
                errorText: errorText,
                alignLabelWithHint: !singleLine,
              ),
              onChanged: (_) {
                if (errorText != null) setState(() => _subjectError = null);
              },
            ),
          ),
          PlaceholderButton(
            key: ValueKey('$key-placeholder'),
            entityType: widget.entity.name,
            controller: controller,
            focusNode: focusNode,
            singleLine: singleLine,
          ),
        ],
      );

  @override
  Widget build(BuildContext context) {
    final narrow = MediaQuery.sizeOf(context).width < 600;
    return AlertDialog(
      title: const Text('Email'),
      content: SizedBox(
        width: narrow ? double.maxFinite : 620,
        child: SingleChildScrollView(
          child: RuleContextBuilder(
            type: _type,
            builder: (context, ctx) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                _fromField(ctx),
                if (_from != null)
                  CheckboxListTile(
                    key: const ValueKey('email-fallback'),
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    value: _fallback,
                    onChanged: (v) => setState(() => _fallback = v ?? true),
                    title: const Text("When they can't send it, send it from the owner instead"),
                  ),
                const SizedBox(height: 12),
                RecipientChipsField(
                  key: const ValueKey('email-to'),
                  ctx: ctx,
                  value: _to,
                  label: 'To *',
                  allowCustomer: true,
                  perRecordLabels: true,
                  knownLabels: {
                    for (final t in _to)
                      if (t.isUser && widget.people[t.userId] != null) t: widget.people[t.userId]!,
                  },
                  errorText: _toError,
                  searchPeople: (q) async {
                    final found = await _search(q);
                    for (final p in found) {
                      if (p.userId != null) widget.people[p.userId!] = p.name;
                    }
                    return found;
                  },
                  onChanged: (tokens) => setState(() {
                    _to = tokens;
                    _toError = null;
                  }),
                ),
                const SizedBox(height: 12),
                _textWithPlaceholders(
                  key: 'email-subject',
                  controller: _subject,
                  focusNode: _subjectFocus,
                  label: 'Subject *',
                  maxLength: FieldLimits.emailSubject,
                  singleLine: true,
                  errorText: _subjectError,
                ),
                const SizedBox(height: 12),
                _textWithPlaceholders(
                  key: 'email-body',
                  controller: _body,
                  focusNode: _bodyFocus,
                  label: 'Message',
                  maxLength: FieldLimits.emailBody,
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
            key: const ValueKey('email-done'), onPressed: _done, child: const Text('Done')),
      ],
    );
  }
}
