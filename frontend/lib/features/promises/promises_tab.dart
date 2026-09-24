import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/status_chip.dart';
import '../approvals/pending_approval_panel.dart';
import '../auth/auth_controller.dart';
import 'promise_form_dialog.dart';
import 'promise_providers.dart';

class PromiseStatusChip extends StatelessWidget {
  final PromiseStatus status;
  final bool overridden;
  const PromiseStatusChip({super.key, required this.status, this.overridden = false});

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

class PromisesTab extends ConsumerWidget {
  final int customerId;
  final String? customerName;

  /// When set, the tab shows only promises covering this invoice and pre-scopes new ones. The
  /// whole invoice, not just its id: the Raise promise dialog shows it as a ticked, untickable
  /// line, which it cannot do for an invoice it knows nothing about (UI-02).
  final InvoiceSummary? invoice;

  final int? paymentId;

  /// Which branch the account this tab hangs off is in, so "Raise promise" is offered only to
  /// somebody who may write here rather than to anybody holding PROMISE_MANAGE somewhere (B1).
  final int? regionId;

  const PromisesTab({
    super.key,
    required this.customerId,
    this.customerName,
    this.invoice,
    this.paymentId,
    this.regionId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.hasIn(Privileges.promiseManage, regionId) ?? false;
    final canOverride = user?.hasIn(Privileges.promiseOverride, regionId) ?? false;
    final scope =
        PromiseScope(customerId: customerId, invoiceId: invoice?.id, paymentId: paymentId);
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
                    preselectedInvoices: invoice == null ? const [] : [invoice!],
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
    // Narrowed a second time per row: a tab can list promises from more than one branch once a
    // record's account has moved, and the card's own promise is the only thing that says where
    // this one lives (B1).
    final viewer = ref.watch(currentUserProvider);
    final mayManage =
        canManage && (viewer?.hasIn(Privileges.promiseManage, promise.regionId) ?? false);
    final mayOverride =
        canOverride && (viewer?.hasIn(Privileges.promiseOverride, promise.regionId) ?? false);
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
            Align(
              alignment: Alignment.centerRight,
              child: Wrap(
                children: [
                  TextButton.icon(
                    icon: const Icon(Icons.open_in_new, size: 18),
                    label: const Text('Open'),
                    onPressed: () => goGuarded(context, '/promises/${promise.id}'),
                  ),
                  if (mayManage && promise.isLive)
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
                  if (mayOverride && promise.isLive)
                    TextButton.icon(
                      icon: const Icon(Icons.rule, size: 18),
                      label: Text(promise.statusOverridden ? 'Override…' : 'Override status'),
                      onPressed: () async {
                        final saved = await showOverrideDialog(context: context, promise: promise);
                        if (saved == true) onChanged();
                      },
                    ),
                  if (mayManage && promise.isLive)
                    TextButton.icon(
                      icon: const Icon(Icons.cancel_outlined, size: 18),
                      style: TextButton.styleFrom(
                          foregroundColor: Theme.of(context).colorScheme.error),
                      label: const Text('Cancel'),
                      onPressed: () async {
                        if (await cancelPromise(context, ref, promise)) onChanged();
                      },
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

Future<bool> cancelPromise(BuildContext context, WidgetRef ref, PaymentPromise promise) async {
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
  if (ok != true) return false;
  try {
    await ref
        .read(dioProvider)
        .post('/api/promises/${promise.id}/cancel', data: {'reason': reason.text.trim()});
    return true;
  } on DioException catch (e) {
    // PROMISE_CANCEL is gated on the promise's amount, so a big one comes back 202 and the
    // promise is still ACTIVE. `true` here would tell the caller to refresh as though the cancel
    // had happened, so a held answer returns false and says amber what it says (B2).
    final held = pendingApprovalOf(e);
    if (held != null) {
      if (context.mounted) showApprovalSentSnackBar(context, held);
      return false;
    }
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
    return false;
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
    return false;
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
