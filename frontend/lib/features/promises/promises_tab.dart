import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'promise_form_dialog.dart';
import 'promise_providers.dart';

// promiseStatusColor now lives in shared/widgets/status_chip.dart, beside the colours for
// invoices, payments and disputes, so every screen can use the same palette.

class PromiseStatusChip extends StatelessWidget {
  final PromiseStatus status;
  final bool overridden;
  const PromiseStatusChip({super.key, required this.status, this.overridden = false});

  // The shared chip, so promises, invoices, payments and disputes are one widget rather than
  // four copies of the same shape.
  @override
  Widget build(BuildContext context) => StatusChip(
        label: promiseStatusLabel(status),
        color: promiseStatusColor(context, status),
        trailing: overridden
            ? const Padding(
                padding: EdgeInsets.only(left: 4),
                child: Tooltip(
                  message: 'Status was set by hand, not worked out from payments',
                  child: Icon(Icons.edit_note, size: 16),
                ),
              )
            : null,
      );
}

/// The Payment Promise tab shared by the Customer and Invoice detail screens (C.3).
class PromisesTab extends ConsumerWidget {
  final int customerId;
  final String? customerName;

  /// When set, the tab shows only promises covering this invoice and pre-scopes new ones.
  final int? invoiceId;

  /// When set, the tab shows only the promises this payment counts towards. Promises are raised
  /// from the customer or an invoice, so the tab offers no "Raise promise" here.
  final int? paymentId;

  const PromisesTab({
    super.key,
    required this.customerId,
    this.customerName,
    this.invoiceId,
    this.paymentId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.promiseManage) ?? false;
    final canOverride = user?.has(Privileges.promiseOverride) ?? false;
    final scope = PromiseScope(customerId: customerId, invoiceId: invoiceId, paymentId: paymentId);
    final async = ref.watch(scopedPromisesProvider(scope));

    return Column(
      children: [
        if (canManage && paymentId == null)
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
                    // A cancelled invoice owes nothing, whatever balance it last showed. The chip
                    // takes that invoice's own status colour.
                    .map((i) => StatusChip(
                          label: i.status == 'CANCELLED'
                              ? '${i.invoiceNumber} • cancelled'
                              : '${i.invoiceNumber} • ${formatMoney(i.balance)} left',
                          color: invoiceStatusColorFromWire(context, i.status),
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
