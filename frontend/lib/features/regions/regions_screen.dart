import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/region/region_providers.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/route_query.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/privileges.dart';
import '../approvals/approval_limit_providers.dart';
import '../approvals/approval_providers.dart';
import '../auth/auth_controller.dart';
import 'region_limit_editor.dart';

/// The region map: the branches this company is divided into.
///
/// Region is classified NONE on the region axis and REGION_VIEW is company-wide, so this list is
/// the same list for everybody who may see it — it is not itself narrowed by where you work, and
/// it deliberately has no export and no bulk actions because the API offers neither (B1).
class RegionsScreen extends ConsumerWidget {
  final TableQuery query;
  const RegionsScreen({super.key, required this.query});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canManage = ref.watch(canManageRegionsProvider);
    // The branch map is also where a branch's APPROVAL LIMIT lives, because a limit is per region
    // and this is the only screen that lists regions. A column and a dialog rather than a screen
    // and a nav entry of its own: there is nothing about a limit to read that is not about the
    // branch it belongs to (B2).
    //
    // Two privileges, not one. APPROVAL_VIEW is what GET /api/approvals/thresholds/{regionId} is
    // behind, so without it there is no column at all and nothing is asked for; APPROVAL_CONFIGURE
    // is what proposing a change needs, narrowed per row below (B2).
    final canSeeLimits = ref.watch(canSeeApprovalsProvider);
    final canConfigureLimits = ref.watch(canConfigureApprovalLimitsProvider);
    final me = ref.watch(currentUserProvider);
    // Both, deliberately. An editor that cannot READ the limit it replaces is the region grant
    // editor's write-only defect, so a configure-holder without APPROVAL_VIEW is offered no edit
    // control rather than a form that cannot show what it is about to overwrite (B2, B1).
    bool mayEditLimitIn(RegionRef r) =>
        canSeeLimits &&
        canConfigureLimits &&
        (me?.hasIn(Privileges.approvalConfigure, r.id) ?? false);

    return Scaffold(
      body: DataTableScaffold<RegionRef>(
        entity: 'regions',
        path: '/api/regions',
        query: query,
        onQueryChanged: (q) => RouteQuery(context, '/regions').push(q),
        parse: RegionRef.fromJson,
        idOf: (r) => r.id,
        selectable: false,
        emptyMessage: 'No branches match this filter',
        actions: [
          if (canManage)
            FilledButton.icon(
              icon: const Icon(Icons.add),
              label: const Text('New branch'),
              onPressed: () => showRegionForm(context, ref),
            ),
        ],
        columns: [
          TableColumnSpec(
            label: 'Code',
            sortKey: 'code',
            cell: (context, r) =>
                Text(r.code, style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          TableColumnSpec(
            label: 'Name',
            sortKey: 'name',
            maxWidth: 320,
            cell: (context, r) => Tooltip(
              message: r.name,
              child: Text(r.name, maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
          ),
          TableColumnSpec(
            label: 'Active',
            sortKey: 'active',
            // Retiring a branch hides it from the pickers. It never rewrites the accounts that
            // live there and nothing deletes one, so every historical placement stays readable.
            cell: (context, r) => Text(r.active ? 'Yes' : 'Retired'),
          ),
          if (canSeeLimits)
            TableColumnSpec(
              label: 'Approval limit',
              // No sortKey: the limit is not a column of the regions table. It is a row in
              // approval_thresholds resolved per branch — or the deployment default, which is not
              // in any table at all — so the server cannot order regions by it and offering the
              // arrow would produce a 400 (B2).
              maxWidth: 280,
              cell: (context, r) => RegionLimitCell(region: r),
            ),
          TableColumnSpec(
            label: 'Opened',
            sortKey: 'createdAt',
            cell: (context, r) => Text(formatDateTime(r.createdAt)),
          ),
        ],
        // Null rather than an empty list for a reader, so the table does not carry an actions
        // column with nothing in it. A limit-configurer who cannot manage branches still gets the
        // column, because they have one action per row even though they have no Edit (B2).
        rowActions: !canManage && !(canSeeLimits && canConfigureLimits)
            ? null
            : (context, r) => [
                  if (canManage)
                    IconButton(
                      tooltip: 'Edit branch',
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      onPressed: () => showRegionForm(context, ref, existing: r),
                    ),
                  if (mayEditLimitIn(r))
                    IconButton(
                      tooltip: 'Set the approval limit',
                      icon: const Icon(Icons.price_change_outlined, size: 18),
                      onPressed: () => showRegionLimitDialog(context, ref, region: r),
                    ),
                ],
      ),
    );
  }
}

/// Opening a branch, or renaming or retiring one. REGION_MANAGE is company-wide: a branch is not
/// inside another branch, so there is nothing per-region to check here (B1).
Future<void> showRegionForm(BuildContext context, WidgetRef ref, {RegionRef? existing}) async {
  final saved = await showDialog<bool>(
    context: context,
    builder: (_) => _RegionFormDialog(existing: existing),
  );
  if (saved != true) return;
  ref.invalidate(tablePageProvider);
  // The map feeds the selector, both pickers and the customer form, so all of them have to hear
  // about a branch that has just been opened or retired (B1).
  ref.invalidate(regionMapProvider);
  ref.invalidate(myRegionsProvider);
}

class _RegionFormDialog extends ConsumerStatefulWidget {
  final RegionRef? existing;
  const _RegionFormDialog({this.existing});

  @override
  ConsumerState<_RegionFormDialog> createState() => _RegionFormDialogState();
}

class _RegionFormDialogState extends ConsumerState<_RegionFormDialog> {
  late final TextEditingController _code =
      TextEditingController(text: widget.existing?.code ?? '');
  late final TextEditingController _name =
      TextEditingController(text: widget.existing?.name ?? '');
  late bool _active = widget.existing?.active ?? true;
  bool _saving = false;
  String? _error;

  bool get _isCreate => widget.existing == null;

  @override
  void dispose() {
    _code.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_code.text.trim().isEmpty || _name.text.trim().isEmpty) {
      setState(() => _error = 'A branch needs a code and a name');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final dio = ref.read(dioProvider);
      final body = {
        'code': _code.text.trim(),
        'name': _name.text.trim(),
        'active': _active,
      };
      if (_isCreate) {
        await dio.post('/api/regions', data: body);
      } else {
        await dio.put('/api/regions/${widget.existing!.id}', data: body);
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
    return AlertDialog(
      title: Text(_isCreate ? 'New branch' : 'Edit branch'),
      content: SizedBox(
        width: MediaQuery.sizeOf(context).width < 600 ? double.maxFinite : 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: _code,
              autofocus: _isCreate,
              // The server upper-cases and trims it, because uk_region_code is an exact match
              // and "north" and "NORTH" must not be two branches (B1).
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: 'Code *',
                helperText: 'Short, unique, upper case — NORTH, HQ',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Name *'),
            ),
            const SizedBox(height: 4),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              value: _active,
              title: const Text('Open for new work'),
              subtitle: const Text(
                  'A retired branch keeps its accounts and its history; nothing new is opened '
                  'in one.'),
              onChanged: (v) => setState(() => _active = v ?? true),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(false),
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
