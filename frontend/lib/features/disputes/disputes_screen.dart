import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../shared/models/dispute.dart';
import '../auth/auth_controller.dart';
import 'disputes_providers.dart';

class DisputesScreen extends ConsumerStatefulWidget {
  const DisputesScreen({super.key});
  @override
  ConsumerState<DisputesScreen> createState() => _DisputesScreenState();
}

class _DisputesScreenState extends ConsumerState<DisputesScreen> {
  DisputeStatus? _filter;

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final isAdmin = user?.isAdmin ?? false;
    final async = ref.watch(disputesProvider);
    final df = DateFormat.yMMMd().add_jm();

    return Scaffold(
      body: Column(
        children: [
          if (isAdmin)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Row(
                children: [
                  const Text('Filter: '),
                  const SizedBox(width: 8),
                  ChoiceChip(
                    label: const Text('All'),
                    selected: _filter == null,
                    onSelected: (_) => setState(() => _filter = null),
                  ),
                  const SizedBox(width: 4),
                  ChoiceChip(
                    label: const Text('Pending'),
                    selected: _filter == DisputeStatus.PENDING,
                    onSelected: (_) => setState(() => _filter = DisputeStatus.PENDING),
                  ),
                  const SizedBox(width: 4),
                  ChoiceChip(
                    label: const Text('Approved'),
                    selected: _filter == DisputeStatus.APPROVED,
                    onSelected: (_) => setState(() => _filter = DisputeStatus.APPROVED),
                  ),
                  const SizedBox(width: 4),
                  ChoiceChip(
                    label: const Text('Denied'),
                    selected: _filter == DisputeStatus.DENIED,
                    onSelected: (_) => setState(() => _filter = DisputeStatus.DENIED),
                  ),
                ],
              ),
            ),
          Expanded(
            child: async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text('Failed: $e')),
              data: (list) {
                final filtered = _filter == null
                    ? list
                    : list.where((d) => d.status == _filter).toList();
                if (filtered.isEmpty) return const Center(child: Text('No disputes'));
                return RefreshIndicator(
                  onRefresh: () async => ref.refresh(disputesProvider.future),
                  child: ListView.separated(
                    padding: const EdgeInsets.all(8),
                    itemCount: filtered.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final d = filtered[i];
                      return ListTile(
                        title: Text(
                          '${d.targetType.name} ${d.targetSummary ?? '#${d.targetId}'}',
                        ),
                        subtitle: Text(
                          '${isAdmin && d.customerName != null ? "${d.customerName} • " : ""}'
                          '${df.format(d.createdAt.toLocal())}\n${d.reason}',
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                        ),
                        isThreeLine: true,
                        trailing: Chip(label: Text(disputeStatusLabel(d.status))),
                        onTap: () => context.go('/disputes/${d.id}'),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
