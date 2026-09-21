import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/table/table_providers.dart';
import 'email_entity.dart';
import 'email_models.dart';
import 'email_providers.dart';

enum SendEmailMode {
  /// About one known record.
  single,

  /// From a list page: the dialog first asks which record.
  picker,

  /// Parameters for a bulk action; the table posts them, one email per row.
  bulk,
}

/// How a compose form about one record ended.
enum EmailComposeOutcome {
  /// The email was saved: sent, queued, or kept in the app unsent.
  sent,

  /// Closed without Send, or while a send was still out (its snackbar then says how it went).
  closed,

  /// Closed on the way to the Gmail connection page ("Connect"). The app is already headed there,
  /// so a caller must not navigate anywhere else afterwards: a later go() would win over it.
  leftForGmail,
}

/// Opens the compose dialog about one record.
Future<EmailComposeOutcome> openSendEmailDialog(
  BuildContext context, {
  required EmailEntityType type,
  required int entityId,
  String? entityLabel,
  EmailEvent? event,
}) async {
  final outcome = await showDialog<EmailComposeOutcome>(
    context: context,
    // A tap beside the dialog must not throw away a half-written email.
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

/// The bulk action's parameters, `{entityType, from, to, subject, body}`, or null when abandoned.
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

  /// The record the email is about. In picker mode it stays null until one is chosen; in bulk
  /// mode it is always null, since every row is its own record.
  int? _entityId;
  Map<String, dynamic>? _pickedRecord;

  /// Null means the caller themselves, which the server assumes when From is left out.
  EmailToken? _from;
  final List<EmailToken> _to = [];

  /// Names for people added through the search; a token carries only the id.
  final Map<int, EmailPerson> _people = {};

  /// The documents chosen for this email, in the order they were chosen — which is the order the
  /// server keeps them in (E17). The whole record is held, not just the id, so a chip can name
  /// the file and its size without asking for the list again.
  final List<EmailAttachable> _attached = [];

  /// The last context loaded. Choosing another record reloads it, and the form keeps showing
  /// this one meanwhile rather than collapsing to a spinner.
  EmailContext? _context;
  bool _seeded = false;

  String? _toError;
  String? _recordError;
  String? _error;
  bool _sending = false;

  /// Off until a Send finds the subject missing. From then on the field checks itself as it is
  /// typed in, so "Enter a subject" goes as soon as there is one — as the To error goes when a
  /// recipient is added — rather than staying until the next Send.
  AutovalidateMode _subjectValidation = AutovalidateMode.disabled;

  EmailPreview? _preview;
  String? _previewError;
  bool _previewing = false;
  Timer? _previewDebounce;

  /// Only the newest preview may land; an older answer arriving late would describe a form that
  /// no longer exists.
  int _previewSeq = 0;
  String _previewedSubject = '';
  String _previewedBody = '';

  bool get _bulk => widget.mode == SendEmailMode.bulk;

  /// The record the compose form's own lists — attachable documents, placeholders — are about.
  /// Null in bulk mode and before a record is picked, where there is no one record to ask about.
  EmailRecordKey? get _recordKey =>
      _bulk || _entityId == null ? null : (type: widget.type, entityId: _entityId!);

  @override
  void initState() {
    super.initState();
    _entityId = widget.entityId;
    _subject.addListener(() {
      // The listener also fires for cursor moves; only a changed subject is worth a request.
      if (_subject.text == _previewedSubject) return;
      _previewedSubject = _subject.text;
      _schedulePreview();
    });
    // The body is previewed for the same reason the subject is: the preview shows it with this
    // record's placeholders filled in (M3), so an edit to it changes what the preview says.
    _body.addListener(() {
      if (_body.text == _previewedBody) return;
      _previewedBody = _body.text;
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

  // ---- request ----------------------------------------------------------------------

  /// Trimmed, with any pasted line breaks turned into spaces, as the server stores it (E16).
  String get _cleanSubject => _subject.text.replaceAll(RegExp(r'\s*[\r\n]+\s*'), ' ').trim();

  Map<String, dynamic> _fields() => {
        'entityType': widget.type.wire,
        if (_from != null) 'from': _from!.toJson(),
        'to': [for (final t in _to) t.toJson()],
        'subject': _cleanSubject,
        if (_body.text.isNotEmpty) 'body': _body.text,
      };

  /// The request about one record. The chosen documents go out here rather than in [_fields],
  /// because a bulk send is one email per row and a document belongs to one record: nothing
  /// offered on the form the parameters were written on is on any of the rows it runs over.
  Map<String, dynamic> _request(int entityId) => {
        ..._fields(),
        'entityId': entityId,
        if (_attached.isNotEmpty) 'documentIds': [for (final d in _attached) d.id],
      };

  // ---- preview ----------------------------------------------------------------------

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

  // ---- editing ----------------------------------------------------------------------

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

  Future<EmailPerson?> _pickPerson(String title) =>
      pickEmailPerson(context, ref.read(dioProvider), title: title);

  // ---- attachments and placeholders --------------------------------------------------

  /// Offers the documents already on this record or its customer, seeded with what is already
  /// chosen, so the picker is one list to tick rather than a dialog per file (E17).
  Future<void> _chooseAttachments() async {
    final key = _recordKey;
    if (key == null) return;
    final chosen = await showDialog<List<EmailAttachable>>(
      context: context,
      builder: (_) => _AttachDialog(record: key, chosen: List.of(_attached)),
    );
    if (chosen == null || !mounted) return;
    setState(() {
      _attached
        ..clear()
        ..addAll(chosen);
    });
    // The server warns on the preview that attachments are kept but not delivered yet (E18), so
    // choosing one has to ask for a new preview to hear it.
    _schedulePreview();
  }

  void _detach(EmailAttachable document) {
    setState(() => _attached.remove(document));
    _schedulePreview();
  }

  /// Offers this record's placeholders with what each says on it, and puts the chosen one where
  /// the cursor is (M4). Which field it goes into is the caller's: both the subject and the body
  /// carry the same list, and a controller's own selection is the only thing that says where.
  Future<void> _insertPlaceholder(TextEditingController field) async {
    final key = _recordKey;
    if (key == null) return;
    final chosen = await showDialog<EmailPlaceholder>(
      context: context,
      builder: (_) => _PlaceholderDialog(record: key),
    );
    if (chosen == null || !mounted) return;
    insertAtCursor(field, chosen.key);
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
      // The last preview described another record.
      _preview = null;
      _previewError = null;
      // So did the documents: they were that record's or its customer's, and this record cannot
      // send them. Dropped here rather than refused by the server after Send.
      _attached.clear();
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

    // Taken before the request: the dialog can be gone by the time it answers (the back button
    // still closes it), and a gone dialog's ref throws, which would save the email without a word
    // and leave the lists behind it on their old rows.
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _sending = true);
    try {
      final res = await container.read(dioProvider).post('/api/emails', data: _request(_entityId!));
      final email = EmailMessage.fromJson((res.data as Map).cast<String, dynamic>());
      // The record's Email tab, and the Inbox of anyone it reached — the sender among them.
      container.invalidate(entityEmailsProvider);
      container.invalidate(inboxUnreadCountProvider);
      container.invalidate(tablePageProvider);
      // Closed already, the dialog may still be animating out; a pop then would close the page.
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

  // ---- labels -----------------------------------------------------------------------

  /// A role in To reaches everyone who holds it; as the sender ([asSender]) it is one person. One
  /// person is named in full; several by name only — "Anil, Bala", or "Anil + 2 more" from three
  /// on, so a long list still says how many there are when the chip is cut short. The chip's
  /// tooltip ([_roleTooltip]) and the preview give each one's address. Until [ctx] is the picked
  /// record's, the role is named alone: the people of the record picked before are not this one's.
  /// Which level the role is at is said once, by the group it sits under (§4), not on every chip;
  /// [levelled] names it on the chip itself, for one that stands away from its heading.
  String _roleText(EmailRoleOption r, EmailContext ctx,
      {bool asSender = false, bool levelled = false}) {
    // Every row is its own record, so a levelled chip says whose POC each row's is.
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

  /// The record offers this role at both levels, so wherever its name stands away from the group
  /// headings — the closed From field — that name has to say which of the two it is (§4).
  bool _offeredAtBothLevels(EmailContext ctx, EmailRoleOption r) =>
      ctx.roles.where((other) => other.role == r.role).length > 1;

  /// Everyone a role reaches, one per line with their address, when its chip names them in short.
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

  /// The one person who would send as role [r] — when the From item names them for this record.
  EmailPerson? _roleSender(EmailRoleOption r, EmailContext ctx) =>
      _bulk || _entityId == null || ctx.entityId != _entityId ? null : r.sender;

  /// "Me" is the sender, with no working Gmail of their own: the form offers the way to connect.
  bool _offerConnect(EmailContext ctx) =>
      ctx.delivery.configured && !ctx.restricted && _from == null && ctx.self.gmailNotConnected;

  /// Leaves the form for the Gmail connection page. The form closes on the way — the page is
  /// behind it — so anything written is asked about first. It closes saying so
  /// ([EmailComposeOutcome.leftForGmail]), so a caller that would move on after it (a new
  /// invoice's form heading back to the list) leaves the app on the Gmail page instead. The bulk
  /// form hands back parameters, not an outcome: closed without them, the bulk action is dropped.
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
    // A suggested person the form was never told the name of; the preview knows it.
    for (final p in _preview?.to ?? const <EmailParticipant>[]) {
      if (p.userId == userId) return p.display;
    }
    return 'User #$userId';
  }

  String _tokenText(EmailToken t, EmailContext ctx) {
    if (t.isUser) return _personText(t.userId!);
    if (t.isCustomer) return _customerEmailsText(ctx);
    final option = ctx.roleOf(t);
    if (option == null) return _roleKeyLabel(t.role!);
    // A chip in the To box stands away from the headings that name the level, so one role added
    // at both levels says on each chip which POC it is — otherwise the two would read alike, and
    // in bulk, where neither names anybody, identically.
    final bothLevels = _to.where((other) => other.isRole && other.role == t.role).length > 1;
    return _roleText(option, ctx, levelled: bothLevels);
  }

  String? _tokenTooltip(EmailToken t, EmailContext ctx) {
    final option = ctx.roleOf(t);
    return option == null ? null : _roleTooltip(option, ctx);
  }

  /// The heading over one level's roles — "Customer level", then "Invoice level" (§4). To and the
  /// From picker use the same words, so the two lists read as one.
  Widget _groupLabel(String text) {
    final theme = Theme.of(context);
    return Text(text,
        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant));
  }

  // ---- build ------------------------------------------------------------------------

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
                  // Right above Send, outside the scrolling form, so they are read before
                  // sending. They do not stop it: the email is saved either way.
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
        // The table states the exact count in its confirmation, before anything is sent.
        subtitle = 'Selected ${type.plural} — a separate email for each';
      case SendEmailMode.picker when _pickedRecord == null:
        subtitle = 'Choose the ${type.noun} this email is about';
      case SendEmailMode.picker:
        // The server's label once the chosen record's context has loaded, the row's until then.
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
          _insertFieldRow(_subject),
          const SizedBox(height: 12),
          TextFormField(
            controller: _body,
            minLines: 4,
            maxLines: 12,
            keyboardType: TextInputType.multiline,
            decoration: const InputDecoration(labelText: 'Message', alignLabelWithHint: true),
            inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.emailBody)],
          ),
          _insertFieldRow(_body),
          if (_recordKey != null) ...[
            const SizedBox(height: 12),
            _attachmentsField(),
          ],
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
    // Every item twice over: what the open menu shows, and what the closed field shows once it is
    // the one chosen. They are built together so the two lists stay in step, as DropdownButton
    // needs them to be.
    final items = <DropdownMenuItem<String>>[];
    final closed = <Widget>[];
    void item(String v, Widget menu, {Widget? shut, bool enabled = true}) {
      items.add(DropdownMenuItem(value: v, enabled: enabled, child: menu));
      closed.add(shut ?? menu);
    }

    item('self', _fromItem('Me (${ctx.self.name})'));
    // The same groups as To, in the same order, each role naming the one person who would send for
    // it (§4). A heading is shown but never chosen.
    for (final group in ctx.roleGroups) {
      item('group:${group.level}', _groupLabel(group.label), enabled: false);
      for (final r in group.roles) {
        final note = _gmailNote(_roleSender(r, ctx), ctx);
        // The menu item sits under the heading that says its level; the closed field does not, so
        // there a role offered at both levels says which of the two it is — otherwise the two
        // would read alike, and in bulk, which has no preview to tell them apart, identically.
        item(_fromRoleValue(r.role, r.level),
            _fromItem(_roleText(r, ctx, asSender: true), note: note),
            shut: _fromItem(_roleText(r, ctx, asSender: true, levelled: _offeredAtBothLevels(ctx, r)),
                note: note));
      }
    }
    // A role chosen before the record was, which that record does not offer: a list of users
    // offers the customer seats' roles, an internal user none. It stays on show, as a To chip
    // does, and the preview says why it cannot be used; without an item of its own the dropdown
    // would have no item for its value.
    if (from != null && from.isRole && chosen == null) {
      item(value, _fromItem(_roleKeyLabel(from.role!)));
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
                    // "Someone else…": nothing changes unless a person is actually picked.
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

  /// One item of the From picker: who would send, with the marker of §6 after them when their
  /// email would be saved but not sent. The name is what gives way when the row is too narrow —
  /// the marker is short, and it is the reason to read the item at all: a picker that cuts it off
  /// ("Collection POC · Carlos Duarte <…> · Gmail not con…") cannot be used to choose a sender
  /// whose email actually goes out.
  Widget _fromItem(String text, {String note = ''}) => FromPickerItem(name: text, note: note);

  /// The From picker's value for a role at its level, "role:SALES_POC:CUSTOMER", and back again:
  /// the same role at the two levels is two items, so the level has to be part of the value.
  String _fromRoleValue(String role, String? level) => 'role:$role:${level ?? ''}';

  EmailToken _fromRoleToken(String value) {
    final parts = value.substring('role:'.length).split(':');
    final level = parts.length > 1 && parts[1].isNotEmpty ? parts[1] : null;
    return EmailToken.role(parts.first, level: level);
  }

  /// Under From while "Me" has no working Gmail: the email would be saved but not sent.
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

  /// A row of chips under a field, the way To offers its roles: a small heading and the chips
  /// that add to the field above.
  Widget _chipRow(List<Widget> children) => Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Wrap(
          spacing: 6,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: children,
        ),
      );

  /// "Insert field" under the subject and under the message (M4). It sits under its own field
  /// rather than inside it, because what it inserts goes where the cursor is: a control the field
  /// owns would take the focus, and with it the cursor, before it could be used.
  ///
  /// Only where there is a record. A placeholder's whole point is the value it takes on one
  /// record, and in bulk — where every row is its own record — there is none to read it from;
  /// the form would be offering a list of samples belonging to nobody.
  Widget _insertFieldRow(TextEditingController field) {
    if (_recordKey == null) return const SizedBox.shrink();
    return _chipRow([
      ActionChip(
        avatar: const Icon(Icons.data_object, size: 18),
        label: const Text('Insert field'),
        onPressed: _sending ? null : () => _insertPlaceholder(field),
      ),
    ]);
  }

  /// The documents going out with the email: one chip each, and the way to add more (E17).
  Widget _attachmentsField() {
    final theme = Theme.of(context);
    final attach = ActionChip(
      avatar: const Icon(Icons.attach_file, size: 18),
      label: const Text('Attach…'),
      onPressed: _sending ? null : _chooseAttachments,
    );
    // With nothing chosen there is no list to label, so the row says what it is itself; with
    // something chosen the box's own label does, as the To field's does.
    if (_attached.isEmpty) {
      return _chipRow([Text('Attachments', style: theme.textTheme.labelLarge), attach]);
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        InputDecorator(
          decoration: const InputDecoration(labelText: 'Attachments'),
          child: Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final document in _attached)
                InputChip(
                  avatar: const Icon(Icons.description_outlined, size: 18),
                  label: Text('${document.filename} · ${document.size}',
                      overflow: TextOverflow.ellipsis),
                  deleteButtonTooltipMessage: 'Remove',
                  onDeleted: _sending ? null : () => _detach(document),
                ),
            ],
          ),
        ),
        _chipRow([attach]),
      ],
    );
  }

  Widget _toField(EmailContext ctx) {
    final theme = Theme.of(context);
    IconData iconOf(EmailToken t) => t.isUser
        ? Icons.person_outline
        : t.isRole
            ? Icons.badge_outlined
            : Icons.business_outlined;

    // A level's chips, without the roles already in To. A group left with none is not shown, as a
    // level the record has no roles at is not (§4).
    final groups = [
      for (final group in ctx.roleGroups)
        (
          label: group.label,
          roles: [
            for (final r in group.roles)
              if (!_to.contains(r.token)) r,
          ],
        ),
    ];
    const customer = EmailToken.customer();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        InputDecorator(
          decoration: InputDecoration(labelText: 'To *', errorText: _toError),
          child: _to.isEmpty
              ? Text('Add recipients below', style: TextStyle(color: theme.hintColor))
              : Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final t in _to)
                      // A chip shows its own tooltip only when it can be pressed, and this one
                      // can only be removed. An empty message adds nothing; over the delete
                      // button its "Remove" wins.
                      Tooltip(
                        message: _tokenTooltip(t, ctx) ?? '',
                        child: InputChip(
                          avatar: Icon(iconOf(t), size: 18),
                          label: Text(_tokenText(t, ctx), overflow: TextOverflow.ellipsis),
                          deleteButtonTooltipMessage: 'Remove',
                          onDeleted: _sending ? null : () => _removeTo(t),
                        ),
                      ),
                  ],
                ),
        ),
        _chipRow([
          Text('Add', style: theme.textTheme.labelLarge),
          // Only staff may address other staff by name (E13).
          if (!ctx.restricted)
            ActionChip(
              avatar: const Icon(Icons.person_add_alt, size: 18),
              label: const Text('Person…'),
              onPressed: _sending ? null : _addPerson,
            ),
        ]),
        for (final group in groups)
          if (group.roles.isNotEmpty)
            _chipRow([
              _groupLabel(group.label),
              for (final r in group.roles)
                ActionChip(
                  avatar: const Icon(Icons.badge_outlined, size: 18),
                  label: Text(_roleText(r, ctx), overflow: TextOverflow.ellipsis),
                  tooltip: _roleTooltip(r, ctx),
                  onPressed: _sending ? null : () => _addTo(r.token),
                ),
            ]),
        if (!_to.contains(customer))
          _chipRow([
            ActionChip(
              avatar: const Icon(Icons.business_outlined, size: 18),
              label: Text(_customerEmailsText(ctx), overflow: TextOverflow.ellipsis),
              tooltip: ctx.customerEmailsAvailable ? null : 'There is no customer to write to',
              onPressed: _sending || !ctx.customerEmailsAvailable ? null : () => _addTo(customer),
            ),
          ]),
      ],
    );
  }

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
                // Kept on the email as an in-app copy, but no message leaves for them.
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
            // The words themselves, with this record's placeholders filled in — which is what
            // will be stored and read (M3). Shown only once filling them in has changed
            // something: otherwise it is the very text on the form a few lines above.
            if (preview.differsFrom(typedSubject: _cleanSubject, typedBody: _body.text)) ...[
              const SizedBox(height: 8),
              Text('As it will read', style: heading),
              const SizedBox(height: 2),
              SelectableText(preview.subject ?? '',
                  style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
              if ((preview.body ?? '').isNotEmpty)
                ConstrainedBox(
                  // A long body must not push the preview past the dialog and take Send's warnings
                  // off the screen with it; it scrolls inside this box instead.
                  constraints: const BoxConstraints(maxHeight: 140),
                  child: SingleChildScrollView(
                    child: SelectableText(preview.body!, style: theme.textTheme.bodySmall),
                  ),
                ),
            ],
          ],
        ],
      ),
    );
  }
}

/// Puts [text] where the cursor is in [controller], replacing anything selected, and leaves the
/// cursor just after it — how "Insert field" gets a placeholder into the subject or the body (M4).
///
/// A field that has never been focused reports no selection at all (offset -1). There the text
/// goes on the end: somebody who has not put a cursor anywhere is adding to what they have
/// written, and prefixing it would be the one place they did not mean.
void insertAtCursor(TextEditingController controller, String text) {
  final value = controller.value;
  final selection = value.selection;
  if (!selection.isValid) {
    controller.value = TextEditingValue(
      text: value.text + text,
      selection: TextSelection.collapsed(offset: value.text.length + text.length),
    );
    return;
  }
  controller.value = TextEditingValue(
    text: value.text.replaceRange(selection.start, selection.end, text),
    selection: TextSelection.collapsed(offset: selection.start + text.length),
  );
}

/// Searches internal users and resolves with the one picked, or null when the search was closed.
///
/// Public because the compose form is not the only place that names a person the app will write
/// as: an automation rule's email names its sender the same way, and both have to offer the same
/// people described in the same words.
Future<EmailPerson?> pickEmailPerson(BuildContext context, Dio dio, {required String title}) {
  return showDialog<EmailPerson>(
    context: context,
    builder: (_) => _SearchDialog<EmailPerson>(
      title: title,
      hint: 'Search by name, username or email',
      search: (q) => searchEmailPeople(dio, q),
      labelOf: (p) => p.name,
      subtitleOf: (p) {
        final text =
            [if (p.username != null) '@${p.username}', if (p.email != null) p.email!].join(' · ');
        return text.isEmpty ? null : text;
      },
    ),
  );
}

/// The documents this record can send, grouped as the server offers them — the record's own
/// first, then its customer's (E17). It is a list to tick rather than a dialog per file, because
/// choosing three files should not mean opening the same list three times.
///
/// It pops the whole chosen list, in the order it was chosen, or null when it was closed: a
/// picker that applied each tick as it happened would leave Cancel meaning nothing.
class _AttachDialog extends ConsumerStatefulWidget {
  final EmailRecordKey record;
  final List<EmailAttachable> chosen;

  const _AttachDialog({required this.record, required this.chosen});

  @override
  ConsumerState<_AttachDialog> createState() => _AttachDialogState();
}

class _AttachDialogState extends ConsumerState<_AttachDialog> {
  /// A list rather than a set of ids: the server keeps the documents in the order they were
  /// chosen, so the form has to as well.
  late final List<EmailAttachable> _selected = List.of(widget.chosen);

  bool _isSelected(EmailAttachable document) =>
      _selected.any((chosen) => chosen.id == document.id);

  void _toggle(EmailAttachable document, bool on) {
    setState(() {
      if (on) {
        _selected.add(document);
      } else {
        _selected.removeWhere((chosen) => chosen.id == document.id);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final async = ref.watch(emailAttachableProvider(widget.record));
    // The server refuses more than this, so the picker stops offering them rather than letting
    // Send be the thing that says no.
    final full = _selected.length >= maxEmailAttachments;

    return AlertDialog(
      title: const Text('Attach documents'),
      content: SizedBox(
        width: 460,
        height: 380,
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => _LoadError(
            message: apiErrorMessage(e),
            onRetry: () => ref.invalidate(emailAttachableProvider(widget.record)),
          ),
          data: (documents) {
            if (documents.isEmpty) {
              return const Center(
                  child: Text('There are no documents on this record or its customer.'));
            }
            return ListView(
              children: [
                if (full)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      'At most $maxEmailAttachments documents can be attached to one email.',
                      style: TextStyle(color: theme.colorScheme.error),
                    ),
                  ),
                for (final group in _grouped(documents)) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(4, 8, 4, 2),
                    child: Text(group.label,
                        style: theme.textTheme.labelLarge
                            ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  ),
                  for (final document in group.documents)
                    CheckboxListTile(
                      dense: true,
                      value: _isSelected(document),
                      title: Text(document.filename, overflow: TextOverflow.ellipsis),
                      subtitle: Text(document.details),
                      // A full selection can still be undone, only not added to.
                      onChanged: full && !_isSelected(document)
                          ? null
                          : (on) => _toggle(document, on == true),
                    ),
                ],
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          onPressed: async.hasValue ? () => Navigator.of(context).pop(_selected) : null,
          child: Text(_selected.isEmpty ? 'Attach nothing' : 'Attach ${_selected.length}'),
        ),
      ],
    );
  }

  /// The documents under the headings the server named, in the order it sent them: "This invoice"
  /// before "Customer", because the record's own files are what a sender reaches for first.
  List<({String label, List<EmailAttachable> documents})> _grouped(
      List<EmailAttachable> documents) {
    final order = <String>[];
    final byLabel = <String, List<EmailAttachable>>{};
    for (final document in documents) {
      final label = document.sourceLabel.isEmpty ? 'Documents' : document.sourceLabel;
      if (byLabel[label] == null) {
        order.add(label);
        byLabel[label] = [];
      }
      byLabel[label]!.add(document);
    }
    return [for (final label in order) (label: label, documents: byLabel[label]!)];
  }
}

/// The placeholders this record offers, each with what it says on this very record (M4). Grouped
/// by level, as the server groups them: the customer's fields, then the record's own.
class _PlaceholderDialog extends ConsumerWidget {
  final EmailRecordKey record;

  const _PlaceholderDialog({required this.record});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(emailPlaceholdersProvider(record));

    return AlertDialog(
      title: const Text('Insert a field'),
      content: SizedBox(
        width: 460,
        height: 380,
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => _LoadError(
            message: apiErrorMessage(e),
            onRetry: () => ref.invalidate(emailPlaceholdersProvider(record)),
          ),
          data: (groups) {
            if (groups.isEmpty) {
              return const Center(child: Text('This record has no fields to insert.'));
            }
            return ListView(
              children: [
                for (final group in groups) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(4, 8, 4, 2),
                    child: Text(group.label,
                        style: theme.textTheme.labelLarge
                            ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  ),
                  for (final placeholder in group.placeholders)
                    ListTile(
                      dense: true,
                      isThreeLine: true,
                      title: Text(placeholder.label),
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(placeholder.key,
                              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                          // An empty sample is not a rendering quirk: the record has nothing
                          // there, and the email will read with a gap in that place (M4).
                          Text(
                            placeholder.sample.isEmpty
                                ? 'Nothing on this record'
                                : placeholder.sample,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: placeholder.sample.isEmpty
                                  ? theme.colorScheme.error
                                  : theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
                      onTap: () => Navigator.of(context).pop(placeholder),
                    ),
                ],
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
      ],
    );
  }
}

/// COLLECTION_POC → "Collection POC", for a suggested role the context did not describe.
String _roleKeyLabel(String key) => key
    .split('_')
    .where((w) => w.isNotEmpty)
    .map((w) => w == 'POC' ? w : '${w[0]}${w.substring(1).toLowerCase()}')
    .join(' ');

class _Notice extends StatelessWidget {
  final IconData icon;
  final String text;

  /// The theme's tertiary container by default; the preview's warnings are amber.
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

/// Searches the server as the user types, like SearchPickerField's dialog, but says what the
/// search covers — a person's name, username or email; an invoice's number.
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

  // The dialog owns the search, so the list always answers what is in the box now.
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

/// One line of the From picker: who would send, and after them the marker of §6 when their email
/// would be saved but not sent. The row is often narrower than the two together — a long name with
/// its address at phone width leaves less room than the marker alone needs — so the name is
/// measured against what is left after the marker and gives way first, by ellipsis and then
/// altogether. The marker is the reason to read the line at all, so it is never what goes first.
class FromPickerItem extends StatelessWidget {
  const FromPickerItem({super.key, required this.name, required this.note});

  /// Who would send, e.g. "Collection POC · Bob Smith <bob@company.com>".
  final String name;

  /// " · Gmail not connected", or empty when their email would go out.
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
        // The picker also lays an item out with no width limit, to size itself to its widest one.
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
