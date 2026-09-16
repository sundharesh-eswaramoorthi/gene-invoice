import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../shared/models/dispute.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../../core/table/table_providers.dart';
import 'disputes_providers.dart';

class DisputeDetailScreen extends ConsumerWidget {
  final int id;
  const DisputeDetailScreen({super.key, required this.id});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(disputeDetailProvider(id));
    return Scaffold(
      appBar: AppBar(title: Text('Dispute #$id')),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        // A dispute that is gone, or not this user's to see, reads as a sentence rather than a
        // raw exception (D-54).
        error: (e, _) => RecordUnavailable(
          message: notFoundMessage(e, 'dispute'),
          onBack: () => context.go('/disputes'),
        ),
        data: (d) => _DisputeBody(dispute: d),
      ),
    );
  }
}

class _DisputeBody extends ConsumerStatefulWidget {
  final Dispute dispute;
  const _DisputeBody({required this.dispute});

  @override
  ConsumerState<_DisputeBody> createState() => _DisputeBodyState();
}

class _DisputeBodyState extends ConsumerState<_DisputeBody> {
  late final TextEditingController _appliedCtrl;
  late final TextEditingController _adminNotesCtrl;
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
      if (mounted) context.go('/disputes');
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.isAdmin ?? false;
    final d = widget.dispute;
    final theme = Theme.of(context);

    final details = <Widget>[
      Row(
        children: [
          Expanded(child: Text(disputeTargetText(d), style: theme.textTheme.titleLarge)),
          DisputeStatusChip(status: d.status),
        ],
      ),
      const SizedBox(height: 4),
      Text(
        'Opened ${formatDateTime(d.createdAt)}'
        '${d.customerName != null ? " by ${d.customerName}" : ""}'
        '${d.resolvedAt != null ? " • resolved ${formatDateTime(d.resolvedAt)}" : ""}',
        style: theme.textTheme.bodySmall,
      ),
      const SizedBox(height: 12),
      DetailGrid(items: [
        DetailGridItem(label: 'Reason', child: Text(d.reason)),
        DetailGridItem(
          label: 'Proposed change',
          child: SelectableText(
            _prettyJson(d.proposedChangeJson).isEmpty
                ? '(none — customer only described the problem)'
                : _prettyJson(d.proposedChangeJson),
            style: const TextStyle(fontFamily: 'monospace'),
          ),
        ),
        if (d.adminNotes != null && d.adminNotes!.isNotEmpty)
          DetailGridItem(label: 'Admin notes', child: Text(d.adminNotes!)),
      ]),
      if (canManage && d.status == DisputeStatus.PENDING) ...[
        const Divider(height: 32),
        Text('Resolve', style: theme.textTheme.titleMedium),
        const SizedBox(height: 4),
        const Text(
          'Edit the JSON to fine-tune what gets applied on approve. '
          "Leave it blank to use the customer's proposed change.",
          style: TextStyle(fontStyle: FontStyle.italic, fontSize: 12),
        ),
        const SizedBox(height: 8),
        DetailGrid(items: [
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
    ];

    final historyTitle =
        Text('${disputeTargetLabel(d.targetType)} history', style: theme.textTheme.titleMedium);
    final history = AuditHistoryPanel(entityType: d.targetType.name, entityId: d.targetId);

    return LayoutBuilder(builder: (context, constraints) {
      // Narrow: one scroll, as before.
      if (constraints.maxWidth < 1000) {
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ...details,
              const Divider(height: 32),
              historyTitle,
              const SizedBox(height: 8),
              history,
            ],
          ),
        );
      }
      // Wide: the dispute and its resolution on the left, the record's history in its own column
      // on the right, so neither pushes the other down the page.
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 3,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: details),
            ),
          ),
          const VerticalDivider(width: 1),
          Expanded(
            flex: 2,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [historyTitle, const SizedBox(height: 8), history],
              ),
            ),
          ),
        ],
      );
    });
  }
}
