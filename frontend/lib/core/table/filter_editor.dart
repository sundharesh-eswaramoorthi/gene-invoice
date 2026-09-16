import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'reference_picker.dart';
import 'table_models.dart';

/// Builds one filter chip: pick a column, then an operator valid for that column's type,
/// then a value editor suited to the type (D.4).
Future<TableFilter?> showFilterEditor({
  required BuildContext context,
  required TableSchema schema,
  TableFilter? existing,
}) {
  return showDialog<TableFilter>(
    context: context,
    builder: (_) => _FilterEditorDialog(schema: schema, existing: existing),
  );
}

class _FilterEditorDialog extends StatefulWidget {
  final TableSchema schema;
  final TableFilter? existing;
  const _FilterEditorDialog({required this.schema, this.existing});

  @override
  State<_FilterEditorDialog> createState() => _FilterEditorDialogState();
}

class _FilterEditorDialogState extends State<_FilterEditorDialog> {
  ColumnDef? _column;
  String? _operator;
  final _valueA = TextEditingController();
  final _valueB = TextEditingController();
  final Set<String> _enumSelection = {};
  ReferenceOption? _reference;
  String? _preset;
  String? _error;

  @override
  void initState() {
    super.initState();
    final columns = widget.schema.filterable;
    if (widget.existing != null) {
      _column = widget.schema.column(widget.existing!.field);
      _operator = widget.existing!.operator;
      final values = widget.existing!.values;
      if (values.isNotEmpty) {
        _valueA.text = values.first;
        if (values.length > 1) _valueB.text = values[1];
        _enumSelection.addAll(values);
        _preset = values.first;
        final id = int.tryParse(values.first);
        if (id != null) _reference = ReferenceOption(id, values.first);
      }
    } else if (columns.isNotEmpty) {
      _column = columns.first;
      _operator = columns.first.operators.firstOrNull;
    }
  }

  @override
  void dispose() {
    _valueA.dispose();
    _valueB.dispose();
    super.dispose();
  }

  bool get _needsNoValue => _operator == 'isEmpty' || _operator == 'isNotEmpty';

  TableFilter? _build() {
    final column = _column;
    final op = _operator;
    if (column == null || op == null) {
      setState(() => _error = 'Pick a column and an operator');
      return null;
    }
    if (_needsNoValue) return TableFilter(column.name, op, const []);

    switch (column.type) {
      case ColumnType.enumeration:
        if (op == 'in' || op == 'notIn') {
          if (_enumSelection.isEmpty) {
            setState(() => _error = 'Pick at least one value');
            return null;
          }
          return TableFilter(column.name, op, _enumSelection.toList());
        }
        if (_valueA.text.isEmpty) {
          setState(() => _error = 'Pick a value');
          return null;
        }
        return TableFilter(column.name, op, [_valueA.text]);
      case ColumnType.reference:
        if (_reference == null) {
          setState(() => _error = 'Pick a record');
          return null;
        }
        return TableFilter(column.name, op, ['${_reference!.id}'], label: _reference!.label);
      case ColumnType.boolean:
        return TableFilter(column.name, op, [_valueA.text.isEmpty ? 'true' : _valueA.text]);
      case ColumnType.date:
        if (op == 'relative') {
          if (_preset == null) {
            setState(() => _error = 'Pick a period');
            return null;
          }
          return TableFilter(column.name, op, [_preset!]);
        }
        break;
      default:
        break;
    }

    if (op == 'between') {
      if (_valueA.text.isEmpty || _valueB.text.isEmpty) {
        setState(() => _error = 'Both ends of the range are required');
        return null;
      }
      return TableFilter(column.name, op, [_valueA.text.trim(), _valueB.text.trim()]);
    }
    if (_valueA.text.trim().isEmpty) {
      setState(() => _error = 'Enter a value');
      return null;
    }
    return TableFilter(column.name, op, [_valueA.text.trim()]);
  }

  @override
  Widget build(BuildContext context) {
    final columns = widget.schema.filterable;
    return AlertDialog(
      title: Text(widget.existing == null ? 'Add filter' : 'Edit filter'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DropdownButtonFormField<ColumnDef>(
                decoration: const InputDecoration(labelText: 'Column'),
                initialValue: _column,
                isExpanded: true,
                items: columns
                    .map((c) => DropdownMenuItem(value: c, child: Text(c.label)))
                    .toList(),
                onChanged: (c) => setState(() {
                  _column = c;
                  _operator = c?.operators.firstOrNull;
                  _valueA.clear();
                  _valueB.clear();
                  _enumSelection.clear();
                  _reference = null;
                  _preset = null;
                  _error = null;
                }),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                decoration: const InputDecoration(labelText: 'Condition'),
                initialValue: _operator,
                isExpanded: true,
                items: (_column?.operators ?? const [])
                    .map((o) => DropdownMenuItem(value: o, child: Text(operatorLabel(o))))
                    .toList(),
                onChanged: (o) => setState(() {
                  _operator = o;
                  _error = null;
                }),
              ),
              const SizedBox(height: 12),
              if (!_needsNoValue) _valueEditor(),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Text(_error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            final f = _build();
            if (f != null) Navigator.of(context).pop(f);
          },
          child: const Text('Apply'),
        ),
      ],
    );
  }

  Widget _valueEditor() {
    final column = _column;
    if (column == null) return const SizedBox.shrink();

    switch (column.type) {
      case ColumnType.enumeration:
        if (_operator == 'in' || _operator == 'notIn') {
          return Wrap(
            spacing: 6,
            runSpacing: 6,
            children: column.enumValues
                .map((v) => FilterChip(
                      label: Text(v),
                      selected: _enumSelection.contains(v),
                      onSelected: (on) => setState(() {
                        if (on) {
                          _enumSelection.add(v);
                        } else {
                          _enumSelection.remove(v);
                        }
                      }),
                    ))
                .toList(),
          );
        }
        return DropdownButtonFormField<String>(
          decoration: const InputDecoration(labelText: 'Value'),
          initialValue: _valueA.text.isEmpty ? null : _valueA.text,
          isExpanded: true,
          items: column.enumValues
              .map((v) => DropdownMenuItem(value: v, child: Text(v)))
              .toList(),
          onChanged: (v) => setState(() => _valueA.text = v ?? ''),
        );

      case ColumnType.boolean:
        return DropdownButtonFormField<String>(
          decoration: const InputDecoration(labelText: 'Value'),
          initialValue: _valueA.text.isEmpty ? 'true' : _valueA.text,
          items: const [
            DropdownMenuItem(value: 'true', child: Text('Yes')),
            DropdownMenuItem(value: 'false', child: Text('No')),
          ],
          onChanged: (v) => setState(() => _valueA.text = v ?? 'true'),
        );

      case ColumnType.reference:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (_reference != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Chip(
                  label: Text(_reference!.label),
                  onDeleted: () => setState(() => _reference = null),
                ),
              ),
            ReferencePicker(
              kind: column.referenceKind ?? 'customer',
              value: _reference,
              onChanged: (o) => setState(() => _reference = o),
            ),
          ],
        );

      case ColumnType.date:
        if (_operator == 'relative') {
          return DropdownButtonFormField<String>(
            decoration: const InputDecoration(labelText: 'Period'),
            initialValue: _preset,
            isExpanded: true,
            items: widget.schema.datePresets
                .map((p) => DropdownMenuItem(value: p, child: Text(datePresetLabel(p))))
                .toList(),
            onChanged: (p) => setState(() => _preset = p),
          );
        }
        return Row(
          children: [
            Expanded(child: _dateField(_valueA, _operator == 'between' ? 'From' : 'Date')),
            if (_operator == 'between') ...[
              const SizedBox(width: 8),
              Expanded(child: _dateField(_valueB, 'To')),
            ],
          ],
        );

      default:
        if (_operator == 'between') {
          return Row(
            children: [
              Expanded(child: _numberField(_valueA, 'From')),
              const SizedBox(width: 8),
              Expanded(child: _numberField(_valueB, 'To')),
            ],
          );
        }
        final numeric = column.type == ColumnType.number || column.type == ColumnType.money;
        return numeric
            ? _numberField(_valueA, 'Value')
            : TextField(
                controller: _valueA,
                autofocus: true,
                decoration: const InputDecoration(labelText: 'Value'),
              );
    }
  }

  Widget _numberField(TextEditingController c, String label) => TextField(
        controller: c,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: InputDecoration(labelText: label),
      );

  Widget _dateField(TextEditingController c, String label) => TextField(
        controller: c,
        readOnly: true,
        decoration: InputDecoration(
          labelText: label,
          suffixIcon: const Icon(Icons.calendar_today, size: 18),
        ),
        onTap: () async {
          final picked = await showDatePicker(
            context: context,
            initialDate: DateTime.tryParse(c.text) ?? DateTime.now(),
            firstDate: DateTime(2000),
            lastDate: DateTime(2100),
          );
          if (picked != null) {
            setState(() => c.text = DateFormat('yyyy-MM-dd').format(picked));
          }
        },
      );
}

/// Renders a filter as human-readable text for its chip.
String describeFilter(TableFilter f, TableSchema? schema) {
  final label = schema?.labelFor(f.field) ?? f.field;
  if (f.operator == 'isEmpty') return '$label is empty';
  if (f.operator == 'isNotEmpty') return '$label is set';
  if (f.operator == 'relative') return '$label ${datePresetLabel(f.values.firstOrNull ?? '')}';
  if (f.operator == 'between' && f.values.length == 2) {
    return '$label ${f.values[0]} – ${f.values[1]}';
  }
  return '$label ${operatorLabel(f.operator)} ${f.label ?? f.values.join(', ')}';
}
