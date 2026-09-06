import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/models/notification.dart';
import 'package:gene_invoice/shared/models/notification_strategy.dart';

/// Value-level proof of the wire contracts the Flutter side depends on: the optional
/// invoiceItems parse (older payloads and dispute rows have none), the strategy DTO/operator
/// literals matching the backend enums, and the request payload the backend validator reads.
void main() {
  test('notification without invoiceItems parses to null (dispute path unchanged)', () {
    final n = AppNotification.fromJson({
      'id': 5,
      'type': 'DISPUTE_OPENED',
      'title': 'New dispute',
      'message': 'reason',
      'link': '/disputes/1',
      'read': false,
      'createdAt': '2024-06-01T05:00:00Z',
    });
    expect(n.invoiceItems, isNull);
    expect(n.type, 'DISPUTE_OPENED');
  });

  test('strategy notification invoiceItems parse with ids and numbers', () {
    final n = AppNotification.fromJson({
      'id': 6,
      'type': 'STRATEGY_MATCH',
      'title': 'Overdue large invoices',
      'message': '2 invoice(s) newly matched',
      'read': false,
      'createdAt': '2024-06-01T05:00:00Z',
      'invoiceItems': [
        {'invoiceId': 1001, 'invoiceNumber': 'INV-1001'},
        {'invoiceId': 1003, 'invoiceNumber': 'INV-1003'},
      ],
    });
    expect(n.invoiceItems, hasLength(2));
    expect(n.invoiceItems![0].invoiceId, 1001);
    expect(n.invoiceItems![0].invoiceNumber, 'INV-1001');
    expect(n.invoiceItems![1].invoiceNumber, 'INV-1003');
  });

  test('strategy json parses operators as backend enum names and flags active state', () {
    final s = NotificationStrategy.fromJson({
      'id': 3,
      'title': 'Overdue large invoices',
      'description': null,
      'statuses': ['UNPAID', 'PARTIALLY_PAID'],
      'dateOperator': 'BETWEEN',
      'dateFrom': '2024-01-01',
      'dateTo': '2024-01-31',
      'amountOperator': 'GREATER_THAN',
      'amountFrom': 1000.00,
      'amountTo': null,
      'additionalRecipientUserIds': [70, 71],
      'active': false,
    });
    expect(s.statuses, ['UNPAID', 'PARTIALLY_PAID']);
    expect(s.dateOperator, 'BETWEEN');
    expect(s.dateFrom, DateTime.parse('2024-01-01'));
    expect(s.amountOperator, 'GREATER_THAN');
    expect(s.amountFrom, 1000.00);
    expect(s.additionalRecipientUserIds, [70, 71]);
    expect(s.active, isFalse);
    expect(s.description, isNull);
  });

  test('draft payload carries the exact backend field names and omits unused endpoints', () {
    final draft = StrategyDraft(
      title: 'Overdue large invoices',
      description: '',
      statuses: const ['UNPAID'],
      dateOperator: 'AFTER',
      dateFrom: DateTime(2024, 1, 1),
      dateTo: null,
      amountOperator: 'BETWEEN',
      amountFrom: 1000,
      amountTo: 2000,
      additionalRecipientUserIds: const [70],
    );
    final json = draft.toJson();
    expect(json['title'], 'Overdue large invoices');
    expect(json.containsKey('description'), isFalse,
        reason: 'empty description is omitted, matching its optionality');
    expect(json['statuses'], ['UNPAID']);
    expect(json['dateOperator'], 'AFTER');
    expect(json['dateFrom'], '2024-01-01');
    expect(json.containsKey('dateTo'), isFalse,
        reason: 'non-BETWEEN date predicates send no end date');
    expect(json['amountOperator'], 'BETWEEN');
    expect(json['amountFrom'], 1000);
    expect(json['amountTo'], 2000);
    expect(json['additionalRecipientUserIds'], [70]);
  });

  test('operator labels cover exactly the backend operator sets', () {
    expect(dateOperatorLabels.keys.toSet(),
        {'BEFORE', 'ON', 'AFTER', 'BETWEEN'});
    expect(amountOperatorLabels.keys.toSet(),
        {'LESS_THAN', 'EQUAL', 'GREATER_THAN', 'BETWEEN'});
  });
}
