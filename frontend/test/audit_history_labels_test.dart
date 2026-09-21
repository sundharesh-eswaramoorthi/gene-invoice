import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/audit/audit_history_panel.dart';

import 'support/fake_backend.dart';

// The History tab reads in one voice: every action the server records has a label of its own,
// and the events that exist to show a move say what moved (INV-5).

Map<String, dynamic> _row(String action, {Object? before, Object? after, String? reason}) => {
      'id': 1,
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'action': action,
      'beforeJson': before == null ? null : jsonEncode(before),
      'afterJson': after == null ? null : jsonEncode(after),
      'changedByUserId': 3,
      'changedByUsername': 'jane',
      'disputeId': null,
      'reason': reason,
      'createdAt': '2026-09-18T10:15:00Z',
    };

String? _headline(String action, {Object? before, Object? after}) =>
    AuditEntry.fromJson(_row(action, before: before, after: after)).headline;

const _dueDateMoved = {
  'before': {'dueDate': '2026-01-15', 'paymentTerm': 'NET_15'},
  'after': {'dueDate': '2026-02-14', 'paymentTerm': 'NET_30'},
};

const _documentShared = {
  'before': {
    'id': 8,
    'filename': 'terms.pdf',
    'contentType': 'application/pdf',
    'sizeBytes': 1258291,
    'visibility': 'INTERNAL',
    'description': null,
    'uploadedBy': 'Jane Doe',
  },
  'after': {
    'id': 8,
    'filename': 'terms.pdf',
    'contentType': 'application/pdf',
    'sizeBytes': 1258291,
    'visibility': 'SHARED',
    'description': 'Countersigned',
    'uploadedBy': 'Jane Doe',
  },
};

void main() {
  test('the actions added with due dates and documents are named, not title-cased', () {
    expect(auditActionLabel('INVOICE_DUE_DATE_CHANGED'), 'Due date changed');
    expect(auditActionLabel('INVOICE_DUE_DATES_BACKFILLED'), 'Due dates backfilled');
    expect(auditActionLabel('CUSTOMER_PAYMENT_TERM_CHANGED'), 'Payment terms changed');
    expect(auditActionLabel('DOCUMENT_UPLOADED'), 'Document uploaded');
    expect(auditActionLabel('DOCUMENT_UPDATED'), 'Document updated');
    expect(auditActionLabel('DOCUMENT_DELETED'), 'Document removed');
    expect(auditActionLabel('PRODUCT_CREATED'), 'Product created');
    expect(auditActionLabel('PRODUCT_UPDATED'), 'Product updated');
    expect(auditActionLabel('PRODUCT_ACTIVATED'), 'Product activated');
    expect(auditActionLabel('PRODUCT_DEACTIVATED'), 'Product deactivated');
    expect(auditActionLabel('USER_UPDATED'), 'User updated');
    expect(auditActionLabel('USER_ACTIVATED'), 'User activated');
    expect(auditActionLabel('USER_DEACTIVATED'), 'User deactivated');
    for (final label in [
      for (final action in [
        'INVOICE_DUE_DATE_CHANGED',
        'CUSTOMER_PAYMENT_TERM_CHANGED',
        'DOCUMENT_UPLOADED',
        'PRODUCT_ACTIVATED',
        'USER_DEACTIVATED',
      ])
        auditActionLabel(action)
    ]) {
      expect(label.split(' ').skip(1), everyElement(matches(RegExp(r'^[a-z]'))), reason: label);
    }
  });

  group('the summary says what moved, without opening the snapshots (AC-A8)', () {
    test('a due date, and the terms it now runs on', () {
      expect(
        _headline('INVOICE_DUE_DATE_CHANGED',
            before: _dueDateMoved['before'], after: _dueDateMoved['after']),
        '2026-01-15 → 2026-02-14 · Net 30',
      );
      expect(
        _headline('INVOICE_DUE_DATE_CHANGED',
            before: {'dueDate': null, 'paymentTerm': null},
            after: {'dueDate': '2026-03-01', 'paymentTerm': 'CUSTOM'}),
        '— → 2026-03-01 · Custom',
      );
    });

    test("a customer's terms, old to new", () {
      expect(_headline('CUSTOMER_PAYMENT_TERM_CHANGED', before: 'NET_15', after: 'NET_30'),
          'Net 15 → Net 30');
      // No terms of their own on either side is the system default (D1), not a blank.
      expect(_headline('CUSTOMER_PAYMENT_TERM_CHANGED', after: 'NET_30'),
          'System default → Net 30');
      expect(_headline('CUSTOMER_PAYMENT_TERM_CHANGED', before: 'NET_30'),
          'Net 30 → System default');
      expect(_headline('CUSTOMER_PAYMENT_TERM_CHANGED', before: 'NET_30', after: 'NET_30'), isNull);
    });

    test('a document by name, and the visibility it moved to', () {
      expect(_headline('DOCUMENT_UPLOADED', after: _documentShared['before']), 'terms.pdf');
      expect(_headline('DOCUMENT_DELETED', before: _documentShared['before']), 'terms.pdf');
      expect(
        _headline('DOCUMENT_UPDATED',
            before: _documentShared['before'], after: _documentShared['after']),
        'terms.pdf · Internal → Shared',
      );
      expect(
        _headline('DOCUMENT_UPDATED',
            before: _documentShared['before'], after: _documentShared['before']),
        'terms.pdf',
      );
    });

    test('how many invoices the upgrade filled in', () {
      expect(_headline('INVOICE_DUE_DATES_BACKFILLED', after: {'invoices': 12, 'paymentTerm': 'NET_30'}),
          '12 invoices · Net 30');
      expect(_headline('INVOICE_DUE_DATES_BACKFILLED', after: {'invoices': 1, 'paymentTerm': 'NET_30'}),
          '1 invoice · Net 30');
    });
  });

  testWidgets('the timeline reads label then summary', (tester) async {
    tester.view.physicalSize = const Size(1000, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final backend = FakeBackend({
      'GET /api/audit': (_) => [
            _row('INVOICE_DUE_DATE_CHANGED',
                before: _dueDateMoved['before'], after: _dueDateMoved['after']),
            _row('DOCUMENT_UPLOADED', after: _documentShared['before']),
          ],
    });

    await tester.pumpWidget(ProviderScope(
      overrides: [dioProvider.overrideWithValue(backend.dio)],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: const Scaffold(
          body: SingleChildScrollView(
            child: AuditHistoryPanel(entityType: 'INVOICE', entityId: 42),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Due date changed · 2026-01-15 → 2026-02-14 · Net 30'), findsOneWidget);
    expect(find.text('Document uploaded · terms.pdf'), findsOneWidget);
    expect(find.textContaining('Invoice Due Date Changed'), findsNothing);
  });
}
