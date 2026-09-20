import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:go_router/go_router.dart';

// A filtered list is shared and bookmarked as its URL (AC-D4, §10 of the implementation notes),
// so a filter written to the URL must read back as the very same filter. A '+' — a phone number,
// a plus-addressed email, "+GST" in a note — was written unencoded and read back as a space, and
// the link then quietly showed different rows (TBL-03).

/// A list screen reduced to the URL round-trip: it prints the filters the route carries, and
/// pushes [next] when "apply" is tapped.
class _ListScreen extends StatelessWidget {
  final GoRouterState state;
  final TableQuery next;
  const _ListScreen(this.state, this.next);

  @override
  Widget build(BuildContext context) {
    final query = RouteQuery.read(state, defaultSize: 20, defaultSort: 'name,asc');
    return Scaffold(
      body: Column(
        children: [
          Text('size=${query.size} sort=${query.sort} page=${query.page}'),
          for (final f in query.filters) Text(f.wire),
          TextButton(
            onPressed: () => RouteQuery(context, '/customers').push(next),
            child: const Text('apply'),
          ),
        ],
      ),
    );
  }
}

Future<GoRouter> _pump(WidgetTester tester, TableQuery next,
    {String at = '/customers'}) async {
  final router = GoRouter(
    initialLocation: at,
    routes: [
      GoRoute(path: '/customers', builder: (_, s) => _ListScreen(s, next)),
    ],
  );
  await tester.pumpWidget(MaterialApp.router(routerConfig: router));
  await tester.pumpAndSettle();
  return router;
}

void main() {
  group('a filter written to the URL reads back unchanged (AC-D4)', () {
    // Each of these means something different to the server from what '+'-for-space decoding
    // would make of it.
    const awkward = {
      'phone': '+91 5551234',
      'email': 'ap+billing@acme.example',
      'name': 'Smith & Sons',
      'notes': 'lot #7 = 100% paid',
      'address': 'a/b?c',
    };

    for (final MapEntry(key: field, value: value) in awkward.entries) {
      testWidgets('"$value" survives being pushed and read back', (tester) async {
        final filter = TableFilter(field, 'contains', [value]);
        final router = await _pump(tester, TableQuery(size: 10, sort: 'name,asc', filters: [filter]));

        await tester.tap(find.text('apply'));
        await tester.pumpAndSettle();

        // The chip the user sees is built from the URL, and says exactly what they filtered on.
        expect(find.text(filter.wire), findsOneWidget);
        expect(find.text('size=10 sort=name,asc page=0'), findsOneWidget);
        // Nothing in the link stands for something else, so the address bar cannot show it as a
        // different filter from the one that was applied.
        final url = router.routerDelegate.currentConfiguration.uri.toString();
        expect(url, isNot(contains(' ')));
        expect(url, isNot(contains('+')));
      });

    }

    // The address bar shows the escapes that do not change the URL's shape decoded — a ':' for
    // %3A, a '+' for %2B, a space for %20 — and that is the form people copy out of it into a
    // message, a bookmark or another tab. This is the link the bug was reported on.
    String asShown(String url) => url
        .replaceAll('%3A', ':')
        .replaceAll('%2C', ',')
        .replaceAll('%2B', '+')
        .replaceAll('%20', ' ');

    for (final value in ['+91 5551234', 'ap+billing@acme.example', 'Acme Ltd']) {
      testWidgets('a decoded link still filters on "$value"', (tester) async {
        final filter = TableFilter('phone', 'contains', [value]);
        final shown = asShown(
            RouteQuery.location('/customers', {'size': '10', 'f': [filter.wire]}));

        await _pump(tester, const TableQuery(), at: shown);

        expect(find.text(filter.wire), findsOneWidget);
      });
    }

    testWidgets('every filter chip keeps its own value', (tester) async {
      const filters = [
        TableFilter('phone', 'contains', ['+91 5551234']),
        TableFilter('status', 'in', ['UNPAID', 'PARTIALLY_PAID']),
      ];
      final router = await _pump(tester, const TableQuery(page: 2, size: 50, filters: filters));

      await tester.tap(find.text('apply'));
      await tester.pumpAndSettle();

      for (final f in filters) {
        expect(find.text(f.wire), findsOneWidget);
      }
      // No sort of its own is left out of the URL, so the list's default comes back.
      expect(find.text('size=50 sort=name,asc page=2'), findsOneWidget);
      expect(router.routerDelegate.currentConfiguration.uri.queryParametersAll['f'],
          filters.map((f) => f.wire).toList());
    });
  });

  group('RouteQuery.location', () {
    test("percent-encodes a '+' and a space rather than trading one for the other", () {
      final url = RouteQuery.location('/customers', {
        'size': '10',
        'f': ['phone:contains:+91 5551234'],
      });
      expect(url, '/customers?size=10&f=phone%3Acontains%3A%2B91%205551234');
    });

    test('repeats the key for each filter and leaves a bare path alone', () {
      expect(
          RouteQuery.location('/invoices', {
            'f': ['a:eq:1', 'b:eq:2'],
          }),
          '/invoices?f=a%3Aeq%3A1&f=b%3Aeq%3A2');
      expect(RouteQuery.location('/invoices', {}), '/invoices');
    });
  });
}
