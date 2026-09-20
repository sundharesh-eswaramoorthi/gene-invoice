import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/product.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import 'products_screen.dart';

/// A product's facts, read-only, with its emails and history below. Edits go through the same
/// form as the list, so there is nothing unsaved to guard here (E15).
class ProductDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const ProductDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(productDetailProvider(id));
    final user = ref.watch(currentUserProvider);
    final canEdit = user?.has(Privileges.productManage) ?? false;
    final canViewAudit = user?.has(Privileges.auditView) ?? false;

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'product'),
        onBack: () => context.go('/products'),
      ),
      data: (product) {
        final label = 'Product ${product.name}';
        final send = sendEmailHeaderButton(context, ref,
            type: EmailEntityType.product, entityId: product.id, entityLabel: label);
        final email = emailDetailTab(ref,
            type: EmailEntityType.product, entityId: product.id, entityLabel: label);
        return DetailScaffold(
          title: product.name,
          onBack: () => goGuarded(context, '/products'),
          titleTrailing: [
            if (canEdit)
              OutlinedButton.icon(
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: () => openProductForm(context, ref, existing: product),
              ),
            if (send != null) send,
          ],
          initialTabSlug: initialTab,
          onTabChanged: (slug) => context.go('/products/$id?tab=$slug'),
          top: _top(product),
          tabs: [
            if (canViewAudit)
              DetailTab(
                slug: 'history',
                label: 'History',
                icon: Icons.history,
                builder: (context) => SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: AuditHistoryPanel(entityType: 'PRODUCT', entityId: product.id),
                ),
              ),
            if (email != null) email,
          ],
        );
      },
    );
  }

  Widget _top(Product p) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: DetailGrid(items: [
          DetailGridItem(label: 'Name', child: ReadOnlyValue(p.name)),
          DetailGridItem(label: 'Description', span: 2, child: ReadOnlyValue(p.description ?? '')),
          DetailGridItem(label: 'Price', child: ReadOnlyValue(formatMoney(p.price))),
          DetailGridItem(label: 'Active', child: ReadOnlyValue(p.active ? 'Yes' : 'No')),
          if (p.createdAt != null)
            DetailGridItem(label: 'Created', child: ReadOnlyValue(formatDateTime(p.createdAt))),
        ]),
      );
}
