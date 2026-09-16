import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_models.dart';
import '../../shared/models/email.dart';

/// One page of a record's Email tab: a customer's (with its invoices') or one invoice's.
@immutable
class EmailTabRequest {
  final EmailTargetType type;
  final int id;
  final int page;
  final int size;

  const EmailTabRequest({required this.type, required this.id, this.page = 0, this.size = 20});

  EmailTabRequest atPage(int p) => EmailTabRequest(type: type, id: id, page: p, size: size);

  @override
  bool operator ==(Object other) =>
      other is EmailTabRequest &&
      other.type == type &&
      other.id == id &&
      other.page == page &&
      other.size == size;

  @override
  int get hashCode => Object.hash(type, id, page, size);
}

final emailTabProvider = FutureProvider.autoDispose
    .family<PagedResult<EmailMessage>, EmailTabRequest>((ref, req) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails', queryParameters: {
    req.type == EmailTargetType.customer ? 'customerId' : 'invoiceId': req.id,
    'page': req.page,
    'size': req.size,
  });
  return PagedResult.fromJson(res.data as Map<String, dynamic>).map(EmailMessage.fromJson);
});

/// Opening an email in the Inbox marks it read for this user, so the one request does both.
final openedInboxEmailProvider =
    FutureProvider.autoDispose.family<InboxItem, int>((ref, emailId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.post('/api/inbox/$emailId/read');
  return InboxItem.fromJson(res.data as Map<String, dynamic>);
});

final emailRoleOptionsProvider = FutureProvider.autoDispose<List<RoleOption>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/roles');
  return (res.data as List).cast<Map<String, dynamic>>().map(RoleOption.fromJson).toList();
});

@immutable
class EmailAddressesRequest {
  final EmailTargetType type;
  final int id;
  const EmailAddressesRequest(this.type, this.id);

  @override
  bool operator ==(Object other) => other is EmailAddressesRequest && other.type == type && other.id == id;

  @override
  int get hashCode => Object.hash(type, id);
}

final emailAddressesProvider =
    FutureProvider.autoDispose.family<EmailAddresses, EmailAddressesRequest>((ref, req) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/addresses', queryParameters: {
    req.type == EmailTargetType.customer ? 'customerId' : 'invoiceId': req.id,
  });
  return EmailAddresses.fromJson(res.data as Map<String, dynamic>);
});
