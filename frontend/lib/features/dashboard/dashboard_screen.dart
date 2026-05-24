import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import '../customer_scope/customer_scope.dart';

final _dashboardProvider = FutureProvider.autoDispose<List<InvoiceSummary>>((ref) async {
  final dio = ref.watch(dioProvider);
  final scope = ref.watch(customerScopeProvider);
  final res = await dio.get('/api/invoices', queryParameters: {
    if (scope != null) 'customerId': scope.id,
  });
  final list = (res.data as List).cast<Map<String, dynamic>>();
  return list.map(InvoiceSummary.fromJson).toList();
});

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final invoices = ref.watch(_dashboardProvider);

    return RefreshIndicator(
      onRefresh: () async => ref.refresh(_dashboardProvider.future),
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('Welcome${user?.fullName != null ? ", ${user!.fullName}" : ""}',
              style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 16),
          invoices.when(
            loading: () => const Center(child: Padding(
                padding: EdgeInsets.all(40), child: CircularProgressIndicator())),
            error: (e, _) => Padding(
                padding: const EdgeInsets.all(20),
                child: Text('Failed to load invoices: $e')),
            data: (list) => _Stats(list: list),
          ),
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
                label: Text(user?.has(Privileges.paymentManage) ?? false
                    ? 'Record payment'
                    : 'My payments'),
                onPressed: () => context.go('/payments'),
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

class _Stats extends StatelessWidget {
  final List<InvoiceSummary> list;
  const _Stats({required this.list});

  @override
  Widget build(BuildContext context) {
    double outstanding = 0;
    double revenue = 0;
    int unpaid = 0;
    int partiallyPaid = 0;
    for (final inv in list) {
      revenue += inv.total;
      outstanding += inv.balance;
      if (inv.status == InvoiceStatus.UNPAID) unpaid++;
      if (inv.status == InvoiceStatus.PARTIALLY_PAID) partiallyPaid++;
    }

    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        _StatCard(label: 'Invoices', value: '${list.length}'),
        _StatCard(label: 'Outstanding', value: outstanding.toStringAsFixed(2)),
        _StatCard(label: 'Total billed', value: revenue.toStringAsFixed(2)),
        _StatCard(label: 'Unpaid', value: '$unpaid'),
        _StatCard(label: 'Partial', value: '$partiallyPaid'),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  const _StatCard({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 180,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 6),
          Text(value, style: Theme.of(context).textTheme.headlineSmall),
        ],
      ),
    );
  }
}
