import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/dashboard/dashboard_providers.dart';
import 'package:gene_invoice/features/dashboard/dashboard_screen.dart';
import 'package:gene_invoice/features/poc/poc_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/promise.dart';
import 'package:go_router/go_router.dart';

CurrentUser _user(String role, Set<String> privileges, {int? customerId}) => CurrentUser(
      id: 1,
      username: role.toLowerCase(),
      fullName: 'Test $role',
      role: role,
      privileges: privileges,
      customerId: customerId,
    );

const _everything = {
  Privileges.invoiceView,
  Privileges.paymentView,
  Privileges.promiseView,
  Privileges.customerView,
  Privileges.pocView,
};

MonthlySeries _series(Coverage coverage) => MonthlySeries(coverage: coverage, months: [
      for (var i = 0; i < 12; i++)
        MonthPoint(month: DateTime.utc(2025, 10 + i), amount: 1000.0 * (i + 1), count: i + 1),
    ]);

CustomerRanking _ranking(Coverage coverage, int id, String name) => CustomerRanking(
      coverage: coverage,
      customers: [
        RankedCustomer(
            customerId: id,
            customerName: name,
            amount: 2500,
            count: 3,
            date: DateTime.utc(2026, 6)),
      ],
    );

PaymentPromise _promiseDueIn(int days) {
  final due = todayUtc().add(Duration(days: days));
  return PaymentPromise(
    id: 42,
    customerId: 7,
    customerName: 'Initech',
    amount: 500,
    fulfilledAmount: 100,
    remainingAmount: 400,
    promisedDate: DateTime(due.year, due.month, due.day),
    status: PromiseStatus.PARTIALLY_KEPT,
    statusOverridden: false,
    collectionPoc: const PocUser(id: 3, username: 'cleo', fullName: 'Cleo Collections'),
  );
}

Future<void> _pump(
  WidgetTester tester,
  CurrentUser user, {
  Coverage coverage = Coverage.all,
  Size size = const Size(1366, 3200),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (_, __) => const Scaffold(body: DashboardScreen())),
    GoRoute(path: '/customers/:id', builder: (_, s) => Text('customer ${s.pathParameters['id']}')),
    GoRoute(path: '/promises/:id', builder: (_, s) => Text('promise ${s.pathParameters['id']}')),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      currentUserProvider.overrideWithValue(user),
      billedByMonthProvider.overrideWith((ref) async => _series(coverage)),
      collectedByMonthProvider.overrideWith((ref) async => _series(coverage)),
      outstandingByAgeProvider.overrideWith((ref) async => OutstandingByAge(
            coverage: coverage,
            buckets: const [
              AgeBucket(label: '0–30 days', fromDays: 0, toDays: 30, amount: 1200, count: 2),
              AgeBucket(label: 'Over 90 days', fromDays: 91, toDays: null, amount: 800, count: 1),
            ],
          )),
      topOutstandingProvider.overrideWith((ref) async => _ranking(coverage, 7, 'Acme Ltd')),
      topPayingProvider.overrideWith((ref) async => _ranking(coverage, 8, 'Globex Corp')),
      invoiceSummaryProvider.overrideWith((ref) async => {
            'outstanding': 2000,
            'unpaidCount': 3,
            'partiallyPaidCount': 2,
            'fullyPaidCount': 5,
            'cancelledCount': 1,
          }),
      promiseSummaryProvider.overrideWith((ref) async => {
            'openCount': 1,
            'openAmount': 500,
            'partiallyKeptCount': 1,
            'partiallyKeptAmount': 500,
            'keptCount': 2,
            'brokenCount': 1,
            'brokenAmount': 300,
          }),
      upcomingPromisesProvider
          .overrideWith((ref) async => UpcomingPromises(promises: [_promiseDueIn(3)], book: false)),
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('staff who may see everything get every card, with no "Your book" label',
      (tester) async {
    await _pump(tester, _user('ADMIN', _everything));

    for (final title in [
      'Billed and collected by month',
      'Invoices by status',
      'Outstanding by age',
      'Promises by status',
      'Top customers by outstanding',
      'Top paying customers',
      'Upcoming promises',
    ]) {
      expect(find.text(title), findsOneWidget, reason: title);
    }
    expect(find.text('Collection POC'), findsOneWidget);
    expect(find.text('Cleo Collections'), findsOneWidget);
    expect(find.text('in 3 days'), findsOneWidget);
    expect(find.text('Your book'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a POC limited to their book sees those cards labelled', (tester) async {
    await _pump(tester, _user('SALES_POC', _everything), coverage: Coverage.book);

    expect(find.text('Your book'), findsWidgets);
    expect(find.text('Payments count only what was paid against your invoices'), findsOneWidget);
  });

  testWidgets('a customer login gets its own figures, but no rankings and no POC names',
      (tester) async {
    await _pump(
      tester,
      _user(
          'CUSTOMER',
          {
            Privileges.invoiceView,
            Privileges.paymentView,
            Privileges.promiseView,
            Privileges.customerView,
          },
          customerId: 1),
      coverage: Coverage.own,
    );

    expect(find.text('Billed and paid by month'), findsOneWidget);
    // The key figure and the chart legend both say it; neither says "Collected".
    expect(find.text('Paid'), findsNWidgets(2));
    expect(find.text('Collected'), findsNothing);
    expect(find.text('Upcoming promises'), findsOneWidget);
    expect(find.text('Top customers by outstanding'), findsNothing);
    expect(find.text('Top paying customers'), findsNothing);
    expect(find.text('Collection POC'), findsNothing);
    expect(find.text('Cleo Collections'), findsNothing);
    // Every promise is the customer's own, so the table does not repeat their name.
    expect(find.text('Customer'), findsNothing);
    expect(find.text('Initech'), findsNothing);
  });

  testWidgets('cards follow privileges, not role names', (tester) async {
    await _pump(tester, _user('SALES_POC', {Privileges.invoiceView}));

    expect(find.text('Billed by month'), findsOneWidget);
    expect(find.text('Top customers by outstanding'), findsOneWidget);
    expect(find.text('Top paying customers'), findsNothing);
    expect(find.text('Collected'), findsNothing);
    expect(find.text('Promises by status'), findsNothing);
    expect(find.text('Upcoming promises'), findsNothing);
    expect(find.text('Open promises'), findsNothing);
  });

  testWidgets('a ranking row opens the customer only for users who may see customers',
      (tester) async {
    await _pump(tester, _user('ADMIN', _everything));
    await tester.tap(find.text('Globex Corp'));
    await tester.pumpAndSettle();
    expect(find.text('customer 8'), findsOneWidget);
  });

  testWidgets('without CUSTOMER_VIEW a ranking row stays put', (tester) async {
    await _pump(tester, _user('VIEWER', _everything.difference({Privileges.customerView})));
    await tester.tap(find.text('Globex Corp'));
    await tester.pumpAndSettle();
    expect(find.text('customer 8'), findsNothing);
    expect(find.text('All customers'), findsNothing);
  });

  testWidgets('on a phone the cards stack and the tables drop columns instead of overflowing',
      (tester) async {
    await _pump(tester, _user('ADMIN', _everything), size: const Size(400, 6000));

    expect(tester.takeException(), isNull);
    expect(find.text('Upcoming promises'), findsOneWidget);
    expect(find.text('Initech'), findsOneWidget);
    expect(find.text('Collection POC'), findsNothing);
    expect(find.text('Oldest'), findsNothing);
  });
}
