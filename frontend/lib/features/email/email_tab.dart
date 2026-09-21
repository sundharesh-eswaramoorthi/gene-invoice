import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/widgets/status_chip.dart';
import '../documents/document_models.dart' show DocumentEntityType;
import 'email_actions.dart' show canSendEmailProvider;
import 'email_entity.dart';
import 'email_models.dart';
import 'email_providers.dart';
import 'send_email_dialog.dart';

/// The shared status palette (shared/widgets/status_chip.dart): queued is still waiting, sending
/// is under way, sent and received are the good endings, partly sent is the palette's warning
/// ("partly done"), failed went wrong, and "not sent" — the normal state without a mail service
/// — is inert.
Color emailStatusColor(BuildContext context, String status) {
  final scheme = Theme.of(context).colorScheme;
  return switch (status) {
    'QUEUED' => scheme.primary,
    'SENDING' || 'PARTIAL' => Colors.orange.shade800,
    'SENT' || 'RECEIVED' => Colors.green.shade700,
    'FAILED' => scheme.error,
    _ => scheme.outline,
  };
}

class EmailStatusChip extends StatelessWidget {
  final String status;
  const EmailStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) =>
      StatusChip(label: emailStatusLabel(status), color: emailStatusColor(context, status));
}

/// Asks a view of emails to load them again while their delivery can still change
/// ([emailRefreshInterval]). Call [update] on every build with what is on screen: it keeps one
/// timer, restarts it when the pace changes, and stops when nothing is left to follow. The owner
/// calls [dispose] with its own, so no request outlives the view.
class EmailRefresher {
  final VoidCallback _refresh;
  Timer? _timer;
  Duration? _every;

  EmailRefresher(this._refresh);

  /// [busy]: a load is under way, and its answer's build sets the next timer.
  void update(Duration? every, {bool busy = false}) {
    if (every == null) {
      _stop();
      return;
    }
    if (busy) return;
    if (every == _every && (_timer?.isActive ?? false)) return;
    _timer?.cancel();
    _every = every;
    _timer = Timer(every, _refresh);
  }

  void _stop() {
    _timer?.cancel();
    _timer = null;
    _every = null;
  }

  void dispose() => _stop();
}

/// The Email tab on a details page: every email about the record, newest first, each in full.
class EmailTab extends ConsumerStatefulWidget {
  final EmailEntityType type;
  final int entityId;
  final String? entityLabel;

  const EmailTab({super.key, required this.type, required this.entityId, this.entityLabel});

  @override
  ConsumerState<EmailTab> createState() => _EmailTabState();
}

class _EmailTabState extends ConsumerState<EmailTab> {
  static const _pageSize = 20;

  /// Pages shown so far. Each is its own request, so "Show older" never refetches what is on
  /// screen, and a send that invalidates the family refreshes them all at once.
  int _pages = 1;

  /// Loads the pages on show again while an email on them is still on its way (§6).
  late final _refresher = EmailRefresher(() {
    for (var i = 0; i < _pages; i++) {
      ref.invalidate(entityEmailsProvider(_key(i)));
    }
  });

  EntityEmailsKey _key(int page) =>
      (type: widget.type, entityId: widget.entityId, page: page, size: _pageSize);

  void _reload() {
    ref.invalidate(entityEmailsProvider);
  }

  @override
  void dispose() {
    _refresher.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final canSend = ref.watch(canSendEmailProvider);
    final first = ref.watch(entityEmailsProvider(_key(0)));
    final theme = Theme.of(context);

    Widget header(int? total) => Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(total == null ? 'Emails' : 'Emails ($total)',
                    style: theme.textTheme.titleMedium),
              ),
              IconButton(
                tooltip: 'Refresh',
                icon: const Icon(Icons.refresh, size: 20),
                onPressed: _reload,
              ),
              if (canSend)
                OutlinedButton.icon(
                  icon: const Icon(Icons.mail_outline, size: 18),
                  label: const Text('Send email'),
                  onPressed: () => openSendEmailDialog(context,
                      type: widget.type, entityId: widget.entityId, entityLabel: widget.entityLabel),
                ),
            ],
          ),
        );

    return first.when(
      // A refresh that fails keeps the emails already on show; the next one may do better.
      skipError: true,
      loading: () => ListView(
        padding: const EdgeInsets.all(12),
        children: [header(null), const LinearProgressIndicator()],
      ),
      error: (e, _) {
        _refresher.update(null);
        return ListView(
          padding: const EdgeInsets.all(12),
          children: [
            header(null),
            _Unavailable(message: 'Emails unavailable: ${apiErrorMessage(e)}', onRetry: _reload),
          ],
        );
      },
      data: (page) {
        if (page.isEmpty) {
          _refresher.update(null);
          return ListView(
            padding: const EdgeInsets.all(12),
            children: [
              header(0),
              const SizedBox(height: 24),
              Icon(Icons.mail_outline, size: 44, color: theme.colorScheme.outline),
              const SizedBox(height: 12),
              Text('No emails about this ${widget.type.noun} yet.',
                  textAlign: TextAlign.center, style: theme.textTheme.titleMedium),
            ],
          );
        }

        final emails = <EmailMessage>[];
        final seen = <int>{};
        Widget? tail;
        for (var i = 0; i < _pages; i++) {
          final AsyncValue<PagedResult<EmailMessage>> async =
              i == 0 ? first : ref.watch(entityEmailsProvider(_key(i)));
          final loaded = async.valueOrNull;
          if (loaded == null) {
            tail = async.hasError
                ? _Unavailable(
                    message: 'Older emails unavailable: ${apiErrorMessage(async.error!)}',
                    onRetry: () => ref.invalidate(entityEmailsProvider(_key(i))),
                  )
                : const Padding(
                    padding: EdgeInsets.all(12),
                    child: Center(child: CircularProgressIndicator()),
                  );
            break;
          }
          // Mail that arrived between two pages pushes a row onto the next page as well.
          for (final e in loaded.content) {
            if (seen.add(e.id)) emails.add(e);
          }
        }
        final older = page.totalElements - _pages * _pageSize;
        _refresher.update(emailRefreshInterval(emails), busy: first.isLoading);

        return ListView(
          padding: const EdgeInsets.all(12),
          children: [
            header(page.totalElements),
            for (final e in emails) EmailCard(key: ValueKey('email-${e.id}'), email: e),
            if (tail != null) tail,
            if (tail == null && older > 0)
              Center(
                child: TextButton.icon(
                  icon: const Icon(Icons.expand_more),
                  label: Text('Show older emails ($older more)'),
                  onPressed: () => setState(() => _pages++),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _Unavailable extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _Unavailable({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Column(
          children: [
            Text(message, textAlign: TextAlign.center),
            TextButton.icon(
              icon: const Icon(Icons.refresh),
              label: const Text('Try again'),
              onPressed: onRetry,
            ),
          ],
        ),
      );
}

/// One email in full: who it was from and to and how each was added, what happened to it, and
/// the whole message. Used by the Email tab and the Inbox reader.
class EmailCard extends ConsumerStatefulWidget {
  final EmailMessage email;

  /// Names the record it is about — needed where emails about many records mix, as in the Inbox.
  final bool showRecord;

  /// Seen from the viewer's Inbox, where every email is one they received. The Email tab speaks
  /// for the system ("Outgoing · sent …"), which read to a recipient as if they had sent it.
  final bool inbox;

  const EmailCard({super.key, required this.email, this.showRecord = false, this.inbox = false});

  @override
  ConsumerState<EmailCard> createState() => _EmailCardState();
}

class _EmailCardState extends ConsumerState<EmailCard> {
  bool _retrying = false;

  Future<void> _retry() async {
    // Taken before the request, which sends at once and can take seconds: the card may be gone by
    // the time it answers (the reader closed, another tab chosen), and a gone card's ref throws,
    // which would read as the retry failing and leave the lists on the old status.
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    final id = widget.email.id;
    setState(() => _retrying = true);
    try {
      final res = await container.read(dioProvider).post('/api/emails/$id/retry');
      final updated = EmailMessage.fromJson((res.data as Map).cast<String, dynamic>());
      container.invalidate(entityEmailsProvider);
      container.invalidate(emailDetailProvider(id));
      container.invalidate(inboxUnreadCountProvider);
      container.invalidate(tablePageProvider);
      messenger.showSnackBar(SnackBar(content: Text(emailOutcomeMessage(updated))));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _retrying = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final email = widget.email;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final muted = theme.textTheme.bodySmall;

    final incoming = widget.inbox || email.isInbound;
    final String direction;
    final String when;
    if (widget.inbox) {
      direction = 'Received';
      when = 'Received ${formatDateTime(email.occurredAt)}';
    } else {
      direction = email.isInbound ? 'Incoming' : 'Outgoing';
      when = '$direction · ${emailProgress(email)}';
    }

    // "as Collection POC" — unless the sender is masked, and already reads as that role.
    final roleLabel = email.fromRoleLabel;
    final fromNotes =
        roleLabel == null || roleLabel == email.from.name ? const <String>[] : ['· as $roleLabel'];

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Tooltip(
                  message: direction,
                  child: Padding(
                    padding: const EdgeInsets.only(top: 2, right: 8),
                    child: Icon(incoming ? Icons.call_received : Icons.call_made,
                        size: 18, color: scheme.primary),
                  ),
                ),
                Expanded(
                  child: SelectableText(
                    email.subject.isEmpty ? '(no subject)' : email.subject,
                    style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(width: 8),
                EmailStatusChip(status: email.status),
              ],
            ),
            const SizedBox(height: 2),
            Text(when, style: muted),
            const SizedBox(height: 8),
            // Names and addresses are separate runs now (_Person); one selection area over them
            // keeps them as easy to copy as the single From text was.
            SelectionArea(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (widget.showRecord && email.entityLabel.isNotEmpty)
                    _Field(label: 'About', child: Text(email.entityLabel)),
                  _Field(label: 'From', child: _Person(person: email.from, notes: fromNotes)),
                  // Each To recipient gets a copy of their own, and says what became of it.
                  _Field(label: 'To', child: _Recipients(people: email.to, showDelivery: true)),
                  if (email.cc.isNotEmpty)
                    _Field(label: 'Cc', child: _Recipients(people: email.cc)),
                  if (email.unresolved.isNotEmpty)
                    _Field(
                      label: 'Not assigned',
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          for (final u in email.unresolved)
                            Tooltip(
                              message: u.reason ?? '',
                              child: Text('${u.label} — nobody assigned'),
                            ),
                        ],
                      ),
                    ),
                  if (email.sentByName != null)
                    _Field(label: 'Sent by', child: Text(email.sentByName!)),
                ],
              ),
            ),
            // What went with it, under the people it went to: an email whose whole point was the
            // file it carried says nothing at all without this (E17).
            if (email.attachments.isNotEmpty)
              _Field(label: 'Attached', child: _Attachments(email: email)),
            if (email.deliveredFrom != null)
              _Field(label: 'Delivered from', child: SelectableText(email.deliveredFrom!)),
            if (email.error != null && email.error!.isNotEmpty)
              _Field(
                label: 'Error',
                child: SelectableText(
                  email.attempts > 1
                      ? '${email.error!} (after ${email.attempts} attempts)'
                      : email.error!,
                  style: TextStyle(color: scheme.error),
                ),
              ),
            const Divider(height: 20),
            if (email.body.isEmpty)
              Text('(no message)', style: muted?.copyWith(fontStyle: FontStyle.italic))
            else
              SelectableText(email.body),
            if (email.canRetryNow)
              Align(
                alignment: Alignment.centerRight,
                child: Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: OutlinedButton.icon(
                    icon: _retrying
                        ? const SizedBox(
                            width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.refresh, size: 18),
                    label: const Text('Retry'),
                    onPressed: _retrying ? null : _retry,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// A label beside its value; the label column is narrow enough to leave a phone the width.
class _Field extends StatelessWidget {
  final String label;
  final Widget child;
  const _Field({required this.label, required this.child});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 104,
              child: Text(label, style: Theme.of(context).textTheme.labelMedium),
            ),
            Expanded(child: child),
          ],
        ),
      );
}

/// The documents that went out with an email, each with the filename, kind and size the sender
/// saw when they attached it.
///
/// Those three are the email's own snapshot, not the document's: the file may have been deleted
/// since, and a record of what was sent that changed afterwards would not be a record. The
/// filename links to the record's Documents tab where there is one to land on, which is what
/// `AttachmentDto.documentId` is for — the download lives there and applies the document's own
/// visibility itself (D7), so nothing here has to decide who may have the bytes.
class _Attachments extends StatelessWidget {
  final EmailMessage email;
  const _Attachments({required this.email});

  /// The Documents tab of the record this email is about, or null when there is none to send a
  /// reader to: the viewer may not open the record at all, or it is a kind — a promise, a
  /// dispute — that keeps no documents of its own.
  String? get _documentsTab {
    final link = email.entityLink;
    if (link == null || !email.canOpenRecord) return null;
    if (DocumentEntityType.fromWire(email.entityType?.wire) == null) return null;
    return '$link?tab=documents';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall;
    final location = _documentsTab;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final a in email.attachments)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Wrap(
              spacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Icon(Icons.attach_file, size: 16, color: theme.colorScheme.onSurfaceVariant),
                // Linked only where the document is still one the reader could be taken to. An
                // attachment sent from a file since deleted keeps its name here either way.
                if (location != null && a.documentId != null)
                  Tooltip(
                    message: 'Open the Documents tab',
                    child: InkWell(
                      onTap: () => goGuarded(context, location),
                      child: Text(
                        a.filename.isEmpty ? '(unnamed file)' : a.filename,
                        style: TextStyle(
                            color: theme.colorScheme.primary, fontWeight: FontWeight.w600),
                      ),
                    ),
                  )
                else
                  Text(a.filename.isEmpty ? '(unnamed file)' : a.filename),
                // "PDF · 1.5 MB", in the Documents tab's own words for a kind and a size.
                Text('· ${a.details}', style: muted),
              ],
            ),
          ),
      ],
    );
  }
}

class _Recipients extends StatelessWidget {
  final List<EmailParticipant> people;

  /// Follow each person with what became of their copy.
  final bool showDelivery;
  const _Recipients({required this.people, this.showDelivery = false});

  @override
  Widget build(BuildContext context) {
    if (people.isEmpty) return const Text('—');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final p in people)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: _Person(
              person: p,
              notes: [
                if (p.howAdded.isNotEmpty) '— ${p.howAdded}',
                // Kept on the email as an in-app copy; no message could leave for them.
                if (p.address == null && !p.masked) '— no email address',
              ],
              delivery: showDelivery ? p.delivery : null,
            ),
          ),
      ],
    );
  }
}

/// One sender or recipient, as [EmailParticipant.display] words it, followed by [notes] and, for
/// a recipient, what became of their copy ([delivery]). The name, the address and each note are
/// runs of their own, so a narrow screen breaks the line between them — the name above
/// "<bob@company.com>" — rather than inside the address, as one paragraph did ("<e2 /
/// eui.cust…"). An address too long for a line of its own breaks after its "@".
class _Person extends StatelessWidget {
  final EmailParticipant person;
  final List<String> notes;
  final RecipientDelivery? delivery;
  const _Person({required this.person, this.notes = const [], this.delivery});

  /// "· Delivered 17 Sep 2026, 6:07 AM (estimated)" in its colour, then "· read in app …".
  /// Delivered and read are the good endings; bounced, failed and not sent went wrong.
  List<Widget> _deliveryRuns(BuildContext context, RecipientDelivery delivery) {
    final muted = Theme.of(context).textTheme.bodySmall;
    final status = recipientDeliveryText(delivery);
    final color = switch (delivery.status) {
      'DELIVERED' || 'READ' => Colors.green.shade700,
      'BOUNCED' || 'FAILED' || 'NOT_SENT' => Theme.of(context).colorScheme.error,
      _ => null,
    };
    final inApp = delivery.readInAppAt;
    final estimated = delivery.status == 'DELIVERED' && !delivery.deliveredConfirmed;
    final statusRun =
        status == null ? null : Text('· $status', style: muted?.copyWith(color: color));
    return [
      if (statusRun != null)
        estimated
            // An estimate is all there is for a mailbox the app cannot read.
            ? Tooltip(
                message: 'No bounce came back; Gmail does not confirm delivery',
                child: statusRun,
              )
            : statusRun,
      if (inApp != null) Text('· read in app ${formatDateTime(inApp)}', style: muted),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final muted = Theme.of(context).textTheme.bodySmall;
    final name = person.name;
    final address = person.address ?? '';
    final named = name.isNotEmpty && name != address;
    final Widget? addressRuns;
    if (address.isEmpty) {
      addressRuns = null;
    } else {
      final text = named ? '<$address>' : address;
      final at = text.lastIndexOf('@');
      addressRuns = at <= 0
          ? Text(text)
          : Wrap(children: [Text(text.substring(0, at + 1)), Text(text.substring(at + 1))]);
    }
    // Read out as one person, not as three fragments.
    return MergeSemantics(
      child: Wrap(
        spacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          if (named || addressRuns == null) Text(name.isEmpty ? '—' : name),
          if (addressRuns != null) addressRuns,
          for (final note in notes) Text(note, style: muted),
          if (delivery != null) ..._deliveryRuns(context, delivery!),
        ],
      ),
    );
  }
}
