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

  DateTime? due(DateTime invoiceDate) => days == null
      ? null
      : DateTime(invoiceDate.year, invoiceDate.month, invoiceDate.day + days!);

  DateTime? basisOf(DateTime dueDate) => days == null
      ? null
      : DateTime(dueDate.year, dueDate.month, dueDate.day - days!);

  static List<PaymentTerm> get customerTerms =>
      values.where((t) => t != PaymentTerm.CUSTOM).toList();
}

PaymentTerm? parsePaymentTerm(String? wire) => PaymentTerm.values.asNameMap()[wire];
