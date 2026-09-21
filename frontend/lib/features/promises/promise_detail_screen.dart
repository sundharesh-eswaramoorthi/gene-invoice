import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/assignee.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import '../poc/poc_providers.dart';
import '../tasks/tasks_tab.dart' show TaskAssignees;
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
    final canManage = user?.has(Privileges.promiseManage) ?? false;
    final canOverride = user?.has(Privileges.promiseOverride) ?? false;
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final canSeePoc = ref.watch(canSeePocProvider);
    // Who is answerable is staff identity, and a customer login is sent an empty list for exactly
    // that reason (AC-A8) — which is not the same as nobody being on it, so the row is left out
    // rather than made to say "Unassigned" about people this reader may not know exist.
    final canSeeAssignees = !(user?.isCustomer ?? true);

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'promise'),
        onBack: () => context.go('/promises'),
      ),
      data: (p) {
        final title = 'Promise #${p.id}';
        final send = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.promise, entityId: p.id, entityLabel: title);
        final email =
            emailDetailTab(ref, type: EmailEntityType.promise, entityId: p.id, entityLabel: title);

        return DetailScaffold(
          title: title,
          subtitle: '${p.customerName} • ${formatMoney(p.amount)} by ${formatDate(p.promisedDate)}',
          onBack: () => goGuarded(context, '/promises'),
          titleTrailing: [
            PromiseStatusChip(status: p.status, overridden: p.statusOverridden),
            // The same actions, on the same terms, as the promise's card on a customer or invoice.
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
          top: _top(context, p, canSeePoc: canSeePoc, canSeeAssignees: canSeeAssignees),
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

  /// Top section, tabs and the lists the user came from all pick up the change (AC-C5).
  void _refresh(WidgetRef ref) {
    ref.invalidate(promiseDetailProvider(id));
    ref.invalidate(scopedPromisesProvider);
    ref.invalidate(tablePageProvider);
    ref.invalidate(tableSummaryProvider);
    ref.invalidate(auditHistoryProvider);
  }

  Widget _top(BuildContext context, PaymentPromise p,
      {required bool canSeePoc, required bool canSeeAssignees}) {
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
            // A customer login never learns who its POC is (AC-A8).
            if (canSeePoc)
              DetailGridItem(
                label: 'Collection POC',
                child: Text(p.collectionPoc?.display ?? '—'),
              ),
            // The whole list, not the table's compacted two: this is the page somebody opens to
            // find out who is on it, and a role says who it reaches right now (A2).
            if (canSeeAssignees)
              DetailGridItem(
                label: 'Assigned to',
                span: 2,
                child: p.assignees.isEmpty
                    // Not "—": a promise nobody is answerable for is a promise nobody is
                    // chasing, which is a thing to say rather than a blank.
                    ? const Text('Unassigned')
                    : Wrap(
                        spacing: 12,
                        runSpacing: 4,
                        children: [for (final a in p.assignees) _assignee(context, a)],
                      ),
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

  /// One assignee: the seat or the person, and who it reaches now. A seat nobody holds says so,
  /// in the error colour — a promise that looks assigned but reaches nobody is the thing worth
  /// noticing on this page. The same row a task's own page shows (A2).
  Widget _assignee(BuildContext context, Assignee a) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: TaskAssignees.describe(a),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(a.isUser ? Icons.person_outline : Icons.badge_outlined,
              size: 16, color: a.resolved ? scheme.onSurfaceVariant : scheme.error),
          const SizedBox(width: 4),
          // Cut to the cell rather than out of it: a role that reaches three people is a long
          // line, and the tooltip has all of it whatever the column leaves room for (D-20).
          Flexible(
            child: Text(a.display,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: a.resolved ? null : scheme.error)),
          ),
        ],
      ),
    );
  }

  /// An invoice the promise covers: its number opens it, and what it still owes follows. A
  /// cancelled invoice owes nothing, whatever balance it last showed.
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
