import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/dispute.dart';
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
        error: (e, _) => Center(child: Text('Failed: $e')),
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
    final df = DateFormat.yMMMd().add_jm();

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  disputeTargetText(d),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              Chip(label: Text(disputeStatusLabel(d.status))),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            'Opened ${df.format(d.createdAt.toLocal())}'
            '${d.customerName != null ? " by ${d.customerName}" : ""}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 16),
          _Section(label: 'Reason', body: Text(d.reason)),
          const SizedBox(height: 12),
          _Section(
            label: 'Proposed change (JSON)',
            body: SelectableText(
              _prettyJson(d.proposedChangeJson).isEmpty
                  ? '(none — customer only described the problem)'
                  : _prettyJson(d.proposedChangeJson),
              style: const TextStyle(fontFamily: 'monospace'),
            ),
          ),
          if (d.adminNotes != null && d.adminNotes!.isNotEmpty) ...[
            const SizedBox(height: 12),
            _Section(label: 'Admin notes', body: Text(d.adminNotes!)),
          ],
          if (d.resolvedAt != null) ...[
            const SizedBox(height: 12),
            Text('Resolved ${df.format(d.resolvedAt!.toLocal())}',
                style: Theme.of(context).textTheme.bodySmall),
          ],
          const SizedBox(height: 24),
          if (canManage && d.status == DisputeStatus.PENDING) ...[
            const Divider(),
            Text('Resolve', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const Text(
              'Edit the JSON below to fine-tune what gets applied on approve. '
              'Leave blank to use the customer\'s proposed change.',
              style: TextStyle(fontStyle: FontStyle.italic),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _appliedCtrl,
              maxLines: 8,
              style: const TextStyle(fontFamily: 'monospace'),
              decoration: const InputDecoration(
                labelText: 'Applied change (JSON)',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _adminNotesCtrl,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Admin notes (shown to customer)',
                border: OutlineInputBorder(),
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
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
          const SizedBox(height: 24),
          const Divider(),
          Text('${disputeTargetLabel(d.targetType)} history',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          AuditHistoryPanel(entityType: d.targetType.name, entityId: d.targetId),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  final String label;
  final Widget body;
  const _Section({required this.label, required this.body});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        body,
      ],
    );
  }
}
