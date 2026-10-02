import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import '../email/email_entity.dart';
import '../email/email_providers.dart';
import 'automation_models.dart';
import 'automation_providers.dart';
import 'rules_screen.dart';
import 'run_detail_dialog.dart';

/// The most records one run by hand may name (§5.10.9).
const maxRunRecords = 50;

/// How far ahead a run may be booked (§5.10.9).
const maxRunAhead = Duration(days: 90);

/// "Run now" (A5): over everything the rule's condition matches or over records picked here, now
/// or at a chosen time, in the regions currently in view. The run acts as the rule's owner — so a
/// non-owner administrator is asked first whether to take it over (§5.10.7). Once queued, the
/// run can be opened (§7.7.3); [rule], when known, names its actions there. Answers the run it
/// queued, or null when nothing was.
Future<QueuedRun?> showRunNowDialog(
  BuildContext context, {
  required int ruleId,
  required String ruleName,
  required TriggerEntity entity,
  required bool takesOwnership,
  String? ownerName,
  Rule? rule,
}) {
  return showDialog<QueuedRun>(
    context: context,
    builder: (_) => _RunNowDialog(
      ruleId: ruleId,
      ruleName: ruleName,
      entity: entity,
      takesOwnership: takesOwnership,
      ownerName: ownerName,
      rule: rule,
    ),
  );
}

class _RunNowDialog extends ConsumerStatefulWidget {
  final int ruleId;
  final String ruleName;
  final TriggerEntity entity;
  final bool takesOwnership;
  final String? ownerName;
  final Rule? rule;

  const _RunNowDialog({
    required this.ruleId,
    required this.ruleName,
    required this.entity,
    required this.takesOwnership,
    this.ownerName,
    this.rule,
  });

  @override
  ConsumerState<_RunNowDialog> createState() => _RunNowDialogState();
}

class _RunNowDialogState extends ConsumerState<_RunNowDialog> {
  bool _chosen = false;
  final List<Map<String, dynamic>> _records = [];
  bool _later = false;
  DateTime? _at;
  bool _sending = false;
  String? _recordsError;
  String? _atError;
  String? _error;
  QueuedRun? _queued;

  EmailEntityType get _type => widget.entity.emailType;

  Future<void> _pickAt() async {
    final now = DateTime.now();
    final initial = _at ?? now.add(const Duration(hours: 1));
    final day = await showDatePicker(
      context: context,
      initialDate: DateTime(initial.year, initial.month, initial.day),
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: now.add(maxRunAhead),
    );
    if (day == null || !mounted) return;
    final time =
        await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(initial));
    if (time == null || !mounted) return;
    setState(() {
      _at = DateTime(day.year, day.month, day.day, time.hour, time.minute);
      _atError = null;
    });
  }

  bool _validate() {
    String? recordsError;
    String? atError;
    if (_chosen && _records.isEmpty) recordsError = 'Pick at least one record';
    if (_later) {
      final now = DateTime.now();
      if (_at == null) {
        atError = 'Pick when it should run';
      } else if (!_at!.isAfter(now)) {
        atError = 'Pick a time that has not passed yet';
      } else if (_at!.isAfter(now.add(maxRunAhead))) {
        atError = 'A run can be booked at most 90 days ahead';
      }
    }
    setState(() {
      _recordsError = recordsError;
      _atError = atError;
    });
    return recordsError == null && atError == null;
  }

  Future<void> _run() async {
    if (!_validate()) return;
    setState(() => _error = null);
    final dio = ref.read(dioProvider);
    final container = ProviderScope.containerOf(context, listen: false);
    try {
      final queued = await actTakingOwnership<QueuedRun>(
        context,
        takesOwnership: widget.takesOwnership,
        ownerName: widget.ownerName,
        // Busy only while it is on its way, not while the user is being asked.
        send: (confirm) async {
          setState(() => _sending = true);
          try {
            return await runRuleNow(dio, widget.ruleId,
                recordIds: _chosen ? [for (final r in _records) (r['id'] as num).toInt()] : null,
                runAt: _later ? _at : null,
                confirmOwnershipChange: confirm);
          } finally {
            if (mounted) setState(() => _sending = false);
          }
        },
      );
      if (!mounted || queued == null) return;
      rulesChanged(container, id: widget.ruleId);
      setState(() => _queued = queued);
    } catch (e) {
      if (!mounted) return;
      final fields = ruleFieldErrors(e);
      setState(() {
        _recordsError = fields['recordIds'];
        _atError = fields['runAt'];
        _error = _recordsError == null && _atError == null ? apiErrorMessage(e) : null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final queued = _queued;
    return AlertDialog(
      title: Text('Run "${widget.ruleName}"'),
      content: SizedBox(
        width: 480,
        child:
            SingleChildScrollView(child: queued == null ? _form(context) : _done(context, queued)),
      ),
      actions: queued == null
          ? [
              TextButton(
                onPressed: _sending ? null : () => Navigator.of(context).pop(),
                child: const Text('Cancel'),
              ),
              FilledButton(
                key: const ValueKey('run-now-submit'),
                onPressed: _sending ? null : _run,
                child: _sending
                    ? const SizedBox(
                        width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(_later ? 'Book run' : 'Run now'),
              ),
            ]
          : [
              TextButton(
                key: const ValueKey('run-now-open-rule'),
                onPressed: () {
                  final router = GoRouter.of(context);
                  Navigator.of(context).pop(queued);
                  router.go('/automation/${widget.ruleId}?tab=runs');
                },
                child: const Text('Open rule'),
              ),
              TextButton(
                onPressed: () => Navigator.of(context).pop(queued),
                child: const Text('Close'),
              ),
              FilledButton(
                key: const ValueKey('run-now-open-run'),
                onPressed: () {
                  // The run opens over the page this dialog was opened from.
                  final navigator = Navigator.of(context);
                  navigator.pop(queued);
                  showRunDetailDialog(navigator.context, runId: queued.runId, rule: widget.rule);
                },
                child: const Text('Open run'),
              ),
            ],
    );
  }

  Widget _form(BuildContext context) {
    final theme = Theme.of(context);
    final error = TextStyle(color: theme.colorScheme.error);
    final dio = ref.watch(dioProvider);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Which records', style: theme.textTheme.labelLarge),
        const SizedBox(height: 6),
        SegmentedButton<bool>(
          key: const ValueKey('run-which'),
          showSelectedIcon: false,
          segments: [
            ButtonSegment(
                value: false,
                label: Text('Every ${widget.entity.label.toLowerCase()} that matches'),
                icon: const Icon(Icons.filter_alt_outlined)),
            const ButtonSegment(
                value: true, label: Text('Chosen records'), icon: Icon(Icons.checklist)),
          ],
          selected: {_chosen},
          onSelectionChanged: _sending ? null : (s) => setState(() => _chosen = s.first),
        ),
        const SizedBox(height: 8),
        if (_chosen) ...[
          Text('Up to $maxRunRecords ${widget.entity.plural}', style: theme.textTheme.bodySmall),
          const SizedBox(height: 6),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final r in _records)
                InputChip(
                  key: ValueKey('run-record-${r['id']}'),
                  label: Text(_type.recordLabel(r)),
                  onDeleted: _sending ? null : () => setState(() => _records.remove(r)),
                ),
            ],
          ),
          if (_records.length < maxRunRecords)
            SearchPickerField<Map<String, dynamic>>(
              key: const ValueKey('run-record-picker'),
              label: 'Add ${_type.noun}',
              value: null,
              labelOf: _type.recordLabel,
              subtitleOf: _type.recordSubtitle,
              search: (q) => searchEmailRecords(dio, _type, q),
              errorText: _recordsError,
              onChanged: (r) => setState(() {
                if (!_records.any((x) => x['id'] == r['id'])) _records.add(r);
                _recordsError = null;
              }),
            )
          else if (_recordsError != null)
            Text(_recordsError!, style: error),
        ],
        const Divider(height: 24),
        Text('When', style: theme.textTheme.labelLarge),
        const SizedBox(height: 6),
        SegmentedButton<bool>(
          key: const ValueKey('run-when'),
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: false, label: Text('Now'), icon: Icon(Icons.play_arrow_outlined)),
            ButtonSegment(value: true, label: Text('At…'), icon: Icon(Icons.schedule)),
          ],
          selected: {_later},
          onSelectionChanged: _sending ? null : (s) => setState(() => _later = s.first),
        ),
        const SizedBox(height: 8),
        if (_later)
          InkWell(
            key: const ValueKey('run-at-pick'),
            onTap: _sending ? null : _pickAt,
            child: InputDecorator(
              decoration: InputDecoration(
                labelText: 'Run at',
                errorText: _atError,
                suffixIcon: const Icon(Icons.schedule, size: 18),
              ),
              child: Text(_at == null
                  ? 'Pick a date and time'
                  : DateFormat('d MMM yyyy, h:mm a').format(_at!)),
            ),
          ),
        const SizedBox(height: 12),
        Text('In: ${_regionsInView(ref)}',
            key: const ValueKey('run-regions'), style: theme.textTheme.bodySmall),
        if (widget.takesOwnership)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              '${widget.ownerName ?? 'Someone else'} owns this rule. Running it makes you its owner.',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.tertiary),
            ),
          ),
        if (_error != null)
          Padding(padding: const EdgeInsets.only(top: 8), child: Text(_error!, style: error)),
      ],
    );
  }

  Widget _done(BuildContext context, QueuedRun queued) {
    final theme = Theme.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text('Run #${queued.runId}', style: theme.textTheme.titleMedium),
            const SizedBox(width: 8),
            StatusChip(label: runStatusLabel(queued.status), color: theme.colorScheme.primary),
          ],
        ),
        const SizedBox(height: 8),
        Text(queued.scheduledFor == null
            ? 'It will run shortly, as the rule\'s owner.'
            : 'It will run from ${formatDateTime(queued.scheduledFor)}, as the rule\'s owner.'),
      ],
    );
  }

  /// "SOUTH, WEST", or every region the user holds: what the run is kept to (§5.10.9).
  String _regionsInView(WidgetRef ref) {
    final asked = ref.watch(viewScopeProvider).regionIds;
    final regions = ref.watch(currentUserProvider)?.regions ?? const [];
    if (asked == null) return regions.length == 1 ? regions.single.code : 'All your regions';
    final codes = [
      for (final r in regions)
        if (asked.contains(r.id)) r.code,
    ];
    return codes.isEmpty ? 'All your regions' : codes.join(', ');
  }
}
