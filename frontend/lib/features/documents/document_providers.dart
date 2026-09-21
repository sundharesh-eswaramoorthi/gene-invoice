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

final documentCountProvider =
    FutureProvider.autoDispose.family<int, DocumentRecordKey>((ref, key) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/documents/count', queryParameters: {
    'entityType': key.type.wire,
    'entityId': key.entityId,
  });
  return ((res.data as Map)['count'] as num?)?.toInt() ?? 0;
});

typedef DocumentPicker = Future<PickedDocument?> Function();

final documentPickerProvider =
    Provider<DocumentPicker?>((ref) => canPickDocumentFiles ? pickDocumentFile : null);

typedef DocumentSaver = Future<bool> Function(DownloadedDocument file);

final documentSaverProvider = Provider<DocumentSaver>((ref) => saveDocumentFile);

void invalidateDocuments(ProviderContainer container) {
  container.invalidate(entityDocumentsProvider);
  container.invalidate(documentCountProvider);
  container.invalidate(auditHistoryProvider);
}

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

Future<void> deleteDocument(Dio dio, int id) => dio.delete('/api/documents/$id');

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

Uint8List _bytes(Object? data) {
  if (data is Uint8List) return data;
  if (data is List) return Uint8List.fromList(data.cast<int>());
  return Uint8List.fromList(utf8.encode('${data ?? ''}'));
}
