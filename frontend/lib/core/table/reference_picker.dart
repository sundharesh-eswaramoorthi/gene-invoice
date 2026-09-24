import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/poc/poc_providers.dart';
import '../api/api_client.dart';
import '../region/region_providers.dart';

class ReferenceOption {
  final int id;
  final String label;
  final String? subtitle;
  const ReferenceOption(this.id, this.label, {this.subtitle});
}

class ReferenceSearch {
  final String kind;
  final String search;

  /// Which branches this picker is being opened for. A bare filter has no record to take a
  /// branch from, so the list it is opened on names its own: what it has been narrowed to, or
  /// every branch the caller works in. Empty means "name no branch", which the server accepts
  /// only from a wildcard holder (B1).
  final List<int> regionIds;

  const ReferenceSearch(this.kind, this.search, {this.regionIds = const []});

  @override
  bool operator ==(Object other) =>
      other is ReferenceSearch &&
      other.kind == kind &&
      other.search == search &&
      listEquals(other.regionIds, regionIds);
  @override
  int get hashCode => Object.hash(kind, search, Object.hashAll(regionIds));
}

final referenceOptionsProvider =
    FutureProvider.autoDispose.family<List<ReferenceOption>, ReferenceSearch>((ref, q) async {
  final dio = ref.watch(dioProvider);
  switch (q.kind) {
    case 'pocUser':
      // GET /api/pocs/assignable refuses to guess a branch for anybody but a wildcard holder,
      // because "who can be a POC" has no answer that is true in every branch. So the picker
      // asks once per branch this list is about and takes the union — the honest answer to
      // "who could hold this seat anywhere I work" — rather than naming none and being
      // refused, which is what the shipped client did (B1).
      final branches = q.regionIds.isEmpty ? <int?>[null] : q.regionIds.cast<int?>().toList();
      final calls = <Future<Iterable<PocUser>>>[];
      for (final type in PocType.values) {
        for (final region in branches) {
          calls.add(dio.get('/api/pocs/assignable', queryParameters: {
            'type': type.name,
            if (region != null) 'regionId': region,
            if (q.search.isNotEmpty) 'q': q.search,
            'limit': 25,
          }).then((r) => (r.data as List).cast<Map<String, dynamic>>().map(PocUser.fromJson)));
        }
      }
      final seen = <int, PocUser>{};
      for (final u in (await Future.wait(calls)).expand((e) => e)) {
        seen[u.id] = u;
      }
      return seen.values
          .map((u) => ReferenceOption(u.id, u.display, subtitle: '@${u.username}'))
          .toList();
    case 'region':
      // Off the caller's own roster, not a customer search: without this case the `?? 'customer'`
      // fallback below silently searched customers for a branch (B1).
      final regions = await ref.watch(selectableRegionsProvider.future);
      final needle = q.search.toLowerCase();
      return regions
          .where((r) =>
              needle.isEmpty ||
              r.code.toLowerCase().contains(needle) ||
              r.name.toLowerCase().contains(needle))
          .map((r) => ReferenceOption(r.id, r.name.isEmpty ? r.code : r.name, subtitle: r.code))
          .toList();
    case 'rule':
      // The run history publishes ruleId with referenceKind 'rule', so a reader narrowing the
      // activity list to one rule picks it by NAME rather than by guessing an id (A5).
      final res = await dio.get('/api/automation/rules', queryParameters: {
        'size': 20,
        'sort': 'name,asc',
        if (q.search.isNotEmpty) 'filter': ['name:contains:${q.search}'],
      });
      return ((res.data as Map)['content'] as List)
          .cast<Map<String, dynamic>>()
          .map((m) => ReferenceOption((m['id'] as num).toInt(), m['name'] as String? ?? '',
              subtitle: m['subjectType'] as String?))
          .toList();
    case 'invoice':
      final res = await dio.get('/api/invoices', queryParameters: {
        'size': 20,
        if (q.search.isNotEmpty) 'filter': ['invoiceNumber:contains:${q.search}'],
      });
      return ((res.data as Map)['content'] as List)
          .cast<Map<String, dynamic>>()
          .map((m) => ReferenceOption((m['id'] as num).toInt(), m['invoiceNumber'] as String,
              subtitle: m['customerName'] as String?))
          .toList();
    case 'payment':
      final id = int.tryParse(q.search.replaceAll('#', '').trim());
      final res = await dio.get('/api/payments', queryParameters: {
        'size': 20,
        if (id != null) 'filter': ['id:eq:$id'],
      });
      return ((res.data as Map)['content'] as List)
          .cast<Map<String, dynamic>>()
          .map((m) => ReferenceOption((m['id'] as num).toInt(), 'Payment #${m['id']}',
              subtitle: m['customerName'] as String?))
          .toList();
    case 'customer':
    default:
      final res = await dio.get('/api/customers', queryParameters: {
        'size': 20,
        if (q.search.isNotEmpty) 'filter': ['name:contains:${q.search}'],
      });
      return ((res.data as Map)['content'] as List)
          .cast<Map<String, dynamic>>()
          .map((m) => ReferenceOption((m['id'] as num).toInt(), m['name'] as String,
              subtitle: m['email'] as String?))
          .toList();
  }
});

class ReferencePicker extends ConsumerStatefulWidget {
  final String kind;
  final String? label;
  final ReferenceOption? value;
  final ValueChanged<ReferenceOption?> onChanged;

  /// The branches the list this picker was opened from is about (B1). See [ReferenceSearch].
  final List<int> regionIds;

  const ReferencePicker({
    super.key,
    required this.kind,
    required this.value,
    required this.onChanged,
    this.label,
    this.regionIds = const [],
  });

  @override
  ConsumerState<ReferencePicker> createState() => _ReferencePickerState();
}

class _ReferencePickerState extends ConsumerState<ReferencePicker> {
  String _search = '';
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextField(
          decoration: InputDecoration(
            labelText: widget.label ?? 'Search',
            prefixIcon: const Icon(Icons.search),
          ),
          onChanged: (v) {
            _debounce?.cancel();
            _debounce = Timer(const Duration(milliseconds: 250), () {
              if (mounted) setState(() => _search = v.trim());
            });
          },
        ),
        const SizedBox(height: 8),
        SizedBox(
          height: 220,
          child: Consumer(
            builder: (context, ref, _) {
              final async = ref.watch(referenceOptionsProvider(
                  ReferenceSearch(widget.kind, _search, regionIds: widget.regionIds)));
              return async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(child: Text('Failed to load: $e')),
                data: (options) {
                  if (options.isEmpty) return const Center(child: Text('Nothing matches'));
                  return ListView.builder(
                    itemCount: options.length,
                    itemBuilder: (context, i) {
                      final o = options[i];
                      return ListTile(
                        dense: true,
                        title: Text(o.label),
                        subtitle: o.subtitle == null ? null : Text(o.subtitle!),
                        selected: o.id == widget.value?.id,
                        onTap: () => widget.onChanged(o),
                      );
                    },
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}
