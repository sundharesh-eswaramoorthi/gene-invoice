import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';

/// Whose records a dashboard card covers, as the server worked it out. Only [book] is labelled on
/// screen: a POC limited to their own book should not mistake it for the whole organisation.
enum Coverage { all, book, own }

Coverage _coverage(Object? wire) => switch (wire) {
      'BOOK' => Coverage.book,
      'OWN' => Coverage.own,
      _ => Coverage.all,
    };

double _money(Object? v) => (v as num? ?? 0).toDouble();

Map<String, dynamic> _map(Object? data) => (data as Map).cast<String, dynamic>();

class MonthPoint {
  /// The first day of the month.
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

  /// Oldest first, one per month, empty months as zero.
  final List<MonthPoint> months;

  const MonthlySeries({required this.coverage, required this.months});

  double get total => months.fold(0, (sum, m) => sum + m.amount);

  factory MonthlySeries.fromJson(Map<String, dynamic> json) => MonthlySeries(
        coverage: _coverage(json['coverage']),
        months: ((json['months'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(MonthPoint.fromJson)
            .toList(),
      );
}

class AgeBucket {
  final String label;
  final int fromDays;

  /// Null on the last, open-ended bucket.
  final int? toDays;
  final double amount;
  final int count;

  const AgeBucket({
    required this.label,
    required this.fromDays,
    required this.toDays,
    required this.amount,
    required this.count,
  });

  factory AgeBucket.fromJson(Map<String, dynamic> json) => AgeBucket(
        label: json['label'] as String? ?? '',
        fromDays: (json['fromDays'] as num? ?? 0).toInt(),
        toDays: (json['toDays'] as num?)?.toInt(),
        amount: _money(json['amount']),
        count: (json['count'] as num? ?? 0).toInt(),
      );
}

class OutstandingByAge {
  final Coverage coverage;
  final List<AgeBucket> buckets;

  const OutstandingByAge({required this.coverage, required this.buckets});

  factory OutstandingByAge.fromJson(Map<String, dynamic> json) => OutstandingByAge(
        coverage: _coverage(json['coverage']),
        buckets: ((json['buckets'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(AgeBucket.fromJson)
            .toList(),
      );
}

/// One row of a customer ranking: what they owe or paid, how many invoices or payments that is,
/// and the oldest open invoice or the latest payment.
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
  final List<RankedCustomer> customers;

  const CustomerRanking({required this.coverage, required this.customers});

  static CustomerRanking _fromJson(
      Map<String, dynamic> json, String amountKey, String countKey, String dateKey) {
    return CustomerRanking(
      coverage: _coverage(json['coverage']),
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

/// How many months, ending with this one, the billed/collected figures and "top paying" cover.
final dashboardMonthsProvider = StateProvider<int>((ref) => 12);

/// Today in UTC, the calendar every date filter and dashboard figure uses.
DateTime todayUtc() {
  final now = DateTime.now().toUtc();
  return DateTime.utc(now.year, now.month, now.day);
}

final billedByMonthProvider = FutureProvider.autoDispose<MonthlySeries>((ref) async {
  final months = ref.watch(dashboardMonthsProvider);
  final res = await ref
      .watch(dioProvider)
      .get('/api/dashboard/billed-by-month', queryParameters: {'months': months});
  return MonthlySeries.fromJson(_map(res.data));
});

final collectedByMonthProvider = FutureProvider.autoDispose<MonthlySeries>((ref) async {
  final months = ref.watch(dashboardMonthsProvider);
  final res = await ref
      .watch(dioProvider)
      .get('/api/dashboard/collected-by-month', queryParameters: {'months': months});
  return MonthlySeries.fromJson(_map(res.data));
});

final outstandingByAgeProvider = FutureProvider.autoDispose<OutstandingByAge>((ref) async {
  final res = await ref.watch(dioProvider).get('/api/dashboard/outstanding-by-age');
  return OutstandingByAge.fromJson(_map(res.data));
});

final topOutstandingProvider = FutureProvider.autoDispose<CustomerRanking>((ref) async {
  final res = await ref.watch(dioProvider).get('/api/dashboard/top-outstanding-customers');
  return CustomerRanking.outstandingFromJson(_map(res.data));
});

final topPayingProvider = FutureProvider.autoDispose<CustomerRanking>((ref) async {
  final months = ref.watch(dashboardMonthsProvider);
  final res = await ref
      .watch(dioProvider)
      .get('/api/dashboard/top-paying-customers', queryParameters: {'months': months});
  return CustomerRanking.payingFromJson(_map(res.data));
});

/// The same filter-aware aggregate the Invoices tiles use, so the two can never disagree (AC-E6).
final invoiceSummaryProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final res = await ref.watch(dioProvider).get('/api/invoices/summary');
  return _map(res.data);
});

final promiseSummaryProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final res = await ref.watch(dioProvider).get('/api/promises/summary');
  return _map(res.data);
});

/// Every provider the dashboard reads, for pull-to-refresh.
void refreshDashboard(WidgetRef ref) {
  ref.invalidate(billedByMonthProvider);
  ref.invalidate(collectedByMonthProvider);
  ref.invalidate(outstandingByAgeProvider);
  ref.invalidate(topOutstandingProvider);
  ref.invalidate(topPayingProvider);
  ref.invalidate(invoiceSummaryProvider);
  ref.invalidate(promiseSummaryProvider);
}
