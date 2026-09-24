import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import '../email/email_tab.dart' show EmailRefresher;
import 'automation_providers.dart';

/// THE RUN HISTORY — one list for the event path, the schedule path and "run now" (A5).
///
/// A reader asking what automation has done to an account should not have to know which door the
/// work came in through, so there is ONE table behind this screen and behind the rule's own Runs
/// tab: the `automationSteps` schema, narrowed by a filter in the URL. That is also why there is
/// no `GET /api/automation/runs` — a run IS its steps, and the step rows are already region-scoped
/// by their own axis (A5, B1).
class AutomationActivityScreen extends ConsumerStatefulWidget {
  final TableQuery query;

  /// Where a change to the query goes. The top-level screen pushes it into the URL; the rule's
  /// Runs tab keeps it in state, because a tab has no route of its own.
  final ValueChanged<TableQuery>? onQueryChanged;

  /// A filter this list is ABOUT and cannot be without — `ruleId:eq:7` on a rule's own tab. It is
  /// re-asserted on every query change rather than hidden, so the chip is visible and honest and
  /// taking it off puts it straight back (A5).
  final TableFilter? pinned;

  final bool showHeader;

  const AutomationActivityScreen({
    super.key,
    required this.query,
    this.onQueryChanged,
    this.pinned,
    this.showHeader = true,
  });

  @override
  ConsumerState<AutomationActivityScreen> createState() => _AutomationActivityScreenState();
}

class _AutomationActivityScreenState extends ConsumerState<AutomationActivityScreen> {
  late TableQuery _query = _withPinned(widget.query);

  /// The self-rescheduling poller the email tab already uses: one timer, re-armed from the rows
  /// that came back, and STOPPED once nothing is live. A settled list that went on asking for
  /// ever because somebody left the tab open would be a bug nobody would ever see (A5).
  late final _refresher = EmailRefresher(() {
    if (mounted) invalidateAutomation(ref);
  });

  @override
  void didUpdateWidget(covariant AutomationActivityScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.query != widget.query) _query = _withPinned(widget.query);
  }

  @override
  void dispose() {
    _refresher.dispose();
    super.dispose();
  }

  TableQuery _withPinned(TableQuery query) {
    final pinned = widget.pinned;
    if (pinned == null || query.filters.contains(pinned)) return query;
    return query.withFilters([pinned, ...query.filters.where((f) => f.field != pinned.field)]);
  }

  void _onQueryChanged(TableQuery next) {
    final withPinned = _withPinned(next);
    widget.onQueryChanged?.call(withPinned);
    setState(() => _query = withPinned);
  }

  @override
  Widget build(BuildContext context) {
    final canManage = ref.watch(canManageAutomationProvider);

    // The SAME family key the scaffold below builds, so watching it here costs no second request
    // and the timer is armed from the very rows on screen (A5).
    final pageAsync = ref.watch(tablePageProvider(TableRequest(
      entity: 'automationSteps',
      path: '/api/automation/steps',
      query: _query,
    )));
    _refresher.update(
      automationRefreshInterval(
        (pageAsync.valueOrNull?.content ?? const <Map<String, dynamic>>[])
            .map(AutomationStep.fromJson),
      ),
      busy: pageAsync.isLoading,
    );

    return DataTableScaffold<AutomationStep>(
      entity: 'automationSteps',
      path: '/api/automation/steps',
      query: _query,
      onQueryChanged: _onQueryChanged,
      parse: AutomationStep.fromJson,
      idOf: (s) => s.id,
      // The same reason the rules list has none: POST /api/automation/steps/export does not exist.
      // `selectable: false` kept the button off screen, so this was latent rather than broken —
      // and a latent wire to a mapping nobody wrote is the next person's bug (A1, A5).
      selectable: false,
      emptyMessage: 'Nothing here yet — automation has not touched anything that matches',
      header: widget.showHeader ? const _ActivityHeader() : null,
      quickFilters: const [
        QuickFilterSpec(
          label: 'Stuck only',
          icon: Icons.error_outline,
          filter: TableFilter('status', 'eq', ['POISONED']),
        ),
        QuickFilterSpec(
          label: 'Still running',
          icon: Icons.timelapse,
          filter: TableFilter('status', 'in', ['QUEUED', 'RUNNING']),
        ),
      ],
      columns: [
        TableColumnSpec(
          label: 'When',
          sortKey: 'createdAt',
          // The SOURCE under the time, because "this happened because the invoice changed" is
          // the thing a reader would otherwise have to infer from a null run id — which is
          // exactly why the step keeps the column (A5).
          cell: (context, s) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(formatDateTime(s.createdAt)),
              Text(s.source.label,
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
            ],
          ),
        ),
        TableColumnSpec(
          label: 'Rule',
          sortKey: 'ruleName',
          maxWidth: 200,
          cell: (context, s) => _RuleLink(step: s),
        ),
        TableColumnSpec(
          label: 'Record',
          maxWidth: 200,
          cell: (context, s) => _RecordLink(step: s),
        ),
        TableColumnSpec(
          label: 'Action',
          sortKey: 'actionKind',
          cell: (context, s) => Text(s.actionKind?.label ?? '—'),
        ),
        TableColumnSpec(
          label: 'Status',
          sortKey: 'status',
          cell: (context, s) => StepStatusChip(status: s.status),
        ),
        TableColumnSpec(
          label: 'Attempts',
          sortKey: 'attempts',
          numeric: true,
          cell: (context, s) => Text('${s.attempts}'),
        ),
        TableColumnSpec(
          label: 'Made',
          maxWidth: 160,
          cell: (context, s) => _ProducedLink(step: s),
        ),
        // The server writes these to be READ — 'Task #12', 'The record no longer matches',
        // 'Waiting for approval (change #4): …' — so they are rendered verbatim rather than
        // re-worded here into a second vocabulary for the same fact (A5).
        TableColumnSpec(
          label: 'Result',
          maxWidth: 320,
          cell: (context, s) => _ResultCell(step: s),
        ),
      ],
      rowActions: (context, s) => [
        if (canManage && s.retryable)
          IconButton(
            tooltip: 'Try this again',
            icon: const Icon(Icons.refresh, size: 18),
            onPressed: () => _retry(context, s),
          ),
      ],
      mobileCard: (context, s) => _StepCard(
        step: s,
        onRetry: canManage && s.retryable ? () => _retry(context, s) : null,
      ),
    );
  }

  Future<void> _retry(BuildContext context, AutomationStep step) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await retryStep(ref, step.id);
      messenger.showSnackBar(
          const SnackBar(content: Text('Queued again — it will be picked up shortly')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }
}

/// What this list is, in one sentence, above the filters.
class _ActivityHeader extends StatelessWidget {
  const _ActivityHeader();

  @override
  Widget build(BuildContext context) => const Padding(
        padding: EdgeInsets.fromLTRB(16, 12, 16, 0),
        child: Text(
          'Every task, promise, dispute and email automation has made, and everything it decided '
          'not to.',
        ),
      );
}

class _RuleLink extends ConsumerWidget {
  final AutomationStep step;
  const _RuleLink({required this.step});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final name = step.ruleName.isEmpty ? 'Rule #${step.ruleId ?? '—'}' : step.ruleName;
    final mayOpen = step.ruleId != null &&
        (ref.watch(currentUserProvider)?.has(Privileges.automationView) ?? false);
    final text = Text(name,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: mayOpen
            ? TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w600)
            : null);
    if (!mayOpen) return Tooltip(message: name, child: text);
    return Tooltip(
      message: name,
      child: InkWell(
        onTap: () => goGuarded(context, '/automation/rules/${step.ruleId}'),
        child: text,
      ),
    );
  }
}

/// The record a step acted on, as a link when the reader may open it and as plain text when they
/// may not — a link to a page the router would bounce them off is worse than saying nothing
/// (A5, UI-10).
class _RecordLink extends ConsumerWidget {
  final AutomationStep step;
  const _RecordLink({required this.step});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final link = step.recordLink;
    final label = step.customerName == null
        ? step.recordLabel
        : '${step.recordLabel} · ${step.customerName}';
    // hasIn, not has: a step hangs off one account in one branch, and INVOICE_VIEW held elsewhere
    // is not permission here (A5, B1).
    final mayOpen = link != null &&
        (user?.hasIn(step.subjectType!.recordViewPrivilege, step.regionId) ?? false);
    final text = Text(label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: mayOpen
            ? TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w600)
            : null);
    if (!mayOpen) return Tooltip(message: label, child: text);
    return Tooltip(
      message: label,
      child: InkWell(onTap: () => goGuarded(context, link), child: text),
    );
  }
}

/// What the step MADE, as a link to it.
///
/// PENDING_CHANGE goes to /approvals/<id> and is a SUCCESS, not an error: an action over its
/// region's threshold was parked for a second pair of eyes, and "made a change that is waiting for
/// somebody" is the honest reading (A5, B2 INTEGRATION).
class _ProducedLink extends ConsumerWidget {
  final AutomationStep step;
  const _ProducedLink({required this.step});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final label = step.producedLabel;
    if (label == null) return const Text('—');
    final link = step.producedLink;
    final privilege = step.producedType!.viewPrivilege;
    final mayOpen = link != null &&
        privilege != null &&
        (ref.watch(currentUserProvider)?.has(privilege) ?? false);
    final text = Text(label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: mayOpen
            ? TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w600)
            : null);
    if (!mayOpen) return text;
    return InkWell(onTap: () => goGuarded(context, link), child: text);
  }
}

class _ResultCell extends StatelessWidget {
  final AutomationStep step;
  const _ResultCell({required this.step});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final result = step.result;
    final unresolved = step.unresolved;
    if ((result ?? '').isEmpty && (unresolved ?? '').isEmpty) return const Text('—');
    return Tooltip(
      message: [if (result != null) result, if (unresolved != null) 'Nobody: $unresolved']
          .join('\n'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if ((result ?? '').isNotEmpty)
            Text(result!,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: step.status == StepStatus.POISONED ? TextStyle(color: scheme.error) : null),
          if ((unresolved ?? '').isNotEmpty)
            Text('Nobody: $unresolved',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
        ],
      ),
    );
  }
}

/// A phone has nowhere near eight columns to give (D-61).
class _StepCard extends StatelessWidget {
  final AutomationStep step;
  final VoidCallback? onRetry;

  const _StepCard({required this.step, this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Expanded(child: _RuleLink(step: step)),
              StepStatusChip(status: step.status),
            ],
          ),
          const SizedBox(height: 4),
          _RecordLink(step: step),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(child: Text(step.actionKind?.label ?? '—')),
              _ProducedLink(step: step),
              if (onRetry != null)
                IconButton(
                  tooltip: 'Try this again',
                  icon: const Icon(Icons.refresh, size: 18),
                  onPressed: onRetry,
                ),
            ],
          ),
          _ResultCell(step: step),
        ],
      ),
    );
  }
}

/// The top-level /automation/activity route.
class AutomationActivityPage extends StatelessWidget {
  final TableQuery query;
  const AutomationActivityPage({super.key, required this.query});

  @override
  Widget build(BuildContext context) => Scaffold(
        body: AutomationActivityScreen(
          query: query,
          onQueryChanged: (q) => RouteQuery(context, '/automation/activity').push(q),
        ),
      );
}
