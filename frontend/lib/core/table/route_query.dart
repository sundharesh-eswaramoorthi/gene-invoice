import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import 'table_models.dart';

/// Keeps a list view's page, size, sort and filters in the URL, so a shared link
/// reproduces the exact view and the browser's back button works (AC-D4).
///
/// Lists open with no filters: nothing is pre-applied for any role. A scope the server enforces
/// arrives separately as locked filters and is shown as a locked chip.
class RouteQuery {
  final BuildContext context;
  final String path;

  const RouteQuery(this.context, this.path);

  static TableQuery read(GoRouterState state, {required int defaultSize, String? defaultSort}) =>
      TableQuery.fromRoute(state.uri.queryParametersAll,
          defaultSize: defaultSize, defaultSort: defaultSort);

  void push(TableQuery query, {Map<String, String> keep = const {}}) {
    final params = <String, dynamic>{...keep, ...query.toRouteParams()};
    context.go(Uri(path: path, queryParameters: params).toString());
  }
}
