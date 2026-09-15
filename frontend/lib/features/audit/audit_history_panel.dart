import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';

class AuditEntry {
  /// Null for an event derived from a record older than its audit trail.
  final int? id;
  final String entityType;
  final int entityId;

  /// Names the record the row is about — an invoice number, "Payment #12".
  final String? entityLabel;
  final String action;
  final String? beforeJson;
  final String? afterJson;
  final int? changedByUserId;
  final String? changedByUsername;
  final int? disputeId;
  final String? reason;
  final DateTime? createdAt;

  /// Reconstructed from the record itself because it predates the history log.
  final bool derived;

  /// Who made the change is withheld from this viewer — a customer login never sees staff.
  final bool actorHidden;

  /// The one figure that says what happened, worked out once when the row arrives rather than
  /// on every rebuild.
  final String? headline;

  /// For a payment moving on an invoice, the payment it was.
  final int? paymentId;

  const AuditEntry({
    required this.id,
    required this.entityType,
    required this.entityId,
    this.entityLabel,
    required this.action,
    required this.beforeJson,
    required this.afterJson,
    required this.changedByUserId,
    this.changedByUsername,
    required this.disputeId,
    required this.reason,
    required this.createdAt,
    this.derived = false,
    this.actorHidden = false,
    this.headline,
    this.paymentId,
  });

  /// Identifies the row across rebuilds, filtering and refreshes. Derived rows have no id.
  String get stableKey => '$entityType:$entityId:$action:'
      '${id ?? 'derived:${createdAt?.microsecondsSinceEpoch}'}';

  factory AuditEntry.fromJson(Map<String, dynamic> json) {
    final action = json['action'] as String;
    final beforeJson = json['beforeJson'] as String?;
    final afterJson = json['afterJson'] as String?;
    final before = _decode(beforeJson);
    final after = _decode(afterJson);
    return AuditEntry(
      id: (json['id'] as num?)?.toInt(),
      entityType: json['entityType'] as String,
      entityId: (json['entityId'] as num).toInt(),
      entityLabel: json['entityLabel'] as String?,
      action: action,
      beforeJson: beforeJson,
      afterJson: afterJson,
      changedByUserId: (json['changedByUserId'] as num?)?.toInt(),
      changedByUsername: json['changedByUsername'] as String?,
      disputeId: (json['disputeId'] as num?)?.toInt(),
      reason: json['reason'] as String?,
      createdAt: json['createdAt'] == null ? null : DateTime.parse(json['createdAt'] as String),
      derived: json['derived'] as bool? ?? false,
      actorHidden: json['actorHidden'] as bool? ?? false,
      headline: _headline(action, before, after),
      paymentId: _paymentIdOf(action, before, after),
    );
  }
}

/// A snapshot is usually an object, but some events store a bare value (a promise's status).
Object? _decode(String? raw) {
  if (raw == null || raw.isEmpty) return null;
  try {
    return jsonDecode(raw);
  } catch (_) {
    return null;
  }
}

String? _statusOf(Object? snapshot) => switch (snapshot) {
      String s => s,
      Map<String, dynamic> m => m['status'] as String?,
      _ => null,
    };

String? _headline(String action, Object? before, Object? after) {
  final b = before is Map<String, dynamic> ? before : null;
  final a = after is Map<String, dynamic> ? after : null;
  switch (action) {
    case 'INVOICE_CREATED':
      return a?['total'] == null ? null : formatMoney(a!['total']);
    case 'PAYMENT_RECORDED':
      return a?['amount'] == null ? null : formatMoney(a!['amount']);
    case 'PAYMENT_APPLIED':
    case 'PAYMENT_REVERSED':
      final amount = a?['amount'] ?? b?['amount'];
      final status = a?['status'] as String?;
      final moved = [
        if (amount != null) formatMoney(amount),
        if (status != null) 'invoice now ${humanizeEnum(status)}',
      ];
      return moved.isEmpty ? null : moved.join(' · ');
    case 'PROMISE_CREATED':
      final promised = [
        if (a?['amount'] != null) formatMoney(a!['amount']),
        if (a?['promisedDate'] != null) 'by ${formatDate(a!['promisedDate'])}',
      ];
      return promised.isEmpty ? null : promised.join(' ');
    case 'PROMISE_STATUS_CHANGED':
    case 'PROMISE_STATUS_OVERRIDDEN':
    case 'PROMISE_OVERRIDE_CLEARED':
    case 'PROMISE_CANCELLED':
      final from = _statusOf(before);
      final to = _statusOf(after);
      if (from == null || to == null || from == to) return null;
      return '${humanizeEnum(from)} → ${humanizeEnum(to)}';
    default:
      return null;
  }
}

int? _paymentIdOf(String action, Object? before, Object? after) {
  if (action != 'PAYMENT_APPLIED' && action != 'PAYMENT_REVERSED') return null;
  final id = (after is Map ? after['paymentId'] : null) ?? (before is Map ? before['paymentId'] : null);
  return id is num ? id.toInt() : null;
}

typedef AuditHistoryKey = ({String entityType, int entityId, bool includeRelated});

final auditHistoryProvider =
    FutureProvider.autoDispose.family<List<AuditEntry>, AuditHistoryKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/audit', queryParameters: {
    'entityType': key.entityType,
    'entityId': key.entityId,
    if (key.includeRelated) 'includeRelated': true,
  });
  return (res.data as List).cast<Map<String, dynamic>>().map(AuditEntry.fromJson).toList();
});

const _actionLabels = <String, String>{
  'CUSTOMER_CREATED': 'Customer created',
  'CUSTOMER_UPDATED': 'Customer details updated',
  'POC_ASSIGNED': 'POC assigned',
  'POC_REMOVED': 'POC removed',
  'POC_PRIMARY_CHANGED': 'Primary POC changed',
  'INVOICE_CREATED': 'Invoice created',
  'INVOICE_UPDATED': 'Invoice updated',
  'INVOICE_CANCELLED': 'Invoice cancelled',
  'PAYMENT_RECORDED': 'Payment recorded',
  'PAYMENT_UPDATED': 'Payment updated',
  'PAYMENT_APPLIED': 'Payment applied',
  'PAYMENT_REVERSED': 'Payment reversed',
  'PROMISE_CREATED': 'Promise created',
  'PROMISE_UPDATED': 'Promise updated',
  'PROMISE_CANCELLED': 'Promise cancelled',
  'PROMISE_STATUS_CHANGED': 'Promise status changed',
  'PROMISE_STATUS_OVERRIDDEN': 'Promise status overridden',
  'PROMISE_OVERRIDE_CLEARED': 'Promise override cleared',
  'DISPUTE_OPENED': 'Dispute opened',
  'DISPUTE_APPROVED': 'Dispute approved',
  'DISPUTE_DENIED': 'Dispute denied',
};

String auditActionLabel(String action) => _actionLabels[action] ?? humanizeEnum(action);

const _typeOrder = ['CUSTOMER', 'INVOICE', 'PAYMENT', 'PROMISE', 'DISPUTE'];
const _typeLabels = <String, String>{
  'CUSTOMER': 'Customer',
  'INVOICE': 'Invoices',
  'PAYMENT': 'Payments',
  'PROMISE': 'Promises',
  'DISPUTE': 'Disputes',
};

String? _routeFor(String entityType, int id) => switch (entityType) {
      'CUSTOMER' => '/customers/$id',
      'INVOICE' => '/invoices/$id',
      'PAYMENT' => '/payments/$id',
      'PROMISE' => '/promises/$id',
      'DISPUTE' => '/disputes/$id',
      _ => null,
    };

IconData _iconFor(String entityType) => switch (entityType) {
      'CUSTOMER' => Icons.person_outline,
      'INVOICE' => Icons.receipt_long_outlined,
      'PAYMENT' => Icons.payments_outlined,
      'PROMISE' => Icons.handshake_outlined,
      'DISPUTE' => Icons.flag_outlined,
      _ => Icons.history,
    };

/// The History tab. With [includeRelated] the timeline also covers the records hanging off
/// this one — a customer's invoices, payments, promises and disputes; an invoice's payments,
/// promises and disputes — and can be narrowed to one kind of record.
class AuditHistoryPanel extends ConsumerStatefulWidget {
  final String entityType;
  final int entityId;
  final bool includeRelated;

  const AuditHistoryPanel({
    super.key,
    required this.entityType,
    required this.entityId,
    this.includeRelated = false,
  });

  @override
  ConsumerState<AuditHistoryPanel> createState() => _AuditHistoryPanelState();
}

class _AuditHistoryPanelState extends ConsumerState<AuditHistoryPanel> {
  /// Rows shown at a time; a busy account's timeline can run to thousands.
  static const _pageSize = 100;

  /// The kind of record the timeline is narrowed to; null shows everything.
  String? _type;
  int _visible = _pageSize;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(auditHistoryProvider((
      entityType: widget.entityType,
      entityId: widget.entityId,
      includeRelated: widget.includeRelated,
    )));
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(12),
        child: LinearProgressIndicator(),
      ),
      error: (e, _) => Text('History unavailable: ${apiErrorMessage(e)}'),
      data: (list) {
        if (list.isEmpty) return const Text('No history yet.');
        final counts = <String, int>{};
        for (final e in list) {
          counts[e.entityType] = (counts[e.entityType] ?? 0) + 1;
        }
        final types = counts.keys.toList()..sort((a, b) => _rank(a).compareTo(_rank(b)));
        final active = counts.containsKey(_type) ? _type : null;
        final shown = active == null ? list : list.where((e) => e.entityType == active).toList();
        final visible = math.min(_visible, shown.length);
        final remaining = shown.length - visible;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (widget.includeRelated && types.length > 1)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    ChoiceChip(
                      label: Text('All (${list.length})'),
                      selected: active == null,
                      onSelected: (_) => _narrowTo(null),
                    ),
                    for (final t in types)
                      ChoiceChip(
                        label: Text('${_typeLabels[t] ?? humanizeEnum(t)} (${counts[t]})'),
                        selected: active == t,
                        onSelected: (_) => _narrowTo(t),
                      ),
                  ],
                ),
              ),
            for (final a in shown.take(visible))
              _AuditTile(
                key: ValueKey(a.stableKey),
                entry: a,
                showRecord: widget.includeRelated &&
                    !(a.entityType == widget.entityType && a.entityId == widget.entityId),
              ),
            if (remaining > 0)
              Center(
                child: TextButton.icon(
                  icon: const Icon(Icons.expand_more),
                  label: Text('Show ${math.min(_pageSize, remaining)} more ($remaining older)'),
                  onPressed: () => setState(() => _visible = visible + _pageSize),
                ),
              ),
          ],
        );
      },
    );
  }

  void _narrowTo(String? type) => setState(() {
        _type = type;
        _visible = _pageSize;
      });

  int _rank(String type) {
    final i = _typeOrder.indexOf(type);
    return i < 0 ? _typeOrder.length : i;
  }
}

class _AuditTile extends StatelessWidget {
  final AuditEntry entry;

  /// Whether to name the record the row is about — only needed once other records mix in.
  final bool showRecord;

  const _AuditTile({super.key, required this.entry, required this.showRecord});

  static final _when = DateFormat.yMMMd().add_jm();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final linkStyle = TextStyle(color: theme.colorScheme.primary, fontWeight: FontWeight.w600);
    final label = auditActionLabel(entry.action);

    final String? who;
    if (entry.actorHidden) {
      who = null;
    } else if (entry.changedByUsername != null) {
      who = 'by ${entry.changedByUsername}';
    } else {
      who = entry.changedByUserId == null && !entry.derived ? 'automatic' : null;
    }
    final meta = [
      if (entry.createdAt != null) _when.format(entry.createdAt!.toLocal()),
      if (who != null) who,
      if (entry.disputeId != null && entry.entityType != 'DISPUTE') 'via dispute #${entry.disputeId}',
    ].join(' • ');

    Widget link(String text, String? route) => route == null
        ? Text(text, style: linkStyle)
        : InkWell(onTap: () => goGuarded(context, route), child: Text(text, style: linkStyle));

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: ExpansionTile(
        leading: Icon(_iconFor(entry.entityType), size: 20),
        title: Text(entry.headline == null ? label : '$label · ${entry.headline}'),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (showRecord || entry.paymentId != null)
              Wrap(
                spacing: 12,
                children: [
                  if (showRecord)
                    link(entry.entityLabel ?? '${humanizeEnum(entry.entityType)} #${entry.entityId}',
                        _routeFor(entry.entityType, entry.entityId)),
                  if (entry.paymentId != null)
                    link('Payment #${entry.paymentId}', '/payments/${entry.paymentId}'),
                ],
              ),
            if (meta.isNotEmpty) Text(meta),
          ],
        ),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        // Mounted only while expanded, so the snapshots are formatted only when someone looks.
        children: [_AuditDetails(entry: entry)],
      ),
    );
  }
}

class _AuditDetails extends StatelessWidget {
  final AuditEntry entry;
  const _AuditDetails({required this.entry});

  static String _pretty(String raw) {
    try {
      return const JsonEncoder.withIndent('  ').convert(jsonDecode(raw));
    } catch (_) {
      return raw;
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    const mono = TextStyle(fontFamily: 'monospace', fontSize: 12);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (entry.derived)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              'Reconstructed from the record itself — this happened before the history log '
              'covered it.',
              style: theme.textTheme.bodySmall?.copyWith(fontStyle: FontStyle.italic),
            ),
          ),
        if (entry.reason != null && entry.reason!.isNotEmpty) ...[
          Text('Note: ${entry.reason}'),
          const SizedBox(height: 8),
        ],
        if (entry.beforeJson != null) ...[
          Text('Before', style: theme.textTheme.titleSmall),
          SelectableText(_pretty(entry.beforeJson!), style: mono),
          const SizedBox(height: 8),
        ],
        if (entry.afterJson != null) ...[
          Text(entry.beforeJson == null ? 'Details' : 'After', style: theme.textTheme.titleSmall),
          SelectableText(_pretty(entry.afterJson!), style: mono),
        ],
      ],
    );
  }
}
