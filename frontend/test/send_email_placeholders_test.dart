import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_entity.dart';
import 'package:gene_invoice/features/email/send_email_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

const _staff = CurrentUser(
  id: 3,
  username: 'jane',
  fullName: 'Jane Doe',
  role: 'CASHIER',
  privileges: {Privileges.emailView, Privileges.emailSend},
  customerId: null,
);

const _customerLogin = CurrentUser(
  id: 30,
  username: 'acme',
  fullName: 'Acme Buyer',
  role: 'CUSTOMER',
  privileges: {Privileges.emailView, Privileges.emailSend},
  customerId: 5,
);

Map<String, dynamic> _context({String type = 'INVOICE', bool restricted = false}) => {
      'entityType': type,
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'entityLink': '/invoices/42',
      'delivery': {'configured': false},
      'sender': {
        'restricted': restricted,
        'self': {'userId': 3, 'name': 'Jane Doe', 'email': 'me@x.com'},
      },
      'roles': [],
      'customerEmails': {
        'available': true,
        'addresses': [
          {'name': 'Acme Ltd', 'address': 'ap@acme.com'},
        ],
      },
      'suggestion': null,
    };

const _catalogue = {
  'entityType': 'INVOICE',
  'groups': [
    {
      'level': 'CUSTOMER',
      'label': 'Customer',
      'items': [
        {'token': '{{Customer.Name}}', 'label': 'Name', 'kind': 'TEXT'},
      ],
    },
    {
      'level': 'RECORD',
      'label': 'Invoice',
      'items': [
        {'token': '{{Invoice.Number}}', 'label': 'Number', 'kind': 'TEXT'},
      ],
    },
  ],
};

Map<String, Object? Function(RequestOptions)> _routes({
  Map<String, dynamic>? context,
  bool placeholders = true,
  Object? Function(RequestOptions)? send,
}) =>
    {
      'GET /api/emails/context': (_) => context ?? _context(),
      'POST /api/emails/preview': (_) => {
            'from': {'name': 'Jane Doe', 'address': 'me@x.com', 'internal': true},
            'to': [
              {'name': 'Acme Ltd', 'address': 'ap@acme.com', 'internal': false},
            ],
            'unresolved': [],
            'problems': [],
          },
      if (placeholders) 'GET /api/automation/placeholders': (_) => _catalogue,
      'POST /api/emails': send ??
          (_) => {
                'id': 91,
                'entityType': 'INVOICE',
                'entityId': 42,
                'entityLabel': 'Invoice INV-0042',
                'direction': 'OUTBOUND',
                'status': 'SENT',
                'subject': 'x',
                'body': '',
                'from': {'name': 'Jane Doe', 'address': 'me@x.com', 'internal': true},
                'to': [],
                'cc': [],
                'unresolved': [],
                'attempts': 1,
                'occurredAt': '2026-09-17T10:15:00Z',
              },
    };

Future<FakeBackend> _pump(
  WidgetTester tester, {
  required Map<String, Object? Function(RequestOptions)> routes,
  CurrentUser user = _staff,
  EmailEntityType type = EmailEntityType.invoice,
  bool bulk = false,
  void Function(Object? result)? onResult,
}) async {
  tester.view.physicalSize = const Size(1366, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final backend = FakeBackend(routes);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: TextButton(
              onPressed: () async {
                final result = bulk
                    ? await openBulkEmailParams(context, type: type)
                    : await openSendEmailDialog(context, type: type, entityId: 42);
                onResult?.call(result);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return backend;
}

TextEditingController _field(WidgetTester tester, String label) =>
    tester.widget<TextFormField>(find.widgetWithText(TextFormField, label)).controller!;

Future<void> _insert(WidgetTester tester, String button, String item) async {
  await tester.tap(find.byKey(ValueKey(button)));
  await tester.pumpAndSettle();
  await tester.tap(find.text(item).last);
  await tester.pumpAndSettle();
}

Future<void> _addCustomer(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(ActionChip, 'Customer emails · ap@acme.com'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets("the subject's placeholder button puts {{Invoice.Number}} at the cursor",
      (tester) async {
    final backend = await _pump(tester, routes: _routes());
    expect(backend.sent('GET /api/automation/placeholders').single.queryParameters,
        {'entityType': 'INVOICE'});

    await _addCustomer(tester);
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Invoice  is overdue');
    _field(tester, 'Subject *').selection = const TextSelection.collapsed(offset: 8);
    await _insert(tester, 'email-subject-placeholder', 'Number');

    expect(_field(tester, 'Subject *').text, 'Invoice {{Invoice.Number}} is overdue');
    expect(_field(tester, 'Subject *').selection, const TextSelection.collapsed(offset: 26));

    await tester.tap(find.text('Send'));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    // The server fills it in for this invoice (§5.10.4); the form sends it as written.
    expect(backend.sent('POST /api/emails').single.data['subject'],
        'Invoice {{Invoice.Number}} is overdue');
  });

  testWidgets('after a pick the subject has its focus back with the caret after the token',
      (tester) async {
    await _pump(tester, routes: _routes());
    EditableText subject() => tester.widget<EditableText>(find.descendant(
        of: find.widgetWithText(TextFormField, 'Subject *'), matching: find.byType(EditableText)));

    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Invoice ');
    // On the web the mouse press on {} takes the focus away from the field.
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();
    expect(subject().focusNode.hasFocus, isFalse);
    await _insert(tester, 'email-subject-placeholder', 'Number');

    expect(subject().focusNode.hasFocus, isTrue);
    expect(_field(tester, 'Subject *').text, 'Invoice {{Invoice.Number}}');
    expect(_field(tester, 'Subject *').selection, const TextSelection.collapsed(offset: 26));
    tester.testTextInput.updateEditingValue(const TextEditingValue(
        text: 'Invoice {{Invoice.Number}} is overdue',
        selection: TextSelection.collapsed(offset: 37)));
    await tester.pump();
    expect(_field(tester, 'Subject *').text, 'Invoice {{Invoice.Number}} is overdue');
  });

  testWidgets("the message's button inserts at its own cursor", (tester) async {
    await _pump(tester, routes: _routes());

    await tester.enterText(find.widgetWithText(TextFormField, 'Message'), 'Dear ,\nThanks');
    _field(tester, 'Message').selection = const TextSelection.collapsed(offset: 5);
    await _insert(tester, 'email-body-placeholder', 'Name');

    expect(_field(tester, 'Message').text, 'Dear {{Customer.Name}},\nThanks');
    expect(_field(tester, 'Subject *').text, isEmpty);
  });

  testWidgets('an unknown placeholder is refused under the field it is in, and the form stays',
      (tester) async {
    final backend = await _pump(tester,
        routes: _routes(
            send: (_) => const FakeFailure(400, {
                  'message': 'Validation failed',
                  'fieldErrors': {'subject': 'Unknown placeholder {{Invoice.Numbr}}'},
                })));

    await _addCustomer(tester);
    await tester.enterText(
        find.widgetWithText(TextFormField, 'Subject *'), 'Invoice {{Invoice.Numbr}}');
    await tester.tap(find.text('Send'));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    expect(backend.sent('POST /api/emails'), hasLength(1));
    expect(find.text('Unknown placeholder {{Invoice.Numbr}}'), findsOneWidget);
    expect(find.text('Send email'), findsOneWidget);
    // Shown once, under the subject, not again as the form's own error.
    final subject = tester.getRect(find.widgetWithText(TextFormField, 'Subject *'));
    final error = tester.getRect(find.text('Unknown placeholder {{Invoice.Numbr}}'));
    final message = tester.getRect(find.widgetWithText(TextFormField, 'Message'));
    expect(error.top, greaterThanOrEqualTo(subject.bottom));
    expect(error.bottom, lessThanOrEqualTo(message.top));

    // Put right, the error goes as soon as the text changes, before sending again.
    await tester.enterText(
        find.widgetWithText(TextFormField, 'Subject *'), 'Invoice {{Invoice.Number}}');
    await tester.pump();
    expect(find.text('Unknown placeholder {{Invoice.Numbr}}'), findsNothing);
  });

  testWidgets("an unknown placeholder in the message goes once the message is edited",
      (tester) async {
    await _pump(tester,
        routes: _routes(
            send: (_) => const FakeFailure(400, {
                  'message': 'Validation failed',
                  'fieldErrors': {'body': 'Unknown placeholder {{Customer.Nme}}'},
                })));

    await _addCustomer(tester);
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Hello');
    await tester.enterText(find.widgetWithText(TextFormField, 'Message'), 'Dear {{Customer.Nme}}');
    await tester.tap(find.text('Send'));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    expect(find.text('Unknown placeholder {{Customer.Nme}}'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextFormField, 'Message'), 'Dear {{Customer.Name}}');
    await tester.pump();
    expect(find.text('Unknown placeholder {{Customer.Nme}}'), findsNothing);
  });

  testWidgets('bulk mode hands back the text with its placeholders, for each record to fill',
      (tester) async {
    Object? result;
    await _pump(tester, routes: _routes(), bulk: true, onResult: (r) => result = r);

    await tester.tap(find.widgetWithText(ActionChip, "Customer emails (each record's)"));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Invoice ');
    await _insert(tester, 'email-subject-placeholder', 'Number');
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();

    expect((result as Map)['subject'], 'Invoice {{Invoice.Number}}');
  });

  testWidgets("offers today but not a rule's name, which no hand-written email has",
      (tester) async {
    // The server's catalogue for an invoice, General group and all: it serves rules too.
    const catalogue = {
      'entityType': 'INVOICE',
      'groups': [
        {
          'level': 'CUSTOMER',
          'label': 'Customer',
          'items': [
            {'token': '{{Customer.Name}}', 'label': 'Name', 'kind': 'TEXT'},
          ],
        },
        {
          'level': 'RECORD',
          'label': 'Invoice',
          'items': [
            {'token': '{{Invoice.Number}}', 'label': 'Number', 'kind': 'TEXT'},
          ],
        },
        {
          'level': 'COMMON',
          'label': 'General',
          'items': [
            {'token': '{{Today}}', 'label': 'Today', 'kind': 'DATE'},
            {'token': '{{Rule.Name}}', 'label': 'Rule name', 'kind': 'TEXT'},
          ],
        },
      ],
    };
    await _pump(tester,
        routes: {..._routes(), 'GET /api/automation/placeholders': (_) => catalogue});

    await tester.tap(find.byKey(const ValueKey('email-body-placeholder')));
    await tester.pumpAndSettle();
    expect(find.text('General'), findsOneWidget);
    expect(find.text('Today'), findsOneWidget);
    expect(find.text('Rule name'), findsNothing);
    expect(find.text('Number'), findsOneWidget);

    await tester.tap(find.text('Today'));
    await tester.pumpAndSettle();
    expect(_field(tester, 'Message').text, '{{Today}}');
  });

  testWidgets('a group with nothing left to offer is not shown', (tester) async {
    const catalogue = {
      'entityType': 'INVOICE',
      'groups': [
        {
          'level': 'RECORD',
          'label': 'Invoice',
          'items': [
            {'token': '{{Invoice.Number}}', 'label': 'Number', 'kind': 'TEXT'},
          ],
        },
        {
          'level': 'COMMON',
          'label': 'General',
          'items': [
            {'token': '{{Rule.Name}}', 'label': 'Rule name', 'kind': 'TEXT'},
          ],
        },
      ],
    };
    await _pump(tester,
        routes: {..._routes(), 'GET /api/automation/placeholders': (_) => catalogue});

    await tester.tap(find.byKey(const ValueKey('email-subject-placeholder')));
    await tester.pumpAndSettle();
    expect(find.text('Invoice'), findsOneWidget);
    expect(find.text('General'), findsNothing);
    expect(find.text('Rule name'), findsNothing);
    expect(find.byType(PopupMenuDivider), findsNothing);
  });

  group('no placeholder button', () {
    testWidgets('for a customer login, who writes plain text', (tester) async {
      final backend = await _pump(tester,
          user: _customerLogin, routes: _routes(context: _context(restricted: true)));
      expect(find.byKey(const ValueKey('email-subject-placeholder')), findsNothing);
      expect(find.byKey(const ValueKey('email-body-placeholder')), findsNothing);
      expect(backend.sent('GET /api/automation/placeholders'), isEmpty);
    });

    testWidgets('for a record no placeholders are about', (tester) async {
      final backend = await _pump(tester,
          type: EmailEntityType.product, routes: _routes(context: _context(type: 'PRODUCT')));
      expect(find.byKey(const ValueKey('email-subject-placeholder')), findsNothing);
      expect(backend.sent('GET /api/automation/placeholders'), isEmpty);
    });

    testWidgets('when there are none to offer, with nothing said about it', (tester) async {
      final backend = await _pump(tester, routes: _routes(placeholders: false));
      expect(backend.sent('GET /api/automation/placeholders'), hasLength(1));
      expect(find.byTooltip('Insert placeholder'), findsNothing);
      expect(find.byIcon(Icons.data_object), findsNothing);
      expect(
          [
            for (final t in tester.widgetList<Tooltip>(find.byType(Tooltip)))
              if ((t.message ?? '').isNotEmpty) t.message,
          ],
          isEmpty);
    });

    testWidgets('while a promise offers its customer\'s', (tester) async {
      final backend = await _pump(tester,
          type: EmailEntityType.promise, routes: _routes(context: _context(type: 'PROMISE')));
      expect(backend.sent('GET /api/automation/placeholders').single.queryParameters,
          {'entityType': 'PROMISE'});
      expect(find.byKey(const ValueKey('email-subject-placeholder')), findsOneWidget);
    });
  });
}
