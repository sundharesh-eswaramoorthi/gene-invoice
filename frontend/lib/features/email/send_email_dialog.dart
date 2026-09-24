import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/table/table_providers.dart';
import '../../shared/widgets/role_token_field.dart';
import 'email_entity.dart';
import 'email_models.dart';
import 'email_providers.dart';

enum SendEmailMode {
  single,

  picker,

  bulk,
}

enum EmailComposeOutcome {
  sent,

  closed,

  leftForGmail,
}

Future<EmailComposeOutcome> openSendEmailDialog(
  BuildContext context, {
  required EmailEntityType type,
  required int entityId,
  String? entityLabel,
  EmailEvent? event,
}) async {
  final outcome = await showDialog<EmailComposeOutcome>(
    context: context,
    barrierDismissible: false,
    builder: (_) => SendEmailDialog(
      mode: SendEmailMode.single,
      type: type,
      entityId: entityId,
      entityLabel: entityLabel,
      event: event,
    ),
  );
  return outcome ?? EmailComposeOutcome.closed;
}

Future<EmailComposeOutcome> openSendEmailForPickedRecord(BuildContext context,
    {required EmailEntityType type}) async {
  final outcome = await showDialog<EmailComposeOutcome>(
    context: context,
    barrierDismissible: false,
    builder: (_) => SendEmailDialog(mode: SendEmailMode.picker, type: type),
  );
  return outcome ?? EmailComposeOutcome.closed;
}

Future<Map<String, dynamic>?> openBulkEmailParams(BuildContext context,
    {required EmailEntityType type}) {
  return showDialog<Map<String, dynamic>>(
    context: context,
    barrierDismissible: false,
    builder: (_) => SendEmailDialog(mode: SendEmailMode.bulk, type: type),
  );
}

class SendEmailDialog extends ConsumerStatefulWidget {
  final SendEmailMode mode;
  final EmailEntityType type;
  final int? entityId;
  final String? entityLabel;
  final EmailEvent? event;

  const SendEmailDialog({
    super.key,
    required this.mode,
    required this.type,
    this.entityId,
    this.entityLabel,
    this.event,
  });

  @override
  ConsumerState<SendEmailDialog> createState() => _SendEmailDialogState();
}

class _SendEmailDialogState extends ConsumerState<SendEmailDialog> {
  final _formKey = GlobalKey<FormState>();
  final _subject = TextEditingController();
  final _body = TextEditingController();

  int? _entityId;
  Map<String, dynamic>? _pickedRecord;

  EmailToken? _from;
  final List<EmailToken> _to = [];

  final Map<int, EmailPerson> _people = {};

  EmailContext? _context;
  bool _seeded = false;

  String? _toError;
  String? _recordError;
  String? _error;
  bool _sending = false;

  AutovalidateMode _subjectValidation = AutovalidateMode.disabled;

  EmailPreview? _preview;
  String? _previewError;
  bool _previewing = false;
  Timer? _previewDebounce;

  int _previewSeq = 0;
  String _previewedSubject = '';

  bool get _bulk => widget.mode == SendEmailMode.bulk;

  @override
  void initState() {
    super.initState();
    _entityId = widget.entityId;
    _subject.addListener(() {
      if (_subject.text == _previewedSubject) return;
      _previewedSubject = _subject.text;
      _schedulePreview();
    });
  }

  @override
  void dispose() {
    _previewDebounce?.cancel();
    _subject.dispose();
    _body.dispose();
    super.dispose();
  }

  /// Trimmed, with any pasted line breaks turned into spaces, as the server stores it (E16).
  String get _cleanSubject => _subject.text.replaceAll(RegExp(r'\s*[\r\n]+\s*'), ' ').trim();

  Map<String, dynamic> _fields() => {
        'entityType': widget.type.wire,
        if (_from != null) 'from': _from!.toJson(),
        'to': [for (final t in _to) t.toJson()],
        'subject': _cleanSubject,
        if (_body.text.isNotEmpty) 'body': _body.text,
      };

  Map<String, dynamic> _request(int entityId) => {..._fields(), 'entityId': entityId};

  void _schedulePreview() {
    _previewDebounce?.cancel();
    if (_bulk) return;
    _previewDebounce = Timer(const Duration(milliseconds: 400), _runPreview);
  }

  Future<void> _runPreview() async {
    final entityId = _entityId;
    final seq = ++_previewSeq;
    if (!mounted) return;
    if (entityId == null || _to.isEmpty) {
      setState(() {
        _preview = null;
        _previewError = null;
        _previewing = false;
      });
      return;
    }
    setState(() => _previewing = true);
    try {
      final res =
          await ref.read(dioProvider).post('/api/emails/preview', data: _request(entityId));
      if (!mounted || seq != _previewSeq) return;
      setState(() {
        _preview = EmailPreview.fromJson((res.data as Map).cast<String, dynamic>());
        _previewError = null;
      });
    } catch (e) {
      if (!mounted || seq != _previewSeq) return;
      setState(() {
        _preview = null;
        _previewError = apiErrorMessage(e);
      });
    } finally {
      if (mounted && seq == _previewSeq) setState(() => _previewing = false);
    }
  }

  /// A suggestion arrives with the context after a save (E12); it fills the form once, and only
  /// what the user has not already typed.
  void _seed(EmailContext ctx) {
    if (_seeded) return;
    _seeded = true;
    final suggestion = ctx.suggestion;
    if (suggestion == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      setState(() {
        if (_subject.text.isEmpty) _subject.text = suggestion.subject;
        if (_body.text.isEmpty) _body.text = suggestion.body;
        for (final t in suggestion.to) {
          // A suggested role is added at the level this record offers it, so its chip is the one
          // its group offers and the server is told which POC is meant, even for a token written
          // before levels (L7).
          final token = ctx.roleOf(t)?.token ?? t;
          if (!_to.contains(token)) _to.add(token);
        }
      });
      _schedulePreview();
    });
  }

  void _addTo(EmailToken token) {
    if (_to.contains(token)) return;
    setState(() {
      _to.add(token);
      _toError = null;
    });
    _schedulePreview();
  }

  void _removeTo(EmailToken token) {
    setState(() => _to.remove(token));
    _schedulePreview();
  }

  void _setFrom(EmailToken? token) {
    setState(() => _from = token);
    _schedulePreview();
  }

  Future<EmailPerson?> _pickPerson(String title) {
    final dio = ref.read(dioProvider);
    return showDialog<EmailPerson>(
      context: context,
      builder: (_) => _SearchDialog<EmailPerson>(
        title: title,
        hint: 'Search by name, username or email',
        search: (q) => searchEmailPeople(dio, q),
        labelOf: (p) => p.name,
        subtitleOf: (p) {
          final text = [if (p.username != null) '@${p.username}', if (p.email != null) p.email!]
              .join(' · ');
          return text.isEmpty ? null : text;
        },
      ),
    );
  }

  Future<void> _addPerson() async {
    final person = await _pickPerson('Add a person');
    if (person?.userId == null || !mounted) return;
    _people[person!.userId!] = person;
    _addTo(EmailToken.user(person.userId!));
  }

  Future<void> _chooseRecord() async {
    final dio = ref.read(dioProvider);
    final type = widget.type;
    final row = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => _SearchDialog<Map<String, dynamic>>(
        title: 'Choose the ${type.noun}',
        hint: type.searchHint,
        search: (q) => searchEmailRecords(dio, type, q),
        labelOf: type.recordLabel,
        subtitleOf: type.recordSubtitle,
      ),
    );
    if (row == null || !mounted) return;
    setState(() {
      _pickedRecord = row;
      _entityId = (row['id'] as num).toInt();
      _recordError = null;
      _preview = null;
      _previewError = null;
    });
    _schedulePreview();
  }

  Future<void> _submit() async {
    final subjectOk = _formKey.currentState?.validate() ?? false;
    setState(() {
      _toError = _to.isEmpty ? 'Add at least one recipient' : null;
      _recordError = widget.mode == SendEmailMode.picker && _entityId == null
          ? 'Choose the ${widget.type.noun} this email is about'
          : null;
      _error = null;
      if (!subjectOk) _subjectValidation = AutovalidateMode.onUserInteraction;
    });
    if (!subjectOk || _toError != null || _recordError != null) return;

    if (_bulk) {
      Navigator.of(context).pop(_fields());
      return;
    }

    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _sending = true);
    try {
      final res = await container.read(dioProvider).post('/api/emails', data: _request(_entityId!));
      final email = EmailMessage.fromJson((res.data as Map).cast<String, dynamic>());
      container.invalidate(entityEmailsProvider);
      container.invalidate(inboxUnreadCountProvider);
      container.invalidate(tablePageProvider);
      if (mounted && (ModalRoute.of(context)?.isCurrent ?? false)) {
        Navigator.of(context).pop(EmailComposeOutcome.sent);
      }
      messenger.showSnackBar(SnackBar(content: Text(emailOutcomeMessage(email))));
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  String _roleText(EmailRoleOption r, EmailContext ctx,
      {bool asSender = false, bool levelled = false}) {
    if (_bulk) {
      return levelled
          ? "${r.label} (each ${r.levelLabel.toLowerCase()}'s)"
          : "${r.label} (each record's)";
    }
    final role = levelled ? r.labelWithLevel : r.label;
    if (_entityId == null || ctx.entityId != _entityId || r.resolved == null) return role;
    if (r.resolved == false) return '$role · nobody assigned';
    final people = asSender ? [if (r.sender != null) r.sender!] : r.people;
    return switch (people.length) {
      // Resolved but masked: a customer login sees the role, never the people (E13).
      0 => role,
      1 => '$role · ${people.single.display}',
      2 => '$role · ${people[0].name}, ${people[1].name}',
      _ => '$role · ${people.first.name} + ${people.length - 1} more',
    };
  }

  bool _offeredAtBothLevels(EmailContext ctx, EmailRoleOption r) =>
      ctx.roles.where((other) => other.role == r.role).length > 1;

  String? _roleTooltip(EmailRoleOption r, EmailContext ctx) {
    if (_bulk || _entityId == null || ctx.entityId != _entityId || r.people.length < 2) return null;
    return r.people.map((p) => p.display).join('\n');
  }

  String _customerEmailsText(EmailContext ctx) {
    if (_bulk) return "Customer emails (each record's)";
    if (!ctx.customerEmailsAvailable || _entityId == null || ctx.entityId != _entityId) {
      return 'Customer emails';
    }
    if (ctx.customerAddresses.isEmpty) return 'Customer emails · none on file';
    return 'Customer emails · ${ctx.customerAddresses.map((a) => a.address).join(', ')}';
  }

  /// " · Gmail not connected" after a sender whose email would be saved but not sent (M5). Said
  /// only where a mail service exists: without one nobody's email is sent, and the form's notice
  /// already says so.
  String _gmailNote(EmailPerson? sender, EmailContext ctx) =>
      ctx.delivery.configured && (sender?.gmailNotConnected ?? false)
          ? ' · Gmail not connected'
          : '';

  EmailPerson? _roleSender(EmailRoleOption r, EmailContext ctx) =>
      _bulk || _entityId == null || ctx.entityId != _entityId ? null : r.sender;

  bool _offerConnect(EmailContext ctx) =>
      ctx.delivery.configured && !ctx.restricted && _from == null && ctx.self.gmailNotConnected;

  Future<void> _openGmailConnection() async {
    if (_subject.text.trim().isNotEmpty || _body.text.trim().isNotEmpty) {
      final leave = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Leave this email?'),
          content: const Text('What you have written will not be kept.'),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(context).pop(false), child: const Text('Stay')),
            FilledButton(
                onPressed: () => Navigator.of(context).pop(true), child: const Text('Leave')),
          ],
        ),
      );
      if (leave != true || !mounted) return;
    }
    final router = GoRouter.maybeOf(context);
    Navigator.of(context).pop(_bulk ? null : EmailComposeOutcome.leftForGmail);
    router?.go('/me/gmail');
  }

  String _personText(int userId) {
    final known = _people[userId];
    if (known != null) return known.display;
    for (final p in _preview?.to ?? const <EmailParticipant>[]) {
      if (p.userId == userId) return p.display;
    }
    return 'User #$userId';
  }

  String _tokenText(EmailToken t, EmailContext ctx) {
    if (t.isUser) return _personText(t.userId!);
    if (t.isCustomer) return _customerEmailsText(ctx);
    final option = ctx.roleOf(t);
    if (option == null) return roleKeyLabel(t.role!);
    final bothLevels = _to.where((other) => other.isRole && other.role == t.role).length > 1;
    return _roleText(option, ctx, levelled: bothLevels);
  }

  String? _tokenTooltip(EmailToken t, EmailContext ctx) {
    final option = ctx.roleOf(t);
    return option == null ? null : _roleTooltip(option, ctx);
  }

  Widget _groupLabel(String text) {
    final theme = Theme.of(context);
    return Text(text,
        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant));
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(emailContextProvider(
        (type: widget.type, entityId: _entityId, event: widget.event)));
    final loaded = async.valueOrNull;
    if (loaded != null) {
      _context = loaded;
      _seed(loaded);
    }
    final ctx = _context;
    final narrow = MediaQuery.sizeOf(context).width < 600;
    final problems = _preview?.problems ?? const <String>[];
    final warnings = _preview?.warnings ?? const <String>[];
    final canSend = ctx != null && !_sending && problems.isEmpty;

    return AlertDialog(
      title: _title(ctx),
      content: SizedBox(
        // A phone has nowhere near 620px to give (D-60).
        width: narrow ? double.maxFinite : 620,
        child: ctx == null
            ? async.maybeWhen(
                error: (e, _) => _LoadError(
                  message: apiErrorMessage(e),
                  onRetry: () => ref.invalidate(emailContextProvider),
                ),
                orElse: () => const Padding(
                  padding: EdgeInsets.all(24),
                  child: Center(child: CircularProgressIndicator()),
                ),
              )
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Flexible(
                    child: SingleChildScrollView(child: _form(ctx, loading: async.isLoading)),
                  ),
                  for (final warning in warnings)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: _Notice(
                        icon: Icons.warning_amber_rounded,
                        text: warning,
                        background: Colors.amber.shade100,
                        foreground: Colors.brown.shade800,
                      ),
                    ),
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: _sending ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton.icon(
          icon: _sending
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : Icon(_bulk ? Icons.arrow_forward : Icons.send_outlined, size: 18),
          label: Text(_bulk ? 'Continue' : 'Send'),
          onPressed: canSend ? _submit : null,
        ),
      ],
    );
  }

  Widget _title(EmailContext? ctx) {
    final type = widget.type;
    final String subtitle;
    switch (widget.mode) {
      case SendEmailMode.bulk:
        subtitle = 'Selected ${type.plural} — a separate email for each';
      case SendEmailMode.picker when _pickedRecord == null:
        subtitle = 'Choose the ${type.noun} this email is about';
      case SendEmailMode.picker:
        final loaded = ctx != null && ctx.entityId == _entityId ? ctx.entityLabel : null;
        subtitle = 'About: ${loaded ?? type.recordLabel(_pickedRecord!)}';
      case SendEmailMode.single:
        final label = ctx?.entityLabel ?? widget.entityLabel ?? '${type.label} #$_entityId';
        subtitle = 'About: $label';
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text('Send email'),
        Text(subtitle,
            style: Theme.of(context).textTheme.bodyMedium,
            maxLines: 2,
            overflow: TextOverflow.ellipsis),
      ],
    );
  }

  Widget _form(EmailContext ctx, {required bool loading}) {
    final scheme = Theme.of(context).colorScheme;
    return Form(
      key: _formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (loading) const LinearProgressIndicator(),
          if (!ctx.delivery.configured) ...[
            const _Notice(
              icon: Icons.info_outline,
              text: 'Email delivery is not configured. The email will be saved in the app but '
                  'not sent.',
            ),
            const SizedBox(height: 12),
          ],
          if (widget.mode == SendEmailMode.picker) ...[
            InkWell(
              onTap: _sending ? null : _chooseRecord,
              child: InputDecorator(
                decoration: InputDecoration(
                  labelText: '${widget.type.label} *',
                  errorText: _recordError,
                  suffixIcon: const Icon(Icons.arrow_drop_down),
                ),
                child: Text(
                  _pickedRecord == null ? 'Select…' : widget.type.recordLabel(_pickedRecord!),
                  style:
                      _pickedRecord == null ? TextStyle(color: Theme.of(context).hintColor) : null,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ),
            const SizedBox(height: 12),
          ],
          _fromField(ctx),
          if (_offerConnect(ctx)) _connectLine(),
          const SizedBox(height: 12),
          _toField(ctx),
          const SizedBox(height: 12),
          TextFormField(
            controller: _subject,
            decoration: const InputDecoration(labelText: 'Subject *'),
            inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.emailSubject)],
            autovalidateMode: _subjectValidation,
            validator: (v) => (v ?? '').trim().isEmpty ? 'Enter a subject' : null,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _body,
            minLines: 4,
            maxLines: 12,
            keyboardType: TextInputType.multiline,
            decoration: const InputDecoration(labelText: 'Message', alignLabelWithHint: true),
            inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.emailBody)],
          ),
          if (!_bulk && _entityId != null && _to.isNotEmpty) ...[
            const SizedBox(height: 12),
            _previewPanel(),
          ],
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(_error!, style: TextStyle(color: scheme.error)),
            ),
        ],
      ),
    );
  }

  Widget _fromField(EmailContext ctx) {
    if (ctx.restricted) {
      // A customer login always writes as themselves (E13).
      return const InputDecorator(
        decoration: InputDecoration(labelText: 'From'),
        child: Text('You'),
      );
    }
    final from = _from;
    final chosen = from == null ? null : ctx.roleOf(from);
    final value = from == null
        ? 'self'
        : from.isRole
            // The item's own value, so a token that carries no level (L7) still picks the role the
            // record offers rather than leaving the dropdown without an item for its value.
            ? _fromRoleValue(from.role!, chosen?.level ?? from.level)
            : 'user:${from.userId}';
    final items = <DropdownMenuItem<String>>[];
    final closed = <Widget>[];
    void item(String v, Widget menu, {Widget? shut, bool enabled = true}) {
      items.add(DropdownMenuItem(value: v, enabled: enabled, child: menu));
      closed.add(shut ?? menu);
    }

    item('self', _fromItem('Me (${ctx.self.name})'));
    for (final group in ctx.roleGroups) {
      item('group:${group.level}', _groupLabel(group.label), enabled: false);
      for (final r in group.roles) {
        final note = _gmailNote(_roleSender(r, ctx), ctx);
        item(_fromRoleValue(r.role, r.level),
            _fromItem(_roleText(r, ctx, asSender: true), note: note),
            shut: _fromItem(_roleText(r, ctx, asSender: true, levelled: _offeredAtBothLevels(ctx, r)),
                note: note));
      }
    }
    if (from != null && from.isRole && chosen == null) {
      item(value, _fromItem(roleKeyLabel(from.role!)));
    }
    if (from != null && from.isUser) {
      item(value, _fromItem(_personText(from.userId!), note: _gmailNote(_people[from.userId!], ctx)));
    }
    item('other', _fromItem('Someone else…'));

    return InputDecorator(
      decoration: const InputDecoration(labelText: 'From'),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          value: value,
          isDense: true,
          isExpanded: true,
          items: items,
          selectedItemBuilder: (_) => closed,
          onChanged: _sending
              ? null
              : (v) async {
                  if (v == null || v == value) return;
                  if (v == 'self') {
                    _setFrom(null);
                  } else if (v.startsWith('role:')) {
                    _setFrom(_fromRoleToken(v));
                  } else if (v == 'other') {
                    final person = await _pickPerson('Send as someone else');
                    if (person?.userId == null || !mounted) return;
                    _people[person!.userId!] = person;
                    _setFrom(EmailToken.user(person.userId!));
                  }
                },
        ),
      ),
    );
  }

  Widget _fromItem(String text, {String note = ''}) => FromPickerItem(name: text, note: note);

  String _fromRoleValue(String role, String? level) => 'role:$role:${level ?? ''}';

  EmailToken _fromRoleToken(String value) {
    final parts = value.substring('role:'.length).split(':');
    final level = parts.length > 1 && parts[1].isNotEmpty ? parts[1] : null;
    return EmailToken.role(parts.first, level: level);
  }

  Widget _connectLine() {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(
        children: [
          Icon(Icons.mail_lock_outlined, size: 16, color: theme.colorScheme.outline),
          const SizedBox(width: 6),
          Expanded(
            child: Text('Connect your Gmail to send email', style: theme.textTheme.bodySmall),
          ),
          TextButton(
            onPressed: _sending ? null : _openGmailConnection,
            child: const Text('Connect'),
          ),
        ],
      ),
    );
  }

  /// The To field is now [RoleTokenField], which is this method's own widget tree lifted into
  /// shared/widgets so the automation rule builder addresses people with the SAME picker rather
  /// than a second one that would drift (A3). Everything that needs the record — what a role
  /// resolves to, who holds it, whether there is a customer to write to — is still worked out
  /// here and handed over as a callback.
  Widget _toField(EmailContext ctx) => RoleTokenField(
        label: 'To *',
        errorText: _toError,
        tokens: _to,
        roleGroups: ctx.roleGroups,
        tokenText: (t) => _tokenText(t, ctx),
        tokenTooltip: (t) => _tokenTooltip(t, ctx),
        roleText: (r) => _roleText(r, ctx),
        roleTooltip: (r) => _roleTooltip(r, ctx),
        customerText: _customerEmailsText(ctx),
        customerEnabled: ctx.customerEmailsAvailable,
        customerTooltip: ctx.customerEmailsAvailable ? null : 'There is no customer to write to',
        offerPerson: !ctx.restricted,
        enabled: !_sending,
        onAddPerson: _addPerson,
        onAdd: _addTo,
        onRemove: _removeTo,
      );

  Widget _previewPanel() {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final preview = _preview;
    final heading = theme.textTheme.titleSmall;

    Widget bullet(String text, {Color? color, IconData icon = Icons.circle}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: EdgeInsets.only(top: icon == Icons.circle ? 6 : 1, right: 8),
                child: Icon(icon, size: icon == Icons.circle ? 6 : 16, color: color),
              ),
              Expanded(child: Text(text, style: TextStyle(color: color))),
            ],
          ),
        );

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Expanded(child: Text('Will be sent to', style: heading)),
              if (_previewing)
                const SizedBox(
                    width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
            ],
          ),
          if (_previewError != null)
            Text('Preview unavailable: $_previewError', style: TextStyle(color: scheme.error)),
          if (preview != null) ...[
            if (preview.from != null)
              Padding(
                padding: const EdgeInsets.only(top: 2, bottom: 4),
                child: Text('From ${preview.from!.display}', style: theme.textTheme.bodySmall),
              ),
            if (preview.to.isEmpty) bullet('Nobody'),
            for (final p in preview.to)
              bullet([
                p.display,
                if (p.howAdded.isNotEmpty) p.howAdded,
                if (p.address == null && !p.masked) 'no email address',
              ].join(' — ')),
            if (preview.unresolved.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text('Not assigned', style: heading),
              for (final u in preview.unresolved)
                Tooltip(
                  message: u.reason ?? '',
                  child: bullet('${u.label} — nobody assigned'),
                ),
            ],
            if (preview.problems.isNotEmpty) ...[
              const SizedBox(height: 6),
              for (final problem in preview.problems)
                bullet(problem, color: scheme.error, icon: Icons.error_outline),
            ],
          ],
        ],
      ),
    );
  }
}

/// A role key as a person reads it — "Sales POC". PUBLIC because the automation rule builder
/// addresses the same seats through the same [RoleTokenField] and must not decode them a second,
/// slightly different way (A3).
String roleKeyLabel(String key) => key
    .split('_')
    .where((w) => w.isNotEmpty)
    .map((w) => w == 'POC' ? w : '${w[0]}${w.substring(1).toLowerCase()}')
    .join(' ');

class _Notice extends StatelessWidget {
  final IconData icon;
  final String text;

  final Color? background;
  final Color? foreground;
  const _Notice({required this.icon, required this.text, this.background, this.foreground});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fg = foreground ?? scheme.onTertiaryContainer;
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: background ?? scheme.tertiaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: fg),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: TextStyle(color: fg))),
        ],
      ),
    );
  }
}

class _LoadError extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _LoadError({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
            const SizedBox(height: 8),
            Text('Could not load the email form: $message', textAlign: TextAlign.center),
            TextButton(onPressed: onRetry, child: const Text('Try again')),
          ],
        ),
      );
}

class _SearchDialog<T> extends StatefulWidget {
  final String title;
  final String hint;
  final Future<List<T>> Function(String search) search;
  final String Function(T item) labelOf;
  final String? Function(T item)? subtitleOf;

  const _SearchDialog({
    super.key,
    required this.title,
    required this.hint,
    required this.search,
    required this.labelOf,
    this.subtitleOf,
  });

  @override
  State<_SearchDialog<T>> createState() => _SearchDialogState<T>();
}

class _SearchDialogState<T> extends State<_SearchDialog<T>> {
  late Future<List<T>> _results = widget.search('');
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

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
      title: Text(widget.title),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              autofocus: true,
              decoration: InputDecoration(
                labelText: widget.hint,
                prefixIcon: const Icon(Icons.search),
              ),
              onChanged: _onSearchChanged,
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 300,
              child: FutureBuilder<List<T>>(
                future: _results,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Center(
                        child: Text('Could not search: ${apiErrorMessage(snapshot.error!)}'));
                  }
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  final items = snapshot.data ?? const [];
                  if (items.isEmpty) return const Center(child: Text('Nothing matches'));
                  return ListView.builder(
                    itemCount: items.length,
                    itemBuilder: (context, i) {
                      final item = items[i];
                      final subtitle = widget.subtitleOf?.call(item);
                      return ListTile(
                        title: Text(widget.labelOf(item)),
                        subtitle: subtitle == null ? null : Text(subtitle),
                        onTap: () => Navigator.of(context).pop(item),
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

class FromPickerItem extends StatelessWidget {
  const FromPickerItem({super.key, required this.name, required this.note});

  final String name;

  final String note;

  @override
  Widget build(BuildContext context) {
    final nameText = Text(name, maxLines: 1, softWrap: false, overflow: TextOverflow.ellipsis);
    if (note.isEmpty) return nameText;
    return LayoutBuilder(
      builder: (context, constraints) {
        final style = DefaultTextStyle.of(context).style;
        final painter = TextPainter(
          text: TextSpan(text: note, style: style),
          textDirection: Directionality.of(context),
          textScaler: MediaQuery.textScalerOf(context),
          maxLines: 1,
        )..layout();
        final room = constraints.maxWidth.isFinite
            ? (constraints.maxWidth - painter.width).clamp(0.0, double.infinity)
            : double.infinity;
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (room > 0)
              ConstrainedBox(constraints: BoxConstraints(maxWidth: room), child: nameText),
            Flexible(
              child: Text(note, maxLines: 1, softWrap: false, overflow: TextOverflow.ellipsis),
            ),
          ],
        );
      },
    );
  }
}
