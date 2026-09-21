import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/assignee.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/task.dart';
import '../auth/auth_controller.dart';
import '../email/email_providers.dart';

/// One record a task hangs off, for the things asked per record.
typedef TaskRecordKey = ({TaskEntityType type, int entityId});

/// A record, or a record *type* with no record yet: an automation rule's assignees are picked
/// before any record exists, so the options are asked for by type alone.
typedef AssigneeOptionsKey = ({TaskEntityType type, int? entityId});

/// One task, for the details page and for the edit form — which needs the assignees resolved as
/// they stand now, not as the list page happened to render them.
final taskDetailProvider = FutureProvider.autoDispose.family<Task, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks/$id');
  return Task.fromJson((res.data as Map).cast<String, dynamic>());
});

/// How much live work a record has, for the tab's badge — asked with the page rather than with
/// the tab, so a details page says there is something to do before anyone opens it (the same
/// bargain the Documents tab makes). Only what is still live counts: a done or cancelled task is
/// off the list, and a badge that kept counting it would never go down.
final taskOpenCountProvider =
    FutureProvider.autoDispose.family<int, TaskRecordKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/tasks/count', queryParameters: {
    'entityType': key.type.wire,
    'entityId': key.entityId,
  });
  return ((res.data as Map)['open'] as num?)?.toInt() ?? 0;
});

/// The roles `AssigneePickerField` offers, grouped by level and resolved against the record when
/// there is one (A2).
///
/// This is the email compose context, not an endpoint of its own. `GET /api/emails/context`
/// already answers exactly the question an assignee picker asks — which seats this kind of record
/// offers, at which levels, under what heading, and who holds each one right now — and it already
/// answers it for a type with no record, which is what an automation rule needs. A second
/// endpoint would be a second place for the answer to drift from the To field's (A1, A4), and
/// watching the one provider means a compose form and a task form open on the same record share
/// the one fetch.
///
/// It is watched rather than read, so the groups follow the record: reassign the POC and the
/// chips say who it reaches now.
final assigneeRoleOptionsProvider = FutureProvider.autoDispose
    .family<List<EmailRoleGroup>, AssigneeOptionsKey>((ref, key) async {
  final context = await ref.watch(emailContextProvider(
      (type: key.type.emailType, entityId: key.entityId, event: null)).future);
  return context.roleGroups;
});

/// Internal users matching [search], for the picker's "Person…" chip.
///
/// The compose form's own people search, read into [AssigneePerson]: the endpoint names the
/// address `email` where an assignee names it `address`, which that model reads either way, so
/// the chip for somebody just picked reads exactly like the chip for a role's holder.
Future<List<AssigneePerson>> searchAssigneePeople(Dio dio, String search) async {
  final res = await dio.get('/api/emails/people', queryParameters: {'q': search});
  return (res.data as List)
      .cast<Map>()
      .map((m) => AssigneePerson.fromJson(m.cast<String, dynamic>()))
      .toList();
}

/// Whether this caller may work the assignee picker at all.
///
/// Both halves of it — the role options and the people search — are email endpoints, and both ask
/// for EMAIL_SEND. Every seeded role that holds TASK_MANAGE holds EMAIL_SEND too, so in practice
/// anyone who can raise a task can pick who it is for; a hand-made role with one and not the
/// other would be shown a picker that could only 403, so it is given a form without one instead.
/// A task nobody is assigned is a task nobody has picked up, which is allowed.
final canPickAssigneesProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  return user != null && !user.isCustomer && user.has(Privileges.emailSend);
});
