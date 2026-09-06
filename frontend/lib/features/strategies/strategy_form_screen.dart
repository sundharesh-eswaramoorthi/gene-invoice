import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/invoice.dart';
import '../../shared/models/notification_strategy.dart';
import 'strategies_providers.dart';

/// Create/edit form for a notification strategy. Local validation mirrors the backend's
/// requiredness rules (title, at least one status, complete date and amount predicates,
/// optional description); the backend remains the authoritative validator.
class StrategyFormScreen extends ConsumerStatefulWidget {
  final NotificationStrategy? existing;
  const StrategyFormScreen({super.key, this.existing});

  @override
  ConsumerState<StrategyFormScreen> createState() => _StrategyFormScreenState();
}

class _StrategyFormScreenState extends ConsumerState<StrategyFormScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _title;
  late final TextEditingController _description;
  late final TextEditingController _amountFrom;
  late final TextEditingController _amountTo;
  final Set<String> _statuses = {};
  late String _dateOperator;
  DateTime? _dateFrom;
  DateTime? _dateTo;
  late String _amountOperator;
  final Set<int> _recipientIds = {};
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _title = TextEditingController(text: e?.title ?? '');
    _description = TextEditingController(text: e?.description ?? '');
    _amountFrom =
        TextEditingController(text: e == null ? '' : e.amountFrom.toString());
    _amountTo =
        TextEditingController(text: e?.amountTo == null ? '' : e!.amountTo.toString());
    _statuses.addAll(e?.statuses ?? const []);
    _dateOperator = e?.dateOperator ?? 'AFTER';
    _dateFrom = e?.dateFrom;
    _dateTo = e?.dateTo;
    _amountOperator = e?.amountOperator ?? 'GREATER_THAN';
    _recipientIds.addAll(e?.additionalRecipientUserIds ?? const []);
  }

  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    _amountFrom.dispose();
    _amountTo.dispose();
    super.dispose();
  }

  Future<void> _pickDate(bool isFrom) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: (isFrom ? _dateFrom : _dateTo) ?? now,
      firstDate: DateTime(2000),
      lastDate: DateTime(2100),
    );
    if (picked != null) {
      setState(() => isFrom ? _dateFrom = picked : _dateTo = picked);
    }
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_statuses.isEmpty) {
      setState(() => _error = 'Select at least one invoice status');
      return;
    }
    if (_dateFrom == null) {
      setState(() => _error = 'Pick the date for the date predicate');
      return;
    }
    if (_dateOperator == 'BETWEEN' && _dateTo == null) {
      setState(() => _error = 'A date-between predicate requires an end date');
      return;
    }
    final amountFrom = double.tryParse(_amountFrom.text.trim());
    if (amountFrom == null) {
      setState(() => _error = 'Enter the amount for the amount predicate');
      return;
    }
    final amountTo = double.tryParse(_amountTo.text.trim());
    if (_amountOperator == 'BETWEEN' && amountTo == null) {
      setState(() => _error = 'An amount-between predicate requires an end amount');
      return;
    }

    final draft = StrategyDraft(
      title: _title.text.trim(),
      description: _description.text.trim(),
      statuses: _statuses.toList(),
      dateOperator: _dateOperator,
      dateFrom: _dateFrom!,
      dateTo: _dateOperator == 'BETWEEN' ? _dateTo : null,
      amountOperator: _amountOperator,
      amountFrom: amountFrom,
      amountTo: _amountOperator == 'BETWEEN' ? amountTo : null,
      additionalRecipientUserIds: _recipientIds.toList(),
    );

    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      if (widget.existing == null) {
        await dio.post('/api/notification-strategies', data: draft.toJson());
      } else {
        await dio.put('/api/notification-strategies/${widget.existing!.id}',
            data: draft.toJson());
      }
      ref.invalidate(strategiesProvider);
      if (mounted) context.pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isNew = widget.existing == null;
    final statuses = ref.watch(strategyStatusesProvider);
    final recipients = ref.watch(strategyRecipientsProvider);
    final dayFormat = DateFormat.yMMMd();

    return Scaffold(
      appBar: AppBar(title: Text(isNew ? 'New strategy' : 'Edit strategy')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextFormField(
              controller: _title,
              decoration: const InputDecoration(labelText: 'Title *'),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? 'Title is required' : null,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _description,
              decoration:
                  const InputDecoration(labelText: 'Description (optional)'),
              maxLines: 2,
            ),
            const SizedBox(height: 16),
            Text('Invoice statuses *',
                style: Theme.of(context).textTheme.titleSmall),
            statuses.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text('Failed to load statuses: $e'),
              data: (list) => Wrap(
                spacing: 8,
                children: [
                  for (final s in list)
                    FilterChip(
                      label: Text(statusLabel2(s)),
                      selected: _statuses.contains(s),
                      onSelected: (v) => setState(
                          () => v ? _statuses.add(s) : _statuses.remove(s)),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text('Invoice date predicate *',
                style: Theme.of(context).textTheme.titleSmall),
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    decoration: const InputDecoration(labelText: 'Operator'),
                    value: _dateOperator,
                    items: [
                      for (final e in dateOperatorLabels.entries)
                        DropdownMenuItem(value: e.key, child: Text(e.value)),
                    ],
                    onChanged: (v) => setState(() => _dateOperator = v!),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.calendar_today_outlined),
                    label: Text(_dateFrom == null
                        ? 'Date *'
                        : dayFormat.format(_dateFrom!)),
                    onPressed: () => _pickDate(true),
                  ),
                ),
                if (_dateOperator == 'BETWEEN') ...[
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.calendar_today_outlined),
                      label: Text(_dateTo == null
                          ? 'End date *'
                          : dayFormat.format(_dateTo!)),
                      onPressed: () => _pickDate(false),
                    ),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 16),
            Text('Invoice total predicate *',
                style: Theme.of(context).textTheme.titleSmall),
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    decoration: const InputDecoration(labelText: 'Operator'),
                    value: _amountOperator,
                    items: [
                      for (final e in amountOperatorLabels.entries)
                        DropdownMenuItem(value: e.key, child: Text(e.value)),
                    ],
                    onChanged: (v) => setState(() => _amountOperator = v!),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextFormField(
                    controller: _amountFrom,
                    decoration: const InputDecoration(labelText: 'Amount *'),
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    inputFormatters: [
                      FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
                    ],
                    validator: (v) => double.tryParse((v ?? '').trim()) == null
                        ? 'Required'
                        : null,
                  ),
                ),
                if (_amountOperator == 'BETWEEN') ...[
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: _amountTo,
                      decoration:
                          const InputDecoration(labelText: 'End amount *'),
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      inputFormatters: [
                        FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
                      ],
                      validator: (v) => _amountOperator == 'BETWEEN' &&
                              double.tryParse((v ?? '').trim()) == null
                          ? 'Required'
                          : null,
                    ),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 16),
            Text('Additional recipients (non-customer users, optional)',
                style: Theme.of(context).textTheme.titleSmall),
            recipients.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text('Failed to load users: $e'),
              data: (list) => Column(
                children: [
                  for (final u in list)
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(u.fullName?.isNotEmpty == true
                          ? u.fullName!
                          : u.username),
                      subtitle: Text('${u.username} • ${u.role}'),
                      value: _recipientIds.contains(u.id),
                      onChanged: (v) => setState(() =>
                          v == true
                              ? _recipientIds.add(u.id)
                              : _recipientIds.remove(u.id)),
                    ),
                ],
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Text(_error!,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _saving ? null : _submit,
              child: _saving
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(isNew ? 'Create strategy' : 'Save changes'),
            ),
          ],
        ),
      ),
    );
  }
}

String statusLabel2(String name) {
  final parsed = InvoiceStatus.values.asNameMap()[name];
  return parsed == null ? name : statusLabel(parsed);
}
