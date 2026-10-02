import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/as_of.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/dashboard/dashboard_charts.dart';
import 'package:gene_invoice/features/dashboard/dashboard_providers.dart';
import 'package:gene_invoice/features/dashboard/dashboard_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';

import 'support/fake_backend.dart';

const _user = CurrentUser(
  id: 1,
  username: 'rita',
  fullName: 'Rita',
  role: 'CASHIER',
  privileges: {},
  customerId: null,
  regions: [
    RegionAccess(id: 1, code: 'NORTH', name: 'North'),
    RegionAccess(id: 2, code: 'SOUTH', name: 'South'),
  ],
);

void main() {
  group('coverage', () {
    test('MIXED is its own coverage; anything unknown still reads as everything', () {
      expect(parseCoverage('MIXED'), Coverage.mixed);
      expect(parseCoverage('BOOK'), Coverage.book);
      expect(parseCoverage('OWN'), Coverage.own);
      expect(parseCoverage('ALL'), Coverage.all);
      expect(parseCoverage('SOMETHING_NEW'), Coverage.all);
      expect(parseCoverage(null), Coverage.all);
    });

    test('every figure carries the scope it answered for', () {
      const scope = {
        'regionIds': [1, 2],
        'bookRegionIds': [2],
        'asOf': '2026-06-30',
      };
      final series = MonthlySeries.fromJson({'coverage': 'MIXED', 'months': [], 'scope': scope});
      final ageing = OutstandingByAge.fromJson({'coverage': 'MIXED', 'buckets': [], 'scope': scope});
      final ranking =
          CustomerRanking.outstandingFromJson({'coverage': 'MIXED', 'customers': [], 'scope': scope});

      for (final (coverage, s) in [
        (series.coverage, series.scope),
        (ageing.coverage, ageing.scope),
        (ranking.coverage, ranking.scope),
      ]) {
        expect(coverage, Coverage.mixed);
        expect(s.regionIds, [1, 2]);
        expect(s.bookRegionIds, [2]);
        expect(s.asOf, '2026-06-30');
      }
    });

    test('a figure without a scope, from a server that predates regions, still parses', () {
      final series = MonthlySeries.fromJson({'coverage': 'BOOK', 'months': []});

      expect(series.coverage, Coverage.book);
      expect(series.scope.regionIds, isEmpty);
      expect(series.scope.bookRegionIds, isEmpty);
      expect(series.scope.asOf, isNull);
      expect(DashboardScope.fromJson({'regionIds': ['x', 3], 'asOf': null}).regionIds, [3]);
    });
  });

  group('the dashboard', () {
    Object? Function(RequestOptions) answer(Map<String, dynamic> data, {String? header}) =>
        (o) => FakeReply(200, data, headers: {
              if (header != null) 'X-View-As-Of': [header],
            });

    ProviderContainer containerFor(FakeBackend backend, {DateTime? asOf}) {
      late ProviderContainer container;
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(_user),
      ]);
      addTearDown(container.dispose);
      if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);
      return container;
    }

    test('refuses a figure the server answered live when a past date was asked for', () async {
      final backend = FakeBackend({
        'GET /api/dashboard/outstanding-by-age': answer({'coverage': 'ALL', 'buckets': []}),
      });
      final container = containerFor(backend, asOf: DateTime(2026, 6, 30));
      container.listen(outstandingByAgeProvider, (_, __) {});

      await expectLater(
          container.read(outstandingByAgeProvider.future), throwsA(isA<AsOfMismatch>()));
      expect(backend.sent('GET /api/dashboard/outstanding-by-age').single.queryParameters,
          {'asOf': '2026-06-30'});
    });

    test('takes a figure answered as of the date asked for', () async {
      final backend = FakeBackend({
        'GET /api/dashboard/outstanding-by-age':
            answer({'coverage': 'ALL', 'buckets': [], 'scope': {'asOf': '2026-06-30'}},
                header: '2026-06-30'),
      });
      final container = containerFor(backend, asOf: DateTime(2026, 6, 30));
      container.listen(outstandingByAgeProvider, (_, __) {});

      final ageing = await container.read(outstandingByAgeProvider.future);

      expect(ageing.scope.asOf, '2026-06-30');
    });

    test('its today is the date viewed, else today in UTC', () {
      final container = containerFor(FakeBackend({}));
      expect(container.read(dashboardTodayProvider), todayUtc());

      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      expect(container.read(dashboardTodayProvider), DateTime.utc(2026, 6, 30));
    });

    testWidgets('a refresh after midnight (UTC) moves today on with it', (tester) async {
      var now = DateTime.utc(2026, 9, 26, 23, 59);
      final backend = FakeBackend({
        'GET /api/promises': answer({'content': [], 'lockedFilters': []}),
      });
      late WidgetRef widgetRef;
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(_user),
          dashboardClockProvider.overrideWithValue(() => now),
        ],
        child: MaterialApp(
          home: Consumer(builder: (context, ref, _) {
            widgetRef = ref;
            ref.watch(upcomingPromisesProvider);
            return Text('today ${ref.watch(dashboardTodayProvider).day}');
          }),
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.text('today 26'), findsOneWidget);
      expect(backend.sent('GET /api/promises').last.queryParameters['filter'],
          contains('promisedDate:gte:2026-09-26'));

      now = DateTime.utc(2026, 9, 27, 0, 1);
      refreshDashboard(widgetRef);
      // Nothing animates, so let the fake clock run for the request to go out and come back.
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pumpAndSettle();

      expect(find.text('today 27'), findsOneWidget);
      expect(backend.sent('GET /api/promises').last.queryParameters['filter'],
          contains('promisedDate:gte:2026-09-27'));
    });

    test('upcoming promises start from the date viewed, and note book-only regions', () async {
      final backend = FakeBackend({
        'GET /api/promises': answer({
          'content': [],
          'lockedFilters': [],
          'bookRegionIds': [2],
        }, header: '2026-06-30'),
      });
      final container = containerFor(backend, asOf: DateTime(2026, 6, 30));
      container.listen(upcomingPromisesProvider, (_, __) {});

      final upcoming = await container.read(upcomingPromisesProvider.future);

      expect(backend.sent('GET /api/promises').single.queryParameters['filter'],
          ['status:in:OPEN,PARTIALLY_KEPT', 'promisedDate:gte:2026-06-30']);
      expect(upcoming.book, isFalse);
      expect(upcoming.bookRegionIds, [2]);
    });

    testWidgets('a card that is partly my book names the regions where it is', (tester) async {
      String? label;
      await tester.pumpWidget(ProviderScope(
        overrides: [currentUserProvider.overrideWithValue(_user)],
        child: MaterialApp(
          home: Consumer(builder: (context, ref, _) {
            label = mixedBookRegions(ref, Coverage.mixed, const [2]);
            return DashboardCard(title: 'Billed by month', bookRegions: label, child: const SizedBox());
          }),
        ),
      ));

      expect(label, 'SOUTH');
      expect(find.text('Your book in SOUTH'), findsOneWidget);
      expect(find.text('Your book'), findsNothing);
    });

    testWidgets('a card that is wholly one coverage or the other names no regions', (tester) async {
      final labels = <String?>[];
      await tester.pumpWidget(ProviderScope(
        overrides: [currentUserProvider.overrideWithValue(_user)],
        child: MaterialApp(
          home: Consumer(builder: (context, ref, _) {
            labels
              ..clear()
              ..addAll([
                mixedBookRegions(ref, Coverage.all, const [2]),
                mixedBookRegions(ref, Coverage.book, const [2]),
                mixedBookRegions(ref, null, const []),
              ]);
            return const DashboardCard(title: 'Billed by month', book: true, child: SizedBox());
          }),
        ),
      ));

      expect(labels, [null, null, null]);
      expect(find.text('Your book'), findsOneWidget);
    });
  });
}
