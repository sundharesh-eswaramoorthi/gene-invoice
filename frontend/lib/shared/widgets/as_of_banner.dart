import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';

/// Says, above every page, that what is shown is a past day and cannot be changed (B3, D2). Takes
/// no room at all while the view is live.
class AsOfBanner extends ConsumerWidget {
  const AsOfBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asOf = ref.watch(viewAsOfProvider);
    if (asOf == null) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    // A container of its own, announced when it appears, so a screen reader is told the page is
    // a past day and read only (ASO-R1-1).
    return Semantics(
      container: true,
      liveRegion: true,
      child: Material(
        color: scheme.tertiaryContainer,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
          child: Row(
            children: [
              Icon(Icons.history_toggle_off, size: 20, color: scheme.onTertiaryContainer),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Viewing ${asOfLabel(asOf)} (end of day, UTC) — read only',
                  style: TextStyle(color: scheme.onTertiaryContainer),
                ),
              ),
              TextButton(
                onPressed: () => ref.read(viewScopeProvider.notifier).setAsOf(null),
                child: const Text('Back to today'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
