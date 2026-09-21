import 'package:flutter/widgets.dart';

import 'document_models.dart';

bool get canPickDocumentFiles => false;

Future<PickedDocument?> pickDocumentFile() async => null;

Widget documentDropTarget({
  required Widget child,
  required ValueChanged<PickedDocument> onFile,
  bool enabled = true,
}) =>
    child;

Future<bool> saveDocumentFile(DownloadedDocument file) async => false;
