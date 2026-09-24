import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/models/customer.dart';
import 'package:gene_invoice/shared/models/invoice.dart';
import 'package:gene_invoice/shared/models/payment.dart';
import 'package:gene_invoice/shared/models/promise.dart';

/// THE CLIENT SIDE OF "A CUSTOMER LOGIN IS NOT TOLD WHICH BRANCH" (B1, AUTH-08).
///
/// The server marks regionId/regionName pocRestricted, so a customer login's schema does not
/// carry them and their rows come back with both slots empty. That is a wire shape this client
/// has to read without complaint: every one of these four records is reached by a customer login
/// and each carries B1's two slots, so a non-nullable field or an unguarded `as num` in any of the
/// four would turn "your invoice" into a parse error for exactly the people who cannot be asked to
/// work around it.
///
/// Absent and null are asserted separately on purpose: the server sends null today, but a schema
/// the column is struck from is one field-stripping proxy away from sending neither.
void main() {
  group('a row with no branch on it', () {
    test('an invoice parses with the branch absent and with it null', () {
      for (final row in [_invoice(), _invoice(nulls: true)]) {
        for (final parsed in [
          InvoiceSummary.fromJson(row),
          InvoiceDetail.fromJson({...row, 'items': const []}),
        ]) {
          expect(parsed.regionId, isNull);
          expect(parsed.regionName, isNull);
          expect(parsed.invoiceNumber, 'INV-1');
        }
      }
    });

    test('a payment parses with the branch absent and with it null', () {
      for (final row in [_payment(), _payment(nulls: true)]) {
        final parsed = PaymentRecord.fromJson(row);
        expect(parsed.regionId, isNull);
        expect(parsed.regionName, isNull);
        expect(parsed.amount, 10);
      }
    });

    test('a promise parses with the branch absent and with it null', () {
      for (final row in [_promise(), _promise(nulls: true)]) {
        final parsed = PaymentPromise.fromJson(row);
        expect(parsed.regionId, isNull);
        expect(parsed.regionName, isNull);
        expect(parsed.amount, 90);
      }
    });

    test('a customer parses with the branch absent and with it null', () {
      for (final row in [_customer(), _customer(nulls: true)]) {
        final parsed = Customer.fromJson(row);
        expect(parsed.regionId, isNull);
        expect(parsed.regionName, isNull);
        expect(parsed.name, 'Acme Ltd');
      }
    });
  });
}

Map<String, dynamic> _branchless(Map<String, dynamic> row, bool nulls) =>
    nulls ? {...row, 'regionId': null, 'regionName': null} : row;

Map<String, dynamic> _invoice({bool nulls = false}) => _branchless({
      'id': 1,
      'invoiceNumber': 'INV-1',
      'customerId': 2,
      'customerName': 'Acme Ltd',
      'invoiceDate': '2026-03-01T09:00:00Z',
      'dueDate': '2026-03-31',
      'total': 100,
      'paidAmount': 10,
      'balance': 90,
      'status': 'UNPAID',
    }, nulls);

Map<String, dynamic> _payment({bool nulls = false}) => _branchless({
      'id': 1,
      'customerId': 2,
      'customerName': 'Acme Ltd',
      'amount': 10,
      'paidAt': '2026-03-02T09:00:00Z',
      'status': 'ACTIVE',
    }, nulls);

Map<String, dynamic> _promise({bool nulls = false}) => _branchless({
      'id': 1,
      'customerId': 2,
      'customerName': 'Acme Ltd',
      'amount': 90,
      'promisedDate': '2026-03-10',
      'status': 'OPEN',
    }, nulls);

Map<String, dynamic> _customer({bool nulls = false}) => _branchless({
      'id': 2,
      'name': 'Acme Ltd',
    }, nulls);
