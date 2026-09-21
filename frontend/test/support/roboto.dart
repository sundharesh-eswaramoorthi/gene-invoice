import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Future<void> loadRoboto() async {
  final root = Platform.environment['FLUTTER_ROOT'] ??
      File(Platform.resolvedExecutable).parent.parent.parent.parent.parent.parent.path;
  final dir = '$root/bin/cache/artifacts/material_fonts';
  const files = ['Roboto-Regular.ttf', 'Roboto-Medium.ttf', 'Roboto-Bold.ttf'];
  for (final f in files) {
    if (!File('$dir/$f').existsSync()) {
      fail('Measuring real widths needs Roboto, and $dir/$f is missing');
    }
  }
  for (final family in ['Roboto', '.AppleSystemUIFont', 'CupertinoSystemText']) {
    final loader = FontLoader(family);
    for (final f in files) {
      loader.addFont(File('$dir/$f').readAsBytes().then(ByteData.sublistView));
    }
    await loader.load();
  }
}
