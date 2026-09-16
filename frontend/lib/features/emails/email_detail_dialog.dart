import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import 'email_models.dart';
import 'email_providers.dart';

/// The complete stored Email, shared by the Inbox and both record tabs. The GET itself marks the
/// Email read for a logged-in recipient (FR16), so the views it feeds are refreshed afterwards.
Future<void> showEmailDetailDialog(
  BuildContext context,
  WidgetRef ref,
  int emailId, {
  int? customerId,
  int? invoiceId,
}) async {
  EmailDetail detail;
  try {
    final res = await ref.read(dioProvider).get('/api/emails/$emailId');
    detail = EmailDetail.fromJson(res.data as Map<String, dynamic>);
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
    return;
  }
  // The open above may have flipped the caller's read state; every Email view reflects it.
  invalidateEmailViews(ref, customerId: customerId, invoiceId: invoiceId);
  if (!context.mounted) return;
  await showDialog<void>(
    context: context,
    builder: (_) => AlertDialog(
      title: Text(detail.subject),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _fact(context, 'From',
                  detail.senderAddress == null
                      ? detail.senderDisplay
                      : '${detail.senderDisplay} <${detail.senderAddress}>'),
              _fact(context, 'Sent by', detail.sentByDisplay ?? '—'),
              _fact(context, 'Sent at', formatDateTime(detail.sentAt)),
              if (detail.invoiceNumber != null)
                _fact(context, 'Invoice', detail.invoiceNumber!),
              _fact(context, 'To',
                  detail.recipients.map((r) => r.describe).join(', ')),
              const Divider(height: 24),
              Text(detail.body?.isEmpty ?? true ? '—' : detail.body!),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close')),
      ],
    ),
  );
}

Widget _fact(BuildContext context, String label, String value) {
  return Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 90,
          child: Text(label, style: Theme.of(context).textTheme.labelLarge),
        ),
        Expanded(child: Text(value)),
      ],
    ),
  );
}
