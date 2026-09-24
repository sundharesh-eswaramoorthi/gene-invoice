import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../approvals/approval_providers.dart';
import '../approvals/pending_approval_panel.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import '../poc/poc_providers.dart';
import 'promise_form_dialog.dart';
import 'promise_providers.dart';
import 'promises_tab.dart';

/// A promise's own page (E15). A promise changes only through its dialogs — edit, override,
/// cancel — so the top is read-only and there is nothing unsaved to guard.
class PromiseDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const PromiseDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(promiseDetailProvider(id));
    final user = ref.watch(currentUserProvider);
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'promise'),
        onBack: () => context.go('/promises'),
      ),
      data: (p) {
        // hasIn, not has: a promise belongs to one account in one branch, and holding
        // PROMISE_MANAGE or PROMISE_OVERRIDE elsewhere is not permission here (B1).
        final canManage = user?.hasIn(Privileges.promiseManage, p.regionId) ?? false;
        final canOverride = user?.hasIn(Privileges.promiseOverride, p.regionId) ?? false;
        final title = 'Promise #${p.id}';
        final send = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.promise,
            entityId: p.id,
            entityLabel: title,
            regionId: p.regionId);
        final email = emailDetailTab(ref,
            type: EmailEntityType.promise,
            entityId: p.id,
            entityLabel: title,
            regionId: p.regionId);

        return DetailScaffold(
          title: title,
          subtitle: '${p.customerName} • ${formatMoney(p.amount)} by ${formatDate(p.promisedDate)}',
          onBack: () => goGuarded(context, '/promises'),
          titleTrailing: [
            if (p.approvalPending) const ApprovalPendingChip(),
            PromiseStatusChip(status: p.status, overridden: p.statusOverridden),
            if (canManage && p.isLive)
              TextButton.icon(
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: () async {
                  final saved = await showPromiseDialog(
                    context: context,
                    customerId: p.customerId,
                    customerName: p.customerName,
                    existing: p,
                  );
                  if (saved == true) _refresh(ref);
                },
              ),
            if (canOverride && p.isLive)
              TextButton.icon(
                icon: const Icon(Icons.rule, size: 18),
                label: Text(p.statusOverridden ? 'Override…' : 'Override status'),
                onPressed: () async {
                  final saved = await showOverrideDialog(context: context, promise: p);
                  if (saved == true) _refresh(ref);
                },
              ),
            if (canManage && p.isLive)
              TextButton.icon(
                icon: const Icon(Icons.cancel_outlined, size: 18),
                style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                label: const Text('Cancel'),
                onPressed: () async {
                  if (await cancelPromise(context, ref, p)) _refresh(ref);
                },
              ),
            if (send != null) send,
          ],
          initialTabSlug: initialTab,
          onTabChanged: (slug) => context.go('/promises/$id?tab=$slug'),
          top: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              // Only when the record itself says so: the flag costs the server one indexed
              // lookup it was already making, and this way a page with nothing waiting asks the
              // queue nothing at all (B2).
              if (p.approvalPending)
                PendingApprovalBanner(
                  target: PendingTarget(PendingTargetType.PROMISE, p.id),
                  onDecided: () => _refresh(ref),
                ),
              _top(context, p, canSeePoc: canSeePoc),
            ],
          ),
          tabs: [
            if (canViewAudit)
              DetailTab(
                slug: 'history',
                label: 'History',
                icon: Icons.history,
                builder: (context) => SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: AuditHistoryPanel(entityType: 'PROMISE', entityId: p.id),
                ),
              ),
            if (email != null) email,
          ],
        );
      },
    );
  }

  void _refresh(WidgetRef ref) {
    ref.invalidate(promiseDetailProvider(id));
    ref.invalidate(scopedPromisesProvider);
    ref.invalidate(tablePageProvider);
    ref.invalidate(tableSummaryProvider);
    ref.invalidate(auditHistoryProvider);
  }

  Widget _top(BuildContext context, PaymentPromise p, {required bool canSeePoc}) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _figure(context, 'Promised', formatMoney(p.amount)),
              _figure(context, 'Fulfilled', formatMoney(p.fulfilledAmount)),
              _figure(context, 'Remaining', formatMoney(p.remainingAmount)),
            ],
          ),
          const SizedBox(height: 12),
          DetailGrid(items: [
            DetailGridItem(
              label: 'Customer',
              child: _link(context, p.customerName, '/customers/${p.customerId}'),
            ),
            DetailGridItem(label: 'Promised by', child: Text(formatDate(p.promisedDate))),
            if (canSeePoc)
              DetailGridItem(
                label: 'Collection POC',
                child: Text(p.collectionPoc?.display ?? '—'),
              ),
            DetailGridItem(
              label: 'Invoices',
              span: 2,
              child: p.invoices.isEmpty
                  ? const Text('General promise — against the account balance')
                  : Wrap(
                      spacing: 16,
                      runSpacing: 4,
                      children: [for (final i in p.invoices) _invoice(context, i)],
                    ),
            ),
            if (p.payments.isNotEmpty)
              DetailGridItem(
                label: 'Linked payments',
                child: Wrap(
                  spacing: 16,
                  runSpacing: 4,
                  children: [
                    for (final pay in p.payments)
                      _link(context, 'Payment #${pay.id} • ${formatMoney(pay.amount)}',
                          '/payments/${pay.id}'),
                  ],
                ),
              ),
            DetailGridItem(
              label: 'Notes',
              span: 2,
              child: Text((p.notes ?? '').isEmpty ? '—' : p.notes!),
            ),
            if (p.statusOverridden)
              DetailGridItem(
                label: 'Status override',
                child: Text(
                  '${p.overrideReason ?? '—'}'
                  '${p.overriddenAt != null ? '\n${formatDateTime(p.overriddenAt)}' : ''}',
                ),
              ),
          ]),
        ],
      ),
    );
  }

  Widget _invoice(BuildContext context, PromiseInvoiceRef i) => Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          _link(context, i.invoiceNumber, '/invoices/${i.id}'),
          Text(i.status == 'CANCELLED' ? ' • cancelled' : ' • ${formatMoney(i.balance)} left'),
        ],
      );

  Widget _link(BuildContext context, String label, String location) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: () => goGuarded(context, location),
      child: Text(label, style: TextStyle(color: scheme.primary, fontWeight: FontWeight.w600)),
    );
  }

  Widget _figure(BuildContext context, String label, String value) => Container(
        width: 160,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelMedium),
            const SizedBox(height: 4),
            Text(value,
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.w600)),
          ],
        ),
      );
}
