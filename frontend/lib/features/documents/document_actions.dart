import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import 'document_models.dart';
import 'document_providers.dart';
import 'documents_tab.dart';

export 'document_models.dart' show DocumentEntityType, DocumentItem, DocumentVisibility;

final canViewDocumentsProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.documentView) ?? false);
final canManageDocumentsProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.documentManage) ?? false);

final canUploadDocumentsProvider = Provider.family<bool, DocumentEntityType>((ref, type) {
  final user = ref.watch(currentUserProvider);
  if (user == null || !user.has(Privileges.documentManage)) return false;
  return user.has(user.isCustomer ? type.recordViewPrivilege : type.recordManagePrivilege);
});

DetailTab? documentsDetailTab(WidgetRef ref,
    {required DocumentEntityType type, required int entityId, String? entityLabel}) {
  if (!ref.watch(canViewDocumentsProvider)) return null;
  final count = ref.watch(documentCountProvider((type: type, entityId: entityId))).valueOrNull;
  return DetailTab(
    slug: 'documents',
    label: 'Documents',
    icon: Icons.folder_outlined,
    badgeCount: count,
    builder: (context) =>
        DocumentsTab(type: type, entityId: entityId, entityLabel: entityLabel),
  );
}
