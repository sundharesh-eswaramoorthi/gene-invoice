import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/notification.dart';
import 'notifications_providers.dart';

class NotificationsScreen extends ConsumerWidget {
  final TableQuery query;
  const NotificationsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications'),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.done_all),
            label: const Text('Mark all read'),
            onPressed: () async {
              await ref.read(dioProvider).post('/api/notifications/mark-all-read');
              ref.invalidate(tablePageProvider);
              ref.invalidate(unreadCountProvider);
            },
          ),
        ],
      ),
      body: DataTableScaffold<AppNotification>(
        entity: 'notifications',
        path: '/api/notifications',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/notifications').push(q),
        parse: AppNotification.fromJson,
        idOf: (n) => n.id,
        emptyMessage: 'No notifications match this filter',
        onRowTap: (context, n) => _open(context, ref, n),
        bulkActions: const [
          BulkActionSpec(action: 'MARK_READ', label: 'Mark read', icon: Icons.done_all),
          BulkActionSpec(
              action: 'MARK_UNREAD', label: 'Mark unread', icon: Icons.mark_email_unread_outlined),
        ],
        columns: [
          TableColumnSpec(
            label: 'Title',
            sortKey: 'title',
            maxWidth: 240,
            cell: (context, n) => Text(
              n.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontWeight: n.read ? FontWeight.normal : FontWeight.w700),
            ),
          ),
          TableColumnSpec(
            label: 'Message',
            maxWidth: 360,
            cell: (context, n) => Tooltip(
              message: n.message ?? '',
              child: Text(n.message ?? '', maxLines: 2, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(label: 'Type', sortKey: 'type', cell: (context, n) => Text(n.type)),
          TableColumnSpec(
              label: 'Received',
              sortKey: 'createdAt',
              cell: (context, n) => Text(formatDateTime(n.createdAt))),
          TableColumnSpec(
              label: 'Read',
              sortKey: 'read',
              cell: (context, n) => Icon(
                    n.read ? Icons.drafts_outlined : Icons.markunread,
                    size: 18,
                    color: n.read ? null : Theme.of(context).colorScheme.primary,
                  )),
        ],
      ),
    );
  }

  Future<void> _open(BuildContext context, WidgetRef ref, AppNotification n) async {
    if (!n.read) {
      await ref.read(dioProvider).post('/api/notifications/${n.id}/read');
      ref.invalidate(tablePageProvider);
      ref.invalidate(unreadCountProvider);
    }
    if (n.link != null && context.mounted) {
      // Backend links use the same paths as the app; admin dispute links are the exception.
      final target =
          n.link!.startsWith('/admin/disputes/') ? n.link!.replaceFirst('/admin', '') : n.link!;
      context.go(target);
    }
  }
}
