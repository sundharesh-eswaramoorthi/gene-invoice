import 'dart:math' as math;

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/table/route_query.dart';
import 'dashboard_providers.dart';

const billedColor = Color(0xFF2A78D6);
const collectedColor = Color(0xFFEB6834);

const magnitudeColor = billedColor;

const tabularFigures = [FontFeature.tabularFigures()];

final DateFormat _monthShort = DateFormat('MMM');
final DateFormat _monthLong = DateFormat('MMMM yyyy');
final DateFormat _wireDate = DateFormat('yyyy-MM-dd');

class DashboardCard extends StatelessWidget {
  final String title;
  final String? subtitle;
  final bool book;
  final Widget? action;
  final Widget child;

  const DashboardCard({
    super.key,
    required this.title,
    this.subtitle,
    this.book = false,
    this.action,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title, style: theme.textTheme.titleMedium),
                      if (subtitle != null)
                        Text(subtitle!,
                            style: theme.textTheme.bodySmall
                                ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                    ],
                  ),
                ),
                if (book) const BookBadge(),
                if (action != null) action!,
              ],
            ),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }
}

class BookBadge extends StatelessWidget {
  const BookBadge({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: 'Only your own book, not the whole organisation',
      child: Container(
        margin: const EdgeInsets.only(left: 8),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: scheme.secondaryContainer,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.person_outline, size: 14, color: scheme.onSecondaryContainer),
            const SizedBox(width: 4),
            Text('Your book',
                style: Theme.of(context)
                    .textTheme
                    .labelSmall
                    ?.copyWith(color: scheme.onSecondaryContainer)),
          ],
        ),
      ),
    );
  }
}

class CardLoading extends StatelessWidget {
  final double height;
  const CardLoading({super.key, this.height = 160});

  @override
  Widget build(BuildContext context) =>
      SizedBox(height: height, child: const Center(child: CircularProgressIndicator()));
}

class CardError extends StatelessWidget {
  final Object error;
  final double height;
  const CardError({super.key, required this.error, this.height = 160});

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        child: Center(
          child:
              Text('Could not load this: ${apiErrorMessage(error)}', textAlign: TextAlign.center),
        ),
      );
}

class CardEmpty extends StatelessWidget {
  final String message;
  final double height;
  const CardEmpty({super.key, required this.message, this.height = 160});

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        child: Center(
          child: Text(message,
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
        ),
      );
}

Widget asyncCard<T>(AsyncValue<T> value, Widget Function(T data) data, {double height = 160}) =>
    value.when(
      loading: () => CardLoading(height: height),
      error: (e, _) => CardError(error: e, height: height),
      data: data,
    );

String axisMoney(double v) {
  String trim(double x) => x == x.roundToDouble() ? x.toStringAsFixed(0) : x.toStringAsFixed(1);
  final a = v.abs();
  if (a >= 1e7) return '₹${trim(v / 1e7)}Cr';
  if (a >= 1e5) return '₹${trim(v / 1e5)}L';
  if (a >= 1e3) return '₹${trim(v / 1e3)}K';
  return '₹${trim(v)}';
}

double niceStep(double peak) {
  if (peak <= 0) return 1;
  final raw = peak / 4;
  final magnitude = math.pow(10, (math.log(raw) / math.ln10).floor()).toDouble();
  final n = raw / magnitude;
  final nice = n <= 1
      ? 1.0
      : n <= 2
          ? 2.0
          : n <= 2.5
              ? 2.5
              : n <= 5
                  ? 5.0
                  : 10.0;
  return nice * magnitude;
}

class TrendSeries {
  final String label;
  final String singular;
  final String plural;
  final Color color;
  final MonthlySeries data;

  const TrendSeries({
    required this.label,
    required this.singular,
    required this.plural,
    required this.color,
    required this.data,
  });
}

class TrendChart extends StatelessWidget {
  final List<TrendSeries> series;
  const TrendChart({super.key, required this.series});

  static const double height = 260;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final months = series.first.data.months;
    final peak =
        series.expand((s) => s.data.months).fold<double>(0, (m, p) => math.max(m, p.amount));
    if (months.isEmpty || peak <= 0) {
      return const CardEmpty(message: 'Nothing in this period yet', height: height);
    }
    final step = niceStep(peak);
    final maxY = (peak / step).ceil() * step;
    final muted = theme.textTheme.labelSmall
        ?.copyWith(color: scheme.onSurfaceVariant, fontFeatures: tabularFigures);
    final ring = scheme.surfaceContainerLow;

    final summary = series
        .map(
            (s) => '${s.label} by month: ${s.data.months.map((p) => '${_monthLong.format(p.month)} '
                '${formatMoneyCompact(p.amount)}').join(', ')}')
        .join('. ');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (series.length > 1)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Wrap(
              spacing: 16,
              children: [for (final s in series) _LineKey(color: s.color, label: s.label)],
            ),
          ),
        Semantics(
          container: true,
          label: summary,
          child: ExcludeSemantics(
            child: Container(
              height: height,
              padding: const EdgeInsets.only(top: 10),
              child: LayoutBuilder(builder: (context, constraints) {
                final perLabel = (constraints.maxWidth - 56) / months.length;
                final every = perLabel >= 40 ? 1 : (perLabel >= 20 ? 2 : 3);
                return LineChart(LineChartData(
                  minX: 0,
                  maxX: (months.length - 1).toDouble(),
                  minY: 0,
                  maxY: maxY,
                  gridData: FlGridData(
                    drawVerticalLine: false,
                    horizontalInterval: step,
                    getDrawingHorizontalLine: (_) =>
                        FlLine(color: scheme.outlineVariant.withValues(alpha: 0.6), strokeWidth: 1),
                  ),
                  borderData: FlBorderData(
                    show: true,
                    border: Border(bottom: BorderSide(color: scheme.outlineVariant)),
                  ),
                  titlesData: FlTitlesData(
                    topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                    rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                    leftTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 56,
                        interval: step,
                        getTitlesWidget: (value, meta) => SideTitleWidget(
                          meta: meta,
                          child: Text(axisMoney(value), style: muted),
                        ),
                      ),
                    ),
                    bottomTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 42,
                        interval: 1,
                        getTitlesWidget: (value, meta) {
                          final i = value.round();
                          if (i != value || i < 0 || i >= months.length) {
                            return const SizedBox.shrink();
                          }
                          if ((months.length - 1 - i) % every != 0) return const SizedBox.shrink();
                          final m = months[i].month;
                          final withYear = i == 0 || m.month == 1;
                          return SideTitleWidget(
                            meta: meta,
                            child: Text(
                              withYear
                                  ? '${_monthShort.format(m)}\n${m.year}'
                                  : _monthShort.format(m),
                              style: muted,
                              textAlign: TextAlign.center,
                            ),
                          );
                        },
                      ),
                    ),
                  ),
                  lineTouchData: LineTouchData(
                    getTouchedSpotIndicator: (bar, indexes) => indexes
                        .map((_) => TouchedSpotIndicatorData(
                              FlLine(color: scheme.outline, strokeWidth: 1),
                              FlDotData(
                                getDotPainter: (spot, pct, bar, i) => FlDotCirclePainter(
                                  radius: 5,
                                  color: bar.color ?? scheme.primary,
                                  strokeWidth: 2,
                                  strokeColor: ring,
                                ),
                              ),
                            ))
                        .toList(),
                    touchTooltipData: LineTouchTooltipData(
                      getTooltipColor: (_) => scheme.inverseSurface,
                      tooltipBorderRadius: BorderRadius.circular(8),
                      fitInsideHorizontally: true,
                      fitInsideVertically: true,
                      maxContentWidth: 240,
                      getTooltipItems: (spots) => spots.map((s) {
                        final line = series[s.barIndex];
                        final point = line.data.months[s.spotIndex];
                        final noun = point.count == 1 ? line.singular : line.plural;
                        return LineTooltipItem(
                          identical(s, spots.first) ? '${_monthLong.format(point.month)}\n' : '',
                          TextStyle(
                            color: scheme.onInverseSurface,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                          ),
                          textAlign: TextAlign.left,
                          children: [
                            TextSpan(
                              text: '${line.label} ${formatMoneyCompact(point.amount)}'
                                  ' · ${point.count} $noun',
                              style: const TextStyle(fontWeight: FontWeight.w400),
                            ),
                          ],
                        );
                      }).toList(),
                    ),
                  ),
                  lineBarsData: [
                    for (final s in series)
                      LineChartBarData(
                        spots: [
                          for (var i = 0; i < s.data.months.length; i++)
                            FlSpot(i.toDouble(), s.data.months[i].amount),
                        ],
                        color: s.color,
                        barWidth: 2,
                        isStrokeCapRound: true,
                        isStrokeJoinRound: true,
                        dotData: FlDotData(
                          getDotPainter: (spot, pct, bar, i) => FlDotCirclePainter(
                            radius: 4,
                            color: s.color,
                            strokeWidth: 2,
                            strokeColor: ring,
                          ),
                        ),
                        belowBarData: BarAreaData(
                          show: series.length == 1,
                          color: s.color.withValues(alpha: 0.1),
                        ),
                      ),
                  ],
                ));
              }),
            ),
          ),
        ),
      ],
    );
  }
}

class _LineKey extends StatelessWidget {
  final Color color;
  final String label;
  const _LineKey({required this.color, required this.label});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 16,
          height: 3,
          decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(2)),
        ),
        const SizedBox(width: 6),
        Text(label,
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ],
    );
  }
}

class DonutSlice {
  final String label;
  final int count;
  final Color color;

  final String? link;

  const DonutSlice({required this.label, required this.count, required this.color, this.link});
}

class StatusDonut extends StatefulWidget {
  final List<DonutSlice> slices;

  final String noun;

  const StatusDonut({super.key, required this.slices, required this.noun});

  @override
  State<StatusDonut> createState() => _StatusDonutState();
}

class _StatusDonutState extends State<StatusDonut> {
  DonutSlice? _touched;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final total = widget.slices.fold<int>(0, (sum, s) => sum + s.count);
    if (total == 0) return CardEmpty(message: 'No ${widget.noun} yet');
    final drawn = widget.slices.where((s) => s.count > 0).toList();
    final centre = _touched;

    final chart = SizedBox(
      width: 160,
      height: 160,
      child: Stack(
        alignment: Alignment.center,
        children: [
          ExcludeSemantics(
            child: PieChart(PieChartData(
              startDegreeOffset: -90,
              sectionsSpace: 2,
              centerSpaceRadius: 54,
              sections: [
                for (final s in drawn)
                  PieChartSectionData(
                    value: s.count.toDouble(),
                    color: s.color,
                    radius: identical(s, _touched) ? 26 : 22,
                    showTitle: false,
                  ),
              ],
              pieTouchData: PieTouchData(touchCallback: (event, response) {
                final i = response?.touchedSection?.touchedSectionIndex ?? -1;
                final next = event.isInterestedForInteractions && i >= 0 && i < drawn.length
                    ? drawn[i]
                    : null;
                if (!identical(next, _touched)) setState(() => _touched = next);
              }),
            )),
          ),
          Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('${centre?.count ?? total}',
                  style: theme.textTheme.titleLarge
                      ?.copyWith(fontWeight: FontWeight.w600, fontFeatures: tabularFigures)),
              SizedBox(
                width: 90,
                child: Text(centre?.label ?? widget.noun,
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    style: theme.textTheme.labelSmall
                        ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              ),
            ],
          ),
        ],
      ),
    );

    final legend = Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final s in widget.slices)
          _LegendRow(slice: s, total: total, highlighted: identical(s, _touched)),
      ],
    );

    return LayoutBuilder(builder: (context, constraints) {
      if (constraints.maxWidth >= 380) {
        return Row(children: [chart, const SizedBox(width: 24), Expanded(child: legend)]);
      }
      return Column(children: [chart, const SizedBox(height: 12), legend]);
    });
  }
}

class _LegendRow extends StatelessWidget {
  final DonutSlice slice;
  final int total;
  final bool highlighted;

  const _LegendRow({required this.slice, required this.total, required this.highlighted});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final share = slice.count * 100 / total;
    final percent = slice.count == 0
        ? '0%'
        : share < 1
            ? '<1%'
            : '${share.round()}%';
    return InkWell(
      onTap: slice.link == null ? null : () => context.go(slice.link!),
      borderRadius: BorderRadius.circular(6),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
        decoration: BoxDecoration(
          color: highlighted ? theme.colorScheme.surfaceContainerHighest : null,
          borderRadius: BorderRadius.circular(6),
        ),
        child: Row(
          children: [
            Container(
              width: 10,
              height: 10,
              decoration: BoxDecoration(color: slice.color, borderRadius: BorderRadius.circular(3)),
            ),
            const SizedBox(width: 8),
            Expanded(child: Text(slice.label, style: theme.textTheme.bodyMedium)),
            Text('${slice.count}',
                style: theme.textTheme.bodyMedium?.copyWith(fontFeatures: tabularFigures)),
            SizedBox(
              width: 48,
              child: Text(percent,
                  textAlign: TextAlign.right,
                  style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant, fontFeatures: tabularFigures)),
            ),
          ],
        ),
      ),
    );
  }
}

class AgingBars extends StatelessWidget {
  final OutstandingByAge data;
  final DateTime today;

  const AgingBars({super.key, required this.data, required this.today});

  static String? linkFor(AgeBucket bucket, DateTime today) {
    final dated = bucket.dueDateFrom == null && bucket.dueDateTo == null
        ? _windowFromDays(bucket, today)
        : _window(bucket.dueDateFrom, bucket.dueDateTo);
    if (dated == null) return null;
    return RouteQuery.location('/invoices', {
      'f': ['status:in:UNPAID,PARTIALLY_PAID', dated],
    });
  }

  static String? _window(DateTime? from, DateTime? to) {
    if (from == null) return 'dueDate:lte:${_wireDate.format(to!)}';
    if (to == null) return 'dueDate:gte:${_wireDate.format(from)}';
    if (from.isAfter(to)) return null;
    return 'dueDate:between:${_wireDate.format(from)},${_wireDate.format(to)}';
  }

  static String? _windowFromDays(AgeBucket bucket, DateTime today) {
    String dueAt(int daysOverdue) =>
        _wireDate.format(today.subtract(Duration(days: daysOverdue)));
    final fromDays = bucket.fromDays ?? 0;
    if (fromDays <= 0) return 'dueDate:gte:${dueAt(0)}';
    if (bucket.toDays == null) return 'dueDate:lte:${dueAt(fromDays)}';
    if (bucket.toDays! < fromDays) return null;
    return 'dueDate:between:${dueAt(bucket.toDays!)},${dueAt(fromDays)}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final peak = data.buckets.fold<double>(0, (m, b) => math.max(m, b.amount));
    if (peak <= 0) return const CardEmpty(message: 'Nothing is outstanding');

    return Column(
      children: [
        for (final b in data.buckets)
          _bar(context, b, theme, muted, peak),
      ],
    );
  }

  Widget _bar(
      BuildContext context, AgeBucket b, ThemeData theme, TextStyle? muted, double peak) {
    final link = linkFor(b, today);
    return InkWell(
      onTap: link == null ? null : () => context.go(link),
      borderRadius: BorderRadius.circular(6),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
        child: Row(
          children: [
            SizedBox(
              width: 104,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(b.label, style: theme.textTheme.bodyMedium),
                  Text('${b.count} ${b.count == 1 ? 'invoice' : 'invoices'}', style: muted),
                ],
              ),
            ),
            Expanded(
              child: LayoutBuilder(builder: (context, constraints) {
                final width =
                    b.amount <= 0 ? 0.0 : math.max(2.0, constraints.maxWidth * b.amount / peak);
                return Align(
                  alignment: Alignment.centerLeft,
                  child: Container(
                    height: 20,
                    width: width,
                    decoration: const BoxDecoration(
                      color: magnitudeColor,
                      borderRadius: BorderRadius.horizontal(right: Radius.circular(4)),
                    ),
                  ),
                );
              }),
            ),
            const SizedBox(width: 12),
            SizedBox(
              width: 84,
              child: Tooltip(
                message: formatMoney(b.amount),
                child: Text(formatMoneyCompact(b.amount),
                    textAlign: TextAlign.right,
                    style: theme.textTheme.bodyMedium?.copyWith(fontFeatures: tabularFigures)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
