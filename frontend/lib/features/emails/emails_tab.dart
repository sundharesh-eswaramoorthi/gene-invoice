import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import 'email_detail_dialog.dart';
import 'email_models.dart';
import 'email_providers.dart';

/// The Email tab shared by Customer and Invoice Details. For a customer it shows the roll-up
/// (its own Emails plus its invoices', invoice-number-labelled); for an invoice, only its own.
/// Both are newest first, straight from the scoped list endpoints.
class EmailsTab extends ConsumerWidget {
  final int customerId;
  final int invoiceId;

  const EmailsTab({super.key, this.customerId = 0, this.invoiceId = 0});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = invoiceId != 0
        ? ref.watch(invoiceEmailsProvider(invoiceId))
        : ref.watch(customerEmailsProvider(customerId));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Could not load emails: $e')),
      data: (emails) {
        if (emails.isEmpty) {
          return const Center(child: Text('No emails yet'));
        }
        return RefreshIndicator(
          onRefresh: () async => invalidateEmailViews(ref,
              customerId: customerId != 0 ? customerId : null,
              invoiceId: invoiceId != 0 ? invoiceId : null),
          child: ListView.separated(
            padding: const EdgeInsets.all(12),
            itemCount: emails.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, i) => _row(context, ref, emails[i]),
          ),
        );
      },
    );
  }

  Widget _row(BuildContext context, WidgetRef ref, EmailRecordSummary e) {
    return ListTile(
      leading: const Icon(Icons.email_outlined),
      // Newest first is the list's own order; nothing here re-sorts it.
      title: Text(e.subject, style: const TextStyle(fontWeight: FontWeight.w600)),
      subtitle: Text('From ${e.senderDisplay} • ${formatDateTime(e.sentAt)}'),
      trailing: e.link == 'INVOICE' && e.invoiceNumber != null
          ? Chip(
              label: Text(e.invoiceNumber!),
              visualDensity: VisualDensity.compact,
            )
          : null,
      onTap: () => showEmailDetailDialog(context, ref, e.id,
          customerId: customerId != 0 ? customerId : null,
          invoiceId: invoiceId != 0 ? invoiceId : null),
    );
  }
}
