import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';

/// Asks [search] as the user types — 250 ms after the last key — and resolves with the item
/// tapped, or null when cancelled.
Future<T?> showPeopleSearchDialog<T>(
  BuildContext context, {
  required String title,
  required Future<List<T>> Function(String search) search,
  required String Function(T item) labelOf,
  String? Function(T item)? subtitleOf,
  String hint = 'Search by name or username',
}) =>
    showDialog<T>(
      context: context,
      builder: (_) => PeopleSearchDialog<T>(
        title: title,
        hint: hint,
        search: search,
        labelOf: labelOf,
        subtitleOf: subtitleOf,
      ),
    );

class PeopleSearchDialog<T> extends StatefulWidget {
  final String title;
  final String hint;
  final Future<List<T>> Function(String search) search;
  final String Function(T item) labelOf;
  final String? Function(T item)? subtitleOf;

  const PeopleSearchDialog({
    super.key,
    required this.title,
    required this.search,
    required this.labelOf,
    this.subtitleOf,
    this.hint = 'Search by name or username',
  });

  @override
  State<PeopleSearchDialog<T>> createState() => _PeopleSearchDialogState<T>();
}

class _PeopleSearchDialogState<T> extends State<PeopleSearchDialog<T>> {
  late Future<List<T>> _results = widget.search('');
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _onSearchChanged(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () {
      if (!mounted) return;
      setState(() {
        _results = widget.search(text.trim());
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              key: const ValueKey('people-search'),
              autofocus: true,
              decoration: InputDecoration(
                labelText: widget.hint,
                prefixIcon: const Icon(Icons.search),
              ),
              onChanged: _onSearchChanged,
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 300,
              child: FutureBuilder<List<T>>(
                future: _results,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Center(
                        child: Text('Could not search: ${apiErrorMessage(snapshot.error!)}'));
                  }
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  final items = snapshot.data ?? const [];
                  if (items.isEmpty) return const Center(child: Text('Nobody matches'));
                  return ListView.builder(
                    itemCount: items.length,
                    itemBuilder: (context, i) {
                      final item = items[i];
                      final subtitle = widget.subtitleOf?.call(item);
                      return ListTile(
                        leading: const Icon(Icons.person_outline),
                        title: Text(widget.labelOf(item)),
                        subtitle: subtitle == null ? null : Text(subtitle),
                        onTap: () => Navigator.of(context).pop(item),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
      ],
    );
  }
}
