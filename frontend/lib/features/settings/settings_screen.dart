import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/unsaved_changes.dart';

/// The administrator-maintained application-wide admin email: the From fallback a send uses
/// when the selected role (or named user) has no address of its own. May be left unset; an
/// unset installation only fails sends that actually need the fallback.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  final _adminEmail = TextEditingController();
  late final UnsavedChanges _unsaved;
  bool _loaded = false;
  bool _dirty = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // Navigation cannot silently discard an edit: the route's onExit and goGuarded both ask.
    _unsaved = ref.read(unsavedChangesProvider)..register(_confirmDiscard);    _load();
  }

  @override
  void dispose() {
    _unsaved.unregister(_confirmDiscard);
    _adminEmail.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final res = await ref.read(dioProvider).get('/api/settings/admin-email');
      final value = (res.data as Map)['adminEmail'] as String?;
      if (!mounted || _loaded) return;
      setState(() {
        _loaded = true;
        _adminEmail.text = value ?? '';
      });
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    }
  }

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Discard unsaved changes?'),
        content:
            const Text('You have edits to the settings that have not been saved.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Keep editing')),
          FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Discard')),
        ],
      ),
    );
    if (ok == true) _dirty = false;
    return ok == true;
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(dioProvider).put('/api/settings/admin-email', data: {
        'adminEmail': _adminEmail.text.trim(),
      });
      setState(() => _dirty = false);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Settings saved')));
      }
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded && _error == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Settings', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 16),
              TextFormField(
                controller: _adminEmail,
                decoration: const InputDecoration(
                  labelText: 'Admin email address',
                  hintText: 'Fallback From address for application Emails',
                ),
                inputFormatters: [
                  LengthLimitingTextInputFormatter(FieldLimits.email)
                ],
                onChanged: (_) => setState(() => _dirty = true),
              ),
              const SizedBox(height: 8),
              Text(
                'Used as the From address of a stored Email when the selected role or user '
                'has no address of its own. Leave empty to disable the fallback.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!,
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
              const SizedBox(height: 16),
              Row(
                children: [
                  if (_dirty)
                    Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: Text('Unsaved changes',
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.tertiary)),
                    ),
                  FilledButton.icon(
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('Save'),
                    onPressed: (!_dirty || _saving) ? null : _save,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
