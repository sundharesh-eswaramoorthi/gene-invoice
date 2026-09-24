import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../../core/table/reference_picker.dart';
import '../../core/unsaved_changes.dart';
import '../auth/auth_controller.dart';
import '../../shared/widgets/detail_scaffold.dart';
import 'action_editor.dart';
import 'automation_providers.dart';
import 'condition_editor.dart';
import 'rules_screen.dart' show weekdayName;

/// THE RULE BUILDER — a FULL PAGE, not a dialog (A1, A2, A3, A4).
///
/// A rule is a paragraph of consequences: a subject, a trigger, an AND/OR tree, up to five
/// actions with their own templates, and the branches it may reach. A dialog is the wrong shape
/// for that, and the /invoices/new precedent already says so.
///
/// It is reached at `/automation/rules/new` and, for an existing rule, at
/// `/automation/rules/<id>?edit=true` — the same route the detail page owns, because the URL is
/// the state and an editor is a state of that page, not a fifth route. Both carry
/// `onExit: mayLeave`, so navigating away with unsaved work asks first.
class RuleFormScreen extends ConsumerStatefulWidget {
  /// The rule being edited, or null for a new one.
  final AutomationRule? existing;

  const RuleFormScreen({super.key, this.existing});

  @override
  ConsumerState<RuleFormScreen> createState() => _RuleFormScreenState();
}

/// The three trigger shapes the editor offers, which are five server constants underneath: "on
/// change" carries two checkboxes and maps to ON_CREATED / ON_UPDATED / ON_CREATED_OR_UPDATED
/// (A1).
enum _TriggerShape { onChange, daily, weekly }

class _RuleFormScreenState extends ConsumerState<RuleFormScreen> {
  final _name = TextEditingController();
  final _description = TextEditingController();
  final _cooldown = TextEditingController();

  AutomationSubject _subject = AutomationSubject.invoice;
  _TriggerShape _shape = _TriggerShape.onChange;
  bool _onCreated = false;
  bool _onUpdated = true;
  int _hourUtc = 9;
  int _dayOfWeek = 1;

  ConditionGroup _conditions = const ConditionGroup(Connector.AND, []);
  List<AutomationAction> _actions = [];
  final Set<int> _regionIds = {};

  bool _dirty = false;
  bool _saving = false;
  String? _error;
  AutovalidateMode _validation = AutovalidateMode.disabled;
  final _formKey = GlobalKey<FormState>();

  // ---- the live preview, the send-email dialog's machinery verbatim (A4) ----
  ReferenceOption? _previewRecord;
  RulePreview? _preview;
  String? _previewError;
  bool _previewing = false;
  Timer? _previewDebounce;
  int _previewSeq = 0;

  late final UnsavedChanges _unsaved;

  @override
  void initState() {
    super.initState();
    _unsaved = ref.read(unsavedChangesProvider)..register(_confirmDiscard);
    final rule = widget.existing;
    if (rule == null) {
      _actions = [AutomationAction(kind: AutomationActionKind.CREATE_TASK)];
      return;
    }
    _name.text = rule.name;
    _description.text = rule.description ?? '';
    _cooldown.text = rule.cooldownDays?.toString() ?? '';
    _subject = rule.subjectType ?? AutomationSubject.invoice;
    _hourUtc = rule.scheduleHourUtc ?? 9;
    _dayOfWeek = rule.scheduleDayOfWeek ?? 1;
    _shape = switch (rule.triggerKind) {
      AutomationTrigger.SCHEDULE_DAILY => _TriggerShape.daily,
      AutomationTrigger.SCHEDULE_WEEKLY => _TriggerShape.weekly,
      _ => _TriggerShape.onChange,
    };
    _onCreated = rule.triggerKind == AutomationTrigger.ON_CREATED ||
        rule.triggerKind == AutomationTrigger.ON_CREATED_OR_UPDATED;
    _onUpdated = rule.triggerKind == AutomationTrigger.ON_UPDATED ||
        rule.triggerKind == AutomationTrigger.ON_CREATED_OR_UPDATED;
    final tree = rule.conditions;
    _conditions = tree is ConditionGroup
        ? tree
        : tree is ConditionLeaf
            ? ConditionGroup(Connector.AND, [tree])
            : const ConditionGroup(Connector.AND, []);
    // COPIES, not the provider's own objects: an action is mutable and this form edits it in
    // place, so sharing them would rewrite the cached rule under somebody who backs out (A3).
    _actions = rule.actions.isEmpty
        ? [AutomationAction(kind: AutomationActionKind.CREATE_TASK)]
        : [for (final a in rule.actions) a.copy()];
    _regionIds.addAll(rule.regions.map((r) => r.id));
  }

  @override
  void dispose() {
    _unsaved.unregister(_confirmDiscard);
    _previewDebounce?.cancel();
    _name.dispose();
    _description.dispose();
    _cooldown.dispose();
    super.dispose();
  }

  void _touch([VoidCallback? mutate]) {
    setState(() {
      mutate?.call();
      _dirty = true;
    });
    _schedulePreview();
  }

  /// Which branches the pickers inside this form should ask about: the ones the rule NAMES, or —
  /// when it names none, which means "every branch its author manages" — every branch this person
  /// works in. Empty is "name no branch", which the POC picker is refused for (B1, R8).
  List<int> get _pickerRegions => _regionIds.isEmpty
      ? pickerRegionsFor(ref.read(currentUserProvider))
      : _regionIds.toList();

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Discard unsaved changes?'),
        content: const Text('This rule has edits that have not been saved.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep editing')),
          FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Discard')),
        ],
      ),
    );
    if (ok == true) _dirty = false;
    return ok == true;
  }

  AutomationTrigger get _trigger => switch (_shape) {
        _TriggerShape.daily => AutomationTrigger.SCHEDULE_DAILY,
        _TriggerShape.weekly => AutomationTrigger.SCHEDULE_WEEKLY,
        _TriggerShape.onChange => _onCreated && _onUpdated
            ? AutomationTrigger.ON_CREATED_OR_UPDATED
            : _onCreated
                ? AutomationTrigger.ON_CREATED
                : AutomationTrigger.ON_UPDATED,
      };

  // ------------------------------------------------------------------ the subject reset (A1)

  /// Changing the subject throws away the conditions AND the actions, and asks first.
  ///
  /// It has to: every condition names a column on the old subject's table and every placeholder
  /// names a slot in the old subject's catalogue, so keeping them would produce a rule the server
  /// refuses in words about columns the author can no longer see. Asking first is the standard
  /// confirm; doing it silently would be the surprise (A1, A2).
  Future<void> _changeSubject(AutomationSubject next) async {
    if (next == _subject) return;
    final hasWork = _conditions.leafCount > 0 || _actions.any((a) => !a.isBlank);
    if (hasWork) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text('Change the subject to ${next.plural}?'),
          content: const Text(
              'The conditions and the actions are written against the records this rule is '
              'about, so they will be cleared.'),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: const Text('Keep what I have')),
            FilledButton(
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: const Text('Change and clear')),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    _touch(() {
      _subject = next;
      _conditions = const ConditionGroup(Connector.AND, []);
      _actions = [AutomationAction(kind: AutomationActionKind.CREATE_TASK)];
      _previewRecord = null;
      _preview = null;
    });
  }

  // ------------------------------------------------------------------ the live preview (A4)

  void _schedulePreview() {
    _previewDebounce?.cancel();
    _previewDebounce = Timer(const Duration(milliseconds: 400), _runPreview);
  }

  /// `_runPreview`'s debounce and monotonic sequence guard, copied from the send-email dialog and
  /// checked in the success arm, the error arm AND the finally — an out-of-order answer that only
  /// lost the race on the way back would otherwise overwrite a newer one (A4).
  Future<void> _runPreview() async {
    final record = _previewRecord;
    final seq = ++_previewSeq;
    if (!mounted) return;
    if (record == null) {
      setState(() {
        _preview = null;
        _previewError = null;
        _previewing = false;
      });
      return;
    }
    final first = _actions.isEmpty ? null : _actions.first;
    final title = _actions
        .where((a) => a.kind == AutomationActionKind.CREATE_TASK)
        .map((a) => a.title)
        .firstWhere((t) => t.isNotEmpty, orElse: () => '');
    final emailAction = _actions
        .where((a) => a.kind == AutomationActionKind.SEND_EMAIL)
        .firstOrNull;
    if (title.isEmpty && emailAction == null && first == null) {
      setState(() {
        _preview = null;
        _previewing = false;
      });
      return;
    }
    setState(() => _previewing = true);
    try {
      final preview = await previewTemplates(
        ref.read(dioProvider),
        subject: _subject,
        subjectId: record.id,
        title: title,
        subject0: emailAction?.subject,
        body: emailAction?.body,
      );
      if (!mounted || seq != _previewSeq) return;
      setState(() {
        _preview = preview;
        _previewError = null;
      });
    } catch (e) {
      if (!mounted || seq != _previewSeq) return;
      setState(() {
        _preview = null;
        _previewError = apiErrorMessage(e);
      });
    } finally {
      if (mounted && seq == _previewSeq) setState(() => _previewing = false);
    }
  }

  // ------------------------------------------------------------------ saving

  Future<void> _save() async {
    final ok = _formKey.currentState?.validate() ?? false;
    setState(() {
      if (!ok) _validation = AutovalidateMode.onUserInteraction;
      _error = null;
    });
    if (!ok) return;

    // Empty NESTED groups are dropped rather than sent: the server refuses one with "A group
    // needs at least one condition", and somebody who pressed Add group and changed their mind
    // should not have their save refused for a card they can see is blank (A2).
    final tree = _conditions.pruned;
    final body = <String, dynamic>{
      'name': _name.text.trim(),
      'description': optionalText(_description.text),
      'subjectType': _subject.wire,
      'triggerKind': _trigger.name,
      if (_trigger.scheduled) 'scheduleHourUtc': _hourUtc,
      if (_trigger == AutomationTrigger.SCHEDULE_WEEKLY) 'scheduleDayOfWeek': _dayOfWeek,
      'conditions': tree.leafCount == 0 ? null : tree.toJson(),
      'actions': [for (final a in _actions) a.toJson()],
      'cooldownDays': int.tryParse(_cooldown.text.trim()),
      'enabled': widget.existing?.enabled ?? true,
      // An EMPTY list is a real state — "every branch my author may manage, as of now" — and is
      // NOT the same as leaving the field out, so it is always sent (A1, B1).
      'regionIds': _regionIds.toList(),
    };

    setState(() => _saving = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final saved = await saveRule(ref, id: widget.existing?.id, body: body);
      _dirty = false;
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(
          content: Text(widget.existing == null ? 'Rule created' : 'Rule saved')));
      goGuarded(context, '/automation/rules/${saved.id}');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = apiErrorMessage(e);
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final narrow = MediaQuery.sizeOf(context).width < 760;

    return Scaffold(
      body: Form(
        key: _formKey,
        autovalidateMode: _validation,
        child: ListView(
          padding: EdgeInsets.fromLTRB(narrow ? 16 : 24, 16, narrow ? 16 : 24, 32),
          children: [
            Row(
              children: [
                IconButton(
                  tooltip: 'Back',
                  icon: const Icon(Icons.arrow_back),
                  onPressed: () => goGuarded(
                      context,
                      widget.existing == null
                          ? '/automation/rules'
                          : '/automation/rules/${widget.existing!.id}'),
                ),
                Expanded(
                  child: Text(widget.existing == null ? 'New rule' : 'Edit rule',
                      style: theme.textTheme.headlineSmall),
                ),
                FilledButton.icon(
                  icon: _saving
                      ? const SizedBox(
                          width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.save_outlined, size: 18),
                  label: const Text('Save'),
                  onPressed: _saving ? null : _save,
                ),
              ],
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
              ),
            const SizedBox(height: 8),
            _section(context, '1. What sets it off', _trigger0(context)),
            _section(
              context,
              '2. Which records',
              ConditionEditor(
                entity: _subject.tableEntity,
                subjectPlural: _subject.plural,
                root: _conditions,
                regionIds: _pickerRegions,
                enabled: !_saving,
                onChanged: (next) => _touch(() => _conditions = next),
              ),
            ),
            _section(
              context,
              '3. What it does',
              ActionEditor(
                subject: _subject,
                actions: _actions,
                enabled: !_saving,
                onChanged: _touch,
              ),
            ),
            _section(context, '4. How it will read', _previewPanel(context)),
            _section(context, '5. Where it may reach', _branches(context)),
          ],
        ),
      ),
    );
  }

  Widget _section(BuildContext context, String title, Widget child) => Padding(
        padding: const EdgeInsets.only(top: 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            child,
          ],
        ),
      );

  Widget _trigger0(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        TextFormField(
          controller: _name,
          decoration: const InputDecoration(labelText: 'Name *'),
          inputFormatters: [LengthLimitingTextInputFormatter(200)],
          validator: (v) => (v ?? '').trim().isEmpty ? 'Give the rule a name' : null,
          onChanged: (_) => _touch(),
        ),
        const SizedBox(height: 12),
        TextFormField(
          controller: _description,
          decoration: const InputDecoration(labelText: 'What it is for'),
          inputFormatters: [LengthLimitingTextInputFormatter(1000)],
          minLines: 1,
          maxLines: 3,
          onChanged: (_) => _touch(),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<AutomationSubject>(
          decoration: const InputDecoration(labelText: 'About'),
          initialValue: _subject,
          isExpanded: true,
          items: [
            for (final s in AutomationSubject.values)
              DropdownMenuItem(value: s, child: Text(s.plural)),
          ],
          onChanged: _saving ? null : (s) => s == null ? null : _changeSubject(s),
        ),
        const SizedBox(height: 12),
        SegmentedButton<_TriggerShape>(
          segments: const [
            ButtonSegment(value: _TriggerShape.onChange, label: Text('On change')),
            ButtonSegment(value: _TriggerShape.daily, label: Text('Daily')),
            ButtonSegment(value: _TriggerShape.weekly, label: Text('Weekly')),
          ],
          selected: {_shape},
          showSelectedIcon: false,
          onSelectionChanged:
              _saving ? null : (picked) => _touch(() => _shape = picked.first),
        ),
        if (_shape == _TriggerShape.onChange) ...[
          CheckboxListTile(
            dense: true,
            value: _onCreated,
            title: const Text('When one is created'),
            controlAffinity: ListTileControlAffinity.leading,
            // At least one of the two must stay on, because a trigger that is neither is not a
            // trigger; unticking the last one ticks the other (A1).
            onChanged: _saving
                ? null
                : (on) => _touch(() {
                      _onCreated = on ?? false;
                      if (!_onCreated && !_onUpdated) _onUpdated = true;
                    }),
          ),
          CheckboxListTile(
            dense: true,
            value: _onUpdated,
            title: const Text('When one changes'),
            controlAffinity: ListTileControlAffinity.leading,
            onChanged: _saving
                ? null
                : (on) => _touch(() {
                      _onUpdated = on ?? false;
                      if (!_onCreated && !_onUpdated) _onCreated = true;
                    }),
          ),
        ],
        if (_shape != _TriggerShape.onChange) ...[
          const SizedBox(height: 12),
          Row(
            children: [
              if (_shape == _TriggerShape.weekly) ...[
                Expanded(
                  child: DropdownButtonFormField<int>(
                    decoration: const InputDecoration(labelText: 'Day'),
                    initialValue: _dayOfWeek,
                    isExpanded: true,
                    items: [
                      for (var d = 1; d <= 7; d++)
                        DropdownMenuItem(value: d, child: Text(weekdayName(d))),
                    ],
                    onChanged: _saving
                        ? null
                        : (d) => _touch(() => _dayOfWeek = d ?? 1),
                  ),
                ),
                const SizedBox(width: 12),
              ],
              Expanded(
                child: DropdownButtonFormField<int>(
                  // LABELLED UTC, because it is stored and fired in UTC and a rule that says
                  // "9am" and fires at 2:30pm local is the kind of surprise nobody debugs (A1).
                  decoration: const InputDecoration(labelText: 'Hour (UTC)'),
                  initialValue: _hourUtc,
                  isExpanded: true,
                  items: [
                    for (var h = 0; h < 24; h++)
                      DropdownMenuItem(
                          value: h, child: Text('${h.toString().padLeft(2, '0')}:00 UTC')),
                  ],
                  onChanged: _saving ? null : (h) => _touch(() => _hourUtc = h ?? 0),
                ),
              ),
            ],
          ),
        ],
        const SizedBox(height: 12),
        TextFormField(
          controller: _cooldown,
          decoration: const InputDecoration(
            labelText: 'Cooldown (days)',
            helperText: 'Do not act on the same record again within this many days',
          ),
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          onChanged: (_) => _touch(),
        ),
        const SizedBox(height: 12),
        // THE TWO HONEST LIMITS, stated where the author sets the schedule rather than discovered
        // afterwards (A1, A5).
        Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: theme.colorScheme.tertiaryContainer,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.info_outline, size: 18, color: theme.colorScheme.onTertiaryContainer),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Automation is swept every minute, so a daily rule fires within a minute of '
                  'its hour rather than on it. Order is guaranteed only between the actions of '
                  'one rule on one record — two rules about the same record may finish in any '
                  'order.',
                  style: TextStyle(color: theme.colorScheme.onTertiaryContainer),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _previewPanel(BuildContext context) {
    final theme = Theme.of(context);
    final preview = _preview;
    final record = _previewRecord;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          'Pick one record to see the templates filled in. Nothing is written.',
          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 8),
        if (record != null)
          Align(
            alignment: Alignment.centerLeft,
            child: Chip(
              label: Text(record.label),
              onDeleted: () => _touch(() {
                _previewRecord = null;
                _preview = null;
              }),
            ),
          ),
        SizedBox(
          // The picker is a search box over a 220px list, so 300 is what it actually needs.
          height: 300,
          child: ReferencePicker(
            kind: _subject.referenceKind,
            label: 'Find a ${_subject.noun}',
            value: record,
            regionIds: _pickerRegions,
            onChanged: (o) => _touch(() => _previewRecord = o),
          ),
        ),
        const SizedBox(height: 8),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  // NOT "as of the date it will run": until there is temporal storage the renderer
                  // reads the LIVE record, and a panel that implied otherwise would be lying about
                  // the one thing it exists to show (A4, B3).
                  Expanded(
                      child: Text('How it will read — values as of today',
                          style: theme.textTheme.titleSmall)),
                  if (_previewing)
                    const SizedBox(
                        width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                ],
              ),
              if (_previewError != null)
                Text('Preview unavailable: $_previewError',
                    style: TextStyle(color: theme.colorScheme.error)),
              if (record == null && _previewError == null)
                Text('Pick a record above.', style: theme.textTheme.bodySmall),
              if (preview != null) ...[
                if (preview.title != null) _rendered(context, 'Task title', preview.title!),
                if (preview.subject != null) _rendered(context, 'Email subject', preview.subject!),
                if (preview.body != null) _rendered(context, 'Email message', preview.body!),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _rendered(BuildContext context, String label, RenderedText rendered) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: theme.textTheme.labelSmall),
          Text(rendered.text.isEmpty ? '(empty)' : rendered.text),
          if (rendered.unresolved.isNotEmpty)
            Text(
              'Nothing behind: ${rendered.unresolved.join(', ')}',
              style: theme.textTheme.bodySmall?.copyWith(color: Colors.brown.shade800),
            ),
        ],
      ),
    );
  }

  Widget _branches(BuildContext context) {
    final theme = Theme.of(context);
    // Only branches the AUTHOR may MANAGE: naming one they cannot is a 403 at save (D-46), so
    // the picker offers only what will be accepted (A1, B1).
    final regions = ref.watch(manageableRegionsProvider).valueOrNull ?? const <RegionRef>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          _regionIds.isEmpty
              ? 'Name no branch and the rule runs in every branch its author may manage, as it '
                  'stands on the day it runs.'
              : 'The rule reaches only the branches named here.',
          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 8),
        if (regions.isEmpty)
          const Text('No branches to choose between.')
        else
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final region in regions)
                FilterChip(
                  label: Text(region.label),
                  selected: _regionIds.contains(region.id),
                  onSelected: _saving
                      ? null
                      : (on) => _touch(() {
                            if (on) {
                              _regionIds.add(region.id);
                            } else {
                              _regionIds.remove(region.id);
                            }
                          }),
                ),
            ],
          ),
      ],
    );
  }
}

/// Loads one rule and hands it to the builder.
///
/// The form is seeded once in `initState`, so it needs the rule BEFORE it is built — a form that
/// re-seeded itself every time the provider answered would throw away what somebody was typing
/// the moment anything else invalidated the list (A1).
class RuleEditGate extends ConsumerWidget {
  final int id;
  const RuleEditGate({super.key, required this.id});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref.watch(ruleDetailProvider(id)).when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => RecordUnavailable(
            message: notFoundMessage(e, 'rule'),
            onBack: () => goGuarded(context, '/automation/rules'),
          ),
          // Keyed on the rule's definition version, so re-opening the editor after a save starts
          // from what was saved rather than from a stale seed.
          data: (rule) => RuleFormScreen(
            key: ValueKey('rule-form-${rule.id}-${rule.definitionVersion}'),
            existing: rule,
          ),
        );
  }
}
