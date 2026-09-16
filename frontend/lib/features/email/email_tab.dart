import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/email.dart';
import 'email_providers.dart';
import 'email_view.dart';

/// The Email tab on Customer and Invoice details: the record's emails, newest first, a page at a
/// time. A customer's tab also lists the emails about its invoices, each labelled with the number.
class EmailTab extends ConsumerStatefulWidget {
  final EmailTargetType type;
  final int id;

  const EmailTab({super.key, required this.type, required this.id});

  @override
  ConsumerState<EmailTab> createState() => _EmailTabState();
}

class _EmailTabState extends ConsumerState<EmailTab> {
  int _page = 0;

  EmailTabRequest get _request => EmailTabRequest(type: widget.type, id: widget.id, page: _page);

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(emailTabProvider(_request));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
              const SizedBox(height: 8),
              Text(apiErrorMessage(e), textAlign: TextAlign.center),
              TextButton(
                onPressed: () => ref.invalidate(emailTabProvider(_request)),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      ),
      data: (page) {
        if (page.isEmpty && page.totalElements == 0) {
          return Center(
            child: Text(widget.type == EmailTargetType.customer
                ? 'No emails about this customer or its invoices yet'
                : 'No emails about this invoice yet'),
          );
        }
        return Column(
          children: [
            Expanded(
              child: ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: page.content.length,
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (context, i) {
                  final email = page.content[i];
                  return Card(
                    margin: EdgeInsets.zero,
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: EmailDetails(
                        email: email,
                        about: widget.type == EmailTargetType.customer
                            ? EmailAbout.invoicesOnly
                            : EmailAbout.hidden,
                        onOpenAbout: widget.type == EmailTargetType.customer
                            ? (e) => goGuarded(context, '/invoices/${e.invoiceId}')
                            : null,
                      ),
                    ),
                  );
                },
              ),
            ),
            if (page.totalPages > 1)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Text('${page.firstRowNumber}–${page.lastRowNumber} of ${page.totalElements}'),
                    IconButton(
                      tooltip: 'Newer emails',
                      icon: const Icon(Icons.chevron_left),
                      onPressed: _page == 0 ? null : () => setState(() => _page--),
                    ),
                    IconButton(
                      tooltip: 'Older emails',
                      icon: const Icon(Icons.chevron_right),
                      onPressed: _page + 1 >= page.totalPages ? null : () => setState(() => _page++),
                    ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}
