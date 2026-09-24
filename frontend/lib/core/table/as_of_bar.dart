import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../as_of/as_of_providers.dart';
import '../format.dart';
import 'table_models.dart';

/// The one control that takes a view into the past and the one that brings it back.
///
/// It is deliberately loud. A screen showing January's numbers under today's heading is the single
/// failure this feature can produce that nobody notices, so the date is never implicit: the chip
/// names the day in words, it is coloured differently from everything around it, and leaving is a
/// button of its own rather than an empty date field (B3).
///
/// [value] is what the CLIENT asked for; [info] is what the SERVER says it answered. They are two
/// different facts and the bar shows both, because a date at or after today is short-circuited to
/// live and a page that said "As of today" over live rows would be the same quiet lie in reverse.
class AsOfBar extends ConsumerWidget {
  /// The day the view is being asked about, or null for now.
  final DateTime? value;

  /// The server's own account of what it answered. Null means it answered live.
  final AsOfInfo? info;

  /// Whether any answer has arrived yet. Without it a request still in flight is indistinguishable
  /// from one the server chose to answer live, and the bar would accuse the server of ignoring a
  /// date it has not yet seen.
  final bool answered;

  final ValueChanged<DateTime?> onChanged;

  /// What "today" is when the server has not said. The server's own day is preferred, because a
  /// browser an hour ahead of UTC must not be able to pick a tomorrow the server will refuse.
  final DateTime? clientToday;

  const AsOfBar({
    super.key,
    required this.value,
    required this.info,
    required this.onChanged,
    this.answered = true,
    this.clientToday,
  });

  static const offerLabel = 'As of a date…';

  /// The sentence on the chip and in every disabled tooltip, so one reading of the page teaches
  /// what the other means.
  static String showingLabel(DateTime day) =>
      'As of ${formatDayLong(day)} — showing history';

  static String servedLiveLabel(DateTime day) =>
      '${formatDayLong(day)} is not in the past — showing today';

  static String readOnlyTooltip(DateTime day) =>
      'Read-only: you are looking at ${formatDayLong(day)}';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final capability = ref.watch(asOfCapabilityProvider).valueOrNull ?? AsOfCapability.unknown;
    final day = value;

    if (day == null) {
      return ActionChip(
        avatar: const Icon(Icons.history, size: 18),
        label: const Text(offerLabel),
        tooltip: 'Show this list as it stood on a past date',
        onPressed: () => _pick(context, capability),
      );
    }

    // The server answered live although a date was asked for: it was not in the past. Saying
    // "as of" here would be exactly the misreading this bar exists to prevent.
    final servedLive = answered && info == null;
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        RawChip(
          avatar: Icon(Icons.history,
              size: 18,
              color: servedLive ? scheme.onSurfaceVariant : scheme.onTertiaryContainer),
          label: Text(servedLive ? servedLiveLabel(day) : showingLabel(day)),
          backgroundColor:
              servedLive ? scheme.surfaceContainerHighest : scheme.tertiaryContainer,
          labelStyle: TextStyle(
            color: servedLive ? scheme.onSurfaceVariant : scheme.onTertiaryContainer,
            fontWeight: FontWeight.w600,
          ),
          tooltip: 'Pick another date',
          onPressed: () => _pick(context, capability),
        ),
        TextButton.icon(
          icon: const Icon(Icons.today, size: 18),
          label: const Text('Back to today'),
          onPressed: () => onChanged(null),
        ),
      ],
    );
  }

  Future<void> _pick(BuildContext context, AsOfCapability capability) async {
    final today = capability.latestOffered(clientToday ?? asOfDay(DateTime.now().toUtc()));
    final first = capability.earliestOffered;
    // A floor installed in the future, or a clock skew, would otherwise throw inside showDatePicker
    // rather than simply offering one day.
    final last = today.isBefore(first) ? first : today;
    final initial = value == null
        ? last
        : (value!.isBefore(first) ? first : (value!.isAfter(last) ? last : value!));
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: first,
      lastDate: last,
      helpText: 'Show this view as of',
      confirmText: 'Show',
    );
    if (picked == null) return;
    onChanged(asOfDay(picked));
  }
}

/// What the server said about the answer, rendered verbatim.
///
/// The client never composes a caveat of its own: only the server knows whether a value is the
/// day's or the floor's, and a sentence invented here would drift from the one in the CSV the same
/// export produces. Silent when there is nothing to say (B3).
class AsOfNotice extends StatelessWidget {
  final AsOfInfo? info;
  const AsOfNotice({super.key, required this.info});

  @override
  Widget build(BuildContext context) {
    final info = this.info;
    if (info == null || !info.hasSomethingToSay) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    // Amber says "this is the past"; the chip already does that on every historical page. This
    // one only appears when the answer is APPROXIMATE, which the design (h) puts in red — so it
    // has to read as a different and louder thing than the chip it sits under (B3).
    final loud = info.isApproximate;
    final lines = <String>[
      ...info.notes,
      if (info.omittedDeleted > 0)
        '${info.omittedDeleted} record${info.omittedDeleted == 1 ? '' : 's'} deleted before the '
            'history floor have no history row and cannot be shown.',
      if (loud && info.notes.isEmpty)
        'Some values in this answer are approximate rather than the values of that day.',
    ];
    if (lines.isEmpty) return const SizedBox.shrink();

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: loud ? scheme.errorContainer : scheme.tertiaryContainer,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(loud ? Icons.warning_amber_rounded : Icons.history_toggle_off,
                size: 18,
                color: loud ? scheme.onErrorContainer : scheme.onTertiaryContainer),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final line in lines)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 2),
                      child: Text(line,
                          style: TextStyle(
                              color:
                                  loud ? scheme.onErrorContainer : scheme.onTertiaryContainer)),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
