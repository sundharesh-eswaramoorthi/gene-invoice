import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import 'automation_models.dart';

/// Where the rules screen gets its data.
///
/// There is no provider for the list itself: the rules page is a `DataTableScaffold`, which reads
/// its own page through `tablePageProvider` against `/api/automation/rules` with the `automation`
/// schema, exactly as every other list page does. What is here is the one rule the editor works
/// on, and the four writes — which is everything the list cannot do for itself.

/// True when the signed-in user holds AUTOMATION_VIEW / AUTOMATION_MANAGE. Reading and writing
/// are held apart on the server because a rule acts for everybody: whoever writes one is deciding
/// that tasks, promises, disputes and email will be made with nobody in the loop.
final canViewAutomationProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.automationView) ?? false);
final canManageAutomationProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.automationManage) ?? false);

/// One rule, read fresh. The editor works from this rather than from the row it was opened on:
/// a rule is written once and edited months later, and the row on screen may have been overtaken
/// by somebody else's change — an edit that saved the stale copy back would undo theirs without
/// either of them seeing it.
final automationRuleProvider =
    FutureProvider.autoDispose.family<AutomationRule, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/rules/$id');
  return AutomationRule.fromJson((res.data as Map).cast<String, dynamic>());
});

/// What this rule has lately been asked to do and what came of each (R4).
///
/// Read rather than derived from the row: `runCount` and `lastRunAt` move only when an action
/// actually happened, so a rule that skips or fails every single run looks there exactly like one
/// that has never matched anything. The skips are the answer, and they are only here.
final automationRuleRunsProvider =
    FutureProvider.autoDispose.family<List<AutomationRuleRun>, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/rules/$id/runs');
  return (res.data as List)
      .cast<Map>()
      .map((m) => AutomationRuleRun.fromJson(m.cast<String, dynamic>()))
      .toList();
});

/// A rule as the API takes it. Every write states the whole rule — there is no partial update —
/// so this is also how a rule is switched on or off without opening the editor: the same rule
/// with one field changed.
Map<String, dynamic> automationRuleBody({
  required String name,
  String? description,
  required bool enabled,
  required AutomationRecordType entityType,
  required AutomationTrigger trigger,
  required List<String> filters,
  required AutomationAction action,
  required Map<String, dynamic> actionSpec,
}) =>
    {
      'name': name,
      'description': description,
      'enabled': enabled,
      'entityType': entityType.wire,
      'trigger': trigger.wire,
      'filters': filters,
      'action': action.wire,
      'actionSpec': actionSpec,
    };

/// Writes a rule: a new one without [id], the whole of an existing one with it. A rule the server
/// will not accept — a filter naming a column that kind of record does not have, an action missing
/// what it needs — comes back as a 400 whose message names the thing that is wrong, and is thrown
/// for the form to show (R9).
Future<AutomationRule> saveAutomationRule(WidgetRef ref,
    {int? id, required Map<String, dynamic> body}) async {
  final dio = ref.read(dioProvider);
  final res = id == null
      ? await dio.post('/api/automation/rules', data: body)
      : await dio.put('/api/automation/rules/$id', data: body);
  final saved = AutomationRule.fromJson((res.data as Map).cast<String, dynamic>());
  _refresh(ref, saved.id);
  return saved;
}

/// Switches one rule on or off. The whole rule goes back up because that is the only write the
/// API has; nothing else about it changes.
Future<AutomationRule> setAutomationRuleEnabled(
    WidgetRef ref, AutomationRule rule, bool enabled) async {
  final type = rule.entityType;
  final trigger = rule.trigger;
  final action = rule.action;
  if (type == null || trigger == null || action == null) {
    // The server is newer than this build and this rule is written in terms it does not know.
    // Sending back what it could read would rewrite the rule as something else.
    throw StateError('This rule was written for a newer version of the app; open it to change it');
  }
  return saveAutomationRule(ref,
      id: rule.id,
      body: automationRuleBody(
        name: rule.name,
        description: rule.description,
        enabled: enabled,
        entityType: type,
        trigger: trigger,
        filters: rule.filters,
        action: action,
        actionSpec: automationActionSpecBody(rule.actionSpec),
      ));
}

/// Runs the rule over everything it currently matches, and says what that came to.
Future<AutomationRunResult> runAutomationRule(WidgetRef ref, int id) async {
  final res = await ref.read(dioProvider).post('/api/automation/rules/$id/run');
  final result = AutomationRunResult.fromJson((res.data as Map).cast<String, dynamic>());
  // The run count and the last-run time on the row have both moved.
  _refresh(ref, id);
  return result;
}

Future<void> deleteAutomationRule(WidgetRef ref, int id) async {
  await ref.read(dioProvider).delete('/api/automation/rules/$id');
  _refresh(ref, id);
}

/// The action's parameters as the API takes them, from a rule that was read back. Only what the
/// rule actually carries is sent: the server checks each action against what it needs, and an
/// explicit null is not the same as a field left out.
Map<String, dynamic> automationActionSpecBody(AutomationActionSpec spec) => {
      if (spec.title != null) 'title': spec.title,
      if (spec.body != null) 'body': spec.body,
      if (spec.dueInDays != null) 'dueInDays': spec.dueInDays,
      if (spec.amount != null) 'amount': spec.amount,
      if (spec.from != null) 'from': spec.from!.toJson(),
      'assignees': [for (final token in spec.assignees) token.toJson()],
      if (spec.status != null) 'status': spec.status,
    };

/// After any write: the rules list, the rule itself and its runs all ask again. The whole table
/// family is invalidated rather than one page of it, because a write can move a row between
/// pages; the runs because Run now is exactly what puts rows there.
void _refresh(WidgetRef ref, int id) {
  ref.invalidate(tablePageProvider);
  ref.invalidate(automationRuleProvider(id));
  ref.invalidate(automationRuleRunsProvider(id));
}
