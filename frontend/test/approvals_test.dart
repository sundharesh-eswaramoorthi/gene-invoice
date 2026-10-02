import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/api/pending_reply.dart';
import 'package:gene_invoice/core/format.dart';
import 'package:gene_invoice/core/router.dart';
import 'package:gene_invoice/core/scope/view_scope.dart';
import 'package:gene_invoice/core/storage/secure_storage.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/approvals/approval_detail_screen.dart';
import 'package:gene_invoice/features/approvals/approval_models.dart';
import 'package:gene_invoice/features/approvals/approval_providers.dart';
import 'package:gene_invoice/features/approvals/approval_thresholds_screen.dart';
import 'package:gene_invoice/features/approvals/approvals_screen.dart';
import 'package:gene_invoice/features/approvals/pending_banner.dart';
import 'package:gene_invoice/features/audit/audit_history_panel.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/disputes/dispute_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_form_screen.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/features/payments/payment_detail_screen.dart';
import 'package:gene_invoice/features/payments/record_payment_dialog.dart';
import 'package:gene_invoice/features/promises/promise_detail_screen.dart';
import 'package:gene_invoice/features/promises/promise_form_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/customer.dart';
import 'package:gene_invoice/shared/models/payment_term.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/promise.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/fake_backend.dart';

const _viewing = {
  Privileges.customerView,
  Privileges.invoiceView,
  Privileges.paymentView,
  Privileges.promiseView,
  Privileges.disputeView,
};

const _approving = {..._viewing, Privileges.changeApprove, Privileges.approvalThresholdManage};

const _south = RegionAccess(
    id: 2, code: 'SOUTH', name: 'South', level: 'APPROVE', scope: 'ALL', privileges: _approving);
const _north = RegionAccess(
    id: 1,
    code: 'NORTH',
    name: 'North',
    defaultRegion: true,
    level: 'READ',
    scope: 'ALL',
    privileges: _viewing);

CurrentUser _user(
        {Set<String> privileges = _approving, List<RegionAccess> regions = const [_south]}) =>
    CurrentUser(
      id: 3,
      username: 'cara',
      fullName: 'Cara Checker',
      role: 'MANAGER',
      privileges: privileges,
      customerId: null,
      regions: regions,
    );

/// Answers as the server does: a request that named a day says which day it answered.
Object? Function(RequestOptions) _answer(Object? data) => (o) {
      final asOf = o.queryParameters['asOf'];
      return FakeReply(200, data, headers: {
        if (asOf != null) 'X-View-As-Of': ['$asOf'],
      });
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows, {String? asOf}) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'regionIds': const [2],
      'asOf': asOf,
    };

Map<String, dynamic> _brief({
  int id = 41,
  String status = 'PENDING',
  String? statusAt,
  Map<String, dynamic>? requestedBy = const {'id': 9, 'name': 'Sam Sales'},
  String entityType = 'INVOICE',
  int? entityId = 1203,
}) =>
    {
      'id': id,
      'status': status,
      'statusAt': statusAt,
      'operation': 'INVOICE_CANCEL',
      'operationLabel': 'Cancel invoice',
      'entityType': entityType,
      'entityId': entityId,
      'entityLabel': 'INV-20260926-0003',
      'customerId': 77,
      'customerName': 'Acme Ltd',
      'regionId': 2,
      'regionName': 'South',
      'amount': 120000.0,
      'threshold': 100000.0,
      'summary': 'Cancel invoice INV-20260926-0003 (₹1,20,000.00)',
      'requestedAt': '2026-09-26T09:14:03.120Z',
      'requestedBy': requestedBy,
      'origin': 'USER',
      'decidedAt': null,
    };

Map<String, dynamic> _detail({
  String status = 'PENDING',
  bool canApprove = false,
  bool canReject = false,
  bool canWithdraw = false,
  String? blockedReason,
  String? failureReason,
  Map<String, dynamic>? result,
}) =>
    {
      ..._brief(status: status),
      'operation': 'INVOICE_TERMS',
      'operationLabel': 'Extend invoice due date',
      'affectedType': 'INVOICE',
      'affectedId': 1203,
      'measureLabel': 'Balance whose due date moves later',
      'summary': 'Extend due date of INV-20260926-0003 from 2026-10-26 to 2026-12-31',
      'changes': [
        {
          'field': 'dueDate',
          'label': 'Due date',
          'current': '2026-10-26',
          'proposed': '2026-12-31',
          'changedSinceRequest': false,
        },
      ],
      'current': {'dueDate': '2026-10-26', 'paymentTerm': 'NET_30'},
      'proposed': {'dueDate': '2026-12-31', 'paymentTerm': 'CUSTOM'},
      'ruleId': null,
      'decidedBy': null,
      'decisionNote': null,
      'failureReason': failureReason,
      'supersededById': null,
      'result': result,
      'approverCount': 3,
      'canApprove': canApprove,
      'canReject': canReject,
      'canWithdraw': canWithdraw,
      'blockedReason': blockedReason,
    };

Future<(FakeBackend, ProviderContainer)> _pump(
  WidgetTester tester, {
  required Widget page,
  required Map<String, Object? Function(RequestOptions)> routes,
  CurrentUser? user,
  DateTime? asOf,
  List<Override> overrides = const [],
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  late ProviderContainer container;
  final backend = FakeBackend(Map.of(routes));
  final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
  container = ProviderContainer(overrides: [
    dioProvider.overrideWithValue(dio),
    currentUserProvider.overrideWithValue(user ?? _user()),
    ...overrides,
  ]);
  addTearDown(container.dispose);
  if (asOf != null) container.read(viewScopeProvider.notifier).setAsOf(asOf);

  final router = GoRouter(initialLocation: '/page', routes: [
    ShellRoute(
      builder: (context, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/page', builder: (_, __) => page),
        // As the app's router builds it: the view travels in the URL beside the page.
        GoRoute(
            path: '/approvals',
            builder: (_, s) => ApprovalsScreen(
                  query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'requestedAt,desc'),
                  view: approvalsViewNamed(s.uri.queryParameters['view']),
                )),
        GoRoute(
            path: '/approval-thresholds',
            builder: (_, s) => Text('thresholds ${s.uri.queryParameters['regionId']}')),
        GoRoute(
            path: '/approvals/:id', builder: (_, s) => Text('approval ${s.pathParameters['id']}')),
        GoRoute(
            path: '/invoices/:id', builder: (_, s) => Text('invoice ${s.pathParameters['id']}')),
      ],
    ),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return (backend, container);
}

/// The Approvals badge makes its own request; most cases here are about something else.
final _noBadge = approvalsAwaitingMeCountProvider.overrideWith((ref) => Stream.value(0));

Map<String, dynamic> _thresholds({Map<String, dynamic>? pendingChange, List<String>? warnings}) => {
      'regionId': 2,
      'regionName': 'South',
      'canEdit': true,
      'activeApprovers': 3,
      'pendingChange': pendingChange,
      'thresholds': [
        {
          'operation': 'INVOICE_CREATE',
          'label': 'Create invoice',
          'measure': 'Invoice total',
          'amount': 100000.0,
          'windowHours': 24,
          'updatedAt': '2026-09-20T08:00:00Z',
          'updatedBy': {'id': 1, 'name': 'System Administrator'},
        },
        {
          'operation': 'INVOICE_CANCEL',
          'label': 'Cancel invoice',
          'measure': 'Invoice total',
          'amount': null,
          'windowHours': null,
          'updatedAt': null,
          'updatedBy': null,
        },
        {
          'operation': 'PAYMENT_RECORD',
          'label': 'Record payment',
          'measure': 'Payment amount',
          'amount': 50000.0,
          'windowHours': 0,
          'updatedAt': null,
          'updatedBy': null,
        },
      ],
      if (warnings != null) 'warnings': warnings,
    };

class _MemoryTokens implements TokenStorage {
  String? token;
  @override
  Future<void> save(String t) async => token = t;
  @override
  Future<String?> read() async => token;
  @override
  Future<void> clear() async => token = null;
}

GoRoute _routeAt(List<RouteBase> routes, String path) {
  for (final r in routes) {
    if (r is GoRoute && r.path == path) return r;
    if (r is ShellRoute) {
      final inner = r.routes.where((x) => x is GoRoute && x.path == path);
      if (inner.isNotEmpty) return inner.first as GoRoute;
    }
  }
  throw StateError('no route $path');
}

void main() {
  group('the approval page', () {
    Future<FakeBackend> open(WidgetTester tester, Map<String, dynamic> detail,
        {Map<String, Object? Function(RequestOptions)> more = const {}}) async {
      final (backend, _) = await _pump(tester,
          page: const ApprovalDetailScreen(id: 41),
          routes: {'GET /api/approvals/41': (_) => detail, ...more},
          overrides: [_noBadge]);
      return backend;
    }

    testWidgets('shows what would change, who asked, and the amount against the limit',
        (tester) async {
      await open(tester, _detail());
      expect(find.text('Approval #41'), findsOneWidget);
      expect(find.text('Awaiting approval'), findsWidgets);
      expect(find.text('Extend invoice due date'), findsOneWidget);
      expect(find.text('₹1,20,000.00 against a limit of ₹1,00,000.00'), findsOneWidget);
      expect(find.textContaining('by Sam Sales'), findsOneWidget);
      expect(find.text('Due date'), findsOneWidget);
      expect(find.text('2026-10-26'), findsOneWidget);
      expect(find.text('2026-12-31'), findsOneWidget);
    });

    testWidgets('the second tab sets the record now beside the record as proposed', (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41, initialTab: 'values'),
          routes: {'GET /api/approvals/41': (_) => _detail()},
          overrides: [_noBadge]);
      expect(find.text('Current'), findsOneWidget);
      expect(find.text('Proposed'), findsOneWidget);
      expect(find.textContaining('"paymentTerm": "CUSTOM"'), findsOneWidget);
    });

    Map<String, dynamic> moneyDetail() => {
          ..._detail(),
          'changes': [
            {'field': 'total', 'label': 'Total', 'current': 1234.5, 'proposed': 500.0},
            {'field': 'quantity', 'label': 'Quantity', 'current': 3, 'proposed': 4},
            {
              'field': 'lines',
              'label': 'Lines',
              'current': [
                {'description': 'Kit', 'quantity': 2, 'unitPrice': 617.25},
              ],
              'proposed': null,
            },
          ],
          'current': {
            'total': 1234.5,
            'quantity': 3,
            'limit': {'amount': 100000.0},
          },
          'proposed': {
            'total': 500.0,
            'quantity': 4,
            'limit': {'amount': 100000.0},
          },
        };

    testWidgets('the change table shows money as money, and other numbers as they are',
        (tester) async {
      await open(tester, moneyDetail());
      expect(find.text(formatMoney(1234.5)), findsOneWidget);
      expect(find.text(formatMoney(500)), findsOneWidget);
      expect(find.text('3'), findsOneWidget);
      expect(find.text('4'), findsOneWidget);
      expect(find.textContaining('"unitPrice": "${formatMoney(617.25)}"'), findsOneWidget);
      expect(find.textContaining('"quantity": 2'), findsOneWidget);
      expect(find.textContaining('1234.5'), findsNothing);
      expect(find.text('500.0'), findsNothing);
      expect(find.textContaining(': 617.25'), findsNothing);
    });

    testWidgets('the record now and as proposed show money as money, however deep',
        (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41, initialTab: 'values'),
          routes: {'GET /api/approvals/41': (_) => moneyDetail()},
          overrides: [_noBadge]);
      expect(find.textContaining('"total": "${formatMoney(1234.5)}"'), findsOneWidget);
      expect(find.textContaining('"total": "${formatMoney(500)}"'), findsOneWidget);
      expect(find.textContaining('"amount": "${formatMoney(100000)}"'), findsNWidgets(2));
      expect(find.textContaining('"quantity": 3'), findsOneWidget);
      expect(find.textContaining('1234.5'), findsNothing);
      expect(find.textContaining(': 500.0'), findsNothing);
      expect(find.textContaining(': 100000.0'), findsNothing);
    });

    testWidgets('offers only what the server allows', (tester) async {
      await open(tester, _detail(canApprove: true, canReject: true));
      expect(find.byKey(const ValueKey('approval-approve')), findsOneWidget);
      expect(find.byKey(const ValueKey('approval-reject')), findsOneWidget);
      expect(find.byKey(const ValueKey('approval-withdraw')), findsNothing);
    });

    testWidgets('with no flags there are no buttons, and the reason says why', (tester) async {
      await open(tester, _detail(blockedReason: 'You requested this change', canWithdraw: true));
      expect(find.byKey(const ValueKey('approval-approve')), findsNothing);
      expect(find.byKey(const ValueKey('approval-reject')), findsNothing);
      expect(find.byKey(const ValueKey('approval-withdraw')), findsOneWidget);
      expect(find.text('You requested this change'), findsOneWidget);
    });

    testWidgets('Approve posts the optional note', (tester) async {
      final backend = await open(tester, _detail(canApprove: true), more: {
        'POST /api/approvals/41/approve': (_) => _detail(status: 'APPROVED', result: {
              'entityType': 'INVOICE',
              'entityId': 1203,
              'link': '/invoices/1203',
            }),
      });
      await tester.tap(find.byKey(const ValueKey('approval-approve')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('approval-note')), '  Customer agreed  ');
      await tester.tap(find.byKey(const ValueKey('approval-decide')));
      await tester.pumpAndSettle();

      final posted = backend.sent('POST /api/approvals/41/approve');
      expect(posted, hasLength(1));
      expect(posted.single.data, {'note': 'Customer agreed'});
      expect(find.text('Change approved'), findsOneWidget);
      // The page is read again after the decision.
      expect(backend.sent('GET /api/approvals/41'), hasLength(2));
    });

    testWidgets('Approve without a note sends none', (tester) async {
      final backend = await open(tester, _detail(canApprove: true),
          more: {'POST /api/approvals/41/approve': (_) => _detail(status: 'APPROVED')});
      await tester.tap(find.byKey(const ValueKey('approval-approve')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('approval-decide')));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/approvals/41/approve').single.data, isEmpty);
    });

    testWidgets('Reject refuses an empty note, then posts the one given', (tester) async {
      final backend = await open(tester, _detail(canReject: true),
          more: {'POST /api/approvals/41/reject': (_) => _detail(status: 'REJECTED')});
      await tester.tap(find.byKey(const ValueKey('approval-reject')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('approval-note')), '   ');
      await tester.tap(find.byKey(const ValueKey('approval-decide')));
      await tester.pumpAndSettle();

      expect(find.text('Say why, so the person who asked knows'), findsOneWidget);
      expect(backend.sent('POST /api/approvals/41/reject'), isEmpty);

      await tester.enterText(find.byKey(const ValueKey('approval-note')), 'Terms stay at 30 days');
      await tester.tap(find.byKey(const ValueKey('approval-decide')));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/approvals/41/reject').single.data,
          {'note': 'Terms stay at 30 days'});
      expect(find.text('Change rejected'), findsOneWidget);
    });

    testWidgets('Withdraw asks first', (tester) async {
      final backend = await open(tester, _detail(canWithdraw: true),
          more: {'POST /api/approvals/41/withdraw': (_) => _detail(status: 'WITHDRAWN')});
      await tester.tap(find.byKey(const ValueKey('approval-withdraw')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Keep it'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/approvals/41/withdraw'), isEmpty);

      await tester.tap(find.byKey(const ValueKey('approval-withdraw')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('approval-withdraw-confirm')));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/approvals/41/withdraw'), hasLength(1));
      expect(find.text('Request withdrawn'), findsOneWidget);
    });

    testWidgets("a conflict shows the server's message", (tester) async {
      await open(tester, _detail(canApprove: true), more: {
        'POST /api/approvals/41/approve': (_) =>
            const FakeFailure(409, {'message': 'This change has already been decided'}),
      });
      await tester.tap(find.byKey(const ValueKey('approval-approve')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('approval-decide')));
      await tester.pumpAndSettle();
      expect(find.text('This change has already been decided'), findsOneWidget);
    });

    testWidgets('an approved change links to its record', (tester) async {
      await open(
          tester,
          _detail(
              status: 'APPROVED',
              result: {'entityType': 'INVOICE', 'entityId': 1203, 'link': '/invoices/1203'}));
      await tester.tap(find.text('Open INV-20260926-0003'));
      await tester.pumpAndSettle();
      expect(find.text('invoice 1203'), findsOneWidget);
    });

    testWidgets('a failed change says why', (tester) async {
      await open(tester,
          _detail(status: 'FAILED', failureReason: 'Cannot cancel an invoice with payments'));
      expect(find.text('It could not be applied: Cannot cancel an invoice with payments'),
          findsOneWidget);
      expect(find.text('Failed'), findsOneWidget);
    });

    testWidgets("a customer's Back returns to the record, not to the staff-only list",
        (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41),
          user: const CurrentUser(
            id: 12,
            username: 'acme',
            fullName: 'Acme Buyer',
            role: 'CUSTOMER',
            privileges: {Privileges.invoiceView},
            customerId: 77,
          ),
          routes: {'GET /api/approvals/41': (_) => _detail()},
          overrides: [_noBadge]);
      expect(find.text('Approval #41'), findsOneWidget);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(find.text('invoice 1203'), findsOneWidget);
    });

    testWidgets('on a past date nothing can be decided, whatever the flags', (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41),
          asOf: DateTime(2026, 6, 30),
          routes: {'GET /api/approvals/41': _answer(_detail(canApprove: true, canReject: true))},
          overrides: [_noBadge]);
      expect(find.text('Approval #41'), findsOneWidget);
      expect(find.byKey(const ValueKey('approval-approve')), findsNothing);
      expect(find.byKey(const ValueKey('approval-reject')), findsNothing);
    });

    // Approved today, still waiting on the day viewed; the server of the day sends the decision
    // unmasked (ASO-R1-2).
    Map<String, dynamic> decidedLater() => {
          ..._detail(
              status: 'APPROVED',
              result: {'entityType': 'INVOICE', 'entityId': 1203, 'link': '/invoices/1203'}),
          'statusAt': 'PENDING',
          'decidedBy': {'id': 4, 'name': 'Sam South'},
          'decidedAt': '2026-09-27T14:14:30.803Z',
          'decisionNote': 'ok',
        };

    testWidgets('on a day it was still waiting, nothing decided after that day shows',
        (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41),
          asOf: DateTime(2026, 9, 25),
          routes: {'GET /api/approvals/41': _answer(decidedLater())},
          overrides: [_noBadge]);
      expect(find.text('Approval #41'), findsOneWidget);
      expect(find.text('Awaiting approval'), findsWidgets);
      expect(find.text('Approved'), findsNothing);
      expect(find.text('Decided'), findsNothing);
      expect(find.textContaining('Sam South'), findsNothing);
      expect(find.text('Note'), findsNothing);
      expect(find.text('ok'), findsNothing);
      expect(find.textContaining('Open '), findsNothing);
    });

    testWidgets('live, the same change shows its decision and the record it produced',
        (tester) async {
      await open(tester, decidedLater());
      expect(find.text('Approved'), findsWidgets);
      expect(find.text('Decided'), findsOneWidget);
      expect(find.textContaining('by Sam South'), findsOneWidget);
      expect(find.text('Note'), findsOneWidget);
      expect(find.text('ok'), findsOneWidget);
      expect(find.text('Open INV-20260926-0003'), findsOneWidget);
    });

    testWidgets('on a day it had been approved, the record it produced opens', (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41),
          asOf: DateTime(2026, 9, 28),
          routes: {'GET /api/approvals/41': _answer({...decidedLater(), 'statusAt': 'APPROVED'})},
          overrides: [_noBadge]);
      expect(find.text('Open INV-20260926-0003'), findsOneWidget);
      expect(find.text('Decided'), findsOneWidget);
    });

    // ASO-R5-1: an approver left behind by a region move still sees the change (SC-15), but its
    // record and customer now answer 404 for them, so the page must not link into either.
    String location(WidgetTester tester) => GoRouter.of(tester.element(find.text('Approval #41')))
        .routerDelegate
        .currentConfiguration
        .uri
        .toString();
    Finder inkWellAround(String text) =>
        find.ancestor(of: find.text(text), matching: find.byType(InkWell));

    testWidgets('a change whose record the viewer can no longer see does not link to it',
        (tester) async {
      await open(tester, _detail(status: 'CLOSED', blockedReason: kNoRecordAccessReason));
      // The harness mounts approval 41 here; its route stands in for /approvals/41.
      final at = location(tester);
      expect(find.text('You no longer have access to this record'), findsOneWidget);
      expect(find.text('INV-20260926-0003'), findsOneWidget);
      expect(find.text('Acme Ltd'), findsOneWidget);
      expect(inkWellAround('INV-20260926-0003'), findsNothing);
      expect(inkWellAround('Acme Ltd'), findsNothing);

      for (final text in ['INV-20260926-0003', 'Acme Ltd']) {
        await tester.tap(find.text(text));
        await tester.pumpAndSettle();
        expect(location(tester), at);
        expect(find.text('Approval #41'), findsOneWidget);
        expect(find.text('invoice 1203'), findsNothing);
      }
    });

    testWidgets('an approved change whose record the viewer can no longer see offers no Open',
        (tester) async {
      await open(
          tester,
          _detail(
              status: 'APPROVED',
              result: {'entityType': 'INVOICE', 'entityId': 1203},
              blockedReason: 'You no longer have access to this record'));
      expect(find.text('You no longer have access to this record'), findsOneWidget);
      expect(find.textContaining('Open '), findsNothing);
      expect(inkWellAround('INV-20260926-0003'), findsNothing);
    });

    testWidgets('a change whose record the viewer can see links to the record and customer',
        (tester) async {
      await open(tester, _detail(status: 'CLOSED'));
      expect(inkWellAround('INV-20260926-0003'), findsOneWidget);
      expect(inkWellAround('Acme Ltd'), findsOneWidget);
      await tester.tap(find.text('INV-20260926-0003'));
      await tester.pumpAndSettle();
      expect(find.text('invoice 1203'), findsOneWidget);
    });

    testWidgets('an approved change whose record the viewer can see offers Open', (tester) async {
      await open(
          tester, _detail(status: 'APPROVED', result: {'entityType': 'INVOICE', 'entityId': 1203}));
      expect(find.text('Open INV-20260926-0003'), findsOneWidget);
      expect(inkWellAround('INV-20260926-0003'), findsOneWidget);
    });

    testWidgets("on a past date, 'Read-only in the as-of view' still links to the record",
        (tester) async {
      await _pump(tester,
          page: const ApprovalDetailScreen(id: 41),
          asOf: DateTime(2026, 9, 28),
          routes: {
            'GET /api/approvals/41': _answer({
              ..._detail(
                  status: 'APPROVED',
                  result: {'entityType': 'INVOICE', 'entityId': 1203, 'link': '/invoices/1203'},
                  blockedReason: 'Read-only in the as-of view'),
              'statusAt': 'APPROVED',
            })
          },
          overrides: [_noBadge]);
      expect(find.text('Read-only in the as-of view'), findsOneWidget);
      expect(find.text('Open INV-20260926-0003'), findsOneWidget);
      expect(inkWellAround('INV-20260926-0003'), findsOneWidget);
      expect(inkWellAround('Acme Ltd'), findsOneWidget);
    });

    testWidgets('a value of several lines is shown whole, not cut off at one row', (tester) async {
      await open(tester, {
        ..._detail(),
        'changes': [
          {
            'field': 'INVOICE_CANCEL',
            'label': 'Cancel invoice',
            'current': null,
            'proposed': {'amount': 6000, 'windowHours': 12},
          },
        ],
      });
      final table = tester.widget<DataTable>(find.byType(DataTable));
      expect(table.dataRowMaxHeight, double.infinity);

      final text = find.textContaining('"windowHours": 12');
      expect(text, findsOneWidget);
      expect(tester.widget<Text>(text).data, contains('"amount": "${formatMoney(6000)}"'));
      final textBox = tester.getRect(text);
      // The row the value sits in holds the whole text: nothing is clipped at 48 px.
      final cell = find.ancestor(of: text, matching: find.byType(Padding)).first;
      final cellBox = tester.getRect(cell);
      expect(textBox.height, greaterThan(48));
      expect(cellBox.height, greaterThanOrEqualTo(textBox.height));
      expect(cellBox.top, lessThanOrEqualTo(textBox.top));
      expect(cellBox.bottom, greaterThanOrEqualTo(textBox.bottom));
      final row = find.ancestor(of: text, matching: find.byType(TableCell)).first;
      expect(tester.getRect(row).height, greaterThanOrEqualTo(textBox.height));
      expect(tester.getRect(row).bottom, greaterThanOrEqualTo(textBox.bottom));
    });

    testWidgets('the change table names people, and reads dates and codes as words',
        (tester) async {
      await open(tester, {
        ..._detail(),
        'changes': [
          {
            'field': 'salesPocUserId',
            'label': 'Sales POC',
            'current': null,
            'proposed': 4,
            'currentText': null,
            'proposedText': 'Sam South',
          },
          {
            'field': 'invoiceDate',
            'label': 'Invoice date',
            'current': null,
            'proposed': '2026-09-27T14:50:16Z',
          },
          {'field': 'paymentTerm', 'label': 'Payment term', 'current': 'NET_30', 'proposed': 'CUSTOM'},
          {'field': 'collectionPocUserId', 'label': 'Collection poc user id', 'proposed': 5},
          {'field': 'dueDate', 'label': 'Due date', 'current': null, 'proposed': '2026-12-31'},
        ],
      });
      expect(find.text('Sales POC'), findsOneWidget);
      expect(find.text('Sam South'), findsOneWidget);
      expect(find.text('4'), findsNothing);
      expect(find.text(formatDateTime('2026-09-27T14:50:16Z')), findsOneWidget);
      expect(find.text('2026-09-27T14:50:16Z'), findsNothing);
      expect(find.text('Custom'), findsOneWidget);
      expect(find.text('Net 30'), findsOneWidget);
      expect(find.text('CUSTOM'), findsNothing);
      // An older server's mechanical label, still read as a name.
      expect(find.text('Collection POC'), findsOneWidget);
      expect(find.text('Collection poc user id'), findsNothing);
      expect(find.text(formatDate(DateTime.parse('2026-12-31'))), findsOneWidget);
    });

    testWidgets("an older server's 'Sales poc user id' reads as 'Sales POC'", (tester) async {
      await open(tester, {
        ..._detail(),
        'changes': [
          {'field': 'salesPocUserId', 'label': 'Sales poc user id', 'current': 3, 'proposed': 4},
        ],
      });
      expect(find.text('Sales POC'), findsOneWidget);
      expect(find.text('Sales poc user id'), findsNothing);
      // With no name from the server, the id is all there is.
      expect(find.text('4'), findsOneWidget);
    });

    testWidgets('id lists and invoice lines read as words, not JSON', (tester) async {
      await open(tester, {
        ..._detail(),
        'changes': [
          {
            'field': 'invoiceIds',
            'label': 'Invoice ids',
            'current': null,
            'proposed': [45],
            'proposedText': null,
          },
          {
            'field': 'items',
            'label': 'Items',
            'current': null,
            'proposed': [
              {'productId': 1, 'quantity': 150, 'unitPrice': 100},
              {'productId': 1, 'quantity': 2},
            ],
            'proposedText': null,
          },
        ],
      });
      final table = find.byType(DataTable);
      // The sidebar has its own 'Invoices': the label is looked for in the changes table.
      expect(find.descendant(of: table, matching: find.text('Invoices')), findsOneWidget);
      expect(find.text('Invoice ids'), findsNothing);
      expect(find.text('#45'), findsOneWidget);
      expect(
          find.text('150 × product #1 at ${formatMoney(100)}\n2 × product #1 at list price'),
          findsOneWidget);
      expect(find.descendant(of: table, matching: find.textContaining('productId')), findsNothing);
      expect(find.descendant(of: table, matching: find.textContaining('[')), findsNothing);
    });

    testWidgets('server text for invoices and items is shown as sent', (tester) async {
      await open(tester, {
        ..._detail(),
        'changes': [
          {
            'field': 'invoiceIds',
            'label': 'Invoices',
            'current': null,
            'proposed': [45],
            'proposedText': 'INV-20260927-0045',
          },
          {
            'field': 'items',
            'label': 'Items',
            'current': null,
            'proposed': [
              {'productId': 1, 'quantity': 150, 'unitPrice': 100},
            ],
            'proposedText': '150 × E2E Widget at 100.00',
          },
        ],
      });
      final table = find.byType(DataTable);
      expect(find.descendant(of: table, matching: find.text('Invoices')), findsOneWidget);
      expect(find.text('INV-20260927-0045'), findsOneWidget);
      expect(find.text('150 × E2E Widget at 100.00'), findsOneWidget);
      expect(find.text('#45'), findsNothing);
    });
  });

  group('the record banner', () {
    Future<FakeBackend> banner(WidgetTester tester, Object? Function(RequestOptions) answer,
        {CurrentUser? user, String entityType = 'INVOICE'}) async {
      final (backend, _) = await _pump(tester,
          page: Material(child: PendingChangeBanner(entityType: entityType, entityId: 1203)),
          user: user,
          routes: {'GET /api/approvals/for-record': answer},
          overrides: [_noBadge]);
      return backend;
    }

    testWidgets('names the change, who asked and when, with a way to open it', (tester) async {
      final backend = await banner(tester, (_) => [_brief()]);
      expect(
          find.text('Awaiting approval: Cancel invoice INV-20260926-0003 (₹1,20,000.00) · '
              'requested by Sam Sales on 2026-09-26'),
          findsOneWidget);
      expect(backend.sent('GET /api/approvals/for-record').single.queryParameters,
          {'entityType': 'INVOICE', 'entityId': 1203});

      await tester.tap(find.text('View'));
      await tester.pumpAndSettle();
      expect(find.text('approval 41'), findsOneWidget);
    });

    testWidgets('does not say who asked when the server does not', (tester) async {
      await banner(tester, (_) => [_brief(requestedBy: null)]);
      expect(
          find.text('Awaiting approval: Cancel invoice INV-20260926-0003 (₹1,20,000.00) · '
              'requested on 2026-09-26'),
          findsOneWidget);
    });

    testWidgets('is not there when nothing is waiting', (tester) async {
      await banner(tester, (_) => const []);
      expect(find.textContaining('Awaiting approval'), findsNothing);
      expect(find.text('View'), findsNothing);
    });

    testWidgets('is not there when the list cannot be had', (tester) async {
      await banner(tester, (_) => const FakeFailure(404, {'message': 'Not found'}));
      expect(find.textContaining('Awaiting approval'), findsNothing);
    });

    testWidgets("a customer login's own customer page asks nothing", (tester) async {
      final backend = await banner(tester, (_) => [_brief()],
          entityType: 'CUSTOMER',
          user: const CurrentUser(
              id: 30,
              username: 'acme',
              fullName: 'Acme',
              role: 'CUSTOMER',
              privileges: {Privileges.invoiceView},
              customerId: 77));
      expect(backend.sent('GET /api/approvals/for-record'), isEmpty);
    });

    final pages = <(String, Widget, String, Map<String, dynamic>)>[
      (
        'customer',
        const CustomerDetailScreen(id: 5),
        'GET /api/customers/5',
        {
          'id': 5,
          'name': 'Acme Ltd',
          'outstanding': 1000.0,
          'creditBalance': 0.0,
        }
      ),
      (
        'invoice',
        const InvoiceDetailScreen(id: 42),
        'GET /api/invoices/42',
        {
          'id': 42,
          'invoiceNumber': 'INV-0042',
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'invoiceDate': '2026-06-01',
          'dueDate': '2026-07-01',
          'total': 1000.0,
          'paidAmount': 0.0,
          'balance': 1000.0,
          'status': 'UNPAID',
          'items': [],
        }
      ),
      (
        'payment',
        const PaymentDetailScreen(id: 7),
        'GET /api/payments/7',
        {
          'id': 7,
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'amount': 500.0,
          'paidAt': '2026-06-10T10:00:00Z',
          'status': 'ACTIVE',
          'invoices': [],
        }
      ),
      (
        'promise',
        const PromiseDetailScreen(id: 3),
        'GET /api/promises/3',
        {
          'id': 3,
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'amount': 500.0,
          'remainingAmount': 500.0,
          'promisedDate': '2026-07-15',
          'status': 'OPEN',
        }
      ),
      (
        'dispute',
        const DisputeDetailScreen(id: 11),
        'GET /api/disputes/11',
        {
          'id': 11,
          'customerId': 5,
          'customerName': 'Acme Ltd',
          'openedByUserId': 9,
          'targetType': 'INVOICE',
          'targetId': 42,
          'targetNumber': 'INV-0042',
          'reason': 'Billed twice',
          'status': 'PENDING',
          'createdAt': '2026-06-02T10:00:00Z',
        }
      ),
    ];
    for (final (noun, page, route, json) in pages) {
      testWidgets('sits at the top of the $noun page', (tester) async {
        final (backend, _) = await _pump(tester, page: page, routes: {
          route: (_) => json,
          'GET /api/disputes': (_) => _page(const []),
          'GET /api/promises': (_) => _page(const []),
          'GET /api/approvals/for-record': (_) => [_brief()],
        }, overrides: [
          _noBadge
        ]);
        expect(tester.takeException(), isNull);
        expect(find.textContaining('Awaiting approval: Cancel invoice'), findsOneWidget);
        expect(backend.sent('GET /api/approvals/for-record').last.queryParameters['entityType'],
            noun.toUpperCase());
      });
    }
  });

  group('the approvals list', () {
    Map<String, Object? Function(RequestOptions)> routes(
            {String? asOf, List<Map<String, dynamic>>? rows}) =>
        {
          'GET /api/table-schemas/approvals': (_) => approvalsSchema,
          'GET /api/approvals': _answer(_page(rows ?? [_brief()], asOf: asOf)),
          'GET /api/approvals/summary': _answer(
              {'pendingCount': 7, 'pendingAmount': 950000.0, 'awaitingMeCount': 3, 'mineCount': 2}),
        };

    const list = ApprovalsScreen(query: TableQuery(sort: 'requestedAt,desc'));

    testWidgets('an approver starts on what awaits them, and the view goes as one value',
        (tester) async {
      final (backend, _) = await _pump(tester, page: list, routes: routes(), overrides: [_noBadge]);
      expect(tester.takeException(), isNull);
      expect(find.text('Cancel invoice INV-20260926-0003 (₹1,20,000.00)'), findsOneWidget);
      expect(backend.sent('GET /api/approvals').last.queryParameters['view'], 'AWAITING_ME');
      expect(
          backend.sent('GET /api/approvals/summary').last.queryParameters['view'], 'AWAITING_ME');

      await tester.tap(find.text('My requests').first);
      await tester.pumpAndSettle();
      final params = backend.sent('GET /api/approvals').last.queryParameters;
      expect(params['view'], 'MINE');
      expect(params['view'], isA<String>());
      expect(params['sort'], 'requestedAt,desc');

      await tester.tap(find.text('All I can see'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/approvals').last.queryParameters['view'], 'ALL');
    });

    testWidgets("'Requested by' does not sort: the server refuses sort=requestedByUserId",
        (tester) async {
      final (backend, _) = await _pump(tester, page: list, routes: routes(), overrides: [_noBadge]);
      final headers = tester.widget<DataTable>(find.byType(DataTable).first).columns;
      DataColumn header(String label) => headers.firstWhere((c) {
            final w = c.label;
            final text = w is Text ? w : (w is ConstrainedBox ? w.child : null);
            return text is Text && text.data == label;
          });
      expect(header('Requested by').onSort, isNull);
      expect(header('Requested').onSort, isNotNull);

      await tester.ensureVisible(find.text('Requested by'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Requested by'));
      await tester.pumpAndSettle();
      expect(
          backend
              .sent('GET /api/approvals')
              .where((r) => '${r.queryParameters['sort']}'.startsWith('requestedByUserId')),
          isEmpty);

      // A column the schema publishes as sortable still sorts.
      await tester.ensureVisible(find.text('Amount'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Amount'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/approvals').last.queryParameters['sort'], 'amount,asc');
    });

    testWidgets("exporting every matching row exports the view on screen, not all I can see",
        (tester) async {
      final (backend, _) = await _pump(tester,
          page: list,
          routes: {
            ...routes(),
            'GET /api/approvals': _answer({..._page([_brief()]), 'totalElements': 30}),
            'POST /api/approvals/export': (_) => 'id,status\n41,PENDING\n',
          },
          user: _user(privileges: {..._approving, Privileges.exportData}),
          overrides: [_noBadge]);
      expect(backend.sent('GET /api/approvals').last.queryParameters['view'], 'AWAITING_ME');

      await tester.tap(find.byType(Checkbox).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Select all 30 matching this filter'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Export selected'));
      await tester.pumpAndSettle();

      final export = backend.sent('POST /api/approvals/export').single;
      expect(export.queryParameters['view'], 'AWAITING_ME');
      expect((export.data as Map)['selectAllMatchingFilter'], isTrue);
      expect(find.text('Exported CSV'), findsOneWidget);
    });

    testWidgets('someone who cannot approve starts on their own requests', (tester) async {
      final (backend, _) = await _pump(tester,
          page: list,
          routes: routes(),
          user: _user(privileges: _viewing, regions: const [_north]),
          overrides: [_noBadge]);
      expect(backend.sent('GET /api/approvals').last.queryParameters['view'], 'MINE');
      expect(find.text('Awaiting my approval'), findsNothing);
    });

    testWidgets('on a past date each row shows its status then', (tester) async {
      final (backend, _) = await _pump(tester,
          page: list,
          asOf: DateTime(2026, 6, 30),
          routes: routes(asOf: '2026-06-30', rows: [
            _brief(status: 'APPROVED', statusAt: 'PENDING'),
          ]),
          overrides: [_noBadge]);
      expect(tester.takeException(), isNull);
      expect(backend.sent('GET /api/approvals').last.queryParameters['asOf'], '2026-06-30');
      expect(find.text('Awaiting approval'), findsWidgets);
      expect(find.text('now Approved'), findsOneWidget);
    });

    testWidgets('live, the row shows where it stands now', (tester) async {
      await _pump(tester,
          page: list, routes: routes(rows: [_brief(status: 'APPROVED')]), overrides: [_noBadge]);
      expect(find.text('Approved'), findsOneWidget);
      expect(find.textContaining('now '), findsNothing);
    });

    testWidgets('a threshold change is about a region, with no customer', (tester) async {
      await _pump(tester,
          page: list,
          routes: routes(rows: [
            {
              ..._brief(entityType: 'REGION', entityId: 2),
              'operation': 'THRESHOLD_CHANGE',
              'operationLabel': 'Change approval limits',
              'entityLabel': null,
              'customerId': null,
              'customerName': null,
              'summary': 'Raise the South limit for Create invoice',
            },
          ]),
          overrides: [_noBadge]);
      expect(tester.takeException(), isNull);
      expect(find.text('Raise the South limit for Create invoice'), findsOneWidget);
      expect(find.text('South'), findsWidgets);
    });

    testWidgets('a row opens its change', (tester) async {
      await _pump(tester, page: list, routes: routes(), overrides: [_noBadge]);
      await tester.tap(find.text('Cancel invoice INV-20260926-0003 (₹1,20,000.00)'));
      await tester.pumpAndSettle();
      expect(find.text('approval 41'), findsOneWidget);
    });

    testWidgets('another view starts again at its first page', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const ApprovalsScreen(query: TableQuery(page: 2, sort: 'requestedAt,desc')),
          routes: routes(),
          overrides: [_noBadge]);
      expect('${backend.sent('GET /api/approvals').last.queryParameters['page']}', '2');

      await tester.tap(find.text('My requests').first);
      await tester.pumpAndSettle();
      final params = backend.sent('GET /api/approvals').last.queryParameters;
      expect(params['view'], 'MINE');
      expect('${params['page']}', '0');
      expect(params['sort'], 'requestedAt,desc');
    });

    testWidgets('the view chosen survives a trip to a change and back', (tester) async {
      final (backend, _) = await _pump(tester, page: list, routes: routes(), overrides: [_noBadge]);
      await tester.tap(find.text('All I can see'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel invoice INV-20260926-0003 (₹1,20,000.00)'));
      await tester.pumpAndSettle();
      expect(find.text('approval 41'), findsOneWidget);

      // The change's Back link carries no query.
      GoRouter.of(tester.element(find.text('approval 41'))).go('/approvals');
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/approvals').last.queryParameters['view'], 'ALL');
    });

    testWidgets('a URL naming a view opens on it', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const ApprovalsScreen(query: TableQuery(), view: ApprovalsView.ALL),
          routes: routes(),
          overrides: [_noBadge]);
      expect(backend.sent('GET /api/approvals').last.queryParameters['view'], 'ALL');
    });

    testWidgets('the limits are a click away for whoever approves, and not for anyone else',
        (tester) async {
      await _pump(tester, page: list, routes: routes(), overrides: [_noBadge]);
      await tester.tap(find.byKey(const ValueKey('approvals-thresholds')));
      await tester.pumpAndSettle();
      expect(find.text('thresholds null'), findsOneWidget);
    });

    testWidgets('someone who only views records gets no link to the limits', (tester) async {
      await _pump(tester,
          page: list,
          routes: routes(),
          user: _user(privileges: _viewing, regions: const [_north]),
          overrides: [_noBadge]);
      expect(find.byKey(const ValueKey('approvals-thresholds')), findsNothing);
    });

    test("a threshold change's record is the region's limits, not its page", () {
      expect(approvalEntityRoute('REGION', 2), '/approval-thresholds?regionId=2');
    });

    testWidgets('the list is not for customer logins, though one change is', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final customer = {
        'id': 12,
        'username': 'acme',
        'fullName': 'Acme Buyer',
        'role': 'CUSTOMER',
        'customerId': 77,
        'privileges': [Privileges.invoiceView],
        'regions': const [],
      };
      final backend = FakeBackend({
        'POST /api/auth/login': (_) => {'token': 't', 'expiresInMs': 1000, 'user': customer},
      });
      final container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(backend.dio),
        tokenStorageProvider.overrideWithValue(_MemoryTokens()),
      ]);
      addTearDown(container.dispose);
      await tester
          .runAsync(() => container.read(authControllerProvider.notifier).login('acme', 'pw'));
      expect(container.read(currentUserProvider)!.isCustomer, isTrue);

      await tester.pumpWidget(const SizedBox());
      final context = tester.element(find.byType(SizedBox));
      final router = container.read(routerProvider);
      GoRouterState at(String path) => GoRouterState(router.configuration,
          uri: Uri.parse(path),
          matchedLocation: path,
          fullPath: path,
          pathParameters: const {},
          pageKey: const ValueKey('page'));
      final routes = router.configuration.routes;
      expect(await _routeAt(routes, '/approvals').redirect!(context, at('/approvals')), '/');
      expect(
          await _routeAt(routes, '/approvals/:id').redirect!(context, at('/approvals/41')), isNull);
    });
  });

  group('the nav badge', () {
    testWidgets('shows how many changes await me', (tester) async {
      await _pump(tester,
          page: const Text('home'),
          routes: const {},
          overrides: [approvalsAwaitingMeCountProvider.overrideWith((ref) => Stream.value(3))]);
      final badge =
          find.ancestor(of: find.byIcon(Icons.fact_check_outlined), matching: find.byType(Badge));
      expect(badge, findsOneWidget);
      expect(find.descendant(of: badge, matching: find.text('3')), findsOneWidget);
    });

    test('asks nothing of someone who cannot approve', () async {
      final backend = FakeBackend({
        'GET /api/approvals/awaiting-me/count': (_) => {'count': 5},
      });
      final container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_user(privileges: _viewing)),
      ]);
      addTearDown(container.dispose);
      final seen = <int>[];
      container.listen(approvalsAwaitingMeCountProvider, (_, next) => next.whenData(seen.add),
          fireImmediately: true);
      await Future<void>.delayed(Duration.zero);
      expect(seen, [0]);
      expect(backend.requests, isEmpty);
    });

    test('an approver gets the count, for the regions asked about', () async {
      late ProviderContainer container;
      final backend = FakeBackend({
        'GET /api/approvals/awaiting-me/count': (_) => {'count': 5},
      });
      final dio = backend.dioWith([viewScopeInterceptor(() => container.read(viewScopeProvider))]);
      container = ProviderContainer(overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(_user()),
      ]);
      addTearDown(container.dispose);
      final seen = <int>[];
      container.listen(approvalsAwaitingMeCountProvider, (_, next) => next.whenData(seen.add));
      await container.read(approvalsAwaitingMeCountProvider.future);
      expect(seen, [5]);

      container.read(viewScopeProvider.notifier).setRegions({2});
      // Reading the count again builds it for the new regions, and waits for that answer.
      await container.read(approvalsAwaitingMeCountProvider.future);
      expect(
          backend
              .sent('GET /api/approvals/awaiting-me/count')
              .map((r) => r.queryParameters['regionIds']),
          [null, '2']);
    });
  });

  group('the thresholds screen', () {
    Future<FakeBackend> open(WidgetTester tester,
        {required Object? Function(RequestOptions) put, CurrentUser? user, DateTime? asOf}) async {
      final (backend, _) = await _pump(tester,
          page: const ApprovalThresholdsScreen(regionId: 2),
          user: user,
          asOf: asOf,
          routes: {
            'GET /api/regions/2/approval-thresholds': (_) => _thresholds(),
            'PUT /api/regions/2/approval-thresholds': put,
          },
          overrides: [
            _noBadge
          ]);
      return backend;
    }

    Finder amount(String op) => find.byKey(ValueKey('threshold-amount-$op'));
    Finder hours(String op) => find.byKey(ValueKey('threshold-hours-$op'));

    testWidgets('lists every operation with its limit and window', (tester) async {
      await open(tester, put: (_) => _thresholds());
      expect(tester.takeException(), isNull);
      expect(find.text('Create invoice'), findsOneWidget);
      expect(find.text('Cancel invoice'), findsOneWidget);
      expect(tester.widget<TextField>(amount('INVOICE_CREATE')).controller!.text, '100000.00');
      expect(tester.widget<TextField>(hours('INVOICE_CREATE')).controller!.text, '24');
      expect(tester.widget<TextField>(amount('INVOICE_CANCEL')).controller!.text, '');
      expect(find.textContaining('3 people approve'), findsOneWidget);
    });

    testWidgets('Save sends exactly what changed: amounts and windows', (tester) async {
      final backend = await open(tester, put: (_) => _thresholds());
      await tester.enterText(amount('INVOICE_CREATE'), '80000');
      await tester.enterText(amount('INVOICE_CANCEL'), '250000');
      await tester.enterText(hours('INVOICE_CANCEL'), '12');
      await tester.enterText(amount('PAYMENT_RECORD'), '');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();

      expect(backend.sent('PUT /api/regions/2/approval-thresholds').single.data, {
        'thresholds': [
          {'operation': 'INVOICE_CREATE', 'amount': 80000.0, 'windowHours': 24},
          {'operation': 'INVOICE_CANCEL', 'amount': 250000.0, 'windowHours': 12},
          {'operation': 'PAYMENT_RECORD', 'amount': null},
        ],
      });
      expect(find.text('Approval thresholds saved'), findsOneWidget);
    });

    testWidgets('"Apply to all" sets every window, and only a changed window is sent',
        (tester) async {
      final backend = await open(tester, put: (_) => _thresholds());
      await tester.enterText(find.byKey(const ValueKey('thresholds-all-hours')), '48');
      await tester.tap(find.byKey(const ValueKey('thresholds-apply-all')));
      await tester.pump();
      expect(tester.widget<TextField>(hours('INVOICE_CANCEL')).controller!.text, '48');

      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();
      // INVOICE_CANCEL has no limit, so its window alone is nothing to send.
      expect(backend.sent('PUT /api/regions/2/approval-thresholds').single.data, {
        'thresholds': [
          {'operation': 'INVOICE_CREATE', 'amount': 100000.0, 'windowHours': 48},
          {'operation': 'PAYMENT_RECORD', 'amount': 50000.0, 'windowHours': 48},
        ],
      });
    });

    testWidgets('a bad amount or window is caught before anything is sent', (tester) async {
      final backend = await open(tester, put: (_) => _thresholds());
      await tester.enterText(amount('INVOICE_CREATE'), 'lots');
      await tester.enterText(hours('PAYMENT_RECORD'), '900');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();
      expect(find.text('Enter an amount such as 100000 or 2500.50'), findsOneWidget);
      expect(find.text('From 0 to 720 hours'), findsOneWidget);
      expect(backend.sent('PUT /api/regions/2/approval-thresholds'), isEmpty);
    });

    testWidgets("the server's field errors land under their fields", (tester) async {
      await open(tester,
          put: (_) => const FakeFailure(400, {
                'message': 'Validation failed',
                'fieldErrors': {'thresholds[0].amount': 'must be at most 999999999999.99'},
              }));
      await tester.enterText(amount('INVOICE_CANCEL'), '5');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();
      final field = tester.widget<TextField>(amount('INVOICE_CANCEL'));
      expect(field.decoration!.errorText, 'must be at most 999999999999.99');
    });

    testWidgets('a raise waits for a second approver: the notice, the banner and the warning',
        (tester) async {
      await open(tester,
          put: (_) => FakeReply(
                202,
                _thresholds(
                  pendingChange: {
                    ..._brief(id: 77, entityType: 'REGION', entityId: 2),
                    'operation': 'THRESHOLD_CHANGE',
                    'summary': 'Raise the limit for Create invoice to ₹2,00,000.00',
                    'customerId': null,
                    'customerName': null,
                  },
                  warnings: ['Only 1 person can approve changes in South'],
                ),
                headers: {
                  'Location': ['/api/approvals/77'],
                },
              ));
      await tester.enterText(amount('INVOICE_CREATE'), '200000');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();

      expect(find.text('Raising or removing limits needs a second approver'), findsOneWidget);
      expect(find.text('Approval thresholds saved'), findsNothing);
      expect(find.textContaining('Awaiting approval: Raise the limit for Create invoice'),
          findsOneWidget);
      expect(find.text('Only 1 person can approve changes in South'), findsOneWidget);

      await tester.tap(find.widgetWithText(SnackBarAction, 'View'));
      await tester.pumpAndSettle();
      expect(find.text('approval 77'), findsOneWidget);
    });

    testWidgets('the region cannot change while a save is on its way', (tester) async {
      final backend = await open(tester, put: (_) => _thresholds());
      final gate = Completer<void>();
      backend.held['PUT /api/regions/2/approval-thresholds'] = gate.future;
      DropdownButton<int> region() => tester.widget<DropdownButton<int>>(find.descendant(
          of: find.byKey(const ValueKey('thresholds-region')),
          matching: find.byType(DropdownButton<int>)));
      expect(region().onChanged, isNotNull);

      await tester.enterText(amount('INVOICE_CREATE'), '80000');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pump();
      expect(region().onChanged, isNull);

      gate.complete();
      await tester.pumpAndSettle();
      expect(region().onChanged, isNotNull);
      expect(find.text('Approval thresholds saved'), findsOneWidget);
    });

    testWidgets('a 202 is held even when its body names no change: the id comes from Location',
        (tester) async {
      await open(tester,
          put: (_) => FakeReply(202, _thresholds(pendingChange: {'status': 'PENDING'}), headers: {
                'Location': ['/api/approvals/88'],
              }));
      await tester.enterText(amount('INVOICE_CREATE'), '200000');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Raising or removing limits needs a second approver'), findsOneWidget);
      expect(find.text('Approval thresholds saved'), findsNothing);

      await tester.tap(find.widgetWithText(SnackBarAction, 'View'));
      await tester.pumpAndSettle();
      expect(find.text('approval 88'), findsOneWidget);
    });

    testWidgets('a 202 with no way to name the change still says it waits', (tester) async {
      await open(tester, put: (_) => FakeReply(202, _thresholds()));
      await tester.enterText(amount('INVOICE_CREATE'), '200000');
      await tester.tap(find.byKey(const ValueKey('thresholds-save')));
      await tester.pumpAndSettle();
      expect(find.text('Raising or removing limits needs a second approver'), findsOneWidget);
      expect(find.text('Approval thresholds saved'), findsNothing);
      expect(find.widgetWithText(SnackBarAction, 'View'), findsNothing);
    });

    testWidgets('read only without the right to manage limits, or on a past date', (tester) async {
      const approverOnly = RegionAccess(
          id: 2,
          code: 'SOUTH',
          name: 'South',
          level: 'APPROVE',
          scope: 'ALL',
          privileges: {..._viewing, Privileges.changeApprove});
      await open(tester,
          put: (_) => _thresholds(),
          user: _user(
              privileges: {..._viewing, Privileges.changeApprove}, regions: const [approverOnly]));
      expect(find.byKey(const ValueKey('thresholds-save')), findsNothing);
      expect(tester.widget<TextField>(amount('INVOICE_CREATE')).enabled, isFalse);
    });

    testWidgets('read only on a past date', (tester) async {
      await open(tester, put: (_) => _thresholds(), asOf: DateTime(2026, 6, 30));
      expect(find.byKey(const ValueKey('thresholds-save')), findsNothing);
      expect(tester.widget<TextField>(amount('INVOICE_CREATE')).enabled, isFalse);
    });
  });

  group('history entries of held changes', () {
    test('a pending entry with no id of its own has a key of its own', () {
      AuditEntry entry(int pc) => AuditEntry.fromJson({
            'entityType': 'INVOICE',
            'entityId': 1203,
            'action': 'CHANGE_REQUESTED',
            'createdAt': '2026-09-26T09:14:03Z',
            'pendingChangeId': pc,
          });
      expect(entry(41).stableKey, 'INVOICE:1203:CHANGE_REQUESTED:pending:41');
      expect(entry(41).stableKey, isNot(entry(42).stableKey));
      expect(auditActionLabel('CHANGE_REQUESTED'), 'Change sent for approval');
      expect(auditActionLabel('CHANGE_APPLY_FAILED'), 'Approved change could not be applied');
    });

    testWidgets('say which approval, who approved it, and where it stands', (tester) async {
      await _pump(tester,
          page: const SingleChildScrollView(
              child: AuditHistoryPanel(entityType: 'INVOICE', entityId: 1203)),
          routes: {
            'GET /api/audit': (_) => [
                  {
                    'id': 2,
                    'entityType': 'INVOICE',
                    'entityId': 1203,
                    'action': 'INVOICE_CANCELLED',
                    'changedByUserId': 9,
                    'changedByUsername': 'sam',
                    'createdAt': '2026-09-27T10:00:00Z',
                    'pendingChangeId': 41,
                    'origin': 'APPROVAL',
                    'approvedByUserId': 3,
                    'approvedByUsername': 'cara',
                  },
                  {
                    'id': 1,
                    'entityType': 'INVOICE',
                    'entityId': 1203,
                    'action': 'CHANGE_REQUESTED',
                    'changedByUserId': 9,
                    'changedByUsername': 'sam',
                    'createdAt': '2026-09-26T09:14:03Z',
                    'pendingChangeId': 41,
                    'pendingStatus': 'APPROVED',
                  },
                ],
          },
          overrides: [
            _noBadge
          ]);
      expect(find.text('Change sent for approval'), findsOneWidget);
      expect(find.textContaining('via approval #41 · approved by cara'), findsOneWidget);
      expect(find.text('Approval #41'), findsNWidgets(2));
      expect(find.text('Approved'), findsOneWidget);
    });

    testWidgets('JsonDiffView shows both sides, or the one there is', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Material(
          child: Column(children: [
            JsonDiffView(
                before: {'a': 1}, after: '{"a":2}', beforeLabel: 'Current', afterLabel: 'Proposed'),
            JsonDiffView(before: null, after: {'b': true}),
          ]),
        ),
      ));
      expect(find.text('Current'), findsOneWidget);
      expect(find.text('Proposed'), findsOneWidget);
      expect(find.text('Details'), findsOneWidget);
      expect(find.text('{\n  "a": 2\n}'), findsOneWidget);
    });
  });

  group('writes held for approval (202)', () {
    const writing = {
      ..._approving,
      Privileges.invoiceManage,
      Privileges.paymentManage,
      Privileges.promiseManage,
      Privileges.promiseOverride,
      Privileges.disputeManage,
      Privileges.pocView,
      Privileges.emailSend,
    };
    const southMaker = RegionAccess(
        id: 2, code: 'SOUTH', name: 'South', level: 'APPROVE', scope: 'ALL', privileges: writing);
    final maker = _user(privileges: writing, regions: const [southMaker]);

    // As §6.6 answers a held write: 202, the change in `pendingChange`, its address in Location,
    // and no record id anywhere.
    FakeReply held({
      int id = 41,
      String operation = 'INVOICE_CANCEL',
      String summary = 'Cancel invoice INV-20260926-0003 (₹1,20,000.00)',
      int? entityId = 1203,
      int approverCount = 3,
    }) =>
        FakeReply(
          202,
          {
            'status': 'PENDING_APPROVAL',
            'message': "Above South's approval limit of ₹1,00,000.00. It takes effect once "
                'someone else approves it.',
            'pendingChange': {
              ..._brief(id: id, entityId: entityId),
              'operation': operation,
              'summary': summary,
              'approverCount': approverCount,
              'supersededId': null,
              'canWithdraw': true,
            },
          },
          headers: {
            'Location': ['/api/approvals/$id'],
          },
        );

    const acmeJson = {'id': 77, 'name': 'Acme Ltd', 'outstanding': 0.0, 'creditBalance': 0.0};
    final collectionSeat = [
      {
        'id': 1,
        'pocType': 'COLLECTION',
        'primary': true,
        'user': {'id': 3, 'username': 'cara', 'fullName': 'Cara Checker'},
      },
    ];

    Future<void> tapShown(WidgetTester tester, Finder finder) async {
      await tester.ensureVisible(finder);
      await tester.pumpAndSettle();
      await tester.tap(finder);
      await tester.pumpAndSettle();
    }

    test('pendingFrom answers only a 202, and reads what there is of the change', () {
      Response<dynamic> reply(int status, Object? data, {String? location}) => Response(
            requestOptions: RequestOptions(path: '/api/invoices'),
            statusCode: status,
            data: data,
            headers: Headers.fromMap({
              if (location != null) 'location': [location],
            }),
          );

      expect(pendingFrom(reply(200, {'id': 913})), isNull);
      expect(
          pendingFrom(reply(200, {
            'pendingChange': {'id': 41}
          })),
          isNull);

      final full = pendingFrom(reply(202, held(approverCount: 0).data))!;
      expect(full.id, 41);
      expect(full.summary, 'Cancel invoice INV-20260926-0003 (₹1,20,000.00)');
      expect(full.operation, 'INVOICE_CANCEL');
      expect(full.operationLabel, 'Cancel invoice');
      expect(full.regionName, 'South');
      expect(full.approverCount, 0);
      expect(full.supersededId, isNull);
      expect(full.message, startsWith("Above South's approval limit"));

      final bare = pendingFrom(reply(202, {
        'pendingChange': {'id': 7}
      }))!;
      expect(bare.id, 7);
      expect(bare.summary, '');
      expect(bare.operation, isNull);
      expect(bare.approverCount, isNull);

      // Held all the same when the body names no change: the id comes from Location, if at all.
      expect(
          pendingFrom(reply(
                  202,
                  {
                    'pendingChange': {'status': 'PENDING'}
                  },
                  location: '/api/approvals/88'))!
              .id,
          88);
      expect(pendingFrom(reply(202, null, location: '/api/approvals/89'))!.id, 89);
      expect(pendingFrom(reply(202, 'odd'))!.id, isNull);
    });

    test('the notice says who can approve it when nobody can yet', () {
      const p =
          PendingChangeRef(id: 41, summary: 'Record payment of ₹60,000.00', regionName: 'South');
      expect(sentForApprovalText(p), 'Sent for approval: Record payment of ₹60,000.00');
      expect(sentForApprovalText(const PendingChangeRef(id: 41)), 'Sent for approval');
      expect(
          sentForApprovalText(const PendingChangeRef(
              id: 41,
              summary: 'Record payment of ₹60,000.00',
              regionName: 'South',
              approverCount: 0)),
          'Sent for approval: Record payment of ₹60,000.00. No one else can approve changes in '
          'South yet. Ask an administrator.');
      expect(sentForApprovalText(p, message: 'Cancellation sent for approval'),
          'Cancellation sent for approval');
    });

    testWidgets(
        'a held new invoice: the notice, no email, and the change opens instead of an invoice',
        (tester) async {
      final (backend, _) =
          await _pump(tester, page: const InvoiceFormScreen(), user: maker, routes: {
        'GET /api/pocs/my-scope': (_) => {'userId': 3, 'sales': true},
        'GET /api/pocs/assignable': (_) => [
              {'id': 3, 'username': 'cara', 'fullName': 'Cara Checker'},
            ],
        'GET /api/customers': (_) => _page([acmeJson]),
        'GET /api/invoices/due-date-preview': (_) =>
            {'dueDate': '2026-10-26', 'paymentTerm': 'NET_30', 'source': 'CUSTOMER'},
        'GET /api/products': (_) => _page([
              {'id': 5, 'name': 'Widget', 'price': 120000.0},
            ]),
        'POST /api/invoices': (_) => held(
            operation: 'INVOICE_CREATE',
            entityId: null,
            summary: 'Create invoice for Acme Ltd (₹1,20,000.00)'),
      }, overrides: [
        _noBadge
      ]);

      await tapShown(tester, find.text('Customer *'));
      await tapShown(tester, find.text('Acme Ltd'));
      await tapShown(tester, find.text('Product'));
      await tapShown(tester, find.text('Widget'));
      await tapShown(tester, find.text('Notify through email'));
      await tapShown(tester, find.text('Create invoice'));

      expect(tester.takeException(), isNull);
      final post = backend.sent('POST /api/invoices');
      expect(post, hasLength(1));
      expect((post.single.data as Map)['customerId'], 77);
      expect(find.text('Sent for approval: Create invoice for Acme Ltd (₹1,20,000.00)'),
          findsOneWidget);
      expect(find.text('Invoice created'), findsNothing);
      // The email is about an invoice, and there is none yet.
      expect(backend.sent('GET /api/emails/context'), isEmpty);
      expect(find.text('approval 41'), findsOneWidget);
    });

    testWidgets('a held payment: the dialog closes as done, with the notice and no email',
        (tester) async {
      bool? result;
      final (backend, _) = await _pump(tester, page: _Launcher((context) async {
        result =
            await showRecordPaymentDialog(context: context, customer: Customer.fromJson(acmeJson));
      }), user: maker, routes: {
        'GET /api/customers/77/pocs': (_) => collectionSeat,
        'GET /api/invoices': (_) => _page(const []),
        'GET /api/promises': (_) => _page(const []),
        'POST /api/payments': (_) => held(
            operation: 'PAYMENT_RECORD',
            entityId: null,
            summary: 'Record payment of ₹60,000.00 for Acme Ltd'),
      }, overrides: [
        _noBadge
      ]);

      await tapShown(tester, find.text('open'));
      await tester.enterText(find.widgetWithText(TextField, 'Amount *'), '60000');
      await tapShown(tester, find.text('Notify through email'));
      await tapShown(tester, find.text('Record'));

      expect(tester.takeException(), isNull);
      expect(backend.sent('POST /api/payments'), hasLength(1));
      expect(result, isTrue);
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text('Sent for approval: Record payment of ₹60,000.00 for Acme Ltd'),
          findsOneWidget);
      expect(backend.sent('GET /api/emails/context'), isEmpty);

      await tester.tap(find.widgetWithText(SnackBarAction, 'View'));
      await tester.pumpAndSettle();
      expect(find.text('approval 41'), findsOneWidget);
    });

    testWidgets('when nobody else can approve yet, the notice says so', (tester) async {
      await _pump(tester, page: _Launcher((context) async {
        await showRecordPaymentDialog(context: context, customer: Customer.fromJson(acmeJson));
      }), user: maker, routes: {
        'GET /api/customers/77/pocs': (_) => collectionSeat,
        'GET /api/invoices': (_) => _page(const []),
        'GET /api/promises': (_) => _page(const []),
        'POST /api/payments': (_) => held(
            operation: 'PAYMENT_RECORD',
            entityId: null,
            summary: 'Record payment of ₹60,000.00 for Acme Ltd',
            approverCount: 0),
      }, overrides: [
        _noBadge
      ]);
      await tapShown(tester, find.text('open'));
      await tester.enterText(find.widgetWithText(TextField, 'Amount *'), '60000');
      await tapShown(tester, find.text('Record'));
      expect(
          find.text('Sent for approval: Record payment of ₹60,000.00 for Acme Ltd. No one else '
              'can approve changes in South yet. Ask an administrator.'),
          findsOneWidget);
    });

    testWidgets('a held invoice edit: the fields go back to what the invoice holds now',
        (tester) async {
      final before = {
        'id': 42,
        'invoiceNumber': 'INV-0042',
        'customerId': 77,
        'customerName': 'Acme Ltd',
        'invoiceDate': '2026-06-01',
        'dueDate': '2026-07-01',
        'paymentTerm': 'NET_30',
        'total': 1000.0,
        'paidAmount': 0.0,
        'balance': 1000.0,
        'status': 'UNPAID',
        'notes': 'As agreed',
        'canManage': true,
        'items': [],
      };
      // Someone else changed the invoice while this edit was being made: after the save the
      // fields must show that, not what was loaded before nor what was typed.
      final after = {
        ...before,
        'dueDate': '2026-07-15',
        'paymentTerm': 'CUSTOM',
        'notes': 'Changed by Bob',
      };
      var patched = false;
      final (backend, _) =
          await _pump(tester, page: const InvoiceDetailScreen(id: 42), user: maker, routes: {
        'GET /api/invoices/42': (o) => _answer(patched ? after : before)(o),
        'GET /api/disputes': _answer(_page(const [])),
        'GET /api/promises': _answer(_page(const [])),
        'GET /api/approvals/for-record': _answer(const []),
        'PATCH /api/invoices/42': (_) {
          patched = true;
          return held(
              operation: 'INVOICE_TERMS',
              summary: 'Extend due date of INV-0042 from 2026-07-01 to 2026-07-31');
        },
      }, overrides: [
        _noBadge
      ]);

      final notes = find
          .byWidgetPredicate((w) => w is TextField && w.decoration?.hintText == 'Internal notes');
      final term = find.byType(DropdownButton<PaymentTerm>);
      expect(tester.widget<TextField>(notes).controller!.text, 'As agreed');
      expect(tester.widget<DropdownButton<PaymentTerm>>(term).value, PaymentTerm.NET_30);
      expect(find.text('2026-07-01'), findsOneWidget);

      await tapShown(tester, term);
      await tester.tap(find.text('Net 60').last);
      await tester.pumpAndSettle();
      expect(find.text('2026-07-31'), findsOneWidget);
      await tester.enterText(notes, 'Moved to December');
      await tester.pump();
      expect(find.text('Unsaved changes'), findsOneWidget);
      final loads = backend.sent('GET /api/invoices/42').length;

      await tapShown(tester, find.text('Save changes'));

      expect(tester.takeException(), isNull);
      final patch = backend.sent('PATCH /api/invoices/42').single.data;
      expect(patch['paymentTerm'], 'NET_60');
      expect(patch['notes'], 'Moved to December');
      expect(find.text('Invoice saved'), findsNothing);
      expect(
          find.text('Sent for approval: Extend due date of INV-0042 from 2026-07-01 to 2026-07-31'),
          findsOneWidget);
      // Read again, and the fields show the invoice as it is now: the edit has not taken effect.
      expect(backend.sent('GET /api/invoices/42').length, greaterThan(loads));
      expect(tester.widget<TextField>(notes).controller!.text, 'Changed by Bob');
      expect(tester.widget<DropdownButton<PaymentTerm>>(term).value, PaymentTerm.CUSTOM);
      expect(find.text('2026-07-15'), findsOneWidget);
      expect(find.text('2026-07-31'), findsNothing);
      expect(find.text('Unsaved changes'), findsNothing);
      // The banner is asked again, so the change now shows above the invoice.
      expect(backend.sent('GET /api/approvals/for-record').length, greaterThan(1));
    });

    testWidgets('a held invoice edit answered after the page was left is still told of',
        (tester) async {
      final gate = Completer<void>();
      final (backend, _) =
          await _pump(tester, page: const InvoiceDetailScreen(id: 42), user: maker, routes: {
        'GET /api/invoices/42': _answer({
          'id': 42,
          'invoiceNumber': 'INV-0042',
          'customerId': 77,
          'customerName': 'Acme Ltd',
          'invoiceDate': '2026-06-01',
          'dueDate': '2026-07-01',
          'total': 1000.0,
          'paidAmount': 0.0,
          'balance': 1000.0,
          'status': 'UNPAID',
          'notes': 'As agreed',
          'canManage': true,
          'items': [],
        }),
        'GET /api/disputes': _answer(_page(const [])),
        'GET /api/promises': _answer(_page(const [])),
        'GET /api/approvals/for-record': _answer(const []),
        'PATCH /api/invoices/42': (_) =>
            held(operation: 'INVOICE_TERMS', summary: 'Extend due date of INV-0042'),
      }, overrides: [
        _noBadge
      ]);

      final notes = find
          .byWidgetPredicate((w) => w is TextField && w.decoration?.hintText == 'Internal notes');
      await tester.enterText(notes, 'Moved to December');
      await tester.pump();
      backend.held['PATCH /api/invoices/42'] = gate.future;
      await tapShown(tester, find.text('Save changes'));
      expect(backend.sent('PATCH /api/invoices/42'), hasLength(1));

      // The user leaves (discarding the edit, as far as they know) before the server answers.
      GoRouter.of(tester.element(find.byType(InvoiceDetailScreen))).go('/invoices/7');
      await tester.pumpAndSettle();
      expect(find.byType(InvoiceDetailScreen), findsNothing);

      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('invoice 7'), findsOneWidget);
      // They left thinking the edit was dropped; the server kept it, waiting for someone else.
      expect(find.text('Sent for approval: Extend due date of INV-0042'), findsOneWidget);
      await tester.tap(find.text('View'));
      await tester.pumpAndSettle();
      expect(find.text('approval 41'), findsOneWidget);
    });

    testWidgets('a held cancel from the list says so, and the list is read again', (tester) async {
      final (backend, _) = await _pump(tester,
          page: const InvoicesScreen(query: TableQuery(sort: 'invoiceDate,desc')),
          user: maker,
          routes: {
            'GET /api/table-schemas/invoices': (_) => {'entity': 'invoices', 'columns': const []},
            'GET /api/invoices/summary': (_) => const <String, dynamic>{},
            'GET /api/invoices': (_) => _page([
                  {
                    'id': 1203,
                    'invoiceNumber': 'INV-20260926-0003',
                    'customerId': 77,
                    'customerName': 'Acme Ltd',
                    'invoiceDate': '2026-09-26',
                    'dueDate': '2026-10-26',
                    'total': 120000.0,
                    'paidAmount': 0.0,
                    'balance': 120000.0,
                    'status': 'UNPAID',
                    'canManage': true,
                  },
                ]),
            'POST /api/invoices/1203/cancel': (_) => held(),
          },
          overrides: [
            _noBadge
          ]);
      final lists = backend.sent('GET /api/invoices').length;

      await tapShown(tester, find.byTooltip('Cancel invoice'));
      await tapShown(tester, find.widgetWithText(FilledButton, 'Cancel invoice'));

      expect(backend.sent('POST /api/invoices/1203/cancel'), hasLength(1));
      expect(find.text('Cancellation sent for approval'), findsOneWidget);
      expect(backend.sent('GET /api/invoices').length, greaterThan(lists));
    });

    PaymentPromise promise() => PaymentPromise.fromJson({
          'id': 3,
          'customerId': 77,
          'customerName': 'Acme Ltd',
          'amount': 150000.0,
          'remainingAmount': 150000.0,
          'promisedDate': '2026-10-15',
          'status': 'OPEN',
          'collectionPoc': {'id': 3, 'username': 'cara', 'fullName': 'Cara Checker'},
        });

    for (final edit in [false, true]) {
      testWidgets('a held ${edit ? 'promise edit' : 'new promise'}: the notice, and no email step',
          (tester) async {
        bool? result;
        final (backend, _) = await _pump(tester, page: _Launcher((context) async {
          result = await showPromiseDialog(
              context: context,
              customerId: 77,
              customerName: 'Acme Ltd',
              existing: edit ? promise() : null);
        }), user: maker, routes: {
          'GET /api/customers/77/pocs': (_) => collectionSeat,
          'GET /api/invoices': (_) => _page(const []),
          'POST /api/promises': (_) => held(
              operation: 'PROMISE_CREATE',
              entityId: null,
              summary: 'Promise of ₹1,50,000.00 from Acme Ltd'),
          'PUT /api/promises/3': (_) => held(
              operation: 'PROMISE_UPDATE',
              entityId: 3,
              summary: 'Change promise #3 to ₹2,50,000.00'),
        }, overrides: [
          _noBadge
        ]);

        await tapShown(tester, find.text('open'));
        await tester.enterText(
            find.widgetWithText(TextField, 'Promised amount *'), edit ? '250000' : '150000');
        await tapShown(tester, find.text('Notify through email'));
        await tapShown(tester, find.widgetWithText(FilledButton, edit ? 'Save' : 'Raise promise'));

        expect(tester.takeException(), isNull);
        expect(backend.sent(edit ? 'PUT /api/promises/3' : 'POST /api/promises'), hasLength(1));
        expect(result, isTrue);
        expect(find.byType(AlertDialog), findsNothing);
        expect(
            find.text(edit
                ? 'Sent for approval: Change promise #3 to ₹2,50,000.00'
                : 'Sent for approval: Promise of ₹1,50,000.00 from Acme Ltd'),
            findsOneWidget);
        expect(backend.sent('GET /api/emails/context'), isEmpty);
      });
    }

    testWidgets('a held override closes the dialog as done, with the notice', (tester) async {
      bool? result;
      final (backend, _) = await _pump(tester, page: _Launcher((context) async {
        result = await showOverrideDialog(context: context, promise: promise());
      }), user: maker, routes: {
        'POST /api/promises/3/override': (_) => held(
            operation: 'PROMISE_OVERRIDE',
            entityId: 3,
            summary: 'Mark promise #3 kept with ₹1,50,000.00 unpaid'),
      }, overrides: [
        _noBadge
      ]);

      await tapShown(tester, find.text('open'));
      await tester.enterText(find.widgetWithText(TextField, 'Reason *'), 'Paid by cheque');
      await tapShown(tester, find.widgetWithText(FilledButton, 'Override'));

      expect(backend.sent('POST /api/promises/3/override'), hasLength(1));
      expect(result, isTrue);
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text('Sent for approval: Mark promise #3 kept with ₹1,50,000.00 unpaid'),
          findsOneWidget);
    });

    testWidgets('a held dispute approval stays on the dispute, and emails nobody', (tester) async {
      final dispute = {
        'id': 11,
        'customerId': 77,
        'customerName': 'Acme Ltd',
        'openedByUserId': 9,
        'targetType': 'INVOICE',
        'targetId': 42,
        'targetNumber': 'INV-0042',
        'reason': 'Billed twice',
        'status': 'PENDING',
        'canResolve': true,
        'proposedChangeJson': '{"amount": 0}',
        'createdAt': '2026-06-02T10:00:00Z',
      };
      final (backend, _) =
          await _pump(tester, page: const DisputeDetailScreen(id: 11), user: maker, routes: {
        'GET /api/disputes/11': _answer(dispute),
        'GET /api/approvals/for-record': _answer(const []),
        'POST /api/disputes/11/approve': (_) => held(
            operation: 'DISPUTE_APPROVE',
            entityId: 11,
            summary: 'Approve dispute #11 on INV-0042 (₹1,20,000.00)'),
      }, overrides: [
        _noBadge
      ]);
      final loads = backend.sent('GET /api/disputes/11').length;

      await tapShown(tester, find.text('Notify through email'));
      await tapShown(tester, find.widgetWithText(FilledButton, 'Approve'));

      expect(tester.takeException(), isNull);
      expect(backend.sent('POST /api/disputes/11/approve'), hasLength(1));
      expect(find.text('This approval needs a second approver'), findsOneWidget);
      expect(backend.sent('GET /api/emails/context'), isEmpty);
      // Still on the dispute, read again, with the banner asked again.
      expect(find.text('Dispute #11'), findsOneWidget);
      expect(backend.sent('GET /api/disputes/11').length, greaterThan(loads));
      expect(backend.sent('GET /api/approvals/for-record').length, greaterThan(1));
    });

    group('a bulk cancel with rows held', () {
      Future<FakeBackend> bulk(WidgetTester tester, Map<String, dynamic> result) async {
        final (backend, _) = await _pump(tester,
            page: DataTableScaffold<Map<String, dynamic>>(
              entity: 'invoices',
              path: '/api/invoices',
              query: const TableQuery(),
              onQueryChanged: (_) {},
              parse: (json) => json,
              idOf: (row) => row['id'] as int,
              columns: [
                TableColumnSpec(
                    label: 'Number', cell: (_, row) => Text(row['invoiceNumber'] as String)),
              ],
              bulkActions: const [
                BulkActionSpec(action: 'CANCEL', label: 'Cancel unpaid', icon: Icons.block),
              ],
            ),
            user: maker,
            routes: {
              'GET /api/table-schemas/invoices': (_) => {'entity': 'invoices', 'columns': const []},
              'GET /api/invoices': (_) => _page([
                    {'id': 1203, 'invoiceNumber': 'INV-1203'},
                    {'id': 1204, 'invoiceNumber': 'INV-1204'},
                    {'id': 1205, 'invoiceNumber': 'INV-1205'},
                  ]),
              'POST /api/invoices/bulk': (_) => result,
            },
            overrides: [
              _noBadge
            ]);
        for (final i in [1, 2, 3]) {
          await tester.tap(find.byType(Checkbox).at(i));
          await tester.pumpAndSettle();
        }
        await tapShown(tester, find.text('Cancel unpaid'));
        await tapShown(tester, find.text('Confirm'));
        return backend;
      }

      Map<String, dynamic> result(
              {List<int> succeeded = const [], List<Map<String, dynamic>> failed = const []}) =>
          {
            'action': 'CANCEL',
            'requested': 3,
            'succeeded': succeeded,
            'failed': failed,
            'skipped': const [],
            'truncated': false,
            'limit': 500,
            'pending': [
              {'id': 1203, 'pendingChangeId': 41, 'summary': 'Cancel invoice INV-1203'},
              if (succeeded.isEmpty && failed.isEmpty)
                {'id': 1204, 'pendingChangeId': 42, 'summary': 'Cancel invoice INV-1204'},
            ],
          };

      testWidgets('some done, some held: the snack counts both', (tester) async {
        final backend = await bulk(tester, result(succeeded: [1204, 1205]));
        expect(backend.sent('POST /api/invoices/bulk'), hasLength(1));
        expect(find.text('2 updated, 1 sent for approval'), findsOneWidget);
        expect(find.text('3 records updated'), findsNothing);
        expect(find.byType(AlertDialog), findsNothing);
      });

      testWidgets('all held is still a success, not a failure', (tester) async {
        await bulk(tester, result());
        expect(find.text('0 updated, 2 sent for approval'), findsOneWidget);
        expect(find.byType(AlertDialog), findsNothing);
      });

      testWidgets('with failures, the result lists what awaits approval', (tester) async {
        await bulk(
            tester,
            result(succeeded: [
              1205
            ], failed: [
              {'id': 1204, 'reason': 'Already paid'},
            ]));
        expect(find.text('Cancel unpaid result'), findsOneWidget);
        expect(find.text('1 succeeded, 1 sent for approval, 1 failed, 0 skipped, of 3 requested.'),
            findsOneWidget);
        expect(find.text('Awaiting approval'), findsOneWidget);
        expect(find.text('#1203 → approval #41'), findsOneWidget);
        expect(find.text('#1204 — Already paid'), findsOneWidget);
      });
    });
  });

  test('a brief parses tolerantly, and an unknown status reads as pending (G25)', () {
    final b = PendingChangeBrief.fromJson({'id': 5, 'status': 'SOMETHING_NEW'});
    expect(b.status, PendingStatus.PENDING);
    expect(b.statusAt, isNull);
    expect(b.requestedBy, isNull);
    expect(b.recordText, 'New record');
    final d = PendingChangeDetail.fromJson({'id': 6, 'status': 'FAILED', 'canApprove': true});
    expect(d.status, PendingStatus.FAILED);
    expect(d.canApprove, isTrue);
    expect(d.changes, isEmpty);
  });
}

/// A page with one button that runs [onOpen] — for the flows that start from a dialog.
class _Launcher extends StatelessWidget {
  final Future<void> Function(BuildContext context) onOpen;
  const _Launcher(this.onOpen);

  @override
  Widget build(BuildContext context) =>
      Center(child: TextButton(onPressed: () => onOpen(context), child: const Text('open')));
}
