import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../shared/models/customer.dart';
import '../customers/customers_screen.dart';

/// Global "filter all list views by this customer" selector for admin/cashier.
/// When null → no filter (show everything the caller is allowed to see).
final customerScopeProvider = StateProvider<Customer?>((ref) => null);

class CustomerScopePicker extends ConsumerWidget {
  const CustomerScopePicker({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scope = ref.watch(customerScopeProvider);
    final async = ref.watch(customersProvider);

    return PopupMenuButton<Customer?>(
      tooltip: 'Filter by customer',
      position: PopupMenuPosition.under,
      onSelected: (c) => ref.read(customerScopeProvider.notifier).state = c,
      itemBuilder: (_) {
        final entries = <PopupMenuEntry<Customer?>>[
          PopupMenuItem<Customer?>(
            value: null,
            // PopupMenuButton.onSelected swallows null returns (treats them as
            // a dismissal), so reset the scope here instead.
            onTap: () => ref.read(customerScopeProvider.notifier).state = null,
            child: const ListTile(
              leading: Icon(Icons.public),
              title: Text('All customers'),
            ),
          ),
          const PopupMenuDivider(),
        ];
        async.whenData((list) {
          for (final c in list) {
            entries.add(PopupMenuItem<Customer?>(
              value: c,
              child: ListTile(
                leading: const Icon(Icons.person_outline),
                title: Text(c.name),
                subtitle: c.username != null ? Text('@${c.username}') : null,
              ),
            ));
          }
        });
        return entries;
      },
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 4),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: scope != null
              ? Theme.of(context).colorScheme.primaryContainer
              : Colors.transparent,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.filter_alt_outlined, size: 18),
            const SizedBox(width: 6),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 180),
              child: Text(
                scope == null ? 'All customers' : scope.name,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w500),
              ),
            ),
            const Icon(Icons.arrow_drop_down),
          ],
        ),
      ),
    );
  }
}
