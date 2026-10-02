import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import 'regions_screen.dart';

/// Opens the region form, and on a save refreshes everything that lists regions. Returns the
/// saved region's id.
Future<int?> openRegionForm(BuildContext context, WidgetRef ref, {Region? existing}) async {
  final saved = await showDialog<int>(
    context: context,
    builder: (_) => RegionFormDialog(existing: existing),
  );
  if (saved != null) await regionsChanged(ref, id: saved);
  return saved;
}

/// New (code, name, active) or edit (name, active). The code is the region's key and never
/// changes once made (§6.3).
class RegionFormDialog extends ConsumerStatefulWidget {
  final Region? existing;
  const RegionFormDialog({super.key, this.existing});

  @override
  ConsumerState<RegionFormDialog> createState() => _RegionFormDialogState();
}

class _RegionFormDialogState extends ConsumerState<RegionFormDialog> {
  static const _codeLength = 20;
  static const _nameLength = 120;
  static final _codePattern = RegExp(r'^[A-Z0-9_-]{2,20}$');

  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _code;
  late final TextEditingController _name;
  late bool _active;
  bool _saving = false;
  String? _error;
  Map<String, String> _fieldErrors = const {};

  bool get _isNew => widget.existing == null;

  @override
  void initState() {
    super.initState();
    _code = TextEditingController(text: widget.existing?.code ?? '');
    _name = TextEditingController(text: widget.existing?.name ?? '');
    _active = widget.existing?.active ?? true;
  }

  @override
  void dispose() {
    _code.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() => _fieldErrors = const {});
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      final Response res;
      if (_isNew) {
        res = await dio.post('/api/regions', data: {
          'code': _code.text.trim(),
          'name': _name.text.trim(),
          'active': _active,
        });
      } else {
        res = await dio.put('/api/regions/${widget.existing!.id}', data: {
          'name': _name.text.trim(),
          'active': _active,
          'version': widget.existing!.version,
        });
      }
      final id = ((res.data as Map)['id'] as num?)?.toInt() ?? widget.existing?.id;
      if (mounted) Navigator.of(context).pop(id);
    } catch (e) {
      final fields = _fieldErrorsOf(e);
      setState(() {
        _fieldErrors = fields;
        // A complaint about a field reads under that field; anything else reads below.
        _error = fields.keys.every({'code', 'name'}.contains) && fields.isNotEmpty
            ? null
            : apiErrorMessage(e);
      });
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  static Map<String, String> _fieldErrorsOf(Object error) {
    final data = error is DioException ? error.response?.data : null;
    final fields = data is Map ? data['fieldErrors'] : null;
    if (fields is! Map) return const {};
    return {for (final e in fields.entries) '${e.key}': '${e.value}'};
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isNew ? 'New region' : 'Edit region ${widget.existing!.code}'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  key: const ValueKey('region-code'),
                  controller: _code,
                  enabled: _isNew,
                  decoration: InputDecoration(
                    labelText: 'Code',
                    helperText: _isNew ? 'Capitals, digits, - and _; cannot be changed later' : null,
                    errorText: _fieldErrors['code'],
                  ),
                  textCapitalization: TextCapitalization.characters,
                  inputFormatters: [
                    LengthLimitingTextInputFormatter(_codeLength),
                    TextInputFormatter.withFunction((old, value) =>
                        value.copyWith(text: value.text.toUpperCase())),
                  ],
                  validator: (v) {
                    if (!_isNew) return null;
                    final code = (v ?? '').trim();
                    if (code.isEmpty) return 'Required';
                    if (!_codePattern.hasMatch(code)) {
                      return '2 to 20 capitals, digits, - or _';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 8),
                TextFormField(
                  key: const ValueKey('region-name'),
                  controller: _name,
                  decoration:
                      InputDecoration(labelText: 'Name', errorText: _fieldErrors['name']),
                  inputFormatters: [LengthLimitingTextInputFormatter(_nameLength)],
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Active'),
                  subtitle: const Text('An inactive region takes no new customers'),
                  value: _active,
                  onChanged: (v) => setState(() => _active = v),
                ),
                if (_error != null)
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
