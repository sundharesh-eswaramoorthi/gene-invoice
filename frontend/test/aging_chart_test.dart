import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/features/dashboard/dashboard_charts.dart';
import 'package:gene_invoice/features/dashboard/dashboard_providers.dart';
import 'package:go_router/go_router.dart';

// The ageing chart after Feature B: it measures days past due, so its buckets are the server's
// new ones and each opens the invoice list at exactly the invoices behind it (AC-B5, AC-B6).

final _today = DateTime.utc(2026, 9, 20);

DateTime _d(int year, int month, int day) => DateTime.utc(year, month, day);

/// The buckets §3 defines, exactly as the backend sends them for [_today]: the bounds say how
/// late the band is, and the two dates are the window the invoice list filters by. Either end is
/// null where the band is open.
final _buckets = [
  AgeBucket(
      label: 'Not yet due',
      fromDays: null,
      toDays: 0,
      amount: 5000,
      count: 4,
      dueDateFrom: _d(2026, 9, 20)),
  AgeBucket(
      label: '1–30 days',
      fromDays: 1,
      toDays: 30,
      amount: 3000,
      count: 3,
      dueDateFrom: _d(2026, 8, 21),
      dueDateTo: _d(2026, 9, 19)),
  AgeBucket(
      label: '31–60 days',
      fromDays: 31,
      toDays: 60,
      amount: 2000,
      count: 2,
      dueDateFrom: _d(2026, 7, 22),
      dueDateTo: _d(2026, 8, 20)),
  AgeBucket(
      label: '61–90 days',
      fromDays: 61,
      toDays: 90,
      amount: 1000,
      count: 1,
      dueDateFrom: _d(2026, 6, 22),
      dueDateTo: _d(2026, 7, 21)),
  AgeBucket(
      label: 'Over 90 days',
      fromDays: 91,
      toDays: null,
      amount: 800,
      count: 1,
      dueDateTo: _d(2026, 6, 21)),
];

/// The same five bands from a server that sends only the day counts, which is all the deep link
/// needs to fall back on.
const _datelessBuckets = [
  AgeBucket(label: 'Not yet due', fromDays: null, toDays: 0, amount: 5000, count: 4),
  AgeBucket(label: '1–30 days', fromDays: 1, toDays: 30, amount: 3000, count: 3),
  AgeBucket(label: '31–60 days', fromDays: 31, toDays: 60, amount: 2000, count: 2),
  AgeBucket(label: '61–90 days', fromDays: 61, toDays: 90, amount: 1000, count: 1),
  AgeBucket(label: 'Over 90 days', fromDays: 91, toDays: null, amount: 800, count: 1),
];

/// The filters a bucket's link carries, or null when it carries no link at all.
List<String>? _filters(AgeBucket bucket, {DateTime? today}) {
  final link = AgingBars.linkFor(bucket, today ?? _today);
  return link == null ? null : Uri.parse(link).queryParametersAll['f'];
}

/// The chart, with somewhere for a bucket to lead. [landed] collects the filters it arrives with.
Future<void> _pumpBars(WidgetTester tester, List<AgeBucket> buckets,
    void Function(List<String>) landed) async {
  final router = GoRouter(routes: [
    GoRoute(
      path: '/',
      builder: (_, __) => Scaffold(
        body: AgingBars(
          data: OutstandingByAge(coverage: Coverage.all, buckets: buckets),
          today: _today,
        ),
      ),
    ),
    GoRoute(
      path: '/invoices',
      builder: (_, s) {
        landed(s.uri.queryParametersAll['f'] ?? const []);
        return const Scaffold(body: Text('invoices'));
      },
    ),
  ]);
  await tester.pumpWidget(MaterialApp.router(routerConfig: router));
  await tester.pumpAndSettle();
}

/// Where tapping [label] left the app, as its filter chips, or null when it went nowhere.
Future<List<String>?> _tap(WidgetTester tester, String label,
    {List<AgeBucket>? buckets}) async {
  List<String>? landed;
  await _pumpBars(tester, buckets ?? _buckets, (f) => landed = f);
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
  return landed;
}

void main() {
  testWidgets('the bars are labelled as the server labels them', (tester) async {
    await _pumpBars(tester, _buckets, (_) {});

    for (final b in _buckets) {
      expect(find.text(b.label), findsOneWidget, reason: b.label);
    }
    // Nothing is left saying "days since the invoice date" (AC-B5).
    expect(find.text('0–30 days'), findsNothing);
  });

  testWidgets('"Not yet due" opens the invoices due today or later (AC-B6)', (tester) async {
    expect(await _tap(tester, 'Not yet due'), [
      'status:in:UNPAID,PARTIALLY_PAID',
      'dueDate:gte:2026-09-20',
    ]);
  });

  testWidgets('a dated bucket opens exactly its own window', (tester) async {
    expect(await _tap(tester, '1–30 days'), [
      'status:in:UNPAID,PARTIALLY_PAID',
      'dueDate:between:2026-08-21,2026-09-19',
    ]);
  });

  testWidgets('the open-ended bucket has no far end', (tester) async {
    expect(await _tap(tester, 'Over 90 days'), [
      'status:in:UNPAID,PARTIALLY_PAID',
      'dueDate:lte:2026-06-21',
    ]);
  });

  test('every bucket is a due-date window, and the windows touch without overlapping (AC-B1)', () {
    expect(_filters(_buckets[2])!.last, 'dueDate:between:2026-07-22,2026-08-20');
    expect(_filters(_buckets[3])!.last, 'dueDate:between:2026-06-22,2026-07-21');

    // Read newest first, each bucket's window starts the day after the next one's ends.
    List<DateTime> window(AgeBucket b) => _filters(b)!
        .last
        .split(':')
        .last
        .split(',')
        .map((d) => DateTime.parse('${d}T00:00:00Z'))
        .toList();

    for (var i = 0; i < _buckets.length - 1; i++) {
      expect(window(_buckets[i]).first.difference(window(_buckets[i + 1]).last).inDays, 1,
          reason: '${_buckets[i].label} after ${_buckets[i + 1].label}');
    }
  });

  test('the window is the server\'s own, not this browser\'s idea of today (AC-B6)', () {
    // A clock a day out here would move every bound by a day and the bar would open a set of
    // rows the bucket never counted. The server sent the dates, so they stand.
    final skewed = _today.subtract(const Duration(days: 1));
    for (final b in _buckets) {
      expect(_filters(b, today: skewed), _filters(b),
          reason: '${b.label} must not follow the browser clock');
    }
  });

  test('a bucket that arrives with no dates falls back to its days past due', () {
    // The same five links, worked out against today rather than read off the response.
    for (var i = 0; i < _buckets.length; i++) {
      expect(_filters(_datelessBuckets[i])!.last, _filters(_buckets[i])!.last,
          reason: _buckets[i].label);
    }
  });

  test('the response is read as the backend writes it', () {
    final bucket = AgeBucket.fromJson(const {
      'label': 'Not yet due',
      'fromDays': null,
      'toDays': 0,
      'amount': 1200.5,
      'count': 2,
      'dueDateFrom': '2026-09-20',
      'dueDateTo': null,
    });

    expect(bucket.label, 'Not yet due');
    // Open at the lower end, and not quietly read as "nought days late".
    expect(bucket.fromDays, isNull);
    expect(bucket.toDays, 0);
    expect(bucket.amount, 1200.5);
    expect(bucket.count, 2);
    expect(bucket.dueDateFrom, _d(2026, 9, 20));
    expect(bucket.dueDateTo, isNull);
    expect(_filters(bucket)!.last, 'dueDate:gte:2026-09-20');
  });

  testWidgets('a bucket this build cannot express is shown but does not pretend to be a link',
      (tester) async {
    const odd = AgeBucket(label: 'Later', fromDays: 30, toDays: 5, amount: 900, count: 1);
    expect(AgingBars.linkFor(odd, _today), isNull);

    expect(await _tap(tester, 'Later', buckets: [..._buckets, odd]), isNull);
    expect(find.text('Later'), findsOneWidget);
    expect(find.text('invoices'), findsNothing);
  });

  testWidgets('a window the server sent back to front is no link either', (tester) async {
    final backwards = AgeBucket(
        label: 'Later',
        fromDays: 1,
        toDays: 30,
        amount: 900,
        count: 1,
        dueDateFrom: _d(2026, 9, 19),
        dueDateTo: _d(2026, 8, 21));
    expect(AgingBars.linkFor(backwards, _today), isNull);

    expect(await _tap(tester, 'Later', buckets: [..._buckets, backwards]), isNull);
  });
}
