import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/pending_reply.dart';
import '../../core/format.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../approvals/pending_banner.dart';
import '../assignees/record_assignees.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../../core/table/table_providers.dart';
import '../email/email_actions.dart';
import '../regions/regions_screen.dart';
import 'disputes_providers.dart';

class DisputeDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const DisputeDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(disputeDetailProvider(id));
    final asOf = ref.watch(viewAsOfProvider);
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      // A dispute that is gone, or not this user's to see, reads as a sentence rather than a
      // raw exception (D-54).
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'dispute', asOf: asOf),
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
          jsonDecode(text);
          applied = text;
        }
      }
      final dio = ref.read(dioProvider);
      final res = await dio.post('/api/disputes/${widget.dispute.id}/$action', data: {
        if (_adminNotesCtrl.text.trim().isNotEmpty) 'adminNotes': _adminNotesCtrl.text.trim(),
        if (applied != null) 'appliedChangeJson': applied,
      });
      // An approval that moves more than the region's limit waits for a second approver (B2):
      // the dispute stays open, so there is nothing to email about and the page stays.
      final held = pendingFrom(res);
      if (held != null) {
        ref.invalidate(disputeDetailProvider(widget.dispute.id));
        ref.invalidate(scopedDisputesProvider);
        ref.invalidate(auditHistoryProvider);
        invalidateApprovals(ref);
        if (mounted) {
          showSentForApproval(context, held, message: 'This approval needs a second approver');
        }
        return;
      }
      ref.invalidate(disputeDetailProvider(widget.dispute.id));
      ref.invalidate(scopedDisputesProvider);
      ref.invalidate(tablePageProvider);
      var compose = EmailComposeOutcome.closed;
      if (mounted) {
        compose = await notifyByEmailAfterSave(context,
            notify: _notify,
            type: EmailEntityType.dispute,
            entityId: widget.dispute.id,
            event: EmailEvent.updated);
      }
      if (mounted && compose != EmailComposeOutcome.leftForGmail) context.go('/disputes');
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _editAssignees(Dispute d) async {
    final saved = await showRecordAssigneesDialog(context,
        type: EmailEntityType.dispute,
        entityId: d.id,
        path: '/api/disputes/${d.id}/assignees',
        title: 'Assign dispute #${d.id}',
        current: d.assignees ?? const []);
    if (!saved || !mounted) return;
    ref.invalidate(disputeDetailProvider(d.id));
    ref.invalidate(auditHistoryProvider);
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('Assignees saved')));
  }

  /// A dispute on a customer carries no change to make: approving it acknowledges it (§5.10.11).
  bool _acknowledgeOnly(Dispute d) => d.targetType == DisputeTargetType.CUSTOMER;

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final d = widget.dispute;
    final historical = ref.watch(isHistoricalProvider);
    // A past date is read only (D2). Resolving is DISPUTE_MANAGE in the dispute's region, which
    // the server answers for this dispute (D14) — not the role's name (G20).
    final canResolve = !historical &&
        (d.canResolve ?? user?.has(Privileges.disputeManage) ?? false) &&
        d.status == DisputeStatus.PENDING;
    // Assigning is DISPUTE_MANAGE in the dispute's region too (§6.13).
    final canAssign = !historical &&
        user != null &&
        !user.isCustomer &&
        user.hasIn(Privileges.disputeManage, d.regionId);
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final title = 'Dispute #${d.id}';
    final send = sendEmailHeaderButton(context, ref,
        type: EmailEntityType.dispute, entityId: d.id, entityLabel: title, regionId: d.regionId);
    final email = emailDetailTab(ref,
        type: EmailEntityType.dispute, entityId: d.id, entityLabel: title, regionId: d.regionId);

    return DetailScaffold(
      title: title,
      subtitle: disputeTargetText(d),
      onBack: () => goGuarded(context, '/disputes'),
      titleTrailing: [
        DisputeStatusChip(status: d.status),
        // Raised by a rule for the team's own follow-up; its customer never sees it (D17).
        if (d.internal ?? false)
          const Tooltip(
            message: "For the team only: the customer doesn't see it",
            child: Chip(
              key: ValueKey('dispute-internal'),
              avatar: Icon(Icons.lock_outline, size: 16),
              label: Text('Internal'),
              visualDensity: VisualDensity.compact,
            ),
          ),
        // Only those who may see rules get a link to one; for the rest it is a plain label.
        if (d.ruleId != null && (user?.has(Privileges.automationView) ?? false))
          ActionChip(
            key: const ValueKey('dispute-rule'),
            avatar: const Icon(Icons.bolt_outlined, size: 16),
            label: const Text('Created by rule'),
            tooltip: 'Rule #${d.ruleId}',
            visualDensity: VisualDensity.compact,
            onPressed: () => goGuarded(context, '/automation/${d.ruleId}'),
          )
        else if (d.ruleId != null)
          const Chip(
            key: ValueKey('dispute-rule'),
            avatar: Icon(Icons.bolt_outlined, size: 16),
            label: Text('Created by rule'),
            visualDensity: VisualDensity.compact,
          ),
        if (send != null) send,
      ],
      initialTabSlug: widget.initialTab,
      onTabChanged: (slug) => context.go('/disputes/${d.id}?tab=$slug'),
      top: _top(d,
          canResolve: canResolve,
          canAssign: canAssign,
          showRegion: user?.multiRegion ?? false),
      tabs: [
        if (canResolve)
          DetailTab(
            slug: 'resolve',
            label: 'Resolve',
            icon: Icons.gavel_outlined,
            builder: (context) => Material(
              type: MaterialType.transparency,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: _resolvePanel(d),
              ),
            ),
          ),
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
    if (pretty.isNotEmpty) return pretty;
    return (d.internal ?? false) ? '(none)' : '(none — customer only described the problem)';
  }

  Widget _top(Dispute d,
      {required bool canResolve, required bool canAssign, required bool showRegion}) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          // A change to it held for approval shows here, above everything else (G5).
          PendingChangeBanner(entityType: 'DISPUTE', entityId: d.id),
          Text(
            'Opened ${formatDateTime(d.createdAt)}'
            '${d.customerName != null ? " by ${d.customerName}" : ""}'
            '${d.resolvedAt != null ? " • resolved ${formatDateTime(d.resolvedAt)}" : ""}',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          DetailGrid(items: [
            DetailGridItem(label: 'Reason', child: Text(d.reason)),
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
            if (showRegion)
              DetailGridItem(
                  label: 'Region', child: Text(regionLabel(d.regionCode, d.regionName))),
            // Only a dispute's own page carries them; a server that sends none has none to show.
            if (d.assignees != null)
              DetailGridItem(
                label: 'Assigned to',
                span: 2,
                child: RecordAssigneesView(
                  assignees: d.assignees!,
                  onEdit: canAssign ? () => _editAssignees(d) : null,
                ),
              ),
          ]),
        ],
      ),
    );
  }

  Widget _resolvePanel(Dispute d) {
    if (_acknowledgeOnly(d)) return _acknowledgePanel(d);
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
  /// A dispute about a customer as a whole: there is nothing to apply, only to acknowledge it or
  /// close it without change (§5.10.11).
  Widget _acknowledgePanel(Dispute d) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text(
          'This dispute is about the customer, so nothing changes either way: acknowledge it '
          'once it has been dealt with, or close it without change.',
          style: TextStyle(fontStyle: FontStyle.italic, fontSize: 12),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _adminNotesCtrl,
          minLines: 3,
          maxLines: 6,
          decoration: InputDecoration(
            labelText: (d.internal ?? false) ? 'Notes' : 'Admin notes (shown to customer)',
            border: const OutlineInputBorder(),
            isDense: true,
          ),
        ),
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
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              key: const ValueKey('dispute-acknowledge'),
              icon: const Icon(Icons.check),
              label: const Text('Acknowledge'),
              onPressed: _saving ? null : () => _resolve('approve'),
            ),
            OutlinedButton.icon(
              key: const ValueKey('dispute-close'),
              icon: const Icon(Icons.close),
              label: const Text('Close without change'),
              onPressed: _saving ? null : () => _resolve('deny'),
            ),
          ],
        ),
      ],
    );
  }
}
