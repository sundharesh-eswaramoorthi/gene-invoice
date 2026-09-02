import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/invoices/invoices_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/invoice.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

/// Serves canned responses and records every request, so tests read invoice
/// data through the same dio providers the running app uses.
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this._handler);

  final Object? Function(String method, String path, RequestOptions options)
      _handler;
  final List<String> log = [];

  int count(String request) => log.where((r) => r == request).length;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    log.add('${options.method} ${options.uri.path}');
    final result = _handler(options.method, options.uri.path, options);
    if (result is DioException) throw result;
    return ResponseBody.fromString(
      jsonEncode(result),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

const _viewerUser = CurrentUser(
  id: 1,
  username: 'viewer1',
  fullName: 'Vera Viewer',
  role: 'CASHIER',
  privileges: {Privileges.invoiceView},
  customerId: null,
);

const _managerUser = CurrentUser(
  id: 2,
  username: 'manager1',
  fullName: 'Manny Manager',
  role: 'ADMIN',
  privileges: {Privileges.invoiceView, Privileges.invoiceManage},
  customerId: null,
);

final _summaryJson = {
  'id': 42,
  'invoiceNumber': 'INV-0042',
  'customerId': 7,
  'customerName': 'Acme Corp',
  'invoiceDate': '2024-05-01T10:00:00Z',
  'total': 100.0,
  'paidAmount': 0.0,
  'balance': 100.0,
  'activeCreditedTotal': 25.0,
  'outstanding': 75.0,
  'status': 'PARTIALLY_PAID',
};

final _detailJson = {
  ..._summaryJson,
  'notes': 'Deliver to warehouse 3',
  'items': [
    {
      'id': 1,
      'productId': 2,
      'productName': 'Widget',
      'quantity': 4,
      'unitPrice': 25.0,
      'lineTotal': 100.0,
    }
  ],
  'creditNotes': [
    {
      'id': 501,
      'amount': 15.0,
      'reason': 'Damaged on arrival',
      'issuedBy': 'manager1',
      'issuedAt': '2024-05-02T09:30:00Z',
      'status': 'ACTIVE',
    },
    {
      'id': 502,
      'amount': 10.0,
      'reason': 'Goodwill adjustment',
      'issuedBy': 'manager1',
      'issuedAt': '2024-05-03T14:00:00Z',
      'status': 'VOIDED',
    },
  ],
};

void main() {
  group('invoice credit-note model parsing', () {
    test('parses additive credit fields and retained notes', () {
      final inv = InvoiceDetail.fromJson(Map<String, dynamic>.from(_detailJson));

      expect(inv.activeCreditedTotal, 25.0);
      expect(inv.outstanding, 75.0);
      // Legacy keys are untouched.
      expect(inv.balance, 100.0);
      expect(inv.total, 100.0);
      expect(inv.paidAmount, 0.0);

      expect(inv.creditNotes, hasLength(2));
      final active = inv.creditNotes[0];
      expect(active.id, 501);
      expect(active.amount, 15.0);
      expect(active.reason, 'Damaged on arrival');
      expect(active.issuedBy, 'manager1');
      expect(active.status, CreditNoteStatus.ACTIVE);
      expect(active.isVoided, isFalse);

      final voided = inv.creditNotes[1];
      expect(voided.id, 502);
      expect(voided.amount, 10.0);
      expect(voided.reason, 'Goodwill adjustment');
      expect(voided.status, CreditNoteStatus.VOIDED);
      expect(voided.isVoided, isTrue);
    });

    test('stays additive: responses without the new keys render as before', () {
      final summaryJson = Map<String, dynamic>.from(_summaryJson)
        ..remove('activeCreditedTotal')
        ..remove('outstanding');
      final summary = InvoiceSummary.fromJson(summaryJson);
      expect(summary.activeCreditedTotal, 0);
      expect(summary.outstanding, 100.0); // falls back to the legacy balance
      expect(summary.balance, 100.0);

      final detailJson = Map<String, dynamic>.from(summaryJson)
        ..['notes'] = null
        ..['items'] = const [];
      final detail = InvoiceDetail.fromJson(detailJson);
      expect(detail.creditNotes, isEmpty);
      expect(detail.outstanding, 100.0);
    });
  });

  group('invoices screen credit-note experience', () {
    Future<_FakeAdapter> pumpScreen(
      WidgetTester tester, {
      required CurrentUser user,
      Object? warningIssueResponse = const Object(),
      String? voidError,
    }) async {
      late final _FakeAdapter adapter;
      adapter = _FakeAdapter((method, path, options) {
        if (method == 'GET' && path == '/api/invoices') return [_summaryJson];
        if (method == 'GET' && path == '/api/invoices/42') return _detailJson;
        if (method == 'POST' && path == '/api/invoices/42/credit-notes') {
          return {
            'id': 503,
            'amount': 30.0,
            'reason': 'Overcharged',
            'issuedBy': 'manager1',
            'issuedAt': '2024-05-04T08:00:00Z',
            'status': 'ACTIVE',
            if (warningIssueResponse is String) 'warning': warningIssueResponse,
          };
        }
        if (method == 'POST' &&
            path == '/api/invoices/42/credit-notes/501/void' &&
            voidError != null) {
          return DioException(
            requestOptions: options,
            response: Response(
              requestOptions: options,
              statusCode: 400,
              data: {'message': voidError},
            ),
          );
        }
        if (method == 'POST' &&
            path == '/api/invoices/42/credit-notes/501/void') {
          return (_detailJson['creditNotes'] as List).first;
        }
        return <String, dynamic>{};
      });
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            dioProvider.overrideWithValue(Dio()..httpClientAdapter = adapter),
            currentUserProvider.overrideWithValue(user),
          ],
          child: const MaterialApp(home: InvoicesScreen()),
        ),
      );
      await tester.pumpAndSettle();
      return adapter;
    }

    Future<void> openDetail(WidgetTester tester) async {
      await tester.tap(find.text('INV-0042 • Acme Corp'));
      await tester.pumpAndSettle();
    }

    testWidgets('viewer sees credited total, outstanding and both retained '
        'notes with the voided one plainly marked, but no manage controls',
        (tester) async {
      final adapter = await pumpScreen(tester, user: _viewerUser);
      expect(adapter.count('GET /api/invoices'), 1);

      // List row shows the shared outstanding, not a screen-side recomputation.
      expect(find.text('Total 100.00'), findsOneWidget);
      expect(find.text('Credited 25.00'), findsOneWidget);
      expect(find.text('Outstanding 75.00'), findsOneWidget);

      await openDetail(tester);

      // Summary rows in the detail dialog.
      expect(find.text('Credited'), findsOneWidget);
      expect(find.text('Outstanding'), findsOneWidget);
      expect(find.text('75.00'), findsWidgets);

      // Both retained notes remain present, voided plainly identified.
      expect(find.text('15.00 • Damaged on arrival'), findsOneWidget);
      expect(find.text('10.00 • Goodwill adjustment'), findsOneWidget);
      expect(find.text('VOIDED'), findsOneWidget);

      // Viewing is not managing: no issue or void affordances.
      expect(find.text('Issue credit note'), findsNothing);
      expect(find.byTooltip('Void credit note'), findsNothing);
    });

    testWidgets('manager gets issue and void controls on the active note only',
        (tester) async {
      await pumpScreen(tester, user: _managerUser);
      await openDetail(tester);

      expect(find.text('Issue credit note'), findsOneWidget);
      // Exactly one active note => exactly one void affordance.
      expect(find.byTooltip('Void credit note'), findsOneWidget);
    });

    testWidgets('committed issuance refreshes list and detail and shows the '
        'notification warning without failing', (tester) async {
      final adapter = await pumpScreen(
        tester,
        user: _managerUser,
        warningIssueResponse:
            'Credit note issued, but admin notification could not be delivered',
      );
      await openDetail(tester);
      expect(adapter.count('GET /api/invoices/42'), 1);

      await tester.tap(find.text('Issue credit note'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextFormField).at(0), '30');
      await tester.enterText(find.byType(TextFormField).at(1), 'Overcharged');
      await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
      await tester.pumpAndSettle();

      // Committed issuance: issue dialog closed, success path ran, no failure.
      expect(adapter.count('POST /api/invoices/42/credit-notes'), 1);
      // Success invalidates both list and detail providers.
      expect(adapter.count('GET /api/invoices/42'), 2);
      expect(adapter.count('GET /api/invoices'), 2);
      // The warning is presented as a warning, not an error.
      expect(
        find.text(
            'Credit note issued, but admin notification could not be delivered'),
        findsOneWidget,
      );
      expect(find.text('Failed: '), findsNothing);
    });

    testWidgets('failed void stays inline and invalidates nothing',
        (tester) async {
      final adapter = await pumpScreen(
        tester,
        user: _managerUser,
        voidError: 'Credit note is already voided',
      );
      await openDetail(tester);
      expect(adapter.count('GET /api/invoices/42'), 1);

      await tester.tap(find.byTooltip('Void credit note'));
      await tester.pumpAndSettle();
      expect(find.text('Void credit note'), findsOneWidget); // dialog title

      await tester.tap(find.widgetWithText(FilledButton, 'Void'));
      await tester.pumpAndSettle();

      // The failure is shown inline and the dialog stays open.
      expect(find.text('Credit note is already voided'), findsOneWidget);
      expect(find.text('Void credit note'), findsOneWidget);
      // No success invalidation was triggered.
      expect(adapter.count('GET /api/invoices/42'), 1);
      expect(adapter.count('GET /api/invoices'), 1);
    });
  });
}
