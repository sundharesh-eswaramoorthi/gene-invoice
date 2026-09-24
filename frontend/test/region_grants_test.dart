import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/regions/region_grant_editor.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

const _north = RegionGrant(id: 3, code: 'NORTH', name: 'North Branch', rights: {regionRightManage});

CurrentUser _user({
  required Set<String> privileges,
  bool allRegions = false,
  List<RegionGrant> regions = const [],
}) =>
    CurrentUser(
      id: 9,
      username: 'ada',
      fullName: 'Ada Admin',
      role: 'ADMIN',
      privileges: privileges,
      customerId: null,
      allRegions: allRegions,
      regions: regions,
    );

Map<String, dynamic> _region(int id, String code, String name) =>
    {'id': id, 'code': code, 'name': name, 'active': true};

FakeBackend _grantBackend() => FakeBackend({
      'GET /api/regions': (o) => {
            'content': [_region(3, 'NORTH', 'North Branch'), _region(7, 'WEST', 'West Branch')],
          },
      'GET /api/regions/my': (o) => {'allRegions': false, 'regions': const []},
      // The dialog READS the set it is about to replace before it shows anything. Here that
      // read answers "they hold nothing", which is what these four tests compose from (B1).
      'GET /api/users/21/regions': (o) => {'allRegions': false, 'regions': const []},
      'PUT /api/users/21/regions': (o) => {'allRegions': true, 'regions': const []},
    });

Future<void> _openEditor(WidgetTester tester, FakeBackend backend) async {
  tester.view.physicalSize = const Size(1000, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_user(privileges: const {
        Privileges.userManage,
        Privileges.regionManage,
        // The VIEW pair too: reading somebody else's roster asks for USER_VIEW and REGION_VIEW,
        // and every role that carries the MANAGE pair carries it (B1).
        Privileges.userView,
        Privileges.regionView,
      })),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showRegionGrantEditor(context, userId: 21, username: 'ravi'),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

List<Map<String, Object?>> _grantsSent(FakeBackend backend) {
  final sent = backend.sent('PUT /api/users/21/regions');
  expect(sent, isNotEmpty, reason: 'the dialog never called the endpoint');
  final body = (sent.last.data as Map)['grants'] as List;
  return body.cast<Map<String, Object?>>();
}

void main() {
  group('the region grant editor composes (branch, level) PAIRS', () {
    testWidgets('a branch can be given two levels, and both are saved', (tester) async {
      final backend = _grantBackend();
      await _openEditor(tester, backend);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'NORTH — North Branch'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'Manage'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'Approve'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'View'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Replace branches'));
      await tester.pumpAndSettle();

      // The ladder is not a total order: MANAGE does not cover APPROVE. A manager who both
      // works and signs off in their own branch holds TWO rows there, and before this the
      // dialog could express exactly one level per branch — so that state, which B2's
      // four-eyes needs, could only be reached by curl (B1).
      expect(_grantsSent(backend), [
        {'regionId': 3, 'right': 'MANAGE'},
        {'regionId': 3, 'right': 'APPROVE'},
      ]);
    });

    testWidgets('a wildcard save keeps every level it was given', (tester) async {
      final backend = _grantBackend();
      await _openEditor(tester, backend);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'Every branch'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'Manage'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'Approve'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'View'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Replace branches'));
      await tester.pumpAndSettle();

      // The save REPLACES the whole set, and the migration gives every administrator TWO
      // wildcard rows (MANAGE and APPROVE). A one-level dialog silently dropped the second
      // one on any save, and could not put it back (B1).
      expect(_grantsSent(backend), [
        {'regionId': null, 'right': 'MANAGE'},
        {'regionId': null, 'right': 'APPROVE'},
      ]);
    });

    testWidgets('the result panel says which levels the wildcard was given', (tester) async {
      final backend = _grantBackend();
      await _openEditor(tester, backend);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'Every branch'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'Approve'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Replace branches'));
      await tester.pumpAndSettle();

      // allRegions on the answer is a bool computed at VIEW, so the answer alone renders a
      // wildcard VIEW and a wildcard VIEW+APPROVE identically. What was asked for is the only
      // thing that can be shown (B1).
      expect(find.text('Every branch'), findsOneWidget);
      expect(find.text('VIEW, APPROVE'), findsOneWidget);
    });

    testWidgets('turning the last level off gives the branch up altogether', (tester) async {
      final backend = _grantBackend();
      await _openEditor(tester, backend);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'WEST — West Branch'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilterChip, 'View'));
      await tester.pumpAndSettle();

      // A branch with no level is not a state the server can be sent, so it is the same
      // statement as unticking the branch (B1).
      expect(find.byType(FilterChip), findsNothing);
      await tester.tap(find.widgetWithText(FilledButton, 'Replace branches'));
      await tester.pumpAndSettle();
      expect(_grantsSent(backend), isEmpty);
    });
  });

  group('the customer form says why it cannot offer a branch', () {
    Future<void> pumpForm(WidgetTester tester, CurrentUser user, FakeBackend backend) async {
      tester.view.physicalSize = const Size(1000, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(user),
        ],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: const Scaffold(body: CustomerFormDialog()),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('a wildcard holder who may not read the branch map is told what is missing',
        (tester) async {
      final backend = FakeBackend({
        'GET /api/regions/my': (o) => {'allRegions': true, 'regions': const []},
      });
      await pumpForm(
        tester,
        _user(privileges: const {Privileges.customerManage}, allRegions: true),
        backend,
      );

      // They may open an account in ANY branch — the server would take it — but the map is
      // behind REGION_VIEW, so this client has no id to send. Hiding the field left the
      // server's "Choose a region" pointing at a control that was not on the form (B1).
      expect(find.textContaining('needs the Regions privilege'), findsOneWidget);
      // ...and it never asked for a map it may not read.
      expect(backend.sent('GET /api/regions'), isEmpty);
    });

    testWidgets('somebody who manages no branch is told that instead', (tester) async {
      final backend = FakeBackend({
        'GET /api/regions/my': (o) => {
              'allRegions': false,
              'regions': [
                {
                  'id': 3,
                  'code': 'NORTH',
                  'name': 'North Branch',
                  'rights': ['VIEW'],
                },
              ],
            },
      });
      await pumpForm(
        tester,
        _user(privileges: const {Privileges.customerManage}, regions: const [_north]),
        backend,
      );

      expect(find.textContaining('You manage no branch'), findsOneWidget);
      expect(find.textContaining('needs the Regions privilege'), findsNothing);
    });
  });
}
