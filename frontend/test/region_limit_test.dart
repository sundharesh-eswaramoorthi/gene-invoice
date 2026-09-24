import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/regions/regions_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// The four branches of the live instance, and the four things a limit can be: HQ has one of its
// own, NORTH inherits the deployment default, SOUTH has nothing anywhere, WEST has been switched
// off on purpose (B2).
const _administrator = {
  Privileges.regionView,
  Privileges.regionManage,
  Privileges.approvalView,
  Privileges.approvalConfigure,
};

CurrentUser _user({
  Set<String> privileges = _administrator,
  bool allRegions = true,
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
    {'id': id, 'code': code, 'name': name, 'active': true, 'createdAt': '2026-01-04T00:00:00Z'};

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'sort': 'code,asc',
      'appliedFilters': const <String>[],
      'lockedFilters': const <String>[],
    };

/// ApprovalDtos.ThresholdDto, field for field.
Map<String, dynamic> _limit(
  int regionId,
  String regionName, {
  double amount = 0,
  bool enabled = false,
  bool fromDefault = false,
}) =>
    {
      'regionId': regionId,
      'regionName': regionName,
      'amount': amount,
      'enabled': enabled,
      'updatedByUserId': fromDefault ? null : 9,
      'updatedAt': fromDefault ? null : '2026-09-20T11:00:00Z',
      'fromDefault': fromDefault,
    };

/// The 202 an always-checked change comes back with, message and all, exactly as
/// ApprovalService.message writes it for one (B2).
Map<String, dynamic> _accepted({int id = 4200, String regionName = 'Head Office'}) => {
      'outcome': 'PENDING_APPROVAL',
      'pendingChangeId': id,
      'action': 'APPROVAL_THRESHOLD_SET',
      'targetType': 'REGION',
      'targetId': 1,
      'customerId': null,
      'regionId': 1,
      'regionName': regionName,
      'summary': 'Set the $regionName approval limit to ₹2,50,000.00',
      'exposure': 250000.00,
      'thresholdApplied': 0.00,
      'requestedAt': '2026-09-24T09:14:03Z',
      'path': '/api/approvals/thresholds/1',
      'link': '/approvals/$id',
      'message': 'This change always needs a second pair of eyes, whatever the amount. It has '
          'been sent for approval in $regionName and has not taken effect.',
    };

Map<String, dynamic> _pendingLimitChange({int id = 4200, int regionId = 1}) => {
      'id': id,
      'action': 'APPROVAL_THRESHOLD_SET',
      'targetType': 'REGION',
      'targetId': regionId,
      'customerId': null,
      'regionId': regionId,
      'regionName': 'Head Office',
      'summary': 'Set the Head Office approval limit to ₹2,50,000.00',
      'exposure': 250000.00,
      'thresholdApplied': 0.00,
      'alwaysChecked': true,
      'status': 'PENDING',
      'requestedByUserId': 9,
      'requestedByName': 'Ada Admin',
      'requestedAt': '2026-09-24T09:14:03Z',
      'decidedByUserId': null,
      'decidedByName': null,
      'decidedAt': null,
      'decisionNotes': null,
      'payloadJson': '{"amount":250000.00,"enabled":true}',
      'beforeJson': '{"amount":100000.00,"enabled":true,"fromDefault":false}',
      'targetVersion': null,
      'batchId': null,
      'mine': true,
      'canDecide': false,
      'cannotDecideReason': 'You raised this change, so somebody else has to decide it',
    };

FakeBackend _backend({
  List<Map<String, dynamic>>? regions,
  Map<int, Map<String, dynamic>>? limits,
  Object? Function(RequestOptions)? put,
  List<Map<String, dynamic>> Function()? pending,
}) {
  final rows = regions ??
      [
        _region(1, 'HQ', 'Head Office'),
        _region(2, 'NORTH', 'North Branch'),
        _region(3, 'SOUTH', 'South Branch'),
        _region(4, 'WEST', 'West Branch'),
      ];
  final byId = limits ??
      {
        1: _limit(1, 'Head Office', amount: 100000, enabled: true),
        2: _limit(2, 'North Branch', amount: 50000, enabled: true, fromDefault: true),
        3: _limit(3, 'South Branch', fromDefault: true),
        4: _limit(4, 'West Branch', amount: 75000),
      };
  return FakeBackend({
    'GET /api/table-schemas/regions': (o) => {'entity': 'regions', 'columns': const <dynamic>[]},
    'GET /api/regions': (o) => _page(rows),
    'GET /api/regions/my': (o) => {'allRegions': true, 'regions': const <dynamic>[]},
    'GET /api/approvals': (o) => _page(pending == null ? const [] : pending()),
    for (final entry in byId.entries)
      'GET /api/approvals/thresholds/${entry.key}': (o) => entry.value,
    if (put != null) 'PUT /api/approvals/thresholds/1': put,
  });
}

Future<GoRouter> _pumpRegions(WidgetTester tester, FakeBackend backend, CurrentUser user) async {
  tester.view.physicalSize = const Size(1500, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(initialLocation: '/regions', routes: [
    GoRoute(
      path: '/regions',
      builder: (_, s) =>
          RegionsScreen(query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'code,asc')),
    ),
    GoRoute(
      path: '/approvals/:id',
      builder: (_, s) => Scaffold(body: Text('the queue, change ${s.pathParameters['id']}')),
    ),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

Future<void> _openLimitDialog(WidgetTester tester) async {
  await tester.tap(find.byTooltip('Set the approval limit').first);
  await tester.pumpAndSettle();
}

void main() {
  group('the branch list says what each branch holds', () {
    testWidgets('a branch with a limit of its own shows it as money', (tester) async {
      final backend = _backend();
      await _pumpRegions(tester, backend, _user());

      expect(find.text('Approval limit'), findsOneWidget);
      expect(find.text('₹1,00,000.00'), findsOneWidget);
      // Its own row, so nothing is said about where it came from.
      expect(find.text('Deployment default'), findsOneWidget, reason: 'only NORTH inherits one');

      // One read per branch on the page and no more; the server publishes no bulk shape for
      // these, so the cost is visible here rather than hidden behind a client-side join (B2).
      expect(backend.sent('GET /api/approvals/thresholds/1'), hasLength(1));
      expect(backend.sent('GET /api/approvals/thresholds/4'), hasLength(1));

      // The queue is an ordinary table endpoint and the page-size contract is not negotiable:
      // TableQuery.ALLOWED_SIZES is [10, 20, 50] and anything else is a 400 (B1, B2).
      final asked = backend.sent('GET /api/approvals').single;
      expect(const [10, 20, 50], contains(asked.queryParameters['size']));
      expect(asked.queryParameters['filter'],
          ['targetType:eq:REGION', 'status:eq:PENDING']);
    });

    testWidgets('a limit that came from the deployment default is labelled as the default',
        (tester) async {
      final backend = _backend();
      await _pumpRegions(tester, backend, _user());

      // NORTH has no row of its own. Showing ₹50,000.00 with nothing beside it would say an
      // operator chose that figure for this branch, and none did (B2).
      expect(find.text('₹50,000.00'), findsOneWidget);
      expect(find.text('Deployment default'), findsOneWidget);

      final tooltip = tester.widget<Tooltip>(find.ancestor(
        of: find.text('₹50,000.00'),
        matching: find.byType(Tooltip),
      ));
      expect(tooltip.message, contains('has no limit of its own'));
      expect(tooltip.message, contains('nobody chose it here'));
    });

    testWidgets('a branch with no limit and no default says maker-checker holds nothing there',
        (tester) async {
      final backend = _backend();
      await _pumpRegions(tester, backend, _user());

      // SOUTH and WEST both hold nothing, and a blank cell in a money column reads as zero —
      // which would mean EVERYTHING is held, the exact opposite of what is true (B2).
      expect(find.text('Nothing is held here'), findsNWidgets(2));
      expect(find.text('No limit set'), findsOneWidget);
      expect(find.text('Switched off'), findsOneWidget);
      expect(find.text('₹0.00'), findsNothing);

      final south = tester.widget<Tooltip>(find.ancestor(
        of: find.text('No limit set'),
        matching: find.byType(Tooltip),
      ));
      expect(south.message, contains('no amount is too large to go straight through'));
    });

    testWidgets('a limit that could not be read is unknown and never reads as "nothing held"',
        (tester) async {
      final backend = _backend();
      backend.routes['GET /api/approvals/thresholds/1'] =
          (o) => const FakeFailure(500, {'message': 'The database is unreachable'});
      await _pumpRegions(tester, backend, _user());

      expect(find.text('Unknown'), findsOneWidget);
      expect(find.text('Nothing is held here'), findsNWidgets(2), reason: 'SOUTH and WEST only');
    });
  });

  group('who is offered the limit, and who is offered the editor', () {
    testWidgets('without APPROVAL_VIEW there is no limit column and nothing is asked for',
        (tester) async {
      final backend = _backend();
      await _pumpRegions(
          tester,
          backend,
          _user(privileges: const {Privileges.regionView, Privileges.regionManage}));

      expect(find.text('Approval limit'), findsNothing);
      expect(find.text('₹1,00,000.00'), findsNothing);
      expect(backend.sent('GET /api/approvals/thresholds/1'), isEmpty,
          reason: 'GET /api/approvals/thresholds is behind APPROVAL_VIEW (AUTH-08)');
      expect(find.byTooltip('Set the approval limit'), findsNothing);
    });

    testWidgets('a reader with APPROVAL_VIEW sees every limit and is offered no editor',
        (tester) async {
      final backend = _backend();
      await _pumpRegions(
          tester,
          backend,
          _user(privileges: const {
            Privileges.regionView,
            Privileges.regionManage,
            Privileges.approvalView,
          }));

      expect(find.text('₹1,00,000.00'), findsOneWidget);
      // REGION_MANAGE is still there, so the row actions column exists and the branch editor is
      // in it. What is absent is the one control APPROVAL_CONFIGURE pays for (B2).
      expect(find.byTooltip('Edit branch'), findsNWidgets(4));
      expect(find.byTooltip('Set the approval limit'), findsNothing);
    });

    testWidgets('the editor is offered only in a branch this person may manage', (tester) async {
      final backend = _backend(
        regions: [_region(1, 'HQ', 'Head Office'), _region(2, 'NORTH', 'North Branch')],
        limits: {
          1: _limit(1, 'Head Office', amount: 100000, enabled: true),
          2: _limit(2, 'North Branch', amount: 50000, enabled: true, fromDefault: true),
        },
      );
      await _pumpRegions(
        tester,
        backend,
        _user(
          privileges: const {
            Privileges.regionView,
            Privileges.approvalView,
            Privileges.approvalConfigure,
          },
          allRegions: false,
          regions: const [
            RegionGrant(id: 1, code: 'HQ', name: 'Head Office', rights: {regionRightManage}),
            RegionGrant(id: 2, code: 'NORTH', name: 'North Branch', rights: {regionRightView}),
          ],
        ),
      );

      // Naming a branch on a write you cannot manage is a 403 and not a 404, so the control is
      // offered exactly where the server would accept it (B1, B2, D-46).
      expect(find.byTooltip('Set the approval limit'), findsOneWidget);
      expect(find.text('₹1,00,000.00'), findsOneWidget, reason: 'both limits are still READABLE');
      expect(find.text('₹50,000.00'), findsOneWidget);
    });
  });

  group('proposing a limit', () {
    testWidgets('the editor opens on the limit in force rather than on an empty box',
        (tester) async {
      final backend = _backend(regions: [_region(1, 'HQ', 'Head Office')], limits: {
        1: _limit(1, 'Head Office', amount: 100000, enabled: true),
      });
      await _pumpRegions(tester, backend, _user());
      await _openLimitDialog(tester);

      // The region grant editor's defect, not repeated: an editor that cannot show what it is
      // about to replace lets somebody switch maker-checker off while believing they raised a
      // limit (B2, B1).
      expect(find.text('Approval limit — HQ'), findsOneWidget);
      expect(
          find.textContaining('A change worth more than ₹1,00,000.00 in Head Office waits'),
          findsOneWidget);
      final field = tester.widget<TextField>(find.widgetWithText(TextField, '100000.00'));
      expect(field.controller!.text, '100000.00');
      // Said before the button is pressed, not only in the amber afterwards (B2).
      expect(find.textContaining('Changing a limit is itself always checked'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Send for approval'), findsOneWidget);
    });

    testWidgets('a 202 shows the held amber, and the limit on the row does not move',
        (tester) async {
      late FakeBackend backend;
      backend = _backend(
        regions: [_region(1, 'HQ', 'Head Office')],
        limits: {1: _limit(1, 'Head Office', amount: 100000, enabled: true)},
        put: (o) => FakeAccepted(_accepted()),
        // The queue answers what it would really answer once the change is parked (B2).
        pending: () => backend.sent('PUT /api/approvals/thresholds/1').isEmpty
            ? const []
            : [_pendingLimitChange()],
      );
      final router = await _pumpRegions(tester, backend, _user());
      await _openLimitDialog(tester);

      await tester.enterText(find.widgetWithText(TextField, '100000.00'), '250000');
      await tester.tap(find.widgetWithText(FilledButton, 'Send for approval'));
      await tester.pumpAndSettle();

      expect((backend.sent('PUT /api/approvals/thresholds/1').single.data as Map),
          {'amount': 250000.0, 'enabled': true});

      // Dio's default validateStatus accepts 200-299, so without the 202 interceptor this is a
      // SUCCESS and the operator is told the limit is now ₹2,50,000.00 when it is not (B2).
      expect(find.text('Approval limit — HQ'), findsNothing, reason: 'the dialog must close');
      expect(
          find.textContaining(
              'always needs a second pair of eyes, whatever the amount. It has been sent for '
              'approval in Head Office and has not taken effect'),
          findsOneWidget);
      expect(find.textContaining('Saved'), findsNothing);

      // The row still shows the limit that is in force, because nothing has changed yet — with
      // the amber dot beside it saying somebody has asked for it to become something else (B2).
      expect(find.text('₹1,00,000.00'), findsOneWidget);
      expect(find.text('₹2,50,000.00'), findsNothing);
      expect(find.byTooltip('A change on this record is waiting for approval'), findsOneWidget);

      // And the link is the server's own, already in this app's route space (B2).
      await tester.tap(find.text('View request'));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.toString(), '/approvals/4200');
    });

    testWidgets('switching approval checking off in a branch is held for approval too',
        (tester) async {
      final backend = _backend(
        regions: [_region(1, 'HQ', 'Head Office')],
        limits: {1: _limit(1, 'Head Office', amount: 100000, enabled: true)},
        put: (o) => FakeAccepted(_accepted()),
      );
      await _pumpRegions(tester, backend, _user());
      await _openLimitDialog(tester);

      await tester.tap(find.widgetWithText(CheckboxListTile, 'Hold changes above this amount'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Send for approval'));
      await tester.pumpAndSettle();

      // The most dangerous change on the form — it holds nothing afterwards — and it goes
      // through the same second pair of eyes as raising the number (B2).
      expect((backend.sent('PUT /api/approvals/thresholds/1').single.data as Map),
          {'amount': 100000.0, 'enabled': false});
      expect(find.text('Approval limit — HQ'), findsNothing, reason: 'the dialog must close');
      expect(find.byType(SnackBar), findsOneWidget);
      expect(find.textContaining('has not taken effect'), findsOneWidget);
      expect(find.text('₹1,00,000.00'), findsOneWidget);
    });

    testWidgets('an ordinary refusal stays in the dialog and is not dressed up as held',
        (tester) async {
      final backend = _backend(
        regions: [_region(1, 'HQ', 'Head Office')],
        limits: {1: _limit(1, 'Head Office', amount: 100000, enabled: true)},
        put: (o) => const FakeFailure(400, {
          'message': 'A change on this record is already waiting for approval (change #12)',
        }),
      );
      await _pumpRegions(tester, backend, _user());
      await _openLimitDialog(tester);

      await tester.tap(find.widgetWithText(FilledButton, 'Send for approval'));
      await tester.pumpAndSettle();

      expect(find.text('Approval limit — HQ'), findsOneWidget, reason: 'the dialog stays open');
      expect(find.text('A change on this record is already waiting for approval (change #12)'),
          findsOneWidget);
      expect(find.textContaining('sent for approval and has not taken effect'), findsNothing);
    });

    testWidgets('the editor refuses to open when the limit it would replace cannot be read',
        (tester) async {
      final backend = _backend(regions: [_region(1, 'HQ', 'Head Office')], limits: {
        1: _limit(1, 'Head Office', amount: 100000, enabled: true),
      });
      backend.routes['GET /api/approvals/thresholds/1'] =
          (o) => const FakeFailure(503, {'message': 'The database is unreachable'});
      await _pumpRegions(tester, backend, _user());

      await _openLimitDialog(tester);

      expect(find.text('Approval limit — HQ'), findsNothing);
      expect(find.textContaining('could not be read, so it cannot be edited'), findsOneWidget);
    });
  });
}
