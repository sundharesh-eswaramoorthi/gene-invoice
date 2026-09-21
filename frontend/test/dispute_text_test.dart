import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/models/dispute.dart';

Dispute _dispute(Map<String, dynamic> fields) => Dispute.fromJson({
      'id': 1,
      'customerId': 2,
      'openedByUserId': 3,
      'targetType': 'INVOICE',
      'targetId': 9,
      'reason': 'wrong total',
      'status': 'PENDING',
      'createdAt': '2026-09-15T10:00:00Z',
      ...fields,
    });

void main() {
  test('names the disputed record with a formatted amount', () {
    expect(
      disputeTargetText(_dispute({'targetNumber': 'INV-20260915-0003', 'targetAmount': 451234.5})),
      'Invoice INV-20260915-0003 — ₹4,51,234.50',
    );
    expect(
      disputeTargetText(_dispute(
          {'targetType': 'PAYMENT', 'targetId': 158, 'targetNumber': '#158', 'targetAmount': 1000})),
      'Payment #158 — ₹1,000.00',
    );
  });

  test('falls back to the plain summary once the record is gone', () {
    expect(disputeTargetText(_dispute({'targetSummary': 'Invoice #9'})), 'Invoice #9');
    expect(disputeTargetLabel(DisputeTargetType.PAYMENT), 'Payment');
  });
}
