import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/table/table_models.dart';
import '../audit/audit_history_panel.dart';
import 'document_files.dart';
import 'document_models.dart';

typedef DocumentListKey = ({DocumentEntityType type, int entityId, int page, int size});
typedef DocumentRecordKey = ({DocumentEntityType type, int entityId});

/// One page of a record's documents, newest first (AC-C2). The tab watches as many pages as it
/// has shown, so invalidating the family after an upload refreshes all of them together.
final entityDocumentsProvider = FutureProvider.autoDispose
    .family<PagedResult<DocumentItem>, DocumentListKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/documents', queryParameters: {
    'entityType': key.type.wire,
    'entityId': key.entityId,
    'page': key.page,
    'size': key.size,
  });
  return PagedResult.fromJson((res.data as Map).cast<String, dynamic>())
      .map(DocumentItem.fromJson);
});

/// How many documents a record has, for the tab's badge — asked without opening the tab, so the
/// page says there is something there before anyone looks (AC-C1).
final documentCountProvider =
    FutureProvider.autoDispose.family<int, DocumentRecordKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/documents/count', queryParameters: {
    'entityType': key.type.wire,
    'entityId': key.entityId,
  });
  return ((res.data as Map)['count'] as num?)?.toInt() ?? 0;
});

/// Where a file to upload comes from: the browser's own chooser (document_files.dart). Null in a
/// build that has none, so the form says so rather than offering a button that does nothing. A
/// test hands over a file instead of opening a chooser.
typedef DocumentPicker = Future<PickedDocument?> Function();

final documentPickerProvider =
    Provider<DocumentPicker?>((ref) => canPickDocumentFiles ? pickDocumentFile : null);

/// How a downloaded document reaches the viewer: the browser saves it, and away from the web
/// nothing can (document_files.dart). A test overrides this to see what it was handed.
typedef DocumentSaver = Future<bool> Function(DownloadedDocument file);

final documentSaverProvider = Provider<DocumentSaver>((ref) => saveDocumentFile);

/// After an upload, an edit or a delete: the list, the tab's count and the record's History all
/// ask again (AC-C4). Takes the container rather than a ref, so a form already closed by the
/// time its request answers still refreshes what is on screen.
void invalidateDocuments(ProviderContainer container) {
  container.invalidate(entityDocumentsProvider);
  container.invalidate(documentCountProvider);
  container.invalidate(auditHistoryProvider);
}

/// Uploads one file against a record (§4.2). [onProgress] follows the bytes on their way out, so
/// the dialog can show how far along a slow upload is (AC-C20). The file's type is the server's
/// to decide, from the content itself, so none is claimed here (§4.3).
Future<DocumentItem> uploadDocument(
  Dio dio, {
  required DocumentEntityType type,
  required int entityId,
  required PickedDocument file,
  String? description,
  DocumentVisibility? visibility,
  ProgressCallback? onProgress,
}) async {
  final form = FormData.fromMap({
    'file': MultipartFile.fromBytes(file.bytes, filename: file.name),
    'entityType': type.wire,
    'entityId': '$entityId',
    if (description != null && description.isNotEmpty) 'description': description,
    if (visibility != null) 'visibility': visibility.wire,
  });
  final res = await dio.post('/api/documents', data: form, onSendProgress: onProgress);
  return DocumentItem.fromJson((res.data as Map).cast<String, dynamic>());
}

/// Changes what a document says about itself, or who may see it.
Future<DocumentItem> updateDocument(
  Dio dio,
  int id, {
  String? description,
  DocumentVisibility? visibility,
}) async {
  final res = await dio.patch('/api/documents/$id', data: {
    if (description != null) 'description': description,
    if (visibility != null) 'visibility': visibility.wire,
  });
  return DocumentItem.fromJson((res.data as Map).cast<String, dynamic>());
}

/// Soft-deletes a document: the row stays for the audit trail, the file stops coming down
/// (AC-C3).
Future<void> deleteDocument(Dio dio, int id) => dio.delete('/api/documents/$id');

/// Fetches the bytes. The name and type are the row's own, not the response's headers, which are
/// deliberately anonymous so no browser renders the file in the app's origin (AC-C13).
Future<DownloadedDocument> downloadDocument(Dio dio, DocumentItem document) async {
  final res = await dio.get<Object?>(
    '/api/documents/${document.id}/download',
    options: Options(responseType: ResponseType.bytes),
  );
  return DownloadedDocument(
    filename: document.filename,
    contentType: document.contentType,
    bytes: _bytes(res.data),
  );
}

/// Bytes as Dio hands them over. A response that came back as text — an error page, or a fake
/// server in a test — is still bytes to whoever saves it.
Uint8List _bytes(Object? data) {
  if (data is Uint8List) return data;
  if (data is List) return Uint8List.fromList(data.cast<int>());
  return Uint8List.fromList(utf8.encode('${data ?? ''}'));
}
