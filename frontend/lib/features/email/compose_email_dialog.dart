import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/table/data_table_scaffold.dart' show BulkSelection;
import '../../shared/models/customer.dart';
import '../../shared/models/email.dart';
import '../../shared/models/invoice.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../auth/auth_controller.dart';
import '../customers/customers_screen.dart' show searchCustomers;
import 'email_providers.dart';

/// What the form finds wrong before sending, by field. Empty when the email may go.
@immutable
class ComposeProblems {
  final String? target;
  final String? from;
  final String? to;
  final String? subject;

  const ComposeProblems({this.target, this.from, this.to, this.subject});

  bool get isEmpty => target == null && from == null && to == null && subject == null;
}

/// The form's own checks, the same ones the server makes: something to send about, a sender, at
/// least one recipient, and a subject that is more than spaces. The body may be empty.
ComposeProblems checkCompose({
  required bool hasTarget,
  required bool hasSender,
  required int recipients,
  required String subject,
}) =>
    ComposeProblems(
      target: hasTarget ? null : 'Choose what this email is about',
      from: hasSender ? null : 'Choose who the email is from',
      to: recipients > 0 ? null : 'Add at least one recipient',
      subject: subject.trim().isEmpty ? 'Enter a subject' : null,
    );

/// Opens the form for one email. [id] fixes what it is about, as on a details page or a table row;
/// without it the form asks, as the list page's "Send email" does. Resolves true once sent.
Future<bool> showSendEmailDialog(
  BuildContext context, {
  required EmailTargetType type,
  int? id,
  String? label,
}) async {
  final sent = await showDialog<bool>(
    context: context,
    // A stray tap outside must not throw away a half-written email.
    barrierDismissible: false,
    builder: (_) => ComposeEmailDialog(type: type, targetId: id, targetLabel: label),
  );
  if (sent == true && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Email sent')));
  }
  return sent == true;
}

/// Opens the form once for the selected rows, which sends one email per row, then says how many
/// were created and how many rows were skipped. Resolves true when any email was created.
Future<bool> sendBulkEmail(
    BuildContext context, EmailTargetType type, BulkSelection selection) async {
  final result = await showDialog<Map<String, dynamic>>(
    context: context,
    barrierDismissible: false,
    builder: (_) => ComposeEmailDialog(type: type, bulk: selection),
  );
  if (result == null || !context.mounted) return false;
  final outcome = BulkEmailOutcome.fromJson(result);
  if (outcome.skipped.isEmpty && outcome.failed.isEmpty && !outcome.truncated) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(outcome.headline)));
  } else {
    await showDialog<void>(
      context: context,
      builder: (_) => BulkEmailResultDialog(outcome: outcome),
    );
  }
  return outcome.created > 0;
}

/// What a bulk send did, row by row.
class BulkEmailOutcome {
  final int requested;
  final int created;
  final List<({int id, String reason})> skipped;
  final List<({int id, String reason})> failed;
  final bool truncated;
  final int limit;

  const BulkEmailOutcome({
    required this.requested,
    required this.created,
    required this.skipped,
    required this.failed,
    required this.truncated,
    required this.limit,
  });

  static List<({int id, String reason})> _outcomes(Object? raw) => ((raw as List?) ?? const [])
      .cast<Map<String, dynamic>>()
      .map((o) => (id: (o['id'] as num).toInt(), reason: o['reason'] as String? ?? ''))
      .toList();

  factory BulkEmailOutcome.fromJson(Map<String, dynamic> json) => BulkEmailOutcome(
        requested: (json['requested'] as num?)?.toInt() ?? 0,
        created: ((json['succeeded'] as List?) ?? const []).length,
        skipped: _outcomes(json['skipped']),
        failed: _outcomes(json['failed']),
        truncated: json['truncated'] as bool? ?? false,
        limit: (json['limit'] as num?)?.toInt() ?? 0,
      );

  String get headline {
    final emails = '$created ${created == 1 ? 'email' : 'emails'} created';
    final rows = '${skipped.length} ${skipped.length == 1 ? 'row' : 'rows'} skipped';
    final broke = failed.isEmpty ? '' : ', ${failed.length} failed';
    return '$emails, $rows$broke';
  }
}

class BulkEmailResultDialog extends StatelessWidget {
  final BulkEmailOutcome outcome;
  const BulkEmailResultDialog({super.key, required this.outcome});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('Emails sent'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(outcome.headline, style: theme.textTheme.titleMedium),
              if (outcome.truncated)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    'The filtered set was larger than the ${outcome.limit} records a send handles at '
                    'once. Narrow the filter and send again for the rest.',
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                ),
              if (outcome.skipped.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text('Skipped', style: theme.textTheme.titleSmall),
                for (final s in outcome.skipped) Text('#${s.id} — ${s.reason}'),
              ],
              if (outcome.failed.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text('Failed', style: theme.textTheme.titleSmall),
                for (final f in outcome.failed) Text('#${f.id} — ${f.reason}'),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
      ],
    );
  }
}

/// Invoices whose number contains [search], newest first, for the list page's invoice picker.
Future<List<InvoiceSummary>> searchInvoices(Dio dio, String search) async {
  final res = await dio.get('/api/invoices', queryParameters: {
    'size': 20,
    'sort': 'invoiceDate,desc',
    if (search.isNotEmpty) 'filter': ['invoiceNumber:contains:$search'],
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(InvoiceSummary.fromJson)
      .toList();
}

/// The compose form: From, To, Subject and Body, for one email or one per selected row.
class ComposeEmailDialog extends ConsumerStatefulWidget {
  final EmailTargetType type;

  /// What a single email is about; null lets the user choose.
  final int? targetId;
  final String? targetLabel;

  /// Set for a bulk send: the form is filled in once and one email goes per row.
  final BulkSelection? bulk;

  const ComposeEmailDialog({
    super.key,
    required this.type,
    this.targetId,
    this.targetLabel,
    this.bulk,
  });

  @override
  ConsumerState<ComposeEmailDialog> createState() => _ComposeEmailDialogState();
}

class _ComposeEmailDialogState extends ConsumerState<ComposeEmailDialog> {
  int? _targetId;
  Customer? _pickedCustomer;
  InvoiceSummary? _pickedInvoice;

  EmailPartyType _fromType = EmailPartyType.USER;
  StaffOption? _fromUser;
  RoleOption? _fromRole;

  final List<StaffOption> _toUsers = [];
  final List<RoleOption> _toRoles = [];

  /// A single email's picked customer addresses.
  final Set<String> _toAddresses = {};

  /// A bulk send's choice: every address of each row's own customer.
  bool _allCustomerEmails = false;

  final _subject = TextEditingController();
  final _body = TextEditingController();

  ComposeProblems _problems = const ComposeProblems();
  bool _submitted = false;
  bool _sending = false;
  String? _error;

  bool get _isBulk => widget.bulk != null;

  @override
  void initState() {
    super.initState();
    _targetId = widget.targetId;
    // Most emails are from whoever is writing them, so that is where the form starts.
    final me = ref.read(currentUserProvider);
    if (me != null && !me.isCustomer) {
      _fromUser = StaffOption(id: me.id, username: me.username, fullName: me.fullName, role: me.role);
    }
  }

  @override
  void dispose() {
    _subject.dispose();
    _body.dispose();
    super.dispose();
  }

  int get _recipientCount =>
      _toUsers.length +
      _toRoles.length +
      (_isBulk ? (_allCustomerEmails ? 1 : 0) : _toAddresses.length);

  ComposeProblems _check() => checkCompose(
        hasTarget: _isBulk || _targetId != null,
        hasSender: _fromType == EmailPartyType.USER ? _fromUser != null : _fromRole != null,
        recipients: _recipientCount,
        subject: _subject.text,
      );

  /// Changes the form and, once Send has been tried, keeps its complaints up to date.
  void _update(VoidCallback change) => setState(() {
        change();
        if (_submitted) _problems = _check();
      });

  Future<void> _send() async {
    final problems = _check();
    setState(() {
      _submitted = true;
      _problems = problems;
      _error = null;
    });
    if (!problems.isEmpty) return;

    final email = <String, dynamic>{
      'from': {
        'type': _fromType.name,
        'id': _fromType == EmailPartyType.USER ? _fromUser!.id : _fromRole!.id,
      },
      'to': {
        'userIds': _toUsers.map((u) => u.id).toList(),
        'roleIds': _toRoles.map((r) => r.id).toList(),
        if (_isBulk) 'allCustomerEmails': _allCustomerEmails,
        if (!_isBulk) 'customerEmails': _toAddresses.toList(),
      },
      'subject': _subject.text.trim(),
      'body': _body.text,
    };

    setState(() => _sending = true);
    try {
      final dio = ref.read(dioProvider);
      if (_isBulk) {
        final res = await dio.post('/api/emails/bulk', data: {
          'targetType': widget.type.wire,
          ...widget.bulk!.toJson(),
          'email': email,
        });
        ref.invalidate(emailTabProvider);
        if (mounted) Navigator.of(context).pop((res.data as Map).cast<String, dynamic>());
      } else {
        await dio.post('/api/emails', data: {
          widget.type == EmailTargetType.customer ? 'customerId' : 'invoiceId': _targetId,
          ...email,
        });
        ref.invalidate(emailTabProvider);
        if (mounted) Navigator.of(context).pop(true);
      }
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final count = widget.bulk?.count ?? 1;
    return AlertDialog(
      title: Text(_isBulk ? 'Send email to $count ${_plural(widget.type.noun, count)}' : 'Send email'),
      content: SizedBox(
        width: 580,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              _about(theme),
              const SizedBox(height: 16),
              _sectionLabel(theme, 'From *'),
              _fromSection(),
              const SizedBox(height: 16),
              _sectionLabel(theme, 'To *'),
              _toSection(theme),
              const SizedBox(height: 16),
              TextField(
                controller: _subject,
                decoration: InputDecoration(labelText: 'Subject *', errorText: _problems.subject),
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.emailSubject)],
                onChanged: (_) => _update(() {}),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _body,
                minLines: 5,
                maxLines: 12,
                keyboardType: TextInputType.multiline,
                decoration: const InputDecoration(labelText: 'Body', alignLabelWithHint: true),
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.emailBody)],
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _sending ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton.icon(
          icon: _sending
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.send_outlined),
          label: Text(_isBulk ? 'Send $count ${_plural('email', count)}' : 'Send'),
          onPressed: _sending ? null : _send,
        ),
      ],
    );
  }

  static String _plural(String noun, int n) => n == 1 ? noun : '${noun}s';

  static String _people(int n) => n == 1 ? 'person' : 'people';

  Widget _sectionLabel(ThemeData theme, String text) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Text(text, style: theme.textTheme.labelLarge),
      );

  // ---- what it is about ----------------------------------------------------------

  Widget _about(ThemeData theme) {
    final bulk = widget.bulk;
    if (bulk != null) {
      final what = bulk.allMatching
          ? 'Every ${widget.type.noun} matching the current filter (${bulk.count})'
          : '${bulk.count} selected ${_plural(widget.type.noun, bulk.count)}';
      return _aboutLine(theme,
          '$what — each gets its own email, linked to that ${widget.type.noun}.');
    }
    if (widget.targetId != null) {
      final addresses = ref.watch(
          emailAddressesProvider(EmailAddressesRequest(widget.type, widget.targetId!)));
      final loaded = addresses.valueOrNull;
      final label = widget.type == EmailTargetType.customer
          ? (loaded?.customerName ?? widget.targetLabel ?? 'Customer #${widget.targetId}')
          : [
              loaded?.invoiceNumber ?? widget.targetLabel ?? 'Invoice #${widget.targetId}',
              if (loaded != null) loaded.customerName,
            ].join(' • ');
      return _aboutLine(theme, label);
    }
    final dio = ref.read(dioProvider);
    return widget.type == EmailTargetType.customer
        ? SearchPickerField<Customer>(
            label: 'Customer',
            required: true,
            value: _pickedCustomer,
            labelOf: (c) => c.name,
            subtitleOf: (c) => c.email,
            search: (q) => searchCustomers(dio, q),
            errorText: _problems.target,
            onChanged: (c) => _update(() {
              _pickedCustomer = c;
              _targetId = c.id;
              _toAddresses.clear();
            }),
          )
        : SearchPickerField<InvoiceSummary>(
            label: 'Invoice',
            required: true,
            value: _pickedInvoice,
            labelOf: (i) => '${i.invoiceNumber} • ${i.customerName}',
            search: (q) => searchInvoices(dio, q),
            searchLabel: 'Search by invoice number',
            errorText: _problems.target,
            onChanged: (i) => _update(() {
              _pickedInvoice = i;
              _targetId = i.id;
              _toAddresses.clear();
            }),
          );
  }

  Widget _aboutLine(ThemeData theme, String text) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            widget.type == EmailTargetType.customer
                ? Icons.people_outline
                : Icons.receipt_long_outlined,
            size: 18,
            color: theme.colorScheme.onSurfaceVariant,
          ),
          const SizedBox(width: 8),
          Expanded(child: Text('About: $text', style: theme.textTheme.bodyMedium)),
        ],
      );

  // ---- from ----------------------------------------------------------------------

  Future<List<StaffOption>> _searchStaff(String q) async {
    final res = await ref.read(dioProvider).get('/api/emails/staff', queryParameters: {
      if (q.isNotEmpty) 'q': q,
      'limit': 25,
    });
    return (res.data as List).cast<Map<String, dynamic>>().map(StaffOption.fromJson).toList();
  }

  static String _roleLabel(RoleOption r) =>
      r.email == null ? '${r.name} (no email address)' : '${r.name} — ${r.email}';

  Widget _fromSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        SegmentedButton<EmailPartyType>(
          segments: const [
            ButtonSegment(
                value: EmailPartyType.USER, icon: Icon(Icons.person_outline), label: Text('User')),
            ButtonSegment(
                value: EmailPartyType.ROLE, icon: Icon(Icons.groups_outlined), label: Text('Role')),
          ],
          selected: {_fromType},
          onSelectionChanged: (s) => _update(() => _fromType = s.first),
        ),
        const SizedBox(height: 8),
        if (_fromType == EmailPartyType.USER)
          SearchPickerField<StaffOption>(
            label: 'User',
            value: _fromUser,
            labelOf: (u) => u.email == null ? u.display : '${u.display} <${u.email}>',
            subtitleOf: (u) => [u.email ?? '@${u.username}', if (u.role != null) u.role!].join(' • '),
            search: _searchStaff,
            searchLabel: 'Search by name, username or email',
            errorText: _problems.from,
            onChanged: (u) => _update(() => _fromUser = u),
          )
        else
          ref.watch(emailRoleOptionsProvider).when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Could not load roles: ${apiErrorMessage(e)}'),
                data: (roles) => DropdownButtonFormField<int>(
                  initialValue: _fromRole?.id,
                  isExpanded: true,
                  decoration: InputDecoration(
                    labelText: 'Role',
                    errorText: _problems.from,
                    // The address is looked up when the email is sent, and shown on it.
                    helperText: 'The role\'s email address is shown as the sender.',
                  ),
                  items: [
                    for (final r in roles)
                      DropdownMenuItem(
                        value: r.id,
                        child: Text(_roleLabel(r), overflow: TextOverflow.ellipsis),
                      ),
                  ],
                  onChanged: (id) =>
                      _update(() => _fromRole = roles.where((r) => r.id == id).firstOrNull),
                ),
              ),
      ],
    );
  }

  // ---- to ------------------------------------------------------------------------

  Future<void> _addUser() async {
    final picked = await showSearchPicker<StaffOption>(
      context: context,
      title: 'Add a user',
      labelOf: (u) => u.display,
      subtitleOf: (u) => [u.email ?? '@${u.username}', if (u.role != null) u.role!].join(' • '),
      search: _searchStaff,
      searchLabel: 'Search by name, username or email',
    );
    if (picked != null && !_toUsers.contains(picked)) {
      _update(() => _toUsers.add(picked));
    }
  }

  Future<void> _addRole() async {
    final picked = await showDialog<RoleOption>(
      context: context,
      builder: (dialogContext) => Consumer(
        builder: (context, ref, _) => SimpleDialog(
          title: const Text('Add a role'),
          children: [
            ref.watch(emailRoleOptionsProvider).when(
                  loading: () => const Padding(
                      padding: EdgeInsets.all(24), child: LinearProgressIndicator()),
                  error: (e, _) => Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text('Could not load roles: ${apiErrorMessage(e)}')),
                  data: (roles) => Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final r in roles.where((r) => !_toRoles.contains(r)))
                        ListTile(
                          leading: const Icon(Icons.groups_outlined),
                          title: Text(r.name),
                          subtitle: Text('${r.memberCount} ${_people(r.memberCount)} in it now'
                              '${r.email == null ? '' : ' • ${r.email}'}'),
                          onTap: () => Navigator.of(dialogContext).pop(r),
                        ),
                    ],
                  ),
                ),
          ],
        ),
      ),
    );
    if (picked != null && !_toRoles.contains(picked)) {
      _update(() => _toRoles.add(picked));
    }
  }

  Widget _toSection(ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (_toUsers.isNotEmpty || _toRoles.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final u in _toUsers)
                  InputChip(
                    avatar: const Icon(Icons.person_outline, size: 18),
                    label: Text(u.display),
                    tooltip: u.email ?? '@${u.username}',
                    onDeleted: () => _update(() => _toUsers.remove(u)),
                  ),
                for (final r in _toRoles)
                  InputChip(
                    avatar: const Icon(Icons.groups_outlined, size: 18),
                    label: Text('${r.name} · ${r.memberCount} ${_people(r.memberCount)}'),
                    tooltip: 'Everyone in ${r.name} when the email is sent',
                    onDeleted: () => _update(() => _toRoles.remove(r)),
                  ),
              ],
            ),
          ),
        Wrap(
          spacing: 8,
          children: [
            TextButton.icon(
              icon: const Icon(Icons.person_add_alt, size: 18),
              label: const Text('Add user'),
              onPressed: _addUser,
            ),
            TextButton.icon(
              icon: const Icon(Icons.group_add_outlined, size: 18),
              label: const Text('Add role'),
              onPressed: _addRole,
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text('Customer emails', style: theme.textTheme.bodySmall),
        const SizedBox(height: 4),
        _customerEmails(theme),
        if (_problems.to != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(_problems.to!,
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
          ),
      ],
    );
  }

  Widget _customerEmails(ThemeData theme) {
    if (_isBulk) {
      return CheckboxListTile(
        contentPadding: EdgeInsets.zero,
        dense: true,
        controlAffinity: ListTileControlAffinity.leading,
        value: _allCustomerEmails,
        title: Text(widget.type == EmailTargetType.customer
            ? 'Every email address of each customer'
            : 'Every email address of each invoice\'s customer'),
        subtitle: const Text('A row whose customer has no address still gets its email if '
            'someone else is in To; a row with no one to receive it is skipped.'),
        onChanged: (v) => _update(() => _allCustomerEmails = v ?? false),
      );
    }
    final id = _targetId;
    final muted = theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    if (id == null) {
      return Text('Choose the ${widget.type.noun} first to pick its customer\'s addresses.',
          style: muted);
    }
    return ref.watch(emailAddressesProvider(EmailAddressesRequest(widget.type, id))).when(
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text('Could not load the addresses: ${apiErrorMessage(e)}',
              style: TextStyle(color: theme.colorScheme.error)),
          data: (a) => a.addresses.isEmpty
              ? Text('${a.customerName} has no email address.', style: muted)
              : Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final address in a.addresses)
                      FilterChip(
                        avatar: const Icon(Icons.alternate_email, size: 16),
                        label: Text(address),
                        selected: _toAddresses.contains(address),
                        onSelected: (on) => _update(() {
                          if (on) {
                            _toAddresses.add(address);
                          } else {
                            _toAddresses.remove(address);
                          }
                        }),
                      ),
                  ],
                ),
        );
  }
}
