import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/models/credit_note.dart';
import 'package:gene_invoice/shared/models/invoice.dart';

void main() {
  group('CreditNote.fromJson', () {
    test('parses all five recorded facts and the current voided state', () {
      final note = CreditNote.fromJson({
        'id': 7,
        'invoiceId': 42,
        'invoiceNumber': 'INV-2026-0007',
        'amount': 25.50,
        'reason': 'Damaged goods',
        'issuedByUserId': 3,
        'issuedByName': 'admin',
        'issuedAt': '2026-09-01T10:15:30Z',
        'voided': true,
      });

      expect(note.id, 7);
      expect(note.invoiceId, 42);
      expect(note.invoiceNumber, 'INV-2026-0007');
      expect(note.amount, 25.50);
      expect(note.reason, 'Damaged goods');
      expect(note.issuedByUserId, 3);
      expect(note.issuedByName, 'admin');
      expect(note.issuedAt, DateTime.parse('2026-09-01T10:15:30Z'));
      expect(note.voided, isTrue);
    });

    test('an active note reports voided false', () {
      final note = CreditNote.fromJson({
        'id': 8,
        'invoiceId': 42,
        'invoiceNumber': 'INV-2026-0007',
        'amount': 10.00,
        'reason': 'Price correction',
        'issuedByUserId': 3,
        'issuedByName': null,
        'issuedAt': '2026-09-02T08:00:00Z',
        'voided': false,
      });

      expect(note.voided, isFalse);
      expect(note.issuedByName, isNull);
      expect(note.amount, 10.00);
      expect(note.reason, 'Price correction');
    });
  });

  group('IssueCreditNoteResult.fromJson', () {
    Map<String, dynamic> creditNoteJson() => {
          'id': 9,
          'invoiceId': 42,
          'invoiceNumber': 'INV-2026-0007',
          'amount': 12.34,
          'reason': 'Goodwill',
          'issuedByUserId': 3,
          'issuedByName': 'admin',
          'issuedAt': '2026-09-03T12:00:00Z',
          'voided': false,
        };

    test('reads committed issuance with a notification warning as success-with-warning', () {
      final result = IssueCreditNoteResult.fromJson({
        'creditNote': creditNoteJson(),
        'notificationWarning': true,
        'notificationWarningMessage':
            'Credit note issued, but admin notifications could not be delivered',
      });

      // The issuance itself is still a committed success.
      expect(result.creditNote.id, 9);
      expect(result.creditNote.amount, 12.34);
      expect(result.notificationWarning, isTrue);
      expect(result.notificationWarningMessage,
          'Credit note issued, but admin notifications could not be delivered');
    });

    test('reads a clean issuance as success without warning', () {
      final result = IssueCreditNoteResult.fromJson({
        'creditNote': creditNoteJson(),
        'notificationWarning': false,
        'notificationWarningMessage': null,
      });

      expect(result.notificationWarning, isFalse);
      expect(result.notificationWarningMessage, isNull);
    });
  });

  group('InvoiceSummary.fromJson credit position', () {
    test('parses server-derived credited amount and outstanding balance', () {
      final summary = InvoiceSummary.fromJson({
        'id': 42,
        'invoiceNumber': 'INV-2026-0007',
        'customerId': 5,
        'customerName': 'Acme Corp',
        'invoiceDate': '2026-08-15T00:00:00Z',
        'total': 100.00,
        'paidAmount': 40.00,
        'creditedAmount': 25.50,
        'balance': 34.50,
        'status': 'PARTIALLY_PAID',
      });

      expect(summary.total, 100.00);
      expect(summary.paidAmount, 40.00);
      expect(summary.creditedAmount, 25.50);
      expect(summary.balance, 34.50);
      expect(summary.status, InvoiceStatus.PARTIALLY_PAID);
    });

    test('treats a missing creditedAmount as zero for legacy payloads', () {
      final summary = InvoiceSummary.fromJson({
        'id': 43,
        'invoiceNumber': 'INV-2026-0008',
        'customerId': 5,
        'customerName': 'Acme Corp',
        'invoiceDate': '2026-08-16T00:00:00Z',
        'total': 80.00,
        'paidAmount': 0.00,
        'balance': 80.00,
        'status': 'UNPAID',
      });

      expect(summary.creditedAmount, 0.0);
      expect(summary.balance, 80.00);
      expect(summary.status, InvoiceStatus.UNPAID);
    });

    test('maps server status names onto InvoiceStatus values', () {
      expect(parseStatus('UNPAID'), InvoiceStatus.UNPAID);
      expect(parseStatus('PARTIALLY_PAID'), InvoiceStatus.PARTIALLY_PAID);
      expect(parseStatus('FULLY_PAID'), InvoiceStatus.FULLY_PAID);
      expect(parseStatus('CANCELLED'), InvoiceStatus.CANCELLED);
    });
  });
}
