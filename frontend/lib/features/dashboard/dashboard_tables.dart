import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/format.dart';
import '../../shared/models/promise.dart';
import '../promises/promises_tab.dart';
import 'dashboard_charts.dart';
import 'dashboard_providers.dart';

class DashColumn {
  final String label;
  final int flex;
  final bool numeric;

  /// Below this table width the column is dropped, so a phone keeps the columns that matter
  /// instead of scrolling sideways.
  final double minTableWidth;

  const DashColumn(this.label, {this.flex = 1, this.numeric = false, this.minTableWidth = 0});
}

class DashRow {
  final List<Widget> cells;
  final VoidCallback? onTap;

  const DashRow(this.cells, {this.onTap});
}

/// A compact read-only table: a header and hairline-separated rows, each optionally opening its
/// record.
class DashTable extends StatelessWidget {
  final List<DashColumn> columns;
  final List<DashRow> rows;
  final String empty;

  const DashTable({super.key, required this.columns, required this.rows, required this.empty});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (rows.isEmpty) return CardEmpty(message: empty, height: 120);
    final divider = Divider(height: 1, color: theme.colorScheme.outlineVariant);
    final header = theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant);

    return LayoutBuilder(builder: (context, constraints) {
      final shown = [
        for (var i = 0; i < columns.length; i++)
          if (constraints.maxWidth >= columns[i].minTableWidth) i,
      ];
      Widget line(List<Widget> cells) => Row(children: [
            for (var n = 0; n < shown.length; n++) ...[
              if (n > 0) const SizedBox(width: 12),
              Expanded(
                flex: columns[shown[n]].flex,
                child: Align(
                  alignment:
                      columns[shown[n]].numeric ? Alignment.centerRight : Alignment.centerLeft,
                  child: cells[shown[n]],
                ),
              ),
            ],
          ]);

      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            child: line([for (final c in columns) Text(c.label, style: header)]),
          ),
          divider,
          for (final r in rows) ...[
            MergeSemantics(
              child: InkWell(
                onTap: r.onTap,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 10),
                  child: line(r.cells),
                ),
              ),
            ),
            divider,
          ],
        ],
      );
    });
  }
}

Widget _text(BuildContext context, String text, {bool numeric = false, bool muted = false}) {
  final theme = Theme.of(context);
  return Text(
    text,
    maxLines: 1,
    overflow: TextOverflow.ellipsis,
    style: theme.textTheme.bodyMedium?.copyWith(
      color: muted ? theme.colorScheme.onSurfaceVariant : null,
      fontFeatures: numeric ? tabularFigures : null,
    ),
  );
}

/// Whole days between two instants' UTC calendar dates.
int _daysBetween(DateTime from, DateTime to) {
  DateTime day(DateTime d) {
    final u = d.toUtc();
    return DateTime.utc(u.year, u.month, u.day);
  }

  return day(to).difference(day(from)).inDays;
}

/// Customers owing the most. A row opens the customer when the user may see customers.
class TopOutstandingTable extends StatelessWidget {
  final CustomerRanking ranking;
  final bool canOpenCustomer;

  const TopOutstandingTable({super.key, required this.ranking, required this.canOpenCustomer});

  @override
  Widget build(BuildContext context) {
    final today = todayUtc();
    return DashTable(
      empty: 'Nothing is outstanding',
      columns: const [
        DashColumn('Customer', flex: 3),
        DashColumn('Outstanding', flex: 2, numeric: true),
        DashColumn('Open invoices', flex: 2, numeric: true, minTableWidth: 420),
        DashColumn('Oldest', flex: 2, numeric: true, minTableWidth: 520),
      ],
      rows: [
        for (final c in ranking.customers)
          DashRow(
            [
              _text(context, c.customerName),
              _text(context, formatMoney(c.amount), numeric: true),
              _text(context, '${c.count}', numeric: true),
              _text(
                context,
                c.date == null ? '—' : '${_daysBetween(c.date!, today)} days',
                numeric: true,
                muted: true,
              ),
            ],
            onTap: canOpenCustomer ? () => context.go('/customers/${c.customerId}') : null,
          ),
      ],
    );
  }
}

/// Customers who paid the most over the chosen period.
class TopPayingTable extends StatelessWidget {
  final CustomerRanking ranking;
  final bool canOpenCustomer;

  const TopPayingTable({super.key, required this.ranking, required this.canOpenCustomer});

  @override
  Widget build(BuildContext context) {
    return DashTable(
      empty: 'No payments in this period',
      columns: const [
        DashColumn('Customer', flex: 3),
        DashColumn('Paid', flex: 2, numeric: true),
        DashColumn('Payments', flex: 2, numeric: true, minTableWidth: 420),
        DashColumn('Last paid', flex: 2, numeric: true, minTableWidth: 520),
      ],
      rows: [
        for (final c in ranking.customers)
          DashRow(
            [
              _text(context, c.customerName),
              _text(context, formatMoney(c.amount), numeric: true),
              _text(context, '${c.count}', numeric: true),
              _text(context, formatDate(c.date), numeric: true, muted: true),
            ],
            onTap: canOpenCustomer ? () => context.go('/customers/${c.customerId}') : null,
          ),
      ],
    );
  }
}

/// The next promises falling due. A customer login sees only its own, so it gets no Customer
/// column; the Collection POC column is for staff who may see POCs.
class UpcomingPromisesTable extends StatelessWidget {
  final List<PaymentPromise> promises;
  final bool showCustomer;
  final bool showPoc;

  const UpcomingPromisesTable({
    super.key,
    required this.promises,
    required this.showCustomer,
    required this.showPoc,
  });

  static String _due(DateTime promisedDate, DateTime today) {
    // A promised date is a calendar date, so compare it as one.
    final due = DateTime.utc(promisedDate.year, promisedDate.month, promisedDate.day);
    final days = due.difference(today).inDays;
    return switch (days) {
      0 => 'today',
      1 => 'tomorrow',
      _ => 'in $days days',
    };
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final today = todayUtc();
    return DashTable(
      empty: 'No promises are due',
      columns: [
        const DashColumn('Promised by', flex: 2),
        if (showCustomer) const DashColumn('Customer', flex: 3),
        const DashColumn('Still to pay', flex: 2, numeric: true),
        const DashColumn('Status', flex: 2, minTableWidth: 480),
        if (showPoc) const DashColumn('Collection POC', flex: 2, minTableWidth: 720),
      ],
      rows: [
        for (final p in promises)
          DashRow(
            [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _text(context, formatDate(p.promisedDate), numeric: true),
                  Text(_due(p.promisedDate, today),
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                ],
              ),
              if (showCustomer) _text(context, p.customerName),
              _text(context, formatMoney(p.remainingAmount), numeric: true),
              PromiseStatusChip(status: p.status, overridden: p.statusOverridden),
              if (showPoc) _text(context, p.collectionPoc?.display ?? '—', muted: true),
            ],
            onTap: () => context.go('/promises/${p.id}'),
          ),
      ],
    );
  }
}
