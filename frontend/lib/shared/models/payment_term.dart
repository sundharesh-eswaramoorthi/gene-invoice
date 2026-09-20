/// Payment terms as the backend defines them (`com.geneinvoice.invoice.PaymentTerm`): how many
/// calendar days after the invoice date the money falls due. Only [CUSTOM] has no number of
/// days — its date was chosen by hand on the invoice.
enum PaymentTerm {
  DUE_ON_RECEIPT(0, 'Due on receipt'),
  NET_15(15, 'Net 15'),
  NET_30(30, 'Net 30'),
  NET_45(45, 'Net 45'),
  NET_60(60, 'Net 60'),
  NET_90(90, 'Net 90'),
  CUSTOM(null, 'Custom');

  final int? days;
  final String label;

  const PaymentTerm(this.days, this.label);

  /// The due date these terms give [invoiceDate], or null for [CUSTOM]. Counted by calendar day
  /// rather than by adding hours, so a term never lands a day early across a clock change.
  DateTime? due(DateTime invoiceDate) => days == null
      ? null
      : DateTime(invoiceDate.year, invoiceDate.month, invoiceDate.day + days!);

  /// The invoice date a due date on these terms was worked out from — [due] backwards.
  DateTime? basisOf(DateTime dueDate) => days == null
      ? null
      : DateTime(dueDate.year, dueDate.month, dueDate.day - days!);

  /// What a customer may be given. Custom belongs to one invoice, not to a customer (§2.1), and
  /// a customer with no terms of its own falls back to the system default.
  static List<PaymentTerm> get customerTerms =>
      values.where((t) => t != PaymentTerm.CUSTOM).toList();
}

/// Null for an unknown value, so a term added to the backend later leaves the screen standing
/// and shows the label the server sent with it.
PaymentTerm? parsePaymentTerm(String? wire) => PaymentTerm.values.asNameMap()[wire];
