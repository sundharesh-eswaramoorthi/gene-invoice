import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';

class AuditEntry {
  final int id;
  final String entityType;
  final int entityId;
  final String action;
  final String? beforeJson;
  final String? afterJson;
  final int? changedByUserId;
  final int? disputeId;
  final String? reason;
  final DateTime createdAt;

  const AuditEntry({
    required this.id,
    required this.entityType,
    required this.entityId,
    required this.action,
    required this.beforeJson,
    required this.afterJson,
    required this.changedByUserId,
    required this.disputeId,
    required this.reason,
    required this.createdAt,
  });

  factory AuditEntry.fromJson(Map<String, dynamic> json) => AuditEntry(
        id: (json['id'] as num).toInt(),
        entityType: json['entityType'] as String,
        entityId: (json['entityId'] as num).toInt(),
        action: json['action'] as String,
        beforeJson: json['beforeJson'] as String?,
        afterJson: json['afterJson'] as String?,
        changedByUserId: (json['changedByUserId'] as num?)?.toInt(),
        disputeId: (json['disputeId'] as num?)?.toInt(),
        reason: json['reason'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

final auditHistoryProvider = FutureProvider.autoDispose
    .family<List<AuditEntry>, ({String entityType, int entityId})>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/audit', queryParameters: {
    'entityType': key.entityType,
    'entityId': key.entityId,
  });
  return (res.data as List).cast<Map<String, dynamic>>().map(AuditEntry.fromJson).toList();
});

class AuditHistoryPanel extends ConsumerWidget {
  final String entityType;
  final int entityId;
  const AuditHistoryPanel({super.key, required this.entityType, required this.entityId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(auditHistoryProvider((entityType: entityType, entityId: entityId)));
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(12),
        child: LinearProgressIndicator(),
      ),
      error: (e, _) => Text('Failed: $e'),
      data: (list) {
        if (list.isEmpty) return const Text('No history yet.');
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: list.map((a) => _AuditTile(entry: a)).toList(),
        );
      },
    );
  }
}

class _AuditTile extends StatelessWidget {
  final AuditEntry entry;
  const _AuditTile({required this.entry});

  String _pretty(String? raw) {
    if (raw == null || raw.isEmpty) return '(none)';
    try {
      return const JsonEncoder.withIndent('  ').convert(jsonDecode(raw));
    } catch (_) {
      return raw;
    }
  }

  @override
  Widget build(BuildContext context) {
    final df = DateFormat.yMMMd().add_jm();
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: ExpansionTile(
        title: Text(entry.action),
        subtitle: Text(df.format(entry.createdAt.toLocal())
            + (entry.disputeId != null ? ' • dispute #${entry.disputeId}' : '')),
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (entry.reason != null && entry.reason!.isNotEmpty) ...[
                  Text('Reason: ${entry.reason}'),
                  const SizedBox(height: 8),
                ],
                Text('Before', style: Theme.of(context).textTheme.titleSmall),
                SelectableText(_pretty(entry.beforeJson),
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                const SizedBox(height: 8),
                Text('After', style: Theme.of(context).textTheme.titleSmall),
                SelectableText(_pretty(entry.afterJson),
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
