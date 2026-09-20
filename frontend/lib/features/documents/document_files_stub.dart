import 'package:flutter/widgets.dart';

import 'document_models.dart';

/// Away from the web there is no file chooser, no page to drop a file on and nowhere to put a
/// downloaded one, so each of these says so plainly rather than pretending it worked. The
/// Documents tab reads the answer and tells the user (§4.6).

bool get canPickDocumentFiles => false;

Future<PickedDocument?> pickDocumentFile() async => null;

Widget documentDropTarget({
  required Widget child,
  required ValueChanged<PickedDocument> onFile,
  bool enabled = true,
}) =>
    child;

Future<bool> saveDocumentFile(DownloadedDocument file) async => false;
