import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/pending_reply.dart';
import '../../core/format.dart';
import '../../core/scope/view_scope.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import 'approval_models.dart';
import 'pending_banner.dart';

/// The longest cumulative window the server accepts, in hours (§6.11).
const thresholdWindowMaxHours = 720;

/// The regions whose limits [user] may see: where they approve changes or manage the limits.
List<RegionAccess> thresholdRegionsFor(CurrentUser? user) => [
      for (final r in user?.regions ?? const <RegionAccess>[])
        if (user!.hasIn(Privileges.approvalThresholdManage, r.id) ||
            user.hasIn(Privileges.changeApprove, r.id))
          r,
    ];

/// A region's approval limits (B2): above which amount each money change waits for a second
/// person, and over how many hours one person's changes to a customer count together. Lowering
/// a limit applies at once; raising or removing one waits for another approver (§5.11.11).
class ApprovalThresholdsScreen extends ConsumerStatefulWidget {
  final int? regionId;
  const ApprovalThresholdsScreen({super.key, this.regionId});

  @override
  ConsumerState<ApprovalThresholdsScreen> createState() => _ApprovalThresholdsScreenState();
}

class _ThresholdDraft {
  final ThresholdRow row;
  final TextEditingController amount;
  final TextEditingController hours;
  _ThresholdDraft(this.row)
      : amount =
            TextEditingController(text: row.amount == null ? '' : row.amount!.toStringAsFixed(2)),
        hours = TextEditingController(text: row.windowHours?.toString() ?? '');

  void dispose() {
    amount.dispose();
    hours.dispose();
  }
}

class _ApprovalThresholdsScreenState extends ConsumerState<ApprovalThresholdsScreen> {
  int? _regionId;
  Object? _loadError;

  /// The limits as the server last answered them; the drafts are edited against these.
  RegionThresholds? _loaded;
  List<_ThresholdDraft> _drafts = const [];
  List<String> _warnings = const [];
  final _allHours = TextEditingController();
  Map<String, String> _fieldErrors = const {};
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final regions = thresholdRegionsFor(ref.read(currentUserProvider));
    _regionId = regions.any((r) => r.id == widget.regionId)
        ? widget.regionId
        : (regions.isEmpty ? null : regions.first.id);
    if (_regionId != null) _load(_regionId!);
  }

  // The limits are configuration: they answer live whatever the date viewed (D9).
  Future<void> _load(int regionId) async {
    try {
      final res = await ref.read(dioProvider).get('/api/regions/$regionId/approval-thresholds');
      final t = RegionThresholds.fromJson((res.data as Map).cast<String, dynamic>());
      if (!mounted || regionId != _regionId) return;
      setState(() => _seed(t));
    } catch (e) {
      if (!mounted || regionId != _regionId) return;
      setState(() => _loadError = e);
    }
  }

  @override
  void dispose() {
    for (final d in _drafts) {
      d.dispose();
    }
    _allHours.dispose();
    super.dispose();
  }

  void _seed(RegionThresholds t, {List<String> warnings = const []}) {
    // The fields still hold the old controllers until the next frame is built.
    final old = _drafts;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      for (final d in old) {
        d.dispose();
      }
    });
    _loaded = t;
    _loadError = null;
    _drafts = [for (final r in t.thresholds) _ThresholdDraft(r)];
    _warnings = warnings;
    _fieldErrors = const {};
  }

  void _selectRegion(int id) {
    if (id == _regionId) return;
    setState(() {
      _regionId = id;
      _loaded = null;
      _loadError = null;
      _warnings = const [];
      _fieldErrors = const {};
    });
    _load(id);
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final regions = thresholdRegionsFor(user);
    final regionId = _regionId;

    if (regionId == null) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text('You do not approve changes or manage approval limits in any region.',
              textAlign: TextAlign.center),
        ),
      );
    }

    // A past date is read only (D2); the limits themselves always answer live.
    final historical = ref.watch(isHistoricalProvider);
    final loaded = _loaded;
    final canEdit = !historical &&
        loaded != null &&
        loaded.regionId == regionId &&
        loaded.canEdit &&
        (user?.hasIn(Privileges.approvalThresholdManage, regionId) ?? false);

    return Scaffold(
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          Row(
            children: [
              Expanded(
                child:
                    Text('Approval thresholds', style: Theme.of(context).textTheme.headlineSmall),
              ),
              if (canEdit)
                FilledButton.icon(
                  key: const ValueKey('thresholds-save'),
                  icon: const Icon(Icons.save_outlined, size: 18),
                  label: const Text('Save'),
                  onPressed: _saving ? null : () => _save(regionId),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerLeft,
            child: SizedBox(
              width: 320,
              child: DropdownButtonFormField<int>(
                key: const ValueKey('thresholds-region'),
                initialValue: regionId,
                decoration: const InputDecoration(labelText: 'Region', isDense: true),
                items: [
                  for (final r in regions)
                    DropdownMenuItem(value: r.id, child: Text('${r.code} — ${r.name}')),
                ],
                // Not while a save is on its way: its answer belongs to the region it was for.
                onChanged: _saving
                    ? null
                    : (id) {
                        if (id != null) _selectRegion(id);
                      },
              ),
            ),
          ),
          const SizedBox(height: 12),
          if (_loadError != null)
            Text(apiErrorMessage(_loadError!))
          else if (loaded == null || loaded.regionId != regionId)
            const Padding(
              padding: EdgeInsets.all(12),
              child: LinearProgressIndicator(),
            )
          else
            ..._body(loaded, canEdit: canEdit, historical: historical),
        ],
      ),
    );
  }

  List<Widget> _body(RegionThresholds t, {required bool canEdit, required bool historical}) {
    final theme = Theme.of(context);
    final region = t.regionName ?? 'this region';
    return [
      if (t.pendingChange != null)
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: PendingBriefBanner(change: t.pendingChange!),
        ),
      for (final w in _warnings)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Row(
            children: [
              Icon(Icons.warning_amber_outlined, size: 18, color: Colors.orange.shade800),
              const SizedBox(width: 8),
              Expanded(child: Text(w, style: TextStyle(color: Colors.orange.shade800))),
            ],
          ),
        ),
      Text(
        [
          'A change above its limit waits until someone else who approves changes in $region '
              'approves it. An empty limit means the change never waits.',
          if (t.activeApprovers != null)
            '${t.activeApprovers} ${t.activeApprovers == 1 ? 'person approves' : 'people approve'} '
                'changes here.',
        ].join(' '),
        style: theme.textTheme.bodySmall,
      ),
      if (historical)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text('These are the current limits; they can be changed only in the current view.',
              style: theme.textTheme.bodySmall?.copyWith(fontStyle: FontStyle.italic)),
        ),
      const SizedBox(height: 12),
      if (canEdit)
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            const Text('Counts changes over'),
            SizedBox(
              width: 90,
              child: TextField(
                key: const ValueKey('thresholds-all-hours'),
                controller: _allHours,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(isDense: true, suffixText: 'h'),
              ),
            ),
            OutlinedButton(
              key: const ValueKey('thresholds-apply-all'),
              onPressed: () => setState(() {
                for (final d in _drafts) {
                  d.hours.text = _allHours.text.trim();
                }
              }),
              child: const Text('Apply to all'),
            ),
          ],
        ),
      const SizedBox(height: 8),
      for (var i = 0; i < _drafts.length; i++) _row(_drafts[i], canEdit: canEdit),
    ];
  }

  Widget _row(_ThresholdDraft d, {required bool canEdit}) {
    final theme = Theme.of(context);
    final op = d.row.operation;
    final updated = [
      if (d.row.updatedBy != null) 'by ${d.row.updatedBy!.name}',
      if (d.row.updatedAt != null) 'on ${formatDateTime(d.row.updatedAt)}',
    ];
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Wrap(
          spacing: 16,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 260,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(d.row.label, style: theme.textTheme.titleSmall),
                  if (d.row.measure != null) Text(d.row.measure!, style: theme.textTheme.bodySmall),
                  if (updated.isNotEmpty)
                    Text('Changed ${updated.join(' ')}', style: theme.textTheme.bodySmall),
                ],
              ),
            ),
            SizedBox(
              width: 200,
              child: TextField(
                key: ValueKey('threshold-amount-$op'),
                controller: d.amount,
                enabled: canEdit,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                  labelText: 'Limit',
                  prefixText: '₹ ',
                  hintText: 'Never waits',
                  isDense: true,
                  errorText: _fieldErrors['$op.amount'],
                ),
              ),
            ),
            SizedBox(
              width: 200,
              child: TextField(
                key: ValueKey('threshold-hours-$op'),
                controller: d.hours,
                enabled: canEdit,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(
                  labelText: 'Counts changes over',
                  suffixText: 'hours',
                  helperText: '0 = each change alone',
                  isDense: true,
                  errorText: _fieldErrors['$op.windowHours'],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _save(int regionId) async {
    final loaded = _loaded;
    if (loaded == null) return;
    final messenger = ScaffoldMessenger.of(context);
    final errors = <String, String>{};
    final rows = <Map<String, dynamic>>[];
    for (final d in _drafts) {
      final op = d.row.operation;
      final amountText = d.amount.text.trim().replaceAll(',', '');
      final hoursText = d.hours.text.trim();
      double? amount;
      if (amountText.isNotEmpty) {
        amount = parseMoneyInput(amountText);
        if (amount == null) {
          errors['$op.amount'] = 'Enter an amount such as 100000 or 2500.50';
          continue;
        }
      }
      int? hours;
      if (hoursText.isNotEmpty) {
        hours = int.tryParse(hoursText);
        if (hours == null || hours < 0 || hours > thresholdWindowMaxHours) {
          errors['$op.windowHours'] = 'From 0 to $thresholdWindowMaxHours hours';
          continue;
        }
      }
      final amountChanged = amount != d.row.amount;
      final hoursChanged = amount != null && hours != null && hours != d.row.windowHours;
      if (!amountChanged && !hoursChanged) continue;
      // Only what changed is sent; a row left out stays as it is, and no amount removes it.
      rows.add({
        'operation': op,
        'amount': amount,
        if (amount != null && hours != null) 'windowHours': hours,
      });
    }
    if (errors.isNotEmpty) {
      setState(() => _fieldErrors = errors);
      return;
    }
    if (rows.isEmpty) {
      setState(() => _fieldErrors = const {});
      messenger.showSnackBar(const SnackBar(content: Text('Nothing has changed')));
      return;
    }
    setState(() {
      _saving = true;
      _fieldErrors = const {};
    });
    try {
      final res = await ref
          .read(dioProvider)
          .put('/api/regions/$regionId/approval-thresholds', data: {'thresholds': rows});
      final saved = RegionThresholds.fromJson((res.data as Map).cast<String, dynamic>());
      if (!mounted || saved.regionId != _regionId) return;
      setState(() => _seed(saved, warnings: saved.warnings));
      final held = pendingFrom(res);
      if (held != null) {
        // The tightening part (if any) is saved; the rest waits for a second approver (§5.11.11).
        // The change is named in the body, and failing that in the Location header.
        showSentForApproval(context, held,
            message: 'Raising or removing limits needs a second approver');
        invalidateApprovals(ref);
      } else {
        messenger.showSnackBar(const SnackBar(content: Text('Approval thresholds saved')));
      }
    } catch (e) {
      final fields = _fieldErrorsOf(e, rows);
      if (!mounted) return;
      setState(() => _fieldErrors = fields);
      if (fields.isEmpty) messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// The server names a field by its place in what was sent: `thresholds[2].amount`.
  static Map<String, String> _fieldErrorsOf(Object error, List<Map<String, dynamic>> sent) {
    final data = error is DioException ? error.response?.data : null;
    final fields = data is Map ? data['fieldErrors'] : null;
    if (fields is! Map) return const {};
    final out = <String, String>{};
    final pattern = RegExp(r'^thresholds\[(\d+)\]\.(\w+)$');
    for (final e in fields.entries) {
      final m = pattern.firstMatch('${e.key}');
      final i = m == null ? null : int.parse(m.group(1)!);
      if (i == null || i >= sent.length) continue;
      final field = m!.group(2) == 'operation' ? 'amount' : m.group(2)!;
      out['${sent[i]['operation']}.$field'] = '${e.value}';
    }
    return out;
  }
}
