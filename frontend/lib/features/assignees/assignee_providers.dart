import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../email/email_entity.dart';
import '../email/email_models.dart';

typedef AssigneeContextKey = ({EmailEntityType type, int? entityId});

/// The roles a record of [type] can be assigned to, grouped by level with who holds each today —
/// the same shape the email form's To field is offered (§6.13). Without an entity id, the roles
/// alone. It answers live whatever the date (D9).
final assigneeContextProvider =
    FutureProvider.autoDispose.family<EmailContext, AssigneeContextKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/assignees/context', queryParameters: {
    'entityType': key.type.wire,
    if (key.entityId != null) 'entityId': key.entityId,
  });
  return EmailContext.fromJson((res.data as Map).cast<String, dynamic>());
});

/// Staff who may be assigned: those holding access in the regions asked about, or — given a
/// record — in that record's region (§6.13, SC-18). Never the whole directory.
Future<List<EmailPerson>> searchAssignablePeople(Dio dio, String search,
    {EmailEntityType? entityType, int? entityId}) async {
  final res = await dio.get('/api/assignees/people', queryParameters: {
    if (search.isNotEmpty) 'q': search,
    if (entityType != null && entityId != null) ...{
      'entityType': entityType.wire,
      'entityId': entityId,
    },
  });
  return (res.data as List)
      .whereType<Map>()
      .map((m) => EmailPerson.fromJson(m.cast<String, dynamic>()))
      .toList();
}
