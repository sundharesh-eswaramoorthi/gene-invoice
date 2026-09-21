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

/// What the compose form offers for a record, or for a type before a record is chosen.
final emailContextProvider =
    FutureProvider.autoDispose.family<EmailContext, EmailContextKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/context', queryParameters: {
    'entityType': key.type.wire,
    if (key.entityId != null) 'entityId': key.entityId,
    if (key.event != null) 'event': key.event!.wire,
    // The suggestion's dates are written on the server, which would otherwise use the UTC day:
    // a record made just after midnight here would read as yesterday's in the email, while the
    // app shows today's.
    'utcOffsetMinutes': DateTime.now().timeZoneOffset.inMinutes,
  });
  return EmailContext.fromJson((res.data as Map).cast<String, dynamic>());
});

/// One record, for the things the compose form asks about only once a record is known.
typedef EmailRecordKey = ({EmailEntityType type, int entityId});

/// The documents already on this record or on its customer, for attaching without uploading them
/// again (E17). Watched by the attach picker alone rather than by the form: a compose form is
/// opened far more often than a file is attached, and an autoDispose family drops the answer as
/// soon as that picker closes, so the next one sees anything uploaded meanwhile.
final emailAttachableProvider =
    FutureProvider.autoDispose.family<List<EmailAttachable>, EmailRecordKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/attachable',
      queryParameters: {'entityType': key.type.wire, 'entityId': key.entityId});
  return (res.data as List)
      .cast<Map>()
      .map((m) => EmailAttachable.fromJson(m.cast<String, dynamic>()))
      .toList();
});

/// The placeholders this record offers, each with what it says on this very record (M4). Loaded
/// when the "Insert field" list is opened, for the same reason as [emailAttachableProvider].
final emailPlaceholdersProvider =
    FutureProvider.autoDispose.family<List<EmailPlaceholderGroup>, EmailRecordKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/placeholders',
      queryParameters: {'entityType': key.type.wire, 'entityId': key.entityId});
  return (res.data as List)
      .cast<Map>()
      .map((m) => EmailPlaceholderGroup.fromJson(m.cast<String, dynamic>()))
      .toList();
});

typedef EntityEmailsKey = ({EmailEntityType type, int entityId, int page, int size});

/// One page of a record's emails, newest first. The Email tab watches as many pages as it has
/// shown, so invalidating the family after a send refreshes all of them together.
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

/// The Inbox badge. Polls like the notification bell, so mail that arrives through the mailbox
/// sync shows up without a reload.
final inboxUnreadCountProvider = StreamProvider.autoDispose<int>((ref) {
  // The count is the signed-in user's own; the next user to sign in must not inherit it.
  final userId = ref.watch(currentUserProvider.select((u) => u?.id));
  // Signing out changes that to nobody while the rail's badge still watches. There is no count to
  // ask for then, and asking without a token only earns a 401 that signs out again (D-70).
  if (userId == null) return const Stream.empty();
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/inbox/unread-count');
});

/// Whether the signed-in user has a Gmail of their own to connect: staff who send email
/// (mail-service.md §5.6). Customer logins, and anyone without EMAIL_SEND, are refused
/// `/api/me/gmail`, so nothing offers them the Gmail connection page.
final canConnectGmailProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  return user != null && !user.isCustomer && user.has(Privileges.emailSend);
});

/// Whether mail leaves the app at all, and the caller's own Gmail connection (the Inbox banner).
final emailDeliveryProvider = FutureProvider.autoDispose<EmailDelivery>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/delivery');
  return EmailDelivery.fromJson((res.data as Map).cast<String, dynamic>());
});

/// The signed-in user's own Gmail connection, as the mail service knows it now (or the app's
/// last copy, with `serviceError`, when the service cannot be reached).
final myGmailProvider = FutureProvider.autoDispose<GmailConnection>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/me/gmail');
  return GmailConnection.fromJson((res.data as Map).cast<String, dynamic>());
});

/// Another user's Gmail connection, from the app's copy (the user details page).
final userGmailProvider =
    FutureProvider.autoDispose.family<GmailConnection, int>((ref, userId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/users/$userId/gmail');
  return GmailConnection.fromJson((res.data as Map).cast<String, dynamic>());
});

/// After a connect or a disconnect: everything that shows someone's Gmail status asks again.
void invalidateGmailStatus(ProviderContainer container) {
  container.invalidate(myGmailProvider);
  container.invalidate(userGmailProvider);
  container.invalidate(emailDeliveryProvider);
  container.invalidate(emailContextProvider);
}

/// Active internal users matching [search], for the From and To people pickers. Customer logins
/// are refused by the server and never offered the search.
Future<List<EmailPerson>> searchEmailPeople(Dio dio, String search) async {
  final res = await dio.get('/api/emails/people', queryParameters: {'q': search});
  return (res.data as List)
      .cast<Map>()
      .map((m) => EmailPerson.fromJson(m.cast<String, dynamic>()))
      .toList();
}

/// Records of [type] matching [search], for the compose dialog opened from a list page.
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
