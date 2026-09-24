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

/// "May I attach or remove a document on a record in THIS branch."
///
/// DOCUMENT_MANAGE is a MANAGE-level privilege in the region partition, so holding it in one
/// branch says nothing about a record in another — and the server now asks the same question per
/// record. hasIn, not has: the global answer is "somewhere", which is the nav gate and not the
/// button gate. A null regionId is a record that does not say which branch it is in, and falls
/// back to the global answer (B1).
final canManageDocumentsInProvider = Provider.family<bool, int?>((ref, regionId) =>
    ref.watch(currentUserProvider)?.hasIn(Privileges.documentManage, regionId) ?? false);

/// The same question with no record in hand: may this person do it anywhere (B1).
final canManageDocumentsProvider =
    Provider<bool>((ref) => ref.watch(canManageDocumentsInProvider(null)));

/// Which record an upload control is about: its type, and the branch it lives in (B1).
typedef DocumentUploadTarget = ({DocumentEntityType type, int? regionId});

final canUploadDocumentsProvider =
    Provider.family<bool, DocumentUploadTarget>((ref, target) {
  final user = ref.watch(currentUserProvider);
  if (user == null || !user.hasIn(Privileges.documentManage, target.regionId)) return false;
  final needed =
      user.isCustomer ? target.type.recordViewPrivilege : target.type.recordManagePrivilege;
  return user.hasIn(needed, target.regionId);
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
    builder: (context) => DocumentsTab(
        type: type, entityId: entityId, entityLabel: entityLabel, regionId: regionId),
  );
}
