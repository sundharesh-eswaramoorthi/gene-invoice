import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../auth/auth_controller.dart';
import 'email_models.dart';
import 'email_providers.dart';
import 'email_tab.dart';

/// The signed-in user's inbox: every email they are a To recipient of, whatever became of its
/// delivery (E11).
class InboxScreen extends ConsumerWidget {
  final TableQuery query;
  const InboxScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Who sent an email is staff identity, which a customer login cannot sort or filter by; the
    // server's inbox schema leaves that column out for them and refuses the sort (AC-A8).
    final isCustomer = ref.watch(currentUserProvider)?.isCustomer ?? false;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Inbox'),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.done_all),
            label: const Text('Mark all as read'),
            onPressed: () => _markAllRead(context),
          ),
        ],
      ),
      body: DataTableScaffold<InboxItem>(
        entity: 'inbox',
        path: '/api/inbox',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/inbox').push(q),
        header: const _DeliveryBanner(),
        parse: InboxItem.fromJson,
        idOf: (i) => i.id,
        emptyMessage: 'No emails match this filter',
        // The screen's own context rather than the cell's: the reader navigates after the table
        // has refreshed behind it, and a cell's context may be gone by then.
        onRowTap: (_, item) => _open(context, item),
        // Marking rows read changes the sidebar's unread badge, which lives outside this table.
        onBulkDone: () => ref.invalidate(inboxUnreadCountProvider),
        bulkActions: const [
          BulkActionSpec(action: 'MARK_READ', label: 'Mark read', icon: Icons.done_all),
          BulkActionSpec(
              action: 'MARK_UNREAD', label: 'Mark unread', icon: Icons.mark_email_unread_outlined),
        ],
        rowActions: (_, item) => [
          IconButton(
            tooltip: item.read ? 'Mark unread' : 'Mark read',
            icon: Icon(item.read ? Icons.mark_email_unread_outlined : Icons.drafts_outlined,
                size: 18),
            onPressed: () => _setRead(context, item, read: !item.read),
          ),
        ],
        mobileCard: (context, item) => _InboxCard(item: item),
        columns: [
          TableColumnSpec(
            label: 'From',
            sortKey: isCustomer ? null : 'fromName',
            maxWidth: 180,
            cell: (context, item) => Text(
              item.from.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _weight(item),
            ),
          ),
          TableColumnSpec(
            label: 'Subject',
            sortKey: 'subject',
            maxWidth: 320,
            cell: (context, item) => Tooltip(
              message: item.snippet,
              child: Text(
                item.subject.isEmpty ? '(no subject)' : item.subject,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: _weight(item),
              ),
            ),
          ),
          TableColumnSpec(
            label: 'About',
            sortKey: 'entityLabel',
            maxWidth: 200,
            cell: (context, item) =>
                Text(item.entityLabel, maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          TableColumnSpec(
            label: 'Received',
            sortKey: 'occurredAt',
            cell: (context, item) => Text(formatDateTime(item.occurredAt)),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'status',
            cell: (context, item) => EmailStatusChip(status: item.status),
          ),
        ],
      ),
    );
  }

  static TextStyle _weight(InboxItem item) =>
      TextStyle(fontWeight: item.read ? FontWeight.normal : FontWeight.w700);

  // Both posts take the container and the messenger before they go out. The screen may be gone
  // when the answer comes (the reader's "Open" leaves it at once), and a gone screen's ref throws,
  // so the sidebar's badge would never catch up.

  Future<void> _markAllRead(BuildContext context) async {
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await container.read(dioProvider).post('/api/inbox/mark-all-read');
      container.invalidate(tablePageProvider);
      container.invalidate(inboxUnreadCountProvider);
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Future<void> _setRead(BuildContext context, InboxItem item, {required bool read}) async {
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await container.read(dioProvider).post('/api/inbox/${item.id}/${read ? 'read' : 'unread'}');
      container.invalidate(tablePageProvider);
      container.invalidate(inboxUnreadCountProvider);
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Future<void> _open(BuildContext context, InboxItem item) async {
    // Opening an email reads it; the row and the badge catch up behind the reader.
    if (!item.read) unawaited(_setRead(context, item, read: true));
    await showDialog<void>(
      context: context,
      builder: (_) => _ReaderDialog(
        item: item,
        // The table's context, which outlives the reader that closes before navigating.
        onOpenRecord: (link) => goGuarded(context, link),
      ),
    );
  }
}

class _ReaderDialog extends ConsumerStatefulWidget {
  final InboxItem item;
  final ValueChanged<String> onOpenRecord;
  const _ReaderDialog({required this.item, required this.onOpenRecord});

  @override
  ConsumerState<_ReaderDialog> createState() => _ReaderDialogState();
}

class _ReaderDialogState extends ConsumerState<_ReaderDialog> {
  /// Loads the email again while its delivery can still change, as the Email tab does (§6).
  late final _refresher =
      EmailRefresher(() => ref.invalidate(emailDetailProvider(widget.item.emailId)));

  @override
  void dispose() {
    _refresher.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final async = ref.watch(emailDetailProvider(item.emailId));
    final email = async.valueOrNull;
    _refresher.update(email == null ? null : emailRefreshInterval([email]),
        busy: async.isLoading);
    final link = item.entityLink;
    // Only once the email says this reader may open its record: a recipient can read an email
    // about a record they cannot see, and the link would lead to a refusal.
    final canOpenRecord = email?.canOpenRecord ?? false;
    return AlertDialog(
      contentPadding: const EdgeInsets.fromLTRB(12, 16, 12, 0),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 680,
        child: async.when(
          // A refresh that fails keeps the email on show.
          skipError: true,
          loading: () => const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Padding(
            padding: const EdgeInsets.all(12),
            child: Text('Could not load this email: ${apiErrorMessage(e)}'),
          ),
          data: (email) => SingleChildScrollView(
              child: EmailCard(email: email, showRecord: true, inbox: true)),
        ),
      ),
      actions: [
        if (canOpenRecord && link != null && link.isNotEmpty)
          TextButton.icon(
            icon: const Icon(Icons.open_in_new, size: 18),
            label: Text(item.entityLabel.isEmpty ? 'Open record' : 'Open ${item.entityLabel}'),
            onPressed: () {
              Navigator.of(context).pop();
              widget.onOpenRecord(link);
            },
          ),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
      ],
    );
  }
}

class _InboxCard extends StatelessWidget {
  final InboxItem item;
  const _InboxCard({required this.item});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final weight = InboxScreen._weight(item);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(item.from.name,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: weight),
            ),
            const SizedBox(width: 8),
            Text(formatDateTime(item.occurredAt), style: theme.textTheme.bodySmall),
          ],
        ),
        Text(item.subject.isEmpty ? '(no subject)' : item.subject,
            maxLines: 1, overflow: TextOverflow.ellipsis, style: weight),
        if (item.snippet.isNotEmpty)
          Text(item.snippet,
              maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
        const SizedBox(height: 4),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            EmailStatusChip(status: item.status),
            Text(item.entityLabel, style: theme.textTheme.bodySmall),
          ],
        ),
      ],
    );
  }
}

/// Says why mail may not be leaving or arriving — no mail service, or the reader's own Gmail not
/// connected or failing — so an empty or undelivered inbox is not a mystery. Only staff who send
/// email connect a Gmail; customer logins and readers without EMAIL_SEND hear only about the mail
/// service, since the page a Connect would lead to turns them away.
class _DeliveryBanner extends ConsumerWidget {
  const _DeliveryBanner();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final delivery = ref.watch(emailDeliveryProvider).valueOrNull;
    final gmail = ref.watch(canConnectGmailProvider) ? delivery?.gmail : null;
    final String? message;
    String? action;
    if (delivery == null) {
      message = null;
    } else if (!delivery.configured) {
      message = 'Email delivery is not configured';
    } else if (gmail == null) {
      message = null;
    } else if (gmail.needsReconnect) {
      message = 'Your Gmail connection needs to be renewed';
      action = 'Reconnect';
    } else if (!gmail.isConnected) {
      message = 'Connect your Gmail to send email and receive replies';
      action = 'Connect';
    } else if (gmail.lastSyncError != null && gmail.lastSyncError!.isNotEmpty) {
      message = 'The last Gmail check failed: ${gmail.lastSyncError}';
    } else {
      message = null;
    }
    if (message == null) return const SizedBox.shrink();

    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 12, 12, 0),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: scheme.tertiaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Icon(Icons.info_outline, size: 18, color: scheme.onTertiaryContainer),
          const SizedBox(width: 8),
          Expanded(child: Text(message, style: TextStyle(color: scheme.onTertiaryContainer))),
          if (action != null) ...[
            const SizedBox(width: 8),
            TextButton(
              onPressed: () => goGuarded(context, '/me/gmail'),
              child: Text(action),
            ),
          ],
        ],
      ),
    );
  }
}
