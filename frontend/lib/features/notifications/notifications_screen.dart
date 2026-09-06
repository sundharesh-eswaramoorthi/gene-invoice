import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/notification.dart';
import 'notifications_providers.dart';

class NotificationsScreen extends ConsumerWidget {
  const NotificationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(notificationsProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications'),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.done_all),
            label: const Text('Mark all read'),
            onPressed: () async {
              final dio = ref.read(dioProvider);
              await dio.post('/api/notifications/mark-all-read');
              ref.invalidate(notificationsProvider);
              ref.invalidate(unreadCountProvider);
            },
          ),
        ],
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) {
          if (list.isEmpty) return const Center(child: Text('No notifications'));
          return RefreshIndicator(
            onRefresh: () async => ref.refresh(notificationsProvider.future),
            child: ListView.separated(
              itemCount: list.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, i) => _NotificationTile(notification: list[i]),
            ),
          );
        },
      ),
    );
  }
}

class _NotificationTile extends ConsumerWidget {
  final AppNotification notification;
  const _NotificationTile({required this.notification});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final df = DateFormat.yMMMd().add_jm();
    return ListTile(
      leading: Icon(
        notification.read ? Icons.notifications_none : Icons.notifications_active,
        color: notification.read ? null : Theme.of(context).colorScheme.primary,
      ),
      title: Text(
        notification.title,
        style: TextStyle(fontWeight: notification.read ? FontWeight.normal : FontWeight.w600),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (notification.message != null) Text(notification.message!),
          // Strategy notifications carry an optional structured invoice list; rows
          // without items (e.g. disputes) render exactly as before.
          if (notification.invoiceItems != null &&
              notification.invoiceItems!.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final item in notification.invoiceItems!)
                    Text('Invoice ${item.invoiceNumber}',
                        style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
          Text(df.format(notification.createdAt.toLocal()),
              style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
      onTap: () async {
        if (!notification.read) {
          await ref.read(dioProvider).post('/api/notifications/${notification.id}/read');
          ref.invalidate(notificationsProvider);
          ref.invalidate(unreadCountProvider);
        }
        if (notification.link != null && context.mounted) {
          _navigateToLink(context, notification.link!);
        }
      },
    );
  }

  void _navigateToLink(BuildContext context, String link) {
    // Backend stores routes like `/disputes/42` or `/admin/disputes/42`.
    // Map admin links to the admin route prefix.
    final target = link.startsWith('/admin/disputes/')
        ? link.replaceFirst('/admin', '')
        : link;
    context.go(target);
  }
}
