import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/user.dart';
import '../auth/auth_controller.dart';

final usersProvider = FutureProvider.autoDispose<List<AppUser>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/users');
  return (res.data as List).cast<Map<String, dynamic>>().map(AppUser.fromJson).toList();
});

final rolesProvider = FutureProvider.autoDispose<List<AppRole>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/roles');
  return (res.data as List).cast<Map<String, dynamic>>().map(AppRole.fromJson).toList();
});

class UsersScreen extends ConsumerWidget {
  const UsersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.userManage) ?? false;
    final async = ref.watch(usersProvider);

    return Scaffold(
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              icon: const Icon(Icons.add),
              label: const Text('New user'),
              onPressed: () => _openForm(context, ref, null),
            )
          : null,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Failed: $e')),
        data: (list) => RefreshIndicator(
          onRefresh: () async => ref.refresh(usersProvider.future),
          child: ListView.separated(
            padding: const EdgeInsets.all(8),
            itemCount: list.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, i) {
              final u = list[i];
              return ListTile(
                leading: CircleAvatar(child: Text(u.username[0].toUpperCase())),
                title: Text(u.fullName?.isNotEmpty == true ? u.fullName! : u.username),
                subtitle: Text('${u.username} • ${u.role ?? "-"}${u.active ? "" : " • inactive"}'),
                trailing: canManage
                    ? IconButton(icon: const Icon(Icons.edit_outlined),
                        onPressed: () => _openForm(context, ref, u))
                    : null,
              );
            },
          ),
        ),
      ),
    );
  }

  Future<void> _openForm(BuildContext context, WidgetRef ref, AppUser? existing) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _UserForm(existing: existing),
    );
    if (saved == true) ref.invalidate(usersProvider);
  }
}

class _UserForm extends ConsumerStatefulWidget {
  final AppUser? existing;
  const _UserForm({this.existing});
  @override
  ConsumerState<_UserForm> createState() => _UserFormState();
}

class _UserFormState extends ConsumerState<_UserForm> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _username;
  late final TextEditingController _email;
  late final TextEditingController _fullName;
  late final TextEditingController _password;
  AppRole? _role;
  late bool _active;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _username = TextEditingController(text: widget.existing?.username ?? '');
    _email = TextEditingController(text: widget.existing?.email ?? '');
    _fullName = TextEditingController(text: widget.existing?.fullName ?? '');
    _password = TextEditingController();
    _active = widget.existing?.active ?? true;
  }

  @override
  void dispose() {
    _username.dispose(); _email.dispose(); _fullName.dispose(); _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_role == null && widget.existing == null) {
      setState(() => _error = 'Pick a role');
      return;
    }
    setState(() { _saving = true; _error = null; });
    try {
      final dio = ref.read(dioProvider);
      if (widget.existing == null) {
        await dio.post('/api/users', data: {
          'username': _username.text.trim(),
          'email': _email.text.trim(),
          'fullName': _fullName.text.trim(),
          'password': _password.text,
          'roleId': _role!.id,
          'active': _active,
        });
      } else {
        await dio.put('/api/users/${widget.existing!.id}', data: {
          'email': _email.text.trim(),
          'fullName': _fullName.text.trim(),
          if (_password.text.isNotEmpty) 'password': _password.text,
          if (_role != null) 'roleId': _role!.id,
          'active': _active,
        });
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
    final roles = ref.watch(rolesProvider);
    final isNew = widget.existing == null;

    return AlertDialog(
      title: Text(isNew ? 'New user' : 'Edit user'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: _username,
                  decoration: const InputDecoration(labelText: 'Username'),
                  enabled: isNew,
                  validator: (v) => isNew && (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(controller: _email, decoration: const InputDecoration(labelText: 'Email')),
                const SizedBox(height: 8),
                TextFormField(controller: _fullName, decoration: const InputDecoration(labelText: 'Full name')),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _password,
                  decoration: InputDecoration(labelText: isNew ? 'Password' : 'New password (leave blank to keep)'),
                  obscureText: true,
                  validator: (v) => isNew && (v == null || v.isEmpty) ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                roles.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text('Failed to load roles: $e'),
                  data: (list) {
                    _role ??= widget.existing?.role == null
                        ? null
                        : list.firstWhere(
                            (r) => r.name == widget.existing!.role,
                            orElse: () => list.first);
                    return DropdownButtonFormField<AppRole>(
                      decoration: const InputDecoration(labelText: 'Role'),
                      value: _role,
                      items: list.map((r) => DropdownMenuItem(value: r, child: Text(r.name))).toList(),
                      onChanged: (r) => setState(() => _role = r),
                    );
                  },
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Active'),
                  value: _active,
                  onChanged: (v) => setState(() => _active = v),
                ),
                if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
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
