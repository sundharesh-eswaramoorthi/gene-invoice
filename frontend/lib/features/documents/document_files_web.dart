import 'dart:async';
import 'dart:js_interop';

import 'package:flutter/material.dart';
import 'package:web/web.dart' as web;

import 'document_models.dart';

/// The browser's own file chooser, drop target and downloader, reached through package:web.
/// Only the web build compiles this file (see document_files.dart).

bool get canPickDocumentFiles => true;

/// Opens the file chooser, limited to the types the server keeps, and reads what was picked.
/// Resolves null when the chooser was closed with nothing.
Future<PickedDocument?> pickDocumentFile() {
  final input = web.HTMLInputElement()
    ..type = 'file'
    ..accept = documentAcceptAttribute
    ..multiple = false;
  final picked = Completer<PickedDocument?>();
  void finish(PickedDocument? file) {
    if (!picked.isCompleted) picked.complete(file);
  }

  input.addEventListener(
      'change',
      ((web.Event _) {
        final file = input.files?.item(0);
        if (file == null) {
          finish(null);
          return;
        }
        // The handler itself stays synchronous: a JS event listener may not hand back a future.
        _read(file).then(finish, onError: (_) => finish(null));
      }).toJS);
  // A chooser closed with nothing picked fires 'cancel'; without it the dialog would wait
  // forever for a file that is not coming.
  input.addEventListener('cancel', ((web.Event _) => finish(null)).toJS);
  input.click();
  return picked.future;
}

/// Wraps [child] so a file dragged onto the page arrives as a [PickedDocument] (AC-C20). Flutter
/// draws the page into one canvas, so the events are taken from the document itself and only
/// while this widget is mounted and [enabled].
Widget documentDropTarget({
  required Widget child,
  required ValueChanged<PickedDocument> onFile,
  bool enabled = true,
}) =>
    _DocumentDropTarget(onFile: onFile, enabled: enabled, child: child);

/// Hands [file] to the viewer as a download, named as it was uploaded.
Future<bool> saveDocumentFile(DownloadedDocument file) async {
  final blob = web.Blob(
    <JSAny>[file.bytes.toJS].toJS,
    web.BlobPropertyBag(
        type: file.contentType.isEmpty ? 'application/octet-stream' : file.contentType),
  );
  final url = web.URL.createObjectURL(blob);
  final anchor = web.HTMLAnchorElement()
    ..href = url
    ..download = file.filename;
  web.document.body?.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoked late: a browser still reading the blob when the URL goes stops the download.
  Timer(const Duration(minutes: 1), () => web.URL.revokeObjectURL(url));
  return true;
}

Future<PickedDocument> _read(web.File file) async {
  final buffer = (await file.arrayBuffer().toDart).toDart;
  return PickedDocument(
    name: file.name,
    mimeType: file.type.isEmpty ? null : file.type,
    bytes: buffer.asUint8List(),
  );
}

class _DocumentDropTarget extends StatefulWidget {
  final Widget child;
  final ValueChanged<PickedDocument> onFile;
  final bool enabled;

  const _DocumentDropTarget({
    required this.child,
    required this.onFile,
    required this.enabled,
  });

  @override
  State<_DocumentDropTarget> createState() => _DocumentDropTargetState();
}

class _DocumentDropTargetState extends State<_DocumentDropTarget> {
  bool _over = false;
  JSFunction? _dragOver;
  JSFunction? _dragLeave;
  JSFunction? _drop;

  @override
  void initState() {
    super.initState();
    if (widget.enabled) _listen();
  }

  @override
  void didUpdateWidget(covariant _DocumentDropTarget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.enabled == oldWidget.enabled) return;
    if (widget.enabled) {
      _listen();
    } else {
      _stop();
    }
  }

  @override
  void dispose() {
    _stop();
    super.dispose();
  }

  void _listen() {
    // Without preventDefault the browser opens the file itself and leaves the app.
    _dragOver = ((web.Event event) {
      event.preventDefault();
      if (!_over && mounted) setState(() => _over = true);
    }).toJS;
    _dragLeave = ((web.Event _) {
      if (_over && mounted) setState(() => _over = false);
    }).toJS;
    _drop = ((web.Event event) {
      event.preventDefault();
      if (mounted) setState(() => _over = false);
      final files = (event as web.DragEvent).dataTransfer?.files;
      final file = (files == null || files.length == 0) ? null : files.item(0);
      if (file == null) return;
      // One at a time: the upload form takes one file and its own description.
      _read(file).then((picked) {
        if (mounted) widget.onFile(picked);
      }, onError: (_) {});
    }).toJS;
    web.document.addEventListener('dragover', _dragOver);
    web.document.addEventListener('dragleave', _dragLeave);
    web.document.addEventListener('drop', _drop);
  }

  void _stop() {
    if (_dragOver != null) web.document.removeEventListener('dragover', _dragOver);
    if (_dragLeave != null) web.document.removeEventListener('dragleave', _dragLeave);
    if (_drop != null) web.document.removeEventListener('drop', _drop);
    _dragOver = null;
    _dragLeave = null;
    _drop = null;
    _over = false;
  }

  @override
  Widget build(BuildContext context) {
    if (!_over) return widget.child;
    final scheme = Theme.of(context).colorScheme;
    return Stack(
      children: [
        widget.child,
        Positioned.fill(
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: scheme.primary.withValues(alpha: 0.08),
                border: Border.all(color: scheme.primary, width: 2),
              ),
              child: Center(
                child: Text('Drop the file to upload it',
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(color: scheme.primary)),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
