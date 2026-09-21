import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';
import '../notifications/notifications_providers.dart' show pollUnreadCount;
import 'email_entity.dart';
import 'email_models.dart';

typedef EmailContextKey = ({EmailEntityType type, int? entityId, EmailEvent? event});

final emailContextProvider =
    FutureProvider.autoDispose.family<EmailContext, EmailContextKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/context', queryParameters: {
    'entityType': key.type.wire,
    if (key.entityId != null) 'entityId': key.entityId,
    if (key.event != null) 'event': key.event!.wire,
    'utcOffsetMinutes': DateTime.now().timeZoneOffset.inMinutes,
  });
  return EmailContext.fromJson((res.data as Map).cast<String, dynamic>());
});

typedef EntityEmailsKey = ({EmailEntityType type, int entityId, int page, int size});

final entityEmailsProvider = FutureProvider.autoDispose
    .family<PagedResult<EmailMessage>, EntityEmailsKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails', queryParameters: {
    'entityType': key.type.wire,
    'entityId': key.entityId,
    'page': key.page,
    'size': key.size,
    'sort': 'occurredAt,desc',
  });
  return PagedResult.fromJson((res.data as Map).cast<String, dynamic>())
      .map(EmailMessage.fromJson);
});

final emailDetailProvider =
    FutureProvider.autoDispose.family<EmailMessage, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/$id');
  return EmailMessage.fromJson((res.data as Map).cast<String, dynamic>());
});

final inboxUnreadCountProvider = StreamProvider.autoDispose<int>((ref) {
  final userId = ref.watch(currentUserProvider.select((u) => u?.id));
  // Signing out changes that to nobody while the rail's badge still watches. There is no count to
  // ask for then, and asking without a token only earns a 401 that signs out again (D-70).
  if (userId == null) return const Stream.empty();
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/inbox/unread-count');
});

final canConnectGmailProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  return user != null && !user.isCustomer && user.has(Privileges.emailSend);
});

final emailDeliveryProvider = FutureProvider.autoDispose<EmailDelivery>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/delivery');
  return EmailDelivery.fromJson((res.data as Map).cast<String, dynamic>());
});

final myGmailProvider = FutureProvider.autoDispose<GmailConnection>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/me/gmail');
  return GmailConnection.fromJson((res.data as Map).cast<String, dynamic>());
});

final userGmailProvider =
    FutureProvider.autoDispose.family<GmailConnection, int>((ref, userId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/users/$userId/gmail');
  return GmailConnection.fromJson((res.data as Map).cast<String, dynamic>());
});

void invalidateGmailStatus(ProviderContainer container) {
  container.invalidate(myGmailProvider);
  container.invalidate(userGmailProvider);
  container.invalidate(emailDeliveryProvider);
  container.invalidate(emailContextProvider);
}

Future<List<EmailPerson>> searchEmailPeople(Dio dio, String search) async {
  final res = await dio.get('/api/emails/people', queryParameters: {'q': search});
  return (res.data as List)
      .cast<Map>()
      .map((m) => EmailPerson.fromJson(m.cast<String, dynamic>()))
      .toList();
}

Future<List<Map<String, dynamic>>> searchEmailRecords(
    Dio dio, EmailEntityType type, String search) async {
  final filters = type.searchFilters(search);
  final res = await dio.get(type.apiPath, queryParameters: {
    'size': 20,
    'sort': type.pickerSort,
    if (filters.isNotEmpty) 'filter': filters,
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map>()
      .map((m) => m.cast<String, dynamic>())
      .toList();
}
