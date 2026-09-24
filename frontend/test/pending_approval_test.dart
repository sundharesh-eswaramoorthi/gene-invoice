import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/core/unsaved_changes.dart';
import 'package:gene_invoice/features/approvals/approval_providers.dart';
import 'package:gene_invoice/features/approvals/approval_detail_screen.dart';
import 'package:gene_invoice/features/approvals/approvals_screen.dart';
import 'package:gene_invoice/features/approvals/pending_approval_panel.dart';
import 'package:gene_invoice/features/audit/audit_history_panel.dart';
import 'package:gene_invoice/core/table/route_query.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/features/payments/record_payment_dialog.dart';
import 'package:gene_invoice/features/promises/promises_tab.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/customer.dart';
import 'package:gene_invoice/shared/models/pending_change.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/promise.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

const _north = RegionGrant(id: 3, code: 'NORTH', name: 'North Branch', rights: {regionRightManage});

CurrentUser _staff({
  Set<String> privileges = const {
    Privileges.customerView,
    Privileges.invoiceView,
    Privileges.invoiceManage,
    Privileges.paymentView,
    Privileges.paymentManage,
    Privileges.pocView,
    Privileges.approvalView,
  },
  List<RegionGrant> regions = const [_north],
}) =>
    CurrentUser(
      id: 5,
      username: 'mona',
      fullName: 'Mona Maker',
      role: 'CASHIER',
      privileges: privileges,
      customerId: null,
      regions: regions,
    );

/// The 202 body, exactly as ApprovalDtos.Accepted puts it on the wire.
Map<String, dynamic> _accepted({
  int id = 4127,
  String action = 'PAYMENT_RECORD',
  String message =
      '₹12,50,000.00 is above the North Branch approval limit of ₹1,00,000.00. It has been sent '
      'for approval and has not taken effect.',
}) =>
    {
      'outcome': 'PENDING_APPROVAL',
      'pendingChangeId': id,
      'action': action,
      'targetType': 'PAYMENT',
      'targetId': null,
      'customerId': 42,
      'regionId': 3,
      'regionName': 'North Branch',
      'summary': 'Record a payment of ₹12,50,000.00 for Acme Ltd',
      'exposure': 1250000.00,
      'thresholdApplied': 100000.00,
      'requestedAt': '2026-09-22T09:14:03Z',
      'path': '/api/payments',
      'link': '/approvals/$id',
      'message': message,
    };

Map<String, dynamic> _change({
  int id = 4127,
  bool? mine = false,
  bool? canDecide = false,
  String? cannotDecideReason,
  String status = 'PENDING',
  String targetType = 'INVOICE',
  int? targetId = 1,
}) =>
    {
      'id': id,
      'action': 'INVOICE_REPLACE_ITEMS',
      'targetType': targetType,
      'targetId': targetId,
      'customerId': 42,
      'customerName': 'Acme Ltd',
      'regionId': 3,
      'regionName': 'North Branch',
      'summary': 'Re-bill INV-20260901-0003 at ₹1,50,000.00',
      'exposure': 500000.00,
      'thresholdApplied': 100000.00,
      'alwaysChecked': false,
      'status': status,
      'requestedByUserId': 11,
      'requestedByName': 'R Kumar',
      'requestedAt': '2026-09-22T09:14:03Z',
      'decidedByUserId': null,
      'decidedByName': null,
      'decidedAt': null,
      'decisionNotes': null,
      'payloadJson': '{"items":[{"productId":2,"quantity":1,"unitPrice":150000.00}]}',
      'beforeJson': '{"id":7,"total":500000.00,"paidAmount":0.00}',
      'targetVersion': 4,
      'batchId': null,
      'mine': mine,
      'canDecide': canDecide,
      'cannotDecideReason': cannotDecideReason,
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows, {String sort = 'requestedAt,desc'}) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
      'sort': sort,
      'appliedFilters': const <String>[],
      'lockedFilters': const <String>[],
    };

PaymentPromise _promise() => PaymentPromise(
      id: 8,
      customerId: 42,
      customerName: 'Acme Ltd',
      amount: 1250000,
      fulfilledAmount: 0,
      remainingAmount: 1250000,
      promisedDate: DateTime.utc(2026, 10, 1),
      status: PromiseStatus.OPEN,
      statusOverridden: false,
      regionId: 3,
      regionName: 'North Branch',
    );

Map<String, dynamic> _invoice({bool approvalPending = false}) => {
      'id': 1,
      'invoiceNumber': 'INV-20260917-1001',
      'customerId': 42,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-09-17',
      'dueDate': '2026-10-17',
      'total': 500000.0,
      'paidAmount': 0.0,
      'balance': 500000.0,
      'status': 'UNPAID',
      'notes': 'first',
      'items': const <dynamic>[],
      'pocMissing': false,
      'regionId': 3,
      'regionName': 'North Branch',
      'approvalPending': approvalPending,
    };

const _customer = Customer(
  id: 42,
  name: 'Acme Ltd',
  creditBalance: 0,
  regionId: 3,
  regionName: 'North Branch',
);

Future<void> _sized(WidgetTester tester, {Size size = const Size(1400, 1200)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

// --------------------------------------------------------------- the payment dialog

FakeBackend _paymentBackend(Object? Function(RequestOptions) post) => FakeBackend({
      'POST /api/payments': post,
      'GET /api/customers/42/pocs': (o) => [
            {
              'id': 1,
              'pocType': 'COLLECTION',
              'primary': true,
              'user': {'id': 11, 'username': 'colin', 'fullName': 'Colin Collect', 'active': true},
            },
          ],
      'GET /api/invoices': (o) => _page(const []),
      'GET /api/promises': (o) => _page(const []),
      'GET /api/pocs/assignable': (o) => const <Map<String, dynamic>>[],
    });

/// What showRecordPaymentDialog answered, and the router it ran under — so the test can follow
/// the link the amber offers as well as read its words.
typedef _DialogRun = ({bool? result, GoRouter router});

Future<_DialogRun> _openPaymentDialog(WidgetTester tester, FakeBackend backend) async {
  await _sized(tester);
  bool? result;
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, _) => Scaffold(
          body: TextButton(
            onPressed: () async {
              result = await showRecordPaymentDialog(context: context, customer: _customer);
            },
            child: const Text('open'),
          ),
        ),
      ),
      GoRoute(
        path: '/approvals/:id',
        builder: (context, s) =>
            Scaffold(body: Text('the queue, change ${s.pathParameters['id']}')),
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_staff()),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  await tester.enterText(find.widgetWithText(TextField, 'Amount *'), '1250000');
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Record'));
  await tester.pumpAndSettle();
  return (result: result, router: router);
}

void main() {
  // ----------------------------------------------------------------- the one edit that matters

  testWidgets('a save the server held for approval closes the dialog and shows the amber sheet, '
      'not a success snackbar', (tester) async {
    final backend = _paymentBackend((o) => FakeAccepted(_accepted()));
    final run = await _openPaymentDialog(tester, backend);

    // Dio's default validateStatus accepts 200-299, so without the interceptor this 202 is a
    // SUCCESS and the dialog parses the held change as the payment it asked for (B2).
    expect(find.text('Record payment'), findsNothing, reason: 'the dialog must close');
    expect(run.result, isFalse,
        reason: 'nothing was created, so the caller must not be told one was');
    expect(
        find.textContaining('has been sent for approval and has not taken effect'), findsOneWidget);

    // And the link is the server's own, already in this app's route space (B2).
    await tester.tap(find.text('View request'));
    await tester.pumpAndSettle();
    expect(run.router.routerDelegate.currentConfiguration.uri.toString(), '/approvals/4127');
    expect(find.text('the queue, change 4127'), findsOneWidget);
  });

  testWidgets('an ordinary 400 still renders in the red error slot', (tester) async {
    final backend = _paymentBackend(
        (o) => const FakeFailure(400, {'message': 'Enter an amount greater than zero'}));
    await _openPaymentDialog(tester, backend);

    // The 202 rule must not swallow the errors that were already handled: a refusal still keeps
    // the dialog open with the reason beside the fields it is about (B2).
    expect(find.text('Record payment'), findsOneWidget);
    expect(find.text('Enter an amount greater than zero'), findsOneWidget);
    expect(find.textContaining('sent for approval'), findsNothing);
  });

  testWidgets('a 200 save still returns the record and is not treated as held', (tester) async {
    final backend = _paymentBackend((o) => {'id': 77});
    final run = await _openPaymentDialog(tester, backend);

    expect(run.result, isTrue);
    expect(find.text('Record payment'), findsNothing);
    expect(find.textContaining('sent for approval'), findsNothing);
  });

  // ----------------------------------------------------------------- the panel

  late FakeBackend backend;

  Future<void> pumpPanel(
    WidgetTester tester, {
    required Map<String, dynamic> change,
    Set<String> privileges = const {Privileges.invoiceView, Privileges.approvalView},
  }) async {
    await _sized(tester, size: const Size(1200, 1000));
    backend = FakeBackend({'GET /api/approvals': (o) => _page([change])});
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff(privileges: privileges)),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: const Scaffold(
          body: SingleChildScrollView(
            child: PendingApprovalBanner(target: PendingTarget(PendingTargetType.INVOICE, 1)),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('the pending panel renders read-only for a viewer with no approval right',
      (tester) async {
    await pumpPanel(
      tester,
      change: _change(
          mine: false,
          canDecide: false,
          cannotDecideReason: 'You cannot approve changes in this branch'),
    );

    // Everyone who can read the record sees what is waiting on it. Whether they may DECIDE it is
    // the server's answer, per row, and the reason is shown rather than guessed at (B2).
    expect(find.text('Waiting for approval'), findsOneWidget);
    expect(find.text('Re-bill INV-20260901-0003 at ₹1,50,000.00'), findsOneWidget);

    // The queue is an ordinary table endpoint, so the read has to obey the table contract:
    // TableQuery.parse answers 400 for any size outside [10, 20, 50], and these three filters
    // are how a detail screen asks what is waiting on the record it is showing (B2).
    final asked = backend.sent('GET /api/approvals').single;
    expect(const [10, 20, 50], contains(asked.queryParameters['size']));
    expect(asked.queryParameters['filter'], [
      'targetType:eq:INVOICE',
      'targetId:eq:1',
      'status:eq:PENDING',
    ]);
    expect(find.text('You cannot approve changes in this branch'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Approve'), findsNothing);
    expect(find.widgetWithText(OutlinedButton, 'Reject'), findsNothing);
    expect(find.widgetWithText(TextButton, 'Withdraw'), findsNothing);
  });

  testWidgets('the approve and reject buttons appear only when the server says canDecide',
      (tester) async {
    await pumpPanel(tester, change: _change(canDecide: true));
    expect(find.widgetWithText(FilledButton, 'Approve'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, 'Reject'), findsOneWidget);

    await pumpPanel(tester, change: _change(canDecide: false, cannotDecideReason: 'no'));
    expect(find.widgetWithText(FilledButton, 'Approve'), findsNothing);
    expect(find.widgetWithText(OutlinedButton, 'Reject'), findsNothing);

    // Null is "the server was not asked", not "yes": a decision must never be offered on the
    // strength of a missing answer (B2).
    await pumpPanel(tester, change: _change(canDecide: null, mine: null));
    expect(find.widgetWithText(FilledButton, 'Approve'), findsNothing);
  });

  testWidgets('the withdraw button appears only on a change the viewer raised', (tester) async {
    // Withdrawing is the maker's own right and is not an approval, so it follows `mine` and not
    // `canDecide` — which is false for a maker by design (B2).
    await pumpPanel(
        tester,
        change: _change(
            mine: true,
            canDecide: false,
            cannotDecideReason: 'You raised this change, so somebody else has to decide it'));
    expect(find.widgetWithText(TextButton, 'Withdraw'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Approve'), findsNothing);

    await pumpPanel(tester, change: _change(mine: false, canDecide: true));
    expect(find.widgetWithText(TextButton, 'Withdraw'), findsNothing);

    // A change already decided is nobody's to withdraw.
    await pumpPanel(tester, change: _change(mine: true, canDecide: false, status: 'APPROVED'));
    expect(find.widgetWithText(TextButton, 'Withdraw'), findsNothing);
  });

  // ------------------------------------------------- the two calls that carry no record back

  testWidgets('a held customer delete never says deleted and never leaves the page',
      (tester) async {
    await _sized(tester);
    final backend = FakeBackend({
      'GET /api/customers/42': (o) => {
            'id': 42,
            'name': 'Acme Ltd',
            'username': 'acme',
            'creditBalance': 0,
            'outstanding': 0,
            'regionId': 3,
            'regionName': 'North Branch',
            'approvalPending': false,
          },
      'DELETE /api/customers/42': (o) => FakeAccepted(_accepted(
            id: 4130,
            action: 'CUSTOMER_DELETE',
            message: 'Deleting Acme Ltd has been sent for approval and has not taken effect.',
          )),
      'GET /api/approvals': (o) => _page(const []),
    });
    final router = GoRouter(
      initialLocation: '/customers/42',
      routes: [
        GoRoute(
            path: '/customers',
            builder: (_, __) => const Scaffold(body: Text('the customer list'))),
        GoRoute(
            path: '/customers/:id',
            builder: (_, s) => Scaffold(
                body: CustomerDetailScreen(id: int.parse(s.pathParameters['id']!)))),
        GoRoute(
            path: '/approvals/:id',
            builder: (_, s) =>
                Scaffold(body: Text('the queue, change ${s.pathParameters['id']}'))),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff(privileges: const {
          Privileges.customerView,
          Privileges.customerManage,
          Privileges.approvalView,
        })),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(OutlinedButton, 'Delete customer'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Delete customer'));
    await tester.pumpAndSettle();

    // CUSTOMER_DELETE is alwaysChecked, so this answer comes back on EVERY delete at any
    // threshold in any region, on a deployment that never configured maker-checker at all.
    // Dio's default validateStatus accepts 200-299, so without the interceptor the success
    // arm ran: "Acme Ltd deleted" and go('/customers'), for a customer that still exists (B2).
    expect(find.text('Acme Ltd deleted'), findsNothing);
    expect(find.text('the customer list'), findsNothing);
    expect(router.routerDelegate.currentConfiguration.uri.toString(), '/customers/42');
    // The amber sheet and its link to the queue, not a red error slot: the ask was accepted (B2).
    expect(find.widgetWithText(SnackBar, 'Deleting Acme Ltd has been sent for approval and has '
        'not taken effect.'), findsOneWidget);
    expect(find.text('View request'), findsOneWidget);
  });

  testWidgets('a held invoice cancel says so rather than quietly reloading the row',
      (tester) async {
    await _sized(tester);
    final backend = FakeBackend({
      'GET /api/invoices': (o) => _page([_invoice()]),
      'GET /api/invoices/summary': (o) => <String, dynamic>{},
      'GET /api/table-schemas/invoices': (o) =>
          {'entity': 'invoices', 'columns': const <dynamic>[]},
      'POST /api/invoices/1/cancel': (o) =>
          FakeAccepted(_accepted(id: 4131, action: 'INVOICE_CANCEL')),
    });
    final router = GoRouter(
      initialLocation: '/invoices',
      routes: [
        GoRoute(
          path: '/invoices',
          builder: (_, s) => InvoicesScreen(
              query: RouteQuery.read(s, defaultSize: 20, defaultSort: 'invoiceDate,desc')),
        ),
        GoRoute(
            path: '/approvals/:id',
            builder: (_, s) =>
                Scaffold(body: Text('the queue, change ${s.pathParameters['id']}'))),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Cancel invoice'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Cancel invoice'));
    await tester.pumpAndSettle();

    // A bare await on a call that reads no response body is the shape an interceptor author is
    // most likely to miss: the table was invalidated, the invoice re-rendered still ISSUED, and
    // the maker was told nothing at all (B2).
    expect(find.textContaining('has been sent for approval'), findsOneWidget);
    // Amber with the link to the change, not a red error snackbar (B2).
    expect(find.text('View request'), findsOneWidget);
  });

  testWidgets('a held promise cancel tells the caller nothing changed', (tester) async {
    await _sized(tester);
    final backend = FakeBackend({
      'POST /api/promises/8/cancel': (o) =>
          FakeAccepted(_accepted(id: 4132, action: 'PROMISE_CANCEL')),
    });
    bool? answered;
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(
          path: '/',
          builder: (_, __) => Scaffold(
            body: Consumer(
              builder: (context, ref, _) => TextButton(
                onPressed: () async {
                  answered = await cancelPromise(context, ref, _promise());
                },
                child: const Text('cancel it'),
              ),
            ),
          ),
        ),
        GoRoute(
            path: '/approvals/:id',
            builder: (_, s) =>
                Scaffold(body: Text('the queue, change ${s.pathParameters['id']}'))),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));

    await tester.tap(find.text('cancel it'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Cancel promise'));
    await tester.pumpAndSettle();

    // true would tell every caller to refresh as though the promise had been cancelled; it is
    // still OPEN and somebody has to approve the cancellation first (B2).
    expect(answered, isFalse);
    // The amber sheet with its link to the change, and not the plain one apiErrorMessage
    // would have put there for an ordinary failure (B2).
    expect(find.textContaining('has been sent for approval'), findsOneWidget);
    expect(find.text('View request'), findsOneWidget);
  });

  // ----------------------------------------------------------------- the in-place form

  testWidgets('an in-place form that was held stops challenging on the way out', (tester) async {
    await _sized(tester);
    var saves = 0;
    final backend = FakeBackend({
      'GET /api/invoices/1': (o) => _invoice(approvalPending: saves > 0),
      'PATCH /api/invoices/1': (o) {
        saves++;
        return FakeAccepted(_accepted(id: 900, action: 'INVOICE_REPLACE_ITEMS'));
      },
      'GET /api/approvals': (o) => _page([_change(id: 900)]),
      'GET /api/pocs/assignable': (o) => const <Map<String, dynamic>>[],
    });
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: const Scaffold(body: InvoiceDetailScreen(id: 1)),
      ),
    ));
    await tester.pumpAndSettle();

    final unsaved = ProviderScope.containerOf(tester.element(find.byType(InvoiceDetailScreen)))
        .read(unsavedChangesProvider);

    await tester.enterText(find.widgetWithText(TextField, 'Internal notes'), 'changed');
    await tester.pumpAndSettle();

    // Dirty: leaving now must challenge.
    final blocked = unsaved.mayLeave();
    await tester.pumpAndSettle();
    expect(find.text('Discard unsaved changes?'), findsOneWidget);
    await tester.tap(find.text('Keep editing'));
    await tester.pumpAndSettle();
    expect(await blocked, isFalse);

    await tester.tap(find.widgetWithText(FilledButton, 'Save changes'));
    await tester.pumpAndSettle();

    // The form is no longer holding the change, the server is. Challenging on the way out would
    // ask the maker to discard edits that nobody is holding any more (B2).
    expect(find.textContaining('has been sent for approval'), findsOneWidget);
    expect(await unsaved.mayLeave(), isTrue);
    await tester.pumpAndSettle();
    expect(find.text('Discard unsaved changes?'), findsNothing);

    // And the record now says so, without a reload.
    expect(find.text('Change pending approval'), findsOneWidget);
    expect(find.text('Waiting for approval'), findsOneWidget);
  });

  // ----------------------------------------------------------------- the bulk sheet

  testWidgets('the bulk result sheet shows the rows sent for approval separately from the '
      'skipped ones', (tester) async {
    await _sized(tester);
    Object? bulkAnswer;
    final backend = FakeBackend({
      'GET /api/invoices': (o) => _page([_invoice()], sort: 'invoiceDate,desc'),
      'GET /api/invoices/summary': (o) => <String, dynamic>{},
      'GET /api/table-schemas/invoices': (o) => {'entity': 'invoices', 'columns': <dynamic>[]},
      'POST /api/invoices/bulk': (o) => bulkAnswer,
      'GET /api/pocs/assignable': (o) => const <Map<String, dynamic>>[],
    });
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: InvoicesScreen(
            query: TableQuery.fromRoute(const {},
                defaultSize: 20, defaultSort: 'invoiceDate,desc')),
      ),
    ));
    await tester.pumpAndSettle();

    Future<void> runCancel() async {
      await tester.tap(find.byType(Checkbox).last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, 'Cancel unpaid'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Confirm'));
      await tester.pumpAndSettle();
    }

    // Every row held and nothing else: before the pending bucket this short-circuited to
    // "0 records updated" with no sheet at all, and an operator who bulk-cancelled three large
    // invoices was never told that three changes are now waiting (B2).
    bulkAnswer = {
      'requested': 1,
      'succeeded': const <dynamic>[],
      'failed': const <dynamic>[],
      'skipped': const <dynamic>[],
      'pending': [
        {'id': 1, 'reason': 'Sent for approval as change #41'},
      ],
      'truncated': false,
      'limit': 500,
      'pendingLimitReached': false,
    };
    await runCancel();
    expect(find.text('0 records updated'), findsNothing);
    expect(find.text('Sent for approval'), findsOneWidget);
    expect(find.text('#1 — Sent for approval as change #41'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Close'));
    await tester.pumpAndSettle();

    // And a held row is its own bucket: "skipped" already means "did not qualify" (TBL-05, B2).
    bulkAnswer = {
      'requested': 2,
      'succeeded': const <dynamic>[],
      'failed': const <dynamic>[],
      'skipped': [
        {'id': 9, 'reason': 'Already cancelled'},
      ],
      'pending': [
        {'id': 1, 'reason': 'Sent for approval as change #41'},
      ],
      'truncated': false,
      'limit': 500,
      'pendingLimitReached': true,
    };
    await runCancel();
    expect(find.textContaining('1 sent for approval'), findsOneWidget);
    expect(find.text('Sent for approval'), findsOneWidget);
    expect(find.text('Skipped'), findsOneWidget);
    expect(find.text('#9 — Already cancelled'), findsOneWidget);
    expect(find.textContaining('as many approvals as it may at once'), findsOneWidget);
  });

  // ----------------------------------------------------------------- the nav entry

  testWidgets('the approvals nav entry is hidden without approval view', (tester) async {
    Future<void> pumpShell(CurrentUser user) async {
      await _sized(tester);
      final backend = FakeBackend({
        'GET /api/approvals/summary': (o) => {'awaitingMyDecisionCount': 3},
      });
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(user),
        ],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: const AppShell(path: '/', child: SizedBox.shrink()),
        ),
      ));
      await tester.pumpAndSettle();
      // The rail keeps its own pinned state across a re-pump, so the second shell is already
      // extended and the button is the other one.
      final pin = find.byTooltip('Keep the sidebar open');
      if (pin.evaluate().isNotEmpty) {
        await tester.tap(pin);
        await tester.pumpAndSettle();
      }
    }

    await pumpShell(_staff());
    // The sidebar's own rule, which the router guards the same route with, so a screen the nav
    // hides cannot be reached by hand-editing the URL either (UI-10).
    expect(find.text('Approvals'), findsOneWidget);
    expect(navPrivilegesFor('/approvals'), const [Privileges.approvalView]);
    // The badge is the queue's own awaitingMyDecisionCount: "waiting" and "waiting for me" are
    // two different questions and only the server can answer the second (B2).
    expect(find.text('3'), findsOneWidget);

    await pumpShell(_staff(privileges: const {Privileges.invoiceView}));
    expect(find.text('Approvals'), findsNothing);
    expect(find.text('3'), findsNothing);
  });

  // ----------------------------------------------------------------- the queue

  testWidgets('the queue lists what is waiting and opens one change on its own page',
      (tester) async {
    await _sized(tester);
    final backend = FakeBackend({
      'GET /api/approvals': (o) => _page([_change(canDecide: true)]),
      'GET /api/approvals/summary': (o) => {
            'count': 1,
            'pendingCount': 1,
            'pendingExposure': 500000.00,
            'mineCount': 0,
            'awaitingMyDecisionCount': 1,
            'approvedCount': 0,
            'rejectedCount': 0,
            'withdrawnCount': 0,
            'supersededCount': 0,
          },
      'GET /api/approvals/4127': (o) => _change(canDecide: true),
      'GET /api/table-schemas/approvals': (o) => {'entity': 'approvals', 'columns': <dynamic>[]},
      'GET /api/audit': (o) => const <Map<String, dynamic>>[],
    });
    final router = GoRouter(
      initialLocation: '/approvals',
      routes: [
        GoRoute(
          path: '/approvals',
          builder: (c, s) => ApprovalsScreen(
              query: TableQuery.fromRoute(const {},
                  defaultSize: 20, defaultSort: 'requestedAt,desc')),
        ),
        GoRoute(
          path: '/approvals/:id',
          builder: (c, s) =>
              ApprovalDetailScreen(id: int.parse(s.pathParameters['id']!)),
        ),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
    ));
    await tester.pumpAndSettle();

    // The generic table against the published schema and nothing bespoke (B2, B1).
    expect(find.text('Replace invoice lines'), findsOneWidget);
    expect(find.text('Invoice #1'), findsOneWidget);
    expect(find.text('Waiting for me'), findsOneWidget);
    expect(find.text('You can decide'), findsOneWidget);

    await tester.tap(find.byTooltip('Open'));
    await tester.pumpAndSettle();
    expect(router.routerDelegate.currentConfiguration.uri.toString(), '/approvals/4127');
    expect(find.text('Change #4127'), findsOneWidget);
    // Before and proposed, side by side, so a checker sees what actually changes (B2).
    expect(find.textContaining('"paidAmount": 0'), findsOneWidget);
    expect(find.textContaining('"unitPrice": 150000'), findsOneWidget);
  });

  testWidgets('approving from the panel asks for a note, posts the decision and refreshes',
      (tester) async {
    await _sized(tester, size: const Size(1200, 1000));
    var approvals = 0;
    final backend = FakeBackend({
      'GET /api/approvals': (o) =>
          _page(approvals == 0 ? [_change(canDecide: true)] : const []),
      'POST /api/approvals/4127/approve': (o) {
        approvals++;
        return {'change': _change(status: 'APPROVED', canDecide: false), 'result': null};
      },
    });
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: const Scaffold(
          body: SingleChildScrollView(
            child: PendingApprovalBanner(target: PendingTarget(PendingTargetType.INVOICE, 1)),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Approve'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'looked at the lines');
    await tester.tap(find.widgetWithText(FilledButton, 'Approve').last);
    await tester.pumpAndSettle();

    final sent = backend.sent('POST /api/approvals/4127/approve');
    expect(sent, hasLength(1));
    expect((sent.single.data as Map)['decisionNotes'], 'looked at the lines');
    // Nothing is waiting on the record any more, so the panel goes (B2).
    expect(find.text('Waiting for approval'), findsNothing);
  });

  testWidgets('the audit timeline says which approval a row came from', (tester) async {
    await _sized(tester, size: const Size(1000, 900));
    final backend = FakeBackend({
      'GET /api/audit': (o) => [
            {
              'id': 1,
              'entityType': 'INVOICE',
              'entityId': 1,
              'action': 'CHANGE_APPROVED',
              'beforeJson': null,
              'afterJson': null,
              'changedByUserId': 12,
              'changedByUsername': 'chandra',
              'disputeId': null,
              'pendingChangeId': 4127,
              'reason': null,
              'createdAt': '2026-09-22T10:00:00Z',
            },
            {
              'id': 2,
              'entityType': 'INVOICE',
              'entityId': 1,
              'action': 'INVOICE_UPDATED',
              'beforeJson': null,
              'afterJson': null,
              'changedByUserId': 12,
              'changedByUsername': 'chandra',
              'disputeId': null,
              'reason': null,
              'createdAt': '2026-09-22T09:00:00Z',
            },
          ],
    });
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff()),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: const Scaffold(
          body: SingleChildScrollView(
            child: AuditHistoryPanel(entityType: 'INVOICE', entityId: 1),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    // The twin of "via dispute #N". A null pending_change_id is what "nobody had to approve
    // this" looks like, and it must not render a line at all (B2).
    expect(find.textContaining('via approval #4127'), findsOneWidget);
    expect(find.textContaining('via approval #null'), findsNothing);
  });

  testWidgets('a branch limit change asks the audit log for no timeline it cannot answer',
      (tester) async {
    await _sized(tester);
    final threshold = {
      ..._change(targetType: 'REGION', targetId: 3, canDecide: true),
      'action': 'APPROVAL_THRESHOLD_SET',
      'alwaysChecked': true,
      'exposure': null,
      'thresholdApplied': null,
      'summary': 'Set the North Branch approval limit to ₹2,00,000.00',
    };
    final backend = FakeBackend({'GET /api/approvals/4127': (o) => threshold});
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(backend.dio),
        currentUserProvider.overrideWithValue(_staff(privileges: const {
          Privileges.approvalView,
          Privileges.auditView,
        })),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: const Scaffold(body: ApprovalDetailScreen(id: 4127)),
      ),
    ));
    await tester.pumpAndSettle();

    // GET /api/audit answers 400 for any entityType outside its SUPPORTED set, and REGION and
    // DISPUTE are both outside it. Asking would render "History unavailable: Unknown entity
    // type" on a page that is otherwise perfectly fine (B2).
    expect(backend.sent('GET /api/audit'), isEmpty);
    expect(find.text('Change #4127'), findsOneWidget);
    expect(find.text('Always checked, whatever the amount'), findsOneWidget);
    expect(find.textContaining('no history to show for a branch limit'), findsOneWidget);
  });
}
