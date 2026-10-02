import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../auth/auth_controller.dart';
import 'approval_models.dart';
import 'approval_providers.dart';

/// "Awaiting approval: …" across the top of a record's page while a change to it is held (B2).
/// Everyone who can see the record sees it. It goes in the page's `top`, never as a tab, so its
/// coming and going never resets the tabs (G5). Nothing shows while there is nothing waiting, or
/// when the list cannot be had.
class PendingChangeBanner extends ConsumerWidget {
  /// INVOICE, PAYMENT, PROMISE, DISPUTE or CUSTOMER (whose banner lists the creates waiting for it).
  final String entityType;
  final int entityId;

  const PendingChangeBanner({super.key, required this.entityType, required this.entityId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // A customer's own page never lists pending creates for a customer login (§6.11), so there
    // is nothing to ask.
    final isCustomer = ref.watch(currentUserProvider.select((u) => u?.isCustomer ?? false));
    if (isCustomer && entityType == 'CUSTOMER') return const SizedBox.shrink();
    final pending = ref
        .watch(pendingForRecordProvider((entityType: entityType, entityId: entityId)))
        .maybeWhen(data: (list) => list, orElse: () => const <PendingChangeBrief>[]);
    if (pending.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final p in pending)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: PendingBriefBanner(change: p),
            ),
        ],
      ),
    );
  }
}

/// The words of a waiting change: "Awaiting approval: {summary} · requested by {name} on {date}".
/// A customer login is never told who asked (§5.11.9), so then it is only when.
String pendingBannerText(PendingChangeBrief p) {
  final when = p.requestedAt == null ? null : formatDate(p.requestedAt);
  final requested = switch ((p.requestedBy?.name, when)) {
    (final String name, final String date) => 'requested by $name on $date',
    (final String name, null) => 'requested by $name',
    (null, final String date) => 'requested on $date',
    (null, null) => null,
  };
  final summary = p.summary.isEmpty ? (p.operationLabel ?? 'A change') : p.summary;
  return requested == null
      ? 'Awaiting approval: $summary'
      : 'Awaiting approval: $summary · $requested';
}

/// One waiting change, with a way to open it.
class PendingBriefBanner extends StatelessWidget {
  final PendingChangeBrief change;
  const PendingBriefBanner({super.key, required this.change});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final accent = Colors.orange.shade800;
    return Container(
      key: ValueKey('pending-banner-${change.id}'),
      padding: const EdgeInsets.fromLTRB(12, 6, 6, 6),
      decoration: BoxDecoration(
        color: accent.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: accent.withValues(alpha: 0.4)),
      ),
      child: Row(
        children: [
          Icon(Icons.hourglass_top, size: 18, color: accent),
          const SizedBox(width: 8),
          Expanded(
            child: Text(pendingBannerText(change),
                style: TextStyle(color: scheme.onSurface),
                maxLines: 3,
                overflow: TextOverflow.ellipsis),
          ),
          TextButton(
            key: ValueKey('pending-banner-view-${change.id}'),
            onPressed: () => goGuarded(context, '/approvals/${change.id}'),
            child: const Text('View'),
          ),
        ],
      ),
    );
  }
}
