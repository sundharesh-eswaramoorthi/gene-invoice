import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/regions/regions_screen.dart';
import 'package:gene_invoice/features/users/region_grants_editor.dart';
import 'package:gene_invoice/features/users/user_detail_screen.dart';
import 'package:gene_invoice/features/users/users_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/user.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// What the person editing holds: APPROVE over everything in NORTH, where they also approve
// changes, MANAGE in SOUTH and CENTRAL, and only READ over their own book in EAST.
const _north = RegionAccess(
    id: 1,
    code: 'NORTH',
    name: 'North',
    defaultRegion: true,
    level: 'APPROVE',
    scope: 'ALL',
    privileges: {Privileges.changeApprove});
const _south = RegionAccess(id: 2, code: 'SOUTH', name: 'South', level: 'MANAGE', scope: 'ALL');
const _east = RegionAccess(id: 3, code: 'EAST', name: 'East', level: 'READ', scope: 'BOOK');
const _central =
    RegionAccess(id: 5, code: 'CENTRAL', name: 'Central', level: 'MANAGE', scope: 'ALL');

CurrentUser _admin({int id = 1, List<RegionAccess> regions = const [_north, _south, _east, _central]}) =>
    CurrentUser(
      id: id,
      username: 'admin',
      fullName: 'Ada Admin',
      role: 'ADMIN',
      privileges: const {Privileges.userView, Privileges.userManage, Privileges.roleView},
      customerId: null,
      regions: regions,
      regionsReported: true,
    );

const _regionList = [
  Region(id: 1, code: 'NORTH', name: 'North', defaultRegion: true),
  Region(id: 2, code: 'SOUTH', name: 'South'),
];

Map<String, dynamic> _region(int id, String code, String name,
        {bool defaultRegion = false, bool active = true}) =>
    {'id': id, 'code': code, 'name': name, 'active': active, 'defaultRegion': defaultRegion};

final _regions = {
  'content': [
    _region(1, 'NORTH', 'North', defaultRegion: true),
    _region(2, 'SOUTH', 'South'),
    _region(3, 'EAST', 'East'),
    _region(4, 'WEST', 'West'),
    _region(5, 'CENTRAL', 'Central'),
  ],
  'page': 0,
  'size': 50,
  'totalElements': 5,
  'totalPages': 1,
};

/// Rita's access today: NORTH is within what the editor holds; SOUTH at APPROVE and EAST over
/// all records are beyond it.
const _ritaGrants = [
  {'regionId': 1, 'regionCode': 'NORTH', 'regionName': 'North', 'level': 'READ', 'scope': 'ALL'},
  {'regionId': 2, 'regionCode': 'SOUTH', 'regionName': 'South', 'level': 'APPROVE', 'scope': 'ALL'},
  {'regionId': 3, 'regionCode': 'EAST', 'regionName': 'East', 'level': 'READ', 'scope': 'ALL'},
];

Map<String, dynamic> _role(int id, String name, List<String> privileges) =>
    {'id': id, 'name': name, 'privileges': privileges};

Map<String, dynamic> _roles(List<Map<String, dynamic>> roles) =>
    {'content': roles, 'page': 0, 'size': 50, 'totalElements': roles.length, 'totalPages': 1};

const _collector = [
  Privileges.customerView,
  Privileges.invoiceView,
  Privileges.scopeOverride,
  Privileges.pocAssignableCollection,
];

Future<FakeBackend> _pump(
  WidgetTester tester, {
  required CurrentUser viewer,
  required Widget page,
  required Map<String, Object? Function(RequestOptions)> routes,
}) async {
  tester.view.physicalSize = const Size(1366, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes));
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(viewer),
  ]);
  addTearDown(container.dispose);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [GoRoute(path: '/page', builder: (_, __) => page)],
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

Map<String, Object? Function(RequestOptions)> _ritaRoutes({List<String> rolePrivileges = _collector}) =>
    {
      'GET /api/users/7': (_) => {
            'id': 7,
            'username': 'rita',
            'fullName': 'Rita',
            'active': true,
            'role': 'COLLECTOR',
          },
      'GET /api/users/7/region-grants': (_) => _ritaGrants,
      'GET /api/regions': (_) => _regions,
      'GET /api/roles': (_) => _roles([_role(4, 'COLLECTOR', rolePrivileges)]),
    };

Finder _segment(int regionId, String kind, String label) => find.descendant(
      of: find.byKey(ValueKey('grant-$kind-$regionId')),
      matching: find.ancestor(of: find.text(label), matching: find.byType(TextButton)),
    );

bool _enabled(WidgetTester tester, int regionId, String kind, String label) =>
    tester.widget<TextButton>(_segment(regionId, kind, label)).onPressed != null;

void main() {
  group("a user's region access", () {
    testWidgets('saves every row, the untouchable ones unchanged, in one PUT', (tester) async {
      final backend = await _pump(tester,
          viewer: _admin(),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: {
            ..._ritaRoutes(),
            'PUT /api/users/7/region-grants': (o) => o.data['grants'],
          });
      expect(tester.takeException(), isNull);
      expect(find.text('Save access'), findsOneWidget);

      // NORTH: READ → MANAGE.
      await tester.tap(_segment(1, 'level', 'Manage'));
      await tester.pumpAndSettle();

      // CENTRAL is offered (the editor holds it); WEST is not (the editor holds nothing there).
      await tester.tap(find.byKey(const ValueKey('grant-add')));
      await tester.pumpAndSettle();
      expect(find.text('WEST — West'), findsNothing);
      await tester.tap(find.text('CENTRAL — Central').last);
      await tester.pumpAndSettle();

      // Within CENTRAL the editor holds MANAGE, so APPROVE is not theirs to give.
      expect(_enabled(tester, 5, 'level', 'Approve'), isFalse);
      expect(_enabled(tester, 5, 'level', 'Manage'), isTrue);
      await tester.tap(_segment(5, 'scope', 'Own book'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Save access'));
      await tester.pumpAndSettle();

      final put = backend.sent('PUT /api/users/7/region-grants');
      expect(put, hasLength(1));
      expect(put.single.data, {
        'grants': [
          {'regionId': 1, 'level': 'MANAGE', 'scope': 'ALL'},
          {'regionId': 2, 'level': 'APPROVE', 'scope': 'ALL'},
          {'regionId': 3, 'level': 'READ', 'scope': 'ALL'},
          {'regionId': 5, 'level': 'READ', 'scope': 'BOOK'},
        ],
      });
      expect(find.text('Region access saved'), findsOneWidget);
    });

    testWidgets("rows beyond the editor's own grants are read only", (tester) async {
      await _pump(tester,
          viewer: _admin(),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: _ritaRoutes());

      // SOUTH is at APPROVE where the editor holds MANAGE; EAST covers all records where the
      // editor sees only a book. Both are shown, locked, and say why.
      expect(find.text('Only someone with this access can change it'), findsNWidgets(2));
      for (final id in [2, 3]) {
        expect(find.byKey(ValueKey('grant-remove-$id')), findsNothing);
        for (final level in ['Read', 'Manage', 'Approve']) {
          expect(_enabled(tester, id, 'level', level), isFalse, reason: 'region $id $level');
        }
        expect(_enabled(tester, id, 'scope', 'Own book'), isFalse);
      }
      // NORTH is the editor's to change, and to remove.
      expect(find.byKey(const ValueKey('grant-remove-1')), findsOneWidget);
      expect(_enabled(tester, 1, 'level', 'Approve'), isTrue);

      // Tapping a locked row changes nothing: there is still nothing to save.
      await tester.tap(_segment(2, 'level', 'Read'), warnIfMissed: false);
      await tester.pumpAndSettle();
      final save = tester.widget<FilledButton>(
          find.ancestor(of: find.text('Save access'), matching: find.byType(FilledButton)));
      expect(save.onPressed, isNull);
    });

    testWidgets('without SCOPE_OVERRIDE the role decides the book, so the choice is not offered',
        (tester) async {
      await _pump(tester,
          viewer: _admin(),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: _ritaRoutes(rolePrivileges: const [
            Privileges.customerView,
            Privileges.pocAssignableCollection,
          ]));

      expect(find.byKey(const ValueKey('grant-book-by-role-1')), findsOneWidget);
      expect(find.text('Book (set by role)'), findsNWidgets(3));
      expect(_enabled(tester, 1, 'scope', 'Own book'), isFalse);
      expect(_enabled(tester, 1, 'scope', 'All records'), isFalse);
      // The level is still the editor's to set.
      expect(_enabled(tester, 1, 'level', 'Manage'), isTrue);
    });

    testWidgets('a book-only grant for a role that holds no POC seats says it would show nothing',
        (tester) async {
      await _pump(tester,
          viewer: _admin(),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: _ritaRoutes(rolePrivileges: const [
            Privileges.customerView,
            Privileges.scopeOverride,
          ]));

      const warning = 'This role holds no POC seats, so its own book in NORTH is empty.';
      expect(find.text('Book (set by role)'), findsNothing);
      expect(find.text(warning), findsNothing);
      await tester.tap(_segment(1, 'scope', 'Own book'));
      await tester.pumpAndSettle();
      expect(find.text(warning), findsOneWidget);
    });

    testWidgets('your own access is read only', (tester) async {
      final backend = await _pump(tester,
          viewer: _admin(id: 7),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: _ritaRoutes());

      expect(find.text('You cannot change your own region access'), findsOneWidget);
      expect(find.text('Save access'), findsNothing);
      expect(find.byKey(const ValueKey('grant-add')), findsNothing);
      expect(find.byKey(const ValueKey('grant-remove-1')), findsNothing);
      expect(_enabled(tester, 1, 'level', 'Manage'), isFalse);
      // No lock notes: nothing here is anyone's to change from this page.
      expect(find.text('Only someone with this access can change it'), findsNothing);
      expect(backend.sent('PUT /api/users/7/region-grants'), isEmpty);
    });
  });

  group('what the editor may give', () {
    test('a staff editor the server reports with no regions holds nothing, so gives nothing', () {
      final reported = CurrentUser.fromJson({
        'id': 1,
        'username': 'uma',
        'role': 'USER_ADMIN',
        'privileges': [Privileges.userManage],
        'regions': [],
      });
      expect(reported.regionsKnown, isTrue);
      final bounds = GrantBounds(reported);
      expect(bounds.unbounded, isFalse);
      expect(bounds.offersLevel(1, 'READ'), isFalse);
      expect(bounds.offersAllRecords(1), isFalse);
      expect(bounds.covers(const RegionGrant(regionId: 1, level: 'READ', scope: 'BOOK')), isFalse);
      expect(initialGrantsFor(reported, const [Privileges.invoiceManage], _regionList), isEmpty);
      // Asked about a region, the user's own privileges no longer stand in for a grant.
      expect(reported.hasIn(Privileges.customerManage, 1), isFalse);

      // A server that predates regions sends none: nothing to bound by, as before.
      final legacy = CurrentUser.fromJson({
        'id': 1,
        'username': 'uma',
        'role': 'ADMIN',
        'privileges': [Privileges.userManage],
      });
      expect(legacy.regionsKnown, isFalse);
      expect(GrantBounds(legacy).unbounded, isTrue);
      expect(initialGrantsFor(legacy, const [Privileges.invoiceManage], _regionList),
          [const RegionGrant(regionId: 1, level: 'MANAGE', scope: 'ALL')]);
    });

    test('APPROVE is given, and pre-filled, only where the editor approves changes', () {
      const approverRole = [Privileges.customerView, Privileges.changeApprove];
      // NORTH: APPROVE and CHANGE_APPROVE. SOUTH: APPROVE without it.
      const southApprove =
          RegionAccess(id: 2, code: 'SOUTH', name: 'South', level: 'APPROVE', scope: 'ALL');
      final editor = _admin(regions: const [_north, southApprove]);
      final bounds = GrantBounds(editor);
      expect(bounds.offersLevel(1, 'APPROVE'), isTrue);
      expect(bounds.offersLevel(2, 'APPROVE'), isFalse);
      expect(bounds.offersLevel(2, 'MANAGE'), isTrue);
      expect(initialGrantsFor(editor, approverRole, _regionList),
          [const RegionGrant(regionId: 1, level: 'APPROVE', scope: 'ALL')]);

      // Without CHANGE_APPROVE in the default region the new user starts at MANAGE there.
      const northNoApprove = RegionAccess(
          id: 1, code: 'NORTH', name: 'North', defaultRegion: true, level: 'APPROVE', scope: 'ALL');
      expect(initialGrantsFor(_admin(regions: const [northNoApprove]), approverRole, _regionList),
          [const RegionGrant(regionId: 1, level: 'MANAGE', scope: 'ALL')]);
    });

    testWidgets('an editor with no regions sees every row locked and nothing to add',
        (tester) async {
      await _pump(tester,
          viewer: _admin(regions: const []),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: _ritaRoutes());

      expect(find.text('Only someone with this access can change it'), findsNWidgets(3));
      expect(find.byKey(const ValueKey('grant-add')), findsNothing);
      for (final id in [1, 2, 3]) {
        expect(find.byKey(ValueKey('grant-remove-$id')), findsNothing);
        expect(_enabled(tester, id, 'level', 'Read'), isFalse, reason: 'region $id');
      }
    });

    testWidgets('an APPROVE row may be kept where the editor cannot give APPROVE', (tester) async {
      const southApprove =
          RegionAccess(id: 2, code: 'SOUTH', name: 'South', level: 'APPROVE', scope: 'ALL');
      await _pump(tester,
          viewer: _admin(regions: const [_north, southApprove, _east]),
          page: const UserDetailScreen(id: 7, initialTab: 'regions'),
          routes: _ritaRoutes());

      // Rita holds APPROVE in SOUTH already: the editor may lower it, or leave it as it is.
      expect(find.byKey(const ValueKey('grant-remove-2')), findsOneWidget);
      expect(_enabled(tester, 2, 'level', 'Approve'), isTrue);
      expect(_enabled(tester, 2, 'level', 'Manage'), isTrue);
    });
  });

  group('a new user', () {
    testWidgets('starts with the default region at the level the role needs, and always sends it',
        (tester) async {
      final backend = await _pump(tester,
          viewer: _admin(),
          page: Builder(
            builder: (context) => Center(
              child: FilledButton(
                onPressed: () =>
                    showDialog<void>(context: context, builder: (_) => const UserFormDialog()),
                child: const Text('Open form'),
              ),
            ),
          ),
          routes: {
            'GET /api/regions': (_) => _regions,
            'GET /api/roles': (_) => _roles([
                  _role(2, 'VIEWER', [Privileges.customerView, Privileges.invoiceView]),
                  _role(3, 'CASHIER', [
                    Privileges.customerView,
                    Privileges.invoiceView,
                    Privileges.invoiceManage,
                    Privileges.scopeOverride,
                  ]),
                ]),
            'POST /api/users': (_) => {'id': 12, 'username': 'nina'},
          });

      await tester.tap(find.text('Open form'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'nina');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-1');

      // Before a role is picked the default region is offered at READ.
      expect(find.byKey(const ValueKey('grant-level-1')), findsOneWidget);
      await tester.tap(find.byType(DropdownButtonFormField<AppRole>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CASHIER').last);
      await tester.pumpAndSettle();

      final selected = tester.widget<SegmentedButton<String>>(
          find.byKey(const ValueKey('grant-level-1')));
      expect(selected.selected, {'MANAGE'});

      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      final post = backend.sent('POST /api/users');
      expect(post, hasLength(1));
      expect((post.single.data as Map)['regionGrants'], [
        {'regionId': 1, 'level': 'MANAGE', 'scope': 'ALL'},
      ]);
    });

    testWidgets('is not pre-filled by an editor who holds no region, and gets no access',
        (tester) async {
      final backend = await _pump(tester,
          viewer: _admin(regions: const []),
          page: Builder(
            builder: (context) => Center(
              child: FilledButton(
                onPressed: () =>
                    showDialog<void>(context: context, builder: (_) => const UserFormDialog()),
                child: const Text('Open form'),
              ),
            ),
          ),
          routes: {
            'GET /api/regions': (_) => _regions,
            'GET /api/roles': (_) => _roles([
                  _role(3, 'CASHIER', [Privileges.customerView, Privileges.invoiceManage]),
                ]),
            'POST /api/users': (_) => {'id': 12, 'username': 'nina'},
          });

      await tester.tap(find.text('Open form'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'nina');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-1');
      await tester.tap(find.byType(DropdownButtonFormField<AppRole>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CASHIER').last);
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('grant-level-1')), findsNothing);
      expect(find.byKey(const ValueKey('grant-add')), findsNothing);

      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect((backend.sent('POST /api/users').single.data as Map)['regionGrants'], isEmpty);
    });
  });
}
