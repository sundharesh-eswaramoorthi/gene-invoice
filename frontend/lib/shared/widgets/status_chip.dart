import 'package:flutter/material.dart';

import '../models/dispute.dart';
import '../models/invoice.dart';
import '../models/payment.dart';
import '../models/promise.dart';

/// Colour-coded status chips for invoices, payments and disputes, in the same shape payment
/// promises have always used (`PromiseStatusChip` in features/promises/promises_tab.dart).
///
/// One palette across all four, so a colour means the same thing wherever it appears:
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
