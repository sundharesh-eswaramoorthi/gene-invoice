import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/email.dart';
import 'email_view.dart';

/// The emails the signed-in user received — named directly or through a role — newest first.
/// Unread ones are bold and marked; each person's read status is their own.
class InboxScreen extends ConsumerWidget {
  final TableQuery query;
  const InboxScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Inbox'),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.done_all),
            label: const Text('Mark all as read'),
            onPressed: () => _markAllRead(context, ref),
          ),
        ],
      ),
      body: DataTableScaffold<InboxItem>(
        entity: 'inbox',
        path: '/api/inbox',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/inbox').push(q),
        parse: InboxItem.fromJson,
        idOf: (item) => item.id,
        // Search, filters and bulk marking are not part of the Inbox yet.
        showFilterBar: false,
        selectable: false,
        emptyMessage: 'No emails',
        onRowTap: (context, item) => context.go('/inbox/${item.id}'),
        mobileCard: (context, item) => _InboxCard(item: item),
        columns: [
          TableColumnSpec(
            label: '',
            cell: (context, item) => _ReadMark(read: item.read),
          ),
          TableColumnSpec(
            label: 'From',
            maxWidth: 200,
            cell: (context, item) => Text(
              item.email.from.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _weight(item),
            ),
          ),
          TableColumnSpec(
            label: 'Subject',
            maxWidth: 380,
            cell: (context, item) => _SubjectAndPreview(item: item),
          ),
          TableColumnSpec(
            label: 'About',
            maxWidth: 220,
            cell: (context, item) => Text(
              _about(item.email),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          TableColumnSpec(
            label: 'Received',
            cell: (context, item) => Text(formatDateTime(item.email.sentAt), style: _weight(item)),
          ),
        ],
        rowActions: (context, item) => [
          if (!item.read)
            IconButton(
              tooltip: 'Mark as read',
              icon: const Icon(Icons.mark_email_read_outlined, size: 18),
              onPressed: () => _markRead(context, ref, item),
            ),
        ],
      ),
    );
  }

  Future<void> _markRead(BuildContext context, WidgetRef ref, InboxItem item) async {
    try {
      await ref.read(dioProvider).post('/api/inbox/${item.id}/read');
      ref.invalidate(tablePageProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }

  /// Every unread email of this user's, not just the page on screen.
  Future<void> _markAllRead(BuildContext context, WidgetRef ref) async {
    try {
      final res = await ref.read(dioProvider).post('/api/inbox/read-all');
      ref.invalidate(tablePageProvider);
      final updated = ((res.data as Map)['updated'] as num?)?.toInt() ?? 0;
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(updated == 0
              ? 'Nothing was unread'
              : '$updated ${updated == 1 ? 'email' : 'emails'} marked as read'),
        ));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }
}

TextStyle? _weight(InboxItem item) => item.read ? null : const TextStyle(fontWeight: FontWeight.w700);

String _about(EmailMessage e) =>
    e.invoiceNumber == null ? e.customerName : '${e.invoiceNumber} • ${e.customerName}';

/// A filled dot for unread, an open envelope for read, with words for a screen reader.
class _ReadMark extends StatelessWidget {
  final bool read;
  const _ReadMark({required this.read});

  @override
  Widget build(BuildContext context) => Semantics(
        label: read ? 'Read' : 'Unread',
        child: read
            ? Icon(Icons.drafts_outlined, size: 18, color: Theme.of(context).colorScheme.outline)
            : Icon(Icons.circle, size: 10, color: Theme.of(context).colorScheme.primary),
      );
}

class _SubjectAndPreview extends StatelessWidget {
  final InboxItem item;
  const _SubjectAndPreview({required this.item});

  @override
  Widget build(BuildContext context) {
    final preview = item.email.body.replaceAll(RegExp(r'\s+'), ' ').trim();
    return Text.rich(
      TextSpan(children: [
        TextSpan(text: item.email.subject, style: _weight(item)),
        if (preview.isNotEmpty)
          TextSpan(
            text: ' — $preview',
            style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
      ]),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );
  }
}

class _InboxCard extends StatelessWidget {
  final InboxItem item;
  const _InboxCard({required this.item});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _ReadMark(read: item.read),
            const SizedBox(width: 8),
            Expanded(
              child: Text(item.email.from.name,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: _weight(item)),
            ),
            Text(formatDateTime(item.email.sentAt), style: theme.textTheme.bodySmall),
          ],
        ),
        const SizedBox(height: 4),
        _SubjectAndPreview(item: item),
        const SizedBox(height: 4),
        EmailAboutChip(email: item.email),
      ],
    );
  }
}
