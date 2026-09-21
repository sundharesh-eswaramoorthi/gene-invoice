import 'package:flutter/material.dart';

import '../models/dispute.dart';
import '../models/invoice.dart';
import '../models/payment.dart';
import '../models/promise.dart';
import '../models/task.dart';

/// Colour-coded status chips for invoices, payments, disputes and tasks, in the same shape
/// payment promises have always used (`PromiseStatusChip` in features/promises/promises_tab.dart).
///
/// One palette across all of them, so a colour means the same thing wherever it appears:
///
/// * primary (blue) — still open, waiting on someone
/// * orange — under way, partly done
/// * green — settled, the good ending
/// * error (red) — went wrong: refused or broken
/// * outline (grey) — inert: cancelled or voided, nothing owed and nothing to do
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

/// Lives here rather than with the promise widgets, so every screen can reach it. The chip that
/// uses it, `PromiseStatusChip`, stays in features/promises/promises_tab.dart.
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

/// For the places that only have the raw wire value ("PARTIALLY_PAID") rather than the enum.
/// An unknown value takes the neutral colour instead of guessing.
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

/// A task's status takes the same palette as everything else, which it fits without stretching:
/// open work is waiting on someone, work under way is orange, done is the good ending, and a
/// cancelled task is inert — the work never needed doing. Nothing here is red: a task is never
/// wrong, only late, and lateness is [OverdueBadge]'s to say beside the chip rather than inside
/// it, exactly as an overdue invoice keeps its own status (T9).
Color taskStatusColor(BuildContext context, TaskStatus s) {
  final scheme = Theme.of(context).colorScheme;
  return switch (s) {
    TaskStatus.OPEN => scheme.primary,
    TaskStatus.IN_PROGRESS => Colors.orange.shade800,
    TaskStatus.DONE => Colors.green.shade700,
    TaskStatus.CANCELLED => scheme.outline,
  };
}

/// The chip itself: a tinted pill with the status in its own colour. Same padding, radius and
/// weight as the promise chip, so a row that shows both reads as one design.
class StatusChip extends StatelessWidget {
  final String label;
  final Color color;

  /// Shown after the label, e.g. the promise chip's "set by hand" note.
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

  /// On a list row the due date is in the next column, so the badge there says only "Overdue"
  /// and keeps the day count in its tooltip.
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

/// A task's status. [label] is the server's own wording where there is a task to hand
/// (`Task.statusLabel`), so a status this build has not heard of still reads as whatever the
/// server calls it; a form choosing a status has only the enum, and takes the app's word.
class TaskStatusChip extends StatelessWidget {
  final TaskStatus status;
  final String? label;
  const TaskStatusChip({super.key, required this.status, this.label});

  @override
  Widget build(BuildContext context) => StatusChip(
        label: label ?? taskStatusLabel(status),
        color: taskStatusColor(context, status),
      );
}
