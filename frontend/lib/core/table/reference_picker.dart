import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/poc/poc_providers.dart';
import '../api/api_client.dart';

class ReferenceOption {
  final int id;
  final String label;
  final String? subtitle;
  const ReferenceOption(this.id, this.label, {this.subtitle});
}

class ReferenceSearch {
  final String kind;
  final String search;
  const ReferenceSearch(this.kind, this.search);

  @override
  bool operator ==(Object other) =>
      other is ReferenceSearch && other.kind == kind && other.search == search;
  @override
  int get hashCode => Object.hash(kind, search);
}

final referenceOptionsProvider =
    FutureProvider.autoDispose.family<List<ReferenceOption>, ReferenceSearch>((ref, q) async {
  final dio = ref.watch(dioProvider);
  switch (q.kind) {
    case 'pocUser':
      final res = await dio.get('/api/pocs/assignable', queryParameters: {
        'type': PocType.SALES.name,
        if (q.search.isNotEmpty) 'q': q.search,
        'limit': 25,
      });
      final sales = (res.data as List).cast<Map<String, dynamic>>().map(PocUser.fromJson);
      final others = await Future.wait([PocType.SUCCESS, PocType.COLLECTION].map((t) async {
        final r = await dio.get('/api/pocs/assignable', queryParameters: {
          'type': t.name,
          if (q.search.isNotEmpty) 'q': q.search,
          'limit': 25,
        });
        return (r.data as List).cast<Map<String, dynamic>>().map(PocUser.fromJson);
      }));
      final seen = <int, PocUser>{};
      for (final u in [sales, ...others].expand((e) => e)) {
        seen[u.id] = u;
      }
      return seen.values
          .map((u) => ReferenceOption(u.id, u.display, subtitle: '@${u.username}'))
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

  const ReferencePicker({
    super.key,
    required this.kind,
    required this.value,
    required this.onChanged,
    this.label,
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
              final async = ref.watch(referenceOptionsProvider(ReferenceSearch(widget.kind, _search)));
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
