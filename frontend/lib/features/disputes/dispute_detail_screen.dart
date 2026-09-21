import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/assignee.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../../core/table/table_providers.dart';
import '../email/email_actions.dart';
import '../tasks/tasks_tab.dart' show TaskAssignees;
import 'dispute_assignees_dialog.dart';
import 'disputes_providers.dart';

class DisputeDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const DisputeDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(disputeDetailProvider(id));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      // A dispute that is gone, or not this user's to see, reads as a sentence rather than a
      // raw exception (D-54).
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'dispute'),
        onBack: () => context.go('/disputes'),
      ),
      data: (d) => _DisputeBody(dispute: d, initialTab: initialTab),
    );
  }
}

class _DisputeBody extends ConsumerStatefulWidget {
  final Dispute dispute;
  final String? initialTab;
  const _DisputeBody({required this.dispute, this.initialTab});

  @override
  ConsumerState<_DisputeBody> createState() => _DisputeBodyState();
}

class _DisputeBodyState extends ConsumerState<_DisputeBody> {
  late final TextEditingController _appliedCtrl;
  late final TextEditingController _adminNotesCtrl;
  bool _notify = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _appliedCtrl = TextEditingController(text: _prettyJson(widget.dispute.proposedChangeJson));
    _adminNotesCtrl = TextEditingController(text: widget.dispute.adminNotes ?? '');
  }

  @override
  void dispose() {
    _appliedCtrl.dispose();
    _adminNotesCtrl.dispose();
    super.dispose();
  }

  String _prettyJson(String? raw) {
    if (raw == null || raw.isEmpty) return '';
    try {
      final parsed = jsonDecode(raw);
      return const JsonEncoder.withIndent('  ').convert(parsed);
    } catch (_) {
      return raw;
    }
  }

  Future<void> _resolve(String action) async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      String? applied;
      if (action == 'approve') {
        final text = _appliedCtrl.text.trim();
        if (text.isNotEmpty) {
          jsonDecode(text); // validate
          applied = text;
        }
      }
      final dio = ref.read(dioProvider);
      await dio.post('/api/disputes/${widget.dispute.id}/$action', data: {
        if (_adminNotesCtrl.text.trim().isNotEmpty) 'adminNotes': _adminNotesCtrl.text.trim(),
        if (applied != null) 'appliedChangeJson': applied,
      });
      ref.invalidate(disputeDetailProvider(widget.dispute.id));
      ref.invalidate(scopedDisputesProvider);
      ref.invalidate(tablePageProvider);
      // The email is written here, with the decision still on screen; leaving for the list would
      // take this context with it.
      var compose = EmailComposeOutcome.closed;
      if (mounted) {
        compose = await notifyByEmailAfterSave(context,
            notify: _notify,
            type: EmailEntityType.dispute,
            entityId: widget.dispute.id,
            event: EmailEvent.updated);
      }
      // The compose form's Connect is already taking the app to the Gmail page; going to the
      // list now would win over it.
      if (mounted && compose != EmailComposeOutcome.leftForGmail) context.go('/disputes');
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final d = widget.dispute;
    final canResolve = (user?.isAdmin ?? false) && d.status == DisputeStatus.PENDING;
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final isCustomer = user?.isCustomer ?? true;
    // Who is answerable is staff identity, and a customer login is sent an empty list for exactly
    // that reason (AC-A8) — which is not the same as nobody being on it, so that reader is shown
    // no row rather than one that says "Unassigned" about staff they may not know exist.
    final canSeeAssignees = !isCustomer;
    // Assigning is the server's own act, held at DISPUTE_MANAGE: a dispute arrives from the
    // customer's side and is picked up from this page or from nowhere (A1).
    final canAssign = !isCustomer && (user?.has(Privileges.disputeManage) ?? false);
    final title = 'Dispute #${d.id}';
    final send = sendEmailHeaderButton(context, ref,
        type: EmailEntityType.dispute, entityId: d.id, entityLabel: title);
    final email =
        emailDetailTab(ref, type: EmailEntityType.dispute, entityId: d.id, entityLabel: title);

    return DetailScaffold(
      title: title,
      subtitle: disputeTargetText(d),
      onBack: () => goGuarded(context, '/disputes'),
      titleTrailing: [
        DisputeStatusChip(status: d.status),
        if (canAssign)
          TextButton.icon(
            icon: const Icon(Icons.person_add_alt, size: 18),
            label: Text(d.assignees.isEmpty ? 'Assign' : 'Reassign'),
            onPressed: () => showDisputeAssigneesDialog(context: context, dispute: d),
          ),
        if (send != null) send,
      ],
      initialTabSlug: widget.initialTab,
      onTabChanged: (slug) => context.go('/disputes/${d.id}?tab=$slug'),
      top: _top(d, canResolve: canResolve, canSeeAssignees: canSeeAssignees),
      tabs: [
        // The resolve form is the dispute's edit form. In the top pane, which takes at most about
        // half the page, it left Approve below the fold on a laptop screen; as the first tab it
        // opens by default while there is a decision to make, with the page's height to use.
        if (canResolve)
          DetailTab(
            slug: 'resolve',
            label: 'Resolve',
            icon: Icons.gavel_outlined,
            // The page has no Scaffold, so the notify checkbox's ink needs a Material of its own:
            // the nearest one is the shell's, under the page transition, which paints over it
            // while the page leaves after a decision.
            builder: (context) => Material(
              type: MaterialType.transparency,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: _resolvePanel(d),
              ),
            ),
          ),
        // The disputed record's history, which had a column of its own before the page had tabs.
        if (canViewAudit)
          DetailTab(
            slug: 'history',
            label: '${disputeTargetLabel(d.targetType)} history',
            icon: Icons.history,
            builder: (context) => SingleChildScrollView(
              padding: const EdgeInsets.all(12),
              child: AuditHistoryPanel(entityType: d.targetType.name, entityId: d.targetId),
            ),
          ),
        if (email != null) email,
      ],
    );
  }

  String _proposedChangeText(Dispute d) {
    final pretty = _prettyJson(d.proposedChangeJson);
    return pretty.isEmpty ? '(none — customer only described the problem)' : pretty;
  }

  /// The dispute's facts. The proposed change moves to the Resolve tab while that tab is shown,
  /// beside the applied change that starts as a copy of it.
  Widget _top(Dispute d, {required bool canResolve, required bool canSeeAssignees}) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            'Opened ${formatDateTime(d.createdAt)}'
            '${d.customerName != null ? " by ${d.customerName}" : ""}'
            '${d.resolvedAt != null ? " • resolved ${formatDateTime(d.resolvedAt)}" : ""}',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          DetailGrid(items: [
            DetailGridItem(label: 'Reason', child: Text(d.reason)),
            // The whole list, and each role showing who it reaches right now (A2): a dispute
            // nobody has picked up is exactly what this page is opened to find out.
            if (canSeeAssignees)
              DetailGridItem(
                label: 'Assigned to',
                child: d.assignees.isEmpty
                    ? const Text('Unassigned')
                    : Wrap(
                        spacing: 12,
                        runSpacing: 4,
                        children: [for (final a in d.assignees) _assignee(a)],
                      ),
              ),
            if (!canResolve)
              DetailGridItem(
                label: 'Proposed change',
                child: SelectableText(
                  _proposedChangeText(d),
                  style: const TextStyle(fontFamily: 'monospace'),
                ),
              ),
            if (d.adminNotes != null && d.adminNotes!.isNotEmpty)
              DetailGridItem(label: 'Admin notes', child: Text(d.adminNotes!)),
          ]),
        ],
      ),
    );
  }

  /// One assignee: the seat or the person, and who it reaches now. A seat nobody holds says so,
  /// in the error colour — a dispute that looks assigned but reaches nobody is the thing worth
  /// noticing on this page. The same row a task's and a promise's own page show (A2).
  Widget _assignee(Assignee a) {
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

  /// Approve or deny a pending dispute, for an admin.
  Widget _resolvePanel(Dispute d) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text(
          'Edit the JSON to fine-tune what gets applied on approve. '
          "Leave it blank to use the customer's proposed change.",
          style: TextStyle(fontStyle: FontStyle.italic, fontSize: 12),
        ),
        const SizedBox(height: 8),
        DetailGrid(items: [
          DetailGridItem(
            label: 'Proposed change',
            child: SelectableText(
              _proposedChangeText(d),
              style: const TextStyle(fontFamily: 'monospace'),
            ),
          ),
          DetailGridItem(
            label: 'Applied change (JSON)',
            child: TextField(
              controller: _appliedCtrl,
              minLines: 3,
              maxLines: 6,
              style: const TextStyle(fontFamily: 'monospace'),
              decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true),
            ),
          ),
          DetailGridItem(
            label: 'Admin notes (shown to customer)',
            child: TextField(
              controller: _adminNotesCtrl,
              minLines: 3,
              maxLines: 6,
              decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true),
            ),
          ),
        ]),
        const SizedBox(height: 8),
        NotifyByEmailCheckbox(
          value: _notify,
          onChanged: (v) => setState(() => _notify = v),
        ),
        if (_error != null) ...[
          const SizedBox(height: 8),
          Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
        ],
        const SizedBox(height: 12),
        Row(
          children: [
            FilledButton.icon(
              icon: const Icon(Icons.check),
              label: const Text('Approve'),
              onPressed: _saving ? null : () => _resolve('approve'),
            ),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              icon: const Icon(Icons.close),
              label: const Text('Deny'),
              onPressed: _saving ? null : () => _resolve('deny'),
            ),
          ],
        ),
      ],
    );
  }
}
