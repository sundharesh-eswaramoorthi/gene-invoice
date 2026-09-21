import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';

typedef _Row = Map<String, dynamic>;

Dio _dio(Map<String, dynamic> page, {Map<String, dynamic>? bulkResult, String? pageError}) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    if (pageError != null && options.method == 'GET' && options.path == '/api/things') {
      handler.reject(DioException(
        requestOptions: options,
        type: DioExceptionType.badResponse,
        response: Response(
            requestOptions: options, statusCode: 400, data: {'message': pageError}),
      ));
      return;
    }
    final data = options.path.startsWith('/api/table-schemas/')
        ? <String, dynamic>{'entity': 'things', 'columns': <dynamic>[]}
        : options.method == 'POST'
            ? bulkResult
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
  List<Widget> actions = const [],
  ValueChanged<TableQuery>? onQueryChanged,
  Size size = const Size(1366, 900),
  Map<String, dynamic>? bulkResult,
  String? pageError,
  TableQuery query = const TableQuery(size: 10),
  Widget Function(BuildContext, Map<String, dynamic>)? tiles,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(_dio(page, bulkResult: bulkResult, pageError: pageError)),
      currentUserProvider.overrideWithValue(null),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Row(
          children: [
            if (size.width >= 900) const SizedBox(width: 257),
            Expanded(
              child: DataTableScaffold<_Row>(
                entity: 'things',
                path: '/api/things',
                query: query,
                onQueryChanged: onQueryChanged ?? (_) {},
                tiles: tiles,
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
                actions: actions,
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

  testWidgets('on a phone two page actions go below the filter bar instead of squeezing it',
      (tester) async {
    const phone = Size(390, 844);
    final rows = _page([
      {'id': 1, 'name': 'Acme Ltd'},
    ]);
    final actions = [
      OutlinedButton.icon(
          icon: const Icon(Icons.mail_outline, size: 18),
          label: const Text('Send email'),
          onPressed: () {}),
      FilledButton.icon(
          icon: const Icon(Icons.add), label: const Text('New customer'), onPressed: () {}),
    ];
    await _pump(tester, page: rows, size: phone);
    final chipAlone = tester.getSize(find.byType(ActionChip));

    await _pump(tester, page: rows, size: phone, actions: actions);
    expect(tester.takeException(), isNull);
    expect(tester.getSize(find.byType(ActionChip)), chipAlone);
    final chip = tester.getRect(find.byType(ActionChip));
    expect(tester.getRect(find.text('Send email')).top, greaterThan(chip.bottom));
    expect(tester.getRect(find.text('New customer')).right, lessThanOrEqualTo(phone.width));

    await _pump(tester, page: rows, actions: actions);
    expect(tester.getRect(find.text('New customer')).top,
        lessThan(tester.getRect(find.byType(ActionChip)).bottom));
  });

  testWidgets('a bulk result with rows left out is titled with the action\'s name, not its code',
      (tester) async {
    await _pump(
      tester,
      page: _page([
        {'id': 1, 'name': 'Acme Ltd'},
        {'id': 2, 'name': 'Globex'},
      ]),
      bulkActions: const [
        BulkActionSpec(
            action: 'SEND_EMAIL',
            label: 'Send email',
            icon: Icons.mail_outline,
            endpoint: '/api/emails/bulk'),
      ],
      bulkResult: {
        'action': 'SEND_EMAIL',
        'requested': 2,
        'succeeded': [1],
        'failed': [],
        'skipped': [
          {'id': 2, 'reason': 'Nobody holds Collection POC on Customer Globex'},
        ],
      },
    );

    await tester.tap(find.byType(Checkbox).at(1));
    await tester.tap(find.byType(Checkbox).at(2));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Send email'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    expect(find.text('Send email result'), findsOneWidget);
    expect(find.text('SEND_EMAIL result'), findsNothing);
    expect(find.text('1 succeeded, 0 failed, 1 skipped, of 2 requested.'), findsOneWidget);
    expect(find.text('#2 — Nobody holds Collection POC on Customer Globex'), findsOneWidget);
  });

  testWidgets('row actions stay beside the rows while wide columns scroll, level with their row',
      (tester) async {
    final rows = _page([
      {'id': 1, 'name': 'Acme Ltd ' * 30},
      {'id': 2, 'name': 'Globex'},
    ]);
    await _pump(tester, page: rows, canExport: true);

    final open = find.byTooltip('Open');
    expect(open, findsNWidgets(2));
    for (var i = 0; i < 2; i++) {
      expect(tester.getRect(open.at(i)).right, lessThanOrEqualTo(1366));
      expect(tester.getCenter(open.at(i)).dy,
          moreOrLessEquals(tester.getCenter(find.byType(Checkbox).at(i + 1)).dy, epsilon: 0.5));
    }
    await tester.tap(find.byType(Checkbox).at(2));
    await tester.pumpAndSettle();
    final actionRows = tester.widget<DataTable>(find.byType(DataTable).last).rows;
    expect(actionRows.map((r) => r.selected), [false, true]);
  });

  testWidgets('"Select all matching" ticks the rows it claims, and unticking one is visible',
      (tester) async {
    await _pump(tester, canExport: true, page: _page([
      {'id': 1, 'name': 'Acme Ltd'},
      {'id': 2, 'name': 'Globex'},
      {'id': 3, 'name': 'Initech'},
    ], total: 45));

    await tester.tap(find.byType(Checkbox).at(1));
    await tester.tap(find.byType(Checkbox).at(2));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Select all 45 matching this filter'));
    await tester.pumpAndSettle();

    // What the toolbar claims is what the rows show: every row ticked, the heading fully
    // checked rather than indeterminate (TBL-06).
    expect(find.text('45 selected'), findsOneWidget);
    expect(tester.widgetList<Checkbox>(find.byType(Checkbox)).map((c) => c.value).toList(),
        [true, true, true, true]);

    await tester.tap(find.byType(Checkbox).at(2));
    await tester.pumpAndSettle();
    expect(find.text('2 selected'), findsOneWidget);
    expect(tester.widgetList<Checkbox>(find.byType(Checkbox)).map((c) => c.value).toList(),
        [null, true, false, true]);
  });

  testWidgets('a failed page keeps the Rows selector, so a bad size in the URL is fixable',
      (tester) async {
    TableQuery? requested;
    await _pump(
      tester,
      page: _page([]),
      query: const TableQuery(size: 13),
      pageError: 'size must be one of [10, 20, 50]',
      onQueryChanged: (q) => requested = q,
    );

    expect(find.text('Request failed'), findsOneWidget);
    expect(find.text('size must be one of [10, 20, 50]'), findsOneWidget);
    // Retry can only fail the same way; the Rows selector is the way out (TBL-09).
    expect(find.text('Rows'), findsOneWidget);

    await tester.tap(find.byType(DropdownButton<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('50').last);
    await tester.pumpAndSettle();
    expect(requested?.size, 50);
  });

  testWidgets('on a phone the summary tiles go two to a row instead of one', (tester) async {
    const phone = Size(390, 844);
    await _pump(
      tester,
      size: phone,
      page: _page([
        {'id': 1, 'name': 'Acme Ltd'},
      ]),
      tiles: (context, s) => const Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          SummaryTile(label: 'Invoices', value: '45'),
          SummaryTile(label: 'Total billed', value: '₹1.2Cr'),
          SummaryTile(label: 'Outstanding', value: '₹40L'),
          SummaryTile(label: 'Overdue', value: '₹12L'),
        ],
      ),
    );

    final tiles = find.byType(SummaryTile);
    expect(tiles, findsNWidgets(4));
    final first = tester.getRect(tiles.at(0));
    final second = tester.getRect(tiles.at(1));
    final third = tester.getRect(tiles.at(2));

    // Two up, using the width rather than leaving half the row empty (UI-07).
    expect(second.top, first.top);
    expect(second.left, greaterThan(first.right));
    expect(second.right, greaterThan(phone.width * 0.9));
    expect(third.top, greaterThan(first.bottom));
    expect(tester.getRect(find.text('Acme Ltd')).top, lessThan(phone.height));
  });

  test('row numbers are 0–0 on an empty page, even past the end', () {
    final past = PagedResult.fromJson(
        {'content': [], 'page': 99, 'size': 10, 'totalElements': 4, 'totalPages': 1});
    expect(past.firstRowNumber, 0);
    expect(past.lastRowNumber, 0);
  });
}
