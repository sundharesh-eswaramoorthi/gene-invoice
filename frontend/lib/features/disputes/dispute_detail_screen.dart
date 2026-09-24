import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/dispute.dart';
import '../../shared/models/pending_change.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../approvals/approval_providers.dart';
import '../approvals/pending_approval_panel.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../../core/table/table_providers.dart';
import '../email/email_actions.dart';
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
          jsonDecode(text);
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
      var compose = EmailComposeOutcome.closed;
      if (mounted) {
        compose = await notifyByEmailAfterSave(context,
            notify: _notify,
            type: EmailEntityType.dispute,
            entityId: widget.dispute.id,
            event: EmailEvent.updated);
      }
      if (mounted && compose != EmailComposeOutcome.leftForGmail) context.go('/disputes');
    } on DioException catch (e) {
      final held = pendingApprovalOf(e);
      if (held != null) {
        // Approving a dispute can move money, so it is gated like any other money write. The
        // dispute is NOT resolved and this page must stay where it is — the navigation to
        // /disputes above is on the success path only (B2).
        ref.invalidate(disputeDetailProvider(widget.dispute.id));
        ref.invalidate(scopedDisputesProvider);
        invalidateApprovals(ref);
        if (mounted) {
          setState(() => _error = null);
          showApprovalSentSnackBar(context, held);
        }
        return;
      }
      setState(() => _error = apiErrorMessage(e));
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
        if (d.approvalPending) const ApprovalPendingChip(),
        DisputeStatusChip(status: d.status),
        if (send != null) send,
      ],
      initialTabSlug: widget.initialTab,
      onTabChanged: (slug) => context.go('/disputes/${d.id}?tab=$slug'),
      top: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (d.approvalPending)
            PendingApprovalBanner(
              target: PendingTarget(PendingTargetType.DISPUTE, d.id),
              onDecided: () {
                ref.invalidate(disputeDetailProvider(d.id));
                ref.invalidate(scopedDisputesProvider);
              },
            ),
          _top(d, canResolve: canResolve),
        ],
      ),
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
    return pretty.isEmpty ? '(none — customer only described the problem)' : pretty;
  }

  Widget _top(Dispute d, {required bool canResolve}) {
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
