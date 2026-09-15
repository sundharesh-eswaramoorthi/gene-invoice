import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';

/// A form field that picks one record through a server-side search, for lists too long to load
/// whole into a dropdown — every customer, every active product. Tapping it opens a dialog that
/// searches as the user types.
class SearchPickerField<T> extends StatelessWidget {
  final String label;
  final T? value;
  final String Function(T item) labelOf;
  final String? Function(T item)? subtitleOf;

  /// Fetches the records matching what was typed; an empty search lists the first page.
  final Future<List<T>> Function(String search) search;
  final ValueChanged<T> onChanged;
  final bool required;
  final String? errorText;

  const SearchPickerField({
    super.key,
    required this.label,
    required this.value,
    required this.labelOf,
    required this.search,
    required this.onChanged,
    this.subtitleOf,
    this.required = false,
    this.errorText,
  });

  @override
  Widget build(BuildContext context) {
    final current = value;
    return InkWell(
      onTap: () => _open(context),
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: required ? '$label *' : label,
          errorText: errorText,
          suffixIcon: const Icon(Icons.arrow_drop_down),
        ),
        child: Text(
          current == null ? 'Select…' : labelOf(current),
          style: current == null ? TextStyle(color: Theme.of(context).hintColor) : null,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }

  Future<void> _open(BuildContext context) async {
    final picked = await showDialog<T>(
      context: context,
      builder: (_) => _SearchPickerDialog<T>(
        title: 'Choose ${label.toLowerCase()}',
        labelOf: labelOf,
        subtitleOf: subtitleOf,
        search: search,
      ),
    );
    if (picked != null) onChanged(picked);
  }
}

class _SearchPickerDialog<T> extends StatefulWidget {
  final String title;
  final String Function(T item) labelOf;
  final String? Function(T item)? subtitleOf;
  final Future<List<T>> Function(String search) search;

  const _SearchPickerDialog({
    super.key,
    required this.title,
    required this.labelOf,
    required this.subtitleOf,
    required this.search,
  });

  @override
  State<_SearchPickerDialog<T>> createState() => _SearchPickerDialogState<T>();
}

class _SearchPickerDialogState<T> extends State<_SearchPickerDialog<T>> {
  late Future<List<T>> _results = widget.search('');
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  // The dialog owns the search, so the list always answers what is in the box now rather than
  // the keystroke before it.
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
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Search by name',
                prefixIcon: Icon(Icons.search),
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
                  if (items.isEmpty) return const Center(child: Text('Nothing matches'));
                  return ListView.builder(
                    itemCount: items.length,
                    itemBuilder: (context, i) {
                      final item = items[i];
                      final subtitle = widget.subtitleOf?.call(item);
                      return ListTile(
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
