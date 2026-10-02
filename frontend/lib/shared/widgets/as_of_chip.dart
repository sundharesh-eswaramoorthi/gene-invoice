import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';

/// "Today" or "As of 30 Jun 2026" (B3). Tapping it asks the server which days it can answer for and
/// opens a date picker bounded by them — never later than yesterday, since today is simply live.
/// Nothing is fetched until it is tapped (G13). Staff only: the shell does not offer it to
/// customer logins (Q22).
class AsOfChip extends ConsumerStatefulWidget {
  /// Below 600px the chip gives way to an icon; the banner then offers the way back to today.
  final bool compact;

  const AsOfChip({super.key, this.compact = false});

  @override
  ConsumerState<AsOfChip> createState() => _AsOfChipState();
}

class _AsOfChipState extends ConsumerState<AsOfChip> {
  bool _opening = false;

  @override
  Widget build(BuildContext context) {
    final asOf = ref.watch(viewAsOfProvider);
    final label = asOf == null ? 'Today' : 'As of ${asOfLabel(asOf)}';
    final icon = Icon(asOf == null ? Icons.history : Icons.history_toggle_off, size: 18);

    if (widget.compact) {
      return IconButton(
        tooltip: '$label — view another day',
        icon: Icon(asOf == null ? Icons.history : Icons.history_toggle_off),
        onPressed: _opening ? null : _pick,
      );
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Center(
        child: asOf == null
            ? ActionChip(
                avatar: icon,
                label: Text(label),
                tooltip: 'View the figures as they stood at the end of an earlier day',
                onPressed: _opening ? null : _pick,
              )
            : InputChip(
                avatar: icon,
                label: Text(label),
                tooltip: 'View another day',
                onPressed: _opening ? null : _pick,
                deleteButtonTooltipMessage: 'Back to today',
                onDeleted: () => ref.read(viewScopeProvider.notifier).setAsOf(null),
              ),
      ),
    );
  }

  Future<void> _pick() async {
    setState(() => _opening = true);
    // Held open for as long as it is awaited: nothing else watches the range.
    final sub = ref.listenManual(historyRangeProvider.future, (_, __) {});
    HistoryRange? range;
    Object? failure;
    try {
      range = await sub.read();
    } catch (e) {
      failure = e;
    } finally {
      sub.close();
      if (mounted) setState(() => _opening = false);
    }
    if (!mounted) return;
    if (range == null) {
      _say('Could not find out which days can be shown: ${apiErrorMessage(failure!)}');
      return;
    }

    final last = range.latestAsOf ?? _yesterdayUtc();
    final first = range.historyStartsOn ?? DateTime(2000);
    if (first.isAfter(last)) {
      _say('History starts on ${asOfLabel(first)}, so there is no earlier day to view yet.');
      return;
    }
    final current = ref.read(viewScopeProvider).asOf ?? last;
    final initial = current.isBefore(first) ? first : (current.isAfter(last) ? last : current);
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: first,
      lastDate: last,
      helpText: 'View as at the end of',
      confirmText: 'View',
    );
    if (picked != null && mounted) ref.read(viewScopeProvider.notifier).setAsOf(picked);
  }

  void _say(String message) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));

  /// What the server would have said: the day before today, in UTC.
  static DateTime _yesterdayUtc() {
    final now = DateTime.now().toUtc();
    return DateTime(now.year, now.month, now.day - 1);
  }
}
