import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/regions/region_grant_editor.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

/// The grant editor used to be WRITE-ONLY, and said so: "their current branches cannot be read
/// back from here, so compose the whole set". Because PUT /api/users/{id}/regions REPLACES the
/// whole set, an administrator who opened it to add one branch stripped every other grant —
/// including the wildcard, which is reach only another administrator can give back. Nothing on
/// screen could tell them, and no test crossed the client/server boundary, which is why it
/// shipped. These tests are that crossing (B1).
///
/// The subject throughout is the live instance's raj (id 14): HQ MANAGE and NORTH VIEW.
const _writer = {
  Privileges.userView,
  Privileges.userManage,
  Privileges.regionView,
  Privileges.regionManage,
};

/// Reading a roster asks for the VIEW pair; only CHANGING it asks for the MANAGE pair.
const _reader = {Privileges.userView, Privileges.regionView};

CurrentUser _viewer({Set<String> privileges = _writer}) => CurrentUser(
      id: 1,
      username: 'admin',
      fullName: 'Ada Admin',
      role: 'ADMIN',
      privileges: privileges,
      customerId: null,
      allRegions: true,
      regions: const [],
    );

Map<String, dynamic> _region(int id, String code, String name, {bool active = true}) =>
    {'id': id, 'code': code, 'name': name, 'active': active};

Map<String, dynamic> _grant(int id, String code, String name, List<String> rights) =>
    {'id': id, 'code': code, 'name': name, 'rights': rights};

/// RegionController.MyRegionsDto, field for field. allRegions is a BOOL — the wildcard's own
/// levels are not on the wire, which is the one thing this shape cannot say (B1).
Map<String, dynamic> _roster({bool allRegions = false, List<Map<String, dynamic>> regions = const []}) =>
    {'allRegions': allRegions, 'regions': regions};

/// The live instance's branch map: HQ, NORTH, SOUTH, WEST.
Object _map(_) => {
      'content': [
        _region(1, 'HQ', 'Head Office'),
        _region(2, 'NORTH', 'Northern Branch'),
        _region(3, 'SOUTH', 'Southern Branch'),
        _region(4, 'WEST', 'Western Branch'),
      ],
      'page': 0,
      'size': 50,
      'totalElements': 4,
      'totalPages': 1,
    };

FakeBackend _backend({
  Object? Function(dynamic)? roster,
  Object? Function(dynamic)? map,
  Object? Function(dynamic)? put,
}) =>
    FakeBackend({
      'GET /api/regions': map ?? _map,
      'GET /api/users/14/regions': roster ??
          (_) => _roster(regions: [
                _grant(1, 'HQ', 'Head Office', ['MANAGE']),
                _grant(2, 'NORTH', 'Northern Branch', ['VIEW']),
              ]),
      'PUT /api/users/14/regions': put ?? (_) => _roster(allRegions: false, regions: const []),
    });

Future<void> _open(
  WidgetTester tester,
  FakeBackend backend, {
  Set<String> privileges = _writer,
}) async {
  tester.view.physicalSize = const Size(1000, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_viewer(privileges: privileges)),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showRegionGrantEditor(context, userId: 14, username: 'raj'),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

bool _ticked(WidgetTester tester, String label) =>
    tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, label)).value ?? false;

/// Is one level ticked FOR ONE BRANCH — 'every' for the wildcard, otherwise the region id.
bool _levelOn(WidgetTester tester, Object scope, String label) {
  final chip = find.descendant(
    of: find.byKey(ValueKey('rights-$scope')),
    matching: find.widgetWithText(FilterChip, label),
  );
  expect(chip, findsOneWidget, reason: 'no $label chip on $scope');
  return tester.widget<FilterChip>(chip).selected;
}

Finder get _save => find.widgetWithText(FilledButton, 'Replace branches');

bool _saveEnabled(WidgetTester tester) =>
    tester.widget<FilledButton>(_save).onPressed != null;

List<Map<String, Object?>> _sent(FakeBackend backend) {
  final calls = backend.sent('PUT /api/users/14/regions');
  expect(calls, isNotEmpty, reason: 'the dialog never called the endpoint');
  return ((calls.last.data as Map)['grants'] as List).cast<Map<String, Object?>>();
}

void main() {
  group('the grant editor shows the set it is about to replace', () {
    testWidgets('it ticks the branches and the levels this person already holds', (tester) async {
      final backend = _backend();
      await _open(tester, backend);

      // It ASKED, which the write-only editor never did.
      expect(backend.sent('GET /api/users/14/regions'), hasLength(1));

      expect(_ticked(tester, 'HQ — Head Office'), isTrue);
      expect(_ticked(tester, 'NORTH — Northern Branch'), isTrue);
      expect(_ticked(tester, 'SOUTH — Southern Branch'), isFalse);
      expect(_ticked(tester, 'WEST — Western Branch'), isFalse);
      // The right level in the right branch, and not merely "something is ticked somewhere".
      expect(_levelOn(tester, 1, 'Manage'), isTrue);
      expect(_levelOn(tester, 1, 'View'), isFalse);
      expect(_levelOn(tester, 1, 'Approve'), isFalse);
      expect(_levelOn(tester, 2, 'View'), isTrue);
      expect(_levelOn(tester, 2, 'Manage'), isFalse);
      // Nothing is being taken away by a dialog nobody has touched yet.
      expect(find.textContaining('This save takes away'), findsNothing);

      await tester.tap(_save);
      await tester.pumpAndSettle();

      // A save that touched nothing must be a no-op on the server, because the endpoint REPLACES
      // the set. Composed from an empty dialog it used to be a total revocation (B1).
      expect(_sent(backend), [
        {'regionId': 1, 'right': 'MANAGE'},
        {'regionId': 2, 'right': 'VIEW'},
      ]);
    });

    testWidgets('several levels in one branch all survive a save nobody touched', (tester) async {
      final backend = _backend(
        roster: (_) => _roster(regions: [
          _grant(3, 'SOUTH', 'Southern Branch', ['MANAGE', 'APPROVE']),
        ]),
      );
      await _open(tester, backend);

      // The ladder is not a total order: MANAGE does not cover APPROVE, and a regional manager who
      // both works and signs off holds TWO rows in one branch — exactly what four-eyes needs (B1).
      expect(_levelOn(tester, 3, 'Manage'), isTrue);
      expect(_levelOn(tester, 3, 'Approve'), isTrue);
      expect(_levelOn(tester, 3, 'View'), isFalse);

      await tester.tap(_save);
      await tester.pumpAndSettle();

      expect(_sent(backend), [
        {'regionId': 3, 'right': 'MANAGE'},
        {'regionId': 3, 'right': 'APPROVE'},
      ]);
    });

    testWidgets('a wildcard holder shows as the wildcard, not as a list of branches',
        (tester) async {
      final backend = _backend(roster: (_) => _roster(allRegions: true));
      await _open(tester, backend);

      // Everywhere and nowhere BOTH answer regions: [] — only allRegions tells them apart, and an
      // editor that dropped the flag would strip a wildcard on the next save (B1).
      expect(_ticked(tester, 'Every branch'), isTrue);
      expect(_ticked(tester, 'HQ — Head Office'), isFalse);
      expect(_ticked(tester, 'NORTH — Northern Branch'), isFalse);

      // ...and it does not PRETEND to know the levels, because the wire cannot say: allRegions is
      // a bool computed at VIEW. Guessing VIEW here would replace an administrator's wildcard
      // MANAGE and APPROVE with a guess, so the save is refused until somebody states them (B1).
      expect(_levelOn(tester, 'every', 'Manage'), isFalse);
      expect(_levelOn(tester, 'every', 'Approve'), isFalse);
      expect(find.textContaining('cannot say at which levels'), findsOneWidget);
      expect(_saveEnabled(tester), isFalse);

      await tester.tap(find.descendant(
        of: find.byKey(const ValueKey('rights-every')),
        matching: find.widgetWithText(FilterChip, 'Manage'),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
        of: find.byKey(const ValueKey('rights-every')),
        matching: find.widgetWithText(FilterChip, 'Approve'),
      ));
      await tester.pumpAndSettle();
      expect(_saveEnabled(tester), isTrue);

      await tester.tap(_save);
      await tester.pumpAndSettle();

      expect(_sent(backend), [
        {'regionId': null, 'right': 'MANAGE'},
        {'regionId': null, 'right': 'APPROVE'},
      ]);
    });

    testWidgets('a roster that could not be read is never shown as an empty set', (tester) async {
      final backend = _backend(
        roster: (_) => const FakeFailure(500, {'message': 'Grant table unavailable'}),
      );
      await _open(tester, backend);

      // THE DANGEROUS CASE. A failed read is unknown, not "they hold nothing", and this endpoint
      // REPLACES the set — so an editor that fell back to an empty dialog would be one Save away
      // from revoking every grant the person has, silently, while looking like it worked (B1).
      expect(find.textContaining('could not be read'), findsOneWidget);
      expect(find.textContaining('Grant table unavailable'), findsOneWidget);
      expect(find.byType(CheckboxListTile), findsNothing);
      expect(_save, findsNothing);
      expect(backend.sent('PUT /api/users/14/regions'), isEmpty);

      // And it can be retried rather than only abandoned.
      expect(find.widgetWithText(FilledButton, 'Try again'), findsOneWidget);
    });

    testWidgets('giving up a branch or a level says in words what the save takes away',
        (tester) async {
      final backend = _backend(
        roster: (_) => _roster(regions: [
          _grant(1, 'HQ', 'Head Office', ['MANAGE']),
          _grant(2, 'NORTH', 'Northern Branch', ['VIEW']),
          _grant(3, 'SOUTH', 'Southern Branch', ['MANAGE', 'APPROVE']),
        ]),
      );
      await _open(tester, backend);
      expect(find.textContaining('This save takes away'), findsNothing);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'NORTH — Northern Branch'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
        of: find.byKey(const ValueKey('rights-3')),
        matching: find.widgetWithText(FilterChip, 'Approve'),
      ));
      await tester.pumpAndSettle();

      // The replace is STILL a replace. What changed is that the loss is visible BEFORE it
      // happens — a whole branch given up, and one level dropped from a branch that stays — which
      // is the whole of the defect: the strip was silent (B1).
      expect(
          find.textContaining(
              'This save takes away: NORTH — Northern Branch, SOUTH — Southern Branch (APPROVE)'),
          findsOneWidget);
    });

    testWidgets('giving up the wildcard is named as such and not left implied', (tester) async {
      final backend = _backend(roster: (_) => _roster(allRegions: true));
      await _open(tester, backend);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'Every branch'));
      await tester.pumpAndSettle();

      // Revoking every branch there is and every branch there will ever be is the single most
      // destructive thing this dialog can send, and it used to be the DEFAULT state of an empty
      // form: allRegions was invisible, so the wildcard went on every blind save (B1).
      expect(find.textContaining('This save takes away: Every branch'), findsOneWidget);
      // ...and with it gone there is nothing unstated left to block the save.
      expect(_saveEnabled(tester), isTrue);
    });

    testWidgets('a branch the map does not carry is still shown and still kept', (tester) async {
      final backend = _backend(
        roster: (_) => _roster(regions: [
          _grant(9, 'ZULU', 'Retired Depot', ['MANAGE']),
        ]),
        map: (_) => const FakeFailure(500, {'message': 'Branch map unavailable'}),
      );
      await _open(tester, backend);

      // A held branch that is not in the map — the map read failed here — would otherwise be
      // invisible and unticked, and the save would strip a grant nobody was ever shown (B1).
      expect(_ticked(tester, 'ZULU — Retired Depot'), isTrue);
      expect(find.textContaining('could not be listed'), findsOneWidget);

      await tester.tap(_save);
      await tester.pumpAndSettle();

      expect(_sent(backend), [
        {'regionId': 9, 'right': 'MANAGE'},
      ]);
    });

    testWidgets('opening it a second time reads the roster again rather than a cached one',
        (tester) async {
      final backend = _backend();
      await _open(tester, backend);
      await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      // Somebody else may have changed their branches in between, and a cached roster would tick
      // a set that no longer exists — which this dialog would then save over the real one (B1).
      expect(backend.sent('GET /api/users/14/regions'), hasLength(2));
    });

    testWidgets('a reader holding only the view pair sees the roster and is offered no save',
        (tester) async {
      final backend = _backend();
      await _open(tester, backend, privileges: _reader);

      // Reading who may work where is strictly less than deciding it: the roster renders, and the
      // control that would collect a 403 is not on the screen at all (B1).
      expect(_ticked(tester, 'HQ — Head Office'), isTrue);
      expect(_levelOn(tester, 1, 'Manage'), isTrue);
      expect(_save, findsNothing);
      expect(
          tester
              .widget<CheckboxListTile>(
                  find.widgetWithText(CheckboxListTile, 'HQ — Head Office'))
              .onChanged,
          isNull);
      expect(find.textContaining('read-only for you'), findsOneWidget);
    });

    testWidgets('somebody who may not read a roster at all is told so, and asks nothing',
        (tester) async {
      final backend = _backend();
      await _open(tester, backend, privileges: const {Privileges.regionView});

      // The client refuses before the request, and — the point — it refuses by FAILING rather
      // than by answering the empty roster, which would read as "they hold nothing" (B1).
      expect(find.textContaining('Users and Regions view privileges'), findsOneWidget);
      expect(backend.sent('GET /api/users/14/regions'), isEmpty);
      expect(_save, findsNothing);
    });
  });
}
