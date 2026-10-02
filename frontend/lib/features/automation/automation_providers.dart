import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import 'automation_models.dart';

// A rule by id answers as of the date viewed — the revision in force then (§5.10.10) — like any
// record by id (I3): the date reloads it, the regions do not.
final ruleDetailProvider = FutureProvider.autoDispose.family<Rule, int>((ref, id) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/rules/$id');
  checkAsOf(res, asOf);
  return Rule.fromJson((res.data as Map).cast<String, dynamic>());
});

/// The placeholders a text about [entityType] may use (A4). Configuration: it answers live, so
/// neither the date nor the regions reload it.
final placeholdersProvider =
    FutureProvider.autoDispose.family<PlaceholderCatalogue, String>((ref, entityType) async {
  final dio = ref.watch(dioProvider);
  final res =
      await dio.get('/api/automation/placeholders', queryParameters: {'entityType': entityType});
  return PlaceholderCatalogue.fromJson((res.data as Map).cast<String, dynamic>());
});

/// One run, as of the date viewed (its `statusAt` then).
final runDetailProvider = FutureProvider.autoDispose.family<RunSummary, int>((ref, id) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/runs/$id');
  checkAsOf(res, asOf);
  return RunSummary.fromJson((res.data as Map).cast<String, dynamic>());
});

typedef RunEffectsKey = ({int runId, int page});

/// A page of what a run did, record by record, as of the date viewed.
final runEffectsProvider =
    FutureProvider.autoDispose.family<PagedResult<Effect>, RunEffectsKey>((ref, key) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/runs/${key.runId}/effects',
      queryParameters: {'page': key.page, 'size': 50});
  checkAsOf(res, asOf);
  return PagedResult.fromJson((res.data as Map).cast<String, dynamic>()).map(Effect.fromJson);
});

/// How the consumer is keeping up. Only an administrator may ask (§6.14), so for anyone else it
/// asks nothing at all.
final automationHealthProvider = FutureProvider.autoDispose<AutomationHealth?>((ref) async {
  final admin = ref.watch(currentUserProvider
      .select((u) => u != null && !u.isCustomer && u.has(Privileges.automationAdmin)));
  if (!admin) return null;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/automation/health');
  return AutomationHealth.fromJson((res.data as Map).cast<String, dynamic>());
});

/// After a rule is saved, switched, run or deleted: everything that shows one. It takes the
/// container, as it runs once a request has come back and the row that asked may have gone.
void rulesChanged(ProviderContainer container, {int? id}) {
  if (id != null) container.invalidate(ruleDetailProvider(id));
  container.invalidate(tablePageProvider);
  container.invalidate(tableSummaryProvider);
  container.invalidate(auditHistoryProvider);
  container.invalidate(automationHealthProvider);
}

/// Whether [error] is the server asking the caller to confirm taking a rule over (§5.10.7).
bool asksToTakeOwnership(Object error) {
  if (error is! DioException || error.response?.statusCode != 400) return false;
  final data = error.response?.data;
  final fields = data is Map ? data['fieldErrors'] : null;
  return fields is Map && fields.containsKey('confirmOwnershipChange');
}

/// The `fieldErrors` of a 400, keyed by their paths (`name`, `trigger.time`,
/// `condition.children[1].filter`, …).
Map<String, String> ruleFieldErrors(Object error) {
  final data = error is DioException ? error.response?.data : null;
  final fields = data is Map ? data['fieldErrors'] : null;
  if (fields is! Map) return const {};
  return {for (final e in fields.entries) '${e.key}': '${e.value}'};
}

Map<String, dynamic> _json(Response res) => (res.data as Map).cast<String, dynamic>();

Future<Rule> enableRule(Dio dio, int id, {bool confirmOwnershipChange = false}) async =>
    Rule.fromJson(_json(await dio.post('/api/automation/rules/$id/enable', data: {
      if (confirmOwnershipChange) 'confirmOwnershipChange': true,
    })));

Future<Rule> disableRule(Dio dio, int id) async =>
    Rule.fromJson(_json(await dio.post('/api/automation/rules/$id/disable')));

/// Resumes a suspended rule; [replay] also acts on what changed while it was suspended. The
/// answer says how many such changes there were.
Future<({Rule rule, int skippedWhileSuspended})> resumeRule(Dio dio, int id,
    {required bool replay, bool confirmOwnershipChange = false}) async {
  final json = _json(await dio.post('/api/automation/rules/$id/resume', data: {
    'replay': replay,
    if (confirmOwnershipChange) 'confirmOwnershipChange': true,
  }));
  return (
    rule: Rule.fromJson(json),
    skippedWhileSuspended: (json['skippedWhileSuspended'] as num?)?.toInt() ?? 0,
  );
}

Future<void> deleteRule(Dio dio, int id) => dio.delete('/api/automation/rules/$id');

/// Queues a run by hand (§5.10.9): over everything the condition matches, or [recordIds]; now, or
/// at [runAt]. A 202 is a new run, a 200 the identical one already waiting — both are queued, and
/// neither is a held change.
Future<QueuedRun> runRuleNow(Dio dio, int id,
    {List<int>? recordIds, DateTime? runAt, bool confirmOwnershipChange = false}) async {
  final res = await dio.post('/api/automation/rules/$id/run', data: {
    if (recordIds != null && recordIds.isNotEmpty) 'recordIds': recordIds,
    if (runAt != null) 'runAt': runAt.toUtc().toIso8601String(),
    if (confirmOwnershipChange) 'confirmOwnershipChange': true,
  });
  return QueuedRun.fromJson(_json(res));
}

/// Puts a dead event back in the queue (AUTOMATION_ADMIN).
Future<void> requeueEvent(Dio dio, int eventId) =>
    dio.post('/api/automation/events/$eventId/requeue');
