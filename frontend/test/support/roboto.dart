import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Makes text measure as the web app draws it, for tests about what fits on a line or a screen.
/// Without it every glyph is drawn in the test font, a square as wide as the font size, so a line
/// is about twice its real width. Call it from `setUpAll`: fonts stay loaded for the rest of the
/// test file.
///
/// Roboto comes from the Flutter SDK, and is loaded under the family names the theme asks for:
/// Roboto itself, and the system font of a Mac's typography (a desktop browser on a Mac reports
/// that platform, and the web app draws that family in Roboto too).
Future<void> loadRoboto() async {
  final root = Platform.environment['FLUTTER_ROOT'] ??
      // flutter_tester runs from <root>/bin/cache/artifacts/engine/<platform>/.
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
