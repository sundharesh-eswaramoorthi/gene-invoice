import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/format.dart';
import '../../core/unsaved_changes.dart';
import '../../shared/models/auth_models.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../../shared/widgets/status_chip.dart';
import '../auth/auth_controller.dart';
import 'task_form_dialog.dart';
import 'task_models.dart';
import 'task_providers.dart';
import 'tasks_screen.dart';

/// One task's own page. A task changes only through its dialog and its three buttons, so the top
/// is read-only and there is nothing unsaved to guard (A6).
///
/// There is deliberately NO History tab: "TASK" is not in AuditController.SUPPORTED, so
/// GET /api/audit?entityType=TASK is refused. The audit rows TaskService writes exist and are
/// simply not reachable through the API in Part A, and a tab that could only ever show an error is
/// worse than no tab (A6).
class TaskDetailScreen extends ConsumerWidget {
  final int id;
  final String? initialTab;
  const TaskDetailScreen({super.key, required this.id, this.initialTab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(taskDetailProvider(id));
    final user = ref.watch(currentUserProvider);

    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => RecordUnavailable(
        message: notFoundMessage(e, 'task'),
        onBack: () => context.go('/tasks'),
      ),
      data: (t) {
        // hasIn, not has: a task hangs off one account in one branch, and TASK_MANAGE held
        // elsewhere is not permission here. TASK_MANAGE is already MANAGE-level in auth_models'
        // region map — R10 shipped it and nothing is re-declared (A6, B1).
        final canManage = user?.hasIn(Privileges.taskManage, t.regionId) ?? false;
        final live = !t.status.terminal;

        return DetailScaffold(
          title: t.title,
          subtitle: [
            t.recordLabel,
            if ((t.customerName ?? '').isNotEmpty) t.customerName!,
            if (t.dueDate != null) 'due ${formatDate(t.dueDate)}',
          ].join(' • '),
          onBack: () => goGuarded(context, '/tasks'),
          titleTrailing: [
            TaskStatusChip(status: t.status),
            if (t.overdue) const OverdueBadge(daysOverdue: 0),
            if (canManage && live)
              TextButton.icon(
                icon: const Icon(Icons.check_circle_outline, size: 18),
                label: const Text('Complete'),
                onPressed: () => _run(context, ref, () => completeTask(ref, t.id),
                    'Task marked complete'),
              ),
            if (canManage && live)
              TextButton.icon(
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Edit'),
                onPressed: () => showTaskDialog(
                    context: context, existing: t, regionId: t.regionId),
              ),
            if (canManage && live)
              TextButton.icon(
                icon: const Icon(Icons.cancel_outlined, size: 18),
                style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                label: const Text('Cancel'),
                onPressed: () async {
                  final confirmed = await showDialog<bool>(
                    context: context,
                    builder: (dialogContext) => AlertDialog(
                      title: const Text('Cancel this task?'),
                      content: const Text(
                          'It stays on the record as called off. This cannot be undone.'),
                      actions: [
                        TextButton(
                            onPressed: () => Navigator.of(dialogContext).pop(false),
                            child: const Text('Keep it')),
                        FilledButton(
                          style: FilledButton.styleFrom(
                              backgroundColor: Theme.of(dialogContext).colorScheme.error),
                          onPressed: () => Navigator.of(dialogContext).pop(true),
                          child: const Text('Cancel task'),
                        ),
                      ],
                    ),
                  );
                  if (confirmed != true || !context.mounted) return;
                  await _run(context, ref, () => cancelTask(ref, t), 'Task cancelled');
                },
              ),
          ],
          initialTabSlug: initialTab,
          onTabChanged: (slug) => context.go('/tasks/$id?tab=$slug'),
          top: _top(context, ref, t, user: user),
          tabs: [
            DetailTab(
              slug: 'notes',
              label: 'Notes',
              icon: Icons.notes_outlined,
              builder: (context) => SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text((t.notes ?? '').isEmpty ? 'No notes on this task.' : t.notes!),
                    const SizedBox(height: 16),
                    const Divider(),
                    const SizedBox(height: 8),
                    DetailGrid(items: [
                      DetailGridItem(
                          label: 'Created', child: Text(formatDateTime(t.createdAt))),
                      DetailGridItem(
                          label: 'Last changed', child: Text(formatDateTime(t.updatedAt))),
                      DetailGridItem(
                        label: 'Completed',
                        child: Text(t.completedAt == null ? '—' : formatDateTime(t.completedAt)),
                      ),
                    ]),
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Future<void> _run(BuildContext context, WidgetRef ref, Future<void> Function() action,
      String done) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await action();
      messenger.showSnackBar(SnackBar(content: Text(done)));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
    }
  }

  Widget _top(BuildContext context, WidgetRef ref, Task t, {required CurrentUser? user}) {
    final mayOpenCustomer = t.customerId != null &&
        (user?.hasIn(Privileges.customerView, t.regionId) ?? false);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          // Where the task came from. A rule that made it is a fact about the task, not a note:
          // it says why somebody is being asked to do this and nobody remembers asking.
          //
          // A-UI has now declared /automation/rules/:id, so this is a LINK — but only for
          // somebody holding AUTOMATION_VIEW, because the route is behind that privilege and
          // offering a link the router would bounce them off is worse than plain text. The DTO
          // still carries the rule's id and not its name, which is why the text reads "#7"
          // (A6, A5, UI-10).
          if (t.automated)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Row(
                children: [
                  Icon(Icons.bolt_outlined,
                      size: 18, color: Theme.of(context).colorScheme.tertiary),
                  const SizedBox(width: 6),
                  if (user?.has(Privileges.automationView) ?? false)
                    InkWell(
                      onTap: () =>
                          goGuarded(context, '/automation/rules/${t.createdByRuleId}'),
                      child: Text('Created by automation rule #${t.createdByRuleId}',
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.tertiary,
                              fontWeight: FontWeight.w600)),
                    )
                  else
                    Text('Created by automation rule #${t.createdByRuleId}',
                        style: TextStyle(color: Theme.of(context).colorScheme.tertiary)),
                ],
              ),
            ),
          DetailGrid(items: [
            DetailGridItem(label: 'About', child: TaskRecordLink(task: t)),
            DetailGridItem(
              label: 'Customer',
              child: t.customerName == null
                  ? const ReadOnlyValue('—')
                  : mayOpenCustomer
                      ? InkWell(
                          onTap: () => goGuarded(context, '/customers/${t.customerId}'),
                          child: Text(t.customerName!,
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.primary,
                                  fontWeight: FontWeight.w600)),
                        )
                      : Text(t.customerName!),
            ),
            DetailGridItem(
              label: 'Due',
              child: Text(t.dueDate == null ? 'No due date' : formatDate(t.dueDate)),
            ),
            if (t.regionId != null)
              DetailGridItem(
                  label: 'Region', child: ReadOnlyValue(t.regionName ?? '#${t.regionId}')),
            DetailGridItem(
              label: 'Assigned to',
              span: 2,
              child: t.assignees.isEmpty
                  ? const Text('Nobody')
                  : Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: [
                        for (final a in t.assignees)
                          // The seat's own source as the tooltip: "Picked by name", or the role a
                          // rule fanned out to whoever held it. Which seat somebody holds is why
                          // they are being asked, and it is the one thing the chip cannot say in
                          // the space it has (A6, A3).
                          Tooltip(
                            message: a.sourceLabel(t.entityType),
                            child: Chip(
                              avatar: const Icon(Icons.person_outline, size: 16),
                              label: Text(a.display),
                            ),
                          ),
                      ],
                    ),
            ),
          ]),
        ],
      ),
    );
  }
}
