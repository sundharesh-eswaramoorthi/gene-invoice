import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/notification_strategy.dart';
import 'strategies_providers.dart';

/// Admin workbench listing each separately saved strategy with its active state, plus
/// activate/deactivate, edit, and a manual-run action that works on any saved row —
/// including an inactive one — without changing its active state.
class StrategiesScreen extends ConsumerWidget {
  const StrategiesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(strategiesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Notification strategies')),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.add),
        label: const Text('New strategy'),
        onPressed: () async {
          final saved = await context.push<bool>('/strategies/new');
          if (saved == true) ref.invalidate(strategiesProvider);
        },
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) {
          if (list.isEmpty) {
            return const Center(child: Text('No strategies yet'));
          }
          return RefreshIndicator(
            onRefresh: () async => ref.refresh(strategiesProvider.future),
            child: ListView.separated(
              padding: const EdgeInsets.all(8),
              itemCount: list.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, i) => _StrategyTile(strategy: list[i]),
            ),
          );
        },
      ),
    );
  }
}

class _StrategyTile extends ConsumerWidget {
  final NotificationStrategy strategy;
  const _StrategyTile({required this.strategy});

  String _summary(NotificationStrategy s) {
    final dateOp = dateOperatorLabels[s.dateOperator] ?? s.dateOperator;
    final amountOp = amountOperatorLabels[s.amountOperator] ?? s.amountOperator;
    return '${s.statuses.join(", ")} • date $dateOp • amount $amountOp';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ListTile(
      leading: Icon(
        strategy.active ? Icons.toggle_on : Icons.toggle_off_outlined,
        color: strategy.active ? Theme.of(context).colorScheme.primary : null,
      ),
      title: Text(strategy.title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_summary(strategy)),
          if (strategy.description != null && strategy.description!.isNotEmpty)
            Text(strategy.description!,
                style: Theme.of(context).textTheme.bodySmall),
          Text(strategy.active ? 'Active' : 'Inactive',
              style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
      trailing: Wrap(
        spacing: 4,
        children: [
          IconButton(
            tooltip: 'Run now (does not change active state)',
            icon: const Icon(Icons.play_arrow_outlined),
            onPressed: () => _runNow(context, ref),
          ),
          IconButton(
            tooltip: strategy.active ? 'Deactivate' : 'Activate',
            icon: Icon(strategy.active
                ? Icons.pause_circle_outline
                : Icons.play_circle_outline),
            onPressed: () => _toggleActive(context, ref),
          ),
          IconButton(
            tooltip: 'Edit',
            icon: const Icon(Icons.edit_outlined),
            onPressed: () async {
              final saved = await context
                  .push<bool>('/strategies/${strategy.id}/edit');
              if (saved == true) ref.invalidate(strategiesProvider);
            },
          ),
        ],
      ),
    );
  }

  Future<void> _toggleActive(BuildContext context, WidgetRef ref) async {
    final dio = ref.read(dioProvider);
    final action = strategy.active ? 'deactivate' : 'activate';
    try {
      await dio.post('/api/notification-strategies/${strategy.id}/$action');
      ref.invalidate(strategiesProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }

  Future<void> _runNow(BuildContext context, WidgetRef ref) async {
    final dio = ref.read(dioProvider);
    try {
      final res =
          await dio.post('/api/notification-strategies/${strategy.id}/run');
      final status = (res.data as Map)['status'];
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Strategy run started (status: $status)')));
      }
      ref.invalidate(strategiesProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }
}
