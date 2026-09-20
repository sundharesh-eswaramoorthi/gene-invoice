import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../auth/auth_controller.dart';
import 'document_files.dart';
import 'document_models.dart';
import 'document_providers.dart';
import 'documents_tab.dart' show documentIcon;

/// The upload form for one record. Resolves true once a file has been uploaded, so the tab knows
/// to say so. [file] is one already chosen — dropped on the tab — and the form opens ready to
/// send it (AC-C20).
Future<bool> openUploadDocumentDialog(
  BuildContext context, {
  required DocumentEntityType type,
  required int entityId,
  String? entityLabel,
  PickedDocument? file,
}) async {
  final uploaded = await showDialog<bool>(
    context: context,
    // A file on its way is not something to lose by tapping beside the form.
    barrierDismissible: false,
    builder: (_) => _UploadDocumentDialog(
      type: type,
      entityId: entityId,
      entityLabel: entityLabel,
      file: file,
    ),
  );
  return uploaded ?? false;
}

class _UploadDocumentDialog extends ConsumerStatefulWidget {
  final DocumentEntityType type;
  final int entityId;
  final String? entityLabel;
  final PickedDocument? file;

  const _UploadDocumentDialog({
    required this.type,
    required this.entityId,
    this.entityLabel,
    this.file,
  });

  @override
  ConsumerState<_UploadDocumentDialog> createState() => _UploadDocumentDialogState();
}

class _UploadDocumentDialogState extends ConsumerState<_UploadDocumentDialog> {
  final _description = TextEditingController();
  late PickedDocument? _file = widget.file;
  DocumentVisibility _visibility = DocumentVisibility.INTERNAL;
  bool _sending = false;

  /// How much of the file has gone, 0 to 1, or null before anything has (dio's onSendProgress).
  double? _progress;
  String? _error;

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  Future<void> _choose() async {
    final picker = ref.read(documentPickerProvider);
    if (picker == null) return;
    final picked = await picker();
    if (picked == null || !mounted) return;
    setState(() {
      _file = picked;
      // What was wrong with the last file says nothing about this one.
      _error = null;
    });
  }

  Future<void> _upload() async {
    // The same words the server uses, before a file it was never going to keep is sent (AC-C20).
    final refusal = documentRefusal(_file);
    if (refusal != null) {
      setState(() => _error = refusal);
      return;
    }
    // Taken before the request, which can take a while on a large file: the form may be gone by
    // the time it answers, and a gone widget's ref throws.
    final container = ProviderScope.containerOf(context, listen: false);
    setState(() {
      _sending = true;
      _progress = null;
      _error = null;
    });
    try {
      await uploadDocument(
        ref.read(dioProvider),
        type: widget.type,
        entityId: widget.entityId,
        file: _file!,
        description: _description.text.trim(),
        // A customer login's uploads are shared by the server's own rule (§1, answer 4); it is
        // never asked, so it never sends an answer.
        visibility: _customerLogin ? null : _visibility,
        onProgress: (sent, total) {
          if (mounted && total > 0) setState(() => _progress = sent / total);
        },
      );
      invalidateDocuments(container);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      // The form stays open with what was typed, so the upload can be tried again (AC-C21).
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  bool get _customerLogin => ref.read(currentUserProvider)?.isCustomer ?? false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final file = _file;
    final title = widget.entityLabel == null
        ? 'Attach a document'
        : 'Attach a document • ${widget.entityLabel}';

    return AlertDialog(
      title: Text(title),
      content: SizedBox(
        width: 460,
        // A file may be dropped on the form itself, as well as on the tab behind it (AC-C20).
        // The tab turns its own target off while this is open, so a drop is taken once.
        child: documentDropTarget(
          enabled: !_sending,
          onFile: (picked) => setState(() {
            _file = picked;
            _error = null;
          }),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (file == null)
                  _Chooser(
                    available: ref.watch(documentPickerProvider) != null,
                    onChoose: _sending ? null : _choose,
                  )
                else
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(documentIcon(file.mimeType ?? '', file.name)),
                    title: Text(file.name, maxLines: 2, overflow: TextOverflow.ellipsis),
                    subtitle: Text(formatBytes(file.sizeBytes)),
                    // It takes the file back off the form rather than opening the chooser, and is
                    // named for that; what was wrong with the file goes with it (UI-05).
                    trailing: IconButton(
                      tooltip: 'Remove file',
                      icon: const Icon(Icons.close),
                      onPressed: _sending
                          ? null
                          : () => setState(() {
                                _file = null;
                                _error = null;
                              }),
                    ),
                  ),
                const SizedBox(height: 8),
                TextField(
                  controller: _description,
                  enabled: !_sending,
                  minLines: 1,
                  maxLines: 3,
                  inputFormatters: [
                    LengthLimitingTextInputFormatter(FieldLimits.documentDescription),
                  ],
                  decoration: const InputDecoration(
                    labelText: 'Description (optional)',
                    hintText: 'What is this file?',
                  ),
                ),
                if (!_customerLogin) ...[
                  const SizedBox(height: 12),
                  DropdownButtonFormField<DocumentVisibility>(
                    initialValue: _visibility,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Who can see it'),
                    items: [
                      for (final v in DocumentVisibility.values)
                        DropdownMenuItem(value: v, child: Text(v.label)),
                    ],
                    onChanged:
                        _sending ? null : (v) => setState(() => _visibility = v ?? _visibility),
                  ),
                ],
                if (_sending) ...[
                  const SizedBox(height: 12),
                  LinearProgressIndicator(value: _progress),
                  const SizedBox(height: 4),
                  Text(
                    _progress == null
                        ? 'Uploading…'
                        : 'Uploading… ${(_progress! * 100).round()}%',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _sending ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton.icon(
          icon: const Icon(Icons.upload_file, size: 18),
          label: const Text('Upload'),
          onPressed: _sending ? null : _upload,
        ),
      ],
    );
  }
}

/// Where a file comes from before there is one: the browser's chooser, or the page itself.
class _Chooser extends StatelessWidget {
  /// False in a build with no file chooser of its own, which says so instead (§4.6).
  final bool available;
  final VoidCallback? onChoose;
  const _Chooser({required this.available, this.onChoose});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (available)
          OutlinedButton.icon(
            icon: const Icon(Icons.attach_file, size: 18),
            label: const Text('Choose file'),
            onPressed: onChoose,
          )
        else
          Text('Open the app in a browser to attach a file.', style: theme.textTheme.bodyMedium),
        const SizedBox(height: 8),
        Text(
          'PDF, PNG, JPEG, Word or Excel, up to '
          '${formatBytes(FieldLimits.documentMaxBytes)}.',
          style: theme.textTheme.bodySmall,
        ),
        if (available)
          Text('You can also drop a file on the Documents tab.',
              style: theme.textTheme.bodySmall),
      ],
    );
  }
}
