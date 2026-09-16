import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/format.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/promise.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'dashboard_charts.dart';
import 'dashboard_providers.dart';
import 'dashboard_tables.dart';

/// Which cards a user gets. It goes by privileges rather than role names, because an admin can
/// reshape any role. The server enforces the same rules and scopes every figure, so this only
/// decides what is worth drawing.
class DashboardAccess {
  final bool invoices;
  final bool payments;
  final bool promises;
  final bool isCustomer;

  /// May open a customer from a ranking row.
  final bool customers;

  /// May see who the Collection POC is (AC-A8).
  final bool pocs;

  const DashboardAccess({
    required this.invoices,
    required this.payments,
    required this.promises,
    required this.isCustomer,
    required this.customers,
    required this.pocs,
  });

  factory DashboardAccess.of(CurrentUser? user) {
    final staff = user != null && !user.isCustomer;
    return DashboardAccess(
      invoices: user?.has(Privileges.invoiceView) ?? false,
      payments: user?.has(Privileges.paymentView) ?? false,
      promises: user?.has(Privileges.promiseView) ?? false,
      isCustomer: user?.isCustomer ?? false,
      customers: staff && user.has(Privileges.customerView),
      pocs: staff && user.has(Privileges.pocView),
    );
  }

  /// Rankings compare customers with each other, which is for staff only; the server refuses a
  /// customer login too.
  bool get rankings => !isCustomer;

  /// A customer pays; staff collect.
  String get collectedLabel => isCustomer ? 'Paid' : 'Collected';
}

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final access = DashboardAccess.of(user);

    return RefreshIndicator(
      onRefresh: () async => refreshDashboard(ref),
      child: LayoutBuilder(builder: (context, constraints) {
        final wide = constraints.maxWidth >= 1100;
        final twoUp = constraints.maxWidth >= 760;
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _Header(user: user, access: access),
            const SizedBox(height: 12),
            _QuickActions(user: user),
            const SizedBox(height: 16),
            _KeyFigures(access: access),
            const SizedBox(height: 16),
            _CardRow(sideBySide: wide, cards: [
              if (access.invoices || access.payments) (2, _TrendCard(access: access)),
              if (access.invoices) (1, const _InvoiceStatusCard()),
            ]),
            _CardRow(sideBySide: twoUp, cards: [
              if (access.invoices) (1, const _AgingCard()),
              if (access.promises) (1, const _PromiseStatusCard()),
            ]),
            _CardRow(sideBySide: wide, cards: [
              if (access.rankings && access.invoices) (1, _TopOutstandingCard(access: access)),
              if (access.rankings && access.payments) (1, _TopPayingCard(access: access)),
            ]),
            if (access.promises) _UpcomingPromisesCard(access: access),
          ],
        );
      }),
    );
  }
}

/// Cards side by side when there is room, stacked otherwise. A card the user may not see is simply
/// left out, and the rest take its space.
class _CardRow extends StatelessWidget {
  final bool sideBySide;
  final List<(int, Widget)> cards;

  const _CardRow({required this.sideBySide, required this.cards});

  @override
  Widget build(BuildContext context) {
    if (cards.isEmpty) return const SizedBox.shrink();
    final Widget content;
    if (sideBySide && cards.length > 1) {
      content = Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < cards.length; i++) ...[
            if (i > 0) const SizedBox(width: 16),
            Expanded(flex: cards[i].$1, child: cards[i].$2),
          ],
        ],
      );
    } else {
      content = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < cards.length; i++) ...[
            if (i > 0) const SizedBox(height: 16),
            cards[i].$2,
          ],
        ],
      );
    }
    return Padding(padding: const EdgeInsets.only(bottom: 16), child: content);
  }
}

class _Header extends ConsumerWidget {
  final CurrentUser? user;
  final DashboardAccess access;

  const _Header({required this.user, required this.access});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final months = ref.watch(dashboardMonthsProvider);
    final showPeriod = access.invoices || access.payments;
    final today = todayUtc();
    final from = DateTime.utc(today.year, today.month - (months - 1));
    final range =
        '${DateFormat('MMM yyyy').format(from)} – ${DateFormat('MMM yyyy').format(today)}';

    final String note;
    if (!showPeriod) {
      note = 'These figures cover everything you are permitted to see.';
    } else {
      final flows = [
        if (access.invoices) 'billed',
        if (access.payments) access.collectedLabel.toLowerCase(),
      ].join(' and ');
      final account = access.isCustomer ? 'Your account. ' : '';
      note = '$account${flows[0].toUpperCase()}${flows.substring(1)} cover $range; '
          'balances and promises are as of today (UTC).';
    }

    return Wrap(
      spacing: 16,
      runSpacing: 12,
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.end,
      children: [
        Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Welcome${user?.fullName != null ? ", ${user!.fullName}" : ""}',
                style: theme.textTheme.headlineSmall),
            Text(note, style: theme.textTheme.bodySmall),
          ],
        ),
        if (showPeriod)
          SegmentedButton<int>(
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: 3, label: Text('3 months')),
              ButtonSegment(value: 6, label: Text('6 months')),
              ButtonSegment(value: 12, label: Text('12 months')),
            ],
            selected: {months},
            onSelectionChanged: (s) => ref.read(dashboardMonthsProvider.notifier).state = s.first,
          ),
      ],
    );
  }
}

class _QuickActions extends StatelessWidget {
  final CurrentUser? user;
  const _QuickActions({required this.user});

  @override
  Widget build(BuildContext context) {
    final canSeePromises = user?.has(Privileges.promiseView) ?? false;
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        if (user?.has(Privileges.invoiceManage) ?? false)
          FilledButton.icon(
            icon: const Icon(Icons.add),
            label: const Text('New invoice'),
            onPressed: () => context.go('/invoices/new'),
          ),
        OutlinedButton.icon(
          icon: const Icon(Icons.list),
          label: Text(user?.isCustomer ?? false ? 'My invoices' : 'All invoices'),
          onPressed: () => context.go('/invoices'),
        ),
        OutlinedButton.icon(
          icon: const Icon(Icons.payments_outlined),
          // Staff without PAYMENT_MANAGE land on every payment in the organisation, so the
          // button must not call it "My payments" (D-67).
          label: Text((user?.has(Privileges.paymentManage) ?? false)
              ? 'Record payment'
              : ((user?.isCustomer ?? false) ? 'My payments' : 'All payments')),
          onPressed: () => context.go('/payments'),
        ),
        if (canSeePromises)
          OutlinedButton.icon(
            icon: const Icon(Icons.handshake_outlined),
            label: const Text('Payment promises'),
            onPressed: () => context.go('/promises'),
          ),
        if (user?.has(Privileges.disputeView) ?? false)
          OutlinedButton.icon(
            icon: const Icon(Icons.flag_outlined),
            label: const Text('Disputes'),
            onPressed: () => context.go('/disputes'),
          ),
      ],
    );
  }
}

double _num(Object? v) => v is num ? v.toDouble() : double.tryParse('$v') ?? 0;

class _KeyFigures extends ConsumerWidget {
  final DashboardAccess access;
  const _KeyFigures({required this.access});

  static String _value<T>(AsyncValue<T> v, String Function(T data) show) =>
      v.when(data: show, loading: () => '…', error: (_, __) => '—');

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final error = Theme.of(context).colorScheme.error;
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        if (access.invoices)
          SummaryTile(
            label: 'Billed',
            value: _value(ref.watch(billedByMonthProvider), (s) => formatMoneyCompact(s.total)),
          ),
        if (access.payments)
          SummaryTile(
            label: access.collectedLabel,
            value: _value(ref.watch(collectedByMonthProvider), (s) => formatMoneyCompact(s.total)),
          ),
        if (access.invoices)
          SummaryTile(
            label: 'Outstanding',
            value: _value(
                ref.watch(invoiceSummaryProvider), (s) => formatMoneyCompact(s['outstanding'])),
            accent: error,
          ),
        if (access.promises) ...[
          SummaryTile(
            label: 'Open promises',
            value: _value(
                ref.watch(promiseSummaryProvider),
                (s) => '${_num(s['openCount']).round() + _num(s['partiallyKeptCount']).round()} • '
                    '${formatMoneyCompact(_num(s['openAmount']) + _num(s['partiallyKeptAmount']))}'),
          ),
          SummaryTile(
            label: 'Broken promises',
            value: _value(ref.watch(promiseSummaryProvider),
                (s) => '${s['brokenCount'] ?? 0} • ${formatMoneyCompact(s['brokenAmount'])}'),
            accent: error,
          ),
        ],
      ],
    );
  }
}

class _TrendCard extends ConsumerWidget {
  final DashboardAccess access;
  const _TrendCard({required this.access});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final billed = access.invoices ? ref.watch(billedByMonthProvider) : null;
    final collected = access.payments ? ref.watch(collectedByMonthProvider) : null;
    final parts = [billed, collected].whereType<AsyncValue<MonthlySeries>>().toList();

    final title = switch ((billed != null, collected != null)) {
      (true, true) => 'Billed and ${access.collectedLabel.toLowerCase()} by month',
      (true, false) => 'Billed by month',
      _ => '${access.collectedLabel} by month',
    };
    final book = parts.any((p) => p.valueOrNull?.coverage == Coverage.book);

    final failed = parts.where((p) => p.hasError).firstOrNull;
    final Widget body;
    if (failed != null) {
      body = CardError(error: failed.error!, height: TrendChart.height);
    } else if (parts.any((p) => !p.hasValue)) {
      body = const CardLoading(height: TrendChart.height);
    } else {
      body = TrendChart(series: [
        if (billed != null)
          TrendSeries(
            label: 'Billed',
            singular: 'invoice',
            plural: 'invoices',
            color: billedColor,
            data: billed.value!,
          ),
        if (collected != null)
          TrendSeries(
            label: access.collectedLabel,
            singular: 'payment',
            plural: 'payments',
            color: collectedColor,
            data: collected.value!,
          ),
      ]);
    }

    return DashboardCard(
      title: title,
      subtitle: book && collected != null
          ? 'Payments count only what was paid against your invoices'
          : null,
      book: book,
      child: body,
    );
  }
}

class _InvoiceStatusCard extends ConsumerWidget {
  const _InvoiceStatusCard();

  static String _link(InvoiceStatus s) =>
      Uri(path: '/invoices', queryParameters: {'f': 'status:eq:${s.name}'}).toString();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // The summary carries no coverage of its own; it runs under the same scope as billed.
    final book = ref.watch(billedByMonthProvider).valueOrNull?.coverage == Coverage.book;
    return DashboardCard(
      title: 'Invoices by status',
      book: book,
      child: asyncCard(ref.watch(invoiceSummaryProvider), (s) {
        DonutSlice slice(InvoiceStatus status, String key) => DonutSlice(
              label: statusLabel(status),
              count: _num(s[key]).round(),
              color: invoiceStatusColor(context, status),
              link: _link(status),
            );
        // Orange and green are hard to tell apart for some readers, so another status sits
        // between them whenever it has any invoices; the legend names every slice regardless.
        return StatusDonut(noun: 'invoices', slices: [
          slice(InvoiceStatus.FULLY_PAID, 'fullyPaidCount'),
          slice(InvoiceStatus.UNPAID, 'unpaidCount'),
          slice(InvoiceStatus.PARTIALLY_PAID, 'partiallyPaidCount'),
          slice(InvoiceStatus.CANCELLED, 'cancelledCount'),
        ]);
      }),
    );
  }
}

class _AgingCard extends ConsumerWidget {
  const _AgingCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final aging = ref.watch(outstandingByAgeProvider);
    return DashboardCard(
      title: 'Outstanding by age',
      subtitle: 'Days since the invoice date',
      book: aging.valueOrNull?.coverage == Coverage.book,
      child: asyncCard(aging, (data) => AgingBars(data: data, today: todayUtc())),
    );
  }
}

class _PromiseStatusCard extends ConsumerWidget {
  const _PromiseStatusCard();

  static String _link(PromiseStatus s) =>
      Uri(path: '/promises', queryParameters: {'f': 'status:eq:${s.name}'}).toString();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return DashboardCard(
      title: 'Promises by status',
      subtitle: 'Cancelled promises are left out',
      // The summary runs under the same scope as the promises list.
      book: ref.watch(upcomingPromisesProvider).valueOrNull?.book ?? false,
      child: asyncCard(ref.watch(promiseSummaryProvider), (s) {
        DonutSlice slice(PromiseStatus status, String key) => DonutSlice(
              label: promiseStatusLabel(status),
              count: _num(s[key]).round(),
              color: promiseStatusColor(context, status),
              link: _link(status),
            );
        // Kept (green) sits opposite Partially kept (orange), the pair that is hardest to tell
        // apart, so they touch only when Open and Broken are both empty; the legend names every
        // slice regardless.
        return StatusDonut(noun: 'promises', slices: [
          slice(PromiseStatus.OPEN, 'openCount'),
          slice(PromiseStatus.PARTIALLY_KEPT, 'partiallyKeptCount'),
          slice(PromiseStatus.BROKEN, 'brokenCount'),
          slice(PromiseStatus.KEPT, 'keptCount'),
        ]);
      }),
    );
  }
}

class _TopOutstandingCard extends ConsumerWidget {
  final DashboardAccess access;
  const _TopOutstandingCard({required this.access});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ranking = ref.watch(topOutstandingProvider);
    return DashboardCard(
      title: 'Top customers by outstanding',
      subtitle: 'The five owing the most today',
      book: ranking.valueOrNull?.coverage == Coverage.book,
      action: access.customers
          ? TextButton(
              onPressed: () => context.go('/customers?sort=outstanding,desc'),
              child: const Text('All customers'),
            )
          : null,
      child: asyncCard(
          ranking, (r) => TopOutstandingTable(ranking: r, canOpenCustomer: access.customers)),
    );
  }
}

class _TopPayingCard extends ConsumerWidget {
  final DashboardAccess access;
  const _TopPayingCard({required this.access});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ranking = ref.watch(topPayingProvider);
    final months = ref.watch(dashboardMonthsProvider);
    final book = ranking.valueOrNull?.coverage == Coverage.book;
    return DashboardCard(
      title: 'Top paying customers',
      subtitle: book
          ? 'Last $months months, paid against your invoices'
          : 'The five who paid the most in the last $months months',
      book: book,
      child:
          asyncCard(ranking, (r) => TopPayingTable(ranking: r, canOpenCustomer: access.customers)),
    );
  }
}

class _UpcomingPromisesCard extends ConsumerWidget {
  final DashboardAccess access;
  const _UpcomingPromisesCard({required this.access});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final upcoming = ref.watch(upcomingPromisesProvider);
    return DashboardCard(
      title: 'Upcoming promises',
      subtitle: 'The next ten due, open or partly kept',
      book: upcoming.valueOrNull?.book ?? false,
      action: TextButton(
        onPressed: () => context.go(UpcomingPromises.listLink(todayUtc())),
        child: const Text('View all'),
      ),
      child: asyncCard(
          upcoming,
          (u) => UpcomingPromisesTable(
              promises: u.promises, showCustomer: !access.isCustomer, showPoc: access.pocs)),
    );
  }
}
