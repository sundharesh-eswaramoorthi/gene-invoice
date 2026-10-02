import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/route_query.dart';
import '../../shared/models/promise.dart';

/// Whose figures a card counts. [mixed]: the whole of some of the regions asked about, but only my
/// own book in others (B1).
enum Coverage { all, book, own, mixed }

Coverage parseCoverage(Object? wire) => switch (wire) {
      'BOOK' => Coverage.book,
      'OWN' => Coverage.own,
      'MIXED' => Coverage.mixed,
      _ => Coverage.all,
    };

/// Which regions a figure answered for, where of those only my book counts, and the day it is as
/// of (null when live) — the dashboard's counterpart of a list's `regionIds`, `bookRegionIds` and
/// `asOf` (§6.9).
class DashboardScope {
  final List<int> regionIds;
  final List<int> bookRegionIds;
  final String? asOf;

  const DashboardScope({this.regionIds = const [], this.bookRegionIds = const [], this.asOf});

  static const none = DashboardScope();

  factory DashboardScope.fromJson(Object? json) {
    if (json is! Map) return none;
    List<int> ids(Object? raw) =>
        ((raw as List?) ?? const []).whereType<num>().map((e) => e.toInt()).toList();
    return DashboardScope(
      regionIds: ids(json['regionIds']),
      bookRegionIds: ids(json['bookRegionIds']),
      asOf: json['asOf'] as String?,
    );
  }
}

double _money(Object? v) => (v as num? ?? 0).toDouble();

Map<String, dynamic> _map(Object? data) => (data as Map).cast<String, dynamic>();

class MonthPoint {
  final DateTime month;
  final double amount;
  final int count;

  const MonthPoint({required this.month, required this.amount, required this.count});

  factory MonthPoint.fromJson(Map<String, dynamic> json) => MonthPoint(
        month: DateTime.parse('${json['month']}-01'),
        amount: _money(json['amount']),
        count: (json['count'] as num? ?? 0).toInt(),
      );
}

class MonthlySeries {
  final Coverage coverage;
  final DashboardScope scope;

  final List<MonthPoint> months;

  const MonthlySeries(
      {required this.coverage, required this.months, this.scope = DashboardScope.none});

  double get total => months.fold(0, (sum, m) => sum + m.amount);

  factory MonthlySeries.fromJson(Map<String, dynamic> json) => MonthlySeries(
        coverage: parseCoverage(json['coverage']),
        scope: DashboardScope.fromJson(json['scope']),
        months: ((json['months'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(MonthPoint.fromJson)
            .toList(),
      );
}

/// One band of outstanding money by **days past due** (D4): the label is the server's, and the
/// bounds say how late the invoices in it are. Both ends are open somewhere — [fromDays] is null
/// on "not yet due", [toDays] on the last band.
class AgeBucket {
  final String label;

  final int? fromDays;

  final int? toDays;
  final double amount;
  final int count;

  final DateTime? dueDateFrom;
  final DateTime? dueDateTo;

  const AgeBucket({
    required this.label,
    required this.fromDays,
    required this.toDays,
    required this.amount,
    required this.count,
    this.dueDateFrom,
    this.dueDateTo,
  });

  factory AgeBucket.fromJson(Map<String, dynamic> json) => AgeBucket(
        label: json['label'] as String? ?? '',
        fromDays: (json['fromDays'] as num?)?.toInt(),
        toDays: (json['toDays'] as num?)?.toInt(),
        amount: _money(json['amount']),
        count: (json['count'] as num? ?? 0).toInt(),
        dueDateFrom: _day(json['dueDateFrom']),
        dueDateTo: _day(json['dueDateTo']),
      );
}

DateTime? _day(Object? value) =>
    value == null ? null : DateTime.tryParse('${value}T00:00:00Z');

class OutstandingByAge {
  final Coverage coverage;
  final DashboardScope scope;
  final List<AgeBucket> buckets;

  const OutstandingByAge(
      {required this.coverage, required this.buckets, this.scope = DashboardScope.none});

  factory OutstandingByAge.fromJson(Map<String, dynamic> json) => OutstandingByAge(
        coverage: parseCoverage(json['coverage']),
        scope: DashboardScope.fromJson(json['scope']),
        buckets: ((json['buckets'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(AgeBucket.fromJson)
            .toList(),
      );
}

class RankedCustomer {
  final int customerId;
  final String customerName;
  final double amount;
  final int count;
  final DateTime? date;

  const RankedCustomer({
    required this.customerId,
    required this.customerName,
    required this.amount,
    required this.count,
    required this.date,
  });
}

class CustomerRanking {
  final Coverage coverage;
  final DashboardScope scope;
  final List<RankedCustomer> customers;

  const CustomerRanking(
      {required this.coverage, required this.customers, this.scope = DashboardScope.none});

  static CustomerRanking _fromJson(
      Map<String, dynamic> json, String amountKey, String countKey, String dateKey) {
    return CustomerRanking(
      coverage: parseCoverage(json['coverage']),
      scope: DashboardScope.fromJson(json['scope']),
      customers: ((json['customers'] as List?) ?? const [])
          .cast<Map<String, dynamic>>()
          .map((c) => RankedCustomer(
                customerId: (c['customerId'] as num).toInt(),
                customerName: c['customerName'] as String? ?? '',
                amount: _money(c[amountKey]),
                count: (c[countKey] as num? ?? 0).toInt(),
                date: c[dateKey] == null ? null : DateTime.parse(c[dateKey] as String),
              ))
          .toList(),
    );
  }

  factory CustomerRanking.outstandingFromJson(Map<String, dynamic> json) =>
      _fromJson(json, 'outstanding', 'openInvoices', 'oldestInvoiceDate');

  factory CustomerRanking.payingFromJson(Map<String, dynamic> json) =>
      _fromJson(json, 'collected', 'payments', 'lastPaidAt');
}

final dashboardMonthsProvider = StateProvider<int>((ref) => 12);

DateTime todayUtc([DateTime? at]) {
  final now = (at ?? DateTime.now()).toUtc();
  return DateTime.utc(now.year, now.month, now.day);
}

/// Where the dashboard reads the time from; tests move it past midnight.
final dashboardClockProvider = Provider<DateTime Function()>((ref) => DateTime.now);

/// The day the dashboard's "today" is: the date being viewed, or today (UTC). It drives the
/// ageing links, "days" counts and upcoming promises, as the server's today does its figures (D9).
/// Worked out again on every refresh and every visit, so a session that runs past midnight
/// moves on with the server.
final dashboardTodayProvider = Provider.autoDispose<DateTime>((ref) {
  final asOf = ref.watch(viewAsOfProvider);
  return asOf == null
      ? todayUtc(ref.watch(dashboardClockProvider)())
      : DateTime.utc(asOf.year, asOf.month, asOf.day);
});

/// Every figure on the dashboard answers for the regions asked about, as of the day viewed (§6.9):
/// each asks again when either changes, and refuses an answer that is not the day it asked for.
Future<Map<String, dynamic>> _dashboardGet(Ref ref, String path,
    [Map<String, dynamic>? params]) async {
  final view = ref.watch(viewScopeProvider);
  final res = await ref.watch(dioProvider).get(path, queryParameters: params);
  checkAsOf(res, view.asOf);
  return _map(res.data);
}

final billedByMonthProvider = FutureProvider.autoDispose<MonthlySeries>((ref) async {
  final months = ref.watch(dashboardMonthsProvider);
  return MonthlySeries.fromJson(
      await _dashboardGet(ref, '/api/dashboard/billed-by-month', {'months': months}));
});

final collectedByMonthProvider = FutureProvider.autoDispose<MonthlySeries>((ref) async {
  final months = ref.watch(dashboardMonthsProvider);
  return MonthlySeries.fromJson(
      await _dashboardGet(ref, '/api/dashboard/collected-by-month', {'months': months}));
});

final outstandingByAgeProvider = FutureProvider.autoDispose<OutstandingByAge>((ref) async =>
    OutstandingByAge.fromJson(await _dashboardGet(ref, '/api/dashboard/outstanding-by-age')));

final topOutstandingProvider = FutureProvider.autoDispose<CustomerRanking>((ref) async =>
    CustomerRanking.outstandingFromJson(
        await _dashboardGet(ref, '/api/dashboard/top-outstanding-customers')));

final topPayingProvider = FutureProvider.autoDispose<CustomerRanking>((ref) async {
  final months = ref.watch(dashboardMonthsProvider);
  return CustomerRanking.payingFromJson(
      await _dashboardGet(ref, '/api/dashboard/top-paying-customers', {'months': months}));
});

final invoiceSummaryProvider = FutureProvider.autoDispose<Map<String, dynamic>>(
    (ref) => _dashboardGet(ref, '/api/invoices/summary'));

final promiseSummaryProvider = FutureProvider.autoDispose<Map<String, dynamic>>(
    (ref) => _dashboardGet(ref, '/api/promises/summary'));

class UpcomingPromises {
  final List<PaymentPromise> promises;
  final bool book;

  /// Regions where only my own book is listed while others are listed whole (B1); empty when
  /// [book] already says the whole list is my book.
  final List<int> bookRegionIds;

  const UpcomingPromises(
      {required this.promises, required this.book, this.bookRegionIds = const []});

  static String listLink(DateTime today) => RouteQuery.location('/promises', {
        'sort': 'promisedDate,asc',
        'f': _upcomingFilters(today),
      });
}

List<String> _upcomingFilters(DateTime today) => [
      'status:in:OPEN,PARTIALLY_KEPT',
      'promisedDate:gte:${DateFormat('yyyy-MM-dd').format(today)}',
    ];

final upcomingPromisesProvider = FutureProvider.autoDispose<UpcomingPromises>((ref) async {
  final data = await _dashboardGet(ref, '/api/promises', {
    'size': 10,
    'sort': 'promisedDate,asc',
    'filter': _upcomingFilters(ref.watch(dashboardTodayProvider)),
  });
  final book = ((data['lockedFilters'] as List?) ?? const []).isNotEmpty;
  return UpcomingPromises(
    promises: ((data['content'] as List?) ?? const [])
        .cast<Map<String, dynamic>>()
        .map(PaymentPromise.fromJson)
        .toList(),
    // lockedFilters still means the whole list is my book (R14).
    book: book,
    bookRegionIds: book
        ? const []
        : ((data['bookRegionIds'] as List?) ?? const [])
            .whereType<num>()
            .map((e) => e.toInt())
            .toList(),
  );
});

void refreshDashboard(WidgetRef ref) {
  ref.invalidate(dashboardTodayProvider);
  ref.invalidate(billedByMonthProvider);
  ref.invalidate(collectedByMonthProvider);
  ref.invalidate(outstandingByAgeProvider);
  ref.invalidate(topOutstandingProvider);
  ref.invalidate(topPayingProvider);
  ref.invalidate(invoiceSummaryProvider);
  ref.invalidate(promiseSummaryProvider);
  ref.invalidate(upcomingPromisesProvider);
}
