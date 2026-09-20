import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../core/table/table_models.dart';
import '../../shared/widgets/status_chip.dart';
import 'document_actions.dart' show canUploadDocumentsProvider;
import 'document_files.dart';
import 'document_models.dart';
import 'document_providers.dart';
import 'upload_document_dialog.dart';

/// The icon for a kind of file: a list of documents is scanned by shape before it is read.
IconData documentIcon(String contentType, String filename) =>
    switch (documentKindLabel(contentType, filename)) {
      'PDF' => Icons.picture_as_pdf_outlined,
      'PNG' || 'JPEG' => Icons.image_outlined,
      'Word' => Icons.description_outlined,
      'Excel' => Icons.table_chart_outlined,
      _ => Icons.insert_drive_file_outlined,
    };

/// The Documents tab on a details page: every file attached to the record, newest first, and
/// what this caller may do with each (§4.6). A file dropped anywhere on the page opens the
/// upload form with it already chosen (AC-C20).
class DocumentsTab extends ConsumerStatefulWidget {
  final DocumentEntityType type;
  final int entityId;
  final String? entityLabel;

  const DocumentsTab({
    super.key,
    required this.type,
    required this.entityId,
    this.entityLabel,
  });

  @override
  ConsumerState<DocumentsTab> createState() => _DocumentsTabState();
}

class _DocumentsTabState extends ConsumerState<DocumentsTab> {
  static const _pageSize = 20;

  /// Pages shown so far. Each is its own request, so "Show older" never refetches what is on
  /// screen, and an upload that invalidates the family refreshes them all at once.
  int _pages = 1;

  /// A form is open, so the page must not answer a drop behind it with a second one.
  bool _formOpen = false;

  /// Rows with a request of their own under way; their buttons wait for it.
  final Set<int> _busy = {};

  DocumentListKey _key(int page) =>
      (type: widget.type, entityId: widget.entityId, page: page, size: _pageSize);

  void _reload() {
    ref.invalidate(entityDocumentsProvider);
    ref.invalidate(documentCountProvider);
  }

  Future<void> _upload({PickedDocument? file}) async {
    setState(() => _formOpen = true);
    final messenger = ScaffoldMessenger.of(context);
    final uploaded = await openUploadDocumentDialog(
      context,
      type: widget.type,
      entityId: widget.entityId,
      entityLabel: widget.entityLabel,
      file: file,
    );
    if (!mounted) return;
    setState(() => _formOpen = false);
    if (uploaded) {
      messenger.showSnackBar(const SnackBar(content: Text('Document uploaded')));
    }
  }

  Future<void> _download(DocumentItem document) async {
    final messenger = ScaffoldMessenger.of(context);
    final save = ref.read(documentSaverProvider);
    setState(() => _busy.add(document.id));
    try {
      final saved = await save(await downloadDocument(ref.read(dioProvider), document));
      messenger.showSnackBar(SnackBar(
        content: Text(saved
            ? 'Downloaded ${document.filename}'
            : 'Open the app in a browser to download files.'),
      ));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _busy.remove(document.id));
    }
  }

  Future<void> _edit(DocumentItem document) async {
    setState(() => _formOpen = true);
    final messenger = ScaffoldMessenger.of(context);
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _EditDocumentDialog(document: document),
    );
    if (!mounted) return;
    setState(() => _formOpen = false);
    if (saved == true) {
      messenger.showSnackBar(const SnackBar(content: Text('Document updated')));
    }
  }

  Future<void> _delete(DocumentItem document) async {
    setState(() => _formOpen = true);
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Remove this document?'),
        content: Text('${document.filename} stops being downloadable. It stays on the record\'s '
            'history, with who removed it and when.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep it')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(dialogContext).colorScheme.error),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    setState(() => _formOpen = false);
    if (confirmed != true) return;
    final container = ProviderScope.containerOf(context, listen: false);
    setState(() => _busy.add(document.id));
    try {
      await deleteDocument(ref.read(dioProvider), document.id);
      invalidateDocuments(container);
      messenger.showSnackBar(const SnackBar(content: Text('Document removed')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _busy.remove(document.id));
    }
  }

  @override
  Widget build(BuildContext context) {
    final canUpload = ref.watch(canUploadDocumentsProvider(widget.type));
    final first = ref.watch(entityDocumentsProvider(_key(0)));
    final theme = Theme.of(context);

    Widget header(int? total) => Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(total == null ? 'Documents' : 'Documents ($total)',
                    style: theme.textTheme.titleMedium),
              ),
              IconButton(
                tooltip: 'Refresh',
                icon: const Icon(Icons.refresh, size: 20),
                onPressed: _reload,
              ),
              // Nothing here for someone the upload endpoint would refuse (AC-C22).
              if (canUpload)
                OutlinedButton.icon(
                  icon: const Icon(Icons.upload_file, size: 18),
                  label: const Text('Upload'),
                  onPressed: _formOpen ? null : () => _upload(),
                ),
            ],
          ),
        );

    final body = first.when(
      // A refresh that fails keeps the documents already on show; the next one may do better.
      skipError: true,
      loading: () => ListView(
        padding: const EdgeInsets.all(12),
        children: [header(null), const LinearProgressIndicator()],
      ),
      error: (e, _) => ListView(
        padding: const EdgeInsets.all(12),
        children: [
          header(null),
          _Unavailable(
              message: 'Documents unavailable: ${apiErrorMessage(e)}', onRetry: _reload),
        ],
      ),
      data: (page) {
        if (page.isEmpty) {
          return ListView(
            padding: const EdgeInsets.all(12),
            children: [
              header(0),
              const SizedBox(height: 24),
              Icon(Icons.folder_outlined, size: 44, color: theme.colorScheme.outline),
              const SizedBox(height: 12),
              Text('No documents on this ${widget.type.noun} yet.',
                  textAlign: TextAlign.center, style: theme.textTheme.titleMedium),
              if (canUpload) ...[
                const SizedBox(height: 4),
                Text('Upload one to keep it with this ${widget.type.noun}.',
                    textAlign: TextAlign.center, style: theme.textTheme.bodySmall),
              ],
            ],
          );
        }

        final documents = <DocumentItem>[];
        final seen = <int>{};
        Widget? tail;
        for (var i = 0; i < _pages; i++) {
          final AsyncValue<PagedResult<DocumentItem>> async =
              i == 0 ? first : ref.watch(entityDocumentsProvider(_key(i)));
          final loaded = async.valueOrNull;
          if (loaded == null) {
            tail = async.hasError
                ? _Unavailable(
                    message: 'Older documents unavailable: ${apiErrorMessage(async.error!)}',
                    onRetry: () => ref.invalidate(entityDocumentsProvider(_key(i))),
                  )
                : const Padding(
                    padding: EdgeInsets.all(12),
                    child: Center(child: CircularProgressIndicator()),
                  );
            break;
          }
          // A file uploaded between two pages pushes a row onto the next page as well.
          for (final d in loaded.content) {
            if (seen.add(d.id)) documents.add(d);
          }
        }
        final older = page.totalElements - _pages * _pageSize;

        return ListView(
          padding: const EdgeInsets.all(12),
          children: [
            header(page.totalElements),
            for (final d in documents)
              _DocumentRow(
                key: ValueKey('document-${d.id}'),
                document: d,
                busy: _busy.contains(d.id),
                onDownload: () => _download(d),
                onEdit: () => _edit(d),
                onDelete: () => _delete(d),
              ),
            if (tail != null) tail,
            if (tail == null && older > 0)
              Center(
                child: TextButton.icon(
                  icon: const Icon(Icons.expand_more),
                  label: Text('Show older documents ($older more)'),
                  onPressed: () => setState(() => _pages++),
                ),
              ),
          ],
        );
      },
    );

    // Dropping is for those who may upload, and never behind an open form.
    return documentDropTarget(
      enabled: canUpload && !_formOpen,
      onFile: (file) => _upload(file: file),
      child: body,
    );
  }
}

/// One document: what it is, who put it there, and the buttons this caller may press. What may
/// be done is the server's answer on the row, not a guess, so nothing offered here can 403
/// (AC-C22).
class _DocumentRow extends StatelessWidget {
  final DocumentItem document;
  final bool busy;
  final VoidCallback onDownload;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  const _DocumentRow({
    super.key,
    required this.document,
    required this.busy,
    required this.onDownload,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final uploader = document.uploadedBy?.name ?? '—';
    final facts = [
      document.kindLabel,
      document.size,
      uploader,
      if (document.uploadedAt != null) formatDateTime(document.uploadedAt),
    ].join(' · ');

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 4, right: 12),
              child: Icon(documentIcon(document.contentType, document.filename),
                  color: scheme.primary),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Text(document.filename,
                          style: theme.textTheme.titleSmall
                              ?.copyWith(fontWeight: FontWeight.w600)),
                      // Shared says the customer can see it too; internal is the quiet default.
                      StatusChip(
                        label: document.visibility.shortLabel,
                        color: document.visibility == DocumentVisibility.SHARED
                            ? Colors.green.shade700
                            : scheme.outline,
                      ),
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(facts, style: theme.textTheme.bodySmall),
                  if (document.description != null && document.description!.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(document.description!),
                  ],
                ],
              ),
            ),
            if (busy)
              const Padding(
                padding: EdgeInsets.all(12),
                child: SizedBox(
                    width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              )
            else
              Wrap(
                children: [
                  if (document.canDownload)
                    IconButton(
                      tooltip: 'Download',
                      icon: const Icon(Icons.download_outlined, size: 20),
                      onPressed: onDownload,
                    ),
                  if (document.canEdit)
                    IconButton(
                      tooltip: 'Edit',
                      icon: const Icon(Icons.edit_outlined, size: 20),
                      onPressed: onEdit,
                    ),
                  if (document.canDelete)
                    IconButton(
                      tooltip: 'Remove',
                      icon: const Icon(Icons.delete_outline, size: 20),
                      color: scheme.error,
                      onPressed: onDelete,
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}

/// What a document says about itself, and who may see it (`PATCH /api/documents/{id}`). The file
/// itself never changes: a new version is a new upload.
class _EditDocumentDialog extends ConsumerStatefulWidget {
  final DocumentItem document;
  const _EditDocumentDialog({required this.document});

  @override
  ConsumerState<_EditDocumentDialog> createState() => _EditDocumentDialogState();
}

class _EditDocumentDialogState extends ConsumerState<_EditDocumentDialog> {
  late final _description =
      TextEditingController(text: widget.document.description ?? '');
  late DocumentVisibility _visibility = widget.document.visibility;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final container = ProviderScope.containerOf(context, listen: false);
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await updateDocument(
        ref.read(dioProvider),
        widget.document.id,
        description: _description.text.trim(),
        visibility: _visibility,
      );
      invalidateDocuments(container);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text('Edit • ${widget.document.filename}'),
        content: SizedBox(
          width: 460,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: _description,
                enabled: !_saving,
                minLines: 1,
                maxLines: 3,
                inputFormatters: [
                  LengthLimitingTextInputFormatter(FieldLimits.documentDescription),
                ],
                decoration: const InputDecoration(labelText: 'Description'),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<DocumentVisibility>(
                initialValue: _visibility,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Who can see it'),
                items: [
                  for (final v in DocumentVisibility.values)
                    DropdownMenuItem(value: v, child: Text(v.label)),
                ],
                onChanged: _saving ? null : (v) => setState(() => _visibility = v ?? _visibility),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(_error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(
                    width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Save'),
          ),
        ],
      );
}

/// A failed load with a way back from it, rather than an empty list (AC-C21).
class _Unavailable extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _Unavailable({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Column(
          children: [
            Text(message, textAlign: TextAlign.center),
            TextButton.icon(
              icon: const Icon(Icons.refresh),
              label: const Text('Try again'),
              onPressed: onRetry,
            ),
          ],
        ),
      );
}
