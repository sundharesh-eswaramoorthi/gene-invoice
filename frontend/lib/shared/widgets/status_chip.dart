import 'package:flutter/material.dart';

import '../../features/approvals/approval_models.dart';
import '../../features/automation/automation_models.dart';
import '../../features/tasks/task_models.dart';
import '../models/dispute.dart';
import '../models/invoice.dart';
import '../models/payment.dart';
import '../models/promise.dart';

Color invoiceStatusColor(BuildContext context, InvoiceStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    InvoiceStatus.UNPAID => scheme.primary,
    InvoiceStatus.PARTIALLY_PAID => Colors.orange.shade800,
    InvoiceStatus.FULLY_PAID => Colors.green.shade700,
    InvoiceStatus.CANCELLED => scheme.outline,
  };
}

Color paymentStatusColor(BuildContext context, PaymentStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    PaymentStatus.ACTIVE => Colors.green.shade700,
    PaymentStatus.VOIDED => scheme.outline,
  };
}

Color promiseStatusColor(BuildContext context, PromiseStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    PromiseStatus.OPEN => scheme.primary,
    PromiseStatus.KEPT => Colors.green.shade700,
    PromiseStatus.PARTIALLY_KEPT => Colors.orange.shade800,
    PromiseStatus.BROKEN => scheme.error,
    PromiseStatus.CANCELLED => scheme.outline,
  };
}

Color invoiceStatusColorFromWire(BuildContext context, String? wire) {
  final parsed = InvoiceStatus.values.asNameMap()[wire];
  return parsed == null
      ? Theme.of(context).colorScheme.outline
      : invoiceStatusColor(context, parsed);
}

Color disputeStatusColor(BuildContext context, DisputeStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    DisputeStatus.PENDING => scheme.primary,
    DisputeStatus.APPROVED => Colors.green.shade700,
    DisputeStatus.DENIED => scheme.error,
  };
}

Color approvalStatusColor(BuildContext context, PendingStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    PendingStatus.PENDING => Colors.orange.shade800,
    PendingStatus.APPROVED => Colors.green.shade700,
    PendingStatus.REJECTED || PendingStatus.FAILED => scheme.error,
    PendingStatus.WITHDRAWN || PendingStatus.SUPERSEDED || PendingStatus.CLOSED => scheme.outline,
  };
}

Color taskStatusColor(BuildContext context, TaskStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    TaskStatus.OPEN => scheme.primary,
    TaskStatus.IN_PROGRESS => Colors.orange.shade800,
    TaskStatus.DONE => Colors.green.shade700,
    TaskStatus.CANCELLED => scheme.outline,
  };
}

Color ruleStatusColor(BuildContext context, RuleStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    RuleStatus.ACTIVE => Colors.green.shade700,
    RuleStatus.DISABLED => scheme.outline,
    RuleStatus.SUSPENDED => scheme.error,
  };
}

class StatusChip extends StatelessWidget {
  final String label;
  final Color color;

  final Widget? trailing;

  const StatusChip({super.key, required this.label, required this.color, this.trailing});

  @override
  Widget build(BuildContext context) {
    final chip = Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Text(label,
          style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
    );
    if (trailing == null) return chip;
    return Row(mainAxisSize: MainAxisSize.min, children: [chip, trailing!]);
  }
}

class InvoiceStatusChip extends StatelessWidget {
  final InvoiceStatus status;
  const InvoiceStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) =>
      StatusChip(label: statusLabel(status), color: invoiceStatusColor(context, status));
}

/// Says an invoice is past its due date and by how long (US-A4). Overdue is not a status — it is
/// worked out from today's date and the balance (D3) — so it sits beside the status chip rather
/// than replacing it, in the error colour.
class OverdueBadge extends StatelessWidget {
  final int daysOverdue;

  final bool compact;

  const OverdueBadge({super.key, required this.daysOverdue, this.compact = false});

  static String describe(int days) =>
      days <= 0 ? 'Overdue' : 'Overdue by $days ${days == 1 ? 'day' : 'days'}';

  @override
  Widget build(BuildContext context) {
    final chip = StatusChip(
      label: compact ? 'Overdue' : describe(daysOverdue),
      color: Theme.of(context).colorScheme.error,
    );
    return compact ? Tooltip(message: describe(daysOverdue), child: chip) : chip;
  }
}

class PaymentStatusChip extends StatelessWidget {
  final PaymentStatus status;
  const PaymentStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: status == PaymentStatus.VOIDED ? 'Voided' : 'Active',
        color: paymentStatusColor(context, status),
      );
}

class DisputeStatusChip extends StatelessWidget {
  final DisputeStatus status;
  const DisputeStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: disputeStatusLabel(status),
        color: disputeStatusColor(context, status),
      );
}

/// Where a change held for approval stands (B2).
class ApprovalStatusChip extends StatelessWidget {
  final PendingStatus status;
  const ApprovalStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: pendingStatusLabel(status),
        color: approvalStatusColor(context, status),
      );
}

/// Says a list row has a change held for approval (B2). The change has not taken effect, so the
/// row's own status is still the one shown; this sits beside it rather than replacing it.
class PendingApprovalChip extends StatelessWidget {
  const PendingApprovalChip({super.key});

  static const label = 'Awaiting approval';
  static const tooltip = 'A change is waiting for approval';

  /// [status] as it stands, with this chip beside it when the row's `pendingApproval` is true.
  static Widget beside(Widget status, bool? pendingApproval) {
    if (pendingApproval != true) return status;
    return Wrap(
      spacing: 4,
      runSpacing: 2,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [status, const PendingApprovalChip()],
    );
  }

  @override
  Widget build(BuildContext context) => Tooltip(
        message: tooltip,
        child: StatusChip(label: label, color: approvalStatusColor(context, PendingStatus.PENDING)),
      );
}

/// Where a task stands (A6).
class TaskStatusChip extends StatelessWidget {
  final TaskStatus status;
  const TaskStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: taskStatusLabel(status),
        color: taskStatusColor(context, status),
      );
}

/// Whether a rule acts (A5): on, switched off, or stopped by the engine — then with the reason it
/// gives on hover.
class RuleStatusChip extends StatelessWidget {
  final RuleStatus status;
  final String? suspendedReason;
  const RuleStatusChip({super.key, required this.status, this.suspendedReason});

  @override
  Widget build(BuildContext context) {
    final chip = StatusChip(label: ruleStatusLabel(status), color: ruleStatusColor(context, status));
    if (status != RuleStatus.SUSPENDED) return chip;
    return Tooltip(message: suspendReasonLabel(suspendedReason), child: chip);
  }
}

Color runStatusColor(BuildContext context, RunStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    RunStatus.QUEUED || RunStatus.RUNNING => scheme.primary,
    RunStatus.SUCCEEDED => Colors.green.shade700,
    RunStatus.PARTIAL => Colors.orange.shade800,
    RunStatus.FAILED => scheme.error,
    RunStatus.SKIPPED || RunStatus.SUPERSEDED => scheme.outline,
  };
}

/// A run's status. Given [statusAt] — how it stood on the date viewed (§5.10.10) — that is the
/// chip, with how it stands now beside it when that has moved on.
class RunStatusChip extends StatelessWidget {
  final RunStatus status;
  final RunStatus? statusAt;
  const RunStatusChip({super.key, required this.status, this.statusAt});

  @override
  Widget build(BuildContext context) {
    final shown = statusAt ?? status;
    final chip = StatusChip(label: runStatusLabel(shown), color: runStatusColor(context, shown));
    if (statusAt == null || statusAt == status) return chip;
    return Wrap(
      spacing: 6,
      runSpacing: 2,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        chip,
        Text('now ${runStatusLabel(status)}', style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}
