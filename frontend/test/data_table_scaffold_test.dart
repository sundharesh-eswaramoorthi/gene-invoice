import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';

typedef _Row = Map<String, dynamic>;

/// Answers the schema request with no columns and every other request with [page].
Dio _dio(Map<String, dynamic> page) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    final data = options.path.startsWith('/api/table-schemas/')
        ? <String, dynamic>{'entity': 'things', 'columns': <dynamic>[]}
        : page;
    handler.resolve(Response(requestOptions: options, statusCode: 200, data: data));
  }));

Map<String, dynamic> _page(List<_Row> rows, {int page = 0, int? total}) {
  final count = total ?? rows.length;
  return {
    'content': rows,
    'page': page,
    'size': 10,
    'totalElements': count,
    'totalPages': count == 0 ? 1 : (count / 10).ceil(),
  };
}

Future<void> _pump(
  WidgetTester tester, {
  required Map<String, dynamic> page,
  List<TableColumnSpec<_Row>>? columns,
  bool canExport = false,
  List<BulkActionSpec> bulkActions = const [],
  ValueChanged<TableQuery>? onQueryChanged,
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(_dio(page)),
      currentUserProvider.overrideWithValue(null),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Row(
          children: [
            // Stands in for the extended navigation rail beside every desktop list page.
            const SizedBox(width: 257),
            Expanded(
              child: DataTableScaffold<_Row>(
                entity: 'things',
                path: '/api/things',
                query: const TableQuery(size: 10),
                onQueryChanged: onQueryChanged ?? (_) {},
                parse: (json) => json,
                idOf: (r) => r['id'] as int,
                columns: columns ??
                    [
                      TableColumnSpec(label: 'Name', cell: (context, r) => Text('${r['name']}')),
                      TableColumnSpec(label: 'Status', cell: (context, r) => const Text('Open')),
                    ],
                rowActions: (context, r) => [
                  IconButton(tooltip: 'Open', icon: const Icon(Icons.open_in_new), onPressed: () {}),
                ],
                canExport: canExport,
                bulkActions: bulkActions,
              ),
            ),
          ],
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the table fits the space beside the navigation rail', (tester) async {
    await _pump(tester, page: _page([
      {'id': 1, 'name': 'Acme Ltd'},
    ]));
    expect(tester.getRect(find.byTooltip('Open')).right, lessThanOrEqualTo(1366));
  });

  testWidgets('long free text is capped instead of stretching the table', (tester) async {
    final reason = 'wrong amount ' * 150;
    await _pump(
      tester,
      page: _page([
        {'id': 1, 'reason': reason},
      ]),
      columns: [
        TableColumnSpec(
          label: 'Reason',
          maxWidth: 360,
          cell: (context, r) => Text('${r['reason']}', maxLines: 2, overflow: TextOverflow.ellipsis),
        ),
      ],
    );
    expect(tester.getSize(find.text(reason)).width, lessThanOrEqualTo(360));
    expect(tester.getRect(find.byTooltip('Open')).right, lessThanOrEqualTo(1366));
  });

  testWidgets('a role that may only export can still select rows and export them', (tester) async {
    await _pump(tester, canExport: true, page: _page([
      {'id': 1, 'name': 'Acme Ltd'},
    ]));
    expect(find.byType(Checkbox), findsWidgets);

    await tester.tap(find.byType(Checkbox).last);
    await tester.pumpAndSettle();
    expect(find.text('Export selected'), findsOneWidget);
  });

  testWidgets('with nothing to do with a selection, rows have no checkboxes', (tester) async {
    await _pump(tester, page: _page([
      {'id': 1, 'name': 'Acme Ltd'},
    ]));
    expect(find.byType(Checkbox), findsNothing);
  });

  testWidgets('a page past the end offers the last page instead of "no matches"', (tester) async {
    TableQuery? requested;
    await _pump(tester, page: _page([], page: 99, total: 4), onQueryChanged: (q) => requested = q);

    expect(find.text('This page is past the end'), findsOneWidget);
    expect(find.textContaining('No rows match'), findsNothing);
    expect(find.text('0–0 of 4'), findsOneWidget);

    await tester.tap(find.text('Go to last page'));
    expect(requested?.page, 0);
  });

  test('row numbers are 0–0 on an empty page, even past the end', () {
    final past = PagedResult.fromJson(
        {'content': [], 'page': 99, 'size': 10, 'totalElements': 4, 'totalPages': 1});
    expect(past.firstRowNumber, 0);
    expect(past.lastRowNumber, 0);
  });
}
