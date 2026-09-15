import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';

/// The dashboard reads the same filter-aware aggregate the Invoices tiles use, so the two
/// can never disagree (AC-E6).
final _invoiceTilesProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices/summary');
  return (res.data as Map).cast<String, dynamic>();
});

final _promiseTilesProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/promises/summary');
  return (res.data as Map).cast<String, dynamic>();
});

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final invoices = ref.watch(_invoiceTilesProvider);
    final canSeePromises = user?.has(Privileges.promiseView) ?? false;

    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(_invoiceTilesProvider);
        ref.invalidate(_promiseTilesProvider);
      },
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('Welcome${user?.fullName != null ? ", ${user!.fullName}" : ""}',
              style: Theme.of(context).textTheme.headlineSmall),
          Text(
            'These figures cover everything you are permitted to see.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 16),
          Text('Invoices', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          invoices.when(
            loading: () => const Center(
                child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator())),
            error: (e, _) => Padding(
              padding: const EdgeInsets.all(12),
              child: Text('Could not load the invoice summary: ${apiErrorMessage(e)}'),
            ),
            data: (s) => Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                SummaryTile(label: 'Invoices', value: '${s['count'] ?? 0}'),
                SummaryTile(label: 'Total billed', value: formatMoneyCompact(s['totalBilled'])),
                SummaryTile(
                  label: 'Outstanding',
                  value: formatMoneyCompact(s['outstanding']),
                  accent: Theme.of(context).colorScheme.error,
                ),
                SummaryTile(label: 'Unpaid', value: '${s['unpaidCount'] ?? 0}'),
                SummaryTile(label: 'Partially paid', value: '${s['partiallyPaidCount'] ?? 0}'),
              ],
            ),
          ),
          if (canSeePromises) ...[
            const SizedBox(height: 24),
            Text('Payment promises', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ref.watch(_promiseTilesProvider).when(
                  loading: () => const Center(
                      child: Padding(
                          padding: EdgeInsets.all(24), child: CircularProgressIndicator())),
                  error: (e, _) => Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text('Could not load the promise summary: ${apiErrorMessage(e)}'),
                  ),
                  data: (s) => Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      SummaryTile(
                        label: 'Open',
                        value: '${s['openCount'] ?? 0} • ${formatMoneyCompact(s['openAmount'])}',
                      ),
                      SummaryTile(
                        label: 'Kept',
                        value: '${s['keptCount'] ?? 0}',
                        accent: Colors.green.shade700,
                      ),
                      SummaryTile(
                        label: 'Broken',
                        value:
                            '${s['brokenCount'] ?? 0} • ${formatMoneyCompact(s['brokenAmount'])}',
                        accent: Theme.of(context).colorScheme.error,
                      ),
                    ],
                  ),
                ),
          ],
          const SizedBox(height: 24),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              if (user?.has(Privileges.invoiceManage) ?? false)
                FilledButton.icon(
                  icon: const Icon(Icons.add),
                  label: const Text('New invoice'),
                  onPressed: () => context.go('/invoices/new'),
                ),
              OutlinedButton.icon(
                icon: const Icon(Icons.list),
                label: Text(user?.isCustomer ?? false ? 'My invoices' : 'All invoices'),
                onPressed: () => context.go('/invoices'),
              ),
              OutlinedButton.icon(
                icon: const Icon(Icons.payments_outlined),
                label: Text(
                    user?.has(Privileges.paymentManage) ?? false ? 'Record payment' : 'My payments'),
                onPressed: () => context.go('/payments'),
              ),
              if (canSeePromises)
                OutlinedButton.icon(
                  icon: const Icon(Icons.handshake_outlined),
                  label: const Text('Payment promises'),
                  onPressed: () => context.go('/promises'),
                ),
              if (user?.has(Privileges.disputeView) ?? false)
                OutlinedButton.icon(
                  icon: const Icon(Icons.flag_outlined),
                  label: const Text('Disputes'),
                  onPressed: () => context.go('/disputes'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
