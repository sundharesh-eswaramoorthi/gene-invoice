import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/router.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/storage/secure_storage.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/regions/region_detail_screen.dart';
import 'package:gene_invoice/features/regions/regions_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/fake_backend.dart';

const _north = RegionAccess(
    id: 1, code: 'NORTH', name: 'North', defaultRegion: true, level: 'APPROVE', scope: 'ALL');

CurrentUser _user(Set<String> privileges) => CurrentUser(
      id: 1,
      username: 'admin',
      fullName: 'Ada Admin',
      role: 'ADMIN',
      privileges: privileges,
      customerId: null,
      regions: const [_north],
    );

final _admin = _user(const {
  Privileges.regionView,
  Privileges.regionManage,
  Privileges.userView,
  Privileges.userManage,
});

Map<String, dynamic> _region(int id, String code, String name,
        {bool defaultRegion = false, bool active = true, int version = 0}) =>
    {
      'id': id,
      'code': code,
      'name': name,
      'active': active,
      'defaultRegion': defaultRegion,
      'version': version,
      'createdAt': '2026-09-26T10:00:00Z',
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'regionIds': const [],
    };

final _listRoutes = <String, Object? Function(RequestOptions)>{
  'GET /api/table-schemas/regions': (_) => {'entity': 'regions', 'columns': const []},
  'GET /api/regions': (_) => _page([
        _region(1, 'NORTH', 'North', defaultRegion: true),
        _region(2, 'SOUTH', 'South', version: 3),
        _region(3, 'EAST', 'East', active: false),
      ]),
};

Future<FakeBackend> _pump(
  WidgetTester tester, {
  required Widget page,
  required Map<String, Object? Function(RequestOptions)> routes,
  CurrentUser? user,
  DateTime? asOf,
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes));
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user ?? _admin),
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/page', builder: (_, __) => page),
        GoRoute(path: '/regions/:id', builder: (_, s) => Text('region ${s.pathParameters['id']}')),
        GoRoute(path: '/users/:id', builder: (_, s) => Text('user ${s.pathParameters['id']}')),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return backend;
}

const _list = RegionsScreen(query: TableQuery(sort: 'code,asc'));

Future<void> _openNewRegion(WidgetTester tester) async {
  await tester.tap(find.text('New region'));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const ValueKey('region-code')), 'south-east');
  await tester.enterText(find.byKey(const ValueKey('region-name')), '  South East ');
}

class _MemoryTokens implements TokenStorage {
  String? token;
  @override
  Future<void> save(String t) async => token = t;
  @override
  Future<String?> read() async => token;
  @override
  Future<void> clear() async => token = null;
}

Map<String, dynamic> _me(List<Map<String, dynamic>> regions) => {
      'id': 1,
      'username': 'admin',
      'fullName': 'Ada Admin',
      'role': 'ADMIN',
      'privileges': [Privileges.regionView, Privileges.regionManage],
      'regions': regions,
    };

void main() {
  group("the signed-in user's own regions", () {
    testWidgets('are read again after a change, without leaving the page or the regions viewed',
        (tester) async {
      SharedPreferences.setMockInitialValues({});
      final northOnly = [
        {'id': 1, 'code': 'NORTH', 'name': 'North', 'defaultRegion': true, 'level': 'APPROVE'},
      ];
      final both = [
        ...northOnly,
        {'id': 9, 'code': 'SOUTH-EAST', 'name': 'South East', 'level': 'APPROVE'},
      ];
      final backend = FakeBackend({
        'POST /api/auth/login': (_) => {'token': 't', 'expiresInMs': 1000, 'user': _me(northOnly)},
        'GET /api/auth/me': (_) => _me(both),
      });
      final container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(backend.dio),
        tokenStorageProvider.overrideWithValue(_MemoryTokens()),
      ]);
      addTearDown(container.dispose);
      final auth = container.read(authControllerProvider.notifier);

      await tester.runAsync(() => auth.login('admin', 'pw'));
      final router = container.read(routerProvider);
      container.read(viewScopeProvider.notifier).setRegions({1});
      expect(container.read(currentUserProvider)!.regions.map((r) => r.code), ['NORTH']);

      await tester.runAsync(auth.refreshUser);
      expect(container.read(currentUserProvider)!.regions.map((r) => r.code),
          ['NORTH', 'SOUTH-EAST']);
      // The same router — a new one would start again from the dashboard — and the same view.
      expect(identical(container.read(routerProvider), router), isTrue);
      expect(container.read(viewScopeProvider).regionIds, {1});

      await tester.runAsync(auth.logout);
      expect(identical(container.read(routerProvider), router), isFalse);
    });
  });

  group('the regions list', () {
    testWidgets('lists every region, marks the default, and is in the sidebar', (tester) async {
      final backend = await _pump(tester, page: _list, routes: _listRoutes);
      expect(tester.takeException(), isNull);

      expect(backend.sent('GET /api/regions'), hasLength(1));
      expect(find.text('NORTH'), findsOneWidget);
      expect(find.text('SOUTH'), findsOneWidget);
      expect(find.text('EAST'), findsOneWidget);
      expect(find.text('Default'), findsOneWidget);
      // Only an active region that is not already the default can become it.
      expect(find.byTooltip('Make default'), findsOneWidget);
      expect(find.text('New region'), findsOneWidget);
      expect(find.byIcon(Icons.public_outlined), findsOneWidget);
    });

    testWidgets('someone who may only look is offered no changes', (tester) async {
      await _pump(tester,
          page: _list, routes: _listRoutes, user: _user(const {Privileges.regionView}));
      expect(find.text('NORTH'), findsOneWidget);
      expect(find.text('New region'), findsNothing);
      expect(find.byTooltip('Edit'), findsNothing);
      expect(find.byTooltip('Make default'), findsNothing);
    });

    testWidgets('nor is anyone on a past date, when the list is still today\'s', (tester) async {
      final backend = await _pump(tester,
          page: _list,
          routes: {
            ..._listRoutes,
            // Regions answer live whatever the date (D9).
            'GET /api/regions': (_) => FakeReply(200, _page([_region(2, 'SOUTH', 'South')]),
                headers: {'X-View-As-Of': ['live']}),
          },
          asOf: DateTime(2026, 6, 30));
      expect(find.text('SOUTH'), findsOneWidget);
      expect(find.text('New region'), findsNothing);
      expect(find.byTooltip('Edit'), findsNothing);
      expect(find.byTooltip('Make default'), findsNothing);
      expect(find.text("Showing current data — history isn't kept for this list"), findsOneWidget);
      expect(backend.sent('GET /api/regions').single.queryParameters['asOf'], '2026-06-30');
    });

    testWidgets('the sidebar has no Regions entry without REGION_VIEW', (tester) async {
      await _pump(tester,
          page: const SizedBox.expand(), routes: const {}, user: _user(const {Privileges.userView}));
      expect(find.byIcon(Icons.public_outlined), findsNothing);
      expect(navPrivilegesFor('/regions'), [Privileges.regionView]);
    });

    testWidgets('the create form posts the code in capitals, the trimmed name and active',
        (tester) async {
      final backend = await _pump(tester, page: _list, routes: {
        ..._listRoutes,
        'POST /api/regions': (o) => {..._region(9, 'SOUTH-EAST', 'South East'), ...o.data},
      });
      await _openNewRegion(tester);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      final post = backend.sent('POST /api/regions');
      expect(post, hasLength(1));
      expect(post.single.data, {'code': 'SOUTH-EAST', 'name': 'South East', 'active': true});
      expect(find.text('New region'), findsOneWidget);
      expect(find.byType(AlertDialog), findsNothing);
      // The list is asked again.
      expect(backend.sent('GET /api/regions').length, greaterThan(1));
    });

    testWidgets("the server's field errors read under their fields and the form stays open",
        (tester) async {
      final backend = await _pump(tester, page: _list, routes: {
        ..._listRoutes,
        'POST /api/regions': (_) => const FakeFailure(400, {
              'message': 'Validation failed',
              'fieldErrors': {'code': 'A region with this code already exists'},
            }),
      });
      await _openNewRegion(tester);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(backend.sent('POST /api/regions'), hasLength(1));
      expect(find.byType(AlertDialog), findsOneWidget);
      expect(find.text('A region with this code already exists'), findsOneWidget);
      expect(find.text('Validation failed'), findsNothing);
    });

    testWidgets('a code the server would refuse is caught before sending', (tester) async {
      final backend = await _pump(tester, page: _list, routes: _listRoutes);
      await tester.tap(find.text('New region'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('region-code')), 'x');
      await tester.enterText(find.byKey(const ValueKey('region-name')), 'Tiny');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(find.text('2 to 20 capitals, digits, - or _'), findsOneWidget);
      expect(backend.sent('POST /api/regions'), isEmpty);
    });

    testWidgets('editing sends the name, active and version, never the code', (tester) async {
      final backend = await _pump(tester, page: _list, routes: {
        ..._listRoutes,
        'PUT /api/regions/2': (o) => _region(2, 'SOUTH', 'South & East', active: false),
      });
      await tester.tap(find.byTooltip('Edit').at(1));
      await tester.pumpAndSettle();
      final code = tester.widget<TextField>(find.descendant(
          of: find.byKey(const ValueKey('region-code')), matching: find.byType(TextField)));
      expect(code.enabled, isFalse);
      await tester.enterText(find.byKey(const ValueKey('region-name')), 'South & East');
      await tester.tap(find.byType(Switch));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(backend.sent('PUT /api/regions/2').single.data,
          {'name': 'South & East', 'active': false, 'version': 3});
    });

    testWidgets('make default asks first, then posts', (tester) async {
      final backend = await _pump(tester, page: _list, routes: {
        ..._listRoutes,
        'POST /api/regions/2/make-default': (_) => _region(2, 'SOUTH', 'South', defaultRegion: true),
      });
      await tester.tap(find.byTooltip('Make default'));
      await tester.pumpAndSettle();
      expect(find.text('Make SOUTH the default region?'), findsOneWidget);
      expect(backend.sent('POST /api/regions/2/make-default'), isEmpty);

      await tester.tap(find.widgetWithText(FilledButton, 'Make default'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/regions/2/make-default'), hasLength(1));
      expect(find.text('SOUTH is now the default region'), findsOneWidget);
    });
  });

  group("a region's page", () {
    final detailRoutes = <String, Object? Function(RequestOptions)>{
      'GET /api/regions/2': (_) => _region(2, 'SOUTH', 'South', version: 3),
      'GET /api/regions/2/grants': (_) => [
            {
              'userId': 7,
              'username': 'rita',
              'fullName': 'Rita Rao',
              'roleName': 'CASHIER',
              'level': 'MANAGE',
              'scope': 'ALL',
            },
            {
              'userId': 8,
              'username': 'sam',
              'fullName': null,
              'roleName': 'SALES_POC',
              'level': 'READ',
              'scope': 'BOOK',
            },
          ],
    };

    testWidgets('its Grants tab lists who holds access there, and at what', (tester) async {
      final backend = await _pump(tester,
          page: const RegionDetailScreen(id: 2, initialTab: 'grants'), routes: detailRoutes);
      expect(tester.takeException(), isNull);

      expect(backend.sent('GET /api/regions/2/grants'), hasLength(1));
      expect(find.text('Rita Rao'), findsOneWidget);
      expect(find.text('Manage • All records'), findsOneWidget);
      expect(find.text('sam'), findsOneWidget);
      expect(find.text('Read • Own book'), findsOneWidget);
      expect(find.text('Make default'), findsOneWidget);

      await tester.tap(find.text('Rita Rao'));
      await tester.pumpAndSettle();
      expect(find.text('user 7'), findsOneWidget);
    });

    testWidgets('without USER_VIEW there is no Grants tab', (tester) async {
      final backend = await _pump(tester,
          page: const RegionDetailScreen(id: 2),
          routes: detailRoutes,
          user: _user(const {Privileges.regionView}));
      expect(find.text('Grants'), findsNothing);
      expect(find.text('Edit'), findsNothing);
      expect(backend.sent('GET /api/regions/2/grants'), isEmpty);
    });

    testWidgets('delete is refused in words the server chose', (tester) async {
      final backend = await _pump(tester,
          page: const RegionDetailScreen(id: 2),
          routes: {
            ...detailRoutes,
            'DELETE /api/regions/2': (_) => const FakeFailure(400, {
                  'message':
                      'Region still has customers, disputes or grants; deactivate it instead',
                }),
          });
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delete region'));
      await tester.pumpAndSettle();

      expect(backend.sent('DELETE /api/regions/2'), hasLength(1));
      expect(find.text('Region still has customers, disputes or grants; deactivate it instead'),
          findsOneWidget);
    });
  });
}
