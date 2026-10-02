import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/scope/view_scope.dart';
import '../../core/table/data_table_scaffold.dart';
import '../../core/table/table_models.dart';
import '../../core/table/table_providers.dart';
import '../auth/auth_controller.dart';
import 'task_actions.dart';
import 'task_form_dialog.dart';
import 'task_models.dart';
import 'task_providers.dart';
import 'tasks_screen.dart';

/// A record's tasks (A6), with "New task". The page, sort and filters live here rather than in the
/// URL, which already names the record's page and tab. A customer's tab ([customerScope]) asks by
/// customer, so the tasks on its invoices and payments are there too (§5.9.5).
class TasksTab extends ConsumerStatefulWidget {
  final TaskEntityType entityType;
  final int entityId;
  final bool customerScope;
  final String? entityLabel;

  /// The record's region, which creating a task on it needs the right in (§7.5).
  final int? regionId;

  const TasksTab({
    super.key,
    required this.entityType,
    required this.entityId,
    this.customerScope = false,
    this.entityLabel,
    this.regionId,
  });

  @override
  ConsumerState<TasksTab> createState() => _TasksTabState();
}

class _TasksTabState extends ConsumerState<TasksTab> {
  late TableQuery _query = TableQuery(
    size: ref.read(pageSizeStoreProvider)['tasks'] ?? 20,
    sort: 'dueDate,asc',
  );

  bool _creating = false;

  Map<String, dynamic> get _context => widget.customerScope
      ? {'customerId': widget.entityId}
      : {'entityType': widget.entityType.wire, 'entityId': widget.entityId};

  Future<void> _create() async {
    setState(() => _creating = true);
    final messenger = ScaffoldMessenger.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    final saved = await showTaskDialog(context,
        entityType: widget.entityType, entityId: widget.entityId, entityLabel: widget.entityLabel);
    if (mounted) setState(() => _creating = false);
    if (saved == null) return;
    tasksChanged(container, id: saved.id);
    messenger.showSnackBar(const SnackBar(content: Text('Task created')));
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(currentUserProvider);
    final historical = ref.watch(isHistoricalProvider);
    final canCreate = ref.watch(canCreateTaskInProvider(widget.regionId));

    return DataTableScaffold<TaskItem>(
      entity: 'tasks',
      path: '/api/tasks',
      query: _query,
      extraParams: _context,
      onQueryChanged: (q) => setState(() => _query = q),
      parse: TaskItem.fromJson,
      idOf: (t) => t.id,
      selectable: false,
      emptyMessage: 'No tasks match this filter',
      onRowTap: (context, t) => context.go('/tasks/${t.id}'),
      actions: [
        if (canCreate)
          FilledButton.icon(
            key: const ValueKey('task-new'),
            icon: const Icon(Icons.add_task, size: 18),
            label: const Text('New task'),
            onPressed: _creating ? null : _create,
          ),
      ],
      // A customer's tab gathers its invoices' and payments' tasks too, so each says whose it is.
      columns: taskColumns(context,
          user: user, showRecord: widget.customerScope, showCustomer: false),
      rowActions: (context, t) => taskRowActions(context, t, user: user, historical: historical),
      mobileCard: (context, t) => TaskCard(task: t),
    );
  }
}
