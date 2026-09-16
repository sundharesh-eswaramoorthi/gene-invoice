import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../shared/models/email.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import 'email_providers.dart';
import 'email_view.dart';

/// One email opened from the Inbox. Opening it marks it read for this user only.
class InboxEmailScreen extends ConsumerWidget {
  final int id;
  const InboxEmailScreen({super.key, required this.id});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final async = ref.watch(openedInboxEmailProvider(id));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'email'),
        onBack: () => context.go('/inbox'),
      ),
      data: (item) {
        final email = item.email;
        final canOpenAbout = email.invoiceId != null
            ? (user?.has(Privileges.invoiceView) ?? false)
            : (user?.has(Privileges.customerView) ?? false);
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(8, 12, 16, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextButton.icon(
                icon: const Icon(Icons.arrow_back),
                label: const Text('Inbox'),
                onPressed: () => context.go('/inbox'),
              ),
              Padding(
                padding: const EdgeInsets.only(left: 8, top: 4),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 900),
                  child: EmailDetails(
                    email: email,
                    onOpenAbout: canOpenAbout ? (e) => context.go(_aboutPath(e)) : null,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  static String _aboutPath(EmailMessage e) =>
      e.invoiceId != null ? '/invoices/${e.invoiceId}' : '/customers/${e.customerId}';
}
