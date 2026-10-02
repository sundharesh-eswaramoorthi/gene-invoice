import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/record_assignee.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/search_picker_field.dart';
import '../../shared/widgets/status_chip.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_models.dart';
import '../email/email_providers.dart';
import 'action_cards.dart';
import 'automation_models.dart';
import 'automation_providers.dart';
import 'condition_builder.dart';
import 'rule_runs_tab.dart';
import 'rules_screen.dart';
import 'run_now_dialog.dart';

/// Everything the editor sends as a rule (§6.14 `RuleRequest`), with its text boxes.
class _RuleDraft {
  final name = TextEditingController();
  final description = TextEditingController();
  final maxRecords = TextEditingController(text: '1000');
  bool enabled = true;
  TriggerKind kind = TriggerKind.ENTITY_CREATED;
  TriggerEntity entity = TriggerEntity.INVOICE;
  String time = '09:00';
  String day = weekdays.first;
  String zone = deviceZone();
  String catchUp = 'ONE';
  Set<int> regionIds = {};
  ConditionGroup condition = ConditionGroup.empty;
  List<RuleAction> actions = const [];
  int? version;

  _RuleDraft();

  _RuleDraft.of(Rule rule) {
    seed(rule);
  }

  void seed(Rule r) {
    name.text = r.name;
    description.text = r.description ?? '';
    maxRecords.text = '${r.maxRecordsPerRun}';
    enabled = r.enabled;
    kind = r.trigger.kind;
    entity = r.trigger.entity;
    time = r.trigger.time ?? '09:00';
    day = r.trigger.day ?? weekdays.first;
    zone = r.trigger.zone ?? deviceZone();
    catchUp = r.trigger.catchUp ?? 'ONE';
    regionIds = {...r.regionIds};
    condition = r.condition;
    actions = [for (final a in r.actions) a.withKey()];
    version = r.version;
  }

  RuleTrigger get trigger => RuleTrigger(
        kind: kind,
        entity: entity,
        time: time,
        day: day,
        zone: zone,
        catchUp: catchUp,
      );

  /// The request, every key always present so it has one shape (§6.14).
  Map<String, dynamic> toRequest({bool confirmOwnershipChange = false}) => {
        'name': name.text.trim(),
        'description': optionalText(description.text),
        'enabled': enabled,
        'trigger': trigger.toJson(),
        'regionIds': regionIds.toList()..sort(),
        'condition': condition.toRequest(),
        'actions': [for (final a in actions) a.toJson()],
        'maxRecordsPerRun': int.tryParse(maxRecords.text.trim()),
        'version': version,
        'confirmOwnershipChange': confirmOwnershipChange,
      };

  void dispose() {
    name.dispose();
    description.dispose();
    maxRecords.dispose();
  }
}

/// A rule (A1-A5), new (`/automation/new`) or saved (`/automation/:id`): what starts it, which
/// regions it works in, which records it acts on, and a preview of what it would match. A saved
/// rule also has its history, and runs, switches and deletes from its header. A past date shows
/// the rule as it was then, read only; Preview still works, as of that date (D10).
class RuleEditorScreen extends ConsumerStatefulWidget {
  final int? id;
  final String? initialTab;
  const RuleEditorScreen({super.key, this.id, this.initialTab});

  @override
  ConsumerState<RuleEditorScreen> createState() => _RuleEditorScreenState();
}

class _RuleEditorScreenState extends ConsumerState<RuleEditorScreen> {
  final _draft = _RuleDraft();

  /// A past date's revision, shown read only; built afresh for each one loaded.
  _RuleDraft? _past;
  Rule? _pastOf;

  bool _loadedInto = false;

  /// Moves on whenever the fields are filled afresh, so the dropdowns take the new values too.
  int _generation = 0;
  bool _dirty = false;
  bool _saving = false;
  bool _busy = false;
  Map<String, String> _fieldErrors = const {};
  String? _error;

  Map<String, dynamic>? _previewRecord;
  bool _asOwner = false;
  bool _previewing = false;
  RulePreview? _preview;
  String? _previewError;

  /// Names of the people the actions name, which their tokens do not carry: the saved rule's, as
  /// the server gives them, and those picked on this page.
  final Map<int, String> _people = {};

  late final UnsavedChanges _unsaved;

  @override
  void initState() {
    super.initState();
    // One slot for the whole page (G17): nothing this page opens registers one of its own.
    _unsaved = ref.read(unsavedChangesProvider)..register(_confirmDiscard);
  }

  @override
  void dispose() {
    _unsaved.unregister(_confirmDiscard);
    _draft.dispose();
    _past?.dispose();
    super.dispose();
  }

  /// Only ever from a live rule (G18): a past date's values are not ones to edit from.
  void _seed(Rule rule) {
    if (_loadedInto) return;
    _loadedInto = true;
    _generation++;
    // Names picked here stay for the people the rule no longer names (an unsaved edit's, say).
    _people.addAll(rule.people);
    _draft.seed(rule);
    _clearPreview();
  }

  /// A preview answers for one draft on one day in one set of regions: any change and it is gone,
  /// never left to read as the answer for another (D13).
  void _clearPreview() {
    _preview = null;
    _previewError = null;
  }

  _RuleDraft _pastDraftFor(Rule rule) {
    if (_pastOf != rule) {
      _past?.dispose();
      _past = _RuleDraft.of(rule);
      _pastOf = rule;
    }
    return _past!;
  }

  void _changed(VoidCallback change) => setState(() {
        change();
        _dirty = true;
        _clearPreview();
      });

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Discard unsaved changes?'),
        content: const Text('You have edits on this rule that have not been saved.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep editing')),
          FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true), child: const Text('Discard')),
        ],
      ),
    );
    if (ok == true) _dirty = false;
    return ok == true;
  }

  /// Another kind of record has other filters, so the conditions go; and the actions keep only
  /// the placeholders, people and choices it has too — after asking, when anything would go.
  Future<void> _entityChanged(TriggerEntity next) async {
    if (next == _draft.entity) return;
    final adapted = [for (final a in _draft.actions) actionFor(a, next)];
    final actionsChange = [
      for (final (i, a) in adapted.indexed)
        if (!identical(a, _draft.actions[i])) a,
    ].isNotEmpty;
    if (!_draft.condition.isEmpty || actionsChange) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text('Change to ${next.plural}?'),
          content: Text([
            if (!_draft.condition.isEmpty)
              'The conditions are about ${_draft.entity.plural}, so they will be removed.',
            if (actionsChange)
              'Placeholders, people and choices in the actions that only '
                  '${_draft.entity.plural} have will be taken out.',
          ].join('\n\n')),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: const Text('Cancel')),
            FilledButton(
                key: const ValueKey('rule-entity-confirm'),
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: const Text('Change')),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    _changed(() {
      _draft.entity = next;
      _draft.condition = ConditionGroup.empty;
      _draft.actions = adapted;
      _previewRecord = null;
    });
  }

  Future<void> _pickTime() async {
    final parts = _draft.time.split(':');
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(
          hour: int.tryParse(parts.first) ?? 9,
          minute: int.tryParse(parts.length > 1 ? parts[1] : '') ?? 0),
    );
    if (picked == null) return;
    String two(int n) => n.toString().padLeft(2, '0');
    _changed(() => _draft.time = '${two(picked.hour)}:${two(picked.minute)}');
  }

  Map<String, String> _validate() {
    final errors = <String, String>{};
    final name = _draft.name.text.trim();
    if (name.isEmpty) errors['name'] = 'Give the rule a name';
    final max = int.tryParse(_draft.maxRecords.text.trim());
    if (max == null || max < 1 || max > 5000) {
      errors['maxRecordsPerRun'] = 'Enter a number from 1 to 5,000';
    }
    return errors;
  }

  Future<void> _save(Rule? rule) async {
    if (ref.read(isHistoricalProvider)) return;
    final local = _validate();
    setState(() {
      _fieldErrors = local;
      _error = null;
    });
    if (local.isNotEmpty) return;
    final dio = ref.read(dioProvider);
    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    final router = GoRouter.of(context);
    try {
      final saved = await actTakingOwnership<Rule>(
        context,
        takesOwnership:
            rule != null && (rule.editTakesOwnership ?? false) && !(rule.canEdit ?? false),
        ownerName: rule?.owner?.name,
        // Busy only while it is on its way, not while the user is being asked.
        send: (confirm) async {
          setState(() => _saving = true);
          try {
            final body = _draft.toRequest(confirmOwnershipChange: confirm);
            final Response res = rule == null
                ? await dio.post('/api/automation/rules', data: body)
                : await dio.put('/api/automation/rules/${rule.id}', data: body);
            return Rule.fromJson((res.data as Map).cast<String, dynamic>());
          } finally {
            if (mounted) setState(() => _saving = false);
          }
        },
      );
      if (saved == null) return;
      _dirty = false;
      rulesChanged(container, id: saved.id);
      messenger.showSnackBar(SnackBar(content: Text(rule == null ? 'Rule created' : 'Rule saved')));
      if (rule == null) {
        router.go('/automation/${saved.id}');
        return;
      }
      if (!mounted) return;
      setState(() {
        _loadedInto = false;
        _seed(saved);
      });
    } catch (e) {
      if (!mounted) return;
      final fields = ruleFieldErrors(e);
      final stale = e is DioException && e.response?.statusCode == 409;
      // Their version, for Undo to seed from; the fields keep the user's edits until then.
      if (stale && rule != null) ref.invalidate(ruleDetailProvider(rule.id));
      setState(() {
        _fieldErrors = fields;
        _error = stale
            ? 'Someone else changed this rule while you were editing it. Undo your changes to see '
                'theirs, then make yours again.'
            : fields.isEmpty
                ? apiErrorMessage(e)
                : _unplaced(fields);
      });
    }
  }

  /// Field errors that no section shows under itself.
  String? _unplaced(Map<String, String> fields) {
    final rest = [
      for (final e in fields.entries)
        if (!_placed(e.key)) e.value,
    ];
    return rest.isEmpty ? null : rest.join('\n');
  }

  static bool _placed(String key) =>
      key == 'name' ||
      key == 'description' ||
      key == 'maxRecordsPerRun' ||
      key == 'regionIds' ||
      key.startsWith('trigger') ||
      key.startsWith('condition') ||
      key.startsWith('actions');

  String? _errorsWhere(bool Function(String key) test) {
    final found = [
      for (final e in _fieldErrors.entries)
        if (test(e.key)) e.value,
    ];
    return found.isEmpty ? null : found.join('\n');
  }

  Future<void> _runPreview(_RuleDraft d, Rule? rule) async {
    setState(() {
      _previewing = true;
      _previewError = null;
    });
    try {
      final res = await ref.read(dioProvider).post('/api/automation/rules/preview', data: {
        'rule': d.toRequest(),
        if (rule != null) 'ruleId': rule.id,
        if (_previewRecord != null) 'recordId': (_previewRecord!['id'] as num).toInt(),
        'sampleSize': 10,
        if (_asOwner) 'evaluateAs': 'OWNER',
      });
      // Asked of a past date, it must answer for that date (D13).
      checkAsOf(res, ref.read(viewAsOfProvider));
      if (!mounted) return;
      setState(() => _preview = RulePreview.fromJson((res.data as Map).cast<String, dynamic>()));
    } catch (e) {
      if (mounted) setState(() => _previewError = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _previewing = false);
    }
  }

  /// After a switch, run or resume from the header: the rule as it now is, into the fields too
  /// (they are unchanged, or the header would not have been on offer).
  Future<void> _afterAction(bool done) async {
    if (!done || !mounted) return;
    Rule? live;
    try {
      live = await ref.refresh(ruleDetailProvider(widget.id!).future);
    } catch (_) {
      // The page says why it cannot load.
    }
    if (!mounted) return;
    setState(() {
      _loadedInto = false;
      if (live != null && ref.read(viewAsOfProvider) == null) _seed(live);
    });
  }

  Future<void> _act(Future<bool> Function() action) async {
    setState(() => _busy = true);
    final done = await action();
    if (mounted) setState(() => _busy = false);
    await _afterAction(done);
  }

  @override
  Widget build(BuildContext context) {
    final historical = ref.watch(isHistoricalProvider);
    ref.listen(viewScopeProvider, (_, __) => setState(_clearPreview));
    final id = widget.id;
    if (id == null) return _buildNew(historical);

    final async = ref.watch(ruleDetailProvider(id));
    final asOf = ref.watch(viewAsOfProvider);
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'rule', asOf: asOf),
        onBack: () => context.go('/automation'),
      ),
      data: (rule) {
        if (asOf == null) _seed(rule);
        return _buildSaved(rule, historical);
      },
    );
  }

  Widget _buildNew(bool historical) {
    // A past date is read only (D2). The draft stays in this state, so it is all still here when
    // the user comes back to today.
    if (historical) return const _ReadOnlyNotice();
    return Scaffold(
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 860),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('New rule', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 16),
              _form(_draft, rule: null, editable: true),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSaved(Rule rule, bool historical) {
    final user = ref.watch(currentUserProvider);
    final rights = RuleRights.forRule(user, rule, historical: historical);
    final editable =
        !historical && ((rule.canEdit ?? rights.canAct) || (rule.editTakesOwnership ?? false));
    final d = historical ? _pastDraftFor(rule) : _draft;
    final canViewAudit = user?.has(Privileges.auditView) ?? false;
    final suspended = rule.status == RuleStatus.SUSPENDED;
    // Switching or running a rule with edits not yet saved would act on the saved one.
    final idle = !historical && !_busy && !_saving && !_dirty;
    const waitTip = 'Save or undo your changes first';

    Widget headerButton(String key, IconData icon, String label, VoidCallback? onPressed) =>
        Tooltip(
          message: !_dirty || historical ? '' : waitTip,
          child: OutlinedButton.icon(
            key: ValueKey(key),
            icon: Icon(icon, size: 18),
            label: Text(label),
            onPressed: idle ? onPressed : null,
          ),
        );

    return DetailScaffold(
      title: rule.name,
      subtitle: '${rule.trigger.entity.label} rule · ${rule.trigger.description}',
      onBack: () => goGuarded(context, '/automation'),
      titleTrailing: [
        RuleStatusChip(status: rule.status, suspendedReason: rule.suspendedReason),
        if (rights.canAct && !suspended)
          headerButton('rule-run', Icons.play_arrow_outlined, 'Run now', () async {
            final queued = await showRunNowDialog(context,
                ruleId: rule.id,
                ruleName: rule.name,
                entity: rule.trigger.entity,
                takesOwnership: rights.takesOwnership,
                ownerName: rule.owner?.name,
                rule: rule);
            await _afterAction(queued != null);
          }),
        if (rights.canAct && suspended)
          headerButton(
              'rule-resume',
              Icons.restart_alt,
              'Resume',
              () => _act(() => resumeRuleFlow(context,
                  id: rule.id,
                  name: rule.name,
                  suspendedReason: rule.suspendedReason,
                  takesOwnership: rights.takesOwnership,
                  ownerName: rule.owner?.name))),
        if (rights.canAct && !rule.enabled && !suspended)
          headerButton(
              'rule-enable',
              Icons.toggle_off_outlined,
              'Enable',
              () => _act(() => enableRuleFlow(context,
                  id: rule.id,
                  name: rule.name,
                  takesOwnership: rights.takesOwnership,
                  ownerName: rule.owner?.name))),
        if (rights.canStop && rule.enabled)
          headerButton('rule-disable', Icons.toggle_on_outlined, 'Disable',
              () => _act(() => disableRuleFlow(context, id: rule.id, name: rule.name))),
        if (rights.canStop)
          headerButton('rule-delete', Icons.delete_outline, 'Delete', () async {
            setState(() => _busy = true);
            final router = GoRouter.of(context);
            final gone = await deleteRuleFlow(context, id: rule.id, name: rule.name);
            if (mounted) setState(() => _busy = false);
            if (gone) router.go('/automation');
          }),
      ],
      initialTabSlug: widget.initialTab,
      onTabChanged: (slug) => context.go('/automation/${rule.id}?tab=$slug'),
      top: _top(rule, historical: historical),
      tabs: [
        DetailTab(
          slug: 'rule',
          label: 'Rule',
          icon: Icons.tune,
          builder: (context) => SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Align(
              alignment: Alignment.topLeft,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 860),
                child: _form(d, rule: rule, editable: editable),
              ),
            ),
          ),
        ),
        DetailTab(
          slug: 'runs',
          label: 'Runs',
          icon: Icons.playlist_play,
          builder: (context) => RuleRunsTab(rule: rule),
        ),
        if (canViewAudit)
          DetailTab(
            slug: 'history',
            label: 'History',
            icon: Icons.history,
            builder: (context) => SingleChildScrollView(
              padding: const EdgeInsets.all(12),
              child: AuditHistoryPanel(entityType: 'RULE', entityId: rule.id),
            ),
          ),
      ],
    );
  }

  Widget _top(Rule rule, {required bool historical}) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final last = rule.lastRun;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Runs as ${ownerText(rule.owner)}', key: const ValueKey('rule-owner')),
          if (!historical && (rule.editTakesOwnership ?? false) && !(rule.canEdit ?? false))
            Text('Saving, running, enabling or resuming it makes you its owner.',
                style: muted?.copyWith(color: theme.colorScheme.tertiary)),
          if (rule.status == RuleStatus.SUSPENDED)
            Text(
              [
                'Suspended: ${suspendReasonLabel(rule.suspendedReason)}',
                if (rule.suspendedDetail != null && rule.suspendedDetail!.isNotEmpty)
                  rule.suspendedDetail!,
              ].join(' — '),
              style: TextStyle(color: theme.colorScheme.error),
            ),
          const SizedBox(height: 4),
          Wrap(
            spacing: 16,
            runSpacing: 4,
            children: [
              if (rule.nextRunAt != null)
                Text('Next run ${formatDateTime(rule.nextRunAt)}', style: muted),
              if (last != null)
                Text(
                  'Last run #${last.id}: ${humanizeEnum(last.status)}'
                  '${last.finishedAt == null ? '' : ' · ${formatDateTime(last.finishedAt)}'}'
                  ' · ${last.matched} matched, ${last.done} done, ${last.skipped} skipped, '
                  '${last.failed} failed',
                  style: muted,
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _section(String title, List<Widget> children, {String? subtitle}) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: theme.textTheme.titleMedium),
          if (subtitle != null)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(subtitle,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ),
          const SizedBox(height: 8),
          ...children,
        ],
      ),
    );
  }

  Widget _form(_RuleDraft d, {required Rule? rule, required bool editable}) {
    final theme = Theme.of(context);
    final errorStyle = TextStyle(color: theme.colorScheme.error);
    final generalError = _error;
    return Column(
      key: ValueKey('rule-form-${identityHashCode(d)}-$_generation'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _basics(d, rule: rule, editable: editable),
        _trigger(d, editable: editable),
        _regions(d, editable: editable),
        _conditions(d, rule: rule, editable: editable),
        _actions(d, rule: rule, editable: editable),
        _previewSection(d, rule: rule),
        if (generalError != null)
          Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(generalError, style: errorStyle)),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                key: const ValueKey('rule-undo'),
                onPressed: _saving || !editable
                    ? null
                    : rule == null
                        ? () => goGuarded(context, '/automation')
                        : _dirty
                            ? () => setState(() {
                                  _dirty = false;
                                  _loadedInto = false;
                                  _fieldErrors = const {};
                                  _error = null;
                                  _seed(rule);
                                })
                            : null,
                child: Text(rule == null ? 'Cancel' : 'Undo changes'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FilledButton(
                key: const ValueKey('rule-save'),
                onPressed:
                    !editable || _saving || (rule != null && !_dirty) ? null : () => _save(rule),
                child: _saving
                    ? const SizedBox(
                        width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(rule == null ? 'Create rule' : 'Save changes'),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _basics(_RuleDraft d, {required Rule? rule, required bool editable}) {
    return _section('Basics', [
      TextField(
        key: const ValueKey('rule-name'),
        controller: d.name,
        enabled: editable,
        decoration: InputDecoration(labelText: 'Name *', errorText: _fieldErrors['name']),
        inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.ruleName)],
        onChanged: (_) => _changed(() => _fieldErrors = {..._fieldErrors}..remove('name')),
      ),
      const SizedBox(height: 8),
      TextField(
        key: const ValueKey('rule-description'),
        controller: d.description,
        enabled: editable,
        maxLines: 2,
        decoration:
            InputDecoration(labelText: 'Description', errorText: _fieldErrors['description']),
        inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.ruleDescription)],
        onChanged: (_) => _changed(() {}),
      ),
      const SizedBox(height: 8),
      SizedBox(
        width: 260,
        child: TextField(
          key: const ValueKey('rule-max-records'),
          controller: d.maxRecords,
          enabled: editable,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: InputDecoration(
            labelText: 'Most records per run',
            helperText: '1 to 5,000',
            errorText: _fieldErrors['maxRecordsPerRun'],
          ),
          onChanged: (_) =>
              _changed(() => _fieldErrors = {..._fieldErrors}..remove('maxRecordsPerRun')),
        ),
      ),
      // A saved rule is switched from its header, where a non-owner is asked about taking it over.
      if (rule == null)
        SwitchListTile(
          key: const ValueKey('rule-enabled'),
          contentPadding: EdgeInsets.zero,
          value: d.enabled,
          onChanged: editable ? (v) => _changed(() => d.enabled = v) : null,
          title: const Text('Turn it on when it is created'),
        ),
    ]);
  }

  Widget _trigger(_RuleDraft d, {required bool editable}) {
    final theme = Theme.of(context);
    final zones = {deviceZone(), 'UTC', d.zone};
    final error = _errorsWhere((k) => k.startsWith('trigger'));
    return _section('Trigger', [
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: SegmentedButton<TriggerEntity>(
          key: const ValueKey('rule-entity'),
          showSelectedIcon: false,
          segments: [
            for (final e in TriggerEntity.values) ButtonSegment(value: e, label: Text(e.label)),
          ],
          selected: {d.entity},
          onSelectionChanged: editable ? (s) => _entityChanged(s.first) : null,
        ),
      ),
      const SizedBox(height: 12),
      SizedBox(
        width: 360,
        child: DropdownButtonFormField<TriggerKind>(
          key: const ValueKey('rule-kind'),
          decoration: const InputDecoration(labelText: 'Runs'),
          initialValue: d.kind,
          isExpanded: true,
          items: [
            for (final k in TriggerKind.values) DropdownMenuItem(value: k, child: Text(k.label)),
          ],
          onChanged: editable
              ? (k) {
                  if (k != null) _changed(() => d.kind = k);
                }
              : null,
        ),
      ),
      if (d.kind.isSchedule) ...[
        const SizedBox(height: 12),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SizedBox(
              width: 140,
              child: InkWell(
                key: const ValueKey('rule-time'),
                onTap: editable ? _pickTime : null,
                child: InputDecorator(
                  decoration: InputDecoration(
                    labelText: 'At',
                    enabled: editable,
                    suffixIcon: const Icon(Icons.schedule, size: 18),
                  ),
                  child: Text(d.time),
                ),
              ),
            ),
            if (d.kind == TriggerKind.SCHEDULE_WEEKLY)
              SizedBox(
                width: 180,
                child: DropdownButtonFormField<String>(
                  key: const ValueKey('rule-day'),
                  decoration: const InputDecoration(labelText: 'On'),
                  initialValue: d.day,
                  isExpanded: true,
                  items: [
                    for (final w in weekdays)
                      DropdownMenuItem(value: w, child: Text(weekdayLabel(w))),
                  ],
                  onChanged: editable
                      ? (w) {
                          if (w != null) _changed(() => d.day = w);
                        }
                      : null,
                ),
              ),
            SizedBox(
              width: 220,
              child: DropdownButtonFormField<String>(
                key: const ValueKey('rule-zone'),
                decoration: const InputDecoration(labelText: 'Time zone'),
                initialValue: d.zone,
                isExpanded: true,
                items: [
                  for (final z in zones)
                    DropdownMenuItem(
                      value: z,
                      child: Text(z == deviceZone() && z != 'UTC' ? '$z (this device)' : z),
                    ),
                ],
                onChanged: editable
                    ? (z) {
                        if (z != null) _changed(() => d.zone = z);
                      }
                    : null,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Text('After a missed ${d.kind == TriggerKind.SCHEDULE_WEEKLY ? 'week' : 'day'}',
            style: theme.textTheme.labelLarge),
        const SizedBox(height: 6),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: SegmentedButton<String>(
            key: const ValueKey('rule-catch-up'),
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: 'ONE', label: Text('Catch up with one run')),
              ButtonSegment(value: 'ALL', label: Text('Run once for each one missed')),
            ],
            selected: {d.catchUp},
            onSelectionChanged: editable ? (s) => _changed(() => d.catchUp = s.first) : null,
          ),
        ),
      ],
      if (d.kind == TriggerKind.MANUAL)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text('It never runs by itself: only when someone presses Run now.',
              style: theme.textTheme.bodySmall),
        ),
      if (error != null)
        Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(error, style: TextStyle(color: theme.colorScheme.error))),
    ]);
  }

  Widget _regions(_RuleDraft d, {required bool editable}) {
    final theme = Theme.of(context);
    final user = ref.watch(currentUserProvider);
    // A region the rule may be restricted to is one where its records can be seen and each of its
    // actions can act (§5.10.7); AUTOMATION_MANAGE is global, so it narrows nothing.
    final needed = {
      _viewPrivilege(d.entity),
      for (final a in d.actions)
        if (ActionType.parse(a.type) case final type?) type.privilege,
    };
    final mine = user == null
        ? const <int>{}
        : needed.map(user.regionsWith).reduce((a, b) => a.intersection(b));
    final choices = [
      for (final r in user?.regions ?? const <RegionAccess>[])
        if (mine.contains(r.id)) r,
    ];
    final others = d.regionIds.where((id) => !choices.any((r) => r.id == id)).toList()..sort();
    final error = _fieldErrors['regionIds'];
    if (choices.isEmpty && others.isEmpty) return const SizedBox.shrink();
    return _section(
      'Regions',
      subtitle: d.regionIds.isEmpty
          ? 'None chosen: every region where the owner can act.'
          : 'Only records in the regions chosen.',
      [
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final r in choices)
              FilterChip(
                key: ValueKey('rule-region-${r.id}'),
                label: Text(r.code),
                tooltip: r.name,
                selected: d.regionIds.contains(r.id),
                onSelected: editable
                    ? (on) => _changed(() {
                          d.regionIds = {...d.regionIds};
                          if (on) {
                            d.regionIds.add(r.id);
                          } else {
                            d.regionIds.remove(r.id);
                          }
                          _fieldErrors = {..._fieldErrors}..remove('regionIds');
                        })
                    : null,
              ),
            // Chosen before, but not one this user can offer: it can be taken off (the server would
            // refuse the save while it is on), never put back.
            for (final id in others)
              FilterChip(
                key: ValueKey('rule-region-$id'),
                label: Text(_regionCode(user, id)),
                tooltip: 'Not a region you can choose; it can only be removed',
                selected: true,
                onSelected: editable
                    ? (on) {
                        if (on) return;
                        _changed(() {
                          d.regionIds = {...d.regionIds}..remove(id);
                          _fieldErrors = {..._fieldErrors}..remove('regionIds');
                        });
                      }
                    : null,
              ),
          ],
        ),
        if (error != null)
          Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(error, style: TextStyle(color: theme.colorScheme.error))),
      ],
    );
  }

  Widget _conditions(_RuleDraft d, {required Rule? rule, required bool editable}) {
    final theme = Theme.of(context);
    final warnings = rule?.warnings ?? const <String>[];
    final listLink = rule?.listLink;
    return _section(
      'Conditions',
      subtitle:
          'The ${d.entity.plural} it acts on, using the filters of the ${d.entity.plural} list.',
      [
        ConditionBuilder(
          key: ValueKey('conditions-${d.entity.name}'),
          entity: d.entity,
          value: d.condition,
          enabled: editable,
          errorText: _errorsWhere((k) => k.startsWith('condition')),
          onChanged: (c) => _changed(() {
            d.condition = c;
            _fieldErrors = {
              for (final e in _fieldErrors.entries)
                if (!e.key.startsWith('condition')) e.key: e.value,
            };
          }),
        ),
        for (final w in warnings)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.warning_amber_outlined, size: 18, color: theme.colorScheme.tertiary),
                const SizedBox(width: 6),
                Expanded(child: Text(w, style: theme.textTheme.bodySmall)),
              ],
            ),
          ),
        if (listLink != null && !_dirty)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              key: const ValueKey('rule-list-link'),
              icon: const Icon(Icons.open_in_new, size: 18),
              label: Text('See the matching ${d.entity.plural} in the list'),
              onPressed: () => goGuarded(context, listLink),
            ),
          ),
      ],
    );
  }

  Widget _actions(_RuleDraft d, {required Rule? rule, required bool editable}) {
    final theme = Theme.of(context);
    final user = ref.watch(currentUserProvider);
    // What no card shows: about the list as a whole ("1 to 5 actions"), or an action since gone.
    final general = _errorsWhere((k) {
      if (!k.startsWith('actions')) return false;
      final index = RegExp(r'^actions\[(\d+)\]').firstMatch(k)?.group(1);
      return index == null || int.parse(index) >= d.actions.length;
    });
    return _section('Actions', [
      ActionCards(
        entity: d.entity,
        actions: d.actions,
        enabled: editable,
        offered: {
          for (final t in ActionType.values)
            if (user?.has(t.privilege) ?? false) t,
        },
        errors: {
          for (final e in _fieldErrors.entries)
            if (e.key.startsWith('actions[')) e.key: e.value,
        },
        ownerName: rule == null ? 'you' : rule.owner?.name,
        // A past date's rule names its own people; nothing is picked on it.
        people: identical(d, _draft) ? _people : {..._people, ...?rule?.people},
        onChanged: (next) => _changed(() {
          d.actions = next;
          _fieldErrors = {
            for (final e in _fieldErrors.entries)
              if (!e.key.startsWith('actions')) e.key: e.value,
          };
        }),
      ),
      if (general != null) Text(general, style: TextStyle(color: theme.colorScheme.error)),
    ]);
  }

  Widget _previewSection(_RuleDraft d, {required Rule? rule}) {
    final theme = Theme.of(context);
    final user = ref.watch(currentUserProvider);
    final type = d.entity.emailType;
    final dio = ref.watch(dioProvider);
    // Whose view to count in: the owner's, for the owner and an administrator (§5.10.9).
    final mayAskAsOwner = rule != null &&
        user != null &&
        (rule.owner?.id == user.id || user.has(Privileges.automationAdmin));
    final preview = _preview;
    return _section(
      'Preview',
      subtitle: 'What these conditions match now — or on the date viewed — without saving.',
      [
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 300,
              child: SearchPickerField<Map<String, dynamic>>(
                key: const ValueKey('preview-record'),
                label: 'Try one ${type.noun}',
                value: _previewRecord,
                labelOf: type.recordLabel,
                subtitleOf: type.recordSubtitle,
                search: (q) => searchEmailRecords(dio, type, q),
                onChanged: (r) => setState(() => _previewRecord = r),
              ),
            ),
            if (_previewRecord != null)
              IconButton(
                tooltip: 'Clear',
                icon: const Icon(Icons.clear, size: 18),
                onPressed: () => setState(() => _previewRecord = null),
              ),
            if (mayAskAsOwner)
              FilterChip(
                key: const ValueKey('preview-as-owner'),
                label: const Text('As the owner'),
                tooltip: 'Count what ${rule.owner?.name ?? 'the owner'} would match',
                selected: _asOwner,
                onSelected: (v) => setState(() => _asOwner = v),
              ),
            FilledButton.tonalIcon(
              key: const ValueKey('rule-preview'),
              icon: const Icon(Icons.visibility_outlined, size: 18),
              label: const Text('Preview'),
              onPressed: _previewing ? null : () => _runPreview(d, rule),
            ),
          ],
        ),
        if (_previewing)
          const Padding(padding: EdgeInsets.only(top: 8), child: LinearProgressIndicator()),
        if (_previewError != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(_previewError!, style: TextStyle(color: theme.colorScheme.error)),
          ),
        if (preview != null)
          _PreviewResult(
              preview: preview,
              entity: d.entity,
              ownerName: rule?.owner?.name,
              actions: d.actions),
      ],
    );
  }
}

/// What a preview found: how many match, a sample, and the one record looked at closely.
class _PreviewResult extends StatelessWidget {
  final RulePreview preview;
  final TriggerEntity entity;
  final String? ownerName;

  /// The draft's actions, to name what each rendered action is.
  final List<RuleAction> actions;
  const _PreviewResult(
      {required this.preview, required this.entity, this.ownerName, this.actions = const []});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final n = preview.matchCount;
    final noun = n == 1 ? entity.label.toLowerCase() : entity.plural;
    final record = preview.record;
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Column(
        key: const ValueKey('preview-result'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            [
              '$n $noun match${n == 1 ? 'es' : ''}',
              if (preview.evaluatedAs == 'OWNER') 'as ${ownerName ?? 'the owner'} sees them',
              if (preview.asOf != null) 'on ${preview.asOf}',
            ].join(', '),
            style: theme.textTheme.titleSmall,
          ),
          if (preview.asOfNote != null) Text(preview.asOfNote!, style: muted),
          if (record != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                '${record.label ?? '#${record.id}'} ${record.matches ? 'matches' : 'does not match'}',
                key: const ValueKey('preview-record-result'),
              ),
            ),
          // What each action would do to that record, now (live only, D10).
          for (final rendered in record?.actions ?? const <Map<String, dynamic>>[])
            _RenderedAction(rendered: rendered, actions: actions),
          for (final s in preview.sample)
            s.hidden
                ? const ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    enabled: false,
                    leading: Icon(Icons.visibility_off_outlined, size: 18),
                    title: Text('A record you cannot see'),
                  )
                : ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.chevron_right, size: 18),
                    title: Text(s.label ?? '#${s.id}'),
                    trailing: s.alreadyDone.isEmpty ? null : Text('Already acted on', style: muted),
                    onTap: s.link == null ? null : () => goGuarded(context, s.link!),
                  ),
          for (final p in preview.problems)
            Text(p, style: TextStyle(color: theme.colorScheme.error)),
          for (final w in preview.warnings) Text(w, style: muted),
        ],
      ),
    );
  }
}

/// One action as the preview rendered it for the record picked (§6.14 `PreviewDto.record`): its
/// texts with the placeholders filled, who it would be for, and what stands in its way.
class _RenderedAction extends StatelessWidget {
  final Map<String, dynamic> rendered;
  final List<RuleAction> actions;
  const _RenderedAction({required this.rendered, required this.actions});

  static const _labels = {
    'title': 'Title',
    'description': 'Description',
    'dueDate': 'Due',
    'amount': 'Amount',
    'promisedDate': 'Promised for',
    'notes': 'Notes',
    'reason': 'Reason',
    'subject': 'Subject',
    'body': 'Message',
  };

  String get _name {
    final key = rendered['key'];
    for (final a in actions) {
      if (a.key == key) return a.summary;
    }
    return ActionType.parse(rendered['type'])?.label ?? humanizeEnum(rendered['type'] as String?);
  }

  static List<String> _strings(Object? raw) =>
      [for (final e in (raw is List ? raw : const [])) '$e'];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final texts = rendered['rendered'] is Map
        ? (rendered['rendered'] as Map).cast<String, dynamic>()
        : const <String, dynamic>{};
    final assignees = RecordAssignee.listFrom(rendered['assignees']) ?? const [];
    final email = rendered['email'] is Map
        ? EmailPreview.fromJson((rendered['email'] as Map).cast<String, dynamic>())
        : null;
    return Container(
      key: ValueKey('preview-action-${rendered['key']}'),
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_name, style: theme.textTheme.titleSmall),
          for (final e in texts.entries)
            if (e.value != null && '${e.value}'.isNotEmpty)
              Text(
                '${_labels[e.key] ?? humanizeEnum(e.key)}: '
                '${e.key == 'amount' ? formatMoney(e.value) : e.value}',
              ),
          if (email?.from != null) Text('From ${email!.from!.display}'),
          if (email != null && email.to.isNotEmpty)
            Text('To ${email.to.map((p) => p.display).join(', ')}'),
          for (final a in assignees)
            Text([
              a.label,
              if (a.people.isNotEmpty) a.people.map((p) => p.name).join(', '),
              if (a.unresolved) 'nobody assigned',
            ].join(' · ')),
          for (final p in [..._strings(rendered['problems']), ...?email?.problems])
            Text(p, style: TextStyle(color: theme.colorScheme.error)),
          for (final w in [..._strings(rendered['warnings']), ...?email?.warnings])
            Text(w, style: muted),
        ],
      ),
    );
  }
}

String _regionCode(CurrentUser? user, int id) {
  for (final r in user?.regions ?? const <RegionAccess>[]) {
    if (r.id == id) return r.code;
  }
  return 'Region #$id';
}

String _viewPrivilege(TriggerEntity entity) => switch (entity) {
      TriggerEntity.CUSTOMER => Privileges.customerView,
      TriggerEntity.INVOICE => Privileges.invoiceView,
      TriggerEntity.PAYMENT => Privileges.paymentView,
    };

class _ReadOnlyNotice extends StatelessWidget {
  const _ReadOnlyNotice();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.lock_clock_outlined, size: 44, color: theme.colorScheme.outline),
              const SizedBox(height: 12),
              Text("New rules can't be created on a past date.",
                  textAlign: TextAlign.center, style: theme.textTheme.titleMedium),
              const SizedBox(height: 4),
              Text("Go back to today to carry on. What you've entered so far is kept.",
                  textAlign: TextAlign.center, style: theme.textTheme.bodySmall),
            ],
          ),
        ),
      ),
    );
  }
}
