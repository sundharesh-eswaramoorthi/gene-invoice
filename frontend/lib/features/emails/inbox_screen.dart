import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import 'email_detail_dialog.dart';
import 'email_providers.dart';

/// The logged-in internal user's Email Inbox. The server scopes every query and mutation to the
/// session; this screen only renders what comes back and never carries a user id (FR14).
class InboxScreen extends ConsumerWidget {
  final TableQuery query;
  const InboxScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      body: DataTableScaffold<Map<String, dynamic>>(
        entity: 'inbox',
        path: '/api/emails/inbox',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/inbox').push(q),
        parse: (json) => json,
        idOf: (row) => (row['id'] as num).toInt(),
        // No mark-unread, search, filter or badge surface by design (NFR1); the Inbox schema
        // exposes no filterable columns either, so the filter bar stays empty.
        selectable: false,
        emptyMessage: 'No emails',
        onRowTap: (context, row) => _open(context, ref, row),
        columns: [
          TableColumnSpec(
            label: 'From',
            sortKey: 'senderDisplay',
            cell: (context, row) => _text(context, row, '${row['senderDisplay']}'),
          ),
          TableColumnSpec(
            label: 'Subject',
            sortKey: 'subject',
            maxWidth: 420,
            cell: (context, row) => _text(context, row, '${row['subject']}'),
          ),
          TableColumnSpec(
            label: 'Received',
            sortKey: 'sentAt',
            cell: (context, row) =>
                _text(context, row, formatDateTime(row['sentAt'])),
          ),
          TableColumnSpec(
            label: 'Status',
            sortKey: 'read',
            cell: (context, row) => row['read'] == true
                ? const Text('Read')
                : Text('Unread',
                    style: TextStyle(
                        fontWeight: FontWeight.w700,
                        color: Theme.of(context).colorScheme.primary)),
          ),
        ],
        rowActions: (context, row) => [
          if (row['read'] != true)
            IconButton(
              tooltip: 'Mark as read',
              icon: const Icon(Icons.mark_email_read_outlined, size: 18),
              onPressed: () => _markRead(context, ref, (row['emailId'] as num).toInt()),
            ),
        ],
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.done_all_outlined, size: 18),
            label: const Text('Mark all as read'),
            onPressed: () => _markAll(context, ref),
          ),
        ],
      ),
    );
  }

  /// Unread rows render bold so read and unread are distinct at a glance (FR15).
  Widget _text(BuildContext context, Map<String, dynamic> row, String value) {
    final unread = row['read'] != true;
    return Text(value,
        style: unread ? const TextStyle(fontWeight: FontWeight.w700) : null,
        overflow: TextOverflow.ellipsis);
  }

  void _open(BuildContext context, WidgetRef ref, Map<String, dynamic> row) {
    // The detail GET also marks the recipient's row read; views refresh on return.
    showEmailDetailDialog(context, ref, (row['emailId'] as num).toInt());
  }

  Future<void> _markRead(BuildContext context, WidgetRef ref, int emailId) async {
    try {
      await ref.read(dioProvider).post('/api/emails/$emailId/read');
      invalidateEmailViews(ref);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }

  /// One owner-scoped update across every unread Email, not just the visible page (EDGE3);
  /// the page-size metadata of the Inbox schema (10/25/50/100, default 25) drives the pager.
  Future<void> _markAll(BuildContext context, WidgetRef ref) async {
    try {
      final res = await ref.read(dioProvider).post('/api/emails/mark-all-read');
      final updated = (res.data as Map)['updated'] as num? ?? 0;
      invalidateEmailViews(ref);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Marked $updated email(s) as read')));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }
}
