import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_providers.dart';
import 'email_models.dart';

/// The From / To choices, loaded once while a compose dialog is open.
final emailOptionsProvider = FutureProvider.autoDispose<EmailOptions>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/emails/options');
  return EmailOptions.fromJson(res.data as Map<String, dynamic>);
});

/// The Customer Email tab's roll-up (own Emails plus its invoices'), newest first.
final customerEmailsProvider =
    FutureProvider.autoDispose.family<List<EmailRecordSummary>, int>((ref, customerId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/customers/$customerId/emails');
  return (res.data as List)
      .cast<Map<String, dynamic>>()
      .map(EmailRecordSummary.fromJson)
      .toList();
});

/// The Invoice Email tab: only Emails linked to that exact invoice.
final invoiceEmailsProvider =
    FutureProvider.autoDispose.family<List<EmailRecordSummary>, int>((ref, invoiceId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/invoices/$invoiceId/emails');
  return (res.data as List)
      .cast<Map<String, dynamic>>()
      .map(EmailRecordSummary.fromJson)
      .toList();
});

/// After anything Email-shaped changed, refresh the tabs, the Inbox table and the detail.
void invalidateEmailViews(WidgetRef ref, {int? customerId, int? invoiceId}) {
  if (customerId != null) ref.invalidate(customerEmailsProvider(customerId));
  if (invoiceId != null) ref.invalidate(invoiceEmailsProvider(invoiceId));
  // Blanket-invalidate the table stack so the Inbox picks up read-state changes.
  ref.invalidate(tablePageProvider);
}

