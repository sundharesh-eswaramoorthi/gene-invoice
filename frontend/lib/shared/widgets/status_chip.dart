import 'package:flutter/material.dart';

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

/// Where a piece of work has got to. Open is the app's "waiting" primary, in progress its amber,
/// and the two terminal states borrow the endings the rest of the app already uses: finished is
/// green, called off is the same grey a cancelled invoice or a voided payment wears (A6).
Color taskStatusColor(BuildContext context, TaskStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    TaskStatus.OPEN => scheme.primary,
    TaskStatus.IN_PROGRESS => Colors.orange.shade800,
    TaskStatus.DONE => Colors.green.shade700,
    TaskStatus.CANCELLED => scheme.outline,
  };
}

/// The PaymentStatusChip shape. A-UI appends StepStatusChip to this same file two waves later,
/// with no shared hunk (A6, A5).
class TaskStatusChip extends StatelessWidget {
  final TaskStatus status;
  const TaskStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) =>
      StatusChip(label: status.label, color: taskStatusColor(context, status));
}

/// Where one unit of automation work has got to. QUEUED is the app's "waiting" primary and
/// RUNNING its amber, exactly as a task's in-progress is; the three terminal states are the
/// endings the rest of the app already uses — finished is green, SKIPPED is the same grey a
/// cancelled invoice wears, and POISONED is the error colour because something is stuck and
/// somebody has to look (A5).
Color stepStatusColor(BuildContext context, StepStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    StepStatus.QUEUED => scheme.primary,
    StepStatus.RUNNING => Colors.orange.shade800,
    StepStatus.DONE => Colors.green.shade700,
    StepStatus.SKIPPED => scheme.outline,
    StepStatus.POISONED => scheme.error,
  };
}

/// The PaymentStatusChip shape, appended after A-TASKS-UI's TaskStatusChip with no shared hunk,
/// as the file-conflict note mandates (A5, A6).
class StepStatusChip extends StatelessWidget {
  final StepStatus status;
  const StepStatusChip({super.key, required this.status});

  @override
  Widget build(BuildContext context) =>
      StatusChip(label: status.label, color: stepStatusColor(context, status));
}
