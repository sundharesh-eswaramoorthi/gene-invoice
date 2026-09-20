import 'package:flutter/material.dart';

import '../../shared/models/customer.dart';
import '../../shared/models/payment_term.dart';

/// The terms a customer's new invoices default to (US-A1), on the customer form and the detail
/// page. Null is a value of its own — "use the system default" (D1) — so it is the first entry
/// rather than an empty box, and `Custom` belongs to one invoice, never to a customer (§2.1).
class PaymentTermField extends StatelessWidget {
  final PaymentTerm? value;
  final ValueChanged<PaymentTerm?> onChanged;

  /// The floating label on a form. The detail grid writes its own label above the field, so it
  /// passes none and the field stays as short as the ones beside it.
  final String? label;

  const PaymentTermField(
      {super.key, required this.value, required this.onChanged, this.label});

  @override
  Widget build(BuildContext context) => InputDecorator(
        decoration: InputDecoration(labelText: label, isDense: label == null),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<PaymentTerm?>(
            isExpanded: true,
            isDense: true,
            value: value,
            items: [
              const DropdownMenuItem<PaymentTerm?>(
                  value: null, child: Text(Customer.systemDefaultTerms)),
              for (final t in PaymentTerm.customerTerms)
                DropdownMenuItem<PaymentTerm?>(value: t, child: Text(t.label)),
            ],
            onChanged: onChanged,
          ),
        ),
      );
}
