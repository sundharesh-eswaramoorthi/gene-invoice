import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import 'table_models.dart';

/// Keeps a list view's page, size, sort and filters in the URL, so a shared link
/// reproduces the exact view and the browser's back button works (AC-D4).
///
/// Lists open with no filters: nothing is pre-applied for any role. A scope the server enforces
/// arrives separately as locked filters and is shown as a locked chip.
///
/// Both halves of that URL deliberately avoid the HTML-form convention where a space is written
/// '+' and a '+' therefore has to be escaped. A filter value is free text — a phone number, a
/// plus-addressed email, "+GST" in a note — and under that convention the link only survives
/// while every escape stays intact. The address bar shows it decoded, and the decoded form is
/// what people copy, share and bookmark: reopened, "+91 5551234" came back as " 91 5551234",
/// silently showing different rows (TBL-03). Here a space is written %20 and a '+' is a '+' on
/// both sides, so the link means the same thing encoded or decoded.
class RouteQuery {
  final BuildContext context;
  final String path;

  const RouteQuery(this.context, this.path);

  static TableQuery read(GoRouterState state, {required int defaultSize, String? defaultSort}) =>
      TableQuery.fromRoute(queryParametersAll(state.uri),
          defaultSize: defaultSize, defaultSort: defaultSort);

  void push(TableQuery query, {Map<String, String> keep = const {}}) {
    final params = <String, dynamic>{...keep, ...query.toRouteParams()};
    context.go(location(path, params));
  }

  static String location(String path, Map<String, dynamic> params) {
    final pairs = <String>[];
    params.forEach((key, value) {
      for (final one in value is Iterable ? value : [value]) {
        pairs.add('${Uri.encodeComponent(key)}=${Uri.encodeComponent('$one')}');
      }
    });
    return pairs.isEmpty ? path : '$path?${pairs.join('&')}';
  }

  static Map<String, List<String>> queryParametersAll(Uri uri) {
    final params = <String, List<String>>{};
    for (final pair in uri.query.split('&')) {
      if (pair.isEmpty) continue;
      final eq = pair.indexOf('=');
      final key = _decode(eq < 0 ? pair : pair.substring(0, eq));
      (params[key] ??= []).add(eq < 0 ? '' : _decode(pair.substring(eq + 1)));
    }
    return params;
  }

  static String _decode(String raw) {
    try {
      return Uri.decodeComponent(raw);
    } on ArgumentError {
      return raw;
    } on FormatException {
      return raw;
    }
  }
}
