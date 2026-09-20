import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../shared/models/privileges.dart';
import '../../shared/models/user.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../audit/audit_history_panel.dart';
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';

final usersProvider = FutureProvider.autoDispose<List<AppUser>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/users', queryParameters: {'size': 50, 'sort': 'username,asc'});
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(AppUser.fromJson)
      .toList();
});

final rolesProvider = FutureProvider.autoDispose<List<AppRole>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/roles', queryParameters: {'size': 50, 'sort': 'name,asc'});
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(AppRole.fromJson)
      .toList();
});

final userDetailProvider = FutureProvider.autoDispose.family<AppUser, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/users/$id');
  return AppUser.fromJson(res.data as Map<String, dynamic>);
});

/// Opens the user form, for a new user or [existing], from the list or the details page. Once it
/// saves, whatever shows the user is refreshed, and a new user the admin asked to notify gets the
/// compose dialog — opened on [context], since the form's own is gone.
Future<void> openUserForm(BuildContext context, WidgetRef ref, {AppUser? existing}) async {
  final saved = await showDialog<({int id, bool notify})>(
    context: context,
    builder: (_) => UserFormDialog(existing: existing),
  );
  if (saved == null) return;
  ref.invalidate(usersProvider);
  ref.invalidate(tablePageProvider);
  ref.invalidate(userDetailProvider(saved.id));
  ref.invalidate(auditHistoryProvider);
  if (!context.mounted) return;
  await notifyByEmailAfterSave(context,
      notify: saved.notify,
      type: EmailEntityType.user,
      entityId: saved.id,
      event: EmailEvent.created);
}

class UsersScreen extends ConsumerWidget {
  final TableQuery query;
  const UsersScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.userManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSendEmail = ref.watch(canSendEmailProvider);
    final sendEmail = sendEmailPageAction(context, ref, type: EmailEntityType.user);

    return Scaffold(
      body: DataTableScaffold<AppUser>(
        entity: 'users',
        actions: [
          if (sendEmail != null) sendEmail,
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New user'),
              onPressed: () => openUserForm(context, ref),
            ),
        ],
        path: '/api/users',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/users').push(q),
        parse: AppUser.fromJson,
        idOf: (u) => u.id,
        canExport: canExport,
        emptyMessage: 'No users match this filter',
        onRowTap: (context, u) => context.go('/users/${u.id}'),
        bulkActions: [
          if (canManage) ...const [
            BulkActionSpec(
                action: 'ACTIVATE', label: 'Activate', icon: Icons.check_circle_outline),
            BulkActionSpec(
                action: 'DEACTIVATE', label: 'Deactivate', icon: Icons.block, destructive: true),
          ],
          if (canSendEmail) sendEmailBulkAction(EmailEntityType.user),
        ],
        columns: [
          TableColumnSpec(
            label: 'Username',
            sortKey: 'username',
            cell: (context, u) => Text(u.username,
                style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          TableColumnSpec(
              label: 'Full name',
              sortKey: 'fullName',
              cell: (context, u) => Text(u.fullName ?? '—')),
          TableColumnSpec(
              label: 'Email', sortKey: 'email', cell: (context, u) => Text(u.email ?? '—')),
          TableColumnSpec(
              label: 'Role', sortKey: 'roleName', cell: (context, u) => Text(u.role ?? '—')),
          TableColumnSpec(
              label: 'Active',
              sortKey: 'active',
              cell: (context, u) => Text(u.active ? 'Yes' : 'No')),
        ],
        // Someone who may neither edit nor send email gets no empty action column.
        rowActions: canManage || canSendEmail
            ? (context, u) => [
                  if (canManage)
                    IconButton(
                      tooltip: 'Edit',
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      onPressed: () => openUserForm(context, ref, existing: u),
                    ),
                  sendEmailRowAction(context,
                      type: EmailEntityType.user,
                      entityId: u.id,
                      entityLabel: 'User ${u.username}'),
                ]
            : null,
      ),
    );
  }
}

/// Create / edit form for a user. Pops the saved user's id, and whether to write an email about
/// it, so the caller can follow up once this dialog is gone; open it with [openUserForm].
class UserFormDialog extends ConsumerStatefulWidget {
  final AppUser? existing;
  const UserFormDialog({super.key, this.existing});
  @override
  ConsumerState<UserFormDialog> createState() => _UserFormDialogState();
}

class _UserFormDialogState extends ConsumerState<UserFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _username;
  late final TextEditingController _email;
  late final TextEditingController _fullName;
  late final TextEditingController _password;
  AppRole? _role;
  late bool _active;
  bool _notify = false;
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
      final int id;
      if (widget.existing == null) {
        final res = await dio.post('/api/users', data: {
          'username': _username.text.trim(),
          'email': _email.text.trim(),
          'fullName': _fullName.text.trim(),
          'password': _password.text,
          'roleId': _role!.id,
          'active': _active,
        });
        id = ((res.data as Map)['id'] as num).toInt();
      } else {
        id = widget.existing!.id;
        await dio.put('/api/users/$id', data: {
          'email': _email.text.trim(),
          'fullName': _fullName.text.trim(),
          if (_password.text.isNotEmpty) 'password': _password.text,
          if (_role != null) 'roleId': _role!.id,
          'active': _active,
        });
      }
      if (mounted) Navigator.of(context).pop((id: id, notify: _notify));
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
                  inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.username)],
                  validator: (v) => isNew && (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _email,
                  decoration: const InputDecoration(labelText: 'Email'),
                  inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.email)],
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _fullName,
                  decoration: const InputDecoration(labelText: 'Full name'),
                  inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.fullName)],
                ),
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
                      initialValue: _role,
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
                if (isNew)
                  NotifyByEmailCheckbox(
                    value: _notify,
                    onChanged: (v) => setState(() => _notify = v),
                  ),
                if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _saving ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Save'),
        ),
      ],
    );
  }
}
