import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/models/user.dart';

void main() {
  test('AppRole parses the backend "email" wire key into the model field', () {
    final withEmail = AppRole.fromJson({
      'id': 7,
      'name': 'BILLING',
      'description': 'Billing desk',
      'email': 'billing@geneinvoice.test',
      'privileges': ['CUSTOMER_VIEW'],
    });
    expect(withEmail.email, 'billing@geneinvoice.test');

    // Absent and null both mean "no role address": the send-time fallback applies instead.
    final without = AppRole.fromJson({
      'id': 8,
      'name': 'PLAIN',
      'privileges': const <String>[],
    });
    expect(without.email, isNull);
  });
}
