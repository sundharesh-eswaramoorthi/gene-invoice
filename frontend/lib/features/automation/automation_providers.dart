import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import '../notifications/notifications_providers.dart' show pollUnreadCount;
import 'automation_models.dart';

export 'automation_models.dart';

/// May this person read the automation screens at all. AUTOMATION_VIEW is what all five GETs sit
/// behind, so without it every screen here has nothing to show (A1, UI-10).
final canViewAutomationProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.automationView) ?? false);

/// May this person author rules. AUTOMATION_* is COMPANY-WIDE by design — the privilege says you
/// may write rules, and WHERE a rule may reach is bounded by the branches it NAMES. So this is
/// `has` and deliberately not `hasIn`: there is no per-record branch to ask about, because a rule
/// belongs to no branch.
///
/// What this gate cannot answer, and must not pretend to: AutomationRuleService.validate re-checks
/// every branch a SAVED rule names against the CALLER's own MANAGE grants, not its author's. So a
/// rule naming a branch this person may only read is visible, and its Edit button will 403 on
/// save. That is the server's answer to give and this client asks for it rather than guessing —
/// but it is why switching a rule off must not go through the save door (A1, B1).
final canManageAutomationProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.automationManage) ?? false);

/// May this person set a rule going. A separate privilege from authoring one, and the bulk
/// RUN_NOW arm needs BOTH: the mapping is gated on MANAGE and the arm re-checks RUN, so a button
/// offered on MANAGE alone would earn a 403 after the rows were picked (A5, AUTH-05).
final canRunAutomationProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.automationRun) ?? false);

// There is deliberately NO canExportAutomationProvider. EXPORT_DATA says a person may export, not
// that a table can be exported: DataTableScaffold's export posts to `<path>/export`, and neither
// /api/automation/rules nor /api/automation/steps declares one. The privilege was the only thing
// either screen checked, so the button appeared and answered 405 (A1).

/// One rule, by its own id — the detail page and every refresh after a save.
final ruleDetailProvider = FutureProvider.autoDispose.family<AutomationRule, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/rules/$id');
  return AutomationRule.fromJson((res.data as Map).cast<String, dynamic>());
});

/// What the placeholder picker offers for one subject, at BOTH levels — the server groups them
/// and this passes the grouping through untouched, so the picker and the To field cannot disagree
/// about what "Customer level" means (A4, L4).
final placeholdersProvider = FutureProvider.autoDispose
    .family<List<PlaceholderSlot>, AutomationSubject>((ref, subject) async {
  final dio = ref.watch(dioProvider);
  final res = await dio
      .get('/api/automation/placeholders', queryParameters: {'subjectType': subject.wire});
  return (res.data as List)
      .whereType<Map>()
      .map((m) => PlaceholderSlot.fromJson(m.cast<String, dynamic>()))
      .toList();
});

/// How many steps are stuck, for the sidebar badge.
///
/// `pollUnreadCount` verbatim — the poller the notification bell, the inbox and the task badge
/// already use — against the endpoint that answers `{"count": N}` in exactly the shape it reads.
/// Nobody without AUTOMATION_VIEW is shown the entry, and the stream answers 0 for them rather
/// than polling an endpoint that would 403 every thirty seconds (A5, UI-10).
final poisonedStepCountProvider = StreamProvider.autoDispose<int>((ref) {
  if (!ref.watch(canViewAutomationProvider)) return Stream.value(0);
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/automation/steps/poisoned-count');
});

/// Saving a rule. POST for a new one, PUT for an existing one, and the SAME body either way —
/// the server takes one SaveRuleRequest and replaces the whole rule, because a half-sent rule is
/// a rule that does something nobody asked for (A1).
Future<AutomationRule> saveRule(WidgetRef ref, {int? id, required Map<String, dynamic> body}) async {
  final dio = ref.read(dioProvider);
  final res = id == null
      ? await dio.post('/api/automation/rules', data: body)
      : await dio.put('/api/automation/rules/$id', data: body);
  final saved = AutomationRule.fromJson((res.data as Map).cast<String, dynamic>());
  invalidateAutomation(ref, saved.id);
  return saved;
}

/// A SOFT delete: the run history keeps its subject and still names the rule (A5).
Future<void> deleteRule(WidgetRef ref, int id) async {
  await ref.read(dioProvider).delete('/api/automation/rules/$id');
  invalidateAutomation(ref, id);
}

/// SWITCHING A RULE OFF IS THE SAFETY VALVE, so it goes through the door that means only that.
///
/// This used to re-POST the whole rule — `PUT /api/automation/rules/{id}` with every field read
/// back off the DTO and `enabled` flipped. A PUT is a SAVE: AutomationRuleService.update runs
/// validate(), which re-checks every branch the rule names against the CALLER's own MANAGE grants
/// and refuses any branch that has since been retired. So the one control that stops a misbehaving
/// rule was the one that could fail — 403 for a reader who may only view one of its branches, 400
/// "<CODE> is retired" for a rule pointed at a closed branch — on a body that was only the
/// server's own answer with one flag changed. Delete and the list's bulk Switch off both worked on
/// the same rule, which made the destructive control the only working one on the page.
///
/// The bulk ENABLE/DISABLE arm is the endpoint that means "just this flag": it calls
/// `setEnabled`, which touches `enabled` (and a schedule's next slot) and validates nothing else.
/// One id is a perfectly good bulk selection, and it is the SAME door the list's own Switch off
/// uses, so two controls with one name can no longer give two answers (A1, A5, B1).
///
/// "It was already like that" comes back as a SKIPPED row rather than a failure, and is reported
/// as the server worded it rather than swallowed: the page thought otherwise, and the reader is
/// owed the correction.
Future<void> setRuleEnabled(WidgetRef ref, AutomationRule rule, bool enabled) async {
  final res = await ref.read(dioProvider).post('/api/automation/rules/bulk', data: {
    'action': enabled ? 'ENABLE' : 'DISABLE',
    'ids': [rule.id],
  });
  // Defensively cast, the house rule for every hand-written DTO: a BulkResult is what this door
  // answers, and a page whose Switch off threw a TypeError would be no better than the 403 it
  // replaced.
  final body = res.data is Map
      ? (res.data as Map).cast<String, dynamic>()
      : const <String, dynamic>{};
  // Whatever the answer, the rule on screen may no longer be the rule on the server.
  invalidateAutomation(ref, rule.id);
  final refused = _firstOutcome(body['failed']) ?? _firstOutcome(body['skipped']);
  if (refused != null) throw RuleNotToggled(refused);
}

/// The server's own sentence for the one rule the bulk door was given, so the page repeats what it
/// was told instead of inventing a reason of its own (A1, TBL-05).
class RuleNotToggled implements Exception {
  final String reason;
  const RuleNotToggled(this.reason);

  /// `apiErrorMessage` falls back to `toString()` for anything that is not a DioException, so the
  /// snackbar reads as the sentence and not as "Instance of ...".
  @override
  String toString() => reason;
}

String? _firstOutcome(Object? outcomes) {
  if (outcomes is! List || outcomes.isEmpty) return null;
  final first = outcomes.first;
  final reason = first is Map ? first['reason'] : null;
  return reason is String && reason.isNotEmpty ? reason : 'The rule was left as it was';
}

/// The dry run: what would happen, having written nothing at all. `apply` defaults to false on
/// the server too, and it is named here anyway so the URL says which of the two this is (A5).
Future<DryRun> dryRunRule(WidgetRef ref, int id, {DateTime? asOf}) async {
  final res = await ref.read(dioProvider).post(
        '/api/automation/rules/$id/run',
        queryParameters: {'apply': false, if (asOf != null) 'asOf': _day(asOf)},
        data: const <String, dynamic>{},
      );
  return DryRun.fromJson((res.data as Map).cast<String, dynamic>());
}

/// Setting the rule going for real.
///
/// [requestId] IS THE DOUBLE-CLICK GUARD and it is required: the occasion is `"M" + requestId`,
/// so a second click carrying the SAME id bounces off `uk_run_occasion` and answers 200 with the
/// first click's run instead of opening a second one. A guard the client may omit is a guard that
/// is not there, which is why the sheet mints one per press and keeps it (A5).
Future<AutomationRun> runRule(WidgetRef ref, int id, {required String requestId}) async {
  final res = await ref.read(dioProvider).post(
    '/api/automation/rules/$id/run',
    queryParameters: {'apply': true},
    data: {'requestId': requestId},
  );
  final run = AutomationRun.fromJson((res.data as Map).cast<String, dynamic>());
  invalidateAutomation(ref, id);
  return run;
}

/// Trying a stopped step again. The server puts it back to QUEUED with its attempts reset and the
/// sweeper picks it up; nothing is run inline (A5).
Future<AutomationStep> retryStep(WidgetRef ref, int id) async {
  final res = await ref.read(dioProvider).post('/api/automation/steps/$id/retry');
  final step = AutomationStep.fromJson((res.data as Map).cast<String, dynamic>());
  invalidateAutomation(ref);
  return step;
}

/// Rendering one template against one record, for the builder's live preview.
Future<RulePreview> previewTemplates(
  Dio dio, {
  required AutomationSubject subject,
  required int subjectId,
  String? title,
  String? subject0,
  String? body,
}) async {
  final res = await dio.post('/api/automation/preview', data: {
    'subjectType': subject.wire,
    'subjectId': subjectId,
    if (title != null && title.isNotEmpty) 'title': title,
    if (subject0 != null && subject0.isNotEmpty) 'subject': subject0,
    if (body != null && body.isNotEmpty) 'body': body,
  });
  return RulePreview.fromJson((res.data as Map).cast<String, dynamic>());
}

/// Everything a change to a rule or a step moves: the row it is in, the rule's own page and the
/// sidebar's stuck-step badge. The generic table providers are family-keyed on the whole request,
/// so they are invalidated wholesale — the same thing invalidateTasks does (A5).
void invalidateAutomation(WidgetRef ref, [int? ruleId]) {
  if (ruleId != null) ref.invalidate(ruleDetailProvider(ruleId));
  ref.invalidate(tablePageProvider);
  ref.invalidate(tableSummaryProvider);
  ref.invalidate(poisonedStepCountProvider);
}

/// The activity list, narrowed to one run. This is why there is no `GET /api/automation/runs`:
/// the run history IS the automationSteps table filtered by runId (A5).
String runActivityLocation(int runId) => RouteQuery.location('/automation/activity', {
      'f': ['runId:eq:$runId'],
      'sort': 'id,desc',
    });

String _day(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
