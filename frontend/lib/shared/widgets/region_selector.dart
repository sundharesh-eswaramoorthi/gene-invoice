import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/scope/view_scope.dart';
import '../../features/auth/auth_controller.dart';
import '../models/auth_models.dart';

/// How a choice of regions reads: "All regions", or the chosen codes in the order `/me` lists them.
String regionSelectionLabel(List<RegionAccess> regions, Set<int>? chosen) {
  if (chosen == null) return 'All regions';
  final codes = [for (final r in regions) if (chosen.contains(r.id)) r.code];
  return codes.isEmpty ? 'All regions' : codes.join(', ');
}

/// "Which regions am I asking about" (B1), for every list, count and dashboard figure at once. The
/// choices are the regions `/me` returned, so opening it fetches nothing (G13). Hidden for customer
/// logins, whose one region is their customer's, and for anyone with a single region to choose.
class RegionSelector extends ConsumerWidget {
  /// Below 600px the label gives way to an icon; the tooltip still says what is chosen.
  final bool compact;

  const RegionSelector({super.key, this.compact = false});

  static const _allRegions = -1;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    if (user == null || user.isCustomer || user.regions.length <= 1) return const SizedBox.shrink();
    final regions = user.regions;
    final chosen = ref.watch(viewScopeProvider.select((s) => s.regionIds));
    final label = regionSelectionLabel(regions, chosen);

    return PopupMenuButton<int>(
      tooltip: 'Regions: $label',
      position: PopupMenuPosition.under,
      onSelected: (id) => _choose(ref, regions, chosen, id),
      itemBuilder: (_) => [
        CheckedPopupMenuItem(
          value: _allRegions,
          checked: chosen == null,
          child: const Text('All my regions'),
        ),
        const PopupMenuDivider(),
        for (final r in regions)
          CheckedPopupMenuItem(
            value: r.id,
            // With every region asked about, each one is ticked; unticking one leaves the rest.
            checked: chosen == null || chosen.contains(r.id),
            child: Text('${r.code} · ${r.name}${r.active ? '' : ' (inactive)'}'),
          ),
      ],
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.public),
            if (!compact) ...[
              const SizedBox(width: 6),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 180),
                child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
              ),
              const Icon(Icons.arrow_drop_down),
            ],
          ],
        ),
      ),
    );
  }

  void _choose(WidgetRef ref, List<RegionAccess> regions, Set<int>? chosen, int id) {
    final scope = ref.read(viewScopeProvider.notifier);
    if (id == _allRegions) {
      scope.setRegions(null);
      return;
    }
    final all = {for (final r in regions) r.id};
    final next = {...(chosen ?? all)};
    if (!next.remove(id)) next.add(id);
    // Some region is always being asked about: unticking the last one changes nothing.
    if (next.isEmpty) return;
    scope.setRegions(next.containsAll(all) ? null : next);
  }
}
