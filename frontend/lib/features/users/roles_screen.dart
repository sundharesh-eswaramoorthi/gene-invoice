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
import '../auth/auth_controller.dart';
import '../email/email_actions.dart';
import 'users_screen.dart';

final allPrivilegesProvider = FutureProvider.autoDispose<List<String>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/privileges');
  return (res.data as List).map((e) => (e as Map<String, dynamic>)['name'] as String).toList()
    ..sort();
});

final roleDetailProvider = FutureProvider.autoDispose.family<AppRole, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/roles/$id');
  return AppRole.fromJson(res.data as Map<String, dynamic>);
});

Future<void> openRoleForm(BuildContext context, WidgetRef ref, {AppRole? existing}) async {
  final saved = await showDialog<({int id, bool notify})>(
    context: context,
    builder: (_) => RoleFormDialog(existing: existing),
  );
  if (saved == null) return;
  ref.invalidate(rolesProvider);
  ref.invalidate(tablePageProvider);
  ref.invalidate(roleDetailProvider(saved.id));
  ref.invalidate(userDetailProvider);
  if (!context.mounted) return;
  await notifyByEmailAfterSave(context,
      notify: saved.notify,
      type: EmailEntityType.role,
      entityId: saved.id,
      event: EmailEvent.created);
}

class RolesScreen extends ConsumerWidget {
  final TableQuery query;
  const RolesScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(currentUserProvider);
    final canManage = user?.has(Privileges.roleManage) ?? false;
    final canExport = user?.has(Privileges.exportData) ?? false;
    final canSendEmail = ref.watch(canSendEmailProvider);
    final sendEmail = sendEmailPageAction(context, ref, type: EmailEntityType.role);

    return Scaffold(
      body: DataTableScaffold<AppRole>(
        entity: 'roles',
        actions: [
          if (sendEmail != null) sendEmail,
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New role'),
              onPressed: () => openRoleForm(context, ref),
            ),
        ],
        path: '/api/roles',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/roles').push(q),
        parse: AppRole.fromJson,
        idOf: (r) => r.id,
        canExport: canExport,
        emptyMessage: 'No roles match this filter',
        onRowTap: (context, r) => context.go('/roles/${r.id}'),
        bulkActions: [
          if (canSendEmail) sendEmailBulkAction(EmailEntityType.role),
        ],
        columns: [
          TableColumnSpec(
            label: 'Name',
            sortKey: 'name',
            cell: (context, r) =>
                Text(r.name, style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          TableColumnSpec(
              label: 'Description', cell: (context, r) => Text(r.description ?? '—')),
          TableColumnSpec(
            label: 'Privileges',
            cell: (context, r) => Text('${r.privileges.length}'),
            numeric: true,
          ),
        ],
        rowActions: canManage || canSendEmail
            ? (context, r) => [
                  if (canManage)
                    IconButton(
                      tooltip: 'Edit',
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      onPressed: () => openRoleForm(context, ref, existing: r),
                    ),
                  sendEmailRowAction(context,
                      type: EmailEntityType.role,
                      entityId: r.id,
                      entityLabel: 'Role ${r.name}'),
                ]
            : null,
      ),
    );
  }
}

class RoleFormDialog extends ConsumerStatefulWidget {
  final AppRole? existing;
  const RoleFormDialog({super.key, this.existing});
  @override
  ConsumerState<RoleFormDialog> createState() => _RoleFormDialogState();
}

class _RoleFormDialogState extends ConsumerState<RoleFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _description;
  late Set<String> _selected;
  bool _notify = false;
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
      final int id;
      if (widget.existing == null) {
        final res = await dio.post('/api/roles', data: body);
        id = ((res.data as Map)['id'] as num).toInt();
      } else {
        id = widget.existing!.id;
        await dio.put('/api/roles/$id', data: body);
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
    final privs = ref.watch(allPrivilegesProvider);
    return AlertDialog(
      title: Text(widget.existing == null ? 'New role' : 'Edit role'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Flexible(
                child: SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      TextFormField(
                        controller: _name,
                        decoration: const InputDecoration(labelText: 'Name'),
                        inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.roleName)],
                        validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
                      ),
                      const SizedBox(height: 8),
                      TextFormField(
                        controller: _description,
                        decoration: const InputDecoration(labelText: 'Description'),
                        inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.roleDescription)],
                      ),
                      const SizedBox(height: 12),
                      Text('Privileges', style: Theme.of(context).textTheme.titleSmall),
                      privs.when(
                        loading: () => const Padding(
                            padding: EdgeInsets.all(12), child: LinearProgressIndicator()),
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
                    ],
                  ),
                ),
              ),
              if (widget.existing == null)
                NotifyByEmailCheckbox(
                  value: _notify,
                  onChanged: (v) => setState(() => _notify = v),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ],
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
