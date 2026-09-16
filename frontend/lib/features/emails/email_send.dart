import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import 'email_compose_dialog.dart';
import 'email_providers.dart';

/// Opens the shared compose dialog for one record and sends through its scoped endpoint.
/// Reports the stored-Email outcome: created, or the zero-recipient explanation (FAIL3).
Future<void> sendEmailForRecord(
  BuildContext context,
  WidgetRef ref, {
  required String recordType, // 'customers' | 'invoices'
  required int recordId,
  required String recordLabel,
}) async {
  final draft = await showEmailComposeDialog(context, contextLabel: recordLabel);
  if (draft == null || !context.mounted) return;
  try {
    final res = await ref
        .read(dioProvider)
        .post('/api/$recordType/$recordId/emails', data: draft);
    final data = res.data as Map<String, dynamic>;
    final created = data['created'] == true;
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(created
              ? 'Email recorded'
              : (data['message'] as String? ?? 'No Email was created'))));
    }
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  } finally {
    invalidateEmailViews(
        ref,
        customerId: recordType == 'customers' ? recordId : null,
        invoiceId: recordType == 'invoices' ? recordId : null);
  }
}
