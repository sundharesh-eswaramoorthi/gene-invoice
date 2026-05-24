import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/user.dart';
import '../auth/auth_controller.dart';
import 'users_screen.dart';

final allPrivilegesProvider = FutureProvider.autoDispose<List<String>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/privileges');
  return (res.data as List).map((e) => (e as Map<String, dynamic>)['name'] as String).toList()
    ..sort();
});

class RolesScreen extends ConsumerWidget {
  const RolesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.roleManage) ?? false;
    final async = ref.watch(rolesProvider);

    return Scaffold(
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              icon: const Icon(Icons.add),
              label: const Text('New role'),
              onPressed: () => _openForm(context, ref, null),
            )
          : null,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) => RefreshIndicator(
          onRefresh: () async => ref.refresh(rolesProvider.future),
          child: ListView.separated(
            padding: const EdgeInsets.all(8),
            itemCount: list.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, i) {
              final r = list[i];
              return ListTile(
                title: Text(r.name),
                subtitle: Text(r.description ?? ''),
                trailing: Wrap(
                  spacing: 8,
                  children: [
                    Text('${r.privileges.length} privs',
                        style: const TextStyle(color: Colors.black54)),
                    if (canManage)
                      IconButton(
                        icon: const Icon(Icons.edit_outlined),
                        onPressed: () => _openForm(context, ref, r),
                      ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Future<void> _openForm(BuildContext context, WidgetRef ref, AppRole? existing) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _RoleForm(existing: existing),
    );
    if (saved == true) ref.invalidate(rolesProvider);
  }
}

class _RoleForm extends ConsumerStatefulWidget {
  final AppRole? existing;
  const _RoleForm({this.existing});
  @override
  ConsumerState<_RoleForm> createState() => _RoleFormState();
}

class _RoleFormState extends ConsumerState<_RoleForm> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _description;
  late Set<String> _selected;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.existing?.name ?? '');
    _description = TextEditingController(text: widget.existing?.description ?? '');
    _selected = (widget.existing?.privileges ?? const []).toSet();
  }

  @override
  void dispose() {
    _name.dispose(); _description.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() { _saving = true; _error = null; });
    try {
      final dio = ref.read(dioProvider);
      final body = {
        'name': _name.text.trim(),
        'description': _description.text.trim(),
        'privileges': _selected.toList(),
      };
      if (widget.existing == null) {
        await dio.post('/api/roles', data: body);
      } else {
        await dio.put('/api/roles/${widget.existing!.id}', data: body);
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final privs = ref.watch(allPrivilegesProvider);
    return AlertDialog(
      title: Text(widget.existing == null ? 'New role' : 'Edit role'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextFormField(
                  controller: _name,
                  decoration: const InputDecoration(labelText: 'Name'),
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(controller: _description, decoration: const InputDecoration(labelText: 'Description')),
                const SizedBox(height: 12),
                Text('Privileges', style: Theme.of(context).textTheme.titleSmall),
                privs.when(
                  loading: () => const Padding(padding: EdgeInsets.all(12), child: LinearProgressIndicator()),
                  error: (e, _) => Text('Failed: $e'),
                  data: (list) => Column(
                    children: list.map((name) {
                      return CheckboxListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(name),
                        value: _selected.contains(name),
                        onChanged: (v) => setState(() {
                          if (v == true) {
                            _selected.add(name);
                          } else {
                            _selected.remove(name);
                          }
                        }),
                      );
                    }).toList(),
                  ),
                ),
                if (_error != null) Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _saving ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Save'),
        ),
      ],
    );
  }
}
