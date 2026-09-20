import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import 'document_models.dart';
import 'document_providers.dart';
import 'documents_tab.dart';

export 'document_models.dart' show DocumentEntityType, DocumentItem, DocumentVisibility;

// The documents feature as the rest of the app sees it. Screens import only this file, so the
// tab, the upload form and their providers can change without touching every details page.

/// True when the signed-in user holds DOCUMENT_VIEW / DOCUMENT_MANAGE.
final canViewDocumentsProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.documentView) ?? false);
final canManageDocumentsProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.documentManage) ?? false);

/// Whether this caller may attach a file to a record of [type]: the document privilege **and**
/// the record's own manage privilege, because the endpoint asks for both (§4.5). A customer
/// login works on its own records with the record's view privilege instead — the one deliberate
/// exception, made so that a customer can send evidence of their own (§1, answer 4). Anyone else
/// is never shown a button that would 403 (AC-C22).
final canUploadDocumentsProvider = Provider.family<bool, DocumentEntityType>((ref, type) {
  final user = ref.watch(currentUserProvider);
  if (user == null || !user.has(Privileges.documentManage)) return false;
  return user.has(user.isCustomer ? type.recordViewPrivilege : type.recordManagePrivilege);
});

/// The Documents tab for DetailScaffold.tabs (slug 'documents', label 'Documents',
/// Icons.folder_outlined), badged with how many the record has (AC-C1). Null when the user lacks
/// DOCUMENT_VIEW, so callers write `if (tab != null) tab`.
DetailTab? documentsDetailTab(WidgetRef ref,
    {required DocumentEntityType type, required int entityId, String? entityLabel}) {
  if (!ref.watch(canViewDocumentsProvider)) return null;
  // Asked for with the page rather than with the tab, so the badge is there before anyone opens
  // it. A count that has not arrived, or could not be had, simply shows no badge.
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
