import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import 'dispute_create_dialog.dart';
import 'disputes_providers.dart';

/// The Disputes tab shared by the detail screens. Reuses the existing dispute screens
/// for anything beyond the list itself (C.3).
class DisputesTab extends ConsumerWidget {
  /// Filters to one record. Leave null on a customer to show all of that customer's disputes.
  final DisputeTargetType? targetType;
  final int? targetId;
  final int? customerId;
  final String? targetLabel;

  const DisputesTab({
    super.key,
    this.targetType,
    this.targetId,
    this.customerId,
    this.targetLabel,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canCreate =
        (user?.has(Privileges.disputeCreate) ?? false) && targetType != null && targetId != null;
    final scope = DisputeScope(
        targetType: targetType, targetId: targetId, customerId: customerId);
    final async = ref.watch(scopedDisputesProvider(scope));

    return Column(
      children: [
        if (canCreate)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: Align(
              alignment: Alignment.centerLeft,
              child: FilledButton.icon(
                icon: const Icon(Icons.flag_outlined),
                label: const Text('Raise dispute'),
                onPressed: () async {
                  final filed = await showDisputeDialog(
                    context: context,
                    targetType: targetType!,
                    targetId: targetId!,
                    targetLabel: targetLabel ?? '#$targetId',
                  );
                  if (filed == true) ref.invalidate(scopedDisputesProvider(scope));
                },
              ),
            ),
          ),
        Expanded(
          child: async.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
                    const SizedBox(height: 8),
                    Text(apiErrorMessage(e), textAlign: TextAlign.center),
                    TextButton(
                      onPressed: () => ref.invalidate(scopedDisputesProvider(scope)),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
            ),
            data: (disputes) {
              if (disputes.isEmpty) {
                return const Center(child: Text('No disputes on this record'));
              }
              return ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: disputes.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  final d = disputes[i];
                  return ListTile(
                    title: Text('${d.targetType.name} ${d.targetSummary ?? '#${d.targetId}'}'),
                    subtitle: Text('${formatDateTime(d.createdAt)}\n${d.reason}',
                        maxLines: 3, overflow: TextOverflow.ellipsis),
                    isThreeLine: true,
                    trailing: Chip(label: Text(disputeStatusLabel(d.status))),
                    onTap: () => goGuarded(context, '/disputes/${d.id}'),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}
