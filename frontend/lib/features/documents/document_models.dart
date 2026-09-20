import 'dart:typed_data';

import '../../core/field_limits.dart';
import '../../shared/models/privileges.dart';

/// The records a document can be attached to (§4.1). The wire value, the route and the API path
/// all follow from the name, so a details page and the backend agree on one spelling — the same
/// shape `EmailEntityType` has for email. The enum is open: another record type is one entry
/// here and one in the backend's `DocumentEntityType` (D5).
enum DocumentEntityType {
  customer,
  invoice,
  payment;

  /// 'INVOICE', as the API spells it.
  String get wire => name.toUpperCase();

  /// 'invoice', for sentences: "No documents on this invoice yet."
  String get noun => name;

  /// 'Invoice', for labels.
  String get label => '${name[0].toUpperCase()}${name.substring(1)}';

  /// '/invoices' — the list page; a record's page is `$routeBase/$id`.
  String get routeBase => '/${name}s';

  /// '/api/invoices' — the record's own endpoint.
  String get apiPath => '/api$routeBase';

  /// The parent record's own privileges. Every document endpoint asks for one of these as well
  /// as the document privilege (§4.5), so the UI hides what would 403 (AC-C22).
  String get recordViewPrivilege => switch (this) {
        customer => Privileges.customerView,
        invoice => Privileges.invoiceView,
        payment => Privileges.paymentView,
      };

  String get recordManagePrivilege => switch (this) {
        customer => Privileges.customerManage,
        invoice => Privileges.invoiceManage,
        payment => Privileges.paymentManage,
      };

  static DocumentEntityType? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }
}

/// Who may see a document (D7). A customer login is shown only [SHARED] ones, and its own
/// uploads land there so that the uploader can still see them (§1, answer 4).
enum DocumentVisibility {
  INTERNAL,
  SHARED;

  String get wire => name;

  String get label => this == INTERNAL ? 'Internal only' : 'Shared with customer';

  /// The chip on a row, where the column is narrow and the label above it says what it is.
  String get shortLabel => this == INTERNAL ? 'Internal' : 'Shared';
}

DocumentVisibility parseDocumentVisibility(String? wire) =>
    DocumentVisibility.values.asNameMap()[wire] ?? DocumentVisibility.INTERNAL;

/// Who uploaded a document. The user id is null for a row whose uploader has since been removed.
class DocumentUploader {
  final int? userId;
  final String name;

  const DocumentUploader({this.userId, required this.name});

  factory DocumentUploader.fromJson(Map<String, dynamic> json) => DocumentUploader(
        userId: (json['userId'] as num?)?.toInt(),
        name: json['name'] as String? ?? '—',
      );
}

/// One row of `GET /api/documents` (`DocumentDto`, §4.2). What the caller may do with it is the
/// server's answer, not this app's guess, so the row shows exactly the buttons that will work.
class DocumentItem {
  final int id;
  final DocumentEntityType? entityType;
  final int entityId;
  final String entityLabel;
  final String? entityLink;

  /// The name the file was uploaded under, for display only — never a path (AC-C8).
  final String filename;

  /// What the server detected from the file's first bytes, not what the browser claimed (§4.3).
  final String contentType;
  final int sizeBytes;

  /// The server's own wording for [sizeBytes] ("1.2 MB"), so one place decides how a size reads.
  final String sizeLabel;

  final DocumentVisibility visibility;
  final String? description;
  final DocumentUploader? uploadedBy;
  final DateTime? uploadedAt;
  final bool canDownload;
  final bool canEdit;
  final bool canDelete;

  const DocumentItem({
    required this.id,
    this.entityType,
    required this.entityId,
    this.entityLabel = '',
    this.entityLink,
    required this.filename,
    this.contentType = '',
    this.sizeBytes = 0,
    this.sizeLabel = '',
    this.visibility = DocumentVisibility.INTERNAL,
    this.description,
    this.uploadedBy,
    this.uploadedAt,
    this.canDownload = false,
    this.canEdit = false,
    this.canDelete = false,
  });

  factory DocumentItem.fromJson(Map<String, dynamic> json) => DocumentItem(
        id: (json['id'] as num).toInt(),
        entityType: DocumentEntityType.fromWire(json['entityType'] as String?),
        entityId: (json['entityId'] as num?)?.toInt() ?? 0,
        entityLabel: json['entityLabel'] as String? ?? '',
        entityLink: json['entityLink'] as String?,
        filename: json['filename'] as String? ?? '',
        contentType: json['contentType'] as String? ?? '',
        sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
        sizeLabel: json['sizeLabel'] as String? ?? '',
        visibility: parseDocumentVisibility(json['visibility'] as String?),
        description: json['description'] as String?,
        uploadedBy: json['uploadedBy'] == null
            ? null
            : DocumentUploader.fromJson((json['uploadedBy'] as Map).cast<String, dynamic>()),
        uploadedAt: json['uploadedAt'] == null
            ? null
            : DateTime.tryParse('${json['uploadedAt']}'),
        canDownload: json['canDownload'] as bool? ?? false,
        canEdit: json['canEdit'] as bool? ?? false,
        canDelete: json['canDelete'] as bool? ?? false,
      );

  /// What the size reads as: the server's wording when it sent one, this app's otherwise.
  String get size => sizeLabel.isEmpty ? formatBytes(sizeBytes) : sizeLabel;

  /// 'PDF', 'PNG', 'Word', … — the kind of file, for the row's type column.
  String get kindLabel => documentKindLabel(contentType, filename);
}

/// A file chosen for upload, as the browser handed it over. The bytes are held in memory: the
/// limit is 10 MB (§4.3), so nothing larger than that is ever carried around.
class PickedDocument {
  final String name;

  /// What the browser called it. The server decides the type from the content and ignores this
  /// (§4.3); it only helps the form refuse an obvious mismatch before sending.
  final String? mimeType;
  final Uint8List bytes;

  const PickedDocument({required this.name, required this.bytes, this.mimeType});

  int get sizeBytes => bytes.length;
}

/// The bytes of a downloaded document, on their way to wherever the platform puts them.
class DownloadedDocument {
  final String filename;
  final String contentType;
  final Uint8List bytes;

  const DownloadedDocument(
      {required this.filename, required this.contentType, required this.bytes});
}

/// The types the server keeps (`app.documents.allowed-types`, §5), by the extension people know
/// them by. The server decides from the file's first bytes; this list only spares someone an
/// upload that was never going to be kept (AC-C20).
const documentContentTypes = <String, String>{
  'pdf': 'application/pdf',
  'png': 'image/png',
  'jpg': 'image/jpeg',
  'jpeg': 'image/jpeg',
  'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
};

/// What a file input offers: the extensions and the types behind them.
String get documentAcceptAttribute => [
      ...documentContentTypes.keys.map((e) => '.$e'),
      ...documentContentTypes.values.toSet(),
    ].join(',');

/// Why [file] cannot be uploaded, in the server's own words (§4.2), or null when it may be. The
/// server checks all of this again from the content itself; saying it here saves a 10 MB round
/// trip that was always going to be refused (AC-C20).
String? documentRefusal(PickedDocument? file) {
  if (file == null) return 'Choose a file';
  if (file.sizeBytes > FieldLimits.documentMaxBytes) {
    return 'The file is larger than ${formatBytes(FieldLimits.documentMaxBytes)}';
  }
  final extension = documentExtension(file.name);
  final claimed = file.mimeType ?? '';
  final known = documentContentTypes[extension];
  // Either end may be right: a browser that names no type still has the extension, and a file
  // renamed on the way in still has the type the browser read from it.
  if (known == null && !documentContentTypes.values.contains(claimed)) {
    return 'Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)';
  }
  return null;
}

/// 'report.final.pdf' → 'pdf'; '' when there is nothing after the last dot.
String documentExtension(String filename) {
  final dot = filename.lastIndexOf('.');
  if (dot < 0 || dot == filename.length - 1) return '';
  return filename.substring(dot + 1).toLowerCase();
}

/// The kind of file, named as people name it rather than by its media type.
String documentKindLabel(String contentType, String filename) => switch (contentType) {
      'application/pdf' => 'PDF',
      'image/png' => 'PNG',
      'image/jpeg' => 'JPEG',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document' => 'Word',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' => 'Excel',
      _ => switch (documentExtension(filename)) {
          'pdf' => 'PDF',
          'png' => 'PNG',
          'jpg' || 'jpeg' => 'JPEG',
          'docx' => 'Word',
          'xlsx' => 'Excel',
          final e => e.isEmpty ? 'File' : e.toUpperCase(),
        },
    };

/// A file size in the units the server writes one in: "512 B", "1.5 KB", "10 MB". A stored
/// document shows the label the server sent with it; this words a file the browser has just
/// handed over, and stands in when an older record carries no label.
///
/// The figure is rounded **up** to one decimal, so a size never reads as less than it is: a file
/// of 10 MB and one byte is "10.1 MB" beside "The file is larger than 10 MB", not "10 MB" beside
/// it (DOC-8). The unit rolls over on the rounded figure too, so nothing ever reads "1024.0 KB".
String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  const units = ['KB', 'MB', 'GB'];
  var value = bytes / 1024;
  var unit = 0;
  while (_ceilToTenth(value) >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  final rounded = _ceilToTenth(value).toStringAsFixed(1);
  return '${rounded.endsWith('.0') ? rounded.substring(0, rounded.length - 2) : rounded} '
      '${units[unit]}';
}

/// [value] to one decimal, never downwards. A size divided by 1024 is exact in binary, so a size
/// that lands on a tenth — 1.5 KB, 10 MB — stays there rather than creeping up.
double _ceilToTenth(double value) => (value * 10).ceil() / 10;
