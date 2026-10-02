import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/scope/view_scope.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import 'document_models.dart';
import 'document_providers.dart';
import 'documents_tab.dart';

export 'document_models.dart' show DocumentEntityType, DocumentItem, DocumentVisibility;

final canViewDocumentsProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.documentView) ?? false);
// Uploading and managing are writes, and a past date is read only (D2).
final canManageDocumentsProvider = Provider<bool>((ref) =>
    !ref.watch(isHistoricalProvider) &&
    (ref.watch(currentUserProvider)?.has(Privileges.documentManage) ?? false));

final canUploadDocumentsProvider = Provider.family<bool, DocumentEntityType>((ref, type) {
  if (ref.watch(isHistoricalProvider)) return false;
  final user = ref.watch(currentUserProvider);
  if (user == null || !user.has(Privileges.documentManage)) return false;
  return user.has(user.isCustomer ? type.recordViewPrivilege : type.recordManagePrivilege);
});

/// Uploading to one record needs the document and record privileges in that record's region
/// (§7.5); the server checks them again. With no region known the flat privileges answer.
final canUploadDocumentsInProvider =
    Provider.family<bool, ({DocumentEntityType type, int? regionId})>((ref, key) {
  if (!ref.watch(canUploadDocumentsProvider(key.type))) return false;
  final user = ref.watch(currentUserProvider);
  if (key.regionId == null || user == null || user.isCustomer) return true;
  return user.hasIn(Privileges.documentManage, key.regionId) &&
      user.hasIn(key.type.recordManagePrivilege, key.regionId);
});

DetailTab? documentsDetailTab(WidgetRef ref,
    {required DocumentEntityType type,
    required int entityId,
    String? entityLabel,
    int? regionId}) {
  if (!ref.watch(canViewDocumentsProvider)) return null;
  final count = ref.watch(documentCountProvider((type: type, entityId: entityId))).valueOrNull;
  return DetailTab(
    slug: 'documents',
    label: 'Documents',
    icon: Icons.folder_outlined,
    badgeCount: count,
    builder: (context) =>
        DocumentsTab(type: type, entityId: entityId, entityLabel: entityLabel, regionId: regionId),
  );
}
