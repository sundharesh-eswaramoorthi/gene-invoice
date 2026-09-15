import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../auth/auth_controller.dart';
import 'promise_form_dialog.dart';
import 'promise_providers.dart';

/// Colour-codes a promise status consistently wherever it appears.
Color promiseStatusColor(BuildContext context, PromiseStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    PromiseStatus.OPEN => scheme.primary,
    PromiseStatus.KEPT => Colors.green.shade700,
    PromiseStatus.PARTIALLY_KEPT => Colors.orange.shade800,
    PromiseStatus.BROKEN => scheme.error,
    PromiseStatus.CANCELLED => scheme.outline,
  };
}

class PromiseStatusChip extends StatelessWidget {
  final PromiseStatus status;
  final bool overridden;
  const PromiseStatusChip({super.key, required this.status, this.overridden = false});

  @override
  Widget build(BuildContext context) {
    final color = promiseStatusColor(context, status);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: color.withValues(alpha: 0.4)),
          ),
          child: Text(promiseStatusLabel(status),
              style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
        ),
        if (overridden)
          const Padding(
            padding: EdgeInsets.only(left: 4),
            child: Tooltip(
              message: 'Status was set by hand, not worked out from payments',
              child: Icon(Icons.edit_note, size: 16),
            ),
          ),
      ],
    );
  }
}

/// The Payment Promise tab shared by the Customer and Invoice detail screens (C.3).
class PromisesTab extends ConsumerWidget {
  final int customerId;
  final String? customerName;

  /// When set, the tab shows only promises covering this invoice and pre-scopes new ones.
  final int? invoiceId;

  const PromisesTab({
    super.key,
    required this.customerId,
    this.customerName,
    this.invoiceId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.promiseManage) ?? false;
    final canOverride = user?.has(Privileges.promiseOverride) ?? false;
    final scope = PromiseScope(customerId: customerId, invoiceId: invoiceId);
    final async = ref.watch(scopedPromisesProvider(scope));

    return Column(
      children: [
        if (canManage)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: Align(
              alignment: Alignment.centerLeft,
              child: FilledButton.icon(
                icon: const Icon(Icons.add),
                label: const Text('Raise promise'),
                onPressed: () async {
                  final saved = await showPromiseDialog(
                    context: context,
                    customerId: customerId,
                    customerName: customerName,
                    preselectedInvoiceIds: invoiceId == null ? const [] : [invoiceId!],
                  );
                  if (saved == true) ref.invalidate(scopedPromisesProvider(scope));
                },
              ),
            ),
          ),
        Expanded(
          child: async.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => _TabError(message: apiErrorMessage(e), onRetry: () {
              ref.invalidate(scopedPromisesProvider(scope));
            }),
            data: (promises) {
              if (promises.isEmpty) {
                return const Center(child: Text('No promises on this record'));
              }
              return ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: promises.length,
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (context, i) => PromiseCard(
                  promise: promises[i],
                  canManage: canManage,
                  canOverride: canOverride,
                  onChanged: () => ref.invalidate(scopedPromisesProvider(scope)),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class PromiseCard extends ConsumerWidget {
  final PaymentPromise promise;
  final bool canManage;
  final bool canOverride;
  final VoidCallback onChanged;

  const PromiseCard({
    super.key,
    required this.promise,
    required this.canManage,
    required this.canOverride,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${formatMoney(promise.amount)} by ${formatDate(promise.promisedDate)}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                PromiseStatusChip(
                    status: promise.status, overridden: promise.statusOverridden),
              ],
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 16,
              runSpacing: 4,
              children: [
                Text('Fulfilled ${formatMoney(promise.fulfilledAmount)}'),
                Text('Remaining ${formatMoney(promise.remainingAmount)}'),
                if (promise.collectionPoc != null)
                  Text('POC: ${promise.collectionPoc!.display}'),
              ],
            ),
            if (promise.invoices.isNotEmpty) ...[
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 4,
                children: promise.invoices
                    .map((i) => Chip(
                          visualDensity: VisualDensity.compact,
                          label: Text('${i.invoiceNumber} • ${formatMoney(i.balance)} left'),
                        ))
                    .toList(),
              ),
            ],
            if (promise.payments.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(
                'Linked payments: ${promise.payments.map((p) => '#${p.id} ${formatMoney(p.amount)}').join(', ')}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            if (promise.notes != null && promise.notes!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(promise.notes!, style: const TextStyle(fontStyle: FontStyle.italic)),
            ],
            if (promise.statusOverridden && promise.overrideReason != null) ...[
              const SizedBox(height: 6),
              Text('Overridden: ${promise.overrideReason}',
                  style: Theme.of(context).textTheme.bodySmall),
            ],
            if (canManage || canOverride)
              Align(
                alignment: Alignment.centerRight,
                child: Wrap(
                  children: [
                    if (canManage && promise.isLive)
                      TextButton.icon(
                        icon: const Icon(Icons.edit_outlined, size: 18),
                        label: const Text('Edit'),
                        onPressed: () async {
                          final saved = await showPromiseDialog(
                            context: context,
                            customerId: promise.customerId,
                            customerName: promise.customerName,
                            existing: promise,
                          );
                          if (saved == true) onChanged();
                        },
                      ),
                    if (canOverride && promise.isLive)
                      TextButton.icon(
                        icon: const Icon(Icons.rule, size: 18),
                        label: Text(promise.statusOverridden ? 'Override…' : 'Override status'),
                        onPressed: () async {
                          final saved =
                              await showOverrideDialog(context: context, promise: promise);
                          if (saved == true) onChanged();
                        },
                      ),
                    if (canManage && promise.isLive)
                      TextButton.icon(
                        icon: const Icon(Icons.cancel_outlined, size: 18),
                        style: TextButton.styleFrom(
                            foregroundColor: Theme.of(context).colorScheme.error),
                        label: const Text('Cancel'),
                        onPressed: () => _cancel(context, ref),
                      ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _cancel(BuildContext context, WidgetRef ref) async {
    final reason = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cancel this promise?'),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Linked payments are unlinked. The payments themselves and their invoice '
                'allocations are left untouched.',
              ),
              const SizedBox(height: 12),
              TextField(
                controller: reason,
                decoration: const InputDecoration(labelText: 'Reason (optional)'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Cancel promise'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref
          .read(dioProvider)
          .post('/api/promises/${promise.id}/cancel', data: {'reason': reason.text.trim()});
      onChanged();
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    }
  }
}

class _TabError extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _TabError({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
              const SizedBox(height: 8),
              Text(message, textAlign: TextAlign.center),
              const SizedBox(height: 8),
              TextButton(onPressed: onRetry, child: const Text('Retry')),
            ],
          ),
        ),
      );
}
