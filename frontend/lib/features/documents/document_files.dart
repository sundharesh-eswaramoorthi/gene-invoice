/// Choosing a file, dropping one on the page and handing a downloaded one back — the parts of
/// the Documents tab only a browser can do. The web build talks to the DOM; every other build,
/// including the widget tests, gets the stub, so the feature compiles everywhere (§4.6).
///
/// Both sides offer the same four names:
///
/// * `canPickDocumentFiles` — whether this build can open a file chooser at all
/// * `pickDocumentFile()` — the chooser; null when nothing was picked
/// * `documentDropTarget(...)` — wraps a widget so a file dropped on it arrives as a
///   [PickedDocument]
/// * `saveDocumentFile(...)` — gives the viewer the bytes; false when this build cannot
library;

export 'document_files_stub.dart'
    if (dart.library.js_interop) 'document_files_web.dart';
