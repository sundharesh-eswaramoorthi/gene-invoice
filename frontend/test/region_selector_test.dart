import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/dashboard/dashboard_providers.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:gene_invoice/shared/widgets/region_selector.dart';

import 'support/fake_backend.dart';

const _north = RegionAccess(id: 1, code: 'NORTH', name: 'North', defaultRegion: true);
const _south = RegionAccess(id: 2, code: 'SOUTH', name: 'South');
const _east = RegionAccess(id: 3, code: 'EAST', name: 'East');

CurrentUser _staff({List<RegionAccess> regions = const [_north, _south], int? customerId}) =>
    CurrentUser(
      id: 1,
      username: 'rita',
      fullName: 'Rita',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: const {Privileges.invoiceView, Privileges.invoiceManage},
      customerId: customerId,
      regions: regions,
    );

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? Function(RequestOptions) data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data(o), headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows,
        {List<int> book = const [], List<String> locked = const []}) =>
    {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'lockedFilters': locked,
      'bookRegionIds': book,
    };

final _rows = [
  for (final id in [1, 2]) {'id': id, 'invoiceNumber': 'INV-$id'},
];

Widget _invoices({List<BulkActionSpec> bulk = const [], bool bulkWhileHistorical = false}) =>
    DataTableScaffold<Map<String, dynamic>>(
      entity: 'invoices',
      path: '/api/invoices',
      query: const TableQuery(),
      onQueryChanged: (_) {},
      parse: (json) => json,
      idOf: (row) => row['id'] as int,
      columns: [TableColumnSpec(label: 'Number', cell: (_, row) => Text('${row['invoiceNumber']}'))],
      canExport: true,
      bulkActions: bulk,
      bulkWhileHistorical: bulkWhileHistorical,
    );

Future<(FakeBackend, ProviderContainer)> _pumpShell(
  WidgetTester tester, {
  required CurrentUser user,
  Widget child = const SizedBox.expand(),
  Map<String, Object? Function(RequestOptions)> routes = const {},
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend({
    'GET /api/table-schemas/invoices': (_) => {'entity': 'invoices', 'columns': const []},
    ...routes,
  });
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user),
  ]);
  addTearDown(container.dispose);

  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(theme: AppTheme.light(), home: AppShell(path: '/invoices', child: child)),
  ));
  await tester.pumpAndSettle();
  return (backend, container);
}

void main() {
  group('the region selector', () {
    testWidgets('is offered to staff with more than one region, and fetches nothing to do so',
        (tester) async {
      final (backend, _) = await _pumpShell(tester, user: _staff());

      expect(find.byType(RegionSelector), findsOneWidget);
      expect(find.text('All regions'), findsOneWidget);
      expect(backend.requests, isEmpty);
    });

    for (final (name, user) in [
      ('no region', _staff(regions: const [])),
      ('a single region', _staff(regions: const [_north])),
      ('a customer login', _staff(customerId: 5)),
    ]) {
      testWidgets('is hidden for $name', (tester) async {
        await _pumpShell(tester, user: user);

        expect(find.text('All regions'), findsNothing);
        expect(find.byIcon(Icons.public), findsNothing);
      });
    }

    testWidgets('choosing regions asks the list again for just those, and says which',
        (tester) async {
      final (backend, container) = await _pumpShell(tester,
          user: _staff(regions: const [_north, _south, _east]),
          child: _invoices(),
          routes: {'GET /api/invoices': _answer((_) => _page(_rows))});
      expect(backend.sent('GET /api/invoices').single.queryParameters.containsKey('regionIds'),
          isFalse);

      // Every region is ticked while all are asked about; unticking one leaves the others.
      await tester.tap(find.text('All regions'));
      await tester.pumpAndSettle();
      expect(
          tester.widget<CheckedPopupMenuItem<int>>(
              find.widgetWithText(CheckedPopupMenuItem<int>, 'NORTH · North')).checked,
          isTrue);
      await tester.tap(find.text('NORTH · North'));
      await tester.pumpAndSettle();

      expect(container.read(viewScopeProvider).regionIds, {2, 3});
      expect(backend.sent('GET /api/invoices').last.queryParameters['regionIds'], '2,3');
      expect(find.text('SOUTH, EAST'), findsOneWidget);

      await tester.tap(find.text('SOUTH, EAST'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('EAST · East'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/invoices').last.queryParameters['regionIds'], '2');
      expect(find.text('SOUTH'), findsOneWidget);

      // The last region left cannot be unticked: something is always being asked about.
      await tester.tap(find.text('SOUTH'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('SOUTH · South'));
      await tester.pumpAndSettle();
      expect(container.read(viewScopeProvider).regionIds, {2});

      await tester.tap(find.text('SOUTH'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('All my regions'));
      await tester.pumpAndSettle();
      expect(container.read(viewScopeProvider).regionIds, isNull);
      expect(backend.sent('GET /api/invoices').last.queryParameters.containsKey('regionIds'),
          isFalse);
      expect(find.text('All regions'), findsOneWidget);
    });

    testWidgets('ticking every region again is the same as all of them', (tester) async {
      final (_, container) = await _pumpShell(tester, user: _staff());
      container.read(viewScopeProvider.notifier).setRegions({1});
      await tester.pumpAndSettle();

      await tester.tap(find.text('NORTH'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('SOUTH · South'));
      await tester.pumpAndSettle();

      expect(container.read(viewScopeProvider).regionIds, isNull);
    });

    testWidgets('on a phone it is an icon, which still says what is chosen', (tester) async {
      final (_, container) = await _pumpShell(tester, user: _staff(), size: const Size(390, 844));
      container.read(viewScopeProvider.notifier).setRegions({2});
      await tester.pumpAndSettle();

      expect(find.text('SOUTH'), findsNothing);
      expect(find.byIcon(Icons.public), findsOneWidget);
      expect(find.byTooltip('Regions: SOUTH'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('everything else that counts asks again with the regions', () {
    late FakeBackend backend;
    late ProviderContainer container;

    /// Waits for each provider's answer, not for time: reading `.future` builds again a provider the
    /// view made stale, so once this completes its request has gone out and come back.
    Future<void> answered(Iterable<ProviderListenable<Future<Object?>>> futures) =>
        Future.wait([for (final f in futures) container.read(f)]);

    setUp(() {
      backend = FakeBackend({
        for (final path in [
          '/api/dashboard/billed-by-month',
          '/api/dashboard/collected-by-month',
          '/api/dashboard/outstanding-by-age',
          '/api/dashboard/top-outstanding-customers',
          '/api/dashboard/top-paying-customers',
        ])
          'GET $path': _answer((_) => {'coverage': 'ALL'}),
        'GET /api/invoices/summary': _answer((_) => <String, dynamic>{}),
        'GET /api/promises/summary': _answer((_) => <String, dynamic>{}),
        'GET /api/promises': _answer((_) => _page(const [])),
        'GET /api/notifications/unread-count': (_) => {'count': 3},
        'GET /api/inbox/unread-count': (_) => {'count': 4},
      });
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(_staff()),
      ]);
      addTearDown(container.dispose);
    });

    test('every dashboard figure', () async {
      final providers = <AutoDisposeFutureProvider<Object?>>[
        billedByMonthProvider,
        collectedByMonthProvider,
        outstandingByAgeProvider,
        topOutstandingProvider,
        topPayingProvider,
        invoiceSummaryProvider,
        promiseSummaryProvider,
        upcomingPromisesProvider,
      ];
      for (final p in providers) {
        container.listen(p, (_, __) {});
      }
      await answered([for (final p in providers) p.future]);
      final paths = backend.requests.map((r) => r.path).toSet();
      expect(paths, hasLength(8));
      expect(backend.requests.any((r) => r.queryParameters.containsKey('regionIds')), isFalse);

      final before = backend.requests.length;
      container.read(viewScopeProvider.notifier).setRegions({2});
      await answered([for (final p in providers) p.future]);

      final again = backend.requests.skip(before).toList();
      expect(again.map((r) => r.path).toSet(), paths);
      expect(again.map((r) => r.queryParameters['regionIds']).toSet(), {'2'});
    });

    test('the bell and the inbox badge', () async {
      final bell = <int>[];
      final inbox = <int>[];
      container.listen(unreadCountProvider, (_, next) => next.whenData(bell.add));
      container.listen(inboxUnreadCountProvider, (_, next) => next.whenData(inbox.add));
      await answered([unreadCountProvider.future, inboxUnreadCountProvider.future]);
      expect(bell, [3]);
      expect(inbox, [4]);

      container.read(viewScopeProvider.notifier).setRegions({2});
      await answered([unreadCountProvider.future, inboxUnreadCountProvider.future]);

      for (final path in ['/api/notifications/unread-count', '/api/inbox/unread-count']) {
        expect(backend.sent('GET $path').map((r) => r.queryParameters['regionIds']), [null, '2']);
      }
    });

    test('the counts answer live: a past date alone does not ask them again', () async {
      container.listen(unreadCountProvider, (_, __) {});
      await answered([unreadCountProvider.future]);

      // Were the date to make the count stale, this read would build it again and ask again.
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await answered([unreadCountProvider.future]);

      expect(backend.sent('GET /api/notifications/unread-count'), hasLength(1));
    });
  });

  group('the as-of chip', () {
    Map<String, Object? Function(RequestOptions)> range(String? startsOn, String latest) => {
          'GET /api/history/range': (_) =>
              {'historyStartsOn': startsOn, 'latestAsOf': latest, 'timezone': 'UTC'},
        };

    testWidgets('reads Today, and asks for nothing until it is tapped', (tester) async {
      final (backend, _) = await _pumpShell(tester, user: _staff(), routes: range(null, '2026-09-25'));

      expect(find.text('Today'), findsOneWidget);
      expect(backend.requests, isEmpty);
    });

    testWidgets('is not offered to customer logins', (tester) async {
      await _pumpShell(tester, user: _staff(customerId: 5));

      expect(find.text('Today'), findsNothing);
    });

    testWidgets('opens a picker bounded by the history the server keeps', (tester) async {
      final (backend, container) =
          await _pumpShell(tester, user: _staff(), routes: range('2026-06-01', '2026-09-25'));

      await tester.tap(find.text('Today'));
      await tester.pumpAndSettle();

      expect(backend.sent('GET /api/history/range'), hasLength(1));
      final picker = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      expect(picker.firstDate, DateTime(2026, 6, 1));
      // Yesterday at the latest: today is simply live, and never sent.
      expect(picker.lastDate, DateTime(2026, 9, 25));
      expect(picker.initialDate, DateTime(2026, 9, 25));

      await tester.tap(find.text('20'));
      await tester.tap(find.text('View'));
      await tester.pumpAndSettle();

      expect(container.read(viewScopeProvider).asOf, DateTime(2026, 9, 20));
      expect(find.text('As of 20 Sep 2026'), findsOneWidget);
    });

    testWidgets('with no lower bound, the picker starts in 2000', (tester) async {
      await _pumpShell(tester, user: _staff(), routes: range(null, '2026-09-25'));

      await tester.tap(find.text('Today'));
      await tester.pumpAndSettle();

      expect(tester.widget<DatePickerDialog>(find.byType(DatePickerDialog)).firstDate,
          DateTime(2000));
    });

    testWidgets('says so when history has not started yet', (tester) async {
      await _pumpShell(tester, user: _staff(), routes: range('2026-10-01', '2026-09-25'));

      await tester.tap(find.text('Today'));
      await tester.pumpAndSettle();

      expect(find.byType(DatePickerDialog), findsNothing);
      expect(find.text('History starts on 1 Oct 2026, so there is no earlier day to view yet.'),
          findsOneWidget);
    });

    testWidgets('the banner appears on a past date, and Back to today clears it', (tester) async {
      final (_, container) = await _pumpShell(tester, user: _staff());
      expect(find.textContaining('read only'), findsNothing);

      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();
      expect(find.text('Viewing 30 Jun 2026 (end of day, UTC) — read only'), findsOneWidget);
      expect(find.text('As of 30 Jun 2026'), findsOneWidget);

      await tester.tap(find.text('Back to today'));
      await tester.pumpAndSettle();
      expect(container.read(viewScopeProvider).asOf, isNull);
      expect(find.textContaining('read only'), findsNothing);
      expect(find.text('Today'), findsOneWidget);
    });

    testWidgets('its × also goes back to today', (tester) async {
      final (_, container) = await _pumpShell(tester, user: _staff());
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Back to today'));
      await tester.pumpAndSettle();

      expect(container.read(viewScopeProvider).asOf, isNull);
    });
  });

  group('a list', () {
    const bulk = [BulkActionSpec(action: 'CANCEL', label: 'Cancel invoices', icon: Icons.cancel)];

    Future<void> tickFirstRow(WidgetTester tester) async {
      await tester.tap(find.byType(Checkbox).at(1));
      await tester.pumpAndSettle();
    }

    testWidgets('offers its bulk actions today', (tester) async {
      await _pumpShell(tester,
          user: _staff(),
          child: _invoices(bulk: bulk),
          routes: {'GET /api/invoices': _answer((_) => _page(_rows))});
      await tickFirstRow(tester);

      expect(find.text('Cancel invoices'), findsOneWidget);
      expect(find.text('Export selected'), findsOneWidget);
    });

    testWidgets('hides its bulk actions on a past date, but still exports', (tester) async {
      final (_, container) = await _pumpShell(tester,
          user: _staff(),
          child: _invoices(bulk: bulk),
          routes: {
            'GET /api/invoices': _answer((o) => {..._page(_rows), 'asOf': o.queryParameters['asOf']}),
          });
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();
      await tickFirstRow(tester);

      expect(find.text('1 selected'), findsOneWidget);
      expect(find.text('Cancel invoices'), findsNothing);
      expect(find.text('Export selected'), findsOneWidget);
    });

    testWidgets('keeps housekeeping on a past date', (tester) async {
      final (_, container) = await _pumpShell(tester,
          user: _staff(),
          child: _invoices(bulk: bulk, bulkWhileHistorical: true),
          routes: {'GET /api/invoices': _answer((_) => _page(_rows))});
      container.read(viewScopeProvider.notifier).setAsOf(DateTime(2026, 6, 30));
      await tester.pumpAndSettle();
      await tickFirstRow(tester);

      expect(find.text('Cancel invoices'), findsOneWidget);
    });

    group('says where only my book is shown', () {
      for (final (name, book, locked, shown) in [
        ('some regions are my book only', [2], <String>[], true),
        ('the whole list is my book, which the locked chip says', [2], ['myBook:eq:true'], false),
        ('no region is book only', <int>[], <String>[], false),
      ]) {
        testWidgets(name, (tester) async {
          await _pumpShell(tester,
              user: _staff(),
              child: _invoices(),
              routes: {'GET /api/invoices': _answer((_) => _page(_rows, book: book, locked: locked))});

          expect(find.text('Your book only in: SOUTH'), shown ? findsOneWidget : findsNothing);
          expect(find.text('INV-1'), findsOneWidget);
        });
      }
    });
  });
}
